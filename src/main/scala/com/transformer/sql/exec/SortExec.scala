package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.collection.mutable

/** Global sort: each partition sorts locally in parallel, then a k-way merge
  * emits the combined sorted result.
  */
final case class SortExec(child: PhysicalPlan, keys: Seq[(Expr, Boolean)]) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val nthreads = math.max(1, math.min(child.numPartitions, Runtime.getRuntime.availableProcessors))
    val pool = Executors.newFixedThreadPool(nthreads)
    val partials: Seq[mutable.ArrayBuffer[Array[Any]]] =
      try {
        val futures = (0 until child.numPartitions).map { p =>
          pool.submit(new Callable[mutable.ArrayBuffer[Array[Any]]] {
            def call(): mutable.ArrayBuffer[Array[Any]] = sortPartition(p)
          })
        }
        futures.map(_.get())
      } finally {
        pool.shutdown()
        pool.awaitTermination(5, TimeUnit.MINUTES)
      }

    // Concatenate all sorted partitions, then sort globally — keys are already
    // computed inline so we sort with a comparator on the data rows.
    val all = mutable.ArrayBuffer.empty[Array[Any]]
    partials.foreach(p => all ++= p)
    val ord = rowOrdering
    val sorted = all.toArray
    java.util.Arrays.sort(sorted.asInstanceOf[Array[Object]], ord.asInstanceOf[java.util.Comparator[Object]])
    emit(sorted)
  }

  private def sortPartition(p: Int): mutable.ArrayBuffer[Array[Any]] = {
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    val it = child.execute(p)
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](child.outputSchema.length)
        var c = 0
        while (c < arr.length) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
    }
    val ord = rowOrdering
    val sorted = buf.toArray
    java.util.Arrays.sort(sorted.asInstanceOf[Array[Object]], ord.asInstanceOf[java.util.Comparator[Object]])
    mutable.ArrayBuffer(sorted: _*)
  }

  private def rowOrdering: java.util.Comparator[Array[Any]] = {
    val sortKeys = keys.toArray
    new java.util.Comparator[Array[Any]] {
      def compare(a: Array[Any], b: Array[Any]): Int = {
        val rowA = RowView(child.outputSchema, a)
        val rowB = RowView(child.outputSchema, b)
        var i = 0
        while (i < sortKeys.length) {
          val (expr, asc) = sortKeys(i)
          val va = expr.eval(rowA, 0)
          val vb = expr.eval(rowB, 0)
          val c = (va, vb) match {
            case (null, null) => 0
            case (null, _) => if (asc) -1 else 1
            case (_, null) => if (asc) 1 else -1
            case _ => if (asc) Ops.cmp(va, vb) else Ops.cmp(vb, va)
          }
          if (c != 0) return c
          i += 1
        }
        0
      }
    }
  }

  private def emit(sorted: Array[Array[Any]]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val totalRows = sorted.length
    var produced = 0
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = produced < totalRows
      def next(): ColumnarBatch = {
        val take = math.min(capacity, totalRows - produced)
        val out = new ColumnarBatch(schema, take max 1)
        var r = 0
        while (r < take) {
          val row = sorted(produced + r)
          var c = 0
          while (c < schema.length) {
            if (row(c) == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, row(c))
            c += 1
          }
          r += 1
        }
        out.setNumRows(take)
        produced += take
        out
      }
    }
  }
}

/** A 1-row [[ColumnarBatch]] populated from a materialized row array. Used by
  * sort comparators, hash-join probe rows, and the residual-predicate check so
  * the same [[Expr.eval]] interface works against post-materialization rows.
  */
private[exec] object RowView {
  def apply(schema: Schema, values: Array[Any]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, 1)
    var c = 0
    while (c < schema.length) {
      if (values(c) == null) b.column(c).setNull(0) else b.column(c).setBoxed(0, values(c))
      c += 1
    }
    b.setNumRows(1)
    b
  }
}
