# Plan 02a: Get a deterministic repro of the sharded deadlock (or refute it)

> Status: DONE — hard deadlock REFUTED on JDK 21 · Tier: investigation (gates 02b) · Effort: 1-3 days · Risk: medium
>
> Parent design doc: [`02-sharded-execution-deadlock.md`](02-sharded-execution-deadlock.md).
> Read its "Session 1 finding" banner first — this sub-plan exists because the
> deep-plan timeout test that plan prescribed is **green on HEAD**.
>
> **Outcome (full write-up in "Resolution" at the bottom): no deterministic hard
> deadlock is reproducible on JDK 21 for the shapes we generate.** H1–H5 all ran
> green; `ForkJoinTask.get()` work-helping (not the absence of the bug) is what
> masks it. `docs/gotchas.md` re-characterised from "deadlocks" to "throughput
> cliff + latent footgun".

## Why this is the gating item

The whole point of Phase 1 in the parent plan was a *red* test: something that
hangs on HEAD so the fix has a target. Session 1 built that test and it did not
hang (details in the parent's finding banner). Until we have a deterministic
reproduction, we cannot:

- prove the bug exists in the form the gotchas entry describes, on this JDK;
- prove the 02b fix actually fixes anything (a green-on-HEAD test stays green
  whether or not the fix lands — a false negative);
- responsibly remove the `shard_count=1` pin (02c) — we'd be removing a guard
  against a hazard we can't demonstrate.

So: **either produce a deterministic, CI-able reproduction, or conclude the
hard-deadlock is not reproducible on JDK 21 for the plan shapes we generate and
re-characterise the hazard (livelock / load-dependent starvation / DAG-only).**
Both outcomes are acceptable deliverables; an unverified "it deadlocks" is not.

## What we already know (don't re-derive)

- **`ForkJoinTask.get()` from a worker help-steals.** A worker awaiting its
  submitted sub-tasks runs them itself, depth-first, on its own stack. This is
  why a *tree* of exchanges never starves a single worker. Confirmed
  empirically (parent banner).
- **A tree has no monitor cycle.** `ExchangeExec.ensureMaterialized` holds the
  instance monitor across `materialize()`, but in a tree the deepest-monitor
  holder is a help-stealing worker with an *independent* subtree; it always
  finishes and releases. Reduced parallelism, not deadlock.
- **The single-threaded top drain still materialises the whole tree.** Pulling
  output partition 0 of a sharded root breaker recursively triggers every nested
  exchange's `materialize()` (each `execute(p)` eagerly materialises on first
  touch), so you do not need concurrent consumers to *exercise* the nesting —
  but you do (apparently) need more than a tree to *deadlock* it.
- **Sharding is off in the shipping config**, so nothing users run reaches this;
  the only driver is `ShardedModeFuzzTest` with the sharding `jvm_flags`.

## Reusable forcing-function skeleton

Session 1's deep-plan builder (drop into a `scala_junit_test` with
`jvm_flags = ["-Dtransformer.scheduler.parallelism=2"]`; `Scheduler.parallelism`
is a class-load `val`, so the small pool needs its own JVM). It is correct and
reusable — it just isn't *sufficient* to deadlock on its own:

```scala
private val schema = Schema(Vector(
  Field("k", DataType.IntType), Field("v", DataType.IntType)))
private val cols: Seq[Expr] = Seq(
  ColRefExpr(0, "k", DataType.IntType), ColRefExpr(1, "v", DataType.IntType))

/** depth nested DISTINCT(Exchange(...)) layers; DISTINCT is idempotent so the
  * whole stack computes the distinct input rows but nests `depth` exchanges. */
private def deepShardedDistinct(scan: PhysicalPlan, depth: Int, k: Int): PhysicalPlan = {
  var cur = scan
  var d = 0
  while (d < depth) { cur = DistinctExec(ExchangeExec(cur, cols, numShards = k)); d += 1 }
  cur
}
```

A minimal partitioned leaf (`InMemoryPartitionedPlan` exists privately in
`ExchangeExecTest.scala`/`SortExecTest.scala`; copy it locally to keep suites
independent — do not export it). Assert the output multiset equals the distinct
input rows (a "completes but wrong" guard), under `@Test(timeout = 20000)`.

## Repro hypotheses, in priority order

Each: the idea, the concrete experiment, the signal that confirms it, and what
it implies. Stop at the first that yields a deterministic hang.

### H1 (highest value) — the real fuzzer at default K hangs

The parent plan claims removing `shard_count=1` makes `ShardedModeFuzzTest`
hang. That is the original observation ("14 workers parked on two exchange
monitors"). It runs on the **default** pool (`2 × cores`, not 2), generates
**joins, unions, CTEs, windows** (DAGs, not just trees), and runs **base +
metamorphic variants** per case.

- Experiment: on HEAD, drop `-Dtransformer.scheduler.shard_count=1` from the
  `sharded_mode_fuzz_test` `jvm_flags` (parent plan lists the exact lines), run
  it under a wall-clock cap (`--test_timeout=180`), watch for a hang. If it
  hangs, capture a thread dump (`jstack` on the test JVM, or
  `-Dtest.timeout`-driven dump) to confirm workers parked on exchange monitors /
  `ForkJoinTask` waits.
- Confirms: the bug is real at default K; the trigger is the generated DAG
  shapes and/or the heavier concurrency, not depth alone.
- Implies: minimise the failing generated case (the fuzzer already shrinks) into
  a deterministic unit repro for 02b's red test. NOTE: this was deliberately
  **not** run in session 1 to avoid a multi-minute hang mid-session — it is the
  first thing to do here.

### H2 — DAG / shared-exchange monitor cycle

A tree has no cycle; a DAG might. Self-joins (`t JOIN t`) and any plan where one
exchange feeds two parents can put two workers in a hold-and-wait cycle across
two exchange monitors.

- Experiment: build (directly, via exec classes) a sharded **self-join** or a
  diamond where two parent breakers share one child exchange, on `parallelism=2`,
  drain shards concurrently. Also try the planner path: a generated
  `WITH x AS (...) SELECT ... FROM x JOIN x` under sharding flags.
- Confirms: a thread dump showing worker A holding monitor M1 + blocked on M2,
  worker B holding M2 + blocked on M1.
- Implies: the monitor (mechanism B) is the true culprit and is cycle-, not
  depth-, driven — strengthens the case that B (monitor-free exchange) is the
  load-bearing fix, more than A or C.

### H3 — non-helping external threads pin monitors

Help-stealing rescues trees only because the *workers* help. Threads that are
**not** `ForkJoinWorkerThread` (the writer's drain threads, validation drains,
GUI/FX, the test's main thread) block on `.get()`/monitors **without** helping.
Saturate the pool with monitor-blocked external threads while the holder is also
external/non-helping.

- Experiment: drain all K shards on K dedicated external threads while *also*
  occupying both pool workers with unrelated blocking tasks; or reproduce the
  writer path (`ParquetWriter.writePartitioned` fans per-partition drain tasks
  onto the pool — those drains trigger materialise from *inside* a worker).
- Confirms: progress stalls with both workers monitor-blocked and the holder a
  non-helping thread.
- Implies: the hazard is real in the **writer** path even if not in a bare
  drain — important because that is the production consumer.

### H4 — `.get()` vs `.join()` and compensation thresholds

Quantify how much help-stealing is actually saving us. Swap
`submitAndAwaitAll`'s `.get()` for behaviour that does **not** help (e.g. await
on a separate `CountDownLatch` the task counts down, so the awaiter genuinely
parks) and see whether the *tree* then deadlocks at `depth × K > P`.

- Experiment: a throwaway Scheduler variant that parks without helping; run the
  H0 skeleton against it.
- Confirms: tree deadlocks once helping is removed.
- Implies: the documented mechanism #2 is real but *masked* by helping on JDK
  21; the fix (A: ManagedBlocker) is still correct insurance because helping is
  an implementation detail, not a contract — document this explicitly.

### H5 — default-size pool, depth, and width together

Maybe `P = 2` is too small to deadlock (one worker always suffices for a tree)
but a *mid-size* pool with a *wide* DAG hits the "all workers monitor-blocked,
none holding a finishable subtree" state. Sweep `parallelism ∈ {2,4,8}` ×
`depth ∈ {4,8}` × `K ∈ {4,8}` × {tree, self-join, union-of-N} and look for a
hang.

- Implies (if found): a parameterised deterministic repro; pick the smallest
  hanging point for CI.

## Decision criteria

- **Found a deterministic hang** (H1-H5): minimise it to a unit test with a
  timeout, land it as the red test, and proceed to
  [`02b`](02b-implement-fix.md). Record the exact shape + pool size that trips
  it (this becomes 02b's acceptance and 02c's residual-limit measurement).
- **No deterministic hang after H1-H5**: write up the negative result in
  `docs/gotchas.md` — downgrade the entry from "deadlocks" to "can livelock /
  severely degrade under concurrent sharded load; not a hard deadlock on JDK 21
  for the shapes we generate, because FJP work-helping serialises tree
  materialisation onto one worker." Then decide with the user whether 02b's
  defensive fix still ships (recommended: yes — A+B are cheap and remove a real
  latent footgun, and B fixes the monitor-serialisation throughput cliff even if
  it isn't a hard deadlock). Do **not** remove the `shard_count=1` pin (02c)
  without a green fuzzer at default K.

## Acceptance

- A committed artifact: **either** a `@Test(timeout=…)` that is red on HEAD and
  (after 02b) green, with the triggering shape documented; **or** a written,
  evidence-backed refutation (thread dumps / experiment matrix) plus an updated
  gotchas characterisation.
- No production-code changes in this sub-plan — investigation only. Any
  throwaway targets/files are removed before handoff (keep the tree clean; only
  the committed red test, if found, stays).

## Resolution (2026-07-08, JDK 21, 8-core / parallelism default 16)

**Verdict: REFUTED as a hard deadlock.** No deterministic hang was found; the
hazard is real but masked by `ForkJoinTask.get()` work-helping. Deliverable is
the evidence-backed refutation + the `docs/gotchas.md` re-characterisation.

### What was run

- **H1 — real fuzzer at default K.** Dropped `shard_count=1`; `sharded_mode_fuzz_test`
  at K = `Scheduler.parallelism` = 16 → **PASSED in 4.6s.**
- **H5/H2 — worst realistic regime.** Same target with `parallelism=2`,
  `shard_count=8`, `FUZZ_SEEDS=1500` (joins, self-joins, CTEs-referenced-twice,
  windows — i.e. the DAG/shared-exchange shapes) → **PASSED in 66s.** A `jstack`
  mid-run showed: no `Found ... deadlock`, zero threads blocked on an exchange
  monitor, ~100–157% CPU (actively progressing), the `main` thread parked in
  `ExchangeExec.materialize → submitAndAwaitAll → ForkJoinTask.get → awaitDone →
  unmanagedBlock`, and heavy `transformer-worker-*` thread churn (thread ids into
  the #11000s) = FJP compensation spawning spares for I/O-blocked workers.
- **H3 — non-helping external drainers (real exec classes).** A 6-deep
  `DistinctExec(ExchangeExec(...))` plan, K=8, `parallelism=2`, drained by K
  concurrent *external* (non-worker) threads that hold/contend the top exchange
  monitor while parked → **completes, correct multiset.** The writer path
  (`ParquetWriter.writePartitioned`) is strictly safer: it drains on pool workers
  (windowed `Scheduler.submit` + `.get()`), which *do* help-steal.
- **H2 — diamond.** Two per-shard breakers sharing ONE `ExchangeExec` instance,
  drained concurrently → **completes.** No two-monitor cycle (monitor order is
  the plan's acyclic topology, so `{E1→E2}` vs `{E2→E1}` cannot both occur).
- **H4 — mechanism isolation (the proof it's masked, not absent).** Same nested
  fan-out (depth 5, fan 4, 1024 leaves) on a 2-thread pool:
  `ForkJoinTask.get()` (work-helping) → **completes**; `CountDownLatch.await()`
  (non-helping, no compensation) → **deadlocks (TimeoutException).** So helping is
  the load-bearing reason HEAD is safe — an FJP implementation detail, not a
  contract.

### Corrections to the parent design doc's model

- **`.get()` DOES cooperate.** The parent's mechanism #2 ("parks the worker
  WITHOUT pool compensation") is wrong: from a worker `.get()` work-helps
  (steals + runs the awaited sub-tasks depth-first), and I/O-blocked workers are
  compensated. The `Scheduler` scaladoc the parent calls "wrong" is effectively
  right for the plans we generate. (Do not "fix" that scaladoc in 02b to say the
  opposite.)
- **No monitor cycle is possible** from an acyclic plan (mechanism #1 is a
  serialisation throughput cliff, not a deadlock).
- The old "14 workers on two exchange monitors, 0% CPU" was most likely a
  transient pile-up snapshot during nested materialisation (or an earlier JVM),
  not a permanent stall.

### Recommendations for 02b / 02c

- **02b (defensive fix): still worth landing** — but reframed as a *performance +
  latent-footgun* fix, not a deadlock fix. Value: (B) monitor-free `ExchangeExec`
  materialisation removes the shard-reader serialisation cliff; (A) compensated
  waits bound the thread churn. There is no red acceptance test to make green —
  acceptance becomes "sharded fuzzer green at default K (already true) + a
  throughput / thread-count improvement + the H4 non-helping-wait guard as a
  regression test if the wait path is ever changed." **C (growable executor) is
  not justified by any observed hang** — hold it unless profiling shows the churn
  matters.
- **02c (un-gate):** the pin can technically be removed (fuzzer is green at
  default K), but keep it for campaign speed / bounded churn until 02b lands.

### Throwaway artifacts (removed before handoff)

- `sharded_mode_fuzz_test` variant without the `shard_count=1` pin
  (`zz_scratch_sharded_kdefault` BUILD target).
- `ZzDeadlockProbeTest.scala` + `zz_deadlock_probe_test` BUILD target (probes H2,
  H3, H4). Reproducible verbatim from this write-up if 02b wants to promote the
  H3 completion probe (green characterisation guard) and the H4 non-helping-wait
  probe (mechanism guard) into permanent tests.
