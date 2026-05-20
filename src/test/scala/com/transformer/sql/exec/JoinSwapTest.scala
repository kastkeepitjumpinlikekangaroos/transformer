package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

/** Planner-level tests for cardinality-driven join build-side swapping.
  *
  * The implementation (Phase 2) adds a 7th param `buildRight: Boolean` to
  * [[HashJoinExec]] and teaches [[PhysicalPlanner]] to set it based on
  * estimated child cardinalities and join kind:
  *
  *   - Inner:       swap (buildRight=false) when leftEst * 2 <= rightEst.
  *   - Left outer:  never swap (buildRight stays true).
  *   - Right outer: always swap (buildRight=false).
  *   - Full outer:  never swap (buildRight stays true).
  *   - Missing est: default (buildRight=true).
  *
  * Until the impl lands these tests are expected to fail. The whitebox tests
  * read [[HashJoinExec]] via [[scala.Product]] so they survive both the
  * "param doesn't exist" and "param exists" worlds without a compile break.
  */
class JoinSwapTest {

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private val LeftSchema: Schema = Schema(Vector(
    Field("lk", DataType.IntType),
    Field("lv", DataType.IntType)
  ))

  private val RightSchema: Schema = Schema(Vector(
    Field("rk", DataType.IntType),
    Field("rv", DataType.IntType)
  ))

  /** A [[CatalogView]] that holds rows in memory but reports any row count it
    * is told to report from [[exactRowCount]]. The planner reads
    * `exactRowCount` to estimate cardinality, but the join still executes
    * over the real (potentially much smaller) row set — so a single test can
    * deterministically force a swap decision without materializing 10k rows.
    */
  private final class FakeCountView(
      val schema: Schema,
      rows: Seq[Array[Any]],
      reportedCount: Option[Long]
  ) extends CatalogView {
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] = {
      require(p == 0)
      if (rows.isEmpty) {
        val b = new ColumnarBatch(schema, 1)
        b.setNumRows(0)
        Iterator.single(b)
      } else {
        val b = new ColumnarBatch(schema, rows.length)
        var r = 0
        while (r < rows.length) {
          val row = rows(r)
          var c = 0
          while (c < schema.length) {
            if (row(c) == null) b.column(c).setNull(r)
            else b.column(c).setBoxed(r, row(c))
            c += 1
          }
          r += 1
        }
        b.setNumRows(rows.length)
        Iterator.single(b)
      }
    }
    override val exactRowCount: Option[Long] = reportedCount
  }

  private def view(schema: Schema, rows: Seq[Array[Any]], reportedCount: Option[Long]): CatalogView =
    new FakeCountView(schema, rows, reportedCount)

  private def emptyView(schema: Schema, reportedCount: Option[Long]): CatalogView =
    new FakeCountView(schema, Nil, reportedCount)

  /** Build a `lk = rk` equi-join condition. ColRef indices are absolute over
    * the combined `left.outputSchema ++ right.outputSchema`. `lk` lives at
    * index 0; `rk` lives at index `leftSchema.length`.
    */
  private def equiCondition(leftSchema: Schema, rightSchema: Schema): Expr =
    BinOpExpr(
      "=",
      ColRefExpr(0, "lk", DataType.IntType),
      ColRefExpr(leftSchema.length, "rk", DataType.IntType),
      DataType.BooleanType
    )

  private def buildJoinPlan(
      leftView: CatalogView,
      rightView: CatalogView,
      kind: JoinKind,
      condition: Option[Expr] = None
  ): LogicalPlan = {
    val left = LogicalScan("l", leftView, Some("l"))
    val right = LogicalScan("r", rightView, Some("r"))
    val cond = condition.getOrElse(equiCondition(leftView.schema, rightView.schema))
    LogicalJoin(left, right, cond, kind)
  }

  /** Locate the [[HashJoinExec]] in the physical plan. The planner may wrap a
    * scan in [[FilterExec]] etc., but at the join level the top-most operator
    * for these tests is the join itself.
    */
  private def findHashJoin(p: PhysicalPlan): HashJoinExec = p match {
    case j: HashJoinExec => j
    case other =>
      fail(s"Expected HashJoinExec at top of plan, got ${other.getClass.getSimpleName}")
      throw new IllegalStateException("unreachable")
  }

  /** Read `buildRight` from the join's product representation. Returns
    *   - `Some(true)`  if a Boolean appears in the product and is true,
    *   - `Some(false)` if a Boolean appears and is false,
    *   - `None` if no Boolean product element exists (param not implemented yet).
    *
    * We don't index by position because the surrounding case class shape
    * could change; we look for the single Boolean entry. The current
    * signature is (left, right, leftKeys, rightKeys, extra, kind), all
    * non-Boolean — so any Boolean we find is the new `buildRight`.
    */
  private def buildRightFrom(j: HashJoinExec): Option[Boolean] = {
    val arity = j.productArity
    var i = 0
    var found: Option[Boolean] = None
    while (i < arity) {
      j.productElement(i) match {
        case b: java.lang.Boolean =>
          if (found.isDefined) fail("Multiple Boolean fields on HashJoinExec; cannot identify buildRight")
          found = Some(b.booleanValue)
        case _ =>
      }
      i += 1
    }
    found
  }

  /** Assert the planner picked `buildRight=expected`. If the param doesn't
    * exist yet (no Boolean on the case class), the test fails with a
    * descriptive message — that's the expected pre-implementation state.
    */
  private def assertBuildRight(j: HashJoinExec, expected: Boolean, why: String): Unit = {
    buildRightFrom(j) match {
      case Some(actual) =>
        assertEquals(s"$why: expected buildRight=$expected, got $actual", expected, actual)
      case None =>
        fail(s"$why: HashJoinExec has no Boolean product field — the `buildRight` parameter has not been added yet")
    }
  }

  private def collectAllRows(q: ExecutedQuery): Seq[Map[String, Any]] = {
    val buf = mutable.ArrayBuffer.empty[Map[String, Any]]
    while (q.batches.hasNext) {
      val b = q.batches.next()
      var r = 0
      while (r < b.numRows) {
        buf += q.schema.fieldNames.zipWithIndex.map { case (n, i) =>
          n -> (if (b.column(i).isNull(r)) null else b.column(i).getBoxed(r))
        }.toMap
        r += 1
      }
    }
    buf.toSeq
  }

  private def catalogWith(views: (String, CatalogView)*): Catalog = {
    val c = new Catalog
    views.foreach { case (n, v) => c.register(n, v) }
    c
  }

  private def row(values: Any*): Array[Any] = values.toArray

  // ---------------------------------------------------------------------------
  // Whitebox planner inspection tests
  //
  // Post-implementation expectations are documented inline. Each test
  // forces a cardinality scenario by lying about `exactRowCount` on the
  // scanned views, runs PhysicalPlanner.plan, and checks the resulting
  // HashJoinExec's `buildRight` flag.
  // ---------------------------------------------------------------------------

  /** Post-impl expectation: leftEst=1000, rightEst=10000 → leftEst*2 (=2000) <=
    * rightEst, so the planner picks the smaller (left) as the build side:
    * buildRight=false.
    */
  @Test def innerJoinLeftMuchSmallerSwaps(): Unit = {
    val l = emptyView(LeftSchema, Some(1000L))
    val r = emptyView(RightSchema, Some(10000L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Inner))
    assertBuildRight(findHashJoin(plan), expected = false,
      why = "inner join with left << right should swap to build the smaller left side")
  }

  /** Post-impl expectation: rightEst is already the smaller side, so the
    * default (buildRight=true) is kept — no swap.
    */
  @Test def innerJoinRightSmallerDoesNotSwap(): Unit = {
    val l = emptyView(LeftSchema, Some(10000L))
    val r = emptyView(RightSchema, Some(1000L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Inner))
    assertBuildRight(findHashJoin(plan), expected = true,
      why = "inner join with right smaller should keep the default (no swap)")
  }

  /** Post-impl expectation: 1500 < 1000*2, so the swap threshold is NOT
    * met — buildRight stays at the default true.
    */
  @Test def innerJoinNearEqualKeepsDefault(): Unit = {
    val l = emptyView(LeftSchema, Some(1000L))
    val r = emptyView(RightSchema, Some(1500L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Inner))
    assertBuildRight(findHashJoin(plan), expected = true,
      why = "inner join near-equal sizes should stay below the 2x swap threshold")
  }

  /** Post-impl expectation: 1000*2 == 2000, and 2001 > 2000 — exactly above
    * the threshold, so swap fires: buildRight=false.
    */
  @Test def innerJoinJustAboveThresholdSwaps(): Unit = {
    val l = emptyView(LeftSchema, Some(1000L))
    val r = emptyView(RightSchema, Some(2001L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Inner))
    assertBuildRight(findHashJoin(plan), expected = false,
      why = "inner join just above the 2x threshold should swap")
  }

  /** Post-impl expectation: with both estimates missing, the planner has no
    * basis to decide — it keeps the default (buildRight=true).
    */
  @Test def innerJoinNoEstimatesKeepsDefault(): Unit = {
    val l = emptyView(LeftSchema, None)
    val r = emptyView(RightSchema, None)
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Inner))
    assertBuildRight(findHashJoin(plan), expected = true,
      why = "inner join with no cardinality info should keep the default (no swap)")
  }

  /** Post-impl expectation: LEFT OUTER never swaps, even when the left side
    * is much larger than the right — preserving left rows requires probing
    * from the left.
    */
  @Test def leftOuterJoinNeverSwapsEvenWhenLeftHuge(): Unit = {
    val l = emptyView(LeftSchema, Some(1000000L))
    val r = emptyView(RightSchema, Some(100L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Left))
    assertBuildRight(findHashJoin(plan), expected = true,
      why = "LEFT OUTER never swaps; build must stay on the right")
  }

  /** Post-impl expectation: RIGHT OUTER always swaps so right-row preservation
    * works the other way around — buildRight=false irrespective of sizes.
    */
  @Test def rightOuterJoinAlwaysSwaps(): Unit = {
    val l = emptyView(LeftSchema, Some(100L))
    val r = emptyView(RightSchema, Some(1000000L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Right))
    assertBuildRight(findHashJoin(plan), expected = false,
      why = "RIGHT OUTER always swaps so the right side becomes the probe")
  }

  /** Post-impl expectation: FULL OUTER does not swap; the matched-build
    * tracking only flows the default direction.
    */
  @Test def fullOuterJoinDoesNotSwap(): Unit = {
    val l = emptyView(LeftSchema, Some(1000000L))
    val r = emptyView(RightSchema, Some(100L))
    val plan = PhysicalPlanner.plan(buildJoinPlan(l, r, JoinKind.Full))
    assertBuildRight(findHashJoin(plan), expected = true,
      why = "FULL OUTER stays at the default build side")
  }

  // ---------------------------------------------------------------------------
  // End-to-end correctness tests
  //
  // These set up real data on both sides, lie about `exactRowCount` to force
  // a particular swap decision, run SQL via SqlEngine, and check that the
  // result rows match the un-swapped reference.
  // ---------------------------------------------------------------------------

  private val SmallLeftRows: Seq[Array[Any]] = Seq(
    row(1, 10),
    row(2, 20),
    row(3, 30),
    row(4, 40),
    row(5, 50)
  )

  private val SmallRightRows: Seq[Array[Any]] = Seq(
    row(1, 100),
    row(3, 300),
    row(5, 500),
    row(7, 700),
    row(9, 900)
  )

  /** Post-impl expectation: an inner join produces identical output regardless
    * of which side the planner builds. Force the swap by lying about cardinality
    * in two scenarios and verify both row sets are the same when sorted.
    */
  @Test def innerJoinResultsAreSameUnderSwap(): Unit = {
    val swapped = {
      val l = view(LeftSchema, SmallLeftRows, Some(10L))
      val r = view(RightSchema, SmallRightRows, Some(1000000L))
      val cat = catalogWith("l" -> l, "r" -> r)
      collectAllRows(SqlEngine.execute(
        "SELECT l.lk, l.lv, r.rk, r.rv FROM l JOIN r ON l.lk = r.rk",
        cat))
    }
    val unswapped = {
      val l = view(LeftSchema, SmallLeftRows, Some(1000000L))
      val r = view(RightSchema, SmallRightRows, Some(10L))
      val cat = catalogWith("l" -> l, "r" -> r)
      collectAllRows(SqlEngine.execute(
        "SELECT l.lk, l.lv, r.rk, r.rv FROM l JOIN r ON l.lk = r.rk",
        cat))
    }
    val expected = Seq(
      Map("lk" -> 1, "lv" -> 10, "rk" -> 1, "rv" -> 100),
      Map("lk" -> 3, "lv" -> 30, "rk" -> 3, "rv" -> 300),
      Map("lk" -> 5, "lv" -> 50, "rk" -> 5, "rv" -> 500)
    )
    val key: Map[String, Any] => Int = _("lk").asInstanceOf[Int]
    assertEquals("swapped inner join rows", expected, swapped.sortBy(key))
    assertEquals("unswapped inner join rows", expected, unswapped.sortBy(key))
  }

  /** Post-impl expectation: LEFT OUTER doesn't swap, so the standard
    * probe-from-left path runs. Unmatched left rows must still appear with
    * NULL right columns.
    */
  @Test def leftOuterJoinPreservesUnmatchedLeftRows(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, Some(1000000L))
    val r = view(RightSchema, SmallRightRows, Some(10L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val rows = collectAllRows(SqlEngine.execute(
      "SELECT l.lk, l.lv, r.rk, r.rv FROM l LEFT JOIN r ON l.lk = r.rk",
      cat))
    val sorted = rows.sortBy(_("lk").asInstanceOf[Int])
    assertEquals(5, sorted.size)
    assertEquals(Seq(1, 2, 3, 4, 5), sorted.map(_("lk")))
    assertEquals(Seq(10, 20, 30, 40, 50), sorted.map(_("lv")))
    assertEquals(Seq[Any](1, null, 3, null, 5), sorted.map(_("rk")))
    assertEquals(Seq[Any](100, null, 300, null, 500), sorted.map(_("rv")))
  }

  /** Post-impl expectation: RIGHT OUTER sets buildRight=false. The matched
    * tracking is flipped accordingly so every right row appears in the
    * output, including the unmatched ones with NULL left columns.
    */
  @Test def rightOuterJoinPreservesUnmatchedRightRowsUnderSwap(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, Some(10L))
    val r = view(RightSchema, SmallRightRows, Some(1000000L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val rows = collectAllRows(SqlEngine.execute(
      "SELECT l.lk, l.lv, r.rk, r.rv FROM l RIGHT JOIN r ON l.lk = r.rk",
      cat))
    val sorted = rows.sortBy(_("rk").asInstanceOf[Int])
    assertEquals(5, sorted.size)
    assertEquals(Seq(1, 3, 5, 7, 9), sorted.map(_("rk")))
    assertEquals(Seq(100, 300, 500, 700, 900), sorted.map(_("rv")))
    assertEquals(Seq[Any](1, 3, 5, null, null), sorted.map(_("lk")))
    assertEquals(Seq[Any](10, 30, 50, null, null), sorted.map(_("lv")))
  }

  /** Post-impl expectation: FULL OUTER doesn't swap and emits every row from
    * both sides, with NULL on the non-matching side.
    */
  @Test def fullOuterJoinEmitsBothUnmatchedSides(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, Some(1000L))
    val r = view(RightSchema, SmallRightRows, Some(1000L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val rows = collectAllRows(SqlEngine.execute(
      "SELECT l.lk, l.lv, r.rk, r.rv FROM l FULL JOIN r ON l.lk = r.rk",
      cat))
    val matched = rows.filter(r => r("lk") != null && r("rk") != null)
      .sortBy(_("lk").asInstanceOf[Int])
    val leftOnly = rows.filter(_("rk") == null).sortBy(_("lk").asInstanceOf[Int])
    val rightOnly = rows.filter(_("lk") == null).sortBy(_("rk").asInstanceOf[Int])
    assertEquals(7, rows.size)
    assertEquals(Seq(1, 3, 5), matched.map(_("lk")))
    assertEquals(Seq(1, 3, 5), matched.map(_("rk")))
    assertEquals(Seq(2, 4), leftOnly.map(_("lk")))
    assertEquals(Seq(7, 9), rightOnly.map(_("rk")))
  }

  // ---------------------------------------------------------------------------
  // Nested-loop size guard (no equality conjuncts → degenerate hash plan)
  // ---------------------------------------------------------------------------

  /** Without any equality conjunct the planner produces a degenerate-hash
    * join (everything in a single bucket). That's fine when the inputs are
    * tiny, and the small × small case must keep working — this test pins
    * the under-threshold path with known sizes (5 rows × 5 rows). */
  @Test def nonEquiJoinSmallSidesIsAllowed(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, Some(5L))
    val r = view(RightSchema, SmallRightRows, Some(5L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val rows = collectAllRows(SqlEngine.execute(
      "SELECT l.lk, r.rk FROM l JOIN r ON l.lv < r.rv",
      cat))
    assertEquals(25, rows.size)
  }

  /** When both sides are known to exceed [[PhysicalPlanner.NestedLoopMaxRows]],
    * the planner refuses rather than silently planning an O(N*M) join. */
  @Test def nonEquiJoinHugeSidesRefused(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, Some(1_000_000L))
    val r = view(RightSchema, SmallRightRows, Some(1_000_000L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val ex = try {
      SqlEngine.execute("SELECT l.lk FROM l JOIN r ON l.lv < r.rv", cat)
      null
    } catch { case e: UnsupportedOperationException => e }
    assertNotNull("expected UnsupportedOperationException on large non-equi join", ex)
    assertTrue(ex.getMessage, ex.getMessage.toLowerCase.contains("non-equi join"))
    assertTrue(ex.getMessage, ex.getMessage.contains("equality keys"))
  }

  /** Estimate unknown on either side → the planner can't size the join, so
    * it falls back to the permissive path (matches CSV inputs without
    * `exactRowCount`). */
  @Test def nonEquiJoinUnknownSizesIsAllowed(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, None)
    val r = view(RightSchema, SmallRightRows, Some(1_000_000L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val rows = collectAllRows(SqlEngine.execute(
      "SELECT l.lk FROM l JOIN r ON l.lv < r.rv",
      cat))
    assertEquals(25, rows.size)
  }

  /** Post-impl expectation: a residual non-equi predicate (kept on the join
    * after equality split) must still apply correctly after the swap. The
    * `l.lv < r.rv` filter rules out no rows here (all right values are 10x
    * the matching left values), so we get the full inner match set; an
    * additional filter `l.lv >= r.rv` rules out all of them.
    */
  @Test def innerJoinWithResidualPredicateUnderSwap(): Unit = {
    val l = view(LeftSchema, SmallLeftRows, Some(10L))
    val r = view(RightSchema, SmallRightRows, Some(1000000L))
    val cat = catalogWith("l" -> l, "r" -> r)
    val pass = collectAllRows(SqlEngine.execute(
      "SELECT l.lk, l.lv, r.rv FROM l JOIN r ON l.lk = r.rk AND l.lv < r.rv",
      cat))
    val passSorted = pass.sortBy(_("lk").asInstanceOf[Int])
    assertEquals(3, passSorted.size)
    assertEquals(Seq(1, 3, 5), passSorted.map(_("lk")))
    assertEquals(Seq(100, 300, 500), passSorted.map(_("rv")))

    val none = collectAllRows(SqlEngine.execute(
      "SELECT l.lk FROM l JOIN r ON l.lk = r.rk AND l.lv >= r.rv",
      cat))
    assertEquals(0, none.size)
  }
}
