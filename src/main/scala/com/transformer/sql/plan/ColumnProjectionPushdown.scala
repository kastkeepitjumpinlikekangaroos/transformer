package com.transformer.sql.plan

/** Rewrites a logical plan so each `LogicalScan` decodes only the columns its
  * ancestors actually reference.
  *
  * Implementation is a single recursive pass that threads a "names my parent
  * needs from me" set down the tree. At each scan we ask the underlying
  * [[com.transformer.core.CatalogView]] for a projected view; if it obliges
  * (parquet does, csv does not), we substitute it and return a remap that
  * the parent uses to rewrite the column indices in its own expressions.
  *
  * The pass is conservative: joins, unions, and window operators are not
  * pruned, since their output positions are sensitive to combined schemas
  * and would require coordinated remaps across siblings. Inside a single
  * scan's pipeline (Filter / Project / Aggregate / Sort / Limit / Distinct /
  * Having) all expressions get rebound.
  *
  * Push-down is an unconditional logical-plan optimization — it never
  * changes results, only how many bytes the scan decodes.
  */
object ColumnProjectionPushdown {

  def apply(plan: LogicalPlan): LogicalPlan = {
    // The root's parent (the executor) consumes every output column.
    rewrite(plan, plan.outputSchema.fieldNames.toSet)._1
  }

  /** Rewrite `plan` given the set of column names the parent needs from its
    * output. Returns (newPlan, remap) where `remap` maps old → new positions
    * in the rewritten plan's output. Parents apply `remap` to rewrite the
    * indices of any [[ColRefExpr]] over `plan`.
    */
  private def rewrite(
      plan: LogicalPlan,
      neededByParent: Set[String]
  ): (LogicalPlan, Map[Int, Int]) = plan match {

    case s @ LogicalScan(_, view, _) =>
      val full = s.outputSchema.fieldNames
      // Keep original order; the view's contract is to do the same.
      val keep = full.iterator.filter(neededByParent.contains).toList
      if (keep.isEmpty || keep.length == full.length) {
        (s, identityRemap(full.length))
      } else {
        view.withProjectedColumns(keep) match {
          case Some(projected) =>
            val newCols = projected.schema.fieldNames
            val remap = full.iterator.zipWithIndex.collect {
              case (n, oldIdx) if newCols.contains(n) =>
                (oldIdx, newCols.indexOf(n))
            }.toMap
            (s.copy(view = projected), remap)
          case None => (s, identityRemap(full.length))
        }
      }

    case LogicalFilter(child, pred) =>
      val childNeeded = colRefNames(pred) ++ neededByParent
      val (newChild, remap) = rewrite(child, childNeeded)
      (LogicalFilter(newChild, rewriteExpr(pred, remap)), remap)

    case LogicalProject(child, projections) =>
      val childNeeded = projections.iterator.flatMap { case (e, _) => colRefNames(e) }.toSet
      val (newChild, remap) = rewrite(child, childNeeded)
      val newProjs = projections.map { case (e, n) => (rewriteExpr(e, remap), n) }
      // Project re-emits its own schema; positions of *my* output don't shift
      // for the parent, so identity remap upward.
      (LogicalProject(newChild, newProjs), identityRemap(projections.length))

    case LogicalAggregate(child, gks, aggs, having) =>
      val childNeeded =
        gks.iterator.flatMap { case (e, _) => colRefNames(e) }.toSet ++
          aggs.iterator.flatMap { case (a, _) => aggColRefNames(a) }.toSet ++
          having.iterator.flatMap(colRefNames).toSet
      val (newChild, remap) = rewrite(child, childNeeded)
      val newGks = gks.map { case (e, n) => (rewriteExpr(e, remap), n) }
      val newAggs = aggs.map { case (a, n) => (rewriteAgg(a, remap), n) }
      val newHaving = having.map(rewriteExpr(_, remap))
      // Aggregate output = gks ++ aggs in that order — its own schema, not the child's.
      (LogicalAggregate(newChild, newGks, newAggs, newHaving),
        identityRemap(gks.length + aggs.length))

    case LogicalSort(child, keys) =>
      val childNeeded = keys.iterator.flatMap { case (e, _) => colRefNames(e) }.toSet ++ neededByParent
      val (newChild, remap) = rewrite(child, childNeeded)
      val newKeys = keys.map { case (e, asc) => (rewriteExpr(e, remap), asc) }
      (LogicalSort(newChild, newKeys), remap)

    case LogicalLimit(child, n) =>
      val (newChild, remap) = rewrite(child, neededByParent)
      (LogicalLimit(newChild, n), remap)

    case LogicalDistinct(child) =>
      val (newChild, remap) = rewrite(child, neededByParent)
      (LogicalDistinct(newChild), remap)

    case u: LogicalUnion =>
      // Pruning a Union requires identical column sets on both sides AND
      // identical remaps so positional alignment survives. Skip for now.
      (u, identityRemap(u.outputSchema.length))

    case j: LogicalJoin =>
      // Join references columns by combined-output indices; pruning either
      // side shifts everything to its right. Doable but invasive — skip for now.
      (j, identityRemap(j.outputSchema.length))

    case w: LogicalWindow =>
      // Window's output is child + synthetic _winN columns; pruning the
      // child shifts the _winN positions. Skip for now.
      (w, identityRemap(w.outputSchema.length))
  }

  private def identityRemap(n: Int): Map[Int, Int] = {
    val b = Map.newBuilder[Int, Int]
    var i = 0
    while (i < n) { b += (i -> i); i += 1 }
    b.result()
  }

  /** Names of every [[ColRefExpr]] reached by walking `e`. Used to figure out
    * which child output columns this expression depends on.
    */
  private def colRefNames(e: Expr): Set[String] = {
    val b = Set.newBuilder[String]
    def go(e: Expr): Unit = e match {
      case ColRefExpr(_, n, _) => b += n
      case LitExpr(_, _) => ()
      case CastExpr(c, _) => go(c)
      case UnaryOpExpr(_, c, _) => go(c)
      case BinOpExpr(_, l, r, _) => go(l); go(r)
      case FuncExpr(_, args, _) => args.foreach(go)
      case CaseExpr(branches, elseE, _) =>
        branches.foreach { case (a, c) => go(a); go(c) }; elseE.foreach(go)
      case IsNullExpr(c, _) => go(c)
      case InListExpr(c, items, _) => go(c); items.foreach(go)
      case LikeExpr(s, p, _) => go(s); go(p)
    }
    go(e); b.result()
  }

  private def aggColRefNames(a: AggExpr): Set[String] =
    a.arg.map(colRefNames).getOrElse(Set.empty)

  /** Rewrite every [[ColRefExpr]] inside `e` using `remap`. Indices not in the
    * map pass through unchanged — Project / Aggregate emit identity-remapped
    * outputs, so the same expression tree threads through cleanly.
    */
  private def rewriteExpr(e: Expr, remap: Map[Int, Int]): Expr = e match {
    case ColRefExpr(i, n, dt) => ColRefExpr(remap.getOrElse(i, i), n, dt)
    case c: LitExpr => c
    case CastExpr(c, t) => CastExpr(rewriteExpr(c, remap), t)
    case UnaryOpExpr(op, c, dt) => UnaryOpExpr(op, rewriteExpr(c, remap), dt)
    case BinOpExpr(op, l, r, dt) => BinOpExpr(op, rewriteExpr(l, remap), rewriteExpr(r, remap), dt)
    case FuncExpr(n, args, dt) => FuncExpr(n, args.map(rewriteExpr(_, remap)), dt)
    case CaseExpr(branches, elseE, dt) =>
      CaseExpr(branches.map { case (a, b) => (rewriteExpr(a, remap), rewriteExpr(b, remap)) },
        elseE.map(rewriteExpr(_, remap)), dt)
    case IsNullExpr(c, neg) => IsNullExpr(rewriteExpr(c, remap), neg)
    case InListExpr(c, items, neg) => InListExpr(rewriteExpr(c, remap), items.map(rewriteExpr(_, remap)), neg)
    case LikeExpr(s, p, neg) => LikeExpr(rewriteExpr(s, remap), rewriteExpr(p, remap), neg)
  }

  private def rewriteAgg(a: AggExpr, remap: Map[Int, Int]): AggExpr = a match {
    case _: AggExprCountStar => a
    case AggExprCount(c, d) => AggExprCount(rewriteExpr(c, remap), d)
    case AggExprSum(c) => AggExprSum(rewriteExpr(c, remap))
    case AggExprAvg(c) => AggExprAvg(rewriteExpr(c, remap))
    case AggExprMin(c) => AggExprMin(rewriteExpr(c, remap))
    case AggExprMax(c) => AggExprMax(rewriteExpr(c, remap))
    case AggExprCountIf(c) => AggExprCountIf(rewriteExpr(c, remap))
  }
}
