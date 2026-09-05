# Plan 01: Spill files can't round-trip duplicate column names

> Status: DONE · Tier: correctness bug · Effort: ~2-4 days · Risk: low-medium
>
> Surfaced by the metamorphic fuzzer (`MetaModeDifferential`). Confirmed with a
> live stack trace (below). Fixed by writing spill files with positional column
> names (`Spill.positionalSchema`); the `MetaModeDifferential` join spill skip
> is removed and the metamorphic + sharded campaigns are green at 10k seeds.

## Summary

Every spill-capable breaker writes its working set to a temp parquet file and
reads it back. The parquet column model is keyed by **name-path**, not
position. When the spilled schema contains two columns with the same name, the
second one's pages resolve to `null` on read-back and the engine NPEs. Joins
produce duplicate-named schemas as a matter of course (`a JOIN b` over two
relations that share a column name yields `[k, v, k, v]`), and so do
name-colliding `GROUP BY` keys, so this is reachable from ordinary SQL the
moment spill is enabled.

This is the bug behind `docs/gotchas.md`'s "Grace-hash join spill spill crashes
when the probe rows are join-derived" entry — but the root cause is **not**
join-specific. It is a property of the shared spill-to-parquet path, and it
affects `HashJoinExec`, `HashAggregateExec`, `SortExec`, `DistinctExec`, and
`WindowExec` alike.

## Reproduction (confirmed)

`spill_threshold_bytes = 1` forces a flush on every batch:

```
a(k,v) = {(1,1)},  b(k,v) = {(1,9)}
SELECT t2.v FROM a t0 JOIN b t1 ON t0.k = t1.k JOIN b t2 ON t1.k = t2.k
```

The inner `a JOIN b1` has output schema `[k, v, k, v]` (duplicate `k`, `v`).
The outer join, under spill, routes that inner result through `bucketSide` →
parquet → `probeBucket` → `ParquetReader.fromPath`, which NPEs:

```
java.lang.NullPointerException: Cannot invoke
  "org.apache.parquet.column.page.DataPage.accept(...)" because "page" is null
  at org.apache.parquet.column.impl.ColumnReaderBase.readPage(ColumnReaderBase.java:686)
  at org.apache.parquet.column.impl.ColumnReadStoreImpl.getColumnReader(ColumnReadStoreImpl.java:80)
  at com.transformer.read.parquet.ParquetPartitionIterator.$anonfun$loadNextGroup$2(ParquetReader.scala:435)
  at com.transformer.read.parquet.ParquetPartitionIterator.loadNextGroup(ParquetReader.scala:435)
  at com.transformer.read.parquet.ParquetPartitionIterator.hasNext(ParquetReader.scala:458)
  at com.transformer.sql.exec.HashJoinExec.probeBucket(JoinExec.scala:880)
  at com.transformer.sql.exec.HashJoinExec.processBucketPair(JoinExec.scala:797)
  at com.transformer.sql.exec.HashJoinExec.executeGraceHash(JoinExec.scala:617)
```

Note the NPE is **not** the `pages == null` case that `loadNextGroup`
(ParquetReader.scala:421-425) already guards. `readNextRowGroup()` returns a
non-null `PageReadStore` with the right row count; the failure is one level
deeper, when `ColumnReadStoreImpl.getColumnReader` builds the per-column reader
for the *second* column sharing a name and finds its first `DataPage` is
`null`. The existing guard cannot catch it.

## Root cause

Two facts combine:

1. **Parquet addresses columns by path.** `ParquetReader.scala:362-365` builds
   `columnDescriptors` from `effectiveMessageType.getColumns`, then
   `loadNextGroup` (ParquetReader.scala:435) does
   `columnReaders = columnDescriptors.map(crs.getColumnReader)`.
   `ColumnReadStoreImpl.getColumnReader` and the underlying `PageReadStore`
   look up pages by the descriptor's name-path. Two columns named `k` share the
   path `["k"]`; the first reader drains that path's pages, the second gets
   nothing → `null` first page → NPE.

2. **The writer copies logical field names straight through.** `ParquetWriter`
   names each output column `schema.fields(i).name`
   (ParquetWriter.scala:310) and `ParquetSchema.toMessageType` emits one
   parquet field per `Schema` field, so a duplicate-named `Schema` becomes a
   parquet `MessageType` with duplicate paths. The write "succeeds" but
   produces a file the reader cannot disambiguate.

The operators never actually *need* the spilled names — every spill read path
addresses columns by **index** and reinterprets types from the operator's own
logical schema. The names in the spill file are dead weight that happens to be
load-bearing in the worst way.

## Scope — every spill path, not just the join

| Operator | Spill write site | How a duplicate name arises |
|----------|------------------|------------------------------|
| `HashJoinExec` | `bucketSide` → `new TParquetWriter(file, plan.outputSchema, …)` (JoinExec.scala:648-662) | a build/probe **input** that is itself a join → `[k,v,k,v]`. **Confirmed.** |
| `HashAggregateExec` | `writeCodecMap` → `ParquetWriter.writeAll(file, spillSchema(groupKeySchema, …), …)` (AggregateExec.scala:1567); `spillSchema` keeps the original key names (AggregateExec.scala:1515-1518) | name-colliding GROUP BY keys, e.g. `GROUP BY t0.k, t1.k` → key cols `[k, k, _agg0…]`. **Confirmed by code.** |
| `SortExec` | `ParquetWriter.writeAll(file, child.outputSchema, …)` (SortExec.scala:141-143) | `child` is a join → duplicate names. |
| `DistinctExec` | `DistinctSpiller.writeSet(file, codec, child.outputSchema, …)` (DistinctExec.scala:176-177) | `child` is a join → duplicate names. |
| `WindowExec` | `new TParquetWriter(file, child.outputSchema, …)` (WindowExec.scala:176-177) | `child` is a join → duplicate names. |

So `docs/gotchas.md`'s "needs join-DERIVED rows under spill" is the *symptom of
the first row only*. The general statement is: **any spilled schema with two
equal field names breaks.** The join is simply the easiest generator of one.

## Proposed fix — positional spill schemas

Names in spill files carry no information the reader uses, so strip the
ambiguity at the source: write spill files with **positional, guaranteed-unique
field names**, and keep reading by index (already the case).

### Core helper

Add to `core/Spill.scala`:

```scala
/** A copy of `schema` with field names replaced by positional, collision-free
  * placeholders (`_c0`, `_c1`, …) and types/order preserved. Spill files are
  * read back by column INDEX, never by name, so the original names are not
  * needed on disk — and parquet's by-name column model cannot round-trip two
  * columns that share a name (a join output like `[k, v, k, v]`, or
  * name-colliding GROUP BY keys). Writing positional names sidesteps that. */
def positionalSchema(schema: Schema): Schema =
  Schema(schema.fields.iterator.zipWithIndex.map {
    case (f, i) => Field(s"_c$i", f.dataType)
  }.toVector)
```

### Apply at every write site

Wrap the schema passed to each spill writer in `Spill.positionalSchema(...)`:

- `JoinExec.scala` `bucketSide` (~line 648-662): the writer's `schema`.
- `AggregateExec.scala` `spillSchema` (line 1515): rename the **key** columns
  positionally too (`_k$i`), not just the `_agg$i` state columns. The read path
  `foldCodecSpillFiles` / `foldLongSpillFiles` already addresses key columns by
  index `[0, nKeys)` (see the comment at AggregateExec.scala:1575-1580), so
  positional names are safe. (The long-key spill path has a single key column,
  so it can't collide — but uniformity is cheaper than a special case.)
- `SortExec.scala` (~line 141): the writer's `schema`.
- `DistinctExec.scala` (~line 176 and the `writeAll` at ~280): the writer's
  `schema`, and the `DistinctSpiller.writeSet` schema arg.
- `WindowExec.scala` (~line 176): the per-bucket writer's `schema`.

### Verify (and, where needed, fix) the read paths

This is the part that needs care. Each operator reads the spill file back with
`ParquetReader.fromPath`, whose schema now reports `_c0…_cN` with the correct
types. Two things to confirm per operator:

1. **All column access is by index.** Grep each read-back loop for `.column(c)`
   / `ColRefExpr(index, …)` (index-based — fine) vs any name lookup like
   `schema.fieldIndex("…")` (would break). The join paths
   (`loadBuildFromBucket`, `probeBucket`, `streamProbeAsUnmatched`) are already
   index-only; verify the others.
2. **Re-labelling before emit.** If an operator streams spill-read batches
   *directly* to its parent (rather than copying into an `outputSchema`-typed
   batch), the parent would see `_c0…` names. Most operators build a fresh
   `outputSchema` batch, but `SortExec`'s k-way merge over runs and
   `DistinctExec`'s merge may forward read batches — confirm, and if so, relabel
   the batch's schema to the logical one (cheap: `ColumnarBatch`'s schema is
   metadata; either construct a relabelled view or set it). A focused
   `Spill`-level read helper that returns batches already wrapped in the logical
   schema would make this uniform and is worth considering if more than one
   operator forwards.

### Why not fix it in the parquet reader instead?

The reader can't disambiguate after the fact — duplicate paths are
fundamentally ambiguous in a parquet `PageReadStore`. The write side is the only
place with enough information (column position) to produce a recoverable file.
A position-indexed reader would still need the file to carry distinct paths.

## Files to touch

- **Modified**: `core/Spill.scala` — add `positionalSchema`.
- **Modified**: `sql/exec/JoinExec.scala` — `bucketSide` writer schema.
- **Modified**: `sql/exec/AggregateExec.scala` — `spillSchema` key-column
  naming.
- **Modified**: `sql/exec/SortExec.scala` — spill writer schema; verify merge
  read path relabels.
- **Modified**: `sql/exec/DistinctExec.scala` — spill writer + `DistinctSpiller`
  schema; verify merge read path.
- **Modified**: `sql/exec/WindowExec.scala` — per-bucket writer schema.
- **Modified**: `src/test/.../fuzz/oracle/MetaModeDifferential.scala` — delete
  the `hasAnyJoin` spill skip (lines ~59-82) and its doc comment; spill now runs
  for join queries.
- **Modified**: `docs/gotchas.md` — replace the "Grace-hash join spill crashes
  when the probe rows are join-derived" entry with the resolved, generalized
  statement (or remove it).
- **Modified**: `docs/testing.md` — note the new regression coverage and that
  `MetaModeDifferential` no longer skips spill for joins.
- **Modified**: `docs/architecture.md` — the spill section should state that
  spill files use positional column names.

## Tests

Add a focused regression per affected operator — the cheapest reproductions,
all under `spillThresholdBytes = 1`:

- **`HashJoinSpillTest`**: a join whose probe (and, separately, build) input is
  itself a join with a duplicate-named output schema, under spill, equals the
  non-spill multiset. (This is exactly the temp repro used to confirm the bug —
  see the stack trace above.)
- **`HashAggregateSpillTest`**: `GROUP BY` on two columns that render to the
  same name (the duplicate-key spill schema), under spill, equals non-spill.
- **`SortExecSpillTest` / `DistinctExecSpillTest` / `WindowExecSpillTest`**: a
  child plan with a duplicate-named output schema (an `InMemoryPlan` whose
  `Schema` has two same-named fields is enough — no real join needed), under
  spill, equals non-spill.
- **End-to-end**: the SQL repro above through `SqlEngine` with
  `ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))`
  returns the right rows instead of throwing.

The decisive gate: **delete `MetaModeDifferential`'s `hasAnyJoin` skip and run
the metamorphic + sharded campaigns.** The spill mode then exercises join
queries on every seed; green there is the real proof.

## Risks

1. **A read path that looks up a spilled column by name.** The whole fix
   assumes index-only reads. Mitigation: grep every read-back loop; the
   per-operator regressions catch a missed one immediately.
2. **A forwarded spill batch leaking `_c0…` names downstream.** Mitigation: the
   re-labelling check above; assert the *final* operator output schema in each
   regression (not just the row multiset).
3. **`AggStateSerde` key decode keyed by name.** `codec.decode` writes group-key
   columns by index into the output batch, so it should be name-agnostic —
   verify against `KeyCodec` before assuming.
4. **Other writers reusing `positionalSchema` files.** Spill files are private
   to one operator instance and deleted on `OperatorSpillDir.close()`; no
   external consumer reads them. Low risk, but don't let `positionalSchema`
   leak into the non-spill `ParquetWriter` callers (real output files must keep
   their real column names).

## Suggested phases

1. **Phase 1 — helper + join.** Add `Spill.positionalSchema`; apply to
   `HashJoinExec`; add the join-of-join regression; confirm the captured NPE is
   gone. Smallest end-to-end proof.
2. **Phase 2 — the other four operators.** Apply to Sort / Distinct / Window /
   HashAggregate (the `spillSchema` key rename); verify each read path; add one
   regression apiece.
3. **Phase 3 — un-gate the fuzzer + docs.** Remove the `hasAnyJoin` spill skip,
   run the metamorphic + sharded campaigns to a high seed count, update
   `docs/gotchas.md` / `docs/testing.md` / `docs/architecture.md`.

## Launch prompt

```
Read plans/bugfixes/01-spill-duplicate-column-names.md and implement it
end-to-end. Use max effort.

The bug: spill-capable breakers (HashJoin, HashAggregate, Sort, Distinct,
Window) write their working set to temp parquet and read it back, but parquet
addresses columns by name and the engine writes the logical schema's names
verbatim. When a spilled schema has two same-named columns (a join output like
[k,v,k,v], or name-colliding GROUP BY keys), the second column's pages come back
null on read and the engine NPEs at ParquetReader.scala:435. Confirmed live;
the stack trace is in the plan.

The fix: write spill files with positional, collision-free column names
(Spill.positionalSchema) since spill reads address columns by INDEX, never by
name. Apply at every spill write site; the AggregateExec.spillSchema helper must
rename the GROUP BY key columns positionally too. Then VERIFY each operator's
read-back path is index-only and does not forward spilled `_c0…` names to its
parent (relabel if it does).

Honor CLAUDE.md: no new deps, bazel test //... green, jaffle_shop deploy jar
15/15. Add a duplicate-named-schema spill regression to EACH operator's
*SpillTest. The decisive gate: delete MetaModeDifferential's `hasAnyJoin` spill
skip so the metamorphic + sharded fuzzers run spill on join queries, and run the
campaigns. Update docs/gotchas.md (the entry moves from known-bug to fixed),
docs/testing.md, docs/architecture.md.

Do NOT change the non-spill ParquetWriter callers — real output files keep their
real column names; positional naming is spill-only.
```
