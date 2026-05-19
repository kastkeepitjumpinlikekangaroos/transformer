package com.transformer.core

import java.util.concurrent.{Callable, ForkJoinPool, ForkJoinTask, ForkJoinWorkerThread}

/** Shared work-stealing scheduler used everywhere this library needs parallel
  * execution: DAG-level task scheduling, per-operator partition fan-out, parallel
  * input materialization, partitioned writers.
  *
  * Before this object existed, each pipeline-breaking operator
  * ([[com.transformer.sql.exec.AggregateExec]], `JoinExec`, `SortExec`,
  * `DistinctExec`), each writer (`CsvWriter`, `ParquetWriter`), the
  * input-resolution stage of `DataJob.run`, and the DAG task scheduler all spun
  * up their own `Executors.newFixedThreadPool(...)` of size ≈ `cores`. With even
  * a handful of concurrent SQL tasks each running an aggregate + writer + scan
  * fan-out, the JVM ended up with N × cores OS threads contending for cores ×
  * physical cores — no work-stealing across pools, and a single heavy task could
  * pin its pool while neighbouring pools sat idle.
  *
  * This single pool gives the whole library cooperative parallelism: any thread
  * (worker OR main) submitting `Callable`s to `pool` gets work-stealing for free.
  * Nested submission is safe because `ForkJoinTask.get`/`.join` cooperates with
  * the pool — a worker blocked waiting for its child tasks won't deadlock the
  * scheduler.
  *
  * Threads are daemon so the pool never blocks JVM exit. Default size is
  * `2 × availableProcessors` (see [[parallelism]] for why — mixed CPU + I/O
  * workloads need headroom so one branch's blocked I/O doesn't starve
  * independent branches). Override via the `transformer.scheduler.parallelism`
  * system property or the `TRANSFORMER_SCHEDULER_PARALLELISM` env var. We do
  * NOT cap concurrent in-flight ColumnarBatches in the pool itself —
  * individual call sites (notably `ParquetWriter.writePartitioned`, which
  * clamps to `min(cores, heap/256MB)`) bound their own fan-out so a single
  * heavy task can't pin the whole pool.
  */
object Scheduler {
  private val daemonFactory: ForkJoinPool.ForkJoinWorkerThreadFactory =
    new ForkJoinPool.ForkJoinWorkerThreadFactory {
      def newThread(p: ForkJoinPool): ForkJoinWorkerThread = {
        val t = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(p)
        t.setDaemon(true)
        t.setName(s"transformer-worker-${t.getPoolIndex}")
        t
      }
    }

  /** Available parallelism; matches the parallelism the pool was sized with so
    * callers that want to clamp their own fan-out (e.g. partitioned writers
    * bounded by heap) can read it once instead of recomputing.
    *
    * Default: 2× `availableProcessors`. The work this pool serves is a mix of
    * CPU-bound (decode, filter, aggregate) and I/O-bound (parquet/CSV reads
    * and writes); a worker blocked on disk I/O cannot steal CPU-bound work,
    * so sizing strictly to `cores` leaves the box idle whenever the heavy
    * task is in its I/O phase. Doubling absorbs that — independent DAG
    * branches keep making progress while one branch's writers drain.
    *
    * Override with the system property `transformer.scheduler.parallelism`
    * (or the env var `TRANSFORMER_SCHEDULER_PARALLELISM`). Going higher costs
    * heap (more in-flight `ColumnarBatch`es per writer × per scan) and may
    * thrash under tight memory; going lower can be useful on shared hosts
    * where you don't want transformer hogging cores.
    */
  val parallelism: Int = {
    val cores = math.max(1, Runtime.getRuntime.availableProcessors)
    val configured = Option(System.getProperty("transformer.scheduler.parallelism"))
      .orElse(Option(System.getenv("TRANSFORMER_SCHEDULER_PARALLELISM")))
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(s => try Some(s.toInt) catch { case _: NumberFormatException => None })
    math.max(1, configured.getOrElse(cores * 2))
  }

  /** The pool itself. Lifetime = JVM; threads are daemon so the pool never
    * prevents process exit. Sized to [[parallelism]]. */
  val pool: ForkJoinPool = new ForkJoinPool(
    parallelism,
    daemonFactory,
    /*uncaughtExceptionHandler =*/ null,
    /*asyncMode =*/ false
  )

  /** Submit a `Callable` on the shared pool; the returned `ForkJoinTask`
    * cooperates with worker threads' join/get so nested submission won't
    * deadlock.
    */
  def submit[T](c: Callable[T]): ForkJoinTask[T] = pool.submit(c)

  /** Submit every task and block until all have completed; results returned in
    * submission order. Convenience for the common "fan out N partitions, wait
    * for them all" pattern in pipeline-breaking operators.
    *
    * Any `Throwable` from a task is rethrown (unwrapping `ExecutionException`).
    * Caller is responsible for cleanup ordering; everything else is best-effort
    * draining.
    */
  def submitAndAwaitAll[T](tasks: Seq[Callable[T]]): IndexedSeq[T] = {
    val ftasks = tasks.iterator.map(pool.submit(_)).toIndexedSeq
    ftasks.map(_.get())
  }
}
