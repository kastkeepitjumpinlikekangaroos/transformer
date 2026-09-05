# Plan 02d: Re-land the monitor-free exchange (B alone) + guard the liveness assumption

> Status: DONE (2026-07-23) — landed, with the premise overturned mid-landing.
> **Read [`02e`](02e-campaign-deadlock-reproduced.md) before trusting this
> plan's liveness reasoning.** The pre-change baseline campaign for the
> wall-time gate (FUZZ_SEEDS=20000, K=4) hard-deadlocked HEAD, overturning
> 02a's refutation at campaign scale; the "plain `ready.await()` is live / a
> loser is a pure leaf in the wait graph" argument below is WRONG (helpJoin
> can inline a consumer of an exchange beneath its own materialization
> frames, on another worker's stack — the open cross-thread cycle — or on the
> winner's own stack — a self-deadlock the old reentrant monitor masked and
> the shipped code escapes via a `claimer` reentrancy check + private
> materialize). B shipped as a **perf + convention fix only**; the wall-time
> gate was dropped (the baseline never completes); Phase 3 / parent Option D
> is now backed by a reproduced hang, not a hypothesis.
>
> Parent design doc: [`02-sharded-execution-deadlock.md`](02-sharded-execution-deadlock.md)
> (options A-D). Prereqs read: [`02a`](02a-reproduce-or-refute-deadlock.md)
> "Resolution" (the refutation + H4 mechanism proof) and
> [`02b`](02b-implement-fix.md) (the REVERTED attempt — see its corrected banner).

## Why this plan exists

The 02b attempt bundled two changes and was reverted **in full** when it
regressed K>1 from green into a hard hang:

- **A — compensated waits** (`submitAndAwaitAll` + the exchange loser-wait wrapped
  in `ForkJoinPool.managedBlock`). *This was the culprit.* Under deep K-shard
  nesting the compensation spawned a spare-thread storm that wedged the pool.
- **B — monitor-free `ExchangeExec`** (replace the `synchronized` double-checked
  lock with a CAS-claim + `CountDownLatch`). *This was fine.*

The 02c bisection is explicit (see `docs/gotchas.md`): *"reverting only the
managed-blocking waits — back to plain `.get()` / `latch.await()` — restores
green, isolating `managedBlock` as the sole culprit."* That green config is
exactly **B with a plain, uncompensated `latch.await()` and `submitAndAwaitAll`
on plain `.get()`**. The full revert threw B out with A to reach a known-good
baseline fast; B was never the problem.

Net today (HEAD + the uncommitted 02c un-gate): `ExchangeExec.ensureMaterialized`
is back to `synchronized { ... materialize() ... }` — a JVM monitor held across a
`Scheduler.pool`-blocking call. That is (a) the shard-reader serialisation
throughput cliff 02a measured, and (b) a direct violation of the concurrency
convention this very campaign added to `docs/conventions.md` ("never hold a JVM
monitor across a `Scheduler.pool`-blocking call"). This plan lands B alone to
resolve both, and pins the load-bearing liveness assumption with a regression
guard so the latent footgun can't silently return.

## Scope decisions (locked by 02a's evidence — do not relitigate)

- **A is NOT re-landed.** No `managedBlock`, no `awaitCompensated`, no
  `awaitLatch`. `submitAndAwaitAll` stays on plain `.get()` work-helping.
- **C (growable materialization pool) is NOT built.** No observed hang justifies
  it, and it would let a wide sharded plan spawn unbounded CPU-active threads.
  Stays dropped; reopen only with a profile-backed starvation.
- **The architectural footgun elimination (parent Option D) is deferred**, gated
  on sharding ever becoming a shipping default — see Phase 3.

## Phase 0 — land the uncommitted 02c un-gate first

The 02c deliverable (un-gate `shard_count` 1→4 + doc reconciliation) is done but
sitting **uncommitted** in the working tree — 6 tracked files:

```
docs/architecture.md  docs/conventions.md  docs/gotchas.md  docs/testing.md
src/test/scala/com/transformer/fuzz/BUILD.bazel
src/test/scala/com/transformer/fuzz/ShardedModeFuzzTest.scala
```

Commit these on their own first (the K=4 fuzzer is already green — verified
2026-07-23, `sharded_mode_fuzz_test` PASSED 5.0s). This is the "restore fuzzer
coverage" step; keep it as a separate commit from the engine change below so a
bisect can tell the coverage restoration from the fix.

## Phase 1 — B alone: monitor-free `ExchangeExec` materialization

`src/main/scala/com/transformer/sql/exec/ExchangeExec.scala`, replace the
`synchronized` double-checked lock (lines ~76-95) with a CAS-claim + published
latch. The loser-wait is a **plain** `ready.await()` — NOT compensated:

```scala
@volatile private var shards: Array[Array[ColumnarBatch]] = null
private val claimed = new java.util.concurrent.atomic.AtomicBoolean(false)
private val ready   = new java.util.concurrent.CountDownLatch(1)
@volatile private var matFailure: Throwable = null

private def ensureMaterialized(): Array[Array[ColumnarBatch]] = {
  val s = shards
  if (s != null) return s
  if (claimed.compareAndSet(false, true)) {   // exactly one materializer
    try shards = materialize()
    catch { case t: Throwable => matFailure = t }
    finally ready.countDown()                 // monitor never held across materialize()
  } else {
    ready.await()                             // plain wait — NOT managedBlock (that was A)
  }
  if (matFailure != null)
    throw new RuntimeException("ExchangeExec materialization failed", matFailure)
  shards
}
```

Update the class comment: it currently documents "double-checked-locking-style
lazy init"; make it describe the CAS-claim + latch, and state explicitly that the
loser-wait is intentionally uncompensated (compensation was the 02b regression).

### Why plain `ready.await()` is live (the argument to put in the comment)

The losers are threads that lost the CAS; they wait only for the winner to
publish `shards`. The winner runs `materialize()` on its own stack and its
`submitAndAwaitAll` fan-out uses `.get()` work-helping, so the winner descends
and completes its whole subtree without needing any loser's thread. A loser is a
pure leaf in the wait graph — no thread that holds "winner" responsibility ever
parks (a thread is winner XOR loser per exchange, and a winner is actively
running, not parked). So even if every-worker-but-one parks as a loser, the one
winner still finishes and `countDown`s. No cycle is possible (monitor/latch
acquisition follows the plan's acyclic parent→child topology). This removes the
throughput cliff (losers wake together on `countDown` instead of serialising
through the monitor) without reintroducing A's compensation.

## Phase 2 — pin the assumptions with regression guards

Resurrect the two impl-agnostic exchange tests 02b added (reverted with it) and
promote 02a's H4 mechanism probe. Put the exchange tests in the existing
`exchange_exec_test` target; the mechanism guard belongs with the scheduler.

1. **`ExchangeExec` exactly-once under concurrent callers** — N (=32) ≫ K threads
   race `execute(shard)` on one exchange; assert the child's `execute(p)` runs
   exactly `child.numPartitions` times (materialize ran once), never N×.
2. **`ExchangeExec` materialization-failure propagation** — a throwing child makes
   every concurrent caller observe the wrapped failure (original cause in the
   chain), materialize attempted once. (Guards the `matFailure` path.)
3. **Work-helping mechanism guard** (the load-bearing invariant — new
   `//src/test/scala/com/transformer/core:scheduler_test`, `parallelism=2` via its
   own `jvm_flags` JVM): a depth-5 / fan-4 nested `submitAndAwaitAll` fan-out
   **completes** on a 2-thread pool via `.get()`; the same shape awaited through a
   non-helping `CountDownLatch.await()` **deadlocks** (assert `TimeoutException`
   under `@Test(timeout=...)`). This is 02a's H4 promoted to CI: it documents *and
   enforces* that liveness rests on `ForkJoinTask.get()` work-helping, so any
   future refactor that swaps `submitAndAwaitAll` off `.get()` fails loudly here
   instead of wedging in production. The reusable deep-fan-out skeleton is in 02a
   ("Reusable forcing-function skeleton").

## Phase 3 — DEFERRED: eliminate the footgun (parent Option D)

The residual after Phase 1 is unchanged and honest: liveness of nested sharded
breakers rests on `ForkJoinTask.get()` work-helping — an FJP implementation
detail, not a contract. The real elimination is non-blocking / event-driven
breaker materialization (parent doc Option D), a multi-week architectural change.
**Do not do it now.** Sharding is off by default; the Phase-2 guard catches
regressions. Trigger to reopen: sharding is promoted toward a shipping default
(`MinShardableSize` / `BroadcastBuildThreshold` lowered), OR the Phase-2
wall-time check (below) shows a real cliff that B did not remove.

## Standard gates (CLAUDE.md required workflow)

1. `bazel test //...` green — including the new `scheduler_test` and the exchange
   concurrency cases.
2. **Sharded fuzzer green at K=4, no wall-time cliff.** Default target, plus a
   campaign to shake out timing residue:
   `bazel test //src/test/scala/com/transformer/fuzz:sharded_mode_fuzz_campaign
   --test_env=FUZZ_SEEDS=20000 --nocache_test_results --test_timeout=1800`.
   Record wall time; compare against the pre-change K=4 baseline — B should be
   neutral-to-faster (it removes serialisation), never slower.
3. **jaffle_shop 15/15**, exit 0, `/tmp/transformer-jaffle-out/`. Sharding is off
   there so no `ExchangeExec` is built — this only guards that the change is inert
   on the non-sharded path.
4. **Docs** reconciled in the same commit as the code:
   - `docs/gotchas.md` — the sharded-execution entry moves from "monitor DCL +
     plain `.get()`, cliff present, 02b reverted" to "monitor removed (CAS+latch,
     uncompensated loser-wait); cliff gone; footgun still rests on `.get()`
     work-helping, now guarded by `scheduler_test`." Keep the "sharding off by
     default" + "never `managedBlock` these waits" notes.
   - `docs/architecture.md` §2b — CAS+latch materialization (was `synchronized`
     DCL); drop the "a monitor-free CAS+latch WOULD remove the cliff" future-tense
     caveat now that it is done.
   - `docs/conventions.md` — the "never hold a monitor across a `Scheduler.pool`
     wait" convention now has a compliant in-tree exemplar (`ExchangeExec`); the
     code no longer violates it.
   - `docs/testing.md` — register `scheduler_test` + the two `ExchangeExecTest`
     concurrency cases.
   - `README.md` — unchanged (never claimed K>1 safe/unsafe).

## Acceptance

- `ExchangeExec` holds no JVM monitor across `materialize()`; loser-wait is a
  plain uncompensated `ready.await()`; no `managedBlock` anywhere.
- `bazel test //...` green; sharded fuzzer green at K=4 with wall time
  neutral-or-better vs baseline; jaffle_shop 15/15.
- Exactly-once + failure-propagation verified under N concurrent callers; the
  work-helping mechanism guard is a permanent CI test.
- All doc files above reconciled in the code commit; the residual footgun stated
  honestly (guarded, not eliminated; Option D deferred with a written trigger).

## Launch prompt

> Read `plans/bugfixes/02d-reland-monitor-free-exchange.md` and its prereqs
> (`02a` Resolution, `02b` banner, and the sharded-execution entry in
> `docs/gotchas.md`). Land it: Phase 0 commit the uncommitted 02c un-gate; Phase 1
> re-land the monitor-free `ExchangeExec` (CAS-claim + latch, plain uncompensated
> `ready.await()`, `submitAndAwaitAll` stays on plain `.get()` — do NOT reintroduce
> `managedBlock`); Phase 2 add the exactly-once / failure-propagation exchange
> tests and the `scheduler_test` work-helping mechanism guard; skip Phase 3. Then
> the CLAUDE.md gates (bazel test //..., sharded fuzzer at K=4 with a wall-time
> check, jaffle_shop 15/15) and the doc reconciliation. Prefer --effort max.
