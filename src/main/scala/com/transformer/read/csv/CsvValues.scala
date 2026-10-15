package com.transformer.read.csv

import com.transformer.core._

import java.math.{BigDecimal => JBigDecimal}
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.time.format.DateTimeParseException

/** The text of each type's values in a CSV file: what the writer prints, what
  * the reader parses, and what inference recognizes, kept in one place so a
  * value the writer prints is a value the reader reads back as itself.
  *
  * The grammar is strict, and the same for an inferred column as for a
  * declared one:
  *
  *   - INT and BIGINT: an optional sign and ASCII digits (`007` is 7).
  *   - FLOAT and DOUBLE: a plain decimal number, optionally signed, with a
  *     point, digits either side of it, and an exponent (`1.5`, `.5`, `5.`,
  *     `-1e-5`), or `NaN`, `Infinity` and `-Infinity`, which the writer
  *     prints. Java's own grammar is not used: it reads `1d`, `0x1p3`, ` 1`
  *     and non-ASCII digits as numbers.
  *   - DECIMAL: a plain decimal number, read at the column's declared scale
  *     when it is one exactly, and refused when it would have to round or has
  *     more digits than the precision.
  *   - BOOLEAN: `true` or `false`, in any case. Anything else is an error,
  *     never `false`.
  *   - DATE: ISO `yyyy-MM-dd` (a year past 9999 or before 1 carries a sign).
  *   - TIMESTAMP: an ISO instant (`2024-01-01T12:00:00Z`, or with an offset),
  *     an ISO date-time with no zone (read as UTC), or a date alone (midnight
  *     UTC, as `CAST` reads one), kept to the microsecond toward the past.
  *   - BINARY: Base64.
  *   - STRING: the text itself.
  */
object CsvValues {

  /** A field whose text is no value of the type it was read as. Carries no
    * stack: the reader replaces it with an error naming the file, line and
    * column. */
  final class Unreadable(val typeName: String) extends RuntimeException(null, null, false, false)

  /** The most digits of a DECIMAL the engine computes in, and of the
    * DECIMAL an integer column past BIGINT infers as. */
  val MaxDecimalDigits: Int = 38

  /** The type an integer column whose values pass BIGINT infers as. */
  val WideIntegerType: DataType = DataType.DecimalType(MaxDecimalDigits, 0)

  // ---- the grammar ----------------------------------------------------------

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

  /** An optional sign and one or more ASCII digits. */
  private[csv] def isIntegerText(s: String): Boolean = {
    val start = if (s.nonEmpty && (s.charAt(0) == '+' || s.charAt(0) == '-')) 1 else 0
    start < s.length && (start until s.length).forall(i => isDigit(s.charAt(i)))
  }

  /** A plain decimal number: an optional sign, digits with an optional point
    * (at least one digit either side of it), and an optional exponent. */
  private[csv] def isDecimalText(s: String): Boolean = {
    var i = 0
    val n = s.length
    if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
    var digits = 0
    while (i < n && isDigit(s.charAt(i))) { i += 1; digits += 1 }
    if (i < n && s.charAt(i) == '.') {
      i += 1
      while (i < n && isDigit(s.charAt(i))) { i += 1; digits += 1 }
    }
    if (digits == 0) return false
    if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      i += 1
      if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
      val exponentStart = i
      while (i < n && isDigit(s.charAt(i))) i += 1
      if (i == exponentStart) return false
    }
    i == n
  }

  /** The words a FLOAT or DOUBLE also takes: what the writer prints for the
    * values that are no number. */
  private def isSpecialDouble(s: String): Boolean = s == "NaN" || s == "Infinity" || s == "-Infinity"

  // ---- reading ----------------------------------------------------------------

  /** Whether `dt` has a value whose text is empty: the empty string and the
    * empty byte string. A field of any other type that is empty is NULL. */
  def hasEmptyValue(dt: DataType): Boolean = dt == DataType.StringType || dt == DataType.BinaryType

  def int(s: String): Int = {
    if (!isIntegerText(s)) throw new Unreadable("int")
    try Integer.parseInt(s) catch { case _: NumberFormatException => throw new Unreadable("int") }
  }

  def long(s: String): Long = {
    if (!isIntegerText(s)) throw new Unreadable("long")
    try java.lang.Long.parseLong(s) catch { case _: NumberFormatException => throw new Unreadable("long") }
  }

  def double(s: String): Double =
    if (isDecimalText(s) || isSpecialDouble(s)) java.lang.Double.parseDouble(s) else throw new Unreadable("double")

  def float(s: String): Float =
    if (isDecimalText(s) || isSpecialDouble(s)) java.lang.Float.parseFloat(s) else throw new Unreadable("float")

  def boolean(s: String): Boolean =
    if (s.equalsIgnoreCase("true")) true
    else if (s.equalsIgnoreCase("false")) false
    else throw new Unreadable("boolean")

  /** Days since the epoch. */
  def date(s: String): Int =
    try {
      val days = LocalDate.parse(s).toEpochDay
      if (days < Int.MinValue || days > Int.MaxValue) throw new Unreadable("date")
      days.toInt
    } catch { case _: DateTimeParseException => throw new Unreadable("date") }

  /** Microseconds since the epoch. */
  def timestamp(s: String): Long = {
    val instant =
      try Instant.parse(s)
      catch {
        case _: DateTimeParseException =>
          try LocalDateTime.parse(s).toInstant(ZoneOffset.UTC)
          catch {
            case _: DateTimeParseException =>
              try LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant
              catch { case _: DateTimeParseException => throw new Unreadable("timestamp") }
          }
      }
    // The nanosecond of the second is never negative, so dividing it keeps the
    // earlier microsecond, before 1970 too.
    try Math.addExact(Math.multiplyExact(instant.getEpochSecond, 1000000L), (instant.getNano / 1000).toLong)
    catch { case _: ArithmeticException => throw new Unreadable("timestamp") }
  }

  /** `s` at `dt`'s scale, when it is a number that holds exactly at that
    * scale and within that precision. */
  def decimal(s: String, dt: DataType.DecimalType): JBigDecimal = {
    if (!isDecimalText(s)) throw new Unreadable(dt.name)
    val atScale =
      try new JBigDecimal(s).setScale(dt.scale)
      catch { case _: ArithmeticException => throw new Unreadable(dt.name) }
    if (atScale.precision > dt.precision) throw new Unreadable(dt.name)
    atScale
  }

  def binary(s: String): Array[Byte] =
    try java.util.Base64.getDecoder.decode(s)
    catch { case _: IllegalArgumentException => throw new Unreadable("binary") }

  /** Parse `s` into row `row` of `col`, a vector of type `dt`, or throw
    * [[Unreadable]]. */
  def parseInto(col: ColumnVector, row: Int, s: String, dt: DataType): Unit = dt match {
    case DataType.StringType => col.asInstanceOf[StringVector].set(row, s)
    case DataType.IntType => col.asInstanceOf[IntVector].set(row, int(s))
    case DataType.LongType => col.asInstanceOf[LongVector].set(row, long(s))
    case DataType.FloatType => col.asInstanceOf[FloatVector].set(row, float(s))
    case DataType.DoubleType => col.asInstanceOf[DoubleVector].set(row, double(s))
    case DataType.BooleanType => col.asInstanceOf[BooleanVector].set(row, boolean(s))
    case DataType.DateType => col.asInstanceOf[DateVector].set(row, date(s))
    case DataType.TimestampType => col.asInstanceOf[TimestampVector].set(row, timestamp(s))
    case d: DataType.DecimalType => col.asInstanceOf[DecimalVector].set(row, decimal(s, d))
    case DataType.BinaryType => col.asInstanceOf[BinaryVector].set(row, binary(s))
    // A NULL-typed column (`SELECT NULL AS x`, written and read back) holds
    // only the null string, which the reader never parses.
    case DataType.NullType => throw new Unreadable("null")
  }

  // ---- writing ----------------------------------------------------------------

  /** The text of row `row` of `v`, which is not NULL: what [[parseInto]] reads
    * back as the same value. */
  def format(v: ColumnVector, row: Int): String = v match {
    case s: StringVector => s.get(row)
    case i: IntVector => i.get(row).toString
    case l: LongVector => l.get(row).toString
    case f: FloatVector => java.lang.Float.toString(f.get(row))
    case d: DoubleVector => java.lang.Double.toString(d.get(row))
    case b: BooleanVector => b.get(row).toString
    case d: DateVector => LocalDate.ofEpochDay(d.get(row).toLong).toString
    case t: TimestampVector =>
      val micros = t.get(row)
      Instant.ofEpochSecond(Math.floorDiv(micros, 1000000L), Math.floorMod(micros, 1000000L) * 1000L).toString
    case d: DecimalVector => d.get(row).toPlainString
    case b: BinaryVector => java.util.Base64.getEncoder.encodeToString(b.get(row))
    case _: NullVectorImpl => throw new IllegalStateException("a NULL-typed vector holds no value")
  }

  // ---- inference ----------------------------------------------------------------

  /** The narrowest type a field's text reads as. */
  private[csv] def classify(s: String): DataType =
    if (isIntegerText(s)) {
      val digits = s.length - (if (s.charAt(0) == '+' || s.charAt(0) == '-') 1 else 0)
      // Leading zeros are no digits of the value.
      if (digits <= 9 || fits(s, Integer.parseInt)) DataType.IntType
      else if (digits <= 18 || fits(s, java.lang.Long.parseLong)) DataType.LongType
      else if (new JBigDecimal(s).precision <= MaxDecimalDigits) WideIntegerType
      else DataType.DoubleType
    }
    else if (isDecimalText(s) || isSpecialDouble(s)) DataType.DoubleType
    else if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) DataType.BooleanType
    else if (readsAs(s, date)) DataType.DateType
    else if (readsAs(s, timestamp)) DataType.TimestampType
    else DataType.StringType

  private def fits(s: String, parse: String => Any): Boolean =
    try { parse(s); true } catch { case _: NumberFormatException => false }

  private def readsAs(s: String, parse: String => Any): Boolean =
    try { parse(s); true } catch { case _: Unreadable => false }

  /** The narrowest type both `a` and `b` read as: numbers widen from INT to
    * BIGINT to the wide DECIMAL to DOUBLE, a DATE widens to a TIMESTAMP, and
    * any other two types share only STRING. */
  private[csv] def join(a: DataType, b: DataType): DataType = {
    def numberRank(t: DataType): Int = t match {
      case DataType.IntType => 1
      case DataType.LongType => 2
      case `WideIntegerType` => 3
      case DataType.DoubleType => 4
      case _ => 0
    }
    if (a == b) a
    else if (numberRank(a) > 0 && numberRank(b) > 0) { if (numberRank(a) >= numberRank(b)) a else b }
    else if (Set(a, b) == Set[DataType](DataType.DateType, DataType.TimestampType)) DataType.TimestampType
    else DataType.StringType
  }
}
