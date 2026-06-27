package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.QueryGen.QueryCase
import com.transformer.fuzz.{DataGen, QueryGen, RowOracle}
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.plan.{ColRefExpr, LogicalBuilder, Ops}

import scala.collection.mutable

/** The mode-differential property for one `(dataset, query)`: every execution
  * mode the engine exposes must produce the SAME result multiset.
  *
  * This is the generated generalization of the hand-written `*SpillTest` parity
  * checks (which pin "spill-on equals spill-off" on fixed fixtures). The query
  * text is fixed, so all modes parse, bind, optimize and plan it identically —
  * only [[ExecutionOptions]] and the physical input layout vary:
  *
  *   - '''spill''' on (1-byte threshold, forcing flushes) vs off;
  *   - '''metrics''' on (operators wrapped in metered iterators) vs off;
  *   - '''layout''' — the same logical rows replayed as a [[MaterializedView]]
  *     with one big batch, many tiny batches, several partitions, and partitions
  *     left empty at the head / interior / tail.
  *
  * Sharded-vs-collapsing aggregation is NOT a mode here: the gate
  * (`LogicalPlanCardinality.MinShardableSize`) is read once at class load, so it
  * cannot be toggled within a JVM — that equivalence is a separate cross-JVM
  * target.
  *
  * Comparison is a multiset (the K-way merge and the collapsing aggregate emit
  * rows in a layout-dependent order) with a tolerance on `Double` columns only
  * (the order-dependent aggregates) — see [[RowOracle.multisetEquals]]. When a
  * query carries `ORDER BY`, the oracle additionally checks each run's output is
  * actually sorted by the primary order key (multiset equality alone would not
  * notice an unsorted `SortExec`).
  *
  * A query that the engine REJECTS at bind/plan time (an `IllegalArgumentException`
  * from the generator's own SQL) is not a finding — [[check]] returns
  * [[Rejected]] and the caller counts it. Only a successfully-planned query whose
  * modes disagree throws (an `AssertionError`, which the harness minimizes).
  */
object ModeDifferential {

  sealed trait Result
  /** All modes agreed (or the query produced no surprises). */
  case object Agreed extends Result
  /** The engine rejected the generated SQL at bind/plan time. */
  final case class Rejected(reason: String) extends Result

  private val SpillOn: ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))
  private val MetricsOn: ExecutionOptions =
    ExecutionOptions(metricsEnabled = true)
  private val SpillAndMetrics: ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), metricsEnabled = true)

  /** Run `qc` under every mode and assert agreement. Returns [[Agreed]] /
    * [[Rejected]]; throws `AssertionError` on a genuine divergence. */
  def check(qc: QueryCase): Result = {
    val sql = qc.sql
    val dataset = qc.dataset
    val baselineView = dataset.singlePartition()

    // The baseline doubles as the bind/plan probe: `SqlEngine.execute` runs
    // parse -> bind -> optimize -> plan eagerly and returns before any row is
    // pulled, so an IllegalArgumentException here is a bind/plan rejection (not
    // an execution divergence). Because binding and planning are independent of
    // ExecutionOptions and of partition layout, a query that plans for the
    // baseline plans for every mode.
    val baselineQ =
      try SqlEngine.execute(sql, catalogOf(baselineView), ExecutionOptions.Default)
      catch { case e: IllegalArgumentException => return Rejected(rejectReason(e)) }

    val schema = baselineQ.schema
    val baselineRows = collectRows(baselineQ)
    assertSorted(schema, baselineRows, qc.query.orderBy, "baseline", sql)

    modes(dataset).foreach { case (label, view, opts) =>
      val rows = run(view, sql, opts)
      RowOracle.multisetEquals(schema, baselineRows, rows).foreach { diff =>
        throw new AssertionError(
          s"[mode-differential] $label disagrees with baseline.\n  sql = $sql\n$diff")
      }
      assertSorted(schema, rows, qc.query.orderBy, label, sql)
    }
    Agreed
  }

  /** Lightweight bind probe used by the reject-rate report: did the engine accept
    * and plan this query? Runs the eager parse/bind/plan only (no drain). */
  def binds(qc: QueryCase): Boolean = bindReject(qc).isEmpty

  /** `Some(reason)` if the engine rejects the generated SQL at bind time, `None`
    * if it accepts it. Uses the parse+bind front door directly (no planning or
    * execution) so the reject-rate report is cheap; for single-table queries the
    * later optimize/plan stages add no rejections. */
  def bindReject(qc: QueryCase): Option[String] =
    try { LogicalBuilder.build(qc.sql, catalogOf(qc.dataset.singlePartition())); None }
    catch { case e: IllegalArgumentException => Some(rejectReason(e)) }

  /** The comparison modes (baseline excluded): each is a (label, input layout,
    * options). One execution per entry; keep the count modest so the default
    * `bazel test //...` run stays fast. */
  private def modes(d: DataGen.Dataset): Seq[(String, MaterializedView, ExecutionOptions)] = Seq(
    ("layout=1part/tiny-batches", d.singlePartition(batchRows = 3), ExecutionOptions.Default),
    ("layout=4-partitions", d.evenPartitions(4, batchRows = 5), ExecutionOptions.Default),
    ("layout=empty-partitions", d.withEmptyPartitions(5, batchRows = 4), ExecutionOptions.Default),
    ("spill-on", d.singlePartition(), SpillOn),
    ("metrics-on", d.singlePartition(), MetricsOn),
    ("spill+metrics/4-partitions", d.evenPartitions(3, batchRows = 4), SpillAndMetrics))

  private def run(view: MaterializedView, sql: String, opts: ExecutionOptions): Vector[Array[Any]] = {
    val q = SqlEngine.execute(sql, catalogOf(view), opts)
    val rows = collectRows(q)
    // Drain-then-snapshot exercises the metrics path end to end without
    // affecting the result; the snapshot is discarded.
    if (opts.metricsEnabled) q.metricsSnapshot
    rows
  }

  private def catalogOf(view: MaterializedView): Catalog = {
    val c = new Catalog
    c.register(QueryGen.ViewName, view)
    c
  }

  private def collectRows(q: ExecutedQuery): Vector[Array[Any]] = {
    val ncols = q.schema.length
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    val it = q.batches
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](ncols)
        var c = 0
        while (c < ncols) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
    }
    buf.toVector
  }

  /** Assert the output is sorted by the primary ORDER BY key. Checks only the
    * non-NULL values of the primary key for monotonicity, which is robust to the
    * engine's NULL-clustering policy (NULLs land together at one end, so ignoring
    * them leaves the non-NULL run monotonic) and to ties on the primary key (the
    * unstable merge may reorder tied rows, but the key values stay monotonic). */
  private def assertSorted(
      schema: Schema,
      rows: Seq[Array[Any]],
      orderBy: Vector[(ColRefExpr, Boolean)],
      label: String,
      sql: String): Unit = {
    if (orderBy.isEmpty) return
    val (key, asc) = orderBy.head
    val idx = schema.indexOf(key.name)
    if (idx < 0) return // key not projected into the output — nothing to check
    var prev: Any = null
    var havePrev = false
    var i = 0
    while (i < rows.length) {
      val v = rows(i)(idx)
      if (v != null) {
        if (havePrev) {
          val c = Ops.cmp(prev, v)
          val ok = if (asc) c <= 0 else c >= 0
          if (!ok) {
            throw new AssertionError(
              s"[mode-differential] $label output not sorted by ${key.name} " +
                s"${if (asc) "ASC" else "DESC"}: ${RowOracle.describe(prev)} then ${RowOracle.describe(v)}\n  sql = $sql")
          }
        }
        prev = v
        havePrev = true
      }
      i += 1
    }
  }

  private def rejectReason(e: IllegalArgumentException): String = {
    val m = Option(e.getMessage).getOrElse(e.getClass.getName)
    if (m.length <= 200) m else m.take(200) + "..."
  }
}
