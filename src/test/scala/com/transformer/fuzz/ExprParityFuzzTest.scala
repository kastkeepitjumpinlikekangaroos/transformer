package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.oracle.ExprParity
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
    }
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
    * measured. Prints the tally for the PR. */
  @Test def generatorReachesIeeeSpecialValues(): Unit = {
    val n = 4000
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
    // (~4.5% of seeds) or an IN list (~1.5%). Both are narrow by construction —
    // each needs a Boolean-typed node, then that specific producer, then a
    // floating operand type — so every bound below separates "rare" from
    // "gone" with a 2-3x margin rather than pinning the measured rate. Both
    // fell from ~6% and ~1.8% when the corpus widened from six types to ten,
    // which left a float operand fewer draws to win.
    assertTrue(s"no float/double ordering comparison generated (shape=$ordShape)", ordShape > 100)
    assertTrue(s"no float/double IN list generated (shape=$inShape)", inShape > 30)
    // The value half, ~0.7% and ~0.45% of seeds. Rarer than the shapes because the
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
  // The block is 20000 seeds, the campaign's, rather than the 4000 the IEEE
  // guard above reads: the value half is a thin chain (a comparison of the type
  // AND the pair on one row), and at 4000 seeds the high-bit and one-double
  // counts were 5, too few to tell rare from gone. Generation only, about a
  // second. Measured over these 20000 seeds: compared date 255, timestamp 248,
  // binary 323, decimal 352; IN date 140, timestamp 167, binary 140, decimal
  // 135; bytes of one content 84, one high bit apart 47; one decimal at two
  // scales 29, two of one double 25; instants under a millisecond apart 113.
  // Each bound sits about 3x below, so it separates a corpus that stopped
  // producing the shape or the value from one that merely drifted.

  import ExprParityFuzzTest.{TypeTally, typeTally}

  private def typeMessage(t: TypeTally): String =
    s"over ${t.seeds} seeds: compared=${t.compared} inList=${t.inList} bytesOfOneContent=${t.bytesOfOneContent} " +
      s"bytesOneHighBitApart=${t.bytesOneHighBitApart} decimalAtTwoScales=${t.decimalAtTwoScales} " +
      s"decimalsOfOneDouble=${t.decimalsOfOneDouble} instantsUnderAMillisecondApart=${t.instantsUnderAMillisecondApart}"

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
      t.instantsUnderAMillisecondApart > 35)
  }

  @Test def binaryReachesComparisonsWithEqualContentAndHighBitPairs(): Unit = {
    val t = typeTally
    assertTrue(s"a BINARY never reaches a comparison, ${typeMessage(t)}", t.compared("binary") > 80)
    assertTrue(s"a BINARY never reaches an IN list, ${typeMessage(t)}", t.inList("binary") > 40)
    assertTrue(s"two byte arrays of one content never meet, ${typeMessage(t)}", t.bytesOfOneContent > 25)
    assertTrue(s"two byte arrays one high bit apart never meet, ${typeMessage(t)}", t.bytesOneHighBitApart > 15)
  }

  @Test def decimalsReachComparisonsAtTwoScalesAndOfOneDouble(): Unit = {
    val t = typeTally
    assertTrue(s"a DECIMAL never reaches a comparison, ${typeMessage(t)}", t.compared("decimal") > 80)
    assertTrue(s"a DECIMAL never reaches an IN list, ${typeMessage(t)}", t.inList("decimal") > 40)
    assertTrue(s"one decimal at two scales never meets itself, ${typeMessage(t)}", t.decimalAtTwoScales > 9)
    assertTrue(s"two decimals of one double never meet, ${typeMessage(t)}", t.decimalsOfOneDouble > 8)
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
      bytesOfOneContent: Int,
      bytesOneHighBitApart: Int,
      decimalAtTwoScales: Int,
      decimalsOfOneDouble: Int,
      instantsUnderAMillisecondApart: Int)

  /** The tally over the first 20000 seeds, computed once for the four guards. */
  lazy val typeTally: TypeTally = {
    val n = 20000
    val compared, inList = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var oneContent, highBit, twoScales, oneDouble, subMilli = 0
    var seed = 0
    while (seed < n) {
      val c = ExprGen.typeCoverage(ExprGen.generate(new Rng(seed.toLong)))
      c.compared.foreach(k => compared(k) += 1)
      c.inList.foreach(k => inList(k) += 1)
      if (c.bytesOfOneContent) oneContent += 1
      if (c.bytesOneHighBitApart) highBit += 1
      if (c.decimalAtTwoScales) twoScales += 1
      if (c.decimalsOfOneDouble) oneDouble += 1
      if (c.instantsUnderAMillisecondApart) subMilli += 1
      seed += 1
    }
    TypeTally(n, compared.toMap.withDefaultValue(0), inList.toMap.withDefaultValue(0),
      oneContent, highBit, twoScales, oneDouble, subMilli)
  }
}
