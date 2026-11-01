package com.transformer.sql.plan

import com.transformer.core.{DataType, Schema}

/** Checks that a logical plan is well formed, as the binder makes it and after
  * every pass that rewrites it, as Spark validates a plan after each rule and
  * DuckDB verifies its column bindings. A pass that leaves a column reference
  * past its input's columns, or on a column of another type, fails here, at
  * plan time, naming the stage and the node, rather than as an
  * `ArrayIndexOutOfBoundsException` or a wrong answer once rows flow.
  *
  * At every node:
  *   - each column reference is within its input's columns and of that
  *     column's type. A projection's, a filter's, a sort's, an aggregate's
  *     and a window's input is the child's output; a join condition's is both
  *     sides' together.
  *   - a condition is a BOOLEAN: a filter's, a join's, each operand of AND, OR
  *     and NOT, a CASE's WHEN, IF's first argument and COUNT_IF's.
  *   - a comparison's two sides, an IN list's items and a BETWEEN's bounds
  *     have the type their operand has, which the binder casts them to.
  *   - a union's arms have one width and one type per column.
  *   - a scan of a CTE the resolver has not replaced (a [[PendingCteView]])
  *     does not survive CTE resolution.
  *
  * And a pass keeps what its plan returns: the same columns, by name and type,
  * in the same order. Each check walks the plan once, so it runs on every
  * query.
  */
object PlanValidator {

  /** `plan` as [[LogicalBuilder.build]] made it, checked. Scans of the
    * statement's own CTEs are still pending here. */
  def bound(plan: LogicalPlan): LogicalPlan = {
    check(plan, "as bound", ctesPending = true)
    plan
  }

  /** `after`, which the pass `stage` made from `before`, checked: well
    * formed, with the output `before` had. A pass may meet a scan of a CTE
    * that is materialized before the plan runs, as EXPLAIN shows one. */
  def afterPass(stage: String, before: LogicalPlan, after: LogicalPlan): LogicalPlan =
    checked(s"after $stage", before, after, ctesPending = true)

  /** `after`, `before` with its CTEs resolved, checked: no CTE is pending. */
  def resolved(before: LogicalPlan, after: LogicalPlan): LogicalPlan =
    checked("after CTE resolution", before, after, ctesPending = false)

  private def checked(stage: String, before: LogicalPlan, after: LogicalPlan, ctesPending: Boolean): LogicalPlan = {
    check(after, stage, ctesPending)
    val (was, is) = (before.outputSchema, after.outputSchema)
    if (was.fields.map(f => (f.name, f.dataType)) != is.fields.map(f => (f.name, f.dataType)))
      throw malformed(stage, "the plan", s"the output changed from ${columns(was)} to ${columns(is)}")
    after
  }

  /** Throw an [[IllegalStateException]] naming `stage` and the node if `plan`
    * is not well formed. */
  def check(plan: LogicalPlan, stage: String, ctesPending: Boolean): Unit = {
    def visit(p: LogicalPlan, path: String): Unit = {
      val here = if (path.isEmpty) nodeName(p) else s"$path > ${nodeName(p)}"
      def expr(e: Expr, input: Schema, what: String): Unit = checkExpr(e, input, stage, s"$here.$what")
      def condition(e: Expr, input: Schema, what: String): Unit = {
        expr(e, input, what)
        requireBoolean(e, stage, s"$here.$what")
      }
      p match {
        case LogicalScan(name, view, _) =>
          if (!ctesPending && view.isInstanceOf[PendingCteView])
            throw malformed(stage, here, s"the scan of CTE '$name' was never resolved")
        case LogicalProject(child, projections) =>
          projections.foreach { case (e, name) => expr(e, child.outputSchema, s"projection '$name'") }
        case LogicalFilter(child, predicate) => condition(predicate, child.outputSchema, "predicate")
        case LogicalAggregate(child, keys, aggregates) =>
          keys.foreach { case (e, name) => expr(e, child.outputSchema, s"key '$name'") }
          aggregates.foreach { case (a, name) => aggregate(a, child.outputSchema, stage, s"$here.aggregate '$name'") }
        case LogicalJoin(left, right, cond, _) =>
          condition(cond, Schema(left.outputSchema.fields ++ right.outputSchema.fields), "condition")
        case LogicalSort(child, keys) => keys.foreach(k => expr(k.expr, child.outputSchema, "key"))
        case LogicalLimit(_, n) => if (n < 0) throw malformed(stage, here, s"a negative row count, $n")
        case LogicalOffset(_, n) => if (n < 0) throw malformed(stage, here, s"a negative row count, $n")
        case LogicalDistinct(_) => ()
        case LogicalUnion(left, right, _) =>
          val (l, r) = (left.outputSchema, right.outputSchema)
          if (l.fields.map(_.dataType) != r.fields.map(_.dataType))
            throw malformed(stage, here, s"the arms return ${columns(l)} and ${columns(r)}")
        case LogicalWindow(child, windows) =>
          val input = child.outputSchema
          windows.foreach { w =>
            val at = s"window '${w.outputName}'"
            w.spec.partitionKeys.foreach(expr(_, input, s"$at PARTITION BY"))
            w.spec.orderKeys.foreach(k => expr(k.expr, input, s"$at ORDER BY"))
            w.fn match {
              case WindowFnLag(value, _, default) =>
                expr(value, input, at); default.foreach(expr(_, input, s"$at default"))
              case WindowFnLead(value, _, default) =>
                expr(value, input, at); default.foreach(expr(_, input, s"$at default"))
              case WindowFnAgg(a) => aggregate(a, input, stage, s"$here.$at")
              case WindowFnRowNumber() | WindowFnRank() | WindowFnDenseRank() => ()
            }
          }
      }
      p.children.foreach(visit(_, here))
    }
    visit(plan, "")
  }

  private def aggregate(a: AggExpr, input: Schema, stage: String, at: String): Unit = {
    a.args.foreach(checkExpr(_, input, stage, at))
    a match {
      case AggExprCountIf(c) => requireBoolean(c, stage, at)
      case _ => ()
    }
  }

  private def checkExpr(e: Expr, input: Schema, stage: String, at: String): Unit = {
    def go(e: Expr): Unit = e match {
      case ColRefExpr(i, name, t) =>
        if (i < 0 || i >= input.length)
          throw malformed(stage, at, s"column $i ('$name') is past the ${input.length} columns of ${columns(input)}")
        val f = input.fields(i)
        if (f.dataType != t)
          throw malformed(stage, at, s"column $i ('$name') is read as $t, but ${columns(input)} holds a ${f.dataType}")
      case LitExpr(_, _) => ()
      case CastExpr(c, _) => go(c)
      case UnaryOpExpr(op, c, _) =>
        go(c)
        if (op == "NOT") requireBoolean(c, stage, at)
      case BinOpExpr(op, l, r, _) =>
        go(l); go(r)
        op match {
          case "AND" | "OR" => requireBoolean(l, stage, at); requireBoolean(r, stage, at)
          case "=" | "<>" | "<" | "<=" | ">" | ">=" => sameType(op, Seq(l, r), stage, at)
          case _ => ()
        }
      case FuncExpr(name, args, _) =>
        args.foreach(go)
        if (name.equalsIgnoreCase("IF") && args.size == 3) requireBoolean(args.head, stage, at)
      case CaseExpr(branches, elseExpr, _) =>
        branches.foreach { case (w, t) => go(w); requireBoolean(w, stage, at); go(t) }
        elseExpr.foreach(go)
      case SwitchCaseExpr(operand, branches, elseExpr, _) =>
        go(operand)
        branches.foreach { b => go(b.when); go(b.value) }
        elseExpr.foreach(go)
      case BetweenExpr(c, low, high) =>
        go(c); go(low); go(high)
        sameType("BETWEEN", Seq(c, low, high), stage, at)
      case IsNullExpr(c, _) => go(c)
      case InListExpr(c, items, _) =>
        go(c); items.foreach(go)
        sameType("IN", c +: items, stage, at)
      case LikeExpr(c, pattern, _, _) => go(c); go(pattern)
    }
    go(e)
  }

  private def requireBoolean(e: Expr, stage: String, at: String): Unit =
    if (e.dataType != DataType.BooleanType)
      throw malformed(stage, at, s"a condition is a ${e.dataType}, not a BOOLEAN: $e")

  private def sameType(op: String, operands: Seq[Expr], stage: String, at: String): Unit = {
    val types = operands.map(_.dataType).distinct
    if (types.size > 1) throw malformed(stage, at, s"$op meets operands of types ${types.mkString(", ")}")
  }

  private def nodeName(p: LogicalPlan): String = p match {
    case LogicalScan(name, _, _) => s"Scan($name)"
    case _: LogicalProject => "Project"
    case _: LogicalFilter => "Filter"
    case _: LogicalAggregate => "Aggregate"
    case _: LogicalJoin => "Join"
    case _: LogicalSort => "Sort"
    case _: LogicalLimit => "Limit"
    case _: LogicalOffset => "Offset"
    case _: LogicalDistinct => "Distinct"
    case _: LogicalUnion => "Union"
    case _: LogicalWindow => "Window"
  }

  private def columns(s: Schema): String =
    s.fields.map(f => s"${f.name} ${f.dataType}").mkString("(", ", ", ")")

  private def malformed(stage: String, at: String, problem: String): IllegalStateException =
    new IllegalStateException(s"The plan $stage is malformed at $at: $problem")
}
