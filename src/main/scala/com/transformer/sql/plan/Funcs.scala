package com.transformer.sql.plan

import com.transformer.core._

import java.util

/** Scalar function registry. */
object Funcs {

  /** Return type for `fn(argTypes)`, or `None` if the function is unknown.
    * The analyzer uses this for type-checking; the executor uses [[apply]] at runtime.
    */
  def returnType(name: String, argTypes: Seq[DataType]): Option[DataType] = name.toUpperCase match {
    case "LENGTH" | "LEN" if argTypes.size == 1 => Some(DataType.IntType)
    case "UPPER" | "LOWER" | "TRIM" if argTypes.size == 1 => Some(DataType.StringType)
    case "SUBSTRING" if argTypes.size == 2 || argTypes.size == 3 => Some(DataType.StringType)
    case "CONCAT" => Some(DataType.StringType)
    case "COALESCE" if argTypes.nonEmpty => Some(widestNonNull(argTypes))
    case "IF" if argTypes.size == 3 => Some(widestNonNull(argTypes.drop(1)))
    case "NULLIF" if argTypes.size == 2 => Some(argTypes.head)
    case "ABS" if argTypes.size == 1 => Some(argTypes.head)
    case "ROUND" if argTypes.size >= 1 => Some(DataType.DoubleType)
    case "FLOOR" | "CEIL" | "CEILING" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "TRUNC" | "TRUNCATE" if argTypes.size == 1 || argTypes.size == 2 => Some(DataType.DoubleType)
    case "MOD" if argTypes.size == 2 => Some(DataType.widerNumeric(argTypes(0), argTypes(1)))
    case "POW" | "POWER" if argTypes.size == 2 => Some(DataType.DoubleType)
    case "SQRT" | "CBRT" | "EXP" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "LN" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "LOG" if argTypes.size == 1 || argTypes.size == 2 => Some(DataType.DoubleType)
    case "LOG10" | "LOG2" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "SIN" | "COS" | "TAN" | "ASIN" | "ACOS" | "ATAN" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "SINH" | "COSH" | "TANH" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "ATAN2" if argTypes.size == 2 => Some(DataType.DoubleType)
    case "DEGREES" | "RADIANS" if argTypes.size == 1 => Some(DataType.DoubleType)
    case "SIGN" if argTypes.size == 1 => Some(DataType.IntType)
    case "PI" | "E" if argTypes.isEmpty => Some(DataType.DoubleType)
    case "RAND" | "RANDOM" if argTypes.isEmpty || argTypes.size == 1 => Some(DataType.DoubleType)
    case "GREATEST" | "LEAST" if argTypes.nonEmpty => Some(widestNonNull(argTypes))
    case "CURRENT_DATE" if argTypes.isEmpty => Some(DataType.DateType)
    case "CURRENT_TIMESTAMP" if argTypes.isEmpty => Some(DataType.TimestampType)
    case _ => None
  }

  private def widestNonNull(types: Seq[DataType]): DataType = {
    val nonNull = types.filterNot(_ == DataType.NullType)
    if (nonNull.isEmpty) DataType.NullType
    else if (nonNull.forall(DataType.isNumeric)) nonNull.reduce(DataType.widerNumeric)
    else nonNull.head
  }

  /** Per-thread RNG so RAND() is safe under parallel execution. Seeded RAND(x) returns
    * a deterministic value (not a per-row stream) — sufficient for tests and tagging. */
  private val threadRng: ThreadLocal[java.util.Random] = new ThreadLocal[java.util.Random] {
    override def initialValue(): java.util.Random = new java.util.Random()
  }

  def apply(name: String, args: Seq[Any], resultType: DataType, argTypes: Seq[DataType]): Any =
    name.toUpperCase match {
      case "LENGTH" | "LEN" =>
        val v = args.head
        if (v == null) null else v.toString.length
      case "UPPER" =>
        if (args.head == null) null else args.head.toString.toUpperCase
      case "LOWER" =>
        if (args.head == null) null else args.head.toString.toLowerCase
      case "TRIM" =>
        if (args.head == null) null else args.head.toString.trim
      case "SUBSTRING" =>
        val s = args.head
        if (s == null) null
        else {
          val str = s.toString
          val start = math.max(0, args(1).asInstanceOf[Number].intValue - 1)
          val end =
            if (args.size == 3) math.min(str.length, start + args(2).asInstanceOf[Number].intValue)
            else str.length
          if (start >= str.length) "" else str.substring(start, math.max(start, end))
        }
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
          case n: java.lang.Number => resultType match {
            case DataType.IntType => math.abs(n.intValue)
            case DataType.LongType => math.abs(n.longValue)
            case _ => math.abs(n.doubleValue)
          }
          case _ => null
        }
      case "ROUND" =>
        if (args.head == null) null
        else {
          val v = args.head.asInstanceOf[Number].doubleValue
          val scale = if (args.size >= 2) args(1).asInstanceOf[Number].intValue else 0
          val factor = math.pow(10, scale)
          math.round(v * factor) / factor
        }
      case "FLOOR" =>
        if (args.head == null) null else math.floor(args.head.asInstanceOf[Number].doubleValue)
      case "CEIL" | "CEILING" =>
        if (args.head == null) null else math.ceil(args.head.asInstanceOf[Number].doubleValue)
      case "TRUNC" | "TRUNCATE" =>
        if (args.head == null) null
        else {
          val v = args.head.asInstanceOf[Number].doubleValue
          val scale = if (args.size >= 2) {
            if (args(1) == null) return null
            args(1).asInstanceOf[Number].intValue
          } else 0
          val factor = math.pow(10, scale)
          val truncated = math.signum(v) * math.floor(math.abs(v) * factor) / factor
          if (truncated == 0.0) 0.0 else truncated
        }
      case "MOD" =>
        if (args(0) == null || args(1) == null) null
        else Ops.apply("%", args(0), args(1), argTypes(0), argTypes(1), resultType)
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
        else {
          val seed = args.head.asInstanceOf[Number].longValue
          new java.util.Random(seed).nextDouble()
        }
      case "GREATEST" =>
        pickByOrdering(args, argTypes, resultType, greatest = true)
      case "LEAST" =>
        pickByOrdering(args, argTypes, resultType, greatest = false)
      case "CURRENT_DATE" =>
        java.time.LocalDate.now(java.time.ZoneOffset.UTC).toEpochDay.toInt
      case "CURRENT_TIMESTAMP" =>
        val now = java.time.Instant.now()
        now.getEpochSecond * 1000000L + now.getNano / 1000L
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
    name.toUpperCase match {
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
        values(i) = if (lower) s.toLowerCase else s.toUpperCase
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

  /** SUBSTRING(s, start[, len]). Mirrors [[Funcs.apply]]'s semantics: 1-based
    * start, clamps to string length, returns empty for out-of-range start. */
  def substring(args: Seq[Expr], batch: ColumnarBatch): StringVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val sVec = args(0).evalVec(batch)
    val startVec = args(1).evalVec(batch)
    val lenVec: ColumnVector = if (args.size == 3) args(2).evalVec(batch) else null
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      if (sVec.isNull(i)) values(i) = null
      else {
        val str = sVec.getBoxed(i).toString
        val start = math.max(0, startVec.getBoxed(i).asInstanceOf[Number].intValue - 1)
        val end =
          if (lenVec != null) math.min(str.length, start + lenVec.getBoxed(i).asInstanceOf[Number].intValue)
          else str.length
        values(i) = if (start >= str.length) "" else str.substring(start, math.max(start, end))
      }
      i += 1
    }
    new StringVector(values, cap)
  }

  /** ABS — preserves input numeric type (matches eval: `resultType` mirrors
    * argument type). Non-numeric args yield NULL, same as eval's
    * `case _ => null` fallback. */
  def abs(arg: Expr, resultType: DataType, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val argVec = arg.evalVec(batch)
    resultType match {
      case DataType.IntType =>
        val values = new Array[Int](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else argVec.getBoxed(i) match {
            case num: java.lang.Number => values(i) = math.abs(num.intValue)
            case _ => nulls.set(i)
          }
          i += 1
        }
        new IntVector(values, nulls, cap)
      case DataType.LongType =>
        val values = new Array[Long](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else argVec.getBoxed(i) match {
            case num: java.lang.Number => values(i) = math.abs(num.longValue)
            case _ => nulls.set(i)
          }
          i += 1
        }
        new LongVector(values, nulls, cap)
      case DataType.FloatType =>
        val values = new Array[Float](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else argVec.getBoxed(i) match {
            case num: java.lang.Number => values(i) = math.abs(num.doubleValue).toFloat
            case _ => nulls.set(i)
          }
          i += 1
        }
        new FloatVector(values, nulls, cap)
      case _ =>
        val values = new Array[Double](cap)
        val nulls = new util.BitSet(cap)
        var i = 0
        while (i < n) {
          if (argVec.isNull(i)) nulls.set(i)
          else argVec.getBoxed(i) match {
            case num: java.lang.Number => values(i) = math.abs(num.doubleValue)
            case _ => nulls.set(i)
          }
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

  def round(args: Seq[Expr], batch: ColumnarBatch): DoubleVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val xVec = args(0).evalVec(batch)
    val scaleVec: ColumnVector = if (args.size >= 2) args(1).evalVec(batch) else null
    val values = new Array[Double](cap)
    val nulls = new util.BitSet(cap)
    var i = 0
    while (i < n) {
      if (xVec.isNull(i)) nulls.set(i)
      else {
        val v = xVec.getBoxed(i).asInstanceOf[Number].doubleValue
        // Match eval: ROUND with a null scale arg NPEs (no explicit null
        // check on args(1)); using getBoxed → null → asInstanceOf[Number]
        // succeeds → .intValue NPEs, same failure.
        val scale = if (scaleVec != null) scaleVec.getBoxed(i).asInstanceOf[Number].intValue else 0
        val factor = math.pow(10, scale)
        values(i) = math.round(v * factor) / factor
      }
      i += 1
    }
    new DoubleVector(values, nulls, cap)
  }

  /** TRUNC matches eval semantics: NULL scale arg → NULL result (eval has an
    * explicit `if (args(1) == null) return null` for this; ROUND does not). */
  def trunc(args: Seq[Expr], batch: ColumnarBatch): DoubleVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val xVec = args(0).evalVec(batch)
    val scaleVec: ColumnVector = if (args.size >= 2) args(1).evalVec(batch) else null
    val values = new Array[Double](cap)
    val nulls = new util.BitSet(cap)
    var i = 0
    while (i < n) {
      if (xVec.isNull(i) || (scaleVec != null && scaleVec.isNull(i))) nulls.set(i)
      else {
        val v = xVec.getBoxed(i).asInstanceOf[Number].doubleValue
        val scale = if (scaleVec != null) scaleVec.getBoxed(i).asInstanceOf[Number].intValue else 0
        val factor = math.pow(10, scale)
        val truncated = math.signum(v) * math.floor(math.abs(v) * factor) / factor
        values(i) = if (truncated == 0.0) 0.0 else truncated
      }
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
