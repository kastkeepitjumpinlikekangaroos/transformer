# Plan 03: `eval` vs `evalVec` disagree on NaN / -0.0

> Status: **done** 2026-09-06 · Effort: 2-3 days · Risk: medium
>
> Four claims in this file were wrong or incomplete; each is corrected in place
> below and the set is summarized in "Corrections found while implementing".

## Problem

The row-eval path (`Expr.eval`) and the vectorized path (`Expr.evalVec`) are
meant to be two implementations of the same semantics — `ExprBatchTest` exists
precisely to pin that parity. But numeric ordering (`< <= > >=`) and IN-list
membership both take genuinely different code in the two paths, and diverge
whenever a `Float`/`Double` operand is `NaN` or `-0.0`. Flagged as a candidate
follow-up in [docs/gotchas.md](../../docs/gotchas.md) ("`eval` and `evalVec`
disagree on `NaN` / `-0.0` ordering"). `ExprParityFuzzTest` deliberately
excludes float/double ordering and IN generation to stay green — this plan is
what would let that exclusion come off.

Narrow in practice (only affects float/double columns actually carrying NaN
or -0.0, e.g. via `x / 0.0`), which is why it's shipped unnoticed — but it
means the same `WHERE` predicate or `IN` list can produce different rows
depending on whether it happens to run through a vectorized operator
(`ProjectExec`/`FilterExec`) or a row-driven one (a join's residual predicate,
a comparator). This is a real, if narrow, contradiction — the same
compiled `Expr` tree gives a different boolean for the same input row
depending on which method the caller happened to invoke.

## Current behavior — two independent divergences, opposite polarity

### Ordering (`< <= > >=`)

- **Row path** (`Ops.cmp`, `Ops.scala:36-43`): `java.lang.Double.compare(x.doubleValue, y.doubleValue)` — a total order. `NaN` sorts above every other value; `-0.0 < 0.0`.
- **Vector path** (`VecOps.numericCompare`, `Ops.scala:637-689`): raw primitive comparison, e.g. `readDouble(l, i) < readDouble(r, i)` — IEEE semantics. `NaN` compares `false` against everything (including itself); `-0.0 == 0.0`.

This direction is forced one way: a sort comparator *must* be a total order
(IEEE comparison of `NaN` isn't one — using it as a `Comparator` would
violate the contract and can throw at runtime under some sort
implementations). The row path's `Double.compare`-based total order is
therefore the side that has to stay put; the fix is bringing the vector path
in line with it, not the reverse.

### IN-list membership

- **Row path** (`InListExpr.eval`, `Expr.scala:356-369`): calls `Ops.eq(v, item)` per item — raw `==` (IEEE): `NaN == NaN` is `false`, `-0.0 == 0.0` is `true`.
- **Vector path's literal-hoisting fast path** (`InListExpr.evalVec`, `Expr.scala:390-436`): boxes every literal and the probe value into `java.lang.Double` and does `HashSet<java.lang.Double>.contains`. `java.lang.Double.equals` is defined via `doubleToLongBits`, which is the *opposite* of IEEE on both points: `Double.valueOf(NaN).equals(Double.valueOf(NaN))` is `true` (all NaN encodings canonicalize to one bit pattern), and `Double.valueOf(-0.0).equals(Double.valueOf(0.0))` is `false` (different bit patterns).

Concretely: `x IN (NaN)` with `x = NaN` is `false` under `eval`, `true` under
`evalVec`. `x IN (0.0)` with `x = -0.0` is `true` under `eval`, `false` under
`evalVec`. This is the opposite polarity from the ordering case — here the
*row* path is the naive-IEEE one and the *vector* path is the
one doing (accidental, wrong-direction) special-casing via boxing.

Plain scalar `=` / `<>` do **not** diverge — both `Ops.eq` and `VecOps`'s
`=`/`<>` branch use the same raw primitive `==`/`!=`, so they already agree
(this is the "Equality (`= <>`) agrees... and is covered" note in
`docs/gotchas.md`). Only the ordering operators and the IN-list membership
check are affected — because IN is where a hash-based fast path was
introduced, and boxing incidentally imports `Double.equals`'s non-IEEE rules.

## Proposed fix

Adopt the row path's semantics as canonical for both (it's already forced
for ordering, and picking one direction for the whole file is simpler than
splitting the standard by operator).

**Ordering** — in `VecOps.numericCompare`'s float/double branch, replace the
raw operator with a total-order-preserving comparison:
```scala
case "<"  => java.lang.Double.compare(readDouble(l, i), readDouble(r, i)) < 0
```
(and the same shape for `<=`, `>`, `>=`). `java.lang.Double.compare` takes
primitive `double`s — no boxing, so this doesn't reintroduce the allocation
the vectorized path exists to avoid.

> **Corrected.** This asked whether `Float` needs its own fix. It does not, and
> the question is answerable from the code: all four ordering branches read
> through `readDouble` (`Ops.scala:436-442`), which widens `FloatVector`,
> `LongVector` and `IntVector` into `double` exactly as `Number.doubleValue`
> does on the row side. There is no separate `Float` comparison branch, so one
> change covers both types and `java.lang.Float.compare` is never needed. Both
> paths also widen `Long` to `double` and so lose the same precision on large
> longs — that is agreement, not a divergence, and is out of scope here.

**IN-list** — the `numericSet: java.util.HashSet[java.lang.Double]` fast path
(`Expr.scala:390-400`) can't represent "not equal to itself" for a hash
lookup, so don't try to make the set itself IEEE-correct for `NaN`. Instead:
- **Normalize `-0.0` to `0.0`** both when inserting literals into the set and
  when boxing the probe value (`if (v == 0.0) 0.0 else v` before
  `java.lang.Double.valueOf` — primitive `==` is already true for `+0.0`/
  `-0.0`, so this collapses them to one canonical key on both sides).
- **Short-circuit `NaN` at the probe**: if the probed value is `NaN`,
  treat it as "not found" unconditionally, without consulting the set —
  `Ops.eq(NaN, anything)` is always `false`, including `NaN` vs `NaN`, so
  whether the set happens to contain a `NaN` literal is irrelevant once the
  probe itself is known to be `NaN`.

> **Corrected — "not found" must not become "FALSE".** "Treat as not found
> unconditionally" reads like an early `values(r) = negated`, which would be
> wrong. `InListExpr.eval` returns `if (sawNull) null else negated`, so
> `NaN IN (1.0, NULL)` is NULL, not FALSE. The short-circuit has to leave
> `found = false` and fall through to the existing
> `else if (sawNullItem) nulls.set(r)` branch, not bypass it. Implemented that
> way, and pinned by `ExprBatchTest.inListNanProbeWithNullItemStaysThreeValued`
> — which fails against the old code with `eval=null vs vec=true`.

This keeps the `HashSet` fast path for the overwhelmingly common case
(finite, non-signed-zero values) and only adds two cheap branches.

## Files to touch

- `src/main/scala/com/transformer/sql/plan/Ops.scala` — `VecOps.numericCompare`'s ordering branch.
- `src/main/scala/com/transformer/sql/plan/Expr.scala` — `InListExpr.evalVec`'s `numericSet` construction and probe (~390-421).
- `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala` — add the parity cases this plan exists to unblock: `<`/`<=`/`>`/`>=` and `IN` over `Double`/`Float` columns carrying `NaN` and `-0.0`, comparing `eval` against `evalVec` row-by-row.
- `src/test/scala/com/transformer/fuzz/ExprGen.scala` and/or `ExprParityFuzzTest` — once parity holds, remove the float/double ordering + IN exclusion so the fuzzer actually guards this instead of dodging it (see `docs/gotchas.md`'s note on `ExprParityFuzzTest` deliberately not generating these shapes).
- `docs/gotchas.md` — move the note from "candidate follow-up" to fixed, and update the `ExprParityFuzzTest` exclusion description once it's removed.

> **Corrected — the fuzzer has THREE gates, not two, and the third is 140 lines
> from the two this list names.** The two above are *shape* gates:
> `ExprGen.genComparison` (ordering restricted to `Integrals`) and
> `ExprGen.genInList` (child type restricted to Int/Long/String/Boolean). The
> third is a *value* gate: `cleanDouble`/`cleanFloat` mapped every `NaN`, `Inf`
> and `-0.0` to plain `0.0`, and `genValue` routed both float types through
> them, so the corpus contained none of the values the shapes would break on.
> Removing only the shape gates would have generated float ordering and float
> `IN` over a corpus with no `NaN` and no `-0.0`, gone green, and tested
> nothing. All three are gone, and
> `ExprParityFuzzTest.generatorReachesIeeeSpecialValues` asserts both halves —
> the shapes reached AND the values reaching them.
>
> `ExprGen`'s class Scaladoc documented all three gates and went stale with
> them; it is rewritten. `sentinel` was checked and left alone: it fills the
> `[numRows, capacity)` tail that the read contract forbids touching, and
> `99999.0` is a more distinctive marker there than `NaN` would be.

## Decisions to confirm before implementing

- Confirm the row path (`Double.compare` total order for ordering, raw `==`
  for equality/IN) is genuinely the desired canonical semantics rather than
  switching the row path to IEEE for ordering — it isn't a free choice
  (breaks sort comparator correctness), but confirm no caller relies on the
  vector path's current IEEE ordering behavior before changing it (a grep
  for any test currently asserting the *current* buggy vector behavior,
  which would need updating rather than newly passing).

> **Settled.** Grepped `NaN` / `-0.0` / `isNaN` across `src/`: no test asserted
> the vector path's IEEE ordering or the `Double.equals` `IN` behaviour. The
> only pre-existing `NaN` handling is in the fuzz harness (`RowOracle`'s
> `NaN`-aware comparators, `MetaQueryGen`'s float exclusions) and
> `core/HashKeys.scala`'s packed-key exclusion — all unaffected. So every new
> case newly passes; nothing needed updating.
>
> For **ordering** the direction was forced, as the plan says. For **IN** it is
> a real choice, and no option matches Postgres: Postgres `float8` has
> `NaN = NaN` TRUE and `-0.0 = 0.0` TRUE, while `Ops.eq` gives false/true and
> `Double.equals` gives true/false. We took the plan's answer (row path
> canonical) because scalar `=` already uses `Ops.eq` and already agrees across
> both paths, so making `IN` match keeps `x IN (y)` equivalent to `x = y`. An
> internal contradiction is worse than a documented difference from another
> engine. The divergence is recorded in `docs/gotchas.md` and in the README's
> SQL-features section, not left to the commit message alone.

## Corrections found while implementing

Four claims in this file were wrong or incomplete; each is corrected inline
above:

1. The open question about whether `Float` needs its own comparison branch was
   already answered by the code (it does not — `readDouble` widens), and the
   `numericCompare` line range was off (`637-689`, not `~650-687`).
2. "Remove the float/double ordering + IN exclusion" named two gates where
   there are three; the third is the value generator, and removing only the
   first two would have left the fuzzer green and vacuous.
3. The `IN` `NaN` short-circuit as described would have broken three-valued
   logic (`NaN IN (1.0, NULL)` must be NULL, not FALSE).
4. The plan named two divergence sites; a third, unrelated one turned up next
   door. `MinMaxState` compares `Float`/`Double` with raw IEEE in `updateAt`,
   `updateBatchAt` **and** `merge`, which makes `MIN`/`MAX` over a
   `NaN`-carrying column depend on row order, partition fan-out and spill flush
   order. It is *not* an `eval`/`evalVec` parity bug (both paths use the same
   accumulator and agree), so it was deliberately left out of this commit and
   written up as [plan 05](05-minmax-nan-order-dependence.md).

## Launch prompt

```
Read plans/followups/03-eval-evalvec-nan-parity.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... must pass, jaffle_shop
deploy jar must hit 15/15 Succeeded. Land docs in the same PR.

Implement both fixes per "Proposed fix" — VecOps.numericCompare's ordering
branch and InListExpr.evalVec's numericSet handling. Add the ExprBatchTest
parity cases FIRST (they should fail against current code, confirming the
bug), then fix, then confirm they pass. Only after parity holds, remove the
float/double ordering + IN exclusion from the expr-parity fuzzer and run a
campaign-sized seed count to confirm it stays green before landing.

Stop and ask before: changing Ops.cmp / Ops.eq (the row path) — this plan
brings the vector path in line with the row path, not the other way around;
if you find a reason the row path itself needs to change, stop and confirm
that's actually the right direction first.
```
