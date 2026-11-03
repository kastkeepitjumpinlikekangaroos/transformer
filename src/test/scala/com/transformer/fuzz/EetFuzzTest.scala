package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen.MetaCase
import com.transformer.fuzz.QueryGen.QueryCase
import com.transformer.fuzz.oracle.Eet
import com.transformer.sql.plan.LogicalBuilder
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

/** Value-preserving rewrites ([[Eet]], EET's relation): every query the
  * generators write, its bound plan with up to three subexpressions each
  * wrapped in a form that cannot change its value, printed as SQL and run,
  * answers as the query does. Over [[QueryGen]]'s single-table queries,
  * [[MetaQueryGen]]'s joins, windows, CTEs and unions, and the frontend's
  * honoured clauses in their contexts and both spellings of a shape
  * ([[SqlTextGen]]).
  *
  * The guards say the corpus is not vacuous: rewrites stand at each position
  * and in each form, unused branches that would fail are planted, the
  * queries rewritten return rows (two queries that return none agree
  * whatever they mean), and the printed rewrites bind.
  */
class EetFuzzTest {

  import EetFuzzTest._

  @Test def fuzzSingleTableQueries(): Unit =
    Props.forAll[Case[QueryCase]](
      name = "eet-query",
      gen = rng => Case(QueryGen.generate(rng.split()), rng.nextLong()),
      shrink = c => Shrinker.queryCase(c.value).map(v => c.copy(value = v)),
      count = Props.seedCountOr(DefaultSeeds)
    ) { c => Eet.check(c.value.sql, queryCatalog(c.value), new Rng(c.rewriteSeed), Variants); () }

  @Test def fuzzMultiRelationQueries(): Unit =
    Props.forAll[Case[MetaCase]](
      name = "eet-meta",
      gen = rng => Case(MetaQueryGen.generate(rng.split()), rng.nextLong()),
      shrink = c => Shrinker.metaCase(c.value).map(v => c.copy(value = v)),
      count = Props.seedCountOr(DefaultSeeds)
    ) { c => Eet.check(c.value.bodySql, c.value.env.catalog(), new Rng(c.rewriteSeed), Variants); () }

  @Test def fuzzHonouredClauses(): Unit =
    Props.forAll[Case[SqlTextGen.HonouredCase]](
      name = "eet-honoured",
      gen = rng => Case(SqlTextGen.genHonouredCase(rng.split()), rng.nextLong()),
      shrink = c => Shrinker.honouredCase(c.value).map(v => c.copy(value = v)),
      count = Props.seedCountOr(DefaultSeeds)
    ) { c => Eet.check(c.value.contextSql, c.value.tables.catalog(), new Rng(c.rewriteSeed), Variants); () }

  @Test def fuzzSpellings(): Unit =
    Props.forAll[Case[SqlTextGen.SpellingCase]](
      name = "eet-spelling",
      gen = rng => Case(SqlTextGen.genSpellingCase(rng.split()), rng.nextLong()),
      shrink = c => Shrinker.spellingCase(c.value).map(v => c.copy(value = v)),
      count = Props.seedCountOr(DefaultSeeds)
    ) { c =>
      val catalog = c.value.tables.catalog()
      val rng = new Rng(c.rewriteSeed)
      Eet.check(c.value.left, catalog, rng.split(), Variants)
      Eet.check(c.value.right, catalog, rng.split(), Variants)
      ()
    }

  /** Rewrites stand at every position and in every form, and unused
    * branches that would fail on every row they read are planted: generation
    * and binding only, over a block of each generator. */
  @Test def rewritesReachEveryPositionAndForm(): Unit = {
    val positions = mutable.Map.empty[String, Int].withDefaultValue(0)
    val forms = mutable.Map.empty[String, Int].withDefaultValue(0)
    var failing, queries = 0
    corpus(GuardSeeds).foreach { case (sql, catalog, seed) =>
      try {
        val built = LogicalBuilder.build(sql, catalog)
        if (!Eet.nondeterministic(sql, built)) {
          queries += 1
          val rw = Eet.rewrite(built, new Rng(seed))
          rw.rewrites.foreach { r =>
            positions(r.position) += 1
            if (r.inCaseBranch) positions("CASE branch") += 1
            forms(r.form) += 1
            if (r.failingBranch) failing += 1
          }
        }
      } catch { case _: IllegalArgumentException => () }
    }
    val report = s"over $queries queries: positions ${Eet.Positions.map(p => s"$p ${positions(p)}").mkString(", ")}; " +
      s"forms ${Eet.Forms.map(f => s"$f ${forms(f)}").mkString(", ")}; failing branches planted $failing"
    println(s"[eet] $report")
    Eet.Positions.foreach(p => assertTrue(s"a rewrite rarely stands at $p: $report", positions(p) > PositionFloor))
    Eet.Forms.foreach(f => assertTrue(s"the form $f is rarely drawn: $report", forms(f) > FormFloor))
    assertTrue(s"failing branches are rarely planted: $report", failing > FailingFloor)
  }

  /** The queries rewritten return rows, and the printed rewrites bind: over
    * a block of each generator, run once each, and each rewrite printed and
    * bound. Measured over the default 300 seeds: 1497 queries checked, 1033
    * returning rows; 2854 rewrites held, 2 moved, none refused, unparsed or
    * unprintable. `FUZZ_PROBE_SEEDS` scales the block; at 4000 none of
    * 38185 rewrites failed to bind. */
  @Test def rewrittenQueriesReturnRowsAndTheirTextBinds(): Unit = {
    val kinds = mutable.Map.empty[String, Int].withDefaultValue(0)
    val why = mutable.LinkedHashMap.empty[String, Int].withDefaultValue(0)
    var checked, withRows = 0
    val n = Props.probeSeedsOr(RunSeeds)
    corpus(n).foreach { case (sql, catalog, seed) =>
      val outcome = Eet.check(sql, catalog, new Rng(seed), Variants)
      if (outcome.skipped.isEmpty) {
        checked += 1
        if (outcome.rows > 0) withRows += 1
        outcome.variants.foreach { v =>
          kinds(v.kind) += 1
          if (v.why.nonEmpty) why(s"${v.kind}: ${v.why.take(120)}\n    ${v.text.replace("\n", "\n    ")}") += 1
        }
      }
    }
    val variants = kinds.values.sum
    val report = s"$checked queries checked, $withRows returning rows; variants ${kinds.toSeq.sorted.mkString(", ")}" +
      why.toSeq.sortBy(-_._2).take(12).map { case (w, n) => s"\n  [$n] $w" }.mkString
    println(s"[eet] $report")
    assertTrue(s"too few queries are checked: $report", checked > n * 2)
    assertTrue(s"too few queries rewritten return rows: $report", withRows > checked / 2)
    val unbound = kinds("rejected") + kinds("unprintable") + kinds("too deep")
    assertTrue(s"too many rewrites do not bind: $report", unbound <= variants / 100)
    assertTrue(s"too few rewrites are checked: $report", kinds("held") > variants * 9 / 10)
  }
}

object EetFuzzTest {

  /** A generated case and the seed its rewrites draw from. */
  final case class Case[A](value: A, rewriteSeed: Long)

  /** Each case runs its query once and each of its rewrites once. */
  val DefaultSeeds: Int = 300
  val Variants: Int = 2
  val GuardSeeds: Int = 3000
  val RunSeeds: Int = 300
  val GuardBase: Long = 700000L

  /** Measured over the guard's 3000 seeds (14980 queries): the thinnest
    * position, a window's argument, 115 (its partition key 145, order key 303,
    * a CASE branch 395, a HAVING 594); the thinnest form, the repeated IN list
    * item, 138, the four over a condition about 500 each, the others about
    * 5000; failing branches planted 4784. Bounds sit about 3x below. */
  val PositionFloor: Int = 38
  val FormFloor: Int = 45
  val FailingFloor: Int = 1500

  def queryCatalog(qc: QueryCase): Catalog = {
    val c = new Catalog
    c.register(QueryGen.ViewName, qc.dataset.evenPartitions(2, 3))
    c
  }

  /** `n` seeds of each generator, as (sql, catalog, rewrite seed). */
  def corpus(n: Int): Iterator[(String, Catalog, Long)] = (0 until n).iterator.flatMap { i =>
    val seed = GuardBase + i
    val qc = QueryGen.generate(new Rng(seed))
    val mc = MetaQueryGen.generate(new Rng(seed))
    val hc = SqlTextGen.genHonouredCase(new Rng(seed))
    val sc = SqlTextGen.genSpellingCase(new Rng(seed))
    val spellings = sc.tables.catalog()
    Iterator(
      (qc.sql, queryCatalog(qc), seed),
      (mc.bodySql, mc.env.catalog(), seed),
      (hc.contextSql, hc.tables.catalog(), seed),
      (sc.left, spellings, seed),
      (sc.right, spellings, seed + 1))
  }
}
