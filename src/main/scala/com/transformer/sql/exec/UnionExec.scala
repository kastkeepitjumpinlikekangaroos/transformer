package com.transformer.sql.exec

import com.transformer.core._

/** UNION ALL: concatenates partitions from both sides. The planner places a
  * [[DistinctExec]] on top for plain UNION.
  *
  * The result's columns are named as the left side's are, so each batch the
  * right side gives is relabelled with them: the same vectors, the union's
  * schema. Passed through as they were, its batches named the right side's
  * columns, and `CsvWriter`, which checks a batch's names against the
  * output's, refused `SELECT a AS x FROM t UNION ALL SELECT k FROM u`
  * (found by `--config=verify`, plans/testing/17).
  */
final case class UnionExec(left: PhysicalPlan, right: PhysicalPlan) extends PhysicalPlan {
  require(
    left.outputSchema.length == right.outputSchema.length,
    s"UNION mismatched column counts: ${left.outputSchema.length} vs ${right.outputSchema.length}"
  )

  def outputSchema: Schema = left.outputSchema
  def numPartitions: Int = left.numPartitions + right.numPartitions

  /** Whether the right side's batches need the union's names. */
  private val relabel: Boolean = right.outputSchema != left.outputSchema

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (partition < left.numPartitions) left.execute(partition)
    else {
      val batches = right.execute(partition - left.numPartitions)
      if (!relabel) batches
      else batches.map(b => ColumnarBatch.fromColumns(outputSchema, b.columns, b.numRows))
    }
  }
}
