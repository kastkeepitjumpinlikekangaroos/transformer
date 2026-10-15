package com.transformer.job

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant
import scala.util.control.NonFatal

/** Job-level run record written at the end of every [[DataJob.run]] when a
  * `jobRunOutput` is configured. Acts as the GUI's single entry point on
  * reload: load this file first, then follow each task's [[JobTaskSummary.runFile]]
  * pointer to its per-task [[TaskRunRecord]].
  *
  * Reruns of the same job (same `jobRunOutput.path`) overwrite this file in
  * place via atomic temp-then-rename. The corresponding `_run.json` files in
  * each task's output directory are independently versioned by the task's
  * templated output path, so partitioned history is preserved per task.
  *
  * The [[warnings]] field surfaces consistency-check findings: a task's
  * `runFile` is missing from disk, a task claims data files that aren't
  * there, etc. Surfaced in the GUI's run-log panel so the user notices
  * drift between the manifest and the filesystem.
  *
  * The [[perfManifest]] field lists the per-task `_perf.json` paths emitted
  * by this run when metrics were enabled. `None` when metrics were disabled
  * on every task — keeps the default `job.json` shape unchanged for unmetered
  * runs. The macro bench runner reads this list to find every `_perf.json` in
  * one query without re-walking the output tree.
  */
final case class JobRunRecord(
    schemaVersion: Int,
    succeeded: Boolean,
    errorMessage: Option[String],
    executionTime: Instant,
    startedAt: Instant,
    finishedAt: Instant,
    tasks: Seq[JobTaskSummary],
    warnings: Seq[String],
    perfManifest: Option[Seq[String]] = None
)

/** Compact per-task entry in [[JobRunRecord.tasks]]. `runFile` is `Some(path)`
  * when the task has an `outputFile` (the full [[TaskRunRecord]] lives in
  * that directory); otherwise it is None.
  *
  * The entry holds everything about the task but the sample rows of its
  * failed validations, which live beside its `_run.json`: so a task whose
  * record is missing, or that has none, reopens from the entry alone. The
  * timestamps and `failedValidations` are absent from a `job.json` written
  * before entries carried them, which reads with them as None and empty. */
final case class JobTaskSummary(
    taskName: String,
    status: TaskRunStatus,
    runFile: Option[String],
    rowsProduced: Long,
    failedValidationCount: Int,
    errorMessage: Option[String],
    startedAt: Option[Instant] = None,
    finishedAt: Option[Instant] = None,
    enqueuedAt: Option[Instant] = None,
    failedValidations: Seq[FailedValidation] = Nil
)

/** A validation a task failed, as `job.json` records it: its name and the rows
  * it returned. */
final case class FailedValidation(name: String, failedRowCount: Long)

object JobRunRecord {

  val SchemaVersion: Int = 1

  /** Atomic write of `record` to `file`. Creates the parent directory if it
    * doesn't already exist. */
  def write(file: Path, record: JobRunRecord): Unit = {
    val parent = file.getParent
    if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent)
    val text = serialize(record)
    val tmp = file.resolveSibling(file.getFileName.toString + ".tmp")
    Files.writeString(tmp, text)
    try {
      Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /** Read `file`. Returns None for missing or malformed records — callers
    * treat both as "no cached job", same as [[TaskRunRecord.read]]. */
  def read(file: Path): Option[JobRunRecord] = {
    if (!Files.isRegularFile(file)) return None
    try {
      val ctx = s"job.json at $file"
      val obj = Json.parse(Files.readString(file)).asObject(ctx)
      val tasks = obj.get("tasks") match {
        case Some(JsonArray(items)) =>
          items.iterator.zipWithIndex.map { case (item, i) =>
            val tctx = s"$ctx: tasks[$i]"
            val o = item.asObject(tctx)
            val statusStr = o.requiredString("status", tctx)
            JobTaskSummary(
              taskName = o.requiredString("taskName", tctx),
              status = TaskRunStatus.fromJson(statusStr).getOrElse(
                throw new IllegalArgumentException(s"$tctx: unknown status '$statusStr'")),
              runFile = o.optString("runFile", tctx),
              rowsProduced = o.get("rowsProduced").map(_.stringValue.toLong).getOrElse(0L),
              failedValidationCount =
                o.get("failedValidationCount").map(_.stringValue.toInt).getOrElse(0),
              errorMessage = o.optString("errorMessage", tctx),
              startedAt = o.optString("startedAt", tctx).map(Instant.parse),
              finishedAt = o.optString("finishedAt", tctx).map(Instant.parse),
              enqueuedAt = o.optString("enqueuedAt", tctx).map(Instant.parse),
              failedValidations = o.get("failedValidations") match {
                case Some(JsonArray(vs)) =>
                  vs.iterator.zipWithIndex.map { case (v, j) =>
                    val vctx = s"$tctx: failedValidations[$j]"
                    val vo = v.asObject(vctx)
                    FailedValidation(
                      name = vo.requiredString("name", vctx),
                      failedRowCount = vo.get("failedRowCount").map(_.stringValue.toLong).getOrElse(0L))
                  }.toVector
                case _ => Vector.empty
              }
            )
          }.toVector
        case _ => Vector.empty
      }
      val warnings = obj.get("warnings") match {
        case Some(JsonArray(items)) => items.iterator.map(_.stringValue).toVector
        case _                       => Vector.empty
      }
      val perfManifest = obj.get("perfManifest") match {
        case Some(JsonArray(items)) => Some(items.iterator.map(_.stringValue).toVector: Seq[String])
        case Some(JsonNull)         => None
        case _                       => None
      }
      Some(JobRunRecord(
        schemaVersion = obj.get("schemaVersion").map(_.stringValue.toInt).getOrElse(SchemaVersion),
        succeeded = obj.optBool("succeeded", ctx).getOrElse(false),
        errorMessage = obj.optString("errorMessage", ctx),
        executionTime = Instant.parse(obj.requiredString("executionTime", ctx)),
        startedAt = Instant.parse(obj.requiredString("startedAt", ctx)),
        finishedAt = Instant.parse(obj.requiredString("finishedAt", ctx)),
        tasks = tasks,
        warnings = warnings,
        perfManifest = perfManifest
      ))
    } catch {
      case NonFatal(_) => None
    }
  }

  private def serialize(r: JobRunRecord): String = {
    val sb = new java.lang.StringBuilder()
    sb.append("{\n")
    sb.append("  \"schemaVersion\": ").append(r.schemaVersion).append(",\n")
    sb.append("  \"succeeded\": ").append(r.succeeded).append(",\n")
    r.errorMessage match {
      case Some(msg) => sb.append("  \"errorMessage\": \"").append(Json.escape(msg)).append("\",\n")
      case None      => sb.append("  \"errorMessage\": null,\n")
    }
    sb.append("  \"executionTime\": \"").append(Json.escape(r.executionTime.toString)).append("\",\n")
    sb.append("  \"startedAt\": \"").append(Json.escape(r.startedAt.toString)).append("\",\n")
    sb.append("  \"finishedAt\": \"").append(Json.escape(r.finishedAt.toString)).append("\",\n")
    sb.append("  \"tasks\": [")
    if (r.tasks.nonEmpty) {
      sb.append('\n')
      var i = 0
      while (i < r.tasks.size) {
        val t = r.tasks(i)
        sb.append("    {")
        sb.append("\"taskName\": \"").append(Json.escape(t.taskName)).append("\", ")
        sb.append("\"status\": \"").append(Json.escape(TaskRunStatus.toJson(t.status))).append("\", ")
        t.runFile match {
          case Some(f) => sb.append("\"runFile\": \"").append(Json.escape(f)).append("\", ")
          case None    => sb.append("\"runFile\": null, ")
        }
        sb.append("\"rowsProduced\": ").append(t.rowsProduced).append(", ")
        sb.append("\"failedValidationCount\": ").append(t.failedValidationCount).append(", ")
        t.errorMessage match {
          case Some(msg) => sb.append("\"errorMessage\": \"").append(Json.escape(msg)).append("\"")
          case None      => sb.append("\"errorMessage\": null")
        }
        def instant(key: String, at: Option[Instant]): Unit = at.foreach { i =>
          sb.append(", \"").append(key).append("\": \"").append(Json.escape(i.toString)).append('"')
        }
        instant("startedAt", t.startedAt)
        instant("finishedAt", t.finishedAt)
        instant("enqueuedAt", t.enqueuedAt)
        sb.append(", \"failedValidations\": [")
        var v = 0
        while (v < t.failedValidations.size) {
          val fv = t.failedValidations(v)
          if (v > 0) sb.append(", ")
          sb.append("{\"name\": \"").append(Json.escape(fv.name)).append("\", ")
          sb.append("\"failedRowCount\": ").append(fv.failedRowCount).append('}')
          v += 1
        }
        sb.append(']')
        sb.append('}')
        if (i < r.tasks.size - 1) sb.append(',')
        sb.append('\n')
        i += 1
      }
      sb.append("  ")
    }
    sb.append("],\n")
    sb.append("  \"warnings\": [")
    if (r.warnings.nonEmpty) {
      sb.append('\n')
      var j = 0
      while (j < r.warnings.size) {
        sb.append("    \"").append(Json.escape(r.warnings(j))).append('"')
        if (j < r.warnings.size - 1) sb.append(',')
        sb.append('\n')
        j += 1
      }
      sb.append("  ")
    }
    sb.append("]")
    r.perfManifest match {
      case Some(paths) =>
        sb.append(",\n  \"perfManifest\": [")
        if (paths.nonEmpty) {
          sb.append('\n')
          var k = 0
          val arr = paths.toIndexedSeq
          while (k < arr.size) {
            sb.append("    \"").append(Json.escape(arr(k))).append('"')
            if (k < arr.size - 1) sb.append(',')
            sb.append('\n')
            k += 1
          }
          sb.append("  ")
        }
        sb.append("]")
      case None => ()
    }
    sb.append('\n').append("}\n")
    sb.toString
  }
}
