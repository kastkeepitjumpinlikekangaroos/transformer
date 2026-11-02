package com.transformer.fuzz.oracle

import com.transformer.fuzz.JobDagGen
import com.transformer.fuzz.JobDagGen.{Corruption, JobCase}
import com.transformer.fuzz.oracle.DagSchedule.Run
import com.transformer.gui.{JobSession, RunState, UiTaskState}
import com.transformer.job.{FailedValidation, JobTaskSummary, TaskResult, TaskRunRecord, TaskRunStatus, TaskStatus}

import java.nio.file.{Path, Paths}
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
  *   2. '''Which records are a task's.''' Only a record that names the task:
  *      the task's partition picker lists every directory its path rendered to
  *      across the runs that holds a readable record naming it, newest first.
  *   3. '''Where each task comes from''' ([[Source]]). A task the shown run's
  *      `job.json` names is rebuilt from the record its `runFile` points at, if
  *      that record is readable and names it; else from its own record of the
  *      same run, if its picker lists one; else from the `job.json` entry. A
  *      task the manifest does not name falls back to its newest record, and a
  *      task with neither is unknown.
  *   4. '''What each source carries.''' A task rebuilt from a record is the
  *      `TaskResult` its run returned, field for field — status with its reason
  *      or its failed validations and their sample rows, rows, the three
  *      timestamps, and an output path only where the run wrote output. One
  *      rebuilt from the entry is the same but for its failed validations'
  *      sample rows, which only the record held, and its output path, which it
  *      keeps where that directory still exists; if a record was expected, the
  *      session says why it was not used (`JobSession.summaryReasonFor`). An
  *      unknown task is `Pending`.
  *   5. '''The job.''' The session's `job.json` is the shown run's: its verdict,
  *      warnings, execution time, and one entry per task with its status,
  *      reason, rows, `runFile`, timestamps and failed validations. Its
  *      warnings name each task rebuilt from its entry for want of its record,
  *      and the execution time's own run directory when it exists without a
  *      readable `job.json` and no other run was picked.
  *
  * Then the case's [[Corruption]] is applied to the latest run's files and the
  * sessions are opened again over the damaged tree; the same rules decide what
  * they must show, from a model of the damage ([[corrupted]]).
  *
  * A run a fault stopped is reopened before it reruns ([[checkUnfinished]]):
  * a task whose record says `Running` shows as unfinished, never as
  * succeeded. Its rerun leaves the tree as a clean run would, which the rules
  * above then decide.
  *
  * A directory job names a task after its directory, so a session cannot match
  * a task the case gave an adversarial name to: its records never name a task
  * the session opened. Those names are held through `job.json`, which the
  * session reads whole, and through `TaskRunRecord.read`, one of the entry
  * points hydration is built from ([[checkRecordsRead]]).
  */
object Hydration {

  // ---- what the runs left on disk -------------------------------------------

  /** The run, and the task in it, that wrote a file last. */
  final case class Writer(run: Int, task: Int)

  /** What the runs left on disk, decided from the case and the runs: every
    * directory holding a readable task record and which run's task wrote it,
    * the directories whose record is there but unreadable, every run directory
    * holding a readable `job.json` and which run wrote it, each `runFile` a
    * corruption repointed (by run directory and task), for each task every
    * directory its path rendered to, every run directory a run wrote into, and
    * whose directory each task directory is — the run's task that wrote its
    * validation samples, which stay put when a record is moved. */
  final case class Disk(
      records: Map[Path, Writer],
      unreadable: Set[Path],
      manifests: Map[Path, Int],
      dangling: Map[(Path, Int), String],
      taskDirs: Vector[Vector[Path]],
      runDirs: Set[Path],
      owners: Map[Path, Writer]) {

    /** Whether `dir` is a directory a task's path rendered to, so one on disk;
      * a `runFile` a corruption repointed names one that is not. */
    def holds(dir: Path): Boolean = taskDirs.exists(_.contains(dir))
  }

  def disk(jc: JobCase, runs: Vector[Run], root: Path): Disk = {
    val records = mutable.LinkedHashMap.empty[Path, Writer]
    val manifests = mutable.LinkedHashMap.empty[Path, Int]
    runs.indices.foreach { r =>
      val time = runs(r).executionTime
      jc.tasks.foreach { t =>
        jc.outputPath(root, t).foreach(p => records(Paths.get(jc.render(p, time))) = Writer(r, t.index))
      }
      manifests(jc.manifestFile(root, time).getParent) = r
    }
    val taskDirs = jc.tasks.map { t =>
      jc.outputPath(root, t) match {
        case Some(p) => runs.map(run => Paths.get(jc.render(p, run.executionTime))).distinct
        case None => Vector.empty
      }
    }
    Disk(records.toMap, Set.empty, manifests.toMap, Map.empty, taskDirs, manifests.keySet.toSet, records.toMap)
  }

  /** `d` after `jc.corrupt` damaged the latest run's files: a deleted record
    * is gone, one cut short or followed by garbage is there but unreadable, a
    * deleted `job.json` no longer lists its run, a repointed `runFile` names a
    * path nothing wrote, and swapped records trade writers. */
  def corrupted(jc: JobCase, runs: Vector[Run], root: Path, d: Disk): Disk =
    if (!jc.corruptionApplies) d
    else {
      val time = runs.last.executionTime
      def dir(t: Int): Path = jc.recordFile(root, t, time).getParent
      val runDir = jc.manifestFile(root, time).getParent
      jc.corruption.get match {
        case Corruption.DeleteRecord(t) => d.copy(records = d.records - dir(t))
        case Corruption.TruncateRecord(t, _) => d.copy(records = d.records - dir(t), unreadable = d.unreadable + dir(t))
        case Corruption.GarbageAfterRecord(t, _) => d.copy(records = d.records - dir(t), unreadable = d.unreadable + dir(t))
        case Corruption.DeleteManifest => d.copy(manifests = d.manifests - runDir)
        case Corruption.DanglingRunFile(t) => d.copy(dangling = d.dangling + ((runDir, t) -> jc.danglingRunFile(root, t)))
        case Corruption.SwapRecords(a, b) =>
          d.copy(records = d.records.updated(dir(a), d.records(dir(b))).updated(dir(b), d.records(dir(a))))
      }
    }

  // ---- what a session must show ---------------------------------------------

  /** Why a task the manifest names is not rebuilt from the record its
    * `runFile` points at; `word` is how the session's reason says it. */
  sealed abstract class Problem(val word: String)
  /** Nothing is there. */
  case object Missing extends Problem("missing")
  /** Something is there that does not read as a record. */
  case object Unreadable extends Problem("unreadable")
  /** The record there names another task. */
  case object AnotherTasks extends Problem("names task")

  /** Where a session finds what it shows for one task. */
  sealed trait Source
  /** The record in `dir`, which `writer` wrote, reached `how`: the record the
    * shown run's `job.json` points at, the task's own record of that run when
    * the pointer led nowhere usable, or its newest record when `job.json` does
    * not name it. */
  final case class FromRecord(dir: Path, writer: Writer, how: String) extends Source
  /** The shown run's `job.json` entry `task`; `problem` says what became of the
    * record its `runFile` points at, when it has one. */
  final case class FromSummary(run: Int, task: Int, runFile: Option[String], problem: Option[Problem]) extends Source
  /** Nothing on disk the session can read for the task. */
  case object Unknown extends Source

  /** What a session at `time`, with `picked` chosen in the run picker, must
    * show: the runs on disk newest first, the one it shows, each task's source
    * and partitions newest first, and whether it warns that its own run
    * directory has no readable `job.json`. */
  final case class View(
      available: Vector[Path],
      shown: Option[Path],
      sources: Vector[Source],
      history: Vector[Vector[(Path, Writer)]],
      jobJsonWarning: Boolean)

  def view(jc: JobCase, runs: Vector[Run], root: Path, d: Disk, time: Instant, picked: Option[Path]): View = {
    val ownDir = jc.manifestFile(root, time).getParent
    val available = d.manifests.toVector.filter(_._1.getParent == ownDir.getParent).sortBy(-_._2).map(_._1)
    val pickedShown = picked.filter(available.contains)
    val shown = pickedShown
      .orElse(Some(ownDir).filter(available.contains))
      .orElse(available.headOption)
    // A record is a task's only if it names the task; the session's tasks are
    // named after their views.
    def names(w: Writer, i: Int): Boolean = jc.tasks(w.task).displayName == jc.tasks(i).view
    val history = jc.tasks.indices.map { i =>
      d.taskDirs(i).filter(d.records.contains).map(dir => dir -> d.records(dir))
        .filter { case (_, w) => names(w, i) }.sortBy(-_._2.run)
    }.toVector
    val sources = jc.tasks.indices.map { i =>
      val named: Option[Source] = shown.flatMap { dir =>
        val r = d.manifests(dir)
        val entries = runs(r).result.tasks
        entries.indices.find(k => entries(k).taskName == jc.tasks(i).view).map { k =>
          val rf = runFile(jc, root, d, runs, dir, k)
          val pointed: Either[Option[Problem], Source] = rf match {
            case None => Left(None)
            case Some(f) =>
              val recDir = Paths.get(f).getParent
              d.records.get(recDir) match {
                case Some(w) if names(w, i) => Right(FromRecord(recDir, w, "record"))
                case Some(_) => Left(Some(AnotherTasks))
                case None => Left(Some(if (d.unreadable.contains(recDir)) Unreadable else Missing))
              }
          }
          pointed match {
            case Right(src) => src
            case Left(problem) =>
              history(i).find(_._2.run == r)
                .map { case (hdir, w) => FromRecord(hdir, w, "own record"): Source }
                .getOrElse(FromSummary(r, k, rf, problem))
          }
        }
      }
      named
        .orElse(history(i).headOption.map { case (dir, w) => FromRecord(dir, w, "discovered") })
        .getOrElse(Unknown)
    }.toVector
    val jobJsonWarning = pickedShown.isEmpty && !shown.contains(ownDir) && d.runDirs.contains(ownDir)
    View(available, shown, sources, history, jobJsonWarning)
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
    /** Tasks compared, by how the session reached them (a record — through
      * `job.json`, as the task's own record after the pointer failed, or found
      * on disk — job.json's entry, or nothing) and the status of the run that
      * wrote what it showed. */
    val sources: mutable.Map[(String, String), Int] = mutable.Map.empty.withDefaultValue(0)
    /** Tasks rebuilt from their `job.json` entry for want of their record, by
      * what became of the record. */
    val problems: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Sessions that warned their own run directory has no readable job.json. */
    var jobJsonWarnings: Int = 0
    /** Sessions compared, by how they were opened. */
    val openings: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Corrupted trees reopened, by corruption. */
    val corruptions: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)
    /** Adversarial strings read back as written, by the path they took (a task
      * name through `job.json` or a record, a failure's message through either,
      * a failed validation's name through a record) and the string's label. */
    val strings: mutable.Map[(String, String), Int] = mutable.Map.empty.withDefaultValue(0)
    /** Tasks whose partition picker listed two or more runs. */
    var multiPartitionTasks: Int = 0
    /** Sessions whose run picker listed two or more runs. */
    var multiRunSessions: Int = 0
    /** Runs a fault stopped, by its point and whether it was reached. */
    val faults: mutable.Map[(String, Boolean), Int] = mutable.Map.empty.withDefaultValue(0)
    /** Tasks a session reopened unfinished after a run a fault stopped. */
    var unfinished: Int = 0
    /** Tasks rigged to fail after they start writing that did, by outcome. */
    val writeFailures: mutable.Map[String, Int] = mutable.Map.empty.withDefaultValue(0)

    def add(other: Report): Unit = {
      other.sources.foreach { case (k, v) => sources(k) += v }
      other.problems.foreach { case (k, v) => problems(k) += v }
      jobJsonWarnings += other.jobJsonWarnings
      other.openings.foreach { case (k, v) => openings(k) += v }
      other.corruptions.foreach { case (k, v) => corruptions(k) += v }
      other.strings.foreach { case (k, v) => strings(k) += v }
      multiPartitionTasks += other.multiPartitionTasks
      multiRunSessions += other.multiRunSessions
      other.faults.foreach { case (k, v) => faults(k) += v }
      unfinished += other.unfinished
      other.writeFailures.foreach { case (k, v) => writeFailures(k) += v }
    }
  }

  /** Opens `jc`'s finished `runs` (written under `root`) in sessions and asserts
    * each shows what the runs returned; then applies the case's corruption and
    * asserts the same of the sessions over the damaged tree. */
  def check(jc: JobCase, runs: Vector[Run], root: Path): Report = {
    val report = new Report
    runs.flatMap(_.stopped).foreach { st =>
      report.faults((st.plan.arming.point, st.fired)) += 1
      report.unfinished += st.unfinished
    }
    runs.foreach { run =>
      jc.tasks.foreach { t =>
        (t.outcome, run.result.tasks(t.index).status) match {
          case (o @ (JobDagGen.FailsWhileWriting | JobDagGen.FailsInValidation), TaskStatus.Failed(_)) =>
            report.writeFailures(o.toString) += 1
          case _ => ()
        }
      }
    }
    val jobDir = jc.writeDirectoryJob(root)
    val intact = disk(jc, runs, root)
    checkRecordsRead(jc, runs, intact, report)
    reopen(jc, runs, root, jobDir, intact, "", report)
    if (jc.corruptionApplies) {
      jc.corrupt(root, runs.last.executionTime)
      reopen(jc, runs, root, jobDir, corrupted(jc, runs, root, intact), s" after ${jc.corruption.get}", report)
      report.corruptions(jc.corruption.get.name) += 1
    }
    report
  }

  /** Every record on disk read back through `TaskRunRecord.read`, held to the
    * result of the run that wrote it: its task's name, status and reason, and
    * its failed validations with their sample rows. The session reaches every
    * record a task of its directory job owns; this reaches the ones it cannot,
    * those of a task with an adversarial name. */
  private def checkRecordsRead(jc: JobCase, runs: Vector[Run], d: Disk, report: Report): Unit =
    d.records.foreach { case (dir, w) =>
      val run = runs(w.run).result.tasks(w.task)
      val rec = TaskRunRecord.read(dir).getOrElse(throw new AssertionError(
        s"[hydration] the record ${jc.tasks(w.task).view} wrote in $dir does not read back\n  case = $jc"))
      val failures = rec.validations.filterNot(_.passed).map { v =>
        (v.name, v.failedRowCount, v.sampleFile.flatMap(TaskRunRecord.readValidationSample(dir, _)).getOrElse(""))
      }
      val ranWith = run.status match {
        case TaskStatus.ValidationFailed(fs) => fs.map(f => (f.validationName, f.rowCount, f.sampleRowsCsv))
        case _ => Nil
      }
      val agrees = rec.taskName == run.taskName && rec.status == runStatus(run.status) &&
        rec.errorMessage == reason(run.status) && (run.status match {
          case _: TaskStatus.ValidationFailed => failures == ranWith
          case _ => true
        })
      assert(agrees, s"[hydration] the record ${jc.tasks(w.task).view} wrote in $dir reads back as " +
        s"${DagSchedule.shown(rec.toString)}, the run said ${DagSchedule.shown(run.toString)}\n  case = $jc")
      val t = jc.tasks(w.task)
      t.name.foreach(a => report.strings(("task name through a record", a.label)) += 1)
      run.status match {
        case _: TaskStatus.Failed => t.failure.foreach(a => report.strings(("failure through a record", a.label)) += 1)
        case _: TaskStatus.ValidationFailed =>
          t.checks.foreach(a => report.strings(("validation name through a record", a.label)) += 1)
        case _ => ()
      }
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

  /** After `jc`'s run at `time` was stopped by a fault: a session opened then
    * shows a task whose record says `Running` as unfinished, with that
    * record's start and nothing else, never as succeeded; and shows a task
    * unfinished only for such a record. Returns how many it showed
    * unfinished. */
  def checkUnfinished(jc: JobCase, root: Path, time: Instant): Int = {
    val s = open(jc, root, jc.writeDirectoryJob(root), time)
    def ctx: String = s"\n  reopened after the run at $time a fault stopped\n  case = $jc"
    assert(s.runState == RunState.Idle, s"[hydration] the session did not load the job: ${s.runState}$ctx")
    jc.tasks.indices.count { i =>
      val running = s.taskRecordFor(i).filter(_.status == TaskRunStatus.Running)
      (running, s.taskStates(i)) match {
        case (Some(rec), UiTaskState.Unfinished(at)) =>
          assert(at == rec.startedAt && s.outputPathFor(i).isEmpty,
            s"[hydration] task t$i is unfinished from ${rec.startedAt}, yet the session shows it from $at with " +
              s"output ${s.outputPathFor(i)}$ctx")
          true
        case (Some(_), other) =>
          throw new AssertionError(s"[hydration] task t$i's record says Running, yet the session shows $other$ctx")
        case (None, UiTaskState.Unfinished(_)) =>
          throw new AssertionError(
            s"[hydration] task t$i shows unfinished, yet its record is ${s.taskRecordFor(i).map(_.status)}$ctx")
        case _ => false
      }
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
        val shownRun = runs(d.manifests(dir)).result
        jc.tasks.foreach { t =>
          t.name.foreach(a => report.strings(("task name through job.json", a.label)) += 1)
          (t.failure, shownRun.tasks(t.index).status) match {
            case (Some(a), _: TaskStatus.Failed) => report.strings(("failure through job.json", a.label)) += 1
            case _ => ()
          }
        }
    }

    jc.tasks.indices.foreach { i =>
      checkHistory(runs, s, v, i, ctx, report)
      v.sources(i) match {
        case FromRecord(dir, w, how) =>
          checkFromRecord(runs, s, i, dir, w, d.owners(dir), ctx)
          report.sources((how, kind(runs(w.run).result.tasks(w.task).status))) += 1
        case FromSummary(r, k, rf, problem) =>
          checkFromSummary(runs, s, d, i, r, k, rf, problem, ctx)
          report.sources(("summary", kind(runs(r).result.tasks(k).status))) += 1
          problem.foreach(p => report.problems(p.word) += 1)
        case Unknown =>
          assert(s.taskStates(i) == UiTaskState.Pending,
            s"[hydration] task ${jc.tasks(i).view}: nothing on disk names it, yet the session shows " +
              s"${s.taskStates(i)}$ctx")
          assert(s.summaryReasonFor(i).isEmpty,
            s"[hydration] task ${jc.tasks(i).view} is unknown, yet the session gives ${s.summaryReasonFor(i)}$ctx")
          report.sources(("unknown", "Pending")) += 1
      }
    }

    // One warning per task rebuilt from its entry for want of its record, and
    // one for the run directory's job.json.
    val (perTask, jobLevel) = s.hydrationWarnings.partition(_.startsWith("task '"))
    val expectedPerTask = v.sources.count {
      case FromSummary(_, _, _, Some(_)) => true
      case _ => false
    }
    assert(perTask.length == expectedPerTask,
      s"[hydration] ${perTask.length} task warnings for $expectedPerTask tasks rebuilt for want of their record: " +
        s"${perTask.map(DagSchedule.shown)}$ctx")
    assert(jobLevel.length == (if (v.jobJsonWarning) 1 else 0),
      s"[hydration] job.json warnings ${jobLevel.map(DagSchedule.shown)}, expected " +
        s"${if (v.jobJsonWarning) "one" else "none"}$ctx")
    if (v.jobJsonWarning) report.jobJsonWarnings += 1
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
    // Every input as the run read it, one that failed to load or that no task
    // reads too.
    val inputs = jc.inputRecords(root)
    assert(rec.inputs.contains(inputs),
      s"[hydration] job.json inputs ${rec.inputs.map(_.map(i => DagSchedule.shown(i.toString)))}, expected the run's $inputs$ctx")
    val expected = res.tasks.indices.map { k =>
      val t = res.tasks(k)
      val failed = t.status match {
        case TaskStatus.ValidationFailed(fs) => fs.map(f => FailedValidation(f.validationName, f.rowCount))
        case _ => Nil
      }
      // What the task's main SQL reads, as generated: its FROM sources, never
      // the task a validation reads.
      val gen = jc.tasks(k)
      JobTaskSummary(
        taskName = t.taskName,
        status = runStatus(t.status),
        runFile = runFile(jc, root, d, runs, runDir, k),
        rowsProduced = t.rowsProduced,
        failedValidationCount = failed.size,
        errorMessage = reason(t.status),
        startedAt = Some(t.startedAt),
        finishedAt = Some(t.finishedAt),
        enqueuedAt = Some(t.enqueuedAt),
        failedValidations = failed,
        readsInputs = jc.readsInputs(gen),
        readsTasks = gen.taskSrcs.distinct.sorted.map(i => res.tasks(i).taskName))
    }.toVector
    rec.tasks.toVector.zipAll(expected, null, null).zipWithIndex.foreach { case ((got, want), k) =>
      assert(got == want, s"[hydration] job.json entry $k reads back as ${DagSchedule.shown(String.valueOf(got))}, " +
        s"expected ${DagSchedule.shown(String.valueOf(want))}$ctx")
    }
  }

  /** A task's partition picker: every directory its path rendered to holding a
    * readable record that names it, newest first, each read as its writer wrote
    * it. */
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
    * every field, with an output path — the record's directory — only where
    * that run wrote output. The record names the task, so it is the one its
    * directory's task wrote, beside its own sample rows. */
  private def checkFromRecord(
      runs: Vector[Run], s: JobSession, i: Int, dir: Path, w: Writer, owner: Writer, ctx: String): Unit = {
    assert(w.task == i && owner == w,
      s"[hydration] the oracle chose a record t${w.task} wrote in t${owner.task}'s directory for task t$i$ctx")
    val written = runs(w.run).result.tasks(w.task)
    val expected = written.copy(outputPath = if (wroteOutput(written.status)) Some(dir.toString) else None)
    val got = done(s, i, ctx)
    assert(got == expected,
      s"[hydration] task t$i, rebuilt from the record in $dir:\n    got      ${DagSchedule.shown(got.toString)}\n" +
        s"    run said ${DagSchedule.shown(expected.toString)}$ctx")
    assert(s.outputPathFor(i) == expected.outputPath,
      s"[hydration] task t$i shows output ${s.outputPathFor(i)}, the run reported ${expected.outputPath}$ctx")
    val rec = s.taskRecordFor(i).getOrElse(throw new AssertionError(
      s"[hydration] task t$i was rebuilt from the record in $dir but the session holds no record$ctx"))
    assert(rec.taskName == written.taskName && rec.status == runStatus(written.status) &&
      rec.rowsProduced == written.rowsProduced && rec.enqueuedAt.contains(written.enqueuedAt),
      s"[hydration] task t$i's record reads back as $rec, the run said $written$ctx")
    assert(s.summaryReasonFor(i).isEmpty,
      s"[hydration] task t$i was rebuilt from its record, yet the session gives ${s.summaryReasonFor(i)}$ctx")
  }

  /** A task rebuilt from its `job.json` entry is the result its run returned
    * but for its failed validations' sample rows, which only its record held,
    * and its output path, kept where the `runFile`'s directory still exists.
    * The session says why it did not use the record, if one was expected. */
  private def checkFromSummary(
      runs: Vector[Run], s: JobSession, d: Disk, i: Int, r: Int, k: Int, runFile: Option[String],
      problem: Option[Problem], ctx: String): Unit = {
    val run = runs(r).result.tasks(k)
    val status = run.status match {
      case TaskStatus.ValidationFailed(fs) => TaskStatus.ValidationFailed(fs.map(_.copy(sampleRowsCsv = "")))
      case other => other
    }
    val dir = runFile.map(rf => Paths.get(rf).getParent).filter(d.holds)
    val expected = run.copy(
      status = status,
      outputPath = if (wroteOutput(run.status)) dir.map(_.toString) else None)
    val got = done(s, i, ctx)
    assert(got == expected,
      s"[hydration] task t$i, rebuilt from job.json's entry:\n    got      ${DagSchedule.shown(got.toString)}\n" +
        s"    expected ${DagSchedule.shown(expected.toString)}$ctx")
    assert(s.outputPathFor(i) == expected.outputPath,
      s"[hydration] task t$i shows output ${s.outputPathFor(i)}, expected ${expected.outputPath}$ctx")
    assert(s.taskRecordFor(i).isEmpty,
      s"[hydration] task t$i was rebuilt from job.json's entry yet holds record ${s.taskRecordFor(i)}$ctx")
    val because = s.summaryReasonFor(i)
    assert(because.isDefined == problem.isDefined && problem.forall(p => because.exists(_.contains(p.word))),
      s"[hydration] task t$i was rebuilt from its entry because its record is ${problem.map(_.word)}, yet the " +
        s"session gives ${because.map(DagSchedule.shown)}$ctx")
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

  private def assert(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new AssertionError(msg)
}
