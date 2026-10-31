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

  /** The signature of `name` over arguments of `argTypes`, or `None` if no
    * function of that name takes that many arguments. The analyzer checks
    * each argument against its [[Param]] (`Analyzer.checkArgument`) and types
    * the call by it; the executor uses [[apply]] at runtime. */
  def signature(name: String, argTypes: Seq[DataType]): Option[Signature] = {
    import Param._
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
        Some(Signature(Seq(Number, Integer("scale")).take(n), DataType.DoubleType))
      case "FLOOR" | "CEIL" | "CEILING" if n == 1 => each(Number, DataType.DoubleType)
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
            case DataType.IntType => math.abs(n.intValue)
            case DataType.LongType => math.abs(n.longValue)
            // Narrow on a Float result so eval matches VecFuncs.abs (which
            // stores a narrowed Float); leaving it a Double would diverge once
            // the value feeds an enclosing operation.
            case DataType.FloatType => math.abs(n.doubleValue).toFloat
            case _ => math.abs(n.doubleValue)
          }
        }
      case "ROUND" =>
        if (args.exists(_ == null)) null
        else round(args.head.asInstanceOf[Number].doubleValue,
          if (args.size == 2) args(1).asInstanceOf[Number].longValue else 0L)
      case "FLOOR" =>
        if (args.head == null) null else math.floor(args.head.asInstanceOf[Number].doubleValue)
      case "CEIL" | "CEILING" =>
        if (args.head == null) null else math.ceil(args.head.asInstanceOf[Number].doubleValue)
      case "TRUNC" | "TRUNCATE" =>
        if (args.exists(_ == null)) null
        else trunc(args.head.asInstanceOf[Number].doubleValue,
          if (args.size == 2) args(1).asInstanceOf[Number].longValue else 0L)
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
    * character counts from the first; one past the last, or a length below
    * 1, gives the empty string. Read as a BIGINT, a position past an INT's
    * range is past the end rather than wrapped round to before the start. */
  private[plan] def substring(s: String, position: Long, length: Long): String = {
    val start = if (position < 1) 0L else position - 1
    if (start >= s.length || length <= 0) ""
    else s.substring(start.toInt, if (length < s.length - start) (start + length).toInt else s.length)
  }

  /** ROUND's answer: `v` rounded half up (toward positive infinity, so -2.5
    * is -2) to `scale` decimal places, or to tens, hundreds, ... for a
    * negative scale ([[atScale]]). */
  private[plan] def round(v: Double, scale: Long): Double =
    atScale(v, scale, x => math.round(x).toDouble)

  /** TRUNC's answer: `v` cut toward zero at `scale` decimal places
    * ([[atScale]]), a negative zero read as 0. */
  private[plan] def trunc(v: Double, scale: Long): Double = {
    val t = atScale(v, scale, x => math.signum(x) * math.floor(math.abs(x)))
    if (t == 0.0) 0.0 else t
  }

  /** From 2^52 up every double is a whole number. */
  private val WholeDoubles: Double = 4503599627370496.0

  /** `v` at `scale` decimal places: `whole` of `v * 10^scale`, scaled back.
    * NaN and the infinities are their own answer. Where `v * 10^scale`
    * reaches 2^52, `v` is already whole at that scale, since a double that
    * large has no fraction left; multiplying it out would only lose digits,
    * and past 2^63 the long ROUND rounds through clamps. Where 10^scale is
    * too small for a double, every finite value is 0 at that scale. */
  private def atScale(v: Double, scale: Long, whole: Double => Double): Double = {
    val factor = math.pow(10, scale.toDouble)
    val scaled = v * factor
    if (java.lang.Double.isNaN(v) || java.lang.Double.isInfinite(v)) v
    else if (factor == 0.0) 0.0
    else if (!(math.abs(scaled) < WholeDoubles)) v
    else whole(scaled) / factor
  }

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
      case "FLOOR" if a.size == 1                     => Some(VecFuncs.floorCeil(a.head, batch, ceil = false))
      case "CEIL" | "CEILING" if a.size == 1          => Some(VecFuncs.floorCeil(a.head, batch, ceil = true))
      case "ROUND" if a.size == 1 || a.size == 2      => Some(VecFuncs.round(a, batch))
      case "TRUNC" | "TRUNCATE" if a.size == 1 || a.size == 2 => Some(VecFuncs.trunc(a, batch))
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
    * Args are evaluated left-to-right and short-circuited at row level (we
    * skip rows already filled). For analyzer-uniform `resultType`, all args
    * already share a type, so a typed copy would be possible — using
    * `setBoxed` keeps this simple and still avoids per-row `Expr.eval`. */
  def coalesce(args: Seq[Expr], resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(resultType, cap)
    val filled = new util.BitSet(cap)
    val it = args.iterator
    while (it.hasNext) {
      val argVec = it.next().evalVec(batch)
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
          else values(i) = math.abs(argVec.getBoxed(i).asInstanceOf[Number].intValue)
          i += 1
        }
        new IntVector(values, nulls, cap)
      case DataType.LongType =>
        val values = new Array[Long](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else values(i) = math.abs(argVec.getBoxed(i).asInstanceOf[Number].longValue)
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

  def floorCeil(arg: Expr, batch: ColumnarBatch, ceil: Boolean): DoubleVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVec = arg.evalVec(batch)
    val values = new Array[Double](cap)
    val nulls = new util.BitSet(cap)
    var i = 0
    while (i < n) {
      if (argVec.isNull(i)) nulls.set(i)
      else {
        val v = argVec.getBoxed(i).asInstanceOf[Number].doubleValue
        values(i) = if (ceil) math.ceil(v) else math.floor(v)
      }
      i += 1
    }
    new DoubleVector(values, nulls, cap)
  }

  /** ROUND(x[, scale]), by [[Funcs.round]]. NULL in either argument is NULL. */
  def round(args: Seq[Expr], batch: ColumnarBatch): DoubleVector = atEachScale(args, batch, Funcs.round)

  /** TRUNC(x[, scale]), by [[Funcs.trunc]]. NULL in either argument is NULL. */
  def trunc(args: Seq[Expr], batch: ColumnarBatch): DoubleVector = atEachScale(args, batch, Funcs.trunc)

  private def atEachScale(args: Seq[Expr], batch: ColumnarBatch, f: (Double, Long) => Double): DoubleVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val xVec = args(0).evalVec(batch)
    val scaleVec: ColumnVector = if (args.size == 2) args(1).evalVec(batch) else null
    val values = new Array[Double](cap)
    val nulls = new util.BitSet(cap)
    var i = 0
    while (i < n) {
      if (xVec.isNull(i) || (scaleVec != null && scaleVec.isNull(i))) nulls.set(i)
      else values(i) = f(xVec.getBoxed(i).asInstanceOf[Number].doubleValue,
        if (scaleVec != null) scaleVec.getBoxed(i).asInstanceOf[Number].longValue else 0L)
      i += 1
    }
    new DoubleVector(values, nulls, cap)
  }

  /** `IF(cond, a, b)`. Null/false cond → b. Both branches evaluated eagerly
    * (vectorized; side-effect-free Expr makes this safe). */
  def iff(args: Seq[Expr], resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(resultType, cap)
    val condVec = args(0).evalVec(batch).asInstanceOf[BooleanVector]
    val aVec = args(1).evalVec(batch)
    val bVec = args(2).evalVec(batch)
    var i = 0
    while (i < n) {
      val takeA = !condVec.isNull(i) && condVec.values(i)
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
