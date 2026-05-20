package com.transformer.sql.exec

import com.transformer.core.{CatalogView, DataType, ExecutionOptions}
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._

import scala.collection.mutable

/** Logical → physical conversion. Applies a small set of rewrites first
  * (projection pruning, predicate pushdown for join keys, equality split,
  * build-side selection for hash joins).
  *
  * Accepts [[ExecutionOptions]] (defaults to [[ExecutionOptions.Default]] for
  * callers that don't carry per-task config). Spill-capable operators read the
  * `opts` value at construction; today no operator consumes it, so the field
  * is plumbed but functionally inert — Phase 1 of the spill plan is plumbing
  * only.
  */
object PhysicalPlanner {

  def plan(logical: LogicalPlan, opts: ExecutionOptions = ExecutionOptions.Default): PhysicalPlan = logical match {
    case LogicalScan(_, view, _) => ScanExec(view)
    case LogicalFilter(LogicalScan(name, view, schema), pred) =>
      // Best-effort: try to push the predicate into the scan view. The original
      // FilterExec stays in place — pushdown only enables row-group skipping
      // (stats can prove non-matching groups, never prove matching ones).
      val pushed = tryPushdown(view, pred)
      FilterExec(ScanExec(pushed.getOrElse(view)), pred)
    case LogicalFilter(child, pred) => FilterExec(plan(child, opts), pred)
    case LogicalProject(child, projs) => ProjectExec(plan(child, opts), projs)
    // Fast path: `SELECT COUNT(*) FROM <view>` with no WHERE, no GROUP BY, no HAVING.
    // The view can answer from metadata (parquet footer, in-memory row count) so
    // we skip the entire scan + per-row aggregation pipeline.
    case LogicalAggregate(LogicalScan(_, view, _), Seq(), Seq((AggExprCountStar(), name)), None)
        if view.exactRowCount.isDefined =>
      CountStarMetadataExec(view.exactRowCount.get, name)
    case LogicalAggregate(child, gks, aggs, _) =>
      val physChild = plan(child, opts)
      if (gks.isEmpty || !shouldShardForSize(child)) {
        // No GROUP BY, OR the estimated input is small enough that the
        // exchange-shard overhead would dominate the per-shard parallelism.
        // Fall back to the collapsing aggregate (fan out + merge).
        HashAggregateExec(physChild, gks, aggs, opts)
      } else {
        val partitionBy = gks.iterator.map(_._1).toSeq
        val exchanged = ExchangeExec(
          physChild,
          partitionBy,
          ExchangeExec.defaultNumShards,
          HashPartitioner.NullsToLast)
        HashAggregateExec(exchanged, gks, aggs, opts)
      }
    case LogicalSort(child, keys) => SortExec(plan(child, opts), keys, opts)
    case LogicalDistinct(child) =>
      val physChild = plan(child, opts)
      if (shouldShardForSize(child)) buildShardedDistinct(physChild, opts)
      else DistinctExec(physChild, opts)
    case LogicalUnion(l, r, all) =>
      val u = UnionExec(plan(l, opts), plan(r, opts))
      if (all) u
      else if (shouldShardUnion(l, r)) buildShardedDistinct(u, opts)
      else DistinctExec(u, opts)
    case LogicalLimit(child, n) =>
      val p = plan(child, opts)
      if (p.numPartitions <= 1) LocalLimitExec(p, n)
      else GlobalLimitExec(LocalLimitExec(p, n), n)
    case LogicalJoin(l, r, cond, kind) =>
      val left = plan(l, opts)
      val right = plan(r, opts)
      val (leftKeys, rightKeys, extra) = splitEqualityKeys(cond, l.outputSchema.length, r.outputSchema.length)
      if (leftKeys.isEmpty && rightKeys.isEmpty) enforceNestedLoopGuard(l, r)
      val buildRight = shouldBuildRight(l, r, kind)
      if (leftKeys.isEmpty || !shouldShardJoin(l, r)) {
        // Either non-equi (no keys to shard by) OR the smaller side is
        // broadcast-shaped (fits in heap) so we keep the historic shape:
        // build one side, stream the other. Sharding both sides forces an
        // extra pass over the *large* side just to redistribute rows; with
        // a small build that pass dominates the join itself.
        HashJoinExec(left, right, leftKeys, rightKeys, extra, kind, buildRight, opts)
      } else {
        // Equi-join with both sides large enough to benefit from sharded
        // build+probe. Hash-partition both sides by their respective keys so
        // matching keys collocate. NullsToZero policy — NULL keys never
        // match (SQL 3VL) but must still land in some shard so outer-join
        // unmatched emission sees them.
        val K = ExchangeExec.defaultNumShards
        val leftExch = ExchangeExec(left, leftKeys, K, HashPartitioner.NullsToZero)
        val rightExch = ExchangeExec(right, rightKeys, K, HashPartitioner.NullsToZero)
        HashJoinExec(leftExch, rightExch, leftKeys, rightKeys, extra, kind, buildRight, opts)
      }
    case LogicalWindow(child, windows) =>
      val physChild = plan(child, opts)
      buildWindow(physChild, child, windows, opts)
  }

  /** Wrap a window's child in `ExchangeExec` when sharding is both safe
    * (every [[WindowSpec]] agrees on the same non-empty PARTITION BY key
    * set) AND cardinality-justified (input estimate ≥
    * [[LogicalPlanCardinality.MinShardableSize]]).
    *
    * Disagreeing PARTITION BYs would each need a different sharding — no
    * single exchange satisfies both — so we fall back to the historic
    * collapsing window. Empty PARTITION BY means the window spans the
    * whole result; sharding would split rows that must be computed
    * together. Below the cardinality threshold, the exchange overhead
    * isn't worth the parallelism (the same Phase 6 gate as aggregates). */
  private def buildWindow(
      physChild: PhysicalPlan,
      logicalChild: LogicalPlan,
      windows: Seq[WindowDef],
      opts: ExecutionOptions): WindowExec = {
    val distinctParts = windows.map(_.spec.partitionKeys).distinct
    val canShard =
      distinctParts.length == 1 && distinctParts.head.nonEmpty &&
      shouldShardForSize(logicalChild)
    if (canShard) {
      val partitionBy = distinctParts.head
      val exchanged = ExchangeExec(
        physChild,
        partitionBy,
        ExchangeExec.defaultNumShards,
        HashPartitioner.NullsToLast)
      WindowExec(exchanged, windows, opts)
    } else {
      WindowExec(physChild, windows, opts)
    }
  }

  /** Wrap a physical plan in `ExchangeExec → DistinctExec` so dedup runs
    * per-shard. The exchange's `partitionBy` is the full column set the
    * dedup keys on — every output column becomes a [[ColRefExpr]], so two
    * rows are equal iff they collide on every column, the same predicate
    * DistinctExec's per-shard HashSet uses. */
  private def buildShardedDistinct(child: PhysicalPlan, opts: ExecutionOptions): DistinctExec = {
    val schema = child.outputSchema
    val ncols = schema.length
    val partitionBy = (0 until ncols).map { i =>
      val f = schema.fields(i)
      ColRefExpr(i, f.name, f.dataType)
    }
    val exchanged = ExchangeExec(
      child,
      partitionBy,
      ExchangeExec.defaultNumShards,
      HashPartitioner.NullsToLast)
    DistinctExec(exchanged, opts)
  }

  /** Cardinality gate for aggregate / distinct sharding: insert an
    * `ExchangeExec` only when the planner can prove the input is at least
    * [[LogicalPlanCardinality.MinShardableSize]] rows. Unknown size (`None`)
    * defaults to NOT sharding — the exchange's full materialization +
    * scatter overhead is a real cost, and silently paying it on inputs
    * whose size we can't reason about (streaming CSV) is the wrong default.
    * Callers with known-large inputs (parquet, in-memory views) get the
    * sharded parallelism; everyone else gets the collapsing path that ran
    * before plan 04 landed. */
  private def shouldShardForSize(child: LogicalPlan): Boolean =
    LogicalPlanCardinality.estimate(child).exists(_ >= LogicalPlanCardinality.MinShardableSize)

  /** Cardinality gate for [[LogicalUnion]]'s downstream `DistinctExec`. The
    * union's row count is the sum of the two sides; we shard the dedup only
    * when that sum is large enough to justify the exchange. Unknown on
    * either side defaults to not sharding (conservative). */
  private def shouldShardUnion(l: LogicalPlan, r: LogicalPlan): Boolean = {
    val total = for {
      lc <- LogicalPlanCardinality.estimate(l)
      rc <- LogicalPlanCardinality.estimate(r)
    } yield lc + rc
    total.exists(_ >= LogicalPlanCardinality.MinShardableSize)
  }

  /** Cardinality gate for hash-join sharding: shard both sides only when
    * the *smaller* side is above [[LogicalPlanCardinality.BroadcastBuildThreshold]].
    * Below that, the smaller side fits in heap and the historic broadcast
    * shape (build small once, stream-probe large) is faster than scattering
    * the large side just to redistribute rows. */
  private def shouldShardJoin(l: LogicalPlan, r: LogicalPlan): Boolean = {
    (LogicalPlanCardinality.estimate(l), LogicalPlanCardinality.estimate(r)) match {
      case (Some(lc), Some(rc)) =>
        math.min(lc, rc) >= LogicalPlanCardinality.BroadcastBuildThreshold
      case _ =>
        // Either side unknown — broadcast via the historic collapsing path
        // rather than risk the perf trap of scattering a huge unknown input.
        false
    }
  }

  /** Minimum size ratio at which we'll swap the join build side. Keeps near-
    * equal estimates pinned to the default plan — the win below this ratio is
    * a wash and not worth the risk that the estimator was wrong.
    */
  private val JoinSwapRatio: Double = 2.0

  /** Refuse a nested-loop-style join (no equality conjuncts) when both
    * estimated sides exceed this row count. Below the threshold the
    * degenerate-hash path is fine — small × small is cheap. Above it, the
    * planner forces the user to add equality keys rather than silently
    * planning an O(N*M) join over millions of rows. */
  private val NestedLoopMaxRows: Long = 5000L

  /** Decide which side of a join to build into the hash table.
    *
    * Returns `true` when the right side should be built (the historic shape).
    * Returns `false` when the planner should swap to building the left side.
    *
    * The decision is driven by [[LogicalPlanCardinality.estimate]] for inner
    * joins (build the smaller side, with a threshold to ignore near-equal
    * sizes), and pinned by join kind for outer joins (the preserved side
    * stays the probe, so a RIGHT outer always swaps and a LEFT outer never
    * does — there's no symmetric "build smaller" call to make once the
    * preservation requirement is fixed). FULL outer stays at the default
    * because both sides emit unmatched rows regardless of build choice.
    *
    * If estimates are unavailable for either side (e.g. CSV inputs with no
    * exactRowCount), fall back to `true` — no information beats a guess.
    */
  private def shouldBuildRight(l: LogicalPlan, r: LogicalPlan, kind: JoinKind): Boolean = kind match {
    case JoinKind.Inner =>
      (LogicalPlanCardinality.estimate(l), LogicalPlanCardinality.estimate(r)) match {
        case (Some(lc), Some(rc)) if lc.toDouble * JoinSwapRatio <= rc.toDouble => false
        case _ => true
      }
    case JoinKind.Left  => true
    case JoinKind.Right => false
    case JoinKind.Full  => true
  }

  /** Throw when a non-equi join would have to scan a known-large input on
    * both sides. The check is intentionally conservative: it never refuses a
    * plan we can't size (estimates are `None` for streaming CSV inputs, so
    * those still get a degenerate-hash plan). Only when both sides expose an
    * exact row count and the smaller of the two exceeds [[NestedLoopMaxRows]]
    * do we bail — at that point the user has the information to add
    * equality keys and we have no business silently materializing a
    * cartesian product.
    */
  private def enforceNestedLoopGuard(l: LogicalPlan, r: LogicalPlan): Unit = {
    val lEst = LogicalPlanCardinality.estimate(l)
    val rEst = LogicalPlanCardinality.estimate(r)
    (lEst, rEst) match {
      case (Some(lc), Some(rc)) if math.min(lc, rc) > NestedLoopMaxRows =>
        throw new UnsupportedOperationException(
          s"non-equi join over >$NestedLoopMaxRows rows requires equality keys " +
          s"(left=$lc, right=$rc rows)")
      case _ => ()
    }
  }

  /** Try to push a bound predicate into the underlying view. Today only
    * [[ParquetReader]] participates — its `withPushdownFilter` translates the
    * expression to a parquet `FilterPredicate` and returns a new view that
    * skips row groups whose column statistics rule them out. Other view
    * implementations return None and the planner stays on the original. */
  private def tryPushdown(view: CatalogView, predicate: Expr): Option[CatalogView] = view match {
    case p: ParquetReader => p.withPushdownFilter(predicate)
    case _                => None
  }

  /** Split an AND-chain of conjuncts into (leftKey, rightKey) equality pairs and a
    * residual predicate. References on the right side of the join begin at column
    * index `leftWidth` in the combined schema.
    */
  private def splitEqualityKeys(cond: Expr, leftWidth: Int, rightWidth: Int): (Seq[Expr], Seq[Expr], Option[Expr]) = {
    val conjuncts = collectConjuncts(cond)
    val leftKeys = mutable.ArrayBuffer.empty[Expr]
    val rightKeys = mutable.ArrayBuffer.empty[Expr]
    val rest = mutable.ArrayBuffer.empty[Expr]
    conjuncts.foreach {
      case BinOpExpr("=", l, r, _) =>
        (JoinSideAnalysis.sideOf(l, leftWidth), JoinSideAnalysis.sideOf(r, leftWidth)) match {
          case (JoinSide.LeftOnly, JoinSide.RightOnly) =>
            leftKeys += l
            rightKeys += JoinSideAnalysis.shiftToRight(r, leftWidth)
          case (JoinSide.RightOnly, JoinSide.LeftOnly) =>
            leftKeys += r
            rightKeys += JoinSideAnalysis.shiftToRight(l, leftWidth)
          case _ => rest += BinOpExpr("=", l, r, DataType.BooleanType)
        }
      case other => rest += other
    }
    val residual = rest.reduceLeftOption[Expr] { (a, b) => BinOpExpr("AND", a, b, DataType.BooleanType) }
    (leftKeys.toSeq, rightKeys.toSeq, residual)
  }

  private def collectConjuncts(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => collectConjuncts(l) ++ collectConjuncts(r)
    case other => Seq(other)
  }
}
