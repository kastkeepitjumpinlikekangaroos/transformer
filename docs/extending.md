# Extending the project

Step-by-step recipes for the common "add a thing" tasks. Each recipe assumes
you've skimmed [architecture.md](architecture.md) so you know where the
relevant module lives, and that you'll follow the workflow in
[testing.md](testing.md) (unit test, jaffle end-to-end, update docs).

## Add a scalar SQL function

1. Add it to `Funcs.returnType` in `sql/plan/Funcs.scala` so the analyzer
   knows the return type.
2. Add an `apply` case in the same file with the runtime semantics.
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
6. Update [`README.md`'s "SQL features"](../README.md#sql-features) so users
   can see the new function in the supported list.

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
4. Wire it in `AggState.init` (`AggregateExec.scala`) and
   `LogicalBuilder.bindAgg` (`sql/plan/LogicalBuilder.scala`).
5. Test it.
6. Update [`README.md`'s "SQL features"](../README.md#sql-features) and (if
   the change affects window behaviour) the window-fn note in
   [architecture.md §6](architecture.md#6-window-functions-two-stage-logical-binding).

Any new `AggExpr` is automatically usable as a window aggregate too, because
`WindowExec.computeAggOverPartition` calls `AggState.init(agg)` over the
frame's rows. To expose it as a window function by name, also add a case to
`LogicalBuilder.bindAnalytic`'s `fnName match { ... }`.

## Add a window function

Non-aggregate window functions (ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD) live
as their own `WindowFn` ADT cases in `sql/plan/Window.scala`. To add one:

1. New `WindowFn*` case in `sql/plan/Window.scala`. Set `name`, `resultType`,
   and `respectsFrame` (false for ranking/offset functions; true only if the
   function's value changes with the frame).
2. Add a `case` in `LogicalBuilder.bindAnalytic` that recognises the function
   name and returns the new `WindowFn`.
3. Add a `case` in `WindowExec.computeFunctions` that implements the per-row
   computation (it gets the sorted partition's row indices, the original
   row buffer, and the output column to populate).
4. Test it in `SqlEngineTest`.
5. Update the supported list in [`README.md`'s "SQL features"](../README.md#sql-features)
   and the omissions block in [`docs/gotchas.md`](gotchas.md#whats-intentionally-not-done).

Aggregate window functions (SUM/AVG/MIN/MAX/COUNT/COUNT_IF and the univariate
stats STDDEV*/VAR*) reuse the existing `AggExpr` and `AggState` machinery via
`WindowFnAgg(agg)` — see the previous section. New `AggExpr`s become available
as window aggregates automatically once they're wired into both
`LogicalBuilder.bindAgg` (group context) and `LogicalBuilder.bindAnalytic`
(window context). Multi-arg aggregates (e.g. `COVAR`, `CORR`) store all their
operand expressions on `AggExpr.args: Seq[Expr]` so column-projection
pushdown reaches every referenced column.

## Add a new file format (e.g., JSON)

1. `read/json/` module with a `JsonReader extends CatalogView` and BUILD file.
2. `write/json/` with a `JsonWriter` (atomic temp + rename pattern).
3. Add `read/json` + `write/json` to `job/BUILD.bazel`'s deps and extend
   `InputResolver.resolve`, `DataJob.writeOutput`, and
   `DataJob.materializeIfNeeded` with the new case (same pattern as parquet).
4. Update `InputFilePath.detectedFormat` and `OutputFilePath.detectedFormat`
   to recognize the extension.
5. Update [`README.md`'s "Supported file formats"](../README.md#supported-file-formats)
   and the module map in [`docs/architecture.md`](architecture.md#module-map).

## Add a config field to the directory loader

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
4. Update the schema in [`README.md`'s "Defining jobs from a directory"](../README.md#defining-jobs-from-a-directory).

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
modules `job/` uses. Pattern recap:

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

To extend the interactive SQL console (`SqlConsolePanel`): the catalog comes
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

## Add a SQL operator (e.g., subqueries)

1. AST handling in `LogicalBuilder.fromItem` (which currently only handles
   plain `Table`). For subqueries you'd add a case for
   `net.sf.jsqlparser.statement.select.PlainSelect` and recurse into
   `buildSelect`, then wrap as a synthetic `CatalogView` over a materialized
   batch list or a new `LogicalSubquery` node.
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

## Add a logical-plan optimizer pass

`LogicalOptimizer.optimize` (in `sql/plan/`) runs each pass once in a fixed
order — `FilterPushdown` then `ColumnProjectionPushdown` today. To add a new
pass:

1. New file `sql/plan/<MyPass>.scala` exposing a single
   `def apply(plan: LogicalPlan): LogicalPlan`. The body is a recursive
   `rewrite` matching every `LogicalPlan` case — Scala's exhaustivity
   check will flag missed cases. Pass-through nodes (Project, Sort, Limit,
   Distinct, Union, Window, …) just recurse on their children; the
   interesting work happens on the shape your pass targets.
2. **Don't drift `ColRefExpr` indices without remapping.** Any rewrite
   that prunes columns, reorders output, or inserts a new producer must
   update every ancestor's `ColRefExpr` indices accordingly. Use the
   `(plan, remap)` pattern that `ColumnProjectionPushdown` uses — the
   caller composes the child's remap with its own. For join-level
   helpers (`sideOf`, `shiftToRight`), pull from `JoinSideAnalysis.scala`
   so you're not duplicating the recursive Expr traversal.
3. **Validate at plan time.** If your pass can introduce an index/remap
   bug that wouldn't surface until execution, add a `verify` helper
   modeled on `ColumnProjectionPushdown.verify`: walk the rewritten tree
   and assert every `ColRefExpr`'s `(index, dataType)` matches the
   relevant child schema. Gate behind a `private val VerifyRewrites:
   Boolean = true` if perf ever becomes a concern (cheap at plan time —
   probably stays always-on).
4. **Wire into `LogicalOptimizer.optimize`** by adding a line. Order
   matters; place your pass where its preconditions hold and document
   *why* it must run before/after existing passes in the optimize body.
   Don't introduce a generic rule engine — explicit calls keep the
   ordering inspectable.
5. **Test the new pass directly.** Add `src/test/scala/com/transformer/sql/plan/<MyPass>Test.scala`
   with a `scala_junit_test` entry — pattern-match on the rewritten plan
   shape so the test fails loudly when an index-remap regresses, and
   include at least one round-trip through `SqlEngineTest` to confirm
   end-to-end query correctness is unchanged.
6. Update [`docs/architecture.md`'s "Logical plan optimization"](architecture.md#9-logical-plan-optimization)
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
