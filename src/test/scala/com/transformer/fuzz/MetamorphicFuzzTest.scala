package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.oracle.{MetaModeDifferential, NoRec, RelEngine, Tlp}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

/** Property-based metamorphic gate over multi-relation SQL — the phase that finds
  * SEMANTIC bugs (wrong answers every execution mode agrees on, which
  * mode-differential is blind to).
  *
  * Three relations are checked, each decided by SQL semantics alone with no
  * reference engine:
  *   - [[Tlp]] — `Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS NULL)` as
  *     multisets, partitioning `Q`'s output via a CTE wrapper (sound for joins,
  *     aggregates, and every other shape — no aggregate decomposition);
  *   - [[NoRec]] — `COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0 END)`;
  *   - [[MetaModeDifferential]] — the multi-relation extension of Plan 02's mode
  *     agreement (joins under spill/metrics/layout variation).
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
      shrink = _ => Iterator.empty, // multi-relation shrinking lands in Shrinker.metaCase
      count = Props.seedCountOr(DefaultMetaSeeds)
    ) { mc => Tlp.check(mc); () }

  @Test def fuzzNoRec(): Unit =
    Props.forAll[NoRecCase](
      name = "norec",
      gen = MetaQueryGen.generateNoRec,
      shrink = _ => Iterator.empty,
      count = Props.seedCountOr(DefaultMetaSeeds * 2) // NoREC is cheap (2 queries)
    ) { nc => NoRec.check(nc); () }

  @Test def fuzzModeAgreement(): Unit =
    Props.forAll[MetaCase](
      name = "meta-mode-differential",
      gen = MetaQueryGen.generate,
      shrink = _ => Iterator.empty,
      count = Props.seedCountOr(DefaultMetaSeeds)
    ) { mc => MetaModeDifferential.check(mc); () }

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
    try { LogicalBuilder.build(s"WITH q AS (${mc.bodySql}) SELECT * FROM q", mc.env.catalog()); None }
    catch { case e: IllegalArgumentException => Some(RelEngine.rejectReason(e)) }

  private def noRecReject(nc: NoRecCase): Option[String] = {
    val cat = new Catalog
    cat.register(nc.relation.name, nc.relation.dataset.singlePartition())
    val sql = s"SELECT COUNT(*) AS c FROM ${nc.relation.name} ${nc.alias} WHERE ${SqlRender.expr(nc.predicate)}"
    try { LogicalBuilder.build(sql, cat); None }
    catch { case e: IllegalArgumentException => Some(RelEngine.rejectReason(e)) }
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
