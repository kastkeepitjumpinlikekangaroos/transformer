# Plan 21: floats have two equalities, and a hash join uses the other one

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · decided by the user:
> Postgres's and Spark's, NaN = NaN and -0.0 = 0.0 everywhere · Effort: 1-2
> days · Risk: medium (reverses plans/followups/03 and /06)

## How this was found

`Ops`' comment says "GROUP BY, DISTINCT and join keys (`KeyCodec`) agree with
`eq` on all of these but floats, which they key by bit pattern". The audit
probed what that disagreement does at `b2c83fc`, over `fl(k DOUBLE, v)` =
(NaN,1) (NaN,2) (-0.0,3) (0.0,4) (1.0,5) (NULL,6) (NaN with another payload,7)
(-NaN,8) and `fr(k, w)` = (NaN,10) (0.0,20) (-0.0,30) (1.0,40) (NULL,50).

## Problem

| query | answer |
|---|---|
| `fl JOIN fr ON fl.k = fr.k` (hash join) | (1,10) (2,10) (3,30) (4,20) (5,40) (7,10) (8,10) |
| `fl, fr WHERE fl.k = fr.k` (nested loop, filter) | (3,20) (3,30) (4,20) (4,30) (5,40) |
| `GROUP BY k` | -0.0 and 0.0 two groups, the three NaNs one |
| `COUNT(DISTINCT k)`, `UNION`, `PARTITION BY k` | the same split |
| `k = k` / `k <= k` on NaN | FALSE / TRUE |
| `RANK() OVER (ORDER BY k)` | -0.0 rank 2, 0.0 rank 3 |

One predicate, two answers: the hash join matched four NaN pairs that `=` calls
FALSE and missed two -0.0 / 0.0 pairs it calls TRUE, so `ON a = b` depends on
the planner's choice of join. GROUP BY puts two values that are `=` in different
groups. `a <= b AND a >= b` is not `a = b`. Postgres, Spark and DuckDB have one
equality: NaN equals NaN and sorts above every number, and -0.0 equals 0.0.

## Decision

NaN = NaN is TRUE and -0.0 = 0.0 is TRUE, everywhere: `=`, `<>`, `IN`,
`NULLIF`, `BETWEEN`, ordering (NaN highest, -0.0 tied with 0.0), joins, GROUP
BY, DISTINCT, UNION, COUNT(DISTINCT), PARTITION BY, RANK / DENSE_RANK peers, MIN
/ MAX. A key normalizes -0.0 to 0.0 and every NaN to the canonical one. Which
of two tied zeros a `MIN` or `MAX` returns stays layout-independent (MIN gives
-0.0, MAX 0.0, as `Double.compare` orders them), since both are the same value.

## Fix

One comparison for a pair of floats, `Ops.floatCompare` (NaN equal to NaN and
above everything, `-0.0 == 0.0`, otherwise `<`), used by `Ops.eq`, `Ops.cmp`,
`VecOps.numericCompare`, `InListExpr`'s set key, the sort comparators
(`SortExec`, `SortKey`), and `WindowExec.sameKey` (through `Ops.cmp`); the key
codecs, `HashPartitioner` and the DISTINCT states hash a float through one
normalizing function. Parquet pushdown: a comparison on a float column already
pushes only when the file orders as the engine does (`pushesComparisons`);
re-check it under the new order (an `= 0.0` must keep a row group holding -0.0).

## Tests

Regressions for the table above (both join algorithms, spill on and off,
sharded). Two new metamorphic relations in the fuzz harness: an inner join `a
JOIN b ON p` returns the same multiset as `a CROSS JOIN b WHERE p` (small
relations, under the nested-loop guard), and no two rows of a `GROUP BY` result
have keys that are `=` (a self-join of the result on its keys returns exactly
one match per non-NULL group). Both must fail against `b2c83fc`. The IEEE
special-value guards stay; `ExprGen`'s value pools already carry the values.

## Docs

README (NaN and -0.0), gotchas (the followups/03 and /06 entries describe the
new rule), conventions (the one-comparison rule), testing.md.

## Outcome

Done as planned. `Ops.floatCompare` is the one float comparison behind
`Ops.eq`, `Ops.cmp` and `VecOps.numericCompare`; `KeyCodec.floatKeyBits` /
`doubleKeyBits` / `floatKeyValue` give the key form (`-0.0` as `0.0`;
`floatToIntBits` and `Double.equals` already treat every NaN as one), and the
packed codec, the object codec, the DISTINCT sets, shard routing and the spill
buckets all use it. `InListExpr`'s NaN-probe bypass is gone: NaN finds NaN.
`MinMaxState` keeps `Double.compare`, finer only in ordering `-0.0` below the
`0.0` it equals, so of two equal zeros its answer does not move with row
order. Parquet pushdown stops pushing a float comparison with a zero or NaN
literal (`ordersFloatsAlike`): a file may keep `-0.0` as a bound or leave NaN
out of its statistics.

`DataGen.generateKeyCase` and `oracle/KeyEquality` hold joins (hash, cross
join plus filter, left), GROUP BY, DISTINCT, COUNT(DISTINCT), UNION, PARTITION
BY and RANK to `Ops.eq` in every layout; at the code before it failed at seed
1 (`GROUP BY` made a NaN group that `=` called equal to nothing). Its default
budget is 40 seeds: each case runs eleven queries in seven layouts.
`window_exec_spill_test`'s signed-zero test now asserts the two zeros share a
rank, reversing plans/followups/06's half about them.
