package com.transformer.job

/** Streaming callbacks fired by [[DataJob.run]] as tasks transition through the
  * unified input + task DAG.
  *
  * Callbacks fire from the runner's worker threads. UI surfaces (JavaFX, Swing,
  * etc.) must marshal back to their own thread inside the listener — the
  * runner does not do this on their behalf. Callbacks are best-effort: any
  * throwable raised by a listener is swallowed so a misbehaving observer
  * cannot break the scheduler.
  *
  * Per-task lifecycle:
  *   - `onTaskEnqueued` fires the moment a task becomes eligible (all input +
  *     task deps complete) — *before* it waits in the scheduler queue for a
  *     worker. Pairing this with `onTaskStart` lets the UI distinguish "queued"
  *     from "running".
  *   - `onTaskStart` fires when a worker has actually picked the task up.
  *   - `onTaskFinish` fires on terminal status (Succeeded, ValidationFailed,
  *     Failed, Skipped).
  *
  * Skipped tasks (upstream failure) do NOT fire `onTaskEnqueued` or
  * `onTaskStart` — they receive a single `onTaskFinish` with a
  * [[TaskStatus.Skipped]] result.
  *
  * Per-input lifecycle:
  *   - `onInputStart` fires when the input-load callable begins running.
  *   - `onInputFinish` fires when the catalog view has been resolved (and
  *     materialized, if `cache = true`) — or when loading failed.
  *
  * Defaults are no-ops so old implementations don't need to touch input
  * callbacks. */
trait TaskProgressListener {
  def onTaskEnqueued(taskIndex: Int, taskName: String): Unit = ()
  def onTaskStart(taskIndex: Int, taskName: String): Unit
  def onTaskFinish(taskIndex: Int, result: TaskResult): Unit

  /** Fired when the runner begins loading input `inputIndex`. Inputs are
    * indexed by their position in `DataJob.inputs`. */
  def onInputStart(inputIndex: Int, viewName: String): Unit = ()

  /** Fired when input `inputIndex` finishes — succeeded or failed.
    * `succeeded = false` means the catalog never received this view and every
    * SQL task that referenced it will be marked Skipped. */
  def onInputFinish(inputIndex: Int, viewName: String, succeeded: Boolean, errorMessage: Option[String]): Unit = ()
}

object TaskProgressListener {
  val NoOp: TaskProgressListener = new TaskProgressListener {
    def onTaskStart(taskIndex: Int, taskName: String): Unit = ()
    def onTaskFinish(taskIndex: Int, result: TaskResult): Unit = ()
  }
}
