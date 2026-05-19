package com.transformer.write.csv

import com.transformer.core._

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.{Charset, StandardCharsets}
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
)

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
  */
final class CsvWriter(target: Path, schema: Schema, options: CsvWriteOptions = CsvWriteOptions()) {

  private val tmp: Path = {
    val parent = target.getParent
    if (parent != null) Files.createDirectories(parent)
    val name = s".${target.getFileName.toString}.${System.nanoTime()}.tmp"
    if (parent != null) parent.resolve(name) else Paths.get(name)
  }

  private val out = new BufferedWriter(
    new OutputStreamWriter(Files.newOutputStream(tmp), options.charset)
  )

  private val needsQuotingChars: Array[Boolean] = {
    val arr = new Array[Boolean](128)
    arr(options.delimiter.toInt) = true
    arr(options.quote.toInt) = true
    arr('\n'.toInt) = true
    arr('\r'.toInt) = true
    arr
  }

  if (options.header) {
    var i = 0
    val n = schema.length
    while (i < n) {
      if (i > 0) out.write(options.delimiter.toInt)
      out.write(formatField(schema.fields(i).name))
      i += 1
    }
    out.write(options.lineEnding)
  }

  private val rowsWritten: Array[Long] = Array(0L)

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
        else out.write(formatField(cellToString(v, row)))
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

  private def cellToString(v: ColumnVector, row: Int): String = v match {
    case s: StringVector => s.get(row)
    case i: IntVector => i.get(row).toString
    case l: LongVector => l.get(row).toString
    case f: FloatVector => f.get(row).toString
    case d: DoubleVector => d.get(row).toString
    case b: BooleanVector => b.get(row).toString
    case d: DateVector => java.time.LocalDate.ofEpochDay(d.get(row).toLong).toString
    case t: TimestampVector =>
      val micros = t.get(row)
      val instant = java.time.Instant.ofEpochSecond(micros / 1000000L, (micros % 1000000L) * 1000L)
      instant.toString
    case dec: DecimalVector => Option(dec.get(row)).map(_.toPlainString).getOrElse("")
    case bin: BinaryVector =>
      val bs = bin.get(row)
      if (bs == null) "" else java.util.Base64.getEncoder.encodeToString(bs)
    case _: NullVectorImpl => ""
  }

  private def needsQuoting(s: String): Boolean = {
    var i = 0
    val n = s.length
    while (i < n) {
      val c = s.charAt(i)
      if (c < 128 && needsQuotingChars(c.toInt)) return true
      i += 1
    }
    false
  }

  private def formatField(s: String): String = {
    if (s == null) return options.nullValue
    if (!needsQuoting(s)) return s
    val sb = new java.lang.StringBuilder(s.length + 2)
    sb.append(options.quote)
    var i = 0
    val n = s.length
    while (i < n) {
      val c = s.charAt(i)
      if (c == options.quote) {
        if (options.escape == options.quote) sb.append(options.quote)
        else sb.append(options.escape)
        sb.append(c)
      } else sb.append(c)
      i += 1
    }
    sb.append(options.quote)
    sb.toString
  }
}

object CsvWriter {
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
    if (n == 0) return 0L
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
      try total += f.get()
      catch {
        case e: java.util.concurrent.ExecutionException =>
          if (firstError == null) firstError = e.getCause
        case t: Throwable =>
          if (firstError == null) firstError = t
      }
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
