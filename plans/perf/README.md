# Performance plans — index and launch prompts

Nine focused plans for moving transformer's performance forward, in the
order they should be tackled. Each plan is self-contained: read the
file, paste the launch prompt into a fresh Claude session, and the
agent should have everything it needs.

## Priority and dependency table

| # | Plan | Tier | Effort | Risk | Depends on |
|---|------|------|--------|------|------------|
| [01](01-kway-merge-sortexec.md) | K-way merge in SortExec | 2 | ~1 day | low | — |
| [02](02-packed-aggregate-keys.md) | Packed hash-aggregate/distinct keys | 2 | 3-5 days | medium | — |
| [03](03-vectorized-expr-eval.md) | Vectorized expression evaluation | 1 | 1-3 weeks (Phases 1-4 done; 0/5/7 remain) | medium | — |
| [04](04-hash-partition-breakers.md) | Hash-partition between breakers | 1 | 4-8 weeks | high | 02, 03 |
| [05](05-join-planner.md) | Smarter join planner | 2 | 2-4 days | low-med | — |
| [06](06-expand-parquet-pushdown.md) | Expand parquet predicate pushdown | 3 | 2-3 days | low | — |
| [07](07-push-through-joins.md) | Push predicates/projections through joins | 3 | 2-4 days | medium | — |
| [08](08-stream-emit-breakers.md) | Stream emit from breakers | 3 | 2-3 days | medium | — |
| [09](09-spill-to-disk.md) | Spill-to-disk for breakers | cross-cutting | 3-6 weeks | high | 01, 04 |
| [10](10-instrumentation.md) | In-tree instrumentation framework | cross-cutting | 2-3 days | low | 09 (only for spill counter coverage) |
| [11](11-benchmarking.md) | Microbench + macro bench + regression guard | cross-cutting | 3-5 days | medium | 10 |
| [12](12-cte-materialization.md) | Materialize multiply-referenced CTEs once | 2 | 3-5 days (Phases 1-3 done; Phase 4 refinements deferred) | medium | — (optional 09 tie-in for spill-backed materialization) |

## Recommended sequence

**Quick wins (do first, in parallel):**
- 01 (k-way merge) — local, low-risk, immediately visible.
- 06 (expanded pushdown) — local, low-risk, cumulative with other plans.
- 05 (join planner) — independent, modest scope.

**Mid-tier (do next):**
- 02 (packed keys) — big hotspot, moderate scope. Plan 04 will need it.
- 07 (push through joins) — orthogonal to the rest. Watch outer-join correctness.
- 08 (stream emit) — only valuable if profiling confirms writer is starved.

**Structural commitments (long-running, do last):**
- 03 (vectorized eval) — Phases 1-4 already in main. Remaining work
  (Phase 0 parity tests + Phase 5 Func/Case/InList/Like overrides +
  Phase 7 breaker key extraction) is 1-3 weeks. **Phase 7 is what
  actually unlocks the speedup on agg- and join-heavy workloads.**
- 04 (hash-partition breakers) — the biggest architectural change. Requires 02 + 03 Phase 7.

**Survival (only if production data growth demands it):**
- 09 (spill) — requires 01 + 04. Most workloads will never hit the limits this fixes.

## Required workflow gates (applies to every plan)

From `CLAUDE.md`:

1. `bazel test //...` green.
2. `java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar` exits
   0 with 15/15 tasks Succeeded.
3. `java -jar bazel-bin/examples/polymarket/polymarket_deploy.jar` matches
   the 15-Succeeded / 1-ValidationFailed / 1-Skipped pattern.
4. Docs (`README.md`, `docs/architecture.md`, `docs/conventions.md`,
   `docs/extending.md`, `docs/gotchas.md`, `docs/testing.md`,
   `docs/code-map.md`) updated where claims are now stale.

Project-wide rules from `CLAUDE.md`:
- Scala 2.13.16, JDK 21, no version bumps without asking.
- No emojis.
- **No new heavy SQL deps.** No Calcite, no DuckDB, no embedded databases.
  JSqlParser is for AST only.
- Prefer editing existing files to creating new ones.
- No backwards-compatibility shims unless asked.

## Launch prompts (copy-paste into a fresh Claude Code session)

Each session's prompt is also embedded at the bottom of its own plan
file. The launch prompts are designed for a fresh session with no prior
context — they tell the agent what to read, what gates to honor, what
phases to follow, and what to stop and ask about.

> **Best practice**: open a new terminal and run `claude --effort max`
> (or invoke `/effort max` in the session) before pasting. Each
> workstream is large enough that max effort pays off.

### 01 — K-way merge in SortExec

```
Read plans/perf/01-kway-merge-sortexec.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy SQL deps, bazel test //... must
pass, jaffle_shop deploy jar must pass with 15/15 Succeeded. Land docs in
the same PR per CLAUDE.md "Required workflow".

Approach: implement the K-way heap merge behind a feature flag first; run
both old + new paths under existing SortExec tests for parity; remove the
flag once green. Profile with async-profiler on a 1M-row sort and a
jaffle ORDER BY path; include before/after comparator counts and wall
times in the PR description.

Spawn parallel sub-agents only for genuinely independent work (e.g. one
agent writing the microbenchmark while the main agent implements). Don't
amend commits — create new commits on hook failures.

Stop and ask before: removing the old code path, changing the comparator
interface, or touching anything outside SortExec.scala and its tests.
```

### 02 — Packed hash-aggregate / distinct keys

```
Read plans/perf/02-packed-aggregate-keys.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps (stdlib + minimal additions
only; do NOT add fastutil or guava), bazel test //... must pass, jaffle_shop
deploy jar must hit 15/15 Succeeded, polymarket deploy jar must hit the
15/1/1 pattern. Land docs in the same PR.

Follow the 6-phase plan in the doc. After each phase, run the full test
suite + the end-to-end examples. If perf numbers from phases 2-5 already
meet the goal, skip Phase 6 (LongHashMap) and leave it as a follow-up.

Spawn parallel sub-agents for: (a) building HashKeysTest in parallel with
the codec implementation, (b) profiling polymarket before and after to
quantify the win.

Stop and ask before: changing the Expr.eval interface, adding any
external dependency, modifying anything outside the operators and core/.
Include in the PR description: HashAggregate wall-time before/after on
Polymarket stg→int tasks.
```

### 03 — Vectorized expression evaluation (Phases 1-4 done; finish 0/5/7)

```
Read plans/perf/03-vectorized-expr-eval.md and finish the remaining work.

CONTEXT: Phases 1-4 of this plan are already landed in main — evalVec
exists on the Expr trait with a default boxed-loop fallback, and the
common Expr subtypes (LitExpr, ColRefExpr, CastExpr, UnaryOpExpr,
BinOpExpr, IsNullExpr) override it via VecOps. FilterExec and ProjectExec
use evalVec. Do NOT re-do that work.

WHAT'S REMAINING (in order):
- Phase 0: parity tests (ExprBatchTest). NONE exist today; this is the
  urgent gap. Land before any new override.
- Phase 5: evalVec overrides on FuncExpr, CaseExpr, InListExpr, LikeExpr.
- Phase 7: operator integration in HashAggregateExec, HashJoinExec,
  WindowExec for batch-at-a-time key extraction (and AggState.updateVec
  for primitive aggregate states).

Use max effort. Honor CLAUDE.md: no new heavy deps, no codegen libs,
bazel test //... must pass at every phase, jaffle_shop and polymarket
deploy jars must pass at every phase. Land docs in the same PRs.

CRITICAL ORDER: Phase 0 first. Without parity tests every new override
is a correctness gamble — the codebase has no test that compares
evalVec(b)(i) against eval(b, i). Build the test infrastructure in
Phase 0 and extend it as each later phase adds an override.

COORDINATION:
- Plan 02 (packed keys): the codec must consume Array[ColumnVector]
  from evalVec. If plan 02 has already landed assuming row eval,
  Phase 7 has to refactor it.
- Plan 01 (k-way merge): SortExec comparator vectorization is plan 01
  Phase 3. Do NOT duplicate here.

Phase boundaries:
- Phase 5 lands as one PR (all four remaining Expr overrides + their
  parity cases).
- Phase 7 lands as three PRs: 7a (HashAggregate), 7b (HashJoin),
  7c (WindowExec).

Spawn parallel sub-agents for: (a) building ExprBatchTest while
implementing Phase 5, (b) profiling polymarket between phases to track
cumulative speedup.

Stop and ask before: (a) changing the Expr trait shape beyond what
exists, (b) modifying ColumnarBatch storage layout, (c) adding
vectorization for SortExec comparator (that's plan 01), (d) adding any
caching or pooling for intermediate ColumnVectors (that's Phase 8,
deferred).

Include in each PR description: parity test status, before/after wall
time on the relevant workload (Phase 5 = jaffle_shop with DATE_TRUNC/
COALESCE; Phase 7a = polymarket stg→int aggregate tasks; Phase 7b =
polymarket mart_* join tasks), async-profiler flame graph confirming
the boxed eval call is gone from the relevant hot loop.
```

### 04 — Hash-partition between breakers

```
Read plans/perf/04-hash-partition-breakers.md and implement it end-to-end.

PREREQUISITE: plans 02 (packed keys) and 03 (vectorized eval) must be
landed first — hashing cost depends on both. If they aren't done, stop
and ask before proceeding.

This is the most invasive change in the perf workstream. Use max effort.
Honor CLAUDE.md: no new heavy deps, bazel test //... must pass after
EVERY phase, jaffle_shop deploy jar must hit 15/15 Succeeded after every
phase, polymarket deploy jar must hit 15/1/1. Land docs in the same PRs.

Follow the 8 phases. Phases 2 (HashAggregate) and 4 (HashJoin) are the
landmark phases — pause after each for a separate PR review and
performance numbers before moving on.

Spawn parallel sub-agents for: (a) property-testing K ∈ {1, 2, 8, 32}
equivalence in parallel with implementation, (b) profiling memory
pressure during the exchange materialization.

Stop and ask before: (a) changing the shape of CatalogView or
PhysicalPlan, (b) adding any skew-handling logic (defer to a separate
plan), (c) range-partitioning SortExec — that's Phase 7 and only after
the rest is stable.

Include in each PR description: before/after wall time on polymarket
end-to-end, K-by-K scaling chart (wall time vs K), async-profiler flame
graph confirming downstream operators are now running parallel.
```

### 05 — Smarter join planner

```
Read plans/perf/05-join-planner.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Follow the 4-phase plan. Phase 2 (planner integration) is the landmark;
land it in its own PR with side-by-side join timings on jaffle_shop and
polymarket. Selectivity constants in Phase 4 should be left at the
plan's defaults unless profiling shows a regression.

Spawn a parallel sub-agent for: writing the planner-level swap-correctness
tests (forced sizes via in-memory views) while implementing the
estimator.

Stop and ask before: (a) introducing any new statistics-collection
infrastructure (sketches, sampling), (b) changing the JoinKind ADT, (c)
adding a sort-merge join variant — that's plan 09 territory.

Include in PR description: jaffle_shop join task wall times before/
after, polymarket mart_* task wall times before/after, list of joins
where the planner now swaps build side.
```

### 06 — Expand parquet predicate pushdown

```
Read plans/perf/06-expand-parquet-pushdown.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Follow the 4 phases. Land Phase 1 (IS NULL/IS NOT NULL) and Phase 2 (IN)
together; they share a small amount of code. Phase 3 (BETWEEN) is
verification — add a test and update docs if it already works.

Critical correctness gate: for every new translation shape, add a test
that runs the same query both with and without pushdown (use a feature
flag or compare against in-memory results) and asserts identical row
sets. Over-pruning is the silent failure mode.

Skip Phase 4 (decimal) unless polymarket profiling shows decimal-column
filters are a hotspot — they shouldn't be on this dataset.

Stop and ask before: (a) attempting LIKE pushdown, (b) modifying anything
outside ParquetFilterTranslator and its test, (c) removing the FilterExec
above the scan.

Include in PR description: before/after row-group skip count and wall
time on a polymarket task that exercises the new shape.
```

### 07 — Push predicates and projections through joins

```
Read plans/perf/07-push-through-joins.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Follow the 5 phases. Phase 2 (outer-join filter pushdown) is the
correctness landmine — be conservative. Default to NOT pushing on outer
joins unless the filter is provably null-rejecting on the inner side;
when in doubt, leave the filter above the join.

For Phase 3, the index remap is the hard part. Add a debug-mode
assertion at every rewrite step that the rewritten plan's outputSchema
re-types correctly — catch index errors at plan time, not run time.

Spawn parallel sub-agents for: (a) building the comprehensive outer-join
correctness test matrix in parallel with Phase 2 implementation, (b)
profiling jaffle mart_* tasks before and after to quantify the win.

Stop and ask before: (a) pushing a filter on the null-extended side of
an outer join under any condition, (b) introducing a generic
optimizer-pass framework — keep it simple, two explicit passes called
in order, (c) touching anything outside sql/plan/ except SqlEngine.

Include in PR description: jaffle and polymarket mart task wall times
before/after; bytes decoded from parquet on the joined sides before/
after.
```

### 08 — Stream emit from breakers

```
Read plans/perf/08-stream-emit-breakers.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Before starting: profile a polymarket join + parquet-write task with
async-profiler to confirm the writer is genuinely starved by breaker CPU.
If writer wall time is <10% of total, the optimization is not worth the
complexity — stop and report.

Follow the 5 phases. Phase 5 is conditional on plan 04 (hash-partition
breakers) being landed; skip otherwise.

Concurrency correctness is the #1 risk. Use ArrayBlockingQueue; ensure
sentinel push in finally; test with queue size = 1 to flush out
deadlocks; test producer-exception propagation.

Spawn parallel sub-agents for: (a) building the multi-producer
contention tests in parallel with implementation, (b) measuring writer
overlap with async-profiler before/after.

Stop and ask before: (a) introducing a cancellation API on iterators
beyond StreamingResults.close, (b) touching SortExec (out of scope), (c)
modifying anything outside JoinExec, DistinctExec, and the new
StreamingResults file in Phases 1-4.

Include in PR description: latency-to-first-batch on a polymarket join
task before/after; total wall time before/after; flame graph
confirming writer threads now active during breaker CPU.
```

### 09 — Spill-to-disk for breakers

```
Read plans/perf/09-spill-to-disk.md and implement it end-to-end.

PREREQUISITES: plans 01 (k-way merge) and 04 (hash-partition breakers)
must be landed. If they aren't, stop and ask before starting — phases of
this plan strictly depend on them.

Use max effort. This is a multi-week structural change. Honor CLAUDE.md:
no new heavy deps (use stdlib + existing parquet stack for spill format),
bazel test //... must pass, jaffle_shop deploy jar must hit 15/15
Succeeded, polymarket deploy jar must hit 15/1/1. Land docs in the same
PRs.

Follow the 8 phases. CountDistinct AggState is excluded from spill in
v1 — document the heap limit instead of building serialization for
HashSets.

Critical correctness gate: every spill-capable operator must produce
bit-equal output with spill enabled (low threshold) vs disabled.
Build that parity check into the test suite for every Phase.

Spawn parallel sub-agents for: (a) writing the AggStateSerde tests in
parallel with implementation, (b) writing the stress-test target for
above-heap workloads, (c) verifying temp-file cleanup correctness
across normal and crash exits.

Stop and ask before: (a) attempting CountDistinct spill, (b) introducing
any new spill format beyond parquet, (c) modifying anything outside
core/ and the operator files in scope, (d) changing the spill threshold
default — it must be opt-in initially.

Include in PR description: result-equivalence test results, wall-time
overhead at sub-threshold sizes (must be ≤5%), and at-least-one
above-heap workload completing successfully (eg. polymarket full-day
orderbook).
```

### 10 — In-tree instrumentation framework

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

### 11 — Microbench + macro bench + regression guard

```
Read plans/perf/11-benchmarking.md and implement Sub-plans 3 + 4 in
order. Plan 10 (in-tree instrumentation framework) must be landed first.

Use max effort. Honor CLAUDE.md: no new heavy deps EXCEPT jmh-core +
jmh-generator-bytecode (Maven artifacts scoped to //benchmarks/micro/),
bazel test //... must pass, jaffle_shop deploy jar must hit 15/15
Succeeded, polymarket deploy jar must hit 15/1/1. Land docs in the same
PR.

Sub-plan 3 first — JMH harness via the programmatic Runner pattern.
START with the toolchain spike: get the SmokeBench building + running
before writing the real ones. If Pattern B (programmatic runner) breaks
on rules_scala 7.0.0, FALL BACK to Pattern A (Java wrapper classes
delegating to Scala). If both fail within a reasonable spike, stop and
ask before going stdlib-only.

CRITICAL: DisabledOverheadBench is the most important benchmark in the
suite. It gates plan 10's disabled-path invariant at < 1%. If it
reports anything above that, plan 10 has a regression that must be
fixed before continuing.

Then Sub-plan 4 — macro bench runner + bench_diff + baselines +
perf-tagged regression test + ALL the docs (this is the bulk of the
work). The --force-spill sysprop is the cleanest pattern for the
spill-on baseline; honor it in DataJob.buildExecutionOptions. The
perf-tagged regression test uses in-process invocation of
DirectoryJobLoader rather than the deploy jar — simpler Bazel wiring,
absorbed by a looser threshold.

Spawn parallel sub-agents for: (a) writing the macro bench BUILD files
in parallel with the runner code, (b) capturing both jaffle baselines
in parallel iterations, (c) drafting the docs.

Stop and ask before: (a) adding non-baseline Maven deps, (b) changing
the schema of _run.json (separate from _perf.json — _run.json is
load-bearing), (c) modifying anything in examples/ (jaffle, polymarket).

Include in PR description: bench_diff self-diff exit 0, regression
test result, macro_bench_runner output snippet on jaffle, the count of
new docs files / lines of doc.
```

### 12 — Materialize multiply-referenced CTEs once

```
Read plans/perf/12-cte-materialization.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy SQL deps (JSqlParser is AST-only;
reuse MaterializedView, do NOT add an embedded DB), bazel test //... must pass,
jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy jar must hit
15/1/1. Land docs in the same PR per CLAUDE.md "Required workflow".

Follow the 4-phase plan. Phase 1 (structure, inline-only resolver) MUST pass
every existing CTE test byte-identically before you add any materialization —
land it as its own commit. Phase 2 (materialize refs>=2) is the landmark; its
compute-once test (a counting CatalogView asserting the source is read once
under an N-reference CTE) is the proof the feature works.

Reuse MaterializedView.materializeInParallel via a small PhysicalPlanView
adapter. Do NOT add a new sealed LogicalPlan subtype — resolve every CTE
reference to either an inlined body or a LogicalScan over a MaterializedView
before the optimizer/physical-planner run, so their exhaustive matches stay
untouched.

Spawn a parallel sub-agent for: writing the counting-view compute-once and
determinism-fence tests while the main agent implements the resolver.

Stop and ask before: (a) the three "decisions to confirm" in the plan
(nested-WITH scope, size-cap policy, single-ref fence), (b) adding spill-backed
materialization (that's a plan 09 tie-in, defer), (c) changing the public
shape of CatalogView or PhysicalPlan, (d) introducing a generic optimizer-rule
framework — keep withChildren a plain structural primitive.

Include in PR description: the compute-once test result, jaffle + polymarket
e2e status, and wall-time before/after on a 2x- and 4x-referenced aggregate CTE.
```

## Notes on running these in fresh sessions

- Each prompt is self-contained — the sub-agent reads the plan from
  disk and acts. Don't paste the plan body inline; it's already in
  `plans/perf/<file>.md`.
- The "spawn parallel sub-agents" lines tell the launched session it
  may use the Agent tool with sub-agents (eg. `Explore`, `Plan`,
  `general-purpose`) for parallel work — particularly research, test
  scaffolding, and profiling that can happen alongside implementation.
- If a launched session hits a "stop and ask" condition, it will surface
  back to you for a decision rather than guessing.
- Profile numbers belong in PR descriptions, not in this repo's docs.
  Docs describe steady state; PR descriptions justify the change.
