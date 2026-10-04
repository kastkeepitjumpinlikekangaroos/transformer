# transformer

A Scala library for building standalone JVM ETL jobs. Read data from disparate
sources into memory, run SQL transforms with a built-in parallel execution
engine, write to disparate destinations. Inspired by Apache Spark's batch SQL
model but with no Spark dependency — the SQL parser, logical planner, physical
planner, and executor are all in this repo.

Compiles to a self-contained deploy jar via Bazel + rules_scala. Memory and CPU
are scaled by giving the JVM more hardware; the library is designed for
single-node parallelism, not distributed execution.

## Status

**v1 (this release):**

- Local CSV read/write (folder or glob, schema inference)
- Local Parquet read/write (snappy, all primitive types)
- Full SQL: SELECT, WHERE, JOIN (INNER/LEFT/RIGHT/FULL), GROUP BY, HAVING,
  ORDER BY, LIMIT/OFFSET, DISTINCT, UNION/UNION ALL, CTEs (`WITH`), CASE, CAST, scalar functions
- DBT-style data-quality validations
- Jinja-style temporal templating in SQL and output paths
- Per-task path-template partitioning (one partition per run keyed off the
  job's execution time), with per-task `_run.json` records that capture
  status, timings, validation results, and failure samples — plus a
  per-job `job.json` manifest referencing all task records
- Two ways to define a job: programmatic (`DataJob(...)`) or directory-driven
  (`DirectoryJobLoader.load(dir)`)
- A JavaFX GUI for browsing a job directory, editing execution time + output
  dir, running the pipeline with live per-task status, and inspecting past
  runs via a partition picker
- Bazel-deployable example apps (programmatic, directory-driven, GUI launcher)

**v1.1 (planned):** `gs://` and `s3://` paths. The `InputFilePath` /
`OutputFilePath` API already accepts them; today they raise a clear
`UnsupportedOperationException` with the cache directory already wired up.

## Documentation

This README is the user-facing intro. For deeper material — architecture,
extension recipes, conventions, gotchas — see the [docs/](docs/) directory:

- [docs/architecture.md](docs/architecture.md) — mental model, module map,
  cross-cutting patterns.
- [docs/conventions.md](docs/conventions.md) — Scala / Bazel / docs
  conventions.
- [docs/extending.md](docs/extending.md) — how to add SQL functions, file
  formats, GUI panels, etc.
- [docs/gotchas.md](docs/gotchas.md) — known pitfalls and what's intentionally
  not done.
- [docs/testing.md](docs/testing.md) — build/test commands and the test
  inventory.
- [docs/code-map.md](docs/code-map.md) — file-size hot spots and external
  reference material.

Future Claude sessions should also read [CLAUDE.md](CLAUDE.md), which links
back here and into `docs/`.

## Quick start

```bash
# Build + test everything.
bazel test //...

# Programmatic example: read 2 CSVs, join, aggregate, validate, write.
bazel build //examples/scala_app:example_job_deploy.jar
java -jar bazel-bin/examples/scala_app/example_job_deploy.jar \
    examples/scala_app/data/input \
    /tmp/transformer-example-out

# Directory-driven example (DBT-style): same shape, defined as a folder.
# Optional 3rd arg = ISO instant for executionTime — re-run with different
# values to produce multiple partitioned outputs for the GUI to browse.
bazel build //examples/directory_app:directory_example_deploy.jar
java -jar bazel-bin/examples/directory_app/directory_example_deploy.jar \
    examples/directory_app/job \
    /tmp/transformer-directory-out \
    2026-01-01T05:30:21Z

# JavaFX GUI: visualize the DAG, edit execution time + output dir, Run,
# inspect persisted runs.
bazel build //examples/gui_app:gui_app_deploy.jar
java -jar bazel-bin/examples/gui_app/gui_app_deploy.jar \
    examples/directory_app/job

# Heavy-load example: a 17-task pipeline over the Polymarket tick-level
# orderbook dataset. Reads ~85M parquet rows across 5 inputs (a 6-hour slice
# of one orderbook day, 21 days of snapshots, full features/trades/markets),
# writes 17 parquet outputs, runs 58 validations, and intentionally fails one
# branch to exercise the "failed validation blocks downstream" scheduler path.
# ~5 min on a fast Mac with -Xmx12g; needs the dataset at `~/Downloads/archive/`.
bazel build //examples/polymarket:polymarket_deploy.jar
java -Xmx12g -jar bazel-bin/examples/polymarket/polymarket_deploy.jar \
    examples/polymarket/job \
    /tmp/transformer-polymarket-out \
    2026-03-26T00:00:00Z
```

The programmatic example reads two CSVs, joins them, aggregates spend per user
tier with a templated output path (`day=20260101/`), runs a validation, and
exits 0. The directory example is the same job re-expressed as a folder of
JSON configs and SQL files — see
[Defining jobs from a directory](#defining-jobs-from-a-directory). The GUI is
a thin reader over either layout; see [GUI](#gui). The polymarket example is
described in [Heavy-load example: polymarket](#heavy-load-example-polymarket).

## Example usage

```scala
import com.transformer.job._
import com.transformer.temporal.TemporalVariables
import java.time.Instant

val job = DataJob(
  temporalVariables = Some(TemporalVariables(Instant.parse("2026-01-01T05:30:21Z"))),
  inputs = Seq(
    InputFilePath("data/events/*.csv", viewName = "events"),
    InputFilePath("data/users.parquet", viewName = "users")
  ),
  sql = Seq(
    SQLTask(
      name      = Some("enrich"),
      sqlString = Some(
        """SELECT e.user_id, u.name, u.tier, e.event, e.amount
          |FROM events e
          |LEFT JOIN users u ON e.user_id = u.user_id
          |""".stripMargin),
      outputFile = Some(OutputFilePath("out/day={{ today }}/enriched.csv")),
      viewName   = Some("enriched")
    ),
    SQLTask(
      name      = Some("spend_by_tier"),
      sqlString = Some(
        """SELECT tier, COUNT(*) AS n, SUM(amount) AS total
          |FROM enriched
          |WHERE event = 'buy'
          |GROUP BY tier
          |ORDER BY tier
          |""".stripMargin),
      outputFile = Some(OutputFilePath("out/day={{ today }}/spend_by_tier.parquet")),
      validations = Seq(
        Validation(
          name      = "no_null_tiers",
          sqlString = Some("SELECT * FROM result WHERE tier IS NULL")
        )
      )
    )
  )
)

val result = job.run()
if (!result.succeeded) sys.exit(1)
```

A task's `outputFile` is optional. Set it and the result is persisted, and any
downstream task or validation reads it back from disk. Leave it out and the task
is a **memory-only feeder**: it still registers its `viewName` for downstream
tasks and validations, but the result is held in heap for the rest of the run
instead of being written — useful for an intermediate step whose output nobody
needs on disk, at the cost of keeping it in memory (the same trade a cached
input or a multiply-referenced CTE makes).

`DataJob.run()` returns a `JobResult` describing per-task status, row counts,
durations, and any consistency warnings. If a validation fails the
corresponding task is marked `ValidationFailed`, its output is persisted
regardless (for debugging), and a sample of failing rows is written to
`_validation-<name>.csv` next to that task's `_run.json` — see [Run
records](#run-records-and-historical-runs) below.

## Defining jobs from a directory

For lighter-weight DBT-style projects you can express the whole job as a
directory tree and skip the Scala builder. Point `DirectoryJobLoader` at a job
directory and it returns the same `DataJob` you'd otherwise build by hand.

### Expected layout

```
<jobDir>/
  inputs/
    <viewName>/
      <any>.json                  # InputFilePath config (exactly one .json file)
  tables/
    <viewName>/
      main.sql                    # required: the SELECT for this table
      validations/                # optional
        <validationName>.sql
```

Directory names become catalog view names. Each `inputs/<viewName>/` contains
exactly one `.json` file whose fields match `InputFilePath`:

```json
{
  "path": "data/events.csv",
  "format": "csv",
  "options": {"delimiter": ",", "header": true},
  "cache": true
}
```

The directory name is the view name — don't set `viewName` in the JSON (a
`viewName` field there is silently ignored). Relative `path` values are
resolved against the job directory; absolute paths and cloud URLs (`gs://`,
`s3://`) are passed through unchanged. Template variables (`{{ today }}`,
etc.) inside `path` are expanded at run time.

Listing order is alphabetical for predictability, but it does NOT constrain
run order — `DataJob` builds a DAG from each task's SQL and runs independent
branches in parallel. The order only matters as a *declared index*. Use
numeric prefixes (`01_raw/`, `02_clean/`) for human-readable grouping.

### Per-table output config (optional)

Each table can carry an optional `tables/<viewName>/output.json` for
table-level output settings. Today the only supported field is `partitionBy`:

```json
{ "partitionBy": "day={{today}}" }
```

When present, the task's output path becomes
`<outputDir>/<viewName>/<partitionBy>` — so the example above lands at
`<outputDir>/<viewName>/day=20260101/part-NNNNN.csv` for an execution time of
Jan 1 2026. Running the same job at a different execution time produces a
sibling `day=20260102/` partition; both stay on disk and the GUI's run picker
sees them as historical runs of the same task.

### Loading and running

```scala
import com.transformer.job.DirectoryJobLoader
import com.transformer.temporal.TemporalVariables
import java.nio.file.Paths
import java.time.Instant

val job = DirectoryJobLoader.load(
  jobDir            = Paths.get("examples/directory_app/job"),
  outputDir         = Some("/tmp/transformer-directory-out/day={{ today }}"),
  temporalVariables = Some(TemporalVariables(Instant.parse("2026-01-01T05:30:21Z")))
)

val result = job.run()
```

`outputDir` is templated like any output path. Each table writes to
`<outputDir>/<viewName>/part-NNNNN.csv` (the loader passes `format = Some("csv")`
explicitly so the per-table directory stays clean) and is registered in the
catalog so subsequent tables can reference it. If you don't pass `outputDir`
the default is `<jobDir>/output/`. To partition a table's output per run, see
[Per-table output config (optional)](#per-table-output-config-optional) below.

### Example app

```bash
bazel build //examples/directory_app:directory_example_deploy.jar
java -jar bazel-bin/examples/directory_app/directory_example_deploy.jar \
    examples/directory_app/job \
    /tmp/transformer-directory-out
```

The job reads two CSVs (events, users), produces an `enriched_events` table
with `'{{ iso_timestamp }}' AS execution_time`, then a `spend_by_tier`
aggregate with `'{{ today }}' AS execution_date` and a `no_null_tiers`
validation. Output lands under
`/tmp/transformer-directory-out/day=20260101/` (path templated from the
execution time). The 3rd CLI arg lets you override the execution time:

```bash
java -jar bazel-bin/examples/directory_app/directory_example_deploy.jar \
    examples/directory_app/job /tmp/transformer-directory-out \
    2026-01-02T00:00:00Z
# → outputs land under day=20260102/, alongside any prior day=20260101/ run
```

## Heavy-load example: polymarket

`examples/polymarket/` is a 17-task pipeline over the
[Polymarket tick-level orderbook dataset](https://www.kaggle.com/datasets/marvingozo/polymarket-tick-level-orderbook-dataset)
(Kaggle, ~40GB across 5 parquet sources). It exists to stretch the executor on
a realistic high-volume workload while exercising every layer of the runner:
streaming parquet input, multi-stage parquet output, parallel branches, and
the `ValidationFailed → downstream Skipped` scheduler path.

**What it does:**

- Reads 5 parquet inputs (1 daily orderbook ≈131M rows / ~900MB compressed,
  21 daily snapshot files / 51M rows, the pre-built 1-minute feature parquet
  / 5.6M rows, all 4.1M trade executions, the 124K-row market-metadata
  parquet).
- The shipped `stg_orderbook` filters the orderbook day to its first 6 hours
  (≈27M rows) so the scan finishes in a few minutes. The orderbook file has
  139 row groups, so the row-group-level partitioner splits it into ~4
  partitions of ~256MB each — the scan now runs across multiple cores
  concurrently instead of single-threaded. Remove the
  `timestamp_received < …` clause in `tables/stg_orderbook/main.sql` to
  process the full ≈131M-row day.
- Produces 17 parquet outputs in a staging → intermediate → mart → final
  layering. Every output is partitioned by `day={{ today }}`. Every task
  (including the deliberately-failed and skipped ones) gets a `_run.json`
  record, and the whole run is rolled up into `job.json` next to the output
  dir — so the GUI's run picker can replay this run's exact statuses later.
- Carries 58 validations across the 17 tables (2-5 per table), all in the
  DBT-style "this query should return zero rows" shape. Failure samples for
  the intentionally-failing validation land in
  `<output>/mart_orderbook_quality_check/.../_validation-zzz_no_observable_snapshot_latency_intentional_failure.csv`.
- **Two branches expected to ValidationFail on real-feed data:**
  `mart_orderbook_quality_check` has a validation that asserts no market has
  snapshot latency above zero — real data does, so the task is marked
  `ValidationFailed` and its downstream `mart_quality_report` is `Skipped`.
  `final_combined_report` carries a `category_not_null` validation that
  surfaces the NULL `category_normalized` values propagated through
  `mart_market_overview`'s LEFT JOIN against `int_markets_categorized` (any
  orderbook row whose `condition_id` doesn't match a market row leaves a NULL
  category in the rollup). The other three mart branches (overview,
  high-activity, volatility) continue and feed `final_combined_report`. The
  launcher exits 0 iff this exact pattern holds.

**Prerequisite:** unpack the Kaggle dataset into `~/Downloads/archive/` so the
input paths in `job/inputs/raw_*/config.json` resolve. The launcher hardcodes
`/Users/owenchristie/Downloads/archive/...` paths — edit the configs if your
checkout lives elsewhere.

**Run:**

```bash
bazel build //examples/polymarket:polymarket_deploy.jar
java -Xmx12g -jar bazel-bin/examples/polymarket/polymarket_deploy.jar \
    examples/polymarket/job /tmp/transformer-polymarket-out \
    2026-03-26T00:00:00Z
# Runs in ~5 min on a fast Mac. Output:
# 14 Succeeded,
# 2 ValidationFailed (mart_orderbook_quality_check, final_combined_report),
# 1 Skipped (mart_quality_report), 0 Failed. Exit 0.
```

`-Xmx12g` is the floor for the shipped 6-hour-orderbook-slice configuration;
bump higher (and expect much longer runs) if you remove the time-window
filter to process the full ≈131M-row day, or expand `raw_orderbook` to
multiple daily files.

## Run records and historical runs

The runner persists a full run record for every task **at every termination
status** — Succeeded, ValidationFailed, Failed, Skipped — so reopening the
GUI against a previous run shows the exact same status, validation
detail, and error messages it did during the live run. Reruns at the same
templated output path completely overwrite prior state.

There are two artifacts.

### Per-task `_run.json`

Each task with an `outputFile` writes `_run.json` into its output directory:

```
/tmp/out/day=20260101/spend_by_tier/_run.json
/tmp/out/day=20260101/spend_by_tier/part-00000.csv
/tmp/out/day=20260101/spend_by_tier/_validation-customer_id_unique.csv  # only when that validation fails
```

The record captures everything needed to reconstruct the task's UI state:

```json
{
  "schemaVersion": 1,
  "taskName": "spend_by_tier",
  "status": "ValidationFailed",
  "errorMessage": null,
  "executionTime": "2026-01-01T05:30:21Z",
  "enqueuedAt":    "2026-05-18T03:17:02.508Z",
  "startedAt":     "2026-05-18T03:17:02.612Z",
  "finishedAt":    "2026-05-18T03:17:03.099Z",
  "writtenAt":     "2026-05-18T03:17:03.103Z",
  "rowsProduced":  2,
  "format":        "csv",
  "outputFiles":   ["part-00000.csv"],
  "validations": [
    { "name": "customer_id_unique", "passed": false, "failedRowCount": 7,
      "sampleFile": "_validation-customer_id_unique.csv" },
    { "name": "row_count_positive", "passed": true, "failedRowCount": 0,
      "sampleFile": null }
  ]
}
```

`enqueuedAt` is the moment all of the task's inputs and upstream task results
were available — i.e. when the runner *would* have started the task if the
shared worker pool had a free thread. `startedAt - enqueuedAt` is the
queue-wait time the GUI surfaces alongside the run duration, so you can tell
"this task ran fast but waited 3s for a worker" from "this task ran for 3s".

The underscore prefix means CSV/Parquet readers skip both the record and the
validation samples when re-reading the directory as data.
`TaskRunRecord.discover(templatedPattern)` glob-walks the filesystem and
returns every directory matching the pattern that contains a `_run.json`,
sorted newest-first. This is what powers the GUI's partition picker — open
a job that's been run a few times against `out/day={{today}}/<view>` and
the picker lists every historical `day=*` partition with its preserved
execution time + status.

### Per-job `job.json`

`DataJob.jobRunOutput` (defaulted by `DirectoryJobLoader` to
`<outputDir>/job.json` — co-located with the data so an output directory
is a self-contained snapshot of one run) is a single file written at the
end of every run, listing every task with a pointer to its per-task
`_run.json` plus a short summary (status, rows, error). Loading this one
file is the GUI's entry point for hydrating the whole job; per-task
records are loaded lazily as the user drills into each task. Consistency
checks (declared part file missing, referenced `runFile` not on disk,
etc.) land in the manifest's `warnings` array and surface in the GUI's
run-log panel.

Reruns at the same `outputDir` overwrite the manifest in place. To keep
run-by-run history, template `outputDir` itself
(e.g. `/data/runs/{{ today }}`): each execution time writes to a fresh
subdir with its own `job.json` + per-task data, and the parent dir
becomes a multi-run layout. When the GUI's controls panel finds 2+
sibling runs at the parent of the rendered `jobRunOutput`, it surfaces a
run picker — switching the picker rehydrates the entire UI from the
chosen `job.json` without re-running anything.

## GUI

A JavaFX visualizer / runner. Open a job directory, see its DAG, edit the
execution time + output directory, press Run, watch task colors update live,
double-click a node to inspect the persisted output rows.

```bash
bazel build //examples/gui_app:gui_app_deploy.jar
java -jar bazel-bin/examples/gui_app/gui_app_deploy.jar \
    examples/directory_app/job
# Optional first arg auto-opens that job; otherwise use File > Open Job Directory.
```

Layout:

- **Top** — menu bar (File: Open / Reload / Quit; View: Fit DAG) stacked
  above a horizontal controls strip: open job dir, execution time (UTC date
  + h/m/s spinners), output dir field, a small Run button (⌘R / Ctrl+R),
  and run status. Hover the output-dir field for the effective rendered
  path.
- **Center top** — DAG canvas. Pan with right- or middle-mouse drag (or
  Alt+left-drag), zoom with the scroll wheel. Click a node to select it;
  double-click to load its output rows.
- **Right** — selected task's source SQL, rendered (post-template) SQL,
  status, error/validation summary, planned vs. actual output path, and any
  `_run.json` provenance.
- **Center bottom** — five tabs: task details, output data table (with a
  partition picker above it when 2+ historical runs exist for the activated
  task), Validations (per-validation cards with an Edit… button that pops
  out the `AddValidationDialog.showEdit` editor and writes back to
  `tables/<view>/validations/<name>.sql`), an ad-hoc SQL console, and a run
  log.

Node colors:

| Color | Meaning |
|---|---|
| Grey | Pending — never run, no `_run.json` on disk |
| Blue | Running |
| Green | Succeeded (either freshly this session, or hydrated from `_run.json`) |
| Red | Failed (error message + timing reloaded from disk) |
| Orange | Validation failed (per-validation pass/fail + sample CSV reloaded from disk) |
| Light grey + dashed | Skipped (upstream failure) |

When you open a job directory the GUI loads `<outputDir>/job.json` if it
exists and follows each task's `runFile` pointer to its per-task
`_run.json` — reconstructing the full status (including validation failure
detail with sample rows) without re-running anything. The picker dropdown
above the output-data table is populated by `TaskRunRecord.discover` over
each task's templated output path, so you can flip between historical
partitions inline.

`DataJob.run(executor, listener)` fires `TaskProgressListener.onTaskStart` /
`onTaskFinish` from runner worker threads; the GUI's Run button installs a
listener that marshals back to the FX thread via `Platform.runLater`.

The GUI module is `//src/main/scala/com/transformer/gui`; the launcher is
`//examples/gui_app`. The launcher bundles parquet read+write so the GUI's
preview works for both formats.

## Supported file formats

### CSV

- Read: hand-rolled state-machine parser. RFC 4180 doubled-quote escape,
  separate escape character, embedded delimiters, embedded newlines, CR/LF
  line endings.
- Write: atomic temp-file + rename, header from `Schema`.
- Schema inference: samples first 1000 rows of the first file.
  Type priority `Int → Long → Double → Boolean → Date → Timestamp → String`.

Options (passed via `InputFilePath.options` / `OutputFilePath.options`):

| Key | Default | Notes |
|---|---|---|
| `header` | `true` | First line contains column names |
| `delimiter` | `,` | Pass `\t` for TSV |
| `quote` | `"` | |
| `escape` | `"` | RFC 4180 doubled-quote escape when equal to `quote` |
| `nullValue` | `""` | Empty string = null |
| `inferSchema` | `true` | If false, `columns` is required (input) |
| `charset` | `UTF-8` | |
| `compression` (output, parquet only) | `snappy` | `uncompressed` / `snappy` / `gzip` — `none` is an alias for `uncompressed`. Set `uncompressed` on tables whose schema is narrow-numeric (timestamps + prices etc.) where dictionary encoding alone is enough — they pay snappy CPU for negligible disk-size savings. |

When `inferSchema = false`, pass an explicit schema:

```scala
InputFilePath("data.csv",
  viewName = "t",
  options  = Map("inferSchema" -> "false", "header" -> "false"))
// then set columns programmatically via CsvOptions if you build the reader
// directly; or build CsvOptions from the option bag and override columns.
```

### Parquet

- Read: vectorized — `parquet-hadoop` open + `ColumnReadStoreImpl` decode
  straight into `ColumnarBatch` primitive vectors. No `Group`/`SimpleGroup`
  objects allocated per row. Row groups are packed into ~256MB partitions so a
  multi-GB single file still saturates worker threads.
- A glob of parquet files is one view whose schema is the first file's. Every
  other file's columns are found **by name** and decoded as that file declares
  them, so the files may order their columns differently, add columns of their
  own (which the view does not see), allow NULLs the first does not (the view
  then allows them), or store a column another way that reads as the same type
  (a `TIMESTAMP` as `INT96` in one file and as `INT64` in the next). A file
  that lacks one of the first file's columns, or reads one as another type,
  fails the read when the view is opened, naming the file and the column.
- Types read. A column reads as its physical type and its annotation say,
  decided per file:

  | parquet column | reads as |
  |---|---|
  | `BOOLEAN` | `BOOLEAN` |
  | `INT32`, alone or `INTEGER(8, 16 or 32, signed)` or `INTEGER(8 or 16, unsigned)` | `INT` |
  | `INT32 INTEGER(32, unsigned)` | `BIGINT`, so its upper half does not read negative |
  | `INT32 DATE` | `DATE` |
  | `INT64`, alone or `INTEGER(64, signed)` | `BIGINT` |
  | `INT64 INTEGER(64, unsigned)` | `DECIMAL(20,0)`, which holds every value |
  | `INT64 TIMESTAMP` (`MILLIS`, `MICROS` or `NANOS`), and `INT96` | `TIMESTAMP` (below) |
  | `FLOAT`, `DOUBLE` | `FLOAT`, `DOUBLE` |
  | `FIXED_LEN_BYTE_ARRAY(2) FLOAT16` | `FLOAT`, exactly |
  | `DECIMAL(p,s)` as `INT32`, `INT64`, `FIXED_LEN_BYTE_ARRAY` or `BINARY` | `DECIMAL(p,s)` |
  | `BINARY STRING`, `ENUM` or `JSON` | `STRING` |
  | `BINARY`, alone or `BSON`; `FIXED_LEN_BYTE_ARRAY`, alone or `UUID` | `BINARY` |
  | `TIME`, `INTERVAL`, a `REPEATED` column (a list per row), a nested group | none: the file cannot be read |

  The last row fails when the view is opened, naming the column, even for a
  query that never mentions it: the view's schema is the file's, and the
  engine has no type to give it. No annotation falls through to its physical
  type, which would read a `DECIMAL` as its unscaled integer or an unsigned
  integer as a negative one.
- A parquet `TIMESTAMP` reads as the instant it stores, whatever its unit:
  `MILLIS`, `MICROS` and `NANOS` alike, and the legacy 12-byte `INT96` too. A
  `TIMESTAMP` holds microseconds, so a `NANOS` value is truncated to its
  microsecond, as on every other path into the engine: a `TIMESTAMP` literal
  with nine fractional digits truncates the same way, and before 1970 that is
  the earlier microsecond (`1969-12-31 23:59:59.999998500` reads as
  `...59.999998`). A `MILLIS` value with no microsecond value (more than about
  292,000 years from 1970) fails the read, naming the column. A timestamp
  marked local (`isAdjustedToUTC = false`) reads as the UTC instant with the
  same wall-clock reading, since the engine has one timestamp type, an instant
  in UTC; one written back out is marked UTC.
- Read predicate pushdown: `WHERE c <op> lit`, `c IS NULL` / `c IS NOT NULL`,
  `c IN (lit, lit, …)` / `c NOT IN (…)`, `c BETWEEN lo AND hi`, and
  `NOT`/`AND`/`OR` over those forms are translated to parquet-mr's `FilterApi`
  and applied against each row group's column statistics — groups proven not
  to match are skipped entirely. The original `FilterExec` still runs above
  the scan for per-row precision (stats can prove non-matching, never
  matching). Unsupported conjuncts (computed expressions, two-column
  predicates, `LIKE`, `IN` with a NULL literal in the list, decimal columns,
  comparisons on a timestamp column, whose statistics are in whatever unit
  its file stores, and comparisons on a column whose stored values are not
  the ones it reads as, such as an unsigned `INT32` or a `FLOAT16`) drop
  through as residual filtering. `IS NULL` pushes on any column but a decimal.
- Write: vectorized — custom `WriteSupport[BatchRowRef]` drives parquet-mr's
  `RecordConsumer` straight from `ColumnarBatch` vectors. No `SimpleGroup` per
  row, no `Integer`/`Long` boxing per cell. Compression defaults to `snappy`
  (see the options table above); override per-table via `output.json`'s
  `options.compression` — `uncompressed` is the right opt-in for pure
  narrow-numeric tables where dictionary encoding alone is enough.
- Written: `Int` (`INT32`), `Long` (`INT64`), `Float`, `Double`, `Boolean`,
  `String` (`BINARY (STRING)`), `Binary`, `Date` (`INT32 (DATE)`, days),
  `Timestamp` (`INT64 TIMESTAMP(MICROS, true)`) and `DECIMAL(p,s)` in the
  layout the format spec recommends: `INT32` up to 9 digits, `INT64` up to 18,
  and beyond that the narrowest `FIXED_LEN_BYTE_ARRAY` that holds every value
  of `p` digits (16 bytes for `DECIMAL(38,s)`). Parquet stores one scale per
  column, and a column does not hold every value at its declared scale, so a
  decimal is written at the declared scale when it can be exactly (`1.5` in a
  `DECIMAL(10,2)` as `1.50`); one that would need rounding, or has more digits
  than `p`, fails the write, naming the value and the column, rather than
  storing a different number.

Parquet is a first-class format — `job/` depends on the parquet read/write
modules directly, so any user of the `DataJob` API gets parquet support out
of the box.

### Cloud (`gs://` / `s3://`)

v1: any cloud path triggers `UnsupportedOperationException` with a clear v1.1
message. The cache directory layout (`./.transformer-cache/<sha1(path)>/`) is
already in the design; v1.1 will populate it from GCS/S3.

## SQL features

The engine supports the standard SQL-92 SELECT shape:

```
[ WITH <name> [(<col>, ...)] AS (<select>) [, ...] ]
SELECT [DISTINCT] [TOP <n>] <projections>
[ FROM <table> [<alias>]
  [ {INNER|LEFT|RIGHT|FULL} JOIN <table> [<alias>] ON <predicate> ]* ]
[ WHERE <predicate> ]
[ GROUP BY <exprs> ] [ HAVING <predicate> ]
[ ORDER BY <expr> [ASC|DESC] [NULLS FIRST|NULLS LAST] [, ...] ]
[ LIMIT <n> ] [ OFFSET <m> [ROWS] ] [ FETCH {FIRST|NEXT} [<n>] {ROW|ROWS} ONLY ]
```

A NULL sorts as the smallest value unless the key says otherwise: first under
`ASC`, last under `DESC`. That is Spark's, MySQL's and SQLite's default and the
reverse of Postgres's, so SQL ported from Postgres that relies on where NULLs
land should spell out `NULLS FIRST` or `NULLS LAST`, which take effect per key
in `ORDER BY` and in a window's `OVER (ORDER BY ...)` alike.

`OFFSET m` skips the first `m` rows of the whole result — after `DISTINCT` and
`ORDER BY`, before `LIMIT` — so `ORDER BY a LIMIT 10 OFFSET 20` is the third page
of ten. MySQL's `LIMIT m, n` spelling means the same as `LIMIT n OFFSET m`.
Written after the last arm of a `UNION`, both clauses apply to the whole union,
as they do in Postgres. Without an `ORDER BY` that fixes the order, which rows a
`LIMIT` or `OFFSET` keeps is unspecified, as in any SQL engine.

The row count has two more spellings, both common in SQL ported from other
warehouses: the standard `FETCH FIRST n ROWS ONLY` (also `FETCH NEXT`, `ROW`, and
`FETCH FIRST ROW ONLY` for one row), which pairs with `OFFSET m ROWS`, and T-SQL's
`SELECT TOP n` (or `TOP (n)`). Each means `LIMIT n`, and a query may use one of
the three. `TOP` cannot be combined with `OFFSET`, as in T-SQL, and the
`WITH TIES` and `PERCENT` forms of both are rejected. After a `UNION`, `FETCH`
limits the whole union like `LIMIT`; a `TOP` belongs to the arm it is written in.

Without a `FROM`, a `SELECT` reads a single row that has no columns, so its
expressions are evaluated once: `SELECT 1 + 1 AS two` returns one row,
`SELECT COUNT(*) AS n` returns 1, and `WHERE`, `LIMIT` and `UNION` apply as they
do anywhere else. No column name resolves there, and `SELECT *` is an error.

A `HAVING` with no `GROUP BY` makes the query an aggregate over a single group,
as in standard SQL: `SELECT 'non-empty' AS s FROM t HAVING COUNT(*) > 0` returns
one row or none. A column outside an aggregate is an error there, not the row
filter MySQL makes of it.

`GROUP BY` and `ORDER BY` also accept a **positional ordinal** — a bare integer
naming a SELECT-list position, as in BigQuery / Postgres / MySQL. `ORDER BY 2
DESC` sorts by the second output column, and unlike the named form it reaches an
aliased expression (`SELECT a + b AS s FROM t ORDER BY 1` works; `ORDER BY s`
does not — see [docs/gotchas.md](docs/gotchas.md) on ORDER BY scope). Only a bare
integer counts: `ORDER BY +1` is a constant, not a position. A `GROUP BY` ordinal
may not point at an aggregate; an `ORDER BY` ordinal may. An out-of-range
position is an error.

**SQL outside this grammar is rejected, not ignored.** The parser accepts far
more SQL than the engine implements, and a clause the binder does not read used
to vanish without an error — `JOIN ... USING` ran as a cartesian product,
`OFFSET` returned the wrong page. Every such clause now fails at bind time with
an `IllegalArgumentException` naming it, for example `QUALIFY is not supported;
compute the window function in a WITH clause and filter on it in the outer
WHERE`. Rejected, among others: `JOIN ... USING`, `NATURAL`, `SEMI` and `APPLY`
joins, a bare `OUTER JOIN` and Oracle's `(+)`; `TOP n PERCENT`, `WITH TIES`, and
`ORDER BY` / `LIMIT` / `OFFSET` / `FETCH` after a parenthesized query; `QUALIFY`,
`DISTINCT ON`, `GROUPING SETS` and `WITH ROLLUP`; `FILTER (WHERE ...)`,
`WITHIN GROUP`, `IGNORE NULLS` and named `WINDOW`s; `DISTINCT` inside
`STDDEV`, `VARIANCE`, `COUNT_IF`, `COVAR` or `CORR`; `RLIKE` / `REGEXP`, `SIMILAR TO` and
`x NOTNULL`; `TRY_CAST`, array types and
subscripts; `* EXCEPT` / `* REPLACE`; `PIVOT`, `TABLESAMPLE`, `CONNECT BY` and
schema-qualified table names; `SELECT ... INTO`, `FOR UPDATE`, and vendor
hints. The complete list, with the reason for each, is
[`UnsupportedClauses.scala`](src/main/scala/com/transformer/sql/plan/UnsupportedClauses.scala).

**Aggregates:** `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)`, `COUNT_IF(pred)`,
`SUM`, `AVG`, `MIN`, `MAX` (each of these four also as `SUM(DISTINCT col)` and so
on, which aggregates each distinct non-NULL value once),
`STDDEV`/`STDDEV_SAMP`/`STDDEV_POP`, `VARIANCE`/`VAR_SAMP`/`VAR_POP`,
`COVAR_SAMP`/`COVAR_POP`, `CORR`. Univariate variance/stddev (and `COUNT_IF`)
are also available as window aggregates with `OVER (...)`. Variance and stddev
use Welford + Chan's parallel merge for numerical stability; `*_SAMP` returns
NULL when fewer than two non-NULL inputs are present. `SUM` is a `BIGINT` over
integers, a `DOUBLE` over floats, and over a `DECIMAL(p,s)` an exact
`DECIMAL(38,s)`. `AVG` is a `DOUBLE`, but over a `DECIMAL(p,s)` an exact
`DECIMAL(p+4,s+4)` (past 38 digits it gives way on scale, as arithmetic does),
rounded half up. A decimal sum too long for 38 digits is an error.

**Window functions:** `<fn>() OVER ([PARTITION BY <exprs>] [ORDER BY <keys>]
[<frame>])` — `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG`/`LEAD` (with the SQL
`offset` and `default` arguments), and the aggregate forms above. The `ORDER BY`
keys take `ASC`/`DESC` and `NULLS FIRST`/`NULLS LAST` exactly as the outer
`ORDER BY` does. Frames are
`ROWS BETWEEN ... AND ...`; `RANGE` is accepted and executed as `ROWS`, which
differs only when the `ORDER BY` has ties. `RANK` and `DENSE_RANK` tie exactly
the rows the `ORDER BY` ties, on the same total order described below — so
`DENSE_RANK()` over a partition whose order key is equal on every row is `1`
everywhere. No `FIRST_VALUE`/`LAST_VALUE`/`NTH_VALUE`/`PERCENT_RANK`/
`CUME_DIST`/`NTILE` yet.

**Scalar functions:** strings — `LENGTH`/`LEN`, `UPPER`, `LOWER`, `TRIM`
(single-argument whitespace trim only — `TRIM([LEADING|TRAILING|BOTH] [str]
FROM x)` and `TRIM(x, str)` are rejected with a clear error, and there is no
`LTRIM`/`RTRIM`), `SUBSTRING`, `CONCAT`; flow — `COALESCE`, `IF`, `NULLIF`,
`GREATEST`, `LEAST`;
numeric — `ABS`, `ROUND`, `FLOOR`, `CEIL`/`CEILING`, `TRUNC`/`TRUNCATE`,
`MOD`, `POW`/`POWER`, `SIGN`; math — `SQRT`, `CBRT`, `EXP`, `LN`, `LOG(x)`,
`LOG(base, x)`, `LOG10`, `LOG2`; trig — `SIN`, `COS`, `TAN`, `ASIN`, `ACOS`,
`ATAN`, `ATAN2(y, x)`, `SINH`, `COSH`, `TANH`, `DEGREES`, `RADIANS`;
constants — `PI()`, `E()`; non-deterministic — `RAND()` / `RAND(seed)`;
temporal — `CURRENT_DATE`, `CURRENT_TIMESTAMP`. NULL propagates through every
math function; `GREATEST`/`LEAST` skip NULLs and return NULL only when every
argument is NULL. `MOD(a, b)` is `a % b`, typed the same way.

`CURRENT_DATE` and `CURRENT_TIMESTAMP` take every spelling other engines accept
(`current_date`, `CURRENT_DATE()`, DB2's `CURRENT DATE`). Both read the wall
clock once per query, in UTC, so every row of a result sees the same instant and
`CURRENT_DATE` is that instant's date. They are not the job's execution time: a
re-run reads the clock afresh, so SQL that should follow the execution time uses
the [templating variables](#temporal-templating) instead, as in
`DATE '{{ iso_date }}'`. There is no `CURRENT_TIME`, because there is no TIME
type.

**Operators:** `+ - * / %`, `=  <>  <  <=  >  >=`, `AND OR NOT`, `||` (concat),
`LIKE` and its case-insensitive form `ILIKE`, `IS NULL` / `IS NOT NULL`,
`IN (...)`, `BETWEEN`, `CASE WHEN`, `CAST(expr AS type)`. In a `LIKE` / `ILIKE`
pattern `\` makes the next `%` or `_` literal; `ESCAPE 'c'` names another
character, and `ESCAPE ''` turns escaping off. Grouping parentheses work anywhere an expression does —
`SELECT (a + b) * c`, `WHERE (x = y)`, `ON (a = b)`, `HAVING (COUNT(*) > 1)` —
and are transparent to column naming (`SELECT (a)` yields a column named `a`)
and to ordinals (`ORDER BY (1)` is position 1). Row-value constructors
(`(a, b) = (c, d)`) are not supported.

**Types in CAST:** `INT`, `INTEGER`, `BIGINT`, `LONG`, `FLOAT`, `REAL`,
`DOUBLE`, `STRING`/`VARCHAR`/`TEXT`/`CHAR`, `BOOLEAN`/`BOOL`, `DATE`,
`TIMESTAMP`. A `DECIMAL` or `BINARY` column comes from a declared schema or a
parquet file; neither type is a CAST target. A number
widens to a `DECIMAL` where the two meet, and a `BINARY` casts to nothing.

Text casts to a number as Java parses one, which is also what CSV inference
reads: `'10'`, `'-2.5'` and `'1e3'` are numbers, and an `INT` or `BIGINT` reads
an integer only. Text casts to `BOOLEAN` from `true` or `false`, in any case.
Other text is an error that names it, such as `Cannot cast 'x10' to int`, and no
text quietly becomes `false`.

Text casts to `TIMESTAMP` from an ISO date, optionally followed by a time (after
`T` or a space) and then `Z` or an offset such as `+02:00`. A time with no zone
is UTC and a date alone is midnight, so `'2026-01-01 05:30:21'`,
`'2026-01-01T05:30:21Z'` and `'2026-01-01 07:30:21+02:00'` are one instant. Text
casts to `DATE` from an ISO date (`2026-01-01`) and nothing else: which date a
time of day falls on depends on its zone, so `CAST(CAST(x AS TIMESTAMP) AS DATE)`
spells that out. A `TIMESTAMP` casts to `DATE` as its date in UTC, and a `DATE`
to `TIMESTAMP` as its midnight. `DATE '2026-01-01'` and
`TIMESTAMP '2026-01-01 05:30:21'` are literals, read when the query is bound like
any string literal under a CAST, so a malformed one fails before anything runs. A
number or a boolean does not cast to or from either type: an epoch in days,
seconds or milliseconds would be a guess.

**Values of different types meet only as numbers, or through a string
literal.** Wherever values meet — a comparison, `BETWEEN`, an `IN` list, a
`CASE` switch, arithmetic — numbers widen to the widest among them. A `DECIMAL`
is the widest: an integer meets one exactly, and a `FLOAT` or `DOUBLE` as the
decimal it prints as, so `price = 0.1` finds a `0.10`; `NaN` and the infinities
have no decimal value, and one meeting a `DECIMAL` is an error. (Arithmetic on
a `DECIMAL` reads its other side its own way, below.) A string
literal beside another type is read as that type when the query is bound, so
`i < '10'` compares numbers, `b = 'TRUE'` booleans and
`ts < '2026-01-01 06:00:00'` instants, and a literal that is no such value
(`i = '9.5'` for an `INT`, `d = '2026-1-5'`) is an error before anything runs.
A `DECIMAL` reads no string literal (write the number), and a `BINARY` meets
only another `BINARY`.
Every other mix is an error, which names the CAST that would say what is meant:
beside a string column, CAST the column, or the other side to `STRING` to compare
text; a boolean beside a number takes `CAST(b AS INT)` for 1 and 0; and the two
temporal types take a CAST either way (`CAST(d AS TIMESTAMP) < ts` compares at
the date's midnight, `d = CAST(ts AS DATE)` by the timestamp's date in UTC). A
number and a date do not meet at all. Engines that compare such
pairs as text, or promote a value quietly, answer questions nobody asked — as
text, `9 < '10'` is false. This one prefers an error to a guess. Arithmetic is on
numbers only: `x + '1'` adds one, and `s + 1` over a string column is an error,
as is any arithmetic on a boolean, a date or a timestamp. `||` joins text.

**`DECIMAL` arithmetic is exact, and declares a type that holds its
result.** `+ - * / %` (and `MOD`) on a `DECIMAL` type their result as Spark
and SQL Server do. For a `DECIMAL(p1,s1)` and a `DECIMAL(p2,s2)`, `+` and `-`
keep the larger scale and add an integer digit for the carry, `*` is a
`DECIMAL(p1+p2+1, s1+s2)`, `/` takes the scale `max(6, s1+p2+1)`, and `%` the
larger scale and the narrower integer part; unary minus keeps its operand's
type. Beside a `DECIMAL`, an `INT` counts as the `DECIMAL(10,0)` its every
value fits, a `BIGINT` as a `DECIMAL(19,0)`, and a number written in the query
as the decimal of just its digits, so `price * 1.1` is exact (`1.1` is a
`DECIMAL(2,1)`). Past 38 digits a type gives way on its scale, down to six
digits, and keeps its integer part. A result is rounded half up to its type's
scale, and one whose integer part is too long for the type is an error naming
it (`Decimal overflow: ... does not fit decimal(38,0)`), never a rounded or a
NULL answer; division or `%` by zero is NULL, as for integers. A `FLOAT` or
`DOUBLE` column has no scale for a decimal result to keep, so arithmetic
between one and a `DECIMAL` is an error before the query runs: CAST the
`DECIMAL` to `DOUBLE` to compute in `DOUBLE`. `SUM` and `AVG` over a `DECIMAL`
are exact too (see Aggregates), so a decimal result never depends on row
order, partitions or spill, and parquet writes it as it is.

**A `BINARY` value is not text.** It casts to nothing else, and `LIKE`, `||`
and the string functions (`LENGTH`, `UPPER`, `LOWER`, `TRIM`, `SUBSTRING`,
`CONCAT`) refuse one before the query runs, as does a `WHERE` or a `CASE`
result that would read one as a boolean or a string. Each of these used to read
the array's identity, text like `[B@1a96d94c` that changed on every run.

**CTEs (`WITH`):** supported, non-recursive. `WITH a AS (...), b AS (SELECT ...
FROM a) SELECT ... FROM b` — later CTEs may reference earlier ones, a CTE can
carry an explicit column-alias list (`WITH c(x, y) AS (...)`), and a CTE name
shadows a catalog view of the same name. A CTE referenced **two or more times**
is materialized once into an in-memory result that every reference replays — so
`FROM c x JOIN c y` executes the body once, not twice (and a non-deterministic
body like `RAND()` yields the same rows to every reference). A CTE referenced at
most once stays inlined (zero overhead, streaming preserved); a declared-but-
unused CTE is never executed. Materialization buffers the body in heap.
`WITH RECURSIVE` is rejected.

**Subqueries:** not in v1 (scalar, IN/EXISTS, derived tables in FROM). Use a
`WITH` CTE, or chain results across a multi-task pipeline with `viewName`.

**NULL handling:** three-valued logic in boolean contexts; NULL propagates
through arithmetic and string ops; `IS NULL` / `IS NOT NULL` for explicit
checks.

**`NaN` and `-0.0`:** equality follows IEEE and ordering follows a total order,
so on `FLOAT` / `DOUBLE` columns `NaN = NaN` is FALSE but `NaN <= NaN` is TRUE,
and `-0.0 = 0.0` is TRUE but `-0.0 < 0.0` is also TRUE. `IN` follows equality, so
`NaN IN (NaN)` is FALSE — matching `NaN = NaN` and therefore **differing from
Postgres**, which treats `NaN` as equal to itself. Ordering matches Postgres:
`NaN` sorts above every other value. Everything that orders follows that one
total order — `ORDER BY`, `GREATEST`/`LEAST`, the `MIN`/`MAX` aggregates
(including as window functions), and the ORDER BY peer groups `RANK` /
`DENSE_RANK` share a rank on — so `MAX` over a column containing `NaN` is `NaN`,
`MIN` over one is the smallest non-`NaN` value, `MIN` over `0.0` and `-0.0` is
`-0.0`, and `DENSE_RANK()` over a partition whose order key is `NaN` on every row
is `1` on every row (while `-0.0` and `0.0` rank 1 and 2, since the order
separates them). Those answers do not depend on row order, partition count or
whether the operator spilled. Both evaluation paths (row-at-a-time and
vectorized) agree on all of this; see [docs/gotchas.md](docs/gotchas.md) for why
the two operator families differ.

**Integers, decimals and byte arrays compare exactly.** Two integers compare as
64-bit integers, so `9007199254740993 = 9007199254740992` is FALSE, though a
`DOUBLE` cannot tell the two apart. A `DECIMAL` compares by value at full
precision, and its scale is only how it is written: `1.0 = 1.00` is TRUE, and
`GROUP BY`, `DISTINCT`, `UNION`, `COUNT(DISTINCT …)`, `PARTITION BY` and join
keys all treat the two as one value. A column does not hold every value at its
declared scale (CSV text keeps the scale it was written with), so a grouping
key reads back at the column's declared scale, `1.000` in a `DECIMAL(10,3)`,
whichever spelling arrived first, and so do `MIN` and `MAX`, and any value
written to parquet and read back, a spilled one included. `BINARY` values
are equal when their bytes are, and order byte by byte as unsigned values, so a
byte `0x7f` sorts before `0x80` and a prefix sorts before the longer array.
`ORDER BY`, `MIN`/`MAX`, `GREATEST`/`LEAST` and `RANK` peers all follow these
orders, whatever the row order, partition count or spill.

**Join planning:** for hash joins the planner picks the build side based on
estimated row counts. INNER joins build the smaller side when the size ratio
exceeds 2×; LEFT outer always builds right (left stays on probe to preserve
unmatched-left rows); RIGHT outer always builds left (right stays on probe);
FULL outer keeps the default. Output schema and column order are always
`left ++ right` regardless of which side ends up in the hash — `SELECT a.*,
b.*` keeps its layout. Non-equi joins (no equality conjunct) work for tiny
inputs but are refused when both sides expose a row count and the smaller
side exceeds 5000 rows — at that scale you should add an equality key.

## Temporal templating

`TemplateRenderer` substitutes `{{ ... }}` expressions in SQL strings and
output paths against a `TemporalVariables.executionTime` (defaults to job-start
wall clock). All times are interpreted in **UTC**.

Every variable supports `± N` in its natural unit. The reference time
`2026-01-01T05:30:21Z` produces, for example:

| Template | Result | Unit for arithmetic |
|---|---|---|
| `{{ today }}` | `20260101` | days |
| `{{ today - 5 }}` | `20251227` | days |
| `{{ yesterday }}` | `20251231` | days |
| `{{ tomorrow }}` | `20260102` | days |
| `{{ iso_date }}` | `2026-01-01` | days |
| `{{ iso_datetime }}` | `2026-01-01T05:30:21` | seconds |
| `{{ iso_timestamp }}` | `2026-01-01T05:30:21Z` | seconds |
| `{{ year_month }}` | `202601` | months |
| `{{ current_year }}` | `2026` | years |
| `{{ current_month }}` / `{{ current_month_pad }}` | `1` / `01` | months |
| `{{ current_day }}` / `{{ current_day_pad }}` | `1` / `01` | days |
| `{{ current_hour }}` / `{{ current_hour_pad }}` | `5` / `05` | hours |
| `{{ current_minute }}` / `{{ current_minute_pad }}` | `30` / `30` | minutes |
| `{{ current_second }}` / `{{ current_second_pad }}` | `21` / `21` | seconds |
| `{{ current_dow }}` | `4` (1=Mon … 7=Sun) | days |
| `{{ current_doy }}` | `1` | days |
| `{{ current_week }}` / `{{ current_week_pad }}` | `1` / `01` | weeks |
| `{{ current_quarter }}` | `1` | quarters (= 3 months) |
| `{{ epoch_seconds }}` | `1767245421` | seconds |
| `{{ epoch_millis }}` | `1767245421000` | milliseconds |
| `{{ year }}`/`{{ month }}`/`{{ day }}`/`{{ hour }}`/`{{ minute }}`/`{{ second }}` | aliases for `current_*` | |

Compound offsets work the way you'd guess: `{{ yesterday - 1 }}` is
`20251230` (today minus 2 days). Arithmetic on padded variants stays padded:
`{{ current_hour_pad - 5 }}` is `00`.

Unknown variables raise `IllegalArgumentException` listing all known names.

## Project layout

```
.
├── MODULE.bazel               Bazel deps: rules_scala 7.0.0, scala 2.13.16,
│                              jsqlparser 5.0, parquet-hadoop 1.14.3 (+ minimal
│                              hadoop 3.4.3), openjfx 21.0.1 (gui only), junit
├── .bazelrc                   JDK 21 build toolchain (runtime is JDK 21+)
├── docs/                      Contributor documentation (architecture,
│                              conventions, extending, gotchas, testing).
├── examples/                  Bazel-deployable example apps (programmatic,
│                              directory-driven, jaffle-shop, GUI launcher).
├── tools/                     Stand-alone CLIs (e.g. `parquet_peek`).
├── src/main/scala/com/transformer/
│   ├── core/                  Shared types: DataType, Schema, ColumnarBatch,
│   │                          Catalog, SqlExecutor boundary + registry.
│   ├── temporal/              TemporalVariables, TemplateRenderer.
│   ├── read/{csv,parquet}/    Format-specific readers.
│   ├── sql/{parse,plan,exec}/ JSqlParser façade, logical plan + builder,
│   │                          physical operators + planner + engine.
│   ├── write/{csv,parquet}/   Format-specific writers + shared ParquetSchema.
│   ├── job/                   User-facing API: InputFilePath, OutputFilePath,
│   │                          SQLTask, DataJob runner, DirectoryJobLoader,
│   │                          TaskDag, TaskRunRecord, JobRunRecord,
│   │                          JobOutputLayout, Json parser.
│   └── gui/                   JavaFX visualiser/runner.
└── src/test/scala/com/transformer/...   (mirrors src/main/scala layout)
```

For the full module-by-module breakdown — key files, responsibilities,
cross-cutting patterns — see
[docs/architecture.md](docs/architecture.md#module-map).

## Building your own job

Create a `scala_binary` target. Minimal `BUILD.bazel`:

```python
load("@rules_scala//scala:scala.bzl", "scala_binary")

scala_binary(
    name = "my_job",
    srcs = ["MyJob.scala"],
    main_class = "com.acme.MyJob",
    deps = [
        "//src/main/scala/com/transformer/core",
        "//src/main/scala/com/transformer/job",
        "//src/main/scala/com/transformer/temporal",
        # add this if you read or write parquet:
        # "//src/main/scala/com/transformer/read/parquet",
    ],
)
```

Then:

```bash
bazel build //path/to/your:my_job_deploy.jar
java -jar bazel-bin/path/to/your/my_job_deploy.jar [args]
```

The `_deploy.jar` is fat — all transitive deps inlined. Ship it to anywhere with
JDK 21 or newer (tested through JDK 25).

## Limitations

- **Single-node**: heavy workloads can OOM if the JVM heap is too small.
  Mitigations: give the JVM more heap, OR opt into **spill-to-disk** for
  `ORDER BY` / `GROUP BY` / `DISTINCT` / equi `JOIN` / window functions
  with a non-empty `PARTITION BY` (the five operators that currently
  support it) by adding to a task's `output.json`:

  ```json
  {
    "options": {
      "spill": "true",
      "spill_threshold_bytes": "1073741824"
    }
  }
  ```

  Spill defaults to **off** per task (no overhead when not needed) and is
  not exposed as a global toggle. Temp files land under
  `${java.io.tmpdir}/transformer-spill` by default — override via the
  `transformer.spill.dir` system property. DISTINCT aggregates
  (`COUNT`, `SUM` and `AVG(DISTINCT …)`) silently disable spill on the whole
  operator (their state doesn't round-trip through a typed schema in v1).
- **No subqueries** (scalar, IN/EXISTS, derived tables in FROM): use a `WITH`
  CTE, or chain `SQLTask`s via `viewName`.
- **Window functions: `ROWS` frames only.** They are supported (see
  [SQL features](#sql-features) for the list), but a `RANGE` frame is executed
  as `ROWS`, so a running aggregate does not share one value across `ORDER BY`
  peers the way the standard requires. `COVAR_SAMP`/`COVAR_POP`/`CORR` are
  `GROUP BY` only.
- **Cloud paths recognized but unimplemented**: see v1.1 above.
- **A CSV glob's schema is inferred from its first file**; mixed schemas across
  files in the same view are not validated. Explicit schemas via the
  `inferSchema = false` route avoid this. A parquet glob checks every file
  against the first when it is opened (see [Parquet](#parquet)).
- **`DECIMAL` math functions and statistics compute in `DOUBLE`.** Comparison,
  arithmetic, `ABS`, `SUM` and `AVG` keep a `DECIMAL` exact, but `ROUND`,
  `FLOOR`, `CEIL`, `TRUNC`, `SQRT`, `POWER` and the other math functions, and
  `STDDEV` / `VARIANCE`, read a decimal as a `DOUBLE` and return one. There is
  no `CAST` to a `DECIMAL`, so a query cannot choose a decimal result's type.

## Performance instrumentation

Per-operator metrics are **off by default** — the engine is byte-for-byte the
un-instrumented baseline. Three opt-in routes:

```bash
# Global enable via env var (every task in this JVM gets metrics):
TRANSFORMER_METRICS_ENABLED=1 java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar

# Per-task enable via output.json (wins over the global default):
# tables/<table>/output.json:
# { "options": { "metrics": "true" } }

# Or via sysprop:
java -Dtransformer.metrics.enabled=true -jar ...
```

When enabled, each task with an `outputFile` writes a sibling `_perf.json` next
to `_run.json` containing the per-operator counter tree (rows in/out, batches,
wall nanos, plus operator-specific counters like `groupCount`, `bytesRead`,
`spillEvents`). The `job.json` manifest gains a `perfManifest` array listing
every `_perf.json` the run produced so post-processing tools find them in one
read.

See [docs/benchmarking.md](docs/benchmarking.md) for the full counter inventory,
the JMH microbench harness under `benchmarks/micro/`, the macro-bench runner
under `benchmarks/macro/`, and the perf-tagged regression test.

## Running tests

```bash
bazel test //...                                     # everything
bazel test //src/test/scala/com/transformer/...      # same, different syntax
bazel test //src/test/scala/com/transformer/sql/...  # just the SQL tests
```

Each leaf test directory has its own `scala_junit_test` target.

## License

To be added.
