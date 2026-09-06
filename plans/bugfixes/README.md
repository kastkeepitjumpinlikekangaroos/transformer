# Bug-fix plans — fuzzer-surfaced `src/main` defects

The metamorphic fuzz campaign (commits `bc5d977`..`a157494`, "metamorphic
fuzz pt1-5") found two real `src/main` engine bugs. **Both are fixed**: bug
01 committed at `33fa4e4`; bug 02 closed by 02f (bottom-up exchange
pre-materialization) after a five-sub-plan investigation — see the status
column below. Each plan removes its harness gate as the proof the fix
landed.

Two more defects (03, 04) came later, from the DAG-scheduler fuzzer rather than
the SQL ones. Neither needed a fix-design plan — each was a one-expression cause
and the fix landed with the fuzzer that found it — so they are recorded in the
table below and described in [docs/gotchas.md](../../docs/gotchas.md) without
plan files.

These are correctness / liveness defects in `src/main`, not harness issues.
The harness changes that came out of the same campaign (e.g. routing `Float`
columns through the NaN-aware comparator) were already fixed in-place and are
not tracked here.

## The bugs

| # | Plan | What breaks | Status |
|---|------|-------------|--------|
| [01](01-spill-duplicate-column-names.md) | Spill files can't round-trip duplicate column names | Any spill of a schema with two same-named columns NPEs on read-back. Joins (`a JOIN b` → `[k,v,k,v]`) and name-colliding `GROUP BY` keys are the triggers. | **FIXED + committed** (`33fa4e4`): positional spill schema at every write site, per-operator regression tests + e2e repro, `MetaModeDifferential`'s `hasAnyJoin` spill skip removed. Done. |
| [02](02-sharded-execution-deadlock.md) | Sharded execution hard-deadlocks at K>1 (helpJoin cycle) | [02a](02a-reproduce-or-refute-deadlock.md) refuted the hang at small budgets; [02b](02b-implement-fix.md) was reverted (`managedBlock` wedged the pool); [02c](02c-ungate-validate-document.md) un-gated the fuzzer to `shard_count=4`; [02d](02d-reland-monitor-free-exchange.md) landed the monitor-free CAS+latch exchange — during whose baseline the 20000-seed campaign **reproduced the hang** ([02e](02e-campaign-deadlock-reproduced.md)): `ForkJoinTask.get()`'s helpJoin inlines a consumer of an exchange beneath that exchange's own materialization frames, a cycle no monitor-vs-latch choice avoids. | **FIXED for engine execution** by [**02f**](02f-nonblocking-materialization.md) (parent Option D made concrete: `PhysicalPlanner.preMaterializeExchanges` publishes every exchange bottom-up at the drain choke points before consumers exist, removing the wait every deadlock cycle needs). Gated by `exchange_deadlock_stress_test`: the wedge topology that failed 7/7 pre-fix runs green through the pass (5/5, ~2s), `STRESS_LAZY=1` keeps the red instrument, and the 20000-seed campaign completes. Residual: directly-constructed lazily-NESTED exchanges drained from pool tasks remain unsafe (gotchas.md). |
| 03 | (no plan — fixed on discovery) | A task with no `outputFile` could not feed a downstream task or run a validation | `DataJob.materializeIfNeeded` threw `UnsupportedOperationException` for a memory-only task with a consumer, from inside the worker — so a structurally-fine job failed mid-run and every downstream task cascaded to `Skipped`, while `SQLTask`'s own scaladoc advertised memory-only feeders. | **FIXED**: the result is drained into a `MaterializedView` (`MaterializedView.fromQuery`) and published like any other view. Regressions in `job/data_job_test`; the general guard is `fuzz/dag_scheduler_fuzz_test`. |
| 04 | (no plan — fixed on discovery) | `job.json` does not point at a Failed or Skipped task's `_run.json` | `writeJobRecord` derived each entry's `runFile` from `TaskResult.outputPath`, which is `None` for both statuses even when the task has an `outputFile` and the runner stamped a record there. The record was unreachable from the manifest that indexes it, so `JobSession` hydration fell back to the compact summary and lost the timestamps, validation entries and output directory. | **FIXED**: `runFile` is derived from `SQLTask.outputFile` (rendered, cloud paths excluded), so it is present for every terminal status. Regression in `job/data_job_test`; the general guard is the manifest check in `oracle/DagSchedule`. |

## What's left (as of 2026-09-05)

Nothing — all four bugs are closed. [02f](02f-nonblocking-materialization.md)
landed the same day it was designed (sub-plans 02a-02e are the investigation
record; 02f holds the fix design + landing record). Sharding itself remains
off by default (`MinShardableSize = Long.MaxValue`,
`BroadcastBuildThreshold = 1M`); flipping those gates is a perf decision
(plans/perf/04, 05) that can now be evaluated without a liveness blocker.

## Shared context

Bugs 01 and 02 live behind execution modes that are off in the default build,
which is why they survived to the fuzzer rather than being caught by the e2e
examples:

- **Spill** is per-task opt-in (`output.json` → `options.spill = "true"`),
  default off — see [plans/perf/09-spill-to-disk.md](../perf/09-spill-to-disk.md).
- **Sharding** is gated by class-load `val`s in
  `LogicalPlanCardinality` that the shipping config never trips — see
  [plans/perf/04-hash-partition-breakers.md](../perf/04-hash-partition-breakers.md)
  and [plans/perf/05-join-planner.md](../perf/05-join-planner.md).

The fuzzer reaches them by forcing the modes on (`spillThresholdBytes = 1`;
`jvm_flags` setting the sharding gates to `1`). Bugs 03 and 04 hid differently:
neither needs a special mode, only a job shape the e2e examples never produce.
Every task in `jaffle_shop`, `polymarket` and the directory loader sets an
`outputFile`, so nothing exercised a memory-only feeder (03) until a generator
drew one; and the examples' happy paths leave no Failed or Skipped task holding
an unreachable run record (04). Authoritative descriptions of all four bugs live
in [docs/gotchas.md](../../docs/gotchas.md); plans 01 and 02 are the fix designs
for the two that needed one.

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
