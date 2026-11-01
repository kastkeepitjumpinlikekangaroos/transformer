package com.transformer.slt

import com.transformer.slt.Runner._
import org.junit.Assert._
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import org.junit.runners.Parameterized.Parameters

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Every sqllogictest script under `src/test/slt`, one JUnit case each.
  *
  * A script under `src/test/slt/sqlite/` is a file of SQLite's corpus, read
  * in [[Dialect.Sqlite]]; every other script is read in
  * [[Dialect.Transformer]]: this repo's own, or under `postgres/` a port of
  * PostgreSQL's regression tests with Postgres's answers. Either way each record must pass: a record the
  * engine answers differently by design carries `skipif transformer` and a
  * comment saying why. Every query also runs under each verification mode
  * ([[Modes.Verification]]) and must print what the baseline printed.
  *
  * Set `SLT_FILTER` to a substring of a script's path to run only those
  * scripts.
  */
@RunWith(classOf[Parameterized])
class SltTest(name: String, path: Path) {

  @Test def scriptPasses(): Unit = {
    val dialect = if (name.startsWith("sqlite/")) Dialect.Sqlite else Dialect.Transformer
    val result = new Runner(dialect).run(name, new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
    val problems = result.problems
    if (problems.nonEmpty || result.stoppedAt.isDefined) {
      val shown = problems.take(10).map { r =>
        val sql = r.record match {
          case q: Script.Query => q.sql
          case s: Script.Statement => s.sql
          case _ => ""
        }
        s"  line ${r.record.line}: ${r.outcome}\n    ${sql.replace("\n", "\n    ")}"
      }
      fail(s"${result.summary}\n${shown.mkString("\n")}" +
        (if (problems.length > 10) s"\n  ... and ${problems.length - 10} more" else ""))
    }
    assertTrue(s"$name checked nothing", result.results.exists(r => r.record match {
      case _: Script.Query => true
      case s: Script.Statement => s.expectError
      case _ => false
    }))
  }
}

object SltTest {

  /** Where the scripts are: the runfiles tree under Bazel, the source tree
    * otherwise. */
  private val Root: Path = Paths.get("src/test/slt")

  @Parameters(name = "{0}")
  def scripts(): java.util.Collection[Array[AnyRef]] = {
    require(Files.isDirectory(Root), s"no scripts at ${Root.toAbsolutePath}")
    val filter = Option(System.getenv("SLT_FILTER")).filter(_.nonEmpty)
    val walk = Files.walk(Root)
    val files =
      try walk.iterator.asScala.filter(_.toString.endsWith(".test")).toVector.sortBy(_.toString)
      finally walk.close()
    files
      .map(p => Root.relativize(p).toString.replace('\\', '/') -> p)
      .filter { case (n, _) => filter.forall(n.contains) }
      .map { case (n, p) => Array[AnyRef](n, p) }
      .asJava
  }
}
