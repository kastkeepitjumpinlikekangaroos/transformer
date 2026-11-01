package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen.MetaCase
import com.transformer.fuzz.QueryGen.QueryCase
import com.transformer.fuzz.oracle.{PlanRoundTrip, RelEngine}
import com.transformer.sql.exec.{CteResolver, PhysicalPlanner}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import java.util.Locale
import scala.collection.mutable

/** Every query the generators write prints as SQL that binds back to its own
  * plan ([[PlanRoundTrip]]): the single-table queries of [[QueryGen]], the
  * joins, windows, CTEs and unions of [[MetaQueryGen]], and the frontend
  * fuzzer's texts and spellings ([[SqlTextGen]]), whose shapes no planner
  * test writes. A query that binds must round trip; the printed text must
  * answer as the query does, and so must the optimized plan, printed and run
  * with the optimizer off.
  *
  * The guards say the corpus is not vacuous: it reaches every plan node and
  * every expression the binder makes but two, and the printer writes nearly
  * every plan it meets.
  */
class PlanRoundTripFuzzTest {

  import PlanRoundTripFuzzTest._

  @Test def fuzzSingleTableQueries(): Unit =
    Props.forAll[QueryCase](
      name = "plan-roundtrip-query",
      gen = QueryGen.generate,
      shrink = Shrinker.queryCase,
      count = Props.seedCountOr(DefaultSeeds)
    ) { qc => roundTrip(qc.sql, queryCatalog(qc)); () }

  @Test def fuzzMultiRelationQueries(): Unit =
    Props.forAll[MetaCase](
      name = "plan-roundtrip-meta",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultSeeds)
    ) { mc => roundTrip(mc.bodySql, mc.env.catalog()); () }

  @Test def fuzzFrontendTexts(): Unit =
    Props.forAll[SqlTextGen.TextCase](
      name = "plan-roundtrip-text",
      gen = SqlTextGen.genTextCase,
      shrink = Shrinker.textCase,
      count = Props.seedCountOr(DefaultSeeds)
    ) { tc => roundTrip(tc.sql, tc.tables.catalog()); () }

  @Test def fuzzSpellings(): Unit =
    Props.forAll[SqlTextGen.SpellingCase](
      name = "plan-roundtrip-spelling",
      gen = SqlTextGen.genSpellingCase,
      shrink = Shrinker.spellingCase,
      count = Props.seedCountOr(DefaultSeeds)
    ) { sc =>
      val catalog = sc.tables.catalog()
      roundTrip(sc.left, catalog)
      roundTrip(sc.right, catalog)
      ()
    }

  /** Over a fixed sample of every generator, the plans that round trip reach
    * each plan node, each join kind, both unions, and each expression but
    * the two the binder makes only for an operand that reads RAND()
    * (`PlanSqlTest` prints those); and the printer writes all but a sliver
    * of the plans that bind. */
  @Test def theCorpusReachesEveryNodeAndPrintsNearlyEveryPlan(): Unit = {
    val seen = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    val verdicts = mutable.HashMap.empty[String, Int].withDefaultValue(0)
    val why = mutable.LinkedHashSet.empty[String]
    def visit(sql: String, catalog: Catalog): Unit = {
      val v = roundTrip(sql, catalog)
      verdicts(v.kind) += 1
      if (v.why.nonEmpty) why += v.why.take(160)
      if (v.kind == "same") kinds(PlanRoundTrip.inlined(LogicalBuilder.build(sql, catalog))).foreach(k => seen(k) += 1)
    }
    (0 until GuardSeeds).foreach { i =>
      val qc = QueryGen.generate(new Rng(GuardBase + i))
      visit(qc.sql, queryCatalog(qc))
      val mc = MetaQueryGen.generate(new Rng(GuardBase + i))
      visit(mc.bodySql, mc.env.catalog())
      val tc = SqlTextGen.genTextCase(new Rng(GuardBase + i))
      visit(tc.sql, tc.tables.catalog())
    }
    val report = s"verdicts: ${verdicts.toSeq.sortBy(_._1).mkString(", ")}; kinds: ${seen.toSeq.sortBy(_._1).mkString(", ")}" +
      why.map("\n  not printed: " + _).mkString
    println(s"[plan-roundtrip] $report")
    val missing = RequiredKinds.filterNot(seen.contains)
    assertTrue(s"the corpus never reaches ${missing.mkString(", ")}; $report", missing.isEmpty)
    val bound = verdicts("same") + verdicts("unprintable") + verdicts("too deep")
    assertTrue(s"too few plans bind to check: $report", verdicts("same") >= GuardSeeds)
    assertTrue(s"the printer writes too few of the plans that bind: $report",
      verdicts("unprintable") + verdicts("too deep") <= bound / 100)
  }

  /** The comparison sees what a printer that wrote a comparison or a
    * literal wrong would bind to, and sees past what binding chooses afresh. */
  @Test def theComparisonSeesAWrongPrintAndNotItsNames(): Unit = {
    val catalog = SqlTextGen.ReferenceTables.catalog()
    def plan(sql: String) = PlanRoundTrip.normalize(PlanRoundTrip.inlined(LogicalBuilder.build(sql, catalog)))
    val base = plan("SELECT a, b FROM t WHERE a > 1 AND b < 2.5")
    assertNotEquals(base, plan("SELECT a, b FROM t WHERE a >= 1 AND b < 2.5"))
    assertNotEquals(base, plan("SELECT a, b FROM t WHERE a > 1 AND b < CAST(2.5 AS FLOAT)"))
    assertNotEquals(base, plan("SELECT b, a FROM t WHERE a > 1 AND b < 2.5"))
    assertEquals(base, plan("SELECT x.a AS p, x.b AS q FROM t AS x WHERE x.a > 1 AND x.b < 2.5"))
    assertEquals(base, plan("WITH c AS (SELECT * FROM t) SELECT a, b FROM c WHERE a > 1 AND b < 2.5"))
    assertEquals(plan("SELECT -5 AS n"), plan("SELECT CAST('-5' AS INT) AS n"))
  }
}

object PlanRoundTripFuzzTest {

  /** Per property: each case binds, prints, binds again and runs three times. */
  val DefaultSeeds: Int = 300
  val GuardSeeds: Int = 150
  val GuardBase: Long = 900000L

  /** The node and expression kinds the corpus must reach. */
  val RequiredKinds: Seq[String] = Seq(
    "Scan", "Project", "Filter", "Aggregate", "Sort", "Limit", "Offset", "Distinct", "Window",
    "Join Inner", "Join Left", "Join Right", "Join Full", "Union all", "Union distinct",
    "Lit", "ColRef", "Cast", "Negate", "Not", "Arithmetic", "Comparison", "And", "Or", "Concat",
    "Func", "Case", "IsNull", "InList", "Like")

  /** What happened to a query: `same`, `refused`, `unprintable` or `too
    * deep`, and why for the last two. */
  final case class Outcome(kind: String, why: String = "")

  def queryCatalog(qc: QueryCase): Catalog = {
    val c = new Catalog
    c.register(QueryGen.ViewName, qc.dataset.evenPartitions(2, 3))
    c
  }

  /** Round trip `sql` over `catalog`, and compare the answers of the query,
    * its printed plan and its printed optimized plan. A query the engine
    * refuses has nothing to check. */
  def roundTrip(sql: String, catalog: Catalog): Outcome = {
    val verdict =
      try PlanRoundTrip.check(sql, catalog)
      catch { case _: IllegalArgumentException => return Outcome("refused") }
    verdict match {
      case PlanRoundTrip.Same(printed) =>
        if (deterministic(sql)) {
          val expected = answer(sql, catalog, optimize = true)
          compare(sql, printed, expected, answer(printed, catalog, optimize = true))
          PlanRoundTrip.optimizedSql(sql, catalog).foreach { optimized =>
            compare(sql, optimized, expected, answer(optimized, catalog, optimize = false))
          }
        }
        Outcome("same")
      case PlanRoundTrip.Unprintable(why) => Outcome("unprintable", why)
      case PlanRoundTrip.TooDeep(why) => Outcome("too deep", why)
    }
  }

  /** A query whose answer one run fixes: no RAND(), no clock, which each
    * statement reads at its own start (the printed query holds the first
    * run's instant as a literal), and no row count, whose rows a sort's ties
    * leave to the layout. */
  private def deterministic(sql: String): Boolean = {
    val upper = sql.toUpperCase(Locale.ROOT)
    !Seq("RAND", "CURRENT_", "LIMIT", "OFFSET", "FETCH", "TOP ").exists(upper.contains)
  }

  /** The rows `sql` returns, or the class of what it threw on the data. */
  private def answer(sql: String, catalog: Catalog, optimize: Boolean): Either[String, (Schema, Vector[Array[Any]])] =
    try {
      val q =
        if (optimize) RelEngine.execute(catalog, sql, ExecutionOptions.Default)
        else {
          val physical = PhysicalPlanner.plan(
            CteResolver.resolve(LogicalBuilder.build(sql, catalog), ExecutionOptions.Default), ExecutionOptions.Default)
          PhysicalPlanner.preMaterializeExchanges(physical)
          new ExecutedQuery(physical.outputSchema, (0 until physical.numPartitions).map(physical.execute))
        }
      Right((q.schema, RelEngine.collectRows(q)))
    } catch { case e: Exception => Left(e.getClass.getName) }

  private def compare(sql: String, printed: String,
      expected: Either[String, (Schema, Vector[Array[Any]])],
      actual: Either[String, (Schema, Vector[Array[Any]])]): Unit = {
    val problem = (expected, actual) match {
      case (Left(a), Left(b)) => if (a == b) None else Some(s"it fails with $b where the query fails with $a")
      case (Left(a), Right(_)) => Some(s"it runs where the query fails with $a")
      case (Right(_), Left(b)) => Some(s"it fails with $b where the query runs")
      case (Right((schema, rows)), Right((_, other))) => RowOracle.multisetEquals(schema, rows, other)
    }
    problem.foreach(p => throw new AssertionError(s"[plan round trip] the printed plan answers otherwise: $p\n" +
      s"  query:   $sql\n  printed:\n${printed.split("\n").map("    " + _).mkString("\n")}"))
  }

  /** The node and expression kinds `plan` holds. */
  def kinds(plan: LogicalPlan): Set[String] = {
    val out = mutable.HashSet.empty[String]
    def expr(e: Expr): Unit = {
      e match {
        case _: LitExpr => out += "Lit"
        case _: ColRefExpr => out += "ColRef"
        case _: CastExpr => out += "Cast"
        case UnaryOpExpr("-", _, _) => out += "Negate"
        case UnaryOpExpr(_, _, _) => out += "Not"
        case BinOpExpr("AND", _, _, _) => out += "And"
        case BinOpExpr("OR", _, _, _) => out += "Or"
        case BinOpExpr("||", _, _, _) => out += "Concat"
        case BinOpExpr("+" | "-" | "*" | "/" | "%", _, _, _) => out += "Arithmetic"
        case _: BinOpExpr => out += "Comparison"
        case _: FuncExpr => out += "Func"
        case _: CaseExpr => out += "Case"
        case _: SwitchCaseExpr => out += "SwitchCase"
        case _: BetweenExpr => out += "Between"
        case _: IsNullExpr => out += "IsNull"
        case _: InListExpr => out += "InList"
        case _: LikeExpr => out += "Like"
      }
      children(e).foreach(expr)
    }
    def node(p: LogicalPlan): Unit = {
      p match {
        case _: LogicalScan => out += "Scan"
        case LogicalProject(_, ps) => out += "Project"; ps.foreach(x => expr(x._1))
        case LogicalFilter(_, pred) => out += "Filter"; expr(pred)
        case LogicalAggregate(_, ks, as) => out += "Aggregate"; ks.foreach(k => expr(k._1)); as.foreach(_._1.args.foreach(expr))
        case LogicalJoin(_, _, cond, kind) => out += s"Join $kind"; expr(cond)
        case LogicalSort(_, keys) => out += "Sort"; keys.foreach(k => expr(k.expr))
        case _: LogicalLimit => out += "Limit"
        case _: LogicalOffset => out += "Offset"
        case _: LogicalDistinct => out += "Distinct"
        case LogicalUnion(_, _, all) => out += (if (all) "Union all" else "Union distinct")
        case LogicalWindow(_, ws) =>
          out += "Window"
          ws.foreach(w => (w.spec.partitionKeys ++ w.spec.orderKeys.map(_.expr)).foreach(expr))
      }
      p.children.foreach(node)
    }
    node(plan)
    out.toSet
  }

  private def children(e: Expr): Seq[Expr] = e match {
    case _: LitExpr | _: ColRefExpr => Nil
    case CastExpr(c, _) => Seq(c)
    case UnaryOpExpr(_, c, _) => Seq(c)
    case BinOpExpr(_, l, r, _) => Seq(l, r)
    case FuncExpr(_, args, _) => args
    case CaseExpr(branches, elseExpr, _) => branches.flatMap { case (w, t) => Seq(w, t) } ++ elseExpr
    case SwitchCaseExpr(operand, branches, elseExpr, _) => operand +: (branches.flatMap(b => Seq(b.when, b.value)) ++ elseExpr)
    case BetweenExpr(c, low, high) => Seq(c, low, high)
    case IsNullExpr(c, _) => Seq(c)
    case InListExpr(c, items, _) => c +: items
    case LikeExpr(c, pattern, _, _) => Seq(c, pattern)
  }
}
