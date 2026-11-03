package com.transformer.core

import com.transformer.core.Settings._
import org.junit.Assert._
import org.junit.Test

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Paths}

/** [[Settings]]: each setting read from its property, else its environment
  * twin, the property winning; each value parsed as its reader always
  * parsed it, or its default; and README's "Settings" table listing every
  * setting, with its twin and its default, and none that is not one. */
class SettingsTest {

  private def read(s: Setting[_], properties: Map[String, String], environment: Map[String, String]): Option[String] =
    s.valueIn(properties.getOrElse(_, null), environment.getOrElse(_, null))

  @Test def eachSettingIsReadFromItsPropertyThenItsEnvironmentTwin(): Unit =
    All.foreach { s =>
      val (p, e) = (s.property, s.environmentVariable)
      assertEquals(None, read(s, Map(), Map()))
      assertEquals(Some("7"), read(s, Map(p -> "7"), Map()))
      assertEquals(Some("8"), read(s, Map(), Map(e -> "8")))
      assertEquals(s"$p wins over $e", Some("7"), read(s, Map(p -> "7"), Map(e -> "8")))
      assertEquals(s"a blank $p is unset", Some("8"), read(s, Map(p -> "  "), Map(e -> "8")))
      assertEquals("a value is trimmed", Some("7"), read(s, Map(p -> " 7 "), Map()))
      assertEquals(None, read(s, Map(p -> ""), Map(e -> " ")))
    }

  @Test def eachEnvironmentTwinIsItsPropertyUpperCased(): Unit = {
    All.foreach { s =>
      assertTrue(s"${s.property} is a transformer.* name", s.property.matches("""transformer\.[a-z_]+(\.[a-z_]+)*"""))
      assertTrue(s"${s.environmentVariable} is a TRANSFORMER_* name", s.environmentVariable.matches("TRANSFORMER_[A-Z_]+"))
    }
    assertEquals("TRANSFORMER_HASH_ZERO", HashZero.environmentVariable)
    assertEquals("TRANSFORMER_SPILL_DIR", SpillDir.environmentVariable)
    assertEquals("TRANSFORMER_SCHEDULER_SHARD_MIN_SIZE", ShardMinSize.environmentVariable)
    assertEquals("TRANSFORMER_MACRO_FORCE_SPILL", ForceSpill.environmentVariable)
    assertEquals("no two settings share a property", All.size, All.map(_.property).distinct.size)
  }

  @Test def eachSettingHasItsDefault(): Unit = {
    assertEquals(8192, BatchCapacity.parse(None))
    assertEquals(2 * math.max(1, Runtime.getRuntime.availableProcessors), Parallelism.parse(None))
    assertEquals(Scheduler.parallelism, ShardCount.parse(None))
    assertEquals(Long.MaxValue, ShardMinSize.parse(None))
    assertEquals(1000000L, BroadcastThreshold.parse(None))
    Seq(MetricsEnabled, ForceSpill, HashZero, Verify, AuditLeaks).foreach(s => assertFalse(s.property, s.parse(None)))
    assertEquals(System.getProperty("java.io.tmpdir") + "/transformer-spill", SpillDir.parse(None))
    assertEquals(None, ChaosSeed.parse(None))
    assertEquals(None, Fault.parse(None))
  }

  @Test def eachValueParsesAsItsReaderParsedIt(): Unit = {
    assertEquals(2, BatchCapacity.parse(Some("2")))
    assertEquals("a value that is no number is the default", 8192, BatchCapacity.parse(Some("many")))
    assertEquals("a capacity below 1 is the default", 8192, BatchCapacity.parse(Some("0")))
    assertEquals("at least one thread", 1, Parallelism.parse(Some("0")))
    assertEquals("at least one shard", 1, ShardCount.parse(Some("-3")))
    assertEquals(1L, ShardMinSize.parse(Some("1")))
    Seq("true", "TRUE", "True", "1").foreach(v => assertTrue(v, HashZero.parse(Some(v))))
    Seq("false", "0", "yes", "on").foreach(v => assertFalse(v, HashZero.parse(Some(v))))
    assertEquals("/data/spill", SpillDir.parse(Some("/data/spill")))
    assertEquals(Some(42L), ChaosSeed.parse(Some("42")))
    val e = assertThrows(classOf[IllegalArgumentException], () => { val _ = ChaosSeed.parse(Some("x")) })
    assertEquals("transformer.chaos must be a whole number, a seed, not 'x'", e.getMessage)
    assertEquals(Some("commit:1:throw"), Fault.parse(Some("commit:1:throw")))
  }

  /** README's table has a row for each setting, in `All`'s order: its
    * property, its environment twin and its default. */
  @Test def readmeListsEverySettingWithItsTwinAndDefault(): Unit = {
    val readme = new String(Files.readAllBytes(Paths.get("README.md")), StandardCharsets.UTF_8).replace("\r\n", "\n")
    val section = readme.split("\n## ").find(_.startsWith("Settings\n"))
      .getOrElse(throw new AssertionError("README.md has no \"## Settings\" section"))
    val rows = section.split("\n").toSeq.filter(_.startsWith("| `transformer.")).map { line =>
      line.split('|').map(_.trim).toSeq.slice(1, 4)
    }
    def backticked(cell: String): String = cell.stripPrefix("`").stripSuffix("`")
    assertEquals("README's Settings rows, by property",
      All.map(_.property), rows.map(r => backticked(r.head)))
    All.zip(rows).foreach { case (s, row) =>
      assertEquals(s"${s.property}'s environment variable", s.environmentVariable, backticked(row(1)))
      assertEquals(s"${s.property}'s default", s.default, row(2))
    }
  }
}
