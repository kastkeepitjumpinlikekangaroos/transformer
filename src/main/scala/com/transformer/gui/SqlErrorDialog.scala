package com.transformer.gui

import atlantafx.base.theme.Styles
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.{Button, Label, TextArea, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyEvent}
import javafx.scene.layout.{HBox, Priority, Region, VBox}
import javafx.stage.{Modality, Stage}

/** Large, prominent error popup for SQL-console failures.
  *
  * The built-in JavaFX `Alert` uses a small default font and a narrow content
  * box, which makes the multi-line "Encountered X, was expecting …" messages
  * produced by JSqlParser hard to read. This dialog instead:
  *   * shows a big red heading so the user immediately sees the failure,
  *   * renders the error in a wide monospace, selectable, scrollable TextArea,
  *   * shows the offending SQL beneath, highlighted, so context isn't lost
  *     after dismissal,
  *   * offers Copy + Close (plus ESC) for keyboard-driven workflows.
  */
object SqlErrorDialog {

  /** Show a modal error popup. Blocks until the user closes it.
    *
    * @param owner   parent stage; the dialog inherits app focus/position
    * @param heading short red banner heading, e.g. "Query failed"
    * @param error   full error text — multi-line is fine
    * @param sql     optional SQL that triggered the error, shown beneath
    */
  def show(owner: Stage, heading: String, error: String, sql: Option[String] = None): Unit = {
    val stage = new Stage()
    if (owner != null) {
      stage.initOwner(owner)
      stage.initModality(Modality.WINDOW_MODAL)
    } else {
      stage.initModality(Modality.APPLICATION_MODAL)
    }
    stage.setTitle(heading)

    val title = new Label(heading)
    title.getStyleClass.add("heading")

    val subtitle = new Label("The query could not be executed. Details below:")
    subtitle.getStyleClass.add("muted")

    val errorArea = new TextArea(error)
    errorArea.setEditable(false)
    errorArea.setWrapText(true)
    errorArea.getStyleClass.addAll("mono", "error-detail")
    errorArea.setPrefRowCount(8)
    VBox.setVgrow(errorArea, Priority.ALWAYS)

    val sqlLabel = new Label("QUERY")
    sqlLabel.getStyleClass.add("section-label")
    val sqlView = new SqlEditor()
    sqlView.setText(sql.getOrElse(""))
    sqlView.setEditable(false)
    sqlView.setPrefHeight(170)

    val copyBtn = new Button("Copy error", Icons.copy)
    copyBtn.setTooltip(new Tooltip("Copy the error text to the clipboard"))
    copyBtn.setOnAction(_ => {
      val content = new ClipboardContent()
      content.putString(error)
      Clipboard.getSystemClipboard.setContent(content)
      ()
    })

    val closeBtn = new Button("Close")
    closeBtn.getStyleClass.add(Styles.ACCENT)
    closeBtn.setDefaultButton(true)
    closeBtn.setCancelButton(true)
    closeBtn.setOnAction(_ => stage.close())

    val spacer = new Region()
    HBox.setHgrow(spacer, Priority.ALWAYS)
    val buttonRow = new HBox(8, copyBtn, spacer, closeBtn)
    buttonRow.setAlignment(Pos.CENTER_LEFT)

    val root = new VBox()
    root.getStyleClass.add("error-dialog")
    root.getChildren.addAll(title, subtitle, errorArea)
    val showSql = sql.exists(_.trim.nonEmpty)
    if (showSql) root.getChildren.addAll(sqlLabel, sqlView)
    root.getChildren.add(buttonRow)
    root.setPrefWidth(780)
    root.setPrefHeight(if (showSql) 580 else 380)

    val scene = new Scene(root)
    Theme.style(scene)
    scene.setOnKeyPressed((ev: KeyEvent) => {
      if (ev.getCode == KeyCode.ESCAPE) { stage.close(); ev.consume() }
    })
    stage.setScene(scene)
    stage.setResizable(true)
    stage.setMinWidth(440)
    stage.setMinHeight(280)
    stage.showAndWait()
    ()
  }
}
