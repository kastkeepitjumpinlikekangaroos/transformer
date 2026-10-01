package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Random

/** Bucketed-window spill parity: with `opts.spillEnabled` and a PARTITION BY
  * present, [[WindowExec]] routes child rows through K disk buckets
  * keyed by `hash(partitionKey)`. Bucket processing must produce the
  * same per-row window values as the in-memory path — every row sharing
  * a partition key collocates in the same bucket, so LAG/LEAD/RANK/
  * aggregate frames all see a complete view of their partition.
  *
  * We compare by (childRow → windowOutputColumns) keyed by the child
  * row, so output order differences (buckets emit sequentially) don't
  * mask correctness. For ties on the partition key + non-unique
  * ORDER BY rows, both paths produce the same multiset under their
  * (unstable) sorts.
  *
  * The file also pins `RANK`/`DENSE_RANK` peer semantics against the
  * engine's total order (the last section), on both the heap and the
  * spill path — those ranks are a property of the sort, not of spill,
  * but asserting them through both paths costs one extra run. */
class WindowExecSpillTest {

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("window-spill-test-")
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

  // ---- helpers -------------------------------------------------------------

  /** Plan that emits a fixed schema and a single partition of pre-built rows. */
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

  private def multisetOf(v: Vector[Vector[Any]]): Map[Vector[Any], Int] =
    v.groupBy(identity).view.mapValues(_.size).toMap

  /** Build a (k, v) test plan: k ∈ [0, K), v ∈ [0, N) with no key→v
    * correlation. Used by row_number / rank / lag / lead tests. */
  private def kvPlan(N: Int, K: Int, seed: Long): InMemoryPlan = {
    val rng = new Random(seed)
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    val rows = (0 until N).map { _ =>
      Array[Any](
        java.lang.Integer.valueOf(rng.nextInt(K)),
        java.lang.Long.valueOf(rng.nextLong() & 0xFFFFL))
    }.toVector
    new InMemoryPlan(schema, Vector(rows))
  }

  // ---- ROW_NUMBER / RANK / DENSE_RANK -------------------------------------

  @Test def rowNumberParityOverPartitionByOrderBy(): Unit = {
    val plan = kvPlan(N = 2000, K = 7, seed = 42L)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq(SortKey(v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val expected = drainRows(
      WindowExec(plan, Seq(win)).execute(0), Schema(plan.outputSchema.fields :+ Field("rn", DataType.LongType)))
    val actual = drainRows(
      WindowExec(plan, Seq(win), spillyOpts()).execute(0),
      Schema(plan.outputSchema.fields :+ Field("rn", DataType.LongType)))
    // Per-row equivalence keyed by (k, v): for a given (k, v) the row_number
    // is deterministic only if (k, v) is unique within its partition.
    // We assert multiset equality on (k, rn) — ROW_NUMBER within each
    // partition must be 1..|partition| regardless of row order.
    val byKey: Vector[(Int, Long)] =
      expected.map(row => (row(0).asInstanceOf[Integer].intValue, row(2).asInstanceOf[java.lang.Long].longValue))
    val actByKey: Vector[(Int, Long)] =
      actual.map(row => (row(0).asInstanceOf[Integer].intValue, row(2).asInstanceOf[java.lang.Long].longValue))
    // Within each key, the multiset of row_numbers must be 1..N_k.
    expected.groupBy(_(0)).foreach { case (key, rows) =>
      val rns = rows.map(_(2).asInstanceOf[java.lang.Long].longValue).sorted
      val actRns = actual.filter(_(0) == key)
        .map(_(2).asInstanceOf[java.lang.Long].longValue).sorted
      assertEquals(s"row_number multiset for key=$key", rns, actRns)
      assertEquals(s"row_number must be 1..${rows.size} for key=$key",
        (1L to rows.size).toVector, rns.toVector)
    }
  }

  // ---- SUM as whole-partition aggregate -----------------------------------

  /** SUM(v) over whole partition (no ORDER BY) must broadcast the same
    * per-partition sum to every row in the partition under spill. */
  @Test def whorlAggregateBroadcastParity(): Unit = {
    val plan = kvPlan(N = 5000, K = 13, seed = 99L)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq.empty, WindowFrame.defaultFor(hasOrderBy = false))
    val win = WindowDef(spec, WindowFnAgg(AggExprSum(v)), "sumv")
    val outSchema = Schema(plan.outputSchema.fields :+ Field("sumv", DataType.LongType))
    val expected = drainRows(WindowExec(plan, Seq(win)).execute(0), outSchema)
    val actual = drainRows(WindowExec(plan, Seq(win), spillyOpts()).execute(0), outSchema)

    // For each key, every row should have the same sumv value, and both
    // paths should agree on what that value is.
    val expectedSumByKey: Map[Int, Long] = expected.groupBy(_(0).asInstanceOf[Integer].intValue)
      .map { case (k, rs) => k -> rs.head(2).asInstanceOf[java.lang.Long].longValue }
    val actualSumByKey: Map[Int, Long] = actual.groupBy(_(0).asInstanceOf[Integer].intValue)
      .map { case (k, rs) => k -> rs.head(2).asInstanceOf[java.lang.Long].longValue }
    assertEquals(expectedSumByKey, actualSumByKey)
    // Confirm broadcast: every row in actual for a given key has the same sumv.
    actual.groupBy(_(0).asInstanceOf[Integer].intValue).foreach { case (k, rs) =>
      val sums = rs.map(_(2)).distinct
      assertEquals(s"all rows of key=$k must share one sumv", 1, sums.size)
    }
  }

  // ---- LAG / LEAD ---------------------------------------------------------

  @Test def lagWithDefaultParity(): Unit = {
    val plan = kvPlan(N = 1000, K = 5, seed = 7L)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq(SortKey(v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec,
      WindowFnLag(v, offset = 1, default = Some(LitExpr(-1L, DataType.LongType))),
      "lagv")
    val outSchema = Schema(plan.outputSchema.fields :+ Field("lagv", DataType.LongType))
    val expected = drainRows(WindowExec(plan, Seq(win)).execute(0), outSchema)
    val actual = drainRows(WindowExec(plan, Seq(win), spillyOpts()).execute(0), outSchema)

    // Within each partition, the multiset of (v, lagv) pairs must match.
    // Since ORDER BY v is the sort key and v's are random Longs (effectively
    // unique within a small partition), positional equality holds within
    // a key.
    expected.groupBy(_(0)).foreach { case (key, expRows) =>
      val actRows = actual.filter(_(0) == key)
      val expSorted = expRows.sortBy(_(1).asInstanceOf[java.lang.Long].longValue)
      val actSorted = actRows.sortBy(_(1).asInstanceOf[java.lang.Long].longValue)
      assertEquals(s"key=$key: lag sequence after sort by v", expSorted, actSorted)
    }
  }

  // ---- NULL partition keys ------------------------------------------------

  @Test def nullPartitionKeysGroupTogetherUnderSpill(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    val rows = Vector(
      Array[Any](java.lang.Integer.valueOf(1), java.lang.Long.valueOf(100L)),
      Array[Any](null,                          java.lang.Long.valueOf(50L)),
      Array[Any](java.lang.Integer.valueOf(1), java.lang.Long.valueOf(200L)),
      Array[Any](null,                          java.lang.Long.valueOf(75L)),
      Array[Any](java.lang.Integer.valueOf(2), java.lang.Long.valueOf(10L)),
      Array[Any](null,                          java.lang.Long.valueOf(25L)))
    val plan = new InMemoryPlan(schema, Vector(rows))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq.empty, WindowFrame.defaultFor(hasOrderBy = false))
    val win = WindowDef(spec, WindowFnAgg(AggExprSum(v)), "sumv")
    val outSchema = Schema(schema.fields :+ Field("sumv", DataType.LongType))
    val expected = drainRows(WindowExec(plan, Seq(win)).execute(0), outSchema).toSet
    val actual = drainRows(WindowExec(plan, Seq(win), spillyOpts()).execute(0), outSchema).toSet
    assertEquals(expected, actual)
    // NULL-key partition must have one collective sum of 50+75+25 = 150.
    val nullSum = actual.find(_(0) == null).map(_(2).asInstanceOf[java.lang.Long].longValue)
    assertEquals(Some(150L), nullSum)
  }

  // ---- Empty input + opted-in spill ---------------------------------------

  @Test def emptyInputProducesEmptyOutputUnderSpill(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("v", DataType.LongType)))
    val plan = new InMemoryPlan(schema, Vector(Vector.empty))
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq(SortKey(v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val outSchema = Schema(schema.fields :+ Field("rn", DataType.LongType))
    val rows = drainRows(WindowExec(plan, Seq(win), spillyOpts()).execute(0), outSchema)
    assertEquals(0, rows.length)
  }

  /** An empty PARTITION BY makes `canSpill` false — opting into spill
    * must NOT change behavior (silent fallback to in-memory). */
  @Test def emptyPartitionByDisablesSpillSilently(): Unit = {
    val plan = kvPlan(N = 100, K = 5, seed = 13L)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq.empty, Seq(SortKey(v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val outSchema = Schema(plan.outputSchema.fields :+ Field("rn", DataType.LongType))
    val expected = drainRows(WindowExec(plan, Seq(win)).execute(0), outSchema)
    val actual = drainRows(WindowExec(plan, Seq(win), spillyOpts()).execute(0), outSchema)
    assertEquals(expected, actual)
  }

  // ---- Sub-plan 2: spill + metrics composition ------------------------------

  @Test def spillAndMetricsComposeReportingNonZeroSpillCounters(): Unit = {
    val plan = kvPlan(N = 5000, K = 7, seed = 99L)
    val k = ColRefExpr(0, "k", DataType.IntType)
    val v = ColRefExpr(1, "v", DataType.LongType)
    val spec = WindowSpec(Seq(k), Seq(SortKey(v, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val node = new MetricsNode(0, "WindowExec", 0, WindowExec.IdxCounterNames)
    val w = WindowExec(plan, Seq(win), spillyOpts(), node)
    val it = w.execute(0)
    while (it.hasNext) it.next()
    val snap = node.snapshot()
    assertTrue(s"partitionSpillEvents must be > 0, got ${snap.counter("partitionSpillEvents")}",
      snap.counter("partitionSpillEvents") > 0L)
    assertTrue(s"bytesSpilled must be > 0, got ${snap.counter("bytesSpilled")}",
      snap.counter("bytesSpilled") > 0L)
    // Each bucket produces its own partitionCount / peakPartitionRows
    // accumulation, so the counter reflects sum across buckets.
    assertTrue(s"partitionCount must be > 0, got ${snap.counter("partitionCount")}",
      snap.counter("partitionCount") > 0L)
  }

  // ---- Regression: duplicate-named child schema under spill -----------------
  //
  // A child whose output schema has two columns sharing a name (`[k, k]`, as a
  // join output `[k, v, k, v]` would) used to write disk buckets with
  // duplicate parquet column paths, which read back null and NPE'd in
  // processBatches. Buckets now use positional names. See plans/bugfixes/01.
  @Test def duplicateNamedChildSchemaUnderSpill(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("k", DataType.IntType)))
    val rng = new Random(2026L)
    val rows = (0 until 5000).map { _ =>
      Array[Any](
        java.lang.Integer.valueOf(rng.nextInt(7)),
        java.lang.Integer.valueOf(rng.nextInt(1000)))
    }.toVector
    val plan = new InMemoryPlan(schema, Vector(rows))
    // PARTITION BY the first column, ORDER BY the second; ROW_NUMBER.
    val partKey = ColRefExpr(0, "k", DataType.IntType)
    val orderKey = ColRefExpr(1, "k", DataType.IntType)
    val spec = WindowSpec(Seq(partKey), Seq(SortKey(orderKey, true)), WindowFrame.defaultFor(hasOrderBy = true))
    val win = WindowDef(spec, WindowFnRowNumber(), "rn")
    val outSchema = Schema(schema.fields :+ Field("rn", DataType.LongType))

    val expected = drainRows(WindowExec(plan, Seq(win)).execute(0), outSchema)

    // Spill run, drained manually so we can assert no positional-name leak.
    val node = new MetricsNode(0, "WindowExec", 0, WindowExec.IdxCounterNames)
    val it = WindowExec(plan, Seq(win), spillyOpts(), node).execute(0)
    val buf = mutable.ArrayBuffer.empty[Vector[Any]]
    while (it.hasNext) {
      val b = it.next()
      assertEquals("emitted batch must carry the logical output schema, not spilled _cN names",
        outSchema.fields.map(_.name), b.schema.fields.map(_.name))
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](outSchema.length)
        var c = 0
        while (c < outSchema.length) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr.toVector
        r += 1
      }
    }
    val actual = buf.toVector

    // ROW_NUMBER within each partition must be 1..|partition|, order aside.
    expected.groupBy(_(0)).foreach { case (key, erows) =>
      val rns = erows.map(_(2).asInstanceOf[java.lang.Long].longValue).sorted
      val actRns = actual.filter(_(0) == key)
        .map(_(2).asInstanceOf[java.lang.Long].longValue).sorted
      assertEquals(s"row_number multiset for key=$key", rns, actRns)
      assertEquals(s"row_number must be 1..${erows.size} for key=$key",
        (1L to erows.size).toVector, rns.toVector)
    }
    assertTrue(s"partitionSpillEvents must be > 0, got ${node.snapshot().counter("partitionSpillEvents")}",
      node.snapshot().counter("partitionSpillEvents") > 0L)
  }

  // ---- RANK / DENSE_RANK peers follow the sort's total order ----------------
  //
  // A rank's peer groups are exactly the ties of the sort that produced the
  // order, so `sameKey` compares order keys with `Ops.cmp` — the comparison
  // `sortPartition` used. It once compared with `Ops.eq`, raw IEEE equality,
  // and the two disagree on the values the rest of the engine reconciles:
  // `NaN` ties with `NaN` under the total order but is not IEEE-equal to it,
  // so tied rows took different ranks (a wrong answer — every row of an
  // all-tied partition must rank 1); `-0.0` sorts strictly below `0.0` but is
  // IEEE-equal to it, so strictly-ordered rows shared a rank. See
  // plans/followups/06-window-rank-peer-total-order.md.

  private val kdSchema = Schema(Vector(
    Field("k", DataType.IntType),
    Field("d", DataType.DoubleType),
    Field("id", DataType.IntType)))

  /** RANK and DENSE_RANK over `PARTITION BY k ORDER BY d`, as `id -> (rank,
    * denseRank)`. Every row carries `k = 1` (one partition, and a non-empty
    * PARTITION BY so the spill path is eligible) and `id` = its index in
    * `ds`, which is how each row's rank is identified without depending on
    * emit order. A `null` in `ds` is a NULL key; `nullsFirst` is an explicit
    * NULLS FIRST / NULLS LAST on the ORDER BY. */
  private def ranksById(
      ds: Seq[Any],
      asc: Boolean,
      opts: ExecutionOptions,
      nullsFirst: Option[Boolean] = None): Map[Int, (Long, Long)] = {
    val rows = ds.iterator.zipWithIndex.map { case (d, i) =>
      Array[Any](java.lang.Integer.valueOf(1), d, java.lang.Integer.valueOf(i))
    }.toVector
    val k = ColRefExpr(0, "k", DataType.IntType)
    val d = ColRefExpr(1, "d", DataType.DoubleType)
    val key = nullsFirst.fold(SortKey(d, asc))(first => SortKey(d, asc, first))
    val spec = WindowSpec(Seq(k), Seq(key), WindowFrame.defaultFor(hasOrderBy = true))
    val wins = Seq(
      WindowDef(spec, WindowFnRank(), "rk"),
      WindowDef(spec, WindowFnDenseRank(), "dr"))
    val outSchema = Schema(kdSchema.fields ++
      Vector(Field("rk", DataType.LongType), Field("dr", DataType.LongType)))
    val out = drainRows(
      WindowExec(new InMemoryPlan(kdSchema, Vector(rows)), wins, opts).execute(0), outSchema)
    assertEquals("every input row must come back", ds.length, out.length)
    out.iterator.map { row =>
      row(2).asInstanceOf[Integer].intValue ->
        ((row(3).asInstanceOf[java.lang.Long].longValue, row(4).asInstanceOf[java.lang.Long].longValue))
    }.toMap
  }

  /** The same ranks on the heap path and the spill path. */
  private def assertRanks(
      label: String,
      expected: Map[Int, (Long, Long)],
      ds: Seq[Any],
      asc: Boolean,
      nullsFirst: Option[Boolean] = None): Unit = {
    assertEquals(s"$label (heap)", expected, ranksById(ds, asc, ExecutionOptions.Default, nullsFirst))
    assertEquals(s"$label (spill)", expected, ranksById(ds, asc, spillyOpts(), nullsFirst))
  }

  /** A partition whose ORDER BY key is NaN on every row is one peer group, so
    * every row ranks 1 under both RANK and DENSE_RANK. */
  @Test def allNaNOrderKeysAreOnePeerGroup(): Unit =
    assertRanks("every row ties on NaN", Map(0 -> (1L, 1L), 1 -> (1L, 1L), 2 -> (1L, 1L)),
      Seq(Double.NaN, Double.NaN, Double.NaN), asc = true)

  /** NaN ties with NaN and sorts above every finite value, so a mixed partition
    * ranks the NaN rows last and ranks them equally. DESC reverses the order
    * without splitting the peer group. */
  @Test def nanOrderKeysTieAboveEveryFiniteValue(): Unit = {
    val ds = Seq(1.0, Double.NaN, Double.NaN, 2.0)
    // ASC:  1.0, 2.0, NaN, NaN -> rank 1, 2, 3, 3 / dense 1, 2, 3, 3
    assertRanks("NaN last, tied", Map(0 -> (1L, 1L), 3 -> (2L, 2L), 1 -> (3L, 3L), 2 -> (3L, 3L)),
      ds, asc = true)
    // DESC: NaN, NaN, 2.0, 1.0 -> rank 1, 1, 3, 4 / dense 1, 1, 2, 3
    assertRanks("NaN first, tied", Map(1 -> (1L, 1L), 2 -> (1L, 1L), 3 -> (3L, 2L), 0 -> (4L, 3L)),
      ds, asc = false)
  }

  /** NULLs are one peer group placed where the key says, on both paths. The
    * default puts them first under ASC; NULLS LAST moves them below every
    * value, and DESC NULLS FIRST keeps them on top while reversing the rest. */
  @Test def nullOrderKeysRankWhereThePlacementPutsThem(): Unit = {
    val ds = Seq[Any](2.0, null, 1.0, null)
    // ASC (default, NULLs first): NULL, NULL, 1.0, 2.0
    assertRanks("ASC, NULLs first by default",
      Map(1 -> (1L, 1L), 3 -> (1L, 1L), 2 -> (3L, 2L), 0 -> (4L, 3L)), ds, asc = true)
    // ASC NULLS LAST: 1.0, 2.0, NULL, NULL
    assertRanks("ASC NULLS LAST",
      Map(2 -> (1L, 1L), 0 -> (2L, 2L), 1 -> (3L, 3L), 3 -> (3L, 3L)), ds, asc = true, nullsFirst = Some(false))
    // DESC NULLS FIRST: NULL, NULL, 2.0, 1.0
    assertRanks("DESC NULLS FIRST",
      Map(1 -> (1L, 1L), 3 -> (1L, 1L), 0 -> (3L, 2L), 2 -> (4L, 3L)), ds, asc = false, nullsFirst = Some(true))
  }

  /** `-0.0` sorts strictly below `0.0`, so the two rows are not peers and rank
    * 1 and 2 — the rank agrees with the row order ORDER BY produces, where
    * IEEE equality would have called them one peer group. */
  @Test def signedZeroOrderKeysAreNotPeers(): Unit =
    assertRanks("-0.0 ranks below 0.0", Map(1 -> (1L, 1L), 0 -> (2L, 2L)),
      Seq(0.0, -0.0), asc = true)
}
