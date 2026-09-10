# Follow-up plans — gaps flagged in docs/gotchas.md

Four small, independent workstreams surfaced by a full read of
`docs/gotchas.md` and the `plans/` tree on 2026-09-05. Three are real SQL
engine gaps the gotchas doc itself already flags as "a candidate follow-up";
the fourth is a docs-accuracy pass the same audit turned up as a side effect.
Plan 05 is a fifth, found while implementing plan 03. Plan 06 is a sixth, found
by the fuzzers within hours of plan 05 landing the corpus that reaches it.
**Five are done; 06 is open.**
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
| [01](01-order-by-ordinal.md) | `ORDER BY <ordinal>` parses but silently doesn't sort | **done** 2026-09-06 | ~1 day | low | — |
| [02](02-binder-grouping-parens-and-trim.md) | Binder rejects `(a + b) * c`, `WHERE (x = y)`, `TRIM(x)` | **done** 2026-09-06 | ~1 day | low | — |
| [03](03-eval-evalvec-nan-parity.md) | `eval` vs `evalVec` disagree on NaN/-0.0 ordering and IN | **done** 2026-09-06 | 2-3 days | medium | — |
| [05](05-minmax-nan-order-dependence.md) | `MIN`/`MAX` over a NaN-carrying float column is order-dependent | **done** 2026-09-09 | ~1 day | medium | — |
| [06](06-window-rank-peer-total-order.md) | `RANK`/`DENSE_RANK` rank tied rows separately when the ORDER BY key is NaN | not started | ~half a day | low | 05 (its corpus reaches the bug) |

No plan depends on another. **They do not all touch disjoint files, though** —
an earlier version of this line claimed they did, and it was wrong: 01 and 02
both edit `LogicalBuilder.scala` and `SqlEngineTest.scala`, and 02's
`selectListOrdinal` change lands inside the helper 01 introduced. Run those two
in sequence, branching the second off the first rather than off `main`. 03
touches `Expr.scala` / `Ops.scala` and is genuinely independent of both. All
three do edit `docs/gotchas.md` and `docs/testing.md`, so a plan branched off
`main` after another has landed will conflict there. 05 touches
`AggregateExec.scala` plus the fuzz generators and oracles (`DataGen`,
`MetaQueryGen`, `AggDecomposition`, `RowOracle`) and shares no source file with
any of them — but it edits the same two docs, so the same rule applies.

## Recommended sequence

**Done:** plan 04. The perf plan tree misrepresented most of its 12 plans
as not-started when they were shipped and tested; `plans/perf/README.md`
now carries a verified status table and a "what's actually left" section,
and every plan file's own status header was corrected. Two claims in plan
04's own audit turned out to be wrong and are recorded at the bottom of
that file.

**Done:** plan 01. All three ORDER BY sort sites — below `Project`, above
`Distinct`, above `Union` — now resolve a bare-integer ordinal to the
SELECT-list position it names, through a `selectListOrdinal` helper shared with
`GROUP BY` (whose behavior and error strings are unchanged). Out-of-range is now
an error rather than a silent no-op, and an ORDER BY position may point at an
aggregate where a GROUP BY position may not. 13 regressions in `SqlEngineTest`.
The plan file had two wrong claims of its own, both corrected in place and
recorded there: it counted three binding sites in a heading but enumerated only
two (the UNION site was missing and was equally broken), and it misattributed
`MetaQueryGen`'s source-column ordering to the ordinal bug rather than to the
alias-scope rule.

**Done:** plan 02. Grouping parentheses bind everywhere an expression does, and
`TRIM(x)` binds; a single `Grouped` extractor is matched at all five walks over
the JSqlParser AST, since the binder was only the first of them. `TRIM`'s
extended grammar is rejected with a clear error rather than supported, because
`Funcs` implements the single-argument whitespace trim only. 15 regressions in
`SqlEngineTest`. Three claims in the plan file were wrong or incomplete and are
corrected there: its `TrimFunction` snippet bound the remstr instead of the
target string (a silent wrong answer, measured); the `Parenthesis` case it cites
as existing coverage is dead code that jsqlparser-5.0 never constructs (removed,
along with the uncalled `rebuildExpr` that shared it); and it named one AST
traversal where there are five — missing the last of them would have made
`ORDER BY (1)` a silent no-op, re-creating plan 01's bug in a new spelling.

**Done:** plan 03. Numeric ordering (`< <= > >=`) in the vector path now goes
through `java.lang.Double.compare`, matching `Ops.cmp`'s total order, and the
`IN` fast path's boxed `HashSet` no longer imports `Double.equals` — signed
zero is canonicalized into the set and a `NaN` probe bypasses it, falling
through to the NULL-item branch so `NaN IN (1.0, NULL)` stays NULL. 6
regressions in `ExprBatchTest` (all six fail against the old code). The fuzzer's
exclusion came off and a coverage guard went on; 2,000,000 seeds green. Four
claims in the plan file were wrong or incomplete and are corrected there — most
consequentially, it named two generator gates where there were three (the third
being the value generator, which normalized every NaN and -0.0 away, so
removing only the shape gates would have produced a green and vacuous fuzzer).

**Done:** plan 05. `MinMaxState`'s `Float`/`Double` slot advances through
`java.lang.Double.compare` at all three sites — `updateAt`, `updateBatchAt` and
`merge` — instead of raw IEEE `<` / `>`, so `MAX` over a column containing `NaN`
is `NaN` and `MIN` over one is the smallest non-`NaN`, matching `ORDER BY`,
`GREATEST`/`LEAST` and Postgres, and no longer moving with row order, partition
fan-out or spill flush order. That is a user-visible semantic change to an
aggregate, not only a determinism fix. Five regressions in
`hash_aggregate_spill_test`, all five failing against the old comparison. The
corpus half took two generators, not one: `DataGen` and
`MetaQueryGen.payloadDomain` are independent value pools, and only the first is
what `DataGen.generate` feeds — so widening `DataGen` alone would have left the
aggregate-decomposition relation, the one that actually drives `merge`, on an
all-finite corpus. Both now draw `NaN` / `-0.0` / `±Infinity` through the shared
`DataGen.withSpecialDoubles`, with a coverage guard on each side. Two claims in
the plan file were wrong and are corrected there — most consequentially the
one-corpus assumption above, and a claim that `HashKeys` excludes `Float` /
`Double` from packed keys (it does not: `isPackable` includes them, and it is
`isLongFittable`, a different gate, that excludes them).

**Open:** plan 06. `WindowExec.sameKey` compares ORDER BY peers with `Ops.eq`
while the sort that produced the order used `Ops.cmp`, so `RANK`/`DENSE_RANK`
give tied `NaN` rows different ranks (a wrong answer, not just a
layout-dependence) and give strictly-ordered `-0.0`/`0.0` rows the same rank.
Pre-existing — `WindowExec` is untouched by plan 05 and the failing query holds
no aggregate — but reachable only since plan 05 widened the corpora, so
`metamorphic_fuzz_campaign` and `sharded_mode_fuzz_campaign` are **red at seed
16502 until 06 lands**, deliberately: the alternative was gating the generator
back down, which is how this repo has hidden engine bugs before. Everything
else is green — `bazel test //...`, jaffle_shop, `mode_differential_fuzz_campaign`
at 20000 seeds, and 7 of 8 seed blocks in each of the other two campaigns.

## How this list was produced

Read `docs/gotchas.md` end to end. Its "Known gotchas" section explicitly
flagged three items as "a candidate follow-up" — those are plans 01-03 above.
All three have moved to fixed there, and so has the fourth that plan 03's
implementation added and plan 05 then closed. `docs/gotchas.md` flags exactly
one "candidate follow-up" today, and the fuzzers wrote it rather than an audit:
the `RANK`/`DENSE_RANK` peer entry plan 06 covers, which
`metamorphic_fuzz_campaign` found at seed 16502 once plan 05 widened the corpus
to carry `NaN`. That is the list feeding itself — each fix in this value domain
has exposed the next one, and all three so far have been the same mistake in a
different file.
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
