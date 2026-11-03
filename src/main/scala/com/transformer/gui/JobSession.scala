package com.transformer.gui

import com.transformer.core.{Catalog, CatalogView}
import com.transformer.job.{DataJob, DirectoryJobLoader, InputFilePath, InputResolver, JobOutputLayout, JobResult, JobRunRecord, JobTaskSummary, SQLTask, TaskDag, TaskResult, TaskRunRecord, TaskRunStatus, TaskStatus, ValidationFailure}
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.read.parquet.ParquetReader
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.Locale
import scala.collection.mutable
import scala.util.control.NonFatal

/** Per-task UI status. Distinct from [[TaskStatus]] because we need 'Queued'
  * and 'Running' states that the engine doesn't track (it goes straight from
  * pending to a terminal result).
  *
  * Lifecycle: `Pending` (not yet eligible) → `Queued` (deps satisfied, waiting
  * for a worker thread) → `Running` (worker picked up) → `Done(result)`. The
  * Queued ↔ Running gap is where the user-perceived "stuck" feeling lives — it
  * appears as queue-wait time in the task result. */
sealed trait UiTaskState
object UiTaskState {
  case object Pending extends UiTaskState
  case object Queued extends UiTaskState
  case object Running extends UiTaskState
  final case class Done(result: TaskResult) extends UiTaskState
  /** Reopened from a `_run.json` that says `Running`: the run that started
    * the task at `startedAt` stopped before it finished, or is still running
    * in another process. It has no duration, no rows and no output to show.
    * A run in this GUI shows a running task from memory, as `Running`. */
  final case class Unfinished(startedAt: Instant) extends UiTaskState
}

/** Per-input UI status. Inputs are now scheduled as DAG nodes alongside tasks
  * so the GUI can render them as Loading instead of static "always there"
  * grey boxes. */
sealed trait UiInputState
object UiInputState {
  case object Pending extends UiInputState
  case object Loading extends UiInputState
  case object Loaded extends UiInputState
  final case class Failed(error: String) extends UiInputState
}

/** Top-level run lifecycle. */
sealed trait RunState
object RunState {
  case object Idle extends RunState
  case object Running extends RunState
  final case class Done(result: JobResult) extends RunState
  final case class LoadFailed(error: String) extends RunState
}

/** What's currently selected in the DAG canvas. */
sealed trait Selection
object Selection {
  /** A SQLTask node (index into [[TaskDag.nodes]]). */
  final case class Task(index: Int) extends Selection
  /** An input view node (index into [[JobSession.inputs]]). */
  final case class Input(index: Int) extends Selection
}

/** Mutable, FX-thread-affinity session state shared by every panel.
  *
  * The session does NOT own threads or fire callbacks from background work — callers
  * (e.g. the controls panel's Run button) are responsible for posting state changes
  * back to the FX thread (via [[FxHelpers.onFx]]) before calling mutators here.
  *
  * Listeners are invoked synchronously inside mutators, in registration order.
  */
final class JobSession {

  private var _jobDir: Option[Path] = None
  private var _executionTime: Instant = Instant.now()
  private var _outputDir: Option[String] = None // user override; falls back to <jobDir>/output
  private var _dataJob: Option[DataJob] = None
  private var _dag: Option[TaskDag] = None
  private var _inputs: Vector[InputFilePath] = Vector.empty
  private var _renderedInputPaths: Vector[String] = Vector.empty
  private var _layout: Option[DagLayout] = None
  private var _taskStates: Vector[UiTaskState] = Vector.empty
  private var _inputStates: Vector[UiInputState] = Vector.empty
  private var _runState: RunState = RunState.Idle
  private var _selection: Option[Selection] = None
  // Per-task resolved output paths captured from JobResult so we can re-read after run.
  private var _outputPaths: Vector[Option[String]] = Vector.empty
  // Per-task records loaded from disk on job-dir open. Populates UI state with
  // the previous run's results so the GUI is non-empty before the user presses Run.
  private var _taskRecords: Vector[Option[TaskRunRecord]] = Vector.empty
  // Every run of each task discovered under its templated output path: the
  // records there that name the task. Sorted newest-first by writtenAt. Used
  // by the GUI to render a partition picker when a task has 2+ persisted runs.
  private var _historicalRuns: Vector[Seq[(Path, TaskRunRecord)]] = Vector.empty
  // Job-level manifest from disk (<outputDir>/job.json by default). Loaded
  // alongside per-task records on open so the run-log can surface warnings
  // and the GUI can find every task's record file from a single entry point.
  private var _jobRecord: Option[JobRunRecord] = None
  // Sibling runs discovered at the parent of the rendered jobRunOutput path.
  // Populated when the user has templated `outputDir` to vary by run (e.g.
  // `/data/runs/{{ today }}`) — the parent then holds N self-contained
  // `<runDir>/job.json` snapshots. Sorted newest-first by finishedAt.
  // Empty if only one run (or none) is on disk at that layout.
  private var _availableRuns: Vector[(Path, JobRunRecord)] = Vector.empty
  // When set, overrides the default `<outputDir>/job.json` path so hydration
  // pulls from a user-selected historical run. Reset on jobDir / executionTime
  // changes (the default-resolved path may have moved).
  private var _selectedRunDir: Option[Path] = None
  // Per task, why the last hydration rebuilt it from job.json's entry rather
  // than its own `_run.json`, when it did and a record was expected.
  private var _summaryReasons: Vector[Option[String]] = Vector.empty
  // What the last hydration could not read, for the run log.
  private var _hydrationWarnings: Vector[String] = Vector.empty

  private val listeners = mutable.ArrayBuffer.empty[() => Unit]

  def addListener(f: () => Unit): Unit = listeners += f
  private def notifyListeners(): Unit = {
    var i = 0
    while (i < listeners.size) {
      try listeners(i)() catch { case NonFatal(_) => () }
      i += 1
    }
  }

  // ---- Accessors -----------------------------------------------------------

  def jobDir: Option[Path] = _jobDir
  def executionTime: Instant = _executionTime
  /** Output dir actually in effect: explicit override if set, else <jobDir>/output. */
  def effectiveOutputDir: Option[String] = _outputDir.orElse(
    _jobDir.map(_.resolve("output").toString)
  )
  def outputDirOverride: Option[String] = _outputDir
  def dataJob: Option[DataJob] = _dataJob
  def dag: Option[TaskDag] = _dag
  def inputs: Vector[InputFilePath] = _inputs
  /** The on-disk path each input resolves to with template variables substituted. */
  def renderedInputPath(inputIndex: Int): Option[String] = _renderedInputPaths.lift(inputIndex)
  def layout: Option[DagLayout] = _layout
  def taskStates: Vector[UiTaskState] = _taskStates
  def inputStates: Vector[UiInputState] = _inputStates
  def inputStateFor(inputIndex: Int): UiInputState =
    _inputStates.lift(inputIndex).getOrElse(UiInputState.Pending)
  def runState: RunState = _runState
  def selection: Option[Selection] = _selection
  /** Convenience: returns the selected task's index if a task is selected, else None. */
  def selectedTaskIndex: Option[Int] = _selection.collect { case Selection.Task(i) => i }
  /** Convenience: returns the selected input's index if an input is selected, else None. */
  def selectedInputIndex: Option[Int] = _selection.collect { case Selection.Input(i) => i }
  def outputPathFor(taskIndex: Int): Option[String] = _outputPaths.lift(taskIndex).flatten

  /** Cached `_run.json` record for this task, if any was discovered when the
    * job directory was opened (or after the most recent run). Used by the UI
    * to show "Loaded from previous run" provenance and render validation
    * detail without re-running the task.
    */
  def taskRecordFor(taskIndex: Int): Option[TaskRunRecord] = _taskRecords.lift(taskIndex).flatten

  /** Every run of this task discovered under its templated output path — the
    * records there that name the task; one another task wrote is not its run —
    * newest-first. Surfaces a partition picker in the UI when 2+ runs exist.
    *
    * Each entry is `(partitionPath, record)` — the path is the on-disk output
    * directory for that specific run, ready to feed to a reader.
    */
  def historicalRunsFor(taskIndex: Int): Seq[(Path, TaskRunRecord)] =
    _historicalRuns.lift(taskIndex).getOrElse(Nil)

  /** Last-loaded `job.json` aggregate, if one exists at the configured
    * `jobRunOutput` path. The GUI uses this for cross-task consistency
    * warnings and to drive single-file-load reload on open. */
  def jobRecord: Option[JobRunRecord] = _jobRecord

  /** All run directories discovered at the parent of the rendered
    * `jobRunOutput` path, newest-first by `finishedAt`. Each entry is
    * `(runDir, manifest)`. Surfaces a run picker in the GUI when the user
    * has templated `outputDir` to vary by run; otherwise this is at most a
    * single-element list (the current run) — or empty before the first
    * run.
    */
  def availableRuns: Vector[(Path, JobRunRecord)] = _availableRuns

  /** Currently-displayed historical run directory, or None when displaying
    * the default (most-recent) run. Mutated via [[selectRun]]; the run
    * picker UI reads this to highlight the active row. */
  def selectedRunDir: Option[Path] = _selectedRunDir

  /** Why this task was rebuilt from `job.json`'s entry rather than its own
    * `_run.json` when the job directory was opened — the record is missing,
    * unreadable, or names another task. None for a task rebuilt from its record,
    * one with no output directory (the entry is all it ever has), and one not
    * rebuilt at all. A task rebuilt from its entry has no sample rows for its
    * failed validations. */
  def summaryReasonFor(taskIndex: Int): Option[String] = _summaryReasons.lift(taskIndex).flatten

  /** What opening the job directory could not read, one line each for the run
    * log: a `job.json` that is missing or unreadable beside records of the run,
    * and each task rebuilt from its `job.json` entry because its record was not
    * usable. */
  def hydrationWarnings: Vector[String] = _hydrationWarnings

  /** The job-wide output directory the runner will actually use, with any
    * `{{ today }}`-style template variables already resolved against
    * [[executionTime]]. None if no job dir is loaded.
    */
  def effectiveOutputDirRendered: Option[String] =
    effectiveOutputDir.map { raw =>
      try TemplateRenderer.render(raw, TemporalVariables(_executionTime))
      catch { case NonFatal(_) => raw }
    }

  /** The per-task on-disk path the runner will write to (template variables in
    * the path are pre-rendered against [[executionTime]]). None if the selected
    * task has no `outputFile` set.
    */
  def plannedOutputPathFor(taskIndex: Int): Option[String] =
    for {
      d <- _dag
      node <- d.nodes.lift(taskIndex)
      ofp <- node.task.outputFile
    } yield {
      try TemplateRenderer.render(ofp.path, TemporalVariables(_executionTime))
      catch { case NonFatal(_) => ofp.path }
    }

  // ---- Mutators (FX-thread only) -------------------------------------------

  def setExecutionTime(t: Instant): Unit = {
    if (t != _executionTime) {
      _executionTime = t
      reloadJob()
    }
  }

  def setOutputDirOverride(dir: Option[String]): Unit = {
    val cleaned = dir.map(_.trim).filter(_.nonEmpty)
    if (cleaned != _outputDir) {
      _outputDir = cleaned
      reloadJob()
    }
  }

  def openJobDir(dir: Path): Unit = {
    _jobDir = Some(dir)
    // Reset any previous selection — the new job's indices won't match.
    _selection = None
    // A path from the old job's run picker is meaningless for the new
    // job; let hydration pick the new job's default.
    _selectedRunDir = None
    reloadJob()
  }

  /** Re-read the on-disk job dir and, after the new DAG is built, re-select the
    * node whose viewName matches `preferredViewName` (if given) or the
    * currently-selected node's viewName (if `preferredViewName` is None) —
    * even if its index has shifted. If the viewName is gone (e.g. the user
    * deleted it), selection is cleared.
    *
    * Used after the GUI writes files (add/edit/delete) so the user's
    * selection survives the round-trip through disk.
    */
  def reloadPreservingSelection(preferredViewName: Option[String]): Unit = {
    val target = preferredViewName.orElse(currentSelectedViewName)
    reloadJob()
    val newSel = target.flatMap(indexOfViewName)
    if (newSel != _selection) {
      _selection = newSel
      notifyListeners()
    }
  }

  private def currentSelectedViewName: Option[String] = _selection.flatMap {
    case Selection.Task(i)  => _dag.flatMap(_.nodes.lift(i)).flatMap(_.task.viewName)
    case Selection.Input(i) => _inputs.lift(i).map(_.viewName)
  }

  private def indexOfViewName(name: String): Option[Selection] = {
    val taskIdx = _dag.flatMap { dag =>
      val i = dag.nodes.indexWhere(_.task.viewName.exists(_.equalsIgnoreCase(name)))
      if (i >= 0) Some(i) else None
    }
    taskIdx.map(Selection.Task(_)).orElse {
      val i = _inputs.indexWhere(_.viewName.equalsIgnoreCase(name))
      if (i >= 0) Some(Selection.Input(i)) else None
    }
  }

  /** Re-resolve the DataJob + DAG from the current jobDir / executionTime /
    * outputDir. Resets per-task UI state to Pending. Records load failures as
    * [[RunState.LoadFailed]] so the UI can surface them.
    */
  private def reloadJob(): Unit = {
    _jobDir match {
      case None =>
        _dataJob = None
        _dag = None
        _inputs = Vector.empty
        _renderedInputPaths = Vector.empty
        _layout = None
        _taskStates = Vector.empty
        _inputStates = Vector.empty
        _outputPaths = Vector.empty
        _taskRecords = Vector.empty
        _historicalRuns = Vector.empty
        _jobRecord = None
        _availableRuns = Vector.empty
        _selectedRunDir = None
        _summaryReasons = Vector.empty
        _hydrationWarnings = Vector.empty
        _runState = RunState.Idle
      case Some(dir) =>
        try {
          val job = DirectoryJobLoader.load(
            jobDir = dir,
            outputDir = _outputDir,
            temporalVariables = Some(TemporalVariables(_executionTime))
          )
          val dag = job.buildDag()
          _dataJob = Some(job)
          _dag = Some(dag)
          _inputs = job.inputs.toVector
          val tv = TemporalVariables(_executionTime)
          _renderedInputPaths = _inputs.map { in =>
            try TemplateRenderer.render(in.path, tv)
            catch { case NonFatal(_) => in.path }
          }
          _layout = Some(DagLayout.compute(dag, _inputs))
          _taskStates = Vector.fill(dag.nodes.size)(UiTaskState.Pending)
          _inputStates = Vector.fill(_inputs.size)(UiInputState.Pending)
          _outputPaths = Vector.fill(dag.nodes.size)(None)
          _taskRecords = Vector.fill(dag.nodes.size)(None)
          _runState = RunState.Idle
          hydrateRunRecords(dag, job)
        } catch {
          case NonFatal(e) =>
            _dataJob = None
            _dag = None
            _inputs = Vector.empty
            _renderedInputPaths = Vector.empty
            _layout = None
            _taskStates = Vector.empty
            _inputStates = Vector.empty
            _outputPaths = Vector.empty
            _taskRecords = Vector.empty
            _historicalRuns = Vector.empty
            _jobRecord = None
            _availableRuns = Vector.empty
            _selectedRunDir = None
            _summaryReasons = Vector.empty
            _hydrationWarnings = Vector.empty
            _runState = RunState.LoadFailed(Option(e.getMessage).getOrElse(e.toString))
        }
    }
    notifyListeners()
  }

  /** Load whatever past-run state exists on disk so the canvas isn't an empty
    * sea of grey on first open, showing each task as its run reported it.
    *
    *   1. Walk each task's templated output pattern via
    *      [[TaskRunRecord.discover]] for the per-task partition picker: every
    *      record under the pattern that names the task, newest first. A task
    *      with 2+ partitions on disk surfaces a dropdown so the user can browse
    *      prior execution-time slices.
    *
    *   2. Detect the *job-level* multi-run layout by walking the parent of
    *      the rendered `jobRunOutput` path for sibling run subdirs (each
    *      containing its own `job.json`). When the user has templated
    *      `outputDir` to vary by run, this populates [[availableRuns]] —
    *      surfaced in the GUI's run picker. The run shown is the picked one,
    *      else the execution time's own, else the newest.
    *
    *   3. Rebuild each task the shown run's `job.json` names from the
    *      `_run.json` its `runFile` points at, if that record names the task.
    *      If it does not — missing, unreadable, or another task's — use the
    *      task's own record of the same run if step 1 found one, and else the
    *      `job.json` entry, which holds everything but the failed validations'
    *      sample rows. A task rebuilt from its entry for want of its record gets
    *      a [[summaryReasonFor]] and a line in [[hydrationWarnings]].
    *
    *   4. A task the manifest does not name (added since the last run, or no
    *      `job.json` at all) falls back to its newest record on disk. A
    *      `job.json` missing or unreadable in a run directory that exists is a
    *      line in [[hydrationWarnings]].
    *
    * A record that says `Running`, by whichever step it is reached, is a run
    * that never finished, and its task reopens as [[UiTaskState.Unfinished]]:
    * that record, not `job.json`, says what its directory holds now.
    *
    * Tasks present in the manifest but no longer in the DAG are silently
    * ignored. */
  private def hydrateRunRecords(dag: TaskDag, job: DataJob): Unit = {
    val states = Array.fill[UiTaskState](dag.nodes.size)(UiTaskState.Pending)
    val outputs = Array.fill[Option[String]](dag.nodes.size)(None)
    val records = Array.fill[Option[TaskRunRecord]](dag.nodes.size)(None)
    val historicals = Array.fill[Seq[(Path, TaskRunRecord)]](dag.nodes.size)(Nil)
    val reasons = Array.fill[Option[String]](dag.nodes.size)(None)

    // 1. Walk each task's templated output pattern for the historical picker.
    //    Done first so the picker is populated even if job.json is missing.
    var i = 0
    while (i < dag.nodes.size) {
      historicals(i) = ownRecords(dag.nodes(i).task)
      i += 1
    }

    // 2. Try to load the job manifest. Templated against the current
    //    execution time so the *default* run dir (parent of rendered
    //    jobRunOutput) corresponds to the currently-selected exec time.
    //    Then walk the parent of that default dir to populate
    //    `_availableRuns` with every sibling run that has a job.json — this
    //    is what surfaces the multi-run picker when the user has templated
    //    `outputDir` to vary by run.
    val vars = TemporalVariables(_executionTime)
    val defaultJobRunPath: Option[Path] = job.jobRunOutput.flatMap { ofp =>
      try Some(Paths.get(TemplateRenderer.render(ofp.path, vars)))
      catch { case NonFatal(_) => None }
    }
    val defaultRunDir: Option[Path] = defaultJobRunPath.flatMap(p => Option(p.getParent))

    _availableRuns = defaultRunDir.flatMap(d => Option(d.getParent)) match {
      case Some(parent) =>
        JobOutputLayout.detect(parent) match {
          case JobOutputLayout.MultiRun(runs) => runs.toVector
          case _ =>
            // Parent doesn't itself look like a multi-run dir, so the
            // current default (if it exists) stands alone.
            defaultJobRunPath.flatMap(p =>
              try JobRunRecord.read(p).map(rec => defaultRunDir.get -> rec)
              catch { case NonFatal(_) => None }
            ).toVector
        }
      case None => Vector.empty
    }

    // Resolve which run is being displayed. Selection survives execution-time
    // changes so a user inspecting a historical run isn't snapped back to
    // "current."
    val pickedRunDir: Option[Path] = _selectedRunDir.filter(d => _availableRuns.exists(_._1 == d))
    val activeRunDir: Option[Path] = pickedRunDir
      .orElse(defaultRunDir.filter(d => _availableRuns.exists(_._1 == d)))
      .orElse(_availableRuns.headOption.map(_._1))

    val jobRecord: Option[JobRunRecord] = activeRunDir.flatMap { d =>
      _availableRuns.find(_._1 == d).map(_._2)
        .orElse(try JobRunRecord.read(d.resolve("job.json")) catch { case NonFatal(_) => None })
    }
    _jobRecord = jobRecord

    // Build a name→index map once so manifest matching is cheap regardless of
    // task count.
    val nameToIndex: Map[String, Int] =
      dag.nodes.iterator.zipWithIndex.map { case (n, idx) => n.task.displayName -> idx }.toMap

    def fromRecord(idx: Int, dir: Path, rec: TaskRunRecord): Unit = {
      val state = stateFromRecord(rec, dir)
      records(idx) = Some(rec)
      outputs(idx) = outputOf(state)
      states(idx) = state
    }

    // 3. Apply manifest entries where we have one.
    jobRecord.foreach { rec =>
      rec.tasks.foreach { summary =>
        nameToIndex.get(summary.taskName).foreach { idx =>
          // Left(Some(problem)) when the pointer leads to no usable record;
          // Left(None) when the task has no record to point at.
          val pointed: Either[Option[String], (Path, TaskRunRecord)] = summary.runFile match {
            case Some(runFile) => pointedRecord(runFile, summary.taskName).left.map(Some(_))
            case None          => Left(None)
          }
          pointed.toOption
            .orElse(historicals(idx).find { case (_, r) => r.executionTime == rec.executionTime }) match {
            case Some((dir, taskRec)) => fromRecord(idx, dir, taskRec)
            case None =>
              val state = stateFromSummary(summary)
              outputs(idx) = outputOf(state)
              states(idx) = state
              reasons(idx) = pointed.left.toOption.flatten
          }
        }
      }
    }

    // 4. For DAG tasks not covered by the manifest (added since last run, or
    //    no job.json at all), fall back to "most recent partition on disk".
    var j = 0
    while (j < dag.nodes.size) {
      if (states(j) == UiTaskState.Pending)
        historicals(j).headOption.foreach { case (path, rec) => fromRecord(j, path, rec) }
      j += 1
    }

    val warnings = Vector.newBuilder[String]
    // The execution time's own run directory exists without a readable
    // job.json — the run died before writing it, or it was damaged since —
    // and the user did not pick another run to look at.
    for {
      path <- defaultJobRunPath
      dir <- defaultRunDir
      if pickedRunDir.isEmpty && !activeRunDir.contains(dir) && Files.isDirectory(dir)
    } {
      val problem = if (Files.isRegularFile(path)) s"job.json at $path is unreadable" else s"no job.json at $path"
      warnings += (activeRunDir match {
        case Some(other) => s"$problem; showing the run in $other instead"
        case None =>
          s"$problem; each task is restored from its own _run.json, and the run's verdict and warnings are unknown"
      })
    }
    dag.nodes.indices.foreach { k =>
      reasons(k).foreach { reason =>
        warnings += s"task '${dag.nodes(k).task.displayName}': $reason; shown from job.json, which holds no sample rows"
      }
    }

    _taskStates = states.toVector
    _outputPaths = outputs.toVector
    _taskRecords = records.toVector
    _historicalRuns = historicals.toVector
    _summaryReasons = reasons.toVector
    _hydrationWarnings = warnings.result()
  }

  /** Every record on disk under `task`'s templated output path that names the
    * task, newest first: a record another task wrote is not this task's run. */
  private def ownRecords(task: SQLTask): Seq[(Path, TaskRunRecord)] =
    task.outputFile.map { ofp =>
      val runs = try TaskRunRecord.discover(ofp.path) catch { case NonFatal(_) => Nil }
      runs.filter(_._2.taskName == task.displayName)
    }.getOrElse(Nil)

  /** The `_run.json` a `job.json` entry's `runFile` points at, when it is
    * readable and names the task; otherwise what is wrong with it. */
  private def pointedRecord(runFile: String, taskName: String): Either[String, (Path, TaskRunRecord)] =
    try {
      val file = Paths.get(runFile)
      val dir = file.getParent
      if (dir == null || !Files.isRegularFile(file)) Left(s"its _run.json at $runFile is missing")
      else TaskRunRecord.read(dir) match {
        case None                                => Left(s"its _run.json at $runFile is unreadable")
        case Some(rec) if rec.taskName != taskName => Left(s"the _run.json at $runFile names task '${rec.taskName}'")
        case Some(rec)                           => Right(dir -> rec)
      }
    } catch {
      case NonFatal(e) => Left(s"its _run.json at $runFile cannot be read: ${Option(e.getMessage).getOrElse(e.toString)}")
    }

  /** The output path a reopened task shows: its run's, when it wrote one. */
  private def outputOf(state: UiTaskState): Option[String] = state match {
    case UiTaskState.Done(result) => result.outputPath
    case _ => None
  }

  /** Rehydrate a full in-memory [[TaskResult]] from an on-disk [[TaskRunRecord]],
    * as the run reported it. For [[TaskRunStatus.ValidationFailed]] this also
    * reads each per-validation `_validation-<slug>.csv` sample file from `dir`
    * so the validation cards can render the original failure detail. A
    * `Running` record is a run that never finished, which reported nothing:
    * [[UiTaskState.Unfinished]].
    *
    * `dir` is the task's output only when the run wrote output there: a Failed
    * or Skipped task's directory holds nothing but its record, and the run
    * reported no output path for it. A record written before records carried an
    * enqueue time gets the epoch, which [[TaskResult.queueWaitMillis]] reads as
    * unknown. */
  private def stateFromRecord(rec: TaskRunRecord, dir: Path): UiTaskState = {
    def done(status: TaskStatus, finishedAt: Instant): UiTaskState = UiTaskState.Done(TaskResult(
      taskName = rec.taskName,
      status = status,
      rowsProduced = rec.rowsProduced,
      outputPath = if (TaskRunStatus.wroteOutput(rec.status)) Some(dir.toString) else None,
      startedAt = rec.startedAt,
      finishedAt = finishedAt,
      enqueuedAt = rec.enqueuedAt.getOrElse(Instant.EPOCH)
    ))
    // Every record but a Running one says when its run finished.
    val finished = rec.finishedAt.getOrElse(rec.startedAt)
    rec.status match {
      case TaskRunStatus.Running => UiTaskState.Unfinished(rec.startedAt)
      case TaskRunStatus.Succeeded => done(TaskStatus.Succeeded, finished)
      case TaskRunStatus.ValidationFailed =>
        val failures = rec.validations.filterNot(_.passed).map { vr =>
          val sample = vr.sampleFile
            .flatMap(sf => TaskRunRecord.readValidationSample(dir, sf))
            .getOrElse("")
          ValidationFailure(vr.name, vr.failedRowCount, sample)
        }
        done(TaskStatus.ValidationFailed(failures), finished)
      case TaskRunStatus.Failed =>
        done(TaskStatus.Failed(rec.errorMessage.getOrElse("(no error message recorded)")), finished)
      case TaskRunStatus.Skipped =>
        done(TaskStatus.Skipped(rec.errorMessage.getOrElse("(no reason recorded)")), finished)
    }
  }

  /** Rebuild a [[TaskResult]] from a `job.json` entry alone, for a task whose
    * record is missing, unreadable or another task's, or that has none. The
    * entry holds the status, reason, rows, timestamps, and each failed
    * validation's name and row count; not its sample rows, which come back
    * empty. The output path follows the record rule — the `runFile`'s
    * directory when the status wrote output — where that directory still
    * exists. An entry from before entries carried timestamps and failed
    * validations gives the epoch (unknown) and `ValidationFailed(Nil)` (which
    * ones unknown). A runner writes `job.json` once its tasks are done, so no
    * entry says `Running`; one that does is unfinished, as such a record is. */
  private def stateFromSummary(s: JobTaskSummary): UiTaskState = {
    def done(status: TaskStatus): UiTaskState = {
      val outputDir =
        if (!TaskRunStatus.wroteOutput(s.status)) None
        else s.runFile.flatMap { rf =>
          try Option(Paths.get(rf).getParent).filter(Files.isDirectory(_)).map(_.toString)
          catch { case NonFatal(_) => None }
        }
      UiTaskState.Done(TaskResult(
        taskName = s.taskName,
        status = status,
        rowsProduced = s.rowsProduced,
        outputPath = outputDir,
        startedAt = s.startedAt.getOrElse(Instant.EPOCH),
        finishedAt = s.finishedAt.getOrElse(Instant.EPOCH),
        enqueuedAt = s.enqueuedAt.getOrElse(Instant.EPOCH)
      ))
    }
    s.status match {
      case TaskRunStatus.Running => UiTaskState.Unfinished(s.startedAt.getOrElse(Instant.EPOCH))
      case TaskRunStatus.Succeeded => done(TaskStatus.Succeeded)
      case TaskRunStatus.ValidationFailed =>
        done(TaskStatus.ValidationFailed(s.failedValidations.map(v => ValidationFailure(v.name, v.failedRowCount, ""))))
      case TaskRunStatus.Failed  => done(TaskStatus.Failed(s.errorMessage.getOrElse("(no error message)")))
      case TaskRunStatus.Skipped => done(TaskStatus.Skipped(s.errorMessage.getOrElse("(no reason)")))
    }
  }

  /** Mark a task as Queued — its deps are satisfied but no worker has picked it
    * up yet. Distinguishes "queued for too long" from "running for too long" in
    * the GUI. No-ops if no DAG is loaded or the index is bogus. */
  def markTaskQueued(taskIndex: Int): Unit = {
    if (taskIndex >= 0 && taskIndex < _taskStates.size) {
      _taskStates = _taskStates.updated(taskIndex, UiTaskState.Queued)
      notifyListeners()
    }
  }

  /** Mark a task as Running. No-ops if no DAG is loaded or the index is bogus. */
  def markTaskRunning(taskIndex: Int): Unit = {
    if (taskIndex >= 0 && taskIndex < _taskStates.size) {
      _taskStates = _taskStates.updated(taskIndex, UiTaskState.Running)
      notifyListeners()
    }
  }

  /** Mark an input as Loading. */
  def markInputLoading(inputIndex: Int): Unit = {
    if (inputIndex >= 0 && inputIndex < _inputStates.size) {
      _inputStates = _inputStates.updated(inputIndex, UiInputState.Loading)
      notifyListeners()
    }
  }

  /** Mark an input as Loaded or Failed. */
  def markInputFinished(inputIndex: Int, succeeded: Boolean, error: Option[String]): Unit = {
    if (inputIndex >= 0 && inputIndex < _inputStates.size) {
      val state = if (succeeded) UiInputState.Loaded
                  else UiInputState.Failed(error.getOrElse("load failed"))
      _inputStates = _inputStates.updated(inputIndex, state)
      notifyListeners()
    }
  }

  def markTaskFinished(taskIndex: Int, result: TaskResult): Unit = {
    if (taskIndex >= 0 && taskIndex < _taskStates.size) {
      _taskStates = _taskStates.updated(taskIndex, UiTaskState.Done(result))
      _outputPaths = _outputPaths.updated(taskIndex, result.outputPath)
      // Refresh the record so post-run UI reflects the freshest on-disk state.
      val freshRecord = result.outputPath.flatMap { p =>
        try TaskRunRecord.read(Paths.get(p)) catch { case NonFatal(_) => None }
      }
      _taskRecords = _taskRecords.updated(taskIndex, freshRecord)
      // Re-discover historical partitions so a brand-new run is immediately
      // visible in the picker dropdown.
      _dag.foreach { dag =>
        dag.nodes.lift(taskIndex).foreach { node =>
          if (node.task.outputFile.isDefined)
            _historicalRuns = _historicalRuns.updated(taskIndex, ownRecords(node.task))
        }
      }
      notifyListeners()
    }
  }

  def beginRun(): Unit = {
    _runState = RunState.Running
    _taskStates = _taskStates.map(_ => UiTaskState.Pending)
    _inputStates = _inputStates.map(_ => UiInputState.Pending)
    _outputPaths = _outputPaths.map(_ => None)
    _taskRecords = _taskRecords.map(_ => None)
    // What the last reopen could not read describes a run this one replaces.
    _summaryReasons = _summaryReasons.map(_ => None)
    _hydrationWarnings = Vector.empty
    // Keep historicalRuns intact — they're still on disk and useful in the
    // picker. They get refreshed after the run completes via markTaskFinished.
    notifyListeners()
  }

  def endRun(result: JobResult): Unit = {
    _runState = RunState.Done(result)
    // Snap selection back to "the just-completed run" — if the user was
    // inspecting a historical run before pressing Run, they probably want
    // to see THIS run's result now.
    _selectedRunDir = None
    // Refresh the job manifest + available runs so a brand-new templated
    // outputDir is visible in the picker immediately.
    _dataJob.foreach { job =>
      val vars = TemporalVariables(_executionTime)
      val defaultPath = job.jobRunOutput.flatMap { ofp =>
        try Some(Paths.get(TemplateRenderer.render(ofp.path, vars)))
        catch { case NonFatal(_) => None }
      }
      val defaultRunDir = defaultPath.flatMap(p => Option(p.getParent))
      _availableRuns = defaultRunDir.flatMap(d => Option(d.getParent)) match {
        case Some(parent) =>
          JobOutputLayout.detect(parent) match {
            case JobOutputLayout.MultiRun(runs) => runs.toVector
            case _ =>
              defaultPath.flatMap(p =>
                try JobRunRecord.read(p).map(rec => defaultRunDir.get -> rec)
                catch { case NonFatal(_) => None }
              ).toVector
          }
        case None => Vector.empty
      }
      _jobRecord = defaultPath.flatMap(p =>
        try JobRunRecord.read(p) catch { case NonFatal(_) => None }
      )
    }
    notifyListeners()
  }

  /** Switch the GUI's displayed run to a different historical run directory
    * — must be one listed in [[availableRuns]]. Re-runs the full hydration
    * so per-task records, validation samples, and the partition picker all
    * reflect the picked run. No-op when `dir` isn't in `availableRuns` or
    * no job is loaded. */
  def selectRun(dir: Path): Unit = {
    if (!_availableRuns.exists(_._1 == dir)) return
    if (_selectedRunDir.contains(dir)) return
    _selectedRunDir = Some(dir)
    for {
      dag <- _dag
      job <- _dataJob
    } hydrateRunRecords(dag, job)
    notifyListeners()
  }

  /** Set or clear the current selection. The DAG canvas calls this with either a
    * [[Selection.Task]] or a [[Selection.Input]] when the user clicks a node, or
    * `None` when they click empty space.
    */
  def select(sel: Option[Selection]): Unit = {
    if (_selection != sel) {
      _selection = sel
      notifyListeners()
    }
  }

  /** Shortcut used by callers (tests, helpers) that only care about task selection. */
  def selectTask(taskIndex: Option[Int]): Unit = select(taskIndex.map(Selection.Task(_)))

  /** True iff a Run is currently in flight. */
  def isRunning: Boolean = _runState == RunState.Running

  /** True iff we have a job loaded and it's safe to press Run. */
  def canRun: Boolean = _dataJob.isDefined && _dag.exists(_.nodes.nonEmpty) && !isRunning

  /** Build a fresh [[Catalog]] for interactive (ad-hoc) SQL execution.
    *
    * Populates one view per:
    *   - input — resolved via [[InputResolver]] against its rendered path, so
    *     CSV options and parquet reads behave the same way they would for a
    *     real run.
    *   - task with a persisted output — most-recent `_SUCCESS`-marked partition
    *     (or the path captured during the last in-session run), read back from
    *     disk under the task's `viewName`. Tasks with no on-disk output are
    *     skipped.
    *
    * Failures resolving any single view are recorded in [[InteractiveCatalog.errors]]
    * rather than thrown — interactive SQL is best-effort and the user still
    * benefits from a partial catalog. View names match what the task DAG
    * scheduler would use, so the same queries the user writes in `.sql` files
    * work here.
    */
  def buildInteractiveCatalog(): InteractiveCatalog = {
    val cat = new Catalog
    val views = mutable.ArrayBuffer.empty[InteractiveViewSpec]
    val errors = mutable.ArrayBuffer.empty[String]
    val seen = mutable.Set.empty[String]

    _inputs.iterator.zipWithIndex.foreach { case (in, i) =>
      val rendered = _renderedInputPaths.lift(i).getOrElse(in.path)
      try {
        val view = InputResolver.resolve(in.copy(path = rendered))
        cat.register(in.viewName, view)
        seen += in.viewName.toLowerCase(Locale.ROOT)
        views += InteractiveViewSpec(
          name = in.viewName,
          kind = ViewKind.Input,
          path = Some(rendered),
          schema = view.schema.fieldNames
        )
      } catch {
        case NonFatal(e) =>
          errors += s"input '${in.viewName}': ${Option(e.getMessage).getOrElse(e.toString)}"
      }
    }

    _dag.foreach { dag =>
      dag.nodes.foreach { node =>
        val viewNameOpt = node.task.viewName
        val taskOutputPath: Option[String] = _outputPaths.lift(node.index).flatten
          .orElse(_historicalRuns.lift(node.index).flatMap(_.headOption).map(_._1.toString))
        (viewNameOpt, taskOutputPath) match {
          case (Some(name), Some(path)) if !seen.contains(name.toLowerCase(Locale.ROOT)) =>
            try {
              val view = JobSession.readOutputAsView(path)
              cat.replace(name, view)
              seen += name.toLowerCase(Locale.ROOT)
              views += InteractiveViewSpec(
                name = name,
                kind = ViewKind.Task,
                path = Some(path),
                schema = view.schema.fieldNames
              )
            } catch {
              case NonFatal(e) =>
                errors += s"task '${node.task.displayName}': ${Option(e.getMessage).getOrElse(e.toString)}"
            }
          case _ => ()
        }
      }
    }
    InteractiveCatalog(cat, views.toVector, errors.toVector)
  }
}

/** Static helpers — kept here to avoid sprawling another small file. */
object JobSession {

  /** Best-effort: resolve `dirString` to a Path even if the user typed something
    * weird. Returns None for empty/invalid input.
    */
  def parseDir(dirString: String): Option[Path] = {
    val trimmed = dirString.trim
    if (trimmed.isEmpty) None
    else try Some(Paths.get(trimmed)) catch { case NonFatal(_) => None }
  }

  /** Read an on-disk task output directory as a [[CatalogView]], as the
    * output preview reads it, so the interactive SQL catalog sees the same
    * data the preview tab does.
    *
    *   1. A directory with a `_run.json` reads as the tasks after it read it
    *      ([[TaskRunRecord.outputView]]): the part files its run wrote, in the
    *      record's format, a CSV with the writer's options and the result's
    *      column types.
    *   2. Otherwise the `.parquet` / `.csv` substring in the path decides
    *      (legacy single-file paths that pre-date the record, and ad-hoc paths
    *      the user types in).
    *
    * Without (1) the path-substring check is the only signal, and
    * `DirectoryJobLoader` deliberately writes parquet to ext-less directories
    * (e.g. `output/<view>/`) — so a plain extension check would route a
    * parquet directory to the CSV reader, which would then parse the raw
    * `PAR1`-magic bytes as a CSV header.
    */
  def readOutputAsView(path: String): CatalogView = {
    val p = try Paths.get(path) catch { case NonFatal(_) => null }
    val record = if (p != null && Files.isDirectory(p)) TaskRunRecord.read(p) else None
    record match {
      case Some(rec) => TaskRunRecord.outputView(p, rec)
      case None =>
        val lower = path.toLowerCase(Locale.ROOT)
        if (lower.endsWith(".parquet") || lower.contains(".parquet")) ParquetReader.fromPath(path)
        else CsvReader.fromPath(path, CsvOptions())
    }
  }
}

/** A view available to interactive SQL — either an input or a task's persisted
  * output. Surfaced in the SQL console UI so the user knows what they can
  * query.
  */
final case class InteractiveViewSpec(
    name: String,
    kind: ViewKind,
    path: Option[String],
    schema: Seq[String]
)

sealed trait ViewKind
object ViewKind {
  case object Input extends ViewKind
  case object Task  extends ViewKind
}

/** Result of [[JobSession.buildInteractiveCatalog]]. The catalog is what the
  * SQL engine actually executes against; `views` is the user-facing listing
  * and `errors` collects per-view resolution failures.
  */
final case class InteractiveCatalog(
    catalog: Catalog,
    views: Vector[InteractiveViewSpec],
    errors: Vector[String]
)
