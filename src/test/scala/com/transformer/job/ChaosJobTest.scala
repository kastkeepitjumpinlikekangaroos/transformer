package com.transformer.job

import com.transformer.core.Chaos
import org.junit.Assert._
import org.junit.Test

import java.nio.file.Files

/** Under `--config=chaos` ([[Chaos]]), run under `-Dtransformer.chaos=7` and
  * the sharding gates at their smallest: a job that reads an input, spills a
  * sharded aggregate, writes its output and hands it to a task after it
  * passes every chaos point, and answers as it does without them. */
class ChaosJobTest {

  @Test def aJobPassesEveryPointAndAnswers(): Unit = {
    assertTrue("the target's jvm_flags set -Dtransformer.chaos=7", Chaos.enabled)
    val root = Files.createTempDirectory("chaos-job-")
    val in = Files.createDirectories(root.resolve("in"))
    (0 until 3).foreach { f =>
      Files.writeString(in.resolve(s"part-$f.csv"),
        ("k,v" +: (0 until 300).map(i => s"${i % 7},${f * 300 + i}")).mkString("", "\n", "\n"))
    }
    val spill = Map("spill" -> "true", "spill_threshold_bytes" -> "1024")
    val job = DataJob(
      inputs = Seq(InputFilePath(in.toString + "/*.csv", viewName = "t")),
      sql = Seq(
        SQLTask(
          name = Some("sums"),
          sqlString = Some("SELECT k, SUM(v) AS s, COUNT(*) AS n FROM t GROUP BY k"),
          outputFile = Some(OutputFilePath(root.resolve("sums").toString, options = spill)),
          viewName = Some("sums")),
        SQLTask(
          name = Some("total"),
          sqlString = Some("SELECT SUM(s) AS total, SUM(n) AS rows FROM sums"),
          outputFile = Some(OutputFilePath(root.resolve("total").toString)))))
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertEquals(Seq(7L, 1L), result.tasks.map(_.rowsProduced))
    val total = Files.readString(Files.list(root.resolve("total")).filter(_.toString.endsWith(".csv")).findFirst().get)
    assertEquals(s"total,rows\n${(0 until 900).sum},900\n", total)
    Chaos.Points.foreach(p => assertTrue(s"point $p was passed", Chaos.passed(p) > 0L))
  }
}
