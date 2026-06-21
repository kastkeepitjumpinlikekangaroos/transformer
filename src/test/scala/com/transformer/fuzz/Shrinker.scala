package com.transformer.fuzz

import com.transformer.core.DataType
import com.transformer.sql.plan._

/** Hand-rolled, integrated-style shrinking: given a failing value, produce an
  * `Iterator` of strictly-smaller candidates to try. [[Props]] greedily walks
  * the iterator, replacing the failing value with the first smaller candidate
  * that still fails, until nothing smaller fails — yielding a minimal
  * counterexample.
  *
  * Generic combinators ([[int]], [[long]], [[string]], [[list]]) compose the
  * scalar moves; [[expr]] is the [[Expr]]-tree shrinker used by the parity
  * fuzzer. Every candidate is strictly smaller by [[size]] (node count for
  * trees, magnitude/length for scalars), and [[Props]] additionally rejects any
  * candidate whose size did not decrease — so the shrink loop always terminates.
  */
object Shrinker {

  // ---- scalar combinators -------------------------------------------------

  /** Integers shrink toward 0 by repeated halving. */
  def int(n: Int): Iterator[Int] =
    if (n == 0) Iterator.empty
    else {
      val half = n / 2
      (Iterator(0) ++ (if (half != 0) Iterator(half) else Iterator.empty)).distinct
    }

  def long(n: Long): Iterator[Long] =
    if (n == 0L) Iterator.empty
    else {
      val half = n / 2L
      (Iterator(0L) ++ (if (half != 0L) Iterator(half) else Iterator.empty)).distinct
    }

  /** Strings shrink toward "" by dropping the second half, then the tail char. */
  def string(s: String): Iterator[String] =
    if (s.isEmpty) Iterator.empty
    else (Iterator("", s.substring(0, s.length / 2), s.substring(0, s.length - 1)))
      .filter(_ != s)
      .distinct

  /** Lists shrink by dropping one element (down to `minLen`), then by shrinking
    * individual elements with `shrinkElem`. */
  def list[A](xs: Seq[A], minLen: Int, shrinkElem: A => Iterator[A]): Iterator[Seq[A]] = {
    val drops: Iterator[Seq[A]] =
      if (xs.length <= minLen) Iterator.empty
      else xs.indices.iterator.map(i => xs.patch(i, Nil, 1))
    val shrinks: Iterator[Seq[A]] =
      xs.indices.iterator.flatMap(i => shrinkElem(xs(i)).map(e => xs.updated(i, e)))
    drops ++ shrinks
  }

  // ---- Expr-tree shrinker -------------------------------------------------

  /** Node count, the strict-decrease metric for the shrink loop. */
  def size(e: Expr): Int = 1 + childrenOf(e).iterator.map(size).sum

  /** Strictly-smaller, still-type-correct, still-arity-valid candidates for an
    * `Expr`. Moves, in the order [[Props]] tries them:
    *   1. replace the whole node with the simplest literal of its type, then
    *      with a NULL literal of its type — collapses any subtree to a leaf and
    *      surfaces NULL-handling minimal cases;
    *   2. replace the node with one of its same-typed children — collapses
    *      `f(g(x))` toward `x`, the single most effective structural move;
    *   3. node-specific element drops (CASE branches, IN-list items, ELSE,
    *      variadic COALESCE/CONCAT args) down to the arity floor;
    *   4. shrink one child in place (integrated recursion), keeping the child's
    *      declared type so the rebuilt parent stays valid;
    *   5. for a literal, shrink its magnitude toward 0 / "".
    */
  def expr(e: Expr): Iterator[Expr] = {
    val replaceWhole: Iterator[Expr] = e match {
      case LitExpr(_, _) => Iterator.empty // covered by magnitude shrink below
      case _ => Iterator(simplestLit(e.dataType), LitExpr(null, e.dataType))
    }

    val collapseToChild: Iterator[Expr] =
      childrenOf(e).iterator.filter(_.dataType == e.dataType)

    val structuralDrops: Iterator[Expr] = e match {
      case InListExpr(child, items, neg) if items.length > 1 =>
        items.indices.iterator.map(i => InListExpr(child, items.patch(i, Nil, 1), neg))
      case CaseExpr(branches, elseExpr, dt) =>
        val dropBranch =
          if (branches.length > 1)
            branches.indices.iterator.map(i => CaseExpr(branches.patch(i, Nil, 1), elseExpr, dt))
          else Iterator.empty
        val dropElse = if (elseExpr.isDefined) Iterator(CaseExpr(branches, None, dt)) else Iterator.empty
        dropBranch ++ dropElse
      case FuncExpr(name, args, dt) if isVariadic(name) && args.length > 1 =>
        args.indices.iterator.map(i => FuncExpr(name, args.patch(i, Nil, 1), dt))
      case _ => Iterator.empty
    }

    val shrinkOneChild: Iterator[Expr] = {
      val kids = childrenOf(e)
      kids.indices.iterator.flatMap { i =>
        expr(kids(i)).map(smaller => rebuild(e, kids.updated(i, smaller)))
      }
    }

    val magnitude: Iterator[Expr] = e match {
      case LitExpr(v, dt) => shrinkLitValue(v, dt).map(nv => LitExpr(nv, dt))
      case _ => Iterator.empty
    }

    // Keep only strictly-smaller candidates under the well-founded order
    // (node count, then total literal magnitude). Structural moves drop the
    // node count; a literal magnitude shrink keeps the count but lowers the
    // magnitude — both strictly decrease the pair, so the shrink loop
    // terminates.
    (replaceWhole ++ collapseToChild ++ structuralDrops ++ shrinkOneChild ++ magnitude)
      .filter { c =>
        val sc = size(c)
        val se = size(e)
        sc < se || (sc == se && magnitudeOf(c) < magnitudeOf(e))
      }
  }

  /** Total literal magnitude in a tree — the tie-breaker that lets a same-size
    * literal shrink (`42 -> 0`, `"abc" -> ""`) still count as strictly smaller. */
  private def magnitudeOf(e: Expr): Long = e match {
    case LitExpr(v, _) => litMagnitude(v)
    case _ => childrenOf(e).iterator.map(magnitudeOf).sum
  }

  private def litMagnitude(v: Any): Long = v match {
    case null => 0L
    case b: Boolean => if (b) 1L else 0L
    case i: Int => math.abs(i.toLong)
    case l: Long => if (l == Long.MinValue) Long.MaxValue else math.abs(l)
    case f: Float => if (f == 0.0f) 0L else 1L
    case d: Double => if (d == 0.0) 0L else 1L
    case s: String => s.length.toLong
    case _ => 0L
  }

  private def isVariadic(name: String): Boolean = name.toUpperCase match {
    case "COALESCE" | "CONCAT" => true
    case _ => false
  }

  /** The simplest non-null literal of a type — the collapse target. */
  private def simplestLit(dt: DataType): LitExpr = dt match {
    case DataType.IntType => LitExpr(0, dt)
    case DataType.LongType => LitExpr(0L, dt)
    case DataType.FloatType => LitExpr(0.0f, dt)
    case DataType.DoubleType => LitExpr(0.0, dt)
    case DataType.BooleanType => LitExpr(false, dt)
    case DataType.StringType => LitExpr("", dt)
    case _ => LitExpr(null, dt)
  }

  private def shrinkLitValue(v: Any, dt: DataType): Iterator[Any] = v match {
    case null => Iterator.empty
    case i: Int => int(i).map(x => x: Any)
    case l: Long => long(l).map(x => x: Any)
    case f: Float => if (f == 0.0f) Iterator.empty else Iterator(0.0f: Any)
    case d: Double => if (d == 0.0) Iterator.empty else Iterator(0.0: Any)
    case b: Boolean => if (b) Iterator(false: Any) else Iterator.empty
    case s: String => string(s).map(x => x: Any)
    case _ => Iterator.empty
  }

  /** Immediate `Expr` children, flattened left-to-right. Paired with
    * [[rebuild]] so the recursion can swap one child and reassemble the node. */
  private def childrenOf(e: Expr): Seq[Expr] = e match {
    case LitExpr(_, _) => Nil
    case ColRefExpr(_, _, _) => Nil
    case CastExpr(c, _) => Seq(c)
    case UnaryOpExpr(_, c, _) => Seq(c)
    case BinOpExpr(_, l, r, _) => Seq(l, r)
    case FuncExpr(_, args, _) => args
    case CaseExpr(branches, elseExpr, _) =>
      branches.flatMap { case (c, v) => Seq(c, v) } ++ elseExpr.toSeq
    case IsNullExpr(c, _) => Seq(c)
    case InListExpr(c, items, _) => c +: items
    case LikeExpr(c, p, _) => Seq(c, p)
  }

  /** Reassemble `e` from a new child list of the same length and shape as
    * [[childrenOf]] returned. */
  private def rebuild(e: Expr, kids: Seq[Expr]): Expr = e match {
    case LitExpr(_, _) => e
    case ColRefExpr(_, _, _) => e
    case CastExpr(_, t) => CastExpr(kids.head, t)
    case UnaryOpExpr(op, _, dt) => UnaryOpExpr(op, kids.head, dt)
    case BinOpExpr(op, _, _, dt) => BinOpExpr(op, kids(0), kids(1), dt)
    case FuncExpr(name, _, dt) => FuncExpr(name, kids, dt)
    case CaseExpr(branches, elseExpr, dt) =>
      val nPaired = branches.length * 2
      val paired = kids.take(nPaired).grouped(2).map(p => (p(0), p(1))).toSeq
      val newElse = if (elseExpr.isDefined) Some(kids(nPaired)) else None
      CaseExpr(paired, newElse, dt)
    case IsNullExpr(_, neg) => IsNullExpr(kids.head, neg)
    case InListExpr(_, items, neg) => InListExpr(kids.head, kids.drop(1).take(items.length), neg)
    case LikeExpr(_, _, neg) => LikeExpr(kids(0), kids(1), neg)
  }
}
