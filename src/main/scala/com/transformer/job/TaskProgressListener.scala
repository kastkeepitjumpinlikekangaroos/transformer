package com.transformer.job

/** Streaming callbacks fired by [[DataJob.run]] as tasks transition through the DAG.
  *
  * Callbacks fire from the runner's worker threads. UI surfaces (JavaFX, Swing,
  * etc.) must marshal back to their own thread inside the listener — the runner
  * does not do this on their behalf. Callbacks are best-effort: any throwable
  * raised by a listener is swallowed so a misbehaving observer cannot break the
  * scheduler.
  *
  * Skipped tasks (upstream failure) do NOT fire `onTaskStart` — they receive a
  * single `onTaskFinish` with a [[TaskStatus.Skipped]] result.
  */
trait TaskProgressListener {
  def onTaskStart(taskIndex: Int, taskName: String): Unit
  def onTaskFinish(taskIndex: Int, result: TaskResult): Unit
}

object TaskProgressListener {
  val NoOp: TaskProgressListener = new TaskProgressListener {
    def onTaskStart(taskIndex: Int, taskName: String): Unit = ()
    def onTaskFinish(taskIndex: Int, result: TaskResult): Unit = ()
  }
}
