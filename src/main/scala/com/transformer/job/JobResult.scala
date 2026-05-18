package com.transformer.job

import java.time.Instant

sealed trait TaskStatus
object TaskStatus {
  case object Pending extends TaskStatus
  case object Succeeded extends TaskStatus
  final case class Failed(reason: String) extends TaskStatus
  final case class ValidationFailed(failures: Seq[ValidationFailure]) extends TaskStatus
  final case class Skipped(reason: String) extends TaskStatus
}

final case class ValidationFailure(
    validationName: String,
    rowCount: Long,
    sampleRowsCsv: String
)

final case class TaskResult(
    taskName: String,
    status: TaskStatus,
    rowsProduced: Long,
    outputPath: Option[String],
    startedAt: Instant,
    finishedAt: Instant
) {
  def durationMillis: Long = finishedAt.toEpochMilli - startedAt.toEpochMilli
  def succeeded: Boolean = status == TaskStatus.Succeeded
}

/** Final outcome of a [[DataJob.run]] call.
  *
  * `warnings` carries non-fatal consistency findings — disk state that
  * disagrees with the manifest (e.g. a declared part file that's missing, a
  * `runFile` reference that no longer exists). They don't fail the run on
  * their own; the GUI's run-log panel surfaces them so the user notices
  * drift before acting on the data.
  */
final case class JobResult(
    succeeded: Boolean,
    tasks: Seq[TaskResult],
    error: Option[String] = None,
    warnings: Seq[String] = Nil
)
