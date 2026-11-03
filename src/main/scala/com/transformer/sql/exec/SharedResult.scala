package com.transformer.sql.exec

import com.transformer.core.{Chaos, Scheduler}

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** An operator holding a result computed once and read by every partition
  * that runs it: an [[ExchangeExec]]'s shards, a broadcast [[HashJoinExec]]'s
  * build side. [[PhysicalPlanner.preMaterialize]] publishes each one,
  * children first, before any consumer of the plan exists, so no pool task
  * ever waits for one to be ready: the K>1 liveness rule
  * (plans/bugfixes/02f), which a lazily shared result read from pool tasks
  * breaks (docs/conventions.md, "Never let a pool task wait on a
  * lazily-shared breaker result"). An operator that holds a result of this
  * kind extends this trait, keeps the result in a [[SharedOnce]], and needs a
  * branch in `PhysicalPlanner.childrenOf` so the pass reaches what lies
  * under it. */
private[exec] trait SharedResult { this: PhysicalPlan =>

  /** Compute and publish the shared result now, if this operator holds one
    * as it is planned; the pass calls it on the drain's thread. A second call
    * does nothing. */
  private[exec] def materializeNow(): Unit
}

/** A value computed once and read by many threads: the publication every
  * [[SharedResult]] holds its result in. The engine's pass computes it before
  * any reader exists ([[PhysicalPlanner.preMaterialize]]), so `get` is then a
  * read of a volatile field.
  *
  * A plan run without the pass (one built in code, a test) reaches the lazy
  * fallback: the first caller to win a CAS claim computes the value with no
  * JVM monitor held and publishes it through a volatile field; every other
  * caller parks on a latch until it does, and all wake together. The wait is
  * a plain, deliberately UNCOMPENSATED `ready.await()`: do not wrap it in
  * `ForkJoinPool.managedBlock`, whose compensation spawned a spare-thread
  * storm that wedged the pool under deep K-shard nesting (the
  * plans/bugfixes/02b regression). Nor make it a Scala `lazy val` or a
  * `synchronized` block: either holds the operator's monitor across the
  * computation's pool fan-out, which work-stealing cannot see
  * (plans/bugfixes/02d).
  *
  * What the latch does NOT fix, and why a lazily shared result must never be
  * read from pool tasks: `ForkJoinTask.get()`'s helpJoin runs tasks from
  * stealers' queues that need not be descendants of the awaited task, so
  * while the claimer parks in its computation's fan-out, a CONSUMER of this
  * very value can be inlined onto a stack:
  *
  *   - **Onto the claimer's own stack (escaped here).** The guest would lose
  *     the claim to its carrier thread and park on a latch only that thread
  *     can open, a self-deadlock the old reentrant `synchronized` monitor
  *     avoided by computing twice. `get` sees the claimer is the calling
  *     thread and computes a private, unpublished copy instead (duplicate
  *     work, live). The computation reads only the operator's children, which
  *     cannot read the operator back (a plan is acyclic), so the copy cannot
  *     park here again.
  *   - **Onto another worker's stack (NOT escapable here).** A guest inlined
  *     beneath a task of the computation on a different worker parks as an
  *     ordinary waiter, closing a claimer -> task -> inlined consumer ->
  *     latch cycle no choice of monitor or latch avoids: reproduced as a
  *     permanent wedge at fuzz-campaign scale and by
  *     `exchange_deadlock_stress_test` under `STRESS_LAZY=1` (plans/bugfixes/
  *     02e, 02f). Engine execution is immune because the pass leaves nothing
  *     for a consumer to wait on; that pass, not this latch, is the liveness
  *     fix.
  *
  * A failure of the computation is kept and thrown to the claimer and every
  * waiter as itself ([[Scheduler.rethrowable]]); the claim stays taken, so a
  * failed computation is never retried. The claim and the publish are chaos
  * points ([[Chaos.SharedClaim]], [[Chaos.SharedPublish]]). The tree-shaped
  * work-helping everything else relies on is guarded by
  * `//src/test/scala/com/transformer/core:scheduler_test`. */
private[exec] final class SharedOnce[A <: AnyRef](compute: () => A) {
  @volatile private var value: A = null.asInstanceOf[A]
  private val claimed = new AtomicBoolean(false)
  @volatile private var claimer: Thread = null
  private val ready = new CountDownLatch(1)
  @volatile private var failure: Throwable = null

  /** The value: computed by this call when none has been, waited for while
    * another thread computes it, and read otherwise. */
  def get(): A = {
    val v = value
    if (v != null) return v
    Chaos.at(Chaos.SharedClaim)
    if (claimed.compareAndSet(false, true)) { // exactly one publishing computation
      claimer = Thread.currentThread()
      try value = compute()
      catch { case t: Throwable => failure = t }
      finally {
        claimer = null
        Chaos.at(Chaos.SharedPublish)
        ready.countDown()
      }
    } else if (claimer eq Thread.currentThread()) {
      // A consumer that helpJoin inlined beneath the claimer's own frames,
      // while the claimer waits in the computation's fan-out: parking it on
      // `ready` would deadlock the thread against itself.
      return compute()
    } else {
      ready.await()
    }
    if (failure != null) throw Scheduler.rethrowable(failure)
    value
  }
}
