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
  ORDER BY, LIMIT, DISTINCT, UNION/UNION ALL, CASE, CAST, scalar functions
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
  (≈27M rows) so the single-partition single-threaded parquet scan finishes
  in a few minutes — remove the `timestamp_received < …` clause in
  `tables/stg_orderbook/main.sql` to process the full ≈131M-row day.
- Produces 17 parquet outputs in a staging → intermediate → mart → final
  layering. Every output is partitioned by `day={{ today }}`. Every task
  (including the deliberately-failed and skipped ones) gets a `_run.json`
  record, and the whole run is rolled up into `job.json` next to the output
  dir — so the GUI's run picker can replay this run's exact statuses later.
- Carries 58 validations across the 17 tables (2-5 per table), all in the
  DBT-style "this query should return zero rows" shape. Failure samples for
  the intentionally-failing validation land in
  `<output>/mart_orderbook_quality_check/.../_validation-zzz_no_observable_snapshot_latency_intentional_failure.csv`.
- **Intentionally fails one branch:** `mart_orderbook_quality_check` has a
  validation that asserts no market has snapshot latency above zero. Real-feed
  data does, so the validation fails, the task is marked `ValidationFailed`,
  and its downstream `mart_quality_report` is `Skipped`. The other three mart
  branches (overview, high-activity, volatility) continue and feed
  `final_combined_report`. The launcher exits 0 iff this exact pattern holds.

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
# 15 Succeeded, 1 ValidationFailed (mart_orderbook_quality_check),
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
| `compression` (output, parquet only) | `snappy` | `uncompressed` / `snappy` / `gzip` |

When `inferSchema = false`, pass an explicit schema:

```scala
InputFilePath("data.csv",
  viewName = "t",
  options  = Map("inferSchema" -> "false", "header" -> "false"))
// then set columns programmatically via CsvOptions if you build the reader
// directly; or build CsvOptions from the option bag and override columns.
```

### Parquet

- Read: `parquet-hadoop` with `GroupReadSupport`, local FS only. One row group
  per partition for built-in parallelism.
- Write: `ExampleParquetWriter` with `GroupWriteSupport`, snappy by default.
- All transformer primitive types map: `Int`, `Long`, `Float`, `Double`,
  `Boolean`, `String` (UTF-8 logical type), `Binary`, `Date` (INT32 days),
  `Timestamp` (INT64 micros).

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
SELECT [DISTINCT] <projections>
FROM <table> [<alias>]
[ {INNER|LEFT|RIGHT|FULL} JOIN <table> [<alias>] ON <predicate> ]*
[ WHERE <predicate> ]
[ GROUP BY <exprs> [ HAVING <predicate> ] ]
[ ORDER BY <exprs> [ASC|DESC] ]
[ LIMIT <n> ]
```

**Aggregates:** `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)`, `COUNT_IF(pred)`,
`SUM`, `AVG`, `MIN`, `MAX`,
`STDDEV`/`STDDEV_SAMP`/`STDDEV_POP`, `VARIANCE`/`VAR_SAMP`/`VAR_POP`,
`COVAR_SAMP`/`COVAR_POP`, `CORR`. Univariate variance/stddev (and `COUNT_IF`)
are also available as window aggregates with `OVER (...)`. Variance and stddev
use Welford + Chan's parallel merge for numerical stability; `*_SAMP` returns
NULL when fewer than two non-NULL inputs are present.

**Scalar functions:** strings — `LENGTH`/`LEN`, `UPPER`, `LOWER`, `TRIM`,
`SUBSTRING`, `CONCAT`; flow — `COALESCE`, `IF`, `NULLIF`, `GREATEST`, `LEAST`;
numeric — `ABS`, `ROUND`, `FLOOR`, `CEIL`/`CEILING`, `TRUNC`/`TRUNCATE`,
`MOD`, `POW`/`POWER`, `SIGN`; math — `SQRT`, `CBRT`, `EXP`, `LN`, `LOG(x)`,
`LOG(base, x)`, `LOG10`, `LOG2`; trig — `SIN`, `COS`, `TAN`, `ASIN`, `ACOS`,
`ATAN`, `ATAN2(y, x)`, `SINH`, `COSH`, `TANH`, `DEGREES`, `RADIANS`;
constants — `PI()`, `E()`; non-deterministic — `RAND()` / `RAND(seed)`;
temporal — `CURRENT_DATE`, `CURRENT_TIMESTAMP`. NULL propagates through every
math function; `GREATEST`/`LEAST` skip NULLs and return NULL only when every
argument is NULL.

**Operators:** `+ - * / %`, `=  <>  <  <=  >  >=`, `AND OR NOT`, `||` (concat),
`LIKE`, `IS NULL` / `IS NOT NULL`, `IN (...)`, `BETWEEN`, `CASE WHEN`,
`CAST(expr AS type)`.

**Types in CAST:** `INT`, `INTEGER`, `BIGINT`, `LONG`, `FLOAT`, `REAL`,
`DOUBLE`, `STRING`/`VARCHAR`/`TEXT`/`CHAR`, `BOOLEAN`/`BOOL`, `DATE`,
`TIMESTAMP`.

**Subqueries:** not in v1. Use a multi-task pipeline with `viewName` to chain
results.

**NULL handling:** three-valued logic in boolean contexts; NULL propagates
through arithmetic and string ops; `IS NULL` / `IS NOT NULL` for explicit
checks.

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

- **Single-node**: large hash-joins / aggregations / sorts can OOM. Mitigation:
  give the JVM more heap. Spill-to-disk is not in v1.
- **No subqueries**: chain `SQLTask`s via `viewName`.
- **No window functions** (`OVER (PARTITION BY ...)`): planned post-v1.
- **Cloud paths recognized but unimplemented**: see v1.1 above.
- **Schema is inferred from the first file** in a glob; mixed schemas across
  files in the same view are not validated. Explicit schemas via the
  `inferSchema = false` route avoid this.

## Running tests

```bash
bazel test //...                                     # everything
bazel test //src/test/scala/com/transformer/...      # same, different syntax
bazel test //src/test/scala/com/transformer/sql/...  # just the SQL tests
```

Each leaf test directory has its own `scala_junit_test` target.

## License

To be added.
