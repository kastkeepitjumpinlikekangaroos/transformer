# Plan 02: Binder rejects grouping parentheses and `TRIM(x)`

> Status: not started · Effort: ~1 day · Risk: low

## Problem

Two related, independent parse failures, both flagged as a candidate
follow-up in [docs/gotchas.md](../../docs/gotchas.md) under "The SQL frontend
rejects grouping parentheses":

1. `SELECT (a + b) * c`, `WHERE (x = y)`, and any other expression-position
   grouping paren fail with `Unsupported expression: ParenthesedExpressionList`.
2. `TRIM(x)` fails with an unbound-node error even though `UPPER(x)` /
   `LOWER(x)` work fine, and `Funcs` already implements `TRIM` at runtime.

Both are hard parse-time errors (loud, not silent), so lower urgency than
[plan 01](01-order-by-ordinal.md)'s silent-wrong-answer bug, but they block
valid, unremarkable SQL.

## Current behavior

### Grouping parens

`LogicalBuilder.bindExprWithAggs` (`LogicalBuilder.scala:511-648`) has a case
for `Parenthesis` already:
```scala
case p: Parenthesis => b(p.getExpression)
```
But this JSqlParser version parses a grouping paren around a *binary*
expression — `(a - b)`, `(x = y)` — as a `ParenthesedExpressionList` (a
one-element list node), not as `Parenthesis`. There is no case for it, so it
falls through to the catch-all at line 646:
```scala
case other => throw new IllegalArgumentException(s"Unsupported expression: ${other.getClass.getSimpleName}: $other")
```
The engine's own generated SQL and `QueryGen`/`MetaQueryGen` both render
paren-free and rely on operator precedence specifically to avoid this
(`docs/gotchas.md`, `src/test/scala/com/transformer/fuzz/QueryGen.scala`).

Note the binder is not blind to `ParenthesedExpressionList` everywhere — the
`InExpression` case (`LogicalBuilder.scala:576-580`) already unwraps one via
`case el: ExpressionList[_] => el.asScala...toSeq`, because JSqlParser always
wraps an `IN (...)` right-hand side that way. That path is specific to `IN`
and doesn't help a bare grouping paren in an arbitrary expression position.

### `TRIM(x)`

JSqlParser parses `TRIM(...)` as its own `TrimFunction` AST node (to support
the full `TRIM([LEADING|TRAILING|BOTH] [remstr] FROM str)` grammar), not as a
generic `Function`. `bindExprWithAggs`'s `case f: Function =>` branch
(`LogicalBuilder.scala:632-639`) never sees it, so it falls through to the
same catch-all. Meanwhile `Funcs.applyVec`'s fast path already lists TRIM
among the functions it vectorizes (`Expr.scala:264-269`,
`FuncExpr.evalVec`'s doc comment: "COALESCE, LOWER/UPPER/TRIM/LENGTH/..."), so
the runtime side is done — only the JSqlParser-node-to-`FuncExpr` mapping is
missing.

## Proposed fix

### Grouping parens

Add a case to `bindExprWithAggs` alongside the existing `Parenthesis` one,
unwrapping a single-element `ParenthesedExpressionList`:
```scala
case pel: ParenthesedExpressionList[_] if pel.size() == 1 =>
  b(pel.get(0).asInstanceOf[Expression])
```
A multi-element `ParenthesedExpressionList` in a non-IN position isn't valid
SQL in any position this binder reaches (row-value constructors aren't
supported) — let it fall through to the existing catch-all rather than
special-casing it.

### `TRIM(x)`

Add a case for `TrimFunction`, extracting its trimmed expression and binding
to the same `FuncExpr("TRIM", ...)` shape `Funcs` already runs:
```scala
case tf: TrimFunction =>
  val inner = b(tf.getExpression)
  FuncExpr("TRIM", Seq(Analyzer.implicitCast(inner, DataType.StringType)), DataType.StringType)
```
Check `TrimFunction`'s actual getter name against the installed JSqlParser
version before writing this (`getExpression` vs some other accessor) and
check whether it exposes the trim-specification / FROM-remstr fields — if it
does, decide whether to support the full grammar or explicitly reject
anything but the simple `TRIM(x)` form with a clear error (matching what
`Funcs.apply("TRIM", ...)` actually implements — check its arity before
committing to scope).

## Decisions to confirm before implementing

- **TRIM scope**: does `Funcs.apply("TRIM", ...)` support only the simple
  single-argument whitespace trim, or also `LEADING`/`TRAILING`/`BOTH` +
  custom remstr? If only the former, the binder should throw a clear
  "TRIM(... FROM ...) not supported, use TRIM(x)" for the extended grammar
  rather than silently ignoring the specification — check `Funcs.scala`
  first.
- **Multi-element `ParenthesedExpressionList`** outside IN: confirm there's
  no legitimate SQL shape this binder should accept that produces one (row
  constructors, `(a, b) = (c, d)`, are out of scope per "No subqueries" /
  existing binder coverage) before deciding to leave it on the catch-all.

## Files to touch

- `src/main/scala/com/transformer/sql/plan/LogicalBuilder.scala` — two new
  cases in `bindExprWithAggs` (~511-648).
- `src/test/scala/com/transformer/sql/exec/SqlEngineTest.scala` — add cases:
  `SELECT (a + b) * c`, `WHERE (x = y)`, `SELECT TRIM(x)` (and the rejected
  extended-TRIM form, if scoped out).
- `docs/gotchas.md` — move both notes from "candidate follow-up" to fixed.

## Testing

Once both land, consider whether `QueryGen`/`MetaQueryGen` should stop
avoiding grouping parens in generated SQL — right now they render paren-free
specifically to dodge this gap (see the gotcha text and
`src/test/scala/com/transformer/fuzz/QueryGen.scala`). Widening the
generator is optional and separate from this fix; don't block this plan on
it, but note it in `docs/gotchas.md` / `docs/testing.md` as a natural
next step if left undone.

## Launch prompt

```
Read plans/followups/02-binder-grouping-parens-and-trim.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... must pass, jaffle_shop
deploy jar must hit 15/15 Succeeded. Land docs in the same PR.

Resolve the "Decisions to confirm" section first by reading Funcs.scala's
TRIM implementation and the installed JSqlParser's TrimFunction /
ParenthesedExpressionList API — then implement both binder cases per
"Proposed fix", add the SqlEngineTest cases, and update docs/gotchas.md.

Stop and ask before: implementing the full TRIM(... FROM ...) grammar (only
do this if Funcs.scala already supports it — otherwise reject it with a
clear error and say so), or touching anything outside LogicalBuilder.scala
and its tests.
```
