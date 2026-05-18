package com.transformer.gui

import java.nio.file.Path
import javafx.animation.PauseTransition
import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Alert, Button, Label, ScrollPane, TextArea, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout.{BorderPane, HBox, Priority, Region, VBox}
import javafx.scene.paint.Color
import javafx.scene.text.{Font, Text}
import javafx.util.Duration

/** Read-only SQL display with syntax highlighting + a small toolbar.
  *
  * The body is one [[HBox]] per source line wrapped in a [[ScrollPane]] —
  * gives horizontal scroll instead of wrapping long lines into illegible
  * piles. Each [[javafx.scene.text.Text]] node holds one token (keyword,
  * string literal, etc.) and is coloured per [[SqlHighlighter.Kind]].
  *
  * The view is cheap to repopulate — every [[setSql]] tokenises from scratch
  * and rebuilds the line nodes. SQL bodies in this project are short (tens
  * to a few hundred lines), so this is plenty fast.
  *
  * @param showOpenInEditor when true the toolbar shows an "Open in editor"
  *                         button alongside Copy. The button is only enabled
  *                         once [[setSourceFile]] is called with `Some(path)`.
  */
final class SqlView(showOpenInEditor: Boolean) extends BorderPane {

  private val GutterColor   = Color.web("#4f5670")
  private val Background    = "#1e1e2a"
  private val ToolbarFg     = "#c5cad8"

  private val GutterFont = Font.font("Monospaced", 11)
  private val CodeFont   = Font.font("Monospaced", 13)

  private var currentSql: String = ""
  private var currentFile: Option[Path] = None
  private var editing: Boolean = false

  private val placeholderLabel = new Label("(no SQL)")
  placeholderLabel.setStyle(s"-fx-text-fill: #6a708a; -fx-padding: 12; -fx-font-style: italic;")

  private val linesBox = new VBox()
  linesBox.setSpacing(0)
  linesBox.setPadding(new Insets(8, 12, 8, 6))
  linesBox.setFillWidth(false)
  linesBox.setStyle(s"-fx-background-color: $Background;")

  private val scroll = new ScrollPane(linesBox)
  scroll.setFitToWidth(false)
  scroll.setFitToHeight(false)
  scroll.setPannable(true)
  scroll.setStyle(s"-fx-background: $Background; -fx-background-color: $Background;")
  VBox.setVgrow(scroll, Priority.ALWAYS)

  // Editable buffer shown only in edit mode. Hosted alongside the read-only
  // viewer (we swap them via BorderPane.setCenter) so highlighting in view
  // mode stays unchanged.
  private val editArea = new TextArea()
  editArea.setStyle(
    s"-fx-control-inner-background: $Background; " +
      "-fx-text-fill: #d4d4d4; " +
      "-fx-highlight-fill: #2b4f7a; " +
      "-fx-highlight-text-fill: #ffffff; " +
      "-fx-font-family: Monospaced; " +
      "-fx-font-size: 13px;"
  )
  editArea.setWrapText(false)

  private val copyButton = makeToolbarButton("Copy")
  copyButton.setTooltip(new Tooltip("Copy SQL to the clipboard"))
  copyButton.setOnAction(_ => copyToClipboard())

  private val openEditorButton = makeToolbarButton("Open in editor")
  openEditorButton.setTooltip(new Tooltip(s"Open the source SQL file.\n${ExternalEditor.describe}"))
  openEditorButton.setOnAction(_ => openInEditor())
  openEditorButton.setDisable(true)

  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)

  private val toolbar = new HBox(6)
  toolbar.setAlignment(Pos.CENTER_LEFT)
  toolbar.setPadding(new Insets(0, 0, 4, 0))
  toolbar.getChildren.add(spacer)
  toolbar.getChildren.add(copyButton)
  if (showOpenInEditor) toolbar.getChildren.add(openEditorButton)

  setStyle(s"-fx-background-color: $Background;")
  setTop(toolbar)
  setCenter(scroll)
  linesBox.getChildren.add(placeholderLabel)

  /** Replace the displayed SQL. Passing `null` or `""` clears the view. If the
    * view is currently in edit mode, the editable buffer is reseeded too so
    * `getCurrentSql` reflects the new content immediately.
    */
  def setSql(sql: String): Unit = {
    currentSql = if (sql == null) "" else sql
    if (editing) editArea.setText(currentSql)
    rebuild()
  }

  /** Switch between read-only (default) and editable display. In edit mode the
    * line-by-line highlighted renderer is hidden and a plain monospace
    * TextArea takes its place, seeded with the current SQL.
    *
    * Save/Cancel buttons are owned by the parent panel — call
    * [[getCurrentSql]] to read out the buffer when the user clicks Save.
    */
  def setEditable(editable: Boolean): Unit = {
    if (editing == editable) return
    editing = editable
    if (editing) {
      editArea.setText(currentSql)
      setCenter(editArea)
      // Read-only-only actions don't apply while editing.
      copyButton.setDisable(true)
      openEditorButton.setDisable(true)
    } else {
      setCenter(scroll)
      copyButton.setDisable(false)
      openEditorButton.setDisable(currentFile.isEmpty || !showOpenInEditor)
    }
  }

  /** Returns the editable buffer's current text when in edit mode; otherwise
    * returns the last value passed to [[setSql]].
    */
  def getCurrentSql: String = if (editing) editArea.getText else currentSql

  /** Observable view of the editable text buffer. Hosting panels can attach a
    * listener to capture in-progress edits (e.g. so a session refresh that
    * rebuilds the surrounding container doesn't drop unsaved work).
    */
  def editTextProperty: javafx.beans.value.ObservableStringValue = editArea.textProperty()

  /** True iff the view is currently accepting edits. */
  def isEditing: Boolean = editing

  /** Bind an on-disk source file to the "Open in editor" button. Pass `None`
    * (e.g. for the rendered-SQL view, or when the task is built from
    * `sqlString`) to keep the button disabled.
    */
  def setSourceFile(file: Option[Path]): Unit = {
    currentFile = file
    openEditorButton.setDisable(file.isEmpty || !showOpenInEditor)
  }

  def clear(): Unit = setSql("")

  private def rebuild(): Unit = {
    linesBox.getChildren.clear()
    if (currentSql.isEmpty) {
      linesBox.getChildren.add(placeholderLabel)
      return
    }
    val lines = SqlHighlighter.splitIntoLines(SqlHighlighter.tokenize(currentSql))
    val total = lines.size
    val gutterWidth = math.max(2, total.toString.length)
    var n = 1
    val it = lines.iterator
    while (it.hasNext) {
      addLine(n, gutterWidth, it.next())
      n += 1
    }
  }

  private def addLine(number: Int, gutterWidth: Int, tokens: Vector[SqlHighlighter.Token]): Unit = {
    val line = new HBox(0)
    line.setAlignment(Pos.BASELINE_LEFT)
    line.setPadding(new Insets(0))

    val gutter = new Text(String.format(s"%${gutterWidth}d  ", Integer.valueOf(number)))
    gutter.setFill(GutterColor)
    gutter.setFont(GutterFont)
    line.getChildren.add(gutter)

    if (tokens.isEmpty) {
      // Keep an empty line at the same height as a non-empty one so the
      // gutter numbers stay aligned vertically.
      val spacer = new Text(" ")
      spacer.setFont(CodeFont)
      line.getChildren.add(spacer)
    } else {
      val tit = tokens.iterator
      while (tit.hasNext) {
        val tk = tit.next()
        val t = new Text(tk.text)
        t.setFont(CodeFont)
        t.setFill(colorFor(tk.kind))
        line.getChildren.add(t)
      }
    }
    linesBox.getChildren.add(line)
  }

  private def colorFor(kind: SqlHighlighter.Kind): Color = kind match {
    case SqlHighlighter.Kind.Keyword    => Color.web("#569cd6")
    case SqlHighlighter.Kind.Function   => Color.web("#dcdcaa")
    case SqlHighlighter.Kind.StringLit  => Color.web("#ce9178")
    case SqlHighlighter.Kind.NumberLit  => Color.web("#b5cea8")
    case SqlHighlighter.Kind.Comment    => Color.web("#6a9955")
    case SqlHighlighter.Kind.Template   => Color.web("#c586c0")
    case SqlHighlighter.Kind.Punct      => Color.web("#d4d4d4")
    case SqlHighlighter.Kind.Identifier => Color.web("#d4d4d4")
    case SqlHighlighter.Kind.Whitespace => Color.web("#d4d4d4")
  }

  private def copyToClipboard(): Unit = {
    val content = new ClipboardContent()
    content.putString(currentSql)
    val ok = Clipboard.getSystemClipboard.setContent(content)
    System.err.println(s"[SqlView] copy clicked — ${currentSql.length} char(s), ok=$ok")
    flashButton(copyButton, if (ok) "Copied!" else "Copy failed")
  }

  private def openInEditor(): Unit = {
    val path = currentFile.getOrElse {
      flashButton(openEditorButton, "(no file)")
      return
    }
    System.err.println(s"[SqlView] open-in-editor clicked — $path")
    try {
      ExternalEditor.openWithCallback(
        path,
        t => FxHelpers.onFx(showLaunchAlert(t))
      )
      flashButton(openEditorButton, "Launching…")
    } catch {
      case t: Throwable => showLaunchAlert(t)
    }
  }

  private def showLaunchAlert(t: Throwable): Unit = {
    val alert = new Alert(Alert.AlertType.ERROR)
    alert.setTitle("Could not launch editor")
    alert.setHeaderText("Could not launch external editor")
    alert.setContentText(
      s"${t.getMessage}\n\n" +
        "On first use macOS may need to grant the 'java' process " +
        "permission to control Terminal (System Settings → Privacy & Security " +
        "→ Automation). Or set TRANSFORMER_EDITOR to a command that opens a " +
        "file directly (e.g. 'code', 'subl', 'gvim')."
    )
    alert.showAndWait()
    ()
  }

  /** Briefly swap a button's label so the user sees their click registered. */
  private def flashButton(btn: Button, msg: String): Unit = {
    val original = btn.getText
    btn.setText(msg)
    val pause = new PauseTransition(Duration.millis(1200))
    pause.setOnFinished(_ => btn.setText(original))
    pause.play()
  }

  private def makeToolbarButton(text: String): Button = {
    val b = new Button(text)
    val base  = "#3a3f55"
    val hover = "#4a5070"
    val press = "#2a2f45"
    def style(bg: String): String =
      s"-fx-background-color: $bg; " +
        s"-fx-text-fill: $ToolbarFg; " +
        "-fx-background-radius: 4; " +
        "-fx-padding: 4 12; " +
        "-fx-font-size: 11px; " +
        "-fx-cursor: hand;"
    b.setStyle(style(base))
    b.setOnMouseEntered(_ => if (!b.isDisabled) b.setStyle(style(hover)))
    b.setOnMouseExited(_ => b.setStyle(style(base)))
    b.setOnMousePressed(_ => if (!b.isDisabled) b.setStyle(style(press)))
    b.setOnMouseReleased(_ => b.setStyle(style(if (b.isHover && !b.isDisabled) hover else base)))
    b
  }
}
