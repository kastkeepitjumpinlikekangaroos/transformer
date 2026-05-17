package com.transformer.write.parquet

import com.transformer.core._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.example.data.simple.SimpleGroup
import org.apache.parquet.hadoop.ParquetWriter.{DEFAULT_BLOCK_SIZE, DEFAULT_PAGE_SIZE}
import org.apache.parquet.hadoop.example.{ExampleParquetWriter, GroupWriteSupport}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.MessageType

import java.nio.file.{Files, Path, StandardCopyOption}

/** Writes a stream of [[ColumnarBatch]]es to a single Parquet file.
  *
  * Snappy compression by default. Atomic temp-file + rename on close.
  */
final class ParquetWriter(target: Path, schema: Schema, options: Map[String, String]) {

  private val tmp: Path = {
    val parent = target.getParent
    if (parent != null) Files.createDirectories(parent)
    val name = s".${target.getFileName.toString}.${System.nanoTime()}.tmp.parquet"
    if (parent != null) parent.resolve(name) else Path.of(name)
  }

  private val messageType: MessageType = ParquetSchema.toMessageType(schema)

  private val conf: Configuration = {
    val c = new Configuration()
    c.set("fs.defaultFS", "file:///")
    GroupWriteSupport.setSchema(messageType, c)
    c
  }

  private val codec: CompressionCodecName = options.get("compression").map(_.toUpperCase) match {
    case Some("UNCOMPRESSED") | Some("NONE") => CompressionCodecName.UNCOMPRESSED
    case Some("SNAPPY") | None => CompressionCodecName.SNAPPY
    case Some("GZIP") => CompressionCodecName.GZIP
    case Some(other) => throw new IllegalArgumentException(s"Unsupported parquet compression: $other")
  }

  private val writer = ExampleParquetWriter.builder(new HPath(tmp.toUri))
    .withConf(conf)
    .withType(messageType)
    .withCompressionCodec(codec)
    .withRowGroupSize(DEFAULT_BLOCK_SIZE.toLong)
    .withPageSize(DEFAULT_PAGE_SIZE)
    .withDictionaryEncoding(true)
    .build()

  private var rowsWritten: Long = 0L

  def write(batch: ColumnarBatch): Unit = {
    val ncols = schema.length
    var r = 0
    while (r < batch.numRows) {
      val g = new SimpleGroup(messageType)
      var c = 0
      while (c < ncols) {
        val v = batch.column(c)
        if (!v.isNull(r)) {
          schema.fields(c).dataType match {
            case DataType.BooleanType => g.add(c, v.asInstanceOf[BooleanVector].get(r))
            case DataType.IntType => g.add(c, v.asInstanceOf[IntVector].get(r))
            case DataType.LongType => g.add(c, v.asInstanceOf[LongVector].get(r))
            case DataType.FloatType => g.add(c, v.asInstanceOf[FloatVector].get(r))
            case DataType.DoubleType => g.add(c, v.asInstanceOf[DoubleVector].get(r))
            case DataType.StringType => g.add(c, Binary.fromString(v.asInstanceOf[StringVector].get(r)))
            case DataType.BinaryType => g.add(c, Binary.fromConstantByteArray(v.asInstanceOf[BinaryVector].get(r)))
            case DataType.DateType => g.add(c, v.asInstanceOf[DateVector].get(r))
            case DataType.TimestampType => g.add(c, v.asInstanceOf[TimestampVector].get(r))
            case other => throw new UnsupportedOperationException(s"Writing $other to Parquet not supported")
          }
        }
        c += 1
      }
      writer.write(g)
      r += 1
    }
    rowsWritten += batch.numRows
  }

  def close(): Long = {
    writer.close()
    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    rowsWritten
  }

  def abort(): Unit = {
    try writer.close() catch { case _: Throwable => () }
    try Files.deleteIfExists(tmp) catch { case _: Throwable => () }
    ()
  }
}

object ParquetWriter {
  def writeAll(target: Path, schema: Schema, batches: Iterator[ColumnarBatch],
               options: Map[String, String] = Map.empty): Long = {
    val w = new ParquetWriter(target, schema, options)
    try {
      while (batches.hasNext) w.write(batches.next())
      w.close()
    } catch {
      case e: Throwable =>
        w.abort()
        throw e
    }
  }
}
