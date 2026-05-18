package com.transformer.gui

/** Pure-Scala SQL tokenizer for the GUI's syntax highlighter.
  *
  * Recognises SQL keywords, built-in functions, string literals (with the
  * SQL doubled-quote escape `''`), line and block comments, numeric literals
  * and `{{ template }}` placeholders embedded in source SQL. Anything else
  * falls out as an identifier or punctuation.
  *
  * No deps; safe to call from any thread. Single-pass, O(n) on the input.
  *
  * Newlines are preserved verbatim inside [[Kind.Whitespace]] tokens; the
  * caller can split on `\n` to render line by line.
  */
object SqlHighlighter {

  sealed trait Kind
  object Kind {
    case object Keyword    extends Kind
    case object Function   extends Kind
    case object StringLit  extends Kind
    case object NumberLit  extends Kind
    case object Comment    extends Kind
    case object Template   extends Kind
    case object Punct      extends Kind
    case object Identifier extends Kind
    case object Whitespace extends Kind
  }

  final case class Token(kind: Kind, text: String)

  private val Keywords: Set[String] = Set(
    "select", "from", "where", "group", "by", "order", "having", "limit",
    "offset", "distinct", "join", "inner", "outer", "left", "right",
    "full", "cross", "on", "using", "as", "and", "or", "not", "in", "is",
    "null", "like", "between", "exists", "case", "when", "then", "else",
    "end", "if", "true", "false", "union", "all", "intersect", "except",
    "asc", "desc", "with", "over", "partition", "window", "any", "some",
    "cast", "into", "values", "default",
    "rows", "range", "preceding", "following", "unbounded", "current", "row",
    "nulls", "first", "last"
  )

  private val Functions: Set[String] = Set(
    "count", "sum", "avg", "min", "max",
    "length", "len", "upper", "lower", "trim",
    "substring", "concat", "coalesce", "nullif",
    "abs", "round", "floor", "ceil", "ceiling",
    "mod", "pow", "power",
    "current_date", "current_timestamp",
    "row_number", "rank", "dense_rank", "lag", "lead"
  )

  def tokenize(sql: String): Vector[Token] = {
    if (sql == null || sql.isEmpty) return Vector.empty
    val out = Vector.newBuilder[Token]
    val len = sql.length
    var i = 0
    while (i < len) {
      val c = sql.charAt(i)
      if (Character.isWhitespace(c)) {
        val start = i
        while (i < len && Character.isWhitespace(sql.charAt(i))) i += 1
        out += Token(Kind.Whitespace, sql.substring(start, i))
      } else if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
        val start = i
        while (i < len && sql.charAt(i) != '\n') i += 1
        out += Token(Kind.Comment, sql.substring(start, i))
      } else if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
        val start = i
        i += 2
        while (i + 1 < len && !(sql.charAt(i) == '*' && sql.charAt(i + 1) == '/')) i += 1
        if (i + 1 < len) i += 2 else i = len
        out += Token(Kind.Comment, sql.substring(start, i))
      } else if (c == '\'') {
        val start = i
        i += 1
        var done = false
        while (!done && i < len) {
          if (sql.charAt(i) == '\'') {
            if (i + 1 < len && sql.charAt(i + 1) == '\'') i += 2
            else { i += 1; done = true }
          } else i += 1
        }
        out += Token(Kind.StringLit, sql.substring(start, i))
      } else if (c == '{' && i + 1 < len && sql.charAt(i + 1) == '{') {
        val start = i
        i += 2
        while (i + 1 < len && !(sql.charAt(i) == '}' && sql.charAt(i + 1) == '}')) i += 1
        if (i + 1 < len) i += 2 else i = len
        out += Token(Kind.Template, sql.substring(start, i))
      } else if (isDigit(c) || (c == '.' && i + 1 < len && isDigit(sql.charAt(i + 1)))) {
        val start = i
        while (i < len && isDigit(sql.charAt(i))) i += 1
        if (i < len && sql.charAt(i) == '.') {
          i += 1
          while (i < len && isDigit(sql.charAt(i))) i += 1
        }
        if (i < len && (sql.charAt(i) == 'e' || sql.charAt(i) == 'E')) {
          i += 1
          if (i < len && (sql.charAt(i) == '+' || sql.charAt(i) == '-')) i += 1
          while (i < len && isDigit(sql.charAt(i))) i += 1
        }
        out += Token(Kind.NumberLit, sql.substring(start, i))
      } else if (isIdentStart(c)) {
        val start = i
        while (i < len && isIdentPart(sql.charAt(i))) i += 1
        val text = sql.substring(start, i)
        val lower = text.toLowerCase
        val kind =
          if (Keywords.contains(lower)) Kind.Keyword
          else if (Functions.contains(lower)) Kind.Function
          else Kind.Identifier
        out += Token(kind, text)
      } else {
        out += Token(Kind.Punct, sql.substring(i, i + 1))
        i += 1
      }
    }
    out.result()
  }

  /** Split a token stream into per-line slices, breaking any whitespace
    * token that contains `\n` so each line's iterator is independent. Useful
    * for laying out the highlighted SQL one line at a time without losing
    * non-newline whitespace inside a line.
    */
  def splitIntoLines(tokens: Vector[Token]): Vector[Vector[Token]] = {
    val lines = Vector.newBuilder[Vector[Token]]
    val current = Vector.newBuilder[Token]
    val it = tokens.iterator
    while (it.hasNext) {
      val tk = it.next()
      if (tk.text.indexOf('\n') < 0) current += tk
      else {
        val parts = tk.text.split("\n", -1)
        var pi = 0
        while (pi < parts.length) {
          if (parts(pi).nonEmpty) current += Token(tk.kind, parts(pi))
          if (pi < parts.length - 1) {
            lines += current.result()
            current.clear()
          }
          pi += 1
        }
      }
    }
    lines += current.result()
    lines.result()
  }

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isIdentStart(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_'
  private def isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c)
}
