package com.transformer.gui

import javafx.application.Application
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.{CheckMenuItem, Menu, MenuBar, MenuItem, SeparatorMenuItem, SplitPane}
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import javafx.scene.layout.{BorderPane, VBox}
import javafx.stage.Stage

import java.nio.file.Paths

/** Top-level JavaFX Application for the transformer GUI.
  *
  * Layout:
  *   * Top — the header ([[ControlsPanel]]: the open job, execution time,
  *     output dir, run status, Run). The menus are in the macOS menu bar, or
  *     above the header elsewhere.
  *   * Center — vertical [[SplitPane]] with the DAG ([[DagOverlay]]) above
  *     and the [[ResultsTabPane]] below. The tab pane hosts every secondary
  *     panel (task details, output data, validations, SQL console, run log)
  *     so the DAG canvas gets the full window width.
  */
final class GuiApp extends Application {

  override def start(primaryStage: Stage): Unit = {
    Theme.install()
    primaryStage.setTitle("Transformer")

    val session = new JobSession()
    val canvas = new DagCanvas(session)
    val controls = new ControlsPanel(session, () => primaryStage)
    val details = new TaskDetailsPanel(session, () => primaryStage)
    val results = new ResultsTabPane(session, () => primaryStage, details)

    // Wire canvas double-click → load output rows at the bottom.
    canvas.setOnTaskActivated(idx => results.loadTaskOutput(idx))
    canvas.setOnInputActivated(idx => results.loadInputData(idx))
    // "Inspect validations" button inside the details panel → switch to the
    // Validations tab.
    details.setOnInspectValidations(idx => results.focusValidations(idx))

    val dagPane = new DagOverlay(session, canvas)

    val centerSplit = new SplitPane()
    centerSplit.setOrientation(Orientation.VERTICAL)
    centerSplit.getItems.addAll(dagPane, results)
    centerSplit.setDividerPositions(0.56)

    val root = new BorderPane()
    root.setTop(new VBox(buildMenuBar(primaryStage, session, canvas), controls))
    root.setCenter(centerSplit)

    val scene = new Scene(root, 1440, 920)
    Theme.style(scene)
    // CMD+R (CTRL+R on Linux/Windows) triggers the same code path as the Run
    // button. Wired on the scene rather than as a MenuItem accelerator so the
    // shortcut works without us needing to expose Run in the menu bar.
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN),
      new Runnable { def run(): Unit = controls.triggerRun() }
    )
    session.addListener(() =>
      primaryStage.setTitle(session.jobDir.map(d => s"Transformer — ${d.getFileName}").getOrElse("Transformer"))
    )
    primaryStage.setScene(scene)
    primaryStage.setMinWidth(900)
    primaryStage.setMinHeight(600)
    primaryStage.show()

    // Optional CLI arg: open a job dir at startup.
    val args = getParameters.getRaw
    if (args.size() >= 1) {
      val first = args.get(0).trim
      if (first.nonEmpty) session.openJobDir(Paths.get(first).toAbsolutePath.normalize())
    }
  }

  private def buildMenuBar(stage: Stage, session: JobSession, canvas: DagCanvas): MenuBar = {
    def shortcut(code: KeyCode, more: KeyCombination.Modifier*): KeyCodeCombination =
      new KeyCodeCombination(code, (KeyCombination.SHORTCUT_DOWN +: more): _*)

    val newProjectItem = new MenuItem("New Project…")
    newProjectItem.setAccelerator(shortcut(KeyCode.N))
    newProjectItem.setOnAction(_ => {
      NewProjectDialog.show(stage).foreach(p => session.openJobDir(p))
    })

    val openItem = new MenuItem("Open Job Directory…")
    openItem.setAccelerator(shortcut(KeyCode.O))
    openItem.setOnAction(_ => {
      FxHelpers.chooseDirectory(stage, "Open job directory", session.jobDir.map(_.toFile))
        .foreach(f => session.openJobDir(f.toPath))
    })

    val reloadItem = new MenuItem("Reload")
    // CMD+SHIFT+R — CMD+R is reserved for Run pipeline (wired on the scene).
    reloadItem.setAccelerator(shortcut(KeyCode.R, KeyCombination.SHIFT_DOWN))
    reloadItem.setOnAction(_ => session.jobDir.foreach(session.openJobDir))

    val addInputItem = new MenuItem("Add Input…")
    addInputItem.setOnAction(_ => session.jobDir.foreach { dir =>
      AddInputDialog.showAdd(stage, dir).foreach { name =>
        session.reloadPreservingSelection(Some(name))
      }
    })

    val addTableItem = new MenuItem("Add Table…")
    addTableItem.setOnAction(_ => session.jobDir.foreach { dir =>
      val upstream = session.selectedTaskIndex.flatMap(i =>
        session.dag.flatMap(_.nodes.lift(i)).flatMap(_.task.viewName)
      ).orElse(
        session.selectedInputIndex.flatMap(i => session.inputs.lift(i).map(_.viewName))
      )
      AddTableDialog.showAdd(stage, dir, upstream).foreach { name =>
        session.reloadPreservingSelection(Some(name))
      }
    })

    // Add Input/Table only make sense once a job is loaded — bind enabled-ness
    // to the session so the menu items reflect that.
    def syncEnabled(): Unit = {
      val haveJob = session.jobDir.isDefined
      addInputItem.setDisable(!haveJob)
      addTableItem.setDisable(!haveJob)
      reloadItem.setDisable(!haveJob)
    }
    session.addListener(() => syncEnabled())
    syncEnabled()

    val quitItem = new MenuItem("Quit")
    quitItem.setAccelerator(shortcut(KeyCode.Q))
    quitItem.setOnAction(_ => stage.close())

    val fileMenu = new Menu("File")
    fileMenu.getItems.addAll(
      newProjectItem,
      openItem,
      reloadItem,
      new SeparatorMenuItem(),
      addInputItem,
      addTableItem,
      new SeparatorMenuItem(),
      quitItem
    )

    val fitItem = new MenuItem("Fit DAG to Window")
    fitItem.setAccelerator(shortcut(KeyCode.DIGIT0))
    fitItem.setOnAction(_ => { canvas.fitToWindow(); canvas.render() })
    val zoomInItem = new MenuItem("Zoom In")
    zoomInItem.setAccelerator(shortcut(KeyCode.EQUALS))
    zoomInItem.setOnAction(_ => canvas.zoomIn())
    val zoomOutItem = new MenuItem("Zoom Out")
    zoomOutItem.setAccelerator(shortcut(KeyCode.MINUS))
    zoomOutItem.setOnAction(_ => canvas.zoomOut())
    val vimItem = new CheckMenuItem("Vim Keybindings")
    vimItem.selectedProperty().bindBidirectional(SqlEditor.vimEnabled)
    val viewMenu = new Menu("View")
    viewMenu.getItems.addAll(fitItem, zoomInItem, zoomOutItem, new SeparatorMenuItem(), vimItem)

    val shortcutsItem = new MenuItem("Keyboard Shortcuts")
    shortcutsItem.setAccelerator(shortcut(KeyCode.SLASH))
    shortcutsItem.setOnAction(_ => {
      import ShortcutsDialog.{Cmd, Shift}
      ShortcutsDialog.show(stage, Seq(
        s"${Cmd}O" -> "Open a job directory",
        s"${Cmd}N" -> "New project",
        s"${Cmd}R" -> "Run the job",
        s"$Shift${Cmd}R" -> "Reload the job directory",
        s"${Cmd}0" -> "Fit the DAG to the window",
        s"$Cmd= / $Cmd-" -> "Zoom the DAG in / out",
        s"${Cmd}/" -> "This sheet"
      ))
    })
    val helpMenu = new Menu("Help")
    helpMenu.getItems.add(shortcutsItem)

    val bar = new MenuBar()
    bar.getMenus.addAll(fileMenu, viewMenu, helpMenu)
    // On macOS the menus belong in the screen's menu bar.
    bar.setUseSystemMenuBar(true)
    bar
  }
}

object GuiApp {
  def main(args: Array[String]): Unit = Application.launch(classOf[GuiApp], args: _*)
}
