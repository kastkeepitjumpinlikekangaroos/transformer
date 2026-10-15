# Plan 07: the run records are written under fuzz and read only by hand

> Status: **done** 2026-10-15 (see [Outcome](#outcome)) · Effort: 2 days ·
> Risk: low (test-only), though what it found changed `JobSession`, the record
> writers and `job.json`'s entries

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

## Outcome

Landed 2026-10-15, in the order the launch prompt asked for: the oracle over
the existing corpus, then the corruption mode, then the adversarial strings.
Each found defects, each fixed ahead of the commit that found it with a named
regression, and the one the plan reserved for the user became
[plan 15](15-what-a-reopened-run-shows-without-its-record.md), decided and
landed the same day.

**`JobSession` is the entry point, and it builds without JavaFX.** The check
the prompt asked for first: `new JobSession()` and `openJobDir` touch no toolkit
class (a probe hydrated a real run in a plain JUnit JVM). But the class lived in
the `gui` Bazel target, whose deps carry the JavaFX jars, and a fuzz target may
take no JavaFX dependency. `JobSession` and `DagLayout` now build as
`gui:session`, with no JavaFX, which `gui` exports; the fuzz library, the
session test and the layout test depend on it alone. `JobSession` opens a job
*directory*, so `JobCase.writeDirectoryJob` writes each generated job as one,
through `JobFiles`, whose tables `DirectoryJobLoader` gives the run's own output
paths and manifest.

**The oracle over the existing corpus** (`oracle/Hydration`). `JobDagGen` gained
a `RunLayout` — a templated run directory, a templated partition one to three
segments deep per task output, and a second run an hour later, a day later or a
day earlier — drawn after the DAG from its own stream, so a seed's DAG is the
one it always was. Sessions open at the latest run's execution time, through
the run picker at every other run, and fresh at each earlier time; `view`
decides from the case and the runs alone which run each shows and where each
task must come from, and every task is held to the `TaskResult` its run
returned. Its first runs found three defects in the read side:

- **A reopened task's `enqueuedAt` was dropped**, so every task of a reopened
  run showed as queued for fifty-six years on the DAG canvas and the details
  panel (`TaskResult.queueWaitMillis` now also reads the epoch as unknown, as
  its scaladoc promised).
- **A reopened Failed or Skipped task claimed its record's directory as its
  output**, so the details panel said "Wrote to" a directory holding only the
  record.
- **Newest-first orders were cut to the millisecond** in
  `JobOutputLayout.detect` and `TaskRunRecord.discover`: two runs inside one
  millisecond (a small job run twice) listed in the directory's hash order,
  and the session's fallback took an older partition as the newest. Seed 1259
  of the first 2000-seed sweep.

**The corruption mode.** Each case names one `JobDagGen.Corruption` of the
latest run's files — a `_run.json` deleted, cut short inside its object, or
followed by garbage; `job.json` deleted, or pointing a `runFile` nowhere; two
records swapped — applied after the intact check, and the sessions reopen the
damaged tree. Nothing threw and every task was accounted for, but what the
session showed in place of a missing record (a 0 ms duration, every validation
card PASSED beside a VALIDATION FAILED status, the other task's record trusted
after a swap, nothing said) was the product decision the prompt reserved. It
was recorded as `Hydration.Degraded` with repros until the user decided plan
15; the oracle now encodes the decided rules exactly, and breaking any one in
`JobSession` fails the property within ten seeds.

**The adversarial strings.** Half the cases draw task names, injected failure
messages (`JobCase.executor` throws the drawn text) and failing validations'
names from a pool of quotes, backslashes, newlines, tabs, every control
character, astral text and halves of a surrogate pair, invisible characters,
JSON- and template-shaped text, the empty string and a 100KB stack trace. It
found two defects in the write side:

- **Half a surrogate pair failed the write of every record holding it** —
  `_run.json`, `job.json` and `_perf.json` — silently, since a record write
  never fails a run: the writers put it in raw, and UTF-8 cannot encode it.
  The escape had five copies; it is now one per JSON reader (`Json.escape`,
  `JsonMini.escape`), writing such a character as a hex escape.
- **A failed validation's sample file name could collide or run past 255
  bytes**, so one failure reopened with another's rows, or with none. The slug
  is capped and a taken name gets `-2`, `-3`, ...

A directory job names a task after its directory, so an adversarial task name
cannot reach a session's task; those names are held through `job.json`, which
the session reads whole, and through `TaskRunRecord.read`.

**Guards.** One per step, as the prompt asked: the corpus reaches record-backed
and memory-only tasks of every status, every partition depth, two-partition
tasks, two-run layouts, rewrites and backfills; every corruption is drawn and
every decided rule reached; every adversarial string is drawn in every role and
read back by every path. The campaigns showed a fixed-size sample of real runs
can miss a rare shape at another base seed (no rebuilt `Failed` task at bases
5000 and 15000, with the property green), so the guards now reopen cases until
they have seen what they assert, up to a cap.

**Incidental.** `job/Json.scala` held a raw NUL character in a char literal, so
git and the editing tools took it for binary; `docs/conventions.md` now asks for
control characters to be spelled, not written.

**Verification.** `bazel test //...` green (52 targets), jaffle-shop 15/15, and
the real jaffle run reopened through `JobSession` exactly (15 tasks from their
records, real durations, queue waits of 0 or 1 ms; before the first fix, any
reopened task's queue wait was its start time in epoch milliseconds, about
1.79e12). The GUI app starts and opens the jaffle job; looking at it is for a
person. Campaigns on the final code, from snapshots of the launchers:
`dag_scheduler_fuzz_campaign`, `sharded_dag_scheduler_fuzz_campaign` and
`starved_dag_scheduler_fuzz_campaign` green at 20000 seeds each, as four
5000-seed blocks of the whole class (property and guards), about four minutes
a block beside two others. The round before the guards sought their shapes
found that flake and nothing else: the property was green at every seed of all
three.

