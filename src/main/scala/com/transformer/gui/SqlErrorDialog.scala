package com.transformer.gui

import javafx.geometry.{Insets, Pos}
import javafx.scene.Scene
import javafx.scene.control.{Button, Label, TextArea, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyEvent}
import javafx.scene.layout.{HBox, Priority, Region, VBox}
import javafx.scene.text.Font
import javafx.stage.{Modality, Stage}

/** Large, prominent error popup for SQL-console failures.
  *
  * The built-in JavaFX `Alert` uses a small default font and a narrow content
  * box, which makes the multi-line "Encountered X, was expecting …" messages
  * produced by JSqlParser hard to read. This dialog instead:
  *   * shows a big red heading so the user immediately sees the failure,
  *   * renders the error in a wide monospace, selectable, scrollable TextArea,
  *   * shows the offending SQL beneath so context isn't lost after dismissal,
  *   * offers Copy + Close (plus ESC) for keyboard-driven workflows.
  *
  * Styled to match the rest of the GUI's dark theme.
  */
object SqlErrorDialog {

  private val PanelBg  = "#2a2c38"
  private val Subtle   = "#9ba2b8"
  private val Accent   = "#f8a3a3"
  private val EditorBg = "#1e1e2a"
  private val EditorFg = "#d4d4d4"
  private val BtnBg    = "#3a3f55"
  private val BtnFg    = "#d7dcec"
  private val BtnPrim  = "#3d6ee8"

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
    title.setStyle(s"-fx-text-fill: $Accent; -fx-font-size: 22px; -fx-font-weight: bold;")

    val subtitle = new Label("The query could not be executed. Details below:")
    subtitle.setStyle(s"-fx-text-fill: $Subtle; -fx-font-size: 12px;")

    val errorArea = new TextArea(error)
    errorArea.setEditable(false)
    errorArea.setWrapText(true)
    errorArea.setFont(Font.font("Monospaced", 14))
    errorArea.setStyle(
      s"-fx-control-inner-background: $EditorBg; -fx-text-fill: $EditorFg; " +
        "-fx-highlight-fill: #264f78; -fx-highlight-text-fill: white;"
    )
    errorArea.setPrefRowCount(8)
    VBox.setVgrow(errorArea, Priority.ALWAYS)

    val sqlLabel = new Label("Query:")
    sqlLabel.setStyle(s"-fx-text-fill: $Subtle; -fx-font-size: 11px; -fx-font-weight: bold;")
    val sqlArea = new TextArea(sql.getOrElse(""))
    sqlArea.setEditable(false)
    sqlArea.setWrapText(false)
    sqlArea.setFont(Font.font("Monospaced", 12))
    sqlArea.setStyle(s"-fx-control-inner-background: $EditorBg; -fx-text-fill: $EditorFg;")
    sqlArea.setPrefRowCount(6)

    val copyBtn = new Button("Copy error")
    copyBtn.setTooltip(new Tooltip("Copy the error text to the clipboard"))
    copyBtn.setStyle(buttonStyle(BtnBg, BtnFg))
    copyBtn.setOnAction(_ => {
      val cb = Clipboard.getSystemClipboard
      val content = new ClipboardContent()
      content.putString(error)
      cb.setContent(content)
      ()
    })

    val closeBtn = new Button("Close")
    closeBtn.setDefaultButton(true)
    closeBtn.setCancelButton(true)
    closeBtn.setStyle(buttonStyle(BtnPrim, "white"))
    closeBtn.setOnAction(_ => stage.close())

    val spacer = new Region()
    HBox.setHgrow(spacer, Priority.ALWAYS)
    val buttonRow = new HBox(8, copyBtn, spacer, closeBtn)
    buttonRow.setAlignment(Pos.CENTER_LEFT)

    val root = new VBox(10)
    root.getChildren.addAll(title, subtitle, errorArea)
    val showSql = sql.exists(_.trim.nonEmpty)
    if (showSql) root.getChildren.addAll(sqlLabel, sqlArea)
    root.getChildren.add(buttonRow)
    root.setPadding(new Insets(20))
    root.setStyle(s"-fx-background-color: $PanelBg;")
    root.setPrefWidth(760)
    root.setPrefHeight(if (showSql) 540 else 380)

    val scene = new Scene(root)
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

  private def buttonStyle(bg: String, fg: String): String =
    s"-fx-background-color: $bg; -fx-text-fill: $fg; " +
      "-fx-padding: 8 22; -fx-background-radius: 4; -fx-font-size: 13px; -fx-font-weight: bold;"
}
