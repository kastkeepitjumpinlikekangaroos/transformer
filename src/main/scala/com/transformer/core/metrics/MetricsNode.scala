package com.transformer.core.metrics

/** Mutable per-operator counter container, with thread-safe per-batch
  * increments via [[java.util.concurrent.atomic.LongAdder]].
  *
  * One [[MetricsNode]] exists per operator (NOT per partition). The
  * [[MeteredIterator]] for each partition of that operator holds a direct
  * reference and bumps the adders on `hasNext` / `next` boundaries — adders
  * are lock-free and contention-tolerant, so multi-partition operators
  * fanned out across the scheduler stay correct without per-partition
  * sharding of the node tree.
  *
  * Why a single node per operator (not per partition): the snapshot consumers
  * — `_perf.json` and the macro-bench aggregator — want the accumulated
  * counters per operator, summed across partitions, so one node that every
  * partition's [[MeteredIterator]] bumps is enough. If per-partition
  * breakdowns are ever needed, the simplest extension is one MetricsNode per
  * (operator, partition): operators already thread their `MetricsNode` through
  * their constructor, so adding a `partition` accessor to [[MeteredIterator]]
  * would be a small follow-up.
  *
  * Counter discipline:
  *
  *   - Custom counters live in [[counters]], a fixed-size
  *     `Array[LongAdder]` sized at construction from the operator's
  *     compile-time counter list. Operators that declare counters (e.g.
  *     `HashAggregateExec` with `groupCount`, `mergeNanos`, `spillEvents`,
  *     ...) get one slot per name; operators without custom counters get an
  *     empty array and only the framework-managed row/batch counters below.
  *   - Counter *names* live in [[counterNames]]; per-operator unit tests
  *     assert `counterNames.length == counters.length` to catch index drift
  *     when a counter is added.
  *   - Increments use `node.counters(IdxBuildNanos).add(delta)`; the
  *     `Idx<Name>: Int` constants are `final val`s in the operator's
  *     companion object so the JIT can const-fold them.
  *   - Allocations are forbidden on the per-row hot path. Counter-name
  *     strings live only here and in the serialized `OperatorMetrics`, never
  *     in the inner loop. `LongAdder.add` is allocation-free in steady
  *     state.
  *
  * @param id
  *   Stable per-query operator id assigned at planner time. Order-preserving
  *   pre-order walk so the tree shape is reproducible across runs.
  * @param name
  *   Short operator name used in `_perf.json` and the GUI (e.g.
  *   `"ScanExec"`, `"FilterExec"`, `"HashAggregateExec"`). Comes from
  *   `getClass.getSimpleName` at wrap time so future operator subclasses
  *   are picked up without further wiring.
  * @param partition
  *   Reserved for future per-partition observation; currently always 0 — the
  *   node's counters aggregate across every partition.
  * @param counterNames
  *   Compile-time counter names supplied by the operator (empty for operators
  *   without custom counters). Size-locked at construction.
  */
final class MetricsNode(
    val id: Int,
    val name: String,
    val partition: Int,
    val counterNames: Array[String]) {

  import java.util.concurrent.atomic.LongAdder

  /** Per-operator custom counters as adders, one slot per [[counterNames]]
    * entry (empty for operators without custom counters). Operators bump
    * entries via `counters(IdxBuildNanos).add(delta)`. */
  val counters: Array[LongAdder] = Array.fill(counterNames.length)(new LongAdder)

  /** Number of input rows the operator pulled from its child(ren). Same
    * semantic as [[rowsOut]] at the iterator-wrap level — the wrapped
    * iterator IS the operator's output, so every emitted row counts as both
    * input (from the parent's perspective) and output (from this operator's
    * perspective). Maintained by [[MeteredIterator]]. */
  val rowsIn: LongAdder = new LongAdder

  /** Number of output rows the operator emitted. */
  val rowsOut: LongAdder = new LongAdder

  /** Number of input batches pulled from the child iterator. */
  val batchesIn: LongAdder = new LongAdder

  /** Number of output batches emitted. */
  val batchesOut: LongAdder = new LongAdder

  /** Total nanoseconds spent inside this operator's `hasNext`/`next` calls,
    * summed across every iteration. */
  val wallNanos: LongAdder = new LongAdder

  /** Child operator nodes in pre-order. Set once at planner time; never
    * mutated after the iterator starts. */
  var children: Vector[MetricsNode] = Vector.empty

  /** Take an immutable snapshot of this node + its children. Adders return
    * the sum across all incrementing threads; the read is itself not atomic
    * across counters, so consumers may see a snapshot where rowsIn and
    * batchesIn were taken at slightly different instants in time. For the
    * intended consumers (`_perf.json` written after the iterators have been
    * drained) this is harmless because no more increments are in flight. */
  def snapshot(): OperatorMetrics = {
    val cs = new Array[Long](counters.length)
    var i = 0
    while (i < counters.length) {
      cs(i) = counters(i).sum()
      i += 1
    }
    OperatorMetrics(
      id = id,
      name = name,
      partition = partition,
      rowsIn = rowsIn.sum(),
      rowsOut = rowsOut.sum(),
      batchesIn = batchesIn.sum(),
      batchesOut = batchesOut.sum(),
      wallNanos = wallNanos.sum(),
      counters = cs,
      counterNames = counterNames,
      children = children.map(_.snapshot())
    )
  }
}
