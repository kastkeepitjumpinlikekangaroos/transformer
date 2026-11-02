package com.transformer.write.csv

import com.transformer.core._
import com.transformer.read.csv.{CsvOptions, CsvValues}

import java.io.{BufferedWriter, OutputStreamWriter, Writer}
import java.nio.charset.{Charset, CharsetEncoder, CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.util.Locale
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
  /** The option keys a CSV output's writer reads ([[fromMap]]). */
  val Keys: Seq[String] = Seq("header", "delimiter", "quote", "escape", "nullValue", "charset", "lineEnding")

  /** The options [[Keys]] name in `raw`, each read strictly ([[OptionValues]]);
    * the map carries the task's execution options too, whose keys the
    * caller checks. */
  def fromMap(raw: Map[String, String]): CsvWriteOptions = {
    def bool(key: String, default: Boolean): Boolean = raw.get(key).map(OptionValues.bool(key, _)).getOrElse(default)
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
      charset = raw.get("charset").map(OptionValues.charset("charset", _)).getOrElse(StandardCharsets.UTF_8),
      lineEnding = raw.getOrElse("lineEnding", "\n")
    )
  }
}

/** Writes a stream of [[ColumnarBatch]]es to a single CSV file, as
  * [[CsvRowWriter]] writes rows. The file is staged: written under a hidden
  * temp name in the same directory (`.<name>.<nanos>.tmp`), which [[finish]]
  * closes, and renamed to the target path by [[commit]]. [[close]] does both,
  * for a file written on its own; a partitioned write finishes every part
  * first and commits them together ([[CsvWriter.writePartitioned]]), so no
  * part appears at its target path unless every part was written.
  */
final class CsvWriter(target: Path, schema: Schema, options: CsvWriteOptions = CsvWriteOptions()) {

  private val tmp: Path = {
    val parent = target.getParent
    if (parent != null) Files.createDirectories(parent)
    val name = s".${target.getFileName.toString}.${System.nanoTime()}.tmp"
    if (parent != null) parent.resolve(name) else Paths.get(name)
  }

  // A reporting encoder, so a value that slipped past the checks fails the
  // write rather than reaching the file as `?`.
  private val out = new BufferedWriter(
    new OutputStreamWriter(Files.newOutputStream(tmp), CsvWriter.reportingEncoder(options.charset)))

  private val rows = new CsvRowWriter(out, schema, options, s"CSV file '$target'")

  private var committed = false

  def write(batch: ColumnarBatch): Unit = rows.write(batch)

  /** Flush and close the staged file, still under its temp name. Returns the
    * rows written. */
  def finish(): Long = {
    out.flush()
    out.close()
    rows.rowsWritten
  }

  /** Rename the finished file to the target path, atomically. */
  def commit(): Unit = {
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    committed = true
  }

  /** Finish and commit: a file written on its own. Returns the rows written. */
  def close(): Long = {
    val n = finish()
    commit()
    n
  }

  /** Discard the file: the staged one, or the target once it is committed. */
  def abort(): Unit = {
    try out.close() catch { case _: Throwable => () }
    try Files.deleteIfExists(tmp) catch { case _: Throwable => () }
    if (committed) try Files.deleteIfExists(target) catch { case _: Throwable => () }
    ()
  }
}

/** CSV text on `out`: the header, when `options` has one, then each row
  * written. `destination` names where the text goes in an error (`CSV file
  * 'x.csv'`). A CSV file is written through one ([[CsvWriter]]), and so is a
  * result `transformer query` prints as CSV.
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
final class CsvRowWriter(out: Writer, schema: Schema, options: CsvWriteOptions, destination: String) {

  /** Asks whether a value encodes, for a charset that does not encode every
    * character; the stream keeps an encoder of its own, whose state a check
    * must not touch. None for UTF-8, UTF-16, UTF-32 and GB18030, where only
    * half of a surrogate pair fails, which [[formatField]] looks for itself. */
  private val checker: Option[CharsetEncoder] = {
    val name = options.charset.name.toUpperCase(Locale.ROOT)
    if (name.startsWith("UTF-") || name == "GB18030") None else Some(CsvWriter.reportingEncoder(options.charset))
  }

  private var written: Long = 0L

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

  /** The rows written so far, the header not counted. */
  def rowsWritten: Long = written

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
        else out.write(formatField(CsvValues.format(v, row), written + row + 1, c))
        c += 1
      }
      out.write(options.lineEnding)
      row += 1
    }
    written += nrows
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
        else unwritable(row, column, "half of a surrogate pair (U+%04X), which is no character".formatLocal(Locale.ROOT, c.toInt))
      }
      i += 1
    }
    if (checker.isDefined && !checker.get.canEncode(s)) {
      val enc = checker.get
      val bad = s.codePoints.toArray.find(cp => !enc.canEncode(new String(Character.toChars(cp)))).getOrElse(0)
      unwritable(row, column,
        "'%s' (U+%04X), which %s cannot encode".formatLocal(Locale.ROOT, new String(Character.toChars(bad)), bad, options.charset.name))
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
      s"Cannot write $destination: $where, column '${schema.fields(column).name}' holds $what")
  }
}

object CsvWriter {
  private[csv] val ByteOrderMark: Char = 0xfeff.toChar

  /** An encoder of `charset` that fails on a character it cannot encode,
    * rather than writing `?`. */
  private[csv] def reportingEncoder(charset: Charset): CharsetEncoder = charset.newEncoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)

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
    * each staged in parallel through its own [[CsvWriter]]. With no partitions
    * at all (a query over a parquet file with no row groups has none), one
    * empty part file is written, so the directory reads back as the empty
    * table rather than as no file.
    *
    * Once every partition is written the parts are committed, renamed to
    * their names in partition order. If any partition fails, or a rename
    * does, every part is discarded, staged or committed, and the failure is
    * rethrown: the directory gains no part file.
    *
    * Returns the part files committed and the rows they hold.
    */
  def writePartitioned(
      targetDir: Path,
      schema: Schema,
      partitions: IndexedSeq[Iterator[ColumnarBatch]],
      options: CsvWriteOptions = CsvWriteOptions()
  ): WrittenParts = {
    Files.createDirectories(targetDir)
    val parts = if (partitions.isEmpty) IndexedSeq(Iterator.empty) else partitions
    val n = parts.length
    val names = Vector.tabulate(n)(i => "part-%05d.csv".formatLocal(Locale.ROOT, i))
    val writers = new Array[CsvWriter](n)
    val tasks: Seq[Callable[Long]] = (0 until n).map { i =>
      new Callable[Long] {
        def call(): Long = {
          val w = new CsvWriter(targetDir.resolve(names(i)), schema, options)
          writers(i) = w
          val it = parts(i)
          while (it.hasNext) w.write(it.next())
          w.finish()
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
    if (firstError == null) {
      try writers.foreach(_.commit())
      catch { case t: Throwable => firstError = t }
    }
    if (firstError != null) {
      abortAll(writers)
      throw firstError
    }
    WrittenParts(names, total)
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
