package com.transformer.read.csv

import com.transformer.core._

import java.io.{BufferedReader, InputStreamReader}
import java.nio.file.{Files, Path}

/** Reads a folder/glob of CSV files as a single named view. One file is one partition
  * (the unit of parallelism for the executor).
  *
  * Schema is determined once at construction time. If `options.inferSchema` is true,
  * the first file's first `inferenceSampleRows` rows are sampled. Otherwise
  * `options.columns` must be set.
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

  def numPartitions: Int = files.length

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    require(p >= 0 && p < files.length, s"partition $p out of range [0,${files.length})")
    new CsvPartitionIterator(files(p), schema, options)
  }

  private def inferSchemaFromFirstFile(): Schema = {
    val in = new BufferedReader(new InputStreamReader(Files.newInputStream(files.head), options.charset))
    try {
      val parser = new CsvRowParser(in, options)
      val headerRow: Option[Array[String]] =
        if (options.header) parser.readRow() else None
      val sample = scala.collection.mutable.ArrayBuffer.empty[Array[String]]
      var more = true
      while (more && sample.length < options.inferenceSampleRows) {
        parser.readRow() match {
          case Some(r) => sample += r
          case None => more = false
        }
      }
      buildSchema(headerRow, sample)
    } finally in.close()
  }

  private def buildSchema(
      header: Option[Array[String]],
      sample: scala.collection.mutable.ArrayBuffer[Array[String]]
  ): Schema = {
    val widthFromSample = if (sample.nonEmpty) sample.head.length else 0
    val width = header.map(_.length).getOrElse(widthFromSample)
    if (width == 0) throw new IllegalArgumentException(s"CSV file ${files.head} is empty")
    val names = header.map(_.toVector).getOrElse((0 until width).map(i => s"_c$i").toVector)
    CsvSchemaInferer.infer(names, sample, options.nullValue)
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
}

/** Iterator that streams one CSV file as a sequence of [[ColumnarBatch]]es of size
  * `options.batchSize`. Header row (if any) is consumed up front.
  */
final class CsvPartitionIterator(file: Path, schema: Schema, options: CsvOptions)
    extends Iterator[ColumnarBatch] {

  private val in = new BufferedReader(new InputStreamReader(Files.newInputStream(file), options.charset))
  private val parser = new CsvRowParser(in, options)
  // Drop header if present.
  if (options.header) parser.readRow()

  private var peeked: Option[Array[String]] = parser.readRow()
  private var closed: Boolean = false

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
      peeked = parser.readRow()
    }
    batch.setNumRows(rowIdx)
    batch
  }

  private def writeRow(batch: ColumnarBatch, row: Int, fields: Array[String]): Unit = {
    val n = schema.length
    var c = 0
    while (c < n) {
      val raw = if (c < fields.length) fields(c) else null
      val isNull = raw == null || raw == options.nullValue
      if (isNull) batch.column(c).setNull(row)
      else parseAndSet(batch.column(c), row, raw, schema.fields(c).dataType)
      c += 1
    }
  }

  private def parseAndSet(col: ColumnVector, row: Int, s: String, dt: DataType): Unit = dt match {
    case DataType.StringType =>
      col.asInstanceOf[StringVector].set(row, s)
    case DataType.IntType =>
      col.asInstanceOf[IntVector].set(row, s.toInt)
    case DataType.LongType =>
      col.asInstanceOf[LongVector].set(row, s.toLong)
    case DataType.FloatType =>
      col.asInstanceOf[FloatVector].set(row, s.toFloat)
    case DataType.DoubleType =>
      col.asInstanceOf[DoubleVector].set(row, s.toDouble)
    case DataType.BooleanType =>
      col.asInstanceOf[BooleanVector].set(row, java.lang.Boolean.parseBoolean(s))
    case DataType.DateType =>
      col.asInstanceOf[DateVector].set(row, java.time.LocalDate.parse(s).toEpochDay.toInt)
    case DataType.TimestampType =>
      val ts: Long = try {
        val i = java.time.Instant.parse(s)
        i.getEpochSecond * 1000000L + i.getNano / 1000L
      } catch {
        case _: Exception =>
          val ldt = java.time.LocalDateTime.parse(s)
          val i = ldt.toInstant(java.time.ZoneOffset.UTC)
          i.getEpochSecond * 1000000L + i.getNano / 1000L
      }
      col.asInstanceOf[TimestampVector].set(row, ts)
    case d: DataType.DecimalType =>
      col.asInstanceOf[DecimalVector].set(row, new java.math.BigDecimal(s))
    case other =>
      col.setBoxed(row, s)
  }

  private def ensureClosed(): Unit = if (!closed) {
    closed = true
    try parser.close() catch { case _: Throwable => () }
  }
}
