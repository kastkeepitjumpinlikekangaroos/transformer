# Plan 04: Reconcile `plans/perf/` against what's actually shipped

> Status: not started · Effort: ~half a day · Risk: none (docs only, no behavior change)

## Problem

`plans/perf/README.md`'s priority table and "Recommended sequence" present
the performance workstream as a 12-plan backlog with almost nothing landed —
only plan 03 gets an inline "(Phases 1-4 done; 0/5/7 remain)" note and plan 12
gets "(Phases 1-3 done; Phase 4 refinements deferred)". Every other plan
(01, 02, 04, 05, 06, 07, 08, 09, 10, 11) shows no status at all, and the
"Recommended sequence" section frames 01/05/06 as "quick wins (do first)" and
03/04 as "structural commitments... do last" as if none of it exists yet.

A read of the actual code (2026-09-05) shows **9 of the 12 plans are fully
implemented, one more (12) is implemented except an explicitly-optional
phase, and 03's own "0/5/7 remain" note is itself wrong** — 5 and 7 are also
done. Only two plans are genuinely incomplete, and both already say so
accurately in their own files. Leaving the summary table this stale risks a
future session re-implementing (or "fixing") work that already shipped and
is covered by tests — which is exactly the kind of wasted effort this doc
exists to prevent.

## Verdict table (verified against `src/main`, 2026-09-05)

| # | Plan | perf/README status | Actual status | Evidence |
|---|------|---------------------|----------------|----------|
| 01 | K-way merge SortExec | none shown | **DONE** | `SortExec.scala`'s `mergeEmit` uses a `java.util.PriorityQueue`-based heap merge (`heapCmp`, `numCursors`), not pairwise merge |
| 02 | Packed agg/distinct/join keys | none shown | **DONE** | `core/HashKeys.scala` (`KeyCodec`, `PackedBytesCodec`, `ObjectArrayKey`, `EmptyKeyCodec`) is wired into `AggregateExec`, `DistinctExec`, `JoinExec`, and `WindowExec` via `KeyCodec.forColumns` + a `KeyPathPacked`/`KeyPathSingleObject` dispatch; the single-Long fast path (`KeyCodec.isLongFittable`/`readAsLong`/`writeLongTo`, `keyMapLong`) covers what the plan's deferred Phase 6 (`LongHashMap`) asked for. **See the caution below — this was misread as dead code on a first pass.** |
| 03 | Vectorized expr eval | "Phases 1-4 done; 0/5/7 remain" | **DONE** (this note is wrong) | Phase 0: `ExprBatchTest` exists and is the fuzz harness's trusted oracle. Phase 5: `FuncExpr`/`CaseExpr`/`InListExpr`/`LikeExpr` all `override def evalVec` in `Expr.scala`. Phase 7a/b/c: `AggregateExec`, `JoinExec`, `WindowExec` all extract keys via `evalVec` at exactly the call sites the plan named. Only Phase 8 (vector pooling) is open, and it's explicitly conditional on profiler evidence. |
| 04 | Hash-partition breakers | none shown, "depends on 02, 03" | **DONE** | `ExchangeExec` + `HashPartitioner` fully wired into `PhysicalPlanner`; this is the machinery behind the entire `plans/bugfixes/02(a-f)` sharded-deadlock investigation, and `docs/gotchas.md` documents it in detail. Its stated dependency on 02/03 is satisfied — both are also done. |
| 05 | Smarter join planner | none shown | **DONE** | `PhysicalPlanner`'s build-side swap logic and `enforceNestedLoopGuard`, documented in `docs/gotchas.md` ("The planner may swap join build sides...", "Non-equi joins are refused..."). |
| 06 | Expand parquet pushdown | none shown | **PARTIAL — accurate** | `ParquetFilterTranslator` handles IS NULL/IS NOT NULL, IN, and BETWEEN (Phases 1/2/3 done via BETWEEN's existing AND-lowering); decimal columns (Phase 4) explicitly return `None` at two sites — matches `docs/gotchas.md`'s "best-effort partial" description. Nothing to fix here beyond adding a status line. |
| 07 | Push predicates/projections through joins | none shown | **DONE** | `FilterPushdown.scala` + `ColumnProjectionPushdown.scala`, both documented in detail in `docs/gotchas.md`, including the outer-join correctness caveat the plan flagged as the landmine. |
| 08 | Stream emit from breakers | none shown | **NOT STARTED — accurate** | No `ArrayBlockingQueue` or producer/consumer streaming in `sql/exec`; operators fully materialize before emitting. The plan's own launch prompt already makes this conditional on profiling showing genuine writer starvation — leave as-is. |
| 09 | Spill-to-disk | none shown, "survival, only if..." | **DONE** | `docs/gotchas.md`'s "What's intentionally NOT done" section documents spill as implemented for all five named operators (SortExec, HashAggregateExec, DistinctExec, HashJoinExec, WindowExec), opt-in via `options.spill`, with exactly the caveats (non-equi joins, `COUNT DISTINCT`, WindowExec's PARTITION BY requirement, fixed 16-bucket grace hash) the plan called out. |
| 10 | Instrumentation | none shown | **DONE, correctly self-documented** | `MetricsCollector`, `_perf.json`, `metrics_collector_test`, `operator_counters_test`, `metrics_plan_wrap_test` all present. Only the README's summary table fails to mark it. |
| 11 | Benchmarking | none shown | **DONE, correctly self-documented** | `docs/benchmarking.md` is referenced live from `docs/gotchas.md`. Only the README's summary table fails to mark it. |
| 12 | CTE materialization | "Phases 1-3 done; Phase 4 deferred" | **Accurate as written** | Own file already carries the correct status line. No change needed beyond the shared README table. |

**Caution for whoever does this work**: the first pass at plan 02 concluded
`HashKeys.scala` was dead code, because a `grep -r "HashKeys"` across
`src/main` found no hits outside the file itself. That's the wrong search —
`HashKeys.scala` doesn't export a symbol called `HashKeys`; it exports
`KeyCodec`, `PackedBytesCodec`, `ObjectArrayKey`, and `EmptyKeyCodec`, all of
which are used elsewhere. **When verifying a "done" claim, grep for the
types/methods a file actually defines, not the file's own name** — the
`docs/code-map.md` entry (if any) or the file's own `object`/`class`
declarations are the source of truth for what to search for.

## Proposed fix

1. **Rewrite `plans/perf/README.md`'s priority table** to mark 01, 02, 04,
   05, 07, 09, 10, 11 as done (with a one-line evidence pointer each, not a
   full audit trail — that lives in this file), 06 and 12 as accurately
   partial, and 08 as accurately not-started/conditional.
2. **Rewrite the "Recommended sequence" section** — it currently reads as a
   forward-looking roadmap for a workstream that's ~90% shipped. Replace it
   with: what's actually left (06 Phase 4 decimal pushdown if profiling ever
   shows it matters, 08 if profiling shows writer starvation, 12 Phase 4 if
   the optional refinements become worth it), and drop the "tier"/"do
   first"/"do last" framing that no longer applies.
3. **Fix plan 03's own file** (`plans/perf/03-vectorized-expr-eval.md`) — its
   "Status" line and the "WHAT'S REMAINING" section in its own launch prompt
   both claim Phase 5/7 are outstanding. Correct both, or the next session
   that opens this file specifically (rather than the README) will repeat
   the same mistake.
4. **Add a "Status:" line to every plan file that lacks one** (01, 02, 04,
   05, 06, 07, 08, 09, 10, 11), matching the style already used in 03 and
   12's headers, so a future reader doesn't have to cross-reference the
   README to know whether a given plan file describes shipped or
   speculative work.
5. Leave the plan files' body content (design rationale, phase breakdowns)
   otherwise untouched — they're accurate historical design records, just
   missing a "this happened" marker at the top.

## Files to touch

- `plans/perf/README.md`
- `plans/perf/03-vectorized-expr-eval.md`
- `plans/perf/01-kway-merge-sortexec.md`, `02-packed-aggregate-keys.md`,
  `04-hash-partition-breakers.md`, `05-join-planner.md`,
  `06-expand-parquet-pushdown.md`, `07-push-through-joins.md`,
  `08-stream-emit-breakers.md`, `09-spill-to-disk.md`,
  `10-instrumentation.md`, `11-benchmarking.md` — one-line status header
  each.

No `src/main` or `src/test` changes; no `bazel test` or e2e re-run needed
(docs-only), but run them anyway per the standard gate since it's cheap
insurance against having missed something while reading.

## Launch prompt

```
Read plans/followups/04-perf-docs-reconciliation.md and carry out its
"Proposed fix" section.

This is a docs-only change — no src/main or src/test edits. Before writing
anything, independently re-verify every "DONE" verdict in the table by
reading the actual code (not just trusting this file) — grep for the types
each perf plan's own "Files to touch" section names, not the plan's own
title or filename (see the "Caution" paragraph: a filename-only grep
produced a false negative on plan 02 during the original audit).

Update plans/perf/README.md's table and "Recommended sequence", fix plan
03's status line and launch-prompt text, and add a one-line "Status:" header
to every other perf plan file that's missing one.

Run bazel test //... and the jaffle_shop deploy jar anyway even though
nothing in src/ changes, as a sanity check that this session didn't touch
anything it shouldn't have.

Stop and ask before: changing any plan file's design/rationale content —
this is a status-accuracy pass only, not a rewrite of the technical content.
```
