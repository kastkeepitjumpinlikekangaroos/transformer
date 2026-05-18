package com.transformer.gui

import com.transformer.job.{JobFiles, SQLTask}

import java.nio.file.{Files, Path}
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control._
import javafx.scene.layout.GridPane
import javafx.stage.Stage
import scala.util.control.NonFatal

/** Modal capturing the fields needed for a new (or edited) table:
  *   - viewName (locked when editing)
  *   - main SQL (pre-filled with `SELECT * FROM <upstream>` when an upstream
  *     view is offered)
  *   - output format / partitionBy / maxPartitions (mapping straight to
  *     [[com.transformer.job.OutputFilePath]] + the directory loader's
  *     `output.json` schema)
  *
  * Used for both "Add table" and "Edit table attributes" — the SQL editor in
  * the details panel handles SQL-only edits.
  */
object AddTableDialog {

  /** Show "Add new table". If `upstream` is given, SQL pre-fills with a
    * `SELECT * FROM <upstream>` stub so the new node lands attached to the
    * selected one.
    */
  def showAdd(owner: Stage, jobDir: Path, upstream: Option[String]): Option[String] =
    show(owner, jobDir, isEdit = false, existing = None, upstream = upstream)

  /** Show "Edit table attributes" (output config only — SQL editing happens
    * inline in the details panel).
    */
  def showEdit(owner: Stage, jobDir: Path, task: SQLTask): Option[String] =
    show(owner, jobDir, isEdit = true, existing = Some(task), upstream = None)

  private def show(
      owner: Stage,
      jobDir: Path,
      isEdit: Boolean,
      existing: Option[SQLTask],
      upstream: Option[String]
  ): Option[String] = {
    val dialog = new Dialog[String]()
    dialog.initOwner(owner)
    dialog.setTitle(if (isEdit) "Edit table attributes" else "Add table")
    dialog.setHeaderText(
      if (isEdit) "Modify output settings for this table. SQL edits happen inline in the details panel."
      else "Define a new SQL-derived table. main.sql + (optional) output.json are written under tables/."
    )

    val okType = new ButtonType(if (isEdit) "Save" else "Create", ButtonType.OK.getButtonData)
    dialog.getDialogPane.getButtonTypes.addAll(okType, ButtonType.CANCEL)

    val nameField = new TextField()
    nameField.setPromptText("e.g. customer_summary")
    nameField.setText(existing.flatMap(_.viewName).getOrElse(""))
    nameField.setDisable(isEdit)

    val sqlArea = new TextArea()
    sqlArea.setPromptText("SELECT ...\nFROM ...")
    sqlArea.setPrefRowCount(10)
    sqlArea.setStyle("-fx-font-family: Monospaced;")
    if (!isEdit) {
      val seed = upstream match {
        case Some(name) => s"SELECT *\nFROM $name\n"
        case None       => "SELECT *\n"
      }
      sqlArea.setText(seed)
    } else {
      // In Edit mode, SQL editing lives in the inline editor. Disable the textarea
      // so the user understands this dialog only covers attributes.
      sqlArea.setText(existing.flatMap(t => loadExistingSql(t)).getOrElse("(SQL is edited inline)"))
      sqlArea.setDisable(true)
    }

    val formatCombo = new ComboBox[String]()
    formatCombo.getItems.addAll("csv", "parquet")
    formatCombo.setValue(existing.flatMap(_.outputFile).flatMap(_.format).getOrElse("csv"))

    val partitionField = new TextField()
    partitionField.setPromptText("Optional: day={{today}}")
    partitionField.setText(existing.map(extractPartitionBy).getOrElse(""))

    val maxPartsField = new TextField()
    maxPartsField.setPromptText("default: one file per source partition")
    maxPartsField.setText(existing.flatMap(_.outputFile).flatMap(_.maxPartitions).map(_.toString).getOrElse(""))

    val grid = new GridPane()
    grid.setHgap(8); grid.setVgap(8)
    grid.setPadding(new Insets(12, 4, 12, 4))
    var row = 0
    grid.add(new Label("View name:"),    0, row); grid.add(nameField,      1, row); row += 1
    grid.add(new Label("SQL:"),          0, row); grid.add(sqlArea,        1, row); row += 1
    grid.add(new Label("Format:"),       0, row); grid.add(formatCombo,    1, row); row += 1
    grid.add(new Label("Partition by:"), 0, row); grid.add(partitionField, 1, row); row += 1
    grid.add(new Label("Max parts:"),    0, row); grid.add(maxPartsField,  1, row); row += 1
    dialog.getDialogPane.setContent(grid)

    val okButton = dialog.getDialogPane.lookupButton(okType)
    okButton.addEventFilter(ActionEvent.ACTION, (ev: ActionEvent) => {
      val name = nameField.getText
      val format = formatChoice(formatCombo.getValue)
      val partitionBy = Option(partitionField.getText).map(_.trim).filter(_.nonEmpty)
      try {
        val maxPartitions = parseMaxPartitions(maxPartsField.getText)
        if (isEdit) {
          JobFiles.updateTableConfig(jobDir, name.trim, format, partitionBy, maxPartitions)
        } else {
          JobFiles.validateViewName(name).left.foreach(reason =>
            throw new IllegalArgumentException(reason))
          JobFiles.ensureNotInUse(jobDir, name.trim).left.foreach(reason =>
            throw new IllegalArgumentException(reason))
          val sql = Option(sqlArea.getText).getOrElse("")
          if (sql.trim.isEmpty)
            throw new IllegalArgumentException("SQL cannot be empty.")
          JobFiles.addTable(jobDir, name.trim, sql, format, partitionBy, maxPartitions)
        }
      } catch {
        case NonFatal(e) =>
          FxHelpers.showError(owner, if (isEdit) "Couldn't save table" else "Couldn't create table",
            Option(e.getMessage).getOrElse(e.toString))
          ev.consume()
      }
    })

    dialog.setResultConverter(bt => if (bt == okType) nameField.getText.trim else null)
    val res = dialog.showAndWait()
    if (res.isPresent) Option(res.get()) else None
  }

  /** `format = "csv"` is the directory loader's default — we treat it as "no
    * explicit format" so the on-disk `output.json` stays minimal. */
  private def formatChoice(raw: String): Option[String] =
    Option(raw).map(_.trim).filter(s => s.nonEmpty && !s.equalsIgnoreCase("csv"))

  private def parseMaxPartitions(raw: String): Option[Int] = {
    val s = Option(raw).map(_.trim).getOrElse("")
    if (s.isEmpty) None
    else try {
      val n = s.toInt
      if (n < 1) throw new IllegalArgumentException("Max parts must be >= 1.")
      Some(n)
    } catch {
      case _: NumberFormatException =>
        throw new IllegalArgumentException(s"Max parts must be a positive integer (got '$s').")
    }
  }

  /** Recover the user-set `partitionBy` from an existing SQLTask's output path
    * by stripping the canonical `<outputDir>/<viewName>` prefix. Best-effort —
    * if the prefix doesn't match (older job format, custom path), we leave the
    * field blank rather than guess.
    */
  private def extractPartitionBy(task: SQLTask): String = {
    val viewName = task.viewName.getOrElse(return "")
    val path = task.outputFile.map(_.path).getOrElse(return "")
    val marker = s"/$viewName"
    val idx = path.lastIndexOf(marker)
    if (idx < 0) return ""
    val suffix = path.substring(idx + marker.length)
    if (suffix.startsWith("/")) suffix.substring(1) else suffix
  }

  private def loadExistingSql(task: SQLTask): Option[String] = {
    task.sqlFile.flatMap { f =>
      try {
        val p = java.nio.file.Paths.get(f)
        if (Files.isRegularFile(p)) Some(Files.readString(p)) else None
      } catch {
        case NonFatal(_) => None
      }
    }
  }
}
