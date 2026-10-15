# Plan 15: what a reopened run shows for a task without its record

> Status: **needs a decision** · Effort: half a day to a day once decided ·
> Risk: low · Found by [plan 07](07-hydration-oracle-on-the-dag-corpus.md)'s
> hydration oracle; recorded in it as `Hydration.Degraded`

## How this was found

Plan 07's `Hydration` oracle reopens every run `fuzz/dag_scheduler_fuzz_test`
generates in a `JobSession` and holds what the session rebuilds to the
`JobResult` the run returned. Where the session has a task's own `_run.json` it
now rebuilds the task exactly — the fields it got wrong there (a dropped
`enqueuedAt`, an output path for a task that wrote none, newest-first orders cut
to the millisecond) were fixed on discovery, see `docs/gotchas.md`. Then it
damages the tree one way per case — a record deleted, cut short or followed by
garbage, `job.json` deleted or pointing a `runFile` nowhere, two records
swapped — and reopens it. Nothing throws and every task is accounted for; what
follows is what the user sees instead of the run.

## What a session shows today

**A task `job.json` lists, with no readable record behind it** — a record that
is missing, truncated or has a tail, a `runFile` that resolves nowhere, or a task
with no output directory (`runFile: null`; a programmatic job's memory-only
task, which the GUI's own directory jobs never write). The session rebuilds it
from the entry (`JobSession.taskResultFromSummary`):

| field | the entry carries | the session rebuilds | what the GUI renders |
|---|---|---|---|
| status | yes | right | right |
| reason (Failed / Skipped) | yes | right | right |
| rows | yes | right | right |
| which validations failed | a count only | `ValidationFailed(Nil)` | every validation card reads **PASSED** beside a **VALIDATION FAILED** status; the details panel's failure list is empty; the run log says "validation failed (0)" |
| timestamps | no | all three at the epoch | the duration reads **0 ms** |
| output | the `runFile` | no output path for the panel; the `TaskResult` names the `runFile`'s directory whatever the status | "Will write to" for a task that wrote output; the SQL console finds no view for it |

A `runFile` that resolves nowhere is rebuilt from the entry even when the task's
own record sits at its configured path — the partition picker lists it — since
the session only falls back to the records on disk for a task `job.json` does not
name. A record that is unreadable also drops out of the partition picker, so its
run's data is unreachable from the GUI although its part files are intact.

**A record that names another task.** When two tasks' records trade places, each
task is rebuilt from the other's: its name, status, reason, rows and timestamps,
and that record in its partition picker. The record's `taskName` disagrees with
the `job.json` entry that pointed at it, and nothing checks.

**No readable `job.json`.** Every task with a record is rebuilt from the newest
one on disk, exactly; a task with none stays `Pending`, grey, which the README's
legend reads as "never run". The run's verdict and warnings are gone. In a
templated multi-run layout the session shows the newest run that still has its
`job.json`, and the run picker says which.

In none of these cases does anything tell the user that a task, or the run, was
rebuilt from less than its records: no run-log line, no mark on the task.

## The judgement to make

1. **A task with only its `job.json` entry.** What should it show?
   - *Carry more, then mark the rest.* Add each task's three timestamps and the
     names and row counts of its failed validations to its `job.json` entry, so
     the entry rebuilds everything but the sample rows (an old `job.json` reads
     as today); give the output path by the record rule (`runFile`'s directory
     when the status wrote output); and mark what is still unknown — the sample
     rows — and say in the run log which record was missing.
   - *Mark it unknown.* No format change: show the status, reason and rows,
     render the duration and each validation's result as unknown, and say in the
     run log which record was missing.
   - *Leave it, documented.*
2. **A record that names another task.** Reject it, and rebuild the task as for
   a missing record (with a run-log line), or keep trusting it.
3. **A `runFile` that resolves nowhere while the task's own directory holds its
   record for the same run.** Use that record, or rebuild from the entry.
4. **No readable `job.json`.** Say so in the run log, or stay silent.

Recommendation: carry more and mark the rest; reject a mismatched record; use
the task's own record when the pointer fails and that record is the same run's
(`executionTime` and `taskName` agree); and say so in the run log when
`job.json` is missing. Each leaves a reopened run either exactly what ran, or
visibly incomplete.

## What the oracle records meanwhile

`Hydration.Degraded` names each behaviour above that shows something other than
the run, with a repro on a two-table directory job (`checked` fails its
validation, `broken` fails outright): `SummaryLosesValidationDetail`,
`SummaryLosesTimestamps` and `SummaryLosesOutputPath` (`checked`'s `_run.json`
deleted), and `RecordOfAnotherTaskTrusted` (the two records swapped). The oracle
still checks every field the entry or the record carries.
`DagSchedulerFuzzTest.everyDegradedHydrationStillReproduces` fails once an entry
stops reproducing, so whichever option lands updates the oracle in the same
change — removing an entry is the proof the decision landed.

## Verification

- Regressions in `gui/job_session_test` for the chosen behaviour, on the repros
  above, and in `job/data_job_test` / `job/json_test` if `job.json` gains fields
  (an old manifest still reads).
- `Hydration`'s summary check says what the chosen option shows, and the
  `Degraded` entries it resolves are gone.
- `bazel test //...` green; the three DAG campaigns green; jaffle-shop 15/15.

## Docs to update

`README.md` (the GUI section's account of reopening a run, and the legend's
"never run"), `docs/architecture.md` (§4, the manifest's entry), `docs/gotchas.md`
(move this from open to done), `docs/testing.md` (the hydration oracle's summary
rule).
