package com.transformer.write.parquet

import com.transformer.core._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.example.data.simple.SimpleGroup
import org.apache.parquet.hadoop.ParquetWriter.DEFAULT_PAGE_SIZE
import org.apache.parquet.hadoop.example.{ExampleParquetWriter, GroupWriteSupport}
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.api.Binary
import org.apache.parquet.schema.MessageType

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.{Callable, Executors, TimeUnit}

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

  // Row group size = peak heap held per writer (uncompressed buffer waiting to flush).
  // parquet-mr's 128MB default × N parallel partition writers blows past modest heaps
  // in writePartitioned. 32MB is a healthy compromise: still good IO patterns and
  // compression ratios, but bounded enough that 10 in-flight writers fit in <1GB.
  // Override via options("parquet_row_group_size") (bytes).
  private val rowGroupSize: Long =
    options.get("parquet_row_group_size").map(_.toLong).getOrElse(ParquetWriter.DefaultRowGroupSize)

  private val writer = ExampleParquetWriter.builder(new HPath(tmp.toUri))
    .withConf(conf)
    .withType(messageType)
    .withCompressionCodec(codec)
    .withRowGroupSize(rowGroupSize)
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
  /** Per-writer row-group buffer cap. parquet-mr defaults to 128MB which OOMs
    * when several writers run in parallel under a modest heap; 32MB keeps page
    * dictionaries effective while letting `writePartitioned` fan out safely.
    */
  val DefaultRowGroupSize: Long = 32L * 1024L * 1024L

  /** Default fan-out for `writePartitioned`. Each in-flight parquet writer
    * pins a row-group buffer + per-column dictionaries — so even on a 10-core
    * box, 10 simultaneous writers can exhaust a 2GB heap on wide schemas.
    * 4 is a safe heuristic; benchmarks with the snapshots dataset hit a hard
    * wall at this many readers + writers in flight. Override per-task via
    * options("parquet_write_parallelism").
    */
  val DefaultWriteParallelism: Int = 4

  /** Drain an iterator of batches into a single Parquet file. Returns rows written. */
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

  /** Write a sequence of partition iterators into `targetDir` as
    * `part-00000.parquet`, ... — one file per input partition, each written
    * in parallel. On any failure, every in-flight writer is aborted.
    */
  def writePartitioned(
      targetDir: Path,
      schema: Schema,
      partitions: IndexedSeq[Iterator[ColumnarBatch]],
      options: Map[String, String] = Map.empty
  ): Long = {
    Files.createDirectories(targetDir)
    val n = partitions.length
    if (n == 0) return 0L
    // Cap concurrency low for parquet: each in-flight writer holds a row-group buffer
    // (32MB) + per-column dictionary pages (≈1MB × column count). On wide schemas a
    // pool of `cores` writers blows past a modest heap before any data flushes.
    // Override via options("parquet_write_parallelism").
    val cap = options.get("parquet_write_parallelism").map(_.toInt).getOrElse(DefaultWriteParallelism)
    val parallelism = math.max(1, math.min(n, math.min(cap, Runtime.getRuntime.availableProcessors)))
    val pool = Executors.newFixedThreadPool(parallelism)
    val writers = new Array[ParquetWriter](n)
    try {
      val futures = (0 until n).map { i =>
        pool.submit(new Callable[Long] {
          def call(): Long = {
            val target = targetDir.resolve(f"part-$i%05d.parquet")
            val w = new ParquetWriter(target, schema, options)
            writers(i) = w
            val it = partitions(i)
            while (it.hasNext) w.write(it.next())
            w.close()
          }
        })
      }
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
    } catch {
      case t: Throwable =>
        abortAll(writers)
        throw t
    } finally {
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }
  }

  private def abortAll(writers: Array[ParquetWriter]): Unit = {
    var i = 0
    while (i < writers.length) {
      val w = writers(i)
      if (w != null) try w.abort() catch { case _: Throwable => () }
      i += 1
    }
  }
}
