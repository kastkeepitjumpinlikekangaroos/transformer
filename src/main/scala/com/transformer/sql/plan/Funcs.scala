package com.transformer.sql.plan

import com.transformer.core._

import java.util
import java.util.Locale

/** Scalar function registry. */
object Funcs {

  /** What an argument of a function must be. NULL is every kind. */
  sealed trait Param
  object Param {
    /** Any value. The flow functions take these: their arguments meet as a
      * CASE's values do, or are compared as NULLIF's are, before the call is
      * typed, and IF's first is a condition (`Analyzer.condition`). */
    case object AnyValue extends Param
    /** A number: an INT, a BIGINT, a FLOAT, a DOUBLE or a DECIMAL. */
    case object Number extends Param
    /** An INT or a BIGINT that counts something, which `role` names: a scale,
      * a position, a length or a seed. */
    final case class Integer(role: String) extends Param
    /** A STRING. */
    case object Text extends Param
  }

  /** A function's signature over the arguments it is called with: what each
    * of them must be, and the type it returns. */
  final case class Signature(params: Seq[Param], returnType: DataType)

  /** The signature of `name` over `args`, or `None` if no function of that
    * name takes that many arguments. The analyzer checks each argument against
    * its [[Param]] (`Analyzer.checkArgument`) and types the call by it; the
    * executor uses [[apply]] at runtime. Most return types follow the
    * argument types alone; a `ROUND` or `TRUNC` of a DECIMAL also reads a
    * scale written as a number ([[Rounding.resultType]]). */
  def signature(name: String, args: Seq[Expr]): Option[Signature] = {
    import Param._
    val argTypes = args.map(_.dataType)
    val n = argTypes.size
    def each(param: Param, returns: DataType) = Some(Signature(Seq.fill(n)(param), returns))
    name.toUpperCase(Locale.ROOT) match {
      case "LENGTH" | "LEN" if n == 1 => each(Text, DataType.IntType)
      case "UPPER" | "LOWER" | "TRIM" if n == 1 => each(Text, DataType.StringType)
      case "SUBSTRING" if n == 2 || n == 3 =>
        Some(Signature(Seq(Text, Integer("position"), Integer("length")).take(n), DataType.StringType))
      case "CONCAT" => each(Text, DataType.StringType)
      case "COALESCE" if n > 0 => each(AnyValue, oneType(argTypes))
      case "IF" if n == 3 => each(AnyValue, oneType(argTypes.drop(1)))
      case "NULLIF" if n == 2 => each(AnyValue, argTypes.head)
      case "ABS" if n == 1 => each(Number, argTypes.head)
      case "ROUND" | "TRUNC" | "TRUNCATE" if n == 1 || n == 2 =>
        Some(Signature(Seq(Number, Integer("scale")).take(n), Rounding.resultType(name, args)))
      case "FLOOR" | "CEIL" | "CEILING" if n == 1 => each(Number, Rounding.resultType(name, args))
      case "POW" | "POWER" if n == 2 => each(Number, DataType.DoubleType)
      case "SQRT" | "CBRT" | "EXP" if n == 1 => each(Number, DataType.DoubleType)
      case "LN" if n == 1 => each(Number, DataType.DoubleType)
      case "LOG" if n == 1 || n == 2 => each(Number, DataType.DoubleType)
      case "LOG10" | "LOG2" if n == 1 => each(Number, DataType.DoubleType)
      case "SIN" | "COS" | "TAN" | "ASIN" | "ACOS" | "ATAN" if n == 1 => each(Number, DataType.DoubleType)
      case "SINH" | "COSH" | "TANH" if n == 1 => each(Number, DataType.DoubleType)
      case "ATAN2" if n == 2 => each(Number, DataType.DoubleType)
      case "DEGREES" | "RADIANS" if n == 1 => each(Number, DataType.DoubleType)
      case "SIGN" if n == 1 => each(Number, DataType.IntType)
      case "PI" | "E" if n == 0 => Some(Signature(Nil, DataType.DoubleType))
      case "RAND" | "RANDOM" if n == 0 || n == 1 => each(Integer("seed"), DataType.DoubleType)
      case "GREATEST" | "LEAST" if n > 0 => each(AnyValue, oneType(argTypes))
      case _ => None
    }
  }

  /** The type of arguments the binder has given one type: theirs, or NULL
    * when every one is an untyped NULL. */
  private def oneType(types: Seq[DataType]): DataType =
    types.find(_ != DataType.NullType).getOrElse(DataType.NullType)

  /** Per-thread RNG so RAND() is safe under parallel execution. Seeded RAND(x) returns
    * a deterministic value (not a per-row stream) — sufficient for tests and tagging. */
  private val threadRng: ThreadLocal[java.util.Random] = new ThreadLocal[java.util.Random] {
    override def initialValue(): java.util.Random = new java.util.Random()
  }

  def apply(name: String, args: Seq[Any], resultType: DataType, argTypes: Seq[DataType]): Any =
    name.toUpperCase(Locale.ROOT) match {
      case "LENGTH" | "LEN" =>
        val v = args.head
        if (v == null) null else v.toString.length
      case "UPPER" =>
        if (args.head == null) null else args.head.toString.toUpperCase(Locale.ROOT)
      case "LOWER" =>
        if (args.head == null) null else args.head.toString.toLowerCase(Locale.ROOT)
      case "TRIM" =>
        if (args.head == null) null else args.head.toString.trim
      case "SUBSTRING" =>
        if (args.exists(_ == null)) null
        else substring(args.head.toString, args(1).asInstanceOf[Number].longValue,
          if (args.size == 3) args(2).asInstanceOf[Number].longValue else Long.MaxValue)
      case "CONCAT" =>
        if (args.exists(_ == null)) null else args.map(_.toString).mkString
      case "COALESCE" =>
        args.find(_ != null).orNull
      case "IF" =>
        val cond = args(0)
        if (cond != null && cond.asInstanceOf[Boolean]) args(1) else args(2)
      case "NULLIF" =>
        if (Ops.eq(args(0), args(1))) null else args(0)
      case "ABS" =>
        args.head match {
          case null => null
          // A decimal's absolute value is exact, at the value's own scale.
          case d: java.math.BigDecimal => d.abs
          case n: java.lang.Number => resultType match {
            case DataType.IntType => Integers.abs(n.intValue)
            case DataType.LongType => Integers.abs(n.longValue)
            // Narrow on a Float result so eval matches VecFuncs.abs (which
            // stores a narrowed Float); leaving it a Double would diverge once
            // the value feeds an enclosing operation.
            case DataType.FloatType => math.abs(n.doubleValue).toFloat
            case _ => math.abs(n.doubleValue)
          }
        }
      case "ROUND" | "TRUNC" | "TRUNCATE" | "FLOOR" | "CEIL" | "CEILING" =>
        if (args.exists(_ == null)) null
        else Rounding(name, args.head.asInstanceOf[Number], if (args.size == 2) args(1).asInstanceOf[Number].longValue else 0L,
          resultType)
      case "POW" | "POWER" =>
        if (args(0) == null || args(1) == null) null
        else math.pow(args(0).asInstanceOf[Number].doubleValue, args(1).asInstanceOf[Number].doubleValue)
      case "SQRT" =>
        if (args.head == null) null else math.sqrt(args.head.asInstanceOf[Number].doubleValue)
      case "CBRT" =>
        if (args.head == null) null else math.cbrt(args.head.asInstanceOf[Number].doubleValue)
      case "EXP" =>
        if (args.head == null) null else math.exp(args.head.asInstanceOf[Number].doubleValue)
      case "LN" =>
        if (args.head == null) null else math.log(args.head.asInstanceOf[Number].doubleValue)
      case "LOG" =>
        if (args.exists(_ == null)) null
        else if (args.size == 1) math.log(args.head.asInstanceOf[Number].doubleValue)
        else {
          val base = args(0).asInstanceOf[Number].doubleValue
          val x = args(1).asInstanceOf[Number].doubleValue
          math.log(x) / math.log(base)
        }
      case "LOG10" =>
        if (args.head == null) null else math.log10(args.head.asInstanceOf[Number].doubleValue)
      case "LOG2" =>
        if (args.head == null) null
        else math.log(args.head.asInstanceOf[Number].doubleValue) / math.log(2.0)
      case "SIN" => if (args.head == null) null else math.sin(args.head.asInstanceOf[Number].doubleValue)
      case "COS" => if (args.head == null) null else math.cos(args.head.asInstanceOf[Number].doubleValue)
      case "TAN" => if (args.head == null) null else math.tan(args.head.asInstanceOf[Number].doubleValue)
      case "ASIN" => if (args.head == null) null else math.asin(args.head.asInstanceOf[Number].doubleValue)
      case "ACOS" => if (args.head == null) null else math.acos(args.head.asInstanceOf[Number].doubleValue)
      case "ATAN" => if (args.head == null) null else math.atan(args.head.asInstanceOf[Number].doubleValue)
      case "ATAN2" =>
        if (args(0) == null || args(1) == null) null
        else math.atan2(args(0).asInstanceOf[Number].doubleValue, args(1).asInstanceOf[Number].doubleValue)
      case "SINH" => if (args.head == null) null else math.sinh(args.head.asInstanceOf[Number].doubleValue)
      case "COSH" => if (args.head == null) null else math.cosh(args.head.asInstanceOf[Number].doubleValue)
      case "TANH" => if (args.head == null) null else math.tanh(args.head.asInstanceOf[Number].doubleValue)
      case "DEGREES" =>
        if (args.head == null) null else math.toDegrees(args.head.asInstanceOf[Number].doubleValue)
      case "RADIANS" =>
        if (args.head == null) null else math.toRadians(args.head.asInstanceOf[Number].doubleValue)
      case "SIGN" =>
        if (args.head == null) null
        else {
          val d = args.head.asInstanceOf[Number].doubleValue
          if (d > 0.0) 1 else if (d < 0.0) -1 else 0
        }
      case "PI" => math.Pi
      case "E"  => math.E
      case "RAND" | "RANDOM" =>
        if (args.isEmpty) threadRng.get().nextDouble()
        else if (args.head == null) null
        else new java.util.Random(args.head.asInstanceOf[Number].longValue).nextDouble()
      case "GREATEST" =>
        pickByOrdering(args, argTypes, resultType, greatest = true)
      case "LEAST" =>
        pickByOrdering(args, argTypes, resultType, greatest = false)
      case other => throw new IllegalArgumentException(s"Unsupported function '$other'")
    }

  private def pickByOrdering(args: Seq[Any], argTypes: Seq[DataType], resultType: DataType, greatest: Boolean): Any = {
    var best: Any = null
    var i = 0
    while (i < args.size) {
      val v = args(i)
      if (v != null) {
        val cast = Casts.cast(v, argTypes(i), resultType)
        if (best == null) best = cast
        else {
          val c = Ops.cmp(cast, best)
          if ((greatest && c > 0) || (!greatest && c < 0)) best = cast
        }
      }
      i += 1
    }
    best
  }

  /** SUBSTRING's answer: `length` characters of `s` from the 1-based
    * `position`, or as many as there are. A position before the first
    * character counts from the first; one past the last, or a length of 0,
    * gives the empty string, and a negative length is an error, as in
    * Postgres: it took nothing. Read as a BIGINT, a position past an INT's
    * range is past the end rather than wrapped round to before the start. */
  private[plan] def substring(s: String, position: Long, length: Long): String = {
    if (length < 0) throw negativeLength(length)
    val start = if (position < 1) 0L else position - 1
    if (start >= s.length || length <= 0) ""
    else s.substring(start.toInt, if (length < s.length - start) (start + length).toInt else s.length)
  }


  private[plan] def negativeLength(length: Long): IllegalArgumentException =
    new IllegalArgumentException(s"SUBSTRING's length $length is negative")

  /** Vectorized counterpart to [[apply]]. Returns `Some(vec)` for the function
    * names with a fast path, `None` otherwise — the caller falls back to the
    * default boxed loop.
    *
    * Covers the hot scalar functions in the example workloads: `COALESCE`,
    * `LENGTH`, `UPPER`, `LOWER`, `TRIM`, `CONCAT`, `SUBSTRING`, `ABS`,
    * `FLOOR`, `CEIL[ING]`, `ROUND`, `TRUNC[ATE]`, `IF`, `NULLIF`. Unknown
    * names and unsupported argument shapes return `None`. */
  def applyVec(
      name: String,
      args: Seq[Expr],
      resultType: DataType,
      batch: ColumnarBatch): Option[ColumnVector] = {
    val a = args
    name.toUpperCase(Locale.ROOT) match {
      case "COALESCE" if a.nonEmpty                   => Some(VecFuncs.coalesce(a, resultType, batch))
      case "LENGTH" | "LEN" if a.size == 1            => Some(VecFuncs.length(a.head, batch))
      case "UPPER" if a.size == 1                     => Some(VecFuncs.upperLower(a.head, batch, lower = false))
      case "LOWER" if a.size == 1                     => Some(VecFuncs.upperLower(a.head, batch, lower = true))
      case "TRIM" if a.size == 1                      => Some(VecFuncs.trim(a.head, batch))
      case "CONCAT"                                   => Some(VecFuncs.concat(a, batch))
      case "SUBSTRING" if a.size == 2 || a.size == 3  => Some(VecFuncs.substring(a, batch))
      case "ABS" if a.size == 1                       => Some(VecFuncs.abs(a.head, resultType, batch))
      case "FLOOR" | "CEIL" | "CEILING" if a.size == 1 => Some(VecFuncs.rounding(name, a, resultType, batch))
      case "ROUND" | "TRUNC" | "TRUNCATE" if a.size == 1 || a.size == 2 =>
        Some(VecFuncs.rounding(name, a, resultType, batch))
      case "IF" if a.size == 3                        => Some(VecFuncs.iff(a, resultType, batch))
      case "NULLIF" if a.size == 2                    => Some(VecFuncs.nullIf(a, resultType, batch))
      case _ => None
    }
  }
}

/** Column-at-a-time implementations for the hot subset of [[Funcs]] — used by
  * [[FuncExpr.evalVec]]. Each helper allocates one typed output vector, then
  * walks once with the function-name dispatch hoisted outside the loop, so the
  * JIT sees a monomorphic body. NULL semantics match [[Funcs.apply]] row by
  * row — verified by `ExprBatchTest`. */
object VecFuncs {

  /** SQL `COALESCE(a, b, c, ...)` — first non-NULL arg per row, else NULL.
    * Args are evaluated left to right, each needed only on the rows every
    * earlier one left NULL ([[Operand]]), and none once every row is filled.
    * For analyzer-uniform `resultType`, all args already share a type, so a
    * typed copy would be possible — using `setBoxed` keeps this simple and
    * still avoids per-row `Expr.eval`. */
  def coalesce(args: Seq[Expr], resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(resultType, cap)
    val filled = new util.BitSet(cap)
    val it = args.iterator
    while (it.hasNext && filled.cardinality < n) {
      val argVec = Operand.evalVec(it.next(), batch, i => !filled.get(i))
      var i = 0
      while (i < n) {
        if (!filled.get(i) && !argVec.isNull(i)) {
          out.setBoxed(i, argVec.getBoxed(i))
          filled.set(i)
        }
        i += 1
      }
    }
    var i = 0
    while (i < n) {
      if (!filled.get(i)) out.setNull(i)
      i += 1
    }
    out
  }

  def length(arg: Expr, batch: ColumnarBatch): IntVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVec = arg.evalVec(batch)
    val values = new Array[Int](cap)
    val nulls = new util.BitSet(cap)
    var i = 0
    while (i < n) {
      if (argVec.isNull(i)) nulls.set(i)
      else values(i) = argVec.getBoxed(i).toString.length
      i += 1
    }
    new IntVector(values, nulls, cap)
  }

  def upperLower(arg: Expr, batch: ColumnarBatch, lower: Boolean): StringVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVec = arg.evalVec(batch)
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      if (argVec.isNull(i)) values(i) = null
      else {
        val s = argVec.getBoxed(i).toString
        values(i) = if (lower) s.toLowerCase(Locale.ROOT) else s.toUpperCase(Locale.ROOT)
      }
      i += 1
    }
    new StringVector(values, cap)
  }

  def trim(arg: Expr, batch: ColumnarBatch): StringVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVec = arg.evalVec(batch)
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      if (argVec.isNull(i)) values(i) = null
      else values(i) = argVec.getBoxed(i).toString.trim
      i += 1
    }
    new StringVector(values, cap)
  }

  /** Null-propagating CONCAT — matches the row-form [[Funcs.apply]] (NULL in
    * any arg short-circuits to NULL, not silent skip). */
  def concat(args: Seq[Expr], batch: ColumnarBatch): StringVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVecs = args.iterator.map(_.evalVec(batch)).toArray
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      var anyNull = false
      var j = 0
      while (j < argVecs.length && !anyNull) {
        if (argVecs(j).isNull(i)) anyNull = true
        j += 1
      }
      if (anyNull) values(i) = null
      else {
        val sb = new java.lang.StringBuilder()
        var k = 0
        while (k < argVecs.length) {
          sb.append(argVecs(k).getBoxed(i).toString)
          k += 1
        }
        values(i) = sb.toString
      }
      i += 1
    }
    new StringVector(values, cap)
  }

  /** SUBSTRING(s, position[, length]), by [[Funcs.substring]]. NULL in any
    * argument is NULL. */
  def substring(args: Seq[Expr], batch: ColumnarBatch): StringVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val sVec = args(0).evalVec(batch)
    val startVec = args(1).evalVec(batch)
    val lenVec: ColumnVector = if (args.size == 3) args(2).evalVec(batch) else null
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      if (sVec.isNull(i) || startVec.isNull(i) || (lenVec != null && lenVec.isNull(i))) values(i) = null
      else values(i) = Funcs.substring(sVec.getBoxed(i).toString, startVec.getBoxed(i).asInstanceOf[Number].longValue,
        if (lenVec != null) lenVec.getBoxed(i).asInstanceOf[Number].longValue else Long.MaxValue)
      i += 1
    }
    new StringVector(values, cap)
  }

  /** ABS — preserves its argument's numeric type (matches eval: `resultType`
    * is the argument's type). */
  def abs(arg: Expr, resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVec = arg.evalVec(batch)
    resultType match {
      case _: DataType.DecimalType =>
        val out = ColumnVector.allocate(resultType, cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) out.setNull(i)
          else out.setBoxed(i, argVec.getBoxed(i).asInstanceOf[java.math.BigDecimal].abs)
          i += 1
        }
        out
      case DataType.IntType =>
        val values = new Array[Int](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else values(i) = Integers.abs(argVec.getBoxed(i).asInstanceOf[Number].intValue)
          i += 1
        }
        new IntVector(values, nulls, cap)
      case DataType.LongType =>
        val values = new Array[Long](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else values(i) = Integers.abs(argVec.getBoxed(i).asInstanceOf[Number].longValue)
          i += 1
        }
        new LongVector(values, nulls, cap)
      case DataType.FloatType =>
        val values = new Array[Float](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else values(i) = math.abs(argVec.getBoxed(i).asInstanceOf[Number].doubleValue).toFloat
          i += 1
        }
        new FloatVector(values, nulls, cap)
      case _ =>
        val values = new Array[Double](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else values(i) = math.abs(argVec.getBoxed(i).asInstanceOf[Number].doubleValue)
          i += 1
        }
        new DoubleVector(values, nulls, cap)
    }
  }

  /** ROUND / TRUNC (x[, scale]), FLOOR and CEIL, by [[Rounding]], into a
    * vector of `resultType`. NULL in either argument is NULL. */
  def rounding(name: String, args: Seq[Expr], resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val xVec = args(0).evalVec(batch)
    val scaleVec: ColumnVector = if (args.size == 2) args(1).evalVec(batch) else null
    val out = ColumnVector.allocate(resultType, cap)
    var i = 0
    while (i < n) {
      if (xVec.isNull(i) || (scaleVec != null && scaleVec.isNull(i))) out.setNull(i)
      else out.setBoxed(i, Rounding(name, xVec.getBoxed(i).asInstanceOf[Number],
        if (scaleVec != null) scaleVec.getBoxed(i).asInstanceOf[Number].longValue else 0L, resultType))
      i += 1
    }
    out
  }

  /** `IF(cond, a, b)`. Null/false cond → b. `a` is needed only on the rows
    * `cond` makes TRUE and `b` only on the rest ([[Operand]]), so `IF` guards
    * as `CASE` does. */
  def iff(args: Seq[Expr], resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(resultType, cap)
    val condVec = args(0).evalVec(batch).asInstanceOf[BooleanVector]
    val chosen: Int => Boolean = i => !condVec.isNull(i) && condVec.values(i)
    val aVec = Operand.evalVec(args(1), batch, chosen)
    val bVec = Operand.evalVec(args(2), batch, i => !chosen(i))
    var i = 0
    while (i < n) {
      val takeA = chosen(i)
      val src = if (takeA) aVec else bVec
      if (src.isNull(i)) out.setNull(i)
      else out.setBoxed(i, src.getBoxed(i))
      i += 1
    }
    out
  }

  /** `NULLIF(a, b)` → NULL when `Ops.eq(a, b)`, else `a`. Matches eval. */
  def nullIf(args: Seq[Expr], resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(resultType, cap)
    val aVec = args(0).evalVec(batch)
    val bVec = args(1).evalVec(batch)
    var i = 0
    while (i < n) {
      if (aVec.isNull(i)) out.setNull(i)
      else {
        val a = aVec.getBoxed(i)
        val eq = !bVec.isNull(i) && Ops.eq(a, bVec.getBoxed(i))
        if (eq) out.setNull(i)
        else out.setBoxed(i, a)
      }
      i += 1
    }
    out
  }
}

/** `ROUND`, `TRUNC`, `FLOOR` and `CEIL`, exact. Each rounds a number as the
  * decimal it is, which for a FLOAT or a DOUBLE is the decimal it prints as
  * ([[Casts.toDecimal]]), so `ROUND(1.005, 2)` is 1.01 whatever the binary
  * fraction nearest 1.005 would round to; `ROUND` rounds half away from zero
  * (`ROUND(-2.5)` is -3), `TRUNC` toward zero, `FLOOR` down and `CEIL` up.
  * An INT or a BIGINT keeps its type, a negative scale rounding it to tens,
  * hundreds, ... (an answer past the type is the integer overflow error); a
  * DECIMAL stays a DECIMAL ([[resultType]]); a FLOAT or DOUBLE is a DOUBLE,
  * NaN and the infinities their own answer. */
private[plan] object Rounding {

  /** The type of `name` over `args`: an INT, BIGINT or DECIMAL argument's own
    * type for FLOOR and CEIL of a whole number, and for ROUND and TRUNC at a
    * scale that keeps every digit; otherwise a DECIMAL(p,s) loses the digits
    * past the scale, which is the scale written as a number (0 when there is
    * none, and none below 0), and ROUND, FLOOR and CEIL keep one more integer
    * digit for the carry (9.5 rounds to 10). A scale that is not a number
    * written in the query keeps the DECIMAL's scale, rounding within it. A
    * FLOAT, a DOUBLE and an untyped NULL are a DOUBLE. */
  def resultType(name: String, args: Seq[Expr]): DataType = {
    val fn = name.toUpperCase(Locale.ROOT)
    args.head.dataType match {
      case DataType.IntType | DataType.LongType => args.head.dataType
      case d @ DataType.DecimalType(p, s) =>
        val carry = if (fn == "TRUNC" || fn == "TRUNCATE") 0 else 1
        val scale: Option[Long] =
          if (args.size == 1) Some(0L)
          else args(1) match {
            case LitExpr(v: java.lang.Number, _) => Some(v.longValue)
            case _ => None
          }
        scale match {
          case Some(k) if k >= s => d
          case Some(k) =>
            val kept = math.max(0L, k).toInt
            DataType.DecimalType(math.min(Decimals.MaxPrecision, math.max(1, p - s + carry + kept)), kept)
          case None => DataType.DecimalType(math.min(Decimals.MaxPrecision, p + carry), s)
        }
      case _ => DataType.DoubleType
    }
  }

  /** `x` rounded by `name` at `scale` places, as a value of `resultType`. */
  def apply(name: String, x: java.lang.Number, scale: Long, resultType: DataType): Any = {
    val mode = name.toUpperCase(Locale.ROOT) match {
      case "ROUND" => java.math.RoundingMode.HALF_UP
      case "TRUNC" | "TRUNCATE" => java.math.RoundingMode.DOWN
      case "FLOOR" => java.math.RoundingMode.FLOOR
      case _ => java.math.RoundingMode.CEILING
    }
    resultType match {
      case DataType.DoubleType =>
        val d = x.doubleValue
        if (d.isNaN || d.isInfinite) d
        // A double's whole part is exact, so FLOOR and CEIL need no decimal.
        else if (mode == java.math.RoundingMode.FLOOR) math.floor(d)
        else if (mode == java.math.RoundingMode.CEILING) math.ceil(d)
        else at(Casts.toDecimal(x), scale, mode).doubleValue
      case DataType.IntType => Integers.toInt(at(Casts.toDecimal(x), scale, mode))
      case DataType.LongType => Integers.toLong(at(Casts.toDecimal(x), scale, mode))
      case t: DataType.DecimalType =>
        Decimals.fit(at(x.asInstanceOf[java.math.BigDecimal], scale, mode).setScale(t.scale, mode), t)
      case other => throw new IllegalStateException(s"Rounding to $other")
    }
  }

  /** `v` at `scale` places by `mode`. A scale at or past `v`'s own keeps it
    * as it is, and one below its integer digits makes it 0 (or, by the carry,
    * one unit of the place above them), so no scale costs more digits than `v`
    * has: `setScale` to a scale written as a billion would build a billion
    * digits. */
  private def at(v: java.math.BigDecimal, scale: Long, mode: java.math.RoundingMode): java.math.BigDecimal =
    if (scale >= v.scale) v
    else {
      val integerDigits = v.precision - v.scale
      val k = math.max(scale, -(integerDigits.toLong + 1)).toInt
      v.setScale(k, mode)
    }
}
