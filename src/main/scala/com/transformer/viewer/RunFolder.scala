package com.transformer.viewer

import com.transformer.job.{JobInputRecord, JobRunRecord, JobTaskSummary, TaskRunRecord}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}

import java.nio.file.{Files, Path, Paths}
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** A folder of job outputs as the data viewer reads it: every dataset under
  * it, and what could not be read.
  *
  * A dataset is one job's output. Each `job.json` under the folder is a run
  * of a job; runs whose records share an `outputTemplate` are runs of one job
  * writing to a templated directory (`/data/runs/jaffle/day={{ today }}`),
  * and make one dataset, newest run first. A run from before records carried
  * the template is a dataset of its own. */
final case class RunFolder(root: Path, datasets: Vector[Dataset], warnings: Vector[String])

/** One job's output: its runs, newest first, and the names it goes by. */
final case class Dataset(name: String, sqlName: String, runs: Vector[DatasetRun])

/** A run of a job: the directory its `job.json` is in, and what that records. */
final case class DatasetRun(dir: Path, record: JobRunRecord) {

  /** Each table the run made, in the order the job declares them. */
  lazy val tables: Vector[TableInfo] = record.tasks.iterator.map { s =>
    val recorded = s.runFile.flatMap(rf => RunFolder.parentOf(rf))
    TableInfo(s.taskName, s, recorded, s.runFile.flatMap(rf => RunFolder.locateRunFile(this, rf)))
  }.toVector

  /** The run's inputs as it read them; empty for a run from before records
    * carried lineage. */
  def inputs: Seq[JobInputRecord] = record.inputs.getOrElse(Nil)

  /** Whether the run recorded its lineage: its inputs, and what each table
    * reads. */
  def hasLineage: Boolean = record.inputs.isDefined

  def table(name: String): Option[TableInfo] = tables.find(_.name == name)
}

/** A table of a run: its job.json entry, the directory the run wrote it to as
  * the run recorded it, and the directory it is in now, when that holds its
  * `_run.json` (the same one, unless the folder was moved since). */
final case class TableInfo(name: String, summary: JobTaskSummary, recordedDir: Option[String], dir: Option[Path]) {
  /** Its `_run.json`, read afresh. */
  def record: Option[TaskRunRecord] = dir.flatMap(TaskRunRecord.read)
}

object RunFolder {

  /** How deep below the folder a `job.json` is looked for. */
  val MaxDepth: Int = 6

  /** How many directories a scan opens before it stops, so opening a home
    * directory by mistake ends. */
  val MaxDirectories: Int = 20000

  /** Every dataset under `root`, named and ordered by name. */
  def scan(root: Path): RunFolder = {
    val runs = mutable.ArrayBuffer.empty[DatasetRun]
    val warnings = mutable.ArrayBuffer.empty[String]
    var opened = 0

    def walk(dir: Path, depth: Int): Unit = {
      opened += 1
      if (opened > MaxDirectories) return
      val manifest = dir.resolve("job.json")
      if (Files.isRegularFile(manifest)) {
        // A run's subdirectories are its tables: there is no job below it.
        JobRunRecord.read(manifest) match {
          case Some(rec) => runs += DatasetRun(dir, rec)
          case None => warnings += s"$manifest is not a job.json this viewer can read"
        }
      } else if (depth < MaxDepth && !TaskRunRecord.isMarked(dir)) {
        subdirectories(dir).foreach(walk(_, depth + 1))
      }
    }

    val absRoot = root.toAbsolutePath.normalize()
    if (!Files.isDirectory(absRoot)) return RunFolder(absRoot, Vector.empty, Vector(s"$absRoot is not a directory"))
    walk(absRoot, 0)
    if (opened > MaxDirectories)
      warnings += s"stopped looking after $MaxDirectories directories; open a folder closer to the job outputs"
    RunFolder(absRoot, group(absRoot, runs.toVector), warnings.toVector)
  }

  /** The runs as datasets: one per output template (a run without one on its
    * own), named, newest run first, ordered by name. */
  private[viewer] def group(root: Path, runs: Vector[DatasetRun]): Vector[Dataset] = {
    val groups = runs.groupBy(r => r.record.outputTemplate.map("template:" + _).getOrElse("dir:" + r.dir)).values
      .map(_.sortWith((a, b) =>
        a.record.finishedAt.isAfter(b.record.finishedAt) ||
          (a.record.finishedAt == b.record.finishedAt && a.dir.toString > b.dir.toString)))
      .toVector
    val named = groups.map(g => (displayName(root, g.head), g))
    // Two datasets that come out with one name are told apart by where they
    // are under the folder.
    val counts = named.groupBy(_._1).map { case (n, v) => n -> v.size }
    val unique = named.map { case (n, g) =>
      if (counts(n) == 1) (n, g)
      else (relative(root, g.head.dir).filter(_.nonEmpty).getOrElse(g.head.dir.toString), g)
    }.sortBy(_._1.toLowerCase(Locale.ROOT))
    val sqlNames = uniqueNames(unique.map(n => sqlIdentifier(n._1)))
    unique.zip(sqlNames).map { case ((name, g), sql) => Dataset(name, sql, g) }
  }

  /** A run's dataset as the folder shows it: its directory under the folder,
    * without the parts its output template fills in per run, so the runs of
    * `jaffle/day={{ today }}` are `jaffle`. The folder's own name when the run
    * is the folder itself. */
  private[viewer] def displayName(root: Path, run: DatasetRun): String = {
    val rel = relative(root, run.dir).map(_.split('/').filter(_.nonEmpty).toSeq).getOrElse(Seq(run.dir.toString))
    val templated: Seq[Boolean] = run.record.outputTemplate match {
      case Some(t) =>
        val parts = t.replace('\\', '/').split('/').filter(_.nonEmpty).toSeq
        // Align from the end: the run's last directories are the template's.
        rel.indices.map(i => parts.lift(parts.size - rel.size + i).exists(_.contains("{{")))
      case None => rel.map(_ => false)
    }
    val kept = rel.zip(templated).collect { case (p, false) => p }
    if (kept.nonEmpty) kept.mkString("/")
    else run.record.outputTemplate.flatMap(t => t.replace('\\', '/').split('/').reverseIterator.find(p =>
      p.nonEmpty && !p.contains("{{"))).orElse(Option(root.getFileName).map(_.toString)).getOrElse(root.toString)
  }

  /** `dir` under `root` as a `/`-separated path; None when it is not under it. */
  private def relative(root: Path, dir: Path): Option[String] =
    if (dir.startsWith(root)) Some(root.relativize(dir).toString.replace('\\', '/')) else None

  /** `name` as a name SQL reads without quotes: each run of other characters
    * an underscore, and not starting with a digit. */
  def sqlIdentifier(name: String): String = {
    val cleaned = name.replaceAll("[^A-Za-z0-9_]+", "_").replaceAll("^_+|_+$", "")
    if (cleaned.isEmpty) "dataset"
    else if (cleaned.head.isDigit) "_" + cleaned
    else cleaned
  }

  /** `names` with a repeat (regardless of case) given `_2`, `_3`, ... */
  def uniqueNames(names: Seq[String]): Seq[String] = {
    val taken = mutable.Set.empty[String]
    names.map { n =>
      val name = Iterator.from(1).map(i => if (i == 1) n else s"${n}_$i")
        .find(c => !taken.contains(c.toLowerCase(Locale.ROOT))).get
      taken += name.toLowerCase(Locale.ROOT)
      name
    }
  }

  private def subdirectories(dir: Path): Seq[Path] =
    try {
      val stream = Files.list(dir)
      try stream.iterator().asScala.filter(p => Files.isDirectory(p) && !p.getFileName.toString.startsWith("."))
        .toVector.sortBy(_.getFileName.toString)
      finally stream.close()
    } catch { case NonFatal(_) => Nil }

  private[viewer] def parentOf(path: String): Option[String] =
    try Option(Paths.get(path).getParent).map(_.toString) catch { case NonFatal(_) => None }

  /** Where `runFile`, as `run`'s job.json records it, is now: the path itself
    * when its `_run.json` is there; else, for a folder moved since the run, the
    * same place under the run's directory now (the run wrote it under the
    * directory its template renders to); else, for a path recorded relative
    * to where the job ran, under the run's directory or one above it. */
  private[viewer] def locateRunFile(run: DatasetRun, runFile: String): Option[Path] = {
    def held(p: Path): Option[Path] = Option(p.getParent).filter(_ => Files.isRegularFile(p))
    val recorded = try Some(Paths.get(runFile)) catch { case NonFatal(_) => None }
    recorded.flatMap { p =>
      if (p.isAbsolute) held(p).orElse {
        val renderedRunDir = run.record.outputTemplate.flatMap(t =>
          try Some(Paths.get(TemplateRenderer.render(t, TemporalVariables(run.record.executionTime))))
          catch { case NonFatal(_) => None })
        renderedRunDir.filter(p.startsWith).flatMap(d => held(run.dir.resolve(d.relativize(p))))
      } else Iterator.iterate(run.dir)(_.getParent).takeWhile(_ != null).map(a => held(a.resolve(p)))
        .collectFirst { case Some(d) => d }
    }
  }
}
