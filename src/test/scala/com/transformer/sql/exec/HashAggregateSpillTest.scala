package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Random

/** Aggregate spill parity: with spill enabled at a tiny threshold, the codec
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

  /** SUM(DISTINCT v) / AVG(DISTINCT v): the partials' value sets merge before
    * the replay, so a value seen in two partitions counts once and a value seen
    * in only one still counts. Like COUNT(DISTINCT) the state has no spill
    * encoding, so asking for spill falls back to the in-memory path and must
    * give the same answer. */
  @Test def distinctSumAndAvgMergeAcrossPartitionsAndIgnoreSpill(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))
    // Partition p holds v = 2p+1 .. 2p+6 for keys 0 and 1: neighbours overlap by
    // four values, and together they cover 1..12. Losing a partial or counting
    // an overlap twice both change the answer.
    val parts = Vector.tabulate(4)(p => (for (k <- 0 to 1; v <- (2 * p + 1) to (2 * p + 6)) yield
      Array[Any](java.lang.Integer.valueOf(k), java.lang.Long.valueOf(v.toLong))).toVector)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    def run(opts: ExecutionOptions): Map[Any, (Any, Any, Any)] = {
      val agg = HashAggregateExec(
        new InMemoryPartitionedPlanForAggSpill(schema, parts),
        groupKeys = Seq((k, "k")),
        aggregates = Seq(
          (AggExprDistinct(AggExprSum(v)), "sd"),
          (AggExprDistinct(AggExprAvg(v)), "ad"),
          (AggExprSum(v), "s")),
        opts = opts)
      drainRows((0 until agg.numPartitions).iterator.flatMap(agg.execute), agg.outputSchema)
        .map(r => r(0) -> ((r(1), r(2), r(3)))).toMap
    }
    // DISTINCT: 1..12 sums to 78, averages 6.5. Plain SUM: 21 + 33 + 45 + 57.
    val expected = Map[Any, (Any, Any, Any)](0 -> ((78L, 6.5, 156L)), 1 -> ((78L, 6.5, 156L)))
    assertEquals(expected, run(ExecutionOptions.Default))
    assertEquals(expected, run(spillyOpts()))
  }

  // ---- Regression: name-colliding GROUP BY keys under spill -----------------
  //
  // `GROUP BY t0.k, t1.k` renders two group-key columns both named "k". The
  // spill schema used to keep those names, so writeCodecMap emitted a parquet
  // file with two columns named "k"; on read-back foldCodecSpillFiles got a
  // null page for the second and NPE'd. The spillSchema now names key columns
  // positionally (_k0, _k1). See plans/bugfixes/01.
  @Test def collidingGroupKeyNamesUnderSpill(): Unit = {
    val schema = Schema(Vector(
      Field("k0", DataType.IntType),
      Field("k1", DataType.IntType),
      Field("v", DataType.LongType)))
    val rng = new Random(2026L)
    val parts = (0 until 3).map { _ =>
      (0 until 3000).map { _ =>
        Array[Any](
          java.lang.Integer.valueOf(rng.nextInt(15)),
          java.lang.Integer.valueOf(rng.nextInt(15)),
          java.lang.Long.valueOf(rng.nextLong() & 0xFFFFL))
      }.toVector
    }.toVector
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, parts)
    val k0 = ColRefExpr(0, "k0", DataType.IntType)
    val k1 = ColRefExpr(1, "k1", DataType.IntType)
    val v = ColRefExpr(2, "v", DataType.LongType)
    // Both group keys aliased to the SAME name "k".
    def run(opts: ExecutionOptions, node: MetricsNode = null): Vector[Vector[Any]] = {
      val agg = HashAggregateExec(plan, Seq((k0, "k"), (k1, "k")),
        Seq((AggExprSum(v), "sumv")), opts, node)
      val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
      drainRows(out, agg.outputSchema)
    }
    val expected = run(ExecutionOptions.Default)
    val node = new MetricsNode(0, "HashAggregateExec", 0, HashAggregateExec.IdxCounterNames)
    val actual = run(spillyOpts(), node)
    assertTrue("expected non-empty result", expected.nonEmpty)
    assertEquals(expected.toSet, actual.toSet)
    // Prove the spill path actually ran — otherwise the regression is vacuous.
    assertTrue(s"spillEvents must be > 0, got ${node.snapshot().counter("spillEvents")}",
      node.snapshot().counter("spillEvents") > 0L)
  }

  // ---- Regression: MIN/MAX over a Boolean column under spill ----------------
  //
  // `MinMaxState` accumulated a Boolean extremum in the BOXED slot while its
  // spill format wrote the LONG slot, so a spilled state restored with
  // hasValue=true and a null boxed value: MIN(bool)/MAX(bool) came back NULL
  // under spill and correct off-spill. Booleans now ride the long slot as 0/1
  // on every path. Surfaced by `fuzz/mode_differential_fuzz_test`, which had
  // the shape gated out of its generator until the engine was fixed.
  @Test def booleanMinMaxUnderSpill(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("b", DataType.BooleanType)))
    val rng = new Random(20260725L)
    // Every group sees a mix, an all-TRUE run, or an all-FALSE run, so MIN and
    // MAX each return both values across the output and an all-TRUE MIN (the
    // value an unwritten long slot would misreport as FALSE) is covered.
    val parts = (0 until 3).map { p =>
      (0 until 3000).map { i =>
        val k = rng.nextInt(15)
        val b = k % 3 match {
          case 0 => true
          case 1 => false
          case _ => (i + p) % 2 == 0
        }
        Array[Any](java.lang.Integer.valueOf(k), java.lang.Boolean.valueOf(b))
      }.toVector
    }.toVector
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, parts)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val b = ColRefExpr(1, "b", DataType.BooleanType)
    def run(opts: ExecutionOptions, node: MetricsNode = null): Vector[Vector[Any]] = {
      val agg = HashAggregateExec(plan, Seq((k, "k")),
        Seq((AggExprMin(b), "minb"), (AggExprMax(b), "maxb")), opts, node)
      val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
      drainRows(out, agg.outputSchema)
    }
    val expected = run(ExecutionOptions.Default)
    val node = new MetricsNode(0, "HashAggregateExec", 0, HashAggregateExec.IdxCounterNames)
    val actual = run(spillyOpts(), node)
    assertTrue("expected non-empty result", expected.nonEmpty)
    assertTrue("expected a non-NULL MIN off-spill", expected.exists(_(1) != null))
    assertEquals(expected.toSet, actual.toSet)
    assertTrue(s"spillEvents must be > 0, got ${node.snapshot().counter("spillEvents")}",
      node.snapshot().counter("spillEvents") > 0L)
  }

  // ---- Regression: MIN/MAX over a NaN-carrying float column ----------------
  //
  // `MinMaxState` advanced its primitive `doubleCur` accumulator with raw IEEE
  // `v < doubleCur` / `v > doubleCur` in `updateAt`, `updateBatchAt` AND
  // `merge`. Every comparison against NaN is false in both directions, so the
  // answer depended on where the NaN landed: seen first it seeded the
  // accumulator and nothing could beat it, seen later it was skipped. MAX over
  // [NaN, 5.0] was NaN; over [5.0, NaN] it was 5.0. Because `merge` had the
  // same shape, partition fan-out and spill flush order moved the answer too.
  // All three sites now use `java.lang.Double.compare`, the total order
  // `Ops.cmp` already gives reference types, `ORDER BY` and GREATEST/LEAST:
  // NaN sorts above every other value, so MAX of a NaN-carrying column is NaN
  // and MIN is the smallest non-NaN. -0.0 sorts below 0.0 on the same order.

  /** The four values every NaN regression below aggregates, in a fixed order.
    * MIN is -2.0 and MAX is NaN under the total order, whatever the row order. */
  private val NanMix: Vector[Double] = Vector(Double.NaN, 5.0, -2.0, 1.0)

  private def nanMixSchema: Schema = Schema(Vector(
    Field("k", DataType.IntType),
    Field("d", DataType.DoubleType),
    Field("f", DataType.FloatType)))

  private def nanMixRow(k: Int, v: Double): Array[Any] = Array[Any](
    java.lang.Integer.valueOf(k),
    java.lang.Double.valueOf(v),
    java.lang.Float.valueOf(v.toFloat))

  /** MIN(d), MAX(d), MIN(f), MAX(f) over `parts`, grouped by `k` when
    * `grouped`, global otherwise. */
  private def runNanMinMax(
      parts: Vector[Vector[Array[Any]]],
      grouped: Boolean,
      opts: ExecutionOptions,
      node: MetricsNode = null): Vector[Vector[Any]] = {
    val schema = nanMixSchema
    val plan = new InMemoryPartitionedPlanForAggSpill(schema, parts)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val d = ColRefExpr(1, "d", DataType.DoubleType)
    val f = ColRefExpr(2, "f", DataType.FloatType)
    val agg = HashAggregateExec(
      plan,
      if (grouped) Seq((k, "k")) else Seq.empty,
      Seq((AggExprMin(d), "mind"), (AggExprMax(d), "maxd"),
        (AggExprMin(f), "minf"), (AggExprMax(f), "maxf")),
      opts, node)
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    drainRows(out, agg.outputSchema)
  }

  /** The MIN/MAX cells of a one-row result, as the total order requires. */
  private def assertNanMixExtrema(label: String, row: Vector[Any], offset: Int): Unit = {
    assertEquals(s"$label MIN(d)", java.lang.Double.valueOf(-2.0), row(offset))
    assertEquals(s"$label MAX(d)", java.lang.Double.valueOf(Double.NaN), row(offset + 1))
    assertEquals(s"$label MIN(f)", java.lang.Float.valueOf(-2.0f), row(offset + 2))
    assertEquals(s"$label MAX(f)", java.lang.Float.valueOf(Float.NaN), row(offset + 3))
  }

  @Test def minMaxOverNaNIsRowOrderIndependent(): Unit = {
    // One group per rotation of the same four values: every group holds the
    // same multiset, only the row order differs. Grouped aggregation takes
    // `MinMaxState.updateAt`.
    val rows = NanMix.indices.flatMap { g =>
      NanMix.indices.map(i => nanMixRow(g, NanMix((g + i) % NanMix.length)))
    }.toVector
    val out = runNanMinMax(Vector(rows), grouped = true, ExecutionOptions.Default)
    assertEquals(NanMix.length, out.length)
    out.foreach(r => assertNanMixExtrema(s"group ${r(0)}", r, 1))
  }

  @Test def globalMinMaxOverNaNIsRowOrderIndependent(): Unit = {
    // No GROUP BY: the whole-batch `MinMaxState.updateBatchAt` path.
    val forward = NanMix.map(nanMixRow(0, _))
    val reversed = forward.reverse
    val a = runNanMinMax(Vector(forward), grouped = false, ExecutionOptions.Default)
    val b = runNanMinMax(Vector(reversed), grouped = false, ExecutionOptions.Default)
    assertEquals(1, a.length)
    assertNanMixExtrema("forward", a.head, 0)
    assertNanMixExtrema("reversed", b.head, 0)
  }

  @Test def minMaxOverNaNIsPartitionSplitIndependent(): Unit = {
    // One row per partition, so every value arrives through `MinMaxState.merge`
    // rather than through an update loop. Each permutation of the partitions
    // must give the same answer.
    val perms = Vector(
      Vector(0, 1, 2, 3), Vector(3, 2, 1, 0), Vector(1, 3, 0, 2), Vector(2, 0, 3, 1))
    perms.foreach { perm =>
      val parts = perm.map(i => Vector(nanMixRow(0, NanMix(i))))
      val out = runNanMinMax(parts, grouped = false, ExecutionOptions.Default)
      assertEquals(1, out.length)
      assertNanMixExtrema(s"partitions ${perm.mkString(",")}", out.head, 0)
    }
  }

  @Test def minMaxOverNaNUnderSpillMatchesHeap(): Unit = {
    // Spill flushes partial states to disk and folds them back through `merge`,
    // so the restored NaN meets the accumulator in flush order rather than row
    // order. Enough groups and rows to force several runs.
    val rng = new Random(20260909L)
    val parts = (0 until 3).map { _ =>
      (0 until 3000).map(i => nanMixRow(rng.nextInt(15), NanMix(i % NanMix.length))).toVector
    }.toVector
    val expected = runNanMinMax(parts, grouped = true, ExecutionOptions.Default)
    val node = new MetricsNode(0, "HashAggregateExec", 0, HashAggregateExec.IdxCounterNames)
    val actual = runNanMinMax(parts, grouped = true, spillyOpts(), node)
    assertTrue("expected non-empty result", expected.nonEmpty)
    expected.foreach(r => assertNanMixExtrema(s"heap group ${r(0)}", r, 1))
    actual.foreach(r => assertNanMixExtrema(s"spill group ${r(0)}", r, 1))
    // Group keys only: a whole-row multiset compare cannot be used here, because
    // Scala's `==` on a boxed NaN is primitive `NaN == NaN`, which is false, so
    // two identical NaN-carrying rows would never match. `assertNanMixExtrema`
    // above compares the aggregate cells with `java.lang.Double.equals`, which
    // is bit-exact and agrees with itself on NaN.
    assertEquals(expected.map(_(0)).toSet, actual.map(_(0)).toSet)
    assertEquals(expected.length, actual.length)
    assertTrue(s"spillEvents must be > 0, got ${node.snapshot().counter("spillEvents")}",
      node.snapshot().counter("spillEvents") > 0L)
  }

  @Test def minMaxOverSignedZeroFollowsTheTotalOrder(): Unit = {
    // The same total order separates -0.0 from 0.0, where raw IEEE finds them
    // equal and keeps whichever arrived first. One value per partition so the
    // merge site decides, and both orders must agree.
    def run(values: Vector[Double]): Vector[Any] = {
      val parts = values.map(v => Vector(nanMixRow(0, v)))
      val out = runNanMinMax(parts, grouped = false, ExecutionOptions.Default)
      assertEquals(1, out.length)
      out.head
    }
    Vector(Vector(0.0, -0.0), Vector(-0.0, 0.0)).foreach { values =>
      val row = run(values)
      val label = values.mkString(",")
      assertEquals(s"$label MIN(d)", java.lang.Double.valueOf(-0.0), row(0))
      assertEquals(s"$label MAX(d)", java.lang.Double.valueOf(0.0), row(1))
      assertEquals(s"$label MIN(f)", java.lang.Float.valueOf(-0.0f), row(2))
      assertEquals(s"$label MAX(f)", java.lang.Float.valueOf(0.0f), row(3))
    }
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
