package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.sql.exec.SqlEngine
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}
import org.apache.parquet.format.{CompressionCodec, LogicalType, UUIDType}
import org.apache.parquet.column.ParquetProperties
import org.apache.parquet.hadoop.metadata.{BlockMetaData, CompressionCodecName}
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName
import org.junit.Assert._
import org.junit.Test

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import java.util.Locale
import scala.jdk.CollectionConverters._

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

  /** An INT96 day this far from 1970 has no microsecond a long counts, and
    * wrapped round to another instant (plans/robustness/25). It fails the
    * read naming the column and the day, as a MILLIS value past it does. */
  @Test def anInt96TimestampBeyondTheMicrosecondRangeFailsTheRead(): Unit = {
    val far = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
      .putLong(0L).putInt(Int.MaxValue).array()
    val file = writeFixture("message m { optional int96 ts; }", Seq(Seq(int96(Instant.EPOCH)), Seq(far)))
    val msg = failure(rows(read(file)))
    assertTrue(msg, msg.contains("'ts'") && msg.contains("INT96") && msg.contains("Julian day 2147483647"))
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
    // Spelled from its parts: parquet-mr prints a type's name in the default
    // locale, INT32 as ınt32 under a Turkish one.
    def layout(i: Int): String = {
      val t = footer.get(i).asPrimitiveType
      val width = if (t.getPrimitiveTypeName == PrimitiveTypeName.FIXED_LEN_BYTE_ARRAY) s"(${t.getTypeLength})" else ""
      s"${t.getRepetition.name.toLowerCase(Locale.ROOT)} ${t.getPrimitiveTypeName.name.toLowerCase(Locale.ROOT)}$width " +
        s"${t.getName} (${t.getLogicalTypeAnnotation})"
    }
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

  // ─────────────────────────────────────────────────────────────────────
  // Every other annotation, decided rather than fallen through.
  // ─────────────────────────────────────────────────────────────────────

  @Test def unsignedInt32WidensToLong(): Unit = {
    val file = writeFixture("message m { optional int32 n (INTEGER(32,false)); }",
      Seq(Seq(0), Seq(Int.MaxValue), Seq(-1), Seq(Int.MinValue), Seq(null)))
    val view = read(file)
    assertEquals(DataType.LongType, typeOf(view, "n"))
    assertEquals(Seq(0L, 2147483647L, 4294967295L, 2147483648L, null), column(view, "n"))
  }

  @Test def unsignedInt64WidensToADecimal(): Unit = {
    val file = writeFixture("message m { optional int64 n (INTEGER(64,false)); }",
      Seq(Seq(0L), Seq(Long.MaxValue), Seq(-1L), Seq(Long.MinValue), Seq(null)))
    val view = read(file)
    assertEquals(DataType.DecimalType(20, 0), typeOf(view, "n"))
    assertEquals(Seq("0", "9223372036854775807", "18446744073709551615", "9223372036854775808").map(dec) :+ null,
      column(view, "n"))
  }

  @Test def narrowIntegersReadAsInt(): Unit = {
    val file = writeFixture(
      """message m {
        |  required int32 u8 (INTEGER(8,false));
        |  required int32 u16 (INTEGER(16,false));
        |  required int32 i8 (INTEGER(8,true));
        |  required int32 i16 (INTEGER(16,true));
        |  required int32 i32 (INTEGER(32,true));
        |  required int64 i64 (INTEGER(64,true));
        |}""".stripMargin,
      Seq(Seq[Any](255, 65535, -128, -32768, Int.MinValue, Long.MinValue)))
    val view = read(file)
    assertEquals(Seq(DataType.IntType, DataType.IntType, DataType.IntType, DataType.IntType, DataType.IntType, DataType.LongType),
      view.schema.fields.map(_.dataType))
    assertEquals(Seq(Seq[Any](255, 65535, -128, -32768, Int.MinValue, Long.MinValue)), rows(view))
  }

  @Test def textAnnotationsReadAsStringsAndBinaryOnesAsBytes(): Unit = {
    val uuid = bytes(0x12, 0x3e, 0x45, 0x67, 0xe8, 0x9b, 0x12, 0xd3, 0xa4, 0x56, 0x42, 0x66, 0x14, 0x17, 0x40, 0x00)
    val bson = bytes(0x05, 0x00, 0x00, 0x00, 0x00)  // the empty BSON document
    val file = writeFixture(
      """message m {
        |  optional binary j (JSON);
        |  optional binary e (ENUM);
        |  optional binary s (STRING);
        |  optional binary b (BSON);
        |  optional fixed_len_byte_array(16) u (UUID);
        |}""".stripMargin,
      Seq(Seq("""{"a": 1}""", "RED", "text", bson, uuid)))
    val view = read(file)
    assertEquals(Seq(DataType.StringType, DataType.StringType, DataType.StringType, DataType.BinaryType, DataType.BinaryType),
      view.schema.fields.map(_.dataType))
    assertEquals(Seq(Seq("""{"a": 1}""", "RED", "text", bson.toSeq, uuid.toSeq)), rows(view))
  }

  @Test def float16WidensToFloat(): Unit = {
    // IEEE half precision, little-endian as parquet stores it.
    val file = writeFixture("message m { optional fixed_len_byte_array(2) h (FLOAT16); }", Seq(
      Seq(bytes(0x00, 0x3E)),  // 1.5
      Seq(bytes(0x00, 0xC0)),  // -2.0
      Seq(bytes(0xFF, 0x7B)),  // 65504, the largest finite half
      Seq(bytes(0x01, 0x00)),  // 2^-24, the smallest subnormal
      Seq(bytes(0x00, 0x80)),  // -0.0
      Seq(bytes(0x00, 0x7C)),  // +Infinity
      Seq(bytes(0x00, 0x7E)),  // NaN
      Seq(null)))
    val view = read(file)
    assertEquals(DataType.FloatType, typeOf(view, "h"))
    val got = column(view, "h")
    assertEquals(Seq(1.5f, -2.0f, 65504f, java.lang.Math.scalb(1f, -24), -0.0f, Float.PositiveInfinity).map(Float.box),
      got.take(6))
    assertEquals(java.lang.Float.floatToIntBits(-0.0f), java.lang.Float.floatToIntBits(got(4).asInstanceOf[Float]))
    assertTrue(got(6).asInstanceOf[Float].isNaN)
    assertNull(got(7))
  }

  /** Each was an `UnsupportedOperationException` that named the column but
    * not the file. */
  @Test def columnsWithNoEngineTypeFailNamingTheColumn(): Unit = {
    for ((schema, column, kind) <- Seq(
        ("message m { required int32 id; optional int32 t (TIME(MILLIS,true)); }", "t", "TIME"),
        ("message m { required int32 id; optional int64 t (TIME(MICROS,true)); }", "t", "TIME"),
        ("message m { required int32 id; optional int64 t (TIME(NANOS,false)); }", "t", "TIME"),
        ("message m { required int32 id; optional fixed_len_byte_array(12) span (INTERVAL); }", "span", "INTERVAL"))) {
      val file = newDir().resolve("unreadable.parquet")
      ExternalParquet.write(file, ExternalParquet.schema(schema), Seq.empty)
      val msg = refusalOf(file)(read(file))
      assertTrue(s"$schema: $msg", msg.contains(s"column '$column' is") && msg.contains(kind) &&
        msg.endsWith("which has no transformer type"))
    }
  }

  @Test def aRepeatedColumnFailsNamingIt(): Unit = {
    // A repeated primitive is a list per row, which a column cannot hold.
    val file = writeFixture("message m { required int32 id; repeated int32 xs; }", Seq(Seq(1, 7)))
    val msg = refusalOf(file)(read(file))
    assertTrue(msg, msg.contains("column 'xs' is repeated, a list of values in each row, and only flat schemas are supported"))
  }

  /** A nested column's refusal printed its whole group as parquet's schema
    * text, which runs to many lines for a deep one; it says what the group
    * is. */
  @Test def aNestedColumnFailsNamingItsGroup(): Unit = {
    for ((schema, what) <- Seq(
        ("message m { required int32 id; optional group xs (LIST) { repeated group list { optional int32 element; } } }",
          "column 'xs' is nested, a group annotated LIST,"),
        ("message m { required int32 id; optional group xs { optional int32 a; optional binary b; } }",
          "column 'xs' is nested, a group of 2 fields,"))) {
      val file = newDir().resolve("nested.parquet")
      ExternalParquet.write(file, ExternalParquet.schema(schema), Seq.empty)
      val msg = refusalOf(file)(read(file))
      assertTrue(s"$schema: $msg", msg.contains(what) && msg.endsWith("and only flat schemas are supported"))
    }
  }

  /** A string column's statistics are in UTF-8 byte order, which is code point
    * order, and the engine orders strings by UTF-16 code unit. The two disagree
    * where a character from U+E000 to U+FFFF meets one past U+FFFF, stored as
    * a surrogate pair below U+E000: the emoji orders below U+FFFD for the
    * engine and above it in the file. A range pushed on such a literal skipped
    * the group holding the emoji, so the query lost it; found by
    * `fuzz/parquet_fuzz_test` at seed 1203. */
  @Test def aStringRangeIsJudgedInTheEnginesOrderNotTheFilesOrder(): Unit = {
    val emoji = "\ud83d\ude00"
    val path = newDir().resolve("strings.parquet")
    ExternalParquet.writeRowGroups(path, ExternalParquet.schema("message m { optional binary s (STRING); }"),
      Seq((Seq(Seq(emoji)), ExternalParquet.Layout()), (Seq(Seq("\ufffd")), ExternalParquet.Layout()),
        (Seq(Seq("\ue000")), ExternalParquet.Layout()), (Seq(Seq("abc")), ExternalParquet.Layout())))
    def find(where: String): Seq[String] =
      query(s"SELECT s FROM t WHERE $where", "t" -> ParquetReader.fromPath(path.toString, 8192, 1L))
        .map(_.head.asInstanceOf[String])
    // The engine's order: abc < emoji < U+E000 < U+FFFD.
    assertEquals(Seq(emoji, "\ue000", "abc"), find("s < '\ufffd'"))
    assertEquals(Seq("\ufffd", "\ue000"), find(s"s > '$emoji'"))
    assertEquals(Seq(emoji, "\ufffd", "\ue000"), find(s"s >= '$emoji'"))
    // A literal below the surrogates orders both ways alike, so it still
    // pushes, and equality pushes whatever the literal.
    val view = ParquetReader.fromPath(path.toString, 8192, 1L)
    import com.transformer.sql.plan._
    def cmp(op: String, lit: String): Expr =
      BinOpExpr(op, ColRefExpr(0, "s", DataType.StringType), LitExpr(lit, DataType.StringType), DataType.BooleanType)
    assertEquals(None, view.withPushdownFilter(cmp("<", "\ufffd")))
    assertEquals(None, view.withPushdownFilter(cmp(">", emoji)))
    assertTrue(view.withPushdownFilter(cmp("<", "abd")).isDefined)
    assertTrue(view.withPushdownFilter(cmp("=", emoji)).isDefined)
    assertEquals(Seq("abc"), find("s < 'abd'"))
    assertEquals(Seq(emoji), find(s"s = '$emoji'"))
  }

  /** An unsigned 8- or 16-bit integer reads as the INT it is, but its file
    * orders it unsigned, and parquet-mr judges a pushed bound in that order, so
    * a negative bound sorted above every value: `u8 > '-1'` (a literal the
    * binder reads as the INT -1) skipped the whole file where the engine keeps
    * every row. A bare `-1` is a negation, which is not pushed. Found by
    * `fuzz/parquet_fuzz_campaign` at seeds 9494 and 18048. */
  @Test def anUnsignedNarrowIntegerIsComparedSigned(): Unit = {
    val file = writeFixture(
      "message m { optional int32 u8 (INTEGER(8,false)); optional int32 u16 (INTEGER(16,false)); }",
      Seq(Seq(255, 2618), Seq(0, 65535), Seq(null, null)))
    val view = ParquetReader.fromPath(file.toString)
    val all = Seq(Seq(255, 2618), Seq(0, 65535))
    assertEquals(all, query("SELECT u8, u16 FROM t WHERE u8 > '-1' AND u16 >= CAST('-32768' AS INT)", "t" -> view))
    assertEquals(all, query("SELECT u8, u16 FROM t WHERE u8 BETWEEN '-5' AND '300'", "t" -> view))
    assertEquals(all, query("SELECT u8, u16 FROM t WHERE u8 > -1", "t" -> view))
    import com.transformer.sql.plan._
    val gt = BinOpExpr(">", ColRefExpr(0, "u8", DataType.IntType), LitExpr(Int.box(-1), DataType.IntType), DataType.BooleanType)
    assertEquals(None, view.withPushdownFilter(gt))
    assertTrue(view.withPushdownFilter(IsNullExpr(ColRefExpr(0, "u8", DataType.IntType), negated = false)).isDefined)
  }

  /** A comparison is pushed to row-group statistics only on a column whose
    * stored values are the engine's. An unsigned INT32 reads as a BIGINT and a
    * FLOAT16 as a FLOAT, and their statistics bound other bits, so a bound on
    * either stays in the residual filter; IS NULL, which reads only null
    * counts, still pushes. */
  @Test def comparisonsArePushedOnlyWhereTheStoredValueIsTheEngineValue(): Unit = {
    import com.transformer.sql.plan._
    val file = writeFixture(
      """message m {
        |  optional int32 u (INTEGER(32,false));
        |  optional fixed_len_byte_array(2) h (FLOAT16);
        |  optional int64 n;
        |}""".stripMargin,
      Seq(Seq(-1, bytes(0x00, 0x3E), 5L), Seq(7, null, 9L)))
    val view = ParquetReader.fromPath(file.toString)
    def gt(name: String, dt: DataType, v: Any) =
      BinOpExpr(">", ColRefExpr(view.schema.indexOf(name), name, dt), LitExpr(v, dt), DataType.BooleanType)
    assertEquals(None, view.withPushdownFilter(gt("u", DataType.LongType, java.lang.Long.valueOf(10L))))
    assertEquals(None, view.withPushdownFilter(gt("h", DataType.FloatType, java.lang.Float.valueOf(1.0f))))
    assertTrue(view.withPushdownFilter(gt("n", DataType.LongType, java.lang.Long.valueOf(10L))).isDefined)
    assertTrue(view.withPushdownFilter(IsNullExpr(ColRefExpr(0, "u", DataType.LongType), negated = false)).isDefined)
    // Through the planner, the residual filter answers on the engine values.
    assertEquals(Seq(Seq[Any](4294967295L, 1.5f)), query("SELECT u, h FROM t WHERE u > 10 AND h > 1.0", "t" -> view))
  }

  // ─────────────────────────────────────────────────────────────────────
  // Refusals: in the reader's words, naming the file.
  // ─────────────────────────────────────────────────────────────────────

  /** What a library's failure shows: its classes, its code, Thrift's dump of
    * a struct. */
  private val LibraryWords = Seq("org.apache", "shaded.", "java.", "Exception", "Cannot invoke", "Struct:")

  /** The message of the refusal `read` meets on `file`, held to the rule
    * every refusal of a parquet file keeps: an `IllegalArgumentException`,
    * never a class of parquet-mr's or the JVM's, whose message names the file
    * first and holds none of [[LibraryWords]] and no bare `null`. */
  private def refusalOf(file: Path)(read: => Any): String = {
    val thrown =
      try { read; null }
      catch { case e: Throwable if !e.isInstanceOf[AssertionError] => e }
    assertNotNull(s"$file was read", thrown)
    assertTrue(s"$file: ${thrown.getClass.getName}: ${thrown.getMessage}", thrown.isInstanceOf[IllegalArgumentException])
    val msg = thrown.getMessage
    assertTrue(msg, msg != null && msg.startsWith(s"Parquet file '$file' "))
    LibraryWords.foreach(w => assertFalse(s"'$w' in: $msg", msg.contains(w)))
    assertFalse(msg, """\bnull\b""".r.findFirstIn(msg).isDefined)
    msg
  }

  /** `bytes` with `from`, which occurs in it exactly once, replaced by `to`. */
  private def replaceOnce(bytes: Array[Byte], from: Seq[Int], to: Seq[Int]): Array[Byte] = {
    val pattern = from.map(_.toByte).toArray
    val at = bytes.indices.filter(i => bytes.length - i >= pattern.length &&
      java.util.Arrays.equals(bytes, i, i + pattern.length, pattern, 0, pattern.length))
    assertEquals(s"occurrences of ${from.map(b => f"$b%02x").mkString(" ")}", 1, at.length)
    bytes.take(at.head) ++ to.map(_.toByte) ++ bytes.drop(at.head + pattern.length)
  }

  /** A footer that is not parquet's Thrift failed with Thrift's text, `can
    * not read class org.apache.parquet.format.FileMetaData: ...`. */
  @Test def aFooterThatIsNotParquetMetadataIsRefusedNamingTheFile(): Unit = {
    val file = writeFixture("message m { required int32 id; }", Seq(Seq(1)))
    ExternalParquet.rewriteFooterBytes(file)(bytes => Array.fill(bytes.length)(0x7f.toByte))
    val msg = refusalOf(file)(read(file))
    assertTrue(msg, msg.endsWith("has a footer that is not valid parquet metadata"))
  }

  /** A file that is not parquet at all failed with parquet-mr's
    * `RuntimeException`, `... is not a Parquet file. Expected magic number at
    * tail, but found [...]`. */
  @Test def aFileThatIsNotParquetIsRefusedNamingIt(): Unit = {
    val file = newDir().resolve("text.parquet")
    Files.writeString(file, "id,name\n1,a\n")
    val msg = refusalOf(file)(read(file))
    assertTrue(msg, msg.contains("is not a parquet file"))
  }

  /** A footer marked encrypted (`PARE`) failed with parquet-mr's `Trying to
    * read file with encrypted footer. No keys available`. */
  @Test def anEncryptedFileIsRefusedNamingIt(): Unit = {
    val file = writeFixture("message m { required int32 id; }", Seq(Seq(1)))
    ExternalParquet.rewriteFooterBytes(file, magic = "PARE")(identity)
    val msg = refusalOf(file)(read(file))
    assertTrue(msg, msg.endsWith("is encrypted, and transformer reads no encrypted parquet"))
  }

  /** A logical type newer than parquet-mr failed with a
    * `NullPointerException`'s text, `Cannot invoke
    * "org.apache.parquet.format.LogicalType$_Fields.ordinal()" ...`, which
    * named neither the file nor the column. It is refused naming both and
    * the annotation (plans/testing/13, decision 1). The footer is written as
    * a newer writer writes it: the `UUID` member of a column's logical type,
    * union member 14, an empty struct, becomes member `id`. */
  @Test def aLogicalTypeTheReaderDoesNotKnowIsRefusedNamingTheColumn(): Unit = {
    for ((id, annotation) <- Seq(
        17 -> "is fixed_len_byte_array(16) GEOMETRY, which has no transformer type",
        18 -> "is fixed_len_byte_array(16) GEOGRAPHY, which has no transformer type",
        2000 -> "with a logical type this reader does not know (numbered 2000), which has no transformer type")) {
      val file = writeFixture("message m { required int32 id; optional fixed_len_byte_array(16) u (UUID); }",
        Seq(Seq(1, null)))
      // The name 'u', then field 10, the logical type (delta 6, a struct),
      // holding member 14 (delta 14, a struct), empty, and the union's end.
      // A member past delta 15 is written long: a struct with no delta, its
      // id as a zigzag varint.
      val zigzag = id * 2
      val varint = if (zigzag < 128) Seq(zigzag) else Seq((zigzag & 0x7f) | 0x80, zigzag >>> 7)
      ExternalParquet.rewriteFooterBytes(file)(bytes =>
        replaceOnce(bytes, Seq(0x18, 0x01, 'u'.toInt, 0x6c, 0xec, 0x00, 0x00),
          Seq(0x18, 0x01, 'u'.toInt, 0x6c, 0x0c) ++ varint ++ Seq(0x00, 0x00)))
      val msg = refusalOf(file)(read(file))
      assertTrue(msg, msg.contains("column 'u' ") && msg.endsWith(annotation))
    }
  }

  /** An annotation the format does not allow on its physical type failed with
    * parquet-mr's words, `UUID can only annotate FIXED_LEN_BYTE_ARRAY(16)`. */
  @Test def anAnnotationTheFormatDoesNotAllowIsRefusedNamingTheColumn(): Unit = {
    val file = writeFixture("message m { required int32 id; optional int32 n; }", Seq(Seq(1, 2)))
    ExternalParquet.rewriteFooter(file)(_.getSchema.get(2).setLogicalType(LogicalType.UUID(new UUIDType())))
    val msg = refusalOf(file)(read(file))
    assertTrue(msg, msg.endsWith("column 'n' is int32 annotated UUID, which the format does not allow"))
  }

  /** A codec this classpath does not have failed the scan at its first page,
    * `Class org.apache.hadoop.io.compress.BrotliCodec was not found`; it fails
    * the view as it opens, naming the column and the codec (plans/testing/13,
    * decision 4). */
  @Test def aCodecTheReaderDoesNotHaveIsRefusedWhenTheViewOpens(): Unit = {
    for (codec <- Seq(CompressionCodec.BROTLI, CompressionCodec.LZO)) {
      val file = writeFixture("message m { required int32 id; }", Seq(Seq(1)))
      ExternalParquet.rewriteFooter(file)(
        _.getRow_groups.asScala.foreach(_.getColumns.asScala.foreach(_.getMeta_data.setCodec(codec))))
      val msg = refusalOf(file)(read(file))
      assertTrue(msg, msg.endsWith(s"column 'id' is compressed with $codec, which transformer cannot decompress"))
    }
  }

  /** Hadoop's LZ4 codec, which older Hadoop and Spark wrote (parquet's
    * `LZ4`, framed as Hadoop frames a block, not `LZ4_RAW`), decompresses
    * with the lz4 library, which was not on the classpath: such a file failed
    * with `java.lang.NoClassDefFoundError: net/jpountz/lz4/LZ4Factory`, then
    * was refused as a codec the reader does not have (plans/testing/13,
    * decision 4). Every row group of it reads, its pages v1 and v2, a
    * dictionary or none. */
  @Test def aHadoopLz4FileReads(): Unit = {
    val file = newDir().resolve("lz4.parquet")
    val schema = ExternalParquet.schema("message m { required int32 id; optional binary s (STRING); optional double d; }")
    def group(from: Int) = (from until from + 300).map(i => Seq[Any](i, if (i % 7 == 0) null else s"v${i % 13}", i * 0.5))
    ExternalParquet.writeRowGroups(file, schema, Seq(
      (group(0), ExternalParquet.Layout(codec = CompressionCodecName.LZ4)),
      (group(300), ExternalParquet.Layout(codec = CompressionCodecName.LZ4, dictionary = false,
        writerVersion = ParquetProperties.WriterVersion.PARQUET_2_0))))
    assertEquals((0 until 600).map(i => Seq[Any](i, if (i % 7 == 0) null else s"v${i % 13}", i * 0.5)),
      rows(read(file)))
  }

  /** A page header that is not parquet's Thrift failed with Thrift's text,
    * `can not read class org.apache.parquet.format.PageHeader: ...`. Its
    * column is found by reading each alone, and named with its row group. */
  @Test def aPageThatDoesNotDecodeIsRefusedNamingTheColumnAndRowGroup(): Unit = {
    val file = newDir().resolve("pages.parquet")
    val schema = ExternalParquet.schema("message m { required int32 id; required int32 n; }")
    ExternalParquet.writeRowGroups(file, schema, Seq(
      ((0 until 5).map(i => Seq[Any](i, i)), ExternalParquet.Layout(dictionary = false)),
      ((5 until 10).map(i => Seq[Any](i, i)), ExternalParquet.Layout(dictionary = false))))
    val offset = ExternalParquet.dataPageOffsets(file)(1)(1)
    val bytes = Files.readAllBytes(file)
    java.util.Arrays.fill(bytes, offset.toInt, offset.toInt + 8, 0xff.toByte)
    Files.write(file, bytes)
    val view = read(file)
    assertEquals(Some(10L), view.exactRowCount)
    val msg = refusalOf(file)(rows(view))
    assertTrue(msg, msg.endsWith("column 'n', row group 1: a page does not decode"))
  }

  /** parquet-mr writes a CRC of each page's bytes into its header, and read
    * none of them back: a page whose bytes changed after they were written
    * decoded to whatever they now said, here a value read as another. The
    * read fails, naming the column and row group (plans/testing/13,
    * decision 2), for a data page and for a dictionary page alike; a file
    * whose pages carry no CRC reads as before. */
  @Test def aPageThatDoesNotMatchItsChecksumFailsTheRead(): Unit = {
    val schema = ExternalParquet.schema("message m { required int32 id; required int32 n; }")
    for ((dictionary, what) <- Seq(false -> "a data page", true -> "a dictionary page")) {
      val file = newDir().resolve("crc.parquet")
      ExternalParquet.writeRowGroups(file, schema, Seq(
        ((0 until 5).map(i => Seq[Any](i, i)), ExternalParquet.Layout(dictionary = false)),
        ((5 until 10).map(i => Seq[Any](i, 1000 + i % 2)), ExternalParquet.Layout(dictionary = dictionary))))
      // The last byte of the second group's 'n' before its data page: a
      // value of its dictionary page, or the last value of the first
      // group's 'n', whose data page ends where the second group starts.
      val at = if (dictionary) ExternalParquet.dataPageOffsets(file)(1)(1) - 1 else ExternalParquet.dataPageOffsets(file)(1)(0) - 1
      val bytes = Files.readAllBytes(file)
      bytes(at.toInt) = (bytes(at.toInt) ^ 0x01).toByte
      Files.write(file, bytes)
      val group = if (dictionary) 1 else 0
      val msg = refusalOf(file)(rows(read(file)))
      assertTrue(s"$what: $msg",
        msg.endsWith(s"column 'n', row group $group: a page does not match its checksum, so its bytes are not the ones written"))
    }
  }

  // ─────────────────────────────────────────────────────────────────────
  // Row groups: each read once, whatever bytes its footer gives it.
  // ─────────────────────────────────────────────────────────────────────

  /** A file of `n` row groups of ten ids each, counting from 0, with the
    * groups at `zero` given no bytes in the footer's `total_byte_size`, as a
    * writer that left the field unset wrote them. */
  private def zeroByteGroups(n: Int, zero: Set[Int]): Path = {
    val file = newDir().resolve("groups.parquet")
    ExternalParquet.writeRowGroups(file, ExternalParquet.schema("message m { required int32 id; }"),
      (0 until n).map(g => ((0 until 10).map(i => Seq[Any](g * 10 + i)), ExternalParquet.Layout())))
    ExternalParquet.rewriteFooter(file) { footer =>
      footer.getRow_groups.asScala.zipWithIndex.foreach { case (g, i) => if (zero(i)) g.setTotal_byte_size(0L) }
    }
    file
  }

  /** The reader packed row groups into partitions by `total_byte_size` alone
    * and emitted a partition only when its bytes were above 0, so a file of
    * one group whose footer gave it no bytes read as empty, `COUNT(*)` 0
    * (parquet-testing's `nation.dict-malformed.parquet`), and so did a
    * zero-byte group after a full partition. Every group is read once, in
    * order, and `COUNT(*)` counts every row. */
  @Test def aRowGroupIsReadWhateverBytesItsFooterGivesIt(): Unit = {
    val default = ParquetReader.DefaultTargetBytesPerPartition
    for ((n, zero, target) <- Seq(
        (1, Set(0), default),
        (3, Set(0, 1, 2), default),
        (3, Set(0), 1L),
        (3, Set(2), 1L),
        (4, Set(1, 3), 1L))) {
      val view = ParquetReader.fromPath(zeroByteGroups(n, zero).toString, ColumnarBatch.DefaultCapacity, target)
      val what = s"$n groups, those at $zero of no bytes, a target of $target bytes"
      assertEquals(what, (0 until n * 10).toVector, rows(view).map(_.head).toVector)
      assertEquals(what, Some(n * 10L), view.exactRowCount)
      assertEquals(what, Seq(Seq(n * 10L)), query("SELECT COUNT(*) FROM t", "t" -> view))
    }
  }

  /** The packing itself: every group lands in exactly one partition, the
    * partitions in order and each holding a group at least, whatever the
    * groups' bytes, none at all, a negative count, or more than the target. */
  @Test def everyRowGroupPacksIntoExactlyOnePartition(): Unit = {
    def block(bytes: Long, rows: Long): BlockMetaData = {
      val b = new BlockMetaData()
      b.setTotalByteSize(bytes)
      b.setRowCount(rows)
      b
    }
    for ((bytes, target) <- Seq(
        (Seq.empty[Long], 100L), (Seq(0L), 100L), (Seq(0L, 0L, 0L), 100L), (Seq(0L, 0L, 0L), 1L),
        (Seq(0L, 50L, 50L), 100L), (Seq(100L, 0L), 100L), (Seq(100L, 0L, 0L), 1L),
        (Seq(60L, 60L, 0L, 60L, 0L), 100L), (Seq(0L, 200L, 0L), 100L), (Seq(-5L, 10L), 1L))) {
      val blocks = bytes.zipWithIndex.map { case (b, i) => block(b, i + 1L) }
      val parts = ParquetReader.packRowGroups(null, blocks.asJava, target)
      val what = s"groups of $bytes bytes, a target of $target"
      assertEquals(what, bytes.indices.toVector, parts.flatMap(p => p.startRowGroup until p.endRowGroup).toVector)
      parts.foreach { p =>
        assertTrue(what, p.endRowGroup > p.startRowGroup)
        assertEquals(what, (p.startRowGroup until p.endRowGroup).map(_ + 1L).sum, p.rowCount)
      }
      if (target == 1L) assertEquals(what, bytes.length, parts.length)
    }
  }
}
