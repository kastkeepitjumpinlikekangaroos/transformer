# Plan 29: files the run did not write join a task's output

> Status: planned · found by the 2026-10-31 audit · decided by the user:
> re-read only the files this run wrote, and refuse to write beside stray part
> files · Effort: half a day · Risk: low

## Problem

A task's output directory is cleared before a write only when it holds a
`_run.json` (`TaskRunRecord.clearIfMarked`), and a downstream task or a
validation re-reads the directory, every visible file in it. Measured at
`b2c83fc`: with `out/q/part-00007.csv` (one row, 999) left in place and no
`_run.json`, task `q` wrote `part-00000.csv` (one row) and task `r: SELECT a
FROM q` read **two** rows.

That directory arises whenever a run stops between clearing a directory and
writing its record (a killed JVM, a full disk), when two jobs share an output
directory, or when a user copies files in. Any other reader of the directory
(Spark, DuckDB, the GUI) sees the mix too.

## Fix

- The view a downstream task and a validation read is the part files this run
  wrote, by name, never a listing.
- Before writing, a directory holding `part-*` files but no `_run.json` fails
  the task, naming the files and saying to delete them or choose another
  directory; files that are not part files (a README) are left alone and are
  not read.

## Tests

`data_job_test` (stray part file, a README beside the output, a re-run over the
task's own marked output); the DAG fuzzer's corruption modes keep passing.

## Docs

README (outputs, run records), gotchas ("Output paths are directories"),
testing.md.
