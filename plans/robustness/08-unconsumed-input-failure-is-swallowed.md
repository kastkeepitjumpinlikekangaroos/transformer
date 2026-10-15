# Plan 08: an input that fails to load and feeds nothing is swallowed

> Status: **done** 2026-10-15 (see [Outcome](#outcome)) · Effort: half a day ·
> Risk: low · Carried over from the DAG-fuzzer session as a reported-not-fixed
> observation

## How this was found

By the DAG-scheduler fuzzer on 2026-09-05, and deliberately left unfixed: it is a
design judgement about what `succeeded` means, not a clear contract violation, so
it was reported rather than changed. It is recorded here so it stops living only
in session memory.

## Problem

`DataJob.runUnifiedDag`'s input completion handler fans a load failure out to the
input's dependents and nowhere else:

```scala
def handleInputCompletion(name: String, success: Boolean, error: Option[String]): Unit = {
  inputDependents.getOrElse(name, Vector.empty).foreach { i =>
    pendingInputDeps(i) -= 1
    if (!success && !upstreamFailed(i)) { ... }
    maybeSubmit(i)
  }
}
```

When `inputDependents` has no entry for that input — a declared input no task
reads — the `foreach` runs zero times and the failure leaves no trace. The job
reports `succeeded = true`, with no warning, no entry in `JobResult.warnings`, and
nothing in `job.json`. A user who typos the path of an input they stopped using,
or whose source disappears before the task that consumes it is written, gets a
clean green run.

The failure *is* observed — `onInputStart`/`onInputFinish` fire, and
`data_job_test` covers both paths — so this is purely about whether an observed
failure that affects no task is worth reporting.

## The judgement to make

Three defensible answers. The plan does not pick one; it asks for the decision,
because each means something different to a user:

1. **Warn, still succeed.** Add to `JobResult.warnings` (where
   `runConsistencyChecks` already puts declared-part-files-missing-on-disk, so the
   mechanism and the surfacing path both exist) and carry it into `job.json` so
   the GUI shows it. Least disruptive, and consistent with how the other
   "everything ran but something is off" condition is already handled.
2. **Fail the job.** A declared input that cannot be loaded is a broken job
   whether or not this run happened to need it. Strictest, and the most likely to
   annoy someone mid-development with a half-written job.
3. **Nothing, but documented.** Say in the README that inputs are loaded lazily
   *in effect* — a failure only matters if something consumes it — and leave the
   behaviour. Cheapest, and honest, but it leaves a silent failure silent.

Recommendation: **1**. It matches the existing warnings mechanism, keeps green
runs green, and makes the condition visible in the one artifact the GUI reads.

## Proposed fix (for option 1)

- Record the failure regardless of dependents — the natural spot is
  `runConsistencyChecks`, which already assembles `JobResult.warnings`, driven off
  the input-completion bookkeeping rather than off `inputDependents`.
- Thread it into `job.json`'s `warnings` array (`JobRunRecord` already writes
  one).
- Keep `succeeded` true for this condition alone, and say so explicitly in the
  scaladoc — the next reader will otherwise assume a warning implies failure.

## Verification

- A regression in `job/data_job_test`: a job with two inputs where the unloaded
  one has no dependents succeeds, carries exactly one warning naming that input,
  and the warning survives into `job.json`.
- `oracle/DagSchedule` needs a matching change, and this is the subtle part: its
  job verdict is *"`JobResult.succeeded` iff every task succeeded with no
  consistency warning"*. Adding a warning for an unconsumed input failure makes
  that clause false for a shape `JobDagGen` already generates — the generator
  deliberately points some inputs at paths matching no file. So the oracle must
  learn the new rule, or this change turns a passing campaign red. Decide the
  oracle's wording at the same time as the engine's behaviour, not after.
- `bazel test //...` green; the three DAG campaigns green; jaffle-shop 15/15.

## Docs to update

`README.md` (run markers / job.json section: what a warning means and that it does
not fail the run), `docs/gotchas.md` (move this from "reported, unresolved" to
done, or record the decision if the answer is option 3), `docs/testing.md` (the
`DagSchedule` verdict wording).

## Launch prompt

```
Read plans/robustness/08-unconsumed-input-failure-is-swallowed.md and implement
it.

Ask the user which of the three options they want BEFORE writing code — the plan
recommends option 1 (warn, still succeed) but the choice is theirs, and options 2
and 3 lead to different changes.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15, docs
in the same commit.

oracle/DagSchedule's job-verdict clause must change in the same commit — its
current rule is "succeeded iff every task succeeded with no consistency warning",
and JobDagGen already generates unloadable inputs, so the campaigns go red
otherwise. Run all three DAG campaigns before landing.
```

## Outcome

The user chose option 1 on 2026-10-15 and, asked which failed inputs it should
name, every one:

- **Every input that fails to load is a warning**, in declared order, whether
  or not a task reads it, not only the unread inputs this plan was about. A
  read input's failure was already visible through its dependents' `Skipped`
  reasons, but a skip reason names only the first failed upstream its task
  met, so a task reading two broken inputs left the second recorded nowhere.
  The warnings are now the one place every failed input is listed. A read
  input's warning is the text its dependents are skipped with,
  `failed to load input 'x': <cause>`; an unread one's says why the run did not
  fail on it, `failed to load input 'x', which no task reads: <cause>`.
- **A warning never fails a run.** `succeeded` is the tasks' verdict alone, and
  the scaladoc of `JobResult` and `JobRunRecord` now says so.

The engine change is small. `handleInputCompletion` keeps each input's failure
by index before the fan-out, which an unread input never reaches;
`runUnifiedDag` returns them beside the task results; `DataJob.inputWarnings`
words them, ahead of `runConsistencyChecks`' findings. `job.json` already wrote
`JobResult.warnings`, and the GUI's run log already showed them, live and on a
reopened run; its header said "consistency warning(s)" and now says
"warning(s)". An input whose exception has no message is now reported by the
exception's name rather than as `null`.

**The plan's premise about the generator was half right.** `JobDagGen` did
point inputs at missing files, but `attachOrphanInputs` gave every input a
reader (its comment said an unread input's failure would be invisible to the
oracle), so the campaigns never drew the shape this plan is about; only the
shrinker could orphan an input. Warning on every failed input would have
turned the old verdict clause ("no consistency warning") red on every
broken-input case, as the plan predicted; warning on unread inputs only would
have left it green, testing nothing. So the generator now also declares, in a
quarter of cases, one more input that no task reads, broken half the time,
drawn last from its own stream so every existing seed keeps its case, and the
shrinker can drop it. `oracle/DagSchedule`'s verdict is now: `succeeded` iff
every task succeeded, whatever the warnings say, and the warnings are exactly
one per input that failed to load, in declared order, each beginning as the
engine words it for a read or an unread input, and no other; `job.json` carries
the same list. A new guard, `generatorCoversInputFailures`, asserts the corpus
reaches unread inputs both broken and healthy, a case whose tasks all succeed
beside a broken one (the one shape where a warning meets a succeeded run), and
a task reading two broken inputs.

Breaking each rule in `DataJob` in turn fails the property within the first
fifty seeds, each with a minimized counterexample naming the broken rule: the
old behaviour (an unread input's failure swallowed), warnings for unread
inputs only, the unread warning without its clause, a warning failing the run,
`job.json` without the warnings, and the warnings in reverse order.
Regressions in `job/data_job_test`. `bazel test //...` 52/52, jaffle-shop
15/15, and the three DAG campaigns green at 20000 seeds each.
