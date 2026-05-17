package com.transformer.sql.exec

import com.transformer.core._

/** Reusable 1-row [[ColumnarBatch]] used as a target for [[Expr.eval]] over a
  * materialized row (sort comparators, join probes, residual predicates). Avoids
  * allocating a new batch per row in tight loops.
  *
  * Not thread-safe — each thread that needs a [[RowBuf]] should own one.
  */
private[exec] final class RowBuf(val schema: Schema) {
  val batch: ColumnarBatch = new ColumnarBatch(schema, 1)
  batch.setNumRows(1)

  /** Fill this view from a materialized row array. */
  def set(values: Array[Any]): Unit = {
    var c = 0
    while (c < schema.length) {
      if (values(c) == null) batch.column(c).setNull(0)
      else batch.column(c).setBoxed(0, values(c))
      c += 1
    }
  }

  /** Fill this view from one row of another batch (no allocation). */
  def setFromBatch(src: ColumnarBatch, row: Int): Unit = {
    var c = 0
    while (c < schema.length) {
      if (src.column(c).isNull(row)) batch.column(c).setNull(0)
      else batch.column(c).setBoxed(0, src.column(c).getBoxed(row))
      c += 1
    }
  }
}
