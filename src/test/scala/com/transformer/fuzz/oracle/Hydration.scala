package com.transformer.fuzz.oracle

import com.transformer.fuzz.JobDagGen.JobCase
import com.transformer.fuzz.oracle.DagSchedule.Run
import com.transformer.gui.{JobSession, RunState, UiTaskState}
import com.transformer.job.{DirectoryJobLoader, JobFiles, JobTaskSummary, TaskResult, TaskRunRecord, TaskRunStatus, TaskStatus}
import com.transformer.temporal.TemporalVariables

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import scala.collection.mutable

/** Hydration oracle: open a generated job's finished runs the way the GUI does —
  * a [[JobSession]] on the job's directory — and hold what the session rebuilds
  * to what the runs returned.
  *
  * [[DagSchedule]] checks the write side: each record is on disk and `job.json`
  * points at it. This checks the read side, which is where a defect reaches the
  * user: a session that ignores a pointer, or reads a record into the wrong
  * field, shows a run that never happened while every file is correct. So the
  * comparison is against the `JobResult` each run returned — never against a
  * second reading of the files, which would only hold the reader to itself.
  *
  * The session opens `JobCase.writeDirectoryJob`, the same job as a directory,
  * with the case's output directory as its own, at an execution time:
  *
  *   - at the latest run's, as the GUI opens a job now;
  *   - then, through the run picker (`selectRun`), at every other run on disk;
  *   - and in a fresh session at each earlier run's execution time.
  *
  * What it must show is decided from the case and the runs alone ([[view]]):
  *
  *   1. '''Which run.''' The runs on disk are the run directories beside the
  *      execution time's own that hold a `job.json`, newest first; the session
  *      shows the picked one, else its own, else the newest.
  *   2. '''Where each task comes from''' ([[Source]]). A task the shown run's
  *      `job.json` names is rebuilt from the record its `runFile` points at, or
  *      from the manifest's compact entry when there is no record behind it — a
  *      memory-only task. A task it does not name falls back to the newest
  *      record on disk under the task's templated path.
  *   3. '''What each source carries.''' A task rebuilt from a record is the
  *      `TaskResult` the run that wrote the record returned, field for field:
  *      status with its reason or its failed validations and their sample rows,
  *      rows, the three timestamps, and an output path only where the run wrote
  *      output. A task rebuilt from the summary carries the summary's fields;
  *      the ones it cannot carry are recorded as [[Degraded]].
  *   4. '''History.''' Each task's partition picker lists every directory its
  *      path rendered to across the runs, newest first, each holding the record
  *      of the run that wrote it last.
  *   5. '''The job.''' The session's `job.json` is the shown run's: its verdict,
  *      warnings, execution time, and one entry per task with that task's
  *      status, reason, rows and `runFile`.
  */
object Hydration {

  // ---- degraded hydration --------------------------------------------------

  /** What a session shows today for a task rebuilt from job.json's compact entry
    * alone, with no record behind it: the fields the entry does not carry.
    * Recorded, not fixed — what a reopened run should show when a record is
    * missing is a product decision, open in [[Plan]]. Each entry has a repro
    * (`reproduces`), and `DagSchedulerFuzzTest` fails once one stops
    * reproducing, so the record cannot outlive the decision. */
  sealed abstract class Degraded(val name: String, val shows: String)
  object Degraded {
    case object SummaryLosesValidationDetail extends Degraded(
      "a ValidationFailed task rebuilt from the summary names no failed validation",
      "ValidationFailed(Nil): every validation card reads PASSED beside a VALIDATION FAILED status")
    case object SummaryLosesTimestamps extends Degraded(
      "a task rebuilt from the summary has every timestamp at the epoch",
      "startedAt = finishedAt = enqueuedAt = 1970-01-01: its duration reads 0 ms")

    val All: Vector[Degraded] = Vector(SummaryLosesValidationDetail, SummaryLosesTimestamps)

    /** Whether a session still shows `d`: a directory job whose task fails its
      * validation, run, its `_run.json` deleted, reopened. */
    def reproduces(d: Degraded): Boolean = {
      val root = Files.createTempDirectory("hydration-repro-")
      try {
        val jobDir = root.resolve("job")
        Files.createDirectories(jobDir.resolve("data"))
        Files.writeString(jobDir.resolve("data").resolve("x.csv"), "k,v\n1,10\n")
        JobFiles.addInput(jobDir, "x", "data/x.csv", None, cache = true, Map.empty)
        JobFiles.addTable(jobDir, "checked", "SELECT k, v FROM x", None, None, None)
        JobFiles.addValidation(jobDir, "checked", "always", "SELECT k FROM checked")
        val time = Instant.parse("2026-01-01T05:30:21Z")
        DirectoryJobLoader.load(jobDir, TemporalVariables(time)).run()
        Files.delete(jobDir.resolve("output").resolve("checked").resolve(TaskRunRecord.FileName))
        val s = new JobSession()
        s.setExecutionTime(time)
        s.openJobDir(jobDir)
        s.taskStates.headOption match {
          case Some(UiTaskState.Done(r)) => d match {
            case SummaryLosesValidationDetail => r.status == TaskStatus.ValidationFailed(Nil)
            case SummaryLosesTimestamps =>
              r.startedAt == Instant.EPOCH && r.finishedAt == Instant.EPOCH && r.enqueuedAt == Instant.EPOCH
          }
          case _ => false
        }
      } finally deleteRecursively(root)
    }
  }

  /** The plan that holds the decision [[Degraded]] waits on. */
  val Plan: String = "plans/robustness/15-what-a-reopened-run-shows-without-its-record.md"

  // ---- what the runs left on disk -------------------------------------------

  /** The run, and the task in it, that wrote a file last. */
  final case class Writer(run: Int, task: Int)

  /** What the runs left on disk, decided from the case and the runs: every
    * directory holding a task record and which run's task wrote it last, every
    * run directory holding a `job.json` and which run wrote it last, and for
    * each task every directory its path rendered to. */
  final case class Disk(
      records: Map[Path, Writer],
      manifests: Map[Path, Int],
      taskDirs: Vector[Vector[Path]])

  def disk(jc: JobCase, runs: Vector[Run], root: Path): Disk = {
    val records = mutable.LinkedHashMap.empty[Path, Writer]
    val manifests = mutable.LinkedHashMap.empty[Path, Int]
    runs.indices.foreach { r =>
      val time = runs(r).executionTime
      jc.tasks.foreach { t =>
        jc.outputPath(root, t).foreach(p => records(Paths.get(jc.render(p, time))) = Writer(r, t.index))
      }
      manifests(Paths.get(jc.render(jc.manifestPath(root), time)).getParent) = r
    }
    val taskDirs = jc.tasks.map { t =>
      jc.outputPath(root, t) match {
        case Some(p) => runs.map(run => Paths.get(jc.render(p, run.executionTime))).distinct
        case None => Vector.empty
      }
    }
    Disk(records.toMap, manifests.toMap, taskDirs)
  }

  // ---- what a session must show ---------------------------------------------

  /** Where a session finds what it shows for one task. */
  sealed trait Source
  /** The record in `dir`, which `writer` wrote. */
  final case class FromRecord(dir: Path, writer: Writer, viaManifest: Boolean) extends Source
  /** The shown run's `job.json` entry `task`, with no record behind it. */
  final case class FromSummary(run: Int, task: Int) extends Source
  /** Nothing on disk the session can read for the task. */
  case object Unknown extends Source

  /** What a session at `time`, with `picked` chosen in the run picker, must
    * show: the runs on disk newest first, the one it shows, each task's source,
    * and each task's partitions newest first. */
  final case class View(
      available: Vector[Path],
      shown: Option[Path],
      sources: Vector[Source],
      history: Vector[Vector[(Path, Writer)]])

  def view(jc: JobCase, runs: Vector[Run], root: Path, d: Disk, time: Instant, picked: Option[Path]): View = {
    val ownDir = Paths.get(jc.render(jc.manifestPath(root), time)).getParent
    val available = d.manifests.toVector.filter(_._1.getParent == ownDir.getParent).sortBy(-_._2).map(_._1)
    val shown = picked.filter(available.contains)
      .orElse(Some(ownDir).filter(available.contains))
      .orElse(available.headOption)
    val history = jc.tasks.indices.map { i =>
      d.taskDirs(i).filter(d.records.contains).map(dir => dir -> d.records(dir)).sortBy(-_._2.run)
    }.toVector
    val sources = jc.tasks.indices.map { i =>
      val named: Option[Source] = shown.flatMap { dir =>
        val r = d.manifests(dir)
        val entries = runs(r).result.tasks
        entries.indices.find(k => entries(k).taskName == jc.tasks(i).view).map { k =>
          runFile(jc, root, runs(r).executionTime, k) match {
            case Some(rf) =>
              val recDir = Paths.get(rf).getParent
              d.records.get(recDir).map(w => FromRecord(recDir, w, viaManifest = true): Source)
                .getOrElse(FromSummary(r, k))
            case None => FromSummary(r, k)
          }
        }
      }
      named
        .orElse(history(i).headOption.map { case (dir, w) => FromRecord(dir, w, viaManifest = false) })
        .getOrElse(Unknown)
    }.toVector
    View(available, shown, sources, history)
  }

  /** The `runFile` the run at `time` writes for task `k`: its record, in the
    * directory its path renders to, if it has an output directory. */
  private def runFile(jc: JobCase, root: Path, time: Instant, k: Int): Option[String] =
    jc.outputPath(root, jc.tasks(k)).map(p => Paths.get(jc.render(p, time)).resolve(TaskRunRecord.FileName).toString)

  // ---- the check ------------------------------------------------------------

  /** What the checks saw, for the coverage guards. */
  final class Report {
    /** Tasks compared, by where the session found them and the run's status. */
    val sources: mutable.Map[(String, String), Int] = mutable.Map.empty.withDefaultValue(0)
    /** Degraded fields seen, by entry. */
    val degraded: mutable.Map[Degraded, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Sessions compared, by how they were opened. */
    val openings: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Tasks whose partition picker listed two or more runs. */
    var multiPartitionTasks: Int = 0
    /** Sessions whose run picker listed two or more runs. */
    var multiRunSessions: Int = 0

    def add(other: Report): Unit = {
      other.sources.foreach { case (k, v) => sources(k) += v }
      other.degraded.foreach { case (k, v) => degraded(k) += v }
      other.openings.foreach { case (k, v) => openings(k) += v }
      multiPartitionTasks += other.multiPartitionTasks
      multiRunSessions += other.multiRunSessions
    }
  }

  /** Opens `jc`'s finished `runs` (written under `root`) in sessions and asserts
    * each shows what the runs returned. */
  def check(jc: JobCase, runs: Vector[Run], root: Path): Report = {
    val report = new Report
    val jobDir = jc.writeDirectoryJob(root)
    val d = disk(jc, runs, root)
    val latest = runs.last.executionTime

    val now = open(jc, root, jobDir, latest)
    val first = view(jc, runs, root, d, latest, None)
    compare(jc, runs, root, d, now, first, "at the latest run's execution time", report)
    report.openings("latest") += 1
    first.available.filterNot(first.shown.contains).foreach { dir =>
      now.selectRun(dir)
      compare(jc, runs, root, d, now, view(jc, runs, root, d, latest, Some(dir)), s"picking run $dir", report)
      report.openings("picked") += 1
    }
    runs.map(_.executionTime).distinct.filterNot(_ == latest).foreach { time =>
      val earlier = open(jc, root, jobDir, time)
      compare(jc, runs, root, d, earlier, view(jc, runs, root, d, time, None), s"at execution time $time", report)
      report.openings("earlier") += 1
    }
    report
  }

  /** A session on `jobDir`, with the case's output directory and `time` set
    * before the job is opened, as the controls panel sets them. */
  private def open(jc: JobCase, root: Path, jobDir: Path, time: Instant): JobSession = {
    val s = new JobSession()
    s.setOutputDirOverride(Some(jc.outputDir(root)))
    s.setExecutionTime(time)
    s.openJobDir(jobDir)
    s
  }

  private def compare(
      jc: JobCase, runs: Vector[Run], root: Path, d: Disk,
      s: JobSession, v: View, how: String, report: Report): Unit = {
    def ctx: String = s"\n  opened $how\n  case = $jc"
    assert(s.runState == RunState.Idle, s"[hydration] the session did not load the job: ${s.runState}$ctx")
    val names = s.dag.map(_.nodes.map(_.task.displayName).toVector).getOrElse(Vector.empty)
    assert(names == jc.tasks.map(_.view), s"[hydration] the session's DAG is $names$ctx")
    assert(s.taskStates.length == jc.tasks.length,
      s"[hydration] ${s.taskStates.length} task states for ${jc.tasks.length} tasks$ctx")

    val availableGot = s.availableRuns.map(_._1)
    assert(availableGot == v.available,
      s"[hydration] run picker lists $availableGot, expected ${v.available} (newest first)$ctx")
    if (v.available.length >= 2) report.multiRunSessions += 1
    v.shown match {
      case None =>
        assert(s.jobRecord.isEmpty, s"[hydration] no job.json is readable, yet the session shows one$ctx")
      case Some(dir) =>
        checkJobRecord(jc, runs, root, d.manifests(dir), s, ctx)
    }

    jc.tasks.indices.foreach { i =>
      checkHistory(runs, s, v, i, ctx, report)
      v.sources(i) match {
        case FromRecord(dir, w, viaManifest) =>
          checkFromRecord(runs, s, i, dir, w, ctx)
          report.sources((if (viaManifest) "record" else "discovered", kind(runs(w.run).result.tasks(w.task).status))) += 1
        case FromSummary(r, k) =>
          checkFromSummary(runs, s, i, r, k, ctx, report)
          report.sources(("summary", kind(runs(r).result.tasks(k).status))) += 1
        case Unknown =>
          assert(s.taskStates(i) == UiTaskState.Pending,
            s"[hydration] task ${jc.tasks(i).view}: nothing on disk names it, yet the session shows " +
              s"${s.taskStates(i)}$ctx")
          report.sources(("unknown", "Pending")) += 1
      }
    }
  }

  /** The shown run's `job.json`, as the session read it. */
  private def checkJobRecord(jc: JobCase, runs: Vector[Run], root: Path, r: Int, s: JobSession, ctx: String): Unit = {
    val rec = s.jobRecord.getOrElse(throw new AssertionError(s"[hydration] the session read no job.json$ctx"))
    val run = runs(r)
    val res = run.result
    assert(rec.succeeded == res.succeeded, s"[hydration] job.json says succeeded=${rec.succeeded}, run said ${res.succeeded}$ctx")
    assert(rec.errorMessage == res.error, s"[hydration] job.json error ${rec.errorMessage}, run said ${res.error}$ctx")
    assert(rec.warnings == res.warnings, s"[hydration] job.json warnings ${rec.warnings}, run said ${res.warnings}$ctx")
    assert(rec.executionTime == run.executionTime,
      s"[hydration] job.json execution time ${rec.executionTime}, run ran at ${run.executionTime}$ctx")
    val expected = res.tasks.indices.map { k =>
      val t = res.tasks(k)
      JobTaskSummary(
        taskName = t.taskName,
        status = runStatus(t.status),
        runFile = runFile(jc, root, run.executionTime, k),
        rowsProduced = t.rowsProduced,
        failedValidationCount = t.status match {
          case TaskStatus.ValidationFailed(fs) => fs.size
          case _ => 0
        },
        errorMessage = reason(t.status))
    }.toVector
    rec.tasks.toVector.zipAll(expected, null, null).zipWithIndex.foreach { case ((got, want), k) =>
      assert(got == want, s"[hydration] job.json entry $k reads back as $got, expected $want$ctx")
    }
  }

  /** A task's partition picker: every directory its path rendered to, newest
    * first, each read as the record its writer wrote. */
  private def checkHistory(runs: Vector[Run], s: JobSession, v: View, i: Int, ctx: String, report: Report): Unit = {
    val got = s.historicalRunsFor(i)
    val want = v.history(i)
    assert(got.map(_._1) == want.map(_._1),
      s"[hydration] task t$i's partitions are ${got.map(_._1)}, expected ${want.map(_._1)} (newest first)$ctx")
    got.zip(want).foreach { case ((dir, rec), (_, w)) =>
      val run = runs(w.run).result.tasks(w.task)
      assert(rec.taskName == run.taskName && rec.status == runStatus(run.status),
        s"[hydration] task t$i's partition $dir reads as ${rec.taskName}/${rec.status}, written as " +
          s"${run.taskName}/${kind(run.status)}$ctx")
    }
    if (want.length >= 2) report.multiPartitionTasks += 1
  }

  /** A task rebuilt from a record is the result the record's run returned:
    * every field, with an output path only where that run wrote output. */
  private def checkFromRecord(runs: Vector[Run], s: JobSession, i: Int, dir: Path, w: Writer, ctx: String): Unit = {
    val run = runs(w.run).result.tasks(w.task)
    val got = done(s, i, ctx)
    assert(got == run,
      s"[hydration] task t$i, rebuilt from its record in $dir:\n    got      $got\n    run said $run$ctx")
    assert(s.outputPathFor(i) == run.outputPath,
      s"[hydration] task t$i shows output ${s.outputPathFor(i)}, the run reported ${run.outputPath}$ctx")
    val rec = s.taskRecordFor(i).getOrElse(throw new AssertionError(
      s"[hydration] task t$i was rebuilt from its record in $dir but the session holds no record$ctx"))
    assert(rec.taskName == run.taskName && rec.status == runStatus(run.status) &&
      rec.rowsProduced == run.rowsProduced && rec.enqueuedAt.contains(run.enqueuedAt),
      s"[hydration] task t$i's record reads back as $rec, the run said $run$ctx")
  }

  /** A task rebuilt from the summary carries the summary's fields: its name,
    * status and reason, and rows. The fields the summary does not carry are
    * [[Degraded]], counted in `report`. */
  private def checkFromSummary(
      runs: Vector[Run], s: JobSession, i: Int, r: Int, k: Int, ctx: String, report: Report): Unit = {
    val run = runs(r).result.tasks(k)
    val got = done(s, i, ctx)
    def mismatch(field: String): String =
      s"[hydration] task t$i, rebuilt from job.json's entry, has the wrong $field:\n    got      $got\n" +
        s"    run said $run$ctx"
    assert(got.taskName == run.taskName, mismatch("name"))
    assert(kind(got.status) == kind(run.status), mismatch("status"))
    assert(reason(got.status) == reason(run.status), mismatch("reason"))
    assert(got.rowsProduced == run.rowsProduced, mismatch("row count"))
    (run.status, got.status) match {
      case (TaskStatus.ValidationFailed(fs), TaskStatus.ValidationFailed(gs)) if gs != fs =>
        assert(gs.isEmpty, mismatch("validation failures"))
        report.degraded(Degraded.SummaryLosesValidationDetail) += 1
      case _ => ()
    }
    val stamps = (got.startedAt, got.finishedAt, got.enqueuedAt)
    if (stamps != ((run.startedAt, run.finishedAt, run.enqueuedAt))) {
      assert(stamps == ((Instant.EPOCH, Instant.EPOCH, Instant.EPOCH)), mismatch("timestamps"))
      report.degraded(Degraded.SummaryLosesTimestamps) += 1
    }
    assert(got.outputPath == run.outputPath, mismatch("output path"))
    assert(s.outputPathFor(i) == run.outputPath,
      s"[hydration] task t$i shows output ${s.outputPathFor(i)}, the run reported ${run.outputPath}$ctx")
    assert(s.taskRecordFor(i).isEmpty,
      s"[hydration] task t$i was rebuilt from job.json's entry yet holds record ${s.taskRecordFor(i)}$ctx")
  }

  private def done(s: JobSession, i: Int, ctx: String): TaskResult = s.taskStates(i) match {
    case UiTaskState.Done(r) => r
    case other => throw new AssertionError(s"[hydration] task t$i reopened as $other, not Done$ctx")
  }

  private def kind(s: TaskStatus): String = DagSchedule.kind(s)

  private def reason(s: TaskStatus): Option[String] = s match {
    case TaskStatus.Failed(m) => Some(m)
    case TaskStatus.Skipped(m) => Some(m)
    case _ => None
  }

  private def runStatus(s: TaskStatus): TaskRunStatus = s match {
    case TaskStatus.Succeeded => TaskRunStatus.Succeeded
    case _: TaskStatus.ValidationFailed => TaskRunStatus.ValidationFailed
    case _: TaskStatus.Failed => TaskRunStatus.Failed
    case _: TaskStatus.Skipped => TaskRunStatus.Skipped
    case TaskStatus.Pending => TaskRunStatus.Failed
  }

  private def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val st = Files.list(p)
      try { val it = st.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally st.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }

  private def assert(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new AssertionError(msg)
}
