package com.transformer.fuzz.oracle

import com.transformer.fuzz.JobDagGen.{Corruption, JobCase}
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
  *      execution time's own that hold a readable `job.json`, newest first; the
  *      session shows the picked one, else its own, else the newest.
  *   2. '''Where each task comes from''' ([[Source]]). A task the shown run's
  *      `job.json` names is rebuilt from the record its `runFile` points at, or
  *      from the manifest's compact entry when there is no readable record
  *      there. A task it does not name falls back to the newest readable record
  *      under the task's templated path, and a task with neither is unknown.
  *   3. '''What each source carries.''' A task rebuilt from a record is the
  *      `TaskResult` the run that wrote the record returned, field for field:
  *      status with its reason or its failed validations and their sample rows,
  *      rows, the three timestamps, and an output path only where the run wrote
  *      output. A task rebuilt from the entry carries the entry's fields; the
  *      ones it cannot carry are recorded as [[Degraded]]. An unknown task is
  *      `Pending`.
  *   4. '''History.''' Each task's partition picker lists every directory its
  *      path rendered to across the runs that holds a readable record, newest
  *      first, each read as the record its writer wrote.
  *   5. '''The job.''' The session's `job.json` is the shown run's: its verdict,
  *      warnings, execution time, and one entry per task with that task's
  *      status, reason, rows and `runFile`.
  *
  * Then the case's [[Corruption]] is applied to the latest run's files and the
  * sessions are opened again over the damaged tree. The same rules decide what
  * they must show, from a model of the damage ([[corrupted]]): a degraded but
  * sane session — nothing thrown, the job loaded, every task accounted for, and
  * each task either what its source carries or a [[Degraded]] entry. A task that
  * shows another task's record is one ([[Degraded.RecordOfAnotherTaskTrusted]]).
  */
object Hydration {

  // ---- degraded hydration --------------------------------------------------

  /** What a session shows today for a task rebuilt from less than its own
    * record: job.json's compact entry alone, or a record that is another
    * task's. Recorded, not fixed — what a reopened run should show then is a
    * product decision, open in [[Plan]]. Each entry has a repro (`reproduces`),
    * and `DagSchedulerFuzzTest` fails once one stops reproducing, so the record
    * cannot outlive the decision. */
  sealed abstract class Degraded(val name: String, val shows: String)
  object Degraded {
    case object SummaryLosesValidationDetail extends Degraded(
      "a ValidationFailed task rebuilt from the summary names no failed validation",
      "ValidationFailed(Nil): every validation card reads PASSED beside a VALIDATION FAILED status")
    case object SummaryLosesTimestamps extends Degraded(
      "a task rebuilt from the summary has every timestamp at the epoch",
      "startedAt = finishedAt = enqueuedAt = 1970-01-01: its duration reads 0 ms")
    case object SummaryLosesOutputPath extends Degraded(
      "a task rebuilt from the summary shows no output",
      "no output path for the details panel (\"Will write to\" for a task that wrote output), and " +
        "a TaskResult whose outputPath is its runFile's directory whatever its status")
    case object RecordOfAnotherTaskTrusted extends Degraded(
      "a task whose runFile holds another task's record is rebuilt as that task",
      "the other task's name, status, reason, rows and timestamps, and that record in its partition picker")

    val All: Vector[Degraded] =
      Vector(SummaryLosesValidationDetail, SummaryLosesTimestamps, SummaryLosesOutputPath, RecordOfAnotherTaskTrusted)

    /** Whether a session still shows `d`, on a directory job of two tables
      * reopened after its run: `checked` fails its validation and `broken`
      * fails outright. For the summary entries `checked`'s `_run.json` is
      * deleted; for the last, the two records trade places. */
    def reproduces(d: Degraded): Boolean = {
      val root = Files.createTempDirectory("hydration-repro-")
      try {
        val jobDir = root.resolve("job")
        Files.createDirectories(jobDir.resolve("data"))
        Files.writeString(jobDir.resolve("data").resolve("x.csv"), "k,v\n1,10\n")
        JobFiles.addInput(jobDir, "x", "data/x.csv", None, cache = true, Map.empty)
        JobFiles.addTable(jobDir, "checked", "SELECT k, v FROM x", None, None, None)
        JobFiles.addValidation(jobDir, "checked", "always", "SELECT k FROM checked")
        JobFiles.addTable(jobDir, "broken", "SELECT k, nope AS v FROM x", None, None, None)
        val time = Instant.parse("2026-01-01T05:30:21Z")
        val result = DirectoryJobLoader.load(jobDir, TemporalVariables(time)).run()
        val out = jobDir.resolve("output")
        def record(table: String): Path = out.resolve(table).resolve(TaskRunRecord.FileName)
        d match {
          case RecordOfAnotherTaskTrusted =>
            val parked = record("checked").resolveSibling("parked")
            Files.move(record("checked"), parked)
            Files.move(record("broken"), record("checked"))
            Files.move(parked, record("broken"))
          case _ => Files.delete(record("checked"))
        }
        val s = new JobSession()
        s.setExecutionTime(time)
        s.openJobDir(jobDir)
        val checked = s.dag.get.nodes.indexWhere(_.task.displayName == "checked")
        s.taskStates(checked) match {
          case UiTaskState.Done(r) => d match {
            case SummaryLosesValidationDetail => r.status == TaskStatus.ValidationFailed(Nil)
            case SummaryLosesTimestamps =>
              r.startedAt == Instant.EPOCH && r.finishedAt == Instant.EPOCH && r.enqueuedAt == Instant.EPOCH
            case SummaryLosesOutputPath =>
              s.outputPathFor(checked).isEmpty && result.tasks.find(_.taskName == "checked").exists(_.outputPath.isDefined)
            case RecordOfAnotherTaskTrusted => r.taskName == "broken"
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
    * directory holding a readable task record and which run's task wrote it,
    * every run directory holding a readable `job.json` and which run wrote it,
    * each `runFile` a corruption repointed (by run directory and task), for
    * each task every directory its path rendered to, and whose directory each
    * one is — the run's task that wrote its validation samples, which stay put
    * when a record is moved. */
  final case class Disk(
      records: Map[Path, Writer],
      manifests: Map[Path, Int],
      dangling: Map[(Path, Int), String],
      taskDirs: Vector[Vector[Path]],
      owners: Map[Path, Writer])

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
    Disk(records.toMap, manifests.toMap, Map.empty, taskDirs, records.toMap)
  }

  /** `d` after `jc.corrupt` damaged the latest run's files: a record deleted,
    * cut short or followed by garbage is no longer readable, a deleted
    * `job.json` no longer lists its run, a repointed `runFile` names a path
    * nothing wrote, and swapped records trade writers. */
  def corrupted(jc: JobCase, runs: Vector[Run], root: Path, d: Disk): Disk =
    if (!jc.corruptionApplies) d
    else {
      val time = runs.last.executionTime
      def dir(t: Int): Path = jc.recordFile(root, t, time).getParent
      val runDir = jc.manifestFile(root, time).getParent
      jc.corruption.get match {
        case Corruption.DeleteRecord(t) => d.copy(records = d.records - dir(t))
        case Corruption.TruncateRecord(t, _) => d.copy(records = d.records - dir(t))
        case Corruption.GarbageAfterRecord(t, _) => d.copy(records = d.records - dir(t))
        case Corruption.DeleteManifest => d.copy(manifests = d.manifests - runDir)
        case Corruption.DanglingRunFile(t) => d.copy(dangling = d.dangling + ((runDir, t) -> jc.danglingRunFile(root, t)))
        case Corruption.SwapRecords(a, b) =>
          d.copy(records = d.records.updated(dir(a), d.records(dir(b))).updated(dir(b), d.records(dir(a))))
      }
    }

  // ---- what a session must show ---------------------------------------------

  /** Where a session finds what it shows for one task. */
  sealed trait Source
  /** The record in `dir`, which `writer` wrote: through the shown run's
    * `job.json`, or found on disk when that names no such task. */
  final case class FromRecord(dir: Path, writer: Writer, viaManifest: Boolean) extends Source
  /** The shown run's `job.json` entry `task`, with no readable record behind it;
    * `runFile` is the pointer the entry holds. */
  final case class FromSummary(run: Int, task: Int, runFile: Option[String]) extends Source
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
    val ownDir = jc.manifestFile(root, time).getParent
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
          runFile(jc, root, d, runs, dir, k) match {
            case Some(rf) =>
              val recDir = Paths.get(rf).getParent
              d.records.get(recDir).map(w => FromRecord(recDir, w, viaManifest = true): Source)
                .getOrElse(FromSummary(r, k, Some(rf)))
            case None => FromSummary(r, k, None)
          }
        }
      }
      named
        .orElse(history(i).headOption.map { case (dir, w) => FromRecord(dir, w, viaManifest = false) })
        .getOrElse(Unknown)
    }.toVector
    View(available, shown, sources, history)
  }

  /** The `runFile` the `job.json` in `runDir` holds for task `k`: the record of
    * the run that wrote it, in the directory the task's path rendered to, if the
    * task has an output directory — unless a corruption repointed it. */
  private def runFile(jc: JobCase, root: Path, d: Disk, runs: Vector[Run], runDir: Path, k: Int): Option[String] =
    d.dangling.get((runDir, k)).orElse {
      val time = runs(d.manifests(runDir)).executionTime
      jc.outputPath(root, jc.tasks(k)).map(_ => jc.recordFile(root, k, time).toString)
    }

  // ---- the check ------------------------------------------------------------

  /** What the checks saw, for the coverage guards. */
  final class Report {
    /** Tasks compared, by where the session found them and the status of the
      * run that wrote what it showed. */
    val sources: mutable.Map[(String, String), Int] = mutable.Map.empty.withDefaultValue(0)
    /** Degraded fields seen, by entry. */
    val degraded: mutable.Map[Degraded, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Sessions compared, by how they were opened. */
    val openings: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Corrupted trees reopened, by corruption. */
    val corruptions: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Tasks whose partition picker listed two or more runs. */
    var multiPartitionTasks: Int = 0
    /** Sessions whose run picker listed two or more runs. */
    var multiRunSessions: Int = 0

    def add(other: Report): Unit = {
      other.sources.foreach { case (k, v) => sources(k) += v }
      other.degraded.foreach { case (k, v) => degraded(k) += v }
      other.openings.foreach { case (k, v) => openings(k) += v }
      other.corruptions.foreach { case (k, v) => corruptions(k) += v }
      multiPartitionTasks += other.multiPartitionTasks
      multiRunSessions += other.multiRunSessions
    }
  }

  /** Opens `jc`'s finished `runs` (written under `root`) in sessions and asserts
    * each shows what the runs returned; then applies the case's corruption and
    * asserts the sessions over the damaged tree are degraded but sane. */
  def check(jc: JobCase, runs: Vector[Run], root: Path): Report = {
    val report = new Report
    val jobDir = jc.writeDirectoryJob(root)
    val intact = disk(jc, runs, root)
    reopen(jc, runs, root, jobDir, intact, "", report)
    if (jc.corruptionApplies) {
      jc.corrupt(root, runs.last.executionTime)
      reopen(jc, runs, root, jobDir, corrupted(jc, runs, root, intact), s" after ${jc.corruption.get}", report)
      report.corruptions(jc.corruption.get.name) += 1
    }
    report
  }

  /** The three ways a session opens the job, each compared with what `d`
    * implies. */
  private def reopen(
      jc: JobCase, runs: Vector[Run], root: Path, jobDir: Path, d: Disk, damage: String, report: Report): Unit = {
    val latest = runs.last.executionTime
    val now = open(jc, root, jobDir, latest)
    val first = view(jc, runs, root, d, latest, None)
    compare(jc, runs, root, d, now, first, s"at the latest run's execution time$damage", report)
    report.openings("latest") += 1
    first.available.filterNot(first.shown.contains).foreach { dir =>
      now.selectRun(dir)
      compare(jc, runs, root, d, now, view(jc, runs, root, d, latest, Some(dir)), s"picking run $dir$damage", report)
      report.openings("picked") += 1
    }
    runs.map(_.executionTime).distinct.filterNot(_ == latest).foreach { time =>
      val earlier = open(jc, root, jobDir, time)
      compare(jc, runs, root, d, earlier, view(jc, runs, root, d, time, None), s"at execution time $time$damage", report)
      report.openings("earlier") += 1
    }
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
        checkJobRecord(jc, runs, root, d, dir, s, ctx)
    }

    jc.tasks.indices.foreach { i =>
      checkHistory(runs, s, v, i, ctx, report)
      v.sources(i) match {
        case FromRecord(dir, w, viaManifest) =>
          checkFromRecord(runs, s, i, dir, w, d.owners(dir), ctx, report)
          report.sources((if (viaManifest) "record" else "discovered", kind(runs(w.run).result.tasks(w.task).status))) += 1
        case FromSummary(r, k, rf) =>
          checkFromSummary(runs, s, i, r, k, rf, ctx, report)
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
  private def checkJobRecord(
      jc: JobCase, runs: Vector[Run], root: Path, d: Disk, runDir: Path, s: JobSession, ctx: String): Unit = {
    val rec = s.jobRecord.getOrElse(throw new AssertionError(s"[hydration] the session read no job.json$ctx"))
    val run = runs(d.manifests(runDir))
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
        runFile = runFile(jc, root, d, runs, runDir, k),
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

  /** A task's partition picker: every directory its path rendered to that holds
    * a readable record, newest first, each read as the record its writer wrote. */
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
      if (w.task != i) report.degraded(Degraded.RecordOfAnotherTaskTrusted) += 1
    }
    if (want.length >= 2) report.multiPartitionTasks += 1
  }

  /** A task rebuilt from a record is the result the record's run returned:
    * every field, with an output path — the record's directory — only where
    * that run wrote output. A record another task wrote is one of the
    * [[Degraded]] entries; the session must still read it faithfully, and reads
    * each failed validation's sample rows from the directory it found the record
    * in, where only the directory's own task left samples. */
  private def checkFromRecord(
      runs: Vector[Run], s: JobSession, i: Int, dir: Path, w: Writer, owner: Writer,
      ctx: String, report: Report): Unit = {
    val written = runs(w.run).result.tasks(w.task)
    val status = (written.status, runs(owner.run).result.tasks(owner.task).status) match {
      case (TaskStatus.ValidationFailed(fs), ownStatus) if w != owner =>
        val own = ownStatus match {
          case TaskStatus.ValidationFailed(os) => os
          case _ => Nil
        }
        TaskStatus.ValidationFailed(fs.map(f =>
          f.copy(sampleRowsCsv = own.find(_.validationName == f.validationName).map(_.sampleRowsCsv).getOrElse(""))))
      case (st, _) => st
    }
    val expected = written.copy(
      status = status,
      outputPath = if (wroteOutput(written.status)) Some(dir.toString) else None)
    val got = done(s, i, ctx)
    assert(got == expected,
      s"[hydration] task t$i, rebuilt from the record in $dir:\n    got      $got\n    run said $expected$ctx")
    assert(s.outputPathFor(i) == expected.outputPath,
      s"[hydration] task t$i shows output ${s.outputPathFor(i)}, the run reported ${expected.outputPath}$ctx")
    val rec = s.taskRecordFor(i).getOrElse(throw new AssertionError(
      s"[hydration] task t$i was rebuilt from the record in $dir but the session holds no record$ctx"))
    assert(rec.taskName == written.taskName && rec.status == runStatus(written.status) &&
      rec.rowsProduced == written.rowsProduced && rec.enqueuedAt.contains(written.enqueuedAt),
      s"[hydration] task t$i's record reads back as $rec, the run said $written$ctx")
    if (w.task != i) report.degraded(Degraded.RecordOfAnotherTaskTrusted) += 1
  }

  /** A task rebuilt from the summary carries the summary's fields: its name,
    * status and reason, and rows. The fields the summary does not carry are
    * [[Degraded]], counted in `report`. */
  private def checkFromSummary(
      runs: Vector[Run], s: JobSession, i: Int, r: Int, k: Int, runFile: Option[String],
      ctx: String, report: Report): Unit = {
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
    if (got.outputPath != run.outputPath || s.outputPathFor(i) != run.outputPath) {
      val runFileDir = runFile.map(rf => Paths.get(rf).getParent.toString)
      assert(got.outputPath == runFileDir && s.outputPathFor(i).isEmpty,
        mismatch(s"output path (the details panel shows ${s.outputPathFor(i)})"))
      report.degraded(Degraded.SummaryLosesOutputPath) += 1
    }
    assert(s.taskRecordFor(i).isEmpty,
      s"[hydration] task t$i was rebuilt from job.json's entry yet holds record ${s.taskRecordFor(i)}$ctx")
  }

  private def done(s: JobSession, i: Int, ctx: String): TaskResult = s.taskStates(i) match {
    case UiTaskState.Done(r) => r
    case other => throw new AssertionError(s"[hydration] task t$i reopened as $other, not Done$ctx")
  }

  /** The statuses after which the runner reports an output path: the task wrote
    * its output, whatever its validations then said. */
  private def wroteOutput(s: TaskStatus): Boolean = s match {
    case TaskStatus.Succeeded | TaskStatus.ValidationFailed(_) => true
    case _ => false
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
