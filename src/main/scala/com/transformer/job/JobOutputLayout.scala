package com.transformer.job

import java.nio.file.{Files, Path}
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** What kind of run-output layout a directory holds. The GUI uses
  * [[JobOutputLayout.detect]] to decide whether opening a job source dir
  * resolves to a single past run, a parent of multiple past runs (each its
  * own self-contained `job.json`-rooted directory), or nothing yet
  * persisted.
  *
  * The two non-empty cases both produce a non-empty `runs` list sorted
  * newest-first by [[JobRunRecord.finishedAt]]. The distinction is:
  *   * `SingleRun` — `dir` itself contains the `job.json`. Reruns at the
  *     same templated output path overwrite this file in place.
  *   * `MultiRun` — `dir` contains subdirectories, each a self-contained
  *     run (`<runDir>/job.json` + per-task `_run.json` files). Users
  *     produce this layout by templating their `outputDir` (e.g.
  *     `/data/runs/{{ today }}`) so each execution time writes to its own
  *     subdir.
  */
sealed trait JobOutputLayout
object JobOutputLayout {
  final case class SingleRun(dir: Path, record: JobRunRecord) extends JobOutputLayout
  final case class MultiRun(runs: Seq[(Path, JobRunRecord)]) extends JobOutputLayout
  case object Empty extends JobOutputLayout

  /** Inspect `dir` and classify what it holds.
    *
    *   * `<dir>/job.json` present → [[SingleRun]] with the parsed record.
    *   * Otherwise, look for `<dir>/<sub>/job.json` across one level of
    *     subdirectories. If 1+ found → [[MultiRun]] (sorted newest-first
    *     by `finishedAt`).
    *   * Otherwise → [[Empty]].
    *
    * The walk is one level deep — we never recurse — so this is cheap to
    * run on every directory open. Subdirectories without a `job.json` are
    * ignored (could be cache, archive, etc.).
    */
  def detect(dir: Path): JobOutputLayout = {
    if (!Files.isDirectory(dir)) return Empty
    val direct = dir.resolve("job.json")
    if (Files.isRegularFile(direct)) {
      JobRunRecord.read(direct) match {
        case Some(rec) => SingleRun(dir, rec)
        case None      => Empty
      }
    } else {
      val runs = mutable.ArrayBuffer.empty[(Path, JobRunRecord)]
      val stream = Files.list(dir)
      try {
        stream.iterator().asScala.foreach { sub =>
          if (Files.isDirectory(sub)) {
            val j = sub.resolve("job.json")
            if (Files.isRegularFile(j)) {
              try JobRunRecord.read(j).foreach(rec => runs += sub -> rec)
              catch { case NonFatal(_) => () }
            }
          }
        }
      } finally stream.close()
      if (runs.isEmpty) Empty
      else MultiRun(runs.sortBy(-_._2.finishedAt.toEpochMilli).toVector)
    }
  }
}
