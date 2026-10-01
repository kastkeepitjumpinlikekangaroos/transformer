# Plan 08: an input that fails to load and feeds nothing is swallowed

> Status: **not started** · Effort: half a day · Risk: low · Carried over from
> the DAG-fuzzer session as a reported-not-fixed observation

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
