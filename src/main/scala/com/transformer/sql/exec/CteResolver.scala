package com.transformer.sql.exec

import com.transformer.core.{CatalogView, ColumnarBatch, ExecutionOptions, MaterializedView, Schema}
import com.transformer.sql.plan.{BuiltQuery, CteDef, LogicalOptimizer, LogicalPlan, LogicalScan, PendingCteView, PlanValidator}

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

  /** Rewrite `built.main` so no [[PendingCteView]] scans remain: [[planned]]
    * decides which CTEs are inlined, and each it keeps is materialized here,
    * in declaration order, its rows scanned wherever it is read, the earlier
    * ones in the later ones' bodies (so a materialized `b` that reads `a`
    * reads `a`'s already-materialized view). The no-CTE path returns `main`
    * unchanged (a true no-op for the common case); otherwise the result is
    * checked by [[PlanValidator.resolved]]. */
  def resolve(built: BuiltQuery, opts: ExecutionOptions): LogicalPlan =
    if (built.ctes.isEmpty) built.main
    else {
      val plan = planned(built)
      val bodies = plan.ctes.iterator.map(_.body).toArray
      var main = plan.main
      plan.ctes.iterator.zipWithIndex.foreach { case (cte, i) =>
        val scan = materialize(cte.name, bodies(i), opts) // compute once, scan many
        var j = i + 1
        while (j < bodies.length) {
          bodies(j) = substitute(bodies(j), cte.pending, scan)
          j += 1
        }
        main = substitute(main, cte.pending, scan)
      }
      PlanValidator.resolved(built.main, main)
    }

  /** The plan `built` runs as, decided without running anything: each CTE
    * read at most once is inlined where it is read, and each read twice or
    * more stays a scan of its pending view, to be materialized. The result's
    * CTEs are those, in declaration order, each body with the earlier inlined
    * ones substituted in as well. [[resolve]] materializes them; EXPLAIN
    * shows them without running them. */
  def planned(built: BuiltQuery): BuiltQuery = {
    if (built.ctes.isEmpty) return built
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

    val materialized = Seq.newBuilder[CteDef]
    built.ctes.iterator.zipWithIndex.foreach { case (cte, i) =>
      // `bodies(i)` already has every earlier inlined CTE substituted into it.
      if (reads(i) >= 2) materialized += cte.copy(body = bodies(i))
      else {
        var j = i + 1
        while (j < bodies.length) {
          bodies(j) = substitute(bodies(j), cte.pending, bodies(i))
          j += 1
        }
        main = substitute(main, cte.pending, bodies(i))
      }
    }
    BuiltQuery(main, materialized.result())
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
    // A CTE body is drained through the pool below; publish its shared
    // results (exchanges, broadcast join builds) first so that drain never
    // waits on one being ready (same K>1 liveness rule as SqlEngine's
    // main-plan pass).
    PhysicalPlanner.preMaterialize(physical)
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
  /** A query's strings are text: its scans and literals refuse anything else. */
  override def stringsAreText: Boolean = true
  def readPartition(part: Int): Iterator[ColumnarBatch] = p.execute(part)
}
