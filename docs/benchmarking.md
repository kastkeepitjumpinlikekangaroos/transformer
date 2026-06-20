# Benchmarking and performance instrumentation

How to enable, measure, compare, and gate on the transformer engine's
performance. Covers the in-tree instrumentation framework (the per-task
`_perf.json` files, the operator counter set), the JMH microbench harness
under `benchmarks/micro/`, the macro-bench runner under `benchmarks/macro/`,
and the perf-tagged regression test that ties them together.

Companion to [architecture.md §2d](architecture.md#2d-per-operator-instrumentation-opt-in)
(the planner-side wrap pattern) and [conventions.md](conventions.md#counter-discipline)
(the "Counter discipline" sub-rules).

## 1. Enabling instrumentation

Instrumentation is **disabled by default**. With `ExecutionOptions.metricsEnabled
= false` the operator tree returned by `PhysicalPlanner.plan` is the
un-wrapped baseline — one branch + load in `PhysicalPlanner.plan` is the
only added cost. Enable by one of three routes; the per-task option takes
precedence when both are set.

| Source | How | Precedence |
|---|---|---|
| Per-task `options.metrics` in `output.json` | `"metrics": "true"` (or `"1"`) on a task's `output.json` `options` map | wins when explicitly present |
| Sysprop `transformer.metrics.enabled` | `-Dtransformer.metrics.enabled=true` on the JVM command line | applies when the per-task value is unset |
| Env var `TRANSFORMER_METRICS_ENABLED` | `TRANSFORMER_METRICS_ENABLED=1 java -jar ...` | same as the sysprop; sysprop wins if both are set |

Parse semantics: case-insensitive `"true"` / `"1"` → true; anything else
(including typos like `"yes"`) → false. This mirrors the same parser
[`ExecutionOptions.fromOutputOptions`](../src/main/scala/com/transformer/core/ExecutionOptions.scala)
uses for `options.spill`.

When enabled on a task that also writes an `outputFile`, a sibling file
`_perf.json` lands in the task's output directory next to `_run.json`.
For tasks without an `outputFile` (memory-only feeders) metrics still
populate inside the operator tree but no on-disk record is written —
there is no place to put it.

The schema of `_perf.json` lives in
[`TaskMetricsRecord.scala`](../src/main/scala/com/transformer/core/metrics/TaskMetricsRecord.scala).
A current run is `schemaVersion: 1`; consumers should reject older
versions rather than back-fill.

## 2. What counters exist and what they mean

Every operator declares its custom counters as named `Idx<Name>: Int`
constants in its companion object, plus a parallel `IdxCounterNames:
Array[String]` whose length matches the highest `Idx` + 1
(asserted by [`OperatorCountersTest`](../src/test/scala/com/transformer/core/metrics/OperatorCountersTest.scala)).
The names listed below are the keys you see inside each operator's
`counters` object in `_perf.json`.

The names below are the authoritative list, but the `IdxCounterNames`
arrays in each operator file are the source of truth — link in for the
canonical inventory.

### Scan (parquet only)

[`ScanExec`](../src/main/scala/com/transformer/sql/exec/PhysicalPlan.scala)

| Counter | Meaning |
|---|---|
| `bytesRead` | Total compressed bytes read from parquet files attributed to this scan partition. CSV / in-memory scans leave at zero. |
| `rowGroupsRead` | Row groups whose column data was actually decoded. |
| `rowGroupsSkipped` | Row groups proven non-matching by pushdown statistics (no column data read). |
| `predicatePushedDownCount` | 1 when the scan carried a translated `FilterPredicate`; 0 otherwise. |

### HashAggregate

[`HashAggregateExec`](../src/main/scala/com/transformer/sql/exec/AggregateExec.scala) (also covers `Distinct`, which is shape-identical with no value columns)

In-memory counters:

| Counter | Meaning |
|---|---|
| `groupCount` | Distinct group keys emitted. |
| `hashMapPeakSize` | Maximum size of the in-memory keymap at any point during the partial-aggregate phase. |
| `keyCodecPath` | 0 = LongHashMap fast path, 1 = `PackedBytesCodec`, 2 = `ObjectArrayCodec`, 3 = `SingleObjectKeyCodec` (single non-packable column — e.g. `GROUP BY market_id`). |
| `mergeNanos` | Time spent merging partial AggStates from sibling partitions / spill runs. |
| `partialAggregateNanos` | Time spent in the per-batch update loop. |

Spill counters (zero when the operator never spilled):

| Counter | Meaning |
|---|---|
| `spillEvents` | Number of times the in-memory buffer crossed the threshold and flushed. |
| `bytesSpilled` | Cumulative bytes written to disk across every flush. |
| `spillRunsWritten` | Total run files written (one per flush). |
| `spillRunsRead` | Run files read back at emit time. |
| `serdeWriteNanos` | Time inside `AggStateSerde.serialize` summed across every flushed AggState. |
| `serdeReadNanos` | Time inside `AggStateSerde.deserialize` summed across every restored AggState. |
| `mergeFromSpillNanos` | Time folding spilled runs back into the final map at emit time. |
| `peakInMemoryBytes` | High-water-mark of the byte estimator across the partial-aggregate phase. |

### HashJoin

[`HashJoinExec`](../src/main/scala/com/transformer/sql/exec/JoinExec.scala)

In-memory counters:

| Counter | Meaning |
|---|---|
| `buildSideRows` | Rows ingested into the build-side keymap. |
| `probeSideRows` | Rows probed against the build map. |
| `matchedRows` | Probe rows that found at least one match. |
| `unmatchedRows` | Probe (or build, for LEFT outer) rows that surfaced as unmatched in the outer-join emission. |
| `buildNanos` | Time spent building the keymap. |
| `probeNanos` | Time spent probing. |
| `keyCodecPath` | 0 = LongHashMap fast path, 1 = `PackedBytesCodec`, 2 = `ObjectArrayCodec`, 3 = `SingleObjectKeyCodec` (single non-packable column — e.g. `GROUP BY market_id`). |

Grace-hash spill counters:

| Counter | Meaning |
|---|---|
| `bucketCount` | Number of disk buckets the join used (fixed at 16 when spill triggers; 0 when spill never triggered). |
| `bytesSpilledBuild` | Bytes written to the build-side bucket files. |
| `bytesSpilledProbe` | Bytes written to the probe-side bucket files. |
| `bucketsLoadedNanos` | Time loading each `build_k` partition into memory before probing `probe_k`. |
| `peakBucketBytes` | Largest single-bucket size observed across the build phase. |

### Sort

[`SortExec`](../src/main/scala/com/transformer/sql/exec/SortExec.scala)

| Counter | Meaning |
|---|---|
| `comparatorCalls` | Total comparator invocations (heap operations × 2). |
| `inputRows` | Rows pulled from the child iterator. |
| `outputRows` | Rows emitted. Should match `inputRows`. |
| `path` | 0 = small-N concat + `Arrays.sort`, 1 = K-way heap merge. |
| `runsWritten` | Sorted runs flushed to disk (zero on the in-memory path). |
| `bytesSpilled` | Bytes written to spill files. |
| `mergeRunsActive` | Number of sources the final K-way merge consumed (in-memory tails + disk runs). |
| `mergeNanos` | Time inside the K-way merge loop. |
| `runDecodeNanos` | Time decoding spilled parquet runs back into batches during the merge. |

### Exchange

[`ExchangeExec`](../src/main/scala/com/transformer/sql/exec/ExchangeExec.scala)

The exchange's counter array is shaped per-instance, because the planner
picks K (shard count) per breaker. The shape is
`[keyNullCount, rowsRoutedShard0, rowsRoutedShard1, ..., rowsRoutedShard{K-1}]`.

| Counter | Meaning |
|---|---|
| `keyNullCount` | Rows routed via the NULL policy (`NullsToLast` for GROUP BY / DISTINCT / PARTITION BY; `NullsToZero` for equi-join probes). |
| `rowsRoutedShardN` | Rows routed to shard N. Skew detection: compare max/min/median across shards. |

### Window

[`WindowExec`](../src/main/scala/com/transformer/sql/exec/WindowExec.scala)

| Counter | Meaning |
|---|---|
| `partitionCount` | Distinct partitions observed (sum across all input batches). |
| `peakPartitionRows` | Largest single-partition row count. |
| `frameEvalNanos` | Time inside per-frame window-function evaluation. |
| `partitionSpillEvents` | Partition-internal spill events when bucketed spill triggered. |
| `bytesSpilled` | Bytes written to bucket files. |
| `peakBufferedRows` | High-water-mark of buffered rows during the in-memory path. |

## 3. Microbenchmark harness

Lives under [`benchmarks/micro/`](../benchmarks/micro/). JMH integration via
Pattern B (programmatic Runner) — see
[`benchmarks/micro/BUILD.bazel`](../benchmarks/micro/BUILD.bazel) for the
toolchain wiring rationale.

Build and run the full suite:

```bash
bazel build //benchmarks/micro:bench_all_deploy.jar
java -jar bazel-bin/benchmarks/micro/bench_all_deploy.jar \
    -w 3s -i 5 -f 1 -r 3s \
    -rf json -rff /tmp/bench-micro-$(date +%Y%m%d).json
```

Smoke run (1s warmup, 3 measurement iterations, 1 fork):

```bash
java -jar bazel-bin/benchmarks/micro/bench_all_deploy.jar -w 1s -i 3 -f 1
```

Target a single benchmark by glob:

```bash
java -jar bazel-bin/benchmarks/micro/bench_all_deploy.jar 'com.transformer.bench.KeyCodecBench.*'
```

### Bench inventory

| File | What it measures |
|---|---|
| `SmokeBench` | The trivial "is JMH wiring alive" smoke test. |
| `KeyCodecBench` | `KeyCodec.encode` packed path, object-array path, `LongHashMap.getOrInsert` cold + warm. |
| `ExprEvalBench` | `Expr.evalVec` for `LitExpr` / `ColRefExpr` / `BinOpExpr` arithmetic / `BinOpExpr` comparison / `IsNullExpr`. |
| `SortComparatorBench` | Single-key Long, multi-key with mixed ASC/DESC, with NULLs. |
| `ParquetDecodeBench` | 1M-row decode loop from a fixture parquet. |
| `ColumnarBatchBench` | `ColumnVector.allocate` per-type cost, `selectByBoolean` fast-path vs full-copy. |
| `DisabledOverheadBench` | Filter→Project pipeline with `metricsEnabled = false` vs an un-wrapped baseline. Gate: diff < 1%. Catches regressions to the disabled-path invariant. |
| `AggStateSerdeBench` | Round-trip throughput for every spillable `AggState` subtype (Count, Sum, Avg, Min, Max, CountIf, Moment, Covar, Corr). |
| `SpillEstimateBench` | `Spill.estimateBytes(ColumnarBatch)` overhead across batch shapes. Target: < 5% of a typical aggregate's per-batch work. |
| `ExternalSortMergeBench` | K-way merge over N sorted runs read from spilled parquet files. |

Output format is JMH's standard JSON. The `DisabledOverheadBench` result
is the most important — a regression there means the disabled-path
invariant (zero added cost when metrics are off) has been violated and
must be fixed before continuing.

## 4. Macro bench workflow

Lives under [`benchmarks/macro/`](../benchmarks/macro/). The macro bench
captures end-to-end per-task wall-time statistics over a real workload by
invoking the deploy jar in a subprocess N times with metrics enabled,
harvesting every `_perf.json`, and aggregating across iterations.

### Capture a baseline

```bash
# spill-off baseline
bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
bazel run //benchmarks/macro:macro_bench_runner -- \
    --workload jaffle \
    --iterations 5 \
    --warmup 1 \
    --output benchmarks/baseline/jaffle_shop.json

# spill-on baseline (set transformer.macro.force_spill=true on every task)
bazel run //benchmarks/macro:macro_bench_runner -- \
    --workload jaffle \
    --iterations 5 \
    --warmup 1 \
    --force-spill \
    --output benchmarks/baseline/jaffle_shop_spill.json

git add benchmarks/baseline/jaffle_shop.json benchmarks/baseline/jaffle_shop_spill.json
```

The `--force-spill` flag sets the JVM sysprop
`transformer.macro.force_spill=true` on each spawned subprocess. The runner
([`DataJob.buildExecutionOptions`](../src/main/scala/com/transformer/job/DataJob.scala))
honors that sysprop globally: every task's `ExecutionOptions.spillEnabled`
becomes true regardless of the per-task `output.json` config. This mirrors
the existing global override pattern that `TRANSFORMER_METRICS_ENABLED`
uses for metrics — one sysprop, every task — and avoids editing the
workload's checked-in config.

### Macro-bench arguments

| Flag | Default | Meaning |
|---|---|---|
| `--workload <jaffle\|polymarket\|custom>` | required | Selects the deploy jar + job dir to drive. `custom` requires `--deploy-jar` + `--job-dir`. |
| `--job-dir <path>` | workload-specific | Overrides the workload's default job dir. |
| `--deploy-jar <path>` | workload-specific | Overrides the workload's default deploy jar path. |
| `--iterations <N>` | 5 | Measurement iterations after warmup. |
| `--warmup <K>` | 1 | Warmup iterations to discard. |
| `--output <path>` | required | Where to write the result JSON. |
| `--output-dir <path>` | `${java.io.tmpdir}/transformer-macro-<workload>` | Directory the workload writes into each iteration. |
| `--execution-time <ISO>` | `2026-01-01T00:00:00Z` (set by the workload itself) | Override the workload's templated execution time. |
| `--force-spill` | unset | Pass `-Dtransformer.macro.force_spill=true` to every subprocess. |
| `--xmx <size>` | `2g` | JVM heap size per iteration. Default fits the jaffle workload comfortably; the polymarket workload needs `--xmx 12g` (or larger) to avoid GC-thrashing — see [`examples/polymarket/BUILD.bazel`](../examples/polymarket/BUILD.bazel) for the rationale. |

### Output format

```json
{
  "schemaVersion": 1,
  "workload": "jaffle",
  "iterations": 5,
  "warmup": 1,
  "forceSpill": false,
  "capturedAt": "...",
  "_comment": "Machine-dependent baseline. Re-capture by ...",
  "tasks": [
    {
      "taskName": "customers",
      "wallNanosMedian": ...,
      "wallNanosP95": ...,
      "wallNanosStddev": ...,
      "wallNanosSamples": [...],
      "operatorTreeMedian": { ...the operator tree from the median run... }
    },
    ...
  ]
}
```

`wallNanos*` is the per-task `startedAt → finishedAt` delta (matches what
the human-readable run summary surfaces). The per-operator self-time
counters live inside `operatorTreeMedian` for cross-operator attribution.

### Diff two runs

```bash
bazel run //benchmarks/macro:bench_diff -- \
    benchmarks/baseline/jaffle_shop.json /tmp/jaffle_fresh.json

# Tighten the threshold:
bazel run //benchmarks/macro:bench_diff -- \
    benchmarks/baseline/jaffle_shop.json /tmp/jaffle_fresh.json \
    --threshold 0.1
```

Exit code is 0 when every task's median is within `--threshold` (default
20%) of the baseline; non-zero when any task regressed by more.

Self-diff smoke test:

```bash
bazel run //benchmarks/macro:bench_diff -- \
    benchmarks/baseline/jaffle_shop.json \
    benchmarks/baseline/jaffle_shop.json
echo "Exit: $?"  # 0
```

## 5. Regression guard

The perf-tagged JUnit test
[`JaffleRegressionTest`](../src/test/scala/com/transformer/bench/JaffleRegressionTest.scala)
runs jaffle twice (spill-off and spill-on), parses the `_perf.json` files,
and asserts no task regressed by more than the per-mode threshold.

```bash
# Opt-in via the perf tag filter:
bazel test //... --test_tag_filters=perf

# Or target the test directly:
bazel test //src/test/scala/com/transformer/bench:jaffle_regression_test --test_tag_filters=perf
```

Default `bazel test //...` excludes the test via the
`--test_tag_filters=-perf` line in [`.bazelrc`](../.bazelrc) — the
baselines are machine-dependent, so the regression assertion is only
meaningful on the developer machine that captured them.

### Thresholds

| Mode | Threshold | Why |
|---|---|---|
| Spill-off | 20% | Tight enough to catch real regressions, loose enough to absorb in-process JIT-warmup variance (the test invokes `DataJob.run` in-process rather than via the deploy jar — see the test's class scaladoc for the trade-off rationale). |
| Spill-on | 25% | Looser to absorb spill's higher inherent variance — flushing thresholds, parquet writer flush boundaries, temp-file sync timing. |

### Re-capture baselines after intentional perf changes

After a change that intentionally moves the wall-time numbers (e.g. a new
operator-level optimization), the regression test will fail until the
baselines are re-captured. Procedure:

```bash
# 1. Land the optimization on a branch.
# 2. Re-build the jaffle deploy jar.
bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar

# 3. Re-capture both baselines.
bazel run //benchmarks/macro:macro_bench_runner -- \
    --workload jaffle --iterations 5 --warmup 1 \
    --output benchmarks/baseline/jaffle_shop.json

bazel run //benchmarks/macro:macro_bench_runner -- \
    --workload jaffle --iterations 5 --warmup 1 \
    --force-spill \
    --output benchmarks/baseline/jaffle_shop_spill.json

# 4. Sanity-check: self-diff should exit 0.
bazel run //benchmarks/macro:bench_diff -- \
    benchmarks/baseline/jaffle_shop.json \
    benchmarks/baseline/jaffle_shop.json

# 5. Commit the updated baselines in the same PR as the optimization.
git add benchmarks/baseline/jaffle_shop.json benchmarks/baseline/jaffle_shop_spill.json
```

The polymarket baseline is dev-local (`benchmarks/baseline/local.polymarket.json`,
gitignored) — polymarket numbers belong in PR descriptions, not the repo,
because the dataset checkout at `~/Downloads/archive/` isn't part of the
repo and machine timings vary widely with disk speed. The
`polymarket.json.example` template documents the expected shape.

## 6. Limitations

- **ThreadMXBean allocation accounting is HotSpot-specific.**
  `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` works on
  Oracle / OpenJDK / Temurin (i.e. every JVM we test against) but not on
  every JVM. Non-HotSpot JVMs surface `allocBytes: -1` in `_perf.json`;
  every other field stays accurate.
  See [`MetricsCollector.currentAllocatedBytes`](../src/main/scala/com/transformer/core/metrics/MetricsCollector.scala)
  for the `isInstanceOf` guard.
- **Per-thread CPU time** is enabled by `MetricsCollector` at class load
  via `setThreadCpuTimeEnabled(true)`. Most JVMs support it but some
  refuse — `cpuNanos: -1` in `_perf.json` indicates the JVM declined.
- **Macro bench variability across machines** is real. The regression
  threshold accommodates ~20% variance on the developer machine that
  captured the baselines; running on a slower or faster machine will
  produce numbers that drift outside the threshold even without any code
  change. Re-capture before relying on the regression test on a new
  machine.
- **GUI hydration ignores `_perf.json`.** The GUI reads `_run.json` only;
  a future GUI integration PR can extend `TaskDetailsPanel` to render
  the operator tree from `_perf.json`. The schema already reserves
  `id` / `name` / `children` for this.
- **Most-recent-only on disk.** `_perf.json` overwrites in place on
  rerun — historical perf data lives in `benchmarks/macro/` outputs and
  the checked-in baselines, not in the task output directory.
