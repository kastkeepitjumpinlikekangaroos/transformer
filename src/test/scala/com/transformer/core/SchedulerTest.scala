package com.transformer.core

import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.{Callable, ConcurrentLinkedQueue, CountDownLatch, TimeUnit, TimeoutException}
import java.util.concurrent.atomic.AtomicBoolean

/** Mechanism guard for the liveness assumption nested execution rests on:
  * [[Scheduler.submitAndAwaitAll]] awaits its sub-tasks with
  * `ForkJoinTask.get()`, which from a pool worker work-HELPS — the awaiting
  * worker steals and runs the very sub-tasks it awaits, descending a nested
  * fan-out depth-first on its own stack — so a bounded pool survives task
  * TREES arbitrarily deeper than its thread count. That helping is an FJP
  * implementation detail, not a contract: the identical shape awaited through
  * a non-helping `CountDownLatch.await()` genuinely parks the workers and
  * wedges the pool. Both sides are pinned here so a future refactor that
  * swaps `submitAndAwaitAll` off `.get()` (or onto `ForkJoinPool.managedBlock`
  * — the reverted plans/bugfixes/02b regression: its compensation spawns a
  * spare-thread storm under deep nesting) fails this suite loudly instead of
  * wedging production.
  *
  * This pins the tree-shaped mechanism only, not a blanket liveness
  * guarantee: helpJoin also runs tasks from stealers' queues that need not be
  * descendants of the awaited task, and combined with an exactly-once
  * exchange gate that non-descendant inlining can close a real deadlock cycle
  * at K>1 sharding — see docs/gotchas.md ("Sharded execution at K>1") and
  * plans/bugfixes/02e.
  *
  * Runs in its own JVM with `-Dtransformer.scheduler.parallelism=2`
  * ([[Scheduler.parallelism]] is a class-load `val`): 2 threads is the
  * smallest pool where "every worker parked on a non-helping wait while the
  * queue holds runnable tasks" is reachable, and the depth-5 / fan-4 shape
  * (1364 tasks, 1024 leaves) dwarfs it.
  */
class SchedulerTest {

  private val Depth = 5
  private val Fan = 4

  /** Guard that the BUILD `jvm_flags` actually arrived. The wedge below would
    * reproduce on a default-sized pool too, but the documented regime is the
    * 2-thread one; fail loudly if the flag is dropped rather than silently
    * testing something else. Mirrors `shardingGateIsActive` in the sharded
    * fuzz target. */
  @Test def parallelismPinnedTo2ByJvmFlags(): Unit =
    assertEquals(2, Scheduler.parallelism)

  @Test(timeout = 30000) def nestedFanOutDeeperThanPoolCompletesViaGetWorkHelping(): Unit = {
    def spawn(depth: Int): Int =
      if (depth == 0) 1
      else Scheduler.submitAndAwaitAll(
        (0 until Fan).map(_ => new Callable[Int] { def call(): Int = spawn(depth - 1) })).sum
    assertEquals("every leaf of the nested fan-out reached", 1024, spawn(Depth))
  }

  @Test(timeout = 60000) def nestedFanOutWedgesWithoutWorkHelping(): Unit = {
    // Identical shape, but each parent awaits its children through a
    // CountDownLatch — a wait `.get()` work-helping cannot see. Both workers
    // park on children latches while every runnable child sits in the queue:
    // no thread is left to make progress, so the root never completes.
    val abort = new AtomicBoolean(false)
    val latches = new ConcurrentLinkedQueue[CountDownLatch]
    val rootDone = new CountDownLatch(1)

    def spawn(depth: Int): Unit = {
      if (abort.get || depth == 0) return
      val childrenDone = new CountDownLatch(Fan)
      latches.add(childrenDone)
      var i = 0
      while (i < Fan) {
        Scheduler.submit(new Callable[Unit] {
          def call(): Unit = try spawn(depth - 1) finally childrenDone.countDown()
        })
        i += 1
      }
      childrenDone.await() // non-helping park: the hazard under guard
    }

    Scheduler.submit(new Callable[Unit] {
      def call(): Unit = try spawn(Depth) finally rootDone.countDown()
    })

    var wedged: TimeoutException = null
    try {
      if (!rootDone.await(5, TimeUnit.SECONDS))
        throw new TimeoutException("2-thread pool wedged under non-helping nested waits")
    } catch { case t: TimeoutException => wedged = t }
    assertNotNull("expected the non-helping wait shape to wedge the pool; if " +
      "this now completes, the FJP wait/helping semantics changed", wedged)

    // Unwedge before the next test in this JVM: stop new latches from being
    // created, then force every parked parent's latch open until the tree
    // drains. A task mid-`spawn` can add a latch after a drain pass, hence
    // the loop-until-root-done.
    abort.set(true)
    var drained = false
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    while (!drained && System.nanoTime() < deadline) {
      var l = latches.poll()
      while (l != null) {
        while (l.getCount > 0) l.countDown()
        l = latches.poll()
      }
      drained = rootDone.await(50, TimeUnit.MILLISECONDS)
    }
    assertTrue("pool failed to drain after force-releasing the latches", drained)
  }
}
