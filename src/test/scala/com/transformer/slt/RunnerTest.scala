package com.transformer.slt

import com.transformer.slt.Runner._
import org.junit.Assert._
import org.junit.Test

import java.nio.file.Paths

/** The runner over scripts written here, against a ledger of two rules: a
  * condition on transformer cites a rule, a skipped record still runs once and
  * must still diverge as its rule says, and a twin runs like any record; and
  * CockroachDB's and DataFusion's formats and dialects, each directive read or
  * refused by name, each result compared as that engine's runner compares it. */
class RunnerTest {

  private val ledger = Ledger.parse("ledger.md", Seq(
    "# Gotchas",
    "",
    Ledger.Heading,
    "",
    "| id | rule | refusal | stated in | decided in | pinned by |",
    "|---|---|---|---|---|---|",
    "| `refused-except-intersect` | EXCEPT and INTERSECT are refused. | `only UNION and UNION ALL are supported` | here | here | `refused/unsupported.test` |",
    "| `div-zero-null` | A division or % by zero is NULL. | | here | here | `types/float_equality.test` |",
    "",
    "## Next section").mkString("\n"))

  private val setup = Seq(
    "statement ok", "CREATE TABLE t (a INT)", "",
    "statement ok", "INSERT INTO t VALUES (1), (2)", "")

  private def parse(lines: String*): Vector[Script.Record] =
    Script.parse("inline.test", (setup ++ lines).mkString("\n"), ledger)

  private def run(lines: String*): FileResult =
    new Runner(Dialect.Transformer, ledger, Nil).run("inline.test", parse(lines: _*), Paths.get("."))

  /** The outcome of the record at `line` of the script `setup ++ lines`. */
  private def outcomeAt(result: FileResult, line: Int): Outcome =
    result.results.find(_.record.line == line).map(_.outcome)
      .getOrElse(throw new AssertionError(s"no record at line $line: ${result.results}"))

  private def parseError(lines: String*): String =
    assertThrows(classOf[Script.ParseError], () => { parse(lines: _*); () }).getMessage

  /** The line of the first condition after `setup`, and of the record it
    * stands before, which is the line a record's outcome and a record's
    * parse error name. */
  private val First = setup.length + 1
  private val Rec = First + 1

  // ---- parsing -------------------------------------------------------------------

  @Test def aSkipWithNoIdIsAParseErrorNamingTheLineAndTheNearestRule(): Unit = {
    val m = parseError("skipif transformer # a division by zero is NULL", "query I", "SELECT a / 0 FROM t", "----",
      "NULL", "NULL")
    assertTrue(m, m.startsWith(s"inline.test:$First: 'skipif transformer' cites no rule"))
    assertTrue(m, m.contains("the nearest to its comment is 'div-zero-null'"))
  }

  @Test def anIdTheLedgerDoesNotHaveIsAParseErrorNamingTheNearest(): Unit = {
    val m = parseError("onlyif transformer div-by-zero-null", "query I", "SELECT 1")
    assertTrue(m, m.startsWith(s"inline.test:$First: 'onlyif transformer div-by-zero-null' cites a rule"))
    assertTrue(m, m.contains("the nearest is 'div-zero-null'"))
  }

  @Test def aConditionOnTransformerTakesOnlyACommentAfterItsId(): Unit = {
    val m = parseError("skipif transformer div-zero-null because", "query I", "SELECT 1")
    assertTrue(m, m.contains("takes a comment after a #, not 'because'"))
  }

  @Test def aSkipKeepsTheConditionsOnOtherEnginesBesideIt(): Unit = {
    val rec = parse("skipif transformer refused-except-intersect", "skipif mysql # not compatible", "query I",
      "SELECT a FROM t EXCEPT SELECT a FROM t", "----").last
    assertEquals(Seq(Script.Condition(skip = true, "transformer", Some("refused-except-intersect")),
      Script.Condition(skip = true, "mysql", None)), rec.conditions)
    assertEquals(Some("refused-except-intersect"), rec.skipRule)
  }

  @Test def aRecordTakesOneConditionOnTransformerAndNoOnlyifOnAnother(): Unit = {
    assertTrue(parseError("skipif transformer div-zero-null", "onlyif transformer div-zero-null", "query I",
      "SELECT 1").contains("a record takes one condition on transformer"))
    assertTrue(parseError("onlyif mysql", "skipif transformer refused-except-intersect", "query I",
      "SELECT 1").contains("the onlyif keeps it from this engine already"))
  }

  @Test def aSkipWhoseRuleIsNoRefusalIsFollowedByItsTwin(): Unit = {
    val skip = Seq("skipif transformer div-zero-null", "statement error", "SELECT a / 0 FROM t", "")
    assertTrue(parseError(skip: _*).contains(s"inline.test:$Rec: a record skipped as div-zero-null is followed by its twin"))
    // A twin of other SQL is none.
    assertTrue(parseError(skip ++ Seq("onlyif transformer div-zero-null", "query I", "SELECT 1 / 0", "----",
      "NULL"): _*).contains("followed by its twin"))
    // A refusal's skip needs no twin.
    parse("skipif transformer refused-except-intersect", "statement error", "SELECT a FROM t EXCEPT SELECT a FROM t")
  }

  // ---- the stale check -----------------------------------------------------------

  @Test def aValueSkipThatStillDivergesIsSkippedAsItsRuleAndItsTwinRuns(): Unit = {
    val result = run(
      "skipif transformer div-zero-null # Postgres refuses it", "statement error", "SELECT a / 0 FROM t", "",
      "onlyif transformer div-zero-null", "query I rowsort", "SELECT a / 0 FROM t", "----", "NULL", "NULL")
    assertEquals(Skipped("skipped as div-zero-null", Some("div-zero-null")), outcomeAt(result, Rec))
    assertEquals(Passed, outcomeAt(result, Rec + 4))
    assertTrue(result.summary, result.problems.isEmpty)
  }

  @Test def aTwinWithAnotherAnswerFails(): Unit = {
    val result = run(
      "skipif transformer div-zero-null", "statement error", "SELECT a / 0 FROM t", "",
      "onlyif transformer div-zero-null", "query I rowsort", "SELECT a / 0 FROM t", "----", "0", "0")
    assertTrue(result.summary, outcomeAt(result, Rec + 4).isInstanceOf[Failed])
  }

  @Test def aSkippedQueryTheEngineNowAnswersIsStale(): Unit = {
    val result = run(
      "skipif transformer div-zero-null", "query I rowsort", "SELECT a / 0 FROM t", "----", "NULL", "NULL", "",
      "onlyif transformer div-zero-null", "query I rowsort", "SELECT a / 0 FROM t", "----", "NULL", "NULL")
    assertEquals(Stale("div-zero-null", "skipped as div-zero-null, but the engine now gives the record's answer, " +
      "compared as I (an integer's low 32 bits); remove the skip"), outcomeAt(result, Rec))
    assertEquals(Vector(Rec), result.problems.map(_.record.line))
  }

  @Test def aSkippedStatementErrorTheEngineNowRefusesIsStale(): Unit = {
    val twin = Seq("onlyif transformer div-zero-null", "statement error", "SELECT CAST('x' AS INT)")
    val now = run(Seq("skipif transformer div-zero-null", "statement error", "SELECT CAST('x' AS INT)", "") ++ twin: _*)
    assertTrue(show(outcomeAt(now, Rec)), show(outcomeAt(now, Rec)).startsWith(
      "skipped as div-zero-null, but the engine now fails as the record expects, with IllegalArgumentException: " +
        "Cannot cast 'x' to int"))
    // With the other engine's words, which the engine's do not contain, the
    // record would still fail; the rule says the engine answers, and it does
    // not.
    val other = run(Seq("skipif transformer div-zero-null", "statement error", "SELECT CAST('x' AS INT)", "----",
      "invalid input syntax for type integer", "") ++ twin: _*)
    assertTrue(show(outcomeAt(other, Rec)), show(outcomeAt(other, Rec)).startsWith(
      "skipped as div-zero-null, but the rule says the engine answers where the other engine refuses, and the " +
        "engine now fails"))
  }

  @Test def aRefusalSkipStillRefusedWithItsRulesWordsIsSkipped(): Unit = {
    val result = run("skipif transformer refused-except-intersect", "query I rowsort",
      "SELECT a FROM t EXCEPT SELECT a FROM t", "----")
    assertEquals(Skipped("skipped as refused-except-intersect", Some("refused-except-intersect")),
      outcomeAt(result, Rec))
  }

  @Test def aRefusalSkipRefusedForAnotherReasonIsStale(): Unit = {
    val result = run("skipif transformer refused-except-intersect", "query I rowsort",
      "SELECT a FROM t WHERE a IN (SELECT a FROM t)", "----", "1", "2")
    val o = show(outcomeAt(result, Rec))
    assertTrue(o, o.startsWith("skipped as refused-except-intersect, but the engine refuses it for another reason, " +
      "IllegalArgumentException: IN with a subquery is not supported"))
    assertTrue(o, o.endsWith("the rule's refusal says [only UNION and UNION ALL are supported]"))
  }

  @Test def aRefusalSkipTheEngineNowRunsIsStale(): Unit = {
    val right = run("skipif transformer refused-except-intersect", "query I rowsort",
      "SELECT a FROM t UNION SELECT a FROM t", "----", "1", "2")
    assertEquals(Stale("refused-except-intersect", "skipped as refused-except-intersect, but the engine now gives " +
      "the record's answer, compared as I (an integer's low 32 bits); remove the skip"), outcomeAt(right, Rec))
    val wrong = run("skipif transformer refused-except-intersect", "query I rowsort",
      "SELECT a FROM t UNION SELECT a FROM t", "----", "1", "3")
    val o = show(outcomeAt(wrong, Rec))
    assertTrue(o, o.startsWith("skipped as refused-except-intersect, but the engine no longer refuses it, and " +
      "answers otherwise than the record (2 values expected, 2 returned; first difference at value 2"))
  }

  @Test def aSkippedRecordThatChangesATableFails(): Unit = {
    val result = run("skipif transformer refused-except-intersect", "statement ok", "INSERT INTO t VALUES (3)")
    assertEquals(Failed("skipped as refused-except-intersect, but it is no query: a skipped record runs to check " +
      "its skip, so it must change no table"), outcomeAt(result, Rec))
  }

  @Test def anAuditTalliesSkipsByRuleAndKeepsStaleOnes(): Unit = {
    val result = run(
      "skipif transformer refused-except-intersect", "query I", "SELECT a FROM t EXCEPT SELECT a FROM t", "----", "",
      "skipif transformer refused-except-intersect", "query I rowsort", "SELECT a FROM t UNION SELECT a FROM t",
      "----", "1", "2", "",
      "onlyif mysql", "query I", "SELECT 1", "----", "1")
    val c = result.compact(keepRefusals = false)
    assertEquals(Map("refused-except-intersect" -> 1), c.skipTally)
    assertEquals(Some(1), c.counts.get("stale"))
    assertEquals(Some(2), c.counts.get("skipped"))
    assertTrue(c.kept.exists(_.outcome.isInstanceOf[Stale]))
  }

  // ---- CockroachDB's and DataFusion's formats -----------------------------------------

  private def runAs(dialect: Dialect, lines: String*): FileResult = {
    val text = lines.mkString("\n")
    new Runner(dialect.forScript(text), ledger, Modes.Verification)
      .run("inline.test", Script.parse("inline.test", text, ledger, dialect.format), Paths.get("."))
  }

  private def problems(r: FileResult): String = r.problems.map(p => s"${p.record.line}: ${show(p.outcome)}").mkString("\n")

  @Test def cockroachsDirectivesAreReadAndAnUnknownOneFailsByName(): Unit = {
    val recs = Script.parse("c.test", Seq(
      "# LogicTest: local", "subtest one", "",
      "statement OK", "CREATE TABLE t (a INT)", "",
      "statement", "INSERT INTO t VALUES (1)", "",
      "statement count 1", "INSERT INTO t VALUES (2)", "",
      "statement error pgcode 42703 column \"x\" does not exist", "SELECT x FROM t", "",
      "skipif config local", "onlyif config fakedist", "query IT colnames,partialsort(1) lbl", "SELECT a, a FROM t", "----",
      "a a", "1 1").mkString("\n"), ledger, Script.Format.Cockroach)
    assertEquals(5, recs.length)
    val err = recs(3).asInstanceOf[Script.Statement]
    assertEquals(Some("column \"x\" does not exist"), err.errorPattern)
    val q = recs(4).asInstanceOf[Script.Query]
    assertTrue(q.colNames)
    assertEquals(Seq(1), q.partialSort)
    assertEquals(Some("lbl"), q.label)
    assertFalse(q.runs) // onlyif config fakedist: this engine is no configuration of CockroachDB's
    def fails(lines: String*): String =
      assertThrows(classOf[Script.ParseError], () => { Script.parse("c.test", lines.mkString("\n"), ledger,
        Script.Format.Cockroach); () }).getMessage
    assertTrue(fails("let $x", "SELECT 1").contains("unknown record type 'let'"))
    assertTrue(fails("query I kvtrace", "SELECT 1").contains("unknown query option 'kvtrace'"))
    assertTrue(fails("query IX", "SELECT 1").contains("unknown column type 'X'"))
  }

  @Test def dataFusionsDirectivesAreReadAndAnIncludeReadsItsFile(): Unit = {
    val recs = Script.parse("d.slt", Seq(
      "# configMatrix: none", "include ./part.slt.part", "",
      "query error DataFusion error: .* not found", "SELECT x", "",
      "statement error", "SELECT y", "----", "the whole message", "",
      "onlyif postgres", "query D", "SELECT DATE '2020-01-01'", "----", "2020-01-01").mkString("\n"),
      ledger, Script.Format.DataFusion, name => if (name == "./part.slt.part") "statement ok\nCREATE TABLE p (a INT)\n"
        else throw new IllegalArgumentException(s"no file $name"))
    assertEquals(4, recs.length)
    assertEquals(Some("DataFusion error: .* not found"), recs(1).asInstanceOf[Script.Statement].errorPattern)
    assertEquals(Some("the whole message"), recs(2).asInstanceOf[Script.Statement].errorText)
    assertFalse(recs(3).runs)
    assertTrue(assertThrows(classOf[Script.ParseError], () => {
      Script.parse("d.slt", "system ok\nls\n", ledger, Script.Format.DataFusion); ()
    }).getMessage.contains("unknown record type 'system'"))
  }

  @Test def cockroachsTablesDropATypeTheEngineHasNotAndTheirClauses(): Unit = {
    val r = runAs(Dialect.Cockroach(),
      "# transformer: an unconstrained DECIMAL is DECIMAL(38,10)", "",
      "statement ok", "CREATE TABLE t (k INT PRIMARY KEY, i INTERVAL, arr INT[], d DECIMAL, s STRING DEFAULT 'z',",
      "  INDEX (k), FAMILY (k, i))", "",
      "statement ok", "INSERT INTO t VALUES (1, '1 day', ARRAY[1], 1.5); INSERT INTO t (k, d) VALUES (3, 2)", "",
      // INT is CockroachDB's 64 bits: 2^31 fits, and ::INT is a BIGINT here too.
      "query IRT rowsort", "SELECT k + 2147483647, d, s FROM t", "----", "2147483648 1.5 z", "2147483650 2 z", "",
      "query I", "SELECT 3000000000::INT", "----", "3000000000", "",
      "query R", "SELECT avg(k) FROM t", "----", "2.0000000000000000000", "",
      "query T", "SELECT ''", "----", "\u00b7", "",
      "query error pgcode 42703 column \"i\" does not exist", "SELECT i FROM t")
    assertEquals("", problems(r))
  }

  @Test def cockroachsWordsAreComparedOneByOneAndAFloatTo15Digits(): Unit = {
    val r = runAs(Dialect.Cockroach(),
      "statement ok", "CREATE TABLE f (x FLOAT, s STRING)", "",
      "statement ok", "INSERT INTO f VALUES (0.1, 'a b'), (1e6, 'c'), (1.0 / 3.0, NULL)", "",
      // A float compares to 15 significant digits, whatever the form it is
      // written in; a value's words are compared one by one, across lines.
      "query F nosort", "SELECT x FROM f ORDER BY x", "----", "0.1", "0.333333333333333", "1e+06", "",
      "query T rowsort", "SELECT s FROM f", "----", "a b", "c", "NULL", "",
      "query T nosort", "SELECT s FROM f ORDER BY x", "----", "a", "b NULL c", "",
      "query T colnames", "SELECT s AS label FROM f WHERE x > 1", "----", "label", "c")
    assertEquals("", problems(r))
    val wrong = runAs(Dialect.Cockroach(),
      "query F", "SELECT 1.0 / 3.0", "----", "0.33333333333334")
    assertTrue(problems(wrong), problems(wrong).contains("expected [0.33333333333334]"))
  }

  @Test def dataFusionsRowsCompareAsItsRunnerPrintsThem(): Unit = {
    val r = runAs(Dialect.DataFusion(),
      "statement ok", "CREATE TABLE v AS VALUES (1, 'a', 0.5), (2, '', NULL)", "",
      "query ITR rowsort", "SELECT column1, column2, column3 / 3 FROM v", "----",
      "1 a 0.166666666667", "2 (empty) NULL", "",
      "statement ok", "set datafusion.execution.target_partitions = 2", "",
      "query D", "SELECT DATE '2020-01-31'", "----", "2020-01-31", "",
      "query P", "SELECT CAST('2020-01-31 10:00:00.5' AS TIMESTAMP)", "----", "2020-01-31T10:00:00.500")
    assertEquals("", problems(r))
  }

  @Test def anErrorInAnotherEnginesWordsPassesForTheSameReasonOnly(): Unit = {
    val r = runAs(Dialect.Cockroach(),
      "statement ok", "CREATE TABLE t (a INT)", "",
      "query error column \"b\" does not exist", "SELECT b FROM t", "",
      "query error at or near \"from\": syntax error", "SELECT a a a FROM t")
    assertEquals("", problems(r))
    val other = runAs(Dialect.Cockroach(),
      "statement ok", "CREATE TABLE t (a INT)", "",
      "query error division by zero", "SELECT b FROM t")
    assertTrue(problems(other), problems(other).contains("in which the record's pattern [division by zero] finds nothing"))
  }

  @Test def aRecordHeldForItsEngineRunsOnceAndMustStillNotPass(): Unit = {
    val held = runAs(Dialect.Cockroach(),
      "onlyif cockroachdb # needs EXTRACT", "query I", "SELECT extract(year FROM DATE '2020-01-01')", "----", "2020")
    assertEquals(Skipped("held for cockroachdb", None), held.results.head.outcome)
    val stale = runAs(Dialect.Cockroach(),
      "onlyif cockroachdb # held: a finding", "query I", "SELECT 1 + 1", "----", "2")
    assertTrue(show(stale.results.head.outcome),
      show(stale.results.head.outcome).endsWith("but the engine now gives the record's answer; remove the hold"))
  }

  @Test def aStatementTheRunnerCannotCarryOutFailsInAnUpstreamScript(): Unit = {
    val r = runAs(Dialect.Cockroach(), "statement ok", "ALTER TABLE t ADD COLUMN b INT")
    assertTrue(problems(r), problems(r).contains("unsupported statement: ALTER statement: hold the record for cockroachdb"))
  }

  @Test def aViewReadsItsTablesAsTheyAre(): Unit = {
    val r = runAs(Dialect.Cockroach(),
      "statement ok", "CREATE TABLE t (a INT); CREATE VIEW v AS SELECT a FROM t; INSERT INTO t VALUES (7)", "",
      "query I", "SELECT a FROM v", "----", "7")
    assertEquals("", problems(r))
  }

  @Test def anRe2PatternReadsAsJavaReadsIt(): Unit = {
    assertEquals("<tuple\\{int\\}> IN x{2}", Runner.javaRegex("<tuple{int}> IN x{2}"))
  }

  @Test def aResultNoOrderByOrdersComparesAsAMultiset(): Unit = {
    val r = runAs(Dialect.DataFusion(),
      "statement ok", "CREATE TABLE t (a BIGINT UNSIGNED, b INT)", "",
      "statement ok", "INSERT INTO t VALUES (18446744073709551615, 2), (1, 1)", "",
      // An unsigned 64-bit column is the DECIMAL(20,0) that holds each value,
      // which an I column takes.
      "query II", "SELECT a, b FROM t", "----", "1 1", "18446744073709551615 2", "",
      "query II", "SELECT a, b FROM t ORDER BY b DESC", "----", "18446744073709551615 2", "1 1")
    assertEquals("", problems(r))
    val ordered = runAs(Dialect.DataFusion(),
      "statement ok", "CREATE TABLE t (b INT)", "", "statement ok", "INSERT INTO t VALUES (2), (1)", "",
      "query I", "SELECT b FROM t ORDER BY b", "----", "2", "1")
    assertTrue(problems(ordered), problems(ordered).contains("expected [2] got [1]"))
  }

  @Test def aHeldRecordWhoseRowsNoOrderByFixesIsNotRun(): Unit = {
    val held = runAs(Dialect.DataFusion(),
      "statement ok", "CREATE TABLE t (b INT)", "", "statement ok", "INSERT INTO t VALUES (2), (1)", "",
      "onlyif datafusion # needs the rows its plan reaches first", "query I", "SELECT b FROM t LIMIT 1", "----", "2")
    assertEquals(Skipped("held for datafusion", None), held.results.last.outcome)
  }

  @Test def aScriptsHeaderSaysWhatAnUnconstrainedDecimalIsElseTheDialectDoes(): Unit = {
    val sized = Dialect.Cockroach(Some(com.transformer.core.DataType.DecimalType(38, 2)))
    assertEquals(sized, sized.forScript("statement ok\nSELECT 1\n"))
    assertEquals(Dialect.Cockroach(Some(com.transformer.core.DataType.DecimalType(20, 4))),
      sized.forScript("# transformer: an unconstrained DECIMAL is DECIMAL(20,4)\n"))
  }
}
