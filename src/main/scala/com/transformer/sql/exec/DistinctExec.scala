package com.transformer.sql.exec

import com.transformer.core._

import java.util
import java.util.concurrent.Callable

/** SELECT DISTINCT. Builds a HashSet of full-row keys. Single global partition.
  *
  * Keys are encoded via [[KeyCodec]] (packed `byte[]` for fixed-width-only
  * schemas; cached-hash `Array[AnyRef]` otherwise) — avoids the
  * `Seq[Any]` allocation + per-element dynamic-dispatch hashCode/equals walk
  * the original implementation paid on every row.
  */
final case class DistinctExec(child: PhysicalPlan) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  private val ncols: Int = child.outputSchema.length
  private val codec: KeyCodec = KeyCodec.forColumns(
    Array.tabulate(ncols)(identity),
    child.outputSchema.fields.iterator.map(_.dataType).toArray
  )

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
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
