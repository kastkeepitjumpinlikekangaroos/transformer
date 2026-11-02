package com.transformer.read.csv

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}

class CsvReaderTest {

  private def tmpCsv(name: String, contents: String): Path = {
    val dir = Files.createTempDirectory("transformer-csv-")
    val p = dir.resolve(name)
    Files.writeString(p, contents)
    p
  }

  /** Partition 0 as one batch, however many batches the reader cut it into:
    * the batch size is `ColumnarBatch.DefaultCapacity`, which the whole suite
    * also runs at 1 and 2 rows (docs/testing.md). */
  private def wholePartition(r: CsvReader): ColumnarBatch = {
    val parts = r.readPartition(0).toVector
    val n = parts.map(_.numRows).sum
    val out = new ColumnarBatch(r.schema, math.max(1, n))
    var row = 0
    parts.foreach { b =>
      var i = 0
      while (i < b.numRows) {
        var c = 0
        while (c < r.schema.length) { b.column(c).copyTo(i, out.column(c), row); c += 1 }
        row += 1
        i += 1
      }
    }
    out.setNumRows(n)
    out
  }

  @Test def inferSchemaIntAndString(): Unit = {
    val p = tmpCsv("data.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(2, r.schema.length)
    assertEquals(DataType.IntType, r.schema.fields(0).dataType)
    assertEquals(DataType.StringType, r.schema.fields(1).dataType)
    val b = wholePartition(r)
    assertEquals(3, b.numRows)
    assertEquals(1, b.column(0).asInstanceOf[IntVector].get(0))
    assertEquals("alice", b.column(1).asInstanceOf[StringVector].get(0))
    assertEquals(3, b.column(0).asInstanceOf[IntVector].get(2))
    assertEquals("charlie", b.column(1).asInstanceOf[StringVector].get(2))
  }

  @Test def inferSchemaWidensIntToDouble(): Unit = {
    val p = tmpCsv("nums.csv", "x\n1\n2\n3.14\n4\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.DoubleType, r.schema.fields(0).dataType)
  }

  @Test def inferSchemaBoolean(): Unit = {
    val p = tmpCsv("b.csv", "flag\ntrue\nfalse\nTRUE\nFalse\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.BooleanType, r.schema.fields(0).dataType)
  }

  @Test def inferSchemaDate(): Unit = {
    val p = tmpCsv("d.csv", "d\n2026-01-01\n2025-12-31\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.DateType, r.schema.fields(0).dataType)
  }

  @Test def nullValueHandling(): Unit = {
    val p = tmpCsv("nulls.csv", "id,name\n1,alice\n2,\n3,charlie\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    val b = wholePartition(r)
    assertFalse(b.column(1).isNull(0))
    assertTrue(b.column(1).isNull(1))
    assertFalse(b.column(1).isNull(2))
  }

  @Test def explicitColumnsNoInfer(): Unit = {
    val p = tmpCsv("e.csv", "1,alice\n2,bob\n")
    val schema = Seq(Field("id", DataType.IntType), Field("name", DataType.StringType))
    val opts = CsvOptions(inferSchema = false, header = false, columns = Some(schema))
    val r = CsvReader.fromPath(p.toString, opts)
    val b = wholePartition(r)
    assertEquals(2, b.numRows)
    assertEquals(1, b.column(0).asInstanceOf[IntVector].get(0))
    assertEquals("bob", b.column(1).asInstanceOf[StringVector].get(1))
  }

  @Test def quotedFieldsWithDelimiter(): Unit = {
    val p = tmpCsv("q.csv", "name,note\n\"smith, john\",hello\n\"doe, jane\",\"hi, there\"\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    val b = wholePartition(r)
    assertEquals("smith, john", b.column(0).asInstanceOf[StringVector].get(0))
    assertEquals("hi, there", b.column(1).asInstanceOf[StringVector].get(1))
  }

  @Test def globExpansion(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-glob-")
    Files.writeString(dir.resolve("a.csv"), "x\n1\n")
    Files.writeString(dir.resolve("b.csv"), "x\n2\n")
    Files.writeString(dir.resolve("ignore.txt"), "y\n3\n")
    val r = CsvReader.fromPath(dir.toString + "/*.csv", CsvOptions())
    assertEquals(2, r.numPartitions)
    val all = (0 until r.numPartitions).flatMap { p =>
      val b = Closing.draining(r.readPartition(p))(_.next())
      (0 until b.numRows).map(b.column(0).asInstanceOf[IntVector].get)
    }.sorted
    assertEquals(Seq(1, 2), all)
  }

  @Test def globMatchesBareDirectory(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-dir-")
    Files.writeString(dir.resolve("a.csv"), "x\n5\n")
    Files.writeString(dir.resolve("b.csv"), "x\n10\n")
    val r = CsvReader.fromPath(dir.toString, CsvOptions())
    assertEquals(2, r.numPartitions)
  }

  @Test def batchSplittingAtCapacity(): Unit = {
    val sb = new StringBuilder("x\n")
    (0 until 10).foreach(i => sb.append(i).append('\n'))
    val p = tmpCsv("many.csv", sb.toString)
    val opts = CsvOptions(batchSize = 4)
    val r = CsvReader.fromPath(p.toString, opts)
    val batches = r.readPartition(0).toList
    // 10 rows / batch of 4 → 4 + 4 + 2 = 3 batches
    assertEquals(3, batches.size)
    assertEquals(4, batches(0).numRows)
    assertEquals(4, batches(1).numRows)
    assertEquals(2, batches(2).numRows)
  }

  @Test def emptyMatchThrows(): Unit = {
    try {
      CsvReader.fromPath("/tmp/does-not-exist-" + System.nanoTime() + "/*.csv", CsvOptions())
      fail("expected exception")
    } catch { case _: IllegalArgumentException => () }
  }

  // ---- regressions: plans/robustness/06 ----------------------------------

  private def rows(r: CsvReader): Seq[Seq[Any]] =
    (0 until r.numPartitions).flatMap { p =>
      r.readPartition(p).flatMap { b =>
        (0 until b.numRows).map(i => (0 until b.schema.length).map { c =>
          b.column(c).getBoxed(i) match {
            case a: Array[Byte] => a.toSeq
            case v => v
          }
        })
      }
    }

  private def explicitColumns(cols: (String, DataType)*): CsvOptions = explicit(cols: _*)

  private def explicit(cols: (String, DataType)*): CsvOptions =
    CsvOptions(inferSchema = false, columns = Some(cols.map { case (n, t) => Field(n, t) }))

  private def failure(body: => Any): String =
    try { body; fail("expected a failure"); "" }
    catch {
      case e: AssertionError => throw e
      case e: IllegalArgumentException => e.getMessage
    }

  /** The writer writes a NULL in a one-column table as a blank line; the
    * reader skipped blank lines, so the row was lost (here 3 rows read as 2). */
  @Test def aOneColumnFileKeepsItsNullRows(): Unit = {
    val p = tmpCsv("one.csv", "x\n1\n\n3\n")
    assertEquals(Seq(Seq(1), Seq(null), Seq(3)), rows(CsvReader.fromPath(p.toString, explicit("x" -> DataType.IntType))))
    val inferred = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(DataType.IntType, inferred.schema.fields(0).dataType)
    assertEquals(Seq(Seq(1), Seq(null), Seq(3)), rows(inferred))
    // A wider file still skips them: the writer never writes one there.
    assertEquals(Seq(Seq(1, 2), Seq(3, 4)), rows(CsvReader.fromPath(tmpCsv("two.csv", "a,b\n1,2\n\n3,4\n\n").toString, CsvOptions())))
    // With no header, blank lines before the first value are rows too, and
    // they are the first rows of the inference sample.
    val headerless = tmpCsv("h.csv", "\n\n7\n").toString
    val two = CsvReader.fromPath(headerless, CsvOptions(header = false, inferenceSampleRows = 2))
    assertEquals((DataType.StringType, Seq(Seq(null), Seq(null), Seq("7"))), (two.schema.fields(0).dataType, rows(two)))
    val three = CsvReader.fromPath(headerless, CsvOptions(header = false, inferenceSampleRows = 3))
    assertEquals((DataType.IntType, Seq(Seq(null), Seq(null), Seq(7))), (three.schema.fields(0).dataType, rows(three)))
  }

  /** `""` is the empty string in a STRING column, where it used to be NULL;
    * in a column of any other type an empty field is NULL, quoted or not. */
  @Test def anEmptyStringReadsApartFromNull(): Unit = {
    val p = tmpCsv("e.csv", "s,n\n\"\",\"\"\n,\na,1\n")
    assertEquals(Seq(Seq("", null), Seq(null, null), Seq("a", 1)),
      rows(CsvReader.fromPath(p.toString, explicit("s" -> DataType.StringType, "n" -> DataType.IntType))))
    // A custom null string: unquoted it is NULL, quoted it is text.
    val q = tmpCsv("n.csv", "s\nNULL\n\"NULL\"\n\n")
    assertEquals(Seq(Seq(null), Seq("NULL"), Seq("")),
      rows(CsvReader.fromPath(q.toString, explicit("s" -> DataType.StringType).copy(nullValue = "NULL"))))
  }

  /** The writer writes a BINARY as Base64, and the reader used to throw
    * `Cannot set BinaryVector from java.lang.String`. A BINARY has an empty
    * value, as a STRING does, so `""` is the empty byte string. */
  @Test def aBinaryColumnReadsBackFromBase64(): Unit = {
    val p = tmpCsv("b.csv", "b\nAQI=\n\n\"\"\n/w==\n")
    assertEquals(Seq(Seq(Seq[Byte](1, 2)), Seq(null), Seq(Seq[Byte]()), Seq(Seq[Byte](-1))),
      rows(CsvReader.fromPath(p.toString, explicit("b" -> DataType.BinaryType))))
    val msg = failure(rows(CsvReader.fromPath(tmpCsv("bad.csv", "b\nnot base64!\n").toString, explicit("b" -> DataType.BinaryType))))
    assertTrue(msg, msg.contains("'not base64!'") && msg.contains("binary"))
  }

  /** A row with more or fewer fields than the file has columns used to be
    * read anyway: the extra fields dropped, the missing ones NULL. */
  @Test def aRowWithTheWrongNumberOfFieldsFails(): Unit = {
    val extra = failure(rows(CsvReader.fromPath(tmpCsv("x.csv", "a,b\n1,2\n1,2,3\n").toString, CsvOptions())))
    assertTrue(extra, extra.contains("x.csv") && extra.contains("line 3") && extra.contains("3 fields") && extra.contains("its header has 2"))
    val missing = failure(rows(CsvReader.fromPath(tmpCsv("m.csv", "a,b,c\n1,2\n").toString, CsvOptions())))
    assertTrue(missing, missing.contains("line 2") && missing.contains("2 fields") && missing.contains("has 3"))
    val schema = failure(rows(CsvReader.fromPath(tmpCsv("s.csv", "1\n").toString,
      explicit("a" -> DataType.IntType, "b" -> DataType.IntType).copy(header = false))))
    assertTrue(schema, schema.contains("1 field,") && schema.contains("the schema has 2"))
    // A line of spaces is a row of one field, not a blank line.
    val spaces = failure(rows(CsvReader.fromPath(tmpCsv("w.csv", "a,b\n1,2\n   \n").toString, CsvOptions())))
    assertTrue(spaces, spaces.contains("line 3"))
  }

  /** A UTF-8 byte-order mark used to start the first column's name. */
  @Test def aByteOrderMarkIsNotPartOfTheFirstName(): Unit = {
    val p = tmpCsv("bom.csv", s"${0xfeff.toChar}id,name\n1,a\n")
    val r = CsvReader.fromPath(p.toString, CsvOptions())
    assertEquals(Seq("id", "name"), r.schema.fieldNames)
    assertEquals(Seq(Seq(1, "a")), rows(r))
  }

  /** Bytes that are no UTF-8 used to read as U+FFFD. The line is the one the
    * bytes are on, though the decoder reads ahead of the parser. */
  @Test def bytesThatAreNoTextFailTheRead(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-")
    val p = dir.resolve("latin1.csv")
    Files.write(p, Array[Byte]('s', '\n', 'c', 'a', 'f', 0xe9.toByte, '\n'))
    val msg = failure(rows(CsvReader.fromPath(p.toString, CsvOptions())))
    assertTrue(msg, msg.contains("latin1.csv") && msg.contains("line 2") && msg.contains("UTF-8"))
    val far = dir.resolve("far.csv")
    Files.write(far, ("s\n" + "x\r\n" * 20000 + "y\r").getBytes(java.nio.charset.StandardCharsets.UTF_8) ++
      Array[Byte](0xff.toByte, '\n'))
    val farMsg = failure(rows(CsvReader.fromPath(far.toString, explicitColumns("s" -> DataType.StringType))))
    assertTrue(farMsg, farMsg.contains("line 20003"))
    assertEquals(Seq(Seq("caf" + 0xe9.toChar)),
      rows(CsvReader.fromPath(p.toString, CsvOptions(charset = java.nio.charset.StandardCharsets.ISO_8859_1))))
  }

  /** An empty file's message named it unquoted, `CSV file /x/a.csv is
    * empty`, where every other message quotes its path; a gzip-compressed
    * CSV (five of DuckDB's data/csv files) was refused as `line 1 is not
    * valid UTF-8`, and read as garbage under a charset that decodes every
    * byte. It is refused as compressed, naming the file (plans/testing/13,
    * finding 6). */
  @Test def anEmptyOrCompressedFileIsRefusedNamingIt(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-")
    val empty = dir.resolve("empty.csv")
    Files.write(empty, Array.emptyByteArray)
    assertEquals(s"CSV file '$empty' is empty", failure(CsvReader.fromPath(empty.toString, CsvOptions())))
    val gz = dir.resolve("data.csv.gz")
    val bytes = new java.io.ByteArrayOutputStream
    val zip = new java.util.zip.GZIPOutputStream(bytes)
    zip.write("a,b\n1,2\n".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    zip.close()
    Files.write(gz, bytes.toByteArray)
    val compressed = s"CSV file '$gz' is compressed with gzip, and CSV is read as text: decompress it first"
    assertEquals(compressed, failure(CsvReader.fromPath(gz.toString, CsvOptions())))
    assertEquals(compressed, failure(rows(CsvReader.fromPath(gz.toString, explicitColumns("a" -> DataType.StringType)))))
    assertEquals(compressed,
      failure(CsvReader.fromPath(gz.toString, CsvOptions(charset = java.nio.charset.StandardCharsets.ISO_8859_1))))
  }

  /** A field that does not read as its column's type fails naming the file,
    * line, column and text, where it used to throw a bare
    * NumberFormatException, or for a BOOLEAN read as false. */
  @Test def aValueThatDoesNotReadAsItsTypeFailsNamingIt(): Unit = {
    val msg = failure(rows(CsvReader.fromPath(tmpCsv("v.csv", "id,n\n1,2\n2,abc\n").toString,
      explicit("id" -> DataType.IntType, "n" -> DataType.IntType))))
    assertTrue(msg, msg.contains("v.csv") && msg.contains("line 3") && msg.contains("'n'") && msg.contains("'abc'") && msg.contains("int"))
    // A value past the sample that does not read as its column's type fails
    // naming where the type came from.
    val dir = Files.createTempDirectory("transformer-csv-glob-")
    Files.writeString(dir.resolve("a.csv"), "flag\ntrue\nfalse\n")
    Files.writeString(dir.resolve("b.csv"), "flag\nyes\nno\n")
    val bool = failure(rows(CsvReader.fromPath(dir.toString, CsvOptions(inferenceSampleRows = 2))))
    assertTrue(bool, bool.contains("b.csv") && bool.contains("'yes'") && bool.contains("boolean") &&
      bool.contains("inferred from the first 2 rows of") && bool.contains("a.csv"))
    // Within the sample, inference reads every value as what it is.
    Files.writeString(dir.resolve("b.csv"), "flag\nTRUE\nFalse\n")
    assertEquals(Seq(true, false, true, false), rows(CsvReader.fromPath(dir.toString, CsvOptions())).map(_.head))
  }

  /** Inference sampled the first file alone, so a glob whose first file held
    * a header and no rows (what a writer leaves for an output partition that
    * held none, as a sharded GROUP BY writes) typed every column STRING, and a
    * downstream SUM of a count was refused; and a two-row first file decided
    * the type of every file after it. The sample is the first rows of the
    * glob's files in order. */
  @Test def inferenceSamplesTheGlobsFilesInOrder(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-sample-")
    Files.writeString(dir.resolve("part-00000.csv"), "tier,customers\n")
    Files.writeString(dir.resolve("part-00001.csv"), "tier,customers\ngold,2\n")
    Files.writeString(dir.resolve("part-00002.csv"), "tier,customers\nsilver,1\n")
    val r = CsvReader.fromPath(dir.toString, CsvOptions())
    assertEquals(Vector(DataType.StringType, DataType.IntType), r.schema.fields.map(_.dataType))
    assertEquals(Seq(Seq("gold", 2), Seq("silver", 1)), rows(r))
    // A later file's values join the sample's types while the sample lasts.
    Files.writeString(dir.resolve("part-00002.csv"), "tier,customers\nsilver,1.5\n")
    assertEquals(DataType.DoubleType, CsvReader.fromPath(dir.toString, CsvOptions()).schema.fields(1).dataType)
    // A sample the first file fills reads nothing after it.
    assertEquals(DataType.IntType,
      CsvReader.fromPath(dir.toString, CsvOptions(inferenceSampleRows = 1)).schema.fields(1).dataType)
    // No row anywhere: every column is a STRING, as for one empty file.
    Files.writeString(dir.resolve("part-00001.csv"), "tier,customers\n")
    Files.writeString(dir.resolve("part-00002.csv"), "tier,customers\n")
    assertEquals(Vector(DataType.StringType, DataType.StringType),
      CsvReader.fromPath(dir.toString, CsvOptions()).schema.fields.map(_.dataType))
  }

  /** A later file's header was skipped and its fields read in the first
    * file's order, so a file that wrote the columns in another order read
    * each value into the other column, silently where the two had one type.
    * Each file is read by its own header's names, regardless of case. */
  @Test def aLaterFileIsReadByItsHeadersNames(): Unit = {
    val dir = Files.createTempDirectory("transformer-csv-names-")
    Files.writeString(dir.resolve("a.csv"), "first,last,n\nAda,Lovelace,1\n")
    Files.writeString(dir.resolve("b.csv"), "LAST,n,First\nHopper,2,Grace\n")
    val r = CsvReader.fromPath(dir.toString, CsvOptions())
    assertEquals(Vector("first", "last", "n"), r.schema.fieldNames)
    assertEquals(Seq(Seq("Ada", "Lovelace", 1), Seq("Grace", "Hopper", 2)), rows(r))
    // A file naming other columns fails, naming both headers.
    Files.writeString(dir.resolve("b.csv"), "first,surname,n\nGrace,Hopper,2\n")
    val other = failure(rows(CsvReader.fromPath(dir.toString, CsvOptions())))
    assertTrue(other, other.contains("b.csv") && other.contains("[first, surname, n]") &&
      other.contains("a.csv") && other.contains("[first, last, n]"))
    // So does one with a column more or fewer.
    Files.writeString(dir.resolve("b.csv"), "first,last\nGrace,Hopper\n")
    assertTrue(failure(rows(CsvReader.fromPath(dir.toString, CsvOptions()))).contains("must name the same columns"))
    // Declared columns name the columns themselves: each file's header is
    // skipped, and its fields are read in the declared order.
    Files.writeString(dir.resolve("b.csv"), "x,y,z\nGrace,Hopper,2\n")
    assertEquals(Seq(Seq("Ada", "Lovelace", 1), Seq("Grace", "Hopper", 2)), rows(CsvReader.fromPath(dir.toString,
      explicit("first" -> DataType.StringType, "last" -> DataType.StringType, "n" -> DataType.IntType))))
  }

  /** A declared DECIMAL kept the text's own scale and precision, so a column
    * held values wider than its type, which a parquet write then refused. */
  @Test def aDecimalReadsAtItsDeclaredScaleOrFails(): Unit = {
    val t = DataType.DecimalType(9, 2)
    assertEquals(Seq("1.50", "-0.25", "100000.00", "0.00").map(new java.math.BigDecimal(_)),
      rows(CsvReader.fromPath(tmpCsv("d.csv", "d\n1.5\n-0.25\n1e5\n0\n").toString, explicit("d" -> t))).map(_.head))
    for (bad <- Seq("1.555", "12345678.00", "1d", "NaN")) {
      val msg = failure(rows(CsvReader.fromPath(tmpCsv("d.csv", s"d\n$bad\n").toString, explicit("d" -> t))))
      assertTrue(msg, msg.contains(s"'$bad'") && msg.contains("decimal(9,2)"))
    }
  }

  /** Inference used to widen along one ladder, INT to BIGINT to DOUBLE to
    * BOOLEAN to DATE to TIMESTAMP to STRING, so a column of 1.5 and true was a
    * BOOLEAN that read 1.5 as false, and one of 1 and a date a DATE the read
    * then failed on. Only types that read each other's values widen now. */
  @Test def inferenceWidensOnlyToATypeEveryValueReadsAs(): Unit = {
    def infer(values: String*): (DataType, Seq[Any]) = {
      val r = CsvReader.fromPath(tmpCsv("i.csv", ("v" +: values).mkString("", "\n", "\n")).toString, CsvOptions())
      (r.schema.fields(0).dataType, rows(r).map(_.head))
    }
    assertEquals((DataType.StringType, Seq("1.5", "true")), infer("1.5", "true"))
    assertEquals((DataType.StringType, Seq("1", "true")), infer("1", "true"))
    assertEquals((DataType.StringType, Seq("1", "2024-01-01")), infer("1", "2024-01-01"))
    assertEquals((DataType.StringType, Seq("true", "2024-01-01")), infer("true", "2024-01-01"))
    assertEquals((DataType.DoubleType, Seq(1.0, 2.5, 30.0)), infer("1", "2.5", "3e1"))
    assertEquals((DataType.LongType, Seq(1L, 3000000000L)), infer("1", "3000000000"))
    // A date beside a timestamp is that day's midnight UTC.
    assertEquals((DataType.TimestampType, Seq("2024-01-01T00:00:00Z", "2024-01-01T12:30:00Z").map(java.time.Instant.parse)),
      infer("2024-01-01", "2024-01-01T12:30:00"))
  }

  /** Numbers were read with Java's grammar: `1d` and ` 1` were the DOUBLE 1.0,
    * `0x1p3` was 8.0 and Arabic-Indic digits an INT. Only plain decimals are
    * numbers now; `NaN` and `Infinity`, which the writer writes, still are. */
  @Test def inferenceReadsOnlyPlainNumbers(): Unit = {
    def typeOf(v: String): DataType =
      CsvReader.fromPath(tmpCsv("g.csv", s"v\n$v\n").toString, CsvOptions()).schema.fields(0).dataType
    for (v <- Seq("1d", "1f", "0x1p3", " 1", "1 ", "1_000", 0x0661.toChar.toString + 0x0662.toChar, "nan", "inf", "1e", "."))
      assertEquals(s"'$v'", DataType.StringType, typeOf(v))
    for ((v, t) <- Seq("+5" -> DataType.IntType, "-0" -> DataType.IntType, "007" -> DataType.IntType,
        ".5" -> DataType.DoubleType, "5." -> DataType.DoubleType, "-1e-5" -> DataType.DoubleType,
        "NaN" -> DataType.DoubleType, "Infinity" -> DataType.DoubleType, "-Infinity" -> DataType.DoubleType))
      assertEquals(s"'$v'", t, typeOf(v))
  }

  /** A number no DOUBLE or FLOAT holds read as one: `1e400` as an infinity
    * and `1e-400` as 0, and an integer of 400 digits inferred a DOUBLE column
    * holding an infinity. Each is no value of the type now, as it is to a
    * CAST, which reads the same grammar (plans/robustness/24): text, to
    * inference, and a failure naming it in a declared column. */
  @Test def aNumberTheTypeCannotHoldIsNoValueOfIt(): Unit = {
    def typeOf(v: String): DataType =
      CsvReader.fromPath(tmpCsv("g.csv", s"v\n$v\n").toString, CsvOptions()).schema.fields(0).dataType
    for (v <- Seq("1e400", "-1e400", "1e-400", "1" + "0" * 400))
      assertEquals(s"'$v'", DataType.StringType, typeOf(v))
    assertEquals(DataType.DoubleType, typeOf("1" + "0" * 40))
    for ((v, t) <- Seq("1e400" -> DataType.DoubleType, "1e-400" -> DataType.DoubleType, "1e40" -> DataType.FloatType,
        "1e-50" -> DataType.FloatType)) {
      val msg = failure(rows(CsvReader.fromPath(tmpCsv("h.csv", s"v\n$v\n").toString, explicit("v" -> t))))
      assertTrue(msg, msg.contains(s"'$v'") && msg.contains(t.name))
    }
    assertEquals(Seq(Double.MaxValue, Double.MinPositiveValue, 0.0),
      rows(CsvReader.fromPath(tmpCsv("e.csv", "v\n1.7976931348623157e308\n4.9e-324\n0e400\n").toString,
        explicit("v" -> DataType.DoubleType))).map(_.head))
  }

  /** A date or a timestamp past what its column stores is no value of it
    * (plans/robustness/25): text to inference, and a failure naming it in a
    * declared column, while the first and last of each read back. The
    * earliest microsecond failed: its product overflowed before its fraction
    * brought it back. */
  @Test def aDateOrTimestampPastItsRangeIsNoValueOfIt(): Unit = {
    val lastDay = java.time.LocalDate.ofEpochDay(Int.MaxValue)
    val firstMicro = java.time.Instant.EPOCH.plus(Long.MinValue, java.time.temporal.ChronoUnit.MICROS)
    val lastMicro = java.time.Instant.EPOCH.plus(Long.MaxValue, java.time.temporal.ChronoUnit.MICROS)
    def typeOf(v: String): DataType =
      CsvReader.fromPath(tmpCsv("g.csv", s"v\n$v\n").toString, CsvOptions()).schema.fields(0).dataType
    for (v <- Seq(lastDay.plusDays(1).toString, "+999999999-12-31", lastMicro.plusNanos(1000).toString))
      assertEquals(v, DataType.StringType, typeOf(v))
    assertEquals(Seq(lastDay), rows(CsvReader.fromPath(tmpCsv("d.csv", s"v\n$lastDay\n").toString,
      explicit("v" -> DataType.DateType))).map(_.head))
    assertEquals(Seq(firstMicro, lastMicro), rows(CsvReader.fromPath(tmpCsv("t.csv", s"v\n$firstMicro\n$lastMicro\n").toString,
      explicit("v" -> DataType.TimestampType))).map(_.head))
    for ((v, t) <- Seq(lastDay.plusDays(1).toString -> DataType.DateType,
        lastMicro.plusNanos(1000).toString -> DataType.TimestampType)) {
      val msg = failure(rows(CsvReader.fromPath(tmpCsv("h.csv", s"v\n$v\n").toString, explicit("v" -> t))))
      assertTrue(msg, msg.contains(s"'$v'") && msg.contains(t.name))
    }
  }

  /** A header names the columns, so each name must be one and its own,
    * compared regardless of case as a query compares them. `a,a,,B,b` loaded
    * as columns `a`, `a`, `` , `B` and `b`, and a query failed on `a`
    * (plans/robustness/26). Without a header, or with declared columns, the
    * header's text names nothing and is not checked. */
  @Test def aHeaderThatRepeatsOrLeavesOutANameFails(): Unit = {
    for ((header, expected) <- Seq(
           "a,a,,B,b" -> "has a column with no name in its header, column 3; name it",
           "a,b,A" -> "names more than one column 'a' in its header, columns 1 (a) and 3 (A), names being read regardless of case",
           "x,y,X,Y" -> "names more than one column 'x' in its header, columns 1 (x) and 3 (X)")) {
      val file = tmpCsv("h.csv", s"$header\n" + header.split(",", -1).indices.map(_.toString).mkString(",") + "\n")
      val msg = failure(CsvReader.fromPath(file.toString, CsvOptions()))
      assertTrue(msg, msg.startsWith(s"CSV file '$file' $expected"))
    }
    val declared = tmpCsv("d.csv", "a,a\n1,2\n")
    assertEquals(Seq(Seq(1, 2)), rows(CsvReader.fromPath(declared.toString,
      explicit("x" -> DataType.IntType, "y" -> DataType.IntType).copy(header = true))))
  }

  /** An integer past BIGINT used to infer DOUBLE and lose its digits
    * (1.0E20). It is a DECIMAL(38,0) now, exact, up to 38 digits. */
  @Test def anIntegerPastBigintInfersAnExactDecimal(): Unit = {
    val r = CsvReader.fromPath(tmpCsv("big.csv", "n\n99999999999999999999\n-9223372036854775809\n1\n").toString, CsvOptions())
    assertEquals(DataType.DecimalType(38, 0), r.schema.fields(0).dataType)
    assertEquals(Seq("99999999999999999999", "-9223372036854775809", "1").map(new java.math.BigDecimal(_)), rows(r).map(_.head))
    def typeOf(values: String*): DataType =
      CsvReader.fromPath(tmpCsv("w.csv", ("n" +: values).mkString("\n")).toString, CsvOptions()).schema.fields(0).dataType
    assertEquals(DataType.DoubleType, typeOf("9" * 39))
    assertEquals(DataType.DoubleType, typeOf("99999999999999999999", "0.5"))
  }
}
