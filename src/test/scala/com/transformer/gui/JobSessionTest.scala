package com.transformer.gui

import com.transformer.job.{DirectoryJobLoader, FailedValidation, JobFiles, JobResult, JobRunRecord, JobTaskSummary, TaskResult, TaskRunRecord, TaskRunStatus, TaskStatus}
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
    val result = run(jobDir)
    (result, reopen(jobDir))
  }

  private def run(jobDir: Path): JobResult =
    DirectoryJobLoader.load(jobDir, TemporalVariables(ExecutionTime)).run()

  private def reopen(jobDir: Path): JobSession = {
    val session = new JobSession()
    session.setExecutionTime(ExecutionTime)
    session.openJobDir(jobDir)
    assertEquals(RunState.Idle, session.runState)
    session
  }

  private def record(jobDir: Path, table: String): Path =
    jobDir.resolve("output").resolve(table).resolve(TaskRunRecord.FileName)

  private def manifest(jobDir: Path): Path = jobDir.resolve("output").resolve("job.json")

  /** `result` as a task rebuilt from its `job.json` entry shows it: its failed
    * validations without their sample rows, which only its record held. */
  private def fromEntry(result: TaskResult): TaskResult = result.status match {
    case TaskStatus.ValidationFailed(fs) =>
      result.copy(status = TaskStatus.ValidationFailed(fs.map(_.copy(sampleRowsCsv = ""))))
    case _ => result
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

  // ---- reopening a run without a task's record -------------------------------

  /** A task whose `_run.json` is gone reopens from its `job.json` entry, which
    * holds everything but its failed validations' sample rows: the same status,
    * the names and row counts of the validations it failed, its rows, its three
    * timestamps, and the output it wrote. A line in the run log names the
    * missing record. It reopened with a 0 ms duration, no output, and every
    * validation card PASSED beside a VALIDATION FAILED status. */
  @Test def aTaskWithoutItsRecordReopensFromItsJobJsonEntry(): Unit = {
    val jobDir = writeFourStatusJob()
    val result = run(jobDir)
    Files.delete(record(jobDir, "c_val"))
    val session = reopen(jobDir)
    assertEquals(fromEntry(result.tasks(2)), hydrated(session, 2))
    assertEquals(result.tasks(2).outputPath, session.outputPathFor(2))
    assertTrue(session.summaryReasonFor(2).exists(_.contains("missing")))
    assertEquals(1, session.hydrationWarnings.size)
    assertTrue(session.hydrationWarnings.head, session.hydrationWarnings.head.contains("task 'c_val'"))
    Seq(0, 1, 3).foreach(i => assertEquals(result.tasks(i), hydrated(session, i)))
  }

  /** A record that names another task is not trusted: two swapped records each
    * reopened as the other task. Each task now reopens from its own `job.json`
    * entry, and the run log says which record named which task. */
  @Test def aRecordThatNamesAnotherTaskIsNotTrusted(): Unit = {
    val jobDir = writeFourStatusJob()
    val result = run(jobDir)
    val parked = record(jobDir, "a_ok").resolveSibling("parked")
    Files.move(record(jobDir, "a_ok"), parked)
    Files.move(record(jobDir, "b_fail"), record(jobDir, "a_ok"))
    Files.move(parked, record(jobDir, "b_fail"))
    val session = reopen(jobDir)
    Seq(0, 1).foreach { i =>
      assertEquals(fromEntry(result.tasks(i)), hydrated(session, i))
      assertTrue(session.summaryReasonFor(i).toString, session.summaryReasonFor(i).exists(_.contains("names task")))
    }
    assertEquals(2, session.hydrationWarnings.size)
    // Neither swapped record is that task's run, so neither picker lists it.
    assertEquals(Nil, session.historicalRunsFor(0))
    assertEquals(Nil, session.historicalRunsFor(1))
  }

  /** A `runFile` that resolves nowhere falls back to the task's own record of
    * the same run, found under its output path, and reopens it exactly. */
  @Test def aRunFileThatResolvesNowhereFallsBackToTheTasksOwnRecord(): Unit = {
    val jobDir = writeFourStatusJob()
    val result = run(jobDir)
    val rec = JobRunRecord.read(manifest(jobDir)).get
    val nowhere = tmpDir.resolve("nowhere").resolve(TaskRunRecord.FileName).toString
    JobRunRecord.write(manifest(jobDir), rec.copy(tasks = rec.tasks.updated(2, rec.tasks(2).copy(runFile = Some(nowhere)))))
    val session = reopen(jobDir)
    assertEquals(result.tasks(2), hydrated(session, 2))
    assertEquals(None, session.summaryReasonFor(2))
    assertEquals(Vector.empty, session.hydrationWarnings)
  }

  /** With no `job.json`, each task reopens from its own record exactly, and the
    * run log says the run's verdict and warnings are unknown. */
  @Test def aMissingJobJsonIsReportedAndEachTaskReopensFromItsRecord(): Unit = {
    val jobDir = writeFourStatusJob()
    val result = run(jobDir)
    Files.delete(manifest(jobDir))
    val session = reopen(jobDir)
    assertEquals(None, session.jobRecord)
    result.tasks.indices.foreach(i => assertEquals(result.tasks(i), hydrated(session, i)))
    assertEquals(1, session.hydrationWarnings.size)
    assertTrue(session.hydrationWarnings.head, session.hydrationWarnings.head.startsWith("no job.json at"))
  }

  /** A `job.json` written before entries carried timestamps and failed
    * validation names still reads, and a task rebuilt from it has those as
    * unknown: the epoch, and `ValidationFailed` naming none. */
  @Test def aJobJsonFromBeforeEntriesCarriedTimestampsStillReads(): Unit = {
    val jobDir = writeFourStatusJob()
    val result = run(jobDir)
    val rec = JobRunRecord.read(manifest(jobDir)).get
    assertEquals(Seq(FailedValidation("always", 2L)), rec.tasks(2).failedValidations)
    assertEquals(result.tasks.map(t => Some(t.startedAt)), rec.tasks.map(_.startedAt))
    // The entries as the writer wrote them before they carried these fields.
    val older = Files.readString(manifest(jobDir))
      .replaceAll(", \"(startedAt|finishedAt|enqueuedAt)\": \"[^\"]*\"", "")
      .replaceAll(", \"failedValidations\": \\[[^\\]]*\\]", "")
    assertFalse(older, older.contains("failedValidations") || older.contains("enqueuedAt"))
    Files.writeString(manifest(jobDir), older)
    val olderRec = JobRunRecord.read(manifest(jobDir)).get
    assertEquals(rec.tasks.map(_.copy(startedAt = None, finishedAt = None, enqueuedAt = None, failedValidations = Nil)),
      olderRec.tasks)
    Files.delete(record(jobDir, "c_val"))
    val back = hydrated(reopen(jobDir), 2)
    assertEquals(TaskStatus.ValidationFailed(Nil), back.status)
    assertEquals(Instant.EPOCH, back.startedAt)
    assertEquals(0L, back.queueWaitMillis)
  }
}
