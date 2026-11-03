package com.transformer.job

import com.transformer.core.{CatalogView, DataType, Faults, Field, MaterializedView, Schema}
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.read.parquet.ParquetReader

import java.nio.file.{FileSystems, Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.Locale
import scala.collection.mutable
import scala.util.control.NonFatal
import scala.jdk.CollectionConverters._

/** Per-task run record in each task's output directory: `Running` while a run
  * writes the directory, then what the run came to — Succeeded,
  * ValidationFailed, Failed or Skipped. Each record replaces the one before it
  * in one rename, and no file a record names is deleted before the record is
  * replaced ([[OutputDirectory]] holds the order a run writes in).
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
  *     none for Failed and Skipped ones. Under a `Running` record the
  *     directory holds whatever the run had written when it stopped, or is
  *     writing still, and no reader reads it as an output.
  *
  * A `Running` record has no `finishedAt`: nothing finished. Every other
  * status has one.
  *
  * The record also says how to read the part files back as the run wrote
  * them, which [[TaskRunRecord.outputView]] does: the writer's `options` (a
  * CSV's delimiter, header and null string) and the result's `schema`, which
  * a CSV file cannot carry. `sql` is the task's main SQL with its templates
  * rendered: how the rows were made. A record from before these fields reads
  * with none of them, and its CSV output reads with inferred types.
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
    finishedAt: Option[Instant],
    writtenAt: Instant,
    rowsProduced: Long,
    format: String,
    outputFiles: Seq[String],
    validations: Seq[ValidationRecord],
    enqueuedAt: Option[Instant] = None,
    options: Map[String, String] = Map.empty,
    schema: Option[Schema] = None,
    sql: Option[String] = None
) {
  /** True when the recorded status implies on-disk part files (and so a
    * missing one is a consistency warning). */
  def expectsDataFiles: Boolean = TaskRunStatus.wroteOutput(status) && outputFiles.nonEmpty
}

/** Flat status discriminator persisted as a string in `_run.json`. The runner
  * uses the richer in-memory [[TaskStatus]] for control flow; this enum is
  * just the serialized projection. */
sealed trait TaskRunStatus
object TaskRunStatus {
  /** A run is writing the directory, or stopped before it finished: written
    * before the run deletes or writes any other file there, so whatever the
    * run leaves is the job's, and the next run clears it. Only a task's own
    * record holds it, never `job.json`. */
  case object Running extends TaskRunStatus
  case object Succeeded extends TaskRunStatus
  case object ValidationFailed extends TaskRunStatus
  case object Failed extends TaskRunStatus
  case object Skipped extends TaskRunStatus

  def fromJson(s: String): Option[TaskRunStatus] = s match {
    case "Running"          => Some(Running)
    case "Succeeded"        => Some(Succeeded)
    case "ValidationFailed" => Some(ValidationFailed)
    case "Failed"           => Some(Failed)
    case "Skipped"          => Some(Skipped)
    case _                  => None
  }

  def toJson(s: TaskRunStatus): String = s match {
    case Running          => "Running"
    case Succeeded        => "Succeeded"
    case ValidationFailed => "ValidationFailed"
    case Failed           => "Failed"
    case Skipped          => "Skipped"
  }

  /** Whether a record of status `s` names the output its run wrote: the run
    * finished writing, whatever its validations then said. */
  def wroteOutput(s: TaskRunStatus): Boolean = s match {
    case Succeeded | ValidationFailed  => true
    case Running | Failed | Skipped    => false
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
        // Nothing finished under a Running record; every other one says when.
        finishedAt =
          if (status == TaskRunStatus.Running) obj.optString("finishedAt", ctx).map(Instant.parse)
          else Some(Instant.parse(obj.requiredString("finishedAt", ctx))),
        writtenAt = Instant.parse(obj.requiredString("writtenAt", ctx)),
        rowsProduced = obj.get("rowsProduced").map(_.stringValue.toLong).getOrElse(0L),
        format = obj.optString("format", ctx).getOrElse("csv"),
        outputFiles = outputFiles,
        validations = validations,
        enqueuedAt = obj.optString("enqueuedAt", ctx).map(Instant.parse),
        options = obj.optStringMap("options", ctx).getOrElse(Map.empty),
        schema = readSchema(obj.get("schema")),
        sql = obj.optString("sql", ctx)
      ))
    } catch {
      case NonFatal(_) => None
    }
  }

  /** A recorded schema, or None when there is none or a column's type is one
    * this build has no name for: the output then reads as a record without
    * one does, rather than not at all. */
  private def readSchema(value: Option[JsonValue]): Option[Schema] = value match {
    case Some(JsonArray(items)) =>
      val fields = items.map {
        case o: JsonObject =>
          for {
            name <- o.get("name").collect { case JsonString(s) => s }
            dataType <- o.get("type").collect { case JsonString(s) => s }.flatMap(DataType.fromName)
          } yield Field(name, dataType, o.get("nullable").collect { case JsonBool(b) => b }.getOrElse(true))
        case _ => None
      }
      if (fields.forall(_.isDefined)) Some(Schema(fields.flatten.toVector)) else None
    case _ => None
  }

  /** The output in `dir` of the run `record` describes, as a reader reads it:
    * see the other overload. A `Running` record describes no output: its run
    * stopped before it finished, or is writing the directory still. */
  def outputView(dir: Path, record: TaskRunRecord): CatalogView = {
    if (record.status == TaskRunStatus.Running) throw new IllegalArgumentException(
      s"The output in $dir is unfinished: its _run.json says a run is writing it, so that run stopped before it " +
        "finished or is still running")
    outputView(dir, record.format, record.outputFiles, record.options, record.schema)
  }

  /** A task's output in `dir` as the tasks after it read it: the part files
    * `files` its run wrote, by name, never a listing of the directory, so a
    * file that is no part of the output (a README) is not read. A CSV output
    * reads with the writer's `options` and, when known, the result's `schema`,
    * so its columns keep the types they were written with; without one its
    * types are inferred. An output of no part files (a parquet result of no
    * partitions) is the empty view of its schema. */
  def outputView(
      dir: Path,
      format: String,
      files: Seq[String],
      options: Map[String, String],
      schema: Option[Schema]
  ): CatalogView = {
    val paths = files.map(f => dir.resolve(f)).toIndexedSeq
    format.toLowerCase(Locale.ROOT) match {
      case _ if paths.isEmpty => schema match {
        case Some(s) => new MaterializedView(s, IndexedSeq.empty)
        case None => throw new IllegalArgumentException(s"The output in $dir has no part files")
      }
      case "csv" =>
        new CsvReader(paths, schema match {
          case Some(s) => CsvOptions.forWrittenFile(options, s)
          case None => CsvOptions.fromMap(options)
        })
      case "parquet" => ParquetReader.fromFiles(paths)
      case other => throw new IllegalArgumentException(s"Cannot read an output of unknown format '$other': $dir")
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

    // The whole instant, not its milliseconds: two partitions can be written
    // inside one millisecond, and a tie would fall back to the walk's order.
    results.sortWith((a, b) => a._2.writtenAt.isAfter(b._2.writtenAt)).toVector
  }

  /** If `dir` holds a `_run.json`, delete every other file a run writes at the
    * top level of `dir` — part files, validation samples, metrics, staged
    * parts and the writers' hidden sidecars, each `part-*`, `_*` or `.*` — and
    * return true; the record stays. So a clear stopped part way leaves the
    * record that marks what is left as a run's, and the next run clears it.
    * Any other file, a README someone put there, is left alone, as no reader
    * of the output reads it (plans/robustness/29).
    *
    * Subdirectories are deliberately left alone — our output layout is flat,
    * so a nested directory is not "ours" to wipe.
    */
  def clear(dir: Path): Boolean = {
    if (!isMarked(dir)) return false
    val doomed = mutable.ArrayBuffer.empty[Path]
    val stream = Files.list(dir)
    try {
      val it = stream.iterator()
      while (it.hasNext) {
        val p = it.next()
        val n = p.getFileName.toString
        if (n != FileName && Files.isRegularFile(p) && (n.startsWith("part-") || n.startsWith("_") || n.startsWith(".")))
          doomed += p
      }
    } finally stream.close()
    doomed.foreach { p =>
      Faults.at(Faults.ClearDelete)
      Files.deleteIfExists(p)
    }
    true
  }

  /** Whether `dir` holds a run's record, which makes its part files a run's. */
  def isMarked(dir: Path): Boolean = Files.isRegularFile(dir.resolve(FileName))

  /** The part files in `dir` that no record names as a run's: what another
    * job writing there, a person copying files in, or a run of a build from
    * before runs marked a directory first left. A reader of the directory,
    * Spark or DuckDB, would read them as part of the output. */
  def unrecordedPartFiles(dir: Path): Seq[String] =
    if (isMarked(dir)) Nil else listPartFiles(dir)

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

  /** The most characters of a validation's name a sample file name keeps, so
    * the name stays inside the 255 bytes a file system takes whatever the
    * validation is called. */
  private val MaxSampleSlug: Int = 100

  /** Build the sibling-file name for a validation failure sample. Lowercases
    * the validation name, replaces non-`[a-z0-9_-]` runs with `_`, trims
    * leading/trailing underscores, and keeps the first [[MaxSampleSlug]]
    * characters. Falls back to `validation` for empty results. Names reduce
    * alike (`a b`, `a.b`), so a name in `taken` — another failed validation's
    * of the same task — gets `-2`, `-3`, ... until it is free. */
  def validationSampleFileName(validationName: String, taken: Set[String] = Set.empty): String = {
    val slug = validationName
      .toLowerCase(java.util.Locale.ROOT)
      .replaceAll("[^a-z0-9_-]+", "_")
      .replaceAll("^_+|_+$", "")
      .take(MaxSampleSlug)
      .replaceAll("_+$", "")
    val base = s"$ValidationSamplePrefix${if (slug.isEmpty) "validation" else slug}"
    Iterator.from(1).map(n => if (n == 1) s"$base.csv" else s"$base-$n.csv").find(!taken.contains(_)).get
  }

  /** Write `csv` to `<dir>/_validation-<slug>.csv` atomically, under a name not
    * in `taken`. Returns the bare file name so the caller can record it in
    * [[ValidationRecord.sampleFile]]. */
  def writeValidationSample(dir: Path, validationName: String, csv: String, taken: Set[String] = Set.empty): String = {
    if (!Files.isDirectory(dir)) Files.createDirectories(dir)
    val name = validationSampleFileName(validationName, taken)
    val target = dir.resolve(name)
    val tmp = dir.resolve(name + ".tmp")
    Files.writeString(tmp, csv)
    try {
      val _ = Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
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
    sb.append("  \"taskName\": \"").append(Json.escape(r.taskName)).append("\",\n")
    sb.append("  \"status\": \"").append(Json.escape(TaskRunStatus.toJson(r.status))).append("\",\n")
    r.errorMessage match {
      case Some(msg) => sb.append("  \"errorMessage\": \"").append(Json.escape(msg)).append("\",\n")
      case None      => sb.append("  \"errorMessage\": null,\n")
    }
    sb.append("  \"executionTime\": \"").append(Json.escape(r.executionTime.toString)).append("\",\n")
    r.enqueuedAt.foreach { ea =>
      sb.append("  \"enqueuedAt\": \"").append(Json.escape(ea.toString)).append("\",\n")
    }
    sb.append("  \"startedAt\": \"").append(Json.escape(r.startedAt.toString)).append("\",\n")
    r.finishedAt.foreach { fa =>
      sb.append("  \"finishedAt\": \"").append(Json.escape(fa.toString)).append("\",\n")
    }
    sb.append("  \"writtenAt\": \"").append(Json.escape(r.writtenAt.toString)).append("\",\n")
    sb.append("  \"rowsProduced\": ").append(r.rowsProduced).append(",\n")
    sb.append("  \"format\": \"").append(Json.escape(r.format)).append("\",\n")
    sb.append("  \"outputFiles\": ").append(Json.stringArray(r.outputFiles)).append(",\n")
    sb.append("  \"options\": ").append(Json.stringMap(r.options)).append(",\n")
    r.schema.foreach { s =>
      sb.append("  \"schema\": [")
      s.fields.iterator.zipWithIndex.foreach { case (f, i) =>
        if (i > 0) sb.append(", ")
        sb.append("{\"name\": \"").append(Json.escape(f.name)).append("\", ")
        sb.append("\"type\": \"").append(Json.escape(f.dataType.name)).append("\", ")
        sb.append("\"nullable\": ").append(f.nullable).append('}')
      }
      sb.append("],\n")
    }
    sb.append("  \"validations\": [")
    if (r.validations.nonEmpty) {
      sb.append('\n')
      var j = 0
      while (j < r.validations.size) {
        val v = r.validations(j)
        sb.append("    {")
        sb.append("\"name\": \"").append(Json.escape(v.name)).append("\", ")
        sb.append("\"passed\": ").append(v.passed).append(", ")
        sb.append("\"failedRowCount\": ").append(v.failedRowCount).append(", ")
        v.sampleFile match {
          case Some(f) => sb.append("\"sampleFile\": \"").append(Json.escape(f)).append("\"")
          case None    => sb.append("\"sampleFile\": null")
        }
        sb.append('}')
        if (j < r.validations.size - 1) sb.append(',')
        sb.append('\n')
        j += 1
      }
      sb.append("  ")
    }
    sb.append("]")
    r.sql.foreach(s => sb.append(",\n  \"sql\": \"").append(Json.escape(s)).append('"'))
    sb.append("\n}\n")
    sb.toString
  }
}
