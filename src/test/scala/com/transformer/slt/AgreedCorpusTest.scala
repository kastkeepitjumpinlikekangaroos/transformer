package com.transformer.slt

import com.transformer.fuzz.AgreedGen.Feature
import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The corpus of answers Postgres and DuckDB agree on keeps its quotas
  * (plans/testing/14, W6): each feature's scripts under `src/test/slt/agreed`,
  * named by the `# feature:` line each carries, hold at least the records
  * the feature's quota says, counting the records the engine runs with the
  * references' answer (a query, or an error all three raise), not a skip, its
  * twin or a statement that makes a table. A regeneration that drops a
  * feature's records, as a generator gate or a normalization rule could
  * silently, fails here, as the fuzzers' coverage guards fail on a corpus that
  * stopped reaching a shape. `slt_test` runs the scripts themselves.
  */
class AgreedCorpusTest {

  private val Dir: Path = Paths.get("src/test/slt/agreed")

  private lazy val ledger: Ledger = Ledger.load(Ledger.DefaultPath)

  private def scripts: Vector[(String, String)] = {
    assertTrue(s"no agreed corpus at ${Dir.toAbsolutePath}", Files.isDirectory(Dir))
    val list = Files.list(Dir)
    try list.iterator.asScala.filter(_.toString.endsWith(".test")).toVector.sortBy(_.toString)
      .map(p => p.getFileName.toString -> new String(Files.readAllBytes(p), StandardCharsets.UTF_8))
    finally list.close()
  }

  /** The feature a script's `# feature:` line names, of which it has one. */
  private def featureOf(name: String, text: String): String = {
    val lines = text.split("\n").toVector.filter(_.startsWith(AgreedCorpus.FeatureLine))
    assertEquals(s"$name names its feature in one '${AgreedCorpus.FeatureLine}' line", 1, lines.length)
    lines.head.stripPrefix(AgreedCorpus.FeatureLine).trim
  }

  /** The records a script runs with the references' answer. */
  private def kept(name: String, text: String): Int =
    Script.parse(s"agreed/$name", text, ledger).count {
      case q: Script.Query => q.conditions.isEmpty
      case s: Script.Statement => s.expectError && s.conditions.isEmpty
      case _ => false
    }

  @Test def everyScriptNamesAFeatureOfTheCorpus(): Unit = {
    val known = Feature.All.map(_.id).toSet
    scripts.foreach { case (name, text) =>
      val feature = featureOf(name, text)
      assertTrue(s"$name names the feature '$feature', which is none of ${known.toSeq.sorted.mkString(", ")}", known(feature))
    }
  }

  @Test def everyFeatureKeepsItsQuota(): Unit = {
    val counts = scripts.groupMapReduce { case (name, text) => featureOf(name, text) } { case (name, text) => kept(name, text) }(_ + _)
    val short = Feature.All.flatMap { f =>
      val n = counts.getOrElse(f.id, 0)
      if (n < f.quota) Some(s"${f.id}: $n records kept, its quota ${f.quota} (${f.title})") else None
    }
    assertTrue(s"features of the agreed corpus below their quota:\n  ${short.mkString("\n  ")}", short.isEmpty)
  }

  /** Each script says how it was made: its seeds, the tool's commit, both
    * references' versions and the normalization rules in force. */
  @Test def everyScriptSaysHowItWasMade(): Unit = scripts.foreach { case (name, text) =>
    Seq("# seeds: ", "# tool commit: ", "# references: PostgreSQL ", "# normalization: ").foreach { prefix =>
      assertTrue(s"$name has no '$prefix' line", text.split("\n").exists(_.startsWith(prefix)))
    }
    val rules = text.split("\n").find(_.startsWith("# normalization: ")).get.stripPrefix("# normalization: ").split(", ").toSet
    assertEquals(s"$name lists the normalization rules in force", AgreedCorpus.Normalize.Rules.map(_._1).toSet, rules)
  }
}
