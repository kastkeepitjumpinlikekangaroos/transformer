package com.transformer.gui

import javafx.application.Application
import javafx.geometry.Orientation
import javafx.scene.Scene
import javafx.scene.control.{Menu, MenuBar, MenuItem, SplitPane}
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination}
import javafx.scene.layout.{BorderPane, Pane}
import javafx.stage.Stage

import java.nio.file.Paths

/** Top-level JavaFX Application for the transformer GUI.
  *
  * Layout:
  *   * Top — menu bar
  *   * Left — [[ControlsPanel]] (open dir, execution time, output dir, Run)
  *   * Center — vertical [[SplitPane]] with the DAG canvas above and the results
  *     tabs below
  *   * Right — [[TaskDetailsPanel]] (selected task's SQL + status)
  */
final class GuiApp extends Application {

  override def start(primaryStage: Stage): Unit = {
    primaryStage.setTitle("Transformer")

    val session = new JobSession()
    val canvas = new DagCanvas(session)
    val controls = new ControlsPanel(session, () => primaryStage)
    val details = new TaskDetailsPanel(session)
    val results = new ResultsTabPane(session)

    // Wire canvas double-click → load output rows at the bottom.
    canvas.setOnTaskActivated(idx => results.loadTaskOutput(idx))
    canvas.setOnInputActivated(idx => results.loadInputData(idx))
    // Right-side "Inspect validations" button → switch to the Validations tab.
    details.setOnInspectValidations(idx => results.focusValidations(idx))

    val canvasPane = new Pane(canvas)
    canvasPane.setStyle("-fx-background-color: #1e1e2a;")
    canvas.widthProperty().bind(canvasPane.widthProperty())
    canvas.heightProperty().bind(canvasPane.heightProperty())

    val centerSplit = new SplitPane()
    centerSplit.setOrientation(Orientation.VERTICAL)
    centerSplit.getItems.addAll(canvasPane, results)
    centerSplit.setDividerPositions(0.65)

    val root = new BorderPane()
    root.setTop(buildMenuBar(primaryStage, session, canvas))
    root.setLeft(controls)
    root.setCenter(centerSplit)
    root.setRight(details)

    val scene = new Scene(root, 1400, 900)
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
    val openItem = new MenuItem("Open Job Directory…")
    openItem.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN))
    openItem.setOnAction(_ => {
      FxHelpers.chooseDirectory(stage, "Open job directory", session.jobDir.map(_.toFile))
        .foreach(f => session.openJobDir(f.toPath))
    })

    val reloadItem = new MenuItem("Reload")
    reloadItem.setAccelerator(new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN))
    reloadItem.setOnAction(_ => session.jobDir.foreach(session.openJobDir))

    val quitItem = new MenuItem("Quit")
    quitItem.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN))
    quitItem.setOnAction(_ => stage.close())

    val fileMenu = new Menu("File")
    fileMenu.getItems.addAll(openItem, reloadItem, quitItem)

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
