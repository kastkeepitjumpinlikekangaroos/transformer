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
}
