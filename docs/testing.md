# Testing

The repo's safety net is split between unit tests (one `scala_junit_test`
per leaf directory) and the jaffle-shop end-to-end example, which exercises
the whole stack at realistic scale. Every change should pass both.

## Required workflow for every change

**1. Unit tests must pass.**

```bash
bazel test //...
```

Every module change should land with a test added or updated under the
corresponding `src/test/scala/...` target. New behaviour without a test is
not done — find the relevant target in the [test inventory](#test-inventory)
below.

The default `bazel test //...` excludes the perf-tagged regression test
under `//src/test/scala/com/transformer/bench` via the
`--test_tag_filters=-perf` line in `.bazelrc`. Opt in via
`bazel test //... --test_tag_filters=perf` after changes that intentionally
affect engine performance — see [Performance regression guard](#performance-regression-guard)
below.

**2. Run the jaffle-shop end-to-end example.** It's the largest realistic
job in the repo (15-task DAG, ~150k rows, full DBT data_test suite) — the
fastest way to find regressions that unit tests miss (planner edge cases,
runner orchestration, GUI hydration when the GUI is changed). Run it after
any non-trivial change to SQL, the runner, or the directory loader:

```bash
bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar
# Should exit 0, write to /tmp/transformer-jaffle-out/, and report
# 15/15 tasks Succeeded with all validations passing.
```

**3. Update the relevant docs.** After landing a change, find every doc in
this repo whose claims are now stale and update them in the same commit:

- [`README.md`](../README.md) — user-visible features, limitations, SQL surface,
  file format support, templating variables.
- [`docs/architecture.md`](architecture.md) — module map (new files),
  cross-cutting patterns (new operators, hooks, batching invariants).
- [`docs/conventions.md`](conventions.md) — new patterns established by the
  change.
- [`docs/extending.md`](extending.md) — new extension points or revised recipes.
- [`docs/gotchas.md`](gotchas.md) — new pitfalls discovered, or features moved
  from "not done" to "done".
- [`docs/testing.md`](testing.md) — new test targets and what they cover.
- [`docs/code-map.md`](code-map.md) — when a file grows past or shrinks below
  a hot spot.

A change that adds a feature without updating user docs is not done.

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

bazel build //examples/polymarket:polymarket_deploy.jar
java -Xmx12g -jar bazel-bin/examples/polymarket/polymarket_deploy.jar \
    [examples/polymarket/job] [/tmp/transformer-polymarket-out] [executionTime]
# 17-task pipeline over the Polymarket tick-level orderbook Kaggle dataset
# (~140M parquet rows across 5 inputs). One branch is intentionally constructed
# to ValidationFail and block its downstream — launcher exits 0 iff that exact
# pattern holds. Needs `~/Downloads/archive/` dataset checkout.

# Build + launch the JavaFX GUI. Prefer the wrapper script over the deploy_jar
# — it bakes in `-XX:MaxRAMPercentage=75.0` (the deploy_jar doesn't, since
# `java -jar` ignores `jvm_flags`). With JVM-default heap a non-trivial parquet
# workload will OOM or thrash on macOS / inside containers; with the percentage
# flag the GUI scales to ~75% of the box's RAM (e.g. ~12GB on a 16GB Mac).
bazel build //examples/gui_app:gui_app
bazel-bin/examples/gui_app/gui_app [job-dir]
# (or `java -XX:MaxRAMPercentage=75.0 -jar bazel-bin/examples/gui_app/gui_app_deploy.jar [job-dir]`)

# Inspect a parquet file or glob — schema, partition count, footer-derived
# row count, and a few decoded rows. Reader-only; no SQL engine pulled in.
bazel build //tools/parquet_peek:parquet_peek_deploy.jar
java -jar bazel-bin/tools/parquet_peek/parquet_peek_deploy.jar \
    'path/or/glob/*.parquet' [--rows N]
```

Tests are JUnit 4 via `scala_junit_test`. Each leaf test directory has a
`BUILD.bazel` with one target per test class. When you add a new test, make
sure the test class name ends with `Test` (matches the `suffixes = ["Test"]`
discovery rule).

## Test inventory

| Test target | Coverage |
|---|---|
| `core/core_types_test` | DataType, Schema, ColumnarBatch null/select, Catalog, Date/Timestamp box || `core/execution_options_test` | `ExecutionOptions.Default` disables spill, `fromOutputOptions` parses `spill`/`spill_threshold_bytes`/`spill_max_runs` with tolerance for typos and unknown writer-config keys riding along |
| `core/spill_test` | `Spill.openOperatorDir` creates a per-operator subdir under the override root, `close()` wipes it (idempotent), `newSpillFile` after close throws, operator tags are sanitized, `estimateBytes` scales with row count and handles all-null columns, `effectiveThresholdBytes` honors override and derives a positive ≤1 GiB default |
| `core/scheduler_test` | Work-helping mechanism guard (plans/bugfixes/02a H4 promoted to CI by 02d) in its own `parallelism=2` JVM: a depth-5/fan-4 nested `Scheduler.submitAndAwaitAll` fan-out (1364 tasks, 1024 leaves) **completes** on the 2-thread pool because `ForkJoinTask.get()` work-helps, while the identical shape awaited through a non-helping `CountDownLatch.await()` **wedges** it (asserted as a timeout, then force-drained so the suite's shared pool stays live). Pins the tree-shaped helping mechanism nested materialization rests on, so a refactor that swaps `submitAndAwaitAll` off `.get()` (or onto `managedBlock` — the reverted 02b regression) fails here instead of wedging production. It is NOT a blanket liveness proof — helpJoin's non-descendant inlining deadlocks K>1 lazily-nested exchanges, which is why engine plans pre-materialize exchanges bottom-up before consumers run (see [gotchas.md](gotchas.md), plans/bugfixes/02e + 02f). `parallelismPinnedTo2ByJvmFlags` asserts the BUILD `jvm_flags` actually arrived |
| `sql/exec/agg_state_serde_test` | Round-trip for every spillable `AggState` subtype (Count, CountIf, LongSum, DoubleSum, Avg, MinMax over Long/String, Variance/Stddev, Covar, Corr), including null-only inputs, empty MinMax, and a partial → spill → restore → merge flow proving the spilled state combines correctly with subsequent in-memory partials. CountDistinct is gated out via `isSpillable` |
| `sql/exec/sort_exec_spill_test` | Plan 09 Phase 3 parity: 5000 unique-keyed rows over 4 partitions produce identical sorted output with spill on (1-byte threshold) vs off; 100k rows × 1-byte threshold remain strictly sorted; empty input → empty output; single-tiny-batch never spills mid-stream; `spill_max_runs` aborts a runaway partition with a clear error; a duplicate-named child schema (`[k, k]`) round-trips through spill and the emitted batches keep the logical names (positional spill names — plans/bugfixes/01) |
| `sql/exec/hash_aggregate_spill_test` | Plan 09 Phase 4 parity: LongHashMap path matches non-spill exactly across 20k random rows × 4 partitions including NULL-key buckets; codec multi-key path matches within floating-point tolerance on SUM/AVG; empty input matches; `spill_max_runs` trips on a runaway partition; CountDistinct silently disables spill rather than erroring; name-colliding GROUP BY keys (two keys aliased to the same name → `[k, k, _agg*]`) round-trip through spill (positional `_k*` key names — plans/bugfixes/01) |
| `sql/exec/distinct_exec_spill_test` | Plan 09 Phase 5 parity: fixed-width and variable-width key schemas match across spill on/off, NULLs survive as a distinct bucket, empty input is empty, `spill_max_runs` aborts a runaway partition, a duplicate-named child schema (`[a, a]`) round-trips through spill with the logical names preserved on emit (positional spill names — plans/bugfixes/01) |
| `sql/exec/hash_join_spill_test` | Plan 09 Phase 6 parity for grace hash: inner / LEFT outer / RIGHT outer / FULL outer all multiset-equal under spill vs no-spill on the long-key fast path, codec multi-key path matches, NULL join keys never match (3VL) but still surface in outer-join unmatched emission, empty-left + empty-right edge cases for inner + LEFT outer; a join-derived (duplicate-named `[k, v, k, v]`) input on the probe side AND on the build side round-trips through grace-hash spill — the plans/bugfixes/01 repro that captured the original NPE |
| `sql/exec/window_exec_spill_test` | Plan 09 Phase 7 parity for window bucketed spill: ROW_NUMBER over PARTITION BY + ORDER BY is 1..|partition| in each partition multiset, whole-partition SUM broadcasts the same value to every row in a partition, LAG-with-default agrees after sorting by ORDER BY within each partition, NULL partition keys group together as a single partition with correct aggregates, empty input emits empty output, empty PARTITION BY silently falls back to in-memory (canSpill gate); a duplicate-named child schema (`[k, k]`) round-trips through bucketed spill with the logical output names preserved on emit (positional spill names — plans/bugfixes/01) |
| `sql/exec/spill_stress_test` | Plan 09 Phase 8 above-heap behavior at smaller scale (~100k–200k rows per operator, 1-byte threshold). HashAggregate: closed-form per-group SUM matches across many spill rounds; row-count conservation holds; unique-keys path stresses the maximum-distinct case. Sort: 200k-row external merge produces strictly increasing output. Distinct: 200k unique rows survive intact through many spill rounds. HashJoin: cross-product of group-counts equals expected match count. Window: ROW_NUMBER per partition is contiguous 1..count and totals to N |
| `core/hash_keys_test` | `KeyCodec.forColumns` selection (packed for fixed-width, object-array for variable-width, empty codec for zero keys, length-mismatch rejection), `EmptyKeyCodec` collapses every encode to the same sentinel, `BytesKey`/`ObjectArrayKey` structural equality + cached hashCode + `Array[Byte]` element handling + nulls, `PackedBytesCodec` round-trip across every supported primitive type + NULL bits + skip-if-any-null + boxed/direct agreement + column-order distinguished, `ObjectArrayCodec` round-trip across String/Binary/Decimal + different-instance Strings hash identically + skip-if-any-null + column-order distinguished, both codecs work as `java.util.HashMap` keys; **LongHashMap: basic put/get, NULL key via getOrInsertNull, getOrInsert evaluates supplier only on absent, insertion-order preserved across put+overwrite+null-insert, resize survives 200 entries (forces 4 doublings) with insertion order preserved, negative + Long.MaxValue + Long.MinValue keys, allocation-free `forEach` matches `iterator` results, `KeyCodec.isLongFittable` covers Int/Long/Bool/Date/Timestamp + rejects Float/Double/String/Binary/Decimal, `readAsLong` ↔ `writeLongTo` round-trip across every long-fittable type** |
| `temporal/template_renderer_test` | Every templated variable + arithmetic edge cases |
| `read/csv/csv_row_parser_test` | State-machine edge cases (quotes, CRLF, escapes, blanks) |
| `read/csv/csv_reader_test` | Inference, null handling, explicit schema, glob, bare dir, batch splitting |
| `write/csv/csv_writer_test` | Quoting, nulls, roundtrip with reader, abort cleanup |
| `read/parquet/parquet_roundtrip_test` | All-primitive write+read, end-to-end DataJob with parquet I/O, **small files pack into one partition each at the default 256MB target**, **a multi-row-group file splits into multiple partitions when `targetBytesPerPartition` is small (proves `skipNextRowGroup` lands correctly + per-partition row counts sum to the total)**, **column projection still works for partitions that don't start at row group 0** |
| `read/parquet/parquet_filter_translator_test` | Translator-level shape coverage for every pushable form: `IS NULL` / `IS NOT NULL` across all supported primitive types, `IS NULL` on a decimal column and on a computed expression are correctly residual, `IN` over int and string literal lists, `NOT IN`, `IN` with a NULL literal or non-literal item or empty list correctly bails (3VL), `BETWEEN`-shaped AND of `>=` / `<=` translates, mixed AND-chain of comparators + IS NOT NULL + IN. Plus an end-to-end correctness gate against a 3-row-group fixture (one no-null group, one all-null group, one mixed) that runs each new shape both with and without pushdown and asserts identical row sets, the pushed reader's raw row count is strictly less than the unfiltered scan (so we know stats-level skipping actually fired), and an IN over values outside every row group's range drops all groups (0 rows surface) |
| `sql/exec/sql_engine_test` | SELECT/WHERE/projection arithmetic, CASE, GROUP BY + COUNT/SUM/AVG/MIN/MAX, COUNT DISTINCT, INNER/LEFT JOIN, ORDER BY DESC + LIMIT, DISTINCT, HAVING, LIKE, IS NULL, scalar fns, empty-input aggregation; **COUNT_IF: no GROUP BY, grouped + NULL predicate ignored, empty input → 0, as window aggregate**; **window functions: ROW_NUMBER, RANK + DENSE_RANK ties, LAG/LEAD with default, running SUM over ORDER BY, aggregates over PARTITION BY, window without PARTITION BY, ROWS BETWEEN sliding frame, ORDER BY referencing a window expression, window function inside arithmetic, window after GROUP BY referencing the aggregate, window function in WHERE rejected**; **column projection push-down: drops unused columns, threads through aggregate, all-columns-used skips rewrite, narrowest-column projection when consumer references zero columns (primitive beats string regardless of declared order), narrowest pick for `SELECT <literal> FROM t LIMIT n`**; **math scalar functions: LN/LOG(x)/LOG(b,x)/LOG10/LOG2/EXP, SQRT/CBRT/SIGN, full trig + ATAN2 + DEGREES/RADIANS + PI()/E(), TRUNC(x[,n]), GREATEST/LEAST skipping NULLs, NULL inputs return NULL, RAND() in [0,1) + seeded determinism**; **statistical aggregates: STDDEV_SAMP/POP and VAR_SAMP/POP on a known dataset, GROUP BY partials → Chan merge, NULL inputs skipped + n<2 → NULL for sample stddev, COVAR_SAMP/COVAR_POP/CORR on y=2x, CORR with zero-variance series → NULL, STDDEV_POP as window aggregate**; **optimizer end-to-end: INNER JOIN with left/right/cross-side WHERE all return the right rows after FilterPushdown; LEFT JOIN preserves null-extended-side semantics — `WHERE r.x > N` filters out unmatched rows correctly without pushing into the right child, and the `r.k IS NULL` anti-join shape still surfaces only the unmatched-left rows; self-join with colliding column names returns the right values for both `a.x` and `b.x`**; **KeyCodec / LongHashMap coverage: GROUP BY single-Long (LongHashMap fast path) / 3×Long packed path / mixed Long+String object-array path, NULL keys grouped together on every path (codec packed-bits + object-array + LongHashMap sidecar slot), DISTINCT over 5-column mixed schema + fixed-width-only schema, JOIN on multi-column key with NULL-skip semantics, JOIN on two-Long packed-codec fast path, JOIN on single-Long key takes LongHashMap fast path (inner + LEFT outer preserves unmatched probe rows including NULL-key probe rows), single-Long GROUP BY with mixed NULL + non-null keys collapses NULLs into one bucket**; **plan 04 collapsing-path correctness (Phase 6's default conservative gates leave aggregates/distinct on the historic merge path; equi-joins broadcast unless both sides are above [[LogicalPlanCardinality.BroadcastBuildThreshold]]): GROUP BY over CSV emits one partition (no exchange inserted); GROUP BY over empty input emits 0 rows; 200 distinct keys all surface exactly once with correct aggregates; GROUP BY → ORDER BY DESC → LIMIT correctly top-Ns; equi-JOIN over small CSV emits one partition (broadcast path); per-side outer-join correctness — LEFT preserves unmatched-left, RIGHT preserves unmatched-build, FULL preserves both, including NULL-key probe rows; 200 distinct join keys all match correctly. The sharded paths themselves are exercised via [[ExchangeExecTest]] (operator-level) and end-to-end when the planner's cardinality gates fire**; **CTEs (`WITH`): simple CTE, outer filter on a column the CTE carries but the outer SELECT drops, a CTE chain where one CTE reads from an earlier one, two CTEs joined, a CTE joined to a real catalog table, the same CTE referenced twice (acts like a self-join), aggregation inside a CTE filtered outside, explicit column-alias list `WITH c(a, b)`, a CTE feeding both arms of a `UNION ALL`, case-insensitive CTE names, a CTE shadowing a like-named catalog view, and the two reject paths — `WITH RECURSIVE` and a column-alias-arity mismatch** (these exercise CTE binding + result correctness; the materialize-on-2+-references behaviour lives in `cte_materialization_test`)**; **spill end-to-end: the plans/bugfixes/01 repro `a JOIN b JOIN b` (whose inner result has a duplicate-named `[k, v, k, v]` schema) returns the same rows under spill (1-byte threshold) as without, exercising grace-hash spill of a join-derived schema through `SqlEngine`** |
| `sql/exec/cte_materialization_test` | CTE materialization policy + the compute-once proof. **compute-once**: a counting `CatalogView` under a 3-reference CTE (`c x JOIN c y JOIN c z`) is read exactly once per partition (not 3×) and the result equals the inline self-join; **empty CTE referenced twice** still reads the source once; **dependent CTEs** (`b` reads `a`, both ≥ 2 refs) read the source once (`b` sees materialized `a`); **UNION arms sharing a CTE** read the source once; **determinism fence**: a `RAND()` body referenced twice agrees on every row (`x.r = y.r`), which inlining would not; **planner controls**: a single-ref CTE introduces no `MaterializedView` scan and never executes the body during resolve, a two-ref CTE introduces the shared MV scan; **COUNT(\*) fast-path**: `SELECT COUNT(*)` over a materialized CTE collapses to `CountStarMetadataExec` (MV `exactRowCount`, no scan) |
| `sql/plan/expr_batch_test` | Parity gate for every `Expr.evalVec` override. Builds 64-row batches with no-null / every-other-null / all-null placements and asserts `eval(batch, row) == evalVec(batch).getBoxed(row)` (after normalizing to the declared `dataType`) for every row. Covers: `LitExpr` per primitive type + NULL literal; `ColRefExpr` per column type; `CastExpr` between representative numeric/string pairs; `UnaryOpExpr` (`+`, `-`, `NOT`); `BinOpExpr` AND/OR 3VL truth table, comparisons (`= == <> != < <= > >=`), arithmetic (`+ - * / %`) including divide-by-zero and both-sides-null, `\|\|` string concat with NULLs, cross-numeric Int-vs-Long comparison; `IsNullExpr` over mixed / no-null / all-null inputs and over a computed expression; `FuncExpr` for the entire `Funcs.applyVec` fast-path list (`COALESCE` 2-arg / literal fallback / all-null / string, `LENGTH`, `UPPER`/`LOWER`, `TRIM`, `CONCAT` null-propagating + 3-arg, `SUBSTRING` 2-arg + 3-arg, `ABS` int + double, `FLOOR`/`CEIL`/`CEILING`, `ROUND` 1-arg + 2-arg, `TRUNC` with null-scale propagation, `IF`, `NULLIF`) plus a `SQRT` case that exercises the unknown-function fallback; `CaseExpr` 2-branch + ELSE, no-ELSE NULL default, every-row-claimed short-circuit, NULL value in branch, column-expr value; `InListExpr` literal lists per type, NULL-in-items 3VL, cross-numeric literals (Int col against Long literals via the typed HashSet path), column items (general path); `LikeExpr` literal `%` suffix / `_` single-char / `%` in middle / NULL pattern → NULL / column pattern; one nested composite (`(a + b) > 0 AND NOT(c IS NULL)`) to verify overrides compose; **a nested-FloatType regression (`(a*b)+c` and `ABS(a*b)-c`) proving `eval` narrows float arithmetic per node, found by the parity fuzzer**. Any new `evalVec` override MUST extend this suite before merging |
| `fuzz/expr_parity_fuzz_test` | Property-based generalization of `expr_batch_test`. Generates random type-correct `Expr` trees over random `ColumnarBatch`es (varied null masks incl. all-null / no-null, the 8192 capacity boundary + 1-row, capacity-pad tails) and asserts `eval` and `evalVec` agree per row — same normalized value, both NULL, or both throw the same exception class — via `oracle/ExprParity`. Also: shrinker minimizes to a single failing node, same-seed reproducibility, and the generator's own type invariant. Default run is a small fixed-seed batch (`Props.DefaultSeeds`); see [Property-based testing](#property-based-testing-fuzz) for long campaigns. Exercises the full `evalVec`-override surface + the `Funcs.applyVec` fast-path list |
| `fuzz/mode_differential_fuzz_test` | Property-based generalization of the `*SpillTest` parity checks. Generates an in-memory dataset (`DataGen` — narrow value domains so GROUP BY / DISTINCT keys collide, tunable NULL density, 0-row / 1-row boundaries) and a single-table `SELECT` (`QueryGen` — projection / WHERE / GROUP BY / HAVING / aggregates incl. COUNT[DISTINCT] / MIN[MAX] over any column type / DISTINCT / ORDER BY / `LIMIT` over a total ORDER BY, which reaches `LocalLimitExec` + `GlobalLimitExec`), then runs the SAME `(data, query)` under spill on/off, metrics on/off, and several partition/batch layouts (1 big batch, tiny batches, many partitions, empty partitions) and asserts the result MULTISETS match via `RowOracle.multisetEquals` (exact on every column except `Double`, which compares with a tolerance because order-dependent aggregates accumulate in `double`). ORDER BY queries additionally assert each run is sorted. Also: a bind-reject-rate guard, a **corpus shape-coverage guard** (`generatedCorpusCoversTheInterestingShapes` — LIMIT reached, `MIN`/`MAX` over a Boolean column reached (~3.5% of seeds; the shape this fuzzer found broken under spill and that was then gated OUT of the generator until the engine was fixed, so the assertion is what stops the exclusion returning unnoticed), and columns spread across the no-NULL / some-NULL / all-NULL densities, with all-NULL asserted to stay a minority; a mis-mapped weight table once collapsed ~48% of columns to all-NULL, which grouped and deduped to nothing and quietly gutted the corpus), same-seed reproducibility, and hand-written regressions (GROUP BY / DISTINCT / ORDER BY-with-ties / empty-input global aggregate / COUNT(*) metadata fast path / float-aggregate tolerance) plus a top-n regression (`LIMIT` over a total order with the cutoff between two identical rows) and `multisetEquals` unit checks. Default budget is small (the engine runs ~7× per case); see [Property-based testing](#property-based-testing-fuzz) for campaigns |
| `fuzz/metamorphic_fuzz_test` | Property-based **metamorphic** gate over multi-relation SQL — finds SEMANTIC bugs (wrong answers every execution mode agrees on, which mode-differential is blind to). `MetaQueryGen` builds a `RelEnv` of 2–3 base relations sharing one narrow join-key domain plus a scope-/type-correct, paren-free `MetaQuery` (left-deep joins of every kind incl. self-joins, WHERE, a projection or GROUP BY core, an optional `UNION`/`UNION ALL` arm, optional CTEs incl. referenced-twice, an optional window, and an optional **top-n** `ORDER BY`-over-every-output-column `LIMIT`). Five relations, each decided by SQL semantics alone (no reference engine): **TLP** — `Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS NULL)` as multisets, partitioning `Q`'s OUTPUT via a CTE wrapper so it is sound for joins / aggregates / windows / unions / CTEs with no aggregate decomposition (partition key restricted to type-reliable non-float output columns); **NoREC** — `COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0 END)` over a single-relation predicate (empty-relation NULL→0 normalized); **multi-relation mode agreement** — the join extension of `mode_differential` (same result multiset across partition/batch layouts, metrics on/off, and spill); **join commutativity** — `A <kind> JOIN B ON p` ≡ `B <flipped kind> JOIN A ON p` with the SELECT list, WHERE and GROUP BY reused verbatim (sound because every reference is alias-qualified), the only relation that varies which side of a join is the BUILD side; **aggregate decomposition** — one-shot aggregation ≡ group-then-re-aggregate (`MIN`→`MIN`, `MAX`→`MAX`, `COUNT`→`SUM`, integral `SUM`→`SUM`), run in heap AND under spill, which drives the partial/final merge and spill-serde paths a global aggregate never reaches — the relation that generalizes the `MinMaxState` Boolean-slot bug; the spill mode runs for join queries too (it used to be gated off around the grace-hash join spill NPE, now fixed via positional spill names — see [gotchas.md](gotchas.md) and plans/bugfixes/01). Plus a bind-reject-rate guard (≈0 over 500 seeds), a shape-coverage guard (joins / unions / CTEs / windows / aggregates / top-n LIMIT / MIN-MAX-over-Boolean all reached), a non-vacuity guard for join commutativity (it must actually run, not skip, on >1/5 of seeds — it has nothing to check for a join-free query and skips a windowed one), a coverage guard for aggregate decomposition (`aggDecompositionCoversEveryColumnType` — it must never skip, and every payload column type must reach its `MIN`/`MAX` decompositions, since `MIN`/`MAX` picks its accumulator slot from the type; reads `AggDecomposition.coveredTypes`, so it costs no query), shrinker termination + reduction, same-seed reproducibility, and hand-written regressions (LEFT/FULL join null-extension, aggregate-output partition, `NOT IN` with NULL, `UNION`/`UNION ALL`, CTE-referenced-twice, window-column partition, self-join mode agreement, LEFT-becomes-RIGHT join commutativity, Boolean `MIN`/`MAX` decomposition, a top-n `LIMIT` whose cutoff splits two identical rows, and the shape checks proving a `LIMIT` drops out once the order stops being total). Default budget is small (each case runs the engine several times); campaign via `metamorphic_fuzz_campaign` |
| `fuzz/sharded_mode_fuzz_test` | The metamorphic gate (TLP / NoREC / multi-relation mode agreement / join commutativity / aggregate decomposition — same generators + oracles + shrinker as `metamorphic_fuzz_test`) re-run in a **separate JVM under sharded planning**. Since `MetaQueryGen` emits top-n queries, this is also where `GlobalLimitExec` over a sharded child is exercised. The sharding levers `LogicalPlanCardinality.{MinShardableSize, BroadcastBuildThreshold}` are `val`s frozen at class load, so this target sets both to `1` via `jvm_flags`, forcing every query onto the shuffle-join + sharded-aggregate/distinct paths the default gate never reaches. It also pins `shard_count=4`, so every breaker fans out to four shards and the target exercises real nested multi-shard plans; the default seed budget is deterministic, so this stays green in seconds. The K>1 campaign-volume hard-deadlock (the helpJoin cycle, reproduced at `FUZZ_SEEDS=20000` — plans/bugfixes/02e) is FIXED by bottom-up exchange pre-materialization (plans/bugfixes/02f): the same 20000-seed campaign now completes (still run campaigns under `--test_timeout` as basic hygiene). The old exchange-monitor serialisation cliff is also gone (`ExchangeExec`'s direct-use fallback materializes via CAS-claim + latch since plans/bugfixes/02d; tree-shaped work-helping pinned by `core/scheduler_test`; a `ForkJoinPool.managedBlock` compensation variant tried in 02b wedged the pool even faster and was reverted); multi-shard routing is also covered operator-level by `exchange_exec_test`, and the wedge topology itself by `sql/exec/exchange_deadlock_stress_test`. `shardingGateIsActive` asserts the flags actually arrived (else the target would silently re-run the default gate). Scope is narrow — do the metamorphic relations still hold under sharding? — so bind-reject / coverage are NOT duplicated here. Campaign via `sharded_mode_fuzz_campaign` (same `jvm_flags`) |
| `sql/plan/logical_plan_cardinality_test` | `LogicalPlanCardinality.estimate` for every plan node: scan / project / filter selectivity (per shape: `=` / range / `IS NULL` / `LIKE` / `NOT` inversion / default), AND multiplies / OR widens, limit caps both ways, distinct shrinks, aggregate without group keys collapses to 1, aggregate with group keys stays bounded by input, joins per kind (Inner=max / Left=left / Right=right / Full=sum) + None propagates when either side unknown, union sums and propagates unknown, sort / window pass through, `filterSelectivity` constants pinned to named `private[plan] val`s |
| `sql/plan/filter_pushdown_test` | `FilterPushdown` over every join kind: inner pushes left-only / right-only conjuncts into the matching child and re-indexes right-side ColRefs; inner with cross-side or mixed conjuncts splits correctly; stacked Filter(Filter(Join)) flattens before pushing; LEFT outer pushes left-only conjuncts and refuses right-only (including the `r.x IS NULL` anti-join shape); RIGHT outer is symmetric; FULL outer never pushes; cascaded inner joins push all the way to the relevant leaf scan with the right per-side shifts; filter above an aggregate is not pushed across; idempotent when re-run |
| `sql/plan/column_projection_pushdown_test` | Join pruning: parent + join-key columns survive on each side, columns referenced by neither are dropped; `SELECT *` leaves the join unchanged; self-join keeps colliding names on both sides (correctness over optimality); cross-side filter above a join still allows both sides to prune to the union of (parent + filter + join-key) refs; the plan-time `verify` rejects an out-of-range `ColRefExpr` with a targeted `IllegalStateException` |
| `sql/exec/join_swap_test` | `PhysicalPlanner.shouldBuildRight` decision matrix — inner left-smaller swaps, right-smaller stays, near-equal stays below 2× threshold, just-above-threshold swaps, no-estimates stays default, LEFT outer never swaps, RIGHT outer always swaps, FULL outer never swaps. End-to-end: inner-join row identity under both swap directions, LEFT outer preserves unmatched-left, RIGHT outer preserves unmatched-right under swap, FULL outer emits both unmatched sides, residual non-equi predicate under swap. Nested-loop size guard: small × small allowed, large × large refused with a specific error message, unknown-size fall-through allowed. **Plan 04 Phase 6 broadcast-vs-shuffle gate: equi-join with both sides small (< `BroadcastBuildThreshold`) does NOT wrap either child in `ExchangeExec` and emits `numPartitions = 1` (broadcast path); equi-join with one side small still skips exchange even when the other side is huge (small-side broadcast wins); equi-join with both sides above threshold wraps BOTH children in `ExchangeExec` and `numPartitions == defaultNumShards` (sharded build + probe); unknown estimate on either side falls back to the collapsing path** |
| `sql/exec/sort_exec_test` | Direct `SortExec` coverage. The K-way heap merge isn't stable, so assertions check sortedness under a local oracle comparator plus multiset equality, never position-by-position equality. Cases: overlapping-range multi-partition merge, multi-key ASC/DESC, NULLs-first under ASC and NULLs-last under DESC, empty partitions mixed with full, all-empty input, single-partition shortcut, **small-N fast-path branch (120 rows, below the 4096-row `SmallNThreshold` → concat+`Arrays.sort` fallback)**, 7200-row random-key cross-partition merge above the threshold, a default-capacity batch-shape check confirming the lazy emit streams `ColumnarBatch.DefaultCapacity`-sized batches, and a 4096-row monotone smoke matching the jaffle-shop sort regression target |
| `sql/exec/exchange_exec_test` | Direct `ExchangeExec` + `HashPartitioner` coverage. Asserts the three contracts every downstream operator depends on (no implementation-specific shard numbers asserted — only the equivalence-class structure of the routing): **permutation** (total rows in == total rows out across every Int / multi-column / String / NULL-key shape, with K ∈ {1, 4, 8, 16}); **co-location** (every row with the same key value lands in exactly one shard, including under `LowCardinality keys < K` where most shards stay empty); **NULL routing** under both `NullsToLast` and `NullsToZero` policies, with multi-column NULL detection (any NULL key column routes the row). Also: output schema matches child, out-of-range / negative partition index throws, zero-shards is rejected at construction, repeated `execute(s)` calls produce identical shard contents (lazy materialization happens once), independent ExchangeExec instances over the same input route identically (partitioner is pinned), concurrent multi-thread `execute(s)` reads via `Scheduler.submitAndAwaitAll` preserve row counts + collocation, and 4096 random Int keys spread to every shard at K=8 (catches a regression where `Int.hashCode % K` clusters contiguous small keys — fixed by `HashPartitioner.fmix32`). Materialization is lazy and exactly-once under the CAS-claim + latch (plans/bugfixes/02d): 32 external threads racing `execute(s)` on one exchange drive the child's `execute(p)` exactly `numPartitions` times with all same-shard readers agreeing on the rows, and a throwing child propagates the wrapped failure to every concurrent caller (original cause preserved in the chain, the failing partition executed once — a failed materialization is never retried, later callers get the same failure). Publication is exactly-once; on the rare winner-stack-reentrancy path (a helpJoin guest, unreachable from external callers) computation can duplicate without publication — see [gotchas.md](gotchas.md). **The 02f engine pass (`PhysicalPlanner.preMaterializeExchanges`): a diamond-shared exchange (one instance, two parents via `UnionExec`) publishes exactly once with both parents reading it, and the pass sees through `MeteredPlan` wrappers (metrics-wrapped exchange published before consumption)** |
| `sql/exec/exchange_deadlock_stress_test` | Regression stress for the K>1 sharded-execution deadlock family (plans/bugfixes/02e diagnosed, 02f fixed). Rebuilds the campaign-captured wedge topology from real exec classes — K pool-task drivers over `ExchangeExec` over a collapsing `HashJoinExec` whose probe side is a second `ExchangeExec` over a union of collapsing joins with multi-partition build fan-outs — and loops it (2 groups x 3000 fresh-instance iterations) under heavy steal churn with the 02f pre-materialization pass applied per iteration, exactly as the engine drain paths do. Must stay green (~2s; parallelism pinned to 16 in `jvm_flags` — the wedge's steal races need idle workers, and at 8 the lazy-mode hit rate collapses). `STRESS_LAZY=1` skips the pass and drains the same graphs lazily from pool tasks: that mode reproduced the 02e wedge in 5-15s in 7/7 pre-fix trials (~1/150 per-iteration hit rate, median ~130 iterations) and remains the on-demand red instrument — probabilistic, so it is documentation-by-demonstration, not a CI assertion. On any stall the test dumps all transformer/ForkJoinPool stacks and classifies whether the 02e shape (`ensureMaterialized` + `helpJoin` frames) is present. Budget knobs via `STRESS_*` env vars |
| `job/data_job_test` | End-to-end CSV → SQL → CSV; templated output path; templated SQL; validation failure path persists per-validation `_validation-<slug>.csv` sample files; multi-task pipeline with view chaining; diamond DAG ordering; failed-task skip propagation with independent sibling success; validation-failure skip propagation; empty `sql`; setup error reporting; concurrent sibling execution; multi-partition input → multiple part files; `maxPartitions` coalesce / no-op / single-file; downstream task reads all upstream part files; **`buildDag` returns nodes/deps without I/O**; **`TaskProgressListener` fires onStart+onFinish for executed tasks and only onFinish (Skipped) for upstream-failed downstreams**; **`_run.json` written on every termination (Succeeded / ValidationFailed / Failed) with the right `status` field and validation list**; **`TaskRunRecord.discover` finds multi-partition layouts newest-first, exact path with no template, empty when no records, sibling-task isolation**; **`job.json` written when `jobRunOutput` is configured, with per-task summaries + runFile pointers + failure messages**; **consistency-check detects declared part files missing on disk**; **`onInputStart`/`onInputFinish` fire for every input load** (success and failure paths); **input-load failure skips every dependent SQL task without firing `onTaskStart`**; **`enqueuedAt` is populated for every task and never exceeds `startedAt`**; **independent branches start before all inputs have finished loading** (proves Phase 1 is no longer a barrier) |
| `job/task_dag_test` | Pure dependency analyzer + DAG builder: table-name extraction, independent roots, linear chain, diamond, cycle detection, unknown reference, duplicate viewName, viewName/input collision, main-SQL self-reference, validation self-reference allowed, validation peer reference, duplicate output path, empty input, template rendering before extraction |
| `job/json_test` | The stdlib JSON parser in `job/Json.scala` — scalars, escapes, nested objects, arrays, type errors, trailing content, scalar→string coercion for the option map |
| `job/directory_job_loader_test` | End-to-end `DirectoryJobLoader.load(...)`: basic run, relative vs absolute input paths, validations dir (success + failure), templated input paths + outputDir, alphabetical chaining, default outputDir, JSON scalar→option-map coercion, error cases (no/multiple `.json`, missing `main.sql`, missing jobDir), **per-table `output.json` `partitionBy` extends output path, absent leaves path unchanged, malformed throws**, **default `jobRunOutput` lands at `<outputDir>/job.json`** |
| `job/job_output_layout_test` | `JobOutputLayout.detect`: SingleRun when `<dir>/job.json` is a direct child, MultiRun when `<dir>/<sub>/job.json` is present (sorted newest-first by `finishedAt`), Empty for dirs lacking either, skips subdirs without job.json, ignores malformed manifests |
| `gui/sql_highlighter_test` | The pure-Scala SQL tokenizer in `gui/SqlHighlighter.scala` — null/empty input, case-insensitive keywords + functions, identifier classification, integer/decimal/scientific numerics, single-quoted strings with `''` escape + unterminated, line / block / unclosed-block comments, top-level `{{ template }}` tokens, template inside a string stays a string, punctuation tagging, full SELECT query round-trips losslessly, line splitting preserves content + handles block comments spanning lines, **window-function keywords (OVER/PARTITION/ROWS/UNBOUNDED/PRECEDING/CURRENT/ROW) and RANK as a function** |
| `gui/result_persister_test` | Interactive-SQL persist path: one part file per source partition with `_run.json` record stamping status=Succeeded + the right row count + format + file list, `csvHeader=false` toggle, `maxPartitions=Some(1)` coalesces multiple partitions into a single part file, unknown format rejected |
| `core/metrics/metrics_collector_test` | Sub-plan 1 of the instrumentation work — disabled-path identity (planner returns un-wrapped operators given default `ExecutionOptions`), enabled-path shape (`OperatorMetrics` tree for a Scan→Filter→Project query), `TaskMetricsRecord` round-trip including the operator tree + per-task CPU / allocation / GC fields, partial-counts survival on a failed task. Also covers `MetricsCollector.parseBool` semantics + `globalDefault` resolution + ThreadMXBean `isInstanceOf` guard |
| `core/metrics/operator_counters_test` | Sub-plan 2 — per-operator custom counters across HashAggregate / HashJoin / Sort / Distinct / Window / Scan / Exchange. Index-drift asserts (`IdxCounterNames.length == highest Idx + 1`) for each operator catch counter-array vs index-constant mismatches; per-operator populated-counter tests build a small fixture, drain through a `MetricsNode`, assert `groupCount` / `buildSideRows` / `comparatorCalls` / `partitionCount` / `bytesRead` / `rowsRoutedShard*` populate to the expected values. Covers the spill-on path for HashAggregate too — a tiny-threshold spill fixture asserts non-zero `spillEvents` + `bytesSpilled` |
| `sql/exec/metrics_plan_wrap_test` | `PhysicalPlanner.plan` wrap path — confirms that when `opts.metricsEnabled = true` every operator in the returned tree carries a `metricsNode`, ids are pre-order indices, and the wrapped tree's `execute(p)` chains through `MeteredIterator`. When `metricsEnabled = false` the planner returns the un-wrapped tree byte-for-byte (object-identity gate on the operator references). |
| `bench/jaffle_regression_test` | **Perf-tagged.** Opt-in via `bazel test //... --test_tag_filters=perf`. Runs jaffle twice (spill-off and spill-on), parses each iteration's `_perf.json` files, asserts no task's median wall time regressed by more than 20% (spill-off) or 25% (spill-on) against `benchmarks/baseline/jaffle_shop{,_spill}.json`. Excluded from the default `bazel test //...` invocation via `.bazelrc`'s `--test_tag_filters=-perf` because the baselines are machine-dependent — see [Performance regression guard](#performance-regression-guard) |

The other GUI components (`SqlView`, `TaskDetailsPanel`, `DagCanvas`, etc.) have
no JUnit tests — they're thin UI over engine APIs. Smoke-test by launching
`bazel-bin/examples/gui_app/gui_app_deploy.jar` and pointing it at a job dir.

## Property-based testing (fuzz)

`src/test/scala/com/transformer/fuzz/` holds a small hand-rolled property-based
testing harness (no new dependency — PBT by hand is the point, same as the SQL
engine). Every property carries a **coverage guard** alongside it — a test that
asserts the generator still reaches the shapes the property targets, and that the
oracle still has something to check. A green property over a corpus that stopped
generating the interesting case is the failure mode these guard against, and it
has happened here twice: a mis-mapped weight table gutted the null-density
distribution (`generatedCorpusCoversTheInterestingShapes`), and `MIN`/`MAX` over a
Boolean column was gated out of the generator around an engine bug and had to be
asserted back in. The recipe for writing one is in
[extending.md](extending.md#add-a-metamorphic-relation). It is a `testonly`
`scala_library` named `fuzz` plus the
`expr_parity_fuzz_test`, `mode_differential_fuzz_test`, `metamorphic_fuzz_test`,
and `sharded_mode_fuzz_test` targets. The pieces:

- `Rng` — seeded, splittable random source over `java.util.SplittableRandom`.
  The top-level seed is the repro key; iteration `i` uses seed `base + i`.
- `Props.forAll` — the property runner. Loops a seed budget (overridable
  per-property via the `count` argument / `Props.seedCountOr`), generates a
  value, runs the property, and on the first failure greedily **shrinks** to a
  minimal counterexample before failing the JUnit test with the seed.
- `Shrinker` — integrated, strictly-decreasing shrink combinators plus the
  `Expr`-tree shrinker (collapse to a child, replace with a literal / NULL,
  drop CASE branches / IN items / variadic args, recurse into children) and the
  `QueryCase` shrinker (drop dataset rows, drop a trailing unreferenced column,
  strip query clauses, drop a projection / aggregate / GROUP BY key, simplify a
  contained expression).
- `RowOracle` — the scalar `normalize` / `scalarEquals` lifted from
  `ExprBatchTest` (NaN-aware so two NaNs count as agreement), plus
  `multisetEquals` for whole-row, order-insensitive comparison with a tolerance
  on `Double` columns only (see [gotchas.md](gotchas.md)).
- `ExprGen` — generates a random `ColumnarBatch` + a typed `Expr`, **type-correct
  and total by construction** (asserts its own `dataType` invariant; excludes
  `RAND()` and the shapes where the engine's row and vector paths legitimately
  differ — float/double *ordering* comparisons and float/double `IN` lists,
  where `eval` orders via `Double.compare` / `Double.equals` and `evalVec` uses
  primitive IEEE ops, disagreeing only on `NaN` / `-0.0`).
- `oracle/ExprParity.check` — the eval-vs-`evalVec` property (same normalized
  value, both NULL, or both throw the same exception class).
- `DataGen` — generates an in-memory `Dataset` (narrow value domains so GROUP BY
  / DISTINCT keys collide; tunable NULL density; 0-row / 1-row boundaries) plus
  `reshape` / `singlePartition` / `evenPartitions` / `withEmptyPartitions` that
  rebuild the same logical rows as a `MaterializedView` under a chosen
  partition/batch layout.
- `QueryGen` — generates a single-table `SELECT` (projection, WHERE, GROUP BY +
  aggregates, HAVING, DISTINCT, ORDER BY, LIMIT) as a structured `GenQuery`
  rendered to SQL by `SqlRender`. Total by construction (a query that binds also
  runs) and **paren-free** — the SQL frontend rejects grouping parentheses, so
  expressions rely on operator precedence (see [gotchas.md](gotchas.md)).
  Excludes `RAND()` and the clock functions. `LIMIT` is emitted only over a
  **total** ORDER BY (every output column an order key, so rows tied at the
  cutoff are identical rows and top-`n` is layout-independent); the soundness
  condition is checked in `render`, so a shrink that drops an order key drops
  the `LIMIT` with it rather than manufacturing a false counterexample.
- `oracle/ModeDifferential.check` — the mode-differential property: one
  `(data, query)` must produce the same result multiset under spill on/off,
  metrics on/off, and every partition/batch layout (and stay sorted when the
  query has ORDER BY).
- `MetaQueryGen` — the multi-relation generator (the metamorphic fuzzers' query
  source). A `RelEnv` of 2–3 base relations sharing one narrow join-key
  type/domain, plus a `MetaQuery` AST: a left-deep `FromClause` (joins of every
  kind incl. self-joins), a WHERE, a `QueryCore` that is a `ProjectCore` or an
  `AggCore`, an optional `setOp` `UNION`/`UNION ALL` arm, optional `ctes` (incl.
  the referenced-twice/materialized shape), and optional window items. Emits
  scope-/type-correct, paren-free, total SQL (the table qualifier rides in
  `ColRefExpr.name`; reuses `QueryGen.AggSpec` + `SqlRender`). Also `NoRecCase`
  (a single relation + a predicate). Derived-table subqueries in FROM are
  unsupported by the binder, so the generator never emits them — the TLP CTE
  wrapper supplies output-level partitioning without them.
- `oracle/RelEngine` — shared engine-run helpers for the metamorphic oracles
  (run a SQL string against a catalog, collect rows, scalar reads) plus the
  `Verdict` ADT (`Held` / `Skipped` / `Rejected`). A bind/plan rejection of the
  generator's own SQL is counted toward the bind-reject rate, never a finding.
- `oracle/Tlp.check` — output-level TLP: `WITH [cte defs,] q AS (<body>) SELECT *
  FROM q [WHERE <partition>]`, run for all four partition variants
  (`p` / `NOT p` / `p IS NULL` / none) on ONE fixed single-partition layout, then
  asserting the three partition multisets reassemble the unpartitioned whole.
  Partitioning the OUTPUT keeps it sound for joins / aggregates / windows /
  unions / CTEs with no aggregate decomposition; the partition key is restricted
  to type-reliable non-float output columns. See
  [extending.md](extending.md#add-a-metamorphic-relation).
- `oracle/NoRec.check` — `COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0 END)`
  over a single relation, normalizing the empty-relation `SUM` NULL to 0.
- `MetaQueryGen` also emits a **top-n** shape: a bare, type-reliable, non-float
  projection with `ORDER BY` over every one of its columns plus a `LIMIT`, so rows
  tied at the cutoff are identical rows and the top-`n` multiset survives every
  layout, build-side swap, and each of TLP's four executions. The keys are DERIVED
  (`MetaQuery.totalOrderKeys`), not stored, so a shrink that drops a projection
  item shrinks the order with it and a shrink that breaks totality drops the
  `LIMIT` instead of manufacturing a false counterexample. `ORDER BY` names the
  SOURCE columns, not the output aliases — see the binding rules in
  [gotchas.md](gotchas.md) — which is also why `DISTINCT`, windowed, aggregate and
  `UNION` shapes get no `LIMIT`.
- `oracle/JoinCommutativity.check` — `A <kind> JOIN B ON p` ≡
  `B <flipped kind> JOIN A ON p` (INNER/FULL unchanged, LEFT↔RIGHT). Rewrites
  only the FROM clause's first join and reuses the SELECT list / WHERE /
  GROUP BY verbatim, which is sound because every generated reference is
  alias-qualified and aliases travel with their leaves. The only relation that
  varies which side of a join the planner builds. Skipped for a join-free or
  windowed query.
- `oracle/AggDecomposition.check` — one-shot aggregation ≡ group-then-
  re-aggregate (`SELECT MIN(v) FROM r` ≡
  `WITH g AS (SELECT k, MIN(v) AS m FROM r GROUP BY k) SELECT MIN(m) FROM g`),
  for `MIN`/`MAX` over any type, `COUNT(*)`/`COUNT(v)` re-aggregated with `SUM`,
  and `SUM` over integral columns only (a float `SUM` legitimately re-associates).
  Run in heap AND under spill: a global aggregate holds one state and never
  flushes, so an `AggState` serde defect is invisible until a grouped query
  spills and restores — exactly the shape of the `MinMaxState` Boolean-slot bug.
- `oracle/MetaModeDifferential.check` — the multi-relation extension of
  `ModeDifferential` (the join paths: exchange insertion, build-side swap). Same
  result multiset across partition/batch layouts, metrics on/off, and spill;
  comparison tolerates `Double` columns only. Only multiset-stable shapes are
  checked (a window whose ORDER BY has ties is layout-dependent and left to `Tlp`
  on a fixed layout). The spill mode runs for join queries too — it used to be
  skipped around the grace-hash join spill NPE, now fixed via positional spill
  column names (see [gotchas.md](gotchas.md) and plans/bugfixes/01).

The default `bazel test //...` runs each property over a **small fixed-seed
batch**, so it is deterministic and fast (the `*_fuzz_test` targets; the
mode-differential default is smaller still since it runs the engine ~7× per
case). Run a longer campaign against the `fuzz`-tagged `*_fuzz_campaign` targets.
Two flags matter: `--test_tag_filters=` clears the default `-perf,-fuzz` filter
(otherwise the tagged campaign is excluded even when named explicitly), and the
`--test_env` (env-var) budget reliably re-runs the action, whereas `--jvmopt`
may hit the cached test result:

```bash
bazel test //src/test/scala/com/transformer/fuzz:mode_differential_fuzz_campaign \
    --test_tag_filters= --test_env=FUZZ_SEEDS=50000 \
    --nocache_test_results --test_timeout=3600
```

Both `fuzz.seeds` / `fuzz.seed` (system properties) and `FUZZ_SEEDS` / `FUZZ_SEED`
(env vars) are read. A failure prints the exact seed and the minimized
counterexample; reproduce a single case with `FUZZ_SEED=<seed> FUZZ_SEEDS=1`.
When the fuzzer finds a real divergence, distill it into a named regression here
(and, for an `eval`/`evalVec` gap, into `ExprBatchTest`; for an operator parity
gap, into the matching `*SpillTest`) and keep the generator general so it stays
guarded.

`.bazelrc` excludes the `fuzz` tag from the default run (`-perf,-fuzz`), so the
small `*_fuzz_test` targets (now including `metamorphic_fuzz_test` and
`sharded_mode_fuzz_test`) are part of `bazel test //...` while the campaigns are
opt-in. `sharded_mode_fuzz_test` / `sharded_mode_fuzz_campaign` additionally
carry `jvm_flags` that set
`transformer.scheduler.{shard_min_size,broadcast_threshold}=1`: those gates are
`val`s read at class load, so forcing the sharded shuffle-join +
sharded-aggregate/distinct paths needs a separate JVM, not an in-test toggle (see
[gotchas.md](gotchas.md)). They also set `shard_count=4` so every breaker fans out
to four shards, exercising real nested multi-shard plans. The default budgets are
deterministic and green in seconds, and since bottom-up exchange
pre-materialization landed (plans/bugfixes/02f) the 20000-seed sharded campaign
completes too — the K>1 campaign-volume hard-deadlock it used to hit is fixed
for engine execution (see [gotchas.md](gotchas.md)); still pass `--test_timeout`
on long campaigns as basic hygiene. Its
`shardingGateIsActive` test asserts the gate flags arrived. Add a new generator /
property by following the recipe in
[extending.md](extending.md#add-a-property--generator), or a new metamorphic
relation via [extending.md](extending.md#add-a-metamorphic-relation).

## Performance regression guard

[`docs/benchmarking.md`](benchmarking.md) is the full reference for the
instrumentation framework, microbench harness, macro bench runner, and
the perf-tagged regression test. Short summary:

- `_perf.json` per task with the operator tree + per-task CPU / allocation /
  GC deltas. Enable via per-task `options.metrics = "true"`, sysprop
  `transformer.metrics.enabled`, or env var `TRANSFORMER_METRICS_ENABLED`.
- Microbenches under `//benchmarks/micro/` (JMH via the programmatic
  Runner pattern). The most important is `DisabledOverheadBench` which
  gates the disabled-path overhead at < 1%.
- Macro bench under `//benchmarks/macro/`: drives the deploy jar N times,
  aggregates per-task wall-time statistics, diffs against a checked-in
  baseline. Checked-in baselines: `benchmarks/baseline/jaffle_shop.json`
  (spill-off) and `benchmarks/baseline/jaffle_shop_spill.json` (spill-on).
  Polymarket is dev-local (`benchmarks/baseline/local.polymarket.json`,
  gitignored).
- `jaffle_regression_test` is the perf-tagged JUnit guard. Opt-in via
  `--test_tag_filters=perf`. Re-capture baselines after intentional perf
  changes — see the procedure in
  [`docs/benchmarking.md` §5](benchmarking.md#5-regression-guard).
