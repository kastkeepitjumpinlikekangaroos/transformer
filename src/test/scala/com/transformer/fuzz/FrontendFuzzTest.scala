package com.transformer.fuzz

import com.transformer.core.ExecutionOptions
import com.transformer.fuzz.SqlTextGen._
import com.transformer.fuzz.oracle.{CleanRejection, NoSilentDrop, RelEngine, SyntaxEquivalence}
import com.transformer.fuzz.oracle.CleanRejection.{Defect, Failed, KnownFailure, Phase, Ran}
import com.transformer.sql.plan.UnsupportedClauses
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable
import scala.util.Try

/** Property-based gate over the SQL FRONTEND: what the engine does with text
  * it is not already expected to handle. The other SQL fuzzers render their
  * own typed AST, so their corpus is the supported grammar and they ask only
  * whether the answer is right; [[SqlTextGen]] writes text, and these
  * properties ask how it fails:
  *
  *   - [[CleanRejection]] — over mutated text, the clause table spliced
  *     everywhere, and both modes below: a text binds and runs, or fails with
  *     an `IllegalArgumentException` naming what the user wrote, or fails
  *     while running because of a value. Never an internal exception class,
  *     never an internal marker in the message, never a rejection that waits
  *     for the data.
  *   - [[NoSilentDrop]] — an entry of `UnsupportedClauses.audits`, spliced
  *     into every context and position, is refused at bind time (every case,
  *     since the table is finite); a clause the binder honours keeps its
  *     meaning in every context a query can sit in, and the data makes it
  *     change the answer often enough to show a drop.
  *   - [[SyntaxEquivalence]] — two spellings of one query agree on their
  *     column types and rows, over data that tells the spellings' subject
  *     from its absence.
  *
  * A failure prints the seed and the minimized counterexample; reproduce with
  * `FUZZ_SEED=<seed> FUZZ_SEEDS=1`, and run a campaign with
  * {{{
  *   bazel test //src/test/scala/com/transformer/fuzz:frontend_fuzz_campaign \
  *     --test_tag_filters= --test_env=FUZZ_SEEDS=100000 \
  *     --nocache_test_results --test_timeout=3600
  * }}}
  */
class FrontendFuzzTest {

  /** Each case parses and binds once and, when it binds, runs once over a
    * handful of rows: a few milliseconds a case. */
  private val DefaultCleanRejectionSeeds = 2000
  /** An honoured case runs three queries, a spelling case three. */
  private val DefaultHonouredSeeds = 1000
  private val DefaultSpellingSeeds = 1000

  // ---- clean rejection ------------------------------------------------------

  @Test def fuzzCleanRejection(): Unit =
    Props.forAll[TextCase](
      name = "clean-rejection",
      gen = SqlTextGen.genTextCase,
      shrink = Shrinker.textCase,
      count = Props.seedCountOr(DefaultCleanRejectionSeeds)
    ) { tc => CleanRejection.check(tc.sql, () => tc.tables.catalog()); () }

  /** The clean-rejection corpus guard. A mutator that only ever produces
    * garbage tests nothing past the parser, and one that never breaks the text
    * tests nothing at all, so the corpus must reach every outcome: text that
    * runs, parse errors, the rejection pass, the binder's own errors and
    * failures while running, and every mutation must get past the parser on a
    * share of its cases. Prints the tallies, the known defects' among them,
    * for the PR.
    *
    * Its block starts past the property's default seeds, so the two check
    * different texts (the check throws on an unclean failure here too).
    * Measured over it: ran a third, parse errors over a third, the rejection
    * pass a tenth, the binder's own errors a tenth, failures on a value one in
    * fourteen; deep nesting, the rarest mutation, about one case in 125; each
    * bound sits well below. */
  @Test def cleanRejectionReachesEveryOutcome(): Unit = {
    val base = 100000L
    val n = 1500
    val outcomes = mutable.LinkedHashMap.empty[String, Int]
    val defects = mutable.LinkedHashMap.empty[Defect, Int]
    val byMutation = mutable.LinkedHashMap.empty[String, (Int, Int)] // (cases, past the parser)
    var seed = 0
    while (seed < n) {
      val tc = SqlTextGen.genTextCase(new Rng(base + seed))
      val outcome = CleanRejection.check(tc.sql, () => tc.tables.catalog())
      val label = outcome match {
        case Ran => "ran"
        case f: Failed => s"${f.phase}/${f.kind}"
        case k: KnownFailure => s"known: ${k.defect}"
      }
      outcomes(label) = outcomes.getOrElse(label, 0) + 1
      outcome match {
        case f: Failed => f.defects.foreach(d => defects(d) = defects.getOrElse(d, 0) + 1)
        case k: KnownFailure => defects(k.defect) = defects.getOrElse(k.defect, 0) + 1
        case Ran => ()
      }
      if (tc.origin.startsWith("mutation/")) {
        val kind = tc.detail.takeWhile(_ != ':')
        val pastParser = outcome match {
          case f: Failed => f.kind != "parse"
          case _ => true
        }
        val (c, p) = byMutation.getOrElse(kind, (0, 0))
        byMutation(kind) = (c + 1, p + (if (pastParser) 1 else 0))
      }
      seed += 1
    }
    println(s"[frontend] clean-rejection outcomes over $n seeds: " +
      outcomes.toSeq.sortBy(-_._2).map { case (k, c) => s"$k=$c" }.mkString(", "))
    println(s"[frontend] known defects: " + defects.toSeq.map { case (d, c) => s"$d=$c" }.mkString(", "))
    println(s"[frontend] mutations (cases / past the parser): " +
      byMutation.toSeq.map { case (k, (c, p)) => s"$k=$c/$p" }.mkString(", "))
    def count(prefix: String): Int = outcomes.iterator.filter(_._1.startsWith(prefix)).map(_._2).sum
    assertTrue(s"ran=${count("ran")}", count("ran") > n / 10)
    assertTrue(s"parse=${count("bind/parse")}", count("bind/parse") > n / 5)
    assertTrue(s"unsupported clause=${count("bind/unsupported clause")}", count("bind/unsupported clause") > n / 50)
    assertTrue(s"bind=${count("bind/bind")}", count("bind/bind") > n / 20)
    assertTrue(s"data=${count("run/data")}", count("run/data") > 5)
    SqlTextGen.MutationKinds.foreach { case (_, kind) =>
      val (c, p) = byMutation.getOrElse(kind, (0, 0))
      assertTrue(s"mutation $kind generated $c times", c > n / 200)
      // An unbalanced query never parses: that mutation is there for the
      // parser's error path, and for the unclosed parentheses that run it
      // into its timeout.
      if (kind != "unbalance-paren") assertTrue(s"mutation $kind got past the parser on $p of $c", p > c / 20)
    }
  }

  /** Every known defect still shows on its repro. A fix that removes one
    * fails here until its entry in `CleanRejection.Defect` is deleted and its
    * plan closed, so an entry cannot excuse failures after the defect is gone.
    * The repros are deterministic rather than drawn from the corpus, where
    * the rarest of them comes once in 20000 seeds. */
  @Test def everyKnownDefectStillReproduces(): Unit = {
    Defect.All.foreach { d =>
      val outcome = CleanRejection.check(d.repro, () => SqlTextGen.ReferenceTables.catalog())
      val shows = outcome match {
        case KnownFailure(k, _) => k == d
        case f: Failed => f.defects.contains(d)
        case Ran => false
      }
      assertTrue(s"known defect '$d' no longer shows on `${SqlTextGen.clip(d.repro)}` (got $outcome): if " +
        s"${d.plan} landed, delete the entry from CleanRejection.Defect and close the plan", shows)
    }
  }

  /** The oracle on throwables made to order, so a classification that went
    * too loose (calling an internal exception clean) or too strict (calling a
    * clean rejection a leak) is caught without a generator in the way. */
  @Test def cleanRejectionClassifiesFailures(): Unit = {
    def classify(sql: String, phase: Phase, e: Throwable): Either[String, CleanRejection.Outcome] =
      try Right(CleanRejection.classify(sql, phase, e, None))
      catch { case a: AssertionError => Left(a.getMessage) }
    def clean(sql: String, phase: Phase, e: Throwable): Unit =
      classify(sql, phase, e).left.foreach(m => fail(s"expected clean, got: $m"))
    def unclean(sql: String, phase: Phase, e: Throwable, why: String): Unit =
      classify(sql, phase, e) match {
        case Left(m) => assertTrue(s"expected '$why' in: $m", m.contains(why))
        case Right(o) => fail(s"expected '$why', got $o")
      }
    val sql = "SELECT x FROM t"
    clean(sql, Phase.Bind, new IllegalArgumentException("Unknown column 'x'. Available: [t.a, t.b, t.s]"))
    clean(sql, Phase.Run, new IllegalArgumentException("Cannot cast 'x' to int"))
    clean(sql, Phase.Run, new ArithmeticException("Decimal overflow: 1234.5 does not fit decimal(5,2)"))
    unclean(sql, Phase.Bind, new NullPointerException("Cannot invoke \"Object.getClass()\" because \"<local6>\" is null"),
      "internal exception class")
    unclean(sql, Phase.Run, new ClassCastException("class java.lang.String cannot be cast to class java.lang.Number"),
      "internal exception class")
    unclean(sql, Phase.Bind, new IllegalStateException("Unknown binary op 'x'"), "internal exception class")
    unclean(sql, Phase.Bind, new ArithmeticException("/ by zero"), "internal exception class")
    unclean(sql, Phase.Bind, new IllegalArgumentException(), "no message")
    unclean(sql, Phase.Bind, new IllegalArgumentException("requirement failed: x in t"), "internal invariant")
    unclean(sql, Phase.Bind, new IllegalArgumentException("x at com.transformer.sql.plan.LogicalBuilder"),
      "qualified class name")
    unclean(sql, Phase.Bind, new IllegalArgumentException("x is ColRefExpr(0,x,int)"), "class name")
    unclean(sql, Phase.Bind, new IllegalArgumentException("x is [B@1b6d3586"), "identity hash")
    unclean(sql, Phase.Bind, new IllegalArgumentException("x not in Vector(a, b)"), "Scala collection")
    unclean(sql, Phase.Bind, new IllegalArgumentException("x not in [_k0, _k1]"), "slot name")
    unclean(sql, Phase.Bind, new IllegalArgumentException("Index 3 out of bounds"), "internal invariant")
    unclean(sql, Phase.Run, new IllegalArgumentException("x is not supported"), "waited for the data")
    unclean("SELECT a FROM t", Phase.Bind, new IllegalArgumentException("Unexpected state 42"), "names nothing")
    // A user's own word is not a leak: a CamelCase alias the SQL wrote.
    clean("SELECT MyAlias FROM t", Phase.Bind, new IllegalArgumentException("Unknown column 'MyAlias'"))
    // The known defects are recognized, and only as far as they reach.
    classify("SELECT a, COUNT(*) FROM t", Phase.Bind,
      new IllegalArgumentException("Unknown column 'a'. Available: [_agg0]")) match {
      case Right(KnownFailure(Defect.AggSlotInUnknownColumn, _)) => ()
      case other => fail(s"expected the aggregate-slot defect, got $other")
    }
    classify(sql, Phase.Bind, new IllegalArgumentException(
      "SQL parse error: net.sf.jsqlparser.parser.ParseException: Encountered unexpected token: \"x\" at line 1, column 8.")) match {
      case Right(f: Failed) => assertEquals(Vector(Defect.ParserClassInParseError), f.defects)
      case other => fail(s"expected a parse error with its class name excused, got $other")
    }
    unclean(sql, Phase.Bind, new IllegalArgumentException(
      "SQL parse error: net.sf.jsqlparser.parser.ParseException: x at com.transformer.Foo"), "qualified class name")
    val wrapped = new java.util.concurrent.ExecutionException(new IllegalArgumentException("Cannot cast 'x' to int"))
    classify(sql, Phase.Run, wrapped) match {
      case Right(f: Failed) => assertEquals(Vector(Defect.PoolWrappedFailure), f.defects)
      case other => fail(s"expected a wrapped data error, got $other")
    }
    unclean(sql, Phase.Run, new java.util.concurrent.ExecutionException(new ClassCastException("x")),
      "internal exception class")
  }

  // ---- no silent drop: the clause table ----------------------------------------

  /** Every entry of the clause table with an example, in every context, and
    * for an entry on an expression class its fragment at every position in
    * every context: refused at bind time, never bound. The table is finite
    * and the verdict reads no data, so every case runs rather than a sample.
    *
    * The guard half, since a splice that stops carrying its clause is green:
    * every expression-level entry must yield a fragment; each example must
    * fire its own entry's rejection in nearly every context (a context it
    * does not parse in, a bare UNION after a tail clause, is refused by the
    * parser instead), and each fragment at nearly every place a supported
    * query binds ([[everySpliceSiteIsAValidHost]]). Prints where they do not,
    * by entry. */
  @Test def noSilentDropRefusesEveryEntryEverywhere(): Unit = {
    val fired = mutable.Map.empty[(UnsupportedClauses.Rejection, String), mutable.Set[String]]
    var cases, own, otherwise = 0
    val otherReasons = mutable.LinkedHashMap.empty[String, Int]
    SqlTextGen.AllClauseCases.foreach { c =>
      cases += 1
      NoSilentDrop.checkEntry(c) match {
        case NoSilentDrop.RefusedByItsEntry =>
          own += 1
          fired.getOrElseUpdate((c.entry, c.position), mutable.Set.empty) += c.context.name
        case NoSilentDrop.RefusedOtherwise(reason) =>
          otherwise += 1
          val key = reason.take(60)
          otherReasons(key) = otherReasons.getOrElse(key, 0) + 1
      }
    }
    val contexts = (SqlTextGen.Contexts :+ SqlTextGen.BareUnion).map(_.name)
    val hosts = validHosts
    println(s"[frontend] no-silent-drop over the clause table: ${SqlTextGen.TableEntries.size} entries, $cases cases, " +
      s"refused by their own entry=$own, otherwise=$otherwise; entries with no example (not spliced): " +
      SqlTextGen.EntriesWithoutExample.map(_.clause).mkString("; "))
    otherReasons.toSeq.sortBy(-_._2).take(8).foreach { case (r, n) => println(f"  [$n%4d] refused otherwise: $r") }
    val problems = mutable.ArrayBuffer.empty[String]
    SqlTextGen.TableEntries.foreach { entry =>
      val example = fired.getOrElse((entry, "example"), mutable.Set.empty)
      val missing = contexts.filterNot(example)
      if (missing.nonEmpty) println(s"  ${entry.clause}: its example is refused otherwise in ${missing.mkString(", ")}")
      if (example.size < contexts.size - 2)
        problems += s"${entry.clause}: its example fires its own rejection in only ${example.size} of ${contexts.size} contexts"
      if (SqlTextGen.isExpressionLevel(entry)) SqlTextGen.fragmentOf(entry.example.get) match {
        case None =>
          problems += s"${entry.clause}: an entry on ${SqlTextGen.classesOf(entry).mkString(", ")} needs an example " +
            "written as SELECT <one item> FROM t or SELECT <items> FROM t [, u] WHERE <predicate>, so its clause " +
            "can be spliced into every position; `${entry.example.get}` is neither"
        case Some(f) =>
          val sites = for { p <- SqlTextGen.positionsFor(f); c <- contexts if hosts((p.name, c)) } yield (p.name, c)
          val reached = sites.count { case (p, c) => fired.get((entry, p)).exists(_(c)) }
          // JSqlParser refuses a few fragments in a few contexts (an IGNORE
          // NULLS or KEEP call inside a CTE counted by its outer query), which
          // the parser refusing is not a drop; a splice that lost its clause
          // misses nearly every site.
          if (reached < sites.size * 3 / 4)
            problems += s"${entry.clause}: its fragment `${f.text}` fires its own rejection at only $reached of " +
              s"${sites.size} valid sites; it misses " +
              sites.filterNot { case (p, c) => fired.get((entry, p)).exists(_(c)) }.take(6).mkString(", ")
      }
    }
    assertTrue(problems.mkString("Splices that do not reach their entry:\n  ", "\n  ", ""), problems.isEmpty)
  }

  /** The constructs beyond the table ([[SqlTextGen.BeyondTheTable]]): each
    * refused in every context it can sit in, or, while its plan records it
    * binding, still binding at top level, so a fix fails here until the plan
    * reference is removed and the construct joins the ones that must be
    * refused. */
  @Test def noSilentDropRefusesWhatTheTableCannotList(): Unit = {
    val problems = mutable.ArrayBuffer.empty[String]
    SqlTextGen.BeyondTheTable.foreach { b =>
      val contexts = if (b.wrappable) SqlTextGen.Contexts else Vector(SqlTextGen.TopLevel)
      val verdicts = contexts.map(c => c -> NoSilentDrop.checkBeyond(b, c))
      b.knownPlan match {
        case Some(plan) =>
          if (verdicts.head._2 == NoSilentDrop.Refused)
            problems += s"${b.name} is refused now (`${b.sql}`): if $plan landed, drop the construct's knownPlan " +
              "so it must be refused in every context"
        case None =>
          verdicts.collect { case (c, v) if v != NoSilentDrop.Refused => c }.foreach(c => problems += s"${b.name} binds in $c")
      }
    }
    assertTrue(problems.mkString("\n  ", "\n  ", ""), problems.isEmpty)
  }

  /** The (position, context) pairs where a supported expression in the
    * fragment's place binds and runs over the reference tables. */
  private lazy val validHosts: Set[(String, String)] = {
    val positions = SqlTextGen.ValuePositions ++ SqlTextGen.PredicatePositions ++
      SqlTextGen.JoinedPredicatePositions ++ SqlTextGen.StarPositions
    (for { p <- positions; c <- SqlTextGen.Contexts :+ SqlTextGen.BareUnion
           if Try(RelEngine.runRows(SqlTextGen.ReferenceTables.catalog(), c.wrap(p.twin), ExecutionOptions.Default)).isSuccess }
      yield (p.name, c.name)).toSet
  }

  /** Every position a fragment is spliced into is a place a supported query
    * binds and runs, in every context but the bare UNION (after a tail
    * clause the first arm does not parse). Otherwise an entry refused there
    * could be refused for the place rather than the clause. */
  @Test def everySpliceSiteIsAValidHost(): Unit = {
    val positions = SqlTextGen.ValuePositions ++ SqlTextGen.PredicatePositions ++
      SqlTextGen.JoinedPredicatePositions ++ SqlTextGen.StarPositions
    val invalid = for { p <- positions; c <- SqlTextGen.Contexts if !validHosts((p.name, c.name)) }
      yield s"${p.name} in ${c.name}: ${c.wrap(p.twin)}"
    assertTrue(invalid.mkString("Splice sites where a supported query does not run:\n  ", "\n  ", ""), invalid.isEmpty)
  }

  // ---- no silent drop: honoured clauses ----------------------------------------

  @Test def fuzzNoSilentDropHonoured(): Unit =
    Props.forAll[HonouredCase](
      name = "no-silent-drop",
      gen = SqlTextGen.genHonouredCase,
      shrink = Shrinker.honouredCase,
      count = Props.seedCountOr(DefaultHonouredSeeds)
    ) { hc => NoSilentDrop.checkHonoured(hc); () }

  /** Every honoured clause in every context over the reference rows, its
    * parameters at their first values: the deterministic floor under the
    * property, which samples. */
  @Test def everyHonouredClauseKeepsItsMeaningInEveryContext(): Unit = {
    var observable = 0
    var refused = Vector.empty[String]
    for { h <- SqlTextGen.honouredAtFirstParams; c <- SqlTextGen.HonouredContexts } {
      NoSilentDrop.checkHonoured(HonouredCase(SqlTextGen.ReferenceTables, h, c)) match {
        case Some(v) => if (v.observable) observable += 1
        case None => refused :+= s"${h.key} in $c"
      }
    }
    assertTrue(refused.mkString("The engine refused honoured clauses in these contexts:\n  ", "\n  ", ""), refused.isEmpty)
    assertTrue(s"observable on the reference rows: $observable", observable > 0)
  }

  /** The honoured property's guard. Every clause must be generated, refused
    * nowhere, and OBSERVABLE on a share of its cases: the query with it and
    * without it must differ at top level, or a context that drops it answers
    * the same as one that keeps it and the property cannot tell. Every
    * context must see the clauses change its answer on a share of its cases
    * (counting rows hides a change of values, so the share there is lower).
    * Prints the measured shares. */
  @Test def noSilentDropHonouredClausesAreObservable(): Unit = {
    val n = 3000
    val byClause = mutable.LinkedHashMap.empty[String, Array[Int]] // generated, refused, observable
    val byContext = mutable.LinkedHashMap.empty[String, Array[Int]] // generated, observable in context
    var seed = 0
    while (seed < n) {
      val hc = SqlTextGen.genHonouredCase(new Rng(seed.toLong))
      val k = byClause.getOrElseUpdate(hc.honoured.key, new Array[Int](3))
      val c = byContext.getOrElseUpdate(hc.context.name, new Array[Int](2))
      k(0) += 1; c(0) += 1
      NoSilentDrop.checkHonoured(hc) match {
        case None => k(1) += 1
        case Some(v) =>
          if (v.observable) k(2) += 1
          if (v.observableInContext) c(1) += 1
      }
      seed += 1
    }
    println(s"[frontend] honoured clauses over $n seeds (generated / refused / observable):")
    byClause.toSeq.sortBy(e => e._2(2).toDouble / e._2(0)).foreach { case (key, a) =>
      println(f"  ${a(0)}%4d ${a(1)}%3d ${a(2)}%4d  $key")
    }
    println("  by context (generated / observable in it): " +
      byContext.toSeq.map { case (name, a) => s"$name=${a(0)}/${a(1)}" }.mkString(", "))
    // The bounds separate "rare" from "gone": DISTINCT inside an aggregate
    // is the thinnest, observable only where a group repeats a value, about
    // one case in ten over these small tables.
    SqlTextGen.HonouredTemplates.map(_.key).distinct.foreach { key =>
      val a = byClause.getOrElse(key, new Array[Int](3))
      assertTrue(s"$key generated ${a(0)} times", a(0) > n / SqlTextGen.HonouredTemplates.size / 3)
      assertEquals(s"$key refused", 0, a(1))
      assertTrue(s"$key observable on ${a(2)} of ${a(0)}", a(2) > a(0) / 20)
    }
    SqlTextGen.HonouredContexts.foreach { c =>
      val a = byContext.getOrElse(c.name, new Array[Int](2))
      assertTrue(s"context $c observable on ${a(1)} of ${a(0)}", a(1) > a(0) / 5)
    }
  }

  /** The oracle reports a drop it is shown: a clause-table splice whose text
    * lost its clause binds, and a context that answers what the query without
    * the clause answers is called out as dropping it. */
  @Test def noSilentDropReportsADroppedClause(): Unit = {
    val c = SqlTextGen.AllClauseCases.find(_.twin.isDefined).get
    val e = Try(NoSilentDrop.checkEntry(c.copy(sql = c.twin.get))).failed.get
    assertTrue(e.getMessage, e.getMessage.contains("was not refused"))
    val offset = SqlTextGen.honouredAtFirstParams.find(_.clause == "OFFSET").get
    val dropping = Context("a context that drops the clause", RowEffect.Same, _ => offset.withoutSql)
    val d = Try(NoSilentDrop.checkHonoured(HonouredCase(SqlTextGen.ReferenceTables, offset, dropping))).failed.get
    assertTrue(d.getMessage, d.getMessage.contains("silently dropped"))
  }

  // ---- syntax equivalence --------------------------------------------------------

  @Test def fuzzSyntaxEquivalence(): Unit =
    Props.forAll[SpellingCase](
      name = "syntax-equivalence",
      gen = SqlTextGen.genSpellingCase,
      shrink = Shrinker.spellingCase,
      count = Props.seedCountOr(DefaultSpellingSeeds)
    ) { sc => SyntaxEquivalence.check(sc); () }

  /** The equivalence guard, after `joinCommutativityIsNotVacuous`: the
    * relation must actually hold on a meaningful share of seeds, not be
    * refused or recorded as known; every shape must be generated, and every
    * shape that agrees must be observable on a share of its cases, so the
    * data tells the pair's subject from its absence. Prints the shares. */
  @Test def syntaxEquivalenceIsNotVacuous(): Unit = {
    val n = 3000
    val byShape = mutable.LinkedHashMap.empty[String, Array[Int]] // generated, agreed, observable, refused, known
    var seed = 0
    while (seed < n) {
      val sc = SqlTextGen.genSpellingCase(new Rng(seed.toLong))
      val a = byShape.getOrElseUpdate(sc.shape, new Array[Int](5))
      a(0) += 1
      SyntaxEquivalence.check(sc) match {
        case SyntaxEquivalence.Agreed(obs) => a(1) += 1; if (obs) a(2) += 1
        case _: SyntaxEquivalence.BothRefused => a(3) += 1
        case _: SyntaxEquivalence.KnownDisagreement => a(4) += 1
      }
      seed += 1
    }
    println(s"[frontend] syntax equivalence over $n seeds (generated / agreed / observable / refused / known):")
    byShape.toSeq.sortBy(e => e._2(2).toDouble / math.max(1, e._2(1))).foreach { case (shape, a) =>
      println(f"  ${a(0)}%4d ${a(1)}%4d ${a(2)}%4d ${a(3)}%3d ${a(4)}%4d  $shape")
    }
    val agreed = byShape.values.map(_(1)).sum
    assertTrue(s"the relation held on only $agreed of $n seeds", agreed > n * 3 / 4)
    SqlTextGen.SpellingShapes.foreach { shape =>
      val a = byShape.getOrElse(shape.shape, new Array[Int](5))
      assertTrue(s"${shape.shape} generated ${a(0)} times", a(0) > n / SqlTextGen.SpellingShapes.size / 3)
      assertEquals(s"${shape.shape} refused", 0, a(3))
      if (SyntaxEquivalence.known(shape.shape).isEmpty)
        assertTrue(s"${shape.shape} observable on ${a(2)} of ${a(1)}", a(2) > a(1) / 10)
    }
  }

  /** Every recorded disagreement still disagrees on its repro, so a fix fails
    * here until its entry in `SyntaxEquivalence.KnownDefects` is deleted. */
  @Test def everyKnownDisagreementStillDisagrees(): Unit =
    SyntaxEquivalence.KnownDefects.foreach { k =>
      val sc = SpellingCase(SqlTextGen.ReferenceTables, k.shape, k.repro._1, k.repro._2, None)
      SyntaxEquivalence.check(sc) match {
        case SyntaxEquivalence.KnownDisagreement(found) => assertEquals(k, found)
        case other => fail(s"'${k.shape}' no longer disagrees on its repro (got $other): if ${k.plan} landed, " +
          "delete it from SyntaxEquivalence.KnownDefects and close the plan")
      }
    }

  /** The oracle reports spellings that differ, in their rows or in their
    * column types alone. */
  @Test def syntaxEquivalenceReportsDifferentSpellings(): Unit = {
    def disagree(left: String, right: String, why: String): Unit = {
      val e = Try(SyntaxEquivalence.check(SpellingCase(SqlTextGen.ReferenceTables, "test", left, right, None))).failed.get
      assertTrue(e.getMessage, e.getMessage.contains(why))
    }
    disagree("SELECT a FROM t WHERE a > 1", "SELECT a FROM t WHERE a >= 1", "the spellings disagree")
    disagree("SELECT a AS v FROM t", "SELECT CAST(a AS BIGINT) AS v FROM t", "column types differ")
    disagree("SELECT a FROM t", "SELECT a FROM nowhere", "refuses the right")
  }

  // ---- the generator --------------------------------------------------------

  /** The lexer renders every text it cuts back to exactly that text — the
    * mutations and the shrinker rebuild SQL from its tokens, so a lexer that
    * lost a character would test text no generator wrote. */
  @Test def lexerRendersEveryTextBackExactly(): Unit = {
    var seed = 0
    while (seed < 2000) {
      val tc = SqlTextGen.genTextCase(new Rng(seed.toLong))
      assertEquals(tc.sql, Lexer.render(Lexer.lex(tc.sql)))
      seed += 1
    }
    Seq("SELECT 'it''s' AS q FROM t", "SELECT a FROM t -- c\nWHERE a = 1", "SELECT /* x */ 1.5e-3, .5, \"q\" FROM t",
      "SELECT 'unclosed", "SELECT a <> b, a != b, s || s, a::INT FROM t").foreach { sql =>
      assertEquals(sql, Lexer.render(Lexer.lex(sql)))
    }
  }

  /** Same seed, same text and tables: the repro contract. */
  @Test def sameSeedReproduces(): Unit = {
    val seed = 4242424242L
    assertEquals(SqlTextGen.genTextCase(new Rng(seed)).toString, SqlTextGen.genTextCase(new Rng(seed)).toString)
  }

  /** The text shrinker reaches a fixpoint and reduces the text. */
  @Test def textShrinkerTerminatesAndReduces(): Unit = {
    val start = SqlTextGen.genTextCase(new Rng(7L))
    var cur = start
    var steps = 0
    var progressed = true
    while (progressed && steps < 5000) {
      val it = Shrinker.textCase(cur)
      progressed = it.hasNext
      if (progressed) { cur = it.next(); steps += 1 }
    }
    assertTrue(s"terminated (steps=$steps)", steps < 5000)
    assertTrue(s"reduced '${start.sql}' -> '${cur.sql}'", cur.sql.length < start.sql.length)
  }
}
