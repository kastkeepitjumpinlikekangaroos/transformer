package com.transformer.sql.plan

import com.transformer.core.{CatalogView, ColumnarBatch, Schema}

/** Stand-in for an unresolved CTE reference. Every instance is unique per CTE
  * definition; [[com.transformer.sql.exec.CteResolver]] swaps each
  * [[LogicalScan]] over it for either the inlined body or a scan over the
  * materialized result. Reaching `numPartitions` / `readPartition` means the
  * resolver missed a reference — a builder bug, not a user error.
  *
  * The view carries the CTE body's `outputSchema` so column resolution at build
  * time binds against the right shape; the invariant the resolver upholds is
  * `replacement.outputSchema == schema`, so swapping the pending scan for its
  * resolution never shifts a column index.
  */
final class PendingCteView(val cteName: String, val schema: Schema) extends CatalogView {
  private def unresolved: Nothing =
    throw new IllegalStateException(s"Unresolved CTE '$cteName' reached execution")
  def numPartitions: Int = unresolved
  def readPartition(p: Int): Iterator[ColumnarBatch] = unresolved
}
