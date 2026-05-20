package com.transformer.core.metrics

import com.transformer.core._
import com.transformer.sql.exec._
import com.transformer.sql.plan._

import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable

/** Per-operator instrumentation tests — Sub-plan 2.
  *
  * Each test:
  *   1. Builds a tiny in-memory fixture for one operator.
  *   2. Enables metrics by passing a freshly-constructed [[MetricsNode]] sized
  *      by the operator's `IdxCounterNames.length`.
  *   3. Drains the operator's output to populate counters.
  *   4. Asserts the per-counter values match the expected counts.
  *
  * Index-drift guard: every operator test also asserts that
  * `IdxCounterNames.length == highest Idx<Name> + 1`. Adding a new counter
  * without bumping the names array (or without bumping the index constant)
  * trips this immediately.
  */
class OperatorCountersTest {

  // ------------------------------------------------------------------
  // Index-drift guards: assert each operator's IdxCounterNames is the
  // correct length for its Idx<Name> constants. The right-hand side is
  // the maximum index + 1 we defined in the operator's companion.
  // ------------------------------------------------------------------

  @Test def hashAggregateExecIdxCountersInSync(): Unit = {
    assertEquals(
      "HashAggregateExec.IdxCounterNames.length must equal highest Idx + 1",
      HashAggregateExec.IdxPeakInMemoryBytes + 1,
      HashAggregateExec.IdxCounterNames.length)
  }

  @Test def hashJoinExecIdxCountersInSync(): Unit = {
    assertEquals(
      "HashJoinExec.IdxCounterNames.length must equal highest Idx + 1",
      HashJoinExec.IdxPeakBucketBytes + 1,
      HashJoinExec.IdxCounterNames.length)
  }

  @Test def sortExecIdxCountersInSync(): Unit = {
    assertEquals(
      "SortExec.IdxCounterNames.length must equal highest Idx + 1",
      SortExec.IdxRunDecodeNanos + 1,
      SortExec.IdxCounterNames.length)
  }

  @Test def distinctExecIdxCountersInSync(): Unit = {
    assertEquals(
      "DistinctExec.IdxCounterNames.length must equal highest Idx + 1",
      DistinctExec.IdxPeakInMemoryBytes + 1,
      DistinctExec.IdxCounterNames.length)
  }

  @Test def windowExecIdxCountersInSync(): Unit = {
    assertEquals(
      "WindowExec.IdxCounterNames.length must equal highest Idx + 1",
      WindowExec.IdxPeakBufferedRows + 1,
      WindowExec.IdxCounterNames.length)
  }

  @Test def scanExecIdxCountersInSync(): Unit = {
    assertEquals(
      "ScanExec.IdxCounterNames.length must equal highest Idx + 1",
      ScanExec.IdxPredicatePushedDownCount + 1,
      ScanExec.IdxCounterNames.length)
  }

  @Test def exchangeExecCounterNamesShapeByShardCount(): Unit = {
    // ExchangeExec's counter array is per-instance (varies with numShards).
    // The shape is `[keyNullCount, rowsRoutedShard0, rowsRoutedShard1, ...]`.
    val k4 = ExchangeExec.counterNamesFor(4)
    assertEquals(5, k4.length)
    assertEquals("keyNullCount", k4(0))
    assertEquals("rowsRoutedShard0", k4(1))
    assertEquals("rowsRoutedShard3", k4(4))
    val k1 = ExchangeExec.counterNamesFor(1)
    assertEquals(2, k1.length)
    assertEquals("rowsRoutedShard0", k1(1))
  }

  // ------------------------------------------------------------------
  // HashAggregateExec — populated counters
  // ------------------------------------------------------------------

  @Test def hashAggregateExecPopulatesInMemoryCounters(): Unit = {
    // Build a (k, v) plan: 4 distinct keys, 100 rows total.
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    val rows = (0 until 100).map { i =>
      Array[Any](
        java.lang.Integer.valueOf(i % 4),
        java.lang.Long.valueOf(i.toLong))
    }.toVector
    val plan = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val node = new MetricsNode(
      id = 0, name = "HashAggregateExec", partition = 0,
      counterNames = HashAggregateExec.IdxCounterNames)
    val agg = HashAggregateExec(
      plan,
      groupKeys = Seq((k, "k")),
      aggregates = Seq((AggExprSum(v), "sumv")),
      opts = ExecutionOptions.Default,
      metricsNode = node)
    // Drain.
    drain(agg)
    val snap = node.snapshot()
    assertEquals(4L, snap.counter("groupCount"))
    assertTrue("hashMapPeakSize > 0", snap.counter("hashMapPeakSize") > 0L)
    // Single Int ColRef → useLongKey → KeyPathLong = 0
    assertEquals(HashAggregateExec.KeyPathLong, snap.counter("keyCodecPath"))
    assertTrue("partialAggregateNanos > 0", snap.counter("partialAggregateNanos") > 0L)
    // Spill never triggered in this fixture.
    assertEquals(0L, snap.counter("spillEvents"))
    assertEquals(0L, snap.counter("bytesSpilled"))
    assertEquals(0L, snap.counter("spillRunsWritten"))
  }

  @Test def hashAggregateExecPacksMultiKeyKeyCodecPath(): Unit = {
    // Two-column GROUP BY (Int + String) forces the Object codec path.
    val schema = Schema(Vector(
      Field("a", DataType.IntType),
      Field("b", DataType.StringType),
      Field("v", DataType.LongType)))
    val rows = (0 until 50).map { i =>
      Array[Any](
        java.lang.Integer.valueOf(i % 3),
        s"k${i % 5}",
        java.lang.Long.valueOf(i.toLong))
    }.toVector
    val plan = new InMemoryPlan(schema, Vector(rows))
    val a = ColRefExpr(0, "a", DataType.IntType)
    val b = ColRefExpr(1, "b", DataType.StringType)
    val v = ColRefExpr(2, "v", DataType.LongType)
    val node = new MetricsNode(0, "HashAggregateExec", 0, HashAggregateExec.IdxCounterNames)
    val agg = HashAggregateExec(
      plan, Seq((a, "a"), (b, "b")), Seq((AggExprSum(v), "sumv")),
      ExecutionOptions.Default, node)
    drain(agg)
    val snap = node.snapshot()
    // Two-column keys with a String → Object codec.
    assertEquals(HashAggregateExec.KeyPathObject, snap.counter("keyCodecPath"))
    // Up to 3 * 5 = 15 distinct (a, b) pairs.
    assertTrue("groupCount must be 1..15", snap.counter("groupCount") >= 1L && snap.counter("groupCount") <= 15L)
  }

  // ------------------------------------------------------------------
  // HashJoinExec — populated counters
  // ------------------------------------------------------------------

  @Test def hashJoinExecPopulatesInMemoryCounters(): Unit = {
    val leftSchema = Schema(Vector(Field("k", DataType.IntType), Field("lv", DataType.LongType)))
    val rightSchema = Schema(Vector(Field("k", DataType.IntType), Field("rv", DataType.LongType)))
    val leftRows = (0 until 50).map(i => Array[Any](
      java.lang.Integer.valueOf(i % 10), java.lang.Long.valueOf(i.toLong))).toVector
    val rightRows = (0 until 20).map(i => Array[Any](
      java.lang.Integer.valueOf(i % 10), java.lang.Long.valueOf((i * 100).toLong))).toVector
    val l = new InMemoryPlan(leftSchema, Vector(leftRows))
    val r = new InMemoryPlan(rightSchema, Vector(rightRows))
    val lk = ColRefExpr(0, "k", DataType.IntType)
    val rk = ColRefExpr(0, "k", DataType.IntType)
    val node = new MetricsNode(0, "HashJoinExec", 0, HashJoinExec.IdxCounterNames)
    val j = HashJoinExec(l, r, Seq(lk), Seq(rk), None, JoinKind.Inner,
      buildRight = true, ExecutionOptions.Default, node)
    drain(j)
    val snap = node.snapshot()
    assertEquals(20L, snap.counter("buildSideRows"))     // right side (50 if buildRight=false)
    assertTrue("probeSideRows > 0", snap.counter("probeSideRows") > 0L)
    assertTrue("matchedRows > 0", snap.counter("matchedRows") > 0L)
    assertTrue("buildNanos > 0", snap.counter("buildNanos") > 0L)
    assertTrue("probeNanos > 0", snap.counter("probeNanos") > 0L)
    assertEquals(HashJoinExec.KeyPathLong, snap.counter("keyCodecPath"))
    // No grace hash: bucket / spill counters all zero.
    assertEquals(0L, snap.counter("bucketCount"))
    assertEquals(0L, snap.counter("bytesSpilledBuild"))
    assertEquals(0L, snap.counter("bytesSpilledProbe"))
  }

  @Test def hashJoinExecCountsUnmatchedBuildForLeftOuter(): Unit = {
    val leftSchema = Schema(Vector(Field("k", DataType.IntType), Field("lv", DataType.LongType)))
    val rightSchema = Schema(Vector(Field("k", DataType.IntType), Field("rv", DataType.LongType)))
    // Left has 5 keys, right has 3 — LEFT outer must surface 2 unmatched left rows
    // (built into the keymap since buildRight=false here).
    val leftRows: Vector[Array[Any]] = (1 to 5).map(i =>
      Array[Any](java.lang.Integer.valueOf(i), java.lang.Long.valueOf(i.toLong))).toVector
    val rightRows: Vector[Array[Any]] = (1 to 3).map(i =>
      Array[Any](java.lang.Integer.valueOf(i), java.lang.Long.valueOf(i.toLong * 100))).toVector
    val l = new InMemoryPlan(leftSchema, Vector(leftRows))
    val r = new InMemoryPlan(rightSchema, Vector(rightRows))
    val lk = ColRefExpr(0, "k", DataType.IntType)
    val rk = ColRefExpr(0, "k", DataType.IntType)
    val node = new MetricsNode(0, "HashJoinExec", 0, HashJoinExec.IdxCounterNames)
    // LEFT outer + buildRight = false → build = left, probe = right; the preserved
    // side stays the build, so unmatched-build emission fires for keys 4, 5.
    val j = HashJoinExec(l, r, Seq(lk), Seq(rk), None, JoinKind.Left,
      buildRight = false, ExecutionOptions.Default, node)
    drain(j)
    val snap = node.snapshot()
    assertEquals(2L, snap.counter("unmatchedRows"))
  }

  // ------------------------------------------------------------------
  // SortExec — populated counters
  // ------------------------------------------------------------------

  @Test def sortExecPopulatesInMemoryCounters(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType)))
    val rows = (0 until 100).reverse.map(i =>
      Array[Any](java.lang.Integer.valueOf(i))).toVector
    val plan = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val node = new MetricsNode(0, "SortExec", 0, SortExec.IdxCounterNames)
    val sort = SortExec(plan, Seq((k, true)), ExecutionOptions.Default, node)
    drain(sort)
    val snap = node.snapshot()
    assertEquals(100L, snap.counter("inputRows"))
    assertEquals(100L, snap.counter("outputRows"))
    // Single-cursor or small-N fallback fires below 4096 rows.
    assertEquals(SortExec.PathSmallN, snap.counter("path"))
    // No spill on the default opts.
    assertEquals(0L, snap.counter("runsWritten"))
    assertEquals(0L, snap.counter("bytesSpilled"))
  }

  // ------------------------------------------------------------------
  // DistinctExec — populated counters
  // ------------------------------------------------------------------

  @Test def distinctExecPopulatesInMemoryCounters(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType)))
    val rows = (0 until 100).map(i =>
      Array[Any](java.lang.Integer.valueOf(i % 7))).toVector  // 7 distinct
    val plan = new InMemoryPlan(schema, Vector(rows))
    val node = new MetricsNode(0, "DistinctExec", 0, DistinctExec.IdxCounterNames)
    val d = DistinctExec(plan, ExecutionOptions.Default, node)
    drain(d)
    val snap = node.snapshot()
    assertEquals(7L, snap.counter("groupCount"))
    assertTrue("hashMapPeakSize > 0", snap.counter("hashMapPeakSize") > 0L)
    assertTrue("partialAggregateNanos > 0", snap.counter("partialAggregateNanos") > 0L)
    // Single Int → Packed codec.
    assertEquals(DistinctExec.KeyPathPacked, snap.counter("keyCodecPath"))
    assertEquals(0L, snap.counter("spillEvents"))
  }

  // ------------------------------------------------------------------
  // WindowExec — populated counters
  // ------------------------------------------------------------------

  @Test def windowExecPopulatesInMemoryCounters(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    val rows = (0 until 50).map(i => Array[Any](
      java.lang.Integer.valueOf(i % 5),
      java.lang.Long.valueOf(i.toLong))).toVector
    val plan = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq((v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val node = new MetricsNode(0, "WindowExec", 0, WindowExec.IdxCounterNames)
    val w = WindowExec(plan, Seq(win), ExecutionOptions.Default, node)
    drain(w)
    val snap = node.snapshot()
    assertEquals(5L, snap.counter("partitionCount"))
    assertEquals(10L, snap.counter("peakPartitionRows"))
    assertTrue("frameEvalNanos > 0", snap.counter("frameEvalNanos") > 0L)
    // No spill.
    assertEquals(0L, snap.counter("partitionSpillEvents"))
    assertEquals(0L, snap.counter("bytesSpilled"))
  }

  // ------------------------------------------------------------------
  // ExchangeExec — populated counters
  // ------------------------------------------------------------------

  @Test def exchangeExecPopulatesPerShardCounters(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType)))
    val rows = (0 until 40).map(i =>
      Array[Any](java.lang.Integer.valueOf(i % 4))).toVector
    val plan = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val numShards = 4
    val names = ExchangeExec.counterNamesFor(numShards)
    val node = new MetricsNode(0, "ExchangeExec", 0, names)
    val exch = ExchangeExec(plan, Seq(k), numShards, HashPartitioner.NullsToLast, node)
    // Materialize all shards.
    (0 until numShards).foreach(s => drainPartition(exch, s))
    val snap = node.snapshot()
    // No NULL keys in the fixture.
    assertEquals(0L, snap.counter("keyNullCount"))
    // Each row routed to exactly one shard; total across shards = 40.
    val perShard = (0 until numShards).map { s =>
      snap.counter(s"rowsRoutedShard$s")
    }
    assertEquals(40L, perShard.sum)
    // At least one shard must have received rows. We don't assert uniformity —
    // hash skew on a small fixture can leave shards empty even with uniform
    // keys. Skew detection is what these counters are FOR.
    assertTrue("at least one shard must be populated", perShard.exists(_ > 0L))
  }

  @Test def exchangeExecCountsNullKeys(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType)))
    val rows: Vector[Array[Any]] = Vector(
      Array[Any](java.lang.Integer.valueOf(1)),
      Array[Any](null),
      Array[Any](java.lang.Integer.valueOf(2)),
      Array[Any](null),
      Array[Any](null))
    val plan = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val numShards = 2
    val names = ExchangeExec.counterNamesFor(numShards)
    val node = new MetricsNode(0, "ExchangeExec", 0, names)
    val exch = ExchangeExec(plan, Seq(k), numShards, HashPartitioner.NullsToLast, node)
    (0 until numShards).foreach(s => drainPartition(exch, s))
    val snap = node.snapshot()
    assertEquals(3L, snap.counter("keyNullCount"))
  }

  // ------------------------------------------------------------------
  // Composition: metrics + result equivalence
  // ------------------------------------------------------------------

  @Test def metricsEnabledDoesNotChangeRows(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    val rows = (0 until 200).map(i => Array[Any](
      java.lang.Integer.valueOf(i % 10),
      java.lang.Long.valueOf(i.toLong))).toVector
    val planA = new InMemoryPlan(schema, Vector(rows))
    val planB = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val opts = ExecutionOptions.Default
    val aggA = HashAggregateExec(planA, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), opts, null)
    val node = new MetricsNode(0, "HashAggregateExec", 0, HashAggregateExec.IdxCounterNames)
    val aggB = HashAggregateExec(planB, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), opts, node)
    val rowsA = drain(aggA)
    val rowsB = drain(aggB)
    assertEquals(rowsA.toSet, rowsB.toSet)
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  private def drain(plan: PhysicalPlan): Vector[Vector[Any]] = {
    val buf = mutable.ArrayBuffer.empty[Vector[Any]]
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

  private def drainPartition(plan: PhysicalPlan, partition: Int): Unit = {
    val it = plan.execute(partition)
    while (it.hasNext) it.next()
  }
}

/** Plan that emits a fixed schema and pre-built rows partitioned across N
  * partitions. Same shape as the operator-spill test fixtures; kept inside
  * the test source so each test target stays standalone. */
private final class InMemoryPlan(
    schema: Schema,
    partitions: Vector[Vector[Array[Any]]]) extends PhysicalPlan {
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
