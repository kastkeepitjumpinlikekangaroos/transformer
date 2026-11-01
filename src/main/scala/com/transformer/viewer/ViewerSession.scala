package com.transformer.viewer

import com.transformer.core.{Catalog, CatalogView}
import com.transformer.job.{InputFilePath, InputResolver, JobInputRecord, TaskDag, TaskRunRecord, TaskRunStatus}

import java.nio.file.{Files, Path}
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.util.control.NonFatal

/** What the viewer has selected: a dataset's box, or a node in it. */
sealed trait ViewerSelection
object ViewerSelection {
  final case class OfDataset(dataset: Int) extends ViewerSelection
  final case class OfNode(node: NodeId) extends ViewerSelection
}

/** The data viewer's state, which every panel reads: the folder it has open,
  * the run it shows of each dataset, the lineage and layout of those runs,
  * what is selected, and the catalog queries run against.
  *
  * Like the job GUI's `JobSession` it has no JavaFX in it, so tests reach it
  * without the toolkit, and it is the FX thread's: a mutator runs there and
  * calls each listener, in the order they were added. Opening a folder reads
  * the disk, so the window scans on a worker ([[RunFolder.scan]]) and hands
  * the result to [[open]].
  */
final class ViewerSession {

  private var _folder: Option[RunFolder] = None
  private var _scanning: Option[Path] = None
  private var _shown: Map[Int, Int] = Map.empty
  private var _lineage: Option[Lineage] = None
  private var _layout: Option[LineageLayout] = None
  private var _catalog: QueryCatalog = QueryCatalog.empty
  private var _selection: Option[ViewerSelection] = None

  private val listeners = mutable.ArrayBuffer.empty[() => Unit]
  def addListener(f: () => Unit): Unit = listeners += f
  private def notifyListeners(): Unit = listeners.foreach(f => try f() catch { case NonFatal(_) => () })

  def folder: Option[RunFolder] = _folder
  def datasets: Vector[Dataset] = _folder.map(_.datasets).getOrElse(Vector.empty)
  /** The folder being scanned, until [[open]] gets what was found. */
  def scanning: Option[Path] = _scanning
  def lineage: Option[Lineage] = _lineage
  def layout: Option[LineageLayout] = _layout
  def selection: Option[ViewerSelection] = _selection
  /** What queries run against: the shown runs' tables and outside inputs. */
  def queryCatalog: QueryCatalog = _catalog

  /** The index into `datasets(d).runs` of the run shown: the newest unless
    * another was picked. */
  def shownRunIndex(d: Int): Int = _shown.getOrElse(d, 0)
  def shownRun(d: Int): Option[DatasetRun] = _lineage.flatMap(_.shownRuns.lift(d))

  def beginScan(root: Path): Unit = {
    _scanning = Some(root)
    notifyListeners()
  }

  /** Show `folder`, each dataset at its newest run, nothing selected. */
  def open(folder: RunFolder): Unit = {
    _scanning = None
    _folder = Some(folder)
    _shown = Map.empty
    _selection = None
    rebuild()
    notifyListeners()
  }

  /** Show dataset `d` at its run `run` (an index into its runs). The
    * selection stays where it still names something. */
  def showRun(d: Int, run: Int): Unit =
    if (d >= 0 && d < datasets.size && run >= 0 && run < datasets(d).runs.size && shownRunIndex(d) != run) {
      _shown = _shown.updated(d, run)
      rebuild()
      _selection = _selection.filter {
        case ViewerSelection.OfNode(n) => _lineage.exists(_.nodes.contains(n))
        case ViewerSelection.OfDataset(_) => true
      }
      notifyListeners()
    }

  def select(sel: Option[ViewerSelection]): Unit =
    if (sel != _selection) {
      _selection = sel
      notifyListeners()
    }

  private def rebuild(): Unit = _folder match {
    case Some(f) =>
      val lineage = Lineage.build(f, _shown)
      _lineage = Some(lineage)
      _layout = Some(LineageLayout.compute(lineage))
      _catalog = QueryCatalog(lineage)
    case None =>
      _lineage = None
      _layout = None
      _catalog = QueryCatalog.empty
  }
}

/** A name a query reads and what it names: a table a shown run wrote, or an
  * input a shown run read from outside the folder. */
sealed trait QueryEntry {
  def sqlName: String
  def node: NodeId
}
object QueryEntry {
  final case class Table(sqlName: String, node: NodeId.Table, dir: Path) extends QueryEntry
  final case class Source(sqlName: String, node: NodeId.Input, input: JobInputRecord) extends QueryEntry
}

/** The names queries read in the viewer, one per table: `<dataset>__<table>`,
  * as `jaffle_shop__customers`, each part as SQL reads it without quotes
  * ([[RunFolder.sqlIdentifier]]). The catalog is flat, so a dataset and its
  * table make one name; the double underscore keeps the two apart. An input
  * a run read from outside the folder is `<dataset>__<input>`; one that is a
  * table of the folder goes by that table's name.
  *
  * A query's catalog holds the views it reads ([[catalogFor]]), each opened
  * once and kept: a table reads as the tasks after it read it
  * ([[TaskRunRecord.outputView]]), an input as its run read it.
  */
final class QueryCatalog private (val entries: Vector[QueryEntry], unavailable: Map[String, String]) {

  private val byName: Map[String, QueryEntry] = entries.map(e => e.sqlName.toLowerCase(Locale.ROOT) -> e).toMap
  private val byNode: Map[NodeId, QueryEntry] = entries.map(e => e.node -> e).toMap
  private val views = new ConcurrentHashMap[String, CatalogView]()

  def sqlName(node: NodeId): Option[String] = byNode.get(node).map(_.sqlName)
  def entry(node: NodeId): Option[QueryEntry] = byNode.get(node)

  /** The catalog for `sql`: a view under each name it reads. A name no
    * table has is an error naming the ones that come closest, and one
    * written as `dataset.table` says how to write it here. Text that does
    * not parse gives an empty catalog, and the query its own error. */
  def catalogFor(sql: String): Catalog = {
    val cat = new Catalog
    val read = try TaskDag.viewsRead(sql) catch { case NonFatal(_) => return cat }
    read.toSeq.sorted.foreach { name =>
      byName.get(name) match {
        case Some(e) => cat.register(e.sqlName, view(e))
        case None => throw new IllegalArgumentException(unknown(name))
      }
    }
    cat
  }

  /** The view under `node`'s name, opened once. */
  def view(node: NodeId): Option[CatalogView] = byNode.get(node).map(view)

  private def view(e: QueryEntry): CatalogView = {
    val key = e.sqlName.toLowerCase(Locale.ROOT)
    val cached = views.get(key)
    if (cached != null) cached
    else {
      val v = e match {
        case QueryEntry.Table(_, _, dir) =>
          val rec = TaskRunRecord.read(dir).getOrElse(throw new IllegalArgumentException(
            s"${e.sqlName}: no readable _run.json in $dir"))
          TaskRunRecord.outputView(dir, rec)
        case QueryEntry.Source(name, _, in) =>
          InputResolver.resolve(InputFilePath(in.path, name, in.options, cache = false, format = Some(in.format)))
      }
      views.putIfAbsent(key, v)
      views.get(key)
    }
  }

  private def unknown(name: String): String = {
    unavailable.get(name).getOrElse {
      val dot = name.indexOf('.')
      val qualified =
        if (dot > 0) byName.get(name.substring(0, dot) + "__" + name.substring(dot + 1)).map(e =>
          s"Write ${e.sqlName} for $name: the viewer names a dataset's table <dataset>__<table>")
        else None
      qualified.getOrElse {
        // A dataset's table of that very name, else names that hold it.
        val names = entries.map(_.sqlName)
        val exact = names.filter(_.toLowerCase(Locale.ROOT).endsWith("__" + name))
        val close = (if (exact.nonEmpty) exact else names.filter(_.toLowerCase(Locale.ROOT).contains(name))).take(5)
        val hint =
          if (close.nonEmpty) s"; did you mean ${close.mkString(", ")}?"
          else entries.headOption.map(e => s"; a table here is named <dataset>__<table>, such as ${e.sqlName}")
            .getOrElse("; this folder has no tables")
        s"No table named '$name' in this folder$hint"
      }
    }
  }
}

object QueryCatalog {
  val empty: QueryCatalog = new QueryCatalog(Vector.empty, Map.empty)

  /** The names of `lineage`'s shown runs. A table whose task wrote no output
    * in the shown run, or whose directory is gone, has no view; a query that
    * reads it is told why. */
  def apply(lineage: Lineage): QueryCatalog = {
    val entries = mutable.ArrayBuffer.empty[QueryEntry]
    val unavailable = mutable.Map.empty[String, String]
    lineage.shownRuns.indices.foreach { d =>
      val dataset = lineage.folder.datasets(d)
      val run = lineage.shownRuns(d)
      val externals = run.inputs.filter(in => lineage.sources.get(NodeId.Input(d, in.name)).contains(InputSource.External))
      val local = RunFolder.uniqueNames(
        run.tables.map(t => RunFolder.sqlIdentifier(t.name)) ++ externals.map(in => RunFolder.sqlIdentifier(in.name)))
      val names = local.map(n => s"${dataset.sqlName}__$n")
      run.tables.zip(names).foreach { case (t, name) =>
        val wrote = t.summary.status == TaskRunStatus.Succeeded || t.summary.status == TaskRunStatus.ValidationFailed
        t.dir.filter(dir => wrote && Files.isDirectory(dir)) match {
          case Some(dir) => entries += QueryEntry.Table(name, NodeId.Table(d, t.name), dir)
          case None =>
            unavailable(name.toLowerCase(Locale.ROOT)) =
              if (!wrote) s"$name has no rows on disk: its task ${TaskRunStatus.toJson(t.summary.status)} in the run shown"
              else s"$name has no output on disk: ${t.recordedDir.getOrElse("its directory")} is gone"
        }
      }
      externals.zip(names.drop(run.tables.size)).foreach { case (in, name) =>
        entries += QueryEntry.Source(name, NodeId.Input(d, in.name), in)
      }
    }
    new QueryCatalog(entries.toVector, unavailable.toMap)
  }
}
