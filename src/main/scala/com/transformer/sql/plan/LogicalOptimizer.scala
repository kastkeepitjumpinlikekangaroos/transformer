package com.transformer.sql.plan

/** Runs the project's logical-plan rewrites in the correct order before
  * physical planning.
  *
  * Two passes, called once each:
  *
  *   1. [[FilterPushdown]] — push filter conjuncts as close to scans as
  *      correctness allows. Done first because pushing a filter through a
  *      join doesn't change the columns the join needs, but pushing a
  *      projection might overlook columns referenced only by a filter that
  *      hasn't moved yet.
  *   2. [[ColumnProjectionPushdown]] — prune unreferenced columns out of
  *      scans, threading through joins so each side reads only what its
  *      ancestors actually use.
  *
  * Each pass's output is checked by [[PlanValidator.afterPass]]: well formed,
  * and returning what its input returned. The order is fixed and the framework
  * is deliberately minimal — an explicit call per pass in a tiny object rather
  * than a generic rule engine. Adding a new pass means adding another method
  * here and a line in [[optimize]]; if a new pass needs to run before existing
  * ones, reorder these lines and document why.
  */
object LogicalOptimizer {
  def optimize(plan: LogicalPlan): LogicalPlan = projectionPushdown(filterPushdown(plan))

  /** [[FilterPushdown]] over `plan`, its output checked. */
  def filterPushdown(plan: LogicalPlan): LogicalPlan =
    PlanValidator.afterPass("FilterPushdown", plan, FilterPushdown(plan))

  /** [[ColumnProjectionPushdown]] over `plan`, its output checked. */
  def projectionPushdown(plan: LogicalPlan): LogicalPlan =
    PlanValidator.afterPass("ColumnProjectionPushdown", plan, ColumnProjectionPushdown(plan))
}
