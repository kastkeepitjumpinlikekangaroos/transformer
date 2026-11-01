package com.transformer.gui

import org.junit.Assert._
import org.junit.Test

/** The vim engine over a plain string. Each case writes the text with `|` at
  * the caret, the keys in vim's notation, and the text and caret after. */
class VimEngineTest {

  private final class StringBuffer(initial: String) extends VimBuffer {
    var text: String = initial.replace("|", "")
    var caret: Int = math.max(0, initial.indexOf('|'))
    var anchor: Int = caret
    var editable: Boolean = true
    var clip: Option[String] = None
    var visible: (Int, Int) = (0, 1000)
    var scrolled: Option[(Int, VimScroll)] = None
    var writes: Int = 0
    var quits: List[Boolean] = Nil
    var writeResult: Option[String] = None
    var quitResult: Option[String] = None
    def replace(start: Int, end: Int, replacement: String): Unit =
      text = text.substring(0, start) + replacement + text.substring(end)
    def select(a: Int, c: Int): Unit = { anchor = a; caret = c }
    def visibleLines: (Int, Int) = visible
    def scrollTo(line: Int, where: VimScroll): Unit = scrolled = Some((line, where))
    def clipboard: Option[String] = clip
    def setClipboard(s: String): Unit = clip = Some(s)
    def write(): Option[String] = { writes += 1; writeResult }
    def quit(force: Boolean): Option[String] = { quits = force :: quits; quitResult }
    def shown: String = text.substring(0, caret) + "|" + text.substring(caret)
  }

  private def setup(before: String): (VimEngine, StringBuffer) = {
    val b = new StringBuffer(before)
    (new VimEngine(b), b)
  }

  private def type_(e: VimEngine, keys: String): Unit = VimKey.parse(keys).foreach(e.handleKey)

  private def check(before: String, keys: String, after: String): Unit = {
    val (e, b) = setup(before)
    type_(e, keys)
    assertEquals(s"'$keys' on '$before'", after, b.shown)
  }

  // ---- keys ----

  @Test def notationRoundTrips(): Unit = {
    val keys = VimKey.parse("d2w<Esc><C-r><CR><lt>x<BS><Del><Tab><Left>")
    assertEquals(
      Vector(VimKey.Typed('d'), VimKey.Typed('2'), VimKey.Typed('w'), VimKey.Esc, VimKey.Ctrl('r'),
        VimKey.Enter, VimKey.Typed('<'), VimKey.Typed('x'), VimKey.Backspace, VimKey.Delete, VimKey.Tab, VimKey.Left),
      keys)
    assertEquals("d2w<Esc><C-r><CR><lt>x<BS><Del><Tab><Left>", VimKey.render(keys))
    assertEquals(Vector(VimKey.Typed('<'), VimKey.Typed('a')), VimKey.parse("<a"))
  }

  // ---- motions ----

  @Test def wordMotions(): Unit = {
    check("|foo bar baz", "w", "foo |bar baz")
    check("|foo bar baz", "2w", "foo bar |baz")
    check("foo bar |baz", "b", "foo |bar baz")
    check("foo bar |baz", "3b", "|foo bar baz")
    check("|foo bar", "e", "fo|o bar")
    check("fo|o bar", "e", "foo ba|r")
    check("foo ba|r", "ge", "fo|o bar")
    check("|foo.bar baz", "w", "foo|.bar baz")
    check("|foo.bar baz", "W", "foo.bar |baz")
    check("foo.bar |baz", "B", "|foo.bar baz")
    check("|foo.bar baz", "E", "foo.ba|r baz")
    check("|abc\n\ndef", "w", "abc\n|\ndef")
    check("abc\n|\ndef", "w", "abc\n\n|def")
    check("abc\n\n|def", "b", "abc\n|\ndef")
    check("|abc\n  def", "w", "abc\n  |def")
    check("|last", "w", "las|t")
  }

  @Test def lineMotions(): Unit = {
    check("  fo|o bar", "0", "|  foo bar")
    check("  fo|o bar", "^", "  |foo bar")
    check("  f|oo bar", "$", "  foo ba|r")
    check("ab|c  \nx", "g_", "ab|c  \nx")
    check("a|bc  \nx", "g_", "ab|c  \nx")
    check("|abc\ndef\nghi", "j", "abc\n|def\nghi")
    check("|abc\ndef\nghi", "2j", "abc\ndef\n|ghi")
    check("|abc\ndef\nghi", "9j", "abc\ndef\n|ghi")
    check("abc\ndef\ngh|i", "k", "abc\nde|f\nghi")
    check("|abc\n  def\nghi", "G", "abc\n  def\n|ghi")
    check("abc\n  def\ngh|i", "gg", "ab|c\n  def\nghi")
    check("|abc\n  def\nghi", "2G", "abc\n|  def\nghi")
    check("abc\n  def\ngh|i", "2gg", "abc\n  |def\nghi")
    check("|abc\n  def", "+", "abc\n  |def")
    check("|abc\n  def", "<CR>", "abc\n  |def")
    check("abc\n  de|f", "-", "|abc\n  def")
    check("abc|d\nx\nabcdef", "jj", "abcd\nx\nabc|def")
    check("ab|c\nabcdef", "$j", "abc\nabcde|f")
    check("abc|def", "10l", "abcde|f")
    check("abc|def", "10h", "|abcdef")
    check("ab|c\ndef", "<Space>", "abc\n|def")
    check("abc\n|def", "<BS>", "ab|c\ndef")
  }

  @Test def findMotions(): Unit = {
    check("|a,b,c,d", "f,", "a|,b,c,d")
    check("|a,b,c,d", "f,;", "a,b|,c,d")
    check("|a,b,c,d", "2f,", "a,b|,c,d")
    check("|a,b,c,d", "f,;,", "a|,b,c,d")
    check("|a,b,c,d", "t,", "|a,b,c,d")
    check("|a,b,c,d", "t,;", "a,|b,c,d")
    check("a,b,c,|d", "F,", "a,b,c|,d")
    check("a,b,c,|d", "T,;", "a,b,|c,d")
    check("|abc", "fz", "|abc")
  }

  @Test def bracketAndParagraphMotions(): Unit = {
    check("|(a [b] c)", "%", "(a [b] c|)")
    check("(a [b] c|)", "%", "|(a [b] c)")
    check("x |(a)", "%", "x (a|)")
    check("(|a)", "%", "|(a)")
    check("|a\nb\n\nc", "}", "a\nb\n|\nc")
    check("a\n\nb\n|c", "{", "a\n|\nb\nc")
    check("|a\nb", "}", "a\n|b")
    check("|a\nb\nc\nd", "50%", "a\n|b\nc\nd")
  }

  @Test def screenMotionsUseTheVisibleLines(): Unit = {
    val (e, b) = setup("|l0\nl1\nl2\nl3\nl4\nl5\nl6")
    b.visible = (2, 6)
    type_(e, "H")
    assertEquals(2, VimText.lineOf(b.text, b.caret))
    type_(e, "L")
    assertEquals(6, VimText.lineOf(b.text, b.caret))
    type_(e, "M")
    assertEquals(4, VimText.lineOf(b.text, b.caret))
    type_(e, "<C-u>")
    assertEquals(2, VimText.lineOf(b.text, b.caret))
    type_(e, "zz")
    assertEquals(Some((2, VimScroll.Center)), b.scrolled)
  }

  // ---- operators ----

  @Test def deleteWithMotions(): Unit = {
    check("|foo bar", "dw", "|bar")
    check("foo |bar", "dw", "foo| ")
    check("foo |bar\nbaz", "dw", "foo| \nbaz")
    check("|a b c d", "d2w", "|c d")
    check("|a b c d", "2dw", "|c d")
    check("|foo bar", "de", "| bar")
    check("ab|cdef", "D", "a|b")
    check("ab|cdef", "d0", "|cdef")
    check("a|bcd", "x", "a|cd")
    check("a|bcd", "3x", "|a")
    check("ab|c", "X", "a|c")
    check("|abc", "dtc", "|c")
    check("|abc", "dfc", "|")
    check("a\nb\n|c\nd", "dk", "a\n|d")
    check("|a\nb\n\nc", "d}", "|\nc")
    check("x |a\nb\n\nc", "d}", "x| \n\nc")
  }

  @Test def deleteLines(): Unit = {
    check("a\n|b\nc", "dd", "a\n|c")
    check("a\nb\n|c", "dd", "a\n|b")
    check("|abc", "dd", "|")
    check("|a\nb\nc\nd", "3dd", "|d")
    check("a\n|b\nc", "5dd", "|a")
    check("a\n  |b\nc", "dj", "|a")
    check("|a\nb\nc", "dG", "|")
    check("a\nb\n|c", "dgg", "|")
  }

  @Test def changeWithMotions(): Unit = {
    check("|foo bar", "cwxyz<Esc>", "xy|z bar")
    check("fo|o bar", "cwX<Esc>", "fo|X bar")
    check("|foo bar baz", "c2wX<Esc>", "|X baz")
    check("ab|cdef", "Cxy<Esc>", "abx|y")
    check("a|bc", "sX<Esc>", "a|Xc")
    check("  a|bc", "Sxy<Esc>", "  x|y")
    check("  a|bc\nd", "ccx<Esc>", "  |x\nd")
    check("|foo(bar)", "ct(x<Esc>", "|x(bar)")
  }

  @Test def yankAndPut(): Unit = {
    check("|a\nb", "yyp", "a\n|a\nb")
    check("a\n|b", "yyP", "a\n|b\nb")
    check("|foo bar", "ywP", "foo| foo bar")
    check("|ab", "xp", "b|a")
    check("|a\nb", "ddp", "b\n|a")
    check("|ab", "yl3p", "aaa|ab")
    check("|a\nb", "yy2p", "a\n|a\na\nb")
    check("|ab", "ylgp", "aa|b")
    check("|a\nb", "yyjgp", "a\nb\n|a")
  }

  @Test def joinLines(): Unit = {
    check("|a\n  b", "J", "a| b")
    check("|a\nb\nc", "3J", "a b| c")
    check("|a\n  b", "gJ", "a|  b")
    check("|a \nb", "J", "a |b")
    check("|f(\n)", "J", "f(|)")
    check("|a\n", "J", "|a")
    check("|a", "J", "|a")
  }

  @Test def replaceAndCase(): Unit = {
    check("|abc", "rx", "|xbc")
    check("|abc", "3rx", "xx|x")
    check("|abc", "4rx", "|abc")
    check("a|bc", "r<CR>", "a\n|c")
    check("|abC", "~~~", "AB|c")
    check("|foo bar", "gUiw", "|FOO bar")
    check("|Foo Bar", "guu", "|foo bar")
    check("|ab", "g~~", "|AB")
    check("|ab", "g~g~", "|AB")
    check("|abc def", "gUe", "|ABC def")
  }

  @Test def shiftLines(): Unit = {
    check("|a\nb", ">>", "|  a\nb")
    check("    |a", "<<", "  |a")
    check("|a\nb\nc", "2>>", "|  a\n  b\nc")
    check("|a\nb", ">j", "|  a\n  b")
    check("|a\n\nb", ">2j", "|  a\n\n  b")
    check("|\ta", "<<", "      |a")
  }

  @Test def increment(): Unit = {
    check("x |9 y", "<C-a>", "x 1|0 y")
    check("|x -5", "3<C-a>", "x -|2")
    check("|a 7", "<C-x>", "a |6")
    check("abc|", "<C-a>", "ab|c")
  }

  /** Where the cursor lands, as nvim (with its default `nostartofline`)
    * puts it. */
  @Test def cursorFollowsNvim(): Unit = {
    // A linewise jump or delete keeps the column j and k aim for.
    check("ab|cd\nx\nwxyz", "G", "abcd\nx\nwx|yz")
    check("  ab|cd\n  xy\n  zz", "dd", "  x|y\n  zz")
    check("  ab|cd\n  xy\n  z", "Vjd", "  |z")
    check("  ab|cd\n  xy", ":2<CR>", "  abcd\n  x|y")
    // A shift keeps the column too, a tab measured to the next multiple of 8.
    check("  ab|cd\n  xy", ">>", "    |abcd\n  xy")
    check("  ab|cd\n  xy", "<<", "abc|d\n  xy")
    check("\ta|b", ">>", "         | ab")
    // An ex command that changes lines goes to the first non-blank.
    check("  ab|cd\n  xy", ":d<CR>", "  |xy")
    check("ab|cd\n  xy", ":><CR>", "  |abcd\n  xy")
    // A yank leaves the cursor at the start of what it yanked.
    check("  ab|cd\n  xy", "yy", "  ab|cd\n  xy")
    check("  abcd\n  x|y", "yk", "  a|bcd\n  xy")
    check("  abcd\n  x|y\n\nz", "yip", "|  abcd\n  xy\n\nz")
    check("  abcd\n  x|y", "ygg", "  a|bcd\n  xy")
    check("  ab|cd x", "yb", "  |abcd x")
    // A case change too; doubled, on the first non-blank.
    check("  ab|cd\n  xy", "gUU", "  |ABCD\n  xy")
    check("  abcd\n  x|y", "g~k", "  A|BCD\n  XY")
    check("  ab|cd\n  xy", "gUj", "  AB|CD\n  XY")
    // Over whole lines in visual mode: the cursor when it is the upper end,
    // the first line's start when it is not.
    check("  ab|cd\n  xy", "VjU", "|  ABCD\n  XY")
    check("  abcd\n  x|y", "VkU", "  A|BCD\n  XY")
    check("  ab|cd\n  xy", "Vjy", "|  abcd\n  xy")
    check("  abcd\n  x|y", "Vky", "  a|bcd\n  xy")
    // Undo returns the cursor to where the change first edited the text.
    check("  ab|cd\n  xy", "Ax<Esc>u", "  abc|d\n  xy")
    check("  ab|cd\n  xy", "A<CR>x<Esc>u", "  abc|d\n  xy")
    check("  ab|cd\n  xy", "ox<Esc>u", "  ab|cd\n  xy")
    check("  ab|cd\n  xy\n  z", "2ddu", "  ab|cd\n  xy\n  z")
  }

  // ---- text objects ----

  @Test def wordObjects(): Unit = {
    check("foo b|ar baz", "diw", "foo | baz")
    check("foo b|ar baz", "daw", "foo |baz")
    check("foo b|ar", "daw", "fo|o")
    check("foo b|ar baz", "ciwX<Esc>", "foo |X baz")
    check("a.b c|.d e", "diW", "a.b | e")
    check("foo |  bar", "daw", "fo|o")
    check("|a b c", "d3aw", "|")
  }

  @Test def quoteObjects(): Unit = {
    check("x = 'a|bc' y", "ci'Z<Esc>", "x = '|Z' y")
    check("x = \"a|bc\" y", "da\"", "x = |y")
    check("x = \"a|bc\"", "da\"", "x |=")
    check("|x = 'abc'", "di'", "x = '|'")
    check("x = 'abc'|", "yi'P", "x = 'ab|cabc'")
  }

  @Test def bracketObjects(): Unit = {
    check("f(a, |b)", "di(", "f(|)")
    check("f(a, |b)", "dib", "f(|)")
    check("f(a, |b)", "da(", "|f")
    check("f(a, |b)", "ci)x<Esc>", "f(|x)")
    check("f(|a, b)", "yi(P", "f(a, |ba, b)")
    check("((a|))", "d2i(", "(|)")
    check("x[|1]", "di]", "x[|]")
    check("{ |a }", "da{", "|")
    check("f(\n  a,\n  |b\n)", "di(", "f(\n|)")
    check("f(\n  a,\n  |b\n)", "ci(x<Esc>", "f(\n  |x\n)")
    check("|abc", "di(", "|abc")
  }

  @Test def paragraphObjects(): Unit = {
    check("a\n|b\n\nc", "dip", "|\nc")
    check("a\n|b\n\nc", "dap", "|c")
    check("a\n\nb\n|c", "dap", "|a")
    check("a\n|b\n\nc", "yipP", "|a\nb\na\nb\n\nc")
  }

  // ---- visual mode ----

  @Test def visualOperators(): Unit = {
    check("|abcdef", "vlld", "|def")
    check("a|bc\ndef", "vjd", "a|f")
    check("|a\nb\nc", "Vjd", "|c")
    check("foo b|ar baz", "viwcX<Esc>", "foo |X baz")
    check("|a\nb", "Vj>", "|  a\n  b")
    check("|a\nb", "Vj3>", "|      a\n      b")
    check("ab|cd", "vlohd", "|a")
    check("|abc", "vlU", "|ABc")
    check("|ABC", "vlu", "|abC")
    check("|abc", "vl~", "|ABc")
    check("|abc", "vlrx", "|xxc")
    check("|a\nb\nc", "VjjJ", "a b| c")
    check("|abc", "vl<Esc>gvd", "|c")
    check("|abc", "vly$p", "abca|b")
    check("|abc", "vlyP", "a|babc")
    check("|a\nb", "VyjVp", "a\n|a")
    check("|abc def", "vex", "| def")
    check("ab|c", "vX", "|")
  }

  @Test def visualPutSwapsTheRegister(): Unit = {
    check("|foo bar", "yiwwviwp", "foo fo|o")
    check("|foo bar", "yiwwviwp0viwp", "ba|r foo")
    check("|foo bar", "yiwwviwP0viwP", "fo|o foo")
  }

  @Test def visualSelectionIsReported(): Unit = {
    val (e, b) = setup("|abc\ndef")
    type_(e, "vl")
    assertEquals(VimMode.Visual, e.mode)
    assertEquals(Some((0, 2)), e.selection)
    assertEquals((0, 2), (b.anchor, b.caret))
    type_(e, "o")
    assertEquals((2, 0), (b.anchor, b.caret))
    type_(e, "V")
    assertEquals(VimMode.VisualLine, e.mode)
    assertEquals(Some((0, 3)), e.selection)
    type_(e, "j")
    assertEquals(Some((0, 7)), e.selection)
    assertEquals("2", e.pendingKeys)
    assertTrue(e.handleKey(VimKey.Esc))
    assertEquals(VimMode.Normal, e.mode)
    assertEquals(None, e.selection)
  }

  @Test def visualObjectsExtendTheSelection(): Unit = {
    val (e, _) = setup("foo b|ar baz")
    type_(e, "viw")
    assertEquals(Some((4, 7)), e.selection)
    val (e2, _) = setup("a\n|b\n\nc")
    type_(e2, "vip")
    assertEquals(VimMode.VisualLine, e2.mode)
    assertEquals(Some((0, 3)), e2.selection)
  }

  // ---- insert mode ----

  @Test def insertCommands(): Unit = {
    check("|bc", "ia<Esc>", "|abc")
    check("a|c", "ab<Esc>", "ac|b")
    check("  |x", "Ia<Esc>", "  |ax")
    check("  |x", "gIa<Esc>", "|a  x")
    check("|ab", "Ac<Esc>", "ab|c")
    check("|a", "ob<Esc>", "a\n|b")
    check("|a", "Ob<Esc>", "|b\na")
    check("|", "ix<Esc>", "|x")
  }

  @Test def autoindent(): Unit = {
    check("  |a", "ob<Esc>", "  a\n  |b")
    check("  |a", "o<Esc>", "  a\n|")
    check("  |a", "Ob<Esc>", "  |b\n  a")
    check("  |a", "A<CR>b<Esc>", "  a\n  |b")
    check("  |a", "A<CR><CR>b<Esc>", "  a\n\n  |b")
  }

  @Test def insertKeys(): Unit = {
    check("|", "iab<BS>c<Esc>", "a|c")
    check("|", "ifoo bar<C-w>x<Esc>", "foo |x")
    check("|", "ifoo.bar<C-w><Esc>", "foo|.")
    check("|", "i  foo<C-u>x<Esc>", "  |x")
    check("|", "i<Tab>x<Esc>", "  |x")
    check("|", "ix<Tab>y<Esc>", "x |y")
    check("|foo", "yiwA <C-r>\"<Esc>", "foo fo|o")
    check("|a", "A<C-t>b<Esc>", "  a|b")
    check("  |a", "A<C-d><Esc>", "|a")
    check("|ab", "i<Del><Esc>", "|b")
    check("|ab", "a<Right>x<Esc>", "ab|x")
  }

  @Test def insertWithCount(): Unit = {
    check("|x", "3ia<Esc>", "aa|ax")
    check("|x", "2ob<Esc>", "x\nb\n|b")
    check("|x", "3A!<Esc>", "x!!|!")
  }

  @Test def replaceMode(): Unit = {
    check("|abc", "Rxy<Esc>", "x|yc")
    check("|abc", "Rxyzw<Esc>", "xyz|w")
    check("|abc", "Rxy<BS><BS><Esc>", "|abc")
  }

  @Test def modeLine(): Unit = {
    val (e, _) = setup("|abc")
    assertEquals("", e.statusText)
    type_(e, "i")
    assertEquals(VimMode.Insert, e.mode)
    assertEquals("-- INSERT --", e.statusText)
    type_(e, "<Esc>v")
    assertEquals("-- VISUAL --", e.statusText)
    type_(e, "<Esc>V")
    assertEquals("-- VISUAL LINE --", e.statusText)
    type_(e, "<Esc>R")
    assertEquals("-- REPLACE --", e.statusText)
    type_(e, "<Esc>2d")
    assertEquals("2d", e.pendingKeys)
    assertTrue(e.isOperatorPending)
    type_(e, "<Esc>qa")
    assertEquals("recording @a", e.statusText)
    type_(e, "i")
    assertEquals("-- INSERT -- recording @a", e.statusText)
  }

  // ---- undo, redo and repeat ----

  @Test def undoRedo(): Unit = {
    check("|abc", "xxu", "|bc")
    check("|abc", "xxuu", "|abc")
    check("|abc", "xxuu<C-r>", "|bc")
    check("|abc", "xx2u", "|abc")
    check("|", "ifoo<Esc>u", "|")
    check("|foo bar", "cwxy<Esc>u", "|foo bar")
    check("|ab", "iX<Left>Y<Esc>u", "|Xab")
    check("|a\nb\nc", "Jjxuu", "|a\nb\nc")
    check("|ab\nab", ":%s/a/x/<CR>u", "|ab\nab")
    check("|abc", "u", "|abc")
  }

  @Test def repeatLastChange(): Unit = {
    check("|a b c", "x.", "|b c")
    check("|a\nb\nc", "dd.", "|c")
    check("|foo foo", "cwbar<Esc>w.", "bar ba|r")
    check("|a\nb", "A;<Esc>j.", "a;\nb|;")
    check("|abcdef", "x3.", "|ef")
    check("|abcdef", "vld.", "|ef")
    check("|a\nb\nc\nd", "Vjd.", "|")
    check("|ab", "3ix<Esc>u", "|ab")
    check("|foo bar", "d/bar<CR>u.", "|bar")
    check("|a a a", "rbw.w.", "b b |b")
    check("|x", "ia<Esc>.", "|aax")
    check("|abc def", "gUiww.", "ABC |DEF")
    check("|a\nb", ">>j.", "  a\n|  b")
  }

  // ---- registers ----

  @Test def registers(): Unit = {
    check("|foo bar", "\"ayiww\"byiw0\"bP", "ba|rfoo bar")
    check("|foo bar", "\"ayiww\"Ayiw$\"ap", "foo barfooba|r")
    check("|a b", "yiww\"_dwP", "a|a ")
    check("|a\nb\nc", "dddd\"2p", "c\n|a")
    check("|ab", "x\"-p", "b|a")
    check("|foo", "yiw\"_x\"0P", "fo|ooo")
  }

  @Test def clipboardRegister(): Unit = {
    val (e, b) = setup("|foo\nbar")
    type_(e, "\"+yy")
    assertEquals(Some("foo\n"), b.clip)
    b.clip = Some("X")
    type_(e, "j\"+p")
    assertEquals("foo\nb|Xar", b.shown)
  }

  // ---- search ----

  @Test def search(): Unit = {
    check("|foo bar foo", "/foo<CR>", "foo bar |foo")
    check("|foo bar foo", "/foo<CR>n", "|foo bar foo")
    check("foo bar |foo", "?bar<CR>", "foo |bar foo")
    check("|foo bar foo", "/foo<CR>N", "|foo bar foo")
    check("|foo bar foo", "*", "foo bar |foo")
    check("foo bar |foo", "#", "|foo bar foo")
    check("|foo foobar foo", "*", "foo foobar |foo")
    check("|foo bar", "d/bar<CR>", "|bar")
    check("|ab abc", "/b.<Esc>", "|ab abc")
    check("|ab abc", "/b.<CR>", "a|b abc")
    check("|bar abar", "/\\<bar<CR>", "|bar abar")
    check("|count(x) count(*)", "/count(*<CR>", "count(x) |count(*)")
    check("|SELECT select", "/select\\c<CR>", "SELECT |select")
    check("|a b", "/b<CR>/<CR>", "a |b")
    check("|abc", "/zz<CR>", "|abc")
  }

  @Test def searchMessages(): Unit = {
    val (e, _) = setup("|foo bar foo")
    type_(e, "/foo<CR>")
    assertEquals("/foo", e.statusText)
    type_(e, "n")
    assertEquals("search hit BOTTOM, continuing at TOP", e.statusText)
    type_(e, "/zz<CR>")
    assertEquals("E486: Pattern not found: zz", e.statusText)
    assertTrue(e.statusIsError)
    type_(e, "/q")
    assertEquals(VimMode.CommandLine, e.mode)
    assertEquals("/q", e.statusText)
    type_(e, "<BS><BS>")
    assertEquals(VimMode.Normal, e.mode)
  }

  @Test def searchExtendsAVisualSelection(): Unit = {
    val (e, _) = setup("|abc xyz")
    type_(e, "v/y<CR>")
    assertEquals(VimMode.Visual, e.mode)
    assertEquals(Some((0, 6)), e.selection)
  }

  // ---- ex commands ----

  @Test def exGotoAndRanges(): Unit = {
    check("|a\nb\n  c", ":3<CR>", "a\nb\n|  c")
    check("|a\nb\nc", ":$<CR>", "a\nb\n|c")
    check("|a\nb\nc\nd", ":2,3d<CR>", "a\n|d")
    check("a\n|b\nc", ":.+1d<CR>", "a\n|b")
    check("|a\nb\nc", ":%d<CR>", "|")
    check("|a\nb\nc", ":2,3j<CR>", "a\n|b c")
    check("|a\nb", ":2>\n", "a\n  |b")
    check("|a\nb", ":%y<CR>P", "|a\nb\na\nb")
  }

  @Test def substitute(): Unit = {
    check("|aaa\naaa", ":s/a/b/<CR>", "|baa\naaa")
    check("|aaa\naaa", ":s/a/b/g<CR>", "|bbb\naaa")
    check("|aaa\naaa", ":%s/a/b/g<CR>", "aaa\n|bbb".replace("aaa\n", "bbb\n"))
    check("|x1 x2", ":s/x\\(\\d\\)/[\\1]/g<CR>", "|[1] [2]")
    check("|abc", ":s/b/<&>/<CR>", "|a<b>c")
    check("|a,b", ":s/,/\\r/<CR>", "a\n|b")
    check("|ab\nab", ":s/a/x/<CR>j&", "xb\n|xb")
    check("|ab\nab", ":s/a/x/<CR>j:s<CR>", "xb\n|xb")
    check("|Foo", ":s/foo/bar/i<CR>", "|bar")
    check("|a/b", ":s#/#-#<CR>", "|a-b")
    check("|a/b", ":s/\\//-/<CR>", "|a-b")
    check("|abc", ":s/z/y/<CR>", "|abc")
  }

  @Test def substituteOverAVisualRange(): Unit = {
    val (e, b) = setup("|a\na\na")
    type_(e, "Vj:")
    assertEquals(":'<,'>", e.statusText)
    type_(e, "s/a/b/<CR>")
    assertEquals("b\n|b\na", b.shown)
  }

  @Test def exMessages(): Unit = {
    val (e, _) = setup("|aaa")
    type_(e, ":%s/a/b/g<CR>")
    assertEquals("3 substitutions on 1 line", e.statusText)
    type_(e, ":nope<CR>")
    assertEquals("E492: Not an editor command: nope", e.statusText)
    type_(e, ":5d<CR>")
    assertEquals("E16: Invalid range", e.statusText)
    type_(e, "@:")
    assertEquals("E16: Invalid range", e.statusText)
  }

  @Test def writeAndQuit(): Unit = {
    val (e, b) = setup("|a")
    type_(e, ":w<CR>")
    assertEquals(1, b.writes)
    type_(e, ":q<CR>")
    assertEquals(List(false), b.quits)
    type_(e, ":q!<CR>")
    assertEquals(List(true, false), b.quits)
    type_(e, ":wq<CR>")
    assertEquals(2, b.writes)
    type_(e, "ZZ")
    assertEquals(3, b.writes)
    type_(e, "ZQ")
    assertEquals(List(true, true, true, true, false), b.quits)
    b.writeResult = Some("E32: No file name")
    type_(e, ":x<CR>")
    assertEquals("E32: No file name", e.statusText)
    assertEquals(4, b.writes)
    assertEquals(5, b.quits.length)
    b.quitResult = Some("E37: No write since last change (add ! to override)")
    type_(e, ":q<CR>")
    assertEquals("E37: No write since last change (add ! to override)", e.statusText)
  }

  // ---- macros ----

  @Test def macros(): Unit = {
    check("|a\nb\nc", "qaA!<Esc>jq@a@@", "a!\nb!\nc|!")
    check("|a\nb\nc\nd", "qaA!<Esc>jq2@a", "a!\nb!\nc!\n|d")
    check("|1 2 3", "qq<C-a>wq@q@q", "2 3 |4")
    check("|a\nb", "qaxjq3@a", "\n|")
  }

  @Test def aFailingCommandStopsAMacro(): Unit = {
    check("|a,b\nc", "qaf,xq@a", "a|b\nc")
  }

  // ---- read-only text ----

  @Test def readOnlyTextMovesAndYanksButDoesNotChange(): Unit = {
    val (e, b) = setup("|foo bar")
    b.editable = false
    type_(e, "x")
    assertEquals("|foo bar", b.shown)
    assertEquals("E21: Cannot make changes, 'modifiable' is off", e.statusText)
    type_(e, "wyiw")
    assertEquals("foo |bar", b.shown)
    type_(e, "i")
    assertEquals(VimMode.Normal, e.mode)
    type_(e, "vl")
    assertEquals(Some((4, 6)), e.selection)
    assertEquals(Some("ba"), e.copySelection())
    type_(e, ":s/o/0/<CR>")
    assertEquals("|foo bar".replace("|", "") , b.text)
  }

  // ---- the host's own actions ----

  @Test def escapeWithNothingToCancelIsTheHosts(): Unit = {
    val (e, _) = setup("|abc")
    assertFalse(e.handleKey(VimKey.Esc))
    type_(e, "d")
    assertTrue(e.handleKey(VimKey.Esc))
    type_(e, "i")
    assertTrue(e.handleKey(VimKey.Esc))
  }

  @Test def aCaretTheHostMovesIsTheCursor(): Unit = {
    val (e, b) = setup("|abc def")
    b.caret = 5
    type_(e, "x")
    assertEquals("abc d|f", b.shown)
    type_(e, "v")
    b.caret = 0
    type_(e, "x")
    assertEquals(VimMode.Normal, e.mode)
    assertEquals("|bc df", b.shown)
  }

  @Test def anEditTheHostMakesIsUndone(): Unit = {
    val (e, b) = setup("|abc")
    b.replace(0, 0, "X")
    e.noteExternalEdit(0, "", "X")
    type_(e, "u")
    assertEquals("abc", b.text)
  }

  @Test def hostShortcuts(): Unit = {
    val (e, b) = setup("|foo bar")
    e.paste("X")
    assertEquals("X|foo bar", b.shown)
    e.undo()
    assertEquals("foo bar", b.text)
    e.redo()
    assertEquals("Xfoo bar", b.text)
    e.undo()
    type_(e, "wviw")
    e.paste("Z")
    assertEquals("foo |Z", b.shown)
    type_(e, "A")
    e.paste("12")
    type_(e, "<Esc>")
    assertEquals("foo Z1|2", b.shown)
    e.selectAll()
    assertEquals(Some((0, 7)), e.selection)
    assertEquals(Some("foo Z12"), e.cutSelection().map(_.take(7)))
    assertEquals("", b.text)
    e.undo()
    assertEquals("foo Z12", b.text)
    e.adoptSelection(0, 3)
    assertEquals(Some((0, 3)), e.selection)
    e.adoptSelection(3, 0)
    assertEquals(Some((0, 3)), e.selection)
    assertEquals((3, 0), (b.anchor, b.caret))
  }

  @Test def undoFromInsertModeKeepsInserting(): Unit = {
    val (e, b) = setup("|")
    type_(e, "iabc")
    e.undo()
    assertEquals("", b.text)
    assertEquals(VimMode.Insert, e.mode)
    type_(e, "x<Esc>")
    assertEquals("|x", b.shown)
  }

  @Test def resetForgetsTheHistory(): Unit = {
    val (e, b) = setup("|abc")
    type_(e, "x")
    b.text = "new text"
    b.caret = 0
    e.reset()
    type_(e, "u")
    assertEquals("new text", b.text)
    assertEquals("Already at oldest change", e.statusText)
  }

  @Test def aTrailingLineBreakEndsWithAnEmptyLine(): Unit = {
    check("|a\nb\n", "G", "a\nb\n|")
    check("|a\nb\n", "Gdd", "a\n|b")
    check("|a\n", "dw", "|\n")
  }

  @Test def diffFindsTheChangedMiddle(): Unit = {
    assertEquals(None, VimEngine.diff("abc", "abc"))
    assertEquals(Some((1, "b", "XY")), VimEngine.diff("abc", "aXYc"))
    assertEquals(Some((0, "", "a")), VimEngine.diff("", "a"))
    assertEquals(Some((2, "a", "")), VimEngine.diff("aaa", "aa"))
  }
}
