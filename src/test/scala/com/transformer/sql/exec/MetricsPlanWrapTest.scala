package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import org.junit.Assert._
import org.junit.Test

/** Sub-plan 1 verification: `PhysicalPlanner.plan` returns the un-wrapped
  * tree when `opts.metricsEnabled` is false, and wraps every operator in
  * a [[MeteredPlan]] when true. The disabled-path test guards against
  * accidental cost on the default-options hot path.
  */
class MetricsPlanWrapTest {

  private val schema = Schema(Vector(
    Field("id", DataType.LongType),
    Field("x",  DataType.LongType)))

  /** Tiny in-memory view backed by a single ColumnarBatch. */
  private final class TinyView extends CatalogView {
    def schema: Schema = MetricsPlanWrapTest.this.schema
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] = {
      val b = new ColumnarBatch(schema, 4)
      val idC = b.column(0).asInstanceOf[LongVector]
      val xC = b.column(1).asInstanceOf[LongVector]
      idC.set(0, 1L); idC.set(1, 2L); idC.set(2, 3L); idC.set(3, 4L)
      xC.set(0, 10L); xC.set(1, 20L); xC.set(2, 30L); xC.set(3, 40L)
      b.setNumRows(4)
      Iterator.single(b)
    }
  }

  private def filterProjectScanLogical(catalog: Catalog): LogicalPlan = {
    catalog.register("t", new TinyView)
    // build now returns a BuiltQuery; resolve to the bound LogicalPlan the
    // same way SqlEngine does (no CTEs here, so resolve is a no-op passthrough).
    CteResolver.resolve(
      LogicalBuilder.build("SELECT id, x + 1 AS y FROM t WHERE x > 15", catalog),
      ExecutionOptions.Default)
  }

  @Test def disabledOptionsReturnUnwrappedTree(): Unit = {
    val catalog = new Catalog
    val logical = filterProjectScanLogical(catalog)
    val plan = PhysicalPlanner.plan(logical, ExecutionOptions.Default)
    // The disabled path returns the un-wrapped operator tree. No node in
    // the tree should be a MeteredPlan — that catches accidental
    // double-wrapping where planInner calls plan() on a child instead of
    // planInner.
    assertNoMeteredPlanAnywhere(plan)
  }

  private def assertNoMeteredPlanAnywhere(p: PhysicalPlan): Unit = {
    assertFalse(s"unexpected MeteredPlan: ${p.getClass.getSimpleName}",
      p.isInstanceOf[MeteredPlan])
    childrenOf(p).foreach(assertNoMeteredPlanAnywhere)
  }

  private def childrenOf(p: PhysicalPlan): Seq[PhysicalPlan] = p match {
    case _: ScanExec | _: CountStarMetadataExec => Nil
    case FilterExec(c, _) => Seq(c)
    case ProjectExec(c, _) => Seq(c)
    case LocalLimitExec(c, _) => Seq(c)
    case GlobalLimitExec(c, _) => Seq(c)
    case HashAggregateExec(c, _, _, _, _) => Seq(c)
    case HashJoinExec(l, r, _, _, _, _, _, _, _) => Seq(l, r)
    case SortExec(c, _, _, _) => Seq(c)
    case DistinctExec(c, _, _) => Seq(c)
    case ExchangeExec(c, _, _, _, _) => Seq(c)
    case WindowExec(c, _, _, _) => Seq(c)
    case UnionExec(l, r) => Seq(l, r)
    case mp: MeteredPlan => Seq(mp.underlying)
    case _ => Nil
  }

  @Test def enabledOptionsWrapEveryOperator(): Unit = {
    val catalog = new Catalog
    val logical = filterProjectScanLogical(catalog)
    val plan = PhysicalPlanner.plan(logical, ExecutionOptions(metricsEnabled = true))
    // Every operator (Project, Filter, Scan) gets wrapped.
    assertTrue("root must be a MeteredPlan when metricsEnabled=true",
      plan.isInstanceOf[MeteredPlan])
    val rootMP = plan.asInstanceOf[MeteredPlan]
    assertEquals("ProjectExec", rootMP.node.name)
    val rootInner = rootMP.underlying
    assertTrue(rootInner.isInstanceOf[ProjectExec])
    val proj = rootInner.asInstanceOf[ProjectExec]
    // The Project's child should be a metered FilterExec.
    assertTrue(proj.child.isInstanceOf[MeteredPlan])
    val filterMP = proj.child.asInstanceOf[MeteredPlan]
    assertEquals("FilterExec", filterMP.node.name)
    val filterInner = filterMP.underlying.asInstanceOf[FilterExec]
    assertTrue(filterInner.child.isInstanceOf[MeteredPlan])
    val scanMP = filterInner.child.asInstanceOf[MeteredPlan]
    assertEquals("ScanExec", scanMP.node.name)
  }

  @Test def planWithMetricsExposesRootNodeAndChildren(): Unit = {
    val catalog = new Catalog
    val logical = filterProjectScanLogical(catalog)
    val (plan, root) = PhysicalPlanner.planWithMetrics(logical, ExecutionOptions(metricsEnabled = true))
    val rootNode = root.getOrElse(fail("expected root MetricsNode").asInstanceOf[com.transformer.core.metrics.MetricsNode])
    assertEquals("ProjectExec", rootNode.name)
    // Pre-order ids: Project=0, Filter=1, Scan=2.
    assertEquals(0, rootNode.id)
    assertEquals(1, rootNode.children.size)
    val filterNode = rootNode.children.head
    assertEquals("FilterExec", filterNode.name)
    assertEquals(1, filterNode.id)
    assertEquals(1, filterNode.children.size)
    val scanNode = filterNode.children.head
    assertEquals("ScanExec", scanNode.name)
    assertEquals(2, scanNode.id)
    // Drain and verify the snapshot captures non-zero row counts on the
    // operators that emitted batches.
    val it = plan.execute(0)
    var rows = 0L
    while (it.hasNext) rows += it.next().numRows.toLong
    assertTrue(s"expected rows > 0, got $rows", rows > 0L)
    val snap = rootNode.snapshot()
    assertTrue(s"ProjectExec wallNanos should be positive: ${snap.wallNanos}", snap.wallNanos > 0L)
    // At the iterator-wrap level, each node's rowsIn/rowsOut both record the
    // operator's own emitted rows — the iterator we wrap IS that operator's
    // output. A row-discard operator like Filter shows up as a difference
    // between its rowsOut and its child's rowsOut (3 vs 4 here). Consumers
    // that want "rows discarded by Filter" subtract child.rowsOut from
    // filter.rowsOut.
    val scanSnap = snap.children.head.children.head
    assertEquals("scan rowsOut should match raw view size", 4L, scanSnap.rowsOut)
    val filterSnap = snap.children.head
    // Filter emits 3 rows (x > 15), so both rowsIn and rowsOut at the
    // wrap level are 3 for the filter.
    assertEquals("filter rowsOut should be filtered count", 3L, filterSnap.rowsOut)
    // Project preserves rows.
    assertEquals(filterSnap.rowsOut, snap.rowsOut)
  }

  @Test def metricsEnabledStillProducesSameRows(): Unit = {
    // Result equivalence: enabling metrics must NOT change the output.
    val catalogA = new Catalog
    val catalogB = new Catalog
    val logicalA = filterProjectScanLogical(catalogA)
    val logicalB = filterProjectScanLogical(catalogB)
    val planA = PhysicalPlanner.plan(logicalA, ExecutionOptions.Default)
    val planB = PhysicalPlanner.plan(logicalB, ExecutionOptions(metricsEnabled = true))
    val rowsA = drain(planA)
    val rowsB = drain(planB)
    assertEquals(rowsA, rowsB)
  }

  private def drain(plan: PhysicalPlan): Vector[Vector[Any]] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[Vector[Any]]
    var p = 0
    while (p < plan.numPartitions) {
      val it = plan.execute(p)
      while (it.hasNext) {
        val b = it.next()
        var r = 0
        while (r < b.numRows) {
          val row = (0 until plan.outputSchema.length).iterator.map { c =>
            val v = b.column(c)
            if (v.isNull(r)) null else v.getBoxed(r)
          }.toVector
          buf += row
          r += 1
        }
      }
      p += 1
    }
    buf.toVector
  }
}
