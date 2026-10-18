package com.transformer.write.csv

import com.transformer.core._
import com.transformer.read.csv.{CsvOptions, CsvValues}

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.{Charset, CharsetEncoder, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.concurrent.Callable

final case class CsvWriteOptions(
    header: Boolean = true,
    delimiter: Char = ',',
    quote: Char = '"',
    escape: Char = '"',
    nullValue: String = "",
    charset: Charset = StandardCharsets.UTF_8,
    lineEnding: String = "\n"
) {
  CsvOptions.checkSeparators(delimiter, quote, escape, nullValue)
  if (!Set("\n", "\r\n", "\r").contains(lineEnding)) throw new IllegalArgumentException(
    "CSV option 'lineEnding' must be a line break: \\n, \\r\\n or \\r")
}

object CsvWriteOptions {
  def fromMap(raw: Map[String, String]): CsvWriteOptions = {
    def bool(key: String, default: Boolean): Boolean =
      raw.get(key).map(_.trim.toLowerCase) match {
        case None => default
        case Some("true") | Some("1") | Some("yes") => true
        case Some("false") | Some("0") | Some("no") => false
        case Some(other) =>
          throw new IllegalArgumentException(s"Invalid boolean for option '$key': '$other'")
      }
    def chr(key: String, default: Char): Char = raw.get(key) match {
      case None => default
      case Some(s) if s.length == 1 => s.charAt(0)
      case Some("\\t") => '\t'
      case Some(other) =>
        throw new IllegalArgumentException(s"Option '$key' must be a single character, got: '$other'")
    }
    CsvWriteOptions(
      header = bool("header", default = true),
      delimiter = chr("delimiter", default = ','),
      quote = chr("quote", default = '"'),
      escape = chr("escape", default = '"'),
      nullValue = raw.getOrElse("nullValue", ""),
      charset = raw.get("charset").map(Charset.forName).getOrElse(StandardCharsets.UTF_8),
      lineEnding = raw.getOrElse("lineEnding", "\n")
    )
  }
}

/** Writes a stream of [[ColumnarBatch]]es to a single CSV file. Writes go to a temp
  * file in the same directory and are atomically renamed at close, so partial output
  * never appears at the target path on failure.
  *
  * A NULL is the null string, unquoted. A value is its text ([[CsvValues.format]],
  * which the reader parses back), quoted when it holds the delimiter, the quote or
  * a line break, when it is the null string itself (the empty string, by default),
  * or when it starts with a byte-order mark, which the reader would otherwise take
  * for one; and an empty column name is quoted, so a header is never blank. Inside
  * quotes the quote is doubled, or escaped with `escape`, which is then escaped
  * too. A value holding a character the charset cannot encode, or half of a
  * surrogate pair, fails the write naming its row and column.
  */
final class CsvWriter(target: Path, schema: Schema, options: CsvWriteOptions = CsvWriteOptions()) {

  private val tmp: Path = {
    val parent = target.getParent
    if (parent != null) Files.createDirectories(parent)
    val name = s".${target.getFileName.toString}.${System.nanoTime()}.tmp"
    if (parent != null) parent.resolve(name) else Paths.get(name)
  }

  private def reportingEncoder(): CharsetEncoder = options.charset.newEncoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)

  /** Asks whether a value encodes, for a charset that does not encode every
    * character; the stream keeps an encoder of its own, whose state a check
    * must not touch. None for UTF-8, UTF-16, UTF-32 and GB18030, where only
    * half of a surrogate pair fails, which [[formatField]] looks for itself. */
  private val checker: Option[CharsetEncoder] = {
    val name = options.charset.name.toUpperCase
    if (name.startsWith("UTF-") || name == "GB18030") None else Some(reportingEncoder())
  }

  // A reporting encoder, so a value that slipped past the checks fails the
  // write rather than reaching the file as `?`.
  private val out = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(tmp), reportingEncoder()))

  private val rowsWritten: Array[Long] = Array(0L)

  if (options.header) {
    var i = 0
    val n = schema.length
    while (i < n) {
      if (i > 0) out.write(options.delimiter.toInt)
      out.write(formatField(schema.fields(i).name, row = 0L, i))
      i += 1
    }
    out.write(options.lineEnding)
  }

  def write(batch: ColumnarBatch): Unit = {
    if (batch.schema != schema && batch.schema.fieldNames != schema.fieldNames) {
      throw new IllegalArgumentException(
        s"Batch schema ${batch.schema} does not match writer schema $schema"
      )
    }
    val ncols = schema.length
    var row = 0
    val nrows = batch.numRows
    while (row < nrows) {
      var c = 0
      while (c < ncols) {
        if (c > 0) out.write(options.delimiter.toInt)
        val v = batch.column(c)
        if (v.isNull(row)) out.write(options.nullValue)
        else out.write(formatField(CsvValues.format(v, row), rowsWritten(0) + row + 1, c))
        c += 1
      }
      out.write(options.lineEnding)
      row += 1
    }
    rowsWritten(0) += nrows
  }

  /** Flush, close, and atomically rename to the target path. */
  def close(): Long = {
    out.flush()
    out.close()
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    rowsWritten(0)
  }

  /** Discard any buffered output and remove the temp file. */
  def abort(): Unit = {
    try out.close() catch { case _: Throwable => () }
    try Files.deleteIfExists(tmp) catch { case _: Throwable => () }
    ()
  }

  /** `s` as the file holds it: as is, or quoted with its quotes (and
    * escapes) escaped. `row` (1 for the first data row, 0 for the header) and
    * `column` place it in an error. */
  private def formatField(s: String, row: Long, column: Int): String = {
    // An empty name is quoted too: a one-column header of an empty name would
    // be a blank line, which a reader skips before a header.
    var quoted = s == options.nullValue || (s.nonEmpty && s.charAt(0) == CsvWriter.ByteOrderMark) ||
      (row == 0L && s.isEmpty)
    var i = 0
    val n = s.length
    while (i < n) {
      val c = s.charAt(i)
      if (c == options.delimiter || c == options.quote || c == '\n' || c == '\r') quoted = true
      else if (Character.isSurrogate(c)) {
        if (Character.isHighSurrogate(c) && i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) i += 1
        else unwritable(row, column, f"half of a surrogate pair (U+${c.toInt}%04X), which is no character")
      }
      i += 1
    }
    if (checker.isDefined && !checker.get.canEncode(s)) {
      val enc = checker.get
      val bad = s.codePoints.toArray.find(cp => !enc.canEncode(new String(Character.toChars(cp)))).getOrElse(0)
      unwritable(row, column,
        f"'${new String(Character.toChars(bad))}' (U+$bad%04X), which ${options.charset.name} cannot encode")
    }
    if (!quoted) return s
    val sb = new java.lang.StringBuilder(s.length + 2)
    sb.append(options.quote)
    i = 0
    while (i < n) {
      val c = s.charAt(i)
      if (c == options.quote) {
        if (options.escape == options.quote) sb.append(options.quote)
        else sb.append(options.escape)
        sb.append(c)
      } else if (c == options.escape) sb.append(options.escape).append(c)
      else sb.append(c)
      i += 1
    }
    sb.append(options.quote)
    sb.toString
  }

  private def unwritable(row: Long, column: Int, what: String): Nothing = {
    val where = if (row == 0L) "the header" else s"row $row"
    throw new IllegalArgumentException(
      s"Cannot write CSV file '$target': $where, column '${schema.fields(column).name}' holds $what")
  }
}

object CsvWriter {
  private val ByteOrderMark: Char = 0xfeff.toChar

  /** Drain an iterator of batches into a single CSV file. Returns rows written. */
  def writeAll(target: Path, schema: Schema, batches: Iterator[ColumnarBatch],
               options: CsvWriteOptions = CsvWriteOptions()): Long = {
    val w = new CsvWriter(target, schema, options)
    try {
      while (batches.hasNext) w.write(batches.next())
      w.close()
    } catch {
      case e: Throwable =>
        w.abort()
        throw e
    }
  }

  /** Write a sequence of partition iterators into `targetDir` as
    * `part-00000.csv`, `part-00001.csv`, ... — one file per input partition,
    * each written in parallel via its own [[CsvWriter]] (atomic temp + rename).
    * With no partitions at all (a query over a parquet file with no row
    * groups has none), one empty part file is written, so the directory
    * reads back as the empty table rather than as no file.
    *
    * If any partition fails, every in-flight part writer is aborted and the
    * thrown exception is rethrown.
    *
    * Returns the total number of rows written across all parts.
    */
  def writePartitioned(
      targetDir: Path,
      schema: Schema,
      partitions: IndexedSeq[Iterator[ColumnarBatch]],
      options: CsvWriteOptions = CsvWriteOptions()
  ): Long = {
    Files.createDirectories(targetDir)
    val n = partitions.length
    if (n == 0) return writeAll(targetDir.resolve("part-00000.csv"), schema, Iterator.empty, options)
    val writers = new Array[CsvWriter](n)
    val tasks: Seq[Callable[Long]] = (0 until n).map { i =>
      new Callable[Long] {
        def call(): Long = {
          val target = targetDir.resolve(f"part-$i%05d.csv")
          val w = new CsvWriter(target, schema, options)
          writers(i) = w
          val it = partitions(i)
          while (it.hasNext) w.write(it.next())
          w.close()
        }
      }
    }
    val futures = tasks.map(Scheduler.submit(_))
    var total = 0L
    var firstError: Throwable = null
    futures.foreach { f =>
      try total += f.await()
      catch { case t: Throwable => if (firstError == null) firstError = t }
    }
    if (firstError != null) {
      abortAll(writers)
      throw firstError
    }
    total
  }

  private def abortAll(writers: Array[CsvWriter]): Unit = {
    var i = 0
    while (i < writers.length) {
      val w = writers(i)
      if (w != null) try w.abort() catch { case _: Throwable => () }
      i += 1
    }
  }
}
