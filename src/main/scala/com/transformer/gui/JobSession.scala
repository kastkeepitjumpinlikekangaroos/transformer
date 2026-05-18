package com.transformer.gui

import com.transformer.job.{DataJob, DirectoryJobLoader, JobResult, TaskDag, TaskResult, TaskStatus}
import com.transformer.temporal.TemporalVariables

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
  private var _layout: Option[DagLayout] = None
  private var _taskStates: Vector[UiTaskState] = Vector.empty
  private var _runState: RunState = RunState.Idle
  private var _selectedTaskIndex: Option[Int] = None
  // Per-task resolved output paths captured from JobResult so we can re-read after run.
  private var _outputPaths: Vector[Option[String]] = Vector.empty

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
  def layout: Option[DagLayout] = _layout
  def taskStates: Vector[UiTaskState] = _taskStates
  def runState: RunState = _runState
  def selectedTaskIndex: Option[Int] = _selectedTaskIndex
  def outputPathFor(taskIndex: Int): Option[String] = _outputPaths.lift(taskIndex).flatten

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
    _selectedTaskIndex = None
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
        _layout = None
        _taskStates = Vector.empty
        _outputPaths = Vector.empty
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
          _layout = Some(DagLayout.compute(dag))
          _taskStates = Vector.fill(dag.nodes.size)(UiTaskState.Pending)
          _outputPaths = Vector.fill(dag.nodes.size)(None)
          _runState = RunState.Idle
        } catch {
          case NonFatal(e) =>
            _dataJob = None
            _dag = None
            _layout = None
            _taskStates = Vector.empty
            _outputPaths = Vector.empty
            _runState = RunState.LoadFailed(Option(e.getMessage).getOrElse(e.toString))
        }
    }
    notifyListeners()
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
      notifyListeners()
    }
  }

  def beginRun(): Unit = {
    _runState = RunState.Running
    _taskStates = _taskStates.map(_ => UiTaskState.Pending)
    _outputPaths = _outputPaths.map(_ => None)
    notifyListeners()
  }

  def endRun(result: JobResult): Unit = {
    _runState = RunState.Done(result)
    notifyListeners()
  }

  def select(taskIndex: Option[Int]): Unit = {
    if (_selectedTaskIndex != taskIndex) {
      _selectedTaskIndex = taskIndex
      notifyListeners()
    }
  }

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
