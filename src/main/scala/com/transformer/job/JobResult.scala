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

/** Outcome of one [[SQLTask]] run.
  *
  * Time fields are recorded by the runner to let the GUI distinguish "queued
  * waiting for a worker" from "actually running":
  *
  *   - [[enqueuedAt]]  — when the task became eligible to run (i.e. when all
  *     of its task-deps AND input-deps had completed). Recorded by the
  *     scheduler before handing the task off to a worker thread.
  *   - [[startedAt]]   — when a worker thread actually began executing the
  *     task body, after waiting in the [[com.transformer.core.Scheduler]]
  *     queue.
  *   - [[finishedAt]]  — when the task body returned.
  *
  * For Skipped tasks (upstream failure) all three timestamps coincide — no
  * work was done. For other terminal statuses,
  * `queueWaitMillis = startedAt - enqueuedAt` measures scheduler pressure and
  * `durationMillis = finishedAt - startedAt` measures useful CPU/IO work. The
  * GUI surfaces both so users don't conflate "ran fast" with "made progress
  * promptly". Defaulting `enqueuedAt` to [[Instant.EPOCH]] keeps older
  * call-sites that build a `TaskResult` with five timestamps source-compatible.
  */
final case class TaskResult(
    taskName: String,
    status: TaskStatus,
    rowsProduced: Long,
    outputPath: Option[String],
    startedAt: Instant,
    finishedAt: Instant,
    enqueuedAt: Instant = Instant.EPOCH
) {
  def durationMillis: Long = finishedAt.toEpochMilli - startedAt.toEpochMilli
  /** Time spent waiting in the scheduler queue before a worker picked the task
    * up. 0 when `enqueuedAt == startedAt` (ran immediately on enqueue) or when
    * the field wasn't populated by an older code path.
    */
  def queueWaitMillis: Long = {
    val w = startedAt.toEpochMilli - enqueuedAt.toEpochMilli
    if (w < 0L) 0L else w
  }
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
