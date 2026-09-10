# Plan 05: `MIN`/`MAX` over a `NaN`-carrying float column is order-dependent

> Status: **done** 2026-09-09 · Effort: ~1 day · Risk: medium (a user-visible
> semantic change to an aggregate, not just a determinism fix)

## How this was found

Surfaced while implementing [plan 03](03-eval-evalvec-nan-parity.md) (the
`eval`/`evalVec` `NaN`/`-0.0` divergence). It is a *different* bug in the same
value domain and was deliberately kept out of that commit: plan 03 reconciles
two evaluation paths, and this one is a single path that is wrong on its own
terms. Read from the code at commit `3e51327`; the failure below is traced
from `MinMaxState`'s comparisons, not executed — no test in the repo reaches
it today, which is half the problem.

## Problem

`MinMaxState` (`src/main/scala/com/transformer/sql/exec/AggregateExec.scala`)
keeps a primitive `doubleCur` accumulator for `Float`/`Double` and advances it
with a raw IEEE comparison:

```scala
case dv: DoubleVector =>
  if (!dv.isNull(r)) {
    val v = dv.values(r)
    if (!hasValue) { doubleCur = v; hasValue = true }
    else if ((min && v < doubleCur) || (!min && v > doubleCur)) doubleCur = v
  }
```

Every comparison against `NaN` is false, in both directions, so the result
depends on where the `NaN` falls:

| input order | `MAX(x)` | why |
|---|---|---|
| `[NaN, 5.0]` | `NaN` | `NaN` seeds the accumulator; `5.0 > NaN` is false |
| `[5.0, NaN]` | `5.0` | `NaN > 5.0` is false, so the `NaN` is skipped |

The same shape appears in **three** places, which is what makes this more than
a row-order curiosity:

- `updateAt` (~`:1065-1076`) — the per-row path.
- `updateBatchAt` (~`:1127-1146`) — the vectorized path. It agrees with
  `updateAt`, so this is *not* an `eval`/`evalVec` parity bug.
- `merge` (~`:1211-1213`) — the partial/final merge. So the answer can change
  with **partition fan-out and spill flush order**, not only with row order
  inside one batch.

## Why the fuzzers miss it

Three independent reasons, all worth knowing before "just fixing" it:

1. `DataGen` never generates `NaN`. `DataGen.DoublePool` is ten finite,
   well-separated values, and `QueryGen` builds `Min`/`Max` over a bare
   `colRef` only (`QueryGen.scala:266-267`) — never over a computed expression
   — so not even a `0.0 / 0.0` can sneak in through the argument. The
   mode-differential and metamorphic fuzzers compare one `(data, query)` across
   partition/batch layouts, metrics on/off and spill, which is exactly the
   differential this bug fails; the corpus simply cannot produce the input.
   Widening the value domain is therefore sufficient — the aggregate-argument
   shape does not also need to change. **Correction:** widening `DataGen`'s
   domain is *not* sufficient, because there are two corpora, not one. See
   correction 1 below.
2. It is not a parity bug, so `ExprBatchTest` and `ExprParityFuzzTest` — the
   gates plan 03 built out — are structurally blind to it.
3. `RowOracle.multisetEquals` routes `Double`/`Float` columns through a
   `NaN`-aware tolerant comparator in which `NaN` agrees only with `NaN`. That
   is correct, and would report the divergence if the corpus produced one.

## Proposed fix

Route the `Float`/`Double` slots through `Ops.cmp` in all three sites, matching
what the class already does for reference types (`case _ => Ops.cmp(v,
currentBoxed)`) and what `GREATEST`/`LEAST` already do
(`Funcs.pickByOrdering`). That makes `MAX` agree with `ORDER BY x DESC LIMIT 1`
(`SortExec.rowOrdering` is `Ops.cmp`-based) and with Postgres, which sorts
`NaN` highest — so `MAX` of a column containing `NaN` is `NaN`, and `MIN` of
one is the smallest non-`NaN` value.

`Ops.cmp` boxes (`(a: Any, b: Any)`), and these are the hot accumulator loops
the primitive slots exist to keep allocation-free, so do **not** call it
directly in the inner loop. Use `java.lang.Double.compare(v, doubleCur)`, which
is the same total order on primitives with no boxing — the same substitution
plan 03 made in `VecOps.numericCompare`.

Check `MinMaxState.finish`'s `Float` narrowing (`doubleCur.toFloat`) still
round-trips `NaN` (it does) and that the spill SerDe path
(`writeSelf`/`readSelf`, which writes `doubleCur` as a raw `double`) is
unaffected (it is — `DataOutput.writeDouble` is bit-preserving).

## What "done" requires beyond the comparison change

A one-line comparison fix with no corpus change would be untested by
construction. Both halves are needed:

- **Widen `DataGen`** so float/double columns draw `NaN`, `±Infinity` and
  `-0.0` at a low rate — the same shape `ExprGen.genValue` grew in plan 03
  (see `SpecialFloatProb` and the `SpecialDoubles`/`SpecialFloats` pools there
  for the precedent, including why `+0.0` is left out of the pool). **And
  `MetaQueryGen.payloadDomain`**, which is a second, independent value pool —
  see correction 1.
- **Add a coverage guard** asserting the corpus actually reaches
  `MIN`/`MAX` over a column carrying `NaN`, in the style of
  `MetamorphicFuzzTest.aggDecompositionCoversEveryColumnType` and
  `generatorReachesIeeeSpecialValues`. Without it the widened corpus can drift
  back to all-finite and the property goes quietly vacuous — the exact failure
  mode `docs/testing.md` records for the Boolean `MIN`/`MAX` slot bug.
- The **aggregate-decomposition** relation (one-shot aggregation ≡
  group-then-re-aggregate) is the one that drives `merge`, so it is the
  relation that would have caught this. Confirm it reaches `NaN` inputs once
  `DataGen` produces them.

## Watch out for

- Widening `DataGen` touches every fuzzer that uses it (mode-differential,
  metamorphic, sharded, and the DAG-scheduler generator's inputs), not just the
  aggregate path. Expect to re-run all campaigns, and expect a `NaN` in a
  **GROUP BY key** to surface separately — `HashKeys` deliberately excludes
  `Float`/`Double` from packed keys over `NaN` canonicalization and `-0.0`
  (see `core/HashKeys.scala` and `docs/gotchas.md`), so check what the
  object-array codec does with a `NaN` key before assuming it is fine.
  **Correction:** only the mode-differential fuzzer consumes `DataGen.generate`,
  and `HashKeys` does not exclude `Float`/`Double` from packed keys. See
  corrections 1 and 2.
- `SUM`/`AVG`/`STDDEV` over `NaN` are not affected (`NaN` propagates through
  addition, which is order-independent), but they will start producing `NaN`
  results once the corpus carries one — verify `RowOracle`'s tolerant
  comparator handles that (it does: `NaN` agrees only with `NaN`).

## Files to touch

- `src/main/scala/com/transformer/sql/exec/AggregateExec.scala` —
  `MinMaxState.updateAt`, `updateBatchAt`, `merge`.
- `src/test/scala/com/transformer/fuzz/DataGen.scala` — float/double value
  domains (`SpecialDoubles`, `SpecialValueProb`, `withSpecialDoubles`).
- `src/test/scala/com/transformer/fuzz/MetaQueryGen.scala` — the second float
  value domain (`payloadDomain`), which calls `DataGen.withSpecialDoubles`.
- `src/test/scala/com/transformer/fuzz/oracle/AggDecomposition.scala` —
  `specialValueCoverage`, read by the metamorphic guard.
- `src/test/scala/com/transformer/fuzz/RowOracle.scala` — comment only: what the
  tolerant comparator can and cannot see now that the corpus carries specials.
- `src/test/scala/com/transformer/fuzz/MetamorphicFuzzTest.scala` and/or
  `ModeDifferentialFuzzTest.scala` — the coverage guard.
- `src/test/scala/com/transformer/sql/exec/HashAggregateSpillTest.scala` — a
  deterministic regression: the same rows in two orders, and across a
  partition split, must give the same `MIN`/`MAX`.
- `docs/gotchas.md` — the entry this plan is linked from moves to fixed.
- `README.md` — the `NaN` / `-0.0` paragraph under **SQL features** carries the
  `MIN`/`MAX` caveat; drop it once this lands.

## What landed

All five raw-IEEE comparison lines in `MinMaxState` — two in `updateAt`
(`DoubleVector`, `FloatVector`), two in `updateBatchAt`, one in `merge` — now
advance through `java.lang.Double.compare`. `finish()`'s `doubleCur.toFloat`
narrowing and the `writeDouble`/`readDouble` spill format both round-trip `NaN`
unchanged, as the plan predicted; neither needed a change, and the spill
regression proves it rather than asserting it.

**The semantic position, stated because it is user-visible.** `MAX` over a
column containing `NaN` is `NaN`, `MIN` over one is the smallest non-`NaN`, and
`MIN` over `0.0` and `-0.0` is `-0.0`. That is the plan's answer: it agrees with
`ORDER BY` (`SortExec.rowOrdering` is `Ops.cmp`-based), with `GREATEST`/`LEAST`
(`Funcs.pickByOrdering`), with the class's own reference-type branch, and with
Postgres. `README.md`'s "SQL features" section states it, `docs/gotchas.md`
explains why, and `docs/conventions.md` generalizes the rule to any state that
orders values.

**Coverage, measured before tuning anything.** The baseline said the binding
constraint was the value domain, not the query shape: `MIN`/`MAX` over a float
column already landed on 138 of 2000 seeds (6.9%), with zero of them carrying a
`NaN`. So the generator's shape weights were left alone and only the value pools
moved. After widening, over the same 2000 seeds: 138 shape (unchanged — the
split rng streams kept the query corpus bit-identical), of which 18 carry `NaN`,
18 a `-0.0` and 16 an infinity. On the metamorphic side the aggregated columns
carry `NaN` on 74 seeds, `-0.0` on 47 and an infinity on 42. Both guards assert
bounds ~3x below those rates.

**Campaigns.** `mode_differential_fuzz_campaign`, `metamorphic_fuzz_campaign`
and `sharded_mode_fuzz_campaign`, each at `FUZZ_SEEDS=20000`, all green over the
widened corpora. `dag_scheduler_fuzz_test` is unaffected — `JobDagGen` writes
integer CSV columns and touches neither value pool.

## Corrections found while implementing

Two claims in this file were wrong; both are corrected inline above.

1. **"Widening `DoublePool` is therefore sufficient" — no; there are two
   corpora.** `DataGen.generate` is called from exactly one place,
   `QueryGen.generate`, so it feeds only the mode-differential fuzzer.
   `MetaQueryGen` — which feeds the metamorphic AND sharded fuzzers — has its
   own `DoublePool` and `payloadDomain` (`MetaQueryGen.scala`) and constructs a
   `DataGen.Dataset` directly, never calling `DataGen.generate`. Widening
   `DataGen` alone would therefore have left the **aggregate-decomposition**
   relation — the one this plan itself names as "the relation that would have
   caught this", because it is the only one that drives `merge` — running over
   an all-finite corpus, and its new coverage guard would have failed rather
   than passed vacuously (which is the guard doing its job). The pool and the
   probability now live once, in `DataGen.withSpecialDoubles`, and
   `MetaQueryGen.payloadDomain` calls it. Same trap as plan 03's: the fix is
   only as good as the corpus, and there was one more gate than the plan
   counted.

2. **"`HashKeys` deliberately excludes `Float`/`Double` from packed keys" — it
   does not.** `KeyCodec.isPackable` **includes** `Float` and `Double`; the gate
   that excludes them is `KeyCodec.isLongFittable`, which guards the
   single-column `LongHashMap` primitive fast path, not packed keys. So a
   float/double GROUP BY, DISTINCT or equi-join key goes through
   `PackedBytesCodec`, which writes `Float.floatToIntBits` /
   `Double.doubleToLongBits`: every `NaN` payload canonicalizes to one bit
   pattern and `-0.0` stays distinct from `0.0`. `ObjectArrayKey` (a mixed key
   including a float column) and `CountDistinctState`'s `java.util.HashSet` both
   compare with `java.lang.Double.equals`, the same bit-level rule. So the
   codecs agree with each other, a `NaN` GROUP BY key groups with other `NaN`s,
   and the widened corpus did not need this to become its own plan — the
   20000-seed campaigns confirm it. It is recorded in
   `docs/architecture.md` §2a, including that this identity rule is deliberately
   NOT `Ops.eq`'s answer.

A third thing worth knowing, not a wrong claim: the plan is right that
`RowOracle.doublesWithinTolerance` is `NaN`-aware, but it treats `+0.0` and
`-0.0` as equal — it must, since reordering a `SUM` over signed zeros
legitimately flips the sign of a zero result. So the layout-differential
properties cannot see a signed-zero `MIN`/`MAX` divergence. That case is pinned
by `minMaxOverSignedZeroFollowsTheTotalOrder` instead, and the blind spot is
recorded in `RowOracle`'s own comment.

## Launch prompt

```
Read plans/followups/05-minmax-nan-order-dependence.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop deploy
jar exits 0 with 15/15 Succeeded. Land docs in the same commit.

Add the deterministic regression FIRST (same rows in two orders, and across a
partition split, must give the same MIN/MAX over a NaN-carrying double
column) and confirm it fails against current code. Then fix all three
comparison sites. Only then widen DataGen and add the coverage guard, and run
the mode-differential, metamorphic and sharded campaigns at a campaign-sized
seed count before landing — widening DataGen affects every fuzzer that uses
it, not just the aggregate path.

Verify every "this is how it works today" claim in the plan against the code
before relying on it; plan files in this repo have carried stale claims.

Stop and ask before: changing Ops.cmp itself, or changing what a NaN GROUP BY
key does (HashKeys) — that is a separate question from MIN/MAX.
```
