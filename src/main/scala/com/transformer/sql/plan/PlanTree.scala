package com.transformer.sql.plan

/** A logical plan as an indented tree of its operators, one line each, the
  * way EXPLAIN shows it: each node's expressions printed as SQL
  * ([[PlanSql.expression]]), a column read by the name it has where it is
  * read, qualified by its table's alias below the first projection.
  *
  * {{{
  * Project: t.a, q.x
  *   Join INNER: t.a = u.a
  *     Scan t: a, s
  *     Filter: u.x = 'two'
  *       Scan u: a, x
  * }}}
  */
object PlanTree {

  /** `plan`'s lines, its root first and each child indented under it. */
  def lines(plan: LogicalPlan): Vector[String] = {
    val out = Vector.newBuilder[String]
    def visit(p: LogicalPlan, depth: Int): Unit = {
      out += ("  " * depth) + node(p)
      p.children.foreach(visit(_, depth + 1))
    }
    visit(plan, 0)
    out.result()
  }

  /** The names `p`'s output columns are read by above it. */
  def labels(p: LogicalPlan): IndexedSeq[String] = p match {
    case LogicalScan(name, _, alias) =>
      val qualifier = PlanSql.identifier(alias.getOrElse(name))
      p.outputSchema.fieldNames.map(n => s"$qualifier.${PlanSql.identifier(n)}")
    case LogicalJoin(l, r, _, _) => labels(l) ++ labels(r)
    case LogicalFilter(c, _) => labels(c)
    case LogicalSort(c, _) => labels(c)
    case LogicalLimit(c, _) => labels(c)
    case LogicalOffset(c, _) => labels(c)
    case LogicalDistinct(c) => labels(c)
    case LogicalUnion(l, _, _) => labels(l)
    case LogicalWindow(c, windows) => labels(c) ++ windows.map(w => PlanSql.identifier(w.outputName))
    case other => other.outputSchema.fieldNames.map(PlanSql.identifier)
  }

  private def node(p: LogicalPlan): String = {
    def in(child: LogicalPlan): Int => String = labels(child)
    def expr(e: Expr, child: LogicalPlan): String = PlanSql.expression(e, in(child))
    def named(e: Expr, name: String, child: LogicalPlan): String = {
      val text = expr(e, child)
      val shown = PlanSql.identifier(name)
      if (text == shown || text.endsWith("." + shown)) text else s"$text AS $shown"
    }
    def sortKey(k: SortKey, child: LogicalPlan): String =
      expr(k.expr, child) + (if (k.asc) "" else " DESC") +
        (if (k.nullsFirst == k.asc) "" else if (k.nullsFirst) " NULLS FIRST" else " NULLS LAST")
    p match {
      case s @ LogicalScan(name, view, alias) =>
        if (LogicalBuilder.readsNoTable(s)) "Values: one row, no columns"
        else {
          val what = view match {
            case pending: PendingCteView => s"CTE ${PlanSql.identifier(pending.cteName)}"
            case _ => PlanSql.identifier(name)
          }
          val as = alias.filterNot(_.equalsIgnoreCase(name)).fold("")(a => s" AS ${PlanSql.identifier(a)}")
          s"Scan $what$as: ${view.schema.fieldNames.map(PlanSql.identifier).mkString(", ")}"
        }
      case LogicalProject(c, projections) => "Project: " + projections.map { case (e, n) => named(e, n, c) }.mkString(", ")
      case LogicalFilter(c, predicate) => s"Filter: ${expr(predicate, c)}"
      case LogicalAggregate(c, keys, aggregates) =>
        val parts =
          (if (keys.isEmpty) Nil else Seq("GROUP BY " + keys.map { case (e, n) => named(e, n, c) }.mkString(", "))) ++
            (if (aggregates.isEmpty) Nil
            else Seq(aggregates.map { case (a, n) => s"${PlanSql.aggregate(a, in(c))} AS ${PlanSql.identifier(n)}" }
              .mkString(", ")))
        if (parts.isEmpty) "Aggregate: one group" else "Aggregate: " + parts.mkString("; ")
      case LogicalJoin(l, r, condition, kind) =>
        val combined: Int => String = { val all = labels(l) ++ labels(r); i => all(i) }
        if (kind == JoinKind.Inner && condition == LitExpr(true, com.transformer.core.DataType.BooleanType)) "Join CROSS"
        else s"Join ${kind.toString.toUpperCase(java.util.Locale.ROOT)}: ${PlanSql.expression(condition, combined)}"
      case LogicalSort(c, keys) => "Sort: " + keys.map(sortKey(_, c)).mkString(", ")
      case LogicalLimit(_, n) => s"Limit: $n"
      case LogicalOffset(_, n) => s"Offset: $n"
      case LogicalDistinct(_) => "Distinct"
      case LogicalUnion(_, _, all) => if (all) "Union ALL" else "Union"
      case LogicalWindow(c, windows) =>
        "Window: " + windows.map(w => s"${PlanSql.window(w, in(c))} AS ${PlanSql.identifier(w.outputName)}").mkString(", ")
    }
  }
}
