package com.transformer.sql.exec

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** Every key hash 0 (`-Dtransformer.hash_zero=true`, [[KeyHash]]), and the
  * planner sharding every breaker four ways: every hash map meets only
  * collisions, every exchange routes every row to one shard, and every spill
  * bucket is one bucket. Each operator that keys rows by a hash must still
  * tell two keys apart by their values; each answer here is computed in Scala
  * beside the query. `--config=hashzero` runs the whole suite so. */
class HashZeroTest {

  private val n = 2000
  private val catalog: Catalog = {
    val c = new Catalog
    val t = Schema(Vector(Field("i", DataType.IntType), Field("k", DataType.IntType), Field("s", DataType.StringType),
      Field("d", DataType.DecimalType(10, 2)), Field("b", DataType.BinaryType), Field("l", DataType.LongType)))
    c.register("t", view(t, (0 until n).map(i => Array[Any](i, i % 500, s"s${i % 300}",
      new java.math.BigDecimal(i % 50).movePointLeft(1).setScale(2), Array[Byte]((i % 40).toByte, 1), (i % 97).toLong)), 4))
    val u = Schema(Vector(Field("k", DataType.IntType), Field("name", DataType.StringType)))
    c.register("u", view(u, (0 until 600 by 2).map(k => Array[Any](k, s"n$k")), 3))
    c
  }

  private def view(schema: Schema, rows: IndexedSeq[Array[Any]], partitions: Int): MaterializedView = {
    val per = (rows.size + partitions - 1) / partitions
    new MaterializedView(schema, rows.grouped(per).map { chunk =>
      val b = new ColumnarBatch(schema, chunk.size)
      chunk.zipWithIndex.foreach { case (row, r) =>
        row.indices.foreach(col => if (row(col) == null) b.column(col).setNull(r) else b.column(col).setBoxed(r, row(col)))
      }
      b.setNumRows(chunk.size)
      IndexedSeq(b)
    }.toIndexedSeq)
  }

  private val spill = ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))

  private def hex(bytes: Seq[Byte]): String = bytes.map(b => f"$b%02x").mkString("x'", "", "'")

  private def rows(sql: String, opts: ExecutionOptions = ExecutionOptions.Default): Vector[Seq[Any]] = {
    val q = SqlEngine.execute(sql, catalog, opts)
    q.batches.flatMap(b => (0 until b.numRows).map(r => (0 until b.schema.length).map { c =>
      if (b.column(c).isNull(r)) null
      else b.column(c).getBoxed(r) match {
        case bytes: Array[Byte] => hex(bytes.toSeq)
        case other => other
      }
    })).toVector
  }

  /** Each query runs as is and with spill at a one-byte threshold, which
    * writes a run a batch: at a batch capacity of 1 (`--config=batch1`) its
    * 2000 rows would pass `spill_max_runs`, so there it runs as is alone. */
  private def assertRows(sql: String, expected: Seq[Seq[Any]]): Unit =
    (ExecutionOptions.Default +: (if (ColumnarBatch.DefaultCapacity >= 1024) Seq(spill) else Nil)).foreach { o =>
    assertEquals(s"$sql (spill ${o.spillEnabled})", expected.map(_.mkString("|")).sorted,
      rows(sql, o).map(_.mkString("|")).sorted)
  }

  @Test def everyKeyHashIsZeroAndEveryBreakerShards(): Unit = {
    assertTrue("-Dtransformer.hash_zero=true did not arrive", KeyHash.zero)
    assertEquals(0, KeyHash(12345))
  }

  @Test def groupByTellsKeysOfOneHashApart(): Unit = {
    val is = 0 until n
    assertRows("SELECT k, COUNT(*) FROM t GROUP BY k", is.groupBy(_ % 500).map { case (k, v) => Seq(k, v.size.toLong) }.toSeq)
    assertRows("SELECT s, k % 3, COUNT(*) FROM t GROUP BY s, k % 3",
      is.groupBy(i => (s"s${i % 300}", (i % 500) % 3)).map { case ((s, m), v) => Seq(s, m, v.size.toLong) }.toSeq)
    assertRows("SELECT d, COUNT(*) FROM t GROUP BY d",
      is.groupBy(_ % 50).map { case (d, v) => Seq(new java.math.BigDecimal(d).movePointLeft(1).setScale(2), v.size.toLong) }.toSeq)
    assertRows("SELECT b, COUNT(*) FROM t GROUP BY b",
      is.groupBy(_ % 40).map { case (b, v) => Seq(hex(Seq[Byte](b.toByte, 1)), v.size.toLong) }.toSeq)
    assertRows("SELECT l, SUM(k) FROM t GROUP BY l",
      is.groupBy(_ % 97).map { case (l, v) => Seq(l.toLong, v.map(i => (i % 500).toLong).sum) }.toSeq)
    // Two fixed-width keys pack into one byte array (BytesKey).
    assertRows("SELECT k % 7, l, COUNT(*) FROM t GROUP BY k % 7, l",
      is.groupBy(i => ((i % 500) % 7, (i % 97).toLong)).map { case ((a, l), v) => Seq(a, l, v.size.toLong) }.toSeq)
  }

  @Test def distinctTellsValuesOfOneHashApart(): Unit = {
    assertRows("SELECT DISTINCT s FROM t", (0 until 300).map(i => Seq(s"s$i")))
    assertRows("SELECT COUNT(DISTINCT k), COUNT(DISTINCT s) FROM t", Seq(Seq(500L, 300L)))
    assertRows("SELECT DISTINCT k % 7, l % 5 FROM t",
      (0 until n).map(i => ((i % 500) % 7, (i % 97).toLong % 5)).distinct.map { case (a, b) => Seq(a, b) })
    assertRows("SELECT s FROM t UNION SELECT name FROM u", ((0 until 300).map(i => s"s$i") ++ (0 until 600 by 2).map(k => s"n$k"))
      .distinct.map(Seq(_)))
  }

  @Test def joinsMatchKeysByValue(): Unit = {
    val is = 0 until n
    assertRows("SELECT COUNT(*) FROM t JOIN u ON t.k = u.k", Seq(Seq(is.count(i => (i % 500) % 2 == 0).toLong)))
    assertRows("SELECT t.k, u.name FROM t JOIN u ON t.k = u.k WHERE t.i < 40",
      (0 until 40).filter(i => i % 2 == 0).map(i => Seq(i, s"n$i")))
    assertRows("SELECT u.k, COUNT(t.i) FROM u LEFT JOIN t ON t.k = u.k GROUP BY u.k",
      (0 until 600 by 2).map(k => Seq(k, if (k < 500) 4L else 0L)))
    // A join on two fixed-width keys packs them (BytesKey).
    assertRows("SELECT COUNT(*) FROM t x JOIN t y ON x.k = y.k AND x.l = y.l",
      Seq(Seq(is.groupBy(i => (i % 500, i % 97)).values.map(v => v.size.toLong * v.size).sum)))
  }

  @Test def windowsPartitionByValue(): Unit =
    assertRows("WITH w AS (SELECT s, ROW_NUMBER() OVER (PARTITION BY s ORDER BY i) AS r FROM t) " +
      "SELECT s, MAX(r), COUNT(*) FROM w GROUP BY s",
      (0 until n).groupBy(i => s"s${i % 300}").map { case (s, v) => Seq(s, v.size.toLong, v.size.toLong) }.toSeq)
}
