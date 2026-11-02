package com.transformer.slt

import com.transformer.core.DataType
import com.transformer.slt.Script.Column
import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The checker that holds a Postgres port to Postgres's output: it names an
  * edited answer with both values, a statement error Postgres ran and a query
  * Postgres refused; it cuts a `.sql` file as psql does and reads psql's
  * aligned output. */
class PgExpectedTest {

  import PgExpectedTest._

  // ---- a port with an edit -----------------------------------------------------

  /** boolean.test in a directory of its own, beside Postgres's source and
    * output, with `from` replaced by `to`; the check's problems. */
  private def checkEdited(from: String, to: String): (Vector[String], Int) = {
    val dir = Files.createTempDirectory("pg-expected")
    Files.createDirectories(dir.resolve("sql"))
    Files.createDirectories(dir.resolve("expected"))
    Files.copy(Root.resolve("sql/boolean.sql"), dir.resolve("sql/boolean.sql"))
    Files.copy(Root.resolve("expected/boolean.out"), dir.resolve("expected/boolean.out"))
    val script = read(Root.resolve("boolean.test"))
    assertEquals(s"the port holds [$from] once", 1, script.split(java.util.regex.Pattern.quote(from), -1).length - 1)
    val edited = script.replace(from, to)
    val path = dir.resolve("boolean.test")
    Files.write(path, edited.getBytes(StandardCharsets.UTF_8))
    // The line of the edited record: its first line, the one before its SQL.
    val sqlLine = edited.substring(0, edited.indexOf(to)).count(_ == '\n') + 1
    (PgExpected.check("boolean.test", path, ledger), sqlLine)
  }

  @Test def anEditedAnswerIsNamedWithBothValues(): Unit = {
    val (problems, line) = checkEdited(
      "query BOOLEAN\nSELECT CAST('true' AS BOOLEAN) AS \"true\"\n----\ntrue\n",
      "query BOOLEAN\nSELECT CAST('true' AS BOOLEAN) AS \"true\"\n----\nfalse\n")
    assertEquals(problems.mkString("\n"), 1, problems.length)
    val p = problems.head
    assertTrue(p, p.startsWith(s"boolean.test:$line (boolean.sql:23): "))
    assertTrue(p, p.contains("the record has [false] where Postgres printed [true]"))
  }

  @Test def aStatementErrorPostgresRanIsNamed(): Unit = {
    val (problems, line) = checkEdited(
      "query BOOLEAN\nSELECT CAST('true' AS BOOLEAN) AS \"true\"\n----\ntrue\n",
      "statement error\nSELECT CAST('true' AS BOOLEAN) AS \"true\"\n")
    assertEquals(problems.mkString("\n"), Vector(s"boolean.test:$line (boolean.sql:23): the record expects an error, " +
      "but Postgres ran the statement and printed 1 rows"), problems)
  }

  @Test def aQueryPostgresRefusedIsNamed(): Unit = {
    val (problems, line) = checkEdited(
      "statement error\nSELECT CAST('test' AS BOOLEAN) AS error\n",
      "query BOOLEAN\nSELECT CAST('test' AS BOOLEAN) AS error\n----\nfalse\n")
    assertEquals(problems.mkString("\n"), Vector(s"boolean.test:$line (boolean.sql:25): Postgres refused the query: " +
      "ERROR: invalid input syntax for type boolean: \"test\""), problems)
  }

  @Test def aRowsortBlockMissingARowIsNamed(): Unit = {
    val (problems, _) = checkEdited(
      "SELECT BOOLTBL1.* FROM BOOLTBL1\n----\ntrue\ntrue\ntrue\n",
      "SELECT BOOLTBL1.* FROM BOOLTBL1\n----\ntrue\ntrue\n")
    assertEquals(problems.mkString("\n"), 1, problems.length)
    assertTrue(problems.head, problems.head.contains("the record has 2 rows, Postgres printed 3"))
  }

  // ---- psql's statements -------------------------------------------------------

  @Test def aSqlFileIsCutAsPsqlCutsIt(): Unit = {
    val sql = PgExpected.splitSql(Seq(
      "SELECT 1; SELECT 2;", // 1: two statements on a line
      "", // 2: not echoed
      "-- a comment; with a semicolon", // 3
      "SELECT 'a;b', \"x;y\", $$ ; $$, $t$ ; $t$,", // 4
      "  E'it\\'s ;', (1;", // 5: an E string's \' and a ; in parentheses
      "", // 6: inside a statement, not echoed
      "2);", // 7
      "\\pset null '(null)'", // 8: a meta-command
      "COPY t FROM stdin;", // 9
      "1\t2", // 10: COPY's rows are not echoed
      "\\.", // 11
      "SET standard_conforming_strings TO off;", // 12
      "SELECT 'a\\';b';", // 13: with it off, a plain string takes \'
      "RESET standard_conforming_strings;", // 14
      "/* a comment", // 15
      "", // 16: inside a comment, echoed
      "*/ SELECT 'x\\';" // 17: with it on again, \ is a character
    ).mkString("\n") + "\n")
    assertEquals(Vector((1, 1, false), (1, 1, false), (4, 7, false), (8, 8, true), (9, 9, false), (12, 12, false),
      (13, 13, false), (14, 14, false), (17, 17, false)), sql.statements.map(s => (s.first, s.last, s.meta)))
    assertEquals(Vector(1, 3, 4, 5, 7, 8, 9, 12, 13, 14, 15, 16, 17), sql.echoed)
    assertEquals("SELECT 'x\\';", sql.statements.last.text.trim)
    assertEquals(Some(2), sql.at(3).map(_.index))
  }

  // ---- psql's output -------------------------------------------------------------

  @Test def psqlsAlignedOutputIsRead(): Unit = {
    val sql = PgExpected.splitSql(Seq(
      "SELECT a, b FROM t;",
      "SELECT 1/0;",
      "\\pset null '(null)'",
      "SELECT x FROM u;",
      "SELECT FROM u;",
      "--"
    ).mkString("\n") + "\n")
    val out = Seq(
      "SELECT a, b FROM t;",
      " a  |  b   ",
      "----+------",
      "  1 | x   +",
      "    | y",
      " 22 | ",
      "  3 | 456+",
      "(3 rows)",
      "",
      "SELECT 1/0;",
      "ERROR:  division by zero",
      "\\pset null '(null)'",
      "SELECT x FROM u;",
      "NOTICE:  a notice",
      "   x    ",
      "--------",
      " (null)",
      " ",
      "(2 rows)",
      "",
      "SELECT FROM u;",
      "--",
      "(2 rows)",
      "",
      "--"
    ).mkString("\n") + "\n"
    val o = PgExpected.readOutput("t.out", sql, out)
    val t = o(0).tables.head
    assertEquals(Vector("a", "b"), t.header)
    // The second row's cell continues; the last column's + is a value's.
    assertEquals(Vector(Vector(" 1", "x\ny"), Vector("22", ""), Vector(" 3", "456+")), t.rows)
    assertEquals(Vector("division by zero"), o(1).errors)
    assertEquals("(null)", o(3).nullDisplay)
    assertEquals(Vector(Vector("(null)"), Vector("")), o(3).tables.head.rows)
    // A table of no columns prints as --, which the next comment's echo is too.
    assertEquals(Vector(Vector(), Vector()), o(4).tables.head.rows)
    assertTrue(o.values.forall(_.unexplained.isEmpty))
    val failure = assertThrows(classOf[PgExpected.OutError], () => { PgExpected.readOutput("t.out", sql, "SELECT 2;\n"); () })
    assertTrue(failure.getMessage, failure.getMessage.startsWith("t.out:1: expected the echo of line 1"))
  }

  @Test def eachValueIsPrintedAsTheRecordsColumnPrintsIt(): Unit = {
    def p(cell: String, c: Column, nul: String = ""): Vector[String] = PgExpected.printings(cell, c, nul)
    assertEquals(Vector("1.2345678901234E200"), p("1.2345678901234e+200", Column.Exact(DataType.DoubleType)))
    assertEquals(Vector("-0.0"), p("     -0", Column.Exact(DataType.DoubleType)))
    assertEquals(Vector("1.2345679E20"), p("1.2345679e+20", Column.Exact(DataType.FloatType)))
    assertEquals(Vector("2.50"), p("2.5000", Column.Exact(DataType.DecimalType(9, 2))))
    assertEquals(Vector("2.5001"), p("2.5001", Column.Exact(DataType.DecimalType(9, 2))))
    assertEquals(Vector("4567890123456789"), p("4567890123456789", Column.Exact(DataType.LongType)))
    assertEquals(Vector("true"), p("t", Column.Exact(DataType.BooleanType)))
    assertEquals(Vector("1957-04-09"), p("04-09-1957", Column.Exact(DataType.DateType)))
    assertEquals(Vector("-0099-01-08"), p("01-08-0100 BC", Column.Exact(DataType.DateType)))
    assertEquals(Vector("  a"), p("  a   ", Column.Exact(DataType.StringType)))
    assertEquals(Vector("NULL", "(empty)"), p("", Column.Exact(DataType.StringType)))
    assertEquals(Vector("(empty)"), p("", Column.Exact(DataType.StringType), "(null)"))
    assertEquals(Vector("NULL"), p("(null)", Column.Exact(DataType.IntType), "(null)"))
    assertEquals(Vector("772350980172061.697"), p("772350980172061.69659105821915863601", Column.Real))
    assertEquals(Vector("-0.000"), p("-0.0001", Column.Real))
    assertEquals(Vector("2", "2.0"), p("    2", Column.Text))
    assertEquals(Vector("1e+20", "1.0e+20"), p("1e+20", Column.Text))
    assertEquals(Vector("3"), p("3.99", Column.Integer))
  }
}

object PgExpectedTest {

  /** The Postgres ports: the runfiles tree under Bazel, the source tree
    * otherwise. */
  private val Root: Path = Paths.get("src/test/slt/postgres")

  private lazy val ledger: Ledger = Ledger.load(Ledger.DefaultPath)

  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
}
