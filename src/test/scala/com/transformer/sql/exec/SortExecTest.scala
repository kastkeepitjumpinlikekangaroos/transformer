package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable
import scala.util.Random

/** Direct [[SortExec]] coverage. Builds a hand-rolled multi-partition
  * [[PhysicalPlan]] so each scenario can exercise the K-way heap merge across
  * specific partition shapes (overlapping ranges, empties, single-partition
  * fast path, small-N concat fallback). Output ordering is asserted against
  * a local oracle comparator that mirrors `SortExec.rowOrdering` — neither
  * the heap merge nor the small-N `Arrays.sort` are stable, so we never
  * assert position-by-position equality, only that the output is sorted
  * under the comparator and is a permutation of the input.
  */
class SortExecTest {

  private val schema2 = Schema(Vector(Field("k", DataType.IntType), Field("p", DataType.IntType)))
  private val k: Expr = ColRefExpr(0, "k", DataType.IntType)
  private val p: Expr = ColRefExpr(1, "p", DataType.IntType)

  // ---- Helpers -------------------------------------------------------------

  private def runSort(
      partitions: Vector[Vector[Array[Any]]],
      schema: Schema,
      keys: Seq[(Expr, Boolean)]): Vector[Array[Any]] = {
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
      keys: Seq[(Expr, Boolean)]): Unit = {
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

  /** Same null + Ops.cmp semantics as `SortExec.rowOrdering` — kept local to
    * the test so we're asserting against a known oracle, not the code under
    * test. */
  private def mirrorOfSortExecComparator(
      schema: Schema, keys: Seq[(Expr, Boolean)]): java.util.Comparator[Array[Any]] = {
    val sortKeys = keys.toArray
    new java.util.Comparator[Array[Any]] {
      def compare(a: Array[Any], b: Array[Any]): Int = {
        val ba = rowBatch(schema, a)
        val bb = rowBatch(schema, b)
        var i = 0
        while (i < sortKeys.length) {
          val (expr, asc) = sortKeys(i)
          val va = expr.eval(ba, 0)
          val vb = expr.eval(bb, 0)
          val c = (va, vb) match {
            case (null, null) => 0
            case (null, _) => if (asc) -1 else 1
            case (_, null) => if (asc) 1 else -1
            case _ => if (asc) Ops.cmp(va, vb) else Ops.cmp(vb, va)
          }
          if (c != 0) return c
          i += 1
        }
        0
      }
    }
  }

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
    val keys = Seq((k, true))
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
    val keys = Seq((k, true), (p, false))
    val out = runSort(parts, schema2, keys)
    assertSortedByKeys("multi-key asc/desc", out, schema2, keys)
    assertPermutationOf("multi-key asc/desc", parts, out)
    val ks = out.map(_(0).asInstanceOf[Int])
    assertEquals(Vector(1, 1, 2, 2, 2, 2, 3, 3, 3), ks)
  }

  @Test def nullsFirstWhenAscending(): Unit = {
    val parts = Vector(
      Vector(row(null, 0), row(1, 0), row(3, 0)),
      Vector(row(2, 1), row(null, 1), row(4, 1)))
    val keys = Seq((k, true))
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
    val keys = Seq((k, false))
    val out = runSort(parts, schema2, keys)
    assertEquals(6, out.length)
    assertEquals(Vector(4, 3, 2, 1), out.take(4).map(_(0).asInstanceOf[Int]))
    assertNull(out(4)(0))
    assertNull(out(5)(0))
    assertSortedByKeys("nulls last desc", out, schema2, keys)
  }

  @Test def emptyPartitionsMixedWithFullPartitions(): Unit = {
    val parts = Vector(
      Vector.empty[Array[Any]],
      Vector(row(2, 1), row(4, 1)),
      Vector.empty[Array[Any]],
      Vector(row(1, 3), row(3, 3), row(5, 3)))
    val keys = Seq((k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(Vector(1, 2, 3, 4, 5), out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("empty + full", out, schema2, keys)
  }

  @Test def allPartitionsEmpty(): Unit = {
    val parts = Vector.fill(4)(Vector.empty[Array[Any]])
    val out = runSort(parts, schema2, Seq((k, true)))
    assertTrue(out.isEmpty)
  }

  @Test def singlePartitionInputStillSorted(): Unit = {
    // Single non-empty partial — exercises the `nonEmpty == 1` shortcut.
    val parts = Vector(Vector(row(5, 0), row(2, 0), row(9, 0), row(1, 0)))
    val keys = Seq((k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(Vector(1, 2, 5, 9), out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("single partition", out, schema2, keys)
  }

  @Test def smallNFastPathSortsCorrectly(): Unit = {
    // Total rows well below SmallNThreshold (4096) → mergeEmit dispatches
    // through smallNSortAndEmit. Verify correctness on that branch.
    val parts = Vector(
      Vector.tabulate(40)(i => row(99 - i, 0)),
      Vector.tabulate(40)(i => row(150 - i * 2, 1)),
      Vector.tabulate(40)(i => row(i, 2)))
    val keys = Seq((k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(120, out.length)
    assertSortedByKeys("small-N fast path", out, schema2, keys)
    assertPermutationOf("small-N fast path", parts, out)
  }

  @Test def largeMultiPartitionMonotone(): Unit = {
    // Bigger than SmallNThreshold so the heap-merge branch actually runs.
    // 12 partitions × 600 rows = 7200 total rows over a random Long key.
    val rnd = new Random(0xC0FFEE)
    val parts = Vector.tabulate(12) { _ =>
      Vector.fill(600)(row(rnd.nextInt(1_000_000), rnd.nextInt(10)))
    }
    val keys = Seq((k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(7200, out.length)
    assertSortedByKeys("large random", out, schema2, keys)
    assertPermutationOf("large random", parts, out)
  }

  @Test def emitsBatchesAtDefaultCapacity(): Unit = {
    // The heap-merge path emits ColumnarBatches of DefaultCapacity. Generate
    // just over capacity rows across two partitions and confirm we see one
    // full batch + one short tail batch — the lazy emit honors DefaultCapacity
    // instead of dumping everything into one batch.
    val cap = ColumnarBatch.DefaultCapacity
    val tail = 17
    val total = cap + tail
    val half = total / 2
    val parts = Vector(
      Vector.tabulate(half)(i => row(i * 2, 0)),
      Vector.tabulate(total - half)(i => row(i * 2 + 1, 1)))
    val plan = new InMemoryPartitionedPlan(schema2, parts)
    val it = SortExec(plan, Seq((k, true))).execute(0)
    val sizes = mutable.ArrayBuffer.empty[Int]
    while (it.hasNext) sizes += it.next().numRows
    assertEquals(Vector(cap, tail), sizes.toVector)
  }

  /** Smoke for a monotone-key sort that crosses partition boundaries — the
    * regression check the plan calls out (jaffle-shop `customer_id`-style
    * sort) at unit-test scale. */
  @Test def monotonicitySmoke(): Unit = {
    val parts = Vector.tabulate(4)(p =>
      Vector.tabulate(1024)(i => row(i * 4 + p, p)))
    val keys = Seq((k, true))
    val out = runSort(parts, schema2, keys)
    assertEquals(4096, out.length)
    assertEquals((0 until 4096).toVector, out.map(_(0).asInstanceOf[Int]))
    assertSortedByKeys("monotonicity", out, schema2, keys)
  }

  private def row(values: Any*): Array[Any] = values.toArray
  private def row(a: Any, b: Any): Array[Any] = Array(a, b)
}

/** Minimal [[PhysicalPlan]] that hands back pre-supplied rows partitioned as
  * given. Each "partition" is split into batches of [[ColumnarBatch.DefaultCapacity]]
  * so [[SortExec]] sees realistic batch boundaries.
  */
private final class InMemoryPartitionedPlan(
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
