package com.transformer.sql.plan

import com.transformer.core.DataType

/** Window-function support shared by the logical and physical layers.
  *
  * A window function is `fn() OVER (PARTITION BY ... ORDER BY ... <frame>)`. The
  * logical plan collects every distinct `OVER (...)` clause referenced by the
  * SELECT/ORDER BY into a single [[com.transformer.sql.plan.LogicalWindow]]
  * node; the executor groups those that share a [[WindowSpec]] so it can
  * partition and sort the input once per spec.
  */

sealed trait WindowFn {
  def name: String
  def resultType: DataType
  /** Whether the function reads a frame: an aggregate does; a ranking
    * function and LAG / LEAD read the partition and its order alone, so the
    * binder refuses a frame written on one. */
  def respectsFrame: Boolean
}

final case class WindowFnRowNumber() extends WindowFn {
  val name = "ROW_NUMBER"
  val resultType: DataType = DataType.LongType
  val respectsFrame: Boolean = false
}
final case class WindowFnRank() extends WindowFn {
  val name = "RANK"
  val resultType: DataType = DataType.LongType
  val respectsFrame: Boolean = false
}
final case class WindowFnDenseRank() extends WindowFn {
  val name = "DENSE_RANK"
  val resultType: DataType = DataType.LongType
  val respectsFrame: Boolean = false
}
final case class WindowFnLag(child: Expr, offset: Int, default: Option[Expr]) extends WindowFn {
  val name = "LAG"
  val resultType: DataType = child.dataType
  val respectsFrame: Boolean = false
}
final case class WindowFnLead(child: Expr, offset: Int, default: Option[Expr]) extends WindowFn {
  val name = "LEAD"
  val resultType: DataType = child.dataType
  val respectsFrame: Boolean = false
}
/** Aggregate-as-window: any [[AggExpr]] evaluated over the frame's rows. */
final case class WindowFnAgg(agg: AggExpr) extends WindowFn {
  val name: String = agg.name
  val resultType: DataType = agg.resultType
  val respectsFrame: Boolean = true
}

/** Frame bounds. A ROWS frame's offsets count rows; a RANGE frame's measure
  * the ORDER BY value ([[ValuePreceding]], [[ValueFollowing]]), and its
  * `CURRENT ROW` is the current row's peers, every row the ORDER BY ties with
  * it.
  */
sealed trait FrameBound
object FrameBound {
  case object UnboundedPreceding extends FrameBound
  case object UnboundedFollowing extends FrameBound
  case object CurrentRow extends FrameBound
  /** `n PRECEDING` in a ROWS frame: `n` rows before the current one. */
  final case class Preceding(n: Long) extends FrameBound
  /** `n FOLLOWING` in a ROWS frame. */
  final case class Following(n: Long) extends FrameBound
  /** `offset PRECEDING` in a RANGE frame: the rows whose ORDER BY value is
    * within `offset` of the current row's, on the side before it. */
  final case class ValuePreceding(offset: java.math.BigDecimal) extends FrameBound
  /** `offset FOLLOWING` in a RANGE frame. */
  final case class ValueFollowing(offset: java.math.BigDecimal) extends FrameBound

  /** Where a bound kind sits among the five, which SQL requires a frame's
    * start not to come after its end in. */
  def rank(b: FrameBound): Int = b match {
    case UnboundedPreceding => 0
    case Preceding(_) | ValuePreceding(_) => 1
    case CurrentRow => 2
    case Following(_) | ValueFollowing(_) => 3
    case UnboundedFollowing => 4
  }
}

sealed trait FrameType
object FrameType {
  case object Rows extends FrameType
  case object Range extends FrameType
}

final case class WindowFrame(frameType: FrameType, start: FrameBound, end: FrameBound) {
  /** Every row of the partition, whatever the current row. */
  def isWholePartition: Boolean =
    start == FrameBound.UnboundedPreceding && end == FrameBound.UnboundedFollowing
}

object WindowFrame {
  /** SQL-standard default when ORDER BY is present: RANGE BETWEEN UNBOUNDED
    * PRECEDING AND CURRENT ROW, so a running total takes every row the ORDER BY
    * ties with the current one. Without an ORDER BY: the entire partition.
    */
  def defaultFor(hasOrderBy: Boolean): WindowFrame =
    if (hasOrderBy) WindowFrame(FrameType.Range, FrameBound.UnboundedPreceding, FrameBound.CurrentRow)
    else WindowFrame(FrameType.Rows, FrameBound.UnboundedPreceding, FrameBound.UnboundedFollowing)
}

/** Partition + order + frame together identify a "window spec". Two window
  * functions with identical specs can share a single partition+sort pass.
  */
final case class WindowSpec(
    partitionKeys: Seq[Expr],
    orderKeys: Seq[SortKey],
    frame: WindowFrame
)

/** One window function call with its output column name. */
final case class WindowDef(spec: WindowSpec, fn: WindowFn, outputName: String)
