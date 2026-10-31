package com.transformer.temporal

import org.junit.Assert._
import org.junit.Test

import java.time.Instant

class TemplateRendererTest {

  // Reference: 2026-01-01T05:30:21Z. All assertions below are anchored on this instant.
  private val ref = TemporalVariables(Instant.parse("2026-01-01T05:30:21Z"))

  private def r(s: String): String = TemplateRenderer.render(s, ref)

  @Test def todayYesterdayTomorrow(): Unit = {
    assertEquals("20260101", r("{{ today }}"))
    assertEquals("20251231", r("{{ yesterday }}"))
    assertEquals("20260102", r("{{ tomorrow }}"))
  }

  @Test def todayArithmetic(): Unit = {
    assertEquals("20251227", r("{{ today - 5 }}"))
    assertEquals("20260102", r("{{ today + 1 }}"))
    assertEquals("20251202", r("{{ today - 30 }}"))
    // Compounding: yesterday - 1 should be 20251230
    assertEquals("20251230", r("{{ yesterday - 1 }}"))
  }

  @Test def isoDateAndDatetime(): Unit = {
    assertEquals("2026-01-01", r("{{ iso_date }}"))
    assertEquals("2025-12-31", r("{{ iso_date - 1 }}"))
    assertEquals("2026-01-01T05:30:21", r("{{ iso_datetime }}"))
    assertEquals("2026-01-01T05:30:21Z", r("{{ iso_timestamp }}"))
    // iso_datetime arithmetic is in seconds
    assertEquals("2026-01-01T05:30:20", r("{{ iso_datetime - 1 }}"))
  }

  @Test def calendarComponents(): Unit = {
    assertEquals("2026", r("{{ current_year }}"))
    assertEquals("1", r("{{ current_month }}"))
    assertEquals("1", r("{{ current_day }}"))
    assertEquals("5", r("{{ current_hour }}"))
    assertEquals("30", r("{{ current_minute }}"))
    assertEquals("21", r("{{ current_second }}"))
    assertEquals("4", r("{{ current_dow }}"))         // 2026-01-01 is a Thursday
    assertEquals("1", r("{{ current_quarter }}"))
    assertEquals("1", r("{{ current_week }}"))
    assertEquals("1", r("{{ current_doy }}"))
    assertEquals("202601", r("{{ year_month }}"))
  }

  @Test def calendarComponentArithmetic(): Unit = {
    // Subtracting 13 hours from 05:30:21 gives 2025-12-31 16:30:21 -> hour 16.
    assertEquals("16", r("{{ current_hour - 13 }}"))
    // Subtracting 60 minutes from 05:30:21 gives 04:30:21 -> minute 30 (same).
    assertEquals("30", r("{{ current_minute - 60 }}"))
    // Subtracting 31 minutes gives 04:59:21 -> minute 59.
    assertEquals("59", r("{{ current_minute - 31 }}"))
    // Subtracting 1 year -> 2025.
    assertEquals("2025", r("{{ current_year - 1 }}"))
    // Subtracting 1 month -> December (12), year wraps to 2025.
    assertEquals("12", r("{{ current_month - 1 }}"))
    // Subtracting 1 quarter -> Q4 of previous year.
    assertEquals("4", r("{{ current_quarter - 1 }}"))
    // Subtracting 1 day -> 2025-12-31 -> dow 3 (Wed).
    assertEquals("3", r("{{ current_dow - 1 }}"))
  }

  @Test def paddedComponents(): Unit = {
    assertEquals("01", r("{{ current_month_pad }}"))
    assertEquals("01", r("{{ current_day_pad }}"))
    assertEquals("05", r("{{ current_hour_pad }}"))
    assertEquals("30", r("{{ current_minute_pad }}"))
    assertEquals("21", r("{{ current_second_pad }}"))
    assertEquals("01", r("{{ current_week_pad }}"))
    // Padded arithmetic: hour - 13 -> 16 (no leading zero needed, but still 2-digit)
    assertEquals("16", r("{{ current_hour_pad - 13 }}"))
    // Padded arithmetic: hour - 5 -> 0 -> "00"
    assertEquals("00", r("{{ current_hour_pad - 5 }}"))
  }

  @Test def shortAliases(): Unit = {
    assertEquals("2026", r("{{ year }}"))
    assertEquals("1", r("{{ month }}"))
    assertEquals("1", r("{{ day }}"))
    assertEquals("5", r("{{ hour }}"))
    assertEquals("30", r("{{ minute }}"))
    assertEquals("21", r("{{ second }}"))
  }

  @Test def epochVariables(): Unit = {
    val expectedSecs = ref.executionTime.getEpochSecond.toString
    assertEquals(expectedSecs, r("{{ epoch_seconds }}"))
    val expectedMillis = ref.executionTime.toEpochMilli.toString
    assertEquals(expectedMillis, r("{{ epoch_millis }}"))
    // 60 seconds earlier
    val expectedPrev = (ref.executionTime.getEpochSecond - 60).toString
    assertEquals(expectedPrev, r("{{ epoch_seconds - 60 }}"))
  }

  @Test def multipleSubstitutionsInOneTemplate(): Unit = {
    val out = r("s3://bucket/day={{ today }}/hour={{ current_hour_pad }}/file_{{ epoch_seconds }}.csv")
    assertEquals("s3://bucket/day=20260101/hour=05/file_" + ref.executionTime.getEpochSecond + ".csv", out)
  }

  @Test def noWhitespaceIsAccepted(): Unit = {
    assertEquals("20260101", r("{{today}}"))
    assertEquals("20251227", r("{{today-5}}"))
    assertEquals("20260102", r("{{today+1}}"))
  }

  @Test def unknownVariableThrows(): Unit = {
    try {
      r("{{ no_such_var }}")
      fail("expected IllegalArgumentException")
    } catch {
      case e: IllegalArgumentException =>
        assertTrue(e.getMessage.contains("no_such_var"))
    }
  }

  @Test def sqlStringSubstitution(): Unit = {
    val sql = "SELECT * FROM t WHERE day = '{{ today }}' AND hour = {{ current_hour }}"
    assertEquals("SELECT * FROM t WHERE day = '20260101' AND hour = 5", r(sql))
  }

  /** A template expression the grammar does not read is an error naming it
    * and the grammar. Each was left in the text, so a filter compared against
    * the template's own text and a path made a directory of it, and `{{ today
    * 5 }}` was read as `+ 5` (plans/robustness/28). A `}}` alone, as nested
    * JSON in a string literal ends, is no template. */
  @Test def anExpressionTheGrammarDoesNotReadIsAnError(): Unit = {
    def refused(t: String): String =
      try { r(t); fail(s"$t rendered"); "" } catch { case e: IllegalArgumentException => e.getMessage }
    for ((t, fragment) <- Seq(
           "'{{ today - 1d }}'" -> "{{ today - 1d }}",
           "'{{ today * 2 }}'" -> "{{ today * 2 }}",
           "'{{ today | ds }}'" -> "{{ today | ds }}",
           "'{{}}'" -> "{{}}",
           "'{{ today'" -> "{{ today'",
           "{{ today 5 }}" -> "{{ today 5 }}",
           "a {{ today }} b {{ today + }}" -> "{{ today + }}"))
      assertEquals(s"The template expression '$fragment' is none the renderer reads; it reads {{ name }}, " +
        "{{ name + N }} and {{ name - N }} with N a whole number", refused(t))
    assertEquals("""SELECT '{"a":{"b":1}}' AS j""", r("""SELECT '{"a":{"b":1}}' AS j"""))
    // An offset no BIGINT holds, or one that moves the time past what a date
    // can be, is the same kind of error, where the first threw
    // NumberFormatException.
    assertEquals("The offset in '{{ today + 99999999999999999999 }}' is past what a BIGINT holds",
      refused("{{ today + 99999999999999999999 }}"))
    assertEquals("'{{ today + 999999999999 }}' moves the time past what a date can be",
      refused("{{ today + 999999999999 }}"))
    assertEquals("'{{ epoch_seconds - 9223372036854775807 }}' moves the time past what a date can be",
      refused("{{ epoch_seconds - 9223372036854775807 }}"))
  }

  @Test def yearMonthFormat(): Unit = {
    // year_month should give 6-digit yyyyMM
    assertEquals("202601", r("{{ year_month }}"))
    assertEquals("202512", r("{{ year_month - 1 }}"))
  }
}
