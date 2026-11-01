package com.transformer.viewer

import atlantafx.base.theme.Styles
import com.transformer.gui.{FxHelpers, Icons, ShortcutsDialog, SqlEditor, Theme}

import javafx.application.Application
import javafx.geometry.{Orientation, Pos}
import javafx.scene.Scene
import javafx.scene.control.{Button, CheckMenuItem, Label, Menu, MenuBar, MenuItem, OverrunStyle, SeparatorMenuItem, SplitPane, Tooltip}
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import javafx.scene.layout.{BorderPane, HBox, Priority, Region, VBox}
import javafx.scene.shape.{Circle, Rectangle}
import javafx.stage.Stage

import java.nio.file.{Path, Paths}
import java.util.prefs.Preferences
import scala.util.control.NonFatal

/** The data viewer: a window over a folder of job outputs.
  *
  * Layout:
  *   * Top — the header: the folder, Open and Reload, and what it holds. The
  *     menus (File, View, Help) are in the macOS menu bar, or above it.
  *   * Left — the [[DatasetTree]]: every dataset, its tables and the inputs
  *     it read from outside the folder.
  *   * Centre — the [[LineageCanvas]] above the [[ViewerTabs]]: Details,
  *     Query, and a tab per sample.
  *
  * Selecting a table anywhere selects it everywhere; double-clicking one
  * opens a sample of its rows. Reading the folder runs on a worker.
  */
final class ViewerApp extends Application {

  override def start(stage: Stage): Unit = {
    Theme.install()
    stage.setTitle("Transformer data viewer")

    val session = new ViewerSession
    val tree = new DatasetTree(session)
    val canvas = new LineageCanvas(session)
    val tabs = new ViewerTabs(session, () => stage)
    val header = new ViewerHeader(session, () => chooseFolder(stage, session), () => reload(session))

    tree.setOnActivate(tabs.openSample)
    tree.setOnQuery(tabs.queryInNewTab)
    canvas.setOnActivate(tabs.openSample)
    // A node selected in the tree comes into view in the lineage.
    session.addListener(() => session.selection.foreach {
      case ViewerSelection.OfNode(n) => canvas.reveal(n)
      case _ => ()
    })

    val centre = new SplitPane(new LineageOverlay(session, canvas), tabs)
    centre.setOrientation(Orientation.VERTICAL)
    centre.setDividerPositions(0.55)
    val main = new SplitPane(tree, centre)
    main.setDividerPositions(0.2)
    SplitPane.setResizableWithParent(tree, false)

    val root = new BorderPane()
    root.setTop(new VBox(menuBar(stage, session, canvas, tabs, tree), header))
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

  private def menuBar(stage: Stage, session: ViewerSession, canvas: LineageCanvas, tabs: ViewerTabs,
      tree: DatasetTree): MenuBar = {
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
      new SeparatorMenuItem(),
      item("Quit", Some(shortcut(KeyCode.Q)))(stage.close()))

    val vim = new CheckMenuItem("Vim Keybindings")
    vim.selectedProperty().bindBidirectional(SqlEditor.vimEnabled)
    val view = new Menu("View")
    view.getItems.addAll(
      item("Find a Table", Some(shortcut(KeyCode.F)))(tree.filter.requestFocus()),
      item("Details", Some(shortcut(KeyCode.I)))(tabs.showDetails()),
      new SeparatorMenuItem(),
      item("Fit Lineage to Window", Some(shortcut(KeyCode.DIGIT0))) { canvas.fitToWindow(); canvas.render() },
      item("Zoom In", Some(shortcut(KeyCode.EQUALS)))(canvas.zoomIn()),
      item("Zoom Out", Some(shortcut(KeyCode.MINUS)))(canvas.zoomOut()),
      new SeparatorMenuItem(),
      vim)

    val help = new Menu("Help")
    help.getItems.add(item("Keyboard Shortcuts", Some(shortcut(KeyCode.SLASH))) {
      import ShortcutsDialog.Cmd
      ShortcutsDialog.show(stage, Seq(
        s"${Cmd}O" -> "Open a folder of job outputs",
        s"${Cmd}R" -> "Read the folder again",
        s"${Cmd}T" -> "A new query tab",
        s"${Cmd}F" -> "Find a table by name",
        s"${Cmd}I" -> "Show the Details tab",
        s"${Cmd}0" -> "Fit the lineage to the window",
        s"$Cmd= / $Cmd-" -> "Zoom the lineage in / out",
        "Double-click" -> "A table or input: open a sample of its rows",
        s"${Cmd}C" -> "In a grid of rows: copy the selected rows",
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

/** The header across the top: the folder that is open, Open and Reload, and a
  * count of what it holds (or that it is being read). */
final class ViewerHeader(session: ViewerSession, openFolder: () => Unit, reload: () => Unit) extends HBox {

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
  getChildren.addAll(titleGroup, folderGroup, spacer, status)

  session.addListener(() => refresh())
  refresh()

  private def refresh(): Unit = {
    val root = session.scanning.orElse(session.folder.map(_.root))
    folderName.setText(root.map(r => Option(r.getFileName).map(_.toString).getOrElse(r.toString)).getOrElse("No folder open"))
    folderPath.setText(root.map(_.toString).getOrElse(""))
    reloadButton.setDisable(session.folder.isEmpty)
    status.getStyleClass.setAll("label", "status-pill")
    (session.scanning, session.lineage) match {
      case (Some(_), _) =>
        status.setText("Reading the folder…")
        status.getStyleClass.add("running")
      case (None, Some(l)) =>
        val tables = l.shownRuns.map(_.tables.size).sum
        val failed = l.shownRuns.count(!_.record.succeeded)
        status.setText(s"${l.shownRuns.size} datasets · $tables tables" + (if (failed > 0) s" · $failed failed" else ""))
        status.getStyleClass.add(if (failed > 0) "danger" else if (l.shownRuns.isEmpty) "warning" else "success")
      case (None, None) => status.setText("No folder open")
    }
    status.setVisible(root.isDefined)
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
