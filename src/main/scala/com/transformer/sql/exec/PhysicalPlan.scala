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

/** Constant single-row output for the `SELECT COUNT(*) FROM <view>` fast path —
  * the planner emits this when the view exposes [[CatalogView.exactRowCount]]
  * (parquet via footer metadata, in-memory views from their materialized count).
  *
  * No data is decoded, so even multi-GB inputs answer in microseconds. The
  * output column is named to match the aggregate's synthetic alias so downstream
  * Project/Sort/Limit bind unchanged.
  */
final case class CountStarMetadataExec(count: Long, columnName: String) extends PhysicalPlan {
  val outputSchema: Schema = Schema(Vector(Field(columnName, DataType.LongType)))
  def numPartitions: Int = 1
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val out = new ColumnarBatch(outputSchema, 1)
    out.column(0).asInstanceOf[LongVector].set(0, count)
    out.setNumRows(1)
    Iterator.single(out)
  }
}

final case class FilterExec(child: PhysicalPlan, predicate: Expr) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    child.execute(partition).flatMap { batch =>
      val n = batch.numRows
      // One vectorized predicate eval per batch — primitive bool array, no
      // per-row Expr dispatch.
      val resultCol = predicate.evalVec(batch).asInstanceOf[BooleanVector]
      val mask = new Array[Boolean](n)
      var i = 0
      while (i < n) {
        // WHERE: NULL is treated as false (rows excluded).
        mask(i) = !resultCol.isNull(i) && resultCol.values(i)
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
  private val projArr: Array[Expr] = projections.iterator.map(_._1).toArray
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    child.execute(partition).map(projectBatch)
  }
  private def projectBatch(batch: ColumnarBatch): ColumnarBatch = {
    val n = batch.numRows
    val ncols = projArr.length
    val outCols = new Array[ColumnVector](ncols)
    var c = 0
    while (c < ncols) {
      // One evalVec call per projection. The returned vector may alias the
      // input batch (ColRefExpr) or be a freshly-allocated compute result —
      // either is fine, downstream operators treat batch columns as read-only.
      outCols(c) = projArr(c).evalVec(batch)
      c += 1
    }
    ColumnarBatch.fromColumns(outputSchema, outCols, n)
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
