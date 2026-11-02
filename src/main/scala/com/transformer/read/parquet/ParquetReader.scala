package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.read.csv.PathGlob
import com.transformer.sql.plan.Expr

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.column.ColumnDescriptor
import org.apache.parquet.column.impl.ColumnReadStoreImpl
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.io.api.{Converter, GroupConverter, PrimitiveConverter}
import org.apache.parquet.filter2.predicate.FilterPredicate
import org.apache.parquet.filter2.statisticslevel.StatisticsFilter
import org.apache.parquet.HadoopReadOptions
import org.apache.parquet.hadoop.{CodecFactory, ParquetFileReader}
import org.apache.parquet.hadoop.metadata.{BlockMetaData, ColumnChunkMetaData, CompressionCodecName, FileMetaData}
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit
import org.apache.parquet.schema.MessageType

import java.nio.channels.ClosedByInterruptException
import java.nio.file.Path
import java.util.concurrent.Callable
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

/** Reads a folder/glob of Parquet files as a single named view.
  *
  * Partition unit = a contiguous selection of row groups inside one file, packed
  * to ~`targetBytesPerPartition` of compressed data each. A small file with a
  * few row groups becomes one partition; a multi-GB file with 100+ row groups
  * becomes ~`fileBytes / targetBytesPerPartition` partitions. That lets a
  * single huge file saturate `cores` worker threads — the old "one partition
  * per file" rule meant a one-file glob was single-threaded regardless of how
  * many row groups were inside.
  *
  * Seeking is metadata-only: each partition opens its own
  * [[ParquetFileReader]] and calls `skipNextRowGroup()` to advance to its first
  * assigned group, then reads the row groups in its range via
  * `readNextRowGroup()`. Skipping is O(1) per group (just a metadata pointer
  * advance) — no column data is read or decoded for skipped groups.
  *
  * Decode is vectorized at the column-batch level: every row group is unpacked
  * one column at a time through parquet-mr's [[ColumnReadStoreImpl]] straight
  * into a [[ColumnarBatch]]'s primitive vectors. No `Group`/`SimpleGroup`
  * objects are allocated per row, no `Integer`/`Long` boxing — the per-row
  * overhead is one definition-level check + one primitive value copy. This is
  * an order of magnitude faster than the older `RecordReader[Group]` path on
  * wide primitive schemas like the polymarket orderbook.
  *
  * Predicate pushdown is supported via [[withPushdownFilter]]. The supplied
  * predicate is best-effort translated to parquet's `FilterPredicate` and
  * applied against each row group's column statistics at iterator time — groups
  * whose min/max prove they can't match are skipped entirely (their column
  * data is never read). The caller is expected to keep the original filter on
  * top of the scan since stats can only PROVE non-matching groups, never PROVE
  * matching ones.
  */
final class ParquetReader private (
    files: IndexedSeq[Path],
    batchSize: Int,
    projectedNames: Option[Set[String]],
    parquetSchema_ : Schema,
    partitions: IndexedSeq[ParquetReader.PartitionMeta],
    pushdownFilter: Option[FilterPredicate],
    comparableColumns: Set[String],
    binaryAsString: Boolean
) extends CatalogView {
  require(files.nonEmpty, "ParquetReader requires at least one file")

  /** Effective output schema. With projection, only the kept fields, in the
    * order they appear in the underlying file's schema (we never reorder —
    * callers' ColRefExpr indices only need a name → new-position lookup).
    */
  val schema: Schema = projectedNames match {
    case Some(keep) => Schema(parquetSchema_.fields.filter(f => keep.contains(f.name)))
    case None => parquetSchema_
  }

  def numPartitions: Int = partitions.length

  /** Its strings are decoded from UTF-8, which cannot encode half a
    * surrogate pair. */
  override def stringsAreText: Boolean = true

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    val m = partitions(p)
    new ParquetPartitionIterator(m.hif, schema, batchSize,
      m.startRowGroup, m.endRowGroup, pushdownFilter, null, binaryAsString, m.label)
  }

  /** Instrumented partition read — funnels per-partition decode statistics
    * into `recorder`. Behavior is identical to [[readPartition]] otherwise;
    * the only change is per-row-group / per-batch bookkeeping that bumps
    * the recorder.
    *
    * Called by [[com.transformer.sql.exec.ScanExec]] when metrics are
    * enabled and the wrapped view is a [[ParquetReader]]. */
  def readPartitionWithMetrics(p: Int, recorder: ParquetReader.MetricsRecorder): Iterator[ColumnarBatch] = {
    val m = partitions(p)
    // Record whether a predicate is pushed down for this partition's reader.
    // The check fires once per partition; the per-row-group skip counting
    // happens inside ParquetPartitionIterator's loadNextGroup.
    if (pushdownFilter.isDefined) recorder.addPredicatePushedDown(1L)
    new ParquetPartitionIterator(m.hif, schema, batchSize,
      m.startRowGroup, m.endRowGroup, pushdownFilter, recorder, binaryAsString, m.label)
  }

  /** Sum the per-partition row counts already collected from each footer. Used
    * by the SQL planner to short-circuit `SELECT COUNT(*) FROM <parquet>` —
    * we already paid for the footer reads at construction time. Unaffected by
    * projection. NB: this is the unfiltered count even when a pushdown filter
    * is set — filter-eliminated row groups still contribute their full row
    * count here, since the count is consumed by `COUNT(*)` paths that don't
    * see the filter.
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
    *
    * Schema + per-partition footer metadata already live in this reader; the
    * projected variant reuses them so the planner can ask for narrow projections
    * under the metadata fast path without paying the open-every-file cost again.
    */
  override def withProjectedColumns(names: Seq[String]): Option[CatalogView] = {
    val available = parquetSchema_.fieldNames.toSet
    val keep = names.iterator.filter(available.contains).toSet
    if (keep.isEmpty || keep.size == parquetSchema_.length) None
    else Some(new ParquetReader(files, batchSize, Some(keep), parquetSchema_, partitions, pushdownFilter, comparableColumns,
      binaryAsString))
  }

  /** Translate `predicate` into a parquet `FilterPredicate` and attach it to a
    * new reader. Returns None when nothing in the predicate could be pushed
    * (e.g. a computed expression like `a - b > 0`). The caller keeps the
    * original [[Expr]] in its [[com.transformer.sql.exec.FilterExec]] above
    * the scan; the filter here only enables row-group skipping in the
    * iterator.
    *
    * A comparison is pushed only on a column that every file stores as the
    * engine's own values, in the engine's order
    * (`ParquetColumns.Decoding.pushesComparisons`), since parquet-mr judges it
    * against the statistics of the stored values, in the file's order: an
    * unsigned `INT32` read as a `BIGINT`, or a `FLOAT16` read as a `FLOAT`, is
    * bounded by statistics of other bits, and an unsigned 8-bit integer is
    * ordered as unsigned, which puts a negative bound above every value. */
  def withPushdownFilter(predicate: Expr): Option[CatalogView] = {
    ParquetFilterTranslator.translate(predicate, comparableColumns.contains).map { fp =>
      new ParquetReader(files, batchSize, projectedNames, parquetSchema_, partitions, Some(fp), comparableColumns,
        binaryAsString)
    }
  }
}

object ParquetReader {
  /** Per-partition decode statistics surfaced through
    * [[ParquetReader.readPartitionWithMetrics]]. The concrete implementation
    * is wired in [[com.transformer.sql.exec.ScanExec]] — the trait stays in
    * `read.parquet` so it doesn't introduce a reverse dependency on
    * `sql.exec`.
    *
    * All methods are bumped from the partition's own thread (the iterator
    * is single-threaded per partition). Implementations route bumps to a
    * `MetricsNode.counters(...).add(n)` against the operator's slot.
    *
    * Bytes accounting: we report the row group's compressed size as the IO
    * cost proxy. Decompressed size would require summing per-column page
    * metadata at read time and is not currently surfaced through parquet-mr
    * cheaply enough. */
  trait MetricsRecorder {
    def addBytesRead(n: Long): Unit
    def addRowGroupsRead(n: Long): Unit
    def addRowGroupsSkipped(n: Long): Unit
    /** Bumped once per partition iterator when the wrapped reader has a
      * pushdown filter set. Not per-row-group — the filter is set or unset
      * for the whole partition. */
    def addPredicatePushedDown(n: Long): Unit
  }

  /** The Hadoop configuration every reader starts from, built once: a
    * `new Configuration()` parses Hadoop's default resources the first time
    * it is read, a millisecond or two, which every spill file read paid. */
  private lazy val baseConf: Configuration = {
    val c = new Configuration()
    // Pin local FS — no HDFS, no Hadoop runtime.
    c.set("fs.defaultFS", "file:///")
    // Use RawLocalFileSystem to match the writer's choice. Hadoop's default
    // ChecksumFileSystem would verify a sibling `.<name>.crc` file on every
    // open; the writer no longer emits those, so verifying them would either
    // fail or fall back silently. Skipping the wrapper also avoids the small
    // open()/lookup() cost of the checksum-file probe on each parquet open.
    c.set("fs.file.impl", "org.apache.hadoop.fs.RawLocalFileSystem")
    c
  }

  /** A reader's configuration: a copy of [[baseConf]], which clones its
    * properties rather than parsing them again. */
  private[parquet] def defaultConf: Configuration = new Configuration(baseConf)

  /** `hif` opened for reading, with read options of its own built from the
    * configuration it was opened with. parquet-mr's `open(file)` builds its
    * options from a fresh `Configuration`, which parses Hadoop's default
    * resources again: 2.2 ms an open, nearly all of what a small file such as
    * a spill file cost to read, and a spill file was opened twice. The
    * options are per reader, since the codec factory they hold is not shared
    * safely.
    *
    * A page whose header carries a CRC is checked against it as its row
    * group is read (`verifyChecksums`), so a page whose bytes changed after
    * they were written fails the read rather than decoding to whatever they
    * now say. parquet-mr and pyarrow check none by default. A file need not
    * carry them: Arrow writes none unless asked, parquet-mr one on every page.
    * Only a refusal's diagnosis reads with them unchecked. */
  private[parquet] def open(hif: HadoopInputFile, verifyChecksums: Boolean = true): ParquetFileReader =
    ParquetFileReader.open(hif,
      HadoopReadOptions.builder(hif.getConfiguration).usePageChecksumVerification(verifyChecksums).build())

  /** A parquet file the reader refuses, in its own words: the message names
    * the file, and the column and row group where the reader knows them, and
    * `cause` keeps what parquet-mr threw, which the message never quotes (a
    * library's exception names its classes and its code's state, and changes
    * with its version). An `IllegalArgumentException`, as every failure the
    * input caused is. */
  final class Unreadable(message: String, cause: Throwable) extends IllegalArgumentException(message, cause)

  /** A stored value that has no engine value, a timestamp past the range of
    * microseconds: a failure on the data, as a CAST that cannot read its text
    * is. */
  final class OutOfRange(message: String) extends ArithmeticException(message)

  /** Whether `e`, thrown as parquet-mr reads a file, is the library's own
    * failure on it, which the reader names in its own words: anything but the
    * reader's own refusals, a fatal error and an interruption. A codec whose
    * library is missing throws a `LinkageError` (`NoClassDefFoundError`),
    * which is the file's codec at fault, not the JVM. */
  private[parquet] def libraryFailure(e: Throwable): Boolean = e match {
    case _: Unreadable | _: OutOfRange => false
    case _: LinkageError => true
    case _ => NonFatal(e) && !causes(e).exists {
      case _: InterruptedException | _: java.io.InterruptedIOException | _: ClosedByInterruptException => true
      case _ => false
    }
  }

  /** `t` and its causes, outermost first. */
  private[parquet] def causes(t: Throwable): Iterator[Throwable] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(20)

  private val codecsDecompressed = new java.util.concurrent.ConcurrentHashMap[CompressionCodecName, java.lang.Boolean]()

  /** Whether parquet-mr can decompress `codec` on this classpath: it makes a
    * decompressor for it, once a codec. `BROTLI` and `LZO` need codec classes
    * nothing here ships, and Hadoop's `LZ4` needs the lz4 library. */
  private[parquet] def decompresses(codec: CompressionCodecName): Boolean =
    codecsDecompressed.computeIfAbsent(codec, c => java.lang.Boolean.valueOf(
      c == CompressionCodecName.UNCOMPRESSED || {
        val factory = new CodecFactory(defaultConf, 0)
        try { factory.getDecompressor(c); true }
        catch { case e: Throwable if NonFatal(e) || e.isInstanceOf[LinkageError] => false }
        finally factory.release()
      })).booleanValue

  /** Compressed-bytes target for one partition's worth of work. Picked to
    * balance:
    *   * enough work per task that scheduling overhead is negligible
    *     (decoding 256MB of compressed parquet is seconds, not microseconds);
    *   * few enough in-flight buffers that a `cores`-wide reader pool stays
    *     under typical heap budgets (8 × ~256MB compressed ≈ ~2GB heap);
    *   * granular enough that a multi-GB file splits into several partitions
    *     so a single big file can saturate worker threads.
    *
    * Override per-input via `options("read_partition_size_bytes")`.
    */
  val DefaultTargetBytesPerPartition: Long = 256L * 1024L * 1024L

  /** One reader partition: a half-open row-group range [startRowGroup, endRowGroup)
    * inside `hif`, with the total `rowCount` pre-computed for fast COUNT(*),
    * and the file as messages name it, the path it was given by.
    *
    * Most partitions cover several row groups (packed up to
    * [[DefaultTargetBytesPerPartition]]). A file whose total compressed size
    * fits in one target becomes one partition spanning all of its row groups.
    */
  private[parquet] final case class PartitionMeta(
      hif: HadoopInputFile,
      rowCount: Long,
      startRowGroup: Int,
      endRowGroup: Int,
      label: String
  )

  def fromPath(pathOrGlob: String, batchSize: Int = ColumnarBatch.DefaultCapacity): ParquetReader =
    fromPath(pathOrGlob, batchSize, DefaultTargetBytesPerPartition)

  def fromPath(pathOrGlob: String, batchSize: Int, targetBytesPerPartition: Long): ParquetReader =
    fromPath(pathOrGlob, batchSize, targetBytesPerPartition, binaryAsString = false)

  /** `binaryAsString` reads each unannotated `BINARY` column as a `STRING`
    * of its UTF-8 bytes, a value that is not UTF-8 failing the read, naming
    * its row (an input's option of the name). */
  def fromPath(
      pathOrGlob: String,
      batchSize: Int,
      targetBytesPerPartition: Long,
      binaryAsString: Boolean
  ): ParquetReader = {
    val files = PathGlob.expand(pathOrGlob).toIndexedSeq
    if (files.isEmpty)
      throw new IllegalArgumentException(s"No Parquet files matched '$pathOrGlob'")
    fromFiles(files, batchSize, targetBytesPerPartition, binaryAsString)
  }

  /** A view of exactly `files`, as a task's output is read back: the part
    * files its run wrote, by name, never what else its directory holds. */
  def fromFiles(
      files: IndexedSeq[Path],
      batchSize: Int = ColumnarBatch.DefaultCapacity,
      targetBytesPerPartition: Long = DefaultTargetBytesPerPartition,
      binaryAsString: Boolean = false
  ): ParquetReader = {
    require(files.nonEmpty, "ParquetReader requires at least one file")
    val footers = readFooters(files, math.max(1L, targetBytesPerPartition), binaryAsString)
    new ParquetReader(files, batchSize, projectedNames = None, footers.schema, footers.partitions,
      pushdownFilter = None, footers.comparableColumns, binaryAsString)
  }

  /** What [[readFooters]] learns from every file of a view: its schema, its
    * partitions, and the names of the columns on which every file's
    * statistics can judge a comparison (`Decoding.pushesComparisons`). */
  private final case class Footers(
      schema: Schema,
      partitions: IndexedSeq[PartitionMeta],
      comparableColumns: Set[String])

  /** Open every file in parallel, decode the footer once, and chunk row groups
    * into partitions. Reused by `withProjectedColumns` so a pruned variant
    * doesn't re-open the world.
    *
    * The view's schema is the first file's. Every file is checked against it
    * here, so a glob whose files disagree fails when it is opened, naming the
    * file, rather than partway through a scan.
    */
  private def readFooters(
      files: IndexedSeq[Path],
      targetBytesPerPartition: Long,
      binaryAsString: Boolean
  ): Footers = {
    val conf = defaultConf
    val tasks: Seq[Callable[FileFooter]] = files.map { f =>
      new Callable[FileFooter] {
        def call(): FileFooter = {
          val hif = HadoopInputFile.fromPath(new HPath(f.toUri), conf)
          val reader =
            try ParquetReader.open(hif)
            catch { case e: Throwable if libraryFailure(e) => throw ParquetFooter.refusal(f.toString, f, e) }
          try {
            // A file whose footer is plain text but whose columns are not
            // opens; reading a column of it would fail on its first page.
            if (reader.getFooter.getFileMetaData.getEncryptionType != FileMetaData.EncryptionType.UNENCRYPTED)
              throw new Unreadable(s"Parquet file '$f' ${ParquetFooter.Encrypted}", null)
            val blocks = reader.getRowGroups
            val codecs = blocks.asScala.iterator.flatMap(_.getColumns.asScala.map(c => c.getPath.toDotString -> c.getCodec))
              .toVector.distinct
            FileFooter(reader.getFooter.getFileMetaData.getSchema, packRowGroups(hif, f.toString, blocks, targetBytesPerPartition),
              codecs)
          } finally reader.close()
        }
      }
    }
    val results = Scheduler.submitAndAwaitAll(tasks)
    val footers = files.map(_.toString).zip(results.map(_.schema))
    val schema = ParquetColumns.viewSchema(footers, binaryAsString)
    val decodings = footers.map { case (label, mt) => ParquetColumns.resolve(schema, mt, label, binaryAsString).decodings }
    // A codec this classpath cannot decompress fails the view when it opens,
    // naming the column, rather than its first page partway through a scan.
    val viewColumns = schema.fieldNames.toSet
    footers.zip(results).foreach { case ((label, _), r) =>
      r.codecs.find { case (column, codec) => viewColumns(column) && !decompresses(codec) }.foreach {
        case (column, codec) => throw new Unreadable(
          s"Parquet file '$label' column '$column' is compressed with $codec, which transformer cannot decompress", null)
      }
    }
    val comparable = schema.fields.indices.collect {
      case i if decodings.forall(_(i).pushesComparisons) => schema.fields(i).name
    }.toSet
    Footers(schema, results.flatMap(_.partitions).toIndexedSeq, comparable)
  }

  /** What one file's footer gives: its schema, its partitions, and the codec
    * of each of its columns (by dotted path; a column in several codecs, one
    * row group to the next, is listed once each). */
  private final case class FileFooter(
      schema: MessageType,
      partitions: IndexedSeq[PartitionMeta],
      codecs: Vector[(String, CompressionCodecName)])

  /** Greedily pack contiguous row groups into partitions of close to `target`
    * bytes each. A row group that is by itself larger than `target` becomes
    * its own partition (we never split a row group across partitions —
    * there's no public API to read part of one).
    *
    * Every group lands in exactly one partition, whatever bytes its footer
    * gives it: a group counts as the larger of its `total_byte_size` and its
    * column chunks' compressed bytes, and as one byte at least, and the last
    * partition closes whenever it holds a group. Packed by `total_byte_size`
    * alone, with a partition emitted only when its bytes were above 0, a file
    * whose writer left that field 0 read as empty (parquet-testing's
    * `nation.dict-malformed.parquet`, 25 rows read as none, `COUNT(*)` 0), and a
    * zero-byte group after a full partition was dropped the same way.
    */
  private[parquet] def packRowGroups(
      hif: HadoopInputFile,
      label: String,
      blocks: java.util.List[BlockMetaData],
      target: Long
  ): IndexedSeq[PartitionMeta] = {
    val n = blocks.size
    val out = mutable.ArrayBuffer.empty[PartitionMeta]
    var startGroup = 0
    var accBytes = 0L
    var accRows = 0L
    var i = 0
    while (i < n) {
      val b = blocks.get(i)
      val groupBytes = math.max(math.max(b.getTotalByteSize, b.getCompressedSize), 1L)
      // If adding this group would push us past target AND we already have at
      // least one group in the partition, close out the current partition first.
      if (i > startGroup && accBytes + groupBytes > target) {
        out += PartitionMeta(hif, accRows, startGroup, i, label)
        startGroup = i
        accBytes = 0L
        accRows = 0L
      }
      accBytes += groupBytes
      accRows += b.getRowCount
      i += 1
    }
    if (startGroup < n) out += PartitionMeta(hif, accRows, startGroup, n, label)
    out.toIndexedSeq
  }
}

/** Iterator that reads a half-open row-group range `[startRowGroup, endRowGroup)`
  * from one parquet file as batches of `batchSize`.
  *
  * Uses the low-level [[ParquetFileReader]] so it can skip past row groups
  * before its first assigned one without paying the O(rows²) row-by-row discard
  * cost a high-level `HParquetReader` approach would incur. `skipNextRowGroup`
  * only advances the metadata pointer — no column data is read or decoded.
  *
  * Decode is column-at-a-time. For each loaded row group we instantiate one
  * [[org.apache.parquet.column.ColumnReader]] per output column, then per
  * `next()` we copy up to `batchSize` typed values from each reader directly
  * into the matching primitive vector in a fresh [[ColumnarBatch]]. The
  * `RecordReader[Group]` path the older implementation used would allocate one
  * `SimpleGroup` per row plus a wrapping `Integer`/`Long` per cell — billions
  * of those objects are the dominant cost on a 2.7B-row scan and the entire
  * reason this rewrite exists.
  *
  * `schema` is the view's (all of the first file's columns, or the projected
  * subset). Its columns are found in this file by name and decoded as this
  * file declares them, and only those column chunks are fetched: the request
  * goes to parquet-mr through `setRequestedSchema`, built from this file's own
  * column types, since parquet-mr decodes a chunk as whatever type the request
  * names.
  *
  * If `pushdownFilter` is set, each candidate row group's column statistics
  * are checked against the filter before opening it; groups proven not to
  * match are skipped without reading their column data.
  */
final class ParquetPartitionIterator(
    hif: org.apache.parquet.hadoop.util.HadoopInputFile,
    schema: Schema,
    batchSize: Int,
    startRowGroup: Int = 0,
    endRowGroup: Int = Int.MaxValue,
    pushdownFilter: Option[FilterPredicate] = None,
    metricsRecorder: ParquetReader.MetricsRecorder = null,
    binaryAsString: Boolean = false,
    label: String = null
) extends Iterator[ColumnarBatch] {

  // The file as messages name it: the path its view was given, else hif's.
  private val fileLabel: String = if (label != null) label else hif.getPath.toUri.getPath
  // Open once: same handle drives schema lookup, the column request,
  // skipNextRowGroup seeks, and the eventual readNextRowGroup loop.
  private val reader: ParquetFileReader =
    try ParquetReader.open(hif)
    catch {
      case e: Throwable if ParquetReader.libraryFailure(e) =>
        throw ParquetFooter.refusal(fileLabel, java.nio.file.Paths.get(hif.getPath.toUri), e)
    }
  private val columns: ParquetColumns.FileColumns =
    try ParquetColumns.resolve(schema, reader.getFooter.getFileMetaData.getSchema, fileLabel, binaryAsString)
    catch { case e: Throwable => reader.close(); throw e }
  // `setRequestedSchema` must come before any read/skip — it tells parquet-mr
  // which column chunks to actually fetch. The skip loop then advances the
  // row-group cursor without reading data (O(1) metadata advance per call) so
  // we can land on `startRowGroup` without paying the full read cost the old
  // `HParquetReader` approach incurred.
  locally {
    try {
      reader.setRequestedSchema(columns.requested)
      var i = 0
      while (i < startRowGroup) { reader.skipNextRowGroup(); i += 1 }
    } catch {
      case e: Throwable =>
        reader.close()
        if (!ParquetReader.libraryFailure(e)) throw e
        throw new ParquetReader.Unreadable(s"Parquet file '$fileLabel' row group $startRowGroup cannot be read", e)
    }
  }

  // Cached for the stats-level pushdown check — `getRowGroups` returns the
  // FULL block list (read from the footer once at open), so we can index into
  // it by absolute row-group position without touching column data.
  private val allBlocks: java.util.List[BlockMetaData] = reader.getRowGroups

  // Column descriptors of the requested columns, indexed in the same order as
  // the output ColumnarBatch's vectors.
  private val columnDescriptors: Array[ColumnDescriptor] = {
    val mc = columns.requested.getColumns
    Array.tabulate(mc.size)(i => mc.get(i))
  }
  private val maxDefinitionLevels: Array[Int] =
    columnDescriptors.map(_.getMaxDefinitionLevel)
  private val decodings: Array[ParquetColumns.Decoding] = columns.decodings
  // Per-column String intern cache. parquet-mr decoders return a fresh
  // `Binary` per row and converting that to a `String` via `toStringUsingUTF8`
  // allocates a brand-new `String` on every call. In ETL workloads it's
  // typical for a single column (foreign-key columns like `market_id`) to
  // hold a tiny dictionary of unique values repeated millions of times — so
  // we cache `Binary → String` per row group and reuse the same `String`
  // instance for matching subsequent rows. The cache is reset at row-group
  // boundaries. Null entries mean "not a string column" — populated lazily in
  // `decodeColumn`.
  //
  // A column caches at most `InternBudgetBytes` of values a row group. A key
  // is the `Binary` the decoder returned: a dictionary's own value, which a
  // lookup finds by reference, or a slice of a page buffer, which keeps that
  // page alive while the row group is read. Unbounded, a column whose values
  // do not repeat, such as a JSON document per row, kept every value of the
  // row group and every page it was read from (some 600 MB a row group of
  // polymarket's snapshots, on each thread reading one), so a scan of it ran
  // out of a 4 GB heap; bounded, it keeps the pages of its first values.
  private val stringInternCache: Array[java.util.HashMap[org.apache.parquet.io.api.Binary, String]] =
    new Array[java.util.HashMap[org.apache.parquet.io.api.Binary, String]](columnDescriptors.length)
  private val internedBytes: Array[Long] = new Array[Long](columnDescriptors.length)
  // Strict UTF-8, for a binaryAsString column; one an iterator, which is read
  // by one thread.
  private lazy val utf8: java.nio.charset.CharsetDecoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)

  private val groupsToProcess: Int = math.max(0, endRowGroup - startRowGroup)
  private var groupsAdvanced: Int = 0
  // Column readers + remaining row count for the row group currently being
  // decoded. Reset whenever we advance to a new row group.
  private var columnReaders: Array[org.apache.parquet.column.ColumnReader] = _
  private var currentRowsRemaining: Long = 0L
  // The row group being read, by its place in the file, for a refusal, and
  // its rows.
  private var currentGroup: Int = startRowGroup
  private var currentGroupRows: Long = 0L
  private var closed: Boolean = false

  private def closeQuietly(): Unit = {
    if (!closed) {
      closed = true
      try reader.close() catch { case _: Throwable => () }
    }
  }

  /** Load the next row group's [[PageReadStore]] and prime per-column
    * readers, transparently skipping any row groups whose statistics prove
    * the pushdown filter excludes them. Returns false when our assigned
    * physical range is exhausted.
    */
  private def loadNextGroup(): Boolean = {
    while (true) {
      if (closed || groupsAdvanced >= groupsToProcess) {
        closeQuietly()
        return false
      }
      val absoluteIdx = startRowGroup + groupsAdvanced
      groupsAdvanced += 1
      val canDrop = pushdownFilter.exists { fp =>
        val block = allBlocks.get(absoluteIdx)
        val cols = block.getColumns.asInstanceOf[java.util.List[ColumnChunkMetaData]]
        try StatisticsFilter.canDrop(fp, cols) catch { case _: Throwable => false }
      }
      if (canDrop) {
        // Skip this group's column data without decoding.
        try reader.skipNextRowGroup() catch { case _: Throwable => () }
        if (metricsRecorder != null) metricsRecorder.addRowGroupsSkipped(1L)
      } else {
        currentGroup = absoluteIdx
        val pages: PageReadStore =
          try reader.readNextRowGroup()
          catch { case e: Throwable if ParquetReader.libraryFailure(e) => throw groupRefusal(absoluteIdx, e) }
        if (pages == null) {
          closeQuietly()
          return false
        }
        // ColumnReadStoreImpl needs a GroupConverter for the (unused-by-us)
        // record-assembly path; supply a no-op one so we never pay for record
        // materialization — we drive each column reader directly. Making a
        // column's reader reads its dictionary page.
        val crs = new ColumnReadStoreImpl(
          pages,
          ParquetPartitionIterator.NoopGroupConverter,
          columns.requested,
          reader.getFileMetaData.getCreatedBy
        )
        columnReaders = Array.tabulate(columnDescriptors.length) { c =>
          try crs.getColumnReader(columnDescriptors(c))
          catch { case e: Throwable if ParquetReader.libraryFailure(e) => throw columnRefusal(c, absoluteIdx, e) }
        }
        currentRowsRemaining = pages.getRowCount
        currentGroupRows = pages.getRowCount
        // Reset the per-column intern caches — Binary refs from the prior
        // row group's page buffers are no longer valid once that row group's
        // pages are released.
        java.util.Arrays.fill(stringInternCache.asInstanceOf[Array[AnyRef]], null)
        java.util.Arrays.fill(internedBytes, 0L)
        if (metricsRecorder != null) {
          metricsRecorder.addRowGroupsRead(1L)
          // Compressed bytes for the row group from the footer — cheap and
          // attributable. Total decompressed size would require summing per-
          // column page sizes which is expensive; the compressed total is
          // the practical IO-cost proxy.
          val block = allBlocks.get(absoluteIdx)
          metricsRecorder.addBytesRead(block.getCompressedSize)
        }
        return true
      }
    }
    false
  }

  override def hasNext: Boolean = {
    while (currentRowsRemaining == 0L && !closed) {
      if (!loadNextGroup()) return false
    }
    currentRowsRemaining > 0L
  }

  /** The first row of the file in the batch being decoded, counted from 0,
    * for a refusal that names a value's row. */
  private var batchFirstRow: Long = 0L

  /** The rows of the file before each row group. */
  private lazy val groupFirstRow: Array[Long] =
    allBlocks.asScala.iterator.map(_.getRowCount).scanLeft(0L)(_ + _).toArray

  override def next(): ColumnarBatch = {
    if (!hasNext) throw new NoSuchElementException
    val rowsThisBatch = math.min(batchSize.toLong, currentRowsRemaining).toInt
    batchFirstRow = groupFirstRow(currentGroup) + (currentGroupRows - currentRowsRemaining)
    val out = new ColumnarBatch(schema, rowsThisBatch)
    val ncols = schema.length
    var c = 0
    while (c < ncols) {
      try decodeColumn(out, c, rowsThisBatch)
      catch { case e: Throwable if ParquetReader.libraryFailure(e) => throw columnRefusal(c, currentGroup, e) }
      c += 1
    }
    out.setNumRows(rowsThisBatch)
    currentRowsRemaining -= rowsThisBatch.toLong
    out
  }

  /** The refusal for column `c` of row group `group`, a page of which
    * parquet-mr failed to decode with `thrown`. Closes the file. */
  private def columnRefusal(c: Int, group: Int, thrown: Throwable): ParquetReader.Unreadable = {
    closeQuietly()
    new ParquetReader.Unreadable(
      s"Parquet file '$fileLabel' column '${schema.fields(c).name}', row group $group: a page does not decode", thrown)
  }

  /** The refusal for row group `group`, whose column chunks parquet-mr failed
    * to read with `thrown`: it reads every chunk with its pages' headers
    * together, checking each page's CRC, so the column at fault is found by
    * reading each alone, and a column that reads alone with the checks left
    * off has a page whose bytes are not the ones its CRC was taken of.
    * Closes the file. */
  private def groupRefusal(group: Int, thrown: Throwable): ParquetReader.Unreadable = {
    closeQuietly()
    columnDescriptors.indices.find(c => !readsAlone(c, group, verifyChecksums = true)) match {
      case Some(c) =>
        val what =
          if (readsAlone(c, group, verifyChecksums = false)) "a page does not match its checksum, so its bytes are not the ones written"
          else "a page does not decode"
        new ParquetReader.Unreadable(s"Parquet file '$fileLabel' column '${schema.fields(c).name}', row group $group: $what", thrown)
      case None => new ParquetReader.Unreadable(s"Parquet file '$fileLabel' row group $group cannot be read", thrown)
    }
  }

  /** Whether column `c`'s chunk of row group `group`, its pages' headers
    * included, reads when it is the only column read. */
  private def readsAlone(c: Int, group: Int, verifyChecksums: Boolean): Boolean = {
    val alone =
      try ParquetReader.open(hif, verifyChecksums)
      catch { case e: Throwable if ParquetReader.libraryFailure(e) => return false }
    try {
      alone.setRequestedSchema(new MessageType(columns.requested.getName, columns.requested.getType(c)))
      var i = 0
      while (i < group) { alone.skipNextRowGroup(); i += 1 }
      alone.readNextRowGroup()
      true
    } catch { case e: Throwable if ParquetReader.libraryFailure(e) => false }
    finally alone.close()
  }

  /** Drain `rowCount` values from `columnReaders(col)` into `batch.column(col)`.
    *
    * The hot inner loop is dispatched once per column (not per row) on how
    * this file stores the column, then specialized to the matching primitive
    * vector. Each row is one definition-level check + one typed copy — no
    * boxing, no `Group` allocation.
    */
  private def decodeColumn(batch: ColumnarBatch, col: Int, rowCount: Int): Unit = {
    val cr = columnReaders(col)
    val maxDef = maxDefinitionLevels(col)
    val vec = batch.column(col)
    decodings(col) match {
      case ParquetColumns.Decoding.Booleans =>
        val v = vec.asInstanceOf[BooleanVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getBoolean)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Ints | ParquetColumns.Decoding.UnsignedNarrowInts =>
        val v = vec.asInstanceOf[IntVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getInteger)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.UnsignedInts =>
        val v = vec.asInstanceOf[LongVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, java.lang.Integer.toUnsignedLong(cr.getInteger))
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Dates =>
        val v = vec.asInstanceOf[DateVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getInteger)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Longs =>
        val v = vec.asInstanceOf[LongVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getLong)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.UnsignedLongs =>
        val v = vec.asInstanceOf[DecimalVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else {
            val bits = cr.getLong
            v.set(r,
              if (bits >= 0L) java.math.BigDecimal.valueOf(bits)
              else new java.math.BigDecimal(java.math.BigInteger.valueOf(bits).add(ParquetPartitionIterator.TwoTo64)))
          }
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Timestamps(unit) =>
        val v = vec.asInstanceOf[TimestampVector]
        var r = 0
        unit match {
          case TimeUnit.MICROS =>
            while (r < rowCount) {
              if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
              else v.set(r, cr.getLong)
              cr.consume(); r += 1
            }
          case TimeUnit.MILLIS =>
            while (r < rowCount) {
              if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
              else {
                val millis = cr.getLong
                if (millis > ParquetPartitionIterator.MaxMillis || millis < ParquetPartitionIterator.MinMillis)
                  throw new ParquetReader.OutOfRange(
                    s"Parquet file '$fileLabel' column '${schema.fields(col).name}' holds TIMESTAMP(MILLIS) " +
                      s"$millis, which is past the range of a timestamp in microseconds")
                v.set(r, millis * 1000L)
              }
              cr.consume(); r += 1
            }
          case TimeUnit.NANOS =>
            while (r < rowCount) {
              if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
              else v.set(r, Math.floorDiv(cr.getLong, 1000L))
              cr.consume(); r += 1
            }
        }
      case ParquetColumns.Decoding.Int96Timestamps =>
        // parquet-mr surfaces the 12 bytes as a Binary; `int96ToMicros`
        // reads the Julian day and nanoseconds of the day out of them.
        val v = vec.asInstanceOf[TimestampVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r,
            try ParquetPartitionIterator.int96ToMicros(cr.getBinary.getBytes)
            catch {
              case e: ArithmeticException => throw new ParquetReader.OutOfRange(
                s"Parquet file '$fileLabel' column '${schema.fields(col).name}' holds an ${e.getMessage}, " +
                  "which is past the range of a timestamp in microseconds")
            })
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Floats =>
        val v = vec.asInstanceOf[FloatVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getFloat)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.HalfFloats =>
        val v = vec.asInstanceOf[FloatVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, java.lang.Float.float16ToFloat(cr.getBinary.get2BytesLittleEndian))
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Doubles =>
        val v = vec.asInstanceOf[DoubleVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getDouble)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Strings =>
        val v = vec.asInstanceOf[StringVector]
        // Lazily build the row-group-scoped intern cache. After the first
        // ~K distinct values are seen the cache hits dominate and we save
        // a `String` allocation per row. For columns with very high
        // distinct cardinality (a UUID per row, say) the cache stays
        // ineffective but the overhead is one HashMap lookup per row —
        // negligible next to the avoided allocation on the common case.
        var cache = stringInternCache(col)
        if (cache == null) {
          cache = new java.util.HashMap[org.apache.parquet.io.api.Binary, String]()
          stringInternCache(col) = cache
        }
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else {
            val bin = cr.getBinary
            var s = cache.get(bin)
            if (s == null) {
              s = bin.toStringUsingUTF8
              if (internedBytes(col) < ParquetPartitionIterator.InternBudgetBytes) {
                cache.put(bin, s)
                internedBytes(col) += bin.length
              }
            }
            v.set(r, s)
          }
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.BinaryText =>
        // As Strings, but each value is decoded strictly: bytes that are no
        // UTF-8 fail the read rather than read as U+FFFD.
        val v = vec.asInstanceOf[StringVector]
        var cache = stringInternCache(col)
        if (cache == null) {
          cache = new java.util.HashMap[org.apache.parquet.io.api.Binary, String]()
          stringInternCache(col) = cache
        }
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else {
            val bin = cr.getBinary
            var s = cache.get(bin)
            if (s == null) {
              s = try utf8.decode(bin.toByteBuffer).toString
                catch {
                  case _: java.nio.charset.CharacterCodingException => throw new ParquetReader.Unreadable(
                    s"Parquet file '$fileLabel' column '${schema.fields(col).name}' row ${batchFirstRow + r + 1} is " +
                      "not valid UTF-8, which binaryAsString reads the column as", null)
                }
              if (internedBytes(col) < ParquetPartitionIterator.InternBudgetBytes) {
                cache.put(bin, s)
                internedBytes(col) += bin.length
              }
            }
            v.set(r, s)
          }
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.Bytes =>
        val v = vec.asInstanceOf[BinaryVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getBinary.getBytes)
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.IntDecimals(_, scale) =>
        val v = vec.asInstanceOf[DecimalVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, java.math.BigDecimal.valueOf(cr.getInteger.toLong, scale))
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.LongDecimals(_, scale) =>
        val v = vec.asInstanceOf[DecimalVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, java.math.BigDecimal.valueOf(cr.getLong, scale))
          cr.consume(); r += 1
        }
      case ParquetColumns.Decoding.ByteDecimals(_, scale) =>
        val v = vec.asInstanceOf[DecimalVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else {
            // BigInteger reads big-endian two's complement and copies what
            // it keeps, so the page's own bytes can be handed over.
            val unscaled = cr.getBinary.getBytesUnsafe
            if (unscaled.length == 0) throw new ParquetReader.Unreadable(
              s"Parquet file '$fileLabel' column '${schema.fields(col).name}' holds a DECIMAL with no bytes", null)
            v.set(r, new java.math.BigDecimal(new java.math.BigInteger(unscaled), scale))
          }
          cr.consume(); r += 1
        }
    }
  }
}

object ParquetPartitionIterator {
  // ColumnReadStoreImpl requires a GroupConverter (it'd use it to assemble
  // records if anyone called `getRecordReader`). We never do, so an
  // empty-everything converter is plenty.
  private[parquet] val NoopGroupConverter: GroupConverter = new GroupConverter {
    private val sink: PrimitiveConverter = new PrimitiveConverter {}
    override def getConverter(fieldIndex: Int): Converter = sink
    override def start(): Unit = ()
    override def end(): Unit = ()
  }

  // Added to a negative INT64's value, its value read as unsigned.
  private[parquet] val TwoTo64: java.math.BigInteger = java.math.BigInteger.ONE.shiftLeft(64)

  /** The bytes of values a string column interns a row group: past them, a
    * value not yet cached is decoded each time it is read. A foreign key's
    * distinct values in a row group fit many times over. */
  private[parquet] val InternBudgetBytes: Long = 4L * 1024 * 1024

  // The TIMESTAMP(MILLIS) values that have a microsecond value: times 1000,
  // anything past these overflows a long.
  private[parquet] val MaxMillis: Long = Long.MaxValue / 1000L
  private[parquet] val MinMillis: Long = Long.MinValue / 1000L

  // Parquet's INT96 timestamp encoding: 8 bytes nanos-of-day (little-endian)
  // followed by 4 bytes julian day. Convert to micros since epoch the same
  // way Spark / Hive / DuckDB do.
  private val JulianEpochOffsetDays: Long = 2440588L  // 1970-01-01 in julian-day
  private val MicrosPerDay: Long = 86400000000L
  private val NanosPerMicro: Long = 1000L

  private[parquet] def int96ToMicros(b: Array[Byte]): Long = {
    require(b.length == 12, s"INT96 must be 12 bytes, got ${b.length}")
    val nanos =
      (b(0) & 0xffL) |
      ((b(1) & 0xffL) << 8) |
      ((b(2) & 0xffL) << 16) |
      ((b(3) & 0xffL) << 24) |
      ((b(4) & 0xffL) << 32) |
      ((b(5) & 0xffL) << 40) |
      ((b(6) & 0xffL) << 48) |
      ((b(7) & 0xffL) << 56)
    val julianDay =
      (b(8) & 0xff) |
      ((b(9) & 0xff) << 8) |
      ((b(10) & 0xff) << 16) |
      ((b(11) & 0xff) << 24)
    // A day this far from 1970 has no microsecond a long counts, which the
    // product wrapped round to another instant (plans/robustness/25).
    try Math.addExact(Math.multiplyExact(julianDay.toLong - JulianEpochOffsetDays, MicrosPerDay), nanos / NanosPerMicro)
    catch {
      case _: ArithmeticException =>
        throw new ArithmeticException(s"INT96 timestamp of Julian day $julianDay and $nanos nanoseconds")
    }
  }
}
