package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.write.parquet.ParquetWriter

import java.nio.file.Path
import java.util
import java.util.concurrent.Callable
import scala.collection.mutable

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
  *
  * ## Spill
  *
  * When `opts.spillEnabled`, each per-partition `collect` watches its
  * accumulated input bytes; when over threshold it flushes the current set
  * to a parquet file (one row per distinct key — same column layout as the
  * operator's output) and resets the set. At emit time the final set is
  * unioned with every row from every spill file.
  *
  * Bit-equal with the non-spill path: the set is deterministic up to
  * insertion order, and the emit iterator never relies on a specific row
  * order (DISTINCT is unordered). The parity check in
  * [[DistinctExecSpillTest]] asserts multiset equality on output rows.
  */
final case class DistinctExec(
    child: PhysicalPlan,
    opts: ExecutionOptions = ExecutionOptions.Default,
    metricsNode: MetricsNode = null
) extends PhysicalPlan {
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

  private val spillEnabled: Boolean = opts.spillEnabled
  private lazy val spillThresholdBytes: Long =
    Spill.effectiveThresholdBytes(opts, Scheduler.parallelism)

  // Stamp the constant key-codec path code into the metrics node once at
  // construction. DistinctExec keys are always every output column, so the
  // codec choice is fixed for the operator's lifetime.
  private val keyCodecPathCode: Long = {
    val types = child.outputSchema.fields.map(_.dataType)
    if (types.isEmpty) DistinctExec.KeyPathPacked  // EmptyKeyCodec (degenerate)
    else if (types.forall(KeyCodec.isPackable)) DistinctExec.KeyPathPacked
    else DistinctExec.KeyPathObject
  }
  if (metricsNode != null) {
    metricsNode.counters(DistinctExec.IdxKeyCodecPath).add(keyCodecPathCode)
  }

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (isSharded) executePerShard(partition)
    else executeCollapsing(partition)
  }

  private def executePerShard(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numPartitions,
      s"partition $partition out of range [0,$numPartitions)")
    val spillDir = if (spillEnabled) Some(Spill.openOperatorDir("distinct-shard")) else None
    val r = collect(partition, spillDir)
    val tMerge = if (metricsNode != null) System.nanoTime() else 0L
    DistinctSpiller.foldSpillFiles(r.spillFiles, codec, ncols, r.set)
    if (metricsNode != null) {
      metricsNode.counters(DistinctExec.IdxMergeNanos).add(System.nanoTime() - tMerge)
      metricsNode.counters(DistinctExec.IdxSpillRunsRead).add(r.spillFiles.length.toLong)
      metricsNode.counters(DistinctExec.IdxGroupCount).add(r.set.size.toLong)
    }
    wrapWithSpillCleanup(emit(r.set), spillDir)
  }

  private def executeCollapsing(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0,
      s"collapsing distinct has only one partition; got partition=$partition")
    val spillDir = if (spillEnabled) Some(Spill.openOperatorDir("distinct")) else None
    val tasks: Seq[Callable[DistinctExec.PartialSet]] =
      (0 until child.numPartitions).map { p =>
        new Callable[DistinctExec.PartialSet] {
          def call(): DistinctExec.PartialSet = collect(p, spillDir)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val tMerge = if (metricsNode != null) System.nanoTime() else 0L
    val merged = new util.LinkedHashSet[AnyRef]()
    partials.foreach(p => merged.addAll(p.set))
    if (metricsNode != null) metricsNode.counters(DistinctExec.IdxMergeNanos).add(System.nanoTime() - tMerge)
    var runsRead = 0L
    partials.foreach { p =>
      DistinctSpiller.foldSpillFiles(p.spillFiles, codec, ncols, merged)
      runsRead += p.spillFiles.length.toLong
    }
    if (metricsNode != null) {
      metricsNode.counters(DistinctExec.IdxSpillRunsRead).add(runsRead)
      metricsNode.counters(DistinctExec.IdxGroupCount).add(merged.size.toLong)
    }
    wrapWithSpillCleanup(emit(merged), spillDir)
  }

  /** Wipe the spill subdir once the consumer drains the iterator. */
  private def wrapWithSpillCleanup(
      inner: Iterator[ColumnarBatch],
      spillDir: Option[OperatorSpillDir]): Iterator[ColumnarBatch] =
    spillDir.fold(inner)(_.closeOnDrain(inner))

  private def collect(p: Int, spillDir: Option[OperatorSpillDir]): DistinctExec.PartialSet = {
    val tStart = if (metricsNode != null) System.nanoTime() else 0L
    val set = new util.LinkedHashSet[AnyRef]()
    val spillFiles = mutable.ArrayBuffer.empty[Path]
    val threshold = if (spillDir.isDefined) spillThresholdBytes else Long.MaxValue
    var bytesSinceFlush: Long = 0L
    var peakSize: Long = 0L
    var peakBytes: Long = 0L
    val scratch = codec.newScratch()
    val it = child.execute(p)
    while (it.hasNext) {
      val b = it.next()
      if (spillDir.isDefined) {
        bytesSinceFlush += Spill.estimateBytes(b)
        if (bytesSinceFlush > peakBytes) peakBytes = bytesSinceFlush
      }
      var r = 0
      while (r < b.numRows) {
        // Probe with the scratch wrapper to avoid the per-row key allocation
        // on dedup HITS (the common case once the set is warm). On a miss we
        // pay one alloc for the owned key, the same as before. Skipping the
        // wrapper alloc on hit eliminates two allocations per duplicate row.
        codec.encodeIntoScratch(scratch, b, r)
        if (!set.contains(scratch)) set.add(codec.ownFromScratch(scratch))
        r += 1
      }
      val sz = set.size.toLong
      if (sz > peakSize) peakSize = sz
      if (bytesSinceFlush >= threshold && spillDir.isDefined && !set.isEmpty) {
        val file = spillDir.get.newSpillFile(".parquet")
        DistinctSpiller.writeSet(file, codec, child.outputSchema, set)
        if (metricsNode != null) {
          val sb = file.toFile.length()
          metricsNode.counters(DistinctExec.IdxSpillEvents).increment()
          metricsNode.counters(DistinctExec.IdxBytesSpilled).add(sb)
        }
        set.clear()
        spillFiles += file
        bytesSinceFlush = 0L
        if (spillFiles.length > opts.spillMaxRuns)
          throw new RuntimeException(
            s"DistinctExec produced ${spillFiles.length} spill runs in partition $p, " +
            s"exceeding spill_max_runs=${opts.spillMaxRuns}.")
      }
    }
    if (metricsNode != null) {
      metricsNode.counters(DistinctExec.IdxPartialAggregateNanos).add(System.nanoTime() - tStart)
      metricsNode.counters(DistinctExec.IdxHashMapPeakSize).add(peakSize)
      metricsNode.counters(DistinctExec.IdxPeakInMemoryBytes).add(peakBytes)
    }
    DistinctExec.PartialSet(set, spillFiles.toArray)
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

object DistinctExec {
  // ---- Counter indices ------------------------------------------------------
  // In-memory counters mirror HashAggregateExec's shape (DistinctExec IS an
  // aggregate with no value columns).
  final val IdxGroupCount: Int             = 0
  final val IdxHashMapPeakSize: Int        = 1
  final val IdxKeyCodecPath: Int           = 2
  final val IdxMergeNanos: Int             = 3
  final val IdxPartialAggregateNanos: Int  = 4
  // Spill counters (a narrower set than HashAggregateExec's).
  final val IdxSpillEvents: Int            = 5
  final val IdxBytesSpilled: Int           = 6
  final val IdxSpillRunsRead: Int          = 7
  final val IdxPeakInMemoryBytes: Int      = 8

  /** Key-codec path codes (Long path doesn't apply to multi-column distinct,
    * so only Packed and Object are possible). */
  final val KeyPathPacked: Long = 1L
  final val KeyPathObject: Long = 2L

  /** Name array parallel to the Idx* constants above. */
  val IdxCounterNames: Array[String] = Array(
    "groupCount",
    "hashMapPeakSize",
    "keyCodecPath",
    "mergeNanos",
    "partialAggregateNanos",
    "spillEvents",
    "bytesSpilled",
    "spillRunsRead",
    "peakInMemoryBytes")

  private[exec] final case class PartialSet(set: util.LinkedHashSet[AnyRef], spillFiles: Array[Path])
}

/** Spill helpers for [[DistinctExec]]. Spill file schema mirrors the
  * operator's output schema — one row per distinct key. */
private[exec] object DistinctSpiller {

  def writeSet(
      file: Path,
      codec: KeyCodec,
      schema: Schema,
      set: util.LinkedHashSet[AnyRef]): Unit = {
    if (set.isEmpty) return
    // Spill files are read back by index (see foldSpillFiles); positional names
    // keep a duplicate-named child schema round-trippable through parquet's
    // by-name column model. See Spill.positionalSchema. The decode below writes
    // key columns by index, so it is unaffected by the rename.
    val spillFileSchema = Spill.positionalSchema(schema)
    val capacity = ColumnarBatch.DefaultCapacity
    val it = set.iterator()
    val batchIter = new Iterator[ColumnarBatch] {
      def hasNext: Boolean = it.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(spillFileSchema, capacity)
        var r = 0
        while (r < capacity && it.hasNext) {
          codec.decode(it.next(), out, 0, r)
          r += 1
        }
        out.setNumRows(r)
        out
      }
    }
    ParquetWriter.writeAll(file, spillFileSchema, batchIter)
  }

  def foldSpillFiles(
      files: Array[Path],
      codec: KeyCodec,
      ncols: Int,
      target: util.LinkedHashSet[AnyRef]): Unit = {
    if (files.isEmpty) return
    val keyBuf = new Array[Any](ncols)
    var f = 0
    while (f < files.length) {
      val reader = ParquetReader.fromPath(files(f).toString)
      var part = 0
      while (part < reader.numPartitions) {
        val batchIt = reader.readPartition(part)
        while (batchIt.hasNext) {
          val batch = batchIt.next()
          var r = 0
          while (r < batch.numRows) {
            var c = 0
            while (c < ncols) {
              val col = batch.column(c)
              keyBuf(c) = if (col.isNull(r)) null else col.getBoxed(r)
              c += 1
            }
            val key = codec.encodeBoxed(keyBuf)
            target.add(key)
            r += 1
          }
        }
        part += 1
      }
      f += 1
    }
  }
}
