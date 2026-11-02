package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.oracle.{AggDecomposition, Cert, JoinCommutativity, MetaModeDifferential, NoRec, RelEngine, Tlp,
  TypeCheck, WindowCrossCheck}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Test}

import scala.util.Try

import MetamorphicFuzzTest._

/** Property-based metamorphic gate over multi-relation SQL — the phase that finds
  * SEMANTIC bugs (wrong answers every execution mode agrees on, which
  * mode-differential is blind to).
  *
  * Five relations are checked, each decided by SQL semantics alone with no
  * reference engine:
  *   - [[Tlp]] — `Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS NULL)` as
  *     multisets, partitioning `Q`'s output via a CTE wrapper (sound for joins,
  *     aggregates, and every other shape — no aggregate decomposition);
  *   - [[NoRec]] — `COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0 END)`;
  *   - [[MetaModeDifferential]] — the multi-relation extension of Plan 02's mode
  *     agreement (joins under spill/metrics/layout variation);
  *   - [[JoinCommutativity]] — `A <kind> JOIN B` ≡ `B <flipped kind> JOIN A`, the
  *     only relation that varies which side of a join is the BUILD side;
  *   - [[AggDecomposition]] — one-shot aggregation ≡ group-then-re-aggregate,
  *     which drives the partial/final merge and spill-serde paths a global
  *     aggregate never reaches.
  *
  * And three small relations from the literature (plans/testing/16, W4):
  *   - [[TypeCheck]] — the INT columns stored as BIGINT and as DECIMAL(10,0)
  *     answer as the INT form does, over `MetaQueryGen`'s queries and queries
  *     aimed at each site whose values sit at INT's edges;
  *   - [[WindowCrossCheck]] — two windows in one SELECT equal each alone,
  *     joined on a unique id;
  *   - [[Cert]] — a restriction of a plan gets no larger estimate, nor a
  *     refusal from the nested-loop guard in the query's own join order,
  *     planned over tables that report a size and hold no rows.
  *
  * Each case runs the engine several times, so the default budget is small; scale
  * it for a campaign with `-Dfuzz.seeds=N` / `FUZZ_SEEDS=N`:
  * {{{
  *   bazel test //src/test/scala/com/transformer/fuzz:metamorphic_fuzz_campaign \
  *     --test_tag_filters= --test_env=FUZZ_SEEDS=20000 \
  *     --nocache_test_results --test_timeout=3600
  * }}}
  * A failure prints the seed and the counterexample; reproduce with
  * `FUZZ_SEED=<seed> FUZZ_SEEDS=1`. Shrinking of the multi-relation AST is added
  * by [[Shrinker.metaCase]].
  *
  * A confirmed metamorphic failure is a `src/main` SEMANTIC bug — surface it with
  * the minimized repro; fixing it is a separate change from this harness.
  */
class MetamorphicFuzzTest {

  /** Default per-property budget. Smaller than the single-table fuzzers' because
    * each case executes the engine up to ~6× across modes / partitions; raise via
    * `-Dfuzz.seeds=N`. */
  private val DefaultMetaSeeds: Int = 80

  // This JVM's spill directory is read once (`Spill.baseDir`, under
  // java.io.tmpdir, which Bazel sets to the target's own TEST_TMPDIR), so a
  // test cannot move it. Every operator that spills closes its directory once
  // nothing reads it, so each test leaves none (plans/testing/17).
  @After def leavesNoSpillDirectory(): Unit =
    assertEquals("spill directories left", Nil, Spill.operatorDirsLeft)

  // ---- the properties -----------------------------------------------------

  @Test def fuzzTlp(): Unit =
    Props.forAll[MetaCase](
      name = "tlp",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultMetaSeeds)
    ) { mc => Tlp.check(mc); () }

  @Test def fuzzNoRec(): Unit =
    Props.forAll[NoRecCase](
      name = "norec",
      gen = MetaQueryGen.generateNoRec,
      shrink = Shrinker.noRecCase,
      count = Props.seedCountOr(DefaultMetaSeeds * 2) // NoREC is cheap (2 queries)
    ) { nc => NoRec.check(nc); () }

  @Test def fuzzModeAgreement(): Unit =
    Props.forAll[MetaCase](
      name = "meta-mode-differential",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultMetaSeeds)
    ) { mc => MetaModeDifferential.check(mc); () }

  @Test def fuzzJoinCommutativity(): Unit =
    Props.forAll[MetaCase](
      name = "join-commutativity",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultMetaSeeds * 2) // cheap: two executions per case
    ) { mc => JoinCommutativity.check(mc); () }

  @Test def fuzzAggDecomposition(): Unit =
    Props.forAll[MetaCase](
      name = "agg-decomposition",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      // The heaviest property here — each case runs ~7 decompositions under two
      // modes, i.e. ~28 executions — so the DEFAULT budget is a quarter of the
      // others'. An explicit `-Dfuzz.seeds=N` still scales it for a campaign.
      count = Props.seedCountOr(DefaultMetaSeeds / 4)
    ) { mc => AggDecomposition.check(mc); () }

  // ---- three small relations (plans/testing/16, W4) ------------------------

  /** TypeCheck: the INT columns stored as BIGINT and as DECIMAL(10,0) answer
    * as the INT form does ([[TypeCheck]]). Three runs a case. */
  @Test def fuzzTypeCheck(): Unit =
    Props.forAll[MetaCase](
      name = "type-check",
      gen = MetaQueryGen.generate,
      shrink = Shrinker.metaCase,
      count = Props.seedCountOr(DefaultMetaSeeds * 2)
    ) { mc => TypeCheck.check(mc); () }

  /** TypeCheck over queries aimed at each site ([[TypeCheck.generateTargeted]]),
    * whose INT values sit at the type's edges. */
  @Test def fuzzTypeCheckTargeted(): Unit =
    Props.forAll[TypeCheck.TargetedCase](
      name = "type-check-targeted",
      gen = TypeCheck.generateTargeted,
      shrink = _ => Iterator.empty,
      count = Props.seedCountOr(DefaultMetaSeeds * 4)
    ) { c => typeCheckTargeted(c); () }

  /** Each wider form is checked on a share of cases, and the INT values reach
    * every site the relation names in a share of the cases it held on: a
    * comparison, a join, GROUP BY, DISTINCT, ORDER BY, MIN, MAX, COUNT, SUM,
    * `+`, `-` and `*`, read off the INT form's bound plan, over `MetaQueryGen`'s
    * queries and the targeted ones. Runs each case. */
  @Test def typeCheckReachesEverySiteInEachWiderForm(): Unit = {
    val n = TypeCheckGuardSeeds
    val forms = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val sites = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var withRows = 0
    (0 until n).foreach { seed =>
      val mc = MetaQueryGen.generate(new Rng(TypeCheckGuardBase + seed))
      val tc = TypeCheck.generateTargeted(new Rng(TypeCheckGuardBase + seed))
      Seq((TypeCheck.check(mc), mc.env, mc.query.renderBody), (typeCheckTargeted(tc), tc.env, tc.sql)).foreach {
        case (outcome, env, sql) =>
          outcome.forms.foreach(f => forms(s"${f.to} ${f.kind}") += 1)
          if (outcome.forms.exists(_.kind == "held")) {
            if (outcome.rows > 0) withRows += 1
            TypeCheck.sites(LogicalBuilder.build(sql, env.catalog()).main).foreach(s => sites(s) += 1)
          }
      }
    }
    val report = s"over $n seeds of each generator: forms ${forms.toSeq.sorted.mkString(", ")}; held with rows " +
      s"$withRows; sites " + TypeCheck.Sites.map(s => s"$s ${sites(s)}").mkString(", ")
    println(s"[type-check] $report")
    assertTrue(s"the BIGINT form rarely holds: $report", forms("long held") > TypeCheckFormFloor)
    assertTrue(s"the DECIMAL form rarely holds: $report", forms("decimal(10,0) held") > TypeCheckFormFloor)
    assertTrue(s"an INT rarely overflows where a wider form answers: $report",
      forms("long wider answered") > TypeCheckSiteFloor)
    TypeCheck.Sites.foreach(s => assertTrue(s"an INT rarely reaches $s: $report", sites(s) > TypeCheckSiteFloor))
  }

  /** Window cross_check: two windows in one SELECT answer as each alone,
    * joined on the row id ([[WindowCrossCheck]]). Six runs a case. */
  @Test def fuzzWindowCrossCheck(): Unit =
    Props.forAll[WindowCrossCheck.WindowCase](
      name = "window-cross-check",
      gen = WindowCrossCheck.generate,
      shrink = WindowCrossCheck.shrink,
      count = Props.seedCountOr(DefaultMetaSeeds * 3)
    ) { c => WindowCrossCheck.check(c); () }

  /** Every kind of window is drawn, beside one of another kind and spec, and
    * most cases run. Generation, and a run of each case. */
  @Test def windowCrossCheckDrawsEveryKindOfWindow(): Unit = {
    val n = WindowGuardSeeds
    val kinds = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var samePartitionOtherOrder, otherPartition, ran = 0
    (0 until n).foreach { seed =>
      val c = WindowCrossCheck.generate(new Rng(WindowGuardBase + seed))
      Seq(c.w1, c.w2).foreach(w => kinds(w.kind) += 1)
      if (c.w1.partition == c.w2.partition && c.w1.order != c.w2.order) samePartitionOtherOrder += 1
      if (c.w1.partition != c.w2.partition) otherPartition += 1
      if (WindowCrossCheck.check(c)) ran += 1
    }
    val report = s"over $n seeds: ${kinds.toSeq.sorted.mkString(", ")}; one partitioning, two orders $samePartitionOtherOrder; " +
      s"two partitionings $otherPartition; ran $ran"
    println(s"[window-cross-check] $report")
    WindowCrossCheck.Kinds.foreach(k => assertTrue(s"a window is rarely $k: $report", kinds(k) > WindowKindFloor))
    assertTrue(s"two orders over one partitioning are rare: $report", samePartitionOtherOrder > WindowPairFloor)
    assertTrue(s"two partitionings are rare: $report", otherPartition > WindowPairFloor)
    assertTrue(s"few cases run: $report", ran > n * 9 / 10)
  }

  /** CERT over the join shapes the nested-loop guard sizes: a restriction
    * never gets a larger estimate, but where decision 3 keeps one, nor is it
    * refused by the guard where the query is not ([[Cert]]). Planning only. */
  @Test def fuzzCertOverJoinShapes(): Unit =
    Props.forAll[Seeded[Cert.JoinShape]](
      name = "cert-join-shapes",
      gen = rng => Seeded(Cert.genShape(rng.split()), rng.nextLong()),
      shrink = _ => Iterator.empty,
      count = Props.seedCountOr(DefaultCertSeeds)
    ) { c => Cert.bound(c.value.sql, c.value.catalog).foreach(p => Cert.check(p, new Rng(c.seed), c.value.toString)); () }

  /** CERT over `MetaQueryGen`'s queries, their relations sized across the
    * guard's thresholds. Planning only. */
  @Test def fuzzCertOverGeneratedQueries(): Unit =
    Props.forAll[Seeded[MetaCase]](
      name = "cert-queries",
      gen = rng => Seeded(MetaQueryGen.generate(rng.split()), rng.nextLong()),
      shrink = c => Shrinker.metaCase(c.value).map(v => c.copy(value = v)),
      count = Props.seedCountOr(DefaultCertSeeds)
    ) { c =>
      val rng = new Rng(c.seed)
      val (catalog, sizes) = Cert.sizedCatalog(c.value, rng.split())
      Cert.bound(c.value.bodySql, catalog).foreach(p => Cert.check(p, rng.split(), s"${c.value.bodySql}\n  sizes $sizes"))
      ()
    }

  /** Every restriction is compared on a share of cases, over plans the guard
    * refuses as well as accepts, so its bound is held where it decides. Also
    * counted, for the user: a LEFT or RIGHT join made INNER the estimator
    * sizes larger (decision 3 keeps it), a restriction whose optimized plan
    * is estimated larger, and one the guard refuses only in the order the
    * optimizer chooses for it (both held, plans/testing/16). Planning
    * only. */
  @Test def certReachesEveryRestrictionAndTheGuard(): Unit = {
    val n = CertGuardSeeds
    val compared = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var refused, accepted, innerGrew, optimizedGrew, reordered = 0
    val examples = scala.collection.mutable.LinkedHashMap.empty[String, String]
    (0 until n).foreach { seed =>
      val rng = new Rng(CertGuardBase + seed)
      val shape = Cert.genShape(rng.split())
      val mc = MetaQueryGen.generate(rng.split())
      val (catalog, sizes) = Cert.sizedCatalog(mc, rng.split())
      val plans = Cert.bound(shape.sql, shape.catalog).map(_ -> shape.toString).toSeq ++
        Cert.bound(mc.bodySql, catalog).map(_ -> s"${mc.bodySql}, sizes ${sizes.toSeq.sorted.mkString(", ")}").toSeq
      plans.foreach { case (p, text) =>
        Cert.check(p, rng.split(), text).foreach { c =>
          compared(c.restriction.name) += 1
          if (c.refused) refused += 1 else accepted += 1
          if (c.restriction.isInstanceOf[Cert.Restriction.Inner] && c.restricted.exists(r => c.estimate.exists(r > _)))
            innerGrew += 1
          if (!c.restriction.isInstanceOf[Cert.Restriction.Inner] &&
              c.optimizedRestricted.exists(r => c.optimizedEstimate.exists(r > _))) {
            optimizedGrew += 1
            examples.getOrElseUpdate("estimated larger once optimized", s"${c.restriction}: $text")
          }
          if (c.refusedOnlyReordered) {
            reordered += 1
            examples.getOrElseUpdate("refused only reordered", s"${c.restriction}: $text")
          }
        }
      }
    }
    val report = s"over $n seeds: ${compared.toSeq.sorted.mkString(", ")}; the query refused in $refused, accepted in " +
      s"$accepted; an outer join made INNER estimated larger in $innerGrew; held: estimated larger once optimized in " +
      s"$optimizedGrew, refused only in the order the optimizer chose for the restriction in $reordered" +
      examples.map { case (k, v) => s"\n  e.g. $k: $v" }.mkString
    println(s"[cert] $report")
    Seq("a WHERE conjunct", "a LIMIT", "a LEFT join made INNER", "a RIGHT join made INNER", "a FULL join made LEFT",
      "a FULL join made RIGHT").foreach(r => assertTrue(s"rarely $r: $report", compared(r) > CertRestrictionFloor))
    assertTrue(s"the guard rarely refuses a query compared: $report", refused > CertRestrictionFloor)
  }

  /** Bind-reject-rate report + guard. A rejection is the engine refusing the
    * generator's own multi-relation SQL at bind time — expected to be rare (the
    * generator aims for always-bindable, scope-correct SQL); a spike means the
    * generator regressed. Prints the rate (captured for the PR) plus a per-reason
    * tally, and fails if the rate crosses a generous ceiling. `FUZZ_PROBE_SEEDS=20000`
    * probes a larger block, as a generator change that touches typing or
    * rendering should be. */
  @Test def bindRejectRateIsLow(): Unit = {
    val n = Props.probeSeedsOr(500)
    val reasons = scala.collection.mutable.LinkedHashMap.empty[String, (Int, String)]
    var rejects = 0
    var probes = 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      probes += 1
      tlpBaseReject(mc).foreach { r => rejects += 1; tally(reasons, r, mc.bodySql) }
      val nc = MetaQueryGen.generateNoRec(new Rng((seed + 1000000).toLong))
      probes += 1
      noRecReject(nc).foreach { r => rejects += 1; tally(reasons, r, SqlRender.expr(nc.predicate)) }
      seed += 1
    }
    val rate = rejects.toDouble / probes
    println(f"[metamorphic] bind-reject rate over $probes probes ($n seeds): $rejects rejected = $rate%.4f")
    reasons.toSeq.sortBy(-_._2._1).foreach { case (k, (c, ex)) => println(f"  [$c%4d] $k  e.g. $ex") }
    assertTrue(s"bind-reject rate $rate too high — the generator is emitting malformed SQL", rate < 0.10)
  }

  private def tally(m: scala.collection.mutable.LinkedHashMap[String, (Int, String)], reason: String, ex: String): Unit = {
    val key = reason.take(70)
    val (cnt, e) = m.getOrElse(key, (0, ex))
    m(key) = (cnt + 1, e)
  }

  /** Does the engine accept the TLP base (the CTE-wrapped query)? Parse+bind only. */
  private def tlpBaseReject(mc: MetaCase): Option[String] =
    try { LogicalBuilder.build(mc.query.tlpBase, mc.env.catalog()); None }
    catch { case e: IllegalArgumentException => Some(RelEngine.rejectReason(e)) }

  private def noRecReject(nc: NoRecCase): Option[String] = {
    val cat = new Catalog
    cat.register(nc.relation.name, nc.relation.dataset.singlePartition())
    val sql = s"SELECT COUNT(*) AS c FROM ${nc.relation.name} ${nc.alias} WHERE ${SqlRender.expr(nc.predicate)}"
    try { LogicalBuilder.build(sql, cat); None }
    catch { case e: IllegalArgumentException => Some(RelEngine.rejectReason(e)) }
  }

  /** Coverage guard + report: the generator must actually reach every SQL shape
    * the metamorphic relations target. A shape silently dropping to zero (a
    * generation regression) would make the campaign pass vacuously, so each shape
    * is asserted present over a fixed block of seeds. Prints the tally for the PR.
    * The block is 4000 seeds so the narrowest shape, MIN/MAX over a Boolean
    * column, is counted in dozens; generation only, well under a second. */
  @Test def generatorCoversShapes(): Unit = {
    val n = 4000
    var joins, unions, ctes, windows, invariantWindows, aggregates, tlpWindowKeys, topNs, offsets, placements, boolMinMax = 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      if (mc.query.hasAnyJoin) joins += 1
      if (mc.query.setOp.isDefined) unions += 1
      if (mc.query.ctes.nonEmpty) ctes += 1
      if (mc.query.core.hasWindow) windows += 1
      if (mc.query.hasInvariantWindow && mc.query.isLayoutInvariant) invariantWindows += 1
      if (mc.query.core.isInstanceOf[AggCore]) aggregates += 1
      if (mc.query.hasTopN) topNs += 1
      if (mc.query.hasOffset) offsets += 1
      if (mc.query.hasNullPlacement) placements += 1
      if (hasBooleanMinMax(mc.query)) boolMinMax += 1
      // A window output column actually chosen as the TLP partition key.
      if (mc.query.core.hasWindow && mc.tlp.isDefined &&
        mc.query.core.tlpCandidates.exists { case (nm, _) => mc.tlp.get.toString.contains(nm) }) tlpWindowKeys += 1
      seed += 1
    }
    println(f"[metamorphic] shape coverage over $n seeds: joins=$joins unions=$unions ctes=$ctes " +
      f"windows=$windows invariantWindows=$invariantWindows aggregates=$aggregates " +
      f"tlpOnWindowCol=$tlpWindowKeys topN=$topNs offset=$offsets nullPlacement=$placements boolMinMax=$boolMinMax")
    assertTrue(s"joins=$joins", joins > 200)
    assertTrue(s"unions=$unions", unions > 100)
    assertTrue(s"ctes=$ctes", ctes > 200)
    assertTrue(s"windows=$windows", windows > 100)
    // Windows whose value cannot differ between rows tied on the ORDER BY
    // (RANK/DENSE_RANK, a no-ORDER-BY aggregate window). These are the ONLY
    // window queries the mode-agreement and join-commutativity oracles accept,
    // so WindowExec's cross-layout / spill / sharded coverage is exactly this
    // count. A generator drift back to all-tie-sensitive windows would restore
    // the old blind spot silently.
    assertTrue(s"invariantWindows=$invariantWindows", invariantWindows > 100)
    assertTrue(s"aggregates=$aggregates", aggregates > 200)
    // A generated `limit` renders nothing unless the projection is still totally
    // ordered, so this counts queries that actually carry a LIMIT.
    assertTrue(s"topN=$topNs", topNs > 200)
    // OFFSET rides on the top-n shape (~40% of it), so this separates "rare"
    // from "gone"; offsetIsGeneratedAndObservable checks it actually drops rows.
    assertTrue(s"offset=$offsets", offsets > 80)
    // An explicit NULLS FIRST / NULLS LAST rides on the same shape (~half of it);
    // nullPlacementIsGeneratedAndObservable checks it changes answers.
    assertTrue(s"nullPlacement=$placements", placements > 80)
    // MIN/MAX over a Boolean column: the accumulator-slot shape that returned NULL
    // under spill until the engine was fixed. It was kept OUT of the generator
    // while the bug stood, so an assertion that it is back is what stops the
    // exclusion from silently returning. Narrow shape (~1.7% of seeds, 67 of
    // this block — the column must be Boolean AND draw a MIN/MAX, and Boolean
    // is one payload type of ten), so the bound sits well below to separate
    // "rare" from "gone"; `aggDecompositionCoversEveryColumnType` is the dense
    // guard on it.
    assertTrue(s"boolMinMax=$boolMinMax", boolMinMax > 12)
  }

  /** Whether any arm of `q` takes `MIN`/`MAX` over a `Boolean` column — as an
    * aggregate or as a window function, in the main arm, a UNION arm, or a CTE
    * body. */
  private def hasBooleanMinMax(q: MetaQuery): Boolean = {
    val here = q.core match {
      case a: AggCore => a.aggs.exists {
        case (QueryGen.Min(arg), _) => arg.dataType == DataType.BooleanType
        case (QueryGen.Max(arg), _) => arg.dataType == DataType.BooleanType
        case _ => false
      }
      case p: ProjectCore => p.windows.exists(w =>
        w.dataType == DataType.BooleanType && (w.sql.startsWith("MIN(") || w.sql.startsWith("MAX(")))
    }
    here || q.setOp.exists { case (rhs, _) => hasBooleanMinMax(rhs) } || q.ctes.exists(c => hasBooleanMinMax(c.body))
  }

  /** Coverage guard for the aggregate-decomposition relation. Two things can make
    * it green and worthless, and neither is visible from the property itself:
    * it could start SKIPPING (nothing to check), or it could stop reaching the
    * column types whose accumulator slot differs — `MIN`/`MAX` picks its slot from
    * the type, which is how the `Boolean` slot bug survived. Reads
    * `AggDecomposition.coveredTypes`, which reports what `check` would exercise
    * without running a query, so this stays a generation-only guard. */
  @Test def aggDecompositionCoversEveryColumnType(): Unit = {
    val n = 2000
    val perType = scala.collection.mutable.LinkedHashMap.empty[DataType, Int]
    var skipped = 0
    var seed = 0
    while (seed < n) {
      AggDecomposition.coveredTypes(MetaQueryGen.generate(new Rng(seed.toLong))) match {
        case None => skipped += 1
        case Some(types) => types.foreach(t => perType(t) = perType.getOrElse(t, 0) + 1)
      }
      seed += 1
    }
    println(s"[metamorphic] agg-decomposition over $n seeds: skipped=$skipped types=" +
      perType.toSeq.sortBy(-_._2).map { case (t, c) => s"$t=$c" }.mkString(" "))
    assertEquals("agg-decomposition has nothing to check on some seeds", 0, skipped)
    MetaQueryGen.PayloadTypes.foreach { t =>
      assertTrue(s"agg-decomposition never aggregates a $t column", perType.getOrElse(t, 0) > 20)
    }
  }

  /** The aggregate-decomposition relation reaches `NaN` / `-0.0` / infinite
    * inputs, not just every column TYPE. It is the relation that drives
    * `MinMaxState.merge` — the one-shot side keeps a single accumulator while
    * the grouped side merges one per group across partitions and spill runs —
    * so it is the relation that would have caught `MIN`/`MAX` over a
    * `NaN`-carrying float column being order-dependent. It did not, because
    * `MetaQueryGen`'s payload pool was all-finite: the relation was checking the
    * right thing over inputs that could not break it. This guard is what stops
    * that pool narrowing back. */
  @Test def aggDecompositionReachesIeeeSpecialValues(): Unit = {
    val n = 10000
    var nan, negZero, infinite = 0
    var seed = 0
    while (seed < n) {
      AggDecomposition.specialValueCoverage(MetaQueryGen.generate(new Rng(seed.toLong))).foreach { c =>
        if (c.nan) nan += 1
        if (c.negZero) negZero += 1
        if (c.infinite) infinite += 1
      }
      seed += 1
    }
    println(s"[metamorphic] agg-decomposition IEEE coverage over $n seeds: " +
      s"NaN=$nan -0.0=$negZero infinity=$infinite")
    // Bounds ~3x below the measured rates (252 / 185 / 139 over these 10000
    // seeds): they separate a pool that stopped producing the value from one
    // that merely drifted, and do not pin a rate. The block was 2000 seeds
    // until relations drew ten column types rather than six, which left two
    // float types of ten and these counts under twice their bounds.
    assertTrue(s"agg-decomposition never aggregates a NaN (nan=$nan)", nan > 80)
    assertTrue(s"agg-decomposition never aggregates a -0.0 (negZero=$negZero)", negZero > 60)
    assertTrue(s"agg-decomposition never aggregates an infinity (infinite=$infinite)", infinite > 45)
  }

  /** `RANK` / `DENSE_RANK` peer coverage. The peer test those two apply to their
    * ORDER BY keys is a float-comparison site — it compared with IEEE equality
    * while the sort compared with the total order, so tied `NaN` rows took
    * different ranks — and the shape half alone does not reach it: the corpus
    * must produce a rank window ORDERED BY a float column AND that column must
    * actually carry a `NaN`. The join-commutativity relation caught it at seed
    * 16502, once the payload pool started drawing specials.
    *
    * The seed block is 100000, five campaigns' worth, rather than the 2000 its
    * sibling guards use, because this is the thinnest chain in either corpus: a
    * rank window lands on ~2% of seeds, a float ORDER BY key on ~0.3%, and one
    * carrying a `NaN` on ~0.04%. It was the campaign's 20000 until the corpus
    * widened from six column types to ten, which left a float column fewer
    * draws to win and the `-0.0` count at 3 over those seeds, one above its
    * bound. Generation only (no query runs), so 100000 seeds costs about a
    * second and a half.
    *
    * Also asserts every ordered window fills in `orderCols`, since a shape that
    * left it empty would silently drop out of this tally. */
  @Test def rankWindowsReachIeeeSpecialOrderKeys(): Unit = {
    val n = 100000
    var ranks, floatKeys, nan, negZero, orderedWindows, missingOrderCols = 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      val wins = allWindows(mc.query)
      wins.foreach { case (_, w) =>
        if (w.sql.contains("ORDER BY")) {
          orderedWindows += 1
          if (w.orderCols.isEmpty) missingOrderCols += 1
        }
      }
      val rankWins = wins.filter { case (_, w) => isRank(w) }
      if (rankWins.nonEmpty) ranks += 1
      val keyCells = rankWins.flatMap { case (from, w) =>
        w.orderCols.filter(c => isFloat(c.dataType)).map(c => columnCells(mc.env, from, c))
      }
      if (keyCells.nonEmpty) floatKeys += 1
      if (keyCells.exists(cs => anyCell(cs, java.lang.Double.isNaN))) nan += 1
      if (keyCells.exists(cs => anyCell(cs, d => d == 0.0 && java.lang.Double.compare(d, 0.0) < 0))) negZero += 1
      seed += 1
    }
    println(s"[metamorphic] RANK/DENSE_RANK order-key coverage over $n seeds: " +
      s"rankWindow=$ranks floatOrderKey=$floatKeys NaN=$nan -0.0=$negZero " +
      s"(orderedWindows=$orderedWindows)")
    assertEquals("an ordered window carries no orderCols, so it is invisible to this tally",
      0, missingOrderCols)
    // Bounds ~3x below the measured rates (2045 / 301 / 38 / 27 over these
    // 100000 seeds): they separate a corpus that stopped producing the shape or
    // the value from one that merely drifted, and do not pin a rate.
    assertTrue(s"generator never ranks over any ORDER BY (ranks=$ranks)", ranks > 680)
    assertTrue(s"a rank window never orders by a float column (floatKeys=$floatKeys)", floatKeys > 100)
    assertTrue(s"a rank window's float order key never carries a NaN (nan=$nan)", nan > 12)
    assertTrue(s"a rank window's float order key never carries a -0.0 (negZero=$negZero)", negZero > 9)
  }

  /** TLP partitions on a FLOAT or DOUBLE output column, which it never did
    * while a NaN fell into none of its three partitions, and the rows it
    * partitions hold NaN and -0.0: read by running the TLP base, on the one
    * layout TLP runs it on, for the seeds whose partition is over a float.
    * Since plans/robustness/21 a comparison of two non-NULL floats is TRUE or
    * FALSE, so the partitions must still add up. */
  @Test def tlpPartitionsFloatColumnsHoldingNaNAndNegativeZero(): Unit = {
    val n = 5000
    var shape, nan, negZero, special = 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      val floats = tlpColumns(mc).filter { case (_, t) => isFloat(t) }
      if (floats.nonEmpty) {
        shape += 1
        if (mc.tlp.exists(p => Seq("NaN", "Infinity", "-0.0").exists(p.truePred.contains))) special += 1
        Try {
          val q = RelEngine.execute(mc.env.catalog(), mc.query.tlpBase, ExecutionOptions.Default)
          val idx = floats.map { case (name, _) => q.schema.indexOf(name) }.filter(_ >= 0)
          val rows = RelEngine.collectRows(q)
          if (rows.exists(r => idx.exists(i => isDouble(r(i), java.lang.Double.isNaN)))) nan += 1
          if (rows.exists(r => idx.exists(i => isDouble(r(i), d => d == 0.0 && java.lang.Double.compare(d, 0.0) < 0))))
            negZero += 1
        }
      }
      seed += 1
    }
    println(s"[metamorphic] over $n seeds: TLP partitions a float column in $shape, its rows holding NaN in $nan, " +
      s"-0.0 in $negZero; the partition compares with NaN, -0.0 or an infinity in $special")
    // Measured over these seeds: the shape 1317, NaN 109, -0.0 58, a special
    // partition constant 309; bounds ~3x below. 5000 seeds since the joins,
    // windows and math functions plans/testing/15 added gave TLP more
    // columns to choose from, which left NaN 58 of 3000.
    assertTrue(s"TLP rarely partitions a float column (shape=$shape)", shape > 220)
    assertTrue(s"a float partition's rows rarely hold NaN (nan=$nan)", nan > 32)
    assertTrue(s"a float partition's rows rarely hold -0.0 (negZero=$negZero)", negZero > 15)
    assertTrue(s"a float partition rarely compares with NaN, -0.0 or an infinity (special=$special)", special > 53)
  }

  /** The output columns `mc`'s TLP predicate reads, with their types. */
  private def tlpColumns(mc: MetaCase): Vector[(String, DataType)] = mc.tlp.toVector.flatMap { p =>
    val words = s"${p.truePred} ${p.falsePred}".split("[^A-Za-z0-9_]").toSet
    mc.query.tlpCandidates.filter { case (name, _) => words(name) }
  }

  /** FLOAT, DOUBLE and BOOLEAN join keys, which `MetaQueryGen` never drew, and
    * a float key's NaN and both zeros meeting across a join: a NaN on both
    * sides, and -0.0 on one beside 0.0 on the other, the keys a hash join
    * matched by bits before plans/robustness/21. And float GROUP BY keys, never
    * drawn either, holding NaN and both zeros, which one group must take.
    * Generation only. */
  @Test def floatAndBooleanKeysJoinAndGroupOverNaNAndBothZeros(): Unit = {
    val n = 6000
    var floatJoin, nanMeets, zerosMeet, booleanJoin, floatGroup, groupNaN, groupZeros = 0
    def cellsOf(r: Relation, i: Int): IndexedSeq[Any] = r.dataset.rows.map(_(i))
    def has(cells: IndexedSeq[Any], p: Double => Boolean): Boolean = cells.exists(isDouble(_, p))
    val isNaN: Double => Boolean = java.lang.Double.isNaN
    val isNegZero: Double => Boolean = d => d == 0.0 && java.lang.Double.compare(d, 0.0) < 0
    val isPosZero: Double => Boolean = d => d == 0.0 && java.lang.Double.compare(d, 0.0) == 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      val rels = mc.env.relations
      val keyType = rels.head.schema.fields(rels.head.keyCols.head).dataType
      if (mc.query.hasAnyJoin && isFloat(keyType)) {
        floatJoin += 1
        val keyCells = rels.map(r => r.keyCols.flatMap(cellsOf(r, _)))
        if (keyCells.count(has(_, isNaN)) >= 2) nanMeets += 1
        if (keyCells.indices.exists(i => keyCells.indices.exists(j =>
            i != j && has(keyCells(i), isNegZero) && has(keyCells(j), isPosZero)))) zerosMeet += 1
      }
      if (mc.query.hasAnyJoin && keyType == DataType.BooleanType) booleanJoin += 1
      val groupCells = floatGroupKeys(mc)
      if (groupCells.nonEmpty) {
        floatGroup += 1
        if (groupCells.exists(has(_, isNaN))) groupNaN += 1
        if (groupCells.exists(cs => has(cs, isNegZero) && has(cs, isPosZero))) groupZeros += 1
      }
      seed += 1
    }
    println(s"[metamorphic] over $n seeds: a join on a float key in $floatJoin, NaN on both sides in $nanMeets, " +
      s"-0.0 beside 0.0 in $zerosMeet; on a boolean key in $booleanJoin; a float GROUP BY key in $floatGroup, " +
      s"holding NaN in $groupNaN, both zeros in $groupZeros")
    // Measured over these seeds: a float join 762 (NaN meets NaN 223, the
    // zeros 293), a boolean join 362, a float group key 321 (NaN 79, both
    // zeros 40); bounds ~3x below. 6000 seeds, since drawing text past ASCII
    // left both zeros in a float key 27 of 4000.
    assertTrue(s"a join is rarely on a float key (floatJoin=$floatJoin)", floatJoin > 250)
    assertTrue(s"NaN rarely meets NaN across a join (nanMeets=$nanMeets)", nanMeets > 74)
    assertTrue(s"-0.0 rarely meets 0.0 across a join (zerosMeet=$zerosMeet)", zerosMeet > 97)
    assertTrue(s"a join is rarely on a boolean key (booleanJoin=$booleanJoin)", booleanJoin > 120)
    assertTrue(s"a GROUP BY key is rarely a float (floatGroup=$floatGroup)", floatGroup > 107)
    assertTrue(s"a float GROUP BY key rarely holds NaN (groupNaN=$groupNaN)", groupNaN > 26)
    assertTrue(s"a float GROUP BY key rarely holds both zeros (groupZeros=$groupZeros)", groupZeros > 14)
  }

  /** The cells of each float GROUP BY key of any arm of `mc`'s query, read in
    * the base relation its alias names. */
  private def floatGroupKeys(mc: MetaCase): Vector[IndexedSeq[Any]] = {
    def arm(q: MetaQuery): Vector[IndexedSeq[Any]] = (q.core match {
      case a: AggCore => a.groupKeys.collect { case (c: ColRefExpr, _) if isFloat(c.dataType) =>
        c.name.split('.') match {
          case Array(alias, col) => columnCells(mc.env, q.from, ScopeCol(alias, col, c.dataType))
          case _ => IndexedSeq.empty[Any]
        }
      }
      case _ => Vector.empty
    }) ++ q.setOp.toVector.flatMap { case (rhs, _) => arm(rhs) } ++ q.ctes.flatMap(c => arm(c.body))
    arm(mc.query).filter(_.nonEmpty)
  }

  /** The FROM shapes plans/testing/15 opened: up to six tables, written as
    * `JOIN ... ON`, a comma list or `CROSS JOIN`s; a run of three or more
    * inner tables that `JoinOrder` reorders, which it does only where a table
    * has no equality with the ones before it; and a join with no equality,
    * which the engine pairs row by row. Read off the bound plan:
    * `JoinOrder` over the plan `FilterPushdown` leaves changes it, and the
    * optimized plan holds a join whose condition has no equality between its
    * sides. */
  @Test def joinsReachJoinOrderAndThePairingPath(): Unit = {
    val n = 3000
    val styles = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var fourOrMore, reordered, pairing, outerPairing = 0
    (0 until n).foreach { seed =>
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      val from = mc.query.from
      if (from.joins.nonEmpty) styles(from.style.name) += 1
      if (from.joins.length >= 3) fourOrMore += 1
      MetamorphicFuzzTest.joinFacts(mc).foreach { f =>
        if (f.reorderedRun >= 3) reordered += 1
        if (f.pairing) pairing += 1
        if (f.outerPairing) outerPairing += 1
      }
    }
    println(s"[metamorphic] joins over $n seeds: styles ${styles.toSeq.sorted.mkString(", ")}; four tables or more " +
      s"in $fourOrMore; JoinOrder reorders a run of three or more in $reordered; a join on the pairing path in " +
      s"$pairing, an outer one in $outerPairing")
    // Measured over these seeds: a comma list 534, CROSS JOIN 262 (JOIN ... ON
    // 1505), four tables or more 174, a reordered run of three or more 190, a
    // join on the pairing path 446, an outer one 198; bounds ~3x below.
    assertTrue(s"a comma list is rarely drawn (${styles("a comma list")})", styles("a comma list") > 178)
    assertTrue(s"CROSS JOIN is rarely drawn (${styles("CROSS JOIN")})", styles("CROSS JOIN") > 87)
    assertTrue(s"four tables or more are rarely joined ($fourOrMore)", fourOrMore > 58)
    assertTrue(s"JoinOrder rarely reorders a run of three or more ($reordered)", reordered > 63)
    assertTrue(s"a join rarely takes the pairing path ($pairing)", pairing > 148)
    assertTrue(s"an outer join rarely takes the pairing path ($outerPairing)", outerPairing > 66)
  }

  /** The window shapes plans/testing/15 opened, each in a share of seeds:
    * a ROWS frame with offsets, a RANGE frame with offsets over a number
    * key (which the mode-agreement oracle runs, its frames being sets of
    * values), `LAG` and `LEAD` with an offset and a default, and an
    * expression as an argument and as a key; beside the default frame and
    * the whole partition. Generation only. */
  @Test def windowsReachEveryFrameKind(): Unit = {
    val n = 12000
    val kinds = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    (0 until n).foreach { seed =>
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      val wins = allWindows(mc.query).map(_._2)
      val here = wins.flatMap(MetamorphicFuzzTest.windowKinds).toSet ++
        (if (mc.query.isLayoutInvariant && wins.exists(w => MetamorphicFuzzTest.windowKinds(w)("RANGE offsets")))
          Set("RANGE offsets, layout-invariant") else Set.empty[String])
      here.foreach(k => kinds(k) += 1)
    }
    println(s"[metamorphic] window kinds over $n seeds: ${kinds.toSeq.sorted.mkString(", ")}")
    // Measured over these seeds: a RANGE frame with offsets 139 (layout-
    // invariant 107), a ROWS frame 140, LAG or LEAD with a default 161, an
    // expression argument 167 and key 474, the default frame 877 and the
    // whole partition 564; the bound sits ~3x below the thinnest. 12000
    // seeds, since a window is on a tenth of them.
    for (k <- MetamorphicFuzzTest.WindowKinds :+ "RANGE offsets, layout-invariant")
      assertTrue(s"a window rarely has $k (${kinds(k)})", kinds(k) > 35)
  }

  /** A CTE that reads a CTE, which the engine inlines or materializes inside
    * another, and a UNION whose arms are any core: a join, an aggregate, a
    * window or a computed item. Generation only. */
  @Test def ctesReadCtesAndUnionArmsTakeAnyCore(): Unit = {
    val n = 4000
    var cteOverCte, unionJoin, unionAggregate, unionWindow, unionComputed = 0
    (0 until n).foreach { seed =>
      val q = MetaQueryGen.generate(new Rng(seed.toLong)).query
      val names = q.ctes.map(_.name).toSet
      if (q.ctes.exists(c => names(c.body.from.root.relName))) cteOverCte += 1
      q.setOp.foreach { _ =>
        if (q.from.joins.nonEmpty) unionJoin += 1
        q.core match {
          case _: AggCore => unionAggregate += 1
          case p: ProjectCore =>
            if (p.windows.nonEmpty) unionWindow += 1
            if (p.items.exists(!_._1.isInstanceOf[ColRefExpr])) unionComputed += 1
        }
      }
    }
    println(s"[metamorphic] over $n seeds: a CTE over a CTE in $cteOverCte; a UNION arm with a join in $unionJoin, " +
      s"an aggregate in $unionAggregate, a window in $unionWindow, a computed item in $unionComputed")
    // Measured over these seeds: a CTE over a CTE 507; a UNION arm with a join
    // 395, an aggregate 239, a window 63, a computed item 89; bounds ~3x below.
    assertTrue(s"a CTE rarely reads a CTE ($cteOverCte)", cteOverCte > 169)
    assertTrue(s"a UNION arm rarely joins ($unionJoin)", unionJoin > 131)
    assertTrue(s"a UNION arm is rarely an aggregate ($unionAggregate)", unionAggregate > 79)
    assertTrue(s"a UNION arm rarely has a window ($unionWindow)", unionWindow > 21)
    assertTrue(s"a UNION arm rarely computes an item ($unionComputed)", unionComputed > 29)
  }

  /** The values at their types' edges make queries fail on their data, and
    * each of the three failures they cause, an integer overflow, a decimal
    * past its precision and a date past a TIMESTAMP's range, is raised inside
    * a join, a window, an aggregate and a spill, where the layout relations
    * hold every layout to fail alike (decision 1 of plans/testing/15). A case
    * whose relations hold no edge value cannot fail this way and is not run;
    * each other one runs once with spill on, and the site is the operator on
    * the failure's stack ([[MetamorphicFuzzTest.failureReach]]). */
  @Test def dataFailuresReachAJoinAWindowAnAggregateAndSpill(): Unit = {
    val n = 15000
    val reached = scala.collection.mutable.Map.empty[(String, String), Int].withDefaultValue(0)
    var atEdges, failed = 0
    (0 until n).foreach { seed =>
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      if (MetamorphicFuzzTest.holdsAnEdge(mc.env)) {
        atEdges += 1
        val cells = MetamorphicFuzzTest.failureReach(mc)
        if (cells.nonEmpty) failed += 1
        cells.foreach(c => reached(c) += 1)
      }
    }
    val table = MetamorphicFuzzTest.FailureKinds.map { case (kind, _) =>
      f"  $kind%-17s" + MetamorphicFuzzTest.FailureSites.map { case (site, _) => f" $site=${reached((kind, site))}%-5d" }.mkString
    }.mkString("\n")
    println(s"[metamorphic] over $n seeds, $atEdges hold an edge value and $failed of them fail on their data " +
      s"under spill; by failure and site:\n$table")
    // Measured over these seeds (4854 at the edges, 556 failing):
    //   integer overflow  join 155, window 42, aggregate 140, spill 69
    //   decimal overflow  join 100, window 29, aggregate 72, spill 56
    //   date range        join 105, window 29, aggregate 81, spill 33
    // Bounds ~3x below.
    val floors = Map(
      "integer overflow" -> Seq(51, 14, 46, 23),
      "decimal overflow" -> Seq(33, 9, 24, 18),
      "date range" -> Seq(35, 9, 27, 11))
    for {
      (kind, _) <- MetamorphicFuzzTest.FailureKinds
      ((site, _), floor) <- MetamorphicFuzzTest.FailureSites.zip(floors(kind))
    } assertTrue(s"a $kind rarely reaches a $site (${reached((kind, site))})\n$table", reached((kind, site)) > floor)
  }

  /** Every function the registry binds as a call, `MOD` among them and the
    * clock functions apart (`QueryGen.CalledFunctions`), is drawn by
    * `MetaQueryGen` on a share of seeds, in any arm of a query, so the
    * metamorphic relations reach each one. Generation only. */
  @Test def everyCatalogFunctionIsDrawn(): Unit = {
    val n = 40000
    val drawn = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    (0 until n).foreach { seed =>
      MetamorphicFuzzTest.functionsDrawn(MetaQueryGen.generate(new Rng(seed.toLong)).query).foreach(f => drawn(f) += 1)
    }
    val counts = QueryGen.CalledFunctions.map(e => e.name -> drawn(e.name))
    println(s"[metamorphic] catalog functions over $n seeds: " + counts.map { case (f, c) => s"$f=$c" }.mkString(", "))
    // Measured over these seeds: the rarest are E, PI and RAND, 66 to 71,
    // the other math functions 140 to 650; the floor sits about 3x below.
    counts.foreach { case (f, c) => assertTrue(s"$f is drawn on $c of $n seeds", c > FunctionFloor) }
  }

  private val FunctionFloor = 22

  /** The rows a math function's domain answers NULL over (`SQRT` of a
    * negative, `LN` of zero, `ASIN` past 1: plans/testing/05) reach a TLP
    * partition, which must put them in `p IS NULL`'s, and a GROUP BY key, which
    * must make them one group: a math function of a column is an output
    * column TLP partitions on, whose type is the function's whatever the
    * column's, and a group key. Read off the base column's values by the
    * function's entry in the catalog, so generation only. */
  @Test def domainNullsReachATlpPartitionAndAGroupByKey(): Unit = {
    val n = 20000
    var tlpShape, tlpValue, groupShape, groupValue = 0
    (0 until n).foreach { seed =>
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      val tlpCalls = MetamorphicFuzzTest.domainCalls(mc, tlpOnly = true)
      val groupCalls = MetamorphicFuzzTest.domainCalls(mc, tlpOnly = false)
      if (tlpCalls.nonEmpty) tlpShape += 1
      if (tlpCalls.exists(_._2)) tlpValue += 1
      if (groupCalls.nonEmpty) groupShape += 1
      if (groupCalls.exists(_._2)) groupValue += 1
    }
    println(s"[metamorphic] over $n seeds: TLP partitions a math function's column in $tlpShape, over a row its " +
      s"domain answers NULL in $tlpValue; a GROUP BY key is one in $groupShape, over such a row in $groupValue")
    // Measured over these seeds: TLP over a math function's column 437, a
    // domain's NULL in it 125; a math GROUP BY key 803, a domain's NULL in it
    // 206; bounds ~3x below.
    assertTrue(s"TLP rarely partitions a math function's column (shape=$tlpShape)", tlpShape > 145)
    assertTrue(s"a domain's NULL rarely reaches a TLP partition (value=$tlpValue)", tlpValue > 41)
    assertTrue(s"a GROUP BY key is rarely a math function (shape=$groupShape)", groupShape > 267)
    assertTrue(s"a domain's NULL rarely reaches a GROUP BY key (value=$groupValue)", groupValue > 68)
  }

  /** Each class of the text past ASCII (`TextPool`) reaches each string site
    * of the metamorphic corpus, in any arm of a query: `LENGTH`, `SUBSTRING`,
    * `UPPER`, `LOWER`, `LIKE` and `ILIKE` over a base column holding it or a
    * literal of it, an ORDER BY key (a window's or a top-n's) and a GROUP BY
    * key over a column holding it, and `MIN`/`MAX`, as an aggregate, a window
    * or the decomposition relation's. `MetaQueryGen` drew ASCII alone, and
    * none of the four functions, until plans/testing/15. Generation only. */
  @Test def textReachesEveryStringSite(): Unit = {
    val t = TextPool.tally(150000)(seed => MetamorphicFuzzTest.textSites(MetaQueryGen.generate(new Rng(seed))))
    println(s"[metamorphic] text past ASCII ${t.report}")
    // Measured over these seeds: the thinnest cell is 23 (UPPER over a
    // character from U+E000 to U+FFFF beside one past U+FFFF, the pair that
    // takes two classes in one column), the function cells mostly 50 to 260,
    // ORDER BY and MIN/MAX hundreds; the floor sits about 3x below the
    // thinnest. 150000 seeds, generation only, two or three seconds; 100000
    // left that cell 14 once the edge calls of plans/testing/15 W7 took a
    // share of the items and keys.
    val low = t.below(TextFloor)
    assertTrue(s"text past ASCII rarely reaches these sites (at most $TextFloor seeds):\n  ${low.mkString("\n  ")}\n${t.report}",
      low.isEmpty)
  }

  private val TextFloor = 7

  private def isDouble(v: Any, p: Double => Boolean): Boolean = v match {
    case x: java.lang.Double => p(x.doubleValue)
    case x: java.lang.Float => p(x.doubleValue)
    case _ => false
  }

  private def isRank(w: WinItem): Boolean =
    w.sql.startsWith("RANK(") || w.sql.startsWith("DENSE_RANK(")

  private def isFloat(dt: DataType): Boolean =
    dt == DataType.FloatType || dt == DataType.DoubleType

  private def anyCell(cells: IndexedSeq[Any], p: Double => Boolean): Boolean =
    cells.exists {
      case x: java.lang.Number => p(x.doubleValue)
      case _ => false
    }

  // ---- DATE, TIMESTAMP, BINARY and DECIMAL coverage ----------------------
  //
  // One guard per type, each asserting both halves over the metamorphic corpus:
  // the type is a join key, reaches a comparison, an ORDER BY (a window's or a
  // top-n's), a GROUP BY key, the aggregate-decomposition relation's MIN/MAX
  // and a TLP partition key, and the base-relation column it reads at each
  // (all but TLP, which partitions computed output) holds the values a
  // comparison of the type gets wrong. A DECIMAL also reaches arithmetic, a
  // SUM or AVG, and the decomposition's SUM, over values a DOUBLE adds wrong.
  // `MetaQueryGen` drew none of the four until plan 04. The guards read one
  // shared tally of the generated corpus.
  //
  // Over 30000 seeds, generation only (about a second and a half); the block
  // was the campaign's 20000 until plans/testing/15 gave the join key and the
  // TLP key three more types and drew text past ASCII, which left the four
  // fewer draws:
  //
  //                  date  timestamp  binary  decimal
  //   join key       2301       2293    2222     2244
  //   comparison     3588       3620    3136     4730
  //   ORDER BY       2782       2726    2744     2752
  //   GROUP BY       1208       1218    1194     1143
  //   MIN/MAX        5675       5727    5569     5614
  //   TLP key        2407       2415     534     2831   (a BINARY only against
  //                                                       another BINARY column)
  //
  // and the columns holding each value pair, at join key / comparison / ORDER
  // BY / GROUP BY / MIN/MAX: instants under a millisecond apart 1815 / 1908 /
  // 1226 / 474 / 2442; bytes of one content 2095 / 2077 / 1564 / 579 / 2988;
  // bytes one high bit apart 820 / 782 / 572 / 198 / 1069; a decimal at two
  // scales 1107 / 1432 / 711 / 264 / 1366; decimals of one double 308 / 452 /
  // 245 / 77 / 424. Decimal arithmetic on 2104 seeds, SUM or AVG of a decimal
  // on 1030 and the decomposition's decimal SUM on 5614, reading decimals a
  // double adds wrong on 418, 207 and 1094. Each bound sits about 3x below its
  // count or further.

  import DataGen.ValueTag._
  import MetamorphicFuzzTest.{Tally, typeTally}

  private def tallyMessage(t: Tally): String =
    s"over ${t.seeds} seeds: shapes=${t.shapes.toSeq.sortBy(_._1.toString).mkString(", ")}; " +
      s"values=${t.values.toSeq.sortBy(_._1.toString).mkString(", ")}"

  /** The shape floor at each site but the TLP key, for every one of the four
    * types. */
  private val ShapeFloor =
    Seq("join key" -> 580, "comparison" -> 780, "ORDER BY" -> 730, "GROUP BY" -> 290, "MIN/MAX" -> 1500)

  private def assertShapes(kind: String): Unit = {
    for ((site, floor) <- ShapeFloor)
      assertTrue(s"a $kind rarely reaches $site, ${tallyMessage(typeTally)}", typeTally.shapes((site, kind)) > floor)
    val tlpFloor = if (kind == "binary") 160 else 790
    assertTrue(s"a $kind rarely reaches TLP key, ${tallyMessage(typeTally)}", typeTally.shapes(("TLP key", kind)) > tlpFloor)
  }

  /** `floors` are for the join key, comparison, ORDER BY, GROUP BY and MIN/MAX,
    * in turn. */
  private def assertValues(kind: String, tag: DataGen.ValueTag, floors: Seq[Int]): Unit =
    for (((site, _), floor) <- ShapeFloor.zip(floors))
      assertTrue(s"a $kind at $site rarely holds $tag, ${tallyMessage(typeTally)}", typeTally.values((site, tag)) > floor)

  @Test def datesReachJoinsComparisonsOrderByGroupByMinMaxAndTlp(): Unit = {
    println(s"[metamorphic] type coverage ${tallyMessage(typeTally)}")
    assertShapes("date")
  }

  @Test def timestampsReachEverySiteUnderAMillisecondApart(): Unit = {
    assertShapes("timestamp")
    assertValues("timestamp", InstantsUnderAMillisecondApart, Seq(450, 470, 370, 125, 690))
  }

  @Test def binaryReachesEverySiteWithEqualContentAndHighBitPairs(): Unit = {
    assertShapes("binary")
    assertValues("binary", BytesOfOneContent, Seq(540, 500, 430, 155, 810))
    assertValues("binary", BytesOneHighBitApart, Seq(210, 195, 155, 60, 295))
  }

  @Test def decimalsReachEverySiteAtTwoScalesAndOfOneDouble(): Unit = {
    assertShapes("decimal")
    assertValues("decimal", DecimalAtTwoScales, Seq(280, 285, 210, 79, 380))
    assertValues("decimal", DecimalsOfOneDouble, Seq(70, 74, 65, 15, 110))
  }

  /** Where a decimal is added up: arithmetic, a SUM or AVG, and the
    * aggregate-decomposition relation's SUM, which drives `DecimalSumState`'s
    * merge and spill against the one-shot answer. Each over columns holding
    * values a DOUBLE adds wrong, without which an answer computed through
    * `doubleValue` would pass as exact. */
  @Test def decimalsAreAddedUpOverValuesADoubleAddsWrong(): Unit = {
    for ((site, shapeFloor, valueFloor) <- Seq(("arithmetic", 440, 85), ("SUM/AVG", 240, 36), ("decomposed SUM", 1500, 290))) {
      assertTrue(s"a DECIMAL rarely reaches $site, ${tallyMessage(typeTally)}",
        typeTally.shapes((site, "decimal")) > shapeFloor)
      assertTrue(s"a DECIMAL at $site rarely holds decimals a double adds wrong, ${tallyMessage(typeTally)}",
        typeTally.values((site, DecimalsADoubleAddsWrong)) > valueFloor)
    }
  }

  /** The join-commutativity relation must actually RUN, not skip, on a healthy
    * share of seeds. It has nothing to check for a join-free query and skips a
    * windowed one (whose tie-break legitimately follows the order the join emits
    * rows in), so a generator drifting toward those shapes would leave the
    * property green and vacuous. Prints the verdict split for the PR. */
  @Test def joinCommutativityIsNotVacuous(): Unit = {
    val n = 400
    var held, skipped, rejected = 0
    var seed = 0
    while (seed < n) {
      JoinCommutativity.check(MetaQueryGen.generate(new Rng(seed.toLong))) match {
        case RelEngine.Held => held += 1
        case RelEngine.Skipped => skipped += 1
        case _: RelEngine.Rejected => rejected += 1
      }
      seed += 1
    }
    println(s"[metamorphic] join-commutativity over $n seeds: held=$held skipped=$skipped rejected=$rejected")
    assertTrue(s"relation ran on only $held of $n seeds", held > n / 5)
  }

  /** The multi-relation shrinker produces valid, strictly-reducing candidates and
    * reaches a fixpoint — a machinery check independent of any real failure (the
    * campaign never fails, so this is the only place shrinking is exercised).
    * Greedily follows the first candidate (an always-failing oracle) to a
    * fixpoint and asserts it terminated and reduced the row count. */
  @Test def shrinkerTerminatesAndReduces(): Unit = {
    val start = MetaQueryGen.generate(new Rng(181L))
    val startRows = start.env.relations.map(_.dataset.numRows).sum
    assertTrue("seed has rows to shrink", startRows > 0)
    var cur = start
    var steps = 0
    var progressed = true
    while (progressed && steps < 5000) {
      progressed = false
      val it = Shrinker.metaCase(cur)
      if (it.hasNext) { cur = it.next(); progressed = true; steps += 1 }
    }
    assertTrue(s"terminated (steps=$steps)", steps < 5000)
    val endRows = cur.env.relations.map(_.dataset.numRows).sum
    assertTrue(s"reduced rows $startRows -> $endRows", endRows < startRows)
    // Every emitted candidate must still render to non-empty SQL (no broken AST).
    Shrinker.metaCase(start).take(20).foreach(c => assertTrue(c.query.renderBody.nonEmpty))
  }

  /** Same seed reproduces the exact query + env — the repro contract failure
    * messages rely on. */
  @Test def sameSeedReproduces(): Unit = {
    val seed = 4242424242L
    assertEquals(MetaQueryGen.generate(new Rng(seed)).bodySql, MetaQueryGen.generate(new Rng(seed)).bodySql)
    assertEquals(
      SqlRender.expr(MetaQueryGen.generateNoRec(new Rng(seed)).predicate),
      SqlRender.expr(MetaQueryGen.generateNoRec(new Rng(seed)).predicate))
  }

  // ---- deterministic regressions -----------------------------------------

  private def assertTlpHeld(mc: MetaCase): Unit =
    assertEquals(s"expected TLP to hold for ${mc.bodySql}", RelEngine.Held, Tlp.check(mc))
  private def assertNoRecHeld(nc: NoRecCase): Unit =
    assertEquals(s"expected NoREC to hold for ${SqlRender.expr(nc.predicate)}", RelEngine.Held, NoRec.check(nc))

  private val intInt = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.IntType)))
  private def rel(name: String, schema: Schema, rows: IndexedSeq[Array[Any]], keyCols: Vector[Int] = Vector(0)): Relation =
    Relation(name, DataGen.Dataset(schema, rows), keyCols)
  private def sc(alias: String, schema: Schema, i: Int): ScopeCol =
    ScopeCol(alias, schema.fields(i).name, schema.fields(i).dataType)

  /** LEFT JOIN whose unmatched left rows null-extend the right side; TLP partitions
    * on the right column, so the `IS NULL` partition must recover exactly the
    * null-extended rows. The classic "LEFT JOIN drops the null-extension" catch. */
  @Test def regressionLeftJoinNullExtension(): Unit = {
    val left = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](2, 20), Array[Any](3, 30), Array[Any](null, 40)))
    val right = rel("r1", intInt, IndexedSeq(
      Array[Any](1, 100), Array[Any](1, 101), Array[Any](2, 200)))
    val env = RelEnv(Vector(left, right))
    val t0c0 = sc("t0", intInt, 0); val t1c1 = sc("t1", intInt, 1)
    val from = FromClause(
      FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))),
      Vector(JoinItem(JoinKind.Left, FromLeaf("r1", "t1", Vector(sc("t1", intInt, 0), sc("t1", intInt, 1))),
        BinOpExpr("=", t0c0.ref, sc("t1", intInt, 0).ref, DataType.BooleanType))))
    val core = ProjectCore(Vector((t0c0.ref, "o0"), (t1c1.ref, "o1")), distinct = false)
    val q = MetaQuery(from, None, core)
    assertTlpHeld(MetaCase(env, q, Some(TlpCmpLit("o1", ">", "<=", "100"))))
  }

  /** FULL OUTER JOIN with both-side unmatched rows; TLP partitions on a left key
    * column that is NULL for right-only rows. */
  @Test def regressionFullJoinBothUnmatched(): Unit = {
    val left = rel("r0", intInt, IndexedSeq(Array[Any](1, 10), Array[Any](2, 20)))
    val right = rel("r1", intInt, IndexedSeq(Array[Any](2, 200), Array[Any](3, 300)))
    val env = RelEnv(Vector(left, right))
    val from = FromClause(
      FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))),
      Vector(JoinItem(JoinKind.Full, FromLeaf("r1", "t1", Vector(sc("t1", intInt, 0), sc("t1", intInt, 1))),
        BinOpExpr("=", sc("t0", intInt, 0).ref, sc("t1", intInt, 0).ref, DataType.BooleanType))))
    val core = ProjectCore(Vector((sc("t0", intInt, 0).ref, "o0"), (sc("t1", intInt, 0).ref, "o1")), distinct = false)
    val q = MetaQuery(from, None, core)
    assertTlpHeld(MetaCase(env, q, Some(TlpCmpCols("o0", "=", "<>", "o1"))))
  }

  /** GROUP BY output partitioned by the COUNT aggregate — TLP over an aggregate's
    * OUTPUT rows (sound; no aggregate decomposition). */
  @Test def regressionAggregateOutputPartition(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 11), Array[Any](2, 20), Array[Any](null, 30), Array[Any](2, 21)))
    val env = RelEnv(Vector(r))
    val k = sc("t0", intInt, 0)
    val from = FromClause(FromLeaf("r0", "t0", Vector(k, sc("t0", intInt, 1))), Vector.empty)
    val core = AggCore(Vector((k.ref, "o0")), Vector((QueryGen.CountStar, "o1")), having = None)
    val q = MetaQuery(from, None, core)
    assertTlpHeld(MetaCase(env, q, Some(TlpCmpLit("o1", ">", "<=", "1"))))
  }

  /** `NOT IN (lit, NULL)` — the 3VL `NOT IN`-with-NULL catch: it is never TRUE, so
    * both NoREC counts must be 0 (never the row count). */
  @Test def regressionNotInWithNull(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType)))
    val r = rel("r0", schema, IndexedSeq(Array[Any](1), Array[Any](2), Array[Any](3), Array[Any](null)), Vector(0))
    val pred = InListExpr(sc("t", schema, 0).ref, Seq(LitExpr(1, DataType.IntType), LitExpr(null, DataType.IntType)), negated = true)
    assertNoRecHeld(NoRecCase(r, pred))
  }

  /** NoREC over a plain comparison with NULLs present in the column. */
  @Test def regressionNoRecComparisonWithNulls(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType)))
    val r = rel("r0", schema, IndexedSeq(
      Array[Any](0), Array[Any](1), Array[Any](2), Array[Any](null), Array[Any](3)), Vector(0))
    val pred = BinOpExpr(">", sc("t", schema, 0).ref, LitExpr(1, DataType.IntType), DataType.BooleanType)
    assertNoRecHeld(NoRecCase(r, pred))
  }

  private val oneLong = Schema(Vector(Field("c0", DataType.LongType)))

  /** TLP over a `UNION ALL` (multiset concat): partitioning the concatenated
    * output must recover every row, including duplicates across arms. */
  @Test def regressionTlpOverUnionAll(): Unit = {
    val a = rel("r0", oneLong, IndexedSeq(Array[Any](1L), Array[Any](2L), Array[Any](null)), Vector(0))
    val b = rel("r1", oneLong, IndexedSeq(Array[Any](2L), Array[Any](3L)), Vector(0))
    val env = RelEnv(Vector(a, b))
    def arm(name: String): MetaQuery =
      MetaQuery(FromClause(FromLeaf(name, "t0", Vector(sc("t0", oneLong, 0))), Vector.empty),
        None, ProjectCore(Vector((sc("t0", oneLong, 0).ref, "o0")), distinct = false))
    val union = arm("r0").copy(setOp = Some((arm("r1"), true)))
    assertTlpHeld(MetaCase(env, union, Some(TlpCmpLit("o0", ">", "<=", "1"))))
  }

  /** TLP over a `UNION` (distinct): partitioning the deduped output must recover
    * every distinct row exactly once. */
  @Test def regressionTlpOverUnionDistinct(): Unit = {
    val a = rel("r0", oneLong, IndexedSeq(Array[Any](1L), Array[Any](1L), Array[Any](2L)), Vector(0))
    val b = rel("r1", oneLong, IndexedSeq(Array[Any](2L), Array[Any](2L), Array[Any](3L), Array[Any](null)), Vector(0))
    val env = RelEnv(Vector(a, b))
    def arm(name: String): MetaQuery =
      MetaQuery(FromClause(FromLeaf(name, "t0", Vector(sc("t0", oneLong, 0))), Vector.empty),
        None, ProjectCore(Vector((sc("t0", oneLong, 0).ref, "o0")), distinct = false))
    val union = arm("r0").copy(setOp = Some((arm("r1"), false))) // UNION (dedup)
    assertEquals(RelEngine.Held, MetaModeDifferential.check(MetaCase(env, union, None)))
    assertTlpHeld(MetaCase(env, union, Some(TlpCmpLit("o0", ">=", "<", "2"))))
  }

  /** CTE referenced twice (materialized once) then self-joined; TLP over the
    * materialized output. Exercises the materialize-on-2+-refs path under a
    * semantic relation. */
  @Test def regressionTlpOverCteReferencedTwice(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 11), Array[Any](2, 20), Array[Any](null, 30)), Vector(0))
    val env = RelEnv(Vector(r))
    val cteBody = MetaQuery(
      FromClause(FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))), Vector.empty),
      None, ProjectCore(Vector((sc("t0", intInt, 0).ref, "o0"), (sc("t0", intInt, 1).ref, "o1")), distinct = false))
    val cte = MetaQueryGen.CteDef("w0", cteBody, Vector(0))
    val xo0 = ScopeCol("x", "o0", DataType.IntType); val yo1 = ScopeCol("y", "o1", DataType.IntType)
    val from = FromClause(
      FromLeaf("w0", "x", Vector(xo0, ScopeCol("x", "o1", DataType.IntType))),
      Vector(JoinItem(JoinKind.Inner, FromLeaf("w0", "y", Vector(ScopeCol("y", "o0", DataType.IntType), yo1)),
        BinOpExpr("=", xo0.ref, ScopeCol("y", "o0", DataType.IntType).ref, DataType.BooleanType))))
    val q = MetaQuery(from, None, ProjectCore(Vector((xo0.ref, "o0"), (yo1.ref, "o1")), distinct = false),
      setOp = None, ctes = Vector(cte))
    assertTlpHeld(MetaCase(env, q, Some(TlpCmpLit("o0", ">", "<=", "1"))))
  }

  /** TLP partitioned on a window output column (ROW_NUMBER). The window query is
    * checked on a fixed layout (its multiset is layout-dependent), where the four
    * partition runs are deterministic. */
  @Test def regressionTlpOverWindowColumn(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 20), Array[Any](2, 30), Array[Any](2, 30), Array[Any](null, 40)), Vector(0))
    val env = RelEnv(Vector(r))
    val from = FromClause(FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))), Vector.empty)
    val win = WinItem("ROW_NUMBER() OVER (PARTITION BY t0.c0 ORDER BY t0.c1)", "o1", DataType.LongType,
      tieSensitive = true)
    val core = ProjectCore(Vector((sc("t0", intInt, 0).ref, "o0")), distinct = false, windows = Vector(win))
    val mc = MetaCase(env, MetaQuery(from, None, core), Some(TlpCmpLit("o1", ">", "<=", "1")))
    assertFalse("windowed query is not layout-invariant", mc.query.isLayoutInvariant)
    assertTlpHeld(mc)
  }

  /** Join commutativity over a LEFT JOIN with unmatched rows on BOTH sides: the
    * commuted form is a RIGHT JOIN, so the swap moves both the build side and the
    * direction the null-extension is generated in. Getting one of those wrong drops
    * or invents exactly the unmatched rows this data has. */
  @Test def regressionJoinCommutativityLeftBecomesRight(): Unit = {
    val left = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 11), Array[Any](2, 20), Array[Any](4, 40), Array[Any](null, 50)))
    val right = rel("r1", intInt, IndexedSeq(
      Array[Any](1, 100), Array[Any](2, 200), Array[Any](2, 201), Array[Any](3, 300)))
    val env = RelEnv(Vector(left, right))
    val from = FromClause(
      FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))),
      Vector(JoinItem(JoinKind.Left, FromLeaf("r1", "t1", Vector(sc("t1", intInt, 0), sc("t1", intInt, 1))),
        BinOpExpr("=", sc("t0", intInt, 0).ref, sc("t1", intInt, 0).ref, DataType.BooleanType))))
    val core = ProjectCore(Vector((sc("t0", intInt, 1).ref, "o0"), (sc("t1", intInt, 1).ref, "o1")), distinct = false)
    assertEquals(RelEngine.Held, JoinCommutativity.check(MetaCase(env, MetaQuery(from, None, core), None)))
  }

  /** Aggregate decomposition over a relation whose payload is BOOLEAN — the shape
    * that returned NULL under spill while `MinMaxState` accumulated Booleans in the
    * boxed slot and serialized the long one. The relation aggregates the key column
    * and the LAST field, so the Boolean payload is the one it decomposes. */
  @Test def regressionAggDecompositionBooleanMinMax(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.BooleanType)))
    val r = rel("r0", schema, IndexedSeq(
      Array[Any](1, true), Array[Any](1, false), Array[Any](2, true), Array[Any](2, true),
      Array[Any](3, false), Array[Any](null, true), Array[Any](4, null)), Vector(0))
    val env = RelEnv(Vector(r))
    val from = FromClause(FromLeaf("r0", "t0", Vector(sc("t0", schema, 0), sc("t0", schema, 1))), Vector.empty)
    val core = ProjectCore(Vector((sc("t0", schema, 0).ref, "o0")), distinct = false)
    assertEquals(Some(Set[DataType](DataType.IntType, DataType.BooleanType)),
      AggDecomposition.coveredTypes(MetaCase(env, MetaQuery(from, None, core), None)))
    assertEquals(RelEngine.Held, AggDecomposition.check(MetaCase(env, MetaQuery(from, None, core), None)))
  }

  /** A top-n query whose `LIMIT` cutoff falls between two IDENTICAL rows — the case
    * the total-order condition exists for. Whichever of the tied rows the engine
    * keeps, the result multiset is the same, so both TLP (four executions) and mode
    * agreement (many layouts) must hold. */
  @Test def regressionTopNLimitAcrossTiedRows(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 10), Array[Any](2, 20), Array[Any](3, 30), Array[Any](null, 40)))
    val env = RelEnv(Vector(r))
    val from = FromClause(FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))), Vector.empty)
    val core = ProjectCore(Vector((sc("t0", intInt, 0).ref, "o0"), (sc("t0", intInt, 1).ref, "o1")), distinct = false)
    val q = MetaQuery(from, None, core, limit = Some(2L))
    assertTrue(s"expected a rendered LIMIT, got ${q.renderBody}", q.hasTopN)
    val mc = MetaCase(env, q, Some(TlpCmpLit("o1", ">", "<=", "10")))
    assertTlpHeld(mc)
    assertEquals(RelEngine.Held, MetaModeDifferential.check(mc))
  }

  /** An OFFSET cutoff among IDENTICAL rows, with and without a LIMIT after it:
    * whichever tied row the skip consumes, the kept multiset is the same, so TLP
    * and mode agreement (tiny batches, many partitions, spill) must both hold. */
  @Test def regressionTopNOffsetAcrossTiedRows(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 10), Array[Any](2, 20), Array[Any](3, 30), Array[Any](null, 40)))
    val env = RelEnv(Vector(r))
    val from = FromClause(FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))), Vector.empty)
    val core = ProjectCore(Vector((sc("t0", intInt, 0).ref, "o0"), (sc("t0", intInt, 1).ref, "o1")), distinct = false)
    Seq(MetaQuery(from, None, core, limit = Some(2L), offset = Some(1L)),
        MetaQuery(from, None, core, offset = Some(2L))).foreach { q =>
      assertTrue(s"expected a rendered OFFSET, got ${q.renderBody}", q.hasOffset)
      val mc = MetaCase(env, q, Some(TlpCmpLit("o1", ">", "<=", "10")))
      assertTlpHeld(mc)
      assertEquals(RelEngine.Held, MetaModeDifferential.check(mc))
    }
  }

  /** The OFFSET guard's value half. Generating the clause is not testing it: an
    * OFFSET of 0, or one past the end of the result, keeps everything or nothing,
    * and a cutoff mid-result is the only place it can be wrong. So over the
    * campaign's first block of seeds, run each OFFSET query without its tail and
    * count the ones whose skip lands strictly inside the result. */
  @Test def offsetIsGeneratedAndObservable(): Unit = {
    val n = 3000
    var offsets, observable = 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      if (mc.query.hasOffset) {
        offsets += 1
        val untrimmed = mc.query.copy(limit = None, offset = None).renderBody
        val rows = Try(RelEngine.runRows(mc.env.catalog(), untrimmed, ExecutionOptions.Default).size).getOrElse(-1)
        val skip = mc.query.offset.get
        if (skip > 0 && skip < rows) observable += 1
      }
      seed += 1
    }
    println(s"[metamorphic] OFFSET over $n seeds: rendered=$offsets, skip strictly inside the result=$observable")
    assertTrue(s"offset=$offsets", offsets > 40)
    assertTrue(s"observable=$observable of $offsets", observable > 20)
  }

  /** The NULLS FIRST / NULLS LAST guard's value half: a placement only tests
    * anything when moving the NULLs changes which rows a LIMIT / OFFSET keeps.
    * So run each placed query again with the opposite placement and count the
    * ones whose answer differs. */
  @Test def nullPlacementIsGeneratedAndObservable(): Unit = {
    val n = 3000
    var placed, observable = 0
    var seed = 0
    while (seed < n) {
      val mc = MetaQueryGen.generate(new Rng(seed.toLong))
      if (mc.query.hasNullPlacement) {
        placed += 1
        val flipped = mc.query.copy(nullsFirst = mc.query.nullsFirst.map(!_))
        val differs = Try {
          val cat = mc.env.catalog()
          val a = RelEngine.execute(cat, mc.query.renderBody, ExecutionOptions.Default)
          val b = RelEngine.execute(mc.env.catalog(), flipped.renderBody, ExecutionOptions.Default)
          RowOracle.multisetEquals(a.schema, RelEngine.collectRows(a), RelEngine.collectRows(b)).isDefined
        }.getOrElse(false)
        if (differs) observable += 1
      }
      seed += 1
    }
    println(s"[metamorphic] NULLS FIRST/LAST over $n seeds: rendered=$placed, flipping it changes the answer=$observable")
    assertTrue(s"placed=$placed", placed > 40)
    assertTrue(s"observable=$observable of $placed", observable > 5)
  }

  /** NULLS LAST / NULLS FIRST on a top-n whose cutoff falls among NULL keys:
    * the kept rows move with the placement, and TLP and mode agreement must
    * hold for both. */
  @Test def regressionTopNNullPlacement(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](null, 10), Array[Any](1, 10), Array[Any](null, 20), Array[Any](2, 20), Array[Any](3, 30)))
    val env = RelEnv(Vector(r))
    val from = FromClause(FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))), Vector.empty)
    val core = ProjectCore(Vector((sc("t0", intInt, 0).ref, "o0"), (sc("t0", intInt, 1).ref, "o1")), distinct = false)
    Seq(Some(true), Some(false)).foreach { placement =>
      val q = MetaQuery(from, None, core, limit = Some(2L), nullsFirst = placement)
      assertTrue(s"expected an explicit placement, got ${q.renderBody}", q.hasNullPlacement)
      val mc = MetaCase(env, q, Some(TlpCmpLit("o1", ">", "<=", "10")))
      assertTlpHeld(mc)
      assertEquals(RelEngine.Held, MetaModeDifferential.check(mc))
    }
  }

  /** A generated `limit` renders only while the projection stays totally ordered:
    * add a computed item or a DISTINCT and the `LIMIT` drops out rather than
    * turning a layout-dependent top-n into a false counterexample. This is what
    * makes every shrink of a top-n case sound. */
  @Test def topNLimitDropsWhenTheOrderStopsBeingTotal(): Unit = {
    val from = FromClause(FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))), Vector.empty)
    val bare = Vector[(Expr, String)]((sc("t0", intInt, 0).ref, "o0"))
    assertTrue(MetaQuery(from, None, ProjectCore(bare, distinct = false), limit = Some(2L)).hasTopN)
    assertFalse("DISTINCT rebinds ORDER BY to the projection, so the source name no longer resolves",
      MetaQuery(from, None, ProjectCore(bare, distinct = true), limit = Some(2L)).hasTopN)
    val computed = bare :+ ((BinOpExpr("+", sc("t0", intInt, 1).ref, LitExpr(1, DataType.IntType), DataType.IntType), "o1"))
    assertFalse("a computed item is not an orderable source column",
      MetaQuery(from, None, ProjectCore(computed, distinct = false), limit = Some(2L)).hasTopN)
    val win = WinItem("ROW_NUMBER() OVER (ORDER BY t0.c1)", "o1", DataType.LongType, tieSensitive = true)
    assertFalse("a window output has no source column to order by",
      MetaQuery(from, None, ProjectCore(bare, distinct = false, windows = Vector(win)), limit = Some(2L)).hasTopN)
  }

  /** Mode agreement over a self-join: the same join result multiset across spill /
    * metrics / partition layouts. */
  @Test def regressionSelfJoinModeAgreement(): Unit = {
    val r = rel("r0", intInt, IndexedSeq(
      Array[Any](1, 10), Array[Any](1, 11), Array[Any](2, 20), Array[Any](2, 21), Array[Any](3, 30)))
    val env = RelEnv(Vector(r))
    val from = FromClause(
      FromLeaf("r0", "t0", Vector(sc("t0", intInt, 0), sc("t0", intInt, 1))),
      Vector(JoinItem(JoinKind.Inner, FromLeaf("r0", "t1", Vector(sc("t1", intInt, 0), sc("t1", intInt, 1))),
        BinOpExpr("=", sc("t0", intInt, 0).ref, sc("t1", intInt, 0).ref, DataType.BooleanType))))
    val core = ProjectCore(Vector((sc("t0", intInt, 1).ref, "o0"), (sc("t1", intInt, 1).ref, "o1")), distinct = false)
    val mc = MetaCase(env, MetaQuery(from, None, core), None)
    assertEquals(RelEngine.Held, MetaModeDifferential.check(mc))
  }
}

object MetamorphicFuzzTest {

  /** A generated case and the seed its draws beyond the case (CERT's sizes
    * and restrictions) come from. */
  final case class Seeded[A](value: A, seed: Long) {
    override def toString: String = s"$value\n  seed = $seed"
  }

  /** [[TypeCheck.check]] of a targeted case, whose conditions can fail where
    * the plan the INT form binds to says. */
  def typeCheckTargeted(c: TypeCheck.TargetedCase): TypeCheck.Outcome = {
    val movable =
      try oracle.Eet.movableFailure(LogicalBuilder.build(c.sql, c.env.catalog()))
      catch { case _: IllegalArgumentException => true }
    TypeCheck.check(c.env, c.sql, movable)
  }

  /** TypeCheck's guard block. Measured over 2000 seeds of each generator:
    * the BIGINT form held on 2651 cases and the DECIMAL on 2629, an INT
    * overflow answered as BIGINT on 200; the sites in the cases it held on,
    * the thinnest COUNT 213, MIN 230, MAX 240, SUM 262 and DISTINCT 384.
    * Bounds ~3x below. */
  val TypeCheckGuardSeeds: Int = 2000
  val TypeCheckGuardBase: Long = 910000L
  val TypeCheckFormFloor: Int = 870
  val TypeCheckSiteFloor: Int = 66

  /** Window cross_check's guard block. Measured over 2000 seeds: each kind
    * 522 (a rank) to 607 (ROW_NUMBER), two orders over one partitioning 297,
    * two partitionings 1703, every case run. Bounds ~3x below. */
  val WindowGuardSeeds: Int = 2000
  val WindowGuardBase: Long = 920000L
  val WindowKindFloor: Int = 170
  val WindowPairFloor: Int = 95

  /** CERT plans each case and its restrictions, and runs nothing. Measured
    * over the guard's 3000 seeds: a FULL join made LEFT 984 times and RIGHT
    * 1000, a LEFT join made INNER 3031, a RIGHT one 1984, a LIMIT and a
    * conjunct 6000 each; the query refused in 3279. Bounds ~3x below. */
  val DefaultCertSeeds: Int = 1500
  val CertGuardSeeds: Int = 3000
  val CertGuardBase: Long = 930000L
  val CertRestrictionFloor: Int = 320

  private val SpillOn: ExecutionOptions = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))

  /** The failures on data W7's edge values cause, by the message's start. */
  private[fuzz] val FailureKinds: Seq[(String, scala.util.matching.Regex)] = Seq(
    "integer overflow" -> "^Integer overflow: ".r,
    "decimal overflow" -> "^Decimal overflow: ".r,
    "date range" -> " is past the range of a (?:DATE|TIMESTAMP),".r)

  /** The operators a failure is raised in, by a frame of its stack. */
  private[fuzz] val FailureSites: Seq[(String, StackTraceElement => Boolean)] = Seq(
    "join" -> (f => f.getClassName.contains("JoinExec")),
    "window" -> (f => f.getClassName.contains("WindowExec")),
    "aggregate" -> (f => f.getClassName.contains("AggregateExec") || f.getClassName.endsWith("State")),
    "spill" -> (f => f.getMethodName.toLowerCase(java.util.Locale.ROOT).contains("spill") ||
      f.getClassName.contains("DiskRun") || f.getClassName.contains("Spill")))

  /** Whether a relation of `env` holds a value at its type's edges
    * (`DataGen.Edges`). */
  private[fuzz] def holdsAnEdge(env: RelEnv): Boolean = env.relations.exists { r =>
    r.schema.fields.indices.exists { i =>
      val edges = DataGen.Edges.of(r.schema.fields(i).dataType).toSet
      edges.nonEmpty && r.dataset.rows.exists(row => edges(row(i)))
    }
  }

  /** The (failure, site) pairs `mc`'s body reaches when it runs with spill
    * on: the kind of failure on its data it stops with, and each operator
    * on the stack of the exception that raised it. Empty when it answers or
    * does not bind. */
  private[fuzz] def failureReach(mc: MetaCase): Set[(String, String)] =
    try { RelEngine.runRows(mc.env.catalog(), mc.query.renderBody, SpillOn); Set.empty }
    catch {
      case scala.util.control.NonFatal(e) if oracle.CleanRejection.isDataError(e) =>
        val raised = oracle.CleanRejection.causes(e).filter(c => oracle.CleanRejection.isDataMessage(c.getMessage))
        val frames = raised.flatMap(_.getStackTrace)
        val message = raised.headOption.map(_.getMessage).getOrElse("")
        (for {
          (kind, re) <- FailureKinds if re.findFirstIn(message).isDefined
          (site, at) <- FailureSites if frames.exists(at)
        } yield (kind, site)).toSet
      case scala.util.control.NonFatal(_) => Set.empty
    }

  /** Every window in any arm of `q` (this body, a UNION arm, a CTE body), paired
    * with the FROM clause its `orderCols` resolve against. */
  private def allWindows(q: MetaQuery): Vector[(FromClause, WinItem)] = {
    val here: Vector[(FromClause, WinItem)] = q.core match {
      case p: ProjectCore => p.windows.map(w => (q.from, w))
      case _: AggCore => Vector.empty
    }
    here ++ q.setOp.toVector.flatMap { case (rhs, _) => allWindows(rhs) } ++
      q.ctes.flatMap(c => allWindows(c.body))
  }

  /** The values `c` takes in the base relation its alias resolves to. Empty when
    * the alias names a CTE, whose rows are computed rather than stored — that
    * under-counts coverage rather than over-counting it. */
  private def columnCells(env: RelEnv, from: FromClause, c: ScopeCol): IndexedSeq[Any] = {
    val leaf = (from.root +: from.joins.map(_.leaf)).find(_.alias == c.alias)
    val rel = leaf.flatMap(l => env.relations.find(_.name == l.relName))
    rel match {
      case None => IndexedSeq.empty
      case Some(r) =>
        val i = r.schema.fields.indexWhere(_.name == c.col)
        if (i < 0) IndexedSeq.empty else r.dataset.rows.map(_(i))
    }
  }

  /** How many seeds of a block reach each site with a column of each kind
    * (`shapes`, keyed by site and `ExprGen.kind`), and with a column holding
    * each value pair there (`values`, keyed by site and tag). */
  final case class Tally(seeds: Int, shapes: Map[(String, String), Int], values: Map[(String, DataGen.ValueTag), Int])

  /** A column reference's scope column: `ColRefExpr.name` carries the alias. */
  private def scopeCol(c: ColRefExpr): Option[ScopeCol] = c.name.split('.') match {
    case Array(alias, col) => Some(ScopeCol(alias, col, c.dataType))
    case _ => None
  }

  /** The sites one case reaches with each of the four types, and the cells of
    * the base-relation columns it reads there. */
  private def sites(mc: MetaCase): Seq[(String, String, IndexedSeq[Any])] = {
    val reported = ExprGen.ReportedKinds
    val out = Vector.newBuilder[(String, String, IndexedSeq[Any])]
    def refs(e: Expr, kind: String): Seq[ColRefExpr] = e match {
      case c: ColRefExpr if ExprGen.kind(c.dataType) == kind => Seq(c)
      case other => Shrinker.childrenOf(other).flatMap(refs(_, kind))
    }
    def cellsOf(from: FromClause, cs: Seq[ColRefExpr]): IndexedSeq[Any] =
      cs.flatMap(scopeCol).flatMap(columnCells(mc.env, from, _)).toIndexedSeq

    // The join key: every relation keys on the env's one key type.
    val r0 = mc.env.relations.head
    val keyKind = ExprGen.kind(r0.schema.fields(r0.keyCols.head).dataType)
    if (mc.query.hasAnyJoin && reported(keyKind))
      out += (("join key", keyKind, mc.env.relations.flatMap(r => r.keyCols.flatMap(i => r.dataset.rows.map(_(i))))))

    def arm(q: MetaQuery): Unit = {
      val exprs: Seq[Expr] = q.where.toSeq ++ q.from.joins.map(_.on) ++ (q.core match {
        case p: ProjectCore => p.items.map(_._1)
        case a: AggCore => a.groupKeys.map(_._1) ++ a.having.toSeq
      })
      def comparisons(e: Expr): Unit = {
        e match {
          case BinOpExpr(op, l, r, _) if Seq("=", "<>", "<", "<=", ">", ">=").contains(op) &&
            reported(ExprGen.kind(l.dataType)) =>
            val k = ExprGen.kind(l.dataType)
            out += (("comparison", k, cellsOf(q.from, refs(l, k) ++ refs(r, k))))
          case BinOpExpr(op, l, r, t) if Seq("+", "-", "*", "/", "%").contains(op) && ExprGen.kind(t) == "decimal" =>
            out += (("arithmetic", "decimal", cellsOf(q.from, refs(l, "decimal") ++ refs(r, "decimal"))))
          case _ => ()
        }
        Shrinker.childrenOf(e).foreach(comparisons)
      }
      exprs.foreach(comparisons)
      q.core match {
        case a: AggCore =>
          a.groupKeys.foreach { case (e, _) =>
            val k = ExprGen.kind(e.dataType)
            if (reported(k)) out += (("GROUP BY", k, cellsOf(q.from, refs(e, k))))
          }
          a.aggs.foreach {
            case (QueryGen.Sum(c: ColRefExpr, _), _) if ExprGen.kind(c.dataType) == "decimal" =>
              out += (("SUM/AVG", "decimal", cellsOf(q.from, Seq(c))))
            case (QueryGen.Avg(c: ColRefExpr, _), _) if ExprGen.kind(c.dataType) == "decimal" =>
              out += (("SUM/AVG", "decimal", cellsOf(q.from, Seq(c))))
            case _ => ()
          }
        case _ => ()
      }
      // A top-n's ORDER BY: every projected column, by its source.
      q.totalOrderKeys.foreach { _ =>
        q.core match {
          case p: ProjectCore => p.items.foreach {
            case (c: ColRefExpr, _) if reported(ExprGen.kind(c.dataType)) =>
              out += (("ORDER BY", ExprGen.kind(c.dataType), cellsOf(q.from, Seq(c))))
            case _ => ()
          }
          case _ => ()
        }
      }
      q.setOp.foreach { case (rhs, _) => arm(rhs) }
      q.ctes.foreach(c => arm(c.body))
    }
    arm(mc.query)

    // A window's ORDER BY.
    allWindows(mc.query).foreach { case (from, w) =>
      w.orderCols.filter(c => reported(ExprGen.kind(c.dataType))).foreach { c =>
        out += (("ORDER BY", ExprGen.kind(c.dataType), columnCells(mc.env, from, c)))
      }
    }

    // MIN/MAX through the aggregate-decomposition relation, over its columns,
    // and SUM over the decimal ones.
    AggDecomposition.aggregatedColumns(mc).foreach { case (dt, cells) =>
      if (reported(ExprGen.kind(dt))) out += (("MIN/MAX", ExprGen.kind(dt), cells))
      if (ExprGen.kind(dt) == "decimal") out += (("decomposed SUM", "decimal", cells))
    }

    // The TLP partition key, an output column.
    mc.tlp.foreach { p =>
      val text = Seq(p.truePred, p.falsePred).mkString(" ")
      mc.query.tlpCandidates.foreach { case (name, dt) =>
        if (reported(ExprGen.kind(dt)) && text.split("[^A-Za-z0-9_]").contains(name))
          out += (("TLP key", ExprGen.kind(dt), IndexedSeq.empty))
      }
    }
    out.result()
  }

  /** What the bound plan of `mc`'s body does with its joins: the most tables
    * of a run of inner joins when `JoinOrder` reorders it (0 when it reorders
    * none), and whether the optimized plan holds a join with no equality
    * between its sides, inner or outer. None where the body does not bind. */
  final case class JoinFacts(reorderedRun: Int, pairing: Boolean, outerPairing: Boolean)

  def joinFacts(mc: MetaCase): Option[JoinFacts] = scala.util.Try {
    val built = LogicalBuilder.build(mc.bodySql, mc.env.catalog())
    val pushed = LogicalOptimizer.filterPushdown(built.main)
    def leaves(p: LogicalPlan): Iterator[LogicalPlan] = p match {
      case LogicalJoin(l, r, _, JoinKind.Inner) => leaves(l) ++ leaves(r)
      case other => Iterator(other)
    }
    def runs(p: LogicalPlan): Iterator[Int] = p match {
      case j @ LogicalJoin(_, _, _, JoinKind.Inner) => Iterator(leaves(j).size) ++ leaves(j).flatMap(_.children).flatMap(runs)
      case other => other.children.iterator.flatMap(runs)
    }
    val reordered = if (JoinOrder(pushed) != pushed) runs(pushed).maxOption.getOrElse(0) else 0
    def joins(p: LogicalPlan): Iterator[LogicalJoin] = (p match {
      case j: LogicalJoin => Iterator(j)
      case _ => Iterator.empty
    }) ++ p.children.iterator.flatMap(joins)
    def conjuncts(e: Expr): Seq[Expr] = e match {
      case BinOpExpr("AND", a, b, _) => conjuncts(a) ++ conjuncts(b)
      case other => Seq(other)
    }
    def keyed(j: LogicalJoin): Boolean = {
      val width = j.left.outputSchema.length
      conjuncts(j.condition).exists {
        case BinOpExpr("=", a, b, _) =>
          Set(JoinSideAnalysis.sideOf(a, width), JoinSideAnalysis.sideOf(b, width)) == Set(JoinSide.LeftOnly, JoinSide.RightOnly)
        case _ => false
      }
    }
    val unkeyed = joins(LogicalOptimizer.optimize(built.main)).filterNot(keyed).toVector
    JoinFacts(reordered, unkeyed.nonEmpty, unkeyed.exists(_.kind != JoinKind.Inner))
  }.toOption

  /** The window kinds `windowsReachEveryFrameKind` asserts, by name. */
  val WindowKinds: Seq[String] = Seq("ROWS frame", "RANGE offsets", "default frame", "whole partition",
    "LAG or LEAD with a default", "expression argument", "expression key")

  private val RangeOffset = """RANGE (?:BETWEEN )?[^)]*\d (?:PRECEDING|FOLLOWING)""".r
  private val OffsetDefault = """^(?:LAG|LEAD)\([^,]+, \d+, """.r
  private val ExpressionArgument = """^(?:SUM|AVG|MIN|MAX)\((?:ABS|UPPER)\(""".r
  private val ExpressionKey = """(?:PARTITION BY|ORDER BY) [^)]*(?:ABS\(|UPPER\(|LENGTH\(| \+ 1)""".r

  /** The kinds of [[WindowKinds]] `w` is, read off its SQL. */
  def windowKinds(w: WinItem): Set[String] = {
    val sql = w.sql
    val over = sql.substring(sql.indexOf(" OVER (") + 1)
    Map(
      "ROWS frame" -> over.contains(" ROWS "),
      "RANGE offsets" -> RangeOffset.findFirstIn(over).isDefined,
      "default frame" -> (over.contains("ORDER BY") && !over.contains(" ROWS ") && !over.contains(" RANGE ")),
      "whole partition" -> !over.contains("ORDER BY"),
      "LAG or LEAD with a default" -> OffsetDefault.findFirstIn(sql).isDefined,
      "expression argument" -> ExpressionArgument.findFirstIn(sql).isDefined,
      "expression key" -> ExpressionKey.findFirstIn(over).isDefined
    ).collect { case (k, true) => k }.toSet
  }

  /** The canonical names of the catalog functions `q` calls, in any arm. */
  def functionsDrawn(q: MetaQuery): Set[String] = {
    def names(e: Expr): Iterator[String] = (e match {
      case FuncExpr(name, _, _) => FunctionCatalog.lookup(name).map(_.name).iterator
      case _ => Iterator.empty
    }) ++ Shrinker.childrenOf(e).iterator.flatMap(names)
    val here = (q.where.toSeq ++ q.from.joins.map(_.on) ++ (q.core match {
      case p: ProjectCore => p.items.map(_._1)
      case a: AggCore => a.groupKeys.map(_._1) ++ a.having.toSeq ++ a.aggs.flatMap {
        case (QueryGen.Min(e), _) => Seq(e)
        case (QueryGen.Max(e), _) => Seq(e)
        case (QueryGen.Count(e, _), _) => Seq(e)
        case (QueryGen.Sum(e, _), _) => Seq(e)
        case (QueryGen.Avg(e, _), _) => Seq(e)
        case (QueryGen.Stat(_, e), _) => Seq(e)
        case _ => Seq.empty
      }
    })).iterator.flatMap(names).toSet
    here ++ q.setOp.toSeq.flatMap { case (rhs, _) => functionsDrawn(rhs) } ++ q.ctes.flatMap(c => functionsDrawn(c.body))
  }

  /** The calls of a math function of one base column in `mc`'s main arm that
    * are an output column TLP partitions on (`tlpOnly`) or a GROUP BY key,
    * each with whether some row of the column, beside the call's literal
    * arguments, lies outside the function's domain. */
  def domainCalls(mc: MetaCase, tlpOnly: Boolean): Seq[(FuncExpr, Boolean)] = {
    val q = mc.query
    val partitioned: Set[String] = mc.tlp.toSeq.flatMap { p =>
      s"${p.truePred} ${p.falsePred}".split("[^A-Za-z0-9_]").toSeq
    }.toSet
    val calls: Seq[FuncExpr] = q.core match {
      case p: ProjectCore if tlpOnly => p.items.collect { case (f: FuncExpr, n) if partitioned(n) => f }
      case a: AggCore => a.groupKeys.collect { case (f: FuncExpr, n) if !tlpOnly || partitioned(n) => f }
      case _ => Seq.empty
    }
    calls.flatMap { f =>
      val entry = FunctionCatalog.lookup(f.name).filter(_.domain.nonEmpty)
      val column = f.args.collectFirst { case c: ColRefExpr => c }
      (entry, column) match {
        case (Some(e), Some(c)) =>
          val cells = scopeCol(c).toSeq.flatMap(columnCells(mc.env, q.from, _))
          val outside = cells.exists { v =>
            v != null && {
              val values = f.args.map {
                case LitExpr(x, _) => x
                case _ => v
              }
              !values.contains(null) && e.domain.exists(_.applies(values))
            }
          }
          Some((f, outside))
        case _ => None
      }
    }
  }

  /** The (site, class) pairs `mc` reaches with text past ASCII, as
    * `textReachesEveryStringSite` counts them. */
  def textSites(mc: MetaCase): Set[(String, TextPool.TextClass)] = {
    val out = Set.newBuilder[(String, TextPool.TextClass)]
    def classesOf(cells: IndexedSeq[Any]): Set[TextPool.TextClass] = TextPool.columnClasses(cells)
    def cellsOf(from: FromClause, e: Expr): IndexedSeq[Any] = {
      def refs(x: Expr): Seq[ColRefExpr] = x match {
        case c: ColRefExpr if c.dataType == DataType.StringType => Seq(c)
        case other => Shrinker.childrenOf(other).flatMap(refs)
      }
      refs(e).flatMap(scopeCol).flatMap(columnCells(mc.env, from, _)).toIndexedSeq
    }
    def literals(e: Expr): Set[TextPool.TextClass] = e match {
      case LitExpr(s: String, _) => TextPool.classesOf(s)
      case other => Shrinker.childrenOf(other).flatMap(literals).toSet
    }
    def arm(q: MetaQuery): Unit = {
      def reads(e: Expr): Set[TextPool.TextClass] = classesOf(cellsOf(q.from, e)) ++ literals(e)
      def walk(e: Expr): Unit = {
        e match {
          case FuncExpr(name @ ("LENGTH" | "SUBSTRING" | "UPPER" | "LOWER"), arg +: _, _) =>
            reads(arg).foreach(c => out += ((name, c)))
          case LikeExpr(child, pattern, _, syntax) =>
            val site = if (syntax.caseInsensitive) "ILIKE" else "LIKE"
            (reads(child) ++ literals(pattern)).foreach(c => out += ((site, c)))
          case _ => ()
        }
        Shrinker.childrenOf(e).foreach(walk)
      }
      (q.where.toSeq ++ q.from.joins.map(_.on) ++ (q.core match {
        case p: ProjectCore => p.items.map(_._1)
        case a: AggCore => a.groupKeys.map(_._1) ++ a.having.toSeq ++ a.aggs.flatMap {
          case (QueryGen.Min(e), _) => Seq(e)
          case (QueryGen.Max(e), _) => Seq(e)
          case (QueryGen.Count(e, _), _) => Seq(e)
          case _ => Seq.empty
        }
      })).foreach(walk)
      q.core match {
        case a: AggCore =>
          a.groupKeys.foreach { case (e, _) => if (e.dataType == DataType.StringType) reads(e).foreach(c => out += (("GROUP BY", c))) }
          a.aggs.foreach {
            case (QueryGen.Min(e), _) if e.dataType == DataType.StringType => reads(e).foreach(c => out += (("MIN/MAX", c)))
            case (QueryGen.Max(e), _) if e.dataType == DataType.StringType => reads(e).foreach(c => out += (("MIN/MAX", c)))
            case _ => ()
          }
        case p: ProjectCore =>
          if (q.totalOrderKeys.isDefined) p.items.foreach { case (e, _) =>
            if (e.dataType == DataType.StringType) reads(e).foreach(c => out += (("ORDER BY", c)))
          }
          p.windows.foreach { w =>
            w.orderCols.filter(_.dataType == DataType.StringType)
              .foreach(c => classesOf(columnCells(mc.env, q.from, c)).foreach(k => out += (("ORDER BY", k))))
            if (w.dataType == DataType.StringType && (w.sql.startsWith("MIN(") || w.sql.startsWith("MAX("))) {
              val col = w.sql.drop(4).takeWhile(_ != ')')
              col.split('.') match {
                case Array(alias, name) =>
                  classesOf(columnCells(mc.env, q.from, ScopeCol(alias, name, DataType.StringType)))
                    .foreach(k => out += (("MIN/MAX", k)))
                case _ => ()
              }
            }
          }
      }
      q.setOp.foreach { case (rhs, _) => arm(rhs) }
      q.ctes.foreach(c => arm(c.body))
    }
    arm(mc.query)
    AggDecomposition.aggregatedColumns(mc).foreach { case (dt, cells) =>
      if (dt == DataType.StringType) classesOf(cells).foreach(k => out += (("MIN/MAX", k)))
    }
    out.result()
  }

  /** The tally over the first 30000 seeds, computed once for the four guards. */
  lazy val typeTally: Tally = {
    val n = 30000
    val shapes = scala.collection.mutable.Map.empty[(String, String), Int].withDefaultValue(0)
    val values = scala.collection.mutable.Map.empty[(String, DataGen.ValueTag), Int].withDefaultValue(0)
    var seed = 0
    while (seed < n) {
      val found = sites(MetaQueryGen.generate(new Rng(seed.toLong)))
      found.map { case (site, kind, _) => (site, kind) }.distinct.foreach(k => shapes(k) += 1)
      found.flatMap { case (site, _, cells) => DataGen.valueTags(cells).map(tag => (site, tag)) }
        .distinct.foreach(k => values(k) += 1)
      seed += 1
    }
    Tally(n, shapes.toMap.withDefaultValue(0), values.toMap.withDefaultValue(0))
  }
}
