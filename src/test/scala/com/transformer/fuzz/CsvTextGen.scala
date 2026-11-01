package com.transformer.fuzz

import com.transformer.core.{DataType, Field, Schema}

import java.nio.charset.{Charset, StandardCharsets}
import java.time.{Instant, LocalDate}

/** Generators for the CSV properties: data written by `CsvWriter` and read
  * back (`oracle/CsvRoundTrip.checkData`), CSV text written here and parsed
  * (`CsvRoundTrip.checkText`), and data read back by inference
  * (`oracle/InferenceRoundTrip`).
  *
  * Every case carries a [[Format]], an option bag of the kind a job's input or
  * output carries (`CsvWriteOptions.fromMap`, `CsvOptions.fromMap`): a
  * delimiter, quote and escape from a pool that reaches past ASCII and puts the
  * escape apart from the quote, a null string that is empty or a word, a line
  * ending of each kind, and a charset that is not always Unicode. Only
  * combinations the options accept are drawn; the refused ones are pinned by
  * `csv_writer_test`.
  *
  * Values are chosen against the format they are written in: strings that hold
  * the delimiter, the quote, the escape, a line break of each kind, the null
  * string itself, the empty string, a leading byte-order mark, spaces at either
  * end, characters past the charset, half a surrogate pair, and text that looks
  * like a number, a boolean or a date; and for the other nine types their
  * edges (`NaN`, `-0.0`, a year past 9999 or before 1, a microsecond before
  * 1970, a decimal at the end of its precision, the empty byte string).
  */
object CsvTextGen {

  // ---- formats --------------------------------------------------------------

  /** One option bag, as a job passes it to the writer and reader. */
  final case class Format(
      header: Boolean,
      delimiter: Char,
      quote: Char,
      escape: Char,
      nullValue: String,
      lineEnding: String,
      charset: Charset) {
    def options: Map[String, String] = Map(
      "header" -> header.toString,
      "delimiter" -> (if (delimiter == '\t') "\\t" else delimiter.toString),
      "quote" -> quote.toString,
      "escape" -> (if (escape == '\t') "\\t" else escape.toString),
      "nullValue" -> nullValue,
      "lineEnding" -> lineEnding,
      "charset" -> charset.name)

    /** Whether the charset encodes every character. */
    def unicode: Boolean = charset.name.toUpperCase.startsWith("UTF-")

    override def toString: String =
      s"Format(header=$header, delimiter=${show(delimiter.toString)}, quote=${show(quote.toString)}, " +
        s"escape=${show(escape.toString)}, nullValue=${show(nullValue)}, lineEnding=${show(lineEnding)}, ${charset.name})"
  }

  /** The default bag: what most jobs use. */
  val DefaultFormat: Format = Format(header = true, ',', '"', '"', "", "\n", StandardCharsets.UTF_8)

  private val Delimiters: Seq[Char] = Seq(',', ',', ',', ';', '\t', '|', ' ', 'x', 0xa7.toChar)
  private val Quotes: Seq[Char] = Seq('"', '"', '"', '\'', 0xab.toChar)
  private val NullValues: Seq[String] = Seq("", "", "", "NULL", "\\N", "NA")
  private val LineEndings: Seq[String] = Seq("\n", "\n", "\r\n", "\r")
  private val Charsets: Seq[Charset] = Seq(StandardCharsets.UTF_8, StandardCharsets.UTF_8, StandardCharsets.UTF_8,
    StandardCharsets.UTF_16, StandardCharsets.UTF_16LE, StandardCharsets.ISO_8859_1)

  def drawFormat(rng: Rng): Format =
    if (rng.bool(0.3)) DefaultFormat.copy(header = rng.bool(0.8))
    else Iterator.continually {
      val quote = rng.oneOf(Quotes)
      Format(
        header = rng.bool(0.8),
        delimiter = rng.oneOf(Delimiters),
        quote = quote,
        escape = rng.weighted((3, quote), (1, '\\'), (1, '~')),
        nullValue = rng.oneOf(NullValues),
        lineEnding = rng.oneOf(LineEndings),
        charset = rng.oneOf(Charsets))
    }.find(valid).get

  /** The separators the options accept: `CsvOptions.checkSeparators`, restated
    * so a refused bag is never drawn. */
  private def valid(f: Format): Boolean =
    f.delimiter != f.quote && f.delimiter != f.escape &&
      !f.nullValue.exists(c => c == f.delimiter || c == '\n' || c == '\r') && !f.nullValue.headOption.contains(f.quote) &&
      // A delimiter or quote the charset cannot encode could never be written.
      Seq(f.delimiter, f.quote, f.escape).forall(c => f.charset.newEncoder().canEncode(c))

  // ---- values ---------------------------------------------------------------

  /** Strings that stress the format they are written in. */
  private def drawString(rng: Rng, f: Format): String = rng.weighted(
    (6, rng.oneOf(PlainStrings)),
    (2, ""),
    (2, f.nullValue),
    (3, rng.oneOf(Seq(s"a${f.delimiter}b", s"${f.delimiter}", s"${f.quote}", s"a${f.quote}b${f.quote}",
      s"${f.quote}${f.quote}", s"${f.escape}${f.quote}", s"${f.escape}${f.escape}x",
      s"${f.quote}starts", "a\nb", "a\r\nb", "a\rb", "\n", "trailing\n"))),
    // A value ending in the escape, quoted (it holds the delimiter) or not:
    // inside quotes an unescaped one escapes the closing quote.
    (2, rng.oneOf(Seq(s"ends${f.escape}", s"${f.escape}", s"x${f.delimiter}${f.escape}", s"a${f.escape}b"))),
    (2, rng.oneOf(Seq(" spaced ", "  ", " ", "\t"))),
    (1, s"${0xfeff.toChar}bom"),
    (2, rng.oneOf(Lookalikes.flatMap(_._2))),
    (2, unicodeString(rng)),
    (1, "x" * rng.between(5000, 20000)),
    (1, if (rng.bool(0.5)) "x" + 0xd83d.toChar else s"${0xde00.toChar}y"))

  private val PlainStrings: Seq[String] = Seq("a", "b", "abc", "hello world", "x", "NULL", "null", "N/A", "-", "0")

  /** Code points from every range a charset encodes differently. */
  private def unicodeString(rng: Rng): String = {
    val sb = new java.lang.StringBuilder
    var i = rng.between(1, 6)
    while (i > 0) {
      sb.appendCodePoint(rng.weighted((3, rng.between(0x20, 0x7e)), (2, rng.between(0xa0, 0xff)),
        (2, rng.between(0x100, 0x7ff)), (2, rng.between(0x4e00, 0x4fff)), (1, rng.between(0x1f600, 0x1f64f))))
      i -= 1
    }
    sb.toString
  }

  /** Text that looks like a value of another type, by the category it reads
    * as under the reader's grammar (or does not, for the Java-only and
    * malformed ones). */
  private[fuzz] val Lookalikes: Seq[(String, Seq[String])] = Seq(
    "int" -> Seq("0", "7", "-3", "007", "+5", "-0", "2147483647", "-2147483648"),
    "long" -> Seq("2147483648", "-2147483649", "9223372036854775807", "-9223372036854775808"),
    "wide integer" -> Seq("9223372036854775808", "99999999999999999999", "-99999999999999999999999999999999999999"),
    "double" -> Seq("1.5", ".5", "5.", "1e5", "-1E-5", "0.1", "NaN", "Infinity", "-Infinity", "1.0",
      "1" + "0" * 40, "100000000000000000000000000000000000000.5"),
    "java-only number" -> Seq("1d", "1f", "0x1p3", " 1", "1 ", "1_000", 0x0661.toChar.toString + 0x0662.toChar,
      "+NaN", "nan", "inf", "1e", "."),
    "number no double holds" -> Seq("1e400", "-1e400", "1e-400", "1" + "0" * 400),
    "boolean" -> Seq("true", "false", "TRUE", "False", "tRuE"),
    "boolean-ish" -> Seq("yes", "no", "t", "f", "on"),
    "date" -> Seq("2024-01-01", "2024-02-29", "1969-12-31", "+10000-01-01", "-0001-06-15", "0000-01-01"),
    "not a date" -> Seq("2024-02-30", "2024-1-1", "20240101", "2023-02-29"),
    "timestamp" -> Seq("2024-01-01T00:00:00", "2024-01-01T12:30:00Z", "2024-01-01T00:00:00.123456789Z",
      "2024-01-01T00:00:00+01:00", "1969-12-31T23:59:59.9999995Z", "2024-01-01T00:00"),
    "not a timestamp" -> Seq("2024-01-01 00:00:00", "2024-01-01T25:00:00", "12:30:00"),
    "text" -> Seq("abc", "N/A", "-", "null", "NULL"))

  /** A value of `dt`, from the edges its text is easy to get wrong at. */
  private def drawValue(rng: Rng, dt: DataType, f: Format): Any = dt match {
    case DataType.StringType => drawString(rng, f)
    case DataType.IntType => rng.oneOf(Seq(0, 1, -1, 7, Int.MaxValue, Int.MinValue, rng.nextInt()))
    case DataType.LongType => rng.oneOf(Seq(0L, -1L, Long.MaxValue, Long.MinValue, 1L << 53, (1L << 53) + 1, rng.nextLong()))
    case DataType.FloatType => rng.oneOf(Seq(0.0f, -0.0f, 0.1f, 1.0f / 3, Float.NaN, Float.PositiveInfinity,
      Float.NegativeInfinity, Float.MinPositiveValue, Float.MaxValue, java.lang.Float.intBitsToFloat(rng.nextInt())))
    case DataType.DoubleType => rng.oneOf(Seq(0.0, -0.0, 0.1, 1.0 / 3, 1e300, -1e-300, Double.NaN,
      Double.PositiveInfinity, Double.NegativeInfinity, Double.MinPositiveValue, Double.MaxValue,
      java.lang.Double.longBitsToDouble(rng.nextLong())))
    case DataType.BooleanType => rng.bool()
    case DataType.DateType => rng.oneOf(Seq(LocalDate.of(1970, 1, 1), LocalDate.of(1969, 12, 31), LocalDate.of(2024, 2, 29),
      LocalDate.of(10000, 1, 1), LocalDate.of(-5, 3, 1), LocalDate.of(0, 1, 1), LocalDate.of(9999, 12, 31)))
    case DataType.TimestampType => rng.oneOf(Seq("1970-01-01T00:00:00Z", "1969-12-31T23:59:59.999999Z",
      "2024-03-10T07:00:00.000001Z", "+10000-01-01T00:00:00Z", "-0001-06-01T12:00:00.5Z", "2024-01-01T00:00:00Z"
    ).map(Instant.parse))
    case d: DataType.DecimalType =>
      val max = java.math.BigInteger.TEN.pow(d.precision).subtract(java.math.BigInteger.ONE)
      val unscaled = rng.oneOf(Seq(java.math.BigInteger.ZERO, java.math.BigInteger.ONE, max, max.negate,
        java.math.BigInteger.valueOf(rng.between(-999, 999).toLong)))
      // The engine holds a decimal at its declared scale or at a smaller
      // one (1.5 in a DECIMAL(9,2)); both write and read back as the value.
      val v = new java.math.BigDecimal(unscaled, d.scale)
      if (rng.bool(0.3)) v.stripTrailingZeros else v
    case DataType.BinaryType =>
      val b = new Array[Byte](rng.weighted((1, 0), (4, rng.between(1, 8)), (1, rng.between(100, 300))))
      b.indices.foreach(i => b(i) = rng.nextInt().toByte)
      b
    case other => throw new IllegalArgumentException(s"no CSV value drawn for $other")
  }

  private val Types: Seq[DataType] = ExprGen.GenTypes

  /** A column name: usually plain, sometimes one the header has to quote. */
  private def drawName(rng: Rng, i: Int, f: Format): String = rng.weighted(
    (8, s"c$i"),
    (1, s"c$i${f.delimiter}x"),
    (1, s"${f.quote}c$i"),
    (1, s"c$i ${0x4e2d.toChar}"))

  // ---- 1. data written and read back ------------------------------------------

  /** `rows` written in `format` across `partitions` part files (contiguous
    * slices, the last ones possibly empty, or none at all). */
  final case class DataCase(schema: Schema, rows: Vector[Vector[Any]], format: Format, partitions: Int) {
    override def toString: String = {
      val cols = schema.fields.map(c => s"${show(c.name)}:${c.dataType}").mkString(", ")
      val preview = rows.take(5).map(_.map(showValue).mkString("(", ", ", ")")).mkString("; ")
      s"DataCase(schema=[$cols], $format, partitions=$partitions, rows=${rows.length}: [$preview${if (rows.length > 5) " ..." else ""}])"
    }
  }

  def dataCase(rng: Rng): DataCase = {
    val shape = rng.split()
    val data = rng.split()
    val format = drawFormat(shape)
    val width = shape.weighted((4, 1), (3, 2), (2, 3), (1, 5))
    val schema = Schema((0 until width).map(i => Field(drawName(shape, i, format), ExprGen.pick(shape, Types))).toVector)
    val numRows = shape.weighted((1, 0), (2, 1), (5, shape.between(2, 12)), (2, shape.between(13, 60)))
    val nullProb = schema.fields.map(_ => data.weighted((3, 0.0), (3, 0.2), (1, 0.6), (1, 1.0)))
    val rows = Vector.fill(numRows)(schema.fields.indices.map { c =>
      if (data.bool(nullProb(c))) null else drawValue(data, schema.fields(c).dataType, format)
    }.toVector)
    val partitions = if (numRows == 0) shape.weighted((1, 0), (2, 1), (1, 3)) else shape.weighted((5, 1), (2, 2), (1, 4))
    DataCase(schema, rows, format, partitions)
  }

  // ---- 2. text read --------------------------------------------------------------

  /** A field as the text writes it: its value, whether it is quoted, and,
    * inside quotes, whether quotes are doubled rather than escaped. Text after
    * the closing quote (`"ab"c`) is `trailing`. */
  final case class TextField(value: String, quoted: Boolean, doubled: Boolean, trailing: String = "")

  sealed trait Line
  /** A row: its fields and the line break after it ("" for none, at the end). */
  final case class Row(fields: Vector[TextField], ending: String) extends Line
  final case class Blank(ending: String) extends Line

  /** How a case's text is malformed, if it is. */
  sealed trait Malformed
  object Malformed {
    /** The last row's last field opens a quote it never closes. */
    case object UnclosedQuote extends Malformed
    /** One row (`at`) has an extra field. */
    final case class ExtraField(at: Int) extends Malformed
    /** One row (`at`) is missing its last field. */
    final case class MissingField(at: Int) extends Malformed
    /** A byte the charset holds no text for, in the row `at`. */
    final case class InvalidBytes(at: Int) extends Malformed
  }

  /** CSV text: an optional header and data lines, each written in `format`,
    * a leading byte-order mark, and at most one malformation. */
  final case class TextCase(
      format: Format,
      width: Int,
      header: Option[Row],
      lines: Vector[Line],
      bom: Boolean,
      leadingBlanks: Int,
      malformed: Option[Malformed]) {
    override def toString: String =
      s"TextCase($format, width=$width, bom=$bom, leadingBlanks=$leadingBlanks, malformed=$malformed,\n" +
        s"  text=${show(CsvTextGen.render(this))})"
  }

  def textCase(rng: Rng): TextCase = {
    val shape = rng.split()
    val data = rng.split()
    val format = drawFormat(shape).copy(header = shape.bool(0.6))
    val width = shape.weighted((4, 1), (3, 2), (2, 3), (1, 6))
    def field(): TextField = {
      val value = drawTextValue(data, format)
      // A value the format cannot leave bare must be quoted: one holding the
      // delimiter or a line break, or starting with the quote, or with a
      // byte-order mark, which the reader drops at the start of a file.
      val mustQuote = value.exists(c => c == format.delimiter || c == '\n' || c == '\r') ||
        value.headOption.exists(c => c == format.quote || c == 0xfeff.toChar) || value.isEmpty && data.bool(0.5)
      val quoted = mustQuote || data.bool(0.3)
      val trailing = if (quoted && data.bool(0.05)) "z" else ""
      TextField(value, quoted, doubled = format.escape == format.quote || data.bool(0.4), trailing)
    }
    /** A row of one bare empty field is a blank line; at the end of the file,
      * with no line break, it is no line at all. */
    def fields(lastWithoutEnding: Boolean): Vector[TextField] = {
      val fs = Vector.fill(width)(field())
      if (lastWithoutEnding && width == 1 && !fs.head.quoted && fs.head.value.isEmpty) Vector(fs.head.copy(quoted = true))
      else fs
    }
    def ending(): String = shape.oneOf(Seq("\n", "\n", "\r\n", "\r"))
    // A header is never blank: the reader skips blank lines before it.
    val header = if (format.header) Some(Row(fields(lastWithoutEnding = true), ending())) else None
    val numLines = shape.weighted((1, 0), (3, shape.between(1, 5)), (3, shape.between(6, 30)), (1, shape.between(31, 120)))
    val lines = Vector.tabulate(numLines) { i =>
      val last = i == numLines - 1
      if (shape.bool(0.12)) Blank(if (last && shape.bool(0.5)) "" else ending())
      else {
        val end = if (last && shape.bool(0.4)) "" else ending()
        Row(fields(lastWithoutEnding = end.isEmpty), end)
      }
    }
    val rowIndices = lines.indices.filter(i => lines(i).isInstanceOf[Row])
    val malformed =
      if (rowIndices.isEmpty || !shape.bool(0.12)) None
      else Some(shape.weighted(
        (1, Malformed.UnclosedQuote),
        (2, Malformed.ExtraField(shape.oneOf(rowIndices))),
        (if (width > 1) 2 else 1, Malformed.MissingField(shape.oneOf(rowIndices))),
        (1, Malformed.InvalidBytes(shape.oneOf(rowIndices)))))
    val kept = malformed.filter {
      // A missing field in a one-column row would leave a blank line.
      case Malformed.MissingField(_) => width > 1
      // Every byte is a Latin-1 character.
      case Malformed.InvalidBytes(_) => format.charset != StandardCharsets.ISO_8859_1
      case _ => true
    }
    // A row cut to one bare empty field would be a blank line, which a wide
    // file skips: quote what is left, so the short row is a row.
    val shortened = kept match {
      case Some(Malformed.MissingField(at)) => lines(at) match {
        case r: Row if width == 2 && !r.fields.head.quoted && r.fields.head.value.isEmpty =>
          lines.updated(at, r.copy(fields = r.fields.updated(0, r.fields.head.copy(quoted = true))))
        case _ => lines
      }
      case _ => lines
    }
    // A byte-order mark is a Unicode character; Latin-1 has none.
    TextCase(format, width, header, shortened, bom = shape.bool(0.15) && format.unicode,
      leadingBlanks = shape.weighted((6, 0), (1, 1), (1, 2)), malformed = kept)
  }

  /** A field's value for the text property: anything a field can hold,
    * encodable in the format's charset (the writer property covers the
    * rest). */
  private def drawTextValue(rng: Rng, f: Format): String = {
    val v = drawString(rng, f)
    val encoder = f.charset.newEncoder()
    if (v.exists(Character.isSurrogate) && !hasPairsOnly(v) || !encoder.canEncode(v)) "plain" else v
  }

  private def hasPairsOnly(s: String): Boolean = {
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))) i += 2
      else if (Character.isSurrogate(c)) return false
      else i += 1
    }
    true
  }

  /** A case's text and where its parts begin in it: the header, each line,
    * an unclosed quote and an invalid byte, as character offsets. */
  final case class Rendered(text: String, header: Option[Int], lines: Vector[Int], unclosedQuote: Option[Int],
      invalidByte: Option[Int]) {
    /** The line `offset` is on, counting from 1, a line ending at `\n`,
      * `\r\n` or a lone `\r`. */
    def lineOf(offset: Int): Long = {
      var line = 1L
      var i = 0
      while (i < offset) {
        val c = text.charAt(i)
        if (c == '\n') line += 1
        else if (c == '\r' && !(i + 1 < text.length && text.charAt(i + 1) == '\n')) line += 1
        i += 1
      }
      line
    }
  }

  /** The text of a case, as a file holds it before encoding. */
  def render(c: TextCase): String = layout(c).text

  def layout(c: TextCase): Rendered = {
    val f = c.format
    val sb = new java.lang.StringBuilder
    if (c.bom) sb.append(0xfeff.toChar)
    // Blank lines before a header are never the header; without one they
    // are rows of a one-column file.
    if (c.header.isDefined) (0 until c.leadingBlanks).foreach(_ => sb.append('\n'))
    def renderField(t: TextField): String =
      if (!t.quoted) t.value
      else {
        val body = t.value.flatMap { ch =>
          if (ch == f.quote) { if (t.doubled) s"${f.quote}${f.quote}" else s"${f.escape}${f.quote}" }
          else if (f.escape != f.quote && ch == f.escape) s"${f.escape}${f.escape}"
          else ch.toString
        }
        s"${f.quote}$body${f.quote}${t.trailing}"
      }
    def renderRow(r: Row, index: Option[Int]): String = {
      val fields = c.malformed match {
        // Quoted, so no delimiter splits it.
        case Some(Malformed.ExtraField(at)) if index.contains(at) => r.fields :+ TextField("extra", quoted = true, doubled = true)
        case Some(Malformed.MissingField(at)) if index.contains(at) => r.fields.init
        case _ => r.fields
      }
      fields.map(renderField).mkString(f.delimiter.toString)
    }
    val headerAt = c.header.map { h =>
      val at = sb.length
      sb.append(renderRow(h, None)).append(h.ending)
      at
    }
    // An empty line ending in `\n` straight after a lone `\r` would make one
    // `\r\n` break with it, not two lines: it is written `\r\n` instead.
    def appendEnding(ending: String, emptyLine: Boolean): Unit =
      if (emptyLine && ending == "\n" && sb.length > 0 && sb.charAt(sb.length - 1) == '\r') sb.append("\r\n")
      else sb.append(ending)
    val lineAt = Vector.newBuilder[Int]
    var quoteAt: Option[Int] = None
    var badAt: Option[Int] = None
    val lastRow = c.lines.lastIndexWhere(_.isInstanceOf[Row])
    var i = 0
    while (i < c.lines.length && quoteAt.isEmpty) {
      lineAt += sb.length
      c.lines(i) match {
        case r: Row =>
          sb.append(renderRow(r, Some(i)))
          if (c.malformed.contains(Malformed.UnclosedQuote) && i == lastRow) {
            // Open a quote in a new last field and never close it; nothing
            // after it is text of its own.
            sb.append(f.delimiter)
            quoteAt = Some(sb.length)
            sb.append(f.quote).append("never closed")
          } else {
            val start = sb.length
            if (c.malformed.contains(Malformed.InvalidBytes(i))) {
              badAt = Some(sb.length)
              sb.append(InvalidByteMarker)
            }
            appendEnding(if (r.ending.isEmpty && i < c.lines.length - 1) "\n" else r.ending, emptyLine = sb.length == start &&
              renderRow(r, Some(i)).isEmpty)
          }
        case Blank(ending) =>
          appendEnding(if (ending.isEmpty && i < c.lines.length - 1) "\n" else ending, emptyLine = true)
      }
      i += 1
    }
    Rendered(sb.toString, headerAt, lineAt.result(), quoteAt, badAt)
  }

  /** Where a malformed case puts a byte with no text: the encoder writes the
    * text, then this marker is replaced by a byte the charset cannot
    * decode. A private-use character no generated value holds. */
  private[fuzz] val InvalidByteMarker: Char = 0xf8ff.toChar

  /** The bytes of a case's text in its charset, with the invalid byte in
    * place of its marker. */
  def encode(c: TextCase): Array[Byte] = {
    val text = render(c)
    val at = text.indexOf(InvalidByteMarker.toInt)
    if (at < 0) text.getBytes(c.format.charset)
    else {
      val before = text.substring(0, at).getBytes(c.format.charset)
      val after = encodeContinuing(text.substring(at + 1), c.format.charset)
      // A lone continuation byte in UTF-8, half a code unit in UTF-16LE and
      // a lone low surrogate's bytes in UTF-16 are each no text.
      val bad: Array[Byte] = c.format.charset match {
        case StandardCharsets.UTF_8 => Array(0x80.toByte)
        case StandardCharsets.UTF_16LE => Array(0x00.toByte, 0xdc.toByte)
        case _ => Array(0xdc.toByte, 0x00.toByte)
      }
      before ++ bad ++ after
    }
  }

  /** `s` in `cs` as the continuation of a file: no byte-order mark. */
  private def encodeContinuing(s: String, cs: Charset): Array[Byte] =
    if (cs == StandardCharsets.UTF_16) s.getBytes(StandardCharsets.UTF_16BE) else s.getBytes(cs)

  // ---- 3. data read back by inference ---------------------------------------------

  /** Columns of every type, and STRING columns of text that looks like other
    * types, written across `files` and read back with a sample of
    * `sampleRows` rows. */
  /** How a file of a glob after the first writes the columns: in `order`
    * (the schema's column at each position), its header's names in upper
    * case when `upper`. A glob is read by each later file's header's names,
    * regardless of case (plans/testing/04, finding 3). */
  final case class Layout(order: Vector[Int], upper: Boolean) {
    override def toString: String = s"${order.mkString("[", ",", "]")}${if (upper) " upper" else ""}"
  }

  /** `layouts` holds one per file after the first, which writes the schema's
    * order. */
  final case class InferenceCase(schema: Schema, rows: Vector[Vector[Any]], format: Format, files: Int, sampleRows: Int,
      layouts: Vector[Layout] = Vector.empty) {
    override def toString: String = {
      val cols = schema.fields.map(c => s"${c.name}:${c.dataType}").mkString(", ")
      val preview = rows.take(6).map(_.map(showValue).mkString("(", ", ", ")")).mkString("; ")
      s"InferenceCase(schema=[$cols], $format, files=$files, sampleRows=$sampleRows, " +
        s"layouts=${layouts.mkString("[", "; ", "]")}, rows=${rows.length}: " +
        s"[$preview${if (rows.length > 6) " ..." else ""}])"
    }
  }

  def inferenceCase(rng: Rng): InferenceCase = {
    val shape = rng.split()
    val data = rng.split()
    // Inference reads names from the header, and every text property of
    // the format is the writer's, which `checkData` covers.
    val format = drawFormat(shape).copy(header = true)
    val width = shape.between(1, 4)
    val columns = (0 until width).map { _ =>
      if (shape.bool(0.5)) Left(Seq.fill(shape.between(1, 3))(shape.oneOf(Lookalikes.indices)).distinct)
      else Right(ExprGen.pick(shape, Types))
    }
    val schema = Schema(columns.indices.map(i => Field(s"c$i", columns(i).fold(_ => DataType.StringType, identity))).toVector)
    val numRows = shape.weighted((1, 1), (5, shape.between(2, 12)), (2, shape.between(13, 60)))
    val nullProb = columns.map(_ => data.weighted((4, 0.0), (3, 0.2), (1, 0.8)))
    val rows = Vector.fill(numRows)(columns.indices.map { c =>
      if (data.bool(nullProb(c))) null
      else columns(c) match {
        case Left(categories) => data.oneOf(Lookalikes(data.oneOf(categories))._2)
        case Right(dt) => drawValue(data, dt, format)
      }
    }.toVector)
    // Strings the charset cannot hold would fail the write, which the data
    // property checks; here they would only end the case early.
    val writable = rows.map(_.map {
      case s: String if !hasPairsOnly(s) || !format.charset.newEncoder().canEncode(s) => "plain"
      case v => v
    })
    val files = shape.weighted((4, 1), (2, 2), (1, 3))
    // Half of a glob's later files write the columns in another order, and
    // some name them in upper case.
    val layouts = Vector.fill(files - 1) {
      val order = if (shape.bool(0.5)) Vector.tabulate(width)(i => (shape.nextLong(), i)).sortBy(_._1).map(_._2)
        else (0 until width).toVector
      Layout(order, upper = shape.bool(0.3))
    }
    InferenceCase(schema, writable, format, files, sampleRows = shape.weighted((4, 1000), (1, 1), (2, shape.between(2, 5))),
      layouts)
  }

  // ---- 4. number text read by a CAST and by a column ------------------------------

  /** Text to read as a number or a boolean, and the type to read it as. */
  final case class NumberTextCase(text: String, dataType: DataType) {
    override def toString: String = s"NumberTextCase(${show(text)} as $dataType)"
  }

  private val NumberCategories: Set[String] =
    Set("int", "long", "wide integer", "double", "java-only number", "number no double holds", "boolean", "boolean-ish")

  private val NumberTypes: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType, DataType.BooleanType)

  def numberTextCase(rng: Rng): NumberTextCase = {
    val text = rng.weighted[() => String](
      (3, () => rng.oneOf(Lookalikes.filter(c => NumberCategories(c._1)).flatMap(_._2))),
      (5, () => composedNumber(rng)))()
    NumberTextCase(text, rng.oneOf(NumberTypes))
  }

  /** A number put together from the parts Java's grammar and the engine's
    * read differently: a sign, digits of ASCII or of another script, a point,
    * an exponent at either float's edges or past them, a type suffix, a
    * hexadecimal float, and space around it. */
  private def composedNumber(rng: Rng): String = {
    val sb = new StringBuilder
    if (rng.bool(0.3)) sb.append(rng.oneOf(Seq("+", "-")))
    def digits(n: Int): String =
      (0 until n).map(_ => if (rng.bool(0.05)) (0x0660 + rng.between(0, 9)).toChar else ('0' + rng.between(0, 9)).toChar)
        .mkString
    if (rng.bool(0.1)) sb.append("0x1p").append(rng.between(-5, 5))
    else {
      sb.append(digits(rng.between(0, 3)))
      if (rng.bool(0.4)) sb.append('.').append(digits(rng.between(0, 3)))
      if (rng.bool(0.5)) sb.append(rng.oneOf(Seq("e", "E"))).append(rng.oneOf(Seq("", "+", "-")))
        .append(rng.oneOf(Seq("0", "5", "37", "38", "39", "44", "45", "46", "307", "308", "309", "323", "324", "400")))
    }
    if (rng.bool(0.1)) sb.append(rng.oneOf(Seq("d", "f", "D", "F", "L")))
    val core = sb.toString
    rng.weighted((8, core), (1, " " + core), (1, core + " "))
  }

  // ---- rendering for failure messages --------------------------------------------

  /** A string with its control and non-ASCII characters escaped. */
  def show(s: String): String =
    if (s == null) "NULL"
    else if (s.length > 200) show(s.take(60)) + s"...(${s.length} chars)"
    else "\"" + s.flatMap(c => if (c < 0x20 || c > 0x7e) f"\\u${c.toInt}%04x" else c.toString) + "\""

  def showValue(v: Any): String = v match {
    case s: String => show(s)
    case other => RowOracle.show(other)
  }

}
