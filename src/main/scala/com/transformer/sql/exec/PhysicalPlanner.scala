package com.transformer.sql.exec

import com.transformer.core.{CatalogView, DataType}
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._

import scala.collection.mutable

/** Logical → physical conversion. Applies a small set of rewrites first
  * (projection pruning, predicate pushdown for join keys, equality split,
  * build-side selection for hash joins).
  */
object PhysicalPlanner {

  def plan(logical: LogicalPlan): PhysicalPlan = logical match {
    case LogicalScan(_, view, _) => ScanExec(view)
    case LogicalFilter(LogicalScan(name, view, schema), pred) =>
      // Best-effort: try to push the predicate into the scan view. The original
      // FilterExec stays in place — pushdown only enables row-group skipping
      // (stats can prove non-matching groups, never prove matching ones).
      val pushed = tryPushdown(view, pred)
      FilterExec(ScanExec(pushed.getOrElse(view)), pred)
    case LogicalFilter(child, pred) => FilterExec(plan(child), pred)
    case LogicalProject(child, projs) => ProjectExec(plan(child), projs)
    // Fast path: `SELECT COUNT(*) FROM <view>` with no WHERE, no GROUP BY, no HAVING.
    // The view can answer from metadata (parquet footer, in-memory row count) so
    // we skip the entire scan + per-row aggregation pipeline.
    case LogicalAggregate(LogicalScan(_, view, _), Seq(), Seq((AggExprCountStar(), name)), None)
        if view.exactRowCount.isDefined =>
      CountStarMetadataExec(view.exactRowCount.get, name)
    case LogicalAggregate(child, gks, aggs, _) => HashAggregateExec(plan(child), gks, aggs)
    case LogicalSort(child, keys) => SortExec(plan(child), keys)
    case LogicalDistinct(child) => DistinctExec(plan(child))
    case LogicalUnion(l, r, all) =>
      val u = UnionExec(plan(l), plan(r))
      if (all) u else DistinctExec(u)
    case LogicalLimit(child, n) =>
      val p = plan(child)
      if (p.numPartitions <= 1) LocalLimitExec(p, n)
      else GlobalLimitExec(LocalLimitExec(p, n), n)
    case LogicalJoin(l, r, cond, kind) =>
      val left = plan(l)
      val right = plan(r)
      val (leftKeys, rightKeys, extra) = splitEqualityKeys(cond, l.outputSchema.length, r.outputSchema.length)
      if (leftKeys.isEmpty && rightKeys.isEmpty) enforceNestedLoopGuard(l, r)
      val buildRight = shouldBuildRight(l, r, kind)
      HashJoinExec(left, right, leftKeys, rightKeys, extra, kind, buildRight)
    case LogicalWindow(child, windows) =>
      WindowExec(plan(child), windows)
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
