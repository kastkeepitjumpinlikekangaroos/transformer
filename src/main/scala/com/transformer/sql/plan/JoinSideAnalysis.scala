package com.transformer.sql.plan

import com.transformer.core.DataType

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
  *   - [[JoinSide.Neither]] — no column references at all (constants,
  *     including the literal `CURRENT_TIMESTAMP` binds to).
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
    case DecimalCastExpr(c, _) => sideOf(c, leftWidth)
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
    case LikeExpr(s, p, _, _) => merge(sideOf(s, leftWidth), sideOf(p, leftWidth))
    case SwitchCaseExpr(operand, branches, elseE, _) =>
      (operand +: (branches.flatMap(b => Seq(b.when, b.value)) ++ elseE)).map(sideOf(_, leftWidth))
        .foldLeft[JoinSide](JoinSide.Neither)(merge)
    case BetweenExpr(c, low, high) =>
      Seq(c, low, high).map(sideOf(_, leftWidth)).foldLeft[JoinSide](JoinSide.Neither)(merge)
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
  def shiftToRight(e: Expr, leftWidth: Int): Expr = remapColumns(e, _ - leftWidth)

  /** `e` with each [[ColRefExpr]]'s index `i` replaced by `to(i)`: how a
    * rewrite that moves columns ([[JoinOrder]] reorders a join's tables)
    * moves the expressions that read them. */
  def remapColumns(e: Expr, to: Int => Int): Expr = e match {
    case ColRefExpr(i, n, dt) => ColRefExpr(to(i), n, dt)
    case lit: LitExpr => lit
    case CastExpr(c, t) => CastExpr(remapColumns(c, to), t)
    case DecimalCastExpr(c, t) => DecimalCastExpr(remapColumns(c, to), t)
    case UnaryOpExpr(op, c, dt) => UnaryOpExpr(op, remapColumns(c, to), dt)
    case BinOpExpr(op, l, r, dt) => BinOpExpr(op, remapColumns(l, to), remapColumns(r, to), dt)
    case FuncExpr(n, args, dt) => FuncExpr(n, args.map(remapColumns(_, to)), dt)
    case CaseExpr(branches, elseE, dt) =>
      CaseExpr(branches.map { case (a, b) => (remapColumns(a, to), remapColumns(b, to)) }, elseE.map(remapColumns(_, to)), dt)
    case IsNullExpr(c, neg) => IsNullExpr(remapColumns(c, to), neg)
    case InListExpr(c, items, neg) => InListExpr(remapColumns(c, to), items.map(remapColumns(_, to)), neg)
    case l: LikeExpr => l.copy(child = remapColumns(l.child, to), pattern = remapColumns(l.pattern, to))
    case SwitchCaseExpr(operand, branches, elseE, dt) =>
      SwitchCaseExpr(remapColumns(operand, to),
        branches.map(b => b.copy(when = remapColumns(b.when, to), value = remapColumns(b.value, to))),
        elseE.map(remapColumns(_, to)), dt)
    case BetweenExpr(c, low, high) => BetweenExpr(remapColumns(c, to), remapColumns(low, to), remapColumns(high, to))
  }

  /** The indices of the columns `e` reads. */
  def columnsRead(e: Expr): Set[Int] = {
    val out = Set.newBuilder[Int]
    def go(x: Expr): Unit = x match {
      case ColRefExpr(i, _, _) => out += i
      case _: LitExpr => ()
      case CastExpr(c, _) => go(c)
      case DecimalCastExpr(c, _) => go(c)
      case UnaryOpExpr(_, c, _) => go(c)
      case BinOpExpr(_, l, r, _) => go(l); go(r)
      case FuncExpr(_, args, _) => args.foreach(go)
      case CaseExpr(branches, elseE, _) => branches.foreach { case (a, b) => go(a); go(b) }; elseE.foreach(go)
      case IsNullExpr(c, _) => go(c)
      case InListExpr(c, items, _) => go(c); items.foreach(go)
      case LikeExpr(c, p, _, _) => go(c); go(p)
      case SwitchCaseExpr(operand, branches, elseE, _) =>
        go(operand); branches.foreach { b => go(b.when); go(b.value) }; elseE.foreach(go)
      case BetweenExpr(c, low, high) => go(c); go(low); go(high)
    }
    go(e)
    out.result()
  }

  /** The conjunction of `conjuncts`, as a balanced tree of ANDs. A pass that
    * splits a predicate into its conjuncts and joins what it keeps uses this,
    * not a left fold: the parser regroups a long AND chain into a balanced
    * tree so that every walk over the plan stays shallow (see
    * `SqlParser.MaxNesting`), and folding the conjuncts back left-deep would
    * nest them a level each again. */
  def conjunction(conjuncts: Seq[Expr]): Expr = {
    require(conjuncts.nonEmpty, "a conjunction of nothing")
    def join(from: Int, until: Int): Expr =
      if (until - from == 1) conjuncts(from)
      else {
        val mid = (from + until) >>> 1
        BinOpExpr("AND", join(from, mid), join(mid, until), DataType.BooleanType)
      }
    join(0, conjuncts.size)
  }
}
