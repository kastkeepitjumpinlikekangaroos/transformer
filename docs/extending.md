# Extending the project

Step-by-step recipes for the common "add a thing" tasks. Each recipe assumes
you've skimmed [architecture.md](architecture.md) so you know where the
relevant module lives, and that you'll follow the workflow in
[testing.md](testing.md) (unit test, jaffle end-to-end, update docs).

## Add a scalar SQL function

1. Add an entry to `FunctionCatalog.Entries` in `sql/plan/FunctionCatalog.scala`:
   its name and aliases, its kind (`Scalar`, or `Flow` for one that answers with
   one of its arguments), the arities it takes, what each argument must be
   (`Param.Number`, `Param.Integer` for a count such as a scale or a position,
   `Param.Text`, `Param.Branch` for a value it may answer with, `Param.Compared`
   for one it compares, `Param.Condition` for a truth value), the rule its
   result's type follows (`Result.Fixed`, `FirstArgument`, `Branches`,
   `Rounded`, which reads a scale written as a number), what a NULL argument
   does, the arguments outside its domain that answer NULL, the errors it can
   raise while running (each also in `CleanRejection.DataErrors` and
   `ExprParity`'s owned set, [testing.md](testing.md)), whether it has a vector
   path, and the ids of the ledger's rows its rules follow (`docs/gotchas.md`,
   "Answers that differ from other engines by design"). That entry is the whole
   of its binding: `LogicalBuilder` binds every call by its entry, checks every
   argument against its `Param` (`Analyzer.checkArgument`), so the function
   never meets a value it cannot take while running, and refuses every other
   arity; a kind none of these say is a new `Param` and a new case in
   `bindArguments` or `checkArgument`, not an argument left unchecked. No name
   is matched as a string in the binder. `ExprGen` and `SqlTextGen` draw the new
   function from the catalog by its parameters, so it is fuzzed the day it
   lands (`expr_parity_fuzz_test`'s `everyCatalogFunctionIsDrawn` fails if the
   generator cannot type it).
2. Add an `apply` case in `sql/plan/Funcs.scala` with the runtime semantics, under
   each of its names. A NULL
   argument answers NULL, a scale or a position included, unless the function
   is a flow function that says otherwise. An argument outside the function's
   domain, which no number answers, answers NULL too, as a division by zero
   does: `SQRT` of less than zero, a logarithm of zero or less, `ASIN` past
   -1 or 1 (`Funcs.inDomain`), never the `NaN` or the infinity Java gives.
3. **For hot functions, also add a `Funcs.applyVec` case and a `VecFuncs`
   helper.** The vectorized path eliminates per-row Expr dispatch in
   `ProjectExec` / `FilterExec` and per-row key eval in
   `HashAggregateExec` / `HashJoinExec` / `WindowExec` for `GROUP BY f(...)`
   shapes. "Hot" = appears in jaffle / polymarket SELECT lists or WHERE
   predicates over millions of rows. Unknown / unsupported names fall
   through to the default boxed loop — correct, just slow.
4. **Add a `FuncExpr` parity case to `ExprBatchTest`** when you add a
   vectorized path. Cover no-null / mixed-null / all-null inputs and any
   special branches (variadic args, null arg propagation rules).
5. Add an end-to-end test in `src/test/scala/com/transformer/sql/exec/SqlEngineTest.scala`.
6. Add each of its names to [`README.md`'s **Scalar functions:** list](../README.md#sql-features):
   `function_catalog_test` fails until every name the catalog has is there,
   and every name there is the catalog's.
7. Regenerate the [semantics tables](testing.md#semantics-tables) (`bazel run
   //src/test/scala/com/transformer/sql/plan:semantics_tables -- --update`),
   whose function sweep runs the new entry over every kind of argument, and
   review every cell it adds, as you would review a test: each NULL its entry
   does not explain and each error it does not declare fails the test, so the
   entry's domain and errors are written down before the sweep passes.

A function that returns a `DECIMAL` computes in `BigDecimal` and fits its answer
to the type it declares with `Decimals.fit`, the way `ABS` keeps a decimal exact
(see the decimal entry in [conventions.md](conventions.md)); one that reads a
decimal's `doubleValue` declares a `DOUBLE`, as `ROUND` and `SQRT` do. A function
that is an operator by another name binds as the operator, the way
`MOD(a, b)`'s entry is of kind `Operator` and binds as `a % b` through
`Analyzer.arithmetic`, so the two cannot be typed differently. A function that
answers with one of its arguments, as `COALESCE`, `IF`, `GREATEST` and `LEAST`
do, declares those arguments `Param.Branch`, and `LogicalBuilder.bindArguments`
gives them one type through `bindBranches` (`Analyzer.branches`), which
`Result.Branches` then reads; one that compares two arguments, as `NULLIF` does,
declares them `Param.Compared`, typed with `Analyzer.unify`; and one that reads
a truth value, as `IF`'s first argument is, declares it `Param.Condition`, bound
through `Analyzer.condition`, as every condition is.

A function whose value must hold still for a whole query, as the clock keywords'
must, does not belong in `Funcs`, which evaluates per row. Give it an entry of
kind `Clock` whose `clock` builds the literal, the way `CURRENT_DATE` and
`CURRENT_TIMESTAMP` bind to the instant `build` captured for the statement
(`LogicalBuilder.clockLiteral`).

## Add an aggregate function

1. New `AggExpr*` case class in `sql/plan/Expr.scala`. Set `resultType`.
2. New `AggState` subclass in `sql/exec/AggregateExec.scala` implementing
   `update`/`merge`/`finish`.
3. **For primitive aggregates, override `AggState.updateBatch`** to read
   the aggregate's argument vector via `Expr.evalVec` and walk it with the
   column-type pattern match hoisted outside the row loop. The default
   loops the per-row `update` (correct, but pays per-row `Expr.eval`); the
   override is what wins on the no-GROUP-BY fast path in
   `HashAggregateExec`. See `LongSumState.updateBatch` for the canonical
   shape (pattern match on `LongVector` / `IntVector` / generic fallback).
4. **If the state ORDERS its input**, compare through the engine's total order,
   not raw IEEE `<` / `>`. On a primitive `double` slot that is
   `java.lang.Double.compare` (`Ops.cmp`'s order, no boxing); on a boxed slot it
   is `Ops.cmp` itself. Every comparison against `NaN` is false in both
   directions, so a raw `<` makes the answer depend on row order, partition
   fan-out and spill flush order — which is exactly what `MinMaxState` did (see
   [gotchas.md](gotchas.md)). The corpus half matters as much as the code half:
   `DataGen` and `MetaQueryGen.payloadDomain` draw `NaN` / `-0.0` / `±Infinity`
   into float columns, and a new ordering state wants a coverage guard asserting
   they reach it.
   **If it de-duplicates its input**, key the set the way GROUP BY keys a
   value: `DistinctElement.of(v, type)` wraps a byte array to compare by content
   and spells a decimal as its `KeyCodec.decimalKey`, where a raw `HashSet`
   would compare arrays by identity and decimals by scale.
   **If it returns a `DECIMAL`**, follow `DecimalSumState`: declare the type
   through `Decimals` in `resultType`, accumulate `BigDecimal`s, merge them
   exactly, and fit the answer with `Decimals.fit` in `finish`, so it cannot
   depend on row order, merge order or spill.
5. Wire it in `AggState.init` (`AggregateExec.scala`), `PlanSql`'s
   `aggregateCall` if its `name` and `args` are not how it is written, and give
   it an entry of kind `Aggregate` in `FunctionCatalog` whose `aggregate`
   builds the `AggExpr`: its parameters say how each argument is checked
   (`Param.Number` for one it adds up, `Param.Condition` for a truth value, as
   `COUNT_IF`'s, `Param.AnyValue` for any), `window = true` makes it a window
   function by name, and `distinct` and `windowDistinct` say whether
   `AGG(DISTINCT x)` binds. A one-argument aggregate honours `DISTINCT` by
   wrapping itself in `AggExprDistinct` when its builder is asked to
   (`DistinctState` replays the distinct values into the aggregate's own
   state); until its entry says `DistinctUse.Honoured`, `AGG(DISTINCT x)` is
   rejected by `UnsupportedClauses`, never bound as plain `AGG(x)`.
6. Test it.
7. Add each of its names to [`README.md`'s **Aggregates:** list](../README.md#sql-features),
   and to the **Window functions:** list if it runs as one (`function_catalog_test`
   holds both lists to the catalog), and (if the change affects window
   behaviour) the window-fn note in
   [architecture.md §6](architecture.md#6-window-functions-two-stage-logical-binding).
   Regenerate the semantics tables, whose aggregate sweep runs it over each
   type at each data set, and review its cells.

Any new `AggExpr` can run as a window aggregate too, because
`WindowExec.computeAggOverPartition` calls `AggState.init(agg)` over the
frame's rows; its entry's `window` flag is what exposes it by name.

## Add a window function

Non-aggregate window functions (ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD) live
as their own `WindowFn` ADT cases in `sql/plan/Window.scala`. To add one:

1. New `WindowFn*` case in `sql/plan/Window.scala`. Set `name`, `resultType`,
   and `respectsFrame` (false for ranking/offset functions; true only if the
   function's value changes with the frame).
2. Add an entry to `FunctionCatalog`, of kind `Ranking` with a `ranking` that
   builds the new `WindowFn`, or `Offset` with an `offset` builder (an offset
   function's value and default meet as a CASE's values, and its offset is a
   constant); `LogicalBuilder.bindAnalytic` binds it by its kind, and
   `UnsupportedClauses` reads the kind to refuse an argument to a ranking
   function or a second one to any but an offset function. Add a case in
   `PlanSql.windowCall` that writes it back.
3. Add a `case` in `WindowExec.computeFunctions` that implements the per-row
   computation (it gets the sorted partition's row indices, the original
   row buffer, and the output column to populate). **If it has a notion of
   ORDER BY peers** (a ranking function, `PERCENT_RANK`, `CUME_DIST`, `NTILE`'s
   tie rule), decide ties with `sameKey`, which compares through the same
   `Ops.cmp` the partition was sorted with — never with `Ops.eq`, which ties
   `-0.0` with `0.0` and splits two `NaN`s the sort had tied (see
   [gotchas.md](gotchas.md)).
4. Test it in `SqlEngineTest`.
5. Update the supported list in [`README.md`'s "SQL features"](../README.md#sql-features)
   and the omissions block in [`docs/gotchas.md`](gotchas.md#whats-intentionally-not-done).

Aggregate window functions (SUM/AVG/MIN/MAX/COUNT/COUNT_IF and the univariate
stats STDDEV*/VAR*) reuse the existing `AggExpr` and `AggState` machinery via
`WindowFnAgg(agg)` — see the previous section. An aggregate whose catalog entry
says `window = true` binds in a window by the same entry it binds by in a
group. Multi-arg aggregates (e.g. `COVAR`, `CORR`) store all their operand
expressions on `AggExpr.args: Seq[Expr]` so column-projection pushdown reaches
every referenced column; they are no window functions (`window = false`), since
a window function takes one argument but an offset function's.

## Add a new file format (e.g., JSON)

1. `read/json/` module with a `JsonReader extends CatalogView` and BUILD file.
2. `write/json/` with a `JsonWriter` that stages and commits, as `CsvWriter`
   does: it writes under a hidden temp name (`.<part>.<nanos>.tmp`, a name
   starting with `.`, which a clear deletes and readers skip), `finish()`
   closes it without renaming, `commit()` renames it to its part name, and
   `abort()` deletes it, staged or committed. Its `writePartitioned` finishes
   every partition, then commits them in partition order, aborts every part
   when any partition or rename fails, and returns the names it committed
   (`core.WrittenParts`): no part may appear before the whole write has
   succeeded (conventions.md).
3. Add `read/json` + `write/json` to `job/BUILD.bazel`'s deps and extend
   `InputResolver.resolve`, `OutputDirectory.write`, and
   `DataJob.materializeIfNeeded` with the new case (same pattern as parquet).
   `OutputDirectory` runs the rest of the protocol — the `Running` mark, the
   clear, the final record — for every format alike.
4. Update `InputFilePath.detectedFormat` and `OutputFilePath.detectedFormat`
   to recognize the extension.
5. Update [`README.md`'s "Supported file formats"](../README.md#supported-file-formats)
   and the module map in [`docs/architecture.md`](architecture.md#module-map).
6. Test the reader against files **some other tool wrote**, not only against
   your own writer's output. A writer and reader that share an assumption
   agree with each other however wrong it is: the parquet reader read every
   `MILLIS` timestamp 1000x too small, and no test noticed, because every
   fixture came from `ParquetWriter`, which writes `MICROS`. For parquet,
   `read/parquet:external_parquet` writes any schema with parquet-mr itself
   (see [testing.md](testing.md#test-inventory)); give a new format the same.
7. Give it two properties, with coverage guards, the way plan 06 gave CSV and
   parquet theirs (`fuzz/csv_fuzz_test`, `fuzz/parquet_fuzz_test`):
   - a **round trip**: generated data of every type, edge values included,
     written by your writer and read back by your reader with the writer's
     schema (the way `DataJob.materializeIfNeeded` re-reads a task's output),
     must come back `RowOracle.identical`; this is what found a CSV NULL row
     vanishing and a BINARY that could not be read back;
   - a **differential against something that is not your code**: an external
     writer (parquet-mr for parquet) or text the generator writes itself (for
     CSV), read by your reader and held to what the format says the bytes
     mean. A round trip alone cannot catch a misreading the writer and reader
     share. Where an external reader exists too, check that it reads the
     generated files as the generator says (`ParquetGen.Kind.rendered`
     against parquet-mr's stringifier), so the generator's reading of the spec
     is not trusted either.
   If the reader pushes predicates down, add pushdown soundness to the
   differential: the rows a predicate keeps must not change when the reader
   skips parts of the file by their statistics. Two parquet defects were in
   the order the statistics are kept in.

## Add an external fixture

Files another program wrote, vendored so the reader is held to more than its
own writer and parquet-mr (plans/testing/13): `src/test/data/parquet-testing/`
is the pattern.

1. Check the licence lets the files be copied (Apache-2.0, MIT, BSD) and copy
   them as they are into a directory of their own under `src/test/data/`,
   with the upstream licence beside them and a `README.md` giving the
   repository, the commit, what was taken and what left out, the size budget
   and how each expectation was made. Never vendor past the budget the plan
   sets; raise it in the plan first. Files another program writes for this
   repo go in a subdirectory of their own beside the script that wrote them,
   run once outside the build (`pyarrow/make_files.py`), never by a test.
2. Read each file twice, outside the repo: with the engine (`transformer
   query --table t=<file> --format jsonl ...`) and with another reader
   (pyarrow for parquet), and compare value by value, by type. Only a file
   both read alike gets a `reads` line and an expect file, the engine's CSV
   text (`--format csv`), gzipped past 8 KB.
3. Give every file one line of the directory's `MANIFEST`: `reads <rows>
   <expect>`, `refused by-design <id>` when a rule of the ledger in
   `docs/gotchas.md` refuses it (add the rule first, pinned by this manifest,
   if none fits), or `refused <text>`. A file another reader reads that the
   engine refuses with no rule behind it is a finding: keep it `refused`,
   mark it `# held: ...`, and list it in gotchas.md for the user.
4. The test (`read/parquet:parquet_testing_test`, `read/csv:csv_corpus_test`)
   reads every file under the directory, so a file with no line fails; add
   the directory's filegroup to its `data`, and the manifest to
   `slt:ledger_test`'s if a rule cites it. A case with another parser's
   reading beside it (`papaparse/NNN.json`) is `agrees`, or `differs
   by-design <id> <expect>` when a ledger rule makes the engine's reading its
   own; `csv_corpus_test`'s report mode prints the engine's reading of every
   input to make the line from.

## Add a fault point

A run that gains a new write or deletion in an output directory (a new
sidecar file, a second record) gets a fault point, so the fault matrices
reach it:

1. Name it in `core/Faults.scala` beside the others, and add it to
   `Faults.Points` in the order a run reaches it.
2. Call `Faults.at(Faults.YourPoint)` just before the change, once per file,
   never per row. Where the write is best effort (its failure is swallowed),
   put the call inside the `try`, so the fault takes the same path a failed
   write does.
3. Make `job/FaultJob.scala`'s job reach it in one run over an earlier run's
   output; `job/data_job_test`'s matrices then arm it to throw and to halt a
   child JVM, and assert it was reached. The DAG fuzzer's fault mode and its
   crash campaign draw from `Faults.Points` too, and their guard
   (`generatorCoversFaultPoints`) fails until some generated run reaches it.
4. Document it in [testing.md's "Fault points"](testing.md#fault-points-transformerfault)
   and architecture.md §3a.

## Add a config field to the directory loader

`DirectoryJobLoader` (in `job/`) is a thin DBT-style layer over the programmatic
`DataJob` API. It walks `<jobDir>/inputs/<viewName>/*.json` and
`<jobDir>/tables/<viewName>/{main.sql,validations/*.sql,output.json}` and emits
regular `InputFilePath` / `SQLTask` values — there's no separate runtime. The
JSON parser itself is in `job/Json.scala` (stdlib only — no Jackson, no circe),
beside `Json.escape`, which every JSON file the module writes goes through.

To accept a new field in an input JSON config:
1. Add the field to `InputFilePath` (if it isn't there already).
2. Read it via the `JsonObject` accessors (`requiredString`, `optString`,
   `optBool`, `optStringMap`) inside `loadInputs` in `DirectoryJobLoader.scala`.
3. Test it in `DirectoryJobLoaderTest`.
4. Update the schema in [`README.md`'s "Defining a pipeline"](../README.md#defining-a-pipeline).

For per-table config there's an optional `tables/<viewName>/output.json`
(`loadOutputConfig` reads it). Supported fields:

```json
{
  "partitionBy": "day={{today}}",
  "format": "parquet",
  "maxPartitions": 4,
  "options": {
    "compression": "snappy",
    "parquet_row_group_size": "67108864",
    "parquet_write_parallelism": "16"
  }
}
```

`partitionBy` is appended to the task's output path; the resulting templated
path goes through `TemplateRenderer` at run time, so the output lands under
`<outputDir>/<viewName>/day=YYYYMMDD/`. `format` selects the writer (`csv`
or `parquet`; defaults to `csv` when omitted). `maxPartitions` caps the
number of part files. `options` is a flat string-keyed map forwarded to the
writer — for parquet the recognized keys are `compression` (`SNAPPY` /
`GZIP` / `UNCOMPRESSED`, default `SNAPPY`), `parquet_row_group_size`
(bytes), and `parquet_write_parallelism`. CSV writer options are read by
`CsvWriteOptions.fromMap`.

To add another field, extend `loadOutputConfig` to read it and adjust the
`SQLTask` / `OutputFilePath` construction in `loadTables`. Cover both
presence and absence in `DirectoryJobLoaderTest`.

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

## Extend the GUI

`gui/` is a JavaFX library; `examples/gui_app/` is the launcher that bundles
it with sql/exec. Parquet support is built into `gui/` directly via the same
modules `job/` uses. The data viewer (`viewer/`, below) is the second GUI, and
what the two share is `gui:components`. Pattern recap:

1. **Mutable session, not Property-bound.** `JobSession` holds the truth;
   panels register a `() => Unit` listener and call `session.…` mutators on
   the FX thread. All cross-panel coordination flows through the session.
2. **Worker-thread → FX thread marshalling.** The runner thread for Run and
   the background loader for output preview both round-trip back to the FX
   thread via `FxHelpers.onFx`. Listeners passed into `DataJob.run` fire
   from runner workers — never touch FX state without marshalling.
3. **Canvas is plain `Canvas` + GraphicsContext.** No scene-graph nodes per
   DAG node; we render the whole graph in `render()` and call it imperatively
   whenever state changes. No AnimationTimer — DAG visualizers don't need
   60fps. Both graphs extend `GraphCanvas`, which owns the pan, zoom, dot grid,
   cards and edges: a subclass gives its size, paints in world coordinates and
   handles a click, so a new graph looks and moves like the others.
4. **Layout and session state are pure code.** `DagLayout` and `JobSession`
   have no JavaFX dep and build as `gui:session`, so they are testable without
   the toolkit — `gui/job_session_test`, `gui/dag_layout_test` and the fuzz
   harness's `Hydration` oracle depend on that target alone. Session state you
   add must keep it that way. World coords are zoom=1 pixels; screen coords are
   `world * zoom + pan`.
5. **The look is the theme plus `transformer.css`.** `Theme.install` makes
   AtlantaFX's Primer Dark the user-agent stylesheet, so every standard control
   is styled; the GUI's own pieces take style classes defined in
   `transformer.css` in the theme's looked-up colours (`-color-fg-muted`,
   `-color-success-subtle`, ...), and the canvas paints with `Theme.Palette`.
   A new scene, dialog or alert goes through `Theme.style`.

To add a new panel: write a JavaFX `Region` subclass that takes `JobSession`
in its ctor, registers a listener, and reads accessors. Wire it into
`GuiApp.start` (BorderPane has free slots; or nest in the SplitPane). Don't
add new state to JobSession unless multiple panels need it. Style it with
classes: AtlantaFX's (`Styles.ACCENT`, `Styles.SMALL`, `Tweaks.EDGE_TO_EDGE`)
for standard controls, and a class added to `transformer.css` for anything of
its own (`.card`, `.chip`, `.pill` and `.section-label` are there already).
SQL anywhere in it is a `SqlEditor` (or a `SqlView`, read-only with a Copy
button), never a `TextArea`, and rows are a `DataGrid` (`DataGrid.sample`
reads a view's first rows), never a bare `TableView`. A piece the data viewer
could use too goes into `gui:components` (add its file to `COMPONENTS` in
`gui/BUILD.bazel`), public and taking its inputs as arguments rather than a
`JobSession`.

To wire a new `DataJob` event into the GUI: extend `TaskProgressListener`
(non-breaking — defaulted to `NoOp`) and have `ControlsPanel`'s listener
forward to a new `JobSession.markXxx` method.

To extend SQL syntax highlighting: `SqlHighlighter.scala` is a pure-Scala,
single-pass tokenizer with no JavaFX deps. Add keywords and type names to the
sets at the top; a function needs no entry, since a word followed by `(` is
one (only functions written without parentheses, like `CURRENT_DATE`, are
listed). Cover the change in `gui/sql_highlighter_test`. Each kind's style
class comes from `SqlHighlighter.styleClass`, and its colour is a `-sql-*`
looked-up colour in `transformer.css` (GitHub's dark palette).

To add a vim command: give it an `Action` (or a `Motion`) in
`VimCommand.scala`, read its keys in `VimParser` (`normalCommand`,
`visualCommand` or `motion`, which operators share), and carry it out in
`VimEngine.execute` (`evalMotion` for a motion: say whether it is exclusive,
inclusive or linewise, and an operator gets it for free). A command that
changes the text is listed in `VimEngine.changes`, which makes it one undo
step and what `.` repeats. Add cases to `gui/vim_engine_test` whose expected
text and cursor are what `nvim --clean` (with `shiftwidth=2 expandtab`) does
from the same start, and list the command in README's vim section and
`ShortcutsDialog`.

To extend the interactive SQL console (`SqlConsolePanel`, the view chips over
a `QueryConsole`): the catalog comes
from `JobSession.buildInteractiveCatalog` — inputs are resolved via
`InputResolver.resolve`, task outputs are read by `JobSession.readOutputAsView`
under each task's `viewName`. Add a new view source by extending that method
(format dispatch already covers CSV + Parquet directly).
Results are materialized into a `MaterializedView` after each Run so Persist
can replay the same partitions into `ResultPersister.persist` — which routes
to `CsvWriter.writePartitioned` or `ParquetWriter.writePartitioned`, honouring
`OutputFilePath.maxPartitions` semantics. To add a new persist format,
extend the format switch in `ResultPersister` and the dropdown in
`PersistDialog`.

Every `QueryConsole` (the job GUI's SQL console, the viewer's query tabs) has
a Chart tab beside its rows, a `ChartPane` over a `ChartCanvas` in
`gui:components`, drawing what `gui:chart` works out:

- **A new kind of chart**: a case of `ChartType`; in `ChartData.build`,
  whether it combines rows per X (`combined`) or draws them as they are
  (`scatter`), and whether it runs along an axis (`Builder.alongAxis`); in
  `ChartCanvas`, a paint method beside `paintBars` and `paintLines`, a
  `Hover` and its case in `hit` and `tip`, and a legend swatch in
  `paintLegend`; an icon in `Icons` and its case in `ChartPane.icon`. Cover
  what it draws in `gui/chart_test` and list it in README's Charts section.
- **A new way to combine rows**: a case of `Aggregation` and of `Acc.value`
  (an `Acc` merges, so what it needs from the rows must merge too: Other is
  made by merging), and a case in `ChartDataTest.eachAggregationAsSqlHasIt`.

### Extend the data viewer

The viewer reads only run records, so what it shows about a run is what
`job.json` and `_run.json` hold. To show something new: record it in
`DataJob.writeJobRecord` (job-level, or a field of `JobTaskSummary`) or in the
`TaskRunRecord` the four `write*Record` methods stamp, read it back in
`JobRunRecord.read` / `TaskRunRecord.read` as absent for an older record, and
check it in `DataJobTest` and, for `job.json`, the `Hydration` oracle (which
builds every entry it expects, field for field). Then read it in
`viewer:model` and show it in `DetailsPanel` (an inspector's Details view) or
in the inspector's header (`InspectorPane.renderHeader`).

- **A new kind of lineage**: `Lineage.build` makes the nodes and edges. An
  edge into an input comes from `TableIndex.sourceOf`, which matches the
  input's recorded path against every table's directory; a new way for a path
  to name a table (a partition directory above a table, say) is a case there
  and a test in `viewer/LineageTest`.
- **A new view of a table**: a case of `InspectorView` (in `viewer:model`,
  with the subjects that have it in `InspectorView.of` and a case in
  `InspectorHistoryTest.theViewsOfEachSubject`), and a case in
  `InspectorPane.fill` that builds its node for one subject from the session
  as it is. The pane is built when the view is first turned to and again on
  a new subject or run, so it needs no listener of its own.
- **A new kind of tab**: `ViewerTabs` holds every tab, and is the one
  listener on `ViewerSession` for them. Selection goes through
  `ViewerTabs.inspect`, which picks the tab and sets
  `ViewerSession.select`, so the list follows the tab in front.
- **Query names**: `QueryCatalog.apply` names each table and outside input
  (`<dataset>__<table>`); `catalogFor` registers what a query reads.

## Extend `transformer query`

The command line lives in `src/main/scala/com/transformer/cli/` (architecture
§11). Each addition below lands with a case in `cli_test`, a line in
`Cli.Usage` or `QuerySession.Help` where it is a flag or a command, and the
README's [Querying files](../README.md#querying-files-transformer-query).

- **An output format**: a case object of `OutputFormat` (its `name`, and
  `setApart` when a result after another wants a blank line), in
  `OutputFormat.All`, which `--format`, `.mode` and their messages read, and a
  case in `ResultPrinter.print`. Pull the batches as they come rather than
  holding them, as every format but `markdown` does, and write a value's text
  as `CsvValues.format` does, so every format agrees on what a value is.
- **A dot command**: a case in `QuerySession.dot` and an entry in
  `QuerySession.DotCommands` (its syntax and what it does), which `.help`, its
  usage error and the shell's completion all read. A command that lists
  something prints a result (`ResultPrinter.strings`), so `.mode` formats it
  like any other.
- **A statement of the CLI's own**: a case of `SqlScript.Statement`, read off
  the front of the statement in `SqlScript.sqlStatement` with its `Lexer`
  (keep the query inside as its written text, a substring between two tokens,
  never JSqlParser's rendering), and a case in `QuerySession.execute`. Run any
  query in it through `withViews`, so it reads views, and with
  `io.cancel.options`, so Ctrl-C stops it; wrap the batches it pulls in
  `io.cancel.watch`. The engine's dialect stays as it is: what the CLI reads is
  only what wraps a query.
- **A flag**: a case in `Cli.parseQuery` (`QueryFlags` for one that takes a
  value), a field of `Command.Query`, and its use in `Cli.query`. A flag that
  names something to read registers it in the session before the script runs,
  so an error in it exits 1 before any statement does.

## Add a SQL operator (e.g., subqueries)

1. AST handling in `LogicalBuilder.fromItem` (which today handles plain
   `Table` and CTE-name references resolved from `cteScope`). For subqueries
   you'd add a case for `net.sf.jsqlparser.statement.select.PlainSelect` /
   `ParenthesedSelect` and recurse into `buildAnySelect`, then either inline
   the returned `LogicalPlan` directly or share its result. CTEs already do
   both (see [architecture §6b](architecture.md#6b-cte-resolution-inline-vs-materialize)):
   a single-use CTE is inlined, while a multiply-referenced one is run once
   into a `MaterializedView` and referenced via a `LogicalScan` over it — the
   same `MaterializedView` + `PhysicalPlanView` machinery (`CteResolver`) is
   the shared-result option to reach for, and it needs no new `LogicalPlan`
   node. Inlining a plan subtree needs no planner or optimizer changes; a new
   `LogicalPlan` node does (every exhaustive match over the sealed trait must
   learn the case, `PlanValidator` and `PlanSql` among them: the printer
   prints a node as a slot of a SELECT or as a CTE the SELECT reads, and the
   round trip in `plan_roundtrip_fuzz_test` and the slt `reprinted` mode holds
   it to binding back as itself).
2. Logical → physical mapping in `PhysicalPlanner.plan`.
3. Optionally a new physical operator (one file in `sql/exec/`).
4. Test against `SqlEngineTest`.
5. Move the feature from "intentionally NOT done" in
   [`docs/gotchas.md`](gotchas.md#whats-intentionally-not-done) to the
   supported list in [`README.md`'s "SQL features"](../README.md#sql-features).
6. **Decide whether to contribute a `LogicalPlanCardinality.estimate` case.**
   If the operator changes row count (filter / aggregate / limit / join /
   distinct) add a case that returns the new estimate. If it preserves row
   count (project / sort / window) return `estimate(child)` directly. The
   default — falling through to the pattern match — will throw a
   `MatchError`, which is the right failure mode (load-bearing planner
   decisions silently treating a new operator as `None` would be worse).
   Pin tests in `LogicalPlanCardinalityTest` to the new shape.

**Teaching the binder a new JSqlParser *expression* node is a different, and
smaller, job — but it takes two edits, not one.** Add the case to
`LogicalBuilder.bindExprWithAggs`, and add the node's operands to
`LogicalBuilder.childExpressions`. The second is easy to miss because its
catch-all is `case _ => Nil`, which fails silently: `childExpressions` drives
`containsAggregate`, the aggregate `collect`, `collectWindows` and the names
`OrderScope` checks in an `ORDER BY` expression, so a node missing from it hides
any aggregate or window function nested inside it and the query dies with
"Aggregate 'SUM' used in non-aggregating position" — an error that points at the
wrong thing entirely — and hides an ambiguous name under it from the check. Three more walks care about node shape
and may need the case too: `buildAggregation`'s `rebind` (matches a projection
to a group key by canonical text), `deriveAlias` (names the output column), and
`selectListOrdinal` (decides what counts as an `ORDER BY` / `GROUP BY`
position). Grouping parentheses needed all five; see
[gotchas.md](gotchas.md). If the node wraps a function the runtime doesn't
fully implement, reject the unsupported forms explicitly rather than binding
the operand you *can* reach — `TrimFunction` is the cautionary case there. If
the node compares or combines values, type its operands with `Analyzer.unify`
instead of casting them by hand: IN lists once had a rule of their own that cast
every item to the left side's type, and `i IN (9.5)` matched a 9. If its answer
is one of several values, type them with `Analyzer.branches`: `CASE` once took
its first value's type, so `THEN 1 ELSE 2.5` was 2.
A test enforces one more edit: the node's class needs an audit in
`UnsupportedClauses`, which `sql/plan:unsupported_clauses_test` asks for by name
as soon as `LogicalBuilder` matches on the class (see the next section). Then
delete the class's entry from `UnsupportedClauses.Constructs`, the name the
binder refused the node by, unless the binder still refuses it in some position
(a `ParenthesedSelect` is a query at the top and a refused subquery in a value).
A node the binder refuses keeps its entry, and a new JSqlParser class the binder
cannot bind needs one: the test fails until every expression, FROM item and set
operation without an audit has a name in SQL's words there, so its refusal never
names the class or renders the node.

## Honor a clause the binder rejects

`UnsupportedClauses` rejects every clause JSqlParser parses that `LogicalBuilder`
does not implement, keyed by the AST getter that carries it. To implement one:

1. Teach `LogicalBuilder` to read the getter and bind what it means. Mind every
   position the node can occur in: an `OrderByElement` is read at three sort
   sites and inside every `OVER (...)`, and the walk in `UnsupportedClauses`
   checks all of them, so a rejection you delete must be honoured in all of them.
2. Delete the getter's rejection from `UnsupportedClauses`. If the binder now
   honours only some uses — one function, one position — keep the entry, mark it
   `partial = true`, and narrow its test to the uses still unsupported (the
   `DISTINCT` entry on `Function`, which lets `COUNT` through, is the model).
   `unsupported_clauses_test` holds you to the choice: a getter `LogicalBuilder`
   reads may not carry a total rejection, and a partial rejection must sit on a
   getter it reads. It learns what the binder reads from the compiled
   `LogicalBuilder` class files, so there is no list to update by hand — but if
   binding moves into another object, add that object to the test's
   `BinderClassPrefixes`. A call counts for the class it is made through and
   every subclass of it, so read a getter `Select` declares (`getOffset`,
   `getFetch`) through a `PlainSelect` or `SetOperationList` reference. Called
   through `Select`, it would count as read on `ParenthesedSelect` too, where
   the clause stays rejected, and the test fails on the contradiction.
3. Replace the clause's rejection regression in `SqlEngineTest` with one that
   asserts the answer, and move the clause in `README.md` from the "rejected,
   not ignored" list into the supported grammar.
4. Add the clause to `SqlTextGen.HonouredTemplates`, once per position it can
   sit in, each with its clause-free twin. `oracle/NoSilentDrop` then checks it
   keeps its meaning in every context a query can sit in (a CTE body, a
   materialized CTE, a UNION arm, a counted CTE), which is where a clause read in
   one place and dropped in another shows; the guard requires the generated data
   to make it change the answer on a share of seeds, so pick positions where it
   can. Deleting the entry from `UnsupportedClauses` removes it from the
   frontend fuzzer's clause splices by itself.

A new entry in `UnsupportedClauses` becomes frontend-fuzz coverage with no change
to the fuzzer: its example is spliced into every context, and an entry on an
expression class has its fragment spliced into every position. That needs the
example written in one of the two shapes `SqlTextGen.fragmentOf` cuts — `SELECT
<one item> FROM t` or `SELECT <items> FROM t [, u] WHERE <predicate>` — and
`FrontendFuzzTest.noSilentDropRefusesEveryEntryEverywhere` fails, saying so,
when an expression-level example is in neither.

When `unsupported_clauses_test` fails after a JSqlParser upgrade, the message
names the new getter (or subclass). Decide whether it can change what a query
means: if it can, add a rejection with an example that reaches it; if it cannot
(a spelling, a structural accessor, another view of a value the binder reads),
list it as inert with the reason. Check the reason against real parser output —
`Table.getDBLinkName` looks like a clause and returns the table's own name on
every plain table.

**If the operator hashes per-row keys (group-by, set semantics, partition
keys, join keys), use `core/HashKeys.scala`'s `KeyCodec` rather than
building a `Seq[Any]` per row.** Construct it via
`KeyCodec.forColumns(indices, types)` with the column indices and types of
the key columns; for keys that are pure `ColRefExpr` use `encodeFromBatch`
(skips per-row boxing on fixed-width columns), for computed expressions
eval into a reusable `Array[Any]` and call `encodeBoxed`. The codec exposes
`decode` for re-materializing keys into output columns (used by
`HashAggregateExec`'s emit path), and `encodeFromBatchSkipIfAnyNull` for
join-probe NULL short-circuiting. See
[architecture.md §2a](architecture.md#2a-keycodec--packed-keys-for-pipeline-breakers).
A hash outside a codec — a route to a shard or a spill bucket, a `java.util`
set of values — has to call the same values equal the codec does: a byte array
by content (`Arrays.hashCode`, never the array's own `hashCode`), a decimal by
value (`HashPartitioner.decimalHash` for a route, `KeyCodec.decimalKey` for a
stored value, never `BigDecimal.hashCode`, which includes the scale).
`HashPartitioner.hashCell` and `HashJoinExec.columnHashCode` are the routing
models, `DistinctElement` the set one.

## Add an expression node

A bound expression is the sealed `Expr` trait in `sql/plan/Expr.scala`, and
several passes walk every kind of it, so a new node (as `SwitchCaseExpr` and
`BetweenExpr` were) is a case in each, or a pass meets it and fails:

1. The node itself: `dataType`, `eval(batch, row)`, and `evalVec(batch)` when
   the default per-row loop is too slow. The two must agree on every value and
   error (`fuzz/expr_parity_fuzz_test` holds them to it), and an operand needed
   on only some rows goes through `Operand.evalVec`, as `CaseExpr`'s do. A node
   that compares an operand it reads once can append that operand's vector to
   the batch (`Expr.withColumn`) and reuse an existing node over a reference to
   it.
2. The walks over bound expressions: `Expr.readsRandom`,
   `LogicalBuilder.isConstant`, `JoinSideAnalysis.sideOf` and `shiftToRight`,
   `ColumnProjectionPushdown`'s four (`colRefNames`, `rewriteExpr`,
   `joinCondNamesPerSide`, `remapJoinCondition`), `PlanValidator.checkExpr`,
   and `PlanSql.expr`, which prints it as the SQL the binder binds it from (an
   operand in parentheses where the parser would otherwise read another tree).
   A walk that only looks for a shape it can use, such as
   `ParquetFilterTranslator`, need not know the node.
3. The test harness's walks: `oracle/CleanRejection.children`,
   `ExprGen.render`, `Shrinker`'s `childrenOf` and its rebuild, `QueryGen.expr`,
   `ColumnProjectionPushdownTest`'s index check, and `oracle/PlanRoundTrip`'s
   `erase` and `PlanRoundTripFuzzTest`'s `kinds` and `children`, with the
   node's name added to `RequiredKinds` if the generators write it (else a
   case in `PlanSqlTest`'s queries).

## Add a sqllogictest script

For SQL whose answer is known: a semantics decision, a regression, a function's
edge values. Scripts live in `src/test/slt/<area>/<name>.test`; the format is in
[testing.md](testing.md#sql-logic-tests).

1. Write the setup as `statement ok` records (`CREATE TABLE`, `INSERT ...
   VALUES`, or `COPY t FROM 'file'` for a table too large to write out, the
   file a `.data` file beside the script in Postgres's text format; the runner
   carries them out, the engine has no DDL) and each query
   as a `query` record whose header names every column's exact type
   (`INT,DECIMAL(13,4)`) and a sort mode, `rowsort` unless the query's ORDER BY
   fixes the order, with an empty `----` block. A query that must fail is a
   `statement error` record with the words its message must contain after
   `----`.
2. Fill the blocks from the engine:
   `bazel run //src/test/scala/com/transformer/slt:corpus -- --dialect transformer --complete src/test/slt/<area>/<name>.test`.
   It writes each result, and lists every query that failed or whose types
   differ from the header.
3. Check every value by hand: the engine wrote them, so a wrong answer is
   now in the script. Where SQLite shares the semantics, comparing with it is
   a quick second opinion; where the repo decided otherwise, the decision in
   `plans/robustness/` is the reference.
4. `bazel test //src/test/scala/com/transformer/slt:slt_test` runs it under every
   verification mode. A `nosort` query whose ORDER BY leaves ties diverges
   across layouts: add a key that breaks them.

The scripts are picked up by glob, so no BUILD change is needed. A slice of
SQLite's corpus goes under `src/test/slt/sqlite/`, cut at a record boundary with
a header naming its source. A port of another engine's tests
(`src/test/slt/postgres/`, `src/test/slt/spark/`) keeps that engine's answers,
never the engine's own: its header names the source file and commit and counts
what it leaves out by kind, and each record names its source line and any
rewrite (only one that cannot change the meaning). Its directory holds the
source's license text and notice (`postgres/COPYRIGHT`, `spark/LICENSE` and
`spark/NOTICE`). A port's new directory joins `LedgerTest.Ports`, which holds
a port to the engine's answers in twins alone.

**Porting a Postgres test file.** Fetch Postgres at the commit the other ports
name (`postgres/COPYRIGHT`), shallow and sparse, into a scratch directory as
data, and copy the file's `src/test/regress/sql/<name>.sql` and
`expected/<name>.out` to `postgres/sql/` and `postgres/expected/` unchanged.
Write the port's header (the source, the commit, what it leaves out by kind),
then its records in the file's order, each query's header naming the engine's
types of its columns and its sort mode (`rowsort` unless the ORDER BY fixes
the order), with an empty `----` block, and each record under a comment that
starts `# <name>.sql:<line>`, the line of the statement it ports (a record of
the port's own, a table it makes for a VALUES list, takes a comment that cites
no line, as `# For int4.sql:130: ...` does). Then

```bash
bazel run //src/test/scala/com/transformer/slt:pg_expected -- --fill src/test/slt/postgres/<name>.test
```

writes Postgres's values into every block, as each column prints them, and
lists what it could not fill: a query Postgres refused, which is a `statement
error`, and a cell psql prints alike for NULL and the empty string. Without
`--fill` the binary reports every block that says other than Postgres printed,
as `slt_test` does for every port, and with `--list` it prints each of the
source's statements as psql cuts it, marked where a record holds it, which
the header's count of what is left out is made from. Where the engine answers
otherwise, the record is a skip with its twin, or held out as a finding, as
above.

Mark each record the engine answers otherwise by design with `skipif
transformer <id>` on the line before it, the id of the rule it follows in the
[ledger](gotchas.md#answers-that-differ-from-other-engines-by-design), and
after a `#` what the particular case is, where the rule does not say it. A rule
whose id starts `refused-` is a construct the engine refuses, and needs nothing
more. Any other rule says the engine answers otherwise, so follow the skip with
its twin: the same SQL under `onlyif transformer <id>`, with the engine's own
answer, which `--complete` fills and you check by hand against the rule
(compare a DOUBLE whose last digit Java need not share, from `EXP`, `LN` or a
statistic, as SQLite's `T`). `slt_test` runs every skipped record once, in the
baseline, and fails a script whose skip no longer diverges as its rule says:
then the engine has changed, and the skip (and its twin) goes, or the rule
changes with a decision. A record the engine answers otherwise with no rule in
the ledger stays out of the script as a finding until a decision settles it;
the commit that decides it adds the rule's row (for a `refused-` rule, with
the text its refusal must contain) and its pin, a record of the engine's
answer under `onlyif transformer <id>` in a script of this repo's own, then
the skips that cite it. A DOUBLE the other engine printed in full is
compared exactly; one it printed to fewer digits (Postgres's float8 under
`extra_float_digits = 0`, 15 significant digits) as SQLite's `T`, which prints
a DOUBLE to 15, since `R` prints every integer digit of a large one; and one
from a C math library function, whose last digit Java's need not share, as
`R`, to three places.

**Vendoring a file of CockroachDB's or DataFusion's.** These come in as they
are (plans/testing/12, decision 1), read through their dialect at load time,
so a later re-sync is a copy and the diff against upstream shows every line
added. Fetch the source at the tag or commit the directory's other files name
(`cockroach/NOTICE`, `datafusion/NOTICE`), shallow and sparse, into a scratch
directory as data, and never run anything in it; never take CockroachDB's
master or anything under `pkg/ccl/`. Copy the file to
`src/test/slt/<cockroach|datafusion>/<name>.test` under a header naming its
source path and commit and, where its tables have unconstrained `DECIMAL`s,
the line `# transformer: an unconstrained DECIMAL is DECIMAL(p,s)` with the
scale its values need. Then run it, carrying on past a statement that fails,
and write what did not pass:

```bash
bazel run //src/test/scala/com/transformer/slt:corpus -- src/test/slt/cockroach/<name>.test \
    --dialect cockroach --modes none --keep-going --problems /tmp/<name>.jsonl
```

Each line is a record that did not pass, with the engine's own answer. Mark
each with a condition line above its directive, and run again until none is
left, since a record held now changes what later ones read:

- a construct the engine refuses by a rule of the ledger: `skipif transformer
  <id>`, and for a rule that is no refusal (`div-zero-null`,
  `division-keeps-type`, `no-interval`, ...) the twin after the record, the same
  SQL under `onlyif transformer <id>` with the engine's answer, written as the
  format writes a result;
- what the engine or the runner lacks (a function, a clause, a type in a cast,
  a statement such as `ALTER` or `SET`, a constraint's error, the rows or the
  table a held statement makes): `onlyif cockroachdb # needs <what>`;
- an answer no rule covers, or an error both engines raise for different
  reasons: `onlyif cockroachdb # held: finding <id>, <what>`, listed in the
  plan's findings for the user until a decision settles it.

`slt_test` then runs the file under every mode; a hold or a skip that no
longer diverges fails it as stale. Count what each file runs, skips by rule and
holds by kind in its header.

**Importing a Spark test file.** Spark's `SQLQueryTestSuite` files are ported
by a converter rather than by hand, since one file can hold over a thousand
records: `SparkImport` (`//src/test/scala/com/transformer/slt:spark_import`)
reads each source's input (`inputs/<name>.sql`, for line numbers) and golden
file (`results/<name>.sql.out`, every statement's schema and output under ANSI
mode) and writes the scripts under `src/test/slt/spark/`. To add a file, add
its `Source` to `SparkImport.Sources` (the script it goes to and the sentence
its header describes it by), then run the converter over a checkout of Spark's
`sql/core/src/test/resources/sql-tests` at the commit the scripts name:

```bash
bazel run //src/test/scala/com/transformer/slt:spark_import -- \
    --sql-tests <spark>/sql/core/src/test/resources/sql-tests \
    --commit <sha> --report /tmp/spark-report.txt
```

Clone Spark into a scratch directory as data, shallow and sparse (the
`sql-tests` directories you need, `LICENSE` and `NOTICE`), and never run
anything in it. The converter runs each record on the engine in the baseline:
a record the engine answers as Spark does is kept; one it answers otherwise by
a rule of the ledger (`SparkImport.Rules`, each reading both the SQL and both
answers) is skipped under that rule, with its twin; one needing a type, a
literal or a function the engine lacks is left out and counted
(`Rewrite.lacks`); and one no rule explains, or that both engines refuse for
different reasons, is held out and listed in the report, a finding for the
user rather than a skip. The scripts are vendored, so read the report and the
diff: each held record is a question, and each new skip should read as its
rule. A new kind of divergence gets a rule only from a decision, with its
ledger row and pin. Changing the converter regenerates every script; the
commit carries the new scripts with it. A file's records keep Spark's answers,
`slt_test` runs them under every mode, and a rewrite the converter does not
already make (`SparkImport.Rewrite`) is a change to the converter, named in
each record it touches and in the script's header.

A **golden plan** pins the plan a query gets, where a planner change could
keep its answer and lose what makes it fast (a join that stops hashing, a
filter that stops reaching its scan): a `query T nosort` record of `EXPLAIN
<query>` under `src/test/slt/plan/`, filled by `--complete` like any other and
checked by hand against what the optimizer should do. It runs in the baseline
alone, since each mode makes another plan, and plain `EXPLAIN`'s sections are
logical, so the plan holds under every whole-suite configuration; `EXPLAIN
VERBOSE`'s physical section depends on the sharding settings and is no golden
plan.

## Add a property / generator

The property-based testing harness lives in `src/test/scala/com/transformer/fuzz/`
(a `testonly` `scala_library` named `fuzz`). `Rng`, `Props`, `Shrinker`, and
`RowOracle` are the reusable core; `ExprGen` + `oracle/ExprParity` are the first
property (eval-vs-`evalVec` parity). See
[testing.md](testing.md#property-based-testing-fuzz) for how it runs.

**To add a new property** over an existing generator:

1. Write a `*Test` class (so `suffixes = ["Test"]` discovers it) that depends on
   `:fuzz`. Call `Props.forAll(name, gen, shrink)(prop)`. `gen: Rng => A` builds
   the value, `shrink: A => Iterator[A]` yields strictly-smaller candidates
   (compose `Shrinker.{int,long,string,list}` or write your own), and `prop`
   throws on a counterexample (a plain `AssertionError` is fine — `Props`
   catches it, minimizes, and prints the seed).
2. Draw randomness only through `Rng` (`oneOf` / `weighted` / `between` / `bool`
   / `split`). Determinism is the contract: same seed must reproduce exactly, so
   never reach for wall-clock time or `Math.random` in a generator.

**To teach `ExprGen` a new `Expr` node** (do this whenever you add one to the
engine — the parity fuzzer then guards its `evalVec` override automatically):

1. Add a producer to the matching target dispatch — `genBoolean`, `genNumeric`,
   or `genString` (or a new branch in `genTyped` for a new result category).
   A producer is a `() => Expr` thunk that builds the node to the requested
   target `DataType` and recurses for children via the local `sub(t)`.
2. Hold the two invariants `ExprGen` documents: **type-correct** (the node's
   `dataType` must equal the requested target — `generate` asserts this) and
   **total** (never generate inputs that throw — mirror the existing exclusions
   for partial casts and NULL `ROUND`/`SUBSTRING` scale args). If your node
   genuinely composes a new partial shape, extend `oracle/ExprParity` rather
   than letting the generator throw. Totality is the *only* reason to exclude a
   shape: a shape held out because the engine diverges on it needs an engine
   fix plus a coverage guard on the way back in, not a permanent gate — see
   `generatorReachesIeeeSpecialValues` and the `NaN`/`-0.0` entry in
   [gotchas.md](gotchas.md).
3. Also add the deterministic case to
   `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala` — that suite is
   the per-override gate; the fuzzer is the generative net over it.

**To add a new `DataType` to the fuzz corpora** — do this whenever the engine
gains a type, and in this order. The four generators draw from separate pools,
so widening one is not widening the others; a type that reaches three of them
leaves the fourth's oracles blind to it, and that is how four of the ten types
went unfuzzed until plan 04.

1. **`RowOracle` first.** The oracle compares with the engine's `=`, which for
   a byte array (content) and a decimal (value, not scale) is not the JVM's
   `equals`, and a comparator that gets a type wrong makes every property over
   it vacuous or falsely red with no way to tell which. Give `normalize` the
   type's equality and `hashCode`, and add a pool for it to `row_oracle_test`'s
   `agreesWithOpsEqOnEveryNonFloatType`, which holds the oracle to `Ops.eq`.
2. **`ExprGen`.** Add it to `GenTypes` (draw from that list with `pick`, not
   `oneOf`, so a type with several declared variants such as DECIMAL is drawn
   as one type), give it `genValue` and `sentinel` cases, and route it in
   `genTyped`. Let it reach every producer the engine answers for over it and
   none it does not — the binder's rules, not convenience, decide (see the
   object comment for the four that arrived together: a DATE meets only a
   DATE, a BINARY is never text, a DECIMAL's arithmetic is built as the binder
   builds it and only where its type holds every answer). Its pool should hold
   the values its comparison is easy to get wrong on, and for a number the
   values a DOUBLE computes wrong.
3. **`DataGen` and `QueryGen`.** `DataGen.ColumnTypes` is `ExprGen.GenTypes`,
   so the type arrives with it; give it a `genDomain` case (a narrow slice, so
   keys collide, of values that cannot fail a spill the in-memory run passes)
   and a `fresh` case if its values are mutable. `QueryGen` then reaches it at
   every site that takes any column, so teach `SqlRender` its literal, its NULL
   (`CAST(NULL AS t)`, or the untyped `NULL` when the binder reads no type name
   for it), and `genLitValue` / `litSqlOfType`; where SQL cannot write a
   literal of it, keep it out of the sites that need one, as a BINARY is kept
   out of HAVING. Give `genAtom` a comparison for it (against what the binder
   allows it to meet, built from expressions whose type is exactly that:
   `QueryGen`'s target type is only a hint, so `c + 0` asked for as an INT can
   be a FLOAT), `genInList` items, and a `genValue` branch so a projected item
   can be one.
4. **`MetaQueryGen`.** Add it to `PayloadTypes` (which is `GenTypes`) and, if
   it can key a join, to `KeyTypes`, with a `payloadDomain` /
   `sharedKeyDomain` case and a `genLit` case; give `genAtom` its comparison,
   and decide whether TLP may partition on it (`isPartitionable`: a comparison
   of two non-NULL values of it must be TRUE or FALSE) and with what
   (`hasLiteral`).
5. **The guards assert the values, not just the shape.** Name the type's
   breaking pairs as a `DataGen.ValueTag` (`valueTags` reads them off a
   column, `pairTags` off two values) and extend the per-type guards in
   `ExprParityFuzzTest`, `ModeDifferentialFuzzTest` and `MetamorphicFuzzTest`:
   each asserts the type reaches every site its corpus has (a comparison, an
   `IN`, an ORDER BY and GROUP BY key, `MIN`/`MAX`, a join key, a TLP key) AND
   that the column there holds those pairs, with the measured counts in the
   comment. Then re-measure every other guard, since the new type took draws
   from the old ones.
6. **Campaign before landing.** A widened corpus finds what the narrow one
   could not reach — plan 04's found `ABS` of a decimal computing in DOUBLE and
   a NULL-typed column failing every spill — so run the metamorphic, sharded
   and mode-differential campaigns at `FUZZ_SEEDS=20000` and treat each
   finding the plan-02 way: a deterministic regression, the engine fix, and
   then the guard. Never gate the shape back out of the generator to get green.

**To add a SQL shape to `QueryGen`** (the mode-differential fuzzer's query
generator): add it to the structured `GenQuery` (a new `AggSpec` for an
aggregate, a new producer in `genValue` / `genPredicate` for a scalar shape, or a
new clause field), render it in the matching `render` / `SqlRender` branch, and
extend the `Shrinker.queryCase` moves so it still minimizes. Two invariants keep
the reject rate near zero and the property sound:

- **Total** — never generate SQL that throws at execution (mirror the existing
  exclusions: numeric->numeric / anything->string casts only, non-null
  `ROUND`/`SUBSTRING` scales). A query that binds must run, in every mode.
- **Paren-free-safe** — `SqlRender` emits no grouping parentheses and relies on
  precedence. (The binder rejected them historically; it accepts them now, so
  teaching `SqlRender` to emit them is an open widening opportunity — but until
  it does, the rule below still binds.) Only generate
  trees that stay type-correct under a precedence-only reparse: arithmetic
  operands numeric, `||` operands string, comparisons same-category (never a
  boolean operand), and `NOT` only over a boolean leaf (the frontend binds `NOT`
  tighter than comparison). Decimal arithmetic is the exception: its type
  depends on what each side is, so `SqlRender.decimalSide` gives it the
  parentheses precedence needs (only those: JSqlParser rejects some statements
  with three stacked). Confirm with the `bindRejectRateIsLow` guard before
  merging, and over a large block when the change is to a type rule: 500 seeds
  missed a reject that 20000 found.

Exclude anything non-deterministic (`RAND()`, the clock functions) or
mode-unstable (`LIMIT` and `OFFSET` without a total order — a cutoff among tied
rows keeps a different row subset depending on merge order). If a new shape needs a comparison tolerance, add it to
`RowOracle.multisetEquals` (today only `Double` columns are tolerant). A new
column type needs its equality there first; see the recipe for a new
`DataType` above.

**To add a mode to `ModeDifferential`**: add an entry to the `modes` sequence — a
`(label, MaterializedView, ExecutionOptions)` triple. Layout modes come from
`Dataset` (`singlePartition` / `evenPartitions` / `withEmptyPartitions`); option
modes flip `ExecutionOptions` flags. Keep the count modest (each entry is one
extra engine run per case) and remember the gate that is NOT a mode here:
sharded-vs-collapsing aggregation and broadcast-vs-shuffle joins are frozen at
class load (`LogicalPlanCardinality.{MinShardableSize, BroadcastBuildThreshold}`),
so they cannot be toggled within a JVM — the `sharded_mode_fuzz_test` target
exercises the sharded path instead, in a separate JVM via `jvm_flags` (see
[testing.md](testing.md#property-based-testing-fuzz)).

**To add a shape to `JobDagGen`** (the DAG-scheduler fuzzer's job generator):
the generated job is not a SQL corpus — its bodies are deliberately trivial
row-monotone `UNION ALL`s — so a new shape belongs there only if it changes what
the SCHEDULER has to decide: a new way to acquire a dependency, a new terminal
status, a new per-task config that changes which code path a worker takes. Three
invariants keep `oracle/DagSchedule` an exact oracle, and a new shape must hold
all three:

- **Acyclic by construction** — a task references only inputs and tasks with a
  lower index, so `TaskDag.build` never rejects the job and the expected status
  is one forward pass.
- **The expected outcome must be forced, not hoped for** — a `ValidationFails`
  task's check must return rows on every seed, which is why bodies never filter
  and inputs are never empty. A shape whose outcome depends on the data makes
  the oracle unsound, not merely noisy.
- **Injected failures must reach the WORKER** — `Fails` selects a column that
  does not exist, so the statement still parses and `TaskDag` recovers the same
  edges. A failure that instead trips `TaskDag.build` aborts the whole job and
  tests nothing about propagation.

Then teach three places about it together: `DagSchedule.expectedStatuses` (if it
affects status) or `DagSchedule.expectedWarnings` (if it affects the run's
warnings, as an input no task reads does), `Shrinker.jobCase` (a
strictly-reducing move that removes it), and `generatorCoversScheduleShapes` (a
tally plus an assertion that it is reached; a shape too rare for that guard's
60 seeds gets a guard of its own over more, as `generatorCoversInputFailures`
does). The measure in `shrinkerTerminatesAndReduces` must count the new field
too, or the shrinker will appear to stall on it. A draw that is not part of
the DAG comes from its own `rng.split()` after the existing ones, so every
existing seed keeps the case it had.

**To record a frontend finding** — a failure `oracle/CleanRejection` or
`oracle/SyntaxEquivalence` reports, or text the parser drops — write its plan in
`plans/robustness/` first, then name it where the property will recognize it:
a `CleanRejection.Defect` with a recognizer that reads the throwable (a
run-time failure that can only be told by its plan wants the bound plan passed
to `CleanRejection.classify` again, which `BoundPlan` walks), never the
generator's intent; a
`SyntaxEquivalence.Known` for a shape; or a `knownPlan` on an entry of
`SqlTextGen.BeyondTheTable`. Each carries a repro over
`SqlTextGen.ReferenceTables`, and the matching `FrontendFuzzTest` check fails
when the repro stops showing the defect, so the entry is removed with the fix,
the same contract as removing a generator gate. Excuse no more than the defect:
recognize it only where it is the cause, so a failure that merely resembles it
is still checked in full. Do not match on a `ClassCastException`'s or
`NullPointerException`'s message: HotSpot throws a hot implicit exception
preallocated, with none.

## Add a metamorphic relation

The metamorphic fuzzers (`metamorphic_fuzz_test`, and its sharded re-run
`sharded_mode_fuzz_test`) check multi-relation SQL against invariants decided by
SQL semantics alone — no reference engine, so they catch SEMANTIC bugs every
execution mode agrees on. `MetaQueryGen` is the generator;
`oracle/{Tlp, NoRec, MetaModeDifferential, JoinCommutativity, AggDecomposition}`
are the five live relations. See
[testing.md](testing.md#property-based-testing-fuzz).

**To add a new metamorphic relation** (a new semantic invariant over a generated
`MetaQuery`):

1. Add an `oracle/*.scala` with `check(mc: MetaCase): RelEngine.Verdict`. Run the
   query (or its variants) via `RelEngine.execute` / `collectRows`, compare with
   `RowOracle.multisetEquals`, and return `Held` / `Skipped` / `Rejected`. Return
   `Rejected(RelEngine.rejectReason(e))` for a bind/plan `IllegalArgumentException`
   so it counts toward the bind-reject rate rather than reading as a finding;
   throw `AssertionError` only on a genuine semantic violation (`Props` minimizes
   it via `Shrinker.metaCase`).
2. Add a `@Test` to `MetamorphicFuzzTest` — and, if the relation should also hold
   under sharded planning, the same `@Test` to `ShardedModeFuzzTest` — calling
   `Props.forAll(MetaQueryGen.generate, Shrinker.metaCase)`. Keep the default
   budget small (each case runs the engine several times); the campaign scales it.
3. Add a **coverage guard** next to it. A relation that silently skips every case
   — or that stops reaching the shape it was written for — is green and worthless,
   and nothing else notices. Two models, pick by cost:
   - `joinCommutativityIsNotVacuous` runs `check` over a fixed block of seeds and
     asserts a healthy `Held` share. Use it when the relation is cheap (two
     executions per case).
   - `aggDecompositionCoversEveryColumnType` asserts over the GENERATED CORPUS
     instead, via a `private[fuzz]` helper on the oracle
     (`AggDecomposition.coveredTypes`) that reports what `check` *would* exercise
     without running a query. Use it when the relation is expensive — and whenever
     the thing at risk is which VALUES or TYPES the relation reaches, not merely
     whether it ran. `MIN`/`MAX` picks its accumulator slot from the column type,
     so "every payload type still reaches a `MIN`/`MAX` decomposition" is the
     assertion that keeps a slot bug from hiding again.

   Then size the seed block from the MEASURED rate, not from what the guard
   beside it uses. A corpus-reading guard costs nothing per seed, and the chain
   it asserts can be very thin: `rankWindowsReachIeeeSpecialOrderKeys` needs a
   rank window AND a float ORDER BY key AND a `NaN` in it, which is 0.04% of
   seeds — over the 2000 its siblings read, the count is about 1 and no bound
   distinguishes rare from gone, so it reads 100000. A corpus that widens moves
   these rates too: when the column types went from six to ten, a float column
   lost draws, and that guard, which had read 20000, sat one seed over its
   bound. Re-measure every guard over the widened corpus, not only the new
   ones.

   Set the bound to separate "rare" from "gone", not to pin the current number:
   the corpus is a fixed seed block, so counts are stable, but a bound sitting one
   or two seeds under the observed rate turns any generator tweak into a failure.

**A relation over syntax** rather than execution — two spellings of one query
that must agree — is a shape in `SqlTextGen.SpellingShapes`, not a new oracle:
list the spellings (two or more; a case draws two), with `{x}` / `{y}` / `{n}` /
`{m}` / `{p}` for the parameters `spellingParams` draws, and a degenerate query
that drops what the pair is about (the filter, the limit, the NULL placement),
or `None` for a shape of spelling alone. `oracle/SyntaxEquivalence` compares the
two on column types and rows, and `syntaxEquivalenceIsNotVacuous` requires every
shape to be observable against its degenerate query on a share of seeds. A shape
that disagrees today is a defect: record it in `SyntaxEquivalence.KnownDefects`
with its plan and a repro, never by leaving the shape out.

**Three rules keep generated SQL sound and bindable** — hold them in any new
relation or generator extension:

- **Qualifier-in-name, scope-correct.** Every column reference carries its table
  qualifier inside `ColRefExpr.name` (e.g. `"t0.c0"`), and the generator only
  references columns in scope for the clause it builds (a join ON sees both
  sides; a `UNION` arm sees only its own FROM). Stay paren-free — `SqlRender`
  emits none but for decimal arithmetic, so every other generated tree must
  stay type-correct under a precedence-only reparse (see
  [gotchas.md](gotchas.md)).
- **TLP partition keys must be type-reliable.** `QueryCore.tlpCandidates`
  restricts the partition column to non-float output columns whose runtime type
  survives a precedence-only reparse. A computed `Int`-labelled column can reparse
  to `Double` (e.g. `-1.0 % c`); a `NaN` there falls into none of `p` / `NOT p` /
  `p IS NULL`, breaking the reassembly through no fault of the engine. Float and
  double keys are excluded for the same reason.
- **TLP partitions the OUTPUT, not the input.** The base is a CTE wrapper,
  `WITH [cte defs,] q AS (<body>) SELECT * FROM q [WHERE <partition>]`
  (`MetaQuery.tlpBase`), so the relation holds for aggregates, windows, unions,
  and joins with no aggregate-level decomposition (which is unsound for
  non-distributive aggregates). Derived-table subqueries in FROM are unsupported
  by the binder, so the CTE wrapper is how output-level partitioning is expressed.

## Add spill to an existing operator

`SortExec`, `HashAggregateExec`, `DistinctExec`, and `HashJoinExec` are
spill-capable. To add spill to another breaker (e.g., a future
`WindowExec` spill in v1.1):

1. **Accept `opts: ExecutionOptions = ExecutionOptions.Default`** as the
   last field on the operator's `case class`. Update
   `PhysicalPlanner.plan(...)` to pass through `opts` when constructing
   the operator.
2. **Compute `spillEnabled` once** — `opts.spillEnabled` plus any
   operator-specific capability gate (e.g.,
   `AggStateSerde.allSpillable(aggs)` in `HashAggregateExec`).
3. **Compute the threshold lazily** via
   `Spill.effectiveThresholdBytes(opts, Scheduler.parallelism)`.
4. **Use `Spill.openOperatorDir("op-name")`** to allocate the per-call
   temp directory. Wrap the emit iterator with a wrapper that calls
   `OperatorSpillDir.close()` when exhausted; the JVM shutdown hook in
   `Spill` catches abandoned iterators.
5. **Accumulate `Spill.estimateBytes(batch)`** per input batch; flush
   the current state to a parquet file under `spillDir` when over
   threshold, then reset the in-memory state. Cap total runs at
   `opts.spillMaxRuns` with a clear error message.
6. **Fold spill files back at emit time.** The pattern from
   `AggSpiller.foldCodecSpillFiles` works for any in-memory map:
   `ParquetReader.fromPath(file.toString)`, iterate batches, decode
   each row, merge into the live state.
7. **Add a `*SpillTest`** asserting bit-equal output between
   `spillEnabled=false` and `spillEnabled=true` with a 1-byte
   threshold. Cover empty inputs, single-row inputs, and a
   `spill_max_runs` overflow.

For state with novel encoding (think `WindowExec`'s per-partition
buffered rows), follow the `AggStateSerde` pattern: add a `writeSelf` /
`readSelf` pair to whatever serializable state the operator carries,
then a thin dispatcher object that knows how to read it back given the
operator's plan-time metadata.

Operators that *can't* meaningfully spill (state doesn't round-trip
through a typed schema, or partitioning logic is incompatible) should
silently fall back to the in-memory path when spill is requested
rather than throwing — exposes the limitation to documentation, not
to users at run time. See `HashAggregateExec.spillEnabled`'s
`AggStateSerde.allSpillable` guard for the pattern.

## Add a counter to an operator

Per-operator counters surface in `_perf.json` and are read by the macro
bench, the perf-tagged regression test, and (eventually) the GUI. Adding
one is mechanical but every step matters — a counter that allocates on
the hot loop or drifts the names array out of sync will trip
`DisabledOverheadBench` or `OperatorCountersTest`. Procedure:

1. **Declare the constant.** In the operator's companion object, add
   `final val IdxYourCounter: Int = <highest existing Idx + 1>`. The
   constant is `final val` so the JIT can const-fold the array offset
   in the inner loop.
2. **Extend the names array.** Add `"yourCounter"` to the operator's
   `IdxCounterNames: Array[String]` (or, for `ExchangeExec`, the
   `counterNamesFor(numShards)` helper) at the position matching the
   index. The name lands verbatim as a JSON key in `_perf.json`.
3. **Increment in the operator.** Use the existing pattern:
   ```scala
   if (metricsNode != null) metricsNode.counters(IdxYourCounter).add(delta)
   ```
   Allocation-free, one branch, one `LongAdder.add` call. Don't read
   strings or build collections on the per-row hot path.
4. **Extend the index-drift test.** In
   `src/test/scala/com/transformer/core/metrics/OperatorCountersTest.scala`,
   bump the corresponding `IdxCounterNames.length must equal highest
   Idx + 1` assertion's right-hand side to point at your new constant.
   This catches the case where someone bumps the index but forgets the
   names array (or vice versa).
5. **Add a populated-counter test case.** Either extend an existing
   `<operatorName>ExecPopulatesInMemoryCounters` test or add a new
   `@Test` method. Build a small fixture, drain the operator with a
   freshly-constructed `MetricsNode`, snapshot, assert the counter
   incremented to the expected value.
6. **Update the counter inventory in `docs/benchmarking.md` §2.** Add a
   one-line description of the new counter so consumers know what it
   measures.

If the counter is on the spill code path only (zero when spill never
fires), document that explicitly — readers of `_perf.json` need to know
whether "0" means "the path didn't trigger" vs "the path triggered but
nothing happened".

## Add a logical-plan optimizer pass

`LogicalOptimizer.optimize` (in `sql/plan/`) runs each pass once in a fixed
order — `FilterPushdown`, `JoinOrder`, then `ColumnProjectionPushdown` today.
`JoinOrder` is the model for a pass that moves columns: it returns where each
column went and re-indexes the expressions above up to the node that makes new
columns. To add a new pass:

1. New file `sql/plan/<MyPass>.scala` exposing a single
   `def apply(plan: LogicalPlan): LogicalPlan`. The body is a recursive
   `rewrite` matching every `LogicalPlan` case — a missed case is a compile
   error ("match may not be exhaustive"), as every compiler warning is
   ([conventions](conventions.md)). Pass-through nodes (Project, Sort, Limit,
   Distinct, Union, Window, …) just recurse on their children; the
   interesting work happens on the shape your pass targets.
2. **Don't drift `ColRefExpr` indices without remapping.** Any rewrite
   that prunes columns, reorders output, or inserts a new producer must
   update every ancestor's `ColRefExpr` indices accordingly. Use the
   `(plan, remap)` pattern that `ColumnProjectionPushdown` uses — the
   caller composes the child's remap with its own. For join-level
   helpers (`sideOf`, `shiftToRight`), pull from `JoinSideAnalysis.scala`
   so you're not duplicating the recursive Expr traversal. To join
   conjuncts back into one predicate use `JoinSideAnalysis.conjunction`, a
   balanced tree: a left fold would rebuild a long AND chain the parser
   regrouped, a level a conjunct, past the depth every walk is safe at (see
   `SqlParser.MaxNesting` in [gotchas.md](gotchas.md)).
3. **Wire into `LogicalOptimizer`** with a method that runs the pass and
   hands its output to `PlanValidator.afterPass(name, before, after)`, and
   a call in `optimize`. The validator checks every `ColRefExpr`'s
   `(index, dataType)` against the relevant child schema, conditions,
   operand types and union arms, and that the pass kept the plan's output,
   so an index/remap bug fails at plan time naming your pass. Order
   matters; place your pass where its preconditions hold and document
   *why* it must run before/after existing passes in the optimize body.
   Don't introduce a generic rule engine — explicit calls keep the
   ordering inspectable.
4. **Test the new pass directly.** Add `src/test/scala/com/transformer/sql/plan/<MyPass>Test.scala`
   with a `scala_junit_test` entry — pattern-match on the rewritten plan
   shape so the test fails loudly when an index-remap regresses, and
   include at least one round-trip through `SqlEngineTest` to confirm
   end-to-end query correctness is unchanged.
5. Update [`docs/architecture.md`'s "Logical plan optimization"](architecture.md#9-logical-plan-optimization)
   to describe the new pass and where it sits in the order.

## Add cloud support (v1.1 work)

The interfaces are already in place:

- `InputFilePath.isCloud` detects `gs://` / `s3://` prefixes.
- `InputResolver.resolve` raises `UnsupportedOperationException` for cloud
  paths today. Replace this with a download-into-cache step that writes to
  `./.transformer-cache/<sha1(path)>/` and then delegates to the local
  resolver.
- Same for `DataJob.writeOutput` on the output side: upload the locally-
  written file after successful close.
- Add `read/cloud/` with GCS + S3 helpers, and list it in `job/BUILD.bazel`'s
  deps so the resolver can call into it directly (same pattern as parquet).

Don't pull in `google-cloud-storage` or `aws-sdk-v2` in modules that don't
need them. Cloud is opt-in.
