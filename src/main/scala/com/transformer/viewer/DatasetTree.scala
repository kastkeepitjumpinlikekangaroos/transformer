package com.transformer.viewer

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.gui.{Icons, Theme}
import com.transformer.job.TaskRunStatus

import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{ContextMenu, Label, MenuItem, SeparatorMenuItem, TextField, TreeCell, TreeItem, TreeView}
import javafx.scene.input.{Clipboard, ClipboardContent, MouseButton, MouseEvent}
import javafx.scene.layout.{HBox, Priority, Region, VBox}
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
  * that filters by name. Selecting an item selects it in the lineage and the
  * details too; double-clicking a table or an input opens a sample of its
  * rows, and its menu also queries it or copies its name or path. */
final class DatasetTree(session: ViewerSession) extends VBox {
  import Theme.Palette

  private var onActivate: NodeId => Unit = _ => ()
  private var onQuery: NodeId => Unit = _ => ()
  def setOnActivate(f: NodeId => Unit): Unit = onActivate = f
  def setOnQuery(f: NodeId => Unit): Unit = onQuery = f

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
  VBox.setVgrow(tree, Priority.ALWAYS)
  getChildren.addAll(top, tree)

  private val items = mutable.Map.empty[TreeEntry, TreeItem[TreeEntry]]
  private var builtFrom: Option[Lineage] = None
  /** Set while the tree selects what the session already has selected. */
  private var syncing = false

  tree.getSelectionModel.selectedItemProperty().addListener((_, _, item) => if (!syncing && item != null) {
    item.getValue match {
      case TreeEntry.OfDataset(d) => session.select(Some(ViewerSelection.OfDataset(d)))
      case TreeEntry.OfNode(n) => session.select(Some(ViewerSelection.OfNode(n)))
      case TreeEntry.Sources(d) => session.select(Some(ViewerSelection.OfDataset(d)))
    }
  })
  tree.addEventHandler(MouseEvent.MOUSE_CLICKED, (e: MouseEvent) =>
    if (e.getButton == MouseButton.PRIMARY && e.getClickCount == 2) selectedNode.foreach(onActivate))
  tree.setContextMenu(contextMenu())

  filter.textProperty().addListener((_, _, _) => rebuild())
  session.addListener(() => refresh())
  refresh()

  private def selectedNode: Option[NodeId] =
    Option(tree.getSelectionModel.getSelectedItem).map(_.getValue).collect { case TreeEntry.OfNode(n) => n }

  private def refresh(): Unit = {
    if (session.lineage ne builtFrom.orNull) rebuild()
    syncSelection()
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
    val sample = new MenuItem("Open sample")
    sample.setOnAction(_ => selectedNode.foreach(onActivate))
    val query = new MenuItem("Query in a new tab")
    query.setOnAction(_ => selectedNode.foreach(onQuery))
    val copyName = new MenuItem("Copy SQL name")
    copyName.setOnAction(_ => selectedNode.flatMap(session.queryCatalog.sqlName).foreach(copy))
    val copyPath = new MenuItem("Copy path")
    copyPath.setOnAction(_ => selectedPath.foreach(copy))
    val menu = new ContextMenu(sample, query, new SeparatorMenuItem(), copyName, copyPath)
    menu.setOnShowing(_ => {
      val node = selectedNode
      val queryable = node.flatMap(session.queryCatalog.sqlName).isDefined
      sample.setDisable(!queryable)
      query.setDisable(!queryable)
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

  /** A row: a mark (a status dot, or an icon), the name, and a count. */
  private final class EntryCell extends TreeCell[TreeEntry] {
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
          val summary = session.shownRun(t.dataset).flatMap(_.table(t.name)).map(_.summary)
          val color = summary.map(s => LineageCanvas.tableStatus(s.status, s.rowsProduced)._1).getOrElse(Palette.TextSubtle)
          val rows = summary.filter(s => s.status == TaskRunStatus.Succeeded || s.status == TaskRunStatus.ValidationFailed)
            .map(s => f"${s.rowsProduced}%,d").getOrElse(summary.map(s => TaskRunStatus.toJson(s.status).toLowerCase(Locale.ROOT)).getOrElse(""))
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
