package com.transformer.sql.plan

import com.transformer.core._

import java.time.{Instant, LocalDate, LocalDateTime, OffsetDateTime, ZoneOffset}
import java.time.chrono.IsoChronology
import java.time.format.{DateTimeFormatter, DateTimeFormatterBuilder, DateTimeParseException, ResolverStyle}
import java.time.temporal.{ChronoField, ChronoUnit}
import java.util

/** Binary operator evaluation, plus NULL-safe equality and ordering helpers. */
object Ops {

  /** Evaluate `lv <op> rv`. Caller guarantees neither side is null. */
  def apply(op: String, lv: Any, rv: Any, lt: DataType, rt: DataType, out: DataType): Any = op match {
    case "+" => arith(lv, rv, out, Plus)
    case "-" => arith(lv, rv, out, Minus)
    case "*" => arith(lv, rv, out, Times)
    case "/" => arith(lv, rv, out, Div)
    case "%" => arith(lv, rv, out, Mod)
    case "="  | "==" => eq(lv, rv)
    case "<>" | "!=" => !eq(lv, rv)
    case "<"  => cmp(lv, rv) < 0
    case "<=" => cmp(lv, rv) <= 0
    case ">"  => cmp(lv, rv) > 0
    case ">=" => cmp(lv, rv) >= 0
    case "||" => lv.toString + rv.toString
    case other => throw new IllegalStateException(s"Unknown binary op '$other'")
  }

  // ---- Equality and the one total order ----
  //
  // Numbers compare by value, exactly where the types allow it. Two integers
  // compare as longs, and a decimal against any number as decimals: its scale
  // is how it is written, not part of the value, so 1.0 = 1.00. Every other
  // pair of numbers has a FLOAT or DOUBLE in it and compares as doubles, where
  // `eq` is IEEE `==` (NaN equals nothing, -0.0 equals 0.0) and `cmp` is
  // `java.lang.Double.compare` (NaN above everything, -0.0 below 0.0). A byte
  // array compares by content, ordered byte by byte as unsigned values, so a
  // prefix sorts first and 0x80 sorts above 0x7f. GROUP BY, DISTINCT and join
  // keys (`KeyCodec`) agree with `eq` on all of these but floats, which they
  // key by bit pattern.

  def eq(a: Any, b: Any): Boolean = (a, b) match {
    case (x: java.math.BigDecimal, y: java.lang.Number) => decimalCompare(x, y) == 0
    case (x: java.lang.Number, y: java.math.BigDecimal) => decimalCompare(y, x) == 0
    case (x: java.lang.Number, y: java.lang.Number) if isIntegral(x) && isIntegral(y) =>
      x.longValue == y.longValue
    case (x: java.lang.Number, y: java.lang.Number) =>
      x.doubleValue == y.doubleValue
    case (x: String, y: String) => x == y
    case (x: java.lang.Boolean, y: java.lang.Boolean) => x.booleanValue == y.booleanValue
    case (x: Array[Byte], y: Array[Byte]) => util.Arrays.equals(x, y)
    case (x, y) => x == y
  }

  def cmp(a: Any, b: Any): Int = (a, b) match {
    case (x: java.math.BigDecimal, y: java.lang.Number) => decimalCompare(x, y)
    case (x: java.lang.Number, y: java.math.BigDecimal) => -decimalCompare(y, x)
    case (x: java.lang.Number, y: java.lang.Number) if isIntegral(x) && isIntegral(y) =>
      java.lang.Long.compare(x.longValue, y.longValue)
    case (x: java.lang.Number, y: java.lang.Number) =>
      java.lang.Double.compare(x.doubleValue, y.doubleValue)
    case (x: String, y: String) => x.compareTo(y)
    case (x: java.lang.Boolean, y: java.lang.Boolean) => x.compareTo(y)
    case (x: Array[Byte], y: Array[Byte]) => util.Arrays.compareUnsigned(x, y)
    case (x: java.lang.Comparable[Any] @unchecked, y) => x.compareTo(y)
    case (x, y) => x.toString.compareTo(y.toString)
  }

  /** The boxes of INT and BIGINT, whose values a long holds exactly; past
    * 2^53 a double does not, and 2^53 + 1 would equal 2^53. */
  private def isIntegral(n: java.lang.Number): Boolean =
    n.isInstanceOf[java.lang.Long] || n.isInstanceOf[java.lang.Integer]

  /** Order a decimal against any number, by value. The analyzer widens a
    * number that meets a decimal through a CAST, so this sees a mixed pair only
    * where operands are not unified, such as NULLIF's two, or in a tree built
    * without the analyzer. The other side is read the way that CAST reads it
    * ([[Casts.toDecimal]]); NaN and the infinities have no decimal and take
    * their places in the total order instead. */
  private def decimalCompare(x: java.math.BigDecimal, y: java.lang.Number): Int = y match {
    case d: java.math.BigDecimal => x.compareTo(d)
    case _: java.lang.Double | _: java.lang.Float =>
      val d = y.doubleValue
      if (java.lang.Double.isNaN(d) || d == Double.PositiveInfinity) -1
      else if (d == Double.NegativeInfinity) 1
      else x.compareTo(Casts.toDecimal(y))
    case _ => x.compareTo(Casts.toDecimal(y))
  }

  private sealed abstract class Arith(val symbol: String)
  private object Plus extends Arith("+")
  private object Minus extends Arith("-")
  private object Times extends Arith("*")
  private object Div extends Arith("/")
  private object Mod extends Arith("%")

  private def arith(lv: Any, rv: Any, out: DataType, op: Arith): Any = {
    out match {
      case d: DataType.DecimalType => Decimals.arith(op.symbol, lv, rv, d)
      case DataType.IntType =>
        val l = lv.asInstanceOf[Number].intValue
        val r = rv.asInstanceOf[Number].intValue
        op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => if (r == 0) null else l / r
          case Mod => if (r == 0) null else l % r
        }
      case DataType.LongType =>
        val l = lv.asInstanceOf[Number].longValue
        val r = rv.asInstanceOf[Number].longValue
        op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => if (r == 0L) null else l / r
          case Mod => if (r == 0L) null else l % r
        }
      case DataType.FloatType =>
        // Compute in double (like VecOps.floatArith) then narrow to Float at
        // THIS node. The row path must narrow per-node, not just at the final
        // store: a float-typed intermediate that stays in double precision would
        // diverge from evalVec, which narrows every result on its FloatVector
        // store. Narrowing here keeps eval and evalVec equal under composition.
        val l = lv.asInstanceOf[Number].doubleValue
        val r = rv.asInstanceOf[Number].doubleValue
        (op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => l / r
          case Mod => l % r
        }).toFloat
      case DataType.DoubleType =>
        val l = lv.asInstanceOf[Number].doubleValue
        val r = rv.asInstanceOf[Number].doubleValue
        op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => l / r
          case Mod => l % r
        }
      case other => throw new IllegalStateException(s"Arithmetic on $other not supported")
    }
  }
}

/** Type-promoting casts at value level. Only invoked for non-null values. A
  * DATE comes back as a `LocalDate` and a TIMESTAMP as an `Instant`, the values
  * a `DateVector` and a `TimestampVector` box to, so the row path and the
  * vector path agree. */
object Casts {
  def cast(v: Any, from: DataType, to: DataType): Any = {
    if (v == null) return null
    if (from == to) return v
    to match {
      case DataType.StringType => v.toString
      case DataType.IntType =>
        v match {
          case s: String => parseNumber(s, to)(_.toInt)
          case n: Number => n.intValue
          case b: Boolean => if (b) 1 else 0
          case _ => v.toString.toInt
        }
      case DataType.LongType =>
        v match {
          case s: String => parseNumber(s, to)(_.toLong)
          case n: Number => n.longValue
          case b: Boolean => if (b) 1L else 0L
          case _ => v.toString.toLong
        }
      case DataType.FloatType =>
        v match {
          case s: String => parseNumber(s, to)(_.toFloat)
          case n: Number => n.floatValue
          case _ => v.toString.toFloat
        }
      case DataType.DoubleType =>
        v match {
          case s: String => parseNumber(s, to)(_.toDouble)
          case n: Number => n.doubleValue
          case _ => v.toString.toDouble
        }
      case DataType.BooleanType =>
        v match {
          case b: Boolean => b
          case s: String => parseBoolean(s)
          case n: Number => n.doubleValue != 0.0
          case _ => v.toString.equalsIgnoreCase("true")
        }
      case DataType.DateType =>
        v match {
          case d: LocalDate => d
          // A timestamp's date in UTC, like every time in the engine.
          case i: Instant => LocalDate.ofInstant(i, ZoneOffset.UTC)
          case s: String => parseDate(s)
          case _ => throw new IllegalArgumentException(s"Cannot cast $v to date")
        }
      case DataType.TimestampType =>
        v match {
          case i: Instant => i
          case d: LocalDate => d.atStartOfDay(ZoneOffset.UTC).toInstant
          case s: String => parseInstant(s)
          case _ => throw new IllegalArgumentException(s"Cannot cast $v to timestamp")
        }
      // The analyzer widens a number that meets a decimal to that decimal. The
      // value is kept exactly, at its own scale: a column's declared scale is
      // not enforced on what it holds, and rounding to it would change which
      // rows a comparison matches.
      case t: DataType.DecimalType =>
        v match {
          case d: java.math.BigDecimal => d
          case n: Number if isFinite(n) => toDecimal(n)
          case s: String => parseNumber(s, to)(new java.math.BigDecimal(_))
          case _ => throw new IllegalArgumentException(s"Cannot cast $v to $t")
        }
      case other => throw new IllegalArgumentException(s"CAST to $other not implemented")
    }
  }

  /** A number as the decimal it is: an integer exactly, and a FLOAT or DOUBLE
    * as the decimal it prints as, the shortest one that reads back as it, so
    * 0.1 is 0.1 rather than the binary fraction nearest it. NaN and the
    * infinities have no decimal; callers rule them out first. */
  def toDecimal(n: Number): java.math.BigDecimal = n match {
    case d: java.math.BigDecimal => d
    case d: java.lang.Double => java.math.BigDecimal.valueOf(d.doubleValue)
    case f: java.lang.Float => new java.math.BigDecimal(java.lang.Float.toString(f.floatValue))
    case i: java.math.BigInteger => new java.math.BigDecimal(i)
    case _ => java.math.BigDecimal.valueOf(n.longValue)
  }

  private[plan] def isFinite(n: Number): Boolean = n match {
    case _: java.lang.Double | _: java.lang.Float => java.lang.Double.isFinite(n.doubleValue)
    case _ => true
  }

  /** A number in the JDK's syntax, the text CSV inference reads as one. */
  private def parseNumber[T](s: String, to: DataType)(parse: String => T): T =
    try parse(s)
    catch { case _: NumberFormatException => throw new IllegalArgumentException(s"Cannot cast '$s' to $to") }

  /** `true` or `false` in any case, the text CSV inference reads as a
    * BOOLEAN. Any other text is an error rather than false. */
  private def parseBoolean(s: String): Boolean =
    if (s.equalsIgnoreCase("true")) true
    else if (s.equalsIgnoreCase("false")) false
    else throw new IllegalArgumentException(s"Cannot cast '$s' to boolean")

  /** The text a CAST to TIMESTAMP reads: an ISO date, then optionally a time
    * and then optionally `Z` or an offset. */
  private val TemporalText: DateTimeFormatter = new DateTimeFormatterBuilder()
    .append(DateTimeFormatter.ISO_LOCAL_DATE)
    .optionalStart()
    .appendLiteral('T')
    .append(DateTimeFormatter.ISO_LOCAL_TIME)
    .optionalStart().appendOffset("+HH:mm", "Z").optionalEnd()
    .optionalEnd()
    .toFormatter()
    .withChronology(IsoChronology.INSTANCE)
    .withResolverStyle(ResolverStyle.STRICT)

  /** A date alone is its midnight, and a time with no zone is UTC. The result
    * is truncated to the microseconds a `TimestampVector` holds. */
  private def parseInstant(s: String): Instant = {
    // SQL writes a space between the date and the time, ISO a T.
    val iso = if (s.length > 10 && s.charAt(10) == ' ') s.substring(0, 10) + "T" + s.substring(11) else s
    val parsed =
      try TemporalText.parse(iso)
      catch { case _: DateTimeParseException => throw new IllegalArgumentException(s"Cannot cast '$s' to timestamp") }
    val instant =
      if (parsed.isSupported(ChronoField.OFFSET_SECONDS)) OffsetDateTime.from(parsed).toInstant
      else if (parsed.isSupported(ChronoField.HOUR_OF_DAY)) LocalDateTime.from(parsed).toInstant(ZoneOffset.UTC)
      else LocalDate.from(parsed).atStartOfDay(ZoneOffset.UTC).toInstant
    instant.truncatedTo(ChronoUnit.MICROS)
  }

  /** An ISO date and nothing else. Which date a time of day falls on depends
    * on its zone, so a timestamp's text goes through TIMESTAMP, where that is
    * decided, rather than being cut short here. */
  private def parseDate(s: String): LocalDate =
    try LocalDate.parse(s)
    catch {
      case _: DateTimeParseException =>
        val advice =
          try { parseInstant(s); "; cast it to TIMESTAMP first, then to DATE for its date in UTC" }
          catch { case _: IllegalArgumentException => "" }
        throw new IllegalArgumentException(s"Cannot cast '$s' to date$advice")
    }
}

/** DECIMAL arithmetic: the type each operation's result is declared as, and
  * its value. The types are Spark's and SQL Server's. For a DECIMAL(p1,s1)
  * `l` and a DECIMAL(p2,s2) `r`:
  *
  *   - `l + r` and `l - r` keep the larger scale and add an integer digit to
  *     the wider integer part, for the carry;
  *   - `l * r` adds the scales, and takes p1 + p2 + 1 digits;
  *   - `l / r` takes the scale max(6, s1 + p2 + 1), and the integer digits of
  *     `l` plus the scale of `r` (dividing by 0.01 multiplies by 100);
  *   - `l % r` keeps the larger scale and the narrower integer part, since a
  *     remainder is smaller than both operands.
  *
  * Each type holds its result exactly, but for a quotient, which need not end
  * (1 / 3). Past [[MaxPrecision]] digits the scale gives way, down to 6
  * digits, and the integer part keeps its own, as many as it needs; that
  * is where a sum or a product can round too, or overflow. A value is rounded
  * half up to its type's scale, and one whose integer part is too long for
  * the type is an error, so every result fits the type it is declared as and
  * parquet can write it. */
object Decimals {

  /** The most digits a DECIMAL result is declared with. */
  val MaxPrecision: Int = 38

  /** The fewest fractional digits a result keeps when its type gives way to
    * [[MaxPrecision]]. */
  private val MinAdjustedScale: Int = 6

  /** The DECIMAL every value of an integer type fits: INT's ten digits and
    * BIGINT's nineteen. */
  def ofIntegral(t: DataType): DataType.DecimalType = t match {
    case DataType.IntType => DataType.DecimalType(10, 0)
    case DataType.LongType => DataType.DecimalType(19, 0)
    case other => throw new IllegalArgumentException(s"$other is not an integer type")
  }

  /** A number written in a query, as the decimal it prints as
    * ([[Casts.toDecimal]]), declared with just the digits it has: `2.5` is a
    * DECIMAL(2,1) and `100` a DECIMAL(3,0). */
  def literal(n: Number): LitExpr = {
    if (!Casts.isFinite(n)) throw new IllegalArgumentException(s"$n has no decimal value")
    val d = Casts.toDecimal(n)
    val v = if (d.scale < 0) d.setScale(0) else d
    val t = DataType.DecimalType(math.max(v.precision, v.scale), v.scale)
    if (t.precision > MaxPrecision)
      throw new IllegalArgumentException(s"${v.toPlainString} has more digits than a DECIMAL holds ($MaxPrecision)")
    LitExpr(v, t)
  }

  /** The type `l op r` is declared as, for an arithmetic `op`. */
  def resultType(op: String, l: DataType.DecimalType, r: DataType.DecimalType): DataType.DecimalType = {
    val (p1, s1, p2, s2) = (l.precision, l.scale, r.precision, r.scale)
    op match {
      case "+" | "-" =>
        val s = math.max(s1, s2)
        bounded(math.max(p1 - s1, p2 - s2) + s + 1, s)
      case "*" => bounded(p1 + p2 + 1, s1 + s2)
      case "/" =>
        val s = math.max(MinAdjustedScale, s1 + p2 + 1)
        bounded(p1 - s1 + s2 + s, s)
      case "%" =>
        val s = math.max(s1, s2)
        bounded(math.min(p1 - s1, p2 - s2) + s, s)
      case other => throw new IllegalArgumentException(s"Unknown arithmetic operator '$other'")
    }
  }

  /** The type of `SUM` over a DECIMAL: every digit a DECIMAL holds, at the
    * input's scale (the input's own digits, should it have more). */
  def sumType(t: DataType.DecimalType): DataType.DecimalType =
    DataType.DecimalType(math.max(MaxPrecision, t.precision), t.scale)

  /** The type of `AVG` over a DECIMAL: four more fractional digits than the
    * input, given way like a quotient's past [[MaxPrecision]]. */
  def avgType(t: DataType.DecimalType): DataType.DecimalType = bounded(t.precision + 4, t.scale + 4)

  /** DECIMAL(p,s), or past [[MaxPrecision]] digits the type that keeps all
    * p - s integer digits and gives way on the scale, to no fewer than
    * min(s, 6) fractional digits. */
  private def bounded(p: Int, s: Int): DataType.DecimalType =
    if (p <= MaxPrecision) DataType.DecimalType(p, s)
    else DataType.DecimalType(MaxPrecision, math.max(MaxPrecision - (p - s), math.min(s, MinAdjustedScale)))

  /** `l op r` as a value of `t`, the type [[resultType]] gives the operation.
    * NULL for a division or a remainder by zero, as for integers. */
  def arith(op: String, lv: Any, rv: Any, t: DataType.DecimalType): java.math.BigDecimal = {
    val l = Casts.toDecimal(lv.asInstanceOf[Number])
    val r = Casts.toDecimal(rv.asInstanceOf[Number])
    op match {
      case "+" => fit(l.add(r), t)
      case "-" => fit(l.subtract(r), t)
      case "*" => fit(l.multiply(r), t)
      case "/" => if (r.signum == 0) null else fit(l.divide(r, t.scale, java.math.RoundingMode.HALF_UP), t)
      case "%" => if (r.signum == 0) null else fit(l.remainder(r), t)
      case other => throw new IllegalArgumentException(s"Unknown arithmetic operator '$other'")
    }
  }

  /** `v` as a value of `t`: rounded half up to its scale, and an error when
    * its integer part has more digits than `t` holds. */
  def fit(v: java.math.BigDecimal, t: DataType.DecimalType): java.math.BigDecimal = {
    val atScale = v.setScale(t.scale, java.math.RoundingMode.HALF_UP)
    if (atScale.precision > t.precision)
      throw new ArithmeticException(s"Decimal overflow: ${v.toPlainString} does not fit $t")
    atScale
  }
}

/** Column-at-a-time variants of [[Ops]]. Each helper allocates one output
  * vector and runs a single tight loop that reads from primitive arrays —
  * the JIT eliminates boxing once it has profiled the call site, and the
  * loops are shapes HotSpot autovectorizes on AVX. The price is more code
  * (one specialized loop per (op, output type) pair) but no codegen step.
  *
  * Generic fallbacks via `getBoxed` cover the cross-type combinations that
  * the analyzer doesn't normalize through an explicit CAST — slower than the
  * specialized paths but still vectorized at the per-Expr-node level (one
  * dispatch per batch, not per row).
  */
object VecOps {

  /** Vectorized arithmetic. The returned vector's [[ColumnVector.dataType]]
    * matches `outType` — required because downstream operators (writers, casts,
    * negate, ...) dispatch on the declared schema type and would crash if
    * given the wrong concrete vector. Float arithmetic is done in double
    * precision (matching [[Ops.apply]]) then narrowed at store time. */
  def arith(op: String, l: ColumnVector, r: ColumnVector, outType: DataType, n: Int): ColumnVector = {
    val cap = math.max(1, n)
    outType match {
      case DataType.LongType => longArith(op, l, r, n, cap)
      case DataType.IntType => intArith(op, l, r, n, cap)
      case DataType.DoubleType => doubleArith(op, l, r, n, cap)
      case DataType.FloatType => floatArith(op, l, r, n, cap)
      case d: DataType.DecimalType => decimalArith(op, l, r, d, n, cap)
      case other => throw new IllegalStateException(s"Arithmetic on $other not supported")
    }
  }

  /** Vectorized comparison. Returns a BooleanVector. */
  def compare(op: String, l: ColumnVector, r: ColumnVector, lt: DataType, rt: DataType, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    if (DataType.isIntegral(lt) && DataType.isIntegral(rt)) integralCompare(op, l, r, values, nulls, n)
    else if (comparesAsDouble(lt) && comparesAsDouble(rt)) numericCompare(op, l, r, values, nulls, n)
    else genericCompare(op, l, r, values, nulls, n)
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized string concatenation (`||`). Coerces each non-null operand via
    * `toString` to match [[Ops.apply]]'s `||` behaviour. */
  def concat(l: ColumnVector, r: ColumnVector, n: Int): StringVector = {
    val cap = math.max(1, n)
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      if (l.isNull(i) || r.isNull(i)) values(i) = null
      else values(i) = String.valueOf(l.getBoxed(i)) + String.valueOf(r.getBoxed(i))
      i += 1
    }
    new StringVector(values, cap)
  }

  /** Three-valued AND. `false AND null = false`; `true AND null = null`. */
  def and(l: ColumnVector, r: ColumnVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    val lv = l.asInstanceOf[BooleanVector]
    val rv = r.asInstanceOf[BooleanVector]
    var i = 0
    while (i < n) {
      val lNull = lv.isNull(i)
      val rNull = rv.isNull(i)
      val lf = !lNull && !lv.values(i)
      val rf = !rNull && !rv.values(i)
      if (lf || rf) values(i) = false
      else if (lNull || rNull) nulls.set(i)
      else values(i) = true
      i += 1
    }
    new BooleanVector(values, nulls, cap)
  }

  /** Fold an AND chain into a single result vector by mutating `acc` in place
    * with the three-valued AND of each `addend`. `acc` MUST be a freshly-
    * allocated [[BooleanVector]] (not an aliased column from the input batch
    * — the caller's responsibility), otherwise this method's writes would
    * corrupt the shared column.
    *
    * Used by [[com.transformer.sql.plan.BinOpExpr.evalVec]] to collapse
    * a left-deep tree of `AND` nodes into a single output allocation. The
    * 9-conjunct WHERE clauses common to ETL scans go from N intermediate
    * `BooleanVector` allocations per batch down to 1.
    */
  def andInto(acc: BooleanVector, addend: BooleanVector, n: Int): Unit = {
    val av = acc.values
    val an = acc.nulls
    val bv = addend.values
    val bn = addend.nulls
    var i = 0
    while (i < n) {
      val aNull = an.get(i)
      val bNull = bn.get(i)
      val aFalse = !aNull && !av(i)
      val bFalse = !bNull && !bv(i)
      if (aFalse || bFalse) {
        av(i) = false
        if (aNull) an.clear(i)
      } else if (aNull || bNull) {
        an.set(i)
        av(i) = false
      } else {
        av(i) = true
      }
      i += 1
    }
  }

  /** OR-fold counterpart of [[andInto]]. Mutates `acc` in place with the
    * three-valued OR of `addend`. Same aliasing contract.
    */
  def orInto(acc: BooleanVector, addend: BooleanVector, n: Int): Unit = {
    val av = acc.values
    val an = acc.nulls
    val bv = addend.values
    val bn = addend.nulls
    var i = 0
    while (i < n) {
      val aNull = an.get(i)
      val bNull = bn.get(i)
      val aTrue = !aNull && av(i)
      val bTrue = !bNull && bv(i)
      if (aTrue || bTrue) {
        av(i) = true
        if (aNull) an.clear(i)
      } else if (aNull || bNull) {
        an.set(i)
        av(i) = false
      } else {
        av(i) = false
      }
      i += 1
    }
  }

  /** Three-valued OR. `true OR null = true`; `false OR null = null`. */
  def or(l: ColumnVector, r: ColumnVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    val lv = l.asInstanceOf[BooleanVector]
    val rv = r.asInstanceOf[BooleanVector]
    var i = 0
    while (i < n) {
      val lNull = lv.isNull(i)
      val rNull = rv.isNull(i)
      val lt = !lNull && lv.values(i)
      val rt = !rNull && rv.values(i)
      if (lt || rt) values(i) = true
      else if (lNull || rNull) nulls.set(i)
      else values(i) = false
      i += 1
    }
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized unary negation. Output type matches operand type. */
  def negate(c: ColumnVector, outType: DataType, n: Int): ColumnVector = {
    val cap = math.max(1, n)
    outType match {
      case DataType.IntType =>
        val cv = c.asInstanceOf[IntVector]
        val values = new Array[Int](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new IntVector(values, nulls, cap)
      case DataType.LongType =>
        val cv = c.asInstanceOf[LongVector]
        val values = new Array[Long](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new LongVector(values, nulls, cap)
      case DataType.FloatType =>
        val cv = c.asInstanceOf[FloatVector]
        val values = new Array[Float](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new FloatVector(values, nulls, cap)
      case DataType.DoubleType =>
        val cv = c.asInstanceOf[DoubleVector]
        val values = new Array[Double](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new DoubleVector(values, nulls, cap)
      case d: DataType.DecimalType =>
        val values = new Array[java.math.BigDecimal](cap)
        var i = 0
        while (i < n) {
          if (!c.isNull(i)) values(i) = c.getBoxed(i).asInstanceOf[java.math.BigDecimal].negate
          i += 1
        }
        new DecimalVector(values, d.precision, d.scale, cap)
      case other => throw new IllegalStateException(s"Unary - on $other")
    }
  }

  /** Vectorized boolean negation. */
  def not(c: ColumnVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val cv = c.asInstanceOf[BooleanVector]
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
    var i = 0
    while (i < n) { if (!cv.isNull(i)) values(i) = !cv.values(i); i += 1 }
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized IS NULL / IS NOT NULL. Returns BooleanVector with no nulls.
    *
    * Fast path: for primitive columns whose null bitset is empty (no nulls in
    * the batch — the common case for cleaned-up input), the result is the
    * constant `!negated` for every row. A single `Arrays.fill` avoids the
    * per-row `isNull(i)` dispatch. Reference columns (String/Binary/Decimal)
    * store nulls inline in the values array, so we still scan per row for
    * those.
    */
  def isNull(c: ColumnVector, negated: Boolean, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    val emptyNullBitset: Boolean = c match {
      case v: IntVector       => v.nulls.isEmpty
      case v: LongVector      => v.nulls.isEmpty
      case v: FloatVector     => v.nulls.isEmpty
      case v: DoubleVector    => v.nulls.isEmpty
      case v: BooleanVector   => v.nulls.isEmpty
      case v: DateVector      => v.nulls.isEmpty
      case v: TimestampVector => v.nulls.isEmpty
      case _                  => false
    }
    if (emptyNullBitset) {
      // No nulls — result is !negated for every row.
      if (!negated) {
        // IS NULL with no nulls present → all false; the zeroed array is correct.
      } else {
        java.util.Arrays.fill(values, 0, n, true)
      }
    } else {
      var i = 0
      while (i < n) { values(i) = c.isNull(i) ^ negated; i += 1 }
    }
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized CAST. Falls back to the per-row [[Casts.cast]] for combinations
    * not covered by a fast path; the inner loop still avoids per-row Expr
    * dispatch. */
  def cast(c: ColumnVector, fromType: DataType, toType: DataType, n: Int): ColumnVector = {
    if (fromType == toType) return c
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(toType, cap)
    var i = 0
    while (i < n) {
      if (c.isNull(i)) out.setNull(i)
      else {
        val v = Casts.cast(c.getBoxed(i), fromType, toType)
        if (v == null) out.setNull(i) else out.setBoxed(i, v)
      }
      i += 1
    }
    out
  }

  // ---- helpers ----

  /** The types a pair of which [[Ops]] compares as primitives: two integers
    * (INT, BIGINT) as longs, which [[integralCompare]] reads with `readLong`,
    * and any other pair of these, which has a FLOAT or DOUBLE in it, as
    * doubles, which [[numericCompare]] reads with `readDouble`. A decimal is a
    * number but not one of these: `Ops` compares it exactly, and through
    * `readDouble` two decimals past double's precision would compare equal.
    * Any pair with a decimal in it takes the generic path, which asks `Ops`. */
  private[plan] def comparesAsDouble(t: DataType): Boolean = t match {
    case DataType.IntType | DataType.LongType | DataType.FloatType | DataType.DoubleType => true
    case _ => false
  }

  private def readLong(v: ColumnVector, i: Int): Long = v match {
    case lv: LongVector => lv.values(i)
    case iv: IntVector => iv.values(i).toLong
    case other => other.getBoxed(i).asInstanceOf[Number].longValue
  }

  private def readInt(v: ColumnVector, i: Int): Int = v match {
    case iv: IntVector => iv.values(i)
    case lv: LongVector => lv.values(i).toInt
    case other => other.getBoxed(i).asInstanceOf[Number].intValue
  }

  private def readDouble(v: ColumnVector, i: Int): Double = v match {
    case dv: DoubleVector => dv.values(i)
    case fv: FloatVector => fv.values(i).toDouble
    case lv: LongVector => lv.values(i).toDouble
    case iv: IntVector => iv.values(i).toDouble
    case other => other.getBoxed(i).asInstanceOf[Number].doubleValue
  }

  private def longArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): LongVector = {
    val values = new Array[Long](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) + readLong(r, i)
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) - readLong(r, i)
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) * readLong(r, i)
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readLong(r, i)
            if (rv == 0L) nulls.set(i) else values(i) = readLong(l, i) / rv
          }
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readLong(r, i)
            if (rv == 0L) nulls.set(i) else values(i) = readLong(l, i) % rv
          }
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown long arith op '$other'")
    }
    new LongVector(values, nulls, cap)
  }

  private def intArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): IntVector = {
    val values = new Array[Int](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readInt(l, i) + readInt(r, i)
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readInt(l, i) - readInt(r, i)
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readInt(l, i) * readInt(r, i)
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readInt(r, i)
            if (rv == 0) nulls.set(i) else values(i) = readInt(l, i) / rv
          }
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readInt(r, i)
            if (rv == 0) nulls.set(i) else values(i) = readInt(l, i) % rv
          }
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown int arith op '$other'")
    }
    new IntVector(values, nulls, cap)
  }

  private def doubleArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): DoubleVector = {
    val values = new Array[Double](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) + readDouble(r, i)
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) - readDouble(r, i)
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) * readDouble(r, i)
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) / readDouble(r, i)
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) % readDouble(r, i)
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown double arith op '$other'")
    }
    new DoubleVector(values, nulls, cap)
  }

  /** Float arithmetic. Math is performed in double precision (matching
    * [[Ops.apply]]'s `DataType.FloatType | DataType.DoubleType` branch) and
    * narrowed to float at store time. */
  private def floatArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): FloatVector = {
    val values = new Array[Float](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) + readDouble(r, i)).toFloat
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) - readDouble(r, i)).toFloat
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) * readDouble(r, i)).toFloat
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) / readDouble(r, i)).toFloat
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) % readDouble(r, i)).toFloat
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown float arith op '$other'")
    }
    new FloatVector(values, nulls, cap)
  }

  /** Decimal arithmetic, one row at a time through [[Decimals.arith]], the
    * function the row path calls, so the two paths cannot round differently. */
  private def decimalArith(
      op: String,
      l: ColumnVector,
      r: ColumnVector,
      t: DataType.DecimalType,
      n: Int,
      cap: Int): DecimalVector = {
    val values = new Array[java.math.BigDecimal](cap)
    var i = 0
    while (i < n) {
      if (!l.isNull(i) && !r.isNull(i)) values(i) = Decimals.arith(op, l.getBoxed(i), r.getBoxed(i), t)
      i += 1
    }
    new DecimalVector(values, t.precision, t.scale, cap)
  }

  /** Comparison of two integers, read as longs — [[Ops]] compares two INT or
    * BIGINT values the same way. A double holds every long only up to 2^53,
    * so [[numericCompare]] would find 2^53 and 2^53 + 1 equal. Longs have no
    * NaN and no signed zero, so the primitive operators are the total order. */
  private def integralCompare(
      op: String,
      l: ColumnVector,
      r: ColumnVector,
      values: Array[Boolean],
      nulls: util.BitSet,
      n: Int): Unit = {
    op match {
      case "=" | "==" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) == readLong(r, i)
          i += 1
        }
      case "<>" | "!=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) != readLong(r, i)
          i += 1
        }
      case "<" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) < readLong(r, i)
          i += 1
        }
      case "<=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) <= readLong(r, i)
          i += 1
        }
      case ">" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) > readLong(r, i)
          i += 1
        }
      case ">=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) >= readLong(r, i)
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown integral compare op '$other'")
    }
  }

  /** Numeric comparison via double promotion, for a pair with a FLOAT or
    * DOUBLE in it — matches [[Ops.cmp]]'s cross-numeric semantics.
    *
    * The two operator families use deliberately different primitives, because
    * [[Ops]] does:
    *
    *   - '''Equality''' (`=` `<>`) is primitive `==` / `!=`, matching
    *     [[Ops.eq]]'s `x.doubleValue == y.doubleValue`. IEEE rules: `NaN`
    *     equals nothing (itself included), `-0.0 == 0.0`.
    *   - '''Ordering''' (`< <= > >=`) goes through `java.lang.Double.compare`,
    *     matching [[Ops.cmp]]. That is a total order — `NaN` sorts above every
    *     other value and equals itself, `-0.0` sorts below `0.0` — and it has
    *     to be, since `Ops.cmp` also backs the sort comparators, where IEEE
    *     comparison would violate the `Comparator` contract. Using the raw
    *     `<` operator here instead would make the same `Expr` answer
    *     differently depending on whether the caller reached `eval` or
    *     `evalVec`. `Double.compare` takes primitive `double`s, so this costs
    *     no boxing.
    *
    * `Float` has no branch of its own: `readDouble` widens `FloatVector` (and
    * `LongVector` / `IntVector`, beside a float) to `double` exactly as
    * `Number.doubleValue` does on the row side, so one implementation covers
    * every such pair.
    */
  private def numericCompare(
      op: String,
      l: ColumnVector,
      r: ColumnVector,
      values: Array[Boolean],
      nulls: util.BitSet,
      n: Int): Unit = {
    op match {
      case "=" | "==" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) == readDouble(r, i)
          i += 1
        }
      case "<>" | "!=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) != readDouble(r, i)
          i += 1
        }
      case "<" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = java.lang.Double.compare(readDouble(l, i), readDouble(r, i)) < 0
          i += 1
        }
      case "<=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = java.lang.Double.compare(readDouble(l, i), readDouble(r, i)) <= 0
          i += 1
        }
      case ">" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = java.lang.Double.compare(readDouble(l, i), readDouble(r, i)) > 0
          i += 1
        }
      case ">=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = java.lang.Double.compare(readDouble(l, i), readDouble(r, i)) >= 0
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown numeric compare op '$other'")
    }
  }

  /** Generic comparison — falls back through [[Ops.cmp]] / [[Ops.eq]] for
    * strings, booleans, dates, decimals, byte arrays, etc. Per-row but one
    * dispatch level shallower than the original `Expr.eval` tree (no BinOpExpr
    * wrapper). */
  private def genericCompare(
      op: String,
      l: ColumnVector,
      r: ColumnVector,
      values: Array[Boolean],
      nulls: util.BitSet,
      n: Int): Unit = {
    val isEq = op == "=" || op == "=="
    val isNe = op == "<>" || op == "!="
    var i = 0
    while (i < n) {
      if (l.isNull(i) || r.isNull(i)) nulls.set(i)
      else {
        val a = l.getBoxed(i)
        val b = r.getBoxed(i)
        values(i) =
          if (isEq) Ops.eq(a, b)
          else if (isNe) !Ops.eq(a, b)
          else {
            val c = Ops.cmp(a, b)
            op match {
              case "<" => c < 0
              case "<=" => c <= 0
              case ">" => c > 0
              case ">=" => c >= 0
              case other => throw new IllegalStateException(s"Unknown generic compare op '$other'")
            }
          }
      }
      i += 1
    }
  }
}
