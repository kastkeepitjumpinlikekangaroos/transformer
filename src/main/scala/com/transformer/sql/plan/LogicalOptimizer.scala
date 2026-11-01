package com.transformer.sql.plan

/** Runs the project's logical-plan rewrites in the correct order before
  * physical planning.
  *
  * Three passes, called once each:
  *
  *   1. [[FilterPushdown]] — push filter conjuncts as close to scans as
  *      correctness allows. Done first because pushing a filter through a
  *      join doesn't change the columns the join needs, but pushing a
  *      projection might overlook columns referenced only by a filter that
  *      hasn't moved yet.
  *   2. [[JoinOrder]] — order each run of inner joins so each join has an
  *      equality to hash on where it can. After filter pushdown, which moves
  *      the WHERE's conditions over two tables into the joins, where this
  *      pass reads them.
  *   3. [[ColumnProjectionPushdown]] — prune unreferenced columns out of
  *      scans, threading through joins so each side reads only what its
  *      ancestors actually use, the joins in their final order.
  *
  * Each pass's output is checked by [[PlanValidator.afterPass]]: well formed,
  * and returning what its input returned. The order is fixed and the framework
  * is deliberately minimal — an explicit call per pass in a tiny object rather
  * than a generic rule engine. Adding a new pass means adding another method
  * here and a line in [[optimize]]; if a new pass needs to run before existing
  * ones, reorder these lines and document why.
  */
object LogicalOptimizer {
  def optimize(plan: LogicalPlan): LogicalPlan = projectionPushdown(joinOrder(filterPushdown(plan)))

  /** [[FilterPushdown]] over `plan`, its output checked. */
  def filterPushdown(plan: LogicalPlan): LogicalPlan =
    PlanValidator.afterPass("FilterPushdown", plan, FilterPushdown(plan))

  /** [[JoinOrder]] over `plan`, its output checked. */
  def joinOrder(plan: LogicalPlan): LogicalPlan =
    PlanValidator.afterPass("JoinOrder", plan, JoinOrder(plan))

  /** [[ColumnProjectionPushdown]] over `plan`, its output checked. */
  def projectionPushdown(plan: LogicalPlan): LogicalPlan =
    PlanValidator.afterPass("ColumnProjectionPushdown", plan, ColumnProjectionPushdown(plan))
}
