# Plan 19: ROUND, TRUNC, FLOOR and CEIL compute in DOUBLE

> Status: planned · found by the 2026-10-31 audit · decided by the user: exact
> for DECIMAL and the integers, and ROUND rounds half away from zero for every
> type · Effort: 1 day · Risk: medium (changes the type of these functions over
> DECIMAL and the integers, and ROUND(-2.5)) · Depends on
> [18](18-integer-overflow-wraps.md)

## How this was found

`docs/gotchas.md` records that a DECIMAL "is exact in arithmetic, SUM and AVG,
but not in the math functions". The audit measured what that costs at
`b2c83fc`.

## Problem

| query | answer | exact |
|---|---|---|
| `ROUND(p, 2)`, p DECIMAL(10,3) = 1.005 | `1.0` (DOUBLE) | 1.01 |
| `FLOOR(big)`, big DECIMAL(38,0) = 90071992547409930 | `9.007199254740994E16` | 90071992547409930 |
| `ROUND(b)`, `FLOOR(b)`, `CEIL(b)`, `TRUNC(b)`, b BIGINT = 9007199254740993 | `9.007199254740992E15` each | 9007199254740993 |
| `ROUND(-2.5)` | `-2` (half toward +infinity) | -3 in Postgres numeric, Spark, DuckDB, SQL Server |

Each function takes the argument's `doubleValue`, so a decimal rounds the binary
fraction nearest it (1.005 is 1.00499999999999989...) and an integer past 2^53
loses digits. A price rounded to cents is wrong on some rows, silently.

## Decision

- Over a DECIMAL(p,s), each is exact and returns a DECIMAL: `ROUND(x, d)` and
  `TRUNC(x, d)` with a constant `d` keep `min(s, max(d, 0))` places (ROUND one
  more integer digit for the carry), `FLOOR` and `CEIL` scale 0 with one more
  integer digit; a non-constant `d` keeps the type `DECIMAL(p+1, s)`.
- Over an INT or BIGINT, each returns that type exactly: `FLOOR`, `CEIL`,
  `TRUNC` and `ROUND` with no scale or one >= 0 return the value, and a negative
  scale rounds to tens, hundreds, ...; a result that does not fit is the integer
  overflow error of plan 18.
- Over FLOAT and DOUBLE they stay DOUBLE.
- `ROUND` rounds half away from zero for every type: `ROUND(2.5)` is 3 and
  `ROUND(-2.5)` is -3 (today -2). Users who relied on the old rule get a
  different answer on exact halves below zero.

## Fix

`Funcs.signature` gains the result types; `Funcs.apply` / `VecFuncs` compute
with `BigDecimal.setScale(_, HALF_UP / DOWN / FLOOR / CEILING)` for decimals,
integer arithmetic for integers, and `Math.floor(|x| + 0.5) * signum` style
half-away rounding for doubles (keeping plan 11's edge rules: a value whole at
the scale comes back as it is, NaN and the infinities are their own answer).

## Tests

`ExprBatchTest` parity on every input type and both paths; `SqlEngineTest`
regressions for the table above; `ExprParity`'s corpus already draws these
functions over decimals (the result type changes) and a harness check compares
a decimal ROUND / TRUNC / FLOOR / CEIL with `BigDecimal` computed by the oracle.

## Docs

README (numeric functions, decimals), gotchas ("a DECIMAL is exact ... but not
in the math functions" narrows to SQRT, POWER, LN and the rest), testing.md.
