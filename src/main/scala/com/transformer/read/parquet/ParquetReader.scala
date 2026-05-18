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

/** Reads a folder/glob of Parquet files as a single named view.
  *
  * Partition unit = one file. We used to split per row group, but `HParquetReader`
  * has no public skip-by-row-group API — seeking to partition `i` meant reading
  * and discarding every row before it, which is O(rows²) over a file and pegs the
  * GC on wide schemas (each discarded record is a heap-heavy `SimpleGroup`).
  * One partition per file keeps reads strictly sequential. For a glob, file count
  * is the parallelism unit.
  */
final class ParquetReader private (
    files: IndexedSeq[Path],
    batchSize: Int,
    projectedNames: Option[Set[String]]
) extends CatalogView {
  require(files.nonEmpty, "ParquetReader requires at least one file")

  private val hadoopConf: Configuration = ParquetReader.defaultConf

  /** Per-file descriptor: total row count from the footer's row-group sum, and
    * the open-input-file handle so we don't re-resolve on partition reads.
    */
  private[parquet] case class PartitionMeta(hif: HadoopInputFile, rowCount: Long)

  /** Materialize the partition layout + schema in one pass. Footer reads across
    * files run on a shared short-lived pool so a many-file glob doesn't pay the
    * per-file open cost serially.
    */
  private val (parquetSchema_, partitions): (Schema, IndexedSeq[PartitionMeta]) = {
    val nThreads = math.max(1, math.min(files.length, Runtime.getRuntime.availableProcessors))
    val pool = Executors.newFixedThreadPool(nThreads)
    try {
      val futures = files.map { f =>
        pool.submit(new Callable[(Schema, PartitionMeta)] {
          def call(): (Schema, PartitionMeta) = {
            val hif = HadoopInputFile.fromPath(new HPath(f.toUri), hadoopConf)
            val reader = ParquetFileReader.open(hif)
            try {
              val schema = ParquetSchema.toSchema(reader.getFooter.getFileMetaData.getSchema)
              val blocks = reader.getRowGroups
              var rc = 0L
              var i = 0
              while (i < blocks.size) { rc += blocks.get(i).getRowCount; i += 1 }
              (schema, PartitionMeta(hif, rc))
            } finally reader.close()
          }
        })
      }
      val results = futures.map(_.get())
      val schema = results.head._1
      (schema, results.map(_._2).toIndexedSeq)
    } finally {
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }
  }

  /** Effective output schema. With projection, only the kept fields, in the
    * order they appear in the underlying file's schema (we never reorder —
    * callers' ColRefExpr indices only need a name → new-position lookup).
    */
  val schema: Schema = projectedNames match {
    case Some(keep) => Schema(parquetSchema_.fields.filter(f => keep.contains(f.name)))
    case None => parquetSchema_
  }

  def numPartitions: Int = partitions.length

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    val m = partitions(p)
    new ParquetPartitionIterator(m.hif, schema, batchSize, projectedNames.isDefined)
  }

  /** Sum the per-file counts already collected from each footer. Used by the SQL
    * planner to short-circuit `SELECT COUNT(*) FROM <parquet>` — we already paid
    * for the footer reads at construction time. Unaffected by projection.
    */
  override val exactRowCount: Option[Long] = {
    var sum = 0L
    var i = 0
    while (i < partitions.length) { sum += partitions(i).rowCount; i += 1 }
    Some(sum)
  }

  /** Return a reader that decodes only the named columns. Names not present in
    * the underlying schema are silently dropped (matches `MessageType.intersection`
    * semantics on the parquet side). Returns None only if no requested name
    * matches anything — in that case the caller stays on the full scan.
    */
  override def withProjectedColumns(names: Seq[String]): Option[CatalogView] = {
    val available = parquetSchema_.fieldNames.toSet
    val keep = names.iterator.filter(available.contains).toSet
    if (keep.isEmpty || keep.size == parquetSchema_.length) None
    else Some(new ParquetReader(files, batchSize, Some(keep)))
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
    new ParquetReader(files.toIndexedSeq, batchSize, projectedNames = None)
  }
}

/** Iterator that reads one Parquet file end-to-end as batches of `batchSize`.
  *
  * Uses [[HParquetReader]] with [[GroupReadSupport]]; reads are strictly
  * sequential so there is no skip-by-row-group cost. The reader is closed when
  * the iterator is drained.
  *
  * If `projected = true`, `schema` is a subset of the file's column set and we
  * push it down via `parquet.read.schema` so parquet-mr skips the unselected
  * column chunks entirely. parquet-mr intersects the requested schema against
  * the file schema by field name; types come from the file.
  */
final class ParquetPartitionIterator(
    hif: org.apache.parquet.hadoop.util.HadoopInputFile,
    schema: Schema,
    batchSize: Int,
    projected: Boolean = false
) extends Iterator[ColumnarBatch] {

  private val reader: HParquetReader[Group] = {
    val conf = ParquetReader.defaultConf
    if (projected) {
      // parquet-mr reads `parquet.read.schema` from the conf and intersects with
      // the file schema. Going Schema → MessageType discards any per-column
      // logical-type annotations that won't match the file 1:1, but intersection
      // resolves by name so this is safe.
      val mt = ParquetSchema.toMessageType(schema)
      conf.set(org.apache.parquet.hadoop.api.ReadSupport.PARQUET_READ_SCHEMA, mt.toString)
    }
    HParquetReader.builder(new GroupReadSupport, new HPath(hif.getPath.toUri))
      .withConf(conf)
      .build()
  }

  private var peeked: Group = _

  private def advance(): Boolean = {
    peeked = reader.read()
    peeked != null
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
