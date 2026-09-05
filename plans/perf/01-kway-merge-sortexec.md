# Plan 01: K-way merge in `SortExec`

> Status: not started · Tier: 2 (quick win) · Effort: ~1 day · Risk: low

## Goal

Replace `SortExec`'s "sort partials, concat, then `Arrays.sort` over the whole
concatenation" pattern with an actual K-way heap merge over the already-sorted
partials. Same output; fewer comparator calls; less peak garbage.

## Why it matters

`Expr.eval` is boxed `Any` per row. The sort comparator
(`SortExec.scala:59-82`) calls it twice per comparison, once per row pair, per
sort key. Comparator cost dominates wall time on `SortExec`. Halving the
number of comparator calls roughly halves total sort CPU.

For K already-sorted partials of total size N, a heap merge is `O(N log K)`
comparisons. The current code does the partial sort (`O(N/K · log(N/K))` per
partition × K partitions = `O(N log(N/K))`) **and then** a global
`Arrays.sort` over the concat'd array (`O(N log N)`). The global re-sort
throws away the per-partition order. With K=12 (typical default
`Scheduler.parallelism` of `2 × cores` on an 8-core box):

| Step                 | Current               | Proposed         |
| -------------------- | --------------------- | ---------------- |
| Per-partition sort   | `O(N log(N/12))`      | unchanged        |
| Merge                | `O(N log N)` (resort) | `O(N log 12)`    |
| Total comparator ops | `~2 N log N`          | `~N log(N/12) + N log 12 ≈ N log N` |

So roughly a 2× reduction in comparator work for typical K — and that
work is exactly the boxed `Expr.eval` path, which is the dominant CPU
cost in `SortExec` today.

## Current state

`src/main/scala/com/transformer/sql/exec/SortExec.scala`:

```scala
def execute(partition: Int): Iterator[ColumnarBatch] = {
  require(partition == 0)
  val tasks = (0 until child.numPartitions).map { p => ... sortPartition(p) }
  val partials = Scheduler.submitAndAwaitAll(tasks)
  val all = mutable.ArrayBuffer.empty[Array[Any]]
  partials.foreach(p => all ++= p)                          // <-- concat
  val sorted = all.toArray
  java.util.Arrays.sort(sorted.asInstanceOf[Array[Object]], // <-- global resort
                        ord.asInstanceOf[java.util.Comparator[Object]])
  emit(sorted)
}
```

Comment at the top of the class file claims "k-way merge" — the implementation
does not actually do this.

The comparator is `SortExec.scala:59-82`. It evaluates each sort key against
each row through `Expr.eval(RowView, 0)` — a fresh `RowView` (1-row
`ColumnarBatch`) per comparison.

## Proposed design

### Algorithm

1. Each input partition still sorts locally in parallel via
   `Scheduler.submitAndAwaitAll` (no change to `sortPartition`).
2. Replace the merge phase with a `PriorityQueue<HeadRef>` of K cursors,
   where each `HeadRef` carries `(partitionIdx, rowIdxInPartition)`. Initial
   heap = head of every non-empty partial.
3. On each `poll`, append the head row to the output and advance the cursor;
   if the cursor still has rows, push the new head.
4. Stream the merged rows into `ColumnarBatch`es of `DefaultCapacity`. No
   need to fully materialize the merged array first.

### Comparator reuse

The comparator already exists (`rowOrdering` at `SortExec.scala:59`). It
takes `Array[Any]` rows. Reuse it unchanged inside the heap's
comparator — wrap `HeadRef` so the heap compares `partials(p)(rowIdx)`.

### Optional micro-optimization (deferred)

Pre-compute sort-key tuples per row during the partition sort so the
comparator compares pre-computed keys instead of re-evaluating `Expr` on
every comparison. Worth ~3-5× on top of the K-way win because boxed
`Expr.eval` is the dominant comparator cost. Recommend doing this as a
follow-up — it's orthogonal to the K-way merge itself and keeps this PR
small.

## Files to touch

- **Modified**: `src/main/scala/com/transformer/sql/exec/SortExec.scala`
- **Tests modified**: `src/test/scala/com/transformer/sql/exec/SqlEngineTest.scala`
  (existing sort tests must still pass)
- **Tests new**: a multi-partition test that confirms ordering survives
  the K-way merge across partition boundaries (today's tests may pass
  with a single input partition and miss merge bugs).

## Edge cases

1. **Empty partials**: heap initialization skips partitions with zero rows.
2. **All-empty input**: emit no batches; iterator returns `hasNext = false`
   immediately.
3. **`numPartitions == 1`**: skip the heap, return the single sorted partial
   directly (one fewer copy).
4. **NULLs in keys**: the existing comparator already handles NULL ordering
   (`SortExec.scala:69-75`). Reuse as-is.
5. **Stable sort**: `java.util.PriorityQueue` is **not stable** by default —
   ties break in unspecified order. If two rows compare equal on all sort
   keys, the heap may emit them out of input order. The current code is
   also unstable (TimSort is stable, but the concat+resort step interleaves
   partitions arbitrarily). Document this as unchanged behavior. If
   stability becomes required later, break ties on `(partitionIdx, rowIdxInPartition)`.
6. **Capacity-aware emit**: pre-allocate the output `ColumnarBatch` with
   `DefaultCapacity`; if the merged size is smaller, downsize at the end
   via `setNumRows`.

## Testing

### Correctness
- Add to `SqlEngineTest.scala` (or split into a dedicated `SortExecTest.scala`):
  - 3-partition input with overlapping ranges → assert globally sorted output.
  - Multi-key sort with ASC/DESC mix across partitions.
  - NULLs FIRST and NULLs LAST behavior, multi-partition.
  - Empty-partition input + all-empty input.
- Sort the jaffle_shop `customers` mart by `customer_id` and assert
  monotonicity (small end-to-end regression smoke).

### Performance
- Microbenchmark: sort 1M rows by a single Long key, 12 partitions.
  Current: measure. New: measure. Both should be within rounding; if the
  K-way version is slower, something is wrong.
- Polymarket end-to-end: any task with `ORDER BY` (none today, but `final_*`
  tables may use ORDER BY). Compare wall time before/after.

### Required workflow gates (per CLAUDE.md)
- `bazel test //...` green.
- `bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar && java -jar
  bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar` exits 0 with 15/15
  Succeeded.

## Risks

1. **Comparator semantics drift.** Easy to introduce subtle off-by-one in
   the heap's compare. Mitigation: keep the exact comparator from `rowOrdering`
   unchanged and wrap.
2. **Performance regression on very small inputs.** Heap setup overhead might
   beat `Arrays.sort` on N<1000. Mitigation: keep the existing path as a
   "small N" fast path (threshold like `if (totalRows < 4096) return concatAndSort()`).
3. **Iterator laziness changes downstream behavior.** Today the entire sorted
   array is materialized before the first `emit()` batch. If we lazily merge,
   downstream sees batches sooner. This should be strictly better, but verify
   that any caller that depends on "sort fully completes before next op" still
   works. (Scan: this codebase doesn't have such a caller.)

## Suggested phases

1. **Phase 1**: implement K-way merge, keep `Arrays.sort` path under a feature
   flag (`SortExec.UseKWayMerge`). Run both in tests for parity.
2. **Phase 2**: remove the flag and the old path once jaffle + polymarket pass.
3. **Phase 3 (optional, separate PR)**: pre-compute sort-key tuples per row
   during the partition sort to eliminate per-comparison `Expr.eval`.

## Docs to update (CLAUDE.md workflow)

- `docs/architecture.md` §2 — the "Pipeline breakers" paragraph mentions
  Sort; if behavior changed visibly (eg. now streams), reflect it.
- `docs/gotchas.md` — remove the implicit fiction in the SortExec
  comment if applicable; add a note about heap merge if any caller
  relies on the old shape.
- `docs/testing.md` — if a new test file is created.

## Launch prompt

```
Read plans/perf/01-kway-merge-sortexec.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy SQL deps, bazel test //... must
pass, jaffle_shop deploy jar must pass with 15/15 Succeeded. Land docs in
the same PR per CLAUDE.md "Required workflow".

Approach: implement the K-way heap merge behind a feature flag first; run
both old + new paths under existing SortExec tests for parity; remove the
flag once green. Profile with async-profiler on a 1M-row sort and a
jaffle ORDER BY path; include before/after comparator counts and wall
times in the PR description.

Spawn parallel sub-agents only for genuinely independent work (e.g. one
agent writing the microbenchmark while the main agent implements). Don't
amend commits — create new commits on hook failures.

Stop and ask before: removing the old code path, changing the comparator
interface, or touching anything outside SortExec.scala and its tests.
```
