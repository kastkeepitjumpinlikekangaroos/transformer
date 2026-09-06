# Plan 01: `ORDER BY <ordinal>` silently doesn't sort

> Status: not started · Effort: ~1 day · Risk: low

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

Both sites call `obe.getExpression` straight into a binder with no ordinal
check, so a bare `LongValue` becomes `LitExpr(v, IntType)` via the normal
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
- **Non-integer / out-of-range ordinals**: match `GROUP BY`'s existing error
  message shape and bounds behavior rather than a bespoke one.

## Files to touch

- `src/main/scala/com/transformer/sql/plan/LogicalBuilder.scala` — the two
  ORDER BY binding sites (~315-320, ~327-337) plus the new/generalized
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

No fuzzer changes are required to land this — `MetaQueryGen.totalOrderKeys`
deliberately orders top-n queries by source columns rather than ordinals
specifically *because* ordinals didn't sort
(`src/test/scala/com/transformer/fuzz/MetaQueryGen.scala`). Once this lands,
teaching the generator to also emit ordinal ORDER BY as an equally-valid key
form is a natural (optional) follow-on for wider fuzz coverage, but the fix
itself only needs hand-written regressions in `SqlEngineTest`.

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
