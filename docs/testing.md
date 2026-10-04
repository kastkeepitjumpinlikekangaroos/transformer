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
| `core/spill_test` | `Spill.openOperatorDir` creates a per-operator subdir under the override root, `close()` wipes it (idempotent), `newSpillFile` after close throws, operator tags are sanitized, `estimateBytes` scales with row count and handles all-null columns, `effectiveThresholdBytes` honors override and derives a positive ≤1 GiB default, `positionalSchema` stores a NULL-typed column as an all-null BOOLEAN (parquet has no NULL type) |
| `core/scheduler_test` | Work-helping mechanism guard (plans/bugfixes/02a H4 promoted to CI by 02d) in its own `parallelism=2` JVM: a depth-5/fan-4 nested `Scheduler.submitAndAwaitAll` fan-out (1364 tasks, 1024 leaves) **completes** on the 2-thread pool because `ForkJoinTask.get()` work-helps, while the identical shape awaited through a non-helping `CountDownLatch.await()` **wedges** it (asserted as a timeout, then force-drained so the suite's shared pool stays live). Pins the tree-shaped helping mechanism nested materialization rests on, so a refactor that swaps `submitAndAwaitAll` off `.get()` (or onto `managedBlock` — the reverted 02b regression) fails here instead of wedging production. It is NOT a blanket liveness proof — helpJoin's non-descendant inlining deadlocks K>1 lazily-nested exchanges, which is why engine plans pre-materialize exchanges bottom-up before consumers run (see [gotchas.md](gotchas.md), plans/bugfixes/02e + 02f). `parallelismPinnedTo2ByJvmFlags` asserts the BUILD `jvm_flags` actually arrived |
| `sql/exec/agg_state_serde_test` | Round-trip for every spillable `AggState` subtype (Count, CountIf, LongSum, DoubleSum, DecimalSum, Avg, DecimalAvg, MinMax over Long/String/Boolean/Binary/Decimal, Variance/Stddev, Covar, Corr), including null-only inputs, empty MinMax, and a partial → spill → restore → merge flow proving the spilled state combines correctly with subsequent in-memory partials. The Binary and Decimal cases ride the boxed slot and assert the true extremum on both sides of the round-trip (unsigned byte order, so `0x80` is the largest; decimals one apart past double's mantissa; the decimal spelled at its declared scale), which `Ops.cmp` got wrong before plan 02. A decimal `SUM` and `AVG` restore their running sum exactly, past double's mantissa, and finish at the result type's scale. CountDistinct is gated out via `isSpillable` |
| `sql/exec/sort_exec_spill_test` | Plan 09 Phase 3 parity: 5000 unique-keyed rows over 4 partitions produce identical sorted output with spill on (1-byte threshold) vs off; 100k rows × 1-byte threshold remain strictly sorted; empty input → empty output; single-tiny-batch never spills mid-stream; `spill_max_runs` aborts a runaway partition with a clear error; a duplicate-named child schema (`[k, k]`) round-trips through spill and the emitted batches keep the logical names (positional spill names — plans/bugfixes/01); explicit NULL placement under spill: ASC NULLS LAST and DESC NULLS FIRST over 4000 rows with ~10% NULL keys and a unique tie-break, spilled output equal to the heap output row for row and every NULL at the named end |
| `sql/exec/hash_aggregate_spill_test` | Plan 09 Phase 4 parity: LongHashMap path matches non-spill exactly across 20k random rows × 4 partitions including NULL-key buckets; codec multi-key path matches within floating-point tolerance on SUM/AVG; empty input matches; `spill_max_runs` trips on a runaway partition; CountDistinct silently disables spill rather than erroring; name-colliding GROUP BY keys (two keys aliased to the same name → `[k, k, _agg*]`) round-trip through spill (positional `_k*` key names — plans/bugfixes/01); `MIN`/`MAX` over a Boolean column under spill (the accumulator-slot bug); and five `NaN`/`-0.0` regressions pinning that `MIN`/`MAX` over a float column follows the total order whatever the row order (`updateAt`), the batch shape (`updateBatchAt`), the partition split (`merge`) or the spill flush order — all five fail against the raw-IEEE comparison they replaced. A whole-row multiset compare cannot be used for those: Scala's `==` on a boxed `NaN` is primitive `NaN == NaN`, so two identical NaN-carrying rows never match; they compare cells with `java.lang.Double.equals`, which is bit-exact; `SUM` / `AVG(DISTINCT v)` over four partitions whose value ranges overlap only partly, so losing a partial or double-counting an overlap both change the answer, with spill requested (it falls back to the heap path) and without; and three plan-02 regressions over `MIN`/`MAX` of a decimal column (values one apart past double's mantissa, one spelled at two scales) and a byte-array column (`0x80` among values a signed comparison would order differently) across row order (`updateAt` and `updateBatchAt`), partition split (`merge`) and spill, each asserting the true extremum, the decimal at its declared scale |
| `sql/exec/distinct_exec_spill_test` | Plan 09 Phase 5 parity: fixed-width and variable-width key schemas match across spill on/off, NULLs survive as a distinct bucket, empty input is empty, `spill_max_runs` aborts a runaway partition, a duplicate-named child schema (`[a, a]`) round-trips through spill with the logical names preserved on emit (positional spill names — plans/bugfixes/01) |
| `sql/exec/hash_join_spill_test` | Plan 09 Phase 6 parity for grace hash: inner / LEFT outer / RIGHT outer / FULL outer all multiset-equal under spill vs no-spill on the long-key fast path, codec multi-key path matches, NULL join keys never match (3VL) but still surface in outer-join unmatched emission, empty-left + empty-right edge cases for inner + LEFT outer; a join-derived (duplicate-named `[k, v, k, v]`) input on the probe side AND on the build side round-trips through grace-hash spill — the plans/bugfixes/01 repro that captured the original NPE; a `BINARY`-keyed inner join keeps every match under grace hash, where the bucket hash used the array's identity and split equal keys across buckets |
| `sql/exec/window_exec_spill_test` | Plan 09 Phase 7 parity for window bucketed spill: ROW_NUMBER over PARTITION BY + ORDER BY is 1..|partition| in each partition multiset, whole-partition SUM broadcasts the same value to every row in a partition, LAG-with-default agrees after sorting by ORDER BY within each partition, NULL partition keys group together as a single partition with correct aggregates, empty input emits empty output, empty PARTITION BY silently falls back to in-memory (canSpill gate); a duplicate-named child schema (`[k, k]`) round-trips through bucketed spill with the logical output names preserved on emit (positional spill names — plans/bugfixes/01). Also the **`RANK`/`DENSE_RANK` peer regressions** (heap and spill, plan 06): a partition whose ORDER BY key is `NaN` on every row is one peer group so every row ranks 1; a mixed partition ranks the tied `NaN` rows last and equally (and DESC first and equally); `-0.0` and `0.0` are NOT peers and rank 1 and 2. All three fail against the `Ops.eq` peer test that shipped before — the ranks must follow the same total order the sort did; NULL ORDER BY keys rank as one peer group where the placement puts them — default (first under ASC), ASC NULLS LAST, DESC NULLS FIRST — on the heap and spill paths; `BINARY` PARTITION BY keys stay one partition under bucketed spill (the bucket hash used the array's identity, so one partition's rows were windowed in pieces) |
| `sql/exec/spill_stress_test` | Plan 09 Phase 8 above-heap behavior at smaller scale (~100k–200k rows per operator, 1-byte threshold). HashAggregate: closed-form per-group SUM matches across many spill rounds; row-count conservation holds; unique-keys path stresses the maximum-distinct case. Sort: 200k-row external merge produces strictly increasing output. Distinct: 200k unique rows survive intact through many spill rounds. HashJoin: cross-product of group-counts equals expected match count. Window: ROW_NUMBER per partition is contiguous 1..count and totals to N |
| `core/hash_keys_test` | `KeyCodec.forColumns` selection (packed for fixed-width, object-array for variable-width, empty codec for zero keys, length-mismatch rejection), `EmptyKeyCodec` collapses every encode to the same sentinel, `BytesKey`/`ObjectArrayKey` structural equality + cached hashCode + `Array[Byte]` element handling + nulls, `PackedBytesCodec` round-trip across every supported primitive type + NULL bits + skip-if-any-null + boxed/direct agreement + column-order distinguished, `ObjectArrayCodec` round-trip across String/Binary/Decimal + different-instance Strings hash identically + skip-if-any-null + column-order distinguished, both codecs work as `java.util.HashMap` keys; **LongHashMap: basic put/get, NULL key via getOrInsertNull, getOrInsert evaluates supplier only on absent, insertion-order preserved across put+overwrite+null-insert, resize survives 200 entries (forces 4 doublings) with insertion order preserved, negative + Long.MaxValue + Long.MinValue keys, allocation-free `forEach` matches `iterator` results, `KeyCodec.isLongFittable` covers Int/Long/Bool/Date/Timestamp + rejects Float/Double/String/Binary/Decimal, `readAsLong` ↔ `writeLongTo` round-trip across every long-fittable type**; **decimal keys are values, not spellings (plan 02): every scale of one value — trailing zeros, exponents, `-0.000` — encodes to one key with one hash through `SingleObjectKeyCodec` and `ObjectArrayCodec`, by the batch, boxed and scratch paths, decodes at the column's declared scale (an over-scale value without its trailing zeros), and distinct values stay apart** |
| `temporal/template_renderer_test` | Every templated variable + arithmetic edge cases |
| `read/csv/csv_row_parser_test` | State-machine edge cases (quotes, CRLF, escapes, blanks) |
| `read/csv/csv_reader_test` | Inference, null handling, explicit schema, glob, bare dir, batch splitting |
| `write/csv/csv_writer_test` | Quoting, nulls, roundtrip with reader, abort cleanup |
| `read/parquet/parquet_roundtrip_test` | All-primitive write+read, end-to-end DataJob with parquet I/O, **small files pack into one partition each at the default 256MB target**, **a multi-row-group file splits into multiple partitions when `targetBytesPerPartition` is small (proves `skipNextRowGroup` lands correctly + per-partition row counts sum to the total)**, **column projection still works for partitions that don't start at row group 0** |
| `read/parquet/parquet_filter_translator_test` | Translator-level shape coverage for every pushable form, plus temporal literals (a `LocalDate` pushes as the same epoch-day predicate an INT literal makes, with its own brute-force gate over three month-disjoint files; and a comparison on a timestamp column is never pushed, compared or in an IN list, whether the literal is an `Instant` or a count, since its statistics are in the unit its file stores): `IS NULL` / `IS NOT NULL` across all supported primitive types, `IS NULL` on a decimal column and on a computed expression are correctly residual, `IN` over int and string literal lists, `NOT IN`, `IN` with a NULL literal or non-literal item or empty list correctly bails (3VL), `BETWEEN`-shaped AND of `>=` / `<=` translates, mixed AND-chain of comparators + IS NOT NULL + IN. Plus an end-to-end correctness gate against a 3-row-group fixture (one no-null group, one all-null group, one mixed) that runs each new shape both with and without pushdown and asserts identical row sets, the pushed reader's raw row count is strictly less than the unfiltered scan (so we know stats-level skipping actually fired), and an IN over values outside every row group's range drops all groups (0 rows surface) |
| `read/parquet/parquet_external_format_test` | Our reader against files **parquet-mr wrote**, never our own writer: a writer and reader that share an assumption agree with each other however wrong it is, so `parquet_roundtrip_test` cannot see a misread of the format. The fixtures come from `read/parquet:external_parquet` (`ExternalParquet`), a testonly library that writes any `MessageType` (given in the footer's own text form, `message m { optional int96 ts; }`) with parquet-mr's example writer and reads files back with parquet-mr's reader, rendering each value with parquet-mr's own stringifier for its declared type; expected values are written by hand or computed with `java.time`. Covers: a `TIMESTAMP` in each unit (`MILLIS` used to read 1000x too small, `NANOS` 1000x too large) and as `INT96`, all four encodings of one instant in one file reading alike, `NANOS` truncated to the microsecond the way a nine-digit `TIMESTAMP` literal is (the earlier one before 1970, and the same as `INT96`), a `MILLIS` value past the microsecond range failing the read by name, a local (`isAdjustedToUTC = false`) timestamp keeping its wall clock, a glob mixing units, and a filter through the planner over every unit; an `INT96` timestamp read whole and through a projected scan (projection used to decode it as `INT64`), a projected `FIXED_LEN_BYTE_ARRAY` column (used to throw), a glob whose files order two same-typed columns differently (used to swap their values silently) and allow NULLs the first file does not, a glob file that lacks a column or stores it as another type (fails when the view opens, naming the file and column); and `DECIMAL` in all four layouts (`INT32`, `INT64`, `FIXED_LEN_BYTE_ARRAY`, `BINARY`, each with negatives, zero and NULL, compared with `BigDecimal.equals` so the scale counts too, sign extension spelled out byte by byte, an empty `BINARY` failing the read), a projected decimal column, our writer's layout per precision as parquet-mr reads the footer, our writer round-tripping every layout as parquet-mr's own stringifier reads it, a value written at the declared scale when exact and refused when it would round or overflow the precision, and one cycle of parquet-mr write, our read, our write, parquet-mr read; and every other annotation as decided: an unsigned `INT32` widening to `BIGINT` and an unsigned `INT64` to `DECIMAL(20,0)` (their upper halves used to read negative), narrow signed and unsigned integers as `INT`, `JSON` and `ENUM` as `STRING`, `BSON` and `UUID` as `BINARY`, `FLOAT16` widening to `FLOAT` exactly (subnormal, `-0.0`, infinity and NaN included), `TIME`, `INTERVAL` and `REPEATED` columns failing by name, and no comparison pushed to the statistics of a column whose stored bits are not its engine values (an unsigned `INT32`, a `FLOAT16`) while `IS NULL` still is. The helper is testonly and public, exports parquet-mr's `MessageType`, and carries its own Hadoop runtime jars, so any test package can write fixtures with it |
| `sql/exec/sql_engine_test` | SELECT/WHERE/projection arithmetic, CASE, GROUP BY + COUNT/SUM/AVG/MIN/MAX, COUNT DISTINCT, INNER/LEFT JOIN, ORDER BY DESC + LIMIT, DISTINCT, HAVING, LIKE, IS NULL, scalar fns, empty-input aggregation; **COUNT_IF: no GROUP BY, grouped + NULL predicate ignored, empty input → 0, as window aggregate**; **window functions: ROW_NUMBER, RANK + DENSE_RANK ties, LAG/LEAD with default, running SUM over ORDER BY, aggregates over PARTITION BY, window without PARTITION BY, ROWS BETWEEN sliding frame, ORDER BY referencing a window expression, window function inside arithmetic, window after GROUP BY referencing the aggregate, window function in WHERE rejected**; **column projection push-down: drops unused columns, threads through aggregate, all-columns-used skips rewrite, narrowest-column projection when consumer references zero columns (primitive beats string regardless of declared order), narrowest pick for `SELECT <literal> FROM t LIMIT n`**; **math scalar functions: LN/LOG(x)/LOG(b,x)/LOG10/LOG2/EXP, SQRT/CBRT/SIGN, full trig + ATAN2 + DEGREES/RADIANS + PI()/E(), TRUNC(x[,n]), GREATEST/LEAST skipping NULLs, NULL inputs return NULL, RAND() in [0,1) + seeded determinism**; **statistical aggregates: STDDEV_SAMP/POP and VAR_SAMP/POP on a known dataset, GROUP BY partials → Chan merge, NULL inputs skipped + n<2 → NULL for sample stddev, COVAR_SAMP/COVAR_POP/CORR on y=2x, CORR with zero-variance series → NULL, STDDEV_POP as window aggregate**; **optimizer end-to-end: INNER JOIN with left/right/cross-side WHERE all return the right rows after FilterPushdown; LEFT JOIN preserves null-extended-side semantics — `WHERE r.x > N` filters out unmatched rows correctly without pushing into the right child, and the `r.k IS NULL` anti-join shape still surfaces only the unmatched-left rows; self-join with colliding column names returns the right values for both `a.x` and `b.x`**; **KeyCodec / LongHashMap coverage: GROUP BY single-Long (LongHashMap fast path) / 3×Long packed path / mixed Long+String object-array path, NULL keys grouped together on every path (codec packed-bits + object-array + LongHashMap sidecar slot), DISTINCT over 5-column mixed schema + fixed-width-only schema, JOIN on multi-column key with NULL-skip semantics, JOIN on two-Long packed-codec fast path, JOIN on single-Long key takes LongHashMap fast path (inner + LEFT outer preserves unmatched probe rows including NULL-key probe rows), single-Long GROUP BY with mixed NULL + non-null keys collapses NULLs into one bucket**; **plan 04 collapsing-path correctness (Phase 6's default conservative gates leave aggregates/distinct on the historic merge path; equi-joins broadcast unless both sides are above [[LogicalPlanCardinality.BroadcastBuildThreshold]]): GROUP BY over CSV emits one partition (no exchange inserted); GROUP BY over empty input emits 0 rows; 200 distinct keys all surface exactly once with correct aggregates; GROUP BY → ORDER BY DESC → LIMIT correctly top-Ns; equi-JOIN over small CSV emits one partition (broadcast path); per-side outer-join correctness — LEFT preserves unmatched-left, RIGHT preserves unmatched-build, FULL preserves both, including NULL-key probe rows; 200 distinct join keys all match correctly. The sharded paths themselves are exercised via [[ExchangeExecTest]] (operator-level) and end-to-end when the planner's cardinality gates fire**; **CTEs (`WITH`): simple CTE, outer filter on a column the CTE carries but the outer SELECT drops, a CTE chain where one CTE reads from an earlier one, two CTEs joined, a CTE joined to a real catalog table, the same CTE referenced twice (acts like a self-join), aggregation inside a CTE filtered outside, explicit column-alias list `WITH c(a, b)`, a CTE feeding both arms of a `UNION ALL`, case-insensitive CTE names, a CTE shadowing a like-named catalog view, and the two reject paths — `WITH RECURSIVE` and a column-alias-arity mismatch** (these exercise CTE binding + result correctness; the materialize-on-2+-references behaviour lives in `cte_materialization_test`)**; **spill end-to-end: the plans/bugfixes/01 repro `a JOIN b JOIN b` (whose inner result has a duplicate-named `[k, v, k, v]` schema) returns the same rows under spill (1-byte threshold) as without, exercising grace-hash spill of a join-derived schema through `SqlEngine`****; **`ORDER BY <ordinal>`: sorts by SELECT-list position over a plain SELECT, mixed with a named key and per-key ASC/DESC, over a GROUP BY pointing at an aggregate and at a group key, over DISTINCT (bare column and computed column), over UNION ALL, over a window column and over a window nested in arithmetic, reaching an alias the name form cannot bind (`ORDER BY s` rejected, `ORDER BY 1` sorts), plus the reject paths — out-of-range and zero positions error, and a signed `+9` stays a constant sort key rather than an ordinal**; **grouping parentheses, one case per AST walk that has to see through them: nested inside arithmetic (`(a + b) * c`, the paren node is the Multiplication's left operand) and proved to actually group by differing from the paren-free `a + b * c`; at the top of a select item (`SELECT (a)`, which also pins the output column name as `a`, not `(a)`); doubly nested `((a + b))`; in WHERE and in a JOIN ON; around a bare aggregate `(SUM(x))`, an aggregate inside arithmetic `(a + SUM(b))`, a window function, and a HAVING predicate (the four shapes that reach `childExpressions` — each fails with a *misleading* "non-aggregating position" / "non-window position" error if only the binder learns parens); around a group-key expression (`SELECT (a + b) ... GROUP BY a + b`, the post-aggregation rebinder); a parenthesized ORDER BY ordinal (`ORDER BY (2)` is position 2, `(5)` is out of range); and the negative — a two-element list is a row-value constructor and stays rejected by node name**; **`TRIM`: single-argument form end-to-end, composed with UPPER/LENGTH/a paren and used in WHERE, over an aggregate output (`TRIM(MAX(name))`, which needs TRIM's operand reported by `childExpressions`), and all six extended forms rejected with one clear message — `BOTH/LEADING/TRAILING ... FROM`, bare `'x' FROM`, `BOTH x`, and MySQL's `TRIM(x, 'a')`, the last three of which set neither the FROM keyword nor a specification**; **clauses the binder does not implement (plans/robustness/01), rejected end-to-end on the plan's own data, each case commenting the wrong answer it used to give: `JOIN ... USING`, `NATURAL`, `LEFT SEMI`, a bare `OUTER JOIN` and Oracle `(+)` (cartesian products and outer-as-inner joins), `agg FILTER (WHERE ...)` (which also stopped collapsing), `QUALIFY`, `DISTINCT ON`, `IGNORE NULLS`, `WITH ROLLUP`, `SELECT ... INTO`, a named `WINDOW`, `NOTNULL`, `RLIKE`, ORDER BY / LIMIT after a parenthesized query, `PIVOT`, `* EXCEPT`, `CONNECT BY`, `COUNT(UNIQUE x)`, `WITHIN GROUP`, `ROW_NUMBER(a)`, `SUM(a, 2) OVER`, `CAST(x AS INT[])`, array subscripts and schema-qualified tables; `GROUPING SETS` rejected without leaking `_agg0`. The clauses the plan's second half honours assert their answers in the entries that follow instead**; **`OFFSET`: the plan's page (`ORDER BY a LIMIT 2 OFFSET 1` = (2)(2)), OFFSET without LIMIT, `OFFSET n ROWS`, MySQL's `LIMIT offset, count`, past-the-end and zero, after DISTINCT, after GROUP BY, inside a CTE body; global across a 4-partition x 3-batch view (a per-partition skip would leave 32 rows, not 53) with the ordered skip starting mid-batch; applied to a whole UNION when JSqlParser parks it on the last arm (with and without LIMIT and ORDER BY), the arm's own inside parentheses, and still rejected after a parenthesized query**; **`NULLS FIRST` / `NULLS LAST`: the default placement pinned unchanged (first under ASC, last under DESC), all four explicit combinations, every sort site (plain, above DISTINCT, above a UNION, past a GROUP BY, an ordinal key), the page moving with the NULL under LIMIT/OFFSET, placement per key within ties of the first key, and inside OVER (ROW_NUMBER default vs NULLS LAST, RANK under DESC NULLS FIRST)**; **`SUM` / `AVG` / `MIN` / `MAX(DISTINCT x)`: the plan's case (`SUM(DISTINCT a)` over 1,2,2,3 is 6, not 8), AVG, the extremes unchanged, NULLs skipped, empty input NULL, beside the plain form and `COUNT(DISTINCT)` in one query, per group, in HAVING and in ORDER BY; `STDDEV` / `COUNT_IF(DISTINCT ...)` still rejected**; **an aggregate named only in ORDER BY (`SELECT g ... GROUP BY g ORDER BY SUM(a)`, `COUNT(*)` tied with a second key, mixed with a SELECT-list aggregate) is collected and sorts correctly instead of dying with an internal `NoSuchElementException`, and above a DISTINCT it stays a clean bind error**; **a `HAVING` with no `GROUP BY` aggregates the whole input as one group, where it used to be dropped unless the SELECT list held an aggregate: a count failing it and passing it (four unfiltered rows before), a SUM in it, the one group over an empty input and over a FROM-less row, the SELECT-aggregate form unchanged, and a column outside an aggregate a clean error**; **`COUNT(ALL a)` is `COUNT(a)` (4 over a column with one NULL, where it used to count the NULL), `COUNT(*)` still 5, `SUM(ALL a)` unchanged, and both again under `OVER ()`**; **`ILIKE`: the plan's case (`s ILIKE 'ab'` finds `'Ab'`), a wildcard pattern, `NOT ILIKE` dropping the NULL row, a computed pattern on the per-row path, and `LIKE` still case-sensitive**; **`LIKE ... ESCAPE`: the plan's case (`'A!_b' ESCAPE '!'` finds `'A_b'`), the backslash default unchanged, `ESCAPE ''` turning escaping off, the escape escaping itself and `%`, a trailing escape standing for itself, a letter escape under ILIKE, and the clean errors for a multi-character or non-literal ESCAPE**; **`SELECT` with no `FROM` reads one row, where it used to die with an internal NullPointerException: literals and arithmetic evaluate once, `COUNT(*)` is 1 and `SUM(2)` is 2, a false WHERE or an OFFSET empties it, it works as UNION ALL arms under an ORDER BY and as a CTE body, and a column name and `SELECT *` are clean errors**; **`FETCH FIRST` and `TOP` mean `LIMIT`: the plan's two cases (both returned all four rows), `TOP (n)`, `FETCH NEXT ... ROW`, a count left out meaning one, `TOP 0`, the standard `OFFSET ... FETCH` page, both counting after DISTINCT, FETCH limiting a whole UNION (sorted, and split by JSqlParser across the last arm and the list: 6 rows, skip 5, keep 1), TOP limiting its own arm (parenthesized or the bare last one) and a CTE body; `PERCENT`, `WITH TIES` and FETCH after a parenthesized query rejected; two counts, or TOP with OFFSET, a clean error; a non-literal count a clean error**; **`CURRENT_DATE` and `CURRENT_TIMESTAMP` read one instant per statement: 20,000 rows over 4 partitions x 50 batches carry a single timestamp (a per-row or per-batch clock read would leave many), it lies between the instants read before and after, its UTC date is `CURRENT_DATE`, every spelling (`current_date`, `CURRENT_DATE()`, `CURRENT DATE`, and `CURRENT_DATE ()`, which parses as a call) reads the same instant, an inlined CTE body agrees with the outer query, both compare with stored dates and timestamps, `CAST` to STRING gives the ISO date, and `CURRENT_TIME` is rejected**; **CAST to TIMESTAMP and the TIMESTAMP literal: SQL's space, ISO's `T`, `Z`, an offset and `{ts '...'}` are one instant, a date alone is midnight, a fraction keeps microseconds, CAST between DATE and TIMESTAMP both ways, a timestamp string reaching DATE through TIMESTAMP, `CAST(CURRENT_TIMESTAMP AS DATE) = CURRENT_DATE`, a typed literal comparing correctly within the day, DATE against TIMESTAMP through an explicit CAST, and bind-time errors naming the text for a bad TIMESTAMP literal (even under `WHERE 1 = 0`), a non-ISO DATE literal, and timestamp text cast straight to DATE**; **a DATE or TIMESTAMP meets another type only as a string literal: the within-the-day cases text comparison got wrong (`<`, `>` and `BETWEEN` against SQL-style literals), a date-only literal beside a timestamp as midnight, a CASE switch and IN lists of literals; and bind-time errors for a literal that is no date or timestamp, a string column on either side or in an IN list, DATE beside TIMESTAMP in a comparison, an IN list and a BETWEEN, a number in a comparison and in a CAST either way, and arithmetic on either type, unary minus included**; **numbers and booleans meet a string the way a date does: the cases text comparison got wrong (`i < '10'` and `'10' > i`, a BIGINT past ten digits, `d = '1'` for a 1.0, `b = 'TRUE'`, `HAVING SUM(i) > '9'`, `BETWEEN '1' AND '10'`, `b <> 'False'`, a CASE switch), IN lists taking the widest type among their operands (`i IN (9.5)` and `i IN (4294967305)` both matched a 9), a string literal in arithmetic, and the CAST to STRING that compares text on purpose; and bind-time errors for a literal that is no INT, DOUBLE or BOOLEAN (even under `WHERE 1 = 0`, and `'9.5'` for an INT), a string column beside a number in a comparison either way round, an IN list either way round, a BETWEEN, a CASE switch and a JOIN ON, a string column beside a boolean, a boolean beside a number (and the `CAST(b AS INT)` its error names), and arithmetic or unary minus on text or a boolean**; **CAST reads numbers and booleans strictly: a string literal under a CAST is read at bind time (even under `WHERE 1 = 0`), BOOLEAN reads `true` and `false` in any case and nothing else, and a string column that holds no INT, BIGINT, DOUBLE or BOOLEAN fails while draining with an `IllegalArgumentException` naming the text, where it threw `NumberFormatException` or read `false`**; **BINARY compares by content and DECIMAL by value (plan 02), each case commenting the old answer: `=`, `<>`, `IN`, `BETWEEN` and `NULLIF` over equal bytes in distinct arrays, the same join as a hash key and as a residual predicate, `ORDER BY` (unsigned, a prefix first, the empty array first), `MIN`/`MAX` within one partition and through merge, `GREATEST`/`LEAST` and `RANK` peers; decimals one apart past double's mantissa under `=`, `<`, `IN`, `NULLIF`, `GREATEST`, `ORDER BY`, `RANK` and `MIN`/`MAX` in both row orders and across partitions, and four scales of one value as peers; a number widening to a DECIMAL (an INT column and literal, a DOUBLE column and literal including `0.1`, an IN list, BETWEEN, a CASE switch, GREATEST, a BIGINT literal past double's precision) where each threw `CAST to decimal not implemented`, and a NaN meeting a decimal as an error naming it; and GROUP BY, COUNT(DISTINCT), DISTINCT, UNION, PARTITION BY and equi-join agreeing with `=` for both types, decimal keys and MIN/MAX read back at the declared scale**; **two BIGINTs past 2^53 compare as longs: `=`, `<`, a literal, an IN list, BETWEEN, NULLIF, GREATEST, ORDER BY and RANK, and the hash join and the residual join agree**; **a BINARY is not read as text: CAST from it, a CASE result and WHERE / COUNT_IF that would cast it, LIKE on either side, `\|\|` on either side and the string functions are bind errors**; a `DECIMAL` column spills through sort, aggregate (decimal key), DISTINCT, grace-hash join (decimal key) and window (decimal `PARTITION BY`) and matches the run without spill (each threw before the parquet writer could write decimals); **`ABS` of a decimal** is exact at its own scale and `MIN` / `MAX` over it is not NULL (`absOfADecimalIsExact`: it computed in `DOUBLE`, read back as a `Double` in a `DECIMAL` column, and `MAX(ABS(k))` finished from an empty slot); **a NULL-typed column spills** (`nullTypedColumnsSpillEndToEnd`: a sort over `SUM(NULL)`, a NULL `GROUP BY` and `DISTINCT` key, a grace-hash join and a `UNION` carrying a NULL column, and a window, each with spill forced against the run without; all but the window threw `Writing null to Parquet not supported`); **`DECIMAL` arithmetic, `SUM` and `AVG` are exact and typed** (`decimalSumAndAvgAreExact`, `decimalArithmeticIsExactAndTyped`, `decimalOverflowIsAnError`, `decimalResultsSpill`): `SUM` and `AVG` per group, over values past double's mantissa, `DISTINCT` and as windows, with their `DECIMAL(38,s)` and `DECIMAL(p+4,s+4)` types (`SUM` added in DOUBLE and kept its input's type, `AVG` was a DOUBLE); `+ - * / %`, `MOD` and unary minus, each with its declared type, beside an INT, a BIGINT, a literal (negated too) and NULL, a quotient rounding half up, division by zero NULL, a sort key on the row path (each threw `IllegalStateException`); a constant beside a DECIMAL computed in DECIMAL (`a * (1 + 0.05)`, `a + 1.5 * 2.5`) with integer arithmetic in one keeping its integer answer (`10 / 4` is 2); a DOUBLE column, an explicit CAST to DOUBLE and an INT column plus a number beside a DECIMAL each a bind error naming the CAST; overflow of `b + 1` and of `SUM(b)` past 38 digits an error naming the value; and a decimal aggregate and arithmetic result spilling through an aggregate and a sort to match the run without; **arithmetic on NULL alone is NULL** (`arithmeticOnNullAloneIsNull`: `NULL + NULL`, `- NULL`, a WHERE over `NULL / NULL` and a SUM of `NULL % NULL`, each of which threw `IllegalStateException`) |
| `sql/exec/cte_materialization_test` | CTE materialization policy + the compute-once proof. **compute-once**: a counting `CatalogView` under a 3-reference CTE (`c x JOIN c y JOIN c z`) is read exactly once per partition (not 3×) and the result equals the inline self-join; **empty CTE referenced twice** still reads the source once; **dependent CTEs** (`b` reads `a`, both ≥ 2 refs) read the source once (`b` sees materialized `a`); **UNION arms sharing a CTE** read the source once; **determinism fence**: a `RAND()` body referenced twice agrees on every row (`x.r = y.r`), which inlining would not; **planner controls**: a single-ref CTE introduces no `MaterializedView` scan and never executes the body during resolve, a two-ref CTE introduces the shared MV scan; **COUNT(\*) fast-path**: `SELECT COUNT(*)` over a materialized CTE collapses to `CountStarMetadataExec` (MV `exactRowCount`, no scan) |
| `sql/plan/expr_batch_test` | Parity gate for every `Expr.evalVec` override, plus a strict one for CAST to DATE / TIMESTAMP (from SQL-style text with NULLs, and between the two types), which also requires `eval` to return the very `LocalDate` / `Instant` the vector boxes to, because `normalize` maps dates to epoch days and hid an `Int`. Builds 64-row batches with no-null / every-other-null / all-null placements and asserts `eval(batch, row) == evalVec(batch).getBoxed(row)` (after normalizing to the declared `dataType`) for every row. Covers: `LitExpr` per primitive type + NULL literal; `ColRefExpr` per column type; `CastExpr` between representative numeric/string pairs; `UnaryOpExpr` (`+`, `-`, `NOT`); `BinOpExpr` AND/OR 3VL truth table, comparisons (`= == <> != < <= > >=`), arithmetic (`+ - * / %`) including divide-by-zero and both-sides-null, `\|\|` string concat with NULLs, cross-numeric Int-vs-Long comparison; `IsNullExpr` over mixed / no-null / all-null inputs and over a computed expression; `FuncExpr` for the entire `Funcs.applyVec` fast-path list (`COALESCE` 2-arg / literal fallback / all-null / string, `LENGTH`, `UPPER`/`LOWER`, `TRIM`, `CONCAT` null-propagating + 3-arg, `SUBSTRING` 2-arg + 3-arg, `ABS` int + double, `FLOOR`/`CEIL`/`CEILING`, `ROUND` 1-arg + 2-arg, `TRUNC` with null-scale propagation, `IF`, `NULLIF`) plus a `SQRT` case that exercises the unknown-function fallback; `CaseExpr` 2-branch + ELSE, no-ELSE NULL default, every-row-claimed short-circuit, NULL value in branch, column-expr value; `InListExpr` literal lists per type, NULL-in-items 3VL, cross-numeric literals (Int col against Long literals via the typed HashSet path), column items (general path); `LikeExpr` literal `%` suffix / `_` single-char / `%` in middle / NULL pattern → NULL / column pattern; one nested composite (`(a + b) > 0 AND NOT(c IS NULL)`) to verify overrides compose; **a nested-FloatType regression (`(a*b)+c` and `ABS(a*b)-c`) proving `eval` narrows float arithmetic per node, found by the parity fuzzer**. Any new `evalVec` override MUST extend this suite before merging; ILIKE parity on the literal fast path (including a non-ASCII `Föö` / `FÖÖ` pair, which needs Unicode case folding) and on the column-pattern path, plus a check that ILIKE and LIKE differ on `'FOO0'` against `'foo%'`; a custom escape character on the literal and column-pattern paths, and escaping turned off; **decimal and binary comparisons, pinned to the right answer as well as to parity, since both paths once agreed on a wrong one**: decimals past double's mantissa and at four scales, a decimal against an uncast BIGINT and DOUBLE (NaN above every decimal, the infinities at the ends), unsigned byte order with prefixes and the empty array, decimal literals in an IN list (which skip the hoisted set of doubles that held `...002` for `...001`) and a BIGINT probe against decimal items; **`ABS` of a decimal**, exact at the value's own scale on both paths (`funcAbsDecimal`; both computed in `DOUBLE` and the vector path returned a `DoubleVector` under the `DECIMAL` type, the widened parity fuzzer's first finding); **two integers compared as longs past 2^53** on both paths, an INT beside a BIGINT, and the IN list's hoisted set keyed on `longValue`; **decimal arithmetic** (`binOpArithDecimal*`, `unaryMinusDecimal`): `+ - * / %` over a DECIMAL(5,2) and a DECIMAL(10,4) with each declared type pinned, every exact answer at that type's scale and a quotient rounded half up; a DECIMAL(38,18) product rounding half up where its type gives way to six fractional digits; overflow an `ArithmeticException` on both paths; and negation; **arithmetic and negation on NULL alone** (`arithOnNullAloneIsNull`), NULL on both paths where the vector path threw |
| `fuzz/row_oracle_test` | `RowOracle`, the comparator every fuzz property ends in, checked on its own: a comparator that is too loose makes a property vacuous and one too strict makes it red on a correct engine, and a failing property cannot say which. Pins each type's equality: a byte array by **content** (two arrays with one content are equal; a prefix, a high-bit pair and the empty array against NULL are not), a decimal by **value, not scale** (`1.0` = `1.00`, `100` = `1E+2`, while `1.000000000000000001` and `...002`, one double, stay two values, and a DOUBLE where a decimal belongs is a mismatch), and a DATE / TIMESTAMP as the `LocalDate` / `Instant` the engine boxes (an epoch-day `Int` or epoch-micros `Long` is a mismatch). `agreesWithOpsEqOnEveryNonFloatType` holds `scalarEquals` to `Ops.eq` over a pool per type (BIGINTs past 2^53, decimals at several scales, equal-content byte arrays, a leap day, instants a microsecond apart); FLOAT and DOUBLE are left out because the oracle departs from `Ops.eq` there on purpose (`NaN` agrees with `NaN`). Also the whole-row `multisetEquals` checks: order-insensitive, tolerant on floating-point columns only, multiplicity and NULL count, byte-array and decimal rows grouped by the engine's equality, and a diff that renders bytes in hex. Run against the comparator as it stood before plan 04, 6 of its 10 tests fail |
| `fuzz/expr_parity_fuzz_test` | Property-based generalization of `expr_batch_test`. Generates random type-correct `Expr` trees over random `ColumnarBatch`es (varied null masks incl. all-null / no-null, the 8192 capacity boundary + 1-row, capacity-pad tails), over all ten of the engine's value types (DECIMAL at three declared types, one per parquet layout), and asserts `eval` and `evalVec` agree per row — same normalized value, both NULL, or both throw the same exception class — via `oracle/ExprParity`. Also: shrinker minimizes to a single failing node, same-seed reproducibility, the generator's own type invariant, and an **IEEE special-value coverage guard** (`generatorReachesIeeeSpecialValues` — over 4000 seeds, a float/double operand must reach an ordering comparison (~4.5% of seeds) and an `IN` list (~1.5%), and `NaN` and `-0.0` must each actually reach both (~0.7% and ~0.45%); those two shapes and those values were all gated OUT of the generator while the row and vector paths disagreed on them, so the guard is what stops any of the three exclusions returning unnoticed — un-gating the shapes alone would leave the property green over a corpus that cannot break them), and one **type coverage guard per type the corpus drew none of before plan 04** (`datesReachComparisonsAndInLists`, `timestampsReachComparisonsUnderAMillisecondApart`, `binaryReachesComparisonsWithEqualContentAndHighBitPairs`, `decimalsReachComparisonsAtTwoScalesAndOfOneDouble`), each asserting the shape half (the type reaches a comparison and an `IN` list) and the value half (on some row a comparison or `IN` meets two distinct byte arrays of one content, two one high bit apart, one decimal at two scales, two decimals of one double, two instants under a millisecond apart). They share one tally of `ExprGen.typeCoverage`, which names the pairs with `DataGen.ValueTag`, over 20000 seeds, because the value chains are thin: measured, each type is compared on 228-346 seeds and reaches an `IN` on 124-164, and the values meet on 76 (one content), 46 (high bit), 26 (two scales), 20 (one double) and 104 (sub-millisecond) seeds; bounds sit ~3x below. The widened corpus found `ABS` of a decimal computing in `DOUBLE` at seed 98 (see `expr_batch_test`). **Decimal arithmetic** (`decimalArithmeticRoundsAndMeetsZeroDivisors`): each of `+ - * / %` over DECIMALs on 228-294 of 20000 seeds and negation on 159 (the shape), and answers that had to round to their declared scale on 185 and zero divisors on 58 (the value), read off every row by `ExprGen.decimalArithCoverage`; bounds ~3x below. Default run is a small fixed-seed batch (`Props.DefaultSeeds`); see [Property-based testing](#property-based-testing-fuzz) for long campaigns. Exercises the full `evalVec`-override surface + the `Funcs.applyVec` fast-path list; `ExprGen` makes 30% of its LIKE nodes ILIKE, so case-insensitive matching is held to eval/evalVec parity on both pattern paths; LIKE nodes also draw their escape character (backslash, `!`, or none) over a pattern pool that now includes `!` escapes |
| `fuzz/mode_differential_fuzz_test` | Property-based generalization of the `*SpillTest` parity checks. Generates an in-memory dataset (`DataGen` — narrow value domains so GROUP BY / DISTINCT keys collide, tunable NULL density, 0-row / 1-row boundaries) and a single-table `SELECT` (`QueryGen` — projection / WHERE / GROUP BY / HAVING / aggregates incl. COUNT[DISTINCT] / MIN[MAX] over any column type / DISTINCT / ORDER BY / `LIMIT` over a total ORDER BY, which reaches `LocalLimitExec` + `GlobalLimitExec`), then runs the SAME `(data, query)` under spill on/off, metrics on/off, and several partition/batch layouts (1 big batch, tiny batches, many partitions, empty partitions) and asserts the result MULTISETS match via `RowOracle.multisetEquals` (exact on every column except `Double`, which compares with a tolerance because order-dependent aggregates accumulate in `double`). ORDER BY queries additionally assert each run is sorted. Also: a bind-reject-rate guard, a **corpus shape-coverage guard** (`generatedCorpusCoversTheInterestingShapes` — LIMIT reached, `MIN`/`MAX` over a Boolean column reached (~3.5% of seeds; the shape this fuzzer found broken under spill and that was then gated OUT of the generator until the engine was fixed, so the assertion is what stops the exclusion returning unnoticed), and columns spread across the no-NULL / some-NULL / all-NULL densities, with all-NULL asserted to stay a minority; a mis-mapped weight table once collapsed ~48% of columns to all-NULL, which grouped and deduped to nothing and quietly gutted the corpus), an **IEEE special-value coverage guard** (`generatorReachesMinMaxOverIeeeSpecialValues` — over 10000 seeds, `MIN`/`MAX` over a float column must be generated (~4% of seeds) and that column must actually CARRY a `NaN`, a `-0.0` and an infinity (~0.35-0.55% each; the block grew from 2000 when columns widened from six types to ten and the infinity count fell to 3); the shape half was always there, the value half was missing for as long as `MIN`/`MAX` over a `NaN` was order-dependent, so this property was the right one over a corpus that could not produce the input), same-seed reproducibility, and hand-written regressions (GROUP BY / DISTINCT / ORDER BY-with-ties / empty-input global aggregate / COUNT(*) metadata fast path / float-aggregate tolerance) plus a top-n regression (`LIMIT` over a total order with the cutoff between two identical rows); the `multisetEquals` unit checks live in `row_oracle_test`. Default budget is small (the engine runs ~7× per case); see [Property-based testing](#property-based-testing-fuzz) for campaigns; **`OFFSET`**: `QueryGen` emits it on top-n shapes (with and without `LIMIT`); `generatedCorpusCoversTheInterestingShapes` asserts it is generated and `offsetIsGeneratedAndObservable` runs each OFFSET query without its tail and asserts enough skips land strictly inside the result (0 and past-the-end test nothing); `regressionTopNOffsetOverTotalOrder` pins a cutoff between identical rows; **`NULLS FIRST` / `NULLS LAST`**: `QueryGen` gives ORDER BY keys an explicit placement half the time, and the oracle's sortedness check now asserts where the primary key's NULLs land (the default included), not only that its non-NULL values are monotonic; `nullPlacementIsGeneratedAndObservable` counts placed primary keys whose result holds both NULL and non-NULL values, the only results where placement is visible; `regressionExplicitNullPlacementAcrossModes` covers all four direction x placement combinations; **`SUM` / `AVG(DISTINCT)`**: `QueryGen` marks some SUM / AVG aggregates DISTINCT (drawn last); `distinctAggregatesAreGeneratedAndObservable` re-runs each such query with DISTINCT dropped over 2000 seeds and counts the answers that change; `regressionDistinctSumAvgAcrossModes` pins duplicates within and across partitions; **every column type**: `DataGen` draws DATE, TIMESTAMP, BINARY and DECIMAL columns and `QueryGen` compares them and puts them in IN lists, and one guard per type (`datesReachComparisonsOrderByGroupByAndMinMax`, `timestampsReachComparisonsOrderByGroupByAndMinMaxUnderAMillisecondApart`, `binaryReachesComparisonsOrderByGroupByAndMinMaxWithEqualContentAndHighBitPairs`, `decimalsReachComparisonsOrderByGroupByMinMaxAndSumAtTwoScalesAndOfOneDouble`) asserts the type reaches a comparison, an IN list, an ORDER BY key, a GROUP BY key and a `MIN`/`MAX` argument (and a decimal `SUM`/`AVG`), and that the column it reads there holds the type's breaking pairs (`DataGen.valueTags`); they share two generation-only tallies over 10000 seeds, where each type reaches each site on 208-726 seeds and the thinnest value chain, `MIN`/`MAX` over two decimals of one double, on 23; bounds sit ~3x below, with the full table in the test's comment. Decimal arithmetic reaches a projection or filter on 492 seeds, 140 of them over decimals a double adds wrong (`decimalArithmeticReachesDecimalsADoubleAddsWrong`), and a decimal `SUM`/`AVG` holds such values on 114. The widened corpus found a NULL-typed column failing every spill at seed 1796 (see `sql_engine_test`) |
| `fuzz/metamorphic_fuzz_test` | Property-based **metamorphic** gate over multi-relation SQL — finds SEMANTIC bugs (wrong answers every execution mode agrees on, which mode-differential is blind to). `MetaQueryGen` builds a `RelEnv` of 2–3 base relations sharing one narrow join-key domain (of any of the ten types but FLOAT, DOUBLE and BOOLEAN; payload columns of all ten) plus a scope-/type-correct, paren-free `MetaQuery` (left-deep joins of every kind incl. self-joins, WHERE, a projection or GROUP BY core, an optional `UNION`/`UNION ALL` arm, optional CTEs incl. referenced-twice, an optional window, and an optional **top-n** `ORDER BY`-over-every-output-column `LIMIT`). Five relations, each decided by SQL semantics alone (no reference engine): **TLP** — `Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS NULL)` as multisets, partitioning `Q`'s OUTPUT via a CTE wrapper so it is sound for joins / aggregates / windows / unions / CTEs with no aggregate decomposition (partition key restricted to type-reliable non-float output columns); **NoREC** — `COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0 END)` over a single-relation predicate (empty-relation NULL→0 normalized); **multi-relation mode agreement** — the join extension of `mode_differential` (same result multiset across partition/batch layouts, metrics on/off, and spill); **join commutativity** — `A <kind> JOIN B ON p` ≡ `B <flipped kind> JOIN A ON p` with the SELECT list, WHERE and GROUP BY reused verbatim (sound because every reference is alias-qualified), the only relation that varies which side of a join is the BUILD side; **aggregate decomposition** — one-shot aggregation ≡ group-then-re-aggregate (`MIN`→`MIN`, `MAX`→`MAX`, `COUNT`→`SUM`, integral `SUM`→`SUM`), run in heap AND under spill, which drives the partial/final merge and spill-serde paths a global aggregate never reaches — the relation that generalizes the `MinMaxState` Boolean-slot bug; the spill mode runs for join queries too (it used to be gated off around the grace-hash join spill NPE, now fixed via positional spill names — see [gotchas.md](gotchas.md) and plans/bugfixes/01). Plus a bind-reject-rate guard (≈0 over 500 seeds), a shape-coverage guard (joins / unions / CTEs / windows / aggregates / top-n LIMIT / MIN-MAX-over-Boolean all reached), a non-vacuity guard for join commutativity (it must actually run, not skip, on >1/5 of seeds — it has nothing to check for a join-free query and skips a windowed one), a coverage guard for aggregate decomposition (`aggDecompositionCoversEveryColumnType` — it must never skip, and every payload column type must reach its `MIN`/`MAX` decompositions, since `MIN`/`MAX` picks its accumulator slot from the type; reads `AggDecomposition.coveredTypes`, so it costs no query), an IEEE special-value coverage guard for the same relation (`aggDecompositionReachesIeeeSpecialValues` — over 10000 seeds the aggregated columns must carry a `NaN`, a `-0.0` and an infinity; this is the relation that drives `MinMaxState.merge`, and it is the one that would have caught `MIN`/`MAX` over a `NaN`-carrying column had `MetaQueryGen`'s payload pool not been all-finite), a `RANK`/`DENSE_RANK` order-key coverage guard (`rankWindowsReachIeeeSpecialOrderKeys` — the peer test those two apply is a float-comparison site, and it is the thinnest chain in the corpus: over 100000 seeds a rank window lands on 2045, one ORDERED BY a float column on 301, one whose float key carries a `NaN` on 38 and a `-0.0` on 27; the block is 100000 rather than 2000 because at 2000 the `NaN` count is about 1 and cannot tell rare from gone (it was 20000 until relations widened from six column types to ten and the `-0.0` count over those seeds fell to 3), and it also asserts every ordered window fills in `WinItem.orderCols`, without which a shape would drop out of the tally silently), shrinker termination + reduction, same-seed reproducibility, and hand-written regressions (LEFT/FULL join null-extension, aggregate-output partition, `NOT IN` with NULL, `UNION`/`UNION ALL`, CTE-referenced-twice, window-column partition, self-join mode agreement, LEFT-becomes-RIGHT join commutativity, Boolean `MIN`/`MAX` decomposition, a top-n `LIMIT` whose cutoff splits two identical rows, and the shape checks proving a `LIMIT` drops out once the order stops being total). Default budget is small (each case runs the engine several times); campaign via `metamorphic_fuzz_campaign`; **`OFFSET`** on the top-n shape: counted by `generatorCoversShapes`, made observable by `offsetIsGeneratedAndObservable` (same value-half check over 2000 seeds), and pinned by `regressionTopNOffsetAcrossTiedRows` under TLP and mode agreement; **`NULLS FIRST` / `NULLS LAST`** on the top-n ORDER BY: counted by `generatorCoversShapes`, and `nullPlacementIsGeneratedAndObservable` re-runs each placed query with the opposite placement and counts the ones whose answer changes; `regressionTopNNullPlacement` pins a LIMIT cutoff among NULL keys under TLP and mode agreement; **every column type**: one guard per type (`datesReachJoinsComparisonsOrderByGroupByMinMaxAndTlp`, `timestampsReachEverySiteUnderAMillisecondApart`, `binaryReachesEverySiteWithEqualContentAndHighBitPairs`, `decimalsReachEverySiteAtTwoScalesAndOfOneDouble`) asserts the type is a join key, reaches a comparison, an ORDER BY (a window's or a top-n's), a GROUP BY key, the aggregate-decomposition relation's `MIN`/`MAX` and a TLP partition key, and that the base-relation column it reads at each site but the TLP key holds the type's breaking pairs; one generation-only tally over the campaign's 20000 seeds (about a second), where each type reaches each site on 501 (a BINARY TLP key, which partitions only against another BINARY column) to 4603 seeds and the thinnest value chain, a GROUP BY over two decimals of one double, on 61; bounds sit ~3x below, with the full table in the test's comment; **where a decimal is added up** (`decimalsAreAddedUpOverValuesADoubleAddsWrong`): arithmetic on 1341 seeds, a SUM or AVG on 732 and the decomposition's decimal `SUM` on 4562, over columns holding decimals a double adds wrong on 260, 110 and 879; and `generatorCoversShapes` reads 4000 seeds, where `MIN`/`MAX` over a Boolean column, its narrowest shape, is 38 against a bound of 12 |
| `fuzz/sharded_mode_fuzz_test` | The metamorphic gate (TLP / NoREC / multi-relation mode agreement / join commutativity / aggregate decomposition — same generators + oracles + shrinker as `metamorphic_fuzz_test`) re-run in a **separate JVM under sharded planning**. Since `MetaQueryGen` emits top-n queries, this is also where `GlobalLimitExec` over a sharded child is exercised. The sharding levers `LogicalPlanCardinality.{MinShardableSize, BroadcastBuildThreshold}` are `val`s frozen at class load, so this target sets both to `1` via `jvm_flags`, forcing every query onto the shuffle-join + sharded-aggregate/distinct paths the default gate never reaches. It also pins `shard_count=4`, so every breaker fans out to four shards and the target exercises real nested multi-shard plans; the default seed budget is deterministic, so this stays green in seconds. The K>1 campaign-volume hard-deadlock (the helpJoin cycle, reproduced at `FUZZ_SEEDS=20000` — plans/bugfixes/02e) is FIXED by bottom-up exchange pre-materialization (plans/bugfixes/02f): the same 20000-seed campaign now completes (still run campaigns under `--test_timeout` as basic hygiene). The old exchange-monitor serialisation cliff is also gone (`ExchangeExec`'s direct-use fallback materializes via CAS-claim + latch since plans/bugfixes/02d; tree-shaped work-helping pinned by `core/scheduler_test`; a `ForkJoinPool.managedBlock` compensation variant tried in 02b wedged the pool even faster and was reverted); multi-shard routing is also covered operator-level by `exchange_exec_test`, and the wedge topology itself by `sql/exec/exchange_deadlock_stress_test`. `shardingGateIsActive` asserts the flags actually arrived (else the target would silently re-run the default gate). Scope is narrow — do the metamorphic relations still hold under sharding? — so bind-reject / coverage are NOT duplicated here. Campaign via `sharded_mode_fuzz_campaign` (same `jvm_flags`) |
| `fuzz/dag_scheduler_fuzz_test` | Property-based gate over the **job-level DAG scheduler** (`DataJob.runUnifiedDag`) — the layer above SQL execution. `JobDagGen` builds a whole `DataJob`: 1–3 CSV inputs (some pointed at a path matching no file, so their load fails) and up to ten `SQLTask`s whose FROM clauses reference only inputs and EARLIER tasks (acyclic by construction), each rigged to succeed, to fail in the worker (it selects a column that does not exist, so the statement still parses and `TaskDag` still recovers the same edges), or to fail a validation. Task bodies are row-monotone `UNION ALL`s of unfiltered scans, so the injected outcome is exact. Per-task writer format, spill and metrics knobs are drawn too, so concurrent tasks hit the CSV and parquet writers, the shared spill directory and the metrics snapshot at once, and a share of tasks acquire a dependency through a VALIDATION's FROM rather than the main SQL (a separate edge source in `TaskDag.build`). `oracle/DagSchedule` then decides the run against what the DAG alone implies, with no reference implementation: per-task status (a task with any unhealthy upstream is `Skipped` and nothing else — transitively), results complete and in declared order, dependency ordering by timestamp (`enqueuedAt >= upstream.finishedAt` on every edge), `enqueuedAt <= startedAt <= finishedAt` with a `Skipped` task doing no work at all, skip reasons that name a real upstream, `JobResult.succeeded` iff every task succeeded with no consistency warning, a `_run.json` on disk whose status matches the in-memory result for every terminal status, and **liveness** — the job runs on a watchdog thread and a run that stops making progress fails with a thread dump instead of hanging. Coverage guards assert every terminal status is reached, that DAGs get deep enough for a transitive skip, that validation-induced edges occur, and that independent tasks really do overlap in wall-clock time. Found two real runner defects — the memory-only-feeder run-time failure and the
manifest that could not reach a Failed or Skipped task's `_run.json` (see
[gotchas.md](gotchas.md)). Campaigns: `dag_scheduler_fuzz_campaign`, `sharded_dag_scheduler_fuzz_campaign` (sharding gates on, so task bodies build exchanges while the scheduler fans out tasks) and `starved_dag_scheduler_fuzz_campaign` (the same, with `scheduler.parallelism=2` — up to ten tasks each fanning nested work onto a two-thread pool, the pool-starvation shape) |
| `sql/plan/logical_plan_cardinality_test` | `LogicalPlanCardinality.estimate` for every plan node: scan / project / filter selectivity (per shape: `=` / range / `IS NULL` / `LIKE` / `NOT` inversion / default), AND multiplies / OR widens, limit caps both ways, distinct shrinks, aggregate without group keys collapses to 1, aggregate with group keys stays bounded by input, joins per kind (Inner=max / Left=left / Right=right / Full=sum) + None propagates when either side unknown, union sums and propagates unknown, sort / window pass through, `filterSelectivity` constants pinned to named `private[plan] val`s; `LogicalOffset` subtracts from its child's estimate, bottoms out at zero, propagates `None`, and composes under a `LogicalLimit` |
| `sql/plan/filter_pushdown_test` | `FilterPushdown` over every join kind: inner pushes left-only / right-only conjuncts into the matching child and re-indexes right-side ColRefs; inner with cross-side or mixed conjuncts splits correctly; stacked Filter(Filter(Join)) flattens before pushing; LEFT outer pushes left-only conjuncts and refuses right-only (including the `r.x IS NULL` anti-join shape); RIGHT outer is symmetric; FULL outer never pushes; cascaded inner joins push all the way to the relevant leaf scan with the right per-side shifts; filter above an aggregate is not pushed across; idempotent when re-run |
| `sql/plan/column_projection_pushdown_test` | Join pruning: parent + join-key columns survive on each side, columns referenced by neither are dropped; `SELECT *` leaves the join unchanged; self-join keeps colliding names on both sides (correctness over optimality); cross-side filter above a join still allows both sides to prune to the union of (parent + filter + join-key) refs; the plan-time `verify` rejects an out-of-range `ColRefExpr` with a targeted `IllegalStateException` |
| `sql/plan/unsupported_clauses_test` | The rejection pass's audit (`UnsupportedClauses`) held against the two things it can drift from. It reads `LogicalBuilder`'s compiled class files (the constant pool, no bytecode library) to learn which JSqlParser classes the binder matches on and which getters it calls, then asserts: every public getter **and public field** of every audited class is read by the binder, rejected, or inert, exactly once (a partial rejection must be on a getter the binder reads, a total one on a getter it never reads); every concrete class the binder names or receives from a getter it calls is audited (`Distinct` arrives that way and is never matched on); every JSqlParser subclass of an audited class is audited or listed in `UnsupportedClauses.unreachable` with the reason; every rejection's example SQL reaches *that* rejection through `LogicalBuilder.build`; a corpus of supported SQL, including every spelling the audit calls inert and every clause plan 01's second half honours, passes the walk untouched (the negative control for presence tests like `Table.getDBLinkName`'s, and for a partial rejection's test widening back over what the binder now honours); and no rejection leaks an internal slot name such as `_agg0`. Each failure message says what to do. A JSqlParser upgrade that adds a getter fails it until the getter is classified |
| `sql/exec/join_swap_test` | `PhysicalPlanner.shouldBuildRight` decision matrix — inner left-smaller swaps, right-smaller stays, near-equal stays below 2× threshold, just-above-threshold swaps, no-estimates stays default, LEFT outer never swaps, RIGHT outer always swaps, FULL outer never swaps. End-to-end: inner-join row identity under both swap directions, LEFT outer preserves unmatched-left, RIGHT outer preserves unmatched-right under swap, FULL outer emits both unmatched sides, residual non-equi predicate under swap. Nested-loop size guard: small × small allowed, large × large refused with a specific error message, unknown-size fall-through allowed. **Plan 04 Phase 6 broadcast-vs-shuffle gate: equi-join with both sides small (< `BroadcastBuildThreshold`) does NOT wrap either child in `ExchangeExec` and emits `numPartitions = 1` (broadcast path); equi-join with one side small still skips exchange even when the other side is huge (small-side broadcast wins); equi-join with both sides above threshold wraps BOTH children in `ExchangeExec` and `numPartitions == defaultNumShards` (sharded build + probe); unknown estimate on either side falls back to the collapsing path** |
| `sql/exec/sort_exec_test` | Direct `SortExec` coverage. The K-way heap merge isn't stable, so assertions check sortedness under a local oracle comparator plus multiset equality, never position-by-position equality. Cases: overlapping-range multi-partition merge, multi-key ASC/DESC, NULLs-first under ASC and NULLs-last under DESC, empty partitions mixed with full, all-empty input, single-partition shortcut, **small-N fast-path branch (120 rows, below the 4096-row `SmallNThreshold` → concat+`Arrays.sort` fallback)**, 7200-row random-key cross-partition merge above the threshold, a default-capacity batch-shape check confirming the lazy emit streams `ColumnarBatch.DefaultCapacity`-sized batches, and a 4096-row monotone smoke matching the jaffle-shop sort regression target; explicit NULL placement against direction (ASC NULLS LAST, DESC NULLS FIRST, the two the default never produces) on the small-N path, and a 6000-row two-key case with NULLs in both keys through the heap merge, checked by the local oracle comparator, which implements placement itself rather than calling `SortKey.compare` |
| `sql/exec/exchange_exec_test` | Direct `ExchangeExec` + `HashPartitioner` coverage. Asserts the three contracts every downstream operator depends on (no implementation-specific shard numbers asserted — only the equivalence-class structure of the routing): **permutation** (total rows in == total rows out across every Int / multi-column / String / NULL-key shape, with K ∈ {1, 4, 8, 16}); **co-location** (every row with the same key value lands in exactly one shard, including under `LowCardinality keys < K` where most shards stay empty); **NULL routing** under both `NullsToLast` and `NullsToZero` policies, with multi-column NULL detection (any NULL key column routes the row). Also: output schema matches child, out-of-range / negative partition index throws, zero-shards is rejected at construction, repeated `execute(s)` calls produce identical shard contents (lazy materialization happens once), independent ExchangeExec instances over the same input route identically (partitioner is pinned), concurrent multi-thread `execute(s)` reads via `Scheduler.submitAndAwaitAll` preserve row counts + collocation, and 4096 random Int keys spread to every shard at K=8 (catches a regression where `Int.hashCode % K` clusters contiguous small keys — fixed by `HashPartitioner.fmix32`). Materialization is lazy and exactly-once under the CAS-claim + latch (plans/bugfixes/02d): 32 external threads racing `execute(s)` on one exchange drive the child's `execute(p)` exactly `numPartitions` times with all same-shard readers agreeing on the rows, and a throwing child propagates the wrapped failure to every concurrent caller (original cause preserved in the chain, the failing partition executed once — a failed materialization is never retried, later callers get the same failure). Publication is exactly-once; on the rare winner-stack-reentrancy path (a helpJoin guest, unreachable from external callers) computation can duplicate without publication — see [gotchas.md](gotchas.md). **The 02f engine pass (`PhysicalPlanner.preMaterializeExchanges`): a diamond-shared exchange (one instance, two parents via `UnionExec`) publishes exactly once with both parents reading it, and the pass sees through `MeteredPlan` wrappers (metrics-wrapped exchange published before consumption)**; **byte-array keys co-locate by content and decimal keys across scales (`1`, `1.0` ... `1.0000`, and a decimal past double's mantissa), and the grace-hash / window spill bucket hash (`HashJoinExec.columnHashCode`) agrees with equality for both (plan 02)** |
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
has happened here three times: a mis-mapped weight table gutted the null-density
distribution (`generatedCorpusCoversTheInterestingShapes`); `MIN`/`MAX` over a
Boolean column was gated out of the generator around an engine bug and had to be
asserted back in; and the float value pools were all-finite, so the
layout-differential properties could not produce the `NaN` that made `MIN`/`MAX`
order-, fan-out- and flush-order-dependent — the shape was generated all along,
the value never was. The third is the one to remember when a property "already
covers" something: covering the SHAPE is not covering the VALUE, and a guard on
either half alone can pass over a corpus that tests nothing. The recipe for writing one is in
[extending.md](extending.md#add-a-metamorphic-relation). It is a `testonly`
`scala_library` named `fuzz` plus the
`expr_parity_fuzz_test`, `mode_differential_fuzz_test`, `metamorphic_fuzz_test`,
`sharded_mode_fuzz_test` and `dag_scheduler_fuzz_test` targets, and
`row_oracle_test` for the comparator they share. The pieces:

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
  on `Double` columns only (see [gotchas.md](gotchas.md)). Equality is the
  engine's `=`, not the JVM's: a byte array compares and hashes by content
  (Scala's `==` on arrays is identity), a decimal by value whatever its scale
  (`compareTo`, matching plan 02's `Ops.eq`; spill and the GROUP BY read-back
  legitimately respell `1.5` as `1.50`), and a DATE or TIMESTAMP as the
  `LocalDate` / `Instant` both paths box, with no conversion from an epoch
  count. `row_oracle_test` pins each and holds `scalarEquals` to `Ops.eq` on
  every non-float type.
- `ExprGen` — generates a random `ColumnarBatch` + a typed `Expr`, **type-correct
  and total by construction** (asserts its own `dataType` invariant; excludes
  `RAND()`, casts that can throw — text into a number or a date, a FLOAT or
  DOUBLE into a DECIMAL — and NULL `ROUND`/`SUBSTRING` scale args). Columns and
  expressions take all ten of the engine's value types (`GenTypes`, DECIMAL at
  `DECIMAL(9,2)`, `(18,4)` and `(38,18)`, one per parquet layout, drawn as one
  type with `pick`). A type takes part in what the engine gives it: a DATE or
  TIMESTAMP meets only its own type, a BINARY is never read as text, and there
  is no arithmetic on a DATE, TIMESTAMP or BINARY; comparisons, `IN`, `IS
  NULL`, the conditional functions and the total CASTs take all four. A DECIMAL
  also takes `ABS`, negation and arithmetic (`genDecimalArith`), built as the
  binder builds it: each side a decimal leaf, an INT or BIGINT leaf under a CAST
  to the DECIMAL its every value fits, or a number as the decimal of its digits,
  the node declared as `Decimals.resultType` says, and an operator drawn only
  where that type holds every answer its sides can give (`holdsEveryAnswer`),
  so no answer overflows and the generator stays total. Their pools hold the values each
  type's comparison gets wrong: two empty arrays and two `01 02`s (every draw is
  a fresh array, so equal content never shares an object), byte pairs one high
  bit apart, one decimal at several scales and pairs of one double, dates
  either side of the epoch and around a leap day and the 2024 DST changes, and
  instants a microsecond apart. `typeCoverage` reports what a case feeds a
  comparison of each, for the per-type guards.
  Float and double leaves draw `NaN`, `±Infinity` and `-0.0` a quarter of the
  time, and those operands reach ordering comparisons and `IN` lists like any
  other — all three were held out while `eval` and `evalVec` disagreed on them
  (see [gotchas.md](gotchas.md)), and `specialValueCoverage` now reports what a
  generated case actually feeds to each so the guard can assert the shapes AND
  the values are reached.
- `oracle/ExprParity.check` — the eval-vs-`evalVec` property (same normalized
  value, both NULL, or both throw the same exception class).
- `DataGen` — generates an in-memory `Dataset` (narrow value domains so GROUP BY
  / DISTINCT keys collide; tunable NULL density; 0-row / 1-row boundaries) plus
  `reshape` / `singlePartition` / `evenPartitions` / `withEmptyPartitions` that
  rebuild the same logical rows as a `MaterializedView` under a chosen
  partition/batch layout. A quarter of `Float`/`Double` columns additionally draw
  from `SpecialDoubles` — `NaN`, `-0.0`, `±Infinity`, a prefix of the pool so
  `NaN` is always present when specials are on at all. That widening is what
  makes the layout-differential properties able to see `MIN`/`MAX` over a
  `NaN`-carrying column, which was order-, fan-out- and flush-order-dependent
  while the corpus was all-finite (see [gotchas.md](gotchas.md)).
  `withSpecialDoubles` is shared with `MetaQueryGen.payloadDomain`: the
  metamorphic and sharded fuzzers build their relations from their own value
  pool and never call `DataGen.generate`, so widening this one alone would have
  left the aggregate-decomposition relation on finite inputs. Columns take all
  ten value types (`ColumnTypes` is `ExprGen.GenTypes`, drawn with `pick`):
  DATE, TIMESTAMP and BINARY columns take slices of `ExprGen`'s pools, so the
  pairs that sit side by side there (bytes of one content, bytes one high bit
  apart, instants under a millisecond apart) collide as keys, and every BINARY
  cell is its own array (`fresh`). A DECIMAL column draws from
  `DecimalPools`, which holds one value at several scales, at
  `DECIMAL(38,18)` two values of one double, and side by side values a DOUBLE
  adds wrong (`0.1`, `0.2`), so a `SUM`, `AVG` or arithmetic computed through
  `doubleValue` would be off and would move with the layout. All of them are
  small, so no generated arithmetic overflows. `valueTags` names the value pairs
  a column holds (`ValueTag`), the vocabulary every type guard asserts with.
- `QueryGen` — generates a single-table `SELECT` (projection, WHERE, GROUP BY +
  aggregates, HAVING, DISTINCT, ORDER BY, LIMIT, OFFSET) as a structured `GenQuery`
  rendered to SQL by `SqlRender`. Total by construction (a query that binds also
  runs) and **paren-free** — expressions rely on operator precedence. That was
  forced when the SQL frontend rejected grouping parentheses; the binder accepts
  them now, so emitting them is an open widening opportunity rather than a
  constraint (see [gotchas.md](gotchas.md)). Decimal arithmetic already takes
  the parentheses its shape needs, so its text reparses to exactly its tree (a
  regrouped reparse could join an integer column and a number into a DOUBLE,
  which the binder refuses beside a DECIMAL).
  Excludes `RAND()` and the clock functions. `LIMIT` and `OFFSET` are emitted
  only over a **total** ORDER BY (every output column an order key, so rows tied
  at a cutoff are identical rows and the kept slice is layout-independent); the
  soundness condition is checked in `render`, so a shrink that drops an order key
  drops the `LIMIT` and `OFFSET` with it rather than manufacturing a false
  counterexample. A top-n shape gets an `OFFSET` half the time (beside its
  `LIMIT`, or alone), weighted to the small skips that land inside the small
  generated results; the draw comes after every other draw for the query, so
  non-top-n seeds are the queries they always were. Every ORDER BY key gets an
  explicit `NULLS FIRST` / `NULLS LAST` half the time, drawn last of all for the
  same reason. Over the four types `DataGen` added, its existing shapes reach
  them as they come (ORDER BY and GROUP BY keys, `MIN`/`MAX`, `COUNT`,
  decimal `SUM`/`AVG`, top-n); literals render as `DATE '...'`,
  `TIMESTAMP '... .ffffff'` and, for a decimal, the number itself, which the
  analyzer widens to the decimal it meets, so only a value a double holds
  exactly is drawn (`SqlRender.writesExactly`). A BINARY has no literal at all
  and a DECIMAL or BINARY no CAST target, so their NULL is the untyped `NULL`
  and a HAVING never compares a BINARY key. A decimal-valued argument takes
  `COALESCE` / `IF` / `NULLIF` / `CASE` / `ABS` and arithmetic, which reaches a
  projection, a filter (compared with a decimal) and a `SUM`/`AVG` argument.
  Decimal arithmetic is anchored on a DECIMAL column (`genDecimalArith`): one
  side is a DECIMAL column or arithmetic over one, the other that, a number, an
  integer leaf or NULL, because SQL reads a number as a DOUBLE until it meets a
  DECIMAL, so `ABS(1.0)` beside one is a DOUBLE the binder refuses. It is never
  a branch of `COALESCE`, `IF` or `CASE`, which take their first branch's type
  and keep a wider decimal there unrounded, so a spill could not write it: a
  named gate for a recorded defect
  ([plans/robustness/09](../plans/robustness/09-branch-result-types.md)). Predicates
  and projected values take the four types too: a DATE or TIMESTAMP is compared
  with its own type (the literal side is now and then the quoted ISO string a
  user writes, `d = '2024-02-29'`, which the binder reads as the type it meets),
  a DECIMAL with a decimal expression or an integer column or literal (never
  anything that can be a FLOAT or DOUBLE, whose `NaN` the widening CAST refuses
  by design, so not an integer EXPRESSION either, whose type is only a hint
  here), and a BINARY with a BINARY; IN lists over each (a BINARY's items are
  BINARY columns); `IS NULL` and projected items over every type, with a text
  CAST or CONCAT never reading a BINARY.
- `oracle/ModeDifferential.check` — the mode-differential property: one
  `(data, query)` must produce the same result multiset under spill on/off,
  metrics on/off, and every partition/batch layout, and stay sorted when the
  query has ORDER BY — the primary key's non-NULL values monotonic and its
  NULLs together at the end the key places them.
- `MetaQueryGen` — the multi-relation generator (the metamorphic fuzzers' query
  source). A `RelEnv` of 2–3 base relations sharing one narrow join-key
  type/domain, plus a `MetaQuery` AST: a left-deep `FromClause` (joins of every
  kind incl. self-joins), a WHERE, a `QueryCore` that is a `ProjectCore` or an
  `AggCore`, an optional `setOp` `UNION`/`UNION ALL` arm, optional `ctes` (incl.
  the referenced-twice/materialized shape), and optional window items. Emits
  scope-/type-correct, paren-free (same widening opportunity as `QueryGen`
  above, and the same parentheses on decimal arithmetic), total SQL (the table
  qualifier rides in
  `ColRefExpr.name`; reuses `QueryGen.AggSpec` + `SqlRender`). Also `NoRecCase`
  (a single relation + a predicate). Derived-table subqueries in FROM are
  unsupported by the binder, so the generator never emits them — the TLP CTE
  wrapper supplies output-level partitioning without them. Relations take all
  ten value types as payload (`PayloadTypes`) and all but FLOAT, DOUBLE and
  BOOLEAN as the join key (`KeyTypes`): a DATE or TIMESTAMP key reaches the
  `LongHashMap` fast path INT and BIGINT take, and a BINARY or DECIMAL key the
  object codecs, grace-hash spill buckets and shard routing that plan 02 made
  agree with `=`. The four types' domains are 2-4 neighbours of `ExprGen`'s
  pools (`DataGen.DecimalPools` for a decimal), every BINARY cell its own array.
  Predicates compare them as `QueryGen`'s do (a DATE / TIMESTAMP / BINARY with
  its own type, a DECIMAL with decimals and integer leaves, and decimal
  arithmetic anchored and gated as `QueryGen`'s is), and they are TLP partition
  keys: compared with a literal, or for a BINARY, which has none, with another
  BINARY output column. A SUM or AVG of a DECIMAL is typed as the engine types
  it (`AggExprSum` / `AggExprAvg.resultType`), so it is a TLP key too.
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
  projection with `ORDER BY` over every one of its columns plus a `LIMIT`, an
  `OFFSET`, or both (the `OFFSET` draw is `QueryGen.withOffset`, shared), and
  half the time an explicit `NULLS FIRST` / `NULLS LAST` on every key, so rows
  tied at a cutoff are identical rows and the kept multiset survives every
  layout, build-side swap, and each of TLP's four executions. The keys are DERIVED
  (`MetaQuery.totalOrderKeys`), not stored, so a shrink that drops a projection
  item shrinks the order with it and a shrink that breaks totality drops the
  `LIMIT` instead of manufacturing a false counterexample. `ORDER BY` names the
  SOURCE columns, not the output aliases — see the binding rules in
  [gotchas.md](gotchas.md) — which is also why `DISTINCT`, windowed, aggregate and
  `UNION` shapes get no `LIMIT` or `OFFSET`.
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
  and `SUM` over integral and DECIMAL columns, which add exactly (a float `SUM`
  legitimately re-associates); the decimal one drives `DecimalSumState`'s merge
  and spill serde against the one-shot answer.
  Run in heap AND under spill: a global aggregate holds one state and never
  flushes, so an `AggState` serde defect is invisible until a grouped query
  spills and restores — exactly the shape of the `MinMaxState` Boolean-slot bug.
- `oracle/MetaModeDifferential.check` — the multi-relation extension of
  `ModeDifferential` (the join paths: exchange insertion, build-side swap). Same
  result multiset across partition/batch layouts, metrics on/off, and spill;
  comparison tolerates `Double` columns only. Only multiset-stable shapes are
  checked: a TIE-SENSITIVE window (`ROW_NUMBER`, `LAG`/`LEAD`, a running
  aggregate) is layout-dependent and left to `Tlp` on a fixed layout, while
  `RANK`/`DENSE_RANK` and no-`ORDER BY` aggregate windows are layout-invariant
  and run here like any other shape — which is how `WindowExec`, and the sharded
  window path beneath it, get cross-layout coverage at all (see
  [gotchas.md](gotchas.md)). The spill mode runs for join queries too — it used to be
  skipped around the grace-hash join spill NPE, now fixed via positional spill
  column names (see [gotchas.md](gotchas.md) and plans/bugfixes/01).
- `JobDagGen` — generates a whole `DataJob` for the scheduler property: inputs
  (some unloadable) plus an acyclic DAG of tasks, each rigged to succeed, fail in
  the worker, or fail a validation, with per-task writer/spill/metrics knobs and
  optional validation-induced dependency edges. Rendered to disk under a scratch
  root by `JobCase.build`. `Shrinker.jobCase` peels the task tail, drops edges,
  heals injected failures, and shrinks input rows.
- `oracle/DagSchedule.check` — runs the generated job on a watchdog thread and
  decides it against the DAG alone: per-task status (skip propagation is
  transitive and total), result completeness and order, dependency ordering by
  timestamp, `Skipped` tasks doing no work, skip reasons naming a real upstream,
  the job verdict, the on-disk `_run.json` matching the in-memory status, the
  `job.json` manifest agreeing with the run and reaching every task's record,
  and termination. A hung run fails with a thread dump — the pool-starvation
  detector.

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

Note the `--test_env=` prefix on each variable: a bare `FUZZ_SEEDS=N bazel test`
sets it in the client process only, never in the sandboxed test action, so the
run silently uses the default budget. That cost one wasted "it reproduces /
it doesn't" cycle on plan 06, whose repro line was missing it.

`metamorphic_fuzz_campaign` and `sharded_mode_fuzz_campaign` were both red at
seed 16502 through plan 05 and are green now: it was `WindowExec.sameKey`
comparing `RANK`/`DENSE_RANK` ORDER BY peers with `Ops.eq` while the sort used
`Ops.cmp` — a pre-existing wrong answer that only became reachable when `DataGen`
/ `MetaQueryGen` started drawing `NaN`. It was left failing rather than gated out
of the generator until [plan 06](../plans/followups/06-window-rank-peer-total-order.md)
fixed the comparison; see [gotchas.md](gotchas.md).

A campaign that big may not fit in one process on a memory-constrained machine.
The seed budget and the base seed are independent (`FUZZ_SEEDS` / `FUZZ_SEED`,
and `Props.forAll` uses `seed = base + i`), so N seeds can be covered as
disjoint blocks — `FUZZ_SEED=0,2500,5000,…` at `FUZZ_SEEDS=2500` covers exactly
the same corpus as one `FUZZ_SEEDS=20000` run, in processes that each start and
exit. Cap the test JVM with `--jvmopt=-Xmx3g` if the machine is shared.

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
`shardingGateIsActive` test asserts the gate flags arrived.

The DAG-scheduler property has three `fuzz`-tagged campaigns beyond its default
target: `dag_scheduler_fuzz_campaign` (plain),
`sharded_dag_scheduler_fuzz_campaign` (the sharding `jvm_flags`, so a task body
builds exchanges while the DAG scheduler is fanning out tasks) and
`starved_dag_scheduler_fuzz_campaign` (the same plus
`transformer.scheduler.parallelism=2`). The starved variant is the liveness
hunter: up to ten tasks, each fanning its own scan / writer / shuffle work onto a
two-thread pool, is the nested-submission shape this pool has wedged on before.
Every engine await is a work-helping `ForkJoinTask.get()`, so it must still
terminate; if it ever does not, the failure carries a thread dump rather than
hanging the run.

Add a new generator /
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
