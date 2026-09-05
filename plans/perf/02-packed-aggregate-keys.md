# Plan 02: Packed keys for `HashAggregateExec` / `DistinctExec` / `HashJoinExec`

> Status: not started · Tier: 2 · Effort: ~3-5 days · Risk: medium

## Goal

Replace `Seq[Any]` HashMap keys in pipeline breakers with a packed,
allocation-light key encoding. Cuts per-row allocations in the inner
aggregation/distinct/join loop to roughly zero on common shapes (fixed-width
primitive keys), and removes the `Seq.equals`-walks and dynamic-dispatch
`hashCode` calls that dominate when group cardinality is high.

## Why it matters

Today the hot loops look like (see `AggregateExec.scala:50-71`):

```scala
while (r < nrows) {
  val key: Seq[Any] = groupKeys.map { case (e, _) => e.eval(batch, r) }
  val states = map.getOrElseUpdate(key, { Array.tabulate(...) })
  ...
}
```

For a 2-column GROUP BY on `(market_id: String, ts_hour: Long)` over 27M
input rows, that's:

- 27M `Seq` allocations (one per row),
- 54M boxed `Any` values stored in those Seqs,
- 27M `hashCode` calls that walk the Seq and dispatch dynamically per element,
- on every collision, an `equals` call that walks the Seq pairwise.

`DistinctExec.collect` is the same pattern (`DistinctExec.scala:36-43`).
`HashJoinExec.buildSide` uses `Seq[Any]` keys for the build map
(`JoinExec.scala:62-73`) — same cost on the build side, hit once per probe
row again.

For Polymarket-shape data this is plausibly the #1 allocation source after
parquet decode and the #2 CPU hotspot after `Expr.eval`.

## Current state

- `HashAggregateExec`: `key: Seq[Any]` keyed `LinkedHashMap` at
  `src/main/scala/com/transformer/sql/exec/AggregateExec.scala:58-71`.
- `DistinctExec`: `LinkedHashSet[Seq[Any]]` at
  `src/main/scala/com/transformer/sql/exec/DistinctExec.scala:30-47`.
- `HashJoinExec`: `HashMap[Seq[Any], ArrayList[Int]]` at
  `src/main/scala/com/transformer/sql/exec/JoinExec.scala:62-74`. Probe side
  also builds `Seq[Any]` keys at `JoinExec.scala:124`.
- `WindowExec`: same pattern for PARTITION BY keys
  (`WindowExec.scala:80-89`). Less hot because Window is single-threaded
  anyway, but the fix is free to extend here.

## Proposed design

### Three key shapes

Build a `KeyCodec` abstraction with three concrete strategies. The planner
or the operator picks the best one at construction time based on the
key types.

1. **`LongKey`** — single fixed-width numeric column (Int/Long/Date/Timestamp/Boolean).
   Use a `Long2ObjectOpenHashMap` (or hand-rolled equivalent) keyed by the
   primitive. NULL sentinel via a side `BitSet` or a reserved value
   (clean approach: `LongOrNull` wrapper that's still cheap — `(Long, Boolean)`
   in a single packed long won't quite work because every long value is
   legal, so use a sidecar `nullBits: util.BitSet` keyed by hash bucket).
2. **`PackedBytesKey`** — multi-column key of fixed-width primitives.
   Pack into a `byte[]` of size `sum(width(col))`. Each slot is either the
   little-endian primitive or a null sentinel byte. Use the `byte[]` itself
   as the HashMap key with a hand-rolled `equals`/`hashCode` (or wrap in a
   `BytesKey` final class that caches `hashCode`). Compare with
   `Arrays.equals(byte[], byte[])` — JIT intrinsifies to SIMD.
3. **`GenericKey`** — fallback for keys with one or more variable-width
   columns (string, binary, decimal). Use a small interner: per-column
   `Object2IntOpenHashMap[String,Int]` mapping strings to short ints, then
   pack into the `PackedBytesKey` path with the int slot.

### Encoder selection

At operator construction:

```scala
val keyCodec: KeyCodec =
  if (groupKeys.length == 1 && isFixedNumeric(groupKeys.head._1.dataType))
    LongKeyCodec(groupKeys.head._1)
  else if (groupKeys.forall(k => isFixedWidth(k._1.dataType)))
    PackedBytesCodec(groupKeys.map(_._1))
  else
    GenericPackedCodec(groupKeys.map(_._1))   // intern strings/binaries
```

### Hot-loop shape

```scala
val codec = ...
while (r < nrows) {
  val key = codec.encode(batch, r)          // single byte[] or long; no Seq
  val states = map.computeIfAbsent(key, _ => Array.tabulate(...))
  ...
}
```

For the LongKey path the `computeIfAbsent` API is on `Long2ObjectOpenHashMap`
(fastutil) — but we don't want to add a fastutil dep just for this. Build
our own minimal `LongHashMap` in `core/` (open-addressing, power-of-two
size). It's ~150 lines and matches the project's "stdlib + small deps"
stance from CLAUDE.md.

For PackedBytesKey: use `java.util.HashMap[BytesKey, V]` where `BytesKey`
caches its hashCode. Avoid the GC pressure of allocating a fresh
`BytesKey` per probe — keep a per-thread reusable encoder buffer; when
the key turns out to be new (computeIfAbsent inserts), clone the
buffer's snapshot.

### Decoder

For aggregate emit (`AggregateExec.scala:87-116`), need to decode keys
back to typed values for the output `ColumnarBatch` columns. Each codec
exposes `decode(key, outBatch, row)` that unpacks the key into the
correct output columns.

For Distinct (where the key = entire row), the decode produces the
output row directly.

For Join (where the key is only the join columns), no decode is needed —
the row arrays are still kept separately.

### NULL semantics

SQL says `NULL = NULL` is unknown (false) in WHERE, but in `GROUP BY`,
all NULLs are grouped together. The codec must hash NULL to one
deterministic bucket. The sidecar BitSet approach handles this cleanly:
record `isNull` per column slot and include it in the hash.

For `HashJoinExec`, the existing code at `JoinExec.scala:125` already
short-circuits NULL keys: `if (key.exists(_ == null)) null`. Preserve
that behavior — a NULL join key produces a non-match.

## Files to touch

- **New**: `src/main/scala/com/transformer/core/HashKeys.scala` — `KeyCodec`
  trait + `LongKeyCodec`, `PackedBytesCodec`, `GenericPackedCodec`,
  `BytesKey` (the cached-hash wrapper), `LongHashMap` if needed.
- **New**: `src/test/scala/com/transformer/core/HashKeysTest.scala` — unit
  tests for each codec: round-trip encode/decode, NULL handling, hash
  consistency under structural-equal-but-different-instance keys.
- **Modified**: `AggregateExec.scala`, `DistinctExec.scala`, `JoinExec.scala`,
  `WindowExec.scala` to use the codec.
- **Modified**: corresponding BUILD.bazel files.
- **Modified existing tests**: `SqlEngineTest.scala` — should pass
  unchanged. If anything is brittle to ordering, the `LinkedHashMap`
  emit order is preserved by the new layer (use a parallel
  `Array[K]` insertion-order list).

## Edge cases

1. **GROUP BY with no keys** (`SELECT COUNT(*)`). Empty group-key list.
   The codec returns a sentinel "always same" key; insertion order has
   one row. Already a special case in `AggregateExec.scala:42-46`.
2. **NULL-only column** in GROUP BY — all NULLs group together; emit
   one row with NULL. Verify against the existing test (or add one if missing).
3. **Decimal keys** — fixed width (16 bytes) when scale is uniform; the
   `BigDecimal` boxing today already pays the cost. Fall into the
   generic codec; encode as the unscaled `BigInteger.toByteArray` plus
   scale.
4. **String hash quality** — `String.hashCode()` is fine. For the
   interner approach, hash collisions in the interner map are not a
   correctness issue (the map handles them); they just slow down
   interning. Skip cryptographic hashing.
5. **Insertion order preservation** — `LinkedHashMap` is used in
   `AggregateExec` so output order matches encounter order. The new
   open-addressing map should track insertion order via a parallel
   `IntArrayList` of slot indices, or by using `java.util.LinkedHashMap`
   for the generic path and skipping the open-addressing optimization for it.
6. **Large group cardinality (heap pressure)** — if 100M distinct keys
   appear, the packed byte[]s + per-group `AggState[]` are still all in
   heap. The packed-key change does **not** fix this; it just makes the
   pre-OOM phase faster. See plan 09 (spill).
7. **Probe vs build asymmetry** in `HashJoinExec`: codec used to build
   keys must round-trip with codec used to probe. Construct one codec
   from `rightKeys` and pass it to the probe loop via a shared instance.

## Testing

### Correctness (must all still pass)
- All existing `SqlEngineTest` cases.
- Add `HashKeysTest` unit tests:
  - Each codec: encode + decode round-trip for every supported type.
  - NULL in every key column position.
  - Equal-but-different-instance Strings hash to the same key.
  - Multi-column key respects column order.
- Add `SqlEngineTest` cases that stress the codec selection:
  - GROUP BY a single Long
  - GROUP BY a single String
  - GROUP BY (Long, String)
  - GROUP BY (Long, Long, Long)
  - DISTINCT over wide schema
  - JOIN on multi-column key including NULLs

### Performance
- Microbenchmark: 27M-row GROUP BY (Long, String) → measure
  HashAggregate-only wall time before/after.
- Polymarket end-to-end: this is the workload where the change matters most.
  Expect ≥20% reduction in `stg_orderbook → int_*` task wall time. Record numbers.
- Jaffle Shop end-to-end: should not regress (small joins, low cardinality).

### Required workflow gates
- `bazel test //...` green.
- jaffle_shop deploy jar: 15/15 Succeeded.
- polymarket deploy jar: 15-Succeeded / 1-ValidationFailed / 1-Skipped pattern.

## Risks

1. **Subtle hash inconsistency** between codecs. Two keys that *should*
   compare equal must hash equal. Mitigation: extensive round-trip and
   equality tests; reuse `Ops.eq` and `Ops.cmp` semantics.
2. **NULL ordering surprise.** SQL says all NULLs group together; if the
   new codec mis-handles this, aggregation results silently change.
   Mitigation: dedicated test case per codec, plus compare against current
   behavior on jaffle_shop tables that contain NULLs.
3. **Insertion-order regression.** Some existing tests may rely on
   `LinkedHashMap` emit order. Mitigation: preserve insertion order
   explicitly in the new map.
4. **Decimal handling complexity.** Skip the optimization for Decimal
   columns initially — keep them on the `GenericKey` (boxed) path. Mark as
   TODO. Polymarket and jaffle_shop don't use Decimal heavily.
5. **No FastUtil dep.** Building a `LongHashMap` from scratch is a real
   commitment; verify the perf win justifies the maintenance cost. Initial
   alternative: use a plain `java.util.HashMap[java.lang.Long, V]` — still
   removes the Seq allocation, but keeps Long boxing. Measure both.

## Suggested phases

1. **Phase 1**: build `KeyCodec` trait + `PackedBytesCodec` + `BytesKey`.
   Land in `core/` with tests. No operator integration yet.
2. **Phase 2**: integrate into `DistinctExec` first (simplest — key = whole
   row, no aggregation state). Compare result correctness via existing tests.
3. **Phase 3**: integrate into `HashAggregateExec`. Most surface area.
4. **Phase 4**: integrate into `HashJoinExec`. Build- and probe-side
   codec construction; verify NULL short-circuit semantics.
5. **Phase 5**: integrate into `WindowExec` partition keys. Cheapest because
   Window is already single-partition.
6. **Phase 6 (deferred)**: `LongHashMap` for the single-Long GROUP BY case.
   Only land if Phase 2-5 perf numbers don't already hit the target.

## Docs to update

- `docs/architecture.md` §2 (ColumnarBatch + RowBuf): add a paragraph on
  `KeyCodec` and where it sits.
- `docs/conventions.md`: mention key encoding as a pattern for new operators.
- `docs/extending.md`: hint for anyone adding a new pipeline-breaking operator.
- `docs/code-map.md`: new `core/HashKeys.scala` entry.
- `docs/testing.md`: new test target if `HashKeysTest` becomes its own target.

## Launch prompt

```
Read plans/perf/02-packed-aggregate-keys.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps (stdlib + minimal additions
only; do NOT add fastutil or guava), bazel test //... must pass, jaffle_shop
deploy jar must hit 15/15 Succeeded, polymarket deploy jar must hit the
15/1/1 pattern. Land docs in the same PR.

Follow the 6-phase plan in the doc. After each phase, run the full test
suite + the end-to-end examples. If perf numbers from phases 2-5 already
meet the goal, skip Phase 6 (LongHashMap) and leave it as a follow-up.

Spawn parallel sub-agents for: (a) building HashKeysTest in parallel with
the codec implementation, (b) profiling polymarket before and after to
quantify the win.

Stop and ask before: changing the Expr.eval interface, adding any
external dependency, modifying anything outside the operators and core/.
Include in the PR description: HashAggregate wall-time before/after on
Polymarket stg→int tasks.
```
