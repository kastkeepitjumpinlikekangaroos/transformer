package com.transformer.write.csv

import com.transformer.core._
import com.transformer.read.csv.{CsvOptions, CsvReader}
import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

class CsvWriterTest {

  private def makeBatch(schema: Schema, rows: Seq[Seq[Any]]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, rows.length max 1)
    rows.zipWithIndex.foreach { case (row, i) =>
      row.zipWithIndex.foreach { case (v, c) =>
        if (v == null) b.column(c).setNull(i) else b.column(c).setBoxed(i, v)
      }
    }
    b.setNumRows(rows.length)
    b
  }

  @Test def writesHeaderAndSimpleRows(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("name", DataType.StringType))
    val batch = makeBatch(s, Seq(Seq(1, "alice"), Seq(2, "bob")))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))
    val content = Files.readString(tmp)
    assertEquals("id,name\n1,alice\n2,bob\n", content)
  }

  @Test def quotesFieldsContainingDelimiterOrQuoteOrNewline(): Unit = {
    val s = Schema(Field("text", DataType.StringType))
    val batch = makeBatch(s, Seq(
      Seq("hello, world"),
      Seq("she said \"hi\""),
      Seq("line1\nline2")
    ))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))
    val content = Files.readString(tmp)
    val expected =
      "text\n\"hello, world\"\n\"she said \"\"hi\"\"\"\n\"line1\nline2\"\n"
    assertEquals(expected, content)
  }

  @Test def nullsWriteAsEmptyByDefault(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("name", DataType.StringType))
    val batch = makeBatch(s, Seq(Seq(1, null), Seq(null, "bob")))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))
    val content = Files.readString(tmp)
    assertEquals("id,name\n1,\n,bob\n", content)
  }

  @Test def roundtripThroughCsvReader(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("score", DataType.DoubleType), Field("name", DataType.StringType))
    val rows = Seq(
      Seq(1, 9.5, "alice"),
      Seq(2, 7.25, "bob, jr."),
      Seq(3, 0.0, "with \"quote\"")
    )
    val batch = makeBatch(s, rows)
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, s, Iterator(batch))

    val r = CsvReader.fromPath(tmp.toString, CsvOptions())
    assertEquals(s.length, r.schema.length)
    assertEquals(DataType.IntType, r.schema.fields(0).dataType)
    assertEquals(DataType.DoubleType, r.schema.fields(1).dataType)
    assertEquals(DataType.StringType, r.schema.fields(2).dataType)
    val out = r.readPartition(0).next()
    assertEquals(3, out.numRows)
    assertEquals(1, out.column(0).asInstanceOf[IntVector].get(0))
    assertEquals(9.5, out.column(1).asInstanceOf[DoubleVector].get(0), 1e-9)
    assertEquals("alice", out.column(2).asInstanceOf[StringVector].get(0))
    assertEquals("bob, jr.", out.column(2).asInstanceOf[StringVector].get(1))
    assertEquals("with \"quote\"", out.column(2).asInstanceOf[StringVector].get(2))
  }

  @Test def abortLeavesNoFileAtTarget(): Unit = {
    val s = Schema(Field("x", DataType.IntType))
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    val w = new CsvWriter(tmp, s)
    w.write(makeBatch(s, Seq(Seq(1), Seq(2))))
    w.abort()
    assertFalse(Files.exists(tmp))
  }

  // ---- regressions: plans/robustness/06 ----------------------------------

  private def written(schema: Schema, rows: Seq[Seq[Any]], options: CsvWriteOptions = CsvWriteOptions()): String = {
    val tmp = Files.createTempFile("out-", ".csv")
    Files.delete(tmp)
    CsvWriter.writeAll(tmp, schema, Iterator(makeBatch(schema, rows)), options)
    new String(Files.readAllBytes(tmp), options.charset)
  }

  /** Read back what the writer wrote with the option bag `raw`, the way a
    * DataJob re-reads a task's output. */
  private def roundTrip(schema: Schema, rows: Seq[Seq[Any]], raw: Map[String, String] = Map.empty): Seq[Seq[Any]] = {
    val dir = Files.createTempDirectory("roundtrip-")
    CsvWriter.writePartitioned(dir, schema, IndexedSeq(Iterator(makeBatch(schema, rows))), CsvWriteOptions.fromMap(raw))
    val r = CsvReader.fromPath(dir.toString, CsvOptions.forWrittenFile(raw, schema))
    (0 until r.numPartitions).flatMap(p => r.readPartition(p).flatMap(b => (0 until b.numRows).map(i =>
      (0 until schema.length).map(c => b.column(c).getBoxed(i) match {
        case a: Array[Byte] => a.toSeq
        case v => v
      }))))
  }

  private def failure(body: => Any): String =
    try { body; fail("expected a failure"); "" }
    catch {
      case e: AssertionError => throw e
      case e: IllegalArgumentException => e.getMessage
    }

  /** An empty string and a NULL both used to write as an empty field, so the
    * empty string read back as NULL; a string equal to the null string is
    * quoted now, and only an unquoted one is NULL. */
  @Test def aStringEqualToTheNullStringIsQuoted(): Unit = {
    val s = Schema(Field("s", DataType.StringType), Field("n", DataType.IntType))
    assertEquals("s,n\n\"\",1\n,2\n", written(s, Seq(Seq("", 1), Seq(null, 2))))
    assertEquals(Seq(Seq("", 1), Seq(null, 2)), roundTrip(s, Seq(Seq("", 1), Seq(null, 2))))
    val custom = Map("nullValue" -> "NULL")
    assertEquals("s,n\n\"NULL\",1\nNULL,NULL\n,3\n",
      written(s, Seq(Seq("NULL", 1), Seq(null, null), Seq("", 3)), CsvWriteOptions.fromMap(custom)))
    assertEquals(Seq(Seq("NULL", 1), Seq(null, null), Seq("", 3)),
      roundTrip(s, Seq(Seq("NULL", 1), Seq(null, null), Seq("", 3)), custom))
  }

  /** A NULL in a one-column table writes as a blank line, which the reader
    * used to skip: three rows written, two read back. */
  @Test def aOneColumnTableKeepsItsNullRows(): Unit = {
    val s = Schema(Field("x", DataType.IntType))
    assertEquals(Seq(Seq(1), Seq(null), Seq(3)), roundTrip(s, Seq(Seq(1), Seq(null), Seq(3))))
    val t = Schema(Field("t", DataType.StringType))
    assertEquals(Seq(Seq(null), Seq(""), Seq("a")), roundTrip(t, Seq(Seq(null), Seq(""), Seq("a"))))
    assertEquals(Seq(Seq(null), Seq(""), Seq("a")), roundTrip(t, Seq(Seq(null), Seq(""), Seq("a")), Map("nullValue" -> "NA")))
  }

  /** A BINARY writes as Base64, which the reader could not read back. */
  @Test def aBinaryColumnRoundTrips(): Unit = {
    val s = Schema(Field("b", DataType.BinaryType))
    val rows = Seq(Seq(Array[Byte](1, 2)), Seq(Array[Byte]()), Seq(null), Seq(Array[Byte](-1, 0, -128)))
    assertEquals(Seq(Seq(Seq[Byte](1, 2)), Seq(Seq[Byte]()), Seq(null), Seq(Seq[Byte](-1, 0, -128))), roundTrip(s, rows))
  }

  /** With an escape of its own, the writer escaped the quote but not the escape
    * itself, so the reader took `a\b` for an escaped `b` and a value ending in
    * the escape for an escaped closing quote, which ran into the next field. */
  @Test def theEscapeIsEscaped(): Unit = {
    val s = Schema(Field("s", DataType.StringType), Field("t", DataType.StringType))
    val esc = Map("escape" -> "\\")
    val rows = Seq(Seq("a\\b,c", "x"), Seq("ends\\,", "y"), Seq("q\"\\", "z"), Seq("plain\\", "w"))
    assertEquals("s,t\n\"a\\\\b,c\",x\n\"ends\\\\,\",y\n\"q\\\"\\\\\",z\nplain\\,w\n",
      written(s, rows, CsvWriteOptions.fromMap(esc)))
    assertEquals(rows, roundTrip(s, rows, esc))
  }

  /** A delimiter or quote past ASCII used to throw
    * ArrayIndexOutOfBoundsException from the writer's quoting table. */
  @Test def anyCharacterCanBeTheDelimiterOrQuote(): Unit = {
    val s = Schema(Field("s", DataType.StringType), Field("n", DataType.IntType))
    val opts = Map("delimiter" -> 0xa7.toChar.toString, "quote" -> 0xab.toChar.toString)
    val rows = Seq(Seq("a" + 0xa7.toChar + "b", 1), Seq(s"${0xab.toChar}q", 2), Seq("plain", 3))
    assertEquals(rows, roundTrip(s, rows, opts))
  }

  /** A charset that lacks a character used to write `?` in its place. */
  @Test def aCharacterTheCharsetCannotEncodeFails(): Unit = {
    val s = Schema(Field("id", DataType.IntType), Field("s", DataType.StringType))
    val latin1 = CsvWriteOptions(charset = StandardCharsets.ISO_8859_1)
    assertEquals("id,s\n1,caf" + 0xe9.toChar + "\n", written(s, Seq(Seq(1, "caf" + 0xe9.toChar)), latin1))
    val euro = failure(written(s, Seq(Seq(1, "a"), Seq(2, "5 " + 0x20ac.toChar)), latin1))
    assertTrue(euro, euro.contains("row 2") && euro.contains("'s'") && euro.contains("U+20AC") && euro.contains("ISO-8859-1"))
    // Half of a surrogate pair is no character in any charset.
    val half = failure(written(s, Seq(Seq(1, "x" + 0xd83d.toChar)), CsvWriteOptions()))
    assertTrue(half, half.contains("row 1") && half.contains("surrogate"))
  }

  /** The reader takes a leading U+FEFF for a byte-order mark, so a value that
    * starts with one is quoted. */
  @Test def aValueStartingWithAByteOrderMarkIsQuoted(): Unit = {
    val s = Schema(Field("s", DataType.StringType))
    val bom = s"${0xfeff.toChar}x"
    assertEquals(Seq(Seq(bom)), roundTrip(s, Seq(Seq(bom)), Map("header" -> "false")))
  }

  /** No partitions at all used to write no file, which a reader of the
    * directory cannot open: `No CSV files matched`. */
  @Test def noPartitionsWriteOneEmptyPartFile(): Unit = {
    val s = Schema(Field("x", DataType.IntType))
    val dir = Files.createTempDirectory("no-parts-")
    assertEquals(0L, CsvWriter.writePartitioned(dir, s, IndexedSeq.empty))
    assertEquals("x\n", Files.readString(dir.resolve("part-00000.csv")))
    val r = CsvReader.fromPath(dir.toString, CsvOptions.forWrittenFile(Map.empty, s))
    assertEquals(0, (0 until r.numPartitions).map(p => r.readPartition(p).map(_.numRows).sum).sum)
  }

  @Test def separatorsThatCannotBeReadBackAreRefused(): Unit = {
    for ((raw, word) <- Seq(
        Map("delimiter" -> ";", "quote" -> ";") -> "both",
        Map("delimiter" -> "\\t", "escape" -> "\\t") -> "both",
        Map("nullValue" -> "a,b") -> "nullValue",
        Map("nullValue" -> "\"x") -> "nullValue",
        Map("lineEnding" -> "|") -> "lineEnding")) {
      val msg = failure(CsvWriteOptions.fromMap(raw))
      assertTrue(s"$raw: $msg", msg.contains(word))
    }
  }
}
