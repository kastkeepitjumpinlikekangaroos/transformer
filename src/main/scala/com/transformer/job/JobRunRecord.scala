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
  * by this run when metrics were enabled (Sub-plan 1 of the instrumentation
  * work). `None` when metrics were disabled on every task — keeps the
  * default `job.json` shape unchanged for unmetered runs. The macro bench
  * runner (Sub-plan 4) reads this list to find every `_perf.json` in one
  * query without re-walking the output tree.
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
  * that directory); otherwise the summary embeds the minimal display fields
  * (status, rowsProduced, error message) and `runFile` is None. */
final case class JobTaskSummary(
    taskName: String,
    status: TaskRunStatus,
    runFile: Option[String],
    rowsProduced: Long,
    failedValidationCount: Int,
    errorMessage: Option[String]
)

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
              errorMessage = o.optString("errorMessage", tctx)
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
      case Some(msg) => sb.append("  \"errorMessage\": \"").append(escape(msg)).append("\",\n")
      case None      => sb.append("  \"errorMessage\": null,\n")
    }
    sb.append("  \"executionTime\": \"").append(escape(r.executionTime.toString)).append("\",\n")
    sb.append("  \"startedAt\": \"").append(escape(r.startedAt.toString)).append("\",\n")
    sb.append("  \"finishedAt\": \"").append(escape(r.finishedAt.toString)).append("\",\n")
    sb.append("  \"tasks\": [")
    if (r.tasks.nonEmpty) {
      sb.append('\n')
      var i = 0
      while (i < r.tasks.size) {
        val t = r.tasks(i)
        sb.append("    {")
        sb.append("\"taskName\": \"").append(escape(t.taskName)).append("\", ")
        sb.append("\"status\": \"").append(escape(TaskRunStatus.toJson(t.status))).append("\", ")
        t.runFile match {
          case Some(f) => sb.append("\"runFile\": \"").append(escape(f)).append("\", ")
          case None    => sb.append("\"runFile\": null, ")
        }
        sb.append("\"rowsProduced\": ").append(t.rowsProduced).append(", ")
        sb.append("\"failedValidationCount\": ").append(t.failedValidationCount).append(", ")
        t.errorMessage match {
          case Some(msg) => sb.append("\"errorMessage\": \"").append(escape(msg)).append("\"")
          case None      => sb.append("\"errorMessage\": null")
        }
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
        sb.append("    \"").append(escape(r.warnings(j))).append('"')
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
            sb.append("    \"").append(escape(arr(k))).append('"')
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

  private def escape(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c             => sb.append(c)
      }
      i += 1
    }
    sb.toString
  }
}
