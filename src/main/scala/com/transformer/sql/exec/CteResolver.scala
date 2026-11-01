package com.transformer.sql.exec

import com.transformer.core.{CatalogView, ColumnarBatch, ExecutionOptions, MaterializedView, Schema}
import com.transformer.sql.plan.{BuiltQuery, LogicalOptimizer, LogicalPlan, LogicalScan, PendingCteView, PlanValidator}

/** Resolves the placeholder [[PendingCteView]] scans that
  * [[com.transformer.sql.plan.LogicalBuilder]] emits for outermost-scope CTEs,
  * running between logical build and the main optimize/physical-plan. By the
  * time the optimizer and physical planner see the plan, every CTE reference is
  * either a plain inlined body subtree or a [[LogicalScan]] over a
  * [[MaterializedView]] — so their exhaustive matches are untouched and no new
  * [[LogicalPlan]] node type exists.
  *
  * Policy: a CTE referenced `>= 2` times is materialized once into an in-memory
  * [[MaterializedView]] and every reference scans that shared result; a CTE
  * referenced `<= 1` time is inlined (preserving streaming and zero overhead;
  * a declared-but-unused CTE is never executed). Materialization is also an
  * optimization fence — every reference observes the same single evaluation of
  * a non-deterministic body (e.g. `RAND()`), which inlining does not guarantee.
  *
  * Only outermost-scope CTEs reach here as [[PendingCteView]]s; CTEs declared
  * inside another CTE body are already inlined by the builder.
  */
object CteResolver {

  /** Rewrite `built.main` so no [[PendingCteView]] scans remain. CTEs are
    * resolved in declaration order; substituting an already-resolved def into
    * the not-yet-processed bodies means a later CTE that references an earlier
    * one sees the earlier one's resolution (so a materialized `b` that reads
    * `a` reads `a`'s already-materialized view). The no-CTE path returns `main`
    * unchanged (a true no-op for the common case); otherwise the result is
    * checked by [[PlanValidator.afterPass]]. */
  def resolve(built: BuiltQuery, opts: ExecutionOptions): LogicalPlan =
    if (built.ctes.isEmpty) built.main
    else PlanValidator.afterPass("CTE resolution", built.main, substituteAll(built, opts))

  private def substituteAll(built: BuiltQuery, opts: ExecutionOptions): LogicalPlan = {
    val bodies = built.ctes.iterator.map(_.body).toArray
    var main = built.main

    // How many times each CTE is read: by the main query, and by the later
    // CTEs that are themselves read, each of which runs once (materialized at
    // two reads, inlined at one). Earlier bodies can't reference a CTE (it
    // wasn't in scope when they were built), so counting from the last CTE
    // back knows every reader's own count first. A reference from a CTE
    // nothing reads used to count, so a CTE only it read twice ran.
    val reads = new Array[Int](bodies.length)
    var k = bodies.length - 1
    while (k >= 0) {
      val pending = built.ctes(k).pending
      reads(k) = countScans(main, pending) +
        (k + 1 until bodies.length).iterator.filter(reads(_) > 0).map(j => countScans(bodies(j), pending)).sum
      k -= 1
    }

    built.ctes.iterator.zipWithIndex.foreach { case (cte, i) =>
      // `bodies(i)` already has every earlier CTE substituted into it.
      val resolvedBody = bodies(i)
      val refs = reads(i)

      val replacement: LogicalPlan =
        if (refs >= 2) materialize(cte.name, resolvedBody, opts)  // compute once, scan many
        else resolvedBody                                          // inline (refs 0 or 1)

      var j = i + 1
      while (j < bodies.length) {
        bodies(j) = substitute(bodies(j), cte.pending, replacement)
        j += 1
      }
      main = substitute(main, cte.pending, replacement)
    }
    main
  }

  /** Materialize `body` once into an in-memory [[MaterializedView]] and return a
    * [[LogicalScan]] over it. The body is optimized + physically planned in
    * isolation and drained in parallel via [[MaterializedView.materializeInParallel]];
    * metrics are disabled for the sub-execution so the body's operator tree
    * doesn't pollute the main query's metrics, while spill config is preserved
    * so a large body can still spill internally.
    *
    * Invariant: `mv.schema == body.outputSchema` exactly, so swapping the
    * pending scan for the MV scan never shifts a bound column index. */
  private def materialize(name: String, body: LogicalPlan, opts: ExecutionOptions): LogicalPlan = {
    val physical = PhysicalPlanner.plan(LogicalOptimizer.optimize(body), opts.copy(metricsEnabled = false))
    // A CTE body is drained through the pool below; publish its exchanges
    // first so that drain never waits on exchange readiness (same K>1
    // liveness rule as SqlEngine's main-plan pass).
    PhysicalPlanner.preMaterializeExchanges(physical)
    val mv = MaterializedView.materializeInParallel(new PhysicalPlanView(physical))
    require(mv.schema == body.outputSchema,
      s"CTE '$name' materialized schema ${mv.schema.fieldNames.mkString("[", ",", "]")} != " +
        s"body schema ${body.outputSchema.fieldNames.mkString("[", ",", "]")}")
    LogicalScan(name, mv, Some(name))
  }

  /** Count `LogicalScan`s whose view is exactly `pending` (identity — one
    * [[PendingCteView]] per def, so this is unambiguous). */
  private def countScans(plan: LogicalPlan, pending: PendingCteView): Int = plan match {
    case LogicalScan(_, view, _) if view eq pending => 1
    case other => other.children.iterator.map(c => countScans(c, pending)).sum
  }

  /** Structural rewrite replacing every `LogicalScan` whose view is exactly
    * `pending` with `replacement`. The same `replacement` object is shared
    * across all reference sites, so an inlined body remains a shared subtree
    * (as before) and a materialized view is scanned once and replayed at each
    * site. */
  private[exec] def substitute(plan: LogicalPlan, pending: PendingCteView, replacement: LogicalPlan): LogicalPlan =
    plan match {
      case LogicalScan(_, view, _) if view eq pending => replacement
      case other => other.withChildren(other.children.map(c => substitute(c, pending, replacement)))
    }
}

/** Adapter exposing a planned [[PhysicalPlan]] as a [[CatalogView]] so
  * [[MaterializedView.materializeInParallel]] (which drains a `CatalogView`)
  * can run a physical plan's partitions into memory. */
private[exec] final class PhysicalPlanView(p: PhysicalPlan) extends CatalogView {
  def schema: Schema = p.outputSchema
  def numPartitions: Int = p.numPartitions
  def readPartition(part: Int): Iterator[ColumnarBatch] = p.execute(part)
}
