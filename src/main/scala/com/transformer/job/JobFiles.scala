package com.transformer.job

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** File-system author for the directory-based job format consumed by
  * [[DirectoryJobLoader]]. Every operation writes to disk atomically (temp +
  * rename) and leaves the directory in a state the loader will round-trip.
  *
  * Used by the GUI's "New project", "Add input/table/validation", "Edit", and
  * "Delete" flows. No JavaFX deps — usable from tests directly.
  *
  * Conventions:
  *
  *   - Input config files are written as `inputs/<view>/config.json` for new
  *     inputs; updates re-use whatever single `.json` file already lives in
  *     the view's directory.
  *   - Per-table output config is written as `tables/<view>/output.json` only
  *     when at least one field is non-default (format != csv, partitionBy set,
  *     or maxPartitions set). Reverting to all-defaults deletes the file so the
  *     on-disk layout stays minimal.
  *   - Paths under the job directory are stored relative to it; absolute paths
  *     outside the job dir and cloud paths (`gs://`, `s3://`) are kept verbatim.
  *     Matches the existing examples in `examples/directory_app/job/` etc.
  */
object JobFiles {

  /** Same identifier rule the SQL engine uses for column references. */
  private val ViewNamePattern = "^[a-zA-Z_][a-zA-Z0-9_]*$".r

  /** Returns Left(reason) if `name` isn't a valid view name. */
  def validateViewName(name: String): Either[String, Unit] = {
    if (name == null || name.trim.isEmpty) Left("Name cannot be empty.")
    else if (!ViewNamePattern.matches(name.trim))
      Left("Name must be a SQL identifier (letters, digits, underscores; can't start with a digit).")
    else Right(())
  }

  /** Returns Left(reason) if a view by this name already exists in either
    * `inputs/` or `tables/`. View names share a namespace because the engine's
    * catalog does.
    */
  def ensureNotInUse(jobDir: Path, viewName: String): Either[String, Unit] = {
    val inputDir = jobDir.resolve("inputs").resolve(viewName)
    val tableDir = jobDir.resolve("tables").resolve(viewName)
    if (Files.exists(inputDir)) Left(s"An input named '$viewName' already exists.")
    else if (Files.exists(tableDir)) Left(s"A table named '$viewName' already exists.")
    else Right(())
  }

  // ---- New project skeleton ------------------------------------------------

  /** Create a runnable starter project at `dir`: one CSV seed, one input, one
    * downstream table referencing it. The skeleton is small enough to fit in
    * the GUI on first open and exercises the full DBT-style layout.
    *
    * Refuses to run if `dir` already has an `inputs/` or `tables/` directory
    * (i.e. it already looks like a job dir) — pointing at an existing project
    * is almost always a mistake we shouldn't silently overwrite. Empty or
    * unrelated directories are fine.
    */
  def createNewProject(dir: Path): Unit = {
    if (Files.isDirectory(dir.resolve("inputs")) || Files.isDirectory(dir.resolve("tables")))
      throw new IllegalArgumentException(
        s"$dir already contains inputs/ or tables/ — refusing to overwrite an existing project."
      )
    Files.createDirectories(dir)
    val csv =
      "id,name,amount\n" +
        "1,alice,10.00\n" +
        "2,bob,25.50\n" +
        "3,carol,7.25\n"
    writeAtomic(dir.resolve("data").resolve("example.csv"), csv)
    addInput(
      dir,
      viewName = "example",
      path = "data/example.csv",
      format = None,
      cache = true,
      options = Map.empty
    )
    addTable(
      dir,
      viewName = "example_summary",
      sql = "SELECT\n  name,\n  amount\nFROM example\nORDER BY name\n",
      format = None,
      partitionBy = None,
      maxPartitions = None
    )
  }

  // ---- Inputs --------------------------------------------------------------

  /** Create a new input. Throws [[IllegalArgumentException]] if the view name
    * is invalid, already in use, or `path` is empty. Returns the config-file
    * path that was written.
    */
  def addInput(
      jobDir: Path,
      viewName: String,
      path: String,
      format: Option[String],
      cache: Boolean,
      options: Map[String, String]
  ): Path = {
    requireValidName(viewName)
    requireNonEmpty(path, "Input path")
    val viewDir = jobDir.resolve("inputs").resolve(viewName)
    if (Files.exists(viewDir))
      throw new IllegalArgumentException(s"Input '$viewName' already exists.")
    Files.createDirectories(viewDir)
    val target = viewDir.resolve("config.json")
    writeAtomic(target, renderInputConfig(relativizeIfUnder(jobDir, path), format, cache, options))
    target
  }

  /** Overwrite an existing input's config.json. Re-uses the existing file name
    * (the loader is tolerant of any `*.json` name) so we don't leave dotfiles
    * or rename the config out from under the user's editor.
    */
  def updateInputConfig(
      jobDir: Path,
      viewName: String,
      path: String,
      format: Option[String],
      cache: Boolean,
      options: Map[String, String]
  ): Path = {
    requireNonEmpty(path, "Input path")
    val viewDir = jobDir.resolve("inputs").resolve(viewName)
    if (!Files.isDirectory(viewDir))
      throw new IllegalArgumentException(s"Input '$viewName' does not exist.")
    val target = findSingleJson(viewDir).getOrElse(viewDir.resolve("config.json"))
    writeAtomic(target, renderInputConfig(relativizeIfUnder(jobDir, path), format, cache, options))
    target
  }

  def deleteInput(jobDir: Path, viewName: String): Unit = {
    val viewDir = jobDir.resolve("inputs").resolve(viewName)
    if (Files.isDirectory(viewDir)) deleteRecursively(viewDir)
  }

  // ---- Tables --------------------------------------------------------------

  def addTable(
      jobDir: Path,
      viewName: String,
      sql: String,
      format: Option[String],
      partitionBy: Option[String],
      maxPartitions: Option[Int]
  ): Path = {
    requireValidName(viewName)
    maxPartitions.foreach(requirePositive)
    val viewDir = jobDir.resolve("tables").resolve(viewName)
    if (Files.exists(viewDir))
      throw new IllegalArgumentException(s"Table '$viewName' already exists.")
    Files.createDirectories(viewDir)
    val main = viewDir.resolve("main.sql")
    writeAtomic(main, normalizeSql(sql))
    writeOutputConfigIfNeeded(viewDir, format, partitionBy, maxPartitions)
    main
  }

  /** Write the table's output.json — or delete it, if every setting reverts to
    * a default (matching the on-disk minimization rule used by the example
    * projects).
    */
  def updateTableConfig(
      jobDir: Path,
      viewName: String,
      format: Option[String],
      partitionBy: Option[String],
      maxPartitions: Option[Int]
  ): Unit = {
    maxPartitions.foreach(requirePositive)
    val viewDir = jobDir.resolve("tables").resolve(viewName)
    if (!Files.isDirectory(viewDir))
      throw new IllegalArgumentException(s"Table '$viewName' does not exist.")
    val configPath = viewDir.resolve("output.json")
    if (allDefaults(format, partitionBy, maxPartitions)) Files.deleteIfExists(configPath)
    else writeAtomic(configPath, renderOutputConfig(format, partitionBy, maxPartitions))
  }

  /** Overwrite `tables/<viewName>/main.sql`. Throws if the table directory
    * doesn't exist — use [[addTable]] to create a new one.
    */
  def writeMainSql(jobDir: Path, viewName: String, sql: String): Unit = {
    val viewDir = jobDir.resolve("tables").resolve(viewName)
    if (!Files.isDirectory(viewDir))
      throw new IllegalArgumentException(s"Table '$viewName' does not exist.")
    writeAtomic(viewDir.resolve("main.sql"), normalizeSql(sql))
  }

  def deleteTable(jobDir: Path, viewName: String): Unit = {
    val viewDir = jobDir.resolve("tables").resolve(viewName)
    if (Files.isDirectory(viewDir)) deleteRecursively(viewDir)
  }

  // ---- Validations ---------------------------------------------------------

  def addValidation(
      jobDir: Path,
      tableViewName: String,
      validationName: String,
      sql: String
  ): Path = {
    requireValidName(validationName)
    val tableDir = jobDir.resolve("tables").resolve(tableViewName)
    if (!Files.isDirectory(tableDir))
      throw new IllegalArgumentException(s"Table '$tableViewName' does not exist.")
    val dir = tableDir.resolve("validations")
    Files.createDirectories(dir)
    val target = dir.resolve(s"$validationName.sql")
    if (Files.exists(target))
      throw new IllegalArgumentException(s"Validation '$validationName' already exists.")
    writeAtomic(target, normalizeSql(sql))
    target
  }

  def writeValidationSql(
      jobDir: Path,
      tableViewName: String,
      validationName: String,
      sql: String
  ): Unit = {
    val target = jobDir.resolve("tables").resolve(tableViewName)
      .resolve("validations").resolve(s"$validationName.sql")
    if (!Files.isRegularFile(target))
      throw new IllegalArgumentException(s"Validation '$validationName' does not exist.")
    writeAtomic(target, normalizeSql(sql))
  }

  def deleteValidation(jobDir: Path, tableViewName: String, validationName: String): Unit = {
    val target = jobDir.resolve("tables").resolve(tableViewName)
      .resolve("validations").resolve(s"$validationName.sql")
    Files.deleteIfExists(target)
    // Remove the validations dir if it ended up empty so the on-disk layout stays clean.
    val dir = target.getParent
    if (Files.isDirectory(dir) && isEmpty(dir)) Files.deleteIfExists(dir)
  }

  // ---- JSON rendering ------------------------------------------------------

  private def renderInputConfig(
      path: String,
      format: Option[String],
      cache: Boolean,
      options: Map[String, String]
  ): String = {
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    parts += s"""  "path": ${jsonString(path)}"""
    format.filter(_.nonEmpty).foreach(f => parts += s"""  "format": ${jsonString(f)}""")
    if (!cache) parts += """  "cache": false"""
    if (options.nonEmpty) {
      val inner = options.toSeq.sortBy(_._1)
        .map { case (k, v) => s"""    ${jsonString(k)}: ${jsonString(v)}""" }
        .mkString(",\n")
      parts += s"""  "options": {
$inner
  }"""
    }
    s"""{
${parts.mkString(",\n")}
}
"""
  }

  private def renderOutputConfig(
      format: Option[String],
      partitionBy: Option[String],
      maxPartitions: Option[Int]
  ): String = {
    val parts = scala.collection.mutable.ListBuffer.empty[String]
    format.filter(_.nonEmpty).foreach(f => parts += s"""  "format": ${jsonString(f)}""")
    partitionBy.filter(_.nonEmpty).foreach(p => parts += s"""  "partitionBy": ${jsonString(p)}""")
    maxPartitions.foreach(n => parts += s"""  "maxPartitions": $n""")
    s"""{
${parts.mkString(",\n")}
}
"""
  }

  private def writeOutputConfigIfNeeded(
      viewDir: Path,
      format: Option[String],
      partitionBy: Option[String],
      maxPartitions: Option[Int]
  ): Unit = {
    if (allDefaults(format, partitionBy, maxPartitions)) return
    writeAtomic(viewDir.resolve("output.json"), renderOutputConfig(format, partitionBy, maxPartitions))
  }

  /** True iff every field maps to the directory loader's default. The loader
    * treats absent format as csv, absent partitionBy as none, and absent
    * maxPartitions as none — so omitting these is semantically identical to
    * specifying defaults.
    */
  private def allDefaults(
      format: Option[String],
      partitionBy: Option[String],
      maxPartitions: Option[Int]
  ): Boolean =
    (format.isEmpty || format.exists(_.trim.equalsIgnoreCase("csv"))) &&
      partitionBy.forall(_.trim.isEmpty) &&
      maxPartitions.isEmpty

  // ---- File I/O helpers ----------------------------------------------------

  private def normalizeSql(sql: String): String = {
    val s = if (sql == null) "" else sql
    if (s.endsWith("\n")) s else s + "\n"
  }

  /** Atomic temp-then-rename write. Mirrors [[TaskRunRecord.write]]. */
  private def writeAtomic(target: Path, content: String): Unit = {
    val parent = target.getParent
    if (parent != null && !Files.isDirectory(parent)) Files.createDirectories(parent)
    val tmp = parent.resolve(s".${target.getFileName.toString}.tmp")
    Files.writeString(tmp, content)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  private def deleteRecursively(dir: Path): Unit = {
    if (!Files.exists(dir)) return
    val stream = Files.walk(dir)
    try {
      // Delete deepest paths first so directories are empty when we get to them.
      val all = stream.iterator().asScala.toVector
      all.sortBy(_.getNameCount)(Ordering[Int].reverse).foreach { p =>
        try Files.deleteIfExists(p) catch { case NonFatal(_) => () }
      }
    } finally stream.close()
  }

  private def findSingleJson(dir: Path): Option[Path] = {
    val stream = Files.list(dir)
    try {
      stream.iterator().asScala.toVector
        .filter(p => Files.isRegularFile(p) && p.getFileName.toString.endsWith(".json"))
        .sortBy(_.getFileName.toString)
        .headOption
    } finally stream.close()
  }

  private def isEmpty(dir: Path): Boolean = {
    val stream = Files.list(dir)
    try !stream.iterator().hasNext finally stream.close()
  }

  /** If `path` is an absolute path under `jobDir`, return its job-dir-relative
    * form so the on-disk config stays portable when the project is moved or
    * cloned to a new checkout. Absolute paths outside the job dir, and cloud
    * paths, pass through unchanged.
    */
  private def relativizeIfUnder(jobDir: Path, path: String): String = {
    if (path.startsWith("gs://") || path.startsWith("s3://")) return path
    try {
      val candidate = Paths.get(path).toAbsolutePath.normalize()
      val anchor = jobDir.toAbsolutePath.normalize()
      if (candidate.startsWith(anchor)) anchor.relativize(candidate).toString
      else path
    } catch {
      case NonFatal(_) => path
    }
  }

  // ---- Validation helpers --------------------------------------------------

  private def requireValidName(name: String): Unit =
    validateViewName(name).left.foreach(reason => throw new IllegalArgumentException(reason))

  private def requireNonEmpty(value: String, label: String): Unit =
    if (value == null || value.trim.isEmpty)
      throw new IllegalArgumentException(s"$label cannot be empty.")

  private def requirePositive(n: Int): Unit =
    if (n < 1)
      throw new IllegalArgumentException(s"maxPartitions must be >= 1; got $n")

  private def jsonString(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 2)
    sb.append('"')
    var i = 0
    while (i < s.length) {
      s.charAt(i) match {
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
    sb.append('"')
    sb.toString
  }
}
