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
# — it bakes in `-Xmx2g` (the deploy_jar doesn't, since `java -jar` ignores
# `jvm_flags`). With JVM-default heap a non-trivial parquet workload will OOM
# or thrash on macOS / inside containers.
bazel build //examples/gui_app:gui_app
bazel-bin/examples/gui_app/gui_app [job-dir]
# (or `java -Xmx2g -jar bazel-bin/examples/gui_app/gui_app_deploy.jar [job-dir]`)

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
| `core/core_types_test` | DataType, Schema, ColumnarBatch null/select, Catalog, Date/Timestamp box |
| `temporal/template_renderer_test` | Every templated variable + arithmetic edge cases |
| `read/csv/csv_row_parser_test` | State-machine edge cases (quotes, CRLF, escapes, blanks) |
| `read/csv/csv_reader_test` | Inference, null handling, explicit schema, glob, bare dir, batch splitting |
| `write/csv/csv_writer_test` | Quoting, nulls, roundtrip with reader, abort cleanup |
| `read/parquet/parquet_roundtrip_test` | All-primitive write+read, end-to-end DataJob with parquet I/O |
| `sql/exec/sql_engine_test` | 27 tests covering SELECT/WHERE/projection arithmetic, CASE, GROUP BY + COUNT/SUM/AVG/MIN/MAX, COUNT DISTINCT, INNER/LEFT JOIN, ORDER BY DESC + LIMIT, DISTINCT, HAVING, LIKE, IS NULL, scalar fns, empty-input aggregation; **COUNT_IF: no GROUP BY, grouped + NULL predicate ignored, empty input → 0, as window aggregate**; **window functions: ROW_NUMBER, RANK + DENSE_RANK ties, LAG/LEAD with default, running SUM over ORDER BY, aggregates over PARTITION BY, window without PARTITION BY, ROWS BETWEEN sliding frame, ORDER BY referencing a window expression, window function inside arithmetic, window after GROUP BY referencing the aggregate, window function in WHERE rejected**; **column projection push-down: drops unused columns, threads through aggregate, all-columns-used skips rewrite, narrowest-column projection when consumer references zero columns (primitive beats string regardless of declared order), narrowest pick for `SELECT <literal> FROM t LIMIT n`**; **math scalar functions: LN/LOG(x)/LOG(b,x)/LOG10/LOG2/EXP, SQRT/CBRT/SIGN, full trig + ATAN2 + DEGREES/RADIANS + PI()/E(), TRUNC(x[,n]), GREATEST/LEAST skipping NULLs, NULL inputs return NULL, RAND() in [0,1) + seeded determinism**; **statistical aggregates: STDDEV_SAMP/POP and VAR_SAMP/POP on a known dataset, GROUP BY partials → Chan merge, NULL inputs skipped + n<2 → NULL for sample stddev, COVAR_SAMP/COVAR_POP/CORR on y=2x, CORR with zero-variance series → NULL, STDDEV_POP as window aggregate** |
| `job/data_job_test` | End-to-end CSV → SQL → CSV; templated output path; templated SQL; validation failure path; multi-task pipeline with view chaining; diamond DAG ordering; failed-task skip propagation with independent sibling success; validation-failure skip propagation; empty `sql`; setup error reporting; concurrent sibling execution; multi-partition input → multiple part files; `maxPartitions` coalesce / no-op / single-file; downstream task reads all upstream part files; **`buildDag` returns nodes/deps without I/O**; **`TaskProgressListener` fires onStart+onFinish for executed tasks and only onFinish (Skipped) for upstream-failed downstreams**; **`_SUCCESS` marker written on success, NOT on Failed or ValidationFailed**; **`RunMarker.discover` finds multi-partition layouts newest-first, exact path with no template, empty when no markers, sibling-task isolation** |
| `job/task_dag_test` | Pure dependency analyzer + DAG builder: table-name extraction, independent roots, linear chain, diamond, cycle detection, unknown reference, duplicate viewName, viewName/input collision, main-SQL self-reference, validation self-reference allowed, validation peer reference, duplicate output path, empty input, template rendering before extraction |
| `job/json_test` | The stdlib JSON parser in `job/Json.scala` — scalars, escapes, nested objects, arrays, type errors, trailing content, scalar→string coercion for the option map |
| `job/directory_job_loader_test` | End-to-end `DirectoryJobLoader.load(...)`: basic run, relative vs absolute input paths, validations dir (success + failure), templated input paths + outputDir, alphabetical chaining, default outputDir, JSON scalar→option-map coercion, error cases (no/multiple `.json`, missing `main.sql`, missing jobDir), **per-table `output.json` `partitionBy` extends output path, absent leaves path unchanged, malformed throws** |
| `gui/sql_highlighter_test` | The pure-Scala SQL tokenizer in `gui/SqlHighlighter.scala` — null/empty input, case-insensitive keywords + functions, identifier classification, integer/decimal/scientific numerics, single-quoted strings with `''` escape + unterminated, line / block / unclosed-block comments, top-level `{{ template }}` tokens, template inside a string stays a string, punctuation tagging, full SELECT query round-trips losslessly, line splitting preserves content + handles block comments spanning lines, **window-function keywords (OVER/PARTITION/ROWS/UNBOUNDED/PRECEDING/CURRENT/ROW) and RANK as a function** |
| `gui/result_persister_test` | Interactive-SQL persist path: one part file per source partition with `_SUCCESS` marker stamping the right row count + format + file list, `csvHeader=false` toggle, `maxPartitions=Some(1)` coalesces multiple partitions into a single part file, unknown format rejected |

The other GUI components (`SqlView`, `TaskDetailsPanel`, `DagCanvas`, etc.) have
no JUnit tests — they're thin UI over engine APIs. Smoke-test by launching
`bazel-bin/examples/gui_app/gui_app_deploy.jar` and pointing it at a job dir.
