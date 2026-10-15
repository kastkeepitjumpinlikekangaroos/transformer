package com.transformer.read.csv

import com.transformer.core._

import java.io.{BufferedReader, InputStreamReader, Reader}
import java.nio.charset.CodingErrorAction
import java.nio.file.{Files, Path}

/** Reads a folder/glob of CSV files as a single named view. One file is one partition
  * (the unit of parallelism for the executor).
  *
  * Schema is determined once at construction time. If `options.inferSchema` is true,
  * the first file's first `inferenceSampleRows` rows are sampled. Otherwise
  * `options.columns` must be set.
  *
  * Every row must have as many fields as the file has columns (its header, or
  * the schema, or its first row): a short or long row fails the read, naming
  * the file and line. A blank line is a row only in a one-column file, where it
  * is the row whose one field is empty, which is how the writer writes a NULL
  * there; in a wider file it is skipped. Each field is parsed strictly as its
  * column's type ([[CsvValues]]), and one that does not read as it fails the
  * read, naming the file, line, column and text. A field is NULL when it is the
  * null string unquoted, and, in a column of any type but STRING and BINARY
  * (the two with an empty value), when it is empty, quoted or not.
  */
final class CsvReader(val files: IndexedSeq[Path], val options: CsvOptions) extends CatalogView {

  require(files.nonEmpty, "CsvReader requires at least one file")

  val schema: Schema = {
    if (options.inferSchema) inferSchemaFromFirstFile()
    else {
      options.columns match {
        case Some(cols) => Schema(cols.toVector)
        case None => throw new IllegalArgumentException(
          "CsvOptions.columns must be set when inferSchema = false"
        )
      }
    }
  }

  /** Where the column types came from, for an error about a value that does
    * not read as one. */
  private val typeOrigin: Option[String] =
    if (options.inferSchema)
      Some(s"its type was inferred from the first ${options.inferenceSampleRows} rows of '${files.head}'")
    else None

  def numPartitions: Int = files.length

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    require(p >= 0 && p < files.length, s"partition $p out of range [0,${files.length})")
    new CsvPartitionIterator(files(p), schema, options, typeOrigin)
  }

  private def inferSchemaFromFirstFile(): Schema = CsvReader.naming(files.head, options) {
    val parser = new CsvRowParser(CsvReader.open(files.head, options), options, files.head.toString)
    try {
      val headerRow: Option[Array[String]] =
        if (options.header) CsvReader.nextNonBlank(parser).map(CsvReader.headerNames(_, options)) else None
      val sample = scala.collection.mutable.ArrayBuffer.empty[Array[String]]
      var width = headerRow.map(_.length).getOrElse(0)
      // Without a header, the first row that is not a blank line says how many
      // columns the file has; blank lines before it are rows if that is one.
      val leadingBlanks = scala.collection.mutable.ArrayBuffer.empty[Array[String]]
      var more = true
      while (more && sample.length < options.inferenceSampleRows) {
        parser.readRow() match {
          case Some(r) =>
            if (width == 0) {
              if (parser.blank) leadingBlanks += r
              else {
                width = r.length
                if (width == 1) sample ++= leadingBlanks.take(options.inferenceSampleRows)
                if (sample.length < options.inferenceSampleRows) sample += r
              }
            } else if (!(parser.blank && width > 1)) {
              CsvReader.checkWidth(r, width, files.head, parser.line, if (headerRow.isDefined) "its header" else "its first row")
              sample += r
            }
          case None => more = false
        }
      }
      if (width == 0) throw new IllegalArgumentException(s"CSV file ${files.head} is empty")
      val names = headerRow.map(_.toVector).getOrElse((0 until width).map(i => s"_c$i").toVector)
      CsvSchemaInferer.infer(names, sample)
    } finally parser.close()
  }
}

object CsvReader {
  /** Build a reader from a path/glob string and an option bag. */
  def fromPath(pathOrGlob: String, options: CsvOptions): CsvReader = {
    val files = PathGlob.expand(pathOrGlob)
    if (files.isEmpty)
      throw new IllegalArgumentException(s"No CSV files matched '$pathOrGlob'")
    new CsvReader(files.toIndexedSeq, options)
  }

  /** `file` as text in the options' charset, failing on bytes that are no
    * text in it rather than reading them as U+FFFD. */
  private[csv] def open(file: Path, options: CsvOptions): Reader =
    new BufferedReader(new InputStreamReader(Files.newInputStream(file),
      options.charset.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)))

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

  private[csv] def checkWidth(row: Array[String], width: Int, file: Path, line: Long, against: String): Unit =
    if (row.length != width) throw new IllegalArgumentException(
      s"CSV file '$file' line $line has ${row.length} field${if (row.length == 1) "" else "s"}, " +
        s"but $against has $width")
}

/** Iterator that streams one CSV file as a sequence of [[ColumnarBatch]]es of size
  * `options.batchSize`. Header row (if any) is consumed up front.
  */
final class CsvPartitionIterator(file: Path, schema: Schema, options: CsvOptions, typeOrigin: Option[String] = None)
    extends Iterator[ColumnarBatch] {

  private val parser = CsvReader.naming(file, options)(new CsvRowParser(CsvReader.open(file, options), options, file.toString))
  private val width = schema.length
  private val against = if (options.inferSchema) { if (options.header) "its header" else "its first row" } else "the schema"
  // Drop header if present.
  if (options.header) CsvReader.naming(file, options)(CsvReader.nextNonBlank(parser))

  private var peeked: Option[Array[String]] = nextRow()
  private var peekedLine: Long = parser.line
  private var closed: Boolean = false

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

  override def next(): ColumnarBatch = {
    if (!hasNext) throw new NoSuchElementException("CsvPartitionIterator exhausted")
    val batch = new ColumnarBatch(schema, options.batchSize)
    var rowIdx = 0
    while (rowIdx < options.batchSize && peeked.isDefined) {
      writeRow(batch, rowIdx, peeked.get)
      rowIdx += 1
      peeked = nextRow()
      peekedLine = parser.line
    }
    batch.setNumRows(rowIdx)
    batch
  }

  private def writeRow(batch: ColumnarBatch, row: Int, fields: Array[String]): Unit = {
    var c = 0
    while (c < width) {
      val raw = fields(c)
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
  }
}

object CsvPartitionIterator {
  /** A field's text as an error quotes it: a long one cut short. */
  private def excerpt(s: String): String = if (s.length <= 80) s else s.take(80) + "..."
}
