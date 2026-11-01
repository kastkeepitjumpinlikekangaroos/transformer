package com.transformer.gui

import java.util.regex.{Pattern, PatternSyntaxException}

/** The text arithmetic the vim engine moves and edits by: lines, words,
  * paragraphs, brackets, quoted strings and searches over one string, with
  * every position a character offset. Pure functions with no state.
  *
  * A line ends at its `\n`, which belongs to it, so a position on a `\n` is the
  * end of that line. Text ending in `\n` has an empty last line after it, as
  * the editor shows.
  */
private[gui] object VimText {

  /** A span of text a text object covers. Charwise, `end` is exclusive.
    * Linewise, `start` and `end` are the starts of the first and the last line
    * it covers. */
  final case class Span(start: Int, end: Int, linewise: Boolean)

  // ---- lines ----

  def lineStart(t: String, pos: Int): Int = {
    val p = math.min(math.max(pos, 0), t.length)
    if (p == 0) 0 else t.lastIndexOf('\n', p - 1) + 1
  }

  def lineEnd(t: String, pos: Int): Int = {
    val i = t.indexOf('\n', math.max(pos, 0))
    if (i < 0) t.length else i
  }

  def lineOf(t: String, pos: Int): Int = {
    var n = 0
    var i = 0
    val end = math.min(pos, t.length)
    while (i < end) {
      if (t.charAt(i) == '\n') n += 1
      i += 1
    }
    n
  }

  def lineCount(t: String): Int = lineOf(t, t.length) + 1

  /** The start of line `line`, clamped to the first and the last line. */
  def lineStartOf(t: String, line: Int): Int = {
    if (line <= 0) return 0
    var n = 0
    var i = 0
    while (i < t.length) {
      if (t.charAt(i) == '\n') {
        n += 1
        if (n == line) return i + 1
      }
      i += 1
    }
    lineStart(t, t.length)
  }

  def column(t: String, pos: Int): Int = pos - lineStart(t, pos)

  def hasNextLine(t: String, pos: Int): Boolean = lineEnd(t, pos) < t.length
  def nextLineStart(t: String, pos: Int): Int = lineEnd(t, pos) + 1
  def prevLineStart(t: String, pos: Int): Int = lineStart(t, lineStart(t, pos) - 1)

  def isEmptyLine(t: String, pos: Int): Boolean = lineStart(t, pos) == lineEnd(t, pos)

  def isBlank(c: Char): Boolean = c == ' ' || c == '\t'

  /** The first character of the line that is not a space or a tab, or the
    * line's end when there is none. */
  def firstNonBlank(t: String, pos: Int): Int = {
    val e = lineEnd(t, pos)
    var p = lineStart(t, pos)
    while (p < e && isBlank(t.charAt(p))) p += 1
    p
  }

  /** The last character of the line that is not a space or a tab, or the
    * line's start when there is none. */
  def lastNonBlank(t: String, pos: Int): Int = {
    val s = lineStart(t, pos)
    var p = lineEnd(t, pos) - 1
    while (p > s && isBlank(t.charAt(p))) p -= 1
    math.max(p, s)
  }

  /** The leading spaces and tabs of the line. */
  def indentOf(t: String, pos: Int): String =
    t.substring(lineStart(t, pos), firstNonBlank(t, pos))

  /** Where a cursor that sits on a character goes for `pos`: on the line's
    * last character at most, or the line's start when the line is empty. */
  def onChar(t: String, pos: Int): Int = {
    val p = math.min(math.max(pos, 0), t.length)
    val s = lineStart(t, p)
    val e = lineEnd(t, p)
    if (e == s) s else math.min(p, e - 1)
  }

  /** Columns a tab reaches to the next multiple of, on screen and in indent. */
  val TabStop: Int = 8

  private def nextColumn(c: Char, col: Int): Int = if (c == '\t') (col / TabStop + 1) * TabStop else col + 1

  /** The screen column the character at `pos` starts at. */
  def virtualColumn(t: String, pos: Int): Int = {
    var col = 0
    var i = lineStart(t, pos)
    while (i < pos && i < t.length) {
      col = nextColumn(t.charAt(i), col)
      i += 1
    }
    col
  }

  /** The column `j` and `k` aim for from `pos`: its screen column, or on a
    * tab the tab's last column, where vim shows the cursor. */
  def cursorColumn(t: String, pos: Int): Int =
    if (pos < t.length && t.charAt(pos) == '\t') nextColumn('\t', virtualColumn(t, pos)) - 1
    else virtualColumn(t, pos)

  /** The character on the line holding `pos` that covers screen column
    * `column`, or the line's last character when the line is shorter. */
  def atColumn(t: String, pos: Int, column: Int): Int = {
    val s = lineStart(t, pos)
    val e = lineEnd(t, pos)
    if (e == s) return s
    if (column == Int.MaxValue) return e - 1
    var col = 0
    var i = s
    while (i < e) {
      val next = nextColumn(t.charAt(i), col)
      if (column < next) return i
      col = next
      i += 1
    }
    e - 1
  }

  // ---- words ----

  /** vim's character class: 0 for blank (space, tab, line break), 2 for a
    * keyword character (a letter, a digit, `_`), 1 for anything else. A WORD
    * has two: blank, and not. */
  def charClass(c: Char, big: Boolean): Int =
    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') 0
    else if (big) 1
    else if (Character.isLetterOrDigit(c) || c == '_') 2
    else 1

  def isKeywordChar(c: Char): Boolean = charClass(c, big = false) == 2

  /** True when `pos` starts an empty line, which the word motions treat as a
    * word of its own. */
  private def emptyLineAt(t: String, pos: Int): Boolean =
    (pos == t.length || t.charAt(pos) == '\n') && (pos == 0 || t.charAt(pos - 1) == '\n')

  /** `w`: the start of the next word, or the end of the text. */
  def wordForward(t: String, pos: Int, big: Boolean): Int = {
    val n = t.length
    if (pos >= n) return n
    var p = pos
    val sc = charClass(t.charAt(p), big)
    if (sc != 0) while (p < n && charClass(t.charAt(p), big) == sc) p += 1
    while (p < n && charClass(t.charAt(p), big) == 0) {
      if (p != pos && emptyLineAt(t, p)) return p
      p += 1
    }
    p
  }

  /** `b`: the start of this word, or of the one before when already there. */
  def wordBackward(t: String, pos: Int, big: Boolean): Int = {
    if (pos <= 0) return 0
    var p = math.min(pos, t.length) - 1
    while (charClass(t.charAt(p), big) == 0) {
      if (emptyLineAt(t, p)) return p
      if (p == 0) return 0
      p -= 1
    }
    val c = charClass(t.charAt(p), big)
    while (p > 0 && charClass(t.charAt(p - 1), big) == c) p -= 1
    p
  }

  /** `e`: the end of this word, or of the next one when already there. Stays
    * at `pos` when no word follows. */
  def wordEnd(t: String, pos: Int, big: Boolean): Int = {
    val n = t.length
    if (pos >= n - 1) return pos
    val sc = charClass(t.charAt(pos), big)
    var p = pos + 1
    if (sc != 0 && charClass(t.charAt(p), big) == sc) {
      while (p < n && charClass(t.charAt(p), big) == sc) p += 1
      return p - 1
    }
    while (p < n && charClass(t.charAt(p), big) == 0) p += 1
    if (p >= n) return pos
    val c = charClass(t.charAt(p), big)
    while (p < n && charClass(t.charAt(p), big) == c) p += 1
    p - 1
  }

  /** `ge`: the end of the word before this one. */
  def wordEndBackward(t: String, pos: Int, big: Boolean): Int = {
    if (pos <= 0) return 0
    var p = math.min(pos, t.length)
    val sc = if (p < t.length) charClass(t.charAt(p), big) else 0
    p -= 1
    if (sc != 0) {
      while (p > 0 && charClass(t.charAt(p), big) == sc) p -= 1
      if (p == 0 && charClass(t.charAt(0), big) == sc) return 0
    }
    while (charClass(t.charAt(p), big) == 0) {
      if (emptyLineAt(t, p)) return p
      if (p == 0) return 0
      p -= 1
    }
    p
  }

  /** The keyword under or after `pos` on its line, as a charwise span, for
    * `*` and `#`. */
  def keywordAt(t: String, pos: Int): Option[(Int, Int)] = {
    val e = lineEnd(t, pos)
    var p = math.max(pos, lineStart(t, pos))
    while (p < e && !isKeywordChar(t.charAt(p))) p += 1
    if (p >= e) return None
    var s = p
    while (s > 0 && isKeywordChar(t.charAt(s - 1)) && t.charAt(s - 1) != '\n') s -= 1
    var end = p
    while (end < e && isKeywordChar(t.charAt(end))) end += 1
    Some((s, end))
  }

  // ---- paragraphs ----

  /** `}`: the empty line after this paragraph, or the end of the text. */
  def paragraphForward(t: String, pos: Int, count: Int): Int = {
    var ls = lineStart(t, pos)
    var k = 0
    while (k < count) {
      while (hasNextLine(t, ls) && isEmptyLine(t, ls)) ls = nextLineStart(t, ls)
      while (hasNextLine(t, ls) && !isEmptyLine(t, ls)) ls = nextLineStart(t, ls)
      k += 1
    }
    if (!hasNextLine(t, ls) && !isEmptyLine(t, ls)) t.length else ls
  }

  /** `{`: the empty line before this paragraph, or the start of the text. */
  def paragraphBackward(t: String, pos: Int, count: Int): Int = {
    var ls = lineStart(t, pos)
    var k = 0
    while (k < count) {
      while (ls > 0 && isEmptyLine(t, ls)) ls = prevLineStart(t, ls)
      while (ls > 0 && !isEmptyLine(t, ls)) ls = prevLineStart(t, ls)
      k += 1
    }
    ls
  }

  // ---- characters in a line ----

  /** The `count`-th `c` after (or before) `pos` on its line. */
  def findInLine(t: String, pos: Int, c: Char, forward: Boolean, count: Int): Option[Int] = {
    val s = lineStart(t, pos)
    val e = lineEnd(t, pos)
    var p = pos
    var k = 0
    while (k < count) {
      if (forward) {
        p += 1
        while (p < e && t.charAt(p) != c) p += 1
        if (p >= e) return None
      } else {
        p -= 1
        while (p >= s && t.charAt(p) != c) p -= 1
        if (p < s) return None
      }
      k += 1
    }
    Some(p)
  }

  // ---- brackets ----

  private val Brackets = "()[]{}"

  /** `%`: the bracket matching the one under or after `pos` on its line. */
  def matchPair(t: String, pos: Int): Option[Int] = {
    val e = lineEnd(t, pos)
    var p = pos
    while (p < e && Brackets.indexOf(t.charAt(p)) < 0) p += 1
    if (p >= e) return None
    val i = Brackets.indexOf(t.charAt(p))
    val open = Brackets.charAt(i - i % 2)
    val close = Brackets.charAt(i - i % 2 + 1)
    if (i % 2 == 0) closeOf(t, p, open, close) else openOf(t, p, open, close)
  }

  /** The `close` matching the `open` at `openPos`. */
  private def closeOf(t: String, openPos: Int, open: Char, close: Char): Option[Int] = {
    var depth = 0
    var q = openPos
    while (q < t.length) {
      val ch = t.charAt(q)
      if (ch == open) depth += 1
      else if (ch == close) {
        depth -= 1
        if (depth == 0) return Some(q)
      }
      q += 1
    }
    None
  }

  /** The `open` matching the `close` at `closePos`. */
  private def openOf(t: String, closePos: Int, open: Char, close: Char): Option[Int] =
    unmatchedOpenBefore(t, closePos, open, close)

  /** The nearest `open` before `from` that no `close` between closes. */
  private def unmatchedOpenBefore(t: String, from: Int, open: Char, close: Char): Option[Int] = {
    var depth = 0
    var q = from - 1
    while (q >= 0) {
      val ch = t.charAt(q)
      if (ch == close) depth += 1
      else if (ch == open) {
        if (depth == 0) return Some(q)
        depth -= 1
      }
      q -= 1
    }
    None
  }

  /** `i(` / `a(` and their kin: the `count`-th pair of brackets around `pos`,
    * with them or without. When the open bracket ends its line and the close
    * bracket starts one, the inner span is the lines between, linewise, as in
    * vim. */
  def bracketObject(t: String, pos: Int, open: Char, close: Char, inner: Boolean, count: Int): Option[Span] = {
    val first =
      if (pos < t.length && t.charAt(pos) == open) Some(pos)
      else unmatchedOpenBefore(t, if (pos < t.length && t.charAt(pos) == close) pos else pos + 1, open, close)
    var o = first.getOrElse(return None)
    var k = 1
    while (k < count) {
      o = unmatchedOpenBefore(t, o, open, close).getOrElse(return None)
      k += 1
    }
    val c = closeOf(t, o, open, close).getOrElse(return None)
    if (!inner) return Some(Span(o, c + 1, linewise = false))
    val s = if (o + 1 < c && t.charAt(o + 1) == '\n') o + 2 else o + 1
    val closeLine = lineStart(t, c)
    val closeStartsLine = closeLine > o && (closeLine until c).forall(i => isBlank(t.charAt(i)))
    if (s > o + 1 && closeStartsLine && closeLine > s) Some(Span(s, lineStart(t, closeLine - 1), linewise = true))
    else Some(Span(s, math.max(s, c), linewise = false))
  }

  // ---- quotes ----

  /** `i"` / `a"`: the quoted string on the line that holds `pos`, or the first
    * one after it. Quotes pair up from the start of the line. The `a` form
    * takes the white space after the string, or before it when none follows. */
  def quoteObject(t: String, pos: Int, quote: Char, inner: Boolean): Option[Span] = {
    val s = lineStart(t, pos)
    val e = lineEnd(t, pos)
    val quotes = (s until e).filter(i => t.charAt(i) == quote)
    val pairs = quotes.grouped(2).collect { case Seq(a, b) => (a, b) }.toVector
    val chosen = pairs.find { case (a, b) => a <= pos && pos <= b }.orElse(pairs.find(_._1 > pos))
    chosen.map { case (a, b) =>
      if (inner) Span(a + 1, b, linewise = false)
      else {
        var end = b + 1
        while (end < e && isBlank(t.charAt(end))) end += 1
        var start = a
        if (end == b + 1) while (start > s && isBlank(t.charAt(start - 1))) start -= 1
        Span(start, end, linewise = false)
      }
    }
  }

  // ---- words as objects ----

  /** `iw` / `aw` (and `iW` / `aW`): the word, or run of white space or of
    * punctuation, under `pos`. The inner form counts white space as a run of
    * its own; the `a` form takes a word with the white space after it, or
    * before it when none follows, and on white space, the white space with
    * the word after it. Stays within the line. */
  def wordObject(t: String, pos: Int, big: Boolean, inner: Boolean, count: Int): Option[Span] = {
    val s = lineStart(t, pos)
    val e = lineEnd(t, pos)
    if (s == e) return None
    val p = math.min(math.max(pos, s), e - 1)
    def cls(i: Int): Int = charClass(t.charAt(i), big)
    def runEnd(from: Int): Int = {
      var q = from
      val c = cls(from)
      while (q < e && cls(q) == c) q += 1
      q
    }
    var start = p
    while (start > s && cls(start - 1) == cls(p)) start -= 1
    var end = runEnd(p)
    if (inner) {
      var k = 1
      while (k < count && end < e) { end = runEnd(end); k += 1 }
      Some(Span(start, end, linewise = false))
    } else if (cls(p) == 0) {
      var k = 0
      while (k < count && end < e) {
        end = runEnd(end)
        k += 1
        if (k < count && end < e && cls(end) == 0) end = runEnd(end)
      }
      Some(Span(start, end, linewise = false))
    } else {
      var k = 1
      if (end < e && cls(end) == 0) end = runEnd(end)
      while (k < count && end < e) {
        end = runEnd(end)
        if (end < e && cls(end) == 0) end = runEnd(end)
        k += 1
      }
      if (cls(end - 1) != 0) while (start > s && cls(start - 1) == 0) start -= 1
      Some(Span(start, end, linewise = false))
    }
  }

  // ---- paragraphs as objects ----

  /** `ip` / `ap`: the run of lines around `pos` that are all empty or all
    * not, linewise. The `a` form adds the run after it (or, at the end of the
    * text, the empty lines before it). */
  def paragraphObject(t: String, pos: Int, inner: Boolean, count: Int): Span = {
    def runFrom(ls: Int): Int = {
      val empty = isEmptyLine(t, ls)
      var last = ls
      while (hasNextLine(t, last) && isEmptyLine(t, nextLineStart(t, last)) == empty) last = nextLineStart(t, last)
      last
    }
    val here = lineStart(t, pos)
    val empty = isEmptyLine(t, here)
    var first = here
    while (first > 0 && isEmptyLine(t, prevLineStart(t, first)) == empty) first = prevLineStart(t, first)
    var last = runFrom(here)
    val runs = if (inner) count else count * 2
    var k = 1
    while (k < runs && hasNextLine(t, last)) {
      last = runFrom(nextLineStart(t, last))
      k += 1
    }
    if (!inner && k < runs && !empty) {
      // No run after the paragraph: take the empty lines before it instead.
      while (first > 0 && isEmptyLine(t, prevLineStart(t, first))) first = prevLineStart(t, first)
    }
    Span(first, last, linewise = true)
  }

  // ---- search ----

  /** A vim search pattern as a java regex. Vim's magic syntax is translated
    * (`\(`, `\|`, `\+`, `\?`, `\{n,m}` and `\<` `\>` are special, and a bare
    * `(`, `|`, `+`, `?`, `{` is literal), `\c` anywhere makes the search ignore
    * case, and a pattern java cannot compile is searched for as literal
    * text. */
  def compilePattern(vimPattern: String): Pattern = {
    val (regex, ignoreCase) = translate(vimPattern)
    val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE else 0
    try Pattern.compile(regex, flags | Pattern.MULTILINE)
    catch {
      case _: PatternSyntaxException =>
        Pattern.compile(Pattern.quote(vimPattern.replace("\\c", "").replace("\\C", "")), flags)
    }
  }

  private def translate(vim: String): (String, Boolean) = {
    val sb = new StringBuilder
    var ignoreCase = false
    var inBrace = false
    var i = 0
    while (i < vim.length) {
      val c = vim.charAt(i)
      if (c == '\\' && i + 1 < vim.length) {
        val d = vim.charAt(i + 1)
        d match {
          case '(' | ')' | '|' | '+' | '?' => sb.append(d)
          case '=' => sb.append('?')
          case '{' => sb.append('{'); inBrace = true
          case '}' => sb.append('}'); inBrace = false
          case '<' | '>' => sb.append("\\b")
          case 'c' => ignoreCase = true
          case 'C' => ignoreCase = false
          case 's' | 'S' | 'd' | 'D' | 'w' | 'W' | 'n' | 't' | 'r' => sb.append('\\').append(d)
          case 'a' => sb.append("[A-Za-z]")
          case 'l' => sb.append("[a-z]")
          case 'u' => sb.append("[A-Z]")
          case 'x' => sb.append("[0-9A-Fa-f]")
          case other => sb.append(Pattern.quote(other.toString))
        }
        i += 2
      } else {
        c match {
          case '(' | ')' | '|' | '+' | '?' | '{' => sb.append('\\').append(c)
          case '}' =>
            if (inBrace) { sb.append('}'); inBrace = false }
            else sb.append("\\}")
          case other => sb.append(other)
        }
        i += 1
      }
    }
    (sb.toString, ignoreCase)
  }

  /** The start of the next match after `from` (forward) or the last one
    * before it (backward), wrapping around the end of the text. The flag says
    * whether the search wrapped. */
  def search(t: String, pattern: Pattern, from: Int, forward: Boolean): Option[(Int, Boolean)] = {
    val m = pattern.matcher(t)
    if (forward) {
      if (from + 1 <= t.length && m.find(from + 1)) Some((m.start, false))
      else if (m.find(0) && m.start <= from) Some((m.start, true))
      else None
    } else {
      var before = -1
      var last = -1
      while (m.find()) {
        if (m.start < from) before = m.start
        last = m.start
      }
      if (before >= 0) Some((before, false))
      else if (last >= 0) Some((last, true))
      else None
    }
  }
}
