package com.transformer.slt

import com.transformer.slt.Runner._
import org.junit.Assert._
import org.junit.Test

import java.nio.file.Paths

/** The runner over scripts written here, against a ledger of two rules: a
  * condition on transformer cites a rule, a skipped record is reported as its
  * rule, and a twin runs like any record. */
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

  // ---- running -------------------------------------------------------------------

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

  @Test def anAuditTalliesSkipsByRule(): Unit = {
    val result = run(
      "skipif transformer refused-except-intersect", "query I", "SELECT a FROM t EXCEPT SELECT a FROM t", "----", "",
      "onlyif mysql", "query I", "SELECT 1", "----", "1")
    val c = result.compact(keepRefusals = false)
    assertEquals(Map("refused-except-intersect" -> 1), c.skipTally)
    assertEquals(Some(2), c.counts.get("skipped"))
  }
}
