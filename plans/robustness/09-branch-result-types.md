# Plan 09: CASE, COALESCE, IF, GREATEST and UNION take their first branch's type

> Status: **open** · Effort: 1-2 days · Risk: medium (it changes the declared
> type, and for `CASE` and `UNION` the values, of queries that run today, so the
> numeric half needs the user's decision first; see [Decisions](#decisions)).

## How this was found

Widening the SQL fuzz corpora to decimal arithmetic, 2026-10-04, once decimal
arithmetic was implemented (commits `8eccd9b`, `bc960f2`, `d6c88d0`). Decimal
arithmetic declares its own result type (`Decimals.resultType`), so a branch of
`COALESCE` holding `k * w` is a `DECIMAL(28,6)` beside a `k` that is a
`DECIMAL(9,2)`. The branch-typing rule below was already there; arithmetic is
what made two decimal types meet in one branch list, and the mode-differential
spill mode is what would have failed on it. `QueryGen.genValue` and
`MetaQueryGen.genValue` carry a named gate for it (`inBranch`: no decimal
arithmetic in a branch of `COALESCE`, `IF` or `CASE`); removing that gate is the
proof this plan landed.

The numeric half is older and was named to the user as a follow-up on
2026-10-03, while `Analyzer.unify` became the one typing rule for comparisons,
`IN`, `BETWEEN`, a `CASE` switch and arithmetic: the value branches of `CASE`,
`COALESCE` / `IF`, and `UNION` arms were left out of it.

## Problem

Each construct picks one branch's type and casts the others to it:

- `CASE` takes its first non-NULL `THEN` / `ELSE` type
  (`LogicalBuilder.bindExprWithAggs`, the `CaseExpression` case);
- `COALESCE`, `IF`, `GREATEST` and `LEAST` take `Funcs.widestNonNull`, which
  for two numbers is `DataType.widerNumeric`, and for two decimals that is
  whichever comes first;
- a `UNION` takes its first arm's column types.

A cast between two decimal types keeps the value as it is (`Casts.cast`: a
column's declared scale is not enforced on what it holds), so the result holds
values its declared type does not. Measured on `t(id INT, k DECIMAL(9,2),
w DECIMAL(18,4), i INT)` holding `(1, 1.25, 0.1234, 1)`, `(2, NULL, 2.5000, 2)`,
`(3, 0.10, NULL, 3)`, at `d6c88d0`:

| query | declared | holds |
|---|---|---|
| `CASE WHEN id = 3 THEN k ELSE k * w END` | `decimal(9,2)` | `0.154250` |
| `COALESCE(k, w * w)` | `decimal(9,2)` | `6.25000000` |
| `IF(id = 1, k, k * w)` / `IF(id = 1, k * w, k)` | `decimal(9,2)` / `decimal(28,6)` | the same values |
| `GREATEST(k, w * 100)` | `decimal(9,2)` | `250.0000` |
| `SELECT k ... UNION ALL SELECT k * k ...` | `decimal(9,2)` | `1.5625` |
| `CASE WHEN id = 1 THEN 1 ELSE 2.5 END` | `int` | `2` for `2.5` |
| `SELECT i ... UNION ALL SELECT 'x' ...` | `int` | the string `x` |

The decimal rows are not wrong in memory, since comparisons read the value, but
the parquet writer refuses a value its column's declared type does not hold
rather than round it: the `UNION` row, sorted with spill forced, fails with
`ArithmeticException: Cannot write 1.5625 to parquet column '_c0', a
decimal(9,2), without rounding it`, and so would writing any of them as a job's
output. The result type also depends on branch order, as the `IF` row shows.
The last two rows are wrong answers, the numeric half.

## Proposed fix

One type for the values that meet, as `Analyzer.unify` gives the operands of a
comparison: run the value branches of `CASE`, the arguments of `COALESCE`, `IF`,
`GREATEST` and `LEAST`, and each column across `UNION` arms through one rule.
For two decimals the rule needs one more step than `widerNumeric`: the decimal
that holds both, `max(p1 - s1, p2 - s2) + max(s1, s2)` digits at the larger
scale (Spark's `widerDecimalType`), past 38 digits giving way as
`Decimals.resultType` does. Every branch value then fits the declared type.

## Decisions

Ask the user before landing the numeric half:

- `CASE ... THEN 1 ELSE 2.5` would become a `DOUBLE` (2.5) where it is an `INT`
  (2) today, and a `UNION` of an `INT` and a `STRING` arm would become an error;
  both change queries that run today.
- Whether a `DECIMAL` branch beside a non-literal `DOUBLE` branch should be the
  bind error arithmetic makes it (`Analyzer.arithmetic`), or widen to `DECIMAL`
  as comparisons do.

## Verification

- Regressions first, each failing at HEAD: the table above, the `UNION` row
  with spill forced, and a job writing a `COALESCE` of two decimal types to
  parquet.
- Remove the `inBranch` gate from `QueryGen.genValue` and
  `MetaQueryGen.genValue`, then run the mode-differential, metamorphic and
  sharded-mode campaigns at 20000 seeds.

## Docs to update

`README.md` (the "values of different types meet" paragraph), `docs/gotchas.md`
(the typing entry, and the gate in the mode-differential entry),
`docs/testing.md` (`QueryGen`'s gate).
