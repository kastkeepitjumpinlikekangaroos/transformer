package com.transformer.cli

import com.transformer.cli.SqlScript.{Piece, Statement}
import org.junit.Assert._
import org.junit.Test

class SqlScriptTest {

  private def texts(script: String): Seq[String] = SqlScript.split(script).all.map(_.text)

  private def statement(sql: String): Statement = SqlScript.sqlStatement(sql)

  private def refusal(sql: String): String =
    try { fail(s"expected a refusal of: $sql, got ${statement(sql)}"); "" }
    catch { case e: IllegalArgumentException => e.getMessage }

  // ---- splitting ---------------------------------------------------------------

  @Test def statementsEndAtSemicolonsAndTheLastNeedsNone(): Unit = {
    assertEquals(Seq("SELECT 1", "SELECT 2", "SELECT 3"), texts("SELECT 1; SELECT 2;\nSELECT 3"))
    assertEquals(Seq("SELECT 1"), texts("SELECT 1;;  ;"))
    assertEquals(Nil, texts("  \n -- just a comment\n /* and another */ "))
  }

  @Test def aSemicolonInQuotesOrCommentsEndsNothing(): Unit = {
    assertEquals(Seq("SELECT 'a;b' AS \"x;y\", `p;q` FROM t"), texts("SELECT 'a;b' AS \"x;y\", `p;q` FROM t;"))
    assertEquals(Seq("SELECT 'it''s; here' AS s"), texts("SELECT 'it''s; here' AS s;"))
    assertEquals(Seq("SELECT 1 -- a; comment\n+ 1", "SELECT 2 /* ; */"), texts("SELECT 1 -- a; comment\n+ 1;\nSELECT 2 /* ; */"))
  }

  @Test def eachPieceKnowsTheLineItStartsOn(): Unit = {
    val pieces = SqlScript.split("SELECT 1;\n\n-- note\nSELECT\n  2;\n.tables\nSELECT 'a\nb';\nSELECT 4").all
    assertEquals(Seq(1, 4, 6, 7, 9), pieces.map(_.line))
    assertEquals(Seq(false, false, true, false, false), pieces.map(_.dot))
  }

  @Test def aDotCommandIsALineWhereAStatementWouldStart(): Unit = {
    assertEquals(Seq(Piece(".mode csv", 1, dot = true), Piece("SELECT 1", 2, dot = false)),
      SqlScript.split(".mode csv\nSELECT 1;").all)
    assertEquals(Seq(Piece(".tables", 2, dot = true)), SqlScript.split("-- list them\n   .tables   \n").all)
    // Inside a statement, or after another on its line, a '.' is SQL's.
    assertEquals(Seq("SELECT a\n.5"), texts("SELECT a\n.5;"))
    assertEquals(Seq("SELECT 1", ".tables"), texts("SELECT 1; .tables"))
    assertTrue(SqlScript.split("SELECT 1; .tables").all.forall(!_.dot))
  }

  @Test def completeWhenEveryStatementHasItsSemicolon(): Unit = {
    assertTrue(SqlScript.complete("SELECT 1;"))
    assertTrue(SqlScript.complete("SELECT 1; -- done"))
    assertTrue(SqlScript.complete(".tables"))
    assertTrue(SqlScript.complete("SELECT 1;\n.mode csv"))
    assertTrue(SqlScript.complete("   "))
    assertFalse(SqlScript.complete("SELECT 1"))
    assertFalse(SqlScript.complete("SELECT 1;\nSELECT 2"))
    assertFalse(SqlScript.complete("SELECT 'a;"))
    assertFalse(SqlScript.complete("SELECT \"a;"))
    assertFalse(SqlScript.complete("SELECT 1; /* still open"))
  }

  // ---- what each statement is ----------------------------------------------------

  @Test def everyOtherStatementGoesToTheEngineAsWritten(): Unit = {
    for (sql <- Seq("SELECT 1", "WITH q AS (SELECT 1) SELECT * FROM q", "EXPLAIN SELECT 1", "-- c\nSELECT 1",
        "INSERT INTO t VALUES (1)", "(SELECT 1) UNION ALL (SELECT 2)"))
      assertEquals(Statement.Query(sql), statement(sql))
  }

  @Test def createViewReadsItsNameColumnsAndQuery(): Unit = {
    assertEquals(Statement.CreateView("v", Nil, orReplace = false, "SELECT a FROM t"),
      statement("CREATE VIEW v AS SELECT a FROM t"))
    assertEquals(Statement.CreateView("My View", Seq("x", "y z"), orReplace = true, "SELECT a, b\nFROM t -- end"),
      statement("create or replace temp view \"My View\" (x, \"y z\") as\n  SELECT a, b\nFROM t -- end"))
    assertEquals(Statement.CreateView("v", Nil, orReplace = false, "WITH q AS (SELECT 1 AS a) SELECT a FROM q"),
      statement("CREATE TEMPORARY VIEW v AS WITH q AS (SELECT 1 AS a) SELECT a FROM q"))
  }

  @Test def createRefusesWhatIsNoView(): Unit = {
    assertTrue(refusal("CREATE TABLE t AS SELECT 1").startsWith("CREATE TABLE is not supported"))
    assertTrue(refusal("CREATE VIEW v SELECT 1").contains("Expected AS in CREATE VIEW, found 'SELECT'"))
    assertTrue(refusal("CREATE VIEW AS").contains("Expected AS"))
    assertEquals("CREATE VIEW v needs a query after AS", refusal("CREATE VIEW v AS  "))
    assertTrue(refusal("CREATE VIEW v (a, AS SELECT 1").contains("',' or ')' in the column list"))
    assertTrue(refusal("CREATE OR VIEW v AS SELECT 1").contains("REPLACE after OR"))
  }

  @Test def dropViewReadsItsName(): Unit = {
    assertEquals(Statement.DropView("v", ifExists = false), statement("DROP VIEW v"))
    assertEquals(Statement.DropView("a b", ifExists = true), statement("drop view if exists \"a b\""))
    assertTrue(refusal("DROP TABLE t").startsWith("DROP TABLE is not supported"))
    assertTrue(refusal("DROP VIEW v w").contains("the end of the statement"))
  }

  @Test def copyReadsItsSourcePathAndOptions(): Unit = {
    assertEquals(Statement.Copy(Right("SELECT f(a), (b) FROM t WHERE c IN (1, 2)"), "out/x.csv", Nil),
      statement("COPY (SELECT f(a), (b) FROM t WHERE c IN (1, 2)) TO 'out/x.csv'"))
    assertEquals(Statement.Copy(Left("My T"), "it's.parquet", Seq("FORMAT" -> "parquet", "compression" -> "gzip",
      "parquet_row_group_size" -> "1048576")),
      statement("copy \"My T\" to 'it''s.parquet' with (FORMAT parquet, compression 'gzip', parquet_row_group_size 1048576)"))
    assertEquals(Statement.Copy(Left("t"), "x.tsv", Seq("format" -> "csv", "delimiter" -> "\\t")),
      statement("COPY t TO 'x.tsv' (format csv, delimiter '\\t')"))
  }

  @Test def copyRefusesWhatItCannotRead(): Unit = {
    assertTrue(refusal("COPY t FROM 'x.csv'").startsWith("COPY ... FROM is not supported"))
    assertTrue(refusal("COPY (SELECT 1 TO 'x.csv'").contains("the ')' that closes the query"))
    assertTrue(refusal("COPY t TO x.csv").contains("the file's path in quotes"))
    assertTrue(refusal("COPY t TO 'x.csv' (header)").contains("a value for header"))
    assertTrue(refusal("COPY () TO 'x.csv'").contains("a query inside the parentheses"))
    assertEquals("COPY TO needs a file's path, not ''", refusal("COPY t TO ''"))
  }

  @Test def dotCommandsSplitAtWhiteSpaceAndQuotes(): Unit = {
    assertEquals(Statement.Dot("table", Seq("t", "/a b/c.csv", "delimiter=|", "nullValue=it's")),
      SqlScript.dotCommand(".TABLE t '/a b/c.csv' delimiter=| \"nullValue=it's\""))
    assertEquals(Statement.Dot("quit", Nil), SqlScript.dotCommand(".quit"))
    assertEquals(Seq("a'b", "c"), SqlScript.dotWords("'a''b' c"))
  }

  // ---- views defined ahead of a query ---------------------------------------------

  @Test def definitionsGoInTheQuerysOwnWithOrAWithOfTheirOwn(): Unit = {
    assertEquals("WITH v AS (x)\nSELECT 1", SqlScript.withDefinitions("SELECT 1", Seq("v AS (x)")))
    assertEquals("WITH v AS (x),\nw AS (y)\n(SELECT 1)", SqlScript.withDefinitions("(SELECT 1)", Seq("v AS (x)", "w AS (y)")))
    assertEquals("-- c\nWITH\nv AS (x),\nq AS (SELECT 1) SELECT * FROM q",
      SqlScript.withDefinitions("-- c\nWITH q AS (SELECT 1) SELECT * FROM q", Seq("v AS (x)")))
    assertEquals("with recursive\nv AS (x),\nq AS (SELECT 1) SELECT 1",
      SqlScript.withDefinitions("with recursive q AS (SELECT 1) SELECT 1", Seq("v AS (x)")))
  }
}
