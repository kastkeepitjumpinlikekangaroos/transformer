package com.transformer.core.metrics

import com.transformer.core.Settings

import java.lang.management.{ManagementFactory, ThreadMXBean}

import scala.jdk.CollectionConverters._

/** Process-level helpers for the in-tree instrumentation framework.
  *
  * The process- and thread-level sampling helpers live here. The framework as
  * a whole is opt-in per [[com.transformer.core.ExecutionOptions.metricsEnabled]];
  * this object provides:
  *
  *   - [[globalDefault]] — the boolean default consulted by
  *     `DataJob.runOneTask` when a task's own `options.metrics` is unset.
  *     Resolved once at class load from
  *     [[com.transformer.core.Settings.MetricsEnabled]]
  *     (`transformer.metrics.enabled`).
  *   - Per-thread sampling helpers ([[currentCpuNanos]],
  *     [[currentAllocatedBytes]]) used at task boundaries to capture
  *     ThreadMXBean snapshots. Both fall back gracefully to `-1L` when the
  *     underlying JVM bean isn't HotSpot — non-HotSpot JVMs still get a usable
  *     `_perf.json`, just with allocation accounting disabled.
  *   - [[gcSample]] / [[GcSample.delta]] for cumulative GC time/count deltas
  *     between task start and finish.
  *
  * Important: [[globalDefault]] is the *default* used when seeding a per-task
  * `ExecutionOptions`. The runtime check that wraps operators in metered
  * iterators consults `opts.metricsEnabled` on the `ExecutionOptions` already
  * threaded through `PhysicalPlanner.plan`. Operators never read this object.
  */
object MetricsCollector {

  /** Global default applied by `DataJob.runOneTask` when seeding the per-task
    * `ExecutionOptions` and the task's own `options.metrics` is unset.
    *
    * Resolved once at class load. Once set, every task in this JVM picks up
    * the same default — there is no facility for runtime reload because the
    * intended consumer is a single CLI invocation. */
  val globalDefault: Boolean = Settings.MetricsEnabled.get

  /** Lazy handle to the JVM's [[ThreadMXBean]]. Cached because `getThreadMXBean`
    * returns a stable singleton; we just want to avoid repeating the lookup in
    * tight loops. */
  private val threadMxBean: ThreadMXBean = ManagementFactory.getThreadMXBean

  /** Whether the running JVM exposes `com.sun.management.ThreadMXBean` (the
    * HotSpot extension that provides per-thread allocation accounting). True
    * on Oracle / OpenJDK / Temurin; false on non-HotSpot JVMs. */
  val supportsThreadAllocations: Boolean =
    threadMxBean.isInstanceOf[com.sun.management.ThreadMXBean]

  /** Whether per-thread CPU measurement is enabled on this JVM. Most JVMs
    * support it but it is *off* by default — we enable it once here so
    * [[currentCpuNanos]] returns useful values. Caught and ignored on JVMs
    * that refuse (the bean returns -1 in that case, which surfaces in
    * `_perf.json` as null). */
  locally {
    try if (threadMxBean.isThreadCpuTimeSupported && !threadMxBean.isThreadCpuTimeEnabled)
      threadMxBean.setThreadCpuTimeEnabled(true)
    catch { case _: Throwable => () }
  }

  /** Cumulative CPU time for the calling thread, in nanoseconds. Returns -1
    * when unsupported (the standard `ThreadMXBean` contract). Callers should
    * compare two samples taken on the same thread — cross-thread comparison
    * is nonsensical. */
  def currentCpuNanos(): Long = {
    try threadMxBean.getCurrentThreadCpuTime
    catch { case _: Throwable => -1L }
  }

  /** Cumulative bytes allocated by the given thread, as reported by HotSpot's
    * `ThreadMXBean.getThreadAllocatedBytes`. Returns -1 on non-HotSpot JVMs.
    *
    * Use `Thread.currentThread.getId` to obtain the thread id at the same
    * thread as the call site — cross-thread reads of another thread's
    * allocation count return a value the bean updates only at safepoints, so
    * the granularity is coarse. */
  def currentAllocatedBytes(threadId: Long): Long = threadMxBean match {
    case sun: com.sun.management.ThreadMXBean =>
      try sun.getThreadAllocatedBytes(threadId)
      catch { case _: Throwable => -1L }
    case _ => -1L
  }

  /** Snapshot of cumulative GC count + time across every collector at this
    * instant. Take one at task start and one at task finish, subtract via
    * [[GcSample.delta]] to attribute GC to the task. */
  def gcSample(): GcSample = {
    val beans = ManagementFactory.getGarbageCollectorMXBeans.asScala
    var count = 0L
    var nanos = 0L
    beans.foreach { b =>
      val c = b.getCollectionCount
      val t = b.getCollectionTime
      if (c > 0L) count += c
      if (t > 0L) nanos += t * 1000000L // beans return millis
    }
    GcSample(count, nanos)
  }
}

/** Cumulative GC delta between two [[MetricsCollector.gcSample]] points.
  *
  * `count` is the total number of collections recorded across every GC bean
  * (young + old + ZGC + Shenandoah etc.); `nanos` is the cumulative
  * wall-clock time those collections took. Both are summed across beans
  * because the per-task `_perf.json` only needs the aggregate. */
final case class GcSample(count: Long, nanos: Long) {

  /** This sample minus `start`. Both are cumulative process-level counters,
    * so subtracting yields the GC activity that happened between the two
    * samples — including any GC triggered by another thread (single-node
    * process, all threads share the same GC). */
  def delta(start: GcSample): GcSample =
    GcSample(count - start.count, nanos - start.nanos)
}

object GcSample {
  val Zero: GcSample = GcSample(0L, 0L)
}
