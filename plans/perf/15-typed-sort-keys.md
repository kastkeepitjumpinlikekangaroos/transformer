# Plan 15: Sorts compare typed keys, not boxed rows

> Status: **not started** (written 2026-11-01 against `32e89b1`, which has
> `TopNExec`) · five decisions for the user below, each with a recommended
> answer · Effort: 5-8 days · Risk: medium: every sort's comparison moves to a
> typed twin of `SortKey.compare`, which must order every type exactly as
> `Ops.cmp` does; one phase changes the order of tied rows on purpose, and only
> with the user's yes (Q1)

## Goal

`SortExec` and `TopNExec` hold the rows they sort in typed column vectors and
compare typed key values: no comparison allocates, no row is an `Array[Any]`,
and a run spills when what the sort holds, not an estimate of its input,
crosses the threshold. Until Q1 is answered every query returns what it returns
today, row for row, ties included.

Not the goal: a parallel merge (plan 04 Phase 7), late materialization for a
top-n (reading `market_id` only for the rows kept, which may be part of
DuckDB's 0.23 s), `WindowExec` (Q4), a new spill format.

## Why

Measured 2026-11-01 (JDK 21, `-Xmx2g`, Apple M4, 8 cores, 16 GB, quiet machine)
over `orderbook_2026-03-26.parquet`: 132M rows, 8 partitions; `market_id` a
66-character STRING, `change_size` a FLOAT (EXPLAIN prints `change_size +
CAST(1 AS FLOAT)`).

| Query | transformer | DuckDB 2.0 |
|---|---|---|
| `SELECT market_id, change_size FROM ob ORDER BY change_size DESC LIMIT 3` (`Top 3`) | 3.0 s, ~0.5 GB | 0.23 s, 83 MB |
| the same without `LIMIT`, `transformer query --spill` (`Sort`) | 333 s | 95 s at a 2 GB `memory_limit` |
| the same without `--spill` | out of the 2 GB heap | |

Where the 333 s goes, from the code's arithmetic (unmeasured; Phase 0 confirms
it with JFR):

- **Runs.** `Spill.effectiveThresholdBytes` is max(64 MiB, 2 GiB / 4 / 16 pool
  threads) = 64 MiB a partition, and `Spill.estimateBytes` charges this row 172
  bytes (8 a cell, plus 24 + 2 × 66 for the string), so a partition flushes a
  run every 48 batches (~393K rows): ~42 runs a partition, ~336 in all.
- **Comparisons.** TimSort over each run, ~18 a row: ~2.5 billion, over 8
  threads. The merge, on one thread: a `PriorityQueue` over ~336 cursors, up to
  2 a level over 8.4 levels a poll and one or two an add: ~2.4 billion. Each
  comparison (`SortExec.rowOrdering`) builds two `RowView`s, one-row
  `ColumnarBatch`es with a vector, an array and a `BitSet` per child column
  (about eight objects each here), and runs `key.expr.eval` on both, boxing a
  `Float` a side. The one-thread merge is likely most of the time.
- **Memory.** A buffered row costs about 50 bytes here: the `Array[Any]` 24, the
  `Float` box 16, the `ArrayBuffer` slot and `toArray` copy 8 (the string is
  shared: the parquet reader interns a row group's values, architecture §3c),
  so 132M rows is ~6.6 GB. `Spill.estimateBytes` miscounts both ways: 172 for
  this row, 16 for a row of two BIGINTs that holds about 80 (24 + two `Long`
  boxes of 24 + slot). A spilling sort of fixed-width columns holds about five
  times its threshold before it flushes: 8 partitions at 64 MiB counted hold up
  to ~2.6 GB, past a 2 GB heap, today.
- **Top-n.** `TopNExec.keep` boxes each row's key (`eval`) and compares it with
  the head through `SortKey.compare`; the rest is the scan, measured alone.

A finding on the way: EXPLAIN VERBOSE of `SELECT DISTINCT market_id FROM ob
ORDER BY RAND()` plans `Sort: RAND()`, though architecture §2e says the binder
keeps `RAND()` out of sort keys. `SortExec` evaluates a key on every
comparison, so that comparator answers differently each call, which gotchas.md
forbids ("An operator that reads an expression twice must not be given one
that reads `RAND()`"). Checked when filing this plan: over 50,000 distinct
rows, 4 runs of 12 failed with "Comparison method violates its general
contract!" (TimSort), and `UNION` / `UNION ALL ... ORDER BY RAND()` plan the
same `Sort: RAND()`. Evaluating each key once a row fixes it; a fix need not
wait for this plan, and which one is the user's call: compute the key in a
column above the DISTINCT or set operation, as a plain SELECT does, or refuse
an ORDER BY expression that is not an output column there, as Postgres does.

## What the code does now

| Site | Holds | Compares |
|---|---|---|
| `SortExec.sortPartition` | each row as an `Array[Any]` (`getBoxed` per cell) in an `ArrayBuffer`; `bufBytes += Spill.estimateBytes(b)` with spill on | `sortBuffer`: `Arrays.sort` (TimSort, stable) with `rowOrdering` |
| `rowOrdering` | | two `RowView`s and an `eval` per key per side a call, then `SortKey.compare` |
| `mergeEmit` | `InMemoryCursor`s and `DiskRunCursor`s, in order: each partition's runs as flushed, then its in-memory tail | `PriorityQueue[Integer]` of cursor indices by `rowOrdering` on `current()` |
| SmallN path | below `SmallNThreshold` (4096) rows, no spill: the sorted partials concatenated and sorted again | `rowOrdering` |
| `flushRun` | sorted rows to `SortExec.batches` (`setBoxed` per cell), `ParquetWriter.writeAll` at `Spill.positionalSchema` | |
| `DiskRunCursor.advance` | re-boxes every row read back into a reused `Array[Any]` | |
| `TopNExec.keep` | keys by `eval` per row into an `Array[Any]`; a kept row boxed into a `Kept` | `SortKey.compare`; `PriorityQueue[Kept]`, ties by ordinal |
| `WindowExec` | rows boxed; keys from `evalVec` per batch, stored boxed per row | `sortPartition` sorts an `Array[Integer]` by `SortKey.compare`; `sameKey` (`Ops.cmp`), `frameBounds` |

**Ties** (rows equal on every key). One cursor: read order. SmallN and
`TopNExec`: partition by partition, read order. The K-way merge (4096 rows or
more over two or more cursors, or any spill): read order within a cursor, and
across cursors what the heap's shape leaves, moving with the partition count
and the run boundaries. `SortExec`'s scaladoc claims spill keeps "the same row
order under ties"; `SortExecSpillTest` pins positions only over unique keys,
and `SortExecTest`'s header calls the SmallN sort unstable (TimSort is stable).

## Relation to other plans

- **Plan 01 Phase 3** (optional "pre-computed sort-key tuples") evaluates each
  row's keys once into a tuple, as `TopNExec` now does. This plan supersedes it
  (keys once a batch, typed, and no boxed rows); plan 01's status line and
  plans/perf/README.md say so.
- **Plan 13 Step 6** needs a column gather in `core`; this plan needs it and a
  range copy. Whichever lands first adds them.
- **plans/testing/18** (not started) would ban raw float comparisons outside
  `Ops.floatCompare` (R2), float key bits outside `KeyCodec` (R3) and
  `String.compareTo` outside `Ops` (R4); the typed order adds no exception.

## Design

### Options

- **A. Keys once a row, boxed** (`TopNExec` today, plan 01 Phase 3): drops
  `RowView` and the repeated `eval`, keeps the boxed rows, the miscount, the
  dispatch and the re-boxing. Not a step of its own: Phase 2 rewrites the lines.
- **B. Typed key columns, an index sort, a columnar gather** (recommended,
  Phases 1-5): no boxed row anywhere, and one comparison shape, (vector, row)
  against (vector, row), for run sorts, merge, read-back and the top-n heap.
- **C. Order-preserving normalized keys** (DuckDB's memcmp-comparable bytes):
  branch-free, open to packed-long sorts, but a second encoding of each type's
  order with traps of its own. Only as a gated prefix over B (Phase 6, Q3).

### The typed order

`SortKey` builds a `KeyOrder` for its key's type (`SortKey.order`, beside
`compare` in `sql/plan`): `compare(a: ColumnVector, i: Int, b: ColumnVector, j:
Int): Int`. NULL placement is `compare`'s (two NULLs tie; a NULL goes where
`nullsFirst` says whatever the direction) and DESC swaps the operands, as
`compare` calls `Ops.cmp(b, a)`. Each type calls the primitive `Ops.cmp` uses:
`Ops.floatCompare`, new `Ops.stringCompare` and `Ops.binaryCompare`, and
`decimalCompare` made reachable, with `Ops.cmp`'s cases calling them too, so
each type's order is written once. Several keys: first difference wins. No
`scala.math.Ordering`, which boxes.

| Type | Vector | `getBoxed` | `Ops.cmp` | `KeyOrder` |
|---|---|---|---|---|
| INT | `IntVector`, null `BitSet` | `Integer` | both integral: `Long.compare` | `Integer.compare` |
| BIGINT | `LongVector` | `Long` | `Long.compare` | `Long.compare` |
| FLOAT | `FloatVector` | `Float` | `floatCompare` of `doubleValue`s | `Ops.floatCompare(a.toDouble, b.toDouble)`; widening is exact |
| DOUBLE | `DoubleVector` | `Double` | `floatCompare` | `Ops.floatCompare` |
| BOOLEAN | `BooleanVector` | `Boolean` | `compareTo`: false first | `java.lang.Boolean.compare` |
| DATE | `DateVector`, epoch days | `LocalDate` | `Comparable`: year, month, day | `Integer.compare` of the days |
| TIMESTAMP | `TimestampVector`, UTC micros | `Instant`, exact | `Comparable`: seconds, nanos | `Long.compare` of the micros |
| STRING | `StringVector`, NULL inline | `String` | `compareTo`: UTF-16 code units | `Ops.stringCompare`, the same |
| BINARY | `BinaryVector` | `Array[Byte]` | `Arrays.compareUnsigned` | `Ops.binaryCompare`, the same |
| DECIMAL | `DecimalVector`, any scale (a column does not enforce its declared one, `KeyCodec.decimalKey`) | `BigDecimal` | `compareTo`, by value | `Ops.decimalCompare`, by value |
| NULL | `NullVectorImpl`; read back from a run, an all-NULL BOOLEAN (`Spill.storedType`) | `null` | none: `SortKey` ties two NULLs | 0, reading neither vector |

### The run buffer and the index sort

Each partition copies its input batches into **chunks**, `ColumnarBatch`es of
`DefaultCapacity` rows: the payload columns, then one column per computed key.
A `ColRefExpr` key (every key `sortOverProjection` makes for a SELECT without
DISTINCT) reads its payload column; any other (a DISTINCT or UNION `ORDER BY a
+ 1`, `RAND()`) is evaluated once a batch with `evalVec`, as §5b's breakers do.
The copy is a typed range copy (new in `core`, an `arraycopy` a column), which
keeps the sort's memory independent of the child's batch sizes and vectors'
lifetimes. A row is the `Int` `(chunk << s) | row`, `s` the bits of the next
power of two at or above `DefaultCapacity`; past 2^31 rows a partition fails.

`IndexSort` (new, `core`) sorts `perm: Array[Int]`: a stable merge sort through
an `IntComparator` (insertion sort below 32, a merge skipped when its halves
are in order, an aux array of n ints). A stable sort's permutation is unique
under one comparator, so this is TimSort's order. A sorted run is (chunks,
perm), and leaves through a typed gather by `perm` (new in `core`). Phase 2's
bench also tries a dense copy of each key: adopt it if runs sort 1.3× faster.

### Merge, spill runs, read-back

- `flushRun` gathers the sorted run, payload and computed keys, and writes it
  at `Spill.positionalSchema(schema ++ computed key fields)`: a `RAND()` key is
  read once and stored, never recomputed. Read-back stays positional.
- `SortCursor` exposes `keyVector(k)` and `row`: an `InMemoryCursor` its chunk
  column at `perm(pos)`, a `DiskRunCursor` its read-back batch's column (a
  ColRef key at its payload index, computed key `j` at `ncols + j`). Nothing is
  re-boxed; output copies each popped row's cells with `copyTo`.
- Phase 2 keeps `PriorityQueue[Integer]`, comparing heads by `KeyOrder`, with
  the boxes made once (`Integer.valueOf` allocates past 127). Same signs, same
  algorithm, same output. Phase 4 (Q1) breaks ties by cursor index.

### TopNExec

`keep(p)` reads keys with `evalVec` a batch. Its slots are a `ColumnarBatch`
of `n` rows plus a column per computed key; its heap an `Array[Int]` of slots,
worst at the top, by `KeyOrder` then an `Array[Long]` of ordinals. Once full, a
row costs one comparison with the head and is copied into the head's slot only
when it sorts strictly before it. At the end every partition's slots, in
partition order, are sorted by `IndexSort` and the first `n` gathered. Same
rows, same order. A top-n then reads each key once, so `PhysicalPlanner.topN`'s
`RAND()` gate can go.

### Normalized prefixes (Phase 6, Q3)

A 64-bit prefix per row of the leading key or keys, compared unsigned,
`KeyOrder` breaking equal prefixes unless the prefix is exact:

| Type | Prefix | Exact |
|---|---|---|
| INT, DATE | `v ^ 0x80000000`, 32 bits | yes |
| BIGINT, TIMESTAMP | `v ^ Long.MinValue` | yes |
| BOOLEAN | 0 or 1 | yes |
| FLOAT, DOUBLE | `KeyCodec.floatKeyBits` / `doubleKeyBits` (-0.0 as 0.0, one NaN), then `~bits` when negative, else the sign bit set: NaN above +Infinity, as `Ops.floatCompare` has it | yes |
| STRING | the first 8 bytes of its UTF-16 code units, each written as UTF-8 writes a BMP character (surrogates one by one, CESU-8 style), zero-padded; UTF-8 of code points orders as code points, which is not the engine's order (gotchas.md, parquet pushdown) | no |
| BINARY | the first 8 bytes, zero-padded | no |
| DECIMAL | none: no cheap exact encoding by value across scales | |

The leading key's NULL rows are a block at its `nullsFirst` end, sorted by the
later keys; a later key in the prefix carries a NULL bit placed by its own
`nullsFirst`; DESC inverts the value bits, never the NULL placement. One
exact key of 32 bits or fewer (INT, DATE, FLOAT, BOOLEAN, the measured query's
shape) packs prefix and row into one long whose signed order is (prefix, row),
which `Arrays.sort(long[])` sorts stably, every long being distinct.

### What each phase reaches

| Phase | Run sort | K-way merge | Spill write | Read-back | Top-n |
|---|---|---|---|---|---|
| 2 | chunks, `perm`, `IndexSort` by `KeyOrder` | `PriorityQueue` kept, heads by `KeyOrder` | gathered batches, computed keys appended | the cursor's batch and row | |
| 3 | | | | | slots, an `Int` heap by `KeyOrder` |
| 4 | | ties by cursor index; a loser tree | | | |
| 5 | flushes on bytes held | fewer cursors | fewer, larger runs | | |
| 6 | prefixes; packed longs | prefix first | | prefix per batch read | prefix test at the head |

## Tests: what proves the answers do not move

1. **`sort_key_order_test`** (Phase 1, in `fuzz/` beside `RowOracleTest`, to
   draw `ExprGen`'s per-type pools): for every type, ASC and DESC, NULLS FIRST
   and LAST, all pairs of a pool and random pairs: `signum(order.compare(va, i,
   vb, j)) == signum(key.compare(boxed a, boxed b))`. Pools: NaNs of several
   bit patterns (tied, above +Infinity); -0.0 and 0.0 (tied; each row keeps its
   own zero); the infinities; INT and BIGINT extremes; `1`, `1.0`, `1.00`
   (tied) below `1.000000000000000001`; bytes `01` against `81`, a prefix
   first; strings: `""` and prefixes first, U+E000 and U+FFFD above U+1F600
   (D83D DE00: below U+E000 by code unit, above by code point), lone
   surrogates; dates and timestamps before 1970 and at the storable extremes;
   NULLs. Again over vectors spilled and read back. Build text past ASCII from
   code points (conventions.md); a typed `\u` escape can land raw.
2. **`index_sort_test`** (Phase 1, `core`): the permutation equals
   `Arrays.sort` of boxed indices under the same comparator, n from 0 to 10^5,
   heavy ties, presorted and reversed input.
3. **`sort_parity_fuzz_test`** (Phase 2, `fuzz/`): `DataGen` datasets (every
   type, narrow domains so keys tie, the special doubles), 1 to 3 keys in every
   direction and NULL placement, ColRef and computed, over `ModeDifferential`'s
   layouts plus 8 partitions, SmallN and K-way sizes, spill at one byte: the new
   `SortExec` equals `BoxedSortExec` row for row, ties included.
   `BoxedSortExec` (`fuzz/oracle/`) is today's `SortExec` copied whole, with its
   own one-row batch for the `private[exec]` `RowView`. From Phase 4 the oracle
   is the stable sort of the input read partition by partition and
   `BoxedSortExec` goes. `TopNExec` equals that stable sort's first `n`.
4. **The suites that hold sort answers already**: the sort, spill-stress and
   top-n tests, `SqlEngineTest`; the mode-differential, metamorphic, sharded
   and plan round-trip fuzzers (whose guards put DATE, TIMESTAMP, BINARY and
   DECIMAL at ORDER BY keys with their breaking pairs); `slt_test`; the whole
   suite under each `.bazelrc` config (`batch1` and `batch2` cross every chunk
   boundary); both campaigns at `FUZZ_SEEDS=20000` (docs/testing.md).

## Memory

| Per buffered row | Today (boxed) | Typed |
|---|---|---|
| this query (STRING shared, FLOAT) | ~50 bytes | ~12 (4 reference, 4 float, a null bit, 4 `perm`); 16 while `IndexSort`'s aux array lives |
| two BIGINTs | ~80 | ~20; 24 while sorting |
| `Spill.estimateBytes` says | 172 / 16 | unchanged until Phase 5 |

132M rows of this query hold ~1.6 GB typed against ~6.6 GB boxed: still a
spill at 2 GB, an in-memory sort at 4 GB. Phase 2 keeps the flush rule, so runs
end where they do today (~393K rows, holding ~5 MB, not ~20), and a sort of
fixed-width columns holds about its threshold (within 1.5×: `perm` and null
bits go uncounted), where today it holds five times it. Phase 5 counts what a
run holds (Q2): chunk arrays by capacity, a reference's payload once per run of
the same reference (`estimateBytes`' sizes), computed keys, `perm`, aux. This
file is ordered by `market_id`, as far as its first rows show, so a run holds
4M-5M rows: 3 or 4 a partition, ~30 in all, against ~336. A disk cursor holds
the row group it reads (`ParquetPartitionIterator.loadNextGroup`, up to
`ParquetWriter.DefaultRowGroupSize`, 32 MB), so the merge holds about what it
does today; Phase 5 measures peak RSS before shrinking runs' row groups.

## Decisions for the user

The work proceeds on each recommended answer unless the user says otherwise.

1. **Should the merge break ties by cursor order?** Recommended: yes, Phase 4.
   Cursors are the partitions in order, each one's runs as flushed and its tail
   last, so every path returns the stable sort of the input read partition by
   partition, as SmallN and `TopNExec` do, and spill output equals non-spill
   output row for row (the scaladoc's claim, true at last). Only rows tied on
   every key move, on the K-way path, where the heap's shape orders them today;
   SQL leaves that open, the fuzzers compare it as a multiset, slt's `nosort`
   queries hold total orders. A total order also lets a loser tree replace the
   heap (log2 K comparisons a row, not up to twice that). If no, Phase 5 moves
   tied rows under spill, as moving the threshold does today.
2. **Should a sort count what it holds?** Recommended: yes, in `SortExec` only
   (Memory above), after Phase 4 since it moves run boundaries;
   `Spill.estimateBytes` stays for the other breakers.
3. **Normalized prefixes?** Recommended: gated. Phase 6 lands only if, after
   Phase 5, JFR puts `KeyOrder` above ~30% of the full sort's CPU or JMH shows
   1.5× on its shapes (one fixed-width key, a string key, two keys) with none
   slower; otherwise the plan stops at Phase 5, its numbers recorded here.
4. **Is `WindowExec` in scope?** Recommended: no, a follow-up plan. Its keys
   are already evaluated once a batch (§5b); its rows, key arrays and the
   `Array[Integer]` it sorts box, and its frames, peer test and RANGE offsets
   read boxed keys (`frameBounds`, `sameKey`, `shifted`). The follow-up reuses
   `KeyOrder` and `IndexSort`; here it keeps `SortKey.compare` and `Ops.cmp`,
   which `sort_key_order_test` holds equal to the typed form.
5. **A typed twin of `SortKey.compare`?** conventions.md says never to
   re-implement a null-aware comparison at a new sort site. Recommended: yes,
   one rule in two forms: `SortKey` builds its `KeyOrder`, so direction and
   NULL placement stay in one class; each type's order lives once in `Ops`;
   the test holds the forms equal; conventions.md names both, as it names
   `Ops.cmp` and `Ops.floatCompare`.

## Work, in landable commits

Every commit: `bazel test //...` green; the jaffle-shop jar 15/15 with every
validation passing; the polymarket jar on README's pattern (14 Succeeded, 2
ValidationFailed, 1 Skipped, exit 0); every doc it makes stale updated in it.
Numbers go in commit messages, not docs (plans/perf/README.md).

**Phase 0: the bench and the baseline.** `SortExecBench` in `benchmarks/micro`:
the real `SortExec` and `TopNExec` over 8 in-memory partitions, N of 1M and 4M;
shapes: this query's (a FLOAT key DESC, a third 0.0, a STRING payload from 13K
strings of 66 characters), one BIGINT key, one STRING key, two keys (INT ASC,
STRING DESC NULLS FIRST, 5% NULLs); spill off, and on at 16 MiB. Run it, the
measurements below and a JFR recording at the base commit. Docs:
benchmarking.md's inventory.

**Phase 1: the typed order, the index sort, the column primitives.**
`KeyOrder`, `SortKey.order`, the `Ops` primitives, `IndexSort`, the range copy
and gather in `core`; no operator uses them yet. Tests: `sort_key_order_test`,
`index_sort_test`, a `core` test of both primitives over every vector type
with NULLs, `NullType`, DECIMAL and BINARY. Docs: architecture §5 and module
map; conventions.md's ordering rule (Q5).

**Phase 2: `SortExec` on typed runs.** Chunks, `IndexSort`, the cursors'
`keyVector`/`row`, gathered runs with computed keys, the `PriorityQueue` over
`KeyOrder`, the SmallN path as a merge whose ties go by cursor index (equal to
concatenating and sorting stably), the same flush rule. `rowOrdering` and the
unused `nKeys` in `mergeEmit` (plans/testing/07) go; `SortExec.batches` stays
for `TopNExec` until Phase 3. Tests: `sort_parity_fuzz_test`; a
`sort_memory_test` (`-Xmx256m`): 4M rows of (INT, BIGINT) made as read, sorted
and drained, order and a checksum asserted, ~300 MB boxed, with
`assumeTrue(DefaultCapacity >= 1024)` as docs/testing.md asks. Docs:
architecture §2 (`RowBuf` no longer serves sorts), §2c, §5a, §5b; gotchas.md
(the no-codegen bullet, a one-row sort evaluates its key, a `RAND()` key is
read once); testing.md; `RowView`'s scaladoc; plan 01, plans/perf/README.md.

**Phase 3: `TopNExec` on typed slots.** As designed, dropping `topN`'s `RAND()`
gate and `SortExec.batches`. Tests: `top_n_exec_test` unchanged (its oracle is
positional), `top_n_memory_test`, top-n cases over every type in
`sort_parity_fuzz_test`, a planner case for `SELECT DISTINCT ... ORDER BY
RAND() LIMIT` (without DISTINCT the binder makes `RAND()` a column). Docs:
architecture §2e (and its `RAND()` sentence), README Limitations' numbers.

**Phase 4 (Q1): a stable merge.** Ties by cursor index, a loser tree for the
`PriorityQueue`, the SmallN path kept only if the bench shows it faster below
4096 rows. Tests: the stable-sort oracle; `sort_exec_test` and
`sort_exec_spill_test` assert positions over tied keys. Docs: `SortExec`'s and
`TopNExec`'s scaladocs, architecture §2e, gotchas.md's multiset bullet (layouts
read rows in different orders, so ties stay multisets across layouts, while
spill on and off agree row for row), testing.md.

**Phase 5 (Q2): count what the sort holds.** As in Memory; measure runs
written, peak RSS and wall time of the full sort. Docs: architecture §2c,
README Limitations.

**Phase 6 (Q3, gated): normalized prefixes.** Computed per chunk and per batch
read back, so the spill format stays. Tests: a prefix property in
`sort_key_order_test` (different prefixes order as `KeyOrder` does; equal exact
prefixes tie), the suites above. Docs: architecture.

## Measurements

At the base commit, then after Phases 2, 3 and 5 (and 6), with `JDK21` the
remotejdk21 under `$(bazel info output_base)/external/` (the system `java` is
17), `OB=$HOME/Downloads/archive/orderbook/orderbook_2026-03-26.parquet`:

```bash
bazel build //:transformer_deploy.jar
/usr/bin/time -l "$JDK21/bin/java" -Xmx2g -jar bazel-bin/transformer_deploy.jar query --table ob=$OB \
  "SELECT market_id, change_size FROM ob ORDER BY change_size DESC LIMIT 3"
/usr/bin/time -l "$JDK21/bin/java" -Xmx2g -jar bazel-bin/transformer_deploy.jar query --spill --table ob=$OB \
  "SELECT market_id, change_size FROM ob ORDER BY change_size DESC"
/usr/bin/time -l "$JDK21/bin/java" -Xmx2g -jar bazel-bin/transformer_deploy.jar query --table ob=$OB \
  "SELECT COUNT(market_id), COUNT(change_size) FROM ob"   # the scan alone
```

plus `-XX:StartFlightRecording=filename=sort.jfr,settings=profile` on the full
sort, read with `$JDK21/bin/jfr view hot-methods sort.jfr`, and
`SortExecBench`. Expected, as hypotheses to verify:

- **H1, top-n:** within 1.3× of the scan alone (from 3.0 s), peak RSS no higher.
- **H2, full sort:** 5× faster after Phase 2 with the same ~336 runs; after
  Phase 5, with ~30 runs, under 40 s, possibly under DuckDB's 95 s, since a run
  file dictionary-encodes `market_id`'s ~13K values. The one-thread merge is
  then the largest cost (JFR), plan 04 Phase 7's opening.
- **H3, memory:** `sort_memory_test` green in 256 MB; the full sort without
  `--spill` completes at `-Xmx4g` (boxed, it needs about 7 GB).
- **H4, JMH:** 10× or more on one FLOAT or BIGINT key, 5× on the STRING and
  two-key shapes.

## Docs to update

- `README.md`: Limitations' "A sort holds every row it sorts" (its numbers).
  `site/` has no sort figure; it changes only if the polymarket job's do.
- `docs/architecture.md`: module map, §2, §2c, §2e, §5, §5a, §5b.
- `docs/conventions.md`: the ordering rule's two forms; a breaker holds rows in
  typed chunks and sorts `Int`s with `IndexSort`, never an `Array[Integer]`.
- `docs/extending.md`: a new `DataType` adds its `KeyOrder` case (and prefix,
  after Phase 6) and its pool in `sort_key_order_test`.
- `docs/gotchas.md`, `docs/testing.md`, `docs/benchmarking.md` (`SortExecBench`;
  `SortComparatorBench` copies the boxed comparator without its `RowView`s:
  rewrite it over `KeyOrder`), `docs/code-map.md` if `SortExec.scala` passes
  ~800 lines; plans/perf/README.md (row 15) and plan 01's status.

## Risks

- **The typed order drifts from the boxed one.** Held by `sort_key_order_test`
  and Phase 2's row-for-row parity; a new type needs both.
- **Eager keys.** A one-row sort never compared, so its key never ran; from
  Phase 2 a key failing on its one row fails, as over two rows and in
  `TopNExec` today. No new error kind (`CleanRejection.DataErrors` unchanged).
- **Chunks under `batch1`** cost a batch object a row, more than a boxed row;
  only tests run there, and the memory test assumes the default.
- **Megamorphic calls.** `IndexSort` reaches `KeyOrder` through a virtual call;
  still far cheaper than two `RowView`s, and Phase 6 is the remedy if JFR asks.
- **Not fixed here:** the one-thread merge; a spilled string with half a
  surrogate pair reads back as `?` and a decimal off its declared scale reads
  back at it (gotchas.md), as today.
- **Concurrent sessions** edit this tree: develop in a git worktree (with a
  local `.bazelignore` naming `.claude` if it lives under `.claude/worktrees`).

## Launch prompt

```
Read plans/perf/15-typed-sort-keys.md and implement it in phase order, one
commit per phase.

Use max effort. Honor CLAUDE.md: bazel test //... green, the jaffle-shop jar
15/15 with every validation passing, the polymarket jar on its README pattern,
docs in the same commit. Peer sessions edit this tree: develop in a git
worktree. Take Phase 0's measurements at the base commit before changing code.
Confirm the user's answers before Phase 4 (Q1), Phase 5 (Q2) and Phase 6 (Q3);
Phases 0-3 need none. WindowExec stays out (Q4) unless the user says otherwise.

Parity is the first risk: land sort_key_order_test and index_sort_test before
any operator change, and keep Phases 2 and 3 row for row equal to today's
operator (BoxedSortExec), ties included. Every type's order comes from Ops:
KeyOrder calls Ops.floatCompare and the new Ops primitives, never a raw < on
floats or String.compareTo outside Ops. No new dependency (no fastutil).

Stop and ask before: (a) changing the spill file beyond appending computed key
columns, (b) changing Spill.estimateBytes for another operator, (c) touching
WindowExec, or the planner beyond topN's RAND() gate, (d) landing Phase 6
without its measured gain, (e) any change to which rows a query returns, or
their order, outside Phase 4.

Report: the three polymarket measurements (wall time, peak RSS) and
SortExecBench before and after each phase, and the JFR hot methods of the full
sort at the base commit and after Phase 5.
```
