package com.transformer.gui

import com.transformer.gui.VimKeyEvents.{Pressed, Shortcut}

import javafx.application.Platform
import javafx.beans.{InvalidationListener, WeakInvalidationListener}
import javafx.beans.property.{BooleanProperty, SimpleBooleanProperty}
import javafx.beans.value.ObservableValue
import javafx.scene.control.{ContextMenu, Label, MenuItem, SeparatorMenuItem, ToggleButton, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent, KeyCode, KeyEvent, MouseEvent}
import javafx.scene.layout.{BorderPane, HBox, Priority}
import javafx.scene.text.{Font, Text}
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.{Caret, CodeArea}
import org.fxmisc.richtext.model.{PlainTextChange, StyleSpansBuilder}

import java.util.{Collection => JCollection, Collections, Locale}
import java.util.function.IntFunction
import java.util.prefs.Preferences
import scala.util.control.NonFatal

/** The GUI's SQL editor, for writing SQL and for showing it read-only: a
  * RichTextFX code area with syntax highlighting ([[SqlHighlighter]]), line
  * numbers, a status line, and, while [[SqlEditor.vimEnabled]] is on, vim's
  * keys through a [[VimEngine]].
  *
  * Under vim the engine reads every key the editor gets, apart from the
  * window's shortcuts ([[VimKeyEvents]]), and the status line shows its mode,
  * the command being typed and its messages; `:w` and `:q` do what the host
  * says ([[setOnWrite]], [[setOnQuit]]). An editable editor keeps `<Esc>` even
  * with nothing to cancel, so an extra one never closes the dialog it is in.
  * Without vim it edits as a text area does, and `<CR>` keeps the line's
  * indent and `<Tab>` indents by two.
  */
final class SqlEditor extends BorderPane {
  import SqlEditor._

  private val area = new CodeArea()
  private val scroll = new VirtualizedScrollPane[CodeArea](area)

  /** The engine is editing: the area's change events are its own. */
  private var engineBusy = false
  private var highlightQueued = false
  private var gutterDigits = 2

  private var onWrite: () => Option[String] = () => Some("E32: No file name")
  private var onQuit: Boolean => Option[String] = _ => Some("Nothing to close here")

  private val buffer: VimBuffer = new VimBuffer {
    def text: String = area.getText
    def caret: Int = area.getCaretPosition
    def editable: Boolean = area.isEditable
    def replace(start: Int, end: Int, replacement: String): Unit = area.replaceText(start, end, replacement)
    def select(anchor: Int, caret: Int): Unit = {
      area.selectRange(anchor, caret)
      area.requestFollowCaret()
    }
    def visibleLines: (Int, Int) =
      try (area.firstVisibleParToAllParIndex, area.lastVisibleParToAllParIndex)
      catch { case NonFatal(_) => (0, math.max(0, area.getParagraphs.size - 1)) }
    def scrollTo(line: Int, where: VimScroll): Unit = where match {
      case VimScroll.Top => area.showParagraphAtTop(line)
      case VimScroll.Center => area.showParagraphAtCenter(line)
      case VimScroll.Bottom => area.showParagraphAtBottom(line)
    }
    def clipboard: Option[String] = clipboardText
    def setClipboard(text: String): Unit = putClipboard(text)
    def write(): Option[String] = onWrite()
    def quit(force: Boolean): Option[String] = onQuit(force)
  }

  private val engine = new VimEngine(buffer)

  // ---- the status line ----
  private val modeLabel = new Label("NORMAL")
  private val messageLabel = new Label()
  messageLabel.setMinWidth(0)
  messageLabel.setMaxWidth(Double.MaxValue)
  HBox.setHgrow(messageLabel, Priority.ALWAYS)
  private val pendingLabel = new Label()
  pendingLabel.getStyleClass.add("vim-pending")
  private val positionLabel = new Label()
  private val vimToggle = new ToggleButton("VIM")
  vimToggle.getStyleClass.add("vim-toggle")
  vimToggle.setFocusTraversable(false)
  vimToggle.setTooltip(new Tooltip("Vim keybindings in every SQL editor (View > Vim Keybindings)"))
  vimToggle.selectedProperty().bindBidirectional(vimEnabled)
  private val status = new HBox(modeLabel, messageLabel, pendingLabel, positionLabel, vimToggle)
  status.getStyleClass.add("editor-status")

  getStyleClass.add("sql-editor")
  area.setWrapText(false)
  area.setParagraphGraphicFactory(lineNumbers)
  setCenter(scroll)
  setBottom(status)

  area.addEventFilter(KeyEvent.KEY_PRESSED, (e: KeyEvent) => if (vimEnabled.get) vimPressed(e) else plainPressed(e))
  area.addEventFilter(KeyEvent.KEY_TYPED, (e: KeyEvent) => if (vimEnabled.get) vimTyped(e))
  area.addEventHandler(MouseEvent.MOUSE_RELEASED, (_: MouseEvent) =>
    if (vimEnabled.get) Platform.runLater(() => syncFromMouse())
  )
  area.plainTextChanges().subscribe((c: PlainTextChange) => {
    if (!engineBusy && vimEnabled.get) engine.noteExternalEdit(c.getPosition, c.getRemoved, c.getInserted)
    queueHighlight()
    checkGutter()
  })
  area.caretPositionProperty().addListener((_, _, _) => showPosition())
  area.currentParagraphProperty().addListener((_, before, now) => {
    redrawLineNumber(before.intValue)
    redrawLineNumber(now.intValue)
  })
  area.focusedProperty().addListener((_, _, _) => styleCaret())
  focusWithinProperty().addListener((_, _, _) => showStatus())

  private val vimListener: InvalidationListener = _ => {
    withEngine(engine.reset())
  }
  vimEnabled.addListener(new WeakInvalidationListener(vimListener))

  area.setContextMenu(contextMenu())
  setEditable(true)

  // ==================== what the host calls ====================

  def text: String = area.getText

  /** Replace the text, forgetting its undo history. */
  def setText(s: String): Unit = {
    val text = Option(s).getOrElse("")
    withEngine {
      area.replaceText(text)
      area.moveTo(0)
      area.getUndoManager.forgetHistory()
      engine.reset()
    }
    highlight()
    area.showParagraphAtTop(0)
  }

  def textProperty: ObservableValue[String] = area.textProperty

  def isEditable: Boolean = area.isEditable

  /** Make the text editable or read-only. Either way vim starts over in
    * normal mode. */
  def setEditable(editable: Boolean): Unit = {
    area.setEditable(editable)
    showStatus()
    if (editable) getStyleClass.remove("read-only")
    else if (!getStyleClass.contains("read-only")) getStyleClass.add("read-only")
    withEngine(engine.reset())
  }

  /** What `:w` does: None once written, or the message saying why not. */
  def setOnWrite(f: () => Option[String]): Unit = onWrite = f

  /** What `:q` does (`:q!` with `true`): None once closed, or the message. */
  def setOnQuit(f: Boolean => Option[String]): Unit = onQuit = f

  /** Put `s` in at the caret, as a paste would, and focus the editor. */
  def insertAtCaret(s: String): Unit = {
    if (vimEnabled.get) withEngine(engine.paste(s))
    else area.insertText(area.getCaretPosition, s)
    area.requestFocus()
  }

  def focusEditor(): Unit = area.requestFocus()

  def setPlaceholderText(s: String): Unit = {
    val l = new Label(s)
    l.getStyleClass.add("editor-placeholder")
    area.setPlaceholder(l)
  }

  // ==================== vim ====================

  private def withEngine[A](f: => A): A = {
    engineBusy = true
    try f
    finally {
      engineBusy = false
      refresh()
    }
  }

  private def vimPressed(e: KeyEvent): Unit = VimKeyEvents.pressed(e, IsMac) match {
    case Pressed.Key(k) =>
      val consumed = withEngine(engine.handleKey(k))
      if (consumed || area.isEditable) e.consume()
    case Pressed.Platform(s) =>
      e.consume()
      platformShortcut(s)
    case Pressed.Typed => e.consume()
    case Pressed.PassThrough => ()
  }

  private def vimTyped(e: KeyEvent): Unit = {
    val keys = VimKeyEvents.typed(e, IsMac)
    e.consume()
    if (keys.nonEmpty) withEngine(keys.foreach(engine.handleKey))
  }

  private def platformShortcut(s: Shortcut): Unit = s match {
    case Shortcut.Undo => withEngine(engine.undo())
    case Shortcut.Redo => withEngine(engine.redo())
    case Shortcut.Copy => withEngine(engine.copySelection()).foreach(putClipboard)
    case Shortcut.Cut => withEngine(engine.cutSelection()).foreach(putClipboard)
    case Shortcut.Paste => clipboardText.foreach(t => withEngine(engine.paste(t)))
    case Shortcut.SelectAll => withEngine(engine.selectAll())
  }

  /** After a click the cursor is where it landed; after a drag (or a
    * double-click's word) the text is selected in visual mode. */
  private def syncFromMouse(): Unit = withEngine {
    if (area.getSelection.getLength > 0) engine.adoptSelection(area.getAnchor, area.getCaretPosition)
    else engine.syncCaret()
  }

  /** Keys without vim: keep the indent on a new line, and indent by spaces. */
  private def plainPressed(e: KeyEvent): Unit = {
    if (!area.isEditable || e.isShortcutDown || e.isAltDown || e.isControlDown || e.isMetaDown) return
    val t = area.getText
    val pos = area.getCaretPosition
    e.getCode match {
      case KeyCode.ENTER if !e.isShiftDown =>
        val indent = VimText.indentOf(t, pos).take(pos - VimText.lineStart(t, pos))
        area.replaceSelection("\n" + indent)
        e.consume()
      case KeyCode.TAB if !e.isShiftDown =>
        val col = VimText.virtualColumn(t, pos)
        area.replaceSelection(" " * (VimEngine.ShiftWidth - col % VimEngine.ShiftWidth))
        e.consume()
      case KeyCode.TAB =>
        val ls = VimText.lineStart(t, pos)
        val drop = VimText.indentOf(t, pos).takeWhile(_ == ' ').length.min(VimEngine.ShiftWidth)
        if (drop > 0) area.replaceText(ls, ls + drop, "")
        e.consume()
      case _ => ()
    }
  }

  private def refresh(): Unit = {
    val vim = vimEnabled.get
    Seq(modeLabel, pendingLabel).foreach { l => l.setVisible(vim); l.setManaged(vim) }
    if (vim) {
      val (name, cls) = engine.mode match {
        case VimMode.Normal => ("NORMAL", "normal")
        case VimMode.Insert => ("INSERT", "insert")
        case VimMode.Replace => ("REPLACE", "replace")
        case VimMode.Visual => ("VISUAL", "visual")
        case VimMode.VisualLine => ("V-LINE", "visual")
        case VimMode.CommandLine => ("COMMAND", "command")
      }
      modeLabel.setText(name)
      modeLabel.getStyleClass.setAll("label", "vim-mode", cls)
      val msg = engine.commandLine
        .orElse(Some(engine.lastMessage).filter(_.nonEmpty))
        .orElse(engine.recording.map(r => s"recording @$r"))
        .getOrElse("")
      messageLabel.setText(msg)
      messageLabel.getStyleClass.setAll("label", "vim-message")
      if (engine.statusIsError) messageLabel.getStyleClass.add("error")
      pendingLabel.setText(engine.pendingKeys)
    } else messageLabel.setText("")
    showPosition()
    styleCaret()
  }

  /** The status line shows while the text is editable, and read-only text
    * shows it only while focused: a page of SQL cards stays quiet. */
  private def showStatus(): Unit = {
    val show = area.isEditable || isFocusWithin
    status.setVisible(show)
    status.setManaged(show)
  }

  private def showPosition(): Unit =
    positionLabel.setText(s"Ln ${area.getCurrentParagraph + 1}, Col ${area.getCaretColumn + 1}")

  /** A block over the character in vim's normal mode, the area's bar
    * otherwise. Read-only text shows the caret only under vim, which moves
    * it. */
  private def styleCaret(): Unit = {
    val caret = area.getCaretSelectionBind.getUnderlyingCaret
    val vim = vimEnabled.get
    val block = vim && (engine.mode == VimMode.Normal || engine.mode == VimMode.CommandLine)
    caret.setStyle(if (block) blockCaretStyle else "")
    caret.setShowCaret(
      if (area.isEditable) Caret.CaretVisibility.AUTO
      else if (vim && area.isFocused) Caret.CaretVisibility.ON
      else Caret.CaretVisibility.OFF
    )
  }

  // ==================== highlighting and line numbers ====================

  /** Restyle once the current event is handled, however many edits it made. */
  private def queueHighlight(): Unit =
    if (!highlightQueued) {
      highlightQueued = true
      Platform.runLater(() => {
        highlightQueued = false
        highlight()
      })
    }

  private def highlight(): Unit = {
    val text = area.getText
    if (text.isEmpty) return
    val spans = new StyleSpansBuilder[JCollection[String]]()
    SqlHighlighter.tokenize(text).foreach { tk =>
      val classes = SqlHighlighter.styleClass(tk.kind) match {
        case Some(c) => Collections.singletonList(c)
        case None => Collections.emptyList[String]()
      }
      spans.add(classes, tk.text.length)
    }
    area.setStyleSpans(0, spans.create())
  }

  private def lineNumbers: IntFunction[javafx.scene.Node] = (index: Int) => {
    val l = new Label(String.valueOf(index + 1))
    l.getStyleClass.add("lineno")
    if (index == area.getCurrentParagraph) l.getStyleClass.add("current")
    l.setMinWidth(gutterDigits * digitWidth + 22)
    l
  }

  private def redrawLineNumber(index: Int): Unit =
    if (index >= 0 && index < area.getParagraphs.size) area.recreateParagraphGraphic(index)

  /** Widen the gutter when the line count gains a digit. */
  private def checkGutter(): Unit = {
    val digits = math.max(2, area.getParagraphs.size.toString.length)
    if (digits != gutterDigits) {
      gutterDigits = digits
      try {
        var i = area.firstVisibleParToAllParIndex
        val last = area.lastVisibleParToAllParIndex
        while (i <= last) { area.recreateParagraphGraphic(i); i += 1 }
      } catch { case NonFatal(_) => () }
    }
  }

  private def contextMenu(): ContextMenu = {
    def item(label: String, plain: () => Unit, vim: Shortcut): MenuItem = {
      val m = new MenuItem(label)
      m.setOnAction(_ => if (vimEnabled.get) platformShortcut(vim) else plain())
      m
    }
    val cut = item("Cut", () => area.cut(), Shortcut.Cut)
    val copy = item("Copy", () => area.copy(), Shortcut.Copy)
    val paste = item("Paste", () => area.paste(), Shortcut.Paste)
    val all = item("Select All", () => area.selectAll(), Shortcut.SelectAll)
    val menu = new ContextMenu(cut, copy, paste, new SeparatorMenuItem(), all)
    menu.setOnShowing(_ => {
      cut.setDisable(!area.isEditable)
      paste.setDisable(!area.isEditable)
    })
    menu
  }
}

object SqlEditor {

  private val IsMac: Boolean = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
  private val VimPreference = "vimKeybindings"

  /** Whether every SQL editor reads keys as vim does. On unless turned off,
    * and remembered between runs (java.util.prefs). */
  lazy val vimEnabled: BooleanProperty = {
    val prefs = Preferences.userNodeForPackage(classOf[SqlEditor])
    val p = new SimpleBooleanProperty(prefs.getBoolean(VimPreference, true))
    p.addListener((_, _, on) => prefs.putBoolean(VimPreference, on))
    p
  }

  private lazy val digitWidth: Double = textWidth("0", Font.font(Theme.monoFamily, 12))

  /** The height of a line of code, in pixels. */
  lazy val lineHeight: Double = {
    val t = new Text("Mg")
    t.setFont(Theme.codeFont)
    t.getLayoutBounds.getHeight + 1
  }

  private lazy val blockCaretStyle: String = {
    val w = textWidth("M", Theme.codeFont)
    // CSS reads a decimal point, which a Turkish or German locale prints as a comma.
    "-fx-stroke-width: %.2fpx; -fx-translate-x: %.2fpx; -fx-stroke-line-cap: butt; -fx-stroke: rgba(139, 196, 255, 0.55);"
      .formatLocal(Locale.ROOT, w, w / 2)
  }

  private def textWidth(s: String, font: Font): Double = {
    val t = new Text(s)
    t.setFont(font)
    t.getLayoutBounds.getWidth
  }

  private def clipboardText: Option[String] =
    Option(Clipboard.getSystemClipboard.getString).map(_.replace("\r\n", "\n"))

  private def putClipboard(s: String): Unit = {
    val content = new ClipboardContent()
    content.putString(s)
    Clipboard.getSystemClipboard.setContent(content)
  }
}
