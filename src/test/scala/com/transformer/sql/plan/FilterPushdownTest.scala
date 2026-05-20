package com.transformer.sql.plan

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** Unit tests for [[FilterPushdown]].
  *
  * Covers the four join-kind / conjunct-side combinations plus a few
  * compositional shapes (cascaded joins, stacked filters, mixed-side
  * conjuncts). Correctness here is "plan shape" rather than query result —
  * [[com.transformer.sql.exec.SqlEngineTest]] covers the end-to-end
  * correctness through the optimizer.
  */
class FilterPushdownTest {

  // ---------------------------------------------------------------------------
  // Test fixtures
  // ---------------------------------------------------------------------------

  private val schemaLR: Schema = Schema(Vector(
    Field("id", DataType.IntType),
    Field("name", DataType.StringType)))

  private val schemaRR: Schema = Schema(Vector(
    Field("user_id", DataType.IntType),
    Field("score", DataType.IntType)))

  private final class EmptyView(s: Schema) extends CatalogView {
    val schema: Schema = s
    def numPartitions: Int = 0
    def readPartition(p: Int): Iterator[ColumnarBatch] =
      throw new AssertionError("FilterPushdown should not read data")
  }

  private def leftScan: LogicalScan = LogicalScan("l", new EmptyView(schemaLR), Some("l"))
  private def rightScan: LogicalScan = LogicalScan("r", new EmptyView(schemaRR), Some("r"))

  private def colInt(i: Int, n: String): ColRefExpr =
    ColRefExpr(i, n, DataType.IntType)

  private def colStr(i: Int, n: String): ColRefExpr =
    ColRefExpr(i, n, DataType.StringType)

  private def litInt(v: Int): LitExpr = LitExpr(v, DataType.IntType)

  private def bin(op: String, l: Expr, r: Expr): Expr =
    BinOpExpr(op, l, r, DataType.BooleanType)

  private def and(l: Expr, r: Expr): Expr =
    BinOpExpr("AND", l, r, DataType.BooleanType)

  /** Combined indices for left ++ right: id=0, name=1, user_id=2, score=3. */
  private def joinEq: Expr = bin("=", colInt(0, "id"), colInt(2, "user_id"))

  private def innerJoin: LogicalJoin = LogicalJoin(leftScan, rightScan, joinEq, JoinKind.Inner)
  private def leftJoin: LogicalJoin = LogicalJoin(leftScan, rightScan, joinEq, JoinKind.Left)
  private def rightJoin: LogicalJoin = LogicalJoin(leftScan, rightScan, joinEq, JoinKind.Right)
  private def fullJoin: LogicalJoin = LogicalJoin(leftScan, rightScan, joinEq, JoinKind.Full)

  // ---------------------------------------------------------------------------
  // Phase 1: inner-join pushdown
  // ---------------------------------------------------------------------------

  @Test def innerJoinLeftConjunctPushedIntoLeftChild(): Unit = {
    val pred = bin("=", colInt(0, "id"), litInt(7))  // left.id = 7
    val plan = LogicalFilter(innerJoin, pred)
    val out = FilterPushdown(plan)
    out match {
      case LogicalJoin(LogicalFilter(_: LogicalScan, p), _: LogicalScan, _, JoinKind.Inner) =>
        // The conjunct was pushed into the left scan.
        assertTrue("pushed predicate references left col", referencesLeftId(p))
      case other => fail(s"expected Join(Filter(LeftScan), RightScan), got $other")
    }
  }

  @Test def innerJoinRightConjunctPushedIntoRightChild(): Unit = {
    val pred = bin(">", colInt(3, "score"), litInt(50))  // right.score > 50
    val plan = LogicalFilter(innerJoin, pred)
    val out = FilterPushdown(plan)
    out match {
      case LogicalJoin(_: LogicalScan, LogicalFilter(_: LogicalScan, p), _, JoinKind.Inner) =>
        // Right-only conjuncts get re-indexed: score was combined idx 3,
        // becomes idx 1 (its position in r alone).
        p match {
          case BinOpExpr(">", ColRefExpr(1, "score", _), LitExpr(50, _), _) => ()
          case other => fail(s"expected score>50 with re-indexed ColRef, got $other")
        }
      case other => fail(s"expected Join(LeftScan, Filter(RightScan)), got $other")
    }
  }

  @Test def innerJoinSplitsAndChainIntoBothSides(): Unit = {
    val pred = and(
      bin("=", colInt(0, "id"), litInt(7)),       // left
      bin(">", colInt(3, "score"), litInt(50)))   // right
    val out = FilterPushdown(LogicalFilter(innerJoin, pred))
    out match {
      case LogicalJoin(LogicalFilter(_: LogicalScan, lp),
                       LogicalFilter(_: LogicalScan, rp), _, JoinKind.Inner) =>
        assertTrue("left conjunct pushed", referencesLeftId(lp))
        rp match {
          case BinOpExpr(">", ColRefExpr(1, "score", _), _, _) => ()
          case other => fail(s"expected score>50 on right child, got $other")
        }
      case other => fail(s"expected Join(Filter,Filter), got $other")
    }
  }

  @Test def innerJoinCrossSideConjunctStaysAbove(): Unit = {
    // l.id + r.score > 100  → references both sides → cannot be pushed.
    val pred = bin(">",
      BinOpExpr("+", colInt(0, "id"), colInt(3, "score"), DataType.IntType),
      litInt(100))
    val out = FilterPushdown(LogicalFilter(innerJoin, pred))
    out match {
      case LogicalFilter(LogicalJoin(_: LogicalScan, _: LogicalScan, _, _), kept) =>
        assertEquals(pred, kept)
      case other => fail(s"expected Filter(Join), got $other")
    }
  }

  @Test def innerJoinMixedConjunctsSplit(): Unit = {
    // left-only conjunct AND cross-side conjunct → left pushed, cross stays.
    val left = bin("=", colInt(0, "id"), litInt(7))
    val cross = bin(">",
      BinOpExpr("+", colInt(0, "id"), colInt(3, "score"), DataType.IntType),
      litInt(100))
    val out = FilterPushdown(LogicalFilter(innerJoin, and(left, cross)))
    out match {
      case LogicalFilter(LogicalJoin(LogicalFilter(_: LogicalScan, lp),
                                     _: LogicalScan, _, _), kept) =>
        assertTrue("left pushed", referencesLeftId(lp))
        assertEquals(cross, kept)
      case other => fail(s"expected Filter(Join(Filter,Scan)), got $other")
    }
  }

  @Test def innerJoinStackedFiltersFlattenAndPush(): Unit = {
    // Filter(Filter(Join, l.id=7), r.score>50)
    val outer = bin(">", colInt(3, "score"), litInt(50))
    val inner = bin("=", colInt(0, "id"), litInt(7))
    val plan = LogicalFilter(LogicalFilter(innerJoin, inner), outer)
    val out = FilterPushdown(plan)
    out match {
      case LogicalJoin(LogicalFilter(_: LogicalScan, lp),
                       LogicalFilter(_: LogicalScan, rp), _, JoinKind.Inner) =>
        assertTrue("left pushed", referencesLeftId(lp))
        rp match {
          case BinOpExpr(">", ColRefExpr(1, "score", _), _, _) => ()
          case other => fail(s"expected right pushed, got $other")
        }
      case other => fail(s"expected Join with both filters pushed, got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // Phase 2: outer-join pushdown (conservative — never push on null-extended side)
  // ---------------------------------------------------------------------------

  @Test def leftJoinLeftConjunctPushedIntoLeftChild(): Unit = {
    val pred = bin("=", colInt(0, "id"), litInt(7))
    val out = FilterPushdown(LogicalFilter(leftJoin, pred))
    out match {
      case LogicalJoin(LogicalFilter(_: LogicalScan, _), _: LogicalScan, _, JoinKind.Left) => ()
      case other => fail(s"expected Join with left-pushed filter (LEFT outer), got $other")
    }
  }

  @Test def leftJoinRightConjunctStaysAbove(): Unit = {
    // LEFT JOIN preserves left rows with null-extended right. Pushing
    // `r.score > 50` into the right child would drop left rows whose
    // right counterpart didn't pass the filter — but those rows should
    // survive as null-extended. Filter stays above.
    val pred = bin(">", colInt(3, "score"), litInt(50))
    val out = FilterPushdown(LogicalFilter(leftJoin, pred))
    out match {
      case LogicalFilter(LogicalJoin(_: LogicalScan, _: LogicalScan, _, JoinKind.Left), kept) =>
        assertEquals(pred, kept)
      case other => fail(s"expected Filter(LeftJoin) - filter NOT pushed, got $other")
    }
  }

  @Test def leftJoinRightIsNullStaysAbove(): Unit = {
    // The classic "anti-join via LEFT JOIN" shape: `WHERE r.score IS NULL`
    // selects exactly the unmatched left rows. Pushing the IS NULL into the
    // right child would change its semantics — the test catches that.
    val pred = IsNullExpr(colInt(3, "score"), negated = false)
    val out = FilterPushdown(LogicalFilter(leftJoin, pred))
    out match {
      case LogicalFilter(_: LogicalJoin, kept) =>
        assertEquals(pred, kept)
      case other => fail(s"expected Filter(LeftJoin), got $other")
    }
  }

  @Test def rightJoinRightConjunctPushedIntoRightChild(): Unit = {
    val pred = bin(">", colInt(3, "score"), litInt(50))
    val out = FilterPushdown(LogicalFilter(rightJoin, pred))
    out match {
      case LogicalJoin(_: LogicalScan, LogicalFilter(_: LogicalScan, _), _, JoinKind.Right) => ()
      case other => fail(s"expected Join with right-pushed filter (RIGHT outer), got $other")
    }
  }

  @Test def rightJoinLeftConjunctStaysAbove(): Unit = {
    val pred = bin("=", colInt(0, "id"), litInt(7))
    val out = FilterPushdown(LogicalFilter(rightJoin, pred))
    out match {
      case LogicalFilter(LogicalJoin(_, _, _, JoinKind.Right), kept) =>
        assertEquals(pred, kept)
      case other => fail(s"expected Filter(RightJoin) - filter NOT pushed, got $other")
    }
  }

  @Test def fullJoinNeverPushes(): Unit = {
    // Both single-side and cross-side conjuncts must stay above FULL outer.
    val onlyLeft = bin("=", colInt(0, "id"), litInt(7))
    val onlyRight = bin(">", colInt(3, "score"), litInt(50))

    Seq(onlyLeft, onlyRight).foreach { pred =>
      val out = FilterPushdown(LogicalFilter(fullJoin, pred))
      out match {
        case LogicalFilter(LogicalJoin(_, _, _, JoinKind.Full), kept) =>
          assertEquals(pred, kept)
        case other => fail(s"expected Filter(FullJoin), got $other (for pred $pred)")
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Compositional shapes
  // ---------------------------------------------------------------------------

  @Test def cascadedInnerJoinsPushAllTheWayDown(): Unit = {
    // (A ⋈ (B ⋈ C)) — filter on each leaf should cascade past both joins.
    val schemaA: Schema = Schema(Vector(Field("a_id", DataType.IntType)))
    val schemaB: Schema = Schema(Vector(Field("b_id", DataType.IntType)))
    val schemaC: Schema = Schema(Vector(Field("c_id", DataType.IntType)))
    val a = LogicalScan("a", new EmptyView(schemaA), Some("a"))
    val b = LogicalScan("b", new EmptyView(schemaB), Some("b"))
    val c = LogicalScan("c", new EmptyView(schemaC), Some("c"))
    // BC: leftWidth=1, b_id at idx 0, c_id at idx 1.
    val bc = LogicalJoin(b, c, bin("=", colInt(0, "b_id"), colInt(1, "c_id")), JoinKind.Inner)
    // A x BC: leftWidth=1 (a), b_id at idx 1, c_id at idx 2.
    val abc = LogicalJoin(a, bc, bin("=", colInt(0, "a_id"), colInt(1, "b_id")), JoinKind.Inner)
    // Filter on c_id (combined idx 2) — should land under bc's right child (c).
    val pred = bin("=", colInt(2, "c_id"), litInt(99))
    val out = FilterPushdown(LogicalFilter(abc, pred))
    // Expected: Join(A, Join(B, Filter(C, c_id=99)))
    out match {
      case LogicalJoin(
            _: LogicalScan,
            LogicalJoin(_: LogicalScan, LogicalFilter(_: LogicalScan, cp), _, _),
            _, _) =>
        // After two shifts through joins, c_id should land at idx 0.
        cp match {
          case BinOpExpr("=", ColRefExpr(0, "c_id", _), LitExpr(99, _), _) => ()
          case other => fail(s"expected c_id=99 on C scan, got $other")
        }
      case other => fail(s"expected cascaded filter pushdown, got $other")
    }
  }

  @Test def filterAboveAggregateAboveJoinIsNotPushedAcrossAggregate(): Unit = {
    // Filter on top of an Aggregate must NOT cross the aggregate boundary.
    // The pass is a no-op here.
    val agg = LogicalAggregate(
      innerJoin,
      Seq((colInt(0, "id"), "id")),
      Seq((AggExprCountStar(), "n")),
      None)
    val pred = bin(">", colInt(1, "n"), litInt(0))  // post-agg filter
    val plan = LogicalFilter(agg, pred)
    val out = FilterPushdown(plan)
    out match {
      case LogicalFilter(LogicalAggregate(_: LogicalJoin, _, _, _), kept) =>
        assertEquals(pred, kept)
      case other => fail(s"expected Filter(Aggregate(Join)) unchanged, got $other")
    }
  }

  @Test def filterUnchangedWhenNoJoinBelow(): Unit = {
    val pred = bin("=", colInt(0, "id"), litInt(1))
    val plan = LogicalFilter(leftScan, pred)
    val out = FilterPushdown(plan)
    out match {
      case LogicalFilter(_: LogicalScan, kept) => assertEquals(pred, kept)
      case other => fail(s"expected Filter(Scan), got $other")
    }
  }

  @Test def idempotentSecondPassIsNoOp(): Unit = {
    val pred = bin("=", colInt(0, "id"), litInt(7))
    val once = FilterPushdown(LogicalFilter(innerJoin, pred))
    val twice = FilterPushdown(once)
    assertEquals(once.toString, twice.toString)
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def referencesLeftId(e: Expr): Boolean = e match {
    case BinOpExpr("=", ColRefExpr(0, "id", _), LitExpr(7, _), _) => true
    case _ => false
  }
}
