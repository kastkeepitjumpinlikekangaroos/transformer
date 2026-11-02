package com.transformer.core

/** Per-query knobs the planner and spill-capable operators read at construction
  * time. Plumbed from `DataJob.runOneTask` through `SqlExecutor.execute` into
  * `PhysicalPlanner.plan` and finally to the breakers that opt into spill.
  *
  * The defaults disable spill and metrics entirely — the only way a query
  * spills or emits a `_perf.json` is for the caller to explicitly construct
  * non-default `ExecutionOptions`. Ad-hoc paths (GUI SQL Console, the 2-arg
  * `SqlExecutor.execute` shim) always use `ExecutionOptions.Default`.
  *
  * @param spillEnabled
  *   Master switch for spill. When false, no operator touches the spill path
  *   and the spill threshold is irrelevant.
  * @param spillThresholdBytes
  *   Per-operator threshold. Crossing this byte count triggers a flush of
  *   partial state to a spill file. `None` lets each operator compute its own
  *   default (typically `maxHeap / 4 / Scheduler.parallelism`, capped at 1 GiB).
  * @param spillMaxRuns
  *   Abort if any one operator spills more than this many times — a guard
  *   against pathological data that produces unbounded spill runs.
  * @param metricsEnabled
  *   Master switch for per-operator metrics. When false (default), the planner
  *   returns the un-wrapped operator tree and the per-task `_perf.json` is not
  *   written; the only added cost on this path is one branch in
  *   `PhysicalPlanner.plan`. When true, each operator's iterator is wrapped in
  *   a `MeteredIterator` and `DataJob.runOneTask` writes a `_perf.json` sibling
  *   to `_run.json`.
  * @param stopped
  *   Asked before each batch every scan of the query reads, on whichever thread
  *   reads it; once it says true, the scan throws [[QueryStoppedException]],
  *   which fails the query wherever it is, so no operator reads another row.
  *   Work already holding its rows (a sort of what it has read) runs to its
  *   end first. Never, by default; `transformer query` stops a statement on
  *   Ctrl-C through it.
  */
final case class ExecutionOptions(
    spillEnabled: Boolean = false,
    spillThresholdBytes: Option[Long] = None,
    spillMaxRuns: Int = ExecutionOptions.DefaultSpillMaxRuns,
    metricsEnabled: Boolean = false,
    stopped: () => Boolean = ExecutionOptions.NeverStopped)

/** What a scan throws once its query's [[ExecutionOptions.stopped]] says to
  * stop. */
final class QueryStoppedException extends RuntimeException("the query was stopped")

object ExecutionOptions {

  /** Default `spill_max_runs`. Picked high enough that a real workload won't
    * trip it under normal conditions, low enough that a stuck loop produces a
    * clear error before exhausting disk. */
  val DefaultSpillMaxRuns: Int = 1000

  /** The default [[ExecutionOptions.stopped]]: one value, so options that
    * leave it alone are equal. */
  val NeverStopped: () => Boolean = () => false

  /** Default options — spill disabled. Use this in any code path that doesn't
    * carry per-task config (GUI SQL Console, tests that don't care). */
  val Default: ExecutionOptions = ExecutionOptions()

  /** Config key for the master spill switch (read from `output.json`'s
    * `options` map): `true` or `false` ([[OptionValues.bool]]). */
  val SpillKey: String = "spill"

  /** Config key for the per-operator spill threshold, a whole number of
    * bytes; without one each operator computes its own default. */
  val SpillThresholdKey: String = "spill_threshold_bytes"

  /** Config key for `spillMaxRuns`, a whole number; without one,
    * [[DefaultSpillMaxRuns]]. */
  val SpillMaxRunsKey: String = "spill_max_runs"

  /** Config key for the master metrics switch (read from `output.json`'s
    * `options` map): `true` or `false`. Mirrors [[SpillKey]]. */
  val MetricsKey: String = "metrics"

  /** The keys of an output's `options` map that this reads; the map also
    * carries its writer's ([[com.transformer.job.OutputFilePath]] checks
    * them all). */
  val Keys: Seq[String] = Seq(SpillKey, SpillThresholdKey, SpillMaxRunsKey, MetricsKey)

  /** Sentinel returned by [[triState]] when a key was not present in the
    * options map at all. Distinguishes "user didn't say" (-> global default
    * applies) from "user explicitly disabled" (-> global default does NOT
    * override). */
  val UnsetTri: Int = 0

  /** Returned by [[triState]] when a key was present and read `true`. */
  val TrueTri: Int = 1

  /** Returned by [[triState]] when a key was present and read `false`. */
  val FalseTri: Int = -1

  /** Tri-state read of a boolean config key. Returns [[UnsetTri]] when the key
    * is absent (caller's global default applies), and [[TrueTri]] or
    * [[FalseTri]] for its value. Used by `DataJob.runOneTask` to decide
    * whether a per-task `options.metrics` value should override the
    * env/sysprop global default. */
  def triState(opts: Map[String, String], key: String): Int = opts.get(key) match {
    case None    => UnsetTri
    case Some(v) => if (OptionValues.bool(key, v)) TrueTri else FalseTri
  }

  /** Parse `ExecutionOptions` from the flat `options` map carried on
    * `OutputFilePath`, reading only its own [[Keys]]: the same map carries
    * the writer's. Each value is read strictly ([[OptionValues]]), and one
    * that is no value of its key is an error naming it. It was tolerant by
    * design, so `"spill": "yes"` ran without spill and `"64MB"` as a
    * threshold was ignored, with nothing said (plans/robustness/27). */
  def fromOutputOptions(opts: Map[String, String]): ExecutionOptions =
    ExecutionOptions(
      spillEnabled = opts.get(SpillKey).exists(OptionValues.bool(SpillKey, _)),
      spillThresholdBytes = opts.get(SpillThresholdKey).map(OptionValues.positiveLong(SpillThresholdKey, _)),
      spillMaxRuns = opts.get(SpillMaxRunsKey).map(OptionValues.positiveInt(SpillMaxRunsKey, _))
        .getOrElse(DefaultSpillMaxRuns),
      metricsEnabled = opts.get(MetricsKey).exists(OptionValues.bool(MetricsKey, _)))
}

/** How a value in a config's `options` map is read, the same way for every
  * key of every reader and writer: strictly, a value that is none of its
  * key's being an error naming the key and the value. Each reader had its own
  * tolerance, so `"yes"` was true to the CSV reader and false to `spill`, and
  * a size with a unit was ignored (plans/robustness/27). */
object OptionValues {

  /** `true` or `false`, in any case. */
  def bool(key: String, value: String): Boolean =
    if (value.equalsIgnoreCase("true")) true
    else if (value.equalsIgnoreCase("false")) false
    else throw new IllegalArgumentException(s"Option '$key' must be true or false, not '$value'")

  /** A whole number, 1 or more, in ASCII digits: a size in bytes has no unit. */
  def positiveLong(key: String, value: String): Long = {
    val n =
      if (value.nonEmpty && value.forall(c => c >= '0' && c <= '9'))
        try java.lang.Long.parseLong(value) catch { case _: NumberFormatException => 0L }
      else 0L
    if (n < 1) throw new IllegalArgumentException(s"Option '$key' must be a whole number, 1 or more, not '$value'")
    n
  }

  def positiveInt(key: String, value: String): Int = {
    val n = positiveLong(key, value)
    if (n > Int.MaxValue) throw new IllegalArgumentException(
      s"Option '$key' must be a whole number from 1 to ${Int.MaxValue}, not '$value'")
    n.toInt
  }

  /** A character set this JVM has. */
  def charset(key: String, value: String): java.nio.charset.Charset =
    try java.nio.charset.Charset.forName(value)
    catch {
      case _: IllegalArgumentException => throw new IllegalArgumentException(
        s"Option '$key' must name a character set, such as UTF-8, not '$value'")
    }

  /** The keys of `options` that `accepted` does not hold, for a refusal. */
  def unknown(options: Map[String, String], accepted: Seq[String]): Seq[String] =
    options.keys.toSeq.filterNot(accepted.contains).sorted
}
