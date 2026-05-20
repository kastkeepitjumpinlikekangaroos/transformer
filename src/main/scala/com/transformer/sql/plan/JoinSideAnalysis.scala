package com.transformer.sql.plan

/** Classifies a join-level expression by which side(s) of the join's combined
  * output schema it references.
  *
  * A bound expression above a [[LogicalJoin]] (filter pred, join condition,
  * residual) uses [[ColRefExpr]] indices into the join's combined output
  * `left ++ right`. Indices in `[0, leftWidth)` refer to left columns; indices
  * in `[leftWidth, leftWidth + rightWidth)` refer to right columns. The
  * classifier walks the expression and folds per-leaf classifications into one
  * of four states:
  *
  *   - [[JoinSide.LeftOnly]] — every column reference is on the left side.
  *   - [[JoinSide.RightOnly]] — every column reference is on the right side.
  *   - [[JoinSide.Both]] — at least one reference per side; the expression
  *     can't move into either child intact.
  *   - [[JoinSide.Neither]] — no column references at all (constants, pure
  *     functions like `current_timestamp()`).
  *
  * Used by [[com.transformer.sql.exec.PhysicalPlanner]] (to extract equality
  * conjuncts as join keys) and [[FilterPushdown]] (to decide which side of a
  * join a filter conjunct can sink into).
  */
sealed trait JoinSide
object JoinSide {
  case object LeftOnly extends JoinSide
  case object RightOnly extends JoinSide
  case object Both extends JoinSide
  case object Neither extends JoinSide
}

object JoinSideAnalysis {

  /** Walk `e` and return the most specific [[JoinSide]] that covers every
    * [[ColRefExpr]] inside it. */
  def sideOf(e: Expr, leftWidth: Int): JoinSide = e match {
    case ColRefExpr(i, _, _) =>
      if (i < leftWidth) JoinSide.LeftOnly else JoinSide.RightOnly
    case LitExpr(_, _) => JoinSide.Neither
    case CastExpr(c, _) => sideOf(c, leftWidth)
    case UnaryOpExpr(_, c, _) => sideOf(c, leftWidth)
    case BinOpExpr(_, l, r, _) => merge(sideOf(l, leftWidth), sideOf(r, leftWidth))
    case FuncExpr(_, args, _) =>
      args.foldLeft[JoinSide](JoinSide.Neither)((acc, a) => merge(acc, sideOf(a, leftWidth)))
    case CaseExpr(branches, elseE, _) =>
      val branchSides = branches.flatMap { case (a, b) =>
        Seq(sideOf(a, leftWidth), sideOf(b, leftWidth))
      }
      val elseSides = elseE.map(sideOf(_, leftWidth)).toSeq
      (branchSides ++ elseSides).foldLeft[JoinSide](JoinSide.Neither)(merge)
    case IsNullExpr(c, _) => sideOf(c, leftWidth)
    case InListExpr(c, items, _) =>
      val all = sideOf(c, leftWidth) +: items.map(sideOf(_, leftWidth))
      all.foldLeft[JoinSide](JoinSide.Neither)(merge)
    case LikeExpr(s, p, _) => merge(sideOf(s, leftWidth), sideOf(p, leftWidth))
  }

  def merge(a: JoinSide, b: JoinSide): JoinSide = (a, b) match {
    case (JoinSide.Neither, x) => x
    case (x, JoinSide.Neither) => x
    case (JoinSide.LeftOnly, JoinSide.LeftOnly) => JoinSide.LeftOnly
    case (JoinSide.RightOnly, JoinSide.RightOnly) => JoinSide.RightOnly
    case _ => JoinSide.Both
  }

  /** Re-index a right-side expression so its [[ColRefExpr]]s (currently using
    * combined indices `>= leftWidth`) refer to indices `0..rightWidth` into the
    * right plan's standalone output.
    *
    * Used both by the physical planner (to shift right-side join keys for
    * [[com.transformer.sql.exec.HashJoinExec]]) and by [[FilterPushdown]] (to
    * shift a right-only filter conjunct so it can be wrapped as a
    * [[LogicalFilter]] over the right child).
    */
  def shiftToRight(e: Expr, leftWidth: Int): Expr = e match {
    case ColRefExpr(i, n, dt) => ColRefExpr(i - leftWidth, n, dt)
    case lit: LitExpr => lit
    case CastExpr(c, t) => CastExpr(shiftToRight(c, leftWidth), t)
    case UnaryOpExpr(op, c, dt) => UnaryOpExpr(op, shiftToRight(c, leftWidth), dt)
    case BinOpExpr(op, l, r, dt) =>
      BinOpExpr(op, shiftToRight(l, leftWidth), shiftToRight(r, leftWidth), dt)
    case FuncExpr(n, args, dt) => FuncExpr(n, args.map(shiftToRight(_, leftWidth)), dt)
    case CaseExpr(branches, elseE, dt) =>
      CaseExpr(
        branches.map { case (a, b) => (shiftToRight(a, leftWidth), shiftToRight(b, leftWidth)) },
        elseE.map(shiftToRight(_, leftWidth)),
        dt)
    case IsNullExpr(c, neg) => IsNullExpr(shiftToRight(c, leftWidth), neg)
    case InListExpr(c, items, neg) =>
      InListExpr(shiftToRight(c, leftWidth), items.map(shiftToRight(_, leftWidth)), neg)
    case LikeExpr(s, p, neg) =>
      LikeExpr(shiftToRight(s, leftWidth), shiftToRight(p, leftWidth), neg)
  }
}
