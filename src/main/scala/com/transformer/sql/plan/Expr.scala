package com.transformer.sql.plan

import com.transformer.core._

import java.util.Locale
import scala.util.control.NonFatal

/** Bound (analyzed) expression. ColumnRefs have been resolved to column indices
  * against a child plan's output schema; type-checking and implicit casts have run.
  *
  * Two evaluation entry points:
  *   - [[eval]] returns one boxed `Any` for a single row — used by RowBuf-driven
  *     paths (window per-row callbacks) and where a conditional evaluates an
  *     operand on the rows that need it.
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

object Expr {

  /** Whether `e` reads `RAND()`, whose value differs each time it is read, so
    * two evaluations of `e` on one row may disagree (`RAND(seed)` is one
    * value). An operator that reads a key twice, once to send a row to a
    * shard or a spill bucket and again to group, match or partition it there,
    * must not take such a key: `GROUP BY RAND() > 0.5` made four groups of
    * two, as each half of a group went to another shard. */
  def readsRandom(e: Expr): Boolean = e match {
    case FuncExpr(name, args, _) => FunctionCatalog.readsRandom(name, args.size) || args.exists(readsRandom)
    case _: ColRefExpr | _: LitExpr => false
    case CastExpr(c, _) => readsRandom(c)
    case DecimalCastExpr(c, _) => readsRandom(c)
    case UnaryOpExpr(_, c, _) => readsRandom(c)
    case BinOpExpr(_, l, r, _) => readsRandom(l) || readsRandom(r)
    case CaseExpr(branches, elseExpr, _) =>
      branches.exists { case (w, t) => readsRandom(w) || readsRandom(t) } || elseExpr.exists(readsRandom)
    case IsNullExpr(c, _) => readsRandom(c)
    case InListExpr(c, items, _) => readsRandom(c) || items.exists(readsRandom)
    case LikeExpr(c, pattern, _, _) => readsRandom(c) || readsRandom(pattern)
    case SwitchCaseExpr(operand, branches, elseExpr, _) =>
      readsRandom(operand) || branches.exists(b => readsRandom(b.when) || readsRandom(b.value)) ||
        elseExpr.exists(readsRandom)
    case BetweenExpr(c, low, high) => readsRandom(c) || readsRandom(low) || readsRandom(high)
  }

  /** `batch` with `column` beside its own columns, sharing them: how a node
    * that reads an operand once hands its value to the expressions that
    * compare it. */
  private[plan] def withColumn(batch: ColumnarBatch, column: ColumnVector, dataType: DataType, n: Int): ColumnarBatch =
    ColumnarBatch.fromColumns(Schema(batch.schema.fields :+ Field("<operand>", dataType)), batch.columns :+ column, n)
}

/** How a conditional evaluates an operand on only the rows that need it.
  *
  * `CASE`, `IF`, `COALESCE`, `AND`, `OR` and `IN` answer a row without reading
  * every operand on it, and an operand can fail on a row whose answer does not
  * need it: `CASE WHEN s = 'q' THEN CAST(s AS INT) END` must not fail on a row
  * holding `'x'`, nor `x <> 0 AND 100 * x > y` on one an overflow would. The
  * row path skips such an operand by evaluating it lazily. The vector path
  * evaluates an operand over the whole batch, which is what makes it fast and
  * what nearly every batch takes, and only when that fails does it go back to
  * the rows that need the operand, one at a time, so that only a failure on
  * one of those reaches the caller. */
private[plan] object Operand {

  /** `e` over `batch`, needed only on the rows `needed` accepts (an index in
    * `[0, batch.numRows)`). A row not needed reads NULL when the whole-batch
    * evaluation failed, and whatever `e` gave it otherwise, so a caller reads
    * the result on needed rows only. */
  def evalVec(e: Expr, batch: ColumnarBatch, needed: Int => Boolean): ColumnVector =
    try e.evalVec(batch)
    catch {
      case NonFatal(_) =>
        val n = batch.numRows
        val out = ColumnVector.allocate(e.dataType, math.max(1, n))
        var i = 0
        while (i < n) {
          if (!needed(i)) out.setNull(i)
          else {
            val v = e.eval(batch, i)
            if (v == null) out.setNull(i) else out.setBoxed(i, v)
          }
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

/** `CAST(x AS DECIMAL(p,s))` as a query writes it: its value as one of the
  * type, rounded half up to the scale and an error past the precision
  * ([[Casts.toWrittenDecimal]]). The binder's own casts to a DECIMAL are
  * [[CastExpr]]s, which read a number exactly, as the comparison or the
  * result they serve needs; the two stay apart, so a printed plan writes
  * this one and leaves the binder to insert its own again. */
final case class DecimalCastExpr(child: Expr, target: DataType.DecimalType) extends Expr {
  def dataType: DataType = target
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    if (v == null) null else Casts.toWrittenDecimal(v, target)
  }

  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val c = child.evalVec(batch)
    val n = batch.numRows
    val out = ColumnVector.allocate(target, math.max(1, n))
    var i = 0
    while (i < n) {
      if (c.isNull(i)) out.setNull(i) else out.setBoxed(i, Casts.toWrittenDecimal(c.getBoxed(i), target))
      i += 1
    }
    out
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
          case DataType.IntType => Integers.negate(v.asInstanceOf[Number].intValue)
          case DataType.LongType => Integers.negate(v.asInstanceOf[Number].longValue)
          case DataType.FloatType => -(v.asInstanceOf[Number].floatValue)
          case DataType.DoubleType => -(v.asInstanceOf[Number].doubleValue)
          case _: DataType.DecimalType => v.asInstanceOf[java.math.BigDecimal].negate
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
      case "AND" => evalAndChain(batch, n)
      case "OR" => evalOrChain(batch, n)
      case "||" => VecOps.concat(left.evalVec(batch), right.evalVec(batch), n)
      case "=" | "==" | "<>" | "!=" | "<" | "<=" | ">" | ">=" =>
        VecOps.compare(op, left.evalVec(batch), right.evalVec(batch), left.dataType, right.dataType, n)
      case "+" | "-" | "*" | "/" | "%" =>
        VecOps.arith(op, left.evalVec(batch), right.evalVec(batch), dataType, n)
      case other => throw new IllegalStateException(s"Unknown binary op '$other'")
    }
  }

  /** Flatten a `BinOpExpr("AND", ...)` tree, left-deep as a short chain binds
    * or balanced as a long one is regrouped, and fold all conjuncts
    * into a single [[BooleanVector]] allocation. The first conjunct's result
    * vector is COPIED into a fresh owned vector before folding — `BooleanVector`s
    * returned by `Expr.evalVec` may alias storage in the input batch
    * (e.g. a `ColRefExpr` to a Boolean column), and mutating an aliased vector
    * would corrupt the shared column. Subsequent conjuncts are folded in
    * place via [[VecOps.andInto]] so the chain pays one allocation total
    * instead of N intermediate vectors.
    *
    * Left to right, as the row path reads them: a conjunct is needed only on
    * the rows every earlier one left TRUE or NULL ([[Operand]]), and once no
    * row is left the rest are not evaluated at all. */
  private def evalAndChain(batch: ColumnarBatch, n: Int): BooleanVector = {
    val conjuncts = collectAndConjuncts()
    val first = conjuncts.head.evalVec(batch).asInstanceOf[BooleanVector]
    val acc = copyBooleanVector(first, n)
    val undecided: Int => Boolean = i => acc.nulls.get(i) || acc.values(i)
    var i = 1
    while (i < conjuncts.length && anyRow(n, undecided)) {
      val next = Operand.evalVec(conjuncts(i), batch, undecided).asInstanceOf[BooleanVector]
      VecOps.andInto(acc, next, n)
      i += 1
    }
    acc
  }

  /** As [[evalAndChain]], a disjunct needed only where every earlier one left
    * FALSE or NULL. */
  private def evalOrChain(batch: ColumnarBatch, n: Int): BooleanVector = {
    val conjuncts = collectOrConjuncts()
    val first = conjuncts.head.evalVec(batch).asInstanceOf[BooleanVector]
    val acc = copyBooleanVector(first, n)
    val undecided: Int => Boolean = i => acc.nulls.get(i) || !acc.values(i)
    var i = 1
    while (i < conjuncts.length && anyRow(n, undecided)) {
      val next = Operand.evalVec(conjuncts(i), batch, undecided).asInstanceOf[BooleanVector]
      VecOps.orInto(acc, next, n)
      i += 1
    }
    acc
  }

  private def anyRow(n: Int, p: Int => Boolean): Boolean = {
    var i = 0
    while (i < n) { if (p(i)) return true; i += 1 }
    false
  }

  private def collectAndConjuncts(): Array[Expr] = {
    // Flatten the AND tree once at evalVec time. The tree shape doesn't change
    // between batches but we don't bother caching across calls — building a
    // ~10-element array dispatched per batch is negligible next to the per-row
    // work it replaces.
    val buf = new scala.collection.mutable.ArrayBuffer[Expr](4)
    collectAndInto(this, buf)
    buf.toArray
  }
  private def collectOrConjuncts(): Array[Expr] = {
    val buf = new scala.collection.mutable.ArrayBuffer[Expr](4)
    collectOrInto(this, buf)
    buf.toArray
  }
  private def collectAndInto(e: Expr, out: scala.collection.mutable.ArrayBuffer[Expr]): Unit = e match {
    case BinOpExpr("AND", l, r, _) =>
      collectAndInto(l, out)
      collectAndInto(r, out)
    case other => out += other
  }
  private def collectOrInto(e: Expr, out: scala.collection.mutable.ArrayBuffer[Expr]): Unit = e match {
    case BinOpExpr("OR", l, r, _) =>
      collectOrInto(l, out)
      collectOrInto(r, out)
    case other => out += other
  }
  private def copyBooleanVector(src: BooleanVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = java.util.Arrays.copyOf(src.values, cap)
    val nulls = new java.util.BitSet(cap)
    nulls.or(src.nulls)
    new BooleanVector(values, nulls, cap)
  }
}

final case class FuncExpr(name: String, args: Seq[Expr], dataType: DataType) extends Expr {
  private lazy val upperName: String = name.toUpperCase(Locale.ROOT)

  /** `IF` and `COALESCE` read an argument only when the answer needs it, as
    * `CASE` does; every other function reads all of its arguments. */
  def eval(batch: ColumnarBatch, row: Int): Any = upperName match {
    case "IF" if args.size == 3 =>
      val cond = args(0).eval(batch, row)
      if (cond != null && cond.asInstanceOf[Boolean]) args(1).eval(batch, row) else args(2).eval(batch, row)
    case "COALESCE" =>
      val it = args.iterator
      while (it.hasNext) {
        val v = it.next().eval(batch, row)
        if (v != null) return v
      }
      null
    case _ =>
      val vals = args.map(_.eval(batch, row))
      Funcs.apply(name, vals, dataType, args.map(_.dataType))
  }

  /** Fast path for the hot subset of scalar functions (see [[Funcs.applyVec]]
    * — COALESCE, LOWER/UPPER/TRIM/LENGTH/CONCAT/SUBSTRING, ABS/FLOOR/CEIL/
    * ROUND/TRUNC, IF/NULLIF). Everything else falls back to the default
    * `Expr.evalVec` row loop. */
  override def evalVec(batch: ColumnarBatch): ColumnVector =
    Funcs.applyVec(name, args, dataType, batch).getOrElse(super.evalVec(batch))
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

  /** Vectorized CASE: one Expr-tree dispatch per branch per batch instead of
    * per row. Each `WHEN` is needed on the rows no earlier branch claimed,
    * each `THEN` on the rows its `WHEN` claims, and `ELSE` on the rest, and
    * [[Operand]] evaluates each so, which is what makes a `CASE` a guard: a
    * value that would fail on a row another branch answers never fails the
    * batch. Once every row is claimed, the remaining branches are not
    * evaluated at all. */
  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(dataType, cap)
    val taken = new java.util.BitSet(cap)
    val claimed = new java.util.BitSet(cap)
    var remaining = n
    val it = branches.iterator
    while (it.hasNext && remaining > 0) {
      val (cond, value) = it.next()
      val condVec = Operand.evalVec(cond, batch, i => !taken.get(i)).asInstanceOf[BooleanVector]
      claimed.clear()
      var i = 0
      while (i < n) {
        if (!taken.get(i) && !condVec.isNull(i) && condVec.values(i)) claimed.set(i)
        i += 1
      }
      if (!claimed.isEmpty) {
        val valueVec = Operand.evalVec(value, batch, claimed.get)
        i = claimed.nextSetBit(0)
        while (i >= 0 && i < n) {
          if (valueVec.isNull(i)) out.setNull(i)
          else out.setBoxed(i, valueVec.getBoxed(i))
          taken.set(i)
          remaining -= 1
          i = claimed.nextSetBit(i + 1)
        }
      }
    }
    if (remaining > 0) {
      elseExpr match {
        case Some(e) =>
          val elseVec = Operand.evalVec(e, batch, i => !taken.get(i))
          var i = 0
          while (i < n) {
            if (!taken.get(i)) {
              if (elseVec.isNull(i)) out.setNull(i)
              else out.setBoxed(i, elseVec.getBoxed(i))
            }
            i += 1
          }
        case None =>
          var i = 0
          while (i < n) {
            if (!taken.get(i)) out.setNull(i)
            i += 1
          }
      }
    }
    out
  }
}

/** One WHEN of a [[SwitchCaseExpr]]: the operand, read as `compareAs`, equals
  * `when` (already of that type), and the CASE answers `value`. */
final case class SwitchBranch(compareAs: DataType, when: Expr, value: Expr)

/** `CASE operand WHEN w1 THEN r1 ... [ELSE e] END` whose operand is read once
  * per row and compared with each WHEN as `=` compares the two, the operand
  * read as `compareAs` beside each. The binder makes one only for an operand
  * that reads RAND() ([[Expr.readsRandom]]): any other simple CASE is the
  * searched CASE of its comparisons, `CASE WHEN operand = w1 ...`, which reads
  * the operand once per WHEN to the same answer and keeps each comparison
  * where the optimizer can read it. Read once per WHEN, `CASE FLOOR(RAND() *
  * 3) WHEN 0 ... WHEN 1 ... WHEN 2 ... END` met a fresh value at each and left
  * a third of the rows NULL. A WHEN and a THEN are read on the rows that need
  * them, as in [[CaseExpr]]. */
final case class SwitchCaseExpr(
    operand: Expr,
    branches: Seq[SwitchBranch],
    elseExpr: Option[Expr],
    dataType: DataType) extends Expr {

  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = operand.eval(batch, row)
    if (v != null) {
      val it = branches.iterator
      while (it.hasNext) {
        val b = it.next()
        val w = b.when.eval(batch, row)
        if (w != null && Ops.eq(Casts.cast(v, operand.dataType, b.compareAs), w)) return b.value.eval(batch, row)
      }
    }
    elseExpr.map(_.eval(batch, row)).orNull
  }

  /** The operand's vector, read once, is a column appended to the batch, and
    * the searched CASE of comparisons with that column answers: the same
    * guards, over a value every WHEN reads alike. */
  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val width = batch.schema.length
    val read = ColRefExpr(width, "<case operand>", operand.dataType)
    val searched = CaseExpr(
      branches.map(b => (BinOpExpr("=", Analyzer.implicitCast(read, b.compareAs), b.when, DataType.BooleanType), b.value)),
      elseExpr, dataType)
    searched.evalVec(Expr.withColumn(batch, operand.evalVec(batch), operand.dataType, n))
  }
}

/** `x BETWEEN lo AND hi` whose `x` is read once per row: `x >= lo AND x <=
  * hi`, the three of one type, with `hi` read only where `x >= lo` is not
  * FALSE, as AND reads its right side. The binder makes one only for an `x`
  * that reads RAND(); any other BETWEEN is its two comparisons, which a
  * parquet scan can skip row groups by. Read twice, `RAND() BETWEEN 0.25 AND
  * 0.75` held on 56% of rows rather than half. */
final case class BetweenExpr(child: Expr, low: Expr, high: Expr) extends Expr {
  def dataType: DataType = DataType.BooleanType

  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    if (v == null) null
    else {
      val l = low.eval(batch, row)
      val atLeast: Any = if (l == null) null else Ops.cmp(v, l) >= 0
      if (atLeast == false) false
      else {
        val h = high.eval(batch, row)
        val atMost: Any = if (h == null) null else Ops.cmp(v, h) <= 0
        if (atMost == false) false
        else if (atLeast == null || atMost == null) null
        else true
      }
    }
  }

  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val read = ColRefExpr(batch.schema.length, "<between operand>", child.dataType)
    val both = BinOpExpr("AND",
      BinOpExpr(">=", read, low, DataType.BooleanType),
      BinOpExpr("<=", read, high, DataType.BooleanType), DataType.BooleanType)
    both.evalVec(Expr.withColumn(batch, child.evalVec(batch), child.dataType, n))
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

  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new java.util.BitSet(cap)
    val cv = child.evalVec(batch)

    val allLits = items.forall(_.isInstanceOf[LitExpr])
    if (allLits) {
      // Hoist literal values out of the per-row loop; choose a HashSet keyed
      // by the comparator-relevant form so the inner loop is a single
      // `.contains` call, comparing as `Ops.eq` does: integers key on their
      // longValue, any other pair of numbers with a FLOAT or DOUBLE in it on
      // its doubleValue. A decimal on either side of the IN compares exactly,
      // so it takes the scan below, as do booleans, dates and byte arrays.
      //
      // The numeric set keys on a value canonicalized by `InListExpr.setKey`,
      // because boxing imports `java.lang.Double.equals`, which separates
      // `-0.0` from `0.0`, two values `Ops.eq` calls equal. (It already treats
      // every NaN as one, as `Ops.eq` does.)
      val nItems = items.length
      val lits = items.iterator.map(_.asInstanceOf[LitExpr].value).toArray
      var sawNullItem = false
      var i = 0
      while (i < nItems) { if (lits(i) == null) sawNullItem = true; i += 1 }

      val integralSet: java.util.HashSet[java.lang.Long] =
        if (DataType.isIntegral(child.dataType) &&
            items.forall(i => i.dataType == DataType.NullType || DataType.isIntegral(i.dataType))) {
          val s = new java.util.HashSet[java.lang.Long]()
          var k = 0
          while (k < nItems) {
            val v = lits(k)
            if (v != null) s.add(java.lang.Long.valueOf(v.asInstanceOf[Number].longValue))
            k += 1
          }
          s
        } else null

      val numericSet: java.util.HashSet[java.lang.Double] =
        if (integralSet == null && VecOps.comparesAsDouble(child.dataType) &&
            items.forall(i => i.dataType == DataType.NullType || VecOps.comparesAsDouble(i.dataType))) {
          val s = new java.util.HashSet[java.lang.Double]()
          var k = 0
          while (k < nItems) {
            val v = lits(k)
            if (v != null) s.add(java.lang.Double.valueOf(InListExpr.setKey(v.asInstanceOf[Number].doubleValue)))
            k += 1
          }
          s
        } else null

      val stringSet: java.util.HashSet[String] =
        if (child.dataType == DataType.StringType) {
          val s = new java.util.HashSet[String]()
          var k = 0
          while (k < nItems) {
            val v = lits(k)
            if (v != null) s.add(v.toString)
            k += 1
          }
          s
        } else null

      var r = 0
      while (r < n) {
        if (cv.isNull(r)) nulls.set(r)
        else {
          val v = cv.getBoxed(r)
          var found = false
          if (integralSet != null) {
            found = integralSet.contains(java.lang.Long.valueOf(v.asInstanceOf[Number].longValue))
          } else if (numericSet != null) {
            val d = v.asInstanceOf[Number].doubleValue
            found = numericSet.contains(java.lang.Double.valueOf(InListExpr.setKey(d)))
          } else if (stringSet != null) {
            found = stringSet.contains(v.toString)
          } else {
            // Decimal / Boolean / Date / Timestamp / Binary — linear scan with Ops.eq.
            var k = 0
            while (k < nItems && !found) {
              val item = lits(k)
              if (item != null && Ops.eq(v, item)) found = true
              k += 1
            }
          }
          if (found) values(r) = !negated
          else if (sawNullItem) nulls.set(r)
          else values(r) = negated
        }
        r += 1
      }
    } else {
      // General path: evaluate each item once per batch into a vector. As the
      // row path stops at the first item that matches, an item is needed only
      // on the rows no earlier item matched ([[Operand]]), so one that would
      // fail there does not fail the batch.
      val found = new java.util.BitSet(cap)
      val sawNull = new java.util.BitSet(cap)
      val open: Int => Boolean = r => !cv.isNull(r) && !found.get(r)
      val it = items.iterator
      while (it.hasNext) {
        val iv = Operand.evalVec(it.next(), batch, open)
        var r = 0
        while (r < n) {
          if (open(r)) {
            if (iv.isNull(r)) sawNull.set(r)
            else if (Ops.eq(cv.getBoxed(r), iv.getBoxed(r))) found.set(r)
          }
          r += 1
        }
      }
      var r = 0
      while (r < n) {
        if (cv.isNull(r)) nulls.set(r)
        else if (found.get(r)) values(r) = !negated
        else if (sawNull.get(r)) nulls.set(r)
        else values(r) = negated
        r += 1
      }
    }
    new BooleanVector(values, nulls, cap)
  }
}

object InListExpr {
  /** Key a numeric value for the hoisted membership set so that boxed equality
    * agrees with [[Ops.eq]]. Only signed zero needs it: `-0.0` equals `0.0`
    * but the two box to different bit patterns, so both collapse to `+0.0`.
    * Boxed equality already holds every NaN equal, as `Ops.eq` does. */
  private[plan] def setKey(d: Double): Double = if (d == 0.0) 0.0 else d
}

/** How a LIKE pattern is read. `caseInsensitive` is ILIKE: letters match
  * regardless of case (Unicode case folding, as `Pattern.UNICODE_CASE` does).
  * `escape` is the character that makes the next one literal, so `\_` matches
  * an underscore: a backslash unless the query says `ESCAPE 'c'`, and none at
  * all for `ESCAPE ''`. */
final case class LikeSyntax(caseInsensitive: Boolean = false, escape: Option[Char] = Some('\\'))

object LikeSyntax {
  val Default: LikeSyntax = LikeSyntax()
}

/** `child LIKE pattern`, or ILIKE per `syntax`. Code that rebuilds a LikeExpr
  * with new children uses `copy`, so a field added to [[LikeSyntax]] cannot be
  * dropped on the way through a rewrite. */
final case class LikeExpr(
    child: Expr,
    pattern: Expr,
    negated: Boolean,
    syntax: LikeSyntax = LikeSyntax.Default) extends Expr {
  def dataType: DataType = DataType.BooleanType
  def eval(batch: ColumnarBatch, row: Int): Any = {
    val v = child.eval(batch, row)
    val p = pattern.eval(batch, row)
    if (v == null || p == null) null
    else {
      val re = LikeExpr.toRegex(p.toString, syntax)
      val m = re.matcher(v.toString).matches()
      if (negated) !m else m
    }
  }

  /** Vectorized LIKE.
    *
    * Literal-pattern fast path: compile + build the [[java.util.regex.Matcher]]
    * once outside the row loop and `.reset(input)` per row (re-using the
    * Matcher avoids the per-row state allocation the per-row `eval` path was
    * making through the global pattern cache).
    *
    * Column-pattern path: still one regex compile per row (via the global
    * cache hit), but with one Expr-tree dispatch per batch instead of per
    * row. Documented as the slow path. */
  override def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new java.util.BitSet(cap)
    val cv = child.evalVec(batch)

    pattern match {
      case LitExpr(p, _) if p == null =>
        // SQL: input LIKE NULL → NULL for every row.
        var i = 0
        while (i < n) { nulls.set(i); i += 1 }
      case LitExpr(p, _) =>
        // Compiled at the first row that has a string to match, as the row
        // path compiles it: a pattern that is no pattern (one ending in its
        // escape) answers NULL on NULL rows rather than failing the batch.
        lazy val matcher = LikeExpr.toRegex(p.toString, syntax).matcher("")
        var i = 0
        while (i < n) {
          if (cv.isNull(i)) nulls.set(i)
          else {
            matcher.reset(cv.getBoxed(i).toString)
            val m = matcher.matches()
            values(i) = if (negated) !m else m
          }
          i += 1
        }
      case _ =>
        val pv = pattern.evalVec(batch)
        var i = 0
        while (i < n) {
          if (cv.isNull(i) || pv.isNull(i)) nulls.set(i)
          else {
            val m = LikeExpr.toRegex(pv.getBoxed(i).toString, syntax).matcher(cv.getBoxed(i).toString).matches()
            values(i) = if (negated) !m else m
          }
          i += 1
        }
    }
    new BooleanVector(values, nulls, cap)
  }
}

object LikeExpr {
  /** Compiled patterns, a column of patterns among them, so it is emptied
    * once it holds [[CacheLimit]]: a pattern per row of a long-lived session
    * otherwise held every one it had ever read. */
  private val cache = new java.util.concurrent.ConcurrentHashMap[(String, LikeSyntax), java.util.regex.Pattern]()
  private val CacheLimit = 4096
  def toRegex(like: String, syntax: LikeSyntax = LikeSyntax.Default): java.util.regex.Pattern = {
    val key = (like, syntax)
    val cached = cache.get(key)
    if (cached != null) return cached
    val sb = new java.lang.StringBuilder("^")
    // The pattern is read a character at a time, a character outside the
    // Basic Multilingual Plane (an emoji) being one, though Java holds it as
    // two chars: the regex reads its input so, and half of one matched
    // nothing, so a pattern holding one matched no string, not even itself.
    def character(at: Int): String = like.substring(at, at + Character.charCount(like.codePointAt(at)))
    var i = 0
    while (i < like.length) {
      val c = like.charAt(i)
      // The escape character comes first, so it works even when it is `%` or
      // `_`. A trailing one has nothing to escape, which Postgres refuses: it
      // stood for itself, so `'a\'` matched what `'a\\'` does.
      if (syntax.escape.contains(c)) {
        if (i + 1 == like.length) throw new IllegalArgumentException(
          s"The LIKE pattern '${like.replace("'", "''")}' ends in its escape character, $c, which escapes " +
            s"nothing; write $c$c to match one")
        val escaped = character(i + 1)
        sb.append(java.util.regex.Pattern.quote(escaped))
        i += 1 + escaped.length
      } else c match {
        case '%' => sb.append(".*"); i += 1
        case '_' => sb.append('.'); i += 1
        case _ =>
          val literal = character(i)
          sb.append(java.util.regex.Pattern.quote(literal))
          i += literal.length
      }
    }
    sb.append("$")
    val caseFlags =
      if (syntax.caseInsensitive) java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE
      else 0
    val p = java.util.regex.Pattern.compile(sb.toString, java.util.regex.Pattern.DOTALL | caseFlags)
    if (cache.size >= CacheLimit) cache.clear()
    cache.put(key, p)
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
/** `SUM`: a BIGINT over integers, a DOUBLE over floats, and over a
  * DECIMAL(p,s) a DECIMAL(38,s), added exactly ([[FunctionCatalog.Result.Sum]]). */
final case class AggExprSum(child: Expr) extends AggExpr {
  val name = "SUM"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = FunctionCatalog.Result.Sum.of(child.dataType)
}
/** `AVG`: a DOUBLE, but over a DECIMAL(p,s) a DECIMAL with four more
  * fractional digits ([[FunctionCatalog.Result.Avg]]), from the exact sum. */
final case class AggExprAvg(child: Expr) extends AggExpr {
  val name = "AVG"; val args: Seq[Expr] = Seq(child); val distinct = false
  val resultType: DataType = FunctionCatalog.Result.Avg.of(child.dataType)
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
/** `inner` over the DISTINCT non-NULL values of its argument — `SUM(DISTINCT x)`
  * and `AVG(DISTINCT x)`. Same name, argument and result type as `inner`; only
  * the input is de-duplicated. `COUNT(DISTINCT x)` predates this and keeps its
  * own flag on [[AggExprCount]]; `MIN` / `MAX` never need it, since DISTINCT
  * cannot change an extreme. */
final case class AggExprDistinct(inner: AggExpr) extends AggExpr {
  require(inner.args.length == 1, s"DISTINCT takes a one-argument aggregate, got ${inner.name}")
  val name: String = inner.name
  val args: Seq[Expr] = inner.args
  val distinct = true
  val resultType: DataType = inner.resultType
}
