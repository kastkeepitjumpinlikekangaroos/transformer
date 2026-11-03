package com.transformer.slt

import com.transformer.core.DataType

import java.util.Locale

/** A sqllogictest script: SQLite's test format, as DuckDB extends it, or as
  * CockroachDB and sqllogictest-rs (DataFusion's and RisingWave's runner)
  * write it ([[Script.Format]]), read into records.
  *
  * A script is records separated by blank lines. Lines starting with `#` are
  * comments. A record may be preceded by `skipif <engine>` / `onlyif <engine>`
  * lines, which say which engines run it; this engine is `transformer`, and
  * a condition on it cites a rule of the [[Ledger]] by its id:
  *
  *   - `skipif transformer <id> [# comment]` before another engine's answer
  *     the engine does not give, by that rule. The runner still runs the
  *     record once ([[Runner]]), and it must still diverge as the rule says.
  *   - `onlyif transformer <id> [# comment]` before a record of the engine's
  *     own answer under the rule: in a port, the twin that follows a skip
  *     whose rule is not a refusal, with the same SQL; in a script of this
  *     repo's own, the rule's pin.
  *
  * A condition on transformer with no id, or with one the ledger does not
  * have, is a [[ParseError]]; so is a skip whose rule is not a refusal with no
  * twin after it. A record carries one condition on transformer, and no
  * `onlyif` on another engine beside it, which would keep it from this one
  * anyway. Conditions on other engines keep their free form (`skipif mysql #
  * not compatible`).
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
  *
  * CockroachDB's and sqllogictest-rs's scripts differ from that as
  * [[Format.Cockroach]] and [[Format.DataFusion]] say: an expected error is a regular
  * expression on the record's own line, a result is a row a line with its
  * values separated by spaces, the column letters are their own, and a few
  * more lines are read and set aside. A line either reader does not know
  * fails the parse, naming it, so a file of a later version that writes
  * something new breaks loudly.
  */
object Script {

  /** The name this engine answers to in `skipif` and `onlyif`. */
  val EngineName: String = "transformer"

  /** How a script writes its records, and so how its results print. */
  sealed trait Format {
    /** The engine whose answers a script of the format holds, which a record
      * held from this engine names in `onlyif <name>`: the record is that
      * engine's alone. */
    def upstream: Option[String]
  }

  object Format {
    /** SQLite's format, as DuckDB extends it: this repo's own scripts and the
      * ports of Postgres's and Spark's tests, and SQLite's corpus. */
    case object Sqlite extends Format { def upstream: Option[String] = None }

    /** CockroachDB's logic tests (`pkg/sql/logictest/logic.go`):
      *
      *   - `statement ok` (in any case), `statement count N` and `statement
      *     notice <regex>` run a statement that must succeed (the count and
      *     the notice are not checked); `statement error [pgcode C] <regex>`
      *     and `query error [pgcode C] <regex>` one that must fail with a
      *     message the regular expression finds something in (the SQLSTATE
      *     code is read and set aside).
      *   - `query <letters> [<option>,...] [<label>]`: the options `nosort`,
      *     `rowsort`, `valuesort`, `partialsort(c,...)`, `colnames` (the
      *     result's first line names the columns) and `retry` (set aside);
      *     the letters `T` text, `I` an integer, `F` a float, `R` a decimal,
      *     `B` a boolean, `O` an oid and `_` a column whose values are not
      *     compared.
      *   - A result is its values' words, split at runs of spaces, compared
      *     word by word; `·` is the empty string.
      *   - `subtest <name>` is set aside, and so is a condition on a
      *     configuration, `skipif config <c>` / `onlyif config <c>`: this
      *     engine is none of CockroachDB's configurations, so the first keeps
      *     the record and the second leaves it out.
      */
    case object Cockroach extends Format { def upstream: Option[String] = Some("cockroachdb") }

    /** sqllogictest-rs's format (https://github.com/risinglightdb/sqllogictest-rs),
      * as DataFusion's tests write it:
      *
      *   - `statement ok`, `statement count N` (the count not checked);
      *     `statement error <regex>` and `query error <regex>`, or an error
      *     record with no regex whose `----` block is the error's text.
      *   - `query <letters> [nosort | rowsort | valuesort] [<label>]`, the
      *     letters `I` an integer, `R` a float or decimal, `T` text, `B` a
      *     boolean, `D` a date, `P` a timestamp and `?` any other type.
      *   - A result is a row a line, its values separated by spaces; `(empty)`
      *     is the empty string, and a float prints rounded to 12 places.
      *   - `include <file>` reads the records of a file beside the script
      *     (DataFusion's `.slt.part` files) in its place.
      *   - `onlyif postgres` keeps a record for Postgres and `skipif postgres`
      *     from it, as any condition on another engine does.
      */
    case object DataFusion extends Format { def upstream: Option[String] = Some("datafusion") }

    /** sqllogictest-rs's format as RisingWave's tests write it, with values
      * printed as Postgres prints them: RisingWave's TPC-H answers. */
    case object RisingWave extends Format { def upstream: Option[String] = Some("risingwave") }

    /** Whether `f` is one of sqllogictest-rs's two. */
    def isRs(f: Format): Boolean = f == DataFusion || f == RisingWave
  }

  /** `skipif` or `onlyif` an engine; `rule` is the ledger id a condition on
    * [[EngineName]] cites, and None on any other engine. A condition on one
    * of CockroachDB's configurations names the engine `config`. */
  final case class Condition(skip: Boolean, engine: String, rule: Option[String]) {
    /** Whether this condition lets the record run on [[EngineName]]. */
    def admits: Boolean = if (skip) !onThisEngine else onThisEngine

    def onThisEngine: Boolean = engine.equalsIgnoreCase(EngineName)
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
    /** A letter of CockroachDB's or sqllogictest-rs's: the value printed as
      * that engine prints a value of its type ([[Values.upstream]]), and the
      * column checked to be of the kind the letter names
      * ([[Values.letterProblem]]). */
    final case class Letter(c: Char) extends Column
  }

  sealed trait Record {
    def line: Int
    def conditions: Seq[Condition]
    def runs: Boolean = conditions.forall(_.admits)

    /** The rule a `skipif transformer` before the record cites. */
    def skipRule: Option[String] = conditions.collectFirst { case Condition(true, _, Some(id)) => id }

    /** The rule an `onlyif transformer` before the record cites. */
    def onlyRule: Option[String] = conditions.collectFirst { case Condition(false, _, Some(id)) => id }

    /** Whether an `onlyif <upstream>` keeps the record for the engine whose
      * answers the script holds ([[Format.upstream]]): a record held from
      * this one for what it needs, or as a finding not yet decided. */
    def heldFor(format: Format): Boolean =
      format.upstream.exists(u => conditions.exists(c => !c.skip && c.engine.equalsIgnoreCase(u)))
  }

  /** `errorText` is text the message must contain (DuckDB's `----` block);
    * `errorPattern` a regular expression the message must hold a match of
    * (CockroachDB's and sqllogictest-rs's error line). */
  final case class Statement(line: Int, conditions: Seq[Condition], sql: String, expectError: Boolean,
      errorText: Option[String], errorPattern: Option[String] = None) extends Record

  /** `sqlEnd` and `end` are the 0-based indices of the line after the SQL
    * and of the line after the record, which [[Runner.complete]] rewrites
    * between. `colNames` says the expected block's first line names the
    * result's columns, and `partialSort` lists the 1-based columns a
    * CockroachDB result is already ordered by, its rows sorted only within
    * runs that agree on them. */
  final case class Query(line: Int, conditions: Seq[Condition], sql: String, columns: Seq[Column],
      sort: SortMode, label: Option[String], expected: Expected, sqlEnd: Int, end: Int,
      colNames: Boolean = false, partialSort: Seq[Int] = Nil) extends Record

  final case class HashThreshold(line: Int, n: Int) extends Record { def conditions: Seq[Condition] = Nil }

  final case class Halt(line: Int, conditions: Seq[Condition]) extends Record

  /** What a query's `----` block says. */
  sealed trait Expected
  object Expected {
    /** The values in order, each line split at tabs. */
    final case class Values(values: Seq[String]) extends Expected
    /** The block's lines as written, a row a line: CockroachDB's and
      * sqllogictest-rs's results ([[Values.upstream]] compares them). */
    final case class Rows(lines: Seq[String]) extends Expected
    /** `N values hashing to H`. */
    final case class Hash(count: Int, md5: String) extends Expected
    /** No `----` block: the result is not checked, only that the query runs. */
    case object Unchecked extends Expected
  }

  private val HashLine = """(\d+) values? hashing to ([0-9a-fA-F]{32})""".r

  final class ParseError(file: String, line: Int, msg: String)
    extends RuntimeException(s"$file:$line: $msg")

  /** Reads no file: a script that includes one fails to parse. */
  val NoIncludes: String => String = name => throw new IllegalArgumentException(s"no file to include: $name")

  /** The records of `text`, the script `file` written in `format`, each
    * condition on transformer checked against `ledger`; `include` gives the
    * text of a file an `include` line names. */
  def parse(file: String, text: String, ledger: Ledger, format: Format = Format.Sqlite,
      include: String => String = NoIncludes): Vector[Record] = {
    val records = read(file, text, ledger, format, include)
    records.indices.foreach { i =>
      val rec = records(i)
      for (id <- rec.skipRule; rule <- ledger.rule(id) if !rule.refuses) {
        val twin = records.lift(i + 1).filter(n => n.onlyRule.contains(id) && sqlOf(n).isDefined && sqlOf(n) == sqlOf(rec))
        if (twin.isEmpty)
          throw new ParseError(file, rec.line, s"a record skipped as $id is followed by its twin, a record " +
            s"'onlyif $EngineName $id' of the same SQL with the engine's own answer, since the rule says the " +
            "engine answers otherwise rather than that it refuses")
      }
    }
    records
  }

  /** A record's SQL, if it has one. */
  def sqlOf(rec: Record): Option[String] = rec match {
    case q: Query => Some(q.sql)
    case s: Statement => Some(s.sql)
    case _ => None
  }

  /** CockroachDB's and sqllogictest-rs's error line: the regular expression
    * after `statement error` or `query error`, after CockroachDB's `pgcode
    * <code>`. */
  private val ErrorLine = """^(?:statement|query)\s+error(?:\s+pgcode\s+[0-9A-Za-z]+)?(?:\s+(.*))?$""".r

  private def read(file: String, text: String, ledger: Ledger, format: Format,
      include: String => String): Vector[Record] = {
    val lines = text.split("\n", -1).map(l => if (l.endsWith("\r")) l.dropRight(1) else l)
    val out = Vector.newBuilder[Record]
    var i = 0
    val upstreamFormat = format != Format.Sqlite
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
        val builder = Vector.newBuilder[Condition]
        var tokens = tokenize(raw)
        while (tokens.headOption.exists(t => t == "skipif" || t == "onlyif")) {
          if (tokens.length < 2) throw new ParseError(file, i + 1, s"'${tokens.head}' names no engine")
          if (format == Format.Cockroach && tokens(1) == "config" && tokens.length < 3)
            throw new ParseError(file, i + 1, s"'${tokens.head} config' names no configuration")
          val onThisEngine = tokens(1).equalsIgnoreCase(EngineName)
          builder += Condition(tokens.head == "skipif", tokens(1),
            if (onThisEngine) Some(ruleId(file, i + 1, lines(i), tokens, ledger)) else None)
          i += 1
          if (i >= lines.length) throw new ParseError(file, i, "a condition with no record after it")
          tokens = tokenize(lines(i))
        }
        val start = i + 1
        val conditions = builder.result()
        if (conditions.count(_.onThisEngine) > 1)
          throw new ParseError(file, start, s"a record takes one condition on $EngineName")
        if (conditions.exists(_.onThisEngine) && conditions.exists(c => !c.skip && !c.onThisEngine))
          throw new ParseError(file, start, s"a record with an onlyif on another engine takes no condition on " +
            s"$EngineName: the onlyif keeps it from this engine already")
        def noConditions(what: String): Unit =
          if (conditions.nonEmpty) throw new ParseError(file, start, s"$what takes no condition")
        tokens.headOption match {
          case Some("statement") | Some("query") if upstreamFormat && tokens.lift(1).contains("error") =>
            val pattern = lines(i).trim match {
              case ErrorLine(p) => Option(p).map(_.trim).filter(_.nonEmpty)
              case other => throw new ParseError(file, start, s"an error record reads 'statement error <regex>', not '$other'")
            }
            val (sqlLines, after) = block(i + 1)
            if (sqlLines.isEmpty) throw new ParseError(file, start, "an error record with no SQL")
            var next = after
            var errorText: Option[String] = None
            if (next < lines.length && lines(next) == "----") {
              val (msg, end) = results(next + 1)
              if (pattern.isDefined || format == Format.Cockroach)
                throw new ParseError(file, start, "an error record with a regular expression takes no ---- block")
              errorText = Some(msg.mkString("\n"))
              next = end
            }
            out += Statement(start, conditions, sqlLines.mkString("\n"), expectError = true, errorText, pattern)
            i = next
          case Some("statement") =>
            val expectError = tokens.lift(1) match {
              case Some("ok") => false
              case Some("error") => true
              case Some(w) if format == Format.Cockroach && w.equalsIgnoreCase("ok") => false
              case Some("count") if upstreamFormat =>
                if (!tokens.lift(2).exists(_.forall(_.isDigit)))
                  throw new ParseError(file, start, "statement count takes a number")
                false
              case Some("notice") if format == Format.Cockroach => false
              case None if format == Format.Cockroach => false
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
            out += Statement(start, conditions, sqlLines.mkString("\n"), expectError, errorText)
            i = next
          case Some("query") =>
            if (tokens.length < 2) throw new ParseError(file, start, "a query names no columns")
            val columns = parseColumns(file, start, tokens(1), format)
            val (sort, label, colNames, partialSort) =
              if (format == Format.Cockroach) cockroachOptions(file, start, tokens.drop(2))
              else {
                val sort = tokens.lift(2) match {
                  case None | Some("nosort") => SortMode.NoSort
                  case Some("rowsort") => SortMode.RowSort
                  case Some("valuesort") => SortMode.ValueSort
                  case Some(other) => throw new ParseError(file, start, s"unknown sort mode '$other'")
                }
                if (tokens.length > 4) throw new ParseError(file, start, s"unknown query option '${tokens(4)}'")
                (sort, tokens.lift(3), false, Nil)
              }
            val (sqlLines, after) = block(i + 1)
            if (sqlLines.isEmpty) throw new ParseError(file, start, "a query with no SQL")
            var next = after
            var expected: Expected = Expected.Unchecked
            if (next < lines.length && lines(next) == "----") {
              val (vals, end) = results(next + 1)
              expected = vals match {
                case Seq(HashLine(n, h)) if !colNames => Expected.Hash(n.toInt, h.toLowerCase(Locale.ROOT))
                case _ if upstreamFormat => Expected.Rows(vals)
                case _ => Expected.Values(vals.flatMap(_.split("\t", -1)))
              }
              next = end
            }
            if (colNames && expected == Expected.Unchecked)
              throw new ParseError(file, start, "a query with colnames has a ---- block naming its columns")
            out += Query(start, conditions, sqlLines.mkString("\n"), columns, sort, label, expected, after, next,
              colNames, partialSort)
            i = next
          case Some("hash-threshold") =>
            if (conditions.exists(_.onThisEngine))
              throw new ParseError(file, start, s"a hash-threshold takes no condition on $EngineName")
            val n = tokens.lift(1).flatMap(s => s.toIntOption)
              .getOrElse(throw new ParseError(file, start, "hash-threshold takes a number"))
            out += HashThreshold(start, n)
            i += 1
          case Some("halt") =>
            if (conditions.exists(_.onThisEngine))
              throw new ParseError(file, start, s"a halt takes no condition on $EngineName")
            out += Halt(start, conditions)
            i += 1
          case Some("subtest") if format == Format.Cockroach =>
            noConditions("a subtest")
            i += 1
          case Some("include") if Format.isRs(format) =>
            noConditions("an include")
            val name = tokens.lift(1).getOrElse(throw new ParseError(file, start, "include names no file"))
            if (tokens.length > 2) throw new ParseError(file, start, s"include names one file, not '${tokens.drop(1).mkString(" ")}'")
            val included =
              try include(name)
              catch { case e: IllegalArgumentException => throw new ParseError(file, start, e.getMessage) }
            out ++= read(name, included, ledger, format, include)
            i += 1
          case Some(other) => throw new ParseError(file, start, s"unknown record type '$other'")
          case None =>
            if (conditions.nonEmpty) throw new ParseError(file, start, "a condition with no record after it")
            i += 1
        }
      }
    }
    out.result()
  }

  /** A CockroachDB query's options after its letters: a comma-separated list,
    * where `partialsort(1,2)` holds commas of its own, and then a label. */
  private def cockroachOptions(file: String, line: Int, rest: Vector[String]): (SortMode, Option[String], Boolean, Seq[Int]) = {
    if (rest.length > 2) throw new ParseError(file, line, s"unknown query option '${rest(2)}'")
    var sort: SortMode = SortMode.NoSort
    var colNames = false
    var partial = Seq.empty[Int]
    val Partial = """partialsort\(([0-9,]+)\)""".r
    val options = rest.headOption.toVector.flatMap(splitTopLevel)
    options.foreach {
      case "nosort" => sort = SortMode.NoSort
      case "rowsort" => sort = SortMode.RowSort
      case "valuesort" => sort = SortMode.ValueSort
      case "colnames" => colNames = true
      case "retry" => ()
      case Partial(cols) => partial = cols.split(",").toSeq.map(_.toInt)
      case other => throw new ParseError(file, line, s"unknown query option '$other'")
    }
    (sort, rest.lift(1), colNames, partial)
  }

  /** The id a condition on transformer cites, its third token: one the
    * ledger has, then nothing but a comment. `raw` is the condition's line. */
  private def ruleId(file: String, line: Int, raw: String, tokens: Vector[String], ledger: Ledger): String = {
    val written = s"${tokens.head} $EngineName"
    tokens.lift(2) match {
      case None =>
        val comment = raw.indexOf('#') match { case -1 => ""; case k => raw.substring(k + 1) }
        val nearest = ledger.suggest(comment).map(id => s"; the nearest to its comment is '$id'").getOrElse("")
        throw new ParseError(file, line, s"'$written' cites no rule: write the id of the rule the record follows, " +
          s"from the ledger in docs/gotchas.md (${ledger.ids.mkString(", ")})$nearest")
      case Some(id) if ledger.rule(id).isEmpty =>
        throw new ParseError(file, line, s"'$written $id' cites a rule the ledger in docs/gotchas.md does not " +
          s"have; the nearest is '${ledger.nearest(id)}'")
      case Some(id) if tokens.length > 3 =>
        throw new ParseError(file, line, s"'$written $id' takes a comment after a #, not '${tokens(3)}'")
      case Some(id) => id
    }
  }

  /** The whitespace-separated tokens of a record's first line, up to a `#`. */
  private def tokenize(line: String): Vector[String] =
    line.trim.split("\\s+").toVector.takeWhile(t => !t.startsWith("#")).filter(_.nonEmpty)

  /** CockroachDB's column letters, and sqllogictest-rs's. */
  private val CockroachLetters = "TIFRBO_"
  private val RsLetters = "IRTBDP?"

  /** `III` (SQLite's letters) or `INT,R,DECIMAL(9,2)` (a column each), or a
    * word of CockroachDB's or sqllogictest-rs's letters. */
  def parseColumns(file: String, line: Int, token: String, format: Format = Format.Sqlite): Vector[Column] =
    format match {
      case Format.Sqlite => sqliteColumns(file, line, token)
      case f =>
        val letters = if (f == Format.Cockroach) CockroachLetters else RsLetters
        token.toVector.map { c =>
          if (!letters.contains(c)) throw new ParseError(file, line, s"unknown column type '$c' in '$token'")
          Column.Letter(c)
        }
    }

  private def sqliteColumns(file: String, line: Int, token: String): Vector[Column] =
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
