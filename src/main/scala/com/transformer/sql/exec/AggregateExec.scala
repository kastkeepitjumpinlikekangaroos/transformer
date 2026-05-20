package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util
import java.util.concurrent.Callable
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Hash-based GROUP BY. Each input partition builds a partial map of
  * `groupKey -> AggStates`; the merge step combines partials into the final result.
  *
  * Output schema = group keys ++ aggregate results (in the order given).
  *
  * Group keys are encoded via [[KeyCodec]] — packed `byte[]` (with cached
  * hashCode and `Arrays.equals` equality) for fixed-width-only keys, cached-
  * hash `Array[AnyRef]` otherwise. When every group expression is a pure
  * [[ColRefExpr]] (the common case) the codec reads primitives directly from
  * the batch's typed [[ColumnVector]]s, avoiding the per-row boxing the old
  * `Seq[Any]`-keyed implementation paid.
  */
final case class HashAggregateExec(
    child: PhysicalPlan,
    groupKeys: Seq[(Expr, String)],
    aggregates: Seq[(AggExpr, String)]
) extends PhysicalPlan {

  val outputSchema: Schema = Schema(
    (groupKeys.map { case (e, n) => Field(n, e.dataType) } ++
      aggregates.map { case (a, n) => Field(n, a.resultType) }).toVector
  )

  def numPartitions: Int = 1

  private val keyExprs: Array[Expr] = groupKeys.iterator.map(_._1).toArray
  private val nKeys: Int = keyExprs.length
  private val keyTypes: Array[DataType] = keyExprs.map(_.dataType)
  private val keysAreColRefs: Boolean = keyExprs.forall(_.isInstanceOf[ColRefExpr])
  private val keyCodec: KeyCodec = {
    val indices =
      if (keysAreColRefs) keyExprs.map(_.asInstanceOf[ColRefExpr].index)
      else new Array[Int](nKeys)
    KeyCodec.forColumns(indices, keyTypes)
  }

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val tasks: Seq[Callable[mutable.LinkedHashMap[AnyRef, Array[AggState]]]] =
      (0 until child.numPartitions).map { p =>
        new Callable[mutable.LinkedHashMap[AnyRef, Array[AggState]]] {
          def call(): mutable.LinkedHashMap[AnyRef, Array[AggState]] = partialAggregate(p)
        }
      }
    val partials: Seq[mutable.LinkedHashMap[AnyRef, Array[AggState]]] =
      Scheduler.submitAndAwaitAll(tasks)

    val merged = partials.reduceOption(merge).getOrElse(mutable.LinkedHashMap.empty)
    // For aggregations with no GROUP BY: SQL requires one row of aggregate output
    // even when input is empty (e.g., COUNT(*) over empty source = 0).
    if (groupKeys.isEmpty && merged.isEmpty) {
      val seed = Array.tabulate(aggregates.size)(i => AggState.init(aggregates(i)._1))
      merged += (keyCodec.encodeBoxed(EmptyKeyBuf) -> seed)
    }
    emit(merged)
  }

  private val EmptyKeyBuf: Array[Any] = new Array[Any](0)

  private def partialAggregate(p: Int): mutable.LinkedHashMap[AnyRef, Array[AggState]] = {
    val map = mutable.LinkedHashMap.empty[AnyRef, Array[AggState]]
    val it = child.execute(p)
    val keyBuf: Array[Any] = if (keysAreColRefs) null else new Array[Any](nKeys)
    while (it.hasNext) {
      val batch = it.next()
      val nrows = batch.numRows
      var r = 0
      while (r < nrows) {
        val key: AnyRef =
          if (keysAreColRefs) keyCodec.encodeFromBatch(batch, r)
          else {
            var i = 0
            while (i < nKeys) { keyBuf(i) = keyExprs(i).eval(batch, r); i += 1 }
            keyCodec.encodeBoxed(keyBuf)
          }
        val states = map.getOrElseUpdate(key, {
          Array.tabulate(aggregates.size)(i => AggState.init(aggregates(i)._1))
        })
        var a = 0
        while (a < aggregates.size) {
          states(a).update(aggregates(a)._1, batch, r)
          a += 1
        }
        r += 1
      }
    }
    map
  }

  private def merge(
      a: mutable.LinkedHashMap[AnyRef, Array[AggState]],
      b: mutable.LinkedHashMap[AnyRef, Array[AggState]]): mutable.LinkedHashMap[AnyRef, Array[AggState]] = {
    b.foreach { case (k, bs) =>
      a.get(k) match {
        case Some(as) =>
          var i = 0
          while (i < as.length) { as(i).merge(bs(i)); i += 1 }
        case None => a.put(k, bs)
      }
    }
    a
  }

  private def emit(map: mutable.LinkedHashMap[AnyRef, Array[AggState]]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val iter = map.iterator
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = iter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(outputSchema, capacity)
        var row = 0
        while (row < capacity && iter.hasNext) {
          val (key, states) = iter.next()
          keyCodec.decode(key, out, 0, row)
          var a = 0
          while (a < states.length) {
            val v = states(a).finish()
            val idx = nKeys + a
            if (v == null) out.column(idx).setNull(row) else out.column(idx).setBoxed(row, v)
            a += 1
          }
          row += 1
        }
        out.setNumRows(row)
        out
      }
    }
  }
}

// ---------------------------------------------------------------------------
// Per-aggregate state. Designed for partial + merge: each partition builds its own
// states; merge() combines them; finish() returns the final value.
// ---------------------------------------------------------------------------

sealed trait AggState {
  def update(agg: AggExpr, batch: ColumnarBatch, row: Int): Unit
  def merge(other: AggState): Unit
  def finish(): Any
}

object AggState {
  def init(agg: AggExpr): AggState = agg match {
    case _: AggExprCountStar => new CountStarState()
    case AggExprCount(_, false) => new CountState()
    case AggExprCount(_, true) => new CountDistinctState()
    case s: AggExprSum => SumState.init(s)
    case _: AggExprAvg => new AvgState()
    case m: AggExprMin => new MinMaxState(min = true, m.resultType)
    case m: AggExprMax => new MinMaxState(min = false, m.resultType)
    case _: AggExprCountIf => new CountIfState()
    case s: AggExprStddev => new MomentState(sample = s.sample, stddev = true)
    case v: AggExprVariance => new MomentState(sample = v.sample, stddev = false)
    case c: AggExprCovar => new CovarState(sample = c.sample)
    case _: AggExprCorr => new CorrState()
  }
}

final class CountStarState extends AggState {
  private var c: Long = 0L
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = { c += 1 }
  def merge(o: AggState): Unit = c += o.asInstanceOf[CountStarState].c
  def finish(): Any = c
}

final class CountState extends AggState {
  private var c: Long = 0L
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    if (agg.asInstanceOf[AggExprCount].child.eval(b, r) != null) c += 1
  }
  def merge(o: AggState): Unit = c += o.asInstanceOf[CountState].c
  def finish(): Any = c
}

final class CountDistinctState extends AggState {
  private val set = new util.HashSet[Any]()
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = agg.asInstanceOf[AggExprCount].child.eval(b, r)
    if (v != null) set.add(v)
  }
  def merge(o: AggState): Unit = set.addAll(o.asInstanceOf[CountDistinctState].set)
  def finish(): Any = set.size.toLong
}

final class CountIfState extends AggState {
  private var c: Long = 0L
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = agg.asInstanceOf[AggExprCountIf].child.eval(b, r)
    if (v != null && v.asInstanceOf[Boolean]) c += 1
  }
  def merge(o: AggState): Unit = c += o.asInstanceOf[CountIfState].c
  def finish(): Any = c
}

sealed trait SumState extends AggState
object SumState {
  def init(agg: AggExprSum): SumState = agg.child.dataType match {
    case DataType.IntType | DataType.LongType => new LongSumState()
    case _ => new DoubleSumState()
  }
}
final class LongSumState extends SumState {
  private var sum: Long = 0L
  private var sawAny: Boolean = false
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = agg.asInstanceOf[AggExprSum].child.eval(b, r)
    if (v != null) { sum += v.asInstanceOf[Number].longValue; sawAny = true }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[LongSumState]
    sum += that.sum
    sawAny = sawAny || that.sawAny
  }
  def finish(): Any = if (sawAny) sum else null
}
final class DoubleSumState extends SumState {
  private var sum: Double = 0.0
  private var sawAny: Boolean = false
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = agg.asInstanceOf[AggExprSum].child.eval(b, r)
    if (v != null) { sum += v.asInstanceOf[Number].doubleValue; sawAny = true }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[DoubleSumState]
    sum += that.sum
    sawAny = sawAny || that.sawAny
  }
  def finish(): Any = if (sawAny) sum else null
}

final class AvgState extends AggState {
  private var sum: Double = 0.0
  private var count: Long = 0L
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = agg.asInstanceOf[AggExprAvg].child.eval(b, r)
    if (v != null) {
      sum += v.asInstanceOf[Number].doubleValue
      count += 1
    }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[AvgState]
    sum += that.sum
    count += that.count
  }
  def finish(): Any = if (count == 0L) null else sum / count
}

final class MinMaxState(min: Boolean, dt: DataType) extends AggState {
  private var current: Any = null
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = agg match {
      case m: AggExprMin => m.child.eval(b, r)
      case m: AggExprMax => m.child.eval(b, r)
      case _ => throw new IllegalStateException
    }
    if (v != null) {
      if (current == null) current = v
      else {
        val c = Ops.cmp(v, current)
        if ((min && c < 0) || (!min && c > 0)) current = v
      }
    }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[MinMaxState]
    if (that.current != null) {
      if (current == null) current = that.current
      else {
        val c = Ops.cmp(that.current, current)
        if ((min && c < 0) || (!min && c > 0)) current = that.current
      }
    }
  }
  def finish(): Any = current
}

/** Univariate variance / standard deviation via Welford + Chan's parallel
  * merge. Numerically stable vs. the naive sum-of-squares formula and works
  * across partial-aggregation boundaries. */
final class MomentState(sample: Boolean, stddev: Boolean) extends AggState {
  private var n: Long = 0L
  private var mean: Double = 0.0
  private var m2: Double = 0.0
  private def childOf(agg: AggExpr): Expr = agg match {
    case s: AggExprStddev => s.child
    case v: AggExprVariance => v.child
    case _ => throw new IllegalStateException
  }
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val v = childOf(agg).eval(b, r)
    if (v != null) {
      val x = v.asInstanceOf[Number].doubleValue
      n += 1
      val delta = x - mean
      mean += delta / n
      m2 += delta * (x - mean)
    }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[MomentState]
    if (that.n == 0L) return
    if (n == 0L) {
      n = that.n; mean = that.mean; m2 = that.m2
    } else {
      val newN = n + that.n
      val delta = that.mean - mean
      m2 = m2 + that.m2 + delta * delta * n.toDouble * that.n.toDouble / newN.toDouble
      mean = mean + delta * that.n.toDouble / newN.toDouble
      n = newN
    }
  }
  def finish(): Any = {
    val varianceOpt: Option[Double] =
      if (sample) { if (n < 2L) None else Some(m2 / (n - 1).toDouble) }
      else { if (n < 1L) None else Some(m2 / n.toDouble) }
    varianceOpt match {
      case None => null
      case Some(v) => if (stddev) math.sqrt(v) else v
    }
  }
}

/** Bivariate covariance via the parallel Welford / Pébay update. Pairs are
  * skipped when either side is NULL. */
final class CovarState(sample: Boolean) extends AggState {
  protected var n: Long = 0L
  protected var meanX: Double = 0.0
  protected var meanY: Double = 0.0
  protected var c: Double = 0.0
  protected def xOf(agg: AggExpr): Expr = agg.asInstanceOf[AggExprCovar].x
  protected def yOf(agg: AggExpr): Expr = agg.asInstanceOf[AggExprCovar].y
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val vx = xOf(agg).eval(b, r)
    val vy = yOf(agg).eval(b, r)
    if (vx != null && vy != null) {
      val x = vx.asInstanceOf[Number].doubleValue
      val y = vy.asInstanceOf[Number].doubleValue
      n += 1
      val dx = x - meanX
      meanX += dx / n
      val dy = y - meanY
      meanY += dy / n
      c += dx * (y - meanY)
    }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[CovarState]
    if (that.n == 0L) return
    if (n == 0L) {
      n = that.n; meanX = that.meanX; meanY = that.meanY; c = that.c
    } else {
      val newN = n + that.n
      val dx = that.meanX - meanX
      val dy = that.meanY - meanY
      c = c + that.c + dx * dy * n.toDouble * that.n.toDouble / newN.toDouble
      meanX = meanX + dx * that.n.toDouble / newN.toDouble
      meanY = meanY + dy * that.n.toDouble / newN.toDouble
      n = newN
    }
  }
  def finish(): Any =
    if (sample) { if (n < 2L) null else c / (n - 1).toDouble }
    else { if (n < 1L) null else c / n.toDouble }
}

/** Pearson correlation: tracks the same partial sums as covariance plus
  * second moments for x and y to normalize. */
final class CorrState extends AggState {
  private var n: Long = 0L
  private var meanX: Double = 0.0
  private var meanY: Double = 0.0
  private var m2x: Double = 0.0
  private var m2y: Double = 0.0
  private var c: Double = 0.0
  def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = {
    val corr = agg.asInstanceOf[AggExprCorr]
    val vx = corr.x.eval(b, r)
    val vy = corr.y.eval(b, r)
    if (vx != null && vy != null) {
      val x = vx.asInstanceOf[Number].doubleValue
      val y = vy.asInstanceOf[Number].doubleValue
      n += 1
      val dx = x - meanX
      val dy = y - meanY
      meanX += dx / n
      meanY += dy / n
      val dx2 = x - meanX
      val dy2 = y - meanY
      m2x += dx * dx2
      m2y += dy * dy2
      c += dx * dy2
    }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[CorrState]
    if (that.n == 0L) return
    if (n == 0L) {
      n = that.n; meanX = that.meanX; meanY = that.meanY
      m2x = that.m2x; m2y = that.m2y; c = that.c
    } else {
      val newN = n + that.n
      val dx = that.meanX - meanX
      val dy = that.meanY - meanY
      val na = n.toDouble; val nb = that.n.toDouble; val nc = newN.toDouble
      m2x = m2x + that.m2x + dx * dx * na * nb / nc
      m2y = m2y + that.m2y + dy * dy * na * nb / nc
      c   = c   + that.c   + dx * dy * na * nb / nc
      meanX = meanX + dx * nb / nc
      meanY = meanY + dy * nb / nc
      n = newN
    }
  }
  def finish(): Any = {
    if (n < 2L) null
    else {
      val denom = math.sqrt(m2x * m2y)
      if (denom == 0.0) null else c / denom
    }
  }
}
