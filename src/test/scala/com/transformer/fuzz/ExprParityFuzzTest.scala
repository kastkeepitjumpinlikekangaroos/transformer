package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.oracle.{ExactIntegers, ExprParity}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

/** Property-based parity gate for `Expr.eval` vs `Expr.evalVec` — the generated
  * generalization of [[com.transformer.sql.plan.ExprBatchTest]].
  *
  * [[fuzzExprParity]] generates a random typed `Expr` over a random
  * `ColumnarBatch` and asserts the two evaluation paths agree on every row,
  * across a small fixed-seed batch by default (`Props.DefaultSeeds`). Override
  * the budget for a long campaign:
  * {{{
  *   bazel test //src/test/scala/com/transformer/fuzz:expr_parity_fuzz_test \
  *     --jvmopt='-Dfuzz.seeds=200000' --test_output=streamed
  * }}}
  * A failure prints the seed; reproduce a single case with
  * `-Dfuzz.seed=<seed> -Dfuzz.seeds=1`.
  *
  * The hand-written [[regressionComposite]] locks a fixed tree so a meaningful
  * case always runs even with `-Dfuzz.seeds=0`. When the fuzzer finds a real
  * divergence, distill it into a named regression here and (if it reflects a
  * gap) into `ExprBatchTest` proper.
  */
class ExprParityFuzzTest {

  @Test def fuzzExprParity(): Unit = {
    Props.forAll[ExprGen.ExprCase](
      name = "expr-parity",
      gen = ExprGen.generate,
      shrink = c => Shrinker.expr(c.expr).map(e => new ExprGen.ExprCase(c.batch, e))
    ) { c =>
      ExprParity.check(c.expr, c.batch)
      if (c.generated) ExprParity.checkTotal(c.expr, c.batch)
    }
  }

  /** INT and BIGINT arithmetic over edge values is its exact answer, or an
    * error where that answer does not fit, on both paths
    * (`oracle/ExactIntegers`). Every other property here compares the engine
    * with itself, and integer arithmetic wrapped round on both paths alike. */
  @Test def fuzzExactIntegerArithmetic(): Unit = {
    Props.forAll[ExprGen.ExprCase](
      name = "exact-integers",
      gen = ExprGen.generateIntegerArith,
      shrink = c => Shrinker.expr(c.expr).map(e => new ExprGen.ExprCase(c.batch, e))
    ) { c =>
      ExactIntegers.checkExpr(c.expr, c.batch)
    }
  }

  /** Coverage guard for [[fuzzExactIntegerArithmetic]]: trees whose exact
    * value overflows on some row, and trees whose value comes within half a
    * type's range of its edge and fits, must both be common, or the property
    * cannot tell an exact engine from a wrapping one or from one that refuses
    * too much. */
  @Test def exactIntegerArithmeticReachesBothSidesOfTheEdge(): Unit = {
    val n = 4000
    var overflows, nearTheEdge = 0
    (0 until n).foreach { seed =>
      val c = ExprGen.generateIntegerArith(new Rng(seed.toLong))
      val cov = ExactIntegers.coverage(c.expr, c.batch)
      if (cov.overflows) overflows += 1
      if (cov.nearTheEdge) nearTheEdge += 1
    }
    println(f"[exact-integers] over $n seeds: a row overflows=$overflows, a row near the edge fits=$nearTheEdge")
    assertTrue(s"integer trees rarely overflow (overflows=$overflows)", overflows > 800)
    assertTrue(s"integer trees rarely come near the edge and fit (nearTheEdge=$nearTheEdge)", nearTheEdge > 800)
  }

  /** Coverage guard for the guarded shapes: a CAST of a batch's guard text,
    * under a conditional whose guard keeps it off the rows it fails on, must
    * be drawn (the shape), and the batch must hold such a row (the value), or
    * the property cannot tell a lazy conditional from an eager one. Each
    * conditional's shape is its own producer, so the shape half is the sum. */
  @Test def guardedConditionalsReachRowsTheyMustSkip(): Unit = {
    val n = 4000
    var shape, excluded = 0
    var seed = 0
    while (seed < n) {
      val cov = ExprGen.guardCoverage(ExprGen.generate(new Rng(seed.toLong)))
      if (cov.shape) shape += 1
      if (cov.shape && cov.excludesAFailure) excluded += 1
      seed += 1
    }
    println(f"[expr-parity] guarded conditionals over $n seeds: shape=$shape, with a row to skip=$excluded")
    assertTrue(s"no guarded conditional generated (shape=$shape)", shape > 200)
    assertTrue(s"guarded conditionals never meet a row they must skip (excluded=$excluded)", excluded > 150)
  }

  /** Same seed reproduces the exact tree and batch — the repro contract the
    * failure messages rely on. */
  @Test def sameSeedReproduces(): Unit = {
    val seed = 123456789L
    val a = ExprGen.generate(new Rng(seed))
    val b = ExprGen.generate(new Rng(seed))
    assertEquals("expr", a.expr, b.expr) // Expr nodes are case classes -> structural equality
    assertEquals("schema", a.batch.schema, b.batch.schema)
    assertEquals("numRows", a.batch.numRows, b.batch.numRows)
    val n = a.batch.numRows
    var col = 0
    while (col < a.batch.schema.length) {
      var i = 0
      while (i < n) {
        val av = if (a.batch.column(col).isNull(i)) null else a.batch.column(col).getBoxed(i)
        val bv = if (b.batch.column(col).isNull(i)) null else b.batch.column(col).getBoxed(i)
        assertEquals(s"cell ($col,$i)", av, bv)
        i += 1
      }
      col += 1
    }
  }

  /** Coverage guard: the corpus must actually carry `NaN` and `-0.0` into a
    * float/double ordering comparison and into a numeric `IN` list.
    *
    * Those are the two operations where the row and vector paths used to
    * disagree, and the generator held out BOTH the shapes (ordering restricted
    * to integrals, `IN` restricted to Int/Long/String/Boolean) and the values
    * (every generated `NaN`/`Inf`/`-0.0` normalized to `0.0`) while the engine
    * diverged. Re-enabling only the shapes would leave the fuzzer green over a
    * corpus that never produces the values that break them, so both halves are
    * measured. Prints the tally for the PR.
    *
    * The block is 8000 seeds: drawing the catalog's every function (the math
    * functions, `GREATEST` and `LEAST`) left a float operand fewer draws, and
    * over 40000 seeds the shapes fell from 3.9% and 1.15% of seeds to 3.55%
    * and 1.06%; at 4000 seeds the rarest value, -0.0 in an IN list, had come
    * within two seeds of its bound before the change too. */
  @Test def generatorReachesIeeeSpecialValues(): Unit = {
    val n = 8000
    var ordShape, ordNan, ordNegZero, inShape, inNan, inNegZero = 0
    var seed = 0
    while (seed < n) {
      val cov = ExprGen.specialValueCoverage(ExprGen.generate(new Rng(seed.toLong)))
      if (cov.orderingShape) ordShape += 1
      if (cov.orderingNan) ordNan += 1
      if (cov.orderingNegZero) ordNegZero += 1
      if (cov.inListShape) inShape += 1
      if (cov.inListNan) inNan += 1
      if (cov.inListNegZero) inNegZero += 1
      seed += 1
    }
    println(f"[expr-parity] IEEE special-value coverage over $n seeds: " +
      f"float ordering shape=$ordShape (NaN=$ordNan, -0.0=$ordNegZero); " +
      f"float IN shape=$inShape (NaN=$inNan, -0.0=$inNegZero)")
    // The shape half: a float/double operand reaching an ordering comparison
    // (~3.5% of seeds) or an IN list (~1%). Both are narrow by construction —
    // each needs a Boolean-typed node, then that specific producer, then a
    // floating operand type — so every bound below separates "rare" from
    // "gone" with a 2-3x margin rather than pinning the measured rate. Both
    // fell from ~6% and ~1.8% when the corpus widened from six types to ten,
    // which left a float operand fewer draws to win.
    assertTrue(s"no float/double ordering comparison generated (shape=$ordShape)", ordShape > 100)
    assertTrue(s"no float/double IN list generated (shape=$inShape)", inShape > 30)
    // The value half, ~0.45% and ~0.27% of seeds. Rarer than the shapes because the
    // operand also has to CARRY the value: a literal gets one draw, and a
    // subtree above a column washes -0.0 out (though not NaN, which
    // propagates). This half is the one that would silently go to zero if the
    // value generator drifted back to normalizing NaN/-0.0 away, leaving the
    // re-enabled shapes green over a corpus that cannot break them.
    assertTrue(s"NaN never reaches an ordering comparison (nan=$ordNan)", ordNan > 10)
    assertTrue(s"-0.0 never reaches an ordering comparison (negZero=$ordNegZero)", ordNegZero > 10)
    assertTrue(s"NaN never reaches an IN list (nan=$inNan)", inNan > 8)
    assertTrue(s"-0.0 never reaches an IN list (negZero=$inNegZero)", inNegZero > 8)
  }

  // ---- DATE, TIMESTAMP, BINARY and DECIMAL coverage ----------------------
  //
  // One guard per type, each asserting both halves: the type reaches a
  // comparison and an IN list (the shape), and the values a comparison of that
  // type gets wrong actually meet in one on some row (the value). The corpus
  // drew none of the four until plan 04, and the guards are what stop any of
  // them, or their values, quietly dropping back out. They read one shared tally
  // of `ExprGen.typeCoverage` over a fixed seed block, so the four cost one pass.
  //
  // The block is 60000 seeds, three campaigns', rather than the 8000 the IEEE
  // guard above reads: the value half is a thin chain (a comparison of the type
  // AND the pair on one row), and at 4000 seeds the high-bit and one-double
  // counts were 5, too few to tell rare from gone. It was 20000 until the
  // generator drew the catalog's every function, which left these shapes about
  // a third fewer draws. Generation only, a few seconds. Measured over these
  // 60000 seeds: compared date 513, timestamp 499, binary 637, decimal 769; IN
  // date 321, timestamp 318, binary 331, decimal 295; bytes of one content
  // 163, one high bit apart 121; one decimal at two scales 89, two of one
  // double 37; instants under a millisecond apart 229. Each bound sits at
  // least 4x below, so it separates a corpus that stopped producing the shape
  // or the value from one that merely drifted.

  import DataGen.ValueTag._
  import ExprParityFuzzTest.{TypeTally, typeTally}

  private def typeMessage(t: TypeTally): String =
    s"over ${t.seeds} seeds: compared=${t.compared} inList=${t.inList} values=${t.values}"

  @Test def datesReachComparisonsAndInLists(): Unit = {
    val t = typeTally
    println(s"[expr-parity] type coverage ${typeMessage(t)}")
    assertTrue(s"a DATE never reaches a comparison, ${typeMessage(t)}", t.compared("date") > 80)
    assertTrue(s"a DATE never reaches an IN list, ${typeMessage(t)}", t.inList("date") > 40)
  }

  @Test def timestampsReachComparisonsUnderAMillisecondApart(): Unit = {
    val t = typeTally
    assertTrue(s"a TIMESTAMP never reaches a comparison, ${typeMessage(t)}", t.compared("timestamp") > 80)
    assertTrue(s"a TIMESTAMP never reaches an IN list, ${typeMessage(t)}", t.inList("timestamp") > 40)
    assertTrue(s"two instants under a millisecond apart never meet, ${typeMessage(t)}",
      t.values(InstantsUnderAMillisecondApart) > 35)
  }

  @Test def binaryReachesComparisonsWithEqualContentAndHighBitPairs(): Unit = {
    val t = typeTally
    assertTrue(s"a BINARY never reaches a comparison, ${typeMessage(t)}", t.compared("binary") > 80)
    assertTrue(s"a BINARY never reaches an IN list, ${typeMessage(t)}", t.inList("binary") > 40)
    assertTrue(s"two byte arrays of one content never meet, ${typeMessage(t)}", t.values(BytesOfOneContent) > 25)
    assertTrue(s"two byte arrays one high bit apart never meet, ${typeMessage(t)}", t.values(BytesOneHighBitApart) > 15)
  }

  @Test def decimalsReachComparisonsAtTwoScalesAndOfOneDouble(): Unit = {
    val t = typeTally
    assertTrue(s"a DECIMAL never reaches a comparison, ${typeMessage(t)}", t.compared("decimal") > 80)
    assertTrue(s"a DECIMAL never reaches an IN list, ${typeMessage(t)}", t.inList("decimal") > 40)
    assertTrue(s"one decimal at two scales never meets itself, ${typeMessage(t)}", t.values(DecimalAtTwoScales) > 9)
    assertTrue(s"two decimals of one double never meet, ${typeMessage(t)}", t.values(DecimalsOfOneDouble) > 8)
  }

  /** Decimal arithmetic, both halves: each operator and negation over DECIMALs
    * reaches the corpus (the shape), and answers that had to round to their
    * declared scale and divisors of zero reach it too (the value), since a
    * corpus of exact answers over nonzero divisors would leave
    * `Decimals.arith`'s rounding and its NULL untested on the vector path.
    * 60000 seeds: 40000 since sides drawn for a narrower DECIMAL an answer is
    * cast to (which rounds it, and fails past its integer digits) made
    * rounding rarer, and 60000 since the generator drew the catalog's every
    * function, which left decimal arithmetic about a third fewer draws.
    * Measured over these seeds: `+` 541, `-` 614, `*` 572, `/` 599, `%` 573,
    * negation 369; an answer that rounded 188, a zero divisor 131. Each bound
    * sits about 3x below the smallest count it covers. */
  @Test def decimalArithmeticRoundsAndMeetsZeroDivisors(): Unit = {
    val n = 60000
    val ops = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var rounded, byZero = 0
    var seed = 0
    while (seed < n) {
      val cov = ExprGen.decimalArithCoverage(ExprGen.generate(new Rng(seed.toLong)))
      cov.ops.foreach(op => ops(op) += 1)
      if (cov.rounded) rounded += 1
      if (cov.byZero) byZero += 1
      seed += 1
    }
    val report = s"over $n seeds: ops=${ops.toSeq.sorted.mkString(", ")} rounded=$rounded byZero=$byZero"
    println(s"[expr-parity] decimal arithmetic $report")
    for (op <- Seq("+", "-", "*", "/", "%"))
      assertTrue(s"a DECIMAL $op is rarely generated, $report", ops(op) > 170)
    assertTrue(s"a DECIMAL is rarely negated, $report", ops("negate") > 105)
    assertTrue(s"a decimal answer rarely rounds, $report", rounded > 59)
    assertTrue(s"a decimal divisor is rarely zero, $report", byZero > 44)
  }

  /** Coverage guard: a `ROUND` or `TRUNC` scale and a `SUBSTRING` position or
    * length reach both paths NULL, on a row where the value they apply to is
    * not, and past an edge: 16 places or more either way, or past an INT's
    * range. NULL ones were kept out of the generator while they threw a
    * `NullPointerException` (plans/robustness/11), and a corpus that only ever
    * gave small literals could not tell the two paths' NULL handling, or
    * their edges, apart. Prints the tally.
    *
    * Measured: the shape on 702 of the 20000 seeds, a NULL beside a value on
    * 198 and an edge on 37, the edge rare because it takes an integer subtree
    * that evaluates large on a row whose value is not NULL. The block is the
    * campaign's, since 4000 seeds held five edges; bounds sit ~3x below. */
  @Test def integerArgumentsReachNullAndTheEdges(): Unit = {
    val n = 20000
    var shape, nullArg, edge = 0
    var seed = 0
    while (seed < n) {
      val cov = ExprGen.integerArgCoverage(ExprGen.generate(new Rng(seed.toLong)))
      if (cov.shape) shape += 1
      if (cov.nullArg) nullArg += 1
      if (cov.edge) edge += 1
      seed += 1
    }
    val report = s"over $n seeds: shape=$shape null=$nullArg edge=$edge"
    println(s"[expr-parity] scale, position and length $report")
    assertTrue(s"a scale, position or length is rarely generated, $report", shape > 250)
    assertTrue(s"a NULL scale, position or length rarely meets a value, $report", nullArg > 65)
    assertTrue(s"a scale, position or length rarely passes an edge, $report", edge > 12)
  }

  /** Coverage guard: a query's CAST to DECIMAL reaches both paths, rounding a
    * value to the target's scale on some row and passing its integer digits
    * on another, the overflow both paths must raise alike. Prints the tally.
    * The cast is one of the shapes a DECIMAL is drawn as, at weight 1 of
    * 16, so the shape is on 1043 of these 100000 seeds, a rounding on 35 and
    * an overflow on 84 (a rounding takes a source with more places than its
    * target, and a value that uses them); each bound sits about 3x below. */
  @Test def writtenDecimalCastsRoundAndOverflow(): Unit = {
    val n = 100000
    var shape, rounded, overflowed = 0
    var seed = 0
    while (seed < n) {
      val cov = ExprGen.writtenDecimalCastCoverage(ExprGen.generate(new Rng(seed.toLong)))
      if (cov.shape) shape += 1
      if (cov.rounded) rounded += 1
      if (cov.overflowed) overflowed += 1
      seed += 1
    }
    val report = s"over $n seeds: shape=$shape rounded=$rounded overflowed=$overflowed"
    println(s"[expr-parity] written CAST to DECIMAL $report")
    assertTrue(s"a CAST to DECIMAL is rarely generated, $report", shape > 340)
    assertTrue(s"a CAST to DECIMAL rarely rounds, $report", rounded > 11)
    assertTrue(s"a CAST to DECIMAL rarely overflows, $report", overflowed > 28)
  }

  /** Coverage guard: every function the catalog binds as a call
    * (`FunctionCatalog`'s scalar and flow entries) is drawn, each on a share
    * of the block. The generator draws its functions from the catalog by
    * what they take and return, so a function added there is fuzzed the day
    * it lands, and one the generator cannot type fails here. Prints the
    * tally. The rarest are the two dozen functions that return a DOUBLE
    * whatever they are given, each on 30 to 55 of these 40000 seeds, so the
    * block is the campaign's and the bound sits 3x below. */
  @Test def everyCatalogFunctionIsDrawn(): Unit = {
    val n = 40000
    val drawn = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var seed = 0
    while (seed < n) {
      ExprGen.functionsDrawn(ExprGen.generate(new Rng(seed.toLong))).foreach(f => drawn(f) += 1)
      seed += 1
    }
    val counts = ExprGen.CalledFunctions.map(e => e.name -> drawn(e.name))
    println(s"[expr-parity] catalog functions over $n seeds: " + counts.map { case (f, c) => s"$f=$c" }.mkString(", "))
    counts.foreach { case (f, c) => assertTrue(s"$f is drawn on $c of $n seeds", c > 10) }
  }

  /** The generator's own type invariant holds across a block of seeds:
    * `generate` asserts `expr.dataType == target` internally, so a violation
    * would throw here. */
  @Test def generatorTypeInvariantHolds(): Unit = {
    var seed = 0L
    while (seed < 500L) {
      val c = ExprGen.generate(new Rng(seed))
      assertTrue(s"non-null expr for seed $seed", c.expr.dataType != null)
      seed += 1
    }
  }

  /** A fixed composite that exercises arithmetic, ABS, COALESCE, an ordering
    * comparison, AND/OR, LIKE, and IS NULL together — a deterministic smoke
    * independent of the seed budget. */
  @Test def regressionComposite(): Unit = {
    val n = 6
    val a = ColumnVector.allocate(DataType.IntType, n)
    Seq[Any](0, 1, 2, 3, null, 5).zipWithIndex.foreach {
      case (null, i) => a.setNull(i)
      case (v, i) => a.setBoxed(i, v)
    }
    val s = ColumnVector.allocate(DataType.StringType, n)
    Seq[Any]("foo", "bar", null, "fizz", "foobar", "f").zipWithIndex.foreach {
      case (null, i) => s.setNull(i)
      case (v, i) => s.setBoxed(i, v)
    }
    val schema = Schema(Vector(Field("a", DataType.IntType), Field("s", DataType.StringType)))
    val batch = ColumnarBatch.fromColumns(schema, Array(a, s), n)

    val aRef = ColRefExpr(0, "a", DataType.IntType)
    val sRef = ColRefExpr(1, "s", DataType.StringType)
    // COALESCE(ABS(a - 3), 0) > 2
    val absDiff = FuncExpr("ABS",
      Seq(BinOpExpr("-", aRef, LitExpr(3, DataType.IntType), DataType.IntType)), DataType.IntType)
    val coalesced = FuncExpr("COALESCE",
      Seq(absDiff, LitExpr(0, DataType.IntType)), DataType.IntType)
    val gt = BinOpExpr(">", coalesced, LitExpr(2, DataType.IntType), DataType.BooleanType)
    // s LIKE 'f%' OR s IS NULL
    val like = LikeExpr(sRef, LitExpr("f%", DataType.StringType), negated = false)
    val isNull = IsNullExpr(sRef, negated = false)
    val or = BinOpExpr("OR", like, isNull, DataType.BooleanType)
    val composite = BinOpExpr("AND", gt, or, DataType.BooleanType)

    ExprParity.check(composite, batch)
  }

  /** The shrinker collapses a tall tree toward a minimal failing leaf — a unit
    * check on the harness itself, using an always-failing predicate so we can
    * observe the minimized result rather than the (passing) parity property. */
  @Test def shrinkerMinimizesToLeaf(): Unit = {
    val deep = BinOpExpr("+",
      BinOpExpr("*", LitExpr(7, DataType.IntType), LitExpr(9, DataType.IntType), DataType.IntType),
      LitExpr(42, DataType.IntType),
      DataType.IntType)
    // Greedily follow the first strictly-smaller candidate to a fixpoint.
    var current: Expr = deep
    var progressed = true
    while (progressed) {
      progressed = false
      val it = Shrinker.expr(current)
      if (it.hasNext) { current = it.next(); progressed = true }
    }
    assertEquals("shrinks to a single node", 1, Shrinker.size(current))
    assertTrue("minimal node is a literal", current.isInstanceOf[LitExpr])
  }
}

object ExprParityFuzzTest {

  /** How many seeds of a block reach each shape and value
    * [[ExprGen.TypeCoverage]] reports. */
  final case class TypeTally(
      seeds: Int,
      compared: Map[String, Int],
      inList: Map[String, Int],
      values: Map[DataGen.ValueTag, Int])

  /** The tally over the first 60000 seeds, computed once for the four guards. */
  lazy val typeTally: TypeTally = {
    val n = 60000
    val compared, inList = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val values = scala.collection.mutable.Map.empty[DataGen.ValueTag, Int].withDefaultValue(0)
    var seed = 0
    while (seed < n) {
      val c = ExprGen.typeCoverage(ExprGen.generate(new Rng(seed.toLong)))
      c.compared.foreach(k => compared(k) += 1)
      c.inList.foreach(k => inList(k) += 1)
      c.values.foreach(v => values(v) += 1)
      seed += 1
    }
    TypeTally(n, compared.toMap.withDefaultValue(0), inList.toMap.withDefaultValue(0),
      values.toMap.withDefaultValue(0))
  }
}
