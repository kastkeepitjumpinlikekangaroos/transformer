# Plan 17: CASE, IF and COALESCE evaluate every branch on every row

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · Effort: 1 day ·
> Risk: medium (the vectorized path of every conditional) · Must land before
> [18](18-integer-overflow-wraps.md), which adds errors a guard has to stop

## How this was found

A probe of conditionals that guard an expression which fails on the rows the
guard excludes. Measured at `b2c83fc` over `n(s STRING)` holding `'x'`, `'y'`,
`'z'` and `' 5'`.

## Problem

`CaseExpr.evalVec` evaluates each branch's value over the whole batch and then
picks per row; its comment calls that acceptable "because every `Expr` is
side-effect-free". An expression can throw, so a guard does not guard:

| query | answer |
|---|---|
| `SELECT CASE WHEN s = 'q' THEN CAST(s AS INT) ELSE 0 END FROM n` | `Cannot cast 'x' to int` (no row is `'q'`) |
| `SELECT CASE WHEN s <> 'x' AND s <> 'y' AND s <> 'z' THEN CAST(s AS DOUBLE) END FROM n` | `Cannot cast 'x' to double` |
| `SELECT IF(s = 'x', 0, CAST(s AS INT)) FROM n WHERE s = 'x'` | `Cannot cast 'x' to int` |
| `SELECT COALESCE(1, CAST(s AS INT)) FROM n` | `Cannot cast 'x' to int` |
| `SELECT CASE WHEN dec < 100 THEN dec * dec * ... END FROM n` | `Decimal overflow` from the rows `dec < 100` excludes |

The row path (`CaseExpr.eval`) evaluates only the chosen branch, so the two paths
disagree: this is an `eval` / `evalVec` parity defect that
`expr_parity_fuzz_test` never drew, because `ExprGen` builds no subexpression
that fails on some rows and not others. SQL defines `CASE` to evaluate the
result it chooses; Postgres documents CASE as the way to guard an expression.
After [18](18-integer-overflow-wraps.md), `CASE WHEN x < 1000 THEN x * x END`
would fail the same way.

`AND` and `OR` evaluate both sides on the vector path too (`WHERE s = 'x' AND
CAST(s AS INT) > 0` fails), while the row path stops at a deciding left side.

## Fix

Each conditional evaluates a branch only on the rows that branch decides:

- `CASE`: each `WHEN` on the rows no earlier `WHEN` claimed, each `THEN` on the
  rows its `WHEN` claimed, `ELSE` on the rest, through a sub-batch of those rows
  (`ColumnarBatch.select`) scattered back. A branch that claims every remaining
  row runs on the batch as it is, so the common case copies nothing.
- `IF(c, a, b)` and `NULLIF`'s comparison: the same, as a two-branch CASE.
- `COALESCE`: each argument on the rows every earlier argument left NULL.
- `AND` / `OR`: the right side on the rows the left does not decide, so both
  paths agree; the docs still name CASE as the guarantee, since the optimizer
  may move a WHERE conjunct.

## Tests

`ExprBatchTest` regressions for each shape above (row path vs vector path,
plus SQL in `SqlEngineTest`). `ExprGen` draws a guarded shape on purpose:
a CAST of text that is not a number, or decimal arithmetic that overflows, under
a `CASE` / `IF` / `COALESCE` / `AND` whose guard excludes exactly the failing
rows; `ExprParity` already requires both paths to agree or throw alike, and a
coverage guard asserts the shape and its failing rows are drawn.

## Docs

README (a conditional evaluates only what it returns), gotchas, testing.md,
architecture §5a (the vectorized conditionals).

## Outcome

Done, with one change of design: instead of evaluating a branch on a
sub-batch of its rows, `Operand.evalVec(e, batch, needed)` evaluates the
operand over the whole batch as before and, only when that throws, again row
by row on the rows `needed` accepts. The fast path every batch takes copies
nothing, and a batch with a guarded failure pays one exception and a row loop.
`CASE`, `IF`, `COALESCE`, `AND` / `OR` and a non-literal `IN` use it; `IF` and
`COALESCE` were eager on the row path too (`FuncExpr.eval` evaluated every
argument), so `IF(s = 'x', 0, CAST(s AS INT))` failed on both paths.

`ExprGen` adds a guard pair to 30% of batches (text an INT reads on some rows,
and a flag FALSE on every row it does not) and makes the tree's root a guarded
shape on 60% of those, drawn from its own random stream so every other seed
draws the tree it drew before. The coverage tallies evaluate subtrees outside
their conditionals and now read a failing row as NULL. `ExprParity.checkTotal`
requires a generated tree never to throw; `ExprParity.check` alone could not
see `IF` and `COALESCE`, which threw alike on both paths. Against the code
before: four of the `ExprBatchTest` regressions, the SQL regression, and the
property at seed 21 failed.
