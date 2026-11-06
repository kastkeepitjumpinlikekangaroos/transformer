package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

import scala.collection.mutable
import scala.util.Random

/** [[TopNExec]]: the rows it returns are the first `n` of a stable sort of
  * every row, partition by partition in the order read, which is what
  * `SortExec` returns below its small-input threshold; and the planner uses it
  * for `ORDER BY ... LIMIT` (with an OFFSET, through projections) and for
  * nothing else. The oracle sorts with its own comparator, not `SortKey`'s. */
class TopNExecTest {

  private val schema = Schema(Vector(Field("k", DataType.IntType), Field("d", DataType.DoubleType),
    Field("s", DataType.StringType), Field("id", DataType.IntType)))
  private val k: Expr = ColRefExpr(0, "k", DataType.IntType)
  private val d: Expr = ColRefExpr(1, "d", DataType.DoubleType)
  private val s: Expr = ColRefExpr(2, "s", DataType.StringType)

  private def rows(it: Iterator[ColumnarBatch]): Vector[Vector[Any]] = {
    val out = mutable.ArrayBuffer.empty[Vector[Any]]
    it.foreach { b =>
      (0 until b.numRows).foreach { r =>
        out += (0 until b.schema.length).map(c => if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)).toVector
      }
    }
    out.toVector
  }

  /** The first `n` rows of every row sorted stably by `keys`: NULLs where each
    * key says, values by `Ops.cmp`, reversed for DESC. */
  private def oracle(partitions: Vector[Vector[Vector[Any]]], keys: Seq[(Int, Boolean, Boolean)], n: Int): Vector[Vector[Any]] = {
    def cmp(a: Vector[Any], b: Vector[Any]): Int = {
      keys.iterator.map { case (col, asc, nullsFirst) =>
        (a(col), b(col)) match {
          case (null, null) => 0
          case (null, _) => if (nullsFirst) -1 else 1
          case (_, null) => if (nullsFirst) 1 else -1
          case (x, y) => if (asc) Ops.cmp(x, y) else Ops.cmp(y, x)
        }
      }.find(_ != 0).getOrElse(0)
    }
    partitions.flatten.sortWith((a, b) => cmp(a, b) < 0).take(n)
  }

  private def keysOf(spec: Seq[(Int, Boolean, Boolean)]): Seq[SortKey] =
    spec.map { case (col, asc, nullsFirst) => SortKey(Seq(k, d, s)(col), asc, nullsFirst) }

  private def randomPartitions(rng: Random): Vector[Vector[Vector[Any]]] = {
    var id = 0
    Vector.fill(1 + rng.nextInt(5)) {
      Vector.fill(rng.nextInt(400)) {
        id += 1
        Vector[Any](
          if (rng.nextInt(10) == 0) null else rng.nextInt(20),
          rng.nextInt(12) match {
            case 0 => null
            case 1 => Double.NaN
            case 2 => -0.0
            case 3 => 0.0
            case 4 => Double.PositiveInfinity
            case _ => rng.nextInt(8).toDouble / 2
          },
          if (rng.nextInt(10) == 0) null else ('a' + rng.nextInt(6)).toChar.toString,
          id)
      }
    }
  }

  @Test def aTopNReturnsTheFirstRowsOfAStableSort(): Unit = {
    val rng = new Random(20261101)
    val specs = Seq(
      Seq((0, true, true)), Seq((0, false, false)), Seq((1, true, true)), Seq((1, false, true)),
      Seq((2, true, false)), Seq((0, true, false), (1, false, true)), Seq((2, false, false), (0, true, true), (1, true, false)))
    for (round <- 0 until 40; spec <- specs) {
      val parts = randomPartitions(rng)
      val total = parts.map(_.size).sum
      for (n <- Seq(1, 2, 7, 100, total, total + 5).distinct.filter(_ >= 1)) {
        val plan = TopNExec(new RowsPlan(schema, parts), keysOf(spec), n)
        // Each row by its id: a row holding NaN is no Vector equal to itself.
        assertEquals(s"round $round, keys $spec, n $n", oracle(parts, spec, n).map(_(3)), rows(plan.execute(0)).map(_(3)))
      }
    }
  }

  @Test def rowsThatSortAlikeComeInTheOrderTheyWereRead(): Unit = {
    val parts = Vector(
      Vector(Vector[Any](1, 0.0, "x", 1), Vector[Any](0, 0.0, "x", 2), Vector[Any](1, 0.0, "x", 3)),
      Vector.empty,
      Vector(Vector[Any](1, 0.0, "x", 4), Vector[Any](0, 0.0, "x", 5)))
    val plan = TopNExec(new RowsPlan(schema, parts), Seq(SortKey(k, asc = true)), 4)
    assertEquals(Seq(2, 5, 1, 3), rows(plan.execute(0)).map(_(3)))
  }

  @Test def itAgreesWithTheSortOverATotalOrder(): Unit = {
    // Every column a key: rows that sort alike are the same row, so a sort
    // over any number of rows keeps the same multiset.
    val rng = new Random(7)
    val parts = Vector.fill(4)(Vector.fill(3000)(Vector[Any](rng.nextInt(50), rng.nextInt(4).toDouble, "v", rng.nextInt(3))))
    val keys = Seq(SortKey(k, asc = false), SortKey(d, asc = true), SortKey(s, asc = true),
      SortKey(ColRefExpr(3, "id", DataType.IntType), asc = true))
    for (n <- Seq(1, 13, 500)) {
      val top = rows(TopNExec(new RowsPlan(schema, parts), keys, n).execute(0))
      val sorted = rows(LocalLimitExec(SortExec(new RowsPlan(schema, parts), keys), n).execute(0))
      assertEquals(s"n $n", sorted.groupBy(identity).view.mapValues(_.size).toMap,
        top.groupBy(identity).view.mapValues(_.size).toMap)
    }
  }

  // ---- what the planner plans as a top-n ------------------------------------

  private val catalog: Catalog = {
    val c = new Catalog
    val rng = new Random(3)
    c.register("t", new RowsPlanView(schema, Vector.fill(3)(Vector.fill(2000)(
      Vector[Any](rng.nextInt(100), rng.nextInt(10).toDouble, ('a' + rng.nextInt(5)).toChar.toString, rng.nextInt())))))
    c
  }

  private def physical(sql: String): Vector[String] =
    SqlEngine.explain(sql, catalog, verbose = true).dropWhile(_ != "== Physical Plan ==").drop(1)

  @Test def theLimitOfASortIsATopN(): Unit = {
    val top = physical("SELECT k, s FROM t ORDER BY k DESC LIMIT 3")
    assertTrue(top.mkString("\n"), top.head.startsWith("Top 3: ") && top.head.endsWith("k DESC [1 partition]"))
    // Its OFFSET drops the first rows it keeps.
    val withOffset = physical("SELECT k FROM t ORDER BY k LIMIT 3 OFFSET 4")
    assertTrue(withOffset.mkString("\n"), withOffset.head.startsWith("Offset: 4") && withOffset(1).contains("Top 7:"))
    // A projection above the sort, as when a key is not selected, runs on its rows.
    val projected = physical("SELECT s FROM t ORDER BY k LIMIT 2")
    assertTrue(projected.mkString("\n"), projected.head.startsWith("Project:") && projected(1).contains("Top 2:"))
    // TOP and FETCH FIRST are a LIMIT.
    assertTrue(physical("SELECT TOP 5 k FROM t ORDER BY k").exists(_.contains("Top 5:")))
    assertTrue(physical("SELECT k FROM t ORDER BY k FETCH FIRST 4 ROWS ONLY").exists(_.contains("Top 4:")))
    // A key that reads RAND() is read once a row, by the top-n as by the sort:
    // over a DISTINCT the sort reads it itself (without one, the binder
    // computes it in a column below the sort).
    val random = physical("SELECT DISTINCT k FROM t ORDER BY RAND() LIMIT 3")
    assertTrue(random.mkString("\n"), random.exists(_.trim.startsWith("Top 3: RAND()")))
  }

  @Test def otherLimitsAndSortsAreNot(): Unit = {
    for (sql <- Seq("SELECT k FROM t ORDER BY k LIMIT 0", s"SELECT k FROM t ORDER BY k LIMIT ${TopNExec.MaxRows + 1}",
        s"SELECT k FROM t ORDER BY k LIMIT ${TopNExec.MaxRows} OFFSET 1", "SELECT k FROM t ORDER BY k OFFSET 3",
        "SELECT k FROM t ORDER BY k", "SELECT k FROM t LIMIT 3")) {
      assertFalse(sql, physical(sql).exists(_.contains("Top ")))
    }
  }

  @Test def aQueryAnswersAsItDoesWithoutATopN(): Unit = {
    def run(sql: String): Vector[Vector[Any]] = rows(SqlEngine.execute(sql, catalog).batches)
    // A total order, so the rows the limit keeps are one multiset.
    val all = run("SELECT k, d, s, id FROM t ORDER BY k DESC, d, s, id")
    for ((limit, offset) <- Seq((1, 0), (10, 0), (10, 5), (999, 1000), (5, 5995), (TopNExec.MaxRows, 0))) {
      assertEquals(s"LIMIT $limit OFFSET $offset", all.slice(offset, offset + limit),
        run(s"SELECT k, d, s, id FROM t ORDER BY k DESC, d, s, id LIMIT $limit OFFSET $offset"))
    }
  }
}

/** A plan of `partitions`, each read as batches of 100 rows. */
private final class RowsPlan(schema: Schema, partitions: Vector[Vector[Vector[Any]]]) extends PhysicalPlan {
  def outputSchema: Schema = schema
  def numPartitions: Int = partitions.length
  def execute(partition: Int): Iterator[ColumnarBatch] = RowsPlan.batches(schema, partitions(partition))
}

private object RowsPlan {
  def batches(schema: Schema, rows: Vector[Vector[Any]]): Iterator[ColumnarBatch] =
    rows.grouped(100).map { group =>
      val b = new ColumnarBatch(schema, group.length)
      group.indices.foreach { r =>
        (0 until schema.length).foreach { c =>
          if (group(r)(c) == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, group(r)(c))
        }
      }
      b.setNumRows(group.length)
      b
    }
}

/** The same rows as a view the catalog holds. */
private final class RowsPlanView(val schema: Schema, partitions: Vector[Vector[Vector[Any]]]) extends CatalogView {
  def numPartitions: Int = partitions.length
  def readPartition(p: Int): Iterator[ColumnarBatch] = RowsPlan.batches(schema, partitions(p))
}
