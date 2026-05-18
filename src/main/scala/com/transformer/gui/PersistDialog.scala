package com.transformer.gui

import java.io.File
import javafx.event.ActionEvent
import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Alert, Button, ButtonType, ComboBox, Dialog, Label, TextField, Tooltip}
import javafx.scene.layout.{GridPane, HBox, Priority}
import javafx.stage.{DirectoryChooser, Stage}

/** Configuration captured from the persist dialog. Mirrors [[com.transformer.job.OutputFilePath]]'s
  * fields so the result-persister can reuse the same writer machinery the
  * library uses for SQLTask outputs.
  */
final case class PersistConfig(
    outputDir: String,
    format: String,
    maxPartitions: Option[Int],
    csvHeader: Boolean
)

/** Modal dialog asking the user where (and how) to persist an interactive
  * SQL result. Mirrors what [[com.transformer.job.OutputFilePath]] exposes
  * programmatically — format, directory, optional `maxPartitions`, plus the
  * CSV-only header toggle.
  *
  * Validation runs in an `ActionEvent` filter on the OK button so failures
  * keep the dialog open (with an error alert) instead of returning a null
  * result.
  */
object PersistDialog {

  def show(owner: Stage, session: JobSession, defaultFormat: String): Option[PersistConfig] = {
    val dialog = new Dialog[PersistConfig]()
    dialog.initOwner(owner)
    dialog.setTitle("Persist query results")
    dialog.setHeaderText("Write the current result set to disk as part files.")

    val okType = new ButtonType("Write", ButtonType.OK.getButtonData)
    dialog.getDialogPane.getButtonTypes.addAll(okType, ButtonType.CANCEL)

    val dirField = new TextField()
    session.effectiveOutputDirRendered.foreach { base =>
      dirField.setText(new File(base, "interactive").getAbsolutePath)
    }
    dirField.setPromptText("/path/to/output-dir")
    HBox.setHgrow(dirField, Priority.ALWAYS)
    val browseBtn = new Button("Browse…")
    browseBtn.setOnAction(_ => {
      val chooser = new DirectoryChooser()
      chooser.setTitle("Choose output directory")
      Option(dirField.getText).map(_.trim).filter(_.nonEmpty).foreach { t =>
        val f = new File(t)
        val anchor = if (f.isDirectory) f else f.getParentFile
        if (anchor != null && anchor.isDirectory) chooser.setInitialDirectory(anchor)
      }
      Option(chooser.showDialog(owner)).foreach(f => dirField.setText(f.getAbsolutePath))
    })
    val dirRow = new HBox(6, dirField, browseBtn)
    dirRow.setAlignment(Pos.CENTER_LEFT)

    val formatCombo = new ComboBox[String]()
    formatCombo.getItems.addAll("csv", "parquet")
    formatCombo.setValue(defaultFormat)

    val maxPartsField = new TextField()
    maxPartsField.setPromptText("default: one file per source partition")
    maxPartsField.setTooltip(new Tooltip(
      "Optional. Sets OutputFilePath.maxPartitions — caps the number of part files. " +
        "1 collapses everything into a single part file."
    ))

    val headerCheck = new javafx.scene.control.CheckBox("Write header row")
    headerCheck.setSelected(true)
    val headerHint = new Label("(CSV only)")
    headerHint.setStyle("-fx-text-fill: #9ba2b8; -fx-font-size: 11px;")
    val headerRow = new HBox(8, headerCheck, headerHint)
    headerRow.setAlignment(Pos.CENTER_LEFT)
    def syncHeaderEnabled(): Unit = {
      val isCsv = formatCombo.getValue == "csv"
      headerCheck.setDisable(!isCsv)
    }
    formatCombo.valueProperty().addListener((_, _, _) => syncHeaderEnabled())
    syncHeaderEnabled()

    val grid = new GridPane()
    grid.setHgap(8); grid.setVgap(8)
    grid.setPadding(new Insets(12, 4, 12, 4))
    grid.add(new Label("Output directory:"), 0, 0); grid.add(dirRow, 1, 0)
    grid.add(new Label("Format:"),            0, 1); grid.add(formatCombo, 1, 1)
    grid.add(new Label("Max part files:"),    0, 2); grid.add(maxPartsField, 1, 2)
    grid.add(new Label("CSV options:"),       0, 3); grid.add(headerRow, 1, 3)
    dialog.getDialogPane.setContent(grid)

    // Validate via an event filter so a bad value keeps the dialog open.
    val okButton = dialog.getDialogPane.lookupButton(okType)
    okButton.addEventFilter(ActionEvent.ACTION, (ev: ActionEvent) => {
      validate(dirField.getText, maxPartsField.getText) match {
        case Left(msg) =>
          val alert = new Alert(Alert.AlertType.ERROR, msg)
          alert.initOwner(owner)
          alert.showAndWait()
          ev.consume()
        case Right(_) => ()
      }
    })

    dialog.setResultConverter(bt => {
      if (bt == okType) {
        validate(dirField.getText, maxPartsField.getText) match {
          case Right(maxOpt) =>
            PersistConfig(
              outputDir = dirField.getText.trim,
              format = formatCombo.getValue,
              maxPartitions = maxOpt,
              csvHeader = headerCheck.isSelected
            )
          case Left(_) => null // shouldn't reach: filter consumed the event
        }
      } else null
    })

    val res = dialog.showAndWait()
    if (res.isPresent) Option(res.get()) else None
  }

  /** Returns Right(maxPartitions option) on success, Left(error message) on failure. */
  private def validate(dirRaw: String, maxRaw: String): Either[String, Option[Int]] = {
    val dir = Option(dirRaw).map(_.trim).getOrElse("")
    if (dir.isEmpty) return Left("Output directory is required.")
    val maxTrimmed = Option(maxRaw).map(_.trim).getOrElse("")
    if (maxTrimmed.isEmpty) Right(None)
    else try {
      val n = maxTrimmed.toInt
      if (n < 1) Left("Max part files must be >= 1.")
      else Right(Some(n))
    } catch {
      case _: NumberFormatException => Left(s"Max part files must be a positive integer (got '$maxTrimmed').")
    }
  }
}
