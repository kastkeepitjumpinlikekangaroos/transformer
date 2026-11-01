package com.transformer.gui

import java.math.{BigDecimal => JBigDecimal, MathContext}
import java.time.{Instant, ZoneOffset, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** The numbers and words along a chart's axes: round ticks for a range of
  * numbers, calendar ticks for a range of times, and how a tick, a value
  * and a time are written. No JavaFX: [[ChartCanvas]] asks it where to put
  * each tick, and `gui/chart_test` checks it. */
object ChartScale {

  /** Ticks `step` apart from `lower` to `upper`. */
  final case class Ticks(lower: Double, upper: Double, step: Double, values: IndexedSeq[Double])

  /** Ticks along a time axis, in milliseconds since 1970 (UTC), each with
    * its label. */
  final case class TimeTicks(values: IndexedSeq[Double], labels: IndexedSeq[String])

  /** At most `maxTicks` round ticks holding `[min, max]`: multiples of a
    * step of 1, 2, 2.5 or 5 times a power of ten, the first at or below
    * `min` and the last at or above `max`. A range of one value is widened
    * by a tenth of it either side (by 1 around 0). */
  def niceTicks(min: Double, max: Double, maxTicks: Int): Ticks = {
    val (lo, hi) = widen(min, max)
    val most = math.max(2, maxTicks)
    // Steps go up from below the smallest that could do, so the first that
    // fits is the finest.
    var exponent = math.floor(math.log10((hi - lo) / most)).toInt - 1
    var found: Option[Ticks] = None
    while (found.isEmpty) {
      found = Multiples.iterator.map(m => new JBigDecimal(m).scaleByPowerOfTen(exponent))
        .map(step => ticksOf(lo, hi, step)).find(_.values.size <= most)
      exponent += 1
    }
    found.get
  }

  private val Multiples = Seq("1", "2", "2.5", "5")

  private def widen(min: Double, max: Double): (Double, Double) =
    if (!min.isFinite || !max.isFinite) (0.0, 1.0)
    else if (max > min) (min, max)
    else if (min == 0) (0.0, 1.0)
    else (min - math.abs(min) / 10, max + math.abs(max) / 10)

  /** The multiples of `step` from the last at or below `lo` to the first at
    * or above `hi`, each computed in decimal so 0.1 + 0.2 lands on 0.3. */
  private def ticksOf(lo: Double, hi: Double, step: JBigDecimal): Ticks = {
    val s = step.doubleValue
    val first = math.floor(lo / s + 1e-9).toLong
    val last = math.ceil(hi / s - 1e-9).toLong
    val values = (first to last).map(i => step.multiply(JBigDecimal.valueOf(i)).doubleValue + 0.0)
    Ticks(values.head, values.last, s, values)
  }

  /** Each tick of `ticks` as an axis writes it: in full below 10,000, with a
    * comma between thousands (`2,500`), and every tick in thousands,
    * millions, billions or trillions on an axis that reaches 10,000 (`20K`,
    * `1.5M`), with the decimals the step needs and no trailing zeros. */
  def tickLabels(ticks: Ticks): IndexedSeq[String] = {
    val reach = ticks.values.map(math.abs).foldLeft(0.0)(math.max)
    Compact.find { case (unit, _) => reach >= 10000 && reach >= unit } match {
      case Some((unit, suffix)) =>
        val d = decimals(ticks.step / unit)
        ticks.values.map(v => trim(grouped(v / unit, d)) + (if (v == 0) "" else suffix))
      case None =>
        val d = decimals(ticks.step)
        ticks.values.map(v => trim(grouped(v, d)))
    }
  }

  private val Compact = Seq(1e12 -> "T", 1e9 -> "B", 1e6 -> "M", 1e3 -> "K")

  /** How many decimals write `step` exactly: 0 for 500, 1 for 2.5, 2 for 0.25. */
  def decimals(step: Double): Int =
    if (!step.isFinite || step == 0) 0
    else math.min(12, math.max(0, new JBigDecimal(java.lang.Double.toString(step)).stripTrailingZeros.scale))

  private def grouped(v: Double, decimals: Int): String =
    String.format(Locale.ROOT, s"%,.${decimals}f", Double.box(if (v == 0) 0.0 else v))

  private def trim(s: String): String =
    if (!s.contains('.')) s else s.reverse.dropWhile(_ == '0').dropWhile(_ == '.').reverse

  /** A value as a tooltip or a legend writes it: a whole number with commas
    * between thousands (`1,234`), else to six significant digits
    * (`1,234.57`, `0.000123457`). */
  def value(v: Double): String =
    if (!v.isFinite) v.toString
    else if (v == math.rint(v) && math.abs(v) < 1e15) String.format(Locale.ROOT, "%,d", Long.box(v.toLong))
    else {
      val bd = new JBigDecimal(v).round(new MathContext(6)).stripTrailingZeros
      val d = math.max(0, bd.scale)
      trim(String.format(Locale.ROOT, s"%,.${d}f", bd))
    }

  /** A share of a whole as a percentage: `45.2%`, `<0.1%` for a sliver. */
  def percent(part: Double, whole: Double): String = {
    val p = if (whole == 0) 0.0 else part / whole * 100
    if (p > 0 && p < 0.05) "<0.1%" else String.format(Locale.ROOT, "%.1f%%", Double.box(p))
  }

  // ==================== time ====================

  /** A step between ticks along a time axis: `count` of `unit`. */
  private final case class TimeStep(unit: ChronoUnit, count: Int) {
    /** About how long the step is, to count the ticks a range takes. */
    def approxMillis: Double = unit.getDuration.toMillis.toDouble * count
  }

  private val SubDaySteps: Seq[TimeStep] =
    Seq(1, 2, 5, 10, 15, 30).map(TimeStep(ChronoUnit.SECONDS, _)) ++
      Seq(1, 2, 5, 10, 15, 30).map(TimeStep(ChronoUnit.MINUTES, _)) ++
      Seq(1, 2, 3, 6, 12).map(TimeStep(ChronoUnit.HOURS, _))

  private val DaySteps: Seq[TimeStep] =
    Seq(TimeStep(ChronoUnit.DAYS, 1), TimeStep(ChronoUnit.DAYS, 2), TimeStep(ChronoUnit.WEEKS, 1)) ++
      Seq(1, 2, 3, 6).map(TimeStep(ChronoUnit.MONTHS, _)) ++
      (0 to 6).flatMap(e => Seq(1, 2, 5).map(m => TimeStep(ChronoUnit.YEARS, m * math.pow(10, e).toInt)))

  /** At most `maxTicks` ticks within `[min, max]` (milliseconds since 1970,
    * UTC) on calendar boundaries: whole seconds, minutes or hours, midnights,
    * Mondays, the first of a month or of a year, whichever step is the
    * finest that keeps to `maxTicks`. `dateOnly` axes step a day at least.
    * Each is labelled as the step calls for: `2026`, `2026-01`,
    * `2026-01-05`, `2026-01-05 14:00`, `14:05:30`. */
  def timeTicks(min: Double, max: Double, maxTicks: Int, dateOnly: Boolean): TimeTicks = {
    val steps = if (dateOnly) DaySteps else SubDaySteps ++ DaySteps
    val most = math.max(1, maxTicks)
    // A step's tick count is estimated first, then the chosen step's ticks
    // are made and, should calendar months run long, the next step tried.
    val candidates = steps.filter(s => (max - min) / s.approxMillis <= most + 1)
    val ticks = candidates.iterator.map(s => s -> ticksIn(min, max, s)).find(_._2.size <= most)
      .getOrElse(steps.last -> ticksIn(min, max, steps.last))
    val (step, values) = ticks
    val format = DateTimeFormatter.ofPattern(pattern(step.unit), Locale.ROOT).withZone(ZoneOffset.UTC)
    TimeTicks(values, values.map(v => format.format(instant(v))))
  }

  private def pattern(unit: ChronoUnit): String = unit match {
    case ChronoUnit.YEARS => "yyyy"
    case ChronoUnit.MONTHS => "yyyy-MM"
    case ChronoUnit.DAYS | ChronoUnit.WEEKS => "yyyy-MM-dd"
    case ChronoUnit.HOURS | ChronoUnit.MINUTES => "yyyy-MM-dd HH:mm"
    case _ => "HH:mm:ss"
  }

  /** The ticks of `step` within `[min, max]`: the first boundary at or after
    * `min`, then one every step. */
  private def ticksIn(min: Double, max: Double, step: TimeStep): IndexedSeq[Double] = {
    val start = instant(min).atZone(ZoneOffset.UTC)
    var t = firstBoundary(start, step)
    val out = IndexedSeq.newBuilder[Double]
    var n = 0
    while (millis(t) <= max && n <= 10000) {
      if (millis(t) >= min) out += millis(t)
      t = t.plus(step.count.toLong, step.unit)
      n += 1
    }
    out.result()
  }

  /** The last boundary of `step` at or before `start`. */
  private def firstBoundary(start: ZonedDateTime, step: TimeStep): ZonedDateTime = step.unit match {
    case ChronoUnit.YEARS =>
      val y = math.floorDiv(start.getYear, step.count) * step.count
      ZonedDateTime.of(y, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    case ChronoUnit.MONTHS =>
      val m = (start.getMonthValue - 1) / step.count * step.count + 1
      ZonedDateTime.of(start.getYear, m, 1, 0, 0, 0, 0, ZoneOffset.UTC)
    case ChronoUnit.WEEKS =>
      val day = start.truncatedTo(ChronoUnit.DAYS)
      day.minusDays((day.getDayOfWeek.getValue - 1).toLong)
    case ChronoUnit.DAYS =>
      val day = start.truncatedTo(ChronoUnit.DAYS)
      day.minusDays(math.floorMod(day.toLocalDate.toEpochDay, step.count.toLong))
    case unit =>
      val size = unit.getDuration.toMillis * step.count
      instant(math.floorDiv(millis(start).toLong, size).toDouble * size).atZone(ZoneOffset.UTC)
  }

  private def millis(t: ZonedDateTime): Double = t.toInstant.toEpochMilli.toDouble

  private def instant(millis: Double): Instant = Instant.ofEpochMilli(millis.toLong)

  /** A time as a tooltip writes it: a date as the grid does (`2026-01-05`),
    * a timestamp to the second, in UTC (`2026-01-05T14:00:00Z`). */
  def timeValue(millis: Double, dateOnly: Boolean): String =
    if (dateOnly) instant(millis).atZone(ZoneOffset.UTC).toLocalDate.toString
    else instant(millis).truncatedTo(ChronoUnit.SECONDS).toString
}
