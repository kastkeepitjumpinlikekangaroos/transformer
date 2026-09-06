# Plan 02: Binder rejects grouping parentheses and `TRIM(x)`

> Status: **done** 2026-09-06 · Effort: ~1 day · Risk: low
>
> Three claims in this file were wrong or incomplete and are corrected in
> place; see ["What landed"](#what-landed) for the list and the evidence.

## Problem

Two related, independent parse failures, both flagged as a candidate
follow-up in [docs/gotchas.md](../../docs/gotchas.md) — the entry now reads
"The SQL frontend used to reject grouping parentheses and `TRIM(x)` — fixed":

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
**That case is dead** (correction 2 below): `Parenthesis` is deprecated
for-removal in JSqlParser 5.0 and *nothing in the jar constructs it* — grepping
the unpacked jar for `net/sf/jsqlparser/expression/Parenthesis` matches only
`Parenthesis.class` itself, and `CCJSqlParser`'s bytecode references it zero
times. There is no parenthesis node in the 5.0 AST at all. Every grouping paren
— around a binary expression or a bare column, it makes no difference — arrives
as a one-element `ParenthesedExpressionList`. With no case for it, it falls
through to the catch-all at line 646:
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

Unwrapping a single-element `ParenthesedExpressionList` in `bindExprWithAggs`
is necessary but **not sufficient** (correction 3 below). A grouping paren has
to be transparent at every walk over the AST, and there are five:

| Walk | Site | Symptom if it doesn't strip parens |
|---|---|---|
| bind | `bindExprWithAggs` | `Unsupported expression: ParenthesedExpressionList` |
| children | `childExpressions` | `(SUM(x))` → "Aggregate 'SUM' used in non-aggregating position"; `(ROW_NUMBER() OVER ...)` → "Window function used in non-window position" |
| post-agg rebind | `buildAggregation`'s `rebind` | `SELECT (a + b) ... GROUP BY a + b` → "Unknown column 'a'. Available: [a + b]" |
| naming | `deriveAlias` | `SELECT (a)` names its column `(a)` instead of `a` |
| ordinals | `selectListOrdinal` | `ORDER BY (1)` silently becomes a constant sort key |

One shared extractor is cleaner than five copies of the same guard:
```scala
private object Grouped {
  def unapply(e: Expression): Option[Expression] = e match {
    case pel: ParenthesedExpressionList[_] if pel.size() == 1 =>
      Some(pel.get(0).asInstanceOf[Expression])
    case _ => None
  }
}
```
A multi-element `ParenthesedExpressionList` in a non-IN position isn't valid
SQL in any position this binder reaches (row-value constructors aren't
supported) — let it fall through to the existing catch-all rather than
special-casing it. Note the `IN` case matches `in.getRightExpression` directly
rather than through `b()`, so the size-1 guard can't swallow `x IN (1)`.

### `TRIM(x)`

Add a case for `TrimFunction`. **Do not bind `tf.getExpression`** (correction 1
below): for every extended form that accessor holds the *remstr*, not the
string being trimmed.

| SQL | `getTrimSpecification` | `isUsingFromKeyword` | `getExpression` | `getFromExpression` |
|---|---|---|---|---|
| `TRIM(x)` | null | false | `x` | null |
| `TRIM(BOTH ' ' FROM x)` | `BOTH` | true | `' '` | `x` |
| `TRIM(LEADING FROM x)` | `LEADING` | true | **null** | `x` |
| `TRIM(' ' FROM x)` | null | true | `' '` | `x` |
| `TRIM(BOTH x)` | `BOTH` | **false** | `x` | null |
| `TRIM(x, 'a')` | null | **false** | `x` | `'a'` |

`Funcs` supports single-argument TRIM only — `Funcs.scala:15` (`returnType`) and
`Funcs.scala:231` (`applyVec`) both gate on `argTypes.size == 1` — so the scope
question below is settled: accept the simple form, reject the rest with a clear
error. The guard has to test all four fields, not just the FROM keyword: the
last two rows above set neither the specification-plus-FROM pair that the
obvious guard would look for.

```scala
case tf: TrimFunction =>
  if (tf.getTrimSpecification != null || tf.isUsingFromKeyword ||
      tf.getFromExpression != null || tf.getExpression == null)
    throw new IllegalArgumentException(
      s"Only the single-argument TRIM(x) is supported, got: $tf")
  FuncExpr("TRIM", Seq(b(tf.getExpression)), DataType.StringType)
```
No `Analyzer.implicitCast` on the argument: `VecFuncs.trim` and `Funcs.apply`
both go through `.toString`, exactly like `UPPER`/`LOWER`, whose binding path
inserts no cast either. Binding TRIM as if JSqlParser had given it a plain
`Function` node is the whole goal.

`childExpressions` needs a `TrimFunction` case too, or `TRIM(MAX(name))` never
collects its aggregate.

## Decisions to confirm before implementing

Both resolved — kept for the reasoning, not as open questions.

- **TRIM scope**: `Funcs` implements only the single-argument whitespace trim
  (both gates cited above). The binder rejects the extended grammar with a
  clear error rather than silently ignoring the specification.
- **Multi-element `ParenthesedExpressionList`** outside IN: the only shape that
  produces one is a row-value constructor (`(a, b) = (c, d)`, `SELECT (a, b)`),
  which is out of scope. Leaving it on the catch-all is right — it reports
  `Unsupported expression: ParenthesedExpressionList: (a, b)`.

## Files to touch

- `src/main/scala/com/transformer/sql/plan/LogicalBuilder.scala` — the
  `Grouped` extractor plus a case at each of the five walks above, and the
  `TrimFunction` cases in `bindExprWithAggs` + `childExpressions`.
- `src/test/scala/com/transformer/sql/exec/SqlEngineTest.scala` — one case per
  walk (see the table), plus `SELECT (a + b) * c`, `WHERE (x = y)`,
  `SELECT TRIM(x)`, and the rejected extended-TRIM forms.
- `docs/gotchas.md` — move both notes from "candidate follow-up" to fixed, and
  revise the earlier `ParenthesedExpressionList` entry (`:21`) plus the
  `ORDER BY (1)` sentence in plan 01's entry, both of which go stale.

## Testing

Once both land, consider whether `QueryGen`/`MetaQueryGen` should stop
avoiding grouping parens in generated SQL — right now they render paren-free
specifically to dodge this gap (see the gotcha text and
`src/test/scala/com/transformer/fuzz/QueryGen.scala`). Widening the
generator is optional and separate from this fix; don't block this plan on
it, but note it in `docs/gotchas.md` / `docs/testing.md` as a natural
next step if left undone. *(Left undone; noted in both docs as a widening
opportunity rather than a constraint.)*

## What landed

A single `Grouped` extractor in `LogicalBuilder`, matched at all five walks in
the table above, plus a `TrimFunction` case in `bindExprWithAggs` and in
`childExpressions`. `SELECT (a + b) * c`, `WHERE (x = y)`, `ON (a = b)`,
`HAVING (COUNT(*) > 1)` and `TRIM(name)` all bind; `SELECT (a)` names its
column `a`; `ORDER BY (1)` is position 1; row-value constructors and the
extended TRIM grammar are still rejected, by name and with a clear message
respectively.

15 regressions in `SqlEngineTest`. Gates: `bazel test //...` 46/46 green;
jaffle_shop deploy jar exits 0 with 15/15 Succeeded. Docs updated in the same
commit — `README.md` (parens + the TRIM restriction in the SQL surface),
`docs/gotchas.md` (both entries; the `:21` node-shape entry and plan 01's
`ORDER BY (1)` sentence revised), `docs/testing.md` (sql_engine_test row +
both generator entries), `docs/extending.md` (a recipe for adding an
expression node, since the two-edit requirement is the trap here),
`docs/architecture.md` (§6 on the shared `childExpressions` traversal),
`docs/code-map.md` (LogicalBuilder ~1005 → ~1040 LOC).

### Three corrections to this plan file

1. **The `TrimFunction` snippet was a silent-wrong-answer bug.** It bound
   `tf.getExpression`, which is the remstr in every extended form. Measured
   against `'xxaxx'` with the snippet as written: `TRIM(BOTH 'x' FROM name)`
   returned `x` (it trimmed the literal `'x'`, not the column);
   `TRIM(LEADING FROM name)` threw a `NullPointerException`; `TRIM(name, 'x')`
   returned `xxaxx`, silently dropping the remstr. That last form is not in the
   plan's original framing at all — it sets neither `getTrimSpecification` nor
   `isUsingFromKeyword`, so a guard written only against "the FROM grammar"
   lets it through. Corrected above, with the full accessor table.
2. **The `Parenthesis` case the plan cites as existing coverage is dead
   code.** Nothing in jsqlparser-5.0 constructs a `Parenthesis`; the class
   ships deprecated-for-removal and is referenced by no other class in the jar.
   The three `case p: Parenthesis` branches in `LogicalBuilder` (binder,
   `childExpressions`, and the entirely-uncalled `rebuildExpr`) were removed
   rather than left sitting beside the live `Grouped` cases. `rebuildExpr` went
   with them: it had no callers, and stripping its only distinguishing case
   would have left a three-argument wrapper around `bindExpr`.
3. **The plan names one traversal; there are five.** `childExpressions` is a
   second walk over the same AST with a silent `case _ => Nil` catch-all, and
   it drives `containsAggregate`, the aggregate `collect`, and
   `collectWindows`. Verified by reverting just that case with everything else
   in place: `(SUM(x))`, `(a + SUM(b))`, `(ROW_NUMBER() OVER ...)` and
   `TRIM(MAX(name))` all fail with "used in non-aggregating position" /
   "used in non-window position" — the aggregate is never collected, so the
   resolver can't find it. `buildAggregation`'s `rebind` is a third (reverting
   it alone fails `SELECT (a + b) ... GROUP BY a + b` with "Unknown column
   'a'. Available: [a + b]"), and `deriveAlias` / `selectListOrdinal` are the
   fourth and fifth. The last one matters most: leaving `ORDER BY (1)` opaque
   would have turned today's loud parse error into a constant sort key that
   silently doesn't sort — reintroducing, in a new spelling, exactly the bug
   [plan 01](01-order-by-ordinal.md) had just removed. Postgres and MySQL both
   drop a redundant paren before the positional check, so position 1 is also
   the standard answer.

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
