package com.transformer.job

import com.transformer.core.{Catalog, ExecutedQuery, SqlExecutor, SqlExecutorRegistry}
import com.transformer.temporal.TemporalVariables
import org.junit.Assert._
import org.junit.Assume.assumeTrue
import org.junit.Test

import java.nio.file.{Files, Path}
import java.time.Instant

class DataJobTest {

  private def tmpDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private def writeCsv(dir: Path, name: String, contents: String): Path = {
    val p = dir.resolve(name)
    Files.writeString(p, contents)
    p
  }

  private def readCsv(p: Path): String = Files.readString(p)

  @Test def endToEndCsvFilterAndSelectWritesOutput(): Unit = {
    val inDir = tmpDir("dj-in-")
    writeCsv(inDir, "events.csv",
      "user_id,event,amount\n1,click,3\n2,buy,42\n1,buy,17\n3,click,1\n2,buy,7\n")
    val outFile = tmpDir("dj-out-").resolve("out.csv")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT user_id, SUM(amount) AS total FROM events WHERE event = 'buy' GROUP BY user_id ORDER BY user_id"),
        outputFile = Some(OutputFilePath(outFile.toString))
      ))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertEquals(1, result.tasks.size)
    assertEquals(2L, result.tasks.head.rowsProduced)

    val out = readCsv(outFile)
    assertEquals("user_id,total\n2,49\n1,17\n", normalizeOrder(out))
  }

  /** CSV output for GROUP BY is in hash-map insertion order, which isn't stable
    * across runs without ORDER BY. We add ORDER BY in the test above; for safety,
    * normalize by sorting rows by everything before the first occurrence.
    */
  private def normalizeOrder(s: String): String = {
    val lines = s.split('\n').filter(_.nonEmpty)
    if (lines.length <= 1) s
    else (Seq(lines.head) ++ lines.tail.sortBy(_.head.toInt).reverse).mkString("\n") + "\n"
  }

  @Test def temporalTemplateInOutputPath(): Unit = {
    val inDir = tmpDir("dj-tin-")
    writeCsv(inDir, "a.csv", "x\n1\n2\n3\n")
    val outDir = tmpDir("dj-tout-")
    val vars = TemporalVariables(Instant.parse("2026-01-01T05:30:21Z"))

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT * FROM t"),
        outputFile = Some(OutputFilePath(outDir.toString + "/day={{ today }}/data.csv"))
      )),
      temporalVariables = Some(vars)
    )
    val result = job.run()
    assertTrue(result.succeeded)
    val expectedPath = outDir.resolve("day=20260101").resolve("data.csv")
    assertTrue(s"Expected file $expectedPath to exist", Files.exists(expectedPath))
  }

  @Test def temporalTemplateInSqlString(): Unit = {
    val inDir = tmpDir("dj-tsql-")
    writeCsv(inDir, "a.csv", "label,score\nA,1\nB,2\n")
    val outFile = tmpDir("dj-tsqlout-").resolve("out.csv")
    val vars = TemporalVariables(Instant.parse("2026-01-01T05:30:21Z"))

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT '{{ today }}' AS day, label, score FROM t ORDER BY label"),
        outputFile = Some(OutputFilePath(outFile.toString))
      )),
      temporalVariables = Some(vars)
    )
    assertTrue(job.run().succeeded)
    val out = readCsv(outFile)
    assertEquals("day,label,score\n20260101,A,1\n20260101,B,2\n", out)
  }

  @Test def validationFailureTriggersAbortAndDiagnostic(): Unit = {
    val inDir = tmpDir("dj-val-")
    writeCsv(inDir, "a.csv", "id,value\n1,ok\n2,bad\n3,ok\n")
    val outFile = tmpDir("dj-valout-").resolve("out.csv")
    val valFile = tmpDir("dj-valdiag-").resolve("diag.csv")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT * FROM t"),
        outputFile = Some(OutputFilePath(outFile.toString)),
        viewName = Some("result"),
        validations = Seq(Validation(
          name = "no_bad",
          sqlString = Some("SELECT * FROM result WHERE value = 'bad'")
        ))
      )),
      validationResultsOutput = Some(OutputFilePath(valFile.toString))
    )
    val result = job.run()
    assertFalse(result.succeeded)
    assertEquals(1, result.tasks.size)
    result.tasks.head.status match {
      case TaskStatus.ValidationFailed(failures) =>
        assertEquals(1, failures.size)
        assertEquals(1L, failures.head.rowCount)
        assertEquals("no_bad", failures.head.validationName)
      case other => fail(s"Expected ValidationFailed, got $other")
    }
    // Output should still exist (we persist on validation failure for debugging).
    assertTrue(Files.exists(outFile))
    assertTrue(Files.exists(valFile))
    val diag = readCsv(valFile)
    assertTrue(diag.contains("no_bad"))
  }

  @Test def downstreamTaskSeesUpstreamView(): Unit = {
    val inDir = tmpDir("dj-chain-")
    writeCsv(inDir, "a.csv", "x\n1\n2\n3\n4\n5\n")
    val mid = tmpDir("dj-mid-").resolve("mid.csv")
    val out = tmpDir("dj-chainout-").resolve("out.csv")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(
        SQLTask(
          sqlString = Some("SELECT x * 10 AS x10 FROM t"),
          outputFile = Some(OutputFilePath(mid.toString)),
          viewName = Some("mid")
        ),
        SQLTask(
          sqlString = Some("SELECT SUM(x10) AS s FROM mid"),
          outputFile = Some(OutputFilePath(out.toString))
        )
      )
    )
    assertTrue(job.run().succeeded)
    assertEquals("s\n150\n", readCsv(out))
  }

  @Test def diamondDagAllTasksSucceedAndOrderRespected(): Unit = {
    val inDir = tmpDir("dj-diam-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n2,2\n3,3\n")
    val outDir = tmpDir("dj-diam-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(
        SQLTask(name = Some("a"), viewName = Some("a"),
          sqlString = Some("SELECT id, x FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("a.csv").toString))),
        SQLTask(name = Some("b"), viewName = Some("b"),
          sqlString = Some("SELECT id, x + 10 AS y FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("b.csv").toString))),
        SQLTask(name = Some("c"), viewName = Some("c"),
          sqlString = Some("SELECT id, x * 2 AS z FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("c.csv").toString))),
        SQLTask(name = Some("d"),
          sqlString = Some("SELECT b.y + c.z AS s FROM b JOIN c ON b.id = c.id"),
          outputFile = Some(OutputFilePath(outDir.resolve("d.csv").toString)))
      )
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertEquals(4, result.tasks.size)
    val byName = result.tasks.map(t => t.taskName -> t).toMap
    val dStarted = byName("d").startedAt
    assertFalse(s"d.started=$dStarted should be at-or-after b.finished=${byName("b").finishedAt}",
      dStarted.isBefore(byName("b").finishedAt))
    assertFalse(s"d.started=$dStarted should be at-or-after c.finished=${byName("c").finishedAt}",
      dStarted.isBefore(byName("c").finishedAt))
  }

  @Test def failedTaskCausesDownstreamSkippedAndIndependentSiblingSucceeds(): Unit = {
    val inDir = tmpDir("dj-fail-")
    writeCsv(inDir, "events.csv", "id,x\n1,10\n2,20\n")
    val outDir = tmpDir("dj-fail-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(
        SQLTask(name = Some("bad"), viewName = Some("bad"),
          sqlString = Some("SELECT no_such_col FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("bad.csv").toString))),
        SQLTask(name = Some("downstream"), viewName = Some("downstream"),
          sqlString = Some("SELECT * FROM bad"),
          outputFile = Some(OutputFilePath(outDir.resolve("down.csv").toString))),
        SQLTask(name = Some("indep"),
          sqlString = Some("SELECT id FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("indep.csv").toString)))
      )
    )
    val result = job.run()
    assertFalse("job should fail overall", result.succeeded)
    val byName = result.tasks.map(t => t.taskName -> t).toMap
    byName("bad").status match {
      case _: TaskStatus.Failed => // ok
      case other                 => fail(s"expected bad=Failed, got $other")
    }
    byName("downstream").status match {
      case s: TaskStatus.Skipped =>
        assertTrue(s.reason, s.reason.contains("bad"))
      case other => fail(s"expected downstream=Skipped, got $other")
    }
    assertEquals(s"indep should succeed: ${byName("indep").status}",
      TaskStatus.Succeeded, byName("indep").status)
    assertTrue("indep output should exist",
      Files.exists(outDir.resolve("indep.csv")))
  }

  @Test def validationFailurePropagatesAsSkippedDownstream(): Unit = {
    val inDir = tmpDir("dj-vfail-")
    writeCsv(inDir, "t.csv", "id,value\n1,ok\n2,bad\n")
    val outDir = tmpDir("dj-vfail-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(
        SQLTask(name = Some("a"), viewName = Some("a"),
          sqlString = Some("SELECT * FROM t"),
          outputFile = Some(OutputFilePath(outDir.resolve("a.csv").toString)),
          validations = Seq(Validation("no_bad",
            sqlString = Some("SELECT * FROM a WHERE value = 'bad'")))),
        SQLTask(name = Some("b"),
          sqlString = Some("SELECT id FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("b.csv").toString)))
      )
    )
    val result = job.run()
    assertFalse(result.succeeded)
    val byName = result.tasks.map(t => t.taskName -> t).toMap
    byName("a").status match {
      case _: TaskStatus.ValidationFailed => // ok
      case other                          => fail(s"expected a=ValidationFailed, got $other")
    }
    byName("b").status match {
      case s: TaskStatus.Skipped => assertTrue(s.reason, s.reason.contains("a"))
      case other                 => fail(s"expected b=Skipped, got $other")
    }
  }

  @Test def emptySqlSucceedsTrivially(): Unit = {
    val result = DataJob(inputs = Nil, sql = Nil).run()
    assertTrue(result.error.getOrElse(""), result.succeeded)
    assertEquals(0, result.tasks.size)
  }

  @Test def setupErrorReportedAsJobError(): Unit = {
    val inDir = tmpDir("dj-setup-")
    writeCsv(inDir, "events.csv", "id\n1\n")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(SQLTask(name = Some("a"), viewName = Some("a"),
        sqlString = Some("SELECT * FROM a")))  // self-reference
    )
    val result = job.run()
    assertFalse(result.succeeded)
    assertTrue(result.error.isDefined)
    assertTrue(result.error.get, result.error.get.toLowerCase.contains("self-cycle"))
  }

  @Test def independentRootsRunConcurrently(): Unit = {
    assumeTrue("requires >=2 CPUs", Runtime.getRuntime.availableProcessors >= 2)
    val inDir = tmpDir("dj-par-")
    writeCsv(inDir, "a.csv", "x\n1\n")
    writeCsv(inDir, "b.csv", "x\n1\n")
    val outDir = tmpDir("dj-par-out-")
    val sleepy = sleepyExecutor(120L)
    val job = DataJob(
      inputs = Seq(
        InputFilePath(inDir.resolve("a.csv").toString, viewName = "a"),
        InputFilePath(inDir.resolve("b.csv").toString, viewName = "b")
      ),
      sql = Seq(
        SQLTask(name = Some("ta"),
          sqlString = Some("SELECT * FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("a.csv").toString))),
        SQLTask(name = Some("tb"),
          sqlString = Some("SELECT * FROM b"),
          outputFile = Some(OutputFilePath(outDir.resolve("b.csv").toString)))
      )
    )
    val result = job.run(sleepy)
    assertTrue(result.error.getOrElse(""), result.succeeded)
    val a = result.tasks.find(_.taskName == "ta").get
    val b = result.tasks.find(_.taskName == "tb").get
    assertTrue(
      s"expected overlap: a=[${a.startedAt}, ${a.finishedAt}], b=[${b.startedAt}, ${b.finishedAt}]",
      a.startedAt.isBefore(b.finishedAt) && b.startedAt.isBefore(a.finishedAt)
    )
  }

  private def sleepyExecutor(ms: Long): SqlExecutor = {
    com.transformer.sql.exec.SqlEngine.init()
    val real = SqlExecutorRegistry.get
    new SqlExecutor {
      override def execute(sql: String, catalog: Catalog): ExecutedQuery = {
        Thread.sleep(ms)
        real.execute(sql, catalog)
      }
    }
  }
}
