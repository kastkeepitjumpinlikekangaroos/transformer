# Architecture

How the code is laid out and the cross-cutting patterns that knit it together.
Read this once before making non-trivial changes; pair it with
[conventions.md](conventions.md) (coding rules) and [gotchas.md](gotchas.md)
(non-obvious pitfalls).

## Mental model

```
InputFilePath  → InputResolver → CatalogView   (one per registered "view")
                                      │
                                      ▼
                                   Catalog ────► SqlExecutor (SqlEngine)
                                                  │  parse  → JSqlParser AST
                                                  │  bind   → LogicalPlan
                                                  │  plan   → PhysicalPlan
                                                  │  execute (ForkJoinPool)
                                                  ▼
                                          Iterator[ColumnarBatch]
                                                  │
                                                  ▼
                                          Writer (CSV / Parquet)
```

Everything between input and output is `ColumnarBatch` — a fixed-capacity batch
of rows with one typed column vector per field. Operators read batches and
emit batches. Expression evaluation runs in two modes: pipeline operators
(Project, Filter) drive `Expr.evalVec(batch): ColumnVector` for column-at-a-time
primitive-array work (no boxing, JIT-friendly inner loops); RowBuf-driven
paths (sort comparators, hash-join key reads, window per-row callbacks) still
use boxed `Any` via `Expr.eval(batch, row)`. See
[§5a Vectorized expression evaluation (`evalVec`)](#5a-vectorized-expression-evaluation-evalvec).

## Module map

| Path | Role | Key files |
|---|---|---|
| `core/` | Shared types crossed by every module. Zero deps. | `DataType.scala`, `Schema.scala`, `ColumnarBatch.scala`, `Row.scala`, `Catalog.scala`, `Scheduler.scala` (the shared `ForkJoinPool` every parallel call site funnels through), `SqlExecutor.scala` (`SqlExecutorRegistry`) |
| `temporal/` | Date/time template variables. No deps. | `TemporalVariables.scala`, `TemplateRenderer.scala` |
| `read/csv/` | CSV input. Stdlib only. | `CsvOptions.scala`, `CsvRowParser.scala` (state machine), `CsvSchemaInferer.scala`, `PathGlob.scala`, `CsvReader.scala` |
| `read/parquet/` | Parquet input. Depends on `parquet-hadoop` + minimal Hadoop. | `ParquetReader.scala` |
| `write/csv/` | CSV output. Stdlib only. | `CsvWriter.scala` |
| `write/parquet/` | Parquet output + shared `ParquetSchema` converter. | `ParquetSchema.scala`, `ParquetWriter.scala` |
| `sql/parse/` | JSqlParser façade. | `SqlParser.scala` |
| `sql/plan/` | Expression types, logical plan, JSqlParser → logical conversion, analyzer. | `Expr.scala`, `Ops.scala`, `Funcs.scala`, `Window.scala` (WindowFn / WindowSpec / WindowFrame ADTs), `LogicalPlan.scala`, `Analyzer.scala`, `LogicalBuilder.scala` (largest file in the repo) |
| `sql/exec/` | Physical operators + planner + entry point. | `PhysicalPlan.scala`, `AggregateExec.scala`, `JoinExec.scala`, `SortExec.scala`, `DistinctExec.scala`, `UnionExec.scala`, `WindowExec.scala`, `RowBuf.scala`, `PhysicalPlanner.scala`, `SqlEngine.scala` |
| `job/` | User-facing API + runner. Depends directly on `read/{csv,parquet}` and `write/{csv,parquet}` — parquet is a first-class format. | `DataJob.scala`, `InputFilePath.scala`, `OutputFilePath.scala`, `SQLTask.scala`, `JobResult.scala`, `InputResolver.scala`, `JobFiles.scala` (filesystem ops for the GUI's edit-validation flow — `writeValidationSql` / `deleteValidation`), `TaskDag.scala` (DAG analyzer/builder from SQL refs; `TaskDag` + `TaskDagNode` are public so the GUI can render without re-implementing), `TaskProgressListener.scala` (per-task callbacks fired from runner worker threads), `TaskRunRecord.scala` (`_run.json` write/read/discover for per-task run records — every status, with `_validation-<slug>.csv` failure samples), `JobRunRecord.scala` (`job.json` aggregate manifest pointing at every task's record + consistency warnings), `JobOutputLayout.scala` (classifies an output dir as `SingleRun`/`MultiRun`/`Empty` for the GUI's run picker), `DirectoryJobLoader.scala` (DBT-style directory loader; supports optional per-table `output.json` with `partitionBy`; threads default `<outputDir>/job.json` as `jobRunOutput` so the manifest lives alongside its data), `Json.scala` (stdlib JSON parser used by the loader + record readers) |
| `gui/` | JavaFX visualizer/runner. Depends on `job/`, `core/`, `read/{csv,parquet}/`, `write/{csv,parquet}/`, `sql/exec/`, `temporal/`, plus `org.openjfx:javafx-{base,controls,graphics}` (bare jars + platform-classifier jars). | `GuiApp.scala` (Application boot + BorderPane: top stack of menu bar + horizontal ControlsPanel, vertical split of DAG canvas above + ResultsTabPane below — no side panels, all secondary panels live in the bottom tabs; scene-level ⌘R / Ctrl+R accelerator calls `controls.triggerRun()`), `JobSession.scala` (mutable FX-thread session state — jobDir, executionTime, outputDir, DAG, per-task UI states, task records, historical runs, job manifest, available run snapshots, selected run; loads `<outputDir>/job.json` (or any sibling snapshot the user picks) and follows each task's `runFile` to reconstruct full `TaskStatus` including `ValidationFailed(failures)` with sample CSVs; `buildInteractiveCatalog` assembles a Catalog over inputs + persisted task outputs for ad-hoc SQL), `DagLayout.scala` (pure-Scala layered DAG layout), `DagCanvas.scala` (custom Canvas: pan/zoom/click/double-click), `ControlsPanel.scala` (horizontal top strip: open dir, exec-time pickers, output-dir field, optional run picker for multi-run output dirs, compact Run button on worker thread; exposes `triggerRun()` for the keyboard accelerator), `TaskDetailsPanel.scala` (selected task/input metadata + a single Source/Rendered-toggle SQL viewer — uses `SqlView` and chips for status/deps/metrics), `SqlHighlighter.scala` (pure-Scala tokenizer: keywords/functions/strings/numbers/comments/templates — testable, no JavaFX deps), `SqlView.scala` (read-only highlighted SQL pane built on `HBox` per line inside a `ScrollPane`; toolbar with Copy + optional Open-in-editor button), `ExternalEditor.scala` (launches `TRANSFORMER_EDITOR` if set, else macOS Terminal+`nvim`; no-wait launch via `ProcessBuilder`), `ResultsTabPane.scala` (5 tabs: **Task details**, Output data, Validations — per-validation cards with an Edit… button that pops out the `AddValidationDialog.showEdit` editor (round-trips through `JobFiles.writeValidationSql` / `deleteValidation`) — **SQL console**, Run log), `AddValidationDialog.scala` (the validation editor dialog used by both the task-details chip and the per-card Edit button; supports add + edit + delete), `SqlConsolePanel.scala` (ad-hoc SQL editor in a horizontal SplitPane with the results table; ⌘⏎ / Ctrl+Enter runs the query at the panel level; chips for registered views + Persist button — runs queries on a worker thread and materializes results in memory so Persist can replay them), `PersistDialog.scala` (modal: output dir, format, maxPartitions, CSV header — mirrors `OutputFilePath` fields), `ResultPersister.scala` (writes a `MaterializedView` via `CsvWriter.writePartitioned` / `ParquetWriter.writePartitioned` + stamps a `_run.json` record with status=Succeeded), `FxHelpers.scala` |
| `examples/scala_app/` | Sample app built as a `scala_binary` deploy jar — programmatic `DataJob(...)` API. | `src/main/scala/com/example/ExampleJob.scala` |
| `examples/directory_app/` | Sample app using `DirectoryJobLoader` — whole job is a folder of JSON configs + SQL files. | `src/main/scala/com/example/directory/DirectoryJobExample.scala`, `job/inputs/<view>/config.json`, `job/tables/<view>/main.sql`, `job/tables/<view>/validations/*.sql`. Accepts optional 3rd CLI arg for `executionTime` (ISO instant) so the same job can produce multiple partitions for testing. |
| `examples/jaffle_shop/` | Port of [dbt-labs/jaffle-shop](https://github.com/dbt-labs/jaffle-shop) to the directory format. 6 raw seed CSVs → 6 staging tables → 3 intermediate aggregations → 3 marts (`customers`, `orders`, `order_items`) + 3 passthroughs (`locations`, `products`, `supplies`). 26 DBT data_tests ported as zero-row validation queries. Exercises the DAG scheduler at a realistic scale (~150k rows, 15 tasks). Omissions vs. DBT: `metricflow_time_spine` (needs `dbt_date`); `customer_order_number` ROW_NUMBER column on `orders` (no window functions); semantic models / metrics / saved_queries / unit_tests (no equivalent layer). DBT CTEs are split — each `with X as (...)` becomes its own SQLTask, since `LogicalBuilder.fromItem` only accepts `Table` in FROM. | `src/main/scala/com/example/jaffle/JaffleShopExample.scala`, `job/data/raw_*.csv`, `job/inputs/<view>/config.json`, `job/tables/<view>/{main.sql,validations/}`. |
| `examples/gui_app/` | Sample app launching the GUI. Pulls in `gui/` + `sql/exec/`; parquet support comes transitively through `gui/`. | `src/main/scala/com/example/gui/GuiAppLauncher.scala` |
| `examples/polymarket/` | Heavy-load directory-driven pipeline over the [Polymarket tick-level orderbook Kaggle dataset](https://www.kaggle.com/datasets/marvingozo/polymarket-tick-level-orderbook-dataset). 5 parquet inputs (1 daily orderbook ≈131M rows — the shipped `stg_orderbook` filters to its first 6 hours / ≈27M rows; 21 daily snapshot files / 51M rows; ml features / 5.6M rows; 4.1M trades; 124K markets), 17 parquet output tables in staging / intermediate / mart / final layers, 58 validations. Two branches are EXPECTED to ValidationFail on real-feed data: `mart_orderbook_quality_check` carries an INTENTIONAL validation asserting no market has snapshot latency above zero (real data does, so the task is `ValidationFailed` and its downstream `mart_quality_report` is `Skipped`); `final_combined_report` carries a `category_not_null` validation that surfaces NULL `category_normalized` rows propagated through `mart_market_overview`'s LEFT JOIN against `int_markets_categorized` (orderbook rows whose `condition_id` doesn't match a market row leave a NULL category in the rollup). The other three mart branches (overview, high-activity, volatility) run to completion. Launcher exits 0 iff that exact 14-Succeeded / 2-ValidationFailed / 1-Skipped pattern matches. Exercises: row-group-level parallel parquet scans of multi-GB inputs (a single 1GB file with 139 row groups splits into ~4 partitions packed to ~256MB each), glob-fed multi-partition parallel scans (snapshots), parallel-branch DAG scheduling, the failed-validation → skipped-downstream propagation path, the `partitionBy: "day={{ today }}"` template-driven partition layout, the `_run.json` + `job.json` run-records written by every task (the failed and skipped tasks get their own records too, so the GUI's run picker can replay the failure later). Needs `~/Downloads/archive/` dataset checkout and `-Xmx12g`. ~4 min on a fast Mac with the shipped 6-hour filter (was ~8 min before row-group splits). | `src/main/scala/com/example/polymarket/PolymarketExample.scala`, `job/inputs/raw_{orderbook,snapshots,features,trades,markets}/config.json`, `job/tables/{stg,int,mart,final}_*/{main.sql,output.json,validations/}` |
| `tools/parquet_peek/` | Stand-alone CLI for inspecting a parquet file or glob — schema, partition count, footer-derived `exactRowCount`, and a configurable sample of decoded rows. Reader-only; no SQL engine. Strings >80 chars are truncated so JSON-blob columns stay legible. | `tools/parquet_peek/ParquetPeek.scala` |

## Cross-cutting patterns

### 1. Module dependencies

`job/` depends directly on `read/parquet/` and `write/parquet/` — parquet is
a first-class format, not an optional add-on. `InputResolver` dispatches CSV
and Parquet inputs in the same `match`, and `DataJob` calls
`ParquetWriter.writePartitioned` / `ParquetReader.fromPath` directly for
output writes + validation re-reads. The `sql/exec/SqlEngine` still
self-registers into `core/SqlExecutorRegistry` on class load — that one
indirection survives because `core/` has no SQL knowledge.

If you add a new format (orc, json, avro), wire it the same way: a `read/<x>`
+ `write/<x>` module, both listed in `job/BUILD.bazel`, with a new case in
`InputResolver.resolve` + `DataJob.writeOutput` + `DataJob.materializeIfNeeded`.

### 2. ColumnarBatch + RowBuf

`ColumnarBatch` is the unit that flows through operators. Each column is
typed:

- Numerics (Int, Long, Float, Double, Boolean, Date as days, Timestamp as
  micros): primitive array + `java.util.BitSet` of nulls.
- References (String, Binary, Decimal): the array itself holds `null` for SQL
  NULL — no separate bitset.

Operators emit batches in two ways:
- **Pipeline operators** (Scan/Filter/Project/LocalLimit) preserve `numPartitions`
  and stream batches via `Iterator[ColumnarBatch]`.
- **Pipeline breakers** (HashAggregate/HashJoin/Sort/Distinct/GlobalLimit) collapse
  to `numPartitions = 1` and materialize across partitions in parallel via an
  `Executors.newFixedThreadPool`.

`SqlEngine.execute` returns an `ExecutedQuery` that exposes both `numPartitions`
+ `partition(i)` and a flattened `batches` view. The writer pipeline pulls
per-partition iterators so it can fan out one output part file per partition
in parallel; in-process callers (validation drains, in-memory materialization)
use the flat view.

When operators need to evaluate an `Expr` over a *materialized row* (sort
comparators, hash-join keys, residual predicates), they use `RowBuf`:
a reusable 1-row batch that the surrounding loop refills with `.set(rowArr)`
or `.setFromBatch(srcBatch, srcRow)`. Don't allocate fresh `ColumnarBatch`es
per row in tight loops — that defeats the JIT.

### 3. Parallel execution

All parallel work — DAG scheduling, pipeline-breaking operators, partitioned
writers, parallel input materialization, parquet footer reads — funnels through
one shared work-stealing pool: `com.transformer.core.Scheduler.pool`. It's a
`ForkJoinPool` sized to `2 × availableProcessors` by default (override via the
`transformer.scheduler.parallelism` system property or the
`TRANSFORMER_SCHEDULER_PARALLELISM` env var), with daemon threads (so it never
blocks JVM exit). The 2× factor matters because the work is a mix of CPU-bound
(decode, filter, aggregate, encode) and I/O-bound (parquet/CSV reads + writes);
a worker blocked on disk I/O can't steal CPU-bound work, so strict `cores`
sizing leaves the box idle whenever the heavy task is in its I/O phase. Each
call site submits Callables via `Scheduler.submit(c).get()` or the convenience
`Scheduler.submitAndAwaitAll`; nested submission is safe because a
`ForkJoinTask.get` from inside a worker cooperates with the pool's compensation
logic.

Before this consolidation, every pipeline breaker (`HashAggregateExec`,
`HashJoinExec`, `SortExec`, `DistinctExec`), every partitioned writer
(`CsvWriter`, `ParquetWriter`), input loading, and the DAG task runner each
created their own short-lived `Executors.newFixedThreadPool(cores)`. With N
concurrent SQL tasks each fanning out an aggregate + writer, the JVM ended up
with N × cores OS threads contending — no work-stealing across pools, and a
single heavy task would pin its pool while neighbouring pools sat idle. The
shared pool fixes that: total OS-level parallelism is bounded by `cores`, and
when one stage blocks on I/O the others keep making progress.

Parallelism still scales with input partitioning. CSV → one partition per
file. Parquet → one partition per ~256MB of compressed row groups: a small
file with a few row groups packs into one partition, a multi-GB file splits
into several. The split uses the low-level `ParquetFileReader.skipNextRowGroup`
to seek (metadata-only — no decoding), so a partition that starts at row group
50 pays O(50 metadata ticks) instead of O(rows-before-50) for the seek. Tune
per-input via `options("read_partition_size_bytes")` (e.g. drop it for highly
contested input where you want even more parallelism, raise it to consolidate
small files). Plan output cap (`OutputFilePath.maxPartitions`) accordingly —
the writer fans out 1:1 by default, so a 4-partition source produces 4 part
files unless capped.

### 3a. Output is always a directory of part files

`OutputFilePath.path` is always interpreted as a *directory*. The writer fans
out one `part-NNNNN.<ext>` per source partition into that directory using the
shared [[Scheduler]] pool. Each part file is written via the existing
single-file writer with the atomic temp + rename pattern, so any failure aborts
all in-flight parts before throwing.

`ParquetWriter.writePartitioned` clamps the in-flight writer count for memory
safety — each writer pins a 32MB row group buffer + per-column dictionaries.
The default cap is `min(cores, maxHeap / 256MB)` (computed by
`ParquetWriter.defaultWriteParallelism()`); override per-task with
`options("parquet_write_parallelism")` in `output.json`. The runner submits all
partition writers to the shared pool but uses a sliding window of `cap`
in-flight Futures so heap stays bounded even when the partition count exceeds
the cap. `CsvWriter.writePartitioned` has no equivalent heap concern (CSV
writers don't buffer row groups) so it submits all partitions immediately and
lets the shared pool's `cores` workers throttle naturally.

`OutputFilePath.maxPartitions: Option[Int]` caps the number of output files.
With `maxPartitions = Some(k)` and `k < q.numPartitions`, `DataJob.coalescedPartitions`
groups source partitions into contiguous chunks; with `k >= q.numPartitions`
it's a no-op (we don't artificially split a single partition into multiple
files). `maxPartitions = Some(1)` collapses everything into a single
`part-00000.<ext>` — the closest equivalent of the old single-file output.

Validation re-reads use `CsvReader.fromPath(dir)` / `ParquetReader.fromPath(dir)`;
both treat a bare directory as "every visible file inside" (via
`PathGlob.expand`, which skips dotfiles + underscore-prefixed files — needed
so we don't trip on Hadoop's CRC sidecars).

### 3b. Input caching: opt-out via `cache: false`

By default (`InputFilePath.cache = true`) every input is drained into a
`MaterializedView` as it loads, so SQL execution hits in-memory batches with no
further I/O. This is fine for typical CSV seeds (MB-sized) but fatal for
multi-GB parquet inputs — decompressed + boxed in `ColumnarBatch`es they easily
blow past a default heap.

Set `cache: false` (in the input's `config.json` or `InputFilePath(cache = false)`)
to skip materialization for that input. The raw streaming `CatalogView` (e.g.
the `ParquetReader` itself, partitioned per file) gets registered directly,
and each query re-reads the underlying file(s). For aggregations / filters this
is cheap; for projection-heavy DAGs with many downstream readers it pays the
I/O cost N times — pick per-input.

When materialization OOMs anyway, `DataJob` appends a hint to the error message
pointing at this flag (see `oomHint` in `DataJob.scala`).

### 3c. Parquet decode/encode is vectorized

Both directions skip the `Group`/`SimpleGroup` row-object model that
`parquet-mr`'s example readers use.

- Read: `ParquetPartitionIterator` opens one `ColumnReader` per output column
  per row group (via `ColumnReadStoreImpl`) and decodes column-at-a-time
  straight into `ColumnarBatch` primitive vectors. The per-row cost is one
  definition-level check + one typed copy. On the polymarket orderbook scan
  (2.7B rows × ~12 columns) this is the dominant speedup over the old
  `RecordReader[Group]` path — billions of `SimpleGroup` allocations + cell-
  level `Integer`/`Long` boxing were the bottleneck.
- Write: a custom `BatchWriteSupport` drives parquet-mr's `RecordConsumer`
  directly from the `ColumnarBatch` vectors. Each batch row maps to one
  `writer.write()` call (parquet-mr needs that 1:1 mapping so its
  `recordCount`-driven row-group flush bookkeeping stays correct) but the
  per-row dispatch is via a reusable `BatchRowRef` — no `SimpleGroup` per
  row, no `Integer`/`Long` boxing per cell.

`ColumnarBatch.DefaultCapacity` is 8192 rows. Larger batches amortize
per-batch dispatch (hasNext checks, allocations, writer flush boundaries) at
the cost of more in-flight memory; lower it if you have very wide schemas.

### 3d. Parquet predicate pushdown

`ParquetReader.withPushdownFilter(predicate: Expr)` translates a bound
transformer predicate into parquet-mr's `FilterApi.FilterPredicate` and
returns a new view that pre-filters at iterator time. Each candidate row
group's column statistics get checked against the filter; groups proven not
to match are skipped without reading their column data.

`PhysicalPlanner.plan` invokes this whenever it sees the shape
`LogicalFilter(LogicalScan(_, view, _), pred)` and the view participates.
The original `FilterExec` is always kept above the scan — stats can prove
non-matching groups but never PROVE matching ones, so the per-row precision
filter still has to run.

`ParquetFilterTranslator.translate` is a best-effort lowering. Supported
shapes today: `c = lit`, `c != lit`, `c < lit`, `c <= lit`, `c > lit`,
`c >= lit` (and the swapped `lit OP c` forms); `c IS NULL` / `c IS NOT NULL`
(translates to `FilterApi.eq(col, null)` / `notEq(col, null)`, so the stats
filter uses `numNulls` / `numRows` to drop all-null or no-null groups);
`c IN (lit, lit, …)` / `c NOT IN (…)` over a homogeneous literal list
(decomposed to an OR of equalities, wrapped in `not(...)` for NOT IN);
`c BETWEEN lo AND hi` (lowered by LogicalBuilder to `c >= lo AND c <= hi`
so each half hits the comparator path); plus `NOT`, `AND`, `OR` over those.
Unsupported (left as residual): computed expressions (`a - b > 0`),
two-column predicates (`a > b`), `LIKE`, `IN` containing a NULL literal
(SQL three-valued-logic semantics make stats-level skipping unsafe in that
case — the translator bails), `IN (…)` with non-literal items, decimal
columns. The translator drops unsupported conjuncts from the AND-chain
rather than refusing to push the whole predicate; the residual `FilterExec`
covers them.

### 3e. Skip materialize re-read when no downstream consumes

When a task with `outputFile` set has neither validations nor a downstream
DAG dependent, `DataJob.runOneTask` does NOT re-open the just-written parquet
to register it in the catalog. The data lives on disk; nothing in the job
will read it. The old code unconditionally re-read for any task with a
`viewName`, which on the polymarket leaf `mart_*` tasks doubled the per-task
tail latency for no benefit.

### 3f. Unified input + task DAG scheduling

`DataJob.runUnifiedDag` is the single completion-driven scheduler over both
input loads and SQL tasks. Inputs are NOT a pre-task barrier; each input load
is submitted to the shared pool the moment `run` is invoked, alongside SQL
tasks that have no input refs. SQL tasks track `pendingInputDeps` (count of
unfinished `inputDeps`) and `pendingTaskDeps` (count of unfinished upstream
task indices). A task is eligible the instant both counters hit zero — so the
`raw_features → stg_features` branch can finish well before the
`raw_orderbook` glob has loaded (which on the polymarket workload is the
slowest input).

`TaskDag.build` is still the gatekeeper: it parses each task's rendered SQL
(main + every validation) with JSqlParser's `TablesNamesFinder` and builds
adjacency based on task viewName references. Setup-time validation rejects
unknown refs, duplicate viewNames, self-cycles, cycles, and duplicate
post-render output paths before any task runs.

The scheduler propagates failure through both axes. If an input load throws,
every SQL task with that input in its `inputDeps` gets `upstreamFailed = true`
and is marked `Skipped` the moment it would otherwise have become ready —
which then propagates through the downstream task chain. Same path for SQL
task failures (Failed / ValidationFailed) — independent siblings keep going.

The flow yields three timestamps per task that the GUI surfaces:
`enqueuedAt` (deps satisfied), `startedAt` (worker picked it up), `finishedAt`
(returned). `queueWaitMillis = startedAt - enqueuedAt` distinguishes "task ran
fast but waited in the pool" from "task ran fast and started immediately" —
historically the GUI reported only `finishedAt - startedAt`, which is why a
2.5-second task on a saturated pool felt like 30 seconds of wall time.

### 4. Run records and historical-run discovery

The runner persists a per-task `_run.json` for **every termination status**
(Succeeded, ValidationFailed, Failed, Skipped) plus a per-job `job.json`
aggregate. The two files together let the GUI reconstruct the exact same
state on reload that it showed during the live run — validation pass/fail
detail, sample failing rows, error messages, durations — and let the
runner flag drift between disk state and the manifest.

#### Per-task record (`_run.json`)

`DataJob.writeSucceededRecord` / `writeValidationFailedRecord` /
`writeFailedRecord` / `writeSkippedRecord` each stamp
`<taskOutputDir>/_run.json` via `TaskRunRecord.write` (atomic temp-then-
rename). The shape:

```json
{
  "schemaVersion": 1,
  "taskName": "spend_by_tier",
  "status": "ValidationFailed",
  "errorMessage": null,
  "executionTime": "2026-01-01T05:30:21Z",
  "startedAt":  "2026-05-18T03:17:02.612Z",
  "finishedAt": "2026-05-18T03:17:03.099Z",
  "writtenAt":  "2026-05-18T03:17:03.103Z",
  "rowsProduced": 1234,
  "format": "csv",
  "outputFiles": ["part-00000.csv", "part-00001.csv"],
  "validations": [
    { "name": "customer_id_unique", "passed": false, "failedRowCount": 7,
      "sampleFile": "_validation-customer_id_unique.csv" }
  ]
}
```

For failed validations, the runner writes the sample CSV (up to 10 rows
returned by the validation SQL) to a sibling
`_validation-<slugified-name>.csv` file and links it via `sampleFile`. The
GUI loads samples lazily — only when the user inspects a specific task's
validation card.

The `_` prefix means `PathGlob.expand` skips both the record and the
sample files when the directory is re-read as data.

`TaskRunRecord.discover(templatedPattern)` is the partner operation: it
replaces every `{{...}}` in the pattern with a glob wildcard, walks the
longest static prefix of the result, and returns every directory under it
that contains a `_run.json` — sorted newest-first by `writtenAt`. The walk
is depth-bounded so a pattern with a leading wildcard doesn't accidentally
scan the whole disk.

Record-write failures are swallowed by the runner — a missing record must
never poison an otherwise-successful run. The exception is rerun cleanup:
before writing data, `writeOutput` calls `TaskRunRecord.clearIfMarked(dir)`
which wipes every top-level regular file in a directory that already
contains a `_run.json`, so reruns at the same templated path completely
overwrite prior state.

#### Per-job manifest (`job.json`)

`DataJob.writeJobRecord` writes a `JobRunRecord` to the path configured by
`jobRunOutput` (defaulted by `DirectoryJobLoader` to
`<outputDir>/job.json` — co-located with the per-task data so an output
directory is a self-contained snapshot of one run). The manifest lists
every task with status, row count, error message, and a `runFile` pointer
to the per-task record file. It's the GUI's single entry point on reload
— load one file, follow the pointers, hydrate the whole job.

The manifest also carries a `warnings` array populated by
`DataJob.runConsistencyChecks`: declared part files that aren't on disk,
referenced `runFile` paths that don't exist, missing validation samples,
etc. These are non-fatal — the run still `succeeded` if every task is
Succeeded — but they surface in the GUI's run-log panel so the user
notices.

Like per-task records, reruns at the same `jobRunOutput` path completely
overwrite the manifest. To keep job-level history, template `outputDir`
itself (e.g. `/data/runs/{{ today }}`); each execution time writes to its
own subdir with its own `job.json`, and the parent becomes a multi-run
layout the GUI can browse.

#### Multi-run layout discovery

[[JobOutputLayout.detect]] classifies a directory at one of:
  * `SingleRun(dir, record)` — `<dir>/job.json` is present.
  * `MultiRun(runs)` — `<dir>/<sub>/job.json` is present in 1+
    subdirectories, sorted newest-first by `finishedAt`.
  * `Empty` — neither.

The walk is one level deep. `JobSession` calls this on the *parent* of
the rendered `jobRunOutput` path: when the user has templated `outputDir`
to vary by run, the parent holds multiple sibling snapshots, and the GUI
surfaces a run picker in the controls panel for switching between them
without re-running anything.

### 5. Expression evaluation

`Expr.eval(batch, row): Any` — boxed return. Pattern:

```scala
val v = expr.eval(batch, row)
if (v == null) // SQL NULL
else v.asInstanceOf[Boolean | Long | Double | String | …]
```

Three-valued boolean logic is handled inside `BinOpExpr` for `AND`/`OR`.
Arithmetic and comparison are NULL-propagating via the `lv == null || rv == null
=> null` short-circuit at the top of the generic branch.

`Ops.cmp` is the canonical NULL-free comparator (numbers compared as
`Double`, strings lexicographic, booleans natural). `Ops.eq` is the canonical
NULL-free equality. Both are used by sort, join, IN, and `=`.

### 5a. Vectorized expression evaluation (`evalVec`)

`Expr` also exposes `def evalVec(batch: ColumnarBatch): ColumnVector` — one
call evaluates the expression across every row of a batch and returns a typed
result column. This is the path `ProjectExec` and `FilterExec` use; the
RowBuf-driven sites (sort/join/window/aggregate-state updates) still call
`eval(batch, row)` because they're working on 1-row batches where
vectorization gives nothing back.

The trait carries a default implementation that allocates an output vector and
loops per-row `eval` writing through `setBoxed` — so any `Expr` (including
`FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr`) is correct out of the box.
Hot nodes override for primitive-array speed:

- `LitExpr.evalVec` — `Arrays.fill` of a single primitive across the batch.
- `ColRefExpr.evalVec` — returns `batch.column(index)` zero-copy. The caller
  treats the result as read-only.
- `CastExpr.evalVec` — delegates to `VecOps.cast`.
- `UnaryOpExpr.evalVec` — `VecOps.negate` / `VecOps.not`.
- `BinOpExpr.evalVec` — dispatches to `VecOps.{and,or,arith,compare,concat}`.
- `IsNullExpr.evalVec` — `VecOps.isNull`.

`VecOps` (in `sql/plan/Ops.scala`) is the column-at-a-time companion to `Ops`.
Each helper:

1. Allocates one typed output vector sized to the batch.
2. Lifts the op-string match **outside** the inner loop (so the JIT sees a
   monomorphic primitive operation per loop body, not a constant-string match
   per row).
3. Reads operands via small `readLong` / `readInt` / `readDouble` accessors
   that pattern-match the operand vector type once per call site — the JIT
   devirtualizes these after profiling, then inlines the primitive read into
   the inner loop.
4. Tracks nulls via a `BitSet` populated alongside the value array.

The shape is what HotSpot autovectorizes on AVX, so a `Long + Long` chain
ends up as a tight SIMD loop without an explicit codegen step. For combinations
the analyzer didn't normalize through CAST (e.g. `IntVector + LongVector → Long`)
the typed accessor handles cross-type promotion polymorphically — slower than
the fully monomorphic case but still one dispatch per Expr node per batch
instead of per row.

**Aliasing contract.** `ColRefExpr.evalVec` returns the input column directly,
so the output of `ProjectExec` (assembled via `ColumnarBatch.fromColumns`) may
share storage with its input. This is safe because:
- No operator mutates a column it didn't allocate. `select`, `copyTo`,
  `evalVec` all produce fresh vectors.
- The output batch holds a reference to the input column, which holds the
  input batch alive via the GC reachability chain.

If you write a new operator that needs to OWN an output column (e.g. to
mutate it after construction), copy via `ColumnVector.copyTo` instead of
relying on the alias.

**When to override `evalVec` on a new `Expr`.** Override when the expression
shows up in `ProjectExec` / `FilterExec` hot paths and a primitive-array loop
saves real time. Leave the default for one-off shapes (rarely-used scalar
functions, string-heavy operations where boxing isn't the bottleneck). The
fallback is one ColumnVector allocation + N `setBoxed` calls per batch —
slightly more allocation than the pre-`evalVec` row loop, but still correct.

### 6. Window functions: two-stage logical binding

A `fn() OVER (...)` clause is **not** a regular `Expr` — it can't be evaluated
per-row in isolation because it depends on the whole partition. `LogicalBuilder`
handles this with a two-stage rewrite:

1. **Pre-window stage.** Aggregation (if any) is built first, producing a
   plan + a `BinderFactory: (AnalyticExpression => Option[Expr]) => Expression
   => Expr`. The factory is called with an empty resolver to get a binder used
   to bind the partition keys, order keys, and function arg of each `OVER (...)`.
2. **Collect windows.** Walk SELECT items + ORDER BY. Every distinct
   `AnalyticExpression` (deduped by canonical SQL) becomes a `WindowDef` with
   a synthetic output column name (`_win0`, `_win1`, …).
3. **Insert `LogicalWindow`** above the post-aggregation plan. Its output
   schema is `child.outputSchema ++ windowOutputs` — original column indices
   are preserved.
4. **Post-window binding.** Call the same `BinderFactory` with the populated
   resolver; the resulting binder substitutes each `AnalyticExpression` with
   a `ColRefExpr` into the window-output position. SELECT projections and
   ORDER BY then bind cleanly.

`WindowExec` is a pipeline breaker (`numPartitions = 1`). It materializes all
child rows, groups `WindowDef`s by `WindowSpec` so that partitioning + sorting
happens once per spec, and writes one output column per window. Ranking
functions and LAG/LEAD ignore the frame; aggregates with ORDER BY and no
explicit frame use running aggregation; aggregates without ORDER BY cover
the entire partition. `RANGE` frames execute as `ROWS` (documented in
[gotchas.md](gotchas.md#whats-intentionally-not-done)).

### 7. Plan-time cardinality estimation

`LogicalPlanCardinality.estimate(plan): Option[Long]` walks a bound
`LogicalPlan` and returns a best-effort row count. It's consulted by
`PhysicalPlanner` to pick the build side of a hash join (the rest of the
planner is structural; estimates only feed the join build decision today).

The estimator's leaves are `CatalogView.exactRowCount` — `MaterializedView`
and `ParquetReader` both populate it; CSV doesn't, so any plan rooted in a
streaming CSV scan returns `None`. Filters apply a fixed-selectivity table
(`= → 0.1`, range `→ 0.3`, `IS NULL → 0.1`, `LIKE → 0.5`, default `0.5`);
aggregates divide by `groupKeys × 100` (capped at the input count); joins
fold the per-side estimates by kind (inner → `max`, left → left, right →
right, full → `left + right`). All constants live as named `private[plan]
val`s in `LogicalPlanCardinality.scala` so they're tweakable as a unit if
profiling shows misestimates dominating real workloads.

**The estimator does not collect statistics.** No sampling, no sketches, no
histograms. The contract is *coarse discrimination only* — "1000 rows vs.
1 million" — which is enough to choose a build side correctly without
acquiring statistics-catalog infrastructure. When you add a new operator
(plan 05 didn't, but a future operator might) contribute an
`estimate(plan)` case if it's a row-count-changing shape; pass-through
shapes (project / sort / window) just return the child's estimate.

### 8. Hash-join build-side selection

`PhysicalPlanner.shouldBuildRight(l, r, kind)` decides which side of a
`LogicalJoin` becomes the build side of the resulting `HashJoinExec`. The
default — and the historic shape — is to build from the right side and
probe from the left. The planner overrides this when:

- **Inner join, left is materially smaller.** `LogicalPlanCardinality.estimate`
  reports both sides; if `leftEst × 2 ≤ rightEst` the planner sets
  `buildRight = false` so the smaller side ends up in the hash. The 2× gate
  keeps near-equal estimates pinned to the default (small ratio differences
  are a wash and not worth the risk of a bad estimate flipping a stable
  plan).
- **Right outer join.** Always `false`. RIGHT outer must preserve right-side
  rows, and the cleanest way is to keep right as the streaming probe — the
  matched-build tracking then surfaces every right row, including unmatched
  ones with NULL left columns. Schema and column order remain `left ++
  right` regardless.
- **Left outer / full outer.** Always `true` (no swap). LEFT outer preserves
  left rows, so left must stay on the probe side; FULL outer's matched-build
  set is the only path for unmatched-build rows, and the symmetry buys
  nothing.
- **No estimate available** (e.g. a CSV-rooted side). Falls back to `true`.
  Best to be conservative when there's nothing to base a decision on.

`HashJoinExec` carries `buildRight` as its 7th parameter (defaulted to
`true` for back-compat with hand-built plans in tests). Internally it
mirrors build/probe roles through `probeIsLeft`/`buildIsLeft` flags;
`mergeMatch` / `mergeUnmatchedProbe` / `mergeUnmatchedBuild` use those flags
to write each row into the correct half of the output array, so callers
never observe the swap.

Non-equi joins (no equality conjunct after `splitEqualityKeys`) plan as a
degenerate hash join — every probe row matches the single empty-key bucket
holding every build row, which gives correct nested-loop semantics. To stop
that from silently materializing an O(N*M) cartesian over millions of rows,
`PhysicalPlanner.enforceNestedLoopGuard` refuses the plan when both
estimates exist and `min(left, right) > 5000`. CSV-rooted joins (no
estimate) keep working — the guard is conservative.
