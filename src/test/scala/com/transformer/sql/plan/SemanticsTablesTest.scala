package com.transformer.sql.plan

import com.transformer.fuzz.oracle.CleanRejection
import com.transformer.sql.plan.Semantics.Generated
import org.junit.Assert._
import org.junit.Test

/** The semantics tables under `src/test/semantics` ([[SemanticsTables]]):
  * each is generated in memory and must be its file line for line, a
  * difference failing with its unified diff; the row path and the vector path
  * agree on every cell; and every error a cell shows is a clean one by
  * `CleanRejection`'s rule, refused at bind time in words that name the SQL,
  * or failing on a value with a message it knows. */
class SemanticsTablesTest {

  import SemanticsTablesTest._

  @Test def everyTableIsItsFile(): Unit = {
    val diffs = generated.flatMap { case (file, g) =>
      Semantics.diff(file, SemanticsTables.read(SemanticsTables.Dir, file), g.lines)
    }
    assertTrue(diffs.mkString("\n") + "\nIf the rule changed on purpose, regenerate the tables with\n" +
      "  bazel run //src/test/scala/com/transformer/sql/plan:semantics_tables -- --update\n" +
      "and review the diff in the same commit.", diffs.isEmpty)
  }

  @Test def bothPathsAgreeOnEveryCell(): Unit = {
    val diverged = generated.flatMap { case (file, g) => g.diverged.map(l => s"$file: $l") }
    assertTrue(s"${diverged.size} cells whose paths disagree:\n${diverged.take(20).mkString("\n")}", diverged.isEmpty)
  }

  @Test def everyErrorIsClean(): Unit = {
    val unclean = for {
      (file, g) <- generated
      r <- g.raised
      why <- try { CleanRejection.classify(r.sql, r.phase, r.error); None }
             catch { case e: AssertionError => Some(e.getMessage) }
    } yield s"$file: $why"
    assertTrue(s"${unclean.size} errors that are not clean:\n${unclean.distinct.take(20).mkString("\n")}", unclean.isEmpty)
    assertTrue("no table shows an error", generated.exists(_._2.raised.nonEmpty))
  }
}

object SemanticsTablesTest {

  /** Every table, generated once for the test's every check. */
  lazy val generated: Seq[(String, Generated)] = SemanticsTables.Tables.map { case (file, g) => file -> g() }
}
