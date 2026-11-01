# transformer

**SQL data pipelines in a single jar.** A transformer project is a folder:
one directory per input, one `main.sql` per table, and data-quality tests as
SQL files next to the table they check. One jar runs it from the command line
or opens it in a desktop GUI. You don't need a warehouse, a cluster, or a
Python environment, only Java 21.

- **One dependency.** The SQL parser, logical and physical planner, and
  parallel executor are all built into transformer. There's no Spark, Calcite,
  DuckDB, or database behind it. It reads and writes CSV and Parquet. Any
  machine with Java 21 can run a pipeline: a laptop, a CI runner, an Airflow
  worker, or a container.
- **Fast iteration.** Edit a `.sql` file and rerun. The 15-table jaffle-shop
  example (about 150k rows, 26 data tests) finishes in about two seconds,
  JVM startup included.
- **Agent first.** Every project has the same fixed layout of plain files.
  The CLI reports through exit codes, and every run writes JSON run records.
  A coding agent such as Claude Code can scaffold a pipeline, run it, read
  what failed, and fix it, all on the local machine.
- **Fast on big data.** The polymarket example stages 166M rows from a 39 GB
  Parquet dataset, then builds 17 tables and runs 59 validations in 90
  seconds on one Apple M4 (8 cores, 16 GB). See
  [Heavy-load example: polymarket](#heavy-load-example-polymarket).

A second jar, the [data viewer](#data-viewer), opens a folder of job outputs
for SQL over every table the jobs wrote, with each result drawn as a chart if
you like, and for inspecting any of those tables: its rows, its columns, what
its run recorded about it, and its lineage across jobs.

The project website lives in [`site/`](site/). Open `site/index.html` in a
browser; it is static HTML with no build step.

## Quick start

You need **Java 21** (JDK or JRE) to run transformer. To build the jar you
also need [Bazelisk](https://github.com/bazelbuild/bazelisk), which fetches
the pinned Bazel version and its own build JDK.

```bash
# Build the single deploy jar.
bazel build //:transformer_deploy.jar
cp bazel-bin/transformer_deploy.jar transformer.jar

# Run the jaffle-shop example headless. Exits 0 when every task and
# validation passes.
java -Xmx2g -jar transformer.jar run examples/jaffle_shop/job \
    --output /tmp/jaffle-out --execution-time 2026-01-01T00:00:00Z

# Open the same project in the desktop GUI.
java -jar transformer.jar gui examples/jaffle_shop/job
```

The run prints one line per task as it finishes and ends with
`Job SUCCEEDED (15 Succeeded)`. `/tmp/jaffle-out/` then holds one directory
per table (`part-*.parquet` plus a `_run.json` record) and a `job.json`
summary of the whole run.

The website's [setup page](site/setup.html) walks through the same steps,
then shows how to read the jaffle-shop project and how to break a
validation on purpose.

## Defining a pipeline

A pipeline (a "job") is a directory:

```
<jobDir>/
  inputs/
    <viewName>/
      config.json                 # where the input lives (exactly one .json file)
  tables/
    <viewName>/
      main.sql                    # required: the SELECT for this table
      output.json                 # optional: format, partitioning, options
      validations/                # optional data-quality tests
        <validationName>.sql
```

Directory names become the view names that SQL refers to. A table's
`main.sql` reads inputs and other tables by name:

```sql
-- tables/orders/main.sql
SELECT o.order_id, o.customer_id, o.order_total, s.count_order_items
FROM stg_orders o
LEFT JOIN order_items_summary s ON o.order_id = s.order_id
```

A table's result is what other tables, validations and other engines read by
column name, so its columns must each have their own name, compared regardless
of case: `SELECT a, a, b AS A FROM t` as a table's `main.sql` fails that table
before it writes anything, naming the columns, and asks for an `AS`. (A query
inside it, such as a CTE, may repeat a name as SQL allows.)

You never declare run order. transformer builds a DAG from each table's
references and runs independent branches in parallel. Directories are listed
alphabetically, but that order is only a declared index. Numeric prefixes
(`01_raw/`, `02_clean/`) are fine for readability.

### Inputs

Each `inputs/<viewName>/` holds exactly one `.json` file whose fields match
`InputFilePath`:

```json
{
  "path": "data/events.csv",
  "format": "csv",
  "options": {"delimiter": ",", "header": true},
  "cache": true
}
```

Only `path` is required. The format is inferred from the extension, and the
path can be a single file or a glob (`/data/events/*.parquet`). Relative paths
resolve against the job directory, and absolute paths are used as-is. Template
variables (`{{ today }}`, etc.) inside `path` are expanded at run time. The
directory name is the view name. Format options are in
[Supported file formats](#supported-file-formats).

**Every config file is read strictly.** A key the file does not take (an input's
are `path`, `options`, `cache` and `format`; `output.json`'s are `partitionBy`,
`format`, `maxPartitions` and `options`), a key written twice in one object, an
option its format does not read, a value that is none of its option's (a boolean
is `true` or `false`, in any case; a size a whole number of bytes, so `64MB` is
no size) and a `format` other than `csv` or `parquet` each fail the job's load,
naming the file, the key and what it takes. A misspelt key used to be skipped,
so a job with `"formatt": "parquet"` or `"partiton_by": ...` ran having done
something else, and `"spill": "yes"` ran without spill.

### Validations

A validation is a SQL file in the table's `validations/` directory, written
DBT-style: it **passes when it returns zero rows**. It queries the table by
name:

```sql
-- tables/customers/validations/customer_type_accepted_values.sql
SELECT customer_id, customer_type
FROM customers
WHERE customer_type NOT IN ('new', 'returning')
```

When a validation returns rows, the table is marked `ValidationFailed`. Its
output is still written so you can inspect it, a sample of the failing rows
goes to `_validation-<name>.csv` next to the output, and every table
downstream of it is `Skipped`.

### Per-table output config (optional)

`tables/<viewName>/output.json` holds table-level output settings:

```json
{ "format": "parquet", "partitionBy": "day={{today}}" }
```

- `format`: `csv` (the default) or `parquet`.
- `partitionBy`: a templated subdirectory. With it, the output goes to
  `<outputDir>/<viewName>/<partitionBy>`, so the example above writes to
  `<outputDir>/<viewName>/day=20260101/part-NNNNN.parquet` for an execution
  time on Jan 1 2026. A run at another execution time writes a sibling
  `day=20260102/`. Both stay on disk, and the GUI's run picker shows them as
  historical runs of the table.
- `options`: per-task engine options, such as spill-to-disk (see
  [Limitations](#limitations)) and metrics (see
  [Performance instrumentation](#performance-instrumentation)).

## Command line

The `transformer` deploy jar (`bazel build //:transformer_deploy.jar`) is the
whole product for building and running jobs: the engine, the CSV and Parquet
readers and writers, and the GUI. The [data viewer](#data-viewer) is a jar of
its own.

```
transformer run <jobDir> [--output <dir>] [--execution-time <instant>]
transformer gui [jobDir]
transformer help
```

| Option | Default | Meaning |
|---|---|---|
| `--output <dir>` | `<jobDir>/output` | Where every table's output and `job.json` go. Templated, e.g. `/data/out/day={{ today }}`. |
| `--execution-time <instant>` | now | ISO-8601 instant that every `{{ ... }}` template resolves against, e.g. `2026-01-01T00:00:00Z` (an offset such as `+00:00` also works). |

`run` exit codes:

| Code | Meaning |
|---|---|
| `0` | Every task succeeded and every validation passed. |
| `1` | A task failed, failed a validation, or was skipped; or the job could not be loaded. |
| `2` | Bad arguments. |

`run` prints one line per task as it finishes (status, rows, duration, and
the reason when it did not succeed), then any warnings, then a summary such
as `Job FAILED (1 ValidationFailed, 14 Succeeded)`. For the machine-readable
version, read `<output>/job.json` (see
[Run records](#run-records-and-historical-runs)).

`java -jar` uses the JVM's default heap, which is often too small for real
workloads. Pass `-Xmx` (e.g. `-Xmx2g` for jaffle-shop, `-Xmx12g` for
polymarket) or `-XX:MaxRAMPercentage=75.0`. The `bazel-bin/transformer` wrapper
script that `bazel build //:transformer` produces sets the latter for you.

### Scheduling (Airflow, cron, CI)

A scheduled run is one shell command. Pass the scheduler's logical time as
`--execution-time` so that `{{ today }}` in your SQL and paths names the
run's date, and reruns and backfills write to the right partition:

```python
BashOperator(
    task_id="jaffle_shop",
    bash_command=(
        "java -Xmx4g -jar /opt/transformer.jar run /opt/jobs/jaffle_shop "
        "--output /data/jaffle/day={{ ds_nodash }} "
        "--execution-time {{ ts }}"
    ),
)
```

Airflow renders `{{ ... }}` in `bash_command` itself, so this example uses
Airflow's `ds_nodash` for the output path. transformer's own templates in
`config.json`, `main.sql`, and `output.json` are untouched by Airflow.

## Working with a coding agent

transformer is designed so that an agent harness such as Claude Code can own
a pipeline end to end:

- **The layout is the documentation.** Inputs in `inputs/<name>/config.json`,
  tables in `tables/<name>/main.sql`, tests in
  `tables/<name>/validations/*.sql`. An agent can learn it from one example
  (`examples/jaffle_shop/job`) and generate new tables without any API.
- **Runs are local and fast.** `transformer run` needs no credentials, no
  network, and no running service, and small jobs finish in seconds, so the
  agent can afford to rerun after every edit.
- **Results are machine-readable.** The exit code says pass or fail. Each
  failed task prints its error or the validations it failed, `job.json` lists
  every task's status, row count, and failed validations, and
  `_validation-<name>.csv` holds sample offending rows.
- **Tests are SQL.** The agent writes data-quality checks as zero-row queries
  next to the table it's changing, then reruns until they pass.

A short brief is enough to point an agent at a project:

```
This is a transformer project (examples/jaffle_shop/job is a reference).
Inputs live in inputs/<name>/config.json, tables in tables/<name>/main.sql,
tests in tables/<name>/validations/*.sql (pass = zero rows).
Run with: java -jar transformer.jar run <jobDir> --output <dir>
It exits 0 only when everything passes; failures are in <dir>/job.json.
```

Supported SQL is strict. A query that would need a guess, such as an implicit
text coercion or a JOIN with no `ON`, is refused with an error that names the
problem, rather than run with a silently different meaning. An agent gets a
clear error to fix instead of wrong data. See [SQL features](#sql-features).

## GUI

A desktop app to see and run a job: open a job directory, see its DAG, set the
execution time and output directory, press Run and watch each task's status
change live, then browse its rows, its validations and its SQL.

```bash
java -jar transformer.jar gui examples/jaffle_shop/job
# The argument is optional; otherwise use File > Open Job Directory, or
# File > New Project to start an empty job.
```

The GUI ships inside the `transformer` jar with JavaFX for macOS on Apple
Silicon and Windows. On other platforms use `transformer run`. Every control
is in a dark theme ([AtlantaFX](https://github.com/mkpaz/atlantafx)'s Primer
Dark).

Layout:

- **Header** — the open job, the execution time (a UTC date and an `HH:mm:ss`
  time; Now resets both), the output directory (hover it for the rendered
  path), a run picker when the output directory holds two or more run
  snapshots, the run's status (with live progress while it runs) and **Run**
  (⌘R / Ctrl+R). The menus (File, View, Help) are in the macOS menu bar, or
  above the header elsewhere.
- **DAG** — inputs on the left, tables in layers after them. A node's status is
  the colour of the bar down its left edge, and its status line says rows and
  time once it has run. Selecting a node lights up the edges into and out of
  it. Drag to pan, scroll to zoom (or use the controls at the bottom right; ⌘0
  fits the window), click to select, double-click to open its rows. The top
  left counts the tasks in each status.
- **Tabs below** — **Task details** (the selected task's status, metrics,
  dependencies, output path and provenance, error and validation summary,
  and beside them its SQL, source or rendered, editable in place), **Output
  data** (the activated node's rows, with a partition picker above them when
  2+ historical runs exist), **Validations** (a card per validation with its
  status, its SQL, its failing rows, and an Edit… button that opens it in
  the validation editor, which writes back to
  `tables/<view>/validations/<name>.sql`), **SQL console** (ad-hoc SQL over the
  inputs and the task outputs on disk; ⌘⏎ / Ctrl+Enter runs it, its result
  shows as rows or as a [chart](#charts), and Persist writes it), and **Run
  log**.

Node colours:

| Colour | Meaning |
|---|---|
| Grey | Pending — not run yet, or nothing on disk records its run (no `_run.json` naming it, no `job.json` entry) |
| Purple | Queued |
| Blue | Running |
| Green | Succeeded (either freshly this session, or hydrated from `_run.json`) |
| Red | Failed (error message + timing reloaded from disk) |
| Amber | Validation failed (per-validation pass/fail + sample CSV reloaded from disk) |
| Dark grey, "skipped" | Skipped (upstream failure) |
| Teal | An input |

### SQL editors and vim keybindings

Everywhere SQL is shown or written (the SQL console, a task's SQL in Task
details, the validation cards, the Add Table and validation dialogs, the
query-error popup) is one editor: syntax highlighting (keywords, functions,
types, strings, numbers, comments, `{{ template }}` placeholders), line
numbers, and a status line with the cursor's position. A new line keeps the
indent of the one before, and `Tab` indents by two spaces.

The editors take vim's keys. That is on by default; View > Vim Keybindings, or
the VIM button in an editor's status line, turns it off and on for every
editor, and the choice is remembered. The status line shows the mode (NORMAL,
INSERT, VISUAL, V-LINE, REPLACE, COMMAND), the command being typed, and vim's
messages. Help > Keyboard Shortcuts (⌘/) lists the keys. What vim covers:

- Modes: insert (`i a I A o O gi gI`), replace (`R`), visual by character or
  by line (`v V gv`, `o` to the other end), and the command line (`: / ?`).
- Motions, all with counts: `h j k l`, `w b e ge` and `W B E gE`, `0 ^ $ g_`,
  `gg G`, `f t F T ; ,`, `%`, `{ }`, `H M L`, `+ - _ <CR>`,
  `<C-d> <C-u> <C-f> <C-b>`, and search `/ ? n N * #`.
- The operators `d c y > < g~ gu gU` with any motion, with a text object
  (`iw aw iW aW ip ap`, brackets `i( a( ib i[ i{ iB i<`, quotes `` i" i' i` ``,
  and their `a` forms), or doubled for whole lines (`dd`, `>>`, `gUU`); and
  `x X s S D C Y p P gp gP r J gJ ~ <C-a> <C-x>`.
- `u` and `<C-r>` (each change is one undo step, an insert from the command
  that began it to `<Esc>` included), `.`, macros (`q{reg}`, `@{reg}`, `@@`,
  `@:`), and registers: `"a` to `"z` (`"A` to `"Z` append), `"0` to `"9`,
  `"-`, `"_`, and `"+` / `"*` for the system clipboard.
- Ex commands: `:{n}`, `:s/pat/rep/` with `g` and `i` over `%`, `'<,'>` or a
  range of lines (`&` repeats it), `:d :y :j :> :<`, and `:w :q :q! :wq :x`
  with `ZZ ZQ`. In a task's SQL being edited `:w` saves it and `:q` cancels
  the edit; in a dialog `:w` presses Save (or Create) and `:q` Cancel. `:q`
  refuses to drop changes (E37) without the `!`.
- In insert mode: `<C-w> <C-u> <C-t> <C-d> <C-r>{reg}` and the arrow keys.
- Search patterns are vim's regex (`\(…\)`, `\|`, `\<…\>`, `\{n,m}`); `\c`
  makes a search ignore case.

Cursors, counts and indents follow Neovim's defaults (`nostartofline`,
`autoindent`, `cw` changing to the end of the word) with `shiftwidth=2` and
`expandtab`, the indent this repo's SQL uses. Not covered: visual block
mode, marks, folds, the jump list, `<C-e>` / `<C-y>` scrolling, `:set`, and
plugins.

The platform's shortcuts still work: ⌘Z and ⇧⌘Z undo and redo (through vim's
undo), ⌘C and ⌘X copy and cut a visual selection, ⌘V pastes, ⌘A selects
everything. Every other ⌘ shortcut (⌘R, ⌘⏎) is the window's. On Windows Ctrl
is both the shortcut key and vim's: Ctrl+C, X, V, Z and A are the clipboard,
undo and select all there, and the other Ctrl keys are vim's, so Ctrl+R in an
editor is vim's redo, not Run. A click moves the cursor and a drag selects in
visual mode. `Esc` in an editable editor never closes the dialog it is in.

Output data and the SQL console read a task's output as the tasks after it
read it: the part files its run wrote, in the CSV writer's delimiter and with
the column types it was written with, which its `_run.json` records (a column
of digits written as text stays text). Their grids show each column's type
under its name, NULL as `NULL`, numbers to the right, sort a column by value,
and copy the selected rows as tab-separated text with ⌘C.

When you open a job directory the GUI loads `<outputDir>/job.json` if it
exists and follows each task's `runFile` pointer to its per-task
`_run.json` — reconstructing the full status (including validation failure
detail with sample rows) without re-running anything. The picker dropdown
above the output-data table is populated by `TaskRunRecord.discover` over
each task's templated output path, so you can flip between historical
partitions inline. Only a record that names the task counts as its run.

When a task's record is not usable, the GUI says so rather than guessing:

- **Missing or unreadable, or naming another task** (two records swapped, a
  file copied in): it uses the task's own record of the same run if one sits
  under the task's output path, and otherwise rebuilds the task from its
  `job.json` entry — every field but the failing rows of its validations,
  which a validation card then says were not recorded. The run log names each
  record it could not use.
- **No readable `job.json`** in a run directory that exists (the job died
  before writing it, or it was damaged): every task reopens from its own
  record, a task with none stays grey, and the run log says the run's verdict
  and warnings are unknown.
- **A `job.json` from before entries carried timestamps and failed
  validations**: a task rebuilt from it shows its duration and which of its
  validations failed as unknown, not as 0 ms and PASSED.

`DataJob.run(executor, listener)` fires `TaskProgressListener.onTaskStart` /
`onTaskFinish` from runner worker threads; the GUI's Run button installs a
listener that marshals back to the FX thread via `Platform.runLater`.

The GUI module is `//src/main/scala/com/transformer/gui`. `transformer gui`
launches it, and `//examples/gui_app` is a GUI-only launcher. The launcher bundles parquet read+write so the GUI's
preview works for both formats. The session state behind every panel
(`JobSession`, which opens a job directory and rebuilds its last run, and
`DagLayout`) builds on its own as `//src/main/scala/com/transformer/gui:session`,
with no JavaFX, so it can be tested without a display; so does the editors'
vim engine, as `//src/main/scala/com/transformer/gui:vim`.

## Data viewer

A second desktop app, a jar of its own, for what your jobs wrote: open a
folder of job outputs, query every table in it with SQL, chart what a query
returns, and inspect any one of the tables: its rows, its columns, what its
run recorded about it, and how it was made from other tables, within its job
and across jobs.

```bash
bazel build //:transformer_viewer_deploy.jar
java -jar bazel-bin/transformer_viewer_deploy.jar /data/runs
# The folder is optional; otherwise use File > Open Folder.
```

A **dataset** is one job's output: a directory holding the `job.json` its run
wrote. The viewer finds every one under the folder you open, up to six levels
down, so this folder holds three datasets:

```
/data/runs/
  jaffle_shop/          job.json, customers/, orders/, ...  (--output /data/runs/jaffle_shop)
  marketing/
    day=20260101/       job.json, ...                       (--output "/data/runs/marketing/day={{ today }}")
    day=20260102/       job.json, ...
  finance/              job.json, ...
```

The runs of one job writing to a templated directory are one dataset, named
without the part the template fills in: the two `marketing` runs show as
`marketing`, at its newest run, with a run picker in its header. A folder that
is itself one run's directory shows as one dataset, named after the folder.

Layout:

- **Header** — the folder, Open (⌘O) and Reload (⌘R), New query (⌘T), and
  how many datasets and tables the folder holds (and how many `job.json` files
  it could not read).
- **Datasets** (left) — each dataset, its tables (a dot in the colour of each
  table's status, and its rows), and the inputs it read from outside the
  folder. The field above it filters by name (⌘F). Click a table, an input or
  a dataset (or move to it with the arrow keys) to inspect it; its menu also
  queries it in a new tab, puts its name in the query you are writing, shows
  its lineage, and copies its name or path.
- **Workspace** (centre) — tabs of two kinds. A **query tab** is a SQL editor
  above what its query returns, as rows or as a [chart](#charts) (⌘⏎ runs
  it); the first one, when a folder opens, reads the folder's first table. An **inspector tab** shows one table,
  input or dataset: a header with its status, its dataset and run, its rows,
  the name to query it by (with Copy, and Query, which opens a query tab of
  its first 100 rows), and a tab per view of it:
  - **Data** — its first 1,000 rows.
  - **Columns** — each column's name and type (⌘C copies the selected names,
    comma-separated, for a select list).
  - **Details** — its rows, timings and format, where it is, what it reads and
    what reads it, its validations and error, and beside them the SQL that
    made it. A name in it opens that table.
  - **Lineage** — the graph of everything it is made from and everything made
    from it, across jobs (below).
  - A dataset has **Tables** (each table's status, rows and SQL name; a name
    opens it), **Details** (its run, the datasets it reads and that read it,
    its warnings) and **Lineage**.

Opening a table shows it in the **preview** tab, its name in italics, which
the next thing you open replaces, so clicking through tables in the list, or
through the names in a table's details, leaves one tab behind and keeps the
view you are on: click down the list on Columns to compare columns, or on
Lineage to follow the graph. Double-click a table in the list (or press Enter
on any item, or double-click the preview tab) to keep it in a tab of its own. Back (⌘[) and
Forward (⌘]) go through what an inspector tab has shown, ⌘L turns it to its
lineage, and ⌘W closes a tab.

**Lineage.** A table's Lineage view draws what it is made from and what is
made from it, across jobs: each dataset a box with its name and run, its
inputs and tables as cards inside, drawn as the job GUI draws a DAG, inputs
on the left and each table right of what it reads. An edge in a box is a
table reading an input or another table; an edge between boxes, in the input
colour, is a job's input reading another job's table. The graph opens fitted
to the window, every upstream and downstream in view; zoomed out that far,
each card shows just its name, set large enough to read, its status the
colour of its edge. Scroll to zoom in for each card's rows, and Fit (⌘0) to
see all of it again. Click a card to light its own lineage; double-click it to
open it, in the Lineage view. **Whole folder** draws every dataset in the folder instead,
with the table's lineage lit and the rest dimmed.

Lineage comes from the run records. Each `job.json` lists the inputs its run
read, with their paths rendered and made absolute, and what each table's main
SQL reads ([Per-job `job.json`](#per-job-jobjson)). An input whose path is
another dataset's table directory, a file in it, or a glob over its part files
(`/data/runs/marketing/day=20260102/revenue/part-*.csv`) reads that table,
whatever symbolic links the two paths go through. The match is made against
every run of that dataset, so an input that read an older run than the one
shown still joins the table, and its details say which run it read. An input
that matches no table is data from outside the folder.

**Querying.** Every table is named `<dataset>__<table>`, such as
`jaffle_shop__customers`: the engine's catalog is flat, so the dataset and the
table make one name, a double underscore between them, and each part is made a
name SQL reads without quotes (`day=20260101` becomes `day_20260101`). An input
read from outside the folder is `<dataset>__<input>`. An inspector's header
shows each name.

```sql
SELECT s.segment, COUNT(*) AS orders, SUM(o.order_total) AS revenue
FROM marketing__customer_segments s
JOIN jaffle_shop__orders o ON o.customer_id = s.customer_id
GROUP BY s.segment
```

A table reads as the tasks after it read it: the part files its run wrote,
with the column types it was written with. `jaffle_shop.customers` is refused
with the name to write instead, and a table whose task failed in the run shown
says it has no rows. Persist writes a result as part files, under
`<folder>/interactive` unless you choose another directory.

A run written before records carried lineage (before 2026-10-31) still
opens: its tables show their rows and query, but nothing in it says what each
table reads, so each stands alone in a lineage, and its Lineage and Details
views say the lineage was not recorded. The job's next run records it.

The viewer is built from the same components as the job GUI
(`//src/main/scala/com/transformer/gui:components`): the theme, the SQL editor
with its highlighting and vim keys (View > Vim Keybindings is one setting for
both apps), the SQL console and its chart, the grid that shows rows and the
canvas graphs are drawn on. The viewer is
`//src/main/scala/com/transformer/viewer`, and its model (finding the
datasets, the lineage, the layout, the query catalog, and an inspector tab's
views and history) builds on its own as `viewer:model`, with no JavaFX; so
does what a chart draws from a result's rows, as
`//src/main/scala/com/transformer/gui:chart`.

### Charts

A query's result shows twice, a tab each, in a data viewer query tab and in
the job GUI's SQL console alike: **Rows**, its first 1,000 rows, and
**Chart**, every row of it drawn as a **bar**, **line**, **area**,
**scatter** or **pie** chart. The choices above the chart are the type, the
column along **X**, the columns of numbers up **Y** with how the rows that
share an X combine (Sum, Average, Min, Max or Count, as SQL's aggregates
combine them, NULLs left out), and **Split by**, a column whose values each
draw a series of their own. A result starts from a suggestion: X is its first
date or timestamp, drawn as a line, else its first text column, drawn as
bars, and Y the first number after X. Run the query again and the choices
stay wherever its columns allow; a new X brings the type it suggests.

```sql
SELECT CAST(o.ordered_at AS DATE) AS day, l.location_name, SUM(o.order_total) AS revenue
FROM jaffle_out__orders o JOIN jaffle_out__locations l ON o.location_id = l.location_id
GROUP BY CAST(o.ordered_at AS DATE), l.location_name
-- Chart: a line along day, Y revenue, split by location_name: a line per location.
```

- **Order.** Text along X keeps the order the query returns it in, so its
  `ORDER BY` orders the bars; numbers and dates are sorted, and NULL comes
  last. A line, an area or a scatter runs along a number, a date or a
  timestamp as along an axis (times on calendar ticks, in UTC); a bar or a pie
  takes a band or a slice per value of X, whatever its type.
- **Combining.** Rows that share an X are combined over the whole result, not
  just the rows the grid shows, and Y's title says how (`SUM(amount)`) when
  any were. A scatter draws a dot per row, uncombined.
- **Limits.** Up to eight series are drawn, a Y column or a split value each;
  past eight, the seven largest are drawn and the rest combined as Other, in
  grey, as a pie's slices past seven are. A bar chart draws at most 500 bands,
  a line or an area 10,000 values of X and a scatter its first 10,000 rows.
  The note under the chart counts the rows and says what was left out: rows
  past those, rows whose X is NULL on an axis, values that are NaN or
  infinite. A pie refuses values below 0.
- **Reading it.** The pointer reads what is under it: a bar's band, every
  series at the X nearest it on a line or an area, the nearest dot, a slice
  and its share. The Rows tab has every value too.
- **Room.** In the job GUI the console sits under the DAG; drag the divider
  between them up for a taller chart. A chart less than 240px tall leaves its
  axes untitled, since the choices above it name X and Y.

**Save PNG…** writes the chart as it shows, at twice its size on screen, in
the viewer's dark theme, named after what it draws (`revenue_by_month.png`).

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
  "options":       {"delimiter": "|"},
  "schema": [{"name": "tier", "type": "string", "nullable": true},
             {"name": "spend", "type": "decimal(38,2)", "nullable": true}],
  "validations": [
    { "name": "customer_id_unique", "passed": false, "failedRowCount": 7,
      "sampleFile": "_validation-customer_id_unique.csv" },
    { "name": "row_count_positive", "passed": true, "failedRowCount": 0,
      "sampleFile": null }
  ],
  "sql": "SELECT tier, SUM(spend) AS spend\nFROM customers\nGROUP BY tier"
}
```

`options` (the table's `output.json` options) and `schema` (each column's
name and type, absent for a task that wrote nothing) say how to read the part
files back as they were written, which a CSV file alone cannot: its delimiter
and header, and its column types, so a column of digits written as text reads
back as text. The tasks after the task, the GUI's previews and the data viewer
all read an output this way (`TaskRunRecord.outputView`). `sql` is the task's
main SQL with its templates rendered: how the rows were made. A record from
before these fields still reads; its CSV output reads with inferred types.

`enqueuedAt` is the moment all of the task's inputs and upstream task results
were available — i.e. when the runner *would* have started the task if the
shared worker pool had a free thread. `startedAt - enqueuedAt` is the
queue-wait time the GUI surfaces alongside the run duration, so you can tell
"this task ran fast but waited 3s for a worker" from "this task ran for 3s".

A failed task's `errorMessage` is its failure's own message, such as
`Cannot cast 'x' to int` for a value a CAST cannot read while the query runs,
or the bind error that names what the query got wrong. Whatever a task throws
fails it, a JVM `Error` too (a stack overflow reads `the query nests too deeply
for the thread's stack`), and the run goes on. A task that runs out of memory
stops the run instead: no task starts after it, each that had not started is
`Skipped` with `not run: the job stopped after task 'x' failed: out of memory
(...)`, and the run's error (`JobResult.error`, and `errorMessage` in
`job.json`) says the same.

The underscore prefix means CSV/Parquet readers skip both the record and the
validation samples when reading the directory as data. The tasks after a task,
and its validations, read exactly the part files its run wrote, by name, so
another file in the directory (a README) is never read as data. A run clears a
directory its earlier run recorded, deleting the files a run writes there
(`part-*`, `_*`, `.*`) and leaving any other file alone. A directory holding
`part-*` files that no `_run.json` names, left by a run killed between clearing
and recording, another job writing there or a copy, fails the task before it
writes, naming the files: delete them or write elsewhere. Such a directory is
not marked as the job's either, so no later run deletes those files.
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
`_run.json` plus a summary: status, rows, error, its `startedAt` /
`finishedAt` / `enqueuedAt`, and the name and row count of each validation it
failed — everything the task's record holds but the failing rows themselves.
Loading this one file is the GUI's entry point for hydrating the whole job;
per-task records are loaded lazily as the user drills into each task. A
`job.json` written before entries carried the timestamps and failed
validations still reads, without them.

It also records the run's lineage, which the [data viewer](#data-viewer) draws:

```json
{
  "outputTemplate": "/data/runs/marketing/day={{ today }}",
  "inputs": [
    {"name": "customers", "path": "/data/runs/jaffle_shop/customers", "format": "parquet", "options": {}}
  ],
  "tasks": [
    {"taskName": "customer_segments", "status": "Succeeded",
     "runFile": "/data/runs/marketing/day=20260102/customer_segments/_run.json", ...,
     "readsInputs": ["customers"], "readsTasks": []}
  ]
}
```

`inputs` holds every input as the run read it: its path with its templates
rendered and made absolute (a cloud path as written), its format and options.
Each task's `readsInputs` and `readsTasks` name what its main SQL reads; a
table only a validation reads is not where its rows come from, so it is not
listed. `outputTemplate` is the directory the record is in with its templates
unrendered, which the runs of one job share. Every path in `job.json` is
absolute, so a reader finds the records from any working directory. A
`job.json` from before these fields reads without them.

The manifest's `warnings` array (the same list as `JobResult.warnings`, shown
in the GUI's run-log panel) holds what to look at before trusting the run's
data:

- **Every input that failed to load**, in declared order. A task that reads
  such an input is `Skipped` naming it, but a task skipped by two failed
  inputs names only the first, so the warnings are the one place every
  failure is listed. An input **no task reads** still loads on every run, and
  its failure skips nothing; its warning (`failed to load input 'x', which no
  task reads: ...`) is the only trace of it, so a typo in the path of an input
  you stopped using does not go unnoticed.
- **Consistency checks**: a declared part file missing, a referenced
  `runFile` not on disk, etc.

A warning never fails a run on its own: `succeeded` is true iff every task
succeeded, so a run whose only problem is an unread input that failed to load
is green, with a warning.

Reruns at the same `outputDir` overwrite the manifest in place. To keep
run-by-run history, template `outputDir` itself
(e.g. `/data/runs/{{ today }}`): each execution time writes to a fresh
subdir with its own `job.json` + per-task data, and the parent dir
becomes a multi-run layout. When the GUI's controls panel finds 2+
sibling runs at the parent of the rendered `jobRunOutput`, it surfaces a
run picker — switching the picker rehydrates the entire UI from the
chosen `job.json` without re-running anything.

## Supported file formats

### CSV

- Read: a hand-rolled state-machine parser. RFC 4180 doubled-quote escape, or
  a separate escape character (which escapes itself too), delimiters and line
  breaks inside quotes, `\n`, `\r\n` and lone `\r` line endings, and any
  character as the delimiter, quote or escape. A UTF-8 byte-order mark before
  the first row is dropped.
- **NULL is the null string written unquoted** (the PostgreSQL rule). A quoted
  field is always a value, so `""` is the empty string and `"NULL"` is the text
  `NULL` even when `nullValue` is `NULL`. The writer writes a NULL as the null
  string, unquoted, and quotes any value whose text is the null string, so an
  empty string is written `""` and reads back as itself. Only a `STRING` and a
  `BINARY` have an empty value: in a column of any other type an empty field is
  NULL, quoted or not.
- **A blank line is a row in a one-column file**, whose one field is empty
  (NULL by default): that is how the writer writes a NULL there. In a wider
  file, and before a header, blank lines are skipped.
- **Malformed text fails the read**, naming the file and the line: a quote that
  is never closed, a row with more or fewer fields than the file has columns
  (its header, the schema, or its first row), and bytes that are no text in the
  `charset`. Text after a closing quote joins the field (`"a"b` is `ab`), and a
  quote inside an unquoted field is a character of it.
- **Every field is read strictly as its column's type**, declared or inferred,
  and one that does not read as it fails the read, naming the file, line,
  column and text (for an inferred column, also where its type came from):

  | type | text it reads |
  |---|---|
  | `INT`, `BIGINT` | an optional sign and ASCII digits (`007` is 7, `+5` is 5) |
  | `FLOAT`, `DOUBLE` | a plain decimal (`1.5`, `.5`, `5.`, `-1e-5`) the type holds, or `NaN`, `Infinity` and `-Infinity`, which the writer writes; not Java's wider grammar (`1d`, `0x1p3`, ` 1`, non-ASCII digits), and not a number past the type's range or so small it would read as 0 (`1e400`, `1e-400`; `1e40` for a `FLOAT`). A `CAST` from text reads the same grammar |
  | `DECIMAL(p,s)` (declared, or `DECIMAL(38,0)` inferred) | a plain decimal that holds exactly at scale `s` in `p` digits, read at that scale (`1.5` as `1.50`, and `5.` in a `DECIMAL(38,0)` as 5); one that would round or has more digits fails |
  | `BOOLEAN` | `true` or `false`, in any case; no other word reads as `false` |
  | `DATE` | ISO `yyyy-MM-dd`, a year past 9999 or before 1 with a sign, within a `DATE`'s range (about 5.8 million years either side of 1970) |
  | `TIMESTAMP` | an ISO instant (`Z` or an offset), an ISO date-time with no zone (UTC), or a date alone (midnight UTC), kept to the microsecond toward the past, within a `TIMESTAMP`'s range (about 292,000 years either side of 1970) |
  | `BINARY` (declared) | Base64, which the writer writes |
  | `STRING` | the text |

- **Schema inference** samples the first 1000 rows of the glob's files, in
  order, and each column takes the narrowest type every sampled field reads
  as: a file holding only a header, which a writer leaves for an output
  partition that held no rows, contributes none, so the sample reads on into
  the files after it (it read the first file alone, so such a file typed every
  column of the glob `STRING`). Numbers widen from `INT` to `BIGINT` to `DECIMAL(38,0)` (an integer past `BIGINT`, kept
  exact) to `DOUBLE` (past 38 digits, or beside a fraction), and a number no
  `DOUBLE` holds (`1e400`) is text; a `DATE` widens to a
  `TIMESTAMP`, its dates read as midnight UTC; and any other mix, such as `1.5`
  with `true` or `1` with a date, is a `STRING`. A column with nothing but NULL
  and empty fields in its sample is a `STRING`. Only the sample is read, so a
  value past it that does not read as its column's type fails the read by name.
- **A glob's files are read by their headers' names.** With inferred types and
  a header, the first file's header names the columns and every later file is
  read by its own header, regardless of case, so a file that writes the
  columns in another order reads each into its column; a file naming other
  columns fails, naming both headers. (A later file's fields used to be read
  in the first file's order, so two columns of one type swapped silently.)
  Declared columns name the columns themselves: each file's header is then
  skipped.
- **What a round trip through inference keeps.** Write a table and read it back
  with its types inferred: `STRING`, `INT`, `BIGINT` (an `INT` when every value
  fits one), `DOUBLE`, `BOOLEAN`, `DATE` and `TIMESTAMP` come back as they were;
  a `FLOAT` comes back a `DOUBLE` with the same digits (`0.1` for `0.1f`); a
  `DECIMAL` comes back a `DOUBLE` (`1.50` as `1.5`, and digits past a double's
  lost), except integers past `BIGINT`, which come back `DECIMAL(38,0)`; a
  `BINARY` comes back as its Base64 text; text that looks like another type is
  read as that type (`007` as 7, `TRUE` as true); and a column that is all NULL
  in the sample comes back a `STRING`. A second round trip changes nothing more:
  inference reaches its fixed point in one pass (`fuzz/csv_fuzz_test`). A job
  that reads back a task's CSV output, for the tasks after it, uses the
  writer's options and schema rather than inference, so every value comes back
  as it was.
- Write: atomic temp-file + rename, header from `Schema`. A value is quoted when
  it holds the delimiter, the quote or a line break, when it is the null string,
  or when it starts with a byte-order mark; inside quotes the quote is doubled,
  or escaped with `escape`, which is then escaped too. Every type is written as
  text the reader reads back (a `BINARY` in Base64, a `DECIMAL` in plain
  digits). A character the `charset` cannot encode, or half of a surrogate pair,
  fails the write, naming the row and column, where it used to be written as
  `?`. A write with no partitions writes one header-only part file.

Options (passed via `InputFilePath.options` / `OutputFilePath.options`):

| Key | Default | Notes |
|---|---|---|
| `header` | `true` | First line contains column names; with an inferred schema each must be a name of its own, so a repeated name (regardless of case) or an empty one fails the load, naming the file and the column |
| `delimiter` | `,` | Any character but a line break; pass `\t` for TSV |
| `quote` | `"` | Not the delimiter |
| `escape` | `"` | RFC 4180 doubled-quote escape when equal to `quote`; otherwise escapes the quote and itself inside quotes. Not the delimiter |
| `nullValue` | `""` | The null string: an unquoted field equal to it is NULL. It cannot hold the delimiter or a line break, or start with the quote |
| `lineEnding` (output) | `\n` | `\n`, `\r\n` or `\r` |
| `inferSchema` | `true` | If false, `columns` is required (input) |
| `read_partition_size_bytes` (input, parquet only) | 256 MB | The bytes of row groups one partition reads, a whole number |
| `charset` | `UTF-8` | Bytes it cannot decode, or characters it cannot encode, fail the read or write |
| `parquet_row_group_size`, `parquet_write_parallelism` (output, parquet only) | 32 MB, by heap | The bytes of a row group, and how many part files are written at once, each a whole number |
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
  its file stores, comparisons on a column whose stored values are not
  the ones it reads as, such as an unsigned `INT32` or a `FLOAT16`, or that
  its file orders otherwise, an unsigned 8- or 16-bit integer, whose unsigned
  order puts a negative bound above every value, and a range on a string
  column against a literal with a character at or past U+D800, since a file
  orders strings by their UTF-8 bytes and the engine by UTF-16 code unit, and
  the two disagree where a character from U+E000 to U+FFFF meets one past
  U+FFFF, and a comparison of a `FLOAT` or `DOUBLE` column with zero or `NaN`,
  since a file orders `-0.0` below the `0.0` the engine calls equal to it and
  may leave `NaN` out of its statistics) drop through as residual filtering.
  `IS NULL` pushes on any column but a decimal.
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
  storing a different number. A column whose only value is the untyped `NULL`
  (`SELECT NULL AS n`) has no type, and parquet stores one for every column,
  so it fails the write before anything is written, naming the column and the
  `CAST` that gives it one (`CAST(NULL AS INT) AS n`); CSV, which stores no
  types, writes it.

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
  [ [INNER|LEFT|RIGHT|FULL] JOIN <table> [<alias>] ON <predicate>
  | CROSS JOIN <table> [<alias>] | , <table> [<alias>] ]* ]
[ WHERE <predicate> ]
[ GROUP BY <exprs> ] [ HAVING <predicate> ]
[ ORDER BY <expr> [ASC|DESC] [NULLS FIRST|NULLS LAST] [, ...] ]
[ LIMIT <n> ] [ OFFSET <m> [ROWS] ] [ FETCH {FIRST|NEXT} [<n>] {ROW|ROWS} ONLY ]
```

A query is one statement. A trailing `;` is fine, and anything written after it
is an error (`Only one statement is supported; remove the text after ';'`)
rather than text that never runs. Each clause comes once: a second `ORDER BY`,
`LIMIT`, `OFFSET` or `FETCH` for one query is an error, after the last arm of a
`UNION` too, as is a `WITH` that defines one name twice (a `WITH` inside a CTE
body may reuse an outer name, which it shadows there). An `ORDER BY`, `LIMIT`,
`OFFSET` or `FETCH` before the last arm of a `UNION` is an error too, as in
Postgres, MySQL and SQLite: in parentheses, `(SELECT ... LIMIT 1) UNION ALL
...`, it applies to that arm, and after the last arm to the whole union (it was
applied to its arm unasked). A `FROM` names each of its tables once: `t x JOIN
u x` and `t JOIN t` are errors asking for an alias each, where a qualified name
read whichever of the two had the column. Every join takes the
condition its kind takes: `JOIN`, `INNER`, `LEFT`, `RIGHT` and `FULL JOIN` one
`ON`, and `CROSS JOIN` and a table after a comma none. So `t JOIN u` with no `ON`
is an error, where MySQL and Spark read it as a cross join: write `CROSS JOIN`
to pair every row. So are an `ON` after `CROSS JOIN` or a comma, and a second
`ON` on one join, whose condition belongs in the first, joined with `AND`.

A name in `ORDER BY` is an output column first, as SQL has it: the output column
of that name when the SELECT list has one, an input column otherwise. So
`SELECT -b AS b FROM t ORDER BY b` sorts by `-b`, `SELECT s, COUNT(*) AS n FROM t
GROUP BY s ORDER BY n` by the count, `SELECT a + b AS total FROM t ORDER BY total`
by the sum and `SELECT s FROM t ORDER BY b` by the input column `b`. Two output
columns with the name make `ORDER BY` of it an error, unless they are one column
(`SELECT a, t.a`). Inside an expression a name is an input column, or the output
column of a name no input column has (`ORDER BY total * 2`); a name that is both,
an input column and the alias of something else, as in `SELECT -b AS b FROM t
ORDER BY b + 0`, is refused as ambiguous, since Postgres reads the input column
there and Spark the output: write `t.b` for the one, `-b` for the other. A
qualified name, and a name inside an aggregate's arguments or a window, is always
the input's. Past a `DISTINCT` or a `UNION`, `ORDER BY` sees only the output
columns. A key that names an output column sorts by the values the query
returns, so `SELECT RAND() AS r FROM t ORDER BY r` comes back sorted.

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
one row or none. So does `GROUP BY ()`, which groups by nothing, and an
aggregate in `ORDER BY` or inside a window function's arguments or keys:
`SELECT 1 FROM t ORDER BY COUNT(*)` and `SELECT SUM(COUNT(*)) OVER () FROM t`
each return one row, as in Postgres. A column outside an aggregate is an error
there, not the row filter MySQL makes of it. In any aggregate query the error
says the rule, `Column 'a' must appear in GROUP BY or inside an aggregate`. A
`GROUP BY` key is the expression it names however it is written and wherever
it is: `SELECT t.a, COUNT(*) FROM t GROUP BY a` reads the key, and so do
`SELECT UPPER(s) || '!' ... GROUP BY UPPER(s)`, `CASE WHEN a % 2 = 0 ...` and
`HAVING a % 2 = 1` over `GROUP BY a % 2`, and `GROUP BY (a % 2), s`; a
different expression (`1 + a` for a key `a + 1`) is not the key. A name two
inputs have is as ambiguous in an aggregate query as in `WHERE`. A window
function in a grouped query reads the groups' aggregates: `SUM(COUNT(*)) OVER
()` adds up the groups' counts. (A key used to be matched by its text and only
as a whole SELECT item, so each of these was refused, and `GROUP BY ()` was
dropped, returning a row for each input row.)

`GROUP BY` and `ORDER BY` also accept a **positional ordinal** — a bare integer
naming a SELECT-list position, as in BigQuery / Postgres / MySQL. `ORDER BY 2
DESC` sorts by the second output column, as `ORDER BY` of its name does. Only a
bare integer counts: `ORDER BY +1` is a constant, not a position. A `GROUP BY` ordinal
may not point at an aggregate; an `ORDER BY` ordinal may. An out-of-range
position is an error.

A key that reads no column orders, groups and partitions nothing, so `ORDER BY`,
`GROUP BY` and a window's `PARTITION BY` and `ORDER BY` refuse one: a literal
(`ORDER BY 'b'`, which is what a query that meant the column `"b"` says, or
`ORDER BY +1`, `NULL`, `TRUE`), a `CAST` of one, arithmetic on constants
(`ORDER BY 1 + 0`), a clock keyword and `RAND(seed)`. `RAND()`, which differs by
row, sorts, and so does an output column, whatever it holds: a position and a
name are keys that read a column.

**SQL outside this grammar is rejected, not ignored.** The parser accepts far
more SQL than the engine implements, and a clause the binder does not read used
to vanish without an error — `JOIN ... USING` ran as a cartesian product,
`OFFSET` returned the wrong page. Every such clause now fails at bind time with
an `IllegalArgumentException` naming it, for example `QUALIFY is not supported;
compute the window function in a WITH clause and filter on it in the outer
WHERE`. Rejected, among others: `JOIN ... USING`, `NATURAL`, `SEMI` and `APPLY`
joins, a bare `OUTER JOIN` and Oracle's `(+)`; `TOP n PERCENT`, `WITH TIES`, and
`ORDER BY` / `LIMIT` / `OFFSET` / `FETCH` after a parenthesized query; `QUALIFY`,
`DISTINCT ON`, `GROUPING SETS`, `WITH ROLLUP`, and `ROLLUP (...)` or `CUBE (...)` in
`GROUP BY`; `FILTER (WHERE ...)`,
`WITHIN GROUP`, `IGNORE NULLS` and named `WINDOW`s; `DISTINCT` inside
`STDDEV`, `VARIANCE`, `COUNT_IF`, `COVAR` or `CORR`; `RLIKE` / `REGEXP`, `SIMILAR TO` and
`x NOTNULL`; `TRY_CAST`, array types and
subscripts; `* EXCEPT` / `* REPLACE`; `PIVOT`, `TABLESAMPLE`, `CONNECT BY` and
schema-qualified table names; `SELECT ... INTO`, `FOR UPDATE`, and vendor
hints. A construct the engine does not implement at all is refused by its SQL
name too, with what to write instead where there is something:
`EXISTS is not supported; name the subquery in a WITH clause and JOIN it`, and
the same for a subquery, `IS DISTINCT FROM`, `IS TRUE`, `INTERVAL`, a query
parameter, an array, `EXCEPT` and `INTERSECT`, a `VALUES` list and the rest.
The complete list, with the reason for each, is
[`UnsupportedClauses.scala`](src/main/scala/com/transformer/sql/plan/UnsupportedClauses.scala).
Text the parser accepts and then leaves out of what the binder sees is refused
too: a statement after `;`, a clause written twice, a join's missing or extra
`ON`, a CTE name defined twice (all above), a `NOT` the parser hangs on the
left side of an operator, and a string literal where an alias goes (both
below).

**Literals and names mean what SQL writes.** A string literal is its value, so
a doubled quote inside one is one quote: `'it''s'` is `it's`, SQL's only way to
write a quote (a `\` escapes nothing). A name in double quotes or backquotes is
the name between them, so `"order date"`, `"select"` or `` `x y` `` names what
a bare word cannot, and `AS "Total"` names its column `Total`. Quotes do not
make a name case-sensitive: every name, quoted or bare, matches regardless of
case, as in Spark and DuckDB (standard SQL keeps a quoted name's case), so
`"a"`, `"A"` and `a` are one column. An alias is a name, so a string literal
where one goes is an error, `SELECT a AS 'x y'` and `FROM t AS 'x'` alike, as is
`SELECT 'a' 'b'`, which the parser reads as a literal and its alias; join
strings with `||`. A whole number is an `INT` if one holds it, else a `BIGINT`,
else the `DECIMAL` of its digits, as Postgres reads it (`99999999999999999999`
is a `DECIMAL(20,0)`), and past 38 digits an error; `-9223372036854775808` is
the smallest `BIGINT`. A number written with a point or an exponent is a
`DOUBLE`, and beside a `DECIMAL` the decimal it is written as (below); one a
`DOUBLE` cannot hold is an error rather than `Infinity` or 0 (`1e400`,
`1e-400`). A `LIMIT`, `OFFSET`, `FETCH`, `TOP` or ordinal past `BIGINT` is an
error naming it.

**Aggregates:** `COUNT(*)`, `COUNT(col)`, `COUNT(DISTINCT col)`, `COUNT_IF(pred)`,
`SUM`, `AVG`, `MIN`, `MAX` (each of these four also as `SUM(DISTINCT col)` and so
on, which aggregates each distinct non-NULL value once),
`STDDEV`/`STDDEV_SAMP`/`STDDEV_POP`, `VARIANCE`/`VAR_SAMP`/`VAR_POP`,
`COVAR_SAMP`/`COVAR_POP`, `CORR`. Univariate variance/stddev (and `COUNT_IF`)
are also available as window aggregates with `OVER (...)`. Variance and stddev
use Welford + Chan's parallel merge for numerical stability; `*_SAMP` returns
NULL when fewer than two non-NULL inputs are present. `SUM`, `AVG` and the
statistics take a number, and `MIN`, `MAX` and `COUNT` any type (see the
argument types below). `COUNT()` with no argument is an error naming
`COUNT(*)`, which counts the rows (SQLite and DuckDB read it as that, Postgres
refuses it). `SUM` is a `BIGINT` over integers, a `DOUBLE` over floats, and over a `DECIMAL(p,s)` an exact
`DECIMAL(38,s)`. `AVG` is a `DOUBLE`, but over a `DECIMAL(p,s)` an exact
`DECIMAL(p+4,s+4)` (past 38 digits it gives way on scale, as arithmetic does,
to at least four fractional digits, so the mean of values past about 10^34
can overflow it), rounded half up. `AVG` of integers divides their exact sum
once, so it is the `DOUBLE` nearest the mean whatever the partitions: summed
as doubles, BIGINTs past 2^53 averaged differently over one partition and
two. A decimal sum too long for 38 digits is an error, and so is
an integer sum past a `BIGINT`, decided from the true sum: a running total
may pass the `BIGINT` range and come back, in whatever order the rows,
partitions or a spill bring it.

**Window functions:** `<fn>() OVER ([PARTITION BY <exprs>] [ORDER BY <keys>]
[<frame>])` — `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG`/`LEAD` (with the SQL
`offset` and `default` arguments; the default meets the value it stands in for
as a `CASE`'s values do, below), and the aggregate forms above. The `ORDER BY`
keys take `ASC`/`DESC` and `NULLS FIRST`/`NULLS LAST` exactly as the outer
`ORDER BY` does. A frame is `ROWS` or `RANGE`, `BETWEEN <start> AND <end>` or a
start alone (whose end is `CURRENT ROW`), each bound one of `UNBOUNDED
PRECEDING`, `n PRECEDING`, `CURRENT ROW`, `n FOLLOWING` and `UNBOUNDED
FOLLOWING`. A `ROWS` offset counts rows and may be any `BIGINT`. A `RANGE`
frame takes rows by their `ORDER BY` value: its `CURRENT ROW` is the current
row and every row the `ORDER BY` ties with it, its peers, and `n PRECEDING` /
`n FOLLOWING` the rows whose value lies within `n` of the current row's on
that side (`n` a number,
`1.5` too, and over a `FLOAT` or `DOUBLE` key one a `DOUBLE` holds, so not
`1e309`; the window needs exactly one `ORDER BY` key, a number). With an
`ORDER BY` and no frame, the frame is `RANGE BETWEEN UNBOUNDED PRECEDING AND
CURRENT ROW`, as in standard SQL: a running total gives tied rows one total,
`SUM(x) OVER (ORDER BY day)` the total through the end of each day. Without an
`ORDER BY` the frame is the whole partition. A NULL `ORDER BY` value's offset
frame is its NULL peers, and a NaN's its NaN peers. A frame that cannot hold a
row by the kinds of its bounds (`1 FOLLOWING AND 1 PRECEDING`, a start of
`UNBOUNDED FOLLOWING`, an end of `UNBOUNDED PRECEDING`) is an error, as in
Postgres, and so is a frame on `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG` or
`LEAD`, which read no frame; two offsets on one side may still select nothing
(`2 PRECEDING AND 1 PRECEDING` at a partition's start). `RANGE` frames used to
run as `ROWS`, so tied rows got different running totals, in whatever order
they arrived. `RANK` and `DENSE_RANK` tie exactly
the rows the `ORDER BY` ties, on the same total order described below — so
`DENSE_RANK()` over a partition whose order key is equal on every row is `1`
everywhere. No `FIRST_VALUE`/`LAST_VALUE`/`NTH_VALUE`/`PERCENT_RANK`/
`CUME_DIST`/`NTILE` yet.

**Scalar functions:** strings — `LENGTH`/`LEN`, `UPPER`, `LOWER`, `TRIM`
(single-argument trim of spaces only — `TRIM([LEADING|TRAILING|BOTH] [str]
FROM x)` and `TRIM(x, str)` are rejected with a clear error, and there is no
`LTRIM`/`RTRIM`), `SUBSTRING`, `CONCAT`; flow — `COALESCE`, `IF`, `NULLIF`,
`GREATEST`, `LEAST`;
numeric — `ABS`, `ROUND`, `FLOOR`, `CEIL`/`CEILING`, `TRUNC`/`TRUNCATE`,
`MOD`, `POW`/`POWER`, `SIGN`; math — `SQRT`, `CBRT`, `EXP`, `LN`,
`LOG(base, x)`, `LOG10`, `LOG2`; trig — `SIN`, `COS`, `TAN`, `ASIN`, `ACOS`,
`ATAN`, `ATAN2(y, x)`, `SINH`, `COSH`, `TANH`, `DEGREES`, `RADIANS`;
constants — `PI()`, `E()`; non-deterministic — `RAND()` / `RAND(seed)`;
temporal — `CURRENT_DATE`, `CURRENT_TIMESTAMP`. The string functions take a
`STRING`, the numeric and math functions a number, and `ROUND`'s and `TRUNC`'s
scale, `SUBSTRING`'s position and length and `RAND`'s seed an `INT` or a
`BIGINT` (see the argument types below). NULL propagates through
every function but the flow ones, a NULL scale, position, length or seed
included; `GREATEST`/`LEAST` skip NULLs and return NULL only when every
argument is NULL, and of two equal zeros `GREATEST` gives `0.0` and `LEAST`
`-0.0` in either order, as `MAX` and `MIN` do. `SIGN` is -1, 0 or 1, and of
`NaN`, which is none of them, an error. `MOD(a, b)` is `a % b`, typed the same way.
A math function of a number outside its domain is NULL, as a division by zero
is, since no number answers it: a square root of less than zero, a logarithm
of zero or less or to a base of zero or less or of 1, an `ASIN` or `ACOS` past
-1 or 1, a `SIN`, `COS` or `TAN` of an infinity, zero to a negative power, and
a negative number to a finite power that is not whole (they were `NaN`, or an
infinity). `NaN` stays `NaN`, and otherwise `POWER` is C's `pow`, as in
Postgres, DuckDB and SQLite: `POWER(1, y)` is 1 for every `y`, and so is
`POWER(-1, y)` for an infinite `y`. `LOG(x)` with one argument is an error,
as engines disagree on its base (e in MySQL and Spark, 10 in Postgres and
SQLite): write `LN(x)` or `LOG10(x)`. `ROUND`,
`TRUNC`, `FLOOR` and `CEIL` are exact: `ROUND` rounds half away from zero
(`ROUND(2.5)` is 3 and `ROUND(-2.5)` is -3), `TRUNC` toward zero, `FLOOR`
down and `CEIL` up, each at `scale` places (0 by default), a negative scale
rounding to tens, hundreds, ... An `INT` or `BIGINT` keeps its type and every
digit (`ROUND(15, -1)` is the `INT` 20, and an answer past the type is the
integer overflow error); a `DECIMAL(p,s)` stays a `DECIMAL`, with the scale the
query writes (`ROUND(price, 2)` over a `DECIMAL(10,3)` is a `DECIMAL(10,2)`,
one more integer digit kept for a carry, a negative one such as `-1` keeping
none past the point, and a scale that is not written as a
number keeps `s`); a `FLOAT` or `DOUBLE` is a `DOUBLE`, rounded as the decimal
it prints as, so `ROUND(1.005, 2)` is 1.01 whatever the binary fraction
nearest 1.005 would give, and `NaN` and the infinities are their own answer.
Each of these used to compute in `DOUBLE`: `ROUND` of a `DECIMAL` 1.005 was 1.0,
and `FLOOR` of a `BIGINT` past 2^53 lost its last digit. `LENGTH` and
`SUBSTRING` count characters, Unicode code points, as Postgres and Spark do, so
an emoji, which Java holds as two chars, is one: `LENGTH` counted chars, and
`SUBSTRING` could cut a character in half, which a spill or a parquet or CSV
file then wrote as `?`. A `SUBSTRING` position before the first character counts from
the first, and one past the last, or a length of 0, gives the empty string; a
negative length is an error, at bind time when it is written as a constant.
`TRIM` removes the spaces (U+0020) a string starts and ends with, as SQL's does,
and keeps a tab or a line break (Java's `trim`, which it used, took every
character up to U+0020). `UPPER` and `LOWER` map
case by Unicode's own rules, the same on every machine: the JVM's locale, under
which a Turkish one would make `UPPER('i')` a dotted capital, plays no part, and
neither does it in matching a name without regard to case.

`CURRENT_DATE` and `CURRENT_TIMESTAMP` take every spelling other engines accept
(`current_date`, `CURRENT_DATE()`, DB2's `CURRENT DATE`). Both read the wall
clock once per query, in UTC, so every row of a result sees the same instant and
`CURRENT_DATE` is that instant's date. They are not the job's execution time: a
re-run reads the clock afresh, so SQL that should follow the execution time uses
the [templating variables](#temporal-templating) instead, as in
`DATE '{{ iso_date }}'`. There is no `CURRENT_TIME`, because there is no TIME
type.

**Operators:** `+ - * / %`, `=  <>  <  <=  >  >=`, `AND OR NOT`, `||` (joins
two strings), `LIKE` and its case-insensitive form `ILIKE` (on strings),
`IS NULL` / `IS NOT NULL`,
`IN (...)`, `BETWEEN`, `CASE WHEN`, `CAST(expr AS type)`. `NOT` negates the
whole comparison after it, so `NOT a = 1` is `NOT (a = 1)`, in a call's
argument too (`COUNT_IF(NOT a = 1)`). Where the parser would hang it on the
comparison's left side alone instead (the second `NOT` of `NOT NOT a = 1`), the
query is an error asking for `NOT (...)` around what the `NOT` negates. An `IN`
list holds at least one value, as in standard SQL: `x IN ()` is an error. Two
signs in a row are an error too, `- -3` or `a * - + 2`, since the parser cannot
read them: write `-(-3)`; a sign after an operator, `5 - -3`, is one sign.
`TRUE` and `FALSE` are the boolean literals wherever they are written; a column
named `true` is written in quotes, `"true"`. A conditional evaluates only what its answer needs,
on each row: a `CASE` reads the `THEN` of the branch it takes and no other,
`IF` one of its two values, `COALESCE` its arguments up to the first that is
not NULL, `IN` its items up to the first that matches, and `AND` and `OR` their
right side only where the left does not decide. So a `CASE` guards an
expression that would fail on other rows: `CASE WHEN s <> 'n/a' THEN CAST(s AS
INT) END` never casts `'n/a'`. (Write the guard as a `CASE`: the optimizer may
move one condition of a `WHERE` ahead of another.) A simple `CASE`'s operand
(`CASE x WHEN 0 THEN ...`) and a `BETWEEN`'s are read once per row, as in
Postgres, so `CASE CAST(FLOOR(RAND() * 3) AS INT) WHEN 0 THEN 'a' WHEN 1 THEN
'b' WHEN 2 THEN 'c' END` puts every row in a bucket and `RAND() BETWEEN 0.25
AND 0.75` holds on half the rows; read again for each comparison, as the
standard's expansion into `x = 0 OR ...` reads it, a third of the rows fell
through to NULL. In a `LIKE` / `ILIKE`
pattern `\` makes the next `%` or `_` literal; `ESCAPE 'c'` names another
character, and `ESCAPE ''` turns escaping off. A pattern that ends in its escape
character escapes nothing and is an error, at bind time when the pattern is a
constant: write the character twice to match one. A pattern reads a character
outside the Basic Multilingual Plane, an emoji, as one character, and `_`
matches one; split in two, a pattern holding one matched nothing, not even
itself. A unary `+` is arithmetic, on a number, as `-` is. Grouping parentheses work anywhere an expression does —
`SELECT (a + b) * c`, `WHERE (x = y)`, `ON (a = b)`, `HAVING (COUNT(*) > 1)` —
and are transparent to column naming (`SELECT (a)` yields a column named `a`)
and to ordinals (`ORDER BY (1)` is position 1). Row-value constructors
(`(a, b) = (c, d)`) are not supported.

**Types in CAST:** `INT`, `INTEGER`, `BIGINT`, `LONG`, `FLOAT`, `REAL`,
`DOUBLE`, `STRING`/`VARCHAR`/`TEXT`/`CHAR`, `BOOLEAN`/`BOOL`, `DATE`,
`TIMESTAMP`. A `DECIMAL` or `BINARY` column comes from a declared schema or a
parquet file; neither type is a CAST target. A number
widens to a `DECIMAL` where the two meet, and a `BINARY` casts to nothing. A
`BOOLEAN` casts to any number as 1 for true and 0 for false.

A number casts to an `INT` or `BIGINT` as its integer part, a fraction
truncated toward zero (`CAST(2.7 AS INT)` is 2 and `CAST(-2.7 AS INT)` is -2,
as in Spark), and one outside the type, or NaN or an infinity, which have no
integer, is an error rather than the type's limit or 0. A `DOUBLE` casts to
the nearest `FLOAT`, and one a `FLOAT` cannot hold, past its range or so small
it would be 0, is an error, as the same number as text is (`Float overflow:
1.0E300 does not fit FLOAT`); NaN and the infinities cast to themselves. A
`DECIMAL` casts to `STRING` as its digits, the text CSV writes for it
(`0.000000100`, where Java's `toString` gave `1.00E-7`).

Text casts to a number by the grammar CSV reads one by (see [CSV](#csv)): an
optional sign and ASCII digits for an `INT` or `BIGINT` (`'007'`, `'+5'`), and a
plain decimal (`'-2.5'`, `'.5'`, `'1e3'`) or `NaN`, `Infinity` and `-Infinity`
for a `FLOAT` or `DOUBLE`, which the value must fit: `'1e400'` is no `DOUBLE`
and `'1e-400'`, which would be 0, none either. Java's wider grammar is not used,
so `' 5'`, `'5d'`, `'0x1p3'` and digits of other scripts are no numbers, and a
string literal beside a number is read by the same rule. Text casts to
`BOOLEAN` from `true` or `false`, in any case.
Other text is an error that names it, such as `Cannot cast 'x10' to int`, and no
text quietly becomes `false`.

Text casts to `TIMESTAMP` from an ISO date, optionally followed by a time (after
`T` or a space) and then `Z` or an offset such as `+02:00`. A time with no zone
is UTC and a date alone is midnight, so `'2026-01-01 05:30:21'`,
`'2026-01-01T05:30:21Z'` and `'2026-01-01 07:30:21+02:00'` are one instant. Text
casts to `DATE` from an ISO date (`2026-01-01`) and nothing else: which date a
time of day falls on depends on its zone, so `CAST(CAST(x AS TIMESTAMP) AS DATE)`
spells that out. A `TIMESTAMP` casts to `DATE` as its date in UTC, and a `DATE`
to `TIMESTAMP` as its midnight. A `DATE` holds about 5.8 million years either
side of 1970 (an `INT` of days) and a `TIMESTAMP` about 292,000 (a `BIGINT` of
microseconds); a value past either, from a CAST, a file or a `DATE` whose
midnight no `TIMESTAMP` holds, is an error naming it. `DATE '2026-01-01'` and
`TIMESTAMP '2026-01-01 05:30:21'` are literals, read when the query is bound like
any string literal under a CAST, so a malformed one fails before anything runs,
and so are the JDBC escapes `{d '2026-01-01'}` and `{ts '2026-01-01 05:30:21'}`,
which the parser read leniently (`{d '2026-02-30'}` was 2026-03-02). A
number or a boolean does not cast to or from either type: an epoch in days,
seconds or milliseconds would be a guess.

**Values of different types meet only as numbers, or through a string
literal.** Wherever values meet — a comparison, `BETWEEN`, an `IN` list, a
`CASE` switch, arithmetic — numbers widen to the widest among them. A `DECIMAL`
is the widest: an integer meets one exactly, a number written in the query as
the decimal it is written as, every digit of it (`price = 0.1` finds a `0.10`,
and `price = 0.120000000000000000001` no `0.12`), and a `FLOAT` or `DOUBLE`
column as the decimal each value prints as; `NaN` and the infinities have no
decimal value, and one meeting a `DECIMAL` is an error. (Arithmetic on
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
`NULLIF(a, b)` compares its two as `=` does and returns `a` as it is, so
`NULLIF(i, '0')` reads the `'0'` as an `INT`.

**Every condition is a BOOLEAN, and every function takes the types it computes
on.** A condition (`WHERE`, `HAVING`, `ON`, `CASE WHEN`, `IF`'s first
argument, `COUNT_IF`, and each side of `AND`, `OR` and `NOT`) is a `BOOLEAN`,
or NULL, the unknown truth value. Anything else is an error before the query
runs, naming the comparison or the `CAST(x AS BOOLEAN)` to write: `WHERE i`
over an `INT` is `WHERE i <> 0`, and `WHERE 'true'` is `WHERE TRUE`. The math
functions, and the aggregates that add their values up (`SUM`, `AVG`,
`STDDEV`, `VARIANCE`, `COVAR_SAMP` / `COVAR_POP` and `CORR`, with `DISTINCT` and
as windows too), take a number: text takes a CAST to the number type meant, a
boolean `CAST(b AS INT)` for 1 and 0, and a date, a timestamp or a byte array is
no number. `ROUND`'s and `TRUNC`'s scale, `SUBSTRING`'s position and length and
`RAND`'s seed count something, so each is an `INT` or a `BIGINT`, and
`ROUND(x, 1.5)` is an error rather than one place or two. Only a `STRING` is
text: `LIKE` and `ILIKE`, `||`, `CONCAT` and the string functions refuse any
other type, and `CAST(x AS STRING)` turns one into text where the query says
so (`'id-' || CAST(id AS STRING)`), since how a `DOUBLE` prints (`1.0E10`) or
where a `TIMESTAMP` puts its zone is a choice. Each of these used to read its
argument as the type it wanted, or fail part-way through the rows: `WHERE i`
was TRUE where `i` was not 0, `WHERE d` kept no rows, `LENGTH(12)` was 2, `ABS`
of a date was NULL, and `SUM(s)` over text failed on its first row.

**Values that become one result take a type that holds each of them.** The
`THEN` and `ELSE` values of a `CASE`, the arguments of `COALESCE`, `GREATEST`
and `LEAST`, `IF`'s two values, `LAG`'s or `LEAD`'s value and default, and each
column of a `UNION`'s arms meet by the rule above, whatever order they come in:
`CASE WHEN p THEN 1 ELSE 2.5 END` is the `DOUBLE` 2.5, `COALESCE(i, '0')` reads
the `'0'` as an `INT`, and `COALESCE(s, 1)` over a string column, a `DATE`
beside a `TIMESTAMP`, a `BOOLEAN` beside a number and a `UNION` of an `INT` arm
and a `STRING` arm are errors naming the CAST. A `DECIMAL` is held exactly: two
meet as the `DECIMAL` with the longer integer part and the larger scale, so
`COALESCE(price, price * rate)` over a `DECIMAL(9,2)` and a `DECIMAL(18,4)` is
a `DECIMAL(28,6)`; an `INT` or `BIGINT` counts as the `DECIMAL(10,0)` or
`DECIMAL(19,0)` its every value fits; and a number written in the query as the
decimal it is written as, so `COALESCE(price, 0)` keeps `price`'s type and
`COALESCE(price, 0.125)` is a `DECIMAL(10,3)`. A `FLOAT` or
`DOUBLE` that is not written in the query beside a `DECIMAL` is an error, as in
arithmetic: CAST the `DECIMAL` to `DOUBLE` for a `DOUBLE` result. Past 38
digits the type gives way on its scale as arithmetic's does, so a value is
rounded half up to it, and one whose integer part is too long is an error. Every
value then fits its column's type, and parquet writes it as it is.

**`DECIMAL` arithmetic is exact, and declares a type that holds its
result.** `+ - * / %` (and `MOD`) on a `DECIMAL` type their result as Spark
and SQL Server do. For a `DECIMAL(p1,s1)` and a `DECIMAL(p2,s2)`, `+` and `-`
keep the larger scale and add an integer digit for the carry, `*` is a
`DECIMAL(p1+p2+1, s1+s2)`, `/` takes the scale `max(6, s1+p2+1)`, and `%` the
larger scale and the narrower integer part; unary minus keeps its operand's
type. Beside a `DECIMAL`, an `INT` counts as the `DECIMAL(10,0)` its every
value fits, a `BIGINT` as a `DECIMAL(19,0)`, and a number written in the query
as the decimal of its digits as written, so `price * 1.1` is exact (`1.1` is a
`DECIMAL(2,1)`, and `1.10` a `DECIMAL(3,2)`, as standard SQL types them). So is
arithmetic on such numbers alone: `price * (1 + 0.05)`
computes `1 + 0.05` in `DECIMAL`, though on its own it is a `DOUBLE`, while
arithmetic on integers alone keeps an integer's answer (`10 / 4` is 2 there as
anywhere). Past 38 digits a type gives way on its scale, down to six
digits, and keeps its integer part. A result is rounded half up to its type's
scale, and one whose integer part is too long for the type is an error naming
it (`Decimal overflow: ... does not fit decimal(38,0)`), never a rounded or a
NULL answer; division or `%` by zero is NULL, as for integers. A `FLOAT` or
`DOUBLE` column has no scale for a decimal result to keep, so arithmetic
between one and a `DECIMAL` is an error before the query runs: CAST the
`DECIMAL` to `DOUBLE` to compute in `DOUBLE`. `SUM` and `AVG` over a `DECIMAL`
are exact too (see Aggregates), so a decimal result never depends on row
order, partitions or spill, and parquet writes it as it is.

**`INT` and `BIGINT` arithmetic is exact, or an error.** `+ - * /`, unary
minus and `ABS` on integers give the exact answer when their type holds it,
and an error naming the operation when it does not (`Integer overflow:
2147483647 + 1 does not fit INT`), where they used to wrap round silently, as
Java's `int` does; `SUM` over integers is exact the same way (see Aggregates).
An `INT` result that needs more room takes a `CAST(x AS BIGINT)` on an
operand. Division by zero and `%` by zero are NULL, and a remainder never
overflows. Division and `%` by zero are NULL for a `FLOAT` and a `DOUBLE`
too, either zero (`7.0 / 0` and `0.0 / 0.0`), as Spark has it; they were
IEEE's infinity and NaN. Past their range floats still overflow to an
infinity (`1e308 * 10`).

**A `BINARY` value is not text.** It casts to nothing else, so no CAST makes
one text for `LIKE`, `||` and the string functions (`LENGTH`, `UPPER`, `LOWER`,
`TRIM`, `SUBSTRING`, `CONCAT`), or a truth value for a condition, and a `CASE`
result that would read one as a string is an error before the query runs. Each
of these used to read the array's identity, text like `[B@1a96d94c` that changed
on every run.

**CTEs (`WITH`):** supported, non-recursive. `WITH a AS (...), b AS (SELECT ...
FROM a) SELECT ... FROM b` — later CTEs may reference earlier ones, a CTE can
carry an explicit column-alias list (`WITH c(x, y) AS (...)`), and a CTE name
shadows a catalog view of the same name. A CTE referenced **two or more times**
is materialized once into an in-memory result that every reference replays — so
`FROM c x JOIN c y` executes the body once, not twice (and a non-deterministic
body like `RAND()` yields the same rows to every reference). A CTE referenced at
most once stays inlined (zero overhead, streaming preserved); a declared-but-
unused CTE is never executed, and neither is one that only unused CTEs read
(each of their references counted, so it ran, and could fail the query).
Materialization buffers the body in heap. Only the outermost `WITH` is
materialized: a CTE defined inside another CTE's body, or inside a
parenthesized `UNION` arm, is inlined at each reference, so a `RAND()` in it
gives each reference its own values. `WITH RECURSIVE` is rejected.

**Subqueries:** not in v1 (scalar, IN/EXISTS, derived tables in FROM). Use a
`WITH` CTE, or chain results across a multi-task pipeline with `viewName`.

**NULL handling:** three-valued logic in boolean contexts; NULL propagates
through arithmetic and string ops; `IS NULL` / `IS NOT NULL` for explicit
checks.

**`NaN` and `-0.0`:** a `FLOAT` or `DOUBLE` has one equality and one order, as
in Postgres and Spark: `NaN` equals `NaN` and sorts above every other number,
the infinities included, and `-0.0` equals `0.0` and ties it. So `NaN = NaN`,
`NaN IN (NaN)` and `-0.0 = 0.0` are TRUE and `-0.0 < 0.0` is FALSE, and every
operator that matches, groups or orders values follows the same rule: a join
on `a = b` pairs the rows `WHERE a = b` keeps, whichever join runs it, and
`GROUP BY`, `DISTINCT`, `UNION`, `COUNT(DISTINCT …)` and `PARTITION BY` put all
the `NaN`s in one group and both zeros in another (read back as `0.0`), and
`RANK` / `DENSE_RANK` give them one rank. `MAX` over a column containing `NaN`
is `NaN` and `MIN` over one the smallest other value; of two equal zeros `MIN`
gives `-0.0` and `MAX` `0.0` whatever order they come in. None of this depends
on row order, partitions or spill. It used to be three rules: `=` called `NaN`
unequal to itself and the zeros equal, the keys went by bit pattern (`NaN`
matched `NaN` in a hash join, the zeros did not match) and the sort ordered
`-0.0` below `0.0`, so a hash join and the same predicate as a filter
answered differently.

**Integers, decimals and byte arrays compare exactly.** Two integers compare as
64-bit integers, so `9007199254740993 = 9007199254740992` is FALSE, though a
`DOUBLE` cannot tell the two apart. A `DECIMAL` compares by value at full
precision, and its scale is only how it is written: `1.0 = 1.00` is TRUE, and
`GROUP BY`, `DISTINCT`, `UNION`, `COUNT(DISTINCT …)`, `PARTITION BY` and join
keys all treat the two as one value. A column built in memory need not hold
every value at its declared scale, so a grouping
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
b.*` keeps its layout. A condition of the `WHERE` that compares the two sides
of an inner join is part of the join, so `FROM t, u WHERE t.id = u.id` (and
`t CROSS JOIN u WHERE ...`) is the hash join `t JOIN u ON t.id = u.id` is; it
stayed above the join, which then paired every row with every row. Non-equi
joins (no equality conjunct) work for tiny inputs but are refused when both
sides' row counts are known and the smaller exceeds 5000 rows — at that scale
you should add an equality key. A row count is known for a parquet file or an
in-memory table, through projections, sorts, windows, `LIMIT`, `OFFSET` and
`UNION ALL`, and is not behind a filter, a grouping or a join condition, so
two one-row filters over large tables join (they were refused on a guess of
how many rows a filter keeps). So is
one over the output of another such join, directly or through joins on an
equality, when the two would pair more than 25 million rows: a chain
multiplies where each side alone looks small (three 300-row tables pair 27
million). One small side against a large one, such as 100 buckets against a
million-row table on `amount BETWEEN lo AND hi`, still runs. Such a join pairs
its rows a wave at a time, so it holds its smaller side and a wave of pairs
(a few hundred thousand rows), not every pair it makes: 25 million pairs count
through a 256 MB heap.

**Join order:** inner and comma joins run in the order `FROM` writes them,
but where the next table has no equality with the tables joined so far, the
first later table that has one is joined first: in `FROM a, b, c WHERE a.x =
c.x AND c.y = b.y` the written first join, `a` with `b`, has no condition and
would pair every row, so `c` is joined second and both joins hash. There is no
cost model: a query whose written order already gives each join it can an
equality keeps its plan, and a table no later table connects to stays where
it is written (`FROM a, b, c WHERE b.k = c.k` pairs `a` with `b`). The columns
keep their written order, an outer join is joined where it is written (the
inner joins on either side of it are ordered on their own), and a run of joins
whose conditions read `RAND()` keeps its order, since another order would draw
it for other rows. The answer never depends on the order; which rows a
condition is evaluated on does, so a condition that fails on some row (an
integer overflow, or a CAST of text that is no number) may fail in one order
and not reach that row in another.
`EXPLAIN` shows the order chosen. In SQLite's test corpus, whose
`select5.test` joins 4 to 64 tables written in three orders each, every one of
its 732 queries now runs, in under six seconds; written order refused 521 of
them.

**EXPLAIN:** `EXPLAIN <query>` returns the query's plan, without running any
of it, as one `STRING` column, `plan`, a row per line: the plan the optimizer
makes, as a tree of operators and as SQL that binds back to it. Every column
is qualified by its table, every cast the engine inserted is written in, and a
filter the optimizer pushed under a join reads as a CTE of its own:

```
EXPLAIN SELECT t.a, u.x FROM t, u WHERE t.a = u.a AND u.x = 'two'

== Optimized Logical Plan ==
Project: t.a, u.x
  Join INNER: t.a = u.a
    Scan t: a, s
    Filter: u.x = 'two'
      Scan u: a, x
== Optimized SQL ==
WITH q1 AS (
  SELECT u.a AS a, u.x AS x
  FROM u
  WHERE u.x = 'two'
)
SELECT t.a AS a, q1.x AS x
FROM t
JOIN q1 ON t.a = q1.a
```

A CTE the query reads twice or more, which runs once before the query, shows
as a scan in the tree and its own plan after it. `EXPLAIN VERBOSE <query>` also
shows the plan as bound, before the optimizer, as a tree and as SQL, and the
physical operators that run it: which side of a join is hashed, a join with no
equality that pairs rows in a nested loop, the exchanges that shard a
pipeline, and the partitions each operator runs in, which depend on the
tables' sizes and the sharding settings. `ANALYZE`, `FORMAT`, `COSTS` and `PLAN
FOR` are refused, and a query the engine refuses is refused under `EXPLAIN` in
its own words. An `EXPLAIN` runs wherever a query does: the GUI's query console,
the data viewer, a task's SQL.

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

Unknown variables raise `IllegalArgumentException` listing all known names. So
does any other `{{` left in the text: an expression is `{{ name }}`,
`{{ name + N }}` or `{{ name - N }}` with `N` a whole number, so
`{{ today - 1d }}`, `{{ today * 2 }}`, `{{ today 5 }}`, `{{}}` and an unclosed
`{{ today` fail the job before it runs, naming the expression, where each was
left in the SQL or the path as written (a filter compared against the template's
own text). Airflow renders its own `{{ }}` before a job reads the text, so one
left is always a mistake. An offset past a `BIGINT`, or one that moves the time
past what a date can be, is the same kind of error. A `}}` alone, as nested JSON
in a string literal ends, is text.

## Examples

Every example below is a job directory you can run with the `transformer`
jar. Each also has its own launcher target, which the end-to-end checks
use.

| Example | What it shows |
|---|---|
| [`examples/jaffle_shop/job`](examples/jaffle_shop/job) | Port of dbt Labs' [jaffle-shop](https://github.com/dbt-labs/jaffle-shop): 6 CSV seeds, 15 tables, 26 DBT `data_tests` ported as validations, Parquet outputs. The reference project to copy. |
| [`examples/directory_app/job`](examples/directory_app/job) | The smallest job: two CSVs, an enrich step, an aggregate with a templated `day={{ today }}` partition, and one validation. |
| [`examples/polymarket/job`](examples/polymarket/job) | A 17-table pipeline over a 39 GB Parquet dataset. See below. |
| [`examples/scala_app`](examples/scala_app) | The same shape as `directory_app`, built in Scala code (see [Embedding in Scala](#embedding-in-scala)). |

```bash
# Each launcher takes [jobDir] [outputDir] [executionTime].
bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar
# Exits 0 with 15/15 Succeeded under ${java.io.tmpdir}/transformer-jaffle-out/.

bazel build //examples/directory_app:directory_example_deploy.jar
java -jar bazel-bin/examples/directory_app/directory_example_deploy.jar \
    examples/directory_app/job /tmp/transformer-directory-out \
    2026-01-02T00:00:00Z
# Outputs land under day=20260102/, next to any earlier day=... run.
```

## Heavy-load example: polymarket

`examples/polymarket/` is a 17-table pipeline over the
[Polymarket tick-level orderbook dataset](https://www.kaggle.com/datasets/marvingozo/polymarket-tick-level-orderbook-dataset)
(Kaggle, 39 GB of Parquet across 5 sources). It exists to stretch the
executor on a realistic high-volume workload while exercising every layer of
the runner: streaming Parquet input with row-group pruning, multi-stage
Parquet output, parallel branches, and the `ValidationFailed -> downstream
Skipped` scheduler path.

**What it does:**

- Reads 5 Parquet inputs: all 21 daily orderbook files (35 GB), 21 daily
  snapshot files, the 1-minute feature table, every trade execution, and the
  market metadata.
- `stg_orderbook` keeps orderbook rows received after 06:00 UTC on
  2026-03-26. Parquet predicate pushdown compares that filter with each row
  group's `timestamp_received` statistics, so the other 20 days are skipped
  without reading their column data. Large files are split by row group and
  scanned on several cores at once.
- The staging tables produce 166.6M rows in total: 105.1M orderbook updates,
  51.7M snapshots, 5.6M feature rows, 4.1M trades, and 124K markets. They
  feed intermediate summaries (one aggregates the 105M orderbook rows into
  13.7K market groups in 10.6 s), then marts and a final report. All 17
  outputs are Parquet partitioned by `day={{ today }}`, with a `_run.json`
  per task and a `job.json` for the run.
- Carries 59 validations across the 17 tables, all in the "this query should
  return zero rows" shape.
- **Two branches are expected to fail validation on the real data.**
  `mart_orderbook_quality_check` asserts that no market has snapshot latency
  above zero. Real data does, so the task is `ValidationFailed` and its
  downstream `mart_quality_report` is `Skipped`. `final_combined_report`
  carries a `category_not_null` validation that catches the NULL
  `category_normalized` values that `mart_market_overview`'s LEFT JOIN
  against `int_markets_categorized` lets through. The other mart branches
  succeed. The launcher exits 0 only if exactly this pattern holds.

**Measured:** 90 s wall-clock end to end, JVM startup included, at
`-Xmx12g` on an Apple M4 (8 cores, 16 GB RAM), JDK 21, 2026-10-30. The
slowest tasks were `stg_orderbook` (105.1M rows, 77.4 s) and `stg_snapshots`
(51.7M rows, 58.0 s), which run concurrently.

**Prerequisite:** unpack the Kaggle dataset into `~/Downloads/archive/` so
the input paths in `job/inputs/raw_*/config.json` resolve. The configs
hardcode `/Users/owenchristie/Downloads/archive/...`. Edit them if your copy
is somewhere else.

**Run:**

```bash
bazel build //examples/polymarket:polymarket_deploy.jar
java -Xmx12g -jar bazel-bin/examples/polymarket/polymarket_deploy.jar \
    examples/polymarket/job /tmp/transformer-polymarket-out \
    2026-03-26T00:00:00Z
# 14 Succeeded,
# 2 ValidationFailed (mart_orderbook_quality_check, final_combined_report),
# 1 Skipped (mart_quality_report), 0 Failed. Exit 0.
```

Running the job with `transformer run` gives the same statuses, but exits 1
because two validations fail. The example's launcher is what treats that
pattern as success. `-Xmx12g` is the floor for the shipped configuration.
Widen the `timestamp_received` window to process more days, and raise the
heap with it.

## Embedding in Scala

Most projects never need this: the directory layout and the `transformer`
jar cover a pipeline end to end. If you'd rather build a job in code, or
embed the runner in a larger JVM application, the same engine is a Scala
library. `DataJob` is what `DirectoryJobLoader` builds from a job directory.

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
durations, and any warnings (see [Per-job `job.json`](#per-job-jobjson));
`succeeded` is true iff every task succeeded. If a validation fails the
corresponding task is marked `ValidationFailed`, its output is persisted
regardless (for debugging), and a sample of failing rows is written to
`_validation-<name>.csv` next to that task's `_run.json` — see [Run
records](#run-records-and-historical-runs).


### Plans and SQL

SQL becomes a logical plan in `LogicalBuilder.build`, and `PlanSql.print`
turns a plan back into SQL, which binds to the same plan again: the query as
the engine read it, with each name qualified, each `*` written out, each cast
the engine inserted written in, and each literal written so that it is its own
type. The optimizer's plan prints too, so you can read what it made of a query:

```scala
import com.transformer.core.Catalog
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer, PlanSql}

val catalog = new Catalog
catalog.register("t", tView)
catalog.register("u", uView)

val built = LogicalBuilder.build(
  "SELECT t.a, u.x FROM t JOIN u ON t.a = u.a WHERE u.x = 'two'", catalog)
PlanSql.print(built, catalog)
// SELECT t.a AS a, u.x AS x
// FROM t
// JOIN u ON t.a = u.a
// WHERE u.x = 'two'

PlanSql.print(LogicalOptimizer.optimize(built.main), catalog)
// WITH q1 AS (
//   SELECT u.a AS a, u.x AS x
//   FROM u
//   WHERE u.x = 'two'
// )
// SELECT t.a AS a, q1.x AS x
// FROM t
// JOIN q1 ON t.a = q1.a
```

A shape no SELECT holds, like the filter the optimizer pushed under the join
above, is printed as a CTE the query reads. A plan that reads a view no catalog
holds (a CTE's materialized rows) has no SQL form and throws
`PlanSql.UnprintablePlanException`. `SqlEngine.explain(query, catalog)` returns
the lines [`EXPLAIN`](#sql-features) does (`verbose = true` for `EXPLAIN
VERBOSE`), and `PlanTree.lines(plan)` a plan's tree alone.

### Loading a job directory from code

`DirectoryJobLoader.load` returns the same `DataJob` the CLI runs.

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
[Per-table output config (optional)](#per-table-output-config-optional).

### Building your own job binary

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
  not exposed as a global toggle. Temp files land in a directory of each
  JVM's own under `${java.io.tmpdir}/transformer-spill` by default — override
  the base via the `transformer.spill.dir` system property. A JVM deletes its
  own directory when it exits, and the next JVM to spill deletes those that
  JVMs no longer running left; nothing outside the base is touched (the sweep
  used to delete whatever stood beside it with a name starting as its did,
  such as `${java.io.tmpdir}/transformer-jaffle-out`, and a JVM exiting
  deleted the files another was spilling). DISTINCT aggregates
  (`COUNT`, `SUM` and `AVG(DISTINCT …)`) silently disable spill on the whole
  operator (their state doesn't round-trip through a typed schema in v1).
- **No subqueries** (scalar, IN/EXISTS, derived tables in FROM): use a `WITH`
  CTE, or chain `SQLTask`s via `viewName`.
- **Window functions: no `GROUPS` frames or `EXCLUDE`**, which the parser does
  not read, and no `FIRST_VALUE`, `LAST_VALUE`, `NTH_VALUE`, `PERCENT_RANK`,
  `CUME_DIST` or `NTILE`; a `RANGE` offset needs a single numeric `ORDER BY`
  key (a `DATE` would need an `INTERVAL`, which the engine has not).
  `COVAR_SAMP`/`COVAR_POP`/`CORR` are `GROUP BY` only.
- **The data viewer names a dataset's table `<dataset>__<table>`**, since the
  catalog has no schemas to write `jaffle_shop.customers` with, and it draws
  lineage only for runs whose records carry it: a run from before records
  listed their inputs shows its tables but not what they read. A cloud input
  is always drawn as coming from outside the folder.
- **Cloud paths recognized but unimplemented**: `gs://` and `s3://` paths are
  accepted by the config, and loading one raises a clear
  `UnsupportedOperationException`. See [Cloud](#cloud-gs--s3).
- **A CSV glob's schema is inferred from its first 1000 rows**, read across its
  files in order. A later value that does not read as its column's type fails
  the read, naming the file, line, column and value; an explicit schema
  (`inferSchema = false` with `columns`) reads every file as declared. A parquet glob checks every
  file against the first when it is opened (see [Parquet](#parquet)).
- **A query nests at most 256 levels deep.** An operator, a call, a CASE, a
  pair of parentheses and a SELECT each take a level of the tree the parser
  builds, so a chain of more than about 250 `+` or `||` terms, or a call or a
  CASE nested more than about 120 deep, is an error naming its depth (`The query
  nests 302 levels deep; the limit is 256. ...`): group the chain in
  parentheses, or compute part of it in a `WITH` clause. The limit is the same
  on every machine; past it some walk over the tree used to overflow its stack,
  at a depth that moved with the thread's stack size (1 MB on Linux, 2 MB on
  macOS) and with the JIT. An `AND` or `OR` chain (`id = 1 OR id = 2 OR ...`)
  and a `UNION` of any number of arms bind at any length, each regrouped into a
  balanced tree that answers as written. Parentheses that do not balance are a
  parse error naming the line and column of the one at fault, at once. The
  parser can still take seconds over some balanced nesting: 25 nested `CAST`s
  run it past its time limit, a parse error that says so.
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
TRANSFORMER_METRICS_ENABLED=1 java -jar transformer.jar run examples/jaffle_shop/job

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

## Development

transformer is Scala 2.13 on JDK 21, built with Bazel (`rules_scala`).

```bash
bazel test //...                                     # every test
bazel test //src/test/scala/com/transformer/sql/...  # just the SQL tests
bazel test //... --config=batch1                     # every test, 1-row batches
bazel build //:transformer_deploy.jar                # the product jar
bazel build //:transformer_viewer_deploy.jar         # the data viewer
```

Each leaf test directory has its own `scala_junit_test` target. SQL's
semantics are also pinned as sqllogictest scripts under `src/test/slt/`
(SQLite's format: each query beside its expected result), which
`slt/slt_test` runs under thirteen execution modes each (two of them run the
query's plan printed back as SQL), beside a slice of SQLite's own corpus and
ports of Postgres's regression tests with Postgres's answers. The suite reruns under `--config=batch1`, `batch2`,
`sharded`, `serial`, `zone` and `hashzero`, one JVM-wide setting each. Before landing a
change, also run the jaffle-shop example (see [Examples](#examples)).
[docs/testing.md](docs/testing.md) has the details.

### Contributor documentation

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

### Project layout

```
.
├── MODULE.bazel               Bazel deps: rules_scala 7.0.0, scala 2.13.16,
│                              jsqlparser 5.2, parquet-hadoop 1.14.3 (+ minimal
│                              hadoop 3.4.3), openjfx 21.0.1, richtextfx
│                              0.11.7 and atlantafx 2.0.1 (gui only), junit
├── .bazelrc                   JDK 21 build toolchain (runtime is JDK 21+)
├── BUILD.bazel                //:transformer, the deploy jar (CLI + GUI), and
│                              //:transformer_viewer, the data viewer's.
├── site/                      The project website (static HTML, no build).
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
│   ├── gui/                   JavaFX visualiser/runner, and the components
│   │                          both GUIs share (gui:components): the theme,
│   │                          the SQL editor and its vim engine, the SQL
│   │                          console, the row grid, the chart of a result
│   │                          (gui:chart has no JavaFX), the graph canvas.
│   ├── viewer/                The data viewer: SQL over a folder of job
│   │                          outputs, and each table's rows, columns,
│   │                          record and lineage (viewer:model has no
│   │                          JavaFX).
│   └── cli/                   `transformer run|gui|help`: Cli (headless,
│                              no JavaFX) + TransformerMain entry point.
└── src/test/scala/com/transformer/...   (mirrors src/main/scala layout)
```

For the full module-by-module breakdown — key files, responsibilities,
cross-cutting patterns — see
[docs/architecture.md](docs/architecture.md#module-map).

## License

To be added.
