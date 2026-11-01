# Code map

Where the bulk of the code lives, and the external reference material that's
useful when a request is ambiguous. Keep this list trimmed to the actually-
biggest files (or the ones that disproportionately attract changes) — it's a
navigation hint, not a comprehensive directory listing.

## File-size hot spots

- `sql/plan/LogicalBuilder.scala` (~1880 LOC) — largest file in `sql/plan`.
  Pattern matches every JSqlParser expression node, dispatches SELECT shapes,
  and (for
  outermost-scope `WITH` CTEs) emits placeholder `PendingCteView` scans +
  collects `CteDef`s into a `BuiltQuery`, which `sql/exec/CteResolver.scala`
  later inlines or materializes (see [architecture §6b](architecture.md#6b-cte-resolution-inline-vs-materialize)).
  If you're adding a syntax feature, this is probably where it lands.
- `sql/plan/UnsupportedClauses.scala` (~840 LOC) — the rejection pass that runs
  before binding, and its audit: for each of the ~60 JSqlParser classes the
  binder reaches, every getter the binder does not read, as a rejection (clause,
  advice, example SQL) or inert (with the reason). Most of the length is that
  table, and the second table, `Constructs`: what SQL calls each construct the
  binder refuses by name, which `refusal` turns into its message. Teaching
  `LogicalBuilder` a clause means deleting its entry here;
  `sql/plan:unsupported_clauses_test` enforces it (see
  [architecture §10](architecture.md#10-nothing-the-parser-accepts-is-silently-dropped)).
- `gui/JobSession.scala` (~900 LOC) — mutable FX-thread state for the GUI;
  also tracks per-input UI state (Pending/Loading/Loaded/Failed) now that
  inputs flow through the unified scheduler. `hydrateRunRecords` is the part
  that rebuilds a finished run from `job.json` and the `_run.json` records —
  which record is a task's, what to fall back to, and what to warn about; the
  `Hydration` oracle in `fuzz/dag_scheduler_fuzz_test` checks it on every
  generated run. Builds without JavaFX as `gui:session`.
- `sql/exec/AggregateExec.scala` (~1900 LOC) — biggest file. Every `AggState` subclass
  plus the codec / LongHashMap GROUP BY paths. Primitive states
  (`CountStarState`, `CountState`, `CountIfState`, `LongSumState`,
  `DoubleSumState`, `AvgState`, `MinMaxState`) override `updateBatch` to
  read typed `ColumnVector`s directly for the no-GROUP-BY fast path.
  Each spillable state additionally implements `writeSelf` / `readSelf`;
  the `AggSpiller` helper at the bottom of the file
  handles per-partition parquet flush + fold-back at emit time.
- `sql/exec/AggStateSerde.scala` (~110 LOC) — dispatcher that pairs
  `AggExpr` types with the right `AggState` subtype for round-trip
  serialization. `isSpillable` gates the operator-level switch (false for
  the DISTINCT aggregates, `CountDistinctState` and `DistinctState`).
- `core/Spill.scala` (~230 LOC) — temp-dir lifecycle, per-operator subdir
  allocation (`OperatorSpillDir`, whose `closeOnDrain` wipes the subdir
  when an operator's output iterator drains), `ColumnarBatch` byte
  estimator, and the `effectiveThresholdBytes` helper that turns
  `ExecutionOptions` into a concrete flush threshold.
- `core/ExecutionOptions.scala` (~120 LOC) — per-query knobs threaded from
  `DataJob.runOneTask` to spill-capable operators. `fromOutputOptions`
  parses the `output.json` `options` map with tolerance for typos.
- `job/DataJob.scala` (~920 LOC) — runner orchestration: unified input + task
  DAG scheduler (`runUnifiedDag`), writeOutput, validation re-read, per-status
  `_run.json` writes + per-failure `_validation-<slug>.csv` sample writes +
  per-job `job.json` write + the run's warnings (inputs that failed to load,
  consistency checks).
- `sql/plan/Expr.scala` (~910 LOC) — `Expr` ADT plus `eval` and `evalVec`
  per subtype. `FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr` each carry
  an `evalVec` override; the rest of the hot subtypes delegate to `VecOps`.
  `SwitchCaseExpr` and `BetweenExpr` read a `RAND()` operand once, and the
  `Expr` companion's `readsRandom` says which expressions read RAND().
- `sql/plan/Ops.scala` (~1120 LOC) — `Ops` (row-at-a-time binary ops, and
  `eq` / `cmp`, the engine's one equality and total order), `Casts`,
  `Decimals` (the declared type of each decimal result, the type that holds
  two decimals, and the one function both paths compute and fit a value
  with), and `VecOps` (one specialized loop per (op,
  output type) pair, which is most of the length). The comparison contract
  both `Expr` paths follow — integers as longs (`integralCompare`), a pair
  with a float in it as doubles (`numericCompare`, whose doc holds the `NaN` /
  `-0.0` rules), anything else through `Ops` (`genericCompare`) — is chosen in
  `VecOps.compare`; see
  [architecture §5a](architecture.md#5a-vectorized-expression-evaluation-evalvec).
- `sql/plan/Funcs.scala` (~550 LOC) — scalar function registry.
  `Funcs.signature` declares each function's arities, argument kinds and
  return type; `Funcs.apply` is the row-form dispatcher; `Funcs.applyVec` +
  the `VecFuncs` object carry the vectorized implementations for the hot
  subset (COALESCE, string ops, ABS/FLOOR/CEIL/ROUND/TRUNC, IF, NULLIF,
  SUBSTRING), sharing `substring`, `length` and `trim` (code points, spaces)
  and `Rounding` with the row form.
- `gui/ResultsTabPane.scala` (~540 LOC) — partition picker + background
  output loader + validation cards + run-log rendering; its rows are a
  `DataGrid`.
- `gui/VimEngine.scala` (~1780 LOC) — the SQL editor's vim mode: the
  `VimBuffer` host trait, then the engine in sections (key dispatch, running a
  command, motions, ranges and vim's exclusive-motion rules, operators,
  registers, simple changes, visual mode, insert mode, repeat and macros, the
  command line and ex commands, undo). Its grammar is `VimCommand.scala`
  (~430 LOC: the command ADT and `VimParser`) and its text arithmetic
  `VimText.scala` (~540 LOC: words, paragraphs, objects, vim regex). A new
  command is an `Action` there and a case in `execute` here; see
  [extending.md](extending.md#extend-the-gui).
- `read/parquet/ParquetReader.scala` (~735 LOC) — `ParquetReader` (the footer
  pass that packs row groups into partitions and checks every file of a glob
  against the first), and `ParquetPartitionIterator`, most of whose length is
  `decodeColumn`: one tight loop per `ParquetColumns.Decoding`. Reading a new
  kind of parquet column is a `Decoding` case in `ParquetColumns.scala` plus
  its loop here.
- `core/ColumnarBatch.scala` (~390 LOC) — defines ten `ColumnVector`
  subclasses. Adding a new `DataType` requires a new vector + companion case
  in `ColumnVector.allocate`.
- `gui/GraphCanvas.scala` (~345 LOC) — the canvas both GUIs draw graphs on:
  pan/zoom, the dot grid, cards, edges, fitting text, and `GraphOverlay` (the
  zoom controls). `gui/DagCanvas.scala` (~270 LOC) draws the job's DAG on it
  (per-input load state beside per-task status, `DagOverlay`'s status counts),
  and `viewer/LineageCanvas.scala` (~260 LOC) a folder's lineage.
- `viewer/DetailsPanel.scala` (~480 LOC) — the data viewer's Details tab: a
  table, an input, a dataset (with its run picker) or the folder, each built
  afresh on a change of selection or of the run shown.
- `sql/exec/JoinExec.scala` (~1040 LOC) — equi-join build + probe paths
  with build/probe role mirroring, the LongHashMap fast path, and
  per-batch `evalVec` key extraction for computed join keys on both
  sides. `probeBatchInto` is the one per-row probe body shared by the
  in-memory and grace-hash paths. Grace hash join is the additional
  `executeGraceHash` path that buckets both sides into K=16 disk parquet
  files via `fmix32(hash(joinKeys)) % K` and processes bucket pairs
  sequentially; per-bucket unmatched-build tracking preserves outer-join
  semantics.
- `sql/exec/WindowExec.scala` (~660 LOC) — partition, sort, frame
  computation for every supported window function; pre-computes per-spec
  partition/order keys per row during the single materialization pass.
  Bucketed spill routes child rows into K=16 disk
  parquet buckets by `fmix32(hash(partitionKey)) % K` and processes each
  bucket through the same in-memory pipeline — every row sharing a
  partition key collocates in one bucket so LAG/LEAD/frame correctness
  is preserved.
- `core/HashKeys.scala` (~955 LOC) — `KeyCodec` (`PackedBytesCodec`,
  `ObjectArrayCodec`, `SingleObjectKeyCodec`, `EmptyKeyCodec`, and
  `decimalKey`, the one spelling a decimal key is stored in) + `BytesKey` /
  `ObjectArrayKey` / `SingleObjectKey` wrappers + `LongHashMap[V]`
  (open-addressing primitive-long-keyed map for the single-Long fast path in
  HashAggregate / HashJoin). Used by every
  pipeline-breaking operator that keys into a HashMap (HashAggregate /
  HashJoin / Distinct / WindowExec partition keys). See
  [architecture.md §2a](architecture.md#2a-keycodec--packed-keys-for-pipeline-breakers).
- `core/Scheduler.scala` (~160 LOC) — the shared `ForkJoinPool` every parallel
  call site funnels through. Daemon threads, default size `2 × availableProcessors`
  (override via `transformer.scheduler.parallelism` system property or
  `TRANSFORMER_SCHEDULER_PARALLELISM` env var). `Submitted.await` rethrows a
  failed task's own exception on the waiting thread.
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

- `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala` (~1220 LOC)
  — the parity gate for every `Expr.evalVec` override. New overrides extend
  this first; see [testing.md](testing.md) for the coverage matrix.
- `src/test/scala/com/transformer/fuzz/` (~13400 LOC) — the hand-rolled
  property-based testing harness (`Rng`, `Props`, `Shrinker`, `RowOracle`), the
  generators (`ExprGen`, `DataGen`, `QueryGen`, `MetaQueryGen` for SQL,
  `SqlTextGen` for SQL text, `JobDagGen` for whole jobs, `ParquetGen` (~730 LOC)
  for parquet files parquet-mr writes, `CsvTextGen` for CSV data and text), and
  the oracles under `oracle/` (`ExprParity`, `ModeDifferential`,
  `MetaModeDifferential`, `Tlp`, `NoRec`, `JoinCommutativity`,
  `AggDecomposition`; `CleanRejection`, `NoSilentDrop` and `SyntaxEquivalence`
  for the frontend; `DagSchedule` for the job-level DAG scheduler; and
  `ParquetDifferential`, `CsvRoundTrip` and `InferenceRoundTrip` for the file
  formats). `SqlTextGen.scala` (~1150 LOC) is the largest:
  most of it is data, the honoured-clause templates, the spelling shapes and the
  mutation vocabulary. See
  [testing.md §Property-based testing](testing.md#property-based-testing-fuzz)
  and the recipes in [extending.md](extending.md#add-a-property--generator).

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
