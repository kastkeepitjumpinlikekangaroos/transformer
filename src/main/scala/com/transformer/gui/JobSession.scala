package com.transformer.gui

import com.transformer.job.{DataJob, DirectoryJobLoader, InputFilePath, JobResult, RunMarker, TaskDag, TaskResult, TaskStatus}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}

import java.nio.file.{Path, Paths}
import java.time.Instant
import scala.collection.mutable
import scala.util.control.NonFatal

/** Per-task UI status. Distinct from [[TaskStatus]] because we need a 'Running'
  * state that the engine doesn't track (it goes straight from pending to a
  * terminal result).
  */
sealed trait UiTaskState
object UiTaskState {
  case object Pending extends UiTaskState
  case object Running extends UiTaskState
  final case class Done(result: TaskResult) extends UiTaskState
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
  private var _runState: RunState = RunState.Idle
  private var _selection: Option[Selection] = None
  // Per-task resolved output paths captured from JobResult so we can re-read after run.
  private var _outputPaths: Vector[Option[String]] = Vector.empty
  // Per-task RunMarkers loaded from disk on job-dir open. Populates UI state with
  // the previous run's results so the GUI is non-empty before the user presses Run.
  private var _markers: Vector[Option[RunMarker]] = Vector.empty
  // All historical runs discovered under each task's templated output path.
  // Sorted newest-first by writtenAt. Used by the GUI to render a partition
  // picker when a task has 2+ persisted runs.
  private var _historicalRuns: Vector[Seq[(Path, RunMarker)]] = Vector.empty

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
  def runState: RunState = _runState
  def selection: Option[Selection] = _selection
  /** Convenience: returns the selected task's index if a task is selected, else None. */
  def selectedTaskIndex: Option[Int] = _selection.collect { case Selection.Task(i) => i }
  /** Convenience: returns the selected input's index if an input is selected, else None. */
  def selectedInputIndex: Option[Int] = _selection.collect { case Selection.Input(i) => i }
  def outputPathFor(taskIndex: Int): Option[String] = _outputPaths.lift(taskIndex).flatten

  /** Cached `_SUCCESS` marker for this task, if any was discovered when the job
    * directory was opened (or after the most recent run). Used by the UI to show
    * "Loaded from previous run" provenance.
    */
  def markerFor(taskIndex: Int): Option[RunMarker] = _markers.lift(taskIndex).flatten

  /** All historical runs discovered under this task's templated output path,
    * newest-first. Surfaces a partition picker in the UI when 2+ runs exist.
    *
    * Each entry is `(partitionPath, marker)` — the path is the on-disk output
    * directory for that specific run, ready to feed to a reader.
    */
  def historicalRunsFor(taskIndex: Int): Seq[(Path, RunMarker)] =
    _historicalRuns.lift(taskIndex).getOrElse(Nil)

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
    reloadJob()
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
        _outputPaths = Vector.empty
        _markers = Vector.empty
        _historicalRuns = Vector.empty
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
          _outputPaths = Vector.fill(dag.nodes.size)(None)
          _markers = Vector.fill(dag.nodes.size)(None)
          _runState = RunState.Idle
          hydrateFromMarkers(dag, job)
        } catch {
          case NonFatal(e) =>
            _dataJob = None
            _dag = None
            _inputs = Vector.empty
            _renderedInputPaths = Vector.empty
            _layout = None
            _taskStates = Vector.empty
            _outputPaths = Vector.empty
            _markers = Vector.empty
            _historicalRuns = Vector.empty
            _runState = RunState.LoadFailed(Option(e.getMessage).getOrElse(e.toString))
        }
    }
    notifyListeners()
  }

  /** Discover `_SUCCESS` markers from previous runs and pre-populate per-task
    * UI state as Done so the canvas isn't an empty sea of grey on first open.
    *
    * Uses [[RunMarker.discover]] to walk the task's templated output pattern,
    * so partitioned outputs (e.g. `day={{today}}`) surface every historical
    * partition. The most-recent run hydrates UI state; the full list is kept
    * for the partition picker.
    */
  private def hydrateFromMarkers(dag: TaskDag, job: DataJob): Unit = {
    val states = Array.fill[UiTaskState](dag.nodes.size)(UiTaskState.Pending)
    val outputs = Array.fill[Option[String]](dag.nodes.size)(None)
    val markers = Array.fill[Option[RunMarker]](dag.nodes.size)(None)
    val historicals = Array.fill[Seq[(Path, RunMarker)]](dag.nodes.size)(Nil)
    var i = 0
    while (i < dag.nodes.size) {
      val node = dag.nodes(i)
      node.task.outputFile.foreach { ofp =>
        val runs = try RunMarker.discover(ofp.path) catch { case NonFatal(_) => Nil }
        historicals(i) = runs
        runs.headOption.foreach { case (path, m) =>
          markers(i) = Some(m)
          outputs(i) = Some(path.toString)
          states(i) = UiTaskState.Done(TaskResult(
            taskName = node.task.displayName,
            status = TaskStatus.Succeeded,
            rowsProduced = m.rowsProduced,
            outputPath = Some(path.toString),
            startedAt = m.writtenAt,
            finishedAt = m.writtenAt
          ))
        }
      }
      i += 1
    }
    _taskStates = states.toVector
    _outputPaths = outputs.toVector
    _markers = markers.toVector
    _historicalRuns = historicals.toVector
  }

  /** Mark a task as Running. No-ops if no DAG is loaded or the index is bogus. */
  def markTaskRunning(taskIndex: Int): Unit = {
    if (taskIndex >= 0 && taskIndex < _taskStates.size) {
      _taskStates = _taskStates.updated(taskIndex, UiTaskState.Running)
      notifyListeners()
    }
  }

  def markTaskFinished(taskIndex: Int, result: TaskResult): Unit = {
    if (taskIndex >= 0 && taskIndex < _taskStates.size) {
      _taskStates = _taskStates.updated(taskIndex, UiTaskState.Done(result))
      _outputPaths = _outputPaths.updated(taskIndex, result.outputPath)
      // Refresh the marker so post-run UI reflects the freshest on-disk state.
      val freshMarker = result.outputPath.flatMap { p =>
        try RunMarker.read(Paths.get(p)) catch { case NonFatal(_) => None }
      }
      _markers = _markers.updated(taskIndex, freshMarker)
      // Re-discover historical partitions so a brand-new run is immediately
      // visible in the picker dropdown.
      _dag.foreach { dag =>
        dag.nodes.lift(taskIndex).foreach { node =>
          node.task.outputFile.foreach { ofp =>
            val runs = try RunMarker.discover(ofp.path) catch { case NonFatal(_) => Nil }
            _historicalRuns = _historicalRuns.updated(taskIndex, runs)
          }
        }
      }
      notifyListeners()
    }
  }

  def beginRun(): Unit = {
    _runState = RunState.Running
    _taskStates = _taskStates.map(_ => UiTaskState.Pending)
    _outputPaths = _outputPaths.map(_ => None)
    _markers = _markers.map(_ => None)
    // Keep historicalRuns intact — they're still on disk and useful in the
    // picker. They get refreshed after the run completes via reloadHistoricals.
    notifyListeners()
  }

  def endRun(result: JobResult): Unit = {
    _runState = RunState.Done(result)
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
}
