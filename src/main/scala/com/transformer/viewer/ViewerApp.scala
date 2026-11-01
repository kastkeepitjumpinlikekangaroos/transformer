package com.transformer.viewer

import atlantafx.base.theme.Styles
import com.transformer.gui.{FxHelpers, Icons, ShortcutsDialog, SqlEditor, Theme}

import javafx.application.Application
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.{Button, CheckMenuItem, Label, Menu, MenuBar, MenuItem, OverrunStyle, SeparatorMenuItem, SplitPane, Tooltip}
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import javafx.scene.layout.{BorderPane, HBox, Priority, Region, VBox}
import javafx.scene.shape.{Circle, Rectangle}
import javafx.stage.Stage

import java.nio.file.{Path, Paths}
import java.util.prefs.Preferences
import scala.util.control.NonFatal

/** The data viewer: a window for querying a folder of job outputs and
  * inspecting its tables.
  *
  * Layout:
  *   * Top — the header: the folder, Open and Reload, New query, and what the
  *     folder holds. The menus (File, View, Help) are in the macOS menu bar,
  *     or above it.
  *   * Left — the [[DatasetTree]]: every dataset, its tables and the inputs
  *     it read from outside the folder.
  *   * Centre — the [[ViewerTabs]]: query tabs, each an editor above its
  *     rows, and inspector tabs, each a table's (or an input's, or a
  *     dataset's) rows, columns, details and lineage.
  *
  * Clicking a table in the list inspects it in the preview tab; its lineage
  * is one of the inspector's views. Reading the folder runs on a worker.
  */
final class ViewerApp extends Application {

  override def start(stage: Stage): Unit = {
    Theme.install()
    stage.setTitle("Transformer data viewer")

    val session = new ViewerSession
    val tree = new DatasetTree(session)
    val tabs = new ViewerTabs(session, () => stage)
    val header = new ViewerHeader(session, () => chooseFolder(stage, session), () => reload(session),
      () => tabs.newQueryTab())

    tree.setOnOpen((sel, keep) => tabs.inspect(sel, pin = keep))
    tree.setOnLineage(sel => tabs.inspect(sel, view = Some(InspectorView.Lineage)))
    tree.setOnQuery(tabs.queryInNewTab)
    tree.setOnInsert(tabs.insertIntoQuery)
    tree.setOnOpenFolder(() => chooseFolder(stage, session))

    val main = new SplitPane(tree, tabs)
    main.setDividerPositions(0.2)
    SplitPane.setResizableWithParent(tree, false)

    val root = new BorderPane()
    root.setTop(new VBox(menuBar(stage, session, tabs, tree), header))
    root.setCenter(main)

    val scene = new Scene(root, 1440, 920)
    Theme.style(scene)
    session.addListener(() => stage.setTitle(session.folder
      .map(f => s"Transformer data viewer — ${Option(f.root.getFileName).getOrElse(f.root)}")
      .getOrElse("Transformer data viewer")))
    stage.setScene(scene)
    stage.setMinWidth(900)
    stage.setMinHeight(600)
    stage.show()

    val args = getParameters.getRaw
    if (!args.isEmpty && args.get(0).trim.nonEmpty) open(session, Paths.get(args.get(0).trim))
  }

  private def menuBar(stage: Stage, session: ViewerSession, tabs: ViewerTabs, tree: DatasetTree): MenuBar = {
    def shortcut(code: KeyCode, more: KeyCombination.Modifier*): KeyCodeCombination =
      new KeyCodeCombination(code, (KeyCombination.SHORTCUT_DOWN +: more): _*)
    def item(text: String, key: Option[KeyCodeCombination])(f: => Unit): MenuItem = {
      val m = new MenuItem(text)
      key.foreach(m.setAccelerator)
      m.setOnAction(_ => f)
      m
    }

    val reloadItem = item("Reload", Some(shortcut(KeyCode.R)))(reload(session))
    session.addListener(() => reloadItem.setDisable(session.folder.isEmpty))
    reloadItem.setDisable(true)
    val file = new Menu("File")
    file.getItems.addAll(
      item("Open Folder…", Some(shortcut(KeyCode.O)))(chooseFolder(stage, session)),
      reloadItem,
      new SeparatorMenuItem(),
      item("New Query Tab", Some(shortcut(KeyCode.T)))(tabs.newQueryTab()),
      item("Close Tab", Some(shortcut(KeyCode.W)))(tabs.closeCurrentTab()),
      new SeparatorMenuItem(),
      item("Quit", Some(shortcut(KeyCode.Q)))(stage.close()))

    val vim = new CheckMenuItem("Vim Keybindings")
    vim.selectedProperty().bindBidirectional(SqlEditor.vimEnabled)
    val view = new Menu("View")
    view.getItems.addAll(
      item("Find a Table", Some(shortcut(KeyCode.F)))(tree.filter.requestFocus()),
      new SeparatorMenuItem(),
      item("Back", Some(shortcut(KeyCode.OPEN_BRACKET)))(tabs.back()),
      item("Forward", Some(shortcut(KeyCode.CLOSE_BRACKET)))(tabs.forward()),
      item("Lineage", Some(shortcut(KeyCode.L)))(tabs.showLineage()),
      new SeparatorMenuItem(),
      item("Fit Lineage to Window", Some(shortcut(KeyCode.DIGIT0)))(tabs.lineageCanvas.foreach { c =>
        c.fitToWindow()
        c.render()
      }),
      item("Zoom In", Some(shortcut(KeyCode.EQUALS)))(tabs.lineageCanvas.foreach(_.zoomIn())),
      item("Zoom Out", Some(shortcut(KeyCode.MINUS)))(tabs.lineageCanvas.foreach(_.zoomOut())),
      new SeparatorMenuItem(),
      vim)

    val help = new Menu("Help")
    help.getItems.add(item("Keyboard Shortcuts", Some(shortcut(KeyCode.SLASH))) {
      import ShortcutsDialog.Cmd
      ShortcutsDialog.show(stage, Seq(
        s"${Cmd}O" -> "Open a folder of job outputs",
        s"${Cmd}R" -> "Read the folder again",
        s"${Cmd}T" -> "A new query tab",
        s"${Cmd}W" -> "Close the tab in front",
        s"${Cmd}F" -> "Find a table by name",
        "Click, or the arrow keys" -> "In the list: inspect a table, an input or a dataset in the preview tab",
        "Double-click, or Enter" -> "In the list: inspect a table or an input (Enter: any item) in a tab of its own",
        s"$Cmd[ / $Cmd]" -> "In an inspector: back to what it showed before, and forward again",
        s"${Cmd}L" -> "The lineage of what the tab in front inspects",
        s"${Cmd}0" -> "Fit the lineage to the window",
        s"$Cmd= / $Cmd-" -> "Zoom the lineage in / out",
        "Double-click" -> "In a lineage: open that table, input or dataset",
        s"${Cmd}C" -> "In a grid of rows: copy the selected rows; in Columns: their names",
        s"${Cmd}/" -> "This sheet"
      ))
    })

    val bar = new MenuBar()
    bar.getMenus.addAll(file, view, help)
    bar.setUseSystemMenuBar(true)
    bar
  }

  private def chooseFolder(stage: Stage, session: ViewerSession): Unit = {
    val initial = session.folder.map(_.root.toFile).orElse(ViewerApp.lastFolder.map(_.toFile))
    FxHelpers.chooseDirectory(stage, "Open a folder of job outputs", initial).foreach(f => open(session, f.toPath))
  }

  private def reload(session: ViewerSession): Unit = session.folder.foreach(f => open(session, f.root))

  /** Read `dir` on a worker, then show it. */
  private def open(session: ViewerSession, dir: Path): Unit = {
    val root = dir.toAbsolutePath.normalize()
    ViewerApp.rememberFolder(root)
    session.beginScan(root)
    val worker = new Thread(() => {
      val folder =
        try RunFolder.scan(root)
        catch { case NonFatal(e) => RunFolder(root, Vector.empty, Vector(s"could not read $root: ${e.getMessage}")) }
      FxHelpers.onFx(session.open(folder))
    }, "transformer-viewer-scan")
    worker.setDaemon(true)
    worker.start()
  }
}

object ViewerApp {
  private val FolderPreference = "lastFolder"
  private lazy val prefs = Preferences.userNodeForPackage(classOf[ViewerApp])

  /** The folder last opened, where the folder chooser starts. */
  def lastFolder: Option[Path] =
    try Option(prefs.get(FolderPreference, null)).map(Paths.get(_)) catch { case NonFatal(_) => None }

  private def rememberFolder(dir: Path): Unit =
    try prefs.put(FolderPreference, dir.toString) catch { case NonFatal(_) => () }
}

/** The header across the top: the folder that is open, Open and Reload, New
  * query, and a count of what it holds (or that it is being read), with what
  * could not be read. */
final class ViewerHeader(session: ViewerSession, openFolder: () => Unit, reload: () => Unit,
    newQuery: () => Unit) extends HBox {

  getStyleClass.add("app-header")

  private val mark = new Rectangle(14, 14)
  mark.getStyleClass.add("app-mark")
  private val title = new Label("transformer", mark)
  title.getStyleClass.add("app-title")
  title.setGraphicTextGap(8)
  title.setMinWidth(Region.USE_PREF_SIZE)
  private val tag = new Label("DATA VIEWER")
  tag.getStyleClass.addAll("pill", "input")
  tag.setMinWidth(Region.USE_PREF_SIZE)

  private val folderName = new Label("No folder open")
  folderName.getStyleClass.add("job-name")
  private val folderPath = new Label("")
  folderPath.getStyleClass.add("job-path")
  folderPath.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS)
  private val folderText = new VBox(0, folderName, folderPath)
  folderText.setAlignment(Pos.CENTER_LEFT)
  folderText.setMaxWidth(420)
  folderText.setMinWidth(120)
  private def iconButton(icon: javafx.scene.Node, tip: String)(f: => Unit): Button = {
    val b = new Button(null, icon)
    b.getStyleClass.addAll(Styles.FLAT, Styles.BUTTON_ICON)
    b.setTooltip(new Tooltip(tip))
    b.setOnAction(_ => f)
    b
  }
  private val openButton = iconButton(Icons.folder, "Open a folder of job outputs (⌘O)")(openFolder())
  private val reloadButton = iconButton(Icons.refresh, "Read the folder again (⌘R)")(reload())

  private val newQueryButton = new Button("New query", Icons.console)
  newQueryButton.getStyleClass.add(Styles.SMALL)
  newQueryButton.setTooltip(new Tooltip("A new query tab (⌘T)"))
  newQueryButton.setMinWidth(Region.USE_PREF_SIZE)
  newQueryButton.setOnAction(_ => newQuery())

  private val dot = new Circle(4)
  dot.getStyleClass.add("dot")
  private val status = new Label("", dot)
  status.getStyleClass.add("status-pill")
  status.setMinWidth(Region.USE_PREF_SIZE)

  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)
  private val titleGroup = new HBox(10, title, tag)
  titleGroup.setAlignment(Pos.CENTER_LEFT)
  private val folderGroup = new HBox(10, folderText, openButton, reloadButton)
  folderGroup.setAlignment(Pos.CENTER_LEFT)
  private val endGroup = new HBox(12, newQueryButton, status)
  endGroup.setAlignment(Pos.CENTER_RIGHT)
  getChildren.addAll(titleGroup, folderGroup, spacer, endGroup)

  session.addListener(() => refresh())
  refresh()

  private def refresh(): Unit = {
    val root = session.scanning.orElse(session.folder.map(_.root))
    folderName.setText(root.map(r => Option(r.getFileName).map(_.toString).getOrElse(r.toString)).getOrElse("No folder open"))
    folderPath.setText(root.map(_.toString).getOrElse(""))
    reloadButton.setDisable(session.folder.isEmpty)
    status.getStyleClass.setAll("label", "status-pill")
    status.setTooltip(null)
    (session.scanning, session.lineage) match {
      case (Some(_), _) =>
        status.setText("Reading the folder…")
        status.getStyleClass.add("running")
      case (None, Some(l)) =>
        val tables = l.shownRuns.map(_.tables.size).sum
        val failed = l.shownRuns.count(!_.record.succeeded)
        val unread = l.folder.warnings.size
        def count(n: Int, noun: String): String = s"$n ${if (n == 1) noun else noun + "s"}"
        status.setText(s"${count(l.shownRuns.size, "dataset")} · ${count(tables, "table")}" +
          (if (failed > 0) s" · $failed failed" else "") +
          (if (unread > 0) s" · $unread not read" else ""))
        status.getStyleClass.add(
          if (failed > 0) "danger" else if (l.shownRuns.isEmpty || unread > 0) "warning" else "success")
        if (unread > 0) status.setTooltip(new Tooltip(l.folder.warnings.mkString("\n")))
      case (None, None) => status.setText("No folder open")
    }
    status.setVisible(root.isDefined)
    status.setManaged(root.isDefined)
  }
}

/** The data viewer's entry point, kept apart from [[ViewerApp]]: `java -jar`
  * refuses a main class that is a JavaFX `Application` when JavaFX is on the
  * class path rather than the module path. `[folder]` opens a folder of job
  * outputs at start. */
object ViewerMain {
  def main(args: Array[String]): Unit = {
    com.transformer.sql.exec.SqlEngine.init()
    Application.launch(classOf[ViewerApp], args: _*)
  }
}
