package com.transformer.gui

import org.junit.Assert._
import org.junit.Test

import java.time.{DayOfWeek, Instant, LocalDate}

/** The ticks along a chart's axes and how they, its values and its times
  * are written. */
class ChartScaleTest {

  private def labels(min: Double, max: Double, most: Int): Seq[String] =
    ChartScale.tickLabels(ChartScale.niceTicks(min, max, most))

  private def day(s: String): Double = LocalDate.parse(s).toEpochDay * 86400000.0
  private def instant(s: String): Double = Instant.parse(s).toEpochMilli.toDouble

  @Test def roundTicksHoldTheRange(): Unit = {
    val t = ChartScale.niceTicks(0, 97, 6)
    assertEquals(Seq(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), t.values)
    assertEquals(20.0, t.step, 0)
    assertEquals(Seq(-2000.0, 0.0, 2000.0, 4000.0, 6000.0), ChartScale.niceTicks(-1234, 5678, 8).values)
  }

  /** Ticks are worked out in decimal: 0.3 is 0.3, not 0.30000000000000004. */
  @Test def ticksHaveNoBinaryNoise(): Unit =
    assertEquals(Seq(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9), ChartScale.niceTicks(0.1, 0.87, 10).values)

  @Test def aRangeOfOneValueIsWidened(): Unit = {
    val t = ChartScale.niceTicks(5, 5, 6)
    assertTrue(t.values.toString, t.lower < 5 && t.upper > 5)
    assertEquals(Seq(0.0, 0.2, 0.4, 0.6, 0.8, 1.0), ChartScale.niceTicks(0, 0, 6).values)
  }

  @Test def neverMoreTicksThanAsked(): Unit =
    for (most <- 2 to 12; (lo, hi) <- Seq((0.0, 1.0), (-7.0, 3.3), (13.0, 9876.0), (0.001, 0.0042), (-1e9, -2e8)))
      assertTrue(s"$lo..$hi in $most", ChartScale.niceTicks(lo, hi, most).values.size <= most)

  @Test def labelsInFullBelowTenThousand(): Unit = {
    assertEquals(Seq("0", "2,000", "4,000", "6,000", "8,000"), labels(0, 8000, 5))
    assertEquals(Seq("0", "0.25", "0.5", "0.75", "1"), labels(0, 1, 5))
  }

  @Test def labelsCompactOnAnAxisReachingTenThousand(): Unit = {
    assertEquals(Seq("0", "2.5K", "5K", "7.5K", "10K"), labels(0, 10000, 5))
    assertEquals(Seq("-20K", "-10K", "0", "10K", "20K"), labels(-20000, 20000, 5))
    assertEquals(Seq("0", "0.5M", "1M", "1.5M", "2M"), labels(0, 2e6, 5))
  }

  @Test def valuesAndShares(): Unit = {
    assertEquals("1,234", ChartScale.value(1234))
    assertEquals("1,234.57", ChartScale.value(1234.5678))
    assertEquals("0.000123457", ChartScale.value(0.000123456789))
    assertEquals("-3.5", ChartScale.value(-3.5))
    assertEquals("45.2%", ChartScale.percent(45.2, 100))
    assertEquals("<0.1%", ChartScale.percent(1, 10000))
  }

  @Test def aYearOfDatesTicksByMonth(): Unit =
    assertEquals(Seq("01", "02", "03", "04", "05", "06", "07", "08", "09", "10", "11", "12").map("2026-" + _),
      ChartScale.timeTicks(day("2026-01-01"), day("2026-12-31"), 12, dateOnly = true).labels)

  @Test def daysThenMondays(): Unit = {
    assertEquals(Seq("2026-03-01", "2026-03-02", "2026-03-03", "2026-03-04", "2026-03-05"),
      ChartScale.timeTicks(day("2026-03-01"), day("2026-03-05"), 10, dateOnly = true).labels)
    val weeks = ChartScale.timeTicks(day("2026-03-01"), day("2026-04-30"), 10, dateOnly = true).labels
    assertEquals(9, weeks.size)
    assertTrue(weeks.toString, weeks.forall(l => LocalDate.parse(l).getDayOfWeek == DayOfWeek.MONDAY))
  }

  @Test def yearsStepByRoundNumbers(): Unit =
    assertEquals(Seq("1995", "2000", "2005", "2010", "2015", "2020", "2025"),
      ChartScale.timeTicks(day("1990-06-01"), day("2026-01-01"), 8, dateOnly = true).labels)

  @Test def timestampsTickWithinADay(): Unit = {
    val noon =
      ChartScale.timeTicks(instant("2026-03-01T00:00:00Z"), instant("2026-03-01T12:00:00Z"), 8, dateOnly = false)
    assertEquals(Seq("00:00", "02:00", "04:00", "06:00", "08:00", "10:00", "12:00").map("2026-03-01 " + _), noon.labels)
    // A date axis steps a day at least, however few days it spans.
    assertEquals(Seq("2026-03-01", "2026-03-02"),
      ChartScale.timeTicks(day("2026-03-01"), day("2026-03-02"), 8, dateOnly = true).labels)
  }

  @Test def timesAsATooltipWritesThem(): Unit = {
    assertEquals("2026-03-01", ChartScale.timeValue(day("2026-03-01"), dateOnly = true))
    assertEquals("2026-03-01T14:05:30Z", ChartScale.timeValue(instant("2026-03-01T14:05:30.250Z"), dateOnly = false))
  }
}
