package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.QueryGen._
import com.transformer.fuzz.oracle.ModeDifferential
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

/** Property-based mode-differential gate over single-table SQL — the generated
  * generalization of the hand-written `*SpillTest` parity checks.
  *
  * [[fuzzModeDifferential]] generates a `(dataset, query)` and asserts every
  * execution mode (spill on/off, metrics on/off, partition/batch layout)
  * produces the same result multiset (see [[ModeDifferential]]). Each case runs
  * the engine several times, so the default budget is smaller than the expr
  * fuzzer's; scale it for a campaign with `-Dfuzz.seeds=N` /
  * `FUZZ_SEEDS=N`:
  * {{{
  *   bazel test //src/test/scala/com/transformer/fuzz:mode_differential_fuzz_campaign \
  *     --test_env=FUZZ_SEEDS=50000 --nocache_test_results --test_timeout=3600
  * }}}
  * A failure prints the seed and the minimized `(dataset, query)`; reproduce with
  * `FUZZ_SEED=<seed> FUZZ_SEEDS=1`.
  *
  * The hand-written regressions lock representative shapes (GROUP BY, DISTINCT,
  * ORDER BY, global + empty-input aggregate, float-aggregate tolerance) so a
  * meaningful set always runs even with `-Dfuzz.seeds=0`. When the fuzzer finds a
  * real divergence, distill it into a named regression here and — if it reflects
  * an operator bug — into the matching `*SpillTest`.
  */
class ModeDifferentialFuzzTest {

  /** Default per-property budget. Smaller than the expr fuzzer's because each
    * case executes the engine once per mode; raise via `-Dfuzz.seeds=N`. */
  private val DefaultModeDiffSeeds: Int = 120

  // Isolate spill files in a temp dir (mirrors the *SpillTest setup) so the
  // 1-byte-threshold spill modes don't litter the default spill location.
  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setSpillDir(): Unit = {
    tmpRoot = Files.createTempDirectory("mode-diff-spill-")
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

  // ---- the property -------------------------------------------------------

  @Test def fuzzModeDifferential(): Unit = {
    Props.forAll[QueryCase](
      name = "mode-differential",
      gen = QueryGen.generate,
      shrink = Shrinker.queryCase,
      count = Props.seedCountOr(DefaultModeDiffSeeds)
    ) { qc =>
      // Rejected (the generator's own un-bindable SQL) is a pass; a real
      // divergence throws AssertionError, which Props minimizes and reports.
      ModeDifferential.check(qc)
      ()
    }
  }

  /** Bind-reject rate report + guard. A rejection is the engine refusing the
    * generator's own SQL — expected to be rare; a spike means the renderer
    * regressed into emitting malformed SQL. Prints the rate (captured for the PR)
    * and fails if it crosses a generous ceiling. */
  /** Bind-reject-rate report and guard. A rejection is the engine refusing the
    * generator's own SQL at bind time — expected to be rare (the renderer aims
    * for always-bindable SQL); a spike means a renderer regression. Prints the
    * rate (captured for the PR) plus a per-reason tally on any rejects, and fails
    * if the rate crosses a generous ceiling. */
  @Test def bindRejectRateIsLow(): Unit = {
    val n = 500
    val reasons = scala.collection.mutable.LinkedHashMap.empty[String, (Int, String)]
    var rejects = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      ModeDifferential.bindReject(qc).foreach { r =>
        rejects += 1
        val key = r.take(70)
        val (cnt, ex) = reasons.getOrElse(key, (0, qc.sql))
        reasons(key) = (cnt + 1, ex)
      }
      seed += 1
    }
    val rate = rejects.toDouble / n
    println(f"[mode-differential] bind-reject rate over $n seeds (base 0): $rejects rejected = $rate%.4f")
    reasons.toSeq.sortBy(-_._2._1).foreach { case (k, (c, ex)) => println(f"  [$c%4d] $k  e.g. $ex") }
    assertTrue(s"bind-reject rate $rate too high — the generator is emitting malformed SQL", rate < 0.25)
  }

  /** Same seed reproduces the exact dataset and SQL — the repro contract failure
    * messages rely on. */
  @Test def sameSeedReproduces(): Unit = {
    val seed = 987654321L
    val a = QueryGen.generate(new Rng(seed))
    val b = QueryGen.generate(new Rng(seed))
    assertEquals("sql", a.sql, b.sql)
    assertEquals("schema", a.dataset.schema, b.dataset.schema)
    assertEquals("numRows", a.dataset.numRows, b.dataset.numRows)
    a.dataset.rows.zip(b.dataset.rows).zipWithIndex.foreach { case ((ra, rb), i) =>
      assertArrayEquals(s"row $i", ra.asInstanceOf[Array[AnyRef]], rb.asInstanceOf[Array[AnyRef]])
    }
  }

  // ---- deterministic regressions -----------------------------------------

  private def assertAgrees(qc: QueryCase): Unit =
    assertEquals(s"expected agreement for ${qc.sql}", ModeDifferential.Agreed, ModeDifferential.check(qc))

  private val intLong = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.LongType)))
  private def c(i: Int, s: Schema): ColRefExpr = ColRefExpr(i, s.fields(i).name, s.fields(i).dataType)

  /** GROUP BY a single int key (LongHashMap fast path) with SUM over a long,
    * including a NULL key — the canonical collapsing-aggregate parity. */
  @Test def regressionGroupBySum(): Unit = {
    val rows: IndexedSeq[Array[Any]] = IndexedSeq(
      Array[Any](1, 10L), Array[Any](1, 20L), Array[Any](2, 30L),
      Array[Any](null, 40L), Array[Any](2, 50L), Array[Any](null, 60L))
    val q = AggregateQuery(
      groupKeys = Vector(c(0, intLong)),
      aggregates = Vector((Sum(c(1, intLong)), "a0"), (CountStar, "a1")),
      where = None, having = None, groupByOrdinals = false, orderBy = Vector.empty)
    assertAgrees(QueryCase(DataGen.Dataset(intLong, rows), q))
  }

  /** Multi-column / string key forces the codec path; DISTINCT dedups identical
    * rows the same way across layouts. */
  @Test def regressionDistinctStringKey(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.StringType), Field("c1", DataType.IntType)))
    val rows: IndexedSeq[Array[Any]] = IndexedSeq(
      Array[Any]("a", 1), Array[Any]("a", 1), Array[Any]("b", 1),
      Array[Any](null, 2), Array[Any]("a", 2), Array[Any](null, 2))
    val q = ProjectQuery(
      distinct = true,
      projection = Vector((c(0, schema): Expr, "c0"), (c(1, schema): Expr, "c1")),
      where = None, orderBy = Vector.empty)
    assertAgrees(QueryCase(DataGen.Dataset(schema, rows), q))
  }

  /** ORDER BY with ties: multiset must match AND each run must be sorted (the
    * unstable K-way merge may reorder tied rows but the key stays monotonic). */
  @Test def regressionOrderByWithTies(): Unit = {
    val rows: IndexedSeq[Array[Any]] = IndexedSeq(
      Array[Any](3, 1L), Array[Any](1, 2L), Array[Any](2, 3L),
      Array[Any](1, 4L), Array[Any](null, 5L), Array[Any](2, 6L))
    val q = ProjectQuery(
      distinct = false,
      projection = Vector((c(0, intLong): Expr, "c0"), (c(1, intLong): Expr, "c1")),
      where = None, orderBy = Vector((c(0, intLong), true)))
    assertAgrees(QueryCase(DataGen.Dataset(intLong, rows), q))
  }

  /** Global aggregate over EMPTY input — the COUNT=0 / NULL-aggregate single
    * row must be identical across modes. */
  @Test def regressionGlobalAggregateOverEmptyInput(): Unit = {
    val q = AggregateQuery(
      groupKeys = Vector.empty,
      aggregates = Vector((CountStar, "a0"), (Sum(c(1, intLong)), "a1"), (Avg(c(0, intLong)), "a2")),
      where = None, having = None, groupByOrdinals = false, orderBy = Vector.empty)
    assertAgrees(QueryCase(DataGen.Dataset(intLong, IndexedSeq.empty), q))
  }

  /** Bare `SELECT COUNT(*)` hits the metadata fast path; every layout reports the
    * same row count. */
  @Test def regressionCountStarMetadataFastPath(): Unit = {
    val rows = IndexedSeq.tabulate(37)(i => Array[Any](i % 4, i.toLong))
    val q = AggregateQuery(
      groupKeys = Vector.empty, aggregates = Vector((CountStar, "a0")),
      where = None, having = None, groupByOrdinals = false, orderBy = Vector.empty)
    assertAgrees(QueryCase(DataGen.Dataset(intLong, rows), q))
  }

  /** Float-input SUM/AVG land in Double columns whose value legitimately differs
    * by reduction order across layouts — the tolerant compare must absorb it
    * while the exact group key still pins identity. Many rows force real
    * reordering between the 1-partition and many-partition layouts. */
  @Test def regressionFloatAggregateTolerance(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.DoubleType)))
    val rng = new scala.util.Random(42L)
    val rows = IndexedSeq.tabulate(250)(i => Array[Any](i % 5, (rng.nextInt(1000) + rng.nextDouble())))
    val q = AggregateQuery(
      groupKeys = Vector(c(0, schema)),
      aggregates = Vector((Sum(c(1, schema)), "a0"), (Avg(c(1, schema)), "a1")),
      where = None, having = Some(HavingSpec(HavingCountStar, ">", "0")),
      groupByOrdinals = false, orderBy = Vector((c(0, schema), true)))
    assertAgrees(QueryCase(DataGen.Dataset(schema, rows), q))
  }

  // ---- comparator unit checks (guard RowOracle.multisetEquals) -------------

  @Test def multisetEqualsIgnoresRowOrder(): Unit = {
    val s = intLong
    val a = Vector(Array[Any](1, 10L), Array[Any](2, 20L))
    val b = Vector(Array[Any](2, 20L), Array[Any](1, 10L))
    assertEquals(None, RowOracle.multisetEquals(s, a, b))
  }

  @Test def multisetEqualsTolerantOnDoubleExactElsewhere(): Unit = {
    val s = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.DoubleType)))
    val a = Vector(Array[Any](1, 1.0), Array[Any](2, 2.0))
    val withinTol = Vector(Array[Any](1, 1.0 + 1e-12), Array[Any](2, 2.0 - 1e-12))
    assertEquals(None, RowOracle.multisetEquals(s, a, withinTol))
    // A gross divergence on the Double column must still be caught.
    val outsideTol = Vector(Array[Any](1, 1.0), Array[Any](2, 2.5))
    assertTrue(RowOracle.multisetEquals(s, a, outsideTol).isDefined)
    // A wrong exact (group) key is caught regardless of the Double tolerance.
    val wrongKey = Vector(Array[Any](1, 1.0), Array[Any](3, 2.0))
    assertTrue(RowOracle.multisetEquals(s, a, wrongKey).isDefined)
  }

  @Test def multisetEqualsCatchesCountAndNullDifferences(): Unit = {
    val s = intLong
    val a = Vector(Array[Any](1, 10L), Array[Any](1, 10L))
    val fewer = Vector(Array[Any](1, 10L))
    assertTrue(RowOracle.multisetEquals(s, a, fewer).isDefined)
    val nullVsValue = Vector(Array[Any](null, 10L), Array[Any](1, 10L))
    assertTrue(RowOracle.multisetEquals(s, a, nullVsValue).isDefined)
  }
}
