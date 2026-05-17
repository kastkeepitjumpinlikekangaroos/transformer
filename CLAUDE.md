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
| `job/` | User-facing API + runner. | `DataJob.scala`, `InputFilePath.scala`, `OutputFilePath.scala`, `SQLTask.scala`, `JobResult.scala`, `InputResolver.scala` (+ `ParquetResolverHook`, `ParquetReaderHook`, `ParquetWriterHook`) |
| `examples/scala_app/` | Sample app built as a `scala_binary` deploy jar. | `src/main/scala/com/example/ExampleJob.scala` |

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

### 4. Expression evaluation

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

# Build the example deploy jar.
bazel build //examples/scala_app:example_job_deploy.jar
java -jar bazel-bin/examples/scala_app/example_job_deploy.jar \
    examples/scala_app/data/input /tmp/transformer-example-out
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
- **No partitioned output** other than templated paths. If a user writes to
  `out/day={{ today }}/file.csv`, the date is fixed for the whole job — we
  don't dynamic-partition on a column value.
- **No INFORMATION_SCHEMA / catalog introspection.** Views are
  programmatically registered via `DataJob.inputs`.
- **No bytecode-level optimizations.** Hot loops use `while` and indexed
  arrays. That's enough.

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
| `job/data_job_test` | End-to-end CSV → SQL → CSV, templated output path, templated SQL, validation failure path, multi-task pipeline with view chaining |

## File-size hot spots

- `sql/plan/LogicalBuilder.scala` — biggest file. Pattern matches every
  JSqlParser expression node. If you're adding a syntax feature, this is
  probably where it lands.
- `sql/exec/AggregateExec.scala` — second biggest. Adding new aggregates means
  adding an `AggState`.
- `core/ColumnarBatch.scala` — defines ten `ColumnVector` subclasses. Adding
  a new `DataType` requires a new vector + companion case in `ColumnVector.allocate`.

## Useful pointers

- The brief: `INIT.md` at the repo root. Read it if a request is unclear about
  intended behavior.
- The reference project: `~/grid-game` — Bazel + rules_scala setup, BUILD file
  conventions match this repo's.
- JSqlParser docs: search `net.sf.jsqlparser` on Maven Central / GitHub. The
  jar at
  `/private/var/tmp/_bazel_owenchristie/.../jsqlparser-5.0.jar` can be `unzip
  -l`'d to inspect available classes — useful when guessing class names.
