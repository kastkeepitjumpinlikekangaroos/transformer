package com.transformer.sql.exec

import com.transformer.core.{Catalog, ColumnarBatch, ExecutedQuery, SqlExecutor, SqlExecutorRegistry}
import com.transformer.sql.plan.{ColumnProjectionPushdown, LogicalBuilder}

/** Wires the SQL stack into [[SqlExecutorRegistry]]. Calling `init()` is
  * idempotent; the job module's `DataJob.run()` calls it lazily.
  */
object SqlEngine extends SqlExecutor {

  def init(): Unit = SqlExecutorRegistry.install(this)

  // Self-install on class load so simply linking against this module is enough.
  init()

  def execute(sql: String, catalog: Catalog): ExecutedQuery = {
    val logical = LogicalBuilder.build(sql, catalog)
    val pruned = ColumnProjectionPushdown(logical)
    val physical = PhysicalPlanner.plan(pruned)
    val parts: IndexedSeq[Iterator[ColumnarBatch]] =
      (0 until physical.numPartitions).map(p => physical.execute(p))
    new ExecutedQuery(physical.outputSchema, parts)
  }
}
