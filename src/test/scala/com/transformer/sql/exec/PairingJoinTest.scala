package com.transformer.sql.exec

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** A join with no equality between its sides pairs its rows a wave at a
  * time: every kind answers as a nested loop over the two sides does, and a
  * pairing of 25 million rows streams through a heap a fraction of their size
  * (the target runs at `-Xmx256m`), where every pair was held before its first
  * batch. */
class PairingJoinTest {

  private def view(schema: Schema, rows: IndexedSeq[Array[Any]], partitions: Int): MaterializedView = {
    val per = math.max(1, (rows.size + partitions - 1) / partitions)
    val parts = rows.grouped(per).map { chunk =>
      val b = new ColumnarBatch(schema, math.max(1, chunk.size))
      chunk.zipWithIndex.foreach { case (row, r) =>
        row.indices.foreach(c => if (row(c) == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, row(c)))
      }
      b.setNumRows(chunk.size)
      IndexedSeq(b)
    }.toIndexedSeq
    new MaterializedView(schema, parts)
  }

  private val ints = Schema(Vector(Field("x", DataType.IntType), Field("tag", DataType.StringType)))

  private def rows(q: ExecutedQuery): Vector[Seq[Any]] =
    q.batches.flatMap(b => (0 until b.numRows).map(r => (0 until b.schema.length).map(c =>
      if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)))).toVector

  /** Inner, LEFT, RIGHT and FULL joins on `l.x < r.x` over tables of 300
    * rows with NULLs, in several partitions: each probe partition takes
    * several waves (a wave's task pairs about 32768 rows, a hundred probe
    * rows here), and the answer is the nested loop's. */
  @Test def everyKindAnswersAsANestedLoop(): Unit = {
    val rng = new scala.util.Random(7)
    def table(n: Int, name: String) =
      (0 until n).map(i => Array[Any](if (rng.nextInt(10) == 0) null else rng.nextInt(50), s"$name$i"))
    val (l, r) = (table(300, "l"), table(300, "r"))
    val catalog = new Catalog
    catalog.register("l", view(ints, l, 3))
    catalog.register("r", view(ints, r, 2))
    def less(a: Array[Any], b: Array[Any]) =
      a(0) != null && b(0) != null && a(0).asInstanceOf[Int] < b(0).asInstanceOf[Int]
    val inner = for (a <- l; b <- r if less(a, b)) yield Seq[Any](a(0), a(1), b(0), b(1))
    val leftOnly = l.filterNot(a => r.exists(less(a, _))).map(a => Seq[Any](a(0), a(1), null, null))
    val rightOnly = r.filterNot(b => l.exists(less(_, b))).map(b => Seq[Any](null, null, b(0), b(1)))
    def check(kind: String, expected: Seq[Seq[Any]]): Unit = {
      val got = rows(SqlEngine.execute(s"SELECT l.x, l.tag, r.x, r.tag FROM l $kind JOIN r ON l.x < r.x", catalog,
        ExecutionOptions.Default))
      assertEquals(kind, expected.map(_.mkString("|")).sorted, got.map(_.mkString("|")).sorted)
    }
    check("INNER", inner)
    check("LEFT", inner ++ leftOnly)
    check("RIGHT", inner ++ rightOnly)
    check("FULL", inner ++ leftOnly ++ rightOnly)
    // An empty side pairs nothing, and an outer join keeps the other.
    catalog.register("e", view(ints, IndexedSeq.empty, 1))
    assertEquals(0, rows(SqlEngine.execute("SELECT * FROM l JOIN e ON l.x < e.x", catalog, ExecutionOptions.Default)).size)
    assertEquals(300, rows(SqlEngine.execute("SELECT * FROM l LEFT JOIN e ON l.x < e.x", catalog,
      ExecutionOptions.Default)).size)
  }

  /** 5000 rows a side, the most the nested-loop guard lets two sides hold,
    * paired on a condition every pair meets: 25 million rows of four columns,
    * a few GB held at once, counted in a 256 MB heap. */
  @Test def aPairingOf25MillionRowsStreams(): Unit = {
    val catalog = new Catalog
    val side = (0 until 5000).map(i => Array[Any](i, s"t$i"))
    catalog.register("a", view(ints, side, 4))
    catalog.register("b", view(ints, side, 4))
    val q = SqlEngine.execute("SELECT COUNT(*), MIN(a.tag || b.tag) FROM a JOIN b ON a.x - b.x < 10000", catalog,
      ExecutionOptions.Default)
    assertEquals(Seq[Any](25000000L, "t0t0"), rows(q).head)
  }
}
