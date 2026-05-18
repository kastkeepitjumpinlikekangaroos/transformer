package com.transformer.sql.exec

import com.transformer.core.DataType
import com.transformer.sql.plan._

import scala.collection.mutable

/** Logical → physical conversion. Applies a small set of rewrites first
  * (projection pruning, predicate pushdown for join keys, equality split).
  */
object PhysicalPlanner {

  def plan(logical: LogicalPlan): PhysicalPlan = logical match {
    case LogicalScan(_, view, _) => ScanExec(view)
    case LogicalFilter(child, pred) => FilterExec(plan(child), pred)
    case LogicalProject(child, projs) => ProjectExec(plan(child), projs)
    // Fast path: `SELECT COUNT(*) FROM <view>` with no WHERE, no GROUP BY, no HAVING.
    // The view can answer from metadata (parquet footer, in-memory row count) so
    // we skip the entire scan + per-row aggregation pipeline.
    case LogicalAggregate(LogicalScan(_, view, _), Seq(), Seq((AggExprCountStar(), name)), None)
        if view.exactRowCount.isDefined =>
      CountStarMetadataExec(view.exactRowCount.get, name)
    case LogicalAggregate(child, gks, aggs, _) => HashAggregateExec(plan(child), gks, aggs)
    case LogicalSort(child, keys) => SortExec(plan(child), keys)
    case LogicalDistinct(child) => DistinctExec(plan(child))
    case LogicalUnion(l, r, all) =>
      val u = UnionExec(plan(l), plan(r))
      if (all) u else DistinctExec(u)
    case LogicalLimit(child, n) =>
      val p = plan(child)
      if (p.numPartitions <= 1) LocalLimitExec(p, n)
      else GlobalLimitExec(LocalLimitExec(p, n), n)
    case LogicalJoin(l, r, cond, kind) =>
      val left = plan(l)
      val right = plan(r)
      val (leftKeys, rightKeys, extra) = splitEqualityKeys(cond, l.outputSchema.length, r.outputSchema.length)
      HashJoinExec(left, right, leftKeys, rightKeys, extra, kind)
    case LogicalWindow(child, windows) =>
      WindowExec(plan(child), windows)
  }

  /** Split an AND-chain of conjuncts into (leftKey, rightKey) equality pairs and a
    * residual predicate. References on the right side of the join begin at column
    * index `leftWidth` in the combined schema.
    */
  private def splitEqualityKeys(cond: Expr, leftWidth: Int, rightWidth: Int): (Seq[Expr], Seq[Expr], Option[Expr]) = {
    val conjuncts = collectConjuncts(cond)
    val leftKeys = mutable.ArrayBuffer.empty[Expr]
    val rightKeys = mutable.ArrayBuffer.empty[Expr]
    val rest = mutable.ArrayBuffer.empty[Expr]
    conjuncts.foreach {
      case BinOpExpr("=", l, r, _) =>
        (sideOf(l, leftWidth), sideOf(r, leftWidth)) match {
          case (LeftSide, RightSide) =>
            leftKeys += l
            rightKeys += shiftToRight(r, leftWidth)
          case (RightSide, LeftSide) =>
            leftKeys += r
            rightKeys += shiftToRight(l, leftWidth)
          case _ => rest += BinOpExpr("=", l, r, DataType.BooleanType)
        }
      case other => rest += other
    }
    val residual = rest.reduceLeftOption[Expr] { (a, b) => BinOpExpr("AND", a, b, DataType.BooleanType) }
    (leftKeys.toSeq, rightKeys.toSeq, residual)
  }

  private def collectConjuncts(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => collectConjuncts(l) ++ collectConjuncts(r)
    case other => Seq(other)
  }

  private sealed trait Side
  private case object LeftSide extends Side
  private case object RightSide extends Side
  private case object BothSides extends Side
  private case object NoSide extends Side

  private def sideOf(e: Expr, leftWidth: Int): Side = e match {
    case ColRefExpr(i, _, _) => if (i < leftWidth) LeftSide else RightSide
    case LitExpr(_, _) => NoSide
    case CastExpr(c, _) => sideOf(c, leftWidth)
    case BinOpExpr(_, l, r, _) => merge(sideOf(l, leftWidth), sideOf(r, leftWidth))
    case UnaryOpExpr(_, c, _) => sideOf(c, leftWidth)
    case FuncExpr(_, args, _) => args.map(sideOf(_, leftWidth)).foldLeft[Side](NoSide)(merge)
    case CaseExpr(branches, elseE, _) =>
      val all = branches.flatMap { case (a, b) => Seq(sideOf(a, leftWidth), sideOf(b, leftWidth)) } ++
        elseE.map(sideOf(_, leftWidth))
      all.foldLeft[Side](NoSide)(merge)
    case IsNullExpr(c, _) => sideOf(c, leftWidth)
    case InListExpr(c, items, _) =>
      (sideOf(c, leftWidth) +: items.map(sideOf(_, leftWidth))).foldLeft[Side](NoSide)(merge)
    case LikeExpr(s, p, _) => merge(sideOf(s, leftWidth), sideOf(p, leftWidth))
  }

  private def merge(a: Side, b: Side): Side = (a, b) match {
    case (NoSide, x) => x
    case (x, NoSide) => x
    case (LeftSide, LeftSide) => LeftSide
    case (RightSide, RightSide) => RightSide
    case _ => BothSides
  }

  /** Re-index a right-side expression so column refs (which currently use combined
    * indices >= leftWidth) refer to indices 0..rightWidth into the right plan's
    * output. This is what [[HashJoinExec]] expects for its `rightKeys`.
    */
  private def shiftToRight(e: Expr, leftWidth: Int): Expr = e match {
    case ColRefExpr(i, n, dt) => ColRefExpr(i - leftWidth, n, dt)
    case CastExpr(c, t) => CastExpr(shiftToRight(c, leftWidth), t)
    case BinOpExpr(op, l, r, dt) => BinOpExpr(op, shiftToRight(l, leftWidth), shiftToRight(r, leftWidth), dt)
    case UnaryOpExpr(op, c, dt) => UnaryOpExpr(op, shiftToRight(c, leftWidth), dt)
    case FuncExpr(n, args, dt) => FuncExpr(n, args.map(shiftToRight(_, leftWidth)), dt)
    case CaseExpr(branches, elseE, dt) =>
      CaseExpr(branches.map { case (a, b) => (shiftToRight(a, leftWidth), shiftToRight(b, leftWidth)) },
        elseE.map(shiftToRight(_, leftWidth)), dt)
    case IsNullExpr(c, neg) => IsNullExpr(shiftToRight(c, leftWidth), neg)
    case InListExpr(c, items, neg) => InListExpr(shiftToRight(c, leftWidth), items.map(shiftToRight(_, leftWidth)), neg)
    case LikeExpr(s, p, neg) => LikeExpr(shiftToRight(s, leftWidth), shiftToRight(p, leftWidth), neg)
    case lit: LitExpr => lit
  }
}
