package com.transformer.core

import org.junit.Assert._
import org.junit.Assume.assumeTrue
import org.junit.Test

import java.util.concurrent.{Callable, ConcurrentHashMap, CountDownLatch, ExecutionException, TimeUnit}
import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}

/** [[ParallelDrain]] over synthetic results whose partitions record each
  * pull, the thread that made it, and any two pulls of one partition at
  * once. Two targets run it: `parallel_drain_test` on a pool of two threads
  * and `parallel_drain_serial_test` on one, each pinned by its `jvm_flags`
  * (`parallelismIsPinnedByJvmFlags`), so a test holds on one thread too. */
class ParallelDrainTest {

  private val schema = Schema(Vector(Field("x", DataType.IntType)))

  /** A one-row batch holding `v`. */
  private def batch(v: Int): ColumnarBatch = {
    val b = new ColumnarBatch(schema, 1)
    b.column(0).setBoxed(0, v)
    b.setNumRows(1)
    b
  }

  /** Partition `index`: `limit` batches, the first `batches`, made in
    * advance, and then fresh ones. `onPull(k)` runs before the k-th batch
    * (from 1) is returned. Records each pull. */
  private final class Source(index: Int, val batches: Vector[ColumnarBatch], limit: Long, onPull: Int => Unit)
      extends Iterator[ColumnarBatch] with AutoCloseable {
    val pulled = new AtomicInteger
    // By id: every pool worker is named transformer-worker-0, its name made
    // before the pool gives it an index.
    val threads: java.util.Set[java.lang.Long] = ConcurrentHashMap.newKeySet[java.lang.Long]()
    @volatile var overlapped = false
    @volatile var closed = false
    private val inside = new AtomicBoolean

    private def enter(): Unit = {
      if (!inside.compareAndSet(false, true)) overlapped = true
      val _ = threads.add(Thread.currentThread.threadId)
    }

    def hasNext: Boolean = {
      enter()
      try pulled.get < limit finally inside.set(false)
    }

    def next(): ColumnarBatch = {
      enter()
      try {
        val k = pulled.incrementAndGet()
        onPull(k)
        if (k <= batches.length) batches(k - 1) else batch(index * 100000 + k)
      } finally inside.set(false)
    }

    def close(): Unit = closed = true
  }

  /** `n` batches made in advance, which a drain must return themselves. */
  private def finite(index: Int, n: Int, onPull: Int => Unit = _ => ()): Source =
    new Source(index, Vector.tabulate(n)(k => batch(index * 100000 + k + 1)), n.toLong, onPull)

  /** `n` batches made as they are pulled. */
  private def made(index: Int, n: Int): Source = new Source(index, Vector.empty, n.toLong, _ => ())

  private def endless(index: Int, onPull: Int => Unit = _ => ()): Source =
    new Source(index, Vector.empty, Long.MaxValue, onPull)

  private def query(sources: Seq[Source]): ExecutedQuery = new ExecutedQuery(schema, sources.toIndexedSeq)

  /** A drain's stop on a flag of its own. */
  private final class Flag {
    val set = new AtomicBoolean
    val stop: ParallelDrain.Stop = ParallelDrain.Stop(() => set.get, () => set.set(true))
  }

  private def thrownBy(work: => Any): Throwable = try { work; null } catch { case t: Throwable => t }

  private def pulls(sources: Seq[Source]): Int = sources.map(_.pulled.get).sum

  /** The pulls once they stop growing. */
  private def settled(sources: Seq[Source]): Int = {
    var last = -1
    var now = pulls(sources)
    while (now != last) {
      last = now
      Thread.sleep(100)
      now = pulls(sources)
    }
    now
  }

  /** Nothing of the drain runs: no pull in 200 ms, and the pool is quiet. */
  private def nothingRuns(sources: Seq[Source]): Unit = {
    val before = pulls(sources)
    Thread.sleep(200)
    assertEquals("pulled after the drain returned", before, pulls(sources))
    assertTrue("the pool still ran tasks", Scheduler.pool.awaitQuiescence(30, TimeUnit.SECONDS))
  }

  private def drained(it: Iterator[ColumnarBatch] with AutoCloseable): Vector[ColumnarBatch] =
    try it.toVector finally it.close()

  // ---- the pool ----------------------------------------------------------------------

  /** The BUILD `jvm_flags` arrived: the pool has the threads the target
    * names, which `parallel_drain_test.parallelism` repeats. */
  @Test def parallelismIsPinnedByJvmFlags(): Unit = {
    val named = System.getProperty("parallel_drain_test.parallelism")
    assertNotNull("the target names its pool's threads", named)
    assertEquals(named.toInt, Scheduler.parallelism)
  }

  @Test def inFlightIsBoundedByThePartitionsThePoolTheProcessorsAndTheHeap(): Unit = {
    val cores = Runtime.getRuntime.availableProcessors
    val byHeap = (Runtime.getRuntime.maxMemory / (1024L * 1024L) / ParallelDrain.PerReaderHeapMb).toInt
    assertEquals(math.max(1, Seq(18, Scheduler.parallelism, cores, byHeap).min), ParallelDrain.inFlight(18))
    assertEquals(1, ParallelDrain.inFlight(1))
    assertEquals(1, ParallelDrain.inFlight(0))
  }

  // ---- order -------------------------------------------------------------------------

  /** `inOrder` returns the batches `batches` does, the same objects in the
    * same order, for results of 0 to 100 partitions of 0, 1, chunk - 1,
    * chunk, chunk + 1 and many batches, for each window and chunk; and no two
    * pulls of a partition overlap. */
  @Test(timeout = 120000) def inOrderReturnsTheBatchesInPartitionOrder(): Unit =
    for (chunk <- Seq(1, 2, 8); readAhead <- Seq(1, 2, 8); n <- Seq(0, 1, 2, 3, 17, 100)) {
      val sizes = Seq(0, 1, chunk - 1, chunk, chunk + 1, 3 * chunk + 2)
      val sources = (0 until n).map(p => finite(p, sizes((p * 7 + chunk) % sizes.length)))
      val expected = sources.flatMap(_.batches)
      val got = drained(ParallelDrain.inOrder(query(sources), readAhead, chunk, new Flag().stop))
      val where = s"$n partitions, readAhead $readAhead, chunk $chunk"
      assertEquals(where, expected.length, got.length)
      expected.indices.foreach(i => assertSame(s"$where: batch $i", expected(i), got(i)))
      assertFalse(s"$where: two pulls of a partition overlapped", sources.exists(_.overlapped))
    }

  /** A partition moves between the pool's threads, a fill at a time, and no
    * two of its pulls overlap. Each pull takes a millisecond, so every thread
    * of the pool is kept busy with fills. */
  @Test(timeout = 60000) def aPartitionsFillsMoveBetweenThreadsWithoutOverlapping(): Unit = {
    val sources = (0 until 8).map(p => finite(p, 60, _ => Thread.sleep(1)))
    val got = drained(ParallelDrain.inOrder(query(sources), readAhead = 4, chunk = 1, new Flag().stop))
    assertEquals(sources.flatMap(_.batches), got)
    assertFalse(sources.exists(_.overlapped))
    if (Scheduler.parallelism >= 2)
      assertTrue(s"no partition was read on more than one thread: ${sources.map(_.threads)}",
        sources.exists(_.threads.size > 1))
  }

  @Test(timeout = 60000) def eachPartitionReturnsEachPartitionsResultInPartitionOrder(): Unit =
    for (inFlight <- Seq(1, 2, 3, 8); n <- Seq(0, 1, 2, 5, 40)) {
      val sources = (0 until n).map(p => finite(p, p % 4))
      val results = ParallelDrain.eachPartition(query(sources), inFlight, new Flag().stop)((p, it) => p -> it.toVector)
      assertEquals(s"$n partitions at $inFlight", (0 until n).toVector, results.map(_._1))
      results.foreach { case (p, got) => assertEquals(sources(p).batches, got) }
      assertFalse(sources.exists(_.overlapped))
      // Each read partition is closed.
      if (n > 1) assertTrue(sources.forall(_.closed))
    }

  // ---- bounded, no worker held -------------------------------------------------------

  /** A consumer that stops taking leaves at most `readAhead + 1` chunks read
    * and not taken, and holds no thread of the pool: a task submitted then
    * runs, on a pool of one thread too. */
  @Test(timeout = 120000) def aStalledConsumerHoldsBoundedChunksAndNoWorker(): Unit =
    for (readAhead <- Seq(1, 2, 3); chunk <- Seq(1, 2, 4)) {
      val sources = (0 until 6).map(p => endless(p))
      val it = ParallelDrain.inOrder(query(sources), readAhead, chunk, new Flag().stop)
      try {
        (1 to 5).foreach(_ => it.next())
        val ahead = settled(sources) - 5
        assertTrue(s"readAhead $readAhead, chunk $chunk: $ahead batches read ahead",
          ahead <= (readAhead + 1) * chunk)
        val ran = new CountDownLatch(1)
        val _ = Scheduler.submit(new Callable[Unit] { def call(): Unit = ran.countDown() })
        assertTrue("a task submitted while the consumer stalled never ran", ran.await(30, TimeUnit.SECONDS))
      } finally it.close()
      nothingRuns(sources)
    }

  @Test(timeout = 60000) def eachPartitionReadsAtMostInFlightPartitionsAtOnce(): Unit =
    for (inFlight <- Seq(1, 2, 3)) {
      val reading = new AtomicInteger
      val most = new AtomicInteger
      val sources = (0 until 10).map(p => finite(p, 5))
      val _ = ParallelDrain.eachPartition(query(sources), inFlight, new Flag().stop) { (_, it) =>
        val now = reading.incrementAndGet()
        val _ = most.accumulateAndGet(now, (a, b) => math.max(a, b))
        try it.foreach(_ => Thread.sleep(2)) finally { val _ = reading.decrementAndGet() }
      }
      assertTrue(s"${most.get} partitions read at once, at most $inFlight", most.get <= inFlight)
      assertTrue(most.get >= 1)
    }

  // ---- stops -------------------------------------------------------------------------

  @Test(timeout = 60000) def aStopEndsTheIteratorWithQueryStoppedException(): Unit = {
    val sources = (0 until 4).map(p => endless(p))
    val flag = new Flag
    val it = ParallelDrain.inOrder(query(sources), 3, 2, flag.stop)
    (1 to 7).foreach(_ => it.next())
    flag.set.set(true)
    val thrown = thrownBy(it.hasNext)
    assertTrue(s"$thrown", thrown.isInstanceOf[QueryStoppedException])
    it.close()
    nothingRuns(sources)
  }

  @Test(timeout = 60000) def nothingIsReadOnceTheIteratorIsClosedEarlyOrLate(): Unit = {
    // Early: three batches taken of results without end.
    val endlessSources = (0 until 5).map(p => endless(p))
    val flag = new Flag
    val early = ParallelDrain.inOrder(query(endlessSources), 4, 2, flag.stop)
    (1 to 3).foreach(_ => early.next())
    early.close()
    assertTrue("closing before the end stops the drain", flag.set.get)
    nothingRuns(endlessSources)
    // Late: read to the end, then closed; the end stops nothing.
    val finiteSources = (0 until 5).map(p => finite(p, 9))
    val done = new Flag
    val late = ParallelDrain.inOrder(query(finiteSources), 4, 2, done.stop)
    assertEquals(45, late.toVector.length)
    late.close()
    assertFalse(done.set.get)
    nothingRuns(finiteSources)
    // Nothing is read before the first hasNext.
    val untouched = (0 until 3).map(p => endless(p))
    ParallelDrain.inOrder(query(untouched), 3, 2, new Flag().stop).close()
    assertEquals(0, settled(untouched))
  }

  @Test(timeout = 60000) def eachPartitionStopsAtItsNextBatch(): Unit = {
    val flag = new Flag
    val sources = (0 until 4).map(p => endless(p, k => if (p == 0 && k == 50) flag.set.set(true)))
    val thrown = thrownBy(ParallelDrain.eachPartition(query(sources), 2, flag.stop)((_, it) => it.foreach(_ => ())))
    assertTrue(s"$thrown", thrown.isInstanceOf[QueryStoppedException])
    nothingRuns(sources)
  }

  // ---- failures ------------------------------------------------------------------------

  /** Partition 5 of 8 throws at its third batch: the consumer gets that
    * exception itself, the waiting thread's stack added to it, and the other
    * partitions, without end, stop. */
  @Test(timeout = 60000) def aFailingPartitionReachesTheConsumerAsItselfAndStopsTheRest(): Unit =
    for (readAhead <- Seq(2, 8)) {
      val boom = new IllegalStateException("partition 5 failed")
      val sources = (0 until 8).map {
        case p if p < 5 => finite(p, 4)
        case 5 => endless(5, k => if (k == 3) throw boom)
        case p => endless(p)
      }
      val it = ParallelDrain.inOrder(query(sources), readAhead, 2, new Flag().stop)
      val thrown = thrownBy(it.foreach(_ => ()))
      it.close()
      assertSame(boom, thrown)
      assertEquals("partition 5 failed", thrown.getMessage)
      assertTrue("the waiter's stack rides along", thrown.getSuppressed.nonEmpty)
      nothingRuns(sources)
    }

  /** The same through `eachPartition`, whose tasks take the partitions in
    * order: those before 5 end, those after it have no end and stop. */
  @Test(timeout = 60000) def eachPartitionGivesAFailingPartitionsExceptionAsItself(): Unit = {
    val boom = new ArithmeticException("Integer overflow")
    val sources = (0 until 8).map {
      case p if p < 5 => finite(p, 4)
      case 5 => endless(5, k => if (k == 3) throw boom)
      case p => endless(p)
    }
    val thrown = thrownBy(ParallelDrain.eachPartition(query(sources), 3, new Flag().stop)((_, it) => it.foreach(_ => ())))
    assertSame(boom, thrown)
    assertTrue(thrown.getSuppressed.nonEmpty)
    nothingRuns(sources)
  }

  /** Of two partitions that fail, the lower-numbered's failure is the one
    * thrown, though it failed second: partition 2 fails only once partition
    * 6 has, and partition 6 only once partition 2 is being read, since a
    * partition not yet read when another fails is stopped, which is no
    * failure (under `--config=chaos` a fill of partition 6 could run before
    * partition 2's began, one run in about fifteen). */
  @Test(timeout = 60000) def ofTwoFailuresTheLowerNumberedIsThrown(): Unit = {
    assumeTrue("needs two partitions read at once", Scheduler.parallelism >= 2)
    def run(drain: ExecutedQuery => Unit): Unit = {
      val twoRead = new CountDownLatch(1)
      val sixFailed = new CountDownLatch(1)
      val two = new IllegalArgumentException("partition 2")
      val six = new IllegalArgumentException("partition 6")
      val sources = (0 until 8).map {
        case 2 => endless(2, _ => { twoRead.countDown(); sixFailed.await(30, TimeUnit.SECONDS); throw two })
        case 6 => endless(6, _ => { twoRead.await(30, TimeUnit.SECONDS); sixFailed.countDown(); throw six })
        case p => finite(p, 3)
      }
      val thrown = thrownBy(drain(query(sources)))
      assertSame(two, thrown)
      nothingRuns(sources)
    }
    run(q => { val _ = drained(ParallelDrain.inOrder(q, 8, 1, new Flag().stop)) })
    run(q => { val _ = ParallelDrain.eachPartition(q, 8, new Flag().stop)((_, it) => it.foreach(_ => ())) })
  }

  /** The partitions a failure stops record a stop each, below it where
    * they were being read when it failed: the failure is thrown, not a
    * stop. */
  @Test(timeout = 60000) def aStopNeverReplacesAFailure(): Unit = {
    // Read in order, partitions 0 to 2 have no end: they are stopped.
    val inOrderFailure = new IllegalStateException("partition 3")
    val s1 = (0 until 6).map(p => if (p == 3) endless(3, _ => throw inOrderFailure) else endless(p))
    assertSame(inOrderFailure, thrownBy(drained(ParallelDrain.inOrder(query(s1), 6, 1, new Flag().stop))))
    nothingRuns(s1)
    // Each partition on its own: partition 0, long, is still being read when
    // partition 3 fails, on a pool of two threads.
    val eachFailure = new IllegalStateException("partition 3")
    val s2 = (0 until 6).map {
      case 0 => made(0, 200000)
      case 3 => endless(3, _ => throw eachFailure)
      case p if p < 3 => finite(p, 3)
      case p => endless(p)
    }
    assertSame(eachFailure,
      thrownBy(ParallelDrain.eachPartition(query(s2), 6, new Flag().stop)((_, it) => it.foreach(_ => ()))))
    nothingRuns(s2)
  }

  /** A fatal error reaches the consumer as [[Scheduler.rethrowable]] gives
    * it: wrapped in an `ExecutionException`, so a catch of non-fatal
    * failures still catches it. */
  @Test(timeout = 60000) def anErrorArrivesWrapped(): Unit = {
    val error = new OutOfMemoryError("synthetic")
    def sources: IndexedSeq[Source] = (0 until 4).map {
      case 0 => finite(0, 3)
      case 1 => endless(1, _ => throw error)
      case p => endless(p)
    }
    for (thrown <- Seq(
        thrownBy(drained(ParallelDrain.inOrder(query(sources), 4, 1, new Flag().stop))),
        thrownBy(ParallelDrain.eachPartition(query(sources), 4, new Flag().stop)((_, it) => it.foreach(_ => ()))))) {
      assertTrue(s"$thrown", thrown.isInstanceOf[ExecutionException])
      assertSame(error, thrown.getCause)
    }
  }

  /** A failure of the consumer's own function stops the other partitions
    * as a partition's does. */
  @Test(timeout = 60000) def aFailureOfTheFunctionStopsTheOthers(): Unit = {
    val refused = new IllegalArgumentException("cannot write this value")
    val sources = (0 until 5).map(p => endless(p))
    val thrown = thrownBy(ParallelDrain.eachPartition(query(sources), 3, new Flag().stop) { (p, it) =>
      it.foreach(b => if (p == 0 && b.column(0).getBoxed(0) == 4) throw refused)
    })
    assertSame(refused, thrown)
    nothingRuns(sources)
  }

  // ---- liveness ------------------------------------------------------------------------

  /** A pull that fans out on the pool and waits, as a pairing join's waves
    * or a breaker under a union do. */
  private def nested(k: Int): Unit = {
    val sum = Scheduler.submitAndAwaitAll((1 to 3).map { i =>
      new Callable[Int] {
        def call(): Int = Scheduler.submitAndAwaitAll((1 to 2).map(j => new Callable[Int] { def call(): Int = i * j + k })).sum
      }
    }).sum
    assertTrue(sum > 0)
  }

  /** Partitions whose every pull runs a nested fan-out drain with a window
    * wider than the pool, from this thread and from a pool task. */
  @Test(timeout = 120000) def partitionsThatFanOutDrainFromOutsideAndInsideThePool(): Unit = {
    val readAhead = Scheduler.parallelism + 2
    def sources: IndexedSeq[Source] = (0 until 6).map(p => finite(p, 10, nested))
    assertEquals(60, drained(ParallelDrain.inOrder(query(sources), readAhead, 2, new Flag().stop)).length)
    assertEquals(60, ParallelDrain.eachPartition(query(sources), readAhead, new Flag().stop)((_, it) => it.length).sum)
    val fromThePool = Scheduler.submit(new Callable[Int] {
      def call(): Int =
        drained(ParallelDrain.inOrder(query(sources), readAhead, 2, new Flag().stop)).length +
          ParallelDrain.eachPartition(query(sources), readAhead, new Flag().stop)((_, it) => it.length).sum
    })
    assertEquals(120, fromThePool.await())
  }
}
