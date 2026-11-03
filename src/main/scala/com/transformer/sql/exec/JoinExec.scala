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

/** Hash join. One side, the build side, is read into one columnar batch and
  * a multi-map from join key to the indices of its rows; the other, the
  * probe side, is read a batch at a time, each row looked up in the map and
  * paired with the build rows it finds. Every path probes through a
  * [[ProbeCursor]], which can stop after any row or candidate pair and go on
  * later, so a join holds its build side and a bounded amount of output,
  * never its whole result. A pair is no boxed row: the cursor writes the
  * probe row and the build row of each into two selection vectors, and the
  * output batch gathers each column from its side by them, a column at a
  * time ([[ColumnVector.gather]]).
  *
  * The keys are the equality conjuncts the planner extracts from the ON
  * condition (`a.x = b.y AND …`); the rest of it is `extra`, evaluated over
  * a chunk of candidate pairs at once ([[Expr.evalVec]]). A join with no
  * equality pairs every probe row with every build row (one empty-key bucket
  * holds them all), which [[PhysicalPlanner]]'s nested-loop guard refuses
  * over large inputs.
  *
  * Four paths, chosen from the plan as built:
  *
  *   - **Broadcast** (a keyed join that keeps no unmatched build row: a
  *     planned INNER, LEFT or RIGHT join under the broadcast threshold). The
  *     build side is built once ([[SharedOnce]]), across every build
  *     partition in parallel, and published by
  *     [[PhysicalPlanner.preMaterialize]] before any partition is read; the
  *     join runs in its probe side's partitions, each streaming its own
  *     probe partition against the shared, read-only build side.
  *     `numPartitions = probe.numPartitions`.
  *   - **In waves** (no equality, or a keyed join that keeps unmatched build
  *     rows: a planned FULL join). The build side is built in `execute(0)`;
  *     a cursor per probe partition, up to [[Scheduler.parallelism]] of them
  *     a wave, is advanced in parallel by at most [[HashJoinExec.PairsPerTask]]
  *     units of work (a probe row read, a candidate pair examined or an
  *     unmatched probe row padded), and each wave's output is emitted before
  *     the next reads on. Each cursor records the build rows it matched in a
  *     `BitSet`; after the last wave their union names the build rows left
  *     unmatched, which follow. `numPartitions = 1`.
  *   - **Per shard** (both children are [[ExchangeExec]]s: the planner
  *     shards an equi-join whose smaller side is at least the broadcast
  *     threshold). Same-key rows land in the same shard on both sides, so
  *     shard `s` builds its build rows on its first pull, streams probe
  *     shard `s` against them, and emits the build rows of the shard no probe
  *     row matched, which no probe row anywhere matches.
  *     `numPartitions = K`.
  *   - **Grace hash** (spill on, keys, none reading `RAND()`). On the first
  *     pull both sides are routed to 16 parquet buckets by key; then bucket
  *     by bucket, the build bucket is loaded, the probe bucket streamed
  *     against it and the bucket's unmatched build rows emitted. One
  *     partition, K when sharded.
  *
  * `buildRight` selects which side is built:
  *   - `true` (default): right is built, left is probed.
  *   - `false`: left is built, right is probed. The planner picks this when
  *     `LogicalPlanCardinality.estimate(left)` is materially below `estimate(right)`
  *     (so the smaller side ends up in the hash) or when the join kind is
  *     RIGHT outer (so the preserved side stays the streaming probe). Output
  *     schema and column order are unchanged — `left.outputSchema ++ right.outputSchema`
  *     either way — so downstream operators don't see the swap.
  *
  * Invariant: when both children are exchanges, both must be partitioned by
  * the equi-join key set with the same K (the planner wraps both children in
  * `ExchangeExec`s with matching K and the pinned [[HashPartitioner]] hash).
  * Direct construction over exchanges partitioned otherwise produces wrong
  * results — same-key rows would land in different shards and the per-shard
  * probe would miss matches.
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
) extends PhysicalPlan with SharedResult {

  val outputSchema: Schema = Schema(left.outputSchema.fields ++ right.outputSchema.fields)

  private val nLeftCols = left.outputSchema.length

  private val probeIsLeft: Boolean = buildRight
  private val buildIsLeft: Boolean = !buildRight

  /** Whether the join keeps the build rows no probe row matched. With
    * [[PhysicalPlanner.shouldBuildRight]] (LEFT builds right, RIGHT builds
    * left) only a FULL join does as planned; one built in code may make any
    * outer kind do so. */
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
  private val probeWidth: Int = probePlan.outputSchema.length
  private val buildWidth: Int = buildPlan.outputSchema.length
  /** Where each side's columns start in the output. */
  private val probeOut: Int = if (probeIsLeft) 0 else nLeftCols
  private val buildOut: Int = if (probeIsLeft) nLeftCols else 0

  // Key encoding. Both sides share one codec layout (its bit/byte layout
  // depends only on key TYPES, not column positions): `keyCodec` is bound to
  // the probe side's key columns ([[KeyCodec.encodeFromBatch]] reads them), and
  // `buildCodec` to the build side's, which the build side's keys are encoded
  // with from its columnar batch. Computed keys (non-ColRef) are evaluated a
  // batch at a time and encoded from their values ([[KeyCodec.encodeBoxed]]).
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
  private val buildCodec: KeyCodec =
    if (buildKeysAreColRefs) KeyCodec.forColumns(buildKeyColIndices, keyTypes) else keyCodec

  // Fast path: single fixed-width-numeric-fittable ColRef join key on BOTH
  // sides → swap out the AnyRef keymap for [[LongHashMap]]. Both sides read
  // the primitive long straight from their `ColumnVector`s (no boxing).
  private val useJoinLongKey: Boolean =
    nKeys == 1 &&
    buildKeysAreColRefs && probeKeysAreColRefs &&
    KeyCodec.isLongFittable(keyTypes(0))
  private val buildKeyLongIdx: Int =
    if (useJoinLongKey) buildKeyColIndices(0) else -1
  private val probeKeyLongIdx: Int =
    if (useJoinLongKey) probeKeyColIndices(0) else -1

  /** The output columns `extra` reads: gathered for its candidate pairs. */
  private val extraColumns: Array[Int] =
    extra.fold(Array.empty[Int])(e => JoinSideAnalysis.columnsRead(e).toArray.sorted)

  /** True when the planner has wrapped both sides in `ExchangeExec`s. The
    * planner does this for equi-joins whose smaller side is above
    * [[com.transformer.sql.plan.LogicalPlanCardinality.BroadcastBuildThreshold]]
    * — below that threshold, even equi-joins broadcast (build the small
    * side, stream-probe the large one) because shuffling the large probe
    * just to redistribute rows costs more than the join itself.
    *
    * Detected via child type rather than a flag so direct construction over
    * non-exchange children (existing tests, future callers) still works; a
    * child the metrics wrapper meters is the exchange it wraps
    * ([[ExchangeExec.feeds]]). */
  private val isSharded: Boolean = ExchangeExec.feeds(left) && ExchangeExec.feeds(right)

  /** Grace hash join is enabled when:
    *   - the user opted in via `output.json -> options.spill=true`, AND
    *   - the join has equi-keys (`nKeys > 0`).
    *
    * Non-equi joins (the pairing path) don't qualify because there's no key
    * to hash-bucket on; their size guard is
    * [[PhysicalPlanner.enforceNestedLoopGuard]] above the planner. Nor do
    * keys that read RAND(), which bucketing would read a second time. */
  private val graceEnabled: Boolean =
    opts.spillEnabled && nKeys > 0 && !(leftKeys ++ rightKeys).exists(Expr.readsRandom)

  /** Which of the class scaladoc's four paths this join runs. */
  private val path: HashJoinExec.Path =
    if (graceEnabled) HashJoinExec.Grace
    else if (isSharded) HashJoinExec.PerShard
    else if (nKeys == 0 || preserveBuildOuter) HashJoinExec.InWaves
    else HashJoinExec.Broadcast

  /** A function of the children, never of anything run: EXPLAIN and the
    * planner's LIMIT read it before anything runs. The probe side's
    * partitions, which can be 0, for a broadcast join; K when sharded; 1 in
    * waves, and for grace hash unsharded. */
  def numPartitions: Int = path match {
    case HashJoinExec.Broadcast => probePlan.numPartitions
    case HashJoinExec.InWaves   => 1
    case HashJoinExec.PerShard  => left.numPartitions
    case HashJoinExec.Grace     => if (isSharded) left.numPartitions else 1
  }

  // Stamp the constant key-codec path code into the metrics node once at
  // construction. See [[HashJoinExec]] companion for the path codes.
  private val keyCodecPathCode: Long = {
    if (useJoinLongKey) HashJoinExec.KeyPathLong
    else if (nKeys == 0) HashJoinExec.KeyPathObject // pairing: no codec
    else if (keyTypes.forall(KeyCodec.isPackable)) HashJoinExec.KeyPathPacked
    else if (nKeys == 1) HashJoinExec.KeyPathSingleObject  // SingleObjectKeyCodec
    else HashJoinExec.KeyPathObject
  }
  if (metricsNode != null) {
    metricsNode.counters(HashJoinExec.IdxKeyCodecPath).add(keyCodecPathCode)
  }

  /** A broadcast join's build side, built once and read by every partition:
    * published by [[PhysicalPlanner.preMaterialize]], or, in a plan run
    * without the pass, by the first `execute` call ([[SharedOnce]]). Never
    * built at construction or planning, so EXPLAIN reads nothing. */
  private val sharedBuild = new SharedOnce[BuildSide](() => timedBuild(buildSideAcrossAllPartitions()))

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numPartitions,
      s"partition $partition out of range [0,$numPartitions)")
    path match {
      case HashJoinExec.Broadcast => executeBroadcast(partition)
      case HashJoinExec.InWaves   => executeInWaves()
      case HashJoinExec.PerShard  => executePerShard(partition)
      case HashJoinExec.Grace     => executeGraceHash(partition)
    }
  }

  /** A broadcast join's build side, built and published now; any other path
    * builds as it runs, so nothing. */
  private[exec] def materializeNow(): Unit =
    if (path == HashJoinExec.Broadcast) { val _ = sharedBuild.get() }

  /** `build`, its time and rows recorded. */
  private def timedBuild(build: => BuildSide): BuildSide = {
    val tBuild = if (metricsNode != null) System.nanoTime() else 0L
    val b = build
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxBuildNanos).add(System.nanoTime() - tBuild)
      metricsNode.counters(HashJoinExec.IdxBuildSideRows).add(b.rows.toLong)
    }
    b
  }

  // ---- Broadcast path ------------------------------------------------------

  /** Probe partition `partition` streamed against the shared build side. The
    * build is taken here, on the calling thread, never on the first `hasNext`:
    * a caller that runs a plan without the pass calls `execute(p)` for every
    * partition on one thread (as `SqlEngine` does), where a build deferred to
    * the first pull would race from the consumer's pool tasks. The probe
    * partition is opened by the first pull. */
  private def executeBroadcast(partition: Int): Iterator[ColumnarBatch] = {
    val build = sharedBuild.get()
    new Probed(new ProbeCursor(() => probePlan.execute(partition), build, null))
  }

  // ---- In waves --------------------------------------------------------------

  /** A join with no equality, or one that keeps unmatched build rows, on one
    * partition: the build side is materialized here, and the probe side read
    * through a cursor per probe partition, paired in waves. Each wave
    * advances up to [[Scheduler.parallelism]] cursors in parallel by at most
    * [[HashJoinExec.PairsPerTask]] units each, and its output is emitted
    * before the next wave reads more, so the join holds the build side and
    * one wave's rows (at most parallelism × PairsPerTask), where it held
    * every row it made before its first batch. A wave's output is its
    * cursors' batches in partition order; a partition joins the waves as an
    * earlier one runs out. A cursor that keeps unmatched build rows records
    * the build rows it matched in a `BitSet` of its own, which is merged on
    * the consumer's thread once the cursor is done; the build rows no
    * cursor matched follow the last wave. */
  private def executeInWaves(): Iterator[ColumnarBatch] = {
    val build = timedBuild(buildSideAcrossAllPartitions())
    val matched: util.BitSet = if (preserveBuildOuter) new util.BitSet(build.rows) else null
    val cursors = mutable.Queue.tabulate(probePlan.numPartitions) { p =>
      new ProbeCursor(() => probePlan.execute(p), build, if (preserveBuildOuter) new util.BitSet else null)
    }
    val waves = new Iterator[ColumnarBatch] with AutoCloseable {
      /** Close every probe partition still being read: the consumer stopped. */
      def close(): Unit = {
        cursors.foreach(_.close())
        cursors.clear()
        current = Iterator.empty
      }
      private var current: Iterator[ColumnarBatch] = Iterator.empty
      def hasNext: Boolean = {
        while (!current.hasNext && cursors.nonEmpty) current = wave()
        current.hasNext
      }
      def next(): ColumnarBatch = {
        val _ = hasNext // moves to the next wave when this one is spent
        current.next()
      }

      /** Advance the first cursors still reading, in parallel, and drop the
        * ones that ran out. */
      private def wave(): Iterator[ColumnarBatch] = {
        val advancing = cursors.take(Scheduler.parallelism).toVector
        val results = Scheduler.submitAndAwaitAll(advancing.map { c =>
          new Callable[Vector[ColumnarBatch]] {
            def call(): Vector[ColumnarBatch] = {
              val out = Vector.newBuilder[ColumnarBatch]
              var budget = HashJoinExec.PairsPerTask.toLong
              while (budget > 0L && !c.done) {
                val o = new Output(ColumnarBatch.DefaultCapacity)
                budget -= timedFill(c, o, budget)
                if (o.rows > 0) out += o.finish(build)
              }
              out.result()
            }
          }
        })
        cursors.filterInPlace { c =>
          if (!c.done) true
          else {
            if (matched != null) matched.or(c.matched)
            c.close()
            false
          }
        }
        results.iterator.flatten
      }
    }
    // The tail is read once every probe row is paired, so the build rows no
    // probe row matched are known by then.
    Closing.over(waves ++ {
      if (matched != null) unmatchedBuildRows(build, matched) else Iterator.empty
    }, waves)
  }

  // ---- Per-shard path ------------------------------------------------------

  /** Shard `partition`, from its first pull: its build rows read from the
    * build side's exchange, its probe shard streamed against them, then the
    * shard's build rows no probe row matched. Nothing runs in `execute`, so a
    * consumer reading shards on several threads builds them in parallel,
    * each holding one shard's build rows. */
  private def executePerShard(partition: Int): Iterator[ColumnarBatch] =
    Closing.concat(Iterator.single { () =>
      val build = timedBuild(buildSide(Vector(collectBuildPartition(partition))))
      val cursor = new ProbeCursor(() => probePlan.execute(partition), build,
        if (preserveBuildOuter) new util.BitSet(build.rows) else null)
      val probed = new Probed(cursor)
      Closing.over(probed ++ {
        if (preserveBuildOuter) unmatchedBuildRows(build, cursor.matched) else Iterator.empty
      }, probed)
    })

  // ---- Building ------------------------------------------------------------

  /** Materialize the build side across every build partition in parallel:
    * the broadcast and in-waves paths. Per-shard execution materializes
    * just the one shard. */
  private def buildSideAcrossAllPartitions(): BuildSide = {
    val tasks: Seq[Callable[CollectedBuildPartition]] =
      (0 until buildPlan.numPartitions).map { p =>
        new Callable[CollectedBuildPartition] {
          def call(): CollectedBuildPartition = collectBuildPartition(p)
        }
      }
    buildSide(Scheduler.submitAndAwaitAll(tasks))
  }

  /** One build partition's batches, as read, and for computed keys each
    * row's key. */
  private def collectBuildPartition(p: Int): CollectedBuildPartition = {
    val it = buildPlan.execute(p)
    try collectBuild(it) finally Closing.close(it)
  }

  /** Every batch of `it`, kept as read (a batch is not changed once read),
    * and when the build keys are computed (non-ColRef) each row's key,
    * evaluated once a batch via `evalVec` and encoded from its values: null
    * where one is NULL, which matches nothing. Shared by the build
    * collection and the grace-hash bucket reload. */
  private def collectBuild(it: Iterator[ColumnarBatch]): CollectedBuildPartition = {
    val batches = mutable.ArrayBuffer.empty[ColumnarBatch]
    val keys: mutable.ArrayBuffer[AnyRef] = if (buildKeysAreColRefs) null else mutable.ArrayBuffer.empty[AnyRef]
    val keyBuf: Array[Any] = if (keys != null) new Array[Any](nKeys) else null
    var rows = 0L
    while (it.hasNext) {
      val b = it.next()
      val n = b.numRows
      if (n > 0) {
        batches += b
        rows += n
        if (keys != null) {
          val keyVecs = Array.tabulate(nKeys)(i => buildKeyExprs(i).evalVec(b))
          var r = 0
          while (r < n) {
            var anyNull = false
            var k = 0
            while (k < nKeys) {
              val kv = keyVecs(k)
              if (kv.isNull(r)) { anyNull = true; keyBuf(k) = null } else keyBuf(k) = kv.getBoxed(r)
              k += 1
            }
            keys += (if (anyNull) null else keyCodec.encodeBoxed(keyBuf))
            r += 1
          }
        }
      }
    }
    CollectedBuildPartition(batches, rows, keys)
  }

  /** The build side of `parts`' rows, in order: compacted into one batch,
    * and each row with no NULL key listed under its key. */
  private def buildSide(parts: Seq[CollectedBuildPartition]): BuildSide = {
    val total = parts.iterator.map(_.rows).sum
    require(total < Int.MaxValue, s"a join's build side holds $total rows, more than it can index")
    val n = total.toInt
    val batch = new ColumnarBatch(buildPlan.outputSchema, math.max(1, n))
    var at = 0
    var identity = new Array[Int](0)
    parts.foreach(_.batches.foreach { b =>
      val rows = b.numRows
      if (identity.length < rows) identity = Array.tabulate(rows)(i => i)
      var c = 0
      while (c < buildWidth) {
        batch.column(c).gather(b.column(c), identity, 0, rows, at)
        c += 1
      }
      at += rows
    })
    batch.setNumRows(n)
    if (useJoinLongKey) {
      val keyMap = new LongHashMap[IntList]()
      val col = batch.column(buildKeyLongIdx)
      var i = 0
      while (i < n) {
        if (!col.isNull(i)) {
          val k = KeyCodec.readAsLong(col, i)
          // Inline get-then-put to avoid the per-row Function0 allocation
          // `getOrInsert` would create from its by-name parameter.
          var list = keyMap.get(k)
          if (list == null) {
            list = new IntList
            val _ = keyMap.put(k, list)
          }
          list.add(i)
        }
        i += 1
      }
      BuildSide(batch, n, null, keyMap)
    } else {
      val keyMap = new util.HashMap[AnyRef, IntList]()
      def put(key: AnyRef, i: Int): Unit =
        if (key != null) {
          var list = keyMap.get(key)
          if (list == null) {
            list = new IntList
            keyMap.put(key, list)
          }
          list.add(i)
        }
      if (buildKeysAreColRefs) {
        var i = 0
        while (i < n) { put(buildCodec.encodeFromBatchSkipIfAnyNull(batch, i), i); i += 1 }
      } else {
        var i = 0
        parts.foreach(_.keys.foreach { key => put(key, i); i += 1 })
      }
      BuildSide(batch, n, keyMap, null)
    }
  }

  // ---- Probing -------------------------------------------------------------

  /** Joined rows being gathered into one output batch of `capacity` rows:
    * for each, its row in the probe batch it came from and its build row, -1
    * for a NULL-padded side. A probe batch's columns are gathered before the
    * cursor moves past it ([[gatherProbe]]), the build side's once the batch
    * is done ([[finish]]). */
  private final class Output(capacity: Int) {
    private val probeSel = new Array[Int](capacity)
    private val buildSel = new Array[Int](capacity)
    private val batch = new ColumnarBatch(outputSchema, capacity)
    private var count = 0
    private var probeGathered = 0

    def rows: Int = count
    def room: Int = capacity - count
    def full: Boolean = count == capacity

    def add(probeRow: Int, buildRow: Int): Unit = {
      probeSel(count) = probeRow
      buildSel(count) = buildRow
      count += 1
    }

    /** Probe row `probeRow` with build rows `rows(from)`, ..., `rows(from + n - 1)`. */
    def addRun(probeRow: Int, rows: Array[Int], from: Int, n: Int): Unit = {
      java.util.Arrays.fill(probeSel, count, count + n, probeRow)
      System.arraycopy(rows, from, buildSel, count, n)
      count += n
    }

    /** The probe columns of the rows added since the last gather, all from
      * `probe`. */
    def gatherProbe(probe: ColumnarBatch): Unit =
      if (probeGathered < count) {
        var j = 0
        while (j < probeWidth) {
          batch.column(probeOut + j).gather(probe.column(j), probeSel, probeGathered, count, probeGathered)
          j += 1
        }
        probeGathered = count
      }

    /** The batch, its build columns gathered from `build`. */
    def finish(build: BuildSide): ColumnarBatch = {
      var j = 0
      while (j < buildWidth) {
        batch.column(buildOut + j).gather(build.batch.column(j), buildSel, 0, count, 0)
        j += 1
      }
      batch.setNumRows(count)
      batch
    }
  }

  /** Where a probe of one stream of probe batches has read to, so it can stop
    * after any row or candidate pair and go on later, on this thread or
    * another: the stream (opened by the first fill), the batch it is in and
    * that batch's computed key vectors (evaluated once a batch), the next
    * row, and for the row being matched its candidates, the next one to
    * examine and whether a candidate kept it. Every path probes through one,
    * so match semantics live here alone.
    *
    * `matched`, when the join keeps unmatched build rows, collects the index
    * of every build row a probe row matched. A cursor is read by one thread
    * at a time: a wave's join, or a partition's pull, orders one fill before
    * the next, and no state here is tied to a thread. */
  private final class ProbeCursor(open: () => Iterator[ColumnarBatch], val build: BuildSide, val matched: util.BitSet) {
    private var batches: Iterator[ColumnarBatch] = null
    private var batch: ColumnarBatch = null
    private var keyVecs: Array[ColumnVector] = null
    private val keyBuf: Array[Any] = if (probeKeysAreColRefs) null else new Array[Any](nKeys)
    private var row = 0
    // The row being matched, when `inRow`: its candidates (null for none) and
    // the next to examine. `kept`: whether a candidate of the row whose pairs
    // were last checked passed the condition, until that row is finished.
    private var inRow = false
    private var candidates: IntList = null
    private var next = 0
    private var kept = false
    private var finished = false
    // `extra`'s candidate pairs and the batch they are gathered into.
    private var chunkProbe: Array[Int] = null
    private var chunkBuild: Array[Int] = null
    private var pairProbe: Array[Int] = null
    private var pairBuild: Array[Int] = null
    private var pairs: ColumnarBatch = null

    /** Whether the probe stream has been read to its end. */
    def done: Boolean = finished

    /** Add to `out` the joined rows that follow (and the null-padded rows of
      * unmatched probe rows a probe-preserving join keeps), until `out` is
      * full, `work` units are spent (a unit is a probe row read, a candidate
      * pair examined or an unmatched row padded, so a fill adds at most
      * `work` rows), or the stream ends; mid-row if need be, so a probe row
      * matching ten million build rows emits them a batch at a time. Row
      * counts go into `tallies`. Returns the units spent. */
    def fill(out: Output, work: Long, tallies: ProbeTallies): Long = {
      if (batches == null && !finished) batches = open()
      val spent = if (extra.isEmpty) fillPairs(out, work, tallies) else fillChecked(out, work, tallies)
      if (batch != null) out.gatherProbe(batch)
      spent
    }

    /** [[fill]] with no condition: every candidate pair is a joined row. */
    private def fillPairs(out: Output, work: Long, tallies: ProbeTallies): Long = {
      var spent = 0L
      while (!finished && !out.full && spent < work) {
        if (inRow) {
          if (candidates != null && next < candidates.size) {
            val n = math.min(math.min(candidates.size - next, out.room).toLong, work - spent).toInt
            out.addRun(row, candidates.values, next, n)
            if (matched != null) {
              var i = next
              while (i < next + n) { matched.set(candidates.values(i)); i += 1 }
            }
            next += n
            spent += n
            kept = true
          } else {
            if (kept) tallies.matchedProbeRows += 1L
            else if (preserveProbeOuter) {
              out.add(row, -1)
              tallies.unmatchedProbeRows += 1L
              spent += 1L
            }
            endRow()
          }
        } else if (batch != null && row < batch.numRows) {
          startRow(tallies)
          spent += 1L
        } else advance(out)
      }
      spent
    }

    /** [[fill]] with `extra`: the pairs that follow, a chunk at a time, each
      * chunk's condition evaluated at once ([[chunk]]). */
    private def fillChecked(out: Output, work: Long, tallies: ProbeTallies): Long = {
      var spent = 0L
      while (!finished && !out.full && spent < work) {
        if (batch != null && (inRow || row < batch.numRows)) spent += chunk(out, work - spent, tallies)
        else advance(out)
      }
      spent
    }

    /** The pairs and row ends that follow in the current batch, up to `out`'s
      * room and `budget` units: `extra` evaluated over the chunk's pairs at
      * once, the pairs it keeps added to `out` in order, and each row whose
      * candidates are all examined finished, its null-padded row added if the
      * join keeps it and none passed. A row end takes a slot of `out`'s room,
      * for the row it may add. Returns the units spent. */
    private def chunk(out: Output, budget: Long, tallies: ProbeTallies): Long = {
      if (chunkProbe == null) {
        val cap = ColumnarBatch.DefaultCapacity
        chunkProbe = new Array[Int](cap)
        chunkBuild = new Array[Int](cap)
        pairProbe = new Array[Int](cap)
        pairBuild = new Array[Int](cap)
        pairs = new ColumnarBatch(outputSchema, cap)
      }
      var n = 0
      var m = 0
      var slots = out.room
      var spent = 0L
      while (slots > 0 && spent < budget && (inRow || row < batch.numRows)) {
        if (!inRow) {
          startRow(tallies)
          spent += 1L
        } else if (candidates != null && next < candidates.size) {
          val b = candidates.values(next)
          chunkProbe(n) = row
          chunkBuild(n) = b
          pairProbe(m) = row
          pairBuild(m) = b
          n += 1
          m += 1
          next += 1
          spent += 1L
          slots -= 1
        } else {
          // The row's end, finished below once its pairs are checked.
          chunkProbe(n) = row
          chunkBuild(n) = -1
          n += 1
          if (preserveProbeOuter) spent += 1L
          slots -= 1
          inRow = false
          candidates = null
          row += 1
        }
      }
      val passes: BooleanVector = if (m == 0) null else check(m)
      var i = 0
      var pair = 0
      while (i < n) {
        val b = chunkBuild(i)
        if (b >= 0) {
          if (passes != null && !passes.nulls.get(pair) && passes.values(pair)) {
            out.add(chunkProbe(i), b)
            kept = true
            if (matched != null) matched.set(b)
          }
          pair += 1
        } else {
          if (kept) tallies.matchedProbeRows += 1L
          else if (preserveProbeOuter) {
            out.add(chunkProbe(i), -1)
            tallies.unmatchedProbeRows += 1L
          }
          kept = false
        }
        i += 1
      }
      spent
    }

    /** `extra` over the first `m` pairs, the columns it reads gathered from
      * the probe batch and the build side; NULL counts as false. */
    private def check(m: Int): BooleanVector = {
      var k = 0
      while (k < extraColumns.length) {
        val c = extraColumns(k)
        val isProbe = c >= probeOut && c < probeOut + probeWidth
        if (isProbe) pairs.column(c).gather(batch.column(c - probeOut), pairProbe, 0, m, 0)
        else pairs.column(c).gather(build.batch.column(c - buildOut), pairBuild, 0, m, 0)
        k += 1
      }
      pairs.setNumRows(m)
      extra.get.evalVec(pairs) match {
        case bv: BooleanVector => bv
        case other =>
          // A condition of no type but NULL: no pair passes.
          val none = new BooleanVector(new Array[Boolean](m), new util.BitSet(m), m)
          var r = 0
          while (r < m) { if (other.isNull(r)) none.setNull(r); r += 1 }
          none
      }
    }

    /** Start probe row `row` of the batch: its candidates looked up. */
    private def startRow(tallies: ProbeTallies): Unit = {
      tallies.inputRows += 1L
      candidates = lookup(row)
      next = 0
      inRow = true
    }

    /** The row is finished: on to the next. */
    private def endRow(): Unit = {
      inRow = false
      candidates = null
      kept = false
      row += 1
    }

    /** On to the next probe batch, its rows' probe columns gathered first;
      * or the end of the stream. */
    private def advance(out: Output): Unit = {
      if (batch != null) out.gatherProbe(batch)
      if (batches.hasNext) {
        batch = batches.next()
        row = 0
        // For computed probe keys, evaluate each key once a batch; ColRef
        // keys are read straight from the batch's columns.
        keyVecs = if (keyBuf == null) null else Array.tabulate(nKeys)(i => probeKeyExprs(i).evalVec(batch))
      } else close()
    }

    /** The build rows probe row `r` of the batch can match: none for a NULL
      * key, which matches nothing (three-valued logic). */
    private def lookup(r: Int): IntList =
      if (useJoinLongKey) {
        val col = batch.column(probeKeyLongIdx)
        if (col.isNull(r)) null
        else build.keyMapLong.get(KeyCodec.readAsLong(col, r))
      } else {
        val key: AnyRef =
          if (probeKeysAreColRefs) keyCodec.encodeFromBatchSkipIfAnyNull(batch, r)
          else {
            var k = 0
            var anyNull = false
            while (k < nKeys) {
              val kv = keyVecs(k)
              val v: Any = if (kv.isNull(r)) null else kv.getBoxed(r)
              if (v == null) anyNull = true
              keyBuf(k) = v
              k += 1
            }
            if (anyNull) null else keyCodec.encodeBoxed(keyBuf)
          }
        if (key == null) null else build.keyMap.get(key)
      }

    /** Stop reading: the stream is closed, read to its end or not. */
    def close(): Unit = {
      finished = true
      batch = null
      keyVecs = null
      candidates = null
      if (batches != null) Closing.close(batches)
    }
  }

  /** A fill of `cursor`, its probe-loop time and row counts recorded.
    * Returns the units it spent. */
  private def timedFill(cursor: ProbeCursor, out: Output, work: Long): Long = {
    val tallies = new ProbeTallies
    val t0 = if (metricsNode != null) System.nanoTime() else 0L
    val spent = cursor.fill(out, work, tallies)
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxProbeNanos).add(System.nanoTime() - t0)
      recordProbeMetrics(tallies)
    }
    spent
  }

  /** A cursor's joined rows, a batch of at most
    * [[ColumnarBatch.DefaultCapacity]] rows at a time, each made when it is
    * pulled: the broadcast, per-shard and grace paths. Holds a probe batch
    * and an output batch; closing it closes the probe stream. */
  private final class Probed(cursor: ProbeCursor) extends Iterator[ColumnarBatch] with AutoCloseable {
    private var ready: ColumnarBatch = null

    def hasNext: Boolean = {
      if (ready == null && !cursor.done) {
        val out = new Output(ColumnarBatch.DefaultCapacity)
        val _ = timedFill(cursor, out, Long.MaxValue)
        if (out.rows > 0) ready = out.finish(cursor.build)
      }
      ready != null
    }

    def next(): ColumnarBatch = {
      if (!hasNext) throw new NoSuchElementException("no batch left")
      val b = ready
      ready = null
      b
    }

    def close(): Unit = {
      ready = null
      cursor.close()
    }
  }

  /** Record the probe-side counters with the meanings documented in
    * docs/benchmarking.md: rows probed, probe rows with at least one match
    * surviving `extra`, and probe rows emitted null-padded by outer-join
    * preservation. (Unmatched BUILD rows are counted separately at
    * unmatched-build emission.) */
  private def recordProbeMetrics(t: ProbeTallies): Unit = {
    if (metricsNode == null) return
    metricsNode.counters(HashJoinExec.IdxProbeSideRows).add(t.inputRows)
    metricsNode.counters(HashJoinExec.IdxMatchedRows).add(t.matchedProbeRows)
    if (t.unmatchedProbeRows > 0L)
      metricsNode.counters(HashJoinExec.IdxUnmatchedRows).add(t.unmatchedProbeRows)
  }

  /** The build rows `matched` does not name, null-padded on the probe's side,
    * a batch at a time. */
  private def unmatchedBuildRows(build: BuildSide, matched: util.BitSet): Iterator[ColumnarBatch] =
    new Iterator[ColumnarBatch] {
      private var i = matched.nextClearBit(0)
      def hasNext: Boolean = i < build.rows
      def next(): ColumnarBatch = {
        if (!hasNext) throw new NoSuchElementException("no batch left")
        val capacity = ColumnarBatch.DefaultCapacity
        val sel = new Array[Int](capacity)
        var n = 0
        while (i < build.rows && n < capacity) {
          sel(n) = i
          n += 1
          i = matched.nextClearBit(i + 1)
        }
        val out = new ColumnarBatch(outputSchema, n)
        var j = 0
        while (j < probeWidth) {
          val col = out.column(probeOut + j)
          var r = 0
          while (r < n) { col.setNull(r); r += 1 }
          j += 1
        }
        j = 0
        while (j < buildWidth) {
          out.column(buildOut + j).gather(build.batch.column(j), sel, 0, n, 0)
          j += 1
        }
        out.setNumRows(n)
        if (metricsNode != null) metricsNode.counters(HashJoinExec.IdxUnmatchedRows).add(n.toLong)
        out
      }
    }

  // ---- Grace hash path -----------------------------------------------------
  //
  // When `opts.spillEnabled` and the join has equi-keys, route both sides into
  // K disk buckets keyed by `hash(joinKey) % K`. Then process each bucket pair
  // (build_k, probe_k) in turn: load build_k's rows into a fresh in-memory
  // BuildSide and stream probe_k against it. Memory bound per bucket pair ≈
  // size of one bucket's build side (≈ totalBuild / K), and an output batch.
  //
  // Bucket count K is fixed at `GraceBuckets` for v1 — recursive bucketing on
  // skewed data is v1.1 (a bucket too big to fit in memory will OOM). NULL join
  // keys deterministically land in bucket 0; they never match (3VL) but still
  // surface in outer-join unmatched-build / unmatched-probe emission.

  private val GraceBuckets: Int = 16

  /** Nothing runs until the first pull, which buckets both sides; the
    * buckets are then joined one after another as the output is pulled, and
    * the spill directory goes when the output is drained or closed, or the
    * bucketing fails. */
  private def executeGraceHash(partition: Int): Iterator[ColumnarBatch] =
    Closing.concat(Iterator.single { () =>
      val spillDir = Spill.openOperatorDir("hashjoin")
      try spillDir.closeOnDrain(executeGraceHash(partition, spillDir))
      catch { case t: Throwable => spillDir.close(); throw t }
    })

  /** [[executeGraceHash]]'s work, its buckets in `spillDir`. */
  private def executeGraceHash(partition: Int, spillDir: OperatorSpillDir): Iterator[ColumnarBatch] = {
    val K = GraceBuckets
    if (metricsNode != null) metricsNode.counters(HashJoinExec.IdxBucketCount).add(K.toLong)

    val buildParts: Seq[Int] = if (isSharded) Seq(partition) else 0 until buildPlan.numPartitions
    val probeParts: Seq[Int] = if (isSharded) Seq(partition) else 0 until probePlan.numPartitions

    val buildBuckets = bucketSide(
      side = HashJoinExec.SideBuild,
      parts = buildParts,
      plan = buildPlan,
      keyExprs = buildKeyExprs,
      keysAreColRefs = buildKeysAreColRefs,
      keyColIndices = buildKeyColIndices,
      K = K,
      spillDir = spillDir)
    val probeBuckets = bucketSide(
      side = HashJoinExec.SideProbe,
      parts = probeParts,
      plan = probePlan,
      keyExprs = probeKeyExprs,
      keysAreColRefs = probeKeysAreColRefs,
      keyColIndices = probeKeyColIndices,
      K = K,
      spillDir = spillDir)

    Closing.concat((0 until K).iterator.map(k => () => bucketPair(buildBuckets(k), probeBuckets(k))))
  }

  /** Route all rows from `parts` of `plan` into K bucket files inside
    * `spillDir`, keyed by `hash(joinKey) % K`. Returns one file path per
    * bucket; a `null` entry means that bucket was empty (no file written).
    *
    * `side` drives counter attribution and the spill-file naming. The
    * bytes-on-disk after close is the source of truth for
    * `bytesSpilledBuild` / `bytesSpilledProbe`, both populated from the
    * final `files`. */
  private def bucketSide(
      side: Int,
      parts: Seq[Int],
      plan: PhysicalPlan,
      keyExprs: Array[Expr],
      keysAreColRefs: Boolean,
      keyColIndices: Array[Int],
      K: Int,
      spillDir: OperatorSpillDir): Array[Path] = {
    val sideName = if (side == HashJoinExec.SideBuild) "build" else "probe"
    val schema = plan.outputSchema
    // Spill files are addressed by index on read-back; positional names keep a
    // join-derived input like `[k, v, k, v]` from colliding in parquet's
    // by-name column model. See Spill.positionalSchema.
    val spillFileSchema = Spill.positionalSchema(schema)
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
        writers(b) = new TParquetWriter(files(b), spillFileSchema, Map.empty)
      }
      writers(b).write(out)
      pending(b) = new ColumnarBatch(schema, ColumnarBatch.DefaultCapacity)
      pendingCounts(b) = 0
    }

    try parts.foreach { p =>
      val it = plan.execute(p)
      try while (it.hasNext) {
        val batch = it.next()
        if (batch.numRows > 0) routeBatchToBuckets(
          batch, ncols, keyExprs, keysAreColRefs, keyColIndices, K,
          pending, pendingCounts, flushBucket _)
      } finally Closing.close(it)
    }
    catch {
      case t: Throwable =>
        writers.foreach(w => if (w != null) w.abort())
        throw t
    }
    // Final flush, and the writers closed; a failure discards them all.
    var b = 0
    try {
      while (b < K) { flushBucket(b); b += 1 }
      b = 0
      while (b < K) {
        val w = writers(b)
        if (w != null) { val _ = w.close() }
        b += 1
      }
    } catch {
      case t: Throwable =>
        writers.foreach(w => if (w != null) w.abort())
        throw t
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
    val mixed = KeyHash(HashJoinExec.fmix32(h))
    (mixed & 0x7FFFFFFF) % K
  }

  /** One bucket pair, as it is pulled: `buildFile` loaded into a fresh
    * in-memory map (if non-null), `probeFile` streamed against it (if
    * non-null), and for a join that keeps unmatched build rows the bucket's
    * build rows no probe row matched. A bucket with no build rows is read
    * only by a join that keeps unmatched probe rows, every one of which it
    * keeps.
    *
    * Why per-bucket unmatched emission preserves correctness: each bucket's
    * build rows can only match probe rows in the SAME bucket (since both
    * sides hash by the same join key). A build row in bucket k whose key
    * is unmatched in this bucket's probe is unmatched globally. */
  private def bucketPair(buildFile: Path, probeFile: Path): Iterator[ColumnarBatch] = {
    val tLoad = if (metricsNode != null) System.nanoTime() else 0L
    val loaded = loadBuildFromBucket(buildFile)
    if (metricsNode != null) {
      metricsNode.counters(HashJoinExec.IdxBucketsLoadedNanos).add(System.nanoTime() - tLoad)
      if (loaded != null) metricsNode.counters(HashJoinExec.IdxBuildSideRows).add(loaded.rows.toLong)
    }
    if (loaded == null && (probeFile == null || !preserveProbeOuter)) return Iterator.empty
    val build = if (loaded != null) loaded else buildSide(Nil)
    val matched: util.BitSet = if (preserveBuildOuter) new util.BitSet(build.rows) else null
    val probed: Iterator[ColumnarBatch] =
      if (probeFile == null) Iterator.empty
      else new Probed(new ProbeCursor(() => readBucket(probeFile), build, matched))
    Closing.over(probed ++ {
      if (matched != null) unmatchedBuildRows(build, matched) else Iterator.empty
    }, probed)
  }

  /** Every batch of a bucket file, each of its partitions read when the one
    * before it runs out. */
  private def readBucket(file: Path): Iterator[ColumnarBatch] = {
    val reader = ParquetReader.fromPath(file.toString)
    Closing.concat((0 until reader.numPartitions).iterator.map(p => () => reader.readPartition(p)))
  }

  /** Load every row of `file` into an in-memory [[BuildSide]]. Returns
    * `null` for empty / absent files. */
  private def loadBuildFromBucket(file: Path): BuildSide = {
    if (file == null) return null
    val it = readBucket(file)
    val collected = try collectBuild(it) finally Closing.close(it)
    if (collected.rows == 0L) null else buildSide(Vector(collected))
  }
}

object HashJoinExec {
  /** How many units of work (a probe row read, a candidate pair examined, an
    * unmatched probe row padded) one pool task of a wave does before the
    * wave's output is emitted: a wave holds at most
    * [[Scheduler.parallelism]] times this many rows. */
  private[exec] final val PairsPerTask: Int = 1 << 15

  /** The paths of the class scaladoc. */
  private sealed trait Path
  private case object Broadcast extends Path
  private case object InWaves extends Path
  private case object PerShard extends Path
  private case object Grace extends Path

  // ---- Counter indices ------------------------------------------------------
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
  final val KeyPathLong: Long         = 0L
  final val KeyPathPacked: Long       = 1L
  final val KeyPathObject: Long       = 2L
  final val KeyPathSingleObject: Long = 3L

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
    * vectors. Grace-hash join and window spill route rows to buckets with it,
    * so two keys the codec calls equal must hash equally: a byte array by its
    * content (its own `hashCode` is its identity) and a decimal by its value
    * ([[HashPartitioner.decimalHash]]). Other reference vectors fall back to
    * the boxed `hashCode` via `getBoxed`. */
  private[exec] def columnHashCode(col: ColumnVector, row: Int): Int = col match {
    case lv: LongVector =>
      val v = lv.values(row); (v ^ (v >>> 32)).toInt
    case iv: IntVector => iv.values(row)
    case dv: DoubleVector =>
      val v = KeyCodec.doubleKeyBits(dv.values(row))
      (v ^ (v >>> 32)).toInt
    case fv: FloatVector => KeyCodec.floatKeyBits(fv.values(row))
    case bv: BooleanVector => if (bv.values(row)) 1231 else 1237
    case tv: TimestampVector =>
      val v = tv.values(row); (v ^ (v >>> 32)).toInt
    case dv: DateVector => dv.values(row)
    case bv: BinaryVector =>
      val v = bv.values(row)
      if (v == null) 0 else java.util.Arrays.hashCode(v)
    case dv: DecimalVector =>
      val v = dv.values(row)
      if (v == null) 0 else HashPartitioner.decimalHash(v)
    case other =>
      val boxed = other.getBoxed(row)
      if (boxed == null) 0 else boxed.hashCode
  }
}

/** A build side: every build row compacted into one batch, and the rows
  * under each key, by index, a row with a NULL key under none (it matches
  * nothing, but a join that keeps unmatched build rows still emits it).
  * Exactly one of `keyMap` / `keyMapLong` is non-null — `keyMapLong` when
  * the equi-join key is a single fixed-width-numeric ColRef on both sides,
  * `keyMap` otherwise. Read-only once built: a broadcast join's is read by
  * every partition's probe at once. */
private[exec] final case class BuildSide(
    batch: ColumnarBatch,
    rows: Int,
    keyMap: util.HashMap[AnyRef, IntList],
    keyMapLong: LongHashMap[IntList])

/** The build rows under one key: their indices, in the order the rows were
  * read, unboxed. */
private[exec] final class IntList {
  var values: Array[Int] = new Array[Int](2)
  var size: Int = 0
  def add(v: Int): Unit = {
    if (size == values.length) values = java.util.Arrays.copyOf(values, size * 2)
    values(size) = v
    size += 1
  }
}

/** One build partition's batches, as read, their row count, and the
  * pre-encoded key of each row when build keys are computed expressions
  * (`keys` is null for ColRef keys, which are encoded from the compacted
  * batch). */
private[exec] final case class CollectedBuildPartition(
    batches: mutable.ArrayBuffer[ColumnarBatch],
    rows: Long,
    keys: mutable.ArrayBuffer[AnyRef])

/** Probe-side row counts a [[HashJoinExec]] cursor accumulates over one fill
  * and surfaces through the `probeSideRows` / `matchedRows` /
  * `unmatchedRows` counters (see docs/benchmarking.md for their meanings). */
private[exec] final class ProbeTallies {
  var inputRows: Long = 0L
  var matchedProbeRows: Long = 0L
  var unmatchedProbeRows: Long = 0L
}
