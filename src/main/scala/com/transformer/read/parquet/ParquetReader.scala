package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.read.csv.PathGlob
import com.transformer.write.parquet.ParquetSchema

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.example.data.Group
import org.apache.parquet.example.data.simple.SimpleGroup
import org.apache.parquet.hadoop.{ParquetFileReader, ParquetReader => HParquetReader}
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.PrimitiveType.PrimitiveTypeName

import java.nio.file.Path
import scala.collection.mutable

/** Reads a folder/glob of Parquet files as a single named view.
  *
  * Partition unit = one row group. Larger files end up with multiple partitions,
  * each independently parallelizable.
  */
final class ParquetReader(files: IndexedSeq[Path], batchSize: Int) extends CatalogView {
  require(files.nonEmpty, "ParquetReader requires at least one file")

  private val hadoopConf: Configuration = ParquetReader.defaultConf

  /** Materialize the partition layout up front: (file, rowGroupIndex). */
  private val partitions: IndexedSeq[(HadoopInputFile, Int, Long)] = {
    val buf = mutable.ArrayBuffer.empty[(HadoopInputFile, Int, Long)]
    files.foreach { f =>
      val hif = HadoopInputFile.fromPath(new HPath(f.toUri), hadoopConf)
      val reader = ParquetFileReader.open(hif)
      try {
        val blocks = reader.getRowGroups
        var i = 0
        while (i < blocks.size) {
          buf += ((hif, i, blocks.get(i).getRowCount))
          i += 1
        }
      } finally reader.close()
    }
    buf.toIndexedSeq
  }

  val schema: Schema = {
    val firstReader = ParquetFileReader.open(partitions.head._1)
    try {
      val mt = firstReader.getFooter.getFileMetaData.getSchema
      ParquetSchema.toSchema(mt)
    } finally firstReader.close()
  }

  def numPartitions: Int = partitions.length

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    val (hif, rowGroupIdx, _) = partitions(p)
    new ParquetPartitionIterator(hif, rowGroupIdx, schema, batchSize)
  }
}

object ParquetReader {
  private[parquet] def defaultConf: Configuration = {
    val c = new Configuration()
    // Pin local FS — no HDFS, no Hadoop runtime.
    c.set("fs.defaultFS", "file:///")
    c
  }

  def fromPath(pathOrGlob: String, batchSize: Int = ColumnarBatch.DefaultCapacity): ParquetReader = {
    val files = PathGlob.expand(pathOrGlob)
    if (files.isEmpty)
      throw new IllegalArgumentException(s"No Parquet files matched '$pathOrGlob'")
    new ParquetReader(files.toIndexedSeq, batchSize)
  }
}

/** Iterator that reads one Parquet row group as batches of `batchSize`.
  *
  * Uses [[HParquetReader]] with [[GroupReadSupport]]. The `parquet.read.support.class`
  * config + per-file open are heavy, but acceptable: we open one reader per partition
  * (row group) and partitions run in parallel.
  */
final class ParquetPartitionIterator(
    hif: org.apache.parquet.hadoop.util.HadoopInputFile,
    rowGroupIdx: Int,
    schema: Schema,
    batchSize: Int
) extends Iterator[ColumnarBatch] {

  private val reader: HParquetReader[Group] = {
    val conf = ParquetReader.defaultConf
    HParquetReader.builder(new GroupReadSupport, new HPath(hif.getPath.toUri))
      .withConf(conf)
      .build()
  }

  // We approximate per-row-group partitioning by reading the *whole* file's reader,
  // then skipping rows from other groups. Parquet's public API doesn't expose a
  // clean per-row-group iterator on top of HParquetReader; for v1, we keep a single
  // reader per file and let partitions split rows by row-group offsets.
  //
  // To stay correct, we materialize this row group's `endRow` count and stop after.
  private var rowsRemaining: Long = ParquetPartitionIterator.rowsInGroup(hif, rowGroupIdx)
  private var skippedHead: Boolean = false
  private val rowsBeforeGroup: Long = ParquetPartitionIterator.rowsBefore(hif, rowGroupIdx)
  private var peeked: Group = _

  private def advance(): Boolean = {
    if (!skippedHead) {
      // Skip rows from earlier groups so this partition only sees its slice.
      var i: Long = 0
      while (i < rowsBeforeGroup) {
        val g = reader.read()
        if (g == null) { rowsRemaining = 0; skippedHead = true; return false }
        i += 1
      }
      skippedHead = true
    }
    if (rowsRemaining <= 0) { peeked = null; return false }
    peeked = reader.read()
    if (peeked == null) { rowsRemaining = 0; false }
    else { rowsRemaining -= 1; true }
  }

  // Prime first row.
  private var primed: Boolean = advance()

  override def hasNext: Boolean = {
    val has = primed
    if (!has) { try reader.close() catch { case _: Throwable => () } }
    has
  }

  override def next(): ColumnarBatch = {
    val out = new ColumnarBatch(schema, batchSize)
    var r = 0
    while (r < batchSize && primed) {
      writeGroup(out, r, peeked)
      r += 1
      primed = advance()
    }
    out.setNumRows(r)
    out
  }

  private def writeGroup(batch: ColumnarBatch, row: Int, g: Group): Unit = {
    val ncols = schema.length
    var c = 0
    while (c < ncols) {
      val field = schema.fields(c)
      val name = field.name
      val present = g.getFieldRepetitionCount(c) > 0
      if (!present) batch.column(c).setNull(row)
      else {
        field.dataType match {
          case DataType.BooleanType => batch.column(c).asInstanceOf[BooleanVector].set(row, g.getBoolean(c, 0))
          case DataType.IntType => batch.column(c).asInstanceOf[IntVector].set(row, g.getInteger(c, 0))
          case DataType.LongType => batch.column(c).asInstanceOf[LongVector].set(row, g.getLong(c, 0))
          case DataType.FloatType => batch.column(c).asInstanceOf[FloatVector].set(row, g.getFloat(c, 0))
          case DataType.DoubleType => batch.column(c).asInstanceOf[DoubleVector].set(row, g.getDouble(c, 0))
          case DataType.StringType => batch.column(c).asInstanceOf[StringVector].set(row, g.getString(c, 0))
          case DataType.BinaryType => batch.column(c).asInstanceOf[BinaryVector].set(row, g.getBinary(c, 0).getBytes)
          case DataType.DateType => batch.column(c).asInstanceOf[DateVector].set(row, g.getInteger(c, 0))
          case DataType.TimestampType =>
            // INT64 micros since epoch; we round-trip as-is.
            batch.column(c).asInstanceOf[TimestampVector].set(row, g.getLong(c, 0))
          case other => throw new UnsupportedOperationException(s"Reading $other from Parquet not supported")
        }
      }
      c += 1
    }
  }
}

private[parquet] object ParquetPartitionIterator {
  /** Total row count in the given row group of `hif`. */
  def rowsInGroup(hif: org.apache.parquet.hadoop.util.HadoopInputFile, idx: Int): Long = {
    val r = ParquetFileReader.open(hif)
    try r.getRowGroups.get(idx).getRowCount
    finally r.close()
  }

  def rowsBefore(hif: org.apache.parquet.hadoop.util.HadoopInputFile, idx: Int): Long = {
    val r = ParquetFileReader.open(hif)
    try {
      var n = 0L
      var i = 0
      val groups = r.getRowGroups
      while (i < idx) { n += groups.get(i).getRowCount; i += 1 }
      n
    } finally r.close()
  }
}
