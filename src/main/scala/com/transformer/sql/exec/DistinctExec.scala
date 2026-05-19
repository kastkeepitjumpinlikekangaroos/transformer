package com.transformer.sql.exec

import com.transformer.core._

import java.util
import java.util.concurrent.Callable
import scala.collection.mutable

/** SELECT DISTINCT. Builds a HashSet of full-row tuples. Single global partition. */
final case class DistinctExec(child: PhysicalPlan) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val tasks: Seq[Callable[util.LinkedHashSet[Seq[Any]]]] =
      (0 until child.numPartitions).map { p =>
        new Callable[util.LinkedHashSet[Seq[Any]]] {
          def call(): util.LinkedHashSet[Seq[Any]] = collect(p)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val merged = new util.LinkedHashSet[Seq[Any]]()
    partials.foreach(merged.addAll)
    emit(merged)
  }

  private def collect(p: Int): util.LinkedHashSet[Seq[Any]] = {
    val set = new util.LinkedHashSet[Seq[Any]]()
    val it = child.execute(p)
    val ncols = child.outputSchema.length
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val key = mutable.ArrayBuffer.empty[Any]
        var c = 0
        while (c < ncols) {
          key += (if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r))
          c += 1
        }
        set.add(key.toSeq)
        r += 1
      }
    }
    set
  }

  private def emit(set: util.LinkedHashSet[Seq[Any]]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val iter = set.iterator()
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = iter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(schema, capacity)
        var r = 0
        while (r < capacity && iter.hasNext) {
          val row = iter.next()
          var c = 0
          while (c < schema.length) {
            val v = row(c)
            if (v == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, v)
            c += 1
          }
          r += 1
        }
        out.setNumRows(r)
        out
      }
    }
  }
}
