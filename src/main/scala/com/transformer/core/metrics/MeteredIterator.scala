package com.transformer.core.metrics

import com.transformer.core.ColumnarBatch

/** JIT-friendly wrapper that meters a child [[Iterator]] of
  * [[ColumnarBatch]]es.
  *
  * Why a `final class` and not `new Iterator { ... }`:
  *
  *   - The HotSpot JIT inlines small, monomorphic, `final` callees more
  *     aggressively than anonymous subclasses of `Iterator`. The pipeline
  *     operators iterate per-batch; the per-call overhead of an un-inlined
  *     `hasNext` / `next` dispatch shows up at scale.
  *   - The class is `final` so the JIT can hoist devirtualization and skip
  *     the dispatch table lookup.
  *
  * Hot-path discipline:
  *
  *   - One `System.nanoTime` pair per `hasNext`/`next` call. No allocation
  *     inside the loop.
  *   - Row counts read `numRows` once per batch (one virtual call into
  *     `ColumnarBatch`, which exposes it as a plain field-backed method).
  *   - Custom counters are bumped by the *operator*, not by this iterator.
  *     The iterator only touches the framework-managed counters on
  *     [[MetricsNode]] ([[MetricsNode.rowsIn]], etc.).
  *
  * Failure semantics: if the child iterator throws, the metrics captured up
  * to the failing batch survive — the wrapping `try` in the operator's
  * `execute(p)` propagates the throwable but the partially-updated
  * [[MetricsNode]] is still visible to the snapshot taken after the query.
  * `_perf.json` consumers should expect partial counts when a task fails.
  *
  * @param inner Underlying iterator emitted by the operator's `execute(p)`.
  * @param node  Metrics container for this operator-partition. Increments
  *              [[MetricsNode.rowsIn]], [[MetricsNode.rowsOut]],
  *              [[MetricsNode.batchesIn]], [[MetricsNode.batchesOut]] and
  *              [[MetricsNode.wallNanos]] on every batch.
  */
final class MeteredIterator(inner: Iterator[ColumnarBatch], node: MetricsNode)
    extends Iterator[ColumnarBatch] {

  // We intentionally do NOT cache hasNext or buffer batches — the contract of
  // Iterator[ColumnarBatch] in this codebase is single-pass and pull-driven;
  // adding a buffer here would change semantics for downstream operators that
  // also pre-fetch.

  def hasNext: Boolean = {
    val t0 = System.nanoTime()
    val h = inner.hasNext
    node.wallNanos.add(System.nanoTime() - t0)
    h
  }

  def next(): ColumnarBatch = {
    val t0 = System.nanoTime()
    val b = inner.next()
    val dt = System.nanoTime() - t0
    node.wallNanos.add(dt)
    val n = b.numRows.toLong
    // Both rowsIn and rowsOut at the iterator-wrap level record what THIS
    // iterator emitted — equivalently, what its underlying operator
    // produced. A row-discarding operator like FilterExec shows up as a
    // *difference* between its rowsOut and its child's rowsOut, NOT as a
    // discrepancy between rowsIn and rowsOut on the filter itself. The
    // child's own MeteredIterator independently records what *it* emitted,
    // so consumers compute "rows discarded by filter" as
    // `child.rowsOut - filter.rowsOut`. Sub-plan 2's per-operator custom
    // counters add explicit "discarded" counters where the difference
    // matters for diagnostics.
    node.rowsIn.add(n)
    node.rowsOut.add(n)
    node.batchesIn.increment()
    node.batchesOut.increment()
    b
  }
}
