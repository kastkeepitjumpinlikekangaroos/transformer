package com.transformer.slt

import com.transformer.core.{DataType, Schema}
import com.transformer.slt.Script.{Column, SortMode}

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{LocalDateTime, ZoneOffset}
import java.util.Locale
import scala.collection.mutable
import scala.util.control.NonFatal

/** Ports test files of Apache Spark's `SQLQueryTestSuite` to sqllogictest
  * scripts under `src/test/slt/spark/`, with Spark's answers.
  *
  * {{{
  *   bazel run //src/test/scala/com/transformer/slt:spark_import -- \
  *     --sql-tests <spark checkout>/sql/core/src/test/resources/sql-tests --commit <sha> \
  *     [--out src/test/slt/spark] [--report <file>]
  * }}}
  *
  * Each source is an input file, `inputs/<name>.sql`, and its golden file,
  * `results/<name>.sql.out`, which records every statement's result schema
  * and output under ANSI mode. A record of the golden file becomes a record of
  * the script ([[port]]):
  *
  *   - Its SQL is Spark's, but for the rewrites [[Rewrite]] makes, each named
  *     in the record's comment: a temporary view of literals is a table of
  *     their types, `USING parquet` is dropped, a timestamp's text cast to
  *     DATE is the DATE literal of its date, and a decimal literal (a number
  *     with a point and no exponent, or one with Spark's `BD` suffix) is
  *     `CAST('...' AS DECIMAL(p,s))` of the type Spark gives it.
  *   - Its columns are the schema's types, each an exact column of the
  *     engine's type of that name, and its values are the output's, printed
  *     as the runner prints them ([[sparkValue]]); `rowsort` unless the query
  *     ends in ORDER BY, as Spark sorts every result its plan does not.
  *   - An error under ANSI is a `statement error`, Spark's error class in its
  *     comment, and the engine's own words for the same reason after `----`.
  *   - A record needing a type, a literal or a function the engine lacks is
  *     left out, and counted by what it needs ([[Rewrite.lacks]]).
  *
  * The converter then runs each record on the engine ([[Engine]]), in the
  * baseline. Where the engine answers as Spark does the record is kept. Where
  * it answers otherwise by a rule of the ledger in `docs/gotchas.md`
  * ([[Rules]]), the record is `skipif transformer <id>`, and, for a rule that
  * is no refusal, followed by its twin, `onlyif transformer <id>` with the
  * engine's own answer. Where no rule explains the difference, or both
  * engines refuse for different reasons, the record is held out of the script
  * and listed in the report as a finding. The scripts are vendored: no test
  * runs this, and `slt_test` holds every record, skip and twin.
  */
object SparkImport {

  // ---------------------------------------------------------------------------
  // What is ported
  // ---------------------------------------------------------------------------

  /** A source file and the script its records go to. `stopAt` is the first
    * statement of a part left out whole, with the reason; `about` describes
    * the script, for its header. */
  final case class Source(name: String, script: String, about: String, stopAt: Option[(String, String)] = None)

  /** A file of `typeCoercion/native`, and its script under `type_coercion/`,
    * or none for a file with no record the engine can run. */
  private def typeCoercion(file: String, script: String, about: String): Source =
    Source(s"typeCoercion/native/$file", if (script.isEmpty) "" else s"type_coercion/$script.test", about)

  val Sources: Seq[Source] = Seq(
    Source("decimalArithmeticOperations", "decimal_arithmetic.test",
      "DECIMAL arithmetic: division and remainder by zero, two DECIMAL(38,18) columns under + - * / and beside an " +
        "integer constant, constant expressions whose types the result-type rules give way on past 38 digits, " +
        "and a UNION ALL of decimal casts",
      stopAt = Some(("set spark.sql.decimalOperations.allowPrecisionLoss=false",
        "the half under spark.sql.decimalOperations.allowPrecisionLoss=false, which gives a NULL or an error past " +
          "38 digits where this engine, as Spark by default, gives way on the scale"))),
    typeCoercion("binaryComparison", "binary_comparison",
      "comparisons (= > >= < <= <>) of a value of each type with a string literal and with a STRING NULL"),
    typeCoercion("booleanEquality", "boolean_equality", "TRUE and FALSE compared with a value of each type"),
    typeCoercion("caseWhenCoercion", "case_when", "CASE WHEN's two values, of each pair of types"),
    typeCoercion("concat", "concat", "|| over values of several types"),
    typeCoercion("decimalPrecision", "decimal_precision",
      "every operator (+ - * / % = <> < <= > >=) of a value of each type beside a DECIMAL at four precisions"),
    typeCoercion("division", "division", "/ of each pair of types"),
    typeCoercion("ifCoercion", "if", "IF's two values, of each pair of types"),
    typeCoercion("implicitTypeCasts", "implicit_casts",
      "a string literal beside a number in arithmetic, and a number where text is taken: under ||, LIKE and " +
        "LENGTH"),
    typeCoercion("inConversion", "in", "IN with a list of one and of two values, of each pair of types"),
    typeCoercion("promoteStrings", "promote_strings",
      "a string literal beside a value of each type under each operator, and under the math functions and the " +
        "aggregates"),
    typeCoercion("stringCastAndExpressions", "string_cast", "a STRING column holding 'aa' cast to each type"),
    typeCoercion("widenSetOperationTypes", "union", "UNION of each pair of types"),
    typeCoercion("windowFrameCoercion", "window_frame", "a window ordered by a value of each type"),
    typeCoercion("dateTimeOperations", "", ""),
    typeCoercion("arrayJoin", "", ""),
    typeCoercion("elt", "", ""),
    typeCoercion("mapconcat", "", ""),
    typeCoercion("mapZipWith", "", ""))

  // ---------------------------------------------------------------------------
  // The golden file
  // ---------------------------------------------------------------------------

  /** One statement of a golden file: its SQL, its schema (`struct<...>`,
    * `struct<>` for a statement or an error) and its output, the result's
    * rows joined by line breaks, or the error's class and message. */
  final case class Golden(sql: String, schema: String, output: String) {
    def isError: Boolean = schema == "struct<>" && output.nonEmpty
    def isStatement: Boolean = schema == "struct<>" && output.isEmpty
    /** The error's class, as Spark's JSON names it, or its exception. */
    def errorClass: String = ErrorClass.findFirstMatchIn(output).map(_.group(1)).getOrElse(output.takeWhile(_ != '\n'))
  }

  private val ErrorClass = """"errorClass" : "([^"]+)"""".r

  /** A golden file's statements. `SQLQueryTestSuite` writes a header line and
    * then each statement as `-- !query`, its SQL, `-- !query schema`, the
    * schema, `-- !query output` and the output, the statements joined by two
    * blank lines. */
  def parseGolden(text: String): Vector[Golden] = {
    val body = text.substring(text.indexOf('\n') + 1).stripSuffix("\n")
    body.split("\n\n\n(?=-- !query\n)", -1).toVector.filter(_.nonEmpty).map { part =>
      val afterQuery = part.stripPrefix("-- !query\n")
      val (sql, rest) = cut(afterQuery, "\n-- !query schema\n")
      val (schema, output) = cut(rest, "\n-- !query output\n")
      Golden(sql, schema, output)
    }
  }

  private def cut(s: String, at: String): (String, String) = {
    val i = s.indexOf(at)
    if (i < 0) throw new IllegalArgumentException(s"no '${at.trim}' in a golden record: ${s.take(80)}")
    (s.substring(0, i), s.substring(i + at.length))
  }

  /** The 1-based line of the input file each statement starts at: the first
    * line at or after the previous statement's that its first line opens. */
  def sourceLines(input: String, statements: Seq[String]): Vector[Int] = {
    val lines = input.split("\n", -1).map(_.trim)
    var from = 0
    statements.toVector.map { sql =>
      val first = sql.trim.takeWhile(_ != '\n').trim
      val i = (from until lines.length).find(k => lines(k).startsWith(first)).getOrElse(
        throw new IllegalArgumentException(s"no line of the input starts '${first.take(60)}'"))
      from = i + 1
      i + 1
    }
  }

  // ---------------------------------------------------------------------------
  // Types and values
  // ---------------------------------------------------------------------------

  /** A schema's column types, `struct<a:int,(a + b):decimal(38,17)>`: each
    * field split at the commas outside parentheses and a type's angle
    * brackets (`array<int>`, whose `<` follows a letter, where a name's `>`
    * is an operator), its type after its last colon outside them, as a name
    * may hold colons. */
  def schemaTypes(schema: String): Vector[String] = {
    val inner = schema.stripPrefix("struct<").stripSuffix(">")
    if (inner.isEmpty) Vector.empty
    else outside(inner, ',').map { field =>
      val colon = outsideIndices(field, ':').last
      field.substring(colon + 1)
    }
  }

  private def outsideIndices(s: String, c: Char): Vector[Int] = {
    var parens, angles = 0
    val out = Vector.newBuilder[Int]
    s.indices.foreach { i =>
      s(i) match {
        case '(' => parens += 1
        case ')' => parens -= 1
        case '<' if i > 0 && s(i - 1).isLetter => angles += 1
        case '>' if angles > 0 => angles -= 1
        case x if x == c && parens == 0 && angles == 0 => out += i
        case _ => ()
      }
    }
    out.result()
  }

  private def outside(s: String, c: Char): Vector[String] = {
    val cuts = outsideIndices(s, c)
    ((-1 +: cuts) zip (cuts :+ s.length)).map { case (a, b) => s.substring(a + 1, b) }.toVector
  }

  private val SparkDecimal = """decimal\((\d+),(\d+)\)""".r

  /** The engine's type for a Spark type name, if it has one. */
  def engineType(spark: String): Option[DataType] = spark match {
    case "int" => Some(DataType.IntType)
    case "bigint" => Some(DataType.LongType)
    case "float" => Some(DataType.FloatType)
    case "double" => Some(DataType.DoubleType)
    case "string" => Some(DataType.StringType)
    case "boolean" => Some(DataType.BooleanType)
    case "date" => Some(DataType.DateType)
    case "timestamp" => Some(DataType.TimestampType)
    case "void" => Some(DataType.NullType)
    case SparkDecimal(p, s) => Some(DataType.DecimalType(p.toInt, s.toInt))
    case _ => None
  }

  /** A value of Spark's output as an exact column prints it ([[Values]]):
    * NULL as NULL, a timestamp, which Spark prints in the suite's zone as
    * `2017-12-12 09:30:00`, as the instant of that local time in UTC, the
    * engine's zone, and text as the runner escapes it; every other value is
    * printed alike, a DOUBLE and a FLOAT as Java prints them and a DECIMAL
    * with its scale. */
  def sparkValue(v: String, t: DataType): String =
    if (v == "NULL") "NULL"
    else t match {
      case DataType.TimestampType => LocalDateTime.parse(v.replace(' ', 'T')).toInstant(ZoneOffset.UTC).toString
      case DataType.StringType => Values.exact(v)
      case _ => v
    }

  // ---------------------------------------------------------------------------
  // Rewrites, and what the engine lacks
  // ---------------------------------------------------------------------------

  /** A record's SQL as the engine is given it, and the rewrites made. */
  final case class Rewritten(sql: String, notes: Vector[String])

  // The rewrites, as a record's comment names them, and as a script's header
  // explains each.
  val ViewNote = "the temporary view as a table of one row"
  val ParquetNote = "USING parquet dropped"
  val DateNote = "a timestamp's text cast to DATE written as the DATE literal of its date"
  val DecimalNote = "each decimal literal written as the CAST to Spark's DECIMAL type for it"

  private val Explained: Seq[(String, String)] = Seq(
    ViewNote -> "a temporary view of literals as a table of their types holding one row of them",
    ParquetNote -> "USING parquet dropped",
    DateNote -> ("a timestamp's text cast to DATE as the DATE literal of its date (Spark's cast drops the time; " +
      "this engine reads only an ISO date as a DATE, and a test of that cast is no test of its subject)"),
    DecimalNote -> ("a decimal literal, a number with a point and no exponent or one with Spark's BD suffix, as " +
      "the CAST of its text to the DECIMAL(p,s) Spark types it as (here a number with a point is a DOUBLE until it " +
      "meets a DECIMAL)"))

  object Rewrite {

    /** Functions Spark has that the engine does not, or has under the name
      * of another (`trunc` of a date and a format). */
    private val SparkOnlyFunctions: Set[String] = Set(
      "pmod", "date", "timestamp", "string", "float", "double", "int", "bigint", "encode", "decode", "range",
      "array_join", "elt", "map", "map_concat", "map_zip_with", "array", "struct", "to_timestamp",
      "to_unix_timestamp", "unix_timestamp", "from_unixtime", "next_day", "trunc", "time_trunc", "year", "month",
      "day", "hour", "minute", "second", "unhex", "sha2", "get_json_object", "json_tuple", "from_json",
      "skewness", "kurtosis")

    /** What `sql` needs that the engine lacks, if anything: a type, a
      * literal or a function. */
    def lacks(sql: String): Option[String] = {
      val tokens = Lexer.tokens(sql)
      val words = tokens.zipWithIndex.collect { case (t, i) if t.kind == Token.Word => (t.upper, i) }
      def next(i: Int): Option[Token] = tokens.lift(i + 1)
      def castTo(name: String): Boolean = words.exists { case (w, i) =>
        w == name && tokens.lift(i - 1).exists(_.upper == "AS")
      }
      val adjacentSuffix = tokens.indices.collectFirst {
        case i if tokens(i).kind == Token.Number && next(i).exists(n => n.kind == Token.Word && n.start == tokens(i).end &&
            n.upper != "BD") => next(i).get.upper
      }
      val bareDecimal = words.exists { case (w, i) =>
        w == "DECIMAL" && tokens.lift(i - 1).exists(_.upper == "AS") && !next(i).exists(_.text == "(")
      }
      if (castTo("TINYINT") || castTo("BYTE")) Some("TINYINT")
      else if (castTo("SMALLINT") || castTo("SHORT")) Some("SMALLINT")
      else if (castTo("BINARY") || tokens.exists(_.kind == Token.Blob)) Some("BINARY, and X'' literals")
      else if (words.exists(_._1 == "INTERVAL")) Some("INTERVAL")
      else if (TemporalDifference.findFirstIn(sql).isDefined)
        Some("INTERVAL, the type of a DATE's or TIMESTAMP's difference with text Spark reads as one")
      else if (castTo("TIME") || words.exists { case (w, i) => w == "TIME" && next(i).exists(_.kind == Token.Text) })
        Some("TIME")
      else if (castTo("ARRAY") || castTo("STRUCT") || castTo("MAP")) Some("arrays, structs and maps")
      else if (bareDecimal) Some("a bare DECIMAL, Spark's DECIMAL(10,0), which the engine's CAST refuses unnamed")
      else adjacentSuffix.map(s => s"the literal suffix $s").orElse {
        val calls = words.collect {
          case (w, i) if next(i).exists(_.text == "(") && !tokens.lift(i - 1).exists(_.upper == "AS") =>
            w.toLowerCase(Locale.ROOT)
        }
        calls.find(SparkOnlyFunctions).map(f => s"Spark's function $f()")
          .orElse(if (tokens.indices.exists(i => tokens(i).text == "<=" && next(i).exists(n => n.text == ">" &&
              n.start == tokens(i).end))) Some("Spark's operator <=>") else None)
          .orElse(if (words.exists(_._1 == "DIV")) Some("Spark's operator div") else None)
      }
    }

    private val TimestampText = """'(\d{4}-\d{2}-\d{2}) \d{2}:\d{2}:\d{2}(?:\.\d+)?'""".r

    /** A DATE or TIMESTAMP and text minus each other, which Spark reads as a
      * temporal difference, an INTERVAL. */
    private val TemporalDifference =
      """(?i)'[^']*'\s*-\s*cast\([^)]*\bas\s+(date|timestamp)\)|cast\([^)]*\bas\s+(date|timestamp)\)\s*-\s*'""".r

    /** `sql` with its decimal literals cast to Spark's type for them, and a
      * timestamp's text cast to DATE as the DATE literal of its date. */
    def apply(sql: String): Rewritten = {
      val tokens = Lexer.tokens(sql)
      val out = new StringBuilder
      val notes = mutable.LinkedHashSet.empty[String]
      var copied = 0
      var i = 0
      while (i < tokens.length) {
        val t = tokens(i)
        def suffix = tokens.lift(i + 1).filter(n => n.kind == Token.Word && n.start == t.end && n.upper == "BD")
        // cast('2017-12-11 09:30:00' as date)
        val dateCast = t.upper == "CAST" && tokens.lift(i + 1).exists(_.text == "(") &&
          tokens.lift(i + 2).exists(x => x.kind == Token.Text && TimestampText.matches(x.text)) &&
          tokens.lift(i + 3).exists(_.upper == "AS") && tokens.lift(i + 4).exists(_.upper == "DATE") &&
          tokens.lift(i + 5).exists(_.text == ")")
        if (dateCast) {
          val TimestampText(date) = tokens(i + 2).text: @unchecked
          out ++= sql.substring(copied, t.start) ++= s"DATE '$date'"
          copied = tokens(i + 5).end
          notes += DateNote
          i += 6
        } else if (t.kind == Token.Number && (suffix.isDefined || isDecimalLiteral(t.text))) {
          val (p, s) = decimalLiteralType(t.text)
          out ++= sql.substring(copied, t.start) ++= s"CAST('${t.text}' AS DECIMAL($p,$s))"
          copied = suffix.map(_.end).getOrElse(t.end)
          notes += DecimalNote
          i += (if (suffix.isDefined) 2 else 1)
        } else i += 1
      }
      out ++= sql.substring(copied)
      Rewritten(out.result(), notes.toVector)
    }

    /** A number with a point and no exponent, which Spark reads as a
      * DECIMAL. */
    private def isDecimalLiteral(text: String): Boolean = text.contains('.') && !text.exists(c => c == 'e' || c == 'E')

    /** Spark's type for a decimal literal: its digits and scale, a negative
      * scale (`5e36`) made 0, and at least as many digits as places. */
    def decimalLiteralType(text: String): (Int, Int) = {
      val d = new java.math.BigDecimal(text)
      val v = if (d.scale < 0) d.setScale(0) else d
      (math.max(v.precision, v.scale), v.scale)
    }
  }

  // ---------------------------------------------------------------------------
  // The engine's answer
  // ---------------------------------------------------------------------------

  /** What the engine did with a query: an answer, or a failure, at bind time
    * or on the data. */
  sealed trait EngineOutcome
  final case class Answer(schema: Schema, rows: Vector[Array[Any]]) extends EngineOutcome {
    def types: Vector[DataType] = schema.fields.map(_.dataType)
  }
  final case class Failure(error: Throwable, atBind: Boolean) extends EngineOutcome {
    def message: String = Runner.message(error)
    def clean: Boolean = Runner.clean(error, running = !atBind)
  }

  /** The tables a script builds, and its queries run on them in the
    * baseline, as [[Runner]] runs a record. */
  final class Engine extends AutoCloseable {
    private val db = new Database(Dialect.Transformer, Paths.get("."))
    private val views = new Modes.Views

    def execute(sql: String): Unit = db.execute(sql, q => query(q) match {
      case Answer(schema, rows) => (schema, rows)
      case f: Failure => throw f.error
    })

    def hasTable(name: String): Boolean = db.allTables.exists(_.name.equalsIgnoreCase(name))

    def query(sql: String): EngineOutcome = {
      val q =
        try Modes.execute(sql, views.catalog(db.allTables, Modes.Baseline.layout), Modes.Baseline)
        catch { case NonFatal(e) => return Failure(e, atBind = true) }
      try Answer(q.schema, Runner.drain(q))
      catch { case NonFatal(e) => Failure(e, atBind = false) }
    }

    def close(): Unit = views.close()
  }

  // ---------------------------------------------------------------------------
  // Why the engine answers otherwise
  // ---------------------------------------------------------------------------

  /** A record, as both engines treated it: Spark's SQL, as written and as
    * the engine is given it, Spark's answer (None for an error) and the
    * engine's. */
  final case class Case(golden: Golden, sql: String, sparkTypes: Vector[DataType], sparkRows: Vector[Vector[String]],
      engine: EngineOutcome) {
    val text: String = golden.sql.toLowerCase(Locale.ROOT)
    def sparkError: Option[String] = if (golden.isError) Some(golden.errorClass) else None
    def refusedWith(words: String): Boolean = engine match {
      case f: Failure => f.clean && f.message.contains(words)
      case _ => false
    }
    def engineTypes: Option[Vector[DataType]] = engine match {
      case a: Answer => Some(a.types)
      case _ => None
    }
    /** Whether both engines answer with numbers that are equal, row by row,
      * whatever their types. */
    def sameNumbers: Boolean = engine match {
      case a: Answer if !golden.isError && a.rows.length == sparkRows.length =>
        val mine = Values.arrangeRows(Values.formatRows(a.rows, a.types.map(Column.Exact)), SortMode.RowSort)
        val theirs = Values.arrangeRows(sparkRows, SortMode.RowSort)
        mine.zip(theirs).forall { case (x, y) =>
          x.length == y.length && x.zip(y).forall { case (p, q) => sameNumber(p, q) }
        }
      case _ => false
    }
    private def sameNumber(a: String, b: String): Boolean =
      a == b || (try new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0
                 catch { case _: NumberFormatException => false })
  }

  /** A rule of the ledger, and whether it explains a case: why the engine,
    * by that rule, answers otherwise than Spark. A rule whose id starts
    * `refused-` explains a record the engine refuses with its words, whatever
    * Spark did (an error for another reason too); any other rule reads both
    * the SQL and both answers, so a case is put down to it only where its
    * construct is there and the engine answers as it says. */
  final case class Rule(id: String, why: String, explains: Case => Boolean)

  object Rules {
    private def answered(c: Case) = !c.golden.isError

    /** Whether the SQL has a string literal that is an operand, not the
      * text a CAST reads. */
    private def stringLiteralOperand(c: Case): Boolean = {
      val tokens = Lexer.tokens(c.golden.sql)
      tokens.indices.exists { i =>
        tokens(i).kind == Token.Text &&
          !(tokens.lift(i - 1).exists(_.text == "(") && tokens.lift(i - 2).exists(_.upper == "CAST"))
      }
    }

    private def refusal(id: String, why: String, words: String): Rule = Rule(id, why, _.refusedWith(words))

    val All: Seq[Rule] = Seq(
      Rule("div-zero-null", "a division or remainder by zero is NULL",
        c => c.sparkError.exists(e => e == "DIVIDE_BY_ZERO" || e == "REMAINDER_BY_ZERO") && (c.engine match {
          case a: Answer => a.rows.nonEmpty && a.rows.forall(_.forall(_ == null))
          case _ => false
        })),
      refusal("refused-decimal-beside-float", "a DECIMAL beside a FLOAT or DOUBLE that is not written in the query",
        "has no scale for a DECIMAL result to keep"),
      refusal("refused-text-arithmetic", "arithmetic on a STRING", "Arithmetic on STRING is not supported"),
      refusal("refused-text-comparison", "text meets another type only as a string literal, which a DECIMAL reads none of",
        "with STRING"),
      refusal("refused-text-branch", "a STRING that is no literal becoming one result with another type", "and STRING"),
      refusal("refused-date-beside-timestamp", "a DATE beside a TIMESTAMP", "a DATE as a TIMESTAMP is its midnight"),
      refusal("refused-implicit-text", "a function, LIKE or || takes a STRING", "CAST it to STRING"),
      refusal("refused-implicit-number", "a math function or an aggregate takes a number", "CAST it to a number"),
      Rule("bool-text", "text casts to BOOLEAN from true and false alone",
        c => answered(c) && c.text.contains("as boolean") && c.refusedWith("to boolean")),
      Rule("window-constant-key", "a window key that reads no column is refused",
        c => answered(c) && c.text.contains("over") && c.refusedWith("reads no column")),
      Rule("division-keeps-type", "/ keeps its operands' type",
        c => answered(c) && c.text.contains(" / ") && c.sparkTypes == Vector(DataType.DoubleType) &&
          c.engineTypes.exists(_.forall(Set[DataType](DataType.IntType, DataType.LongType, DataType.FloatType))) &&
          c.sameNumbers),
      Rule("text-literal-typed", "a string literal is read as the type beside it",
        c => answered(c) && stringLiteralOperand(c) && c.engineTypes.exists(_ != c.sparkTypes) && c.sameNumbers),
      Rule("bigint-decimal", "a BIGINT beside a DECIMAL counts as a DECIMAL(19,0)",
        c => answered(c) && c.text.contains("as bigint") && c.text.contains("as decimal(") &&
          c.engineTypes.exists(ts => ts.length == 1 && ts.head.isInstanceOf[DataType.DecimalType] &&
            c.sparkTypes.head.isInstanceOf[DataType.DecimalType] && ts != c.sparkTypes) && c.sameNumbers),
      Rule("double-literal", "a number written with an exponent beside a DECIMAL is the decimal it is written as",
        c => answered(c) && """\d[eE]\d""".r.findFirstIn(c.golden.sql).isDefined &&
          c.sparkTypes == Vector(DataType.DoubleType) &&
          c.engineTypes.exists(_.forall(_.isInstanceOf[DataType.DecimalType])) && c.sameNumbers))
  }

  /** Why an error is raised, by Spark's error class or the engine's words:
    * the types do not meet, a text is no value of the type, a value is past
    * its type, or another. Two engines refusing a record for one reason
    * agree; for two reasons, they do not. */
  def sparkReason(errorClass: String): String = errorClass.takeWhile(_ != '.') match {
    case "DATATYPE_MISMATCH" | "INCOMPATIBLE_COLUMN_TYPE" => "types"
    case "CAST_INVALID_INPUT" => "text"
    case "NUMERIC_VALUE_OUT_OF_RANGE" | "ARITHMETIC_OVERFLOW" | "CAST_OVERFLOW" => "range"
    case other => other
  }

  def engineReason(f: Failure): String = {
    val m = f.message
    if (m.startsWith("Decimal overflow") || m.startsWith("Integer overflow") || m.startsWith("Float overflow")) "range"
    else if (m.startsWith("Cannot cast '")) "text"
    else if (f.atBind && (m.startsWith("Cannot compare") || m.contains(" cannot mix ") || m.startsWith("Arithmetic on") ||
        m.contains(" is not supported") || m.matches("Cannot cast [A-Z].*") || m.contains(" takes a BOOLEAN"))) "types"
    else s"other: $m"
  }

  // ---------------------------------------------------------------------------
  // A record of the script
  // ---------------------------------------------------------------------------

  /** What became of one statement. */
  sealed trait Port
  /** In the script, as `text`; `rule` is the rule it is skipped by, and
    * `notes` the rewrites its SQL was given. */
  final case class Kept(text: String, rule: Option[String], notes: Seq[String] = Nil) extends Port
  /** A statement left out, by what it needs. */
  final case class LeftOut(reason: String) extends Port
  /** A record held out of the script: the engine answers otherwise and no
    * rule says why, or refuses for another reason. */
  final case class Held(line: Int, sql: String, spark: String, engine: String) extends Port

  private val RefusedForAnotherReason = "refused for another reason:"

  private def columnsText(types: Seq[DataType]): String = types.map(Script.typeName).mkString(",")

  private def sortOf(sql: String): SortMode =
    if ("""(?is).*\border\s+by\b[^()]*$""".r.matches(sql.trim)) SortMode.NoSort else SortMode.RowSort

  private def sortText(s: SortMode): String = s match {
    case SortMode.NoSort => "nosort"
    case SortMode.RowSort => "rowsort"
    case SortMode.ValueSort => "valuesort"
  }

  /** The words of a refusal a record pins: up to the advice after a
    * semicolon, which names the reason. */
  private def reasonWords(message: String): String = message.takeWhile(_ != ';').takeWhile(_ != '\n')

  private def queryRecord(sql: String, types: Seq[DataType], sort: SortMode, rows: Vector[Vector[String]]): String =
    (Seq(s"query ${columnsText(types)} ${sortText(sort)}", sql, "----") ++
      Values.arrangeRows(rows, sort).map(_.mkString("\t"))).mkString("\n")

  /** The engine's answer printed as an exact column of each of its types
    * prints it, in the order `sort` compares rows in. */
  private def printed(a: Answer, sort: SortMode): Vector[Vector[String]] =
    Values.arrangeRows(Values.formatRows(a.rows, a.types.map(Column.Exact)), sort)

  private def engineRecord(sql: String, outcome: EngineOutcome, sort: SortMode): String = outcome match {
    case a: Answer =>
      queryRecord(sql, a.types, sort, Values.formatRows(a.rows, a.types.map(Column.Exact)))
    case f: Failure => Seq("statement error", sql, "----", reasonWords(f.message)).mkString("\n")
  }

  /** One statement of a golden file as a record of the script, run on
    * `engine`: `line` is its line in the input file. */
  def port(source: Source, g: Golden, line: Int, engine: Engine, viewsLeftOut: mutable.Set[String]): Port = {
    val where = s"${source.name.split('/').last}.sql:$line"
    def comment(notes: Seq[String]): String = wrap((where +: notes).mkString("; ")).mkString("\n")
    if (g.sql.trim.toLowerCase(Locale.ROOT).startsWith("set ")) return LeftOut("Spark's SET of a configuration")
    val reads = Lexer.tokens(g.sql).filter(_.kind == Token.Word).map(_.text.toLowerCase(Locale.ROOT))
    if (reads.exists(viewsLeftOut)) return LeftOut("a view over arrays, maps or Spark's VALUES")
    if (g.isStatement) return statement(g, where, engine, viewsLeftOut)
    Rewrite.lacks(g.sql).map(LeftOut).getOrElse {
      val names = if (g.isError) Vector.empty else schemaTypes(g.schema)
      val missing = names.filter(n => engineType(n).isEmpty)
      if (missing.nonEmpty) return LeftOut(s"Spark's type ${missing.head}")
      val types = names.map(n => engineType(n).get)
      val rewritten = Rewrite(g.sql)
      val sql = rewritten.sql
      val sort = sortOf(sql)
      val rows: Vector[Vector[String]] =
        if (g.isError || g.output.isEmpty) Vector.empty
        else g.output.split("\n", -1).toVector.map { row =>
          val cells = row.split("\t", -1).toVector
          if (cells.length != types.length) throw new IllegalArgumentException(s"$where: a row of ${cells.length} values")
          cells.zip(types).map { case (v, t) => sparkValue(v, t) }
        }
      val outcome = engine.query(sql)
      val c = Case(g, sql, types, rows, outcome)
      val sparkRecord =
        if (g.isError) Seq("statement error", sql).mkString("\n")
        else queryRecord(sql, types, sort, rows)
      val notes = rewritten.notes ++ (if (g.isError) Seq(s"Spark: ${g.errorClass}") else Nil)
      def held(why: String): Held = Held(line, g.sql, if (g.isError) g.errorClass else
        s"${columnsText(types)} ${rows.map(_.mkString(" ")).mkString(" | ")}", why)
      (g.isError, outcome) match {
        case (_, f: Failure) if !f.clean => held(s"an internal error: ${Runner.describe(f.error)}")
        case (true, f: Failure) if sparkReason(g.errorClass) == engineReason(f) =>
          Kept(Seq(comment(notes), "statement error", sql, "----", reasonWords(f.message)).mkString("\n"), None,
            rewritten.notes)
        case (false, a: Answer) if a.types == types && printed(a, sort) == Values.arrangeRows(rows, sort) =>
          Kept(Seq(comment(notes), sparkRecord).mkString("\n"), None, rewritten.notes)
        case _ =>
          Rules.All.find(_.explains(c)) match {
            case Some(rule) =>
              val ledgerRefuses = rule.id.startsWith("refused-")
              val skip = Seq(comment(notes), s"skipif transformer ${rule.id} # ${rule.why}", sparkRecord).mkString("\n")
              val twin = if (ledgerRefuses) "" else
                "\n\n" + Seq(s"onlyif transformer ${rule.id}", engineRecord(sql, outcome, sort)).mkString("\n")
              Kept(skip + twin, Some(rule.id), rewritten.notes)
            case None =>
              held(outcome match {
                case a: Answer => s"${columnsText(a.types)} ${printed(a, sort).map(_.mkString(" ")).mkString(" | ")}"
                case f: Failure if g.isError => s"$RefusedForAnotherReason ${f.message}"
                case f: Failure => s"refused: ${f.message}"
              })
          }
      }
    }
  }

  private val TemporaryView = """(?is)CREATE\s+TEMPORARY\s+VIEW\s+(\w+)\s+AS\s+SELECT\s+(.*)""".r

  /** A statement that makes or fills a table, carried out on `engine` too: a
    * temporary view of literals becomes a table of their types holding one
    * row of them, and `USING parquet` is dropped. */
  private def statement(g: Golden, where: String, engine: Engine, viewsLeftOut: mutable.Set[String]): Port = g.sql match {
    case TemporaryView(name, items) if !items.toUpperCase(Locale.ROOT).contains(" FROM ") && engine.hasTable(name) =>
      Kept(s"# $where makes the view $name again, the table above", None)
    case TemporaryView(name, items) if !items.toUpperCase(Locale.ROOT).contains(" FROM ") =>
      val columns = Lexer.splitCommas(Lexer.tokens(items)).zipWithIndex.map { case (item, i) =>
        val (value, alias) = item.indexWhere(_.upper == "AS") match {
          case -1 => (item, s"c${i + 1}")
          case k => (item.take(k), item(k + 1).name)
        }
        val lit = value.map(_.text).mkString
        val t = value match {
          case Vector(n) if n.kind == Token.Number && n.text.contains('.') =>
            val (p, s) = Rewrite.decimalLiteralType(n.text)
            s"DECIMAL($p,$s)"
          case Vector(n) if n.kind == Token.Number => "INT"
          case Vector(s) if s.kind == Token.Text => "STRING"
          case other => throw new IllegalArgumentException(s"$where: no type for ${other.map(_.text).mkString}")
        }
        (alias, t, lit)
      }
      val create = s"CREATE TABLE $name (${columns.map { case (a, t, _) => s"$a $t" }.mkString(", ")})"
      val insert = s"INSERT INTO $name VALUES (${columns.map(_._3).mkString(", ")})"
      engine.execute(create)
      engine.execute(insert)
      Kept(Seq(s"# $where, the temporary view as a table of one row", "statement ok", create, "", "statement ok",
        insert).mkString("\n"), None, Seq(ViewNote))
    case TemporaryView(name, _) =>
      viewsLeftOut += name.toLowerCase(Locale.ROOT)
      LeftOut("a view over arrays, maps or Spark's VALUES")
    case sql =>
      val written = sql.replaceAll("(?i)\\s+using\\s+parquet\\s*$", "")
      engine.execute(written)
      val note = if (written != sql) ", USING parquet dropped" else ""
      Kept(Seq(s"# $where$note", "statement ok", written).mkString("\n"), None,
        if (written != sql) Seq(ParquetNote) else Nil)
  }

  // ---------------------------------------------------------------------------
  // A script
  // ---------------------------------------------------------------------------

  /** What became of every statement of the sources a script gathers. */
  final case class Ported(source: Source, ports: Vector[(Int, Golden, Port)])

  def portSource(source: Source, sqlTests: Path, engine: Engine): Ported = {
    val golden = parseGolden(read(sqlTests.resolve(s"results/${source.name}.sql.out")))
    val lines = sourceLines(read(sqlTests.resolve(s"inputs/${source.name}.sql")), golden.map(_.sql))
    val stop = source.stopAt.map(_._1).map(s => golden.indexWhere(_.sql.trim == s)).getOrElse(-1)
    val viewsLeftOut = mutable.Set.empty[String]
    Ported(source, golden.zip(lines).zipWithIndex.map { case ((g, line), i) =>
      val p =
        if (stop >= 0 && i >= stop) LeftOut(source.stopAt.get._2)
        else try port(source, g, line, engine, viewsLeftOut)
        catch { case NonFatal(e) => Held(line, g.sql, "", s"the converter failed: ${Runner.describe(e)}") }
      (line, g, p)
    })
  }

  private def read(p: Path): String = new String(Files.readAllBytes(p), StandardCharsets.UTF_8)

  /** The script for `parts`, the sources whose records go to it, with its
    * header. */
  def script(parts: Seq[Ported], commit: String): String = {
    val main = parts.head.source
    val all = parts.flatMap(_.ports)
    val kept = all.collect { case (_, _, k: Kept) => k }
    val records = kept.length
    val skips = kept.flatMap(_.rule).groupMapReduce(identity)(_ => 1)(_ + _)
    val leftOut = all.collect { case (_, _, LeftOut(r)) => r }.groupMapReduce(identity)(_ => 1)(_ + _)
    val held = all.count(_._3.isInstanceOf[Held])
    val files = parts.map(p => s"${p.source.name}.sql")
    val header = mutable.ArrayBuffer.empty[String]
    header += "# From Apache Spark's SQL query tests (https://github.com/apache/spark):"
    header ++= wrap(s"sql/core/src/test/resources/sql-tests/inputs/${files.mkString(" and ")} at commit $commit, " +
      s"with every answer from the golden file results/${parts.map(p => s"${p.source.name}.sql.out").mkString(" and ")} " +
      "at that commit, Spark's answer under ANSI mode, never the engine's. Licensed to the Apache Software Foundation " +
      "under the Apache License, Version 2.0: LICENSE and NOTICE in src/test/slt/spark/ hold the license and " +
      "Spark's notice. Written by //src/test/scala/com/transformer/slt:spark_import, which docs/extending.md " +
      "describes; edit the converter, not this file.")
    header += "#"
    header ++= wrap(s"${capitalize(main.about)}. $records of the file's ${all.length} statements are here" +
      (if (skips.nonEmpty) s", ${skips.values.sum} of them records the engine answers otherwise by a rule of the " +
        "ledger in docs/gotchas.md, marked skipif transformer <id> and, for a rule that is no refusal, followed by " +
        "its twin, onlyif transformer <id> with the engine's own answer" else "") + ".")
    header += "#"
    val used = kept.flatMap(_.notes).toSet
    val rewrites = Explained.collect { case (note, text) if used(note) => text }
    val timestamps = kept.exists(k => k.text.linesIterator.exists(l => l.startsWith("query ") && l.contains("TIMESTAMP")))
    val errors = kept.exists(_.text.contains("statement error"))
    header ++= wrap((if (rewrites.isEmpty) "Each statement is as Spark wrote it. " else
      s"A statement is as Spark wrote it but where a rewrite cannot change what it means, which its record names: " +
        s"${rewrites.mkString("; ")}. ") +
      "A column is the engine's type of the name Spark's schema gives it. " +
      (if (timestamps) "A TIMESTAMP Spark prints in the suite's zone is the instant of that local time in UTC, the " +
        "engine's zone, which keeps every value here, since no record moves a time between zones. " else "") +
      "A result is compared as rowsort unless its query ends in ORDER BY, as Spark sorts every result its plan " +
      "does not." +
      (if (errors) " An error under ANSI is a statement error, Spark's error class in its comment and after ---- the " +
        "engine's own words for the same reason." else ""))
    if (skips.nonEmpty) {
      header += "#"
      header += "# Skipped, by rule:"
      skips.toSeq.sortBy { case (id, n) => (-n, id) }.foreach { case (id, n) => header += f"#   $n%4d  $id" }
    }
    if (leftOut.nonEmpty || held > 0) {
      header += "#"
      header += s"# Left out (${leftOut.values.sum + held} statements), by what they need:"
      leftOut.toSeq.sortBy { case (r, n) => (-n, r) }.foreach { case (r, n) =>
        wrap(r, 70).map(_.stripPrefix("# ")).zipWithIndex.foreach { case (l, i) =>
          header += (if (i == 0) f"#   $n%4d  $l" else s"#         $l")
        }
      }
      val refusedByBoth = all.count {
        case (_, _, h: Held) => h.engine.startsWith(RefusedForAnotherReason)
        case _ => false
      }
      if (refusedByBoth > 0) {
        header += f"#   $refusedByBoth%4d  both engines refuse it, for different reasons, which is no"
        header += "#         agreement: each is held for the user (plans/testing/10)"
      }
      if (held > refusedByBoth)
        header += f"#   ${held - refusedByBoth}%4d  the engine answers otherwise, by no rule of the ledger: held for the user"

    }
    (header.mkString("\n") +: kept.map(_.text)).mkString("\n\n") + "\n"
  }

  private def capitalize(s: String): String = if (s.isEmpty) s else s.head.toUpper.toString + s.tail

  /** `text` as comment lines of at most 79 characters. */
  private def wrap(text: String, width: Int = 77): Seq[String] = {
    val lines = mutable.ArrayBuffer.empty[String]
    val cur = new StringBuilder
    text.split(" ").foreach { w =>
      if (cur.nonEmpty && cur.length + 1 + w.length > width) { lines += "# " + cur.result(); cur.clear() }
      if (cur.nonEmpty) cur += ' '
      cur ++= w
    }
    if (cur.nonEmpty) lines += "# " + cur.result()
    lines.toSeq
  }

  // ---------------------------------------------------------------------------
  // Main
  // ---------------------------------------------------------------------------

  def main(args: Array[String]): Unit = {
    var sqlTests: Option[Path] = None
    var commit: Option[String] = None
    var out = workspace("src/test/slt/spark")
    var report: Option[Path] = None
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--sql-tests" => i += 1; sqlTests = Some(Paths.get(args(i)))
        case "--commit" => i += 1; commit = Some(args(i))
        case "--out" => i += 1; out = workspace(args(i))
        case "--report" => i += 1; report = Some(workspace(args(i)))
        case other => throw new IllegalArgumentException(s"unknown argument $other")
      }
      i += 1
    }
    val tests = sqlTests.getOrElse(throw new IllegalArgumentException("--sql-tests names the sql-tests directory"))
    val sha = commit.getOrElse(throw new IllegalArgumentException("--commit names Spark's commit"))
    // The sources of one script share its tables, as its records do.
    val ported = Sources.groupBy(_.script).toSeq.flatMap { case (_, sources) =>
      val engine = new Engine
      try sources.sortBy(s => if (s.about.nonEmpty) 0 else 1).map(portSource(_, tests, engine))
      finally engine.close()
    }
    val lines = mutable.ArrayBuffer.empty[String]
    ported.groupBy(_.source.script).toSeq.sortBy(_._1).foreach { case (name, parts0) =>
      val parts = parts0.sortBy(p => if (p.source.about.nonEmpty) 0 else 1)
      val all = parts.flatMap(_.ports)
      val kept = all.count(_._3.isInstanceOf[Kept])
      if (name.nonEmpty && kept > all.count(_._2.isStatement)) {
        val path = out.resolve(name)
        Files.createDirectories(path.getParent)
        Files.write(path, script(parts, sha).getBytes(StandardCharsets.UTF_8))
      }
      lines += s"== ${if (name.isEmpty) parts.map(_.source.name).mkString(", ") else name}: ${all.length} statements, " +
        s"$kept kept"
      all.collect { case (_, _, Kept(_, Some(id), _)) => id }.groupMapReduce(identity)(_ => 1)(_ + _).toSeq.sortBy(-_._2)
        .foreach { case (id, n) => lines += f"  skipped $n%5d  $id" }
      all.collect { case (_, _, LeftOut(r)) => r }.groupMapReduce(identity)(_ => 1)(_ + _).toSeq.sortBy(-_._2)
        .foreach { case (r, n) => lines += f"  left out $n%4d  $r" }
      all.collect { case (_, _, h: Held) => h }.foreach { h =>
        lines += s"  HELD line ${h.line}: ${h.sql.replace("\n", " ")}\n      spark:  ${h.spark}\n      engine: ${h.engine}"
      }
    }
    val text = lines.mkString("\n")
    System.out.println(text)
    report.foreach(p => Files.write(p, (text + "\n").getBytes(StandardCharsets.UTF_8)))
  }

  /** A path given on the command line: under `bazel run` a relative one is
    * relative to the workspace, not the runfiles directory it runs in. */
  private def workspace(p: String): Path = {
    val path = Paths.get(p)
    if (path.isAbsolute) path
    else Option(System.getenv("BUILD_WORKSPACE_DIRECTORY")).map(Paths.get(_).resolve(path)).getOrElse(path)
  }
}
