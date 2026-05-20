package com.transformer.core

import org.junit.Assert._
import org.junit.Test

class ExecutionOptionsTest {

  @Test def defaultDisablesSpill(): Unit = {
    val d = ExecutionOptions.Default
    assertFalse(d.spillEnabled)
    assertEquals(None, d.spillThresholdBytes)
    assertEquals(ExecutionOptions.DefaultSpillMaxRuns, d.spillMaxRuns)
  }

  @Test def emptyMapYieldsDefault(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map.empty)
    assertEquals(ExecutionOptions.Default, opts)
  }

  @Test def spillTrueEnablesSpill(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("spill" -> "true"))
    assertTrue(opts.spillEnabled)
  }

  @Test def spillOneEnablesSpill(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("spill" -> "1"))
    assertTrue(opts.spillEnabled)
  }

  @Test def spillIsCaseInsensitive(): Unit = {
    assertTrue(ExecutionOptions.fromOutputOptions(Map("spill" -> "TRUE")).spillEnabled)
    assertTrue(ExecutionOptions.fromOutputOptions(Map("spill" -> "True")).spillEnabled)
  }

  @Test def spillTolerantOfTypo(): Unit = {
    // Per plan edge case #11: any non-true/1 value silently disables spill
    // rather than throwing — failure mode is "ran without spill" not
    // "config refused to load".
    assertFalse(ExecutionOptions.fromOutputOptions(Map("spill" -> "yes")).spillEnabled)
    assertFalse(ExecutionOptions.fromOutputOptions(Map("spill" -> "false")).spillEnabled)
    assertFalse(ExecutionOptions.fromOutputOptions(Map("spill" -> "0")).spillEnabled)
    assertFalse(ExecutionOptions.fromOutputOptions(Map("spill" -> "")).spillEnabled)
  }

  @Test def spillThresholdBytesParses(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map(
      "spill" -> "true",
      "spill_threshold_bytes" -> "1073741824"))
    assertEquals(Some(1073741824L), opts.spillThresholdBytes)
  }

  @Test def spillThresholdBytesIgnoresBadValues(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map(
      "spill" -> "true",
      "spill_threshold_bytes" -> "not-a-number"))
    assertEquals(None, opts.spillThresholdBytes)
  }

  @Test def spillThresholdBytesIgnoresZeroAndNegative(): Unit = {
    assertEquals(None, ExecutionOptions.fromOutputOptions(Map(
      "spill_threshold_bytes" -> "0")).spillThresholdBytes)
    assertEquals(None, ExecutionOptions.fromOutputOptions(Map(
      "spill_threshold_bytes" -> "-100")).spillThresholdBytes)
  }

  @Test def spillMaxRunsParses(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("spill_max_runs" -> "50"))
    assertEquals(50, opts.spillMaxRuns)
  }

  @Test def spillMaxRunsDefaultsOnBadValue(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("spill_max_runs" -> "xxx"))
    assertEquals(ExecutionOptions.DefaultSpillMaxRuns, opts.spillMaxRuns)
  }

  @Test def spillMaxRunsDefaultsOnZero(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("spill_max_runs" -> "0"))
    assertEquals(ExecutionOptions.DefaultSpillMaxRuns, opts.spillMaxRuns)
  }

  @Test def unknownKeysAreIgnored(): Unit = {
    // OutputFilePath.options is shared with writer config — keys like
    // `compression`, `parquet_write_parallelism` ride along and must not
    // affect spill parsing.
    val opts = ExecutionOptions.fromOutputOptions(Map(
      "spill" -> "true",
      "compression" -> "SNAPPY",
      "parquet_write_parallelism" -> "8"))
    assertTrue(opts.spillEnabled)
    assertEquals(None, opts.spillThresholdBytes)
    assertEquals(ExecutionOptions.DefaultSpillMaxRuns, opts.spillMaxRuns)
  }

  // --- metricsEnabled (Sub-plan 1 of the instrumentation work) ----------

  @Test def defaultDisablesMetrics(): Unit = {
    assertFalse(ExecutionOptions.Default.metricsEnabled)
  }

  @Test def metricsTrueEnablesMetrics(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("metrics" -> "true"))
    assertTrue(opts.metricsEnabled)
  }

  @Test def metricsOneEnablesMetrics(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("metrics" -> "1"))
    assertTrue(opts.metricsEnabled)
  }

  @Test def metricsIsCaseInsensitive(): Unit = {
    assertTrue(ExecutionOptions.fromOutputOptions(Map("metrics" -> "TRUE")).metricsEnabled)
    assertTrue(ExecutionOptions.fromOutputOptions(Map("metrics" -> "True")).metricsEnabled)
  }

  @Test def metricsTolerantOfTypo(): Unit = {
    // Mirrors the spill parse semantics — typos silently disable metrics
    // rather than throwing.
    assertFalse(ExecutionOptions.fromOutputOptions(Map("metrics" -> "yes")).metricsEnabled)
    assertFalse(ExecutionOptions.fromOutputOptions(Map("metrics" -> "false")).metricsEnabled)
    assertFalse(ExecutionOptions.fromOutputOptions(Map("metrics" -> "0")).metricsEnabled)
    assertFalse(ExecutionOptions.fromOutputOptions(Map("metrics" -> "")).metricsEnabled)
  }

  @Test def metricsComposesWithSpill(): Unit = {
    // Sub-plan 1 verification: both spill and metrics can be set on the same
    // task. The composition path is exercised end-to-end in DataJobTest.
    val opts = ExecutionOptions.fromOutputOptions(Map(
      "spill" -> "true",
      "metrics" -> "true",
      "spill_threshold_bytes" -> "1048576"))
    assertTrue(opts.spillEnabled)
    assertTrue(opts.metricsEnabled)
    assertEquals(Some(1048576L), opts.spillThresholdBytes)
  }

  @Test def triStateReturnsExpectedSentinels(): Unit = {
    // No key -> UnsetTri (global default applies).
    assertEquals(ExecutionOptions.UnsetTri,
      ExecutionOptions.triState(Map.empty[String, String], ExecutionOptions.MetricsKey))
    // Key present and truthy -> TrueTri.
    assertEquals(ExecutionOptions.TrueTri,
      ExecutionOptions.triState(Map(ExecutionOptions.MetricsKey -> "true"), ExecutionOptions.MetricsKey))
    assertEquals(ExecutionOptions.TrueTri,
      ExecutionOptions.triState(Map(ExecutionOptions.MetricsKey -> "1"), ExecutionOptions.MetricsKey))
    // Key present but falsy (including typos) -> FalseTri.
    assertEquals(ExecutionOptions.FalseTri,
      ExecutionOptions.triState(Map(ExecutionOptions.MetricsKey -> "false"), ExecutionOptions.MetricsKey))
    assertEquals(ExecutionOptions.FalseTri,
      ExecutionOptions.triState(Map(ExecutionOptions.MetricsKey -> "yes"), ExecutionOptions.MetricsKey))
  }
}
