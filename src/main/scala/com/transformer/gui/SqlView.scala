package com.transformer.gui

import atlantafx.base.theme.Styles
import javafx.animation.PauseTransition
import javafx.scene.Node
import javafx.scene.control.{Button, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout.BorderPane
import javafx.util.Duration

/** A task's or a validation's SQL: a [[SqlEditor]], read-only until the
  * host makes it editable, and the Copy button that acts on it, which the
  * host places in its own header ([[actions]]).
  *
  * While editing, [[setSql]] changes only the text Cancel goes back to, so a
  * session refresh never drops an edit in progress.
  */
final class SqlView extends BorderPane {

  private var currentSql: String = ""
  private var editing: Boolean = false

  private val editor = new SqlEditor()
  editor.setEditable(false)
  editor.setPlaceholderText("(no SQL)")

  private val copyButton = toolbarButton("Copy", Icons.copy)
  copyButton.setTooltip(new Tooltip("Copy SQL to the clipboard"))
  copyButton.setOnAction(_ => copyToClipboard())

  setCenter(editor)

  /** The buttons that act on the SQL (Copy), for the host's header. */
  val actions: Seq[Node] = Seq(copyButton)

  /** Size the view to its SQL, up to `maxLines` lines. */
  def fitLines(maxLines: Int): Unit = {
    val lines = math.min(maxLines, math.max(2, currentSql.count(_ == '\n') + 1))
    setPrefHeight(lines * SqlEditor.lineHeight + 16)
  }

  /** Show `sql` (`null` for none). While editing, the edit keeps its text and
    * `sql` is what Cancel goes back to. */
  def setSql(sql: String): Unit = {
    currentSql = if (sql == null) "" else sql
    if (!editing) editor.setText(currentSql)
  }

  /** Switch between showing the SQL and editing it. Save and Cancel belong to
    * the host, which reads the edit with [[getCurrentSql]]; leaving edit mode
    * puts back the text last given to [[setSql]]. */
  def setEditable(editable: Boolean): Unit = {
    if (editing == editable) return
    editing = editable
    if (!editing) editor.setText(currentSql)
    editor.setEditable(editing)
    copyButton.setDisable(editing)
    if (editing) editor.focusEditor()
  }

  /** The edit's text in edit mode; otherwise what was last given to [[setSql]]. */
  def getCurrentSql: String = if (editing) editor.text else currentSql

  /** What vim's `:w` and `:q` do while editing; see [[SqlEditor.setOnWrite]]. */
  def setOnWrite(f: () => Option[String]): Unit = editor.setOnWrite(f)
  def setOnQuit(f: Boolean => Option[String]): Unit = editor.setOnQuit(f)

  /** True when the edit's text differs from the SQL it started from. */
  def isModified: Boolean = editing && editor.text != currentSql

  private def copyToClipboard(): Unit = {
    val content = new ClipboardContent()
    content.putString(currentSql)
    val ok = Clipboard.getSystemClipboard.setContent(content)
    flashButton(copyButton, if (ok) "Copied" else "Copy failed")
  }

  /** Briefly swap a button's label so the user sees their click registered. */
  private def flashButton(btn: Button, msg: String): Unit = {
    val original = btn.getText
    btn.setText(msg)
    val pause = new PauseTransition(Duration.millis(1200))
    pause.setOnFinished(_ => btn.setText(original))
    pause.play()
  }

  private def toolbarButton(text: String, icon: Node): Button = {
    val b = new Button(text, icon)
    b.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
    b.setFocusTraversable(false)
    b
  }
}
