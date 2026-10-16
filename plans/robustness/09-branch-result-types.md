# Plan 09: CASE, COALESCE, IF, GREATEST and UNION take their first branch's type

> Status: **done** 2026-10-16 (see [Outcome](#outcome)) · Effort: 1-2 days ·
> Risk: medium (it changes the declared type, and for `CASE` and `UNION` the
> values, of queries that run today, so the numeric half needed the user's
> decision first; see [Decisions](#decisions)).

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

## Outcome

The user's decisions, 2026-10-16, each the recommended option:

- **One strict rule.** Values that become one result meet as a comparison's
  operands do: numbers widen (`THEN 1 ELSE 2.5` is the `DOUBLE` 2.5), a string
  literal is read as the others' type at bind time, and every other mix is a
  bind error naming the CAST: text with a number (`COALESCE(s, 1)`,
  `THEN 'yes' ELSE 0`), a `DATE` with a `TIMESTAMP`, a `BOOLEAN` with a number,
  a `UNION` of an `INT` arm and a `STRING` arm.
- **A `DECIMAL` beside a `FLOAT` or `DOUBLE` that is not written in the query
  is a bind error**, as in arithmetic, naming the CAST of the `DECIMAL` to
  `DOUBLE`. A number written in the query, or a constant of them, is the
  decimal it is, so `COALESCE(price, 0)` keeps `price`'s type.
- **Two more constructs fixed in the same change**, both found while reading
  for this plan: `LAG` and `LEAD` kept their default uncast (`LAG(i, 1, 2.5)`
  was the `INT` 2, a decimal default with more digits sat under the column's
  type, a default that was no date failed mid-run), and `NULLIF` compared its
  operands un-unified (`NULLIF(i, '2')` and `NULLIF(d, '2026-01-01')` never
  matched). The default now meets its value as a branch does; `NULLIF`'s two
  meet as a comparison's and the answer is the first as it is.

What landed. `Analyzer.branches` is the rule, sharing `meet` with `unify`; for
a `DECIMAL` the type must hold each value, since the values are kept: two meet
as `Decimals.holding` (the longer integer part at the larger scale, given way
past 38 digits as `Decimals.resultType` does), an integer as the `DECIMAL` its
every value fits. `LogicalBuilder.bindBranches` binds the values of a `CASE`,
`COALESCE`, `IF`, `GREATEST`, `LEAST` and a `LAG`/`LEAD` default, rebinding a
constant beside a `DECIMAL` through `decimalConstant`, and
`LogicalBuilder.unifyArms` types each column across a `UNION`'s arms, retyping
an arm's SELECT list in place (so a literal there is read as the type it
meets) or wrapping any other arm in a casting projection. `Funcs.returnType`
reads the unified type instead of `widestNonNull`.

Two decimal pieces the plan did not name were needed for "every branch value
then fits the declared type" to hold. A CAST between two `DECIMAL`s now makes a
value of the target (`Decimals.fit`: rounded half up, an error past its
digits): it kept the value as it was, which is how the first-branch type held
`1.5625` in a `DECIMAL(9,2)`. That would have rounded comparisons between two
decimal types, which cast the second to the first's type, so a comparison of
two decimals now reads both as `Decimals.wider`, the holding type with no bound
on its digits, which never rounds. And an integer cast to a `DECIMAL` is put at
the target's scale (`2` in a `DECIMAL(12,2)` is `2.00`), as every other value of
the column is. The binder now casts the arguments of `COALESCE` and `IF` too,
which it never did: `IF(p, TRUE, 0)` and `COALESCE(d, ts)` failed mid-drain
setting the first type's vector, as a `UNION`'s other arms' values rode
uncast under its first arm's types.

Regressions, each failing at the base commit (checked in a worktree there with
the new tests copied in): the plan's table, the strict rejections, a `UNION`'s
column types across arms, `LAG`/`LEAD` defaults, `NULLIF`, past 38 digits
(rounding, an overflow, and exact comparisons) and a spill of each result shape
in `sql_engine_test`; the CAST between decimals on both paths in
`expr_batch_test`; and a job writing `COALESCE(k, w * w)` to parquet in
`data_job_test`, which failed at the base with this plan's message,
`Cannot write 6.25050001 to parquet column 'v', a decimal(9,2), without
rounding it`.

The fuzzers. The `inBranch` gate is gone from `QueryGen.genValue` and
`MetaQueryGen.genValue`, but removing it alone re-enabled a shape the corpora
barely drew: decimal arithmetic beside another `DECIMAL` type in a branch list
on about 4 seeds in 1000, answering with more fractional digits than its first
value's type on about 1, and almost never where a spill writes the value. A
`COALESCE(c, c * x)` cannot answer with the product at all, since it reaches it
only where `c`, and so the product, is NULL. Both generators now draw the shape
on purpose (`genDecimalBranches`, also as a `SUM`/`AVG` argument in
`QueryGen`): a product or a quotient beside the narrowest decimal column, half
the time a column by a number whose digits do not cancel under a condition
that takes it wherever the column has a value. A new guard,
`decimalArithmeticReachesBranches`, asserts the shape (103 of 6000 seeds) and
the value (17), read off the bound plan and by evaluating it. Run against the
base commit, the mode-differential property fails within its first 1300 seeds,
at seed 14621: `SELECT DISTINCT CASE WHEN FALSE THEN c1 ELSE c1 / 0.3333 END`,
whose spill could not write the quotient. The frontend fuzzer's
`MixedBranchTypes` known defect and the two numeric-branch known
disagreements of `SyntaxEquivalence` are deleted, their spellings now
ordinary equivalences. `ExprGen` built `CAST(decimal AS narrower DECIMAL)` as
if it could never throw; it now draws only sources whose integer part the
target holds, and sides of decimal arithmetic for the target an answer is cast
to, which halved how often an answer rounds, so its guard reads 40000 seeds.

Bind rejections of the SQL corpora's own queries: 0.11% of the
mode-differential corpus and 0.02% of the metamorphic one over 20000 seeds,
every one the decided refusal of a `DECIMAL` beside a construct of numbers
alone (`COALESCE(k, ABS(0.2))`), which the generators do not avoid.

`bazel test //...` 52/52, jaffle-shop 15/15, and green at 20000 seeds each:
the mode-differential, metamorphic and sharded-mode campaigns the plan asked
for, and the frontend campaign, whose oracles lost their plan-09 records; the
expression-parity campaign at 200000.

