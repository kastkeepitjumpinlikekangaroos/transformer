package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util.concurrent.Callable
import scala.collection.mutable

/** Global sort: each partition sorts locally in parallel, then a K-way heap
  * merge over the already-sorted partials emits the combined sorted output.
  *
  * For K partials of total size N the merge avoids the `O(N log N)` global
  * resort the previous implementation performed over the concatenated array
  * and skips the 1M-element merged buffer that resort needed — the heap holds
  * just K cursor entries and output streams as the heap drains. Downstream
  * operators see batches without waiting for the full merge to materialize.
  *
  * Below `SmallNThreshold` total rows the merge falls back to a simple
  * concat + `Arrays.sort` because heap startup + per-batch allocation
  * dominates wall time on tiny inputs.
  */
final case class SortExec(child: PhysicalPlan, keys: Seq[(Expr, Boolean)]) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val tasks: Seq[Callable[Array[Array[Any]]]] =
      (0 until child.numPartitions).map { p =>
        new Callable[Array[Array[Any]]] {
          def call(): Array[Array[Any]] = sortPartition(p)
        }
      }
    val partials: IndexedSeq[Array[Array[Any]]] = Scheduler.submitAndAwaitAll(tasks)
    mergeEmit(partials)
  }

  private def sortPartition(p: Int): Array[Array[Any]] = {
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
    sorted
  }

  /** K-way merge over the already-sorted partials. The heap stores partition
    * indices; the comparator delegates to [[rowOrdering]] on the current head
    * row of each partition (`partials(p)(cursors(p))`). After polling, the
    * cursor advances and is re-pushed if rows remain. Output is built batch
    * by batch directly into [[ColumnarBatch]]es so the merged array is never
    * fully materialized.
    *
    * Single-partition input bypasses the heap entirely; small inputs fall
    * back to the concat+resort path below [[SortExec.SmallNThreshold]].
    */
  private def mergeEmit(partials: IndexedSeq[Array[Array[Any]]]): Iterator[ColumnarBatch] = {
    val numPartials = partials.length
    var nonEmpty = 0
    var firstNonEmpty = -1
    var total = 0
    var i = 0
    while (i < numPartials) {
      val n = partials(i).length
      if (n > 0) {
        if (firstNonEmpty < 0) firstNonEmpty = i
        nonEmpty += 1
        total += n
      }
      i += 1
    }
    if (nonEmpty == 0) return Iterator.empty
    if (nonEmpty == 1) return emit(partials(firstNonEmpty))
    if (total < SortExec.SmallNThreshold) return smallNSortAndEmit(partials, total)

    val ord = rowOrdering
    val cursors = new Array[Int](numPartials)
    val heapCmp = new java.util.Comparator[java.lang.Integer] {
      def compare(a: java.lang.Integer, b: java.lang.Integer): Int = {
        val pa = a.intValue
        val pb = b.intValue
        ord.compare(partials(pa)(cursors(pa)), partials(pb)(cursors(pb)))
      }
    }
    val heap = new java.util.PriorityQueue[java.lang.Integer](nonEmpty, heapCmp)
    i = 0
    while (i < numPartials) {
      if (partials(i).length > 0) heap.add(java.lang.Integer.valueOf(i))
      i += 1
    }

    val schema = outputSchema
    val cap = ColumnarBatch.DefaultCapacity
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = !heap.isEmpty
      def next(): ColumnarBatch = {
        if (heap.isEmpty) throw new NoSuchElementException("SortExec merge exhausted")
        val out = new ColumnarBatch(schema, cap)
        var r = 0
        while (r < cap && !heap.isEmpty) {
          val p = heap.poll().intValue
          val row = partials(p)(cursors(p))
          var c = 0
          while (c < schema.length) {
            if (row(c) == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, row(c))
            c += 1
          }
          cursors(p) += 1
          if (cursors(p) < partials(p).length) heap.add(java.lang.Integer.valueOf(p))
          r += 1
        }
        out.setNumRows(r)
        out
      }
    }
  }

  /** Small-N fallback. Concatenate the per-partition sorted arrays and call
    * `Arrays.sort` — heap startup + per-batch allocation outweigh the merge
    * savings below a few thousand rows. */
  private def smallNSortAndEmit(
      partials: IndexedSeq[Array[Array[Any]]], total: Int): Iterator[ColumnarBatch] = {
    val merged = new Array[Array[Any]](total)
    var off = 0
    var i = 0
    while (i < partials.length) {
      val p = partials(i)
      System.arraycopy(p, 0, merged, off, p.length)
      off += p.length
      i += 1
    }
    val ord = rowOrdering
    java.util.Arrays.sort(merged.asInstanceOf[Array[Object]], ord.asInstanceOf[java.util.Comparator[Object]])
    emit(merged)
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

object SortExec {
  /** Rows below this count merge via `Arrays.sort` over a single concatenated
    * buffer instead of the heap merge. Heap startup + per-batch allocation
    * outweighs the merge savings on tiny inputs. */
  private[exec] val SmallNThreshold: Int = 4096
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
