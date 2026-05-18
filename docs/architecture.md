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
emit batches. Per-row evaluation is boxed `Any` (simple and correct for v1);
the JIT keeps this acceptably fast.

## Module map

| Path | Role | Key files |
|---|---|---|
| `core/` | Shared types crossed by every module. Zero deps. | `DataType.scala`, `Schema.scala`, `ColumnarBatch.scala`, `Row.scala`, `Catalog.scala`, `SqlExecutor.scala` (`SqlExecutorRegistry`) |
| `temporal/` | Date/time template variables. No deps. | `TemporalVariables.scala`, `TemplateRenderer.scala` |
| `read/csv/` | CSV input. Stdlib only. | `CsvOptions.scala`, `CsvRowParser.scala` (state machine), `CsvSchemaInferer.scala`, `PathGlob.scala`, `CsvReader.scala` |
| `read/parquet/` | Parquet input. Depends on `parquet-hadoop` + minimal Hadoop. | `ParquetReader.scala` |
| `write/csv/` | CSV output. Stdlib only. | `CsvWriter.scala` |
| `write/parquet/` | Parquet output + shared `ParquetSchema` converter. | `ParquetSchema.scala`, `ParquetWriter.scala` |
| `sql/parse/` | JSqlParser façade. | `SqlParser.scala` |
| `sql/plan/` | Expression types, logical plan, JSqlParser → logical conversion, analyzer. | `Expr.scala`, `Ops.scala`, `Funcs.scala`, `Window.scala` (WindowFn / WindowSpec / WindowFrame ADTs), `LogicalPlan.scala`, `Analyzer.scala`, `LogicalBuilder.scala` (largest file in the repo) |
| `sql/exec/` | Physical operators + planner + entry point. | `PhysicalPlan.scala`, `AggregateExec.scala`, `JoinExec.scala`, `SortExec.scala`, `DistinctExec.scala`, `UnionExec.scala`, `WindowExec.scala`, `RowBuf.scala`, `PhysicalPlanner.scala`, `SqlEngine.scala` |
| `job/` | User-facing API + runner. Depends directly on `read/{csv,parquet}` and `write/{csv,parquet}` — parquet is a first-class format. | `DataJob.scala`, `InputFilePath.scala`, `OutputFilePath.scala`, `SQLTask.scala`, `JobResult.scala`, `InputResolver.scala`, `JobFiles.scala` (filesystem ops for the GUI's edit-validation flow — `writeValidationSql` / `deleteValidation`), `TaskDag.scala` (DAG analyzer/builder from SQL refs; `TaskDag` + `TaskDagNode` are public so the GUI can render without re-implementing), `TaskProgressListener.scala` (per-task callbacks fired from runner worker threads), `RunMarker.scala` (`_SUCCESS` write/read/discover for per-task success markers), `DirectoryJobLoader.scala` (DBT-style directory loader; supports optional per-table `output.json` with `partitionBy`), `Json.scala` (stdlib JSON parser used by the loader + RunMarker.read) |
| `gui/` | JavaFX visualizer/runner. Depends on `job/`, `core/`, `read/{csv,parquet}/`, `write/{csv,parquet}/`, `sql/exec/`, `temporal/`, plus `org.openjfx:javafx-{base,controls,graphics}` (bare jars + platform-classifier jars). | `GuiApp.scala` (Application boot + BorderPane: top stack of menu bar + horizontal ControlsPanel, vertical split of DAG canvas above + ResultsTabPane below — no side panels, all secondary panels live in the bottom tabs; scene-level ⌘R / Ctrl+R accelerator calls `controls.triggerRun()`), `JobSession.scala` (mutable FX-thread session state — jobDir, executionTime, outputDir, DAG, per-task UI states, markers, historical runs; `buildInteractiveCatalog` assembles a Catalog over inputs + persisted task outputs for ad-hoc SQL), `DagLayout.scala` (pure-Scala layered DAG layout), `DagCanvas.scala` (custom Canvas: pan/zoom/click/double-click), `ControlsPanel.scala` (horizontal top strip: open dir, exec-time pickers, output-dir field, compact Run button on worker thread; exposes `triggerRun()` for the keyboard accelerator), `TaskDetailsPanel.scala` (selected task/input metadata + a single Source/Rendered-toggle SQL viewer — uses `SqlView` and chips for status/deps/metrics; the validation chip opens the popout edit dialog), `SqlHighlighter.scala` (pure-Scala tokenizer: keywords/functions/strings/numbers/comments/templates — testable, no JavaFX deps), `SqlView.scala` (read-only highlighted SQL pane built on `HBox` per line inside a `ScrollPane`; toolbar with Copy + optional Open-in-editor button), `ExternalEditor.scala` (launches `TRANSFORMER_EDITOR` if set, else macOS Terminal+`nvim`; no-wait launch via `ProcessBuilder`), `ResultsTabPane.scala` (5 tabs: **Task details**, Output data, Validations — per-validation cards with an Edit… button that pops out the `AddValidationDialog.showEdit` editor (round-trips through `JobFiles.writeValidationSql` / `deleteValidation`) — **SQL console**, Run log), `AddValidationDialog.scala` (the validation editor dialog used by both the task-details chip and the per-card Edit button; supports add + edit + delete), `SqlConsolePanel.scala` (ad-hoc SQL editor in a horizontal SplitPane with the results table; ⌘⏎ / Ctrl+Enter runs the query at the panel level; chips for registered views + Persist button — runs queries on a worker thread and materializes results in memory so Persist can replay them), `PersistDialog.scala` (modal: output dir, format, maxPartitions, CSV header — mirrors `OutputFilePath` fields), `ResultPersister.scala` (writes a `MaterializedView` via `CsvWriter.writePartitioned` / `ParquetWriter.writePartitioned` + stamps a `_SUCCESS` marker), `FxHelpers.scala` |
| `examples/scala_app/` | Sample app built as a `scala_binary` deploy jar — programmatic `DataJob(...)` API. | `src/main/scala/com/example/ExampleJob.scala` |
| `examples/directory_app/` | Sample app using `DirectoryJobLoader` — whole job is a folder of JSON configs + SQL files. | `src/main/scala/com/example/directory/DirectoryJobExample.scala`, `job/inputs/<view>/config.json`, `job/tables/<view>/main.sql`, `job/tables/<view>/validations/*.sql`. Accepts optional 3rd CLI arg for `executionTime` (ISO instant) so the same job can produce multiple partitions for testing. |
| `examples/jaffle_shop/` | Port of [dbt-labs/jaffle-shop](https://github.com/dbt-labs/jaffle-shop) to the directory format. 6 raw seed CSVs → 6 staging tables → 3 intermediate aggregations → 3 marts (`customers`, `orders`, `order_items`) + 3 passthroughs (`locations`, `products`, `supplies`). 26 DBT data_tests ported as zero-row validation queries. Exercises the DAG scheduler at a realistic scale (~150k rows, 15 tasks). Omissions vs. DBT: `metricflow_time_spine` (needs `dbt_date`); `customer_order_number` ROW_NUMBER column on `orders` (no window functions); semantic models / metrics / saved_queries / unit_tests (no equivalent layer). DBT CTEs are split — each `with X as (...)` becomes its own SQLTask, since `LogicalBuilder.fromItem` only accepts `Table` in FROM. | `src/main/scala/com/example/jaffle/JaffleShopExample.scala`, `job/data/raw_*.csv`, `job/inputs/<view>/config.json`, `job/tables/<view>/{main.sql,validations/}`. |
| `examples/gui_app/` | Sample app launching the GUI. Pulls in `gui/` + `sql/exec/`; parquet support comes transitively through `gui/`. | `src/main/scala/com/example/gui/GuiAppLauncher.scala` |
| `examples/polymarket/` | Heavy-load directory-driven pipeline over the [Polymarket tick-level orderbook Kaggle dataset](https://www.kaggle.com/datasets/marvingozo/polymarket-tick-level-orderbook-dataset). 5 parquet inputs (1 daily orderbook ≈131M rows — the shipped `stg_orderbook` filters to its first 6 hours / ≈27M rows; 21 daily snapshot files / 51M rows; ml features / 5.6M rows; 4.1M trades; 124K markets), 17 parquet output tables in staging / intermediate / mart / final layers, 58 validations. One branch (`mart_orderbook_quality_check`) carries a validation that asserts no market has snapshot latency above zero — real-feed data has nonzero latencies on every market, so the validation fails, the task is marked `ValidationFailed`, and its downstream `mart_quality_report` is `Skipped` while the other mart branches run to completion. Launcher exits 0 iff that exact 15-Succeeded / 1-ValidationFailed / 1-Skipped pattern matches. Exercises: streaming single-partition parquet scans of multi-GB inputs, glob-fed multi-partition parallel scans (snapshots), parallel-branch DAG scheduling, the failed-validation → skipped-downstream propagation path, the `partitionBy: "day={{ today }}"` template-driven partition layout, `_SUCCESS` markers stamped only on successful tasks. Needs `~/Downloads/archive/` dataset checkout and `-Xmx12g`. ~5 min on a fast Mac with the shipped 6-hour filter. | `src/main/scala/com/example/polymarket/PolymarketExample.scala`, `job/inputs/raw_{orderbook,snapshots,features,trades,markets}/config.json`, `job/tables/{stg,int,mart,final}_*/{main.sql,output.json,validations/}` |
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

Each pipeline-breaking operator owns a short-lived `Executors.newFixedThreadPool`
sized to `min(child.numPartitions, availableProcessors)`. It submits one
Callable per child partition, collects results, then merges sequentially.
The top-level executor drains the root operator's partitions sequentially
(usually `numPartitions = 1` after any breaker).

This means parallelism scales with input partitioning. CSV → one partition
per file. Parquet → one partition per file (we used to split per row group,
but `HParquetReader` has no skip-by-row-group API and seeking by reading-and-
discarding rows allocated a `SimpleGroup` per skipped row — O(rows²) over
wide schemas, fatal under GC pressure). Plan accordingly when you care
about throughput.

### 3a. Output is always a directory of part files

`OutputFilePath.path` is always interpreted as a *directory*. The writer fans
out one `part-NNNNN.<ext>` per source partition into that directory, using a
`min(numPartitions, cores)` thread pool inside `CsvWriter.writePartitioned` /
`ParquetWriter.writePartitioned`. Each part file is written via the existing
single-file writer with the atomic temp + rename pattern, so any failure aborts
all in-flight parts before throwing.

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

By default (`InputFilePath.cache = true`) every input is eagerly drained into a
`MaterializedView` during `DataJob.run`'s Phase 1, so SQL execution hits
in-memory batches with no further I/O. This is fine for typical CSV seeds
(MB-sized) but fatal for multi-GB parquet inputs — decompressed + boxed in
`ColumnarBatch`es they easily blow past a default heap.

Set `cache: false` (in the input's `config.json` or `InputFilePath(cache = false)`)
to skip materialization for that input. The raw streaming `CatalogView` (e.g.
the `ParquetReader` itself, partitioned per row group) gets registered directly,
and each query re-reads the underlying file(s). For aggregations / filters this
is cheap; for projection-heavy DAGs with many downstream readers it pays the
I/O cost N times — pick per-input.

When materialization OOMs anyway, `DataJob` appends a hint to the error message
pointing at this flag (see `oomHint` in `DataJob.scala`).

Above the operator level, `DataJob` runs SQLTasks as a DAG: `TaskDag.build`
parses each task's rendered SQL (main + every validation) with JSqlParser's
`TablesNamesFinder` and builds adjacency based on task viewName references.
`DataJob.runDag` then schedules tasks on a `min(tasks.size, availableProcessors)`
fixed pool, draining a `LinkedBlockingQueue` of finished indices to submit
newly-ready nodes. Independent branches run concurrently; downstream tasks of
a failure become `TaskStatus.Skipped`; independent siblings keep going. Setup-
time validation rejects unknown refs, duplicate viewNames, self-cycles, cycles,
and duplicate post-render output paths before any task runs.

### 4. `_SUCCESS` markers and historical-run discovery

After each successful task (`TaskStatus.Succeeded` only — not Failed, not
ValidationFailed) `DataJob.runOneTask` calls `RunMarker.write(dir, marker)`
which atomically stamps `<taskOutputDir>/_SUCCESS` with a small JSON blob:

```json
{
  "executionTime": "2026-01-01T05:30:21Z",
  "writtenAt":     "2026-05-18T03:17:03.099Z",
  "rowsProduced":  1234,
  "format":        "csv",
  "outputFiles":   ["part-00000.csv", "part-00001.csv"]
}
```

The marker pulls together three things the user/GUI later cares about: the
*temporal variables used to produce the output* (executionTime), a freshness
hint distinct from executionTime (writtenAt), and an inventory of what's in
the directory. `PathGlob.expand` skips dotfiles and underscore-prefixed files,
so the marker is invisible to any CSV/Parquet re-read.

`RunMarker.discover(templatedPattern)` is the partner operation: it replaces
every `{{...}}` in the pattern with `*`, walks the longest static prefix of
the result, and returns every directory under it that contains a marker —
sorted newest-first by `writtenAt`. This is how the GUI surfaces historical
runs for a partitioned output (`out/day={{today}}/<view>` → all `day=*`
partitions). The walk is depth-bounded so a pattern with a leading `*`
doesn't accidentally scan the whole disk.

Marker-write failures are swallowed by the runner — a missing marker must
never poison an otherwise-successful run.

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
