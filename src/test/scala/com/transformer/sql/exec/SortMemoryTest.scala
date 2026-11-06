package com.transformer.sql.exec

import com.transformer.core._
import org.junit.Assert._
import org.junit.Assume.assumeTrue
import org.junit.Test

/** A sort holds its rows typed, not boxed: `ORDER BY` over 4 million rows of
  * an INT and a BIGINT, made as they are read, runs in this target's 256 MB
  * heap, where the boxed rows (an array, an `Integer` and a `Long` each) took
  * about 300 MB. The rows come back in order, every one of them. */
class SortMemoryTest {

  private val Rows = 4000000
  private val Parts = 4

  private def v(i: Int): Long = (i * 2654435761L) % 1000003L

  private val big: CatalogView = new CatalogView {
    val schema: Schema = Schema(Vector(Field("i", DataType.IntType), Field("v", DataType.LongType)))
    def numPartitions: Int = Parts
    def readPartition(p: Int): Iterator[ColumnarBatch] = {
      val from = p * (Rows / Parts)
      val until = from + Rows / Parts
      Iterator.iterate(from)(_ + ColumnarBatch.DefaultCapacity).takeWhile(_ < until).map { start =>
        val n = math.min(ColumnarBatch.DefaultCapacity, until - start)
        val b = new ColumnarBatch(schema, n)
        val is = b.column(0).asInstanceOf[IntVector]
        val vs = b.column(1).asInstanceOf[LongVector]
        var r = 0
        while (r < n) { is.set(r, start + r); vs.set(r, v(start + r)); r += 1 }
        b.setNumRows(n)
        b
      }
    }
  }

  @Test def aSortOfMillionsOfRowsHoldsThemTyped(): Unit = {
    // A batch of one or two rows costs a chunk of its own a row: the suite's
    // small-batch configurations sort smaller inputs (docs/testing.md).
    assumeTrue("needs batches of 1024 rows or more", ColumnarBatch.DefaultCapacity >= 1024)
    val catalog = new Catalog
    catalog.register("big", big)
    val q = SqlEngine.execute("SELECT i, v FROM big ORDER BY v DESC, i", catalog)
    var count = 0L
    var sumI = 0L
    var sumV = 0L
    var prevV = Long.MaxValue
    var prevI = -1
    q.batches.foreach { b =>
      val is = b.column(0).asInstanceOf[IntVector]
      val vs = b.column(1).asInstanceOf[LongVector]
      var r = 0
      while (r < b.numRows) {
        val i = is.get(r)
        val value = vs.get(r)
        if (value > prevV || (value == prevV && i <= prevI))
          fail(s"row $count out of order: ($i, $value) after ($prevI, $prevV)")
        prevV = value
        prevI = i
        count += 1
        sumI += i
        sumV += value
        r += 1
      }
    }
    assertEquals(Rows.toLong, count)
    assertEquals(Rows.toLong * (Rows - 1) / 2, sumI)
    assertEquals((0 until Rows).iterator.map(i => v(i)).sum, sumV)
  }
}
