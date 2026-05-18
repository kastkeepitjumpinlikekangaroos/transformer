package com.transformer.gui

import com.transformer.job.{InputFilePath, JobFiles}

import java.io.File
import java.nio.file.Path
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control._
import javafx.scene.layout.{GridPane, HBox, Priority}
import javafx.stage.{FileChooser, Stage}
import scala.util.control.NonFatal

/** Modal that captures every field needed to write an
  * `inputs/<viewName>/config.json` for the directory-loader. Used both for
  * "Add input" (when `existing` is None) and "Edit input" (when `existing` is
  * Some — fields are pre-filled and viewName is locked).
  *
  * Returns the viewName on successful write, None if the user cancelled.
  */
object AddInputDialog {

  /** Show "Add new input" — viewName is editable, defaults are blank. */
  def showAdd(owner: Stage, jobDir: Path): Option[String] = show(owner, jobDir, None)

  /** Show "Edit input" — viewName is read-only, all fields pre-populated from
    * `existing`. The on-disk path may have been resolved to an absolute path
    * by [[com.transformer.job.DirectoryJobLoader]] — [[JobFiles.updateInputConfig]]
    * will re-relativize it before writing back.
    */
  def showEdit(owner: Stage, jobDir: Path, existing: InputFilePath): Option[String] =
    show(owner, jobDir, Some(existing))

  private def show(owner: Stage, jobDir: Path, existing: Option[InputFilePath]): Option[String] = {
    val isEdit = existing.isDefined
    val dialog = new Dialog[String]()
    dialog.initOwner(owner)
    dialog.setTitle(if (isEdit) "Edit input" else "Add input")
    dialog.setHeaderText(
      if (isEdit) "Modify the input's config.json. Saving rewrites the file on disk."
      else "Define a new input view. Its directory and config.json are written under inputs/."
    )

    val okType = new ButtonType(if (isEdit) "Save" else "Create", ButtonType.OK.getButtonData)
    dialog.getDialogPane.getButtonTypes.addAll(okType, ButtonType.CANCEL)

    val nameField = new TextField()
    nameField.setPromptText("e.g. raw_orders")
    nameField.setText(existing.map(_.viewName).getOrElse(""))
    nameField.setDisable(isEdit)

    val pathField = new TextField()
    pathField.setPromptText("data/orders.csv or /absolute/path or gs://...")
    pathField.setText(existing.map(_.path).getOrElse(""))
    HBox.setHgrow(pathField, Priority.ALWAYS)
    val pathBrowse = new Button("Browse…")
    pathBrowse.setOnAction(_ => {
      val chooser = new FileChooser()
      chooser.setTitle("Choose input file")
      Option(pathField.getText).map(_.trim).filter(_.nonEmpty).foreach { t =>
        val f = new File(t)
        val anchor = if (f.isDirectory) f else f.getParentFile
        if (anchor != null && anchor.isDirectory) chooser.setInitialDirectory(anchor)
      }
      Option(chooser.showOpenDialog(owner)).foreach(f => pathField.setText(f.getAbsolutePath))
    })
    val pathRow = new HBox(6, pathField, pathBrowse)

    val formatCombo = new ComboBox[String]()
    formatCombo.getItems.addAll("(auto)", "csv", "parquet")
    formatCombo.setValue(existing.flatMap(_.format).getOrElse("(auto)"))

    val cacheCheck = new CheckBox("Cache fully in memory (default)")
    cacheCheck.setSelected(existing.forall(_.cache))
    cacheCheck.setTooltip(new Tooltip(
      "Off = stream from disk on every read (necessary for multi-GB parquet inputs)."
    ))

    val optionsArea = new TextArea()
    optionsArea.setPromptText("Optional, one per line:\n  header=true\n  delimiter=,")
    optionsArea.setPrefRowCount(4)
    optionsArea.setText(
      existing.map(_.options.toSeq.sortBy(_._1)
        .map { case (k, v) => s"$k=$v" }.mkString("\n"))
        .getOrElse("")
    )

    val grid = new GridPane()
    grid.setHgap(8); grid.setVgap(8)
    grid.setPadding(new Insets(12, 4, 12, 4))
    grid.add(new Label("View name:"), 0, 0); grid.add(nameField, 1, 0)
    grid.add(new Label("Path:"),       0, 1); grid.add(pathRow,    1, 1)
    grid.add(new Label("Format:"),     0, 2); grid.add(formatCombo, 1, 2)
    grid.add(new Label(""),            0, 3); grid.add(cacheCheck,  1, 3)
    grid.add(new Label("Options:"),    0, 4); grid.add(optionsArea, 1, 4)
    dialog.getDialogPane.setContent(grid)

    // Validate and write inside the OK event filter so failures keep the dialog open.
    val okButton = dialog.getDialogPane.lookupButton(okType)
    okButton.addEventFilter(ActionEvent.ACTION, (ev: ActionEvent) => {
      val name = nameField.getText
      val path = pathField.getText
      val format = formatChoice(formatCombo.getValue)
      try {
        val options = parseOptions(optionsArea.getText)
        if (isEdit) {
          JobFiles.updateInputConfig(jobDir, name.trim, path.trim, format, cacheCheck.isSelected, options)
        } else {
          JobFiles.validateViewName(name).left.foreach(reason =>
            throw new IllegalArgumentException(reason))
          JobFiles.ensureNotInUse(jobDir, name.trim).left.foreach(reason =>
            throw new IllegalArgumentException(reason))
          JobFiles.addInput(jobDir, name.trim, path.trim, format, cacheCheck.isSelected, options)
        }
      } catch {
        case NonFatal(e) =>
          FxHelpers.showError(owner, if (isEdit) "Couldn't save input" else "Couldn't create input",
            Option(e.getMessage).getOrElse(e.toString))
          ev.consume()
      }
    })

    dialog.setResultConverter(bt => if (bt == okType) nameField.getText.trim else null)
    val res = dialog.showAndWait()
    if (res.isPresent) Option(res.get()) else None
  }

  /** Map the combo's "(auto)" sentinel to `None`. */
  private def formatChoice(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(s => s.nonEmpty && s != "(auto)")

  /** Parse the options textarea: one `key=value` per line, blanks ignored,
    * `#`-prefixed lines treated as comments. Whitespace around the `=` is
    * stripped.
    */
  private def parseOptions(raw: String): Map[String, String] = {
    if (raw == null) return Map.empty
    val out = scala.collection.mutable.LinkedHashMap.empty[String, String]
    raw.linesIterator.foreach { rawLine =>
      val line = rawLine.trim
      if (line.nonEmpty && !line.startsWith("#")) {
        val eq = line.indexOf('=')
        if (eq < 0)
          throw new IllegalArgumentException(s"Options line is missing '=': '$line'")
        val k = line.substring(0, eq).trim
        val v = line.substring(eq + 1).trim
        if (k.isEmpty)
          throw new IllegalArgumentException(s"Options line has empty key: '$line'")
        out(k) = v
      }
    }
    out.toMap
  }
}
