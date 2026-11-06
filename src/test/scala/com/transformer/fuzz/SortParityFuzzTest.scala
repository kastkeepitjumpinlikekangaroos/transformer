package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.exec.{ScanExec, SortExec, TopNExec}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable
import scala.util.control.NonFatal

/** A sort returns the stable sort of its input read partition by partition,
  * row for row, ties included: `SortExec` over `DataGen` datasets of every
  * type (narrow domains, so keys tie; the special doubles), 1 to 3 keys in
  * every direction and NULL placement, columns and computed keys, in
  * `ModeDifferential`'s layouts and 8 partitions, from one run to many, past
  * a batch and a few thousand rows, with spill off and at a one-byte
  * threshold, where runs end at other rows. `TopNExec` returns that sort's
  * first `n` rows. The oracle evaluates each key on each row and sorts the
  * boxed values stably by `SortKey.compare`. */
class SortParityFuzzTest {
  import SortParityFuzzTest._

  @Test def fuzzSortParity(): Unit =
    Props.forAll[SortCase]("sort-parity", gen, shrink, Props.seedCountOr(DefaultSeeds))(check)

  /** The corpus reaches what the property is about: keys of every type, as
    * columns and computed, a few thousand rows and fewer, spill, several
    * partitions, ties between whole rows of keys, and a top-n. Measured over
    * these 600 seeds on 2026-11-03: 313 computed keys and 887 columns, 93
    * cases of 4,096 rows or more and 467 of 1 to 4,095, 237 spilled, 332 over
    * two partitions or more, 455 with rows tied on every key, 229 top-n. */
  @Test def theCorpusReachesEveryTypeAndPath(): Unit = {
    val keyTypes = mutable.Set.empty[String]
    var computed, column, kway, small, spilled, partitioned, ties, topN = 0
    (0 until 600).foreach { seed =>
      val c = gen(new Rng(seed))
      c.keys.foreach { k =>
        keyTypes += ExprGen.kind(k.expr.dataType)
        k.expr match { case _: ColRefExpr => column += 1; case _ => computed += 1 }
      }
      if (c.data.numRows >= SmallInput) kway += 1 else if (c.data.numRows > 0) small += 1
      if (c.opts.spillEnabled) spilled += 1
      if (c.layout.partitions.count(_ > 0) >= 2) partitioned += 1
      if (c.topN.isDefined) topN += 1
      if (Sorts.tiedRows(c) > 0) ties += 1
    }
    val every = ExprGen.GenTypes.map(ExprGen.kind).toSet
    assertEquals("key types", every, keyTypes.toSet & every)
    assertTrue(s"computed keys: $computed", computed >= 100)
    assertTrue(s"column keys: $column", column >= 300)
    assertTrue(s"past the small-input threshold: $kway", kway >= 80)
    assertTrue(s"below it: $small", small >= 300)
    assertTrue(s"spilled: $spilled", spilled >= 150)
    assertTrue(s"several partitions: $partitioned", partitioned >= 200)
    assertTrue(s"rows tied on every key: $ties", ties >= 200)
    assertTrue(s"top-n: $topN", topN >= 150)
  }
}

object SortParityFuzzTest {

  /** A seed is several sorts of a few thousand rows, so the default budget
    * is smaller than [[Props.DefaultSeeds]]. */
  val DefaultSeeds: Int = 300

  /** A few thousand rows: many batches of several partitions, the rows a
    * sort once held to below this merging in another way. */
  val SmallInput: Int = 4096

  /** Partition sizes and rows a batch: how the dataset is read. */
  final case class Layout(label: String, partitions: IndexedSeq[Int], batchRows: Int)

  final case class SortCase(data: DataGen.Dataset, keys: Vector[SortKey], layout: Layout, opts: ExecutionOptions,
      topN: Option[Int]) {
    override def toString: String =
      s"SortCase(\n  data = $data\n  keys = ${keys.mkString(", ")}\n  layout = $layout\n  spill = ${opts.spillEnabled}" +
        s"\n  topN = $topN)"
  }

  private val SpillAtOneByte =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L), spillMaxRuns = 1000000)

  def gen(rng: Rng): SortCase = {
    val dataRng = rng.split()
    val keyRng = rng.split()
    val layoutRng = rng.split()
    val base = DataGen.generate(dataRng)
    // Past the small-input threshold on a fifth of the seeds: rows drawn again
    // from the dataset's own, so its narrow domains keep the keys tied.
    val data =
      if (base.numRows == 0 || !dataRng.bool(0.2)) base
      else {
        val n = dataRng.oneOf(Seq(4095, 4096, 4097, dataRng.between(4100, 6000)))
        DataGen.Dataset(base.schema, IndexedSeq.fill(n)(base.rows(dataRng.nextInt(base.numRows)).map(DataGen.fresh)))
      }
    val schema = data.schema
    val keys = Vector.fill(keyRng.between(1, 3)) {
      val expr: Expr =
        if (keyRng.bool(0.7)) {
          val c = keyRng.nextInt(schema.length)
          ColRefExpr(c, schema.fields(c).name, schema.fields(c).dataType)
        } else ExprGen.genTyped(keyRng, schema, ExprGen.pick(keyRng, ExprGen.GenTypes), keyRng.between(1, 3))
      val asc = keyRng.bool()
      if (keyRng.bool()) SortKey(expr, asc) else SortKey(expr, asc, nullsFirst = keyRng.bool())
    }
    val n = data.numRows
    // Spilling a run per batch, a large input is read in large batches.
    val big = n >= SmallInput
    val layout = layoutRng.oneOf(Seq(
      Layout("1 partition", IndexedSeq(n), if (big) 4096 else ColumnarBatch.DefaultCapacity),
      Layout("1 partition, small batches", IndexedSeq(n), if (big) 700 else 3),
      Layout("4 partitions", sizes(n, 4), if (big) 512 else 5),
      Layout("empty partitions", emptyInterleaved(n, 5), if (big) 512 else 4),
      Layout("3 partitions", sizes(n, 3), if (big) 512 else 4),
      Layout("8 partitions", sizes(n, 8), if (big) 300 else layoutRng.oneOf(Seq(1, 2, 7, 64)))))
    val opts = if (layoutRng.bool(0.4)) SpillAtOneByte else ExecutionOptions.Default
    val topN = if (n > 0 && layoutRng.bool(0.4)) Some(layoutRng.oneOf(Seq(1, 2, 5, math.max(1, n / 2), n, n + 3))) else None
    SortCase(data, keys, layout, opts, topN)
  }

  /** Fewer rows, then fewer keys. */
  def shrink(c: SortCase): Iterator[SortCase] = {
    val n = c.data.numRows
    def withRows(rows: IndexedSeq[Array[Any]]): SortCase = {
      val parts = c.layout.partitions.length
      c.copy(data = DataGen.Dataset(c.data.schema, rows), layout = c.layout.copy(partitions = sizes(rows.length, parts)),
        topN = c.topN.map(math.min(_, math.max(1, rows.length))))
    }
    val fewerRows = if (n <= 1) Iterator.empty
      else Iterator(withRows(c.data.rows.take(n / 2)), withRows(c.data.rows.drop(n / 2)), withRows(c.data.rows.dropRight(1)))
    val fewerKeys = if (c.keys.length <= 1) Iterator.empty else c.keys.indices.iterator.map(i => c.copy(keys = c.keys.patch(i, Nil, 1)))
    fewerRows ++ fewerKeys
  }

  private def sizes(n: Int, parts: Int): IndexedSeq[Int] =
    (0 until parts).map(p => n / parts + (if (p < n % parts) 1 else 0))

  /** Rows in the odd partitions only: empty ones first, between and last. */
  private def emptyInterleaved(n: Int, parts: Int): IndexedSeq[Int] = {
    val odd = (0 until parts).filter(_ % 2 == 1)
    val each = sizes(n, odd.length)
    (0 until parts).map(p => if (p % 2 == 1) each(odd.indexOf(p)) else 0)
  }

  def view(c: SortCase): MaterializedView = c.data.reshape(c.layout.partitions, c.layout.batchRows)

  def check(c: SortCase): Unit = {
    val schema = c.data.schema
    // A key that fails on a row fails both sorts, at different times; such a
    // case says nothing about order.
    if (Sorts.keysFail(c)) return
    val stable = Sorts.stableSort(c)
    val got = Sorts.drain(SortExec(ScanExec(view(c)), c.keys, c.opts).execute(0), schema)
    Sorts.assertSameRows("SortExec against the stable sort", schema, stable, got)
    c.topN.foreach { n =>
      val top = Sorts.drain(TopNExec(ScanExec(view(c)), c.keys, n).execute(0), schema)
      Sorts.assertSameRows(s"TopNExec($n) against the stable sort's first $n", schema, stable.take(n), top)
    }
    if (c.opts.spillEnabled) assertEquals("spill directories left", Nil, Spill.operatorDirsLeft)
  }
}

/** What the sort properties compare with. */
private[fuzz] object Sorts {

  def drain(it: Iterator[ColumnarBatch], schema: Schema): Vector[Array[Any]] = {
    val out = Vector.newBuilder[Array[Any]]
    try while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        out += Array.tabulate[Any](schema.length)(col => if (b.column(col).isNull(r)) null else b.column(col).getBoxed(r))
        r += 1
      }
    } finally Closing.close(it)
    out.result()
  }

  /** Each row of `c`'s input, partition by partition in the order read, with
    * its keys' values evaluated on it. */
  def rowsWithKeys(c: SortParityFuzzTest.SortCase): Vector[(Array[Any], Array[Any])] = {
    val v = SortParityFuzzTest.view(c)
    val out = Vector.newBuilder[(Array[Any], Array[Any])]
    (0 until v.numPartitions).foreach { p =>
      val it = v.readPartition(p)
      try it.foreach { b =>
        (0 until b.numRows).foreach { r =>
          val row = Array.tabulate[Any](b.schema.length)(col => if (b.column(col).isNull(r)) null else b.column(col).getBoxed(r))
          out += ((row, c.keys.map(_.expr.eval(b, r)).toArray))
        }
      } finally Closing.close(it)
    }
    out.result()
  }

  /** Whether a key fails on one of the case's rows. */
  def keysFail(c: SortParityFuzzTest.SortCase): Boolean =
    try { rowsWithKeys(c); false } catch { case NonFatal(_) => true }

  /** The input sorted stably by the keys, as `SortKey.compare` orders them. */
  def stableSort(c: SortParityFuzzTest.SortCase): Vector[Array[Any]] = {
    val rows = rowsWithKeys(c).toArray
    java.util.Arrays.sort(rows, (a: (Array[Any], Array[Any]), b: (Array[Any], Array[Any])) => compareKeys(c.keys, a._2, b._2))
    rows.iterator.map(_._1).toVector
  }

  private def compareKeys(keys: Vector[SortKey], a: Array[Any], b: Array[Any]): Int = {
    var k = 0
    while (k < keys.length) {
      val x = keys(k).compare(a(k), b(k))
      if (x != 0) return x
      k += 1
    }
    0
  }

  /** Rows that tie on every key with an earlier row of the input. */
  def tiedRows(c: SortParityFuzzTest.SortCase): Int =
    try {
      val rows = rowsWithKeys(c)
      val sorted = rows.map(_._2).sortWith((a, b) => compareKeys(c.keys, a, b) < 0)
      sorted.indices.count(i => i > 0 && compareKeys(c.keys, sorted(i - 1), sorted(i)) == 0)
    } catch { case NonFatal(_) => 0 }

  /** One value: a float by its bits, so each row keeps its own zero (-0.0
    * is not 0.0), but any NaN for a NaN (a spilled run need not keep a NaN's
    * payload); a byte array by content; a decimal by value (a spilled run
    * reads one back at its column's declared scale); everything else by
    * `equals`. */
  def sameValue(a: Any, b: Any): Boolean = (a, b) match {
    case (null, null) => true
    case (null, _) | (_, null) => false
    case (x: java.lang.Double, y: java.lang.Double) =>
      (x.isNaN && y.isNaN) || java.lang.Double.doubleToRawLongBits(x) == java.lang.Double.doubleToRawLongBits(y)
    case (x: java.lang.Float, y: java.lang.Float) =>
      (x.isNaN && y.isNaN) || java.lang.Float.floatToRawIntBits(x) == java.lang.Float.floatToRawIntBits(y)
    case (x: Array[Byte], y: Array[Byte]) => java.util.Arrays.equals(x, y)
    case (x: java.math.BigDecimal, y: java.math.BigDecimal) => x.compareTo(y) == 0
    case (x, y) => x == y
  }

  def assertSameRows(label: String, schema: Schema, expected: Vector[Array[Any]], got: Vector[Array[Any]]): Unit = {
    if (expected.length != got.length)
      throw new AssertionError(s"$label: ${got.length} rows where ${expected.length} were expected")
    var i = 0
    while (i < expected.length) {
      var c = 0
      while (c < schema.length) {
        if (!sameValue(expected(i)(c), got(i)(c)))
          throw new AssertionError(s"$label: row $i differs at column $c (${schema.fields(c).name}): " +
            s"${RowOracle.describe(got(i)(c))} where ${RowOracle.describe(expected(i)(c))} was expected\n" +
            s"  expected rows ${expected.slice(math.max(0, i - 2), i + 3).map(_.map(RowOracle.show).mkString("(", ",", ")")).mkString(" ")}\n" +
            s"  got rows      ${got.slice(math.max(0, i - 2), i + 3).map(_.map(RowOracle.show).mkString("(", ",", ")")).mkString(" ")}")
        c += 1
      }
      i += 1
    }
  }
}
