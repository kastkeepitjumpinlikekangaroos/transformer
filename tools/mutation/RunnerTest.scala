package com.transformer.tools.mutation

import java.util.SplittableRandom
import org.junit.Assert._
import org.junit.Test
import scala.collection.mutable

/** The runner's decisions over a scripted executor and a file held in memory:
  * the stages in order and no further than the first kill, a target that fails
  * on the unmutated file too counting as no kill, a timeout, a stillborn
  * mutant, and the file put back after every mutant. */
class RunnerTest {
  private val source = "object A {\n  def f(a: Int): Boolean = a < 1\n}\n"
  private val mutant = Mutant("m001", "A.scala", Mutator.sites(source).find(_.operator == "relational").get)

  private final class Memory extends Workspace {
    var text: String = source
    def read(file: String): String = text
    def write(file: String, text: String): Unit = this.text = text
  }

  /** Answers each test run from `answer`, given the file's text, the targets
    * and whether the run may use Bazel's cache; records every run. */
  private final class Script(
      memory: Memory,
      compiles: Boolean = true,
      answer: (String, Seq[String], Boolean) => Map[String, String]) extends Executor {
    val runs: mutable.ArrayBuffer[(Seq[String], Boolean, Boolean)] = mutable.ArrayBuffer.empty
    def build(targets: Seq[String]): Option[String] = if (compiles) None else Some("A.scala:2: error: type mismatch")
    def test(targets: Seq[String], flags: Seq[String], timeoutSeconds: Long, cached: Boolean): TestRun = {
      val mutated = memory.text != source
      runs += ((targets, mutated, cached))
      val statuses = answer(memory.text, targets, cached)
      TestRun(statuses, statuses.collect { case (t, "FAILED") => t -> Vector(s"$t.someTest") })
    }
  }

  private val stages = Vector(
    Stage(1, "own package", Vector("//a:unit_test"), Vector.empty, 60),
    Stage(2, "engine, slt and job", Vector("//e:engine_test", "//e:slt_test"), Vector.empty, 60),
    Stage(3, "the rest", Vector("//f:fuzz_test"), Vector.empty, 60))

  private def passing(targets: Seq[String]): Map[String, String] = targets.map(_ -> "PASSED").toMap

  private def run(memory: Memory, script: Script): Result =
    new Runner(memory, script, _ => ()).run(mutant, Seq("//a:lib"), stages)

  @Test def aTargetThatFailsOnlyOnTheMutantKillsItAndTheLaterStagesDoNotRun(): Unit = {
    val memory = new Memory
    val script = new Script(memory, answer = (text, targets, _) =>
      targets.map(t => t -> (if (t == "//e:engine_test" && text != source) "FAILED" else "PASSED")).toMap)
    val r = run(memory, script)
    assertEquals("killed", r.outcome)
    assertEquals(2, r.stage)
    assertEquals("//e:engine_test", r.target)
    assertEquals(Vector("//e:engine_test.someTest"), r.tests)
    assertEquals("stage 1, stage 2, the rerun on the unmutated file; never stage 3",
      Seq((Seq("//a:unit_test"), true, true), (Seq("//e:engine_test", "//e:slt_test"), true, true),
        (Seq("//e:engine_test"), false, false)),
      script.runs.toSeq)
    assertEquals(source, memory.text)
  }

  @Test def aTargetThatFailsOnTheUnmutatedFileTooKillsNothing(): Unit = {
    val memory = new Memory
    val script = new Script(memory, answer = (_, targets, _) =>
      targets.map(t => t -> (if (t == "//a:unit_test") "FAILED" else "PASSED")).toMap)
    val r = run(memory, script)
    assertEquals("survived", r.outcome)
    assertTrue(r.note, r.note.contains("//a:unit_test failed on the unmutated file too"))
    assertEquals("every stage ran", 4, script.runs.size)
    assertEquals(source, memory.text)
  }

  /** A target that fails at random kills a mutant only when its rerun on the
    * unmutated file passes; when the rerun fails too it is no kill, and the
    * later stages decide. */
  @Test def aTargetThatFailsAtRandomCountsOnlyWhenItsRerunPasses(): Unit = {
    val random = new SplittableRandom(19L)
    var counted = 0
    var refused = 0
    (1 to 400).foreach { _ =>
      val memory = new Memory
      val draws = mutable.ArrayBuffer.empty[Boolean]
      val script = new Script(memory, answer = (_, targets, _) =>
        targets.map { t =>
          if (t == "//a:unit_test") {
            val fails = random.nextBoolean()
            draws += fails
            t -> (if (fails) "FAILED" else "PASSED")
          } else t -> "PASSED"
        }.toMap)
      val r = run(memory, script)
      draws.toSeq match {
        case Seq(true, false) =>
          assertEquals("killed", r.outcome)
          counted += 1
        case Seq(true, true) =>
          assertEquals("survived", r.outcome)
          assertTrue(r.note.contains("failed on the unmutated file too"))
          refused += 1
        case Seq(false) => assertEquals("survived", r.outcome)
        case other => fail(s"unexpected draws $other")
      }
      assertEquals(source, memory.text)
    }
    assertTrue(s"both cases drawn: $counted counted, $refused refused", counted > 50 && refused > 50)
  }

  @Test def aTargetPastItsTimeoutIsATimeout(): Unit = {
    val memory = new Memory
    val script = new Script(memory, answer = (text, targets, _) =>
      targets.map(t => t -> (if (t == "//a:unit_test" && text != source) "TIMEOUT" else "PASSED")).toMap)
    val r = run(memory, script)
    assertEquals("timeout", r.outcome)
    assertEquals(1, r.stage)
  }

  @Test def aMutantThatDoesNotCompileIsStillbornAndRunsNoTest(): Unit = {
    val memory = new Memory
    val script = new Script(memory, compiles = false, answer = (_, targets, _) => passing(targets))
    val r = run(memory, script)
    assertEquals("stillborn", r.outcome)
    assertTrue(r.note, r.note.contains("error: type mismatch"))
    assertTrue(script.runs.isEmpty)
    assertEquals(source, memory.text)
  }

  @Test def aTestTargetThatDoesNotBuildWithTheMutantMakesItStillborn(): Unit = {
    val memory = new Memory
    val script = new Script(memory, answer = (_, targets, _) =>
      targets.map(t => t -> (if (t == "//e:slt_test") "FAILED_TO_BUILD" else "PASSED")).toMap)
    val r = run(memory, script)
    assertEquals("stillborn", r.outcome)
    assertEquals(2, r.stage)
  }

  @Test def aMutantThatPassesEveryStageSurvives(): Unit = {
    val memory = new Memory
    val script = new Script(memory, answer = (_, targets, _) => passing(targets))
    val r = run(memory, script)
    assertEquals("survived", r.outcome)
    assertEquals(3, script.runs.size)
    assertTrue(script.runs.forall { case (_, mutated, cached) => mutated && cached })
  }

  @Test def theFileIsPutBackWhenATestRunThrows(): Unit = {
    val memory = new Memory
    val script = new Script(memory, answer = (_, _, _) => throw new IllegalStateException("bazel crashed"))
    val e = assertThrows(classOf[IllegalStateException], () => { val _ = run(memory, script) })
    assertEquals("bazel crashed", e.getMessage)
    assertEquals(source, memory.text)
  }

  @Test def aMutantWhoseSiteNoLongerMatchesTheFileIsRefused(): Unit = {
    val memory = new Memory
    memory.text = source.replace("a < 1", "a > 1")
    val script = new Script(memory, answer = (_, targets, _) => passing(targets))
    val e = assertThrows(classOf[IllegalStateException], () => { val _ = run(memory, script) })
    assertTrue(e.getMessage, e.getMessage.contains("no longer holds"))
  }

  @Test def buildEventsGiveEachTargetsWorstShardAndItsFailedTests(): Unit = {
    val dir = java.nio.file.Files.createTempDirectory("bep")
    val xml = dir.resolve("test.xml")
    val _ = java.nio.file.Files.writeString(xml,
      "<testsuites><testsuite name='A'><testcase name='good' classname='p.ATest' time='0.1' />" +
        "<testcase name='bad' classname='p.ATest' time='0.2'><failure message='x'>trace</failure></testcase>" +
        "</testsuite></testsuites>")
    def attempt(label: String, shard: Int, status: String, ms: Int) =
      s"""{"id":{"testResult":{"label":"$label","run":1,"shard":$shard,"attempt":1}},"testResult":""" +
        s"""{"testActionOutput":[{"name":"test.xml","uri":"file://$xml"}],"testAttemptDurationMillis":"$ms","status":"$status"}}"""
    val bep = dir.resolve("bep.json")
    val _ = java.nio.file.Files.writeString(bep, Seq(
      attempt("//s:slt_test", 1, "PASSED", 900),
      attempt("//s:slt_test", 2, "FAILED", 1200),
      attempt("//a:unit_test", 1, "PASSED", 50),
      """{"id":{"targetCompleted":{"label":"//b:broken_test"}},"aborted":{"reason":"ANALYSIS_FAILURE"}}""",
      """{"id":{"targetCompleted":{"label":"//a:unit_test"}},"completed":{"success":true}}""").mkString("\n"))
    val events = BuildEvents.read(bep)
    assertEquals(Map("//s:slt_test" -> "FAILED", "//a:unit_test" -> "PASSED", "//b:broken_test" -> "FAILED_TO_BUILD"),
      events.statuses)
    assertEquals(Map("//s:slt_test" -> 1200L, "//a:unit_test" -> 50L), events.millis)
    assertEquals(Vector("p.ATest.bad"), BuildEvents.failedTests(xml))
  }
}
