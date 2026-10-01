# Plan 02: `Ops.eq` / `Ops.cmp` are wrong for `BinaryType` and lossy for `DecimalType`

> Status: **not started** · Effort: 2-3 days · Risk: medium (user-visible
> semantics for two types, and `Ops` is the engine's single comparison choke
> point — every operator is downstream of it)

## How this was found

A manual audit on 2026-09-10 of the repo's own established bug class. The
through-line of fixed bugs 03, 06 and 07 is recorded in
[`docs/conventions.md`](../../docs/conventions.md): *an ordering site that
reaches for a raw comparison instead of the engine's single total order.* All
three were about floats. This plan is the same question asked about the four
types the float work never touched.

The engine has **ten** concrete `DataType`s. `DataGen.ColumnTypes` and
`ExprGen.GenTypes` list **six**: Int, Long, Float, Double, Boolean, String. So
`DateType`, `TimestampType`, `BinaryType` and `DecimalType` have never been
through a fuzz corpus, and no end-to-end SQL test puts one through the engine
at all — `HashKeysTest` covers the key codecs in isolation and `ExprBatchTest`
touches `DateType` for `ColRefExpr`/`LitExpr` parity, and that is the whole of
it.

Probed all four at commit `d4bb409`. **Date and Timestamp came out clean** —
`=`, `>`, `BETWEEN`, `IN`, `ORDER BY`, `GROUP BY` and `MIN`/`MAX` are all
correct, including string-literal coercion (`WHERE d = '2024-01-01'` works).
Binary and Decimal did not.

## Problem

`Ops.eq` and `Ops.cmp` (`src/main/scala/com/transformer/sql/plan/Ops.scala:28`
and `:36`) dispatch on the *runtime class* of the boxed value:

```scala
def eq(a: Any, b: Any): Boolean = (a, b) match {
  case (x: java.lang.Number, y: java.lang.Number) => x.doubleValue == y.doubleValue
  case (x: String, y: String) => x == y
  case (x: java.lang.Boolean, y: java.lang.Boolean) => x.booleanValue == y.booleanValue
  case (x, y) => x == y                                    // <- Array[Byte] lands here
}

def cmp(a: Any, b: Any): Int = (a, b) match {
  case (x: java.lang.Number, y: java.lang.Number) =>
    java.lang.Double.compare(x.doubleValue, y.doubleValue) // <- BigDecimal lands here
  ...
  case (x, y) => x.toString.compareTo(y.toString)          // <- Array[Byte] lands here
}
```

`BinaryVector.getBoxed` returns `Array[Byte]`, which is not a `Number`, not a
`String`, not `Comparable`. So:

- **`eq` is reference equality.** Two byte arrays with identical content are
  unequal.
- **`cmp` compares `"[B@5e9f23b4"` against `"[B@1b6d3586"`** — identity hash
  codes. The order is arbitrary and differs run to run.

`DecimalVector.getBoxed` returns `java.math.BigDecimal`, which **is** a
`java.lang.Number`, so both helpers go through `doubleValue` and lose every
digit past double's 53-bit mantissa.

Measured, at `d4bb409`:

| query | correct | got |
|---|---|---|
| `WHERE b = b2`, equal content in distinct arrays, 4 rows | 4 rows | **0 rows** |
| `WHERE b <> b2`, same data | 0 rows | **4 rows** |
| `SELECT id, b FROM t ORDER BY b` over `03 03`,`01 01`,`02 02`,`01 01` | the two `01 01` adjacent | **`(2,0101) (3,0202) (1,0303) (4,0101)`** — not sorted |
| `MAX(b)` over the same | `03 03` | **`01 01`** |
| `WHERE a = b` where `a=10000000000000000001`, `b=...002` | no match | **matches** |
| `WHERE a < b`, same row | matches | **no match** |
| `MIN(k)`,`MAX(k)` over `...001, ...002, ...003` ascending | `001`,`003` | **`001`,`001`** |
| the same three rows **reversed** | `001`,`003` | **`003`,`003`** |

That last pair is the bug-06 shape exactly: an extremum that moves with row
order, and therefore with partition fan-out and spill flush order too, because
`MinMaxState.merge` (`AggregateExec.scala:1237`) uses the same `Ops.cmp` for
reference types.

### The half that makes it a correctness defect rather than a precision caveat

The hash path and the comparison path **disagree, in both directions, inside one
query engine.** `HashKeys` special-cases `Array[Byte]` with
`java.util.Arrays.equals` (`HashKeys.scala:242` and `:817`) and falls through to
`BigDecimal.equals` — which is scale-sensitive — for decimals:

| values | `WHERE x = y` (`Ops.eq`) | `GROUP BY x` (`HashKeys`) |
|---|---|---|
| two equal-content byte arrays | **not equal** | one group (equal) |
| `1.0` and `1.00` | **equal** | two groups (not equal) |

Both were confirmed: `GROUP BY b` correctly collapses the duplicate byte arrays
the `=` operator says are different, and `GROUP BY k` over `1.0 / 1.00 / 1.000 /
1` yields **four** groups (and `COUNT(DISTINCT k)` yields `4`) over values `=`
calls equal. An equi-join written as a join key and the same join written as a
residual predicate therefore give different answers.

### Two smaller findings in the same probe, same area

- `SELECT SUM(a)` over a decimal column returns a **Double** (`1.0E+19`),
  discarding the precision the type exists for.
- `SELECT a + a` over a decimal column throws
  `IllegalStateException: Arithmetic on decimal(38,4) not supported` — an
  internal exception class for what is a missing feature.
  `DataType.widerNumeric` ranks `DecimalType` **highest**, so any
  `decimal <op> int` expression routes to the branch that throws.

## Why the fuzzers miss it

One reason, and it is not subtle: the corpus has no decimal or binary columns.
`DataGen.ColumnTypes` is six types, `ExprGen.GenTypes` is the same six by
construction ("identical to `ExprGen`'s `GenTypes` so any shape one can build
the other can too"), and `MetaQueryGen`'s key and payload domains are drawn from
their own pools of the same six. Every oracle in the repo would catch these
today if it could see the input:

- `MetaModeDifferential` would catch the order-dependent `MIN`/`MAX` the moment
  a decimal column met a partition split.
- `AggDecomposition` drives `MinMaxState.merge` directly — it is the relation
  that generalized the Boolean-slot bug — and `aggDecompositionCoversEveryColumnType`
  already asserts *every payload column type* reaches its `MIN`/`MAX`
  decomposition. It passes because "every type" means the six.
- `Tlp` partitions on output columns and would see a non-sorted `ORDER BY`.

So this plan's fix is only as durable as [plan 04](04-widen-fuzz-corpus-to-every-type.md),
and the two should land together or in immediate sequence. The lesson from the
last three bugs applies verbatim: covering the shape is not covering the value,
and here the missing half is the *type*.

## Proposed fix

Make `Ops.eq` and `Ops.cmp` type-aware instead of class-dispatched, at the one
place every operator funnels through:

1. **`Array[Byte]`** — lexicographic unsigned-byte comparison
   (`java.util.Arrays.compareUnsigned` on JDK 21) for `cmp`, `java.util.Arrays.equals`
   for `eq`. That makes both agree with what `HashKeys` already does, and gives
   `ORDER BY`, `MIN`/`MAX`, `BETWEEN`, `IN`, `GREATEST`/`LEAST` and `NULLIF` a
   real order for the first time.
2. **`BigDecimal`** — `compareTo` for `cmp`, and `compareTo(…) == 0` for `eq`,
   so full precision survives and numeric equality (not scale equality) decides.
   Mixed decimal-against-integral comparisons must promote to `BigDecimal`, not
   demote to `double` — `new BigDecimal(longValue)` on the integral side.
3. **Then reconcile `HashKeys` with the new answer.** This is the decision the
   plan cannot make for you and the one to stop and think about: once `=` says
   `1.0 = 1.00`, `GROUP BY` and equi-join must agree, which means the decimal key
   codec has to key on something scale-normalized (`stripTrailingZeros`, or the
   unscaled value rescaled to the column's declared scale) rather than
   `BigDecimal.equals`. Note `DecimalVector.setBoxed` does not enforce the
   vector's declared scale (a `Number` is stored as `BigDecimal.valueOf(doubleValue)`),
   so scales within one column are not uniform today and normalizing at the
   codec is the safer side to fix.
4. Leave the **float/double** path exactly as it is. `Ops.cmp`'s double branch is
   load-bearing for NaN/-0.0 and is pinned by plan 03/05/06's regressions; do not
   let a decimal refactor disturb the `Number` case for non-decimal numbers.
5. The vectorized twin must move with it. `Ops.compare` routes `isNumeric` pairs
   to `numericCompare` — which is `readDouble`-based, and `DataType.isNumeric(DecimalType)`
   is **true** — so decimals must be routed to `genericCompare` (or given their own
   branch) or `evalVec` will keep the lossy answer while `eval` gets the precise
   one. That would turn a semantic bug into a parity bug, which is strictly worse.

`SUM` over decimal and decimal arithmetic are **out of scope** — record them in
`docs/gotchas.md` as known gaps. They are features, not wrong answers, and this
plan is big enough.

## Verification

- Deterministic regressions FIRST, failing at HEAD: the eight table rows above,
  plus the order-dependence pair run in both row orders **and** across a
  partition split **and** under spill (three separate fan-outs, matching how
  plan 05's regressions are structured in `hash_aggregate_spill_test`).
- A `GROUP BY`-versus-`=` agreement test for both types — the inconsistency is
  the finding, so assert the consistency.
- `MinMaxState` serde already round-trips Binary and Decimal correctly
  (`MinMaxState.writeBoxed`/`readBoxed`); add the two types to
  `agg_state_serde_test`'s MinMax coverage anyway, since the test currently
  covers MinMax over Long and String only.
- Then [plan 04](04-widen-fuzz-corpus-to-every-type.md), and campaign-sized runs
  of the metamorphic, sharded and mode-differential targets before landing.

## Docs to update

`README.md` (the supported-types table and the total-order paragraph, which
currently speaks only of floats), `docs/conventions.md` (extend the single-total-
order rule to name byte arrays and decimals — it is the rule that would have
prevented this), `docs/gotchas.md` (the `GROUP BY`/`=` agreement now holds;
decimal `SUM` and decimal arithmetic as known gaps), `docs/testing.md`.

## Launch prompt

```
Read plans/robustness/02-total-order-for-decimal-and-binary.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15,
docs in the same commit.

Re-verify the measured table FIRST with a throwaway probe target against HEAD —
it was captured at d4bb409. Delete the probe before committing. Then add the
regressions and confirm they fail. Then fix Ops, then the vectorized twin, then
HashKeys.

Run the sharded and mode-differential campaigns before landing. Remember
--test_env= is load-bearing for FUZZ_SEEDS (docs/testing.md), and shut bazel
down first if you run parallel JVMs.

Stop and ask before: changing the Number branch of Ops.cmp for non-decimal
numbers (it is pinned by the NaN work in plans/followups/03, 05 and 06), or
changing what a decimal GROUP BY key hashes to if it turns out to cost more
than normalizing scale at the codec.
```
