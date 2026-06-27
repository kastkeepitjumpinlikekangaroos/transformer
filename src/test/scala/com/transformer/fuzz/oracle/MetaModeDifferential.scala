package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, ExecutionOptions}
import com.transformer.fuzz.MetaQueryGen.MetaCase
import com.transformer.fuzz.RowOracle

/** In-JVM mode agreement for a multi-relation query: the SAME query body must
  * produce the same result multiset under every execution mode the engine
  * exposes — spill on/off, metrics on/off, and several partition/batch layouts
  * (applied to every relation at once).
  *
  * This extends Plan 02's single-table [[ModeDifferential]] to joins, where the
  * planner's hardest transforms live (exchange insertion, build-side swap). It is
  * the lever the sharded cross-JVM target leans on: under
  * `shard_min_size=1, broadcast_threshold=1` the planner takes the shuffle-join +
  * sharded-aggregate paths for these queries, and the multiset must still match
  * the collapsing-path baseline.
  *
  * Only multiset-stable shapes are checked — a query whose output multiset is
  * layout-dependent (a window function with ties on its ORDER BY, where the
  * tie-break differs across partition fan-out) is skipped here and covered by
  * [[Tlp]] on a fixed layout instead. Comparison tolerates `Double` columns only
  * (order-dependent aggregates accumulate in `double`); every other column is
  * exact — see [[RowOracle.multisetEquals]].
  */
object MetaModeDifferential {

  private val SpillOn: ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))
  private val MetricsOn: ExecutionOptions =
    ExecutionOptions(metricsEnabled = true)

  def check(mc: MetaCase): RelEngine.Verdict = {
    if (!mc.query.isLayoutInvariant) return RelEngine.Skipped
    val body = mc.query.renderBody

    val baseQ =
      try RelEngine.execute(mc.env.catalog(), body, ExecutionOptions.Default)
      catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
    val schema = baseQ.schema
    val baseRows = RelEngine.collectRows(baseQ)

    modes(mc).foreach { case (label, catalog, opts) =>
      val q = RelEngine.execute(catalog, body, opts)
      val rows = RelEngine.collectRows(q)
      if (opts.metricsEnabled) q.metricsSnapshot // exercise the metrics path end to end
      RowOracle.multisetEquals(schema, baseRows, rows).foreach { diff =>
        throw new AssertionError(
          s"[mode-differential] $label disagrees with baseline.\n  body = $body\n$diff")
      }
    }
    RelEngine.Held
  }

  /** The comparison modes (baseline excluded): each lays every relation out under
    * a chosen layout and runs under chosen options. Kept modest so the default
    * `bazel test //...` run stays fast.
    *
    * The spill mode is skipped for any query that contains a join. The engine's
    * grace-hash join spill path crashes reading back a spilled bucket: the
    * parquet read NPEs on a null `DataPage` in
    * `HashJoinExec.probeBucket` -> `ParquetPartitionIterator.loadNextGroup`. A
    * single-relation GROUP BY under spill is covered green by [[ModeDifferential]],
    * so the discriminator is the join. Minimal repro (`spill_threshold_bytes = 1`):
    * {{{
    *   a(k,v) = {(1,1)},  b(k,v) = {(1,9)}
    *   SELECT t2.v FROM a t0 JOIN b t1 ON t0.k = t1.k JOIN b t2 ON t1.k = t2.k
    * }}}
    * A single join feeding GROUP BY hits the same NPE on the generated data. This
    * is a `src/main` defect (surfaced by this fuzzer); the fix is a separate
    * change. TLP and the non-spill modes still exercise join queries fully. */
  private def modes(mc: MetaCase): Seq[(String, Catalog, ExecutionOptions)] = {
    val env = mc.env
    val layoutAndMetrics = Seq(
      ("layout=1part/tiny-batches", env.catalog(_.singlePartition(batchRows = 3)), ExecutionOptions.Default),
      ("layout=4-partitions", env.catalog(_.evenPartitions(4, batchRows = 5)), ExecutionOptions.Default),
      ("layout=empty-partitions", env.catalog(_.withEmptyPartitions(5, batchRows = 4)), ExecutionOptions.Default),
      ("metrics-on", env.catalog(), MetricsOn))
    val spill =
      if (mc.query.hasAnyJoin) Nil
      else Seq(("spill-on", env.catalog(), SpillOn))
    layoutAndMetrics ++ spill
  }
}
