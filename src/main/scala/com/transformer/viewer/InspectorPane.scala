package com.transformer.viewer

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.core.{DataType, Schema}
import com.transformer.gui.{DataGrid, FxHelpers, Icons, RowSample, Tone}
import com.transformer.job.TaskRunStatus

import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.collections.FXCollections
import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.{Button, ComboBox, ContextMenu, Hyperlink, Label, MenuItem, SelectionMode, Tab, TabPane,
  TableCell, TableColumn, TableRow, TableView, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyCodeCombination, KeyCombination, KeyEvent}
import javafx.scene.layout.{HBox, Priority, Region, StackPane, VBox}
import javafx.scene.shape.Circle

import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.control.NonFatal

/** An inspector: a table, an input or a dataset, with a header naming it,
  * its status and what to do with it, and a tab per [[InspectorView]] of it.
  *
  * It goes from subject to subject (`show`), keeping the view it is on where
  * the next subject has it, so clicking through tables on Columns compares
  * their columns and clicking through a graph on Lineage follows the graph.
  * Back and Forward go through the subjects it has shown. A view is built
  * when it is first turned to, so a table's rows are read only when its Data
  * shows. `open` opens another subject and `query` queries one in a new tab,
  * as the workspace does them; `changed` says the subject changed.
  */
final class InspectorPane(
    session: ViewerSession,
    open: (ViewerSelection, Option[InspectorView]) => Unit,
    query: NodeId => Unit,
    changed: () => Unit
) extends VBox {

  getStyleClass.addAll("panel", "inspector")

  private val history = new InspectorHistory

  /** What the inspector shows, once it has been shown something. */
  def subject: ViewerSelection = history.current.map(_._1).getOrElse(ViewerSelection.OfDataset(0))
  def view: InspectorView = history.current.map(_._2).getOrElse(InspectorView.Data)

  // ---- the header ----
  private def iconButton(icon: Node, tip: String)(f: => Unit): Button = {
    val b = new Button(null, icon)
    b.getStyleClass.addAll(Styles.FLAT, Styles.BUTTON_ICON, Styles.SMALL)
    b.setTooltip(new Tooltip(tip))
    b.setFocusTraversable(false)
    b.setOnAction(_ => f)
    b
  }
  private val backButton = iconButton(Icons.back, "Back (⌘[)")(back())
  private val forwardButton = iconButton(Icons.forward, "Forward (⌘])")(forward())
  private val icon = new StackPane()
  icon.getStyleClass.add("inspector-icon")
  private val title = new Label()
  title.getStyleClass.add("inspector-title")
  title.setMinWidth(0)
  private val pill = new Label()
  pill.setMinWidth(Region.USE_PREF_SIZE)
  private val headerSpacer = new Region()
  HBox.setHgrow(headerSpacer, Priority.ALWAYS)
  private val actions = new HBox(6)
  actions.setAlignment(Pos.CENTER_RIGHT)
  actions.setMinWidth(Region.USE_PREF_SIZE)
  private val titleLine = new HBox(8, icon, title, pill)
  titleLine.setAlignment(Pos.CENTER_LEFT)
  private val crumbs = new HBox(6)
  crumbs.getStyleClass.add("inspector-crumbs")
  crumbs.setAlignment(Pos.CENTER_LEFT)
  private val naming = new VBox(4, titleLine, crumbs)
  naming.setMinWidth(0)
  private val historyButtons = new HBox(2, backButton, forwardButton)
  historyButtons.setAlignment(Pos.CENTER_LEFT)
  private val header = new HBox(10, historyButtons, naming, headerSpacer, actions)
  header.setAlignment(Pos.CENTER_LEFT)
  header.getStyleClass.add("inspector-header")

  // ---- the views ----
  private val views = new TabPane()
  views.getStyleClass.add("inspector-views")
  views.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE)
  VBox.setVgrow(views, Priority.ALWAYS)
  private val viewTabs = mutable.LinkedHashMap.empty[InspectorView, Tab]
  /** Set while the views' tabs are put in place, so the selection that moves
    * meanwhile is not taken for the user's. */
  private var placing = false
  private val lineage = new LineageOverlay(session)
  lineage.canvas.setOnOpen(s => open(s, Some(InspectorView.Lineage)))

  getChildren.addAll(header, views)

  views.getSelectionModel.selectedItemProperty().addListener((_, _, tab) =>
    if (!placing && tab != null) viewTabs.collectFirst { case (v, t) if t eq tab => v }.foreach { v =>
      history.turn(v)
      fill(v)
    })

  /** Show `sel`, at `view` when given (else at the view shown, where `sel`
    * has it), as a step Back returns from. */
  def show(sel: ViewerSelection, view: Option[InspectorView] = None): Unit =
    if (history.current.exists(_._1 == sel)) view.foreach(selectView)
    else {
      history.show(sel, view.getOrElse(this.view))
      render()
    }

  def selectView(v: InspectorView): Unit = viewTabs.get(v).foreach(t => views.getSelectionModel.select(t))

  def back(): Unit = if (history.back().isDefined) {
    render()
    changed()
  }

  def forward(): Unit = if (history.forward().isDefined) {
    render()
    changed()
  }

  /** Show what the session holds now: each subject Back and Forward reach as
    * `move` finds it there, and those it does not find dropped. False when
    * the subject shown is not found, and the tab should close. */
  def update(move: ViewerSelection => Option[ViewerSelection]): Boolean =
    history.update(move) && { render(); true }

  /** The lineage canvas, when the Lineage view shows. */
  def lineageCanvas: Option[LineageCanvas] = if (view == InspectorView.Lineage) Some(lineage.canvas) else None

  /** Build the header and the views for the subject; the view shown is
    * filled now, the rest when turned to. */
  private def render(): Unit = {
    renderHeader()
    val wanted = InspectorView.of(session, subject)
    if (!wanted.contains(view)) history.turn(wanted.head)
    placing = true
    try {
      if (wanted != viewTabs.keys.toSeq) {
        viewTabs.clear()
        views.getTabs.setAll(wanted.map { v =>
          val t = new Tab(v.title)
          viewTabs(v) = t
          t
        }: _*)
      }
      viewTabs.values.foreach(_.setContent(null))
      views.getSelectionModel.select(viewTabs(view))
    } finally placing = false
    fill(view)
  }

  /** Build view `v` for the subject, unless it is built. */
  private def fill(v: InspectorView): Unit = viewTabs.get(v).filter(_.getContent == null).foreach { t =>
    t.setContent((v, subject) match {
      case (InspectorView.Data, ViewerSelection.OfNode(n)) =>
        val p = new SamplePane(session, n)
        p.load()
        p
      case (InspectorView.Columns, ViewerSelection.OfNode(n)) => new ColumnsPane(session, n)
      case (InspectorView.Tables, ViewerSelection.OfDataset(d)) => new TablesPane(session, d, s => open(s, None))
      case (InspectorView.Lineage, s) =>
        lineage.show(s)
        lineage
      case (_, s) => new DetailsPanel(session, s, x => open(x, Some(InspectorView.Details)),
        () => selectView(InspectorView.Lineage))
    })
  }

  private def renderHeader(): Unit = {
    backButton.setDisable(!history.canGoBack)
    forwardButton.setDisable(!history.canGoForward)
    actions.getChildren.clear()
    crumbs.getChildren.clear()
    pill.getStyleClass.setAll("label", "pill")
    for (l <- session.lineage) subject match {
      case ViewerSelection.OfNode(n) =>
        val run = l.shownRuns(n.dataset)
        title.setText(n.name)
        crumbs.getChildren.add(datasetLink(l, n.dataset))
        n match {
          case t: NodeId.Table =>
            icon.getChildren.setAll(Icons.table)
            run.table(t.name) match {
              case Some(info) =>
                val rows = info.summary.rowsProduced
                setPill(DetailsPanel.statusText(info.status), DetailsPanel.tone(info.status))
                crumb(runText(l, n.dataset))
                if (info.wrote) crumb(f"$rows%,d ${if (rows == 1) "row" else "rows"}")
              case None => pill.setVisible(false)
            }
          case i: NodeId.Input =>
            icon.getChildren.setAll(Icons.database)
            setPill("INPUT", Tone.Input)
            l.sources.get(i) match {
              case Some(InputSource.Tables(ms)) =>
                crumb("an input, the output of " + ms.map(m => s"${l.folder.datasets(m.dataset).name}.${m.table}").mkString(", "))
              case _ => crumb("an input read from outside the folder")
            }
            l.inputRecord(i).foreach(r => crumb(r.format.toUpperCase(Locale.ROOT)))
        }
        session.queryCatalog.sqlName(n).foreach { name =>
          val q = new Button("Query", Icons.play)
          q.getStyleClass.addAll(Styles.SMALL, Styles.ACCENT)
          q.setTooltip(new Tooltip(s"Query $name in a new tab"))
          q.setOnAction(_ => query(n))
          val copy = new Button(null, Icons.copy)
          copy.getStyleClass.addAll(Styles.SMALL, Styles.FLAT, Styles.BUTTON_ICON)
          copy.setTooltip(new Tooltip(s"Copy its SQL name, $name"))
          copy.setOnAction(_ => {
            val content = new ClipboardContent()
            content.putString(name)
            Clipboard.getSystemClipboard.setContent(content)
            ()
          })
          val nameLabel = new Label(name)
          nameLabel.getStyleClass.addAll("mono", "sql-name")
          actions.getChildren.addAll(nameLabel, copy, q)
        }
      case ViewerSelection.OfDataset(d) =>
        val dataset = l.folder.datasets(d)
        val run = l.shownRuns(d)
        icon.getChildren.setAll(Icons.folder)
        title.setText(dataset.name)
        val (_, verdict) = LineageCanvas.runStatus(run)
        setPill(verdict.toUpperCase(Locale.ROOT).takeWhile(_ != ' '),
          if (!run.record.succeeded) Tone.Danger else if (run.record.warnings.nonEmpty) Tone.Warning else Tone.Success)
        crumb(runText(l, d))
        crumb(s"${run.tables.size} ${if (run.tables.size == 1) "table" else "tables"}")
        if (dataset.runs.size > 1) actions.getChildren.addAll(runPicker(d): _*)
    }
  }

  private def datasetLink(l: Lineage, d: Int): Hyperlink = {
    val link = new Hyperlink(l.folder.datasets(d).name, Icons.folder)
    link.getStyleClass.add("crumb-link")
    link.setOnAction(_ => open(ViewerSelection.OfDataset(d), None))
    link
  }

  private def crumb(text: String): Unit = {
    if (!crumbs.getChildren.isEmpty) {
      val dot = new Label("·")
      dot.getStyleClass.add("crumb")
      crumbs.getChildren.add(dot)
    }
    val l = new Label(text)
    l.getStyleClass.add("crumb")
    crumbs.getChildren.add(l)
  }

  /** When dataset `d`'s run shown ran, and which of its runs it is. */
  private def runText(l: Lineage, d: Int): String = {
    val runs = l.folder.datasets(d).runs
    val run = l.shownRuns(d)
    val which =
      if (runs.size == 1) ""
      else if (run eq runs.head) s" (the latest of ${runs.size})"
      else s" (run ${runs.size - runs.indexOf(run)} of ${runs.size})"
    s"run of ${LineageCanvas.RunTime.format(run.record.executionTime)}$which"
  }

  private def runPicker(d: Int): Seq[Node] = {
    val dataset = session.datasets(d)
    val combo = new ComboBox[String]()
    combo.getStyleClass.add(Styles.SMALL)
    dataset.runs.zipWithIndex.foreach { case (r, i) =>
      val (_, verdict) = LineageCanvas.runStatus(r)
      combo.getItems.add(s"${LineageCanvas.RunTime.format(r.record.executionTime)} · $verdict · ${r.dir.getFileName}" +
        (if (i == 0) " (latest)" else ""))
    }
    combo.getSelectionModel.select(session.shownRunIndex(d))
    combo.getSelectionModel.selectedIndexProperty().addListener((_, _, i) =>
      if (i.intValue >= 0) session.showRun(d, i.intValue))
    combo.setTooltip(new Tooltip("The run of this dataset that the viewer shows and queries read"))
    val label = new Label("RUN")
    label.getStyleClass.add("section-label")
    Seq(label, combo)
  }

  private def setPill(text: String, t: Tone): Unit = {
    pill.setText(text)
    pill.getStyleClass.setAll("label", "pill", t.css)
    pill.setVisible(true)
  }
}

/** A table's first rows, or an outside input's: the first
  * [[DataGrid.PreviewRows]] of them, read on a worker as a query reads them,
  * with a line saying what they are and Reload beside it. */
final class SamplePane(session: ViewerSession, node: NodeId) extends VBox {

  getStyleClass.add("panel")

  private val status = new Label("")
  status.getStyleClass.add("muted")
  status.setMinWidth(0)
  private val reloadButton = new Button("Reload", Icons.refresh)
  reloadButton.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
  reloadButton.setTooltip(new Tooltip("Read the rows again"))
  reloadButton.setOnAction(_ => load())
  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)
  private val bar = new HBox(8, status, spacer, reloadButton)
  bar.setAlignment(Pos.CENTER_LEFT)
  bar.setPadding(new Insets(6, 12, 6, 14))
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
      case n: NodeId.Table => session.shownRun(n.dataset).flatMap(_.table(n.name))
        .filter(_.wrote).map(_.summary.rowsProduced)
      case _ => None
    }
    status.setText("Reading…")
    grid.clear()
    grid.setPlaceholderText("Reading…")
    val worker = new Thread(() => {
      val result: Either[String, RowSample] =
        try catalog.view(node).map(v => Right(DataGrid.sample(v)))
          .getOrElse(Left(catalog.unreadable(node).getOrElse(s"$name has no rows to read")))
        catch { case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
      FxHelpers.onFx {
        if (token.get() == t) result match {
          case Right(sample) =>
            grid.show(sample.schema, sample.rows)
            val shown = sample.rows.size
            val rows = total match {
              case Some(n) if n <= shown => f"All $n%,d ${if (n == 1) "row" else "rows"}"
              case Some(n) => f"The first $shown%,d of $n%,d rows"
              case None if sample.truncated => f"The first $shown%,d rows"
              case None => f"All $shown%,d ${if (shown == 1) "row" else "rows"}"
            }
            val columns = sample.schema.length
            status.setText(s"$rows · $columns ${if (columns == 1) "column" else "columns"}")
          case Left(err) =>
            grid.setPlaceholderText(err)
            status.setText("No rows to show")
        }
      }
    }, "transformer-viewer-sample")
    worker.setDaemon(true)
    worker.start()
  }

}

/** One column of a table, as the Columns view lists it. */
final case class ColumnRow(position: Int, name: String, typeName: String)

/** A table's columns, or an outside input's: a row each, its position, name
  * and type. They come from the table's `_run.json`, else from reading the
  * data, on a worker. ⌘C copies the selected columns' names, as a select
  * list writes them. */
final class ColumnsPane(session: ViewerSession, node: NodeId) extends VBox {

  getStyleClass.add("panel")

  private val status = new Label("")
  status.getStyleClass.add("muted")
  status.setPadding(new Insets(6, 12, 6, 14))
  status.setMinHeight(Region.USE_PREF_SIZE)

  private val table = new TableView[ColumnRow]()
  table.getStyleClass.addAll("data-table", "columns-table", Styles.STRIPED, Styles.DENSE, Tweaks.EDGE_TO_EDGE)
  table.getSelectionModel.setSelectionMode(SelectionMode.MULTIPLE)
  table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
  table.getColumns.addAll(
    column("#", 56, _.position, Some("number-cell")),
    column("Column", 280, _.name, None),
    column("Type", 200, _.typeName, Some("column-type-name")))
  private val placeholder = new Label("Reading…")
  placeholder.getStyleClass.add("hint")
  placeholder.setWrapText(true)
  table.setPlaceholder(placeholder)
  VBox.setVgrow(table, Priority.ALWAYS)

  private val copyCombo = new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN)
  table.addEventHandler(KeyEvent.KEY_PRESSED, (e: KeyEvent) => if (copyCombo.`match`(e)) { copyNames(); e.consume() })
  private val copyItem = new MenuItem("Copy names")
  copyItem.setOnAction(_ => copyNames())
  table.setContextMenu(new ContextMenu(copyItem))

  getChildren.addAll(status, table)

  private val record = node match {
    case t: NodeId.Table => session.shownRun(t.dataset).flatMap(_.table(t.name)).flatMap(_.record)
    case _ => None
  }
  record.flatMap(_.schema) match {
    case Some(schema) => showSchema(schema, "as its run wrote them")
    case None => session.queryCatalog.view(node) match {
      case Some(_) => read()
      case None =>
        placeholder.setText(
          if (record.isEmpty) "Its columns are not known: its _run.json is not on disk and there are no rows to read."
          else "Its columns are not known: its run kept no schema and wrote no rows.")
    }
  }

  private def read(): Unit = {
    status.setText("Reading the columns from the data…")
    val catalog = session.queryCatalog
    val worker = new Thread(() => {
      val result = try Right(catalog.view(node).get.schema) catch { case NonFatal(e) => Left(String.valueOf(e.getMessage)) }
      FxHelpers.onFx(result match {
        case Right(schema) => showSchema(schema, "as the data reads")
        case Left(err) =>
          status.setText("")
          placeholder.setText(err)
      })
    }, "transformer-viewer-columns")
    worker.setDaemon(true)
    worker.start()
  }

  private def showSchema(schema: Schema, source: String): Unit = {
    table.setItems(FXCollections.observableArrayList(schema.fields.iterator.zipWithIndex.map { case (f, i) =>
      ColumnRow(i + 1, f.name, DataType.sqlName(f.dataType))
    }.toSeq: _*))
    status.setText(s"${schema.length} ${if (schema.length == 1) "column" else "columns"}, $source")
  }

  private def column(title: String, width: Double, value: ColumnRow => Any,
      style: Option[String]): TableColumn[ColumnRow, Any] = {
    val c = new TableColumn[ColumnRow, Any](title)
    c.setPrefWidth(width)
    c.setCellValueFactory(f => new ReadOnlyObjectWrapper[Any](value(f.getValue)))
    c.setCellFactory(_ => new TableCell[ColumnRow, Any] {
      style.foreach(s => getStyleClass.add(s))
      getStyleClass.add("mono")
      override def updateItem(item: Any, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        setText(if (empty || item == null) null else item.toString)
      }
    })
    c.setSortable(false)
    c
  }

  private def copyNames(): Unit = {
    val names = table.getSelectionModel.getSelectedItems
    if (!names.isEmpty) {
      val content = new ClipboardContent()
      val sb = new StringBuilder
      names.forEach(r => sb.append(if (sb.isEmpty) "" else ", ").append(r.name))
      content.putString(sb.toString)
      Clipboard.getSystemClipboard.setContent(content)
    }
  }
}

/** A dataset's tables in the run shown, a row each: its name, which opens it,
  * its status, its rows and the name queries read it by; then the inputs it
  * read from outside the folder. */
final class TablesPane(session: ViewerSession, d: Int, open: ViewerSelection => Unit) extends VBox {
  import com.transformer.gui.Theme.Palette

  getStyleClass.add("panel")

  private val table = new TableView[NodeId]()
  table.getStyleClass.addAll("data-table", Styles.STRIPED, Styles.DENSE, Tweaks.EDGE_TO_EDGE)
  table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN)
  VBox.setVgrow(table, Priority.ALWAYS)
  getChildren.add(table)

  private val lineage = session.lineage
  private val run = session.shownRun(d)
  private def table(n: NodeId): Option[TableInfo] = n match {
    case t: NodeId.Table => run.flatMap(_.table(t.name))
    case _ => None
  }

  private val nameColumn = new TableColumn[NodeId, NodeId]("Name")
  nameColumn.setPrefWidth(280)
  nameColumn.setCellValueFactory(f => new ReadOnlyObjectWrapper[NodeId](f.getValue))
  nameColumn.setCellFactory(_ => new TableCell[NodeId, NodeId] {
    override def updateItem(item: NodeId, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      setText(null)
      if (empty || item == null) setGraphic(null)
      else {
        val link = new Hyperlink(item.name, item match {
          case _: NodeId.Table => Icons.table
          case _: NodeId.Input => Icons.database
        })
        link.setOnAction(_ => open(ViewerSelection.OfNode(item)))
        setGraphic(link)
      }
    }
  })
  private val statusColumn = textColumn("Status", 190, n => table(n).map(_.status) match {
    case Some(TaskRunStatus.Running) => "Unfinished"
    case Some(TaskRunStatus.Succeeded) => "Succeeded"
    case Some(TaskRunStatus.ValidationFailed) => "Validation failed"
    case Some(TaskRunStatus.Failed) => "Failed"
    case Some(TaskRunStatus.Skipped) => "Skipped"
    case None => "Input from outside the folder"
  }, Some(n => table(n).map(t => LineageCanvas.tableStatus(t.status, t.summary.rowsProduced)._1).getOrElse(Palette.Input)))
  private val rowsColumn = textColumn("Rows", 120, n => table(n).filter(_.wrote)
    .map(t => f"${t.summary.rowsProduced}%,d").getOrElse(""), None, "number-cell")
  private val sqlColumn = textColumn("SQL name", 280, n => session.queryCatalog.sqlName(n).getOrElse(""), None, "mono")
  table.getColumns.addAll(nameColumn, statusColumn, rowsColumn, sqlColumn)

  private val outside = for {
    l <- lineage.toSeq
    r <- run.toSeq
    in <- r.inputs if l.sources.get(NodeId.Input(d, in.name)).contains(InputSource.External)
  } yield NodeId.Input(d, in.name): NodeId
  table.setItems(FXCollections.observableArrayList(
    (run.toSeq.flatMap(_.tables).map(t => NodeId.Table(d, t.name): NodeId) ++ outside): _*))
  private val placeholder = new Label("The run shown made no tables.")
  placeholder.getStyleClass.add("hint")
  table.setPlaceholder(placeholder)
  table.setRowFactory(_ => {
    val row = new TableRow[NodeId]()
    row.setOnMouseClicked(e => if (e.getClickCount == 2 && !row.isEmpty) open(ViewerSelection.OfNode(row.getItem)))
    row
  })

  private def textColumn(title: String, width: Double, text: NodeId => String,
      dot: Option[NodeId => javafx.scene.paint.Color], style: String*): TableColumn[NodeId, NodeId] = {
    val c = new TableColumn[NodeId, NodeId](title)
    c.setPrefWidth(width)
    c.setCellValueFactory(f => new ReadOnlyObjectWrapper[NodeId](f.getValue))
    c.setCellFactory(_ => new TableCell[NodeId, NodeId] {
      style.foreach(s => getStyleClass.add(s))
      override def updateItem(item: NodeId, empty: Boolean): Unit = {
        super.updateItem(item, empty)
        if (empty || item == null) { setText(null); setGraphic(null) }
        else {
          setText(text(item))
          setGraphic(dot.map(f => new Circle(4, f(item))).orNull)
        }
      }
    })
    c
  }
}
