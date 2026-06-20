package com.transformer.sql.exec

import com.transformer.core.ExecutionOptions
import com.transformer.sql.plan.{BuiltQuery, LogicalPlan, LogicalScan, PendingCteView}

/** Resolves the placeholder [[PendingCteView]] scans that [[com.transformer.sql.plan.LogicalBuilder]]
  * emits for outermost-scope CTEs, running between logical build and the main
  * optimize/physical-plan. By the time the optimizer and physical planner see
  * the plan, every CTE reference is a plain inlined body subtree — so their
  * exhaustive matches are untouched and no new [[LogicalPlan]] node type exists.
  *
  * Phase 1 (this version) inlines every CTE: each placeholder scan is replaced
  * with the CTE body, exactly reproducing the historic inline-at-each-reference
  * behaviour. Phase 2 adds reference counting so a CTE referenced `>= 2` times
  * is materialized once instead.
  */
object CteResolver {

  /** Rewrite `built.main` so no [[PendingCteView]] scans remain. CTEs are
    * resolved in declaration order; substituting an already-resolved def into
    * the not-yet-processed bodies means a later CTE that references an earlier
    * one sees the earlier one's resolution. The no-CTE path returns `main`
    * unchanged (a true no-op for the common case). */
  def resolve(built: BuiltQuery, opts: ExecutionOptions): LogicalPlan = {
    if (built.ctes.isEmpty) return built.main

    val bodies = built.ctes.iterator.map(_.body).toArray
    var main = built.main

    built.ctes.iterator.zipWithIndex.foreach { case (cte, i) =>
      // `bodies(i)` already has every earlier CTE substituted into it.
      val replacement = bodies(i)
      var j = i + 1
      while (j < bodies.length) {
        bodies(j) = substitute(bodies(j), cte.pending, replacement)
        j += 1
      }
      main = substitute(main, cte.pending, replacement)
    }
    main
  }

  /** Structural rewrite replacing every `LogicalScan` whose view is exactly
    * `pending` (identity — one [[PendingCteView]] per def) with `replacement`.
    * The same `replacement` object is shared across all reference sites, so an
    * inlined body remains a shared subtree (as before) and a materialized view
    * is scanned once and replayed at each site. */
  private[exec] def substitute(plan: LogicalPlan, pending: PendingCteView, replacement: LogicalPlan): LogicalPlan =
    plan match {
      case LogicalScan(_, view, _) if view eq pending => replacement
      case other => other.withChildren(other.children.map(c => substitute(c, pending, replacement)))
    }
}
