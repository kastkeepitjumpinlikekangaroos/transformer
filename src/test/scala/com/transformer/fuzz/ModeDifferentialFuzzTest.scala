package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.QueryGen._
import com.transformer.fuzz.oracle.{ExactIntegers, KeyEquality, ModeDifferential, RelEngine, WindowFrames}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.util.Try

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

  /** `SUM` over BIGINT values at the edges an integer sum overflows past is
    * the exact sum, or an error when that does not fit a BIGINT, the same in
    * every layout (`oracle/ExactIntegers.checkSums`): the sum used to wrap
    * round, alike in every mode, which the agreement property cannot see, and
    * a SUM that checked its partial sums would fail in one layout and not in
    * another. */
  @Test def fuzzExactIntegerSums(): Unit = {
    Props.forAll[DataGen.Dataset](
      name = "exact-integer-sums",
      gen = DataGen.generateSumData,
      shrink = d => d.rows.indices.reverseIterator.map(i => d.copy(rows = d.rows.patch(i, Nil, 1))),
      count = Props.seedCountOr(DefaultModeDiffSeeds)
    ) { d =>
      ExactIntegers.checkSums(d)
    }
  }

  /** Coverage guard for [[fuzzExactIntegerSums]]: datasets with a sum that
    * does not fit, and with one that fits only because a later value brings a
    * running sum back inside the range (which a SUM that checked each partial
    * sum would refuse in some layouts), must both be common. */
  @Test def exactIntegerSumsReachBothSidesOfTheEdge(): Unit = {
    val n = 4000
    var overflows, comesBack = 0
    (0 until n).foreach { seed =>
      val cov = ExactIntegers.sumCoverage(DataGen.generateSumData(new Rng(seed.toLong)))
      if (cov.overflows) overflows += 1
      if (cov.fitsOnlyAtTheEnd) comesBack += 1
    }
    println(f"[exact-integer-sums] over $n seeds: a sum overflows=$overflows, a sum fits only at the end=$comesBack")
    assertTrue(s"sums rarely overflow (overflows=$overflows)", overflows > 800)
    assertTrue(s"sums rarely come back inside the range (comesBack=$comesBack)", comesBack > 400)
  }

  /** A window aggregate under a random ROWS or RANGE frame, or the default
    * one, is the aggregate over the frame SQL defines, worked out by the
    * harness as a set, in every layout (`oracle/WindowFrames`). RANGE frames
    * ran as ROWS in every layout alike, which the agreement property cannot
    * see (plans/robustness/20). */
  @Test def fuzzWindowFrames(): Unit = {
    Props.forAll[QueryGen.WindowFrameCase](
      name = "window-frames",
      gen = QueryGen.generateWindowFrameCase,
      shrink = c => c.dataset.rows.indices.reverseIterator.map(i =>
        c.copy(dataset = c.dataset.copy(rows = c.dataset.rows.patch(i, Nil, 1)))),
      count = Props.seedCountOr(DefaultModeDiffSeeds)
    ) { c =>
      WindowFrames.check(c)
    }
  }

  /** Coverage guard for [[fuzzWindowFrames]]: a RANGE frame, or the default
    * one, whose frames by value differ from what its offsets would select by
    * position must be common, or the property cannot tell RANGE from ROWS. */
  @Test def windowFramesReachWhatRowsGetsWrong(): Unit = {
    val n = 2000
    var range, differs = 0
    (0 until n).foreach { seed =>
      val c = QueryGen.generateWindowFrameCase(new Rng(seed.toLong))
      if (c.order.isDefined && !c.frame.exists(_.rows)) range += 1
      if (WindowFrames.rangeDiffersFromRows(c)) differs += 1
    }
    println(f"[window-frames] over $n seeds: RANGE or default frame with an ORDER BY=$range, differs from ROWS=$differs")
    assertTrue(s"RANGE frames rarely differ from ROWS (differs=$differs of range=$range)", differs > 300)
  }

  /** Every operator that matches, groups or ranks keys agrees with `=` over
    * float, decimal and integer keys that carry NaN, both zeros, the
    * infinities and one decimal at several scales, in every layout
    * (`oracle/KeyEquality`): a hash join matched NaN to NaN and missed -0.0 to
    * 0.0, which the same predicate as a filter did the other way round
    * (plans/robustness/21). */
  @Test def fuzzKeyEquality(): Unit = {
    Props.forAll[DataGen.KeyCase](
      name = "key-equality",
      gen = DataGen.generateKeyCase,
      shrink = c => c.l.rows.indices.reverseIterator.map(i => c.copy(l = c.l.copy(rows = c.l.rows.patch(i, Nil, 1)))) ++
        c.r.rows.indices.reverseIterator.map(i => c.copy(r = c.r.copy(rows = c.r.rows.patch(i, Nil, 1)))),
      // Eleven queries in each of seven layouts per case, so a smaller default
      // budget than the other properties here; a campaign raises it.
      count = Props.seedCountOr(40)
    ) { c =>
      KeyEquality.check(c)
    }
  }

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

  /** Shape-coverage guard over the generated corpus. A generator can silently
    * stop reaching a shape (a mis-mapped weight table, a predicate that never
    * fires) and the property stays green while testing far less than it claims,
    * so the shapes the modes actually differ on are asserted to be reached:
    * a `LIMIT` (the `LocalLimitExec` / `GlobalLimitExec` pair), and columns
    * across the null-density spectrum — no NULLs, some NULLs, all NULLs — since
    * an all-NULL column collapses every GROUP BY to one group and tests little. */
  @Test def generatedCorpusCoversTheInterestingShapes(): Unit = {
    val n = 500
    var withLimit = 0
    var withOffset = 0
    var withPlacement = 0
    var withDistinctAgg = 0
    var withBoolMinMax = 0
    var noNullCols = 0
    var someNullCols = 0
    var allNullCols = 0
    var totalCols = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      if (qc.sql.contains(" LIMIT ")) withLimit += 1
      if (qc.sql.contains(" OFFSET ")) withOffset += 1
      if (qc.sql.contains(" NULLS ")) withPlacement += 1
      if (qc.sql.contains("SUM(DISTINCT") || qc.sql.contains("AVG(DISTINCT")) withDistinctAgg += 1
      if (hasBooleanMinMax(qc.query)) withBoolMinMax += 1
      val ds = qc.dataset
      if (ds.numRows > 0) {
        ds.schema.fields.indices.foreach { c =>
          val nulls = ds.rows.count(_(c) == null)
          totalCols += 1
          if (nulls == 0) noNullCols += 1
          else if (nulls == ds.numRows) allNullCols += 1
          else someNullCols += 1
        }
      }
      seed += 1
    }
    println(f"[mode-differential] over $n seeds: LIMIT in $withLimit; OFFSET in $withOffset; " +
      f"NULLS FIRST/LAST in $withPlacement; SUM/AVG(DISTINCT) in $withDistinctAgg; MIN/MAX over a " +
      f"Boolean column in $withBoolMinMax; columns " +
      f"no-null=$noNullCols some-null=$someNullCols all-null=$allNullCols of $totalCols")
    assertTrue(s"no LIMIT query generated over $n seeds", withLimit >= n / 20)
    // OFFSET rides on the top-n shape, so the bound separates "rare" from
    // "gone"; offsetIsGeneratedAndObservable checks that it actually drops rows.
    assertTrue(s"no OFFSET query generated over $n seeds", withOffset >= n / 50)
    assertTrue(s"no NULLS FIRST / NULLS LAST generated over $n seeds", withPlacement >= n / 20)
    assertTrue(s"no SUM/AVG(DISTINCT) generated over $n seeds", withDistinctAgg >= n / 20)
    // This fuzzer found `MIN`/`MAX` over a Boolean column returning NULL under
    // spill, and the shape was then GATED OUT of the generator until the engine was
    // fixed. Asserting it is back is what keeps that exclusion from returning
    // unnoticed and re-hiding an accumulator-slot bug. It is a narrow shape (an
    // aggregate query, a MIN/MAX among its aggregates, a Boolean column drawn for
    // it) and lands on ~3.5% of seeds, so the bound only has to separate "rare"
    // from "gone".
    assertTrue(s"no MIN/MAX over a Boolean column generated over $n seeds", withBoolMinMax >= n / 100)
    assertTrue("no all-non-null column generated", noNullCols >= totalCols / 10)
    assertTrue("no partially-NULL column generated", someNullCols >= totalCols / 10)
    assertTrue("no all-NULL column generated", allNullCols > 0)
    // The all-NULL density is the deliberate rarity — a corpus where it
    // dominates is a mis-mapped weight table, not a stronger fuzzer.
    assertTrue(s"all-NULL columns dominate the corpus ($allNullCols of $totalCols)",
      allNullCols < totalCols / 4)
  }

  /** The corpus reaches `MIN`/`MAX` over a float column that actually CARRIES a
    * `NaN`, an infinity or a `-0.0` — the input on which `MinMaxState` used to
    * return a different extremum depending on row order, partition fan-out and
    * spill flush order. Both halves have to hold: `QueryGen` has to build the
    * aggregate over a float column (~4% of seeds — the shape half, which was
    * always there), and `DataGen` has to put a special value in that column's
    * domain AND have a row draw it (~0.35-0.55% — the value half, which was
    * missing for as long as the bug lived). The layout-differential property
    * here is exactly the one that would have caught the bug; it ran the whole
    * time over a corpus that could not produce the input.
    *
    * 10000 seeds rather than the 500 the shape guard above uses: the joint rate
    * is under 1%, and fell when columns widened from six types to ten (two of
    * ten are floats, not two of six), so 2000 seeds left the infinity count at
    * 3, too few to tell "rare" from "gone". Generation only — no query runs
    * here. Measured over these 10000 seeds: the shape 421, NaN 55, -0.0 52, an
    * infinity 35; each bound sits ~3x below. */
  @Test def generatorReachesMinMaxOverIeeeSpecialValues(): Unit = {
    val n = 10000
    var shape, nan, negZero, infinite = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      val cols = floatMinMaxColumns(qc.query)
      if (cols.nonEmpty) shape += 1
      def aggregatedCellExists(p: Double => Boolean): Boolean =
        cols.exists(c => qc.dataset.rows.exists(_(c) match {
          case x: java.lang.Number => p(x.doubleValue)
          case _ => false
        }))
      if (aggregatedCellExists(java.lang.Double.isNaN)) nan += 1
      if (aggregatedCellExists(d => d == 0.0 && java.lang.Double.compare(d, 0.0) < 0)) negZero += 1
      if (aggregatedCellExists(_.isInfinite)) infinite += 1
      seed += 1
    }
    println(s"[mode-differential] over $n seeds: MIN/MAX over a float column in $shape " +
      s"(carrying NaN=$nan, -0.0=$negZero, an infinity=$infinite)")
    assertTrue(s"no MIN/MAX over a float column generated (shape=$shape)", shape > 140)
    // Each bound sits ~3x below the measured rate, so it separates a corpus that
    // stopped producing the value from one that merely drifted.
    assertTrue(s"MIN/MAX never sees a NaN (nan=$nan)", nan > 18)
    assertTrue(s"MIN/MAX never sees a -0.0 (negZero=$negZero)", negZero > 17)
    assertTrue(s"MIN/MAX never sees an infinity (infinite=$infinite)", infinite > 11)
  }

  /** The shape the generator kept out of a branch until plans/robustness/09
    * landed, and the value that broke it: decimal arithmetic as a value of a
    * CASE, COALESCE or IF beside a value of another DECIMAL type, and a row on
    * which the list answers with more fractional digits than its first
    * value's type keeps. The binder used to declare such a list as its first
    * value's type and keep the answer as it was, which a spill then refused
    * to write. Both halves are read off what the binder builds: the shape
    * from the values under each list's CASTs, the value by evaluating the
    * list over the dataset's rows. */
  @Test def decimalArithmeticReachesBranches(): Unit = {
    val n = 6000
    var shape, value = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      val view = qc.dataset.singlePartition()
      val catalog = new Catalog
      catalog.register(QueryGen.ViewName, view)
      Try(LogicalBuilder.build(qc.sql, catalog)).toOption.foreach { built =>
        val lists = oracle.BoundPlan.exprs(built.main).flatMap(e => oracle.BoundPlan.decimalArithmeticBeside(e).map((e, _))).toSeq
        if (lists.nonEmpty) shape += 1
        val batches = (0 until view.numPartitions).flatMap(view.readPartition(_).toSeq)
        if (lists.exists { case (e, first) => batches.exists(oracle.BoundPlan.outgrows(e, first, _)) }) value += 1
      }
      seed += 1
    }
    println(s"[mode-differential] over $n seeds: decimal arithmetic beside another DECIMAL in a branch list " +
      s"in $shape, with more fractional digits than the first value's type in $value")
    // Measured over these seeds: the shape 103, the value 17. Each bound sits
    // ~3x below; 6000 seeds rather than 4000 keep the value's bound off the
    // handful where a drift would cross it.
    assertTrue(s"decimal arithmetic rarely meets another DECIMAL in a branch list (shape=$shape)", shape > 34)
    assertTrue(s"its answers rarely outgrow the first value's scale (value=$value)", value > 5)
  }

  /** A `ROUND` scale or a `SUBSTRING` position or length that is NULL on a
    * row whose value is not, which the generator kept out while a NULL one
    * threw a `NullPointerException` (plans/robustness/11). Read off what the
    * binder builds: the shape from the calls that have one, the value by
    * evaluating them over the dataset's rows. */
  @Test def nullScalesAndPositionsReachTheirFunctions(): Unit = {
    val n = 6000
    var shape, value = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      val view = qc.dataset.singlePartition()
      val catalog = new Catalog
      catalog.register(QueryGen.ViewName, view)
      Try(LogicalBuilder.build(qc.sql, catalog)).toOption.foreach { built =>
        val calls = oracle.BoundPlan.exprs(built.main).collect {
          case f @ FuncExpr("ROUND" | "SUBSTRING", _ +: _ +: _, _) => f
        }.toSeq
        if (calls.nonEmpty) shape += 1
        val batches = (0 until view.numPartitions).flatMap(view.readPartition(_).toSeq)
        if (calls.exists(f => batches.exists(nullBeside(f, _)))) value += 1
      }
      seed += 1
    }
    println(s"[mode-differential] over $n seeds: a ROUND scale or SUBSTRING position or length in $shape, " +
      s"NULL beside a value in $value")
    // Measured over these seeds: the shape 373, the value 108; bounds ~3x below.
    assertTrue(s"a scale, position or length is rarely generated (shape=$shape)", shape > 120)
    assertTrue(s"a NULL one rarely meets a value (value=$value)", value > 35)
  }

  /** Whether `f`'s first argument is a value and another is NULL on some row
    * of `batch`. Only over columns `batch` has, so a call that reads another
    * schema (an aggregate's output) is not evaluated. */
  private def nullBeside(f: FuncExpr, batch: ColumnarBatch): Boolean = {
    val fields = batch.schema.fields
    oracle.BoundPlan.subtree(f).forall {
      case c: ColRefExpr => c.index < fields.length && fields(c.index).dataType == c.dataType
      case _ => true
    } && (0 until batch.numRows).exists { row =>
      f.args.head.eval(batch, row) != null && f.args.tail.exists(_.eval(batch, row) == null)
    }
  }

  // ---- DATE, TIMESTAMP, BINARY and DECIMAL coverage ----------------------
  //
  // One guard per type, each asserting both halves: the type reaches a
  // comparison and an IN list, an ORDER BY key, a GROUP BY key and a MIN/MAX
  // argument (and, for DECIMAL, a SUM or AVG and arithmetic), and the column
  // it reads there carries the values a comparison of the type gets wrong.
  // The corpus drew none of the four until plan 04. The guards read two shared
  // tallies of the generated corpus, one by predicate and one by site; no
  // query runs.
  //
  // 10000 seeds, because the thinnest chain (MIN/MAX over a decimal column
  // holding two values of one double, which only DECIMAL(38,18) has) is 25 of
  // them; generation only, well under a second. Measured over these seeds:
  //
  //                         date  timestamp  binary  decimal
  //   comparison             495        536     421      685
  //   IN                     250        281     293      207
  //   ORDER BY               713        730     675      696
  //   GROUP BY               612        655     639      658
  //   MIN/MAX                243        276     244      225   (SUM/AVG decimal 363,
  //                                                             arithmetic 584)
  //
  // and the columns there holding each value pair, at comparison / IN /
  // ORDER BY / GROUP BY / MIN/MAX: instants under a millisecond apart 272 /
  // 174 / 425 / 373 / 150; bytes of one content 276 / 194 / 437 / 433 / 165;
  // bytes one high bit apart 155 / 100 / 229 / 227 / 82; a decimal at two
  // scales 279 / 90 / 293 / 275 / 96 (148 at SUM/AVG, 244 in arithmetic);
  // decimals of one double 87 / 31 / 101 / 101 / 25 (44, 74); decimals a
  // double adds wrong 109 at SUM/AVG and 165 in arithmetic. Each bound sits
  // about 3x below its count.

  import DataGen.ValueTag._
  import ModeDifferentialFuzzTest.{SiteTally, predicateTally, siteTally}

  private def tallyMessage(t: SiteTally): String =
    s"over ${t.seeds} seeds: shapes=${t.shapes.toSeq.sortBy(_._1.toString).mkString(", ")}; " +
      s"values=${t.values.toSeq.sortBy(_._1.toString).mkString(", ")}"

  /** The shape floor at each site, for every one of the four types. */
  private val PredicateShapeFloor = Seq("comparison" -> 140, "IN" -> 80)
  private val SiteShapeFloor = Seq("ORDER BY" -> 220, "GROUP BY" -> 200, "MIN/MAX" -> 75)

  private def assertShapes(kind: String): Unit = {
    for ((site, floor) <- PredicateShapeFloor)
      assertTrue(s"a $kind rarely reaches $site, ${tallyMessage(predicateTally)}",
        predicateTally.shapes((site, kind)) > floor)
    for ((site, floor) <- SiteShapeFloor)
      assertTrue(s"a $kind rarely reaches $site, ${tallyMessage(siteTally)}", siteTally.shapes((site, kind)) > floor)
  }

  /** `floors` are for comparison, IN, ORDER BY, GROUP BY and MIN/MAX, in turn. */
  private def assertValues(kind: String, tag: DataGen.ValueTag, floors: Seq[Int]): Unit = {
    val sites = (PredicateShapeFloor.map(p => (p._1, predicateTally)) ++ SiteShapeFloor.map(p => (p._1, siteTally)))
    for (((site, t), floor) <- sites.zip(floors))
      assertTrue(s"a $kind at $site rarely holds $tag, ${tallyMessage(t)}", t.values((site, tag)) > floor)
  }

  @Test def datesReachComparisonsOrderByGroupByAndMinMax(): Unit = {
    println(s"[mode-differential] predicate coverage ${tallyMessage(predicateTally)}")
    println(s"[mode-differential] site coverage ${tallyMessage(siteTally)}")
    assertShapes("date")
  }

  @Test def timestampsReachComparisonsOrderByGroupByAndMinMaxUnderAMillisecondApart(): Unit = {
    assertShapes("timestamp")
    assertValues("timestamp", InstantsUnderAMillisecondApart, Seq(90, 54, 135, 120, 48))
  }

  @Test def binaryReachesComparisonsOrderByGroupByAndMinMaxWithEqualContentAndHighBitPairs(): Unit = {
    assertShapes("binary")
    assertValues("binary", BytesOfOneContent, Seq(92, 67, 145, 145, 54))
    assertValues("binary", BytesOneHighBitApart, Seq(50, 35, 80, 75, 27))
  }

  @Test def decimalsReachComparisonsOrderByGroupByMinMaxAndSumAtTwoScalesAndOfOneDouble(): Unit = {
    assertShapes("decimal")
    assertValues("decimal", DecimalAtTwoScales, Seq(68, 37, 90, 90, 33))
    assertValues("decimal", DecimalsOfOneDouble, Seq(18, 10, 33, 33, 7))
    // A decimal SUM and AVG add exactly, which only values a DOUBLE adds wrong
    // can tell from adding in DOUBLE, as SUM once did.
    val t = siteTally
    assertTrue(s"a DECIMAL rarely reaches SUM/AVG, ${tallyMessage(t)}", t.shapes(("SUM/AVG", "decimal")) > 125)
    assertTrue(s"a SUM/AVG DECIMAL rarely holds a decimal at two scales, ${tallyMessage(t)}",
      t.values(("SUM/AVG", DecimalAtTwoScales)) > 48)
    assertTrue(s"a SUM/AVG DECIMAL rarely holds decimals of one double, ${tallyMessage(t)}",
      t.values(("SUM/AVG", DecimalsOfOneDouble)) > 15)
    assertTrue(s"a SUM/AVG DECIMAL rarely holds decimals a double adds wrong, ${tallyMessage(t)}",
      t.values(("SUM/AVG", DecimalsADoubleAddsWrong)) > 37)
  }

  /** Decimal arithmetic, both halves: it reaches a projection or a filter, and
    * the decimal columns it reads hold values a DOUBLE adds wrong and one value
    * at two scales, the inputs on which arithmetic through `doubleValue`, or a
    * result spelled at the wrong scale, would show. */
  @Test def decimalArithmeticReachesDecimalsADoubleAddsWrong(): Unit = {
    val t = predicateTally
    assertTrue(s"decimal arithmetic is rarely generated, ${tallyMessage(t)}", t.shapes(("arithmetic", "decimal")) > 160)
    assertTrue(s"decimal arithmetic rarely reads decimals a double adds wrong, ${tallyMessage(t)}",
      t.values(("arithmetic", DecimalsADoubleAddsWrong)) > 45)
    assertTrue(s"decimal arithmetic rarely reads a decimal at two scales, ${tallyMessage(t)}",
      t.values(("arithmetic", DecimalAtTwoScales)) > 68)
  }

  /** Indices of the columns `q` takes `MIN`/`MAX` over, restricted to
    * `Float`/`Double` bare column references (the only aggregate argument shape
    * `QueryGen` builds for `MIN`/`MAX`). */
  private def floatMinMaxColumns(q: QueryGen.GenQuery): Seq[Int] = q match {
    case a: QueryGen.AggregateQuery => a.aggregates.flatMap {
      case (QueryGen.Min(arg: ColRefExpr), _) if isFloating(arg.dataType) => Some(arg.index)
      case (QueryGen.Max(arg: ColRefExpr), _) if isFloating(arg.dataType) => Some(arg.index)
      case _ => None
    }
    case _ => Seq.empty
  }

  private def isFloating(t: DataType): Boolean =
    t == DataType.FloatType || t == DataType.DoubleType

  /** Whether `q` takes `MIN`/`MAX` over a `Boolean` column. */
  private def hasBooleanMinMax(q: QueryGen.GenQuery): Boolean = q match {
    case a: QueryGen.AggregateQuery => a.aggregates.exists {
      case (QueryGen.Min(arg), _) => arg.dataType == DataType.BooleanType
      case (QueryGen.Max(arg), _) => arg.dataType == DataType.BooleanType
      case _ => false
    }
    case _ => false
  }

  /** Top-n over a TOTAL order, with the cutoff falling between two IDENTICAL rows.
    * Every mode must agree on the multiset — which of the tied rows survives is not
    * observable, because they are the same row. The condition this pins is the one
    * that makes `LIMIT` generatable at all: drop an order key and the top-n becomes
    * a layout-dependent SUBSET instead. */
  /** The OFFSET guard's value half: an OFFSET of 0, or one past the end of the
    * result, keeps everything or nothing, and a skip that lands mid-result is the
    * only place the clause can be wrong (a per-partition skip, a batch trimmed on
    * the wrong side). Runs each OFFSET query without its tail and counts the ones
    * whose skip falls strictly inside the result. */
  @Test def offsetIsGeneratedAndObservable(): Unit = {
    val n = 500
    var offsets, observable = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      val (untrimmed, skip) = qc.query match {
        case p: ProjectQuery if p.offset.isDefined => (p.copy(limit = None, offset = None), p.offset)
        case a: AggregateQuery if a.offset.isDefined => (a.copy(limit = None, offset = None), a.offset)
        case other => (other, None)
      }
      if (qc.sql.contains(" OFFSET ")) {
        offsets += 1
        val cat = new Catalog
        cat.register(QueryGen.ViewName, qc.dataset.singlePartition())
        val rows = Try(RelEngine.runRows(cat, untrimmed.render(QueryGen.ViewName), ExecutionOptions.Default).size)
          .getOrElse(-1)
        if (skip.exists(m => m > 0 && m < rows)) observable += 1
      }
      seed += 1
    }
    println(s"[mode-differential] OFFSET over $n seeds: rendered=$offsets, skip strictly inside the result=$observable")
    assertTrue(s"offset=$offsets", offsets >= n / 50)
    assertTrue(s"observable=$observable of $offsets", observable >= n / 50)
  }

  /** The NULLS FIRST / NULLS LAST guard's value half. The oracle checks where
    * the PRIMARY key's NULLs land, and that is only visible when the result
    * holds both NULL and non-NULL values of that key; otherwise any placement
    * passes. Counts the queries whose primary key carries an explicit placement
    * and whose result can show it. */
  @Test def nullPlacementIsGeneratedAndObservable(): Unit = {
    val n = 500
    var placed, observable = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      qc.query.orderBy.headOption.filter(_.nullsFirst.isDefined).foreach { primary =>
        placed += 1
        val cat = new Catalog
        cat.register(QueryGen.ViewName, qc.dataset.singlePartition())
        Try {
          val q = RelEngine.execute(cat, qc.sql, ExecutionOptions.Default)
          val idx = q.schema.indexOf(primary.col.name)
          val values = RelEngine.collectRows(q).map(_(idx))
          if (values.exists(_ == null) && values.exists(_ != null)) observable += 1
        }
      }
      seed += 1
    }
    println(s"[mode-differential] explicit NULL placement on the primary key over $n seeds: " +
      s"generated=$placed, result shows both NULL and non-NULL keys=$observable")
    assertTrue(s"placed=$placed", placed >= n / 20)
    assertTrue(s"observable=$observable of $placed", observable >= n / 100)
  }

  /** The SUM / AVG(DISTINCT) guard's value half: DISTINCT only tests anything
    * when some group repeats a value, so run each query again with DISTINCT
    * dropped and count the ones whose answer changes. */
  @Test def distinctAggregatesAreGeneratedAndObservable(): Unit = {
    // Duplicates within a group are scarce in these small datasets, so this
    // reads more seeds than the other guards: only the DISTINCT queries run.
    val n = 2000
    var generated, observable = 0
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      qc.query match {
        case a: AggregateQuery if a.aggregates.exists {
              case (s: Sum, _) => s.distinct
              case (v: Avg, _) => v.distinct
              case _ => false
            } =>
          generated += 1
          val plain = a.copy(aggregates = a.aggregates.map {
            case (s: Sum, nm) => (s.copy(distinct = false), nm)
            case (v: Avg, nm) => (v.copy(distinct = false), nm)
            case other => other
          })
          val cat = new Catalog
          cat.register(QueryGen.ViewName, qc.dataset.singlePartition())
          val differs = Try {
            val q1 = RelEngine.execute(cat, a.render(QueryGen.ViewName), ExecutionOptions.Default)
            val q2 = RelEngine.execute(cat, plain.render(QueryGen.ViewName), ExecutionOptions.Default)
            RowOracle.multisetEquals(q1.schema, RelEngine.collectRows(q1), RelEngine.collectRows(q2)).isDefined
          }.getOrElse(false)
          if (differs) observable += 1
        case _ => ()
      }
      seed += 1
    }
    println(s"[mode-differential] SUM/AVG(DISTINCT) over $n seeds: generated=$generated, " +
      s"dropping DISTINCT changes the answer=$observable")
    assertTrue(s"generated=$generated", generated >= n / 20)
    assertTrue(s"observable=$observable of $generated", observable >= n / 200)
  }

  /** SUM(DISTINCT) / AVG(DISTINCT) over a value repeated within and across
    * partitions: every layout must merge the partials' value sets before the
    * replay, and spill must fall back to the in-memory path, so all modes agree. */
  @Test def regressionDistinctSumAvgAcrossModes(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.DoubleType)))
    val rows = IndexedSeq[Array[Any]](
      Array[Any](1, 0.5), Array[Any](1, 0.5), Array[Any](1, 1.5), Array[Any](2, 0.5),
      Array[Any](2, 2.5), Array[Any](2, 2.5), Array[Any](1, null), Array[Any](null, 0.5))
    val c0 = ColRefExpr(0, "c0", DataType.IntType)
    val c1 = ColRefExpr(1, "c1", DataType.DoubleType)
    val q = AggregateQuery(Vector(c0),
      Vector((Sum(c1, distinct = true), "a0"), (Avg(c1, distinct = true), "a1"), (Sum(c1), "a2")),
      where = None, having = None, groupByOrdinals = false, orderBy = Vector(OrderKey(c0, asc = true)))
    val qc = QueryGen.QueryCase(DataGen.Dataset(schema, rows), q)
    assertTrue(qc.sql, qc.sql.contains("SUM(DISTINCT c1)") && qc.sql.contains("AVG(DISTINCT c1)"))
    ModeDifferential.check(qc)
  }

  /** NULLS FIRST and NULLS LAST against each direction, over a key with NULLs:
    * every mode must put the NULLs at the end the key names (the oracle's
    * sortedness check asserts the placement, not only the order of the
    * non-NULL values) and agree on the multiset. */
  @Test def regressionExplicitNullPlacementAcrossModes(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.StringType)))
    val rows = IndexedSeq[Array[Any]](
      Array[Any](3, "a"), Array[Any](null, "b"), Array[Any](1, "c"),
      Array[Any](null, "d"), Array[Any](2, null), Array[Any](1, "e"))
    val c0 = ColRefExpr(0, "c0", DataType.IntType)
    val c1 = ColRefExpr(1, "c1", DataType.StringType)
    for (asc <- Seq(true, false); first <- Seq(true, false)) {
      val q = QueryGen.ProjectQuery(distinct = false,
        projection = Vector((c0: Expr, "c0"), (c1: Expr, "c1")), where = None,
        orderBy = Vector(OrderKey(c0, asc, Some(first)), OrderKey(c1, asc = true)))
      val qc = QueryGen.QueryCase(DataGen.Dataset(schema, rows), q)
      assertTrue(s"expected an explicit placement, got ${qc.sql}", qc.sql.contains(" NULLS "))
      ModeDifferential.check(qc)
    }
  }

  /** OFFSET over the same total order, cutting between IDENTICAL rows — with a
    * LIMIT after it and on its own. Every layout must keep the same multiset. */
  @Test def regressionTopNOffsetOverTotalOrder(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.StringType)))
    val rows = IndexedSeq[Array[Any]](
      Array[Any](1, "a"), Array[Any](1, "a"), Array[Any](1, "b"),
      Array[Any](2, "a"), Array[Any](null, "a"), Array[Any](3, null))
    val cols = Vector((ColRefExpr(0, "c0", DataType.IntType), "c0"), (ColRefExpr(1, "c1", DataType.StringType), "c1"))
    val order = Vector(OrderKey(ColRefExpr(0, "c0", DataType.IntType), true), OrderKey(ColRefExpr(1, "c1", DataType.StringType), true))
    Seq((Some(2L), Some(1L), " LIMIT 2 OFFSET 1"), (None, Some(2L), " OFFSET 2")).foreach { case (limit, offset, tail) =>
      val q = QueryGen.ProjectQuery(distinct = false, projection = cols.map { case (c, n) => (c: Expr, n) },
        where = None, orderBy = order, limit = limit, offset = offset)
      val qc = QueryGen.QueryCase(DataGen.Dataset(schema, rows), q)
      assertTrue(s"expected a rendered$tail, got ${qc.sql}", qc.sql.endsWith(tail))
      ModeDifferential.check(qc)
    }
  }

  @Test def regressionTopNLimitOverTotalOrder(): Unit = {
    val schema = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.StringType)))
    val rows = IndexedSeq[Array[Any]](
      Array[Any](1, "a"), Array[Any](1, "a"), Array[Any](1, "b"),
      Array[Any](2, "a"), Array[Any](null, "a"), Array[Any](3, null))
    val q = QueryGen.ProjectQuery(
      distinct = false,
      projection = Vector((ColRefExpr(0, "c0", DataType.IntType), "c0"), (ColRefExpr(1, "c1", DataType.StringType), "c1")),
      where = None,
      orderBy = Vector(OrderKey(ColRefExpr(0, "c0", DataType.IntType), true), OrderKey(ColRefExpr(1, "c1", DataType.StringType), true)),
      limit = Some(3L))
    val qc = QueryGen.QueryCase(DataGen.Dataset(schema, rows), q)
    assertTrue(s"expected a rendered LIMIT, got ${qc.sql}", qc.sql.contains(" LIMIT 3"))
    ModeDifferential.check(qc)
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
      where = None, orderBy = Vector(OrderKey(c(0, intLong), true)))
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
      groupByOrdinals = false, orderBy = Vector(OrderKey(c(0, schema), true)))
    assertAgrees(QueryCase(DataGen.Dataset(schema, rows), q))
  }
}

object ModeDifferentialFuzzTest {

  /** Over a seed block, how many seeds put a column of each kind at each site
    * (`shapes`, keyed by site and `ExprGen.kind`), and how many of those put a
    * column carrying each `DataGen.ValueTag` there (`values`, keyed by site and
    * tag). */
  final case class SiteTally(
      seeds: Int,
      shapes: Map[(String, String), Int],
      values: Map[(String, DataGen.ValueTag), Int])

  /** The columns `q` places at each site: ORDER BY keys, GROUP BY keys, the
    * arguments of MIN / MAX, and of SUM / AVG. */
  private def sites(q: QueryGen.GenQuery): Seq[(String, Int)] = {
    val orderBy = q.orderBy.map(k => ("ORDER BY", k.col.index))
    val rest = q match {
      case a: QueryGen.AggregateQuery =>
        a.groupKeys.map(k => ("GROUP BY", k.index)) ++ a.aggregates.collect {
          case (QueryGen.Min(c: ColRefExpr), _) => ("MIN/MAX", c.index)
          case (QueryGen.Max(c: ColRefExpr), _) => ("MIN/MAX", c.index)
          case (QueryGen.Sum(c: ColRefExpr, _), _) => ("SUM/AVG", c.index)
          case (QueryGen.Avg(c: ColRefExpr, _), _) => ("SUM/AVG", c.index)
        }
      case _ => Seq.empty
    }
    orderBy ++ rest
  }

  /** Every comparison and IN in `q` over one of the four types, and every
    * arithmetic over a DECIMAL, with the columns of that type it reads: the
    * site (comparison, IN or arithmetic), its kind, and the column indices. */
  private def predicates(q: QueryGen.GenQuery): Seq[(String, String, Seq[Int])] = {
    val roots: Seq[Expr] = q match {
      case p: QueryGen.ProjectQuery => p.projection.map(_._1) ++ p.where.toSeq
      case a: QueryGen.AggregateQuery => a.where.toSeq ++ a.aggregates.flatMap {
        case (QueryGen.CountIf(e), _) => Seq(e)
        case (QueryGen.Count(e, _), _) => Seq(e)
        case (QueryGen.Sum(e, _), _) => Seq(e)
        case (QueryGen.Avg(e, _), _) => Seq(e)
        case _ => Seq.empty
      }
    }
    def columns(e: Expr, kind: String): Seq[Int] = e match {
      case c: ColRefExpr if ExprGen.kind(c.dataType) == kind => Seq(c.index)
      case other => Shrinker.childrenOf(other).flatMap(columns(_, kind))
    }
    def walk(e: Expr): Seq[(String, String, Seq[Int])] = {
      val here = e match {
        case BinOpExpr(op, l, r, _) if Seq("=", "<>", "<", "<=", ">", ">=").contains(op) &&
          ExprGen.ReportedKinds(ExprGen.kind(l.dataType)) =>
          val k = ExprGen.kind(l.dataType)
          Seq(("comparison", k, columns(l, k) ++ columns(r, k)))
        case InListExpr(child, items, _) if ExprGen.ReportedKinds(ExprGen.kind(child.dataType)) =>
          val k = ExprGen.kind(child.dataType)
          Seq(("IN", k, (child +: items).flatMap(columns(_, k))))
        case BinOpExpr(op, l, r, t) if Seq("+", "-", "*", "/", "%").contains(op) && ExprGen.kind(t) == "decimal" =>
          Seq(("arithmetic", "decimal", columns(l, "decimal") ++ columns(r, "decimal")))
        case _ => Seq.empty
      }
      here ++ Shrinker.childrenOf(e).flatMap(walk)
    }
    roots.flatMap(walk)
  }

  lazy val predicateTally: SiteTally = {
    val n = 10000
    val shapes = scala.collection.mutable.Map.empty[(String, String), Int].withDefaultValue(0)
    val values = scala.collection.mutable.Map.empty[(String, DataGen.ValueTag), Int].withDefaultValue(0)
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      val found = predicates(qc.query)
      found.map { case (site, kind, _) => (site, kind) }.distinct.foreach(k => shapes(k) += 1)
      found.flatMap { case (site, _, cols) =>
        cols.distinct.flatMap(c => DataGen.valueTags(qc.dataset.rows.map(_(c))).map(tag => (site, tag)))
      }.distinct.foreach(k => values(k) += 1)
      seed += 1
    }
    SiteTally(n, shapes.toMap.withDefaultValue(0), values.toMap.withDefaultValue(0))
  }

  lazy val siteTally: SiteTally = {
    val n = 10000
    val shapes = scala.collection.mutable.Map.empty[(String, String), Int].withDefaultValue(0)
    val values = scala.collection.mutable.Map.empty[(String, DataGen.ValueTag), Int].withDefaultValue(0)
    var seed = 0
    while (seed < n) {
      val qc = QueryGen.generate(new Rng(seed.toLong))
      val fields = qc.dataset.schema.fields
      val here = sites(qc.query).distinct
      here.map { case (site, c) => (site, ExprGen.kind(fields(c).dataType)) }.distinct
        .foreach(k => shapes(k) += 1)
      here.flatMap { case (site, c) =>
        DataGen.valueTags(qc.dataset.rows.map(_(c))).map(tag => (site, tag))
      }.distinct.foreach(k => values(k) += 1)
      seed += 1
    }
    SiteTally(n, shapes.toMap.withDefaultValue(0), values.toMap.withDefaultValue(0))
  }
}
