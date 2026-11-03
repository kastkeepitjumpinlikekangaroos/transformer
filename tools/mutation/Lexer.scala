package com.transformer.tools.mutation

/** One token of a Scala source: its kind, where it starts and ends (offsets
  * into the source, the end exclusive), its text and the line it starts on,
  * counting from 1. */
final case class Token(kind: Token.Kind, start: Int, end: Int, text: String, line: Int) {
  def is(kind: Token.Kind, text: String): Boolean = this.kind == kind && this.text == text
}

object Token {
  sealed trait Kind
  /** A plain identifier or a reserved word. */
  case object Ident extends Kind
  /** A name in backquotes. */
  case object Backquoted extends Kind
  /** A run of operator characters, such as `<=`, `=>` or `!`. */
  case object Operator extends Kind
  case object IntLit extends Kind
  case object FloatLit extends Kind
  /** A string literal, plain, triple-quoted or interpolated, with its
    * interpolator's name and every `${...}` inside it. */
  case object StringLit extends Kind
  case object CharLit extends Kind
  /** A line comment or a block comment, nested ones included. */
  case object Comment extends Kind
  /** One of `( ) [ ] { } , ; .` */
  case object Delimiter extends Kind

  val ReservedWords: Set[String] = Set(
    "abstract", "case", "catch", "class", "def", "do", "else", "extends", "false", "final",
    "finally", "for", "forSome", "if", "implicit", "import", "lazy", "macro", "match", "new",
    "null", "object", "override", "package", "private", "protected", "return", "sealed", "super",
    "this", "throw", "trait", "try", "true", "type", "val", "var", "while", "with", "yield")
}

/** The lexer the mutator reads a source with. It knows enough of Scala 2.13's
  * lexical syntax to tell code from what is not code: string literals (plain,
  * triple-quoted and interpolated, an interpolation's `${...}` with nested
  * braces and strings counted as part of its string), character literals such
  * as `'<'`, line comments and nested block comments, and backquoted names.
  * Whitespace is not a token. A source it cannot read to the end, such as an
  * unclosed string, fails. */
object Lexer {
  def tokens(src: String): Vector[Token] = new Scan(src).all()

  private val OperatorChars = "!#%&*+-/:<=>?@\\^|~"

  def isOperatorChar(c: Char): Boolean =
    OperatorChars.indexOf(c.toInt) >= 0 || {
      val t = Character.getType(c)
      t == Character.MATH_SYMBOL || t == Character.OTHER_SYMBOL
    }

  private def isIdentStart(c: Char): Boolean = Character.isLetter(c) || c == '_' || c == '$'
  private def isIdentPart(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_' || c == '$'

  private final class Scan(src: String) {
    private val n = src.length
    private val lineStarts: Array[Int] = {
      val b = Array.newBuilder[Int]
      b += 0
      var i = 0
      while (i < n) {
        if (src.charAt(i) == '\n') b += i + 1
        i += 1
      }
      b.result()
    }

    private def lineOf(offset: Int): Int = {
      val i = java.util.Arrays.binarySearch(lineStarts, offset)
      if (i >= 0) i + 1 else -i - 1
    }

    private def at(i: Int): Char = if (i < n) src.charAt(i) else '\u0000'

    private def fail(i: Int, what: String): Nothing =
      throw new IllegalArgumentException(s"line ${lineOf(i)}: $what")

    def all(): Vector[Token] = {
      val out = Vector.newBuilder[Token]
      var i = 0
      while (i < n) {
        val c = src.charAt(i)
        if (Character.isWhitespace(c)) i += 1
        else {
          val (kind, end) = next(i)
          out += Token(kind, i, end, src.substring(i, end), lineOf(i))
          i = end
        }
      }
      out.result()
    }

    /** The token that starts at `i`: its kind and where it ends. */
    private def next(i: Int): (Token.Kind, Int) = {
      val c = src.charAt(i)
      if (c == '/' && at(i + 1) == '/') (Token.Comment, lineCommentEnd(i))
      else if (c == '/' && at(i + 1) == '*') (Token.Comment, blockCommentEnd(i))
      else if (c == '"') (Token.StringLit, stringEnd(i, interpolated = false))
      else if (c == '`') {
        val close = src.indexOf('`', i + 1)
        if (close < 0) fail(i, "an unclosed backquote")
        (Token.Backquoted, close + 1)
      } else if (c == '\'') charLiteral(i)
      else if (Character.isDigit(c)) number(i)
      else if (isIdentStart(c)) {
        val end = identEnd(i)
        if (at(end) == '"') (Token.StringLit, stringEnd(end, interpolated = true))
        else (Token.Ident, end)
      } else if ("()[]{},;.".indexOf(c.toInt) >= 0) (Token.Delimiter, i + 1)
      else if (isOperatorChar(c)) (Token.Operator, operatorEnd(i))
      else fail(i, s"a character the lexer does not know, U+${Integer.toHexString(c.toInt)}")
    }

    private def lineCommentEnd(i: Int): Int = {
      val nl = src.indexOf('\n', i)
      if (nl < 0) n else nl
    }

    private def blockCommentEnd(start: Int): Int = {
      var depth = 0
      var i = start
      while (i < n) {
        if (src.charAt(i) == '/' && at(i + 1) == '*') { depth += 1; i += 2 }
        else if (src.charAt(i) == '*' && at(i + 1) == '/') {
          depth -= 1
          i += 2
          if (depth == 0) return i
        } else i += 1
      }
      fail(start, "an unclosed block comment")
    }

    /** An identifier, with the operator characters that may follow its last
      * underscore (`unary_!`, `_*`). */
    private def identEnd(start: Int): Int = {
      var i = start + 1
      while (i < n && isIdentPart(src.charAt(i))) i += 1
      if (src.charAt(i - 1) == '_') while (i < n && isOperatorChar(src.charAt(i)) && !commentStarts(i)) i += 1
      i
    }

    private def commentStarts(i: Int): Boolean = src.charAt(i) == '/' && (at(i + 1) == '/' || at(i + 1) == '*')

    private def operatorEnd(start: Int): Int = {
      var i = start + 1
      while (i < n && isOperatorChar(src.charAt(i)) && !commentStarts(i)) i += 1
      i
    }

    /** A decimal or hexadecimal number, with its fraction, exponent and
      * suffix. `1.max(2)` is the number `1`, then a `.`. */
    private def number(start: Int): (Token.Kind, Int) = {
      if (src.charAt(start) == '0' && (at(start + 1) == 'x' || at(start + 1) == 'X')) {
        var i = start + 2
        while (i < n && (Character.digit(src.charAt(i), 16) >= 0 || src.charAt(i) == '_')) i += 1
        if (at(i) == 'L' || at(i) == 'l') i += 1
        return (Token.IntLit, i)
      }
      var i = start
      def digits(): Unit = while (i < n && (Character.isDigit(src.charAt(i)) || src.charAt(i) == '_')) i += 1
      digits()
      var float = false
      if (at(i) == '.' && Character.isDigit(at(i + 1))) { float = true; i += 1; digits() }
      if (at(i) == 'e' || at(i) == 'E') {
        val sign = if (at(i + 1) == '+' || at(i + 1) == '-') 1 else 0
        if (Character.isDigit(at(i + 1 + sign))) { float = true; i += 1 + sign; digits() }
      }
      if ("fFdD".indexOf(at(i).toInt) >= 0) { float = true; i += 1 }
      else if (!float && (at(i) == 'L' || at(i) == 'l')) i += 1
      (if (float) Token.FloatLit else Token.IntLit, i)
    }

    /** `'x'`, `'\n'`, `'A'`; a quote that opens none of these is an
      * operator character (a symbol literal, which this code base does not
      * write). */
    private def charLiteral(start: Int): (Token.Kind, Int) =
      if (at(start + 1) == '\\') {
        val close = src.indexOf('\'', start + 3)
        if (close < 0 || close - start > 8) fail(start, "an unclosed character literal")
        (Token.CharLit, close + 1)
      } else if (at(start + 2) == '\'' && at(start + 1) != '\n') (Token.CharLit, start + 3)
      else (Token.Operator, start + 1)

    /** A string literal whose opening quote is at `quote`. In an interpolated
      * one, `$$` is a dollar and `${` opens code that runs to its matching
      * brace, with strings, characters and comments of its own. */
    private def stringEnd(quote: Int, interpolated: Boolean): Int = {
      val triple = at(quote + 1) == '"' && at(quote + 2) == '"'
      var i = if (triple) quote + 3 else quote + 1
      while (i < n) {
        val c = src.charAt(i)
        if (triple && c == '"' && at(i + 1) == '"' && at(i + 2) == '"') {
          i += 3
          while (at(i) == '"') i += 1
          return i
        } else if (!triple && c == '"') return i + 1
        else if (!triple && c == '\n') fail(quote, "a string literal that runs past its line")
        else if (!triple && c == '\\') i += 2
        else if (interpolated && c == '$' && at(i + 1) == '{') i = codeEnd(i + 2)
        else if (interpolated && c == '$' && at(i + 1) == '$') i += 2
        else i += 1
      }
      fail(quote, "an unclosed string literal")
    }

    /** The end of an interpolation's code, which opened before `start`: just
      * past its matching closing brace. */
    private def codeEnd(start: Int): Int = {
      var depth = 1
      var i = start
      while (i < n) {
        val c = src.charAt(i)
        if (Character.isWhitespace(c)) i += 1
        else if (c == '{') { depth += 1; i += 1 }
        else if (c == '}') {
          depth -= 1
          i += 1
          if (depth == 0) return i
        } else i = next(i)._2
      }
      fail(start, "an unclosed interpolation")
    }
  }
}
