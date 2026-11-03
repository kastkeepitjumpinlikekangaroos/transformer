package com.transformer.fuzz

import com.transformer.core.{ColumnarBatch, DataType}
import com.transformer.read.parquet.ExternalParquet
import com.transformer.read.parquet.ExternalParquet.Layout
import com.transformer.sql.plan._
import org.apache.parquet.column.ParquetProperties.WriterVersion
import org.apache.parquet.hadoop.metadata.CompressionCodecName

import java.math.{BigDecimal => JBigDecimal, BigInteger}
import java.time.{Instant, LocalDate, ZoneOffset}
import java.time.format.DateTimeFormatter
import java.time.temporal.{ChronoUnit, JulianFields}

/** Generator of parquet files written by parquet-mr, never by transformer, for
  * the external-format differential (`oracle/ParquetDifferential`).
  *
  * A case is a glob of one to three files and a way to read them. Each column
  * of the view has one engine type, and each file stores it in one of the
  * layouts parquet allows for that type ([[Kind]]): a TIMESTAMP as MILLIS,
  * MICROS, NANOS or INT96, a DECIMAL as INT32, INT64, a fixed-length or a
  * variable-length two's complement, an INT as a plain or an 8- or 16-bit
  * INT32, and so on. So a glob can store one column four ways, the way files
  * from different tools do. A later file may order its columns differently,
  * allow NULLs the first does not, or carry a column the first lacks.
  *
  * Values are drawn as the bits a file stores, from pools that put the edges
  * of each type side by side (`Int.MinValue` and `-1` in an unsigned column, a
  * NANOS value a few nanoseconds before 1970, `NaN` payloads, a half-precision
  * subnormal, a decimal at the end of its precision). Each [[Kind]] says what
  * those bits stand for, by the format's spec and computed with `java.time`
  * and `java.math` rather than with any code under test: [[Kind.expected]] is
  * the engine value our reader must produce, and [[Kind.rendered]] is how
  * parquet-mr's own stringifier prints the same value. The oracle holds our
  * reader to the first and parquet-mr to the second, so a misreading of the
  * spec shared by this file and the reader still disagrees with parquet-mr.
  *
  * Row groups are cut exactly ([[ExternalParquet.writeRowGroups]]): one row,
  * a few, a few hundred, NULL runs across a boundary, an empty file. Each group
  * is laid out its own way ([[Layout]]): dictionary or plain, a dictionary
  * that overflows into plain pages, pages of one row, data pages v1 or v2,
  * byte-stream-split floats, and every codec the classpath carries.
  */
object ParquetGen {

  // ---- what a file stores --------------------------------------------------

  /** One way a file stores a column: its type in the footer, the stored
    * values it draws, and what each stands for. */
  sealed abstract class Kind(val engineType: DataType) {
    /** The physical type as the footer's text spells it: `int64`,
      * `fixed_len_byte_array(16)`. */
    def physical: String
    /** The annotation, with its leading space, or "". */
    def annotation: String = ""
    /** A stored value, as [[ExternalParquet.write]] takes it. */
    def draw(rng: Rng): Any
    /** The engine value the format says `stored` is. */
    def expected(stored: Any): Any
    /** How parquet-mr's stringifier prints `stored`, computed from what it
      * stands for; None for a type it prints without reading (INT96). */
    def rendered(stored: Any): Option[String]
    /** A stored value no engine value holds, so reading it must fail, naming
      * the column. Only a TIMESTAMP(MILLIS) past the microsecond range. */
    def unreadable(stored: Any): Boolean = false
    /** A value this kind draws no engine value for: [[unreadable]]'s
      * counterpart, drawn rarely and on purpose. */
    def drawUnreadable(rng: Rng): Option[Any] = None

    override def toString: String = s"$physical$annotation"
  }

  object Kind {
    case object Bool extends Kind(DataType.BooleanType) {
      def physical = "boolean"
      def draw(rng: Rng): Any = rng.bool()
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = Some(stored.toString)
    }

    /** An INT32 read as an INT: unannotated, or an integer of `bits` that
      * every value fits. */
    final case class Int32(bits: Option[Int], signed: Boolean) extends Kind(DataType.IntType) {
      require(signed || bits.exists(_ < 32))
      def physical = "int32"
      override def annotation: String = bits.fold("")(b => s" (INTEGER($b,$signed))")
      def draw(rng: Rng): Any = bits match {
        case None | Some(32) => drawInt(rng)
        case Some(b) =>
          val (lo, hi) = if (signed) (-(1 << (b - 1)), (1 << (b - 1)) - 1) else (0, (1 << b) - 1)
          rng.weighted((1, lo), (1, hi), (1, if (signed) -1 else 1), (1, 0), (4, rng.between(lo, hi)))
      }
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = Some(stored.toString)
    }

    /** An INT32 holding an unsigned 32-bit integer, read as the BIGINT that
      * holds its top half too. */
    case object UInt32 extends Kind(DataType.LongType) {
      def physical = "int32"
      override def annotation = " (INTEGER(32,false))"
      def draw(rng: Rng): Any = drawInt(rng)
      def expected(stored: Any): Any = java.lang.Integer.toUnsignedLong(stored.asInstanceOf[Int])
      def rendered(stored: Any): Option[String] = Some(java.lang.Integer.toUnsignedString(stored.asInstanceOf[Int]))
    }

    /** An INT64 read as a BIGINT: unannotated or a signed 64-bit integer. */
    final case class Int64(annotated: Boolean) extends Kind(DataType.LongType) {
      def physical = "int64"
      override def annotation: String = if (annotated) " (INTEGER(64,true))" else ""
      def draw(rng: Rng): Any = drawLong(rng)
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = Some(stored.toString)
    }

    /** An INT64 holding an unsigned 64-bit integer, which no engine integer
      * holds, read as a DECIMAL(20,0). */
    case object UInt64 extends Kind(DataType.DecimalType(20, 0)) {
      def physical = "int64"
      override def annotation = " (INTEGER(64,false))"
      def draw(rng: Rng): Any = drawLong(rng)
      def expected(stored: Any): Any =
        new JBigDecimal(new BigInteger(java.lang.Long.toUnsignedString(stored.asInstanceOf[Long])))
      def rendered(stored: Any): Option[String] = Some(java.lang.Long.toUnsignedString(stored.asInstanceOf[Long]))
    }

    case object Date extends Kind(DataType.DateType) {
      def physical = "int32"
      override def annotation = " (DATE)"
      def draw(rng: Rng): Any = rng.weighted((2, rng.oneOf(DateEdges)), (3, rng.between(-800000, 3000000)))
      def expected(stored: Any): Any = LocalDate.ofEpochDay(stored.asInstanceOf[Int].toLong)
      def rendered(stored: Any): Option[String] =
        Some(DateText.format(LocalDate.ofEpochDay(stored.asInstanceOf[Int].toLong).atStartOfDay(ZoneOffset.UTC).toInstant))
    }

    /** An INT64 counting `unit`s since the epoch. The engine holds
      * microseconds, so NANOS loses its last three digits, toward the past
      * (the earlier microsecond before 1970 too), and MILLIS past what a long
      * of microseconds holds is unreadable. Whether the file marks it UTC
      * changes nothing the engine reads: one timestamp type, a UTC instant. */
    final case class Timestamp(unit: ChronoUnit, utc: Boolean) extends Kind(DataType.TimestampType) {
      require(unit == ChronoUnit.MILLIS || unit == ChronoUnit.MICROS || unit == ChronoUnit.NANOS)
      private val unitName = unit match {
        case ChronoUnit.MILLIS => "MILLIS"
        case ChronoUnit.MICROS => "MICROS"
        case _ => "NANOS"
      }
      def physical = "int64"
      override def annotation = s" (TIMESTAMP($unitName,$utc))"
      def draw(rng: Rng): Any = unit match {
        case ChronoUnit.MILLIS =>
          rng.weighted((2, rng.oneOf(MillisEdges)), (3, rng.nextLong() % 100000000000000L))
        case ChronoUnit.MICROS =>
          rng.weighted((2, rng.oneOf(SubUnitEdges ++ LongEdges)), (2, rng.nextLong() % 100000000000000000L),
            (1, rng.nextLong()))
        case _ =>
          rng.weighted((2, rng.oneOf(SubUnitEdges ++ LongEdges)), (2, rng.nextLong()))
      }
      override def drawUnreadable(rng: Rng): Option[Any] =
        if (unit == ChronoUnit.MILLIS) Some(rng.oneOf(Seq(Long.MaxValue, MaxMillis + 1, Long.MinValue, MinMillis - 1)))
        else None
      override def unreadable(stored: Any): Boolean = unit == ChronoUnit.MILLIS && {
        val v = stored.asInstanceOf[Long]
        v > MaxMillis || v < MinMillis
      }
      /** The instant the value stands for, to the nanosecond. */
      private def instant(v: Long): Instant = unit match {
        case ChronoUnit.MILLIS => Instant.ofEpochMilli(v)
        case ChronoUnit.MICROS => Instant.ofEpochSecond(Math.floorDiv(v, 1000000L), Math.floorMod(v, 1000000L) * 1000L)
        case _ => Instant.ofEpochSecond(Math.floorDiv(v, 1000000000L), Math.floorMod(v, 1000000000L))
      }
      def expected(stored: Any): Any = instant(stored.asInstanceOf[Long]).truncatedTo(ChronoUnit.MICROS)
      def rendered(stored: Any): Option[String] = {
        val digits = unit match { case ChronoUnit.MILLIS => 3; case ChronoUnit.MICROS => 6; case _ => 9 }
        val pattern = "yyyy-MM-dd'T'HH:mm:ss." + ("S" * digits) + (if (utc) "Z" else "")
        Some(DateTimeFormatter.ofPattern(pattern).withZone(ZoneOffset.UTC).format(instant(stored.asInstanceOf[Long])))
      }
    }

    /** The legacy 12-byte timestamp: nanoseconds of the day as a
      * little-endian long, then a Julian day number as a little-endian int.
      * The day is read through `java.time`'s own Julian-day field, so this
      * shares no arithmetic with the reader. parquet-mr prints INT96 as its
      * bytes, so it has no rendering to compare with. */
    case object Int96 extends Kind(DataType.TimestampType) {
      def physical = "int96"
      def draw(rng: Rng): Any = {
        val day = rng.weighted((2, rng.oneOf(JulianDayEdges)), (3, rng.between(1721426, 5373484)))
        val nanos = rng.weighted((2, rng.oneOf(Seq(0L, 1L, 999L, 1000L, 1001L, 86399999999999L))),
          (3, Math.floorMod(rng.nextLong(), 86400000000000L)))
        val b = java.nio.ByteBuffer.allocate(12).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.putLong(nanos).putInt(day)
        b.array()
      }
      def expected(stored: Any): Any = {
        val b = java.nio.ByteBuffer.wrap(stored.asInstanceOf[Array[Byte]]).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val nanos = b.getLong
        val day = b.getInt
        LocalDate.EPOCH.`with`(JulianFields.JULIAN_DAY, day.toLong).atStartOfDay(ZoneOffset.UTC).toInstant
          .plusNanos(nanos).truncatedTo(ChronoUnit.MICROS)
      }
      def rendered(stored: Any): Option[String] = None
    }

    case object Float32 extends Kind(DataType.FloatType) {
      def physical = "float"
      def draw(rng: Rng): Any =
        rng.weighted((3, rng.oneOf(FloatEdges)), (2, rng.between(-1000, 1000) / 8.0f),
          (2, java.lang.Float.intBitsToFloat(rng.nextInt())))
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = Some(java.lang.Float.toString(stored.asInstanceOf[Float]))
    }

    /** IEEE half precision in a FIXED_LEN_BYTE_ARRAY(2), little-endian, read
      * as the FLOAT that holds every half exactly. Decoded here from its sign,
      * exponent and mantissa bits rather than with `Float.float16ToFloat`. */
    case object Float16 extends Kind(DataType.FloatType) {
      def physical = "fixed_len_byte_array(2)"
      override def annotation = " (FLOAT16)"
      def draw(rng: Rng): Any = {
        val bits = rng.weighted((3, rng.oneOf(HalfEdges)), (2, rng.nextInt(65536)))
        Array((bits & 0xff).toByte, ((bits >> 8) & 0xff).toByte)
      }
      def expected(stored: Any): Any = {
        val b = stored.asInstanceOf[Array[Byte]]
        val bits = (b(0) & 0xff) | ((b(1) & 0xff) << 8)
        val sign = if ((bits & 0x8000) != 0) -1.0 else 1.0
        val exponent = (bits >> 10) & 0x1f
        val mantissa = bits & 0x3ff
        val value: Double =
          if (exponent == 0) sign * mantissa * Math.pow(2, -24)
          else if (exponent == 31) { if (mantissa == 0) sign * Double.PositiveInfinity else Double.NaN }
          else sign * (1.0 + mantissa / 1024.0) * Math.pow(2, exponent - 15)
        value.toFloat
      }
      def rendered(stored: Any): Option[String] = Some(java.lang.Float.toString(expected(stored).asInstanceOf[Float]))
    }

    case object Float64 extends Kind(DataType.DoubleType) {
      def physical = "double"
      def draw(rng: Rng): Any =
        rng.weighted((3, rng.oneOf(DoubleEdges)), (2, rng.between(-1000, 1000) / 8.0),
          (2, java.lang.Double.longBitsToDouble(rng.nextLong())))
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = Some(java.lang.Double.toString(stored.asInstanceOf[Double]))
    }

    /** UTF-8 text: STRING, ENUM or JSON, all read as a STRING. */
    final case class Text(logical: String) extends Kind(DataType.StringType) {
      def physical = "binary"
      override def annotation = s" ($logical)"
      def draw(rng: Rng): Any = drawText(rng)
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = Some(stored.asInstanceOf[String])
    }

    /** Bytes read as they are stored: an unannotated BINARY, a BSON document,
      * an unannotated FIXED_LEN_BYTE_ARRAY or a UUID. */
    final case class Bytes(logical: Option[String], fixed: Option[Int]) extends Kind(DataType.BinaryType) {
      def physical: String = fixed.fold("binary")(n => s"fixed_len_byte_array($n)")
      override def annotation: String = logical.fold("")(l => s" ($l)")
      def draw(rng: Rng): Any = fixed match {
        case Some(n) => randomBytes(rng, n)
        case None => randomBytes(rng, rng.weighted((1, 0), (5, rng.between(1, 8)), (3, rng.between(9, 40)),
          (1, rng.between(1000, 3000))))
      }
      def expected(stored: Any): Any = stored
      def rendered(stored: Any): Option[String] = {
        val b = stored.asInstanceOf[Array[Byte]]
        if (logical.contains("UUID")) {
          val buf = java.nio.ByteBuffer.wrap(b)
          Some(new java.util.UUID(buf.getLong, buf.getLong).toString)
        } else Some(b.iterator.map(x => f"${x & 0xff}%02X").mkString("0x", "", ""))
      }
    }

    /** A DECIMAL(`precision`, `scale`), its unscaled value stored as an
      * INT32, an INT64, a big-endian two's complement of `fixed` bytes
      * (sign-extended), or a BINARY of any length (`fixed` None, sometimes
      * with redundant sign bytes in front). */
    final case class Decimal(precision: Int, scale: Int, layout: String, fixed: Option[Int])
        extends Kind(DataType.DecimalType(precision, scale)) {
      require(Seq("int32", "int64", "fixed", "binary").contains(layout))
      def physical: String = layout match {
        case "fixed" => s"fixed_len_byte_array(${fixed.get})"
        case other => other
      }
      override def annotation = s" (DECIMAL($precision,$scale))"
      def draw(rng: Rng): Any = {
        val unscaled = drawUnscaled(rng, precision)
        layout match {
          case "int32" => unscaled.intValueExact
          case "int64" => unscaled.longValueExact
          case "fixed" => signExtended(unscaled, fixed.get)
          case _ =>
            val minimal = unscaled.toByteArray
            signExtended(unscaled, minimal.length + rng.weighted((4, 0), (1, rng.between(1, 3))))
        }
      }
      private def unscaledOf(stored: Any): BigInteger = stored match {
        case i: Int => BigInteger.valueOf(i.toLong)
        case l: Long => BigInteger.valueOf(l)
        case b: Array[Byte] => new BigInteger(b)
        case other => throw new IllegalArgumentException(s"no unscaled value in $other")
      }
      def expected(stored: Any): Any = new JBigDecimal(unscaledOf(stored), scale)
      def rendered(stored: Any): Option[String] = Some(new JBigDecimal(unscaledOf(stored), scale).toString)
    }
  }

  // ---- pools ----------------------------------------------------------------

  private val IntEdges: Seq[Int] = Seq(Int.MinValue, Int.MinValue + 1, -1, 0, 1, Int.MaxValue - 1, Int.MaxValue)

  private def drawInt(rng: Rng): Int =
    rng.weighted((3, rng.oneOf(IntEdges)), (4, rng.between(-1000, 1000)), (3, rng.nextInt()))

  /** Long edges, and the integers either side of where a double stops
    * holding every one. */
  private val LongEdges: Seq[Long] = Seq(Long.MinValue, Long.MinValue + 1, -1L, 0L, 1L, Long.MaxValue - 1,
    Long.MaxValue, 1L << 53, (1L << 53) + 1, -((1L << 53) + 1), Int.MaxValue.toLong + 1)

  private def drawLong(rng: Rng): Long =
    rng.weighted((3, rng.oneOf(LongEdges)), (4, rng.between(-1000, 1000).toLong), (3, rng.nextLong()))

  private def epochDay(y: Int, m: Int, d: Int): Int = LocalDate.of(y, m, d).toEpochDay.toInt

  private val DateEdges: Seq[Int] = Seq(0, -1, 1, epochDay(2000, 2, 29), epochDay(2024, 2, 29),
    epochDay(1, 1, 1), epochDay(0, 12, 31), epochDay(9999, 12, 31), epochDay(10000, 1, 1),
    epochDay(1582, 10, 15), Int.MinValue, Int.MaxValue)

  private[fuzz] val MaxMillis: Long = Long.MaxValue / 1000L
  private[fuzz] val MinMillis: Long = Long.MinValue / 1000L

  private val MillisEdges: Seq[Long] = Seq(0L, -1L, 1L, 1700000000123L, -62135596800000L, 253402300799999L,
    MaxMillis, MinMillis)

  /** Either side of the epoch and of a microsecond: what a truncation toward
    * zero instead of toward the past gets wrong. */
  private val SubUnitEdges: Seq[Long] = Seq(-1500L, -1001L, -1000L, -999L, -1L, 999L, 1000L, 1001L)

  /** 1970-01-01 and its neighbours, the Gregorian cutover, 0001-01-01 and
    * 9999-12-31, as Julian day numbers. */
  private val JulianDayEdges: Seq[Int] = Seq(2440588, 2440587, 2440589, 2299161, 2299160, 1721426, 5373484)

  private val FloatEdges: Seq[Float] = Seq(Float.NaN, Float.PositiveInfinity, Float.NegativeInfinity, -0.0f, 0.0f,
    Float.MinPositiveValue, Float.MaxValue, Float.MinValue, java.lang.Float.MIN_NORMAL, 1.0f, -1.5f, 0.1f,
    java.lang.Float.intBitsToFloat(0x7fc00001), java.lang.Float.intBitsToFloat(0xffc00000))

  private val DoubleEdges: Seq[Double] = Seq(Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity, -0.0, 0.0,
    Double.MinPositiveValue, Double.MaxValue, Double.MinValue, java.lang.Double.MIN_NORMAL, 1.0, -1.5, 0.1,
    java.lang.Double.longBitsToDouble(0x7ff8000000000001L), java.lang.Double.longBitsToDouble(0xfff8000000000000L))

  /** Half-precision bit patterns: both zeros, one, the largest finite, the
    * smallest subnormal and normal, the infinities, and NaNs. */
  private val HalfEdges: Seq[Int] = Seq(0x0000, 0x8000, 0x3c00, 0xbc00, 0x7bff, 0xfbff, 0x0001, 0x8001, 0x03ff,
    0x0400, 0x7c00, 0xfc00, 0x7e00, 0x7c01, 0xfe00, 0x3555)

  /** Text whose characters sit either side of where UTF-8 and UTF-16 order
    * strings apart (U+E000 to U+FFFF against a pair past U+FFFF), so a range
    * between two of them orders one way in a file's statistics and the other
    * in the engine; and the rest of the encodings. */
  private val TextPool: Seq[String] = Seq("", "a", "abc", "A", "\u00e9", "\u65e5\u672c\u8a9e", "\ud83d\ude00",
    "\ud83d\ude00a", "\ud834\udd1eclef", "\u0000", "\ufffd", "\ue000", "\uffff", "\uffffz", "a\ue000", "a\ud83d\ude00",
    "a,b", "\"quoted\"", "line\nbreak", " spaced ", "x" * 1000)

  /** A string of code points from every range UTF-8 encodes differently:
    * ASCII and its controls, two-byte, three-byte (around the surrogates, which
    * are no characters), and four-byte. */
  private def drawText(rng: Rng): String =
    rng.weighted((5, rng.oneOf(TextPool)), (4, {
      val sb = new java.lang.StringBuilder
      var i = rng.between(0, 12)
      while (i > 0) {
        val cp = rng.weighted((4, rng.between(0x20, 0x7e)), (1, rng.between(0x00, 0x1f)),
          (2, rng.between(0x80, 0x7ff)), (2, rng.between(0x800, 0xd7ff)), (1, rng.between(0xe000, 0xffff)),
          (2, rng.between(0x10000, 0x10ffff)))
        sb.appendCodePoint(cp)
        i -= 1
      }
      sb.toString
    }))

  private def randomBytes(rng: Rng, n: Int): Array[Byte] = {
    val b = new Array[Byte](n)
    var i = 0
    while (i < n) { b(i) = rng.nextInt().toByte; i += 1 }
    b
  }

  /** An unscaled value of at most `precision` digits: zero, one, the largest
    * and smallest, or uniform among them. */
  private def drawUnscaled(rng: Rng, precision: Int): BigInteger = {
    val max = BigInteger.TEN.pow(precision).subtract(BigInteger.ONE)
    rng.weighted(
      (2, rng.oneOf(Seq(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE.negate, max, max.negate,
        max.subtract(BigInteger.ONE)))),
      (4, {
        val r = new BigInteger(max.bitLength + 8, new java.util.Random(rng.nextLong()))
        r.mod(max.shiftLeft(1).add(BigInteger.ONE)).subtract(max)
      }),
      (2, {
        val small = max.min(BigInteger.valueOf(1000)).intValueExact
        BigInteger.valueOf(rng.between(-small, small).toLong)
      }))
  }

  /** `unscaled` as `length` big-endian two's-complement bytes. */
  private def signExtended(unscaled: BigInteger, length: Int): Array[Byte] = {
    val minimal = unscaled.toByteArray
    require(minimal.length <= length, s"$unscaled needs ${minimal.length} bytes, not $length")
    val pad: Byte = if (unscaled.signum < 0) -1 else 0
    Array.fill[Byte](length - minimal.length)(pad) ++ minimal
  }

  /** The most digits every unscaled value a `length`-byte two's complement
    * holds can have. */
  private[fuzz] def maxDigits(length: Int): Int =
    BigInteger.ONE.shiftLeft(8 * length - 1).subtract(BigInteger.ONE).toString.length - 1

  private val DateText: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC)

  // ---- the case ------------------------------------------------------------

  /** A column of one file: its name, how the file stores it, and whether the
    * file forbids NULLs in it. */
  final case class FileColumn(name: String, kind: Kind, required: Boolean) {
    def footerLine: String = s"${if (required) "required" else "optional"} ${kind.physical} $name${kind.annotation};"
  }

  /** Rows of one row group, each one stored value (or null) per file column,
    * and how the group is laid out. */
  final case class RowGroup(rows: Vector[Vector[Any]], layout: Layout)

  final case class PqFile(name: String, columns: Vector[FileColumn], groups: Vector[RowGroup]) {
    def schemaText: String = columns.map(_.footerLine).mkString("message m { ", " ", " }")
    def numRows: Int = groups.iterator.map(_.rows.length).sum
  }

  /** A predicate offered to the reader for row-group skipping, over the
    * view's columns by name. Literals are engine values. */
  sealed trait Pred
  object Pred {
    final case class Cmp(column: String, op: String, literal: Any) extends Pred
    final case class IsNull(column: String, negated: Boolean) extends Pred
    final case class In(column: String, items: Vector[Any], negated: Boolean) extends Pred
    final case class Not(p: Pred) extends Pred
    final case class And(l: Pred, r: Pred) extends Pred
    final case class Or(l: Pred, r: Pred) extends Pred
  }

  /** How the view is read: the batch size, the target bytes per partition,
    * a projection, a pushdown predicate, and the order partitions are read
    * in (each opens the file on its own, so the order must not matter). */
  final case class ReadSpec(
      batchSize: Int,
      targetBytes: Long,
      projection: Option[Vector[String]],
      pushdown: Option[Pred],
      reversed: Boolean)

  final case class ParquetCase(files: Vector[PqFile], read: ReadSpec) {
    /** The view's columns: the first file's, in its order. */
    def viewColumns: Vector[String] = files.head.columns.map(_.name)

    override def toString: String = {
      val fs = files.map { f =>
        val groups = f.groups.map(g => s"${g.rows.length}${if (g.layout == Layout()) "" else g.layout.toString}")
        val preview = f.groups.iterator.flatMap(_.rows).take(4).map(_.map(show).mkString("(", ", ", ")")).mkString("; ")
        s"  ${f.name}: ${f.schemaText}\n    groups=[${groups.mkString(", ")}] rows=[$preview${if (f.numRows > 4) " ..." else ""}]"
      }
      val pushdown = read.pushdown.fold("none")(showPred)
      s"ParquetCase(\n${fs.mkString("\n")}\n  read: batchSize=${read.batchSize} targetBytes=${read.targetBytes} " +
        s"projection=${read.projection.fold("none")(_.mkString(","))} reversed=${read.reversed}\n  pushdown: $pushdown\n)"
    }
  }

  def showPred(p: Pred): String = p match {
    case Pred.Cmp(n, op, lit) => s"$n $op ${show(lit)}"
    case Pred.IsNull(n, neg) => s"$n IS ${if (neg) "NOT " else ""}NULL"
    case Pred.In(n, items, neg) => s"$n ${if (neg) "NOT " else ""}IN ${items.map(show).mkString("(", ", ", ")")}"
    case Pred.Not(q) => s"NOT (${showPred(q)})"
    case Pred.And(l, r) => s"(${showPred(l)}) AND (${showPred(r)})"
    case Pred.Or(l, r) => s"(${showPred(l)}) OR (${showPred(r)})"
  }

  /** A stored value as a failure message shows it. */
  def show(v: Any): String = v match {
    case null => "NULL"
    case a: Array[Byte] => RowOracle.hex(a)
    case f: Float => s"${f}f"
    case l: Long => s"${l}L"
    case s: String => "\"" + s.flatMap(c => if (c < 0x20 || c > 0x7e) f"\\u${c.toInt}%04x" else c.toString) + "\""
    case other => other.toString
  }

  // ---- generation ------------------------------------------------------------

  def generate(rng: Rng): ParquetCase = {
    val shapeRng = rng.split()
    val dataRng = rng.split()
    val readRng = rng.split()

    val numCols = shapeRng.weighted((2, 1), (4, 2), (3, 3), (2, 4), (1, 6))
    val types: Vector[DataType] = Vector.fill(numCols)(drawEngineType(shapeRng))
    val names = Vector.tabulate(numCols)(i => s"c$i")
    val numFiles = shapeRng.weighted((6, 1), (3, 2), (1, 3))

    val files = Vector.tabulate(numFiles) { f =>
      val own = names.indices.map(i => FileColumn(names(i), drawKind(shapeRng, types(i)), shapeRng.bool(0.3))).toVector
      // A later file may order its columns differently, and carry one the
      // first file lacks, which the view ignores.
      val extra =
        if (f > 0 && shapeRng.bool(0.15)) Vector(FileColumn(s"extra$f", drawKind(shapeRng, drawEngineType(shapeRng)), false))
        else Vector.empty
      val ordered = if (f > 0 && shapeRng.bool(0.3)) shuffle(shapeRng, own ++ extra) else own ++ extra
      PqFile(s"f$f.parquet", ordered, drawGroups(shapeRng, dataRng, ordered))
    }
    ParquetCase(files, drawRead(readRng, files, names))
  }

  private val Decimals: Seq[(Int, Int)] = Seq((1, 0), (5, 2), (9, 2), (9, 9), (10, 3), (18, 4), (18, 0), (20, 0),
    (25, 10), (38, 18), (38, 0))

  private def drawEngineType(rng: Rng): DataType = rng.weighted(
    (2, DataType.BooleanType), (3, DataType.IntType), (3, DataType.LongType), (2, DataType.DecimalType(20, 0)),
    (2, DataType.DateType), (4, DataType.TimestampType), (2, DataType.FloatType), (2, DataType.DoubleType),
    (3, DataType.StringType), (3, DataType.BinaryType), (4, {
      val (p, s) = rng.oneOf(Decimals)
      DataType.DecimalType(p, s)
    }))

  /** One of the layouts a file may store a column of engine type `dt` in. */
  private def drawKind(rng: Rng, dt: DataType): Kind = dt match {
    case DataType.BooleanType => Kind.Bool
    case DataType.IntType =>
      rng.oneOf(Seq(Kind.Int32(None, signed = true), Kind.Int32(Some(8), signed = true),
        Kind.Int32(Some(16), signed = true), Kind.Int32(Some(32), signed = true), Kind.Int32(Some(8), signed = false),
        Kind.Int32(Some(16), signed = false)))
    case DataType.LongType => rng.oneOf(Seq(Kind.Int64(false), Kind.Int64(true), Kind.UInt32))
    case DataType.DateType => Kind.Date
    case DataType.TimestampType =>
      rng.weighted((6, Kind.Timestamp(rng.oneOf(Seq(ChronoUnit.MILLIS, ChronoUnit.MICROS, ChronoUnit.NANOS)), rng.bool(0.8))),
        (2, Kind.Int96))
    case DataType.FloatType => rng.weighted((3, Kind.Float32), (2, Kind.Float16))
    case DataType.DoubleType => Kind.Float64
    case DataType.StringType => rng.weighted((4, Kind.Text("STRING")), (1, Kind.Text("ENUM")), (1, Kind.Text("JSON")))
    case DataType.BinaryType =>
      rng.weighted((4, Kind.Bytes(None, None)), (1, Kind.Bytes(Some("BSON"), None)),
        (2, Kind.Bytes(None, Some(rng.between(1, 20)))), (1, Kind.Bytes(Some("UUID"), Some(16))))
    case d: DataType.DecimalType if d == DataType.DecimalType(20, 0) && rng.bool(0.5) => Kind.UInt64
    case DataType.DecimalType(p, s) =>
      val fixedLength = (1 to 17).find(n => maxDigits(n) >= p).get
      val layouts =
        (if (p <= 9) Seq("int32") else Nil) ++ (if (p <= 18) Seq("int64") else Nil) ++ Seq("fixed", "binary")
      rng.oneOf(layouts) match {
        case "fixed" => Kind.Decimal(p, s, "fixed", Some(fixedLength + rng.weighted((4, 0), (1, rng.between(1, 3)))))
        case other => Kind.Decimal(p, s, other, None)
      }
    case other => throw new IllegalArgumentException(s"no parquet layout drawn for $other")
  }

  private def drawLayout(rng: Rng): Layout =
    if (rng.bool(0.3)) Layout()
    else Layout(
      dictionary = rng.bool(0.6),
      // parquet-mr refuses a page or dictionary under its 64-byte first
      // buffer.
      dictionaryPageSize = rng.weighted((3, Layout().dictionaryPageSize), (1, rng.between(64, 512))),
      pageSize = rng.weighted((3, Layout().pageSize), (1, rng.between(64, 1024))),
      pageRowLimit = rng.weighted((3, Layout().pageRowLimit), (1, 1), (2, rng.between(2, 50))),
      writerVersion = rng.weighted((1, WriterVersion.PARQUET_1_0), (1, WriterVersion.PARQUET_2_0)),
      byteStreamSplit = rng.bool(0.2),
      codec = rng.weighted((3, CompressionCodecName.UNCOMPRESSED), (2, CompressionCodecName.SNAPPY),
        (1, CompressionCodecName.GZIP), (2, CompressionCodecName.ZSTD), (1, CompressionCodecName.LZ4_RAW),
        (1, CompressionCodecName.LZ4)))

  /** Row groups of one file, possibly none. Each column draws from a small
    * domain shared by the whole file (repeats, so dictionaries pay), a domain
    * of its own per group (so groups' statistics differ and a predicate can
    * rule some out), or fresh values; and NULLs at a density of its own,
    * none where the file requires it. */
  private def drawGroups(shapeRng: Rng, dataRng: Rng, columns: Vector[FileColumn]): Vector[RowGroup] = {
    val numGroups = shapeRng.weighted((1, 0), (5, 1), (4, shapeRng.between(2, 4)), (1, shapeRng.between(5, 8)))
    val sizes = Vector.fill(numGroups)(shapeRng.weighted((3, 1), (4, shapeRng.between(2, 10)),
      (3, shapeRng.between(11, 100)), (1, shapeRng.between(101, 600))))
    val nullProb = columns.map(c => if (c.required) 0.0 else dataRng.weighted((4, 0.0), (3, 0.1), (2, 0.5), (1, 1.0)))
    val mode = columns.map(_ => dataRng.weighted((3, "file"), (2, "group"), (2, "fresh")))
    def domain(c: FileColumn): Vector[Any] = Vector.fill(dataRng.between(1, 6))(c.kind.draw(dataRng))
    val fileDomains = columns.map(domain)
    // At most one unreadable value per column, rarely: a read that must fail
    // checks nothing else, so it has to stay a small share of the corpus.
    val unreadableAt: Vector[Option[(Int, Int, Any)]] = columns.map { c =>
      if (sizes.nonEmpty && dataRng.bool(0.12)) c.kind.drawUnreadable(dataRng).map { v =>
        val g = dataRng.nextInt(sizes.length)
        (g, dataRng.nextInt(sizes(g)), v)
      } else None
    }
    sizes.zipWithIndex.map { case (n, g) =>
      val groupDomains = columns.map(domain)
      val rows = Vector.tabulate(n) { r =>
        columns.indices.map { c =>
          unreadableAt(c) match {
            case Some((`g`, `r`, v)) => v
            case _ =>
              if (dataRng.bool(nullProb(c))) null
              else mode(c) match {
                case "file" => dataRng.oneOf(fileDomains(c))
                case "group" => dataRng.oneOf(groupDomains(c))
                case _ => columns(c).kind.draw(dataRng)
              }
          }
        }.toVector
      }
      RowGroup(rows, drawLayout(shapeRng))
    }
  }

  private def drawRead(rng: Rng, files: Vector[PqFile], names: Vector[String]): ReadSpec = {
    val batchSize = rng.weighted((2, 1), (2, rng.between(2, 7)), (2, rng.between(8, 100)), (1, 1000),
      (3, ColumnarBatch.DefaultCapacity))
    val targetBytes = rng.weighted((3, 1L), (2, rng.between(50, 5000).toLong),
      (3, com.transformer.read.parquet.ParquetReader.DefaultTargetBytesPerPartition))
    val projection =
      if (names.length > 1 && rng.bool(0.35)) Some(shuffle(rng, names).take(rng.between(1, names.length - 1)))
      else None
    val pushdown = if (rng.bool(0.5)) Some(drawPred(rng, files, names, depth = 0)) else None
    ReadSpec(batchSize, targetBytes, projection, pushdown, reversed = rng.bool())
  }

  /** A predicate over one or two view columns, its literals taken from the
    * values the files hold (so they land inside some groups' ranges and
    * outside others') or drawn afresh. */
  private def drawPred(rng: Rng, files: Vector[PqFile], names: Vector[String], depth: Int): Pred = {
    if (depth < 2 && rng.bool(0.35))
      rng.weighted(
        (1, Pred.Not(drawPred(rng, files, names, depth + 1))),
        (2, Pred.And(drawPred(rng, files, names, depth + 1), drawPred(rng, files, names, depth + 1))),
        (2, Pred.Or(drawPred(rng, files, names, depth + 1), drawPred(rng, files, names, depth + 1))))
    else {
      val name = rng.oneOf(names)
      val dt = files.head.columns.find(_.name == name).get.kind.engineType
      def literal(): Any = {
        val held = for {
          f <- files
          c = f.columns.indexWhere(_.name == name)
          g <- f.groups
          row <- g.rows
          v = row(c)
          if v != null && !f.columns(c).kind.unreadable(v)
        } yield f.columns(c).kind.expected(v)
        val kind = files.head.columns.find(_.name == name).get.kind
        rng.weighted((7, 0), (2, 1), (2, 2)) match {
          case 0 if held.nonEmpty => rng.oneOf(held)
          case 2 => edgeOf(rng, dt).getOrElse(kind.expected(kind.draw(rng)))
          case _ => Iterator.continually(kind.draw(rng)).find(v => !kind.unreadable(v)).map(kind.expected).get
        }
      }
      val ops = dt match {
        case DataType.BooleanType | DataType.BinaryType => Seq("=", "!=")
        case _ => Seq("=", "!=", "<", "<=", ">", ">=")
      }
      rng.weighted(
        (5, Pred.Cmp(name, rng.oneOf(ops), literal())),
        (2, Pred.IsNull(name, rng.bool())),
        (2, Pred.In(name, Vector.fill(rng.between(1, 3))(literal()), rng.bool(0.3))))
    }
  }

  /** An engine value of type `dt` from the edges of the whole type rather
    * than of any one layout's range, so a bound can fall where a layout
    * stores nothing: a negative bound on an unsigned column, past every
    * value of a narrow one. */
  private def edgeOf(rng: Rng, dt: DataType): Option[Any] = dt match {
    case DataType.IntType => Some(rng.oneOf(IntEdges ++ Seq(-128, -129, 255, 256, -32768, 65535, 65536)))
    case DataType.LongType => Some(rng.oneOf(LongEdges))
    case DataType.FloatType => Some(rng.oneOf(FloatEdges))
    case DataType.DoubleType => Some(rng.oneOf(DoubleEdges))
    case DataType.DateType => Some(LocalDate.ofEpochDay(rng.oneOf(DateEdges).toLong))
    case DataType.StringType => Some(rng.oneOf(TextPool))
    case _ => None
  }

  private def shuffle[A](rng: Rng, xs: Vector[A]): Vector[A] = {
    val a = xs.toArray[Any]
    var i = a.length - 1
    while (i > 0) {
      val j = rng.nextInt(i + 1)
      val t = a(i); a(i) = a(j); a(j) = t
      i -= 1
    }
    a.toVector.asInstanceOf[Vector[A]]
  }

  // ---- reading the case back as the engine sees it --------------------------

  /** The predicate as a bound expression over `schema`, the way the planner
    * hands one to a scan. */
  def toExpr(p: Pred, schema: com.transformer.core.Schema): Expr = {
    def col(name: String): ColRefExpr = {
      val i = schema.indexOf(name)
      ColRefExpr(i, schema.fields(i).name, schema.fields(i).dataType)
    }
    p match {
      case Pred.Cmp(name, op, lit) =>
        val c = col(name)
        BinOpExpr(op, c, LitExpr(lit, c.dataType), DataType.BooleanType)
      case Pred.IsNull(name, negated) => IsNullExpr(col(name), negated)
      case Pred.In(name, items, negated) =>
        val c = col(name)
        InListExpr(c, items.map(LitExpr(_, c.dataType)), negated)
      case Pred.Not(q) => UnaryOpExpr("NOT", toExpr(q, schema), DataType.BooleanType)
      case Pred.And(l, r) => BinOpExpr("AND", toExpr(l, schema), toExpr(r, schema), DataType.BooleanType)
      case Pred.Or(l, r) => BinOpExpr("OR", toExpr(l, schema), toExpr(r, schema), DataType.BooleanType)
    }
  }

  /** Columns a predicate reads. */
  def columnsOf(p: Pred): Set[String] = p match {
    case Pred.Cmp(n, _, _) => Set(n)
    case Pred.IsNull(n, _) => Set(n)
    case Pred.In(n, _, _) => Set(n)
    case Pred.Not(q) => columnsOf(q)
    case Pred.And(l, r) => columnsOf(l) ++ columnsOf(r)
    case Pred.Or(l, r) => columnsOf(l) ++ columnsOf(r)
  }

  /** The case written to `dir`, one file per [[PqFile]]. */
  def writeTo(dir: java.nio.file.Path, c: ParquetCase): Unit =
    c.files.foreach { f =>
      ExternalParquet.writeRowGroups(dir.resolve(f.name), ExternalParquet.schema(f.schemaText),
        f.groups.map(g => (g.rows, g.layout)))
    }
}
