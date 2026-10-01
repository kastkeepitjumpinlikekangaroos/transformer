# Plan 07: the run records are written under fuzz and read only by hand

> Status: **not started** · Effort: 2 days · Risk: low (test-only) ·
> **Best value-per-day in this directory** — the corpus already exists

## How this was found

Noticing that the DAG-scheduler fuzzer already produces exactly the artifact the
GUI consumes, and then stops. `oracle/DagSchedule` checks each task's
`_run.json` status against the in-memory result and that `job.json` reaches every
task's record — and that last check exists *because* runner bug 04 was a manifest
that could not reach a Failed or Skipped task's record
([`plans/bugfixes/README.md`](../bugfixes/README.md)). So the write side is
fuzzed, at the level of "the pointer is present".

The read side is not. `JobSession` is 795 lines whose job is to hydrate a run
from those files — `_taskRecords`, `_historicalRuns`, `_jobRecord`,
`_availableRuns`, `_taskStates`, `_outputPaths` — and `JobSessionTest` is 104
lines with three tests, all about which format an output directory is read as.
`TaskDetailsPanel` (906 lines) and `ResultsTabPane` (632) render what hydration
produced and have no tests at all. `docs/testing.md` is upfront about this
("thin UI over engine APIs"), which is true of the panels and not true of
`JobSession`.

## Problem

The manifest contract is checked from the producer's side only. Bug 04's actual
user-visible damage was on the consumer side: *"`JobSession` hydration fell back
to the compact summary and lost the timestamps, validation entries and output
directory."* A check that the pointer exists would not have caught a hydration
path that ignores it, and nothing today checks that what was written can be read
back into the same run.

Specifically unasserted:

- Hydrating a generated run reconstructs per-task status, timestamps, row counts,
  validation entries and output paths matching the `JobResult` the runner returned.
- `JobOutputLayout.detect` classifies the generated output dir correctly
  (`SingleRun` vs `MultiRun` vs `Empty`) — it has a unit test over hand-built
  dirs, never over a fuzzer-built one.
- `TaskRunRecord.discover` finds the generated layouts, newest-first, including
  the multi-partition and templated-path shapes the generator produces.
- Hydration of a run containing **Failed and Skipped tasks** — the exact shape
  bug 04 lived in, and the one the examples never produce because
  `jaffle_shop` and `polymarket` happy paths leave no terminal failure.
- Hydration of a **partially-written** run: the job crashed after some
  `_run.json` files but before `job.json`, or `job.json` points at a record that
  is missing, truncated or has a trailing-garbage tail. These must degrade
  gracefully, not throw into the UI thread.
- Record JSON with **adversarial content**: an error message containing quotes,
  newlines, control characters or a 100KB stack trace. `JobRunRecord.escape`
  looks correct (quote, backslash, `\n`, `\r`, `\t`, and `c < 0x20` as `\uXXXX`),
  and `job/Json.scala` parses escapes — but nothing asserts the pair compose for
  generated strings, and a task name or failure message is attacker-adjacent in
  the sense that it comes from arbitrary user SQL.

## Proposed fix

Extend the existing DAG fuzzer rather than building a new one. The generator,
the on-disk corpus and the shrinker are all already there.

1. **`oracle/Hydration.check`** — after `DagSchedule` finishes deciding a run,
   open the same output directory through the GUI's own entry points and assert
   the reconstruction agrees with the in-memory `JobResult`: status per task,
   timestamps, validation entries, output paths, row counts, and the job-level
   verdict and warnings. Compare against the `JobResult` the runner returned, not
   against a second reading of the files — the point is that the two sides agree.
   Run it for every generated job, so Failed and Skipped tasks are covered by
   construction (the generator already rigs tasks to fail in the worker and to
   fail a validation, and `DagSchedule`'s guards already assert every terminal
   status is reached).
2. **A corruption mode in the generator** — after a successful run, apply one
   mutation to the output tree: delete a `_run.json`, truncate one mid-object,
   append garbage, delete `job.json`, point a `runFile` at a nonexistent path,
   swap two records. Assert hydration produces a *degraded but sane* session — no
   exception escaping, every task accounted for, and a warning or an explicit
   "unknown" rather than a silently wrong status. This is the JSON-robustness
   property and the partial-write property in one mode.
3. **Adversarial task names and error messages** — draw task names and injected
   failure messages from a pool containing quotes, backslashes, newlines, tabs,
   control characters, astral unicode and a very long string. The escape/parse
   round-trip is then fuzzed for free, and the property is just "hydration
   reproduces the string".
4. **Keep it off the GUI toolkit.** `JobSession` must be reachable without
   starting JavaFX for this to run in CI; check that before committing to the
   approach. If construction is entangled with the toolkit, the right move is to
   assert over `JobRunRecord.read` / `TaskRunRecord.discover` / `JobOutputLayout.detect`
   — the three pure entry points hydration is built from — and to note in
   `docs/testing.md` that the `JobSession` wrapper above them stays untested.
   Do not add a JavaFX dependency to a fuzz target.

Coverage guards, same house rule: assert the corpus reaches runs containing a
Failed task, a Skipped task, a validation failure, a multi-partition output and a
templated path; assert each corruption mutation is exercised; assert the
adversarial string pool is actually drawn.

## Why it is cheap

`JobDagGen` already renders whole jobs to disk under a scratch root, with
per-task writer format, spill and metrics knobs, inputs that fail to load, and
validation-induced edges. `Shrinker.jobCase` already minimizes a failing job.
The new code is one oracle plus one mutation mode — the expensive half is built
and already running in three campaigns.

## Verification

- `oracle/Hydration` green across the default seed budget, and across the three
  existing campaigns (`dag_scheduler`, `sharded_dag_scheduler`,
  `starved_dag_scheduler`) at a campaign budget — it costs little beyond the run
  each campaign already performs.
- `bazel test //...` green; jaffle-shop 15/15, and open the jaffle output in the
  GUI once by hand as a smoke check (`bazel-bin/examples/gui_app/gui_app`),
  since that is the real consumer.
- Any defect found gets a named regression in `job/data_job_test` or
  `gui/job_session_test` and a plan file if the fix is not a one-liner.

## Docs to update

`docs/testing.md` (the new oracle and mutation mode in the DAG-fuzzer entry; and
correct the "no JUnit tests / thin UI over engine APIs" line to distinguish
`JobSession` from the render-only panels), `docs/architecture.md` (the record
round-trip is now a checked contract, not just a written format),
`docs/gotchas.md` if the corruption mode turns up a hydration path that throws.

## Launch prompt

```
Read plans/robustness/07-hydration-oracle-on-the-dag-corpus.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15, docs
in the same commit.

Check FIRST whether JobSession can be constructed without starting JavaFX. If it
cannot, assert over JobRunRecord.read / TaskRunRecord.discover /
JobOutputLayout.detect instead and say so in docs/testing.md — do not add a
JavaFX dependency to a fuzz target.

Then the Hydration oracle over the existing corpus, then the corruption mode,
then the adversarial-string pool. Each as its own commit, each with its coverage
guard.

Run all three DAG campaigns before landing, including the starved variant
(docs/testing.md has the flags; --test_env= is load-bearing).

Stop and ask before: changing what a degraded hydration should report to the user
— "what succeeded means when a record is missing" is a product decision, the same
kind as the one parked in plans/robustness/08.
```
