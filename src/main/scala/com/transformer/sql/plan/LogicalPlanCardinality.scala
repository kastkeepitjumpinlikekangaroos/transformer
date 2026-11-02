package com.transformer.sql.plan

import com.transformer.core.Settings

/** Plan-time row-count estimation. Used by
  * [[com.transformer.sql.exec.PhysicalPlanner]] to pick the build side of a
  * hash join.
  *
  * `estimate` returns `None` when no estimate can be derived (e.g. a scan
  * over a view that doesn't expose `exactRowCount`, like a streaming CSV).
  * Callers should treat `None` as "fall back to the default plan" rather
  * than "zero rows".
  *
  * Selectivity constants are deliberately crude — Spark uses sketches and a
  * statistics catalog; this codebase doesn't carry that infrastructure and
  * isn't supposed to acquire it (see plans/perf/05). The estimator's only
  * job is to discriminate "1k rows" from "1M rows" so the planner picks the
  * correct build side. A wildly-wrong selectivity number is fine; the worst
  * case is "we don't swap when we should have", and the executor still
  * produces the right answer.
  */
object LogicalPlanCardinality {

  /** Selectivity defaults. Tunable as a unit once profiling shows real-world
    * misestimates dominate; until then these are the values from the plan.
    */
  private[plan] val SelectivityEq: Double      = 0.1
  private[plan] val SelectivityNeq: Double     = 0.9
  private[plan] val SelectivityRange: Double   = 0.3
  private[plan] val SelectivityIsNull: Double  = 0.1
  private[plan] val SelectivityLike: Double    = 0.5
  private[plan] val SelectivityInList: Double  = 0.5
  private[plan] val SelectivityDefault: Double = 0.5

  /** Minimum estimated row count above which the planner inserts
    * `ExchangeExec` above an aggregate or distinct. Default `Long.MaxValue`
    * — i.e. aggregate/distinct sharding is OFF by default. The exchange's
    * full pass over input rows plus the K-shard heap materialization is a
    * real cost that can be much larger than the cross-partition merge it
    * eliminates, especially when GROUP BY cardinality is low relative to
    * input size (one group having 100M rows hashed to 1 shard is strictly
    * worse than the historic streaming partial-aggregate path). Until we
    * have per-key cardinality estimates and/or a streaming exchange (plan
    * 08), the safe default is to leave the historic shape on.
    *
    * Override per-JVM with [[Settings.ShardMinSize]]
    * (`transformer.scheduler.shard_min_size`). Workloads with provably high
    * distinct-key counts (where the cross-partition merge actually is the
    * wall) can set this to e.g. 1_000_000 to opt back in. */
  val MinShardableSize: Long = Settings.ShardMinSize.get

  /** Threshold for the broadcast-vs-shuffle decision on hash joins. If the
    * smaller side's estimate is below this, the planner refuses to insert
    * the exchanges on both sides — it builds the small side once, streams
    * the large probe through it directly (the historic shape). Sharding both
    * sides forces an extra pass over the *large* side just to redistribute
    * rows, and on a 100M-row probe × 100k-row build that's a 10x slowdown.
    *
    * 1M is a conservative ceiling on what we'll broadcast — the build map
    * stays well inside the heap (~80MB at 80B/row), and any join above this
    * threshold is large enough that the per-shard parallelism gain
    * dominates the extra shuffle pass. Override per-JVM with
    * [[Settings.BroadcastThreshold]]
    * (`transformer.scheduler.broadcast_threshold`). */
  val BroadcastBuildThreshold: Long = Settings.BroadcastThreshold.get

  /** Estimate the number of rows produced by `plan`. */
  def estimate(plan: LogicalPlan): Option[Long] = estimateWith(plan, joinEstimate)

  /** [[estimate]], each join sized by `join` over its sides' estimates and
    * its kind rather than by [[joinEstimate]]: how the planner's nested-loop
    * guard sizes what lies under an aggregate or a union
    * (`PhysicalPlanner.keyedJoinRows`). */
  private[sql] def estimateWith(plan: LogicalPlan, join: (Long, Long, JoinKind) => Long): Option[Long] = {
    def estimate(p: LogicalPlan): Option[Long] = estimateWith(p, join)
    plan match {
      case LogicalScan(_, view, _) =>
        view.exactRowCount

      case LogicalFilter(child, pred) =>
        estimate(child).map(n => math.max(0L, (n * filterSelectivity(pred)).toLong))

      case LogicalProject(child, _) =>
        estimate(child)

      case LogicalLimit(child, n) =>
        estimate(child) match {
          case Some(c) => Some(math.min(c, n))
          case None    => Some(n)
        }

      case LogicalOffset(child, n) =>
        estimate(child).map(c => math.max(0L, c - n))

      case LogicalDistinct(child) =>
        estimate(child).map(n => math.max(1L, n / 2))

      case LogicalAggregate(child, gks, _) =>
        estimate(child).map { n =>
          if (gks.isEmpty) 1L
          else math.min(n, ndvHint(gks, n))
        }

      case LogicalJoin(l, r, _, kind) =>
        for {
          lc <- estimate(l)
          rc <- estimate(r)
        } yield join(lc, rc, kind)

      case LogicalUnion(l, r, _) =>
        for {
          lc <- estimate(l)
          rc <- estimate(r)
        } yield lc + rc

      case LogicalSort(child, _) =>
        estimate(child)

      case LogicalWindow(child, _) =>
        estimate(child)
    }
  }

  private[plan] def filterSelectivity(pred: Expr): Double = pred match {
    case BinOpExpr(op, l, r, _) =>
      op match {
        case "AND" => filterSelectivity(l) * filterSelectivity(r)
        case "OR" =>
          val lf = filterSelectivity(l)
          val rf = filterSelectivity(r)
          1.0 - (1.0 - lf) * (1.0 - rf)
        case "=" | "==" => SelectivityEq
        case "!=" | "<>" => SelectivityNeq
        case ">" | "<" | ">=" | "<=" => SelectivityRange
        case _ => SelectivityDefault
      }
    case UnaryOpExpr("NOT", child, _) => 1.0 - filterSelectivity(child)
    case _: IsNullExpr => SelectivityIsNull
    case _: LikeExpr => SelectivityLike
    case _: InListExpr => SelectivityInList
    case _ => SelectivityDefault
  }

  /** Approximate distinct value count for a group-by key set.
    *
    * Without per-column NDV statistics this can't be accurate. The hint is
    * "more group keys → fewer rows per group" — divide the input by a
    * scaling factor that grows with the number of group columns. The actual
    * row count is the natural upper bound, so the caller takes `min(n, hint)`.
    */
  private def ndvHint(gks: Seq[(Expr, String)], inputRows: Long): Long =
    math.max(1L, inputRows / math.max(1L, gks.length.toLong * 100L))

  /** Row-count guess for a join with no per-key statistics. We can't tell
    * fan-out from key overlap, so use a side-aware upper bound: Inner takes
    * the larger side (a 1:1 join produces `max`, a fan-out produces more —
    * but for build-side selection we only need a coarse signal), Left/Right
    * stay at their preserved-side size, Full is the sum. So an outer join can
    * be sized smaller than the inner join it holds, which picking a build
    * side does not mind; the planner's nested-loop guard counts a join on an
    * equality its own way, no smaller for an outer join
    * (`PhysicalPlanner.keyedJoinRows`, decision 3 of plans/testing/16).
    */
  private[sql] def joinEstimate(l: Long, r: Long, kind: JoinKind): Long = kind match {
    case JoinKind.Inner => math.max(l, r)
    case JoinKind.Left  => l
    case JoinKind.Right => r
    case JoinKind.Full  => l + r
  }
}
