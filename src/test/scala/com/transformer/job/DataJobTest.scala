package com.transformer.job

import com.transformer.core.{Catalog, ExecutedQuery, SqlExecutor, SqlExecutorRegistry}
import com.transformer.temporal.TemporalVariables
import org.junit.Assert._
import org.junit.Assume.assumeTrue
import org.junit.Test

import java.nio.file.{Files, Path}
import java.time.Instant
import scala.jdk.CollectionConverters._

class DataJobTest {

  private def tmpDir(prefix: String): Path = Files.createTempDirectory(prefix)

  private def writeCsv(dir: Path, name: String, contents: String): Path = {
    val p = dir.resolve(name)
    Files.writeString(p, contents)
    p
  }

  private def readCsv(p: Path): String = Files.readString(p)

  /** Concatenate every part file in an output directory, in lexical order, preserving
    * one header (from the first file). Multi-file output writes one part-NNNNN.csv per
    * source partition; for assertions we want the full contents.
    */
  private def readOutputDir(dir: Path): String = {
    val files = Files.list(dir)
    try {
      val parts = files.iterator().asScala.toVector
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-"))
        .sortBy(_.getFileName.toString)
      if (parts.isEmpty) ""
      else {
        val sb = new java.lang.StringBuilder()
        val first = Files.readString(parts.head)
        sb.append(first)
        val headerEnd = first.indexOf('\n')
        val header = if (headerEnd < 0) first else first.substring(0, headerEnd + 1)
        parts.tail.foreach { p =>
          val content = Files.readString(p)
          if (content.startsWith(header)) sb.append(content.substring(header.length))
          else sb.append(content)
        }
        sb.toString
      }
    } finally files.close()
  }

  /** Convenience: when we expect exactly one part file. */
  private def soleOutputFile(dir: Path): Path = {
    val files = Files.list(dir)
    try {
      val parts = files.iterator().asScala.toVector
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.startsWith("part-"))
      assertEquals(s"expected exactly one part file in $dir, got ${parts.map(_.getFileName)}", 1, parts.size)
      parts.head
    } finally files.close()
  }

  @Test def endToEndCsvFilterAndSelectWritesOutput(): Unit = {
    val inDir = tmpDir("dj-in-")
    writeCsv(inDir, "events.csv",
      "user_id,event,amount\n1,click,3\n2,buy,42\n1,buy,17\n3,click,1\n2,buy,7\n")
    val outDir = tmpDir("dj-out-").resolve("out")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT user_id, SUM(amount) AS total FROM events WHERE event = 'buy' GROUP BY user_id ORDER BY user_id"),
        outputFile = Some(OutputFilePath(outDir.toString))
      ))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertEquals(1, result.tasks.size)
    assertEquals(2L, result.tasks.head.rowsProduced)

    // Aggregate collapses to one partition, so one part file is expected.
    assertTrue(Files.isDirectory(outDir))
    val out = Files.readString(soleOutputFile(outDir))
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
        outputFile = Some(OutputFilePath(outDir.toString + "/day={{ today }}/data"))
      )),
      temporalVariables = Some(vars)
    )
    val result = job.run()
    assertTrue(result.succeeded)
    val expectedDir = outDir.resolve("day=20260101").resolve("data")
    assertTrue(s"Expected directory $expectedDir to exist", Files.isDirectory(expectedDir))
    assertTrue(s"Expected at least one part file in $expectedDir",
      Files.list(expectedDir).iterator().asScala.exists(_.getFileName.toString.startsWith("part-")))
  }

  @Test def temporalTemplateInSqlString(): Unit = {
    val inDir = tmpDir("dj-tsql-")
    writeCsv(inDir, "a.csv", "label,score\nA,1\nB,2\n")
    val outDir = tmpDir("dj-tsqlout-").resolve("out")
    val vars = TemporalVariables(Instant.parse("2026-01-01T05:30:21Z"))

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT '{{ today }}' AS day, label, score FROM t ORDER BY label"),
        outputFile = Some(OutputFilePath(outDir.toString))
      )),
      temporalVariables = Some(vars)
    )
    assertTrue(job.run().succeeded)
    val out = readOutputDir(outDir)
    assertEquals("day,label,score\n20260101,A,1\n20260101,B,2\n", out)
  }

  @Test def validationFailureTriggersAbortAndDiagnostic(): Unit = {
    val inDir = tmpDir("dj-val-")
    writeCsv(inDir, "a.csv", "id,value\n1,ok\n2,bad\n3,ok\n")
    val outDir = tmpDir("dj-valout-").resolve("out")
    val valFile = tmpDir("dj-valdiag-").resolve("diag.csv")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT * FROM t"),
        outputFile = Some(OutputFilePath(outDir.toString)),
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
    assertTrue(Files.isDirectory(outDir))
    assertTrue(Files.exists(valFile))
    val diag = readCsv(valFile)
    assertTrue(diag.contains("no_bad"))
  }

  @Test def downstreamTaskSeesUpstreamView(): Unit = {
    val inDir = tmpDir("dj-chain-")
    writeCsv(inDir, "a.csv", "x\n1\n2\n3\n4\n5\n")
    val mid = tmpDir("dj-mid-").resolve("mid")
    val out = tmpDir("dj-chainout-").resolve("out")

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
    assertEquals("s\n150\n", readOutputDir(out))
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
          outputFile = Some(OutputFilePath(outDir.resolve("a").toString))),
        SQLTask(name = Some("b"), viewName = Some("b"),
          sqlString = Some("SELECT id, x + 10 AS y FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("b").toString))),
        SQLTask(name = Some("c"), viewName = Some("c"),
          sqlString = Some("SELECT id, x * 2 AS z FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("c").toString))),
        SQLTask(name = Some("d"),
          sqlString = Some("SELECT b.y + c.z AS s FROM b JOIN c ON b.id = c.id"),
          outputFile = Some(OutputFilePath(outDir.resolve("d").toString)))
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
          outputFile = Some(OutputFilePath(outDir.resolve("bad").toString))),
        SQLTask(name = Some("downstream"), viewName = Some("downstream"),
          sqlString = Some("SELECT * FROM bad"),
          outputFile = Some(OutputFilePath(outDir.resolve("down").toString))),
        SQLTask(name = Some("indep"),
          sqlString = Some("SELECT id FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("indep").toString)))
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
    assertTrue("indep output dir should exist",
      Files.isDirectory(outDir.resolve("indep")))
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
          outputFile = Some(OutputFilePath(outDir.resolve("a").toString)),
          validations = Seq(Validation("no_bad",
            sqlString = Some("SELECT * FROM a WHERE value = 'bad'")))),
        SQLTask(name = Some("b"),
          sqlString = Some("SELECT id FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("b").toString)))
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
          outputFile = Some(OutputFilePath(outDir.resolve("a").toString))),
        SQLTask(name = Some("tb"),
          sqlString = Some("SELECT * FROM b"),
          outputFile = Some(OutputFilePath(outDir.resolve("b").toString)))
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

  @Test def multiPartitionInputWritesOneFilePerPartition(): Unit = {
    // 3 input CSV files => CsvReader produces 3 partitions => projection-only SQL
    // preserves the partition count => 3 part files in the output directory.
    val inDir = tmpDir("dj-multi-in-")
    writeCsv(inDir, "a.csv", "x\n1\n2\n")
    writeCsv(inDir, "b.csv", "x\n3\n4\n")
    writeCsv(inDir, "c.csv", "x\n5\n6\n")
    val outDir = tmpDir("dj-multi-out-").resolve("doubled")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT x * 2 AS y FROM t"),
        outputFile = Some(OutputFilePath(outDir.toString))
      ))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertEquals(6L, result.tasks.head.rowsProduced)

    val parts = Files.list(outDir).iterator().asScala.toVector
      .filter(p => p.getFileName.toString.startsWith("part-") && p.getFileName.toString.endsWith(".csv"))
      .sortBy(_.getFileName.toString)
    assertEquals(s"expected 3 part files, got ${parts.map(_.getFileName)}", 3, parts.size)
    assertEquals("part-00000.csv", parts.head.getFileName.toString)
    // Every part has its own header row.
    parts.foreach { p =>
      val text = Files.readString(p)
      assertTrue(s"$p missing header", text.startsWith("y\n"))
    }
    val allRows = parts.flatMap { p =>
      Files.readString(p).split('\n').toVector.drop(1).filter(_.nonEmpty)
    }.sorted
    assertEquals(Vector("10", "12", "2", "4", "6", "8"), allRows)
  }

  @Test def maxPartitionsCoalescesPartsBelowSourcePartitions(): Unit = {
    val inDir = tmpDir("dj-coalesce-in-")
    writeCsv(inDir, "a.csv", "x\n1\n")
    writeCsv(inDir, "b.csv", "x\n2\n")
    writeCsv(inDir, "c.csv", "x\n3\n")
    writeCsv(inDir, "d.csv", "x\n4\n")
    val outDir = tmpDir("dj-coalesce-out-").resolve("merged")

    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT * FROM t"),
        outputFile = Some(OutputFilePath(outDir.toString, maxPartitions = Some(2)))
      ))
    )
    assertTrue(job.run().succeeded)
    val parts = Files.list(outDir).iterator().asScala.toVector
      .filter(_.getFileName.toString.startsWith("part-"))
    assertEquals(s"expected 2 part files, got ${parts.size}", 2, parts.size)
  }

  @Test def maxPartitionsAboveSourceCountIsNoOp(): Unit = {
    val inDir = tmpDir("dj-cap-noop-in-")
    writeCsv(inDir, "only.csv", "x\n1\n2\n")
    val outDir = tmpDir("dj-cap-noop-out-").resolve("out")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT * FROM t"),
        outputFile = Some(OutputFilePath(outDir.toString, maxPartitions = Some(8)))
      ))
    )
    assertTrue(job.run().succeeded)
    val parts = Files.list(outDir).iterator().asScala.toVector
      .filter(_.getFileName.toString.startsWith("part-"))
    assertEquals(1, parts.size)
  }

  @Test def maxPartitionsOneCollapsesToSingleFile(): Unit = {
    val inDir = tmpDir("dj-single-in-")
    writeCsv(inDir, "a.csv", "x\n1\n2\n")
    writeCsv(inDir, "b.csv", "x\n3\n4\n")
    writeCsv(inDir, "c.csv", "x\n5\n6\n")
    val outDir = tmpDir("dj-single-out-").resolve("out")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT * FROM t"),
        outputFile = Some(OutputFilePath(outDir.toString, maxPartitions = Some(1)))
      ))
    )
    assertTrue(job.run().succeeded)
    val parts = Files.list(outDir).iterator().asScala.toVector
      .filter(_.getFileName.toString.startsWith("part-"))
    assertEquals(1, parts.size)
    val content = Files.readString(parts.head)
    val rows = content.split('\n').toVector.drop(1).filter(_.nonEmpty).sorted
    assertEquals(Vector("1", "2", "3", "4", "5", "6"), rows)
  }

  @Test def downstreamTaskReadsAllPartFilesFromUpstreamView(): Unit = {
    // The upstream task produces multiple part files; the downstream aggregate
    // must read every part. This exercises the "directory as a single view"
    // materializeIfNeeded path.
    val inDir = tmpDir("dj-multi-chain-in-")
    writeCsv(inDir, "a.csv", "x\n1\n2\n")
    writeCsv(inDir, "b.csv", "x\n3\n4\n")
    val outDir = tmpDir("dj-multi-chain-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "t")),
      sql = Seq(
        SQLTask(name = Some("doubled"), viewName = Some("doubled"),
          sqlString = Some("SELECT x * 2 AS y FROM t"),
          outputFile = Some(OutputFilePath(outDir.resolve("doubled").toString))),
        SQLTask(name = Some("summed"),
          sqlString = Some("SELECT SUM(y) AS s FROM doubled"),
          outputFile = Some(OutputFilePath(outDir.resolve("summed").toString)))
      )
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    assertEquals(4L, result.tasks.head.rowsProduced)
    assertEquals("s\n20\n", readOutputDir(outDir.resolve("summed")))
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

  @Test def buildDagExposesNodesAndDepsWithoutRunning(): Unit = {
    val inDir = tmpDir("dj-bd-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n")
    val outDir = tmpDir("dj-bd-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(
        SQLTask(name = Some("a"), viewName = Some("a"),
          sqlString = Some("SELECT id, x FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("a").toString))),
        SQLTask(name = Some("b"),
          sqlString = Some("SELECT id, x + 1 AS y FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("b").toString)))
      )
    )
    val dag = job.buildDag()
    assertEquals(2, dag.nodes.size)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
    assertEquals(Set(0), dag.nodes(1).deps)
    // Calling buildDag must not have created any output (no I/O happened).
    assertFalse("output dir should not exist after buildDag", Files.exists(outDir.resolve("a")))
  }

  @Test def progressListenerFiresStartAndFinishForEveryRunningTask(): Unit = {
    val inDir = tmpDir("dj-pl-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n2,2\n3,3\n")
    val outDir = tmpDir("dj-pl-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(
        SQLTask(name = Some("a"), viewName = Some("a"),
          sqlString = Some("SELECT id, x FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("a").toString))),
        SQLTask(name = Some("b"),
          sqlString = Some("SELECT id, x + 1 AS y FROM a"),
          outputFile = Some(OutputFilePath(outDir.resolve("b").toString)))
      )
    )
    val started = new java.util.concurrent.ConcurrentLinkedQueue[Integer]()
    val finished = new java.util.concurrent.ConcurrentHashMap[Integer, TaskResult]()
    val listener = new TaskProgressListener {
      def onTaskStart(taskIndex: Int, taskName: String): Unit = { started.add(taskIndex); () }
      def onTaskFinish(taskIndex: Int, result: TaskResult): Unit = { finished.put(taskIndex, result); () }
    }
    com.transformer.sql.exec.SqlEngine.init()
    val result = job.run(SqlExecutorRegistry.get, listener)
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)
    val startedSet = started.iterator().asScala.toSet
    assertEquals(Set[Integer](0, 1), startedSet)
    assertEquals(Set[Integer](0, 1), finished.keySet().asScala.toSet)
    assertTrue(finished.get(0).succeeded)
    assertTrue(finished.get(1).succeeded)
  }

  @Test def successWritesSuccessMarkerWithExecutionTimeAndFiles(): Unit = {
    val inDir = tmpDir("dj-mk-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n2,2\n3,3\n")
    val outDir = tmpDir("dj-mk-out-").resolve("out")
    val execTime = Instant.parse("2026-05-17T20:00:00Z")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT id, x FROM events"),
        outputFile = Some(OutputFilePath(outDir.toString))
      )),
      temporalVariables = Some(TemporalVariables(execTime))
    )
    val result = job.run()
    assertTrue(result.error.getOrElse("(no error)"), result.succeeded)

    val marker = RunMarker.read(outDir)
    assertTrue(s"_SUCCESS should exist in $outDir", marker.isDefined)
    val m = marker.get
    assertEquals("executionTime should round-trip", execTime, m.executionTime)
    assertEquals("rowsProduced should match", 3L, m.rowsProduced)
    assertEquals("format should be csv", "csv", m.format)
    assertTrue(s"outputFiles should be non-empty (got ${m.outputFiles})",
      m.outputFiles.nonEmpty && m.outputFiles.forall(_.startsWith("part-")))
  }

  @Test def failedTaskDoesNotWriteSuccessMarker(): Unit = {
    val inDir = tmpDir("dj-mk-fail-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n")
    val outDir = tmpDir("dj-mk-fail-out-").resolve("bad")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(SQLTask(
        sqlString = Some("SELECT no_such_col FROM events"),
        outputFile = Some(OutputFilePath(outDir.toString))
      ))
    )
    job.run()
    assertEquals(s"no _SUCCESS expected on failed task: $outDir",
      None, RunMarker.read(outDir))
  }

  @Test def runMarkerDiscoverFindsMultiplePartitionsSortedNewestFirst(): Unit = {
    val base = tmpDir("dj-disc-")
    val p1 = base.resolve("day=20260101"); Files.createDirectories(p1)
    val p2 = base.resolve("day=20260102"); Files.createDirectories(p2)
    val p3 = base.resolve("day=20260103"); Files.createDirectories(p3)
    // Stamp markers with explicit writtenAt so ordering is deterministic.
    RunMarker.write(p1, RunMarker(
      executionTime = Instant.parse("2026-01-01T00:00:00Z"),
      writtenAt = Instant.parse("2026-01-01T12:00:00Z"),
      rowsProduced = 1, format = "csv", outputFiles = Seq("part-00000.csv")))
    RunMarker.write(p3, RunMarker(
      executionTime = Instant.parse("2026-01-03T00:00:00Z"),
      writtenAt = Instant.parse("2026-01-03T12:00:00Z"),
      rowsProduced = 3, format = "csv", outputFiles = Seq("part-00000.csv")))
    RunMarker.write(p2, RunMarker(
      executionTime = Instant.parse("2026-01-02T00:00:00Z"),
      writtenAt = Instant.parse("2026-01-02T12:00:00Z"),
      rowsProduced = 2, format = "csv", outputFiles = Seq("part-00000.csv")))

    val found = RunMarker.discover(s"${base.toString}/day={{today}}")
    assertEquals(3, found.size)
    // Newest first.
    assertEquals(Instant.parse("2026-01-03T12:00:00Z"), found(0)._2.writtenAt)
    assertEquals(Instant.parse("2026-01-02T12:00:00Z"), found(1)._2.writtenAt)
    assertEquals(Instant.parse("2026-01-01T12:00:00Z"), found(2)._2.writtenAt)
  }

  @Test def runMarkerDiscoverWithoutTemplateMatchesExactPath(): Unit = {
    val base = tmpDir("dj-disc-exact-")
    val p = base.resolve("out"); Files.createDirectories(p)
    RunMarker.write(p, RunMarker(
      executionTime = Instant.parse("2026-01-01T00:00:00Z"),
      writtenAt = Instant.parse("2026-01-01T12:00:00Z"),
      rowsProduced = 1, format = "csv", outputFiles = Seq("part-00000.csv")))
    val found = RunMarker.discover(p.toString)
    assertEquals(1, found.size)
    assertEquals(p.toAbsolutePath.normalize().toString, found.head._1.toString)
  }

  @Test def runMarkerDiscoverReturnsEmptyWhenNoMarkers(): Unit = {
    val base = tmpDir("dj-disc-empty-")
    Files.createDirectories(base.resolve("day=20260101"))
    Files.createDirectories(base.resolve("day=20260102"))
    val found = RunMarker.discover(s"${base.toString}/day={{today}}")
    assertEquals(0, found.size)
  }

  @Test def runMarkerDiscoverIsolatesSiblingTasks(): Unit = {
    val base = tmpDir("dj-disc-sibs-")
    val a = base.resolve("a/day=20260101"); Files.createDirectories(a)
    val b = base.resolve("b/day=20260101"); Files.createDirectories(b)
    RunMarker.write(a, RunMarker(Instant.now(), Instant.now(), 1, "csv", Seq("part-00000.csv")))
    RunMarker.write(b, RunMarker(Instant.now(), Instant.now(), 2, "csv", Seq("part-00000.csv")))
    // Pattern includes the sibling viewName, so only its partitions match.
    val foundA = RunMarker.discover(s"${base.toString}/a/day={{today}}")
    assertEquals(1, foundA.size)
    assertEquals(a.toAbsolutePath.normalize().toString, foundA.head._1.toString)
  }

  @Test def validationFailureDoesNotWriteSuccessMarker(): Unit = {
    val inDir = tmpDir("dj-mk-vf-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n2,2\n")
    val outDir = tmpDir("dj-mk-vf-out-").resolve("out")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(SQLTask(
        name = Some("t"), viewName = Some("t"),
        sqlString = Some("SELECT id, x FROM events"),
        outputFile = Some(OutputFilePath(outDir.toString)),
        validations = Seq(Validation(
          name = "non_empty",
          sqlString = Some("SELECT * FROM t WHERE id > 0")
        ))
      ))
    )
    val result = job.run()
    assertFalse("job should not succeed when a validation fails", result.succeeded)
    assertEquals(s"no _SUCCESS expected on validation failure: $outDir",
      None, RunMarker.read(outDir))
    // Sanity: the part files themselves should still exist (writeOutput happened
    // before validations ran), it's just the marker that gates "blessed".
    val parts = Files.list(outDir)
    try assertTrue("part files should exist on disk",
      parts.iterator().asScala.exists(_.getFileName.toString.startsWith("part-")))
    finally parts.close()
  }

  @Test def progressListenerReceivesSkippedFinishForUpstreamFailure(): Unit = {
    val inDir = tmpDir("dj-pl-skip-")
    writeCsv(inDir, "events.csv", "id,x\n1,1\n")
    val outDir = tmpDir("dj-pl-skip-out-")
    val job = DataJob(
      inputs = Seq(InputFilePath(inDir.toString + "/*.csv", viewName = "events")),
      sql = Seq(
        SQLTask(name = Some("bad"), viewName = Some("bad"),
          sqlString = Some("SELECT no_such_col FROM events"),
          outputFile = Some(OutputFilePath(outDir.resolve("bad").toString))),
        SQLTask(name = Some("downstream"),
          sqlString = Some("SELECT * FROM bad"),
          outputFile = Some(OutputFilePath(outDir.resolve("down").toString)))
      )
    )
    val finished = new java.util.concurrent.ConcurrentHashMap[Integer, TaskResult]()
    val started = new java.util.concurrent.ConcurrentLinkedQueue[Integer]()
    val listener = new TaskProgressListener {
      def onTaskStart(taskIndex: Int, taskName: String): Unit = { started.add(taskIndex); () }
      def onTaskFinish(taskIndex: Int, result: TaskResult): Unit = { finished.put(taskIndex, result); () }
    }
    com.transformer.sql.exec.SqlEngine.init()
    job.run(SqlExecutorRegistry.get, listener)
    // bad runs (and fails); downstream is skipped — onTaskStart must NOT fire for it.
    val startedSet = started.iterator().asScala.toSet
    assertTrue(s"bad should have started: $startedSet", startedSet.contains(0))
    assertFalse(s"downstream should be skipped, never started: $startedSet", startedSet.contains(1))
    // Both must receive a finish event.
    assertEquals(Set[Integer](0, 1), finished.keySet().asScala.toSet)
    finished.get(1).status match {
      case _: TaskStatus.Skipped => // ok
      case other                 => fail(s"expected downstream=Skipped, got $other")
    }
  }
}
