package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, DataType, ExecutedQuery, ExecutionOptions}
import com.transformer.core.metrics.MetricsNode
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.{Rng, RowOracle, Shrinker}
import com.transformer.sql.exec.{CteResolver, ExchangeExec, HashJoinExec, PhysicalPlan, PhysicalPlanner}
import com.transformer.sql.plan._

import scala.collection.mutable

/** Forced plans (DQP, SIGMOD 2024; Velox's join fuzzer): every plan the
  * engine can be made to choose for one query gives one multiset.
  *
  * Two levers force a plan, neither a seam in the engine:
  *
  *   - '''Join orders.''' The leading run of inner joins of a query's FROM
  *     (its first table and each inner join after it, up to the first outer
  *     join, which stays where it is as a barrier) is written in another order
  *     ([[permuted]]), each of its conditions on the first join that has
  *     every table it reads and `ON TRUE` where none does, every column keeping
  *     its alias. Run with the optimizer off, the joins run in the written
  *     order, cross products first where they come first; run with it on,
  *     `JoinOrder` chooses from that written order.
  *   - '''Strategies, by spelling.''' The plan the optimizer makes of a query
  *     is respelled at every join ([[respelled]]) and printed ([[PlanSql]]),
  *     then run as printed with the optimizer off: an equality between the
  *     sides as written (the collapsing hash join, with the long-key codec for
  *     one integer key that is a bare column), its sides as computed keys
  *     `COALESCE(k, k)` (the packed or object codecs), each written twice (two
  *     keys), or the whole condition as `CASE WHEN p THEN TRUE ELSE FALSE END`,
  *     which hides every key, so the join pairs row by row; with spill on a
  *     keyed join takes the grace hash path; and with the sides swapped at
  *     every join, the kind flipped and the columns put back in order above.
  *     In a JVM whose sharding gates are open, every keyed join is per-shard
  *     and the spelling with no key is not: a sharded join against an
  *     unsharded one in one JVM, which the class-load gates otherwise rule
  *     out.
  *
  * Both compare with the query as written, run with the optimizer on, under
  * the rule [[RelEngine.answer]] holds every layout to (decision 1 of
  * plans/testing/15): one multiset, or both failing on their data, but where
  * a WHERE or ON condition that can fail is there to move. Only a query whose
  * output multiset no row order changes is checked
  * ([[MetaQuery.isLayoutInvariant]]): another join order or path emits rows
  * in another order, which a tie-sensitive window reads.
  */
object ForcedPlans {

  // ---------------------------------------------------------------------------
  // Join orders
  // ---------------------------------------------------------------------------

  private val TrueLit: Expr = LitExpr(true, DataType.BooleanType)

  /** How many tables the leading run of inner joins of `from` holds: its
    * first table and each inner join after it, up to the first outer one. */
  def runLength(from: FromClause): Int = 1 + from.joins.takeWhile(_.kind == JoinKind.Inner).length

  /** `q` with the leading run of its FROM written in `order`, a permutation
    * of `0 until runLength(q.from)`: each of the run's conditions on the first
    * join that has every table it reads, `ON TRUE` where none does, and the
    * joins after the run as written. A comma list's or `CROSS JOIN`s'
    * conditions, which the WHERE holds, move to the ONs too. */
  def permuted(q: MetaQuery, order: IndexedSeq[Int]): MetaQuery = {
    val from = q.from
    val n = runLength(from)
    require(order.sorted == (0 until n), s"$order is no order of $n tables")
    val leaves = (from.root +: from.joins.map(_.leaf)).take(n)
    val conditions = from.joins.take(n - 1).flatMap(j => conjuncts(j.on)).filterNot(_ == TrueLit)
    val position = leaves.map(_.alias).zipWithIndex.toMap
    val reads = conditions.map(c => aliases(c).flatMap(position.get))
    val placed = mutable.BitSet.empty
    var joined = Set(order.head)
    val joins = order.tail.map { t =>
      joined += t
      val here = conditions.indices.filter(i => !placed(i) && reads(i).subsetOf(joined))
      here.foreach(placed += _)
      val on = if (here.isEmpty) TrueLit else here.map(conditions).reduceLeft((a, b) => BinOpExpr("AND", a, b, DataType.BooleanType))
      JoinItem(JoinKind.Inner, leaves(t), on)
    }
    q.copy(from = FromClause(leaves(order.head), (joins ++ from.joins.drop(n - 1)).toVector, JoinStyle.On))
  }

  /** At most how many rows the written order of `order` pairs before its
    * conditions keep any: the product of the sizes of each prefix's tables
    * that no placed condition joins, which a run with the optimizer off pairs
    * whole. An equality is taken to keep half the pairs it sees. */
  def pairsBound(q: MetaQuery, order: IndexedSeq[Int], sizes: String => Int): Double = {
    val from = q.from
    val n = runLength(from)
    val leaves = (from.root +: from.joins.map(_.leaf)).take(n)
    val written = permuted(q, order)
    var rows = math.max(1, sizes(leaves(order.head).relName)).toDouble
    var most = rows
    written.from.joins.take(n - 1).foreach { j =>
      val equality = conjuncts(j.on).exists { case BinOpExpr("=", _, _, _) => true; case _ => false }
      rows = rows * math.max(1, sizes(j.leaf.relName)) / (if (equality) 2.0 else 1.0)
      most = math.max(most, rows)
    }
    most
  }

  /** The orders a run of `n` tables is written in: every one of four tables
    * or fewer, and a sample of `sample` beyond, the written order first. */
  def orders(n: Int, rng: Rng, sample: Int): Vector[IndexedSeq[Int]] =
    if (n <= 4) (0 until n).permutations.toVector.sortBy(o => if (o == (0 until n)) 0 else 1)
    else {
      val out = mutable.LinkedHashSet[IndexedSeq[Int]](0 until n)
      var tries = 0
      while (out.size < sample && tries < sample * 20) {
        val xs = mutable.ArrayBuffer.range(0, n)
        var i = n - 1
        while (i > 0) { val j = rng.nextInt(i + 1); val t = xs(i); xs(i) = xs(j); xs(j) = t; i -= 1 }
        out += xs.toIndexedSeq
        tries += 1
      }
      out.toVector
    }

  private def conjuncts(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => conjuncts(l) ++ conjuncts(r)
    case other => Seq(other)
  }

  /** The aliases a generated expression reads: its column references carry
    * `alias.column` as their name. */
  private def aliases(e: Expr): Set[String] = e match {
    case ColRefExpr(_, name, _) => Set(name.takeWhile(_ != '.'))
    case other => Shrinker.childrenOf(other).flatMap(aliases).toSet
  }

  /** What one written order gave: the order, whether the optimizer ran, and
    * `held`, `moved` (one failed on its data where the other answered, a
    * condition there to move) or `refused` (the nested-loop guard, past which
    * a written order may pair). */
  final case class OrderRun(order: IndexedSeq[Int], optimized: Boolean, kind: String)

  /** Check `mc`'s leading run in each of `orders`, with the optimizer off
    * and on, against the query as written. An [[AssertionError]] says which
    * order answered otherwise. Empty when the query does not bind or is not
    * layout-invariant. */
  def checkOrders(mc: MetaCase, orders: Seq[IndexedSeq[Int]]): Vector[OrderRun] = {
    val q = mc.query
    if (!q.isLayoutInvariant) return Vector.empty
    val catalog = mc.env.catalog()
    val sql = q.renderBody
    val base =
      try RelEngine.answer(catalog, sql, ExecutionOptions.Default)
      catch { case _: IllegalArgumentException => return Vector.empty }
    orders.toVector.flatMap { order =>
      val written = permuted(q, order).renderBody
      Seq(false, true).map { optimized =>
        val other =
          try Right(if (optimized) RelEngine.answer(catalog, written, ExecutionOptions.Default)
            else answerAsWritten(catalog, written, ExecutionOptions.Default))
          catch {
            case e: com.transformer.sql.exec.NestedLoopGuardException => Left(e.getMessage)
            case e: IllegalArgumentException => throw new AssertionError(
              s"[forced-plans] the written order ${order.mkString(",")} does not bind: ${e.getMessage}\n  query:   $sql\n" +
                s"  written: $written", e)
          }
        other match {
          case Left(_) => OrderRun(order, optimized, "refused")
          case Right(answer) =>
            val label = s"the order ${order.mkString(",")} with the optimizer ${if (optimized) "on" else "off"}"
            OrderRun(order, optimized, agree(base, answer, q.movableFailure, label, sql, written))
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Strategies
  // ---------------------------------------------------------------------------

  /** How a join's condition is written. */
  sealed abstract class Spelling(val name: String) { override def toString: String = name }
  object Spelling {
    /** As the query wrote it. */
    case object Key extends Spelling("key")
    /** Each equality between the sides over `COALESCE(k, k)` on each. */
    case object ComputedKey extends Spelling("computed key")
    /** Each equality between the sides written twice. */
    case object TwoKeys extends Spelling("two keys")
    /** The condition under `CASE WHEN p THEN TRUE ELSE FALSE END`. */
    case object NoKey extends Spelling("no key")
  }

  /** A spelling, whether spill is on, and whether every join's sides are
    * swapped. */
  final case class Strategy(spelling: Spelling, spill: Boolean, swapped: Boolean) {
    override def toString: String =
      s"$spelling${if (spill) ", spill on" else ""}${if (swapped) ", sides swapped" else ""}"
    def options: ExecutionOptions =
      if (spill) ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L)) else ExecutionOptions.Default
  }

  /** The six spellings and spill settings each case runs. */
  val Strategies: Seq[(Spelling, Boolean)] = Seq(
    (Spelling.Key, false), (Spelling.ComputedKey, false), (Spelling.TwoKeys, false), (Spelling.NoKey, false),
    (Spelling.Key, true), (Spelling.ComputedKey, true))

  /** `built`, as the optimizer leaves it ([[CteResolver.planned]] and each
    * plan optimized, as [[PlanRoundTrip.optimizedSql]] prints it), with every
    * join respelled by `s`. */
  def respelled(built: BuiltQuery, s: Strategy): BuiltQuery = {
    val planned = CteResolver.planned(built)
    BuiltQuery(respell(LogicalOptimizer.optimize(planned.main), s),
      planned.ctes.map(c => c.copy(body = respell(LogicalOptimizer.optimize(c.body), s))))
  }

  private val True: Expr = LitExpr(true, DataType.BooleanType)
  private val False: Expr = LitExpr(false, DataType.BooleanType)

  private def respell(p: LogicalPlan, s: Strategy): LogicalPlan = p match {
    case LogicalJoin(l, r, cond, kind) =>
      val (nl, nr) = (respell(l, s), respell(r, s))
      val width = l.outputSchema.length
      val c = spell(cond, width, s.spelling)
      if (!s.swapped) LogicalJoin(nl, nr, c, kind)
      else {
        val rw = r.outputSchema.length
        val joined = LogicalJoin(nr, nl, JoinSideAnalysis.remapColumns(c, i => if (i < width) i + rw else i - width), flip(kind))
        val fields = l.outputSchema.fields ++ r.outputSchema.fields
        LogicalProject(joined, fields.indices.map { i =>
          (ColRefExpr(if (i < width) rw + i else i - width, fields(i).name, fields(i).dataType): Expr, fields(i).name)
        })
      }
    case other => other.withChildren(other.children.map(respell(_, s)))
  }

  private def flip(k: JoinKind): JoinKind = k match {
    case JoinKind.Left => JoinKind.Right
    case JoinKind.Right => JoinKind.Left
    case other => other
  }

  /** Whether `e` is an equality the physical planner hashes a join on: one
    * side reads the left input alone and the other the right. */
  private def isKey(e: Expr, width: Int): Boolean = e match {
    case BinOpExpr("=", a, b, _) =>
      Set(JoinSideAnalysis.sideOf(a, width), JoinSideAnalysis.sideOf(b, width)) == Set(JoinSide.LeftOnly, JoinSide.RightOnly)
    case _ => false
  }

  private def spell(cond: Expr, width: Int, spelling: Spelling): Expr = {
    val parts = conjuncts(cond).filterNot(_ == True)
    def and(es: Seq[Expr]): Expr = if (es.isEmpty) True else JoinSideAnalysis.conjunction(es)
    spelling match {
      case Spelling.Key => cond
      case Spelling.ComputedKey => and(parts.map {
        case e @ BinOpExpr("=", a, b, t) if isKey(e, width) =>
          BinOpExpr("=", FuncExpr("COALESCE", Seq(a, a), a.dataType), FuncExpr("COALESCE", Seq(b, b), b.dataType), t)
        case other => other
      })
      case Spelling.TwoKeys => and(parts ++ parts.filter(isKey(_, width)))
      case Spelling.NoKey => if (parts.isEmpty) cond else CaseExpr(Seq((cond, True)), Some(False), DataType.BooleanType)
    }
  }

  /** Where one join of a physical plan runs, and with which key codec. */
  final case class JoinPath(path: String, codec: Option[String])

  /** The joins of `sql`'s plan as it runs with the optimizer off under
    * `opts`: each one's path, read off the physical plan, and its codec, read
    * off the metrics node the planner stamps it into
    * (`HashJoinExec.IdxKeyCodecPath`), for a join with keys. */
  def joinPaths(catalog: Catalog, sql: String, opts: ExecutionOptions): Vector[JoinPath] = {
    val resolved = CteResolver.resolve(LogicalBuilder.build(sql, catalog), opts)
    val joins = hashJoins(PhysicalPlanner.plan(resolved, opts))
    val codecs = PhysicalPlanner.planWithMetrics(resolved, opts.copy(metricsEnabled = true))._2.toVector
      .flatMap(metricsJoins).map(n => n.counters(HashJoinExec.IdxKeyCodecPath).sum())
    joins.zipWithIndex.map { case (j, i) =>
      val path =
        if (opts.spillEnabled && j.leftKeys.nonEmpty && !(j.leftKeys ++ j.rightKeys).exists(Expr.readsRandom)) "grace hash"
        else if (j.left.isInstanceOf[ExchangeExec] && j.right.isInstanceOf[ExchangeExec]) "per-shard"
        else if (j.leftKeys.isEmpty) "pairing"
        else "collapsing"
      val codec =
        if (j.leftKeys.isEmpty || codecs.length != joins.length) None
        else Some(codecs(i) match {
          case HashJoinExec.KeyPathLong => "long"
          case HashJoinExec.KeyPathPacked => "packed"
          case HashJoinExec.KeyPathSingleObject => "single object"
          case _ => "object"
        })
      JoinPath(path, codec)
    }
  }

  /** The hash joins of `p`, pre-order: a plan's children are the plans its
    * case class holds. */
  private def hashJoins(p: PhysicalPlan): Vector[HashJoinExec] = {
    val here = p match {
      case j: HashJoinExec => Vector(j)
      case _ => Vector.empty
    }
    val children = p match {
      case prod: Product => prod.productIterator.collect { case c: PhysicalPlan => c }.toVector
      case _ => Vector.empty
    }
    here ++ children.flatMap(hashJoins)
  }

  private def metricsJoins(n: MetricsNode): Vector[MetricsNode] =
    (if (n.name == "HashJoinExec") Vector(n) else Vector.empty) ++ n.children.flatMap(metricsJoins)

  /** What one strategy gave: the strategy, `held` or `moved`, and the joins
    * it ran. */
  final case class StrategyRun(strategy: Strategy, kind: String, joins: Vector[JoinPath])

  /** Check `mc` under each of `strategies` against the query as written. An
    * [[AssertionError]] says which strategy answered otherwise. Empty when
    * the query does not bind, joins nothing, is not layout-invariant or its
    * plan does not print. */
  def checkStrategies(mc: MetaCase, strategies: Seq[Strategy]): Vector[StrategyRun] = {
    val q = mc.query
    if (!q.isLayoutInvariant || !q.hasAnyJoin) return Vector.empty
    val catalog = mc.env.catalog()
    val sql = q.renderBody
    val (built, base) =
      try (LogicalBuilder.build(sql, catalog), RelEngine.answer(catalog, sql, ExecutionOptions.Default))
      catch { case _: IllegalArgumentException => return Vector.empty }
    strategies.toVector.flatMap { s =>
      val printed =
        try Some(PlanSql.print(respelled(built, s), catalog))
        catch { case _: PlanSql.UnprintablePlanException => None }
      printed.map { text =>
        val paths =
          try joinPaths(catalog, text, s.options)
          catch {
            case e: IllegalArgumentException => throw new AssertionError(
              s"[forced-plans] the plan respelled as $s does not plan: ${e.getMessage}\n  query:   $sql\n  printed:\n$text", e)
          }
        val answer = answerAsWritten(catalog, text, s.options)
        StrategyRun(s, agree(base, answer, q.movableFailure, s"the plan respelled as $s", sql, text), paths)
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Running and comparing
  // ---------------------------------------------------------------------------

  /** `sql` run with the optimizer off under `opts`, as [[RelEngine.answer]]
    * runs it with the optimizer on: its rows, or the failure on its data it
    * stopped with. */
  def answerAsWritten(catalog: Catalog, sql: String, opts: ExecutionOptions): Either[String, RelEngine.Answer] =
    try {
      val physical = PhysicalPlanner.plan(CteResolver.resolve(LogicalBuilder.build(sql, catalog), opts), opts)
      PhysicalPlanner.preMaterializeExchanges(physical)
      val q = new ExecutedQuery(physical.outputSchema, (0 until physical.numPartitions).map(physical.execute))
      Right(RelEngine.Answer(q.schema, RelEngine.collectRows(q)))
    } catch {
      case scala.util.control.NonFatal(e) if CleanRejection.isDataError(e) => Left(RelEngine.dataMessage(e))
    }

  /** `held` when `answer` is `base`'s, `moved` when one failed on its data
    * and the other answered with a condition there to move (`movable`);
    * otherwise an [[AssertionError]] naming `label`. */
  def agree(base: Either[String, RelEngine.Answer], answer: Either[String, RelEngine.Answer], movable: Boolean,
      label: String, sql: String, other: String): String = {
    def fail(why: String): Nothing =
      throw new AssertionError(s"[forced-plans] $label answers otherwise: $why\n  query: $sql\n  ran:\n" +
        other.split("\n").map("    " + _).mkString("\n"))
    (base, answer) match {
      case (Right(a), Right(b)) =>
        RowOracle.multisetEquals(a.schema, a.rows, b.rows).foreach(fail)
        "held"
      case (Left(_), Left(_)) => "held"
      case _ if movable => "moved"
      case (Left(f), Right(_)) => fail(s"it answers where the query fails on its data ($f)")
      case (Right(_), Left(f)) => fail(s"it fails on its data ($f) where the query answers")
    }
  }
}
