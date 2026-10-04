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

A function whose value must hold still for a whole query, as the clock keywords'
must, does not belong in `Funcs`, which evaluates per row. Bind it to a literal
instead, the way `LogicalBuilder.clockLiteral` binds `CURRENT_DATE` and
`CURRENT_TIMESTAMP` to the instant `build` captured for the statement.

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
5. Wire it in `AggState.init` (`AggregateExec.scala`) and
   `LogicalBuilder.bindAgg` (`sql/plan/LogicalBuilder.scala`). A one-argument
   aggregate gets `DISTINCT` by wrapping it in `AggExprDistinct` in `bindAgg`
   (`DistinctState` replays the distinct values into the aggregate's own state)
   and adding its name to `UnsupportedClauses.DistinctAggregates`; until then
   `AGG(DISTINCT x)` is rejected, never bound as plain `AGG(x)`.
6. Test it.
7. Update [`README.md`'s "SQL features"](../README.md#sql-features) and (if
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
6. Test the reader against files **some other tool wrote**, not only against
   your own writer's output. A writer and reader that share an assumption
   agree with each other however wrong it is: the parquet reader read every
   `MILLIS` timestamp 1000x too small, and no test noticed, because every
   fixture came from `ParquetWriter`, which writes `MICROS`. For parquet,
   `read/parquet:external_parquet` writes any schema with parquet-mr itself
   (see [testing.md](testing.md#test-inventory)); give a new format the same.

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
   learn the case).
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
`containsAggregate`, the aggregate `collect`, and `collectWindows`, so a node
missing from it hides any aggregate or window function nested inside it and the
query dies with "Aggregate 'SUM' used in non-aggregating position" — an error
that points at the wrong thing entirely. Three more walks care about node shape
and may need the case too: `buildAggregation`'s `rebind` (matches a projection
to a group key by canonical text), `deriveAlias` (names the output column), and
`selectListOrdinal` (decides what counts as an `ORDER BY` / `GROUP BY`
position). Grouping parentheses needed all five; see
[gotchas.md](gotchas.md). If the node wraps a function the runtime doesn't
fully implement, reject the unsupported forms explicitly rather than binding
the operand you *can* reach — `TrimFunction` is the cautionary case there. If
the node compares or combines values, type its operands with `Analyzer.unify`
instead of casting them by hand: IN lists once had a rule of their own that cast
every item to the left side's type, and `i IN (9.5)` matched a 9.
A test enforces one more edit: the node's class needs an audit in
`UnsupportedClauses`, which `sql/plan:unsupported_clauses_test` asks for by name
as soon as `LogicalBuilder` matches on the class (see the next section).

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

**To give `ExprGen` a new `DataType`**: add it to `GenTypes` (draw from that list
with `pick`, not `oneOf`, so a type with several declared variants such as
DECIMAL is drawn as one type), give it `genValue` and `sentinel` cases, and route
it in `genTyped`. Let it reach every producer the engine answers for over it and
none it does not — the binder's rules, not convenience, decide (see the object
comment for the four that arrived together: a DATE meets only a DATE, a BINARY is
never text, nothing does arithmetic on a DECIMAL). Its value pool should hold the
values its comparison is easy to get wrong on, and `typeCoverage` plus a guard in
`ExprParityFuzzTest` must assert both that the type reaches a comparison and an
`IN`, and that those values meet there.

**To give `DataGen` and `QueryGen` a new `DataType`**: `DataGen.ColumnTypes` is
`ExprGen.GenTypes`, so the type arrives with it; give it a `genDomain` case
(a narrow slice, so keys collide, of values that cannot fail a spill the
in-memory run passes) and a `fresh` case if its values are mutable. `QueryGen`
then reaches it at every site that takes any column, so teach `SqlRender` its
literal, its NULL (`CAST(NULL AS t)`, or the untyped `NULL` when the binder reads
no type name for it), and `genLitValue` / `litSqlOfType`; where SQL cannot write
a literal of it, keep it out of the sites that need one, as a BINARY is kept out
of HAVING. Give `genAtom` a comparison for it (against what the binder allows
it to meet, built from expressions whose type is exactly that: `QueryGen`'s
target type is only a hint, so `c + 0` asked for as an INT can be a FLOAT) and
`genInList` items, and `genValue` a branch so a projected item can be one. Name
its breaking pairs as a `DataGen.ValueTag` so the per-type guards in
`ModeDifferentialFuzzTest` can assert them, not only the shape.

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
  tighter than comparison). Confirm with the `bindRejectRateIsLow` guard before
  merging.

Exclude anything non-deterministic (`RAND()`, the clock functions) or
mode-unstable (`LIMIT` and `OFFSET` without a total order — a cutoff among tied
rows keeps a different row subset depending on merge order). If a new shape needs a comparison tolerance, add it to
`RowOracle.multisetEquals` (today only `Double` columns are tolerant). A new
column type needs its equality in `RowOracle.normalize` first: the oracle
compares with the engine's `=`, which for a byte array (content) and a decimal
(value, not scale) is not the JVM's `equals`, and a comparator that gets it
wrong makes every property over the type vacuous or falsely red. Add a pool for
the type to `row_oracle_test`'s `agreesWithOpsEqOnEveryNonFloatType`, which
holds the oracle to `Ops.eq`.

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
affects status), `Shrinker.jobCase` (a strictly-reducing move that removes it),
and `generatorCoversScheduleShapes` (a tally plus an assertion that it is
reached). The measure in `shrinkerTerminatesAndReduces` must count the new field
too, or the shrinker will appear to stall on it.

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
   rank window AND a float ORDER BY key AND a `NaN` in it, which is 0.06% of
   seeds — over the 2000 its siblings read, the count is 1 and no bound
   distinguishes rare from gone, so it reads 20000.

   Set the bound to separate "rare" from "gone", not to pin the current number:
   the corpus is a fixed seed block, so counts are stable, but a bound sitting one
   or two seeds under the observed rate turns any generator tweak into a failure.

**Three rules keep generated SQL sound and bindable** — hold them in any new
relation or generator extension:

- **Qualifier-in-name, scope-correct.** Every column reference carries its table
  qualifier inside `ColRefExpr.name` (e.g. `"t0.c0"`), and the generator only
  references columns in scope for the clause it builds (a join ON sees both
  sides; a `UNION` arm sees only its own FROM). Stay paren-free — `SqlRender`
  emits none, so every generated tree must stay type-correct under a
  precedence-only reparse (see [gotchas.md](gotchas.md)).
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
