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
- **JSqlParser parses `(a - b)` as a `ParenthesedExpressionList`, not a
  parenthesized binary expression.** `LogicalBuilder.bindExpr` does NOT handle
  `ExpressionList`/`ParenthesedExpressionList` and dies with `Unsupported
  expression: ParenthesedExpressionList`. Workaround: drop the parens — `a - b
  AS c` parses fine and the operator-precedence vs. `AS` is unambiguous. If
  parens are needed for grouping (rare in SELECT items), bind the inner
  expression first into a sub-task. The polymarket example's `stg_orderbook`
  was an early casualty of this.
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
  the shared `ParquetSchema` converter; `job/` depends on both. Neither
  parquet module depends on `job/`, so the cycle stays open.
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
- **Hadoop-common pre-3.4.3 breaks on JDK 24+.** `UserGroupInformation.getCurrentUser()`
  in 3.3.x/3.4.0/.1/.2 calls `Subject.getSubject(AccessControlContext)`, which
  throws `UnsupportedOperationException` once JEP 486 (Security Manager
  permanently disabled) lands — default in JDK 24+, hard-fails in JDK 25.
  3.4.3 switches to `SubjectUtil.current()` via HADOOP-18583. Every parquet
  read/write goes through `Path.getFileSystem` → `FileSystem.Cache.Key` → UGI,
  so the whole parquet path blows up on older Hadoop + new JDKs. If you need
  to pin Hadoop lower than 3.4.3, hold the runtime to JDK 23.
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
- **Parquet write parallelism is heap-bounded, not `cores`-bounded.**
  Each in-flight `ParquetWriter` pins a 32MB row-group buffer plus per-column
  dictionary pages (≈1MB × column count). `defaultWriteParallelism = min(cores,
  maxHeap / 256MB)` — on a 2GB heap that's 8; on a 12GB heap (the GUI default
  via `-XX:MaxRAMPercentage=75.0` on a 16GB box) it's `min(cores, 48)`.
  Override per-task via `options("parquet_write_parallelism")` and
  `options("parquet_row_group_size")` if the schema is narrow enough that you
  can push harder.
- **Default parquet write compression is `snappy`** (matches Spark / pyarrow
  / DuckDB). The intuition "snappy is just CPU overhead, intermediate parquet
  on SSD is fine uncompressed" is wrong for high-cardinality string columns:
  dictionary encoding (always on) makes the dictionary pages dominate the
  on-disk bytes, and snappy compresses those 30-50% — net write time goes
  DOWN under snappy on snapshots-shaped data (`market_id`-style 66-char hex
  strings repeated millions of times). Benchmark from one ~5M-row write:
  uncompressed 9.1s/86MB vs snappy 7.3s/54MB. For genuinely narrow-numeric
  tables (orderbook prices + timestamps) override per-table via
  `output.json`'s `options.compression = "uncompressed"`; the legacy alias
  `NONE` is accepted for symmetry with Spark.
- **Parquet decode is vectorized**, not row-at-a-time. `ParquetPartitionIterator`
  uses `ColumnReadStoreImpl` to expose one `ColumnReader` per column per row
  group and copies values straight into `ColumnarBatch` primitive vectors —
  no `Group`/`SimpleGroup` allocations, no `Integer`/`Long` boxing per cell.
  If you add a new `DataType`, extend `decodeColumn`'s outer match on
  `PrimitiveTypeName` (and `BatchWriteSupport.write` on the encode side);
  forgetting either side leaves a `MatchError` at runtime, not a compile
  failure.
- **Predicate pushdown into parquet is best-effort partial.** Only
  `c <op> lit` shapes (and `NOT`/`AND`/`OR` over them) translate; everything
  else is dropped from the AND-chain. The `FilterExec` always stays above
  the scan to catch per-row precision — stats can prove a row group doesn't
  match but can't prove it does. Don't add a "predicate is fully pushed,
  skip the FilterExec" optimization without proving the translator handles
  every conjunct shape; missing one means rows that don't match the SQL
  filter sneak through.
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
- **Don't do I/O on the FX thread during a run.** Every parallel call in this
  library funnels through `Scheduler.pool` (a `2 × cores`-sized `ForkJoinPool`
  by default; configurable via `transformer.scheduler.parallelism`). When
  a job is running, those workers are tied up scanning + writing partitions.
  An FX-thread caller that submits to the pool via
  `Scheduler.submitAndAwaitAll` (e.g. `ParquetReader.fromPath` reading footers
  for a schema chip) will block on `.get()` waiting for a worker — and since
  the FX thread is NOT a `ForkJoinWorkerThread`, FJP compensation doesn't
  apply. The whole GUI freezes for as long as the pool is busy. The pattern
  this came from was `SqlConsolePanel.refreshViewsListing` rebuilding the
  catalog (which opens every input + output to read schemas) on every
  `notifyListeners` fire — 60+ rebuilds per run, each blocking FX. Fix: guard
  with `if (session.isRunning) return` so listener-driven catalog rebuilds
  wait until `endRun` fires its single post-run `notifyListeners`. Same rule
  applies to any future panel that wants to do I/O off a session listener —
  either gate on `isRunning` or spawn a background thread and marshal results
  back via `FxHelpers.onFx`.

## What's intentionally NOT done

- **No spill-to-disk** for hash-aggregate/hash-join/sort. v1 holds all keys in
  memory. Document this if exposed to users; consider adding a
  `RowsToDiskOnPressure` operator post-v1.
- **No whole-stage codegen**, no Janino, no LLVM. The closest analogue is
  `Expr.evalVec` (see [architecture §5a](architecture.md#5a-vectorized-expression-evaluation-evalvec))
  — one call per Expr per batch, primitive-array inner loops, no codegen step.
  `ProjectExec` and `FilterExec` use it; everything else (sort/join/window
  per-row callbacks, aggregate state updates over `RowBuf`) still uses boxed
  `eval(batch, row)` because those are 1-row paths where vectorization gives
  nothing back.
- **No multi-statement SQL.** `SqlParser.parseSelect` only accepts a SELECT.
- **No subqueries** (scalar, IN, EXISTS, derived tables).
- **Window functions: ROWS frames only.** `RANGE BETWEEN` is parsed and accepted
  but executed with ROWS semantics — for `RANGE BETWEEN UNBOUNDED PRECEDING AND
  CURRENT ROW` this is correct unless the ORDER BY produces ties (where RANGE
  would include all tying rows in the current "row group"). Document this if a
  user depends on RANGE behaviour. Supported window functions: ROW_NUMBER, RANK,
  DENSE_RANK, LAG, LEAD, plus aggregates SUM/AVG/MIN/MAX/COUNT(*)/COUNT(expr)/COUNT_IF(pred)
  and the univariate stats STDDEV/STDDEV_SAMP/STDDEV_POP/VARIANCE/VAR_SAMP/VAR_POP.
  No FIRST_VALUE/LAST_VALUE/NTH_VALUE/PERCENT_RANK/CUME_DIST/NTILE yet, and
  COVAR_SAMP/COVAR_POP/CORR are GROUP BY only (no OVER form yet — JSqlParser's
  `AnalyticExpression` exposes a single argument slot).
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
- **Heap is not fully exercised under the default config.** Each in-flight
  parquet writer pins ~32MB row group buffer + a few MB of column dictionaries;
  with the default fan-out of `min(cores, heap/256MB)` only ~`cores × 50MB` of
  heap is in flight at any moment. For huge-heap boxes you can bump the row
  group cap via `options("parquet_row_group_size")` (per-task in `output.json`),
  but there's no automatic "use all the heap" mode yet.
