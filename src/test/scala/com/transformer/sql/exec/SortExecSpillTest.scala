package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Test}
import org.junit.Assume.assumeTrue

import scala.collection.mutable
import scala.util.Random

/** External-sort parity guarantee: with spill enabled at a tiny threshold
  * (forcing many flushes), [[SortExec]] returns the same rows in the same
  * order as the non-spill path, ties included. Every path returns the stable
  * sort of its input read partition by partition, and the merge breaks a tie
  * between two runs by run, each partition's runs in the order they were
  * written, so where the runs end does not show in the output. */
class SortExecSpillTest {

  private val schema = Schema(Vector(Field("k", DataType.IntType), Field("p", DataType.IntType)))
  private val k: Expr = ColRefExpr(0, "k", DataType.IntType)
  private val keysAsc: Seq[SortKey] = Seq(SortKey(k, true))

  // This JVM's spill directory is read once (`Spill.baseDir`, under
  // java.io.tmpdir, which Bazel sets to the target's own TEST_TMPDIR), so a
  // test cannot move it. Every operator that spills closes its directory once
  // nothing reads it, so each test leaves none (plans/testing/17).
  @After def leavesNoSpillDirectory(): Unit =
    assertEquals("spill directories left", Nil, Spill.operatorDirsLeft)

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

  /** A one-byte threshold spills once per input batch, so a test that spills
    * thousands of rows that way writes a run per batch: past
    * `spill_max_runs` and into thousands of files under the 1- and 2-row
    * batch capacities the whole suite also runs at (docs/testing.md), where
    * the spill fuzzers and `slt_test`'s spill mode cover spilling instead. */
  private def assumeFullBatches(): Unit =
    assumeTrue("needs batches of 1024 rows or more", ColumnarBatch.DefaultCapacity >= 1024)

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
    assumeFullBatches()
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

  /** Keys of ten values over 5,000 rows: every row ties with hundreds of
    * others, across runs and partitions. Spilled at a one-byte threshold, a
    * run a batch, the sort returns what it returns unspilled, row for row:
    * each key's rows in the order they were read, partition by partition. */
  @Test def tiedKeysComeBackRowForRowWithAndWithoutSpill(): Unit = {
    assumeFullBatches()
    val rng = new Random(15L)
    val rows = (0 until 5000).map(i => Array[Any](java.lang.Integer.valueOf(rng.nextInt(10)), java.lang.Integer.valueOf(i))).toVector
    val parts = rows.grouped(1250).toVector
    for (key <- Seq(SortKey(k, asc = true), SortKey(k, asc = false))) {
      def run(opts: ExecutionOptions): Vector[Vector[Any]] =
        drainRows(SortExec(new InMemoryPartitionedPlanForSpillTest(schema, parts, 100), Seq(key), opts).execute(0))
          .map(_.toVector)
      val plain = run(ExecutionOptions.Default)
      val spilled = run(spillyOpts())
      assertEquals(s"$key: spill on and off, row for row", plain, spilled)
      val stable = rows.sortBy(r => if (key.asc) r(0).asInstanceOf[Integer].intValue else -r(0).asInstanceOf[Integer].intValue)
      assertEquals(s"$key: each key's rows in the order read", stable.map(_.toVector), spilled)
    }
  }

  @Test def manySpillRoundsStillSorted(): Unit = {
    assumeFullBatches()
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

  /** A run longer than a batch is written a batch at a time, the last one
    * short: a threshold the whole input reaches only at its end (what a run
    * holding every row counts, [[SortRun.heldBytes]]) makes one run of a batch
    * and 100 rows more, read back whole and in order. */
  @Test def aRunLongerThanABatchReadsBackWhole(): Unit = {
    assumeFullBatches()
    val n = ColumnarBatch.DefaultCapacity + 100
    val parts = uniqueKeyedRows(n, parts = 1, seed = 13L)
    val whole = new SortRun(new SortLayout(schema, keysAsc), countsBytes = true)
    new InMemoryPartitionedPlanForSpillTest(schema, parts).execute(0).foreach(whole.append)
    val opts = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(whole.heldBytes))
    val node = new MetricsNode(0, "SortExec", 0, SortExec.IdxCounterNames)
    val rows = drainRows(SortExec(new InMemoryPartitionedPlanForSpillTest(schema, parts), keysAsc, opts, node).execute(0))
    assertEquals("one run, spilled", 1L, node.snapshot().counter("runsWritten"))
    assertEquals((0 until n).toVector, rows.map(_(0).asInstanceOf[Integer].intValue))
  }

  /** A run counts what it holds: its chunks' arrays by capacity, a string's
    * payload once for each run of rows holding the same object, and eight
    * bytes a row for the `Int`s that sort it; nothing when it cannot spill.
    * So rows repeating one string object flush far later than rows of
    * strings of their own, which the batch estimate (`Spill.estimateBytes`)
    * counted alike. */
  @Test def aRunCountsWhatItHolds(): Unit = {
    val s = Schema(Vector(Field("k", DataType.IntType), Field("s", DataType.StringType)))
    val layout = new SortLayout(s, keysAsc)
    def batch(strings: Int => String): ColumnarBatch = {
      val b = new ColumnarBatch(s, 1000)
      (0 until 1000).foreach { r =>
        b.column(0).asInstanceOf[IntVector].set(r, r)
        b.column(1).asInstanceOf[StringVector].set(r, strings(r))
      }
      b.setNumRows(1000)
      b
    }
    val one = "x" * 66
    val shared = new SortRun(layout, countsBytes = true)
    shared.append(batch(_ => one))
    val own = new SortRun(layout, countsBytes = true)
    own.append(batch(r => ("x" * 65) + (r % 10)))
    val chunks = layout.chunkBytes * ((1000 + SortRun.Cap - 1) / SortRun.Cap)
    assertEquals("one string object, counted once", chunks + (24 + 2 * 66) + 8 * 1000, shared.heldBytes)
    assertEquals("a string object a row, each counted", chunks + 1000L * (24 + 2 * 66) + 8 * 1000, own.heldBytes)
    val uncounted = new SortRun(layout, countsBytes = false)
    uncounted.append(batch(_ => one))
    assertEquals("nothing counted when the sort cannot spill", 8 * 1000L, uncounted.heldBytes)
  }

  /** The merge of spilled runs has nothing after its end: `next` past it, or
    * after the reader closed it with rows left, is a NoSuchElementException,
    * never an empty batch or a read of a closed run. */
  @Test def theMergeHasNothingAfterItsEndOrItsClose(): Unit = {
    assumeFullBatches()
    val parts = uniqueKeyedRows(n = 3 * ColumnarBatch.DefaultCapacity, parts = 2, seed = 17L)
    val drained = SortExec(new InMemoryPartitionedPlanForSpillTest(schema, parts), keysAsc, spillyOpts()).execute(0)
    assertEquals(3 * ColumnarBatch.DefaultCapacity, drainRows(drained).length)
    assertThrows(classOf[NoSuchElementException], () => { val _ = drained.next() })

    val stopped = SortExec(new InMemoryPartitionedPlanForSpillTest(schema, parts), keysAsc, spillyOpts()).execute(0)
    assertEquals(ColumnarBatch.DefaultCapacity, stopped.next().numRows)
    stopped.asInstanceOf[AutoCloseable].close()
    assertThrows(classOf[NoSuchElementException], () => { val _ = stopped.next() })
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
      case e: RuntimeException =>
        assertTrue(s"expected mention of spill_max_runs in: ${e.getMessage}",
          e.getMessage.contains("spill_max_runs"))
    }
  }

  // ---- Sub-plan 2: spill + metrics composition ------------------------------

  /** External-merge sort + metrics: the operator's MetricsNode must report
    * non-zero `runsWritten` / `bytesSpilled`. */
  @Test def externalSortAndMetricsComposeReportingNonZeroSpillCounters(): Unit = {
    assumeFullBatches()
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
    assertEquals(SortExec.PathMerge, snap.counter("path"))
    assertEquals(5000L, snap.counter("inputRows"))
    assertEquals(5000L, snap.counter("outputRows"))
  }

  // ---- Regression: duplicate-named child schema under spill -----------------
  //
  // A child whose output schema has two columns sharing a name (`[k, k]`, as a
  /** An explicit NULL placement survives the spill path: flushed runs come
    * back through parquet and merge with the in-memory tails, and a NULL key
    * has to land where the key says in both: the spilled output equals the
    * unspilled one row for row. */
  @Test def explicitNullPlacementUnderSpill(): Unit = {
    assumeFullBatches()
    val p: Expr = ColRefExpr(1, "p", DataType.IntType)
    val rng = new Random(77L)
    val rows = (0 until 4000).map { i =>
      val kv: Any = if (rng.nextInt(10) == 0) null else java.lang.Integer.valueOf(rng.nextInt(500))
      Array[Any](kv, java.lang.Integer.valueOf(i))
    }.toVector
    val parts = rows.grouped(1000).toVector
    Seq(SortKey(k, asc = true, nullsFirst = false), SortKey(k, asc = false, nullsFirst = true)).foreach { key =>
      def run(opts: ExecutionOptions): Vector[Vector[Any]] = {
        val plan = new InMemoryPartitionedPlanForSpillTest(schema, parts)
        drainRows(SortExec(plan, Seq(key, SortKey(p, true)), opts).execute(0)).map(_.toVector)
      }
      val heap = run(ExecutionOptions.Default)
      val spilled = run(spillyOpts())
      assertEquals(s"$key: spill must match the unspilled sort row for row", heap, spilled)
      val nullAt = spilled.indices.filter(i => spilled(i)(0) == null)
      assertTrue(s"$key: expected NULL keys in the data", nullAt.nonEmpty)
      val expectedAt = if (key.nullsFirst) nullAt.indices else (spilled.length - nullAt.length until spilled.length)
      assertEquals(s"$key: NULLs at the wrong end", expectedAt, nullAt)
    }
  }

  // join output `[k, v, k, v]` would) used to write spill runs with duplicate
  // parquet column paths, which read back null and NPE'd in DiskRunCursor. The
  // runs now use positional names. See plans/bugfixes/01.
  @Test def duplicateNamedChildSchemaUnderSpill(): Unit = {
    assumeFullBatches()
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
      val sort = SortExec(plan, Seq(SortKey(sortKey, true)), opts)
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
    partitions: Vector[Vector[Array[Any]]],
    batchRows: Int = ColumnarBatch.DefaultCapacity) extends PhysicalPlan {
  def outputSchema: Schema = schema
  def numPartitions: Int = partitions.length
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    val rows = partitions(partition)
    if (rows.isEmpty) return Iterator.empty
    val batchSize = math.min(batchRows, rows.length)
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
