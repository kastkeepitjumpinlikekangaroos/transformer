package com.transformer.viewer

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.gui.{Icons, Theme}

import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.{Button, ContextMenu, Label, MenuItem, SeparatorMenuItem, TextField, TreeCell, TreeItem,
  TreeView}
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyEvent, MouseButton, MouseEvent}
import javafx.scene.layout.{HBox, Priority, Region, StackPane, VBox}
import javafx.scene.shape.Circle

import java.util.Locale
import scala.collection.mutable

/** An item of the [[DatasetTree]]. */
sealed trait TreeEntry
object TreeEntry {
  final case class OfDataset(dataset: Int) extends TreeEntry
  final case class OfNode(node: NodeId) extends TreeEntry
  /** The inputs a dataset read from outside the folder. */
  final case class Sources(dataset: Int) extends TreeEntry
}

/** The folder's datasets down the side of the window: each with its tables,
  * and under them the inputs it read from outside the folder, with a field
  * that filters by name.
  *
  * Selecting an item, by a click or the arrow keys, opens it in the
  * workspace's preview tab (`onOpen` with `keep` false); a double-click on a
  * table or an input, or Enter on any item, opens it in a tab it keeps. Its menu also opens its lineage,
  * queries it in a new tab, puts its name in the query being written, and
  * copies its name or path. The item selected follows the session's
  * selection, which is what the tab in front inspects. With nothing to list
  * it says why, and offers to open a folder.
  */
final class DatasetTree(session: ViewerSession) extends VBox {
  import Theme.Palette

  private var onOpen: (ViewerSelection, Boolean) => Unit = (_, _) => ()
  private var onLineage: ViewerSelection => Unit = _ => ()
  private var onQuery: NodeId => Unit = _ => ()
  private var onInsert: NodeId => Unit = _ => ()
  private var onOpenFolder: () => Unit = () => ()
  /** Open a selection: `keep` for a tab of its own, else in the preview tab. */
  def setOnOpen(f: (ViewerSelection, Boolean) => Unit): Unit = onOpen = f
  def setOnLineage(f: ViewerSelection => Unit): Unit = onLineage = f
  def setOnQuery(f: NodeId => Unit): Unit = onQuery = f
  /** Put a table's name in the query being written. */
  def setOnInsert(f: NodeId => Unit): Unit = onInsert = f
  def setOnOpenFolder(f: () => Unit): Unit = onOpenFolder = f

  getStyleClass.addAll("panel", "dataset-tree")

  private val header = new Label("DATASETS")
  header.getStyleClass.add("section-label")
  val filter = new TextField()
  filter.setPromptText("Filter by name")
  filter.getStyleClass.add(Styles.SMALL)
  private val top = new VBox(8, header, filter)
  top.setPadding(new Insets(12, 12, 8, 12))

  private val tree = new TreeView[TreeEntry]()
  tree.setShowRoot(false)
  tree.getStyleClass.addAll(Tweaks.EDGE_TO_EDGE, Styles.DENSE)
  tree.setCellFactory(_ => new EntryCell)

  // What shows over the list when it is empty: why, and what to do.
  private val emptyTitle = new Label()
  emptyTitle.getStyleClass.add("empty-title")
  emptyTitle.setWrapText(true)
  private val emptyText = new Label()
  emptyText.getStyleClass.add("hint")
  emptyText.setWrapText(true)
  private val openButton = new Button("Open Folder…", Icons.folder)
  openButton.getStyleClass.add(Styles.SMALL)
  openButton.setOnAction(_ => onOpenFolder())
  private val empty = new VBox(8, emptyTitle, emptyText, openButton)
  empty.getStyleClass.add("tree-empty")
  empty.setPadding(new Insets(16, 14, 16, 14))
  empty.setMaxHeight(Region.USE_PREF_SIZE)
  StackPane.setAlignment(empty, Pos.TOP_LEFT)
  private val listArea = new StackPane(tree, empty)
  VBox.setVgrow(listArea, Priority.ALWAYS)
  getChildren.addAll(top, listArea)

  private val items = mutable.Map.empty[TreeEntry, TreeItem[TreeEntry]]
  private var builtFrom: Option[Lineage] = None
  /** Set while the tree selects what the session already has selected. */
  private var syncing = false

  tree.getSelectionModel.selectedItemProperty().addListener((_, _, item) =>
    if (!syncing && item != null) onOpen(selectionOf(item.getValue), false))
  tree.addEventHandler(KeyEvent.KEY_PRESSED, (e: KeyEvent) => if (e.getCode == KeyCode.ENTER) {
    selected.foreach(onOpen(_, true))
    e.consume()
  })
  tree.setContextMenu(contextMenu())

  filter.textProperty().addListener((_, _, _) => rebuild())
  session.addListener(() => refresh())
  refresh()

  private def selectionOf(e: TreeEntry): ViewerSelection = e match {
    case TreeEntry.OfDataset(d) => ViewerSelection.OfDataset(d)
    case TreeEntry.OfNode(n) => ViewerSelection.OfNode(n)
    case TreeEntry.Sources(d) => ViewerSelection.OfDataset(d)
  }

  private def selected: Option[ViewerSelection] =
    Option(tree.getSelectionModel.getSelectedItem).map(i => selectionOf(i.getValue))

  private def selectedNode: Option[NodeId] =
    Option(tree.getSelectionModel.getSelectedItem).map(_.getValue).collect { case TreeEntry.OfNode(n) => n }

  private def refresh(): Unit = {
    if (session.lineage ne builtFrom.orNull) rebuild()
    syncSelection()
    showEmpty()
  }

  /** Say why the list is empty, while it is. */
  private def showEmpty(): Unit = {
    val (title, text) = (session.scanning, session.folder) match {
      case (Some(dir), _) => ("Looking for job outputs…", dir.toString)
      case (None, None) => ("No folder open", "Open a folder of job outputs: a directory holding the job.json each run writes, at any depth.")
      case (None, Some(f)) if f.datasets.isEmpty =>
        ("No job outputs here", (Seq(s"${f.root} holds no job.json, as `transformer run` writes one.") ++ f.warnings.take(4))
          .mkString("\n\n"))
      case (None, Some(_)) if tree.getRoot == null || tree.getRoot.getChildren.isEmpty =>
        ("Nothing matches", s"No dataset, table or input is named like '${filter.getText.trim}'.")
      case _ => ("", "")
    }
    emptyTitle.setText(title)
    emptyText.setText(text)
    openButton.setVisible(session.scanning.isEmpty && title != "Nothing matches")
    openButton.setManaged(openButton.isVisible)
    empty.setVisible(title.nonEmpty)
  }

  private def rebuild(): Unit = {
    builtFrom = session.lineage
    items.clear()
    val root = new TreeItem[TreeEntry]()
    val wanted = filter.getText.trim.toLowerCase(Locale.ROOT)
    def matches(s: String): Boolean = wanted.isEmpty || s.toLowerCase(Locale.ROOT).contains(wanted)
    session.lineage.foreach { l =>
      l.shownRuns.indices.foreach { d =>
        val dataset = l.folder.datasets(d)
        val run = l.shownRuns(d)
        val whole = matches(dataset.name)
        val tables = run.tables.filter(t => whole || matches(t.name))
        val outside = run.inputs.filter(in => l.sources.get(NodeId.Input(d, in.name)).contains(InputSource.External))
          .filter(in => whole || matches(in.name))
        if (whole || tables.nonEmpty || outside.nonEmpty) {
          val item = entry(TreeEntry.OfDataset(d))
          tables.foreach(t => item.getChildren.add(entry(TreeEntry.OfNode(NodeId.Table(d, t.name)))))
          if (outside.nonEmpty) {
            val group = entry(TreeEntry.Sources(d))
            outside.foreach(in => group.getChildren.add(entry(TreeEntry.OfNode(NodeId.Input(d, in.name)))))
            group.setExpanded(wanted.nonEmpty)
            item.getChildren.add(group)
          }
          item.setExpanded(true)
          root.getChildren.add(item)
        }
      }
    }
    syncing = true
    try tree.setRoot(root) finally syncing = false
    syncSelection()
    showEmpty()
  }

  private def entry(e: TreeEntry): TreeItem[TreeEntry] = {
    val item = new TreeItem[TreeEntry](e)
    items(e) = item
    item
  }

  /** Select what the session has selected, opening the item's dataset. */
  private def syncSelection(): Unit = {
    val wanted: Option[TreeEntry] = session.selection.map {
      case ViewerSelection.OfDataset(d) => TreeEntry.OfDataset(d)
      case ViewerSelection.OfNode(n) => TreeEntry.OfNode(n)
    }
    val current = Option(tree.getSelectionModel.getSelectedItem).map(_.getValue)
    if (wanted != current) {
      syncing = true
      try wanted.flatMap(items.get) match {
        case Some(item) =>
          Iterator.iterate(item.getParent)(_.getParent).takeWhile(_ != null).foreach(_.setExpanded(true))
          tree.getSelectionModel.select(item)
          val row = tree.getRow(item)
          if (row >= 0) tree.scrollTo(math.max(0, row - 3))
        case None => tree.getSelectionModel.clearSelection()
      } finally syncing = false
    }
  }

  private def contextMenu(): ContextMenu = {
    val keep = new MenuItem("Open in New Tab")
    keep.setOnAction(_ => selected.foreach(onOpen(_, true)))
    val lineage = new MenuItem("Show Lineage")
    lineage.setOnAction(_ => selected.foreach(onLineage))
    val query = new MenuItem("Query in New Tab")
    query.setOnAction(_ => selectedNode.foreach(onQuery))
    val insert = new MenuItem("Insert Name into Query")
    insert.setOnAction(_ => selectedNode.foreach(onInsert))
    val copyName = new MenuItem("Copy SQL Name")
    copyName.setOnAction(_ => selectedNode.flatMap(session.queryCatalog.sqlName).foreach(copy))
    val copyPath = new MenuItem("Copy Path")
    copyPath.setOnAction(_ => selectedPath.foreach(copy))
    val menu = new ContextMenu(keep, lineage, new SeparatorMenuItem(), query, insert, new SeparatorMenuItem(),
      copyName, copyPath)
    menu.setOnShowing(_ => {
      val queryable = selectedNode.flatMap(session.queryCatalog.sqlName).isDefined
      keep.setDisable(selected.isEmpty)
      lineage.setDisable(selected.isEmpty)
      query.setDisable(!queryable)
      insert.setDisable(!queryable)
      copyName.setDisable(!queryable)
      copyPath.setDisable(selectedPath.isEmpty)
    })
    menu
  }

  /** Where the selected item's data is: a table's directory, an input's path,
    * a dataset's run. */
  private def selectedPath: Option[String] =
    Option(tree.getSelectionModel.getSelectedItem).map(_.getValue).flatMap {
      case TreeEntry.OfNode(t: NodeId.Table) => session.shownRun(t.dataset).flatMap(_.table(t.name)).flatMap(_.dir).map(_.toString)
      case TreeEntry.OfNode(i: NodeId.Input) => session.lineage.flatMap(_.inputRecord(i)).map(_.path)
      case TreeEntry.OfDataset(d) => session.shownRun(d).map(_.dir.toString)
      case TreeEntry.Sources(_) => None
    }

  private def copy(text: String): Unit = {
    val content = new ClipboardContent()
    content.putString(text)
    Clipboard.getSystemClipboard.setContent(content)
    ()
  }

  /** A row: a mark (a status dot, or an icon), the name, and a count. A
    * click opens its item, as selecting it does, so clicking the item
    * already selected brings it to the front again; a double-click on a
    * table or an input keeps it in a tab of its own, while on a dataset it
    * opens or closes the branch, as a tree does. A click on the arrow only
    * opens or closes the branch. */
  private final class EntryCell extends TreeCell[TreeEntry] {
    // As wide as the list, never wider: a long name ends in an ellipsis and
    // the count beside it stays in view, with no sideways scrolling.
    setPrefWidth(0)
    addEventHandler(MouseEvent.MOUSE_CLICKED, (e: MouseEvent) =>
      if (e.getButton == MouseButton.PRIMARY && !isEmpty && getItem != null && !onArrow(e.getTarget))
        onOpen(selectionOf(getItem), e.getClickCount == 2 && getTreeItem != null && getTreeItem.isLeaf))

    private def onArrow(target: AnyRef): Boolean = target match {
      case n: Node => Option(getDisclosureNode).exists(d => Iterator.iterate(n)(_.getParent).takeWhile(_ != null).contains(d))
      case _ => false
    }

    override def updateItem(item: TreeEntry, empty: Boolean): Unit = {
      super.updateItem(item, empty)
      setText(null)
      if (empty || item == null) setGraphic(null)
      else setGraphic(row(item))
    }

    private def row(item: TreeEntry): javafx.scene.Node = {
      val (mark, name, count, nameClass) = item match {
        case TreeEntry.OfDataset(d) =>
          val run = session.shownRun(d)
          val tables = run.map(_.tables.size).getOrElse(0)
          (run.map(r => dot(LineageCanvas.runStatus(r)._1)).getOrElse(dot(Palette.TextSubtle)),
            session.datasets.lift(d).map(_.name).getOrElse(""),
            s"$tables ${if (tables == 1) "table" else "tables"}", "tree-dataset")
        case TreeEntry.OfNode(t: NodeId.Table) =>
          val table = session.shownRun(t.dataset).flatMap(_.table(t.name))
          val color = table.map(i => LineageCanvas.tableStatus(i.status, i.summary.rowsProduced)._1).getOrElse(Palette.TextSubtle)
          val rows = table.filter(_.wrote).map(i => f"${i.summary.rowsProduced}%,d")
            .getOrElse(table.map(i => DetailsPanel.statusText(i.status).toLowerCase(Locale.ROOT)).getOrElse(""))
          (dot(color), t.name, rows, "tree-table")
        case TreeEntry.OfNode(i: NodeId.Input) =>
          val format = session.lineage.flatMap(_.inputRecord(i)).map(_.format.toUpperCase(Locale.ROOT)).getOrElse("")
          (Icons.database, i.name, format, "tree-input")
        case TreeEntry.Sources(d) =>
          val n = items.get(item).map(_.getChildren.size).getOrElse(0)
          (Icons.folder, "read from outside", n.toString, "tree-group")
      }
      val nameLabel = new Label(name)
      nameLabel.getStyleClass.add(nameClass)
      nameLabel.setMinWidth(0)
      val spacer = new Region()
      HBox.setHgrow(spacer, Priority.ALWAYS)
      val countLabel = new Label(count)
      countLabel.getStyleClass.add("tree-count")
      countLabel.setMinWidth(Region.USE_PREF_SIZE)
      val box = new HBox(7, mark, nameLabel, spacer, countLabel)
      box.setAlignment(Pos.CENTER_LEFT)
      box
    }

    private def dot(color: javafx.scene.paint.Color): javafx.scene.Node = {
      val c = new Circle(4, color)
      val holder = new HBox(c)
      holder.setAlignment(Pos.CENTER)
      holder.setMinWidth(16)
      holder
    }
  }
}
