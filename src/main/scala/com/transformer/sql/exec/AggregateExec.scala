package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Hash-based GROUP BY. Each input partition builds a partial map of
  * `groupKey -> AggStates`; the merge step combines partials into the final result.
  *
  * Output schema = group keys ++ aggregate results (in the order given).
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

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val nthreads = math.max(1, math.min(child.numPartitions, Runtime.getRuntime.availableProcessors))
    val pool = Executors.newFixedThreadPool(nthreads)
    val partials: Seq[mutable.LinkedHashMap[Seq[Any], Array[AggState]]] =
      try {
        val futures = (0 until child.numPartitions).map { p =>
          pool.submit(new Callable[mutable.LinkedHashMap[Seq[Any], Array[AggState]]] {
            def call(): mutable.LinkedHashMap[Seq[Any], Array[AggState]] =
              partialAggregate(p)
          })
        }
        futures.map(_.get())
      } finally {
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.MINUTES)
      }

    val merged = partials.reduceOption(merge).getOrElse(mutable.LinkedHashMap.empty)
    // For aggregations with no GROUP BY: SQL requires one row of aggregate output
    // even when input is empty (e.g., COUNT(*) over empty source = 0).
    if (groupKeys.isEmpty && merged.isEmpty) {
      val seed = Array.tabulate(aggregates.size)(i => AggState.init(aggregates(i)._1))
      merged += (Seq.empty[Any] -> seed)
    }
    emit(merged)
  }

  private def partialAggregate(p: Int): mutable.LinkedHashMap[Seq[Any], Array[AggState]] = {
    val map = mutable.LinkedHashMap.empty[Seq[Any], Array[AggState]]
    val it = child.execute(p)
    while (it.hasNext) {
      val batch = it.next()
      val nrows = batch.numRows
      var r = 0
      while (r < nrows) {
        val key: Seq[Any] = groupKeys.map { case (e, _) => e.eval(batch, r) }
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
      a: mutable.LinkedHashMap[Seq[Any], Array[AggState]],
      b: mutable.LinkedHashMap[Seq[Any], Array[AggState]]): mutable.LinkedHashMap[Seq[Any], Array[AggState]] = {
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

  private def emit(map: mutable.LinkedHashMap[Seq[Any], Array[AggState]]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val iter = map.iterator
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = iter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(outputSchema, capacity)
        var row = 0
        while (row < capacity && iter.hasNext) {
          val (key, states) = iter.next()
          var c = 0
          while (c < groupKeys.size) {
            val v = key(c)
            if (v == null) out.column(c).setNull(row) else out.column(c).setBoxed(row, v)
            c += 1
          }
          var a = 0
          while (a < states.length) {
            val v = states(a).finish()
            val idx = groupKeys.size + a
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
