package com.transformer.sql.exec

import com.transformer.core._

/** UNION ALL: concatenates partitions from both sides. The planner places a
  * [[DistinctExec]] on top for plain UNION.
  */
final case class UnionExec(left: PhysicalPlan, right: PhysicalPlan) extends PhysicalPlan {
  require(
    left.outputSchema.length == right.outputSchema.length,
    s"UNION mismatched column counts: ${left.outputSchema.length} vs ${right.outputSchema.length}"
  )

  def outputSchema: Schema = left.outputSchema
  def numPartitions: Int = left.numPartitions + right.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    if (partition < left.numPartitions) left.execute(partition)
    else right.execute(partition - left.numPartitions)
  }
}
