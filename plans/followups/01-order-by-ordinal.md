# Plan 01: `ORDER BY <ordinal>` silently doesn't sort

> Status: **done** 2026-09-06 · Effort: ~1 day · Risk: low

## Problem

`ORDER BY 2 DESC` parses and executes without error, but returns rows in scan
order. The ordinal binds as an `IntType` literal (a constant sort key), so
`LogicalSort` sorts every row against the same constant and the comparator
never distinguishes two rows. This is a silent-wrong-answer bug, not a
rejection — worse than the other binder gaps in this plan set because nothing
tells the caller their ORDER BY was ignored.

`GROUP BY <ordinal>` already resolves positionally and works correctly
(`LogicalBuilder.resolveGroupOrdinal`, `LogicalBuilder.scala:930-944`). ORDER BY
never got the equivalent treatment. Flagged as a candidate follow-up in
[docs/gotchas.md](../../docs/gotchas.md) ("`ORDER BY` binds against the scope
at that point in the plan, and an ordinal is a silent no-op").

## Current behavior — three binding sites, three scopes

> **Correction (found while implementing).** This section's heading says three
> sites but only enumerated two. The third is the **set-operation** site,
> `buildSetOperationList` (`LogicalBuilder.scala:183-194` at a829794), which
> binds a UNION's ORDER BY against `union.outputSchema` via plain `bindExpr` and
> had the identical silent-no-op bug — confirmed by test, `ORDER BY 1 DESC` over
> a `UNION ALL` returned scan order `[2, 4, 1, 3]`. It is listed as site 3 below
> and was fixed with the other two. Its scope, like DISTINCT's, is the output
> schema 1:1 by position.

ORDER BY's binding scope depends on what sits between it and the SELECT list,
so the fix has to resolve the ordinal against the right thing in each case:

1. **No DISTINCT** (`LogicalBuilder.scala:315-320`) — `Sort` sits *below*
   `Project`, bound via `exprBinder` (the same binder used for the SELECT
   list itself, closing over whatever `aggResolver`/`windowResolver` the
   query needs). This is also the path an aggregated query takes — the scope
   is "the aggregate output" per the gotcha, but that's `exprBinder`'s job
   already; it only needs to be handed the right *expression* to bind (see
   below).
   ```scala
   case (Some(list), false) =>
     val keys = list.map(obe => (exprBinder(obe.getExpression), obe.isAsc))
     LogicalSort(preProject, keys)
   ```
2. **DISTINCT** (`LogicalBuilder.scala:327-337`) — `Sort` sits *above*
   `Distinct`, bound via plain `bindExpr` against a synthetic one-relation
   `Sources` built from the post-projection schema:
   ```scala
   case (Some(list), true) =>
     val projSchema = Schema(projections.iterator.map { case (e, n) => Field(n, e.dataType) }.toVector)
     val sources: Sources = Seq((None, projSchema))
     val keys = list.map { obe => (bindExpr(obe.getExpression, sources), obe.isAsc) }
     LogicalSort(afterDistinct, keys)
   ```
   Here the scope IS the projection, 1:1 by position — an ordinal doesn't
   need re-binding through `sources` at all, it can point straight at the
   schema.
3. **Set operation** (`LogicalBuilder.scala:183-194`) — a `UNION`'s trailing
   ORDER BY (whether written on the set-operation list or hoisted off the last
   bare arm) sorts above `LogicalUnion` and binds against its output schema,
   whose names come from the leftmost arm:
   ```scala
   val unionSources: Sources = Seq((None, union.outputSchema))
   val keys = list.asScala.map { obe =>
     (bindExpr(obe.getExpression, unionSources), obe.isAsc)
   }.toSeq
   ```
   Same shape as site 2: the scope is the output schema positionally, so an
   ordinal indexes it directly.

All three sites called `obe.getExpression` straight into a binder with no
ordinal check, so a bare `LongValue` became `LitExpr(v, IntType)` via the normal
`bindExprWithAggs` literal case (`LogicalBuilder.scala:527-530`) — a constant,
not a reference.

## Proposed fix

Add an ordinal resolver, modeled on `resolveGroupOrdinal` but without its
"cannot refer to aggregate" guard — `ORDER BY` legitimately targets an
aggregate's output position (`SELECT a, SUM(b) FROM t GROUP BY a ORDER BY 2`
is standard SQL). Either generalize `resolveGroupOrdinal` with a
`disallowAggregates: Boolean` parameter, or add a sibling
`resolveOrderOrdinal(e, expandedItems)` that shares its bounds-check logic.

- **Site 1 (non-DISTINCT, line ~315-320)**: run `obe.getExpression` through
  the ordinal resolver against `expandedItems` *before* calling `exprBinder`
  — exactly how `groupExprs` does it today (`LogicalBuilder.scala:372`,
  `rawGroupExprs.map(resolveGroupOrdinal(_, expandedItems))`). Because
  `exprBinder` already closes over the correct resolvers, this one change
  covers both the plain-select and the aggregated-select cases — no separate
  handling needed for "past a GROUP BY."
- **Site 2 (DISTINCT, line ~327-337)**: resolve the ordinal directly to a
  `ColRefExpr(v - 1, projSchema.fields(v - 1).name, projSchema.fields(v - 1).dataType)`
  instead of re-binding through `sources` — the post-DISTINCT scope is the
  projection by position, so there's no source expression to re-bind. Keep
  the existing `bindExpr(obe.getExpression, sources)` path for the
  non-ordinal case.
- Bounds check: reuse `resolveGroupOrdinal`'s error shape —
  `"ORDER BY position $v is not in select list (size ${expandedItems.size})"`
  for `v < 1 || v > expandedItems.size`.

## Decisions to confirm before implementing

- **`ORDER BY (1)` / `ORDER BY +1`**: `resolveGroupOrdinal`'s doc comment
  says only a *bare* integer literal is treated as an ordinal — a
  parenthesized or signed one stays a literal expression (matches
  BigQuery/Postgres/MySQL `GROUP BY` behavior). Do the same for `ORDER BY`
  for consistency, rather than inventing a different rule.
  **Confirmed, and half of that doc comment was wrong.** Parsing each form
  against JSqlParser 5.0 shows: `ORDER BY 2` is a `LongValue` (an ordinal),
  `ORDER BY +1` / `-1` are `SignedExpression`, `ORDER BY 1.0` is a
  `DoubleValue`, and `ORDER BY (1)` is a `ParenthesedExpressionList` — so the
  non-bare forms all stay expressions, as intended. But `GROUP BY (1)` parses
  to a bare `LongValue`: JSqlParser drops the parens in that position, so
  GROUP BY has always treated `(1)` as an ordinal despite its doc comment
  saying otherwise. The comment is corrected in the code rather than the
  behavior, which matches Postgres. `ORDER BY (1)` throws
  `Unsupported expression: ParenthesedExpressionList: (1)` (plan 02's gap),
  unchanged by this work.
- **Non-integer / out-of-range ordinals**: match `GROUP BY`'s existing error
  message shape and bounds behavior rather than a bespoke one.

## Files to touch

- `src/main/scala/com/transformer/sql/plan/LogicalBuilder.scala` — the three
  ORDER BY binding sites (~183-194, ~315-320, ~327-337) plus the new/generalized
  ordinal resolver (~926-944).
- `src/test/scala/com/transformer/sql/exec/SqlEngineTest.scala` — add cases:
  `ORDER BY 2 DESC` over a plain SELECT, over a GROUP BY (ordinal pointing at
  an aggregate column), over DISTINCT, and the out-of-range error case. This
  file already has GROUP BY ordinal coverage to pattern-match against.
- `docs/gotchas.md` — move the "ORDER BY... ordinal is a silent no-op" note
  from "candidate follow-up" to fixed, or delete it if the surrounding
  paragraph's other point (ORDER BY scope resolution) still needs to stand
  alone.

## Testing

No fuzzer changes are required to land this, and none were made — the fix
ships with hand-written regressions in `SqlEngineTest`.

> **Correction (found while implementing).** The reason given here for
> `MetaQueryGen.totalOrderKeys` was wrong. Its doc comment
> (`MetaQueryGen.scala:302-319`) says nothing about ordinals: it orders top-n
> queries by SOURCE columns because ORDER BY binds against the scope at that
> point in the plan, so `ORDER BY o0` over `SELECT t0.c0 AS o0` fails with
> "Unknown column 'o0'" — the alias-scope rule, not the ordinal no-op. The same
> rule is why it excludes `DISTINCT`, windowed, aggregate and `UNION` shapes
> from top-n entirely.
>
> The follow-on is still real, and now bigger than described: since an ordinal
> binds at all three sort sites and names an output position rather than a source
> column, it gives a total order in exactly the four shapes `totalOrderKeys`
> currently excludes. Teaching the generator to emit ordinal ORDER BY would
> widen top-n coverage to `DISTINCT` / window / aggregate / `UNION` queries,
> not merely add a second spelling of what it already generates.

## What landed

Shipped 2026-09-06 in one commit with the docs. `LogicalBuilder` gained
`selectListOrdinal(clause, e, size)` — the shared bare-integer test and bounds
check — with `resolveGroupOrdinal` and the new `resolveOrderOrdinal` layered on
it, so GROUP BY's error strings and its "position cannot refer to aggregate"
guard are byte-identical to before. All three sort sites now resolve ordinals:

- **Site 1 (below `Project`)** expands the ordinal to the SELECT-list expression
  and re-binds through `exprBinder`, which is what makes `ORDER BY 2` over a
  `GROUP BY` land on the aggregate output and over a window land on the window
  column — one change covering plain, aggregated and windowed selects, as the
  plan predicted.
- **Sites 2 and 3 (above `Distinct`, above `Union`)** share a new `bindOrderKey`
  helper: their scope IS the sort's output schema, so an ordinal becomes a
  `ColRefExpr` straight into it and anything else binds as before. That helper
  removed the duplicated `Sources`-wrapping both sites had.

An ORDER BY position may point at an aggregate; a GROUP BY position still may
not. Out-of-range and zero now raise `ORDER BY position N is not in select list
(size M)` instead of silently sorting by a constant.

13 regressions in `SqlEngineTest` (all 13 fail on the parent commit — verified
before the fix, returning scan order rather than throwing). Beyond the four the
plan asked for: ordinal mixed with a named key and per-key ASC/DESC, a GROUP BY
ordinal pointing at the group key rather than the aggregate, DISTINCT over a
computed column, UNION ALL, a window column, a window nested in arithmetic, the
zero position, `+9` staying a constant, and the alias case — `SELECT a + b AS s
FROM t ORDER BY s` still fails with `Unknown column`, while `ORDER BY 1` sorts,
which is the user-visible capability this adds and is now pinned by test.

Gates: `bazel test //...` 46/46 green; jaffle_shop deploy jar exits 0 with 15/15
Succeeded. Docs updated in the same commit — `README.md` (SQL surface now
documents ordinals for both clauses), `docs/gotchas.md` (the entry split in two:
the scope rule stands, the ordinal no-op moved to fixed), `docs/testing.md`
(sql_engine_test row), `docs/code-map.md` (LogicalBuilder ~960 → ~1005 LOC).

## Launch prompt

```
Read plans/followups/01-order-by-ordinal.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... must pass, jaffle_shop
deploy jar must hit 15/15 Succeeded. Land docs in the same PR.

Confirm the two "Decisions to confirm" bullets before writing code — they're
small but change what counts as a valid ordinal. Then fix both binding sites
in LogicalBuilder.scala per the plan's "Proposed fix" section, add the
SqlEngineTest cases listed under "Files to touch", and update the matching
docs/gotchas.md entry.

Stop and ask before: changing GROUP BY's existing ordinal behavior (it's
correct today — this plan only touches ORDER BY), or touching anything
outside LogicalBuilder.scala and its tests.
```
