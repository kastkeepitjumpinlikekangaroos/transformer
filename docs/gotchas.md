# Gotchas and intentional non-features

The library/JVM/Bazel/JSqlParser footguns we've already stepped on, plus the
list of features deliberately left out of v1. Add to either list when you
ship a fix or move something from "not done" to "done".

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
  `_ => None` and fails on any agg. The same parameter pattern is used for
  window functions via `windowResolver: AnalyticExpression => Option[Expr]`.
- **`AnalyticExpression` is its own JSqlParser node, NOT a `Function`.** So
  `SUM(x) OVER (...)` won't match `case f: Function` in `containsAggregate`
  / `childExpressions`. Both helpers explicitly short-circuit on
  `AnalyticExpression` so the window's inner expressions don't leak into
  outer-scope aggregate collection.
- **`COUNT(*) OVER ()` parses with `getExpression` returning an
  `AllColumns`, but `isAllColumns` is false.** Detect star args by checking
  *both* `ae.isAllColumns` and `ae.getExpression instanceof AllColumns`
  (`LogicalBuilder.bindAnalytic` does this).
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
  ignore those (and macOS `.DS_Store`, and the `_run.json` records + per-task
  `_validation-<slug>.csv` failure samples we stamp per task — see
  [architecture.md §4](architecture.md#4-run-records-and-historical-run-discovery)).
- **JavaFX 21+ ships classes only in platform-classifier jars.** The bare
  `org.openjfx:javafx-{base,controls,graphics}` artifacts are metadata-only —
  depending on them alone gives "not found: type Stage" at compile time. The
  `gui/` BUILD lists both the bare jars *and* the `mac-aarch64` classifier
  jars. Add other platforms (`win`, Linux) the same way when building
  cross-platform binaries.
- **`TaskDag` / `TaskDagNode` are public** despite living next to runner
  internals — the GUI needs them to render structure without re-implementing
  the analyzer. Don't accidentally narrow visibility when refactoring.
- **Parquet write parallelism is capped at 4 by default**, not `cores`.
  Each in-flight `ParquetWriter` pins a 32MB row-group buffer plus per-column
  dictionary pages (≈1MB × column count) — on a 10-core box, the default
  `min(n, cores)` blew past 2GB heap on wide schemas before any data flushed.
  See `ParquetWriter.DefaultWriteParallelism` / `DefaultRowGroupSize`.
  Override per-task via `options("parquet_write_parallelism")` and
  `options("parquet_row_group_size")` if you've got headroom.
- **`SELECT COUNT(*) FROM <view>` short-circuits to footer metadata** when
  the view's `CatalogView.exactRowCount` is defined (parquet + in-memory).
  The planner emits `CountStarMetadataExec` directly; no scan happens. The
  pattern is strict — any WHERE / GROUP BY / HAVING / extra agg sends it back
  through `HashAggregateExec`. Adding new readers? Implementing
  `exactRowCount` is "free" when you already know the row count, "expensive"
  if you'd have to count rows yourself — return None and the slow path runs.
- **Column projection push-down rewrites `ColRefExpr` indices.** Before
  `PhysicalPlanner.plan`, `ColumnProjectionPushdown` walks the logical tree
  to figure out which scan columns each ancestor actually references, asks
  the view for a pruned variant via `CatalogView.withProjectedColumns`,
  and remaps every column-ref index above the new scan. The remap is local —
  Project and Aggregate emit their own schemas, so above them positions are
  identity. Pruning is skipped under joins, unions, and window operators
  (their output positions are sensitive to combined schemas and would need
  coordinated remaps across siblings). Parquet pushes the projection via
  `parquet.read.schema`; CSV (and any other view leaving the default `None`)
  is a no-op. The shaved decode cost is 5–20× on wide schemas with one big
  unused column — the snapshots dataset's `data` blob is the canonical case.
  When the consumer references *zero* columns (e.g. `COUNT(1) FROM t`,
  `SELECT 1 FROM t LIMIT n`), the pushdown still projects to a single column
  picked by `narrowestColumn` — fixed-size primitives win over strings/binaries.
  The scan has to drive batches forward to feed row counts; decoding one Long
  instead of a multi-MB JSON blob makes `COUNT(1)` 20–25× faster on
  snapshots-shaped data. `ParquetReader.withProjectedColumns` reuses the
  parent's footer-derived partition layout so the pruned variant doesn't
  re-open every file.

## What's intentionally NOT done

- **No spill-to-disk** for hash-aggregate/hash-join/sort. v1 holds all keys in
  memory. Document this if exposed to users; consider adding a
  `RowsToDiskOnPressure` operator post-v1.
- **No whole-stage codegen**, no Janino, no LLVM. Boxed eval is plenty for v1
  and easy to reason about.
- **No multi-statement SQL.** `SqlParser.parseSelect` only accepts a SELECT.
- **No subqueries** (scalar, IN, EXISTS, derived tables).
- **Window functions: ROWS frames only.** `RANGE BETWEEN` is parsed and accepted
  but executed with ROWS semantics — for `RANGE BETWEEN UNBOUNDED PRECEDING AND
  CURRENT ROW` this is correct unless the ORDER BY produces ties (where RANGE
  would include all tying rows in the current "row group"). Document this if a
  user depends on RANGE behaviour. Supported window functions: ROW_NUMBER, RANK,
  DENSE_RANK, LAG, LEAD, plus aggregates SUM/AVG/MIN/MAX/COUNT(*)/COUNT(expr)/COUNT_IF(pred).
  No FIRST_VALUE/LAST_VALUE/NTH_VALUE/PERCENT_RANK/CUME_DIST/NTILE yet.
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
