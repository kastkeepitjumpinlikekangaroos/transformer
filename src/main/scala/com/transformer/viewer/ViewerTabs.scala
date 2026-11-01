package com.transformer.viewer

import atlantafx.base.theme.Styles
import com.transformer.gui.{Icons, QueryConsole}

import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.geometry.{Insets, Orientation, Pos}
import javafx.scene.Node
import javafx.scene.control.{Button, Label, Tab, TabPane, Tooltip}
import javafx.scene.input.{MouseButton, MouseEvent}
import javafx.scene.layout.{Priority, StackPane, VBox}
import javafx.stage.Stage

import scala.collection.mutable

/** The viewer's workspace: a tab per query, and a tab per table, input or
  * dataset being inspected ([[InspectorPane]]).
  *
  * A query tab is a [[QueryConsole]] over the folder's catalog
  * ([[QueryCatalog]]), as it is when the query runs, the editor above the
  * result: its rows, or the rows drawn as a chart. What is opened to inspect shows in the preview tab, its name in
  * italics, which the next thing opened replaces, so clicking through tables
  * leaves one tab behind; a thing opened to keep (double-clicked in the
  * list, or its preview tab's header double-clicked) has a tab of its own,
  * which it comes back to. Every tab closes; with none open the workspace
  * says how to start one.
  *
  * The tab in front decides what is selected: the subject of an inspector
  * there is the session's selection, which the list follows.
  */
final class ViewerTabs(session: ViewerSession, owner: () => Stage) extends StackPane {

  private val pane = new TabPane()
  pane.getStyleClass.add("workspace-tabs")
  pane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS)

  private val consoles = mutable.LinkedHashMap.empty[Tab, QueryConsole]
  private val inspectors = mutable.LinkedHashMap.empty[Tab, InspectorPane]
  /** The tab that shows whatever was opened last, until it is kept. */
  private var preview: Option[Tab] = None
  /** The query tab last in front: where Insert Name into Query writes. */
  private var lastQuery: Option[Tab] = None
  private var queryCount = 0
  /** The first query tab's text, a query of the folder's first table, while
    * it is untouched: a folder opened gives it that folder's first table. */
  private val starters = mutable.Map.empty[QueryConsole, String]
  private var seenFolder: Option[RunFolder] = None
  private var seenLineage: Option[Lineage] = None

  private val emptyState: VBox = {
    val title = new Label("No tabs open")
    title.getStyleClass.add("empty-title")
    val text = new Label("Click a table in the list to inspect it, or start a query.")
    text.getStyleClass.add("muted")
    val newQuery = new Button("New query", Icons.console)
    newQuery.getStyleClass.add(Styles.ACCENT)
    newQuery.setOnAction(_ => newQueryTab())
    val box = new VBox(10, title, text, newQuery)
    box.setAlignment(Pos.CENTER)
    box.getStyleClass.add("panel")
    box
  }
  getChildren.addAll(emptyState, pane)

  pane.getSelectionModel.selectedItemProperty().addListener((_, _, tab) => if (tab != null) {
    inspectors.get(tab).foreach(i => session.select(Some(i.subject)))
    if (consoles.contains(tab)) lastQuery = Some(tab)
  })
  pane.getTabs.addListener(new ListChangeListener[Tab] {
    def onChanged(c: ListChangeListener.Change[_ <: Tab]): Unit = {
      while (c.next()) c.getRemoved.forEach { t =>
        consoles.remove(t).foreach(starters.remove)
        inspectors.remove(t)
        if (preview.contains(t)) preview = None
        if (lastQuery.contains(t)) lastQuery = None
      }
      pane.setVisible(!pane.getTabs.isEmpty)
      emptyState.setVisible(pane.getTabs.isEmpty)
    }
  })
  // A double-click on the preview tab's header keeps it.
  pane.addEventHandler(MouseEvent.MOUSE_CLICKED, (e: MouseEvent) =>
    if (e.getButton == MouseButton.PRIMARY && e.getClickCount == 2 && inTabHeader(e.getTarget))
      preview.filter(_ eq pane.getSelectionModel.getSelectedItem).foreach(keep))

  // The folder read again, or another run shown: each inspector finds its
  // subject in what the session holds now, or closes.
  session.addListener(() => {
    if (session.folder ne seenFolder.orNull) {
      val before = seenFolder
      seenFolder = session.folder
      seenLineage = session.lineage
      inspectors.toSeq.foreach { case (tab, i) =>
        val moved = before.exists(b => i.update(s => session.relocate(s, b)))
        if (moved) titleTab(tab) else pane.getTabs.remove(tab)
      }
      starters.toSeq.foreach { case (c, sql) =>
        val now = starterSql
        if (c.editor.text == sql && now != sql) {
          c.setQuery(now)
          starters(c) = now
        }
      }
    } else if (session.lineage ne seenLineage.orNull) {
      seenLineage = session.lineage
      inspectors.toSeq.foreach { case (tab, i) =>
        if (i.update(s => Some(s).filter(session.shows))) titleTab(tab) else pane.getTabs.remove(tab)
      }
    }
  })

  addQueryTab(starterSql, starter = true)

  // ==================== inspecting ====================

  /** Show `sel` in a tab: its own tab when it has one; else the preview tab,
    * made when there is none, or kept when `pin`. `view` is the view to turn
    * to; without one the preview stays on the view it is on, where `sel` has
    * it. */
  def inspect(sel: ViewerSelection, pin: Boolean = false, view: Option[InspectorView] = None): Unit =
    if (session.shows(sel)) {
      val own = inspectors.collectFirst { case (t, i) if !preview.contains(t) && i.subject == sel => t }
      val tab = own match {
        case Some(t) =>
          view.foreach(inspectors(t).selectView)
          t
        case None =>
          val t = preview match {
            case Some(p) =>
              inspectors(p).show(sel, view)
              p
            case None => newInspector(sel, view)
          }
          if (pin) keep(t)
          titleTab(t)
          t
      }
      pane.getSelectionModel.select(tab)
      session.select(Some(sel))
    }

  /** The inspector in front, if the tab in front is one. */
  def currentInspector: Option[InspectorPane] = Option(pane.getSelectionModel.getSelectedItem).flatMap(inspectors.get)

  /** Turn the inspector in front to its lineage; else open what is selected
    * at its lineage. */
  def showLineage(): Unit = currentInspector match {
    case Some(i) => i.selectView(InspectorView.Lineage)
    case None => session.selection.foreach(s => inspect(s, view = Some(InspectorView.Lineage)))
  }

  /** The lineage canvas in front, when an inspector shows its Lineage view. */
  def lineageCanvas: Option[LineageCanvas] = currentInspector.flatMap(_.lineageCanvas)

  def back(): Unit = currentInspector.foreach(_.back())
  def forward(): Unit = currentInspector.foreach(_.forward())

  def closeCurrentTab(): Unit = Option(pane.getSelectionModel.getSelectedItem).foreach(t => pane.getTabs.remove(t))

  /** A preview tab showing `sel`. */
  private def newInspector(sel: ViewerSelection, view: Option[InspectorView]): Tab = {
    val tab = new Tab()
    val inspector = new InspectorPane(session,
      open = (s, v) => inspect(s, view = v),
      query = queryInNewTab,
      changed = () => {
        titleTab(tab)
        if (pane.getSelectionModel.getSelectedItem eq tab) inspectors.get(tab).foreach(i => session.select(Some(i.subject)))
      })
    inspector.show(sel, view)
    tab.setContent(inspector)
    inspectors(tab) = inspector
    preview = Some(tab)
    tab.getStyleClass.add("preview-tab")
    insertTab(tab)
    tab
  }

  /** Keep the preview tab: what opens next takes a new one. */
  private def keep(tab: Tab): Unit = if (preview.contains(tab)) {
    preview = None
    tab.getStyleClass.remove("preview-tab")
    titleTab(tab)
  }

  /** An inspector's tab named after its subject. */
  private def titleTab(tab: Tab): Unit = inspectors.get(tab).foreach { i =>
    val (name, icon, tip) = i.subject match {
      case ViewerSelection.OfNode(n) =>
        (n.name, n match {
          case _: NodeId.Table => Icons.table
          case _: NodeId.Input => Icons.database
        }, session.queryCatalog.sqlName(n).getOrElse(n.name))
      case ViewerSelection.OfDataset(d) =>
        val name = session.datasets.lift(d).map(_.name).getOrElse("")
        (name, Icons.folder, session.shownRun(d).map(_.dir.toString).getOrElse(name))
    }
    tab.setText(name)
    tab.setGraphic(icon)
    tab.setTooltip(new Tooltip(
      if (preview.contains(tab)) s"$tip\nA preview: what you open next takes its place. Double-click the tab to keep it."
      else tip))
  }

  private def inTabHeader(target: AnyRef): Boolean = target match {
    case n: Node => Iterator.iterate(n)(_.getParent).takeWhile(_ != null).exists(_.getStyleClass.contains("tab-header-area"))
    case _ => false
  }

  // ==================== querying ====================

  /** A new query tab that reads `node`'s first hundred rows, and runs it. */
  def queryInNewTab(node: NodeId): Unit = session.queryCatalog.sqlName(node).foreach { name =>
    val c = consoles(addQueryTab(s"SELECT *\nFROM $name\nLIMIT 100"))
    c.runQuery()
  }

  /** `node`'s name, put in at the caret of the query tab last in front (a new
    * one when there is none). */
  def insertIntoQuery(node: NodeId): Unit = session.queryCatalog.sqlName(node).foreach { name =>
    val tab = lastQuery.getOrElse(addQueryTab(""))
    pane.getSelectionModel.select(tab)
    consoles(tab).insertAtCaret(name)
  }

  /** A new, empty query tab. */
  def newQueryTab(): Unit = {
    addQueryTab("")
    ()
  }

  private def addQueryTab(sql: String, starter: Boolean = false): Tab = {
    val c = new QueryConsole(
      owner,
      prepare = () => {
        val catalog = session.queryCatalog
        sql => catalog.catalogFor(sql)
      },
      persistDir = () => session.folder.map(_.root.resolve("interactive").toString),
      orientation = Orientation.VERTICAL)
    c.setQuery(sql)
    c.editor.setPlaceholderText(session.queryCatalog.entries.headOption
      .map(e => s"A query over any table here, each named <dataset>__<table>, as ${e.sqlName}")
      .getOrElse("A query over any table here, each named <dataset>__<table>"))
    if (starter) starters(c) = sql
    queryCount += 1
    val box = new VBox(c)
    box.getStyleClass.add("panel")
    box.setPadding(new Insets(12, 14, 12, 14))
    VBox.setVgrow(c, Priority.ALWAYS)
    val tab = new Tab(s"Query $queryCount", box)
    tab.setGraphic(Icons.console)
    consoles(tab) = c
    insertTab(tab)
    pane.getSelectionModel.select(tab)
    Platform.runLater(() => c.focusEditor())
    tab
  }

  /** The query a new folder's first tab starts with: the first table's rows. */
  private def starterSql: String = {
    val name = session.queryCatalog.entries.headOption.map(_.sqlName).getOrElse("<dataset>__<table>")
    s"SELECT *\nFROM $name\nLIMIT 100"
  }

  /** Put `tab` after the tab in front, as an editor opens a file. */
  private def insertTab(tab: Tab): Unit = {
    val at = pane.getSelectionModel.getSelectedIndex
    if (at < 0) pane.getTabs.add(tab) else pane.getTabs.add(at + 1, tab)
  }
}
