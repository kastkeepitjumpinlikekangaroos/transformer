package com.transformer.job

import com.transformer.temporal.TemporalVariables

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** Loads a [[DataJob]] from a directory tree — a DBT-style layout where each input
  * and each transformed table is its own subdirectory.
  *
  * Expected layout (relative to the job directory):
  *
  * {{{
  *   <jobDir>/
  *     inputs/
  *       <viewName>/
  *         <anything>.json           // InputFilePath config
  *     tables/
  *       <viewName>/
  *         main.sql                  // required, the SELECT that produces the table
  *         validations/              // optional
  *           <validationName>.sql
  * }}}
  *
  * Directory names are the view names. Each input config is a JSON object accepting
  * the fields supported by [[InputFilePath]] except `viewName` (taken from the
  * directory). Each table writes its result into the directory
  * `<outputDir>/<viewName>/` as one or more `part-NNNNN.csv` files, and the
  * directory is registered in the catalog so downstream tables can reference it.
  *
  * Tables run in alphabetical order. To express "table B depends on table A", name
  * the directories so A sorts before B (the simplest convention is numeric prefixes
  * like `01_raw`, `02_clean`).
  *
  * Both `inputs/path` strings and `outputDir` are templated against
  * [[TemporalVariables]] at run time, so a config like `{"path": "data/{{ today
  * }}.csv"}` works directly. Relative input paths are resolved against the job
  * directory; absolute and cloud paths are used as-is.
  */
object DirectoryJobLoader {

  /** Default output directory if the caller doesn't specify one: `<jobDir>/output`. */
  def load(jobDir: Path, temporalVariables: TemporalVariables): DataJob =
    load(jobDir, outputDir = None, temporalVariables = Some(temporalVariables))

  /** Default location for the job-level run record: `<resolvedOutputDir>/job.json`
    * — co-located with the per-task data so an output directory is a
    * self-contained, inspectable snapshot of one run (job.json + per-task
    * subdirs with `_run.json` + `part-*` files).
    *
    * To keep run-by-run history, template `outputDir` itself
    * (e.g. `/data/runs/{{ today }}`): each execution time writes to a
    * fresh subdir with its own job.json, and the parent dir becomes a
    * multi-run layout the GUI can browse.
    */
  def load(
      jobDir: Path,
      outputDir: Option[String] = None,
      temporalVariables: Option[TemporalVariables] = None,
      jobRunOutput: Option[OutputFilePath] = None
  ): DataJob = {
    if (!Files.isDirectory(jobDir)) {
      throw new IllegalArgumentException(
        s"Job directory does not exist or is not a directory: $jobDir"
      )
    }
    val absJobDir = jobDir.toAbsolutePath.normalize()
    val resolvedOutputDir = outputDir.getOrElse(absJobDir.resolve("output").toString)
    val resolvedJobRunOutput = jobRunOutput.orElse(
      Some(OutputFilePath(joinPath(resolvedOutputDir, "job.json")))
    )

    val inputs = loadInputs(absJobDir.resolve("inputs"), absJobDir)
    val tables = loadTables(absJobDir.resolve("tables"), resolvedOutputDir)

    DataJob(
      inputs = inputs,
      sql = tables,
      temporalVariables = temporalVariables,
      jobRunOutput = resolvedJobRunOutput
    )
  }

  private def loadInputs(inputsDir: Path, jobDir: Path): Seq[InputFilePath] = {
    if (!Files.isDirectory(inputsDir)) return Nil
    listDirsSorted(inputsDir).map { dir =>
      val viewName = dir.getFileName.toString
      val configPath = findSingleJson(dir, viewName)
      val ctx = s"input '$viewName' (${configPath.getFileName})"
      val obj = Json.parse(Files.readString(configPath)).asObject(ctx)
      InputFilePath(
        path = resolvePath(jobDir, obj.requiredString("path", ctx)),
        viewName = viewName,
        options = obj.optStringMap("options", ctx).getOrElse(Map.empty),
        cache = obj.optBool("cache", ctx).getOrElse(true),
        format = obj.optString("format", ctx)
      )
    }
  }

  private def loadTables(tablesDir: Path, outputDir: String): Seq[SQLTask] = {
    if (!Files.isDirectory(tablesDir)) return Nil
    listDirsSorted(tablesDir).map { dir =>
      val viewName = dir.getFileName.toString
      val mainSql = dir.resolve("main.sql")
      if (!Files.isRegularFile(mainSql)) {
        throw new IllegalArgumentException(
          s"Table '$viewName' is missing required file 'main.sql' (expected at: $mainSql)"
        )
      }
      val validations = loadValidations(dir.resolve("validations"))
      val basePath = joinPath(outputDir, viewName)
      val cfg = loadOutputConfig(dir, viewName)
      val outputPath = cfg.partitionBy match {
        case Some(partition) if partition.nonEmpty => joinPath(basePath, partition)
        case _                                     => basePath
      }
      SQLTask(
        name = Some(viewName),
        viewName = Some(viewName),
        sqlFile = Some(mainSql.toString),
        outputFile = Some(OutputFilePath(
          outputPath,
          format = Some(cfg.format.getOrElse("csv")),
          maxPartitions = cfg.maxPartitions
        )),
        validations = validations
      )
    }
  }

  /** Parsed `tables/<viewName>/output.json` config — every field optional. */
  private final case class OutputConfig(
      partitionBy: Option[String],
      format: Option[String],
      maxPartitions: Option[Int]
  )

  /** Optional `tables/<viewName>/output.json` config. Supported fields:
    *
    *   - `partitionBy` — string appended to the task's output path. Templated
    *     against [[com.transformer.temporal.TemporalVariables]] at run time, so
    *     `"day={{today}}"` produces a partition-per-run layout:
    *     `<outputDir>/<viewName>/day=YYYYMMDD/part-NNNNN.<ext>`.
    *   - `format` — output format (e.g. `"csv"`, `"parquet"`). Defaults to
    *     `"csv"` if omitted.
    *   - `maxPartitions` — cap on the number of `part-*` files written. See
    *     [[OutputFilePath.maxPartitions]] for the coalescing semantics.
    *
    * Returns an empty [[OutputConfig]] if `output.json` is absent. Throws
    * [[IllegalArgumentException]] if it exists but is malformed.
    */
  private def loadOutputConfig(tableDir: Path, viewName: String): OutputConfig = {
    val configPath = tableDir.resolve("output.json")
    if (!Files.isRegularFile(configPath)) return OutputConfig(None, None, None)
    val ctx = s"table '$viewName' (output.json)"
    val obj = Json.parse(Files.readString(configPath)).asObject(ctx)
    val maxPartitions = obj.get("maxPartitions").map { v =>
      val n = try v.stringValue.toInt catch {
        case _: NumberFormatException =>
          throw new IllegalArgumentException(s"$ctx: 'maxPartitions' must be an integer")
      }
      if (n < 1)
        throw new IllegalArgumentException(s"$ctx: 'maxPartitions' must be >= 1; got $n")
      n
    }
    OutputConfig(
      partitionBy = obj.optString("partitionBy", ctx).map(_.trim).filter(_.nonEmpty),
      format = obj.optString("format", ctx).map(_.trim).filter(_.nonEmpty),
      maxPartitions = maxPartitions
    )
  }

  private def loadValidations(validationsDir: Path): Seq[Validation] = {
    if (!Files.isDirectory(validationsDir)) return Nil
    listFilesSorted(validationsDir).filter(_.getFileName.toString.endsWith(".sql")).map { f =>
      val fileName = f.getFileName.toString
      Validation(
        name = fileName.substring(0, fileName.length - ".sql".length),
        sqlFile = Some(f.toString)
      )
    }
  }

  private def findSingleJson(dir: Path, viewName: String): Path = {
    val jsons = listFilesSorted(dir).filter(_.getFileName.toString.endsWith(".json"))
    jsons match {
      case Seq(only) => only
      case Nil =>
        throw new IllegalArgumentException(
          s"Input '$viewName' has no .json config file in $dir"
        )
      case many =>
        throw new IllegalArgumentException(
          s"Input '$viewName' has multiple .json config files (${many.map(_.getFileName).mkString(", ")}). " +
            "Keep exactly one."
        )
    }
  }

  private def listDirsSorted(dir: Path): Seq[Path] = {
    val stream = Files.list(dir)
    try {
      stream.iterator().asScala.toVector
        .filter(Files.isDirectory(_))
        .sortBy(_.getFileName.toString)
    } finally stream.close()
  }

  private def listFilesSorted(dir: Path): Seq[Path] = {
    val stream = Files.list(dir)
    try {
      stream.iterator().asScala.toVector
        .filter(Files.isRegularFile(_))
        .sortBy(_.getFileName.toString)
    } finally stream.close()
  }

  private def resolvePath(jobDir: Path, p: String): String = {
    if (p.startsWith("gs://") || p.startsWith("s3://")) p
    else {
      val asPath = Paths.get(p)
      if (asPath.isAbsolute) p
      else jobDir.resolve(p).toString
    }
  }

  private def joinPath(base: String, name: String): String =
    if (base.endsWith("/")) base + name else base + "/" + name
}
