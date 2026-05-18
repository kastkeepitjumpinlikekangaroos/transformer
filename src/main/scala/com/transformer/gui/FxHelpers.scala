package com.transformer.gui

import javafx.application.Platform
import javafx.scene.control.Alert
import javafx.scene.control.Alert.AlertType
import javafx.stage.{DirectoryChooser, Stage}

import java.io.File

/** Small JavaFX utilities shared across the GUI module. */
object FxHelpers {

  /** Run `body` on the JavaFX Application thread.
    *
    * Safe to call from any thread; if already on the FX thread, runs synchronously
    * (avoids the latency of going through the event loop for no reason).
    */
  def onFx(body: => Unit): Unit = {
    if (Platform.isFxApplicationThread) body
    else Platform.runLater(new Runnable { def run(): Unit = body })
  }

  /** Open a native folder picker rooted at `initial` (if it exists and is a
    * directory). Returns None if the user cancelled.
    */
  def chooseDirectory(owner: Stage, title: String, initial: Option[File]): Option[File] = {
    val chooser = new DirectoryChooser()
    chooser.setTitle(title)
    initial.foreach { dir =>
      if (dir.isDirectory) chooser.setInitialDirectory(dir)
    }
    Option(chooser.showDialog(owner))
  }

  /** Show a blocking error alert. Always call from the FX thread. */
  def showError(owner: Stage, header: String, body: String): Unit = {
    val alert = new Alert(AlertType.ERROR)
    alert.initOwner(owner)
    alert.setTitle("Error")
    alert.setHeaderText(header)
    alert.setContentText(body)
    alert.showAndWait()
    ()
  }
}
