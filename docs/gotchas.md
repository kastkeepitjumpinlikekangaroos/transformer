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
- **Predicate pushdown into parquet is best-effort partial.** The translator
  in `ParquetFilterTranslator` handles `c <op> lit` and its swapped form,
  `c IS NULL` / `c IS NOT NULL`, `c IN (lit, lit, …)` / `c NOT IN (…)` over
  homogeneous literal lists, `c BETWEEN lo AND hi` (because LogicalBuilder
  already lowers it to `c >= lo AND c <= hi`), and `NOT`/`AND`/`OR` over
  those. `IN` with a NULL literal in the list bails out — SQL three-valued
  logic on NULL-in-list interacts with row-group skipping in a way the
  current translator can't safely approximate; let the residual FilterExec
  handle it. Computed expressions (`a - b > 0`), two-column predicates
  (`a > b`), `LIKE`, decimal columns, and `IN` with non-literal items are
  also dropped from the AND-chain. The `FilterExec` always stays above the
  scan to catch per-row precision — stats can prove a row group doesn't
  match but can't prove it does. Don't add a "predicate is fully pushed,
  skip the FilterExec" optimization without proving the translator handles
  every conjunct shape; missing one means rows that don't match the SQL
  filter sneak through.
- **The planner may swap join build sides relative to how the SQL is
  written.** `PhysicalPlanner` consults `LogicalPlanCardinality.estimate`
  and the join kind to pick which side ends up as the build side of the
  resulting `HashJoinExec`. INNER joins with a left side significantly
  smaller than the right (`leftEst × 2 ≤ rightEst`) swap so the smaller
  side is built; RIGHT outer joins always swap (matched-build tracking
  preserves right-side rows); LEFT outer and FULL outer never swap.
  `HashJoinExec.buildRight` carries the decision; output schema and
  column order are always `left ++ right` regardless of swap. Downstream
  operators don't see the swap, but EXPLAIN-style debug code that peeks
  at a join's `buildRight` flag (test fixtures, future planner tracing)
  will notice that the build side is no longer 1:1 with how the user
  wrote `FROM a JOIN b`. See
  [architecture.md §8](architecture.md#8-hash-join-build-side-selection).
- **Non-equi joins are refused over large known inputs.** When a join's
  ON clause has no equality conjunct, `HashJoinExec` runs as a
  degenerate hash (one bucket = whole build side) — correct nested-loop
  semantics but O(N*M). `PhysicalPlanner.enforceNestedLoopGuard` throws
  `UnsupportedOperationException("non-equi join over >5000 rows
  requires equality keys (left=…, right=…)")` when both sides expose a
  `CatalogView.exactRowCount` and the smaller of the two exceeds 5000
  rows. CSV-rooted joins (no exactRowCount) keep working because the
  guard refuses to refuse without evidence. If you hit this on a
  workload that genuinely needs the cartesian, either add a
  redundant-but-cheap equality conjunct (`ON 1 = 1` won't help — it has
  no side info) or restructure the predicate so the planner can extract
  one. There's deliberately no per-task escape hatch — the threshold is
  the contract.
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
  identity. Joins prune through too: parent-needed names get split by side,
  the join condition's own ColRef indices are remapped, and the parent
  receives a combined remap covering both halves. Self-joins (both sides
  sharing column names) keep both sides' refs pessimistically — name-based
  filtering can't distinguish `l.x` from `r.x`, so we keep them all; the
  result stays correct, just with one extra column per name-collision.
  Pruning is still skipped under unions and window operators (their output
  positions interact with sibling schemas / synthetic `_winN` columns).
  Parquet pushes the projection via `parquet.read.schema`; CSV (and any
  other view leaving the default `None`) is a no-op. The shaved decode
  cost is 5–20× on wide schemas with one big unused column — the snapshots
  dataset's `data` blob is the canonical case. When the consumer references
  *zero* columns (e.g. `COUNT(1) FROM t`, `SELECT 1 FROM t LIMIT n`), the
  pushdown still projects to a single column picked by `narrowestColumn` —
  fixed-size primitives win over strings/binaries. The scan has to drive
  batches forward to feed row counts; decoding one Long instead of a
  multi-MB JSON blob makes `COUNT(1)` 20–25× faster on snapshots-shaped
  data. `ParquetReader.withProjectedColumns` reuses the parent's
  footer-derived partition layout so the pruned variant doesn't re-open
  every file. The pass guards itself with a plan-time `verify` that
  asserts every rewritten `ColRefExpr` lines up with its child's schema
  by index range and `dataType` — index bugs surface as targeted
  `IllegalStateException`s at plan time instead of `ArrayIndexOutOfBoundsException`s
  deep inside the executor.
- **Filter push-down sinks WHERE conjuncts through joins.** Before
  projection pruning, `FilterPushdown` walks the logical tree, splits any
  `LogicalFilter(LogicalJoin(...), pred)` by `JoinSideAnalysis.sideOf`,
  and pushes each conjunct under the matching child where the join kind
  allows. Inner joins push both sides; LEFT outer pushes left-only
  conjuncts (right-side ones must NOT be pushed — they'd kill the
  null-extended rows that LEFT JOIN preserves when the right side has
  no match); RIGHT outer is symmetric; FULL outer pushes nothing.
  Conjuncts touching both sides stay above. Stacked Filter(Filter(...))
  is flattened before the split, and a conjunct that lands above a deeper
  join cascades further on the recursive call. The win compounds with
  parquet predicate pushdown — a filter that sinks all the way to a
  parquet scan often triggers row-group skipping in
  `ParquetReader.withPushdownFilter`. **Do not extend the pass to push
  filters on the null-extended side of an outer join.** A `LEFT JOIN`
  with `WHERE r.x IS NULL` is a classic anti-join shape and depends on
  the post-join filter seeing null-extended rows; pushing the IS NULL
  into the right child destroys the semantics. The pass is conservative
  by construction — if you find yourself trying to push on the outer
  null-extended side, stop.
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

- **Per-thread allocation accounting is HotSpot-specific.** The
  instrumentation framework reads
  `com.sun.management.ThreadMXBean.getThreadAllocatedBytes` to surface
  `allocBytes` in `_perf.json`. That class is an Oracle / OpenJDK
  extension to the standard `java.lang.management.ThreadMXBean`. On
  Oracle / OpenJDK / Temurin (i.e. every JVM we test against) it works;
  on a hypothetical non-HotSpot JVM the `isInstanceOf` guard in
  `MetricsCollector.currentAllocatedBytes` falls back to `-1` so
  `allocBytes: -1` in `_perf.json` is the documented "unsupported" sentinel
  rather than a measurement bug. Other fields in `_perf.json` remain
  accurate. Same story for `cpuNanos`: we enable
  `setThreadCpuTimeEnabled(true)` once at class load; if the JVM refuses,
  the field is `-1`.
- **Metrics opt-in is per-task by default, with sysprop / env-var
  override.** `options.metrics = "true"` on a task's `output.json` wins
  over `transformer.metrics.enabled` / `TRANSFORMER_METRICS_ENABLED`.
  An explicit `options.metrics = "false"` overrides the global default;
  unset falls through to the global default. The override pattern
  matches what `options.spill` does — same parser, same semantics. The
  same precedence rule applies to the macro bench's
  `transformer.macro.force_spill` sysprop, which is a global override
  that flips `spillEnabled = true` on every task regardless of the
  per-task config. See [docs/benchmarking.md](benchmarking.md) for the
  full enablement matrix.
- **`eval` and `evalVec` disagree on `NaN` / `-0.0` ordering.** Numeric
  *ordering* comparisons (`< <= > >=`) take different code in the two paths:
  the row path (`Ops.cmp`) uses `java.lang.Double.compare` (a total order — `NaN`
  sorts above everything, `-0.0 < 0.0`), while the vector path
  (`VecOps.numericCompare`) uses primitive IEEE comparisons (`NaN` compares
  false everywhere, `-0.0 == 0.0`). For all finite, non-`-0.0` values they
  agree; they diverge only on `NaN` / `-0.0` operands, which a float/double
  column can carry (e.g. `x / 0.0`). The numeric `IN` path has the same shape
  (`Double.equals` in the vector HashSet vs `Ops.eq` per row). `ExprParityFuzzTest`
  deliberately does not generate float/double ordering or `IN` so the suite
  stays green; reconciling the two paths in `src/main` is a candidate follow-up.
  (Equality `= <>` agrees on these values and is covered.)

- **Mode-differential comparison is a multiset with a `Double`-only tolerance.**
  The `mode_differential_fuzz_test` asserts one `(data, query)` gives the same
  result across spill on/off, metrics on/off, and partition/batch layouts. Two
  comparison gotchas, both in `RowOracle.multisetEquals`:
  - **Multiset, not positional.** The K-way merge in `SortExec` is not stable
    (`sort_exec_test`) and the collapsing aggregate emits groups in a
    layout-dependent order, so equality is over multisets, never row positions.
    For an `ORDER BY` query the oracle additionally checks each run is *sorted*
    (the multiset check alone would not catch an unsorted result).
  - **Tolerance on `Double` columns only.** The order-dependent aggregates
    (`SUM`/`AVG`/`STDDEV`/`VAR`/`COVAR`/`CORR`) accumulate in `double`, so a
    different reduction order (a different layout or spill flush order) yields a
    result that differs by rounding — those columns compare within a small
    relative+absolute tolerance. Every other column (integer `SUM` in `Long`,
    `COUNT`, `MIN`/`MAX`, `Float` reductions, projected/group-key values) is
    bit-identical and compares exactly, so the tolerance can never mask a wrong
    group or a dropped row. `COUNT(DISTINCT …)` silently disables spill (it is
    not spillable), so the spill-on run takes the in-memory path — parity still
    holds, and the fuzzer does not special-case it.

- **The SQL frontend rejects grouping parentheses.** `SELECT (a + b) * c` and
  `WHERE (x = y)` do not bind: this JSqlParser version parses `(expr)` as a
  single-element `ParenthesedExpressionList`, and the binder only handles the
  legacy `Parenthesis` node, so every grouping paren falls through to
  "Unsupported expression". The engine's own queries avoid grouping parens and
  rely on precedence; `QueryGen` renders paren-free for the same reason. `TRIM(x)`
  is similarly unbindable (it parses to a `TrimFunction` node the binder does not
  handle, though `UPPER`/`LOWER` bind fine). Reconciling these in the binder is a
  candidate follow-up.

- **`MIN`/`MAX` over a `BooleanType` column returns NULL under spill.**
  `MinMaxState.updateAt` stores a boolean in the boxed `currentBoxed` slot (the
  `other` branch), but `writeSelf`/`readSelf` route `BooleanType` through the
  primitive `longCur` slot, so the value is lost on a spill round-trip (the
  in-memory path is correct). Found by `mode_differential_fuzz_test`; `QueryGen`
  excludes `MIN`/`MAX` over Boolean columns until the engine is fixed.

## What's intentionally NOT done

- **Spill-to-disk** is implemented for `SortExec`, `HashAggregateExec`,
  `DistinctExec`, `HashJoinExec` (grace hash, equi-join only), and
  `WindowExec` (bucketed by PARTITION BY) but **disabled by default**.
  Opt in per-task via `output.json`'s `options.spill = "true"` (with
  optional `spill_threshold_bytes` and `spill_max_runs`). See
  `plans/perf/09-spill-to-disk.md` for the full design. Caveats:
  - Non-equi joins (cartesian-shaped) ignore the spill option — they're
    rejected above [[PhysicalPlanner.NestedLoopMaxRows]] anyway.
  - `COUNT(DISTINCT …)` aggregates fall back to the in-memory path even
    when spill is enabled — the underlying HashSet state doesn't
    round-trip through a typed schema in v1, so a query mixing
    `COUNT(DISTINCT)` with other aggregates silently disables spill for
    that operator. Document the heap limit if a workload is hitting it.
  - `WindowExec` spill requires a non-empty PARTITION BY that's
    consistent across every window spec in the projection. Empty
    PARTITION BY (whole-result window) or mixed PARTITION BYs silently
    fall back to the in-memory path — there's no key set to bucket by.
  - Grace hash and window spill both use a fixed bucket count of 16
    (no recursive bucketing). Hot keys whose bucket exceeds heap will
    still OOM at probe/process time; that's a v1.1 issue.
- **No whole-stage codegen**, no Janino, no LLVM. The closest analogue is
  `Expr.evalVec` (see [architecture §5a](architecture.md#5a-vectorized-expression-evaluation-evalvec))
  — one call per Expr per batch, primitive-array inner loops, no codegen step.
  `ProjectExec` and `FilterExec` use it; everything else (sort/join/window
  per-row callbacks, aggregate state updates over `RowBuf`) still uses boxed
  `eval(batch, row)` because those are 1-row paths where vectorization gives
  nothing back.
- **No multi-statement SQL.** `SqlParser.parseSelect` only accepts a SELECT.
- **CTEs (`WITH`) are supported and non-recursive; multiply-referenced ones are
  materialized.** `LogicalBuilder` emits a placeholder `PendingCteView` scan per
  outermost-scope CTE and `CteResolver` (a pass between build and optimize)
  resolves each reference: a CTE referenced **&ge; 2 times** is executed once
  into an in-memory `MaterializedView` that every reference scans (compute-once
  + an optimization fence for non-deterministic bodies); a CTE referenced
  **&le; 1 time** is inlined, exactly as before (a declared-but-unused CTE is
  never executed). The optimizer, physical planner, and pushdowns are untouched
  — they only ever see an inlined body subtree or a `LogicalScan` over a
  `MaterializedView`. Caveats: materialization **buffers the whole body in heap**
  (no spill yet — see [plan 09](../plans/perf/09-spill-to-disk.md)); only
  **outermost-scope** CTEs are materialization candidates, so a CTE declared
  *inside* another CTE body is always inlined regardless of its reference count;
  and there is still no result caching *across* SQL statements (use `viewName`
  chaining for that). `WITH RECURSIVE` is rejected with a clear error. See
  [architecture §6b](architecture.md#6b-cte-resolution-inline-vs-materialize).
- **No subqueries** (scalar, IN, EXISTS, derived tables in FROM). A derived
  table in FROM still throws from `LogicalBuilder.fromItem`; reach for a `WITH`
  CTE where the shape allows.
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
