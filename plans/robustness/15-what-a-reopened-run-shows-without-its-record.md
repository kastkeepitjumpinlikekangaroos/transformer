# Plan 15: what a reopened run shows for a task without its record

> Status: **needs a decision** · Effort: half a day once decided · Risk: low ·
> Found by [plan 07](07-hydration-oracle-on-the-dag-corpus.md)'s hydration
> oracle; recorded in it as `Hydration.Degraded`

## How this was found

Plan 07's `Hydration` oracle reopens every run `fuzz/dag_scheduler_fuzz_test`
generates in a `JobSession` and holds what the session rebuilds to the
`JobResult` the run returned. Where the session has a task's `_run.json` it now
rebuilds the task exactly — the two fields it got wrong (a dropped `enqueuedAt`,
an output path for a task that wrote none) were fixed on discovery, see
`docs/gotchas.md`. Where it has no record for a task, it rebuilds the task from
`job.json`'s compact entry (`JobSession.taskResultFromSummary`), and that entry
does not carry everything the GUI renders.

Reached two ways:

- **A task with no output directory.** `job.json` lists it with `runFile: null`.
  A directory job always gives a table an output directory, so the GUI's own
  jobs never write one; a programmatic `DataJob` does, and the oracle reopens
  its memory-only tasks this way on every case.
- **A task whose `_run.json` is missing or unreadable** — deleted, truncated,
  or written by a run that crashed. The GUI's own jobs reach it here.

## What a session shows today

| field | `job.json`'s entry carries | the session rebuilds | what the GUI renders |
|---|---|---|---|
| status | yes | the right status | right |
| reason (Failed / Skipped) | yes | right | right |
| rows | yes | right | right |
| which validations failed | a count only | `ValidationFailed(Nil)` | every validation card reads **PASSED** beside a **VALIDATION FAILED** status; the details panel's failure list is empty; the run log says "validation failed (0)" |
| timestamps | no | all three at the epoch | the duration reads **0 ms** |

Nothing tells the user the task was rebuilt from less than its record: no
warning in the run log, no mark on the task.

## The judgement to make

What should a reopened run show for a task it has no record for? Each answer
means something different to a user, and the oracle encodes whichever is chosen:

1. **Say what is unknown.** Rebuild the status, reason and rows from the entry
   as now, and mark the task as rebuilt from the summary, so the panels show a
   validation's result and the duration as unknown rather than PASSED and 0 ms,
   and the run log names the record that is missing.
2. **Carry more in `job.json`.** Add each task's three timestamps and the names
   and row counts of its failed validations to its entry, so the entry rebuilds
   everything but the sample rows. Additive: an old `job.json` reads as today.
3. **Leave it, documented.** Say in the README that a task without its record
   shows the summary's status, reason and rows, and nothing else.

## What the oracle records meanwhile

`Hydration.Degraded` names each field the session shows wrongly from the entry
alone, with a repro (a directory job whose validation-failing task has its
`_run.json` deleted, reopened): `SummaryLosesValidationDetail` and
`SummaryLosesTimestamps`. The oracle still checks the fields the entry carries.
`DagSchedulerFuzzTest.everyDegradedHydrationStillReproduces` fails once an entry
stops reproducing, so whichever option lands must update the oracle in the same
change — removing an entry is the proof the decision landed.

## Verification

- A regression in `gui/job_session_test` for the chosen behaviour, on the
  repro above.
- `Hydration`'s summary check says what the chosen option shows, and the
  `Degraded` entries it resolves are gone.
- `bazel test //...` green; the three DAG campaigns green; jaffle-shop 15/15.

## Docs to update

`README.md` (the GUI section's account of reopening a run), `docs/gotchas.md`
(move this from open to done), `docs/testing.md` (the hydration oracle's
summary rule).
