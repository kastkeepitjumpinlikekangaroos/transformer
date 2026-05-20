package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}

import java.nio.file.Path
import java.util
import java.util.concurrent.Callable
import scala.collection.mutable

/** Hash join.
  *
  * One side is the build side: materialized into row arrays plus a multi-map
  * from join key to row indices. The other side is the probe side: streamed.
  *
  * Equi-join only — the planner extracts equality conjuncts from the JOIN ON
  * condition (`a.x = b.y AND …`). Other predicates become `extra`, applied
  * after the per-key match. Joins with no equality conjuncts degenerate to a
  * cartesian product (every probe row matches the single empty-key bucket
  * holding every build row) — see [[PhysicalPlanner]]'s size guard for the
  * cutoff above which that's rejected.
  *
  * Two execution modes share this operator:
  *
  *   - **Per-shard** (equi-join, `leftKeys` non-empty): the planner has
  *     wrapped both sides in [[ExchangeExec]]s that hash-partition by the
  *     respective join keys with the same K. Same-key rows land in the same
  *     shard on both sides, so each shard builds + probes independently with
  *     no cross-shard coordination. Outer-join unmatched-side emission is
  *     also per-shard — a build row in shard `s` that wasn't matched in
  *     shard `s` truly has no match anywhere (a matching probe row would
  *     have hashed to `s` as well). `numPartitions = K`.
  *   - **Collapsing** (non-equi join, both key lists empty): no exchange.
  *     The build side fans out across `Scheduler` partitions, accumulates
  *     into one global keymap (which holds the lone empty-key bucket), and
  *     the probe side fans out and tests every row against it. `numPartitions = 1`.
  *     The size guard at [[PhysicalPlanner.enforceNestedLoopGuard]] keeps
  *     this path off truly large inputs.
  *
  * `buildRight` selects which side is built:
  *   - `true` (default): right is built, left is probed. Cheapest when right
  *     is smaller — the historic shape.
  *   - `false`: left is built, right is probed. The planner picks this when
  *     `LogicalPlanCardinality.estimate(left)` is materially below `estimate(right)`
  *     (so the smaller side ends up in the hash) or when the join kind is
  *     RIGHT outer (so the preserved side stays the streaming probe). Output
  *     schema and column order are unchanged — `left.outputSchema ++ right.outputSchema`
  *     either way — so downstream operators don't see the swap.
  *
  * Invariant: when `leftKeys.nonEmpty`, both `left` and `right` must be
  * partitioned by the equi-join key set with the same K (the planner enforces
  * this by wrapping both children in `ExchangeExec`s with matching K and the
  * pinned [[HashPartitioner]] hash). Direct construction over non-partitioned
  * children produces wrong results — same-key rows would land in different
  * shards and the per-shard probe would miss matches.
  */
final case class HashJoinExec(
    left: PhysicalPlan,
    right: PhysicalPlan,
    leftKeys: Seq[Expr],
    rightKeys: Seq[Expr],
    extra: Option[Expr],
    kind: JoinKind,
    buildRight: Boolean = true,
    opts: ExecutionOptions = ExecutionOptions.Default,
    metricsNode: MetricsNode = null
) extends PhysicalPlan {

  val outputSchema: Schema = Schema(left.outputSchema.fields ++ right.outputSchema.fields)

  private val nLeftCols = left.outputSchema.length
  private val nRightCols = right.outputSchema.length
  private val nCols = nLeftCols + nRightCols

  private val probeIsLeft: Boolean = buildRight
  private val buildIsLeft: Boolean = !buildRight

  private val preserveBuildOuter: Boolean =
    if (buildIsLeft) kind == JoinKind.Left || kind == JoinKind.Full
    else kind == JoinKind.Right || kind == JoinKind.Full

  private val preserveProbeOuter: Boolean =
    if (probeIsLeft) kind == JoinKind.Left || kind == JoinKind.Full
    else kind == JoinKind.Right || kind == JoinKind.Full

  private val buildPlan: PhysicalPlan = if (buildRight) right else left
  private val probePlan: PhysicalPlan = if (buildRight) left else right
  private val buildKeys: Seq[Expr]    = if (buildRight) rightKeys else leftKeys
  private val probeKeys: Seq[Expr]    = if (buildRight) leftKeys else rightKeys

  // Key encoding. Both sides share one codec (its bit/byte layout depends only
  // on key TYPES, not column positions). The codec's bound indices are set up
  // for the probe side because that's the side that uses [[KeyCodec.encodeFromBatch]];
  // the build side reads from already-materialized `Array[Any]` rows and goes
  // through [[KeyCodec.encodeBoxed]] (indices unused).
  private val nKeys: Int = buildKeys.length
  private val keyTypes: Array[DataType] = buildKeys.iterator.map(_.dataType).toArray
  private val buildKeyExprs: Array[Expr] = buildKeys.toArray
  private val probeKeyExprs: Array[Expr] = probeKeys.toArray
  private val buildKeysAreColRefs: Boolean = buildKeyExprs.forall(_.isInstanceOf[ColRefExpr])
  private val probeKeysAreColRefs: Boolean = probeKeyExprs.forall(_.isInstanceOf[ColRefExpr])
  private val buildKeyColIndices: Array[Int] =
    if (buildKeysAreColRefs) buildKeyExprs.map(_.asInstanceOf[ColRefExpr].index) else null
  private val probeKeyColIndices: Array[Int] =
    if (probeKeysAreColRefs) probeKeyExprs.map(_.asInstanceOf[ColRefExpr].index) else null
  private val keyCodec: KeyCodec = {
    val indices =
      if (probeKeysAreColRefs) probeKeyColIndices
      else new Array[Int](nKeys)
    KeyCodec.forColumns(indices, keyTypes)
  }

  // Fast path: single fixed-width-numeric-fittable ColRef join key on BOTH
  // sides → swap out the AnyRef keymap for [[LongHashMap]]. Probe-side reads
  // the primitive long straight from the source `ColumnVector` (no boxing),
  // build-side unboxes once from the materialized `Array[Any]` row.
  private val useJoinLongKey: Boolean =
    nKeys == 1 &&
    buildKeysAreColRefs && probeKeysAreColRefs &&
    KeyCodec.isLongFittable(keyTypes(0))
  private val buildKeyLongIdx: Int =
    if (useJoinLongKey) buildKeyColIndices(0) else -1
  private val probeKeyLongIdx: Int =
    if (useJoinLongKey) probeKeyColIndices(0) else -1

  /** True when the planner has wrapped both sides in `ExchangeExec`s. The
    * planner does this for equi-joins whose smaller side is above
    * [[com.transformer.sql.plan.LogicalPlanCardinality.BroadcastBuildThreshold]]
    * — below that threshold, even equi-joins go through the collapsing path
    * (build small side, stream-probe the large one) because shuffling the
    * large probe just to redistribute rows costs more than the join itself.
    *
    * Detected via child type rather than a flag so direct construction over
    * non-exchange children (existing tests, future callers) still works. */
  private val isSharded: Boolean =
    left.isInstanceOf[ExchangeExec] && right.isInstanceOf[ExchangeExec]

  def numPartitions: Int = if (isSharded) left.numPartitions else 1

  /** Grace hash join is enabled when:
    *   - the user opted in via `output.json -> options.spill=true`, AND
    *   - the join has equi-keys (`nKeys > 0`).
    *
    * Non-equi joins (the cartesian-shaped collapsing path) don't qualify
    * because there's no key to hash-bucket on; their size guard is
    * [[PhysicalPlanner.enforceNestedLoopGuard]] above the planner. */
  private val graceEnabled: Boolean = opts.spillEnabled && nKeys > 0

  // Stamp the constant key-codec path code into the metrics node once at
  // construction. See [[HashJoinExec]] companion for the path codes.
  private val keyCodecPathCode: Long = {
    if (useJoinLongKey) HashJoinExec.KeyPathLong
    else if (nKeys == 0) HashJoinExec.KeyPathObject // non-equi (cartesian) — no codec
    else if (keyTypes.forall(KeyCodec.isPackable)) HashJoinExec.KeyPathPacked
    else HashJoinExec.KeyPathObject
  }
  if (metricsNode != null) {
    metricsNode.counters(HashJoinExec.IdxKeyCodecPath).add(keyCodecPathCode)
  }

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (graceEnabled) executeGraceHash(partition)
    else if (isSharded) executePerShard(partition)
    else executeCollapsing(partition)
  }

  // ---- Per-shard path ------------------------------------------------------

  private def executePerShard(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numPartitions,
      s"partition $partition out of range [0,$numPartitions)")
    val tBuild = if (metricsNode != null) System.nanoTime() else 0L
    val build = buildSideForShard(partition)
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxBuildNanos).add(System.nanoTime() - tBuild)
      metricsNode.counters(HashJoinExec.IdxBuildSideRows).add(build.rows.length.toLong)
    }
    val matchedBuild: util.HashSet[Int] =
      if (preserveBuildOuter) new util.HashSet[Int]() else null
    val tProbe = if (metricsNode != null) System.nanoTime() else 0L
    val joined = probeShard(partition, build, matchedBuild)
    if (metricsNode != null) metricsNode.counters(HashJoinExec.IdxProbeNanos).add(System.nanoTime() - tProbe)
    val unmatchedTail: Iterator[ColumnarBatch] =
      if (matchedBuild != null) emitUnmatchedBuild(build, matchedBuild)
      else Iterator.empty
    joined ++ unmatchedTail
  }

  private def buildSideForShard(p: Int): BuildSide = {
    val part = collectBuildPartition(p)
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    rows ++= part.rows
    if (useJoinLongKey) buildLongKeyMap(rows)
    else buildCodecKeyMap(rows, part.keys)
  }

  private def probeShard(
      partition: Int,
      build: BuildSide,
      matchedBuild: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val (rows, matched) = probeOnePartition(partition, build)
    if (matchedBuild != null) matchedBuild.addAll(matched)
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxProbeSideRows).add(rows.length.toLong)
      metricsNode.counters(HashJoinExec.IdxMatchedRows).add(matched.size.toLong)
    }
    rowsToBatches(rows.toArray, outputSchema, ColumnarBatch.DefaultCapacity)
  }

  // ---- Collapsing path (non-equi joins) ------------------------------------

  private def executeCollapsing(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0,
      s"non-equi join collapses to one partition; got partition=$partition")
    val tBuild = if (metricsNode != null) System.nanoTime() else 0L
    val build = buildSideAcrossAllPartitions()
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxBuildNanos).add(System.nanoTime() - tBuild)
      metricsNode.counters(HashJoinExec.IdxBuildSideRows).add(build.rows.length.toLong)
    }
    val matchedBuild: util.HashSet[Int] =
      if (preserveBuildOuter) new util.HashSet[Int]() else null
    val tProbe = if (metricsNode != null) System.nanoTime() else 0L
    val joined = probeAcrossAllPartitions(build, matchedBuild)
    if (metricsNode != null) metricsNode.counters(HashJoinExec.IdxProbeNanos).add(System.nanoTime() - tProbe)
    val unmatchedTail: Iterator[ColumnarBatch] =
      if (matchedBuild != null) emitUnmatchedBuild(build, matchedBuild)
      else Iterator.empty
    joined ++ unmatchedTail
  }

  /** Materialize the build side across every build partition in parallel.
    * Used by the collapsing (non-equi) path; per-shard execution materializes
    * just the one shard via [[buildSideForShard]]. */
  private def buildSideAcrossAllPartitions(): BuildSide = {
    val tasks: Seq[Callable[CollectedBuildPartition]] =
      (0 until buildPlan.numPartitions).map { p =>
        new Callable[CollectedBuildPartition] {
          def call(): CollectedBuildPartition = collectBuildPartition(p)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    val keys: mutable.ArrayBuffer[AnyRef] =
      if (buildKeysAreColRefs || useJoinLongKey) null
      else mutable.ArrayBuffer.empty[AnyRef]
    partials.foreach { part =>
      rows ++= part.rows
      if (keys != null) keys ++= part.keys
    }
    if (useJoinLongKey) buildLongKeyMap(rows)
    else buildCodecKeyMap(rows, keys)
  }

  private def probeAcrossAllPartitions(
      build: BuildSide,
      matchedBuild: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val joinedRows = mutable.ArrayBuffer.empty[Array[Any]]
    var totalMatched = 0L
    val tasks: Seq[Callable[(mutable.ArrayBuffer[Array[Any]], util.HashSet[Int])]] =
      (0 until probePlan.numPartitions).map { pp =>
        new Callable[(mutable.ArrayBuffer[Array[Any]], util.HashSet[Int])] {
          def call(): (mutable.ArrayBuffer[Array[Any]], util.HashSet[Int]) =
            probeOnePartition(pp, build)
        }
      }
    Scheduler.submitAndAwaitAll(tasks).foreach { case (rows, m) =>
      joinedRows ++= rows
      if (matchedBuild != null) matchedBuild.addAll(m)
      totalMatched += m.size.toLong
    }
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxProbeSideRows).add(joinedRows.length.toLong)
      metricsNode.counters(HashJoinExec.IdxMatchedRows).add(totalMatched)
    }
    rowsToBatches(joinedRows.toArray, outputSchema, ColumnarBatch.DefaultCapacity)
  }

  // ---- Shared per-partition primitives -------------------------------------

  private def buildLongKeyMap(rows: mutable.ArrayBuffer[Array[Any]]): BuildSide = {
    val keyMap = new LongHashMap[util.ArrayList[Int]]()
    val keyType = keyTypes(0)
    var i = 0
    while (i < rows.length) {
      val v = rows(i)(buildKeyLongIdx)
      if (v != null) {
        // NULL build keys can never match (equi-join, 3VL) — drop them from
        // the keymap; the row stays in `rows` so outer-join "unmatched build"
        // emission still surfaces it.
        val k = KeyCodec.boxedToLong(v, keyType)
        // Inline get-then-put to avoid the per-row Function0 allocation
        // `getOrInsert` would create from its by-name parameter.
        var list = keyMap.get(k)
        if (list == null) {
          list = new util.ArrayList[Int]()
          keyMap.put(k, list)
        }
        list.add(i)
      }
      i += 1
    }
    BuildSide(rows, null, keyMap)
  }

  private def buildCodecKeyMap(
      rows: mutable.ArrayBuffer[Array[Any]],
      precomputedKeys: mutable.ArrayBuffer[AnyRef]): BuildSide = {
    val keyMap = new util.HashMap[AnyRef, util.ArrayList[Int]]()
    if (buildKeysAreColRefs) {
      // ColRef path: keys derive directly from the materialized row array.
      // No Expr.eval involved here even before vectorization — keep it.
      val keyBuf = new Array[Any](nKeys)
      var i = 0
      while (i < rows.length) {
        val row = rows(i)
        var k = 0
        while (k < nKeys) { keyBuf(k) = row(buildKeyColIndices(k)); k += 1 }
        val key = keyCodec.encodeBoxed(keyBuf)
        // computeIfAbsent's lambda is a per-call allocation; do the get/put
        // by hand to avoid the closure on cache hits (the common path once
        // the keymap is warm).
        var list = keyMap.get(key)
        if (list == null) {
          list = new util.ArrayList[Int]()
          keyMap.put(key, list)
        }
        list.add(i)
        i += 1
      }
    } else {
      // Computed-key path: keys were already encoded once per source batch
      // by `collectBuildPartition` using `evalVec`. Walk the parallel array
      // and slot each one into the keymap.
      var i = 0
      while (i < rows.length) {
        val key = precomputedKeys(i)
        var list = keyMap.get(key)
        if (list == null) {
          list = new util.ArrayList[Int]()
          keyMap.put(key, list)
        }
        list.add(i)
        i += 1
      }
    }
    BuildSide(rows, keyMap, null)
  }

  /** Materialize one build-side partition. Also pre-encodes the key for each
    * row when build keys are computed (non-ColRef), eliminating the per-row
    * `RowBuf`-driven `Expr.eval` that `buildCodecKeyMap` used to do over the
    * already-materialized boxed row array. */
  private def collectBuildPartition(p: Int): CollectedBuildPartition = {
    val rowsBuf = mutable.ArrayBuffer.empty[Array[Any]]
    val keysBuf: mutable.ArrayBuffer[AnyRef] =
      if (buildKeysAreColRefs || useJoinLongKey) null
      else mutable.ArrayBuffer.empty[AnyRef]
    val keyBufRow: Array[Any] = if (keysBuf != null) new Array[Any](nKeys) else null
    val it = buildPlan.execute(p)
    val nCols = buildPlan.outputSchema.length
    while (it.hasNext) {
      val b = it.next()
      val nrows = b.numRows
      val keyVecs: Array[ColumnVector] =
        if (keysBuf != null) Array.tabulate(nKeys)(i => buildKeyExprs(i).evalVec(b))
        else null
      var r = 0
      while (r < nrows) {
        val arr = new Array[Any](nCols)
        var c = 0
        while (c < nCols) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        rowsBuf += arr
        if (keysBuf != null) {
          var k = 0
          while (k < nKeys) {
            val kv = keyVecs(k)
            keyBufRow(k) = if (kv.isNull(r)) null else kv.getBoxed(r)
            k += 1
          }
          keysBuf += keyCodec.encodeBoxed(keyBufRow)
        }
        r += 1
      }
    }
    CollectedBuildPartition(rowsBuf, keysBuf)
  }

  /** Probe one partition's worth of rows against the build keymap. Returns
    * (joined rows, build indices that matched). The caller stitches these
    * together across partitions (collapsing path) or uses one shard's
    * result directly (per-shard path).
    *
    * The probe row's `Array[Any]` materialization is deferred to the
    * point we know we'll emit at least one output row — inner joins where
    * the probe row has no match skip the boxing entirely. For
    * `preserveProbeOuter` joins the unmatched-probe emission still has to
    * box, but at most once per row.
    */
  private def probeOnePartition(
      partitionIdx: Int,
      build: BuildSide): (mutable.ArrayBuffer[Array[Any]], util.HashSet[Int]) = {
    val local = mutable.ArrayBuffer.empty[Array[Any]]
    val localMatched = new util.HashSet[Int]()
    val probeIt = probePlan.execute(partitionIdx)
    val probeSchema = probePlan.outputSchema
    val probeWidth = probeSchema.length
    val keyBuf: Array[Any] = if (probeKeysAreColRefs) null else new Array[Any](nKeys)
    while (probeIt.hasNext) {
      val b = probeIt.next()
      val nrows = b.numRows
      // For non-ColRef probe keys, hoist Expr.eval out of the per-row loop.
      // ColRef keys go through `encodeFromBatchSkipIfAnyNull` which already
      // reads typed primitives directly; the long-key fast path reads its
      // single primitive even more directly.
      val probeKeyVecs: Array[ColumnVector] =
        if (probeKeysAreColRefs || useJoinLongKey) null
        else Array.tabulate(nKeys)(i => probeKeyExprs(i).evalVec(b))
      var r = 0
      while (r < nrows) {
        val matches: util.ArrayList[Int] =
          if (useJoinLongKey) {
            val col = b.column(probeKeyLongIdx)
            if (col.isNull(r)) null
            else build.keyMapLong.get(KeyCodec.readAsLong(col, r))
          } else {
            val key: AnyRef =
              if (probeKeysAreColRefs) keyCodec.encodeFromBatchSkipIfAnyNull(b, r)
              else {
                var k = 0
                var anyNull = false
                while (k < nKeys) {
                  val kv = probeKeyVecs(k)
                  val v: Any = if (kv.isNull(r)) null else kv.getBoxed(r)
                  if (v == null) anyNull = true
                  keyBuf(k) = v
                  k += 1
                }
                if (anyNull) null else keyCodec.encodeBoxed(keyBuf)
              }
            if (key == null) null else build.keyMap.get(key)
          }
        var probeRow: Array[Any] = null
        var matchedAny = false
        if (matches != null && !matches.isEmpty) {
          val it = matches.iterator()
          while (it.hasNext) {
            val buildIdx = it.next()
            if (probeRow == null) probeRow = materializeProbeRow(b, r, probeWidth)
            val combined = mergeMatch(probeRow, build.rows(buildIdx))
            if (passesExtra(combined)) {
              local += combined
              matchedAny = true
              localMatched.add(buildIdx)
            }
          }
        }
        if (!matchedAny && preserveProbeOuter) {
          if (probeRow == null) probeRow = materializeProbeRow(b, r, probeWidth)
          local += mergeUnmatchedProbe(probeRow)
        }
        r += 1
      }
    }
    (local, localMatched)
  }

  private def materializeProbeRow(b: ColumnarBatch, r: Int, width: Int): Array[Any] = {
    val arr = new Array[Any](width)
    var c = 0
    while (c < width) {
      val col = b.column(c)
      arr(c) = if (col.isNull(r)) null else col.getBoxed(r)
      c += 1
    }
    arr
  }

  private def emitUnmatchedBuild(build: BuildSide, matched: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val unmatched = mutable.ArrayBuffer.empty[Array[Any]]
    var i = 0
    while (i < build.rows.length) {
      if (!matched.contains(i)) unmatched += mergeUnmatchedBuild(build.rows(i))
      i += 1
    }
    if (metricsNode != null && unmatched.nonEmpty) {
      metricsNode.counters(HashJoinExec.IdxUnmatchedRows).add(unmatched.length.toLong)
    }
    rowsToBatches(unmatched.toArray, schema, capacity)
  }

  private def rowsToBatches(rows: Array[Array[Any]], schema: Schema, capacity: Int): Iterator[ColumnarBatch] = {
    var pos = 0
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = pos < rows.length
      def next(): ColumnarBatch = {
        val take = math.min(capacity, rows.length - pos)
        val out = new ColumnarBatch(schema, take max 1)
        var r = 0
        while (r < take) {
          val row = rows(pos + r)
          var c = 0
          while (c < schema.length) {
            if (row(c) == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, row(c))
            c += 1
          }
          r += 1
        }
        out.setNumRows(take)
        pos += take
        out
      }
    }
  }

  private def mergeMatch(probeRow: Array[Any], buildRow: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    if (probeIsLeft) {
      System.arraycopy(probeRow, 0, out, 0, nLeftCols)
      System.arraycopy(buildRow, 0, out, nLeftCols, nRightCols)
    } else {
      System.arraycopy(buildRow, 0, out, 0, nLeftCols)
      System.arraycopy(probeRow, 0, out, nLeftCols, nRightCols)
    }
    out
  }

  private def mergeUnmatchedProbe(probeRow: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    if (probeIsLeft) System.arraycopy(probeRow, 0, out, 0, nLeftCols)
    else System.arraycopy(probeRow, 0, out, nLeftCols, nRightCols)
    out
  }

  private def mergeUnmatchedBuild(buildRow: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    if (buildIsLeft) System.arraycopy(buildRow, 0, out, 0, nLeftCols)
    else System.arraycopy(buildRow, 0, out, nLeftCols, nRightCols)
    out
  }

  private def passesExtra(row: Array[Any]): Boolean = extra match {
    case None => true
    case Some(e) =>
      val view = RowView(outputSchema, row)
      val v = e.eval(view, 0)
      v != null && v.asInstanceOf[Boolean]
  }

  // ---- Grace hash path (plan 09 Phase 6) -----------------------------------
  //
  // When `opts.spillEnabled` and the join has equi-keys, route both sides into
  // K disk buckets keyed by `hash(joinKey) % K`. Then process each bucket pair
  // (build_k, probe_k) sequentially: load build_k's rows into a fresh in-memory
  // BuildSide and stream probe_k against it. Memory bound per bucket pair ≈
  // size of one bucket's build side (≈ totalBuild / K).
  //
  // Bucket count K is fixed at `GraceBuckets` for v1 — recursive bucketing on
  // skewed data is v1.1 (a bucket too big to fit in memory will OOM). NULL join
  // keys deterministically land in bucket 0; they never match (3VL) but still
  // surface in outer-join unmatched-build / unmatched-probe emission.

  private val GraceBuckets: Int = 16

  private def executeGraceHash(partition: Int): Iterator[ColumnarBatch] = {
    require(graceEnabled, "executeGraceHash called with graceEnabled=false")
    val K = GraceBuckets
    val spillDir = Spill.openOperatorDir("hashjoin")
    if (metricsNode != null) metricsNode.counters(HashJoinExec.IdxBucketCount).add(K.toLong)

    val buildParts: Seq[Int] =
      if (isSharded) {
        require(partition >= 0 && partition < numPartitions,
          s"partition $partition out of range [0,$numPartitions)")
        Seq(partition)
      } else {
        require(partition == 0,
          s"non-sharded join collapses to one partition; got partition=$partition")
        0 until buildPlan.numPartitions
      }
    val probeParts: Seq[Int] = if (isSharded) Seq(partition) else 0 until probePlan.numPartitions

    val buildBuckets = bucketSide(
      sideName = "build",
      parts = buildParts,
      plan = buildPlan,
      keyExprs = buildKeyExprs,
      keysAreColRefs = buildKeysAreColRefs,
      keyColIndices = buildKeyColIndices,
      K = K,
      spillDir = spillDir,
      side = HashJoinExec.SideBuild)
    val probeBuckets = bucketSide(
      sideName = "probe",
      parts = probeParts,
      plan = probePlan,
      keyExprs = probeKeyExprs,
      keysAreColRefs = probeKeysAreColRefs,
      keyColIndices = probeKeyColIndices,
      K = K,
      spillDir = spillDir,
      side = HashJoinExec.SideProbe)

    val out = mutable.ArrayBuffer.empty[Array[Any]]
    val tLoad = if (metricsNode != null) System.nanoTime() else 0L
    var k = 0
    while (k < K) {
      processBucketPair(buildBuckets(k), probeBuckets(k), out)
      k += 1
    }
    if (metricsNode != null) metricsNode.counters(HashJoinExec.IdxBucketsLoadedNanos).add(System.nanoTime() - tLoad)
    spillDir.close()
    rowsToBatches(out.toArray, outputSchema, ColumnarBatch.DefaultCapacity)
  }

  /** Route all rows from `parts` of `plan` into K bucket files inside
    * `spillDir`, keyed by `hash(joinKey) % K`. Returns one file path per
    * bucket; a `null` entry means that bucket was empty (no file written).
    *
    * Sub-plan 2 adds `side` for counter attribution. The bucket-write
    * threshold doesn't change behavior — the count of bytes-on-disk after
    * close is the source of truth for `bytesSpilledBuild` /
    * `bytesSpilledProbe`, both populated from the final `files`. */
  private def bucketSide(
      sideName: String,
      parts: Seq[Int],
      plan: PhysicalPlan,
      keyExprs: Array[Expr],
      keysAreColRefs: Boolean,
      keyColIndices: Array[Int],
      K: Int,
      spillDir: OperatorSpillDir,
      side: Int): Array[Path] = {
    val schema = plan.outputSchema
    val ncols = schema.length
    val files = new Array[Path](K)
    val writers = new Array[TParquetWriter](K)
    // Per-bucket pending batches; flushed when full or at end.
    val pending = Array.fill(K)(new ColumnarBatch(schema, ColumnarBatch.DefaultCapacity))
    val pendingCounts = new Array[Int](K)

    def flushBucket(b: Int): Unit = {
      val n = pendingCounts(b)
      if (n == 0) return
      val out = pending(b)
      out.setNumRows(n)
      if (writers(b) == null) {
        files(b) = spillDir.newSpillFile(s".${sideName}.b$b.parquet")
        writers(b) = new TParquetWriter(files(b), schema, Map.empty)
      }
      writers(b).write(out)
      pending(b) = new ColumnarBatch(schema, ColumnarBatch.DefaultCapacity)
      pendingCounts(b) = 0
    }

    parts.foreach { p =>
      val it = plan.execute(p)
      while (it.hasNext) {
        val batch = it.next()
        if (batch.numRows > 0) routeBatchToBuckets(
          batch, ncols, keyExprs, keysAreColRefs, keyColIndices, K,
          pending, pendingCounts, flushBucket _)
      }
    }
    // Final flush.
    var b = 0
    while (b < K) { flushBucket(b); b += 1 }
    // Close writers.
    b = 0
    while (b < K) {
      val w = writers(b)
      if (w != null) w.close()
      b += 1
    }
    // Attribute spill bytes per-side. Done after close so the size on disk
    // is final. Also surface peak bucket bytes (max across buckets) for
    // skew detection.
    if (metricsNode != null) {
      var total: Long = 0L
      var peak: Long = 0L
      var i = 0
      while (i < K) {
        val f = files(i)
        if (f != null) {
          val sz = f.toFile.length()
          total += sz
          if (sz > peak) peak = sz
        }
        i += 1
      }
      if (side == HashJoinExec.SideBuild)
        metricsNode.counters(HashJoinExec.IdxBytesSpilledBuild).add(total)
      else
        metricsNode.counters(HashJoinExec.IdxBytesSpilledProbe).add(total)
      // peakBucketBytes is a max across both sides; we accumulate per-side
      // and the consumer reads the resulting sum-of-peaks across sides.
      if (peak > 0L) metricsNode.counters(HashJoinExec.IdxPeakBucketBytes).add(peak)
    }
    files
  }

  /** Compute a 32-bit hash of the join key columns at each row of `batch`
    * and append the row to the corresponding bucket's pending batch. */
  private def routeBatchToBuckets(
      batch: ColumnarBatch,
      ncols: Int,
      keyExprs: Array[Expr],
      keysAreColRefs: Boolean,
      keyColIndices: Array[Int],
      K: Int,
      pending: Array[ColumnarBatch],
      pendingCounts: Array[Int],
      flushBucket: Int => Unit): Unit = {
    val nrows = batch.numRows
    // Evaluate each key expression to a vector. For ColRef keys, evalVec
    // aliases the column.
    val keyVecs = new Array[ColumnVector](nKeys)
    var k = 0
    while (k < nKeys) {
      keyVecs(k) =
        if (keysAreColRefs) batch.column(keyColIndices(k))
        else keyExprs(k).evalVec(batch)
      k += 1
    }
    var r = 0
    while (r < nrows) {
      val bucket = bucketIndex(keyVecs, r, K)
      val dst = pending(bucket)
      val dstRow = pendingCounts(bucket)
      var c = 0
      while (c < ncols) {
        batch.column(c).copyTo(r, dst.column(c), dstRow)
        c += 1
      }
      pendingCounts(bucket) = dstRow + 1
      if (pendingCounts(bucket) >= ColumnarBatch.DefaultCapacity) flushBucket(bucket)
      r += 1
    }
  }

  /** Bucket index = `fmix32(hash(joinKeys)) & (K-1)`. Mirrors the
    * mixing strategy in [[HashPartitioner]] so small contiguous integer
    * keys don't cluster into one bucket. K is forced to a power of two by
    * construction (we set [[GraceBuckets]]). NULL keys deterministically
    * land in bucket 0. */
  private def bucketIndex(keyVecs: Array[ColumnVector], row: Int, K: Int): Int = {
    // Any-null check: NULL join keys can never match (3VL) — bucket 0 is fine
    // as a deterministic destination; they'll match nothing in their bucket.
    var k = 0
    var anyNull = false
    while (k < nKeys && !anyNull) {
      if (keyVecs(k).isNull(row)) anyNull = true
      k += 1
    }
    if (anyNull) return 0
    // Combined hash across columns. Same shape as Java's Objects.hash but
    // with primitive specialization to avoid per-row boxing.
    var h = 1
    k = 0
    while (k < nKeys) {
      val col = keyVecs(k)
      val hi = HashJoinExec.columnHashCode(col, row)
      h = 31 * h + hi
      k += 1
    }
    val mixed = HashJoinExec.fmix32(h)
    (mixed & 0x7FFFFFFF) % K
  }

  /** Process one bucket pair: build a fresh in-memory map from `buildFile`
    * (if non-null), stream `probeFile` against it (if non-null), emit
    * matches into `out`. For outer joins with `preserveBuildOuter`, also
    * emit unmatched build rows from this bucket.
    *
    * Why per-bucket unmatched emission preserves correctness: each bucket's
    * build rows can only match probe rows in the SAME bucket (since both
    * sides hash by the same join key). A build row in bucket k whose key
    * is unmatched in this bucket's probe is unmatched globally. */
  private def processBucketPair(
      buildFile: Path,
      probeFile: Path,
      out: mutable.ArrayBuffer[Array[Any]]): Unit = {
    val build = loadBuildFromBucket(buildFile)
    if (metricsNode != null && build != null) {
      metricsNode.counters(HashJoinExec.IdxBuildSideRows).add(build.rows.length.toLong)
    }
    val matched = if (preserveBuildOuter) new util.HashSet[Int]() else null
    if (build != null && probeFile != null) probeBucket(probeFile, build, out, matched)
    else if (probeFile != null && preserveProbeOuter) {
      // Probe-only bucket: every probe row is unmatched. Stream and null-pad.
      streamProbeAsUnmatched(probeFile, out)
    }
    if (build != null && preserveBuildOuter) {
      var unmatchedHere = 0L
      var i = 0
      while (i < build.rows.length) {
        if (!matched.contains(i)) { out += mergeUnmatchedBuild(build.rows(i)); unmatchedHere += 1 }
        i += 1
      }
      if (metricsNode != null && unmatchedHere > 0L)
        metricsNode.counters(HashJoinExec.IdxUnmatchedRows).add(unmatchedHere)
    }
  }

  /** Load every row of `file` into an in-memory [[BuildSide]] using the
    * existing keymap-build primitives. Returns `null` for empty / absent
    * files. */
  private def loadBuildFromBucket(file: Path): BuildSide = {
    if (file == null) return null
    val reader = ParquetReader.fromPath(file.toString)
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    val keys: mutable.ArrayBuffer[AnyRef] =
      if (buildKeysAreColRefs || useJoinLongKey) null
      else mutable.ArrayBuffer.empty[AnyRef]
    val keyBufRow: Array[Any] = if (keys != null) new Array[Any](nKeys) else null
    val schema = buildPlan.outputSchema
    val ncols = schema.length
    var part = 0
    while (part < reader.numPartitions) {
      val it = reader.readPartition(part)
      while (it.hasNext) {
        val b = it.next()
        val n = b.numRows
        val keyVecs: Array[ColumnVector] =
          if (keys != null) Array.tabulate(nKeys)(i => buildKeyExprs(i).evalVec(b))
          else null
        var r = 0
        while (r < n) {
          val arr = new Array[Any](ncols)
          var c = 0
          while (c < ncols) {
            arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
            c += 1
          }
          rows += arr
          if (keys != null) {
            var k = 0
            while (k < nKeys) {
              val kv = keyVecs(k)
              keyBufRow(k) = if (kv.isNull(r)) null else kv.getBoxed(r)
              k += 1
            }
            keys += keyCodec.encodeBoxed(keyBufRow)
          }
          r += 1
        }
      }
      part += 1
    }
    if (rows.isEmpty) return null
    if (useJoinLongKey) buildLongKeyMap(rows)
    else buildCodecKeyMap(rows, keys)
  }

  /** Stream one probe bucket against a freshly-built BuildSide, appending
    * matches (and outer-probe-unmatched null-pads) to `out`. */
  private def probeBucket(
      file: Path,
      build: BuildSide,
      out: mutable.ArrayBuffer[Array[Any]],
      matchedBuild: util.HashSet[Int]): Unit = {
    val reader = ParquetReader.fromPath(file.toString)
    val probeSchema = probePlan.outputSchema
    val probeWidth = probeSchema.length
    val keyBuf: Array[Any] = if (probeKeysAreColRefs) null else new Array[Any](nKeys)
    var probeRowsHere: Long = 0L
    var matchedHere: Long = 0L
    var part = 0
    while (part < reader.numPartitions) {
      val it = reader.readPartition(part)
      while (it.hasNext) {
        val b = it.next()
        val nrows = b.numRows
        probeRowsHere += nrows.toLong
        val probeKeyVecs: Array[ColumnVector] =
          if (probeKeysAreColRefs || useJoinLongKey) null
          else Array.tabulate(nKeys)(i => probeKeyExprs(i).evalVec(b))
        var r = 0
        while (r < nrows) {
          val matches: util.ArrayList[Int] =
            if (useJoinLongKey) {
              val col = b.column(probeKeyLongIdx)
              if (col.isNull(r)) null
              else build.keyMapLong.get(KeyCodec.readAsLong(col, r))
            } else {
              val key: AnyRef =
                if (probeKeysAreColRefs) keyCodec.encodeFromBatchSkipIfAnyNull(b, r)
                else {
                  var k = 0; var anyNull = false
                  while (k < nKeys) {
                    val kv = probeKeyVecs(k)
                    val v: Any = if (kv.isNull(r)) null else kv.getBoxed(r)
                    if (v == null) anyNull = true
                    keyBuf(k) = v
                    k += 1
                  }
                  if (anyNull) null else keyCodec.encodeBoxed(keyBuf)
                }
              if (key == null) null else build.keyMap.get(key)
            }
          var probeRow: Array[Any] = null
          var matchedAny = false
          if (matches != null && !matches.isEmpty) {
            val mIt = matches.iterator()
            while (mIt.hasNext) {
              val buildIdx = mIt.next()
              if (probeRow == null) probeRow = materializeProbeRow(b, r, probeWidth)
              val combined = mergeMatch(probeRow, build.rows(buildIdx))
              if (passesExtra(combined)) {
                out += combined
                matchedAny = true
                matchedHere += 1L
                if (matchedBuild != null) matchedBuild.add(buildIdx)
              }
            }
          }
          if (!matchedAny && preserveProbeOuter) {
            if (probeRow == null) probeRow = materializeProbeRow(b, r, probeWidth)
            out += mergeUnmatchedProbe(probeRow)
          }
          r += 1
        }
      }
      part += 1
    }
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxProbeSideRows).add(probeRowsHere)
      metricsNode.counters(HashJoinExec.IdxMatchedRows).add(matchedHere)
    }
  }

  /** Stream a probe-side bucket where the build bucket is empty — every
    * probe row is unmatched. Only invoked for outer joins that preserve
    * the probe side. */
  private def streamProbeAsUnmatched(
      file: Path,
      out: mutable.ArrayBuffer[Array[Any]]): Unit = {
    val reader = ParquetReader.fromPath(file.toString)
    val probeWidth = probePlan.outputSchema.length
    var part = 0
    while (part < reader.numPartitions) {
      val it = reader.readPartition(part)
      while (it.hasNext) {
        val b = it.next()
        val n = b.numRows
        var r = 0
        while (r < n) {
          out += mergeUnmatchedProbe(materializeProbeRow(b, r, probeWidth))
          r += 1
        }
      }
      part += 1
    }
  }
}

object HashJoinExec {
  // ---- Counter indices (Sub-plan 2 instrumentation) ------------------------
  // In-memory path counters.
  final val IdxBuildSideRows: Int      = 0
  final val IdxProbeSideRows: Int      = 1
  final val IdxMatchedRows: Int        = 2
  final val IdxUnmatchedRows: Int      = 3
  final val IdxBuildNanos: Int         = 4
  final val IdxProbeNanos: Int         = 5
  final val IdxKeyCodecPath: Int       = 6
  // Spill (grace hash) counters.
  final val IdxBucketCount: Int        = 7
  final val IdxBytesSpilledBuild: Int  = 8
  final val IdxBytesSpilledProbe: Int  = 9
  final val IdxBucketsLoadedNanos: Int = 10
  final val IdxPeakBucketBytes: Int    = 11

  /** Side discriminator for [[HashJoinExec.bucketSide]]'s instrumentation. */
  private[exec] final val SideBuild: Int = 0
  private[exec] final val SideProbe: Int = 1

  /** Key-codec path codes — same convention as
    * [[HashAggregateExec.KeyPathLong]] etc. */
  final val KeyPathLong: Long   = 0L
  final val KeyPathPacked: Long = 1L
  final val KeyPathObject: Long = 2L

  /** Name array parallel to the Idx* constants above. Length asserted equal
    * to `highest Idx<Name> + 1` in `operator_counters_test.scala`. */
  val IdxCounterNames: Array[String] = Array(
    "buildSideRows",
    "probeSideRows",
    "matchedRows",
    "unmatchedRows",
    "buildNanos",
    "probeNanos",
    "keyCodecPath",
    "bucketCount",
    "bytesSpilledBuild",
    "bytesSpilledProbe",
    "bucketsLoadedNanos",
    "peakBucketBytes")

  /** Avalanche the combined column hash so contiguous small-integer keys
    * spread across buckets instead of clustering. Identical mixer to
    * [[HashPartitioner.fmix32]] (we don't reach across packages from
    * `sql.exec` into `core` for this trivial constant). */
  private[exec] def fmix32(h0: Int): Int = {
    var h = h0
    h ^= (h >>> 16)
    h *= 0x85ebca6b
    h ^= (h >>> 13)
    h *= 0xc2b2ae35
    h ^= (h >>> 16)
    h
  }

  /** Per-column hashCode that avoids per-row boxing on the primitive
    * vectors. Reference vectors fall back to the boxed `hashCode` via
    * `getBoxed`. */
  private[exec] def columnHashCode(col: ColumnVector, row: Int): Int = col match {
    case lv: LongVector =>
      val v = lv.values(row); (v ^ (v >>> 32)).toInt
    case iv: IntVector => iv.values(row)
    case dv: DoubleVector =>
      val v = java.lang.Double.doubleToLongBits(dv.values(row))
      (v ^ (v >>> 32)).toInt
    case fv: FloatVector => java.lang.Float.floatToIntBits(fv.values(row))
    case bv: BooleanVector => if (bv.values(row)) 1231 else 1237
    case tv: TimestampVector =>
      val v = tv.values(row); (v ^ (v >>> 32)).toInt
    case dv: DateVector => dv.values(row)
    case other =>
      val boxed = other.getBoxed(row)
      if (boxed == null) 0 else boxed.hashCode
  }
}

/** Materialized build side. Exactly one of `keyMap` / `keyMapLong` is non-null
  * — `keyMapLong` when the equi-join key is a single fixed-width-numeric ColRef
  * on both sides, `keyMap` otherwise. */
private[exec] final case class BuildSide(
    rows: mutable.ArrayBuffer[Array[Any]],
    keyMap: util.HashMap[AnyRef, util.ArrayList[Int]],
    keyMapLong: LongHashMap[util.ArrayList[Int]])

/** One partition's worth of materialized build rows plus the pre-encoded
  * keys for those rows (when build keys are computed expressions — `keys` is
  * null for the ColRef and single-long fast paths). */
private[exec] final case class CollectedBuildPartition(
    rows: mutable.ArrayBuffer[Array[Any]],
    keys: mutable.ArrayBuffer[AnyRef])
