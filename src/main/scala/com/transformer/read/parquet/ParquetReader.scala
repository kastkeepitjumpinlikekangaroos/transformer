package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.read.csv.PathGlob
import com.transformer.write.parquet.ParquetSchema

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.example.data.Group
import org.apache.parquet.hadoop.{ParquetFileReader, ParquetReader => HParquetReader}
import org.apache.parquet.hadoop.example.GroupReadSupport
import org.apache.parquet.hadoop.util.HadoopInputFile

import java.nio.file.Path
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.collection.mutable

/** Reads a folder/glob of Parquet files as a single named view.
  *
  * Partition unit = one row group. Larger files end up with multiple partitions,
  * each independently parallelizable.
  */
final class ParquetReader(files: IndexedSeq[Path], batchSize: Int) extends CatalogView {
  require(files.nonEmpty, "ParquetReader requires at least one file")

  private val hadoopConf: Configuration = ParquetReader.defaultConf

  /** Per row-group partition descriptor. `rowsBefore` is the cumulative row
    * count from all earlier row groups in the same file, so the partition
    * iterator can skip into its slice without re-reading the footer.
    */
  private[parquet] case class PartitionMeta(
      hif: HadoopInputFile,
      rowGroupIdx: Int,
      rowCount: Long,
      rowsBefore: Long
  )

  /** Materialize the partition layout + schema in one pass. Footer reads across
    * files run on a shared short-lived pool so a many-file glob doesn't pay the
    * per-file open cost serially.
    */
  private val (parquetSchema_, partitions): (Schema, IndexedSeq[PartitionMeta]) = {
    val nThreads = math.max(1, math.min(files.length, Runtime.getRuntime.availableProcessors))
    val pool = Executors.newFixedThreadPool(nThreads)
    try {
      val futures = files.map { f =>
        pool.submit(new Callable[(Schema, IndexedSeq[PartitionMeta])] {
          def call(): (Schema, IndexedSeq[PartitionMeta]) = {
            val hif = HadoopInputFile.fromPath(new HPath(f.toUri), hadoopConf)
            val reader = ParquetFileReader.open(hif)
            try {
              val schema = ParquetSchema.toSchema(reader.getFooter.getFileMetaData.getSchema)
              val blocks = reader.getRowGroups
              val meta = mutable.ArrayBuffer.empty[PartitionMeta]
              var i = 0
              var cum = 0L
              while (i < blocks.size) {
                val rc = blocks.get(i).getRowCount
                meta += PartitionMeta(hif, i, rc, cum)
                cum += rc
                i += 1
              }
              (schema, meta.toIndexedSeq)
            } finally reader.close()
          }
        })
      }
      val results = futures.map(_.get())
      val schema = results.head._1
      val flat = results.flatMap(_._2).toIndexedSeq
      (schema, flat)
    } finally {
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }
  }

  val schema: Schema = parquetSchema_

  def numPartitions: Int = partitions.length

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    val m = partitions(p)
    new ParquetPartitionIterator(m.hif, m.rowCount, m.rowsBefore, schema, batchSize)
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
  * Uses [[HParquetReader]] with [[GroupReadSupport]]. The reader is opened on
  * the whole file then advanced past `rowsBefore` rows so this partition only
  * sees its slice. Row counts come from the parent reader's footer pass — we
  * never re-open the file just to count rows.
  */
final class ParquetPartitionIterator(
    hif: org.apache.parquet.hadoop.util.HadoopInputFile,
    rowCount: Long,
    rowsBefore: Long,
    schema: Schema,
    batchSize: Int
) extends Iterator[ColumnarBatch] {

  private val reader: HParquetReader[Group] = {
    val conf = ParquetReader.defaultConf
    HParquetReader.builder(new GroupReadSupport, new HPath(hif.getPath.toUri))
      .withConf(conf)
      .build()
  }

  private var rowsRemaining: Long = rowCount
  private var skippedHead: Boolean = false
  private var peeked: Group = _

  private def advance(): Boolean = {
    if (!skippedHead) {
      var i: Long = 0
      while (i < rowsBefore) {
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
