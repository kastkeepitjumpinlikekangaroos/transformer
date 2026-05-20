package com.transformer.sql.plan

import com.transformer.core.{DataType, Schema}

/** Rewrites a logical plan so each `LogicalScan` decodes only the columns its
  * ancestors actually reference.
  *
  * Implementation is a single recursive pass that threads a "names my parent
  * needs from me" set down the tree. At each scan we ask the underlying
  * [[com.transformer.core.CatalogView]] for a projected view; if it obliges
  * (parquet does, csv does not), we substitute it and return a remap that
  * the parent uses to rewrite the column indices in its own expressions.
  *
  * Joins prune through too: the parent's needed column names get split by
  * which side's output schema they appear in, augmented with the join
  * condition's own column refs (using indices, so self-joins disambiguate
  * correctly), and pushed into each child. The join's combined output may
  * shrink on both sides, so we rebuild the condition's [[ColRefExpr]]
  * indices and hand the parent a combined remap covering both halves.
  *
  * Unions and window operators are still skipped — their output positions
  * require coordinated remaps across siblings (union) or interact with
  * synthetic `_winN` columns (window) — both viable extensions but
  * deferred behind a separate plan.
  *
  * Push-down is an unconditional logical-plan optimization — it never
  * changes results, only how many bytes the scan decodes.
  */
object ColumnProjectionPushdown {

  /** Catch remap bugs at plan time rather than at run time. The verifier walks
    * the rewritten tree and asserts every [[ColRefExpr]] line up with the
    * relevant child schema by both index range and `dataType`. Cheap — runs
    * once per query at plan time — so always on. */
  private val VerifyRewrites: Boolean = true

  def apply(plan: LogicalPlan): LogicalPlan = {
    // The root's parent (the executor) consumes every output column.
    val (rewritten, _) = rewrite(plan, plan.outputSchema.fieldNames.toSet)
    if (VerifyRewrites) verify(rewritten)
    rewritten
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
      if (keep.length == full.length) {
        (s, identityRemap(full.length))
      } else {
        // When the consumer references zero columns the scan still has to
        // drive batches forward (row counts feed `COUNT(*)` / `COUNT(<lit>)`,
        // `LIMIT n`, etc.) but column *values* go unused. Picking the
        // narrowest column lets parquet skip multi-MB JSON-blob columns that
        // otherwise dominate decode time.
        val request = if (keep.isEmpty) Seq(narrowestColumn(s.outputSchema)) else keep
        view.withProjectedColumns(request) match {
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

    case LogicalJoin(l, r, cond, kind) =>
      val leftSchema = l.outputSchema
      val rightSchema = r.outputSchema
      val leftWidth = leftSchema.length

      // Names the parent wants from each side. A name appearing in both
      // schemas (self-join, common keys) gets kept on both sides — pessimistic
      // but correct. The expressions above the join carry concrete indices,
      // so the parent's `rewriteExpr` against the combined remap routes each
      // ColRefExpr to the right column regardless of the name overlap.
      val parentLeftNeeded = neededByParent.filter(leftSchema.fieldNames.contains)
      val parentRightNeeded = neededByParent.filter(rightSchema.fieldNames.contains)

      // Columns referenced by the join condition itself. Indices in [0, leftWidth)
      // are left-side; the rest are right-side. Walking by index disambiguates
      // even when both sides share a name (the symmetric `l.x = r.x` case).
      val (leftCondNames, rightCondNames) = joinCondNamesPerSide(cond, leftWidth)

      val leftNeeded = parentLeftNeeded ++ leftCondNames
      val rightNeeded = parentRightNeeded ++ rightCondNames

      val (newLeft, leftRemap) = rewrite(l, leftNeeded)
      val (newRight, rightRemap) = rewrite(r, rightNeeded)

      val newLeftWidth = newLeft.outputSchema.length

      // Rebuild the condition's ColRefExpr indices against the new combined
      // schema. Left refs use leftRemap; right refs shift through rightRemap
      // and then by the new (possibly smaller) left width.
      val newCond = remapJoinCondition(cond, leftWidth, leftRemap, newLeftWidth, rightRemap)

      // Combined remap for the parent: left index i → leftRemap(i); right
      // index leftWidth + j → newLeftWidth + rightRemap(j). Pruned columns
      // are absent from the remap, which is fine — the parent only looks up
      // indices it asked us to keep.
      val combinedRemap = combineRemaps(leftRemap, rightRemap, leftWidth, newLeftWidth)

      val newJoin = LogicalJoin(newLeft, newRight, newCond, kind)
      if (VerifyRewrites) verify(newJoin)
      (newJoin, combinedRemap)

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
    a.args.iterator.flatMap(colRefNames).toSet

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
    case AggExprStddev(c, s) => AggExprStddev(rewriteExpr(c, remap), s)
    case AggExprVariance(c, s) => AggExprVariance(rewriteExpr(c, remap), s)
    case AggExprCovar(x, y, s) => AggExprCovar(rewriteExpr(x, remap), rewriteExpr(y, remap), s)
    case AggExprCorr(x, y) => AggExprCorr(rewriteExpr(x, remap), rewriteExpr(y, remap))
  }

  /** Pick a single column from `schema` to project to when the consumer
    * doesn't need any column values. Fixed-size primitives beat variable-width
    * strings/binaries; ties break on declared order so the choice is stable.
    */
  private def narrowestColumn(schema: Schema): String =
    schema.fields.iterator.zipWithIndex
      .minBy { case (f, i) => (approxRowWidth(f.dataType), i) }
      ._1.name

  private def approxRowWidth(dt: DataType): Int = dt match {
    case DataType.BooleanType => 1
    case DataType.NullType => 1
    case DataType.IntType | DataType.FloatType | DataType.DateType => 4
    case DataType.LongType | DataType.DoubleType | DataType.TimestampType => 8
    case _: DataType.DecimalType => 16
    case DataType.StringType | DataType.BinaryType => 1024
  }

  /** Walk a join condition and split its [[ColRefExpr]] names by side. Uses
    * the ColRef *index* against `leftWidth` rather than the field name, so a
    * symmetric `l.x = r.x` self-join condition routes the two refs into
    * leftNames / rightNames respectively. */
  private def joinCondNamesPerSide(cond: Expr, leftWidth: Int): (Set[String], Set[String]) = {
    val leftNames = Set.newBuilder[String]
    val rightNames = Set.newBuilder[String]
    def go(e: Expr): Unit = e match {
      case ColRefExpr(i, n, _) =>
        if (i < leftWidth) leftNames += n else rightNames += n
      case LitExpr(_, _) => ()
      case CastExpr(c, _) => go(c)
      case UnaryOpExpr(_, c, _) => go(c)
      case BinOpExpr(_, l, r, _) => go(l); go(r)
      case FuncExpr(_, args, _) => args.foreach(go)
      case CaseExpr(branches, elseE, _) =>
        branches.foreach { case (a, c) => go(a); go(c) }
        elseE.foreach(go)
      case IsNullExpr(c, _) => go(c)
      case InListExpr(c, items, _) => go(c); items.foreach(go)
      case LikeExpr(s, p, _) => go(s); go(p)
    }
    go(cond)
    (leftNames.result(), rightNames.result())
  }

  /** Rebuild every [[ColRefExpr]] in `cond` so its index points into the
    * post-prune combined schema `newLeft ++ newRight`:
    *   - old left refs (`i < oldLeftWidth`) reuse `leftRemap(i)`.
    *   - old right refs (`i >= oldLeftWidth`) become
    *     `newLeftWidth + rightRemap(i - oldLeftWidth)`. */
  private def remapJoinCondition(
      cond: Expr,
      oldLeftWidth: Int,
      leftRemap: Map[Int, Int],
      newLeftWidth: Int,
      rightRemap: Map[Int, Int]
  ): Expr = {
    def remapIndex(i: Int): Int =
      if (i < oldLeftWidth) leftRemap.getOrElse(i, i)
      else newLeftWidth + rightRemap.getOrElse(i - oldLeftWidth, i - oldLeftWidth)
    def go(e: Expr): Expr = e match {
      case ColRefExpr(i, n, dt) => ColRefExpr(remapIndex(i), n, dt)
      case lit: LitExpr => lit
      case CastExpr(c, t) => CastExpr(go(c), t)
      case UnaryOpExpr(op, c, dt) => UnaryOpExpr(op, go(c), dt)
      case BinOpExpr(op, l, r, dt) => BinOpExpr(op, go(l), go(r), dt)
      case FuncExpr(n, args, dt) => FuncExpr(n, args.map(go), dt)
      case CaseExpr(branches, elseE, dt) =>
        CaseExpr(branches.map { case (a, b) => (go(a), go(b)) }, elseE.map(go), dt)
      case IsNullExpr(c, neg) => IsNullExpr(go(c), neg)
      case InListExpr(c, items, neg) => InListExpr(go(c), items.map(go), neg)
      case LikeExpr(s, p, neg) => LikeExpr(go(s), go(p), neg)
    }
    go(cond)
  }

  /** Combine the per-side remaps into one over the join's combined output.
    * Pruned columns stay absent from the result, which is fine — the parent
    * only ever asks for indices it included in `neededByParent`. */
  private def combineRemaps(
      leftRemap: Map[Int, Int],
      rightRemap: Map[Int, Int],
      oldLeftWidth: Int,
      newLeftWidth: Int
  ): Map[Int, Int] = {
    val b = Map.newBuilder[Int, Int]
    leftRemap.foreach { case (oldI, newI) => b += (oldI -> newI) }
    rightRemap.foreach { case (oldJ, newJ) =>
      b += ((oldLeftWidth + oldJ) -> (newLeftWidth + newJ))
    }
    b.result()
  }

  /** Plan-time correctness check: every [[ColRefExpr]] must point at a real
    * column in the relevant child's schema, with matching `dataType`. Runs
    * after the join rewrite (locally) and again at the top of [[apply]]
    * (whole-tree) so an index/remap bug is caught at plan time with a
    * targeted error rather than as an `ArrayIndexOutOfBoundsException` deep
    * inside the executor. */
  private def verify(plan: LogicalPlan): Unit = {
    def checkExpr(e: Expr, schema: Schema, ctx: String): Unit = e match {
      case ColRefExpr(i, n, dt) =>
        if (i < 0 || i >= schema.length)
          throw new IllegalStateException(
            s"ColRefExpr index $i out of range [0, ${schema.length}) in $ctx " +
              s"(col='$n', type=$dt; schema=${schema.fieldNames.mkString("[", ",", "]")})")
        val f = schema.fields(i)
        if (f.dataType != dt)
          throw new IllegalStateException(
            s"ColRefExpr dataType mismatch at index $i in $ctx: ColRef says $dt, " +
              s"schema says ${f.dataType} (col='$n', schema name='${f.name}')")
      case LitExpr(_, _) => ()
      case CastExpr(c, _) => checkExpr(c, schema, ctx)
      case UnaryOpExpr(_, c, _) => checkExpr(c, schema, ctx)
      case BinOpExpr(_, l, r, _) => checkExpr(l, schema, ctx); checkExpr(r, schema, ctx)
      case FuncExpr(_, args, _) => args.foreach(checkExpr(_, schema, ctx))
      case CaseExpr(branches, elseE, _) =>
        branches.foreach { case (a, b) => checkExpr(a, schema, ctx); checkExpr(b, schema, ctx) }
        elseE.foreach(checkExpr(_, schema, ctx))
      case IsNullExpr(c, _) => checkExpr(c, schema, ctx)
      case InListExpr(c, items, _) =>
        checkExpr(c, schema, ctx); items.foreach(checkExpr(_, schema, ctx))
      case LikeExpr(s, p, _) => checkExpr(s, schema, ctx); checkExpr(p, schema, ctx)
    }

    def checkAgg(a: AggExpr, schema: Schema, ctx: String): Unit =
      a.args.foreach(checkExpr(_, schema, ctx))

    def visit(p: LogicalPlan): Unit = p match {
      case _: LogicalScan => ()
      case LogicalProject(child, projs) =>
        visit(child)
        projs.foreach { case (e, n) => checkExpr(e, child.outputSchema, s"Project($n)") }
      case LogicalFilter(child, pred) =>
        visit(child)
        checkExpr(pred, child.outputSchema, "Filter")
      case LogicalAggregate(child, gks, aggs, having) =>
        visit(child)
        gks.foreach { case (e, _) => checkExpr(e, child.outputSchema, "Aggregate.groupKey") }
        aggs.foreach { case (a, _) => checkAgg(a, child.outputSchema, "Aggregate.agg") }
        having.foreach(checkExpr(_, child.outputSchema, "Aggregate.having"))
      case LogicalJoin(l, r, cond, _) =>
        visit(l); visit(r)
        val combined = Schema(l.outputSchema.fields ++ r.outputSchema.fields)
        checkExpr(cond, combined, "Join.condition")
      case LogicalSort(child, keys) =>
        visit(child)
        keys.foreach { case (e, _) => checkExpr(e, child.outputSchema, "Sort.key") }
      case LogicalLimit(child, _) => visit(child)
      case LogicalDistinct(child) => visit(child)
      case LogicalUnion(l, r, _) => visit(l); visit(r)
      case LogicalWindow(child, _) => visit(child)
    }

    visit(plan)
  }
}
