package com.transformer.slt

import com.transformer.core.DataType

import java.util.Locale

/** One token of a statement: its text, where it starts and ends in the
  * statement, and what kind it is. */
final case class Token(text: String, start: Int, end: Int, kind: Token.Kind) {
  def upper: String = if (kind == Token.Word) text.toUpperCase(Locale.ROOT) else text
  /** A name as SQL reads it: a quoted name without its quotes. */
  def name: String = kind match {
    case Token.QuotedName => text.substring(1, text.length - 1).replace("\"\"", "\"")
    case _ => text
  }
}

object Token {
  sealed trait Kind
  case object Word extends Kind
  case object QuotedName extends Kind
  case object Number extends Kind
  case object Text extends Kind
  case object Blob extends Kind
  case object Symbol extends Kind
}

/** A token cutter for the statements [[Database]] carries out. It is not a
  * SQL parser: it splits words, quoted names, numbers, string and blob
  * literals and symbols, enough to find a statement's parts and read a
  * literal; the engine reads every query. */
object Lexer {

  def tokens(s: String): Vector[Token] = {
    val out = Vector.newBuilder[Token]
    var i = 0
    val n = s.length
    while (i < n) {
      val c = s(i)
      if (c.isWhitespace) i += 1
      else if (c == '-' && i + 1 < n && s(i + 1) == '-') {
        while (i < n && s(i) != '\n') i += 1
      } else if ((c == 'x' || c == 'X') && i + 1 < n && s(i + 1) == '\'') {
        val end = closeQuote(s, i + 1, '\'')
        out += Token(s.substring(i, end), i, end, Token.Blob)
        i = end
      } else if (c == '\'') {
        val end = closeQuote(s, i, '\'')
        out += Token(s.substring(i, end), i, end, Token.Text)
        i = end
      } else if (c == '"' || c == '`' || c == '[') {
        val close = if (c == '[') ']' else c
        val end = closeQuote(s, i, close)
        val inner = s.substring(i + 1, end - 1)
        out += Token("\"" + inner.replace("\"", "\"\"") + "\"", i, end, Token.QuotedName)
        i = end
      } else if (c.isDigit || (c == '.' && i + 1 < n && s(i + 1).isDigit)) {
        var j = i
        while (j < n && (s(j).isDigit || s(j) == '.')) j += 1
        if (j < n && (s(j) == 'e' || s(j) == 'E')) {
          var k = j + 1
          if (k < n && (s(k) == '+' || s(k) == '-')) k += 1
          if (k < n && s(k).isDigit) {
            while (k < n && s(k).isDigit) k += 1
            j = k
          }
        }
        out += Token(s.substring(i, j), i, j, Token.Number)
        i = j
      } else if (c.isLetter || c == '_') {
        var j = i
        while (j < n && (s(j).isLetterOrDigit || s(j) == '_' || s(j) == '$')) j += 1
        out += Token(s.substring(i, j), i, j, Token.Word)
        i = j
      } else {
        val two = if (i + 1 < n) s.substring(i, i + 2) else ""
        val len = if (Set("<=", ">=", "<>", "!=", "||", "==").contains(two)) 2 else 1
        out += Token(s.substring(i, i + len), i, i + len, Token.Symbol)
        i += len
      }
    }
    out.result()
  }

  /** The index just past the quote that closes the one at `open`, a doubled
    * quote standing for itself. */
  private def closeQuote(s: String, open: Int, close: Char): Int = {
    val q = s.indexOf(if (s(open) == 'x' || s(open) == 'X') '\'' else s(open), open)
    var i = q + 1
    while (i < s.length) {
      if (s(i) == close) {
        if (close != ']' && i + 1 < s.length && s(i + 1) == close) i += 2
        else return i + 1
      } else i += 1
    }
    throw new IllegalArgumentException(s"unclosed quote at ${s.substring(open, math.min(s.length, open + 20))}")
  }

  /** `sql` cut into its statements at each `;` outside quotes and
    * parentheses, each trimmed; a piece holding no token (a comment, or
    * nothing after the last `;`) is no statement. */
  def splitStatements(sql: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    var depth = 0
    var start = 0
    tokens(sql).foreach { tk =>
      if (tk.kind == Token.Symbol && tk.text == "(") depth += 1
      else if (tk.kind == Token.Symbol && tk.text == ")") depth -= 1
      else if (tk.kind == Token.Symbol && tk.text == ";" && depth == 0) {
        out += sql.substring(start, tk.start)
        start = tk.end
      }
    }
    out += sql.substring(start)
    out.result().map(_.trim).filter(p => tokens(p).nonEmpty)
  }

  /** Tokens split at the commas outside parentheses. */
  def splitCommas(t: Vector[Token]): Vector[Vector[Token]] = {
    if (t.isEmpty) return Vector.empty
    val out = Vector.newBuilder[Vector[Token]]
    val cur = Vector.newBuilder[Token]
    var depth = 0
    t.foreach { tk =>
      if (tk.text == "(" && tk.kind == Token.Symbol) { depth += 1; cur += tk }
      else if (tk.text == ")" && tk.kind == Token.Symbol) { depth -= 1; cur += tk }
      else if (tk.text == "," && tk.kind == Token.Symbol && depth == 0) { out += cur.result(); cur.clear() }
      else cur += tk
    }
    out += cur.result()
    out.result()
  }

  /** A number literal as written, sign included, read as each column type
    * reads it: `-0.0` stays a negative zero in a DOUBLE column, which no
    * `BigDecimal` holds. */
  final case class NumberText(text: String) {
    def decimal: java.math.BigDecimal = new java.math.BigDecimal(text)
    def double: Double = java.lang.Double.parseDouble(text)
    def float: Float = java.lang.Float.parseFloat(text)
    def isWhole: Boolean = !text.exists(c => c == '.' || c == 'e' || c == 'E')
  }

  /** The value a VALUES item writes when it is a literal (a number, possibly
    * signed, held as its text; a string; NULL, TRUE or FALSE; a blob; or a
    * DATE or TIMESTAMP literal, held as its text); None for anything else. */
  def literal(item: Vector[Token]): Option[Any] = item match {
    case Vector(n) if n.kind == Token.Number => Some(NumberText(n.text))
    case Vector(sign, n) if n.kind == Token.Number && (sign.text == "-" || sign.text == "+") =>
      Some(NumberText(if (sign.text == "-") "-" + n.text else n.text))
    case Vector(s) if s.kind == Token.Text => Some(unquote(s.text))
    case Vector(w) if w.kind == Token.Word && w.upper == "NULL" => Some(null)
    case Vector(w) if w.kind == Token.Word && w.upper == "TRUE" => Some(java.lang.Boolean.TRUE)
    case Vector(w) if w.kind == Token.Word && w.upper == "FALSE" => Some(java.lang.Boolean.FALSE)
    case Vector(b) if b.kind == Token.Blob => Some(hexBytes(unquote(b.text.substring(1))))
    case Vector(w, s) if w.kind == Token.Word && (w.upper == "DATE" || w.upper == "TIMESTAMP") && s.kind == Token.Text =>
      Some(unquote(s.text))
    case _ => None
  }

  /** The type a literal's value has before it is stored (for messages). */
  def literalType(v: Any): DataType = v match {
    case null => DataType.NullType
    case n: NumberText if n.isWhole => DataType.LongType
    case n: NumberText =>
      val d = n.decimal
      DataType.DecimalType(math.max(1, d.precision), math.max(0, d.scale))
    case _: java.lang.Boolean => DataType.BooleanType
    case _: Array[Byte] => DataType.BinaryType
    case _ => DataType.StringType
  }

  private def unquote(q: String): String = q.substring(1, q.length - 1).replace("''", "'")

  private def hexBytes(h: String): Array[Byte] = {
    if (h.length % 2 != 0) throw new IllegalArgumentException(s"a blob literal of odd length: x'$h'")
    h.grouped(2).map(p => Integer.parseInt(p, 16).toByte).toArray
  }

  /** A timestamp's text as an instant in UTC: ISO-8601 (`2026-01-31T10:00:00Z`)
    * or SQL's `2026-01-31 10:00:00[.ffffff]`. */
  def instant(s: String): java.time.Instant =
    if (s.endsWith("Z") || s.contains("+")) java.time.Instant.parse(s)
    else java.time.LocalDateTime.parse(s.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC)

  /** `sql` with every run of two or more `+` / `-` signs written as the one
    * sign it amounts to: `- - 96` as `+ 96`, `a - + b` as `a - b`. Whether the
    * run follows an operand (its first sign then binary) or not, the signs
    * multiply, so the folded text means what the original does; it can differ
    * only where a sign in the run would overflow, as negating BIGINT's
    * smallest value does. [[Dialect.Sqlite]] reads SQLite's corpus through
    * this, since JSqlParser cannot parse two signs in a row and the corpus
    * writes them in almost every query. */
  def foldSigns(sql: String): String = {
    val t = tokens(sql)
    val out = new StringBuilder
    var copied = 0
    var i = 0
    def isSign(k: Int) = k < t.length && t(k).kind == Token.Symbol && (t(k).text == "+" || t(k).text == "-")
    while (i < t.length) {
      if (isSign(i) && isSign(i + 1)) {
        var j = i
        var minus = 0
        while (isSign(j)) { if (t(j).text == "-") minus += 1; j += 1 }
        out ++= sql.substring(copied, t(i).start)
        out += (if (minus % 2 == 1) '-' else '+')
        copied = t(j - 1).end
        i = j
      } else i += 1
    }
    out ++= sql.substring(copied)
    out.result()
  }

  /** Whether `sql` selects `*` or `t.*`: a star after SELECT, DISTINCT,
    * ALL, a comma or a dot, not the star of `COUNT(*)` or a multiplication. */
  def selectsStar(sql: String): Boolean = {
    val t = tokens(sql)
    t.indices.exists { i =>
      t(i).kind == Token.Symbol && t(i).text == "*" && i > 0 && {
        val before = t(i - 1)
        (before.kind == Token.Word && Set("SELECT", "DISTINCT", "ALL")(before.upper)) ||
          (before.kind == Token.Symbol && (before.text == "," || before.text == "."))
      }
    }
  }

  /** Whether `sql` orders its result: an `ORDER BY` outside parentheses (one
    * inside a window's `OVER (...)` or a subquery orders no result). */
  def ordersResult(sql: String): Boolean = {
    val t = tokens(sql)
    var depth = 0
    t.indices.exists { i =>
      if (t(i).kind == Token.Symbol && t(i).text == "(") depth += 1
      else if (t(i).kind == Token.Symbol && t(i).text == ")") depth -= 1
      depth == 0 && t(i).kind == Token.Word && t(i).upper == "ORDER" && t.lift(i + 1).exists(_.upper == "BY")
    }
  }

  /** Whether `sql` keeps some rows of its result and not others with no
    * `ORDER BY` to say which: a `LIMIT`, `OFFSET` or `FETCH` outside
    * parentheses in a query that orders nothing. */
  def keepsUnorderedRows(sql: String): Boolean = !ordersResult(sql) && {
    val t = tokens(sql)
    var depth = 0
    t.exists { tk =>
      if (tk.kind == Token.Symbol && tk.text == "(") depth += 1
      else if (tk.kind == Token.Symbol && tk.text == ")") depth -= 1
      depth == 0 && tk.kind == Token.Word && Set("LIMIT", "OFFSET", "FETCH", "TOP")(tk.upper)
    }
  }

  /** Whether `sql` is an EXPLAIN, whose plan text differs by mode, so it
    * runs in the baseline alone. */
  def explains(sql: String): Boolean =
    tokens(sql).find(_.kind == Token.Word).exists(_.upper == "EXPLAIN")

  /** A name in double quotes, which the engine reads as written. */
  def quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""
}
