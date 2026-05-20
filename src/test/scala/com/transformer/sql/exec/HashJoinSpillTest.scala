package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.util.Random

/** Plan 09 Phase 6 parity: grace hash join must produce the same row
  * multiset as the in-memory hash join under inner / LEFT outer / RIGHT
  * outer / FULL outer for both equi-key types (single-Long fast path +
  * codec multi-key path).
  *
  * We force grace hash via `opts.spillEnabled = true`. Because grace hash
  * always processes buckets sequentially (even on tiny inputs in this
  * test) the output row order will differ from the non-spill path — we
  * compare row multisets, never positional equality. */
class HashJoinSpillTest {

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setOverride(): Unit = {
    tmpRoot = Files.createTempDirectory("hashjoin-spill-test-")
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

  private def multiset(v: Vector[Vector[Any]]): Map[Vector[Any], Int] =
    v.groupBy(identity).view.mapValues(_.size).toMap

  private def spillyOpts(): ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))

  /** Plan that emits a fixed schema and a single Iterator of pre-built rows
    * partitioned across N partitions. */
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

  private def runJoin(
      leftPlan: PhysicalPlan,
      rightPlan: PhysicalPlan,
      leftKeys: Seq[Expr],
      rightKeys: Seq[Expr],
      kind: JoinKind,
      opts: ExecutionOptions): Vector[Vector[Any]] = {
    val j = HashJoinExec(leftPlan, rightPlan, leftKeys, rightKeys, None, kind,
                        buildRight = true, opts = opts)
    val out = (0 until j.numPartitions).iterator.flatMap(j.execute)
    drainRows(out, j.outputSchema)
  }

  // ---- Long-key fast path --------------------------------------------------

  private def longKeyPlans(
      lefts: Seq[(Int, Long)], rights: Seq[(Int, Long)]
  ): (PhysicalPlan, PhysicalPlan, Expr, Expr) = {
    val leftSchema = Schema(Vector(Field("k", DataType.IntType), Field("lv", DataType.LongType)))
    val rightSchema = Schema(Vector(Field("k", DataType.IntType), Field("rv", DataType.LongType)))
    def toBatch(rows: Seq[(Int, Long)]) =
      rows.map(t => Array[Any](java.lang.Integer.valueOf(t._1), java.lang.Long.valueOf(t._2))).toVector
    val leftPlan = new InMemoryPlan(leftSchema, Vector(toBatch(lefts)))
    val rightPlan = new InMemoryPlan(rightSchema, Vector(toBatch(rights)))
    val lk = ColRefExpr(0, "k", DataType.IntType)
    val rk = ColRefExpr(0, "k", DataType.IntType)
    (leftPlan, rightPlan, lk, rk)
  }

  @Test def innerJoinLongKeyParity(): Unit = {
    val rng = new Random(42L)
    val lefts = (0 until 500).map(_ => (rng.nextInt(30), rng.nextLong() & 0xFFFFL))
    val rights = (0 until 200).map(_ => (rng.nextInt(30), rng.nextLong() & 0xFFFFL))
    val (l, r, lk, rk) = longKeyPlans(lefts, rights)
    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Inner, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Inner, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  @Test def leftOuterJoinLongKeyParity(): Unit = {
    val rng = new Random(11L)
    val lefts = (0 until 200).map(_ => (rng.nextInt(50), rng.nextLong() & 0xFFFFL))
    val rights = (0 until 50).map(_ => (rng.nextInt(30), rng.nextLong() & 0xFFFFL))
    val (l, r, lk, rk) = longKeyPlans(lefts, rights)
    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Left, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Left, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  @Test def rightOuterJoinLongKeyParity(): Unit = {
    val rng = new Random(13L)
    val lefts = (0 until 50).map(_ => (rng.nextInt(30), rng.nextLong() & 0xFFFFL))
    val rights = (0 until 200).map(_ => (rng.nextInt(50), rng.nextLong() & 0xFFFFL))
    val (l, r, lk, rk) = longKeyPlans(lefts, rights)
    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Right, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Right, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  @Test def fullOuterJoinLongKeyParity(): Unit = {
    val rng = new Random(17L)
    val lefts = (0 until 150).map(_ => (rng.nextInt(40), rng.nextLong() & 0xFFFFL))
    val rights = (0 until 150).map(_ => (rng.nextInt(40), rng.nextLong() & 0xFFFFL))
    val (l, r, lk, rk) = longKeyPlans(lefts, rights)
    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Full, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Full, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  // ---- NULL key handling ---------------------------------------------------

  @Test def nullKeysNeverMatchUnderSpill(): Unit = {
    val leftSchema = Schema(Vector(Field("k", DataType.IntType), Field("lv", DataType.LongType)))
    val rightSchema = Schema(Vector(Field("k", DataType.IntType), Field("rv", DataType.LongType)))
    val lefts: Vector[Array[Any]] = Vector(
      Array(java.lang.Integer.valueOf(1), java.lang.Long.valueOf(10)),
      Array(null,                          java.lang.Long.valueOf(20)),
      Array(java.lang.Integer.valueOf(2), java.lang.Long.valueOf(30)))
    val rights: Vector[Array[Any]] = Vector(
      Array(java.lang.Integer.valueOf(1), java.lang.Long.valueOf(100)),
      Array(null,                          java.lang.Long.valueOf(200)),
      Array(java.lang.Integer.valueOf(3), java.lang.Long.valueOf(300)))
    val l = new InMemoryPlan(leftSchema, Vector(lefts))
    val r = new InMemoryPlan(rightSchema, Vector(rights))
    val lk = ColRefExpr(0, "k", DataType.IntType)
    val rk = ColRefExpr(0, "k", DataType.IntType)

    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Full, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Full, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  // ---- Codec path (multi-key, variable-width) ------------------------------

  @Test def innerJoinCodecMultiKeyParity(): Unit = {
    val leftSchema = Schema(Vector(
      Field("a", DataType.StringType),
      Field("b", DataType.IntType),
      Field("lv", DataType.LongType)))
    val rightSchema = Schema(Vector(
      Field("a", DataType.StringType),
      Field("b", DataType.IntType),
      Field("rv", DataType.LongType)))
    val rng = new Random(99L)
    val lefts = (0 until 300).map(_ =>
      Array[Any](s"a${rng.nextInt(20)}",
                 java.lang.Integer.valueOf(rng.nextInt(10)),
                 java.lang.Long.valueOf(rng.nextLong() & 0xFFFFL))).toVector
    val rights = (0 until 200).map(_ =>
      Array[Any](s"a${rng.nextInt(20)}",
                 java.lang.Integer.valueOf(rng.nextInt(10)),
                 java.lang.Long.valueOf(rng.nextLong() & 0xFFFFL))).toVector
    val l = new InMemoryPlan(leftSchema, Vector(lefts))
    val r = new InMemoryPlan(rightSchema, Vector(rights))
    val la = ColRefExpr(0, "a", DataType.StringType)
    val lb = ColRefExpr(1, "b", DataType.IntType)
    val ra = ColRefExpr(0, "a", DataType.StringType)
    val rb = ColRefExpr(1, "b", DataType.IntType)
    val expected = runJoin(l, r, Seq(la, lb), Seq(ra, rb), JoinKind.Inner, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(la, lb), Seq(ra, rb), JoinKind.Inner, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  // ---- Edge cases ----------------------------------------------------------

  @Test def emptyLeftSideUnderSpill(): Unit = {
    val (l, r, lk, rk) = longKeyPlans(Seq.empty, Seq((1, 100L)))
    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Inner, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Inner, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
  }

  @Test def emptyRightSideUnderSpillLeftOuterPreservesLeft(): Unit = {
    val (l, r, lk, rk) = longKeyPlans(
      Seq((1, 10L), (2, 20L), (3, 30L)), Seq.empty)
    val expected = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Left, ExecutionOptions.Default)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Left, spillyOpts())
    assertEquals(multiset(expected), multiset(actual))
    assertEquals(3, actual.length)
  }

  @Test def bothEmptyUnderSpillProducesEmpty(): Unit = {
    val (l, r, lk, rk) = longKeyPlans(Seq.empty, Seq.empty)
    val actual = runJoin(l, r, Seq(lk), Seq(rk), JoinKind.Full, spillyOpts())
    assertEquals(Vector.empty[Vector[Any]], actual)
  }
}
