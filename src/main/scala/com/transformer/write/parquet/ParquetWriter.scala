package com.transformer.write.parquet

import com.transformer.core._

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.hadoop.ParquetWriter.DEFAULT_PAGE_SIZE
import org.apache.parquet.hadoop.api.WriteSupport
import org.apache.parquet.hadoop.api.WriteSupport.WriteContext
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.apache.parquet.io.api.{Binary, RecordConsumer}
import org.apache.parquet.schema.MessageType

import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.concurrent.Callable

/** Writes a stream of [[ColumnarBatch]]es to a single Parquet file.
  *
  * Atomic temp-file + rename on close. Compression defaults to UNCOMPRESSED;
  * override per call site (or per table via `output.json -> options.compression`).
  *
  * Encode is vectorized at the column-batch level: each batch flows row-by-row
  * to parquet-mr's [[RecordConsumer]] without allocating an intermediate
  * [[org.apache.parquet.example.data.simple.SimpleGroup]] per row. That alone
  * removes one boxed `Integer`/`Long` per cell plus an outer record object,
  * which is the dominant overhead on wide-primitive schemas like the polymarket
  * orderbook.
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
    c
  }

  // Default SNAPPY — matches Spark / pyarrow / DuckDB. Dictionary encoding is
  // already enabled (`.withDictionaryEncoding(true)` below), and on high-
  // cardinality string columns (the `market_id`-style 66-char hex columns
  // common in this library's workloads) the dictionary pages themselves
  // dominate the on-disk bytes — SNAPPY compresses those dictionary pages
  // 30-50% so the write actually finishes faster despite the compression CPU
  // (less disk I/O wins on SSD too). UNCOMPRESSED is the right opt-in for
  // pure-narrow-numeric tables where dictionaries don't help much; override
  // per-table via `output.json` -> `options.compression`. Accepts `SNAPPY`,
  // `GZIP`, `UNCOMPRESSED` / `NONE` (the legacy alias is kept for symmetry
  // with Spark's option strings).
  private val codec: CompressionCodecName = options.get("compression").map(_.toUpperCase) match {
    case Some("SNAPPY") | None => CompressionCodecName.SNAPPY
    case Some("UNCOMPRESSED") | Some("NONE") => CompressionCodecName.UNCOMPRESSED
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

  private val writer: org.apache.parquet.hadoop.ParquetWriter[BatchRowRef] =
    new BatchParquetWriterBuilder(new HPath(tmp.toUri), schema, messageType)
      .withConf(conf)
      .withCompressionCodec(codec)
      .withRowGroupSize(rowGroupSize)
      .withPageSize(DEFAULT_PAGE_SIZE)
      .withDictionaryEncoding(true)
      .build()

  // Reusable per-row handle. parquet-mr's `ParquetWriter.write(T)` increments
  // its internal `recordCount` by 1 per call (used to decide when to flush row
  // groups), so we must call `writer.write` once per logical row. The handle
  // lets us do that without allocating a wrapper object per row — we mutate
  // its two fields in the row loop and the WriteSupport reads them inside its
  // `write` callback.
  private val rowHandle = new BatchRowRef
  private var rowsWritten: Long = 0L

  def write(batch: ColumnarBatch): Unit = {
    rowHandle.batch = batch
    val nrows = batch.numRows
    var r = 0
    while (r < nrows) {
      rowHandle.row = r
      writer.write(rowHandle)
      r += 1
    }
    rowsWritten += nrows
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

  /** Heap headroom we reserve per in-flight writer. Each writer pins a 32MB row
    * group buffer (see [[DefaultRowGroupSize]]) plus per-column dictionary pages
    * (~1MB × column count). 256MB gives safe headroom for wide schemas without
    * being so conservative that small jobs underuse the box.
    */
  val PerWriterHeapMb: Long = 256L

  /** Default fan-out for `writePartitioned` when neither
    * `options("parquet_write_parallelism")` nor the heap-derived cap is set
    * explicitly. We pick the smaller of:
    *   * `availableProcessors` — there's no point spawning more writer threads
    *     than the OS will run concurrently;
    *   * `maxHeap / PerWriterHeapMb` — bounded so wide schemas don't OOM under
    *     many simultaneous row-group buffers.
    *
    * Override per-task via `options("parquet_write_parallelism")` for jobs that
    * need to push harder or stay more conservative than the default.
    */
  def defaultWriteParallelism(): Int = {
    val cores = math.max(1, Runtime.getRuntime.availableProcessors)
    val heapMb = Runtime.getRuntime.maxMemory / (1024L * 1024L)
    val byHeap = math.max(1L, heapMb / PerWriterHeapMb).toInt
    math.min(cores, byHeap)
  }

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
    // Cap concurrency for parquet: each in-flight writer holds a row-group buffer
    // (32MB) + per-column dictionary pages (≈1MB × column count). On wide schemas a
    // pool of `cores` writers blows past a modest heap before any data flushes;
    // `defaultWriteParallelism` clamps to `min(cores, heap/256MB)`. Override via
    // options("parquet_write_parallelism") when the workload knows better.
    val cap = options.get("parquet_write_parallelism").map(_.toInt).getOrElse(defaultWriteParallelism())
    val parallelism = math.max(1, math.min(n, cap))
    val writers = new Array[ParquetWriter](n)

    def writerTask(i: Int): Callable[Long] = new Callable[Long] {
      def call(): Long = {
        val target = targetDir.resolve(f"part-$i%05d.parquet")
        val w = new ParquetWriter(target, schema, options)
        writers(i) = w
        val it = partitions(i)
        while (it.hasNext) w.write(it.next())
        w.close()
      }
    }

    // Windowed submission: at most `parallelism` writers in-flight at once. We
    // keep the slots occupied by submitting the next task as soon as the
    // oldest finishes — that bounds heap (and other concurrent SQL tasks aren't
    // blocked by writer threads sitting in semaphore-wait holding FJP workers).
    val futures = new Array[java.util.concurrent.ForkJoinTask[Long]](n)
    var submitted = 0
    var collected = 0
    var total = 0L
    var firstError: Throwable = null
    while (submitted < parallelism && submitted < n) {
      futures(submitted) = Scheduler.submit(writerTask(submitted))
      submitted += 1
    }
    while (collected < n) {
      try total += futures(collected).get()
      catch {
        case e: java.util.concurrent.ExecutionException =>
          if (firstError == null) firstError = e.getCause
        case t: Throwable =>
          if (firstError == null) firstError = t
      }
      collected += 1
      if (submitted < n && firstError == null) {
        futures(submitted) = Scheduler.submit(writerTask(submitted))
        submitted += 1
      }
    }
    if (firstError != null) {
      abortAll(writers)
      throw firstError
    }
    total
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

/** Mutable handle pointing at one row inside a [[ColumnarBatch]]. Reused across
  * the row loop so per-row writer dispatch costs nothing in allocation — the
  * fields are simple references, and the WriteSupport pulls (batch, row) out
  * each time parquet-mr invokes `write`. */
private final class BatchRowRef {
  var batch: ColumnarBatch = _
  var row: Int = 0
}

/** Custom parquet-mr WriteSupport that drives `RecordConsumer` straight from a
  * [[ColumnarBatch]]'s primitive vectors — no intermediate `Group` object, no
  * `Integer`/`Long`/etc. boxing per cell. Called once per logical row by
  * parquet-mr (which needs `recordCount` to advance per call so its row-group
  * flush bookkeeping stays correct); the row index travels via a reusable
  * [[BatchRowRef]] so there's no per-row allocation.
  *
  * Column names + field indices come from the [[Schema]] supplied at
  * construction; the same schema must drive the writer's [[MessageType]] so
  * `startField(name, idx)` aligns with parquet-mr's column dictionary.
  */
private final class BatchWriteSupport(schema: Schema, messageType: MessageType)
    extends WriteSupport[BatchRowRef] {

  private var rc: RecordConsumer = _
  // Pre-cached so the inner write loop doesn't index into Schema.fields per
  // cell. `fieldNames` is referenced by `startField` (parquet-mr matches by
  // name) and `dataTypes` drives the typed value emitter.
  private val ncols = schema.length
  private val fieldNames: Array[String] = Array.tabulate(ncols)(i => schema.fields(i).name)
  private val dataTypes: Array[DataType] = Array.tabulate(ncols)(i => schema.fields(i).dataType)

  override def init(configuration: Configuration): WriteSupport.WriteContext = {
    val md = new java.util.HashMap[String, String]()
    new WriteContext(messageType, md)
  }

  override def prepareForWrite(recordConsumer: RecordConsumer): Unit = {
    rc = recordConsumer
  }

  override def write(ref: BatchRowRef): Unit = {
    val batch = ref.batch
    val r = ref.row
    rc.startMessage()
    var c = 0
    while (c < ncols) {
      val v = batch.column(c)
      if (!v.isNull(r)) {
        val name = fieldNames(c)
        rc.startField(name, c)
        dataTypes(c) match {
          case DataType.BooleanType   => rc.addBoolean(v.asInstanceOf[BooleanVector].get(r))
          case DataType.IntType       => rc.addInteger(v.asInstanceOf[IntVector].get(r))
          case DataType.LongType      => rc.addLong(v.asInstanceOf[LongVector].get(r))
          case DataType.FloatType     => rc.addFloat(v.asInstanceOf[FloatVector].get(r))
          case DataType.DoubleType    => rc.addDouble(v.asInstanceOf[DoubleVector].get(r))
          case DataType.StringType    => rc.addBinary(Binary.fromString(v.asInstanceOf[StringVector].get(r)))
          case DataType.BinaryType    => rc.addBinary(Binary.fromConstantByteArray(v.asInstanceOf[BinaryVector].get(r)))
          case DataType.DateType      => rc.addInteger(v.asInstanceOf[DateVector].get(r))
          case DataType.TimestampType => rc.addLong(v.asInstanceOf[TimestampVector].get(r))
          case other => throw new UnsupportedOperationException(s"Writing $other to Parquet not supported")
        }
        rc.endField(name, c)
      }
      c += 1
    }
    rc.endMessage()
  }
}

/** Tiny builder so we can plug [[BatchWriteSupport]] into parquet-mr's
  * ParquetWriter.Builder pattern. Mirrors `ExampleParquetWriter.Builder` —
  * abstract base does the heavy lifting; we just hand it a `WriteSupport`. */
private final class BatchParquetWriterBuilder(file: HPath, schema: Schema, messageType: MessageType)
    extends org.apache.parquet.hadoop.ParquetWriter.Builder[BatchRowRef, BatchParquetWriterBuilder](file) {

  override def self(): BatchParquetWriterBuilder = this
  override def getWriteSupport(conf: Configuration): WriteSupport[BatchRowRef] =
    new BatchWriteSupport(schema, messageType)
}
