package com.transformer.gui

import com.transformer.job.{DirectoryJobLoader, JobFiles, JobResult, JobRunRecord, JobTaskSummary, TaskResult, TaskRunRecord, TaskRunStatus}
import com.transformer.temporal.TemporalVariables

import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.Comparator
import scala.jdk.CollectionConverters._

/** Covers what a [[JobSession]] reads back from disk.
  *
  * [[JobSession.readOutputAsView]]'s format detection — the path the
  * interactive SQL console takes to register a task's persisted output as a
  * queryable view. Regression for: ext-less parquet output directories (the
  * default emitted by `DirectoryJobLoader`, e.g. `output/<view>/`) were routed
  * to `CsvReader`, which parsed the raw `PAR1` parquet bytes as a CSV header and
  * produced garbled "column names" in any subsequent `Unknown column` error.
  *
  * Reopening a finished run: a session opened on a job directory rebuilds each
  * task from the run's records and must show what the run itself reported.
  * The general check is `fuzz/dag_scheduler_fuzz_test`'s `Hydration` oracle;
  * these are the defects it found, distilled.
  */
class JobSessionTest {

  private var tmpDir: Path = _

  @Before def setUp(): Unit = {
    tmpDir = Files.createTempDirectory("job-session-test-")
  }

  @After def tearDown(): Unit = {
    if (tmpDir != null && Files.exists(tmpDir)) {
      Files.walk(tmpDir).sorted(Comparator.reverseOrder())
        .iterator().asScala
        .foreach(Files.deleteIfExists(_))
    }
  }

  private def writeRecord(dir: Path, format: String): Unit = {
    Files.createDirectories(dir)
    val t = Instant.parse("2026-01-01T00:00:00Z")
    TaskRunRecord.write(dir, TaskRunRecord(
      schemaVersion = TaskRunRecord.SchemaVersion,
      taskName      = "test",
      status        = TaskRunStatus.Succeeded,
      errorMessage  = None,
      executionTime = t,
      startedAt     = t,
      finishedAt    = t,
      writtenAt     = t,
      rowsProduced  = 0L,
      format        = format,
      outputFiles   = Seq(s"part-00000.$format"),
      validations   = Nil
    ))
  }

  /** Ext-less directory with a parquet-format marker must be routed to the
    * parquet reader, NOT to `CsvReader`. We feed it a fake `PAR1` part file
    * so a wrong (CSV) routing would surface a CSV-specific error — a correct
    * (parquet) routing surfaces a parquet error instead.
    */
  @Test def readOutputAsViewUsesMarkerFormatForExtlessParquetDir(): Unit = {
    val dir = tmpDir.resolve("test")
    writeRecord(dir, "parquet")
    // Drop a fake part file so the CSV fallback (if the routing were wrong)
    // would at least find something to open and produce its CSV-specific
    // error — distinguishing routing failure from "no files at all".
    Files.writeString(dir.resolve("part-00000.parquet"), "PAR1binary")

    val ex = try { JobSession.readOutputAsView(dir.toString); null }
             catch { case t: Throwable => t }

    assertNotNull("expected a parquet decode error on the fake file", ex)
    val msg = Option(ex.getMessage).getOrElse("").toLowerCase
    assertFalse(s"unexpectedly routed through CSV: ${ex.getMessage}",
      msg.contains("no csv files"))
  }

  /** A directory whose marker says `csv` must still be routed to the CSV
    * reader even if `.parquet` appears somewhere in the path (an unusual but
    * possible case — e.g. the user named a parent directory `runs.parquet/`).
    * The marker takes precedence over the substring check.
    */
  @Test def readOutputAsViewMarkerCsvOverridesPathSubstring(): Unit = {
    val dir = tmpDir.resolve("runs.parquet").resolve("test")
    writeRecord(dir, "csv")
    Files.writeString(dir.resolve("part-00000.csv"), "id,name\n1,alpha\n")

    val view = JobSession.readOutputAsView(dir.toString)
    assertEquals(Vector("id", "name"), view.schema.fieldNames.toVector)
  }

  /** Without a marker we fall back to the path-substring check. A bare CSV
    * file path still works (legacy single-file path).
    */
  @Test def readOutputAsViewFallsBackToSubstringWhenNoMarker(): Unit = {
    val dir = tmpDir.resolve("legacy")
    Files.createDirectories(dir)
    val csv = dir.resolve("data.csv")
    Files.writeString(csv, "a,b\n1,2\n")
    val view = JobSession.readOutputAsView(csv.toString)
    assertEquals(Vector("a", "b"), view.schema.fieldNames.toVector)
  }

  // ---- reopening a finished run ----------------------------------------------

  private val ExecutionTime: Instant = Instant.parse("2026-01-01T05:30:21Z")

  /** A job directory with one task per terminal status: `a_ok` succeeds,
    * `b_fail` selects a column that does not exist, `c_val` fails its
    * validation, and `d_skip` reads `b_fail`. */
  private def writeFourStatusJob(): Path = {
    val jobDir = tmpDir.resolve("job")
    Files.createDirectories(jobDir.resolve("data"))
    Files.writeString(jobDir.resolve("data").resolve("x.csv"), "k,v\n1,10\n2,20\n")
    JobFiles.addInput(jobDir, "x", "data/x.csv", None, cache = true, Map.empty)
    JobFiles.addTable(jobDir, "a_ok", "SELECT k, v FROM x", None, None, None)
    JobFiles.addTable(jobDir, "b_fail", "SELECT k, nope AS v FROM x", None, None, None)
    JobFiles.addTable(jobDir, "c_val", "SELECT k, v FROM a_ok", None, None, None)
    JobFiles.addValidation(jobDir, "c_val", "always", "SELECT k FROM c_val")
    JobFiles.addTable(jobDir, "d_skip", "SELECT k, v FROM b_fail", None, None, None)
    jobDir
  }

  private def runAndReopen(jobDir: Path): (JobResult, JobSession) = {
    val result = DirectoryJobLoader.load(jobDir, TemporalVariables(ExecutionTime)).run()
    val session = new JobSession()
    session.setExecutionTime(ExecutionTime)
    session.openJobDir(jobDir)
    assertEquals(RunState.Idle, session.runState)
    (result, session)
  }

  private def hydrated(session: JobSession, i: Int): TaskResult = session.taskStates(i) match {
    case UiTaskState.Done(r) => r
    case other => throw new AssertionError(s"task $i reopened as $other, not Done")
  }

  /** Every task's three timestamps come back as the run recorded them. The
    * enqueue time used to be dropped, so a reopened task had `enqueuedAt` at the
    * epoch and the DAG canvas and the details panel showed every task queued for
    * fifty-six years. */
  @Test def aReopenedRunKeepsEachTasksEnqueueTime(): Unit = {
    val (result, session) = runAndReopen(writeFourStatusJob())
    result.tasks.indices.foreach { i =>
      val run = result.tasks(i)
      val back = hydrated(session, i)
      assertEquals(s"${run.taskName} enqueuedAt", run.enqueuedAt, back.enqueuedAt)
      assertEquals(s"${run.taskName} startedAt", run.startedAt, back.startedAt)
      assertEquals(s"${run.taskName} finishedAt", run.finishedAt, back.finishedAt)
      assertEquals(s"${run.taskName} queue wait", run.queueWaitMillis, back.queueWaitMillis)
    }
  }

  /** A task that failed or was skipped wrote no output, and the run says so
    * (`outputPath = None`); reopened, it reported its record's directory as its
    * output, so the details panel said "Wrote to" a directory holding nothing
    * but the record. */
  @Test def aReopenedFailedOrSkippedTaskReportsNoOutput(): Unit = {
    val (result, session) = runAndReopen(writeFourStatusJob())
    assertEquals(Vector("Succeeded", "Failed", "ValidationFailed", "Skipped"),
      result.tasks.map(_.status.getClass.getSimpleName.stripSuffix("$")).toVector)
    result.tasks.indices.foreach { i =>
      val run = result.tasks(i)
      assertEquals(s"${run.taskName} output", run.outputPath, hydrated(session, i).outputPath)
      assertEquals(s"${run.taskName} outputPathFor", run.outputPath, session.outputPathFor(i))
    }
  }

  /** A record written before records carried an enqueue time reopens with no
    * queue wait, which is what `TaskResult` documents for an unknown one. */
  @Test def aRecordWithNoEnqueueTimeReopensWithNoQueueWait(): Unit = {
    val jobDir = tmpDir.resolve("job")
    JobFiles.addTable(jobDir, "old", "SELECT 1 AS k", None, None, None)
    val outDir = jobDir.resolve("output")
    val started = Instant.parse("2025-06-01T00:00:01Z")
    TaskRunRecord.write(outDir.resolve("old"), TaskRunRecord(
      schemaVersion = TaskRunRecord.SchemaVersion,
      taskName = "old",
      status = TaskRunStatus.Succeeded,
      errorMessage = None,
      executionTime = ExecutionTime,
      startedAt = started,
      finishedAt = started.plusMillis(5),
      writtenAt = started.plusMillis(6),
      rowsProduced = 1L,
      format = "csv",
      outputFiles = Nil,
      validations = Nil,
      enqueuedAt = None))
    JobRunRecord.write(outDir.resolve("job.json"), JobRunRecord(
      schemaVersion = JobRunRecord.SchemaVersion,
      succeeded = true,
      errorMessage = None,
      executionTime = ExecutionTime,
      startedAt = started,
      finishedAt = started.plusMillis(7),
      tasks = Seq(JobTaskSummary("old", TaskRunStatus.Succeeded,
        Some(outDir.resolve("old").resolve(TaskRunRecord.FileName).toString), 1L, 0, None)),
      warnings = Nil))
    val session = new JobSession()
    session.setExecutionTime(ExecutionTime)
    session.openJobDir(jobDir)
    val back = hydrated(session, 0)
    assertEquals(started, back.startedAt)
    assertEquals(0L, back.queueWaitMillis)
  }
}
