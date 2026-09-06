# Follow-up plans — gaps flagged in docs/gotchas.md

Four small, independent workstreams surfaced by a full read of
`docs/gotchas.md` and the `plans/` tree on 2026-09-05. Three are real SQL
engine gaps the gotchas doc itself already flags as "a candidate follow-up";
the fourth is a docs-accuracy pass the same audit turned up as a side effect.
Each plan is self-contained: read the file, paste the launch prompt into a
fresh Claude session, and the agent has what it needs.

## Prerequisite: land the pending DAG-scheduler-fuzzer work first — done

The DAG-scheduler-fuzzer work this audit found uncommitted (the fuzz
harness, `JobDagGen`, `oracle/DagSchedule`, the runner fixes in
`DataJob` / `SQLTask` / `MaterializedView`, and the matching doc updates)
landed as commit `fd32a0f`, "fixes, fuzz DAGs". That was bugs 03 and 04
from `plans/bugfixes/README.md`. Nothing is pending; the plans below are
free to start.

## Priority and dependency table

| # | Plan | What | Status | Effort | Risk | Depends on |
|---|------|------|--------|--------|------|------------|
| [04](04-perf-docs-reconciliation.md) | Reconcile `plans/perf/` status against actual code | **done** 2026-09-06 | ~half a day | none (docs only) | — |
| [01](01-order-by-ordinal.md) | `ORDER BY <ordinal>` parses but silently doesn't sort | not started | ~1 day | low | — |
| [02](02-binder-grouping-parens-and-trim.md) | Binder rejects `(a + b) * c`, `WHERE (x = y)`, `TRIM(x)` | not started | ~1 day | low | — |
| [03](03-eval-evalvec-nan-parity.md) | `eval` vs `evalVec` disagree on NaN/-0.0 ordering and IN | not started | 2-3 days | medium | — |

All four are independent — no plan depends on another, and none touch the
same files, so they can run in parallel in separate sessions.

## Recommended sequence

**Done:** plan 04. The perf plan tree misrepresented most of its 12 plans
as not-started when they were shipped and tested; `plans/perf/README.md`
now carries a verified status table and a "what's actually left" section,
and every plan file's own status header was corrected. Two claims in plan
04's own audit turned out to be wrong and are recorded at the bottom of
that file.

**Remaining — real engine gaps, in severity order:**
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
the actual `src/main` code for all 12 perf plans. Ten are landed (01, 02,
03, 04, 05, 07, 09, 10, 11, 12) — four with nothing left at all, the rest
with one optional evidence-gated phase — yet seven of them still said
"not started" in their own headers. Only 06 (partial by design) and 08
(unstarted, profiler-gated) were accurately described. See
[plan 04](04-perf-docs-reconciliation.md) for the plan-by-plan evidence
and for two errors in that audit's own first pass.

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
