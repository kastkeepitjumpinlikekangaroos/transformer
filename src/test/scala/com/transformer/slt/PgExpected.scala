package com.transformer.slt

import com.transformer.core.DataType
import com.transformer.slt.Script.{Column, Expected, Query, Record, SortMode, Statement}

import java.math.{BigDecimal => JBigDecimal}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDate
import java.util.Locale
import scala.collection.mutable

/** Checks a port of a PostgreSQL regression test (`src/test/slt/postgres/`)
  * against what Postgres printed: every expected block of the script is
  * derived again from the file's `expected/<name>.out`, psql's output, and
  * any block that says otherwise is a problem, as is a `statement error`
  * whose statement Postgres ran and a query Postgres refused.
  *
  * The port names its source in its header (`src/test/regress/sql/<name>.sql`),
  * and its directory holds the source and Postgres's output at the commit the
  * header names, as `sql/<name>.sql` and `expected/<name>.out`. A record names
  * the statement it comes from by a comment line just above it that starts
  * `# <name>.sql:<line>` (or `<line>-<last>` for records made of several
  * statements); a record with no comment comes from the statement after its
  * predecessor's, so a run of records ported in order needs one citation,
  * and a statement under a comment that cites no line is the port's own (a
  * table it makes for a VALUES list) and comes from none. A twin (the
  * `onlyif transformer` record after a skip, the engine's own answer) is no
  * answer of Postgres's and is passed over, and so is a record whose comment
  * names another file. A skipped record keeps Postgres's answer, and is
  * checked like any other.
  *
  * psql's output is read as the regression suite writes it (`psql -X -a -q`):
  * every line of the `.sql` file is echoed but an empty one outside a quote
  * or comment, and after the line that ends a statement comes what the
  * statement printed: an aligned table (header, a `---+---` line, a row a
  * line, a cell over several lines marked `+` in its right margin, `(N
  * rows)`, a blank line), or `ERROR:` and its `LINE`, `DETAIL` and `HINT`
  * lines, or `NOTICE:` lines, or nothing. `\pset null` sets how NULL prints;
  * by default it prints as nothing, as does the empty string, so an empty text
  * cell is either. Anything else in the output fails loudly, naming the line.
  *
  * Each of Postgres's values is printed as the record's column prints it
  * ([[Values]]): an exact column converts the text to its type (a float8 or
  * float4 to the double or float it reads as, then Java's `toString`; a
  * numeric to the column's scale, which must be exact; `t` and `f` to `true`
  * and `false`; a date in Postgres's `MM-DD-YYYY` or ISO style to ISO), `R`
  * prints the number to three places, `I` its integer part, and `T` the text,
  * or a number as SQLite prints a double, since `T` holds both. The rows are
  * then sorted as the record's sort mode says.
  */
object PgExpected {

  // ---- the .sql file ---------------------------------------------------------

  /** One statement of a `.sql` file: its 1-based first and last lines, its
    * text, and whether it is a psql meta-command (`\pset ...`). */
  final case class SqlStatement(index: Int, first: Int, last: Int, text: String, meta: Boolean) {
    /** The statement's first word, upper-cased. */
    def keyword: String = text.trim.takeWhile(c => c.isLetter || c == '\\').toUpperCase(Locale.ROOT)
    /** A statement no port carries out: a psql meta-command, or a setting. */
    def setting: Boolean = meta || keyword == "SET" || keyword == "RESET"
  }

  /** A `.sql` file cut into statements as psql cuts it, and the lines psql
    * echoes as it reads them. */
  final case class SqlFile(lines: Vector[String], statements: Vector[SqlStatement], echoed: Vector[Int]) {
    /** The statement whose lines hold `line`, the first if two share it, or
      * the one after it when only comments and blank lines come between. */
    def at(line: Int): Option[SqlStatement] =
      statements.find(s => s.first <= line && line <= s.last).orElse {
        statements.find(_.first > line).filter(s => (line until s.first).forall { l =>
          val t = lines(l - 1).trim
          t.isEmpty || t.startsWith("--")
        })
      }
  }

  private val CopyFromStdin = """(?is)\s*COPY\b.*\bFROM\s+STDIN\b.*""".r
  private val SetStdStrings =
    """(?is)\s*SET\s+(?:SESSION\s+|LOCAL\s+)?standard_conforming_strings\s*(?:TO|=)\s*'?(on|off|true|false)'?\s*;?\s*""".r
  private val ResetStdStrings = """(?is)\s*RESET\s+standard_conforming_strings\s*;?\s*""".r
  /** The meta-commands that send the statement before them, ending it. */
  private val SendingCommands = Set("g", "gx", "gset", "gexec", "gdesc", "crosstabview", "watch")

  /** `text` cut into statements as psql's lexer cuts it: a statement ends at
    * a `;` outside quotes, comments and parentheses; a `\` outside them
    * starts a meta-command, which runs to the end of its line; the rows a
    * `COPY ... FROM STDIN` reads follow it, up to `\.`, and are not echoed.
    * Plain strings take backslash escapes while `standard_conforming_strings`
    * is off. Empty lines are echoed only inside a quote or a comment. */
  def splitSql(text: String): SqlFile = {
    val lines = text.split("\n", -1).toVector match {
      case init :+ "" => init
      case whole => whole
    }
    val statements = Vector.newBuilder[SqlStatement]
    val echoed = Vector.newBuilder[Int]
    var stdStrings = true
    // The lexer's state as a line begins: inside a block comment (its
    // depth), a quote (its closing text and whether backslashes escape), or
    // neither.
    var commentDepth = 0
    var quote: Option[(String, Boolean)] = None
    var parens = 0
    val buf = new StringBuilder
    var first = -1
    var copyData = false
    def end(lastLine: Int): Unit = {
      val t = buf.result()
      statements += SqlStatement(0, first, lastLine, t, meta = false)
      t match {
        case SetStdStrings(v) => stdStrings = v.equalsIgnoreCase("on") || v.equalsIgnoreCase("true")
        case ResetStdStrings() => stdStrings = true
        case _ => ()
      }
      if (CopyFromStdin.matches(t)) copyData = true
      buf.clear()
      first = -1
      parens = 0
    }
    var ln = 0
    while (ln < lines.length) {
      val line = lines(ln)
      val no = ln + 1
      if (copyData) {
        if (line == "\\.") copyData = false
      } else {
        val inQuote = commentDepth > 0 || quote.isDefined
        if (line.nonEmpty || inQuote) echoed += no
        var i = 0
        var stop = false
        while (i < line.length && !stop) {
          val c = line(i)
          if (commentDepth > 0) {
            if (line.startsWith("*/", i)) { commentDepth -= 1; i += 2 }
            else if (line.startsWith("/*", i)) { commentDepth += 1; i += 2 }
            else i += 1
          } else quote match {
            case Some((close, escapes)) =>
              if (escapes && c == '\\') { buf += c; if (i + 1 < line.length) buf += line(i + 1); i += 2 }
              else if (line.startsWith(close, i)) {
                if (close == "'" && line.startsWith("''", i)) { buf ++= "''"; i += 2 }
                else if (close == "\"" && line.startsWith("\"\"", i)) { buf ++= "\"\""; i += 2 }
                else { buf ++= close; i += close.length; quote = None }
              } else { buf += c; i += 1 }
            case None =>
              if (line.startsWith("--", i)) stop = true
              else if (line.startsWith("/*", i)) { commentDepth = 1; i += 2 }
              else if (c == '\\') {
                // A meta-command: its name, then arguments to the line's end.
                val name = line.substring(i + 1).takeWhile(ch => ch.isLetter) match {
                  case "" => line.substring(i + 1, math.min(i + 2, line.length))
                  case w => w
                }
                if (first >= 0 && SendingCommands.contains(name)) end(no)
                else statements += SqlStatement(0, no, no, line.substring(i), meta = true)
                stop = true
              } else if (c.isWhitespace) { if (first >= 0) buf += c; i += 1 }
              else {
                if (first < 0) first = no
                if (c == '\'') {
                  // E'...' takes backslash escapes, and so does a plain '...'
                  // while standard_conforming_strings is off; U&'...', B'...'
                  // and X'...' never do.
                  val prefix = line.substring(0, i).reverse.takeWhile(ch => isIdent(ch) || ch == '&').reverse
                  val escapes = prefix.toUpperCase(Locale.ROOT) match {
                    case "E" => true
                    case "U&" | "B" | "X" => false
                    case _ => !stdStrings
                  }
                  quote = Some(("'", escapes)); buf += c; i += 1
                } else if (c == '"') { quote = Some(("\"", false)); buf += c; i += 1 }
                else if (c == '$' && !(i > 0 && isIdent(line(i - 1)))) {
                  val tag = line.substring(i + 1).takeWhile(ch => ch.isLetterOrDigit || ch == '_')
                  if (tag.headOption.forall(h => !h.isDigit) && line.startsWith("$" + tag + "$", i)) {
                    val delim = "$" + tag + "$"
                    quote = Some((delim, false)); buf ++= delim; i += delim.length
                  } else { buf += c; i += 1 }
                } else if (c == '(') { parens += 1; buf += c; i += 1 }
                else if (c == ')') { if (parens > 0) parens -= 1; buf += c; i += 1 }
                else if (c == ';' && parens == 0) { buf += c; i += 1; end(no) }
                else { buf += c; i += 1 }
              }
          }
        }
        if (first >= 0) buf += '\n'
      }
      ln += 1
    }
    val all = statements.result().zipWithIndex.map { case (s, k) => s.copy(index = k) }
    SqlFile(lines, all, echoed.result())
  }

  private def isIdent(c: Char): Boolean = c.isLetterOrDigit || c == '_' || c == '$'

  // ---- the .out file ---------------------------------------------------------

  /** One table psql printed: its column names and each row's cells, a cell's
    * text with psql's margins and padding still on it (trimmed per column
    * when it is read, [[cellText]]). */
  final case class Table(header: Vector[String], rows: Vector[Vector[String]])

  /** What psql printed after one statement: its tables, its errors' first
    * lines, how NULL printed at the time, and the first line of the output
    * this reader could not explain, if any. */
  final case class Output(tables: Vector[Table], errors: Vector[String], nullDisplay: String,
      unexplained: Option[String])

  final class OutError(msg: String) extends RuntimeException(msg)

  private val Footer = """\((\d+) rows?\)""".r
  private val Separator = """-+(\+-+)*""".r
  private val Diagnostic = """(NOTICE|WARNING|INFO|DETAIL|HINT|CONTEXT|QUERY|LINE \d+|STATEMENT):.*""".r

  /** What psql printed for each statement of `sql`, read from `out`, the
    * `.out` file `name`, by statement index. Fails, naming the line, where
    * the echo of the `.sql` file is not where it should be; output it cannot
    * read is the statement's [[Output.unexplained]]. */
  def readOutput(name: String, sql: SqlFile, out: String): Map[Int, Output] = {
    val lines = out.split("\n", -1).toVector match {
      case init :+ "" => init
      case whole => whole
    }
    val endingAt = sql.statements.groupBy(_.last)
    val result = mutable.LinkedHashMap.empty[Int, Output]
    var nullDisplay = ""
    var o = 0
    def fail(msg: String): Nothing = throw new OutError(s"$name:${o + 1}: $msg")
    sql.echoed.indices.foreach { k =>
      val no = sql.echoed(k)
      if (o >= lines.length) fail(s"the output ends before the echo of line $no of the .sql file")
      if (lines(o) != sql.lines(no - 1))
        fail(s"expected the echo of line $no of the .sql file, [${sql.lines(no - 1)}], found [${lines(o)}]")
      o += 1
      endingAt.get(no).foreach { stmts =>
        val next = sql.echoed.lift(k + 1).map(n => sql.lines(n - 1))
        val blocks = mutable.ArrayBuffer.empty[Either[String, Table]]
        var unexplained: Option[String] = None
        // A table of no columns: `--`, which a comment's echo can be too,
        // then its row count.
        def noColumns(k: Int): Option[Int] =
          if (lines(k) == "--" && k + 2 < lines.length && lines(k + 2).isEmpty)
            lines(k + 1) match { case Footer(n) => Some(n.toInt); case _ => None }
          else None
        // Read blocks until the next echoed line, which no block's own line
        // is: a table is read whole, header to blank line.
        while (o < lines.length && (!next.contains(lines(o)) || noColumns(o).isDefined)) {
          val l = lines(o)
          if (noColumns(o).isDefined) {
            blocks += Right(Table(Vector.empty, Vector.fill(noColumns(o).get)(Vector.empty)))
            o += 3
          } else if (l.startsWith("ERROR:")) { blocks += Left(l.stripPrefix("ERROR:").trim); o += 1 }
          else if (Diagnostic.matches(l)) o += 1
          else if (l.nonEmpty && l.trim == "^") o += 1
          else if (o + 1 < lines.length && Separator.matches(lines(o + 1))) readTable(lines, o, next) match {
            case Right((t, after)) => blocks += Right(t); o = after
            case Left(why) => unexplained = unexplained.orElse(Some(s"$name:${o + 1}: $why")); o += 1
          }
          // An error's message, or its DETAIL, over several lines.
          else if (blocks.lastOption.exists(_.isLeft) && l.nonEmpty) o += 1
          else { unexplained = unexplained.orElse(Some(s"$name:${o + 1}: [$l]")); o += 1 }
        }
        if (o >= lines.length && next.isDefined) fail(s"the echo of the next line, [${next.get}], never comes")
        val tables = blocks.collect { case Right(t) => t }.toVector
        val errors = blocks.collect { case Left(e) => e }.toVector
        // Statements that end on one line share what follows it: each
        // query's table in turn, the errors with the first that has no table.
        if (stmts.length == 1) result(stmts.head.index) = Output(tables, errors, nullDisplay, unexplained)
        else {
          var t = 0
          var errorsTaken = false
          stmts.foreach { s =>
            val isQuery = Set("SELECT", "VALUES", "WITH", "TABLE").contains(s.keyword)
            val mine = if (isQuery && t < tables.length) { t += 1; Vector(tables(t - 1)) } else Vector.empty
            val errs = if (mine.isEmpty && isQuery && !errorsTaken) { errorsTaken = true; errors } else Vector.empty
            result(s.index) = Output(mine, errs, nullDisplay, unexplained)
          }
          if (t != tables.length) fail(s"${tables.length} tables follow the ${stmts.length} statements ending on line $no")
        }
      }
      // A meta-command takes effect for the statements after it.
      endingAt.get(no).toVector.flatten.filter(_.meta).foreach { s =>
        PsetNull.findFirstMatchIn(s.text).foreach(m => nullDisplay = unquotePsql(m.group(1)))
      }
    }
    if (o < lines.length) fail(s"output after the last statement: [${lines(o)}]")
    result.toMap
  }

  private val PsetNull = """^\\pset\s+null\s+(.*)$""".r

  /** A psql meta-command's argument: in single quotes, `''` is a quote and
    * a backslash escapes the character after it. */
  private def unquotePsql(arg: String): String = {
    val a = arg.trim
    if (!a.startsWith("'")) a.takeWhile(c => !c.isWhitespace)
    else {
      val sb = new StringBuilder
      var i = 1
      var done = false
      while (i < a.length && !done) {
        val c = a(i)
        if (c == '\'' && a.startsWith("''", i)) { sb += '\''; i += 2 }
        else if (c == '\'') done = true
        else if (c == '\\' && i + 1 < a.length) {
          sb += (a(i + 1) match { case 'n' => '\n'; case 't' => '\t'; case other => other }); i += 2
        } else { sb += c; i += 1 }
      }
      sb.result()
    }
  }

  /** The aligned table whose header is at `start`, or why it is none: the
    * `+`s of the line under the header bound the columns, each cell a
    * one-space margin either side of its text (psql leaves off the last
    * column's padding and right margin), a cell that goes on to the next
    * line holding `+` in its right margin. Returns the table and the index
    * past the blank line after it. A row equal to `next`, the next echoed
    * line, means the table never ended. */
  private def readTable(lines: Vector[String], start: Int, next: Option[String]): Either[String, (Table, Int)] = {
    val sep = lines(start + 1)
    val bounds = (sep.indices.filter(sep(_) == '+') :+ sep.length).toVector
    val starts = 0 +: bounds.init.map(_ + 1)
    def cells(l: String): Vector[String] = starts.indices.map { c =>
      l.substring(math.min(starts(c) + 1, l.length), math.min(bounds(c) - 1, l.length))
    }.toVector
    def continues(l: String): Vector[Boolean] = bounds.map(b => b - 1 < l.length && l(b - 1) == '+')
    val header = cells(lines(start)).map(_.trim)
    val rows = Vector.newBuilder[Vector[String]]
    var i = start + 2
    var count = 0
    while (i < lines.length && !Footer.matches(lines(i))) {
      if (next.contains(lines(i)) || lines(i).isEmpty) return Left(s"the table at line ${start + 1} has no (N rows) line")
      var row = cells(lines(i))
      var more = continues(lines(i))
      while (more.exists(identity)) {
        i += 1
        if (i >= lines.length) return Left(s"a cell of the table at line ${start + 1} goes on past the file's end")
        val cont = cells(lines(i))
        row = row.indices.map(c => if (more(c)) rtrim(row(c)) + "\n" + cont(c) else row(c)).toVector
        more = continues(lines(i))
      }
      rows += row
      count += 1
      i += 1
    }
    if (i >= lines.length) Left(s"the table at line ${start + 1} has no (N rows) line")
    else {
      val Footer(n) = lines(i): @unchecked
      if (n.toInt != count) Left(s"the table at line ${start + 1} says ($n rows) and holds $count")
      else if (i + 1 >= lines.length || lines(i + 1).nonEmpty) Left(s"no blank line after the table at line ${start + 1}")
      else Right((Table(header, rows.result()), i + 2))
    }
  }

  // ---- the script --------------------------------------------------------------

  private val Citation = """^#\s+([A-Za-z0-9_]+)\.sql:(\d+)(?:-(\d+))?""".r.unanchored
  private val SourceName = """src/test/regress/sql/([A-Za-z0-9_]+)\.sql""".r.unanchored

  /** A record and the statement Postgres ran for it. */
  final case class Placed(record: Record, statement: SqlStatement, cited: Boolean)

  /** The source a port's header names. */
  def sourceName(script: String): Option[String] =
    script.split("\n").iterator.takeWhile(_.startsWith("#")).collectFirst { case SourceName(n) => n }

  /** Each record of `script` that holds Postgres's answer, with the
    * statement it comes from; problems for those it cannot place. */
  def place(scriptName: String, script: String, records: Vector[Record], name: String,
      sql: SqlFile): (Vector[Placed], Vector[String]) = {
    val lines = script.split("\n", -1).toVector
    val placed = Vector.newBuilder[Placed]
    val problems = Vector.newBuilder[String]
    var prev: Option[SqlStatement] = None
    records.indices.foreach { i =>
      val rec = records(i)
      val twin = rec.onlyRule.isDefined && i > 0 && records(i - 1).skipRule == rec.onlyRule &&
        Script.sqlOf(records(i - 1)) == Script.sqlOf(rec)
      val checked = rec match {
        case _: Query => true
        case s: Statement => s.expectError
        case _ => false
      }
      if (!twin && Script.sqlOf(rec).isDefined) {
        // The comment lines just above the record and its conditions.
        var k = rec.line - 2 - rec.conditions.length
        val comment = mutable.ArrayBuffer.empty[String]
        while (k >= 0 && lines(k).startsWith("#")) { comment.prepend(lines(k)); k -= 1 }
        val citation = comment.reverseIterator.collectFirst { case Citation(f, n, _) => (f, n.toInt) }
        citation match {
          case Some((file, _)) if file != name => ()
          case Some((_, n)) =>
            sql.at(n) match {
              case Some(s) =>
                placed += Placed(rec, s, cited = true)
                prev = Some(s)
              case None =>
                problems += s"$scriptName:${rec.line}: it cites $name.sql:$n, which is no statement's line"
            }
          // A record under a comment that cites no line is the port's own (a
          // table it makes for a VALUES list), and so is any after it with no
          // comment, up to the next citation.
          case None if comment.nonEmpty && !checked => prev = None
          case None =>
            val next = prev.flatMap(p => sql.statements.drop(p.index + 1).find(!_.setting))
            next match {
              case Some(s) =>
                placed += Placed(rec, s, cited = false)
                prev = Some(s)
              case None if checked =>
                problems += s"$scriptName:${rec.line}: it cites no line of $name.sql, and follows no record that does"
              case None => ()
            }
        }
      }
    }
    (placed.result(), problems.result())
  }

  // ---- printing Postgres's values ------------------------------------------------

  /** The text of a cell as a column of type `column` holds it: a number is
    * right-aligned, so its padding is on the left; text is left-aligned. */
  private def cellText(cell: String, column: Column): String = column match {
    case Column.Exact(DataType.StringType) => rtrim(cell)
    // A `T` column holds text or a number, which psql aligns to the right.
    case Column.Text => if (number(cell.trim).isDefined) cell.trim else rtrim(cell)
    case _ => cell.trim
  }

  private def rtrim(s: String): String = {
    var e = s.length
    while (e > 0 && s(e - 1) == ' ') e -= 1
    s.substring(0, e)
  }

  /** The ways the record may print a cell of Postgres's, the first the one
    * [[fill]] writes. More than one where psql's text does not say which: an
    * empty text cell is NULL or the empty string under the default
    * `\pset null`, and a number in a `T` column prints as text or as a double. */
  def printings(cell: String, column: Column, nullDisplay: String): Vector[String] = {
    val text = cellText(cell, column)
    val isText = column == Column.Text || column == Column.Exact(DataType.StringType)
    if (text == nullDisplay && nullDisplay.nonEmpty) Vector("NULL")
    else if (text.isEmpty && nullDisplay.isEmpty) {
      if (isText) Vector("NULL", "(empty)") else Vector("NULL")
    } else column match {
      case Column.Text =>
        val asText = Values.format(text, Column.Text)
        number(text).map(d => Values.format(java.lang.Double.valueOf(d), Column.Text)).filter(_ != asText) match {
          case Some(asDouble) => Vector(asText, asDouble)
          case None => Vector(asText)
        }
      case Column.Integer => Vector(numeric(text).map(v => Values.format(v, column)).getOrElse(text))
      // A number's decimal text rounded to three places, as the record
      // writes Postgres's value: through a double, a numeric past 2^53 or
      // with more digits than a double holds would print another value.
      case Column.Real => Vector(decimal(text) match {
        case Some(d) =>
          val r = d.setScale(3, java.math.RoundingMode.HALF_UP)
          if (r.signum == 0 && text.trim.startsWith("-")) "-" + r.toPlainString else r.toPlainString
        case None => numeric(text).map(v => Values.format(v, column)).getOrElse(text)
      })
      case Column.Exact(t) => Vector(exact(text, t))
    }
  }

  /** A cell as an exact column of type `t` prints it, or the cell as it is
    * when it is no value of the type (which then differs from the record's). */
  private def exact(text: String, t: DataType): String = t match {
    case DataType.IntType | DataType.LongType =>
      decimal(text).filter(d => d.signum == 0 || d.stripTrailingZeros.scale <= 0).map(_.toBigInteger.toString).getOrElse(text)
    case DataType.DoubleType => number(text).map(d => java.lang.Double.toString(d)).getOrElse(text)
    case DataType.FloatType =>
      floatSpecial(text).orElse(decimal(text).map(_ => text.toFloat)).map(f => java.lang.Float.toString(f)).getOrElse(text)
    case DataType.DecimalType(_, s) =>
      decimal(text).flatMap { d =>
        try Some(d.setScale(s, java.math.RoundingMode.UNNECESSARY).toPlainString)
        catch { case _: ArithmeticException => None }
      }.getOrElse(text)
    case DataType.BooleanType => text match { case "t" => "true"; case "f" => "false"; case other => other }
    case DataType.DateType => date(text).map(_.toString).getOrElse(text)
    case DataType.StringType => Values.exact(text)
    case _ => text
  }

  private def decimal(text: String): Option[JBigDecimal] =
    try Some(new JBigDecimal(text)) catch { case _: NumberFormatException => None }

  /** A float8's text as the double it reads as. */
  private def number(text: String): Option[Double] = text match {
    case "Infinity" => Some(Double.PositiveInfinity)
    case "-Infinity" => Some(Double.NegativeInfinity)
    case "NaN" => Some(Double.NaN)
    case _ => decimal(text).map(_ => text.toDouble)
  }

  private def floatSpecial(text: String): Option[Float] = text match {
    case "Infinity" => Some(Float.PositiveInfinity)
    case "-Infinity" => Some(Float.NegativeInfinity)
    case "NaN" => Some(Float.NaN)
    case _ => None
  }

  /** A cell as `I` and `R` read it: a number, or a boolean as 1 or 0. */
  private def numeric(text: String): Option[Any] = text match {
    case "t" => Some(java.lang.Boolean.TRUE)
    case "f" => Some(java.lang.Boolean.FALSE)
    case _ => number(text).map(d => if (d.isNaN || d.isInfinite) java.lang.Double.valueOf(d) else new JBigDecimal(text))
  }

  private val IsoDate = """(\d{4,})-(\d{2})-(\d{2})( BC)?""".r
  private val MdyDate = """(\d{2})-(\d{2})-(\d{4,})( BC)?""".r

  /** A date as Postgres prints it, ISO or `MM-DD-YYYY` (the regression
    * suite's `Postgres, MDY` style), `BC` after a year before 1. */
  private def date(text: String): Option[LocalDate] = {
    def of(y: String, m: String, d: String, bc: String): Option[LocalDate] =
      try Some(LocalDate.of(if (bc == null) y.toInt else 1 - y.toInt, m.toInt, d.toInt))
      catch { case _: java.time.DateTimeException => None }
    text match {
      case IsoDate(y, m, d, bc) => of(y, m, d, bc)
      case MdyDate(m, d, y, bc) => of(y, m, d, bc)
      case _ => None
    }
  }

  // ---- comparing ------------------------------------------------------------------

  /** Where `q`'s expected block differs from Postgres's `table`, or None. */
  def compare(q: Query, table: Table, nullDisplay: String): Option[String] = {
    val n = q.columns.length
    if (table.header.length != n) return Some(s"Postgres printed ${table.header.length} columns, the record has $n")
    val pg = table.rows.map(r => r.indices.map(c => printings(r(c), q.columns(c), nullDisplay)).toVector)
    q.expected match {
      case Expected.Unchecked => Some("the record has no ---- block, and Postgres printed a result")
      case Expected.Hash(count, md5) =>
        val printed = Values.arrange(pg.map(_.map(_.head)), q.sort)
        val got = Values.hashLine(printed)
        if (got == s"$count values hashing to $md5") None
        else Some(s"the record says $count values hashing to $md5, Postgres's values hash as $got")
      case Expected.Values(values) =>
        if (n == 0 || values.length % n != 0) return Some(s"the record's ${values.length} values are not rows of $n")
        val mine = values.grouped(n).map(_.toVector).toVector
        if (mine.length != pg.length)
          return Some(s"the record has ${mine.length} rows, Postgres printed ${pg.length}: " +
            s"[${pg.map(_.map(_.head).mkString(" ")).take(8).mkString(" | ")}${if (pg.length > 8) " | ..." else ""}]")
        q.sort match {
          case SortMode.NoSort =>
            mine.indices.iterator.flatMap(r => (0 until n).iterator.map(c => (r, c)))
              .collectFirst { case (r, c) if !pg(r)(c).contains(mine(r)(c)) =>
                s"row ${r + 1}, column ${c + 1}: the record has [${mine(r)(c)}] where Postgres printed " +
                  s"[${pg(r)(c).mkString("] or [")}]"
              }
          case SortMode.RowSort => matchRows(mine, pg)
          case SortMode.ValueSort => matchRows(mine.flatten.map(Vector(_)), pg.flatten.map(Vector(_)))
        }
    }
  }

  /** The first of `mine`'s rows no row of `pg`'s, each used once, prints as. */
  private def matchRows(mine: Vector[Vector[String]], pg: Vector[Vector[Vector[String]]]): Option[String] = {
    val used = new Array[Boolean](pg.length)
    mine.iterator.map { row =>
      val k = pg.indices.find(j => !used(j) && row.indices.forall(c => pg(j)(c).contains(row(c))))
      k.foreach(used(_) = true)
      row -> k
    }.collectFirst { case (row, None) =>
      val left = pg.indices.filterNot(used(_)).map(j => pg(j).map(_.head).mkString("\t"))
      s"the record's row [${row.mkString("\t")}] is no row Postgres printed; of Postgres's rows none matched yet " +
        s"are [${left.take(4).mkString("] [")}]${if (left.length > 4) " ..." else ""}"
    }
  }

  // ---- the check -----------------------------------------------------------------

  /** The source and output a port at `scriptPath` is checked against. */
  final case class Source(name: String, sql: SqlFile, output: Map[Int, Output])

  def source(scriptPath: Path, script: String): Source = {
    val name = sourceName(script).getOrElse(throw new IllegalArgumentException(
      s"$scriptPath: the header names no src/test/regress/sql/<name>.sql"))
    val dir = scriptPath.toAbsolutePath.getParent
    val sqlPath = dir.resolve(s"sql/$name.sql")
    val outPath = dir.resolve(s"expected/$name.out")
    val sql = splitSql(read(sqlPath))
    Source(name, sql, readOutput(outPath.toString, sql, read(outPath)))
  }

  /** Every place the port at `scriptPath` (named `scriptName`) says other
    * than Postgres printed. */
  def check(scriptName: String, scriptPath: Path, ledger: Ledger): Vector[String] = {
    val script = read(scriptPath)
    val records = Script.parse(scriptName, script, ledger)
    val src = source(scriptPath, script)
    val (placed, problems) = place(scriptName, script, records, src.name, src.sql)
    problems ++ placed.flatMap(p => problem(p, src).map(m =>
      s"$scriptName:${p.record.line} (${src.name}.sql:${p.statement.first}): $m"))
  }

  /** How many queries and statement errors of the port at `scriptPath` the
    * check placed, and how many records it has that are no twin. */
  def coverage(scriptName: String, scriptPath: Path, ledger: Ledger): (Int, Int, Int) = {
    val script = read(scriptPath)
    val records = Script.parse(scriptName, script, ledger)
    val src = source(scriptPath, script)
    val (placed, _) = place(scriptName, script, records, src.name, src.sql)
    val answers = records.indices.count { i =>
      val r = records(i)
      val twin = r.onlyRule.isDefined && i > 0 && records(i - 1).skipRule == r.onlyRule
      !twin && (r match { case _: Query => true; case st: Statement => st.expectError; case _ => false })
    }
    (placed.count(_.record.isInstanceOf[Query]),
      placed.count(p => p.record match { case st: Statement => st.expectError; case _ => false }), answers)
  }

  /** What a placed record says otherwise than Postgres's output, or None. */
  private def problem(p: Placed, src: Source): Option[String] = {
    val out = src.output.get(p.statement.index)
    def ran = out.exists(_.errors.isEmpty)
    def errorText = out.flatMap(_.errors.headOption).getOrElse("")
    val checked = p.record match {
      case _: Query => true
      case s: Statement => s.expectError || p.cited
      case _ => false
    }
    if (checked && out.exists(_.unexplained.isDefined))
      return Some(s"psql printed what this checker cannot read for the statement, at ${out.get.unexplained.get}")
    p.record match {
      case s: Statement if s.expectError =>
        if (ran) Some(s"the record expects an error, but Postgres ran the statement" +
          out.flatMap(_.tables.headOption).map(t => s" and printed ${t.rows.length} rows").getOrElse(""))
        else None
      case _: Statement =>
        if (p.cited && !ran) Some(s"Postgres refused the statement: ERROR: $errorText") else None
      case q: Query =>
        val isQuery = Set("SELECT", "VALUES", "WITH", "TABLE").contains(p.statement.keyword) || p.statement.text.trim.startsWith("(")
        if (!isQuery) Some(s"the record is a query, and ${src.name}.sql:${p.statement.first} is a ${p.statement.keyword}")
        else if (!ran) Some(s"Postgres refused the query: ERROR: $errorText")
        else out.get.tables match {
          case Vector(t) => compare(q, t, out.get.nullDisplay)
          case ts => Some(s"Postgres printed ${ts.length} tables for the statement")
        }
      case _ => None
    }
  }

  /** `script` with each placed query's `----` block holding Postgres's values
    * as its columns print them, sorted as its sort mode says; a hashed
    * block stays hashed. For porting: the blocks are then checked. */
  def fill(scriptName: String, scriptPath: Path, ledger: Ledger): (String, Vector[String]) = {
    val script = read(scriptPath)
    val lines = script.split("\n", -1).toVector
    val records = Script.parse(scriptName, script, ledger)
    val src = source(scriptPath, script)
    val (placed, problems) = place(scriptName, script, records, src.name, src.sql)
    val out = Vector.newBuilder[String]
    val notes = Vector.newBuilder[String] ++= problems
    var copied = 0
    placed.foreach {
      case Placed(q: Query, stmt, _) =>
        src.output.get(stmt.index) match {
          case Some(o) if o.errors.isEmpty && o.tables.length == 1 && o.tables.head.header.length == q.columns.length =>
            val t = o.tables.head
            val pg = t.rows.map(r => r.indices.map(c => printings(r(c), q.columns(c), o.nullDisplay)).toVector)
            if (pg.exists(_.exists(_.length > 1)))
              notes += s"$scriptName:${q.line}: a cell prints more than one way; check the block by hand"
            val printed = Values.arrangeRows(pg.map(_.map(_.head)), q.sort)
            out ++= lines.slice(copied, q.sqlEnd)
            out += "----"
            q.expected match {
              case Expected.Hash(_, _) => out += Values.hashLine(printed.flatten)
              case _ => out ++= printed.map(_.mkString("\t"))
            }
            copied = q.end
          case Some(o) =>
            notes += s"$scriptName:${q.line} (${src.name}.sql:${stmt.first}): Postgres printed " +
              (if (o.errors.nonEmpty) s"ERROR: ${o.errors.head}" else s"${o.tables.length} tables, " +
                o.tables.headOption.map(t => s"the first of ${t.header.length} columns").getOrElse(""))
          case None => notes += s"$scriptName:${q.line}: no output for ${src.name}.sql:${stmt.first}"
        }
      case _ => ()
    }
    out ++= lines.drop(copied)
    (out.result().mkString("\n"), notes.result())
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  /** Each statement of the port's source, by its first line, with whether a
    * record of the port holds it: what a header counts as left out. */
  def listing(scriptName: String, scriptPath: Path, ledger: Ledger): Vector[String] = {
    val script = read(scriptPath)
    val records = Script.parse(scriptName, script, ledger)
    val src = source(scriptPath, script)
    val (placed, _) = place(scriptName, script, records, src.name, src.sql)
    val held = placed.map(_.statement.index).toSet
    s"${src.sql.statements.length} statements, ${held.size} held by a record" +:
      src.sql.statements.map { st =>
        f"${if (held(st.index)) "+" else "-"} ${st.first}%5d  ${st.text.trim.linesIterator.nextOption().getOrElse("").take(100)}"
      }
  }

  /** {{{
    *   bazel run //src/test/scala/com/transformer/slt:pg_expected -- [--fill | --list] <script>...
    * }}}
    * Checks each port and prints every difference, exiting 1 if there is
    * one; `--fill` writes Postgres's values into each query's block instead,
    * and prints what it could not fill; `--list` prints each statement of the
    * source, `+` where a record holds it. */
  def main(args: Array[String]): Unit = {
    val ledger = Ledger.load(resolve(Ledger.DefaultPath.toString))
    val fillMode = args.contains("--fill")
    val listMode = args.contains("--list")
    val scripts = args.filterNot(a => a == "--fill" || a == "--list").toVector
    var differences = 0
    scripts.foreach { s =>
      val path = resolve(s)
      if (listMode) listing(s, path, ledger).foreach(l => System.out.println(l))
      else if (fillMode) {
        val (text, notes) = fill(s, path, ledger)
        Files.write(path, text.getBytes(StandardCharsets.UTF_8))
        notes.foreach(n => System.out.println(n))
      } else {
        val problems = check(s, path, ledger)
        problems.foreach(p => System.out.println(p))
        differences += problems.length
        val (queries, errors, of) = coverage(s, path, ledger)
        System.out.println(s"$s: $queries queries and $errors statement errors checked of $of records with " +
          s"Postgres's answers; ${if (problems.isEmpty) "every answer is Postgres's" else s"${problems.length} differences"}")
      }
    }
    if (differences > 0) sys.exit(1)
  }

  /** A path given on the command line: under `bazel run` a relative one is
    * relative to the workspace, not the runfiles directory it runs in. */
  private def resolve(p: String): Path = {
    val path = Paths.get(p)
    if (path.isAbsolute) path
    else Option(System.getenv("BUILD_WORKSPACE_DIRECTORY")).map(Paths.get(_).resolve(path)).getOrElse(path)
  }
}
