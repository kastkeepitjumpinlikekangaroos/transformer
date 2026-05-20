package com.transformer.sql.plan

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

  /** Estimate the number of rows produced by `plan`. */
  def estimate(plan: LogicalPlan): Option[Long] = plan match {
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

    case LogicalDistinct(child) =>
      estimate(child).map(n => math.max(1L, n / 2))

    case LogicalAggregate(child, gks, _, _) =>
      estimate(child).map { n =>
        if (gks.isEmpty) 1L
        else math.min(n, ndvHint(gks, n))
      }

    case LogicalJoin(l, r, _, kind) =>
      for {
        lc <- estimate(l)
        rc <- estimate(r)
      } yield joinEstimate(lc, rc, kind)

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
    * stay at their preserved-side size, Full is the sum.
    */
  private def joinEstimate(l: Long, r: Long, kind: JoinKind): Long = kind match {
    case JoinKind.Inner => math.max(l, r)
    case JoinKind.Left  => l
    case JoinKind.Right => r
    case JoinKind.Full  => l + r
  }
}
