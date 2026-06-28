package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Random

/** Plan 09 Phase 3 parity guarantee: with spill enabled at a tiny threshold
  * (forcing many flushes), [[SortExec]] must produce the *same global sort*
  * — same row multiset, sorted under the same comparator — as the non-spill
  * path. We can't assert position-by-position equality because the heap
  * merge isn't stable, but: the sort key is unique in each test, so a sorted
  * stable result over unique keys IS positionally determined.
  *
  * To pin position-equality we use unique keys; ties would expose the
  * comparator's stability characteristics, which is out of scope for this
  * parity check. */
class SortExecSpillTest {

  private val schema = Schema(Vector(Field("k", DataType.IntType), Field("p", DataType.IntType)))
  private val k: Expr = ColRefExpr(0, "k", DataType.IntType)
  private val keysAsc: Seq[(Expr, Boolean)] = Seq((k, true))

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("sort-spill-test-")
    System.setProperty(Spill.SpillDirProperty, tmpRoot.resolve("transformer-spill").toString)
  }

  @After def restoreOverride(): Unit = {
    if (originalDirProp == null) System.clearProperty(Spill.SpillDirProperty)
    else System.setProperty(Spill.SpillDirProperty, originalDirProp)
    deleteRecursively(tmpRoot)
  }

  private def deleteRecursively(p: Path): Unit = {
    if (!Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try {
        val it = s.iterator()
        while (it.hasNext) deleteRecursively(it.next())
      } finally s.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }

  // ---- helpers -------------------------------------------------------------

  private def drainRows(it: Iterator[ColumnarBatch]): Vector[Array[Any]] = {
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](schema.length)
        var c = 0
        while (c < schema.length) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
    }
    buf.toVector
  }

  private def runSort(
      partitions: Vector[Vector[Array[Any]]],
      opts: ExecutionOptions): Vector[Array[Any]] = {
    val plan = new InMemoryPartitionedPlanForSpillTest(schema, partitions)
    val sort = SortExec(plan, keysAsc, opts)
    drainRows(sort.execute(0))
  }

  // Stress-test threshold: 1 byte forces a spill on every batch input.
  private def spillyOpts(maxRuns: Int = ExecutionOptions.DefaultSpillMaxRuns): ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = maxRuns)

  /** Generate `n` unique-keyed rows split across `parts` partitions. */
  private def uniqueKeyedRows(n: Int, parts: Int, seed: Long): Vector[Vector[Array[Any]]] = {
    val rng = new Random(seed)
    val keys = rng.shuffle((0 until n).toVector)
    val perPart = (n + parts - 1) / parts
    keys.grouped(perPart).map { group =>
      group.zipWithIndex.map { case (kv, i) =>
        Array[Any](java.lang.Integer.valueOf(kv), java.lang.Integer.valueOf(i))
      }
    }.toVector
  }

  // ---- parity tests --------------------------------------------------------

  @Test def spillAndNonSpillProduceSameSortedOutput(): Unit = {
    val parts = uniqueKeyedRows(n = 5000, parts = 4, seed = 42L)
    val noSpill = runSort(parts, ExecutionOptions.Default)
    val withSpill = runSort(parts, spillyOpts())
    assertEquals(noSpill.length, withSpill.length)
    // Unique keys → positional equality on the key column. The non-key
    // column may shuffle if rows with the same key existed, but here every
    // key is distinct so we can assert the key sequence directly.
    val expectedKeys = noSpill.map(_(0).asInstanceOf[Integer].intValue).toVector
    val actualKeys = withSpill.map(_(0).asInstanceOf[Integer].intValue).toVector
    assertEquals(expectedKeys, actualKeys)
    // Multiset of full rows must also match.
    val expectedMs = noSpill.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    val actualMs = withSpill.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    assertEquals(expectedMs, actualMs)
  }

  @Test def manySpillRoundsStillSorted(): Unit = {
    // 100k rows × 1-byte threshold → spill every batch.
    val parts = uniqueKeyedRows(n = 100_000, parts = 4, seed = 7L)
    val rows = runSort(parts, spillyOpts())
    assertEquals(100_000, rows.length)
    // Strictly increasing keys.
    var i = 1
    while (i < rows.length) {
      val prev = rows(i - 1)(0).asInstanceOf[Integer].intValue
      val cur = rows(i)(0).asInstanceOf[Integer].intValue
      if (prev > cur) fail(s"out of order at i=$i: prev=$prev cur=$cur")
      i += 1
    }
  }

  @Test def emptyInputProducesEmptyOutputUnderSpill(): Unit = {
    val rows = runSort(Vector(Vector.empty, Vector.empty), spillyOpts())
    assertEquals(0, rows.length)
  }

  @Test def singleTinyBatchTriggersNoSpill(): Unit = {
    // 1-row input: even under a 1-byte threshold the buffer is flushed only
    // after the input stream is exhausted (we never flush a partial buffer
    // mid-batch). So this exercises the "spill code present but no run
    // actually written" branch.
    val parts = Vector(Vector(Array[Any](java.lang.Integer.valueOf(1), java.lang.Integer.valueOf(0))))
    val rows = runSort(parts, spillyOpts())
    assertEquals(Vector(1), rows.map(_(0).asInstanceOf[Integer].intValue))
  }

  @Test def spillMaxRunsAbortsRunawayPartition(): Unit = {
    // 50k rows with maxRuns=1: the operator should flush more than once and
    // bail out with a clear error rather than silently filling disk.
    val parts = uniqueKeyedRows(n = 50_000, parts = 1, seed = 11L)
    val opts = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = 1)
    try {
      runSort(parts, opts)
      fail("expected spill_max_runs guard to trip")
    } catch {
      case e: java.util.concurrent.ExecutionException =>
        // Scheduler wraps the underlying exception; either form is acceptable
        // as long as the message names the limit.
        val cause = e.getCause
        assertNotNull(cause)
        val msg = cause.getMessage
        assertTrue(s"expected mention of spill_max_runs in: $msg", msg.contains("spill_max_runs"))
      case e: RuntimeException =>
        assertTrue(s"expected mention of spill_max_runs in: ${e.getMessage}",
          e.getMessage.contains("spill_max_runs"))
    }
  }

  // ---- Sub-plan 2: spill + metrics composition ------------------------------

  /** External-merge sort + metrics: the operator's MetricsNode must report
    * non-zero `runsWritten` / `bytesSpilled`. */
  @Test def externalSortAndMetricsComposeReportingNonZeroSpillCounters(): Unit = {
    val parts = uniqueKeyedRows(n = 5000, parts = 4, seed = 99L)
    val plan = new InMemoryPartitionedPlanForSpillTest(schema, parts)
    val node = new MetricsNode(0, "SortExec", 0, SortExec.IdxCounterNames)
    val sort = SortExec(plan, keysAsc, spillyOpts(), node)
    val it = sort.execute(0)
    while (it.hasNext) it.next()
    val snap = node.snapshot()
    assertTrue(s"runsWritten must be > 0, got ${snap.counter("runsWritten")}",
      snap.counter("runsWritten") > 0L)
    assertTrue(s"bytesSpilled must be > 0, got ${snap.counter("bytesSpilled")}",
      snap.counter("bytesSpilled") > 0L)
    assertEquals(SortExec.PathKWay, snap.counter("path"))
    assertEquals(5000L, snap.counter("inputRows"))
    assertEquals(5000L, snap.counter("outputRows"))
  }

  // ---- Regression: duplicate-named child schema under spill -----------------
  //
  // A child whose output schema has two columns sharing a name (`[k, k]`, as a
  // join output `[k, v, k, v]` would) used to write spill runs with duplicate
  // parquet column paths, which read back null and NPE'd in DiskRunCursor. The
  // runs now use positional names. See plans/bugfixes/01.
  @Test def duplicateNamedChildSchemaUnderSpill(): Unit = {
    val dupSchema = Schema(Vector(Field("k", DataType.IntType), Field("k", DataType.IntType)))
    val rng = new Random(2026L)
    val keys = rng.shuffle((0 until 5000).toVector)
    val rows = keys.map(kv =>
      Array[Any](java.lang.Integer.valueOf(kv), java.lang.Integer.valueOf(kv * 2))).toVector
    val perPart = (rows.length + 3) / 4
    val parts = rows.grouped(perPart).toVector
    val sortKey = ColRefExpr(0, "k", DataType.IntType)

    def run(opts: ExecutionOptions): Vector[Array[Any]] = {
      val plan = new InMemoryPartitionedPlanForSpillTest(dupSchema, parts)
      val sort = SortExec(plan, Seq((sortKey, true)), opts)
      val it = sort.execute(0)
      val buf = mutable.ArrayBuffer.empty[Array[Any]]
      while (it.hasNext) {
        val b = it.next()
        assertEquals("emitted batch must carry the logical schema, not spilled _cN names",
          dupSchema.fields.map(_.name), b.schema.fields.map(_.name))
        var r = 0
        while (r < b.numRows) {
          buf += Array[Any](
            if (b.column(0).isNull(r)) null else b.column(0).getBoxed(r),
            if (b.column(1).isNull(r)) null else b.column(1).getBoxed(r))
          r += 1
        }
      }
      buf.toVector
    }

    val expected = run(ExecutionOptions.Default)
    val actual = run(spillyOpts())
    assertEquals(5000, actual.length)
    // Unique keys → positional equality on the sort key.
    assertEquals(
      expected.map(_(0).asInstanceOf[Integer].intValue),
      actual.map(_(0).asInstanceOf[Integer].intValue))
    val expMs = expected.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    val actMs = actual.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    assertEquals(expMs, actMs)
  }
}

/** Local copy of the in-memory partitioned plan used by [[SortExecTest]] —
  * needed here because the original is `private final class` in that file. */
private final class InMemoryPartitionedPlanForSpillTest(
    schema: Schema,
    partitions: Vector[Vector[Array[Any]]]) extends PhysicalPlan {
  def outputSchema: Schema = schema
  def numPartitions: Int = partitions.length
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    val rows = partitions(partition)
    if (rows.isEmpty) return Iterator.empty
    val batchSize = math.min(ColumnarBatch.DefaultCapacity, rows.length)
    rows.grouped(batchSize).map { group =>
      val b = new ColumnarBatch(schema, math.max(1, group.length))
      var r = 0
      while (r < group.length) {
        val row = group(r)
        var c = 0
        while (c < schema.length) {
          if (row(c) == null) b.column(c).setNull(r)
          else b.column(c).setBoxed(r, row(c))
          c += 1
        }
        r += 1
      }
      b.setNumRows(group.length)
      b
    }
  }
}
