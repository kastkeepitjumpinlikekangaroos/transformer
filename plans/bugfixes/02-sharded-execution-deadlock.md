# Plan 02: Sharded execution deadlocks at K>1 (nested breakers starve the pool)

> Status: RE-SCOPED — NOT a hard deadlock on JDK 21 (see 02a Resolution) · Tier: perf + latent-footgun (was: liveness bug) · Risk: medium
>
> Surfaced by the metamorphic fuzzer's sharded cross-JVM target
> (`ShardedModeFuzzTest`). Currently gated around by pinning
> `transformer.scheduler.shard_count=1`, which keeps each breaker's per-shard
> fan-out to a single task and so never trips the deep nesting.
>
> **02a (2026-07-08) refuted the hard-deadlock premise on JDK 21.** The fuzzer is
> green with the pin removed at default K and at `parallelism=2, shard_count=8,
> 1500 seeds`; `ForkJoinTask.get()` work-helping serialises breaker
> materialisation onto one worker and I/O-blocked workers are compensated, so
> nothing hangs. The residual is a throughput cliff (monitor serialisation) +
> compensation-thread churn, and a latent footgun if a non-helping wait is ever
> introduced over these breakers. Treat the fix options below as **performance +
> hardening**, not a deadlock fix: A + B are still worthwhile, **C is not
> justified by any observed hang**. Mechanism #2's premise ("`.get()` parks
> without compensation") is WRONG — do not act on it. Read
> [`02a-reproduce-or-refute-deadlock.md`](02a-reproduce-or-refute-deadlock.md)
> "Resolution" before starting 02b.
>
> This file remains the canonical **design doc** (root cause, fix options A-D,
> risks). The work is now split across three follow-up sub-plans:
>
> - [`02a-reproduce-or-refute-deadlock.md`](02a-reproduce-or-refute-deadlock.md)
>   — the gating investigation (see the finding below; the simple repro the
>   design assumed does **not** hang on JDK 21).
> - [`02b-implement-fix.md`](02b-implement-fix.md) — the A+B+C fix, with a
>   refined surgical design for C.
> - [`02c-ungate-validate-document.md`](02c-ungate-validate-document.md) — remove
>   the `shard_count=1` pin, validate, update docs.

## Session 1 finding (2026-06-28): a deep tree-shaped sharded plan does NOT hang on HEAD

Phase 1 of this plan — "land a deterministic deep-plan timeout test that hangs
on HEAD" — was attempted and **the premise did not hold for tree-shaped plans**.
A deep sharded plan was constructed directly from the exec classes — six
`DistinctExec(ExchangeExec(...))` layers (six nested exchanges), `numShards = 4`,
over a two-partition in-memory scan — and drained on a JVM pinned to
`-Dtransformer.scheduler.parallelism=2`. It was drained three ways:

1. concurrent external threads, one per output shard;
2. each output shard drained as a task submitted to `Scheduler.pool`;
3. all shards drained sequentially inside one `Scheduler.pool` task.

**All three completed in ~0.7s. None deadlocked.** `depth × fan-out = 6 × 4 = 24`
greatly exceeds `P = 2`, so by this plan's stated threshold it should have hung.

Root cause of the non-reproduction: **`ForkJoinPool` work-helping.** When a
worker calls `ForkJoinTask.get()` (what `Scheduler.submitAndAwaitAll` uses) it
does not merely park — it steals and runs the very sub-tasks it is awaiting,
descending the breaker tree depth-first on a single worker's stack. So one
worker alone materializes an arbitrarily deep *tree* of exchanges. The
monitor mechanism (§ "Root cause" #1 below) still serializes concurrent
shard-readers of one exchange, but it does **not** hard-deadlock a tree: the
worker holding the deepest exchange monitor always has an independent subtree it
can help-steal to completion, then releases the monitor for the next waiter. The
scaladoc this plan calls "wrong" is, for tree plans on JDK 21, effectively
right.

Consequence: **before the fix can be proven, a genuine deterministic repro must
be found, or the hazard re-characterised.** The simple "deep plan + tiny pool +
timeout" regression this plan prescribed is green on HEAD and would be a false
negative. The live hypotheses (DAG / shared-exchange monitor cycles;
non-helping external drain threads pinning monitors; the full fuzzer at default
pool size and default K; `.get()` vs `.join()` helping semantics) are
enumerated and prioritised in
[`02a-reproduce-or-refute-deadlock.md`](02a-reproduce-or-refute-deadlock.md).
The A+B+C fix in [`02b`](02b-implement-fix.md) is still worth doing as a
defensive correctness measure regardless — but its acceptance test depends on
02a delivering a red repro first.

## Summary

With sharding forced on (`MinShardableSize=1`, `BroadcastBuildThreshold=1`) and
the shard count at its default (`= Scheduler.parallelism`), the fuzzer hangs —
not crashes. Every pipeline breaker (`ExchangeExec`, `HashJoinExec`,
`DistinctExec`, `HashAggregateExec`) materializes by **blocking** on the shared,
bounded `Scheduler.pool` while awaiting per-partition sub-tasks. Sharded plans
nest breakers deeply (an exchange over a join over an exchange over a
distinct…), so the blocking waits stack up faster than the pool has threads to
satisfy them: workers park awaiting sub-tasks that then have no thread left to
run on. Observed at the limit: 14 workers parked on two exchange monitors at 0%
CPU.

Sharding is **off in the shipping config** (`MinShardableSize = Long.MaxValue`,
`BroadcastBuildThreshold = 1M` — see
[plans/perf/04](../perf/04-hash-partition-breakers.md) /
[05](../perf/05-join-planner.md)), so nothing users run reaches this today. But
it makes K>1 sharded execution unusable, and it is a latent hazard in the
blocking-fan-out pattern that the whole engine shares.

## Why it hangs instead of failing

A `ForkJoinPool` of `P = 2 × cores` threads is the *only* place breaker
materialization tasks run. Breakers nest, and each level **blocks a pool thread
while holding it**, waiting for the level below:

```
ExchangeExec.execute(s)
  └─ materialize(): submitAndAwaitAll(shardChildPartition × nChildParts)  [blocks a worker]
        └─ child = HashJoinExec.execute(p)
              └─ buildSideAcrossAllPartitions(): submitAndAwaitAll(...)   [blocks another worker]
                    └─ child = ExchangeExec.execute(p)
                          └─ materialize(): submitAndAwaitAll(...)        [blocks another worker]
                                └─ ...
```

Each `submitAndAwaitAll` parks the calling worker until its sub-tasks finish —
but those sub-tasks need free pool threads to run, and the parked workers are
holding them. Once `depth × fan-out > P`, there is no thread left to run the
leaf tasks the whole stack is waiting on. The pool is deadlocked (or
livelocked, depending on timing).

## Root cause — two compounding mechanisms

Both are documented in `docs/gotchas.md`; this plan is the fix.

### 1. A JVM monitor held across a pool-blocking call (`ExchangeExec`)

`ExchangeExec.ensureMaterialized` (ExchangeExec.scala:88-95) is the standard
double-checked-locking lazy init — but the `synchronized` block spans the
`materialize()` call, which blocks on `Scheduler.pool`:

```scala
private def ensureMaterialized(): Array[Array[ColumnarBatch]] = {
  val s = shards
  if (s != null) return s
  synchronized {
    if (shards == null) shards = materialize()  // <-- blocks on the pool, monitor held
    shards
  }
}
```

When several workers call `execute(s)` for different shards of the **same**
exchange (the normal case — K shards, K consumers), the first acquires the
monitor and blocks inside `materialize()`; the rest block **entering** the
monitor. A thread blocked entering a Java monitor is invisible to the
`ForkJoinPool`: it can't help run tasks and it can't be compensated for. So the
holder — itself blocked on the pool — has even fewer threads to draw on, while a
pile of workers sit uselessly on the monitor.

`ExchangeExec` is the only breaker that holds a monitor across the wait
(`DistinctExec`, `AggregateExec`, `SortExec` call `submitAndAwaitAll` but not
under a spanning `synchronized`). So this mechanism is specific to the exchange,
which makes it the highest-leverage single fix.

### 2. Nested blocking on a bounded pool without compensation (`Scheduler`)

`Scheduler.submitAndAwaitAll` (Scheduler.scala:96-99) waits with a plain
`ForkJoinTask.get()`:

```scala
def submitAndAwaitAll[T](tasks: Seq[Callable[T]]): IndexedSeq[T] = {
  val ftasks = tasks.iterator.map(pool.submit(_)).toIndexedSeq
  ftasks.map(_.get())
}
```

`ForkJoinTask.get()` from inside a worker does work-*helping* but **not managed
compensation**: when the awaited tasks are themselves blocked deeper, helping
bottoms out and the worker simply parks — the pool does not start a spare thread
to maintain its target parallelism. Deep sharded fan-out parks every worker.

Note the `Scheduler` scaladoc (Scheduler.scala:20-23) actively claims the
opposite — *"Nested submission is safe because `ForkJoinTask.get`/`.join`
cooperates with the pool — a worker blocked waiting for its child tasks won't
deadlock the scheduler."* That comment is **wrong** at depth and should be
corrected as part of this fix; it is what made the pattern look safe.

## Proposed fix

Ordered least-to-most invasive. Recommendation: land **A + B** as the immediate
correctness fix (kills both documented mechanisms for shallow/moderate plans),
then **C** as the structural fix that makes arbitrary nesting safe. **D** is a
re-architecture, out of scope here.

### A. Compensated waiting in `Scheduler.submitAndAwaitAll`

Wrap the join in a `ForkJoinPool.ManagedBlocker` so the pool compensates
(activates/creates a spare worker) when a thread blocks:

```scala
def submitAndAwaitAll[T](tasks: Seq[Callable[T]]): IndexedSeq[T] = {
  val ftasks = tasks.iterator.map(pool.submit(_)).toIndexedSeq
  ftasks.map(awaitCompensated)   // ManagedBlocker around task.get()
}
```

Cheap, globally beneficial (every nested fan-out in the engine gets it), low
risk. **But partial**: FJP throttles and bounds compensation threads, so at full
shard count deep plans still out-nest it. Necessary, not sufficient.

### B. Don't hold the exchange monitor across materialization

Restructure `ExchangeExec` so the blocking `materialize()` runs **outside** any
monitor, and concurrent callers await a published result instead of contending
on a lock. A one-shot latch / `CompletableFuture` does it:

- The first caller to arrive CASes a `materializing` flag, runs `materialize()`
  without holding a monitor, then completes the future.
- Concurrent callers await the future via managed blocking (compensated),
  *not* by blocking on a monitor.

This removes mechanism 1 entirely: no waiter is ever invisible to the pool.

### C. A dedicated, growable executor for materialization waits (structural)

The deeper problem is that **the blocking coordination runs on the same bounded
pool that must run the work being awaited**, and the number of simultaneous
blocked coordinators scales with `plan depth × shard fan-out`, which can exceed
`P`. Decouple them:

- Keep `Scheduler.pool` (bounded, `2×cores`) for **leaf CPU work** — decode,
  filter, route, the per-partition `shardChildPartition` / `collectBuildPartition`
  bodies.
- Run the **breaker materialization coordination** (the part that submits
  sub-tasks and blocks awaiting them) on a separate, *growable* executor (a
  cached thread pool, daemon threads). Blocked coordinators there never consume
  a compute thread, so a thread is always free to make progress on the leaves.

This bounds compute-pool occupancy to actual CPU tasks and lets nesting depth
grow without starving. It is the durable fix; A and B make the common cases safe
without it.

> A cleaner long-term alternative to C is **stage-by-stage (bottom-up)
> materialization**: walk the plan, materialize each breaker fully (caching its
> result) before any parent begins, so no parent ever blocks on a child that's
> still contending for threads. That is how a real staged engine avoids this
> entirely, and it overlaps with the sharding/exchange work in
> [plans/perf/04](../perf/04-hash-partition-breakers.md). Treat it as the
> "do it properly" path if sharding becomes a shipping default.

### D. Non-blocking / push-based breakers (out of scope)

Event-driven materialization that never parks a thread. Biggest change; note it
exists, don't do it here.

## Files to touch

- **Modified**: `core/Scheduler.scala` — `submitAndAwaitAll` via
  `ManagedBlocker` (A); fix the misleading scaladoc; if doing C, add the
  growable materialization executor here as a second named pool.
- **Modified**: `sql/exec/ExchangeExec.scala` — `ensureMaterialized` no longer
  holds a monitor across `materialize()` (B).
- **Modified (C)**: `sql/exec/JoinExec.scala`, `DistinctExec.scala`,
  `AggregateExec.scala` — route the materialization *await* through the growable
  executor; keep leaf fan-out on `Scheduler.pool`.
- **Modified**: `src/test/.../fuzz/ShardedModeFuzzTest.scala` — remove (or raise
  past 1) the `transformer.scheduler.shard_count=1` pin once the fix lands; the
  `jvm_flags` and the explanatory comment (lines ~26-42) update accordingly.
- **Modified**: `docs/gotchas.md` — the "Sharded execution deadlocks at K>1"
  entry moves from known-bug to fixed (or to a bounded residual note if only
  A+B land).
- **Modified**: `docs/architecture.md` — the parallel-execution / scheduler
  section should describe the compute-vs-coordination split (if C) and the
  "never hold a monitor across a `Scheduler.pool` wait" rule.
- **Modified**: `docs/conventions.md` — codify that rule as a convention.

## Tests / validation

The hard part of a deadlock is making it **deterministic and CI-able**. Two
levers:

1. **Shrink the pool to force the bug.** Set
   `transformer.scheduler.parallelism=2` (and a shard count > 2) so the
   `depth × fan-out > P` threshold is trivially crossed. On the current code a
   deep sharded plan hangs; after the fix it completes.
2. **A deep-plan regression with a timeout.** Construct a deliberately deep
   sharded plan — exchange over join over exchange over distinct over exchange …
   (5+ breakers) — over tiny inputs, and assert it produces the right multiset
   **within a few seconds** (JUnit `@Test(timeout = …)` or an explicit watchdog).
   This test must *fail by timing out on the current code* and pass after — that
   is the proof. Put it in a new `SchedulerNestingTest` /
   `ExchangeExecDeadlockTest` under `sql/exec`.

Then the campaign-level gate:

3. **Un-pin the sharded fuzzer.** Remove `shard_count=1` from
   `ShardedModeFuzzTest` (or add a K=`parallelism` variant) and run the
   metamorphic relations + mode agreement under deep generated sharded plans
   without hanging. This is the same fuzzer that found the bug, now allowed to
   exercise the multi-shard nesting it was held back from.

Plus the standard gates: `bazel test //...` green, jaffle_shop 15/15 (sharding
off there, so it must remain unaffected — a guard that A/B/C didn't regress the
default collapsing path).

## Risks

1. **`ManagedBlocker` compensation has its own bounds.** A alone can still
   starve very deep plans (the note's "only a partial mitigation"). Mitigation:
   don't ship A alone as "fixed" — pair with B, and C for the structural
   guarantee. Be honest in `docs/gotchas.md` about what residual depth, if any,
   remains.
2. **A growable executor (C) can over-subscribe threads** under pathological
   nesting (many coordinators → many threads). Mitigation: coordinator threads
   are nearly idle (they block, they don't compute), so OS-thread count, not CPU,
   is the cost; cap it generously and document. The compute pool stays bounded.
3. **Re-entrancy / double-materialization in the new `ExchangeExec`.** The
   monitor today guarantees exactly-once materialization. The latch/future
   replacement must preserve that (CAS the claim; everyone else awaits the same
   future). Mitigation: a concurrency unit test that fans N threads at one
   exchange and asserts `materialize()` ran once.
4. **Regressing the default (non-sharded) path.** All of jaffle_shop and the
   collapsing join/aggregate paths run through `submitAndAwaitAll`. Mitigation:
   the e2e gate plus a before/after wall-time check on a non-sharded job
   (compensation/await changes must be ~free when nesting is shallow).
5. **Heisenbug reproducibility.** Deadlocks are timing-sensitive. Mitigation:
   the small-pool + deep-plan + timeout test makes it deterministic; rely on
   that rather than the campaign for the regression signal.

## Suggested phases

1. **Phase 1 — make it deterministic.** Land the small-pool deep-plan timeout
   test first; confirm it hangs/fails on `HEAD`. Now there's a red test to fix
   against.
2. **Phase 2 — A + B.** `ManagedBlocker` in `submitAndAwaitAll`; monitor-free
   `ExchangeExec` materialization; fix the `Scheduler` scaladoc. Re-run Phase 1's
   test — it should pass for moderate depth. Establish how deep A+B survives.
3. **Phase 3 — C (structural).** Split compute vs. materialization-await onto
   separate pools; the deep-plan test passes at arbitrary depth on a 2-thread
   compute pool.
4. **Phase 4 — un-gate + docs.** Remove the `shard_count=1` pin, run the sharded
   campaign at default K, update `docs/gotchas.md` / `docs/architecture.md` /
   `docs/conventions.md`.

If time-boxed: Phases 1-2 make K>1 *much* safer and remove both named
mechanisms; Phase 3 is what lets you delete the `shard_count=1` pin with
confidence. Don't claim the gotchas entry resolved until Phase 3 lands or the
residual depth limit is measured and documented.

## Launch prompt

```
Read plans/bugfixes/02-sharded-execution-deadlock.md and implement it. Use max
effort. This is an architectural concurrency fix — expect to think hard about
the ForkJoinPool model, not just patch lines.

The bug: with sharding forced on at K>1, pipeline breakers (ExchangeExec,
HashJoinExec, DistinctExec, HashAggregateExec) materialize by BLOCKING on the
shared bounded Scheduler.pool while awaiting per-partition sub-tasks. Sharded
plans nest breakers deeply, so blocking waits stack faster than the pool has
threads: every worker parks awaiting sub-tasks that have no thread to run on.
Two named mechanisms: (1) ExchangeExec.ensureMaterialized holds its monitor
across the pool-blocking materialize(), so blocked waiters are invisible to the
pool; (2) Scheduler.submitAndAwaitAll uses ForkJoinTask.get() which parks
WITHOUT compensation. The Scheduler scaladoc claims this is safe — it is wrong
and must be corrected.

FIRST, land a deterministic regression: a deep sharded plan (5+ nested
breakers) over tiny inputs with transformer.scheduler.parallelism=2 and a JUnit
timeout. It must HANG/FAIL on HEAD. That is your red test.

THEN fix: (A) wrap submitAndAwaitAll's wait in a ForkJoinPool.ManagedBlocker;
(B) restructure ExchangeExec so materialize() runs outside any monitor and
concurrent callers await a published future via managed blocking; (C) the
structural fix — run breaker materialization WAITS on a separate growable
executor so blocked coordinators never consume compute-pool threads, keeping
leaf CPU work on Scheduler.pool. A+B are the immediate fix; C is what makes
arbitrary nesting safe.

Acceptance: the deep-plan timeout test passes on a 2-thread compute pool, and
the sharded fuzzer runs with the shard_count=1 pin REMOVED (ShardedModeFuzzTest)
without hanging at default K. Honor CLAUDE.md: bazel test //... green,
jaffle_shop 15/15 (sharding is off there — prove you didn't regress the default
collapsing path), no new deps. Add a concurrency test asserting ExchangeExec
materializes exactly once under N concurrent callers. Update docs/gotchas.md,
docs/architecture.md, docs/conventions.md.

Stop and ask before: pursuing option D (push-based breakers) or a full
stage-by-stage execution rewrite — those are larger than this bug fix. If only
A+B prove tractable in scope, measure and DOCUMENT the residual depth limit
rather than claiming the gotchas entry fully resolved.
```
