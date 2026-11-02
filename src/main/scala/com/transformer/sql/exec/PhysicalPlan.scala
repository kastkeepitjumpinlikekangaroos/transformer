package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._

/** Physical operator. One partition = one independent stream of [[ColumnarBatch]].
  * The executor schedules partitions onto a [[java.util.concurrent.ForkJoinPool]].
  *
  * The close protocol ([[Closing]]): an operator whose output holds anything,
  * itself or upstream of it, returns an iterator that is `AutoCloseable`, and
  * closing it closes its inputs; one that stops reading an input early closes
  * it; a pool task that drains an input closes it however the drain ends.
  */
trait PhysicalPlan {
  def outputSchema: Schema
  def numPartitions: Int
  def execute(partition: Int): Iterator[ColumnarBatch]
}

/** Reads from a [[CatalogView]]. One view partition per physical partition.
  *
  * When the wrapping [[com.transformer.sql.exec.PhysicalPlanner]] passes a
  * non-null [[MetricsNode]] and the underlying view is a [[ParquetReader]],
  * scan-side counters (bytesRead, rowGroupsRead / rowGroupsSkipped,
  * predicatePushedDownCount) flow into the node via a per-call recorder. For
  * non-parquet views (CSV, in-memory) the counters stay zero — those readers
  * have no row-group metadata to attribute.
  *
  * `stopped` is the query's [[ExecutionOptions.stopped]], asked before each
  * batch; once it says true, the scan throws [[QueryStoppedException]].
  *
  * A view whose strings are not known to be text
  * ([[CatalogView.stringsAreText]]), one built in Scala, has each STRING it
  * gives checked, and one holding half a surrogate pair is refused, naming
  * its column and row (decision 2 of plans/testing/17). */
final case class ScanExec(view: CatalogView, metricsNode: MetricsNode = null,
    stopped: () => Boolean = ExecutionOptions.NeverStopped) extends PhysicalPlan {
  def outputSchema: Schema = view.schema
  def numPartitions: Int = view.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    val batches = view match {
      case pr: ParquetReader if metricsNode != null =>
        pr.readPartitionWithMetrics(partition, new ParquetReader.MetricsRecorder {
          def addBytesRead(n: Long): Unit =
            metricsNode.counters(ScanExec.IdxBytesRead).add(n)
          def addRowGroupsRead(n: Long): Unit =
            metricsNode.counters(ScanExec.IdxRowGroupsRead).add(n)
          def addRowGroupsSkipped(n: Long): Unit =
            metricsNode.counters(ScanExec.IdxRowGroupsSkipped).add(n)
          def addPredicatePushedDown(n: Long): Unit =
            metricsNode.counters(ScanExec.IdxPredicatePushedDownCount).add(n)
        })
      case _ => view.readPartition(partition)
    }
    val text =
      if (view.stringsAreText || !view.schema.fields.exists(_.dataType == DataType.StringType)) batches
      else Closing.over(batches.map { b => BatchInvariants.requireText(b, "a view built in Scala"); b }, batches)
    if (stopped eq ExecutionOptions.NeverStopped) text
    else Closing.over(new Iterator[ColumnarBatch] {
      def hasNext: Boolean = { if (stopped()) throw new QueryStoppedException; text.hasNext }
      def next(): ColumnarBatch = text.next()
    }, text)
  }
}

object ScanExec {
  // ---- Counter indices ------------------------------------------------------
  // Scan-side counters surfaced by ParquetReader. Non-parquet views leave
  // them all at zero — those views have no row-group / pushdown notion.
  final val IdxBytesRead: Int                 = 0
  final val IdxRowGroupsRead: Int             = 1
  final val IdxRowGroupsSkipped: Int          = 2
  final val IdxPredicatePushedDownCount: Int  = 3

  val IdxCounterNames: Array[String] = Array(
    "bytesRead",
    "rowGroupsRead",
    "rowGroupsSkipped",
    "predicatePushedDownCount")
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
    val input = child.execute(partition)
    Closing.over(input.flatMap { batch =>
      // One vectorized predicate eval per batch — primitive bool array, no
      // per-row Expr dispatch. We pass the BooleanVector straight to
      // `selectByBoolean` so the intermediate `Array[Boolean]` the old path
      // allocated as a bridge is gone, and the pass-through fast path inside
      // `selectByBoolean` returns the original batch when every row passes
      // (no per-column copy).
      val resultCol = predicate.evalVec(batch).asInstanceOf[BooleanVector]
      val filtered = batch.selectByBoolean(resultCol)
      if (filtered.numRows == 0) Iterator.empty else Iterator.single(filtered)
    }, input)
  }
}

final case class ProjectExec(child: PhysicalPlan, projections: Seq[(Expr, String)]) extends PhysicalPlan {
  val outputSchema: Schema = Schema(
    projections.iterator.map { case (e, name) => Field(name, e.dataType) }.toVector
  )
  private val projArr: Array[Expr] = projections.iterator.map(_._1).toArray
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    val input = child.execute(partition)
    Closing.over(input.map(projectBatch), input)
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
  * `numPartitions > 1` so the global cap is enforced. Once it has its rows it
  * closes its input, which it reads no more ([[Closing.limited]]).
  */
final case class LocalLimitExec(child: PhysicalPlan, n: Long) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = child.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] =
    Closing.limited(child.execute(partition), n)
}

/** Global limit collapses all partitions into one stream, stops after `n` rows.
  * Each partition is executed when the one before it runs out, and once the
  * limit has its rows the partition it stopped in is closed; the ones after
  * it were never executed. */
final case class GlobalLimitExec(child: PhysicalPlan, n: Long) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    Closing.limited(LimitExec.partitions(child), n)
  }
}

/** OFFSET: collapses all partitions into one stream, in partition order, and
  * drops its first `n` rows. It has to be global — dropping `n` rows from each
  * partition would drop `n` per partition — so unlike a limit it has no
  * per-partition half to push down. */
final case class OffsetExec(child: PhysicalPlan, n: Long) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = 1
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val input = LimitExec.partitions(child)
    Closing.over(LimitExec.skip(input, n), input)
  }
}

private[exec] object LimitExec {
  /** Every partition of `plan` in turn, each executed when the one before it
    * runs out ([[Closing.concat]]). */
  def partitions(plan: PhysicalPlan): Iterator[ColumnarBatch] =
    Closing.concat((0 until plan.numPartitions).iterator.map(p => () => plan.execute(p)))

  /** Drop the first `n` rows, then pass everything else through: whole batches
    * are skipped, and the batch that crosses the boundary keeps only its tail. */
  def skip(it: Iterator[ColumnarBatch], n: Long): Iterator[ColumnarBatch] = {
    var remaining = n
    it.flatMap { b =>
      if (remaining == 0) Iterator.single(b)
      else if (b.numRows <= remaining) {
        remaining -= b.numRows
        Iterator.empty
      } else {
        val mask = new Array[Boolean](b.numRows)
        var i = remaining.toInt
        while (i < b.numRows) { mask(i) = true; i += 1 }
        remaining = 0
        Iterator.single(b.select(mask))
      }
    }
  }

}
