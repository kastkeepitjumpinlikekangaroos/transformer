package com.transformer.core.metrics

/** Immutable snapshot of one operator-partition's measurements. Captured
  * once per query (in `SqlEngine.execute` after the iterators have been
  * drained) and embedded in [[QueryMetrics]] / written to `_perf.json`.
  *
  * Sub-plan 1's snapshot is intentionally narrow — `id`, `name`, the
  * iterator-level row/batch counters, and the operator's custom
  * counter array (empty in Sub-plan 1, populated in Sub-plan 2). The
  * `children: Vector[OperatorMetrics]` field carries the recursive tree
  * shape so consumers can render the plan in the GUI without re-parsing
  * the original logical/physical plan.
  *
  * @param id           Stable per-query operator id (pre-order index).
  * @param name         Short operator class name (e.g. `"FilterExec"`).
  * @param partition    Partition index this snapshot describes.
  * @param rowsIn       Total input rows the operator pulled.
  * @param rowsOut      Total output rows the operator emitted.
  * @param batchesIn    Input batches pulled.
  * @param batchesOut   Output batches emitted.
  * @param wallNanos    Wall time spent inside this operator's
  *                     `hasNext`/`next` calls (excludes child time).
  * @param counters     Per-operator custom counter values, indexed by
  *                     `counterNames`. Length 0 in Sub-plan 1.
  * @param counterNames Names of the custom counters, used as keys in the
  *                     serialized JSON. Same length as [[counters]].
  * @param children     Snapshots of child operators (pre-order).
  */
final case class OperatorMetrics(
    id: Int,
    name: String,
    partition: Int,
    rowsIn: Long,
    rowsOut: Long,
    batchesIn: Long,
    batchesOut: Long,
    wallNanos: Long,
    counters: Array[Long],
    counterNames: Array[String],
    children: Vector[OperatorMetrics]
) {

  /** Number of operator nodes including self. Useful for sanity checks in
    * tests. */
  def nodeCount: Int = 1 + children.iterator.map(_.nodeCount).sum

  /** Look up a custom counter by name; returns 0L when the counter is
    * absent. Linear scan — Sub-plan 1 has no counters and Sub-plan 2's
    * operators each declare a handful, so a [[Map]] would be overkill.
    * Consumers that want fast lookup should index via the operator's
    * `Idx<Name>` constant on the raw [[counters]] array. */
  def counter(name: String): Long = {
    var i = 0
    while (i < counterNames.length) {
      if (counterNames(i) == name) return counters(i)
      i += 1
    }
    0L
  }
}
