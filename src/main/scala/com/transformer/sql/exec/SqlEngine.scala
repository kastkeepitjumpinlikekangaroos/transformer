package com.transformer.sql.exec

import com.transformer.core.{Catalog, ColumnarBatch, ExecutedQuery, ExecutionOptions, SqlExecutor, SqlExecutorRegistry}
import com.transformer.core.metrics.{MetricsNode, QueryMetrics}
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer}

/** Wires the SQL stack into [[SqlExecutorRegistry]]. Calling `init()` is
  * idempotent; the job module's `DataJob.run()` calls it lazily.
  */
object SqlEngine extends SqlExecutor {

  def init(): Unit = SqlExecutorRegistry.install(this)

  // Self-install on class load so simply linking against this module is enough.
  init()

  def execute(sql: String, catalog: Catalog, opts: ExecutionOptions): ExecutedQuery = {
    if (!opts.metricsEnabled) executeUnmeasured(sql, catalog, opts)
    else executeMeasured(sql, catalog, opts)
  }

  /** Disabled-path: no `System.nanoTime` calls, no [[QueryMetrics]]
    * allocation. The only added cost vs the pre-instrumentation engine is
    * the `if (!opts.metricsEnabled)` branch above and the same branch in
    * `PhysicalPlanner.plan`. */
  private def executeUnmeasured(sql: String, catalog: Catalog, opts: ExecutionOptions): ExecutedQuery = {
    val logical = LogicalBuilder.build(sql, catalog)
    val optimized = LogicalOptimizer.optimize(logical)
    val physical = PhysicalPlanner.plan(optimized, opts)
    val parts: IndexedSeq[Iterator[ColumnarBatch]] =
      (0 until physical.numPartitions).map(p => physical.execute(p))
    new ExecutedQuery(physical.outputSchema, parts)
  }

  /** Enabled path: wraps each phase in a nanoTime delta and captures the
    * root [[MetricsNode]] for the operator tree. The returned
    * [[ExecutedQuery]] carries an [[ExecutedQuery.metricsSnapshot]] hook
    * that consumers (the job runner's `_perf.json` writer) call once after
    * the partitions have been drained. */
  private def executeMeasured(sql: String, catalog: Catalog, opts: ExecutionOptions): ExecutedQuery = {
    val t0 = System.nanoTime()
    val logical = LogicalBuilder.build(sql, catalog)
    val t1 = System.nanoTime()
    val optimized = LogicalOptimizer.optimize(logical)
    val t2 = System.nanoTime()
    val (physical, maybeNode) = PhysicalPlanner.planWithMetrics(optimized, opts)
    val t3 = System.nanoTime()
    val parts: IndexedSeq[Iterator[ColumnarBatch]] =
      (0 until physical.numPartitions).map(p => physical.execute(p))
    val t4 = System.nanoTime()

    val parseNanos = t1 - t0
    val optimizeNanos = t2 - t1
    val physicalPlanNanos = t3 - t2
    val executeNanos = t4 - t3

    val q = new ExecutedQuery(physical.outputSchema, parts)
    maybeNode.foreach { rootNode =>
      // Snapshot is lazy — the operator tree's counters are populated as
      // the partitions are pulled; consumers (`DataJob.runOneTask`) call
      // [[ExecutedQuery.metricsSnapshot]] AFTER the drain to capture
      // final values.
      q.attachMetrics(() => QueryMetrics(
        parseNanos = parseNanos,
        optimizeNanos = optimizeNanos,
        physicalPlanNanos = physicalPlanNanos,
        executeNanos = executeNanos,
        operatorTree = rootNode.snapshot()
      ))
    }
    q
  }
}
