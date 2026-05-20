package com.transformer.core.metrics

import org.junit.Assert._
import org.junit.Test

import java.time.Instant

/** Tests for the instrumentation framework — Sub-plan 1 of the
  * instrumentation work. Covers:
  *
  *   - The disabled-path invariant: `MetricsCollector.globalDefault` reads
  *     the sysprop/env at class load and exposes a boolean.
  *   - Per-thread sampling helpers degrade gracefully (return -1 on JVMs
  *     that don't support them).
  *   - GC sampling produces non-decreasing cumulative counters.
  *   - [[OperatorMetrics]] snapshot tree round-trips and respects
  *     pre-order ids.
  *   - [[TaskMetricsRecord]] serialization round-trips with operator
  *     tree, timing fields, and the developer-facing sample fields.
  */
class MetricsCollectorTest {

  @Test def globalDefaultIsABoolean(): Unit = {
    // No assumption about the build runner — just confirm the value exists
    // and that subsequent reads return the same value.
    val a = MetricsCollector.globalDefault
    val b = MetricsCollector.globalDefault
    assertEquals(a, b)
  }

  @Test def parseBoolHandlesCaseAndAliases(): Unit = {
    assertTrue(MetricsCollector.parseBool("true"))
    assertTrue(MetricsCollector.parseBool("TRUE"))
    assertTrue(MetricsCollector.parseBool("True"))
    assertTrue(MetricsCollector.parseBool("1"))
    assertFalse(MetricsCollector.parseBool("false"))
    assertFalse(MetricsCollector.parseBool("0"))
    assertFalse(MetricsCollector.parseBool("yes"))
    assertFalse(MetricsCollector.parseBool(""))
  }

  @Test def currentCpuNanosReturnsAValidValueOrMinusOne(): Unit = {
    val v = MetricsCollector.currentCpuNanos()
    // -1 is the documented unsupported sentinel; any non-negative value is
    // valid (cumulative CPU since thread start).
    assertTrue(s"cpu nanos must be -1 or >= 0, got $v", v == -1L || v >= 0L)
  }

  @Test def currentAllocatedBytesIsConsistentlySignaled(): Unit = {
    val tid = Thread.currentThread.getId
    val v = MetricsCollector.currentAllocatedBytes(tid)
    if (MetricsCollector.supportsThreadAllocations)
      assertTrue(s"alloc bytes must be >= 0 on HotSpot, got $v", v >= 0L)
    else
      assertEquals("alloc bytes must be -1 on non-HotSpot", -1L, v)
  }

  @Test def gcSampleDeltaIsZeroOrPositive(): Unit = {
    val s1 = MetricsCollector.gcSample()
    // Allocate some garbage to encourage a young-gen collection on small heaps.
    val junk = new Array[Array[Byte]](1024)
    var i = 0
    while (i < junk.length) { junk(i) = new Array[Byte](1024); i += 1 }
    val s2 = MetricsCollector.gcSample()
    val d = s2.delta(s1)
    assertTrue(s"delta count must be >= 0, got ${d.count}", d.count >= 0L)
    assertTrue(s"delta nanos must be >= 0, got ${d.nanos}", d.nanos >= 0L)
    // Force-use junk so the JIT doesn't elide allocation.
    assertTrue(junk.length > 0)
  }

  @Test def metricsNodeSnapshotPreservesShape(): Unit = {
    val root = new MetricsNode(id = 0, name = "ProjectExec", partition = 0, counterNames = Array.empty)
    val child = new MetricsNode(id = 1, name = "ScanExec", partition = 0, counterNames = Array.empty)
    root.children = Vector(child)
    root.rowsIn.add(100L); root.rowsOut.add(90L)
    root.batchesIn.add(2L); root.batchesOut.add(2L)
    root.wallNanos.add(12345L)
    child.rowsIn.add(100L); child.rowsOut.add(100L)
    child.batchesIn.add(2L); child.batchesOut.add(2L)
    child.wallNanos.add(67890L)

    val snap = root.snapshot()
    assertEquals(0, snap.id)
    assertEquals("ProjectExec", snap.name)
    assertEquals(100L, snap.rowsIn)
    assertEquals(90L, snap.rowsOut)
    assertEquals(1, snap.children.size)
    assertEquals(2, snap.nodeCount)
    val csnap = snap.children.head
    assertEquals(1, csnap.id)
    assertEquals("ScanExec", csnap.name)
    assertEquals(67890L, csnap.wallNanos)
  }

  @Test def operatorMetricsCounterReturnsZeroForMissing(): Unit = {
    val o = OperatorMetrics(
      id = 0, name = "Foo", partition = 0,
      rowsIn = 0, rowsOut = 0, batchesIn = 0, batchesOut = 0, wallNanos = 0,
      counters = Array.empty, counterNames = Array.empty, children = Vector.empty)
    assertEquals(0L, o.counter("nonexistent"))
  }

  @Test def operatorMetricsCounterFindsByName(): Unit = {
    val o = OperatorMetrics(
      id = 0, name = "Agg", partition = 0,
      rowsIn = 0, rowsOut = 0, batchesIn = 0, batchesOut = 0, wallNanos = 0,
      counters = Array(7L, 13L),
      counterNames = Array("groupCount", "mergeNanos"),
      children = Vector.empty)
    assertEquals(7L, o.counter("groupCount"))
    assertEquals(13L, o.counter("mergeNanos"))
    assertEquals(0L, o.counter("absent"))
  }

  @Test def taskMetricsRecordRoundTripsFullTree(): Unit = {
    val leaf = OperatorMetrics(
      id = 1, name = "ScanExec", partition = 0,
      rowsIn = 50L, rowsOut = 50L, batchesIn = 1L, batchesOut = 1L,
      wallNanos = 1234L,
      counters = Array.empty, counterNames = Array.empty,
      children = Vector.empty)
    val root = OperatorMetrics(
      id = 0, name = "FilterExec", partition = 0,
      rowsIn = 50L, rowsOut = 25L, batchesIn = 1L, batchesOut = 1L,
      wallNanos = 4567L,
      counters = Array.empty, counterNames = Array.empty,
      children = Vector(leaf))
    val qm = QueryMetrics(
      parseNanos = 10L, optimizeNanos = 20L,
      physicalPlanNanos = 30L, executeNanos = 40L,
      operatorTree = root)
    val rec = TaskMetricsRecord(
      schemaVersion = TaskMetricsRecord.SchemaVersion,
      taskName = "rt-task",
      executionTime = Instant.parse("2026-05-20T00:00:00Z"),
      enqueuedAt = Instant.parse("2026-05-20T00:00:01Z"),
      startedAt = Instant.parse("2026-05-20T00:00:02Z"),
      finishedAt = Instant.parse("2026-05-20T00:00:03Z"),
      query = Some(qm),
      cpuNanos = 5000L,
      allocBytes = 1024L,
      gcDeltaCount = 1L,
      gcDeltaNanos = 100L)
    val dir = java.nio.file.Files.createTempDirectory("metrics-rt-")
    TaskMetricsRecord.write(dir, rec)
    val read = TaskMetricsRecord.read(dir)
      .getOrElse(fail("expected _perf.json to round-trip").asInstanceOf[TaskMetricsRecord])
    assertEquals("rt-task", read.taskName)
    assertEquals(rec.executionTime, read.executionTime)
    assertEquals(rec.enqueuedAt, read.enqueuedAt)
    assertEquals(rec.cpuNanos, read.cpuNanos)
    assertEquals(rec.allocBytes, read.allocBytes)
    assertEquals(rec.gcDeltaCount, read.gcDeltaCount)
    assertEquals(rec.gcDeltaNanos, read.gcDeltaNanos)
    val rq = read.query.getOrElse(fail("expected query metrics").asInstanceOf[QueryMetrics])
    assertEquals(qm.parseNanos, rq.parseNanos)
    assertEquals(qm.executeNanos, rq.executeNanos)
    val rt = rq.operatorTree
    assertEquals("FilterExec", rt.name)
    assertEquals(50L, rt.rowsIn)
    assertEquals(25L, rt.rowsOut)
    assertEquals(1, rt.children.size)
    val rl = rt.children.head
    assertEquals("ScanExec", rl.name)
    assertEquals(1234L, rl.wallNanos)
  }

  @Test def taskMetricsRecordWithCountersRoundTrips(): Unit = {
    val tree = OperatorMetrics(
      id = 0, name = "HashAggregateExec", partition = 0,
      rowsIn = 1000L, rowsOut = 100L, batchesIn = 10L, batchesOut = 1L,
      wallNanos = 50000L,
      counters = Array(42L, 1234L),
      counterNames = Array("groupCount", "mergeNanos"),
      children = Vector.empty)
    val rec = TaskMetricsRecord(
      schemaVersion = TaskMetricsRecord.SchemaVersion,
      taskName = "agg-task",
      executionTime = Instant.parse("2026-05-20T00:00:00Z"),
      enqueuedAt = Instant.parse("2026-05-20T00:00:00Z"),
      startedAt = Instant.parse("2026-05-20T00:00:00Z"),
      finishedAt = Instant.parse("2026-05-20T00:00:00Z"),
      query = Some(QueryMetrics(0L, 0L, 0L, 0L, tree)),
      cpuNanos = -1L, allocBytes = -1L,
      gcDeltaCount = 0L, gcDeltaNanos = 0L)
    val dir = java.nio.file.Files.createTempDirectory("metrics-counters-")
    TaskMetricsRecord.write(dir, rec)
    val read = TaskMetricsRecord.read(dir).get
    val rt = read.query.get.operatorTree
    assertEquals(42L, rt.counter("groupCount"))
    assertEquals(1234L, rt.counter("mergeNanos"))
  }

  @Test def taskMetricsRecordWithoutQueryRoundTrips(): Unit = {
    // When `SqlEngine.execute` errors before the operator tree is captured,
    // the framework still writes a `_perf.json` with `query = null` and the
    // task-boundary sampling fields. Verify that branch round-trips.
    val rec = TaskMetricsRecord(
      schemaVersion = TaskMetricsRecord.SchemaVersion,
      taskName = "no-query",
      executionTime = Instant.parse("2026-05-20T00:00:00Z"),
      enqueuedAt = Instant.parse("2026-05-20T00:00:00Z"),
      startedAt = Instant.parse("2026-05-20T00:00:00Z"),
      finishedAt = Instant.parse("2026-05-20T00:00:00Z"),
      query = None,
      cpuNanos = 100L, allocBytes = 200L,
      gcDeltaCount = 3L, gcDeltaNanos = 999L)
    val dir = java.nio.file.Files.createTempDirectory("metrics-noq-")
    TaskMetricsRecord.write(dir, rec)
    val read = TaskMetricsRecord.read(dir).get
    assertEquals(None, read.query)
    assertEquals(100L, read.cpuNanos)
  }

  @Test def taskMetricsRecordMissingFileReturnsNone(): Unit = {
    val dir = java.nio.file.Files.createTempDirectory("metrics-missing-")
    assertEquals(None, TaskMetricsRecord.read(dir))
  }

  @Test def meteredIteratorIncrementsRowAndBatchCounters(): Unit = {
    // Build a tiny stub iterator over two ColumnarBatches with known row
    // counts. The iterator wrapping must surface them on the node.
    val schema = com.transformer.core.Schema(Vector(
      com.transformer.core.Field("x", com.transformer.core.DataType.LongType)))
    def mkBatch(n: Int): com.transformer.core.ColumnarBatch = {
      val b = new com.transformer.core.ColumnarBatch(schema, math.max(1, n))
      val col = b.column(0).asInstanceOf[com.transformer.core.LongVector]
      var i = 0
      while (i < n) { col.set(i, i.toLong); i += 1 }
      b.setNumRows(n)
      b
    }
    val batches = Iterator(mkBatch(3), mkBatch(5))
    val node = new MetricsNode(0, "ScanExec", 0, Array.empty)
    val mi = new MeteredIterator(batches, node)
    while (mi.hasNext) mi.next()
    val snap = node.snapshot()
    assertEquals(8L, snap.rowsIn)
    assertEquals(8L, snap.rowsOut)
    assertEquals(2L, snap.batchesIn)
    assertEquals(2L, snap.batchesOut)
    assertTrue("wallNanos should be positive", snap.wallNanos > 0L)
  }
}
