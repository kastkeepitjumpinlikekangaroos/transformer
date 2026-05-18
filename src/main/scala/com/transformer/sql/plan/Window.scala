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
  /** Window-frame-driven aggregates need to know the frame to compute their value;
    * ranking functions and LAG/LEAD ignore the frame and use partition+order alone.
    */
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

/** Frame bounds. Offsets are non-negative row counts; CURRENT_ROW etc. carry
  * no payload. RANGE frames are accepted by the parser but treated as ROWS by
  * the executor (documented in CLAUDE.md).
  */
sealed trait FrameBound
object FrameBound {
  case object UnboundedPreceding extends FrameBound
  case object UnboundedFollowing extends FrameBound
  case object CurrentRow extends FrameBound
  final case class Preceding(n: Long) extends FrameBound
  final case class Following(n: Long) extends FrameBound
}

sealed trait FrameType
object FrameType {
  case object Rows extends FrameType
  /** RANGE is parsed but executed as ROWS — see CLAUDE.md "What's intentionally NOT done". */
  case object Range extends FrameType
}

final case class WindowFrame(frameType: FrameType, start: FrameBound, end: FrameBound)

object WindowFrame {
  /** SQL-standard default when ORDER BY is present: RANGE BETWEEN UNBOUNDED PRECEDING
    * AND CURRENT ROW. Without an ORDER BY: the entire partition.
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
    orderKeys: Seq[(Expr, Boolean)],
    frame: WindowFrame
)

/** One window function call with its output column name. */
final case class WindowDef(spec: WindowSpec, fn: WindowFn, outputName: String)
