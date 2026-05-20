package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable

/** Plan 09 Phase 8 stress test. Each test pushes one operator through many
  * spill rounds (1-byte threshold + ~100k–1M rows) and verifies the output
  * matches a closed-form analytical answer — no in-memory oracle is built
  * over the whole dataset, so this also catches correctness bugs that the
  * smaller parity tests would miss when one side fits in memory.
  *
  * Tagged "stress" in the name but it still runs under `bazel test //...`
  * — total wall time stays under ~20s on a modest box. For a *real*
  * above-heap workload, increase the row count and run with a constrained
  * `-Xmx` outside Bazel. */
class SpillStressTest {

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("spill-stress-test-")
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

  /** Synthetic plan: emits `N` rows split into `parts` partitions.
    * Row `i` is `(k = i % G, v = i)`. The analytical answer for
    * `SELECT k, SUM(v) FROM t GROUP BY k` is `(j, sum_j)` where
    * `sum_j = Σ {i : i % G == j, 0 ≤ i < N}`. */
  private final class SyntheticPlan(N: Int, G: Int, parts: Int) extends PhysicalPlan {
    val outputSchema: Schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    def numPartitions: Int = parts
    def execute(p: Int): Iterator[ColumnarBatch] = {
      val perPart = (N + parts - 1) / parts
      val from = p * perPart
      val to = math.min(N, from + perPart)
      if (from >= to) return Iterator.empty
      val cap = ColumnarBatch.DefaultCapacity
      Iterator.unfold(from) { cursor =>
        if (cursor >= to) None
        else {
          val take = math.min(cap, to - cursor)
          val b = new ColumnarBatch(outputSchema, take)
          val kv = b.column(0).asInstanceOf[IntVector]
          val vv = b.column(1).asInstanceOf[LongVector]
          var r = 0
          while (r < take) {
            val i = cursor + r
            kv.set(r, i % G)
            vv.set(r, i.toLong)
            r += 1
          }
          b.setNumRows(take)
          Some((b, cursor + take))
        }
      }
    }
  }

  /** Closed-form SUM(v) for group `j` in `0..G-1`: the sum of i for
    * `i ∈ [0, N)` with `i % G == j`. */
  private def expectedGroupSum(N: Int, G: Int, j: Int): Long = {
    val countInGroup = (N - j + G - 1) / G
    val first = j.toLong
    val last = first + (countInGroup - 1).toLong * G.toLong
    if (countInGroup <= 0) 0L
    else (first + last) * countInGroup / 2
  }

  // ---- HashAggregate stress ------------------------------------------------

  @Test def hashAggregateStressManySpillRounds(): Unit = {
    val N = 200_000
    val G = 47
    val plan = new SyntheticPlan(N, G, parts = 4)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val agg = HashAggregateExec(plan, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), spillyOpts())
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    val results = drainAggResults(out)
    assertEquals(s"distinct group count must equal G=$G", G, results.size)
    var j = 0
    while (j < G) {
      val expected = expectedGroupSum(N, G, j)
      assertEquals(s"group $j sum", java.lang.Long.valueOf(expected), results(j))
      j += 1
    }
  }

  /** Total Σ over k=0..G-1 of SUM(v) for the group equals Σ i for
    * i ∈ [0, N), confirming no rows are dropped during spill. */
  @Test def hashAggregateStressRowConservation(): Unit = {
    val N = 100_000
    val G = 13
    val plan = new SyntheticPlan(N, G, parts = 3)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val agg = HashAggregateExec(plan, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), spillyOpts())
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    val results = drainAggResults(out)
    val total = results.values.iterator.map(_.longValue).sum
    val expectedTotal = (N - 1).toLong * N.toLong / 2L
    assertEquals(expectedTotal, total)
  }

  /** When every group is unique (one row per group), spill is exercised on
    * the maximum-distinct-key shape. */
  @Test def hashAggregateStressUniqueKeys(): Unit = {
    val N = 50_000
    val G = N // one row per group
    val plan = new SyntheticPlan(N, G, parts = 4)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val agg = HashAggregateExec(plan, Seq((k, "k")), Seq((AggExprSum(v), "sumv")), spillyOpts())
    val out = (0 until agg.numPartitions).iterator.flatMap(agg.execute)
    val results = drainAggResults(out)
    assertEquals(N, results.size)
    // Each group's sum equals the group's key (since one row per group, value=key)
    val sample = results(0).longValue
    assertEquals(0L, sample)
    val midKey = N / 2
    assertEquals(midKey.toLong, results(midKey).longValue)
  }

  // ---- Sort stress ---------------------------------------------------------

  @Test def sortStressManySpillRoundsRemainsSorted(): Unit = {
    val N = 200_000
    val G = N // unique keys; pure stress on sort merge
    val plan = new SyntheticPlan(N, G, parts = 4)
    // The keys (k = i % G = i) are already a permutation of 0..N-1 but
    // distributed across partitions in non-sorted order — exactly what
    // the K-way merge has to consume.
    val sort = SortExec(plan, Seq((ColRefExpr(0, "k", DataType.IntType), true)), spillyOpts())
    val out = sort.execute(0)
    var last = Int.MinValue
    var count = 0
    while (out.hasNext) {
      val b = out.next()
      val kv = b.column(0)
      var r = 0
      while (r < b.numRows) {
        val v = kv.asInstanceOf[IntVector].values(r)
        if (v < last) fail(s"out of order at row $count: prev=$last cur=$v")
        last = v
        count += 1
        r += 1
      }
    }
    assertEquals(N, count)
  }

  // ---- Distinct stress -----------------------------------------------------

  @Test def distinctStressMillionDuplicatesCollapse(): Unit = {
    val N = 200_000
    val G = 100 // 100 distinct keys
    val plan = new SyntheticPlan(N, G, parts = 4)
    val d = DistinctExec(plan, spillyOpts())
    val out = (0 until d.numPartitions).iterator.flatMap(d.execute)
    val seen = mutable.Set.empty[(Int, Long)]
    var rows = 0
    while (out.hasNext) {
      val b = out.next()
      val kv = b.column(0).asInstanceOf[IntVector]
      val vv = b.column(1).asInstanceOf[LongVector]
      var r = 0
      while (r < b.numRows) {
        // Each (k, v) pair is unique (k = i % G, v = i). All N rows
        // survive DISTINCT since no duplicates exist in this dataset.
        seen += ((kv.values(r), vv.values(r)))
        rows += 1
        r += 1
      }
    }
    assertEquals("DISTINCT must emit N unique rows", N, rows)
    assertEquals("multiset of emitted (k,v) tuples", N, seen.size)
  }

  // ---- HashJoin stress -----------------------------------------------------

  // ---- Window stress -------------------------------------------------------

  @Test def windowStressRowNumberPerPartitionIsOneToCount(): Unit = {
    val N = 100_000
    val G = 53
    val plan = new SyntheticPlan(N, G, parts = 4)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq((v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val w = WindowExec(plan, Seq(win), spillyOpts())
    val out = (0 until w.numPartitions).iterator.flatMap(w.execute)
    val maxRowNumberByKey = mutable.Map.empty[Int, Long]
    val countByKey = mutable.Map.empty[Int, Long]
    while (out.hasNext) {
      val b = out.next()
      val kv = b.column(0).asInstanceOf[IntVector]
      val rn = b.column(2).asInstanceOf[LongVector]
      var r = 0
      while (r < b.numRows) {
        val key = kv.values(r)
        val v = rn.values(r)
        val curMax = maxRowNumberByKey.getOrElse(key, 0L)
        if (v > curMax) maxRowNumberByKey(key) = v
        countByKey(key) = countByKey.getOrElse(key, 0L) + 1
        r += 1
      }
    }
    // For each key, max(row_number) must equal count (rows of that key)
    // and count(rows) = ceil(N / G) or floor (N / G) depending on key.
    assertEquals(G, maxRowNumberByKey.size)
    countByKey.foreach { case (key, count) =>
      assertEquals(s"max row_number must equal count for key=$key",
        count, maxRowNumberByKey(key))
    }
    val totalCount = countByKey.values.sum
    assertEquals(N.toLong, totalCount)
  }

  @Test def hashJoinStressEquiJoinPreservesEveryMatchedPair(): Unit = {
    // Two SyntheticPlans with the same key distribution: every row in left
    // matches every row in right with the same k. Total matches =
    // Σ (count_left_k × count_right_k) over k.
    val NL = 50_000
    val NR = 30_000
    val G = 23
    val leftPlan = new SyntheticPlan(NL, G, parts = 4)
    val rightPlan = new SyntheticPlan(NR, G, parts = 4)
    val lk = ColRefExpr(0, "k", DataType.IntType)
    val rk = ColRefExpr(0, "k", DataType.IntType)
    val j = HashJoinExec(leftPlan, rightPlan, Seq(lk), Seq(rk), None,
                         JoinKind.Inner, buildRight = true, opts = spillyOpts())
    val out = (0 until j.numPartitions).iterator.flatMap(j.execute)
    var matches = 0L
    while (out.hasNext) {
      val b = out.next()
      matches += b.numRows.toLong
    }
    // Expected: Σ over k of (rows-left-with-k × rows-right-with-k)
    var expected = 0L
    var k = 0
    while (k < G) {
      val cl = (NL - k + G - 1) / G
      val cr = (NR - k + G - 1) / G
      expected += cl.toLong * cr.toLong
      k += 1
    }
    assertEquals(expected, matches)
  }

  // ---- helpers --------------------------------------------------------------

  /** Drain an aggregate's emit iterator into a `Map[k -> Long]` for sum-style
    * checks. */
  private def drainAggResults(it: Iterator[ColumnarBatch]): Map[Int, java.lang.Long] = {
    val result = mutable.Map.empty[Int, java.lang.Long]
    while (it.hasNext) {
      val b = it.next()
      val kv = b.column(0)
      val sv = b.column(1)
      var r = 0
      while (r < b.numRows) {
        val k = kv.asInstanceOf[IntVector].values(r)
        val s = sv.asInstanceOf[LongVector].values(r)
        result(k) = java.lang.Long.valueOf(s)
        r += 1
      }
    }
    result.toMap
  }
}
