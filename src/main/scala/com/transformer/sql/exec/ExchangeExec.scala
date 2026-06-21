package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._

import java.util.concurrent.Callable
import scala.collection.mutable

/** Hash-partitioning exchange. Reshapes its child's output from
  * `child.numPartitions` partitions into `numShards` key-disjoint shards. Rows
  * with the same `partitionBy` key always land in the same shard, so a
  * downstream pipeline breaker (HashAggregate, HashJoin, Distinct, Window)
  * can process each shard independently with no cross-shard coordination.
  *
  * Spark-equivalent shape: ShuffleExchangeExec. Local-equivalent because there
  * is no serialization, no disk, no network — the materialized shuffle is K
  * arrays of [[ColumnarBatch]]es held in heap.
  *
  * ## Execution model
  *
  * Lazy materialization: the first `execute(s)` call on any shard triggers
  * the full materialization (all child partitions read, every row routed to
  * its shard buffer). Subsequent `execute(s)` calls — including for other
  * shards from other worker threads — return iterators over the already-built
  * shard arrays. Materialization itself runs the child's partitions in
  * parallel through the shared [[Scheduler]] pool.
  *
  * ## Row routing
  *
  * One pass per input batch:
  *   1. Evaluate each `partitionBy` expression once via [[Expr.evalVec]] —
  *      one vector per key per batch. ColRef keys alias the source column;
  *      computed keys allocate one fresh vector.
  *   2. Per row, ask [[HashPartitioner.shardIdx]] which shard to land in.
  *   3. Pre-count rows per shard, then scatter into K size-exact
  *      [[ColumnarBatch]]es (one per shard for this input batch). Empty
  *      shards emit nothing for this batch.
  *
  * Output batches are smaller than the input batch by a factor of ~K under
  * uniform distribution. That increases per-batch dispatch overhead
  * downstream — a coalescing pass is a Phase-2+ optimization, not v1.
  *
  * ## NULL handling
  *
  * Routed by the caller's [[HashPartitioner.NullPolicy]]. For GROUP BY /
  * DISTINCT / PARTITION BY use [[HashPartitioner.NullsToLast]] (NULLs group
  * together in shard K-1). For equi-join probes use
  * [[HashPartitioner.NullsToZero]] — a NULL key can never match (SQL three-
  * valued logic) but the row must still reach some shard for outer-join
  * unmatched emission.
  *
  * ## Memory
  *
  * Materializes the entire child output across K shards before any downstream
  * operator starts. This is roughly the same amount of memory the historical
  * `HashAggregate.partialAggregate` already paid — the buffering is just
  * physical batches instead of in-progress agg state. No new spill semantics
  * are introduced.
  */
final case class ExchangeExec(
    child: PhysicalPlan,
    partitionBy: Seq[Expr],
    numShards: Int,
    nullPolicy: HashPartitioner.NullPolicy = HashPartitioner.NullsToLast,
    metricsNode: MetricsNode = null)
    extends PhysicalPlan {

  require(numShards > 0, s"numShards must be positive, got $numShards")

  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = numShards

  private val keyExprs: Array[Expr] = partitionBy.toArray

  // Double-checked-locking-style lazy init. `@volatile` so the first thread
  // to publish a non-null `shards` makes its writes visible to other threads
  // skipping the synchronized block.
  @volatile private var shards: Array[Array[ColumnarBatch]] = null

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numShards,
      s"partition $partition out of range [0,$numShards)")
    val mat = ensureMaterialized()
    mat(partition).iterator
  }

  private def ensureMaterialized(): Array[Array[ColumnarBatch]] = {
    val s = shards
    if (s != null) return s
    synchronized {
      if (shards == null) shards = materialize()
      shards
    }
  }

  private def materialize(): Array[Array[ColumnarBatch]] = {
    val nChildParts = child.numPartitions
    if (nChildParts == 0) return Array.fill(numShards)(Array.empty[ColumnarBatch])
    val tasks: Seq[Callable[Array[Array[ColumnarBatch]]]] =
      (0 until nChildParts).map { p =>
        new Callable[Array[Array[ColumnarBatch]]] {
          def call(): Array[Array[ColumnarBatch]] = shardChildPartition(p)
        }
      }
    val perPartition: IndexedSeq[Array[Array[ColumnarBatch]]] =
      Scheduler.submitAndAwaitAll(tasks)
    val combined = new Array[Array[ColumnarBatch]](numShards)
    var s = 0
    while (s < numShards) {
      val buf = mutable.ArrayBuffer.empty[ColumnarBatch]
      var p = 0
      while (p < perPartition.length) {
        val partShards = perPartition(p)
        val batches = partShards(s)
        var b = 0
        while (b < batches.length) {
          buf += batches(b)
          b += 1
        }
        p += 1
      }
      combined(s) = buf.toArray
      s += 1
    }
    combined
  }

  /** Hash-partition every batch produced by `child.execute(p)` into K
    * per-shard arrays. Returns `Array[shardIdx -> Array[batch]]`. */
  private def shardChildPartition(p: Int): Array[Array[ColumnarBatch]] = {
    val perShard = Array.fill(numShards)(mutable.ArrayBuffer.empty[ColumnarBatch])
    val it = child.execute(p)
    while (it.hasNext) {
      val batch = it.next()
      if (batch.numRows > 0) routeBatch(batch, perShard)
    }
    Array.tabulate(numShards)(s => perShard(s).toArray)
  }

  /** Compute shard assignments for one input batch and scatter rows into K
    * per-shard output batches sized exactly to their row counts. */
  private def routeBatch(
      batch: ColumnarBatch,
      perShard: Array[mutable.ArrayBuffer[ColumnarBatch]]): Unit = {
    val n = batch.numRows
    val nKeys = keyExprs.length

    // 1. Evaluate each key expression once per batch; for ColRefExpr the
    // returned ColumnVector aliases the source column (no copy).
    val keyVecs = new Array[ColumnVector](nKeys)
    var k = 0
    while (k < nKeys) { keyVecs(k) = keyExprs(k).evalVec(batch); k += 1 }

    // 2. Assign every row to a shard. One Int per row.
    val shardOf = new Array[Int](n)
    var nullKeyRowsHere: Long = 0L
    var r = 0
    while (r < n) {
      shardOf(r) = HashPartitioner.shardIdx(keyVecs, r, numShards, nullPolicy)
      if (metricsNode != null && nKeys > 0) {
        // Count rows where at least one partition key is NULL — surfaced as
        // `keyNullCount`. Only the first nKeys check is cheap because we
        // already evaluated the key vectors above.
        var ki = 0
        var anyNull = false
        while (ki < nKeys && !anyNull) {
          if (keyVecs(ki).isNull(r)) anyNull = true
          ki += 1
        }
        if (anyNull) nullKeyRowsHere += 1L
      }
      r += 1
    }

    // 3. Pre-count rows per shard so output batches are size-exact.
    val counts = new Array[Int](numShards)
    r = 0
    while (r < n) { counts(shardOf(r)) += 1; r += 1 }

    // Surface counters: keyNullCount + per-shard row counts. The shard slots
    // are sequentially numbered IdxShard0..IdxShard(K-1) so the consumer can
    // read skew directly from `_perf.json`.
    if (metricsNode != null) {
      if (nullKeyRowsHere > 0L) metricsNode.counters(ExchangeExec.IdxKeyNullCount).add(nullKeyRowsHere)
      var s = 0
      while (s < numShards) {
        val c = counts(s)
        if (c > 0) metricsNode.counters(ExchangeExec.IdxShardBase + s).add(c.toLong)
        s += 1
      }
    }

    // 4. Allocate per-shard output batches. Skip empty shards entirely —
    // ColumnarBatch requires capacity > 0 and downstream operators see
    // exactly the batches we emit.
    val schema = batch.schema
    val ncols = schema.length
    val outBatches = new Array[ColumnarBatch](numShards)
    var s = 0
    while (s < numShards) {
      if (counts(s) > 0) outBatches(s) = new ColumnarBatch(schema, counts(s))
      s += 1
    }

    // 5. Single pass through input rows, copying each to its shard's batch.
    val nextRow = new Array[Int](numShards)
    r = 0
    while (r < n) {
      val shard = shardOf(r)
      val dst = outBatches(shard)
      val dstRow = nextRow(shard)
      var c = 0
      while (c < ncols) {
        batch.column(c).copyTo(r, dst.column(c), dstRow)
        c += 1
      }
      nextRow(shard) = dstRow + 1
      r += 1
    }

    // 6. Publish into per-shard buffers.
    s = 0
    while (s < numShards) {
      if (counts(s) > 0) {
        outBatches(s).setNumRows(counts(s))
        perShard(s) += outBatches(s)
      }
      s += 1
    }
  }
}

object ExchangeExec {
  /** Default shard count used by callers (planner, breakers) that don't have
    * a workload-specific reason to override. Tracks [[Scheduler.parallelism]]
    * so a JVM sized for high concurrency gets proportionally more shards;
    * override globally via the `transformer.scheduler.shard_count` system
    * property or `TRANSFORMER_SCHEDULER_SHARD_COUNT` env var.
    *
    * Going higher costs heap (K × in-flight per-batch overhead during
    * materialization) and produces K writer part-files past any sharded
    * breaker; going lower flattens the post-breaker parallelism the whole
    * point of this operator is to unlock. */
  val defaultNumShards: Int = {
    val configured = Option(System.getProperty("transformer.scheduler.shard_count"))
      .orElse(Option(System.getenv("TRANSFORMER_SCHEDULER_SHARD_COUNT")))
      .map(_.trim).filter(_.nonEmpty)
      .flatMap(s => try Some(s.toInt) catch { case _: NumberFormatException => None })
    math.max(1, configured.getOrElse(Scheduler.parallelism))
  }

  // ---- Counter indices ------------------------------------------------------
  // ExchangeExec's counter array length depends on the configured shard
  // count: one slot per shard plus a single `keyNullCount` slot. The
  // OperatorMetrics JSON shape stores each counter as one Long, so we use
  // individual `IdxShardN` slots rather than encoding the array into a single
  // counter — the JSON consumer reads them as named keys.
  //
  // Layout (length = 1 + numShards):
  //   [0]                       keyNullCount
  //   [1 .. numShards]          shard0Rows, shard1Rows, ..., shardN-1 rows
  final val IdxKeyNullCount: Int = 0
  final val IdxShardBase: Int    = 1

  /** Produce the IdxCounterNames array for an ExchangeExec with `numShards`
    * shards. Different instances of ExchangeExec in the same query may have
    * different shard counts (the planner picks K per breaker), so the array
    * is per-instance. */
  def counterNamesFor(numShards: Int): Array[String] = {
    val names = new Array[String](1 + numShards)
    names(0) = "keyNullCount"
    var i = 0
    while (i < numShards) {
      names(1 + i) = s"rowsRoutedShard$i"
      i += 1
    }
    names
  }
}
