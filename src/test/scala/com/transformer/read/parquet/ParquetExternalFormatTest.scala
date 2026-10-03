package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.sql.exec.SqlEngine
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}
import org.junit.Assert._
import org.junit.Test

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit

/** Our reader against files parquet-mr wrote, and our writer against
  * parquet-mr's reading of what it wrote.
  *
  * Every fixture here comes from [[ExternalParquet]], never from transformer's
  * own `ParquetWriter`: a writer and reader that share an assumption agree with
  * each other however wrong the assumption is, which is how a `MILLIS`
  * timestamp read 1000x too small and a decimal read as its unscaled integer
  * while `parquet_roundtrip_test` passed. Expected values are written out by
  * hand or computed with `java.time` and `java.math`, never with code under
  * test.
  */
class ParquetExternalFormatTest {

  // ─────────────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────────────

  private def newDir(): Path = Files.createTempDirectory("pq-external-")

  private def writeFixture(schemaText: String, rows: Seq[Seq[Any]]): Path = {
    val file = newDir().resolve("fixture.parquet")
    ExternalParquet.write(file, ExternalParquet.schema(schemaText), rows)
    file
  }

  /** Every row of `view`, each cell as the vector boxes it (null for NULL). A
    * byte array becomes a `Seq[Byte]` so rows compare by content. */
  private def rows(view: CatalogView): Seq[Seq[Any]] = {
    val out = scala.collection.mutable.ArrayBuffer.empty[Seq[Any]]
    (0 until view.numPartitions).foreach { p =>
      val it = view.readPartition(p)
      while (it.hasNext) {
        val b = it.next()
        var r = 0
        while (r < b.numRows) {
          out += (0 until view.schema.length).map { c =>
            b.column(c).getBoxed(r) match {
              case a: Array[Byte] => a.toSeq
              case v => v
            }
          }
          r += 1
        }
      }
    }
    out.toSeq
  }

  private def read(path: Path): CatalogView = ParquetReader.fromPath(path.toString)

  private def column(view: CatalogView, name: String): Seq[Any] = {
    val i = view.schema.indexOf(name)
    assertTrue(s"no column '$name' in ${view.schema}", i >= 0)
    rows(view).map(_(i))
  }

  private def typeOf(view: CatalogView, name: String): DataType = view.schema.field(name).dataType

  private def query(sql: String, views: (String, CatalogView)*): Seq[Seq[Any]] = {
    val catalog = new Catalog
    views.foreach { case (n, v) => catalog.register(n, v) }
    val q = SqlEngine.execute(sql, catalog)
    rows(MaterializedView.fromQuery(q))
  }

  private def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray

  /** The message of whatever `body` throws, failing if it returns. */
  private def failure(body: => Any): String = {
    try {
      body
      fail("expected a failure")
      ""
    } catch {
      case e: AssertionError => throw e
      case e: Throwable => String.valueOf(e.getMessage)
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // TIMESTAMP: every unit, and INT96, read as the instant they store.
  // ─────────────────────────────────────────────────────────────────────

  /** Whole milliseconds, so every encoding below holds each one exactly. */
  private val instants: Seq[Instant] = Seq(
    "2024-03-15T12:34:56.789Z",
    "1970-01-01T00:00:00Z",
    "1969-07-20T20:17:40.123Z",
    "1900-01-01T00:00:00.001Z",
    "2262-01-01T00:00:00Z"
  ).map(Instant.parse)

  private def epochMillis(i: Instant): Long = i.toEpochMilli
  private def epochMicros(i: Instant): Long = ChronoUnit.MICROS.between(Instant.EPOCH, i)
  private def epochNanos(i: Instant): Long = ChronoUnit.NANOS.between(Instant.EPOCH, i)

  /** The 12 bytes Hive, Impala and old Spark write for an instant: nanoseconds
    * of the day as a little-endian long, then the Julian day number as a
    * little-endian int. */
  private def int96(i: Instant): Array[Byte] = {
    val t = LocalDateTime.ofInstant(i, ZoneOffset.UTC)
    val nanosOfDay = t.toLocalTime.toNanoOfDay
    val julianDay = (t.toLocalDate.toEpochDay + 2440588L).toInt
    val b = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.putLong(nanosOfDay).putInt(julianDay)
    b.array()
  }

  private val everyEncoding =
    """message m {
      |  optional int64 millis (TIMESTAMP(MILLIS,true));
      |  optional int64 micros (TIMESTAMP(MICROS,true));
      |  optional int64 nanos (TIMESTAMP(NANOS,true));
      |  optional int96 legacy;
      |}""".stripMargin

  private def everyEncodingRow(i: Instant): Seq[Any] =
    Seq(epochMillis(i), epochMicros(i), epochNanos(i), int96(i))

  @Test def millisTimestampsReadAsTheirInstants(): Unit = {
    val file = writeFixture(
      "message m { optional int64 ts (TIMESTAMP(MILLIS,true)); }",
      instants.map(i => Seq(epochMillis(i))) :+ Seq(null))
    val view = read(file)
    assertEquals(DataType.TimestampType, typeOf(view, "ts"))
    assertEquals(instants :+ null, column(view, "ts"))
  }

  @Test def microsTimestampsReadAsTheirInstants(): Unit = {
    val file = writeFixture(
      "message m { optional int64 ts (TIMESTAMP(MICROS,true)); }",
      instants.map(i => Seq(epochMicros(i))) :+ Seq(null))
    assertEquals(instants :+ null, column(read(file), "ts"))
  }

  @Test def nanosTimestampsReadAsTheirInstants(): Unit = {
    val file = writeFixture(
      "message m { optional int64 ts (TIMESTAMP(NANOS,true)); }",
      instants.map(i => Seq(epochNanos(i))) :+ Seq(null))
    assertEquals(instants :+ null, column(read(file), "ts"))
  }

  @Test def int96TimestampsReadAsTheirInstants(): Unit = {
    val file = writeFixture("message m { optional int96 ts; }", instants.map(i => Seq(int96(i))) :+ Seq(null))
    val view = read(file)
    assertEquals(DataType.TimestampType, typeOf(view, "ts"))
    assertEquals(instants :+ null, column(view, "ts"))
  }

  @Test def everyTimestampEncodingReadsTheSameInstant(): Unit = {
    val file = writeFixture(everyEncoding, instants.map(everyEncodingRow))
    rows(read(file)).zip(instants).foreach { case (row, i) =>
      assertEquals(s"millis, micros, nanos and INT96 of $i", Seq(i, i, i, i), row)
    }
  }

  /** A TIMESTAMP holds microseconds, so a NANOS value loses its last three
    * digits the way every other path into the engine loses them: the instant
    * is truncated to its microsecond (`Instant.truncatedTo`), which before 1970
    * is the earlier microsecond. So a NANOS value, the same instant in INT96,
    * and a timestamp literal with nine fractional digits all read alike. */
  @Test def nanosTimestampsTruncateToTheMicrosecond(): Unit = {
    val precise = Seq("2024-03-15T12:34:56.789123456Z", "1969-12-31T23:59:59.999998500Z", "1969-12-31T23:59:59.999999999Z")
      .map(Instant.parse)
    val file = writeFixture(
      "message m { optional int64 nanos (TIMESTAMP(NANOS,true)); optional int96 legacy; }",
      precise.map(i => Seq(epochNanos(i), int96(i))))
    val expected = precise.map(_.truncatedTo(ChronoUnit.MICROS))
    assertEquals(Instant.parse("1969-12-31T23:59:59.999998Z"), expected(1))
    val view = read(file)
    assertEquals(expected.map(i => Seq(i, i)), rows(view))
    // A literal takes the same nine digits the same way.
    assertEquals(Seq(Seq(true)), query(
      "SELECT nanos = TIMESTAMP '1969-12-31 23:59:59.999998500' AS same FROM t WHERE legacy < TIMESTAMP '1970-01-01 00:00:00' AND nanos < TIMESTAMP '1969-12-31 23:59:59.999999'",
      "t" -> view))
  }

  @Test def aMillisTimestampBeyondTheMicrosecondRangeFailsTheRead(): Unit = {
    // Long.MaxValue microseconds is in the year 294247; this many
    // milliseconds is a thousand times later and has no microsecond value.
    val file = writeFixture("message m { optional int64 ts (TIMESTAMP(MILLIS,true)); }",
      Seq(Seq(1L), Seq(Long.MaxValue / 1000L + 1L)))
    val msg = failure(rows(read(file)))
    assertTrue(msg, msg.contains("'ts'") && msg.contains("MILLIS"))
  }

  /** `isAdjustedToUTC = false` marks a local date-time with no zone. The engine
    * has one timestamp type, an instant in UTC, so the stored reading is taken
    * as UTC unchanged: the wall clock survives and no zone is applied. */
  @Test def aLocalTimestampReadsItsWallClockAsUtc(): Unit = {
    val wallClock = LocalDateTime.parse("2024-03-15T12:34:56.789")
    val asUtc = wallClock.toInstant(ZoneOffset.UTC)
    val file = writeFixture(
      "message m { optional int64 millis (TIMESTAMP(MILLIS,false)); optional int64 micros (TIMESTAMP(MICROS,false)); }",
      Seq(Seq(epochMillis(asUtc), epochMicros(asUtc))))
    assertEquals(Seq(Seq(asUtc, asUtc)), rows(read(file)))
  }

  @Test def aGlobMixingTimestampUnitsReadsEachFileInItsOwnUnit(): Unit = {
    val dir = newDir()
    for ((unit, encode) <- Seq[(String, Instant => Long)](
        "MILLIS" -> epochMillis, "MICROS" -> epochMicros, "NANOS" -> epochNanos))
      ExternalParquet.write(dir.resolve(s"$unit.parquet"),
        ExternalParquet.schema(s"message m { optional int64 ts (TIMESTAMP($unit,true)); }"),
        instants.map(i => Seq(encode(i))))
    val view = ParquetReader.fromPath(dir.toString)
    assertEquals(3, view.numPartitions)
    assertEquals((instants ++ instants ++ instants).sorted, column(view, "ts").map(_.asInstanceOf[Instant]).sorted)
  }

  /** Through the planner: projection, a residual filter on each unit, and an
    * `IS NOT NULL`, the one timestamp predicate pushed to row-group
    * statistics (comparisons are not: statistics are in the file's unit). */
  @Test def aTimestampFilterOverAMillisFileFindsItsRows(): Unit = {
    val file = writeFixture(everyEncoding, instants.map(everyEncodingRow) :+ Seq(epochMillis(instants.head), null, null, null))
    val view = read(file)
    val found = query(
      "SELECT millis, nanos, legacy FROM t WHERE millis >= TIMESTAMP '1970-01-01 00:00:00' " +
        "AND nanos < TIMESTAMP '2100-01-01 00:00:00' AND legacy IS NOT NULL",
      "t" -> view)
    val want = instants.filter(i => !i.isBefore(Instant.EPOCH) && i.isBefore(Instant.parse("2100-01-01T00:00:00Z")))
    assertEquals(want.map(i => Seq(i, i, i)), found)
  }

  // ─────────────────────────────────────────────────────────────────────
  // Column resolution: a scan reads each file's columns by name, as the file
  // declares them, whatever the view's schema would write.
  // ─────────────────────────────────────────────────────────────────────

  @Test def aProjectedScanReadsAnInt96Column(): Unit = {
    val file = writeFixture("message m { required int32 id; optional int96 ts; }",
      instants.zipWithIndex.map { case (i, n) => Seq(n, int96(i)) })
    val projected = read(file).withProjectedColumns(Seq("ts")).get
    assertEquals(Seq("ts"), projected.schema.fieldNames)
    assertEquals(instants.map(Seq(_)), rows(projected))
    // The planner projects any scan a query reads part of.
    assertEquals(instants.map(Seq(_)), query("SELECT ts FROM t", "t" -> read(file)))
  }

  @Test def aProjectedScanReadsAFixedLengthColumn(): Unit = {
    val codes = Seq(bytes(1, 2, 3, 4), bytes(0xFF, 0, 0x80, 0x7F))
    val file = writeFixture("message m { required int32 id; optional fixed_len_byte_array(4) code; }",
      codes.zipWithIndex.map { case (c, n) => Seq(n, c) })
    val projected = read(file).withProjectedColumns(Seq("code")).get
    assertEquals(DataType.BinaryType, projected.schema.field("code").dataType)
    assertEquals(codes.map(c => Seq(c.toSeq)), rows(projected))
  }

  @Test def aGlobMatchesEachFilesColumnsByName(): Unit = {
    val dir = newDir()
    ExternalParquet.write(dir.resolve("a.parquet"),
      ExternalParquet.schema("message m { required int32 a; required int32 b; required binary s (STRING); }"),
      Seq(Seq(1, 10, "one"), Seq(2, 20, "two")))
    // Same columns, two of them swapped, and NULLs where the first file has none.
    ExternalParquet.write(dir.resolve("b.parquet"),
      ExternalParquet.schema("message m { optional int32 b; optional int32 a; optional binary s (STRING); }"),
      Seq(Seq(30, 3, "three"), Seq(40, null, null)))
    val view = ParquetReader.fromPath(dir.toString)
    assertEquals(Seq("a", "b", "s"), view.schema.fieldNames)
    assertEquals(Seq(Seq(1, 10, "one"), Seq(2, 20, "two"), Seq(3, 30, "three"), Seq(null, 40, null)), rows(view))
    // One file allows a NULL, so the view does.
    assertTrue(view.schema.toString, view.schema.fields.forall(_.nullable))
    assertEquals(Seq(Seq(3, "three"), Seq(null, null)), rows(view.withProjectedColumns(Seq("a", "s")).get).drop(2))
  }

  @Test def aGlobFileWithoutAColumnFailsNamingIt(): Unit = {
    val dir = newDir()
    ExternalParquet.write(dir.resolve("a.parquet"),
      ExternalParquet.schema("message m { required int32 a; required int32 b; }"), Seq(Seq(1, 2)))
    ExternalParquet.write(dir.resolve("b.parquet"),
      ExternalParquet.schema("message m { required int32 a; }"), Seq(Seq(3)))
    val msg = failure(rows(ParquetReader.fromPath(dir.toString)))
    assertTrue(msg, msg.contains("b.parquet") && msg.contains("'b'"))
  }

  @Test def aGlobFileStoringAColumnAsAnotherTypeFailsNamingIt(): Unit = {
    val dir = newDir()
    ExternalParquet.write(dir.resolve("a.parquet"),
      ExternalParquet.schema("message m { required int32 a; }"), Seq(Seq(1)))
    ExternalParquet.write(dir.resolve("b.parquet"),
      ExternalParquet.schema("message m { required int64 a; }"), Seq(Seq(2L)))
    val msg = failure(rows(ParquetReader.fromPath(dir.toString)))
    assertTrue(msg, msg.contains("b.parquet") && msg.contains("'a'") && msg.contains("long") && msg.contains("int"))
  }

  // ─────────────────────────────────────────────────────────────────────
  // DECIMAL: all four physical layouts, read at their declared scale.
  // ─────────────────────────────────────────────────────────────────────

  private def dec(s: String): JBigDecimal = new JBigDecimal(s)

  /** The unscaled value as `length` big-endian two's-complement bytes. */
  private def fixed(unscaled: BigInteger, length: Int): Array[Byte] = {
    val minimal = unscaled.toByteArray
    require(minimal.length <= length, s"$unscaled needs ${minimal.length} bytes")
    val pad: Byte = if (unscaled.signum < 0) -1 else 0
    Array.fill[Byte](length - minimal.length)(pad) ++ minimal
  }

  private def unscaled(d: JBigDecimal): BigInteger = d.unscaledValue

  @Test def decimalStoredAsInt32(): Unit = {
    val values = Seq("123.45", "-123.45", "0.00", "9999999.99", "-9999999.99", "0.01", "-0.01").map(dec)
    val file = writeFixture("message m { optional int32 price (DECIMAL(9,2)); }",
      values.map(v => Seq(unscaled(v).intValueExact)) :+ Seq(null))
    val view = read(file)
    assertEquals(DataType.DecimalType(9, 2), typeOf(view, "price"))
    assertEquals(values :+ null, column(view, "price"))
  }

  @Test def decimalStoredAsInt64(): Unit = {
    val values = Seq("123456789012.3456", "-0.0001", "99999999999999.9999", "-99999999999999.9999", "0.0000").map(dec)
    val file = writeFixture("message m { optional int64 amount (DECIMAL(18,4)); }",
      values.map(v => Seq(unscaled(v).longValueExact)) :+ Seq(null))
    val view = read(file)
    assertEquals(DataType.DecimalType(18, 4), typeOf(view, "amount"))
    assertEquals(values :+ null, column(view, "amount"))
  }

  @Test def decimalStoredAsFixedLengthBytes(): Unit = {
    val values = Seq(
      "1234567890123456789012345678.0123456789",
      "-9999999999999999999999999999.9999999999",
      "9999999999999999999999999999.9999999999",
      "-1.0000000000",
      "0E-10").map(dec)
    val file = writeFixture("message m { optional fixed_len_byte_array(16) big (DECIMAL(38,10)); }",
      values.map(v => Seq(fixed(unscaled(v), 16))) :+ Seq(null))
    val view = read(file)
    assertEquals(DataType.DecimalType(38, 10), typeOf(view, "big"))
    assertEquals(values :+ null, column(view, "big"))
  }

  /** Sign extension, spelled out byte by byte rather than computed. */
  @Test def decimalFixedLengthBytesAreSignExtended(): Unit = {
    val file = writeFixture("message m { required fixed_len_byte_array(4) price (DECIMAL(9,2)); }", Seq(
      Seq(bytes(0xFF, 0xFF, 0xFF, 0x9C)),  // -100
      Seq(bytes(0x00, 0x00, 0x00, 0x80)),  // 128: a set top bit in the last byte is not a sign
      Seq(bytes(0xFF, 0xFF, 0xFF, 0x80)),  // -128
      Seq(bytes(0x80, 0x00, 0x00, 0x00)),  // Int.MinValue, past DECIMAL(9,2) but stored
      Seq(bytes(0x05, 0xF5, 0xE0, 0xFF))   // 99999999
    ))
    assertEquals(Seq("-1.00", "1.28", "-1.28", "-21474836.48", "999999.99").map(dec), column(read(file), "price"))
  }

  @Test def decimalStoredAsBinary(): Unit = {
    val file = writeFixture("message m { optional binary big (DECIMAL(38,10)); }", Seq(
      Seq(bytes(0xFF)),                    // -1: one byte, all sign
      Seq(bytes(0x00, 0x80)),              // 128 needs a leading zero byte
      Seq(bytes(0x80)),                    // -128
      Seq(bytes(0xFF, 0xFF, 0xFF, 0x9C)),  // -100 with redundant sign bytes
      Seq(fixed(unscaled(dec("-9999999999999999999999999999.9999999999")), 16)),
      Seq(unscaled(dec("1234567890123456789012345678.0123456789")).toByteArray),
      Seq(null)))
    val view = read(file)
    assertEquals(DataType.DecimalType(38, 10), typeOf(view, "big"))
    assertEquals(Seq("-1E-10", "1.28E-8", "-1.28E-8", "-1.00E-8",
      "-9999999999999999999999999999.9999999999", "1234567890123456789012345678.0123456789").map(dec) :+ null,
      column(view, "big"))
  }

  @Test def anEmptyBinaryDecimalFailsTheRead(): Unit = {
    // Two's complement needs at least one byte; an empty value is no number.
    val file = writeFixture("message m { optional binary big (DECIMAL(38,10)); }", Seq(Seq(bytes(1)), Seq(bytes())))
    val msg = failure(rows(read(file)))
    assertTrue(msg, msg.contains("'big'") && msg.contains("DECIMAL"))
  }

  @Test def aProjectedScanReadsADecimalColumn(): Unit = {
    val values = Seq("1.25", "-3.50").map(dec)
    val file = writeFixture(
      "message m { required int32 id; optional fixed_len_byte_array(5) price (DECIMAL(10,2)); }",
      values.zipWithIndex.map { case (v, n) => Seq(n, fixed(unscaled(v), 5)) })
    assertEquals(values.map(Seq(_)), rows(read(file).withProjectedColumns(Seq("price")).get))
    assertEquals(Seq(Seq(dec("1.25"))), query("SELECT price FROM t WHERE price > 0", "t" -> read(file)))
  }

  /** Parquet decimals of every layout, through the engine and out of our
    * writer, back to parquet-mr: the only shape that proves both halves. */
  @Test def decimalsSurviveAnExternalReadWriteCycle(): Unit = {
    val small = Seq("123.45", "-0.01", "0.00").map(dec)
    val medium = Seq("123456789012.3456", "-0.0001", "0.0000").map(dec)
    val large = Seq("1234567890123456789012345678.0123456789", "-1.0000000000", "0E-10").map(dec)
    val source = writeFixture(
      """message m {
        |  optional int32 small (DECIMAL(9,2));
        |  optional int64 medium (DECIMAL(18,4));
        |  optional fixed_len_byte_array(16) fixed (DECIMAL(38,10));
        |  optional binary bytes (DECIMAL(38,10));
        |}""".stripMargin,
      (0 until 3).map(i => Seq(
        unscaled(small(i)).intValueExact, unscaled(medium(i)).longValueExact,
        fixed(unscaled(large(i)), 16), unscaled(large(i)).toByteArray)) :+ Seq(null, null, null, null))
    val view = read(source)
    val target = newDir().resolve("out.parquet")
    TParquetWriter.writeAll(target, view.schema, (0 until view.numPartitions).iterator.flatMap(view.readPartition))
    val expected = (0 until 3).map(i => Seq(small(i), medium(i), large(i), large(i))) :+ Seq(null, null, null, null)
    val back = ExternalParquet.read(target).map(_.map(s => if (s == null) null else dec(s)))
    assertEquals(expected, back)
    // parquet-mr's stringifier renders a decimal at its declared scale, so
    // equal BigDecimals here also means the written scale is the read one.
    assertEquals(Seq("123.45", "123456789012.3456", "1234567890123456789012345678.0123456789",
      "1234567890123456789012345678.0123456789"), ExternalParquet.read(target).head)
  }

  @Test def ourDecimalLayoutsAreTheOnesTheSpecRecommends(): Unit = {
    val schema = Schema(
      Field("p9", DataType.DecimalType(9, 2)),
      Field("p10", DataType.DecimalType(10, 2)),
      Field("p18", DataType.DecimalType(18, 4)),
      Field("p19", DataType.DecimalType(19, 0)),
      Field("p38", DataType.DecimalType(38, 10)),
      Field("p40", DataType.DecimalType(40, 5)))
    val target = newDir().resolve("layouts.parquet")
    TParquetWriter.writeAll(target, schema, Iterator.empty)
    val footer = ExternalParquet.footerSchema(target).getFields
    def layout(i: Int): String = footer.get(i).toString
    assertEquals("optional int32 p9 (DECIMAL(9,2))", layout(0))
    assertEquals("optional int64 p10 (DECIMAL(10,2))", layout(1))
    assertEquals("optional int64 p18 (DECIMAL(18,4))", layout(2))
    assertEquals("optional fixed_len_byte_array(9) p19 (DECIMAL(19,0))", layout(3))
    assertEquals("optional fixed_len_byte_array(16) p38 (DECIMAL(38,10))", layout(4))
    assertEquals("optional fixed_len_byte_array(17) p40 (DECIMAL(40,5))", layout(5))
  }

  @Test def ourDecimalWriterRoundTripsEveryLayout(): Unit = {
    val schema = Schema(
      Field("p1", DataType.DecimalType(1, 0)),
      Field("p9", DataType.DecimalType(9, 2)),
      Field("p18", DataType.DecimalType(18, 4)),
      Field("p20", DataType.DecimalType(20, 0)),
      Field("p38", DataType.DecimalType(38, 10)))
    val values = Seq(
      Seq("9", "9999999.99", "99999999999999.9999", "99999999999999999999", "9999999999999999999999999999.9999999999"),
      Seq("-9", "-9999999.99", "-99999999999999.9999", "-99999999999999999999", "-9999999999999999999999999999.9999999999"),
      Seq("0", "0.01", "-0.0001", "1", "-1E-10")).map(_.map(dec))
    val batch = new ColumnarBatch(schema, values.length + 1)
    values.zipWithIndex.foreach { case (row, r) => row.zipWithIndex.foreach { case (v, c) => batch.column(c).setBoxed(r, v) } }
    (0 until schema.length).foreach(c => batch.column(c).setNull(values.length))
    batch.setNumRows(values.length + 1)
    val target = newDir().resolve("roundtrip.parquet")
    TParquetWriter.writeAll(target, schema, Iterator(batch))
    // Read back at each column's declared scale.
    val expected = values.map(_.zip(Seq(0, 2, 4, 0, 10)).map { case (v, s) => v.setScale(s) }) :+ Seq.fill(5)(null)
    assertEquals(expected, rows(read(target)))
    assertEquals(expected, ExternalParquet.read(target).map(_.map(s => if (s == null) null else dec(s))))
  }

  /** A column does not enforce its declared scale on what it holds, so the
    * writer meets values at other scales. One it can write at the declared
    * scale exactly is written there; one it would have to round, or one with
    * more digits than the precision holds, fails the write rather than storing
    * a different number. */
  @Test def ourDecimalWriterWritesAValueAtTheDeclaredScaleOrFails(): Unit = {
    val schema = Schema(Field("price", DataType.DecimalType(9, 2)))
    def write(v: String): Path = {
      val batch = new ColumnarBatch(schema, 1)
      batch.column(0).setBoxed(0, dec(v))
      batch.setNumRows(1)
      val target = newDir().resolve("scale.parquet")
      TParquetWriter.writeAll(target, schema, Iterator(batch))
      target
    }
    assertEquals(Seq(Seq(dec("1.50"))), rows(read(write("1.5"))))
    assertEquals(Seq(Seq(dec("2.00"))), rows(read(write("2.000000"))))
    val rounding = failure(write("1.555"))
    assertTrue(rounding, rounding.contains("1.555") && rounding.contains("'price'") && rounding.contains("decimal(9,2)"))
    val tooWide = failure(write("12345678.00"))
    assertTrue(tooWide, tooWide.contains("12345678.00") && tooWide.contains("'price'") && tooWide.contains("decimal(9,2)"))
  }
}
