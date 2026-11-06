package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._
import com.transformer.write.parquet.ParquetWriter

import java.nio.file.Path
import java.util.concurrent.Callable
import scala.collection.mutable

/** Global sort: each partition sorts locally in parallel, then a merge over
  * the sorted runs emits the combined sorted output.
  *
  * A partition copies the rows it reads into a [[SortRun]]: typed chunks of
  * [[ColumnarBatch.DefaultCapacity]] rows, its columns and then one column a
  * key that is no plain column reference, evaluated once a batch. It sorts
  * the `Int`s that name those rows with [[IndexSort]], comparing keys through
  * each key's [[KeyOrder]], so no row is boxed and no comparison evaluates
  * anything. The merge is a [[LoserTree]] over every run, a partition's runs
  * in the order written, partition by partition, its winner the head row that
  * sorts first, a tie going to the earlier run. So every path returns the
  * stable sort of the input read partition by partition: rows that tie on
  * every key come in the order they were read, with spill and without it.
  *
  * ## Spill
  *
  * When `opts.spillEnabled`, each per-partition sort counts what its run
  * holds and flushes the run, sorted, to a parquet file when the byte
  * threshold is crossed. A run's file holds its columns and its computed
  * keys, so a key is read once, whatever reads it (`RAND()`). The merge reads
  * **all runs from all partitions** at once, including the final in-memory
  * tail of each partition. Each on-disk run reads streaming through
  * [[ParquetReader]]; memory bound during merge is one in-flight
  * [[ColumnarBatch]] per active cursor.
  */
final case class SortExec(
    child: PhysicalPlan,
    keys: Seq[SortKey],
    opts: ExecutionOptions = ExecutionOptions.Default,
    metricsNode: MetricsNode = null
) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  /** Whether this sort may spill at runtime. Computed once at planning time
    * from `opts`; per-partition tasks consult [[spillThresholdBytes]] for the
    * actual flush threshold. */
  private val maySpill: Boolean = opts.spillEnabled

  /** Effective per-partition threshold in bytes. Resolved against the
    * runtime heap / parallelism via [[Spill.effectiveThresholdBytes]]. */
  private def spillThresholdBytes: Long =
    Spill.effectiveThresholdBytes(opts, Scheduler.parallelism)

  /** Where each key is read, and how its values order. */
  private val layout: SortLayout = new SortLayout(child.outputSchema, keys)

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    // One spill subdirectory per execute() call. The merge reads its runs as
    // it emits, so the directory goes when the output is drained or closed,
    // or when the sort fails before it emits ([[Closing]]).
    val spillDir: Option[OperatorSpillDir] =
      if (maySpill) Some(Spill.openOperatorDir("sort")) else None
    val threshold = if (maySpill) spillThresholdBytes else Long.MaxValue

    try {
      val tasks: Seq[Callable[SortExec.PartitionResult]] =
        (0 until child.numPartitions).map { p =>
          new Callable[SortExec.PartitionResult] {
            def call(): SortExec.PartitionResult = sortPartition(p, threshold, spillDir)
          }
        }
      val partials: IndexedSeq[SortExec.PartitionResult] = Scheduler.submitAndAwaitAll(tasks)
      mergeEmit(partials, spillDir)
    } catch {
      case t: Throwable =>
        spillDir.foreach(_.close())
        throw t
    }
  }

  /** One sort partition. Returns the in-memory tail, sorted, plus any on-disk
    * run paths produced by spilling. With spill on, the run counts what it
    * holds as it copies rows in ([[SortRun.heldBytes]]) and is flushed once
    * that reaches `threshold`, after the batch that crossed it; with spill
    * off nothing is counted and the on-disk run list stays empty. */
  private def sortPartition(
      p: Int,
      threshold: Long,
      spillDir: Option[OperatorSpillDir]): SortExec.PartitionResult = {
    val countsBytes = spillDir.isDefined
    var run = new SortRun(layout, countsBytes)
    var inputRowsHere: Long = 0L
    val runs = mutable.ArrayBuffer.empty[Path]
    val it = child.execute(p)
    try while (it.hasNext) {
      val b = it.next()
      run.append(b)
      inputRowsHere += b.numRows.toLong
      if (countsBytes && run.heldBytes >= threshold && run.rows > 0) {
        flushRun(run, runs, spillDir.get)
        run = new SortRun(layout, countsBytes)
        if (metricsNode != null) {
          val sb = runs.last.toFile.length()
          metricsNode.counters(SortExec.IdxRunsWritten).increment()
          metricsNode.counters(SortExec.IdxBytesSpilled).add(sb)
        }
        if (runs.length > opts.spillMaxRuns)
          throw new RuntimeException(
            s"SortExec produced ${runs.length} spill runs in partition $p, exceeding " +
            s"spill_max_runs=${opts.spillMaxRuns}. Either raise the threshold or " +
            "investigate skew / unbounded input.")
      }
    } finally Closing.close(it)
    val perm = run.sorted()
    if (metricsNode != null) metricsNode.counters(SortExec.IdxInputRows).add(inputRowsHere)
    SortExec.PartitionResult(run, perm, runs.toArray)
  }

  /** Sort `run` and stream it to a fresh parquet file inside `spillDir`: its
    * columns, then its computed keys, by position ([[Spill.positionalSchema]],
    * which keeps a duplicate-named child schema round-trippable). */
  private def flushRun(run: SortRun, runs: mutable.ArrayBuffer[Path], spillDir: OperatorSpillDir): Unit = {
    val perm = run.sorted()
    val file = spillDir.newSpillFile(".parquet")
    val _ = ParquetWriter.writeAll(file, Spill.positionalSchema(layout.runSchema),
      run.batches(perm, layout.runSchema))
    runs += file
  }

  /** The merge over partitions, each contributing zero or more on-disk runs
    * plus an in-memory tail: every run a cursor, in that order, and a
    * [[LoserTree]] over them whose winner is the head that sorts first, ties
    * going to the lower cursor. So a row comes before every row it ties with
    * from a later run or partition, and the output is the stable sort of the
    * input read partition by partition, whichever runs it was cut into: with
    * spill and without it, the same rows in the same order. Disk cursors
    * stream parquet batches lazily — memory bound is `O(K)` cursors × one
    * in-flight ColumnarBatch each. A single run in memory is gathered out
    * whole. */
  private def mergeEmit(
      partials: IndexedSeq[SortExec.PartitionResult],
      spillDir: Option[OperatorSpillDir]): Iterator[ColumnarBatch] = {
    val schema = outputSchema
    val cursorList = mutable.ArrayBuffer.empty[SortCursor]
    try partials.foreach { res =>
      res.diskRuns.foreach { file =>
        val cur = new DiskRunCursor(file)
        cursorList += cur
        if (!cur.advance()) cursorList -= cur
      }
      if (res.perm.length > 0) {
        val cur = new InMemoryCursor(res.run, res.perm)
        if (cur.advance()) cursorList += cur
      }
    } catch {
      case t: Throwable =>
        cursorList.foreach(_.close())
        throw t
    }
    val numCursors = cursorList.length
    if (numCursors == 0) {
      spillDir.foreach(_.close())
      return Iterator.empty
    }
    cursorList(0) match {
      case only: InMemoryCursor if numCursors == 1 =>
        if (metricsNode != null) {
          metricsNode.counters(SortExec.IdxPath).add(SortExec.PathOneRun)
          metricsNode.counters(SortExec.IdxOutputRows).add(only.size.toLong)
        }
        return wrapWithSpillCleanup(only.run.batches(only.perm, schema), spillDir)
      case _ => ()
    }

    val cursors = cursorList.toArray
    if (metricsNode != null) {
      metricsNode.counters(SortExec.IdxPath).add(SortExec.PathMerge)
      metricsNode.counters(SortExec.IdxMergeRunsActive).add(numCursors.toLong)
    }
    val tree = new LoserTree(cursors, layout,
      if (metricsNode != null) metricsNode.counters(SortExec.IdxComparatorCalls) else null)
    val tMergeStart = if (metricsNode != null) System.nanoTime() else 0L
    val capacity = ColumnarBatch.DefaultCapacity
    val ncols = schema.length
    val mergedIt = new Iterator[ColumnarBatch] with AutoCloseable {
      /** Close every run still being read: the consumer stopped. */
      def close(): Unit = {
        done = true
        cursors.foreach(_.close())
      }
      private var done = false
      private var outputRowsLocal: Long = 0L
      def hasNext: Boolean = !done && tree.winner >= 0
      def next(): ColumnarBatch = {
        if (!hasNext) throw new NoSuchElementException("SortExec merge exhausted")
        val out = new ColumnarBatch(schema, capacity)
        var r = 0
        var w = tree.winner
        while (r < capacity && w >= 0) {
          val cur = cursors(w)
          val row = cur.row
          var c = 0
          while (c < ncols) {
            cur.column(c).copyTo(row, out.columns(c), r)
            c += 1
          }
          val tDec = if (metricsNode != null && cur.isInstanceOf[DiskRunCursor]) System.nanoTime() else 0L
          tree.advanceWinner()
          if (metricsNode != null && tDec != 0L)
            metricsNode.counters(SortExec.IdxRunDecodeNanos).add(System.nanoTime() - tDec)
          w = tree.winner
          r += 1
        }
        out.setNumRows(r)
        outputRowsLocal += r.toLong
        if (w < 0) {
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

  /** Wipe the spill subdir once the consumer drains the iterator. */
  private def wrapWithSpillCleanup(
      inner: Iterator[ColumnarBatch],
      spillDir: Option[OperatorSpillDir]): Iterator[ColumnarBatch] =
    spillDir.fold(inner)(_.closeOnDrain(inner))
}

object SortExec {
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

  /** Path codes written into the `path` counter: one run in memory, gathered
    * out whole, or a merge of several runs (or of one spilled run). */
  final val PathOneRun: Long = 0L
  final val PathMerge: Long  = 1L

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

  /** Result of `sortPartition`: the in-memory tail, its rows in key order,
    * plus the paths of any on-disk runs flushed mid-stream. */
  private[exec] final case class PartitionResult(run: SortRun, perm: Array[Int], diskRuns: Array[Path])
}

/** `ORDER BY keys` keeping only the first `n` rows it would return: what a
  * sort followed by `LIMIT n` returns, holding `n` rows a partition however
  * many it reads, where the sort holds every row. Each partition keeps the `n`
  * rows that sort first of those it has read in slots, a batch of `n` rows of
  * its columns and computed keys, with a heap of slot numbers whose head is
  * the slot that sorts last; then the partitions' rows sort together and the
  * first `n` are returned. Rows that sort alike come in the order they were
  * read, partition by partition, as a stable sort leaves them, and so as
  * [[SortExec]] returns them: a later row is kept only when it sorts strictly
  * before the row it replaces.
  *
  * Each row's keys are read once, as the row is read: a key that is a column
  * in place, any other evaluated once a batch ([[SortLayout]]), so a key that
  * reads `RAND()` is one value a row. Once a partition holds `n` rows, a row
  * read costs one comparison of typed keys with the head, and is copied only
  * when it sorts before it. The planner uses it for `ORDER BY ... LIMIT n`,
  * with an `OFFSET m` for `n + m` rows, from 1 to [[TopNExec.MaxRows]] rows. */
final case class TopNExec(child: PhysicalPlan, keys: Seq[SortKey], n: Int) extends PhysicalPlan {
  require(n >= 1 && n <= TopNExec.MaxRows, s"a top-n keeps 1 to ${TopNExec.MaxRows} rows, not $n")

  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1

  private val layout: SortLayout = new SortLayout(child.outputSchema, keys)

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val parts = child.numPartitions
    require(parts <= (1 << (31 - TopNExec.SlotShift)),
      s"a top-n reads at most ${1 << (31 - TopNExec.SlotShift)} partitions, not $parts")
    val tasks: Seq[Callable[TopNExec.Kept]] = (0 until parts).map { p =>
      new Callable[TopNExec.Kept] { def call(): TopNExec.Kept = keep(p) }
    }
    val kept = Scheduler.submitAndAwaitAll(tasks)
    // Every kept row by its partition and slot, sorted by its keys, then its
    // partition, then where it was read there: the stable sort of the rows
    // read partition by partition, which keeps rows that sort alike in the
    // order they were read.
    val total = kept.iterator.map(_.size).sum
    val all = new Array[Int](total)
    var k = 0
    var p = 0
    while (p < parts) {
      var s = 0
      while (s < kept(p).size) { all(k) = (p << TopNExec.SlotShift) | s; k += 1; s += 1 }
      p += 1
    }
    IndexSort.sort(all, total, finalOrder(kept))
    val rows = math.min(n, total)
    val ncols = outputSchema.length
    val cols: Array[Array[ColumnVector]] =
      Array.tabulate(ncols)(c => kept.iterator.map(kp => if (kp.slots == null) null else kp.slots.columns(c)).toArray)
    new Iterator[ColumnarBatch] {
      private var produced = 0
      def hasNext: Boolean = produced < rows
      def next(): ColumnarBatch = {
        if (!hasNext) throw new NoSuchElementException("a top-n has no rows left")
        val take = math.min(ColumnarBatch.DefaultCapacity, rows - produced)
        val out = new ColumnarBatch(outputSchema, take)
        var c = 0
        while (c < ncols) {
          out.columns(c).gatherChunks(cols(c), TopNExec.SlotShift, all, produced, produced + take, 0)
          c += 1
        }
        out.setNumRows(take)
        produced += take
        out
      }
    }
  }

  /** Two kept rows, `p << SlotShift | slot`, by their keys, then their
    * partitions, then where each was read. */
  private def finalOrder(kept: IndexedSeq[TopNExec.Kept]): IntComparator = {
    val nkeys = layout.nkeys
    val orders = layout.orders
    val keyCols = layout.keyCols
    val mask = (1 << TopNExec.SlotShift) - 1
    val slots = kept.iterator.map(_.slots).toArray
    val ordinals = kept.iterator.map(_.ordinals).toArray
    new IntComparator {
      def compare(a: Int, b: Int): Int = {
        val pa = a >>> TopNExec.SlotShift
        val pb = b >>> TopNExec.SlotShift
        val sa = a & mask
        val sb = b & mask
        val ka = slots(pa)
        val kb = slots(pb)
        var k = 0
        while (k < nkeys) {
          val c = orders(k).compare(ka.columns(keyCols(k)), sa, kb.columns(keyCols(k)), sb)
          if (c != 0) return c
          k += 1
        }
        if (pa != pb) Integer.compare(pa, pb)
        else java.lang.Long.compare(ordinals(pa)(sa), ordinals(pb)(sb))
      }
    }
  }

  /** The `n` rows of partition `p` that sort first, rows that sort alike the
    * ones read first. */
  private def keep(p: Int): TopNExec.Kept = {
    val kept = new TopNExec.Kept(layout, n)
    val it = child.execute(p)
    try while (it.hasNext) kept.offer(it.next())
    finally Closing.close(it)
    kept
  }
}

object TopNExec {
  /** The most rows a top-n keeps: past them, a sort, which can spill. A
    * partition holds this many rows at most, and every partition at once. */
  val MaxRows: Int = 10000

  /** The bits of a kept row's slot in its name, `partition << SlotShift |
    * slot`: those of [[MaxRows]]. */
  private[exec] val SlotShift: Int = 32 - Integer.numberOfLeadingZeros(MaxRows - 1)

  /** A partition's kept rows: up to `n` slots of [[slots]] (null until it
    * reads a row), each with where it was read in [[ordinals]], and a heap of
    * slot numbers whose root is the slot that sorts last, of slots that sort
    * alike the one read last. */
  private[exec] final class Kept(layout: SortLayout, n: Int) {
    private val nkeys = layout.nkeys
    private val orders = layout.orders
    private val keyCols = layout.keyCols
    private val ncols = layout.ncols
    private val heap = new Array[Int](n)
    private val keyVecs = new Array[ColumnVector](nkeys)
    private var ordinal = 0L
    private var held = 0
    private var kept: ColumnarBatch = null
    val ordinals: Array[Long] = new Array[Long](n)

    /** The slots, null when no row was read. */
    def slots: ColumnarBatch = kept

    /** Slots held. */
    def size: Int = held

    /** Read `b`'s rows, each kept while fewer than `n` are, and after that
      * only when it sorts strictly before the root, whose slot it takes. */
    def offer(b: ColumnarBatch): Unit = {
      val rows = b.numRows
      if (rows == 0) return
      if (kept == null) kept = new ColumnarBatch(layout.runSchema, n)
      val computed = layout.computedVectors(b)
      var k = 0
      while (k < nkeys) {
        val kc = keyCols(k)
        keyVecs(k) = if (kc < ncols) b.columns(kc) else computed(kc - ncols)
        k += 1
      }
      var r = 0
      while (r < rows) {
        if (held < n) {
          copyIn(b, computed, r, held)
          ordinals(held) = ordinal
          heap(held) = held
          held += 1
          siftUp(held - 1)
        } else {
          val root = heap(0)
          var c = 0
          k = 0
          while (c == 0 && k < nkeys) {
            c = orders(k).compare(keyVecs(k), r, kept.columns(keyCols(k)), root)
            k += 1
          }
          if (c < 0) {
            copyIn(b, computed, r, root)
            ordinals(root) = ordinal
            siftDown()
          }
        }
        ordinal += 1
        r += 1
      }
    }

    /** Row `r` of `b`, and its computed keys, into slot `slot`. */
    private def copyIn(b: ColumnarBatch, computed: Array[ColumnVector], r: Int, slot: Int): Unit = {
      var c = 0
      while (c < ncols) { b.columns(c).copyTo(r, kept.columns(c), slot); c += 1 }
      var j = 0
      while (j < computed.length) { computed(j).copyTo(r, kept.columns(ncols + j), slot); j += 1 }
    }

    /** Whether slot `a` sorts after slot `b`: by the keys, then as read later. */
    private def after(a: Int, b: Int): Boolean = {
      var k = 0
      while (k < nkeys) {
        val col = kept.columns(keyCols(k))
        val c = orders(k).compare(col, a, col, b)
        if (c != 0) return c > 0
        k += 1
      }
      ordinals(a) > ordinals(b)
    }

    private def siftUp(at: Int): Unit = {
      var i = at
      val s = heap(i)
      while (i > 0 && after(s, heap((i - 1) >>> 1))) {
        heap(i) = heap((i - 1) >>> 1)
        i = (i - 1) >>> 1
      }
      heap(i) = s
    }

    private def siftDown(): Unit = {
      var i = 0
      val s = heap(0)
      var done = false
      while (!done) {
        val l = 2 * i + 1
        if (l >= held) done = true
        else {
          val r = l + 1
          val c = if (r < held && after(heap(r), heap(l))) r else l
          if (after(heap(c), s)) { heap(i) = heap(c); i = c } else done = true
        }
      }
      heap(i) = s
    }
  }
}

/** Where a sort reads each key, and how the key's values order: a key that is
  * a column of the sort's input is read in place; any other is computed once
  * a batch (`evalVec`) into a column of its own after the input's, in memory
  * and in a spilled run alike, so a key is read once whatever reads it, and
  * a `RAND()` key is one value a row. */
private[exec] final class SortLayout(schema: Schema, keys: Seq[SortKey]) {
  val ncols: Int = schema.length
  val nkeys: Int = keys.length
  val orders: Array[KeyOrder] = keys.iterator.map(_.order).toArray

  /** The keys computed into columns of their own, in key order. */
  val computed: Array[Expr] = keys.iterator.map(_.expr).filterNot(inPlace).toArray

  /** Each key's column in a run: its input column, or `ncols + j` for the
    * `j`th computed key. */
  val keyCols: Array[Int] = {
    var j = -1
    keys.iterator.map { k =>
      k.expr match {
        case c: ColRefExpr if inPlace(c) => c.index
        case _ => j += 1; ncols + j
      }
    }.toArray
  }

  /** A run's columns: the input's, then the computed keys'. */
  val runSchema: Schema =
    Schema(schema.fields ++ computed.indices.map(j => Field(s"_key$j", computed(j).dataType)))

  /** Bytes a chunk's arrays take: each column's values at the chunk's
    * capacity, and a null mask's words where the vector keeps one. */
  val chunkBytes: Long = runSchema.fields.iterator.map(f => SortRun.chunkBytes(f.dataType)).sum

  /** Each computed key over `b`, evaluated once. */
  def computedVectors(b: ColumnarBatch): Array[ColumnVector] = {
    val out = new Array[ColumnVector](computed.length)
    var j = 0
    while (j < out.length) { out(j) = computed(j).evalVec(b); j += 1 }
    out
  }

  /** A column of the input, of the type the key reads it as. */
  private def inPlace(e: Expr): Boolean = e match {
    case c: ColRefExpr => c.index >= 0 && c.index < ncols && schema.fields(c.index).dataType == c.dataType
    case _ => false
  }
}

/** The rows one sort partition holds until it sorts them: copied, a range of
  * a batch at a time ([[ColumnVector.copyRange]]), into chunks of
  * [[SortRun.Cap]] rows, each a batch of the run's columns, so what the sort
  * holds does not depend on its input's batches or how long they live. A row
  * is named by the `Int` `chunk << Shift | row`; past [[SortRun.MaxRows]] rows
  * a partition fails. [[sorted]] orders those names with [[IndexSort]] by the
  * keys' typed orders, and [[batches]] gathers rows out in that order. When
  * `countsBytes`, it counts what it holds as rows come in ([[heldBytes]]). */
private[exec] final class SortRun(layout: SortLayout, countsBytes: Boolean) {
  private val chunkList = mutable.ArrayBuffer.empty[ColumnarBatch]
  private var fill = 0
  private var held = 0
  private var bytes = 0L
  /** Each column's last value counted: a run of rows holding one object
    * counts its payload once. */
  private val counted = new Array[AnyRef](layout.runSchema.length)

  /** Rows held. */
  def rows: Int = held

  /** Bytes held, counted when `countsBytes`: every chunk's arrays by their
    * capacity, each string's, byte array's and decimal's payload as
    * `Spill.estimateBytes` sizes it, once for each run of rows holding the same
    * object (a parquet row group's values are shared, and so are a sorted
    * input's repeated ones), and the `Int`s sorting the rows takes, the
    * names and [[IndexSort]]'s auxiliary array, four bytes a row each. */
  def heldBytes: Long = bytes + held.toLong * SortRun.IntBytesARow

  /** Chunk `c`. */
  def chunk(c: Int): ColumnarBatch = chunkList(c)

  /** Copy `b`'s rows in, its computed keys evaluated once. */
  def append(b: ColumnarBatch): Unit = {
    val n = b.numRows
    if (n == 0) return
    if (n > SortRun.MaxRows - held)
      throw new IllegalStateException(s"a sort holds at most ${SortRun.MaxRows} rows a partition")
    val computed = layout.computedVectors(b)
    val ncols = layout.ncols
    val width = layout.runSchema.length
    var from = 0
    while (from < n) {
      if (chunkList.isEmpty || fill == SortRun.Cap) {
        chunkList += new ColumnarBatch(layout.runSchema, SortRun.Cap)
        fill = 0
        if (countsBytes) bytes += layout.chunkBytes
      }
      val take = math.min(n - from, SortRun.Cap - fill)
      val chunk = chunkList.last
      var c = 0
      while (c < ncols) {
        chunk.columns(c).copyRange(b.columns(c), from, from + take, fill)
        c += 1
      }
      var j = 0
      while (j < computed.length) {
        chunk.columns(ncols + j).copyRange(computed(j), from, from + take, fill)
        j += 1
      }
      if (countsBytes) {
        c = 0
        while (c < width) {
          bytes += SortRun.payloadBytes(chunk.columns(c), fill, fill + take, counted, c)
          c += 1
        }
      }
      fill += take
      chunk.setNumRows(fill)
      held += take
      from += take
    }
  }

  /** Every row's name in key order, rows that tie in the order appended. */
  def sorted(): Array[Int] = {
    val perm = new Array[Int](held)
    var k = 0
    var c = 0
    while (c < chunkList.length) {
      val base = c << SortRun.Shift
      val n = chunkList(c).numRows
      var r = 0
      while (r < n) { perm(k) = base | r; k += 1; r += 1 }
      c += 1
    }
    IndexSort.sort(perm, held, comparator())
    perm
  }

  /** The rows' order by every key, the first that differs deciding. */
  private def comparator(): IntComparator = {
    val nkeys = layout.nkeys
    val orders = layout.orders
    val keyChunks: Array[Array[ColumnVector]] =
      Array.tabulate(nkeys)(k => chunkList.iterator.map(_.columns(layout.keyCols(k))).toArray)
    val shift = SortRun.Shift
    val mask = SortRun.Mask
    if (nkeys == 1) {
      val order = orders(0)
      val kc = keyChunks(0)
      new IntComparator {
        def compare(a: Int, b: Int): Int = order.compare(kc(a >>> shift), a & mask, kc(b >>> shift), b & mask)
      }
    } else new IntComparator {
      def compare(a: Int, b: Int): Int = {
        val ca = a >>> shift
        val ra = a & mask
        val cb = b >>> shift
        val rb = b & mask
        var k = 0
        while (k < nkeys) {
          val kc = keyChunks(k)
          val c = orders(k).compare(kc(ca), ra, kc(cb), rb)
          if (c != 0) return c
          k += 1
        }
        0
      }
    }
  }

  /** The rows `perm` names, in its order, as batches of up to
    * [[SortRun.Cap]] rows of `schema`, the run's first `schema.length`
    * columns: the sort's output (its input's columns), or a spilled run (all
    * of them). */
  def batches(perm: Array[Int], schema: Schema): Iterator[ColumnarBatch] = {
    val ncols = schema.length
    val cols: Array[Array[ColumnVector]] = Array.tabulate(ncols)(c => chunkList.iterator.map(_.columns(c)).toArray)
    new Iterator[ColumnarBatch] {
      private var produced = 0
      def hasNext: Boolean = produced < perm.length
      def next(): ColumnarBatch = {
        if (!hasNext) throw new NoSuchElementException("a sorted run has no rows left")
        val take = math.min(SortRun.Cap, perm.length - produced)
        val out = new ColumnarBatch(schema, take)
        var c = 0
        while (c < ncols) {
          out.columns(c).gatherChunks(cols(c), SortRun.Shift, perm, produced, produced + take, 0)
          c += 1
        }
        out.setNumRows(take)
        produced += take
        out
      }
    }
  }
}

private[exec] object SortRun {
  /** Rows a chunk holds: a batch's. */
  val Cap: Int = ColumnarBatch.DefaultCapacity
  /** The bits of a row's number within its chunk: those of the next power of
    * two at or above [[Cap]]. */
  val Shift: Int = 32 - Integer.numberOfLeadingZeros(Cap - 1)
  val Mask: Int = (1 << Shift) - 1
  /** The most rows a partition holds: an array's, and as many as `Int`
    * names of chunks of [[Cap]] rows can name. */
  val MaxRows: Int = math.min(Int.MaxValue.toLong, (1L << (31 - Shift)) * Cap).toInt

  /** The `Int`s sorting a run takes a row: its name, and [[IndexSort]]'s
    * auxiliary array while it sorts. */
  val IntBytesARow: Long = 8L

  /** A reference in an array: 4 bytes where the JVM compresses references,
    * as it does by default for a heap under 32 GB, else 8. */
  private val ReferenceBytes: Int = if (Runtime.getRuntime.maxMemory < (32L << 30)) 4 else 8

  /** Bytes a chunk's vector of type `t` takes: its values array at [[Cap]]
    * rows, and its null mask's words where it keeps one. */
  def chunkBytes(t: DataType): Long = {
    val mask = 8L * ((Cap + 63) / 64)
    t match {
      case DataType.IntType | DataType.DateType | DataType.FloatType => 4L * Cap + mask
      case DataType.LongType | DataType.TimestampType | DataType.DoubleType => 8L * Cap + mask
      case DataType.BooleanType => 1L * Cap + mask
      case DataType.StringType | DataType.BinaryType | _: DataType.DecimalType => ReferenceBytes.toLong * Cap
      case DataType.NullType => 0L
    }
  }

  /** The payload of the values in rows `from` to `until - 1` of `v`: a
    * string's characters, a byte array's bytes and a decimal, each with its
    * object's header, as `Spill.estimateBytes` sizes them, but each run of
    * rows holding the same object counted once; `counted(c)` carries the
    * last object seen from one call to the next. 0 for a vector holding its
    * values in its arrays. */
  def payloadBytes(v: ColumnVector, from: Int, until: Int, counted: Array[AnyRef], c: Int): Long = {
    var total = 0L
    var last = counted(c)
    v match {
      case s: StringVector =>
        var i = from
        while (i < until) {
          val x = s.values(i)
          if (x != null && (x ne last)) total += 24L + 2L * x.length
          last = x
          i += 1
        }
      case b: BinaryVector =>
        var i = from
        while (i < until) {
          val x = b.values(i)
          if (x != null && (x ne last)) total += 24L + x.length
          last = x
          i += 1
        }
      case d: DecimalVector =>
        var i = from
        while (i < until) {
          val x = d.values(i)
          if (x != null && (x ne last)) total += 48L
          last = x
          i += 1
        }
      case _ => ()
    }
    counted(c) = last
    total
  }
}

/** One sorted run the merge reads a row at a time: the vectors holding its
  * head row's columns (a run's columns, its computed keys after them), and
  * that row. Advanced once before the first read; `advance` says whether a
  * row is there. Implementations:
  *   - [[InMemoryCursor]]: a partition's in-memory tail, in sorted order.
  *   - [[DiskRunCursor]]: a spilled run, streamed a batch at a time.
  */
private[exec] sealed trait SortCursor {
  /** Column `c` of the batch holding the head row. */
  def column(c: Int): ColumnVector
  /** The head row's row in it. */
  def row: Int
  /** Advance past the head. Returns true if a row remains. */
  def advance(): Boolean
  /** Release resources (close parquet readers). Called on exhaustion and
    * on early-abort paths. Idempotent. */
  def close(): Unit
}

private[exec] final class InMemoryCursor(val run: SortRun, val perm: Array[Int]) extends SortCursor {
  private var pos = -1
  private var cols: Array[ColumnVector] = null
  private var r = 0
  def size: Int = perm.length
  def column(c: Int): ColumnVector = cols(c)
  def row: Int = r
  def advance(): Boolean = {
    pos += 1
    if (pos >= perm.length) false
    else {
      val e = perm(pos)
      cols = run.chunk(e >>> SortRun.Shift).columns
      r = e & SortRun.Mask
      true
    }
  }
  def close(): Unit = ()
}

/** Streams a parquet spill file as a sorted row source. The parquet file's
  * on-disk row order is the sort order (writers emit batches in sorted
  * order; parquet preserves row order within a row group). Its columns are
  * read in place, never boxed: a run's columns at their positions, each at
  * the type it was stored as ([[Spill.storedType]]). */
private[exec] final class DiskRunCursor(file: Path) extends SortCursor {
  private val reader = ParquetReader.fromPath(file.toString)
  private val partIt: Iterator[Int] = (0 until reader.numPartitions).iterator
  private var batchIt: Iterator[ColumnarBatch] = Iterator.empty
  private var cols: Array[ColumnVector] = null
  private var numRows: Int = 0
  private var rowIdx: Int = -1
  @volatile private var closed: Boolean = false

  def column(c: Int): ColumnVector = cols(c)
  def row: Int = rowIdx

  def advance(): Boolean = {
    if (closed) return false
    rowIdx += 1
    while (rowIdx >= numRows) {
      if (!batchIt.hasNext) {
        if (!partIt.hasNext) { close(); return false }
        batchIt = reader.readPartition(partIt.next())
      } else {
        val b = batchIt.next()
        cols = b.columns
        numRows = b.numRows
        rowIdx = 0
      }
    }
    true
  }

  def close(): Unit = {
    if (closed) return
    closed = true
    Closing.close(batchIt)
    cols = null
  }
}

/** A tournament over sorted cursors, each advanced to its first row: its
  * [[winner]] is the cursor whose head row sorts first by the sort's keys,
  * ties going to the lower cursor index, so a merge through it is stable.
  * Each internal node keeps the loser of the match played there, so after
  * the winner advances only the matches on its path are played again:
  * `log2 K` comparisons a row, where a binary heap makes up to twice that. An
  * exhausted cursor loses every match. `comparisons`, when given, counts
  * every comparison of two heads. */
private[exec] final class LoserTree(cursors: Array[SortCursor], layout: SortLayout,
    comparisons: java.util.concurrent.atomic.LongAdder) {
  private val k = cursors.length
  private val live = Array.fill(k)(true)
  private val nkeys = layout.nkeys
  private val keyCols = layout.keyCols
  private val orders = layout.orders
  /** Node `i`'s loser, for the internal nodes 1 to `k - 1` (node `i`'s
    * children are `2i` and `2i + 1`, and leaf `k + c` is cursor `c`); the
    * winner at 0. */
  private val tree = new Array[Int](math.max(1, k))

  locally {
    val winners = new Array[Int](2 * k)
    var c = 0
    while (c < k) { winners(k + c) = c; c += 1 }
    var node = k - 1
    while (node >= 1) {
      val l = winners(2 * node)
      val r = winners(2 * node + 1)
      if (beats(l, r)) { winners(node) = l; tree(node) = r }
      else { winners(node) = r; tree(node) = l }
      node -= 1
    }
    tree(0) = if (k == 1) 0 else winners(1)
  }

  /** The cursor whose head sorts first, or -1 once every cursor is done. */
  def winner: Int = { val w = tree(0); if (live(w)) w else -1 }

  /** Advance the winner past its head, closing it when it has no row left,
    * and play its path again. */
  def advanceWinner(): Unit = {
    val w = tree(0)
    if (!cursors(w).advance()) {
      live(w) = false
      cursors(w).close()
    }
    var winner = w
    var node = (w + k) >>> 1
    while (node >= 1) {
      val other = tree(node)
      if (beats(other, winner)) { tree(node) = winner; winner = other }
      node >>>= 1
    }
    tree(0) = winner
  }

  /** Whether cursor `a`'s head sorts before cursor `b`'s: by the keys, then
    * by index; an exhausted cursor never does, and always loses. */
  private def beats(a: Int, b: Int): Boolean = {
    if (!live(a)) return false
    if (!live(b)) return true
    if (comparisons != null) comparisons.increment()
    val x = cursors(a)
    val y = cursors(b)
    var i = 0
    while (i < nkeys) {
      val kc = keyCols(i)
      val c = orders(i).compare(x.column(kc), x.row, y.column(kc), y.row)
      if (c != 0) return c < 0
      i += 1
    }
    a < b
  }
}
