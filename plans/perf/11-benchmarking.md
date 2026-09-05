# Plan 11: Microbench + macro bench + regression guard

> Status: landed (Sub-plans 3 + 4 of the batched instrumentation work) · Tier: cross-cutting · Effort: 3-5 days · Risk: medium (JMH integration)
>
> Prereqs satisfied: plan 10 (in-tree instrumentation framework) is
> landed — this plan consumes the `_perf.json` schema and the
> per-operator counters plan 10 wired up.

## Goal

Add the measurement surface that catches regressions: a JMH
microbench harness for hot kernels, a macro-bench runner that drives
real workloads end-to-end, two checked-in jaffle baselines (spill-off
and spill-on), and a perf-tagged JUnit test that fails CI if a refactor
regresses jaffle by more than 20% (spill-off) or 25% (spill-on).

This plan is the second half of the comprehensive instrumentation &
benchmarking work. The first half ([plan 10](10-instrumentation.md))
covers the in-tree metrics framework.

## Why it matters

Without this plan, nothing fails CI when a refactor makes jaffle 30%
slower. Today's only guard is "did the e2e jar still exit 0". Plan 10's
`_perf.json` records make the diagnostics legible, but the regression
gate is a separate concern — that lives here.

## Sub-plan 3 — JMH microbenchmark harness

JMH harness + 10 microbenchmarks for the hot kernels.

### Toolchain spike

`rules_scala` 7.0.0 has no first-class JMH support. Two viable
patterns:

- **A) APT in javac:** Java benchmark classes delegating to Scala via
  static methods. JMH's annotation processor runs in javac as designed.
- **B) Programmatic JMH runner:** Use JMH's runtime API
  (`new Runner(new OptionsBuilder()...)`) and register benchmarks
  manually. No annotation processor needed — pure Scala.

**Pattern B is the landed choice.** The build-time
`JmhBytecodeGenerator` runs over a `scala_library`'s output classes
and emits the `_jmhTest` wrapper classes JMH's runtime discovers.
See `benchmarks/micro/BUILD.bazel` for the integration shape.

### New files

- `MODULE.bazel` — add JMH deps:
  - `org.openjdk.jmh:jmh-core:1.37`
  - `org.openjdk.jmh:jmh-generator-bytecode:1.37`
- `benchmarks/micro/BUILD.bazel` — `bench_all` umbrella that runs every
  benchmark plus the `jmh_generate` build-time tool and the
  `bench_generated_jar` genrule that produces the `_jmhTest` wrapper
  classes.
- `benchmarks/micro/src/main/scala/com/transformer/bench/MicroBench.scala`
  — the runner. Programmatic JMH options builder; takes a benchmark
  name glob and the standard JMH CLI flags.
- `benchmarks/micro/src/main/scala/com/transformer/bench/KeyCodecBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/ExprEvalBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/SortComparatorBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/ParquetDecodeBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/ColumnarBatchBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/DisabledOverheadBench.scala`
  — gates the disabled-path cost from [plan 10](10-instrumentation.md)
  at < 1%. The most important benchmark in the suite.
- `benchmarks/micro/src/main/scala/com/transformer/bench/AggStateSerdeBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/SpillEstimateBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/ExternalSortMergeBench.scala`
- `benchmarks/micro/src/main/scala/com/transformer/bench/SmokeBench.scala`
  — the "is JMH alive" smoke test.

### Verification

```bash
bazel build //benchmarks/micro:bench_all_deploy.jar
java -jar bazel-bin/benchmarks/micro/bench_all_deploy.jar -w 3s -i 5 -f 1 -r 3s \
    -rf json -rff /tmp/bench-micro-$(date +%Y%m%d).json

# Critical assertion: DisabledOverheadBench diff < 1%. Anything higher
# means plan 10's disabled-path invariant has regressed and must be
# fixed before continuing.
```

## Sub-plan 4 — Macro bench + regression guard + docs

### Macro bench

`benchmarks/macro/MacroBenchRunner` drives a deploy jar N times in a
subprocess (fresh JVM each iteration so no JIT carryover), harvests
every `_perf.json` via the job-level `JobRunRecord.perfManifest`
field, aggregates per-task wall-time statistics (median / p95 /
stddev), and emits a single result JSON.

`benchmarks/macro/BenchDiff` compares two of those JSONs and exits
non-zero on regression. Default threshold is 20%; configurable via
`--threshold`.

`--force-spill` sets a JVM sysprop `transformer.macro.force_spill=true`
on each subprocess. `DataJob.buildExecutionOptions` honors that
sysprop globally: every task's `ExecutionOptions.spillEnabled` becomes
true regardless of per-task config. Mirrors the existing global
override pattern that `TRANSFORMER_METRICS_ENABLED` uses for metrics —
one sysprop, every task — and avoids editing the workload's checked-in
config.

### Baselines

- `benchmarks/baseline/jaffle_shop.json` — spill-off baseline. Capture
  via `bazel run //benchmarks/macro:macro_bench_runner -- --workload
  jaffle --iterations 5 --warmup 1 --output benchmarks/baseline/jaffle_shop.json`.
- `benchmarks/baseline/jaffle_shop_spill.json` — spill-on baseline,
  same command plus `--force-spill`. Captures spill bookkeeping
  overhead on a sub-threshold workload (target: ≤ 5% overhead vs the
  unspilled baseline).
- `benchmarks/baseline/polymarket.json.example` — template only. The
  real polymarket baseline is dev-local
  (`benchmarks/baseline/local.polymarket.json`, gitignored).
- `.gitignore` — `benchmarks/baseline/local.*.json` excluded.

### Regression test

`src/test/scala/com/transformer/bench/JaffleRegressionTest.scala` — JUnit
4 test tagged `perf`. Default `bazel test //...` excludes it via the
`--test_tag_filters=-perf` line in `.bazelrc`. Opt in via
`bazel test //... --test_tag_filters=perf`.

The test:
1. Sets `transformer.metrics.enabled=true` (and `transformer.macro.force_spill=true`
   for the spill-on variant).
2. Runs jaffle in-process via `DirectoryJobLoader.load(...) + .run()`
   for 2 warmup + 3 measurement iterations, each into its own temp dir.
3. Reads every `_perf.json` produced.
4. Compares per-task wall-time medians against the matching baseline.
5. Fails on > 20% (spill-off) or > 25% (spill-on) regression.

The in-process route trades some accuracy (JIT carryover across
iterations vs. subprocess-fresh) for simpler Bazel wiring. The looser
threshold absorbs that trade-off; a tighter gate would require the
subprocess route.

### Docs

These all land in this batched commit:
- `docs/benchmarking.md` (new file) — full consumer-facing surface.
- `docs/architecture.md` — § 2d "Per-operator instrumentation".
- `docs/conventions.md` — "Counter discipline" sub-rules.
- `docs/extending.md` — "Add a counter to an operator" recipe.
- `docs/gotchas.md` — ThreadMXBean HotSpot-specific path + metrics
  opt-in precedence.
- `docs/testing.md` — new test targets + the perf-tagged regression
  guard procedure.
- `docs/code-map.md` — `core/metrics/`, `benchmarks/{micro,macro,baseline}/`.
- `README.md` — "Performance instrumentation" subsection.

### Verification

```bash
# Capture both baselines (spill-off and spill-on)
bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
bazel run //benchmarks/macro:macro_bench_runner -- \
    --workload jaffle --iterations 5 --warmup 1 \
    --output benchmarks/baseline/jaffle_shop.json
bazel run //benchmarks/macro:macro_bench_runner -- \
    --workload jaffle --iterations 5 --warmup 1 --force-spill \
    --output benchmarks/baseline/jaffle_shop_spill.json

# Self-diff should report no regressions and exit 0
bazel run //benchmarks/macro:bench_diff -- \
    benchmarks/baseline/jaffle_shop.json benchmarks/baseline/jaffle_shop.json
echo "Exit: $?"

# Regression guard against the just-captured baseline
bazel test //src/test/scala/com/transformer/bench:jaffle_regression_test \
    --test_tag_filters=perf
```

## Risks

1. **JMH + Bazel + Scala integration is a real spike.** Pattern B
   (programmatic Runner via the bytecode generator) is the landed
   choice. If a future rules_scala upgrade breaks the bytecode
   generator pipeline, the fallback is Pattern A (Java wrapper
   classes).
2. **Macro bench variability across developer machines** is real. The
   regression guard's threshold accommodates ~20-25% variance on the
   developer machine that captured the baselines; running on a slower
   or faster machine will produce numbers that drift outside the
   threshold even without any code change. Document the regeneration
   procedure in `docs/benchmarking.md`.
3. **Pre-existing `_perf.json` files** from earlier iterations can
   poison the harvest if iteration dirs aren't cleaned. The macro
   bench writes to a fresh per-iter dir each time; the regression test
   uses `Files.createTempDirectory` per iteration.

## Deferred (not in scope)

- **GUI integration.** A future PR can extend `TaskDetailsPanel` to
  render the operator tree from `_perf.json` (TreeTableView with
  rowsIn / wallNanos / exclusiveNanos columns). The schema reserves
  the necessary fields (`id`, `children`, stable `name`) so this is
  purely additive when it lands.
- **OpenTelemetry / Prometheus push.** Out of scope for a single-node
  lib.
- **Async-profiler integration as a Bazel target.** Async-profiler is
  a developer-launches-it-separately tool; integrating it adds
  toolchain weight for marginal value.
- **CI-gated regression** with subprocess-fresh JVM per iteration. The
  in-process route is simpler and sufficient for the developer-side
  guard; a true CI gate would want the subprocess approach.

## Critical files reference

- `MODULE.bazel` — JMH deps (Sub-plan 3 only).
- `benchmarks/micro/` — JMH microbench harness.
- `benchmarks/macro/MacroBenchRunner.scala` — driver.
- `benchmarks/macro/BenchDiff.scala` — comparator.
- `benchmarks/baseline/jaffle_shop.json`,
  `benchmarks/baseline/jaffle_shop_spill.json` — checked-in baselines.
- `src/test/scala/com/transformer/bench/JaffleRegressionTest.scala` —
  perf-tagged regression test.
- `src/main/scala/com/transformer/job/DataJob.scala` —
  `ForceSpillPropertyName` constant + sysprop / env-var resolution.
- `.bazelrc` — `--test_tag_filters=-perf` default.

## Launch prompt

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
