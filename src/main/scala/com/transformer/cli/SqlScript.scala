package com.transformer.cli

import com.transformer.sql.parse.SqlParser

import java.util.Locale

/** A script as `transformer query` reads it: SQL statements, each ended by a
  * `;` outside quotes and comments, and dot commands, each a line of its own
  * that starts with `.` where a statement would start. The last statement of a
  * script needs no `;`.
  *
  * Three statements are the CLI's own, read here before any SQL reaches the
  * engine: `CREATE VIEW`, `DROP VIEW` and `COPY ... TO`. Each holds a query,
  * which goes to the engine as it is written; every other statement goes to the
  * engine whole. */
object SqlScript {

  /** One statement or dot command of a script, without its `;`, and the line
    * of the script it starts on. */
  final case class Piece(text: String, line: Int, dot: Boolean)

  /** A script cut into the statements and dot commands it ends, in order, and
    * `rest`, the text after the last of them: empty when that is only white
    * space and comments, else a statement no `;` ends yet, starting on
    * `restLine`. `open` when the text ends inside a quote or a comment. */
  final case class Split(pieces: Vector[Piece], rest: String, restLine: Int, open: Boolean) {
    /** Every piece, `rest` the last statement when it holds one. */
    def all: Vector[Piece] = if (rest.isEmpty) pieces else pieces :+ Piece(rest, restLine, dot = false)
  }

  def split(text: String): Split = {
    val pieces = Vector.newBuilder[Piece]
    val n = text.length
    var i = 0
    var line = 1
    var lineStart = 0
    var start = 0     // where the statement being read starts
    var startLine = 1 // the line its first token is on
    var blank = true  // it holds only white space and comments so far
    var open = false
    def newline(at: Int): Unit = { line += 1; lineStart = at + 1 }
    while (i < n) {
      val c = text.charAt(i)
      if (c == '\n') { newline(i); i += 1 }
      else if (c.isWhitespace) i += 1
      else if (blank && c == '.' && text.substring(lineStart, i).forall(_.isWhitespace)) {
        val end = text.indexOf('\n', i) match { case -1 => n; case e => e }
        pieces += Piece(text.substring(i, end).trim, line, dot = true)
        i = end
        start = end
      } else if (c == '-' && i + 1 < n && text.charAt(i + 1) == '-') {
        while (i < n && text.charAt(i) != '\n') i += 1
      } else if (c == '/' && i + 1 < n && text.charAt(i + 1) == '*') {
        val close = text.indexOf("*/", i + 2)
        val end = if (close < 0) { open = true; n } else close + 2
        while (i < end) { if (text.charAt(i) == '\n') newline(i); i += 1 }
      } else if (c == ';') {
        if (!blank) pieces += Piece(text.substring(start, i).trim, startLine, dot = false)
        i += 1
        start = i
        blank = true
      } else {
        if (blank) { blank = false; startLine = line }
        if (c == '\'' || c == '"' || c == '`') {
          // A quote doubled inside is one character of the text.
          i += 1
          var closed = false
          while (i < n && !closed) {
            val d = text.charAt(i)
            if (d == c && i + 1 < n && text.charAt(i + 1) == c) i += 2
            else if (d == c) { closed = true; i += 1 }
            else { if (d == '\n') newline(i); i += 1 }
          }
          if (!closed) open = true
        } else i += 1
      }
    }
    Split(pieces.result(), if (blank) "" else text.substring(start).trim, startLine, open)
  }

  /** Whether `text` is whole as the shell reads it: every statement in it
    * ended by a `;`, or a dot command, or nothing but white space and
    * comments. Until it is, the shell reads another line into it. */
  def complete(text: String): Boolean = {
    val s = split(text)
    s.rest.isEmpty && !s.open
  }

  /** What the CLI runs a piece as. */
  sealed trait Statement
  object Statement {
    /** A statement for the engine: a query, or EXPLAIN of one. */
    final case class Query(sql: String) extends Statement
    /** `CREATE [OR REPLACE] VIEW name [(column, ...)] AS query` */
    final case class CreateView(name: String, columns: Seq[String], orReplace: Boolean, query: String) extends Statement
    /** `DROP VIEW [IF EXISTS] name` */
    final case class DropView(name: String, ifExists: Boolean) extends Statement
    /** `COPY (query) TO 'path' [(option value, ...)]`, or `COPY name TO ...`,
      * which copies every row of a table or view: `source` is the name or the
      * query. */
    final case class Copy(source: Either[String, String], path: String, options: Seq[(String, String)]) extends Statement
    /** `.command arg ...`, the command in lower case. */
    final case class Dot(command: String, args: Seq[String]) extends Statement
  }

  val CreateViewSyntax = "CREATE [OR REPLACE] VIEW name [(column, ...)] AS query"
  val DropViewSyntax = "DROP VIEW [IF EXISTS] name"
  val CopySyntax = "COPY (query) TO 'file' [(option value, ...)], or COPY name TO 'file' [(option value, ...)]"

  def statement(piece: Piece): Statement =
    if (piece.dot) dotCommand(piece.text) else sqlStatement(piece.text)

  def sqlStatement(text: String): Statement = {
    val lex = new Lexer(text)
    lex.next() match {
      case w: Word if w.is("CREATE") => createView(text, lex)
      case w: Word if w.is("DROP") => dropView(lex)
      case w: Word if w.is("COPY") => copy(text, lex)
      case _ => Statement.Query(text)
    }
  }

  private def createView(text: String, lex: Lexer): Statement = {
    def fail(found: Token, expected: String): Nothing = throw new IllegalArgumentException(
      s"Expected $expected in CREATE VIEW, found ${describe(found)}; write $CreateViewSyntax")
    var t = lex.next()
    val orReplace = t match {
      case w: Word if w.is("OR") =>
        lex.next() match { case r: Word if r.is("REPLACE") => (); case other => fail(other, "REPLACE after OR") }
        t = lex.next()
        true
      case _ => false
    }
    // Every view lives as long as the session, so TEMP says nothing more.
    t match { case w: Word if w.is("TEMP") || w.is("TEMPORARY") => t = lex.next(); case _ => () }
    t match {
      case w: Word if w.is("VIEW") => ()
      case w: Word => throw new IllegalArgumentException(
        s"CREATE ${w.text.toUpperCase(Locale.ROOT)} is not supported; $CreateViewSyntax names a query, and a table is " +
          "a file named with --table or .table")
      case other => fail(other, "VIEW")
    }
    val name = lex.next() match { case n: NameToken => n.name; case other => fail(other, "the view's name") }
    var columns = Vector.empty[String]
    t = lex.next()
    if (t.isSym('(')) {
      var more = true
      while (more) {
        columns :+= (lex.next() match { case n: NameToken => n.name; case other => fail(other, "a column name") })
        lex.next() match {
          case s if s.isSym(',') => ()
          case s if s.isSym(')') => more = false
          case other => fail(other, "',' or ')' in the column list")
        }
      }
      t = lex.next()
    }
    t match { case w: Word if w.is("AS") => (); case other => fail(other, "AS") }
    val query = text.substring(t.end).trim
    if (query.isEmpty) throw new IllegalArgumentException(s"CREATE VIEW $name needs a query after AS")
    Statement.CreateView(name, columns, orReplace, query)
  }

  private def dropView(lex: Lexer): Statement = {
    def fail(found: Token, expected: String): Nothing = throw new IllegalArgumentException(
      s"Expected $expected in DROP VIEW, found ${describe(found)}; write $DropViewSyntax")
    lex.next() match {
      case w: Word if w.is("VIEW") => ()
      case w: Word => throw new IllegalArgumentException(
        s"DROP ${w.text.toUpperCase(Locale.ROOT)} is not supported; $DropViewSyntax drops a view, and a table " +
          "named with --table or .table stays for the session")
      case other => fail(other, "VIEW")
    }
    var t = lex.next()
    val ifExists = t match {
      case w: Word if w.is("IF") =>
        lex.next() match { case e: Word if e.is("EXISTS") => (); case other => fail(other, "EXISTS after IF") }
        t = lex.next()
        true
      case _ => false
    }
    val name = t match { case n: NameToken => n.name; case other => fail(other, "the view's name") }
    lex.next() match { case _: End => (); case other => fail(other, "the end of the statement") }
    Statement.DropView(name, ifExists)
  }

  private def copy(text: String, lex: Lexer): Statement = {
    def fail(found: Token, expected: String): Nothing = throw new IllegalArgumentException(
      s"Expected $expected in COPY, found ${describe(found)}; write $CopySyntax")
    val source: Either[String, String] = lex.next() match {
      case open if open.isSym('(') =>
        var depth = 1
        var t: Token = open
        while (depth > 0) {
          t = lex.next()
          if (t.isSym('(')) depth += 1
          else if (t.isSym(')')) depth -= 1
          else if (t.isInstanceOf[End]) fail(t, "the ')' that closes the query")
        }
        val query = text.substring(open.end, t.start).trim
        if (query.isEmpty) fail(t, "a query inside the parentheses")
        Right(query)
      case n: NameToken => Left(n.name)
      case other => fail(other, "a query in parentheses or a table's name after COPY")
    }
    lex.next() match {
      case w: Word if w.is("TO") => ()
      case w: Word if w.is("FROM") => throw new IllegalArgumentException(
        "COPY ... FROM is not supported; name a file to read with --table or .table")
      case other => fail(other, "TO")
    }
    val path = lex.next() match { case s: Str => s.value; case other => fail(other, "the file's path in quotes") }
    if (path.isEmpty) throw new IllegalArgumentException("COPY TO needs a file's path, not ''")
    var t = lex.next()
    t match { case w: Word if w.is("WITH") => t = lex.next(); case _ => () }
    var options = Vector.empty[(String, String)]
    if (t.isSym('(')) {
      var more = true
      while (more) {
        val key = lex.next() match { case w: Word => w.text; case other => fail(other, "an option's name") }
        val value = lex.next() match {
          case s: Str => s.value
          case w: Word => w.text
          case n: Num => n.text
          case other => fail(other, s"a value for $key")
        }
        options :+= (key -> value)
        lex.next() match {
          case s if s.isSym(',') => ()
          case s if s.isSym(')') => more = false
          case other => fail(other, "',' or ')' in the options")
        }
      }
      t = lex.next()
    }
    t match { case _: End => (); case other => fail(other, "the end of the statement") }
    Statement.Copy(source, path, options)
  }

  /** `.command arg ...` as the command and its arguments. */
  def dotCommand(text: String): Statement.Dot = {
    val words = dotWords(text.stripPrefix("."))
    if (words.isEmpty) throw new IllegalArgumentException("A '.' needs a command after it; .help lists them")
    Statement.Dot(words.head.toLowerCase(Locale.ROOT), words.tail)
  }

  /** `text` cut into words at white space. A word may be quoted, in single or
    * double quotes, to hold white space; a quote doubled inside is one. */
  def dotWords(text: String): Vector[String] = {
    val words = Vector.newBuilder[String]
    val n = text.length
    var i = 0
    while (i < n) {
      while (i < n && text.charAt(i).isWhitespace) i += 1
      if (i < n) {
        val word = new java.lang.StringBuilder
        while (i < n && !text.charAt(i).isWhitespace) {
          val c = text.charAt(i)
          if (c == '\'' || c == '"') {
            i += 1
            var closed = false
            while (i < n && !closed) {
              val d = text.charAt(i)
              if (d == c && i + 1 < n && text.charAt(i + 1) == c) { word.append(c); i += 2 }
              else if (d == c) { closed = true; i += 1 }
              else { word.append(d); i += 1 }
            }
            if (!closed) throw new IllegalArgumentException(s"A quote in '$text' is never closed")
          } else { word.append(c); i += 1 }
        }
        words += word.toString
      }
    }
    words.result()
  }

  /** `query` with the CTEs `definitions` (each `name AS (query)`) defined ahead
    * of everything else it reads: in its own WITH when it has one, else in a
    * WITH put before it. */
  def withDefinitions(query: String, definitions: Seq[String]): String = {
    val lex = new Lexer(query)
    lex.next() match {
      case w: Word if w.is("WITH") =>
        val after = lex.next() match { case r: Word if r.is("RECURSIVE") => r.end; case _ => w.end }
        query.substring(0, after) + "\n" + definitions.mkString(",\n") + ",\n" + query.substring(after).trim
      case _ => "WITH " + definitions.mkString(",\n") + "\n" + query
    }
  }

  // ---------------------------------------------------------------------------
  // The words of a statement, as far as the CLI reads them
  // ---------------------------------------------------------------------------

  private sealed trait Token {
    def start: Int
    def end: Int
    def isSym(c: Char): Boolean = this match { case s: Sym => s.c == c; case _ => false }
  }
  /** A bare word, a quoted name or neither: what can name a view or a column. */
  private sealed trait NameToken extends Token { def name: String }
  private final case class Word(text: String, start: Int, end: Int) extends NameToken {
    def name: String = text
    def is(keyword: String): Boolean = text.equalsIgnoreCase(keyword)
  }
  private final case class Quoted(name: String, start: Int, end: Int) extends NameToken
  private final case class Str(value: String, start: Int, end: Int) extends Token
  private final case class Num(text: String, start: Int, end: Int) extends Token
  private final case class Sym(c: Char, start: Int, end: Int) extends Token
  private final case class End(start: Int) extends Token { def end: Int = start }

  private def describe(t: Token): String = t match {
    case w: Word => s"'${w.text}'"
    case q: Quoted => s"the name ${SqlParser.quote(q.name)}"
    case s: Str => s"the string '${s.value}'"
    case n: Num => s"the number ${n.text}"
    case s: Sym => s"'${s.c}'"
    case _: End => "the end of the statement"
  }

  /** The tokens of `text` one by one, white space and comments skipped. */
  private final class Lexer(text: String) {
    private var i = 0

    def next(): Token = {
      skipBlank()
      val n = text.length
      if (i >= n) return End(n)
      val start = i
      val c = text.charAt(i)
      if (Character.isLetter(c) || c == '_') {
        while (i < n && wordChar(text.charAt(i))) i += 1
        Word(text.substring(start, i), start, i)
      } else if (Character.isDigit(c)) {
        while (i < n && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '.')) i += 1
        Num(text.substring(start, i), start, i)
      } else if (c == '\'' || c == '"' || c == '`') {
        i += 1
        var closed = false
        while (i < n && !closed) {
          if (text.charAt(i) == c && i + 1 < n && text.charAt(i + 1) == c) i += 2
          else if (text.charAt(i) == c) { closed = true; i += 1 }
          else i += 1
        }
        if (!closed)
          throw new IllegalArgumentException(s"A quote that is never closed starts at ${text.substring(start).take(40)}")
        val written = text.substring(start, i)
        if (c == '\'') Str(SqlParser.stringLiteralValue(written), start, i)
        else Quoted(SqlParser.unquote(written), start, i)
      } else {
        i += 1
        Sym(c, start, i)
      }
    }

    private def wordChar(c: Char): Boolean = Character.isLetterOrDigit(c) || c == '_' || c == '$'

    private def skipBlank(): Unit = {
      var moved = true
      while (moved) {
        moved = false
        while (i < text.length && text.charAt(i).isWhitespace) { i += 1; moved = true }
        if (text.startsWith("--", i)) {
          while (i < text.length && text.charAt(i) != '\n') i += 1
          moved = true
        } else if (text.startsWith("/*", i)) {
          val close = text.indexOf("*/", i + 2)
          i = if (close < 0) text.length else close + 2
          moved = true
        }
      }
    }
  }
}
