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
  parameter list, not an empty parameter list — and `Function.isAllColumns` is
  not the star.** JSqlParser sets `isAllColumns` for the `ALL` quantifier:
  `COUNT(ALL a)` arrives with `isAllColumns = true` and `a` in its parameters,
  while `COUNT(*)` leaves it false. `ALL` is the default quantifier, so the binder
  never reads the flag (`UnsupportedClauses` lists it as inert) and finds the
  star only as an `AllColumns` parameter. It used to read the flag as the star,
  which made `COUNT(ALL a)` count NULLs.
- **JSqlParser parses `(a - b)` as a `ParenthesedExpressionList`, not a
  parenthesized binary expression.** There is no parenthesis node in the 5.0 AST
  at all: the deprecated `Parenthesis` class still ships but nothing in the jar
  constructs it, so `(expr)` reaches the binder as a one-element list and
  `(a, b)` as a two-element one. Anything in `LogicalBuilder` that walks the AST
  therefore has to see through the one-element case or a paren the user wrote
  for grouping changes what the query means. `LogicalBuilder.Grouped` is that
  one rule, and every walk matches on it — see "grouping parentheses" below for
  the five sites and what happens if you add a sixth walk without it. A list of
  two or more elements is a row-value constructor (`(a, b) = (c, d)`), which the
  binder does not support and rejects by node name.
- **Aggregate inside HAVING / ORDER BY** must be rebound against the aggregate
  output, not the source schema. `LogicalBuilder.bindExprWithAggs` takes an
  `aggResolver: Function => Option[Expr]` that returns a `ColRefExpr` into the
  aggregate output for known aggregates. The plain `bindExpr` passes
  `_ => None` and fails on any agg. "Known" means collected first:
  `buildAggregation` collects aggregates from the SELECT list, HAVING **and
  ORDER BY**. ORDER BY was missing from that list, so an aggregate named only
  there (`SELECT g ... GROUP BY g ORDER BY SUM(a)`) had no slot and binding died
  with a `NoSuchElementException`. The same parameter pattern is used for
  window functions via `windowResolver: AnalyticExpression => Option[Expr]`.
- **A `HAVING` decides the path, not just the filter.** `buildSelect`
  takes the aggregate path when there is a `GROUP BY`, an aggregate in the
  SELECT list, **or a `HAVING`**, and only that path reads `getHaving`. The third
  term was missing, so `SELECT 1 AS x FROM t HAVING COUNT(*) > 10` took the plain
  path and returned every row with the HAVING silently dropped — a getter read in
  one context and ignored in another, which the reflection test cannot see (see
  the plans/robustness/01 entry below). With no `GROUP BY` the aggregate has no
  keys, so it is one group even over an empty input, and a column outside an
  aggregate fails to resolve.
- **`AnalyticExpression` is its own JSqlParser node, NOT a `Function`.** So
  `SUM(x) OVER (...)` won't match `case f: Function` in `containsAggregate`
  / `childExpressions`. Both helpers explicitly short-circuit on
  `AnalyticExpression` so the window's inner expressions don't leak into
  outer-scope aggregate collection.
- **`COUNT(*) OVER ()` parses with `getExpression` returning an
  `AllColumns`, and `isAllColumns` false.** As on `Function` (above),
  `AnalyticExpression.isAllColumns` is the `ALL` quantifier, so
  `LogicalBuilder.bindAnalytic` finds the star as `getExpression instanceof
  AllColumns` alone and never reads the flag.
- **JSqlParser attaches a UNION's trailing `ORDER BY` / `LIMIT` / `OFFSET` /
  `FETCH` inconsistently.** When the tail starts with `ORDER BY` it lands on the
  `SetOperationList`; without one, `LIMIT` and `OFFSET` land on the last arm's
  `PlainSelect`; `FETCH` always lands on the list, so `OFFSET 1 ROWS FETCH NEXT 2
  ROWS ONLY` splits — the OFFSET on the arm, the FETCH on the list. In standard SQL
  every one of them applies to the whole union, so
  `LogicalBuilder.buildSetOperationList` hoists ORDER BY, LIMIT and OFFSET off a
  bare last arm unless the list already carries them. A parenthesized last arm
  keeps its own (it parses as a `ParenthesedSelect`, which the hoist skips), and
  so does a `TOP n`, which is part of its arm's SELECT and limits that arm alone.
- **`Fetch.getRowCount` throws unless the count is an integer literal.** It
  unboxes the `LongValue` behind `getExpression`, so `FETCH FIRST ROW ONLY` (no
  count), `FETCH FIRST ? ROWS ONLY` and `FETCH FIRST -1 ROWS ONLY` all throw a
  `NullPointerException` from it. The binder reads `getExpression` (a missing
  count means one) and the audit lists `getRowCount` as inert, so the rejection
  walk never calls it either.
- **A DATE boxes to `LocalDate` and a TIMESTAMP to `Instant`, on both
  evaluation paths.** That is what `DateVector.getBoxed` and
  `TimestampVector.getBoxed` return, so every row-path value of those types must
  be the same class, or `Ops.eq` calls it unequal to a column's value and
  `Ops.cmp` throws. `Casts.cast` returned an epoch-day `Int` for DATE, which the
  vector path absorbed (`setBoxed` takes an `Int`) and `ExprBatchTest`'s
  `normalize` hid; `{ts '...'}` bound to a `LocalDateTime`, which
  `TimestampVector.setBoxed` rejects. Both box canonically now, and
  `castToDateAndTimestampBoxLikeTheVector` compares the classes, not just the
  normalized values.
- **Values of different types meet only as numbers, or through a string
  literal.** `Analyzer.unify` types every set of operands that meet: a
  comparison, arithmetic and a `CASE` switch (through `promotePair`, its
  two-operand case), a `BETWEEN`'s three, and an IN list. Numbers widen to the
  widest among them, a string literal beside another type is read as that type
  at bind time through `Casts`, and every other mix is an error, naming the CAST
  to write where one would say what is meant. The rule it replaced coerced the
  other side of any comparison with a string to text, so `9 < '10'` was false
  (`'9'` sorts after `'1'`), `1.0 = '1'` was false, and `2026-01-01T05:30:21Z`
  met `'2026-01-01 06:00:00'` character by character, its `T` meeting a space at
  index 10. IN lists had a rule of their own that cast every item to the left
  side's type, so `i IN (9.5)` matched a 9, and so did `i IN (4294967305)`, by
  overflow. Promoting a DATE beside a TIMESTAMP to its midnight, as some engines
  do, would make `d = ts` match only midnight, and a boolean beside a number
  would have to mean 1 and 0, which is why those pairs need a CAST too. The same
  strictness runs through `CAST`: a string literal under one is folded at bind
  time, text that is no number fails as `Cannot cast 'x' to int` rather than
  Java's `NumberFormatException`, BOOLEAN reads only `true` and `false` (Java's
  `parseBoolean` read every other text as false, and `WHERE NOT s` returned
  every row), `Analyzer.checkCast` refuses numbers and booleans to or from
  either temporal type, and a DATE reads only an ISO date.
  `Analyzer.checkArithmetic` makes arithmetic on anything but a number a bind
  error, unary minus included, where it used to throw `IllegalStateException`
  mid-drain. A `DECIMAL` is the widest number, so the rest widen to it through
  `Casts`: an integer exactly, a `FLOAT` or `DOUBLE` as the decimal it prints as
  (`Casts.toDecimal`; `0.1` is `0.1`, not the binary fraction nearest it), and
  `NaN` or an infinity is an error naming it. That cast used to be missing, so
  `price = 1` threw `CAST to decimal(10,3) not implemented` mid-drain. A
  `BINARY` casts to nothing else, explicitly or implicitly (`checkCast`, and
  `implicitCast` beneath a WHERE, LIKE or CASE), and `Analyzer.checkText` keeps
  `||` and the string functions off one: each read the array's identity hash as
  text. When a new type meets these rules, extend them; do not loosen them
  (see the strictness entry in [conventions.md](conventions.md)).
- **The clock keywords are a `TimeKeyExpression`, spelled as written.**
  `current_date`, `CURRENT_DATE()` and DB2's `CURRENT DATE` arrive as three
  different strings, and `CURRENT_DATE ()`, with a space, arrives as a
  zero-argument `Function` instead. `UnsupportedClauses.clockKeyword` folds the
  spellings for the audit and the binder alike, and the binder sends the call
  form to the same place. That place is `LogicalBuilder.clockLiteral`, not
  `Funcs`: `build` captures one instant per statement and both keywords bind to
  a `LitExpr` of it, which is what keeps them equal across rows, partitions and
  CTE references. A `Funcs` entry would read the clock per row and stamp a large
  result with many instants. The instant is truncated to microseconds, the unit
  a `TimestampVector` holds, so the literal also equals a stored copy of itself
  on the row path.
- **Parquet `MessageType` constructor takes `java.util.List[Type]`, but a
  `Vector[PrimitiveType].asJava` gives `java.util.List[PrimitiveType]`.**
  Scala won't unify because of Java type invariance. `ParquetSchema.toMessageType`
  maps `_.asInstanceOf[Type]` first.
- **No circular module deps.** `read/parquet` and `write/parquet` do not
  depend on each other, and `job/` depends on both. Each holds its own half of
  the type mapping on purpose: `ParquetSchema` is what transformer writes, and
  `ParquetColumns` reads whatever the file says, which for a file another tool
  wrote is often something `ParquetSchema` would never produce (see "A
  parquet scan reads each file's columns as that file stores them" below).
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
  To read a new kind of column, add a `ParquetColumns.Decoding` case, the
  annotation that selects it in `ParquetColumns.decoding`, and its loop in
  `decodeColumn`; to write a new `DataType`, extend `ParquetSchema.toMessageType`
  and `BatchWriteSupport.write`. The decode match is over a sealed type, so a
  missing loop is a compiler warning; a missing write case is a runtime
  `UnsupportedOperationException`.
- **A parquet scan reads each file's columns as that file stores them.**
  `ParquetPartitionIterator` asks parquet-mr for the view's columns with
  `setRequestedSchema`, and parquet-mr decodes each column chunk as whatever
  type the request names, not as the file stores it. The request used to be
  rebuilt from the view's engine types with the writer's mapping
  (`ParquetSchema.toMessageType`), so a projected scan — any query that reads
  some of a file's columns — decoded an `INT96` timestamp as `INT64` (dates
  thousands of years off) and threw on any `FIXED_LEN_BYTE_ARRAY` column it
  requested as `BINARY`, while scans of the whole file, which skipped the
  request, were fine. And with no request at all, a whole-file scan decoded
  columns by position, so a glob whose second file put two `INT32` columns in
  the other order swapped their values silently. Every file written by
  transformer's own writer hid both: they store exactly what the mapping
  says, in the first file's order. `ParquetColumns.resolve` now builds the
  request from the file's own column types, matched to the view's by name,
  and `parquet_external_format_test` pins each case against files parquet-mr
  wrote.
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
  also dropped from the AND-chain, and so is a timestamp literal: it arrives
  as an `Instant` (`CURRENT_TIMESTAMP`), and a parquet TIMESTAMP's unit varies
  by file while a legacy INT96 one is no INT64 at all, so a skip decided in
  microseconds could over-prune. A date literal arrives as a `LocalDate`
  (`CURRENT_DATE`, `DATE '...'`, `{d '...'}`, or a string literal compared with a
  date, all read at bind time) and is pushed as INT32 epoch days, which every
  parquet DATE is. A string literal beside a number is read as that number at
  bind time too, so `i = '9'` pushes exactly as `i = 9` does. The `FilterExec`
  always stays above the scan to catch per-row precision — stats can prove a
  row group doesn't match but can't prove it does. Don't add a "predicate is
  fully pushed, skip the FilterExec" optimization without proving the
  translator handles every conjunct shape; missing one means rows that don't
  match the SQL filter sneak through.
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
- **Spill files use positional column names, never the logical schema's.**
  Every spill-capable breaker (`HashJoinExec`, `HashAggregateExec`, `SortExec`,
  `DistinctExec`, `WindowExec`) writes its working set to temp parquet and reads
  it back by column INDEX. Parquet addresses columns by name-path, so a spilled
  schema with two equal field names — a join output like `[k, v, k, v]`, or
  name-colliding GROUP BY keys (`GROUP BY t0.k, t1.k` → `[k, k, ...]`) — used to
  write a file the reader couldn't disambiguate: the second column's pages came
  back null and `ParquetPartitionIterator.loadNextGroup` NPE'd dereferencing a
  null `DataPage`. This crashed on the repro below (`spill_threshold_bytes = 1`)
  and was the bug the metamorphic fuzzer gated around:

  ```
  a(k,v) = {(1,1)},  b(k,v) = {(1,9)}
  SELECT t2.v FROM a t0 JOIN b t1 ON t0.k = t1.k JOIN b t2 ON t1.k = t2.k
  ```

  Fixed by writing spill files through `Spill.positionalSchema` (`_c0`, `_c1`,
  ...); `AggSpiller.spillSchema` renames the group-key columns to `_k0`, `_k1`,
  ... too. **If you add a spill write site, wrap its schema the same way** — the
  read paths are all index-only, so names carry nothing and duplicates break the
  round-trip. This is spill-only: real (non-spill) `ParquetWriter` output keeps
  its real column names. Regression coverage is a duplicate-named spill case in
  each operator's `*SpillTest`, the end-to-end repro in `SqlEngineTest`, and the
  metamorphic + sharded fuzzers, which now run the spill mode on join queries
  (`MetaModeDifferential` no longer skips `hasAnyJoin`).
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
  Parquet pushes the projection via `setRequestedSchema`; CSV (and any
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

- **Sharded execution at K>1 used to hard-deadlock the shared pool — FIXED
  for engine execution by bottom-up exchange pre-materialization
  (plans/bugfixes/02f); sharding stays off by default; never `managedBlock`
  these waits; never drain lazily-nested exchanges from pool tasks.** (Hazard
  surfaced by the metamorphic fuzzer; investigated in plans/bugfixes/02a,
  which refuted it at small fuzz budgets; a compensation "fix" attempted in
  02b was reverted; the exchange monitor was removed in 02d — whose pre-change
  baseline campaign then REPRODUCED the hang, see plans/bugfixes/02e; 02f then
  removed the wait the cycle needs.) Sharding is off by default
  (`MinShardableSize = Long.MaxValue`, `BroadcastBuildThreshold = 1M`), so nothing
  users run reaches this. With sharding forced on, every breaker (`ExchangeExec`,
  `HashJoinExec`, `DistinctExec`, `HashAggregateExec`) materialises by blocking on
  the shared bounded `Scheduler.pool` while awaiting per-partition sub-tasks, and
  sharded plans nest breakers deeply (exchange over join over exchange over
  distinct ...). The historical "14 workers parked on two exchange monitors, 0%
  CPU" reading was first refuted on JDK 21 at default/1500-seed fuzz budgets,
  then confirmed REAL at 20000-seed campaign volume (2026-07-23: permanent
  wedge ~3 minutes in, 0% CPU, identical wait set across thread dumps 90s
  apart). Three mechanisms:
  - **Monitor across a pool-blocking call — the throughput cliff, FIXED
    (plans/bugfixes/02d).** `ExchangeExec.ensureMaterialized` used to be
    `synchronized`, holding the instance monitor across its pool-driven
    `materialize()`: one consumer held the monitor + blocked inside
    `materialize()` while the rest blocked *entering* the monitor (invisible to
    the pool) — serialising concurrent shard-readers of one exchange, and a
    standing violation of the "no JVM monitor across a `Scheduler.pool`-blocking
    call" convention. It is now a CAS claim + published `CountDownLatch`: exactly
    one winner runs `materialize()` with no monitor held, losers park on a plain
    **uncompensated** `ready.await()` and wake together on `countDown`, and a
    materialization failure latches and re-throws (wrapped) to every caller,
    never retried. The loser-wait is deliberately NOT `managedBlock` — that was
    02b's regression.
  - **Nested blocking on a bounded pool — rescued by work-helping, for
    TREES.** `Scheduler.submitAndAwaitAll` waits via `ForkJoinTask.get()`. From a
    pool worker `.get()` does not merely park — it work-*helps*, stealing and
    running the very sub-tasks it awaits, descending the breaker tree depth-first
    on one worker's stack, so a single worker alone materialises an arbitrarily
    deep *tree* of exchanges. Workers that block on I/O (e.g. parquet spill)
    rather than on a pool task ARE compensated by the FJP. The external top-level
    drain thread parks without consuming a pool worker.
  - **`helpJoin` breaks stack discipline — the real deadlock (FIXED by 02f:
    engine plans no longer contain the wait the cycle needs).** Helping
    is not restricted to descendants of the awaited task: `ForkJoinTask.get()`'s
    helpJoin path runs tasks from stealers' queues wholesale. In the captured
    wedge, an exchange's materializing winner awaited one of its shard tasks on
    a worker whose own nested `.get()` (a join build fan-out inside the shard
    task) had inlined a *consumer* of that same exchange — a sibling probe task
    of the join above it — beneath the shard-task frame; the guest then waited
    for the exchange. Cycle: winner → shard task → inlined consumer → exchange →
    winner. The cycle is monitor-agnostic: under the old `synchronized` DCL the
    guest blocks entering the monitor, under 02d's CAS+latch it parks on the
    `ready` latch — either way the winner can never finish. This is why 02a's
    "acyclic plan topology ⇒ no cycle" argument fails: helping can interleave
    two plan paths on one stack, so lock/latch acquisition does not follow the
    plan topology. (Descendant helping participates too: a winner help-running
    its own shard task can walk into a *nested* exchange and park there as a
    loser — the post-02d probe dump shows both edge types feeding one wedge.)
    The same inlining can also land the consumer on the
    winner's OWN stack — there the old reentrant monitor silently
    double-materialized (live), and a naive CAS+latch self-deadlocks against
    its own latch (this bit 02d's first cut: the default-seed fuzzer, green
    for months, timed out). `ensureMaterialized` therefore detects
    winner-reentrancy via a `claimer` thread check and materializes a private
    unpublished copy (duplicate work, live). The cross-thread variant admits
    no such point escape (a cross-exchange pair of hosts evades any
    "am I above a producer of THIS exchange" check), which is why the fix
    removes the wait instead: every deadlock cycle must pass through an
    exchange-readiness wait — the one wait that does not follow the
    task-creation tree — and `PhysicalPlanner.preMaterializeExchanges`
    publishes every exchange bottom-up at the drain choke points
    (`SqlEngine`'s two execute paths, `CteResolver.materialize`) before any
    consumer task exists, so no worker ever performs that wait
    (plans/bugfixes/02f).

  Investigation (plan 02a; JDK 21, 8-core): the sharded fuzzer runs GREEN at
  multi-shard K for small budgets — the `shard_count=4` pin, default K
  (= `Scheduler.parallelism` = 16), and the worst realistic regime
  (`parallelism=2`, `shard_count=8`, 1500 seeds of joins / self-joins /
  CTEs-referenced-twice / windows). Direct probes — a 6-deep sharded plan
  drained by K concurrent *external* threads, and a diamond where two breakers
  share one `ExchangeExec` instance — also complete, and the same nested
  fan-out with a NON-helping wait (`CountDownLatch.await()` in place of
  `.get()`) deadlocks a 2-thread pool immediately, so `.get()` work-helping is
  load-bearing. Those measurements stand, but the conclusion drawn from them
  ("no hard deadlock for the shapes we generate") was a sampling artifact:
  the 02d pre-change baseline campaign (`sharded_mode_fuzz_campaign`,
  `FUZZ_SEEDS=20000`, K=4, default pool) wedged permanently ~3 minutes in —
  the helpJoin cycle above needs campaign-scale volume to hit the right steal
  interleaving. Full dump analysis and repro: plans/bugfixes/02e.

  **A compensation "fix" (02b) was attempted and reverted.** 02b tried to (a) wrap
  every pool wait in a `ForkJoinPool.ManagedBlocker` and (b) replace the exchange
  monitor with a CAS-claim + `CountDownLatch`, on the theory that managed blocking
  is safer insurance than the work-helping implementation detail. In practice it
  REGRESSED K>1 from green (4.3s) into a hard hang: under deep K-shard nesting the
  `managedBlock` compensation spawned a spare-thread storm that wedged the pool (a
  timeout thread dump showed every worker idle in `awaitWork` while the external
  submitter parked on an un-run task). Bisection (plans/bugfixes/02c): reverting
  only the managed-blocking waits — back to plain `.get()` / `latch.await()` —
  restores green, isolating `managedBlock` as the sole culprit. 02b was reverted in
  full to reach a known-good baseline; the monitor-free exchange half (B) was later
  re-landed alone as plans/bugfixes/02d, with the loser-wait a plain uncompensated
  `ready.await()`. Lesson: for THIS workload work-helping is not merely adequate,
  it is *better* than compensation — `managedBlock` turns a bounded nested fan-out
  into unbounded thread creation.

  Net: 02d's CAS+latch removed the monitor-serialisation cliff (losers wake
  together on `countDown`; no JVM monitor is held across a pool wait anywhere
  in the tree); 02f removed the deadlock itself for engine execution by making
  the exchange-readiness wait unreachable — the pre-materialization pass runs
  at both drain choke points, and plans with zero exchanges (the shipping
  default) walk and find nothing. The 20000-seed K=4 campaign that wedged
  HEAD in ~1-3 minutes completes under 02f. Guards:
  `//src/test/scala/com/transformer/core:scheduler_test` pins the tree-shaped
  work-helping mechanism (the same depth-5/fan-4 fan-out completes on a
  2-thread pool via `submitAndAwaitAll`'s `.get()` and wedges through a
  non-helping `CountDownLatch`); `exchange_exec_test` pins exactly-once
  materialization + failure propagation under racing readers plus the pass
  itself (diamond-shared exchange publishes once; `MeteredPlan` wrappers are
  transparent); `sql/exec:exchange_deadlock_stress_test` re-drives the exact
  captured wedge topology through the pass under heavy steal churn (green
  ~2s in the default suite; `STRESS_LAZY=1` skips the pass and reproduces the
  historical wedge in seconds as a manual red instrument). RESIDUAL: the lazy
  `ensureMaterialized` fallback still exists for directly-constructed
  exchanges, and draining lazily-NESTED exchanges from pool tasks without the
  pass can still wedge — don't do that; run
  `PhysicalPlanner.preMaterializeExchanges` first like the engine does. The
  general rules stand: never hold a JVM monitor across a
  `Scheduler.pool`-blocking call (`ExchangeExec.ensureMaterialized` is the
  in-tree exemplar of the compliant pattern), and do not reach for
  `ForkJoinPool.managedBlock` as a blanket wrapper on pool-task waits (02b's
  mistake — it wedges the pool even faster).
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
- **`eval` and `evalVec` used to disagree on `NaN` / `-0.0` — fixed, and the
  two operator families deliberately follow different rules.** Numeric
  *ordering* (`< <= > >=`) and `IN`-list membership each took different code in
  the two paths and diverged, in opposite directions, on exactly the operands a
  float/double column can carry but the finite generators never produced
  (`0.0 / 0.0`, `-1.0 * 0.0`):

  - **Ordering.** The row path (`Ops.cmp`) uses `java.lang.Double.compare` — a
    total order, `NaN` above everything and equal to itself, `-0.0 < 0.0` —
    while `VecOps.numericCompare` compared raw IEEE (`NaN` false everywhere,
    `-0.0 == 0.0`). The vector path now goes through `Double.compare` too. The
    direction was forced: `Ops.cmp` also backs the sort comparators, and IEEE
    comparison is not a total order, so it cannot satisfy the `Comparator`
    contract. `Double.compare` takes primitive `double`s, so this costs no
    boxing. `Float` has no branch of its own — `VecOps.readDouble` widens it
    exactly as `Number.doubleValue` does on the row side.
  - **`IN`-list membership.** The row path calls `Ops.eq` (primitive `==`:
    `NaN` matches nothing, `-0.0 == 0.0`), while `InListExpr.evalVec`'s
    literal-hoisting fast path boxed into a `HashSet[java.lang.Double]` and so
    inherited `Double.equals` — bit-pattern equality, the opposite on *both*
    counts. The set now keys through `InListExpr.setKey`, which collapses
    `-0.0` to `0.0`, and a `NaN` probe skips the set entirely. Skipping (rather
    than answering FALSE) is what keeps three-valued logic intact: a `NaN` that
    matches nothing still falls into the NULL-item branch, so
    `NaN IN (1.0, NULL)` is NULL, not FALSE.

  So equality and ordering answer differently on the same operands by design —
  `NaN = NaN` is FALSE while `NaN <= NaN` is TRUE — because ordering has a
  total-order obligation equality does not. **This diverges from Postgres**,
  whose `float8` treats `NaN` as equal to itself and `-0.0` as distinct from
  `0.0` in *both* families. We match Postgres on ordering (it too sorts `NaN`
  highest) and not on `=` / `IN`. The alternative — making `IN` follow
  `Double.equals` — would have split `x IN (y)` from `x = y`, which is a
  contradiction inside the engine rather than a difference from another one; an
  internal contradiction is the worse of the two, so the engine's own `Ops.eq`
  won.

  `ExprParityFuzzTest` used to hold out three things at once: float/double
  ordering, float/double `IN`, *and* every `NaN`/`Inf`/`-0.0` value (normalized
  to `0.0` in `ExprGen.genValue`). All three are gone, and
  `generatorReachesIeeeSpecialValues` asserts both the shapes and the values are
  reached — removing the shape gates alone would have left the fuzzer green over
  a corpus that could not break them.

- **`MIN`/`MAX` over a `NaN`-carrying float column used to be order-dependent —
  fixed.** `MinMaxState` kept a primitive `doubleCur` accumulator for
  `Float`/`Double` and advanced it with raw IEEE `v < doubleCur` /
  `v > doubleCur`, in `updateAt`, `updateBatchAt` *and* `merge`
  (`AggregateExec.scala`). Every `NaN` comparison is false, so the result
  depended on where the `NaN` landed: seen **first** it became the accumulator
  and stuck forever (nothing could beat it), seen **later** it was skipped
  entirely. `MAX(x)` over `[NaN, 5.0]` was `NaN`; over `[5.0, NaN]` it was
  `5.0`. Because `merge` had the same shape, the answer also moved with
  partition fan-out and spill flush order, not just row order within a batch.

  All three sites now advance through `java.lang.Double.compare`, the total
  order the class's own reference-type branch already used (`Ops.cmp`), and the
  one `ORDER BY` (`SortExec.rowOrdering`) and `GREATEST`/`LEAST`
  (`Funcs.pickByOrdering`) follow. So **`MAX` of a column containing `NaN` is
  `NaN` and `MIN` of one is the smallest non-`NaN`**, agreeing with
  `ORDER BY x DESC LIMIT 1` and with Postgres; `-0.0` sorts below `0.0` on the
  same order, so `MIN` over signed zeros is `-0.0` rather than whichever arrived
  first. `compare` takes primitive doubles, so the fix costs no boxing in loops
  the primitive slots exist to keep allocation-free — `Ops.cmp(a: Any, b: Any)`
  would have. This is a user-visible semantic change to an aggregate, not just a
  determinism fix.

  It was **not** an `eval`/`evalVec` parity bug — both paths use the same
  accumulator and agreed with each other — which is why none of the
  `ExprBatchTest` / `ExprParityFuzzTest` gates reached it. It is a
  layout-dependence bug of exactly the kind the mode-differential and
  metamorphic fuzzers exist to catch, and they missed it only because the
  corpora were all-finite. Fixing the comparison was therefore half the work:
  `DataGen` (the mode-differential corpus) and `MetaQueryGen.payloadDomain` (the
  metamorphic and sharded corpora, which build their relations from their own
  pool and do **not** call `DataGen.generate`) now draw `NaN`, `-0.0` and
  `±Infinity` into a quarter of float columns through the shared
  `DataGen.withSpecialDoubles`, and two coverage guards
  (`generatorReachesMinMaxOverIeeeSpecialValues`,
  `aggDecompositionReachesIeeeSpecialValues`) assert the values really reach a
  `MIN`/`MAX`. Deterministic regressions in `hash_aggregate_spill_test` pin row
  order, batch shape, partition split and spill flush order. One residue worth
  knowing: `RowOracle`'s tolerant comparator treats `+0.0` and `-0.0` as equal
  (it must — reordering a `SUM` over signed zeros legitimately flips the sign of
  a zero), so a signed-zero `MIN`/`MAX` divergence is invisible to the
  layout-differential properties and is pinned by the deterministic regression
  instead.

- **`RANK`/`DENSE_RANK` used to rank tied rows separately when the ORDER BY key
  was `NaN` — fixed.** `WindowExec.sameKey` decides whether two rows are ORDER BY
  peers and compared them with `Ops.eq`, but the rows were sorted with `Ops.cmp`
  (`sortPartition`). The two disagree on exactly the values the rest of the
  engine reconciles: `Ops.eq(NaN, NaN)` is FALSE where the sort ties them, so
  tied rows got different ranks; `Ops.eq(-0.0, 0.0)` is TRUE where the sort
  separates them, so strictly-ordered rows shared a rank. The `NaN` direction was
  a plainly wrong answer — `DENSE_RANK()` over a partition whose rows all tie
  must be `1` for every row, and it was not; the rank then moved with the order
  rows reached the window in, which is how a join-commutativity flip surfaced it.

  `sameKey` now compares with `Ops.cmp(av, bv) == 0`, so the peer test IS the
  tie predicate of the sort that produced the order (the comparator's ASC/DESC
  flip negates the sign, which leaves `== 0` alone). Two nulls stay peers,
  matching the comparator's `(null, null) => 0`. The `-0.0` half is a behaviour
  change with no failing seed behind it: `-0.0` and `0.0` used to share a rank
  and now rank 1 and 2, agreeing with the row order `ORDER BY` already produces.
  Only `RANK` and `DENSE_RANK` call `sameKey`; `ROW_NUMBER`, `LAG`/`LEAD` and
  every frame bound are positional, and `PARTITION BY` goes through the key
  codecs (see [architecture.md](architecture.md) §2a) — so one operator
  deliberately applies two different float rules, identity for partitioning and
  the total order for peers.

  Third instance of one family, after the `evalVec` ordering comparisons and
  `MinMaxState`'s accumulator: an ordering site that reached for raw IEEE
  instead of the engine's one total order. Found by `metamorphic_fuzz_campaign`
  and `sharded_mode_fuzz_campaign`, both red at seed 16502 immediately after the
  `MIN`/`MAX` fix above widened the corpora to carry `NaN` — the bug was older
  than that widening, which only made it reachable. Pinned by three regressions
  in `window_exec_spill_test` and by
  `MetamorphicFuzzTest.rankWindowsReachIeeeSpecialOrderKeys`, which measures the
  thinnest chain in the corpus: a rank window lands on ~2% of seeds, a float
  ORDER BY key on ~0.5%, and one carrying a `NaN` on ~0.06%. See
  [plan 06](../plans/followups/06-window-rank-peer-total-order.md).

- **Byte arrays, decimals and BIGINTs past 2^53 used to compare wrongly, and
  hashing disagreed with `=` — fixed.** `Ops.eq` / `Ops.cmp` dispatched on the
  boxed value's class. A byte array is neither a `Number` nor `Comparable`, so
  `=` was reference equality (two equal-content arrays were unequal) and
  `ORDER BY` compared `toString` of the identity hash, an order that changed
  run to run. A `BigDecimal` and a `Long` are both `Number`s, so both went
  through `doubleValue` and lost every digit past double's 53-bit mantissa:
  `10000000000000000001 = 10000000000000000002` and `9007199254740993 =
  9007199254740992` were TRUE, `<` between them FALSE, and `MIN`/`MAX` over
  decimals that collide as doubles kept whichever arrived first, so the answer
  moved with row order, partition fan-out and spill flush order (the bug-06
  shape). The hash side disagreed with the comparison side in both directions:
  `HashKeys` compared byte arrays by content but decimals with
  `BigDecimal.equals`, which includes the scale, so `GROUP BY` collapsed rows
  `=` called different and split `1.0` from `1.00`, which `=` called equal.
  An equi-join written as a join key and the same join written as a residual
  predicate gave different answers, for all three types. Behind the codec were
  three more sites with the same defect: `CountDistinctState` and
  `DistinctState` kept a raw `HashSet` (identity for arrays, scale for
  decimals), `HashPartitioner.hashCell` routed `1.0` and `1.00` to different
  shards, and `HashJoinExec.columnHashCode`, which routes grace-hash join and
  window spill buckets, hashed a byte array by identity, so two equal keys
  landed in different buckets and never met.

  Now every comparison is by value. `Ops` compares two integers as longs, a
  decimal against any number with `compareTo` (exact, scale-blind) and byte
  arrays with `Arrays.equals` / `Arrays.compareUnsigned`; pairs with a `FLOAT`
  or `DOUBLE` in them are untouched, NaN rules included. The vectorized twin
  moves with it: `VecOps.compare` sends two integers to `integralCompare`
  (`readLong`) and any pair with a decimal to the generic path, and
  `InListExpr`'s hoisted literal set keys integers on `longValue` and leaves a
  decimal to the `Ops.eq` scan (its old `Double` set held `10000000000000000002`
  for `10000000000000000001`). Every hash agrees: the codecs store a decimal as
  `KeyCodec.decimalKey` (the value at its column's declared scale when it can be
  written there exactly, else without trailing zeros, so every spelling of a
  value is one key), the DISTINCT sets hold `DistinctElement`s (a byte array in
  a `BytesKey`), and both routing hashes hash a byte array's content and a
  decimal's nearest double (`HashPartitioner.decimalHash`, which is
  scale-free, needs no declared scale, and costs only balance on decimals a
  double cannot tell apart). A decimal key reads back at the declared scale,
  and `MinMaxState.finish` spells a decimal extremum the same way, so neither
  depends on which spelling arrived first; a plain projection still shows the
  stored spelling, since the column's declared scale is not enforced on what
  it holds. The fourth instance of one family (after the three float bugs
  above), widened from "an ordering site" to "an equality, hash or dedup site
  that does not agree with `=`"; see [conventions.md](conventions.md). Found
  by a hand audit of the four `DataType`s the fuzz corpora never draw
  ([plan 02](../plans/robustness/02-total-order-for-decimal-and-binary.md));
  BIGINT turned up while checking the codec's equality claim against `Ops.eq`.
  Deterministic regressions in `sql_engine_test`, `hash_aggregate_spill_test`
  (row order, partition split, spill), `agg_state_serde_test`,
  `exchange_exec_test`, `hash_join_spill_test`, `window_exec_spill_test`,
  `hash_keys_test` and `expr_batch_test`; the fuzz corpora still do not draw
  decimal or binary columns ([plan 04](../plans/robustness/04-widen-fuzz-corpus-to-every-type.md)).

- **Mode-differential comparison is a multiset with a floating-point tolerance.**
  The `mode_differential_fuzz_test` (and the metamorphic `MetaModeDifferential`)
  assert one `(data, query)` gives the same result across spill on/off, metrics
  on/off, and partition/batch layouts. Two comparison gotchas, both in
  `RowOracle.multisetEquals`:
  - **Multiset, not positional.** The K-way merge in `SortExec` is not stable
    (`sort_exec_test`) and the collapsing aggregate emits groups in a
    layout-dependent order, so equality is over multisets, never row positions.
    For an `ORDER BY` query the oracle additionally checks each run is *sorted*
    (the multiset check alone would not catch an unsorted result).
  - **Floating-point (`Double` and `Float`) columns use a `NaN`-aware tolerant
    comparator; every other column compares exactly.** The order-dependent
    aggregates (`SUM`/`AVG`/`STDDEV`/`VAR`/`COVAR`/`CORR`) accumulate in `double`,
    so a different reduction order (a different layout or spill flush order)
    yields a `Double` result that differs by rounding — those columns compare
    within a small relative+absolute tolerance. `Float` columns do NOT reorder (a
    `Float` reduction stays bit-identical across modes), but a *computed* `Float`
    projection can produce `NaN` (a float divide-by-zero), and a boxed `NaN`
    cannot be an exact grouping key — Scala's `==` on a boxed `NaN` is `false`, so
    two identical `NaN` rows would fail to match themselves. So `Float` is routed
    through the same comparator purely for `NaN`-awareness; the tolerance itself
    is never exercised for it (a bit-identical `Float` still compares equal).
    Treating `Float` as an exact key was a latent harness bug the metamorphic
    fuzzer surfaced; `Float` now shares the `Double` path. Every
    non-floating-point column (integer `SUM` in `Long`, `COUNT`, `MIN`/`MAX`,
    projected/group-key values) is bit-identical and compares exactly, so the
    tolerance can never mask a wrong group or a dropped row. A DISTINCT aggregate
    (`COUNT`, `SUM` or `AVG(DISTINCT …)`) silently disables spill (it is not
    spillable), so the spill-on run takes the in-memory path — parity still holds,
    and the fuzzer does not special-case it.

- **The metamorphic TLP oracle partitions the query OUTPUT, never decomposes an
  aggregate.** `Tlp` checks `Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS
  NULL)`. Pushing that partition into the *input* of an aggregate (the
  "aggregate-TLP" form — split the input three ways, aggregate each, recombine)
  is UNSOUND for non-distributive aggregates (`COUNT(DISTINCT)`, `AVG`, `STDDEV`,
  and others do not recombine by union). So the oracle wraps the whole query and
  partitions its OUTPUT — `WITH q AS (<body>) SELECT * FROM q WHERE <partition>`
  (`MetaQuery.tlpBase`) — which is sound for every shape (aggregate, window,
  union, join) because it splits a finished result multiset with no aggregate
  algebra. Input-level and aggregate-decomposition TLP are deliberately NOT used.
  The same wrap could be a derived-table subquery in FROM, but the binder rejects
  those (see "No subqueries" below), so the CTE form is the only route. The
  partition column is also restricted to type-reliable non-float output columns:
  a computed `Int`-labelled column can reparse to `Double` (`-1.0 % c`) and a
  `NaN` there falls into none of the three partitions, breaking the reassembly
  through no engine fault.

- **The sharding gates are frozen at class load, so testing the sharded path
  needs a separate JVM.** `LogicalPlanCardinality.MinShardableSize`
  (aggregate/distinct exchange) and `BroadcastBuildThreshold`
  (broadcast-vs-shuffle join) are `val`s resolved once from system properties /
  env vars at class load. A test cannot flip them mid-JVM to compare
  collapsing-vs-sharded plans — the `val` is already bound, and a
  `System.setProperty` in `@Before` runs too late. So the sharded path gets its
  own target, `sharded_mode_fuzz_test`, with `jvm_flags` setting both to `1`; its
  `shardingGateIsActive` test asserts the flags took effect. This is the same gate
  `ModeDifferential` notes it cannot toggle as an in-JVM mode. See
  [testing.md](testing.md#property-based-testing-fuzz).

- **The SQL frontend used to reject grouping parentheses and `TRIM(x)` —
  fixed.** `SELECT (a + b) * c`, `WHERE (x = y)`, `ON (a = b)`,
  `HAVING (COUNT(*) > 1)` and `TRIM(name)` all bind now. Two things are worth
  knowing about the shape of that fix.

  **A grouping paren is transparent at five walks, not one.** Adding the
  `ParenthesedExpressionList` case to `bindExprWithAggs` alone leaves the engine
  reporting a *misleading* error rather than the honest one: `childExpressions`
  is a second walk over the same AST (it drives `containsAggregate`, the
  aggregate `collect`, and `collectWindows`), and with no paren case there it
  returns `Nil` silently — so `SELECT (SUM(x)) FROM t` never collects its
  aggregate and dies with "Aggregate 'SUM' used in non-aggregating position",
  and `SELECT (ROW_NUMBER() OVER (ORDER BY x))` with "Window function used in
  non-window position". Three more walks matter: the post-aggregation `rebind`
  in `buildAggregation` matches a projection to a group key by canonical text,
  so without a paren case `SELECT (a + b) ... GROUP BY a + b` fails with
  "Unknown column 'a'. Available: [a + b]"; `deriveAlias` decides the output
  column name, so `SELECT (a)` would be named `(a)` rather than `a`; and
  `selectListOrdinal` decides what counts as a position, so `ORDER BY (1)` would
  quietly become a constant sort key. All five match `LogicalBuilder.Grouped`;
  a new walk over the AST must too.

  **Only single-argument `TRIM(x)` is supported, and the rejection is
  deliberate.** JSqlParser gives TRIM its own `TrimFunction` node (not the
  generic `Function` node `UPPER`/`LOWER` get) so it can carry
  `TRIM([LEADING|TRAILING|BOTH] [remstr] FROM str)` and MySQL's
  `TRIM(str, remstr)`. Its accessors are a trap: `getExpression` is the
  **remstr**, not the string being trimmed, in every extended form —
  `TRIM(BOTH ' ' FROM x)` gives `getExpression=' '`, `getFromExpression=x`, and
  `TRIM(LEADING FROM x)` gives `getExpression=null`. Binding `getExpression`
  blindly (the obvious-looking implementation) trims the wrong operand
  silently: `TRIM(BOTH 'x' FROM 'xxaxx')` returns `x`. `TRIM(x, 'a')` is worse,
  because it sets neither the specification nor the FROM keyword — the remstr
  just lands in `getFromExpression` and gets dropped. So `Funcs` implements the
  single-argument whitespace trim (`Funcs.scala` gates TRIM on
  `argTypes.size == 1` in both `returnType` and `applyVec`) and the binder
  rejects every other form with "Only the single-argument TRIM(x) is
  supported". `LTRIM`/`RTRIM` parse as ordinary `Function`s and are simply
  unknown functions.

  `QueryGen` / `MetaQueryGen` still render paren-free — that is now an unused
  widening opportunity rather than a constraint (see
  [testing.md](testing.md#property-based-testing-fuzz)).

- **A named `ORDER BY` key binds against the scope at that point in the plan, not
  against the SELECT list.** `ORDER BY` resolves its columns where the sort sits:
  over a plain projection or join that is the FROM scope, so
  `SELECT t0.c0 AS o0 FROM r0 AS t0 ORDER BY o0` fails with
  `Unknown column 'o0'. Available: [t0.c0, t0.c1]` and `ORDER BY t0.c0` is what
  binds. Past a `GROUP BY` the scope is the aggregate output (`ORDER BY c0`), and
  past a `DISTINCT` it is the projection — so there the alias binds and the source
  name no longer does. An **ordinal is the exception**: `ORDER BY 1` names a
  SELECT-list position and is resolved before binding at all three sort sites, so
  it reaches an aliased expression the name form cannot. These rules are why
  `MetaQueryGen.totalOrderKeys` orders a top-n query by its SOURCE columns and
  emits no `LIMIT` for a `DISTINCT`, windowed, aggregate, or `UNION` shape. That
  generator was written against the name form only; now that ordinals bind at
  every sort site they would give a total order in exactly those excluded shapes,
  so teaching it to emit them is an open widening opportunity, not a constraint.

- **A clause the binder does not read used to vanish — every one is now
  rejected, and a test keeps it that way.** `LogicalBuilder` binds the
  JSqlParser AST by calling the getters it knows about, so a clause carried by
  any other getter never errored: it was dropped, and the query answered a
  different question from the one asked. The 2026-09-10 audit
  ([plans/robustness/01](../plans/robustness/01-reject-or-honor-every-clause.md))
  found eighteen, five of them badly wrong: `JOIN ... USING` and `NATURAL JOIN`
  ran as cartesian products, `LEFT SEMI JOIN` as a left outer join,
  `SUM(DISTINCT x)` ignored `DISTINCT`, and `agg FILTER (WHERE p)` dropped the
  predicate and stopped the aggregate collapsing. Classifying every getter
  found many more: a bare `OUTER JOIN` and Oracle's `a = b(+)` ran as INNER
  joins, `x NOTNULL` ran as `x IS NULL`, `RLIKE` / `REGEXP` ran as `LIKE`,
  `LIMIT 1, 2` dropped its offset, `ORDER BY` / `LIMIT` after a parenthesized
  query were dropped, `COUNT(UNIQUE x)` ignored `UNIQUE`, `ROW_NUMBER(a)` and
  `SUM(a, 2) OVER ()` ignored arguments, `SUM(a) WITHIN GROUP (...)` computed a
  running sum, and `CAST(x AS INT[])`, `t.s[1]`, `* EXCEPT (c)` and
  `FROM other.t` dropped the array type, the subscript, the exclusion and the
  schema.

  `sql/plan/UnsupportedClauses.scala` is the fix. For every AST class the binder
  reaches, each getter the binder does not read is listed either as a rejection
  (the clause, advice, and SQL that reaches it) or as inert (a spelling such as
  `!` for `NOT`, a structural accessor, another view of a value the binder
  reads), with the reason. `LogicalBuilder.build` runs `UnsupportedClauses.reject`
  before binding. It walks the statement reflectively and descends only through
  the getters the binder reads, so it inspects exactly the nodes binding would
  and in every position they occur — `NULLS LAST` inside `OVER (...)` meets the
  same entry as in the outer `ORDER BY`. A `SELECT` with no `FROM` (an internal
  `NullPointerException` before) reads one row with no columns now, and
  `GROUPING SETS` no longer leaks the `_agg0` slot name into its error.

  **The guard is `sql/plan:unsupported_clauses_test`, and it is mechanical.** It
  reads which getters the binder calls out of `LogicalBuilder`'s compiled class
  files — the constant pool names every class the binder matches on and every
  method it calls — and requires every public getter and field of every audited
  class to be read, rejected or inert, exactly once; every class the binder
  reaches, and every JSqlParser subclass of an audited class, to be audited; and
  every rejection's example to reach that rejection. A JSqlParser upgrade that
  adds a getter fails it until someone decides what the getter means, and so
  does a binder change that starts reading a rejected getter without deleting
  the rejection. **What it cannot see is a getter the binder reads in one
  context and ignores in another**: `DISTINCT` is honoured in `COUNT` and was
  dropped in `SUM`; an argument is read for `LAG` and was ignored for
  `ROW_NUMBER`. Those are *partial* rejections, found by reading the binder. When
  you teach the binder to read a getter for one function or one position, check
  what it does in the others.

  Three JSqlParser traps the audit stepped on: `Table.getDBLinkName` returns the
  table's own name when there is no `@link` and `getDatabase` is a non-null empty
  object for an unqualified table, so "the getter returned something" is not
  presence for either; `Function.getAttributeName` throws on every call without
  an attribute; and `TRY_CAST` / `SAFE_CAST` differ from `CAST` only in
  `CastExpression.keyword`, a public field with no getter, which is why the test
  audits public fields as well as getters.

- **`ORDER BY <ordinal>` used to parse and then silently not sort — fixed.**
  `ORDER BY 2 DESC` returned scan order, because the ordinal bound as an integer
  literal (a constant sort key, which the comparator can never use to separate
  two rows) rather than as the second output column. `GROUP BY <ordinal>` always
  resolved positionally; ORDER BY never got the equivalent treatment at any of
  its three binding sites — below a `Project`, above a `Distinct`, and above a
  `Union`. All three now resolve through `LogicalBuilder.selectListOrdinal`, and
  an out-of-range position is a hard error (`ORDER BY position 5 is not in select
  list (size 2)`) instead of a no-op. Two shape rules the resolver inherits from
  `GROUP BY`, and one that differs: only a **bare** integer literal is an
  ordinal, so `ORDER BY +1` (a JSqlParser `SignedExpression`) stays a constant
  sort key; an ORDER BY position **may** point at an aggregate
  (`SELECT a, SUM(b) ... ORDER BY 2` is standard SQL) where a GROUP BY position
  may not; and parens used to be clause-dependent, because JSqlParser drops them
  in `GROUP BY (1)` (a bare `LongValue`, so it *does* resolve positionally,
  despite what the old doc comment claimed) but keeps `ORDER BY (1)` as a
  `ParenthesedExpressionList`. Now that the binder accepts grouping parens the
  resolver strips them first, so both clauses agree and `ORDER BY (1)` is
  position 1 — which is also what Postgres and MySQL do, since their grammars
  drop a redundant paren before the positional check ever runs. Leaving the
  paren opaque there would have made `ORDER BY (1)` a constant sort key: a
  silent no-op, the exact bug this entry records fixing.

- **Layout invariance is a property of the window FUNCTION, not of "has a
  window".** The mode-agreement oracles compare result multisets across
  partition/batch layouts, so they may only run a query whose output multiset is
  layout-independent. `MetaQueryGen` used to exclude every windowed query from
  them, which left `WindowExec` — and the sharded window path, where the planner
  puts an `ExchangeExec` under it — checked by `Tlp` on a single fixed layout
  only. The distinction that matters is whether two rows TIED on the window's
  `ORDER BY` can get different values: `ROW_NUMBER`, `LAG`/`LEAD` and every
  running aggregate can (the engine treats `RANGE` frames as `ROWS`, so peers do
  not share a running value), while `RANK`/`DENSE_RANK` cannot (peers share a
  rank, and a rank depends only on the multiset of order-key values in the
  partition) and neither can an aggregate window with no `ORDER BY` (its frame is
  the whole partition). `WinItem.tieSensitive` carries the classification and
  `MetaQuery.isLayoutInvariant` reads it, so the tie-insensitive shapes now flow
  through mode agreement and join commutativity. The `invariantWindows` coverage
  guard in `generatorCoversShapes` is what stops the blind spot returning.

  The classification is only as sound as the engine's peer test: while
  `WindowExec.sameKey` compared ORDER BY peers with IEEE equality, `RANK` over a
  `NaN` key WAS tie-sensitive, and the relations duly reported the disagreement
  (the `sameKey` entry above). Classifying a function as tie-insensitive is
  therefore a claim about the engine, not only about SQL.

- **`job.json`'s `runFile` follows the task's CONFIG, not its result.** The
  aggregate manifest indexes each task's full `_run.json`, and `JobSession`
  hydration follows that pointer to rebuild a finished run (timestamps, per-
  validation records, the output directory) — falling back to the compact
  summary when it is absent. `DataJob.writeJobRecord` used to derive the pointer
  from `TaskResult.outputPath`, which is `None` for a `Failed` or `Skipped`
  task even when that task has an `outputFile` and the runner stamped a record
  there (`writeFailedRecord` / `writeSkippedRecord` both do). So those records
  sat on disk unreachable from the manifest that exists to index them,
  contradicting `JobTaskSummary`'s own scaladoc. The pointer is now derived from
  `SQLTask.outputFile` (rendered, cloud paths excluded — those write no record),
  so it is present for every terminal status. Found by
  `fuzz/dag_scheduler_fuzz_test`.

- **A task with no `outputFile` can feed downstream views and validations.**
  It could not until recently: `DataJob.materializeIfNeeded` threw
  `UnsupportedOperationException("v1 requires an outputFile ...")` whenever a
  memory-only task had a DAG dependent or a validation, even though `SQLTask`'s
  own scaladoc advertised memory-only feeder tasks. Worse, it threw at RUN time,
  from inside the worker, so half the DAG had already executed and every
  downstream task cascaded to `Skipped` — a structural mistake surfacing as a
  mid-run failure rather than at `DataJob.buildDag()` like every other one
  (cycles, unknown views, duplicate output paths). The limitation is gone: such a
  task's result is drained into a `MaterializedView` via
  `MaterializedView.fromQuery` and published like any other view. The trade is
  explicit — the whole result stays in heap for the rest of the run, the same
  deal `cache = true` inputs and materialized CTEs make — so a task that writes
  an `outputFile` still publishes by re-reading its own output rather than
  holding it twice. Found by `fuzz/dag_scheduler_fuzz_test`.

- **An `AggState`'s accumulator slot must match its spill format, per type.**
  `MinMaxState` keeps a typed extremum in one of three slots — `longCur`,
  `doubleCur`, or `currentBoxed` — chosen from the result `DataType`. `MIN`/`MAX`
  over a `BooleanType` column used to return NULL under spill (and the right
  answer off-spill) because `updateAt` stored the boolean boxed while
  `writeSelf`/`readSelf` wrote and read `longCur`: the restored state had
  `hasValue = true` and a null boxed value. Booleans now ride `longCur` as 0/1 on
  every path (update / merge / finish / serde), which also orders them the SQL
  way, FALSE < TRUE. Found by `mode_differential_fuzz_test`. When adding a type
  to any `AggState`, change all four paths together — the on-disk format is the
  only one a global aggregate never exercises, so the mismatch is invisible until
  a grouped query spills. `fuzz/metamorphic_fuzz_test`'s `agg-decomposition`
  relation is the general guard.

## Known wrong answers, open (found 2026-09-10, not yet fixed)

One cluster remains of the confirmed defects from an audit of the surfaces the
fuzzers cannot reach, executed against `SqlEngine` at commit `d4bb409`, not
inferred. Plans, evidence and repro SQL are in
[`plans/robustness/`](../plans/robustness/README.md); this list exists so a
reader of the docs does not have to find the plans first. The audit's other two
clusters are fixed: SQL clauses the binder silently dropped (see "A clause the
binder does not read used to vanish" above) and byte-array and decimal
comparison (see "Byte arrays, decimals and BIGINTs past 2^53 used to compare
wrongly" above).

- **The parquet reader ignores the timestamp unit and has no `DECIMAL`
  mapping.** `TimestampLogicalTypeAnnotation.getUnit()` is never read and the
  raw `INT64` goes straight into a micros-based `TimestampVector`, so an
  externally-written `MILLIS` file reads 1000x too small and `NANOS` 1000x too
  large. `ParquetWriter` emits `MICROS`, which is why `parquet_roundtrip_test`
  passes: **a self-consistent round-trip cannot catch a wrong interpretation of
  an external format.** Separately, no `DecimalLogicalTypeAnnotation` case
  exists, so a parquet decimal falls through to its physical type - `IntType` or
  `LongType` holding the unscaled value (`123.45` reads as `12345`), or
  `BinaryType` holding raw two's-complement bytes. `ParquetWriter` cannot write
  `DecimalType` at all, so neither can spill: an operator that spills a
  `DECIMAL` column fails with `UnsupportedOperationException`. See
  [plan 03](../plans/robustness/03-parquet-external-format-fidelity.md).

All three clusters hid for the same reason, and it is the lesson to carry: the
fuzz generators render SQL from their own typed AST (so the corpus is exactly the
supported grammar), `DataGen.ColumnTypes` and `ExprGen.GenTypes` list six of the
engine's ten `DataType`s, and every file-format fixture is written by our own
writer. Plans 04-07 in that directory close those three holes.

## What's intentionally NOT done

- **Spill-to-disk** is implemented for `SortExec`, `HashAggregateExec`,
  `DistinctExec`, `HashJoinExec` (grace hash, equi-join only), and
  `WindowExec` (bucketed by PARTITION BY) but **disabled by default**.
  Opt in per-task via `output.json`'s `options.spill = "true"` (with
  optional `spill_threshold_bytes` and `spill_max_runs`). See
  `plans/perf/09-spill-to-disk.md` for the full design. Caveats:
  - Non-equi joins (cartesian-shaped) ignore the spill option — they're
    rejected above [[PhysicalPlanner.NestedLoopMaxRows]] anyway.
  - DISTINCT aggregates (`COUNT`, `SUM` and `AVG(DISTINCT …)`) fall back to
    the in-memory path even when spill is enabled — the underlying HashSet
    state doesn't round-trip through a typed schema in v1, so a query mixing
    one with other aggregates silently disables spill for that operator.
    Document the heap limit if a workload is hitting it.
  - `WindowExec` spill requires a non-empty PARTITION BY that's
    consistent across every window spec in the projection. Empty
    PARTITION BY (whole-result window) or mixed PARTITION BYs silently
    fall back to the in-memory path — there's no key set to bucket by.
  - Grace hash and window spill both use a fixed bucket count of 16
    (no recursive bucketing). Hot keys whose bucket exceeds heap will
    still OOM at probe/process time; that's a v1.1 issue.
- **`DECIMAL` has exact comparison but no exact arithmetic.** `SUM` and `AVG`
  over a `DECIMAL`, and the math functions, compute in `DOUBLE` (`SumState.init`
  picks `DoubleSumState` for anything but an integer), so `SUM` over two
  `10000000000000000001`s comes back as `20000000000000000000`, in a `DECIMAL`
  column. Arithmetic (`+ - * / %`, and unary minus) on a `DECIMAL` throws
  `IllegalStateException` from `Ops.arith` / `VecOps.arith` / `UnaryOpExpr`
  while the query runs, an internal exception class for what is a missing
  feature; `Analyzer.checkArithmetic` passes a `DECIMAL` because it is a
  number. Exact decimal arithmetic is a feature, out of scope of
  [plan 02](../plans/robustness/02-total-order-for-decimal-and-binary.md), which
  made comparison exact.
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
- **No SQL beyond the README's grammar.** `QUALIFY`, `GROUPING SETS`,
  `FILTER (WHERE ...)`, `PIVOT`, `USING` joins and the rest are rejected with an
  error naming the clause, never ignored; `sql/plan/UnsupportedClauses.scala`
  lists every one with its reason.
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
