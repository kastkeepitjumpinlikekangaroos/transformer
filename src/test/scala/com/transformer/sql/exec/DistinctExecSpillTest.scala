package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Random

/** Distinct spill parity: with spill at a tiny threshold, DistinctExec's
  * output must be a multiset-equal permutation of the non-spill output. */
class DistinctExecSpillTest {

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("distinct-spill-test-")
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

  private def spillyOpts(): ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))

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

  /** Plan that exposes one or more partitions of pre-built rows. */
  private final class InMemoryPlan(
      schema: Schema, partitions: Vector[Vector[Array[Any]]]) extends PhysicalPlan {
    def outputSchema: Schema = schema
    def numPartitions: Int = partitions.length
    def execute(p: Int): Iterator[ColumnarBatch] = {
      val rows = partitions(p)
      if (rows.isEmpty) return Iterator.empty
      val cap = math.min(ColumnarBatch.DefaultCapacity, rows.length)
      rows.grouped(cap).map { group =>
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

  private def runDistinct(
      schema: Schema,
      partitions: Vector[Vector[Array[Any]]],
      opts: ExecutionOptions): Set[Vector[Any]] = {
    val plan = new InMemoryPlan(schema, partitions)
    val d = DistinctExec(plan, opts)
    val out = (0 until d.numPartitions).iterator.flatMap(d.execute)
    drainRows(out, d.outputSchema).toSet
  }

  @Test def fixedWidthBitEqualWithAndWithoutSpill(): Unit = {
    val schema = Schema(Vector(Field("a", DataType.IntType), Field("b", DataType.IntType)))
    val rng = new Random(31L)
    val parts = (0 until 4).map { _ =>
      (0 until 5000).map { _ =>
        Array[Any](
          java.lang.Integer.valueOf(rng.nextInt(100)),
          java.lang.Integer.valueOf(rng.nextInt(100)))
      }.toVector
    }.toVector

    val expected = runDistinct(schema, parts, ExecutionOptions.Default)
    val actual = runDistinct(schema, parts, spillyOpts())
    assertEquals(expected, actual)
  }

  @Test def variableWidthBitEqualWithAndWithoutSpill(): Unit = {
    val schema = Schema(Vector(Field("a", DataType.StringType), Field("b", DataType.IntType)))
    val rng = new Random(42L)
    val parts = (0 until 3).map { _ =>
      (0 until 2000).map { _ =>
        Array[Any](s"k${rng.nextInt(40)}", java.lang.Integer.valueOf(rng.nextInt(20)))
      }.toVector
    }.toVector
    val expected = runDistinct(schema, parts, ExecutionOptions.Default)
    val actual = runDistinct(schema, parts, spillyOpts())
    assertEquals(expected, actual)
  }

  @Test def nullsPreservedAsDistinctBucket(): Unit = {
    val schema = Schema(Vector(Field("s", DataType.StringType)))
    val rows = Vector(
      Array[Any]("a"),
      Array[Any](null),
      Array[Any]("a"),
      Array[Any]("b"),
      Array[Any](null))
    val expected = runDistinct(schema, Vector(rows), ExecutionOptions.Default)
    val actual = runDistinct(schema, Vector(rows), spillyOpts())
    assertEquals(expected, actual)
    assertEquals(Set(Vector("a"), Vector("b"), Vector(null)), actual)
  }

  @Test def emptyInputProducesEmptyOutput(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType)))
    val actual = runDistinct(schema, Vector(Vector.empty), spillyOpts())
    assertEquals(Set.empty[Vector[Any]], actual)
  }

  @Test def spillMaxRunsAbortsRunaway(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType)))
    val rng = new Random(5L)
    val parts = Vector((0 until 50_000).map(_ =>
      Array[Any](java.lang.Integer.valueOf(rng.nextInt(1_000_000)))).toVector)
    val opts = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = 1)
    try {
      runDistinct(schema, parts, opts)
      fail("expected spill_max_runs guard to trip")
    } catch {
      case e: java.util.concurrent.ExecutionException =>
        val msg = e.getCause.getMessage
        assertTrue(s"message: $msg", msg.contains("spill_max_runs"))
      case e: RuntimeException =>
        assertTrue(s"message: ${e.getMessage}", e.getMessage.contains("spill_max_runs"))
    }
  }

  // ---- Sub-plan 2: spill + metrics composition ------------------------------

  @Test def spillAndMetricsComposeReportingNonZeroSpillCounters(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.IntType)))
    val rng = new Random(31L)
    val parts = (0 until 4).map { _ =>
      (0 until 5000).map { _ =>
        Array[Any](
          java.lang.Integer.valueOf(rng.nextInt(100)),
          java.lang.Integer.valueOf(rng.nextInt(100)))
      }.toVector
    }.toVector
    val plan = new InMemoryPlan(schema, parts)
    val node = new MetricsNode(0, "DistinctExec", 0, DistinctExec.IdxCounterNames)
    val d = DistinctExec(plan, spillyOpts(), node)
    val it = (0 until d.numPartitions).iterator.flatMap(d.execute)
    while (it.hasNext) it.next()
    val snap = node.snapshot()
    assertTrue(s"spillEvents must be > 0, got ${snap.counter("spillEvents")}",
      snap.counter("spillEvents") > 0L)
    assertTrue(s"bytesSpilled must be > 0, got ${snap.counter("bytesSpilled")}",
      snap.counter("bytesSpilled") > 0L)
    assertTrue(s"groupCount must be > 0, got ${snap.counter("groupCount")}",
      snap.counter("groupCount") > 0L)
    assertEquals(DistinctExec.KeyPathPacked, snap.counter("keyCodecPath"))
  }

  // ---- Regression: duplicate-named child schema under spill -----------------
  //
  // A child whose output schema has two columns sharing a name (`[a, a]`, as a
  // join output `[k, v, k, v]` would) used to write its spilled set with
  // duplicate parquet column paths, which read back null and NPE'd in
  // foldSpillFiles. The spill set now uses positional names. See
  // plans/bugfixes/01.
  @Test def duplicateNamedChildSchemaUnderSpill(): Unit = {
    val schema = Schema(Vector(Field("a", DataType.IntType), Field("a", DataType.IntType)))
    val rng = new Random(2026L)
    val parts = (0 until 4).map { _ =>
      (0 until 5000).map { _ =>
        Array[Any](
          java.lang.Integer.valueOf(rng.nextInt(60)),
          java.lang.Integer.valueOf(rng.nextInt(60)))
      }.toVector
    }.toVector
    val expected = runDistinct(schema, parts, ExecutionOptions.Default)

    // Spill run, drained manually so we can assert no positional-name leak.
    val plan = new InMemoryPlan(schema, parts)
    val node = new MetricsNode(0, "DistinctExec", 0, DistinctExec.IdxCounterNames)
    val d = DistinctExec(plan, spillyOpts(), node)
    val it = (0 until d.numPartitions).iterator.flatMap(d.execute)
    val buf = mutable.ArrayBuffer.empty[Vector[Any]]
    while (it.hasNext) {
      val b = it.next()
      assertEquals("emitted batch must carry the logical schema, not spilled _cN names",
        schema.fields.map(_.name), b.schema.fields.map(_.name))
      var r = 0
      while (r < b.numRows) {
        buf += Vector[Any](
          if (b.column(0).isNull(r)) null else b.column(0).getBoxed(r),
          if (b.column(1).isNull(r)) null else b.column(1).getBoxed(r))
        r += 1
      }
    }

    assertTrue("expected non-empty distinct set", expected.nonEmpty)
    assertEquals(expected, buf.toSet)
    assertTrue(s"spillEvents must be > 0, got ${node.snapshot().counter("spillEvents")}",
      node.snapshot().counter("spillEvents") > 0L)
  }
}
