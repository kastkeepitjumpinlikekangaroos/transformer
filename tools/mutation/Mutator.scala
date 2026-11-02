package com.transformer.tools.mutation

import scala.collection.mutable

/** One mutant of a source: the text from `start` to `end` (offsets, the end
  * exclusive) becomes `replacement`. `line` and `column` count from 1 and
  * place the start. `operator` names the row of [[Mutator.Operators]]. */
final case class Site(
    operator: String,
    start: Int,
    end: Int,
    original: String,
    replacement: String,
    line: Int,
    column: Int) {

  def applyTo(src: String): String = src.substring(0, start) + replacement + src.substring(end)

  /** The site in one line: where it is, the operator and the change. */
  def describe: String = s"$line:$column $operator ${Site.shown(original)} -> ${Site.shown(replacement)}"
}

object Site {
  /** A site's text on one line, its whitespace runs as one space. */
  def shown(text: String): String = text.trim.replaceAll("\\s+", " ")
}

/** Lists every site of each mutation operator in a Scala source, from the
  * tokens of [[Lexer]], so that nothing inside a string, a character literal or
  * a comment ever changes. Nothing inside `[...]` changes either (in Scala that
  * is always a type), nor an annotation's arguments, nor an import.
  *
  * | operator   | mutation |
  * |------------|----------|
  * | relational | `<` and `<=`, `>` and `>=`, `==` and `!=` swapped |
  * | logical    | `&&` and `||` swapped; a prefix `!` dropped |
  * | arithmetic | binary `+` and `-`, `*` and `/` swapped (not a `+` beside a string literal); `+ 1` to `+ 0`, `- 1` to `- 0`, likewise after `+=` and `-=` |
  * | constant   | `0` and `1` swapped (`L` kept), `true` and `false` swapped |
  * | extremum   | `math.max` and `math.min` swapped, `Math`'s too |
  * | checked    | `Math.addExact(a, b)` to `(a + b)`, and each other `*Exact` to its plain operation |
  * | statement  | a call that is a statement of its own line deleted |
  * | refusal    | a `throw` that is the body of an `if` with no `else` to `()` |
  *
  * An operator is binary only with an operand on each side, the left one on
  * its own line: a `-` that starts a line starts a new statement in Scala 2.
  */
object Mutator {
  val Operators: Vector[String] =
    Vector("relational", "logical", "arithmetic", "constant", "extremum", "checked", "statement", "refusal")

  private val Relational = Map("<" -> "<=", "<=" -> "<", ">" -> ">=", ">=" -> ">", "==" -> "!=", "!=" -> "==")
  private val Logical = Map("&&" -> "||", "||" -> "&&")
  private val Arithmetic = Map("+" -> "-", "-" -> "+", "*" -> "/", "/" -> "*")
  private val Constants = Map(
    "0" -> "1", "1" -> "0", "0L" -> "1L", "1L" -> "0L", "0l" -> "1l", "1l" -> "0l",
    "true" -> "false", "false" -> "true")
  private val Ones = Set("1", "1L", "1l")

  /** Each checked method of `java.lang.Math`, with its arity, to its plain
    * operation's text over its arguments' text. */
  private val Checked: Map[String, (Int, Seq[String] => String)] = Map(
    "addExact" -> ((2, a => s"(${a(0)} + ${a(1)})")),
    "subtractExact" -> ((2, a => s"(${a(0)} - ${a(1)})")),
    "multiplyExact" -> ((2, a => s"(${a(0)} * ${a(1)})")),
    "divideExact" -> ((2, a => s"(${a(0)} / ${a(1)})")),
    "negateExact" -> ((1, a => s"(-(${a(0)}))")),
    "incrementExact" -> ((1, a => s"(${a(0)} + 1)")),
    "decrementExact" -> ((1, a => s"(${a(0)} - 1)")),
    "toIntExact" -> ((1, a => s"(${a(0)}).toInt")),
    "absExact" -> ((1, a => s"math.abs(${a(0)})")))

  /** Reserved words that can be an operand, or start one. */
  private val ValueWords = Set("this", "super", "null", "true", "false")
  private val OperandStartWords = ValueWords ++ Set("new", "if", "try")
  private val HeaderWords = Set("if", "while", "for")

  /** Words after which a line goes on to the next: a statement on that next
    * line is the body of what they open, not a statement of the block. */
  private val OpeningWords =
    Set("if", "else", "while", "for", "do", "try", "finally", "yield", "return", "throw", "new", "with",
      "extends", "match", "catch", "case", "val", "var", "def", "lazy", "implicit")

  def sites(src: String): Vector[Site] = new Sites(src).all()

  private final class Sites(src: String) {
    private val code: Vector[Token] = Lexer.tokens(src).filter(_.kind != Token.Comment)
    private val n = code.length
    private val firstOnLine: Map[Int, Int] = code.indices.groupBy(code(_).line).map { case (l, is) => l -> is.min }
    private val lastOnLine: Map[Int, Int] = code.indices.groupBy(code(_).line).map { case (l, is) => l -> is.max }
    private val matching: Array[Int] = matchDelimiters()
    private val skipped: Array[Boolean] = skippedTokens()

    private def tok(i: Int): Option[Token] = if (i >= 0 && i < n) Some(code(i)) else None
    private def isDelim(i: Int, text: String): Boolean = tok(i).exists(_.is(Token.Delimiter, text))
    private def isIdent(i: Int, text: String): Boolean = tok(i).exists(_.is(Token.Ident, text))
    private def isWord(t: Token): Boolean = t.kind == Token.Ident && Token.ReservedWords(t.text)

    private def column(offset: Int): Int = offset - src.lastIndexOf('\n', offset - 1)
    private def indent(line: Int): Int = {
      val first = firstOnLine(line)
      column(code(first).start)
    }
    private def site(operator: String, from: Int, to: Int, replacement: String): Site = {
      val start = code(from).start
      val end = code(to).end
      Site(operator, start, end, src.substring(start, end), replacement, code(from).line, column(start))
    }

    def all(): Vector[Site] = {
      val out = Vector.newBuilder[Site]
      var i = 0
      while (i < n) {
        if (!skipped(i)) out ++= at(i)
        i += 1
      }
      out.result().sortBy(s => (s.start, Operators.indexOf(s.operator)))
    }

    /** Every site that starts at token `i`. */
    private def at(i: Int): Seq[Site] = {
      val t = code(i)
      val found = mutable.ArrayBuffer.empty[Site]
      t.kind match {
        case Token.Operator =>
          if (Relational.contains(t.text) && binary(i)) found += site("relational", i, i, Relational(t.text))
          if (Logical.contains(t.text) && binary(i)) found += site("logical", i, i, Logical(t.text))
          if (t.text == "!" && prefix(i)) found += site("logical", i, i, "")
          if (Arithmetic.contains(t.text) && binary(i) && !besideString(i))
            found += site("arithmetic", i, i, Arithmetic(t.text))
        case Token.IntLit if Constants.contains(t.text) =>
          if (Ones(t.text) && offByOne(i)) found += site("arithmetic", i, i, Constants(t.text))
          else found += site("constant", i, i, Constants(t.text))
        case Token.Ident =>
          if (t.text == "true" || t.text == "false") found += site("constant", i, i, Constants(t.text))
          if ((t.text == "max" || t.text == "min") && isDelim(i - 1, ".") && isDelim(i + 1, "(") &&
            (isIdent(i - 2, "math") || isIdent(i - 2, "Math")))
            found += site("extremum", i, i, if (t.text == "max") "min" else "max")
          if (t.text == "Math" && !isDelim(i - 1, ".") && isDelim(i + 1, ".")) found ++= checked(i)
          if (t.text == "throw") found ++= refusal(i)
          if (firstOnLine(t.line) == i) found ++= statement(i)
        case Token.Backquoted =>
          if (firstOnLine(t.line) == i) found ++= statement(i)
        case _ =>
      }
      found.toSeq
    }

    /** Whether token `j` ends an operand. The `)` of an `if`, `while` or
      * `for` header ends none: what follows it is a body. */
    private def endsOperand(j: Int): Boolean = tok(j).exists { t =>
      t.kind match {
        case Token.Ident => !Token.ReservedWords(t.text) || ValueWords(t.text)
        case Token.Backquoted | Token.IntLit | Token.FloatLit | Token.StringLit | Token.CharLit => true
        case Token.Delimiter =>
          t.text == "]" || t.text == ")" && !HeaderWords.exists(w => isIdent(matching(j) - 1, w))
        case _ => false
      }
    }

    private def startsOperand(t: Token): Boolean = t.kind match {
      case Token.Ident => !Token.ReservedWords(t.text) || OperandStartWords(t.text)
      case Token.Backquoted | Token.IntLit | Token.FloatLit | Token.StringLit | Token.CharLit => true
      case Token.Delimiter => t.text == "(" || t.text == "{"
      case Token.Operator => t.text == "!" || t.text == "-" || t.text == "+" || t.text == "~"
      case _ => false
    }

    /** An operand on each side, the left one on the operator's own line. */
    private def binary(i: Int): Boolean = operandBefore(i) && tok(i + 1).exists(startsOperand)

    /** A prefix operator: what comes before it ends no operand on its line. */
    private def prefix(i: Int): Boolean = !operandBefore(i) && tok(i + 1).exists(startsOperand)

    private def operandBefore(i: Int): Boolean = endsOperand(i - 1) && code(i - 1).line == code(i).line

    private def besideString(i: Int): Boolean =
      tok(i - 1).exists(_.kind == Token.StringLit) || tok(i + 1).exists(_.kind == Token.StringLit)

    /** A `1` that a binary `+` or `-` adds or takes away, or a `+=` or `-=`. */
    private def offByOne(i: Int): Boolean = tok(i - 1).exists { p =>
      (p.is(Token.Operator, "+") || p.is(Token.Operator, "-")) && binary(i - 1) && !besideString(i - 1) ||
      (p.is(Token.Operator, "+=") || p.is(Token.Operator, "-=")) && endsOperand(i - 2)
    }

    /** `Math.<name>Exact(args)`, with `i` at `Math`. */
    private def checked(i: Int): Seq[Site] = {
      val name = tok(i + 2).filter(_.kind == Token.Ident).map(_.text).getOrElse("")
      if (!Checked.contains(name) || !isDelim(i + 3, "(")) Nil
      else {
        val close = matching(i + 3)
        val args = arguments(i + 3, close)
        val (arity, plain) = Checked(name)
        if (args.length != arity) Nil
        else {
          val texts = args.map { case (a, b) => src.substring(code(a).start, code(b).end) }
          Seq(site("checked", i, close, plain(texts)))
        }
      }
    }

    /** The arguments between the parenthesis at `open` and its match at
      * `close`, each as its first and last token. */
    private def arguments(open: Int, close: Int): Vector[(Int, Int)] = {
      val out = Vector.newBuilder[(Int, Int)]
      var from = open + 1
      var i = open + 1
      while (i < close) {
        if (isDelim(i, ",")) { out += ((from, i - 1)); from = i + 1; i += 1 }
        else if (opens(i)) i = matching(i) + 1
        else i += 1
      }
      if (close > open + 1) out += ((from, close - 1))
      out.result()
    }

    private def opens(i: Int): Boolean = isDelim(i, "(") || isDelim(i, "[") || isDelim(i, "{")

    /** The last token of a simple expression from `i`: an optional `new`, a
      * dotted path, type arguments and argument lists, or -1 if there is none. */
    private def simpleExpressionEnd(from: Int): Int = {
      var i = if (isIdent(from, "new")) from + 1 else from
      if (!tok(i).exists(t => (t.kind == Token.Ident && !isWord(t)) || t.kind == Token.Backquoted) &&
        !isIdent(i, "this") && !isIdent(i, "super"))
        return -1
      var last = i
      i += 1
      var going = true
      while (going && i < n) {
        if (isDelim(i, ".") && tok(i + 1).exists(t => (t.kind == Token.Ident && !isWord(t)) || t.kind == Token.Backquoted)) {
          last = i + 1
          i += 2
        } else if ((isDelim(i, "(") || isDelim(i, "[") || isDelim(i, "{")) && code(i).line == code(last).line) {
          last = matching(i)
          i = last + 1
        } else going = false
      }
      last
    }

    /** `throw <expression>` as the body of an `if (...)` with no `else`, with
      * `i` at `throw`. */
    private def refusal(i: Int): Seq[Site] = {
      val headerEnd = i - 1
      val isIfBody = isDelim(headerEnd, ")") && isIdent(matching(headerEnd) - 1, "if")
      val end = simpleExpressionEnd(i + 1)
      if (!isIfBody || end < 0) Nil
      else {
        val after = tok(end + 1)
        val continues = after.exists(t =>
          t.is(Token.Ident, "else") || t.is(Token.Delimiter, ".") || t.kind == Token.Operator)
        if (continues) Nil else Seq(site("refusal", i, end, "()"))
      }
    }

    /** A call that is a statement of its own line, with `i` its first token:
      * a dotted path and at least one argument list, all on the line, and not
      * a value made by a bare constructor or `apply` (`Some(x)`). The line
      * before ends a statement of the same block or opens the block; the line
      * after does not continue this one. The last statement of a block is one
      * only where the block's value goes nowhere ([[unitBlock]]): elsewhere it
      * is the block's value, and deleting it would not compile. */
    private def statement(i: Int): Seq[Site] = {
      val line = code(i).line
      val t = code(i)
      if (isWord(t) && !isIdent(i, "this") && !isIdent(i, "super")) return Nil
      val end = simpleExpressionEnd(i)
      val hasArguments = end > i && (i to end).exists(j => isDelim(j, "(") || isDelim(j, "{"))
      if (end < 0 || !hasArguments || lastOnLine(line) != end || code(end).line != line) return Nil
      if (opens(i + 1) && t.text.headOption.exists(_.isUpper)) return Nil
      val after = tok(end + 1)
      if (after.exists(_.is(Token.Delimiter, "."))) return Nil
      val first = tok(i - 1).exists { p =>
        val opensBlock = (p.is(Token.Delimiter, "{") || p.is(Token.Operator, "=>")) && indent(p.line) < indent(line)
        val sibling = indent(statementLine(i - 1)) == indent(line) && endsStatement(p)
        opensBlock || sibling
      }
      val last = after.forall(a => a.is(Token.Delimiter, "}") || a.is(Token.Ident, "case"))
      if (first && (!last || unitBlock(end + 1))) Seq(site("statement", i, end, "")) else Nil
    }

    /** Whether the block that the `}` at `close` ends is a statement, so the
      * value of its last statement goes nowhere: the body of a `while` or a
      * `for`, of an `if` with no `else`, of a `finally`, of a `foreach`, or of
      * a method declared `: Unit`. */
    private def unitBlock(close: Int): Boolean = isDelim(close, "}") && {
      val b = matching(close) - 1
      tok(b).exists { p =>
        if (p.is(Token.Delimiter, ")")) {
          val header = matching(b) - 1
          isIdent(header, "while") || isIdent(header, "for") || isIdent(header, "if") && !isIdent(close + 1, "else")
        } else if (p.is(Token.Operator, "=")) isIdent(b - 1, "Unit") && tok(b - 2).exists(_.is(Token.Operator, ":"))
        else p.is(Token.Ident, "finally") || p.is(Token.Ident, "foreach")
      }
    }

    /** Whether a statement can end at `p`: no operator, comma, dot or opening
      * delimiter, and no word that goes on to the next line. */
    private def endsStatement(p: Token): Boolean = p.kind match {
      case Token.Operator => false
      case Token.Delimiter => p.text == ")" || p.text == "]" || p.text == "}" || p.text == ";"
      case Token.Ident => !OpeningWords(p.text)
      case _ => true
    }

    /** The line the statement that ends at token `j` starts on: a closing
      * delimiter's statement starts on its opening one's line. */
    private def statementLine(j: Int): Int = {
      var k = j
      while (k >= 0 && (isDelim(k, ")") || isDelim(k, "]") || isDelim(k, "}"))) k = matching(k)
      code(firstOnLine(code(k).line)).line
    }

    private def matchDelimiters(): Array[Int] = {
      val m = Array.fill(n)(-1)
      val stack = mutable.Stack.empty[Int]
      val pairs = Map(")" -> "(", "]" -> "[", "}" -> "{")
      var i = 0
      while (i < n) {
        val t = code(i)
        if (t.kind == Token.Delimiter && (t.text == "(" || t.text == "[" || t.text == "{")) stack.push(i)
        else if (t.kind == Token.Delimiter && pairs.contains(t.text)) {
          if (stack.isEmpty || code(stack.top).text != pairs(t.text))
            throw new IllegalArgumentException(s"line ${t.line}: an unmatched '${t.text}'")
          val open = stack.pop()
          m(open) = i
          m(i) = open
        }
        i += 1
      }
      if (stack.nonEmpty) throw new IllegalArgumentException(s"line ${code(stack.top).line}: an unclosed '${code(stack.top).text}'")
      m
    }

    /** Tokens no operator mutates: inside `[...]`, an annotation's arguments,
      * an import or package clause. */
    private def skippedTokens(): Array[Boolean] = {
      val s = Array.fill(n)(false)
      var i = 0
      while (i < n) {
        val t = code(i)
        if (t.is(Token.Delimiter, "[")) {
          (i to matching(i)).foreach(s(_) = true)
        } else if (t.is(Token.Operator, "@") && tok(i + 1).exists(_.kind == Token.Ident)) {
          var j = i + 1
          while (isDelim(j + 1, ".") && tok(j + 2).exists(_.kind == Token.Ident)) j += 2
          if (isDelim(j + 1, "(") && code(j + 1).line == t.line) (i to matching(j + 1)).foreach(s(_) = true)
        } else if ((t.is(Token.Ident, "import") || t.is(Token.Ident, "package")) && firstOnLine(t.line) == i) {
          (i to lastOnLine(t.line)).foreach(s(_) = true)
        }
        i += 1
      }
      s
    }
  }
}
