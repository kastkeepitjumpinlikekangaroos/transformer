# Plan 10: In-tree instrumentation framework

> Status: landed (Sub-plans 1 + 2 of the batched instrumentation work) · Tier: cross-cutting · Effort: 2-3 days · Risk: low
>
> Prereqs satisfied: plan 09 (spill-to-disk) is landed — this plan
> reuses the `ExecutionOptions` plumbing that plan 09 introduced.

## Goal

Land the in-tree measurement substrate that makes the engine's
performance work durable: per-operator wall-time + custom counters,
`_perf.json` output next to `_run.json`, per-task CPU / allocation / GC
sampling, and a counter inventory across every breaker. The disabled
path is zero-cost by construction (one branch in `PhysicalPlanner.plan`,
asserted by [plan 11](11-benchmarking.md)'s `DisabledOverheadBench`).

This plan is the first half of the comprehensive instrumentation &
benchmarking work. The second half ([plan 11](11-benchmarking.md))
covers the JMH microbench harness, the macro-bench runner, and the
perf-tagged regression guard.

## Why it matters

`TaskResult` records `enqueuedAt / startedAt / finishedAt` and
`rowsProduced`, but every operator in the physical tree (HashAggregate,
HashJoin, SortExec, ExchangeExec, Filter, Project, scans) is opaque
without this plan. We don't know where time goes inside a task, and
plan 09's new spill paths are even more opaque — we can't tell whether
a slow task spilled or just had a lot of data. The macro bench and the
perf-tagged regression test in [plan 11](11-benchmarking.md) need this
substrate to work.

## Approach (high-level)

- **Per-operator instrumentation** is wrapped in at planner time, not
  added as a mutable field on `PhysicalPlan` and not added as a
  listener. When `opts.metricsEnabled` is false (default),
  `PhysicalPlanner.plan` returns the un-wrapped tree and there is
  exactly one branch + load on the disabled path. When enabled, each
  operator's `execute(p)` is wrapped in a `final class MeteredIterator`
  so the JIT can still inline `hasNext` / `next`.
- **Enablement plumbing matches spill.** Metrics enable is a new
  `metricsEnabled: Boolean = false` field on `ExecutionOptions`. The
  `SqlExecutor.execute(sql, catalog, opts)` overload threads it
  through exactly the same path spill uses, so the per-task
  `output.json` config shape stays consistent: `options.spill = "true"`
  toggles spill, `options.metrics = "true"` toggles metrics, both with
  the same parse semantics (case-insensitive `"true"` / `"1"` → true;
  anything else → false; no-throw on typo). The env var
  `TRANSFORMER_METRICS_ENABLED=1` and sysprop
  `transformer.metrics.enabled` are read in `DataJob.runOneTask` as the
  global-default override that flips `metricsEnabled` to true on every
  task's `ExecutionOptions` unless the task's own `options.metrics`
  already specifies a value.
- **Custom counters** are fixed `Array[LongAdder]` indexed by
  per-operator constants, not `Map[String, Long]`. Increments on the
  hot path stay allocation-free.
- **Output** lands in `_perf.json` next to the existing `_run.json` —
  separate file, separate schema version. `_run.json` is load-bearing
  across GUI hydration and external tooling; `_perf.json` is
  developer-facing and free to evolve.

## Sub-plan 1 — Foundation (Filter / Project / Scan only)

Lands the framework with the minimum surface area. The disabled-path
invariant is exercised by [plan 11](11-benchmarking.md)'s
`DisabledOverheadBench`.

New files:
- `src/main/scala/com/transformer/core/metrics/MetricsCollector.scala`
- `src/main/scala/com/transformer/core/metrics/OperatorMetrics.scala`
- `src/main/scala/com/transformer/core/metrics/MeteredIterator.scala`
- `src/main/scala/com/transformer/core/metrics/MetricsNode.scala`
- `src/main/scala/com/transformer/core/metrics/QueryMetrics.scala`
- `src/main/scala/com/transformer/core/metrics/TaskMetricsRecord.scala`
- `src/main/scala/com/transformer/core/metrics/JsonMini.scala` (stdlib-only
  parser scoped to this package because `core/metrics` sits below
  `job/` in the deps DAG)

Existing files modified:
- `src/main/scala/com/transformer/core/ExecutionOptions.scala` — add
  `metricsEnabled: Boolean = false` field.
- `src/main/scala/com/transformer/sql/exec/PhysicalPlanner.scala` —
  wrap operators in `MeteredIterator` when `opts.metricsEnabled`.
- `src/main/scala/com/transformer/sql/exec/SqlEngine.scala` — measure
  parse / optimize / physical-plan times when metrics enabled.
- `src/main/scala/com/transformer/core/SqlExecutor.scala` — extend
  `ExecutedQuery` with `metrics: Option[QueryMetrics]`.
- `src/main/scala/com/transformer/job/DataJob.scala` — write
  `_perf.json` next to `_run.json` when metrics were enabled for the
  task. Per-task `options.metrics` precedence over global default.
- `src/main/scala/com/transformer/job/JobRunRecord.scala` — add
  `perfManifest: Option[Seq[String]]` field (additive).

Tests:
- `src/test/scala/com/transformer/core/metrics/MetricsCollectorTest.scala`
- `src/test/scala/com/transformer/sql/exec/MetricsPlanWrapTest.scala`
- Extensions to `data_job_test` covering the precedence rules
  (per-task wins over global default, both routes round-trip).

## Sub-plan 2 — Custom counters in breakers

Strictly additive on Sub-plan 1. Each pipeline-breaking operator gets
two counter groups: **in-memory path** counters (always potentially
populated) and **spill path** counters (zero when spill never
triggered). Both groups use the same `Array[LongAdder]` storage with
disjoint index ranges declared in the operator's companion object.

Operators wired:
- `HashAggregateExec` — `groupCount`, `hashMapPeakSize`,
  `keyCodecPath`, `mergeNanos`, `partialAggregateNanos`, plus spill
  group (`spillEvents`, `bytesSpilled`, `spillRunsWritten`,
  `spillRunsRead`, `serdeWriteNanos`, `serdeReadNanos`,
  `mergeFromSpillNanos`, `peakInMemoryBytes`).
- `HashJoinExec` — `buildSideRows`, `probeSideRows`, `matchedRows`,
  `unmatchedRows`, `buildNanos`, `probeNanos`, `keyCodecPath`, plus
  grace-hash spill group (`bucketCount`, `bytesSpilledBuild`,
  `bytesSpilledProbe`, `bucketsLoadedNanos`, `peakBucketBytes`).
- `SortExec` — `comparatorCalls`, `inputRows`, `outputRows`, `path`
  (0=small-N / 1=K-way), plus external-merge group (`runsWritten`,
  `bytesSpilled`, `mergeRunsActive`, `mergeNanos`, `runDecodeNanos`).
- `ExchangeExec` — `keyNullCount` + `rowsRoutedShardN` (per-shard,
  shape sized at construction).
- `WindowExec` — `partitionCount`, `peakPartitionRows`,
  `frameEvalNanos`, plus bucketed-spill group (`partitionSpillEvents`,
  `bytesSpilled`, `peakBufferedRows`).
- `DistinctExec` — same shape as HashAggregate with no value columns.
- `ScanExec` (parquet only) — `bytesRead`, `rowGroupsRead`,
  `rowGroupsSkipped`, `predicatePushedDownCount`.

Counter discipline (must not be violated — see
[docs/conventions.md](../../docs/conventions.md)):
- Counters are `Array[LongAdder]`, never `Map[String, Long]`.
- Per-operator `final val Idx<Name>: Int` constants in the companion
  object, parallel `IdxCounterNames: Array[String]`.
- Allocations forbidden on the per-row hot path. Counter writes are
  `if (metricsNode != null) metricsNode.counters(IdxX).add(d)`.
- `AggStateSerde` on-disk format must never change for
  instrumentation — `SerdeStats` overloads are timing-only.

Tests:
- `src/test/scala/com/transformer/core/metrics/OperatorCountersTest.scala`
  — index-drift asserts (`IdxCounterNames.length == highest Idx + 1`)
  for every operator, plus populated-counter tests building small
  fixtures and asserting counter values.

## Counter discipline rules (apply to every sub-plan)

1. **Disabled-path cost is the most important invariant.** When
   `opts.metricsEnabled = false`, the operator tree returned by
   `PhysicalPlanner.plan` must be byte-for-byte the un-wrapped tree.
   The disabled-path bench in [plan 11](11-benchmarking.md) gates this
   at < 1%.
2. **Hand-roll JSON.** No new Maven dep — mirror the
   `TaskRunRecord.serialize` pattern.
3. **`System.nanoTime` for measurement, `Instant.now` for wall-clock
   display.** Don't mix them.
4. **Per-operator counters are `Array[LongAdder]`**, not `Map`.

## Critical files reference

- `src/main/scala/com/transformer/core/ExecutionOptions.scala`
- `src/main/scala/com/transformer/sql/exec/PhysicalPlanner.scala`
- `src/main/scala/com/transformer/sql/exec/SqlEngine.scala`
- `src/main/scala/com/transformer/core/SqlExecutor.scala`
- `src/main/scala/com/transformer/job/DataJob.scala` (`runOneTask` +
  `buildExecutionOptions`)
- `src/main/scala/com/transformer/job/JobRunRecord.scala`
- `src/main/scala/com/transformer/job/TaskRunRecord.scala`
  (atomic-write pattern to mirror)
- `src/main/scala/com/transformer/core/Scheduler.scala` (sysprop / env
  resolution pattern to mirror)
- The six `*Exec.scala` operator files for Sub-plan 2's counter wiring.

## Launch prompt

```
Read plans/perf/10-instrumentation.md and implement Sub-plans 1 + 2 in
order.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass at every step, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket
deploy jar must hit 15/1/1. Land docs in the same PR per CLAUDE.md
"Required workflow".

Sub-plan 1 first — minimum surface area, framework wired through Filter /
Project / Scan only. After it lands, run the full test suite + the
jaffle e2e with TRANSFORMER_METRICS_ENABLED=1 and confirm every task
output dir contains a _perf.json.

Then Sub-plan 2 — per-operator custom counter sets across HashAggregate /
HashJoin / Sort / Distinct / Window / Exchange / Scan. Each spillable
operator gets in-memory AND spill counter groups with disjoint index
ranges. Counters are Array[LongAdder] with named Idx* constants — never
a Map. Index-drift asserts in the per-operator test catch
IdxCounterNames length / index constant mismatches.

CRITICAL invariant: disabled-path cost. With opts.metricsEnabled = false
the operator tree must be byte-for-byte the un-wrapped tree. The
DisabledOverheadBench in plan 11 will gate this — but write Sub-plan 1
expecting that gate to fire.

Spawn parallel sub-agents for: (a) writing the OperatorCountersTest in
parallel with the operator counter wiring, (b) writing the
MetricsPlanWrapTest in parallel with the planner-side wrap.

Stop and ask before: (a) changing the PhysicalPlan trait shape, (b)
adding any per-row allocation on the metered hot path, (c) modifying
the AggStateSerde on-disk format, (d) introducing a counter as
Map[String, Long].

Include in PR description: jaffle wall-time before/after (must be
indistinguishable at the disabled-path level), one sample _perf.json
file with populated operator-tree counters, the
IdxCounterNames.length asserts for every operator passing in the
OperatorCountersTest.
```
