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
  * The order is fixed and the framework is deliberately minimal — two
  * explicit calls in a tiny object rather than a generic rule engine.
  * Adding a new pass means adding another line here; if a new pass needs
  * to run before existing ones, reorder these lines and document why.
  */
object LogicalOptimizer {
  def optimize(plan: LogicalPlan): LogicalPlan = {
    val withFiltersPushed = FilterPushdown(plan)
    val withProjectionsPushed = ColumnProjectionPushdown(withFiltersPushed)
    withProjectionsPushed
  }
}
