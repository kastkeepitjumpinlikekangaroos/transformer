package com.transformer.slt

import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

/** The ledger: its table is read strictly, failing with the line it cannot
  * read, and each rule is pinned by a record of the engine's own answer,
  * after a line `onlyif transformer <id>`, in the script of this repo's own
  * its row names. */
class LedgerTest {

  import LedgerTest._

  // ---- the table ------------------------------------------------------------------

  private def ledgerError(rows: String*): String =
    assertThrows(classOf[Script.ParseError], () => {
      Ledger.parse("ledger.md", (Seq(Ledger.Heading, "", "| id | rule | refusal | stated in | decided in | pinned by |",
        "|---|---|---|---|---|---|") ++ rows).mkString("\n")); ()
    }).getMessage

  @Test def aLedgerRowItCannotReadFailsNamingItsLine(): Unit = {
    assertTrue(ledgerError("| `a-b` | r | | s | d |").startsWith("ledger.md:5: a row has 6 cells, not 5"))
    assertTrue(ledgerError("| a-b | r | | s | d | `x.test` |").startsWith("ledger.md:5: an id is lower-case words"))
    assertTrue(ledgerError("| `refused-x` | r | | s | d | `x.test` |").contains("gives the text its refusal must contain"))
    assertTrue(ledgerError("| `a-b` | r | `t` | s | d | `x.test` |").contains("only a refused- rule gives"))
    assertTrue(ledgerError("| `a-b` | r | | s | d | x.test |").contains("is pinned by a script"))
    assertTrue(ledgerError("| `a-b` | r | | s | d | `x.test` |", "| `a-b` | q | | s | d | `x.test` |")
      .startsWith("ledger.md:6: 'a-b' is the id of line 5 too"))
  }

  @Test def aLedgerReadsItsColumnsAsWrittenAndAnEscapedBar(): Unit = {
    val l = Ledger.parse("ledger.md", Seq(Ledger.Heading, "", "| id | rule | refusal | stated in | decided in | pinned by |",
      "|:---|---|---|---|---|---:|", "| `refused-or` | `a \\|\\| b` is refused. | `use \\|\\| to join` | s | d | `x.test` |",
      "", "| not | the | table | any | more | here |").mkString("\n"))
    assertEquals(Vector(Ledger.Rule("refused-or", "`a \\|\\| b` is refused.", Some("use || to join"), "s", "d",
      "x.test", 5)), l.rules)
    assertTrue(assertThrows(classOf[Script.ParseError], () => { Ledger.parse("ledger.md", "# none"); () })
      .getMessage.contains("no section"))
    assertTrue(assertThrows(classOf[Script.ParseError], () => {
      Ledger.parse("ledger.md", Seq(Ledger.Heading, "", "| id | rule |", "|---|---|").mkString("\n")); ()
    }).getMessage.startsWith("ledger.md:3: the table's columns are id | rule | refusal"))
  }

  // ---- the ledger against the scripts ---------------------------------------------

  @Test def everyRuleIsPinnedInAScriptOfThisReposOwn(): Unit = {
    val problems = ledger.rules.flatMap { r =>
      val name = r.pinnedBy
      if (!Files.isRegularFile(Root.resolve(name))) Some(s"${r.id}: the script that pins it, $name, does not exist")
      else if (isPort(name)) Some(s"${r.id}: $name is a port, which keeps another engine's answers, not the engine's")
      else if (!pins(name).contains(r.id)) Some(s"${r.id}: $name holds no record 'onlyif ${Script.EngineName} ${r.id}'")
      else None
    }
    assertTrue(problems.mkString("\n"), problems.isEmpty)
  }
}

object LedgerTest {

  /** Where the scripts are: the runfiles tree under Bazel, the source tree
    * otherwise. */
  private val Root: Path = Paths.get("src/test/slt")

  /** The directories of ports, scripts with another engine's answers. */
  private val Ports = Set("sqlite", "postgres")

  private def isPort(name: String): Boolean = Ports.contains(name.takeWhile(_ != '/'))

  private lazy val ledger: Ledger = Ledger.load(Ledger.DefaultPath)

  /** The rules a script pins: the ids its lines `onlyif transformer <id>`
    * name. */
  private def pins(name: String): Set[String] =
    new String(Files.readAllBytes(Root.resolve(name)), StandardCharsets.UTF_8).split("\n").iterator
      .map(_.trim.split("\\s+").toVector)
      .collect { case Vector("onlyif", engine, id, _*) if engine.equalsIgnoreCase(Script.EngineName) => id }
      .toSet
}
