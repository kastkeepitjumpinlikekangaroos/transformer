# Bug-fix plans — fuzzer-surfaced `src/main` defects

The metamorphic fuzz campaign (commits `bc5d977`..`a157494`, "metamorphic
fuzz pt1-5") found two real `src/main` engine bugs. **Both are fixed**: bug
01 committed at `33fa4e4`; bug 02 closed by 02f (bottom-up exchange
pre-materialization) after a five-sub-plan investigation — see the status
column below. Each plan removes its harness gate as the proof the fix
landed.

These are correctness / liveness defects in `src/main`, not harness issues.
The harness changes that came out of the same campaign (e.g. routing `Float`
columns through the NaN-aware comparator) were already fixed in-place and are
not tracked here.

## The two bugs

| # | Plan | What breaks | Status |
|---|------|-------------|--------|
| [01](01-spill-duplicate-column-names.md) | Spill files can't round-trip duplicate column names | Any spill of a schema with two same-named columns NPEs on read-back. Joins (`a JOIN b` → `[k,v,k,v]`) and name-colliding `GROUP BY` keys are the triggers. | **FIXED + committed** (`33fa4e4`): positional spill schema at every write site, per-operator regression tests + e2e repro, `MetaModeDifferential`'s `hasAnyJoin` spill skip removed. Done. |
| [02](02-sharded-execution-deadlock.md) | Sharded execution hard-deadlocks at K>1 (helpJoin cycle) | [02a](02a-reproduce-or-refute-deadlock.md) refuted the hang at small budgets; [02b](02b-implement-fix.md) was reverted (`managedBlock` wedged the pool); [02c](02c-ungate-validate-document.md) un-gated the fuzzer to `shard_count=4`; [02d](02d-reland-monitor-free-exchange.md) landed the monitor-free CAS+latch exchange — during whose baseline the 20000-seed campaign **reproduced the hang** ([02e](02e-campaign-deadlock-reproduced.md)): `ForkJoinTask.get()`'s helpJoin inlines a consumer of an exchange beneath that exchange's own materialization frames, a cycle no monitor-vs-latch choice avoids. | **FIXED for engine execution** by [**02f**](02f-nonblocking-materialization.md) (parent Option D made concrete: `PhysicalPlanner.preMaterializeExchanges` publishes every exchange bottom-up at the drain choke points before consumers exist, removing the wait every deadlock cycle needs). Gated by `exchange_deadlock_stress_test`: the wedge topology that failed 7/7 pre-fix runs green through the pass (5/5, ~2s), `STRESS_LAZY=1` keeps the red instrument, and the 20000-seed campaign completes. Residual: directly-constructed lazily-NESTED exchanges drained from pool tasks remain unsafe (gotchas.md). |

## What's left (as of 2026-07-23)

Nothing — both bugs are closed. [02f](02f-nonblocking-materialization.md)
landed the same day it was designed (sub-plans 02a-02e are the investigation
record; 02f holds the fix design + landing record). Sharding itself remains
off by default (`MinShardableSize = Long.MaxValue`,
`BroadcastBuildThreshold = 1M`); flipping those gates is a perf decision
(plans/perf/04, 05) that can now be evaluated without a liveness blocker.

## Shared context

Both bugs live behind execution modes that are off in the default build, which
is why they survived to the fuzzer rather than being caught by the e2e
examples:

- **Spill** is per-task opt-in (`output.json` → `options.spill = "true"`),
  default off — see [plans/perf/09-spill-to-disk.md](../perf/09-spill-to-disk.md).
- **Sharding** is gated by class-load `val`s in
  `LogicalPlanCardinality` that the shipping config never trips — see
  [plans/perf/04-hash-partition-breakers.md](../perf/04-hash-partition-breakers.md)
  and [plans/perf/05-join-planner.md](../perf/05-join-planner.md).

The fuzzer reaches them by forcing the modes on (`spillThresholdBytes = 1`;
`jvm_flags` setting the sharding gates to `1`). Authoritative descriptions of
both bugs already live in [docs/gotchas.md](../../docs/gotchas.md); these plans
are the fix designs.

## Required workflow gates (applies to every plan)

From `CLAUDE.md`:

1. `bazel test //...` green.
2. `java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar` exits 0,
   15/15 Succeeded.
3. Docs updated where claims are now stale — in particular the matching
   `docs/gotchas.md` entry moves from "known bug, not fixed" to fixed/removed,
   and `docs/testing.md` gains the new regression coverage.

Project-wide rules: Scala 2.13.16 / JDK 21 (no bumps), no emojis, no new heavy
deps, prefer editing existing files, no back-compat shims, describe current
behavior in comments (no stale "phase N" scaffolding).

## Launch prompts

Each prompt is embedded at the bottom of its plan file. Open a fresh session,
prefer `--effort max`, and paste the prompt.
