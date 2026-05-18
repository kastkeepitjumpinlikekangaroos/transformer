package com.transformer.job

import com.transformer.temporal.TemporalVariables
import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path}
import java.time.Instant

class DirectoryJobLoaderTest {

  private def tmpDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private def writeFile(p: Path, contents: String): Path = {
    if (p.getParent != null) Files.createDirectories(p.getParent)
    Files.writeString(p, contents)
    p
  }

  @Test def loadsAndRunsBasicJob(): Unit = {
    val jobDir = tmpDir("djl-basic-")
    writeFile(jobDir.resolve("data/events.csv"), "id,value\n1,a\n2,b\n")
    writeFile(jobDir.resolve("inputs/events/config.json"),
      """{"path": "data/events.csv"}""")
    writeFile(jobDir.resolve("tables/passthrough/main.sql"),
      "SELECT * FROM events")

    val outputDir = tmpDir("djl-basic-out-")
    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(outputDir.toString))

    assertEquals(1, job.inputs.length)
    assertEquals("events", job.inputs.head.viewName)
    assertEquals(1, job.sql.length)
    assertEquals(Some("passthrough"), job.sql.head.viewName)

    val result = job.run()
    assertTrue(result.error.getOrElse("(none)"), result.succeeded)
    val outFile = outputDir.resolve("passthrough.csv")
    assertTrue(s"$outFile should exist", Files.exists(outFile))
    assertEquals("id,value\n1,a\n2,b\n", Files.readString(outFile))
  }

  @Test def relativeInputPathIsResolvedAgainstJobDir(): Unit = {
    val jobDir = tmpDir("djl-rel-")
    writeFile(jobDir.resolve("data/x.csv"), "n\n7\n")
    writeFile(jobDir.resolve("inputs/x/cfg.json"), """{"path":"data/x.csv"}""")
    writeFile(jobDir.resolve("tables/t/main.sql"), "SELECT n * 2 AS m FROM x")
    val outputDir = tmpDir("djl-rel-out-")

    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(outputDir.toString))
    // The resolved input path should start with the absolute job dir, not just "data/x.csv".
    val abs = jobDir.toAbsolutePath.normalize().toString
    assertTrue(
      s"Expected input path to be resolved under $abs but was ${job.inputs.head.path}",
      job.inputs.head.path.startsWith(abs)
    )
    assertTrue(job.run().succeeded)
    assertEquals("m\n14\n", Files.readString(outputDir.resolve("t.csv")))
  }

  @Test def absoluteInputPathIsLeftAlone(): Unit = {
    val dataFile = writeFile(tmpDir("djl-data-").resolve("d.csv"), "v\n1\n")
    val jobDir = tmpDir("djl-abs-")
    writeFile(jobDir.resolve("inputs/d/cfg.json"),
      s"""{"path":"${dataFile.toString}"}""")
    writeFile(jobDir.resolve("tables/t/main.sql"), "SELECT * FROM d")
    val outputDir = tmpDir("djl-abs-out-")

    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(outputDir.toString))
    assertEquals(dataFile.toString, job.inputs.head.path)
    assertTrue(job.run().succeeded)
  }

  @Test def loadsValidationsFromValidationsDir(): Unit = {
    val jobDir = tmpDir("djl-val-")
    writeFile(jobDir.resolve("data/x.csv"), "id\n1\n2\n")
    writeFile(jobDir.resolve("inputs/x/cfg.json"), """{"path":"data/x.csv"}""")
    writeFile(jobDir.resolve("tables/t/main.sql"), "SELECT * FROM x")
    writeFile(jobDir.resolve("tables/t/validations/no_negatives.sql"),
      "SELECT * FROM t WHERE id < 0")
    writeFile(jobDir.resolve("tables/t/validations/non_empty.sql"),
      "SELECT * FROM t WHERE 1=0")
    val outputDir = tmpDir("djl-val-out-")
    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(outputDir.toString))

    val validations = job.sql.head.validations.map(_.name).toSet
    assertEquals(Set("no_negatives", "non_empty"), validations)

    val result = job.run()
    assertTrue(result.error.getOrElse("(none)"), result.succeeded)
  }

  @Test def validationFailureAbortsAsExpected(): Unit = {
    val jobDir = tmpDir("djl-valfail-")
    writeFile(jobDir.resolve("data/x.csv"), "id,kind\n1,ok\n2,bad\n")
    writeFile(jobDir.resolve("inputs/src/cfg.json"), """{"path":"data/x.csv"}""")
    writeFile(jobDir.resolve("tables/clean/main.sql"), "SELECT * FROM src")
    writeFile(jobDir.resolve("tables/clean/validations/no_bad.sql"),
      "SELECT * FROM clean WHERE kind = 'bad'")
    val outputDir = tmpDir("djl-valfail-out-")
    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(outputDir.toString))

    val result = job.run()
    assertFalse(result.succeeded)
    result.tasks.head.status match {
      case TaskStatus.ValidationFailed(failures) =>
        assertEquals("no_bad", failures.head.validationName)
        assertEquals(1L, failures.head.rowCount)
      case other => fail(s"Expected ValidationFailed, got $other")
    }
  }

  @Test def templatedInputPathsAndOutputDir(): Unit = {
    val jobDir = tmpDir("djl-tmpl-")
    writeFile(jobDir.resolve("data/2026-01-01.csv"), "id\n10\n")
    writeFile(jobDir.resolve("inputs/x/cfg.json"),
      """{"path":"data/{{ iso_date }}.csv"}""")
    writeFile(jobDir.resolve("tables/derived/main.sql"),
      "SELECT '{{ today }}' AS d, id FROM x")
    val outputBase = tmpDir("djl-tmpl-out-")
    val vars = TemporalVariables(Instant.parse("2026-01-01T00:00:00Z"))
    val job = DirectoryJobLoader.load(
      jobDir,
      outputDir = Some(outputBase.toString + "/day={{ today }}"),
      temporalVariables = Some(vars))

    val result = job.run()
    assertTrue(result.error.getOrElse("(none)"), result.succeeded)
    val outPath = outputBase.resolve("day=20260101").resolve("derived.csv")
    assertTrue(s"$outPath should exist", Files.exists(outPath))
    assertEquals("d,id\n20260101,10\n", Files.readString(outPath))
  }

  @Test def downstreamTableCanReferenceUpstreamAlphabetically(): Unit = {
    val jobDir = tmpDir("djl-chain-")
    writeFile(jobDir.resolve("data/in.csv"), "x\n10\n20\n30\n")
    writeFile(jobDir.resolve("inputs/src/cfg.json"), """{"path":"data/in.csv"}""")
    writeFile(jobDir.resolve("tables/a_doubled/main.sql"),
      "SELECT x * 2 AS x2 FROM src")
    writeFile(jobDir.resolve("tables/b_summed/main.sql"),
      "SELECT SUM(x2) AS s FROM a_doubled")
    val outputDir = tmpDir("djl-chain-out-")
    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(outputDir.toString))
    val result = job.run()
    assertTrue(result.error.getOrElse("(none)"), result.succeeded)
    assertEquals("s\n120\n", Files.readString(outputDir.resolve("b_summed.csv")))
  }

  @Test def defaultOutputDirIsUnderJobDir(): Unit = {
    val jobDir = tmpDir("djl-default-out-")
    writeFile(jobDir.resolve("data/x.csv"), "n\n42\n")
    writeFile(jobDir.resolve("inputs/x/cfg.json"), """{"path":"data/x.csv"}""")
    writeFile(jobDir.resolve("tables/t/main.sql"), "SELECT * FROM x")
    val job = DirectoryJobLoader.load(jobDir)
    assertTrue(job.run().succeeded)
    val expected = jobDir.toAbsolutePath.normalize().resolve("output").resolve("t.csv")
    assertTrue(s"$expected should exist", Files.exists(expected))
  }

  @Test def jsonScalarOptionsBecomeStringMap(): Unit = {
    val jobDir = tmpDir("djl-mapopts-")
    writeFile(jobDir.resolve("inputs/raw/cfg.json"),
      """{"path":"data/foo.csv","options":{"header":false,"delimiter":";","batchSize":2048}}""")
    Files.createDirectories(jobDir.resolve("tables"))

    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(tmpDir("djl-mapopts-out-").toString))
    val opts = job.inputs.head.options
    assertEquals("false", opts("header"))
    assertEquals(";", opts("delimiter"))
    assertEquals("2048", opts("batchSize"))
  }

  @Test def errorsWhenInputHasNoJsonConfig(): Unit = {
    val jobDir = tmpDir("djl-no-cfg-")
    Files.createDirectories(jobDir.resolve("inputs/empty"))
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      DirectoryJobLoader.load(jobDir, outputDir = Some(tmpDir("o-").toString)))
    assertTrue(ex.getMessage, ex.getMessage.contains("empty"))
  }

  @Test def errorsWhenInputHasMultipleJsonConfigs(): Unit = {
    val jobDir = tmpDir("djl-multi-cfg-")
    writeFile(jobDir.resolve("inputs/dup/one.json"), """{"path":"x"}""")
    writeFile(jobDir.resolve("inputs/dup/two.json"), """{"path":"x"}""")
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      DirectoryJobLoader.load(jobDir, outputDir = Some(tmpDir("o-").toString)))
    assertTrue(ex.getMessage, ex.getMessage.contains("multiple"))
  }

  @Test def errorsWhenTableHasNoMainSql(): Unit = {
    val jobDir = tmpDir("djl-no-main-")
    Files.createDirectories(jobDir.resolve("tables/incomplete"))
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      DirectoryJobLoader.load(jobDir, outputDir = Some(tmpDir("o-").toString)))
    assertTrue(ex.getMessage, ex.getMessage.contains("main.sql"))
  }

  @Test def acceptsEmptyJobDir(): Unit = {
    val jobDir = tmpDir("djl-empty-")
    val job = DirectoryJobLoader.load(jobDir, outputDir = Some(tmpDir("o-").toString))
    assertEquals(0, job.inputs.length)
    assertEquals(0, job.sql.length)
  }

  @Test def errorsWhenJobDirMissing(): Unit = {
    val notReal = java.nio.file.Paths.get("/nonexistent/path/that/does/not/exist/" + System.nanoTime())
    val ex = assertThrows(classOf[IllegalArgumentException], () =>
      DirectoryJobLoader.load(notReal, outputDir = Some(tmpDir("o-").toString)))
    assertTrue(ex.getMessage, ex.getMessage.contains("does not exist"))
  }
}
