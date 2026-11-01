package com.transformer.gui

import com.transformer.core.{ColumnarBatch, DataType, Field, Schema}

import org.junit.Assert._
import org.junit.Test

import java.time.{Instant, LocalDate}

/** What a chart draws from a result's rows: the spec a result suggests and
  * keeps, rows sharing an X combined, the order of bands, series per Y
  * column or per split value folded past eight, a scatter's rows as they
  * are, and what is left out and said in a note. */
class ChartDataTest {
  import ChartType._

  private def schema(fields: (String, DataType)*): Schema =
    Schema(fields.map { case (n, t) => Field(n, t) }.toVector)

  private def batch(s: Schema, rows: Seq[Any]*): ColumnarBatch = {
    val b = new ColumnarBatch(s, math.max(1, rows.size))
    rows.zipWithIndex.foreach { case (row, r) =>
      row.zipWithIndex.foreach { case (v, c) => if (v == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, v) }
    }
    b.setNumRows(rows.size)
    b
  }

  private def chart(s: Schema, spec: ChartSpec, rows: Seq[Any]*): ChartData =
    ChartData.build(s, spec, Iterator(batch(s, rows: _*))) match {
      case Right(d) => d
      case Left(err) => throw new AssertionError(err)
    }

  private def refusal(s: Schema, spec: ChartSpec, rows: Seq[Any]*): String =
    ChartData.build(s, spec, Iterator(batch(s, rows: _*))).swap.getOrElse(throw new AssertionError("drew a chart"))

  private def spec(t: ChartType, x: String, y: Seq[String], agg: Aggregation = Aggregation.Sum,
      split: Option[String] = None): ChartSpec = ChartSpec(t, x, y, agg, split)

  private def points(d: ChartData, series: Int = 0): Seq[(Double, Double)] =
    d.series(series).points.map(p => p.x -> p.y)

  private def labels(d: ChartData): IndexedSeq[String] = d.xScale match {
    case XScale.Categories(ls, _) => ls
    case other => throw new AssertionError(s"not bands: $other")
  }

  private def day(s: String): Double = LocalDate.parse(s).toEpochDay * 86400000.0

  private val orders = schema("order_id" -> DataType.LongType, "order_date" -> DataType.DateType,
    "status" -> DataType.StringType, "amount" -> DataType.DoubleType, "qty" -> DataType.IntType)

  // ==================== the spec ====================

  @Test def aResultSuggestsALineAlongItsFirstDate(): Unit =
    assertEquals(Some(spec(Line, "order_date", Seq("amount"))), ChartSpec.fit(None, orders))

  @Test def textSuggestsBars(): Unit = {
    val s = schema("status" -> DataType.StringType, "n" -> DataType.LongType)
    assertEquals(Some(spec(Bar, "status", Seq("n"))), ChartSpec.fit(None, s))
    // Numbers alone: the first along X, the next up Y.
    val numbers = schema("a" -> DataType.LongType, "b" -> DataType.DoubleType)
    assertEquals(Some(spec(Line, "a", Seq("b"))), ChartSpec.fit(None, numbers))
  }

  @Test def nothingToChartWithoutTwoColumns(): Unit = {
    assertEquals(None, ChartSpec.fit(None, schema("n" -> DataType.LongType)))
    assertEquals(None, ChartSpec.fit(None, schema("a" -> DataType.StringType, "b" -> DataType.DateType)))
    assertEquals(None, ChartSpec.fit(None, schema("b" -> DataType.BinaryType, "n" -> DataType.LongType)))
  }

  @Test def aSpecKeepsWhatTheNextResultStillHas(): Unit = {
    val chosen = spec(Pie, "STATUS", Seq("Qty"), Aggregation.Average)
    assertEquals(Some(spec(Pie, "status", Seq("qty"), Aggregation.Average)), ChartSpec.fit(Some(chosen), orders))
    // X is gone: the result's suggestion takes its place, with the type it
    // suggests; the rest stays.
    val renamed = schema("state" -> DataType.StringType, "qty" -> DataType.IntType)
    assertEquals(Some(spec(Bar, "state", Seq("qty"), Aggregation.Average)), ChartSpec.fit(Some(chosen), renamed))
  }

  @Test def aColumnAlongXIsNotAlsoUpY(): Unit = {
    val chosen = spec(Scatter, "amount", Seq("amount", "qty"), split = Some("amount"))
    assertEquals(Some(spec(Scatter, "amount", Seq("qty"))), ChartSpec.fit(Some(chosen), orders))
    // With Y emptied, the first number after X fills it.
    assertEquals(Some(spec(Line, "order_id", Seq("amount"))),
      ChartSpec.fit(Some(spec(Line, "order_id", Seq("order_id"))), orders))
  }

  // ==================== combining ====================

  private val sales = schema("status" -> DataType.StringType, "amount" -> DataType.DoubleType)

  @Test def barsCombineTheRowsSharingAnX(): Unit = {
    val d = chart(sales, spec(Bar, "status", Seq("amount")),
      Seq("shipped", 1.0), Seq("placed", 2.0), Seq("shipped", 3.0), Seq(null, 4.0), Seq("", 5.0))
    // Text keeps the order it came in; NULL comes last.
    assertEquals(Seq("shipped", "placed", "(empty)", "NULL"), labels(d))
    assertEquals(Seq(0.0 -> 4.0, 1.0 -> 2.0, 2.0 -> 5.0, 3.0 -> 4.0), points(d))
    assertEquals("SUM(amount)", d.yTitle)
    assertEquals(5L, d.rows)
  }

  @Test def eachAggregationAsSqlHasIt(): Unit = {
    val rows = Seq(Seq("a", 1.0), Seq("a", null), Seq("a", 3.0), Seq("b", null))
    def value(agg: Aggregation) = points(chart(sales, spec(Bar, "status", Seq("amount"), agg), rows: _*))
    assertEquals(Seq(0.0 -> 4.0), value(Aggregation.Sum))
    assertEquals(Seq(0.0 -> 2.0), value(Aggregation.Average))
    assertEquals(Seq(0.0 -> 1.0), value(Aggregation.Min))
    assertEquals(Seq(0.0 -> 3.0), value(Aggregation.Max))
    // COUNT counts values, not NULLs, and is 0 where there are none.
    assertEquals(Seq(0.0 -> 2.0, 1.0 -> 0.0), value(Aggregation.Count))
  }

  @Test def rowsCombinedByNothingKeepTheirName(): Unit = {
    val d = chart(sales, spec(Bar, "status", Seq("amount")), Seq("a", 1.0), Seq("b", 2.0))
    assertEquals("amount", d.yTitle)
    assertEquals("COUNT(amount)", chart(sales, spec(Bar, "status", Seq("amount"), Aggregation.Count),
      Seq("a", 1.0)).yTitle)
  }

  @Test def bandsOfNumbersAndDatesAreSorted(): Unit = {
    val years = schema("year" -> DataType.LongType, "n" -> DataType.LongType)
    assertEquals(Seq("2019", "2020", "2021"),
      labels(chart(years, spec(Bar, "year", Seq("n")), Seq(2021L, 1L), Seq(2019L, 2L), Seq(2020L, 3L))))
    val days = schema("d" -> DataType.DateType, "n" -> DataType.LongType)
    assertEquals(Seq("2026-01-01", "2026-01-02", "NULL"), labels(chart(days, spec(Pie, "d", Seq("n")),
      Seq(LocalDate.parse("2026-01-02"), 1L), Seq(null, 2L), Seq(LocalDate.parse("2026-01-01"), 3L))))
  }

  /** -0.0 and 0.0 are one value in SQL, and one band. */
  @Test def negativeZeroIsZero(): Unit = {
    val s = schema("x" -> DataType.DoubleType, "n" -> DataType.LongType)
    val d = chart(s, spec(Bar, "x", Seq("n")), Seq(0.0, 1L), Seq(-0.0, 2L))
    assertEquals(Seq("0.0"), labels(d))
    assertEquals(Seq(0.0 -> 3.0), points(d))
  }

  // ==================== along an axis ====================

  @Test def aLineRunsAlongItsDates(): Unit = {
    val d = chart(orders, spec(Line, "order_date", Seq("amount")),
      Seq(1L, LocalDate.parse("2026-01-03"), "a", 5.0, 1),
      Seq(2L, LocalDate.parse("2026-01-01"), "a", 1.0, 1),
      Seq(3L, LocalDate.parse("2026-01-03"), "b", 2.0, 1),
      Seq(4L, null, "a", 9.0, 1))
    assertEquals(XScale.Time(dateOnly = true), d.xScale)
    assertEquals(Seq(day("2026-01-01") -> 1.0, day("2026-01-03") -> 7.0), points(d))
    assertEquals(Seq("1 row whose order_date is NULL is left out"), d.notes)
  }

  @Test def timestampsAndNumbersAreAxesToo(): Unit = {
    val s = schema("at" -> DataType.TimestampType, "n" -> DataType.DoubleType)
    val at = Instant.parse("2026-01-01T12:30:00Z")
    val d = chart(s, spec(Area, "at", Seq("n")), Seq(at, 2.0))
    assertEquals(XScale.Time(dateOnly = false), d.xScale)
    assertEquals(Seq(at.toEpochMilli.toDouble -> 2.0), points(d))
    val n = schema("x" -> DataType.IntType, "y" -> DataType.DoubleType)
    val line = chart(n, spec(Line, "x", Seq("y")), Seq(3, 1.0), Seq(1, 2.0))
    assertEquals(XScale.Numbers, line.xScale)
    assertEquals(Seq(1.0 -> 2.0, 3.0 -> 1.0), points(line))
  }

  // ==================== series ====================

  @Test def eachYColumnIsASeries(): Unit = {
    val d = chart(orders, spec(Bar, "status", Seq("qty", "amount")),
      Seq(1L, null, "a", 2.5, 3), Seq(2L, null, "b", 1.5, 4))
    assertEquals(Seq("qty", "amount"), d.series.map(_.name))
    assertEquals(Seq(0.0 -> 3.0, 1.0 -> 4.0), points(d, 0))
    assertEquals(Seq(0.0 -> 2.5, 1.0 -> 1.5), points(d, 1))
    // The legend names the columns, so Y has no title.
    assertEquals("", d.yTitle)
  }

  @Test def aSplitDrawsASeriesPerValue(): Unit = {
    val d = chart(orders, spec(Line, "order_date", Seq("amount"), split = Some("status")),
      Seq(1L, LocalDate.parse("2026-01-01"), "placed", 1.0, 1),
      Seq(2L, LocalDate.parse("2026-01-01"), "shipped", 2.0, 1),
      Seq(3L, LocalDate.parse("2026-01-02"), "placed", 3.0, 1))
    assertEquals(Seq("placed", "shipped"), d.series.map(_.name))
    assertEquals(Seq(day("2026-01-01") -> 1.0, day("2026-01-02") -> 3.0), points(d, 0))
    assertEquals(Seq(day("2026-01-01") -> 2.0), points(d, 1))
    assertEquals("amount", d.yTitle)
  }

  /** Ten values split: the seven largest series are drawn, in the order
    * they came, then the rest as Other. */
  @Test def pastEightSeriesTheRestAreOther(): Unit = {
    val s = schema("x" -> DataType.StringType, "g" -> DataType.StringType, "v" -> DataType.DoubleType)
    val rows = (1 to 10).map(i => Seq("x", s"g$i", i.toDouble))
    val d = chart(s, spec(Bar, "x", Seq("v"), split = Some("g")), rows: _*)
    assertEquals((4 to 10).map(i => s"g$i") :+ "Other", d.series.map(_.name))
    assertTrue(d.series.last.other)
    assertEquals(Seq(0.0 -> 6.0), points(d, 7))
    assertEquals(Seq("g has 10 values: the 7 largest are drawn, the rest as Other"), d.notes)
  }

  /** Other averages its own rows, not the averages of its series. */
  @Test def otherCombinesItsRows(): Unit = {
    val s = schema("x" -> DataType.StringType, "g" -> DataType.StringType, "v" -> DataType.DoubleType)
    val big = (1 to 7).map(i => Seq("x", s"big$i", 100.0))
    val small = Seq(Seq("x", "s1", 1.0), Seq("x", "s1", 1.0), Seq("x", "s1", 1.0), Seq("x", "s2", 5.0))
    val d = chart(s, spec(Bar, "x", Seq("v"), Aggregation.Average, Some("g")), big ++ small: _*)
    assertEquals("Other", d.series.last.name)
    assertEquals(Seq(0.0 -> 2.0), points(d, 7))
  }

  // ==================== a pie ====================

  @Test def aPieFoldsPastSevenSlices(): Unit = {
    val rows = (1 to 10).map(i => Seq(s"s$i", i.toDouble))
    val d = chart(sales, spec(Pie, "status", Seq("amount")), rows: _*)
    assertEquals((4 to 10).map(i => s"s$i") :+ "Other", labels(d))
    assertEquals(XScale.Categories(labels(d), lastIsOther = true), d.xScale)
    assertEquals(1 + 2 + 3.0, points(d).last._2, 0)
    assertEquals(Seq("status has 10 values: the 7 largest are drawn, the rest as Other"), d.notes)
  }

  /** A slice of 0 or NULL draws nothing, so it is not listed. */
  @Test def aPieListsWhatItDraws(): Unit = {
    val d = chart(sales, spec(Pie, "status", Seq("amount")), Seq("a", 2.0), Seq("zero", 0.0), Seq("none", null),
      Seq("b", 1.0))
    assertEquals(Seq("a", "b"), labels(d))
    assertEquals(Seq(0.0 -> 2.0, 1.0 -> 1.0), points(d))
  }

  @Test def aPieRefusesValuesBelowZero(): Unit = {
    val err = refusal(sales, spec(Pie, "status", Seq("amount")), Seq("a", 2.0), Seq("b", -1.0))
    assertTrue(err, err.contains("below 0"))
  }

  // ==================== a scatter ====================

  @Test def aScatterDrawsEveryRow(): Unit = {
    val s = schema("price" -> DataType.DoubleType, "qty" -> DataType.LongType)
    val d = chart(s, spec(Scatter, "price", Seq("qty")), Seq(1.5, 2L), Seq(1.5, 3L), Seq(0.5, 1L))
    assertEquals(Seq(1.5 -> 2.0, 1.5 -> 3.0, 0.5 -> 1.0), points(d))
    assertEquals("qty", d.yTitle)
  }

  @Test def aScatterDrawsTheFirstRowsOnly(): Unit = {
    val s = schema("x" -> DataType.LongType, "y" -> DataType.LongType)
    val rows = (1 to ChartData.MaxPoints + 5).map(i => Seq(i.toLong, i.toLong))
    val d = chart(s, spec(Scatter, "x", Seq("y")), rows: _*)
    assertEquals(ChartData.MaxPoints, d.series.head.points.size)
    assertEquals(Seq("Only the first 10,000 of 10,005 rows are drawn"), d.notes)
  }

  @Test def barsStopAtMaxBars(): Unit = {
    val rows = (1 to ChartData.MaxBars + 1).map(i => Seq(s"c$i", 1.0))
    val d = chart(sales, spec(Bar, "status", Seq("amount")), rows: _*)
    assertEquals(ChartData.MaxBars, labels(d).size)
    assertEquals(Seq("Only the first 500 of 501 values of status are drawn"), d.notes)
  }

  // ==================== nothing to draw ====================

  @Test def nothingToDraw(): Unit = {
    assertEquals("The query returned no rows.", refusal(sales, spec(Bar, "status", Seq("amount"))))
    val err = refusal(sales, spec(Line, "status", Seq("amount")), Seq("a", null))
    assertTrue(err, err.startsWith("Nothing to draw"))
    assertEquals("Choose a column of numbers to draw up Y.", refusal(sales, spec(Bar, "status", Nil), Seq("a", 1.0)))
  }

  @Test def notANumberIsLeftOut(): Unit = {
    val d = chart(sales, spec(Bar, "status", Seq("amount")), Seq("a", Double.NaN), Seq("a", 2.0),
      Seq("b", Double.PositiveInfinity))
    assertEquals(Seq(0.0 -> 2.0), points(d))
    assertEquals(Seq("2 values that are NaN or infinite are left out"), d.notes)
  }
}
