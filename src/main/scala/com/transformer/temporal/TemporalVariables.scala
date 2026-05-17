package com.transformer.temporal

import java.time.{Instant, ZoneOffset}

/** Job-wide reference time used by [[TemplateRenderer]] to resolve `{{ today }}`, etc.
  *
  * All times are interpreted in UTC. If `executionTime` is omitted the current wall
  * clock is captured at construction time.
  */
final case class TemporalVariables(executionTime: Instant = Instant.now()) {
  def zonedExecutionTime: java.time.ZonedDateTime = executionTime.atZone(ZoneOffset.UTC)
}
