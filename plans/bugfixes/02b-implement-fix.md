# Plan 02b: Monitor-free exchange + compensated waits (A + B; C dropped)

> Status: REVERTED IN FULL (2026-07-08) — superseded by
> [`02d`](02d-reland-monitor-free-exchange.md) · Tier: concurrency hardening
> · Gated by [`02a`](02a-reproduce-or-refute-deadlock.md) — which **refuted the
> hard deadlock** on JDK 21 and reshaped this plan.
>
> **Post-mortem banner (read first).** This plan shipped A + B together and then
> REGRESSED K>1 from green (4.3s) into a hard hang: under deep K-shard nesting the
> **A** part — every pool wait wrapped in `ForkJoinPool.managedBlock` — spawned a
> spare-thread storm that wedged the pool. The 02c bisection isolated `managedBlock`
> as the sole culprit (reverting only the managed-blocking waits, keeping B's
> CAS+latch with a plain `latch.await()`, restored green), but the change was then
> reverted **in full** to reach a known-good baseline. HEAD is back on the
> `synchronized` monitor DCL exchange + plain `.get()` work-helping. The body below
> describes the code **as it was briefly written, then removed** — it is NOT the
> current state. The clean re-land of **B alone** (no `managedBlock`) is
> [`02d`](02d-reland-monitor-free-exchange.md).
>
> Parent design doc: [`02-sharded-execution-deadlock.md`](02-sharded-execution-deadlock.md)
> (options A-D). This sub-plan originally proposed A+B+C. After 02a it shipped as
> **A + B only**; C (a growable materialization pool) was dropped as unjustified.
> Read "What changed after 02a" before the build notes below.

## What changed after 02a

02a could not produce a deterministic hang for any plan shape the planner
generates: `ForkJoinTask.get()` work-helping serialises a nested exchange tree
onto one worker, and no acyclic plan yields a monitor cycle. The hazard is a
**throughput cliff + latent liveness footgun**, not a deadlock. Consequences for
this plan:

- **There is no red test to turn green.** Acceptance is a green sharded fuzzer at
  default K (already true on HEAD), the exactly-once concurrency behaviour of the
  new exchange, and a compensation regression guard — not a formerly-hanging test.
- **C is dropped.** A growable pool was justified only by an unbounded-depth
  starvation that never reproduced. Adding an unbounded executor would also let a
  wide sharded plan spawn unbounded CPU-active threads. Not worth it; hold unless
  profiling ever shows the compute-pool churn matters.
- **The `Scheduler` scaladoc is softened, NOT flipped.** 02a proved the old claim
  ("nested submission is safe") is effectively true via work-helping. We keep the
  claim and add the nuance (helping is an FJP implementation detail; managed
  blocking is the belt-and-suspenders; a monitor across a pool wait defeats both).

## The fix, as shipped

### A — compensated waiting in `Scheduler` (global, cheap)

`submitAndAwaitAll` now awaits each task through `awaitCompensated`, which wraps
the wait in a `ForkJoinPool.ManagedBlocker`. A worker that genuinely parks (I/O,
or a latch it cannot help drive) triggers compensation, so nested fan-out cannot
starve the pool even where work-helping does not apply. `awaitLatch` gives the
same compensation for a `CountDownLatch` (used by B). Helpers: `awaitCompensated`
(private), `awaitLatch` (public), `CompensatingGet` (one-shot ManagedBlocker that
captures the result/failure; `block()` never throws so `managedBlock` cannot abort
mid-compensation; the failure is re-raised by `rethrowIfFailed`).

Exception behaviour is preserved exactly: a task failure still surfaces as the
`ExecutionException` from `ForkJoinTask.get`, carrying the task throwable in its
cause chain — NOT unwrapped. (Note: `ForkJoinTask.get` reconstructs a same-class
copy of the task exception for the caller's stack when the task ran on another
thread, so the original sits one level deeper in the chain than a plain
`FutureTask` would put it. The `spill_max_runs` guards match on the message and
catch `ExecutionException | RuntimeException`, so they are unaffected.)

### B — monitor-free `ExchangeExec` materialization (the load-bearing fix)

`ensureMaterialized` replaces double-checked locking with a CAS claim + a
published latch:

```scala
@volatile private var shards: Array[Array[ColumnarBatch]] = null
private val claimed = new AtomicBoolean(false)
private val ready = new CountDownLatch(1)
@volatile private var matFailure: Throwable = null

private def ensureMaterialized(): Array[Array[ColumnarBatch]] = {
  val s = shards
  if (s != null) return s
  if (claimed.compareAndSet(false, true)) {   // exactly one materializer
    try shards = materialize()
    catch { case t: Throwable => matFailure = t }
    finally ready.countDown()                 // monitor never held across materialize()
  } else {
    Scheduler.awaitLatch(ready)               // compensated wait, not a monitor
  }
  if (matFailure != null)
    throw new RuntimeException("ExchangeExec materialization failed", matFailure)
  shards
}
```

The winner is the only thread in `materialize()` (exactly-once, unchanged from
DCL). Concurrent shard-readers await the `ready` latch via managed blocking
instead of piling onto the instance monitor, so a waiting worker stays visible to
the pool — removing the shard-reader serialisation cliff and the latent footgun.
A materialization failure is captured in `matFailure` and re-thrown (wrapped) to
every caller; `materialize()` itself is unchanged and still fans out on
`Scheduler.submitAndAwaitAll` (compute pool).

## Files touched

- `core/Scheduler.scala` — `awaitCompensated` / `awaitLatch` / `CompensatingGet`;
  `submitAndAwaitAll` via `awaitCompensated`; softened object scaladoc; corrected
  the `submitAndAwaitAll` scaladoc's wrong "unwraps ExecutionException" claim.
- `sql/exec/ExchangeExec.scala` — CAS-claim + latch materialization; dropped the
  `synchronized` block and the DCL comment.
- No change to `JoinExec` / `DistinctExec` / `AggregateExec` / `SortExec` — they
  inherit A through `submitAndAwaitAll`.

## Tests added

1. `ExchangeExecTest.materializesExactlyOnceUnderConcurrentCallers` — N (=32) >> K
   threads race one exchange; the child's `execute(p)` runs exactly
   `child.numPartitions` times (materialize ran once), never N × that.
2. `ExchangeExecTest.materializationFailurePropagatesToEveryConcurrentCaller` — a
   throwing child makes every concurrent caller observe the wrapped failure
   (original cause preserved in the chain), materialize attempted once.
3. `SchedulerTest` (new `//src/test/scala/com/transformer/core:scheduler_test`) —
   `awaitLatch` compensation keeps a fully-parked pool live (P waiters + 1 opener);
   `submitAndAwaitAll` survives fan-out deeper than the pool; and it rethrows a
   task failure as `ExecutionException` (not unwrapped). This promotes 02a's H4
   probe to a permanent guard now that the wait path has changed.
4. Non-sharded regression guard — the existing collapsing-breaker + spill suites
   stay green (they exercise the new `awaitCompensated`); the `spill_max_runs`
   exception tests pass unchanged.

## Acceptance (met)

- `bazel test //...` green, including the new `scheduler_test` and exchange
  concurrency tests, and the unchanged spill guards.
- Exactly-once + failure-propagation verified under N concurrent callers.
- jaffle_shop 15/15 (non-sharded path; only `submitAndAwaitAll`'s wrap changed),
  wall-time neutral — the compensation change is ~free when nesting is shallow.
- Un-gating the sharded fuzzer (remove `shard_count=1`) and the multi-shard
  campaign remain in [`02c`](02c-ungate-validate-document.md); 02a recommends
  keeping the pin for campaign speed until that runs.
