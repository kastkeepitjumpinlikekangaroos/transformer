package com.transformer.slt

import com.transformer.core.DataType

import java.util.Locale

/** A sqllogictest script: SQLite's test format, as DuckDB extends it, read
  * into records.
  *
  * A script is records separated by blank lines. Lines starting with `#` are
  * comments. A record may be preceded by `skipif <engine>` / `onlyif <engine>`
  * lines, which say which engines run it; this engine is `transformer`.
  *
  * {{{
  *   statement ok
  *   CREATE TABLE t (a INT, b STRING)
  *
  *   statement error
  *   SELECT a FROM t GROUP BY b
  *   ----
  *   must appear in GROUP BY
  *
  *   query IT rowsort
  *   SELECT a, b FROM t
  *   ----
  *   1	x
  *   2	NULL
  * }}}
  *
  *   - `statement ok` runs a statement that must succeed, `statement error`
  *     one that must fail; an error record may name, after `----`, text the
  *     error's message must contain (DuckDB's extension).
  *   - `query <columns> [nosort | rowsort | valuesort] [<label>]` runs a
  *     query and compares its result, after `----`, with the expected values:
  *     one value per line (SQLite's layout) or one row per line with its
  *     values separated by tabs (DuckDB's), or `N values hashing to <md5>`.
  *     `<columns>` is SQLite's letters, one per column: `I` an integer, `R` a
  *     real printed with three decimals, `T` text. This runner also takes a
  *     comma-separated list where each column is a letter or an exact type
  *     (`INT`, `BIGINT`, `FLOAT`, `DOUBLE`, `DECIMAL(p,s)`, `STRING`,
  *     `BOOLEAN`, `DATE`, `TIMESTAMP`, `BINARY`), which the result's column
  *     must have and which prints it exactly (see [[Values]]).
  *   - `hash-threshold N` hashes any result of more than N values; `halt`
  *     stops the script.
  */
object Script {

  /** The name this engine answers to in `skipif` and `onlyif`. */
  val EngineName: String = "transformer"

  final case class Condition(skip: Boolean, engine: String) {
    /** Whether this condition lets the record run on [[EngineName]]. */
    def admits: Boolean = if (skip) !engine.equalsIgnoreCase(EngineName) else engine.equalsIgnoreCase(EngineName)
  }

  sealed trait SortMode
  object SortMode {
    case object NoSort extends SortMode
    case object RowSort extends SortMode
    case object ValueSort extends SortMode
  }

  /** How one result column is checked and printed. */
  sealed trait Column
  object Column {
    /** SQLite's `I`: the value as a 32-bit integer, as `sqlite3_column_int` reads it. */
    case object Integer extends Column
    /** SQLite's `R`: the value as a double, printed `%.3f`. */
    case object Real extends Column
    /** SQLite's `T`: the value as text. */
    case object Text extends Column
    /** A column that must have this type, printed exactly. */
    final case class Exact(dataType: DataType) extends Column
  }

  sealed trait Record {
    def line: Int
    def conditions: Seq[Condition]
    def runs: Boolean = conditions.forall(_.admits)
  }

  final case class Statement(line: Int, conditions: Seq[Condition], sql: String, expectError: Boolean,
      errorText: Option[String]) extends Record

  /** `sqlEnd` and `end` are the 0-based indices of the line after the SQL
    * and of the line after the record, which [[Runner.complete]] rewrites
    * between. */
  final case class Query(line: Int, conditions: Seq[Condition], sql: String, columns: Seq[Column],
      sort: SortMode, label: Option[String], expected: Expected, sqlEnd: Int, end: Int) extends Record

  final case class HashThreshold(line: Int, n: Int) extends Record { def conditions: Seq[Condition] = Nil }

  final case class Halt(line: Int, conditions: Seq[Condition]) extends Record

  /** What a query's `----` block says. */
  sealed trait Expected
  object Expected {
    /** The values in order, each line split at tabs. */
    final case class Values(values: Seq[String]) extends Expected
    /** `N values hashing to H`. */
    final case class Hash(count: Int, md5: String) extends Expected
    /** No `----` block: the result is not checked, only that the query runs. */
    case object Unchecked extends Expected
  }

  private val HashLine = """(\d+) values hashing to ([0-9a-f]{32})""".r

  final class ParseError(file: String, line: Int, msg: String)
    extends RuntimeException(s"$file:$line: $msg")

  def parse(file: String, text: String): Vector[Record] = {
    val lines = text.split("\n", -1).map(l => if (l.endsWith("\r")) l.dropRight(1) else l)
    val out = Vector.newBuilder[Record]
    var i = 0
    def blank(k: Int): Boolean = k >= lines.length || lines(k).trim.isEmpty
    // The lines from `k` up to a blank line or a `----` line, and the index
    // just past them.
    def block(k: Int): (Vector[String], Int) = {
      var j = k
      val b = Vector.newBuilder[String]
      while (!blank(j) && lines(j) != "----") { b += lines(j); j += 1 }
      (b.result(), j)
    }
    def results(k: Int): (Vector[String], Int) = {
      var j = k
      val b = Vector.newBuilder[String]
      while (!blank(j)) { b += lines(j); j += 1 }
      (b.result(), j)
    }
    while (i < lines.length) {
      val raw = lines(i)
      if (raw.trim.isEmpty || raw.trim.startsWith("#")) i += 1
      else {
        val conditions = Vector.newBuilder[Condition]
        var tokens = tokenize(raw)
        while (tokens.headOption.exists(t => t == "skipif" || t == "onlyif")) {
          if (tokens.length < 2) throw new ParseError(file, i + 1, s"'${tokens.head}' names no engine")
          conditions += Condition(tokens.head == "skipif", tokens(1))
          i += 1
          if (i >= lines.length) throw new ParseError(file, i, "a condition with no record after it")
          tokens = tokenize(lines(i))
        }
        val start = i + 1
        tokens.headOption match {
          case Some("statement") =>
            val expectError = tokens.lift(1) match {
              case Some("ok") => false
              case Some("error") => true
              case other => throw new ParseError(file, start, s"statement takes ok or error, not ${other.getOrElse("nothing")}")
            }
            val (sqlLines, after) = block(i + 1)
            if (sqlLines.isEmpty) throw new ParseError(file, start, "a statement with no SQL")
            var next = after
            var errorText: Option[String] = None
            if (next < lines.length && lines(next) == "----") {
              val (msg, end) = results(next + 1)
              if (!expectError) throw new ParseError(file, start, "only a statement error takes a ---- block")
              errorText = Some(msg.mkString("\n"))
              next = end
            }
            out += Statement(start, conditions.result(), sqlLines.mkString("\n"), expectError, errorText)
            i = next
          case Some("query") =>
            if (tokens.length < 2) throw new ParseError(file, start, "a query names no columns")
            val columns = parseColumns(file, start, tokens(1))
            val sort = tokens.lift(2) match {
              case None | Some("nosort") => SortMode.NoSort
              case Some("rowsort") => SortMode.RowSort
              case Some("valuesort") => SortMode.ValueSort
              case Some(other) => throw new ParseError(file, start, s"unknown sort mode '$other'")
            }
            val label = tokens.lift(3)
            val (sqlLines, after) = block(i + 1)
            if (sqlLines.isEmpty) throw new ParseError(file, start, "a query with no SQL")
            var next = after
            var expected: Expected = Expected.Unchecked
            if (next < lines.length && lines(next) == "----") {
              val (vals, end) = results(next + 1)
              expected = vals match {
                case Seq(HashLine(n, h)) => Expected.Hash(n.toInt, h)
                case _ => Expected.Values(vals.flatMap(_.split("\t", -1)))
              }
              next = end
            }
            out += Query(start, conditions.result(), sqlLines.mkString("\n"), columns, sort, label, expected, after, next)
            i = next
          case Some("hash-threshold") =>
            val n = tokens.lift(1).flatMap(s => s.toIntOption)
              .getOrElse(throw new ParseError(file, start, "hash-threshold takes a number"))
            out += HashThreshold(start, n)
            i += 1
          case Some("halt") =>
            out += Halt(start, conditions.result())
            i += 1
          case Some(other) => throw new ParseError(file, start, s"unknown record type '$other'")
          case None => i += 1
        }
      }
    }
    out.result()
  }

  /** The whitespace-separated tokens of a record's first line, up to a `#`. */
  private def tokenize(line: String): Vector[String] =
    line.trim.split("\\s+").toVector.takeWhile(t => !t.startsWith("#")).filter(_.nonEmpty)

  /** `III` (SQLite's letters) or `INT,R,DECIMAL(9,2)` (a column each). */
  def parseColumns(file: String, line: Int, token: String): Vector[Column] =
    if (token.forall(c => c == 'I' || c == 'R' || c == 'T')) token.map(letter).toVector
    else splitTopLevel(token).map { item =>
      if (item.length == 1 && "IRT".contains(item)) letter(item.head)
      else Column.Exact(exactType(item).getOrElse(throw new ParseError(file, line, s"unknown column type '$item'")))
    }

  private def letter(c: Char): Column = c match {
    case 'I' => Column.Integer
    case 'R' => Column.Real
    case _ => Column.Text
  }

  private val DecimalName = """DECIMAL\((\d+),(\d+)\)""".r

  /** The engine type an exact column names, in SQL's spelling. */
  def exactType(name: String): Option[DataType] = name.toUpperCase(Locale.ROOT) match {
    case "INT" => Some(DataType.IntType)
    case "BIGINT" => Some(DataType.LongType)
    case "FLOAT" => Some(DataType.FloatType)
    case "DOUBLE" => Some(DataType.DoubleType)
    case "STRING" => Some(DataType.StringType)
    case "BOOLEAN" => Some(DataType.BooleanType)
    case "DATE" => Some(DataType.DateType)
    case "TIMESTAMP" => Some(DataType.TimestampType)
    case "BINARY" => Some(DataType.BinaryType)
    case "NULL" => Some(DataType.NullType)
    case DecimalName(p, s) => Some(DataType.DecimalType(p.toInt, s.toInt))
    case _ => None
  }

  /** The SQL spelling of a type, as [[exactType]] reads it. */
  def typeName(t: DataType): String = t match {
    case DataType.IntType => "INT"
    case DataType.LongType => "BIGINT"
    case DataType.FloatType => "FLOAT"
    case DataType.DoubleType => "DOUBLE"
    case DataType.StringType => "STRING"
    case DataType.BooleanType => "BOOLEAN"
    case DataType.DateType => "DATE"
    case DataType.TimestampType => "TIMESTAMP"
    case DataType.BinaryType => "BINARY"
    case DataType.NullType => "NULL"
    case DataType.DecimalType(p, s) => s"DECIMAL($p,$s)"
  }

  /** `a,DECIMAL(9,2),b` split at the commas outside parentheses. */
  private def splitTopLevel(s: String): Vector[String] = {
    val out = Vector.newBuilder[String]
    val cur = new StringBuilder
    var depth = 0
    s.foreach {
      case '(' => depth += 1; cur += '('
      case ')' => depth -= 1; cur += ')'
      case ',' if depth == 0 => out += cur.result(); cur.clear()
      case c => cur += c
    }
    out += cur.result()
    out.result()
  }
}
