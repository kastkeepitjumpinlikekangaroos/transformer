package com.transformer.slt

import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The ledger: its table is read strictly, failing with the line it cannot
  * read, and against every script under `src/test/slt` each rule is cited by
  * a skip or pinned, each pin is a record of the engine's own answer
  * (`onlyif transformer <id>`) in the script of this repo's own its row
  * names, and a port, which keeps another engine's answers, holds the
  * engine's only as the twins of its skips. */
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
      else if (!pins(name).contains(r.id))
        Some(s"${r.id}: $name holds no record 'onlyif ${Script.EngineName} ${r.id}' that is not a twin")
      else None
    }
    assertTrue(problems.mkString("\n"), problems.isEmpty)
  }

  @Test def everyRuleIsCitedByASkipOrPinned(): Unit = {
    val cited = scripts.iterator.flatMap(_._2).flatMap(r => r.skipRule ++ r.onlyRule).toSet
    val idle = ledger.ids.filterNot(cited)
    assertTrue(s"rules no script cites: ${idle.mkString(", ")}", idle.isEmpty)
  }

  @Test def aPortHoldsTheEnginesAnswersOnlyAsTwins(): Unit = {
    val problems = for {
      (name, records) <- scripts if isPort(name)
      (prev, rec) <- withPrevious(records) if rec.onlyRule.isDefined && !isTwin(prev, rec)
    } yield s"$name:${rec.line}: 'onlyif ${Script.EngineName} ${rec.onlyRule.get}' follows no skip of its rule " +
      "and SQL: a port keeps the engine's answers only as the twins of its skips"
    assertTrue(problems.mkString("\n"), problems.isEmpty)
  }

  /** Prints each script's skips by rule, the counts docs/testing.md gives. */
  @Test def skipsByRule(): Unit = {
    val tally = for {
      (name, records) <- scripts
      counts = records.flatMap(_.skipRule).groupMapReduce(identity)(_ => 1)(_ + _) if counts.nonEmpty
    } yield name -> counts
    tally.foreach { case (name, counts) =>
      System.out.println(s"$name: ${counts.toSeq.sortBy { case (id, n) => (-n, id) }.map { case (id, n) => s"$id $n" }.mkString(", ")}")
    }
    val total = tally.flatMap(_._2).groupMapReduce(_._1)(_._2)(_ + _)
    System.out.println(s"total ${total.values.sum}: " +
      total.toSeq.sortBy { case (id, n) => (-n, id) }.map { case (id, n) => s"$id $n" }.mkString(", "))
    assertTrue("no script skips a record", total.nonEmpty)
  }
}

object LedgerTest {

  /** Where the scripts are: the runfiles tree under Bazel, the source tree
    * otherwise. */
  private val Root: Path = Paths.get("src/test/slt")

  /** The directories of ports, scripts with another engine's answers. */
  private val Ports = Set("sqlite", "postgres", "spark", "cockroach", "datafusion", "tpch")

  private def isPort(name: String): Boolean = Ports.contains(name.takeWhile(_ != '/'))

  private lazy val ledger: Ledger = Ledger.load(Ledger.DefaultPath)

  /** Every script, by its path under [[Root]], parsed against the ledger. */
  private lazy val scripts: Vector[(String, Vector[Script.Record])] = {
    val walk = Files.walk(Root)
    val files =
      try walk.iterator.asScala.filter(_.toString.endsWith(".test")).toVector.sortBy(_.toString)
      finally walk.close()
    files.map { p =>
      val name = Root.relativize(p).toString.replace('\\', '/')
      val include: String => String = n => new String(Files.readAllBytes(p.getParent.resolve(n)), StandardCharsets.UTF_8)
      name -> Script.parse(name, new String(Files.readAllBytes(p), StandardCharsets.UTF_8), ledger,
        Dialect.forPath(name).format, include)
    }
  }

  private def withPrevious(records: Vector[Script.Record]): Vector[(Option[Script.Record], Script.Record)] =
    records.indices.toVector.map(i => (if (i == 0) None else Some(records(i - 1)), records(i)))

  /** Whether `rec` is the twin of `prev`: it follows a skip of its rule, with
    * the same SQL. */
  private def isTwin(prev: Option[Script.Record], rec: Script.Record): Boolean =
    prev.exists(p => p.skipRule.isDefined && p.skipRule == rec.onlyRule && Script.sqlOf(p) == Script.sqlOf(rec))

  /** The rules a script's records pin: those of its `onlyif transformer`
    * records that are not twins. */
  private def pins(name: String): Set[String] =
    scripts.find(_._1 == name).toVector.flatMap { case (_, records) =>
      withPrevious(records).collect { case (prev, rec) if rec.onlyRule.isDefined && !isTwin(prev, rec) => rec.onlyRule.get }
    }.toSet
}
