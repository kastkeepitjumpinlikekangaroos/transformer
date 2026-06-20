package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._
import com.transformer.write.parquet.ParquetWriter

import java.io.{DataInput, DataOutput}
import java.nio.file.Path
import java.util
import java.util.concurrent.Callable
import scala.collection.mutable

/** Hash-based GROUP BY. Two execution modes share this one operator,
  * selected by whether `child` is an [[ExchangeExec]]:
  *
  *   - **Per-shard** (child is `ExchangeExec`): the planner has inserted an
  *     exchange above this operator that hash-partitions by the group keys
  *     into K shards. Each shard is key-disjoint from every other, so this
  *     operator's `execute(p)` consumes only shard `p`, builds one final
  *     map, and emits it. No cross-shard merge. The operator's
  *     `numPartitions` equals the child exchange's K so downstream
  *     operators see all K partitions in parallel.
  *   - **Collapsing** (child is anything else): no exchange. The operator
  *     fans out across the child's partitions through [[Scheduler]] in
  *     `executeCollapsing`, merges the partials, and emits a single
  *     output partition. Reached when `groupKeys.isEmpty` (every row
  *     aggregates into the same bucket) or when the planner's cardinality
  *     estimate is below [[LogicalPlanCardinality.MinShardableSize]] — the
  *     exchange overhead would outweigh the per-shard parallelism. The
  *     zero-row insertion for `COUNT(*) FROM <empty>`-style queries lives
  *     in this mode (only fires when `groupKeys.isEmpty`).
  *
  * Output schema = group keys ++ aggregate results (in the order given).
  *
  * Group keys are encoded via [[KeyCodec]] — packed `byte[]` (with cached
  * hashCode and `Arrays.equals` equality) for fixed-width-only keys, cached-
  * hash `Array[AnyRef]` otherwise. When every group expression is a pure
  * [[ColRefExpr]] (the common case) the codec reads primitives directly from
  * the batch's typed [[ColumnVector]]s, avoiding the per-row boxing the old
  * `Seq[Any]`-keyed implementation paid.
  *
  * Per-row aggregate-arg evaluation is hoisted out of the inner loop: each
  * batch evaluates every aggregate's args once via [[Expr.evalVec]] into a
  * cached `Array[ColumnVector]` and the per-row state update reads typed
  * primitives directly from those vectors via [[AggState.updateAt]]. That
  * eliminates the boxed `Number`/`Boolean` allocations the old per-row
  * `Expr.eval` path created on every row × every aggregate.
  */
final case class HashAggregateExec(
    child: PhysicalPlan,
    groupKeys: Seq[(Expr, String)],
    aggregates: Seq[(AggExpr, String)],
    opts: ExecutionOptions = ExecutionOptions.Default,
    metricsNode: MetricsNode = null
) extends PhysicalPlan {

  val outputSchema: Schema = Schema(
    (groupKeys.map { case (e, n) => Field(n, e.dataType) } ++
      aggregates.map { case (a, n) => Field(n, a.resultType) }).toVector
  )

  /** True when the planner has wrapped `child` in an `ExchangeExec` that
    * has hash-partitioned by the same `groupKeys`. Drives the per-shard vs
    * collapsing dispatch in [[execute]]. */
  private val isSharded: Boolean = child.isInstanceOf[ExchangeExec]

  def numPartitions: Int = if (isSharded) child.numPartitions else 1

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

  // Fast path: a single fixed-width-numeric-fittable column read as a primitive
  // long out of the source [[ColumnVector]]. Skips the codec entirely — keys go
  // into a [[LongHashMap]] without boxing.
  private val useLongKey: Boolean =
    nKeys == 1 && keysAreColRefs && KeyCodec.isLongFittable(keyTypes(0))
  private val longKeyColIdx: Int =
    if (useLongKey) keyExprs(0).asInstanceOf[ColRefExpr].index else -1

  // Encode which key-encoding path runs for this operator. Stable across the
  // whole task: per-shard / collapsing chooses the same codec. Surfaced once
  // at execute() entry into the keyCodecPath counter; downstream consumers
  // read it from `_perf.json` to attribute time to packed-bytes vs object-
  // array codec performance under different schemas. See [[HashAggregateExec]]
  // companion for the Idx<Name> constants.
  private val keyCodecPathCode: Long = {
    if (useLongKey) HashAggregateExec.KeyPathLong
    else if (nKeys == 0) HashAggregateExec.KeyPathPacked  // EmptyKeyCodec — treat as packed (no boxing)
    else if (keyTypes.forall(KeyCodec.isPackable)) HashAggregateExec.KeyPathPacked
    else if (nKeys == 1) HashAggregateExec.KeyPathSingleObject  // SingleObjectKeyCodec
    else HashAggregateExec.KeyPathObject
  }

  // Effective spill switch: user opted in AND every aggregate is spillable.
  // Mixed-aggregate queries with a CountDistinct fall back to the heap-only
  // path — see plan 09's "CountDistinct fall back" risk.
  private val spillEnabled: Boolean =
    opts.spillEnabled && AggStateSerde.allSpillable(aggregates.iterator.map(_._1).toIndexedSeq)

  /** Effective threshold in bytes. Lazy so the Runtime.maxMemory() call is
    * paid once per plan, not per task. */
  private lazy val spillThresholdBytes: Long =
    Spill.effectiveThresholdBytes(opts, Scheduler.parallelism)

  // Stamp the constant key-codec path code into the metrics node once at
  // construction. The path is stable across every partition / shard for a
  // given operator, so a single write at init avoids redundant per-partition
  // bookkeeping. See [[HashAggregateExec]] companion for the path codes.
  if (metricsNode != null) {
    metricsNode.counters(HashAggregateExec.IdxKeyCodecPath).add(keyCodecPathCode)
  }

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (isSharded) executePerShard(partition)
    else if (useLongKey) executeCollapsingLong(partition)
    else executeCollapsing(partition)
  }

  // ---- Per-shard path: exchange above; one shard per `execute(p)` -----------

  private def executePerShard(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numPartitions,
      s"partition $partition out of range [0,$numPartitions)")
    // One final map per shard. No fan-out across child.numPartitions; the
    // exchange already routed rows so each shard's input is key-disjoint.
    val spillDir = if (spillEnabled) Some(Spill.openOperatorDir("agg-shard")) else None
    val stats = if (metricsNode == null) null else new AggStateSerde.SerdeStats
    if (useLongKey) {
      val pa = partialAggregateLong(partition, spillDir)
      val tMerge = if (metricsNode != null) System.nanoTime() else 0L
      AggSpiller.foldLongSpillFiles(pa.spillFiles, aggArr, pa.map, stats)
      if (metricsNode != null) {
        metricsNode.counters(HashAggregateExec.IdxMergeFromSpillNanos).add(System.nanoTime() - tMerge)
        metricsNode.counters(HashAggregateExec.IdxSpillRunsRead).add(pa.spillFiles.length.toLong)
        metricsNode.counters(HashAggregateExec.IdxGroupCount).add(pa.map.size.toLong)
        if (stats != null) {
          metricsNode.counters(HashAggregateExec.IdxSerdeReadNanos).add(stats.readNanos)
          metricsNode.counters(HashAggregateExec.IdxSerdeWriteNanos).add(stats.writeNanos)
        }
      }
      wrapWithSpillCleanup(emitLong(pa.map), spillDir)
    } else {
      val pa = partialAggregate(partition, spillDir)
      val tMerge = if (metricsNode != null) System.nanoTime() else 0L
      AggSpiller.foldCodecSpillFiles(pa.spillFiles, keyCodec, nKeys, aggArr, pa.map, stats)
      if (metricsNode != null) {
        metricsNode.counters(HashAggregateExec.IdxMergeFromSpillNanos).add(System.nanoTime() - tMerge)
        metricsNode.counters(HashAggregateExec.IdxSpillRunsRead).add(pa.spillFiles.length.toLong)
        metricsNode.counters(HashAggregateExec.IdxGroupCount).add(pa.map.size.toLong)
        if (stats != null) {
          metricsNode.counters(HashAggregateExec.IdxSerdeReadNanos).add(stats.readNanos)
          metricsNode.counters(HashAggregateExec.IdxSerdeWriteNanos).add(stats.writeNanos)
        }
      }
      wrapWithSpillCleanup(emit(pa.map), spillDir)
    }
  }

  // ---- Collapsing paths: no exchange; fan out + merge -----------------------

  /** Codec / no-GROUP-BY path. Each child partition builds a partial
    * `LinkedHashMap` (keyed by the encoded codec key, or the empty-key
    * sentinel for no-GROUP-BY), and the merge step combines partials. The
    * empty-input zero-row insertion lives here. */
  private def executeCollapsing(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0,
      s"collapsing aggregate has only one partition; got partition=$partition")
    val spillDir = if (spillEnabled) Some(Spill.openOperatorDir("agg")) else None
    val tasks: Seq[Callable[PartialAgg]] =
      (0 until child.numPartitions).map { p =>
        new Callable[PartialAgg] {
          def call(): PartialAgg = partialAggregate(p, spillDir)
        }
      }
    val partials: Seq[PartialAgg] = Scheduler.submitAndAwaitAll(tasks)
    val tMerge = if (metricsNode != null) System.nanoTime() else 0L
    val merged = partials.map(_.map).reduceOption(merge)
      .getOrElse(new util.LinkedHashMap[AnyRef, Array[AggState]]())
    if (metricsNode != null) metricsNode.counters(HashAggregateExec.IdxMergeNanos).add(System.nanoTime() - tMerge)
    // Fold every partition's spill files into the merged map. The dependency
    // direction is forward: spill files written during ingestion contain
    // partial states that must be combined with anything currently in
    // `merged` for the same key.
    val stats = if (metricsNode == null) null else new AggStateSerde.SerdeStats
    val tFoldStart = if (metricsNode != null) System.nanoTime() else 0L
    var runsRead = 0L
    partials.foreach { p =>
      AggSpiller.foldCodecSpillFiles(p.spillFiles, keyCodec, nKeys, aggArr, merged, stats)
      runsRead += p.spillFiles.length.toLong
    }
    if (metricsNode != null) {
      metricsNode.counters(HashAggregateExec.IdxMergeFromSpillNanos).add(System.nanoTime() - tFoldStart)
      metricsNode.counters(HashAggregateExec.IdxSpillRunsRead).add(runsRead)
      if (stats != null) {
        metricsNode.counters(HashAggregateExec.IdxSerdeReadNanos).add(stats.readNanos)
        metricsNode.counters(HashAggregateExec.IdxSerdeWriteNanos).add(stats.writeNanos)
      }
    }
    // No-GROUP-BY aggregates require one row of output even on empty input
    // (e.g., `COUNT(*) FROM <empty>` → 0). With group keys, an empty input
    // is correctly an empty output (no groups).
    if (groupKeys.isEmpty && merged.isEmpty) {
      merged.put(keyCodec.encodeBoxed(EmptyKeyBuf), newStates())
    }
    if (metricsNode != null) metricsNode.counters(HashAggregateExec.IdxGroupCount).add(merged.size.toLong)
    wrapWithSpillCleanup(emit(merged), spillDir)
  }

  /** Long-key collapsing path. Same fan-out + merge shape as
    * [[executeCollapsing]], but each partition builds a [[LongHashMap]]
    * keyed by the primitive long extracted directly from the source
    * column. Used when GROUP BY is a single fixed-width-numeric ColRef —
    * the codec / boxing-free fast path that the planner picks at runtime
    * (`useLongKey`). */
  private def executeCollapsingLong(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0,
      s"collapsing aggregate has only one partition; got partition=$partition")
    val spillDir = if (spillEnabled) Some(Spill.openOperatorDir("agg-long")) else None
    val tasks: Seq[Callable[PartialAggLong]] =
      (0 until child.numPartitions).map { p =>
        new Callable[PartialAggLong] {
          def call(): PartialAggLong = partialAggregateLong(p, spillDir)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val tMerge = if (metricsNode != null) System.nanoTime() else 0L
    val merged = partials.map(_.map).reduceOption(mergeLong)
      .getOrElse(new LongHashMap[Array[AggState]]())
    if (metricsNode != null) metricsNode.counters(HashAggregateExec.IdxMergeNanos).add(System.nanoTime() - tMerge)
    val stats = if (metricsNode == null) null else new AggStateSerde.SerdeStats
    val tFoldStart = if (metricsNode != null) System.nanoTime() else 0L
    var runsRead = 0L
    partials.foreach { p =>
      AggSpiller.foldLongSpillFiles(p.spillFiles, aggArr, merged, stats)
      runsRead += p.spillFiles.length.toLong
    }
    if (metricsNode != null) {
      metricsNode.counters(HashAggregateExec.IdxMergeFromSpillNanos).add(System.nanoTime() - tFoldStart)
      metricsNode.counters(HashAggregateExec.IdxSpillRunsRead).add(runsRead)
      metricsNode.counters(HashAggregateExec.IdxGroupCount).add(merged.size.toLong)
      if (stats != null) {
        metricsNode.counters(HashAggregateExec.IdxSerdeReadNanos).add(stats.readNanos)
        metricsNode.counters(HashAggregateExec.IdxSerdeWriteNanos).add(stats.writeNanos)
      }
    }
    wrapWithSpillCleanup(emitLong(merged), spillDir)
  }

  /** Merge `b` into `a` for the long-key map, in place. Used by
    * [[executeCollapsingLong]] to combine per-partition partials. */
  private def mergeLong(
      a: LongHashMap[Array[AggState]],
      b: LongHashMap[Array[AggState]]): LongHashMap[Array[AggState]] = {
    b.forEach { (isNull, k, bs) =>
      val existing = if (isNull) a.getNull else a.get(k)
      if (existing != null) {
        var i = 0
        while (i < existing.length) { existing(i).merge(bs(i)); i += 1 }
      } else {
        if (isNull) a.putNull(bs) else a.put(k, bs)
      }
    }
    a
  }

  private val EmptyKeyBuf: Array[Any] = new Array[Any](0)
  private val aggCount: Int = aggregates.size
  private val aggArr: Array[AggExpr] = aggregates.iterator.map(_._1).toArray
  // Pre-compute each aggregate's arg-Expr array once at plan time so the
  // per-batch arg-eval loop doesn't allocate a new Seq.toArray view per batch.
  private val aggArgsArr: Array[Array[Expr]] =
    aggArr.map(_.args.toArray)

  /** Schema of the group key columns alone. Spill writers add Binary
    * state columns on top of this, one per aggregate. The field names
    * are pulled from the user's [[groupKeys]] aliases so the spill file
    * is human-readable in `parquet-tools meta`. */
  private val groupKeySchema: Schema =
    Schema(groupKeys.map { case (e, n) => Field(n, e.dataType) }.toVector)

  /** Wrap `inner` so the spill subdir is wiped when the consumer drains
    * the iterator. The shutdown hook in [[Spill]] catches the abandoned
    * case. */
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

  private def newStates(): Array[AggState] = {
    val out = new Array[AggState](aggCount)
    var i = 0
    while (i < aggCount) { out(i) = AggState.init(aggArr(i)); i += 1 }
    out
  }

  /** Per-batch scratch: evaluate every aggregate's args via `evalVec` and
    * stash the resulting per-arg vectors into a 2D array reused across
    * batches. Returns the populated 2D array. ColRef args alias directly
    * to the source column (no alloc); computed args allocate one vector
    * per arg per batch (not per row). */
  private final class ArgVecsCache {
    private val argVecs: Array[Array[ColumnVector]] = {
      val a = new Array[Array[ColumnVector]](aggCount)
      var i = 0
      while (i < aggCount) {
        a(i) = new Array[ColumnVector](aggArgsArr(i).length)
        i += 1
      }
      a
    }
    def populate(batch: ColumnarBatch): Array[Array[ColumnVector]] = {
      var a = 0
      while (a < aggCount) {
        val args = aggArgsArr(a)
        val vecs = argVecs(a)
        var k = 0
        while (k < args.length) { vecs(k) = args(k).evalVec(batch); k += 1 }
        a += 1
      }
      argVecs
    }
  }

  private def updateStatesAt(
      states: Array[AggState], argVecsByAgg: Array[Array[ColumnVector]], r: Int): Unit = {
    var a = 0
    while (a < aggCount) {
      states(a).updateAt(argVecsByAgg(a), r)
      a += 1
    }
  }

  /** Whole-batch update for the no-GROUP-BY fast path. Each state walks the
    * pre-evaluated arg vectors with the column-type pattern match hoisted
    * outside the row loop — one tight loop per state per batch. */
  private def updateStatesBatch(
      states: Array[AggState], argVecsByAgg: Array[Array[ColumnVector]], n: Int): Unit = {
    var a = 0
    while (a < aggCount) {
      states(a).updateBatchAt(argVecsByAgg(a), n)
      a += 1
    }
  }

  private def partialAggregate(p: Int, spillDir: Option[OperatorSpillDir]): PartialAgg = {
    val tStart = if (metricsNode != null) System.nanoTime() else 0L
    val map = new util.LinkedHashMap[AnyRef, Array[AggState]]()
    val spillFiles = mutable.ArrayBuffer.empty[Path]
    val threshold = if (spillDir.isDefined) spillThresholdBytes else Long.MaxValue
    var bytesSinceFlush: Long = 0L
    var peakMapSize: Long = 0L
    var peakBytes: Long = 0L
    val it = child.execute(p)
    val keyBuf: Array[Any] = if (keysAreColRefs) null else new Array[Any](nKeys)
    val keyVecsScratch: Array[ColumnVector] = if (keysAreColRefs) null else new Array[ColumnVector](nKeys)
    val argVecsCache = new ArgVecsCache()
    // Scratch key reused across the whole partition for ColRef GROUP-BY keys
    // (the common case). On a HashMap hit it stays uninserted; on a miss the
    // codec materializes an owned copy via `ownFromScratch` before insert.
    // For computed keys we still pay the per-row `encodeBoxed` allocation
    // because the codec's scratch API works off direct ColumnVector reads.
    val scratchKey: AnyRef = if (keysAreColRefs && groupKeys.nonEmpty) keyCodec.newScratch() else null
    while (it.hasNext) {
      val batch = it.next()
      val nrows = batch.numRows
      if (spillDir.isDefined) {
        val est = Spill.estimateBytes(batch)
        bytesSinceFlush += est
        if (bytesSinceFlush > peakBytes) peakBytes = bytesSinceFlush
      }
      if (nrows > 0) {
        val argVecsByAgg = argVecsCache.populate(batch)
        if (groupKeys.isEmpty) {
          // No-GROUP-BY fast path. Every row aggregates into the single
          // empty-key bucket — skip per-row encode + map lookup and walk
          // each state's arg vectors with the column-type pattern match
          // hoisted outside the row loop.
          val emptyKey = keyCodec.encodeBoxed(EmptyKeyBuf)
          var states = map.get(emptyKey)
          if (states == null) {
            states = newStates()
            map.put(emptyKey, states)
          }
          updateStatesBatch(states, argVecsByAgg, nrows)
        } else if (keysAreColRefs) {
          // ColRef + scratch-key fast path: encode in-place into a reusable
          // wrapper, probe the map without allocation. Only on a miss do we
          // clone the bytes/values into an owned key for insertion.
          var r = 0
          while (r < nrows) {
            keyCodec.encodeIntoScratch(scratchKey, batch, r)
            var states = map.get(scratchKey)
            if (states == null) {
              states = newStates()
              map.put(keyCodec.ownFromScratch(scratchKey), states)
            }
            updateStatesAt(states, argVecsByAgg, r)
            r += 1
          }
        } else {
          // GROUP BY computed keys: hoist the per-row `Expr.eval` calls
          // out by computing each key expression's whole-batch vector once
          // via `evalVec`, then reading the per-row boxed value into
          // `keyBuf`. The codec's scratch API doesn't help here (it reads
          // straight from raw ColumnVectors, not from precomputed boxed
          // values) so we still pay the per-row `encodeBoxed` allocation.
          var i = 0
          while (i < nKeys) { keyVecsScratch(i) = keyExprs(i).evalVec(batch); i += 1 }
          var r = 0
          while (r < nrows) {
            var k = 0
            while (k < nKeys) {
              val kv = keyVecsScratch(k)
              keyBuf(k) = if (kv.isNull(r)) null else kv.getBoxed(r)
              k += 1
            }
            val key = keyCodec.encodeBoxed(keyBuf)
            var states = map.get(key)
            if (states == null) {
              states = newStates()
              map.put(key, states)
            }
            updateStatesAt(states, argVecsByAgg, r)
            r += 1
          }
        }
      }
      // Track peak in-memory map size across the whole partition. Reset on
      // spill flush below — the metric is "peak resident size", not
      // "cumulative".
      val sz = map.size.toLong
      if (sz > peakMapSize) peakMapSize = sz
      if (bytesSinceFlush >= threshold && spillDir.isDefined && !map.isEmpty) {
        val file = spillDir.get.newSpillFile(".parquet")
        val stats = if (metricsNode == null) null else new AggStateSerde.SerdeStats
        AggSpiller.writeCodecMap(file, keyCodec, groupKeySchema, aggArr, map, stats)
        if (metricsNode != null) {
          val sb = file.toFile.length()
          metricsNode.counters(HashAggregateExec.IdxSpillEvents).increment()
          metricsNode.counters(HashAggregateExec.IdxBytesSpilled).add(sb)
          metricsNode.counters(HashAggregateExec.IdxSpillRunsWritten).increment()
          if (stats != null) metricsNode.counters(HashAggregateExec.IdxSerdeWriteNanos).add(stats.writeNanos)
        }
        map.clear()
        spillFiles += file
        bytesSinceFlush = 0L
        if (spillFiles.length > opts.spillMaxRuns)
          throw new RuntimeException(
            s"HashAggregateExec produced ${spillFiles.length} spill runs in partition $p, " +
            s"exceeding spill_max_runs=${opts.spillMaxRuns}.")
      }
    }
    if (metricsNode != null) {
      metricsNode.counters(HashAggregateExec.IdxPartialAggregateNanos).add(System.nanoTime() - tStart)
      // Use sumThenReset-style probe: peak is a max-reduction across partitions.
      // `LongAdder.max` doesn't exist; we accumulate the per-partition peak
      // into the counter so the consumer reads a sum-of-peaks (worst case per
      // partition). For peak-of-peaks across partitions, divide by partitions
      // or rely on per-partition observation (Sub-plan 1 reserved that).
      metricsNode.counters(HashAggregateExec.IdxHashMapPeakSize).add(peakMapSize)
      metricsNode.counters(HashAggregateExec.IdxPeakInMemoryBytes).add(peakBytes)
    }
    PartialAgg(map, spillFiles.toArray)
  }

  private def merge(
      a: util.LinkedHashMap[AnyRef, Array[AggState]],
      b: util.LinkedHashMap[AnyRef, Array[AggState]]): util.LinkedHashMap[AnyRef, Array[AggState]] = {
    val it = b.entrySet().iterator()
    while (it.hasNext) {
      val entry = it.next()
      val k = entry.getKey
      val bs = entry.getValue
      val existing = a.get(k)
      if (existing != null) {
        var i = 0
        while (i < existing.length) { existing(i).merge(bs(i)); i += 1 }
      } else {
        a.put(k, bs)
      }
    }
    a
  }

  private def emit(map: util.LinkedHashMap[AnyRef, Array[AggState]]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val iter = map.entrySet().iterator()
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = iter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(outputSchema, capacity)
        var row = 0
        while (row < capacity && iter.hasNext) {
          val entry = iter.next()
          val key = entry.getKey
          val states = entry.getValue
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

  // ---- Long-key path: single Int / Long / Date / Timestamp / Boolean ColRef -
  // Only reached from the per-shard path — `useLongKey` requires
  // `nKeys == 1`, which implies `groupKeys.nonEmpty`, which routes
  // `execute` into `executePerShard`. No cross-shard merge needed.

  private def partialAggregateLong(p: Int, spillDir: Option[OperatorSpillDir]): PartialAggLong = {
    val tStart = if (metricsNode != null) System.nanoTime() else 0L
    // var so we can swap in a fresh map after a spill flush (LongHashMap
    // has no `clear()` and grows in place).
    var map = new LongHashMap[Array[AggState]]()
    val spillFiles = mutable.ArrayBuffer.empty[Path]
    val threshold = if (spillDir.isDefined) spillThresholdBytes else Long.MaxValue
    var bytesSinceFlush: Long = 0L
    var peakMapSize: Long = 0L
    var peakBytes: Long = 0L
    val it = child.execute(p)
    val argVecsCache = new ArgVecsCache()
    while (it.hasNext) {
      val batch = it.next()
      val col = batch.column(longKeyColIdx)
      val nrows = batch.numRows
      if (spillDir.isDefined) {
        val est = Spill.estimateBytes(batch)
        bytesSinceFlush += est
        if (bytesSinceFlush > peakBytes) peakBytes = bytesSinceFlush
      }
      if (nrows > 0) {
        val argVecsByAgg = argVecsCache.populate(batch)
        var r = 0
        while (r < nrows) {
          // Inline get-then-put pattern — same rationale as the codec path
          // above: avoid the per-row Function0 allocation `getOrInsert` would
          // create from its by-name parameter.
          val states: Array[AggState] =
            if (col.isNull(r)) {
              var s = map.getNull
              if (s == null) { s = newStates(); map.putNull(s) }
              s
            } else {
              val k = KeyCodec.readAsLong(col, r)
              var s = map.get(k)
              if (s == null) { s = newStates(); map.put(k, s) }
              s
            }
          updateStatesAt(states, argVecsByAgg, r)
          r += 1
        }
      }
      val sz = map.size.toLong
      if (sz > peakMapSize) peakMapSize = sz
      if (bytesSinceFlush >= threshold && spillDir.isDefined && map.size > 0) {
        val file = spillDir.get.newSpillFile(".parquet")
        val stats = if (metricsNode == null) null else new AggStateSerde.SerdeStats
        AggSpiller.writeLongMap(file, groupKeySchema.fields(0), aggArr, map, stats)
        if (metricsNode != null) {
          val sb = file.toFile.length()
          metricsNode.counters(HashAggregateExec.IdxSpillEvents).increment()
          metricsNode.counters(HashAggregateExec.IdxBytesSpilled).add(sb)
          metricsNode.counters(HashAggregateExec.IdxSpillRunsWritten).increment()
          if (stats != null) metricsNode.counters(HashAggregateExec.IdxSerdeWriteNanos).add(stats.writeNanos)
        }
        map = new LongHashMap[Array[AggState]]()
        spillFiles += file
        bytesSinceFlush = 0L
        if (spillFiles.length > opts.spillMaxRuns)
          throw new RuntimeException(
            s"HashAggregateExec produced ${spillFiles.length} spill runs in partition $p (long key), " +
            s"exceeding spill_max_runs=${opts.spillMaxRuns}.")
      }
    }
    if (metricsNode != null) {
      metricsNode.counters(HashAggregateExec.IdxPartialAggregateNanos).add(System.nanoTime() - tStart)
      metricsNode.counters(HashAggregateExec.IdxHashMapPeakSize).add(peakMapSize)
      metricsNode.counters(HashAggregateExec.IdxPeakInMemoryBytes).add(peakBytes)
    }
    PartialAggLong(map, spillFiles.toArray)
  }

  private def emitLong(map: LongHashMap[Array[AggState]]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val iter = map.iterator
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = iter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(outputSchema, capacity)
        val keyCol = out.column(0)
        var row = 0
        while (row < capacity && iter.hasNext) {
          val (k, states) = iter.next()
          if (k == null) keyCol.setNull(row)
          else KeyCodec.writeLongTo(keyCol, row, k.longValue)
          var a = 0
          while (a < states.length) {
            val v = states(a).finish()
            val idx = 1 + a
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

object HashAggregateExec {
  // ---- Counter indices (Sub-plan 2 instrumentation) ------------------------
  // In-memory path counters: always populated when metrics enabled, regardless
  // of spill outcome.
  final val IdxGroupCount: Int             = 0
  final val IdxHashMapPeakSize: Int        = 1
  final val IdxKeyCodecPath: Int           = 2
  final val IdxMergeNanos: Int             = 3
  final val IdxPartialAggregateNanos: Int  = 4
  // Spill path counters: zero on sub-threshold workloads. Disjoint index range
  // so the in-memory + spill counter groups never collide.
  final val IdxSpillEvents: Int            = 5
  final val IdxBytesSpilled: Int           = 6
  final val IdxSpillRunsWritten: Int       = 7
  final val IdxSpillRunsRead: Int          = 8
  final val IdxSerdeWriteNanos: Int        = 9
  final val IdxSerdeReadNanos: Int         = 10
  final val IdxMergeFromSpillNanos: Int    = 11
  final val IdxPeakInMemoryBytes: Int      = 12

  /** Path codes written into the `keyCodecPath` counter. Consumers map back
    * to the named strategy via this table; the codes are stable across
    * releases. */
  final val KeyPathLong: Long         = 0L
  final val KeyPathPacked: Long       = 1L
  final val KeyPathObject: Long       = 2L
  final val KeyPathSingleObject: Long = 3L

  /** Name array parallel to the Idx* constants above. Length is asserted to
    * equal `highest Idx<Name> + 1` in `operator_counters_test.scala` so a
    * later counter add can't silently leave a gap. */
  val IdxCounterNames: Array[String] = Array(
    "groupCount",
    "hashMapPeakSize",
    "keyCodecPath",
    "mergeNanos",
    "partialAggregateNanos",
    "spillEvents",
    "bytesSpilled",
    "spillRunsWritten",
    "spillRunsRead",
    "serdeWriteNanos",
    "serdeReadNanos",
    "mergeFromSpillNanos",
    "peakInMemoryBytes")
}

// ---------------------------------------------------------------------------
// Per-aggregate state. Designed for partial + merge: each partition builds its own
// states; merge() combines them; finish() returns the final value.
//
// Two evaluation entry points:
//   - `updateAt(argVecs, row)` — fast path. The caller pre-evaluates each
//     aggregate's args once per batch (via `Expr.evalVec`) and the state reads
//     typed primitives directly off the vector. No per-row Expr.eval dispatch,
//     no boxing for primitive types. Used by `HashAggregateExec` (both the
//     GROUP BY path and the no-GROUP-BY whole-batch update).
//   - `update(agg, batch, row)` — RowBuf-driven path. The state evaluates the
//     aggregate's args itself via `agg.args(i).eval(batch, row)`. Used by
//     `WindowExec` which feeds 1-row `RowBuf` batches where vectorized
//     pre-eval gives nothing back.
// ---------------------------------------------------------------------------

sealed trait AggState {
  /** Pre-evaluated-args fast path. `argVecs(i)` is the vector for the i-th
    * arg of this state's underlying [[AggExpr]]; the caller populates it
    * once per batch via [[Expr.evalVec]]. The state reads typed primitives
    * directly from the vector. */
  def updateAt(argVecs: Array[ColumnVector], row: Int): Unit

  /** Whole-batch update over pre-evaluated args. Default loops per-row.
    * Primitive states override for a tight loop over a typed vector. */
  def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = {
    var r = 0
    while (r < n) { updateAt(argVecs, r); r += 1 }
  }

  /** Row-form update used by [[WindowExec]] (1-row RowBuf batches). The
    * state evaluates the aggregate's args itself via `agg.args(i).eval(...)`.
    * Default builds a 1-element argVecs from `agg.args.evalVec(batch)` and
    * delegates to [[updateAt]] — ColRef args alias the input column, so for
    * the common case no per-call allocation happens. */
  def update(agg: AggExpr, batch: ColumnarBatch, row: Int): Unit = {
    val args = agg.args
    val n = args.length
    val argVecs = new Array[ColumnVector](n)
    var i = 0
    val it = args.iterator
    while (i < n) {
      argVecs(i) = it.next().evalVec(batch)
      i += 1
    }
    updateAt(argVecs, row)
  }

  /** Whole-batch row-form update — only used by callers that don't pre-eval
    * args (none today other than legacy tests). Default falls back to per-row
    * via [[update]]. */
  def updateBatch(agg: AggExpr, batch: ColumnarBatch): Unit = {
    val n = batch.numRows
    var r = 0
    while (r < n) { update(agg, batch, r); r += 1 }
  }

  def merge(other: AggState): Unit
  def finish(): Any

  /** Write this state's internal accumulator fields to `out`. Used by
    * [[AggStateSerde]] when an operator spills a partial map to disk. The
    * default throws — only states the plan promises to support
    * (Count, Sum, Avg, Min, Max, CountIf, Moment, Covar, Corr) override.
    * `CountDistinctState` deliberately does not (HashSet round-trip is out
    * of scope per plan 09).
    *
    * The on-disk encoding is opaque to the operator — `writeSelf` /
    * `readSelf` are paired by type, never by external schema. Operators
    * may store the resulting bytes in any container (a Binary parquet
    * column, an inline buffer, etc). */
  private[exec] def writeSelf(out: DataOutput): Unit =
    throw new UnsupportedOperationException(s"${getClass.getSimpleName} cannot be spilled in v1")

  /** Mutate this state in place by reading fields written by [[writeSelf]].
    * Caller is responsible for constructing the right state subtype first
    * (typically via `AggState.init(agg)`) before invoking `readSelf`. */
  private[exec] def readSelf(in: DataInput): Unit =
    throw new UnsupportedOperationException(s"${getClass.getSimpleName} cannot be spilled in v1")
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
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = { c += 1 }
  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = { c += n }
  override def update(agg: AggExpr, b: ColumnarBatch, r: Int): Unit = { c += 1 }
  override def updateBatch(agg: AggExpr, b: ColumnarBatch): Unit = { c += b.numRows }
  def merge(o: AggState): Unit = c += o.asInstanceOf[CountStarState].c
  def finish(): Any = c

  override private[exec] def writeSelf(out: DataOutput): Unit = out.writeLong(c)
  override private[exec] def readSelf(in: DataInput): Unit = { c = in.readLong() }
}

final class CountState extends AggState {
  private var c: Long = 0L
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = {
    if (!argVecs(0).isNull(r)) c += 1
  }
  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = {
    val vec = argVecs(0)
    var r = 0
    while (r < n) { if (!vec.isNull(r)) c += 1; r += 1 }
  }
  def merge(o: AggState): Unit = c += o.asInstanceOf[CountState].c
  def finish(): Any = c

  override private[exec] def writeSelf(out: DataOutput): Unit = out.writeLong(c)
  override private[exec] def readSelf(in: DataInput): Unit = { c = in.readLong() }
}

final class CountDistinctState extends AggState {
  private val set = new util.HashSet[Any]()
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = {
    val vec = argVecs(0)
    if (!vec.isNull(r)) set.add(vec.getBoxed(r))
  }
  def merge(o: AggState): Unit = set.addAll(o.asInstanceOf[CountDistinctState].set)
  def finish(): Any = set.size.toLong
}

final class CountIfState extends AggState {
  private var c: Long = 0L
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = {
    argVecs(0) match {
      case bv: BooleanVector =>
        if (!bv.isNull(r) && bv.values(r)) c += 1
      case other =>
        if (!other.isNull(r) && other.getBoxed(r).asInstanceOf[Boolean]) c += 1
    }
  }
  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = {
    argVecs(0) match {
      case bv: BooleanVector =>
        var r = 0
        while (r < n) { if (!bv.isNull(r) && bv.values(r)) c += 1; r += 1 }
      case other =>
        var r = 0
        while (r < n) {
          if (!other.isNull(r) && other.getBoxed(r).asInstanceOf[Boolean]) c += 1
          r += 1
        }
    }
  }
  def merge(o: AggState): Unit = c += o.asInstanceOf[CountIfState].c
  def finish(): Any = c

  override private[exec] def writeSelf(out: DataOutput): Unit = out.writeLong(c)
  override private[exec] def readSelf(in: DataInput): Unit = { c = in.readLong() }
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
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = argVecs(0) match {
    case lv: LongVector =>
      if (!lv.isNull(r)) { sum += lv.values(r); sawAny = true }
    case iv: IntVector =>
      if (!iv.isNull(r)) { sum += iv.values(r).toLong; sawAny = true }
    case other =>
      if (!other.isNull(r)) {
        sum += other.getBoxed(r).asInstanceOf[Number].longValue
        sawAny = true
      }
  }
  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = argVecs(0) match {
    case lv: LongVector =>
      var r = 0
      while (r < n) {
        if (!lv.isNull(r)) { sum += lv.values(r); sawAny = true }
        r += 1
      }
    case iv: IntVector =>
      var r = 0
      while (r < n) {
        if (!iv.isNull(r)) { sum += iv.values(r).toLong; sawAny = true }
        r += 1
      }
    case other =>
      var r = 0
      while (r < n) {
        if (!other.isNull(r)) {
          sum += other.getBoxed(r).asInstanceOf[Number].longValue
          sawAny = true
        }
        r += 1
      }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[LongSumState]
    sum += that.sum
    sawAny = sawAny || that.sawAny
  }
  def finish(): Any = if (sawAny) sum else null

  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeBoolean(sawAny); out.writeLong(sum)
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    sawAny = in.readBoolean(); sum = in.readLong()
  }
}
final class DoubleSumState extends SumState {
  private var sum: Double = 0.0
  private var sawAny: Boolean = false
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = argVecs(0) match {
    case dv: DoubleVector =>
      if (!dv.isNull(r)) { sum += dv.values(r); sawAny = true }
    case fv: FloatVector =>
      if (!fv.isNull(r)) { sum += fv.values(r).toDouble; sawAny = true }
    case lv: LongVector =>
      if (!lv.isNull(r)) { sum += lv.values(r).toDouble; sawAny = true }
    case iv: IntVector =>
      if (!iv.isNull(r)) { sum += iv.values(r).toDouble; sawAny = true }
    case other =>
      if (!other.isNull(r)) {
        sum += other.getBoxed(r).asInstanceOf[Number].doubleValue
        sawAny = true
      }
  }
  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = argVecs(0) match {
    case dv: DoubleVector =>
      var r = 0
      while (r < n) {
        if (!dv.isNull(r)) { sum += dv.values(r); sawAny = true }
        r += 1
      }
    case fv: FloatVector =>
      var r = 0
      while (r < n) {
        if (!fv.isNull(r)) { sum += fv.values(r).toDouble; sawAny = true }
        r += 1
      }
    case lv: LongVector =>
      var r = 0
      while (r < n) {
        if (!lv.isNull(r)) { sum += lv.values(r).toDouble; sawAny = true }
        r += 1
      }
    case iv: IntVector =>
      var r = 0
      while (r < n) {
        if (!iv.isNull(r)) { sum += iv.values(r).toDouble; sawAny = true }
        r += 1
      }
    case other =>
      var r = 0
      while (r < n) {
        if (!other.isNull(r)) {
          sum += other.getBoxed(r).asInstanceOf[Number].doubleValue
          sawAny = true
        }
        r += 1
      }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[DoubleSumState]
    sum += that.sum
    sawAny = sawAny || that.sawAny
  }
  def finish(): Any = if (sawAny) sum else null

  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeBoolean(sawAny); out.writeDouble(sum)
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    sawAny = in.readBoolean(); sum = in.readDouble()
  }
}

final class AvgState extends AggState {
  private var sum: Double = 0.0
  private var count: Long = 0L
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = argVecs(0) match {
    case dv: DoubleVector =>
      if (!dv.isNull(r)) { sum += dv.values(r); count += 1 }
    case fv: FloatVector =>
      if (!fv.isNull(r)) { sum += fv.values(r).toDouble; count += 1 }
    case lv: LongVector =>
      if (!lv.isNull(r)) { sum += lv.values(r).toDouble; count += 1 }
    case iv: IntVector =>
      if (!iv.isNull(r)) { sum += iv.values(r).toDouble; count += 1 }
    case other =>
      if (!other.isNull(r)) {
        sum += other.getBoxed(r).asInstanceOf[Number].doubleValue
        count += 1
      }
  }
  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = argVecs(0) match {
    case dv: DoubleVector =>
      var r = 0
      while (r < n) {
        if (!dv.isNull(r)) { sum += dv.values(r); count += 1 }
        r += 1
      }
    case fv: FloatVector =>
      var r = 0
      while (r < n) {
        if (!fv.isNull(r)) { sum += fv.values(r).toDouble; count += 1 }
        r += 1
      }
    case lv: LongVector =>
      var r = 0
      while (r < n) {
        if (!lv.isNull(r)) { sum += lv.values(r).toDouble; count += 1 }
        r += 1
      }
    case iv: IntVector =>
      var r = 0
      while (r < n) {
        if (!iv.isNull(r)) { sum += iv.values(r).toDouble; count += 1 }
        r += 1
      }
    case other =>
      var r = 0
      while (r < n) {
        if (!other.isNull(r)) {
          sum += other.getBoxed(r).asInstanceOf[Number].doubleValue
          count += 1
        }
        r += 1
      }
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[AvgState]
    sum += that.sum
    count += that.count
  }
  def finish(): Any = if (count == 0L) null else sum / count

  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeLong(count); out.writeDouble(sum)
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    count = in.readLong(); sum = in.readDouble()
  }
}

final class MinMaxState(min: Boolean, dt: DataType) extends AggState {
  // Primitive specialization: track a typed running min/max in unboxed slots
  // to avoid the `java.lang.Long`/`Double`/`Integer` wrapper allocation the
  // older `current: Any` path created on every row that updated the extremum.
  // `currentBoxed` holds the boxed extremum ONLY for reference types
  // (String, Date, Decimal); for primitives it's null and the primitive
  // slots are the source of truth.
  private var hasValue: Boolean = false
  private var longCur: Long = 0L
  private var doubleCur: Double = 0.0
  private var currentBoxed: Any = null

  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = argVecs(0) match {
    case lv: LongVector =>
      if (!lv.isNull(r)) {
        val v = lv.values(r)
        if (!hasValue) { longCur = v; hasValue = true }
        else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
      }
    case iv: IntVector =>
      if (!iv.isNull(r)) {
        val v = iv.values(r).toLong
        if (!hasValue) { longCur = v; hasValue = true }
        else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
      }
    case dv: DoubleVector =>
      if (!dv.isNull(r)) {
        val v = dv.values(r)
        if (!hasValue) { doubleCur = v; hasValue = true }
        else if ((min && v < doubleCur) || (!min && v > doubleCur)) doubleCur = v
      }
    case fv: FloatVector =>
      if (!fv.isNull(r)) {
        val v = fv.values(r).toDouble
        if (!hasValue) { doubleCur = v; hasValue = true }
        else if ((min && v < doubleCur) || (!min && v > doubleCur)) doubleCur = v
      }
    case tv: TimestampVector =>
      if (!tv.isNull(r)) {
        val v = tv.values(r)
        if (!hasValue) { longCur = v; hasValue = true }
        else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
      }
    case dv: DateVector =>
      if (!dv.isNull(r)) {
        val v = dv.values(r).toLong
        if (!hasValue) { longCur = v; hasValue = true }
        else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
      }
    case other =>
      if (!other.isNull(r)) {
        val v = other.getBoxed(r)
        if (!hasValue) { currentBoxed = v; hasValue = true }
        else {
          val c = Ops.cmp(v, currentBoxed)
          if ((min && c < 0) || (!min && c > 0)) currentBoxed = v
        }
      }
  }

  override def updateBatchAt(argVecs: Array[ColumnVector], n: Int): Unit = argVecs(0) match {
    case lv: LongVector =>
      var r = 0
      while (r < n) {
        if (!lv.isNull(r)) {
          val v = lv.values(r)
          if (!hasValue) { longCur = v; hasValue = true }
          else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
        }
        r += 1
      }
    case iv: IntVector =>
      var r = 0
      while (r < n) {
        if (!iv.isNull(r)) {
          val v = iv.values(r).toLong
          if (!hasValue) { longCur = v; hasValue = true }
          else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
        }
        r += 1
      }
    case dv: DoubleVector =>
      var r = 0
      while (r < n) {
        if (!dv.isNull(r)) {
          val v = dv.values(r)
          if (!hasValue) { doubleCur = v; hasValue = true }
          else if ((min && v < doubleCur) || (!min && v > doubleCur)) doubleCur = v
        }
        r += 1
      }
    case fv: FloatVector =>
      var r = 0
      while (r < n) {
        if (!fv.isNull(r)) {
          val v = fv.values(r).toDouble
          if (!hasValue) { doubleCur = v; hasValue = true }
          else if ((min && v < doubleCur) || (!min && v > doubleCur)) doubleCur = v
        }
        r += 1
      }
    case tv: TimestampVector =>
      var r = 0
      while (r < n) {
        if (!tv.isNull(r)) {
          val v = tv.values(r)
          if (!hasValue) { longCur = v; hasValue = true }
          else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
        }
        r += 1
      }
    case dv: DateVector =>
      var r = 0
      while (r < n) {
        if (!dv.isNull(r)) {
          val v = dv.values(r).toLong
          if (!hasValue) { longCur = v; hasValue = true }
          else if ((min && v < longCur) || (!min && v > longCur)) longCur = v
        }
        r += 1
      }
    case other =>
      var r = 0
      while (r < n) {
        if (!other.isNull(r)) {
          val v = other.getBoxed(r)
          if (!hasValue) { currentBoxed = v; hasValue = true }
          else {
            val c = Ops.cmp(v, currentBoxed)
            if ((min && c < 0) || (!min && c > 0)) currentBoxed = v
          }
        }
        r += 1
      }
  }

  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[MinMaxState]
    if (!that.hasValue) return
    if (!hasValue) {
      hasValue = true
      longCur = that.longCur
      doubleCur = that.doubleCur
      currentBoxed = that.currentBoxed
    } else {
      // Pick the right slot based on the *result type* dt. Primitives
      // carry their value in longCur/doubleCur; reference types use
      // currentBoxed. This keeps merge boxing-free for primitives.
      dt match {
        case DataType.IntType | DataType.LongType | DataType.DateType | DataType.TimestampType =>
          val v = that.longCur
          if ((min && v < longCur) || (!min && v > longCur)) longCur = v
        case DataType.FloatType | DataType.DoubleType =>
          val v = that.doubleCur
          if ((min && v < doubleCur) || (!min && v > doubleCur)) doubleCur = v
        case _ =>
          val v = that.currentBoxed
          val c = Ops.cmp(v, currentBoxed)
          if ((min && c < 0) || (!min && c > 0)) currentBoxed = v
      }
    }
  }
  def finish(): Any =
    if (!hasValue) null
    else dt match {
      case DataType.IntType => java.lang.Integer.valueOf(longCur.toInt)
      case DataType.LongType => java.lang.Long.valueOf(longCur)
      case DataType.DateType => java.lang.Integer.valueOf(longCur.toInt)
      case DataType.TimestampType => java.lang.Long.valueOf(longCur)
      case DataType.FloatType => java.lang.Float.valueOf(doubleCur.toFloat)
      case DataType.DoubleType => java.lang.Double.valueOf(doubleCur)
      case _ => currentBoxed
    }

  // Spill SerDe. Encoded shape depends on `dt`:
  //   hasValue (boolean) -> if false, that's the whole record.
  //   Otherwise:
  //     - Int/Long/Date/Timestamp/Boolean: longCur (long)
  //     - Float/Double: doubleCur (double)
  //     - reference types (String/Binary/Decimal/Null): nullable boxed
  //       payload — encoded by type.
  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeBoolean(hasValue)
    if (hasValue) dt match {
      case DataType.IntType | DataType.LongType | DataType.DateType
         | DataType.TimestampType | DataType.BooleanType =>
        out.writeLong(longCur)
      case DataType.FloatType | DataType.DoubleType =>
        out.writeDouble(doubleCur)
      case _ => MinMaxState.writeBoxed(out, dt, currentBoxed)
    }
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    hasValue = in.readBoolean()
    if (hasValue) dt match {
      case DataType.IntType | DataType.LongType | DataType.DateType
         | DataType.TimestampType | DataType.BooleanType =>
        longCur = in.readLong()
      case DataType.FloatType | DataType.DoubleType =>
        doubleCur = in.readDouble()
      case _ => currentBoxed = MinMaxState.readBoxed(in, dt)
    }
  }
}

object MinMaxState {
  /** Reference-type serialization for `currentBoxed`. The boxed value is
    * never `null` once `hasValue == true` — every state class only flips
    * `hasValue` after observing a non-null input — but the serializer is
    * defensive: a one-byte `nonNull` flag precedes each payload. */
  private[exec] def writeBoxed(out: DataOutput, dt: DataType, v: Any): Unit = {
    if (v == null) { out.writeBoolean(false); return }
    out.writeBoolean(true)
    dt match {
      case DataType.StringType =>
        val s = v.asInstanceOf[String]
        val bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8)
        out.writeInt(bytes.length); out.write(bytes)
      case DataType.BinaryType =>
        val a = v.asInstanceOf[Array[Byte]]
        out.writeInt(a.length); out.write(a)
      case _: DataType.DecimalType =>
        val bd = v.asInstanceOf[java.math.BigDecimal]
        val unscaled = bd.unscaledValue.toByteArray
        out.writeInt(unscaled.length); out.write(unscaled)
        out.writeInt(bd.scale)
      case DataType.NullType =>
        () // no payload — the type's only inhabitant is null, and we already wrote nonNull=true defensively
      case other =>
        throw new UnsupportedOperationException(
          s"MinMaxState cannot serialize reference-typed value of $other")
    }
  }

  private[exec] def readBoxed(in: DataInput, dt: DataType): Any = {
    if (!in.readBoolean()) return null
    dt match {
      case DataType.StringType =>
        val n = in.readInt()
        val bytes = new Array[Byte](n); in.readFully(bytes)
        new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
      case DataType.BinaryType =>
        val n = in.readInt()
        val a = new Array[Byte](n); in.readFully(a)
        a
      case _: DataType.DecimalType =>
        val n = in.readInt()
        val unscaled = new Array[Byte](n); in.readFully(unscaled)
        val scale = in.readInt()
        new java.math.BigDecimal(new java.math.BigInteger(unscaled), scale)
      case DataType.NullType => null
      case other =>
        throw new UnsupportedOperationException(
          s"MinMaxState cannot deserialize reference-typed value of $other")
    }
  }
}

/** Univariate variance / standard deviation via Welford + Chan's parallel
  * merge. Numerically stable vs. the naive sum-of-squares formula and works
  * across partial-aggregation boundaries. */
final class MomentState(sample: Boolean, stddev: Boolean) extends AggState {
  private var n: Long = 0L
  private var mean: Double = 0.0
  private var m2: Double = 0.0
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = argVecs(0) match {
    case dv: DoubleVector =>
      if (!dv.isNull(r)) ingest(dv.values(r))
    case fv: FloatVector =>
      if (!fv.isNull(r)) ingest(fv.values(r).toDouble)
    case lv: LongVector =>
      if (!lv.isNull(r)) ingest(lv.values(r).toDouble)
    case iv: IntVector =>
      if (!iv.isNull(r)) ingest(iv.values(r).toDouble)
    case other =>
      if (!other.isNull(r)) ingest(other.getBoxed(r).asInstanceOf[Number].doubleValue)
  }
  private def ingest(x: Double): Unit = {
    n += 1
    val delta = x - mean
    mean += delta / n
    m2 += delta * (x - mean)
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

  // `sample` / `stddev` flags come from the AggExpr at construction time
  // (AggState.init) and never change — no need to round-trip them.
  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeLong(n); out.writeDouble(mean); out.writeDouble(m2)
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    n = in.readLong(); mean = in.readDouble(); m2 = in.readDouble()
  }
}

/** Bivariate covariance via the parallel Welford / Pébay update. Pairs are
  * skipped when either side is NULL. */
final class CovarState(sample: Boolean) extends AggState {
  protected var n: Long = 0L
  protected var meanX: Double = 0.0
  protected var meanY: Double = 0.0
  protected var cAcc: Double = 0.0
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = {
    val vxCol = argVecs(0)
    val vyCol = argVecs(1)
    if (!vxCol.isNull(r) && !vyCol.isNull(r)) {
      val x = readDouble(vxCol, r)
      val y = readDouble(vyCol, r)
      n += 1
      val dx = x - meanX
      meanX += dx / n
      val dy = y - meanY
      meanY += dy / n
      cAcc += dx * (y - meanY)
    }
  }
  private def readDouble(c: ColumnVector, r: Int): Double = c match {
    case dv: DoubleVector => dv.values(r)
    case fv: FloatVector => fv.values(r).toDouble
    case lv: LongVector => lv.values(r).toDouble
    case iv: IntVector => iv.values(r).toDouble
    case other => other.getBoxed(r).asInstanceOf[Number].doubleValue
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[CovarState]
    if (that.n == 0L) return
    if (n == 0L) {
      n = that.n; meanX = that.meanX; meanY = that.meanY; cAcc = that.cAcc
    } else {
      val newN = n + that.n
      val dx = that.meanX - meanX
      val dy = that.meanY - meanY
      cAcc = cAcc + that.cAcc + dx * dy * n.toDouble * that.n.toDouble / newN.toDouble
      meanX = meanX + dx * that.n.toDouble / newN.toDouble
      meanY = meanY + dy * that.n.toDouble / newN.toDouble
      n = newN
    }
  }
  def finish(): Any =
    if (sample) { if (n < 2L) null else cAcc / (n - 1).toDouble }
    else { if (n < 1L) null else cAcc / n.toDouble }

  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeLong(n); out.writeDouble(meanX); out.writeDouble(meanY); out.writeDouble(cAcc)
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    n = in.readLong(); meanX = in.readDouble(); meanY = in.readDouble(); cAcc = in.readDouble()
  }
}

/** Pearson correlation: tracks the same partial sums as covariance plus
  * second moments for x and y to normalize. */
final class CorrState extends AggState {
  private var n: Long = 0L
  private var meanX: Double = 0.0
  private var meanY: Double = 0.0
  private var m2x: Double = 0.0
  private var m2y: Double = 0.0
  private var cAcc: Double = 0.0
  def updateAt(argVecs: Array[ColumnVector], r: Int): Unit = {
    val vxCol = argVecs(0)
    val vyCol = argVecs(1)
    if (!vxCol.isNull(r) && !vyCol.isNull(r)) {
      val x = readDouble(vxCol, r)
      val y = readDouble(vyCol, r)
      n += 1
      val dx = x - meanX
      val dy = y - meanY
      meanX += dx / n
      meanY += dy / n
      val dx2 = x - meanX
      val dy2 = y - meanY
      m2x += dx * dx2
      m2y += dy * dy2
      cAcc += dx * dy2
    }
  }
  private def readDouble(c: ColumnVector, r: Int): Double = c match {
    case dv: DoubleVector => dv.values(r)
    case fv: FloatVector => fv.values(r).toDouble
    case lv: LongVector => lv.values(r).toDouble
    case iv: IntVector => iv.values(r).toDouble
    case other => other.getBoxed(r).asInstanceOf[Number].doubleValue
  }
  def merge(o: AggState): Unit = {
    val that = o.asInstanceOf[CorrState]
    if (that.n == 0L) return
    if (n == 0L) {
      n = that.n; meanX = that.meanX; meanY = that.meanY
      m2x = that.m2x; m2y = that.m2y; cAcc = that.cAcc
    } else {
      val newN = n + that.n
      val dx = that.meanX - meanX
      val dy = that.meanY - meanY
      val na = n.toDouble; val nb = that.n.toDouble; val nc = newN.toDouble
      m2x = m2x + that.m2x + dx * dx * na * nb / nc
      m2y = m2y + that.m2y + dy * dy * na * nb / nc
      cAcc = cAcc + that.cAcc + dx * dy * na * nb / nc
      meanX = meanX + dx * nb / nc
      meanY = meanY + dy * nb / nc
      n = newN
    }
  }
  def finish(): Any = {
    if (n < 2L) null
    else {
      val denom = math.sqrt(m2x * m2y)
      if (denom == 0.0) null else cAcc / denom
    }
  }

  override private[exec] def writeSelf(out: DataOutput): Unit = {
    out.writeLong(n)
    out.writeDouble(meanX); out.writeDouble(meanY)
    out.writeDouble(m2x); out.writeDouble(m2y); out.writeDouble(cAcc)
  }
  override private[exec] def readSelf(in: DataInput): Unit = {
    n = in.readLong()
    meanX = in.readDouble(); meanY = in.readDouble()
    m2x = in.readDouble(); m2y = in.readDouble(); cAcc = in.readDouble()
  }
}

// ---------------------------------------------------------------------------
// Spill support (plan 09 Phase 4). The PartialAgg / PartialAggLong wrappers
// carry both the in-memory tail of a partition's aggregation AND any spill
// files written mid-stream. AggSpiller is the read/write boundary for those
// spill files; the per-file format is one parquet file with
// (groupKey-columns ++ N Binary columns of serialized state).
// ---------------------------------------------------------------------------

private[exec] final case class PartialAgg(
    map: java.util.LinkedHashMap[AnyRef, Array[AggState]],
    spillFiles: Array[Path])

private[exec] final case class PartialAggLong(
    map: LongHashMap[Array[AggState]],
    spillFiles: Array[Path])

private[exec] object AggSpiller {

  /** Spill file schema: groupKey columns + N Binary columns, one per agg.
    * Aggregate columns are named `_agg0`/`_agg1`/... so they don't collide
    * with user-chosen aliases on the group keys. */
  def spillSchema(groupKeySchema: Schema, numAggs: Int): Schema = {
    val stateFields = (0 until numAggs).map(i => Field(s"_agg$i", DataType.BinaryType))
    Schema(groupKeySchema.fields ++ stateFields.toVector)
  }

  // ---- Codec map (LinkedHashMap[AnyRef, Array[AggState]]) ------------------

  def writeCodecMap(
      file: Path,
      codec: KeyCodec,
      groupKeySchema: Schema,
      aggs: Array[AggExpr],
      map: java.util.LinkedHashMap[AnyRef, Array[AggState]]): Unit =
    writeCodecMap(file, codec, groupKeySchema, aggs, map, null)

  /** Instrumented variant: timings (when `stats != null`) flow into
    * `stats.writeNanos`. Bytes written are unchanged; only the caller's
    * counters see a difference. */
  def writeCodecMap(
      file: Path,
      codec: KeyCodec,
      groupKeySchema: Schema,
      aggs: Array[AggExpr],
      map: java.util.LinkedHashMap[AnyRef, Array[AggState]],
      stats: AggStateSerde.SerdeStats): Unit = {
    if (map.isEmpty) return
    val outSchema = spillSchema(groupKeySchema, aggs.length)
    val nKeys = groupKeySchema.length
    val nAggs = aggs.length
    val capacity = ColumnarBatch.DefaultCapacity
    val entryIter = map.entrySet().iterator()
    val batchIter = new Iterator[ColumnarBatch] {
      def hasNext: Boolean = entryIter.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(outSchema, capacity)
        var r = 0
        while (r < capacity && entryIter.hasNext) {
          val entry = entryIter.next()
          codec.decode(entry.getKey, out, 0, r)
          val states = entry.getValue
          var a = 0
          while (a < nAggs) {
            val bytes = AggStateSerde.serializeBytes(states(a), stats)
            out.column(nKeys + a).asInstanceOf[BinaryVector].set(r, bytes)
            a += 1
          }
          r += 1
        }
        out.setNumRows(r)
        out
      }
    }
    ParquetWriter.writeAll(file, outSchema, batchIter)
  }

  /** Fold every spill file's entries into `target`. For each row in each
    * spill file, decode the key via `codec.encodeBoxed` (uniform across the
    * ColRef/computed-key axis since the spill file's key columns are at
    * indices [0, nKeys)) and either merge into an existing entry or insert
    * a freshly-constructed state array. */
  def foldCodecSpillFiles(
      files: Array[Path],
      codec: KeyCodec,
      nKeys: Int,
      aggs: Array[AggExpr],
      target: java.util.LinkedHashMap[AnyRef, Array[AggState]]): Unit =
    foldCodecSpillFiles(files, codec, nKeys, aggs, target, null)

  /** Instrumented variant: deserialize time flows into `stats.readNanos`. */
  def foldCodecSpillFiles(
      files: Array[Path],
      codec: KeyCodec,
      nKeys: Int,
      aggs: Array[AggExpr],
      target: java.util.LinkedHashMap[AnyRef, Array[AggState]],
      stats: AggStateSerde.SerdeStats): Unit = {
    if (files.isEmpty) return
    val nAggs = aggs.length
    val keyBuf = new Array[Any](nKeys)
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
            var k = 0
            while (k < nKeys) {
              val col = batch.column(k)
              keyBuf(k) = if (col.isNull(r)) null else col.getBoxed(r)
              k += 1
            }
            val key = codec.encodeBoxed(keyBuf)
            val existing = target.get(key)
            if (existing != null) {
              var a = 0
              while (a < nAggs) {
                val bytes = batch.column(nKeys + a).asInstanceOf[BinaryVector].get(r)
                val incoming = AggStateSerde.deserializeBytes(aggs(a), bytes, stats)
                existing(a).merge(incoming)
                a += 1
              }
            } else {
              val states = new Array[AggState](nAggs)
              var a = 0
              while (a < nAggs) {
                val bytes = batch.column(nKeys + a).asInstanceOf[BinaryVector].get(r)
                states(a) = AggStateSerde.deserializeBytes(aggs(a), bytes, stats)
                a += 1
              }
              target.put(key, states)
            }
            r += 1
          }
        }
        part += 1
      }
      f += 1
    }
  }

  // ---- Long map (LongHashMap[Array[AggState]]) -----------------------------

  def writeLongMap(
      file: Path,
      keyField: Field,
      aggs: Array[AggExpr],
      map: LongHashMap[Array[AggState]]): Unit =
    writeLongMap(file, keyField, aggs, map, null)

  /** Instrumented variant: serialize time flows into `stats.writeNanos`. */
  def writeLongMap(
      file: Path,
      keyField: Field,
      aggs: Array[AggExpr],
      map: LongHashMap[Array[AggState]],
      stats: AggStateSerde.SerdeStats): Unit = {
    if (map.isEmpty) return
    val outSchema = spillSchema(Schema(Vector(keyField)), aggs.length)
    val nAggs = aggs.length
    val capacity = ColumnarBatch.DefaultCapacity
    val it = map.iterator
    val batchIter = new Iterator[ColumnarBatch] {
      def hasNext: Boolean = it.hasNext
      def next(): ColumnarBatch = {
        val out = new ColumnarBatch(outSchema, capacity)
        val keyCol = out.column(0)
        var r = 0
        while (r < capacity && it.hasNext) {
          val (boxedKey, states) = it.next()
          if (boxedKey == null) keyCol.setNull(r)
          else KeyCodec.writeLongTo(keyCol, r, boxedKey.longValue)
          var a = 0
          while (a < nAggs) {
            val bytes = AggStateSerde.serializeBytes(states(a), stats)
            out.column(1 + a).asInstanceOf[BinaryVector].set(r, bytes)
            a += 1
          }
          r += 1
        }
        out.setNumRows(r)
        out
      }
    }
    ParquetWriter.writeAll(file, outSchema, batchIter)
  }

  def foldLongSpillFiles(
      files: Array[Path],
      aggs: Array[AggExpr],
      target: LongHashMap[Array[AggState]]): Unit =
    foldLongSpillFiles(files, aggs, target, null)

  /** Instrumented variant: deserialize time flows into `stats.readNanos`. */
  def foldLongSpillFiles(
      files: Array[Path],
      aggs: Array[AggExpr],
      target: LongHashMap[Array[AggState]],
      stats: AggStateSerde.SerdeStats): Unit = {
    if (files.isEmpty) return
    val nAggs = aggs.length
    var f = 0
    while (f < files.length) {
      val reader = ParquetReader.fromPath(files(f).toString)
      var part = 0
      while (part < reader.numPartitions) {
        val batchIt = reader.readPartition(part)
        while (batchIt.hasNext) {
          val batch = batchIt.next()
          val keyCol = batch.column(0)
          var r = 0
          while (r < batch.numRows) {
            val isNull = keyCol.isNull(r)
            val k = if (isNull) 0L else KeyCodec.readAsLong(keyCol, r)
            val existing: Array[AggState] =
              if (isNull) target.getNull else target.get(k)
            if (existing != null) {
              var a = 0
              while (a < nAggs) {
                val bytes = batch.column(1 + a).asInstanceOf[BinaryVector].get(r)
                val incoming = AggStateSerde.deserializeBytes(aggs(a), bytes, stats)
                existing(a).merge(incoming)
                a += 1
              }
            } else {
              val states = new Array[AggState](nAggs)
              var a = 0
              while (a < nAggs) {
                val bytes = batch.column(1 + a).asInstanceOf[BinaryVector].get(r)
                states(a) = AggStateSerde.deserializeBytes(aggs(a), bytes, stats)
                a += 1
              }
              if (isNull) target.putNull(states) else target.put(k, states)
            }
            r += 1
          }
        }
        part += 1
      }
      f += 1
    }
  }
}
