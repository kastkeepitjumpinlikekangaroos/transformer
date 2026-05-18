package com.transformer.gui

import javafx.application.Application
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.{Menu, MenuBar, MenuItem, SplitPane}
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import javafx.scene.layout.{BorderPane, Pane, VBox}
import javafx.stage.Stage

import java.nio.file.Paths

/** Top-level JavaFX Application for the transformer GUI.
  *
  * Layout:
  *   * Top — menu bar stacked above the horizontal [[ControlsPanel]] (open
  *     dir, execution time, output dir, Run)
  *   * Center — vertical [[SplitPane]] with the DAG canvas above and the
  *     [[ResultsTabPane]] below. The tab pane hosts every secondary panel
  *     (task details, output data, validations, SQL console, run log) so the
  *     DAG canvas gets the full window width.
  */
final class GuiApp extends Application {

  override def start(primaryStage: Stage): Unit = {
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

    val canvasPane = new Pane(canvas)
    canvasPane.setStyle("-fx-background-color: #1e1e2a;")
    canvas.widthProperty().bind(canvasPane.widthProperty())
    canvas.heightProperty().bind(canvasPane.heightProperty())

    val centerSplit = new SplitPane()
    centerSplit.setOrientation(Orientation.VERTICAL)
    centerSplit.getItems.addAll(canvasPane, results)
    centerSplit.setDividerPositions(0.6)

    val root = new BorderPane()
    val topStack = new VBox(buildMenuBar(primaryStage, session, canvas), controls)
    root.setTop(topStack)
    root.setCenter(centerSplit)

    val scene = new Scene(root, 1400, 900)
    // CMD+R (CTRL+R on Linux/Windows) triggers the same code path as the Run
    // button. Wired on the scene rather than as a MenuItem accelerator so the
    // shortcut works without us needing to expose Run in the menu bar.
    scene.getAccelerators.put(
      new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN),
      new Runnable { def run(): Unit = controls.triggerRun() }
    )
    primaryStage.setScene(scene)
    primaryStage.show()

    // Optional CLI arg: open a job dir at startup.
    val args = getParameters.getRaw
    if (args.size() >= 1) {
      val first = args.get(0).trim
      if (first.nonEmpty) session.openJobDir(Paths.get(first).toAbsolutePath.normalize())
    }
  }

  private def buildMenuBar(stage: Stage, session: JobSession, canvas: DagCanvas): MenuBar = {
    val newProjectItem = new MenuItem("New Project…")
    newProjectItem.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN))
    newProjectItem.setOnAction(_ => {
      NewProjectDialog.show(stage).foreach(p => session.openJobDir(p))
    })

    val openItem = new MenuItem("Open Job Directory…")
    openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN))
    openItem.setOnAction(_ => {
      FxHelpers.chooseDirectory(stage, "Open job directory", session.jobDir.map(_.toFile))
        .foreach(f => session.openJobDir(f.toPath))
    })

    val reloadItem = new MenuItem("Reload")
    // CMD+SHIFT+R — CMD+R is reserved for Run pipeline (wired on the scene).
    reloadItem.setAccelerator(
      new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)
    )
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
    quitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN))
    quitItem.setOnAction(_ => stage.close())

    val fileMenu = new Menu("File")
    fileMenu.getItems.addAll(
      newProjectItem,
      openItem,
      reloadItem,
      new javafx.scene.control.SeparatorMenuItem(),
      addInputItem,
      addTableItem,
      new javafx.scene.control.SeparatorMenuItem(),
      quitItem
    )

    val fitItem = new MenuItem("Fit DAG to window")
    fitItem.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN))
    fitItem.setOnAction(_ => { canvas.fitToWindow(); canvas.render() })
    val viewMenu = new Menu("View")
    viewMenu.getItems.add(fitItem)

    val bar = new MenuBar()
    bar.getMenus.addAll(fileMenu, viewMenu)
    bar
  }
}

object GuiApp {
  def main(args: Array[String]): Unit = Application.launch(classOf[GuiApp], args: _*)
}
