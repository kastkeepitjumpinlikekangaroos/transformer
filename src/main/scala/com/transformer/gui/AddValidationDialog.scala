package com.transformer.gui

import com.transformer.job.JobFiles

import java.nio.file.{Files, Path, Paths}
import javafx.event.ActionEvent
import javafx.geometry.Insets
import javafx.scene.control._
import javafx.scene.layout.GridPane
import javafx.scene.control.ButtonBar
import javafx.stage.Stage
import scala.util.control.NonFatal

/** Modal for adding *or* editing a validation. Validations live as small SQL
  * files under `tables/<table>/validations/<name>.sql` and should return zero
  * rows when the table is healthy.
  *
  * Both [[showAdd]] and [[showEdit]] return an [[Option[Result]]] — the
  * [[Result]] ADT distinguishes Save from Delete so the caller can refresh
  * UI state appropriately. None means the user cancelled without changes.
  */
object AddValidationDialog {

  sealed trait Result
  final case class Saved(name: String) extends Result
  /** The user clicked Delete in the edit form — the on-disk file is gone. */
  case object Deleted extends Result

  def showAdd(owner: Stage, jobDir: Path, tableViewName: String): Option[Result] =
    show(owner, jobDir, tableViewName, existing = None)

  def showEdit(
      owner: Stage,
      jobDir: Path,
      tableViewName: String,
      validationName: String,
      validationSqlFile: Option[String]
  ): Option[Result] = {
    val initialSql = validationSqlFile.flatMap { f =>
      try {
        val p = Paths.get(f)
        if (Files.isRegularFile(p)) Some(Files.readString(p)) else None
      } catch {
        case NonFatal(_) => None
      }
    }
    show(owner, jobDir, tableViewName, existing = Some((validationName, initialSql.getOrElse(""))))
  }

  private def show(
      owner: Stage,
      jobDir: Path,
      tableViewName: String,
      existing: Option[(String, String)]
  ): Option[Result] = {
    val isEdit = existing.isDefined
    val dialog = new Dialog[Result]()
    dialog.initOwner(owner)
    dialog.setTitle(if (isEdit) "Edit validation" else "Add validation")
    dialog.setHeaderText(
      s"Validations run after $tableViewName executes. Each is a SELECT that should return ZERO rows " +
        "when the table is healthy."
    )

    val okType = new ButtonType(if (isEdit) "Save" else "Create", ButtonType.OK.getButtonData)
    val deleteType = new ButtonType("Delete", ButtonBar.ButtonData.OTHER)
    if (isEdit) dialog.getDialogPane.getButtonTypes.addAll(okType, deleteType, ButtonType.CANCEL)
    else        dialog.getDialogPane.getButtonTypes.addAll(okType, ButtonType.CANCEL)

    val nameField = new TextField()
    nameField.setPromptText("e.g. no_negative_amounts")
    nameField.setText(existing.map(_._1).getOrElse(""))
    nameField.setDisable(isEdit)

    val sqlArea = new TextArea()
    sqlArea.setPrefRowCount(8)
    sqlArea.setStyle("-fx-font-family: Monospaced;")
    sqlArea.setText(existing.map(_._2).getOrElse(s"SELECT *\nFROM $tableViewName\nWHERE 1=0\n"))

    val grid = new GridPane()
    grid.setHgap(8); grid.setVgap(8)
    grid.setPadding(new Insets(12, 4, 12, 4))
    grid.add(new Label("Name:"), 0, 0); grid.add(nameField, 1, 0)
    grid.add(new Label("SQL:"),  0, 1); grid.add(sqlArea,   1, 1)
    dialog.getDialogPane.setContent(grid)

    // Validate + write inside the OK event filter so save/delete failures keep
    // the dialog open.
    val okButton = dialog.getDialogPane.lookupButton(okType)
    okButton.addEventFilter(ActionEvent.ACTION, (ev: ActionEvent) => {
      val name = if (isEdit) existing.get._1 else nameField.getText
      try {
        val sql = Option(sqlArea.getText).getOrElse("")
        if (sql.trim.isEmpty)
          throw new IllegalArgumentException("Validation SQL cannot be empty.")
        if (isEdit) {
          JobFiles.writeValidationSql(jobDir, tableViewName, name, sql)
        } else {
          JobFiles.validateViewName(name).left.foreach(reason =>
            throw new IllegalArgumentException(reason))
          JobFiles.addValidation(jobDir, tableViewName, name.trim, sql)
        }
      } catch {
        case NonFatal(e) =>
          FxHelpers.showError(owner, if (isEdit) "Couldn't save validation" else "Couldn't create validation",
            Option(e.getMessage).getOrElse(e.toString))
          ev.consume()
      }
    })

    if (isEdit) {
      val deleteButton = dialog.getDialogPane.lookupButton(deleteType)
      deleteButton.addEventFilter(ActionEvent.ACTION, (ev: ActionEvent) => {
        // Confirm before nuking the file.
        val confirm = new Alert(Alert.AlertType.CONFIRMATION,
          s"Delete validation '${existing.get._1}'? The .sql file will be removed.")
        confirm.initOwner(owner)
        confirm.setHeaderText("Delete validation")
        val pick = confirm.showAndWait()
        if (!pick.isPresent || pick.get() != ButtonType.OK) {
          ev.consume()
        } else {
          try JobFiles.deleteValidation(jobDir, tableViewName, existing.get._1)
          catch {
            case NonFatal(e) =>
              FxHelpers.showError(owner, "Couldn't delete validation",
                Option(e.getMessage).getOrElse(e.toString))
              ev.consume()
          }
        }
      })
    }

    dialog.setResultConverter(bt => {
      if (bt == okType) Saved(if (isEdit) existing.get._1 else nameField.getText.trim)
      else if (isEdit && bt == deleteType) Deleted
      else null
    })
    val res = dialog.showAndWait()
    if (res.isPresent) Option(res.get()) else None
  }
}
