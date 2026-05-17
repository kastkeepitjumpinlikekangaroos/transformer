package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

/** Physical operator. One partition = one independent stream of [[ColumnarBatch]].
  * The executor schedules partitions onto a [[java.util.concurrent.ForkJoinPool]].
  */
trait PhysicalPlan {
  def outputSchema: Schema
  def numPartitions: Int
  def execute(partition: Int): Iterator[ColumnarBatch]
}

/** Reads from a [[CatalogView]]. One view partition per physical partition. */
final case class ScanExec(view: CatalogView) extends PhysicalPlan {
  def outputSchema: Schema = view.schema
  def numPartitions: Int = view.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = view.readPartition(partition)
}

final case class FilterExec(child: PhysicalPlan, predicate: Expr) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    child.execute(partition).flatMap { batch =>
      val mask = new Array[Boolean](batch.numRows)
      var i = 0
      while (i < batch.numRows) {
        val v = predicate.eval(batch, i)
        mask(i) = (v != null) && v.asInstanceOf[Boolean]
        i += 1
      }
      val filtered = batch.select(mask)
      if (filtered.numRows == 0) Iterator.empty else Iterator.single(filtered)
    }
  }
}

final case class ProjectExec(child: PhysicalPlan, projections: Seq[(Expr, String)]) extends PhysicalPlan {
  val outputSchema: Schema = Schema(
    projections.iterator.map { case (e, name) => Field(name, e.dataType) }.toVector
  )
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    child.execute(partition).map(projectBatch)
  }
  private def projectBatch(batch: ColumnarBatch): ColumnarBatch = {
    val out = new ColumnarBatch(outputSchema, math.max(1, batch.numRows))
    val ncols = projections.size
    var c = 0
    while (c < ncols) {
      val (expr, _) = projections(c)
      val outCol = out.column(c)
      var r = 0
      while (r < batch.numRows) {
        val v = expr.eval(batch, r)
        if (v == null) outCol.setNull(r) else outCol.setBoxed(r, v)
        r += 1
      }
      c += 1
    }
    out.setNumRows(batch.numRows)
    out
  }
}

/** Per-partition limit. The executor adds a [[GlobalLimitExec]] above this when
  * `numPartitions > 1` so the global cap is enforced.
  */
final case class LocalLimitExec(child: PhysicalPlan, n: Long) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    var remaining = n
    child.execute(partition).takeWhile(_ => remaining > 0).map { b =>
      if (b.numRows <= remaining) {
        remaining -= b.numRows
        b
      } else {
        val mask = new Array[Boolean](b.numRows)
        var i = 0
        while (i < remaining.toInt) { mask(i) = true; i += 1 }
        remaining = 0
        b.select(mask)
      }
    }
  }
}

/** Global limit collapses all partitions into one stream, stops after `n` rows. */
final case class GlobalLimitExec(child: PhysicalPlan, n: Long) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    var remaining = n
    val it = (0 until child.numPartitions).iterator.flatMap(child.execute)
    it.takeWhile(_ => remaining > 0).map { b =>
      if (b.numRows <= remaining) {
        remaining -= b.numRows
        b
      } else {
        val mask = new Array[Boolean](b.numRows)
        var i = 0
        while (i < remaining.toInt) { mask(i) = true; i += 1 }
        remaining = 0
        b.select(mask)
      }
    }
  }
}
