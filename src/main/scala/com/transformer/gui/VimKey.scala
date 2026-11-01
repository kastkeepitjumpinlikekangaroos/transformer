package com.transformer.gui

import java.util.Locale

/** One key the vim engine reads.
  *
  * A printable key arrives as the character it types, shift and the keyboard
  * layout already applied, so `A`, `$` and `<` are each a [[VimKey.Typed]].
  * Control chords are [[VimKey.Ctrl]] with a lower-case letter (or `[`), and
  * the keys that type nothing have objects of their own.
  */
sealed trait VimKey

object VimKey {
  final case class Typed(c: Char) extends VimKey
  final case class Ctrl(c: Char) extends VimKey
  case object Esc extends VimKey
  case object Enter extends VimKey
  case object Backspace extends VimKey
  case object Delete extends VimKey
  case object Tab extends VimKey
  case object Left extends VimKey
  case object Right extends VimKey
  case object Up extends VimKey
  case object Down extends VimKey
  case object Home extends VimKey
  case object End extends VimKey
  case object PageUp extends VimKey
  case object PageDown extends VimKey

  private val Named: Map[String, VimKey] = Map(
    "esc" -> Esc, "cr" -> Enter, "enter" -> Enter, "return" -> Enter,
    "bs" -> Backspace, "del" -> Delete, "tab" -> Tab,
    "left" -> Left, "right" -> Right, "up" -> Up, "down" -> Down,
    "home" -> Home, "end" -> End, "pageup" -> PageUp, "pagedown" -> PageDown,
    "space" -> Typed(' '), "lt" -> Typed('<'), "bar" -> Typed('|'), "bslash" -> Typed('\\')
  )

  /** Read vim's key notation: plain characters stand for themselves, `<Esc>`,
    * `<CR>`, `<BS>`, `<Del>`, `<Tab>`, `<Left>` and the like name the keys that
    * type nothing, `<C-r>` is a control chord, and `<lt>` is a literal `<`. A
    * `<` that opens no known name is the character itself. A newline in the
    * text is [[Enter]].
    */
  def parse(notation: String): Vector[VimKey] = {
    val out = Vector.newBuilder[VimKey]
    var i = 0
    while (i < notation.length) {
      val c = notation.charAt(i)
      val close = if (c == '<') notation.indexOf('>', i + 1) else -1
      val named =
        if (close > i + 1) {
          val name = notation.substring(i + 1, close)
          val lower = name.toLowerCase(Locale.ROOT)
          if (lower.startsWith("c-") && name.length == 3) Some(Ctrl(name.charAt(2).toLower))
          else Named.get(lower)
        } else None
      named match {
        case Some(k) =>
          out += k
          i = close + 1
        case None =>
          out += (if (c == '\n') Enter else Typed(c))
          i += 1
      }
    }
    out.result()
  }

  /** The notation [[parse]] reads back to the same keys. */
  def render(keys: Seq[VimKey]): String = {
    val sb = new StringBuilder
    keys.foreach {
      case Typed('<') => sb.append("<lt>")
      case Typed(c)   => sb.append(c)
      case Ctrl(c)    => sb.append("<C-").append(c).append('>')
      case Esc        => sb.append("<Esc>")
      case Enter      => sb.append("<CR>")
      case Backspace  => sb.append("<BS>")
      case Delete     => sb.append("<Del>")
      case Tab        => sb.append("<Tab>")
      case Left       => sb.append("<Left>")
      case Right      => sb.append("<Right>")
      case Up         => sb.append("<Up>")
      case Down       => sb.append("<Down>")
      case Home       => sb.append("<Home>")
      case End        => sb.append("<End>")
      case PageUp     => sb.append("<PageUp>")
      case PageDown   => sb.append("<PageDown>")
    }
    sb.toString
  }
}
