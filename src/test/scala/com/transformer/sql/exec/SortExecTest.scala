package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable
import scala.util.Random

/** Direct [[SortExec]] coverage. Builds a hand-rolled multi-partition
  * [[PhysicalPlan]] so each scenario can exercise the merge across specific
  * partition shapes (overlapping ranges, empties, one run, many). Every path
  * returns the stable sort of its input read partition by partition, so the
  * output is asserted row for row against that, ties included, as well as
  * sorted under a local oracle comparator that mirrors `SortKey.compare`.
  */
class SortExecTest {

  private val schema2 = Schema(Vector(Field("k", DataType.IntType), Field("p", DataType.IntType)))
  private val k: Expr = ColRefExpr(0, "k", DataType.IntType)
  private val p: Expr = ColRefExpr(1, "p", DataType.IntType)

  // ---- Helpers -------------------------------------------------------------

  private def runSort(
      partitions: Vector[Vector[Array[Any]]],
      schema: Schema,
      keys: Seq[SortKey]): Vector[Array[Any]] = {
    val plan = new InMemoryPartitionedPlan(schema, partitions)
    val sort = SortExec(plan, keys)
    drainRows(sort.execute(0), schema)
  }

  private def drainRows(it: Iterator[ColumnarBatch], schema: Schema): Vector[Array[Any]] = {
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

  private def assertSortedByKeys(
      label: String,
      rows: Vector[Array[Any]],
      schema: Schema,
      keys: Seq[SortKey]): Unit = {
    if (rows.length < 2) return
    val cmp = mirrorOfSortExecComparator(schema, keys)
    var i = 1
    while (i < rows.length) {
      val c = cmp.compare(rows(i - 1), rows(i))
      if (c > 0)
        fail(s"$label: not sorted at row $i: ${rows(i - 1).mkString("(", ",", ")")} > ${rows(i).mkString("(", ",", ")")}")
      i += 1
    }
  }

  private def assertPermutationOf(
      label: String,
      expected: Vector[Vector[Array[Any]]],
      actual: Vector[Array[Any]]): Unit = {
    val expectedMs = expected.flatten.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    val actualMs = actual.map(_.toVector).groupBy(identity).view.mapValues(_.size).toMap
    assertEquals(s"$label: row multiset", expectedMs, actualMs)
  }

  /** Same null placement + Ops.cmp semantics as `SortKey.compare` (a NULL
    * goes where the key's `nullsFirst` says, whatever the direction) —
    * written out here rather than calling `SortKey.compare`, so we're asserting
    * against a known oracle, not the code under test. */
  private def mirrorOfSortExecComparator(
      schema: Schema, keys: Seq[SortKey]): java.util.Comparator[Array[Any]] = {
    val sortKeys = keys.toArray
    new java.util.Comparator[Array[Any]] {
      def compare(a: Array[Any], b: Array[Any]): Int = {
        val ba = rowBatch(schema, a)
        val bb = rowBatch(schema, b)
        var i = 0
        while (i < sortKeys.length) {
          val key = sortKeys(i)
          val va = key.expr.eval(ba, 0)
          val vb = key.expr.eval(bb, 0)
          val c = (va, vb) match {
            case (null, null) => 0
            case (null, _) => if (key.nullsFirst) -1 else 1
            case (_, null) => if (key.nullsFirst) 1 else -1
            case _ => if (key.asc) Ops.cmp(va, vb) else Ops.cmp(vb, va)
          }
          if (c != 0) return c
          i += 1
        }
        0
      }
    }
  }

  /** The rows of `partitions`, read in order, sorted stably by the oracle
    * comparator: what the sort returns, ties included. */
  private def stableSort(
      partitions: Vector[Vector[Array[Any]]], schema: Schema, keys: Seq[SortKey]): Vector[Array[Any]] = {
    val rows = partitions.flatten.toArray
    java.util.Arrays.sort(rows, mirrorOfSortExecComparator(schema, keys))
    rows.toVector
  }

  private def assertStable(
      label: String,
      partitions: Vector[Vector[Array[Any]]],
      schema: Schema,
      keys: Seq[SortKey],
      actual: Vector[Array[Any]]): Unit =
    assertEquals(s"$label: the stable sort, row for row", stableSort(partitions, schema, keys).map(_.toVector),
      actual.map(_.toVector))

  private def rowBatch(schema: Schema, row: Array[Any]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, 1)
    var c = 0
    while (c < schema.length) {
      if (row(c) == null) b.column(c).setNull(0) else b.column(c).setBoxed(0, row(c))
      c += 1
    }
    b.setNumRows(1)
    b
  }

  // ---- Tests ---------------------------------------------------------------

  @Test def mergesAcrossPartitionsWithOverlappingRanges(): Unit = {
    // Three partitions whose sorted ranges overlap. A simple concat would
    // interleave wrongly; the merge must produce a globally sorted output.
    val parts = Vector(
      Vector(row(1, 0), row(4, 0), row(7, 0)),
      Vector(row(2, 1), row(5, 1), row(8, 1)),
      Vector(row(3, 2), row(6, 2), row(9, 2)))
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(9, out.length)
    assertEquals((1 to 9).toVector, out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("merge overlapping", out, schema2, keys)
  }

  @Test def multiKeySortMixedAscDesc(): Unit = {
    // Primary key ASC, secondary key DESC. Multiple partitions force the
    // merge to honor both keys in lockstep across partition boundaries.
    val parts = Vector(
      Vector(row(1, 10), row(2, 30), row(3, 50)),
      Vector(row(1, 20), row(2, 40), row(3, 10)),
      Vector(row(2, 20), row(2, 10), row(3, 20)))
    val keys = Seq(SortKey(k, true), SortKey(p, false))
    val out = runSort(parts, schema2, keys)
    assertSortedByKeys("multi-key asc/desc", out, schema2, keys)
    assertPermutationOf("multi-key asc/desc", parts, out)
    assertStable("multi-key asc/desc", parts, schema2, keys, out)
    val ks = out.map(_(0).asInstanceOf[Int])
    assertEquals(Vector(1, 1, 2, 2, 2, 2, 3, 3, 3), ks)
  }

  @Test def nullsFirstWhenAscending(): Unit = {
    val parts = Vector(
      Vector(row(null, 0), row(1, 0), row(3, 0)),
      Vector(row(2, 1), row(null, 1), row(4, 1)))
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(6, out.length)
    assertNull(out(0)(0))
    assertNull(out(1)(0))
    assertEquals(Vector(1, 2, 3, 4), out.drop(2).map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("nulls first asc", out, schema2, keys)
  }

  @Test def nullsLastWhenDescending(): Unit = {
    val parts = Vector(
      Vector(row(null, 0), row(3, 0), row(1, 0)),
      Vector(row(4, 1), row(2, 1), row(null, 1)))
    val keys = Seq(SortKey(k, false))
    val out = runSort(parts, schema2, keys)
    assertEquals(6, out.length)
    assertEquals(Vector(4, 3, 2, 1), out.take(4).map(_(0).asInstanceOf[Int]))
    assertNull(out(4)(0))
    assertNull(out(5)(0))
    assertSortedByKeys("nulls last desc", out, schema2, keys)
  }

  @Test def explicitNullPlacementOverridesDirection(): Unit = {
    // ASC NULLS LAST and DESC NULLS FIRST: the two placements the default
    // (NULL as the smallest value) never produces.
    val parts = Vector(
      Vector(row(null, 0), row(1, 0), row(3, 0)),
      Vector(row(2, 1), row(null, 1), row(4, 1)))
    val ascLast = Seq(SortKey(k, asc = true, nullsFirst = false))
    val out1 = runSort(parts, schema2, ascLast)
    assertEquals(Vector(1, 2, 3, 4), out1.take(4).map(_(0).asInstanceOf[Int]))
    assertNull(out1(4)(0))
    assertNull(out1(5)(0))
    assertSortedByKeys("asc nulls last", out1, schema2, ascLast)
    val descFirst = Seq(SortKey(k, asc = false, nullsFirst = true))
    val out2 = runSort(parts, schema2, descFirst)
    assertNull(out2(0)(0))
    assertNull(out2(1)(0))
    assertEquals(Vector(4, 3, 2, 1), out2.drop(2).map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("desc nulls first", out2, schema2, descFirst)
  }

  @Test def nullPlacementSurvivesTheHeapMerge(): Unit = {
    // Many partitions of many rows merge through the tree; placement must
    // hold on both keys, including within ties of the first key.
    val rnd = new Random(0xBADC0DE)
    def maybeNull(v: Int): Any = if (rnd.nextInt(8) == 0) null else v
    val parts = Vector.tabulate(6) { _ =>
      Vector.fill(1000)(row(maybeNull(rnd.nextInt(50)), maybeNull(rnd.nextInt(5))))
    }
    val keys = Seq(SortKey(k, asc = true, nullsFirst = false), SortKey(p, asc = false, nullsFirst = true))
    val out = runSort(parts, schema2, keys)
    assertEquals(6000, out.length)
    assertSortedByKeys("merge, explicit placement", out, schema2, keys)
    assertPermutationOf("merge, explicit placement", parts, out)
    assertStable("merge, explicit placement", parts, schema2, keys, out)
    val firstNull = out.indexWhere(_(0) == null)
    assertTrue("expected NULL keys in the data", firstNull > 0)
    assertTrue("NULLS LAST: every NULL k after every non-NULL k", out.drop(firstNull).forall(_(0) == null))
  }

  @Test def emptyPartitionsMixedWithFullPartitions(): Unit = {
    val parts = Vector(
      Vector.empty[Array[Any]],
      Vector(row(2, 1), row(4, 1)),
      Vector.empty[Array[Any]],
      Vector(row(1, 3), row(3, 3), row(5, 3)))
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(Vector(1, 2, 3, 4, 5), out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("empty + full", out, schema2, keys)
  }

  @Test def allPartitionsEmpty(): Unit = {
    val parts = Vector.fill(4)(Vector.empty[Array[Any]])
    val out = runSort(parts, schema2, Seq(SortKey(k, true)))
    assertTrue(out.isEmpty)
  }

  @Test def singlePartitionInputStillSorted(): Unit = {
    // Single non-empty partial — exercises the `nonEmpty == 1` shortcut.
    val parts = Vector(Vector(row(5, 0), row(2, 0), row(9, 0), row(1, 0)))
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(Vector(1, 2, 5, 9), out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("single partition", out, schema2, keys)
  }

  @Test def aFewRowsOverSeveralPartitionsSortCorrectly(): Unit = {
    // A few rows over three partitions: the merge of three short runs.
    val parts = Vector(
      Vector.tabulate(40)(i => row(99 - i, 0)),
      Vector.tabulate(40)(i => row(150 - i * 2, 1)),
      Vector.tabulate(40)(i => row(i, 2)))
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(120, out.length)
    assertSortedByKeys("small-N fast path", out, schema2, keys)
    assertPermutationOf("small-N fast path", parts, out)
    assertStable("small-N fast path", parts, schema2, keys, out)
  }

  @Test def largeMultiPartitionMonotone(): Unit = {
    // 12 partitions × 600 rows = 7200 total rows over a random key.
    val rnd = new Random(0xC0FFEE)
    val parts = Vector.tabulate(12) { _ =>
      Vector.fill(600)(row(rnd.nextInt(1_000_000), rnd.nextInt(10)))
    }
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(7200, out.length)
    assertSortedByKeys("large random", out, schema2, keys)
    assertPermutationOf("large random", parts, out)
    assertStable("large random", parts, schema2, keys, out)
  }

  @Test def emitsBatchesAtDefaultCapacity(): Unit = {
    // The heap-merge path emits ColumnarBatches of DefaultCapacity. Generate
    // just over capacity rows across two partitions and confirm we see full
    // batches + one short tail batch — the lazy emit honors DefaultCapacity
    // instead of dumping everything into one batch. The suite also runs at a
    // capacity of 1 and 2 rows (docs/testing.md), where 17 rows past it are
    // several more full batches.
    val cap = ColumnarBatch.DefaultCapacity
    val total = cap + 17
    val half = total / 2
    val parts = Vector(
      Vector.tabulate(half)(i => row(i * 2, 0)),
      Vector.tabulate(total - half)(i => row(i * 2 + 1, 1)))
    val plan = new InMemoryPartitionedPlan(schema2, parts)
    val it = SortExec(plan, Seq(SortKey(k, true))).execute(0)
    val sizes = mutable.ArrayBuffer.empty[Int]
    while (it.hasNext) sizes += it.next().numRows
    assertEquals(Vector.fill(total / cap)(cap) ++ Option(total % cap).filter(_ > 0), sizes.toVector)
  }

  /** Smoke for a monotone-key sort that crosses partition boundaries — the
    * regression check the plan calls out (jaffle-shop `customer_id`-style
    * sort) at unit-test scale. */
  @Test def monotonicitySmoke(): Unit = {
    val parts = Vector.tabulate(4)(p =>
      Vector.tabulate(1024)(i => row(i * 4 + p, p)))
    val keys = Seq(SortKey(k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(4096, out.length)
    assertEquals((0 until 4096).toVector, out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("monotonicity", out, schema2, keys)
  }

  /** Rows that tie on every key come in the order they were read, partition
    * by partition, on every path: one run, a few rows over several runs, more
    * rows than a batch over many, and spilled at a one-byte threshold, where
    * runs end at other rows. Spill on and off return the same rows in the
    * same order. */
  @Test def tiedRowsComeInTheOrderTheyWereRead(): Unit = {
    val rnd = new Random(1103)
    var id = 0
    def part(n: Int): Vector[Array[Any]] = Vector.fill(n) { id += 1; row(rnd.nextInt(4), id) }
    val shapes = Seq(
      Vector(part(50)),
      Vector(part(30), part(0), part(40), part(5)),
      Vector.fill(8)(part(700)),
      Vector(part(5000), part(3)))
    val spill = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = 100000)
    for (parts <- shapes; keys <- Seq(Seq(SortKey(k, true)), Seq(SortKey(k, false, nullsFirst = true)))) {
      // Batches of 100 rows: a run a batch when spilling, so a partition's
      // rows that tie meet from several runs.
      val plain = drainRows(SortExec(new InMemoryPartitionedPlan(schema2, parts, 100), keys).execute(0), schema2)
      assertStable(s"${parts.map(_.size)} $keys", parts, schema2, keys, plain)
      val spilled = drainRows(SortExec(new InMemoryPartitionedPlan(schema2, parts, 100), keys, spill).execute(0), schema2)
      assertEquals(s"${parts.map(_.size)} $keys: spill on and off", plain.map(_.toVector), spilled.map(_.toVector))
    }
    assertEquals("spill directories left", Nil, Spill.operatorDirsLeft)
  }

  /** A key that reads `RAND()` over a DISTINCT or a set operation is the
    * sort's own, and the sort reads it once a row: each row draws one value,
    * so the rows that draw `false` come first, in order of `k`, then those
    * that draw `true`, in order of `k`: one descent at most. The sort read
    * such a key at each comparison, so it compared each row with a value of
    * its own each time, and over 50,000 distinct rows TimSort refused it
    * ("Comparison method violates its general contract!") or returned rows
    * in no order (plans/perf/15). */
  @Test def aKeyThatReadsRandIsReadOnceARow(): Unit = {
    val n = 50000
    val t = Schema(Vector(Field("k", DataType.IntType)))
    val rows = new Random(15).shuffle((0 until n).toVector).map(i => Array[Any](i))
    val catalog = new Catalog
    catalog.register("t", new MaterializedView(t, rows.grouped(n / 4).toIndexedSeq.map(part =>
      part.grouped(1000).map(g => batchOf(t, g)).toIndexedSeq)))
    for (sql <- Seq(
        "SELECT DISTINCT k FROM t ORDER BY RAND() < 0.5, k",
        "SELECT k FROM t UNION SELECT k FROM t ORDER BY RAND() < 0.5, k",
        "SELECT DISTINCT k FROM t ORDER BY RAND() < 0.5, k LIMIT 9000")) {
      val ks = drainRows(SqlEngine.execute(sql, catalog).batches, Schema(Vector(Field("k", DataType.IntType))))
        .map(_(0).asInstanceOf[Int])
      val descents = ks.indices.count(i => i > 0 && ks(i) < ks(i - 1))
      assertTrue(s"$sql: $descents descents", descents <= 1)
      if (!sql.contains("LIMIT")) assertEquals(sql, (0 until n).toVector, ks.sorted)
      else assertEquals(sql, 9000, ks.distinct.length)
    }
  }

  private def batchOf(schema: Schema, rows: Vector[Array[Any]]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, rows.length)
    rows.indices.foreach(r => b.column(0).setBoxed(r, rows(r)(0)))
    b.setNumRows(rows.length)
    b
  }

  private def row(a: Any, b: Any): Array[Any] = Array(a, b)
}

/** Minimal [[PhysicalPlan]] that hands back pre-supplied rows partitioned as
  * given. Each "partition" is split into batches of `batchRows` rows, by
  * default [[ColumnarBatch.DefaultCapacity]], so [[SortExec]] sees realistic
  * batch boundaries.
  */
private final class InMemoryPartitionedPlan(
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
