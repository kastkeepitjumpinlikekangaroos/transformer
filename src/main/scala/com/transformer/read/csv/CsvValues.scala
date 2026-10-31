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
  *   - INT, BIGINT, FLOAT and DOUBLE: [[NumberText]]'s grammar, which a CAST
  *     reads too: an optional sign and ASCII digits for the integers; a plain
  *     decimal number (`1.5`, `.5`, `5.`, `-1e-5`) or `NaN`, `Infinity` and
  *     `-Infinity`, which the writer prints, for the floats, one the type
  *     can hold. Java's own grammar is not used: it reads `1d`, `0x1p3`, ` 1`
  *     and non-ASCII digits as numbers, and `1e400` as an infinity.
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

  // ---- reading ----------------------------------------------------------------

  /** Whether `dt` has a value whose text is empty: the empty string and the
    * empty byte string. A field of any other type that is empty is NULL. */
  def hasEmptyValue(dt: DataType): Boolean = dt == DataType.StringType || dt == DataType.BinaryType

  def int(s: String): Int = try NumberText.int(s) catch { case NumberText.NotANumber => throw new Unreadable("int") }

  def long(s: String): Long = try NumberText.long(s) catch { case NumberText.NotANumber => throw new Unreadable("long") }

  def double(s: String): Double =
    try NumberText.double(s) catch { case NumberText.NotANumber => throw new Unreadable("double") }

  def float(s: String): Float =
    try NumberText.float(s) catch { case NumberText.NotANumber => throw new Unreadable("float") }

  def boolean(s: String): Boolean =
    if (s.equalsIgnoreCase("true")) true
    else if (s.equalsIgnoreCase("false")) false
    else throw new Unreadable("boolean")

  /** Days since the epoch, for a date a DATE holds ([[DateVector.epochDay]]). */
  def date(s: String): Int =
    try DateVector.epochDay(LocalDate.parse(s))
    catch { case _: DateTimeParseException | _: IllegalArgumentException => throw new Unreadable("date") }

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
    try TimestampVector.epochMicros(instant)
    catch { case _: IllegalArgumentException => throw new Unreadable("timestamp") }
  }

  /** `s` at `dt`'s scale, when it is a number that holds exactly at that
    * scale and within that precision. */
  def decimal(s: String, dt: DataType.DecimalType): JBigDecimal = {
    val atScale =
      try NumberText.decimal(s).setScale(dt.scale)
      catch { case NumberText.NotANumber | _: ArithmeticException => throw new Unreadable(dt.name) }
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

  /** The narrowest type a field's text reads as. A number no DOUBLE holds
    * (`1e400`, or an integer of 400 digits) is text. */
  private[csv] def classify(s: String): DataType =
    if (NumberText.isInteger(s)) {
      val digits = s.length - (if (s.charAt(0) == '+' || s.charAt(0) == '-') 1 else 0)
      // Leading zeros are no digits of the value.
      if (digits <= 9 || readsAs(s, int)) DataType.IntType
      else if (digits <= 18 || readsAs(s, long)) DataType.LongType
      else if (new JBigDecimal(s).precision <= MaxDecimalDigits) WideIntegerType
      else if (readsAs(s, double)) DataType.DoubleType
      else DataType.StringType
    }
    else if (readsAs(s, double)) DataType.DoubleType
    else if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) DataType.BooleanType
    else if (readsAs(s, date)) DataType.DateType
    else if (readsAs(s, timestamp)) DataType.TimestampType
    else DataType.StringType

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
