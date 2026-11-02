package com.transformer.sql.exec

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** A top-n holds its rows, not its input's: `ORDER BY ... LIMIT 5` over 20
  * million rows, made as they are read, runs in this target's 256 MB heap,
  * where a sort of them (each row a boxed array) would need about 1.6 GB. */
class TopNMemoryTest {

  private val Rows = 20000000
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

  @Test def aTopNOfMillionsOfRowsHoldsOnlyTheRowsItReturns(): Unit = {
    val catalog = new Catalog
    catalog.register("big", big)
    val q = SqlEngine.execute("SELECT i, v FROM big ORDER BY v DESC, i LIMIT 5", catalog)
    val got = q.batches.flatMap(b => (0 until b.numRows).map(r => (b.column(0).getBoxed(r), b.column(1).getBoxed(r)))).toVector
    // The same five, found by walking every row.
    val expected = (0 until Rows).iterator.map(i => (i, v(i)))
      .foldLeft(Vector.empty[(Int, Long)]) { (top, row) =>
        (top :+ row).sortBy { case (i, value) => (-value, i) }.take(5)
      }
    assertEquals(expected, got.map { case (i, value) => (i.asInstanceOf[Int], value.asInstanceOf[Long]) })
  }
}
