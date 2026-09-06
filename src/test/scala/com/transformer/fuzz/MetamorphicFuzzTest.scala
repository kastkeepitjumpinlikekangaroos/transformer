package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.oracle.{AggDecomposition, JoinCommutativity, MetaModeDifferential, NoRec, RelEngine, Tlp}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

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

  // Isolate spill files in a temp dir (the spill-on mode flushes at a 1-byte
  // threshold) so the default spill location is never littered.
  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setSpillDir(): Unit = {
    tmpRoot = Files.createTempDirectory("metamorphic-spill-")
    System.setProperty(Spill.SpillDirProperty, tmpRoot.resolve("transformer-spill").toString)
  }

  @After def restoreSpillDir(): Unit = {
    if (originalDirProp == null) System.clearProperty(Spill.SpillDirProperty)
    else System.setProperty(Spill.SpillDirProperty, originalDirProp)
    deleteRecursively(tmpRoot)
  }

  private def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }

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

  /** Bind-reject-rate report + guard. A rejection is the engine refusing the
    * generator's own multi-relation SQL at bind time — expected to be rare (the
    * generator aims for always-bindable, scope-correct SQL); a spike means the
    * generator regressed. Prints the rate (captured for the PR) plus a per-reason
    * tally, and fails if the rate crosses a generous ceiling. */
  @Test def bindRejectRateIsLow(): Unit = {
    val n = 500
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
    * is asserted present over a fixed block of seeds. Prints the tally for the PR. */
  @Test def generatorCoversShapes(): Unit = {
    val n = 2000
    var joins, unions, ctes, windows, invariantWindows, aggregates, tlpWindowKeys, topNs, boolMinMax = 0
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
      if (hasBooleanMinMax(mc.query)) boolMinMax += 1
      // A window output column actually chosen as the TLP partition key.
      if (mc.query.core.hasWindow && mc.tlp.isDefined &&
        mc.query.core.tlpCandidates.exists { case (nm, _) => mc.tlp.get.toString.contains(nm) }) tlpWindowKeys += 1
      seed += 1
    }
    println(f"[metamorphic] shape coverage over $n seeds: joins=$joins unions=$unions ctes=$ctes " +
      f"windows=$windows invariantWindows=$invariantWindows aggregates=$aggregates " +
      f"tlpOnWindowCol=$tlpWindowKeys topN=$topNs boolMinMax=$boolMinMax")
    assertTrue(s"joins=$joins", joins > 100)
    assertTrue(s"unions=$unions", unions > 50)
    assertTrue(s"ctes=$ctes", ctes > 100)
    assertTrue(s"windows=$windows", windows > 50)
    // Windows whose value cannot differ between rows tied on the ORDER BY
    // (RANK/DENSE_RANK, a no-ORDER-BY aggregate window). These are the ONLY
    // window queries the mode-agreement and join-commutativity oracles accept,
    // so WindowExec's cross-layout / spill / sharded coverage is exactly this
    // count. A generator drift back to all-tie-sensitive windows would restore
    // the old blind spot silently.
    assertTrue(s"invariantWindows=$invariantWindows", invariantWindows > 50)
    assertTrue(s"aggregates=$aggregates", aggregates > 100)
    // A generated `limit` renders nothing unless the projection is still totally
    // ordered, so this counts queries that actually carry a LIMIT.
    assertTrue(s"topN=$topNs", topNs > 100)
    // MIN/MAX over a Boolean column: the accumulator-slot shape that returned NULL
    // under spill until the engine was fixed. It was kept OUT of the generator
    // while the bug stood, so an assertion that it is back is what stops the
    // exclusion from silently returning. Narrow shape (~1.4% of seeds — the column
    // must be Boolean AND draw a MIN/MAX), so the bound separates "rare" from
    // "gone"; `aggDecompositionCoversEveryColumnType` is the dense guard on it.
    assertTrue(s"boolMinMax=$boolMinMax", boolMinMax > 10)
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
