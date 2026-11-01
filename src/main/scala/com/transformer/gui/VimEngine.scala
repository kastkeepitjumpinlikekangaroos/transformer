package com.transformer.gui

import com.transformer.gui.VimCommand._
import com.transformer.gui.VimCommand.Action._
import com.transformer.gui.VimKey._
import com.transformer.gui.VimText._

import scala.collection.mutable

/** What the vim engine edits: a text, its caret, and the few things around
  * them a command reaches. The GUI's SQL editor implements it over its text
  * area; the tests implement it over a string. */
trait VimBuffer {
  def text: String
  /** The caret as the host last showed it. A caret the engine did not put
    * there (a mouse click) is where the cursor goes. */
  def caret: Int
  def editable: Boolean
  def replace(start: Int, end: Int, replacement: String): Unit
  /** Show the caret at `caret` with the text between it and `anchor`
    * selected, or nothing selected when the two are equal. */
  def select(anchor: Int, caret: Int): Unit
  /** The first and the last line on screen, from 0, inclusive. */
  def visibleLines: (Int, Int)
  def scrollTo(line: Int, where: VimScroll): Unit
  def clipboard: Option[String]
  def setClipboard(text: String): Unit
  /** `:w`. None once written, or vim's message saying why not. */
  def write(): Option[String]
  /** `:q`, or `:q!` when `force`. None once closed, or vim's message. */
  def quit(force: Boolean): Option[String]
}

sealed trait VimScroll
object VimScroll {
  case object Top extends VimScroll
  case object Center extends VimScroll
  case object Bottom extends VimScroll
}

sealed trait VimMode
object VimMode {
  case object Normal extends VimMode
  case object Insert extends VimMode
  case object Replace extends VimMode
  case object Visual extends VimMode
  case object VisualLine extends VimMode
  case object CommandLine extends VimMode
}

/** A vim editing engine over a [[VimBuffer]]: modes, counts, operators with
  * motions and text objects, registers, undo, `.`, macros, search, and the ex
  * commands a SQL editor uses (`:w`, `:q`, `:s`, a line number). No JavaFX:
  * the GUI turns key events into [[VimKey]]s and shows what the engine puts in
  * the buffer, and the tests drive it with strings.
  *
  * The engine reads the buffer's text at the start of every key and keeps no
  * copy between keys, so an edit the host makes on its own (a paste from the
  * context menu) is simply there at the next key; [[noteExternalEdit]] lets it
  * be undone too. Undo is the engine's own: each normal-mode change, and each
  * insert from the command that began it to `<Esc>`, is one step.
  */
final class VimEngine(buffer: VimBuffer) {
  import VimEngine._

  private var _mode: VimMode = VimMode.Normal

  /** The text while a key is handled; read from the buffer at its start and
    * after every edit. */
  private var t: String = ""
  /** vim's cursor. Outside insert mode it sits on a character. */
  private var cursor: Int = 0
  /** The column `j` and `k` aim for; `Int.MaxValue` after `$`. */
  private var want: Int = 0
  /** The caret this engine last showed. */
  private var shownCaret: Int = -1

  private val pending = mutable.ArrayBuffer.empty[VimKey]
  private var anchor: Int = 0
  private var lastVisual: Option[(Int, Int, VimMode)] = None
  /** The lines `'<` and `'>` name: the last visual selection's. */
  private var visualMarks: Option[(Int, Int)] = None

  private val registers = mutable.Map.empty[Char, Register]
  private var lastFind: Option[(Char, Char)] = None
  private var lastSearch: Option[(String, Boolean)] = None
  private var lastSubstitute: Option[Substitution] = None
  private var lastEx: Option[String] = None
  private var lastChange: Option[Repeat] = None
  private var lastInserted: String = ""
  private var repeating: Boolean = false

  private var recordInto: Option[Char] = None
  private val recorded = mutable.ArrayBuffer.empty[VimKey]
  private val macros = mutable.Map.empty[Char, Vector[VimKey]]
  private var lastMacro: Option[Char] = None
  private var replayDepth: Int = 0
  /** The last command failed: a running macro stops. */
  private var failed: Boolean = false

  private var insert: Option[InsertSession] = None
  private var lastInsertAt: Option[Int] = None
  /** The start of a line holding only the indent an `<CR>` or `o` gave it;
    * leaving it that way removes the indent, as vim's autoindent does. */
  private var autoIndentAt: Option[Int] = None

  private var cmdKind: Char = ':'
  private val cmdText = new StringBuilder
  private var cmdSearch: Option[Cmd] = None
  private var cmdReturn: VimMode = VimMode.Normal

  private val undoStack = mutable.ArrayBuffer.empty[UndoEntry]
  private val redoStack = mutable.ArrayBuffer.empty[UndoEntry]
  /** The text when the change in progress began, and the cursor at its first
    * edit, where undoing it puts the cursor back (after `A`, the line's end). */
  private var changeStart: Option[String] = None
  private var changeCaret: Int = -1

  private var message: String = ""
  private var messageIsError: Boolean = false

  // ==================== what the host reads ====================

  def mode: VimMode = _mode

  def isVisual: Boolean = _mode == VimMode.Visual || _mode == VimMode.VisualLine

  /** Keys of a command still being typed (vim's `showcmd`), or the size of
    * the visual selection. */
  def pendingKeys: String =
    if (pending.nonEmpty) VimKey.render(pending.toSeq)
    else if (isVisual) {
      val lines = math.abs(lineOf(t, cursor) - lineOf(t, anchor)) + 1
      if (_mode == VimMode.VisualLine || lines > 1) lines.toString
      else (math.abs(cursor - anchor) + 1).toString
    } else ""

  /** True while an operator waits for its motion. */
  def isOperatorPending: Boolean =
    pending.exists {
      case Typed(c) => "dcy<>".indexOf(c) >= 0
      case _ => false
    }

  /** The mode line: the command line being typed, the last message, or the
    * mode, with `recording @x` while a macro records. */
  def statusText: String = {
    val rec = recordInto.map(r => s"recording @$r").getOrElse("")
    def withRec(s: String): String = if (rec.isEmpty) s else if (s.isEmpty) rec else s"$s $rec"
    _mode match {
      case VimMode.CommandLine => s"$cmdKind$cmdText"
      case _ if message.nonEmpty => message
      case VimMode.Insert => withRec("-- INSERT --")
      case VimMode.Replace => withRec("-- REPLACE --")
      case VimMode.Visual => withRec("-- VISUAL --")
      case VimMode.VisualLine => withRec("-- VISUAL LINE --")
      case VimMode.Normal => rec
    }
  }

  def statusIsError: Boolean = message.nonEmpty && messageIsError && _mode != VimMode.CommandLine

  /** The command line being typed, its `:`, `/` or `?` first. */
  def commandLine: Option[String] = if (_mode == VimMode.CommandLine) Some(s"$cmdKind$cmdText") else None

  /** The last message (an error, or what a command reports), or empty. */
  def lastMessage: String = message

  /** The visual selection, start and exclusive end. */
  def selection: Option[(Int, Int)] = _mode match {
    case VimMode.Visual =>
      Some((math.min(anchor, cursor), math.min(t.length, math.max(anchor, cursor) + 1)))
    case VimMode.VisualLine =>
      Some((lineStart(t, math.min(anchor, cursor)), lineEnd(t, math.max(anchor, cursor))))
    case _ => None
  }

  def recording: Option[Char] = recordInto

  // ==================== what the host calls ====================

  /** Handle one key. False when the key means nothing here and the host may
    * use it: `<Esc>` in normal mode with nothing to cancel. */
  def handleKey(key: VimKey): Boolean = {
    if (replayDepth == 0 && recordInto.isDefined) recorded += key
    begin()
    val consumed = _mode match {
      case VimMode.Insert | VimMode.Replace => insertKey(key)
      case VimMode.CommandLine => cmdlineKey(key)
      case _ => normalKey(key)
    }
    show()
    consumed
  }

  /** Forget the history of the text: the host loaded another one. */
  def reset(): Unit = {
    t = buffer.text
    pending.clear()
    insert = None
    changeStart = None
    undoStack.clear()
    redoStack.clear()
    lastVisual = None
    visualMarks = None
    autoIndentAt = None
    message = ""
    _mode = VimMode.Normal
    cursor = onChar(t, math.min(buffer.caret, t.length))
    want = cursorColumn(t, cursor)
    show()
  }

  /** Undo, as `u` does, from any mode (the host's shortcut). */
  def undo(): Unit = {
    begin()
    if (insert.isDefined) { endChange(); undoSteps(1); beginChange() } else undoSteps(1)
    show()
  }

  /** Redo, as `<C-r>` does, from any mode. */
  def redo(): Unit = {
    begin()
    if (insert.isEmpty) redoSteps(1)
    show()
  }

  /** The visual selection's text, for the host's copy shortcut; ends visual
    * mode. None outside it. */
  def copySelection(): Option[String] = {
    begin()
    if (!isVisual) return None
    val r = visualRange(forceLines = false)
    val (text, _) = textOf(r)
    leaveVisual()
    cursor = rangeStart(r)
    show()
    Some(text)
  }

  /** The visual selection's text, deleted, for the host's cut shortcut. */
  def cutSelection(): Option[String] = {
    begin()
    if (!isVisual || !buffer.editable) return None
    val r = visualRange(forceLines = false)
    val (text, _) = textOf(r)
    leaveVisual()
    beginChange()
    delete(r, None)
    endChange()
    show()
    Some(text)
  }

  /** Put `text` in as the host's paste shortcut does: typed in insert mode,
    * over the selection in visual mode, before the cursor otherwise. */
  def paste(text: String): Unit = {
    begin()
    if (text.nonEmpty && buffer.editable) _mode match {
      case VimMode.Insert | VimMode.Replace =>
        insertRaw(text)
        insert.foreach(_.keys ++= text.map(c => if (c == '\n') Enter else Typed(c)))
      case VimMode.CommandLine =>
        cmdText.append(text.takeWhile(_ != '\n'))
      case _ =>
        beginChange()
        if (isVisual) {
          val r = visualRange(forceLines = false)
          leaveVisual()
          val (s, e) = bounds(r)
          edit(s, e, text)
          cursor = s + text.length - 1
        } else {
          edit(cursor, cursor, text)
          cursor = cursor + text.length
        }
        endChange()
    }
    show()
  }

  /** Select the whole text in visual mode. */
  def selectAll(): Unit = {
    begin()
    if (t.nonEmpty) {
      if (insert.isDefined) leaveInsert()
      anchor = 0
      cursor = t.length - 1
      setMode(VimMode.Visual)
    }
    show()
  }

  /** The host changed the text itself (a paste from its menu, a drop). It is
    * one undo step, or part of the insert in progress. */
  def noteExternalEdit(start: Int, removed: String, inserted: String): Unit = {
    if (changeStart.isEmpty) {
      undoStack += UndoEntry(start, removed, inserted, start)
      trimUndo()
      redoStack.clear()
    }
  }

  /** The host selected text itself (a mouse drag): select it in visual mode.
    * `caret` is the end the caret is at, as the host's selection has it. */
  def adoptSelection(selAnchor: Int, selCaret: Int): Unit = {
    begin()
    if (selAnchor != selCaret && _mode != VimMode.CommandLine) {
      if (insert.isDefined) leaveInsert()
      if (selCaret > selAnchor) { anchor = selAnchor; cursor = selCaret - 1 }
      else { anchor = selAnchor - 1; cursor = selCaret }
      setMode(VimMode.Visual)
    }
    show()
  }

  /** Take the host's caret as the cursor (after a mouse click), on a
    * character outside insert mode. */
  def syncCaret(): Unit = {
    begin()
    show()
  }

  // ==================== key dispatch ====================

  private def begin(): Unit = {
    t = buffer.text
    val host = math.min(math.max(buffer.caret, 0), t.length)
    if (host != shownCaret) {
      if (isVisual) leaveVisual()
      if (_mode == VimMode.CommandLine && (cmdReturn == VimMode.Visual || cmdReturn == VimMode.VisualLine)) cmdReturn = VimMode.Normal
      cursor = host
      want = cursorColumn(t, cursor)
    }
    cursor = math.min(cursor, t.length)
  }

  private def show(): Unit = {
    val (a, c) = _mode match {
      case VimMode.Insert | VimMode.Replace => (cursor, cursor)
      case VimMode.Visual | VimMode.VisualLine => visualDisplay()
      case VimMode.CommandLine if cmdReturn == VimMode.Visual || cmdReturn == VimMode.VisualLine => visualDisplay()
      case _ =>
        cursor = onChar(t, cursor)
        (cursor, cursor)
    }
    buffer.select(a, c)
    shownCaret = c
  }

  private def visualDisplay(): (Int, Int) = {
    val linewise = _mode == VimMode.VisualLine || (_mode == VimMode.CommandLine && cmdReturn == VimMode.VisualLine)
    val (s, e) =
      if (linewise) (lineStart(t, math.min(anchor, cursor)), lineEnd(t, math.max(anchor, cursor)))
      else (math.min(anchor, cursor), math.min(t.length, math.max(anchor, cursor) + 1))
    if (cursor < anchor) (e, s) else (s, e)
  }

  private def normalKey(key: VimKey): Boolean = {
    if (key == Esc || key == Ctrl('[') || key == Ctrl('c')) {
      val cancelled = pending.nonEmpty || isVisual
      pending.clear()
      if (isVisual) leaveVisual()
      return cancelled
    }
    if (pending.isEmpty && key == Typed('q') && recordInto.isDefined) {
      stopRecording()
      return true
    }
    pending += key
    VimParser.parse(pending.toIndexedSeq, isVisual) match {
      case VimParser.Incomplete => ()
      case VimParser.Invalid =>
        pending.clear()
        failed = true
      case VimParser.Complete(cmd) =>
        pending.clear()
        run(cmd)
    }
    true
  }

  // ==================== running a command ====================

  /** True for a command that changes the text (and so is undone, and
    * repeated by `.`). */
  private def changes(a: Action): Boolean = a match {
    case Operate(op, _) => op != Op.Yank
    case VisualOperate(op, _) => op != Op.Yank
    case InsertAt(_) | Put(_, _) | ReplaceChar(_) | Join(_) | ToggleCaseChar | ReplaceMode |
         Increment(_) | VisualPut(_) | VisualReplace(_) | VisualJoin(_) | RepeatSubstitute => true
    case _ => false
  }

  private def run(cmd: Cmd): Unit = {
    failed = false
    val changing = changes(cmd.action)
    if (changing && !buffer.editable) {
      if (isVisual) leaveVisual()
      setError("E21: Cannot make changes, 'modifiable' is off")
      failed = true
      return
    }
    if (changing) {
      clearMessage()
      beginChange()
    }
    val visualSpan = if (isVisual) Some(currentVisualSpan()) else None
    execute(cmd)
    if (changing && insert.isEmpty) {
      endChange()
      // An operator waiting on a search is recorded once the search runs.
      if (!repeating && !failed && _mode != VimMode.CommandLine) lastChange = Some(Repeat(cmd, Vector.empty, visualSpan))
    }
    if (changing && insert.isDefined && !repeating) insert.foreach(_.visual = visualSpan)
  }

  private def execute(cmd: Cmd): Unit = cmd.action match {
    case Move(Motion.SearchPrompt(forward)) =>
      openCmdline(if (forward) '/' else '?', Some(cmd))
    case Move(m) =>
      evalMotion(m, cmd.count, forOperator = false) match {
        case Some(l) => moveTo(l)
        case None => failed = true
      }
    case Operate(op, Target.ByMotion(Motion.SearchPrompt(forward))) =>
      openCmdline(if (forward) '/' else '?', Some(cmd))
    case Operate(op, target) =>
      operatorRange(op, target, cmd) match {
        case Some((r, start)) => applyOp(op, r, cmd, levels = 1, start)
        case None => failed = true
      }
    case InsertAt(how) => startInsert(cmd, how)
    case Put(before, after) => put(cmd, before, after)
    case ReplaceChar(k) => replaceChars(cmd.n, k)
    case Join(spaces) => if (!join(lineOf(t, cursor), math.max(2, cmd.n), spaces)) failed = true
    case ToggleCaseChar => toggleCaseChars(cmd.n)
    case ReplaceMode =>
      insert = Some(new InsertSession(cmd, cmd.n, None))
      setMode(VimMode.Replace)
    case Undo => undoSteps(cmd.n)
    case Redo => redoSteps(cmd.n)
    case RepeatChange => repeatLast(cmd.count)
    case StartVisual(linewise) =>
      anchor = cursor
      setMode(if (linewise) VimMode.VisualLine else VimMode.Visual)
    case Reselect =>
      lastVisual match {
        case Some((a, c, m)) =>
          anchor = math.min(a, math.max(0, t.length - 1))
          cursor = math.min(c, math.max(0, t.length - 1))
          setMode(m)
        case None => failed = true
      }
    case CmdLine(_) =>
      if (isVisual) {
        visualMarks = Some((lineOf(t, math.min(anchor, cursor)), lineOf(t, math.max(anchor, cursor))))
        leaveVisual()
        openCmdline(':', None, "'<,'>")
      } else openCmdline(':', None, cmd.count.map(n => if (n == 1) "." else s".,.+${n - 1}").getOrElse(""))
    case Scroll(where, toFirst) =>
      if (toFirst) cursor = firstNonBlank(t, cursor)
      buffer.scrollTo(lineOf(t, cursor), where)
    case Record(r) =>
      recordInto = Some(r)
      recorded.clear()
    case Play(r) => playMacro(r, cmd.n)
    case Increment(sign) => increment(sign.toLong * cmd.n)
    case WriteQuit => ex("x")
    case ForceQuit => ex("q!")
    case RepeatSubstitute =>
      lastSubstitute match {
        case Some(s) => substitute(lineOf(t, cursor), lineOf(t, cursor), s.copy(global = false))
        case None => setError("E35: No previous regular expression"); failed = true
      }
    case VisualOperate(op, linewise) =>
      val r = visualRange(forceLines = linewise)
      val start = visualStart(r)
      cursor = math.min(anchor, cursor)
      leaveVisual()
      applyOp(op, r, cmd, levels = cmd.n, start)
    case SelectObject(obj) => selectObject(obj, cmd.n)
    case VisualPut(keep) => visualPut(cmd, keep)
    case VisualReplace(k) =>
      val r = visualRange(forceLines = false)
      leaveVisual()
      val (s, e) = bounds(r)
      val c = k match { case Typed(ch) => ch; case _ => '\n' }
      edit(s, e, t.substring(s, e).map(ch => if (ch == '\n') ch else c))
      cursor = rangeStart(r)
    case VisualJoin(spaces) =>
      val first = lineOf(t, math.min(anchor, cursor))
      val last = lineOf(t, math.max(anchor, cursor))
      leaveVisual()
      if (!join(first, math.max(2, last - first + 1), spaces)) failed = true
    case VisualSwitch(linewise) =>
      val target = if (linewise) VimMode.VisualLine else VimMode.Visual
      if (_mode == target) leaveVisual() else setMode(target)
    case SwapEnds =>
      val a = anchor
      anchor = cursor
      cursor = a
  }

  // ==================== motions ====================

  private def moveTo(l: Landing): Unit = {
    cursor = onChar(t, l.pos)
    if (l.toEol) want = Int.MaxValue
    else if (!l.vertical) want = cursorColumn(t, cursor)
  }

  private def lastLine: Int = lineCount(t) - 1

  private def lineLanding(line: Int): Landing =
    Landing(firstNonBlank(t, lineStartOf(t, line)), Kind.Linewise)

  /** A line at the column `j` and `k` aim for: where `G`, `gg`, `N%`, `H`, `M`
    * and `L` land under nvim's default `nostartofline`. */
  private def columnLanding(line: Int): Landing =
    Landing(atColumn(t, lineStartOf(t, line), want), Kind.Linewise, vertical = true)

  private def evalMotion(m: Motion, count: Option[Int], forOperator: Boolean): Option[Landing] = {
    val n = count.getOrElse(1)
    val pos = cursor
    val line = lineOf(t, pos)
    m match {
      case Motion.Left =>
        val s = lineStart(t, pos)
        if (pos <= s) None else Some(Landing(math.max(s, pos - n), Kind.Exclusive))
      case Motion.Right =>
        val s = lineStart(t, pos)
        val e = lineEnd(t, pos)
        val last = if (forOperator) e else math.max(s, e - 1)
        if (pos >= last) None else Some(Landing(math.min(last, pos + n), Kind.Exclusive))
      case Motion.LeftWrap =>
        var p = pos
        var k = 0
        while (k < n && p > 0) {
          p -= 1
          if (t.charAt(p) == '\n' && !isEmptyLine(t, p) && p > 0) p -= 1
          k += 1
        }
        if (p == pos) None else Some(Landing(p, Kind.Exclusive))
      case Motion.RightWrap =>
        var p = pos
        var k = 0
        while (k < n && p < t.length) {
          val e = lineEnd(t, p)
          if (p + 1 < e) p += 1
          else if (e < t.length) p = e + 1
          else k = n
          k += 1
        }
        if (p == pos) None else Some(Landing(p, Kind.Exclusive))
      case Motion.Down | Motion.Up =>
        val down = m == Motion.Down
        if ((down && line >= lastLine) || (!down && line <= 0)) None
        else {
          val target = math.max(0, math.min(lastLine, if (down) line + n else line - n))
          Some(Landing(atColumn(t, lineStartOf(t, target), want), Kind.Linewise, vertical = true))
        }
      case Motion.LineStart => Some(Landing(lineStart(t, pos), Kind.Exclusive))
      case Motion.FirstNonBlank => Some(Landing(firstNonBlank(t, pos), Kind.Exclusive))
      case Motion.LineEnd =>
        if (line + n - 1 > lastLine) None
        else {
          val ls = lineStartOf(t, line + n - 1)
          Some(Landing(math.max(ls, lineEnd(t, ls) - 1), Kind.Inclusive, toEol = true))
        }
      case Motion.LastNonBlank =>
        if (line + n - 1 > lastLine) None
        else Some(Landing(lastNonBlank(t, lineStartOf(t, line + n - 1)), Kind.Inclusive))
      case Motion.NextLine => if (line + n > lastLine) None else Some(lineLanding(line + n))
      case Motion.PrevLine => if (line - n < 0) None else Some(lineLanding(line - n))
      case Motion.CurrentLine => if (line + n - 1 > lastLine) None else Some(lineLanding(line + n - 1))
      case Motion.FileStart => Some(columnLanding(math.min(lastLine, count.map(_ - 1).getOrElse(0))))
      case Motion.FileEnd => Some(columnLanding(math.min(lastLine, count.map(_ - 1).getOrElse(lastLine))))
      case Motion.Word(big) =>
        var p = pos
        var k = 0
        while (k < n && p < t.length) { p = wordForward(t, p, big); k += 1 }
        if (p == pos) None
        else if (forOperator && lineOf(t, p) > line && (lineStart(t, p) until p).forall(i => isBlank(t.charAt(i)))) {
          // An operator ends with the last word it moved over, not at the
          // start of the next line.
          Some(Landing(math.max(pos, lineStart(t, p) - 1), Kind.Exclusive))
        } else Some(Landing(p, Kind.Exclusive))
      case Motion.WordBack(big) =>
        var p = pos
        var k = 0
        while (k < n && p > 0) { p = wordBackward(t, p, big); k += 1 }
        if (p == pos) None else Some(Landing(p, Kind.Exclusive))
      case Motion.WordEnd(big) =>
        var p = pos
        var k = 0
        while (k < n) { p = wordEnd(t, p, big); k += 1 }
        if (p == pos) None else Some(Landing(p, Kind.Inclusive))
      case Motion.WordEndBack(big) =>
        var p = pos
        var k = 0
        while (k < n && p > 0) { p = wordEndBackward(t, p, big); k += 1 }
        if (p == pos) None else Some(Landing(p, Kind.Inclusive))
      case Motion.Find(kind, c) =>
        lastFind = Some((kind, c))
        find(kind, c, n, again = false)
      case Motion.RepeatFind => lastFind.flatMap { case (kind, c) => find(kind, c, n, again = true) }
      case Motion.RepeatFindBack =>
        lastFind.flatMap { case (kind, c) =>
          val back = kind match { case 'f' => 'F'; case 'F' => 'f'; case 't' => 'T'; case _ => 't' }
          find(back, c, n, again = true)
        }
      case Motion.MatchPair =>
        count match {
          case Some(pct) =>
            if (pct > 100) None
            else Some(columnLanding(math.max(0, (pct * lineCount(t) + 99) / 100 - 1)))
          case None => matchPair(t, pos).map(Landing(_, Kind.Inclusive))
        }
      case Motion.ParagraphForward =>
        val p = paragraphForward(t, pos, n)
        if (p == pos) None else Some(Landing(p, Kind.Exclusive))
      case Motion.ParagraphBack =>
        val p = paragraphBackward(t, pos, n)
        if (p == pos) None else Some(Landing(p, Kind.Exclusive))
      case Motion.ScreenTop | Motion.ScreenMiddle | Motion.ScreenBottom =>
        val (top0, bottom0) = buffer.visibleLines
        val top = math.max(0, math.min(lastLine, top0))
        val bottom = math.max(top, math.min(lastLine, bottom0))
        val target = m match {
          case Motion.ScreenTop => math.min(bottom, top + n - 1)
          case Motion.ScreenBottom => math.max(top, bottom - n + 1)
          case _ => (top + bottom) / 2
        }
        Some(columnLanding(target))
      case Motion.SearchNext | Motion.SearchPrev =>
        lastSearch match {
          case Some((pattern, forward)) => searchFrom(pattern, if (m == Motion.SearchNext) forward else !forward, n)
          case None => setError("E35: No previous regular expression"); None
        }
      case Motion.Star(forward) =>
        keywordAt(t, pos) match {
          case Some((s, e)) =>
            val pattern = "\\<" + t.substring(s, e) + "\\>"
            lastSearch = Some((pattern, forward))
            cursor = s
            val found = searchFrom(pattern, forward, n)
            if (found.isEmpty) cursor = pos
            found
          case None => setError("E348: No string under cursor"); None
        }
      case Motion.Search(pattern, forward) =>
        val p = if (pattern.nonEmpty) Some(pattern) else lastSearch.map(_._1)
        p match {
          case Some(pat) =>
            lastSearch = Some((pat, forward))
            searchFrom(pat, forward, n)
          case None => setError("E35: No previous regular expression"); None
        }
      case Motion.SearchPrompt(_) => None
      case Motion.Page(down, half) =>
        val (top, bottom) = buffer.visibleLines
        val screen = math.max(1, bottom - top + 1)
        val lines = if (half) count.getOrElse(math.max(1, screen / 2)) else n * math.max(1, screen - 2)
        if ((down && line >= lastLine) || (!down && line <= 0)) None
        else {
          val target = math.max(0, math.min(lastLine, if (down) line + lines else line - lines))
          Some(Landing(atColumn(t, lineStartOf(t, target), want), Kind.Linewise, vertical = true))
        }
    }
  }

  private def find(kind: Char, c: Char, n: Int, again: Boolean): Option[Landing] = {
    val pos = cursor
    kind match {
      case 'f' => findInLine(t, pos, c, forward = true, n).map(Landing(_, Kind.Inclusive))
      case 'F' => findInLine(t, pos, c, forward = false, n).map(Landing(_, Kind.Exclusive))
      case 't' =>
        val from = if (again && pos + 1 < t.length && t.charAt(pos + 1) == c) pos + 1 else pos
        findInLine(t, from, c, forward = true, n).map(p => Landing(p - 1, Kind.Inclusive))
      case _ =>
        val from = if (again && pos > 0 && t.charAt(pos - 1) == c) pos - 1 else pos
        findInLine(t, from, c, forward = false, n).map(p => Landing(p + 1, Kind.Exclusive))
    }
  }

  private def searchFrom(pattern: String, forward: Boolean, n: Int): Option[Landing] = {
    val compiled = compilePattern(pattern)
    var p = cursor
    var wrapped = false
    var k = 0
    while (k < n) {
      search(t, compiled, p, forward) match {
        case Some((q, w)) =>
          p = q
          wrapped = wrapped || w
        case None =>
          setError(s"E486: Pattern not found: $pattern")
          return None
      }
      k += 1
    }
    if (wrapped) setMessage(if (forward) "search hit BOTTOM, continuing at TOP" else "search hit TOP, continuing at BOTTOM")
    else setMessage((if (forward) "/" else "?") + pattern)
    Some(Landing(p, Kind.Exclusive))
  }

  // ==================== ranges ====================

  private def rangeStart(r: Range): Int = r match {
    case Chars(s, _) => s
    case Lines(f, _) => lineStartOf(t, f)
  }

  /** The characters a range covers, without the line break after its last
    * line. */
  private def bounds(r: Range): (Int, Int) = r match {
    case Chars(s, e) => (s, e)
    case Lines(f, l) => (lineStartOf(t, f), lineEnd(t, lineStartOf(t, l)))
  }

  /** What a register holds for the range, and whether it is lines. */
  private def textOf(r: Range): (String, Boolean) = r match {
    case Chars(s, e) => (t.substring(s, e), false)
    case Lines(_, _) =>
      val (s, e) = bounds(r)
      (t.substring(s, e) + "\n", true)
  }

  /** What deleting whole lines removes: through the line break after the last,
    * or the one before the first when the last is the text's last line. */
  private def lineSpan(f: Int, l: Int): (Int, Int) = {
    val s = lineStartOf(t, f)
    val e = lineEnd(t, lineStartOf(t, l))
    if (e < t.length) (s, e + 1)
    else if (s > 0) (s - 1, e)
    else (0, t.length)
  }

  private def rangeFrom(pos: Int, l: Landing): Range = l.kind match {
    case Kind.Linewise =>
      Lines(math.min(lineOf(t, pos), lineOf(t, l.pos)), math.max(lineOf(t, pos), lineOf(t, l.pos)))
    case Kind.Inclusive =>
      val s = math.min(pos, l.pos)
      val e = math.max(pos, l.pos)
      Chars(s, if (e < t.length && t.charAt(e) != '\n') e + 1 else e)
    case Kind.Exclusive =>
      val s = math.min(pos, l.pos)
      val e = math.max(pos, l.pos)
      if (e > s && e == lineStart(t, e) && lineOf(t, e) > lineOf(t, s)) {
        // An exclusive motion that ends at the start of a line stops at the
        // end of the line before; from the indent of its first line, it takes
        // the lines whole.
        if ((lineStart(t, s) until s).forall(i => isBlank(t.charAt(i)))) Lines(lineOf(t, s), lineOf(t, e) - 1)
        else Chars(s, e - 1)
      } else Chars(s, e)
  }

  /** The range an operator covers, and where a yank or a case change
    * leaves the cursor: the start of what the motion or object covers, as
    * nvim does. A doubled yank leaves it where it is; a doubled case change
    * puts it on the first non-blank. */
  private def operatorRange(op: Op, target: Target, cmd: Cmd): Option[(Range, Int)] = target match {
    case Target.ByLines =>
      val first = lineOf(t, cursor)
      val last = math.min(lastLine, first + cmd.n - 1)
      val start = if (op == Op.Yank) cursor else math.min(cursor, firstNonBlank(t, lineStartOf(t, last)))
      Some((Lines(first, last), start))
    case Target.ByObject(obj) =>
      objectSpan(obj, cmd.n).map { span =>
        val r = if (span.linewise) Lines(lineOf(t, span.start), lineOf(t, span.end)) else Chars(span.start, span.end)
        (r, span.start)
      }
    case Target.ByMotion(Motion.Word(big)) if op == Op.Change && cursor < t.length && charClass(t.charAt(cursor), big) != 0 =>
      // `cw` on a word changes to the word's end, like `ce`, but from a
      // word's last character only that character.
      var p = cursor
      var k = 0
      while (k < cmd.n) {
        val atEnd = p + 1 >= t.length || charClass(t.charAt(p + 1), big) != charClass(t.charAt(p), big)
        if (!(k == 0 && atEnd)) p = wordEnd(t, p, big)
        k += 1
      }
      Some((rangeFrom(cursor, Landing(p, Kind.Inclusive)), cursor))
    case Target.ByMotion(m) =>
      evalMotion(m, cmd.count, forOperator = true).map(l => (rangeFrom(cursor, l), math.min(cursor, l.pos)))
  }

  private def objectSpan(obj: Obj, n: Int): Option[Span] = obj.kind match {
    case 'w' => wordObject(t, cursor, big = false, obj.inner, n)
    case 'W' => wordObject(t, cursor, big = true, obj.inner, n)
    case 'p' => Some(paragraphObject(t, cursor, obj.inner, n))
    case '(' | ')' | 'b' => bracketObject(t, cursor, '(', ')', obj.inner, n)
    case '[' | ']' => bracketObject(t, cursor, '[', ']', obj.inner, n)
    case '{' | '}' | 'B' => bracketObject(t, cursor, '{', '}', obj.inner, n)
    case '<' | '>' => bracketObject(t, cursor, '<', '>', obj.inner, n)
    case q => quoteObject(t, cursor, q, obj.inner)
  }

  // ==================== operators ====================

  /** Apply an operator over `r`. `start` is where a yank or a case change
    * leaves the cursor; a delete of lines and a shift leave it at the column
    * `j` and `k` aim for, on the first line. */
  private def applyOp(op: Op, r: Range, cmd: Cmd, levels: Int, start: Int): Unit = op match {
    case Op.Yank =>
      yank(r, cmd.register)
      cursor = start
    case Op.Delete => delete(r, cmd.register)
    case Op.Change => change(r, cmd)
    case Op.ShiftRight | Op.ShiftLeft =>
      val (f, l) = r match {
        case Chars(s, e) => (lineOf(t, s), lineOf(t, math.max(s, e - 1)))
        case Lines(f0, l0) => (f0, l0)
      }
      shiftLines(f, l, levels, right = op == Op.ShiftRight)
      cursor = atColumn(t, lineStartOf(t, f), want)
    case Op.ToggleCase | Op.Lower | Op.Upper =>
      val (s, e) = bounds(r)
      val text = t.substring(s, e)
      val out = op match {
        case Op.Lower => text.map(Character.toLowerCase)
        case Op.Upper => text.map(Character.toUpperCase)
        case _ => text.map(c => if (c.isUpper) c.toLower else if (c.isLower) c.toUpper else c)
      }
      edit(s, e, out)
      cursor = start
  }

  /** Where a visual yank or case change leaves the cursor: the selection's
    * start; with whole lines, the cursor when it is the upper end and the
    * first line's start when it is not, as nvim does. */
  private def visualStart(r: Range): Int = r match {
    case Chars(s, _) => s
    case Lines(f, _) => if (cursor < anchor) cursor else lineStartOf(t, f)
  }

  private def yank(r: Range, register: Option[Char]): Unit = {
    val (text, linewise) = textOf(r)
    storeYank(register, text, linewise)
    r match {
      case Lines(f, l) if l - f + 1 > 2 => setMessage(s"${l - f + 1} lines yanked")
      case _ => ()
    }
  }

  private def delete(r: Range, register: Option[Char]): Unit = {
    val (text, linewise) = textOf(r)
    storeDelete(register, text, linewise)
    r match {
      case Chars(s, e) =>
        edit(s, e, "")
        cursor = s
      case Lines(f, l) =>
        val (s, e) = lineSpan(f, l)
        edit(s, e, "")
        cursor = atColumn(t, lineStartOf(t, math.min(f, lastLine)), want)
        if (l - f + 1 > 2) setMessage(s"${l - f + 1} fewer lines")
    }
  }

  private def change(r: Range, cmd: Cmd): Unit = {
    val (text, linewise) = textOf(r)
    storeDelete(cmd.register, text, linewise)
    r match {
      case Chars(s, e) =>
        edit(s, e, "")
        cursor = s
      case Lines(f, l) =>
        val ls = lineStartOf(t, f)
        val indent = indentOf(t, ls)
        edit(ls, lineEnd(t, lineStartOf(t, l)), indent)
        cursor = ls + indent.length
        if (indent.nonEmpty) autoIndentAt = Some(ls)
    }
    insert = Some(new InsertSession(cmd, 1, None))
    setMode(VimMode.Insert)
  }

  private def shiftLines(first: Int, last: Int, levels: Int, right: Boolean): Unit = {
    val s = lineStartOf(t, first)
    val e = lineEnd(t, lineStartOf(t, last))
    val width = ShiftWidth * levels
    val shifted = t.substring(s, e).split("\n", -1).map { line =>
      if (line.isEmpty) line
      else {
        var cols = 0
        var i = 0
        while (i < line.length && isBlank(line.charAt(i))) {
          cols = if (line.charAt(i) == '\t') (cols / TabStop + 1) * TabStop else cols + 1
          i += 1
        }
        " " * math.max(0, if (right) cols + width else cols - width) + line.substring(i)
      }
    }
    edit(s, e, shifted.mkString("\n"))
    cursor = firstNonBlank(t, lineStartOf(t, first))
    val n = last - first + 1
    if (n > 2) setMessage(s"$n lines ${if (right) ">" else "<"}ed $levels time${if (levels == 1) "" else "s"}")
  }

  // ==================== registers ====================

  private def storeYank(register: Option[Char], text: String, linewise: Boolean): Unit = register match {
    case Some('_') => ()
    case Some(r) => writeRegister(r, text, linewise)
    case None =>
      registers('0') = Register(text, linewise)
      registers('"') = Register(text, linewise)
  }

  private def storeDelete(register: Option[Char], text: String, linewise: Boolean): Unit = register match {
    case Some('_') => ()
    case Some(r) => writeRegister(r, text, linewise)
    case None =>
      val reg = Register(text, linewise)
      if (linewise || text.contains('\n')) {
        var d = 9
        while (d > 1) {
          registers.get(('0' + d - 1).toChar).foreach(prev => registers(('0' + d).toChar) = prev)
          d -= 1
        }
        registers('1') = reg
      } else registers('-') = reg
      registers('"') = reg
  }

  private def writeRegister(r: Char, text: String, linewise: Boolean): Unit = r match {
    case '+' | '*' =>
      buffer.setClipboard(text)
      registers('"') = Register(text, linewise)
    case c if c.isUpper =>
      val lower = c.toLower
      val joined = registers.get(lower) match {
        case Some(prev) if prev.linewise || linewise =>
          Register((if (prev.linewise) prev.text else prev.text + "\n") + text + (if (linewise) "" else "\n"), linewise = true)
        case Some(prev) => Register(prev.text + text, linewise = false)
        case None => Register(text, linewise)
      }
      registers(lower) = joined
      registers('"') = joined
    case '"' => registers('"') = Register(text, linewise)
    case c =>
      registers(c) = Register(text, linewise)
      registers('"') = Register(text, linewise)
  }

  private def readRegister(register: Option[Char]): Option[Register] = register.getOrElse('"') match {
    case '+' | '*' => buffer.clipboard.filter(_.nonEmpty).map(s => Register(s, s.endsWith("\n")))
    case '.' => Some(Register(lastInserted, linewise = false)).filter(_.text.nonEmpty)
    case '/' => lastSearch.map(s => Register(s._1, linewise = false))
    case ':' => lastEx.map(Register(_, linewise = false))
    case '_' => None
    case c => registers.get(c.toLower)
  }

  // ==================== simple changes ====================

  private def put(cmd: Cmd, before: Boolean, cursorAfter: Boolean): Unit = {
    val reg = readRegister(cmd.register).getOrElse {
      setError(s"E353: Nothing in register ${cmd.register.getOrElse('"')}")
      failed = true
      return
    }
    if (reg.linewise) {
      val content = Seq.fill(cmd.n)(reg.text.stripSuffix("\n")).mkString("\n")
      if (before) {
        val s = lineStart(t, cursor)
        edit(s, s, content + "\n")
        cursor = if (cursorAfter) s + content.length + 1 else firstNonBlank(t, s)
      } else {
        val e = lineEnd(t, cursor)
        edit(e, e, "\n" + content)
        cursor = if (cursorAfter) math.min(t.length, e + content.length + 2) else firstNonBlank(t, e + 1)
      }
    } else {
      val content = reg.text * cmd.n
      val at = if (before || isEmptyLine(t, cursor)) cursor else math.min(cursor + 1, lineEnd(t, cursor))
      edit(at, at, content)
      cursor =
        if (cursorAfter) at + content.length
        else if (content.contains('\n')) at
        else at + content.length - 1
    }
  }

  private def replaceChars(n: Int, k: VimKey): Unit = {
    val e = lineEnd(t, cursor)
    if (cursor + n > e) { failed = true; return }
    k match {
      case Typed(c) =>
        edit(cursor, cursor + n, c.toString * n)
        cursor = cursor + n - 1
      case _ =>
        edit(cursor, cursor + n, "\n")
        cursor = cursor + 1
    }
  }

  private def toggleCaseChars(n: Int): Unit = {
    val e = lineEnd(t, cursor)
    if (cursor >= e) { failed = true; return }
    val end = math.min(cursor + n, e)
    edit(cursor, end, t.substring(cursor, end).map(c => if (c.isUpper) c.toLower else if (c.isLower) c.toUpper else c))
    cursor = end
  }

  /** Join `n` lines from `first`; vim's `J` puts one space between them and
    * drops the next line's indent, `gJ` neither. */
  private def join(first: Int, n: Int, spaces: Boolean): Boolean = {
    if (first >= lastLine) return false
    val last = math.min(lastLine, first + n - 1)
    var k = first
    var at = cursor
    while (k < last) {
      val e = lineEnd(t, lineStartOf(t, first))
      if (spaces) {
        val nextStart = e + 1
        val text = firstNonBlank(t, nextStart)
        val nextEmpty = text >= lineEnd(t, nextStart)
        val endsBlank = e > lineStart(t, e) && isBlank(t.charAt(e - 1))
        val emptyHere = e == lineStart(t, e)
        val space = !nextEmpty && !endsBlank && !emptyHere && t.charAt(text) != ')'
        edit(e, text, if (space) " " else "")
        at = e
      } else {
        edit(e, e + 1, "")
        at = e
      }
      k += 1
    }
    cursor = at
    true
  }

  private def increment(delta: Long): Unit = {
    val ls = lineStart(t, cursor)
    val le = lineEnd(t, cursor)
    var p = cursor
    if (p < le && t.charAt(p).isDigit) { while (p > ls && t.charAt(p - 1).isDigit) p -= 1 }
    else { while (p < le && !t.charAt(p).isDigit) p += 1 }
    if (p >= le) { failed = true; return }
    var e = p
    while (e < le && t.charAt(e).isDigit) e += 1
    val negative = p > ls && t.charAt(p - 1) == '-' && !(p - 1 > ls && isKeywordChar(t.charAt(p - 2)))
    val s = if (negative) p - 1 else p
    val rendered = (BigInt(t.substring(s, e)) + delta).toString
    edit(s, e, rendered)
    cursor = s + rendered.length - 1
  }

  // ==================== visual mode ====================

  private def setMode(m: VimMode): Unit = {
    if (m != _mode) clearMessage()
    _mode = m
  }

  private def leaveVisual(): Unit = {
    if (isVisual) {
      lastVisual = Some((anchor, cursor, _mode))
      visualMarks = Some((lineOf(t, math.min(anchor, cursor)), lineOf(t, math.max(anchor, cursor))))
    }
    _mode = VimMode.Normal
  }

  private def visualRange(forceLines: Boolean): Range = {
    val s = math.min(anchor, cursor)
    val e = math.max(anchor, cursor)
    if (_mode == VimMode.VisualLine || forceLines) Lines(lineOf(t, s), lineOf(t, e))
    else Chars(s, math.min(t.length, e + 1))
  }

  private def currentVisualSpan(): VisualSpan = {
    val s = math.min(anchor, cursor)
    val e = math.max(anchor, cursor)
    VisualSpan(_mode == VimMode.VisualLine, lineOf(t, e) - lineOf(t, s) + 1, if (lineOf(t, e) == lineOf(t, s)) e - s + 1 else column(t, e))
  }

  private def selectObject(obj: Obj, n: Int): Unit = {
    objectSpan(obj, n) match {
      case Some(span) if span.linewise =>
        anchor = span.start
        cursor = span.end
        _mode = VimMode.VisualLine
      case Some(span) if span.end > span.start =>
        anchor = span.start
        cursor = span.end - 1
      case _ => failed = true
    }
  }

  private def visualPut(cmd: Cmd, keep: Boolean): Unit = {
    val reg = readRegister(cmd.register).getOrElse {
      leaveVisual()
      setError(s"E353: Nothing in register ${cmd.register.getOrElse('"')}")
      failed = true
      return
    }
    val r = visualRange(forceLines = false)
    val (old, oldLinewise) = textOf(r)
    leaveVisual()
    r match {
      case Chars(s, e) =>
        val content = if (reg.linewise) "\n" + reg.text.stripSuffix("\n") + "\n" else reg.text * cmd.n
        edit(s, e, content)
        cursor = if (reg.linewise) s + 1 else s + content.length - 1
      case Lines(_, _) =>
        val (s, e) = bounds(r)
        edit(s, e, if (reg.linewise) reg.text.stripSuffix("\n") else reg.text)
        cursor = firstNonBlank(t, s)
    }
    if (!keep) storeDelete(None, old, oldLinewise)
  }

  // ==================== insert mode ====================

  private def startInsert(cmd: Cmd, how: Char): Unit = {
    var open: Option[Boolean] = None
    how match {
      case 'i' => ()
      case 'a' => if (!isEmptyLine(t, cursor)) cursor = math.min(cursor + 1, lineEnd(t, cursor))
      case 'I' => cursor = firstNonBlank(t, cursor)
      case '0' => cursor = lineStart(t, cursor)
      case 'A' => cursor = lineEnd(t, cursor)
      case '`' => cursor = math.min(lastInsertAt.getOrElse(cursor), t.length)
      case 'o' => openLine(below = true); open = Some(true)
      case 'O' => openLine(below = false); open = Some(false)
      case _ => ()
    }
    insert = Some(new InsertSession(cmd, cmd.n, open))
    setMode(VimMode.Insert)
  }

  private def openLine(below: Boolean): Unit = {
    val indent = indentOf(t, cursor)
    if (below) {
      val e = lineEnd(t, cursor)
      edit(e, e, "\n" + indent)
      cursor = e + 1 + indent.length
      if (indent.nonEmpty) autoIndentAt = Some(e + 1)
    } else {
      val s = lineStart(t, cursor)
      edit(s, s, indent + "\n")
      cursor = s + indent.length
      if (indent.nonEmpty) autoIndentAt = Some(s)
    }
  }

  private def insertKey(key: VimKey): Boolean = {
    val session = insert.getOrElse { _mode = VimMode.Normal; return false }
    if (session.awaitingRegister) {
      session.awaitingRegister = false
      key match {
        case Typed(r) =>
          readRegister(Some(r)).foreach(reg => insertRaw(reg.text))
          session.keys += Ctrl('r') += key
        case _ => ()
      }
      return true
    }
    key match {
      case Esc | Ctrl('[') | Ctrl('c') => leaveInsert()
      case VimKey.Left | VimKey.Right | VimKey.Up | VimKey.Down | Home | VimKey.End | PageUp | PageDown =>
        moveInInsert(key)
        // Typing after a move is a change of its own, as in vim.
        endChange()
        beginChange()
        session.keys.clear()
        session.cmd = Cmd(None, None, InsertAt('i'))
        session.count = 1
        session.open = None
        session.visual = None
      case Ctrl('r') => session.awaitingRegister = true
      case _ => if (insertEffect(key, session)) session.keys += key
    }
    true
  }

  /** Apply one insert-mode key; false when it does nothing here. */
  private def insertEffect(key: VimKey, session: InsertSession): Boolean = key match {
    case Typed(c) => typeChar(c, session); true
    case Enter | Ctrl('j') | Ctrl('m') => newline(); true
    case Backspace | Ctrl('h') => backspace(session); true
    case Delete =>
      if (cursor < t.length) edit(cursor, cursor + 1, "")
      true
    case Tab =>
      val col = virtualColumn(t, cursor)
      insertRaw(" " * (ShiftWidth - col % ShiftWidth))
      true
    case Ctrl('w') => deleteWordBefore(); true
    case Ctrl('u') =>
      val first = firstNonBlank(t, cursor)
      val from = if (cursor > first) first else lineStart(t, cursor)
      if (from < cursor) edit(from, cursor, "")
      cursor = from
      true
    case Ctrl('t') | Ctrl('d') =>
      // Only the indent changes, so the text after the cursor stays after it.
      val line = lineOf(t, cursor)
      val inIndent = cursor <= firstNonBlank(t, cursor)
      val fromEnd = lineEnd(t, cursor) - cursor
      shiftLines(line, line, 1, right = key == Ctrl('t'))
      val ls = lineStartOf(t, line)
      cursor = if (inIndent) firstNonBlank(t, ls) else lineEnd(t, ls) - fromEnd
      true
    case _ => false
  }

  private def typeChar(c: Char, session: InsertSession): Unit = {
    autoIndentAt = None
    if (_mode == VimMode.Replace && cursor < lineEnd(t, cursor)) {
      session.replaced += Some(t.charAt(cursor))
      edit(cursor, cursor + 1, c.toString)
    } else {
      if (_mode == VimMode.Replace) session.replaced += None
      edit(cursor, cursor, c.toString)
    }
    cursor += 1
  }

  /** Text put in as it is, with no autoindent: a paste, a register. */
  private def insertRaw(s: String): Unit = {
    autoIndentAt = None
    edit(cursor, cursor, s)
    cursor += s.length
  }

  private def newline(): Unit = {
    val ls = lineStart(t, cursor)
    val indent = indentOf(t, cursor).take(cursor - ls)
    dropAutoIndent()
    edit(cursor, cursor, "\n" + indent)
    cursor += 1 + indent.length
    autoIndentAt = if (indent.nonEmpty) Some(cursor - indent.length) else None
  }

  private def dropAutoIndent(): Unit = {
    autoIndentAt.foreach { ls =>
      val e = lineEnd(t, ls)
      if (ls == lineStart(t, cursor) && cursor == e && firstNonBlank(t, ls) == e && e > ls) {
        edit(ls, e, "")
        cursor = ls
      }
    }
    autoIndentAt = None
  }

  private def backspace(session: InsertSession): Unit = {
    autoIndentAt = None
    if (_mode == VimMode.Replace) {
      if (session.replaced.nonEmpty) {
        val original = session.replaced.remove(session.replaced.length - 1)
        cursor -= 1
        edit(cursor, cursor + 1, original.map(_.toString).getOrElse(""))
      } else if (cursor > 0) cursor -= 1
    } else if (cursor > 0) {
      edit(cursor - 1, cursor, "")
      cursor -= 1
    }
  }

  private def deleteWordBefore(): Unit = {
    val ls = lineStart(t, cursor)
    if (cursor == ls) {
      if (cursor > 0) { edit(cursor - 1, cursor, ""); cursor -= 1 }
      return
    }
    var p = cursor
    while (p > ls && isBlank(t.charAt(p - 1))) p -= 1
    if (p > ls) {
      val c = charClass(t.charAt(p - 1), big = false)
      while (p > ls && !isBlank(t.charAt(p - 1)) && charClass(t.charAt(p - 1), big = false) == c) p -= 1
    }
    edit(p, cursor, "")
    cursor = p
  }

  private def moveInInsert(key: VimKey): Unit = {
    autoIndentAt = None
    key match {
      case VimKey.Left => if (cursor > lineStart(t, cursor)) cursor -= 1
      case VimKey.Right => if (cursor < lineEnd(t, cursor)) cursor += 1
      case VimKey.Up | VimKey.Down | PageUp | PageDown =>
        val line = lineOf(t, cursor)
        val (top, bottom) = buffer.visibleLines
        val step = if (key == PageUp || key == PageDown) math.max(1, bottom - top - 1) else 1
        val target = if (key == VimKey.Up || key == PageUp) line - step else line + step
        if (target >= 0 && target <= lastLine) {
          val col = column(t, cursor)
          val ls = lineStartOf(t, target)
          cursor = math.min(ls + col, lineEnd(t, ls))
        }
      case Home => cursor = lineStart(t, cursor)
      case _ => cursor = lineEnd(t, cursor)
    }
  }

  private def leaveInsert(): Unit = {
    val session = insert.getOrElse(return)
    dropAutoIndent()
    val typed = session.keys.toVector
    var k = 1
    while (k < session.count) {
      session.open.foreach(below => openLine(below))
      replayInsert(typed, session)
      dropAutoIndent()
      k += 1
    }
    lastInserted = typed.collect { case Typed(c) => c.toString; case Enter => "\n" }.mkString
    lastInsertAt = Some(cursor)
    insert = None
    _mode = VimMode.Normal
    clearMessage()
    if (cursor > lineStart(t, cursor)) cursor -= 1
    endChange()
    if (!repeating) lastChange = Some(Repeat(session.cmd, typed, session.visual))
  }

  /** Type recorded insert-mode keys again: a count's copies, and `.`. */
  private def replayInsert(keys: Seq[VimKey], session: InsertSession): Unit = {
    val it = keys.iterator
    while (it.hasNext) it.next() match {
      case Ctrl('r') =>
        if (it.hasNext) it.next() match {
          case Typed(r) => readRegister(Some(r)).foreach(reg => insertRaw(reg.text))
          case _ => ()
        }
      case key => insertEffect(key, session)
    }
  }

  // ==================== repeat and macros ====================

  private def repeatLast(count: Option[Int]): Unit = {
    val rep = lastChange.getOrElse { failed = true; return }
    if (!buffer.editable) {
      setError("E21: Cannot make changes, 'modifiable' is off")
      failed = true
      return
    }
    val cmd = if (count.isDefined) rep.cmd.copy(count = count) else rep.cmd
    repeating = true
    try {
      beginChange()
      rep.visual match {
        case Some(span) =>
          anchor = cursor
          if (span.linewise || span.lines > 1) {
            cursor = lineStartOf(t, math.min(lastLine, lineOf(t, cursor) + span.lines - 1))
            if (!span.linewise) cursor = math.min(cursor + span.lastColumn, math.max(cursor, lineEnd(t, cursor) - 1))
          } else cursor = math.min(cursor + span.lastColumn - 1, math.max(cursor, lineEnd(t, cursor) - 1))
          _mode = if (span.linewise) VimMode.VisualLine else VimMode.Visual
        case None => ()
      }
      execute(cmd)
      insert.foreach { session =>
        session.keys ++= rep.keys
        replayInsert(rep.keys, session)
        leaveInsert()
      }
      endChange()
    } finally repeating = false
  }

  private def stopRecording(): Unit = {
    recordInto.foreach { r =>
      val keys = recorded.dropRight(1).toVector
      val all = if (r.isUpper) macros.getOrElse(r.toLower, Vector.empty) ++ keys else keys
      macros(r.toLower) = all
      registers(r.toLower) = Register(VimKey.render(all), linewise = false)
    }
    recordInto = None
    recorded.clear()
  }

  private def playMacro(r: Char, n: Int): Unit = {
    val name = if (r == '@') lastMacro.getOrElse { failed = true; return } else r.toLower
    if (name == ':') {
      lastEx match {
        case Some(c) => var k = 0; while (k < n) { ex(c); k += 1 }
        case None => setError("E30: No previous command line"); failed = true
      }
      return
    }
    val keys = macros.get(name).orElse(
      registers.get(name).map(reg => reg.text.map(c => if (c == '\n') Enter else Typed(c)).toVector)
    ).getOrElse { failed = true; return }
    if (replayDepth >= MaxReplayDepth) { failed = true; return }
    lastMacro = Some(name)
    replayDepth += 1
    try {
      var k = 0
      while (k < n && !failed) {
        val it = keys.iterator
        while (it.hasNext && !failed) handleKey(it.next())
        k += 1
      }
    } finally replayDepth -= 1
  }

  // ==================== command line ====================

  private def openCmdline(kind: Char, search: Option[Cmd], initial: String = ""): Unit = {
    cmdReturn = if (kind == ':') VimMode.Normal else _mode
    cmdKind = kind
    cmdText.clear()
    cmdText.append(initial)
    cmdSearch = search
    clearMessage()
    _mode = VimMode.CommandLine
  }

  private def closeCmdline(): Unit = {
    _mode = cmdReturn
    cmdSearch = None
  }

  private def cmdlineKey(key: VimKey): Boolean = {
    key match {
      case Esc | Ctrl('c') | Ctrl('[') => closeCmdline()
      case Enter | Ctrl('m') | Ctrl('j') =>
        val text = cmdText.toString
        val kind = cmdKind
        val search = cmdSearch
        closeCmdline()
        if (kind == ':') { lastEx = Some(text); ex(text) }
        else {
          val forward = kind == '/'
          val pattern = if (text.isEmpty) lastSearch.map(_._1).getOrElse("") else text
          if (pattern.isEmpty) setError("E35: No previous regular expression")
          else {
            val base = search.getOrElse(Cmd(None, None, Move(Motion.SearchPrompt(forward))))
            val action = base.action match {
              case Operate(op, _) => Operate(op, Target.ByMotion(Motion.Search(pattern, forward)))
              case _ => Move(Motion.Search(pattern, forward))
            }
            run(base.copy(action = action))
          }
        }
      case Backspace | Ctrl('h') =>
        if (cmdText.isEmpty) closeCmdline() else cmdText.deleteCharAt(cmdText.length - 1)
      case Ctrl('u') => cmdText.clear()
      case Ctrl('w') =>
        var p = cmdText.length
        while (p > 0 && cmdText.charAt(p - 1) == ' ') p -= 1
        while (p > 0 && cmdText.charAt(p - 1) != ' ') p -= 1
        cmdText.delete(p, cmdText.length)
      case Typed(c) => cmdText.append(c)
      case _ => ()
    }
    true
  }

  /** Run an ex command line: a range and a command, or a range alone (go to
    * its last line). */
  private def ex(input: String): Unit = {
    val line = input.trim.stripPrefix(":")
    if (line.isEmpty) return
    val (range, rest) = parseRange(line) match {
      case Some(r) => r
      case None => setError(s"E16: Invalid range: $line"); failed = true; return
    }
    val name = if (rest.nonEmpty && (rest.charAt(0) == '>' || rest.charAt(0) == '<' || rest.charAt(0) == '&')) rest.take(1)
               else rest.takeWhile(_.isLetter)
    val after = rest.drop(name.length)
    val bang = after.startsWith("!")
    val args = (if (bang) after.drop(1) else after).trim
    val here = lineOf(t, cursor)
    val (first, last) = range.getOrElse((here, here))
    def lines(): Boolean = {
      if (first < 0 || last > lastLine || first > last) { setError("E16: Invalid range"); failed = true; false } else true
    }
    def writeThen(quit: Boolean): Unit = buffer.write() match {
      case Some(err) => setError(err); failed = true
      case None =>
        if (quit) buffer.quit(force = true).foreach(err => setError(err))
        else setMessage("written")
    }
    name match {
      case "" =>
        range.foreach { case (_, l) => cursor = atColumn(t, lineStartOf(t, math.max(0, math.min(lastLine, l))), want) }
      case "w" | "write" => writeThen(quit = false)
      case "wq" | "x" | "xit" | "exit" => writeThen(quit = true)
      case "q" | "quit" | "qa" | "qall" | "quita" | "quitall" =>
        buffer.quit(bang).foreach { err => setError(err); failed = true }
      case "s" | "substitute" | "&" =>
        if (!buffer.editable) { setError("E21: Cannot make changes, 'modifiable' is off"); failed = true }
        else if (lines()) {
          val parsed = if (args.isEmpty || name == "&") lastSubstitute.map(_.copy(global = args.contains('g')))
                       else parseSubstitute(args)
          parsed match {
            case Some(s) =>
              beginChange()
              substitute(first, last, s)
              endChange()
            case None => setError("E35: No previous regular expression"); failed = true
          }
        }
      case "d" | "delete" | "y" | "yank" | "j" | "join" | ">" | "<" =>
        if (lines()) {
          val register = args.headOption.filter(c => VimParser.RegisterNames.indexOf(c) >= 0)
          if ((name.startsWith("y")) || buffer.editable) {
            beginChange()
            name match {
              case "y" | "yank" => yank(Lines(first, last), register)
              case "d" | "delete" =>
                delete(Lines(first, last), register)
                cursor = firstNonBlank(t, lineStartOf(t, math.min(first, lastLine)))
              case "j" | "join" =>
                join(first, math.max(2, last - first + 1), spaces = true)
                cursor = firstNonBlank(t, lineStartOf(t, first))
              case dir =>
                shiftLines(first, last, 1, right = dir == ">")
                cursor = firstNonBlank(t, lineStartOf(t, last))
            }
            endChange()
          } else { setError("E21: Cannot make changes, 'modifiable' is off"); failed = true }
        }
      case "noh" | "nohlsearch" => ()
      case "u" | "undo" => undoSteps(1)
      case "red" | "redo" => redoSteps(1)
      case _ => setError(s"E492: Not an editor command: $line"); failed = true
    }
  }

  /** An ex range at the start of `s`, and the text after it: `%`, or one or
    * two addresses (`.`, `$`, a number, `'<`, `'>`, each with `+n` or `-n`).
    * None for an address that names no line. */
  private def parseRange(s: String): Option[(Option[(Int, Int)], String)] = {
    if (s.startsWith("%")) return Some((Some((0, lastLine)), s.drop(1)))
    var i = 0
    def address(): Option[Option[Int]] = {
      val base: Option[Int] =
        if (i < s.length && s.charAt(i) == '.') { i += 1; Some(lineOf(t, cursor)) }
        else if (i < s.length && s.charAt(i) == '$') { i += 1; Some(lastLine) }
        else if (i + 1 < s.length && s.charAt(i) == '\'' && (s.charAt(i + 1) == '<' || s.charAt(i + 1) == '>')) {
          val which = s.charAt(i + 1)
          i += 2
          visualMarks.map { case (f, l) => if (which == '<') f else l }.orElse(return None)
        } else if (i < s.length && s.charAt(i).isDigit) {
          val start = i
          while (i < s.length && s.charAt(i).isDigit) i += 1
          Some(s.substring(start, i).toInt - 1)
        } else None
      var value = base
      while (i < s.length && (s.charAt(i) == '+' || s.charAt(i) == '-')) {
        val sign = if (s.charAt(i) == '+') 1 else -1
        i += 1
        val start = i
        while (i < s.length && s.charAt(i).isDigit) i += 1
        val amount = if (i > start) s.substring(start, i).toInt else 1
        value = Some(value.getOrElse(lineOf(t, cursor)) + sign * amount)
      }
      Some(value)
    }
    val first = address().getOrElse(return None)
    first match {
      case None => Some((None, s))
      case Some(f) =>
        if (i < s.length && (s.charAt(i) == ',' || s.charAt(i) == ';')) {
          i += 1
          val second = address().getOrElse(return None)
          Some((Some((f, second.getOrElse(f))), s.substring(i)))
        } else Some((Some((f, f)), s.substring(i)))
    }
  }

  private def parseSubstitute(args: String): Option[Substitution] = {
    if (args.isEmpty) return None
    val delim = args.charAt(0)
    if (delim.isLetterOrDigit || delim == '\\' || delim == ' ') return None
    val parts = mutable.ArrayBuffer.empty[String]
    val sb = new StringBuilder
    var i = 1
    while (i < args.length && parts.length < 2) {
      val c = args.charAt(i)
      if (c == '\\' && i + 1 < args.length && args.charAt(i + 1) == delim) { sb.append(delim); i += 2 }
      else if (c == '\\' && i + 1 < args.length) { sb.append(c).append(args.charAt(i + 1)); i += 2 }
      else if (c == delim) { parts += sb.toString; sb.clear(); i += 1 }
      else { sb.append(c); i += 1 }
    }
    if (parts.length < 2) { parts += sb.toString; sb.clear() }
    val flags = if (parts.length >= 2) args.substring(math.min(i, args.length)) else ""
    val pattern = parts.head
    val replacement = if (parts.length > 1) parts(1) else ""
    val pat = if (pattern.isEmpty) lastSearch.map(_._1).getOrElse(return None) else pattern
    Some(Substitution(pat, replacement, flags.contains('g'), flags.contains('i')))
  }

  private def substitute(first: Int, last: Int, s: Substitution): Unit = {
    lastSubstitute = Some(s)
    lastSearch = Some((s.pattern, true))
    val compiled = compilePattern(if (s.ignoreCase) s.pattern + "\\c" else s.pattern)
    val start = lineStartOf(t, first)
    val end = lineEnd(t, lineStartOf(t, last))
    var count = 0
    var linesChanged = 0
    var lastChangedLine = -1
    val out = t.substring(start, end).split("\n", -1).zipWithIndex.map { case (line, idx) =>
      val m = compiled.matcher(line)
      val sb = new StringBuilder
      var from = 0
      var here = 0
      var going = m.find()
      while (going) {
        sb.append(line.substring(from, m.start))
        sb.append(expandReplacement(s.replacement, m))
        from = m.end
        here += 1
        going = s.global && m.find()
      }
      if (here > 0) {
        count += here
        linesChanged += 1
        lastChangedLine = first + idx
      }
      sb.append(line.substring(from))
      sb.toString
    }
    if (count == 0) { setError(s"E486: Pattern not found: ${s.pattern}"); failed = true; return }
    val replaced = out.mkString("\n")
    edit(start, end, replaced)
    // A replacement holding line breaks moves the lines after it down.
    val extraLines = replaced.count(_ == '\n') - (last - first)
    cursor = firstNonBlank(t, lineStartOf(t, math.max(0, lastChangedLine + math.max(0, extraLines))))
    if (count > 2) setMessage(s"$count substitutions on $linesChanged line${if (linesChanged == 1) "" else "s"}")
  }

  private def expandReplacement(rep: String, m: java.util.regex.Matcher): String = {
    val sb = new StringBuilder
    var i = 0
    while (i < rep.length) {
      val c = rep.charAt(i)
      if (c == '\\' && i + 1 < rep.length) {
        val d = rep.charAt(i + 1)
        if (d.isDigit) {
          val g = d - '0'
          if (g <= m.groupCount && m.group(g) != null) sb.append(m.group(g))
        } else d match {
          case 'n' | 'r' => sb.append('\n')
          case 't' => sb.append('\t')
          case other => sb.append(other)
        }
        i += 2
      } else {
        if (c == '&') sb.append(m.group()) else sb.append(c)
        i += 1
      }
    }
    sb.toString
  }

  // ==================== undo ====================

  private def edit(start: Int, end: Int, replacement: String): Unit = {
    if (changeStart.isDefined && changeCaret < 0) changeCaret = cursor
    buffer.replace(start, end, replacement)
    t = buffer.text
  }

  private def beginChange(): Unit =
    if (changeStart.isEmpty) {
      changeStart = Some(t)
      changeCaret = -1
    }

  private def endChange(): Unit = {
    changeStart.foreach { before =>
      diff(before, t).foreach { case (start, removed, inserted) =>
        undoStack += UndoEntry(start, removed, inserted, if (changeCaret >= 0) changeCaret else cursor)
        trimUndo()
        redoStack.clear()
      }
    }
    changeStart = None
  }

  private def trimUndo(): Unit = if (undoStack.length > MaxUndo) undoStack.remove(0)

  private def undoSteps(n: Int): Unit = {
    var k = 0
    while (k < n) {
      if (undoStack.isEmpty) {
        if (k == 0) setMessage("Already at oldest change")
        return
      }
      val e = undoStack.remove(undoStack.length - 1)
      if (!t.startsWith(e.inserted, e.start)) { lostHistory(); return }
      edit(e.start, e.start + e.inserted.length, e.removed)
      redoStack += e
      cursor = math.min(e.caretBefore, t.length)
      k += 1
    }
  }

  private def redoSteps(n: Int): Unit = {
    var k = 0
    while (k < n) {
      if (redoStack.isEmpty) {
        if (k == 0) setMessage("Already at newest change")
        return
      }
      val e = redoStack.remove(redoStack.length - 1)
      if (!t.startsWith(e.removed, e.start)) { lostHistory(); return }
      edit(e.start, e.start + e.removed.length, e.inserted)
      undoStack += e
      cursor = e.start
      k += 1
    }
  }

  private def lostHistory(): Unit = {
    undoStack.clear()
    redoStack.clear()
    setError("Undo history no longer matches the text")
  }

  // ==================== messages ====================

  private def setMessage(s: String): Unit = { message = s; messageIsError = false }
  private def setError(s: String): Unit = { message = s; messageIsError = true }
  private def clearMessage(): Unit = { message = ""; messageIsError = false }
}

object VimEngine {

  /** Columns a `>>`, `<<`, `<Tab>` or `<C-t>` moves by: two, the indent the
    * repo's SQL uses. */
  val ShiftWidth: Int = 2


  private val MaxUndo = 1000
  private val MaxReplayDepth = 50

  private final case class Register(text: String, linewise: Boolean)

  /** Where a motion leaves the cursor and how an operator reads the span to
    * it. `vertical` keeps the column `j` and `k` aim for; `toEol` aims them at
    * the end of the line. */
  private final case class Landing(pos: Int, kind: Kind, vertical: Boolean = false, toEol: Boolean = false)

  private sealed trait Range
  /** Characters from `start` up to `end`, exclusive. */
  private final case class Chars(start: Int, end: Int) extends Range
  /** Lines `first` to `last`, inclusive. */
  private final case class Lines(first: Int, last: Int) extends Range

  /** One undo step: at `start`, `removed` became `inserted`. */
  private final case class UndoEntry(start: Int, removed: String, inserted: String, caretBefore: Int)

  /** What a visual-mode change covered, so `.` covers as much again from the
    * cursor: lines, and the characters of the last one. */
  private final case class VisualSpan(linewise: Boolean, lines: Int, lastColumn: Int)

  /** The last change, for `.`: its command, the keys typed after it in insert
    * mode, and the visual selection it acted on. */
  private final case class Repeat(cmd: VimCommand.Cmd, keys: Vector[VimKey], visual: Option[VisualSpan])

  private final case class Substitution(pattern: String, replacement: String, global: Boolean, ignoreCase: Boolean)

  private final class InsertSession(var cmd: VimCommand.Cmd, var count: Int, var open: Option[Boolean]) {
    val keys: mutable.ArrayBuffer[VimKey] = mutable.ArrayBuffer.empty
    var awaitingRegister: Boolean = false
    /** In replace mode, what each typed character overwrote, for `<BS>`. */
    val replaced: mutable.ArrayBuffer[Option[Char]] = mutable.ArrayBuffer.empty
    var visual: Option[VisualSpan] = None
  }

  /** The common prefix and suffix of two texts set aside: where they differ,
    * and what each has there. None when they are equal. */
  private[gui] def diff(before: String, after: String): Option[(Int, String, String)] = {
    if (before == after) return None
    val max = math.min(before.length, after.length)
    var p = 0
    while (p < max && before.charAt(p) == after.charAt(p)) p += 1
    var s = 0
    while (s < max - p && before.charAt(before.length - 1 - s) == after.charAt(after.length - 1 - s)) s += 1
    Some((p, before.substring(p, before.length - s), after.substring(p, after.length - s)))
  }
}
