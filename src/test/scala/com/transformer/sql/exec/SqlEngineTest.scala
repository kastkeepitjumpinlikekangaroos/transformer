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

  @Test def orderByPositionalOrdinal(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n5,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // `ORDER BY 2 DESC` means "sort by the 2nd select item" (score), matching
    // BigQuery/Postgres semantics and `GROUP BY <ordinal>` above.
    val q = SqlEngine.execute("SELECT id, score FROM t ORDER BY 2 DESC", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 4, 3, 1, 5), rows.map(_("id")))
    assertEquals(Seq(90, 75, 30, 10, 5), rows.map(_("score")))
  }

  @Test def orderByOrdinalMixedWithColumnAndDirections(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nB,1\nA,2\nB,3\nA,1\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT cat, score FROM t ORDER BY 1 ASC, score DESC", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("A", "A", "B", "B"), rows.map(_("cat")))
    assertEquals(Seq(2, 1, 3, 1), rows.map(_("score")))
  }

  @Test def orderByOrdinalReferencingAggregate(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nA,2\nB,30\nB,4\nC,7\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Unlike GROUP BY, an ORDER BY position may point at an aggregate output.
    val q = SqlEngine.execute(
      "SELECT cat, SUM(score) AS s FROM t GROUP BY cat ORDER BY 2 DESC", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("B", "C", "A"), rows.map(_("cat")))
    assertEquals(Seq(34L, 7L, 3L), rows.map(_("s")))
  }

  @Test def orderByOrdinalReferencingGroupKey(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nC,2\nB,30\nB,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, COUNT(*) AS n FROM t GROUP BY cat ORDER BY 1 DESC", cat)
    assertEquals(Seq("C", "B", "A"), collectAllRows(q).map(_("cat")))
  }

  @Test def orderByOrdinalOverDistinct(): Unit = {
    val p = tmpCsv("a.csv", "a,b\n1,x\n2,y\n1,x\n3,z\n2,y\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // With DISTINCT the sort sits above the dedup and binds against the
    // projection, so the ordinal is a direct position in the output schema.
    val q = SqlEngine.execute("SELECT DISTINCT a, b FROM t ORDER BY 2 DESC", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("z", "y", "x"), rows.map(_("b")))
    assertEquals(Seq(3, 2, 1), rows.map(_("a")))
  }

  @Test def orderByOrdinalOverDistinctExpression(): Unit = {
    val p = tmpCsv("a.csv", "x\n3\n1\n3\n2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT DISTINCT x * 10 AS s FROM t ORDER BY 1", cat)
    assertEquals(Seq(10, 20, 30), collectAllRows(q).map(_("s")))
  }

  @Test def orderByOrdinalOverUnion(): Unit = {
    val a = tmpCsv("a.csv", "id,v\n2,b\n4,d\n")
    val b = tmpCsv("b.csv", "id,v\n1,a\n3,c\n")
    val cat = new Catalog
    cat.register("a", CsvReader.fromPath(a.toString, CsvOptions()))
    cat.register("b", CsvReader.fromPath(b.toString, CsvOptions()))
    // The union's ORDER BY binds against the union output, so the ordinal is a
    // position in that schema (names come from the leftmost arm).
    val q = SqlEngine.execute(
      "SELECT id, v FROM a UNION ALL SELECT id, v FROM b ORDER BY 1 DESC", cat)
    assertEquals(Seq(4, 3, 2, 1), collectAllRows(q).map(_("id")))
  }

  @Test def orderByOrdinalOverWindowColumn(): Unit = {
    val p = tmpCsv("a.csv", "name,score\na,10\nb,30\nc,20\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT name, ROW_NUMBER() OVER (ORDER BY score DESC) AS rn FROM t ORDER BY 2", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("b", "c", "a"), rows.map(_("name")))
    assertEquals(Seq(1L, 2L, 3L), rows.map(_("rn")))
  }

  @Test def orderByOrdinalOutOfRangeRejected(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try { SqlEngine.execute("SELECT x FROM t ORDER BY 5", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("ORDER BY position 5"))
  }

  @Test def orderByOrdinalZeroRejected(): Unit = {
    val p = tmpCsv("a.csv", "x\n1\n2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try { SqlEngine.execute("SELECT DISTINCT x FROM t ORDER BY 0", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("ORDER BY position 0"))
  }

  @Test def orderBySignedLiteralIsNotAnOrdinal(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n3,10\n1,90\n2,30\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Only a BARE integer literal is an ordinal; `+1` parses as a
    // SignedExpression and stays a constant sort key, matching GROUP BY and
    // BigQuery/Postgres/MySQL. Out-of-range bounds don't apply to it either.
    val q = SqlEngine.execute("SELECT id, score FROM t ORDER BY +9", cat)
    assertEquals(Seq(3, 1, 2), collectAllRows(q).map(_("id")))
  }

  @Test def orderByOrdinalReachesAliasThatNameLookupCannot(): Unit = {
    val p = tmpCsv("a.csv", "a,b\n1,5\n4,4\n2,1\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Without DISTINCT the sort sits below the projection, so a SELECT alias is
    // not in scope by name (see docs/gotchas.md). An ordinal is: it expands to
    // the select item's expression before binding, which is how you sort by a
    // computed column here.
    val named = try { SqlEngine.execute("SELECT a + b AS s FROM t ORDER BY s", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(named)
    assertTrue(named.getMessage, named.getMessage.contains("Unknown column"))

    val q = SqlEngine.execute("SELECT a + b AS s FROM t ORDER BY 1 DESC", cat)
    assertEquals(Seq(8, 6, 3), collectAllRows(q).map(_("s")))
  }

  @Test def orderByOrdinalOverWindowInsideArithmetic(): Unit = {
    val p = tmpCsv("a.csv", "id,val\n1,10\n2,40\n3,15\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // The ordinal expands to `val - LAG(val) OVER (...)`, which rebinds through
    // the same window resolver the projection uses.
    val q = SqlEngine.execute(
      "SELECT id, val - LAG(val, 1, 0) OVER (ORDER BY id) AS delta FROM t ORDER BY 2 DESC", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 1, 3), rows.map(_("id")))
    assertEquals(Seq(30, 10, -25), rows.map(_("delta")))
  }

  // --- Grouping parentheses -------------------------------------------------
  // JSqlParser parses `(expr)` as a single-element ParenthesedExpressionList,
  // so every AST walk in LogicalBuilder has to see through it. These pin the
  // shapes that reach a different walk: the binder, `childExpressions` (which
  // drives aggregate / window collection), the post-aggregation rebinder, alias
  // derivation, and ordinal recognition.

  @Test def groupingParenNestedInArithmetic(): Unit = {
    val p = tmpCsv("a.csv", "a,b,c\n2,3,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // The paren node is NOT at the top here — it is the Multiplication's left
    // operand, so the binder only reaches it by recursing.
    val q = SqlEngine.execute("SELECT (a + b) * c AS s FROM t", cat)
    assertEquals(Seq(20), collectAllRows(q).map(_("s")))
  }

  @Test def groupingParenChangesPrecedence(): Unit = {
    val p = tmpCsv("a.csv", "a,b,c\n2,3,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // The parens have to actually group: same operands, different answer.
    val parens = SqlEngine.execute("SELECT (a + b) * c AS s FROM t", cat)
    val bare = SqlEngine.execute("SELECT a + b * c AS s FROM t", cat)
    assertEquals(Seq(20), collectAllRows(parens).map(_("s")))
    assertEquals(Seq(14), collectAllRows(bare).map(_("s")))
  }

  @Test def groupingParenAtTopOfSelectItem(): Unit = {
    val p = tmpCsv("a.csv", "a\n1\n2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Parens are transparent to naming too — `(a)` names its output `a`, the
    // same as a bare `a` would, matching Postgres and DuckDB.
    val q = SqlEngine.execute("SELECT (a) FROM t", cat)
    assertEquals(Vector("a"), q.schema.fieldNames)
    assertEquals(Seq(1, 2), collectAllRows(q).map(_("a")))
  }

  @Test def groupingParenNestedTwice(): Unit = {
    val p = tmpCsv("a.csv", "a,b\n2,3\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT ((a + b)) AS s FROM t", cat)
    assertEquals(Seq(5), collectAllRows(q).map(_("s")))
  }

  @Test def groupingParenInWhereAndJoinOn(): Unit = {
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,carol\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n2,50\n3,99\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name FROM u JOIN s ON (u.id = s.user_id) WHERE (s.score > 60) ORDER BY u.id", cat)
    assertEquals(Seq("alice", "carol"), collectAllRows(q).map(_("name")))
  }

  @Test def groupingParenAroundBareAggregate(): Unit = {
    val p = tmpCsv("a.csv", "x\n10\n20\n30\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Reaches `childExpressions`: without a paren case there the aggregate is
    // never collected and the binder reports "used in non-aggregating position".
    val q = SqlEngine.execute("SELECT (SUM(x)) AS s FROM t", cat)
    assertEquals(Seq(60L), collectAllRows(q).map(_("s")))
  }

  @Test def groupingParenAroundAggregateInArithmetic(): Unit = {
    val p = tmpCsv("a.csv", "a,b\n10,1\n10,2\n20,5\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT (a + SUM(b)) AS s FROM t GROUP BY a ORDER BY a", cat)
    assertEquals(Seq(13L, 25L), collectAllRows(q).map(_("s")))
  }

  @Test def groupingParenAroundGroupKeyExpression(): Unit = {
    val p = tmpCsv("a.csv", "a,b\n1,2\n1,2\n3,4\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // The post-aggregation rebinder matches a projection to a group key by
    // canonical text, so it has to strip the paren before looking `a + b` up.
    val q = SqlEngine.execute("SELECT (a + b) AS s FROM t GROUP BY a + b ORDER BY 1", cat)
    assertEquals(Seq(3, 7), collectAllRows(q).map(_("s")))
  }

  @Test def groupingParenAroundWindowFunction(): Unit = {
    val p = tmpCsv("a.csv", "name,score\na,10\nb,30\nc,20\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Reaches `collectWindows`, the third walk over `childExpressions`.
    val q = SqlEngine.execute(
      "SELECT name, (ROW_NUMBER() OVER (ORDER BY score DESC)) AS rn FROM t ORDER BY name", cat)
    assertEquals(Seq(3L, 1L, 2L), collectAllRows(q).map(_("rn")))
  }

  @Test def groupingParenInHaving(): Unit = {
    val p = tmpCsv("a.csv", "cat\nA\nA\nB\nC\nC\nC\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT cat, COUNT(*) AS n FROM t GROUP BY cat HAVING (COUNT(*) > 1) ORDER BY cat", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("A", "C"), rows.map(_("cat")))
    assertEquals(Seq(2L, 3L), rows.map(_("n")))
  }

  @Test def orderByParenthesizedOrdinal(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Postgres and MySQL both drop a redundant paren before the positional
    // check, so `(2)` is position 2, not a constant sort key that silently
    // leaves scan order in place.
    val q = SqlEngine.execute("SELECT id, score FROM t ORDER BY (2) DESC", cat)
    assertEquals(Seq(2, 3, 1), collectAllRows(q).map(_("id")))

    val ex = try { SqlEngine.execute("SELECT id, score FROM t ORDER BY (5)", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("ORDER BY position 5"))
  }

  @Test def rowValueConstructorStillRejected(): Unit = {
    val p = tmpCsv("a.csv", "a,b,c,d\n1,2,1,2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // A multi-element list is a row-value constructor, not a grouping paren.
    // It stays on the catch-all rather than binding to its first element.
    val ex = try { SqlEngine.execute("SELECT a FROM t WHERE (a, b) = (c, d)", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("ParenthesedExpressionList"))
  }

  // --- TRIM -----------------------------------------------------------------
  // JSqlParser gives TRIM its own node (TrimFunction) rather than the generic
  // Function node UPPER / LOWER get, because it carries the extended
  // `TRIM([LEADING|TRAILING|BOTH] [remstr] FROM str)` grammar. `Funcs`
  // implements the single-argument whitespace trim only.

  @Test def trimSingleArgument(): Unit = {
    val p = tmpCsv("a.csv", "name\n  alice  \nbob \n carol\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT TRIM(name) AS n FROM t", cat)
    assertEquals(Vector("n"), q.schema.fieldNames)
    assertEquals(Seq("alice", "bob", "carol"), collectAllRows(q).map(_("n")))
  }

  @Test def trimComposesWithOtherExpressions(): Unit = {
    val p = tmpCsv("a.csv", "name\n  alice  \n bob\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT UPPER(TRIM(name)) AS u, TRIM(UPPER(name)) AS v, LENGTH(TRIM((name))) AS len " +
        "FROM t WHERE TRIM(name) = 'alice'", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("ALICE"), rows.map(_("u")))
    assertEquals(Seq("ALICE"), rows.map(_("v")))
    assertEquals(Seq(5), rows.map(_("len")))
  }

  @Test def trimOverAggregateOutput(): Unit = {
    val p = tmpCsv("a.csv", "g,name\nA,  ann  \nA, amy\nB,  bea\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // `childExpressions` has to report TRIM's operand, or MAX is never
    // collected and the binder rejects it as non-aggregating.
    val q = SqlEngine.execute("SELECT g, TRIM(MAX(name)) AS m FROM t GROUP BY g ORDER BY g", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("A", "B"), rows.map(_("g")))
    assertEquals(Seq("amy", "bea"), rows.map(_("m")))
  }

  @Test def trimExtendedGrammarRejected(): Unit = {
    val p = tmpCsv("a.csv", "name\nxxaxx\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // Every extended form parks the string to trim in `getFromExpression` and
    // the remstr in `getExpression`, so binding `getExpression` would trim the
    // wrong operand (or hit a null). Reject loudly instead.
    val forms = Seq(
      "SELECT TRIM(BOTH 'x' FROM name) AS n FROM t",
      "SELECT TRIM(LEADING FROM name) AS n FROM t",
      "SELECT TRIM(TRAILING 'x' FROM name) AS n FROM t",
      "SELECT TRIM('x' FROM name) AS n FROM t",
      "SELECT TRIM(BOTH name) AS n FROM t",
      "SELECT TRIM(name, 'x') AS n FROM t")
    forms.foreach { sql =>
      val ex = try { SqlEngine.execute(sql, cat); null }
        catch { case e: IllegalArgumentException => e }
      assertNotNull(sql, ex)
      assertTrue(s"$sql -> ${ex.getMessage}",
        ex.getMessage.contains("single-argument TRIM(x)"))
    }
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

  @Test def groupByLongOnlyExercisesLongHashMapFastPath(): Unit = {
    // Single Long-fittable ColRef key → LongHashMap fast path,
    // primitive long stored unboxed in the hash table.
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
    // Both join columns are integers, multi-column → PackedBytesCodec on the
    // probe-side encodeFromBatchSkipIfAnyNull fast path (not LongHashMap —
    // that needs a single column).
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

  @Test def joinOnSingleLongKeyHitsLongHashMapFastPath(): Unit = {
    // Single Long-fittable ColRef on both sides → LongHashMap (fast
    // path). Includes NULL keys on both sides — must produce no-match per SQL
    // 3VL, identical to the codec path.
    val left = tmpCsv("l.csv",
      "id,n\n1,alice\n2,bob\n3,charlie\n,danny\n")
    val right = tmpCsv("r.csv",
      "user_id,score\n1,100\n3,50\n,99\n4,80\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.n, s.score FROM u JOIN s ON u.id = s.user_id ORDER BY u.n", cat)
    val rows = collectAllRows(q)
    assertEquals(2, rows.size) // alice + charlie; danny's NULL id matches nothing
    assertEquals(Seq("alice", "charlie"), rows.map(_("n")))
    assertEquals(Seq(100, 50), rows.map(_("score")))
  }

  @Test def leftJoinOnSingleLongKeyPreservesUnmatched(): Unit = {
    // LEFT outer with single-Long join key — LongHashMap fast path must still
    // emit unmatched-left rows with NULL right-side columns, including the
    // left row whose key is NULL itself.
    val left = tmpCsv("l.csv",
      "id,n\n1,alice\n2,bob\n,danny\n")
    val right = tmpCsv("r.csv",
      "user_id,score\n1,100\n3,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.n, s.score FROM u LEFT JOIN s ON u.id = s.user_id ORDER BY u.n", cat)
    val rows = collectAllRows(q)
    assertEquals(3, rows.size)
    assertEquals(Seq("alice", "bob", "danny"), rows.map(_("n")))
    assertEquals(Seq(100, null, null), rows.map(_("score")))
  }

  @Test def groupBySingleLongWithNullKeyOnLongHashMapPath(): Unit = {
    // Single-Long GROUP BY with NULL keys — LongHashMap path must put all
    // NULL-keyed rows into one bucket (matching SQL GROUP BY semantics).
    val p = tmpCsv("a.csv", "k,v\n1,10\n,20\n1,30\n,40\n2,50\n,60\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(
      inferSchema = false,
      columns = Some(Seq(Field("k", DataType.LongType), Field("v", DataType.IntType))))))
    val q = SqlEngine.execute("SELECT k, COUNT(*) AS n, SUM(v) AS s FROM t GROUP BY k", cat)
    val rows = collectAllRows(q)
    val byKey: Map[Any, (Long, Long)] = rows.map { r =>
      (r("k"), (r("n").asInstanceOf[Long], r("s").asInstanceOf[Long]))
    }.toMap
    assertEquals(3, byKey.size)
    assertEquals((2L, 40L), byKey(1L))     // k=1: 10+30
    assertEquals((1L, 50L), byKey(2L))     // k=2: 50
    assertEquals((3L, 120L), byKey(null))  // NULL: 20+40+60
  }

  // ---- Sharded aggregation: HashAggregate via ExchangeExec -----------------

  @Test def groupBySmallCsvInputUsesCollapsingPath(): Unit = {
    // Cardinality gate: CSV inputs report no exactRowCount, so the planner can't
    // prove the input is ≥ MinShardableSize and skips the exchange — small
    // GROUP BYs go through the historic collapsing aggregate. The point of
    // this test is the correctness path: the result must still be right
    // (8 distinct keys, count = 1 each) and the output must be a single
    // partition because no exchange was inserted.
    val p = tmpCsv("a.csv", "k,v\n1,10\n2,20\n3,30\n4,40\n5,50\n6,60\n7,70\n8,80\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT k, COUNT(*) AS n FROM t GROUP BY k", cat)
    assertEquals("collapsing path emits one partition", 1, q.numPartitions)
    val rows = collectAllRows(q).sortBy(_("k").toString.toInt)
    assertEquals(8, rows.size)
    assertEquals((1 to 8).toVector, rows.map(_("k").asInstanceOf[Int]).toVector)
    assertEquals(Vector.fill(8)(1L), rows.map(_("n").asInstanceOf[Long]).toVector)
  }

  @Test def groupByOverEmptyInputEmitsNoRows(): Unit = {
    // GROUP BY contract: with no input rows there are no groups, so the
    // output is empty. Distinct from the no-GROUP-BY case (covered by
    // `emptyAggregationReturnsOneRow`), which surfaces one zero-row per SQL
    // semantics. Per-shard execution must not leak a spurious zero-row from
    // any shard.
    val p = tmpCsv("a.csv", "k,v\n")  // header only, no rows
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions(inferSchema = false,
      columns = Some(Seq(Field("k", DataType.IntType), Field("v", DataType.IntType))))))
    val q = SqlEngine.execute("SELECT k, COUNT(*) AS n, SUM(v) AS s FROM t GROUP BY k", cat)
    val rows = collectAllRows(q)
    assertEquals(0, rows.size)
  }

  @Test def groupByManyDistinctKeysAcrossShardsAllSurface(): Unit = {
    // Insert enough distinct keys (1..200) that the hash partitioner spreads
    // them across multiple shards. Every key must surface exactly once with
    // its correct aggregate, regardless of which shard it landed in.
    val sb = new StringBuilder("k,v\n")
    var i = 1
    while (i <= 200) { sb.append(i).append(',').append(i * 10).append('\n'); i += 1 }
    val p = tmpCsv("a.csv", sb.toString)
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("SELECT k, SUM(v) AS s FROM t GROUP BY k", cat)
    val rows = collectAllRows(q)
    assertEquals("every distinct key must surface exactly once", 200, rows.size)
    val byKey: Map[Int, Long] = rows.map { r =>
      r("k").asInstanceOf[Int] -> r("s").asInstanceOf[Long]
    }.toMap
    assertEquals("no key duplicated or missing", 200, byKey.size)
    var k = 1
    while (k <= 200) {
      assertEquals(s"sum for key $k", (k * 10).toLong, byKey(k))
      k += 1
    }
  }

  @Test def equiJoinOnSmallCsvUsesBroadcastPath(): Unit = {
    // Cardinality gate: CSV inputs have no exactRowCount, so the planner can't
    // prove either side is ≥ BroadcastBuildThreshold and falls back to the
    // historic broadcast hash-join (build one side, stream the other —
    // collapses to one output partition). The point is correctness on the
    // broadcast path remains intact; the partition-shape changes are
    // exercised at the operator level in [[ExchangeExecTest]] / [[JoinSwapTest]].
    val left = tmpCsv("l.csv", "id,name\n1,a\n2,b\n3,c\n4,d\n5,e\n6,f\n7,g\n8,h\n")
    val right = tmpCsv("r.csv", "uid,score\n1,10\n2,20\n3,30\n4,40\n5,50\n6,60\n7,70\n8,80\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u JOIN s ON u.id = s.uid", cat)
    assertEquals("broadcast path emits one partition", 1, q.numPartitions)
    val rows = collectAllRows(q).sortBy(_("name").toString)
    assertEquals(8, rows.size)
    assertEquals(Vector("a", "b", "c", "d", "e", "f", "g", "h"),
      rows.map(_("name").asInstanceOf[String]).toVector)
    assertEquals(Vector(10, 20, 30, 40, 50, 60, 70, 80),
      rows.map(_("score").asInstanceOf[Int]).toVector)
  }

  @Test def leftOuterJoinAcrossShardsPreservesUnmatchedLeft(): Unit = {
    // Per-shard outer-join semantics: an unmatched left row must still
    // surface even though the join runs K independent times. Left's
    // unmatched-probe emission lives inside the per-shard probe loop.
    val left = tmpCsv("l.csv",
      "id,name\n1,alice\n2,bob\n3,charlie\n4,danny\n5,eve\n6,frank\n")
    val right = tmpCsv("r.csv", "uid,score\n1,100\n3,30\n5,50\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u LEFT JOIN s ON u.id = s.uid", cat)
    val rows = collectAllRows(q).sortBy(_("name").toString)
    assertEquals(6, rows.size)
    val byName = rows.map(r => r("name").toString -> r("score")).toMap
    assertEquals(100, byName("alice"))
    assertNull(byName("bob"))
    assertEquals(30, byName("charlie"))
    assertNull(byName("danny"))
    assertEquals(50, byName("eve"))
    assertNull(byName("frank"))
  }

  @Test def rightOuterJoinAcrossShardsPreservesUnmatchedBuild(): Unit = {
    // Per-shard outer-join semantics for the "unmatched build" emission —
    // each shard tracks its own matched-build bitset and after probing
    // emits the build rows in that shard that no probe row hit. Correct
    // because matching probe rows would have hashed to the same shard.
    val left = tmpCsv("l.csv", "id,name\n1,alice\n3,charlie\n5,eve\n")
    val right = tmpCsv("r.csv",
      "uid,score\n1,100\n2,200\n3,30\n4,400\n5,50\n6,600\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.name, s.score FROM u RIGHT JOIN s ON u.id = s.uid", cat)
    val rows = collectAllRows(q).sortBy(_("score").toString.toInt)
    assertEquals(6, rows.size)
    val byScore = rows.map(r => r("score").asInstanceOf[Int] -> r("name")).toMap
    assertEquals("alice", byScore(100))
    assertNull(byScore(200))
    assertEquals("charlie", byScore(30))
    assertNull(byScore(400))
    assertEquals("eve", byScore(50))
    assertNull(byScore(600))
  }

  @Test def fullOuterJoinAcrossShardsPreservesBothSides(): Unit = {
    // FULL OUTER: both per-shard "unmatched probe" and per-shard "unmatched
    // build" emissions must fire. The shards' unmatched rows compose into
    // the global unmatched set without any cross-shard coordination.
    val left = tmpCsv("l.csv", "id,name\n1,a\n2,b\n3,c\n")
    val right = tmpCsv("r.csv", "uid,score\n2,20\n3,30\n4,40\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.id, u.name, s.uid, s.score FROM u FULL OUTER JOIN s ON u.id = s.uid", cat)
    val rows = collectAllRows(q)
    assertEquals(4, rows.size)
    // Build a name → matched-rhs map so the assertions are order-agnostic.
    val byKey: Map[String, (Any, Any)] = rows.map { r =>
      val left = if (r("id") != null) r("id").toString else "NULL"
      val right = if (r("uid") != null) r("uid").toString else "NULL"
      s"$left-$right" -> (r("name"), r("score"))
    }.toMap
    assertEquals(("a", null), byKey("1-NULL"))         // unmatched left
    assertEquals(("b", 20), byKey("2-2"))               // matched
    assertEquals(("c", 30), byKey("3-3"))               // matched
    assertEquals((null, 40), byKey("NULL-4"))           // unmatched right
  }

  @Test def equiJoinManyDistinctKeysAcrossShardsAllMatch(): Unit = {
    // Insert enough distinct join keys (1..200) that the hash partitioner
    // spreads them across many shards. Every probe key with a matching
    // build key must surface; the per-shard build/probe must not lose a
    // match because the keys happened to land in different shards (the
    // pinned HashPartitioner guarantees they don't).
    val lsb = new StringBuilder("id,name\n")
    val rsb = new StringBuilder("uid,score\n")
    var i = 1
    while (i <= 200) {
      lsb.append(i).append(",name").append(i).append('\n')
      rsb.append(i).append(',').append(i * 10).append('\n')
      i += 1
    }
    val left = tmpCsv("l.csv", lsb.toString)
    val right = tmpCsv("r.csv", rsb.toString)
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT u.id, s.score FROM u JOIN s ON u.id = s.uid", cat)
    val rows = collectAllRows(q)
    assertEquals(200, rows.size)
    val byId: Map[Int, Int] = rows.map { r =>
      r("id").asInstanceOf[Int] -> r("score").asInstanceOf[Int]
    }.toMap
    assertEquals(200, byId.size)
    var k = 1
    while (k <= 200) {
      assertEquals(s"score for id $k", k * 10, byId(k))
      k += 1
    }
  }

  @Test def groupByThenOrderByLimitWorksAcrossShards(): Unit = {
    // ORDER BY + LIMIT downstream of a sharded GROUP BY: the sort still
    // collapses to a single output partition (the planner above the sort
    // wraps in GlobalLimit), so the top-N over K shards is correct.
    val sb = new StringBuilder("k,v\n")
    var i = 1
    while (i <= 50) { sb.append(i).append(',').append(i).append('\n'); i += 1 }
    val p = tmpCsv("a.csv", sb.toString)
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "SELECT k, SUM(v) AS s FROM t GROUP BY k ORDER BY SUM(v) DESC LIMIT 5", cat)
    val rows = collectAllRows(q)
    assertEquals(5, rows.size)
    assertEquals(Vector(50, 49, 48, 47, 46), rows.map(_("k").asInstanceOf[Int]).toVector)
    assertEquals(Vector(50L, 49L, 48L, 47L, 46L), rows.map(_("s").asInstanceOf[Long]).toVector)
  }

  // ---------------------------------------------------------------------------
  // CTEs (WITH clause)
  // ---------------------------------------------------------------------------

  @Test def simpleCte(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("WITH c AS (SELECT id, name FROM t) SELECT id, name FROM c", cat)
    assertEquals(Vector("id", "name"), q.schema.fieldNames)
    val rows = collectAllRows(q)
    assertEquals(Seq(1, 2, 3), rows.map(_("id")))
    assertEquals(Seq("alice", "bob", "charlie"), rows.map(_("name")))
  }

  @Test def cteOuterFilterReferencesCarriedColumn(): Unit = {
    // The CTE projects `score`; the outer query both filters on it and drops it
    // from its own SELECT list. The carried column must resolve in outer scope.
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH hi AS (SELECT id, score FROM t WHERE score > 20) SELECT id FROM hi WHERE score > 50 ORDER BY id", cat)
    assertEquals(Vector("id"), q.schema.fieldNames)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 4), rows.map(_("id")))
  }

  @Test def cteChainReferencesEarlierCte(): Unit = {
    // `top` reads from `base`, an earlier CTE in the same WITH clause.
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH base AS (SELECT id, score FROM t WHERE score > 20), " +
        "top AS (SELECT id FROM base WHERE score > 50) " +
        "SELECT id FROM top ORDER BY id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(2, 4), rows.map(_("id")))
  }

  @Test def multipleCtesJoined(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH a AS (SELECT id, score FROM t WHERE score >= 30), " +
        "b AS (SELECT id, score FROM t WHERE score <= 75) " +
        "SELECT a.id AS id, a.score AS ascore, b.score AS bscore " +
        "FROM a JOIN b ON a.id = b.id ORDER BY a.id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(3, 4), rows.map(_("id")))
    assertEquals(Seq(30, 75), rows.map(_("ascore")))
    assertEquals(Seq(30, 75), rows.map(_("bscore")))
  }

  @Test def cteJoinedToRealTable(): Unit = {
    val left = tmpCsv("l.csv", "id,name\n1,alice\n2,bob\n3,charlie\n")
    val right = tmpCsv("r.csv", "user_id,score\n1,100\n2,40\n3,80\n")
    val cat = new Catalog
    cat.register("u", CsvReader.fromPath(left.toString, CsvOptions()))
    cat.register("s", CsvReader.fromPath(right.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH big AS (SELECT user_id, score FROM s WHERE score > 60) " +
        "SELECT u.name, big.score FROM u JOIN big ON u.id = big.user_id ORDER BY u.id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("alice", "charlie"), rows.map(_("name")))
    assertEquals(Seq(100, 80), rows.map(_("score")))
  }

  @Test def cteReferencedTwiceActsLikeSelfJoin(): Unit = {
    // Same CTE name appears twice in FROM. Referenced twice, `c` is materialized
    // once and both references scan the shared result; the output is still the
    // self-join (exactly like `FROM t a JOIN t b`). Compute-once is asserted
    // directly in CteMaterializationTest; here we only pin the result.
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,10\n3,30\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH c AS (SELECT id, score FROM t) " +
        "SELECT x.id AS xid, y.id AS yid FROM c x JOIN c y ON x.score = y.score " +
        "WHERE x.id < y.id ORDER BY x.id, y.id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(1), rows.map(_("xid")))
    assertEquals(Seq(2), rows.map(_("yid")))
  }

  @Test def cteWithAggregateInsideFilteredOutside(): Unit = {
    val p = tmpCsv("a.csv", "cat,score\nA,1\nA,2\nB,3\nB,4\nB,5\nC,9\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH agg AS (SELECT cat, COUNT(*) AS n FROM t GROUP BY cat) " +
        "SELECT cat, n FROM agg WHERE n > 1 ORDER BY cat", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq("A", "B"), rows.map(_("cat")))
    assertEquals(Seq(2L, 3L), rows.map(_("n")))
  }

  @Test def cteWithExplicitColumnAliasList(): Unit = {
    // WITH c(uid, sc) AS (...) renames the body's output columns.
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,20\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH c(uid, sc) AS (SELECT id, score FROM t) SELECT uid, sc FROM c ORDER BY uid", cat)
    assertEquals(Vector("uid", "sc"), q.schema.fieldNames)
    val rows = collectAllRows(q)
    assertEquals(Seq(1, 2), rows.map(_("uid")))
    assertEquals(Seq(10, 20), rows.map(_("sc")))
  }

  @Test def cteInUnionAll(): Unit = {
    // WITH clause attaches to the top-level SetOperationList; both arms must
    // see the CTE scope.
    val p = tmpCsv("a.csv", "id,score\n1,10\n2,90\n3,30\n4,75\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH lo AS (SELECT id FROM t WHERE score < 40), " +
        "hi AS (SELECT id FROM t WHERE score > 60) " +
        "SELECT id FROM lo UNION ALL SELECT id FROM hi ORDER BY id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(1, 2, 3, 4), rows.map(_("id")))
  }

  @Test def cteNameIsCaseInsensitive(): Unit = {
    val p = tmpCsv("a.csv", "id,name\n1,alice\n2,bob\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val q = SqlEngine.execute("WITH MyCte AS (SELECT id FROM t) SELECT id FROM mycte ORDER BY id", cat)
    val rows = collectAllRows(q)
    assertEquals(Seq(1, 2), rows.map(_("id")))
  }

  @Test def cteShadowsCatalogTable(): Unit = {
    // A CTE named `t` takes precedence over a registered view also named `t`.
    val realT = tmpCsv("real.csv", "id,v\n9,9\n")
    val src = tmpCsv("src.csv", "id,v\n1,100\n2,10\n3,80\n")
    val cat = new Catalog
    cat.register("t", CsvReader.fromPath(realT.toString, CsvOptions()))
    cat.register("src", CsvReader.fromPath(src.toString, CsvOptions()))
    val q = SqlEngine.execute(
      "WITH t AS (SELECT id, v FROM src WHERE v > 50) SELECT id, v FROM t ORDER BY id", cat)
    val rows = collectAllRows(q)
    // If the CTE shadowed the view we see src's filtered rows, not the real t's (9,9).
    assertEquals(Seq(1, 3), rows.map(_("id")))
    assertEquals(Seq(100, 80), rows.map(_("v")))
  }

  @Test def recursiveCteRejected(): Unit = {
    val p = tmpCsv("a.csv", "id\n1\n2\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try {
      SqlEngine.execute("WITH RECURSIVE c AS (SELECT id FROM t) SELECT id FROM c", cat); null
    } catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("Recursive"))
  }

  @Test def cteColumnAliasArityMismatchRejected(): Unit = {
    val p = tmpCsv("a.csv", "id,score\n1,10\n")
    val cat = catalogWith("t" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val ex = try {
      SqlEngine.execute("WITH c(a, b, x) AS (SELECT id, score FROM t) SELECT a FROM c", cat); null
    } catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("column alias"))
  }

  // --- Clauses JSqlParser parses but the binder does not implement ----------
  // Each of these used to parse, run, and answer a different question than the
  // one asked (plans/robustness/01). UnsupportedClauses now rejects them before
  // binding. The data is the plan's, so the comment on each case records the
  // answer the clause used to give.

  private def clauseCatalog(): Catalog = {
    val c = new Catalog
    def csv(name: String, contents: String): Unit =
      c.register(name, CsvReader.fromPath(tmpCsv(s"$name.csv", contents).toString, CsvOptions()))
    csv("t", "a\n1\n2\n2\n3\n")
    csv("s", "a\n2\n3\n")
    csv("t4", "a\n1\n2\n3\n4\n")
    csv("tn", "id,a\n1,1\n2,2\n3,2\n4,3\n5,\n")
    csv("ts", "id,s\n1,Ab\n2,\n3,xy\n4,A_b\n")
    csv("tg", "g,a\nx,1\nx,2\ny,3\ny,4\n")
    c
  }

  /** `sql` fails at bind time with a message naming `clause`. */
  private def assertRejected(sql: String, cat: Catalog, clause: String): Unit = {
    val ex = try { SqlEngine.execute(sql, cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(s"$sql was accepted", ex)
    assertTrue(s"$sql -> ${ex.getMessage}", ex.getMessage.contains(clause))
    assertTrue(s"$sql -> ${ex.getMessage}", ex.getMessage.contains("is not supported"))
  }

  @Test def joinConditionClausesRejected(): Unit = {
    val cat = clauseCatalog()
    // Both dropped the join condition: 8 rows, the cartesian product, not 3.
    assertRejected("SELECT * FROM t JOIN s USING (a)", cat, "JOIN ... USING")
    assertRejected("SELECT * FROM t NATURAL JOIN s", cat, "NATURAL JOIN")
    // A semi join ran as a left outer join: (1)(2)(3)(4), not (2)(3).
    assertRejected("SELECT t.a FROM t4 t LEFT SEMI JOIN s u ON t.a = u.a", cat, "SEMI JOIN")
    // Both ran as INNER joins: (2)(3) instead of the outer join's (1)(2)(3)(4).
    assertRejected("SELECT t.a FROM t4 t OUTER JOIN s ON t.a = s.a", cat, "OUTER JOIN without LEFT")
    assertRejected("SELECT t.a FROM t4 t, s WHERE t.a = s.a(+)", cat, "(+)")
  }

  @Test def distinctAggregatesSeeEachValueOnce(): Unit = {
    val cat = clauseCatalog() // t.a is 1, 2, 2, 3; tn.a adds a NULL
    // The plan's case: 6. The dropped DISTINCT gave 8.
    assertEquals(Seq(6L), column("SELECT SUM(DISTINCT a) AS s FROM t", cat, "s"))
    assertEquals(Seq(2.0), column("SELECT AVG(DISTINCT a) AS v FROM t", cat, "v"))
    // DISTINCT cannot move an extreme.
    assertEquals(Seq(1), column("SELECT MIN(DISTINCT a) AS m FROM t", cat, "m"))
    assertEquals(Seq(3), column("SELECT MAX(DISTINCT a) AS m FROM t", cat, "m"))
    // A NULL is skipped, as the plain aggregates skip it.
    assertEquals(Seq(6L), column("SELECT SUM(DISTINCT a) AS s FROM tn", cat, "s"))
    // No input at all is NULL, like SUM.
    assertEquals(Seq(null), column("SELECT SUM(DISTINCT a) AS s FROM t WHERE a > 10", cat, "s"))
    // Beside the plain form and COUNT(DISTINCT) in one query: separate slots.
    val row = collectAllRows(SqlEngine.execute(
      "SELECT SUM(a) AS s, SUM(DISTINCT a) AS d, COUNT(DISTINCT a) AS c, AVG(a) AS v FROM t", cat)).head
    assertEquals(Seq(8L, 6L, 3L, 2.0), Seq(row("s"), row("d"), row("c"), row("v")))
    // The aggregates DISTINCT is not implemented for still say so.
    assertRejected("SELECT STDDEV(DISTINCT a) FROM t", cat, "STDDEV(DISTINCT ...)")
    assertRejected("SELECT COUNT_IF(DISTINCT a > 1) FROM t", cat, "COUNT_IF(DISTINCT ...)")
  }

  /** An aggregate ORDER BY names but the SELECT list does not is still
    * computed. Aggregate collection used to read only SELECT and HAVING, so
    * binding died with an internal NoSuchElementException looking up its slot. */
  @Test def orderByAggregateMissingFromTheSelectList(): Unit = {
    val cat = clauseCatalog() // tg: x -> 1, 2 and y -> 3, 4
    assertEquals(Seq("y", "x"), column("SELECT g FROM tg GROUP BY g ORDER BY SUM(a) DESC", cat, "g"))
    assertEquals(Seq("x", "y"), column("SELECT g FROM tg GROUP BY g ORDER BY MAX(a)", cat, "g"))
    // The counts tie, so the second key decides.
    assertEquals(Seq("y", "x"), column("SELECT g FROM tg GROUP BY g ORDER BY COUNT(*), g DESC", cat, "g"))
    // Beside one the SELECT list does name.
    assertEquals(Seq(3L, 7L),
      column("SELECT g, SUM(a) AS s FROM tg GROUP BY g ORDER BY MIN(a) + SUM(a)", cat, "s"))
    // Above a DISTINCT the ORDER BY must name the SELECT list, so this stays a
    // bind error, now a clean one.
    val ex = try { SqlEngine.execute("SELECT DISTINCT g FROM tg GROUP BY g ORDER BY SUM(a)", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("SUM"))
  }

  /** A HAVING with no GROUP BY groups the whole input into one group, as in
    * standard SQL. Only the aggregate path read HAVING, so unless the SELECT
    * list held an aggregate it was dropped: every query below returned its
    * input rows unfiltered. */
  @Test def havingWithoutGroupByAggregatesOneGroup(): Unit = {
    val cat = clauseCatalog() // t.a is 1, 2, 2, 3
    // Four rows of 1 each, where the single group's count fails or passes.
    assertEquals(Nil, column("SELECT 1 AS x FROM t HAVING COUNT(*) > 10", cat, "x"))
    assertEquals(Seq(1), column("SELECT 1 AS x FROM t HAVING COUNT(*) > 1", cat, "x"))
    assertEquals(Seq(8), column("SELECT 8 AS x FROM t HAVING SUM(a) = 8", cat, "x"))
    // The one group exists over an empty input too.
    assertEquals(Seq("empty"), column("SELECT 'empty' AS s FROM t WHERE a > 9 HAVING COUNT(*) = 0", cat, "s"))
    // And over the one row of a FROM-less SELECT.
    assertEquals(Nil, column("SELECT 1 AS x HAVING 1 = 0", cat, "x"))
    // With an aggregate in the SELECT list it already worked; unchanged.
    assertEquals(Nil, column("SELECT COUNT(*) AS c FROM t HAVING COUNT(*) > 10", cat, "c"))
    assertEquals(Seq(4L), column("SELECT COUNT(*) AS c FROM t HAVING COUNT(*) > 1", cat, "c"))
    // A column outside an aggregate has no single value in the group, so this
    // is an error rather than the filter MySQL makes of it. It returned (1)(2)(2)(3).
    val ex = try { SqlEngine.execute("SELECT a FROM t HAVING a > 1", cat); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull(ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("Unknown column 'a'"))
  }

  @Test def distinctAggregatesPerGroupInHavingAndOrderBy(): Unit = {
    val p = tmpCsv("d.csv", "g,a\nx,1\nx,1\nx,2\ny,5\ny,5\nz,\n")
    val cat = catalogWith("d" -> CsvReader.fromPath(p.toString, CsvOptions()))
    val rows = collectAllRows(SqlEngine.execute(
      "SELECT g, SUM(DISTINCT a) AS s, AVG(DISTINCT a) AS v FROM d GROUP BY g ORDER BY g", cat))
    assertEquals(Seq("x", "y", "z"), rows.map(_("g")))
    assertEquals(Seq(3L, 5L, null), rows.map(_("s")))
    assertEquals(Seq(1.5, 5.0, null), rows.map(_("v")))
    assertEquals(Seq("y"), column("SELECT g FROM d GROUP BY g HAVING SUM(DISTINCT a) > 4", cat, "g"))
    assertEquals(Seq("y", "x"),
      column("SELECT g FROM d GROUP BY g HAVING COUNT(a) > 0 ORDER BY SUM(DISTINCT a) DESC", cat, "g"))
  }

  @Test def aggregateFilterRejected(): Unit = {
    // FILTER parses as a window function, so it dropped the predicate AND
    // stopped the aggregate collapsing: five rows of 8, not one row of 7.
    val cat = clauseCatalog()
    assertRejected("SELECT SUM(a) FILTER (WHERE a > 1) AS s FROM tn", cat, "FILTER (WHERE ...)")
    assertRejected("SELECT COUNT(*) FILTER (WHERE a > 1) AS c FROM tn", cat, "FILTER (WHERE ...)")
  }

  private def column(sql: String, cat: Catalog, name: String): Seq[Any] =
    collectAllRows(SqlEngine.execute(sql, cat)).map(_(name))

  @Test def offsetSkipsRowsAfterTheSort(): Unit = {
    val cat = clauseCatalog()
    // The plan's page: (2)(2). Dropping the clause gave (1)(2).
    assertEquals(Seq(2, 2), column("SELECT a FROM t ORDER BY a LIMIT 2 OFFSET 1", cat, "a"))
    // Without a LIMIT, everything after the skipped rows (dropping it gave all four).
    assertEquals(Seq(2, 2, 3), column("SELECT a FROM t ORDER BY a OFFSET 1", cat, "a"))
    // The standard OFFSET n ROWS spelling, and MySQL's LIMIT offset, count.
    assertEquals(Seq(2, 3), column("SELECT a FROM t ORDER BY a OFFSET 2 ROWS", cat, "a"))
    assertEquals(Seq(2, 2), column("SELECT a FROM t ORDER BY a LIMIT 1, 2", cat, "a"))
    // Past the end, and zero.
    assertEquals(Nil, column("SELECT a FROM t ORDER BY a OFFSET 10", cat, "a"))
    assertEquals(Seq(1, 2, 2, 3), column("SELECT a FROM t ORDER BY a OFFSET 0", cat, "a"))
    // OFFSET counts output rows: after DISTINCT, and after GROUP BY.
    assertEquals(Seq(2, 3), column("SELECT DISTINCT a FROM t ORDER BY a OFFSET 1", cat, "a"))
    assertEquals(Seq(3), column("SELECT a, COUNT(*) AS n FROM t GROUP BY a ORDER BY a LIMIT 5 OFFSET 2", cat, "a"))
    // Inside a CTE body.
    assertEquals(Seq(3), column("WITH c AS (SELECT a FROM t ORDER BY a OFFSET 3) SELECT a FROM c", cat, "a"))
  }

  /** OFFSET drops n rows of the whole result. A per-partition skip — the
    * limit's push-down shape — would drop n rows from every partition. */
  @Test def offsetIsGlobalAcrossPartitionsAndBatches(): Unit = {
    val schema = Schema(Vector(Field("v", DataType.IntType)))
    def batch(values: Seq[Int]): ColumnarBatch = {
      val b = new ColumnarBatch(schema, values.length)
      values.zipWithIndex.foreach { case (v, i) => b.column(0).setBoxed(i, java.lang.Integer.valueOf(v)) }
      b.setNumRows(values.length)
      b
    }
    // 4 partitions x 3 batches x 5 rows: v = 1..60, dealt round-robin so no
    // partition holds a contiguous run.
    val values = (1 to 60).toVector
    val partitions = (0 until 4).map { p =>
      values.filter(v => (v - 1) % 4 == p).grouped(5).map(batch).toIndexedSeq
    }
    val cat = catalogWith("m" -> new MaterializedView(schema, partitions))
    // Unordered: 53 rows, whichever they are. A per-partition skip would leave 32.
    assertEquals(53, column("SELECT v FROM m OFFSET 7", cat, "v").size)
    // Ordered: the skip starts mid-batch of the merged stream.
    assertEquals(Seq(8, 9, 10, 11, 12), column("SELECT v FROM m ORDER BY v LIMIT 5 OFFSET 7", cat, "v"))
    assertEquals((53 to 60).toList, column("SELECT v FROM m ORDER BY v OFFSET 52", cat, "v"))
  }

  @Test def offsetAppliesToAWholeUnion(): Unit = {
    val cat = clauseCatalog()
    // t UNION ALL s is 1,2,2,3,2,3. With no ORDER BY, JSqlParser parks the tail
    // on the last arm; it still belongs to the union.
    assertEquals(2, column("SELECT a FROM t UNION ALL SELECT a FROM s OFFSET 4", cat, "a").size)
    assertEquals(1, column("SELECT a FROM t UNION ALL SELECT a FROM s LIMIT 2 OFFSET 5", cat, "a").size)
    assertEquals(Seq(2, 2), column("SELECT a FROM t UNION ALL SELECT a FROM s ORDER BY a LIMIT 2 OFFSET 1", cat, "a"))
    // Inside parentheses the OFFSET is the arm's own: s sorted is 2,3, so (3).
    assertEquals(Seq(1, 2, 2, 3, 3),
      column("SELECT a FROM t UNION ALL (SELECT a FROM s ORDER BY a OFFSET 1) ORDER BY a", cat, "a"))
    // After a parenthesized query it is still rejected, like ORDER BY and LIMIT there.
    assertRejected("(SELECT a FROM t) OFFSET 1", cat, "after a parenthesized query")
  }

  @Test def countAllCountsNonNullValues(): Unit = {
    val cat = clauseCatalog() // tn.a is 1, 2, 2, 3, NULL
    // ALL is the default quantifier: COUNT(ALL a) is COUNT(a), 4. It used to
    // bind as COUNT(*) and count the NULL too.
    assertEquals(Seq(4L), column("SELECT COUNT(ALL a) AS c FROM tn", cat, "c"))
    assertEquals(Seq(5L), column("SELECT COUNT(*) AS c FROM tn", cat, "c"))
    assertEquals(Seq(8L), column("SELECT SUM(ALL a) AS s FROM tn", cat, "s"))
    // The same under OVER.
    assertEquals(Seq.fill(5)(4L), column("SELECT COUNT(ALL a) OVER () AS c FROM tn", cat, "c"))
    assertEquals(Seq.fill(5)(5L), column("SELECT COUNT(*) OVER () AS c FROM tn", cat, "c"))
  }

  @Test def nullsFirstAndLastPlaceNullsInOrderBy(): Unit = {
    val cat = clauseCatalog() // tn.a is 1, 2, 2, 3, NULL
    // Without a NULLS clause the placement is unchanged: NULL sorts as the
    // smallest value, first under ASC and last under DESC.
    assertEquals(Seq(null, 1, 2, 2, 3), column("SELECT a FROM tn ORDER BY a", cat, "a"))
    assertEquals(Seq(3, 2, 2, 1, null), column("SELECT a FROM tn ORDER BY a DESC", cat, "a"))
    // An explicit clause wins in either direction. NULLS LAST used to be
    // dropped, leaving the NULL first.
    assertEquals(Seq(1, 2, 2, 3, null), column("SELECT a FROM tn ORDER BY a NULLS LAST", cat, "a"))
    assertEquals(Seq(1, 2, 2, 3, null), column("SELECT a FROM tn ORDER BY a ASC NULLS LAST", cat, "a"))
    assertEquals(Seq(null, 1, 2, 2, 3), column("SELECT a FROM tn ORDER BY a ASC NULLS FIRST", cat, "a"))
    assertEquals(Seq(null, 3, 2, 2, 1), column("SELECT a FROM tn ORDER BY a DESC NULLS FIRST", cat, "a"))
    assertEquals(Seq(3, 2, 2, 1, null), column("SELECT a FROM tn ORDER BY a DESC NULLS LAST", cat, "a"))
    // Every sort site: above DISTINCT, above a UNION, past a GROUP BY, and an
    // ordinal key.
    assertEquals(Seq(1, 2, 3, null), column("SELECT DISTINCT a FROM tn ORDER BY a NULLS LAST", cat, "a"))
    assertEquals(Seq(1, 2, 2, 2, 3, 3, null),
      column("SELECT a FROM tn UNION ALL SELECT a FROM s ORDER BY a NULLS LAST", cat, "a"))
    assertEquals(Seq(1, 2, 3, null), column("SELECT a, COUNT(*) AS n FROM tn GROUP BY a ORDER BY a NULLS LAST", cat, "a"))
    assertEquals(Seq(1, 2, 2, 3, null), column("SELECT a FROM tn ORDER BY 1 NULLS LAST", cat, "a"))
    // The page moves with the NULL.
    assertEquals(Seq(3, null), column("SELECT a FROM tn ORDER BY a NULLS LAST LIMIT 2 OFFSET 3", cat, "a"))
  }

  @Test def nullPlacementIsPerKey(): Unit = {
    val p = tmpCsv("p.csv", "g,a\nx,1\nx,\ny,2\n,3\ny,\n")
    val cat = catalogWith("p" -> CsvReader.fromPath(p.toString, CsvOptions()))
    // g NULLS LAST puts the NULL group at the end; within each g, a DESC NULLS
    // FIRST puts that group's NULL a first.
    val rows = collectAllRows(SqlEngine.execute("SELECT g, a FROM p ORDER BY g NULLS LAST, a DESC NULLS FIRST", cat))
    assertEquals(Seq("x", "x", "y", "y", null), rows.map(_("g")))
    assertEquals(Seq(null, 1, null, 2, 3), rows.map(_("a")))
  }

  @Test def nullPlacementInsideOver(): Unit = {
    val cat = clauseCatalog() // tn: id 5 is the row whose a is NULL
    def byId(sql: String, col: String): Map[Any, Any] =
      collectAllRows(SqlEngine.execute(sql, cat)).map(r => r("id") -> r(col)).toMap
    // Default: the NULL sorts first under ASC, so it is row 1.
    assertEquals(1L, byId("SELECT id, ROW_NUMBER() OVER (ORDER BY a) AS n FROM tn", "n")(5))
    // NULLS LAST makes it row 5. The clause used to be dropped here too.
    assertEquals(5L, byId("SELECT id, ROW_NUMBER() OVER (ORDER BY a NULLS LAST) AS n FROM tn", "n")(5))
    // RANK under DESC NULLS FIRST: NULL 1, then 3 at 2, the two 2s at 3, and 1 at 5.
    val ranks = byId("SELECT id, RANK() OVER (ORDER BY a DESC NULLS FIRST) AS r FROM tn", "r")
    assertEquals(Map[Any, Any](5 -> 1L, 4 -> 2L, 2 -> 3L, 3 -> 3L, 1 -> 5L), ranks)
  }

  @Test def fetchFirstAndTopAreSpellingsOfLimit(): Unit = {
    val cat = clauseCatalog() // t4.a is 1..4, t.a is 1, 2, 2, 3, s.a is 2, 3
    // The plan's cases: both returned all four rows.
    assertEquals(Seq(1, 2), column("SELECT TOP 2 a FROM t4 ORDER BY a", cat, "a"))
    assertEquals(Seq(1, 2), column("SELECT a FROM t4 ORDER BY a FETCH FIRST 2 ROWS ONLY", cat, "a"))
    // The other spellings: TOP (n); NEXT, ROW, and a count left out, which is one.
    assertEquals(Seq(4, 3), column("SELECT TOP (2) a FROM t4 ORDER BY a DESC", cat, "a"))
    assertEquals(Seq(1), column("SELECT a FROM t4 ORDER BY a FETCH NEXT 1 ROW ONLY", cat, "a"))
    assertEquals(Seq(1), column("SELECT a FROM t4 ORDER BY a FETCH FIRST ROW ONLY", cat, "a"))
    assertEquals(Nil, column("SELECT TOP 0 a FROM t4", cat, "a"))
    // The standard page: OFFSET, then FETCH.
    assertEquals(Seq(2, 3), column("SELECT a FROM t4 ORDER BY a OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY", cat, "a"))
    // Like LIMIT, both count after DISTINCT.
    assertEquals(Seq(1, 2), column("SELECT DISTINCT TOP 2 a FROM t ORDER BY a", cat, "a"))
    assertEquals(Seq(3, 2), column("SELECT DISTINCT a FROM t ORDER BY a DESC FETCH FIRST 2 ROWS ONLY", cat, "a"))
    // After a UNION, FETCH limits the whole union; JSqlParser parks it on the
    // list and an OFFSET without ORDER BY on the last arm, and both apply to
    // the union: 6 rows, skip 5, keep 1. (Read per arm: s skips to nothing,
    // and the union keeps 2.)
    assertEquals(Seq(1, 2, 2),
      column("SELECT a FROM t4 UNION ALL SELECT a FROM s ORDER BY a FETCH FIRST 3 ROWS ONLY", cat, "a"))
    assertEquals(1,
      column("SELECT a FROM t4 UNION ALL SELECT a FROM s OFFSET 5 ROWS FETCH NEXT 2 ROWS ONLY", cat, "a").size)
    // TOP belongs to its own arm, even the last one, and to a CTE body.
    assertEquals(Seq(3, 4), column(
      "(SELECT TOP 1 a FROM t4 ORDER BY a DESC) UNION ALL (SELECT TOP 1 a FROM s ORDER BY a DESC) ORDER BY a",
      cat, "a"))
    assertEquals(5, column("SELECT a FROM t4 UNION ALL SELECT TOP 1 a FROM s", cat, "a").size)
    assertEquals(Seq(4), column("WITH c AS (SELECT TOP 1 a FROM t4 ORDER BY a DESC) SELECT a FROM c", cat, "a"))
    // The modifiers neither spelling of LIMIT can express.
    assertRejected("SELECT TOP 50 PERCENT a FROM t", cat, "TOP n PERCENT")
    assertRejected("SELECT TOP 2 WITH TIES a FROM t ORDER BY a", cat, "TOP n WITH TIES")
    assertRejected("SELECT a FROM t ORDER BY a FETCH FIRST 2 ROWS WITH TIES", cat, "FETCH ... WITH TIES")
    assertRejected("SELECT a FROM t FETCH FIRST 50 PERCENT ROWS ONLY", cat, "FETCH ... PERCENT")
    assertRejected("(SELECT a FROM t) FETCH FIRST 1 ROWS ONLY", cat, "after a parenthesized query")
    // Two counts, or TOP with a skip, are an error rather than a guess.
    def bindError(sql: String): String =
      try { SqlEngine.execute(sql, cat); fail(s"$sql was accepted"); "" }
      catch { case e: IllegalArgumentException => e.getMessage }
    assertTrue(bindError("SELECT TOP 2 a FROM t LIMIT 1").contains("LIMIT and TOP both limit the rows"))
    assertTrue(bindError("SELECT a FROM t LIMIT 3 FETCH FIRST 2 ROWS ONLY")
      .contains("LIMIT and FETCH FIRST both limit the rows"))
    assertTrue(bindError("SELECT a FROM t4 UNION ALL SELECT a FROM s LIMIT 3 FETCH FIRST 2 ROWS ONLY")
      .contains("LIMIT and FETCH FIRST both limit the rows"))
    assertTrue(bindError("SELECT TOP 2 a FROM t ORDER BY a OFFSET 1 ROWS").contains("TOP n with OFFSET is not supported"))
    // The count must be an integer literal, as LIMIT's must.
    assertTrue(bindError("SELECT TOP (1 + 1) a FROM t").contains("TOP must be an integer literal"))
    assertTrue(bindError("SELECT a FROM t FETCH FIRST -1 ROWS ONLY").contains("FETCH FIRST must be an integer literal"))
  }

  @Test def ilikeMatchesRegardlessOfCase(): Unit = {
    val cat = clauseCatalog() // ts.s is 'Ab', NULL, 'xy', 'A_b'
    // The plan's case: (1). It used to run as a case-sensitive LIKE: no rows.
    assertEquals(Seq(1), column("SELECT id FROM ts WHERE s ILIKE 'ab'", cat, "id"))
    assertEquals(Seq(1, 4), column("SELECT id FROM ts WHERE s ILIKE 'a%' ORDER BY id", cat, "id"))
    // NOT ILIKE, with the NULL row dropped as NOT LIKE drops it.
    assertEquals(Seq(3, 4), column("SELECT id FROM ts WHERE s NOT ILIKE 'ab' ORDER BY id", cat, "id"))
    // A computed pattern takes the per-row path.
    assertEquals(Seq(1, 3, 4), column("SELECT id FROM ts WHERE LOWER(s) ILIKE UPPER(s) ORDER BY id", cat, "id"))
    // LIKE itself stays case-sensitive.
    assertEquals(Nil, column("SELECT id FROM ts WHERE s LIKE 'ab'", cat, "id"))
  }

  @Test def likeEscapeMakesTheNextCharacterLiteral(): Unit = {
    val cat = clauseCatalog() // ts.s is 'Ab', NULL, 'xy', 'A_b'
    // The plan's case: (4). The ignored escape gave no rows.
    assertEquals(Seq(4), column("SELECT id FROM ts WHERE s LIKE 'A!_b' ESCAPE '!'", cat, "id"))
    // Without ESCAPE the backslash escapes, as it always has; ESCAPE '' turns
    // escaping off, so the backslash is an ordinary character.
    assertEquals(Seq(4), column("SELECT id FROM ts WHERE s LIKE 'A\\_b'", cat, "id"))
    assertEquals(Nil, column("SELECT id FROM ts WHERE s LIKE 'A\\_b' ESCAPE ''", cat, "id"))
    // The escape escapes itself and %, and a trailing one stands for itself.
    assertEquals(Seq(1), column("SELECT id FROM ts WHERE s || '!' LIKE 'Ab!!' ESCAPE '!'", cat, "id"))
    assertEquals(Seq(3), column("SELECT id FROM ts WHERE s || '%' LIKE 'xy!%' ESCAPE '!'", cat, "id"))
    assertEquals(Seq(1), column("SELECT id FROM ts WHERE s || '!' LIKE 'Ab!' ESCAPE '!'", cat, "id"))
    // With ILIKE: a letter as the escape still escapes only itself, and the
    // rest matches regardless of case.
    assertEquals(Seq(4), column("SELECT id FROM ts WHERE s ILIKE 'aX_B' ESCAPE 'X'", cat, "id"))
    // Only a one-character literal is an escape character.
    Seq("SELECT id FROM ts WHERE s LIKE 'a' ESCAPE '!!'", "SELECT id FROM ts WHERE s LIKE 'a' ESCAPE s").foreach { sql =>
      val ex = try { SqlEngine.execute(sql, cat); null } catch { case e: IllegalArgumentException => e }
      assertNotNull(sql, ex)
      assertTrue(s"$sql -> ${ex.getMessage}", ex.getMessage.contains("ESCAPE must be a single-character"))
    }
  }

  @Test def selectWithoutFromReadsOneRow(): Unit = {
    // Used to die with an internal NullPointerException.
    val cat = new Catalog
    assertEquals(Seq(Map("x" -> 1, "s" -> "a", "y" -> 5)),
      collectAllRows(SqlEngine.execute("SELECT 1 AS x, 'a' AS s, 2 + 3 AS y", cat)))
    // One row: an aggregate sees it once, and a false WHERE removes it.
    assertEquals(Seq(1L), column("SELECT COUNT(*) AS c", cat, "c"))
    assertEquals(Seq(2L), column("SELECT SUM(2) AS s", cat, "s"))
    assertEquals(Nil, column("SELECT 1 AS x WHERE 1 = 0", cat, "x"))
    assertEquals(Nil, column("SELECT 1 AS x OFFSET 1", cat, "x"))
    // As union arms and as a CTE body.
    assertEquals(Seq(1, 2), column("SELECT 2 AS x UNION ALL SELECT 1 ORDER BY x", cat, "x"))
    assertEquals(Seq(3), column("WITH c AS (SELECT 3 AS v) SELECT v FROM c", cat, "v"))
    // No column name resolves, and a star has nothing to expand.
    val unknown = try { SqlEngine.execute("SELECT a", cat); null } catch { case e: IllegalArgumentException => e }
    assertNotNull(unknown)
    assertTrue(unknown.getMessage, unknown.getMessage.contains("Unknown column"))
    for (sql <- Seq("SELECT *", "SELECT *, 1 AS x")) {
      val star = try { SqlEngine.execute(sql, cat); null } catch { case e: IllegalArgumentException => e }
      assertNotNull(sql, star)
      assertTrue(star.getMessage, star.getMessage.contains("SELECT * needs a FROM clause"))
    }
    val tableStar = try { SqlEngine.execute("SELECT t.*", cat); null } catch { case e: IllegalArgumentException => e }
    assertNotNull(tableStar)
    assertTrue(tableStar.getMessage, tableStar.getMessage.contains("Unknown table in 't.*'"))
  }

  /** README listed CURRENT_DATE and CURRENT_TIMESTAMP, but JSqlParser parses
    * the keywords as a TimeKeyExpression the binder did not handle, so both
    * failed as an unsupported expression. They read one instant per statement,
    * in UTC. */
  @Test def currentDateAndTimestampReadOneInstantPerStatement(): Unit = {
    val schema = Schema(Vector(Field("v", DataType.IntType)))
    def batch(values: Seq[Int]): ColumnarBatch = {
      val b = new ColumnarBatch(schema, values.length)
      values.zipWithIndex.foreach { case (v, i) => b.column(0).setBoxed(i, java.lang.Integer.valueOf(v)) }
      b.setNumRows(values.length)
      b
    }
    // 4 partitions x 50 batches x 100 rows: reading the clock per row, or per
    // batch, would stamp them with many different values.
    val partitions = (0 until 4).map(p => (0 until 50).map(b => batch((0 until 100).map(i => p * 5000 + b * 100 + i))))
    val cat = catalogWith("m" -> new MaterializedView(schema, partitions))
    cat.register("tt", CsvReader.fromPath(tmpCsv("tt.csv",
      "d,ts\n2026-01-01,2026-01-01T05:30:21Z\n2999-01-01,2999-01-01T00:00:00Z\n").toString, CsvOptions()))

    val before = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS)
    val q = SqlEngine.execute("SELECT v, CURRENT_DATE AS d, CURRENT_TIMESTAMP AS ts FROM m", cat)
    assertEquals(Vector(DataType.IntType, DataType.DateType, DataType.TimestampType), q.schema.fields.map(_.dataType))
    val rows = collectAllRows(q)
    val after = java.time.Instant.now()
    assertEquals(20000, rows.size)
    val stamps = rows.map(_("ts")).distinct
    assertEquals(1, stamps.size)
    val ts = stamps.head.asInstanceOf[java.time.Instant]
    assertTrue(s"$ts is not between $before and $after", !ts.isBefore(before) && !ts.isAfter(after))
    // CURRENT_DATE is the same instant's date in UTC.
    assertEquals(Seq(java.time.LocalDate.ofInstant(ts, java.time.ZoneOffset.UTC)), rows.map(_("d")).distinct)

    // Every spelling JSqlParser has for the two keywords reads the same
    // instant, including `CURRENT_DATE ()`, which it parses as a call.
    val spelled = collectAllRows(SqlEngine.execute(
      "SELECT current_date AS a, CURRENT_DATE() AS b, CURRENT DATE AS c, CURRENT_DATE () AS e, " +
        "current_timestamp AS x, CURRENT_TIMESTAMP() AS y, CURRENT TIMESTAMP AS z, CURRENT_TIMESTAMP () AS w",
      cat)).head
    assertEquals(1, Seq(spelled("a"), spelled("b"), spelled("c"), spelled("e")).distinct.size)
    assertEquals(1, Seq(spelled("x"), spelled("y"), spelled("z"), spelled("w")).distinct.size)
    assertEquals(java.time.LocalDate.ofInstant(spelled("x").asInstanceOf[java.time.Instant], java.time.ZoneOffset.UTC),
      spelled("a"))
    // So does an inlined CTE body.
    assertEquals(Seq(true), column(
      "WITH c AS (SELECT CURRENT_TIMESTAMP AS ts) SELECT ts = CURRENT_TIMESTAMP AS same FROM c", cat, "same"))

    // Against stored dates and timestamps of the same type.
    assertEquals(Seq(java.time.LocalDate.parse("2026-01-01")), column("SELECT d FROM tt WHERE d < CURRENT_DATE", cat, "d"))
    assertEquals(Seq(java.time.Instant.parse("2999-01-01T00:00:00Z")),
      column("SELECT ts FROM tt WHERE ts > CURRENT_TIMESTAMP", cat, "ts"))
    val text = collectAllRows(SqlEngine.execute("SELECT CURRENT_DATE AS d, CAST(CURRENT_DATE AS STRING) AS s", cat)).head
    assertEquals(text("d").toString, text("s"))
    // There is no TIME type for CURRENT_TIME to return.
    assertRejected("SELECT CURRENT_TIME AS t FROM tt", cat, "CURRENT_TIME")
  }

  /** README listed TIMESTAMP among the CAST types, but CAST to TIMESTAMP threw
    * "not implemented", and so did every `TIMESTAMP '...'` literal, which
    * JSqlParser parses as that cast. */
  @Test def castToTimestampAndTimestampLiterals(): Unit = {
    val cat = new Catalog
    cat.register("tt", CsvReader.fromPath(tmpCsv("tt.csv",
      "d,ts\n2026-01-01,2026-01-01T05:30:21Z\n2999-01-01,2999-01-01T00:00:00Z\n").toString, CsvOptions()))
    def one(sql: String): Any = collectAllRows(SqlEngine.execute(sql, cat)).head("v")
    val at = java.time.Instant.parse("2026-01-01T05:30:21Z")
    // SQL's space before the time, ISO's T, a Z or an offset; no zone is UTC.
    assertEquals(at, one("SELECT TIMESTAMP '2026-01-01 05:30:21' AS v"))
    assertEquals(at, one("SELECT CAST('2026-01-01T05:30:21Z' AS TIMESTAMP) AS v"))
    assertEquals(at, one("SELECT CAST('2026-01-01T05:30:21' AS TIMESTAMP) AS v"))
    assertEquals(at, one("SELECT CAST('2026-01-01 07:30:21+02:00' AS TIMESTAMP) AS v"))
    assertEquals(at, one("SELECT {ts '2026-01-01 05:30:21'} AS v"))
    // A date alone is its midnight; a fraction keeps the microseconds a
    // TimestampVector holds.
    assertEquals(java.time.Instant.parse("2026-01-01T00:00:00Z"), one("SELECT CAST('2026-01-01' AS TIMESTAMP) AS v"))
    assertEquals(java.time.Instant.parse("2026-01-01T05:30:21.123456Z"),
      one("SELECT TIMESTAMP '2026-01-01 05:30:21.123456789' AS v"))
    // Between the two types: a timestamp's date in UTC, and a date's midnight.
    assertEquals(Seq(java.time.LocalDate.parse("2026-01-01"), java.time.LocalDate.parse("2999-01-01")),
      column("SELECT CAST(ts AS DATE) AS v FROM tt ORDER BY ts", cat, "v"))
    assertEquals(Seq(java.time.Instant.parse("2026-01-01T00:00:00Z"), java.time.Instant.parse("2999-01-01T00:00:00Z")),
      column("SELECT CAST(d AS TIMESTAMP) AS v FROM tt ORDER BY d", cat, "v"))
    assertEquals(java.time.LocalDate.parse("2026-01-01"),
      one("SELECT CAST(CAST('2026-01-01 23:59:59' AS TIMESTAMP) AS DATE) AS v"))
    assertEquals(true, one("SELECT CAST(CURRENT_TIMESTAMP AS DATE) = CURRENT_DATE AS v"))
    // A typed literal compares as a timestamp, within the day too.
    assertEquals(Seq(at), column("SELECT ts FROM tt WHERE ts < TIMESTAMP '2026-01-01 06:00:00'", cat, "ts"))
    // Mixing the two types takes a CAST that says which meaning is wanted.
    assertEquals(Seq(java.time.LocalDate.parse("2026-01-01")),
      column("SELECT d FROM tt WHERE CAST(d AS TIMESTAMP) < CURRENT_TIMESTAMP", cat, "d"))
    assertEquals(Seq(java.time.LocalDate.parse("2026-01-01")),
      column("SELECT d FROM tt WHERE CAST(d AS TIMESTAMP) = TIMESTAMP '2026-01-01 00:00:00'", cat, "d"))
    // A typed literal is read when the query is bound, so a bad one fails
    // before anything runs, naming the text.
    def bindError(sql: String): String =
      try { SqlEngine.execute(sql, cat); fail(s"$sql was accepted"); "" }
      catch { case e: IllegalArgumentException => e.getMessage }
    assertTrue(bindError("SELECT TIMESTAMP 'noon' AS v").contains("Cannot cast 'noon' to timestamp"))
    assertTrue(bindError("SELECT ts FROM tt WHERE 1 = 0 AND ts < CAST('noon' AS TIMESTAMP)")
      .contains("Cannot cast 'noon' to timestamp"))
    // DATE reads an ISO date and nothing more: where a time of day falls
    // depends on its zone, so that goes through TIMESTAMP.
    assertTrue(bindError("SELECT DATE '2026-1-5' AS v").contains("Cannot cast '2026-1-5' to date"))
    assertTrue(bindError("SELECT CAST('2026-01-01 23:59:59' AS DATE) AS v")
      .contains("cast it to TIMESTAMP first, then to DATE"))
  }

  /** A DATE or TIMESTAMP meets another type only as a string literal, read as
    * that type when the query is bound; anything else is an error naming the
    * CAST to write. A string beside a timestamp used to compare as text, so the
    * `T` of `2026-01-01T05:30:21Z` met the space of a SQL-style literal and
    * decided the order. */
  @Test def temporalComparisonsAreTypedOrRejected(): Unit = {
    val cat = new Catalog
    cat.register("tt", CsvReader.fromPath(tmpCsv("tt.csv",
      "d,ts,s\n2026-01-01,2026-01-01T05:30:21Z,first day\n2999-01-01,2999-01-01T00:00:00Z,last day\n").toString,
      CsvOptions()))
    val early = java.time.Instant.parse("2026-01-01T05:30:21Z")
    val late = java.time.Instant.parse("2999-01-01T00:00:00Z")
    // Within the day. These found no row, the late one, and no row.
    assertEquals(Seq(early), column("SELECT ts FROM tt WHERE ts < '2026-01-01 06:00:00'", cat, "ts"))
    assertEquals(Seq(late), column("SELECT ts FROM tt WHERE ts > '2026-01-01 06:00:00'", cat, "ts"))
    assertEquals(Seq(early),
      column("SELECT ts FROM tt WHERE ts BETWEEN '2026-01-01 00:00:00' AND '2026-01-01 06:00:00'", cat, "ts"))
    // A date alone is midnight beside a timestamp, as in a CAST.
    assertEquals(Seq(early), column("SELECT ts FROM tt WHERE ts < '2026-01-02'", cat, "ts"))
    // Every place a pair of operands is unified: a CASE switch and an IN list.
    assertEquals(Seq("first", "other"),
      column("SELECT CASE d WHEN '2026-01-01' THEN 'first' ELSE 'other' END AS c FROM tt ORDER BY d", cat, "c"))
    assertEquals(Seq(early), column("SELECT ts FROM tt WHERE ts IN ('2026-01-01 05:30:21', '2026-06-01')", cat, "ts"))
    assertEquals(Seq(java.time.LocalDate.parse("2026-01-01")),
      column("SELECT d FROM tt WHERE d IN ('2026-01-01', '2026-06-01')", cat, "d"))
    assertEquals(Seq(java.time.LocalDate.parse("2026-01-01")), column("SELECT d FROM tt WHERE d = '2026-01-01'", cat, "d"))

    def bindError(sql: String): String =
      try { SqlEngine.execute(sql, cat); fail(s"$sql was accepted"); "" }
      catch { case e: IllegalArgumentException => e.getMessage }
    // A literal that is not one is an error at bind time, rows or no rows.
    // These matched nothing, or compared as text.
    assertTrue(bindError("SELECT ts FROM tt WHERE ts < 'noon'").contains("Cannot cast 'noon' to timestamp"))
    assertTrue(bindError("SELECT d FROM tt WHERE 1 = 0 AND d = '2026-1-5'").contains("Cannot cast '2026-1-5' to date"))
    assertTrue(bindError("SELECT d FROM tt WHERE d = '2026-01-01 00:00:00'").contains("Cannot cast '2026-01-01 00:00:00' to date"))
    // A string that is not a literal needs a CAST to say which comparison it means.
    for (sql <- Seq("SELECT d FROM tt WHERE d = s", "SELECT d FROM tt WHERE s < d", "SELECT d FROM tt WHERE d IN (s)"))
      assertTrue(sql, bindError(sql).contains("Cannot compare DATE with STRING; only a string literal"))
    // So does a DATE beside a TIMESTAMP: a midnight, or a date in UTC.
    for (sql <- Seq("SELECT d FROM tt WHERE d = ts", "SELECT d FROM tt WHERE d < CURRENT_TIMESTAMP",
                    "SELECT d FROM tt WHERE d IN (TIMESTAMP '2026-01-01 00:00:00')",
                    "SELECT ts FROM tt WHERE ts BETWEEN DATE '2026-01-01' AND DATE '2026-01-02'"))
      assertTrue(sql, bindError(sql).contains("CAST one side: a DATE as a TIMESTAMP is its midnight"))
    // And a number is neither, in a comparison or a CAST.
    assertTrue(bindError("SELECT d FROM tt WHERE d = 20260101").contains("Cannot compare DATE with INT"))
    assertTrue(bindError("SELECT CAST(1 AS DATE) AS v").contains("Cannot cast INT to DATE"))
    assertTrue(bindError("SELECT CAST(ts AS BIGINT) AS v FROM tt").contains("Cannot cast TIMESTAMP to LONG"))
    // There is no interval type for arithmetic to produce.
    assertTrue(bindError("SELECT d + 1 AS v FROM tt").contains("Arithmetic on DATE is not supported"))
    assertTrue(bindError("SELECT ts - ts AS v FROM tt").contains("Arithmetic on TIMESTAMP is not supported"))
    assertTrue(bindError("SELECT -d AS v FROM tt").contains("Arithmetic on DATE is not supported"))
  }

  /** The rule a date follows holds for every type: a string literal beside a
    * number or a boolean is read as that type when the query is bound, and a
    * string expression beside one is an error naming the CAST to write. Both
    * used to compare as text, so `9` met `'10'` as `'9'` and sorted after it. */
  @Test def numberAndBooleanComparisonsAreTypedOrRejected(): Unit = {
    val cat = new Catalog
    cat.register("tn", CsvReader.fromPath(tmpCsv("tn.csv",
      "i,l,d,b,s\n9,9000000000,1.0,true,9\n10,10000000000,2.5,false,x10\n").toString, CsvOptions()))
    def col(sql: String): Seq[Any] = column(sql, cat, "v")
    // Each of these found no row as text.
    assertEquals(Seq(9), col("SELECT i AS v FROM tn WHERE i < '10'"))
    assertEquals(Seq(9), col("SELECT i AS v FROM tn WHERE '10' > i"))
    assertEquals(Seq(9000000000L), col("SELECT l AS v FROM tn WHERE l < '10000000000'"))
    assertEquals(Seq(1.0), col("SELECT d AS v FROM tn WHERE d = '1'"))
    assertEquals(Seq(true), col("SELECT b AS v FROM tn WHERE b = 'TRUE'"))
    assertEquals(Seq(false), col("SELECT b AS v FROM tn GROUP BY b HAVING SUM(i) > '9'"))
    // These found the 10 alone, both rows, and no 'one'.
    assertEquals(Seq(9, 10), col("SELECT i AS v FROM tn WHERE i BETWEEN '1' AND '10' ORDER BY i"))
    assertEquals(Seq(true), col("SELECT b AS v FROM tn WHERE b <> 'False'"))
    assertEquals(Seq("one", "other"),
      col("SELECT CASE d WHEN '1' THEN 'one' ELSE 'other' END AS v FROM tn ORDER BY d"))
    // An IN list takes the widest type among its operands. It cast its items
    // to the left side's type instead, so 9.5 and 2^32 + 9 both matched 9.
    assertEquals(Seq(), col("SELECT i AS v FROM tn WHERE i IN (9.5)"))
    assertEquals(Seq(), col("SELECT i AS v FROM tn WHERE i IN (4294967305)"))
    assertEquals(Seq(10), col("SELECT i AS v FROM tn WHERE i IN (9.5, '10')"))
    // Arithmetic reads the literal the same way; this failed mid-drain.
    assertEquals(Seq(10, 11), col("SELECT i + '1' AS v FROM tn ORDER BY i"))
    // The CAST the errors below name compares text on purpose.
    assertEquals(Seq(9), col("SELECT i AS v FROM tn WHERE CAST(i AS STRING) = s"))

    def bindError(sql: String): String =
      try { SqlEngine.execute(sql, cat); fail(s"$sql was accepted"); "" }
      catch { case e: IllegalArgumentException => e.getMessage }
    // A literal that is no such value fails at bind time, rows or no rows, and
    // an INT reads an integer only.
    assertTrue(bindError("SELECT i FROM tn WHERE i < 'ten'").contains("Cannot cast 'ten' to int"))
    assertTrue(bindError("SELECT i FROM tn WHERE 1 = 0 AND d = 'x'").contains("Cannot cast 'x' to double"))
    assertTrue(bindError("SELECT i FROM tn WHERE i = '9.5'").contains("Cannot cast '9.5' to int"))
    assertTrue(bindError("SELECT i FROM tn WHERE i IN ('9', 'ten')").contains("Cannot cast 'ten' to int"))
    assertTrue(bindError("SELECT b FROM tn WHERE b = 'yes'").contains("Cannot cast 'yes' to boolean"))
    // A string column needs a CAST to say whether numbers or text are meant,
    // in every place operands meet.
    for (sql <- Seq("SELECT s FROM tn WHERE s = 9", "SELECT s FROM tn WHERE 9 < s",
                    "SELECT s FROM tn WHERE s IN (9, 10)", "SELECT i FROM tn WHERE i IN (s)",
                    "SELECT s FROM tn WHERE s BETWEEN 1 AND 10",
                    "SELECT CASE s WHEN 9 THEN 'nine' END AS c FROM tn",
                    "SELECT x.i FROM tn x JOIN tn y ON x.i = y.s"))
      assertTrue(sql, bindError(sql).contains("Cannot compare INT with STRING; only a string literal is read as an INT"))
    assertTrue(bindError("SELECT b FROM tn WHERE b = s")
      .contains("Cannot compare BOOLEAN with STRING; only a string literal is read as a BOOLEAN"))
    // A boolean is no number, until a CAST says it is 1 or 0. These found no row.
    assertTrue(bindError("SELECT b FROM tn WHERE b = 1")
      .contains("Cannot compare BOOLEAN with INT; CAST the BOOLEAN to INT for 1 and 0"))
    assertTrue(bindError("SELECT i FROM tn WHERE i IN (TRUE)")
      .contains("Cannot compare INT with BOOLEAN; CAST the BOOLEAN to INT for 1 and 0"))
    assertEquals(Seq(true), col("SELECT b AS v FROM tn WHERE CAST(b AS INT) = 1"))
    // Arithmetic is on numbers; each of these failed mid-drain.
    for (sql <- Seq("SELECT s + 1 AS v FROM tn", "SELECT '1' + '2' AS v", "SELECT -s AS v FROM tn"))
      assertTrue(sql, bindError(sql).contains("Arithmetic on STRING is not supported; CAST it to a number"))
    for (sql <- Seq("SELECT b + 1 AS v FROM tn", "SELECT -b AS v FROM tn"))
      assertTrue(sql, bindError(sql).contains("Arithmetic on BOOLEAN is not supported"))
  }

  /** CAST reads text as a number or a boolean the way a comparison reads a
    * literal: a string literal when the query is bound, and a string column
    * with a clean error naming the text, where it threw NumberFormatException
    * mid-drain and read any text but `true` as false. */
  @Test def castReadsNumbersAndBooleansStrictly(): Unit = {
    val cat = new Catalog
    cat.register("tn", CsvReader.fromPath(tmpCsv("tn.csv", "s\n9\nx10\n").toString, CsvOptions()))
    def one(sql: String): Any = collectAllRows(SqlEngine.execute(sql, cat)).head("v")
    assertEquals(9, one("SELECT CAST('9' AS INT) AS v"))
    assertEquals(true, one("SELECT CAST('TRUE' AS BOOLEAN) AS v"))
    assertEquals(false, one("SELECT CAST('false' AS BOOLEAN) AS v"))

    def bindError(sql: String): String =
      try { SqlEngine.execute(sql, cat); fail(s"$sql was accepted"); "" }
      catch { case e: IllegalArgumentException => e.getMessage }
    assertTrue(bindError("SELECT s FROM tn WHERE 1 = 0 AND CAST('ten' AS INT) > 0").contains("Cannot cast 'ten' to int"))
    assertTrue(bindError("SELECT CAST('yes' AS BOOLEAN) AS v").contains("Cannot cast 'yes' to boolean"))

    // Text in a column is read as it arrives, so its error comes while the
    // result drains, as an IllegalArgumentException naming the text.
    def drainError(sql: String): IllegalArgumentException =
      try { collectAllRows(SqlEngine.execute(sql, cat)); fail(s"$sql drained"); null }
      catch { case e: IllegalArgumentException => e }
    for ((sql, text) <- Seq(
           "SELECT CAST(s AS INT) AS v FROM tn" -> "Cannot cast 'x10' to int",
           "SELECT CAST(s AS BIGINT) AS v FROM tn" -> "Cannot cast 'x10' to long",
           "SELECT CAST(s AS DOUBLE) AS v FROM tn" -> "Cannot cast 'x10' to double",
           "SELECT CAST(s AS BOOLEAN) AS v FROM tn" -> "Cannot cast '9' to boolean")) {
      val e = drainError(sql)
      assertEquals(sql, classOf[IllegalArgumentException], e.getClass)
      assertTrue(sql, e.getMessage.contains(text))
    }
  }

  @Test def groupingSetsRejectedWithoutLeakingAnInternalName(): Unit = {
    // Used to fail binding with "Unknown column 'g'. Available: [_agg0]".
    val cat = clauseCatalog()
    val sql = "SELECT g, SUM(a) FROM tg GROUP BY GROUPING SETS ((g), ())"
    assertRejected(sql, cat, "GROUPING SETS")
    val ex = try { SqlEngine.execute(sql, cat); null } catch { case e: IllegalArgumentException => e }
    assertFalse(ex.getMessage, ex.getMessage.contains("_agg"))
  }

  @Test def otherDroppedClausesRejected(): Unit = {
    val cat = clauseCatalog()
    val cases = Seq(
      // All four rows, as if the clause were absent.
      ("SELECT a, ROW_NUMBER() OVER (ORDER BY a) AS n FROM t QUALIFY n = 1", "QUALIFY"),
      // Plain DISTINCT, four rows instead of one per g.
      ("SELECT DISTINCT ON (g) a FROM tg", "DISTINCT ON"),
      // A plain LAG: (NULL)(Ab)(NULL)(xy) instead of skipping the NULL.
      ("SELECT id, LAG(s) IGNORE NULLS OVER (ORDER BY id) AS l FROM ts", "IGNORE NULLS"),
      // No rollup row.
      ("SELECT g, SUM(a) FROM tg GROUP BY g WITH ROLLUP", "WITH ROLLUP"),
      // Returned the rows and created no table.
      ("SELECT a INTO newtab FROM t", "SELECT ... INTO"),
      // The window specification was dropped, so the numbering followed scan order.
      ("SELECT a, ROW_NUMBER() OVER w AS n FROM t WINDOW w AS (ORDER BY a DESC)", "WINDOW <name> AS"),
      // NOTNULL ran as IS NULL: (2), not (1)(3)(4).
      ("SELECT id FROM ts WHERE s NOTNULL", "NOTNULL"),
      // RLIKE ran as LIKE, matching 'A.b' literally.
      ("SELECT id FROM ts WHERE s RLIKE 'A.b'", "RLIKE"),
      // ORDER BY and LIMIT after the parentheses were both dropped.
      ("(SELECT a FROM t) ORDER BY a DESC LIMIT 1", "after a parenthesized query"),
      // The pivot, the column exclusion and the hierarchy were all dropped.
      ("SELECT * FROM tg PIVOT (SUM(a) FOR g IN ('x', 'y'))", "PIVOT"),
      ("SELECT * EXCEPT (id) FROM ts", "* EXCEPT"),
      ("SELECT a FROM t START WITH a = 1 CONNECT BY PRIOR a = a", "CONNECT BY"),
      // UNIQUE was dropped: 4, not 3.
      ("SELECT COUNT(UNIQUE a) FROM t", "COUNT(UNIQUE ...)"),
      // Ran as a running SUM over the WITHIN GROUP order: (1)(3)(5)(8).
      ("SELECT SUM(a) WITHIN GROUP (ORDER BY a) FROM t", "WITHIN GROUP"),
      // The argument and the second argument were ignored.
      ("SELECT ROW_NUMBER(a) OVER (ORDER BY a) FROM t", "ROW_NUMBER with an argument"),
      ("SELECT SUM(a, 2) OVER () FROM t", "SUM with more than one argument"),
      // The array type and the subscript were dropped.
      ("SELECT CAST(a AS INT[]) FROM t", "array type"),
      ("SELECT ts.s[1] FROM ts", "array subscript"),
      // The schema was dropped and the name resolved to t.
      ("SELECT a FROM other.t", "qualified table name"))
    cases.foreach { case (sql, clause) => assertRejected(sql, cat, clause) }
  }

  // ---------------------------------------------------------------------------
  // BINARY compares by content and DECIMAL by value
  //
  // `Ops.eq` / `Ops.cmp` dispatched on the boxed value's class. A byte array is
  // neither a Number nor Comparable, so `=` was reference equality and ORDER BY
  // compared identity-hash strings. A BigDecimal is a Number, so both went
  // through `doubleValue` and lost every digit past double's 53-bit mantissa.
  // The hash side disagreed with both, in opposite directions: GROUP BY
  // collapsed byte arrays `=` called different, and split 1.0 from 1.00, which
  // `=` called equal. The comment on each assertion records the old answer.
  // ---------------------------------------------------------------------------

  private def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
  private def dec(s: String): java.math.BigDecimal = new java.math.BigDecimal(s)

  /** A byte array as hex text, so a cell that holds one compares by content. */
  private def hex(v: Any): Any = v match {
    case a: Array[Byte] => a.map(b => f"${b & 0xff}%02x").mkString
    case other => other
  }

  /** An in-memory view with one inner Seq of rows per partition. */
  private def memView(schema: Schema, partitions: Seq[Seq[Array[Any]]]): MaterializedView =
    new MaterializedView(schema, partitions.map { rows =>
      val b = new ColumnarBatch(schema, math.max(1, rows.length))
      rows.zipWithIndex.foreach { case (row, r) =>
        row.zipWithIndex.foreach { case (v, c) =>
          if (v == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, v)
        }
      }
      b.setNumRows(rows.length)
      IndexedSeq(b)
    }.toIndexedSeq)

  /** `tb` holds equal bytes in `b` and `b2` as distinct arrays, so reference
    * equality and identity-hash order both show. `tu` holds bytes whose signed
    * and unsigned orders differ, the empty array, and a prefix of a longer
    * array; `tp` four values one per partition, so MIN and MAX meet them in
    * merge; `tg` two columns for GREATEST and LEAST. */
  private def binaryCatalog(): Catalog = {
    val c = new Catalog
    val tb = Schema(Vector(Field("id", DataType.IntType), Field("b", DataType.BinaryType),
      Field("b2", DataType.BinaryType)))
    c.register("tb", memView(tb, Seq(Seq(
      Array[Any](1, bytes(3, 3), bytes(3, 3)),
      Array[Any](2, bytes(1, 1), bytes(1, 1)),
      Array[Any](3, bytes(2, 2), bytes(2, 2)),
      Array[Any](4, bytes(1, 1), bytes(1, 1))))))
    val tu = Schema(Vector(Field("id", DataType.IntType), Field("b", DataType.BinaryType)))
    c.register("tu", memView(tu, Seq(Seq(
      Array[Any](1, bytes(0xff)), Array[Any](2, bytes(0x7f)), Array[Any](3, bytes(0x80)),
      Array[Any](4, bytes()), Array[Any](5, bytes(0x00)), Array[Any](6, bytes(0x7f, 0x00))))))
    c.register("tp", memView(tu,
      Seq(bytes(2, 2), bytes(3, 3), bytes(1, 1), bytes(0x80)).zipWithIndex.map { case (v, i) =>
        Seq(Array[Any](i, v))
      }))
    c.register("tg", memView(tb, Seq(Seq(
      Array[Any](1, bytes(0x80), bytes(0x7f)), Array[Any](2, bytes(0x00), bytes(0xff))))))
    c
  }

  @Test def binaryEqualityComparesContent(): Unit = {
    val cat = binaryCatalog()
    def ids(sql: String): Seq[Any] = column(sql, cat, "id")
    // Each of these found no row, or every row.
    assertEquals(Seq(1, 2, 3, 4), ids("SELECT id FROM tb WHERE b = b2 ORDER BY id"))
    assertEquals(Seq(), ids("SELECT id FROM tb WHERE b <> b2"))
    assertEquals(Seq(1, 2, 3, 4), ids("SELECT id FROM tb WHERE b IN (b2) ORDER BY id"))
    assertEquals(Seq(1, 2, 3, 4), ids("SELECT id FROM tb WHERE b BETWEEN b2 AND b2 ORDER BY id"))
    // NULLIF returned b on every row.
    assertEquals(Seq(null, null, null, null), column("SELECT NULLIF(b, b2) AS v FROM tb", cat, "v"))
    // The same join matched six pairs as a hash key and none as a residual
    // predicate.
    def count(sql: String): Any = column(sql, cat, "n").head
    assertEquals(6L, count("SELECT COUNT(*) AS n FROM tb x JOIN tb y ON x.b = y.b2"))
    assertEquals(6L, count("SELECT COUNT(*) AS n FROM tb x JOIN tb y ON x.b <= y.b2 AND x.b >= y.b2"))
  }

  @Test def binaryOrdersByUnsignedBytes(): Unit = {
    val cat = binaryCatalog()
    def hexes(sql: String, name: String): Seq[Any] = column(sql, cat, name).map(hex)
    // Identity-hash order: 03 03 first one run, 02 02 the next.
    assertEquals(Seq("0101", "0101", "0202", "0303"), hexes("SELECT b FROM tb ORDER BY b", "b"))
    assertEquals(Seq("0303", "0202", "0101", "0101"), hexes("SELECT b FROM tb ORDER BY b DESC", "b"))
    // A byte is unsigned, so 80 follows 7f; a prefix sorts before the longer
    // array, and nothing sorts before the empty one.
    assertEquals(Seq("", "00", "7f", "7f00", "80", "ff"), hexes("SELECT b FROM tu ORDER BY b", "b"))
    // MIN and MAX followed row order, and partition order where each value
    // arrived through merge: (01 01, 01 01), (80, 7f) and (03 03, 02 02).
    def extrema(table: String): (Any, Any) = {
      val row = collectAllRows(SqlEngine.execute(s"SELECT MIN(b) AS lo, MAX(b) AS hi FROM $table", cat)).head
      (hex(row("lo")), hex(row("hi")))
    }
    assertEquals(("0101", "0303"), extrema("tb"))
    assertEquals(("", "ff"), extrema("tu"))
    assertEquals(("0101", "80"), extrema("tp"))
    assertEquals(Seq("80", "ff"), hexes("SELECT GREATEST(b, b2) AS v FROM tg ORDER BY id", "v"))
    assertEquals(Seq("7f", "00"), hexes("SELECT LEAST(b, b2) AS v FROM tg ORDER BY id", "v"))
    // The rows RANK ties are the ones the sort ties: (1,1) (2,3) (3,2) (4,4).
    val ranks = collectAllRows(SqlEngine.execute("SELECT id, RANK() OVER (ORDER BY b) AS r FROM tb", cat))
      .map(r => r("id") -> r("r")).toMap
    assertEquals(Map(2 -> 1L, 4 -> 1L, 3 -> 3L, 1 -> 4L), ranks)
  }

  /** `td` holds two decimals one apart, past double's 53-bit mantissa, and
    * `tk` three such values ascending (`tkr` descending), in one partition and
    * one per partition (`tkp`, `tkpr`). `ts` holds one value at four scales
    * and 2.50 in a DECIMAL(10,3) column, `ts2` 1.00 and 2.5, and `ti` a
    * DECIMAL beside an INT and a DOUBLE. */
  private def decimalCatalog(): Catalog = {
    val c = new Catalog
    val big = DataType.DecimalType(38, 0)
    c.register("td", memView(Schema(Vector(Field("a", big), Field("b", big))),
      Seq(Seq(Array[Any](dec("10000000000000000001"), dec("10000000000000000002"))))))
    val ks = Seq("10000000000000000001", "10000000000000000002", "10000000000000000003").map(dec)
    val k = Schema(Vector(Field("k", big)))
    c.register("tk", memView(k, Seq(ks.map(v => Array[Any](v)))))
    c.register("tkr", memView(k, Seq(ks.reverse.map(v => Array[Any](v)))))
    c.register("tkp", memView(k, ks.map(v => Seq(Array[Any](v)))))
    c.register("tkpr", memView(k, ks.reverse.map(v => Seq(Array[Any](v)))))
    val scaled = DataType.DecimalType(10, 3)
    val ts = Schema(Vector(Field("id", DataType.IntType), Field("k", scaled)))
    c.register("ts", memView(ts, Seq(Seq(
      Array[Any](1, dec("1.0")), Array[Any](2, dec("1.00")), Array[Any](3, dec("1.000")),
      Array[Any](4, dec("1")), Array[Any](5, dec("2.50"))))))
    c.register("ts2", memView(ts, Seq(Seq(Array[Any](10, dec("1.00")), Array[Any](11, dec("2.5"))))))
    val ti = Schema(Vector(Field("id", DataType.IntType), Field("k", scaled),
      Field("i", DataType.IntType), Field("d", DataType.DoubleType)))
    c.register("ti", memView(ti, Seq(Seq(
      Array[Any](1, dec("1.00"), 1, 1.0), Array[Any](2, dec("2.50"), 3, 2.5),
      Array[Any](3, dec("0.10"), 0, 0.1)))))
    c.register("tnan", memView(ti, Seq(Seq(Array[Any](1, dec("1.00"), 1, Double.NaN)))))
    c
  }

  /** Decimals equal by value, whatever their scale. */
  private def assertSameDecimals(label: String, expected: Seq[String], actual: Seq[Any]): Unit = {
    assertEquals(label, expected.length, actual.length)
    expected.zip(actual).foreach { case (e, a) =>
      assertTrue(s"$label: expected $e, got $a", dec(e).compareTo(a.asInstanceOf[java.math.BigDecimal]) == 0)
    }
  }

  @Test def decimalComparesByValueAtFullPrecision(): Unit = {
    val cat = decimalCatalog()
    // A match, and no match: both sides were 1.0E19 as doubles.
    assertEquals(Seq(), column("SELECT a FROM td WHERE a = b", cat, "a"))
    assertEquals(Seq(dec("10000000000000000001")), column("SELECT a FROM td WHERE a < b", cat, "a"))
    assertEquals(Seq(dec("10000000000000000001")), column("SELECT a FROM td WHERE a IN (b, a)", cat, "a"))
    assertEquals(Seq(), column("SELECT a FROM td WHERE a IN (b)", cat, "a"))
    assertEquals(Seq(dec("10000000000000000001")), column("SELECT NULLIF(a, b) AS v FROM td", cat, "v"))
    assertEquals(Seq(dec("10000000000000000002")), column("SELECT GREATEST(a, b) AS v FROM td", cat, "v"))
    // (001, 001) ascending and (003, 003) descending, in one partition and
    // across three: the first value seen won every comparison.
    for (t <- Seq("tk", "tkr", "tkp", "tkpr")) {
      val row = collectAllRows(SqlEngine.execute(s"SELECT MIN(k) AS lo, MAX(k) AS hi FROM $t", cat)).head
      assertEquals(t, (dec("10000000000000000001"), dec("10000000000000000003")), (row("lo"), row("hi")))
    }
    // Every row tied: input order, and one rank for all three.
    assertEquals(Seq("10000000000000000001", "10000000000000000002", "10000000000000000003").map(dec),
      column("SELECT k FROM tkr ORDER BY k", cat, "k"))
    assertEquals(Seq(1L, 2L, 3L), column("SELECT RANK() OVER (ORDER BY k) AS r FROM tkr ORDER BY k", cat, "r"))
    // Scale is not part of the value: 1.0, 1.00, 1.000 and 1 are peers.
    assertEquals(Seq(1L, 1L, 1L, 1L, 5L), column("SELECT RANK() OVER (ORDER BY k) AS r FROM ts ORDER BY id", cat, "r"))
  }

  /** Numbers meet a DECIMAL by widening to it, as they meet any wider number:
    * an integer exactly, and a FLOAT or DOUBLE as the decimal it prints as.
    * Each of these threw "CAST to decimal(10,3) not implemented" mid-drain. */
  @Test def decimalMeetsOtherNumbersByWidening(): Unit = {
    val cat = decimalCatalog()
    def ids(sql: String): Seq[Any] = column(sql, cat, "id")
    assertEquals(Seq(1), ids("SELECT id FROM ti WHERE k = 1"))
    assertEquals(Seq(1), ids("SELECT id FROM ti WHERE k = i"))
    assertEquals(Seq(2), ids("SELECT id FROM ti WHERE k = 2.5"))
    assertEquals(Seq(3), ids("SELECT id FROM ti WHERE k = 0.1"))
    assertEquals(Seq(1, 2, 3), ids("SELECT id FROM ti WHERE k = d ORDER BY id"))
    assertEquals(Seq(1, 2), ids("SELECT id FROM ti WHERE k IN (1, 2.5) ORDER BY id"))
    assertEquals(Seq(1, 2), ids("SELECT id FROM ti WHERE k BETWEEN 0 AND i ORDER BY id"))
    assertEquals(Seq(2), ids("SELECT id FROM ti WHERE CASE k WHEN 2.5 THEN TRUE ELSE FALSE END"))
    assertSameDecimals("GREATEST", Seq("1", "3", "0.1"),
      column("SELECT GREATEST(k, i) AS v FROM ti ORDER BY id", cat, "v"))
    // A BIGINT widens exactly, where a double would have rounded.
    assertEquals(Seq(dec("10000000000000000001")),
      column("SELECT a FROM td WHERE a > 9223372036854775807", cat, "a"))

    // NaN has no decimal value: an error naming it, not a guess at an order.
    val drained = try { collectAllRows(SqlEngine.execute("SELECT id FROM tnan WHERE k = d", cat)); null }
      catch { case e: IllegalArgumentException => e }
    assertNotNull("a NaN met a decimal without an error", drained)
    assertTrue(drained.getMessage, drained.getMessage.contains("Cannot cast NaN to decimal(10,3)"))
  }

  /** GROUP BY, DISTINCT, UNION, COUNT(DISTINCT), PARTITION BY and an equi-join
    * key decide equality the way `=` does. A decimal key comes back at its
    * column's declared scale, and so do MIN and MAX, so neither depends on
    * which row arrived first. */
  @Test def groupingAgreesWithEqualityForBinaryAndDecimal(): Unit = {
    val cat = decimalCatalog()
    // Five groups, one per scale.
    val groups = collectAllRows(SqlEngine.execute("SELECT k, COUNT(*) AS n FROM ts GROUP BY k ORDER BY k", cat))
    assertEquals(Seq((dec("1.000"), 4L), (dec("2.500"), 1L)), groups.map(r => (r("k"), r("n"))))
    // 5, five rows, and six rows.
    assertEquals(Seq(2L), column("SELECT COUNT(DISTINCT k) AS n FROM ts", cat, "n"))
    assertEquals(Seq(dec("1.000"), dec("2.500")), column("SELECT DISTINCT k FROM ts ORDER BY k", cat, "k"))
    assertEquals(Seq(dec("1.000"), dec("2.500")),
      column("SELECT k FROM ts UNION SELECT k FROM ts2", cat, "k")
        .sortWith((a, b) => a.asInstanceOf[java.math.BigDecimal].compareTo(b.asInstanceOf[java.math.BigDecimal]) < 0))
    // Each row's window partition held only that row.
    assertEquals(Seq(4L, 4L, 4L, 4L, 1L),
      column("SELECT id, COUNT(*) OVER (PARTITION BY k) AS n FROM ts ORDER BY id", cat, "n"))
    // The hash key matched 1.00 alone, and the residual predicate every row.
    assertEquals(Seq(1, 2, 3, 4, 5), column("SELECT x.id FROM ts x JOIN ts2 y ON x.k = y.k ORDER BY x.id", cat, "id"))
    assertEquals(Seq(1, 2, 3, 4, 5),
      column("SELECT x.id FROM ts x JOIN ts2 y ON x.k <= y.k AND x.k >= y.k ORDER BY x.id", cat, "id"))
    val minMax = collectAllRows(SqlEngine.execute("SELECT MIN(k) AS lo, MAX(k) AS hi FROM ts", cat)).head
    assertEquals((dec("1.000"), dec("2.500")), (minMax("lo"), minMax("hi")))
    assertEquals(Seq(dec("1.000")), column("SELECT MIN(DISTINCT k) AS v FROM ts", cat, "v"))

    val bcat = binaryCatalog()
    // COUNT(DISTINCT) counted array objects: 4.
    assertEquals(Seq(3L), column("SELECT COUNT(DISTINCT b) AS n FROM tb", bcat, "n"))
    val bgroups = collectAllRows(SqlEngine.execute("SELECT b, COUNT(*) AS n FROM tb GROUP BY b", bcat))
    assertEquals(Map("0101" -> 2L, "0202" -> 1L, "0303" -> 1L), bgroups.map(r => hex(r("b")) -> r("n")).toMap)
    assertEquals(Seq("0101", "0202", "0303"), column("SELECT DISTINCT b FROM tb ORDER BY b", bcat, "b").map(hex))
    assertEquals(Seq(2L, 2L), column("SELECT id, COUNT(*) OVER (PARTITION BY b) AS n FROM tb WHERE id IN (2, 4)", bcat, "n"))
  }

  /** ABS of a decimal is the decimal without its sign, exactly and at the
    * value's own scale. It computed in DOUBLE: each value lost the digits past
    * double's precision and read back as a Double under a DECIMAL type, and MAX
    * over it came back NULL, because MIN / MAX picks its accumulator slot from
    * the type and the vector filled the other one. The plan 04 corpus found it
    * (seed 98 of `expr_parity_fuzz_test`). */
  @Test def absOfADecimalIsExact(): Unit = {
    val cat = new Catalog
    val schema = Schema(Vector(Field("id", DataType.IntType), Field("k", DataType.DecimalType(38, 18))))
    cat.register("tn", memView(schema, Seq(Seq(
      Array[Any](1, dec("-12345678901234567890.123456789012345678")), Array[Any](2, dec("-2.50")),
      Array[Any](3, dec("1.000000000000000001")), Array[Any](4, null)))))
    // 1.2345678901234567E19, 2.5, 1.0 and NULL, as Doubles.
    assertEquals(Seq(dec("12345678901234567890.123456789012345678"), dec("2.50"), dec("1.000000000000000001"), null),
      column("SELECT ABS(k) AS v FROM tn ORDER BY id", cat, "v"))
    // NULL and NULL.
    val extrema = collectAllRows(SqlEngine.execute("SELECT MIN(ABS(k)) AS lo, MAX(ABS(k)) AS hi FROM tn", cat)).head
    assertSameDecimals("MIN / MAX of ABS", Seq("1.000000000000000001", "12345678901234567890.123456789012345678"),
      Seq(extrema("lo"), extrema("hi")))
    // Missed 3: its ABS was 1.0, which is not more than 1.
    assertEquals(Seq(1, 2, 3), column("SELECT id FROM tn WHERE ABS(k) > 1 ORDER BY id", cat, "id"))
  }

  /** Two BIGINTs compare as longs. They compared as doubles, which cannot
    * tell 2^53 from 2^53 + 1, so `=` matched them while GROUP BY and a hash
    * join (which key exactly) kept them apart. */
  @Test def bigintComparesExactlyPastDoublePrecision(): Unit = {
    val cat = new Catalog
    val schema = Schema(Vector(Field("id", DataType.IntType), Field("a", DataType.LongType),
      Field("b", DataType.LongType)))
    cat.register("tl", memView(schema, Seq(Seq(
      Array[Any](1, 9007199254740993L, 9007199254740992L),
      Array[Any](2, 9007199254740992L, 9007199254740993L),
      Array[Any](3, 9007199254740995L, 9007199254740994L)))))
    def ids(sql: String): Seq[Any] = column(sql, cat, "id")
    // (1)(2), no row, and (1)(2).
    assertEquals(Seq(), ids("SELECT id FROM tl WHERE a = b"))
    assertEquals(Seq(2), ids("SELECT id FROM tl WHERE a < b"))
    assertEquals(Seq(2), ids("SELECT id FROM tl WHERE a = 9007199254740992"))
    assertEquals(Seq(2), ids("SELECT id FROM tl WHERE a IN (9007199254740992, 7)"))
    assertEquals(Seq(2), ids("SELECT id FROM tl WHERE a BETWEEN 9007199254740991 AND 9007199254740992"))
    assertEquals(Seq(9007199254740993L, 9007199254740992L, 9007199254740995L),
      column("SELECT NULLIF(a, b) AS v FROM tl ORDER BY id", cat, "v"))
    assertEquals(Seq(9007199254740993L, 9007199254740993L, 9007199254740995L),
      column("SELECT GREATEST(a, b) AS v FROM tl ORDER BY id", cat, "v"))
    // Scan order, and ranks 1, 1, 3.
    assertEquals(Seq(9007199254740992L, 9007199254740993L, 9007199254740995L),
      column("SELECT a FROM tl ORDER BY a", cat, "a"))
    assertEquals(Seq(2L, 1L, 3L), column("SELECT id, RANK() OVER (ORDER BY a) AS r FROM tl ORDER BY id", cat, "r"))
    // The hash join and the residual predicate agree: two pairs, where the
    // residual predicate found four.
    def pairs(sql: String): Set[(Any, Any)] =
      collectAllRows(SqlEngine.execute(sql, cat)).map(r => (r("x"), r("y"))).toSet
    assertEquals(Set((1, 2), (2, 1)), pairs("SELECT x.id AS x, y.id AS y FROM tl x JOIN tl y ON x.a = y.b"))
    assertEquals(Set((1, 2), (2, 1)),
      pairs("SELECT x.id AS x, y.id AS y FROM tl x JOIN tl y ON x.a <= y.b AND x.a >= y.b"))
  }

  /** A byte array is not text. Each of these read the array's identity hash,
    * `[B@1a96d94c`, a different string on every run; each is now an error
    * before anything runs. */
  @Test def binaryIsNotReadAsText(): Unit = {
    val cat = binaryCatalog()
    def bindError(sql: String): String =
      try { SqlEngine.execute(sql, cat); fail(s"$sql was accepted"); "" }
      catch { case e: IllegalArgumentException => e.getMessage }
    for (sql <- Seq("SELECT CAST(b AS STRING) AS v FROM tb", "SELECT CAST(b AS INT) AS v FROM tb",
                    "SELECT CASE WHEN id = 1 THEN 'x' ELSE b END AS v FROM tb"))
      assertTrue(sql, bindError(sql).contains("Cannot cast BINARY to"))
    // These filtered every row out as false, and counted nothing.
    for (sql <- Seq("SELECT id FROM tb WHERE b", "SELECT COUNT_IF(b) AS n FROM tb"))
      assertTrue(sql, bindError(sql).contains("Cannot cast BINARY to BOOLEAN"))
    assertTrue(bindError("SELECT id FROM tb WHERE b LIKE '%B@%'").contains("LIKE on BINARY is not supported"))
    assertTrue(bindError("SELECT id FROM tb WHERE 'x' LIKE b").contains("LIKE on BINARY is not supported"))
    assertTrue(bindError("SELECT b || 'x' AS v FROM tb").contains("|| on BINARY is not supported"))
    assertTrue(bindError("SELECT 'x' || b AS v FROM tb").contains("|| on BINARY is not supported"))
    // LENGTH was 11, the length of the hash text, whatever the bytes.
    for (fn <- Seq("LENGTH(b)", "UPPER(b)", "LOWER(b)", "TRIM(b)", "SUBSTRING(b, 1, 2)", "CONCAT('x', b)"))
      assertTrue(fn, bindError(s"SELECT $fn AS v FROM tb").contains("on BINARY is not supported"))
  }

  /** plans/bugfixes/01 end-to-end repro. `a JOIN b JOIN b` builds an inner
    * result with a duplicate-named schema `[k, v, k, v]`; routing that through
    * the outer join's grace-hash spill used to NPE reading the spilled bucket
    * back (parquet addresses columns by name). Positional spill names fix it.
    * Compares the spilled run against the non-spill baseline. */
  @Test def joinDerivedDuplicateColumnsSpillEndToEnd(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.LongType)))
    def view(rows: Seq[(Int, Long)]): MaterializedView = {
      val b = new ColumnarBatch(schema, math.max(1, rows.length))
      rows.zipWithIndex.foreach { case ((k, v), i) =>
        b.column(0).setBoxed(i, java.lang.Integer.valueOf(k))
        b.column(1).setBoxed(i, java.lang.Long.valueOf(v))
      }
      b.setNumRows(rows.length)
      new MaterializedView(schema, IndexedSeq(IndexedSeq(b)))
    }
    def catalog(): Catalog = {
      val c = new Catalog
      c.register("a", view(Seq((1, 1L), (2, 2L))))
      c.register("b", view(Seq((1, 9L), (2, 8L), (1, 90L))))
      c
    }
    val sql = "SELECT t2.v FROM a t0 JOIN b t1 ON t0.k = t1.k JOIN b t2 ON t1.k = t2.k"
    def run(opts: ExecutionOptions): Seq[Long] = {
      val q = SqlEngine.execute(sql, catalog(), opts)
      val col = q.schema.fieldNames.head
      collectAllRows(q).map(_(col).asInstanceOf[java.lang.Long].longValue)
    }

    val expected = run(ExecutionOptions.Default).sorted
    // Isolate spill output under a throwaway dir (spill is otherwise unused in
    // this test class, so the lazy Spill.rootDir initializes here).
    val prevProp = System.getProperty(Spill.SpillDirProperty)
    val tmp = Files.createTempDirectory("sqlengine-spill-")
    System.setProperty(Spill.SpillDirProperty, tmp.resolve("transformer-spill").toString)
    try {
      val actual = run(ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))).sorted
      assertTrue("expected a non-empty result", expected.nonEmpty)
      assertEquals(expected, actual)
    } finally {
      if (prevProp == null) System.clearProperty(Spill.SpillDirProperty)
      else System.setProperty(Spill.SpillDirProperty, prevProp)
    }
  }

  /** Every spilling operator writes its working set to parquet and reads it
    * back, so a DECIMAL column could not spill at all while the parquet writer
    * had no decimal mapping. Sort, aggregate (a decimal GROUP BY key), DISTINCT,
    * grace-hash join (a decimal join key) and window (a decimal PARTITION BY)
    * each run here with spill forced and must match the run without it. */
  @Test def decimalColumnsSpillEndToEnd(): Unit = {
    val schema = Schema(Vector(
      Field("k", DataType.IntType), Field("d", DataType.DecimalType(10, 2)), Field("v", DataType.LongType)))
    def part(from: Int, until: Int): ColumnarBatch = {
      val b = new ColumnarBatch(schema, until - from)
      (from until until).zipWithIndex.foreach { case (i, r) =>
        b.column(0).setBoxed(r, java.lang.Integer.valueOf(i))
        // 97 distinct values either side of zero, with a NULL every 50th row.
        if (i % 50 == 0) b.column(1).setNull(r)
        else b.column(1).setBoxed(r, java.math.BigDecimal.valueOf((i % 97 - 48).toLong * 1000003L, 2))
        b.column(2).setBoxed(r, java.lang.Long.valueOf(i.toLong * 7L))
      }
      b.setNumRows(until - from)
      b
    }
    def catalog(): Catalog = catalogWith("t" -> new MaterializedView(schema,
      IndexedSeq(IndexedSeq(part(0, 600)), IndexedSeq(part(600, 1200)))))
    def run(sql: String, opts: ExecutionOptions): Seq[String] = {
      val q = SqlEngine.execute(sql, catalog(), opts)
      collectAllRows(q).map(row => q.schema.fieldNames.map(n => String.valueOf(row(n))).mkString("|"))
    }
    val queries = Seq(
      "SELECT k, d FROM t ORDER BY d, k" -> true,
      "SELECT d, COUNT(*) AS n, SUM(v) AS s FROM t GROUP BY d" -> false,
      "SELECT DISTINCT d FROM t" -> false,
      "SELECT a.k, b.k AS k2, a.d FROM t a JOIN t b ON a.d = b.d" -> false,
      "SELECT k, d, ROW_NUMBER() OVER (PARTITION BY d ORDER BY k) AS rn FROM t" -> false)
    val prevProp = System.getProperty(Spill.SpillDirProperty)
    val tmp = Files.createTempDirectory("sqlengine-decimal-spill-")
    System.setProperty(Spill.SpillDirProperty, tmp.resolve("transformer-spill").toString)
    try {
      for ((sql, ordered) <- queries) {
        val expected = run(sql, ExecutionOptions.Default)
        val actual = run(sql, ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L)))
        assertTrue(s"expected a non-empty result: $sql", expected.nonEmpty)
        if (ordered) assertEquals(sql, expected, actual)
        else assertEquals(sql, expected.sorted, actual.sorted)
      }
    } finally {
      if (prevProp == null) System.clearProperty(Spill.SpillDirProperty)
      else System.setProperty(Spill.SpillDirProperty, prevProp)
    }
  }

  /** A NULL-typed column spills like any other. It is the type of an untyped
    * `NULL` and of an aggregate over one, and it has no parquet type, so every
    * spilling operator threw `Writing null to Parquet not supported` on one
    * while the same query ran without spill: a sort over `SUM(NULL)`, a
    * `GROUP BY` and a `DISTINCT` with a NULL key, a grace-hash join or a UNION
    * carrying a NULL column. The plan 04 corpus found it at seed 1796 of
    * `mode_differential_fuzz_campaign`: an untyped NULL is the only NULL a
    * DECIMAL or a BINARY has, since the binder reads no CAST target for either.
    * Each query runs with spill forced and must match the run without it. */
  @Test def nullTypedColumnsSpillEndToEnd(): Unit = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("g", DataType.IntType)))
    def part(from: Int, until: Int): ColumnarBatch = {
      val b = new ColumnarBatch(schema, until - from)
      (from until until).zipWithIndex.foreach { case (i, r) =>
        b.column(0).setBoxed(r, java.lang.Integer.valueOf(i))
        b.column(1).setBoxed(r, java.lang.Integer.valueOf(i % 7))
      }
      b.setNumRows(until - from)
      b
    }
    def catalog(): Catalog = catalogWith("t" -> new MaterializedView(schema,
      IndexedSeq(IndexedSeq(part(0, 600)), IndexedSeq(part(600, 1200)))))
    def run(sql: String, opts: ExecutionOptions): Seq[String] = {
      val q = SqlEngine.execute(sql, catalog(), opts)
      collectAllRows(q).map(row => q.schema.fieldNames.map(n => String.valueOf(row(n))).mkString("|"))
    }
    val queries = Seq(
      "SELECT k, NULL AS x FROM t ORDER BY k" -> true,
      "SELECT g, SUM(NULL) AS s, MIN(NULL) AS m FROM t GROUP BY g ORDER BY g" -> true,
      "SELECT g, NULL AS x, COUNT(*) AS n FROM t GROUP BY g, NULL" -> false,
      "SELECT DISTINCT g, NULL AS x FROM t" -> false,
      "WITH a AS (SELECT k, NULL AS x FROM t) SELECT a.k, a.x, b.g FROM a JOIN t b ON a.k = b.k" -> false,
      "WITH a AS (SELECT k, NULL AS x FROM t WHERE g = 0) SELECT b.k, a.x FROM t b LEFT JOIN a ON a.k = b.k" -> false,
      "SELECT g, NULL AS x FROM t UNION SELECT g, NULL AS x FROM t" -> false,
      "SELECT k, NULL AS x, COUNT(*) OVER (PARTITION BY g) AS n FROM t" -> false)
    val prevProp = System.getProperty(Spill.SpillDirProperty)
    val tmp = Files.createTempDirectory("sqlengine-null-spill-")
    System.setProperty(Spill.SpillDirProperty, tmp.resolve("transformer-spill").toString)
    try {
      for ((sql, ordered) <- queries) {
        val expected = run(sql, ExecutionOptions.Default)
        val actual = run(sql, ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L)))
        assertTrue(s"expected a non-empty result: $sql", expected.nonEmpty)
        if (ordered) assertEquals(sql, expected, actual)
        else assertEquals(sql, expected.sorted, actual.sorted)
      }
    } finally {
      if (prevProp == null) System.clearProperty(Spill.SpillDirProperty)
      else System.setProperty(Spill.SpillDirProperty, prevProp)
    }
  }
}
