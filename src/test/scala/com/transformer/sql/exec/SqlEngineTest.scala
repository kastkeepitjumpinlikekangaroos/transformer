package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.read.csv.{CsvOptions, CsvReader}
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}

class SqlEngineTest {

  private def tmpCsv(name: String, contents: String): Path = {
    val dir = Files.createTempDirectory("sql-engine-")
    val p = dir.resolve(name)
    Files.writeString(p, contents)
    p
  }

  private def catalogWith(view: (String, CatalogView)): Catalog = {
    val c = new Catalog
    c.register(view._1, view._2)
    c
  }

  private def collectAllRows(q: ExecutedQuery): Seq[Map[String, Any]] = {
    val buf = scala.collection.mutable.ArrayBuffer.empty[Map[String, Any]]
    while (q.batches.hasNext) {
      val b = q.batches.next()
      var r = 0
      while (r < b.numRows) {
        buf += q.schema.fieldNames.zipWithIndex.map { case (n, i) =>
          n -> (if (b.column(i).isNull(r)) null else b.column(i).getBoxed(r))
        }.toMap
        r += 1
      }
    }
    buf.toSeq
  }

  @Test def selectStarReturnsAllRows(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT * FROM t", cat)
    assertEquals(Vector("id", "name"), q.schema.fieldNames)
    val rows = collectAllRows(q)
    assertEquals(3, rows.size)
    assertEquals(1, rows.head("id"))
    assertEquals("alice", rows.head("name"))
  }

  @Test def whereFiltersRows(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT id FROM t WHERE score > 50", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 4), rows.map(_("id")))
  }

  @Test def projectArithmeticAndAlias(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n3\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT x * 2 AS doubled FROM t", cat)
    assertEquals(Vector("doubled"), q.schema.fieldNames)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 4, 6), rows.map(_("doubled")))
  }

  @Test def caseExpressionBranches(): Unit = {
    val p = tmpCsv("a.csv", "x\n5\n50\n500\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT CASE WHEN x < 10 THEN 'small' WHEN x < 100 THEN 'mid' ELSE 'big' END AS bucket FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("small", "mid", "big"), rows.map(_("bucket")))
  }

  @Test def groupByCountAndSum(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nA,2\nB,3\nB,4\nB,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT cat, COUNT(*) AS n, SUM(score) AS s FROM t GROUP BY cat", cat)
    val rows = collectAllRows(q).sortBy(_("cat").toString)
    assertEquals(2, rows.size)
    assertEquals("A", rows(0)("cat"))
    assertEquals(2L, rows(0)("n"))
    assertEquals(3L, rows(0)("s"))
    assertEquals("B", rows(1)("cat"))
    assertEquals(3L, rows(1)("n"))
    assertEquals(12L, rows(1)("s"))
  }

  @Test def groupByPositionalOrdinal(): Unit = {
    val p = tmpCsv("a.csv",
      "product_id,is_food_item\n1,true\n1,false\n2,true\n2,true\n3,false\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // `GROUP BY 2` should mean "group by the 2nd select item" (product_id),
    // matching BigQuery/Postgres semantics.
    val q = SqlEngine.execute(
      "SELECT MAX(CASE WHEN is_food_item THEN 1 ELSE 0 END) AS m, product_id " +
        "FROM t GROUP BY 2", cat)
    val rows = collectAllRows(q).sortBy(_("product_id").toString)
    assertEquals(3, rows.size)
    assertEquals(1, rows(0)("product_id"))
    assertEquals(1, rows(0)("m"))
    assertEquals(2, rows(1)("product_id"))
    assertEquals(1, rows(1)("m"))
    assertEquals(3, rows(2)("product_id"))
    assertEquals(0, rows(2)("m"))
  }

  @Test def groupByOrdinalMixedWithColumn(): Unit = {
    val p = tmpCsv("a.csv", "cat,sub,score\nA,x,1\nA,x,2\nA,y,3\nB,x,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, sub, SUM(score) AS s FROM t GROUP BY 1, sub", cat)
    val rows = collectAllRows(q).sortBy(r => (r("cat").toString, r("sub").toString))
    assertEquals(3, rows.size)
    assertEquals(("A", "x", 3L), (rows(0)("cat"), rows(0)("sub"), rows(0)("s")))
    assertEquals(("A", "y", 3L), (rows(1)("cat"), rows(1)("sub"), rows(1)("s")))
    assertEquals(("B", "x", 4L), (rows(2)("cat"), rows(2)("sub"), rows(2)("s")))
  }

  @Test def groupByOrdinalOutOfRangeRejected(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try { SqlEngine.execute("SELECT x, COUNT(*) FROM t GROUP BY 5", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("GROUP BY position 5"))
  }

  @Test def groupByOrdinalReferencingAggregateRejected(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nA,2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try { SqlEngine.execute("SELECT cat, SUM(score) FROM t GROUP BY 2", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("aggregate"))
  }

  @Test def countDistinct(): Unit = {
    val p = tmpCsv("a.csv", "cat\nA\nA\nB\nB\nC\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT COUNT(DISTINCT cat) AS c FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(3L, rows.head("c"))
  }

  @Test def avgMinMax(): Unit = {
    val p = tmpCsv("a.csv", "x\n10\n20\n30\n40\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT AVG(x) AS a, MIN(x) AS lo, MAX(x) AS hi FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(25.0, rows.head("a").asInstanceOf[Double], 1e-9)
    assertEquals(10, rows.head("lo"))
    assertEquals(40, rows.head("hi"))
  }

  @Test def innerJoin(): Unit = {
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n3,50\n4,99\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT u.name, s.score FROM u JOIN s ON u.id = s.user_id ORDER BY u.id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("alice", "charlie"), rows.map(_("name")))
    assertEquals(Seq(100, 50), rows.map(_("score")))
  }

  @Test def leftOuterJoinKeepsAll(): Unit = {
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT u.name, s.score FROM u LEFT JOIN s ON u.id = s.user_id ORDER BY u.id", cat)
    val rows = collectAllRows(q)
    assertEquals(3, rows.size)
    assertEquals(Seq("alice", "bob", "charlie"), rows.map(_("name")))
    assertEquals(Seq(100, null, 50), rows.map(_("score")))
  }

  @Test def innerJoinWithLeftSideFilterStillCorrect(): Unit = {
    // Exercises FilterPushdown: l.id = 1 should push under the left scan.
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n2,80\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u JOIN s ON u.id = s.user_id WHERE u.id = 1", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals("alice", rows.head("name"))
    assertEquals(100, rows.head("score"))
  }

  @Test def innerJoinWithRightSideFilterStillCorrect(): Unit = {
    // s.score > 70 should push under the right scan.
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n2,80\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u JOIN s ON u.id = s.user_id WHERE s.score > 70 ORDER BY u.id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("alice", "bob"), rows.map(_("name")))
    assertEquals(Seq(100, 80), rows.map(_("score")))
  }

  @Test def innerJoinWithCrossSideFilterStillCorrect(): Unit = {
    // u.id + s.score > 100 — references both sides, must stay above the join.
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n2,80\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u JOIN s ON u.id = s.user_id WHERE u.id + s.score > 100 ORDER BY u.id", cat)
    val rows = collectAllRows(q)
    // u.id + s.score: (1,100)=101>100, (2,80)=82, (3,50)=53. Only alice qualifies.
    assertEquals(1, rows.size)
    assertEquals("alice", rows.head("name"))
  }

  @Test def leftOuterJoinWithRightSideFilterStillKeepsUnmatchedAsNull(): Unit = {
    // Critical correctness: `WHERE s.score > 60` on a LEFT JOIN must NOT be
    // pushed into the right child — pushing it would change the result for
    // unmatched left rows (which should still survive as null-extended, but
    // become subject to `null > 60` = unknown = filtered out, matching the
    // post-join filter semantics). The optimizer leaves it above and SQL
    // behaviour is preserved.
    //
    // Expected results: SQL's natural WHERE-filters-null behaviour drops bob
    // (no match) and charlie (score=50). Only alice (score=100) survives.
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u LEFT JOIN s ON u.id = s.user_id WHERE s.score > 60", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals("alice", rows.head("name"))
    assertEquals(100, rows.head("score"))
  }

  @Test def leftOuterJoinAntiJoinPatternStillWorks(): Unit = {
    // WHERE s.user_id IS NULL — the classic anti-join shape. Pushing the
    // IS NULL into the right child would change its semantics (test the
    // actual right-side user_id values rather than the null-extended ones
    // from unmatched left rows). The optimizer keeps it above.
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name FROM u LEFT JOIN s ON u.id = s.user_id WHERE s.user_id IS NULL", cat)
    val rows = collectAllRows(q)
    // Only bob (id=2) lacks a match on the right side.
    assertEquals(1, rows.size)
    assertEquals("bob", rows.head("name"))
  }

  @Test def selfJoinThroughOptimizerStillCorrect(): Unit = {
    // Self-join with the optimizer's name-based pruning would over-keep
    // colliding column names — but the RESULT must be correct.
    val p = tmpCsv("t.csv", "k,x\n1,10\n2,20\n3,30\n")
    val cat = new Catalog
    cat.register("t", CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT a.x AS ax, b.x AS bx FROM t a JOIN t b ON a.k = b.k ORDER BY a.k", cat)
    val rows = collectAllRows(q)
    assertEquals(3, rows.size)
    assertEquals(Seq(10, 20, 30), rows.map(_("ax")))
    assertEquals(Seq(10, 20, 30), rows.map(_("bx")))
  }

  @Test def orderByDescAndLimit(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n5,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT id, score FROM t ORDER BY score DESC LIMIT 3", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 4, 3), rows.map(_("id")))
    assertEquals(Seq(90, 75, 30), rows.map(_("score")))
  }

  @Test def distinct(): Unit = {
    val p = tmpCsv("a.csv", "cat\nA\nA\nB\nA\nB\nC\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT DISTINCT cat FROM t ORDER BY cat", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("A", "B", "C"), rows.map(_("cat")))
  }

  @Test def havingFiltersGroups(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nA,2\nB,3\nB,4\nB,5\nC,10\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT cat, COUNT(*) AS n FROM t GROUP BY cat HAVING COUNT(*) > 1", cat)
    val rows = collectAllRows(q).sortBy(_("cat").toString)
    assertEquals(2, rows.size)
    assertEquals(Seq("A", "B"), rows.map(_("cat")))
  }

  @Test def likePredicate(): Unit = {
    val p = tmpCsv("a.csv", "name\nalice\nbob\ncharlie\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT name FROM t WHERE name LIKE 'a%' OR name LIKE '%lie'", cat)
    val rows = collectAllRows(q).map(_("name")).toSet
    assertEquals(Set("alice", "charlie"), rows)
  }

  @Test def isNullPredicate(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n2,\n3,charlie\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT id FROM t WHERE name IS NULL", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(2, rows.head("id"))
  }

  @Test def scalarFunctions(): Unit = {
    val p = tmpCsv("a.csv", "s\nhello\nWorld\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT UPPER(s) AS u, LENGTH(s) AS n FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("HELLO", "WORLD"), rows.map(_("u")))
    assertEquals(Seq(5, 5), rows.map(_("n")))
  }

  @Test def emptyAggregationReturnsOneRow(): Unit = {
    val p = tmpCsv("a.csv", "x\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(inferSchema = false,
      columns = Some(Seq(Field("x", DataType.IntType))))))
    val q = SqlEngine.execute("SELECT COUNT(*) AS c, SUM(x) AS s FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(0L, rows.head("c"))
    assertNull(rows.head("s"))
  }

  @Test def countIfWithoutGroupBy(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n3\n4\n5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT COUNT_IF(x > 2) AS c FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(3L, rows.head("c"))
  }

  @Test def countIfGroupedAndIgnoresNulls(): Unit = {
    val p = tmpCsv("a.csv", "cat,x\nA,1\nA,\nA,5\nB,2\nB,4\nB,6\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, COUNT_IF(x > 3) AS hi, COUNT_IF(x IS NULL) AS n FROM t GROUP BY cat",
      cat)
    val rows = collectAllRows(q).sortBy(_("cat").toString)
    assertEquals(2, rows.size)
    assertEquals("A", rows(0)("cat"))
    assertEquals(1L, rows(0)("hi"))
    assertEquals(1L, rows(0)("n"))
    assertEquals("B", rows(1)("cat"))
    assertEquals(2L, rows(1)("hi"))
    assertEquals(0L, rows(1)("n"))
  }

  @Test def countIfEmptyInputReturnsZero(): Unit = {
    val p = tmpCsv("a.csv", "x\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(inferSchema = false,
      columns = Some(Seq(Field("x", DataType.IntType))))))
    val q = SqlEngine.execute("SELECT COUNT_IF(x > 0) AS c FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(0L, rows.head("c"))
  }

  @Test def countIfAsWindowAggregate(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,10\nA,40\nA,60\nB,5\nB,80\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, score, COUNT_IF(score >= 30) OVER (PARTITION BY cat) AS hi FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(r => (r("cat").toString, r("score").asInstanceOf[Int]))
    assertEquals(Seq(2L, 2L, 2L, 1L, 1L), rows.map(_("hi")))
  }

  @Test def countIfLowercaseIsNotNull(): Unit = {
    val p = tmpCsv("a.csv", "prev_order_for_product\n1\n\n2\n\n3\n")
    val cat = catalogWith("raw_test" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT count_if(prev_order_for_product is not null) FROM raw_test LIMIT 100",
      cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(3L, rows.head(q.schema.fieldNames.head))
  }

  // ---------------------------------------------------------------------------
  // Window functions
  // ---------------------------------------------------------------------------

  @Test def rowNumberPartitionedAndOrdered(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,10\nA,30\nA,20\nB,50\nB,40\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, score, ROW_NUMBER() OVER (PARTITION BY cat ORDER BY score DESC) AS rn FROM t",
      cat)
    assertEquals(Vector("cat", "score", "rn"), q.schema.fieldNames)
    val rows = collectAllRows(q).sortBy(r => (r("cat").toString, -r("score").asInstanceOf[Int]))
    assertEquals(Seq(1L, 2L, 3L, 1L, 2L), rows.map(_("rn")))
    assertEquals(Seq("A", "A", "A", "B", "B"), rows.map(_("cat")))
    assertEquals(Seq(30, 20, 10, 50, 40), rows.map(_("score")))
  }

  @Test def rankWithTies(): Unit = {
    val p = tmpCsv("a.csv", "score\n100\n90\n90\n80\n70\n70\n70\n60\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT score, RANK() OVER (ORDER BY score DESC) AS r, DENSE_RANK() OVER (ORDER BY score DESC) AS dr FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(r => (-r("score").asInstanceOf[Int], r("r").asInstanceOf[Long]))
    assertEquals(Seq(1L, 2L, 2L, 4L, 5L, 5L, 5L, 8L), rows.map(_("r")))
    assertEquals(Seq(1L, 2L, 2L, 3L, 4L, 4L, 4L, 5L), rows.map(_("dr")))
  }

  @Test def lagAndLeadWithDefault(): Unit = {
    val p = tmpCsv("a.csv", "id,val\n1,10\n2,20\n3,30\n4,40\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id, val, " +
        "LAG(val) OVER (ORDER BY id) AS prev, " +
        "LEAD(val) OVER (ORDER BY id) AS nxt, " +
        "LAG(val, 2, -1) OVER (ORDER BY id) AS prev2 " +
        "FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(_("id").asInstanceOf[Int])
    assertEquals(Seq(null, 10, 20, 30), rows.map(_("prev")))
    assertEquals(Seq(20, 30, 40, null), rows.map(_("nxt")))
    assertEquals(Seq(-1, -1, 10, 20), rows.map(_("prev2")))
  }

  @Test def runningSumWithOrderBy(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,20\n3,30\n4,40\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id, SUM(score) OVER (ORDER BY id) AS running FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(_("id").asInstanceOf[Int])
    assertEquals(Seq(10L, 30L, 60L, 100L), rows.map(_("running")))
  }

  @Test def aggregateOverEntirePartition(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,10\nA,20\nA,30\nB,100\nB,200\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, score, " +
        "SUM(score) OVER (PARTITION BY cat) AS s, " +
        "AVG(score) OVER (PARTITION BY cat) AS a, " +
        "COUNT(*) OVER (PARTITION BY cat) AS c " +
        "FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(r => (r("cat").toString, r("score").asInstanceOf[Int]))
    assertEquals(Seq(60L, 60L, 60L, 300L, 300L), rows.map(_("s")))
    assertEquals(Seq(20.0, 20.0, 20.0, 150.0, 150.0), rows.map(_("a").asInstanceOf[Double]))
    assertEquals(Seq(3L, 3L, 3L, 2L, 2L), rows.map(_("c")))
  }

  @Test def windowWithoutPartitionByCoversWholeInput(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n3\n4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT x, SUM(x) OVER () AS total FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(4, rows.size)
    rows.foreach(r => assertEquals(10L, r("total")))
  }

  @Test def rowsBetweenSlidingFrame(): Unit = {
    val p = tmpCsv("a.csv", "id,val\n1,1\n2,2\n3,3\n4,4\n5,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id, SUM(val) OVER (ORDER BY id ROWS BETWEEN 1 PRECEDING AND 1 FOLLOWING) AS s FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(_("id").asInstanceOf[Int])
    // Frame: [-1, +1] inclusive. Row 1: 1+2=3. Row 2: 1+2+3=6. Row 3: 2+3+4=9. Row 4: 3+4+5=12. Row 5: 4+5=9.
    assertEquals(Seq(3L, 6L, 9L, 12L, 9L), rows.map(_("s")))
  }

  @Test def orderByReferencesWindowExpression(): Unit = {
    val p = tmpCsv("a.csv", "name,score\nalice,90\nbob,70\ncarol,80\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT name FROM t ORDER BY ROW_NUMBER() OVER (ORDER BY score DESC)",
      cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("alice", "carol", "bob"), rows.map(_("name")))
  }

  @Test def windowFunctionInExpression(): Unit = {
    val p = tmpCsv("a.csv", "id,val\n1,10\n2,20\n3,30\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id, val - LAG(val) OVER (ORDER BY id) AS delta FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(_("id").asInstanceOf[Int])
    assertEquals(Seq(null, 10, 10), rows.map(_("delta")))
  }

  @Test def windowFunctionAfterGroupBy(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nA,2\nB,3\nB,4\nB,5\nC,1\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, COUNT(*) AS n, RANK() OVER (ORDER BY COUNT(*) DESC) AS r FROM t GROUP BY cat",
      cat)
    val rows = collectAllRows(q).sortBy(_("r").asInstanceOf[Long])
    // B has 3 (rank 1), A has 2 (rank 2), C has 1 (rank 3).
    assertEquals(Seq("B", "A", "C"), rows.map(_("cat")))
    assertEquals(Seq(3L, 2L, 1L), rows.map(_("n")))
    assertEquals(Seq(1L, 2L, 3L), rows.map(_("r")))
  }

  @Test def windowFunctionInNonWindowPositionThrows(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try {
      SqlEngine.execute("SELECT x FROM t WHERE ROW_NUMBER() OVER (ORDER BY x) = 1", cat)
      null
    } catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage.toLowerCase.contains("window function"))
  }

  // ---- COUNT(*) metadata fast path ----

  /** Stand-in for a "knows its own count" view (e.g. parquet). `readPartition`
    * throws so the test fails loudly if the planner regresses and falls back
    * to the per-row aggregate path.
    */
  private final class MetadataOnlyView(val n: Long) extends CatalogView {
    val schema: Schema = Schema(Vector(Field("x", DataType.IntType)))
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] =
      throw new AssertionError("planner should have short-circuited to metadata; no scan expected")
    override val exactRowCount: Option[Long] = Some(n)
  }

  @Test def countStarUsesExactRowCountWithoutScanning(): Unit = {
    val cat = catalogWith("t" -> new MetadataOnlyView(123456L))
    val q = SqlEngine.execute("SELECT COUNT(*) AS n FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(123456L, rows.head("n"))
  }

  @Test def countStarMetadataPathWorksWithoutAlias(): Unit = {
    val cat = catalogWith("t" -> new MetadataOnlyView(7L))
    val q = SqlEngine.execute("SELECT COUNT(*) FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    assertEquals(7L, rows.head(q.schema.fieldNames.head))
  }

  @Test def countStarWithWhereStillScans(): Unit = {
    // Filter requires actually reading rows — the fast path must not engage.
    val p = tmpCsv("a.csv", "x\n1\n2\n3\n4\n5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT COUNT(*) AS n FROM t WHERE x > 2", cat)
    assertEquals(3L, collectAllRows(q).head("n"))
  }

  @Test def countStarWithGroupByStillScans(): Unit = {
    val p = tmpCsv("a.csv", "cat\nA\nA\nB\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT cat, COUNT(*) AS n FROM t GROUP BY cat", cat)
    val rows = collectAllRows(q).sortBy(_("cat").toString)
    assertEquals(Seq(("A", 2L), ("B", 1L)), rows.map(r => (r("cat"), r("n"))))
  }

  // ---- Column projection push-down ----

  /** Tracks which columns the SQL engine asked the view to project. `readPartition`
    * decodes only those (asserted via the projected schema), and the test inspects
    * `projectedTo` to verify push-down happened.
    */
  private final class TrackingProjectableView(
      val fullSchema: Schema,
      rowsByCol: Map[String, Array[Any]]
  ) extends CatalogView {
    val schema: Schema = fullSchema
    def numPartitions: Int = 1
    @volatile var projectedTo: Option[Seq[String]] = None
    def readPartition(p: Int): Iterator[ColumnarBatch] = readWith(fullSchema)
    private def readWith(s: Schema): Iterator[ColumnarBatch] = {
      val n = rowsByCol.values.head.length
      val batch = new ColumnarBatch(s, math.max(1, n))
      var c = 0
      while (c < s.length) {
        val name = s.fields(c).name
        val data = rowsByCol(name)
        var r = 0
        while (r < n) { batch.column(c).setBoxed(r, data(r)); r += 1 }
        c += 1
      }
      batch.setNumRows(n)
      Iterator.single(batch)
    }
    override def withProjectedColumns(names: Seq[String]): Option[CatalogView] = {
      val keep = names.toSet
      val pruned = Schema(fullSchema.fields.filter(f => keep.contains(f.name)))
      projectedTo = Some(pruned.fieldNames)
      Some(new CatalogView {
        val schema: Schema = pruned
        def numPartitions: Int = 1
        def readPartition(p: Int): Iterator[ColumnarBatch] = readWith(pruned)
      })
    }
  }

  @Test def projectionPushdownDropsUnusedColumns(): Unit = {
    val schema = Schema(Vector(
      Field("a", DataType.IntType),
      Field("big_blob", DataType.StringType),
      Field("b", DataType.IntType)
    ))
    val view = new TrackingProjectableView(schema, Map(
      "a" -> Array[Any](1, 2, 3),
      "big_blob" -> Array[Any]("x", "y", "z"),
      "b" -> Array[Any](10, 20, 30)
    ))
    val cat = catalogWith("t" -> view)
    val q = SqlEngine.execute("SELECT a + b AS s FROM t ORDER BY a", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(11, 22, 33), rows.map(_("s")))
    assertEquals(Some(Seq("a", "b")), view.projectedTo)
  }

  @Test def projectionPushdownThroughAggregate(): Unit = {
    val schema = Schema(Vector(
      Field("cat", DataType.StringType),
      Field("score", DataType.IntType),
      Field("ignored", DataType.StringType)
    ))
    val view = new TrackingProjectableView(schema, Map(
      "cat" -> Array[Any]("A", "A", "B"),
      "score" -> Array[Any](1, 2, 3),
      "ignored" -> Array[Any]("x", "y", "z")
    ))
    val cat = catalogWith("t" -> view)
    val q = SqlEngine.execute(
      "SELECT cat, SUM(score) AS s FROM t GROUP BY cat", cat)
    val rows = collectAllRows(q).sortBy(_("cat").toString)
    assertEquals(Seq(("A", 3L), ("B", 3L)), rows.map(r => (r("cat"), r("s"))))
    assertEquals(Some(Seq("cat", "score")), view.projectedTo)
  }

  @Test def projectionPushdownAllColumnsUsedSkipsRewrite(): Unit = {
    val schema = Schema(Vector(
      Field("a", DataType.IntType),
      Field("b", DataType.IntType)
    ))
    val view = new TrackingProjectableView(schema, Map(
      "a" -> Array[Any](1, 2),
      "b" -> Array[Any](3, 4)
    ))
    val cat = catalogWith("t" -> view)
    SqlEngine.execute("SELECT a, b FROM t", cat).batches.foreach(_ => ())
    // Full schema used → planner shouldn't bother asking for a projection.
    assertEquals(None, view.projectedTo)
  }

  // ---- UNION / UNION ALL ----

  @Test def unionAllConcatenatesRows(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n2,bob\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT * FROM t UNION ALL SELECT * FROM t", cat)
    assertEquals(Vector("id", "name"), q.schema.fieldNames)
    val rows = collectAllRows(q)
    assertEquals(4, rows.size)
    assertEquals(Seq(1, 2, 1, 2), rows.map(_("id")))
  }

  @Test def unionDedupsDuplicateRows(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n2,bob\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT * FROM t UNION SELECT * FROM t", cat)
    val rows = collectAllRows(q).sortBy(_("id").asInstanceOf[Int])
    assertEquals(2, rows.size)
    assertEquals(Seq(1, 2), rows.map(_("id")))
  }

  @Test def unionAllChainsThreeArms(): Unit = {
    val a = tmpCsv("a.csv", "id\n1\n")
    val b = tmpCsv("b.csv", "id\n2\n")
    val c = tmpCsv("c.csv", "id\n3\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    cat.register("c", CsvReader.fromPath(c.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id FROM a UNION ALL SELECT id FROM b UNION ALL SELECT id FROM c", cat)
    assertEquals(Seq(1, 2, 3), collectAllRows(q).map(_("id")))
  }

  @Test def unionMismatchedColumnCountRejected(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try {
      SqlEngine.execute("SELECT * FROM t UNION ALL SELECT id FROM t", cat)
      null
    } catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage.toLowerCase.contains("column count"))
  }

  @Test def intersectRejected(): Unit = {
    val p = tmpCsv("a.csv", "id\n1\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try {
      SqlEngine.execute("SELECT id FROM t INTERSECT SELECT id FROM t", cat)
      null
    } catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage.toUpperCase.contains("UNION"))
  }

  @Test def unionAllOrderByOnUnionOutput(): Unit = {
    val a = tmpCsv("a.csv", "id\n2\n4\n")
    val b = tmpCsv("b.csv", "id\n1\n3\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id FROM a UNION ALL SELECT id FROM b ORDER BY id DESC", cat)
    assertEquals(Seq(4, 3, 2, 1), collectAllRows(q).map(_("id")))
  }

  @Test def unionAllLimit(): Unit = {
    val a = tmpCsv("a.csv", "id\n1\n2\n3\n")
    val b = tmpCsv("b.csv", "id\n4\n5\n6\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    // JSqlParser parks the trailing `LIMIT 4` on the last arm; we hoist it to
    // the union to match Postgres-style semantics. Result: 4 rows across both
    // arms in arrival order (a's three rows first, then b's first row).
    val q = SqlEngine.execute(
      "SELECT id FROM a UNION ALL SELECT id FROM b LIMIT 4", cat)
    val rows = collectAllRows(q).map(_("id"))
    assertEquals(Seq(1, 2, 3, 4), rows)
  }

  @Test def unionAllPerArmLimitWithParens(): Unit = {
    // Parens around the last arm should keep the LIMIT per-arm, not hoist it.
    val a = tmpCsv("a.csv", "id\n1\n2\n3\n")
    val b = tmpCsv("b.csv", "id\n4\n5\n6\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id FROM a UNION ALL (SELECT id FROM b LIMIT 2)", cat)
    val rows = collectAllRows(q).map(_("id"))
    assertEquals(Seq(1, 2, 3, 4, 5), rows)
  }

  @Test def unionAllOrderByLimitTogether(): Unit = {
    val a = tmpCsv("a.csv", "id\n5\n1\n")
    val b = tmpCsv("b.csv", "id\n4\n2\n3\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT id FROM a UNION ALL SELECT id FROM b ORDER BY id ASC LIMIT 3", cat)
    assertEquals(Seq(1, 2, 3), collectAllRows(q).map(_("id")))
  }

  @Test def unionOrderByUsesLeftArmColumnName(): Unit = {
    // UNION output schema comes from the leftmost arm — so ORDER BY references
    // the left arm's column name even if the right arm aliases differently.
    val a = tmpCsv("a.csv", "x\n3\n1\n")
    val b = tmpCsv("b.csv", "y\n2\n4\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT x FROM a UNION ALL SELECT y FROM b ORDER BY x ASC", cat)
    assertEquals(Vector("x"), q.schema.fieldNames)
    assertEquals(Seq(1, 2, 3, 4), collectAllRows(q).map(_("x")))
  }

  @Test def projectionPushdownPicksNarrowestColumnWhenNoneReferenced(): Unit = {
    // COUNT(1) doesn't reference any column, but the scan still has to drive
    // batches forward to feed the per-row count. Without push-down it would
    // decode every column — including the multi-MB string blob — to produce
    // a single Long. We should ask the view for its narrowest column instead.
    val schema = Schema(Vector(
      Field("big_blob", DataType.StringType),
      Field("ts", DataType.LongType),
      Field("flag", DataType.BooleanType)
    ))
    val view = new TrackingProjectableView(schema, Map(
      "big_blob" -> Array[Any]("aaa", "bbb", "ccc", "ddd"),
      "ts" -> Array[Any](1L, 2L, 3L, 4L),
      "flag" -> Array[Any](true, false, true, false)
    ))
    val cat = catalogWith("t" -> view)
    val q = SqlEngine.execute("SELECT COUNT(1) AS n FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(4L, rows.head("n"))
    assertEquals(Some(Seq("flag")), view.projectedTo)
  }

  @Test def projectionPushdownNarrowestSkipsStringWhenPrimitiveAvailable(): Unit = {
    // Tie-break preference: fixed-width primitives over variable-width strings,
    // regardless of column order in the source schema.
    val schema = Schema(Vector(
      Field("blob", DataType.StringType),
      Field("id", DataType.IntType)
    ))
    val view = new TrackingProjectableView(schema, Map(
      "blob" -> Array[Any]("x", "y"),
      "id"   -> Array[Any](1, 2)
    ))
    val cat = catalogWith("t" -> view)
    SqlEngine.execute("SELECT COUNT(1) FROM t", cat).batches.foreach(_ => ())
    assertEquals(Some(Seq("id")), view.projectedTo)
  }

  @Test def projectionPushdownNarrowestForLiteralProject(): Unit = {
    // `SELECT 1 FROM t LIMIT 2` evaluates a literal per row — no column refs
    // anywhere. Same narrowest-column push-down should apply.
    val schema = Schema(Vector(
      Field("payload", DataType.StringType),
      Field("n", DataType.IntType)
    ))
    val view = new TrackingProjectableView(schema, Map(
      "payload" -> Array[Any]("aaa", "bbb", "ccc"),
      "n" -> Array[Any](1, 2, 3)
    ))
    val cat = catalogWith("t" -> view)
    val q = SqlEngine.execute("SELECT 1 AS one FROM t LIMIT 2", cat)
    val rows = collectAllRows(q)
    assertEquals(2, rows.size)
    assertEquals(Seq(1, 1), rows.map(_("one")))
    assertEquals(Some(Seq("n")), view.projectedTo)
  }

  // ---------------------------------------------------------------------------
  // Math scalar functions
  // ---------------------------------------------------------------------------

  private val DoubleEps = 1e-9

  @Test def mathLogarithmsAndExp(): Unit = {
    val p = tmpCsv("a.csv", "x\n1.0\n2.71828182845904523536\n10.0\n8.0\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT LN(x) AS ln, LOG(x) AS log1, LOG(2, x) AS log2_, " +
        "LOG10(x) AS l10, LOG2(x) AS l2, EXP(0) AS e0, EXP(1) AS e1 FROM t",
      cat)
    val rows = collectAllRows(q)
    assertEquals(4, rows.size)
    // LN(1) == 0; LOG(1) == LN(1) for our MySQL-style LOG(x)
    assertEquals(0.0, rows(0)("ln").asInstanceOf[Double], DoubleEps)
    assertEquals(0.0, rows(0)("log1").asInstanceOf[Double], DoubleEps)
    // LN(e) ≈ 1
    assertEquals(1.0, rows(1)("ln").asInstanceOf[Double], 1e-6)
    // LOG10(10) == 1, LOG2(8) == 3
    assertEquals(1.0, rows(2)("l10").asInstanceOf[Double], DoubleEps)
    assertEquals(3.0, rows(3)("l2").asInstanceOf[Double], DoubleEps)
    // LOG(2, 8) == 3
    assertEquals(3.0, rows(3)("log2_").asInstanceOf[Double], DoubleEps)
    // EXP(0) == 1, EXP(1) ≈ e
    assertEquals(1.0, rows(0)("e0").asInstanceOf[Double], DoubleEps)
    assertEquals(math.E, rows(0)("e1").asInstanceOf[Double], 1e-12)
  }

  @Test def mathSqrtCbrtAndSign(): Unit = {
    val p = tmpCsv("a.csv", "x\n9.0\n-27.0\n0.0\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT SQRT(ABS(x)) AS s, CBRT(x) AS c, SIGN(x) AS sg FROM t",
      cat)
    val rows = collectAllRows(q)
    assertEquals(3.0, rows(0)("s").asInstanceOf[Double], DoubleEps)
    assertEquals(-3.0, rows(1)("c").asInstanceOf[Double], DoubleEps)
    assertEquals(1, rows(0)("sg"))
    assertEquals(-1, rows(1)("sg"))
    assertEquals(0, rows(2)("sg"))
  }

  @Test def mathTrigAndConversions(): Unit = {
    val p = tmpCsv("a.csv", "x\n0\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT SIN(0) AS s0, COS(0) AS c0, TAN(0) AS t0, " +
        "ATAN2(1, 1) AS a, DEGREES(PI()) AS deg, RADIANS(180) AS rad, " +
        "PI() AS pi, E() AS e FROM t",
      cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    val r = rows.head
    assertEquals(0.0, r("s0").asInstanceOf[Double], DoubleEps)
    assertEquals(1.0, r("c0").asInstanceOf[Double], DoubleEps)
    assertEquals(0.0, r("t0").asInstanceOf[Double], DoubleEps)
    assertEquals(math.Pi / 4, r("a").asInstanceOf[Double], DoubleEps)
    assertEquals(180.0, r("deg").asInstanceOf[Double], 1e-9)
    assertEquals(math.Pi, r("rad").asInstanceOf[Double], 1e-9)
    assertEquals(math.Pi, r("pi").asInstanceOf[Double], DoubleEps)
    assertEquals(math.E, r("e").asInstanceOf[Double], DoubleEps)
  }

  @Test def mathTruncTwoArg(): Unit = {
    val p = tmpCsv("a.csv", "x\n3.14159\n-3.789\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT TRUNC(x) AS t0, TRUNC(x, 2) AS t2 FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(3.0, rows(0)("t0").asInstanceOf[Double], DoubleEps)
    assertEquals(3.14, rows(0)("t2").asInstanceOf[Double], DoubleEps)
    assertEquals(-3.0, rows(1)("t0").asInstanceOf[Double], DoubleEps)
    assertEquals(-3.78, rows(1)("t2").asInstanceOf[Double], DoubleEps)
  }

  @Test def mathGreatestLeastSkipsNulls(): Unit = {
    val p = tmpCsv("a.csv", "a,b,c\n1,5,3\n,7,2\n,,\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT GREATEST(a, b, c) AS g, LEAST(a, b, c) AS l FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(5, rows(0)("g"))
    assertEquals(1, rows(0)("l"))
    assertEquals(7, rows(1)("g"))
    assertEquals(2, rows(1)("l"))
    assertNull(rows(2)("g"))
    assertNull(rows(2)("l"))
  }

  @Test def mathNullInputsReturnNull(): Unit = {
    val p = tmpCsv("a.csv", "id,x\n1,\n2,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(inferSchema = false,
      columns = Some(Seq(Field("id", DataType.IntType), Field("x", DataType.DoubleType))))))
    val q = SqlEngine.execute(
      "SELECT id, LN(x) AS l, SQRT(x) AS s, SIN(x) AS sn, SIGN(x) AS sg FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(_("id").asInstanceOf[Int])
    assertNull(rows(0)("l"))
    assertNull(rows(0)("s"))
    assertNull(rows(0)("sn"))
    assertNull(rows(0)("sg"))
    assertEquals(math.log(4.0), rows(1)("l").asInstanceOf[Double], DoubleEps)
    assertEquals(2.0, rows(1)("s").asInstanceOf[Double], DoubleEps)
  }

  @Test def randIsBoundedDouble(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n3\n4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT RAND() AS r, RAND(42) AS rs FROM t", cat)
    val rows = collectAllRows(q)
    assertEquals(4, rows.size)
    rows.foreach { r =>
      val v = r("r").asInstanceOf[Double]
      assertTrue(s"RAND() not in [0,1): $v", v >= 0.0 && v < 1.0)
      val s = r("rs").asInstanceOf[Double]
      assertTrue(s"RAND(42) not in [0,1): $s", s >= 0.0 && s < 1.0)
    }
    // Seed should be deterministic across rows in a batch.
    assertEquals(rows.head("rs"), rows.last("rs"))
  }

  // ---------------------------------------------------------------------------
  // Statistical aggregates
  // ---------------------------------------------------------------------------

  @Test def stddevAndVarianceSampleAndPop(): Unit = {
    val p = tmpCsv("a.csv", "x\n2\n4\n4\n4\n5\n5\n7\n9\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT VAR_POP(x) AS vp, VAR_SAMP(x) AS vs, " +
        "STDDEV_POP(x) AS sp, STDDEV_SAMP(x) AS ss, " +
        "VARIANCE(x) AS ve, STDDEV(x) AS sde FROM t",
      cat)
    val rows = collectAllRows(q)
    assertEquals(1, rows.size)
    // Reference values: mean=5, sum sq dev = 32, n=8.
    // pop variance = 32/8 = 4 ; sample variance = 32/7 ≈ 4.5714286
    assertEquals(4.0, rows.head("vp").asInstanceOf[Double], 1e-9)
    assertEquals(32.0 / 7.0, rows.head("vs").asInstanceOf[Double], 1e-9)
    assertEquals(2.0, rows.head("sp").asInstanceOf[Double], 1e-9)
    assertEquals(math.sqrt(32.0 / 7.0), rows.head("ss").asInstanceOf[Double], 1e-9)
    // VARIANCE / STDDEV default to sample.
    assertEquals(32.0 / 7.0, rows.head("ve").asInstanceOf[Double], 1e-9)
    assertEquals(math.sqrt(32.0 / 7.0), rows.head("sde").asInstanceOf[Double], 1e-9)
  }

  @Test def stddevIgnoresNullsAndSingleRowSampleIsNull(): Unit = {
    val p = tmpCsv("a.csv", "id,x\n1,10\n2,\n3,10\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(inferSchema = false,
      columns = Some(Seq(Field("id", DataType.IntType), Field("x", DataType.IntType))))))
    val q = SqlEngine.execute(
      "SELECT STDDEV_SAMP(x) AS ss, STDDEV_POP(x) AS sp, " +
        "VAR_SAMP(x) AS vs, VAR_POP(x) AS vp, COUNT(x) AS c FROM t",
      cat)
    val rows = collectAllRows(q)
    // Two equal non-null values (NULL row skipped) → variance 0; count 2.
    assertEquals(2L, rows.head("c"))
    assertEquals(0.0, rows.head("ss").asInstanceOf[Double], 1e-12)
    assertEquals(0.0, rows.head("sp").asInstanceOf[Double], 1e-12)
    assertEquals(0.0, rows.head("vs").asInstanceOf[Double], 1e-12)
    assertEquals(0.0, rows.head("vp").asInstanceOf[Double], 1e-12)

    val p2 = tmpCsv("b.csv", "id,x\n1,42\n")
    val cat2 = catalogWith("t" -> CsvReader.fromPath(p2.toString, CsvOptions(inferSchema = false,
      columns = Some(Seq(Field("id", DataType.IntType), Field("x", DataType.IntType))))))
    val q2 = SqlEngine.execute("SELECT STDDEV_SAMP(x) AS ss, STDDEV_POP(x) AS sp FROM t", cat2)
    val r2 = collectAllRows(q2).head
    // Sample stddev requires n >= 2.
    assertNull(r2("ss"))
    assertEquals(0.0, r2("sp").asInstanceOf[Double], 1e-12)
  }

  @Test def stddevWithGroupBy(): Unit = {
    val p = tmpCsv("a.csv", "cat,x\nA,1\nA,3\nA,5\nB,10\nB,20\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, STDDEV_POP(x) AS sp, VAR_POP(x) AS vp FROM t GROUP BY cat",
      cat)
    val rows = collectAllRows(q).sortBy(_("cat").toString)
    // A: values 1,3,5 → mean 3, m2 = 8, varpop = 8/3
    assertEquals(8.0 / 3.0, rows(0)("vp").asInstanceOf[Double], 1e-9)
    assertEquals(math.sqrt(8.0 / 3.0), rows(0)("sp").asInstanceOf[Double], 1e-9)
    // B: values 10,20 → mean 15, m2 = 50, varpop = 25
    assertEquals(25.0, rows(1)("vp").asInstanceOf[Double], 1e-9)
    assertEquals(5.0, rows(1)("sp").asInstanceOf[Double], 1e-9)
  }

  @Test def covarianceAndCorrelation(): Unit = {
    val p = tmpCsv("a.csv", "x,y\n1,2\n2,4\n3,6\n4,8\n5,10\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT COVAR_POP(x, y) AS cp, COVAR_SAMP(x, y) AS cs, CORR(x, y) AS r FROM t",
      cat)
    val rows = collectAllRows(q)
    // y = 2x. mean(x)=3, mean(y)=6. dev products: (-2*-4)+(-1*-2)+0+(1*2)+(2*4) = 8+2+0+2+8 = 20.
    // pop covariance = 20/5 = 4. sample = 20/4 = 5. CORR = 1 (perfect linear).
    assertEquals(4.0, rows.head("cp").asInstanceOf[Double], 1e-9)
    assertEquals(5.0, rows.head("cs").asInstanceOf[Double], 1e-9)
    assertEquals(1.0, rows.head("r").asInstanceOf[Double], 1e-9)
  }

  @Test def correlationConstantSeriesIsNull(): Unit = {
    // CORR is undefined when either series has zero variance.
    val p = tmpCsv("a.csv", "x,y\n1,5\n2,5\n3,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT CORR(x, y) AS r FROM t", cat)
    val rows = collectAllRows(q)
    assertNull(rows.head("r"))
  }

  @Test def stddevAsWindowAggregate(): Unit = {
    val p = tmpCsv("a.csv", "cat,x\nA,1\nA,3\nA,5\nB,10\nB,20\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, x, STDDEV_POP(x) OVER (PARTITION BY cat) AS sp FROM t",
      cat)
    val rows = collectAllRows(q).sortBy(r => (r("cat").toString, r("x").asInstanceOf[Int]))
    // A rows share the same partition stddev (sqrt(8/3)); B rows share 5.0.
    val a = math.sqrt(8.0 / 3.0)
    assertEquals(a, rows(0)("sp").asInstanceOf[Double], 1e-9)
    assertEquals(a, rows(1)("sp").asInstanceOf[Double], 1e-9)
    assertEquals(a, rows(2)("sp").asInstanceOf[Double], 1e-9)
    assertEquals(5.0, rows(3)("sp").asInstanceOf[Double], 1e-9)
    assertEquals(5.0, rows(4)("sp").asInstanceOf[Double], 1e-9)
  }

  // ---- KeyCodec coverage ---------------------------------------------------
  // Tests below stress every codec path the pipeline-breaking operators pick
  // — single-Long PackedBytes, multi-Long PackedBytes, ObjectArray with
  // strings, NULL keys grouping together, multi-column joins with NULLs,
  // wide DISTINCT.

  @Test def groupByLongOnlyExercisesPackedCodec(): Unit = {
    // Single Long key → PackedBytesCodec, fast encodeFromBatch path.
    val p = tmpCsv("a.csv", "k,v\n100,1\n200,2\n100,3\n200,4\n100,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT k, SUM(v) AS s, COUNT(*) AS n FROM t GROUP BY k", cat)
    val rows = collectAllRows(q).sortBy(_("k").asInstanceOf[Int])
    assertEquals(2, rows.size)
    assertEquals((100, 9L, 3L), (rows(0)("k"), rows(0)("s"), rows(0)("n")))
    assertEquals((200, 6L, 2L), (rows(1)("k"), rows(1)("s"), rows(1)("n")))
  }

  @Test def groupByThreeLongsExercisesPackedCodec(): Unit = {
    // 3-column packed key → PackedBytesCodec with 24 value bytes + 1 null byte.
    val p = tmpCsv("a.csv", "a,b,c,v\n1,1,1,10\n1,2,1,20\n1,1,1,30\n2,1,1,40\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT a, b, c, SUM(v) AS s FROM t GROUP BY a, b, c", cat)
    val rows = collectAllRows(q).sortBy(r =>
      (r("a").asInstanceOf[Int], r("b").asInstanceOf[Int], r("c").asInstanceOf[Int]))
    assertEquals(3, rows.size)
    assertEquals((1, 1, 1, 40L), (rows(0)("a"), rows(0)("b"), rows(0)("c"), rows(0)("s")))
    assertEquals((1, 2, 1, 20L), (rows(1)("a"), rows(1)("b"), rows(1)("c"), rows(1)("s")))
    assertEquals((2, 1, 1, 40L), (rows(2)("a"), rows(2)("b"), rows(2)("c"), rows(2)("s")))
  }

  @Test def groupByLongAndStringExercisesObjectArrayCodec(): Unit = {
    // Mixed key (String + Long) → ObjectArrayCodec.
    val p = tmpCsv("a.csv", "cat,k,v\nA,100,1\nB,100,2\nA,100,3\nA,200,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, k, SUM(v) AS s FROM t GROUP BY cat, k", cat)
    val rows = collectAllRows(q).sortBy(r => (r("cat").toString, r("k").asInstanceOf[Int]))
    assertEquals(3, rows.size)
    assertEquals(("A", 100, 4L), (rows(0)("cat"), rows(0)("k"), rows(0)("s")))
    assertEquals(("A", 200, 4L), (rows(1)("cat"), rows(1)("k"), rows(1)("s")))
    assertEquals(("B", 100, 2L), (rows(2)("cat"), rows(2)("k"), rows(2)("s")))
  }

  @Test def groupByNullKeysCollapseTogether(): Unit = {
    // SQL GROUP BY puts all NULLs in one bucket. The packed codec uses a null
    // bit per column; the object-array codec uses element == null.
    val p = tmpCsv("a.csv", "cat,v\nA,1\n,2\nA,3\n,4\nB,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT cat, SUM(v) AS s FROM t GROUP BY cat", cat)
    val rows = collectAllRows(q).sortBy(r => Option(r("cat")).map(_.toString).getOrElse("~"))
    assertEquals(3, rows.size)
    assertEquals("A", rows(0)("cat")); assertEquals(4L, rows(0)("s"))
    assertEquals("B", rows(1)("cat")); assertEquals(5L, rows(1)("s"))
    assertNull(rows(2)("cat"));        assertEquals(6L, rows(2)("s"))
  }

  @Test def groupByNullPackedKeysCollapseTogether(): Unit = {
    // Same property on the PackedBytesCodec path (numeric column with NULLs).
    val p = tmpCsv("a.csv", "k,v\n1,10\n,20\n1,30\n,40\n2,50\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(
      inferSchema = false,
      columns = Some(Seq(Field("k", DataType.LongType), Field("v", DataType.IntType))))))
    val q = SqlEngine.execute("SELECT k, SUM(v) AS s FROM t GROUP BY k", cat)
    val rows = collectAllRows(q)
    val nullRows = rows.filter(r => r("k") == null)
    val nonNullRows = rows.filter(r => r("k") != null).sortBy(_("k").asInstanceOf[Long])
    assertEquals(1, nullRows.size)
    assertEquals(60L, nullRows.head("s"))
    assertEquals(2, nonNullRows.size)
    assertEquals(40L, nonNullRows(0)("s")) // k=1: 10+30
    assertEquals(50L, nonNullRows(1)("s")) // k=2: 50
  }

  @Test def distinctOverWideMixedSchemaRoundTrips(): Unit = {
    // 5-column key mixing int, long, string, double, boolean → ObjectArrayCodec.
    // Two duplicate rows + two distinct rows ⇒ 3 output rows.
    val p = tmpCsv("a.csv",
      "i,l,s,d,b\n" +
      "1,100,alice,1.5,true\n" +
      "1,100,alice,1.5,true\n" +
      "2,200,bob,2.5,false\n" +
      "3,300,charlie,3.5,true\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT DISTINCT i, l, s, d, b FROM t", cat)
    val rows = collectAllRows(q).sortBy(_("i").asInstanceOf[Int])
    assertEquals(3, rows.size)
    assertEquals((1, 100, "alice", 1.5, true),
      (rows(0)("i"), rows(0)("l"), rows(0)("s"), rows(0)("d"), rows(0)("b")))
    assertEquals(2, rows(1)("i"))
    assertEquals("charlie", rows(2)("s"))
  }

  @Test def distinctOverFixedWidthOnlyExercisesPackedCodec(): Unit = {
    // No variable-width columns → PackedBytesCodec for the whole-row key.
    val p = tmpCsv("a.csv", "i,l,b\n1,100,true\n1,100,true\n2,200,false\n2,200,false\n3,300,true\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT DISTINCT i, l, b FROM t", cat)
    val rows = collectAllRows(q).sortBy(_("i").asInstanceOf[Int])
    assertEquals(3, rows.size)
    assertEquals((1, 100, true), (rows(0)("i"), rows(0)("l"), rows(0)("b")))
    assertEquals((2, 200, false), (rows(1)("i"), rows(1)("l"), rows(1)("b")))
    assertEquals((3, 300, true), (rows(2)("i"), rows(2)("l"), rows(2)("b")))
  }

  @Test def joinOnMultiColumnKeyHonorsNullSemantics(): Unit = {
    // Equi-join on (k1, k2). Rows where any key column is NULL must not match
    // anything — SQL three-valued logic. The PackedBytesCodec /
    // ObjectArrayCodec must round-trip the same way the old Seq[Any] keys did.
    val left = tmpCsv("l.csv",
      "k1,k2,name\n1,A,alice\n2,B,bob\n3,,charlie\n,A,danny\n")
    val right = tmpCsv("r.csv",
      "k1,k2,score\n1,A,100\n2,B,80\n3,A,50\n,A,99\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u JOIN s ON u.k1 = s.k1 AND u.k2 = s.k2 ORDER BY u.name", cat)
    val rows = collectAllRows(q)
    assertEquals(2, rows.size)
    assertEquals(Seq("alice", "bob"), rows.map(_("name")))
    assertEquals(Seq(100, 80), rows.map(_("score")))
  }

  @Test def joinOnTwoLongKeysHitsPackedCodec(): Unit = {
    // Both join columns are integers → PackedBytesCodec on the probe-side
    // encodeFromBatchSkipIfAnyNull fast path.
    val left = tmpCsv("l.csv", "a,b,n\n1,10,one\n2,20,two\n3,30,three\n")
    val right = tmpCsv("r.csv", "a,b,m\n1,10,X\n2,20,Y\n2,21,Z\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT n, m FROM u JOIN s ON u.a = s.a AND u.b = s.b ORDER BY n", cat)
    val rows = collectAllRows(q)
    assertEquals(2, rows.size)
    assertEquals(Seq("one", "two"), rows.map(_("n")))
    assertEquals(Seq("X", "Y"), rows.map(_("m")))
  }
}
