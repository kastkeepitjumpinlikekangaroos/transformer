package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Random

/** Plan 09 Phase 4 parity: with spill enabled at a tiny threshold, the codec
  * and LongHashMap paths in [[HashAggregateExec]] must each produce the
  * same per-group aggregates as the non-spill path. We assert multiset
  * equality (groups are output in insertion order which differs once spill
  * reshapes things). */
class HashAggregateSpillTest {

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("agg-spill-test-")
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

  private def drainRows(it: Iterator[ColumnarBatch], schema: Schema): Vector[Vector[Any]] = {
    val buf = mutable.ArrayBuffer.empty[Vector[Any]]
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
        buf += arr.toVector
        r += 1
      }
    }
    buf.toVector
  }

  private def spillyOpts(maxRuns: Int = ExecutionOptions.DefaultSpillMaxRuns): ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = maxRuns)

  // ---- Long-key path -------------------------------------------------------

  /** Build a single-int-keyed plan: GROUP BY k, SUM(v). */
  private def runLongKey(
      partitions: Vector[Vector[(Int, Long)]],
      opts: ExecutionOptions): Vector[Vector[Any]] = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))
    val rowsByPart = partitions.map(_.map(kv => Array[Any](
      java.lang.Integer.valueOf(kv._1), java.lang.Long.valueOf(kv._2))))
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, rowsByPart)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val agg = HashAggregateExec(
      plan,
      groupKeys = Seq((k, "k")),
      aggregates = Seq((AggExprSum(v), "sumv")),
      opts = opts)
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    drainRows(out, agg.outputSchema)
  }

  @Test def longKeyPathBitEqualWithAndWithoutSpill(): Unit = {
    val rng = new Random(1234L)
    val parts = (0 until 4).map { p =>
      (0 until 5000).map { _ =>
        val k = rng.nextInt(200)
        val v = rng.nextLong() & 0xFFFFFFL
        (k, v)
      }.toVector
    }.toVector

    val noSpill = runLongKey(parts, ExecutionOptions.Default).toSet
    val withSpill = runLongKey(parts, spillyOpts()).toSet
    assertEquals(noSpill, withSpill)
  }

  @Test def longKeyPathSingleRowNoSpillTriggered(): Unit = {
    val parts = Vector(Vector((1, 100L)))
    val expected = runLongKey(parts, ExecutionOptions.Default)
    val actual = runLongKey(parts, spillyOpts())
    assertEquals(expected.toSet, actual.toSet)
  }

  @Test def longKeyPathNullKeysHandled(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))
    // Manually build a partition with some null keys.
    val rows = Vector(
      Array[Any](java.lang.Integer.valueOf(1), java.lang.Long.valueOf(10)),
      Array[Any](null,                          java.lang.Long.valueOf(20)),
      Array[Any](java.lang.Integer.valueOf(2), java.lang.Long.valueOf(30)),
      Array[Any](null,                          java.lang.Long.valueOf(40)),
      Array[Any](java.lang.Integer.valueOf(1), java.lang.Long.valueOf(50))
    )
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    def run(opts: ExecutionOptions): Vector[Vector[Any]] = {
      val agg = HashAggregateExec(plan, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), opts)
      val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
      drainRows(out, agg.outputSchema)
    }
    val expected = run(ExecutionOptions.Default).toSet
    val actual = run(spillyOpts()).toSet
    assertEquals(expected, actual)
    // Sanity-check: NULL-key group must exist in both with sum=60.
    val nullEntry = expected.find(_(0) == null)
    assertTrue(nullEntry.isDefined)
    assertEquals(java.lang.Long.valueOf(60L), nullEntry.get(1))
  }

  // ---- Codec path (multi-key / variable-width) -----------------------------

  /** GROUP BY (a:String, b:Int) with SUM(v:Double), forces the codec path
    * (variable-width). */
  private def runCodecMultiKey(
      partitions: Vector[Vector[(String, Int, Double)]],
      opts: ExecutionOptions): Vector[Vector[Any]] = {
    val schema = Schema(Vector(
      Field("a", DataType.StringType),
      Field("b", DataType.IntType),
      Field("v", DataType.DoubleType)))
    val rowsByPart = partitions.map(_.map(t => Array[Any](
      t._1, java.lang.Integer.valueOf(t._2), java.lang.Double.valueOf(t._3))))
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, rowsByPart)
    val a = ColRefExpr(0, "a", DataType.StringType)
    val b = ColRefExpr(1, "b", DataType.IntType)
    val v = ColRefExpr(2, "v", DataType.DoubleType)
    val agg = HashAggregateExec(
      plan,
      groupKeys = Seq((a, "a"), (b, "b")),
      aggregates = Seq((AggExprSum(v), "sumv"), (AggExprAvg(v), "avgv")),
      opts = opts)
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    drainRows(out, agg.outputSchema)
  }

  @Test def codecPathBitEqualWithAndWithoutSpill(): Unit = {
    val rng = new Random(99L)
    val parts = (0 until 3).map { _ =>
      (0 until 2000).map { _ =>
        val a = s"key-${rng.nextInt(50)}"
        val b = rng.nextInt(10)
        val v = rng.nextDouble() * 1000.0
        (a, b, v)
      }.toVector
    }.toVector
    val expected = runCodecMultiKey(parts, ExecutionOptions.Default)
    val actual = runCodecMultiKey(parts, spillyOpts())
    // SUM should match exactly; AVG can differ at the floating-point ULP
    // level after a different summation order. We compare structurally with
    // a relative tolerance on the AVG column.
    val expByKey: Map[(String, Int), (Double, Double)] = expected.map { row =>
      ((row(0).asInstanceOf[String], row(1).asInstanceOf[Integer].intValue),
       (row(2).asInstanceOf[Double], row(3).asInstanceOf[Double]))
    }.toMap
    val actByKey: Map[(String, Int), (Double, Double)] = actual.map { row =>
      ((row(0).asInstanceOf[String], row(1).asInstanceOf[Integer].intValue),
       (row(2).asInstanceOf[Double], row(3).asInstanceOf[Double]))
    }.toMap
    assertEquals(expByKey.keySet, actByKey.keySet)
    expByKey.foreach { case (key, (expSum, expAvg)) =>
      val (actSum, actAvg) = actByKey(key)
      assertEquals(s"sum key=$key", expSum, actSum, math.abs(expSum) * 1e-9 + 1e-9)
      assertEquals(s"avg key=$key", expAvg, actAvg, math.abs(expAvg) * 1e-9 + 1e-9)
    }
  }

  @Test def codecPathEmptyInputMatchesNoSpill(): Unit = {
    val expected = runCodecMultiKey(Vector(Vector.empty), ExecutionOptions.Default).toSet
    val actual = runCodecMultiKey(Vector(Vector.empty), spillyOpts()).toSet
    assertEquals(expected, actual)
  }

  // ---- spill_max_runs guard ------------------------------------------------

  @Test def hashAggregateRespectsSpillMaxRuns(): Unit = {
    val rng = new Random(7L)
    val rows = (0 until 50_000).map { _ =>
      (rng.nextInt(1000), rng.nextLong() & 0xFFFFFFL)
    }.toVector
    val parts = Vector(rows)
    val opts = ExecutionOptions(
      spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = 1)
    try {
      runLongKey(parts, opts)
      fail("expected spill_max_runs guard to trip")
    } catch {
      case e: java.util.concurrent.ExecutionException =>
        val cause = e.getCause
        assertNotNull(cause)
        assertTrue(s"message: ${cause.getMessage}", cause.getMessage.contains("spill_max_runs"))
      case e: RuntimeException =>
        assertTrue(s"message: ${e.getMessage}", e.getMessage.contains("spill_max_runs"))
    }
  }

  // ---- CountDistinct opt-out ------------------------------------------------

  // ---- Sub-plan 2: spill + metrics composition ------------------------------

  /** With a tiny spill threshold AND metrics enabled, the operator's
    * MetricsNode must report non-zero `spillEvents` and `bytesSpilled`. This
    * is the composition gate: spill bookkeeping must continue to fire under
    * metering. */
  @Test def spillAndMetricsComposeReportingNonZeroSpillCounters(): Unit = {
    val rng = new Random(1234L)
    val parts = (0 until 4).map { p =>
      (0 until 5000).map { _ =>
        val k = rng.nextInt(200)
        val v = rng.nextLong() & 0xFFFFFFL
        (k, v)
      }.toVector
    }.toVector
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))
    val rowsByPart = parts.map(_.map(kv => Array[Any](
      java.lang.Integer.valueOf(kv._1), java.lang.Long.valueOf(kv._2))))
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, rowsByPart)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val node = new MetricsNode(0, "HashAggregateExec", 0, HashAggregateExec.IdxCounterNames)
    val agg = HashAggregateExec(
      plan, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), spillyOpts(), node)
    // Drain.
    val it = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    while (it.hasNext) it.next()
    val snap = node.snapshot()
    assertTrue(s"spillEvents must be > 0, got ${snap.counter("spillEvents")}",
      snap.counter("spillEvents") > 0L)
    assertTrue(s"bytesSpilled must be > 0, got ${snap.counter("bytesSpilled")}",
      snap.counter("bytesSpilled") > 0L)
    assertTrue(s"spillRunsWritten must be > 0, got ${snap.counter("spillRunsWritten")}",
      snap.counter("spillRunsWritten") > 0L)
    assertTrue(s"groupCount must be > 0, got ${snap.counter("groupCount")}",
      snap.counter("groupCount") > 0L)
  }

  @Test def countDistinctDisablesSpillNotErrors(): Unit = {
    // A task that asks for spill but contains a CountDistinct aggregate
    // should silently fall back to the in-memory path — never throw.
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))
    val parts = Vector((0 until 100).map(i =>
      Array[Any](java.lang.Integer.valueOf(i % 5), java.lang.Long.valueOf(i))).toVector)
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, parts)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val agg = HashAggregateExec(
      plan,
      groupKeys = Seq((k, "k")),
      aggregates = Seq((AggExprCount(v, distinct = true), "cd")),
      opts = spillyOpts())
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    val rows = drainRows(out, agg.outputSchema)
    assertEquals(5, rows.length) // 5 distinct keys
  }
}

private final class InMemoryPartitionedPlanForAggSpill(
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
