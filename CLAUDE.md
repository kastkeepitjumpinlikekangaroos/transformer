# CLAUDE.md

Guide for future Claude sessions working on this repo. Read this first.

## What this project is

`transformer` is a single-node, Spark-inspired ETL library in Scala 2.13. The
project's *interesting* part is that the SQL engine is hand-built — parser
façade (JSqlParser), logical planner, physical planner, parallel executor — no
Spark, no Calcite, no DuckDB. The non-SQL parts (CSV, Parquet, templating, job
runner) are deliberately thin and use stdlib + a few targeted Maven deps.

The user explicitly chose "JSqlParser for AST only, build our own planner +
executor" over Calcite/DuckDB shortcuts. Honor that direction. If you find
yourself wanting to add `org.apache.calcite` or wrap something like DuckDB,
stop and ask — that conflicts with the project's purpose.

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
| `read/parquet/` | Parquet input. Depends on `parquet-hadoop` + minimal Hadoop. | `ParquetReader.scala`, `ParquetSupport.scala` (installs hooks) |
| `write/csv/` | CSV output. Stdlib only. | `CsvWriter.scala` |
| `write/parquet/` | Parquet output + shared `ParquetSchema` converter. | `ParquetSchema.scala`, `ParquetWriter.scala` |
| `sql/parse/` | JSqlParser façade. | `SqlParser.scala` |
| `sql/plan/` | Expression types, logical plan, JSqlParser → logical conversion, analyzer. | `Expr.scala`, `Ops.scala`, `Funcs.scala`, `LogicalPlan.scala`, `Analyzer.scala`, `LogicalBuilder.scala` (largest file in the repo) |
| `sql/exec/` | Physical operators + planner + entry point. | `PhysicalPlan.scala`, `AggregateExec.scala`, `JoinExec.scala`, `SortExec.scala`, `DistinctExec.scala`, `UnionExec.scala`, `RowBuf.scala`, `PhysicalPlanner.scala`, `SqlEngine.scala` |
| `job/` | User-facing API + runner. | `DataJob.scala`, `InputFilePath.scala`, `OutputFilePath.scala`, `SQLTask.scala`, `JobResult.scala`, `InputResolver.scala` (+ `ParquetResolverHook`, `ParquetReaderHook`, `ParquetWriterHook`), `TaskDag.scala` (DAG analyzer/builder from SQL refs; `TaskDag` + `TaskDagNode` are public so the GUI can render without re-implementing), `TaskProgressListener.scala` (per-task callbacks fired from runner worker threads), `RunMarker.scala` (`_SUCCESS` write/read/discover for per-task success markers), `DirectoryJobLoader.scala` (DBT-style directory loader; supports optional per-table `output.json` with `partitionBy`), `Json.scala` (stdlib JSON parser used by the loader + RunMarker.read) |
| `gui/` | JavaFX visualizer/runner. Depends on `job/`, `core/`, `read/csv/`, `sql/exec/`, `temporal/`, plus `org.openjfx:javafx-{base,controls,graphics}` (bare jars + platform-classifier jars). | `GuiApp.scala` (Application boot + BorderPane wiring), `JobSession.scala` (mutable FX-thread session state — jobDir, executionTime, outputDir, DAG, per-task UI states, markers, historical runs), `DagLayout.scala` (pure-Scala layered DAG layout), `DagCanvas.scala` (custom Canvas: pan/zoom/click/double-click), `ControlsPanel.scala` (open dir, exec-time pickers, output-dir field, Run button on worker thread), `TaskDetailsPanel.scala` (selected task's source + rendered SQL + status + provenance — uses `SqlView` for highlighted SQL and chips for status/deps/metrics), `SqlHighlighter.scala` (pure-Scala tokenizer: keywords/functions/strings/numbers/comments/templates — testable, no JavaFX deps), `SqlView.scala` (read-only highlighted SQL pane built on `HBox` per line inside a `ScrollPane`; toolbar with Copy + optional Open-in-editor button), `ExternalEditor.scala` (launches `TRANSFORMER_EDITOR` if set, else macOS Terminal+`nvim`; no-wait launch via `ProcessBuilder`), `ResultsTabPane.scala` (Output data tab with partition picker + run log tab), `FxHelpers.scala` |
| `examples/scala_app/` | Sample app built as a `scala_binary` deploy jar — programmatic `DataJob(...)` API. | `src/main/scala/com/example/ExampleJob.scala` |
| `examples/directory_app/` | Sample app using `DirectoryJobLoader` — whole job is a folder of JSON configs + SQL files. | `src/main/scala/com/example/directory/DirectoryJobExample.scala`, `job/inputs/<view>/config.json`, `job/tables/<view>/main.sql`, `job/tables/<view>/validations/*.sql`. Accepts optional 3rd CLI arg for `executionTime` (ISO instant) so the same job can produce multiple partitions for testing. |
| `examples/jaffle_shop/` | Port of [dbt-labs/jaffle-shop](https://github.com/dbt-labs/jaffle-shop) to the directory format. 6 raw seed CSVs → 6 staging tables → 3 intermediate aggregations → 3 marts (`customers`, `orders`, `order_items`) + 3 passthroughs (`locations`, `products`, `supplies`). 26 DBT data_tests ported as zero-row validation queries. Exercises the DAG scheduler at a realistic scale (~150k rows, 15 tasks). Omissions vs. DBT: `metricflow_time_spine` (needs `dbt_date`); `customer_order_number` ROW_NUMBER column on `orders` (no window functions); semantic models / metrics / saved_queries / unit_tests (no equivalent layer). DBT CTEs are split — each `with X as (...)` becomes its own SQLTask, since `LogicalBuilder.fromItem` only accepts `Table` in FROM. | `src/main/scala/com/example/jaffle/JaffleShopExample.scala`, `job/data/raw_*.csv`, `job/inputs/<view>/config.json`, `job/tables/<view>/{main.sql,validations/}`. |
| `examples/gui_app/` | Sample app launching the GUI. Pulls in `gui/` + `sql/exec/` + `read/parquet/` + `write/parquet/` so the GUI's parquet preview + parquet I/O Just Work. | `src/main/scala/com/example/gui/GuiAppLauncher.scala` |

## Cross-cutting patterns

### 1. The hook system (avoids dependency cycles)

`job/` cannot depend on `read/parquet/` because parquet pulls in Hadoop and
most users don't want it. Solution: three global hook objects live in `job/`:

- `ParquetResolverHook` — `InputFilePath → CatalogView` for parquet inputs
- `ParquetReaderHook` — re-read a just-written parquet file for validations
- `ParquetWriterHook` — write batches to a parquet path

`read/parquet/ParquetSupport` installs all three on class load. As soon as a
user adds `//src/main/scala/com/transformer/read/parquet` to their `scala_binary`
deps, parquet just works. Same pattern with `core/SqlExecutorRegistry` and
`sql/exec/SqlEngine`: the SQL engine self-installs when its class is loaded.

If you add a new format (orc, json, avro), follow the same pattern. Don't
make `job/` depend on the new module.

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
per file. Parquet → one partition per row group. Plan accordingly when you
care about throughput.

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

## Conventions

- **Scala 2.13.16.** rules_scala pins this. Match the version in the reference
  project `~/grid-game`.
- **JDK 21** via `.bazelrc`.
- **`sealed trait` lives in the same file as its case classes/objects.** Scala
  enforces this; if you split, you'll get the "illegal inheritance from sealed
  trait" error. The original `PhysicalPlan` was `sealed`; it's now an open
  trait because the operator implementations are in separate files
  (`PhysicalPlan.scala`, `AggregateExec.scala`, etc.).
- **`ColumnarBatch` is `final`** — don't subclass it. Use the factory
  `RowView.apply(schema, values)` or the mutable `RowBuf` for 1-row batches.
- **No emojis** in code or docs unless explicitly requested.
- **No `*/` inside Scaladoc** — the Scala lexer closes the comment at the first
  `*/`. Glob examples like `data/*.csv` inside a `/** */` block are a footgun;
  use `'*'.csv` or move the example to plain prose. We hit this twice.
- **Don't pre-declare `private val`s used by class-body initializers below
  them.** Scala initializes vals in source order. Put lookup tables above the
  code that reads them (see `CsvWriter`'s `needsQuotingChars` issue we fixed).
- **Prefer dependencies on direct uses.** rules_scala uses strict-deps; if you
  call into a type that lives in `core`, you must list `//src/main/scala/com/transformer/core`
  even when an indirect dep already brings it in. The error message is "Symbol
  'term com.transformer.core' is missing from the classpath."

## Build / test commands

```bash
bazel build //...                     # build everything
bazel test  //...                     # run every junit target
bazel test  //src/test/scala/com/transformer/sql/...   # just the SQL tests
bazel build //src/main/scala/com/transformer/<module>:<name>   # one module

# Build the example deploy jars.
bazel build //examples/scala_app:example_job_deploy.jar
java -jar bazel-bin/examples/scala_app/example_job_deploy.jar \
    examples/scala_app/data/input /tmp/transformer-example-out

bazel build //examples/directory_app:directory_example_deploy.jar
java -jar bazel-bin/examples/directory_app/directory_example_deploy.jar \
    examples/directory_app/job /tmp/transformer-directory-out [executionTime]
# `executionTime` is an optional ISO instant (e.g. 2026-01-02T00:00:00Z) —
# useful for producing several partitioned outputs from one job to demo the
# GUI's historical-run picker.

bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar \
    [examples/jaffle_shop/job] [/tmp/transformer-jaffle-out] [executionTime]
# Port of dbt-labs/jaffle-shop — 15-task DAG over the full DBT seed dataset.

# Build + launch the JavaFX GUI.
bazel build //examples/gui_app:gui_app_deploy.jar
java -jar bazel-bin/examples/gui_app/gui_app_deploy.jar [job-dir]
```

Tests are JUnit 4 via `scala_junit_test`. Each leaf test directory has a
`BUILD.bazel` with one target per test class. When you add a new test, make
sure the test class name ends with `Test` (matches the `suffixes = ["Test"]`
discovery rule).

## How to extend

### Add a scalar SQL function

1. Add it to `Funcs.returnType` in `sql/plan/Funcs.scala` so the analyzer
   knows the return type.
2. Add an `apply` case in the same file with the runtime semantics.
3. Add a test in `src/test/scala/com/transformer/sql/exec/SqlEngineTest.scala`.

### Add an aggregate function

1. New `AggExpr*` case class in `sql/plan/Expr.scala`. Set `resultType`.
2. New `AggState` subclass in `sql/exec/AggregateExec.scala` implementing
   `update`/`merge`/`finish`.
3. Wire it in `AggState.init` (`AggregateExec.scala`) and
   `LogicalBuilder.bindAgg` (`sql/plan/LogicalBuilder.scala`).
4. Test it.

### Add a new file format (e.g., JSON)

1. `read/json/` module with a `JsonReader extends CatalogView` and BUILD file.
2. `write/json/` with a `JsonWriter` (atomic temp + rename pattern).
3. Either:
   a. Add to `InputResolver.resolve` directly if it has no heavy deps (like CSV), or
   b. Install via a new hook (like `ParquetResolverHook`) if it pulls in
      transitive deps users may not want.
4. Update `InputFilePath.detectedFormat` and `OutputFilePath.detectedFormat`
   to recognize the extension.
5. Update `DataJob.materializeIfNeeded` so validation re-reads work.

### Add a config field to the directory loader

`DirectoryJobLoader` (in `job/`) is a thin DBT-style layer over the programmatic
`DataJob` API. It walks `<jobDir>/inputs/<viewName>/*.json` and
`<jobDir>/tables/<viewName>/{main.sql,validations/*.sql,output.json}` and emits
regular `InputFilePath` / `SQLTask` values — there's no separate runtime. The
JSON parser itself is in `job/Json.scala` (stdlib only — no Jackson, no circe).

To accept a new field in an input JSON config:
1. Add the field to `InputFilePath` (if it isn't there already).
2. Read it via the `JsonObject` accessors (`requiredString`, `optString`,
   `optBool`, `optStringMap`) inside `loadInputs` in `DirectoryJobLoader.scala`.
3. Test it in `DirectoryJobLoaderTest`.

For per-table config there's an optional `tables/<viewName>/output.json`
(`loadOutputConfig` reads it). Today it carries one field:

```json
{ "partitionBy": "day={{today}}" }
```

`partitionBy` is appended to the task's output path; the resulting templated
path goes through `TemplateRenderer` at run time, so the output lands under
`<outputDir>/<viewName>/day=YYYYMMDD/`. To add another field, extend
`loadOutputConfig` to read it and adjust the `SQLTask` / `OutputFilePath`
construction in `loadTables`. Cover both presence and absence in
`DirectoryJobLoaderTest`.

Conventions baked into the loader:
- View name comes from the directory name, never from JSON. JSON `viewName` is
  silently ignored (we don't fail to keep the loader lenient).
- Tables are listed in **alphabetical order of directory name**. This only
  determines task *declared index* (and any synthetic `__task_N` viewName for
  tasks without an explicit one) — `DataJob` builds a DAG from each task's SQL
  and parallelizes independent branches, so listing order does NOT constrain
  execution order. Numeric prefixes are still useful as documentation, not as
  a scheduling hint.
- Relative input paths are resolved against the job directory before
  `InputResolver.resolve` sees them; cloud paths (`gs://`, `s3://`) and
  absolute paths are passed through.
- Default `outputDir` is `<jobDir>/output`; both `outputDir` and JSON `path`
  strings flow through `TemplateRenderer` at run time, so they may contain
  `{{ today }}` etc.

### Extend the GUI

`gui/` is a JavaFX library; `examples/gui_app/` is the launcher that bundles
it with sql/exec + read/parquet + write/parquet so everything Just Works at
runtime. Pattern recap:

1. **Mutable session, not Property-bound.** `JobSession` holds the truth;
   panels register a `() => Unit` listener and call `session.…` mutators on
   the FX thread. All cross-panel coordination flows through the session.
2. **Worker-thread → FX thread marshalling.** The runner thread for Run and
   the background loader for output preview both round-trip back to the FX
   thread via `FxHelpers.onFx`. Listeners passed into `DataJob.run` fire
   from runner workers — never touch FX state without marshalling.
3. **Canvas is plain `Canvas` + GraphicsContext.** No scene-graph nodes per
   DAG node; we render the whole graph in `DagCanvas.render()` and call it
   imperatively whenever state changes. No AnimationTimer — DAG visualizers
   don't need 60fps.
4. **Layout is pure code.** `DagLayout` has no JavaFX dep so it's trivially
   testable. World coords are zoom=1 pixels; screen coords are `world * zoom
   + pan`.

To add a new panel: write a JavaFX `Region` subclass that takes `JobSession`
in its ctor, registers a listener, and reads accessors. Wire it into
`GuiApp.start` (BorderPane has free slots; or nest in the SplitPane). Don't
add new state to JobSession unless multiple panels need it.

To wire a new `DataJob` event into the GUI: extend `TaskProgressListener`
(non-breaking — defaulted to `NoOp`) and have `ControlsPanel`'s listener
forward to a new `JobSession.markXxx` method.

To extend SQL syntax highlighting: `SqlHighlighter.scala` is a pure-Scala,
single-pass tokenizer with no JavaFX deps. Add new keywords/functions by
editing the in-file sets at the top, and cover the change in
`gui/sql_highlighter_test`. Token colours live in `SqlView.colorFor` (palette
intentionally mirrors VS Code's dark+ theme).

To change the editor that "Open in editor" launches: users set
`TRANSFORMER_EDITOR=<cmd>` in their environment (the file path is appended
as a single trailing arg, no shell). With no override `ExternalEditor` opens
`Terminal.app` and runs `nvim <file>` via `osascript` on macOS, and throws a
helpful error on other platforms. Buttons surface launch failures through an
`Alert`.

### Add a SQL operator (e.g., subqueries)

1. AST handling in `LogicalBuilder.fromItem` (which currently only handles
   plain `Table`). For subqueries you'd add a case for
   `net.sf.jsqlparser.statement.select.PlainSelect` and recurse into
   `buildSelect`, then wrap as a synthetic `CatalogView` over a materialized
   batch list or a new `LogicalSubquery` node.
2. Logical → physical mapping in `PhysicalPlanner.plan`.
3. Optionally a new physical operator (one file in `sql/exec/`).
4. Test against `SqlEngineTest`.

### Add cloud support (v1.1 work)

The interfaces are already in place:

- `InputFilePath.isCloud` detects `gs://` / `s3://` prefixes.
- `InputResolver.resolve` raises `UnsupportedOperationException` for cloud
  paths today. Replace this with a download-into-cache step that writes to
  `./.transformer-cache/<sha1(path)>/` and then delegates to the local
  resolver.
- Same for `DataJob.writeOutput` on the output side: upload the locally-
  written file after successful close.
- Add `read/cloud/` with GCS + S3 helpers; ideally hook-installed so the
  `job/` module doesn't compile against the cloud SDKs.

Don't pull in `google-cloud-storage` or `aws-sdk-v2` in modules that don't
need them. Cloud is opt-in.

## Known gotchas

- **JSqlParser 5.0 doesn't have a `BooleanValue` class.** TRUE/FALSE come
  through as `Column` references. `LogicalBuilder.bindExprWithAggs` has a
  special case for unqualified `Column("TRUE"|"FALSE")` when no actual
  column of that name exists.
- **JSqlParser's `AllTableColumns` is a subtype of `AllColumns`.** Pattern-match
  `AllTableColumns` first or you'll never reach the `table.*` branch.
- **JSqlParser's `SelectItem<? extends Expression>` generics confuse Scala
  inference.** `LogicalBuilder.buildSelect` annotates the list as
  `Seq[SelectItem[_ <: Expression]]` to keep `getExpression` typed.
- **`COUNT(*)` in JSqlParser is a `Function` with `AllColumns` in its
  parameter list, not an empty parameter list.** `bindAgg` checks both
  `isAllColumns` and `params(0).isInstanceOf[AllColumns]`.
- **Aggregate inside HAVING / ORDER BY** must be rebound against the aggregate
  output, not the source schema. `LogicalBuilder.bindExprWithAggs` takes an
  `aggResolver: Function => Option[Expr]` that returns a `ColRefExpr` into the
  aggregate output for known aggregates. The plain `bindExpr` passes
  `_ => None` and fails on any agg.
- **Parquet `MessageType` constructor takes `java.util.List[Type]`, but a
  `Vector[PrimitiveType].asJava` gives `java.util.List[PrimitiveType]`.**
  Scala won't unify because of Java type invariance. `ParquetSchema.toMessageType`
  maps `_.asInstanceOf[Type]` first.
- **No circular module deps.** `read/parquet` depends on `write/parquet` for
  the shared `ParquetSchema` converter; the reverse direction goes through
  hooks (`ParquetSupport` lives in `read/parquet` and installs into
  `job/`-owned hook objects).
- **CSV writer's `needsQuotingChars` must be initialized before the header is
  written.** Class-body order matters; put it above the `if (options.header)`
  block.
- **Output paths are directories, not files.** Anything Spark/Hive-y you might
  expect (`output/foo.csv`) is now a directory of `part-NNNNN.csv` files; older
  examples that wrote a single named file have been renamed. Path-based format
  detection still looks for `.csv` / `.parquet` substrings, so an ext-less
  directory path (e.g. `output/totals`) needs `format = Some("parquet")` or
  the default (CSV) wins. `DirectoryJobLoader` sets `format` explicitly to keep
  the per-table dir name clean (`output/<view>/...` rather than `output/<view>.csv/...`).
- **Hadoop's `LocalFileSystem` writes hidden `.crc` sidecars** alongside any
  Parquet temp file; the atomic rename leaves them stranded inside the output
  directory. `PathGlob.expand` skips dotfiles and `_`-prefixed files so re-reads
  ignore those (and macOS `.DS_Store`, and the `_SUCCESS` markers we now stamp
  per task — see cross-cutting pattern #4).
- **JavaFX 21+ ships classes only in platform-classifier jars.** The bare
  `org.openjfx:javafx-{base,controls,graphics}` artifacts are metadata-only —
  depending on them alone gives "not found: type Stage" at compile time. The
  `gui/` BUILD lists both the bare jars *and* the `mac-aarch64` classifier
  jars. Add other platforms (`win`, Linux) the same way when building
  cross-platform binaries.
- **`TaskDag` / `TaskDagNode` are public** despite living next to runner
  internals — the GUI needs them to render structure without re-implementing
  the analyzer. Don't accidentally narrow visibility when refactoring.

## What's intentionally NOT done

- **No spill-to-disk** for hash-aggregate/hash-join/sort. v1 holds all keys in
  memory. Document this if exposed to users; consider adding a
  `RowsToDiskOnPressure` operator post-v1.
- **No whole-stage codegen**, no Janino, no LLVM. Boxed eval is plenty for v1
  and easy to reason about.
- **No multi-statement SQL.** `SqlParser.parseSelect` only accepts a SELECT.
- **No window functions** (`ROW_NUMBER`, `LAG`, `LEAD`, etc.). Common ask
  post-v1.
- **No subqueries** (scalar, IN, EXISTS, derived tables).
- **No dynamic column-value partitioning.** The multi-file output we *do*
  support is along the executor's partition axis (file-per-input-file for
  CSV; file-per-row-group for Parquet), capped by `OutputFilePath.maxPartitions`.
  Path-template partitioning per task IS supported (`DirectoryJobLoader`'s
  per-table `output.json` `partitionBy` field, or the user templating the
  path themselves) — but the partition value is fixed for the whole job
  (it's the run's executionTime), not bucketed per row by a column value
  like Spark's `partitionBy("col")`.
- **No INFORMATION_SCHEMA / catalog introspection.** Views are
  programmatically registered via `DataJob.inputs`.
- **No bytecode-level optimizations.** Hot loops use `while` and indexed
  arrays. That's enough.
- **No shared executor across DAG- and operator-level parallelism.** With N
  SQLTasks running concurrently (via `DataJob.runDag`) and each query's
  pipeline-breakers spawning their own `min(partitions, cores)` pool, peak
  thread count is `N × min(maxPartitions, cores)`. JVM handles it; cache
  thrash in worst-case CPU-bound jobs is the trade-off. Post-v1: thread a
  single shared executor through both layers.

## Test inventory

| Test target | Coverage |
|---|---|
| `core/core_types_test` | DataType, Schema, ColumnarBatch null/select, Catalog, Date/Timestamp box |
| `temporal/template_renderer_test` | Every templated variable + arithmetic edge cases |
| `read/csv/csv_row_parser_test` | State-machine edge cases (quotes, CRLF, escapes, blanks) |
| `read/csv/csv_reader_test` | Inference, null handling, explicit schema, glob, bare dir, batch splitting |
| `write/csv/csv_writer_test` | Quoting, nulls, roundtrip with reader, abort cleanup |
| `read/parquet/parquet_roundtrip_test` | All-primitive write+read, end-to-end DataJob with parquet I/O |
| `sql/exec/sql_engine_test` | 16 tests covering SELECT/WHERE/projection arithmetic, CASE, GROUP BY + COUNT/SUM/AVG/MIN/MAX, COUNT DISTINCT, INNER/LEFT JOIN, ORDER BY DESC + LIMIT, DISTINCT, HAVING, LIKE, IS NULL, scalar fns, empty-input aggregation |
| `job/data_job_test` | End-to-end CSV → SQL → CSV; templated output path; templated SQL; validation failure path; multi-task pipeline with view chaining; diamond DAG ordering; failed-task skip propagation with independent sibling success; validation-failure skip propagation; empty `sql`; setup error reporting; concurrent sibling execution; multi-partition input → multiple part files; `maxPartitions` coalesce / no-op / single-file; downstream task reads all upstream part files; **`buildDag` returns nodes/deps without I/O**; **`TaskProgressListener` fires onStart+onFinish for executed tasks and only onFinish (Skipped) for upstream-failed downstreams**; **`_SUCCESS` marker written on success, NOT on Failed or ValidationFailed**; **`RunMarker.discover` finds multi-partition layouts newest-first, exact path with no template, empty when no markers, sibling-task isolation** |
| `job/task_dag_test` | Pure dependency analyzer + DAG builder: table-name extraction, independent roots, linear chain, diamond, cycle detection, unknown reference, duplicate viewName, viewName/input collision, main-SQL self-reference, validation self-reference allowed, validation peer reference, duplicate output path, empty input, template rendering before extraction |
| `job/json_test` | The stdlib JSON parser in `job/Json.scala` — scalars, escapes, nested objects, arrays, type errors, trailing content, scalar→string coercion for the option map |
| `job/directory_job_loader_test` | End-to-end `DirectoryJobLoader.load(...)`: basic run, relative vs absolute input paths, validations dir (success + failure), templated input paths + outputDir, alphabetical chaining, default outputDir, JSON scalar→option-map coercion, error cases (no/multiple `.json`, missing `main.sql`, missing jobDir), **per-table `output.json` `partitionBy` extends output path, absent leaves path unchanged, malformed throws** |
| `gui/sql_highlighter_test` | The pure-Scala SQL tokenizer in `gui/SqlHighlighter.scala` — null/empty input, case-insensitive keywords + functions, identifier classification, integer/decimal/scientific numerics, single-quoted strings with `''` escape + unterminated, line / block / unclosed-block comments, top-level `{{ template }}` tokens, template inside a string stays a string, punctuation tagging, full SELECT query round-trips losslessly, line splitting preserves content + handles block comments spanning lines |

The other GUI components (`SqlView`, `TaskDetailsPanel`, `DagCanvas`, etc.) have
no JUnit tests — they're thin UI over engine APIs. Smoke-test by launching
`bazel-bin/examples/gui_app/gui_app_deploy.jar` and pointing it at a job dir.

## File-size hot spots

- `sql/plan/LogicalBuilder.scala` (~545 LOC) — biggest file. Pattern matches
  every JSqlParser expression node. If you're adding a syntax feature, this
  is probably where it lands.
- `job/DataJob.scala` (~440 LOC) — runner orchestration: input materialization
  pool, DAG scheduler, writeOutput, validation re-read, `_SUCCESS` marker
  write, parquet hooks.
- `gui/ResultsTabPane.scala` (~360 LOC) — partition picker + background
  output loader + run-log rendering.
- `core/ColumnarBatch.scala` (~320 LOC) — defines ten `ColumnVector`
  subclasses. Adding a new `DataType` requires a new vector + companion case
  in `ColumnVector.allocate`.
- `gui/JobSession.scala` (~305 LOC) — mutable FX-thread state for the GUI.
- `gui/DagCanvas.scala` (~300 LOC) — Canvas drawing + pan/zoom/click.
- `sql/exec/AggregateExec.scala` (~255 LOC) — adding new aggregates means
  adding an `AggState`.

## Useful pointers

- The brief: `INIT.md` at the repo root. Read it if a request is unclear about
  intended behavior.
- The reference project: `~/grid-game` — Bazel + rules_scala setup, BUILD file
  conventions, and JavaFX-on-Bazel wiring (platform classifier jars, Canvas +
  GraphicsContext rendering, mutable-state-with-manual-refresh UI pattern)
  match this repo's GUI module directly.
- JSqlParser docs: search `net.sf.jsqlparser` on Maven Central / GitHub. The
  jar at
  `/private/var/tmp/_bazel_owenchristie/.../jsqlparser-5.0.jar` can be `unzip
  -l`'d to inspect available classes — useful when guessing class names.
