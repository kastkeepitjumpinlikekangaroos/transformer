package com.transformer.core

import java.util.concurrent.Callable
import java.util.concurrent.atomic.{AtomicInteger, AtomicReferenceArray}
import scala.collection.mutable

/** An [[ExecutedQuery]]'s partitions read on [[Scheduler.pool]], for a
  * consumer that reads a whole result: `transformer query`'s formats and
  * `COPY`, and the GUI's query console. Two ways:
  *
  *   - [[eachPartition]], for a consumer that needs no row order across
  *     partitions: up to `inFlight` pool tasks each take the next partition no
  *     task has taken and run the consumer's function over it to its end,
  *     until none is left; the caller gets each partition's result, in
  *     partition order, once all are read.
  *   - [[inOrder]], for one that needs the rows in order: an iterator of the
  *     result's batches, in partition order, whose next batches are read
  *     ahead on the pool, a bounded number at a time.
  *
  * A result of one partition (an aggregate, a sort, a LIMIT, EXPLAIN) is read
  * on the caller's thread, as [[ExecutedQuery.batches]] reads it.
  *
  * Nothing on the pool waits for the consumer. A task reads its partition and
  * returns; it waits only for the tasks its partition's own operators submit,
  * a tree, which [[Scheduler.Submitted.await]] work-helps. The consumer waits
  * only in `Submitted.await`. No queue: a pool task parked on a bounded queue
  * for a slow consumer is parked in `ForkJoinPool.managedBlock`, which JDK
  * 21's condition waits call by themselves, and whose spare threads wedged the
  * pool in plans/bugfixes/02b. A partition moves from one pool thread to
  * another only between reads, each completed before the next is submitted,
  * which [[ExecutedQuery.partition]] allows.
  *
  * Every partition stops at its next batch once the drain's [[Stop]] says so,
  * and the drain calls it at a partition's first failure: the failure the
  * caller gets is the lowest-numbered partition's that is no stop, as itself
  * ([[Scheduler.rethrowable]]: a fatal error wrapped), else a stop. When either
  * returns or throws, nothing it started still runs.
  */
object ParallelDrain {

  /** What stops a drain's reads: `stopped`, asked before each batch read,
    * and `stop`, which the drain calls at a partition's first failure and
    * when it is closed before its end, so the other partitions stop at their
    * next batch. A query's scans ask the same question when it is the
    * query's [[ExecutionOptions.stopped]], which it should be. */
  final case class Stop(stopped: () => Boolean, stop: () => Unit)

  /** The batches an [[inOrder]] fill reads at most: 65,536 rows at the
    * default batch capacity. */
  val ChunkBatches: Int = 8

  /** Heap set aside for each partition read at once, as the parquet writer
    * sets aside 256 MB for each part file it writes at once: a reader's row
    * group and the batches it decodes. */
  val PerReaderHeapMb: Long = 256L

  /** How many of `partitions` to read at once: no more than there are, than
    * the pool has threads or the machine processors, or than the heap holds
    * [[PerReaderHeapMb]] for; at least one. 8 on an 8-core machine at
    * `-Xmx2g`, 4 at `-Xmx1g`, 1 on a pool of one thread. */
  def inFlight(partitions: Int): Int = {
    val cores = math.max(1, Runtime.getRuntime.availableProcessors)
    val byHeap = math.max(1L, Runtime.getRuntime.maxMemory / (1024L * 1024L) / PerReaderHeapMb)
    math.max(1, math.min(math.min(partitions.toLong, byHeap), math.min(Scheduler.parallelism, cores).toLong).toInt)
  }

  // ---------------------------------------------------------------------------
  // Each partition, in no order
  // ---------------------------------------------------------------------------

  /** `f` over each partition of `q`, the partition's index and its batches,
    * on at most `inFlight` pool tasks, each taking the next partition no task
    * has taken (so a slow partition holds up no other); the results in
    * partition order, once every partition is read. `f` sees a stop as its
    * iterator throwing [[QueryStoppedException]]. A result of one partition
    * is read on the caller's thread.
    *
    * When a partition fails, or `f` does on it, the others stop at their next
    * batch, every task ends, and the failure is thrown here: the
    * lowest-numbered partition's that is no stop, else a stop. Each
    * partition is closed once read, and those never read once every task has
    * ended. */
  def eachPartition[A](q: ExecutedQuery, inFlight: Int, stop: Stop)(f: (Int, Iterator[ColumnarBatch]) => A): IndexedSeq[A] =
    q.numPartitions match {
      case 0 => IndexedSeq.empty
      case 1 => IndexedSeq(f(0, watched(q.partition(0), stop.stopped)))
      case n => new Each(q, n, inFlight, stop, f).run()
    }

  private final class Each[A](q: ExecutedQuery, n: Int, inFlight: Int, stop: Stop, f: (Int, Iterator[ColumnarBatch]) => A) {
    // Claimed before any is read, so a failure in one does not close another
    // under the task reading it.
    private val parts = Vector.tabulate(n)(q.claim)
    // Each written by the task that read the partition, and read once every
    // task has returned.
    private val results = new Array[Any](n)
    private val read = new Array[Boolean](n)
    private val failures = new Failures(n, stop)
    private val taken = new AtomicInteger

    def run(): IndexedSeq[A] = {
      val tasks = Vector.fill(math.max(1, math.min(inFlight, n)))(Scheduler.submit(new Callable[Unit] {
        def call(): Unit = work()
      }))
      tasks.foreach(t => try t.await() catch { case e: Throwable => failures.record(n, e) })
      parts.foreach(Closing.close)
      failures.thrown.foreach(t => throw t)
      // A partition no task read: the drain was stopped before it began.
      if (!read.forall(identity)) throw new QueryStoppedException
      results.toIndexedSeq.asInstanceOf[IndexedSeq[A]]
    }

    private def work(): Unit = {
      var p = taken.getAndIncrement()
      while (p < n && !stop.stopped()) {
        try {
          results(p) = Closing.draining(parts(p))(it => f(p, watched(it, stop.stopped)))
          read(p) = true
        } catch { case t: Throwable => failures.record(p, t) }
        p = taken.getAndIncrement()
      }
    }
  }

  // ---------------------------------------------------------------------------
  // In order
  // ---------------------------------------------------------------------------

  /** `q`'s batches in partition order, read ahead on the pool: a window of
    * `readAhead` partitions from the one being read, each read by fills, pool
    * tasks that each read at most `chunk` batches of one partition and
    * return them. A partition has one fill at a time: its first is submitted
    * as it enters the window, and each next one as the iterator takes the
    * chunk before it, so the iterator holds at most `readAhead + 1` chunks
    * not yet returned (two of the partition being read, one of each ahead),
    * and a consumer that stops taking holds no pool thread. Nothing is
    * submitted before the first `hasNext`. A result of one partition is read
    * on the caller's thread.
    *
    * `stop` is asked before each batch the iterator returns and each a fill
    * reads. A partition's failure stops the rest, and the iterator throws, at
    * its next `hasNext`, the lowest-numbered partition's failure that is no
    * stop, else a stop. `close()` stops what is still being read when the
    * iterator has not reached its end, waits for every fill in flight, and
    * closes every partition it read; the consumer calls it however its read
    * ends, then closes `q`. */
  def inOrder(q: ExecutedQuery, readAhead: Int, chunk: Int, stop: Stop): Iterator[ColumnarBatch] with AutoCloseable =
    if (q.numPartitions <= 1) new Direct(watched(q.batches, stop.stopped))
    else new Ordered(q, q.numPartitions, math.max(1, readAhead), math.max(1, chunk), stop)

  private final class Direct(it: Iterator[ColumnarBatch]) extends Iterator[ColumnarBatch] with AutoCloseable {
    def hasNext: Boolean = it.hasNext
    def next(): ColumnarBatch = it.next()
    def close(): Unit = ()
  }

  /** What a fill read: up to a chunk of batches, whether the partition ended
    * with them, and whether the read failed. */
  private final class Filled(val batches: Array[ColumnarBatch], val ended: Boolean, val failed: Boolean)

  private final class Ordered(q: ExecutedQuery, n: Int, window: Int, chunk: Int, stop: Stop)
      extends Iterator[ColumnarBatch] with AutoCloseable {

    // Every field is the consumer's, on its thread, but `failures`, which the
    // fills write.
    private var parts: Vector[Iterator[ColumnarBatch]] = Vector.empty
    // Each partition's fill in flight, or null.
    private val fills = new Array[Scheduler.Submitted[Filled]](n)
    private val failures = new Failures(n, stop)
    // The partition being read, and the partitions [reading, entered) in the window.
    private var reading = 0
    private var entered = 0
    // The chunk being returned, and the next batch of it to return.
    private var batches: Array[ColumnarBatch] = Array.empty
    private var at = 0
    // Whether the chunk being returned is the last of the partition read.
    private var last = false
    private var started = false
    private var finished = false
    private var closed = false

    def hasNext: Boolean = {
      if (finished || closed) return false
      if (!started) start()
      check()
      while (at >= batches.length) {
        if (last) {
          reading += 1
          if (reading == n) { finished = true; return false }
          last = false
          enter()
        }
        take()
        check()
      }
      true
    }

    def next(): ColumnarBatch = {
      if (!hasNext) throw new NoSuchElementException("no batch left")
      val b = batches(at)
      batches(at) = null
      at += 1
      b
    }

    /** Stop what is still being read, unless the end was reached; wait for
      * every fill in flight; close every partition. */
    def close(): Unit = if (!closed) {
      closed = true
      if (started) {
        if (!finished) stop.stop()
        var p = 0
        while (p < n) {
          val f = fills(p)
          if (f != null) {
            fills(p) = null
            try { val _ = f.await() } catch { case t: Throwable => failures.record(p, t) }
          }
          p += 1
        }
        parts.foreach(Closing.close)
      }
      batches = Array.empty
      at = 0
    }

    private def start(): Unit = {
      started = true
      // Claimed before any is read, so a failure in one does not close
      // another under the fill reading it.
      parts = Vector.tabulate(n)(q.claim)
      enter()
    }

    /** Submit the first fill of each partition the window now reaches. */
    private def enter(): Unit =
      while (entered < n && entered < reading + window) {
        submit(entered)
        entered += 1
      }

    /** Take the next chunk of the partition being read, and submit its next
      * fill unless the partition ended. */
    private def take(): Unit = {
      val f = fills(reading)
      fills(reading) = null
      val got = try f.await() catch { case t: Throwable => failures.record(reading, t); null }
      if (got == null || got.failed) fail()
      batches = got.batches
      at = 0
      last = got.ended
      if (!last) submit(reading)
    }

    private def submit(p: Int): Unit =
      fills(p) = Scheduler.submit(new Callable[Filled] { def call(): Filled = fill(p) })

    /** Read at most a chunk of partition `p`, on a pool thread. A failure is
      * recorded, and stops the other partitions, rather than thrown. */
    private def fill(p: Int): Filled = {
      val it = parts(p)
      val got = mutable.ArrayBuffer.empty[ColumnarBatch]
      try {
        var ended = false
        while (!ended && got.length < chunk) {
          if (stop.stopped()) throw new QueryStoppedException
          ended = !it.hasNext
          if (!ended) got += it.next()
        }
        new Filled(got.toArray, ended, failed = false)
      } catch {
        case t: Throwable =>
          failures.record(p, t)
          new Filled(Array.empty, ended = true, failed = true)
      }
    }

    private def check(): Unit = if (failures.any || stop.stopped()) fail()

    /** Close, then throw what stopped the read. */
    private def fail(): Nothing = {
      close()
      throw failures.thrown.getOrElse(new QueryStoppedException)
    }
  }

  // ---------------------------------------------------------------------------
  // Shared
  // ---------------------------------------------------------------------------

  /** `it`, asking `stopped` before each batch. */
  private def watched(it: Iterator[ColumnarBatch], stopped: () => Boolean): Iterator[ColumnarBatch] =
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = { if (stopped()) throw new QueryStoppedException; it.hasNext }
      def next(): ColumnarBatch = it.next()
    }

  /** Each partition's failure, the first of each, recorded from any thread;
    * recording one stops the drain. Slot `n` holds a failure of no
    * partition. */
  private final class Failures(n: Int, stop: Stop) {
    private val byPartition = new AtomicReferenceArray[Throwable](n + 1)
    @volatile private var recorded = false

    def record(p: Int, t: Throwable): Unit = {
      val _ = byPartition.compareAndSet(p, null, t)
      recorded = true
      stop.stop()
    }

    def any: Boolean = recorded

    /** What the drain throws: the lowest-numbered partition's failure that
      * is no stop, else the first stop, as [[Scheduler.rethrowable]] gives
      * it; None when nothing failed. Read once every reader has ended. */
    def thrown: Option[Throwable] =
      if (!recorded) None
      else {
        val all = (0 to n).iterator.map(byPartition.get).filter(_ != null).toVector
        all.find(t => !isStop(t)).orElse(all.headOption).map(Scheduler.rethrowable)
      }
  }

  /** Whether `t` is a read stopping as it was asked to: a
    * [[QueryStoppedException]] among its causes, however the engine's
    * threads passed it on. */
  def isStop(t: Throwable): Boolean =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(64).exists(_.isInstanceOf[QueryStoppedException])
}
