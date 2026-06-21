# Code map

Where the bulk of the code lives, and the external reference material that's
useful when a request is ambiguous. Keep this list trimmed to the actually-
biggest files (or the ones that disproportionately attract changes) — it's a
navigation hint, not a comprehensive directory listing.

## File-size hot spots

- `sql/plan/LogicalBuilder.scala` (~960 LOC) — largest file in `sql/plan`.
  Pattern matches every JSqlParser expression node, dispatches SELECT shapes,
  and (for
  outermost-scope `WITH` CTEs) emits placeholder `PendingCteView` scans +
  collects `CteDef`s into a `BuiltQuery`, which `sql/exec/CteResolver.scala`
  later inlines or materializes (see [architecture §6b](architecture.md#6b-cte-resolution-inline-vs-materialize)).
  If you're adding a syntax feature, this is probably where it lands.
- `gui/JobSession.scala` (~795 LOC) — mutable FX-thread state for the GUI;
  also tracks per-input UI state (Pending/Loading/Loaded/Failed) now that
  inputs flow through the unified scheduler.
- `sql/exec/AggregateExec.scala` (~1740 LOC) — biggest file. Every `AggState` subclass
  plus the codec / LongHashMap GROUP BY paths. Primitive states
  (`CountStarState`, `CountState`, `CountIfState`, `LongSumState`,
  `DoubleSumState`, `AvgState`, `MinMaxState`) override `updateBatch` to
  read typed `ColumnVector`s directly for the no-GROUP-BY fast path.
  Each spillable state additionally implements `writeSelf` / `readSelf`
  (plan 09 Phase 4); the `AggSpiller` helper at the bottom of the file
  handles per-partition parquet flush + fold-back at emit time.
- `sql/exec/AggStateSerde.scala` (~110 LOC) — dispatcher that pairs
  `AggExpr` types with the right `AggState` subtype for round-trip
  serialization. `isSpillable` gates the operator-level switch
  (`CountDistinct` is the only false case).
- `core/Spill.scala` (~200 LOC) — temp-dir lifecycle, per-operator subdir
  allocation, `ColumnarBatch` byte estimator, and the
  `effectiveThresholdBytes` helper that turns `ExecutionOptions` into a
  concrete flush threshold.
- `core/ExecutionOptions.scala` (~120 LOC) — per-query knobs threaded from
  `DataJob.runOneTask` to spill-capable operators. `fromOutputOptions`
  parses the `output.json` `options` map with tolerance for typos.
- `job/DataJob.scala` (~875 LOC) — runner orchestration: unified input + task
  DAG scheduler (`runUnifiedDag`), writeOutput, validation re-read, per-status
  `_run.json` writes + per-failure `_validation-<slug>.csv` sample writes +
  per-job `job.json` write + consistency checks.
- `sql/plan/Expr.scala` (~625 LOC) — `Expr` ADT plus `eval` and `evalVec`
  per subtype. `FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr` each carry
  an `evalVec` override; the rest of the hot subtypes delegate to `VecOps`.
- `sql/plan/Funcs.scala` (~550 LOC) — scalar function registry. `Funcs.apply`
  is the row-form dispatcher; `Funcs.applyVec` + the `VecFuncs` object
  carry the vectorized implementations for the hot subset (COALESCE,
  string ops, ABS/FLOOR/CEIL/ROUND/TRUNC, IF, NULLIF, SUBSTRING).
- `gui/ResultsTabPane.scala` (~630 LOC) — partition picker + background
  output loader + run-log rendering.
- `core/ColumnarBatch.scala` (~390 LOC) — defines ten `ColumnVector`
  subclasses. Adding a new `DataType` requires a new vector + companion case
  in `ColumnVector.allocate`.
- `gui/DagCanvas.scala` (~380 LOC) — Canvas drawing + pan/zoom/click; renders
  per-input load state alongside per-task status.
- `sql/exec/JoinExec.scala` (~1060 LOC) — equi-join build + probe paths
  with build/probe role mirroring, the LongHashMap fast path, and
  per-batch `evalVec` key extraction for computed join keys on both
  sides. Grace hash join (plan 09 Phase 6) is the additional
  `executeGraceHash` path that buckets both sides into K=16 disk parquet
  files via `fmix32(hash(joinKeys)) % K` and processes bucket pairs
  sequentially; per-bucket unmatched-build tracking preserves outer-join
  semantics.
- `sql/exec/WindowExec.scala` (~655 LOC) — partition, sort, frame
  computation for every supported window function; pre-computes per-spec
  partition/order keys per row during the single materialization pass.
  Bucketed spill (plan 09 Phase 7) routes child rows into K=16 disk
  parquet buckets by `fmix32(hash(partitionKey)) % K` and processes each
  bucket through the same in-memory pipeline — every row sharing a
  partition key collocates in one bucket so LAG/LEAD/frame correctness
  is preserved.
- `core/HashKeys.scala` (~910 LOC) — `KeyCodec` (`PackedBytesCodec`,
  `ObjectArrayCodec`, `EmptyKeyCodec`) + `BytesKey` / `ObjectArrayKey`
  wrappers + `LongHashMap[V]` (open-addressing primitive-long-keyed map for
  the single-Long fast path in HashAggregate / HashJoin). Used by every
  pipeline-breaking operator that keys into a HashMap (HashAggregate /
  HashJoin / Distinct / WindowExec partition keys). See
  [architecture.md §2a](architecture.md#2a-keycodec--packed-keys-for-pipeline-breakers).
- `core/Scheduler.scala` (~100 LOC) — the shared `ForkJoinPool` every parallel
  call site funnels through. Daemon threads, default size `2 × availableProcessors`
  (override via `transformer.scheduler.parallelism` system property or
  `TRANSFORMER_SCHEDULER_PARALLELISM` env var).
- `core/metrics/` — in-tree instrumentation framework. `MetricsCollector.scala`
  (global default + ThreadMXBean helpers + GC sample), `MeteredIterator.scala`
  (the `final class` wrapper applied to operator iterators at planner time),
  `MetricsNode.scala` (`Array[LongAdder]` counter container, one per
  operator), `OperatorMetrics.scala` + `QueryMetrics.scala` (immutable
  snapshots + hand-rolled serializers), `TaskMetricsRecord.scala` (the
  `_perf.json` on-disk record). `JsonMini.scala` is the stdlib-only JSON
  reader scoped to this package (the `job/` module's `Json.scala` sits
  above `core/` in the deps DAG; replicating just enough here keeps the
  dependency graph one-way). See [docs/benchmarking.md](benchmarking.md)
  for the consumer-facing surface and [architecture.md §2d](architecture.md#2d-per-operator-instrumentation-opt-in)
  for the planner-wrap pattern.
- `benchmarks/micro/` — JMH microbench harness (Pattern B / programmatic
  Runner). 10 benchmarks covering hot kernels (`KeyCodec`, `ExprEval`,
  `SortComparator`, `ParquetDecode`, `ColumnarBatch`, `AggStateSerde`,
  `SpillEstimate`, `ExternalSortMerge`) plus `DisabledOverheadBench`
  which gates the disabled-path overhead at < 1% and `SmokeBench` for
  "is JMH wiring alive". JMH artifacts are scoped to this package — no
  production / example deploy_jar pulls them in.
- `benchmarks/macro/` — macro-bench runner + diff tool. `MacroBenchRunner`
  invokes a deploy jar N times via ProcessBuilder with
  `TRANSFORMER_METRICS_ENABLED=1`, aggregates per-task wall-time stats
  (median / p95 / stddev) and emits a single result JSON. `BenchDiff`
  compares two of those JSONs and exits non-zero on regression. Driven
  by the perf-tagged regression test under
  `//src/test/scala/com/transformer/bench/` and by the manual baseline
  regeneration procedure documented in `docs/benchmarking.md`.
- `benchmarks/baseline/` — checked-in macro-bench baselines.
  `jaffle_shop.json` (spill-off) and `jaffle_shop_spill.json` (spill-on)
  are the regression-guard baselines for the jaffle workload;
  `polymarket.json.example` is a template (the real polymarket baseline
  is gitignored — `local.polymarket.json`). Re-capture by running the
  macro bench runner; see `docs/benchmarking.md` for the procedure.

### Test suites

- `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala` (~1030 LOC)
  — the parity gate for every `Expr.evalVec` override. New overrides extend
  this first; see [testing.md](testing.md) for the coverage matrix.

## Useful pointers

- **The brief:** [`INIT.md`](../INIT.md) at the repo root. Read it if a
  request is unclear about intended behavior.
- **The reference project:** `~/grid-game` — Bazel + rules_scala setup,
  BUILD file conventions, and JavaFX-on-Bazel wiring (platform classifier
  jars, Canvas + GraphicsContext rendering, mutable-state-with-manual-
  refresh UI pattern) match this repo's GUI module directly.
- **JSqlParser docs:** search `net.sf.jsqlparser` on Maven Central / GitHub.
  The jar at
  `/private/var/tmp/_bazel_owenchristie/.../jsqlparser-5.0.jar` can be `unzip
  -l`'d to inspect available classes — useful when guessing class names.
