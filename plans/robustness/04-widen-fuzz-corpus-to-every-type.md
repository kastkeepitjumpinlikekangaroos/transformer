# Plan 04: the fuzz corpus covers six of the engine's ten types

> Status: **done** 2026-10-03 · Effort: 2-3 days · Risk: low (test-only), but it
> surfaced failures, two of them in `src/main`. What landed, what it found, and
> where it departed from this plan is in [Outcome](#outcome) at the end.
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
>
> Plan 03 landed 2026-10-03, so a DECIMAL column now spills, and two of its
> rules reach this plan. A spilled decimal reads back at its column's declared
> scale, so the spill mode can spell a value differently from the in-memory
> run (`1.50` for `1.5`): another reason for `compareTo` in `RowOracle`. And
> the parquet writer refuses, rather than rounds, a decimal it cannot store
> exactly at the declared scale and precision, so a generated value past
> either fails the spill mode where the in-memory mode succeeds: generate
> decimals within the declared precision and exactly representable at the
> declared scale, or keep such values on purpose and expect the error. A
> decimal `SUM` is one (it keeps its input's type while computing in DOUBLE),
> which matters wherever a later operator spills one. Timestamp values that
> would differ under a unit mis-read (step 1) now matter only to the reader's
> own tests, which `parquet_external_format_test` covers.

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

## Outcome

Landed 2026-10-03 as eight commits, in the order the launch prompt asked for:
the comparator first, then one corpus at a time, each with its guards, and an
engine fix ahead of the corpus that found it.

**`RowOracle` first** (`30b5115`). The comparator was wrong in both directions
on the four types: Scala's `==` on two byte arrays is reference equality, so
every BINARY property would have been falsely red, and `BigDecimal.equals`
includes the scale, though `=` calls `1.0` and `1.00` equal and the engine
respells a value freely (a spilled decimal reads back at its declared scale).
Decimals now compare with `compareTo`, the decision plan 02 made for the
engine, so the stop-and-ask on scale-sensitive equality never triggered; byte
arrays by content; dates and timestamps as the `LocalDate` / `Instant` both
paths box, with no conversion from an epoch count (the leniency that once hid
an `Int` in `ExprBatchTest`). `fuzz/row_oracle_test` holds `scalarEquals` to
`Ops.eq` on every non-float type; 6 of its 10 tests fail against the old
comparator.

**The four corpora**, each its own commit with its guards:

| commit | corpus | what it draws | guards |
|---|---|---|---|
| `9e1bbe0` | `ExprGen` | all ten types; DECIMAL at (9,2), (18,4) and (38,18), one per parquet layout, drawn as one type | four, over 20000 seeds: a comparison and an IN of each type, and the value pairs meeting there |
| `1208617` | `DataGen` (+ what `QueryGen` needs to stay total over it) | `ColumnTypes` is `GenTypes`; slices of `ExprGen`'s pools; SUM-safe decimals | four, over 10000 seeds: ORDER BY, GROUP BY, MIN/MAX (and decimal SUM/AVG), with the pairs in the column |
| `d0ece71` | `QueryGen` predicates | comparisons, IN, IS NULL and projected items over the four | the same four guards, plus comparison and IN |
| `dbf42b1` | `MetaQueryGen` | join keys of every type but FLOAT, DOUBLE and BOOLEAN; payloads of all ten; TLP keys | four, over 20000 seeds: join key, comparison, ORDER BY, GROUP BY, decomposition MIN/MAX, TLP key |

`DataGen.ValueTag` names the value pairs every guard asserts (bytes of one
content, bytes one high bit apart, a decimal at two scales, decimals of one
double, instants under a millisecond apart), and each guard's comment records
the measured counts its bounds sit ~3x below.

**What the widened corpora found**, each given the plan-02 treatment
(regression first, confirmed red, then the fix):

- **`ABS` of a decimal computed in DOUBLE** (`4b4b007`). `expr_parity_fuzz_test`
  seed 98: the vector path returned a `DoubleVector` under a `DECIMAL` type.
  Behind it, a wrong answer: `MAX(ABS(k))` filled `MinMaxState`'s double slot
  and finished from its empty decimal one, so it returned NULL.
- **A NULL-typed column failed every spill** (`fadc2ff`).
  `mode_differential_fuzz_campaign` seed 1796: `SUM(NULL)` under spill threw
  `Writing null to Parquet not supported`. Pre-existing and independent of the
  four types, but unreachable to the six-type corpora, which wrote every NULL as
  a typed `CAST`: an untyped NULL is the only one a DECIMAL or a BINARY has.
- One generator bug, fixed in `d0ece71` rather than in the engine: a decimal
  compared with an "INT" expression that was really a FLOAT (`c0 + 0`), whose
  NaN the widening CAST refuses by plan 02's design.

**Where this plan was wrong, or silent.**

- *Literals.* Step 4 proposed the quoted ISO string for a DATE. Both forms are
  emitted: `DATE '...'` / `TIMESTAMP '...'` by default, since a bare string
  stays a STRING where nothing unifies it (COALESCE, CASE), and the quoted
  string in comparisons and IN items, where the binder reads it as the type it
  meets. A DECIMAL has no literal either: a number is a DOUBLE or an INT that
  the analyzer widens, so only values a double holds exactly are drawn. A
  BINARY has none at all, as the plan expected: columns and NULL only.
- *NULLs.* Nor do DECIMAL and BINARY have a typed NULL, since the binder reads
  no CAST target for either. Their NULL is the untyped one, which is what
  reached the spill defect.
- *Decimal `SUM`.* Plan 03's note offered "values a SUM keeps exact" or "keep
  them and expect the error". Every decimal in the SQL corpora is a small
  multiple of a quarter as a double, so a SUM is exact in any order, never
  moves with the layout, and always fits the scale a spill writes it at; the
  gap (SUM adds in DOUBLE) stays recorded rather than fuzzed. The `ExprGen`
  pools, which add nothing up, run to the edge of their precision.
- *Decimal arithmetic* is not generated: plan 01 did not settle it, and it
  throws, a recorded gap.
- *Other guards moved.* Ten types dilute the six, so four existing guards lost
  margin and were re-measured rather than loosened.

**Verification.** `bazel test //...` 49/49 after every commit and jaffle-shop
15/15 after each engine fix; the bind-reject rates stayed at 0. Before landing:
the metamorphic, sharded-mode and mode-differential campaigns ran 20000 seeds
each at `dbf42b1`, as eight disjoint 2500-seed blocks per target over three
JVMs at `-Xmx1200m` with bazel shut down: all 24 blocks green, every test in
every block passing (35, 6 and 22 per block, the sharded ones including
`shardingGateIsActive`). A block took ~14.5 minutes for metamorphic, ~16 for
sharded and ~3.3 for mode-differential, about 92 minutes of wall time in all.
The expression-parity campaign ran 200000 seeds green on the widened corpus,
in 17 seconds.

**Still open, found on the way and left as follow-ups:**
- **Aggregates and math functions bind over a non-number.** Probed at
  `dbf42b1`, none of it reachable from the corpora, which only aggregate
  numbers: `SUM` or `AVG` over a DATE, TIMESTAMP, BINARY or STRING binds and
  then throws a raw `ClassCastException` while the result drains; `ROUND` over a
  STRING does the same; `ABS` over a DATE or STRING binds and answers NULL for
  every row, in a `DoubleVector` under the argument's type. Each should be a
  bind error naming the type; recorded with plan 05's other user-facing-error
  findings.
- **Decimal `SUM`, arithmetic and unary minus** stay the gaps plan 02 recorded
  (`SUM` adds in DOUBLE and keeps the decimal type; `+ - * / %` and `-x` throw
  `IllegalStateException`). The corpora steer clear of them as described above,
  so no property watches them; implementing them is a feature, and the day it
  lands the SQL corpora should draw non-dyadic decimals and decimal arithmetic.
