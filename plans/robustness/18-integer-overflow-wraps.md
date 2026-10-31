# Plan 18: integer arithmetic, SUM and narrowing CASTs wrap silently

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · decided by the user: an
> integer result that does not fit its type is an error naming the value ·
> Effort: 1-2 days · Risk: medium (a new run-time error in the hottest loops) ·
> Depends on [17](17-case-evaluates-every-branch.md)

## How this was found

`plans/robustness/README.md` listed "numeric overflow wraps silently" as known
and undocumented since 2026-09-10. The audit probed every integer operator and
CAST at `b2c83fc`; no fuzz oracle can see a wrap, because every mode wraps alike.

## Problem

| query | answer | the value |
|---|---|---|
| `SELECT 2147483647 + 1` | `-2147483648` | 2147483648 |
| `SELECT 2147483647 * 2` | `-2` | 4294967294 |
| `SELECT i * i` (i = 100000) | `1410065408` | 10000000000 |
| `SELECT -i`, `ABS(i)`, `i / -1` (i = INT min) | `-2147483648` each | 2147483648 |
| `SELECT 9223372036854775807 + 1` | `-9223372036854775808` | 2^63 |
| `SELECT SUM(b)` over `Long.MaxValue`, 3e9, 2 | `-9223372033854775807` | 9223372039854775809 |
| `SUM(b) OVER ()` over the same | the same negative sum | |
| `CAST(b AS INT)` (b = 3000000000) | `-1294967296` | |
| `CAST(d AS INT)` (d = 1e20, Infinity, NaN) | `2147483647`, `2147483647`, `0` | |
| `CAST(dec AS INT)` (DECIMAL 99999999999999999999) | `1661992959` | |

`Ops.arith` and `VecOps.longArith` / `intArith` use plain `+ - *`; `LongSumState`
adds plain longs; `Casts.cast` narrows through `Number.intValue` / `longValue`,
which wrap a long and a decimal and saturate a double (NaN to 0).

## Decision

An INT or BIGINT result that does not fit is an error naming the operation and
the value, as DECIMAL overflow already is ([decimal decisions, 2026-10-04]):
`+ - *`, `/` (only INT min / -1 overflows), `%` (never), unary minus, `ABS`,
`SUM` (grouped, global and window, in update and merge), and every CAST to INT
or BIGINT from a wider integer, a float or a decimal, NaN and the infinities
included. A CAST from a float or a decimal still truncates toward zero
(`CAST(2.7 AS INT)` is 2, `CAST(-2.7 AS INT)` is -2), as Spark does, now
documented. `AVG` and the statistics compute in DOUBLE and do not overflow.
COUNT cannot.

## Fix

`Math.addExact` / `subtractExact` / `multiplyExact` / `negateExact` / `absExact`
(and the int forms) on the row path and in `VecOps`' loops; a checked narrowing
in `Casts` and `VecOps.cast`; `LongSumState` (and the window aggregate path,
which reuses it) adds exactly. The error is an `ArithmeticException` with the
message `integer overflow: 2147483647 + 1 does not fit int`, mirroring
`Decimal overflow: ...`.

## Tests

Regressions per operator and per CAST on both paths (`ExprBatchTest`), SQL in
`SqlEngineTest`, SUM under spill and across partitions
(`hash_aggregate_spill_test`), the window SUM. A property in
`expr_parity_fuzz_test`: integer arithmetic over edge values (0, +-1, the type's
min and max, values near sqrt(max)) evaluated in `BigInt` by the harness; the
engine returns exactly that value or fails with integer overflow, and fails only
when the exact value is out of range. A coverage guard asserts both halves are
drawn. `DataGen` draws edge integers into a share of columns so SUM overflow
reaches the mode-differential corpus (where both modes must fail alike).

## Docs

README (Integers, CAST), gotchas (removed from "known outstanding"),
plans/robustness/README, testing.md.

## Outcome

Done as planned, with one decision the plan did not foresee: **`SUM` checks the
true sum, not each partial sum.** A SUM built on `Math.addExact` would fail on
`Long.MaxValue, 1, -1` in that order and succeed on `Long.MaxValue, -1, 1`,
so whether a query fails would move with row order, partitioning and spill.
`LongSumState` keeps a 128-bit two's-complement running sum (two longs) and
decides once, at `finish`, whether it fits a BIGINT; its spill encoding carries
both words. The messages: `Integer overflow: 2147483647 + 1 does not fit INT`,
`... -(-2147483648) ...`, `... ABS(-2147483648) ...`, `Integer overflow: the SUM
9223372039854775809 does not fit BIGINT`, `Integer overflow: 3000000000 does not
fit INT`, and `Cannot cast NaN to INT: it has no integer value`, each an
`ArithmeticException`.

Two properties test meaning, through `oracle/ExactIntegers`: integer trees over
edge values against a `BigInt` reference that overflows at any node (both
paths), and SUM over edge values in every execution layout against exact sums.
Against the code before, both failed at seed 0. `ExprGen`'s own trees now throw
an `ArithmeticException` on 1.4% of seeds (its integer pools hold the limits),
which `ExprParity.checkTotal` allows: a generated tree may fail only where its
own values overflow. The mode-differential corpus keeps its small integer
domains: an overflow there would be filtered out of one TLP partition and not
another, so the relations would have to reason about errors.
