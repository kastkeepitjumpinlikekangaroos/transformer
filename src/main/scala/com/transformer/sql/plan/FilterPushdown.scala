package com.transformer.sql.plan

import com.transformer.core.DataType

/** Pushes filter conjuncts through joins so each conjunct sits as close to the
  * underlying scan as it can correctly run.
  *
  * The pattern is `LogicalFilter(LogicalJoin(left, right, cond, kind), pred)`.
  * Conjuncts of `pred` are classified by [[JoinSideAnalysis.sideOf]] and either
  * pushed under the matching child (as a new [[LogicalFilter]]) or left above
  * the join. The exact rules depend on the join kind:
  *
  *   - INNER: left-only conjuncts go into the left child; right-only into the
  *     right child; both-side conjuncts stay above.
  *   - LEFT outer: left-only conjuncts push into the left child. Right-only
  *     conjuncts cannot be pushed — `LEFT JOIN ... WHERE r.x = 1` evaluates
  *     `r.x = 1` against the null-extended rows that come back when the right
  *     side has no match, so pushing the filter into the right child would
  *     drop rows that the post-join filter would keep (or vice versa). Leave
  *     them above. Both-side conjuncts also stay above.
  *   - RIGHT outer: symmetric to LEFT.
  *   - FULL outer: nothing is pushable — both sides emit null-extended rows.
  *
  * The pass is correctness-preserving by construction. It is also idempotent:
  * a second invocation finds no further opportunities (every conjunct already
  * lives in its narrowest correct position) and returns the same plan.
  *
  * The win is that pushed conjuncts can fire under the join's child scan,
  * where the parquet predicate-pushdown in
  * [[com.transformer.sql.exec.PhysicalPlanner]] can prove whole row groups
  * don't match and skip their decode entirely.
  */
object FilterPushdown {

  def apply(plan: LogicalPlan): LogicalPlan = rewrite(plan)

  private def rewrite(plan: LogicalPlan): LogicalPlan = plan match {
    case LogicalFilter(child, pred) =>
      // Recurse first so the child is already in pushed form. Then if the
      // rewritten child is a Filter (residual from a deeper join push), merge
      // its conjuncts with ours so we can push as much as possible in one go.
      val outerConjuncts = collectConjuncts(pred)
      val rewrittenChild = rewrite(child)
      val (innerPlan, allConjuncts) = rewrittenChild match {
        case LogicalFilter(c, innerPred) => (c, outerConjuncts ++ collectConjuncts(innerPred))
        case other => (other, outerConjuncts)
      }
      innerPlan match {
        case j: LogicalJoin => pushIntoJoin(j, allConjuncts)
        case other => LogicalFilter(other, andAll(allConjuncts))
      }

    case LogicalProject(child, projs) => LogicalProject(rewrite(child), projs)
    case LogicalAggregate(child, gks, aggs, having) =>
      LogicalAggregate(rewrite(child), gks, aggs, having)
    case LogicalSort(child, keys) => LogicalSort(rewrite(child), keys)
    case LogicalLimit(child, n) => LogicalLimit(rewrite(child), n)
    case LogicalDistinct(child) => LogicalDistinct(rewrite(child))
    case LogicalUnion(l, r, all) => LogicalUnion(rewrite(l), rewrite(r), all)
    case LogicalJoin(l, r, cond, kind) =>
      LogicalJoin(rewrite(l), rewrite(r), cond, kind)
    case LogicalWindow(child, windows) => LogicalWindow(rewrite(child), windows)
    case s: LogicalScan => s
  }

  /** Push `conjuncts` (already flattened from an AND-chain) into `join`'s
    * children where the join kind permits, leaving the rest above. The
    * returned plan re-runs [[rewrite]] on each child so a pushed filter that
    * lands above another join can cascade further down.
    */
  private def pushIntoJoin(join: LogicalJoin, conjuncts: Seq[Expr]): LogicalPlan = {
    val LogicalJoin(left, right, cond, kind) = join
    val leftWidth = left.outputSchema.length

    val pushLeftOk = kind match {
      case JoinKind.Inner | JoinKind.Left => true
      case _ => false
    }
    val pushRightOk = kind match {
      case JoinKind.Inner | JoinKind.Right => true
      case _ => false
    }

    val leftPush = scala.collection.mutable.ArrayBuffer.empty[Expr]
    val rightPush = scala.collection.mutable.ArrayBuffer.empty[Expr]
    val keep = scala.collection.mutable.ArrayBuffer.empty[Expr]

    conjuncts.foreach { c =>
      JoinSideAnalysis.sideOf(c, leftWidth) match {
        case JoinSide.LeftOnly if pushLeftOk => leftPush += c
        case JoinSide.RightOnly if pushRightOk =>
          rightPush += JoinSideAnalysis.shiftToRight(c, leftWidth)
        case _ => keep += c
      }
    }

    val pushedLeft: LogicalPlan =
      if (leftPush.isEmpty) left
      else rewrite(LogicalFilter(left, andAll(leftPush.toSeq)))
    val pushedRight: LogicalPlan =
      if (rightPush.isEmpty) right
      else rewrite(LogicalFilter(right, andAll(rightPush.toSeq)))

    val newJoin = LogicalJoin(pushedLeft, pushedRight, cond, kind)
    if (keep.isEmpty) newJoin else LogicalFilter(newJoin, andAll(keep.toSeq))
  }

  private def collectConjuncts(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => collectConjuncts(l) ++ collectConjuncts(r)
    case other => Seq(other)
  }

  private def andAll(es: Seq[Expr]): Expr =
    es.reduceLeft[Expr] { (a, b) => BinOpExpr("AND", a, b, DataType.BooleanType) }
}
