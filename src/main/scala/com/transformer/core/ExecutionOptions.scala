package com.transformer.core

/** Per-query knobs the planner and spill-capable operators read at construction
  * time. Plumbed from `DataJob.runOneTask` through `SqlExecutor.execute` into
  * `PhysicalPlanner.plan` and finally to the breakers that opt into spill.
  *
  * The defaults disable spill entirely — the only way a query spills is for the
  * caller to explicitly construct non-default `ExecutionOptions`. Ad-hoc paths
  * (GUI SQL Console, the 2-arg `SqlExecutor.execute` shim) always use
  * `ExecutionOptions.Default`.
  *
  * @param spillEnabled
  *   Master switch. When false, no operator touches the spill path and the
  *   spill threshold is irrelevant.
  * @param spillThresholdBytes
  *   Per-operator threshold. Crossing this byte count triggers a flush of
  *   partial state to a spill file. `None` lets each operator compute its own
  *   default (typically `maxHeap / 4 / Scheduler.parallelism`, capped at 1 GiB).
  * @param spillMaxRuns
  *   Abort if any one operator spills more than this many times — a guard
  *   against pathological data that produces unbounded spill runs.
  */
final case class ExecutionOptions(
    spillEnabled: Boolean = false,
    spillThresholdBytes: Option[Long] = None,
    spillMaxRuns: Int = ExecutionOptions.DefaultSpillMaxRuns)

object ExecutionOptions {

  /** Default `spill_max_runs`. Picked high enough that a real workload won't
    * trip it under normal conditions, low enough that a stuck loop produces a
    * clear error before exhausting disk. */
  val DefaultSpillMaxRuns: Int = 1000

  /** Default options — spill disabled. Use this in any code path that doesn't
    * carry per-task config (GUI SQL Console, tests that don't care). */
  val Default: ExecutionOptions = ExecutionOptions()

  /** Config key for the master spill switch (read from `output.json`'s
    * `options` map). Accepts `"true"`/`"1"` (case-insensitive); anything else
    * leaves spill disabled — typos should fall back to the safe default rather
    * than fail the task at config parse time. */
  val SpillKey: String = "spill"

  /** Config key for the per-operator spill threshold in bytes. Unparseable
    * values are silently ignored (operator computes its own default). */
  val SpillThresholdKey: String = "spill_threshold_bytes"

  /** Config key for `spillMaxRuns`. Unparseable values fall back to
    * [[DefaultSpillMaxRuns]]. */
  val SpillMaxRunsKey: String = "spill_max_runs"

  /** Parse `ExecutionOptions` from the flat `options` map carried on
    * `OutputFilePath` (mirrors the existing `parquet_write_parallelism` /
    * `compression` plumbing). Any key not recognised is ignored — the same
    * map also carries writer-specific keys.
    *
    * Tolerant by design: invalid booleans become `false`; invalid longs/ints
    * become `None` / default. The user has many ways to break their config,
    * and `spill: "yes"` running without spill is a better failure mode than
    * the task refusing to load at all. */
  def fromOutputOptions(opts: Map[String, String]): ExecutionOptions = {
    val enabled = opts.get(SpillKey).exists(parseBool)
    val threshold = opts.get(SpillThresholdKey).flatMap(parseLong).filter(_ > 0L)
    val maxRuns = opts.get(SpillMaxRunsKey).flatMap(parseInt).filter(_ > 0).getOrElse(DefaultSpillMaxRuns)
    ExecutionOptions(spillEnabled = enabled, spillThresholdBytes = threshold, spillMaxRuns = maxRuns)
  }

  private def parseBool(s: String): Boolean = {
    val t = s.trim.toLowerCase
    t == "true" || t == "1"
  }

  private def parseLong(s: String): Option[Long] =
    try Some(s.trim.toLong) catch { case _: NumberFormatException => None }

  private def parseInt(s: String): Option[Int] =
    try Some(s.trim.toInt) catch { case _: NumberFormatException => None }
}
