package com.transformer.sql.exec

import com.transformer.core._

import java.util
import java.util.concurrent.Callable

/** SELECT DISTINCT. Two execution modes share this one operator, selected
  * by whether `child` is an [[ExchangeExec]]:
  *
  *   - **Per-shard** (child is `ExchangeExec`): the exchange has hash-
  *     partitioned by every output column, so duplicates of any row land
  *     together. `execute(p)` dedupes one shard's batches into a local
  *     HashSet and emits. `numPartitions = K`; downstream stays parallel.
  *   - **Collapsing** (child is anything else): no exchange. Fan out
  *     across the child's partitions through [[Scheduler]], union the
  *     per-partition HashSets, emit as one output partition. Reached when
  *     [[PhysicalPlanner]] decides the input is below
  *     [[com.transformer.sql.plan.LogicalPlanCardinality.MinShardableSize]]
  *     — the exchange's scatter overhead would dominate.
  *
  * Keys are encoded via [[KeyCodec]] (packed `byte[]` for fixed-width-only
  * schemas; cached-hash `Array[AnyRef]` otherwise) — avoids the
  * `Seq[Any]` allocation + per-element dynamic-dispatch hashCode/equals walk
  * the original implementation paid on every row.
  */
final case class DistinctExec(child: PhysicalPlan) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema

  /** True when the planner inserted `ExchangeExec` above this operator.
    * Same gate as [[HashAggregateExec.isSharded]] / [[HashJoinExec.isSharded]]
    * — runtime detection via the child's concrete type so direct construction
    * over a non-exchange child still works (collapsing mode). */
  private val isSharded: Boolean = child.isInstanceOf[ExchangeExec]

  def numPartitions: Int = if (isSharded) child.numPartitions else 1

  private val ncols: Int = child.outputSchema.length
  private val codec: KeyCodec = KeyCodec.forColumns(
    Array.tabulate(ncols)(identity),
    child.outputSchema.fields.iterator.map(_.dataType).toArray
  )

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (isSharded) executePerShard(partition)
    else executeCollapsing(partition)
  }

  private def executePerShard(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numPartitions,
      s"partition $partition out of range [0,$numPartitions)")
    emit(collect(partition))
  }

  private def executeCollapsing(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0,
      s"collapsing distinct has only one partition; got partition=$partition")
    val tasks: Seq[Callable[util.LinkedHashSet[AnyRef]]] =
      (0 until child.numPartitions).map { p =>
        new Callable[util.LinkedHashSet[AnyRef]] {
          def call(): util.LinkedHashSet[AnyRef] = collect(p)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val merged = new util.LinkedHashSet[AnyRef]()
    partials.foreach(merged.addAll)
    emit(merged)
  }

  private def collect(p: Int): util.LinkedHashSet[AnyRef] = {
    val set = new util.LinkedHashSet[AnyRef]()
    val it = child.execute(p)
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        set.add(codec.encodeFromBatch(b, r))
        r += 1
      }
    }
    set
  }

  private def emit(set: util.LinkedHashSet[AnyRef]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val iter = set.iterator()
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = iter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(schema, capacity)
        var r = 0
        while (r < capacity && iter.hasNext) {
          codec.decode(iter.next(), out, 0, r)
          r += 1
        }
        out.setNumRows(r)
        out
      }
    }
  }
}
