# Plan 26: a task's output and a CSV header may repeat a column name

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · Effort: half a day ·
> Risk: low (the downstream task already failed, later and elsewhere)

## Problem

Names match without regard to case, so `a` and `A` are one name. Measured at
`b2c83fc` with directory jobs:

| job | result |
|---|---|
| task `q`: `SELECT a, a, b AS A FROM t`, task `r`: `SELECT * FROM q` | `q` Succeeded and wrote a file with three columns named a; `r` Failed: `Ambiguous column reference 'a' matches 3 columns` |
| the same with `"format": "parquet"` | the same |
| input CSV with header `a,a,,B,b` | loaded with columns `a`, `a`, ``, `B`, `b`; the task reading it failed on `a` |

The defect is in `q` and in the CSV, and the error names neither. A file whose
columns repeat a name cannot be read back by name by this engine, Spark or a
dataframe library without guessing.

## Fix

A task whose result has two columns of one name (case-insensitively) fails
before it writes anything, naming the columns and asking for an alias (a SELECT
that is not a task's output, and a CTE, may still repeat names as SQL allows; a
reference to one stays the existing ambiguity error). A CSV header with a
repeated or an empty name fails the input's load, naming the file and the
column (Spark renames, DuckDB renames; this engine refuses rather than guess).

## Tests

`data_job_test`, `csv_reader_test`.

## Docs

README (pipeline outputs, CSV), testing.md.

## Outcome

Done as planned. `Schema.repeatedName` finds the first name more than one
column has, compared regardless of case. `DataJob.runOneTask` fails a task
whose result has one before writing, naming the task and the columns (`1 (a),
2 (a) and 3 (A)`), and `CsvReader.checkHeader` fails an inferred CSV input's
load on a repeated or an empty header name, naming the file and the columns;
the load failure reaches the run's warnings and skips the tasks that read the
input, as plan 08 has it. A header under declared columns names nothing and is
not checked, and a query that is no task's result may still repeat names.

`aResultOrAHeaderThatRepeatsAColumnNameFails` and
`aHeaderThatRepeatsOrLeavesOutANameFails` failed against the code before.
