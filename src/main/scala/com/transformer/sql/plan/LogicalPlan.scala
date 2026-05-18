package com.transformer.sql.plan

import com.transformer.core._

sealed trait JoinKind
object JoinKind {
  case object Inner extends JoinKind
  case object Left extends JoinKind
  case object Right extends JoinKind
  case object Full extends JoinKind
}

sealed trait LogicalPlan {
  def outputSchema: Schema
  def children: Seq[LogicalPlan]
}

final case class LogicalScan(viewName: String, view: CatalogView, alias: Option[String]) extends LogicalPlan {
  def outputSchema: Schema = view.schema
  def children: Seq[LogicalPlan] = Nil
}

final case class LogicalProject(child: LogicalPlan, projections: Seq[(Expr, String)]) extends LogicalPlan {
  def outputSchema: Schema = Schema(projections.iterator.map { case (e, name) =>
    Field(name, e.dataType, nullable = true)
  }.toVector)
  def children: Seq[LogicalPlan] = Seq(child)
}

final case class LogicalFilter(child: LogicalPlan, predicate: Expr) extends LogicalPlan {
  def outputSchema: Schema = child.outputSchema
  def children: Seq[LogicalPlan] = Seq(child)
}

/** GROUP BY aggregation. Output: groupKeys then aggregate results, in that order. */
final case class LogicalAggregate(
    child: LogicalPlan,
    groupKeys: Seq[(Expr, String)],
    aggregates: Seq[(AggExpr, String)],
    having: Option[Expr]
) extends LogicalPlan {
  def outputSchema: Schema = Schema(
    (groupKeys.map { case (e, n) => Field(n, e.dataType) } ++
      aggregates.map { case (a, n) => Field(n, a.resultType) }).toVector
  )
  def children: Seq[LogicalPlan] = Seq(child)
}

final case class LogicalJoin(
    left: LogicalPlan,
    right: LogicalPlan,
    condition: Expr,
    kind: JoinKind
) extends LogicalPlan {
  def outputSchema: Schema = Schema(left.outputSchema.fields ++ right.outputSchema.fields)
  def children: Seq[LogicalPlan] = Seq(left, right)
}

final case class LogicalSort(child: LogicalPlan, keys: Seq[(Expr, Boolean)]) extends LogicalPlan {
  def outputSchema: Schema = child.outputSchema
  def children: Seq[LogicalPlan] = Seq(child)
}

final case class LogicalLimit(child: LogicalPlan, n: Long) extends LogicalPlan {
  def outputSchema: Schema = child.outputSchema
  def children: Seq[LogicalPlan] = Seq(child)
}

final case class LogicalDistinct(child: LogicalPlan) extends LogicalPlan {
  def outputSchema: Schema = child.outputSchema
  def children: Seq[LogicalPlan] = Seq(child)
}

final case class LogicalUnion(left: LogicalPlan, right: LogicalPlan, all: Boolean) extends LogicalPlan {
  def outputSchema: Schema = left.outputSchema
  def children: Seq[LogicalPlan] = Seq(left, right)
}

/** Window-function projection: appends one output column per [[WindowDef]]
  * to the child's schema. Output indices for child columns are preserved.
  */
final case class LogicalWindow(child: LogicalPlan, windows: Seq[WindowDef]) extends LogicalPlan {
  def outputSchema: Schema = Schema(
    child.outputSchema.fields ++ windows.map(w => Field(w.outputName, w.fn.resultType))
  )
  def children: Seq[LogicalPlan] = Seq(child)
}
