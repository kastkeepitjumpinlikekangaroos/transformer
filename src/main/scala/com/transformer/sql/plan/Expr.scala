package com.transformer.sql.plan

import com.transformer.core._

/** Bound (analyzed) expression. ColumnRefs have been resolved to column indices
  * against a child plan's output schema; type-checking and implicit casts have run.
  *
  * Two evaluation entry points:
  *   - [[eval]] returns one boxed `Any` for a single row — used by RowBuf-driven
  *     paths (sort comparators, hash-join key reads, window per-row callbacks).
  *   - [[evalVec]] returns a column vector for the whole batch — used by
  *     pipeline operators (Project, Filter) where eliminating per-row dispatch
  *     and boxing is the dominant speedup. Hot nodes override; everything else
  *     falls back to a default per-row writer that keeps results correct.
  */
sealed trait Expr {
  def dataType: DataType
  /** Evaluate against one row of a batch. Returns `null` for SQL NULL. */
  def eval(batch: ColumnarBatch, row: Int): Any

  /** Evaluate against every row of `batch`. Returns a [[ColumnVector]] whose
    * `dataType` equals [[dataType]] and whose valid range is `[0, batch.numRows)`.
    *
    * The returned vector may alias storage inside `batch` (e.g. [[ColRefExpr]]
    * returns the underlying column zero-copy). Callers must treat the result
    * as read-only — operators that need to OWN a result column should copy.
    *
    * The default implementation loops [[eval]] row-by-row and writes via
    * `setBoxed`. Hot nodes override for primitive-array speed.
    */
  def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val out = ColumnVector.allocate(dataType, math.max(1, n))
    var i = 0
    while (i < n) {
      val v = eval(batch, i)
      if (v == null) out.setNull(i) else out.setBoxed(i, v)
      i += 1
    }
    out
  }
}

/** Literal. `value` must be of the type implied by `dataType` (or `null`). */
final case class LitExpr(value: Any, dataType: DataType) extends Expr {
  def eval(batch: ColumnarBatch, row: Int): Any = value

  /** Broadcast the constant across the batch. Specialized for primitive types
    * so the fill is one `Arrays.fill` instead of N `setBoxed` calls. */
  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    if (value == null) {
      val out = ColumnVector.allocate(dataType, cap)
      var i = 0
      while (i < n) { out.setNull(i); i += 1 }
      return out
    }
    dataType match {
      case DataType.IntType =>
        val arr = new Array[Int](cap)
        java.util.Arrays.fill(arr, 0, n, value.asInstanceOf[Number].intValue)
        new IntVector(arr, new java.util.BitSet(cap), cap)
      case DataType.LongType =>
        val arr = new Array[Long](cap)
        java.util.Arrays.fill(arr, 0, n, value.asInstanceOf[Number].longValue)
        new LongVector(arr, new java.util.BitSet(cap), cap)
      case DataType.FloatType =>
        val arr = new Array[Float](cap)
        java.util.Arrays.fill(arr, 0, n, value.asInstanceOf[Number].floatValue)
        new FloatVector(arr, new java.util.BitSet(cap), cap)
      case DataType.DoubleType =>
        val arr = new Array[Double](cap)
        java.util.Arrays.fill(arr, 0, n, value.asInstanceOf[Number].doubleValue)
        new DoubleVector(arr, new java.util.BitSet(cap), cap)
      case DataType.BooleanType =>
        val arr = new Array[Boolean](cap)
        java.util.Arrays.fill(arr, 0, n, value.asInstanceOf[Boolean])
        new BooleanVector(arr, new java.util.BitSet(cap), cap)
      case DataType.StringType =>
        val arr = new Array[String](cap)
        java.util.Arrays.fill(arr.asInstanceOf[Array[AnyRef]], 0, n, value.asInstanceOf[AnyRef])
        new StringVector(arr, cap)
      case _ =>
        val out = ColumnVector.allocate(dataType, cap)
        var i = 0
        while (i < n) { out.setBoxed(i, value); i += 1 }
        out
    }
  }
}

/** Reference to a column at a known index in the child plan's schema. */
final case class ColRefExpr(index: Int, name: String, dataType: DataType) extends Expr {
  def eval(batch: ColumnarBatch, row: Int): Any =
    if (batch.column(index).isNull(row)) null else batch.column(index).getBoxed(row)

  /** Zero-copy: return the input column directly. Callers must treat the
    * result as read-only — operators that need ownership copy. */
  override def evalVec(batch: ColumnarBatch): ColumnVector = batch.column(index)
}

final case class CastExpr(child: Expr, target: DataType) extends Expr {
  def dataType: DataType = target
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    if (v == null) null else Casts.cast(v, child.dataType, target)
  }

  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val c = child.evalVec(batch)
    VecOps.cast(c, child.dataType, target, batch.numRows)
  }
}

final case class UnaryOpExpr(op: String, child: Expr, dataType: DataType) extends Expr {
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    if (v == null) null
    else op match {
      case "NOT" => !v.asInstanceOf[Boolean]
      case "-" =>
        dataType match {
          case DataType.IntType => -(v.asInstanceOf[Number].intValue)
          case DataType.LongType => -(v.asInstanceOf[Number].longValue)
          case DataType.FloatType => -(v.asInstanceOf[Number].floatValue)
          case DataType.DoubleType => -(v.asInstanceOf[Number].doubleValue)
          case _ => throw new IllegalStateException(s"Unary - on $dataType")
        }
      case "+" => v
      case other => throw new IllegalStateException(s"Unknown unary op '$other'")
    }
  }

  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val c = child.evalVec(batch)
    op match {
      case "+" => c
      case "-" => VecOps.negate(c, dataType, batch.numRows)
      case "NOT" => VecOps.not(c, batch.numRows)
      case other => throw new IllegalStateException(s"Unknown unary op '$other'")
    }
  }
}

final case class BinOpExpr(op: String, left: Expr, right: Expr, dataType: DataType) extends Expr {
  def eval(batch: ColumnarBatch, row: Int): Any = {
    op match {
      case "AND" =>
        val l = left.eval(batch, row)
        if (l != null && !l.asInstanceOf[Boolean]) false
        else {
          val r = right.eval(batch, row)
          if (r != null && !r.asInstanceOf[Boolean]) false
          else if (l == null || r == null) null
          else true
        }
      case "OR" =>
        val l = left.eval(batch, row)
        if (l != null && l.asInstanceOf[Boolean]) true
        else {
          val r = right.eval(batch, row)
          if (r != null && r.asInstanceOf[Boolean]) true
          else if (l == null || r == null) null
          else false
        }
      case _ =>
        val lv = left.eval(batch, row)
        val rv = right.eval(batch, row)
        if (lv == null || rv == null) null
        else Ops.apply(op, lv, rv, left.dataType, right.dataType, dataType)
    }
  }

  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    op match {
      case "AND" => VecOps.and(left.evalVec(batch), right.evalVec(batch), n)
      case "OR" => VecOps.or(left.evalVec(batch), right.evalVec(batch), n)
      case "||" => VecOps.concat(left.evalVec(batch), right.evalVec(batch), n)
      case "=" | "==" | "<>" | "!=" | "<" | "<=" | ">" | ">=" =>
        VecOps.compare(op, left.evalVec(batch), right.evalVec(batch), left.dataType, right.dataType, n)
      case "+" | "-" | "*" | "/" | "%" =>
        VecOps.arith(op, left.evalVec(batch), right.evalVec(batch), dataType, n)
      case other => throw new IllegalStateException(s"Unknown binary op '$other'")
    }
  }
}

final case class FuncExpr(name: String, args: Seq[Expr], dataType: DataType) extends Expr {
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val vals = args.map(_.eval(batch, row))
    Funcs.apply(name, vals, dataType, args.map(_.dataType))
  }
}

/** CASE WHEN cond THEN value … [ELSE default] END. */
final case class CaseExpr(branches: Seq[(Expr, Expr)], elseExpr: Option[Expr], dataType: DataType) extends Expr {
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val it = branches.iterator
    while (it.hasNext) {
      val (cond, value) = it.next()
      val c = cond.eval(batch, row)
      if (c != null && c.asInstanceOf[Boolean]) return value.eval(batch, row)
    }
    elseExpr.map(_.eval(batch, row)).orNull
  }
}

final case class IsNullExpr(child: Expr, negated: Boolean) extends Expr {
  def dataType: DataType = DataType.BooleanType
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    val isnull = v == null
    if (negated) !isnull else isnull
  }

  override def evalVec(batch: ColumnarBatch): ColumnVector =
    VecOps.isNull(child.evalVec(batch), negated, batch.numRows)
}

/** `x IN (a, b, …)`. */
final case class InListExpr(child: Expr, items: Seq[Expr], negated: Boolean) extends Expr {
  def dataType: DataType = DataType.BooleanType
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    if (v == null) null
    else {
      var sawNull = false
      val it = items.iterator
      while (it.hasNext) {
        val item = it.next().eval(batch, row)
        if (item == null) sawNull = true
        else if (Ops.eq(v, item)) return !negated
      }
      if (sawNull) null else negated
    }
  }
}

final case class LikeExpr(child: Expr, pattern: Expr, negated: Boolean) extends Expr {
  def dataType: DataType = DataType.BooleanType
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    val p = pattern.eval(batch, row)
    if (v == null || p == null) null
    else {
      val re = LikeExpr.toRegex(p.toString)
      val m = re.matcher(v.toString).matches()
      if (negated) !m else m
    }
  }
}

object LikeExpr {
  private val cache = new java.util.concurrent.ConcurrentHashMap[String, java.util.regex.Pattern]()
  def toRegex(like: String): java.util.regex.Pattern = {
    val cached = cache.get(like)
    if (cached != null) return cached
    val sb = new java.lang.StringBuilder("^")
    var i = 0
    while (i < like.length) {
      val c = like.charAt(i)
      c match {
        case '%' => sb.append(".*")
        case '_' => sb.append('.')
        case '\\' if i + 1 < like.length =>
          sb.append(java.util.regex.Pattern.quote(like.substring(i + 1, i + 2)))
          i += 1
        case other =>
          sb.append(java.util.regex.Pattern.quote(other.toString))
      }
      i += 1
    }
    sb.append("$")
    val p = java.util.regex.Pattern.compile(sb.toString, java.util.regex.Pattern.DOTALL)
    cache.put(like, p)
    p
  }
}

/** Aggregate expression. The planner replaces these with column refs to the
  * aggregate's output position before scalar evaluation happens.
  *
  * `args` is the full argument list — most aggregates take one expression, but
  * bivariate aggregates (COVAR, CORR) carry two. Column projection pushdown and
  * other plan rewrites visit every entry.
  */
sealed trait AggExpr {
  def name: String
  def args: Seq[Expr]
  def distinct: Boolean
  def resultType: DataType
}
final case class AggExprCountStar() extends AggExpr {
  val name = "COUNT"; val args: Seq[Expr] = Nil; val distinct = false
  val resultType: DataType = DataType.LongType
}
final case class AggExprCount(child: Expr, distinct: Boolean) extends AggExpr {
  val name = "COUNT"; val args: Seq[Expr] = Seq(child)
  val resultType: DataType = DataType.LongType
}
final case class AggExprSum(child: Expr) extends AggExpr {
  val name = "SUM"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = child.dataType match {
    case DataType.IntType | DataType.LongType => DataType.LongType
    case DataType.FloatType | DataType.DoubleType => DataType.DoubleType
    case other => other
  }
}
final case class AggExprAvg(child: Expr) extends AggExpr {
  val name = "AVG"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = DataType.DoubleType
}
final case class AggExprMin(child: Expr) extends AggExpr {
  val name = "MIN"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = child.dataType
}
final case class AggExprMax(child: Expr) extends AggExpr {
  val name = "MAX"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = child.dataType
}
final case class AggExprCountIf(child: Expr) extends AggExpr {
  val name = "COUNT_IF"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = DataType.LongType
}
/** Sample/population standard deviation and variance. `sample = true` divides
  * by N-1, otherwise by N. Returns NULL when not enough values are present
  * (count == 0 for population; count < 2 for sample). */
final case class AggExprStddev(child: Expr, sample: Boolean) extends AggExpr {
  val name: String = if (sample) "STDDEV_SAMP" else "STDDEV_POP"
  val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = DataType.DoubleType
}
final case class AggExprVariance(child: Expr, sample: Boolean) extends AggExpr {
  val name: String = if (sample) "VAR_SAMP" else "VAR_POP"
  val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = DataType.DoubleType
}
/** Bivariate aggregates: covariance and Pearson correlation. Pairs are
  * skipped when either side is NULL. */
final case class AggExprCovar(x: Expr, y: Expr, sample: Boolean) extends AggExpr {
  val name: String = if (sample) "COVAR_SAMP" else "COVAR_POP"
  val args: Seq[Expr] = Seq(x, y); val distinct = false
  val resultType: DataType = DataType.DoubleType
}
final case class AggExprCorr(x: Expr, y: Expr) extends AggExpr {
  val name = "CORR"; val args: Seq[Expr] = Seq(x, y); val distinct = false
  val resultType: DataType = DataType.DoubleType
}
