package com.transformer.viewer

import com.transformer.job.JobInputRecord

import java.nio.file.{FileSystems, Files, Path, Paths}
import scala.collection.mutable
import scala.util.control.NonFatal

/** A node of the lineage graph: a table a dataset's shown run made, or an
  * input it read. `dataset` indexes [[RunFolder.datasets]]. */
sealed trait NodeId {
  def dataset: Int
  def name: String
}
object NodeId {
  final case class Table(dataset: Int, name: String) extends NodeId
  final case class Input(dataset: Int, name: String) extends NodeId
}

/** Where an input's rows came from. */
sealed trait InputSource
object InputSource {
  /** The output of tables in the folder, most often one. */
  final case class Tables(matches: Seq[TableMatch]) extends InputSource
  /** A file or directory no run in the folder wrote: data from outside. */
  case object External extends InputSource
}

/** An input's path is the output of `table` in the run of `dataset` in
  * `runDir`; `shown` when that run is the one the viewer shows, and
  * `inShownRun` when the shown run has a table of that name, which the edge
  * then joins. */
final case class TableMatch(dataset: Int, table: String, runDir: Path, shown: Boolean, inShownRun: Boolean)

/** The lineage across a folder's datasets, each as its shown run recorded it.
  *
  * Within a dataset an edge joins each input or table a table's main SQL
  * reads to the table. Across datasets an edge joins a table to an input of
  * another job whose path is that table's output directory, a file in it, or
  * a glob that matches its part files: the run of job B read what job A
  * wrote. The path is compared as each run recorded it, and as it is on disk
  * now (through symbolic links: `/tmp` is `/private/tmp` on macOS). An input
  * may read a run of A other than the one shown; its edge still joins A's
  * table of that name, and [[TableMatch.shown]] says so. */
final class Lineage(
    val folder: RunFolder,
    val shownRuns: IndexedSeq[DatasetRun],
    val nodes: IndexedSeq[NodeId],
    val edges: IndexedSeq[(NodeId, NodeId)],
    val sources: Map[NodeId.Input, InputSource]
) {
  private val incoming: Map[NodeId, Seq[NodeId]] = edges.groupBy(_._2).map { case (k, v) => k -> v.map(_._1) }
  private val outgoing: Map[NodeId, Seq[NodeId]] = edges.groupBy(_._1).map { case (k, v) => k -> v.map(_._2) }

  def upstream(n: NodeId): Seq[NodeId] = incoming.getOrElse(n, Nil)
  def downstream(n: NodeId): Seq[NodeId] = outgoing.getOrElse(n, Nil)

  /** Every node `n` derives from, across datasets: its ancestors. */
  def ancestors(n: NodeId): Set[NodeId] = closure(n, upstream)

  /** Every node derived from `n`, across datasets: its descendants. */
  def descendants(n: NodeId): Set[NodeId] = closure(n, downstream)

  /** `n`'s whole lineage: itself, what it derives from and what derives from
    * it. */
  def around(n: NodeId): Set[NodeId] = ancestors(n) ++ descendants(n) + n

  /** The whole lineage of every node of `dataset`. */
  def aroundDataset(dataset: Int): Set[NodeId] = nodes.filter(_.dataset == dataset).toSet.flatMap(around)

  private def closure(start: NodeId, next: NodeId => Seq[NodeId]): Set[NodeId] = {
    val seen = mutable.LinkedHashSet.empty[NodeId]
    val todo = mutable.Stack[NodeId](start)
    while (todo.nonEmpty) next(todo.pop()).foreach(m => if (m != start && seen.add(m)) todo.push(m))
    seen.toSet
  }

  /** The datasets whose tables an input of `dataset` reads, itself apart. */
  def upstreamDatasets(dataset: Int): Seq[Int] = datasetEdges.collect { case (a, b) if b == dataset => a }.distinct

  def downstreamDatasets(dataset: Int): Seq[Int] = datasetEdges.collect { case (a, b) if a == dataset => b }.distinct

  /** `(a, b)` for each dataset b with an input that reads a table of a. */
  lazy val datasetEdges: Seq[(Int, Int)] =
    edges.collect { case (t: NodeId.Table, i: NodeId.Input) if t.dataset != i.dataset => (t.dataset, i.dataset) }
      .distinct

  def inputRecord(n: NodeId.Input): Option[JobInputRecord] = shownRuns(n.dataset).inputs.find(_.name == n.name)
}

object Lineage {

  /** The lineage of `folder` with each dataset shown at the run `shown` picks
    * (its index into the dataset's runs; the newest when absent). */
  def build(folder: RunFolder, shown: Map[Int, Int] = Map.empty): Lineage = {
    val runs = folder.datasets.indices.map(d =>
      folder.datasets(d).runs(shown.getOrElse(d, 0).max(0).min(folder.datasets(d).runs.size - 1)))
    val index = new TableIndex(folder, runs)
    val nodes = mutable.ArrayBuffer.empty[NodeId]
    val edges = mutable.ArrayBuffer.empty[(NodeId, NodeId)]
    val sources = mutable.LinkedHashMap.empty[NodeId.Input, InputSource]
    runs.indices.foreach { d =>
      val run = runs(d)
      run.inputs.foreach { in =>
        val node = NodeId.Input(d, in.name)
        nodes += node
        val source = index.sourceOf(in)
        sources(node) = source
        source match {
          case InputSource.Tables(ms) => ms.filter(_.inShownRun).foreach(m => edges += (NodeId.Table(m.dataset, m.table) -> node))
          case InputSource.External => ()
        }
      }
      val tableNames = run.tables.map(_.name).toSet
      val inputNames = run.inputs.map(_.name).toSet
      run.tables.foreach { t =>
        val node = NodeId.Table(d, t.name)
        nodes += node
        t.summary.readsInputs.filter(inputNames).foreach(i => edges += (NodeId.Input(d, i) -> node))
        t.summary.readsTasks.filter(tableNames).foreach(u => edges += (NodeId.Table(d, u) -> node))
      }
    }
    new Lineage(folder, runs, nodes.toVector, edges.distinct.toVector, sources.toMap)
  }

  /** Whether `path` is a glob rather than a path. */
  private def isGlob(path: String): Boolean = path.exists(c => c == '*' || c == '?' || c == '[' || c == '{')

  /** Every run's table directories, under each spelling a path to them may
    * take, for matching inputs against. */
  private final class TableIndex(folder: RunFolder, shownRuns: IndexedSeq[DatasetRun]) {
    // (spelling of a directory, dataset, table, run)
    private val entries: Seq[(String, Int, String, DatasetRun)] =
      folder.datasets.indices.flatMap { d =>
        folder.datasets(d).runs.flatMap { run =>
          run.tables.flatMap { t =>
            val spellings = (t.recordedDir.toSeq ++ t.dir.map(_.toString).toSeq).flatMap(spellingsOf).distinct
            spellings.map(s => (s, d, t.name, run))
          }
        }
      }
    private val byDir: Map[String, Seq[(Int, String, DatasetRun)]] =
      entries.groupBy(_._1).map { case (k, v) => k -> v.map(e => (e._2, e._3, e._4)).distinct }

    def sourceOf(in: JobInputRecord): InputSource = {
      if (in.path.startsWith("gs://") || in.path.startsWith("s3://")) return InputSource.External
      val found =
        if (isGlob(in.path)) globMatches(in.path)
        else spellingsOf(in.path).flatMap { p =>
          // The table's directory, or a file in it.
          byDir.getOrElse(p, Nil) ++ parentOf(p).toSeq.flatMap(byDir.getOrElse(_, Nil))
        }.distinct
      if (found.isEmpty) InputSource.External
      else InputSource.Tables(found.map { case (d, t, run) =>
        val shown = shownRuns(d)
        TableMatch(d, t, run.dir, shown = run eq shown, inShownRun = shown.table(t).isDefined)
      }.sortBy(m => (!m.shown, m.dataset, m.table)).distinctBy(m => (m.dataset, m.table)))
    }

    /** The tables a glob reads: those whose directory, or a part file in it,
      * it matches. */
    private def globMatches(glob: String): Seq[(Int, String, DatasetRun)] = {
      val matchers = spellingsOf(glob).flatMap(g =>
        try Some(FileSystems.getDefault.getPathMatcher("glob:" + g)) catch { case NonFatal(_) => None })
      entries.filter { case (dir, _, _, _) =>
        val p = Paths.get(dir)
        matchers.exists(m => m.matches(p) ||
          Seq("part-00000.csv", "part-00000.parquet").exists(f => m.matches(p.resolve(f))))
      }.map(e => (e._2, e._3, e._4)).distinct
    }
  }

  /** `path` as written, normalized, and with symbolic links resolved in the
    * longest part of it that exists, so `/tmp/x` and `/private/tmp/x` meet. */
  private[viewer] def spellingsOf(path: String): Seq[String] =
    try {
      val p = Paths.get(path).toAbsolutePath.normalize()
      Seq(path, p.toString, realPath(p).toString).distinct
    } catch { case NonFatal(_) => Seq(path) }

  /** `p` with links resolved in its longest existing prefix: a glob's
    * wildcards, or a directory since deleted, stay as written after it. */
  private def realPath(p: Path): Path = {
    var existing = p
    val rest = mutable.ArrayBuffer.empty[String]
    while (existing != null && !Files.exists(existing)) {
      Option(existing.getFileName).foreach(n => rest.prepend(n.toString))
      existing = existing.getParent
    }
    if (existing == null) p
    else try rest.foldLeft(existing.toRealPath())(_ resolve _) catch { case NonFatal(_) => p }
  }

  private def parentOf(path: String): Option[String] =
    try Option(Paths.get(path).getParent).map(_.toString) catch { case NonFatal(_) => None }
}
