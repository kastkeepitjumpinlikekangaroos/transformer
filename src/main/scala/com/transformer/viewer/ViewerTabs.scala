package com.transformer.viewer

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.gui.{DataGrid, FxHelpers, Icons, QueryConsole, RowSample}
import com.transformer.job.TaskRunStatus

import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Button, Label, Tab, TabPane, Tooltip}
import javafx.scene.layout.{HBox, Priority, Region, VBox}
import javafx.stage.Stage

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.control.NonFatal

/** The tabs below the lineage: Details, a Query tab, a tab per sample of a
  * table's rows, and any more query tabs. A sample or query tab closes; the
  * first two stay. Each query tab is a [[QueryConsole]] over the folder's
  * catalog ([[QueryCatalog]]), as it is when the query runs. */
final class ViewerTabs(session: ViewerSession, owner: () => Stage) extends TabPane {

  val details = new DetailsPanel(session)
  private val detailsTab = new Tab("Details", details)
  detailsTab.setClosable(false)

  private var queryCount = 0
  private val firstQuery = console("SELECT *\nFROM <dataset>__<table>\nLIMIT 100")
  private val queryTab = new Tab("Query", padded(firstQuery))
  queryTab.setClosable(false)

  getTabs.addAll(detailsTab, queryTab)
  getStyleClass.add("results-tabs")
  setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS)

  private val samples = mutable.LinkedHashMap.empty[NodeId, (Tab, SamplePane)]
  private var lastLineage: Option[Lineage] = None

  details.setOnSample(openSample)
  details.setOnQuery(queryInNewTab)

  // Another run, or the folder read again: a sample shows the run now shown,
  // and one of a table that is gone closes.
  session.addListener(() => if (session.lineage ne lastLineage.orNull) {
    lastLineage = session.lineage
    samples.toSeq.foreach { case (node, (tab, pane)) =>
      if (session.lineage.exists(_.nodes.contains(node)) && session.queryCatalog.sqlName(node).isDefined) pane.load()
      else getTabs.remove(tab)
    }
  })

  def showDetails(): Unit = getSelectionModel.select(detailsTab)

  /** A tab of `node`'s first rows: its tab if one is open, else a new one. */
  def openSample(node: NodeId): Unit = samples.get(node) match {
    case Some((tab, _)) => getSelectionModel.select(tab)
    case None if session.queryCatalog.sqlName(node).isDefined =>
      val pane = new SamplePane(session, node, () => queryInNewTab(node))
      val tab = new Tab(node.name, pane)
      tab.setGraphic(node match {
        case _: NodeId.Table => Icons.table
        case _: NodeId.Input => Icons.database
      })
      tab.setTooltip(new Tooltip(session.queryCatalog.sqlName(node).getOrElse(node.name)))
      tab.setOnClosed(_ => samples.remove(node))
      samples(node) = (tab, pane)
      getTabs.add(tab)
      getSelectionModel.select(tab)
      pane.load()
    case None => ()
  }

  /** A new query tab that reads `node`'s first hundred rows, and runs it. */
  def queryInNewTab(node: NodeId): Unit = session.queryCatalog.sqlName(node).foreach { name =>
    val c = newQueryTab(s"SELECT *\nFROM $name\nLIMIT 100")
    c.runQuery()
  }

  /** A new, empty query tab, or one holding `sql`. */
  def newQueryTab(sql: String = ""): QueryConsole = {
    val c = console(sql)
    queryCount += 1
    val tab = new Tab(s"Query ${queryCount + 1}", padded(c))
    getTabs.add(tab)
    getSelectionModel.select(tab)
    c.focusEditor()
    c
  }

  private def console(sql: String): QueryConsole = {
    val c = new QueryConsole(
      owner,
      prepare = () => {
        val catalog = session.queryCatalog
        sql => catalog.catalogFor(sql)
      },
      persistDir = () => session.folder.map(_.root.resolve("interactive").toString))
    c.setQuery(sql)
    c
  }

  private def padded(node: javafx.scene.Node): VBox = {
    val box = new VBox(node)
    box.getStyleClass.add("panel")
    box.setPadding(new Insets(12, 14, 12, 14))
    VBox.setVgrow(node, Priority.ALWAYS)
    box
  }
}

/** A sample of a table's rows, or an outside input's: the first
  * [[DataGrid.PreviewRows]] of them, read on a worker as a query reads them,
  * with a line saying what they are and Query and Reload beside it. */
final class SamplePane(session: ViewerSession, node: NodeId, query: () => Unit) extends VBox {

  getStyleClass.add("panel")

  private val status = new Label("")
  status.getStyleClass.add("muted")
  status.setMinWidth(0)
  private val queryButton = new Button("Query", Icons.play)
  queryButton.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
  queryButton.setTooltip(new Tooltip("Query it in a new tab"))
  queryButton.setOnAction(_ => query())
  private val reloadButton = new Button("Reload", Icons.refresh)
  reloadButton.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
  reloadButton.setTooltip(new Tooltip("Read the rows again"))
  reloadButton.setOnAction(_ => load())
  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)
  private val bar = new HBox(8, status, spacer, queryButton, reloadButton)
  bar.setAlignment(Pos.CENTER_LEFT)
  bar.setPadding(new Insets(8, 12, 8, 12))
  private val grid = new DataGrid()
  grid.getStyleClass.add(Tweaks.EDGE_TO_EDGE)
  grid.setPlaceholderText("Reading…")
  VBox.setVgrow(grid, Priority.ALWAYS)
  getChildren.addAll(bar, grid)

  private val token = new AtomicLong()

  def load(): Unit = {
    val t = token.incrementAndGet()
    val catalog = session.queryCatalog
    val name = catalog.sqlName(node).getOrElse(node.name)
    val total = node match {
      case n: NodeId.Table => session.shownRun(n.dataset).flatMap(_.table(n.name)).map(_.summary)
        .filter(s => s.status == TaskRunStatus.Succeeded || s.status == TaskRunStatus.ValidationFailed)
        .map(_.rowsProduced)
      case _ => None
    }
    status.setText(s"$name · reading…")
    grid.clear()
    val worker = new Thread(() => {
      val result: Either[String, RowSample] =
        try catalog.view(node).map(v => Right(DataGrid.sample(v))).getOrElse(Left(s"$name has no rows to read"))
        catch { case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
      FxHelpers.onFx {
        if (token.get() == t) result match {
          case Right(sample) =>
            grid.show(sample.schema, sample.rows)
            val shown = sample.rows.size
            val rows = total match {
              case Some(n) if n <= shown => f"all $n%,d rows"
              case Some(n) => f"the first $shown%,d of $n%,d rows"
              case None if sample.truncated => f"the first $shown%,d rows"
              case None => f"all $shown%,d rows"
            }
            status.setText(s"$name · $rows · ${sample.schema.length} columns")
          case Left(err) =>
            grid.setPlaceholderText(err)
            status.setText(s"$name · could not be read")
        }
      }
    }, "transformer-viewer-sample")
    worker.setDaemon(true)
    worker.start()
  }
}
