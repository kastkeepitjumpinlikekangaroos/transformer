package com.transformer.sql.plan

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** Unit tests for [[LogicalPlanCardinality]].
  *
  * The estimator's contract is "best-effort, monotone, never panics".
  * Tests cover every plan node and the discrimination of orders-of-magnitude
  * differences — exact selectivity numbers will drift, so assertions stay
  * coarse (ratios, bounds, signs) where possible and exact-pin only the
  * deterministic shapes (literal limit, count(*) → 1).
  */
class LogicalPlanCardinalityTest {

  private val intSchema: Schema = Schema(Vector(Field("a", DataType.IntType), Field("b", DataType.IntType)))

  /** A no-op view that just advertises an `exactRowCount`. The estimator never
    * reads rows; it only consults the count, so this is the minimum surface.
    */
  private final class SizedView(rows: Option[Long], s: Schema = intSchema) extends CatalogView {
    val schema: Schema = s
    def numPartitions: Int = 0
    def readPartition(p: Int): Iterator[ColumnarBatch] =
      throw new AssertionError("cardinality estimator should not read data")
    override val exactRowCount: Option[Long] = rows
  }

  private def scan(rows: Long): LogicalScan =
    LogicalScan("t", new SizedView(Some(rows)), None)

  private def scanUnknown(): LogicalScan =
    LogicalScan("t", new SizedView(None), None)

  private def col(i: Int, name: String, t: DataType = DataType.IntType): ColRefExpr =
    ColRefExpr(i, name, t)

  private def boolLit(v: Boolean): LitExpr = LitExpr(v, DataType.BooleanType)

  @Test def scanWithExactRowCountReturnsIt(): Unit = {
    assertEquals(Some(1234L), LogicalPlanCardinality.estimate(scan(1234L)))
  }

  @Test def scanWithoutExactRowCountReturnsNone(): Unit = {
    assertEquals(None, LogicalPlanCardinality.estimate(scanUnknown()))
  }

  @Test def projectPassesThrough(): Unit = {
    val s = scan(1000L)
    val p = LogicalProject(s, Seq((col(0, "a"), "a")))
    assertEquals(Some(1000L), LogicalPlanCardinality.estimate(p))
  }

  @Test def filterAppliesSelectivity(): Unit = {
    val s = scan(1_000_000L)
    val eqPred = BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType)
    val est = LogicalPlanCardinality.estimate(LogicalFilter(s, eqPred)).get
    // SelectivityEq = 0.1 → 100k. Allow some slack in case the constant ever drifts.
    assertTrue(s"filter est too low: $est", est >= 10_000L)
    assertTrue(s"filter est too high: $est", est <= 500_000L)
    assertTrue(s"filter must shrink: $est >= source", est < 1_000_000L)
  }

  @Test def filterRangeIsLessSelectiveThanEq(): Unit = {
    val s = scan(1_000_000L)
    val eq  = LogicalFilter(s, BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType))
    val gt  = LogicalFilter(s, BinOpExpr(">", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType))
    val eqEst = LogicalPlanCardinality.estimate(eq).get
    val gtEst = LogicalPlanCardinality.estimate(gt).get
    assertTrue(s"= ($eqEst) should be more selective than > ($gtEst)", eqEst < gtEst)
  }

  @Test def filterAndCombinesMultiplicatively(): Unit = {
    val s = scan(1_000_000L)
    val pred = BinOpExpr("AND",
      BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType),
      BinOpExpr("=", col(1, "b"), LitExpr(7, DataType.IntType), DataType.BooleanType),
      DataType.BooleanType
    )
    val combined = LogicalPlanCardinality.estimate(LogicalFilter(s, pred)).get
    val single = LogicalPlanCardinality.estimate(
      LogicalFilter(s, BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType))).get
    assertTrue(s"AND should be tighter than a single =: $combined < $single", combined < single)
  }

  @Test def filterOrIsLessSelectiveThanEither(): Unit = {
    val s = scan(1_000_000L)
    val pred = BinOpExpr("OR",
      BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType),
      BinOpExpr("=", col(1, "b"), LitExpr(7, DataType.IntType), DataType.BooleanType),
      DataType.BooleanType
    )
    val combined = LogicalPlanCardinality.estimate(LogicalFilter(s, pred)).get
    val single = LogicalPlanCardinality.estimate(
      LogicalFilter(s, BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType))).get
    assertTrue(s"OR should be at least as wide as either branch: $combined >= $single", combined >= single)
  }

  @Test def filterPropagatesNoneEstimate(): Unit = {
    val pred = BinOpExpr("=", col(0, "a"), LitExpr(5, DataType.IntType), DataType.BooleanType)
    assertEquals(None, LogicalPlanCardinality.estimate(LogicalFilter(scanUnknown(), pred)))
  }

  @Test def limitCapsByConstant(): Unit = {
    val s = scan(1_000_000L)
    assertEquals(Some(100L), LogicalPlanCardinality.estimate(LogicalLimit(s, 100L)))
  }

  @Test def limitBelowChildKeepsChild(): Unit = {
    val s = scan(50L)
    assertEquals(Some(50L), LogicalPlanCardinality.estimate(LogicalLimit(s, 1000L)))
  }

  @Test def limitWithUnknownChildUsesLimit(): Unit = {
    assertEquals(Some(42L), LogicalPlanCardinality.estimate(LogicalLimit(scanUnknown(), 42L)))
  }

  @Test def distinctShrinksRoughly(): Unit = {
    val est = LogicalPlanCardinality.estimate(LogicalDistinct(scan(100L))).get
    assertTrue(s"distinct must shrink: $est < 100", est < 100L)
    assertTrue(s"distinct must be at least 1: $est", est >= 1L)
  }

  @Test def distinctOnEmptyIsAtLeastOne(): Unit = {
    assertEquals(Some(1L), LogicalPlanCardinality.estimate(LogicalDistinct(scan(1L))))
  }

  @Test def aggregateWithoutGroupKeysCollapsesToOne(): Unit = {
    val s = scan(1_000_000L)
    val agg = LogicalAggregate(s, Seq.empty, Seq((AggExprCountStar(), "n")), None)
    assertEquals(Some(1L), LogicalPlanCardinality.estimate(agg))
  }

  @Test def aggregateWithGroupKeysShrinksAndStaysBounded(): Unit = {
    val s = scan(1_000_000L)
    val agg = LogicalAggregate(s, Seq((col(0, "a"), "a")), Seq((AggExprCountStar(), "n")), None)
    val est = LogicalPlanCardinality.estimate(agg).get
    assertTrue(s"groupby must not exceed input: $est <= 1_000_000", est <= 1_000_000L)
    assertTrue(s"groupby must shrink input: $est < 1_000_000", est < 1_000_000L)
    assertTrue(s"groupby must be at least 1: $est", est >= 1L)
  }

  @Test def aggregateBoundedByInput(): Unit = {
    val s = scan(50L)
    val agg = LogicalAggregate(s, Seq((col(0, "a"), "a")), Seq((AggExprCountStar(), "n")), None)
    val est = LogicalPlanCardinality.estimate(agg).get
    assertTrue(s"groupby result $est must not exceed input 50", est <= 50L)
  }

  @Test def joinInnerTakesMaxSide(): Unit = {
    val cond = BinOpExpr("=", col(0, "a"), col(2, "a"), DataType.BooleanType)
    val j = LogicalJoin(scan(100L), scan(10_000L), cond, JoinKind.Inner)
    assertEquals(Some(10_000L), LogicalPlanCardinality.estimate(j))
  }

  @Test def joinLeftKeepsLeftSize(): Unit = {
    val cond = BinOpExpr("=", col(0, "a"), col(2, "a"), DataType.BooleanType)
    val j = LogicalJoin(scan(100L), scan(10_000L), cond, JoinKind.Left)
    assertEquals(Some(100L), LogicalPlanCardinality.estimate(j))
  }

  @Test def joinRightKeepsRightSize(): Unit = {
    val cond = BinOpExpr("=", col(0, "a"), col(2, "a"), DataType.BooleanType)
    val j = LogicalJoin(scan(100L), scan(10_000L), cond, JoinKind.Right)
    assertEquals(Some(10_000L), LogicalPlanCardinality.estimate(j))
  }

  @Test def joinFullSums(): Unit = {
    val cond = BinOpExpr("=", col(0, "a"), col(2, "a"), DataType.BooleanType)
    val j = LogicalJoin(scan(100L), scan(10_000L), cond, JoinKind.Full)
    assertEquals(Some(10_100L), LogicalPlanCardinality.estimate(j))
  }

  @Test def joinReturnsNoneWhenEitherSideUnknown(): Unit = {
    val cond = BinOpExpr("=", col(0, "a"), col(2, "a"), DataType.BooleanType)
    assertEquals(None, LogicalPlanCardinality.estimate(
      LogicalJoin(scanUnknown(), scan(100L), cond, JoinKind.Inner)))
    assertEquals(None, LogicalPlanCardinality.estimate(
      LogicalJoin(scan(100L), scanUnknown(), cond, JoinKind.Inner)))
  }

  @Test def unionSums(): Unit = {
    assertEquals(Some(150L), LogicalPlanCardinality.estimate(
      LogicalUnion(scan(100L), scan(50L), all = true)))
  }

  @Test def unionAnyUnknownIsUnknown(): Unit = {
    assertEquals(None, LogicalPlanCardinality.estimate(
      LogicalUnion(scan(100L), scanUnknown(), all = true)))
  }

  @Test def sortPassesThrough(): Unit = {
    assertEquals(Some(100L),
      LogicalPlanCardinality.estimate(LogicalSort(scan(100L), Seq((col(0, "a"), true)))))
  }

  @Test def windowPassesThrough(): Unit = {
    val child = scan(123L)
    val spec = WindowSpec(Seq.empty, Seq.empty, WindowFrame.defaultFor(hasOrderBy = false))
    val def0 = WindowDef(spec, WindowFnRowNumber(), "_w0")
    assertEquals(Some(123L),
      LogicalPlanCardinality.estimate(LogicalWindow(child, Seq(def0))))
  }

  @Test def filterSelectivityKnownShapes(): Unit = {
    val eq  = BinOpExpr("=", col(0, "a"), LitExpr(1, DataType.IntType), DataType.BooleanType)
    val neq = BinOpExpr("!=", col(0, "a"), LitExpr(1, DataType.IntType), DataType.BooleanType)
    val lt  = BinOpExpr("<", col(0, "a"), LitExpr(1, DataType.IntType), DataType.BooleanType)
    assertEquals(LogicalPlanCardinality.SelectivityEq,
      LogicalPlanCardinality.filterSelectivity(eq), 1e-9)
    assertEquals(LogicalPlanCardinality.SelectivityNeq,
      LogicalPlanCardinality.filterSelectivity(neq), 1e-9)
    assertEquals(LogicalPlanCardinality.SelectivityRange,
      LogicalPlanCardinality.filterSelectivity(lt), 1e-9)
  }

  @Test def filterSelectivityIsNullAndLike(): Unit = {
    val isn = IsNullExpr(col(0, "a"), negated = false)
    val lk  = LikeExpr(col(0, "a"), LitExpr("x%", DataType.StringType), negated = false)
    assertEquals(LogicalPlanCardinality.SelectivityIsNull,
      LogicalPlanCardinality.filterSelectivity(isn), 1e-9)
    assertEquals(LogicalPlanCardinality.SelectivityLike,
      LogicalPlanCardinality.filterSelectivity(lk), 1e-9)
  }

  @Test def filterSelectivityNotInverts(): Unit = {
    val isn = IsNullExpr(col(0, "a"), negated = false)
    val not = UnaryOpExpr("NOT", isn, DataType.BooleanType)
    val s = LogicalPlanCardinality.filterSelectivity(not)
    assertEquals(1.0 - LogicalPlanCardinality.SelectivityIsNull, s, 1e-9)
  }

  @Test def filterSelectivityUnknownDefaults(): Unit = {
    val s = LogicalPlanCardinality.filterSelectivity(boolLit(true))
    assertEquals(LogicalPlanCardinality.SelectivityDefault, s, 1e-9)
  }
}
