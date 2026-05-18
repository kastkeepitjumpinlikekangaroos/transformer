package com.transformer.gui

import com.transformer.job.JobFiles

import java.nio.file.Path
import javafx.stage.Stage
import scala.util.control.NonFatal

/** "New project" UX. There's no dedicated JavaFX dialog — we just present a
  * native directory picker, then call into [[JobFiles.createNewProject]].
  *
  * Returns the project path on success, None if the user cancelled or if
  * project creation failed (the user has already seen an error alert in that
  * case).
  */
object NewProjectDialog {

  def show(owner: Stage): Option[Path] = {
    val initial = Option(System.getProperty("user.home")).map(new java.io.File(_))
    FxHelpers.chooseDirectory(owner, "Choose new project directory", initial).flatMap { f =>
      val path = f.toPath
      try {
        JobFiles.createNewProject(path)
        Some(path)
      } catch {
        case NonFatal(e) =>
          FxHelpers.showError(owner, "Could not create project",
            Option(e.getMessage).getOrElse(e.toString))
          None
      }
    }
  }
}
