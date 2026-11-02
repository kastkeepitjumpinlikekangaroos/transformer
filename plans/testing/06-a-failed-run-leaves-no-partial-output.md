# Plan 06: a failed run leaves no partial output, and its rerun needs no cleanup

> Status: **not started** · found 2026-11-01 by the test survey (category 1 of
> 9) and confirmed with the CLI jar · decided by the user: option (c), parts
> staged under hidden names and renamed once every partition has succeeded,
> and an in-progress `_run.json` written first; a final record that cannot be
> written fails the task · Effort: 2-3 days · Risk:
> medium (it changes the order of every output write and adds a status every
> reader of a record must show) · Depends on: nothing; it builds on
> `a3424bc`, whose `transformer query` work split `CsvWriter.scala` into
> `CsvWriter` and `CsvRowWriter`

## How this was found

The survey asked what the DAG fuzzer cannot produce. Its `Fails` tasks throw
before the engine runs (`JobDagGen.executor`, JobDagGen.scala:421-441) or
read a column that does not exist (the poison, :408-418), which fails at bind
time. No generated task fails after its output is written. Running two such
jobs through the CLI jar showed what happens when one does.

## Problem

| What fails | When | Left in the output directory | The next run, data fixed |
|---|---|---|---|
| A later partition: `SELECT x * 2` over a glob of a 10-row `a.csv` and a 300,000-row `b.csv` whose last value is 2000000000 | while the parts are written | `part-00000.csv`, the 10 rows of `a.csv`, and no `_run.json` | fails the task: `Output directory '.../doubled' already holds part-00000.csv, which no run record names, so a reader of the directory would read them as part of this output; delete them or write to another directory` |
| A validation whose SQL overflows (`SELECT x FROM t WHERE x * 2 < 0`) | after the write, one partition | `part-00000.csv`, no record | fails the same way |
| The final record's write (a full disk) | after the validations | every part, no record; the run reports `Succeeded`, since `writeSucceededRecord` swallows the error | fails the same way |
| The process (killed, out of memory, power) | from the first part committed to the record written, or inside `clearIfMarked` after it deleted the record | parts, no record | fails the same way |

The first two were run with the jar built from `f309205`'s tree
(`/private/tmp/claude-501/-Users-owenchristie-transformer2/0ca617c2-7eff-4ba8-a79c-df764adf4100/scratchpad/rerun-repro`
and `.../validation-repro`). The last two follow from the same code.

A scheduled job stays failed until someone deletes the files. Meanwhile a
reader outside the job (Spark, DuckDB, another job's input globbing
`part-*`) reads the partial table as the output. The message blames another
writer, though the files are the job's own.

The code:

- `CsvWriter.writePartitioned` and `ParquetWriter.writePartitioned` give each
  partition a writer whose `close()` renames its temp file to `part-NNNNN.*`
  the moment its partition ends. When another partition fails, `abortAll`
  deletes temp files only, so the parts already renamed stay.
- `DataJob.writeFailedRecord` (DataJob.scala ~:662-698) writes no record when
  the directory holds part files no record names, so that it never adopts
  another program's files. Here they are the failed run's own.
- `DataJob.writeOutput` (~:747-770) refuses such a directory (plan 29).
- `runOneTask` (~:381-475) writes the record after the validations, so the
  window spans the whole validation run.
- `TaskRunRecord.clearIfMarked` (TaskRunRecord.scala ~:275-293) deletes the
  record first and then the files. `writeSkippedRecord` and
  `writeFailedRecord` clear the same way before writing theirs.
- The GUI's `ResultPersister.persist` (gui/ResultPersister.scala:28-52) is a
  second writer of output directories with its own copy of these steps. It
  ignores `clearIfMarked` returning false, so it writes into a directory of
  stray part files that `DataJob.writeOutput` would refuse. Its record then
  lists every `part-*` file in the directory (`listPartFiles`, :80), not the
  ones it wrote. Found by plan 07's survey: four stray one-row parts, then
  one persisted row, give a `_run.json` that names all four files with
  `rowsProduced` 1. `writeSucceededRecord` lists the directory the same way,
  which plan 29's refusal keeps honest there alone.

README's "Per-task `_run.json`" already names one cause, "a run killed
between clearing and recording". The other three are ordinary failures.

## Decision (the user, 2026-11-01)

Option (c), both halves:

1. **A run marks the directory first.** Before it deletes or writes any file
   in a task's output directory, the run writes `_run.json` with the status
   `Running`, replacing the earlier record in one rename. From then on the
   directory is the job's, whatever the run leaves in it, and the next run
   clears it.
2. **Parts are staged and committed together.** Each partition writes under
   its hidden temp name. Only when every partition has succeeded are the temp
   files renamed to `part-NNNNN.*`. A failure before that leaves no part file.
   A crash during the renames leaves parts under a `Running` record, which the
   next run clears.
3. **A final record that cannot be written fails the task.** Today
   `writeSucceededRecord` and `writeValidationFailedRecord` swallow the error
   (a full disk), so the task reports `Succeeded` with no record on disk. The
   record is the commit, and every reader outside the run trusts it, so a
   task without one did not finish for any of them. The task fails, naming
   the write that failed. Its `Failed` record usually cannot be written
   either. Then the `Running` record stays, the next run clears it, and
   `job.json`, when it can be written, says `Failed`. `_perf.json` stays best
   effort: it is not the commit.

## The protocol

One routine carries it out, for every writer of an output directory: a
job's task (`DataJob`) and the GUI's saved query result (`ResultPersister`).
A record names the files its commit renamed, never a listing of the
directory.

For a task with an output directory:

1. **Check.** A directory with `part-*` files and no `_run.json` is refused,
   as today. It is someone else's.
2. **Mark.** `TaskRunRecord.write` writes a `Running` record: task name,
   execution time, `enqueuedAt`, `startedAt`, format, options and SQL, no
   output files, no rows.
3. **Clear.** Delete the earlier run's top-level `part-*`, `_*` and `.*`
   files, all but `_run.json`. That is what `clearIfMarked` deletes today,
   minus the record.
4. **Stage.** Each partition writes its temp file: `.<part>.<nanos>.tmp` for
   CSV and `.<part>.<nanos>.tmp.parquet` for parquet, the names the writers
   use today. `close()` flushes and closes without renaming.
5. **Commit.** Once every partition has succeeded, rename each temp file to
   its part name, in partition order. An all-empty parquet result then gets
   its header-only placeholder, as it does now.
6. **Read back and validate**, as now.
7. **Record.** The final record, `Succeeded` or `ValidationFailed`, replaces
   `Running`. It is the run's last write in the directory: `_perf.json` and
   the validation samples go before it.

If anything fails after step 2, delete the staged files and this run's
committed parts, then write a `Failed` record. A failed task's directory
then holds its record and nothing else, as `TaskRunRecord`'s doc already
says a `Failed` record's output is empty. If the process dies after step 2,
the `Running` record stays, and the next run's step 3 deletes whatever it
left. That includes the staged files, which start with `.`.

`writeSkippedRecord` follows the same rule: the `Skipped` record replaces the
old one first, and only then are the old files deleted. Nothing deletes a
record before writing its replacement.

What each reader sees:

- **Tasks in the same job** read an upstream's output by its record's file
  names (`TaskRunRecord.outputView`), and only after the final record. No
  change.
- **The GUI** (`JobSession`, the run picker, `TaskDetailsPanel`,
  `ResultsTabPane`) **and the data viewer** (`ViewerSession`, `DatasetTree`,
  `DetailsPanel`, `InspectorPane`, `LineageCanvas`) see a `Running` record read
  from disk as a task its run never finished: it stopped, or it is still
  running in another process. They show it as `UNFINISHED`, with no
  duration, no rows and no preview.
- **Outside readers** see no part file until the commit. Most of them skip
  names starting with `.` or `_`, as the engine's own `PathGlob` does
  (PathGlob.scala:51). After a crash during the renames they can see some
  parts under a `Running` record. The docs say: read a directory when its
  `_run.json` says `Succeeded` or `ValidationFailed`.
- **`job.json`**: a run that crashed writes none. The hydration rules for a
  missing `job.json` already apply ([plan 15 of the robustness series](../robustness/15-what-a-reopened-run-shows-without-its-record.md)).
- **An older binary** reads `Running` as an unknown status, so
  `TaskRunRecord.read` returns no record, as it does for any record it cannot
  read. `isMarked` asks only whether the file exists, so the older binary's
  next run still clears the directory.

## Claude's calls within the decision (report them; revisit if the user objects)

1. **A failed task leaves no part file.** This run's committed parts are
   deleted when it fails after the commit, because a validation threw or the
   record could not be written.
2. **The status on disk is `Running`, and readers show it as
   `UNFINISHED`.** In-process, the GUI keeps showing a live run from memory,
   as it does now.
3. **A `Running` record has no `finishedAt`.** The reader accepts that for
   this status only, and nothing computes a duration from it.
4. **`schemaVersion` stays 1.** No field changed meaning, and a reader that
   does not know a status reads no record.
5. **Two runs writing one output directory at once stay unsupported.** A live
   `Running` record looks the same as a dead one. gotchas.md says so.

## Decisions for the user

None left open: the user decided the protocol and the failed record write
(decisions 1 to 3 above).

## What to build

**W1. The protocol.**
- `TaskRunStatus.Running`; the reader takes a missing `finishedAt` for it.
- The writers split `close()` into finishing a part and committing it.
- `writePartitioned` in both writers commits once all partitions are done,
  and `abortAll` deletes staged and committed files alike.
- `DataJob.writeOutput` marks, clears, stages and commits.
- `runOneTask` writes the final record last.
- `writeFailedRecord` and `writeSkippedRecord` replace the record first.
- `TaskRunRecord.clearIfMarked` becomes a clear that leaves the record.
- The protocol lives in one place, which `DataJob` and `ResultPersister`
  both call. `ResultPersister` gains the stray-part refusal and the
  `Running` mark. Both records list the committed files rather than
  `listPartFiles`.
- Every match on `TaskRunStatus` gains `Running`: 13 files under
  `src/main/scala`, listed by `grep -rn 'TaskRunStatus\.' src/main/scala`.

Regressions in `DataJobTest`: the four cases in the table, each run and then
rerun with the data fixed. The rerun must succeed with the rows a clean run
gives. Done when all four pass and fail at the base commit, checked in a
worktree.

**W2. Fault points.** A small object in `core`, `Faults.at(point)`, called at
every place a run changes the disk:
- a partition's first batch written
- each commit rename
- each record write (`Running`, final, `Failed`, `Skipped`)
- each deletion while clearing
- the validation samples, `_perf.json` and `job.json`

It does nothing unless armed: one volatile read per call, and the calls are
per file, not per row. Two ways to arm it:
- In-JVM, through a test API.
- In a child JVM, through `-Dtransformer.fault=<point>:<n>:<action>`, read
  once per JVM, as `transformer.hash_zero` is (KeyHash).

The actions are `throw` (an `IOException` naming the point), `enospc` (an
`IOException` reading `No space left on device`) and `halt`
(`Runtime.getRuntime.halt`, so no `finally` block runs).

A `DataJobTest` matrix arms every point with `throw` over a job with two
partitions, a validation and a downstream task. After the failed run the
directory holds a `Failed` record and no part or staged file; the rerun
succeeds and matches a clean run. Then the same matrix runs with `halt` in a
child JVM. A child launches as
`java -cp <java.class.path> -Dtransformer.fault=... <a small test main running the job>`;
the parent reruns in-JVM and checks the same, plus that the crashed run's
leftover files are gone after the rerun.

**W3. The DAG fuzzer meets failures after the write.**
- `JobDagGen` gains two rigged outcomes:
  - `FailsWhileWriting`: an integer overflow on a row of a later input
    partition, through an input of several part files.
  - `FailsInValidation`: a validation whose SQL overflows.
- A fault mode arms a random point for one run of a `RunLayout` and leaves
  the next run clean.
- `DagSchedule` adds invariants:
  - After any run, every task's directory is absent or holds a record.
  - A `Failed` or `Skipped` record's directory holds no part or staged file.
  - A `Succeeded` or `ValidationFailed` record's output files are exactly
    the directory's `part-*` files.
  - A clean run after a failed one gets the statuses a clean run gets.
- `Hydration` learns `Running`: a reopened run shows such a task as
  unfinished, never as succeeded.
- Coverage guards: every new outcome, and every fault point, is drawn and
  reached over the default seeds.
- A `fuzz`-tagged campaign target runs generated jobs in a child JVM with a
  `halt` at a random point, reruns them in-JVM, and checks the same
  invariants. A full disk is the `enospc` action; a real one needs a mounted
  size-limited volume, which stays a manual check.

**W4. Docs**, in each commit (below).

## Tests

- `job:data_job_test`: W1's four regressions and W2's matrix.
- `fuzz:dag_scheduler_fuzz_test`: W3's outcomes, fault mode, invariants and
  guards.
- A new `fuzz:dag_crash_fuzz_campaign`, tagged `fuzz`.
- `gui:job_session_test`: reopening a run whose task stopped as `Running`.
- `gui:result_persister_test`:
  - a directory of stray parts is refused;
  - a failed persist leaves no part file;
  - the record names exactly the files written (the four-stray repro).
- `viewer:model_test` (`RunFolderTest`): such a task listed as unfinished,
  with no rows.
- `job:data_job_test`: a `Running` record without `finishedAt` read back.

Each new test must fail on the base commit, in a worktree, before it counts.
The survey's lesson: a property the generator cannot reach proves nothing.

## Docs

- **README**:
  - "Per-task `_run.json`": the `Running` status, the order a run writes in,
    what a failed task's directory holds, what a reader outside the job
    should check, and the paragraph about "a run killed between clearing and
    recording", which no longer applies to the job's own runs.
  - "Scheduling": a failed run is rerun as it is.
- **site/**: the setup page's run-record listing, if it names statuses.
- **docs/architecture.md**: the output-as-directory section becomes the
  protocol, and a paragraph covers fault points.
- **docs/conventions.md**:
  - A writer never publishes a part file before its task commits.
  - A record is replaced before any file it names is deleted.
- **docs/extending.md**: a new output format's writer stages and commits;
  how to add a fault point.
- **docs/gotchas.md**: two runs into one directory at once; outside readers
  and hidden files; an older binary reading `Running`.
- **docs/testing.md**: the new tests, `transformer.fault`, the crash
  campaign.
- **docs/code-map.md**: if `DataJob.scala` grows past its hot spot.

## Risks

- **Every output write changes order.** Before landing: the jaffle-shop job
  (15/15) and the polymarket job (its 14 / 2 / 1 pattern, in about 80 s, when
  `~/Downloads/archive/` holds its dataset) must
  keep their timings. The commit step is one rename per part file.
- **The GUI and the viewer gain a status.** The exhaustivity warnings name
  every match that lacks it. Plan 07 makes them errors.
- **A `Running` record from a live run in another process** is cleared by a
  second run started at the same time. That stays unsupported and documented.
- **A test JVM that halts** must be a child, never the Bazel test JVM; the
  launcher reads `java.home` and `java.class.path`.

## Launch prompt

```
Read plans/testing/06-a-failed-run-leaves-no-partial-output.md and implement
it, W1 to W4, one commit per workstream, each with its tests and docs.

First run ListAgents and git status. If another session is editing this
tree, develop in a git worktree outside the repo and copy back after
comparing against a base snapshot (memory: concurrent-sessions-worktree).

Follow CLAUDE.md's workflow: bazel test //... green, the jaffle-shop job
15/15, the polymarket job's pattern, every stale doc updated in the same
commit. Use JDK 21 from bazel's remotejdk21 to run jars (system java is 17).

Each regression must fail at the base commit, checked in a worktree, before
it counts.

Stop and ask before: changing the protocol the user decided (decisions 1 to
3: mark first, stage and commit together, a final record that cannot be
written fails the task); any change to what plan 29 refuses (a directory of
part files no record names stays refused); adding a dependency.

Report at the end: the commits, the four regressions and what each printed
before the fix, the fault points and how often the fuzzer reached each, any
defect the new outcomes found, and the jaffle-shop and polymarket timings
before and after.
```

