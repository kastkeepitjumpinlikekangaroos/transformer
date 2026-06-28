package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._
import com.transformer.write.parquet.ParquetWriter

import java.nio.file.Path
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
  *
  * ## Spill
  *
  * When `opts.spillEnabled`, each per-partition sort watches its accumulator
  * size and flushes "runs" to disk parquet files when the byte threshold is
  * crossed. The merge step then heap-merges across **all runs from all
  * partitions** simultaneously, including the final in-memory tail of each
  * partition. Each on-disk run reads streaming through [[ParquetReader]];
  * memory bound during merge is one in-flight [[ColumnarBatch]] per active
  * cursor.
  *
  * Spill is bit-equal with the non-spill path: both produce the same global
  * sort, the same row order under ties, and the same output schema. The
  * parity guarantee is enforced by [[SortExecSpillTest]].
  */
final case class SortExec(
    child: PhysicalPlan,
    keys: Seq[(Expr, Boolean)],
    opts: ExecutionOptions = ExecutionOptions.Default,
    metricsNode: MetricsNode = null
) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  /** Whether this sort may spill at runtime. Computed once at planning time
    * from `opts`; per-partition tasks consult [[spillThresholdBytes]] for the
    * actual flush threshold. */
  private val mayspill: Boolean = opts.spillEnabled

  /** Effective per-partition threshold in bytes. Resolved against the
    * runtime heap / parallelism via [[Spill.effectiveThresholdBytes]]. */
  private def spillThresholdBytes: Long =
    Spill.effectiveThresholdBytes(opts, Scheduler.parallelism)

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    // One spill subdirectory per execute() call. Cursors close their own
    // parquet handles; the directory is wiped when the iterator finishes
    // (or via JVM shutdown hook if the consumer abandons it).
    val spillDir: Option[OperatorSpillDir] =
      if (mayspill) Some(Spill.openOperatorDir("sort")) else None
    val threshold = if (mayspill) spillThresholdBytes else Long.MaxValue

    val tasks: Seq[Callable[SortExec.PartitionResult]] =
      (0 until child.numPartitions).map { p =>
        new Callable[SortExec.PartitionResult] {
          def call(): SortExec.PartitionResult = sortPartition(p, threshold, spillDir)
        }
      }
    val partials: IndexedSeq[SortExec.PartitionResult] = Scheduler.submitAndAwaitAll(tasks)
    mergeEmit(partials, spillDir)
  }

  /** One sort partition. Returns the in-memory tail plus any on-disk run
    * paths produced by spilling. When `threshold` is `Long.MaxValue` (the
    * non-spill path) we never spill and the on-disk run list stays empty. */
  private def sortPartition(
      p: Int,
      threshold: Long,
      spillDir: Option[OperatorSpillDir]): SortExec.PartitionResult = {
    val schema = child.outputSchema
    val ncols = schema.length
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    val tracksBytes = spillDir.isDefined
    var bufBytes: Long = 0L
    var inputRowsHere: Long = 0L
    val runs = mutable.ArrayBuffer.empty[Path]
    val it = child.execute(p)
    while (it.hasNext) {
      val b = it.next()
      // Only walk the batch counting bytes when spill is actually possible
      // — `Spill.estimateBytes` is O(rows × cols) for variable-width
      // columns, which is real per-batch work we don't want to pay on the
      // common (spill-disabled) path.
      if (tracksBytes) bufBytes += Spill.estimateBytes(b)
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](ncols)
        var c = 0
        while (c < arr.length) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
      inputRowsHere += b.numRows.toLong
      if (bufBytes >= threshold && buf.length > 0 && spillDir.isDefined) {
        flushRun(buf, runs, spillDir.get, schema)
        if (metricsNode != null) {
          val sb = runs.last.toFile.length()
          metricsNode.counters(SortExec.IdxRunsWritten).increment()
          metricsNode.counters(SortExec.IdxBytesSpilled).add(sb)
        }
        bufBytes = 0L
        if (runs.length > opts.spillMaxRuns)
          throw new RuntimeException(
            s"SortExec produced ${runs.length} spill runs in partition $p, exceeding " +
            s"spill_max_runs=${opts.spillMaxRuns}. Either raise the threshold or " +
            "investigate skew / unbounded input.")
      }
    }
    val sorted = sortBuffer(buf)
    if (metricsNode != null) metricsNode.counters(SortExec.IdxInputRows).add(inputRowsHere)
    SortExec.PartitionResult(inMemory = sorted, diskRuns = runs.toArray)
  }

  /** Sort the in-memory tail and stream it to a fresh parquet file inside
    * `spillDir`. Clears the buffer. */
  private def flushRun(
      buf: mutable.ArrayBuffer[Array[Any]],
      runs: mutable.ArrayBuffer[Path],
      spillDir: OperatorSpillDir,
      schema: Schema): Unit = {
    val sorted = sortBuffer(buf)
    buf.clear()
    val file = spillDir.newSpillFile(".parquet")
    val batches = batchIterator(sorted, schema)
    // Runs are read back by index (see DiskRunCursor); positional names keep a
    // duplicate-named child schema round-trippable. See Spill.positionalSchema.
    ParquetWriter.writeAll(file, Spill.positionalSchema(schema), batches)
    runs += file
  }

  /** Convert sorted rows into a batch iterator suitable for parquet write.
    * Reuses the existing [[emit]] logic shape but inline so we don't allocate
    * the outer Iterator wrapper twice. */
  private def batchIterator(sorted: Array[Array[Any]], schema: Schema): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
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

  private def sortBuffer(buf: mutable.ArrayBuffer[Array[Any]]): Array[Array[Any]] = {
    val ord = rowOrdering
    val sorted = buf.toArray
    java.util.Arrays.sort(sorted.asInstanceOf[Array[Object]], ord.asInstanceOf[java.util.Comparator[Object]])
    sorted
  }

  /** K-way merge over partitions, each contributing zero or more on-disk
    * runs plus an in-memory tail. The heap stores [[SortCursor]] indices;
    * the comparator delegates to [[rowOrdering]] on each cursor's current
    * row. Disk cursors stream parquet batches lazily — memory bound is
    * `O(K)` cursors × one in-flight ColumnarBatch each.
    *
    * Single non-empty cursor bypasses the heap entirely; small inputs fall
    * back to the concat+resort path below [[SortExec.SmallNThreshold]]. */
  private def mergeEmit(
      partials: IndexedSeq[SortExec.PartitionResult],
      spillDir: Option[OperatorSpillDir]): Iterator[ColumnarBatch] = {
    val schema = outputSchema
    val cursorList = mutable.ArrayBuffer.empty[SortCursor]
    val nKeys = keys.length
    var total: Long = 0L
    partials.foreach { res =>
      res.diskRuns.foreach { file =>
        val cur = new DiskRunCursor(file, schema)
        if (cur.advance()) cursorList += cur
        // total unknown for disk runs — we'll just bypass the SmallN path
      }
      if (res.inMemory.length > 0) {
        val cur = new InMemoryCursor(res.inMemory)
        if (cur.advance()) cursorList += cur
        total += res.inMemory.length.toLong
      }
    }
    val numCursors = cursorList.length
    if (numCursors == 0) {
      spillDir.foreach(_.close())
      return Iterator.empty
    }
    val anyDiskRuns = partials.exists(_.diskRuns.nonEmpty)
    // Small-N path is in-memory only — disk cursors don't expose a row count
    // up front, so when any partition spilled we skip the optimization.
    if (!anyDiskRuns && numCursors == 1) {
      val res = closeCursorAndUseUnderlying(cursorList(0))
      if (metricsNode != null) {
        metricsNode.counters(SortExec.IdxPath).add(SortExec.PathSmallN)
        metricsNode.counters(SortExec.IdxOutputRows).add(res.length.toLong)
      }
      val it = emit(res)
      return wrapWithSpillCleanup(it, spillDir)
    }
    if (!anyDiskRuns && total < SortExec.SmallNThreshold) {
      val arrs = cursorList.iterator.map(closeCursorAndUseUnderlying).toIndexedSeq
      if (metricsNode != null) {
        metricsNode.counters(SortExec.IdxPath).add(SortExec.PathSmallN)
        metricsNode.counters(SortExec.IdxOutputRows).add(total)
      }
      val it = smallNSortAndEmit(arrs, total.toInt)
      return wrapWithSpillCleanup(it, spillDir)
    }

    val ord = rowOrdering
    val cursors = cursorList.toArray
    if (metricsNode != null) {
      metricsNode.counters(SortExec.IdxPath).add(SortExec.PathKWay)
      metricsNode.counters(SortExec.IdxMergeRunsActive).add(numCursors.toLong)
    }
    val heapCmp = new java.util.Comparator[java.lang.Integer] {
      def compare(a: java.lang.Integer, b: java.lang.Integer): Int = {
        if (metricsNode != null) metricsNode.counters(SortExec.IdxComparatorCalls).increment()
        ord.compare(cursors(a.intValue).current(), cursors(b.intValue).current())
      }
    }
    val heap = new java.util.PriorityQueue[java.lang.Integer](numCursors, heapCmp)
    var i = 0
    while (i < numCursors) { heap.add(java.lang.Integer.valueOf(i)); i += 1 }

    val tMergeStart = if (metricsNode != null) System.nanoTime() else 0L
    val capacity = ColumnarBatch.DefaultCapacity
    val mergedIt = new Iterator[ColumnarBatch] {
      private var done = false
      private var outputRowsLocal: Long = 0L
      def hasNext: Boolean = !done && !heap.isEmpty
      def next(): ColumnarBatch = {
        if (done || heap.isEmpty)
          throw new NoSuchElementException("SortExec merge exhausted")
        val out = new ColumnarBatch(schema, capacity)
        var r = 0
        while (r < capacity && !heap.isEmpty) {
          val cIdx = heap.poll().intValue
          val cur = cursors(cIdx)
          val row = cur.current()
          var c = 0
          while (c < schema.length) {
            if (row(c) == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, row(c))
            c += 1
          }
          val tDec = if (metricsNode != null && cur.isInstanceOf[DiskRunCursor]) System.nanoTime() else 0L
          val advanced = cur.advance()
          if (metricsNode != null && tDec != 0L)
            metricsNode.counters(SortExec.IdxRunDecodeNanos).add(System.nanoTime() - tDec)
          if (advanced) heap.add(java.lang.Integer.valueOf(cIdx))
          else cur.close()
          r += 1
        }
        out.setNumRows(r)
        outputRowsLocal += r.toLong
        if (heap.isEmpty) {
          done = true
          if (metricsNode != null) {
            metricsNode.counters(SortExec.IdxMergeNanos).add(System.nanoTime() - tMergeStart)
            metricsNode.counters(SortExec.IdxOutputRows).add(outputRowsLocal)
          }
        }
        out
      }
    }
    wrapWithSpillCleanup(mergedIt, spillDir)
  }

  /** Wrap `inner` so the spill subdir is wiped when the consumer drains the
    * iterator. The shutdown hook in [[Spill]] catches the abandoned case. */
  private def wrapWithSpillCleanup(
      inner: Iterator[ColumnarBatch],
      spillDir: Option[OperatorSpillDir]): Iterator[ColumnarBatch] = spillDir match {
    case None => inner
    case Some(d) =>
      new Iterator[ColumnarBatch] {
        def hasNext: Boolean = {
          val h = inner.hasNext
          if (!h) d.close()
          h
        }
        def next(): ColumnarBatch = inner.next()
      }
  }

  /** Extract the underlying [[Array[Array[Any]]]] from an in-memory cursor
    * without consuming it through the heap. Cheap escape hatch for the
    * single-cursor and small-N paths. Disk cursors should never reach here. */
  private def closeCursorAndUseUnderlying(c: SortCursor): Array[Array[Any]] = c match {
    case mc: InMemoryCursor => mc.allRows
    case _ => throw new IllegalStateException(
      "small-N / single-cursor path assumes in-memory cursors only")
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

  /** Small-N fallback. Concatenate the per-cursor sorted arrays and call
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

  // ---- Counter indices ------------------------------------------------------
  // In-memory path counters.
  final val IdxComparatorCalls: Int = 0
  final val IdxInputRows: Int       = 1
  final val IdxOutputRows: Int      = 2
  final val IdxPath: Int            = 3
  // External-merge counters.
  final val IdxRunsWritten: Int     = 4
  final val IdxBytesSpilled: Int    = 5
  final val IdxMergeRunsActive: Int = 6
  final val IdxMergeNanos: Int      = 7
  final val IdxRunDecodeNanos: Int  = 8

  /** Path codes written into the `path` counter. */
  final val PathSmallN: Long = 0L
  final val PathKWay: Long   = 1L

  /** Name array parallel to the Idx* constants above. Asserted length =
    * `highest Idx<Name> + 1` in `operator_counters_test.scala`. */
  val IdxCounterNames: Array[String] = Array(
    "comparatorCalls",
    "inputRows",
    "outputRows",
    "path",
    "runsWritten",
    "bytesSpilled",
    "mergeRunsActive",
    "mergeNanos",
    "runDecodeNanos")

  /** Result of `sortPartition`: the in-memory sorted tail plus the paths
    * of any on-disk runs flushed mid-stream. */
  private[exec] final case class PartitionResult(
      inMemory: Array[Array[Any]],
      diskRuns: Array[Path])
}

/** One cursor over a sorted stream of rows. Consumed by the merge heap.
  * Implementations:
  *   - [[InMemoryCursor]]: walks a pre-sorted Array[Array[Any]] in place.
  *   - [[DiskRunCursor]]: streams a parquet spill file, materializing one
  *     boxed-row view per `current()` call.
  *
  * Cursors must be advanced once at construction-time before the first
  * `current()` — both implementations follow this convention; the calling
  * heap reads `advance()` first and only adds the cursor to the heap when
  * it returns `true`.
  */
private[exec] sealed trait SortCursor {
  /** Boxed row currently at the cursor head. */
  def current(): Array[Any]
  /** Advance past `current()`. Returns true if more rows remain. */
  def advance(): Boolean
  /** Release resources (close parquet readers). Called on exhaustion and
    * on early-abort paths. Idempotent. */
  def close(): Unit
}

private[exec] final class InMemoryCursor(rows: Array[Array[Any]]) extends SortCursor {
  private var idx = -1
  // `allRows` is consumed only by the small-N / single-cursor escape hatch
  // before any `advance()` call.
  def allRows: Array[Array[Any]] = rows
  def current(): Array[Any] = rows(idx)
  def advance(): Boolean = {
    idx += 1
    idx < rows.length
  }
  def close(): Unit = ()
}

/** Streams a parquet spill file as a sorted row source. The parquet file's
  * on-disk row order is the sort order (writers emit batches in sorted
  * order; parquet preserves row order within a row group). */
private[exec] final class DiskRunCursor(file: Path, schema: Schema) extends SortCursor {
  private val reader = ParquetReader.fromPath(file.toString)
  private val partIt: Iterator[Int] = (0 until reader.numPartitions).iterator
  private var batchIt: Iterator[ColumnarBatch] = Iterator.empty
  private var currentBatch: ColumnarBatch = null
  private var rowIdx: Int = -1
  private val ncols: Int = schema.length
  // Reusable Array[Any] to materialize the row — the comparator reads
  // `current()` repeatedly across heap operations, so we materialize once
  // per advance() rather than per current() call.
  private var rowView: Array[Any] = new Array[Any](ncols)
  @volatile private var closed: Boolean = false

  def current(): Array[Any] = rowView

  def advance(): Boolean = {
    if (closed) return false
    rowIdx += 1
    while (currentBatch == null || rowIdx >= currentBatch.numRows) {
      if (!batchIt.hasNext) {
        if (!partIt.hasNext) { close(); return false }
        batchIt = reader.readPartition(partIt.next())
      } else {
        currentBatch = batchIt.next()
        rowIdx = 0
      }
      if (currentBatch != null && rowIdx >= currentBatch.numRows) {
        // batch was empty for some reason; loop to fetch next
        currentBatch = null
        rowIdx = -1
      }
    }
    // Materialize the current row into rowView.
    var c = 0
    while (c < ncols) {
      val col = currentBatch.column(c)
      rowView(c) = if (col.isNull(rowIdx)) null else col.getBoxed(rowIdx)
      c += 1
    }
    true
  }

  def close(): Unit = {
    if (closed) return
    closed = true
    currentBatch = null
    rowView = null
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
