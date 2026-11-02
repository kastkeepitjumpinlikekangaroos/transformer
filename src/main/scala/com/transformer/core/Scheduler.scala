package com.transformer.core

import java.util.concurrent.{Callable, ExecutionException, ForkJoinPool, ForkJoinTask, ForkJoinWorkerThread}
import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

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
  * Nested submission of a pure task TREE is safe because `ForkJoinTask.get`/
  * `.join` work-helps — a worker blocked waiting for its child tasks runs them
  * on its own stack instead of deadlocking the scheduler. Helping is not a
  * blanket liveness guarantee, though: its helpJoin path runs tasks from
  * stealers' queues that need not be descendants of the awaited task, which is
  * how K>1 sharded exchanges can still deadlock (see docs/gotchas.md).
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
    * Override with [[Settings.Parallelism]]
    * (`transformer.scheduler.parallelism`). Going higher costs heap (more
    * in-flight `ColumnarBatch`es per writer × per scan) and may thrash under
    * tight memory; going lower can be useful on shared hosts where you don't
    * want transformer hogging cores.
    */
  val parallelism: Int = Settings.Parallelism.get

  /** The pool itself. Lifetime = JVM; threads are daemon so the pool never
    * prevents process exit. Sized to [[parallelism]]. */
  val pool: ForkJoinPool = new ForkJoinPool(
    parallelism,
    daemonFactory,
    /*uncaughtExceptionHandler =*/ null,
    /*asyncMode =*/ false
  )

  // Under `--config=leaks` the exit audit watches every JVM that runs the
  // engine, whatever it opens.
  LeakAudit.watch()

  /** Submit a `Callable` on the shared pool. [[Submitted.await]] waits for it,
    * and from a pool worker that wait work-helps, so nested submission won't
    * deadlock.
    */
  def submit[T](c: Callable[T]): Submitted[T] = {
    val failure = new AtomicReference[Throwable]
    val task = pool.submit(new Callable[T] {
      def call(): T =
        try {
          Chaos.at(Chaos.Submit)
          c.call()
        } catch { case t: Throwable => failure.set(t); throw t }
    })
    new Submitted(task, failure)
  }

  /** Submit every task and block until all have completed; results returned in
    * submission order. Convenience for the common "fan out N partitions, wait
    * for them all" pattern in pipeline-breaking operators.
    *
    * Every task is waited for, whether or not one failed, and then the first
    * in submission order that failed throws its own exception here, as
    * [[Submitted.await]] does: so when it throws, nothing it started still
    * runs, and the caller can release what the tasks shared (a spill
    * directory) without a task still writing there. It returned at the first
    * failure, and a sibling task could hold its input open, or spill into a
    * directory already deleted, after the query had failed (plans/testing/17).
    */
  def submitAndAwaitAll[T](tasks: Seq[Callable[T]]): IndexedSeq[T] = {
    val submitted = tasks.iterator.map(submit(_)).toIndexedSeq
    var failure: Throwable = null
    val results = submitted.map { s =>
      try s.await()
      catch { case t: Throwable => if (failure == null) failure = t; null.asInstanceOf[T] }
    }
    if (failure != null) throw failure
    results
  }

  /** A callable running on [[pool]], and what it threw if it failed. */
  final class Submitted[T] private[Scheduler] (task: ForkJoinTask[T], failure: AtomicReference[Throwable]) {

    /** Block until the callable has returned and give its result, or throw
      * what it threw, as [[rethrowable]] says. `ForkJoinTask.get` alone
      * throws an `ExecutionException` around a copy of the callable's
      * exception made on the waiting thread: the same class, with the
      * original's `toString` as its message, so a caller would read
      * `java.lang.IllegalArgumentException: java.lang.IllegalArgumentException:
      * Cannot cast 'x' to int`, or no message at all when the class has no
      * constructor that takes a cause, as `ArithmeticException` has not.
      *
      * The wait is deliberately a plain `get()`: from a pool worker it
      * work-helps, which is what keeps nested fan-outs live on this bounded
      * pool. Do not swap it for a latch or `ForkJoinPool.managedBlock` —
      * `//src/test/scala/com/transformer/core:scheduler_test` fails if the
      * helping mechanism goes away.
      */
    def await(): T =
      try {
        Chaos.at(Chaos.Await)
        task.get()
      }
      catch {
        case e: ExecutionException => throw Option(failure.get).fold[Throwable](e)(rethrowable)
      }
  }

  /** What a thread that waited for work done on another thread throws when
    * that work threw `failure`: `failure` itself, which names what went
    * wrong, with the waiting thread's stack added to it as a suppressed
    * exception. A fatal error (one `NonFatal` does not match, such as an
    * `OutOfMemoryError` or a `StackOverflowError`) is wrapped in an
    * `ExecutionException` instead, so that a caller which catches only
    * non-fatal failures, as `DataJob`'s task runner does, still catches it
    * rather than dying with it.
    */
  def rethrowable(failure: Throwable): Throwable = failure match {
    case NonFatal(t) =>
      t.addSuppressed(new WaitedFor)
      t
    case fatal => new ExecutionException(fatal)
  }

  /** Added to an exception rethrown on the thread that waited for the work
    * that threw it; its stack is where that thread waited. */
  private final class WaitedFor
    extends RuntimeException(s"waited for on thread ${Thread.currentThread.getName}")
}
