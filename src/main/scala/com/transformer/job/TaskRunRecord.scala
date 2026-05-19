package com.transformer.job

import java.nio.file.{FileSystems, Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters._

/** Per-task run record written into each task's output directory at the end
  * of every run attempt — Succeeded, ValidationFailed, Failed, or Skipped.
  * Reruns of the same execution-time + output path completely overwrite it.
  *
  * File name: `_run.json` (the `_` prefix means
  * [[com.transformer.read.csv.PathGlob]] and friends skip it when re-reading
  * the directory as data). Written atomically via temp-then-rename.
  *
  * Companion files in the same directory:
  *   * `_validation-<slug>.csv` — failure samples for each failed validation,
  *     pointed at by [[ValidationRecord.sampleFile]]. Only present when a
  *     validation has [[ValidationRecord.passed]] = false.
  *   * `part-NNNNN.<ext>` — the actual data files, named in
  *     [[outputFiles]]. Present for Succeeded and ValidationFailed records;
  *     empty for Failed/Skipped tasks.
  *
  * On-disk JSON is hand-rolled (same approach as [[Json]] elsewhere in this
  * module) so the schema lives in one place and we don't take an external
  * dep just for serialization.
  */
final case class TaskRunRecord(
    schemaVersion: Int,
    taskName: String,
    status: TaskRunStatus,
    errorMessage: Option[String],
    executionTime: Instant,
    startedAt: Instant,
    finishedAt: Instant,
    writtenAt: Instant,
    rowsProduced: Long,
    format: String,
    outputFiles: Seq[String],
    validations: Seq[ValidationRecord],
    enqueuedAt: Option[Instant] = None
) {
  /** True when the recorded status implies on-disk part files (and so a
    * missing one is a consistency warning). */
  def expectsDataFiles: Boolean = status match {
    case TaskRunStatus.Succeeded | TaskRunStatus.ValidationFailed => outputFiles.nonEmpty
    case _                                                        => false
  }
}

/** Flat status discriminator persisted as a string in `_run.json`. The runner
  * uses the richer in-memory [[TaskStatus]] for control flow; this enum is
  * just the serialized projection. */
sealed trait TaskRunStatus
object TaskRunStatus {
  case object Succeeded extends TaskRunStatus
  case object ValidationFailed extends TaskRunStatus
  case object Failed extends TaskRunStatus
  case object Skipped extends TaskRunStatus

  def fromJson(s: String): Option[TaskRunStatus] = s match {
    case "Succeeded"        => Some(Succeeded)
    case "ValidationFailed" => Some(ValidationFailed)
    case "Failed"           => Some(Failed)
    case "Skipped"          => Some(Skipped)
    case _                  => None
  }

  def toJson(s: TaskRunStatus): String = s match {
    case Succeeded        => "Succeeded"
    case ValidationFailed => "ValidationFailed"
    case Failed           => "Failed"
    case Skipped          => "Skipped"
  }
}

/** One entry per validation declared on the task. `sampleFile` points at a
  * sibling `_validation-<slug>.csv` when `passed = false`, and is None
  * otherwise. */
final case class ValidationRecord(
    name: String,
    passed: Boolean,
    failedRowCount: Long,
    sampleFile: Option[String]
)

object TaskRunRecord {

  val FileName: String = "_run.json"
  val SchemaVersion: Int = 1
  val ValidationSamplePrefix: String = "_validation-"

  /** Atomic write of `record` to `<dir>/_run.json`. Creates `dir` if needed
    * — Failed/Skipped tasks may not have produced their output dir yet but
    * still need a record on disk. */
  def write(dir: Path, record: TaskRunRecord): Unit = {
    if (!Files.isDirectory(dir)) Files.createDirectories(dir)
    val text = serialize(record)
    val target = dir.resolve(FileName)
    val tmp = dir.resolve(FileName + ".tmp")
    Files.writeString(tmp, text)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /** Read `<dir>/_run.json`. Returns None for missing or malformed records —
    * callers treat both as "no cached run", same as the GUI's hydration. */
  def read(dir: Path): Option[TaskRunRecord] = {
    val p = dir.resolve(FileName)
    if (!Files.isRegularFile(p)) return None
    try {
      val ctx = s"_run.json at $p"
      val obj = Json.parse(Files.readString(p)).asObject(ctx)
      val statusStr = obj.requiredString("status", ctx)
      val status = TaskRunStatus.fromJson(statusStr).getOrElse(
        throw new IllegalArgumentException(s"$ctx: unknown status '$statusStr'"))
      val outputFiles = obj.get("outputFiles") match {
        case Some(JsonArray(items)) => items.iterator.map(_.stringValue).toVector
        case _                       => Vector.empty
      }
      val validations = obj.get("validations") match {
        case Some(JsonArray(items)) =>
          items.iterator.zipWithIndex.map { case (item, i) =>
            val vctx = s"$ctx: validations[$i]"
            val o = item.asObject(vctx)
            ValidationRecord(
              name = o.requiredString("name", vctx),
              passed = o.optBool("passed", vctx).getOrElse(true),
              failedRowCount = o.get("failedRowCount").map(_.stringValue.toLong).getOrElse(0L),
              sampleFile = o.optString("sampleFile", vctx)
            )
          }.toVector
        case _ => Vector.empty
      }
      Some(TaskRunRecord(
        schemaVersion = obj.get("schemaVersion").map(_.stringValue.toInt).getOrElse(SchemaVersion),
        taskName = obj.requiredString("taskName", ctx),
        status = status,
        errorMessage = obj.optString("errorMessage", ctx),
        executionTime = Instant.parse(obj.requiredString("executionTime", ctx)),
        startedAt = Instant.parse(obj.requiredString("startedAt", ctx)),
        finishedAt = Instant.parse(obj.requiredString("finishedAt", ctx)),
        writtenAt = Instant.parse(obj.requiredString("writtenAt", ctx)),
        rowsProduced = obj.get("rowsProduced").map(_.stringValue.toLong).getOrElse(0L),
        format = obj.optString("format", ctx).getOrElse("csv"),
        outputFiles = outputFiles,
        validations = validations,
        enqueuedAt = obj.optString("enqueuedAt", ctx).map(Instant.parse)
      ))
    } catch {
      case NonFatal(_) => None
    }
  }

  /** Walk `templatedPattern` (templated path with `{{ ... }}` placeholders),
    * returning every dir that holds a `_run.json` — newest first by
    * `writtenAt`. Used by the GUI's partition picker to surface historical
    * runs of the same task across different execution times.
    *
    * Each `{{...}}` is replaced with a glob wildcard matching one path segment,
    * so an output path like `out/day={{today}}/customers` discovers every
    * historical `out/day=YYYYMMDD/customers` partition on disk.
    */
  def discover(templatedPattern: String): Seq[(Path, TaskRunRecord)] = {
    val glob = templatedPattern.replaceAll("\\{\\{[^}]*\\}\\}", "*")
    val (rootStr, _) = splitAtFirstWildcard(glob)
    val root = Paths.get(rootStr).toAbsolutePath.normalize()
    if (!Files.isDirectory(root)) return Nil

    val absoluteGlob = Paths.get(glob).toAbsolutePath.normalize().toString
    val matcher = FileSystems.getDefault.getPathMatcher(s"glob:$absoluteGlob")

    val results = mutable.ArrayBuffer.empty[(Path, TaskRunRecord)]
    val maxDepth = math.max(1, segmentCount(absoluteGlob) - segmentCount(root.toString) + 1)
    val stream = Files.walk(root, maxDepth)
    try {
      stream.iterator().asScala.foreach { p =>
        if (Files.isDirectory(p) && matcher.matches(p)) {
          read(p).foreach(r => results += p -> r)
        }
      }
    } finally stream.close()

    results.sortBy(-_._2.writtenAt.toEpochMilli).toVector
  }

  /** If `dir` contains a `_run.json`, delete every regular file at the top
    * level of `dir` (the record, validation samples, part files) and return
    * true. The record file is deleted first so an interrupted clear leaves a
    * directory that no longer claims a coherent run.
    *
    * Subdirectories are deliberately left alone — our output layout is flat,
    * so a nested directory is not "ours" to wipe.
    */
  def clearIfMarked(dir: Path): Boolean = {
    if (!Files.isDirectory(dir)) return false
    val markerPath = dir.resolve(FileName)
    if (!Files.isRegularFile(markerPath)) return false
    Files.deleteIfExists(markerPath)
    val stream = Files.list(dir)
    try {
      val it = stream.iterator()
      while (it.hasNext) {
        val p = it.next()
        if (Files.isRegularFile(p)) Files.deleteIfExists(p)
      }
    } finally stream.close()
    true
  }

  /** Enumerate bare `part-*` file names in `dir`. */
  def listPartFiles(dir: Path): Seq[String] = {
    if (!Files.isDirectory(dir)) return Nil
    val stream = Files.list(dir)
    try {
      val out = mutable.ArrayBuffer.empty[String]
      val it = stream.iterator()
      while (it.hasNext) {
        val p = it.next()
        val n = p.getFileName.toString
        if (Files.isRegularFile(p) && n.startsWith("part-")) out += n
      }
      out.sortInPlace()
      out.toVector
    } finally stream.close()
  }

  /** Build the sibling-file name for a validation failure sample. Lowercases
    * the validation name, replaces non-`[a-z0-9_-]` runs with `_`, trims
    * leading/trailing underscores. Falls back to `validation` for empty
    * results. */
  def validationSampleFileName(validationName: String): String = {
    val slug = validationName
      .toLowerCase
      .replaceAll("[^a-z0-9_-]+", "_")
      .replaceAll("^_+|_+$", "")
    s"$ValidationSamplePrefix${if (slug.isEmpty) "validation" else slug}.csv"
  }

  /** Write `csv` to `<dir>/_validation-<slug>.csv` atomically. Returns the
    * bare file name so the caller can record it in
    * [[ValidationRecord.sampleFile]]. */
  def writeValidationSample(dir: Path, validationName: String, csv: String): String = {
    if (!Files.isDirectory(dir)) Files.createDirectories(dir)
    val name = validationSampleFileName(validationName)
    val target = dir.resolve(name)
    val tmp = dir.resolve(name + ".tmp")
    Files.writeString(tmp, csv)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
    name
  }

  /** Read a validation sample CSV by bare file name. None if missing. */
  def readValidationSample(dir: Path, sampleFile: String): Option[String] = {
    val p = dir.resolve(sampleFile)
    if (!Files.isRegularFile(p)) None
    else try Some(Files.readString(p)) catch { case NonFatal(_) => None }
  }

  // ---- private helpers ------------------------------------------------------

  private def splitAtFirstWildcard(pattern: String): (String, String) = {
    val star = pattern.indexOf('*')
    if (star < 0) return (pattern, "")
    val before = pattern.substring(0, star)
    val lastSep = math.max(before.lastIndexOf('/'), before.lastIndexOf(java.io.File.separatorChar))
    if (lastSep < 0) (".", pattern)
    else (before.substring(0, lastSep), pattern.substring(lastSep + 1))
  }

  private def segmentCount(p: String): Int =
    p.split("[/\\\\]").count(_.nonEmpty)

  private def serialize(r: TaskRunRecord): String = {
    val sb = new java.lang.StringBuilder()
    sb.append("{\n")
    sb.append("  \"schemaVersion\": ").append(r.schemaVersion).append(",\n")
    sb.append("  \"taskName\": \"").append(escape(r.taskName)).append("\",\n")
    sb.append("  \"status\": \"").append(escape(TaskRunStatus.toJson(r.status))).append("\",\n")
    r.errorMessage match {
      case Some(msg) => sb.append("  \"errorMessage\": \"").append(escape(msg)).append("\",\n")
      case None      => sb.append("  \"errorMessage\": null,\n")
    }
    sb.append("  \"executionTime\": \"").append(escape(r.executionTime.toString)).append("\",\n")
    r.enqueuedAt.foreach { ea =>
      sb.append("  \"enqueuedAt\": \"").append(escape(ea.toString)).append("\",\n")
    }
    sb.append("  \"startedAt\": \"").append(escape(r.startedAt.toString)).append("\",\n")
    sb.append("  \"finishedAt\": \"").append(escape(r.finishedAt.toString)).append("\",\n")
    sb.append("  \"writtenAt\": \"").append(escape(r.writtenAt.toString)).append("\",\n")
    sb.append("  \"rowsProduced\": ").append(r.rowsProduced).append(",\n")
    sb.append("  \"format\": \"").append(escape(r.format)).append("\",\n")
    sb.append("  \"outputFiles\": [")
    var i = 0
    while (i < r.outputFiles.size) {
      if (i > 0) sb.append(", ")
      sb.append('"').append(escape(r.outputFiles(i))).append('"')
      i += 1
    }
    sb.append("],\n")
    sb.append("  \"validations\": [")
    if (r.validations.nonEmpty) {
      sb.append('\n')
      var j = 0
      while (j < r.validations.size) {
        val v = r.validations(j)
        sb.append("    {")
        sb.append("\"name\": \"").append(escape(v.name)).append("\", ")
        sb.append("\"passed\": ").append(v.passed).append(", ")
        sb.append("\"failedRowCount\": ").append(v.failedRowCount).append(", ")
        v.sampleFile match {
          case Some(f) => sb.append("\"sampleFile\": \"").append(escape(f)).append("\"")
          case None    => sb.append("\"sampleFile\": null")
        }
        sb.append('}')
        if (j < r.validations.size - 1) sb.append(',')
        sb.append('\n')
        j += 1
      }
      sb.append("  ")
    }
    sb.append("]\n")
    sb.append("}\n")
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
