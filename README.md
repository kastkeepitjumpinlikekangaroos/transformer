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
  job's execution time), with `_SUCCESS` markers preserving the temporal
  variables used to produce each partition
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
```

The programmatic example reads two CSVs, joins them, aggregates spend per user
tier with a templated output path (`day=20260101/`), runs a validation, and
exits 0. The directory example is the same job re-expressed as a folder of
JSON configs and SQL files — see
[Defining jobs from a directory](#defining-jobs-from-a-directory). The GUI is
a thin reader over either layout; see [GUI](#gui).

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
and durations. If a validation fails the corresponding task is marked
`ValidationFailed`, its output is persisted regardless (for debugging), and a
CSV of failure samples is dumped to `validationResultsOutput` (defaults to
`validation_results/{{ epoch_millis }}.csv`).

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

## Run markers and historical runs

After every successful task with an `outputFile`, the runner atomically
writes a `_SUCCESS` file into the output directory:

```
/tmp/out/day=20260101/spend_by_tier/_SUCCESS
/tmp/out/day=20260101/spend_by_tier/part-00000.csv
```

`_SUCCESS` is a small JSON blob capturing:

```json
{
  "executionTime": "2026-01-01T05:30:21Z",
  "writtenAt":     "2026-05-18T03:17:03.099Z",
  "rowsProduced":  2,
  "format":        "csv",
  "outputFiles":   ["part-00000.csv"]
}
```

The underscore prefix means CSV/Parquet readers skip it automatically (via
`PathGlob.expand`'s dotfile/underscore filter). The file is intentionally
informative — it preserves the **temporal variables used to produce the
output**, so a later viewer can show "this partition was written by execution
time T" even after the producing process is gone.

`RunMarker.discover(templatedPattern)` glob-walks the filesystem and returns
every directory matching the pattern that contains a `_SUCCESS`, sorted
newest-first. This is what powers the GUI's partition picker — open a job
that's been run a few times against `out/day={{today}}/<view>` and the picker
lists every historical `day=*` partition with its preserved execution time.

`_SUCCESS` is NOT written for failed tasks or for tasks whose validations
failed (the part files are still on disk, but the directory is unblessed).
Failures and skips never produce a marker.

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
  `_SUCCESS` provenance.
- **Center bottom** — output data table (with a partition picker above it
  when 2+ historical runs exist for the activated task), plus a run log tab.

Node colors:

| Color | Meaning |
|---|---|
| Grey | Pending — never run, no `_SUCCESS` on disk |
| Blue | Running |
| Green | Succeeded (either freshly this session, or hydrated from `_SUCCESS`) |
| Red | Failed |
| Orange | Validation failed |
| Light grey + dashed | Skipped (upstream failure) |

When you open a job directory the GUI calls `RunMarker.discover` on each
task's templated output path, hydrates UI state with the most-recent
discovered run, and exposes the full list via the partition picker — so you
can flip between historical runs without re-running anything.

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

Parquet support is loaded by adding `//src/main/scala/com/transformer/read/parquet`
to your `scala_binary` deps. The first reference auto-installs hooks the job
runner uses.

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

**Aggregates:** `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)`, `SUM`, `AVG`,
`MIN`, `MAX`.

**Scalar functions:** `LENGTH`/`LEN`, `UPPER`, `LOWER`, `TRIM`, `SUBSTRING`,
`CONCAT`, `COALESCE`, `IF`, `NULLIF`, `ABS`, `ROUND`, `FLOOR`, `CEIL`/`CEILING`,
`MOD`, `POW`/`POWER`, `CURRENT_DATE`, `CURRENT_TIMESTAMP`.

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
│                              hadoop), openjfx 21.0.1 (gui only), junit
├── .bazelrc                   JDK 21 toolchain
├── docs/                      Contributor documentation (architecture,
│                              conventions, extending, gotchas, testing).
├── examples/                  Bazel-deployable example apps (programmatic,
│                              directory-driven, jaffle-shop, GUI launcher).
├── tools/                     Stand-alone CLIs (e.g. `parquet_peek`).
├── src/main/scala/com/transformer/
│   ├── core/                  Shared types: DataType, Schema, ColumnarBatch,
│   │                          Catalog, SqlExecutor boundary + registry.
│   ├── temporal/              TemporalVariables, TemplateRenderer.
│   ├── read/{csv,parquet}/    Format-specific readers + parquet hook installer.
│   ├── sql/{parse,plan,exec}/ JSqlParser façade, logical plan + builder,
│   │                          physical operators + planner + engine.
│   ├── write/{csv,parquet}/   Format-specific writers + shared ParquetSchema.
│   ├── job/                   User-facing API: InputFilePath, OutputFilePath,
│   │                          SQLTask, DataJob runner, DirectoryJobLoader,
│   │                          TaskDag, RunMarker, parquet hooks, Json parser.
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
a JDK 21.

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
