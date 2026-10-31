package com.transformer.core

import org.junit.Assert._
import org.junit.Test

class ExecutionOptionsTest {

  private def refused(opts: Map[String, String]): String =
    try { ExecutionOptions.fromOutputOptions(opts); fail(s"$opts was read"); "" }
    catch { case e: IllegalArgumentException => e.getMessage }

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


  @Test def spillIsCaseInsensitive(): Unit = {
    assertTrue(ExecutionOptions.fromOutputOptions(Map("spill" -> "TRUE")).spillEnabled)
    assertTrue(ExecutionOptions.fromOutputOptions(Map("spill" -> "True")).spillEnabled)
  }

  /** A value that is not true or false is an error naming the key. Any
    * other value used to leave spill off, so `"spill": "yes"` ran without
    * spill and said nothing (plans/robustness/27). */
  @Test def spillIsTrueOrFalse(): Unit = {
    assertFalse(ExecutionOptions.fromOutputOptions(Map("spill" -> "false")).spillEnabled)
    for (v <- Seq("yes", "0", "1", "", " true"))
      assertEquals(s"Option 'spill' must be true or false, not '$v'", refused(Map("spill" -> v)))
  }

  @Test def spillThresholdBytesParses(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map(
      "spill" -> "true",
      "spill_threshold_bytes" -> "1073741824"))
    assertEquals(Some(1073741824L), opts.spillThresholdBytes)
  }

  /** A size is a whole number of bytes, 1 or more; `64MB` and the rest were
    * ignored, leaving the operator's own default. */
  @Test def spillThresholdBytesIsAWholeNumber(): Unit = {
    for (v <- Seq("not-a-number", "64MB", "0", "-100", "1.5", "99999999999999999999"))
      assertEquals(s"Option 'spill_threshold_bytes' must be a whole number, 1 or more, not '$v'",
        refused(Map("spill" -> "true", "spill_threshold_bytes" -> v)))
  }

  @Test def spillMaxRunsParses(): Unit = {
    val opts = ExecutionOptions.fromOutputOptions(Map("spill_max_runs" -> "50"))
    assertEquals(50, opts.spillMaxRuns)
  }

  @Test def spillMaxRunsIsAWholeNumber(): Unit = {
    for (v <- Seq("xxx", "0", "-1"))
      assertEquals(s"Option 'spill_max_runs' must be a whole number, 1 or more, not '$v'",
        refused(Map("spill_max_runs" -> v)))
    assertTrue(refused(Map("spill_max_runs" -> "3000000000")).contains("from 1 to 2147483647"))
  }

  @Test def unknownKeysAreIgnored(): Unit = {
    // OutputFilePath.options is shared with writer config — keys like
    // `compression`, `parquet_write_parallelism` ride along and must not
    // affect spill parsing. OutputFilePath checks the keys.
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


  @Test def metricsIsCaseInsensitive(): Unit = {
    assertTrue(ExecutionOptions.fromOutputOptions(Map("metrics" -> "TRUE")).metricsEnabled)
    assertTrue(ExecutionOptions.fromOutputOptions(Map("metrics" -> "True")).metricsEnabled)
  }

  @Test def metricsIsTrueOrFalse(): Unit = {
    assertFalse(ExecutionOptions.fromOutputOptions(Map("metrics" -> "false")).metricsEnabled)
    for (v <- Seq("yes", "0", "1", ""))
      assertEquals(s"Option 'metrics' must be true or false, not '$v'", refused(Map("metrics" -> v)))
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
    // Key present and false -> FalseTri.
    assertEquals(ExecutionOptions.FalseTri,
      ExecutionOptions.triState(Map(ExecutionOptions.MetricsKey -> "false"), ExecutionOptions.MetricsKey))
    // Anything else is an error, where a typo read as false.
    for (v <- Seq("1", "yes"))
      try { ExecutionOptions.triState(Map(ExecutionOptions.MetricsKey -> v), ExecutionOptions.MetricsKey); fail(v) }
      catch { case e: IllegalArgumentException => assertTrue(e.getMessage, e.getMessage.contains("'metrics'")) }
  }
}
