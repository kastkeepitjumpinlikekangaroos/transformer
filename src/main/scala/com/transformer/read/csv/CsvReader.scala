package com.transformer.read.csv

import com.transformer.core._

import java.io.{BufferedInputStream, BufferedReader, InputStreamReader, Reader}
import java.nio.charset.CodingErrorAction
import java.nio.file.{Files, Path}

/** Reads a folder/glob of CSV files as a single named view. One file is one partition
  * (the unit of parallelism for the executor).
  *
  * Schema is determined once at construction time. If `options.inferSchema` is true,
  * the first `inferenceSampleRows` rows of the files, in glob order, are sampled
  * ([[CsvReader.infer]]). Otherwise `options.columns` must be set.
  *
  * With an inferred schema and a header, the first file's header names the
  * columns and every later file is read by its own header's names, so a file
  * that writes the columns in another order reads into the right ones, and one
  * that names other columns fails, naming both headers; with declared columns
  * a header is skipped. Every row must have as many fields as the file has
  * columns (its header, or the schema, or its first row): a short or long row
  * fails the read, naming the file and line. A blank line is a row only in a
  * one-column file, where it is the row whose one field is empty, which is how
  * the writer writes a NULL there; in a wider file it is skipped. Each field is
  * parsed strictly as its column's type ([[CsvValues]]), and one that does not
  * read as it fails the read, naming the file, line, column and text. A field
  * is NULL when it is the null string unquoted, and, in a column of any type but
  * STRING and BINARY (the two with an empty value), when it is empty, quoted or
  * not.
  */
final class CsvReader(val files: IndexedSeq[Path], val options: CsvOptions) extends CatalogView {

  require(files.nonEmpty, "CsvReader requires at least one file")

  /** What the files' text says when the types are inferred: the schema, the
    * files its sample came from, and the header every later file is read by. */
  private val inferred: Option[CsvReader.Inferred] =
    if (options.inferSchema) Some(CsvReader.infer(files, options)) else None

  val schema: Schema = inferred match {
    case Some(i) => i.schema
    case None =>
      options.columns match {
        case Some(cols) => Schema(cols.toVector)
        case None => throw new IllegalArgumentException(
          "CsvOptions.columns must be set when inferSchema = false"
        )
      }
  }

  /** Where the column types came from, for an error about a value that does
    * not read as one. */
  private val typeOrigin: Option[String] = inferred.map { i =>
    s"its type was inferred from the first ${options.inferenceSampleRows} rows of ${CsvReader.describe(i.sampled)}"
  }

  def numPartitions: Int = files.length

  /** Its strings are decoded text: the decoder refuses half a surrogate pair. */
  override def stringsAreText: Boolean = true

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    require(p >= 0 && p < files.length, s"partition $p out of range [0,${files.length})")
    val header = if (p == 0) None else inferred.flatMap(_.header).map(_ -> files.head)
    new CsvPartitionIterator(files(p), schema, options, typeOrigin, header)
  }
}

object CsvReader {

  /** The schema inference read off a glob's text, the files its sample came
    * from, and the first file's header names (with a header). */
  private[csv] final case class Inferred(schema: Schema, header: Option[Vector[String]], sampled: Vector[Path])

  /** The schema the files' text says. The first file's header names the
    * columns (without a header, its first row says how many there are, named
    * `_c0`, `_c1`, ...), and each column takes the narrowest type every field
    * of the sample reads as ([[CsvSchemaInferer]]). The sample is the first
    * `inferenceSampleRows` rows of the files in glob order, each later file's
    * fields taken by its header's names: a file with no rows, such as the
    * header alone that a writer leaves for an output partition holding none,
    * says nothing of the types, so the sample reads on into the files after
    * it. With no row in any file every column is a STRING. It read the first
    * file alone, so a glob whose first part file was empty typed every column
    * STRING, and a two-row first file decided every later file's types. */
  private[csv] def infer(files: IndexedSeq[Path], options: CsvOptions): Inferred = {
    val limit = options.inferenceSampleRows
    val sample = scala.collection.mutable.ArrayBuffer.empty[Array[String]]
    val sampled = Vector.newBuilder[Path]
    var header: Option[Vector[String]] = None
    var width = 0
    var i = 0
    while (i < files.length && (i == 0 || sample.length < limit)) {
      val file = files(i)
      val before = sample.length
      naming(file, options) {
        val parser = new CsvRowParser(open(file, options), options, file.toString)
        try {
          var order: Array[Int] = null
          if (options.header) {
            val names = nextNonBlank(parser).map(headerNames(_, options))
            if (i == 0) {
              names.foreach(checkHeader(_, file))
              header = names.map(_.toVector)
              width = names.map(_.length).getOrElse(0)
            } else order = columnOrder(names, header.get, file, files.head)
          }
          val against =
            if (i > 0) s"'${files.head}'" else if (header.isDefined) "its header" else "its first row"
          // Without a header, the first row that is not a blank line says how
          // many columns the file has; blank lines before it are rows if that
          // is one.
          val leadingBlanks = scala.collection.mutable.ArrayBuffer.empty[Array[String]]
          var more = true
          while (more && sample.length < limit) {
            parser.readRow() match {
              case Some(r) =>
                if (width == 0) {
                  if (parser.blank) leadingBlanks += r
                  else {
                    width = r.length
                    if (width == 1) sample ++= leadingBlanks.take(limit)
                    if (sample.length < limit) sample += r
                  }
                } else if (!(parser.blank && width > 1)) {
                  checkWidth(r, width, file, parser.line, against)
                  sample += (if (order == null) r else order.map(r(_)))
                }
              case None => more = false
            }
          }
        } finally parser.close()
      }
      if (i == 0 && width == 0) throw new IllegalArgumentException(s"CSV file '${files.head}' is empty")
      if (i == 0 || sample.length > before) sampled += file
      i += 1
    }
    val names = header.getOrElse((0 until width).map(i => s"_c$i").toVector)
    Inferred(CsvSchemaInferer.infer(names, sample), header, sampled.result())
  }

  /** Where each of the first file's columns, `expected`, is in a later file
    * of the glob whose header is `found`, matched by name regardless of case
    * as a query reads names: null when the file names them in the same order,
    * and for a file with no header at all, which has no rows either. A header
    * naming other columns fails, naming both files' headers. */
  private[csv] def columnOrder(found: Option[Array[String]], expected: Vector[String], file: Path,
      first: Path): Array[Int] = found match {
    case None => null
    case Some(names) =>
      def key(n: String) = n.toLowerCase(java.util.Locale.ROOT)
      if (names.length == expected.length && names.indices.forall(k => key(names(k)) == key(expected(k)))) null
      else {
        val at = names.indices.groupBy(k => key(names(k)))
        val order = expected.map(n => at.get(key(n)) match {
          case Some(Seq(k)) => k
          case _ => -1
        })
        if (names.length != expected.length || order.contains(-1))
          throw new IllegalArgumentException(
            s"CSV file '$file' names the columns ${names.mkString("[", ", ", "]")} in its header, where the " +
              s"glob's first file '$first' names ${expected.mkString("[", ", ", "]")}; each file of a glob must " +
              "name the same columns, in any order")
        order.toArray
      }
  }

  /** Files for a message: `'a.csv'`, `'a.csv' and 'b.csv'`, or the first and
    * how many more. */
  private[csv] def describe(files: Seq[Path]): String = files match {
    case Seq(one) => s"'$one'"
    case Seq(a, b) => s"'$a' and '$b'"
    case many => s"'${many.head}' and ${many.length - 1} files after it"
  }

  /** Build a reader from a path/glob string and an option bag. */
  def fromPath(pathOrGlob: String, options: CsvOptions): CsvReader = {
    val files = PathGlob.expand(pathOrGlob)
    if (files.isEmpty)
      throw new IllegalArgumentException(s"No CSV files matched '$pathOrGlob'")
    new CsvReader(files.toIndexedSeq, options)
  }

  /** `file` as text in the options' charset, failing on bytes that are no
    * text in it rather than reading them as U+FFFD. A file that starts with
    * gzip's magic bytes is refused as compressed, whatever the charset: one
    * that decodes every byte would read it as text, and UTF-8 said only that
    * its first line was no text. */
  private[csv] def open(file: Path, options: CsvOptions): Reader = {
    val in = new BufferedInputStream(Files.newInputStream(file))
    try {
      in.mark(2)
      val magic = (in.read(), in.read())
      in.reset()
      if (magic == ((0x1f, 0x8b))) throw new IllegalArgumentException(
        s"CSV file '$file' is compressed with gzip, and CSV is read as text: decompress it first")
      new BufferedReader(new InputStreamReader(in, options.charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)))
    } catch { case e: Throwable => in.close(); throw e }
  }

  /** Run `body` over `file`, naming the line of any bytes in it that are no
    * text in the charset. */
  private[csv] def naming[A](file: Path, options: CsvOptions)(body: => A): A =
    try body
    catch {
      case _: CsvRowParser.InvalidText => throw new IllegalArgumentException(
        s"CSV file '$file' line ${lineOfInvalidText(file, options.charset)} is not valid ${options.charset.name}")
    }

  /** The line of `file` that its first byte sequence with no text in
    * `charset` is on. Decoded afresh, chunk by chunk, rather than taken from
    * the parser, whose reader decodes ahead of the row it is on. Lines end as
    * the parser ends them: at `\n`, `\r\n` or a lone `\r`. */
  private def lineOfInvalidText(file: Path, charset: java.nio.charset.Charset): Long = {
    val decoder = charset.newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val channel = java.nio.channels.FileChannel.open(file)
    try {
      val bytes = java.nio.ByteBuffer.allocate(1 << 16)
      val chars = java.nio.CharBuffer.allocate(1 << 16)
      var line = 1L
      var afterCr = false
      var eof = false
      while (true) {
        if (!eof) eof = channel.read(bytes) < 0
        bytes.flip()
        val result = decoder.decode(bytes, chars, eof)
        chars.flip()
        while (chars.hasRemaining) {
          val c = chars.get()
          if (c == '\n') { if (!afterCr) line += 1; afterCr = false }
          else if (c == '\r') { line += 1; afterCr = true }
          else afterCr = false
        }
        chars.clear()
        if (result.isError) return line
        bytes.compact()
        if (eof && result.isUnderflow) return line
      }
      line
    } finally channel.close()
  }

  /** The next row that is not a blank line: a header is never blank. */
  private[csv] def nextNonBlank(parser: CsvRowParser): Option[Array[String]] = {
    var r = parser.readRow()
    while (r.isDefined && parser.blank) r = parser.readRow()
    r
  }

  /** A header's names: a name the parser read as NULL is the text it is. */
  private[csv] def headerNames(row: Array[String], options: CsvOptions): Array[String] =
    row.map(n => if (n == null) options.nullValue else n)

  /** A header's names are the columns' names, so each must be one, and its
    * own: a query cannot read a column with no name, nor tell apart two with
    * one name, which it reads regardless of case. Spark and DuckDB rename
    * them; this reader refuses rather than guess (plans/robustness/26). */
  private[csv] def checkHeader(names: Array[String], file: Path): Unit = {
    names.indexWhere(_.isEmpty) match {
      case -1 => ()
      case i => throw new IllegalArgumentException(
        s"CSV file '$file' has a column with no name in its header, column ${i + 1}; name it")
    }
    Schema.repeatedName(names.toSeq).foreach { positions =>
      throw new IllegalArgumentException(
        s"CSV file '$file' names more than one column '${names(positions.head)}' in its header, columns " +
          s"${Schema.describePositions(names.toSeq, positions)}, names being read regardless of case; " +
          "give each its own name")
    }
  }

  private[csv] def checkWidth(row: Array[String], width: Int, file: Path, line: Long, against: String): Unit =
    if (row.length != width) throw new IllegalArgumentException(
      s"CSV file '$file' line $line has ${row.length} field${if (row.length == 1) "" else "s"}, " +
        s"but $against has $width")
}

/** Iterator that streams one CSV file as a sequence of [[ColumnarBatch]]es of size
  * `options.batchSize`. Header row (if any) is consumed up front. `header`,
  * the first file's header names and that file, makes this file's own header
  * say where each column is ([[CsvReader.columnOrder]]); without it the
  * header is skipped and fields are read in the schema's order.
  *
  * The file is open from construction until the last row is read or
  * [[close]] is called, whichever comes first, or the construction fails
  * ([[Closing]]).
  */
final class CsvPartitionIterator(file: Path, schema: Schema, options: CsvOptions, typeOrigin: Option[String] = None,
    header: Option[(Vector[String], Path)] = None)
    extends Iterator[ColumnarBatch] with AutoCloseable {

  private val parser = CsvReader.naming(file, options)(new CsvRowParser(CsvReader.open(file, options), options, file.toString))
  LeakAudit.opened(this, s"the CSV file '$file'")
  private val width = schema.length
  private val against = if (options.inferSchema) { if (options.header) "its header" else "its first row" } else "the schema"
  private var closed: Boolean = false
  /** Where each schema column is in this file's rows; null when in order. */
  private val order: Array[Int] =
    try {
      if (!options.header) null
      else {
        val names = CsvReader.naming(file, options)(CsvReader.nextNonBlank(parser)).map(CsvReader.headerNames(_, options))
        header match {
          case Some((expected, first)) => CsvReader.columnOrder(names, expected, file, first)
          case None => null
        }
      }
    } catch { case e: Throwable => close(); throw e }

  private var peeked: Option[Array[String]] = try nextRow() catch { case e: Throwable => close(); throw e }
  private var peekedLine: Long = parser.line

  /** The next row: in a file of two or more columns a blank line is no row. */
  private def nextRow(): Option[Array[String]] = CsvReader.naming(file, options) {
    var r = parser.readRow()
    while (r.isDefined && parser.blank && width > 1) r = parser.readRow()
    r.foreach(CsvReader.checkWidth(_, width, file, parser.line, against))
    r
  }

  override def hasNext: Boolean = {
    if (peeked.isDefined) true
    else {
      ensureClosed()
      false
    }
  }

  /** The next batch. A read that fails, on a row or a value the file cannot
    * give, closes the file: nothing reads it after. */
  override def next(): ColumnarBatch = {
    if (!hasNext) throw new NoSuchElementException("CsvPartitionIterator exhausted")
    val batch = new ColumnarBatch(schema, options.batchSize)
    var rowIdx = 0
    try while (rowIdx < options.batchSize && peeked.isDefined) {
      writeRow(batch, rowIdx, peeked.get)
      rowIdx += 1
      peeked = nextRow()
      peekedLine = parser.line
    } catch { case e: Throwable => close(); throw e }
    batch.setNumRows(rowIdx)
    batch
  }

  private def writeRow(batch: ColumnarBatch, row: Int, fields: Array[String]): Unit = {
    var c = 0
    while (c < width) {
      val raw = if (order == null) fields(c) else fields(order(c))
      val dt = schema.fields(c).dataType
      // Only a STRING and a BINARY have an empty value; in any other column
      // an empty field is NULL, quoted or not.
      if (raw == null || (raw.isEmpty && !CsvValues.hasEmptyValue(dt))) batch.column(c).setNull(row)
      else {
        try CsvValues.parseInto(batch.column(c), row, raw, dt)
        catch {
          case e: CsvValues.Unreadable => throw new IllegalArgumentException(
            s"CSV file '$file' line $peekedLine, column '${schema.fields(c).name}': " +
              s"'${CsvPartitionIterator.excerpt(raw)}' cannot be read as ${e.typeName}" +
              typeOrigin.fold("")(o => s" ($o)"))
        }
      }
      c += 1
    }
  }

  private def ensureClosed(): Unit = if (!closed) {
    closed = true
    try parser.close() catch { case _: Throwable => () }
    LeakAudit.closed(this)
  }

  /** Close the file, whether or not every row was read. */
  def close(): Unit = {
    peeked = None
    ensureClosed()
  }
}

object CsvPartitionIterator {
  /** A field's text as an error quotes it: a long one cut short. */
  private def excerpt(s: String): String = if (s.length <= 80) s else s.take(80) + "..."
}
