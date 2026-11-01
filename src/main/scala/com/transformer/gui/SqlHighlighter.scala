package com.transformer.gui

/** Pure-Scala SQL tokenizer for the GUI's syntax highlighter.
  *
  * Recognises SQL keywords, function calls, type names, string literals (with
  * the SQL doubled-quote escape `''`), line and block comments, numeric
  * literals and `{{ template }}` placeholders embedded in source SQL. Anything
  * else falls out as an identifier or punctuation.
  *
  * A word is a function when a `(` follows it, so a column named `count` or an
  * alias `e` stays an identifier, and a call to any function is one. A type
  * name is a type where one is written: after the `AS` of a `CAST`, before a
  * `(` (`DECIMAL(10, 2)`), or before the string of a typed literal
  * (`DATE '2026-01-01'`); elsewhere `date` is a column like any other.
  *
  * No deps; safe to call from any thread. Single-pass, O(n) on the input.
  * Concatenating the tokens' texts gives the input back.
  */
object SqlHighlighter {

  sealed trait Kind
  object Kind {
    case object Keyword    extends Kind
    case object Function   extends Kind
    case object Type       extends Kind
    case object StringLit  extends Kind
    case object NumberLit  extends Kind
    case object Comment    extends Kind
    case object Template   extends Kind
    case object Punct      extends Kind
    case object Identifier extends Kind
    case object Whitespace extends Kind
  }

  final case class Token(kind: Kind, text: String)

  /** The style class the editor gives a token of `kind`; none for plain
    * text. `transformer.css` colours each. */
  def styleClass(kind: Kind): Option[String] = kind match {
    case Kind.Keyword    => Some("keyword")
    case Kind.Function   => Some("function")
    case Kind.Type       => Some("type")
    case Kind.StringLit  => Some("string")
    case Kind.NumberLit  => Some("number")
    case Kind.Comment    => Some("comment")
    case Kind.Template   => Some("template")
    case Kind.Punct      => Some("punct")
    case Kind.Identifier => None
    case Kind.Whitespace => None
  }

  private val Keywords: Set[String] = Set(
    "select", "from", "where", "group", "by", "order", "having", "limit",
    "offset", "distinct", "join", "inner", "outer", "left", "right",
    "full", "cross", "on", "using", "as", "and", "or", "not", "in", "is",
    "null", "like", "ilike", "between", "exists", "case", "when", "then", "else",
    "end", "true", "false", "union", "all", "intersect", "except",
    "asc", "desc", "with", "over", "partition", "window", "any", "some",
    "cast", "into", "values", "default", "top", "fetch", "next", "only",
    "rows", "range", "preceding", "following", "unbounded", "current", "row",
    "nulls", "first", "last", "qualify", "natural", "lateral", "recursive",
    "escape", "interval", "filter", "within", "ties", "percent", "rollup",
    "cube", "grouping", "sets"
  )

  /** Functions written without parentheses. */
  private val NiladicFunctions: Set[String] = Set("current_date", "current_timestamp")

  private val Types: Set[String] = Set(
    "int", "integer", "bigint", "smallint", "tinyint", "float", "real", "double",
    "precision", "decimal", "numeric", "varchar", "char", "string", "text",
    "boolean", "bool", "date", "timestamp", "binary", "varbinary", "long"
  )

  def tokenize(sql: String): Vector[Token] = {
    if (sql == null || sql.isEmpty) return Vector.empty
    val out = Vector.newBuilder[Token]
    val len = sql.length
    // The word before each open parenthesis, innermost first, so the AS of a
    // CAST can be told from an alias's.
    var parens: List[String] = Nil
    var lastWord = ""
    var prevWord = ""
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
        prevWord = ""
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
        prevWord = ""
      } else if (isIdentStart(c)) {
        val start = i
        while (i < len && isIdentPart(sql.charAt(i))) i += 1
        val text = sql.substring(start, i)
        val lower = text.toLowerCase
        val next = nextSignificant(sql, i)
        val castsTo = prevWord == "as" && parens.headOption.contains("cast")
        val kind =
          if (Keywords.contains(lower)) Kind.Keyword
          else if (Types.contains(lower) && (castsTo || next == '(' || next == '\'')) Kind.Type
          else if (next == '(' || NiladicFunctions.contains(lower)) Kind.Function
          else Kind.Identifier
        out += Token(kind, text)
        lastWord = lower
        prevWord = lower
      } else {
        if (c == '(') parens = lastWord :: parens
        else if (c == ')' && parens.nonEmpty) parens = parens.tail
        lastWord = ""
        prevWord = ""
        out += Token(Kind.Punct, sql.substring(i, i + 1))
        i += 1
      }
    }
    out.result()
  }

  /** The first character from `from` that is not white space, or 0 at the
    * end. */
  private def nextSignificant(sql: String, from: Int): Char = {
    var i = from
    while (i < sql.length && Character.isWhitespace(sql.charAt(i))) i += 1
    if (i < sql.length) sql.charAt(i) else 0.toChar
  }

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
  private def isIdentStart(c: Char): Boolean =
    (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_'
  private def isIdentPart(c: Char): Boolean = isIdentStart(c) || isDigit(c)
}
