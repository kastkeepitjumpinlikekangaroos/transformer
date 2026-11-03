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
                                                  │  reject → every clause bind can't read
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
paths (sort comparators, window per-row callbacks) still use boxed `Any` via
`Expr.eval(batch, row)`. A hash join boxes no row: it gathers its output a
column at a time by selection vectors, and evaluates its residual condition
with `evalVec` over a chunk of candidate pairs (§8). See
[§5a Vectorized expression evaluation (`evalVec`)](#5a-vectorized-expression-evaluation-evalvec).

## Module map

| Path | Role | Key files |
|---|---|---|
| `core/` | Shared types crossed by every module. Zero deps. | `DataType.scala` (`sqlName`, the one spelling every grid and listing shows a type in: `BIGINT`, `DECIMAL(10,2)`), `ExecutionOptions.scala` (per-query knobs: spill, metrics, and `stopped`, §3g), `Schema.scala`, `ColumnarBatch.scala`, `Row.scala`, `Catalog.scala`, `NumberText.scala` (the one grammar text becomes a number by, for a CSV field and a CAST alike), `Scheduler.scala` (the shared `ForkJoinPool` every parallel call site funnels through), `ParallelDrain.scala` (a whole result's partitions read on that pool, each partition's result in partition order (`eachPartition`) or every batch in order with a bounded read-ahead (`inOrder`), with no queue and no pool task waiting for the consumer, §3), `SqlExecutor.scala` (`SqlExecutorRegistry`; `ExecutedQuery`, whose `claim` gives a partition to a reader on several threads), `WrittenParts.scala` (the part files a partitioned write committed, which a run record names), `Settings.scala` (every setting the engine reads from outside the program, a `transformer.*` system property or its `TRANSFORMER_*` environment twin, each with its default and how it parses: the one place that reads a property or the environment, rule R8), `Faults.scala` (the fault points every disk change of a run calls, armed by a test or `-Dtransformer.fault`, §3a) |
| `temporal/` | Date/time template variables. No deps. | `TemporalVariables.scala`, `TemplateRenderer.scala` |
| `read/csv/` | CSV input. Stdlib only. | `CsvOptions.scala` (also `forWrittenFile`, the options a job reads back its own CSV output with, and `checkSeparators`, which the writer shares), `CsvRowParser.scala` (state machine: an unquoted null string is NULL, a blank line is flagged rather than skipped, an unclosed quote fails), `CsvValues.scala` (the text of each type's values: what the writer prints, what the reader parses strictly, and what inference recognizes and joins), `CsvSchemaInferer.scala`, `PathGlob.scala`, `CsvReader.scala` (inference over the first rows of the glob's files in order, each later file read by its header's names, row width, blank lines as rows in a one-column file, typed parse errors naming file, line and column) |
| `read/parquet/` | Parquet input. Depends on `parquet-hadoop` + minimal Hadoop. | `ParquetReader.scala`, `ParquetColumns.scala` (the type each parquet column reads as and how its values decode, decided per file from that file's footer; matches a view's columns to each file's by name), `ParquetFooter.scala` (why a footer parquet-mr refused does not read, in the reader's words, from its Thrift walked leniently by `CompactThrift`), `ParquetFilterTranslator.scala` |
| `write/csv/` | CSV output. Stdlib only; uses `read/csv`'s `CsvValues`, so the writer and reader share one text per type. | `CsvWriter.scala` (`CsvWriter`, one file staged under a hidden temp name and committed by a rename, and `writePartitioned`, which commits every part together once all are written; `CsvRowWriter`, the header and rows' text on any `Writer`, which `transformer query` prints CSV through, so its stdout reads back as a file does) |
| `write/parquet/` | Parquet output. | `ParquetSchema.scala` (the parquet schema transformer writes for a `Schema`), `ParquetWriter.scala` (staged and committed as `CsvWriter` is) |
| `sql/parse/` | JSqlParser façade: one statement, none of its text lost on the way to the AST, and no tree too deep to walk. | `SqlParser.scala` (refuses text after the statement's `;` and a repeated tail clause JSqlParser overwrites — see §10; refuses parentheses that do not balance and a tree deeper than `MaxNesting`, 256 levels, once AND / OR chains are regrouped balanced; `unquote` / `quote`, the one rule for the name a quoted identifier names; `escapeLiteralText`, the text a JDBC `{d '...'}` escape was written with, which its node does not keep) |
| `sql/plan/` | Expression types, logical plan, JSqlParser → logical conversion, analyzer. | `Expr.scala`, `Ops.scala`, `FunctionCatalog.scala` (every function a query can call, one entry each, which the binder binds by and the README's lists, the fuzz generators and the tests read), `Funcs.scala` (the scalar functions' implementations), `Window.scala` (WindowFn / WindowSpec / WindowFrame ADTs), `LogicalPlan.scala`, `PendingCteView.scala` (placeholder for an unresolved CTE reference), `Analyzer.scala`, `LogicalBuilder.scala` (largest file in the repo), `LogicalOptimizer.scala` and its passes (`FilterPushdown`, `JoinOrder`, `ColumnProjectionPushdown`), `PlanValidator.scala` (the check every stage's plan passes, §9), `PlanSql.scala` (a plan printed as SQL the binder reads back, "Plans as SQL"), `PlanTree.scala` (a plan as EXPLAIN's tree), `UnsupportedClauses.scala` (the rejection pass that runs before binding, and its audit of every JSqlParser getter the binder does not read — see §10) |
| `sql/exec/` | Physical operators + planner + entry point. | `PhysicalPlan.scala`, `AggregateExec.scala`, `JoinExec.scala` (the hash join's four paths, every one probing through one resumable cursor, §8), `SortExec.scala` (also `TopNExec`, §2e), `DistinctExec.scala`, `UnionExec.scala`, `WindowExec.scala`, `RowBuf.scala`, `PhysicalPlanner.scala` (with `preMaterialize`, the pass that publishes every shared result before a plan runs, §2b), `SharedResult.scala` (the trait an operator holding a result computed once extends, an exchange or a broadcast join, and `SharedOnce`, the publication it is kept in), `CteResolver.scala` (inline-vs-materialize CTE pass + `PhysicalPlanView`), `SqlEngine.scala`, `Explain.scala` (what `EXPLAIN` returns) |
| `job/` | User-facing API + runner. Depends directly on `read/{csv,parquet}` and `write/{csv,parquet}` — parquet is a first-class format. | `DataJob.scala`, `InputFilePath.scala`, `OutputFilePath.scala`, `SQLTask.scala`, `JobResult.scala`, `InputResolver.scala`, `JobFiles.scala` (filesystem ops for the GUI's edit-validation flow — `writeValidationSql` / `deleteValidation`), `TaskDag.scala` (DAG analyzer/builder from SQL refs; `TaskDag` + `TaskDagNode` are public so the GUI can render without re-implementing), `TaskProgressListener.scala` (per-task callbacks fired from runner worker threads), `TaskRunRecord.scala` (`_run.json` write/read/discover for per-task run records — `Running` while a run writes the directory, then every final status, with `_validation-<slug>.csv` failure samples; `clear`, which deletes what a run wrote beside the record), `OutputDirectory.scala` (the order every writer of an output directory follows — check, mark `Running`, clear, stage, commit, final record — and what a run that ends without output leaves, `settle`; §3a), `JobRunRecord.scala` (`job.json` aggregate manifest pointing at every task's record + the run's warnings), `JobOutputLayout.scala` (classifies an output dir as `SingleRun`/`MultiRun`/`Empty` for the GUI's run picker), `DirectoryJobLoader.scala` (DBT-style directory loader; supports optional per-table `output.json` with `partitionBy`; threads default `<outputDir>/job.json` as `jobRunOutput` so the manifest lives alongside its data), `Json.scala` (stdlib JSON parser used by the loader + record readers) |
| `gui/` | JavaFX visualizer/runner. Depends on `job/`, `core/`, `read/{csv,parquet}/`, `sql/exec/`, `temporal/` (and on `write/{csv,parquet}/` through `job/`), plus `org.openjfx:javafx-{base,controls,graphics}` (the platform-classifier jars), RichTextFX (the SQL editor, with the flowless / reactfx / undofx / wellbehavedfx jars its API is made of) and AtlantaFX (the theme). `JobSession.scala` and `DagLayout.scala` build separately as `gui:session`, which has no JavaFX dependency; the vim engine (`VimKey`, `VimCommand`, `VimText`, `VimEngine`) builds as `gui:vim`, with no dependency at all. `gui` exports both, so tests and the fuzz harness reach them without the toolkit. What both GUIs (this one and `viewer/`) are built from is `gui:components`: `Theme`, `transformer.css`, `Icons`, `FxHelpers`, `SqlHighlighter`, `SqlEditor`, `VimKeyEvents`, `SqlView`, `SqlErrorDialog`, `ShortcutsDialog`, `PersistDialog`, `ResultPersister`, `DataGrid`, `QueryConsole`, `ChartPane`, `ChartCanvas` and `GraphCanvas`; `gui` exports it too. What a chart draws (`ChartData`, `ChartScale`) builds as `gui:chart`, with no JavaFX. | `GuiApp.scala` (Application boot: installs the theme, then a BorderPane with the menu bar (in the macOS menu bar there) and the header (`ControlsPanel`) above a vertical split of the DAG over the ResultsTabPane; scene-level ⌘R / Ctrl+R accelerator calls `controls.triggerRun()`; File, View (fit, zoom, Vim Keybindings) and Help menus), `Theme.scala` (AtlantaFX Primer Dark as the user-agent stylesheet; `Theme.style` adds `transformer.css` and a generated stylesheet naming the installed monospace family to every scene, dialog and alert; `Palette`, the theme's colours, for the canvas), `transformer.css` (the GUI's own style classes in the theme's looked-up colours, and the SQL token colours; ships in the jar as `com/transformer/gui/transformer.css`), `Icons.scala` (SVG line icons), `JobSession.scala` (mutable FX-thread session state — jobDir, executionTime, outputDir, DAG, per-task UI states, task records, historical runs, job manifest, available run snapshots, selected run; loads `<outputDir>/job.json` (or any sibling snapshot the user picks) and follows each task's `runFile` to reconstruct full `TaskStatus` including `ValidationFailed(failures)` with sample CSVs, and a task whose record says `Running` as `UiTaskState.Unfinished`; `buildInteractiveCatalog` assembles a Catalog over inputs + persisted task outputs for ad-hoc SQL), `DagLayout.scala` (pure-Scala layered DAG layout), `GraphCanvas.scala` (components: `GraphCanvas`, the canvas both GUIs draw graphs on — a dot grid, pan/zoom, cards with a bar in a status colour, curved edges, fitting text; `GraphOverlay`, the zoom controls and a summary row over one), `DagCanvas.scala` (`DagCanvas`: the job's DAG on a `GraphCanvas` — the selection's edges in the accent colour, click/double-click; `DagOverlay`: the per-status task counts over it), `ControlsPanel.scala` (the header: open job, execution date + `HH:mm:ss` time, output-dir field, optional run picker for multi-run output dirs, a status pill with live progress, Run on a worker thread; exposes `triggerRun()` for the keyboard accelerator), `TaskDetailsPanel.scala` (the selected task/input: a header with the actions on it, a column of details — status chips, deps, output, error, validations — and a Source/Rendered SQL column editable in place, where vim's `:w` saves and `:q` cancels), `SqlEditor.scala` (the SQL editor everywhere: RichTextFX `CodeArea`, `SqlHighlighter` styling, line numbers, a status line, and vim through `VimEngine` over a `VimBuffer` on the area while `SqlEditor.vimEnabled` is on, remembered in java.util.prefs), `VimKeyEvents.scala` (a JavaFX key event as an engine key, a platform shortcut, or the window's), `VimKey.scala` / `VimCommand.scala` / `VimText.scala` / `VimEngine.scala` (`gui:vim`: key notation; the normal/visual command grammar and its parser; lines, words, paragraphs, text objects and vim regex over a string; and the engine — modes, operators, registers, undo, `.`, macros, search, ex commands), `SqlHighlighter.scala` (pure-Scala tokenizer: keywords, functions (a word before `(`), types (where one is written), strings, numbers, comments, templates, and each kind's style class — testable, no JavaFX deps), `SqlView.scala` (a read-only `SqlEditor` the host can make editable, with a Copy action for the host's header), `ResultsTabPane.scala` (5 tabs: **Task details**, Output data, Validations — per-validation cards with an Edit… button that pops out the `AddValidationDialog.showEdit` editor (round-trips through `JobFiles.writeValidationSql` / `deleteValidation`) — **SQL console**, Run log), `AddValidationDialog.scala` (the validation editor dialog used by both the task-details chip and the per-card Edit button; supports add + edit + delete; `:w` presses Save, `:q` Cancel), `AddTableDialog.scala`, `AddInputDialog.scala`, `QueryConsole.scala` (components: a `SqlEditor` with Run query and Persist results… beside the result, or above it with `Orientation.VERTICAL`, as the data viewer's query tabs have it: a `DataGrid` of its rows in a Rows tab beside a Chart tab; ⌘⏎ / Ctrl+Enter runs it; the host's `prepare` runs on the FX thread and returns the catalog builder the worker calls; materializes the result, its partitions read at once on the pool with a stop of its own (`ParallelDrain.eachPartition`, a failure in one stopping the rest), so Persist can replay it and the chart read every row), `ChartPane.scala` (components: the Chart tab — the type, X, Y and its aggregation, Split by — over a `ChartCanvas`, worked out on a worker while the tab shows; Save PNG… paints the chart again at twice its size and writes it with `ImageIO`), `ChartCanvas.scala` (components: bars, lines, areas, dots or a pie on a plain `Canvas` — axes, legend, the pointer's reading; below 240px tall, axes untitled to keep the plot's height — and `image`, the same paint at a scale), `ChartData.scala` (`gui:chart`: `ChartSpec`, what to draw, and `ChartSpec.fit`, which suggests one for a result and carries one over to the next; `ChartData.build`, one pass over a result's batches into what each X and series hold, then series in order, folded into Other past eight), `ChartScale.scala` (`gui:chart`: round ticks, calendar ticks in UTC, and how ticks, values and times are written), `DataGrid.scala` (components: rows in a `TableView` — each column's type under its name, NULL in its own style, numbers right, sorting by value, ⌘C copies rows — plus `DataGrid.sample`, a view's first rows), `SqlConsolePanel.scala` (the job's console: chips for the registered views above a `QueryConsole` over `JobSession.buildInteractiveCatalog`), `PersistDialog.scala` (modal: output dir, format, maxPartitions, CSV header — mirrors `OutputFilePath` fields), `ResultPersister.scala` (writes a `MaterializedView` through `job.OutputDirectory`, as a job's task writes its directory: strays refused, a `Running` mark, parts staged and committed, a `Succeeded` record naming them, or a `Failed` one and no part file), `ShortcutsDialog.scala` (Help > Keyboard Shortcuts), `SqlErrorDialog.scala`, `FxHelpers.scala` |
| `viewer/` | The data viewer, a standalone JavaFX app (`//:transformer_viewer`): every job output in a folder as a dataset, SQL over all of their tables with a chart of each result, and an inspector per table (its rows, columns, recorded details and lineage within and across jobs). Depends on `gui:components`, `job/`, `core/`, `sql/exec/`. `viewer:model` (`RunFolder`, `Lineage`, `LineageLayout`, `ViewerSession` with `QueryCatalog`, `InspectorView` and `InspectorHistory`) has no JavaFX; see §4. | `ViewerApp.scala` (the window, menus and header; `ViewerMain`, the entry point, apart from the `Application` so `java -jar` starts it), `RunFolder.scala` (finding the runs, grouping them into datasets, names; `TableInfo.status`, `Running` for a table whose `_run.json` says so whatever `job.json` says), `Lineage.scala` (nodes, edges, input → table matching, a node's whole lineage), `LineageLayout.scala` (boxes and cards, of the folder or of one lineage), `ViewerSession.scala` (state, selection, the run shown per dataset, finding a selection again in a folder read again, `QueryCatalog`, an inspector's views and Back/Forward history), `ViewerTabs.scala` (the workspace: query tabs, inspector tabs, the preview tab), `InspectorPane.scala` (an inspector's header and views; `SamplePane`, `ColumnsPane`, `TablesPane`), `DetailsPanel.scala` (the Details view), `LineageCanvas.scala` (the Lineage view on a `GraphCanvas`; `LineageOverlay` with its whole-folder switch), `DatasetTree.scala` (the list with its filter) |
| `cli/` | The `transformer` command line. `:cli` (`Cli.scala`) parses `run` / `query` / `gui` / `help`, runs a job directory headless through `DirectoryJobLoader` with a `TaskProgressListener` printing one line per finished task (exit code 0 = every task Succeeded, 1 = anything else or the job would not load, 2 = usage), and runs `query` (§11): SQL over files named by flag, a job's tables, the CLI's own `CREATE VIEW` / `DROP VIEW` / `COPY`, dot commands, seven output formats, and a JLine shell. No JavaFX, so `cli_test` drives it directly; it depends on `gui:session`, the GUI's toolkit-free `JobSession`, for `--job`. `:main` (`TransformerMain.scala`) adds the `gui` dispatch to `GuiApp`. | `Cli.scala`, `SqlScript.scala` (a script cut into statements and dot commands; the CLI's statements read off the front of one; a WITH put ahead of a query), `QuerySession.scala` (the tables, the job's views and the session's views, each statement run, views defined ahead of what reads them, COPY to a file or to a directory of a parquet file a partition, dot commands; `Io` and `Cancel`, whose `child` is a statement's own stop), `ResultPrinter.scala` (`OutputFormat`, the box table and the other formats, and a result of several partitions read on the pool: `TableSlices`, what a table keeps of each partition), `Shell.scala` (the JLine shell: multi-line input, history, completion, Ctrl-C), `TransformerMain.scala` |
| `examples/scala_app/` | Sample app built as a `scala_binary` deploy jar — programmatic `DataJob(...)` API. | `src/main/scala/com/example/ExampleJob.scala` |
| `examples/directory_app/` | Sample app using `DirectoryJobLoader` — whole job is a folder of JSON configs + SQL files. | `src/main/scala/com/example/directory/DirectoryJobExample.scala`, `job/inputs/<view>/config.json`, `job/tables/<view>/main.sql`, `job/tables/<view>/validations/*.sql`. Accepts optional 3rd CLI arg for `executionTime` (ISO instant) so the same job can produce multiple partitions for testing. |
| `examples/jaffle_shop/` | Port of [dbt-labs/jaffle-shop](https://github.com/dbt-labs/jaffle-shop) to the directory format. 6 raw seed CSVs → 6 staging tables → 3 intermediate aggregations → 3 marts (`customers`, `orders`, `order_items`) + 3 passthroughs (`locations`, `products`, `supplies`). 26 DBT data_tests ported as zero-row validation queries. Exercises the DAG scheduler at a realistic scale (~150k rows, 15 tasks). Omissions vs. DBT: `metricflow_time_spine` (needs `dbt_date`); `customer_order_number` ROW_NUMBER column on `orders` (no window functions); semantic models / metrics / saved_queries / unit_tests (no equivalent layer). DBT CTEs are mostly split into their own SQLTasks (the staging -> intermediate -> mart layering), but `order_items_summary` keeps an in-query `WITH` to exercise CTE support end-to-end through the job runner; `LogicalBuilder.fromItem` resolves CTE names from the WITH scope before falling back to the catalog. | `src/main/scala/com/example/jaffle/JaffleShopExample.scala`, `job/data/raw_*.csv`, `job/inputs/<view>/config.json`, `job/tables/<view>/{main.sql,validations/}`. |
| `//:transformer` (root `BUILD.bazel`) | The product binary. `bazel build //:transformer_deploy.jar` is the single jar users run (`run` headless, `gui` desktop), bundling the JavaFX classifier jars for mac-aarch64 and Windows. | `BUILD.bazel` |
| `site/` | The project website: static `index.html` (pitch, polymarket benchmark) and `setup.html` (Java 21, building the jar, jaffle-shop walkthrough) sharing `style.css`. No build step and no external assets. Its claims (commands, numbers, CLI output) are user-facing docs; keep them in step with README. | `index.html`, `setup.html`, `style.css` |
| `examples/gui_app/` | Sample app launching the GUI. Pulls in `gui/` + `sql/exec/`; parquet support comes transitively through `gui/`. | `src/main/scala/com/example/gui/GuiAppLauncher.scala` |
| `examples/polymarket/` | Heavy-load directory-driven pipeline over the [Polymarket tick-level orderbook Kaggle dataset](https://www.kaggle.com/datasets/marvingozo/polymarket-tick-level-orderbook-dataset). 5 parquet inputs (1 daily orderbook ≈131M rows — the shipped `stg_orderbook` filters to its first 6 hours / ≈27M rows; 21 daily snapshot files / 51M rows; ml features / 5.6M rows; 4.1M trades; 124K markets), 17 parquet output tables in staging / intermediate / mart / final layers, 58 validations. Two branches are EXPECTED to ValidationFail on real-feed data: `mart_orderbook_quality_check` carries an INTENTIONAL validation asserting no market has snapshot latency above zero (real data does, so the task is `ValidationFailed` and its downstream `mart_quality_report` is `Skipped`); `final_combined_report` carries a `category_not_null` validation that surfaces NULL `category_normalized` rows propagated through `mart_market_overview`'s LEFT JOIN against `int_markets_categorized` (orderbook rows whose `condition_id` doesn't match a market row leave a NULL category in the rollup). The other three mart branches (overview, high-activity, volatility) run to completion. Launcher exits 0 iff that exact 14-Succeeded / 2-ValidationFailed / 1-Skipped pattern matches. Exercises: row-group-level parallel parquet scans of multi-GB inputs (a single 1GB file with 139 row groups splits into ~4 partitions packed to ~256MB each), glob-fed multi-partition parallel scans (snapshots), parallel-branch DAG scheduling, the failed-validation → skipped-downstream propagation path, the `partitionBy: "day={{ today }}"` template-driven partition layout, the `_run.json` + `job.json` run-records written by every task (the failed and skipped tasks get their own records too, so the GUI's run picker can replay the failure later). Needs `~/Downloads/archive/` dataset checkout and `-Xmx12g`. ~4 min on a fast Mac with the shipped 6-hour filter (was ~8 min before row-group splits). | `src/main/scala/com/example/polymarket/PolymarketExample.scala`, `job/inputs/raw_{orderbook,snapshots,features,trades,markets}/config.json`, `job/tables/{stg,int,mart,final}_*/{main.sql,output.json,validations/}` |
| `tools/parquet_peek/` | Stand-alone CLI for inspecting a parquet file or glob — schema, partition count, footer-derived `exactRowCount`, and a configurable sample of decoded rows. Reader-only; no SQL engine. Strings >80 chars are truncated so JSON-blob columns stay legible. | `tools/parquet_peek/ParquetPeek.scala` |
| `tools/mutation/` | The mutation-testing tool (docs/testing.md, "Mutation testing"): a lexer that tells code from strings, characters, comments and backquoted names; the mutator that lists every site of eight operators in a Scala source; a sample stratified by operator and the ledger of equivalent mutants; and the runner that takes each mutant through Bazel's stages in a worktree outside the repo, reading Bazel's build event protocol. Links nothing of the engine. | `tools/mutation/Mutator.scala`, `tools/mutation/Campaign.scala` |
| `src/test/scala/com/transformer/bytecode/` | Test-only: the class-file reader the tests over compiled classes share, and the rules the compiled jars are held to (plans/testing/18). No bytecode library: the reader is JVMS chapter 4 by hand. | `ClassFile.scala` (`bytecode:classfile`: the constant pool, each method's code decoded an instruction at a time, and every call, field access, string constant and float comparison found in the method that holds it, with its source line; `sql/plan:unsupported_clauses_test` reads the binder through it), `BytecodeRules.scala` (each rule: its modules, what it bans, its advice, its allowlist of methods and reasons), `BytecodeRulesTest.scala` (`bytecode_rules_test`) |

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
`InputResolver.resolve` + `DataJob.writeOutput` + `TaskRunRecord.outputView`
(which `DataJob.materializeIfNeeded`, the GUI's previews and the data viewer
all read an output through).

### 2. ColumnarBatch + RowBuf

`ColumnarBatch` is the unit that flows through operators. Each column is
typed:

- Numerics (Int, Long, Float, Double, Boolean, Date as days, Timestamp as
  micros): primitive array + `java.util.BitSet` of nulls.
- References (String, Binary, Decimal): the array itself holds `null` for SQL
  NULL — no separate bitset.

A STRING holds text: never half a surrogate pair, which UTF-8 cannot encode.
No reader gives one and no function makes one of text, and where one could
come in it is refused: a string literal at bind time, a `MaterializedView`
built in Scala when it is built, and a `CatalogView` of one's own when a scan
reads it (`CatalogView.stringsAreText`, which the readers and
`MaterializedView` say for themselves, so their scans check nothing).

Operators emit batches in two ways:
- **Pipeline operators** (Scan/Filter/Project/LocalLimit) preserve `numPartitions`
  and stream batches via `Iterator[ColumnarBatch]`.
- **Pipeline breakers** materialize across child partitions before they can emit.
  How they reshape output partitions depends on whether the planner inserts an
  `ExchangeExec` above them (see §2b):
  - **Sharded breakers** — HashAggregate (with non-empty `GROUP BY`),
    Distinct, equi-HashJoin, and Window (when every spec shares the same
    non-empty PARTITION BY) all carry an exchange-aware path the planner
    activates by wrapping their child(ren) in `ExchangeExec`. When activated,
    the breaker's `execute(p)` consumes only shard `p`, builds its state for
    that one shard, and emits `numPartitions = K` — downstream operators stay
    parallel. Joins exchange both sides by their respective equi-join keys
    with the same K so matching rows collocate; per-shard build + probe +
    outer-emit needs no cross-shard coordination. Whether the planner
    activates the sharded path is gated on
    [[com.transformer.sql.plan.LogicalPlanCardinality]] estimates (see §2b).
  - **Collapsing breakers** — Sort, TopN (§2e), GlobalLimit, and any of the above when
    the planner's cardinality gates aren't met (most aggregates by default;
    windows whose specs disagree on PARTITION BY or whose PARTITION BY is
    empty). Fan out across the child's partitions via `Scheduler.submitAndAwaitAll`
    (a window through `ParallelDrain.eachPartition`, §6) to build their
    state, merge across the partials, and emit `numPartitions = 1`.
  - **Broadcast joins** — an equi-join under the broadcast threshold that
    keeps no unmatched build row (a planned INNER, LEFT or RIGHT join) is no
    breaker on its probe side: its build side is built once, before the plan
    runs (§2b), and it runs in its probe side's partitions, each streaming
    its own probe partition against the shared build side. A join that keeps
    unmatched build rows (a planned FULL join) and one with no equality run
    on one partition, in waves (§8).

`SqlEngine.execute` returns an `ExecutedQuery` that exposes both `numPartitions`
+ `partition(i)` and a flattened `batches` view. The writer pipeline pulls
per-partition iterators so it can fan out one output part file per partition
in parallel, and so do in-memory materialization (`MaterializedView.fromQuery`)
and the readers of a whole result, `transformer query` and the GUI's query
console, through `core/ParallelDrain` (§3); a validation's drain uses the flat
view, so a validation whose root is a join probes a partition at a time. A
partition may move from thread to thread between reads, each finished before
the next begins: no iterator keeps state tied to its thread, and no batch
changes once `next()` has returned it.

When operators need to evaluate an `Expr` over a *materialized row* (window
callbacks), they use `RowBuf` (sort comparators read a boxed row through
`RowView`, which allocates no batch):
a reusable 1-row batch that the surrounding loop refills with `.set(rowArr)`
or `.setFromBatch(srcBatch, srcRow)`. Don't allocate fresh `ColumnarBatch`es
per row in tight loops — that defeats the JIT.

`ColumnarBatch.selectByBoolean(mask: BooleanVector)` is the canonical
WHERE-applies-NULL-as-false filter primitive. It carries an
all-pass fast path: when every row passes the mask the original batch is
returned unchanged (no per-column copy, no fresh vector allocations) —
typical for the leaf-scan filters in ETL workloads where the parquet
row-group skip has already discarded the non-matching rows at the stats
level. `FilterExec.execute` evaluates the predicate via `Expr.evalVec`
and hands the resulting `BooleanVector` straight to `selectByBoolean`,
which removes the intermediate `Array[Boolean]` mask the older path
allocated as a bridge.

### 2a. KeyCodec — packed keys for pipeline breakers

`HashAggregate`, `HashJoin`, `Distinct`, and `WindowExec.processSpec` all
key into a `HashMap` per row. The old implementation built a `Seq[Any]` per
row (allocation + boxed-element walk on every `hashCode`/`equals`), which
dominated CPU once group cardinality climbed into the millions.
`core/HashKeys.scala` replaces that with a small codec hierarchy:

- **`PackedBytesCodec`** — chosen when every key column is a fixed-width
  primitive (Int / Long / Float / Double / Boolean / Date / Timestamp).
  Packs the key into a `byte[]` (`ceil(nKeys/8)` null bits + concatenated
  primitive value bytes) wrapped in a `BytesKey` whose `equals` calls
  `Arrays.equals` (JIT-intrinsifies to SIMD) and whose `hashCode` is cached
  at construction. The fast `encodeFromBatch(batch, row)` path reads
  primitives straight from the typed `ColumnVector`s — zero boxing.
- **`ObjectArrayCodec`** — chosen when any key column is variable-width
  (String / Binary / Decimal / NullType). Copies the row's key values into
  a fresh `Array[AnyRef]` wrapped in an `ObjectArrayKey` (cached hashCode,
  element-wise `equals` with `Array[Byte]` special-cased for structural
  equality). Still one allocation per row + two on insert, but the `Seq`
  wrapper overhead and dynamic-dispatch walk are gone. A single
  variable-width key column takes `SingleObjectKeyCodec`, the same thing
  without the array.

**Scratch-key lookup path.** Operators that probe a `HashMap` per row
(`HashAggregateExec`, `DistinctExec`) hold a reusable scratch wrapper —
`BytesKeyScratch` for the packed codec, `ObjectArrayKeyScratch` for the
object-array codec — and rewrite its bytes/values in place via
`KeyCodec.encodeIntoScratch(scratch, batch, row)` before each probe. On a
HashMap hit the scratch stays unmaterialized; on a miss the operator clones
it into an owned key via `KeyCodec.ownFromScratch`. For aggregations with
high group repeat factors (`int_orderbook_market_summary` groups 105M rows
into 13K keys) this collapses the per-row two-allocation cost of
`encodeFromBatch` down to roughly one allocation per unique key.
- **`EmptyKeyCodec`** — singleton sentinel for zero-column keys
  (`GROUP BY ()` from `SELECT COUNT(*)`). Every encode collapses to the
  same object so all rows land in one bucket.

`KeyCodec.forColumns(indices, types)` selects the right codec from key
column types. Operators detect when every key expression is a pure
`ColRefExpr` (the common case — `GROUP BY a, b` resolves to plain column
references) and use `encodeFromBatch` for the boxing-free fast path;
otherwise they eval the expressions into a reusable `Array[Any]` and call
`encodeBoxed`. NULL keys group together (GROUP BY semantics) via dedicated
null bits / null elements; for hash-join probes that need NULL to short-
circuit a non-match, the codec exposes `encodeFromBatchSkipIfAnyNull`,
which returns the Java `null` sentinel instead of a key.

**A `NaN` or `-0.0` key.** A float key agrees with `Ops.eq`, whose floats
follow `Ops.floatCompare`: every `NaN` is one value and `-0.0` is `0.0`.
`PackedBytesCodec` writes a float through `KeyCodec.floatKeyBits` /
`doubleKeyBits`, the bits with `-0.0` read as `0.0` (`floatToIntBits` already
gives every `NaN` one pattern), and `ObjectArrayCodec` (a mixed key with a float
column) and the `HashSet`s behind `CountDistinctState` and `DistinctState` hold a
boxed float as `KeyCodec.floatKeyValue`, so `Double.equals`, which separates
the zeros, never sees `-0.0`. Shard routing (`HashPartitioner.hashCell`) and the
spill buckets (`HashJoinExec.columnHashCode`) hash the same bits. So grouping,
`DISTINCT`, `COUNT` / `SUM` / `AVG(DISTINCT …)`, `PARTITION BY` and equi-join
agree with `=` on these values; until plans/robustness/21 the keys went by bit
pattern while `=` followed IEEE, and a hash join matched `NaN` to `NaN` that the
same predicate as a filter did not.

**A decimal or byte-array key.** For these two types a key agrees with `Ops.eq`
exactly. A byte array is compared and hashed by content (a raw array's own
`equals` and `hashCode` are its identity). A decimal is equal by value, but
`BigDecimal.equals` and `hashCode` include the scale, and a column built in
memory need not hold every value at its declared scale, so the object codecs
store a decimal as `KeyCodec.decimalKey`: the value
at the column's declared scale when it can be written there exactly, and
otherwise without its trailing zeros. Every spelling of a value is then one key,
the key decodes at the declared scale whichever spelling arrived first, and a
column that does hold its declared scale pays nothing (`decimalKey` returns the
value itself). The DISTINCT aggregates' sets hold the same representative,
through `DistinctElement`, with a byte array inside a `BytesKey`. A shard or a
spill bucket cannot use the declared scale, since the two sides of a join or a
`UNION` may declare different ones, so `HashPartitioner.decimalHash` routes a
decimal by its nearest double, which depends on the value alone; decimals a
double cannot tell apart share a route, which costs balance, never correctness.

`WindowExec` applies both rules at once, deliberately: `PARTITION BY` is an
identity and goes through a `KeyCodec` like every other breaker's key, while the
`ORDER BY` peer groups `RANK`/`DENSE_RANK` share a rank on are an ordering
question and go through `Ops.cmp` — the same comparison `sortPartition` used, so
the peer test and the sort cannot disagree. `sameKey` asked `Ops.eq` instead
until plan 06; see [gotchas.md](gotchas.md).

**Single-Long fast path (`LongHashMap`).** When `GROUP BY` / `JOIN ON` boils
down to one fixed-width-numeric ColRef key (Int / Long / Date / Timestamp /
Boolean — `KeyCodec.isLongFittable`), `HashAggregateExec` and `HashJoinExec`
bypass the codec entirely and use `LongHashMap[V]` — a hand-rolled open-
addressing primitive-long-keyed map in `core/HashKeys.scala`. The keymap
stores primitive `long` values directly (no `BytesKey` allocation, no
`java.lang.Long` boxing); the probe / per-row loop reads
`KeyCodec.readAsLong(batch.column(idx), row)` straight off the typed
`ColumnVector`. NULL keys are tracked separately (a single sidecar slot per
map), so GROUP BY NULL still groups together while equi-join NULL still
short-circuits to no-match. Insertion order is preserved by a parallel
`(entryKeys, entryValues)` pair indexed alongside the hash table, so
`HashAggregateExec`'s emit order matches the codec-path `LinkedHashMap`
order. The single-Long shape is the most common one for foreign-key joins
and integer-ID group-bys, so the fast path is worth its weight.

### 2b. ExchangeExec — hash-partition between pipeline breakers

Sharded pipeline breakers read from an `ExchangeExec` that hash-partitions
their input into `K` key-disjoint shards by the same key set the breaker
groups / joins / partitions by. Rows with the same key always land in the
same downstream shard, so each shard processes independently with no
cross-shard coordination — the K output partitions stay parallel through
the rest of the plan. The operators all carry both an exchange-aware
per-shard execution path AND a collapsing path (fan out + merge across
the child's natural partitions); the planner picks which based on
[[com.transformer.sql.plan.LogicalPlanCardinality]] estimates.

**Planner gates** (`PhysicalPlanner`):
- **HashAggregate / Distinct / Window** — insert `ExchangeExec` only when
  the estimated input row count is `≥ LogicalPlanCardinality.MinShardableSize`.
  Defaults to `Long.MaxValue` (sharding off), because the exchange's
  full-input scatter is a real cost that beats the cross-partition merge
  only when GROUP BY / PARTITION BY cardinality is high relative to input
  size; we don't have per-key NDV estimates yet. Workloads that profile as
  merge-bottlenecked can opt back in via the
  `transformer.scheduler.shard_min_size` setting (README, "Settings"). Window has an
  additional structural gate — every `WindowSpec` in the operator must
  share the same non-empty PARTITION BY key set, otherwise no single
  sharding satisfies all specs. No key may read `RAND()`
  (`Expr.readsRandom`): the exchange reads a key to route a row and the
  breaker reads it again in the shard, so `GROUP BY RAND() > 0.5` split each
  group across shards.
- **HashJoin** — broadcast-vs-shuffle decision: insert exchanges on
  both sides only when the smaller side estimate is
  `≥ LogicalPlanCardinality.BroadcastBuildThreshold` (default 1M, overridable
  via `transformer.scheduler.broadcast_threshold`). Below that, the
  build map fits in heap and a broadcast join (build the small side once,
  stream-probe the large one in its own partitions) is faster than
  scattering both sides. A key that reads `RAND()` is never shuffled, for
  the reason above.

Operators detect their mode by inspecting whether `child` (or both sides,
for joins) is an `ExchangeExec`. Direct construction of a breaker over a
non-exchange child silently picks collapsing mode (a join: broadcast, or in
waves when it keeps unmatched build rows) — robust for tests and future
callers that bypass the planner.

The exchange is the intra-JVM analog of Spark's shuffle exchange — no
serialization, no disk, no network; the materialized shuffle is K arrays
of `ColumnarBatch`es held in heap. `K` defaults to `Scheduler.parallelism`;
override with the `transformer.scheduler.shard_count` system property or
`TRANSFORMER_SCHEDULER_SHARD_COUNT` env var.

Routing uses a pinned Murmur3-style cell mix in `HashPartitioner.hashRow`.
The function dispatches on the key vector's concrete subtype so primitive
cells stay unboxed; a String goes through `hashCode`, a byte array through
`Arrays.hashCode` and a decimal through `decimalHash` (its value, whatever its
scale), so a route agrees with the key equality each shard's codec applies.
Cross-column combination is a 31-style
accumulator wrapped in Murmur3's 32-bit finalizer (`fmix32`) — the
finalizer spreads bits enough that the `% numShards` step distributes
contiguous small-integer keys evenly instead of clustering them into one
shard. NULL keys route by policy: `NullsToLast` (GROUP BY / DISTINCT /
PARTITION BY) puts every NULL-key row in shard `K-1` so SQL's "all NULLs
group together" semantics survive; `NullsToZero` (equi-join probes) puts
them in shard `0` so the outer-join "unmatched probe" emission still sees
them. Both policies are deterministic and pinned across runs.

Materialization is **eager and bottom-up for engine-planned queries**:
right after physical planning, both drain entry points (`SqlEngine`'s two
execute paths and `CteResolver`'s CTE-body materialization) run
`PhysicalPlanner.preMaterialize`, a post-order walk (identity-deduped,
`MeteredPlan`-transparent) that publishes every **shared result** children-
first before any consumer task exists: an operator that holds a result
computed once and read by every partition extends `SharedResult`
(`sql/exec/SharedResult.scala`), and the pass calls its `materializeNow`.
Two do: each exchange, whose build reads all child partitions in parallel
through `Scheduler.submitAndAwaitAll`, routes every row, and scatters into K
size-exact output batches; and each broadcast hash join, whose build side is
built the same way, across its build partitions (§8). Consumers then only
ever take the published fast path — an exchange's `execute(s)` is an array
read, a broadcast join's `execute(p)` a read of its build side and a probe
of partition `p` opened by its first pull. A materialization failure is
thrown out of the pass at `SqlEngine.execute` time as itself — never
retried. The memory cost is roughly what `HashAggregate.partialAggregate`
already paid; no new spill semantics are introduced. A broadcast join's
build side lives as long as the query: built in `SqlEngine.execute`, it is
read by each partition as it is pulled, so a consumer holding the query
(the job runner holds it through the validations) holds the build side.

The bottom-up ordering is the K>1 **liveness guarantee** (plans/bugfixes/02f):
a wait on an unready shared result was the one wait in the engine that does
not follow the task-creation tree, so the one wait `ForkJoinTask.get()`
work-helping cannot rescue — helpJoin can inline a *consumer* of an exchange
beneath that exchange's own materialization frames (helping runs tasks from
stealers' queues, which need not be descendants of the awaited task), closing
a winner → shard-task → consumer → exchange cycle no monitor-vs-latch choice
avoids (reproduced at campaign scale, plans/bugfixes/02e). With every shared
result published before its consumers run, no worker ever waits on one being
ready; every remaining pool wait is a pure tree await, which work-helping
completes — the mechanism `//src/test/scala/com/transformer/core:scheduler_test`
pins, and the wedge shape `sql/exec:exchange_deadlock_stress_test` re-drives
green under churn, its inner joins now publishing builds too. A broadcast
join's partition iterator, a shard's and a grace bucket's fan nothing out of
their own and wait on no shared result (what they read may fan out, a tree);
the only other waits a join has are its build's and its waves'
`submitAndAwaitAll`, over its build partitions and over cursors reading their
own probe partitions (§8), pure trees.

Directly-constructed plans (tests, embedders that bypass the planner) fall
back to lazy one-shot materialization, through the same helper
(`SharedOnce`): concurrent first-callers race a CAS claim (`AtomicBoolean`);
exactly one winner computes the result with **no JVM monitor held**,
publishes it through a `@volatile` field, and opens a `CountDownLatch`;
losers park on a plain **uncompensated** `ready.await()` and wake together; a
consumer helpJoin inlines beneath the winner's own frames sees itself the
claimer and computes a private copy. The loser-wait is deliberately not
`ForkJoinPool.managedBlock`-wrapped (that compensation was the reverted
plans/bugfixes/02b regression — see §3), nor a `lazy val` or `synchronized`,
which hold the operator's monitor across the computation's pool fan-out. A
broadcast join's fallback builds in `execute(p)` on the calling thread, never
on the first pull: a caller that skips the pass calls `execute(p)` for every
partition on one thread, where a build deferred to the first pull would race
from the consumer's pool tasks. This fallback keeps the helpJoin hazard: do
not drain lazily-nested shared results from pool tasks — run the
pre-materialization pass first, like the engine does (see
[gotchas.md](gotchas.md)).

### 2c. Spill-to-disk for breakers (opt-in)

`SortExec`, `HashAggregateExec`, and `DistinctExec` can spill partial
state to local parquet files when their in-memory buffer crosses a
byte threshold. The spill path is **disabled by default**; tasks opt in
via `output.json`'s `options` map:

```json
{
  "format": "parquet",
  "options": {
    "spill": "true",
    "spill_threshold_bytes": "1073741824",
    "spill_max_runs": "500"
  }
}
```

The plumbing flows from `DataJob.runOneTask` (which builds an
`ExecutionOptions` from `OutputFilePath.options`) through
`SqlExecutor.execute(_, _, opts)` and `PhysicalPlanner.plan(_, opts)` to
each spill-capable operator. With spill disabled the cost is one boolean
check per operator — `ExecutionOptions.Default` short-circuits all
threshold tracking. The GUI's SQL console sets only a query's stop (§3g),
so spill never activates for exploratory work.

Per-operator format:
- `SortExec` writes per-partition sorted "runs" as parquet files;
  the K-way merge heap consumes both in-memory tails and disk-resident
  runs via a uniform `SortCursor` abstraction.
- `HashAggregateExec` writes one row per group with `groupKey columns`
  + N `Binary` columns containing serialized `AggState`s (see
  `AggStateSerde`). At emit time every partition's spill files are
  folded back into the merged map. The codec map path and the
  `LongHashMap` fast path each spill independently. An aggregate with no
  GROUP BY keys never spills: it holds one group, so there is no map to
  bound, and with no aggregates either (`GROUP BY ()`) its state has no
  columns for a parquet file to hold.
- `DistinctExec` writes one row per distinct key (the operator's output
  columns, positionally renamed — see below) and the union step folds
  spill files back into the final set.
- `HashJoinExec` uses **grace hash**: with spill enabled, on its first
  pull both sides are routed into K=16 disk buckets by
  `fmix32(hash(joinKeys)) % K`; then the `(build_k, probe_k)` pairs are
  processed one after another as the output is pulled — load `build_k`
  into an in-memory keymap, stream `probe_k` against it a batch at a time,
  emit matches, then the bucket's unmatched build rows. Outer-join
  unmatched-build emission happens per-bucket (a build row unmatched in its
  bucket is unmatched globally because both sides hash on the same key). So
  spill bounds a join's result as well as its build side: it holds one
  bucket's build rows and an output batch, both inputs on disk. Restricted to
  equi-joins; non-equi joins ignore the spill option since they have
  no key to bucket on.
- `WindowExec` uses **bucketed spill**: with spill enabled and a
  consistent non-empty PARTITION BY across every window spec, child
  rows route into K=16 disk buckets keyed by
  `fmix32(hash(partitionKey)) % K`. Each bucket is processed
  independently — load every row, group by exact partition key, sort
  within partition, compute window functions, emit. Window functions
  that need full partition visibility (LAG/LEAD, whole-partition
  aggregates) stay correct because every row sharing a partition key
  collocates in the same bucket. Empty PARTITION BY or mixed
  partition-keys across specs silently disable spill (there's no key
  set to bucket by); same gate the planner uses for sharded windows.

Spill files use **positional column names**, not the logical schema's.
Every read path above addresses spilled columns by INDEX and reinterprets
types from the operator's own logical schema, so the on-disk names carry
no information — and parquet's by-name column model cannot round-trip two
columns that share a name (a join output like `[k, v, k, v]`, or
name-colliding GROUP BY keys). Each write site wraps its schema in
`Spill.positionalSchema` (`_c0`, `_c1`, ...); `AggSpiller.spillSchema`
renames the group-key columns to `_k0`, `_k1`, ... ahead of the `_agg*`
state columns. This is spill-only — real output files (the non-spill
`ParquetWriter` callers) keep their real column names. See
[plans/bugfixes/01](../plans/bugfixes/01-spill-duplicate-column-names.md).
Both also store each column at `Spill.storedType` of its type: the type
itself, except a NULL-typed column (an untyped `NULL`, or an aggregate over
one), which parquet has no type for. It holds nothing but NULLs, so it is
stored as a BOOLEAN with no values, and the index-addressed readers put the
NULLs back into the operator's own NULL-typed column.

Limitations: DISTINCT aggregates (`COUNT`, `SUM` and `AVG(DISTINCT …)`)
silently disable spill on the whole operator because the underlying
`HashSet[Any]` doesn't round-trip through a typed schema. Grace hash and window spill both
use a fixed bucket count of 16 with no recursive bucketing — hot keys
whose bucket exceeds heap will still OOM at processing time (v1.1).
The temp-file base defaults to `${java.io.tmpdir}/transformer-spill`,
overridable with the `transformer.spill.dir` setting (or
`TRANSFORMER_SPILL_DIR`). Each JVM spills
into a directory of its own under it, `Spill.rootDir` (`jvm-<pid>-<suffix>`),
whose shutdown hook deletes that directory and nothing else; operator
subdirectories are wiped when the result iterator drains, and the hook
catches abandoned iterators. `Spill.cleanupStaleRoots` sweeps the base, never
anything beside it, deleting only the directories of JVMs no longer running
(their pid names no live process). The base itself was the root, shared, so
one JVM's exit deleted another's live spill files, and the sweep deleted every
entry beside it whose name began as its did up to the first `-`. A key that
reads `RAND()` never spills by bucket (grace hash, window), since bucketing
would read it a second time. Stress-test parity
(bit-equal output with spill on vs off at 1-byte threshold) is
enforced for every spill-capable operator in its respective
`*SpillTest`.

A spill file costs about half a millisecond to write and read back beyond
its data: the writer writes through `java.nio` rather than Hadoop's local file
system, which ran `chmod` in a process per file, and the reader builds its
parquet-mr read options from a configuration built once, where parquet-mr
parsed Hadoop's default resources for every open, twice a file. Together
they cost 12 ms a file, so an operator spilling a run per batch spent most of
its time opening files (plans/testing/05, W8).

### 2d. Per-operator instrumentation (opt-in)

Every operator has a per-partition counter set wired in at planner time.
When `ExecutionOptions.metricsEnabled = false` (the default),
`PhysicalPlanner.plan` returns the un-wrapped operator tree and the cost
on the disabled path is one branch + load — measured by
`benchmarks/micro/DisabledOverheadBench` to be below 1%. When enabled,
the planner wraps each operator's `execute(p)` iterator in a `final
class MeteredIterator` (final so the JIT inlines `hasNext` / `next`),
attaches a `MetricsNode` whose counter array is sized at construction
from the operator's `IdxCounterNames` array, and `DataJob.runOneTask`
writes a sibling `_perf.json` next to `_run.json` with the operator
tree snapshot plus per-task `ThreadMXBean` CPU / allocation / GC deltas.

Metrics watch a plan and never change it: an operator that runs per shard
over an exchange tells by its child, and sees through the wrapper that
meters it (`ExchangeExec.feeds`), so a sharded plan stays sharded with
metrics on.

The wrap is one walk of the planned tree (`PhysicalPlanner`'s `Walk`), which
rebuilds each operator over its wrapped children and wraps it, in a
`MeteredPlan` with metrics on and, under `--config=verify`
(`-Dtransformer.verify=true`, read once per JVM), in a `VerifiedPlan` inside
it, which checks each batch the operator emits against the schema it declares
(`core/BatchInvariants`: a vector per column of the declared type, the rows
within each vector, a DECIMAL that fits its type, a STRING holding text, the
batch's own names and types the operator's) and fails with an
`AssertionError` naming the operator by its line of the plan as `EXPLAIN
VERBOSE` prints it (`Explain.operatorLines`), the column, the row and the
value. Both wrappers are `PlanWrapper`s, which whatever reads a plan's shape
sees through (`ExchangeExec.feeds`, `preMaterialize`, `EXPLAIN`), so
neither changes the plan. Every operator the planner builds has a branch in the
walk; under verify one without fails the walk (with metrics alone it is left
unwrapped, its own output still metered), and `sql/exec:verify_test` finds the
operator classes by reflection and checks its queries build each. With neither
on, the walk does not run.

Enablement plumbing mirrors spill: a new `metricsEnabled: Boolean` field
on `ExecutionOptions` threaded through `SqlExecutor.execute` →
`SqlEngine.execute` → `PhysicalPlanner.plan` to the operator
constructors. Three routes: per-task `options.metrics = "true"` in
`output.json` (wins when explicitly set), sysprop
`transformer.metrics.enabled`, or env var `TRANSFORMER_METRICS_ENABLED`.

Counter storage is `Array[LongAdder]` (lock-free, contention-tolerant)
indexed by `final val Idx<Name>: Int` constants declared in each
operator's companion object. Names live in a parallel `IdxCounterNames:
Array[String]`; a per-operator unit test asserts
`IdxCounterNames.length == highest Idx<Name> + 1` so adding a counter
without bumping the array trips the build immediately. Increments on
the per-row hot path are `if (metricsNode != null)
metricsNode.counters(IdxX).add(d)` — allocation-free, single branch.
The on-disk format of `AggStateSerde` is untouched; timing-only
`SerdeStats` overloads let the operator attribute serde nanos to its
own counters without changing the bytes a spill run writes.

The full counter inventory plus enable-by-route + macro-bench + JMH
harness documentation lives in [benchmarking.md](benchmarking.md). The
on-disk `_perf.json` schema is defined in
`core/metrics/TaskMetricsRecord.scala` (schema version 1).

### 2e. Top-N for ORDER BY ... LIMIT

`PhysicalPlanner.topN` plans a `LIMIT n` over a sort as a `TopNExec`, through
the projections the binder puts between them (a key that is not selected) and
an `OFFSET m`, which makes it keep `n + m` rows and runs above it. Each
partition keeps, in a heap with the row that sorts last at its head, the `n`
rows that sort first of those it has read, evaluating each row's keys once as
it reads it; then the partitions' rows sort together and the first `n` are
returned. So it holds `n` rows a partition, every partition at once, where a
sort holds every row: `ORDER BY change_size DESC LIMIT 3` over 132M polymarket
rows runs in 3 s and 0.5 GB, where the sort ran out of a 2 GB heap.

It returns the first `n` rows of a stable sort: rows that sort alike come in
the order they were read, partition by partition, as `SortExec` returns them
below `SmallNThreshold` (its heap merge of larger inputs orders such rows by
the heap). A later row replaces the one at the head only when it sorts strictly
before it. `LIMIT 0`, a key that reads `RAND()` (a plain SELECT's binder computes
one in a column, but over a DISTINCT or a set operation the sort reads it
itself; plans/perf/15 records what that breaks) and a count past `TopNExec.MaxRows`
(10,000, with the offset) plan the sort, which can spill, instead.

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
call site submits Callables via `Scheduler.submit(c).await()` or the convenience
`Scheduler.submitAndAwaitAll`; a task that fails makes its waiter throw the
task's own exception (a fatal error stays wrapped in an `ExecutionException`),
where `ForkJoinTask.get` would throw a wrapper whose message is the chain of
class names. Nested submission is safe because a worker
awaiting its children with `ForkJoinTask.get`/`.join` work-*helps* — it steals
and runs the awaited tasks on its own stack, so an arbitrarily deep breaker tree
materialises without starving the pool. Do NOT "harden" this with
`ForkJoinPool.managedBlock`: plans/bugfixes/02b wrapped these waits in a
`ManagedBlocker`, whose compensation spawned a spare-thread storm that wedged the
pool under deep sharded nesting (reverted — see §2b and gotchas.md). Holding a JVM
monitor across a pool-blocking call is a related footgun (invisible to
work-stealing); `ExchangeExec`'s materialize used to do exactly that and is now
monitor-free (CAS claim + latch, see §2b) — keep it that way. Both sides of the
helping assumption are pinned by
`//src/test/scala/com/transformer/core:scheduler_test`: the same nested fan-out
completes via `.get()` on a 2-thread pool and wedges via a non-helping wait.
Helping is a tree-shape guarantee, not a blanket one: its helpJoin path can
inline non-descendant tasks beneath a worker's stack, which is how a K>1
sharded exchange can still deadlock (see §2b and gotchas.md).

**Reading a whole result: `ParallelDrain`.** `SqlEngine.execute` runs a
collapsing breaker at the root on the pool before it returns, but a scan and
its filters and projections, a union's concatenation, a join's probe (a
broadcast join's partitions, a join's waves, a shard's or a grace bucket's
probe) and a sharded breaker's emit run as their partitions are pulled, so a
consumer that pulls every partition on one thread decodes a scan, or probes a
join, on one core. `core/ParallelDrain` reads an `ExecutedQuery` on the pool
two ways, for `transformer query` (§11) and the GUI's query console, and a
plan's partitions one way, for `WindowExec` over its whole input (§6):

- `eachPartition(q, inFlight, stop)(f)`: up to `inFlight` pool tasks each take
  the next partition no task has taken (an `AtomicInteger`, so a slow one holds
  up no other) and run `f` over it to its end; the caller gets the results in
  partition order. A table, markdown and the console read through it.
- `inOrder(q, readAhead, chunk, stop)`: an iterator of the batches in partition
  order. A *fill* is a pool task that reads at most `chunk` batches of one
  partition, returns them and ends; a partition has one fill at a time. The
  iterator decides every fill on its own thread: a window of `readAhead`
  partitions from the one it reads, each partition's first fill submitted as
  it enters the window, the read partition's next fill as the iterator takes a
  chunk of it, and nothing before its first `hasNext`. So it holds at most
  `readAhead + 1` chunks not yet returned, and a consumer that stops taking
  holds no pool thread. The ordered formats, `COPY` and `--output` read
  through it.

`inFlight` and `readAhead` are `ParallelDrain.inFlight`: the partitions, the
pool's threads, the processors and the heap at 256 MB a reader, whichever is
fewest (8 at `-Xmx2g` on 8 cores, 1 on a pool of one thread); a chunk is 8
batches. A result of one partition (an aggregate, a sort, a LIMIT, EXPLAIN) is
read on the caller's thread, as `batches` reads it. Nothing in the pool waits
for the consumer: a task waits only for the tasks its partition's own
operators submit, a tree, which `Submitted.await` work-helps, and the consumer
waits only in `Submitted.await`. There is no queue between them: a pool task
parked on a bounded queue for a slow consumer is parked in
`ForkJoinPool.managedBlock`, which JDK 21's condition waits (`ArrayBlockingQueue`
and `LinkedBlockingQueue`'s `put` and `take`, `SynchronousQueue`) call by
themselves, and whose spare threads wedged the pool in plans/bugfixes/02b.
Each drain claims every partition before it reads one (`ExecutedQuery.claim`),
so another partition's failure cannot close one under the thread reading it.
At a partition's first failure the drain calls its stop, so every other
partition stops at its next batch (§3g), and the caller gets the
lowest-numbered partition's failure that is no stop, as itself
(`Scheduler.rethrowable`, a fatal error wrapped); `close()`, and
`eachPartition` before it returns, wait for everything they submitted.

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
file. Parquet → one partition per ~256MB of row groups, each counted as the
larger of its footer's uncompressed and compressed bytes and one byte at
least, so a group whose footer gives it none is still read: a small file with
a few row groups packs into one partition, a multi-GB file splits into
several. The split uses the low-level `ParquetFileReader.skipNextRowGroup`
to seek (metadata-only — no decoding), so a partition that starts at row group
50 pays O(50 metadata ticks) instead of O(rows-before-50) for the seek. Tune
per-input via `options("read_partition_size_bytes")` (e.g. drop it for highly
contested input where you want even more parallelism, raise it to consolidate
small files). Plan output cap (`OutputFilePath.maxPartitions`) accordingly —
the writer fans out 1:1 by default, so a 4-partition source produces 4 part
files unless capped, and so does a broadcast join probing it (§8).

### 3a. Output is always a directory of part files, committed by its record

`OutputFilePath.path` is always interpreted as a *directory*. The writer fans
out one `part-NNNNN.<ext>` per source partition into that directory using the
shared [[Scheduler]] pool.

Every writer of an output directory — a job's task (`DataJob.writeOutput`) and
the GUI's saved query result (`ResultPersister`) — goes through
`job/OutputDirectory.scala`, which holds the order a run writes in. The
record is the commit: a reader trusts the directory only when its `_run.json`
says `Succeeded` or `ValidationFailed`, and then reads exactly the part files
it names.

1. **Check** (`open`). A directory with `part-*` files and no `_run.json`
   (`TaskRunRecord.unrecordedPartFiles`) is refused: they are someone else's
   (plans/robustness/29).
2. **Mark.** `TaskRunRecord.write` writes a `Running` record — task name,
   execution time, `enqueuedAt`, `startedAt`, format, options and SQL; no
   `finishedAt`, no output files, no rows — replacing the earlier record in
   one rename.
3. **Clear.** `TaskRunRecord.clear` deletes the earlier run's top-level
   `part-*`, `_*` and `.*` files, all but `_run.json`.
4. **Stage** (`write`). Each partition writes its part under a hidden temp
   name, `.<part>.<nanos>.tmp` for CSV and `.<part>.<nanos>.tmp.parquet` for
   parquet. A writer's `finish()` flushes and closes without renaming.
5. **Commit.** Once every partition has succeeded, `writePartitioned` renames
   each staged file to its part name (`commit()`), in partition order, and
   returns the names it committed (`core.WrittenParts`). An all-empty parquet
   result gets one header-only placeholder; a CSV result of no partitions one
   empty part. A failure in any partition or rename makes `abortAll` delete
   every part, staged or committed (`abort()` deletes the target once
   committed).
6. The output is read back and validated (`materializeIfNeeded`, §3e).
7. **Record** (`finish`). The final record, naming the committed files, never
   a listing of the directory, replaces `Running`. It is the run's last write
   there: `_perf.json` and the validation samples go before it. One that
   cannot be written throws, and the task fails naming the write.

A run that ends without output — a failure after step 2, or a task that
failed before it or was skipped — runs `OutputDirectory.settle`: a directory
not yet marked is marked `Running` first (a directory of strays gets
nothing), then `clear`, then the `Failed` or `Skipped` record, so such a
task's directory holds its record and nothing else; a failure inside it
leaves `Running`. A process that dies after step 2 leaves `Running`, and the
next run's step 3 deletes whatever it left, staged files included. So nothing
deletes a file a record names before the record is replaced, and every step a
run can stop at leaves a record that says what the directory holds, or
`Running`, which says to trust nothing in it. (A `Skipped` or early `Failed`
record written first and cleared after sat beside the earlier run's parts
when the clear stopped part way; the fault matrices below found it.)

Every one of those disk changes is a fault point (`core/Faults.scala`):
`OutputDirectory` calls `Faults.at` before each record, by its status
(`running-record`, `final-record`, `failed-record`, `skipped-record`);
`TaskRunRecord.clear` before each deletion (`clear-delete`); the writers'
`writePartitioned` after a partition's first batch (`first-batch`) and before
each commit rename (`commit`); `DataJob` before the samples, `_perf.json` and
`job.json`. Unarmed, a call is one volatile read, per file. A test arms one
point to throw, to throw as a full disk does, or to halt the JVM
(`-Dtransformer.fault=<point>:<n>:<action>` in a child), and checks that the
directories say what they hold and that the next run is clean
(`job/data_job_test`, docs/testing.md "Fault points").

Readers: a task's downstream tasks read its output by its record's file names
(`TaskRunRecord.outputView`), only after the final record. The GUI and the
data viewer show a `Running` record read from disk as unfinished
(`UiTaskState.Unfinished`, `TableInfo.status`), and `outputView` refuses one.
Outside readers see no part file before the commit, and most skip names
starting with `.` or `_`, as the engine's own `PathGlob` does; after a crash
during the renames they can see some parts under a `Running` record. An older
binary reads `Running` as an unknown status, so `TaskRunRecord.read` returns
no record, but `isMarked` asks only whether the file exists, so its next run
still clears the directory. Two runs writing one directory at once stay
unsupported: a live `Running` record looks the same as a dead one.

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

Validation re-reads use `TaskRunRecord.outputView` over the files the run
committed. A bare directory read (`CsvReader.fromPath(dir)` /
`ParquetReader.fromPath(dir)`) is "every visible file inside" (via
`PathGlob.expand`, which skips dotfiles + underscore-prefixed files — the
records, samples, staged parts and Hadoop's CRC sidecars).

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
- Which loop a column gets is decided per file. `ParquetColumns.resolve`
  finds the view's columns in the file by name and gives each a `Decoding`
  from that file's own physical type and annotation, and the iterator asks
  parquet-mr for exactly those columns (`setRequestedSchema`) as the file
  types them. The request has to come from the file: parquet-mr decodes a
  column chunk as whatever type the request names, so a request rebuilt from
  the view's engine types (what `ParquetSchema` would write) decodes an
  `INT96` chunk as `INT64` and a `FIXED_LEN_BYTE_ARRAY` one as `BINARY`. The
  same lookup lets a glob's files order their columns differently and store a
  column in different ways; `ParquetReader.readFooters` checks every file
  against the first file's schema up front, so a file that lacks a column or
  reads one as another type fails when the view opens.
- A `Decoding` carries whatever its loop needs from the file. A `TIMESTAMP`'s
  carries its unit (`Decoding.Timestamps(unit)`): `MILLIS` values are
  multiplied into the microseconds a `TimestampVector` holds (after a range
  check, so an overflow fails the read rather than wrapping), `MICROS` copied,
  and `NANOS` truncated with `Math.floorDiv`. One glob can mix units, so the
  unit is read per file like everything else. A `DECIMAL`'s carries its
  precision and scale, one case per layout: the unscaled value as an `INT32`
  or `INT64` (`IntDecimals`, `LongDecimals`), or as big-endian two's
  complement in a `BINARY` or `FIXED_LEN_BYTE_ARRAY` (`ByteDecimals`). Two
  files can store one `DECIMAL(p,s)` column in different layouts.
- `ParquetColumns.decoding` lists every pairing of physical type and
  annotation it reads, and nothing falls through to the physical type: an
  unsigned `INT32` widens to `BIGINT` (`UnsignedInts`) and an unsigned
  `INT64` to `DECIMAL(20,0)` (`UnsignedLongs`), `FLOAT16` to `FLOAT`
  (`HalfFloats`), `JSON` and `ENUM` read as text, `BSON` and `UUID` as bytes,
  an unannotated `BINARY` as bytes, or as strictly decoded UTF-8 text under
  the input's `binaryAsString` (`BinaryText`, which pushes no comparison),
  and `TIME`, `INTERVAL`, a `REPEATED` column or a nested group fail, naming
  the file and the column. Each `Decoding` also says whether it `pushesComparisons`: true
  for a plain copy in the engine's order, false where the decode changes the
  stored bits or where the file orders them otherwise (an unsigned 8- or 16-bit
  integer, `UnsignedNarrowInts`, reads as an `INT` but is ordered unsigned).
  Only a column that pushes comparisons in every file takes a pushed one
  (see 3d).
- A file the reader cannot read fails in the reader's words, never
  parquet-mr's: a `ParquetReader.Unreadable` (an `IllegalArgumentException`)
  whose message names the file, and the column and row group where the
  reader knows them, with parquet-mr's exception as its cause. Three places
  wrap the library. Opening a file (`readFooters`, and the iterator's own
  open): when parquet-mr cannot read the footer, `ParquetFooter` reads it
  again, walking its compact Thrift leniently (`CompactThrift`, where a union
  member or an enum value newer than parquet-mr's generated classes is a
  number), and says what it finds: an encrypted file, a file that is not
  parquet, a logical type, encoding or codec newer than the library, a column
  chunk the schema lacks, an annotation the format does not allow on its type
  (found by converting each column alone with parquet-mr's own rules), or a
  footer that is not valid metadata. Reading a row group
  (`readNextRowGroup`, which reads each chunk's page headers): the column at
  fault is found by reading each chunk alone. Decoding a column (its
  dictionary page as its reader is made, its data pages in `decodeColumn`):
  the column is the one being read. `ParquetReader.libraryFailure` says what
  is the library's to wrap: not the reader's own refusals, not a fatal error
  or an interruption, but a missing codec library's `LinkageError`. A codec
  the classpath cannot decompress (`ParquetReader.decompresses`, a
  decompressor made once a codec) fails the view as it opens, as an engine
  type the file's column has none of does.
- Write: a custom `BatchWriteSupport` drives parquet-mr's `RecordConsumer`
  directly from the `ColumnarBatch` vectors. Each batch row maps to one
  `writer.write()` call (parquet-mr needs that 1:1 mapping so its
  `recordCount`-driven row-group flush bookkeeping stays correct) but the
  per-row dispatch is via a reusable `BatchRowRef` — no `SimpleGroup` per
  row, no `Integer`/`Long` boxing per cell. `ParquetSchema.toMessageType`
  picks each column's layout; a `DECIMAL(p,s)` is the spec's (`INT32` to 9
  digits, `INT64` to 18, else the narrowest `FIXED_LEN_BYTE_ARRAY`, its length
  from `ParquetSchema.decimalBytes`), written at the declared scale or not at
  all: a value that would need rounding, or has more than `p` digits, throws.

`ColumnarBatch.DefaultCapacity` is 8192 rows: every reader and operator emits
batches of at most that many. Larger batches amortize per-batch dispatch
(hasNext checks, allocations, writer flush boundaries) at the cost of more
in-flight memory; lower it if you have very wide schemas. It is read once per
JVM from the `transformer.batch_capacity` system property (or the
`TRANSFORMER_BATCH_CAPACITY` env var), which the test suite sets to 1 and 2
(`--config=batch1`, `batch2`) so that every batch boundary inside an operator
is crossed on a test's few rows ([testing.md](testing.md#whole-suite-configurations)).

**Per-row-group String intern cache.** `ParquetPartitionIterator` holds a
per-output-column `HashMap[Binary, String]` that's lazily allocated on the
first String-typed column it decodes and reset at every row-group
boundary (because the underlying `Binary` references point into the
just-freed page buffers). On any column with significant duplication —
the typical case for foreign-key columns like `market_id`, status flags,
side enums — the cache collapses the per-row `Binary.toStringUsingUTF8()`
allocation down to one `String` per unique value per row group. On the
polymarket orderbook scan that's ~13K String objects instead of ~210M,
which eliminates the dominant young-gen allocation source AND lets
downstream `ObjectArrayCodec`-keyed group-bys hit identity-equality
fast paths on the cached `String`'s cached `hashCode`. A key is the
decoder's own `Binary` (a dictionary value, found by reference), so a key
from a plain page keeps that page alive; a column therefore caches at most
`InternBudgetBytes` (4 MB) of values a row group, past which a new value is
decoded each time. Unbounded, a column of values that never repeat (polymarket
snapshots' JSON `data`, 317 MB a row group decompressed) kept all of them and
their pages, about 600 MB a row group on each thread reading one, and a scan
of it ran out of a 4 GB heap; bounded, it reads in 2 GB, and a foreign key's
distinct values fit many times over.

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
columns, comparisons on a timestamp column (its statistics are in the unit
its file stores, which varies by file; `IS NULL` on one still pushes), and
comparisons on any column some file stores as other bits than the engine
reads it as, or orders otherwise: `readFooters` collects the columns on which
every file's statistics judge a comparison as the engine would
(`Decoding.pushesComparisons`), and `withPushdownFilter` passes them to
`translate` as `comparable`. A string column pushes `=`, `<>` and `IN` on any
literal but a range only against one with every character below U+D800, since
its statistics are in UTF-8 (code point) order and the engine compares UTF-16
code units, and the two orders differ only where a character from U+E000 to
U+FFFF meets a surrogate pair (`ordersAlike`). The translator drops unsupported
conjuncts from the AND-chain rather than refusing to push the whole predicate;
the residual `FilterExec` covers them.

### 3e. Skip materialize re-read when no downstream consumes

When a task with `outputFile` set has neither validations nor a downstream
DAG dependent, `DataJob.runOneTask` does NOT re-open the just-written parquet
to register it in the catalog. The data lives on disk; nothing in the job
will read it. The old code unconditionally re-read for any task with a
`viewName`, which on the polymarket leaf `mart_*` tasks doubled the per-task
tail latency for no benefit.

When a task DOES have a consumer, how its view gets published depends on
whether it wrote anything. A task with an `outputFile` re-reads its own output
directory, so the published view stays backed by disk; a CSV output is read
back with `CsvOptions.forWrittenFile`, the writer's own option bag and the
query's schema, never by inference, so every value comes back as it was
written (`fuzz/csv_fuzz_test` holds the pair to that). A CSV output of no
partitions still writes one header-only part file, so there is a file to
re-read. A task without one has
nothing to re-read, so `MaterializedView.fromQuery` drains the finished
`ExecutedQuery` — one pool task per output partition, preserving the partition
layout and keeping `rowsProduced` accurate — and publishes that. The result then
lives in heap for the rest of the run, which is why it is only done when
something actually needs the view.

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
adjacency based on task viewName references, read as the binder resolves
names (unquoted, regardless of case, a CTE's name excluded however it is
spelled). Setup-time validation rejects unknown refs, duplicate viewNames,
self-cycles, cycles, and duplicate post-render output paths before any task
runs. A statement nested past `SqlParser.MaxNesting` reads no tables there,
so the DAG builds and the task fails when it runs, alone.

The scheduler propagates failure through both axes. If an input load throws,
every SQL task with that input in its `inputDeps` gets `upstreamFailed = true`
and is marked `Skipped` the moment it would otherwise have become ready —
which then propagates through the downstream task chain. Same path for SQL
task failures (Failed / ValidationFailed) — independent siblings keep going.

The loop waits for one event per input and per task, so each worker posts
its event in a `finally`, whatever its work threw, and turns any `Throwable`
into the input's or the task's failure: an `Error` that passed a worker by
once left `job.run()` waiting for ever. One failure stops the run rather
than its task: the JVM's own (a `VirtualMachineError` other than a stack
overflow, such as running out of memory). The failing worker sets a stop
the loop and the workers both read, so no task starts after it; each that
had not started is `Skipped` saying why, the ones running finish, and the
stop is `JobResult.error`.

That fan-out reaches only the tasks that read the input, and a skip reason
names only the first failed upstream its task met, so the scheduler also keeps
each input's failure itself, by input index, before fanning it out.
`DataJob.inputWarnings` turns those into one warning per failed input, in
declared order, ahead of the consistency checks in `JobResult.warnings`. That
is the one place every failed input is named, and the only trace of one that
no task reads (such an input still loads on every run; its failure skips
nothing, and its warning says no task reads it). Warnings never touch the
verdict: `JobResult.succeeded` is true iff every task succeeded.

The flow yields three timestamps per task that the GUI surfaces:
`enqueuedAt` (deps satisfied), `startedAt` (worker picked it up), `finishedAt`
(returned). `queueWaitMillis = startedAt - enqueuedAt` distinguishes "task ran
fast but waited in the pool" from "task ran fast and started immediately" —
historically the GUI reported only `finishedAt - startedAt`, which is why a
2.5-second task on a saturated pool felt like 30 seconds of wall time. An
`enqueuedAt` at the epoch is unknown (a record written before records carried
it), and its queue wait is 0.

### 3g. Stopping a query

`ExecutionOptions.stopped` is asked before each batch every scan of a query
reads (`ScanExec`, whichever thread pulls it); once it says true, the scan
throws `QueryStoppedException`. The query then fails as any query does wherever
it is: a pool task that was reading fails, `Scheduler` rethrows the task's own
exception to its waiter, and so on up to the caller. A CTE body materialized
while planning (§6b), and an exchange or a broadcast join's build side
materialized before the plan runs (§2b), plan with the same options, so they
stop too. A join's probe side is read as its output is pulled, so a join
stops probing at its probe side's next batch, on whichever thread pulls it.
What a stop cannot reach is work that already holds its rows and reads no
more, such as a sort's merge of what it has read, a window over a partition it
holds, or a hash table being probed with a batch already read; it runs to its
end, bounded by the rows it read, and the next batch read stops.

The default, `ExecutionOptions.NeverStopped`, is one shared value, so options
that leave it alone are equal, and `ScanExec` hands out the view's iterator
itself when it sees it (`eq`), costing nothing. `transformer query` sets it:
each statement runs with a stop of its own, `Cancel.child()` of the run's
`Cancel`, which says stop once either is cancelled. Ctrl-C in the shell, or an
output that can take no more, cancels the run's, and the CLI asks the same
question before each batch it prints or writes (§11); a partition that fails
cancels only the statement's, through the drain reading it (§3), so every
other partition of the statement stops at its next batch and the shell's
output stays on to say why. The GUI's query console gives each query a stop of
its own the same way, for its drain to stop the rest at a failure.

### 3h. Releasing what a query opened

A query opens files (each scan's reader) and spill directories (a spilling
sort, aggregate, DISTINCT, grace-hash join or window), and releases them when
its consumer stops reading, however it stops (`core/Closing`, decision 4 of
plans/testing/17):

- **An output that holds anything is `AutoCloseable`.** A reader
  (`CsvPartitionIterator`, `ParquetPartitionIterator`) closes its file when it
  runs out or is closed; an operator's output closes its inputs
  (`Closing.over`), so closing a query's partitions closes the plan under them;
  a breaker that reads its spill runs as it emits (a sort's merge, a spilled
  window's buckets, a grace-hash join's buckets) closes them and its
  directory when drained or closed (`OperatorSpillDir.closeOnDrain`); an
  abandoned one, neither drained nor closed, keeps its files until the JVM
  exits. An aggregate and a DISTINCT fold their runs before they emit, so
  their directories go then.
- **What stops reading closes.** A `LIMIT` closes its input once it has its
  rows (`Closing.limited`), and `GlobalLimitExec` and `OffsetExec` execute each
  partition only when the one before it runs out (`Closing.concat`). A join's
  output closes the probe partition it reads, so a `LIMIT` over a join stops
  its probe and closes the file it stopped in. A pool
  task that drains an input closes it however the drain ends, and a breaker
  that fails releases its spill directory. `Scheduler.submitAndAwaitAll`
  waits for every task before it throws the first failure, so nothing a
  failed fan-out started is still writing when its caller releases.
- **The consumer closes the query.** `ExecutedQuery.close()` closes every
  partition, read or not; the job runner (after the task, failed or not, and
  after each validation), the GUI's query console, the data viewer's preview
  (`DataGrid.sample`, which reads a partition in part) and `transformer query`
  (a print that `| head` or Ctrl-C stops, and `COPY`) call it, and
  `SqlEngine` closes the partitions it executed when a later one fails. A
  reader of partitions on several threads claims each first
  (`ExecutedQuery.claim`), so a failed read no longer closes it as it closes a
  partition no one has read yet; `ParallelDrain` closes each partition it read
  once no thread reads it, and the rest when it ends, before the consumer
  closes the query.

`--config=leaks` (`-Dtransformer.audit_leaks=true`, `core/LeakAudit`) holds
every test to it: each reader and writer file and each operator spill
directory registers when it opens, with its stack, and leaves when it closes;
at exit a shutdown hook waits for the pool to go quiet, then reports what is
still open, any directory left in the JVM's spill directory and any thread the
engine started (`transformer-*`, not the pool's) still alive, and ends the JVM
with status 117. Off, it is a final val read.

### 3i. Chaos points

Each handoff between threads is a named point (`core/Chaos`): a pool task
about to run and a wait for one (`Scheduler`), a shared result's claim and
publish (an exchange's shards, a broadcast join's build side: `SharedOnce`),
the job runner's event queue and task and input submissions, a partitioned
writer's commit, a spill directory made, and a task's output read back for the
tasks after it. Under `-Dtransformer.chaos=<seed>` (`--config=chaos`, read
once per JVM) a thread passing one does nothing, yields or sleeps 0 to 2 ms, a
third each, drawn by a generator of its own seeded from the seed and its id,
so the whole suite runs under interleavings the pool's timing alone would not
give. The threads that reach a point, and their order, are the run's, so an
interleaving does not replay from its seed: each thread keeps the last eight
points it passed (`Chaos.lastPointsOf`), and the DAG fuzzer's watchdog prints
them beside each thread in its dump. Off, a point is a final val read.

### 4. Run records and historical-run discovery

The runner persists a per-task `_run.json` for **every termination status**
(Succeeded, ValidationFailed, Failed, Skipped), and `Running` while a run
writes the directory (§3a), plus a per-job `job.json` aggregate. The two
files together let the GUI reconstruct the exact same state on reload that it
showed during the live run — validation pass/fail detail, sample failing
rows, error messages, durations — and let the runner flag drift between disk
state and the manifest.

That round trip is a checked contract, not just a written format. The
`Hydration` oracle in `fuzz/dag_scheduler_fuzz_test` reopens every generated
run in a `JobSession` — the job written as a job directory, opened the way the
GUI opens one — and holds each task the session rebuilds from its record to
the `TaskResult` the run returned, field for field, along with the session's
`job.json`, its run picker and each task's partition picker. Then it damages
the tree one way — a record deleted, cut short or followed by garbage, the
manifest deleted or pointing nowhere, two records swapped — and the reopened
session must show exactly what survived and say what did not.

The rules it holds `JobSession` to, decided in
[plans/robustness/15](../plans/robustness/15-what-a-reopened-run-shows-without-its-record.md):
only a record that names a task is that task's — in its partition picker and as
its source. A task `job.json` names is rebuilt from the record its `runFile`
points at; if that is missing, unreadable or another task's, from the task's
own record of the same run (same name and execution time) found under its
output path; and else from its `job.json` entry, which carries everything but
the failed validations' sample rows. Each task rebuilt from its entry for want
of its record gets `JobSession.summaryReasonFor` and a line in
`hydrationWarnings`, which the run log shows, and so does a run directory that
exists without a readable `job.json`. A task with no output directory has only
its entry, by design, and gets no warning. A record that says `Running`,
reached by any of these routes, reopens its task as `UiTaskState.Unfinished`:
no duration, rows or output, whatever `job.json` says, as that record, not
the manifest, says what the directory holds now. The DAG fuzzer's fault mode
stops a generated run at a fault point (throwing, or halting a child JVM)
and reopens what it left before rerunning it: a session must show a task
whose record says `Running` as unfinished, never as succeeded
(`Hydration.checkUnfinished`); the rerun leaves the tree a clean run would,
which the rules above then decide.

#### Per-task record (`_run.json`)

`DataJob.writeOutput` stamps the `Running` record when the run opens the
directory, and `writeSucceededRecord` / `writeValidationFailedRecord` /
`writeFailedRecord` / `writeSkippedRecord` the final one, each through
`OutputDirectory` and `TaskRunRecord.write` (atomic temp-then-rename) into
`<taskOutputDir>/_run.json`. A `Running` record has no `finishedAt`, which
`TaskRunRecord.read` takes for that status only. The shape:

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
  "options": {"delimiter": "|"},
  "schema": [{"name": "customer_id", "type": "long", "nullable": true}],
  "validations": [
    { "name": "customer_id_unique", "passed": false, "failedRowCount": 7,
      "sampleFile": "_validation-customer_id_unique.csv" }
  ],
  "sql": "SELECT ..."
}
```

`options` (the output's options) and `schema` (the result's columns, by
`DataType.name`, which `DataType.fromName` reads back; absent for a task that
wrote nothing) say how to read the part files as they were written:
`TaskRunRecord.outputView(dir, record)` reads exactly `outputFiles`, a CSV
with `CsvOptions.forWrittenFile(options, schema)`, so its column types survive
the round trip through text. `DataJob.materializeIfNeeded` reads a written
output through the same function, so the tasks after a task, the GUI's
previews and console (`JobSession.readOutputAsView`) and the data viewer read
an output alike. A record without a schema (one from before it carried one)
reads its CSV with inferred types; an unknown type name makes the schema
absent rather than the record unreadable. `sql` is the main SQL, rendered.

For failed validations, the runner writes the sample CSV (up to 10 rows
returned by the validation SQL) to a sibling
`_validation-<slugified-name>.csv` file and links it via `sampleFile`. The
slug keeps the name's first 100 characters, and a failed validation whose name
reduces to a file name another of the task's already took gets `-2` (then
`-3`, ...), so every failure keeps its own rows. The GUI loads samples lazily
— only when the user inspects a specific task's validation card.

The `_` prefix means `PathGlob.expand` skips both the record and the
sample files when the directory is re-read as data.

`TaskRunRecord.discover(templatedPattern)` is the partner operation: it
replaces every `{{...}}` in the pattern with a glob wildcard, walks the
longest static prefix of the result, and returns every directory under it
that contains a `_run.json` — sorted newest-first by `writtenAt`. The walk
is depth-bounded so a pattern with a leading wildcard doesn't accidentally
scan the whole disk.

A final record (`Succeeded` or `ValidationFailed`) that cannot be written
fails its task: it is the commit, so a task without one did not finish for
any reader (§3a). The `Running`, `Failed` and `Skipped` records, the
validation samples, `_perf.json` and `job.json` are written on a best-effort
basis: a failure there is swallowed, leaving the directory as §3a says. So a
record must be writable whatever it holds: every string in `_run.json` and
`job.json` goes through `Json.escape` (and in `_perf.json` through
`JsonMini.escape`), which writes half a surrogate pair — a character UTF-8
cannot encode — as a `\u` escape rather than failing the write. A run clears
a directory a record marks, deleting the top-level files a run writes
(`part-*`, `_*`, `.*`) but the record, so reruns at the same templated path
completely overwrite prior state, and leaves any other file alone. A
directory with part files and no `_run.json` (`unrecordedPartFiles`) is
refused before anything is written, and gets no Failed or Skipped record,
which would make those files the job's to delete next time. So after a write
the directory's part files are this run's, and `materializeIfNeeded` gives
downstream tasks and validations a reader of exactly those files
(`CsvReader` over the list, `ParquetReader.fromFiles`), never a directory
listing (plans/robustness/29).

#### Per-job manifest (`job.json`)

`DataJob.writeJobRecord` writes a `JobRunRecord` to the path configured by
`jobRunOutput` (defaulted by `DirectoryJobLoader` to
`<outputDir>/job.json` — co-located with the per-task data so an output
directory is a self-contained snapshot of one run). The manifest lists
every task with status, row count, error message, its three timestamps, the
name and row count of each validation it failed, and a `runFile` pointer to
the per-task record file — so an entry rebuilds the task's `TaskResult` but
for the failing rows, which only the record directory holds. It's the GUI's single entry point on reload
— load one file, follow the pointers, hydrate the whole job.

The manifest also carries the run's `warnings` array: first one per input
that failed to load (`DataJob.inputWarnings`, see 3f), then
`DataJob.runConsistencyChecks`' findings: declared part files that aren't on
disk, referenced `runFile` paths that don't exist, missing validation
samples, etc. These are non-fatal — the run still `succeeded` if every task is
Succeeded — but they surface in the GUI's run-log panel so the user
notices. A `jobRunOutput` that is a cloud path writes no manifest at all; the
run's result carries a last warning saying so (`DataJob.cloudRecordWarning`),
where it was a line on standard error before plans/testing/18.

The manifest records the run's lineage too, for the data viewer: `inputs`
(each `JobInputRecord`: the input's view name, its path rendered and made
absolute, its format and options; every input, one no task reads included),
each task's `readsInputs` and `readsTasks` (the `TaskDagNode.mainDeps` and
`mainInputDeps`: what its main SQL reads, which a validation's reads are not),
and `outputTemplate`, the record's directory with its templates unrendered,
which the runs of one job share. Every path in it (`runFile` included) is
absolute, made so against the working directory the run wrote from, so a
reader in another directory finds them. `JobRunRecord.read` takes a manifest
without these fields as one with none (`inputs` and `outputTemplate` None).

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

#### The data viewer reads a folder of runs

The data viewer (`viewer/`) opens a folder of job outputs and reads only the
records. `RunFolder.scan` walks the folder (six levels at most, 20,000
directories at most, not into a run's directory or a table's) and takes each
`job.json` as a run; runs sharing an `outputTemplate` are one `Dataset`,
newest first, named by their directory under the folder less the segments the
template fills in, and given a SQL name (`RunFolder.sqlIdentifier`). A table's
directory is its `runFile`'s parent, or, for a folder moved since the run, the
same place under the run's directory now (`RunFolder.locateRunFile` renders
the template at the run's execution time to find what to strip).

`Lineage.build` joins the datasets, each at the run shown: within a run, an
edge from each input and table in a task's `readsInputs` / `readsTasks` to
the task; across runs, an edge from a table to an input of another job whose
recorded path is that table's directory, a file in it, or a glob matching it
or its part files. Paths compare as recorded and with symbolic links resolved
(`Lineage.spellingsOf`), against every run of every dataset, so an input that
read an older run still joins the table (`TableMatch.shown`). `LineageLayout`
places each dataset as a box (inputs, then tables in longest-path columns)
and the boxes in columns by the datasets they read, with one barycentre sweep
to order each column; given one node's lineage (`Lineage.around`, its
ancestors and descendants) it lays out only those nodes, and a box only for
the datasets they are in, which is what an inspector's Lineage view draws.
`QueryCatalog` names each table `<dataset>__<table>` and each outside input
`<dataset>__<input>`, and says why a table without one has no rows; a
query's catalog holds the views `TaskDag.viewsRead` finds in its text, each
opened once through `TaskRunRecord.outputView`. All four, and
`ViewerSession` (the state the panels share: what is selected, found again by
name when the folder is read again), are `viewer:model`, with no JavaFX,
tested by `viewer/model_test` over real runs.

The window is a workspace of tabs (`ViewerTabs`): query tabs, each a
`QueryConsole` with the editor above the result (its rows, or the chart of
them), and inspector tabs
(`InspectorPane`), each a header and a tab per `InspectorView` of a table,
input or dataset (Data, Columns, Details, Lineage; a dataset's Tables). A
view is built when first turned to. Opening a table goes to the one preview
tab, which keeps its view from table to table, unless the table has a tab of
its own; `InspectorHistory` is each tab's Back and Forward. The tab in front
sets the session's selection, which the list follows; the workspace alone
listens to the session for its tabs, and moves each inspector to its subject
in a new run or a folder read again, or closes it.

A query tab's Chart (`ChartPane`, the console's, so the job GUI's SQL console
has one too) draws the result the console holds in memory. `ChartSpec.fit`
suggests what to draw from the result's columns, or keeps what was chosen for
the last result where its columns allow. `ChartData.build` reads every batch
once: each row goes to an accumulator for its X and its series (rows, values,
sum, least, greatest), so SUM, AVG, MIN, MAX and COUNT come out of one pass
and series past eight merge into Other without a second; a scatter keeps its
first 10,000 rows as they are. Text along X keeps the result's order and
anything else is sorted; a line, an area or a scatter places numbers, dates
and timestamps along an axis. Both are `gui:chart`, with no JavaFX, tested by
`gui/chart_test`. `ChartCanvas` lays the chart out on each paint (the legend,
ticks from `ChartScale`, X's labels set aslant and thinned when they would
collide), keeps where it put things for the pointer to find, and paints again
into a larger canvas for Save PNG (`image`), since a snapshot of a canvas
scaled up blurs.

### 5. Expression evaluation

`Expr.eval(batch, row): Any` — boxed return. Pattern:

```scala
val v = expr.eval(batch, row)
if (v == null) // SQL NULL
else v.asInstanceOf[Boolean | Long | Double | String | …]
```

Three-valued boolean logic is handled inside `BinOpExpr` for `AND`/`OR`.
Arithmetic and comparison are NULL-propagating via the `lv == null || rv == null
=> null` short-circuit at the top of the generic branch. Their operands arrive
with one type: `Analyzer.unify` types every set of operands that meet when the
query is bound (numbers widen, an `INT` or `BIGINT` beside a `FLOAT` to a
`DOUBLE`, which holds every `INT`, by `DataType.widerNumeric`; a string literal
is read as the type beside it; any other mix is a bind error), so a bound comparison never asks `Ops` to order
a number against a string. Values that become one result — a `CASE`'s `THEN`s
and `ELSE`, the arguments of `COALESCE`, `GREATEST` and `LEAST`, `IF`'s two,
`LAG`'s or `LEAD`'s value and default, a column across a `UNION`'s arms
(`LogicalBuilder.unifyArms`) — meet through `Analyzer.branches`, the same rule
with one difference: they are kept, so their type must hold each of them. Two
decimals meet there as `Decimals.holding`, the longer integer part at the
larger scale, given way past 38 digits as an arithmetic result's is, and a
`FLOAT` or `DOUBLE` beside a `DECIMAL` is the error it is in arithmetic. A
cast the binder inserts between two `DECIMAL`s produces a value of the target
(`Decimals.fit`), so a comparison of two decimals reads both as
`Decimals.wider`, which holds both with no bound on its digits and so never
rounds; any other number is read exactly, as a comparison needs.

So there are two kinds of cast to a `DECIMAL`. The binder's own, a `CastExpr`
it inserts where types meet, reads a number exactly, an integer at the
target's scale whatever its digits, and may name a `DECIMAL` wider than 38
digits. A query's `CAST(x AS DECIMAL(p,s))` binds to a node of its own,
`DecimalCastExpr`, which fits every value to the type it names
(`Casts.toWrittenDecimal`: rounded half up, an error past the precision, text
read by `NumberText`'s decimal grammar, a number written in the query read as
written through `decimalConstant`) and names only a type a query can write.
`PlanValidator` checks that last, and `PlanSql` prints the query's cast as
itself and the binder's as its operand, which the binder casts again in the
same place; printing the binder's as a written cast would make it round.

Conditions and function arguments are typed at bind time too, so evaluation
can cast to the kind it expects. Every condition (WHERE, HAVING, ON, CASE WHEN,
IF's first argument, COUNT_IF, each side of AND, OR and NOT) is bound through
`Analyzer.condition`: a `BOOLEAN` as it is, NULL cast to one, anything else a
bind error; so `CaseExpr`, the AND / OR chains, the join operators and
`VecFuncs.iff` only ever read a `BooleanVector` or a boxed `Boolean`.
Every function a query can call is an entry of `FunctionCatalog`: its name and
aliases, its kind (scalar, flow, operator, clock, aggregate, ranking, offset),
its arities, what each argument must be (a number, an `INT` or `BIGINT` that
counts, a `STRING`, a truth value, a value it may answer with, which meets the
others as a CASE's values do, or one it compares, as NULLIF's), the rule its
result type follows, its NULL rule, its domain, the errors it can raise, and
whether it has a vector path, runs as a window function and takes `DISTINCT`.
`LogicalBuilder` binds every call by its entry (`bindArguments`,
`bindAgg`, `bindAnalytic`) and checks every argument against it through
`Analyzer.checkArgument` or the aggregate's own check; `UnsupportedClauses`
reads from it which calls take `DISTINCT`, an argument or an offset; and no
function's name is matched as a string in the binder. `Funcs.apply` and
`VecFuncs` read an argument as the `Number` or `String` it was declared, and
answer NULL for a NULL one. `function_catalog_test` holds the README's lists of
functions and CAST types to the catalog, both ways, and the semantics tables'
function sweep (`src/test/semantics/functions-*.txt`, `aggregates.txt`,
`windows.txt`) runs every entry over every kind of argument, holding each NULL
to the entry's NULL rule or domain and each error to the ones it declares.

These are three families of rules: `unify` for the operands that are compared,
`branches` for the values that become one result and `arithmetic` for
arithmetic. Each family's every construct types a pair alike, and the families
agree but where a rule says otherwise: a `DECIMAL` beside a `FLOAT` or `DOUBLE`
column compares but is refused in a branch and in arithmetic, arithmetic
declares a decimal's type by its operator where a branch takes the type that
holds both, and arithmetic is on numbers alone. The coercion tables under
`src/test/semantics/` list every pair in every construct, and
`semantics_tables_test` holds the families to those properties (testing.md,
"Semantics tables").

Arithmetic with a `DECIMAL` on either side is typed by `Analyzer.arithmetic`
instead, because the result's type depends on both sides' digits (`Decimals`,
in `Ops.scala`). Each side becomes a `DECIMAL` of its own: an `INT` the
`DECIMAL(10,0)` its every value fits, a `BIGINT` a `DECIMAL(19,0)`, a number
written in the query the `DECIMAL` of just its digits (`Decimals.literal`, so
`2.5` is a `DECIMAL(2,1)`), and `NULL` the other side's type. Arithmetic on
numbers alone (`1 + 0.05`) binds as a `DOUBLE`, so `LogicalBuilder.decimalConstant`
rebinds such a constant from its parse tree when it meets a `DECIMAL`: decimal
steps over the decimals of its numbers, but integer arithmetic within it bound
as it is anywhere, so a constant has the value it has on its own (`10 / 4` is
2). Any other `FLOAT` or `DOUBLE` is a bind error: it has no scale for the
result to keep. `Decimals.resultType` then declares the result as Spark and
SQL Server do (`*` takes `p1 + p2 + 1` digits at scale `s1 + s2`, `/` the scale
`max(6, s1 + p2 + 1)`, past 38 digits the scale gives way, down to 6), and
`Decimals.arith`, which both `Ops.apply` and `VecOps.arith` call, computes the
exact answer and fits it to that type (`Decimals.fit`: rounded half up to the
scale, an `ArithmeticException` when the integer part is too long). So a
decimal result always fits its declared type and parquet writes it as it is.
`MOD(a, b)` binds as `a % b`. `SUM` and `AVG` over a `DECIMAL` are exact the
same way: `DecimalSumState` and `DecimalAvgState` add `BigDecimal`s, which do
not round, so the answer does not depend on the order rows, partials or spills
arrive in, and fit it to `DECIMAL(38,s)` and to `DECIMAL(p+4,s+4)` once, in
`finish`.

`Ops.cmp` is the canonical NULL-free comparator and decides every ORDERING
question: `ORDER BY`, `< <= > >=`, and any tie test that has to agree with a
sort. Numbers compare by value, exactly where the types allow: two integers as
longs, a decimal against any number with `BigDecimal.compareTo` (scale-blind,
full precision), and any other pair, which has a FLOAT or DOUBLE in it, through
`Ops.floatCompare`, `NaN` equal to itself and above every other number, `-0.0`
equal to `0.0`. Strings compare lexicographically, booleans naturally, and
byte arrays byte by byte as unsigned values. `Ops.eq` is the canonical
NULL-free EQUALITY and decides `=`, `IN` and `NULLIF`, and it is `cmp(a, b) ==
0` for every pair of values, floats included (until plans/robustness/21 it
followed IEEE on floats, which made `WindowExec.sameKey` wrong when it asked
`eq` whether two sorted rows tied).
Both dispatch on the boxed value's class, so a new boxed type needs a case of
its own: the fallback compares `toString`, which for a byte array was its
identity hash.

### 5a. Vectorized expression evaluation (`evalVec`)

`Expr` also exposes `def evalVec(batch: ColumnarBatch): ColumnVector` — one
call evaluates the expression across every row of a batch and returns a typed
result column. This is the path `ProjectExec` and `FilterExec` use; the
RowBuf-driven sites (sort/join/window/aggregate-state updates) still call
`eval(batch, row)` because they're working on 1-row batches where
vectorization gives nothing back.

The trait carries a default implementation that allocates an output vector and
loops per-row `eval` writing through `setBoxed` — so any `Expr` is correct out
of the box. Hot nodes override for primitive-array speed:

- `LitExpr.evalVec` — `Arrays.fill` of a single primitive across the batch.
- `ColRefExpr.evalVec` — returns `batch.column(index)` zero-copy. The caller
  treats the result as read-only.
- `CastExpr.evalVec` — delegates to `VecOps.cast`.
- `UnaryOpExpr.evalVec` — `VecOps.negate` / `VecOps.not`.
- `BinOpExpr.evalVec` — dispatches to `VecOps.{arith,compare,concat}` for
  the binary ops. `AND` / `OR` chains take a fold path: the operator
  flattens the left-deep `AND`/`OR` tree, allocates ONE intermediate
  `BooleanVector` from the first conjunct, and folds every subsequent
  conjunct in via `VecOps.andInto` / `VecOps.orInto` mutating in place.
  A 9-conjunct `WHERE` clause goes from N intermediate `BooleanVector`s
  per batch down to 1.
- `IsNullExpr.evalVec` — `VecOps.isNull`. Carries a fast path for primitive
  columns whose null bitset is empty: when no rows are null the result is
  the constant `!negated` for every row and we skip the per-row
  `isNull(i)` dispatch.
- `FuncExpr.evalVec` — delegates to `Funcs.applyVec` for the hot subset
  (`COALESCE`, `LENGTH`, `UPPER`/`LOWER`/`TRIM`, `CONCAT`, `SUBSTRING`,
  `ABS`, `FLOOR`/`CEIL[ING]`, `ROUND`, `TRUNC[ATE]`, `IF`, `NULLIF`); falls
  through to the default boxed loop for unknown / unsupported functions.
- `CaseExpr.evalVec` — one predicate and one value vector per branch, each
  needed only on the rows that branch decides (below), walked in order; stops
  once every row is claimed by an earlier branch.
- `SwitchCaseExpr` and `BetweenExpr` — a simple `CASE` and a `BETWEEN` whose
  operand reads `RAND()`, which the binder makes so the operand is read once
  per row (any other simple CASE is the `CaseExpr` of its `operand = when`
  comparisons, any other BETWEEN its two comparisons, which parquet pushdown
  reads). The vector path appends the operand's vector to the batch as one
  more column (`Expr.withColumn`, sharing the batch's own) and evaluates the
  same `CaseExpr` or `AND` over a reference to it, so the guards are those of
  the general nodes.
- **Conditionals evaluate an operand only where the answer needs it.**
  `CASE`, `IF`, `COALESCE`, `IN` and `AND` / `OR` evaluate each operand
  through `Operand.evalVec(e, batch, needed)`: over the whole batch, which is
  the fast path nearly every batch takes, and only if that throws, row by row
  on the rows `needed` accepts, so a `CAST` or an overflow a guard keeps off a
  row never fails the batch. Every `Expr` is side-effect free, but an
  expression can throw, and a throw is visible: the row path was lazy and the
  vector path was not, so `CASE WHEN s = 'q' THEN CAST(s AS INT) END` failed
  on a row holding `'x'` (plans/robustness/17). `IF` and `COALESCE` read
  every argument on the row path too, before `FuncExpr.eval` became lazy for
  them.
- `InListExpr.evalVec` — when items are all literals, pre-builds a typed
  HashSet keyed the way `Ops.eq` compares (`Long` for integers via
  `longValue`; `Double` via `doubleValue` for other numbers when a FLOAT or
  DOUBLE is involved; `String` for string columns; a linear `Ops.eq` scan
  for decimals, booleans, dates and byte arrays). Non-literal items go through
  one column-eval per item per batch + a per-row linear scan. NULL items are
  tracked for SQL 3VL.
- `LikeExpr.evalVec` — literal-pattern fast path compiles the regex once
  outside the row loop and re-uses a single `Matcher` via `.reset(input)`
  per row. Column-pattern path still goes through the global pattern cache
  per row (rare; documented slow path).

`VecOps` (in `sql/plan/Ops.scala`) is the column-at-a-time companion to `Ops`;
`VecFuncs` (in `sql/plan/Funcs.scala`) is the same idea for scalar functions.
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

The same rule lets every `SELECT` with no `FROM` scan one shared view,
`LogicalBuilder.NoFromRow`: a single batch of one row and no columns, read by
concurrent queries at once. An operator that wrote to its input batch — even
just its row count — would corrupt every later FROM-less query.

**Numeric comparison contract.** `VecOps.compare` picks its loop from the two
operand types the way `Ops` picks a branch from the two values: two integers
go to `integralCompare` (`readLong`, since a double cannot hold every long past
2^53), a pair with a FLOAT or DOUBLE in it to `numericCompare` (`readDouble`),
and anything else, a decimal included, to `genericCompare`, which asks `Ops`.
Reading a decimal through `readDouble` would make two decimals past double's
precision equal on this path and unequal on the row path.

**Floating-point comparison contract.** A vectorized helper may not reach for
the raw primitive operator just because the operands are `double`. Equality
(`= <>`) and ordering (`< <= > >=`) both go through `Ops.floatCompare`, in
*both* paths: `NaN` equals `NaN` and sorts above every other number, and
`-0.0` equals `0.0`. It is a total preorder, which the sort comparators that
share `Ops.cmp` need (IEEE comparison is not one). The same rule reaches `IN`,
where the literal-hoisting `HashSet` would otherwise import
`java.lang.Double.equals`, which separates the zeros; `InListExpr.setKey`
reads `-0.0` as `0.0` into the set. See [gotchas.md](gotchas.md).

**When to override `evalVec` on a new `Expr`.** Override when the expression
shows up in `ProjectExec` / `FilterExec` hot paths and a primitive-array loop
saves real time. Leave the default for one-off shapes (rarely-used scalar
functions, string-heavy operations where boxing isn't the bottleneck). The
fallback is one ColumnVector allocation + N `setBoxed` calls per batch —
slightly more allocation than the pre-`evalVec` row loop, but still correct.

Parity is verified two ways. `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala`
is the hand-written gate — a JUnit suite that builds 64-row batches with
controlled NULL placements, runs both `eval(batch, row)` and `evalVec(batch)`,
and asserts equality at every row for every subtype that overrides `evalVec`.
When you add a new override, extend this suite first — it enforces NULL-handling
parity, divide-by-zero parity, and per-type result-shape parity (after
normalizing to the declared `dataType`). The property-based
`ExprParityFuzzTest` (`src/test/scala/com/transformer/fuzz/`) generalizes it:
it generates random type-correct `Expr` trees over random batches and asserts
the same per-row agreement across many seeds (see
[testing.md](testing.md#property-based-testing-fuzz)).

That fuzzer is what surfaced the compositional FloatType case: `eval` narrows
float arithmetic and `ABS` to Float at **each node** (`Ops.arith` /
`Funcs.apply`) so it matches `evalVec`, which narrows on every `FloatVector`
store. Without per-node narrowing a nested float intermediate like `(a*b)+c`
stayed in double precision through `eval` and diverged by ~1 ULP from the
vectorized path once it fed the enclosing `+`.

### 5b. Vectorized key extraction in pipeline breakers

`HashAggregateExec`, `HashJoinExec`, and `WindowExec` all derive a "key" from
each input row to drive a HashMap lookup. When the key expression is a pure
`ColRefExpr` (the common case for `GROUP BY a`, `JOIN ON x = y`, `PARTITION BY
p`) the existing `KeyCodec.encodeFromBatch` path reads typed primitives
directly from the source `ColumnVector`s — no boxing, no Expr dispatch.

When the key is a computed expression (`GROUP BY a + b`, `JOIN ON x.id =
y.user_id + 1`), the operators evaluate each key Expr **once per batch via
`evalVec`** and read the boxed per-row value from the resulting vector,
instead of running per-row `Expr.eval` through a `RowBuf`. The win compounds
with the `evalVec` overrides above: `BinOpExpr.evalVec` returns a single
typed vector for the whole batch instead of N recursive `Expr.eval` calls.

- **`HashAggregateExec.partialAggregate`** computes `keyVecs: Array[ColumnVector]`
  once per scan batch for non-ColRef keys. The per-row loop reads
  `keyVecs(i).getBoxed(r)` into the key buffer instead of calling
  `keyExprs(i).eval(batch, r)`.
- **`HashJoinExec`** does the same on both sides. Computed build-side keys
  are evaluated and encoded per scan batch as the build side is collected;
  ColRef build keys are encoded from the compacted build batch through a
  codec bound to the build columns, the probe's codec layout, so both sides'
  keys compare equal. Probe-side keys are evaluated once per probe batch by
  the probe cursor, when it moves to the batch, however many fills that
  batch's rows take.
- **`WindowExec`** is more invasive: during the single materialization pass
  it eval-vecs each spec's partition keys + order keys per scan batch and
  stashes the per-row values in `Array[Array[Any]]` arrays. Downstream
  partitioning (`processSpec`), the sort comparator (`sortPartition`), and
  rank / dense_rank tie detection all read from those pre-computed arrays.
  LAG/LEAD args and aggregate args still go through the per-row path — a
  follow-up that pairs with plan 04 (sharding).

### 5c. Vectorized aggregate state updates

`AggState` exposes three update entry points:

- **`updateAt(argVecs: Array[ColumnVector], row: Int)`** — fast path used by
  `HashAggregateExec`'s GROUP BY loop. The caller pre-evaluates each
  aggregate's args once per batch via `Expr.evalVec` (caching the result
  vectors in an `ArgVecsCache` reused across batches) and the state reads
  typed primitives directly from the vector — no per-row `Expr.eval`
  dispatch, no per-row boxing. Primitive states (`CountStarState`,
  `CountState`, `CountIfState`, `LongSumState`, `DoubleSumState`,
  `AvgState`, `MinMaxState`, `MomentState`, `CovarState`, `CorrState`)
  override with a pattern match on the argument vector's concrete subtype
  for typed reads. A decimal argument is boxed in its vector, so
  `DecimalSumState` and `DecimalAvgState` read `getBoxed`.
- **`updateBatchAt(argVecs, n)`** — whole-batch variant used by the
  no-GROUP-BY fast path. The column-type pattern match is hoisted outside
  the row loop so a `SUM(spread)` over a `DoubleVector` ends up as a tight
  primitive-array sum loop.
- **`update(agg, batch, row)`** — legacy row-form, kept only for
  `WindowExec` which feeds 1-row `RowBuf` batches where vectorized pre-eval
  gives nothing back. The default implementation pre-evaluates each arg
  once per call and delegates to `updateAt`, so ColRef args alias the
  RowBuf's underlying column at zero alloc cost.

`HashAggregateExec.partialAggregate` pre-evaluates `argVecsByAgg` once per
batch, then per-row looks up the state and calls `updateAt` for every
aggregate. On 100M+ row scales this collapses the per-row work to one map
lookup + N typed primitive reads, instead of the old N-per-row
`Expr.eval`-and-box dispatches that dominated young-gen GC.

`MinMaxState` also carries primitive specialization: rather than holding
the current extremum in a boxed `Any` slot that allocated a fresh
`java.lang.Long`/`Double` every time the extremum advanced, it tracks the
running value in unboxed `longCur` / `doubleCur` slots and only boxes once
at `finish()`. The result type field decides which slot is canonical. The
`doubleCur` slot advances through `java.lang.Double.compare`, not raw IEEE
`<` / `>` — the same total order `Ops.cmp` gives the boxed slot, `SortExec`
gives `ORDER BY`, and `Funcs.pickByOrdering` gives `GREATEST`/`LEAST`, so
`NaN` is the largest value and a `MIN`/`MAX` over one does not move with row
order, partition fan-out or spill flush order. `compare` takes primitives, so
the total order costs no boxing in the loop the slot exists to keep
allocation-free.

### 6. Window functions: two-stage logical binding

A `fn() OVER (...)` clause is **not** a regular `Expr` — it can't be evaluated
per-row in isolation because it depends on the whole partition. `LogicalBuilder`
handles this with a two-stage rewrite:

1. **Pre-window stage.** Aggregation (if any) is built first, producing a
   plan + a `BinderFactory: (AnalyticExpression => Option[Expr], Column =>
   Option[Expr]) => Expression => Expr`, its second resolver the output columns
   an `ORDER BY` name may read (below). The factory is called with empty
   resolvers to get a binder used to bind the partition keys, order keys, and
   function arg of each `OVER (...)`.
2. **Collect windows.** Walk SELECT items + ORDER BY. Every distinct
   `AnalyticExpression` (deduped by canonical SQL) becomes a `WindowDef` with
   a synthetic output column name (`_win0`, `_win1`, …). The walk recurses
   through `LogicalBuilder.childExpressions`, the same traversal
   `containsAggregate` and the aggregate `collect` use — a node shape missing
   from it returns `Nil` silently, so the function it contains is never
   collected and the binder later reports it as being used in the wrong
   position. See [gotchas.md](gotchas.md) on grouping parentheses for the
   worked example.
3. **Insert `LogicalWindow`** above the post-aggregation plan. Its output
   schema is `child.outputSchema ++ windowOutputs` — original column indices
   are preserved.
4. **Post-window binding.** Call the same `BinderFactory` with the populated
   resolver; the resulting binder substitutes each `AnalyticExpression` with
   a `ColRefExpr` into the window-output position. SELECT projections and
   ORDER BY then bind cleanly.

**ORDER BY scope.** Without a `DISTINCT`, `ORDER BY` sees the output columns and
the input's. `LogicalBuilder.OrderScope` gives each key as an output position (an
ordinal, or a bare name some output column has, so `SELECT -b AS b ... ORDER BY b`
sorts by `-b`) or an expression over the input, in which a name no input column
has reads the output column (the factory's second resolver) and a name both could
read, an input column and the alias of something else, is refused before
binding. `sortOverProjection` then sorts over the projection: the projection
computes each expression key beside the output columns as `_orderN`, the sort
reads positions, and a second projection drops the `_orderN` columns, so a key
naming an output column sorts by exactly the values returned (`RAND()`'s
included) and no SELECT item is computed twice. Past a `DISTINCT` or a `UNION`
the sort binds against the output schema alone (`bindOrderKey`).

`WindowExec` is a pipeline breaker that materializes the rows in scope and
groups `WindowDef`s by `WindowSpec` so that partitioning + sorting happens
once per spec, then writes one output column per window. Mode is selected
by whether `child` is an `ExchangeExec` (see §2 and §2b): in **sharded**
mode the planner has hash-partitioned by the shared PARTITION BY keys, so
each shard processes its rows independently and `numPartitions = K`; in
**collapsing** mode (no exchange — empty PARTITION BY, disagreeing specs,
or sub-threshold input size) the operator reads every child partition and
emits as a single output partition. It reads them at once on the pool
(`ParallelDrain.eachPartition` over the child's partitions, each opened when
a task takes it), each partition's rows and keys into a buffer of its own,
concatenated in partition order: the rows the window sees, and so how its
ties come out, are those reading one partition after another gave. Its child
is often a join running in its probe side's partitions, whose probe this
spreads over the cores; a partition's failure stops the others at their
next batch. Ranking functions and LAG/LEAD read no
frame (the binder refuses one written on them). An aggregate reads each row's
frame, `WindowExec.frameBounds`'s inclusive positions [lo, hi] in the sorted
partition: a `ROWS` offset in long arithmetic clamped to the partition, and a
`RANGE` bound by a binary search under the sort's own comparison
(`SortKey.compare`), `CURRENT ROW` the first or last peer and `n PRECEDING` /
`n FOLLOWING` the first or last row within `n` of the current row's value on
that side, so NULL and NaN keys fall where the sort put them. A frame over the
whole partition is one aggregate; one that starts at `UNBOUNDED PRECEDING`
grows a single state as its end moves on (the default frame with an `ORDER BY`,
a running total in one pass), one that ends at `UNBOUNDED FOLLOWING` the same
from the other end, which needs `AggState.finish` to leave the state as it was
(every state's does); any other frame aggregates its own rows.

### 6b. CTE resolution: inline vs materialize

CTEs need no `LogicalCTE` node and leave the optimizer, physical planner, and
cardinality estimator untouched: by the time they run, every `WITH` reference
is either an inlined body subtree or a `LogicalScan` over a `MaterializedView`.
This happens in two stages — a builder change plus a resolve pass that runs
*between* `LogicalBuilder.build` and `LogicalOptimizer.optimize`.

**Stage 1 — builder emits placeholders (`LogicalBuilder`).** `buildAnySelect`
reads `Select.getWithItemsList` (the WITH clause attaches to the top-level
`Select`, including a `SetOperationList`, so both UNION arms see the same
scope) and folds the items into a `cteScope`. The builder can't yet decide
inline-vs-materialize (that needs the reference count, unknown until the whole
enclosing query is built), so for each **outermost-scope** CTE it instead emits
a unique `PendingCteView` — a placeholder `CatalogView` that throws if it ever
reaches execution — and collects a `CteDef(name, pending, body)`. `fromItem`
returns a `LogicalScan` over that pending view (alias handling identical to a
real table, so column resolution downstream is unchanged), consulting the scope
before the catalog so a CTE name still **shadows** a like-named view. Building
proceeds in declaration order so a later CTE can reference an earlier one; an
explicit column-alias list (`WITH c(x, y) AS (...)`) wraps the body in a rename
`LogicalProject` (arity mismatch throws); `WITH RECURSIVE` is rejected up front.
`build` returns a `BuiltQuery(main, ctes)`. A CTE declared *inside* another
CTE's body is **not** outermost-scope: it is inlined directly (the historic
behaviour), so only top-level CTEs are materialization candidates.

**Stage 2 — `CteResolver.resolve` (in `sql/exec`).** One linear pass over the
declaration-ordered defs. For each def it counts references (`view eq pending`,
identity) across the not-yet-resolved later bodies plus the main plan — earlier
bodies can't mention it — then:

- **&ge; 2 references → materialize once.** Optimize + physically plan the body
  in isolation and drain it through `MaterializedView.materializeInParallel`
  (via a tiny `PhysicalPlanView` `CatalogView` adapter over the physical plan),
  then replace every reference with a shared `LogicalScan` over that one
  `MaterializedView`. The body runs once; each site replays in-memory. Because
  the same MV object is shared, a non-deterministic body (`RAND()`) yields
  identical rows to every reference — an optimization fence inlining doesn't
  provide. (The clock keywords need no fence: each binds to one literal per
  statement.)
- **&le; 1 reference → inline.** Substitute the body subtree at the (single or
  zero) site, structurally identical to today's plan; a zero-reference CTE is
  never executed.

Substitution uses `LogicalPlan.withChildren` (the structural inverse of
`children`) so the rewrite touches no node type by name. Resolving in
declaration order means a materialized `b` that reads `a` first has its body
rewritten to scan `a`'s already-materialized view, so `b`'s single execution
reads `a` once. Invariant: `MaterializedView.schema == body.outputSchema`
exactly (asserted in `materialize`), so swapping a pending scan for the MV scan
never shifts a bound column index. A bonus: `MaterializedView.exactRowCount`
gives the main planner an exact CTE cardinality (better hash-join build-side
selection) and lets `SELECT COUNT(*) FROM <materialized cte>` hit the
metadata fast-path. The measured path times the whole pass as
`QueryMetrics.cteMaterializeNanos`; the body sub-executions run unmeasured.

Heap is the cost: a materialized body is buffered whole, with no spill yet
(a [plan 09](../plans/perf/09-spill-to-disk.md) tie-in). The policy is
unconditional at `refs >= 2` — recomputing a huge body N times is also
expensive, so materialize-once is usually the better of two heavy options.

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

**Running a join.** `HashJoinExec` builds one side into one columnar batch
(its partitions' batches compacted, `ColumnVector.gather`) and a multi-map
from key to the indices of its rows (`IntList`s, unboxed), and reads the
other through a probe cursor (`ProbeCursor`), which every path shares, so
match semantics live in one place: it holds a probe stream (opened by its
first fill), the batch it is in and that batch's computed key vectors, the
next row, and for the row being matched its candidates, the next to examine
and whether a candidate kept it; a fill stops at a row limit (an output
batch) or a work limit (`PairsPerTask` units: a probe row read, a candidate
pair examined or an unmatched row padded), mid-row if need be, so a probe
row matching ten million build rows emits them a batch at a time. No row is
boxed: the cursor writes each joined row's probe row and build row into two
selection vectors (-1 for a NULL-padded side), and the output batch gathers
each probe column from the probe batch before the cursor moves past it and
each build column from the build side's batch once the output batch is done,
a column at a time (`ColumnVector.gather`). A residual condition (`extra`) is evaluated with
`evalVec` over a chunk of candidate pairs at once, the columns it reads
gathered for them, each probe row finished once its last candidate is
checked. A join that keeps the build rows no probe row matched records the
ones it matched in a `BitSet` per cursor. Four paths, chosen from the plan as
built:

| Path | Taken when | Partitions | Holds |
|---|---|---|---|
| Broadcast | a keyed join that keeps no unmatched build row (planned INNER, LEFT, RIGHT under the broadcast threshold) | the probe side's | the build side, built once by the pre-materialization pass (§2b), and per partition being pulled a probe batch and an output batch |
| In waves | no equality, or a keyed join that keeps unmatched build rows (planned FULL) | 1 | the build side and one wave (`Scheduler.parallelism` × `PairsPerTask` rows, 524,288 at 16 threads), a `BitSet` per cursor |
| Per shard | both children are exchanges (smaller side at or above the broadcast threshold) | K | the two exchanges, which hold both inputs, and per shard being pulled its build rows and an output batch: shard `s` builds on its first pull, streams probe shard `s`, then emits its unmatched build rows |
| Grace hash | spill on, keys, none reading `RAND()` (§2c) | 1, K when sharded | one bucket's build rows and an output batch; both inputs on disk, bucketed on the first pull |

So no join holds its result, and a join under an aggregate, a sort, a top-n,
a writer, a CTE's materialization or a LIMIT streams as a scan does: a LIMIT
over a join stops after its rows, and one over an endless probe side
returns. A broadcast join's partitions are its probe side's, read in parallel
by whatever reads partitions in parallel, so a job table over one writes a
part file per probe partition (`maxPartitions` caps them; `1` writes one). Its
rows, read flat, come in probe order, as the collapsing join that preceded it
gave them; an aggregate over it merges a partial per probe partition, as over
a multi-partition scan, so a `DOUBLE` sum's last digits may differ from one
collapsing partial's. A join in waves emits each wave's rows (its cursors'
chunks in partition order) and then the unmatched build rows; a FULL join's
rows used to be in probe order. A consumer that reads one stream (OFFSET
without LIMIT, a validation's rows, `ExecutedQuery.batches`) probes a
partition at a time; `transformer query` and the GUI's console read a join's
partitions on the pool like any result's (§3), with the probe inside their
reads.

Non-equi joins (no equality conjunct after `splitEqualityKeys`) plan as a
degenerate hash join — every probe row matches the single empty-key bucket
holding every build row, which gives correct nested-loop semantics. They run
in waves, as a join that keeps unmatched build rows does (`executeInWaves`):
the build side is materialized, and the probe side is read through a cursor
per probe partition and paired in waves, each advancing up to
`Scheduler.parallelism` cursors in parallel by at most `PairsPerTask` (32768)
units of work, its output emitted before the next wave reads on. So a pairing
holds its build side and one wave's pairs, never the product (the collapsing
path it left held every pair before its first batch), and the build rows a
RIGHT or FULL join leaves unmatched follow the last wave. To stop
that from silently materializing an O(N*M) cartesian over millions of rows,
`PhysicalPlanner.enforceNestedLoopGuard` refuses the plan when both sides'
row counts are proven and `min(left, right) > 5000`, and when a side is itself
such a join, or a join on an equality over one, and the two would pair more
than `NestedLoopMaxPairs` (25 million) rows: `rowsAtMost` bounds a join with
no equality by the product of its sides, which its cardinality estimate (the
larger side) understates, so a chain of them was the case the first rule
missed; it sizes a join on an equality over its sides' bounds, not by the
estimator's own recursion, which sizes a pairing below it as the pairing's
larger side and so hid it (plans/testing/04), and by its larger side whatever
its kind, both sides for FULL (`keyedJoinRows`), where the estimator sizes a
LEFT or RIGHT join by its preserved side to pick a build side: so an outer
join counts no fewer rows than the inner join it holds, and a restriction
of a query, an outer join made INNER, is never refused in the query's own
join order where the query is not (decision 3 of plans/testing/16, which
CERT confirmed). Under an aggregate or a union it reads the estimator with
joins sized that way (`LogicalPlanCardinality.estimateWith`). A proven count
(`provenRows`) is a view's exact one, kept through the operators that keep
every row (project, sort, window), cut by LIMIT and OFFSET, summed by UNION
ALL, and multiplied by a CROSS JOIN; behind a filter, a grouping or a join
condition there is none. The first rule read estimates, a filter keeping a
guessed share of its input, so it refused two one-row filters over large
tables. CSV-rooted joins (no count) keep working — the guard is conservative.
Its refusal is a `NestedLoopGuardException` (an `IllegalArgumentException`),
so a caller can tell it from a refusal of the SQL: which joins have an
equality depends on the order they run in, which `JoinOrder` chooses (§9),
and a plan run without the optimizer may meet the guard where the optimized
plan does not.

### 9. Logical plan optimization

`SqlEngine.execute` runs `LogicalOptimizer.optimize(plan)` after binding and
before physical planning. The optimizer is three explicit passes, called once
each in a fixed order:

1. **`FilterPushdown`** sinks filter conjuncts as close to scans as
   correctness allows. The pattern is
   `LogicalFilter(LogicalJoin(...), pred)`: each conjunct of `pred` is
   classified by `JoinSideAnalysis.sideOf` and either pushed under the
   matching child or left above the join. Inner joins push both sides;
   LEFT outer pushes left-only conjuncts (right-side ones would change
   results — they'd drop the null-extended rows that LEFT JOIN preserves
   when the right side has no match); RIGHT outer is symmetric; FULL outer
   pushes nothing. Conjuncts touching both sides join an inner join's own
   condition, where `PhysicalPlanner.splitEqualityKeys` finds an equality
   among them as a key, so `FROM t, u WHERE t.id = u.id` is a hash join;
   above an outer join they stay above. The pass also
   flattens stacked Filter(Filter(...)) into a single conjunct list before
   deciding, so a pushed filter that lands above another join cascades
   further down on the recursive call.

2. **`JoinOrder`** orders each run of inner joins (a maximal tree of inner
   and comma joins; an outer join is a table of the run above it and a
   barrier between the runs on its sides) so each join has an equality to hash
   on where it can: the written order, but where the next table has no
   equality with the tables joined so far, the first later table that has one
   comes in first. No cost model, so a written order that already keys every
   join it can is kept as it is. The run is flattened into its tables and its
   conditions' conjuncts and rebuilt left-deep, each conjunct at the first join
   that has its tables, and its columns come out in the new order: the moves
   travel up the plan (`(plan, moves)`, as ColumnProjectionPushdown's remaps
   do) and re-index each expression above, up to the projection or aggregate
   that makes the block's columns, so the plan prints as one SELECT; a union
   arm or the root gets a projection restoring the order. A run whose
   conditions read `RAND()` keeps its order, since another would evaluate them
   on other rows. It runs after FilterPushdown, which moves a WHERE's
   conjuncts over two tables into the joins' conditions, where it reads them.
   `sql/plan:join_order_test` holds it to fixed shapes and the metamorphic
   fuzzer to generated ones: comma lists and `CROSS JOIN`s whose equalities
   make a random spanning tree, so a table's equalities may all be with tables
   after it, of up to six tables (plans/testing/15).

3. **`ColumnProjectionPushdown`** prunes unreferenced columns out of
   scans, threading through joins so each side reads only the columns its
   ancestors actually use plus the columns the join condition itself
   needs. The combined output shrinks on both sides, so the condition's
   `ColRefExpr` indices are rebuilt against the new combined schema, and
   the parent receives a remap that updates its own expressions.

Order matters: filter pushdown can shift which columns the joined
sides need (a pushed filter may add or remove ColRef references), and join
ordering reads the conditions filter pushdown put in the joins, so
projection pruning runs last. The framework is deliberately minimal —
an explicit method per pass in `LogicalOptimizer`, called in order by
`optimize`, not a rule engine. Adding a new pass means adding another
method and line; if a new pass needs to run before existing ones, reorder
the lines and document why.

**Every stage's plan is validated** (`PlanValidator`, as Spark validates a
plan after each rule and DuckDB verifies its column bindings): the plan as
`LogicalBuilder.build` binds it (CTE bodies included), after CTE resolution,
and after each optimizer pass. At every node each `ColRefExpr` is within its
input's columns and of that column's type (window keys and arguments, the
aggregate's arguments and a join condition over both sides included), every
condition is a BOOLEAN, a comparison's, IN's and BETWEEN's operands share one
type, a union's arms one width and column types, and no scan of an unresolved
CTE survives resolution; and a pass returns the columns, by name and type, its
input did. A remap bug surfaces at plan time naming the stage and the node's
path (`after ColumnProjectionPushdown at Project > Join.condition: column 7
...`) rather than as an `ArrayIndexOutOfBoundsException` deep inside the
executor or a wrong answer. It walks the plan once a stage, so it is always
on. A HAVING is a `LogicalFilter` above the `LogicalAggregate`, which has no
condition of its own.

`JoinSideAnalysis` (`sql/plan/JoinSideAnalysis.scala`) is the shared
utility both passes lean on: it walks an expression's `ColRefExpr` indices
against `leftWidth` and classifies the expression as `LeftOnly` /
`RightOnly` / `Both` / `Neither`. The physical planner also uses it for
join-key extraction (`splitEqualityKeys`) and right-side index shifting
(`shiftToRight`). Index-based classification matters for self-joins,
where the two sides share field names — index says definitively which
side a ref belongs to.

Joins are now pruned through; unions and window operators still are not.
Union pruning requires identical column sets and remaps on both arms;
window's output is `child ++ syntheticWinN`, so pruning the child shifts
the synthetic column positions. Both are viable extensions deferred to a
future pass (see the optional Phase 5 in `plans/perf/07-push-through-joins.md`).

### 10. Nothing the parser accepts is silently dropped

`LogicalBuilder` binds the JSqlParser AST by calling the getters it knows
about. That makes binding readable, but it also means a clause carried by a
getter the binder never calls does not fail — it simply never reaches the plan.
JSqlParser parses a great deal of vendor SQL (`QUALIFY`, `OFFSET`, `USING`,
`PIVOT`, Oracle's `(+)`), so before this pass existed a query could bind
cleanly and answer a different question from the one asked.

`UnsupportedClauses.reject` runs between parsing and binding
(`LogicalBuilder.build` calls it first). Its data is an audit with one entry per
AST class the binder reaches; each entry lists the class's getters the binder
does not read, as either a `Rejection` (clause name, advice, and example SQL) or
inert, with the reason. A getter the binder does read needs no entry. The pass
walks the statement reflectively: at each node it runs the class's rejections,
then descends through every getter that is neither inert nor totally rejected —
which is, by the audit's invariant, exactly the getters the binder reads. So it
visits the nodes binding will visit, in every position they can occur, and
stops at any class without an audit (the binder rejects those by name). A
clause found this way raises `UnsupportedClauseException`, an
`IllegalArgumentException` that names the clause and carries the entry.

The names the binder refuses by come from the same file:
`UnsupportedClauses.refusal` says what SQL calls a construct the binder has no
case for (`EXISTS`, `IS DISTINCT FROM`, a subquery, a parenthesized join,
`EXCEPT`), keyed by the class JSqlParser parses it as, with advice where there
is any, so a message never names one of JSqlParser's classes or renders a node
with its `toString`, which throws for some. The table lives outside
`LogicalBuilder` so that matching on those classes does not count, in the test
below, as the binder reaching them.

Some getters the binder honours for some uses and drops for others —
`Function.isDistinct` is honoured for `COUNT`, `SUM`, `AVG`, `MIN` and `MAX`
and not for `STDDEV` or `CORR`. Those carry a *partial* rejection that tests
the node (function name, argument shape) and lets the honoured uses through.

The invariant is enforced, not maintained. `sql/plan:unsupported_clauses_test`
derives what the binder reads from `LogicalBuilder`'s compiled class files —
their constant pools name every JSqlParser class the binder matches on and every
member it calls — and fails unless every public getter and field of every
audited class is read, rejected or inert exactly once, every class the binder
reaches (and every JSqlParser subclass of one) is audited, every rejection's
example reaches that rejection, and every expression, FROM item and set
operation without an audit has a name in `refusal`'s table. A JSqlParser
upgrade cannot add a getter that is silently ignored, nor a construct refused
without a name, and the binder cannot start honouring a clause the pass still
rejects. See [extending.md](extending.md#honor-a-clause-the-binder-rejects) for
the workflow and [gotchas.md](gotchas.md) for what the test cannot see.

The generative net over the pass is `fuzz/frontend_fuzz_test`'s NoSilentDrop
property: it splices every entry into every context a query can sit in and every
position an expression can, requires each refused, and checks that each clause
the binder honours keeps its meaning in every context, which is where a getter
read in one place and dropped in another shows.

Two things lie outside what the pass can see. The first is text that never
becomes a getter's value, refused where it shows
([plans/robustness/12](../plans/robustness/12-text-the-binder-never-sees.md)):
`SqlParser.parse` refuses text after the statement's `;`, which JSqlParser
never reads, and a repeated `ORDER BY`, `LIMIT`, `OFFSET` or `FETCH`, which it
overwrites (the statement rendered back has fewer of the clause's keyword than
the text); `LogicalBuilder` refuses the same clause on a UNION's bare last arm
and on the union, a WITH that names one query twice, a join with an ON its kind
does not take or without one it needs, and a NOT JSqlParser hangs on an
operator's left side. `SqlTextGen.BeyondTheTable` holds each refusal to account
in every context, as NoSilentDrop holds the table. The second is a getter the
binder reads whose value is not what SQL means: `StringValue.getValue` keeps a
doubled quote doubled, every identifier getter keeps the quotes, and a number's
`getValue` throws or rounds
([plans/robustness/13](../plans/robustness/13-literals-are-read-as-written.md)).
The binder reads a string literal's `getNotExcapedValue`, a number from its
digits, and every name through `SqlParser.unquote`, the one rule `TaskDag`
reads a job's table references by too; `oracle/SyntaxEquivalence` holds
spellings that differ only in quoting to one answer.

Plan 01's rule, that text which parses must change the outcome or fail, holds
for values too ([plans/robustness/23](../plans/robustness/23-clauses-that-cannot-change-the-answer.md)).
A sort, group or partition key that reads no column (`ORDER BY 'b'`, `GROUP BY
1.5`; `LogicalBuilder.isConstant`) orders, groups or partitions nothing and is
refused at every key site (`refuseConstantKey`), and so are a LIKE pattern that
ends in its escape character and a negative SUBSTRING length, at bind time when
`constantValue` can compute them and on the row otherwise.

### 11. `transformer query`

The engine reads a query and nothing else, so `transformer query` keeps what
a DuckDB-style CLI adds around SQL in the CLI, and hands the engine queries as
they were written:

- **Script.** `SqlScript.split` cuts a script into statements at each `;`
  outside quotes and comments, and into dot commands, each a line starting
  with `.` where a statement would start. `SqlScript.sqlStatement` reads
  `CREATE VIEW`, `DROP VIEW` and `COPY ... TO` off the front of a statement
  with a small lexer of its own (JSqlParser parses no `COPY`), keeping the
  query inside as the text the user wrote, so the engine's errors are about
  their own text. Any other statement goes to the engine whole.
- **Tables.** `--table` and `.table` name a file through `InputResolver`, with
  an `InputFilePath`'s strict format and options, and a job's output directory
  through `TaskRunRecord.outputView`; `--job` takes the catalog
  `JobSession.buildInteractiveCatalog` builds for the GUI's SQL console. All of
  them are views of one flat `Catalog`. The dialect is unchanged: a file has
  only the name a flag gives it.
- **Views.** A view is its query's text, the names it reads
  (`TaskDag.viewsRead`) and its columns. Before a statement runs,
  `QuerySession.withViews` finds the views it reads, and those they read, and
  defines them, each after the views it reads, in a WITH ahead of it
  (`SqlScript.withDefinitions`: into the statement's own WITH when it has one,
  after an EXPLAIN's keywords when it is one), each body on lines of its own so
  a trailing comment cannot swallow the parenthesis. The engine then binds and
  optimizes the view inside the statement, as it would a CTE written there; a
  view read twice in one statement is materialized once, as such a CTE is
  (§6b). A view is bound when it is created, which checks it and records its
  columns, and refused if it would read itself.
- **Output.** `ResultPrinter` reads an `ExecutedQuery`'s partitions on the
  pool (`ParallelDrain`, §3) and makes every value's text on the statement's
  thread. A table and markdown of several partitions read through
  `eachPartition`, at most `ParallelDrain.inFlight` partitions at once, and
  print once every partition is read, so they first check that the output can
  still take them: a table keeps each partition's count and its first and last
  rows as values (`TableSlices`, which lets go of a partition's first rows
  once those before it have counted the rows shown first, and its last once
  those after it have counted the rows shown last), then finds the rows shown
  among them and makes their text, the same box as for its batches in order;
  markdown keeps every batch, as it kept every row. The other formats print
  from `inOrder`'s iterator, today's printers over the batches in order, read
  ahead on the pool and closed in a `finally`. A result of one partition is
  read on the statement's thread, as its batches come. CSV goes through
  `CsvRowWriter`, JSON escapes through `Json.escape`, a type is named by
  `DataType.sqlName`, and a value's text is `CsvValues.format`'s, the text the
  CSV writer writes. `COPY` and `--output` write one file through
  `CsvWriter.writeAll` / `ParquetWriter.writeAll` from `inOrder`'s iterator,
  decoded ahead on the pool and encoded on the statement's thread. A join's
  probe runs inside those reads (§8): a table or markdown probes every
  partition at once, an ordered format or a one-file `COPY` mostly the
  partition being written, since the read-ahead holds one chunk of each
  partition ahead and a selective join's chunk is many probe batches. `COPY ...
  (FORMAT parquet, PER_PARTITION_OUTPUT true)` writes a new or empty directory
  of a parquet file a partition through `ParquetWriter.writePartitioned`, as a
  job's table is written but with no `_run.json`, its partitions claimed and
  wrapped so a failure in one cancels the statement's stop; a failed or
  stopped write leaves no part file, nor a directory it made, and the error
  said is the lowest-numbered partition's that is no stop.
- **Errors.** When a partition fails, the drain stops the statement's other
  partitions at once and the statement says the lowest-numbered partition's
  failure that is no stop; with several, that can vary between runs, and an
  ordered format may have printed fewer rows before it. Once the run's
  `Cancel` is set (Ctrl-C, a closed output), a failure met on the way out says
  nothing, as a stop says nothing.
- **Shell.** `Shell` reads inputs through JLine. Its parser answers JLine's
  "is this line whole?" with `SqlScript.complete`, so a statement over several
  lines is one input and one history entry, and treats no character as an
  escape (JLine's default drops each backslash). History expansion is off, as
  `!=` is SQL's. Each input runs on a thread of its own, through an `Io` whose
  streams fall silent once its `Cancel` is set; Ctrl-C sets it (§3g) and the
  prompt returns when the statement has stopped. A statement printing rows
  also stops once its output can take no more (`Io.printing`: a reader that
  closed its end of a pipe, as `head` does), which a `PrintStream` reports only
  through `checkError`. The system terminal prefers
  JLine's `exec` provider (no native code, so no JDK warns of native access) on
  macOS and Linux.
- **Options.** Each statement runs with `QuerySession.optionsFor`: spill as
  `--spill` or `.spill` set it, and scans that stop with the statement's own
  `Cancel`, a child of the run's (§3g).

## Property-based testing

Alongside the example-based unit tests, `src/test/scala/com/transformer/fuzz/`
holds a hand-rolled property-based harness (no new dependency — PBT by hand, like
the engine itself). Each property generates random-but-valid inputs, checks an
invariant over many seeds, and shrinks any counterexample to a minimal repro.
The oracle families over generated queries, in increasing semantic reach, and
one over generated text:

- **Expression parity** (`oracle/ExprParity`) — a generated type-correct `Expr`
  over a random `ColumnarBatch` must evaluate identically through the scalar
  `eval` and the vectorized `evalVec` paths. Guards every `evalVec` override.
- **Mode-differential** (`oracle/ModeDifferential`, and its multi-relation
  extension `oracle/MetaModeDifferential`) — one `(data, query)` must produce the
  same result multiset under spill on/off, metrics on/off, and every
  partition/batch layout. The metamorphic variant covers joins; a separate
  sharded JVM (`ShardedModeFuzzTest`, `jvm_flags` force the sharding gates) re-runs
  it on the shuffle-join + sharded-aggregate paths. Differential by nature: it
  catches disagreements between execution modes but is blind to a wrong answer
  they all share.
- **TLP** (`oracle/Tlp`, ternary logic partitioning) — a query's output, split by
  a predicate into the `p` / `NOT p` / `p IS NULL` partitions, must reassemble (as
  a multiset) into the unpartitioned output. Decided by SQL semantics alone, so it
  catches wrong answers every execution mode agrees on. Partitioning the OUTPUT
  via a CTE wrapper keeps it sound for aggregates, windows, unions, and joins.
- **NoREC** (`oracle/NoRec`, non-optimizing reference engine construction) —
  `COUNT(*) WHERE p` must equal `SUM(CASE WHEN p THEN 1 ELSE 0 END)`: the same
  predicate as a filter vs. as a projection.
- **Join commutativity** (`oracle/JoinCommutativity`) — `A <kind> JOIN B ON p`
  must equal `B <flipped kind> JOIN A ON p` (INNER/FULL unchanged, LEFT↔RIGHT),
  with the rest of the query reused verbatim. The only relation that varies which
  side of a join the planner builds, so it bites on the build-side swap and the
  null-extension direction.
- **Aggregate decomposition** (`oracle/AggDecomposition`) — aggregating in one
  shot must equal grouping by an arbitrary key and re-aggregating the per-group
  results. Run in heap and under spill, it drives the partial/final merge and the
  `AggState` spill serde that a global aggregate (one accumulator, never flushed)
  never reaches.
- **Value-preserving rewrites** (`oracle/Eet`, EET's relation, OSDI 2024) — up
  to three subexpressions of a query's bound plan, anywhere the binder puts
  one, each wrapped in a form that cannot change its value (`CASE WHEN FALSE
  THEN r ELSE e END` with an `r` that would fail, `COALESCE(e, e)`, a condition
  ANDed with a tautology, ...), printed as SQL and run: the answer must not
  change. It needs no reference engine and reaches every generator and, as the
  slt `rewritten` mode, every script; it aims at the guards a conditional
  keeps (a branch never taken must never run) and at passes that move or drop
  a condition they do not recognize.
- **Forced plans** (`oracle/ForcedPlans`, DQP's relation, SIGMOD 2024) — every
  plan the engine can be made to choose for one query gives one multiset: the
  leading run of inner joins written in every order and run with the
  optimizer off and on, and every join respelled so it hashes on its key, on
  computed keys, on two keys or on none (pairing row by row), with spill on or
  off and its sides swapped, in a JVM with the sharding gates shut and in one
  with them open. It aims at the paths and codecs a join chooses among, which
  the planner otherwise picks one of for a query.
- **Known join answers** (`oracle/JoinTruth`, TQS's relation, SIGMOD 2023) — a
  wide table split along its keys into a fact table and dimension tables, so
  the answer to a query walking the keys is known without the engine: a
  small evaluator joins the tables by nested loops, pads an outer join's
  unmatched rows, filters in three-valued logic and adds in `BigInt`. The
  one relation here with an expected answer from outside the engine at fuzz
  volume, for multi-way and outer joins, where every relation that compares
  the engine with itself agrees with a wrong answer it gives everywhere.
- **Three small relations** (plans/testing/16 W4, in `metamorphic_fuzz_test`) —
  TypeCheck (PVLDB 2026, `oracle/TypeCheck`): the same values stored as INT,
  BIGINT and DECIMAL(10,0) give the same answers, so the typing rules apply as
  one rule; window cross_check (RisingWave, `oracle/WindowCrossCheck`): two
  windows in one SELECT equal each alone joined on a row id, aimed at the one
  `WindowExec` that computes every spec; CERT (ICSE 2024, `oracle/Cert`): a
  restriction never gets a larger estimate, nor a refusal from the
  nested-loop guard in the query's own join order, planned over tables that
  report a size and hold no rows.
- **Frontend robustness** (`oracle/CleanRejection`, `oracle/NoSilentDrop`,
  `oracle/SyntaxEquivalence`, over `SqlTextGen`) — every generator above renders
  its own typed AST, so its corpus is the supported grammar. This one writes
  text: mutated queries, the `UnsupportedClauses` table spliced everywhere,
  honoured clauses beside their clause-free twins, and pairs of equivalent
  spellings. A text must run or fail cleanly (an `IllegalArgumentException`
  naming what the user wrote, never an internal exception or marker); a clause
  must be refused or keep its meaning in every context; two spellings must
  agree. Known defects are recorded in the oracles with their plans and repros.

**A query that fails on its data.** The generators draw values at their
types' edges and CASTs that can fail, so a generated query may stop on an
integer overflow, a decimal past its precision, a date past a TIMESTAMP's range
or text a CAST cannot read (`CleanRejection.DataErrors`). The layout and
metamorphic relations hold every layout to agree on whether a query fails and,
where it answers, on the answer, but where the failure can come from a WHERE or
ON condition the optimizer may move (`QueryGen.mayFail`, `movableFailure`), since
pushing a filter into a join or reordering joins evaluates a condition on other
rows; TLP checks nothing when the query fails, and which failure a layout
reports is never compared (decision 1 of plans/testing/15). `RelEngine.answer`
returns a failure on the data wherever it is met: in the drain, or while
planning, since a global aggregate runs as its query is planned.

Every generator draws all ten of the engine's value types, and every oracle
compares with the engine's own equality rather than the JVM's (`RowOracle`): a
byte array by content, a decimal by value whatever its scale. Each property
carries a coverage guard per type asserting that the type reaches the sites the
property exercises and that the values its comparison gets wrong are present
there; the recipe for a new type is in
[extending.md](extending.md#add-a-property--generator).

The default `bazel test //...` runs each over a small fixed-seed batch; the
`*_fuzz_campaign` targets scale to long runs. Full mechanics, the generators, and
recipes for adding a property or a metamorphic relation are in
[testing.md](testing.md#property-based-testing-fuzz) and
[extending.md](extending.md#add-a-metamorphic-relation).

## SQL logic tests and whole-suite configurations

Every property above compares the engine with itself, so an answer that is
wrong the same way in every mode passes them all. Two more nets, borrowed from
DuckDB and Spark SQL, check answers against expectations written outside the
engine and multiply each one across execution modes:

- **sqllogictest scripts** (`src/test/slt/`, run by
  `src/test/scala/com/transformer/slt/`): each query beside its expected
  result, in SQLite's format, this repo's own scripts per area of semantics,
  slices of SQLite's corpus, whose answers five engines agreed on, and the
  agreed corpus, queries the fuzz generators drew whose answers Postgres and
  DuckDB agree on (`agreed/`, written by `slt/AgreedCorpus` through
  `fuzz/PortableSql`, which prints a generated query as SQL all three read
  alike). The runner
  carries out the DDL itself (`Database`) and runs every query under fourteen
  verification modes (`Modes`: layouts, spill, metrics, parquet files read
  back, columns stored in another order, the logical optimizer off or one
  pass at a time, the plan printed as SQL, below, and the plan rewritten in
  forms that cannot change its values, EET's relation), DuckDB's query
  verification. Each mode must print the baseline's result. A record the
  engine answers otherwise by design cites its rule in the ledger in
  [gotchas.md](gotchas.md#answers-that-differ-from-other-engines-by-design)
  (`skipif transformer <id>`) and still runs once, so a skip that no longer
  diverges fails.
- **Whole-suite configurations** (`.bazelrc` `--config=batch1`, `batch2`,
  `sharded`, `serial`, `zone`, `hashzero`, `verify`, `leaks`, `chaos`): the whole suite under one
  JVM-wide setting apiece, the batch capacity among them, as DuckDB runs its
  tests at a vector size of 2, every key hash 0 (`KeyHash` in
  `core/HashKeys.scala`, which every hash site goes through: the key wrappers
  and `LongHashMap` the hash maps key by, `HashPartitioner`'s shard routing, the
  grace join's and the spilling window's buckets), as DuckDB's `HASH_ZERO`
  build does, and every batch every operator emits checked against its schema
  (§2d), as DuckDB's debug builds verify every vector, and what each test
  JVM leaves open audited at its exit (§3h), as Spark's `DebugFilesystem`
  fails a test that leaves a stream open, and every handoff between threads
  perturbed by a seed (§3i). A test that depends on a default says so with
  `assumeTrue`.

Formats, modes, the corpus and how to write a script are in
[testing.md](testing.md#sql-logic-tests).

## Plans as SQL

`PlanSql` (`sql/plan/PlanSql.scala`) prints a plan as SQL that
`LogicalBuilder.build` reads back to the same plan: binding's inverse, so a
plan can be read as the query it is, and so the binder can be held to its own
output as DuckDB re-parses each statement it prints.

- **One SELECT per stack.** The binder builds a SELECT as a fixed stack of
  nodes, top down: LIMIT, OFFSET, the projection that drops the columns ORDER
  BY computed (`_orderN`), the sort, DISTINCT, the SELECT list, the window
  functions, HAVING (a filter over the aggregate), the aggregate, WHERE, and a
  left-deep tree of joins over tables. The printer reads that stack off a plan
  (`readSelect`) and prints one SELECT for it; anything that fits no slot (a
  filter the optimizer pushed under a join, a scan it pruned to some columns,
  the query a nested WITH left where a table would be) becomes a CTE of its
  own that the SELECT reads. A CTE read once is inlined when the text is bound
  again, so it adds nothing to the plan but a projection of every column in
  order, which the comparison sets aside. A union prints as one chain where its
  tree is the balanced one the binder builds from a chain, and in parentheses
  otherwise; an AND or OR prints so too, the parser regrouping a chain as the
  same balanced tree.
- **Names.** Every column is printed with its FROM item's alias (`t.a`), so
  ORDER BY's rules for output names never come into play; an ORDER BY key is
  an output position (`ORDER BY 2`) or the expression it computes; a CTE whose
  columns repeat a name is printed with them renamed.
- **Types.** Each cast the binder inserted is printed, but a cast to DECIMAL or
  BINARY, which the binder inserts again wherever the operands' types call for
  it (SQL cannot write a BINARY's, and a written cast to DECIMAL is a
  `DecimalCastExpr`, which fits its value where the binder's reads it exactly,
  so it prints as itself). The one place the binder's DECIMAL casts are not
  what its operands call for again is a BETWEEN, whose three operands meet as
  one type where each of its two comparisons would meet as a pair, so the
  comparisons it expands to print as the BETWEEN where its operand is cast to
  a DECIMAL (`DecimalBetween`). A CAST's operand that is a condition (a
  comparison, IS NULL, IN, LIKE, BETWEEN, AND or OR) is printed in
  parentheses, the only way JSqlParser reads it. A literal is printed so that it binds to its own
  type wherever it stands: a BIGINT an INT would hold as `CAST('5' AS BIGINT)`,
  a FLOAT, NaN and the infinities as a CAST of their text, a DATE and a
  TIMESTAMP as `DATE '...'` and `TIMESTAMP '...'`, a DECIMAL as its digits
  (one stands only beside a DECIMAL, where a number is the decimal of its
  digits, or past a BIGINT), and a negative number as the negation of its
  magnitude.
- **Unprintable plans.** A scan of a view no catalog holds (a CTE's
  materialized rows: print a plan before `CteResolver.resolve` runs it, or use
  `CteResolver.planned`, which decides without running anything) and a string
  holding a backslash before a quote, which no literal writes, throw
  `PlanSql.UnprintablePlanException`.

`EXPLAIN <query>` (`sql/exec/Explain.scala`, detected by
`SqlParser.explained` before the query binds) shows a plan without running it:
the optimizer's plan as a tree (`PlanTree`, a line per operator, expressions
printed by `PlanSql`) and as SQL; `CteResolver.planned` says which CTEs would
be materialized, each shown as a scan and its own plan. `EXPLAIN VERBOSE` adds
the plan as bound and the physical operators, planned over a stand-in for each
materialized CTE that is never read. The job runner reads an EXPLAIN's views
from its query (`TaskDag.viewsRead`).

The round trip is tested three ways: `sql/plan:plan_sql_test` over every
construct, the slt runner's `reprinted` and `optimized-reprinted` modes over
every script, and `fuzz:plan_roundtrip_fuzz_test` over every query generator.
`PlanRoundTrip.check` binds a query, prints its plan, binds the text and holds
the two plans equal once each is normalized (every CTE inlined, names the
binder or printer chooses set aside, a projection of every column in order
removed, a projection of a scan's columns read as a pruned scan, the negation
of a number read as the negative number), with the same output names and
types, and prints the second plan to the same text. Its first finding was the
binder's: it kept an aggregate per spelling, so `SUM(a)` and `SUM(t.a)` were
computed twice; each aggregate and window function is now computed once,
however it is spelled.
