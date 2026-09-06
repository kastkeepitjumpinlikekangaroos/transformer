# Follow-up plans — gaps flagged in docs/gotchas.md

Four small, independent workstreams surfaced by a full read of
`docs/gotchas.md` and the `plans/` tree on 2026-09-05. Three are real SQL
engine gaps the gotchas doc itself already flags as "a candidate follow-up";
the fourth is a docs-accuracy pass the same audit turned up as a side effect.
Each plan is self-contained: read the file, paste the launch prompt into a
fresh Claude session, and the agent has what it needs.

## Prerequisite: land the pending DAG-scheduler-fuzzer work first

Before starting any plan below, commit the work already sitting uncommitted
in the working tree as of this audit: `src/test/scala/com/transformer/fuzz/
DagSchedulerFuzzTest.scala`, `JobDagGen.scala`, `oracle/DagSchedule.scala`
(all new/untracked), the two runner fixes in `DataJob.scala` / `SQLTask.scala`
/ `MaterializedView.scala`, and the matching doc updates across `README.md`,
`docs/architecture.md`, `docs/code-map.md`, `docs/extending.md`,
`docs/gotchas.md`, `docs/testing.md`, and `plans/bugfixes/README.md`. This is
bugs 03 and 04 from `plans/bugfixes/README.md` — already fixed, already
documented, and `bazel test //...` is green with it in the tree (46/46
targets passed, confirmed 2026-09-05). There's no remaining design work here,
just a commit. None of the plans below touch the same files, so landing it
first is safe and doesn't block anything.

## Priority and dependency table

| # | Plan | What | Effort | Risk | Depends on |
|---|------|------|--------|------|------------|
| [04](04-perf-docs-reconciliation.md) | Reconcile `plans/perf/` status against actual code | ~half a day | none (docs only) | — |
| [01](01-order-by-ordinal.md) | `ORDER BY <ordinal>` parses but silently doesn't sort | ~1 day | low | — |
| [02](02-binder-grouping-parens-and-trim.md) | Binder rejects `(a + b) * c`, `WHERE (x = y)`, `TRIM(x)` | ~1 day | low | — |
| [03](03-eval-evalvec-nan-parity.md) | `eval` vs `evalVec` disagree on NaN/-0.0 ordering and IN | 2-3 days | medium | — |

All four are independent — no plan depends on another, and none touch the
same files, so they can run in parallel in separate sessions.

## Recommended sequence

**Do first (quick, zero behavior risk):** plan 04. The perf plan tree
currently misrepresents 9 of its 12 plans as not-started or partially done
when they're actually shipped and tested. Leaving it stale risks a future
session re-implementing, or worse "fixing," work that doesn't need it.

**Real engine gaps, in severity order:**
- Plan 01 — silent wrong results (no error, the requested order is just
  ignored). Highest priority of the three because it fails quietly.
- Plan 02 — blocks valid SQL with a hard parse-time error. Annoying but
  loud, so lower urgency than a silent-wrong-answer bug.
- Plan 03 — narrowest in practice: only float/double columns actually
  carrying NaN or -0.0 are affected, and the fuzzers already exclude the
  shapes that would catch it, which is exactly why it's shipped unnoticed.
  Do last.

## How this list was produced

Read `docs/gotchas.md` end to end. Its "Known gotchas" section explicitly
flags three items as "a candidate follow-up" — those are plans 01-03 above.
Separately, cross-checked `plans/perf/README.md`'s priority table against
the actual `src/main` code for all 12 perf plans and found 9 of them fully
landed (01, 02, 03, 04, 05, 07, 09, 10, 11) but shown as not-started or
under-reported in the summary table — see
[plan 04](04-perf-docs-reconciliation.md) for the plan-by-plan evidence.
Two plans (06, 08) and one phase (12's Phase 4) are genuinely
partial/deferred exactly as already documented — left alone.

One trap worth naming, because it will recur: the first pass at the plan-02
(packed keys) question concluded `core/HashKeys.scala` was dead code, because
a grep for the literal string `HashKeys` (the file's own name) found no
callers outside the file. It has callers — under the type names the file
actually defines (`KeyCodec`, `PackedBytesCodec`, `ObjectArrayKey`) — in
`AggregateExec`, `DistinctExec`, `JoinExec`, and `WindowExec`. When verifying
a "done" or "not done" claim anywhere in this repo, grep for the symbols a
file exports, not the file's own name.

## Required workflow gates (applies to every plan)

From `CLAUDE.md`:

1. `bazel test //...` green.
2. `java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar` exits 0,
   15/15 Succeeded.
3. Docs updated where claims are now stale — in particular the matching
   `docs/gotchas.md` entry moves from "candidate follow-up" to fixed, and
   `docs/testing.md` gains any new regression coverage.

Project-wide rules: Scala 2.13.16 / JDK 21 (no bumps), no emojis, no new
heavy deps, prefer editing existing files, no back-compat shims, describe
current behavior in comments (no stale "phase N" scaffolding).

## Launch prompts

Each prompt is embedded at the bottom of its own plan file. Open a fresh
session, prefer `--effort max`, and paste the prompt — the agent reads the
plan from disk and acts.
