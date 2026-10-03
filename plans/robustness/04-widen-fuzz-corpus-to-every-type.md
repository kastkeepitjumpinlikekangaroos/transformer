# Plan 04: the fuzz corpus covers six of the engine's ten types

> Status: **not started** · Effort: 2-3 days · Risk: low (test-only), but it will
> surface failures — do not start it until [plan 02](02-total-order-for-decimal-and-binary.md)
> has landed or you are prepared to park what it finds
>
> Plan 02 landed 2026-10-03, and it made the decision step 5 below defers to:
> **decimal equality is by value** (`compareTo`; `1.0 = 1.00`), byte arrays by
> content, and two integers as longs. A decimal GROUP BY / DISTINCT key and a
> decimal MIN / MAX read back at the column's declared scale, but rows that tie
> under `compareTo` can still differ in spelling (a LIMIT cutoff between `1.0`
> and `1.00`), so `RowOracle` should compare decimals with `compareTo`, which
> is what the engine means by equal. A BINARY is never
> read as text (CAST, LIKE, `||` and the string functions refuse one at bind
> time), so `ExprGen` and `QueryGen` must not build those shapes over one.

## How this was found

Reading the generators on 2026-09-10, looking for the *absence* of something
rather than a gate. `src/test/.../fuzz` carries no parked-bug exclusions any more
(the two that existed were removed with the fixes for bugs 03 and 06-07, and a
grep for "until the engine is fixed" / "excluded" / "gated" now turns up only
past-tense references). The gap is not a gate — it is a list that was never
widened.

```scala
// DataGen.scala:88
/** All supported column types — identical to [[ExprGen]]'s `GenTypes` so any
  * shape one can build the other can too. */
val ColumnTypes: Seq[DataType] = Seq(
  DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
  DataType.BooleanType, DataType.StringType)
```

`DataType` has ten concrete members. The four missing ones — `DateType`,
`TimestampType`, `BinaryType`, `DecimalType` — are not exotic: `CsvSchemaInferer`
**infers Date and Timestamp from plain CSV**, which is the repo's primary input
path, and parquet produces all four. No end-to-end SQL test puts any of them
through the engine either.

## Problem

Every oracle in the harness is type-parametric, so each one is currently running
at 60% of its reach:

- `ExprParity` — `eval` vs `evalVec` over four types' worth of `CastExpr`,
  `InListExpr`, `LikeExpr` and comparison branches that have never been
  differentially tested.
- `ModeDifferential` / `MetaModeDifferential` — the layout, spill and metrics
  differential never sees a Date sort key, a Binary group key or a Decimal
  aggregate.
- `AggDecomposition` — the relation that drives `MinMaxState.merge` and the spill
  serde. Its own coverage guard, `aggDecompositionCoversEveryColumnType`, asserts
  *every payload column type* reaches its `MIN`/`MAX` decomposition — and passes,
  because "every type" resolves to the six in the list. That is a coverage guard
  reporting full coverage of an incomplete universe, which is the most expensive
  kind of green.
- `Tlp` restricts its partition key to "type-reliable non-float output columns",
  so Date/Timestamp/Decimal/Binary would widen the partition-key pool too.

[Plan 02](02-total-order-for-decimal-and-binary.md) documents what is already
broken behind this hole (Binary `=` is reference equality, Binary `ORDER BY` is
identity-hash ordering, Decimal comparison is `doubleValue`-lossy, `GROUP BY`
disagrees with `=` for both). This plan is the reason those stay fixed.

## Proposed fix

Widen all four corpora. They are separate pools and widening one is not widening
the others — the lesson from plan 05's correction, where widening `DataGen` alone
left `MetaQueryGen.payloadDomain` on finite floats:

1. **`DataGen.ColumnTypes` + `genDomain`** — add the four types with narrow
   domains so keys still collide: a handful of dates around a DST boundary and a
   leap day; timestamps at microsecond and sub-millisecond offsets (and, once
   [plan 03](03-parquet-external-format-fidelity.md) lands, values that would
   differ under a unit mis-read); byte arrays including the **empty array**, two
   distinct objects with equal content, and pairs that differ only in the high bit
   of a byte (unsigned-vs-signed lexicographic order is the thing to catch);
   decimals including `1.0` / `1.00` (same value, different scale), a pair that
   collapses to one double, and a negative.
2. **`ExprGen.GenTypes`** — the comment promises the two lists are identical, so
   keep that true. Needs `genValue` and `sentinel` cases per type, and decisions
   about which operators are legal per type (no arithmetic on Date/Binary; see
   what [plan 01](01-reject-or-honor-every-clause.md) settles about decimal
   arithmetic before generating it).
3. **`MetaQueryGen`** — `keyDomain` and `payloadDomain` both. Date and Timestamp
   are good join keys (they are `isLongFittable`, so they reach the `LongHashMap`
   fast path that currently only Int/Long/Boolean exercise); Binary and Decimal
   are good payloads and reach `ObjectArrayCodec`.
4. **`QueryGen`** — `genLitValue` / `litSqlOfType` need per-type literal
   rendering, and this is where the generator meets the frontend: a Date literal
   has to be written in a form the binder accepts. Probing showed
   `WHERE d = '2024-01-01'` works, so a quoted ISO string is the shape to emit.
   There may be no writable literal form for Binary — if so, generate Binary as a
   *column* only and never as a literal, and say so in a comment.
5. **`RowOracle`** — `normalize` / `scalarEquals` / `multisetEquals` must compare
   the new types correctly, and this is the highest-risk part of the plan: a
   `multisetEquals` that compares byte arrays with `==` would make every Binary
   property vacuously pass, or falsely fail. `RowOracle` already has Date and
   Timestamp cases; Binary needs `java.util.Arrays.equals` and Decimal needs a
   deliberate choice between `equals` (scale-sensitive) and `compareTo` — pick the
   one that matches what [plan 02](02-total-order-for-decimal-and-binary.md)
   decided the engine means, and put the reasoning in a comment the way the
   existing `Double`-tolerance note does.
6. **Coverage guards, one per type, asserting both halves.** The repo has been
   burned three times by a guard that covered the shape but not the value
   (`docs/testing.md` spells this out). For each new type assert it reaches a
   comparison, an `ORDER BY` key, a `GROUP BY` key and a `MIN`/`MAX` — and that
   the *values* that matter are present: the equal-content-distinct-object byte
   arrays, the same-value-different-scale decimals, the double-colliding decimal
   pair. Report the observed rates in the guard's failure message, as
   `rankWindowsReachIeeeSpecialOrderKeys` does, so a future reader can tell rare
   from gone.

Expect the bind-reject-rate guards to move: new literal forms and new type pairs
will find binder rejections. A rise in that rate is a finding about the frontend
([plan 01](01-reject-or-honor-every-clause.md)), not a reason to narrow the
generator.

## Verification

- Every coverage guard green at its own seed budget, with the rates recorded.
- `bazel test //...` green; campaign-sized runs (`FUZZ_SEEDS=20000`, disjoint
  blocks) of `metamorphic`, `sharded_mode` and `mode_differential` before landing.
  Remember `--test_env=` is load-bearing, and `bazel shutdown` before running
  parallel JVMs at `-Xmx1200m` (`docs/testing.md`).
- Whatever the widened corpus finds gets the plan-02 treatment: a deterministic
  regression first, then the engine fix, then the general guard. Do not gate a
  shape back out to get green — that is the failure mode
  `docs/gotchas.md` and [[fuzzer-generator-gates]] exist to prevent. If something
  has to be parked, park it with a plan file and a named gate, not a silent filter.

## Docs to update

`docs/testing.md` (the generator inventory's type lists, the new guards and their
rates), `docs/gotchas.md` (if anything is parked), `docs/extending.md` (the
"add a property / generator" recipe gains a step: a new `DataType` has to reach
all four corpora plus `RowOracle`, and the guard has to assert the values, not
just the shape).

## Launch prompt

```
Read plans/robustness/04-widen-fuzz-corpus-to-every-type.md and implement it
end-to-end. Land plans/robustness/02 first, or expect this to go red.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15, docs
in the same commit.

Do RowOracle FIRST and test it directly — a wrong comparator there makes every
new property either vacuous or falsely red, and you will not be able to tell
which. Then widen one corpus at a time, each with its coverage guard, each as its
own commit, running the default fuzz targets between.

Campaign before landing: metamorphic, sharded_mode and mode_differential at
FUZZ_SEEDS=20000 in disjoint blocks. --test_env= is load-bearing.

Stop and ask before: gating any newly-reachable shape back out, or deciding that
Decimal equality is scale-sensitive in RowOracle — that has to match what the
engine decided in plan 02, not be chosen independently here.
```
