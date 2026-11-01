package com.transformer.gui

import com.transformer.gui.VimKeyEvents.{Pressed, Shortcut}

import javafx.scene.input.{KeyCode, KeyEvent}
import org.junit.Assert._
import org.junit.Test

/** How the SQL editor's vim mode reads key events, on macOS (Cmd is the
  * shortcut key, every Ctrl chord is vim's) and elsewhere (Ctrl is both).
  * Building a KeyEvent needs no running toolkit. */
class VimKeyEventsTest {

  private def press(code: KeyCode, shift: Boolean = false, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false): KeyEvent =
    new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta)

  private def typed(ch: String, ctrl: Boolean = false, alt: Boolean = false, meta: Boolean = false): KeyEvent =
    new KeyEvent(KeyEvent.KEY_TYPED, ch, "", KeyCode.UNDEFINED, false, ctrl, alt, meta)

  @Test def keysThatTypeNothingGoToTheEngine(): Unit = {
    for (mac <- Seq(true, false)) {
      assertEquals(Pressed.Key(VimKey.Esc), VimKeyEvents.pressed(press(KeyCode.ESCAPE), mac))
      assertEquals(Pressed.Key(VimKey.Enter), VimKeyEvents.pressed(press(KeyCode.ENTER), mac))
      assertEquals(Pressed.Key(VimKey.Backspace), VimKeyEvents.pressed(press(KeyCode.BACK_SPACE), mac))
      assertEquals(Pressed.Key(VimKey.Tab), VimKeyEvents.pressed(press(KeyCode.TAB), mac))
      assertEquals(Pressed.Key(VimKey.Left), VimKeyEvents.pressed(press(KeyCode.LEFT, shift = true), mac))
      assertEquals(Pressed.PassThrough, VimKeyEvents.pressed(press(KeyCode.TAB, shift = true), mac))
      // A printable key waits for its KEY_TYPED.
      assertEquals(Pressed.Typed, VimKeyEvents.pressed(press(KeyCode.D), mac))
      assertEquals(Pressed.Typed, VimKeyEvents.pressed(press(KeyCode.DIGIT4, shift = true), mac))
    }
  }

  @Test def onMacCtrlIsVimsAndCmdIsTheWindows(): Unit = {
    assertEquals(Pressed.Key(VimKey.Ctrl('r')), VimKeyEvents.pressed(press(KeyCode.R, ctrl = true), mac = true))
    assertEquals(Pressed.Key(VimKey.Ctrl('v')), VimKeyEvents.pressed(press(KeyCode.V, ctrl = true), mac = true))
    assertEquals(Pressed.Key(VimKey.Ctrl('[')), VimKeyEvents.pressed(press(KeyCode.OPEN_BRACKET, ctrl = true), mac = true))
    // Cmd+R runs the job and Cmd+Enter a query: the window's.
    assertEquals(Pressed.PassThrough, VimKeyEvents.pressed(press(KeyCode.R, meta = true), mac = true))
    assertEquals(Pressed.PassThrough, VimKeyEvents.pressed(press(KeyCode.ENTER, meta = true), mac = true))
    assertEquals(Pressed.Platform(Shortcut.Undo), VimKeyEvents.pressed(press(KeyCode.Z, meta = true), mac = true))
    assertEquals(Pressed.Platform(Shortcut.Redo), VimKeyEvents.pressed(press(KeyCode.Z, shift = true, meta = true), mac = true))
    assertEquals(Pressed.Platform(Shortcut.Copy), VimKeyEvents.pressed(press(KeyCode.C, meta = true), mac = true))
    assertEquals(Pressed.Platform(Shortcut.Cut), VimKeyEvents.pressed(press(KeyCode.X, meta = true), mac = true))
    assertEquals(Pressed.Platform(Shortcut.Paste), VimKeyEvents.pressed(press(KeyCode.V, meta = true), mac = true))
    assertEquals(Pressed.Platform(Shortcut.SelectAll), VimKeyEvents.pressed(press(KeyCode.A, meta = true), mac = true))
  }

  @Test def elsewhereCtrlIsTheClipboardsAndOtherwiseVims(): Unit = {
    assertEquals(Pressed.Platform(Shortcut.Copy), VimKeyEvents.pressed(press(KeyCode.C, ctrl = true), mac = false))
    assertEquals(Pressed.Platform(Shortcut.Paste), VimKeyEvents.pressed(press(KeyCode.V, ctrl = true), mac = false))
    assertEquals(Pressed.Platform(Shortcut.Undo), VimKeyEvents.pressed(press(KeyCode.Z, ctrl = true), mac = false))
    assertEquals(Pressed.Key(VimKey.Ctrl('r')), VimKeyEvents.pressed(press(KeyCode.R, ctrl = true), mac = false))
    assertEquals(Pressed.Key(VimKey.Ctrl('d')), VimKeyEvents.pressed(press(KeyCode.D, ctrl = true), mac = false))
    // Ctrl+Enter runs a query.
    assertEquals(Pressed.PassThrough, VimKeyEvents.pressed(press(KeyCode.ENTER, ctrl = true), mac = false))
    assertEquals(Pressed.PassThrough, VimKeyEvents.pressed(press(KeyCode.R, meta = true), mac = false))
  }

  @Test def typedCharactersAreTheEnginesUnlessAChordTypedThem(): Unit = {
    for (mac <- Seq(true, false)) {
      assertEquals(Seq(VimKey.Typed('$')), VimKeyEvents.typed(typed("$"), mac))
      assertEquals(Seq(VimKey.Typed('a'), VimKey.Typed('b')), VimKeyEvents.typed(typed("ab"), mac))
      assertEquals(Nil, VimKeyEvents.typed(typed("\r"), mac))
      assertEquals(Nil, VimKeyEvents.typed(typed("r", ctrl = true), mac))
      assertEquals(Nil, VimKeyEvents.typed(typed(KeyEvent.CHAR_UNDEFINED), mac))
    }
    assertEquals(Nil, VimKeyEvents.typed(typed("v", meta = true), mac = true))
    // AltGr (Ctrl and Alt) types characters on Windows layouts, and Option
    // on macOS.
    assertEquals(Seq(VimKey.Typed('@')), VimKeyEvents.typed(typed("@", ctrl = true, alt = true), mac = false))
    assertEquals(Seq(VimKey.Typed('e')), VimKeyEvents.typed(typed("e", alt = true), mac = true))
  }
}
