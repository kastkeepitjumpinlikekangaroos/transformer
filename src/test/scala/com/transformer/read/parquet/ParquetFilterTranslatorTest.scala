package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.sql.plan._
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}
import org.junit.Assert._
import org.junit.Test

import java.nio.file.Files

/** Two layers of coverage for the predicate translator:
  *
  *   1. Translator-level: each new shape (`IS NULL`, `IS NOT NULL`, `IN`,
  *      `NOT IN`, `BETWEEN`) translates to Some(FilterPredicate); the
  *      documented bailouts return None. Cheap, fully synthetic.
  *
  *   2. Correctness gate: for every new shape, materialize a parquet
  *      fixture with the column statistics needed to actually exercise
  *      row-group skipping, run the same predicate two ways — brute-force
  *      (no `withPushdownFilter`, applied in-memory) vs pushed
  *      (`withPushdownFilter(pred)`, then the same in-memory filter) — and
  *      assert the row sets are identical. Over-pruning is the silent
  *      failure mode the plan calls out, so this gate is non-negotiable
  *      for each new shape.
  *
  * The push-fired sanity check (raw-row count from the pushed reader is
  * strictly less than the unfiltered count) proves stats-level skipping
  * actually fired — without it a buggy translator that returns Some(predicate)
  * but matches every row group would still pass the correctness gate.
  */
class ParquetFilterTranslatorTest {

  private def col(name: String, dt: DataType, idx: Int = 0): ColRefExpr =
    ColRefExpr(idx, name, dt)
  private def lit(v: Any, dt: DataType): LitExpr = LitExpr(v, dt)
  private def and(l: Expr, r: Expr): BinOpExpr = BinOpExpr("AND", l, r, DataType.BooleanType)

  private def makeBatch(schema: Schema, rows: Seq[Seq[Any]]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, rows.length max 1)
    rows.zipWithIndex.foreach { case (row, i) =>
      row.zipWithIndex.foreach { case (v, c) =>
        if (v == null) b.column(c).setNull(i) else b.column(c).setBoxed(i, v)
      }
    }
    b.setNumRows(rows.length)
    b
  }

  private def collectRows(view: CatalogView): Seq[Map[String, Any]] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    (0 until view.numPartitions).foreach { p =>
      val it = view.readPartition(p)
      while (it.hasNext) {
        val b = it.next()
        var r = 0
        while (r < b.numRows) {
          val m = view.schema.fieldNames.zipWithIndex.map { case (n, i) =>
            n -> (if (b.column(i).isNull(r)) null else b.column(i).getBoxed(r))
          }.toMap
          buf += m
          r += 1
        }
      }
    }
    buf.toSeq
  }

  private def rawRowCount(view: CatalogView): Long = {
    var n = 0L
    (0 until view.numPartitions).foreach { p =>
      val it = view.readPartition(p)
      while (it.hasNext) { n += it.next().numRows; () }
    }
    n
  }

  // ─────────────────────────────────────────────────────────────────────
  // Translator-level: shape coverage.
  // ─────────────────────────────────────────────────────────────────────

  @Test def translatesIsNullOnIntColumn(): Unit = {
    val out = ParquetFilterTranslator.translate(
      IsNullExpr(col("x", DataType.IntType), negated = false))
    assertTrue("IS NULL on int should translate", out.isDefined)
  }

  @Test def translatesIsNotNullOnLongColumn(): Unit = {
    val out = ParquetFilterTranslator.translate(
      IsNullExpr(col("ts", DataType.LongType), negated = true))
    assertTrue("IS NOT NULL on long should translate", out.isDefined)
  }

  @Test def translatesIsNullOnAllSupportedTypes(): Unit = {
    val types = Seq[DataType](
      DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
      DataType.BooleanType, DataType.StringType, DataType.BinaryType,
      DataType.DateType, DataType.TimestampType)
    types.foreach { t =>
      val isNullOut = ParquetFilterTranslator.translate(IsNullExpr(col("c", t), negated = false))
      val isNotNullOut = ParquetFilterTranslator.translate(IsNullExpr(col("c", t), negated = true))
      assertTrue(s"IS NULL on $t should translate", isNullOut.isDefined)
      assertTrue(s"IS NOT NULL on $t should translate", isNotNullOut.isDefined)
    }
  }

  @Test def doesNotTranslateIsNullOnDecimal(): Unit = {
    val out = ParquetFilterTranslator.translate(
      IsNullExpr(col("price", DataType.DecimalType(18, 4)), negated = false))
    assertTrue("IS NULL on decimal should remain residual", out.isEmpty)
  }

  @Test def doesNotTranslateIsNullOnComputedExpression(): Unit = {
    // `(a + 1) IS NULL` — child isn't a bare ColRef, must not push.
    val computed = BinOpExpr("+", col("a", DataType.IntType), lit(1, DataType.IntType), DataType.IntType)
    val out = ParquetFilterTranslator.translate(IsNullExpr(computed, negated = false))
    assertTrue(out.isEmpty)
  }

  @Test def translatesInListOnInts(): Unit = {
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("x", DataType.IntType),
        Seq(lit(1, DataType.IntType), lit(5, DataType.IntType), lit(9, DataType.IntType)),
        negated = false))
    assertTrue("IN over int literals should translate", out.isDefined)
  }

  @Test def translatesInListOnStrings(): Unit = {
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("c", DataType.StringType),
        Seq(lit("a", DataType.StringType), lit("b", DataType.StringType)),
        negated = false))
    assertTrue("IN over string literals should translate", out.isDefined)
  }

  @Test def translatesNotInList(): Unit = {
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("x", DataType.IntType),
        Seq(lit(1, DataType.IntType), lit(2, DataType.IntType)),
        negated = true))
    assertTrue("NOT IN over literals should translate", out.isDefined)
  }

  @Test def doesNotTranslateInListWithNullLiteral(): Unit = {
    // SQL three-valued logic: `x IN (1, NULL, 3)` is NULL (not false) for x ∉ {1,3}.
    // Pushing as OR of equalities would over-prune.
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("x", DataType.IntType),
        Seq(lit(1, DataType.IntType), lit(null, DataType.IntType), lit(3, DataType.IntType)),
        negated = false))
    assertTrue("IN with NULL literal must bail", out.isEmpty)
  }

  @Test def doesNotTranslateNotInListWithNullLiteral(): Unit = {
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("x", DataType.IntType),
        Seq(lit(1, DataType.IntType), lit(null, DataType.IntType)),
        negated = true))
    assertTrue("NOT IN with NULL literal must bail", out.isEmpty)
  }

  @Test def doesNotTranslateInListWithNonLiteral(): Unit = {
    val items = Seq[Expr](
      lit(1, DataType.IntType),
      col("y", DataType.IntType))  // non-literal item
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("x", DataType.IntType), items, negated = false))
    assertTrue("IN with a non-literal item must bail", out.isEmpty)
  }

  @Test def doesNotTranslateEmptyInList(): Unit = {
    val out = ParquetFilterTranslator.translate(
      InListExpr(col("x", DataType.IntType), Seq.empty, negated = false))
    assertTrue(out.isEmpty)
  }

  @Test def translatesBetweenAsAndOfRangePredicates(): Unit = {
    // BETWEEN is lowered to `c >= lo AND c <= hi` by LogicalBuilder, so it
    // hits the AND-then-compareToLiteral path. Lock that in here so a future
    // refactor that breaks the lowering doesn't silently drop pushdown for
    // BETWEEN queries.
    val between = and(
      BinOpExpr(">=", col("x", DataType.IntType), lit(10, DataType.IntType), DataType.BooleanType),
      BinOpExpr("<=", col("x", DataType.IntType), lit(20, DataType.IntType), DataType.BooleanType))
    assertTrue("BETWEEN-shaped AND of >= / <= should translate", ParquetFilterTranslator.translate(between).isDefined)
  }

  @Test def translatesMixedAndOfOldAndNewShapes(): Unit = {
    // The polymarket stg_orderbook query shape: a chain of IS NOT NULL
    // followed by range comparisons. Every conjunct should land.
    val pred = and(
      and(
        IsNullExpr(col("c", DataType.LongType), negated = true),  // IS NOT NULL
        BinOpExpr(">", col("c", DataType.LongType), lit(100L, DataType.LongType), DataType.BooleanType)),
      InListExpr(col("s", DataType.StringType),
        Seq(lit("a", DataType.StringType), lit("b", DataType.StringType)),
        negated = false))
    assertTrue(ParquetFilterTranslator.translate(pred).isDefined)
  }

  // ─────────────────────────────────────────────────────────────────────
  // Correctness gate: brute-force vs pushed must produce identical row sets.
  //
  // Fixture layout (three files → three row groups, scoped to make the
  // statistics filter actually fire):
  //   - low.parquet:        n in [0..99], no nulls in c
  //   - high_nulls.parquet: n in [200..299], c is all null
  //   - mixed.parquet:      n in [400..499], every fifth row's c is null
  //
  // Stats facts that the translator relies on:
  //   - low has numNulls = 0   → `c IS NULL` drops it.
  //   - high_nulls has numNulls = numRows → `c IS NOT NULL` drops it.
  //   - mixed straddles both → keeps in both predicates.
  //   - n ranges are disjoint → `n IN (...)` skips groups whose stats
  //     don't cover any of the literals.
  // ─────────────────────────────────────────────────────────────────────

  private val fixtureSchema = Schema(
    Field("n", DataType.IntType),
    Field("c", DataType.StringType))

  private def buildFixture(): java.nio.file.Path = {
    val dir = Files.createTempDirectory("pq-pushdown-translator-")
    TParquetWriter.writeAll(dir.resolve("low.parquet"), fixtureSchema,
      Iterator(makeBatch(fixtureSchema, (0 until 100).map(i => Seq[Any](i, s"v$i")))))
    TParquetWriter.writeAll(dir.resolve("high_nulls.parquet"), fixtureSchema,
      Iterator(makeBatch(fixtureSchema, (200 until 300).map(i => Seq[Any](i, null)))))
    TParquetWriter.writeAll(dir.resolve("mixed.parquet"), fixtureSchema,
      Iterator(makeBatch(fixtureSchema, (400 until 500).map(i =>
        Seq[Any](i, if (i % 5 == 0) null else s"v$i")))))
    dir
  }

  /** Filter rows in-memory by exhaustively evaluating `pred` against each row.
    * Bypasses the SQL planner entirely so we have a "ground truth" the
    * pushdown variant can be compared against.
    */
  private def applyInMemory(rows: Seq[Map[String, Any]], pred: Map[String, Any] => Boolean): Seq[Map[String, Any]] =
    rows.filter(pred)

  /** Brute-force vs pushed identity check. `pred` is the transformer Expr we
    * push; `inMemoryFilter` is the equivalent predicate in Scala. Asserts:
    *   - pushed reader returned `Some` (the translator actually accepted the shape);
    *   - pushed reader's raw row count is STRICTLY less than the full count
    *     (otherwise pushdown didn't fire and the test isn't proving anything);
    *   - row sets after applying the in-memory filter are identical.
    */
  private def assertPushdownAgreesWithBruteForce(
      dir: java.nio.file.Path,
      pred: Expr,
      inMemoryFilter: Map[String, Any] => Boolean,
      expectStrictSkip: Boolean = true): Unit = {
    val reader = ParquetReader.fromPath(dir.toString)
    val bruteRows = applyInMemory(collectRows(reader), inMemoryFilter).toSet
    val pushed = reader.withPushdownFilter(pred).getOrElse(
      fail(s"expected predicate to translate: $pred").asInstanceOf[CatalogView])
    val pushedRowsRaw = collectRows(pushed)
    val pushedRows = applyInMemory(pushedRowsRaw, inMemoryFilter).toSet
    if (expectStrictSkip) {
      assertTrue(
        s"pushdown didn't fire: pushed raw count ${pushedRowsRaw.size} == unfiltered ${rawRowCount(reader)}",
        pushedRowsRaw.size.toLong < rawRowCount(reader))
    }
    assertEquals("brute-force and pushed row sets must match", bruteRows, pushedRows)
  }

  @Test def isNullPushdownMatchesBruteForce(): Unit = {
    val dir = buildFixture()
    // c IS NULL: drops the low.parquet group (numNulls=0), keeps the other two.
    val pred = IsNullExpr(col("c", DataType.StringType, idx = 1), negated = false)
    assertPushdownAgreesWithBruteForce(dir, pred, row => row("c") == null)
  }

  @Test def isNotNullPushdownMatchesBruteForce(): Unit = {
    val dir = buildFixture()
    // c IS NOT NULL: drops high_nulls (all-null group), keeps the other two.
    val pred = IsNullExpr(col("c", DataType.StringType, idx = 1), negated = true)
    assertPushdownAgreesWithBruteForce(dir, pred, row => row("c") != null)
  }

  @Test def inListPushdownMatchesBruteForce(): Unit = {
    val dir = buildFixture()
    // n IN (5, 50, 95): all three literals fall in [0..99]. Stats for low's
    // [0..99] keep it; the other two groups' stats prove non-match → skip.
    val pred = InListExpr(col("n", DataType.IntType, idx = 0),
      Seq(lit(5, DataType.IntType), lit(50, DataType.IntType), lit(95, DataType.IntType)),
      negated = false)
    val want = Set(5, 50, 95)
    assertPushdownAgreesWithBruteForce(dir, pred, row => want.contains(row("n").asInstanceOf[Int]))
  }

  @Test def notInListPushdownMatchesBruteForce(): Unit = {
    val dir = buildFixture()
    // n NOT IN (200, 210, 220): stats prove the high_nulls [200..299] group
    // MAY contain matches (so it must be kept); the low [0..99] and mixed
    // [400..499] groups' min/max prove no value equals 200/210/220 → the
    // NOT-of-OR-of-eq predicate is satisfied for every row in those groups.
    //
    // parquet-mr's StatisticsFilter is conservative on NOT — it'll typically
    // keep all groups for a wrapped NOT. So we don't require a strict skip
    // here, just correctness.
    val pred = InListExpr(col("n", DataType.IntType, idx = 0),
      Seq(lit(200, DataType.IntType), lit(210, DataType.IntType), lit(220, DataType.IntType)),
      negated = true)
    val excl = Set(200, 210, 220)
    assertPushdownAgreesWithBruteForce(dir, pred,
      row => !excl.contains(row("n").asInstanceOf[Int]),
      expectStrictSkip = false)
  }

  @Test def betweenPushdownMatchesBruteForce(): Unit = {
    val dir = buildFixture()
    // n BETWEEN 50 AND 250: drops mixed [400..499] entirely; low and
    // high_nulls survive stats and get row-level checked.
    val pred = and(
      BinOpExpr(">=", col("n", DataType.IntType, idx = 0), lit(50, DataType.IntType), DataType.BooleanType),
      BinOpExpr("<=", col("n", DataType.IntType, idx = 0), lit(250, DataType.IntType), DataType.BooleanType))
    assertPushdownAgreesWithBruteForce(dir, pred, row => {
      val n = row("n").asInstanceOf[Int]
      n >= 50 && n <= 250
    })
  }

  @Test def isNotNullCombinedWithRangePushdownMatchesBruteForce(): Unit = {
    val dir = buildFixture()
    // Mimics the polymarket stg_orderbook shape: `c IS NOT NULL AND n > N`.
    // Both conjuncts push; stats drop high_nulls (numNulls = numRows) on
    // the first conjunct and low (max=99 < 150) on the second.
    val pred = and(
      IsNullExpr(col("c", DataType.StringType, idx = 1), negated = true),
      BinOpExpr(">", col("n", DataType.IntType, idx = 0), lit(150, DataType.IntType), DataType.BooleanType))
    assertPushdownAgreesWithBruteForce(dir, pred,
      row => row("c") != null && row("n").asInstanceOf[Int] > 150)
  }

  @Test def inListOutsideAllGroupsDropsEverything(): Unit = {
    // Sanity: IN over values that no row group can contain skips every group.
    val dir = buildFixture()
    val pred = InListExpr(col("n", DataType.IntType, idx = 0),
      Seq(lit(9999, DataType.IntType), lit(8888, DataType.IntType)),
      negated = false)
    val reader = ParquetReader.fromPath(dir.toString)
    val pushed = reader.withPushdownFilter(pred).getOrElse(
      fail("expected IN to translate").asInstanceOf[CatalogView])
    // Every row group's stats prove it can't satisfy n IN (9999, 8888) → all
    // three groups skip and the iterator produces no rows.
    assertEquals(0L, rawRowCount(pushed))
  }
}
