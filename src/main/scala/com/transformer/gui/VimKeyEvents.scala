package com.transformer.gui

import javafx.scene.input.{KeyCode, KeyEvent}

/** How the SQL editor's vim mode reads a JavaFX key event.
  *
  * A printable key is read from its KEY_TYPED event, which carries the
  * character the layout and shift made; every other key from KEY_PRESSED.
  * The platform's clipboard and undo shortcuts (Cmd on macOS, Ctrl elsewhere)
  * stay those shortcuts. Any other shortcut is the window's (Cmd+R runs the
  * job, Cmd+Enter a query). On macOS every Ctrl chord is vim's; elsewhere,
  * where Ctrl is also the shortcut key, vim gets the Ctrl chords the platform
  * shortcuts above leave.
  */
private[gui] object VimKeyEvents {

  sealed trait Shortcut
  object Shortcut {
    case object Undo extends Shortcut
    case object Redo extends Shortcut
    case object Copy extends Shortcut
    case object Cut extends Shortcut
    case object Paste extends Shortcut
    case object SelectAll extends Shortcut
  }

  sealed trait Pressed
  object Pressed {
    /** A key for the engine. */
    final case class Key(key: VimKey) extends Pressed
    final case class Platform(shortcut: Shortcut) extends Pressed
    /** Not the editor's: the window may have it. */
    case object PassThrough extends Pressed
    /** A printable key; its character arrives as KEY_TYPED. */
    case object Typed extends Pressed
  }

  def pressed(e: KeyEvent, mac: Boolean): Pressed = {
    val code = e.getCode
    val shortcutDown = if (mac) e.isMetaDown else e.isControlDown
    if (shortcutDown && !e.isAltDown) {
      platformShortcut(code, e.isShiftDown) match {
        case Some(s) => return Pressed.Platform(s)
        case None =>
          if (mac) return Pressed.PassThrough
      }
    }
    if (e.isControlDown && !e.isAltDown && !(mac && e.isMetaDown)) {
      return ctrlKey(code).map(Pressed.Key).getOrElse(Pressed.PassThrough)
    }
    if (e.isMetaDown) return Pressed.PassThrough
    code match {
      case KeyCode.ESCAPE => Pressed.Key(VimKey.Esc)
      case KeyCode.ENTER => Pressed.Key(VimKey.Enter)
      case KeyCode.BACK_SPACE => Pressed.Key(VimKey.Backspace)
      case KeyCode.DELETE => Pressed.Key(VimKey.Delete)
      case KeyCode.TAB => if (e.isShiftDown) Pressed.PassThrough else Pressed.Key(VimKey.Tab)
      case KeyCode.LEFT | KeyCode.KP_LEFT => Pressed.Key(VimKey.Left)
      case KeyCode.RIGHT | KeyCode.KP_RIGHT => Pressed.Key(VimKey.Right)
      case KeyCode.UP | KeyCode.KP_UP => Pressed.Key(VimKey.Up)
      case KeyCode.DOWN | KeyCode.KP_DOWN => Pressed.Key(VimKey.Down)
      case KeyCode.HOME => Pressed.Key(VimKey.Home)
      case KeyCode.END => Pressed.Key(VimKey.End)
      case KeyCode.PAGE_UP => Pressed.Key(VimKey.PageUp)
      case KeyCode.PAGE_DOWN => Pressed.Key(VimKey.PageDown)
      case _ => Pressed.Typed
    }
  }

  /** The characters a KEY_TYPED event types, for the engine: none for a
    * chord read from KEY_PRESSED, or for a control character. AltGr (Ctrl
    * and Alt together) types characters on Windows layouts. */
  def typed(e: KeyEvent, mac: Boolean): Seq[VimKey] = {
    val s = e.getCharacter
    if (s == null || s.isEmpty || s == KeyEvent.CHAR_UNDEFINED) Nil
    else if (mac && e.isMetaDown) Nil
    else if (e.isControlDown && !e.isAltDown) Nil
    else s.toSeq.filter(c => c >= ' ' && c != 127.toChar).map(VimKey.Typed)
  }

  private def platformShortcut(code: KeyCode, shift: Boolean): Option[Shortcut] = code match {
    case KeyCode.Z => Some(if (shift) Shortcut.Redo else Shortcut.Undo)
    case KeyCode.C => Some(Shortcut.Copy)
    case KeyCode.X => Some(Shortcut.Cut)
    case KeyCode.V => Some(Shortcut.Paste)
    case KeyCode.A => Some(Shortcut.SelectAll)
    case _ => None
  }

  private def ctrlKey(code: KeyCode): Option[VimKey] =
    if (code.isLetterKey) Some(VimKey.Ctrl(code.getChar.toLowerCase.charAt(0)))
    else if (code == KeyCode.OPEN_BRACKET) Some(VimKey.Ctrl('['))
    else None
}
