package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, DataType, Field, Schema}
import com.transformer.fuzz.MetaQueryGen.MetaCase
import com.transformer.fuzz.Rng
import com.transformer.sql.exec.{NestedLoopGuardException, PhysicalPlanner}
import com.transformer.sql.plan._

/** A restriction never looks bigger (CERT, ICSE 2024): a query made more
  * restrictive, so that it returns a sub-multiset of the rows it did, never
  * gets a larger row estimate, and the nested-loop guard never refuses it
  * where it accepts the query.
  *
  * A restriction ([[Restriction]]) is made on the bound plan: a WHERE
  * conjunct added, a LIMIT over the whole, a LEFT or RIGHT join made INNER,
  * a FULL join made LEFT or RIGHT. Two rules hold ([[check]]):
  *
  *   - `LogicalPlanCardinality.estimate` of the restriction's bound plan is
  *     no larger than the query's, but for a LEFT or RIGHT join made INNER:
  *     the estimator sizes an outer join by its preserved side and an inner
  *     one by its larger side, to pick a build side, which plans/testing/16's
  *     decision 3 keeps;
  *   - the planner's nested-loop guard ([[PhysicalPlanner.plan]] throwing
  *     [[NestedLoopGuardException]]) does not refuse the restriction made in
  *     the query's optimized plan, its joins in the order the optimizer chose
  *     for the query, where it accepts the query. Decision 3 made the guard
  *     size a join on an equality no smaller as an outer join than as the
  *     inner join it holds, which it did not.
  *
  * Two ways a restriction still looks bigger are reported for the user, held
  * by plans/testing/16, not failed: the optimizer pushes a filter under a
  * join, where the estimate of an inner join (its larger side) may not read
  * it, so a FULL join made LEFT, which lets a filter through, can be
  * estimated larger; and the guard refuses a restriction the optimizer then
  * orders anew, a key joined before a pairing counting their product.
  *
  * The tables are [[SizedView]]s, which report a row count and are never
  * read: planning reads a count, and nothing runs. Sizes are drawn across
  * the guard's thresholds (5,000 rows a side, 25 million pairs), so the
  * guard's bound is held at the sizes where it decides. A CTE is inlined
  * where it is read rather than materialized, which would run it.
  */
object Cert {

  /** A view of `schema` that holds `rows` rows by its count alone. */
  final class SizedView(val schema: Schema, rows: Long) extends CatalogView {
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] =
      throw new IllegalStateException("a sized view stands for a table no query reads")
    override def exactRowCount: Option[Long] = Some(rows)
  }

  /** The row counts a sized table draws: either side of the guard's 5,000
    * rows and of the square roots of its 25 million pairs. */
  val Sizes: Seq[Long] = Seq(0L, 1L, 3L, 10L, 50L, 100L, 1000L, 4000L, 5001L, 10000L, 100000L, 300000L, 1000000L)

  sealed abstract class Restriction(val name: String) { override def toString: String = name }
  object Restriction {
    final case class Conjunct(predicate: String) extends Restriction("a WHERE conjunct")
    final case class Limit(n: Long) extends Restriction("a LIMIT")
    final case class Inner(join: Int, from: JoinKind) extends Restriction(s"a ${keyword(from)} join made INNER")
    final case class FullToOuter(join: Int, to: JoinKind) extends Restriction(s"a FULL join made ${keyword(to)}")
  }

  private def keyword(k: JoinKind): String = k match {
    case JoinKind.Inner => "INNER"
    case JoinKind.Left => "LEFT"
    case JoinKind.Right => "RIGHT"
    case JoinKind.Full => "FULL"
  }

  /** How one restriction compared: the query's estimate and the
    * restriction's, both of the bound plans; the optimized plans' estimates;
    * whether the guard refused the query, the restriction, and the
    * restriction made in the query's own optimized plan, its joins in the
    * order the optimizer chose for the query. */
  final case class Compared(restriction: Restriction, estimate: Option[Long], restricted: Option[Long],
      optimizedEstimate: Option[Long], optimizedRestricted: Option[Long], refused: Boolean,
      restrictedRefused: Boolean, refusedInOrder: Boolean) {
    /** The guard refuses the restriction only in the order the optimizer
      * chooses for it, and not in the query's own (held, plans/testing/16). */
    def refusedOnlyReordered: Boolean = restrictedRefused && !refused && !refusedInOrder
  }

  /** The plan of `sql` over `catalog` as the planner sees it, every CTE
    * inlined, or None where it does not bind. */
  def bound(sql: String, catalog: Catalog): Option[LogicalPlan] =
    try Some(PlanRoundTrip.inlined(LogicalBuilder.build(sql, catalog)))
    catch { case _: IllegalArgumentException => None }

  /** Each restriction of `plan` `rng` draws: a WHERE conjunct, a LIMIT, and
    * each outer join's restriction. */
  def restrictions(plan: LogicalPlan, rng: Rng): Vector[(Restriction, LogicalPlan)] = {
    val out = Vector.newBuilder[(Restriction, LogicalPlan)]
    conjunct(plan, rng).foreach(out += _)
    val n = rng.oneOf(Seq(0L, 1L, 10L, 1000L, 100000L))
    out += ((Restriction.Limit(n), LogicalLimit(plan, n)))
    joins(plan).zipWithIndex.foreach { case (j, i) =>
      j.kind match {
        case JoinKind.Left | JoinKind.Right => out += ((Restriction.Inner(i, j.kind), rekind(plan, i, JoinKind.Inner)))
        case JoinKind.Full =>
          val to = if (rng.bool()) JoinKind.Left else JoinKind.Right
          out += ((Restriction.FullToOuter(i, to), rekind(plan, i, to)))
        case JoinKind.Inner => ()
      }
    }
    out.result()
  }

  /** The joins of `plan`, pre-order. */
  def joins(plan: LogicalPlan): Vector[LogicalJoin] = (plan match {
    case j: LogicalJoin => Vector(j)
    case _ => Vector.empty
  }) ++ plan.children.flatMap(joins)

  private def rekind(plan: LogicalPlan, at: Int, kind: JoinKind): LogicalPlan = {
    var i = -1
    def go(p: LogicalPlan): LogicalPlan = {
      val here = p match {
        case j: LogicalJoin => i += 1; if (i == at) Some(j) else None
        case _ => None
      }
      val rebuilt = p.withChildren(p.children.map(go))
      here.fold(rebuilt)(_ => rebuilt.asInstanceOf[LogicalJoin].copy(kind = kind))
    }
    go(plan)
  }

  /** A conjunct ANDed into the WHERE of `plan`'s main query (its first union
    * arm's), over the columns its FROM clause makes: two columns of one type
    * equal, which can make a join keyed, or one IS NOT NULL. None where the
    * plan has no FROM clause to filter. */
  private def conjunct(plan: LogicalPlan, rng: Rng): Option[(Restriction, LogicalPlan)] = {
    def from(p: LogicalPlan): Option[LogicalPlan => LogicalPlan] = p match {
      case LogicalLimit(c, n) => from(c).map(f => (x: LogicalPlan) => LogicalLimit(f(x), n))
      case LogicalOffset(c, n) => from(c).map(f => (x: LogicalPlan) => LogicalOffset(f(x), n))
      case LogicalProject(c, ps) => from(c).map(f => (x: LogicalPlan) => LogicalProject(f(x), ps))
      case LogicalSort(c, ks) => from(c).map(f => (x: LogicalPlan) => LogicalSort(f(x), ks))
      case LogicalDistinct(c) => from(c).map(f => (x: LogicalPlan) => LogicalDistinct(f(x)))
      case LogicalWindow(c, ws) => from(c).map(f => (x: LogicalPlan) => LogicalWindow(f(x), ws))
      case LogicalFilter(a: LogicalAggregate, h) => from(a).map(f => (x: LogicalPlan) => LogicalFilter(f(x), h))
      case LogicalAggregate(c, ks, as) => from(c).map(f => (x: LogicalPlan) => LogicalAggregate(f(x), ks, as))
      case LogicalUnion(l, r, all) => from(l).map(f => (x: LogicalPlan) => LogicalUnion(f(x), r, all))
      case _: LogicalFilter | _: LogicalJoin | _: LogicalScan => Some((x: LogicalPlan) => x)
    }
    def at(p: LogicalPlan): LogicalPlan = p match {
      case LogicalLimit(c, _) => at(c)
      case LogicalOffset(c, _) => at(c)
      case LogicalProject(c, _) => at(c)
      case LogicalSort(c, _) => at(c)
      case LogicalDistinct(c) => at(c)
      case LogicalWindow(c, _) => at(c)
      case LogicalFilter(a: LogicalAggregate, _) => at(a)
      case LogicalAggregate(c, _, _) => at(c)
      case LogicalUnion(l, _, _) => at(l)
      case other => other
    }
    from(plan).flatMap { rebuild =>
      val target = at(plan)
      val schema = target.outputSchema
      if (schema.length == 0 || readsNoTable(target)) None
      else {
        val cols = schema.fields.zipWithIndex.map { case (f, i) => ColRefExpr(i, f.name, f.dataType): Expr }
        val pairs = for { a <- cols; b <- cols if a != b && a.dataType == b.dataType && a.dataType != DataType.NullType } yield (a, b)
        val c: Expr =
          if (pairs.nonEmpty && rng.bool(0.6)) { val (a, b) = rng.oneOf(pairs); BinOpExpr("=", a, b, DataType.BooleanType) }
          else IsNullExpr(rng.oneOf(cols), negated = true)
        val filtered = target match {
          case LogicalFilter(child, p) => LogicalFilter(child, BinOpExpr("AND", p, c, DataType.BooleanType))
          case other => LogicalFilter(other, c)
        }
        Some((Restriction.Conjunct(PlanSql.expression(c, i => schema.fields(i).name)), rebuild(filtered)))
      }
    }
  }

  private def readsNoTable(p: LogicalPlan): Boolean = p match {
    case s: LogicalScan => LogicalBuilder.readsNoTable(s)
    case _ => false
  }

  /** Whether the nested-loop guard refuses `optimized`, a plan as the
    * optimizer leaves it. */
  def refuses(optimized: LogicalPlan): Boolean =
    try { PhysicalPlanner.plan(optimized); false }
    catch { case _: NestedLoopGuardException => true }

  /** The k-th join of `plan` that is not INNER, pre-order. The optimizer
    * reorders inner joins only, so the outer joins of a bound plan and of its
    * optimized plan come in one order. */
  private def outerIndex(plan: LogicalPlan, join: Int): Int =
    joins(plan).take(join).count(_.kind != JoinKind.Inner)

  private def rekindOuter(plan: LogicalPlan, k: Int, kind: JoinKind): LogicalPlan = {
    val at = joins(plan).zipWithIndex.filter(_._1.kind != JoinKind.Inner).map(_._2).lift(k)
    at.fold(plan)(rekind(plan, _, kind))
  }

  /** Hold each restriction of `plan` to the two rules; an
    * [[AssertionError]] says which broke.
    *
    * The estimates compared are the bound plans', before the optimizer runs:
    * it can make a restriction's estimate the larger, pushing a filter under
    * a join to a side the join's estimate (its larger side) does not read
    * (held, plans/testing/16), so the optimized plans' are only reported.
    *
    * The guard is held in the query's own join order: the restriction made
    * in the query's optimized plan, its joins where the optimizer put them,
    * must not be refused where the query is not. A restriction the guard
    * refuses only once the optimizer has ordered its joins anew (an outer
    * join made INNER joins a run, a conjunct keys a table) is reported, not
    * failed: the guard sizes a join on an equality by its larger side,
    * whatever pairings come before it, so a key joined before a pairing
    * counts their product where joined after it does not (held,
    * plans/testing/16). */
  def check(plan: LogicalPlan, rng: Rng, describe: => String): Vector[Compared] = {
    val optimized = LogicalOptimizer.optimize(plan)
    val refused = refuses(optimized)
    val estimate = LogicalPlanCardinality.estimate(plan)
    val optimizedEstimate = LogicalPlanCardinality.estimate(optimized)
    restrictions(plan, rng).map { case (r, restricted) =>
      val rOptimized = LogicalOptimizer.optimize(restricted)
      val rRefused = refuses(rOptimized)
      def fail(why: String): Nothing =
        throw new AssertionError(s"[cert] $r $why\n  $describe\n  plan:\n${tree(plan)}\n  restricted:\n${tree(restricted)}")
      // The same restriction made in the query's optimized plan, its joins in
      // the order the optimizer chose for the query. A conjunct or a LIMIT
      // there changes no join, so only a join's kind is made there.
      val refusedInOrder = rRefused && !refused && (r match {
        case Restriction.Inner(join, _) => refuses(rekindOuter(optimized, outerIndex(plan, join), JoinKind.Inner))
        case Restriction.FullToOuter(join, to) => refuses(rekindOuter(optimized, outerIndex(plan, join), to))
        case _ => false
      })
      if (refusedInOrder) fail("is refused by the nested-loop guard in the query's own join order, where the query is not")
      val rEstimate = LogicalPlanCardinality.estimate(restricted)
      (estimate, rEstimate) match {
        case (Some(a), Some(b)) if b > a && !r.isInstanceOf[Restriction.Inner] =>
          fail(s"is estimated at $b rows, the query at $a")
        case _ => ()
      }
      Compared(r, estimate, rEstimate, optimizedEstimate, LogicalPlanCardinality.estimate(rOptimized), refused,
        rRefused, refusedInOrder)
    }
  }

  // ---------------------------------------------------------------------------
  // The cases
  // ---------------------------------------------------------------------------

  /** `mc`'s relations as sized views of the sizes `rng` draws. */
  def sizedCatalog(mc: MetaCase, rng: Rng): (Catalog, Map[String, Long]) = {
    val c = new Catalog
    val sizes = mc.env.relations.map(r => r.name -> rng.oneOf(Sizes)).toMap
    mc.env.relations.foreach(r => c.register(r.name, new SizedView(r.schema, sizes(r.name))))
    (c, sizes)
  }

  /** A FROM of two to five tables of `(k, v)`, each joined by any kind on an
    * equality with a table before it, on a comparison that is none, or by
    * CROSS JOIN: the shapes the guard sizes, a pairing over or under a join
    * on a key. Now and then its first item is a CTE `x` over a join of two
    * more tables, grouped, unioned with one of them or made DISTINCT, which
    * the guard sizes through the operator over the join. */
  final case class JoinShape(sizes: Map[String, Long], sql: String) {
    def catalog: Catalog = {
      val c = new Catalog
      sizes.foreach { case (name, n) =>
        c.register(name, new SizedView(Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.IntType))), n))
      }
      c
    }
    override def toString: String = s"JoinShape($sql, sizes ${sizes.toSeq.sorted.mkString(", ")})"
  }

  def genShape(rng: Rng): JoinShape = {
    val n = rng.between(2, 5)
    val names = (0 until n).map(i => s"t$i") ++ Seq("u0", "u1")
    val sizes = names.map(name => name -> rng.oneOf(Sizes)).toMap
    val cte: Option[String] =
      if (!rng.bool(0.35)) None
      else {
        val join = s"FROM u0 ${kind(rng)} JOIN u1 ON u0.k = u1.k"
        Some(rng.weighted[() => String](
          (2, () => s"SELECT u0.k AS k, COUNT(*) AS v $join GROUP BY u0.k"),
          (1, () => s"SELECT u0.k AS k, u1.v AS v $join UNION ALL SELECT u1.k AS k, u1.v AS v FROM u1"),
          (1, () => s"SELECT DISTINCT u0.k AS k, u1.v AS v $join"))())
      }
    val root = if (cte.isDefined) "x" else "t0"
    def item(i: Int): String = if (i == 0) root else s"t$i"
    val joins = (1 until n).map { i =>
      val j = item(rng.between(0, i - 1))
      rng.weighted[() => String](
        (2, () => s" CROSS JOIN t$i"),
        (4, () => s" ${kind(rng)} JOIN t$i ON $j.k = t$i.k"),
        (2, () => s" ${kind(rng)} JOIN t$i ON $j.v < t$i.v"))()
    }
    val select = rng.weighted((3, s"$root.k AS a"), (1, "COUNT(*) AS n"))
    JoinShape(sizes, s"${cte.fold("")(c => s"WITH x AS ($c) ")}SELECT $select FROM $root${joins.mkString}")
  }

  private def kind(rng: Rng): String = rng.weighted((2, "INNER"), (3, "LEFT"), (2, "RIGHT"), (2, "FULL"))

  private def tree(p: LogicalPlan): String = PlanTree.lines(p).map("    " + _).mkString("\n")
}
