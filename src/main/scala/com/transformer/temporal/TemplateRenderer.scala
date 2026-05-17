package com.transformer.temporal

import java.time.{Duration, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.time.temporal.{IsoFields, WeekFields}
import scala.util.matching.Regex

/** Jinja-style template renderer for SQL strings and output file paths.
  *
  * Variables resolve against a [[TemporalVariables]] reference time; every variable
  * supports `± N` arithmetic in the variable's natural unit. All times are UTC.
  *
  * Examples (reference = 2026-01-01T05:30:21Z):
  * {{{
  *   {{ today }}              -> 20260101
  *   {{ today - 5 }}          -> 20251227
  *   {{ yesterday }}          -> 20251231
  *   {{ current_hour }}       -> 5
  *   {{ current_hour - 13 }}  -> 16
  *   {{ iso_date - 1 }}       -> 2025-12-31
  *   {{ epoch_seconds }}      -> 1767245421
  * }}}
  */
object TemplateRenderer {

  private val Pattern: Regex = """\{\{\s*([A-Za-z_][A-Za-z0-9_]*)\s*([+-])?\s*(\d+)?\s*\}\}""".r

  private final case class Var(
      shift: (ZonedDateTime, Long) => ZonedDateTime,
      format: ZonedDateTime => String,
      preOffset: Long = 0L
  ) {
    def apply(ref: ZonedDateTime, userOffset: Long): String =
      format(shift(ref, preOffset + userOffset))
  }

  private val yyyyMMdd: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
  private val yyyy_MM_dd: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
  private val isoDateTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
  private val isoTimestamp: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
  private val yyyyMM: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMM")

  private val plusDays: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusDays(n)
  private val plusMonths: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusMonths(n)
  private val plusYears: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusYears(n)
  private val plusHours: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusHours(n)
  private val plusMinutes: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusMinutes(n)
  private val plusSeconds: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusSeconds(n)
  private val plusWeeks: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusWeeks(n)
  private val plusQuarters: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plusMonths(n * 3L)
  private val plusMillis: (ZonedDateTime, Long) => ZonedDateTime = (z, n) => z.plus(Duration.ofMillis(n))

  private val vars: Map[String, Var] = Map(
    // -------- Compact yyyyMMdd dates --------
    "today" -> Var(plusDays, _.format(yyyyMMdd)),
    "yesterday" -> Var(plusDays, _.format(yyyyMMdd), preOffset = -1L),
    "tomorrow" -> Var(plusDays, _.format(yyyyMMdd), preOffset = 1L),

    // -------- ISO dates/times --------
    "iso_date" -> Var(plusDays, _.format(yyyy_MM_dd)),
    "iso_datetime" -> Var(plusSeconds, _.format(isoDateTime)),
    "iso_timestamp" -> Var(plusSeconds, _.format(isoTimestamp)),

    // -------- Calendar components (unpadded) --------
    "current_year" -> Var(plusYears, _.getYear.toString),
    "current_month" -> Var(plusMonths, _.getMonthValue.toString),
    "current_day" -> Var(plusDays, _.getDayOfMonth.toString),
    "current_hour" -> Var(plusHours, _.getHour.toString),
    "current_minute" -> Var(plusMinutes, _.getMinute.toString),
    "current_second" -> Var(plusSeconds, _.getSecond.toString),
    "current_dow" -> Var(plusDays, _.getDayOfWeek.getValue.toString),
    "current_doy" -> Var(plusDays, _.getDayOfYear.toString),
    "current_week" -> Var(plusWeeks, z => z.get(WeekFields.ISO.weekOfWeekBasedYear).toString),
    "current_quarter" -> Var(plusQuarters, z => z.get(IsoFields.QUARTER_OF_YEAR).toString),
    "year_month" -> Var(plusMonths, _.format(yyyyMM)),

    // -------- Calendar components (zero-padded to 2) --------
    "current_month_pad" -> Var(plusMonths, z => f"${z.getMonthValue}%02d"),
    "current_day_pad" -> Var(plusDays, z => f"${z.getDayOfMonth}%02d"),
    "current_hour_pad" -> Var(plusHours, z => f"${z.getHour}%02d"),
    "current_minute_pad" -> Var(plusMinutes, z => f"${z.getMinute}%02d"),
    "current_second_pad" -> Var(plusSeconds, z => f"${z.getSecond}%02d"),
    "current_week_pad" -> Var(plusWeeks, z => f"${z.get(WeekFields.ISO.weekOfWeekBasedYear)}%02d"),

    // -------- Short aliases --------
    "year" -> Var(plusYears, _.getYear.toString),
    "month" -> Var(plusMonths, _.getMonthValue.toString),
    "day" -> Var(plusDays, _.getDayOfMonth.toString),
    "hour" -> Var(plusHours, _.getHour.toString),
    "minute" -> Var(plusMinutes, _.getMinute.toString),
    "second" -> Var(plusSeconds, _.getSecond.toString),

    // -------- Epoch --------
    "epoch_seconds" -> Var(plusSeconds, _.toEpochSecond.toString),
    "epoch_millis" -> Var(plusMillis, _.toInstant.toEpochMilli.toString)
  )

  /** Substitute every `{{ … }}` expression in `template` against `vars.executionTime` (UTC).
    *
    * @throws IllegalArgumentException if a template references an unknown variable.
    */
  def render(template: String, variables: TemporalVariables): String =
    render(template, variables.zonedExecutionTime)

  def render(template: String, ref: ZonedDateTime): String = {
    Pattern.replaceAllIn(template, m => {
      val name = m.group(1).toLowerCase
      val signStr = Option(m.group(2))
      val numStr = Option(m.group(3))
      val v = vars.getOrElse(
        name,
        throw new IllegalArgumentException(
          s"Unknown template variable '{{ $name }}'. Known variables: [${vars.keys.toSeq.sorted.mkString(", ")}]"
        )
      )
      val offset: Long = (signStr, numStr) match {
        case (None, None) => 0L
        case (Some("-"), Some(n)) => -n.toLong
        case (Some(_), Some(n)) => n.toLong // regex only captures + or - here
        case (None, Some(n)) => n.toLong    // tolerate "{{ today 5 }}" defensively → treat as +N
        case (Some(_), None) =>
          throw new IllegalArgumentException(s"Operator without number in template fragment '${m.matched}'")
      }
      // Replacement is fed to Matcher#appendReplacement which interprets $ and \.
      Regex.quoteReplacement(v(ref, offset))
    })
  }
}
