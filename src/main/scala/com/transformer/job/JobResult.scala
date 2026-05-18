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

final case class JobResult(
    succeeded: Boolean,
    tasks: Seq[TaskResult],
    error: Option[String] = None
)
