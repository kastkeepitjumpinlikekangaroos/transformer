package com.transformer.read.parquet

import com.transformer.core._
import com.transformer.read.csv.PathGlob
import com.transformer.sql.plan.Expr

import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path => HPath}
import org.apache.parquet.column.ColumnDescriptor
import org.apache.parquet.column.impl.ColumnReadStoreImpl
import org.apache.parquet.column.page.PageReadStore
import org.apache.parquet.io.ParquetDecodingException
import org.apache.parquet.io.api.{Converter, GroupConverter, PrimitiveConverter}
import org.apache.parquet.filter2.predicate.FilterPredicate
import org.apache.parquet.filter2.statisticslevel.StatisticsFilter
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.metadata.{BlockMetaData, ColumnChunkMetaData}
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.parquet.schema.LogicalTypeAnnotation.TimeUnit
import org.apache.parquet.schema.MessageType

import java.nio.file.Path
import java.util.concurrent.Callable
import scala.collection.mutable

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
    pushdownFilter: Option[FilterPredicate]
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

  def readPartition(p: Int): Iterator[ColumnarBatch] = {
    val m = partitions(p)
    new ParquetPartitionIterator(m.hif, schema, batchSize,
      m.startRowGroup, m.endRowGroup, pushdownFilter, null)
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
      m.startRowGroup, m.endRowGroup, pushdownFilter, recorder)
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
    else Some(new ParquetReader(files, batchSize, Some(keep), parquetSchema_, partitions, pushdownFilter))
  }

  /** Translate `predicate` into a parquet `FilterPredicate` and attach it to a
    * new reader. Returns None when nothing in the predicate could be pushed
    * (e.g. a computed expression like `a - b > 0`). The caller keeps the
    * original [[Expr]] in its [[com.transformer.sql.exec.FilterExec]] above
    * the scan; the filter here only enables row-group skipping in the
    * iterator. */
  def withPushdownFilter(predicate: Expr): Option[CatalogView] = {
    ParquetFilterTranslator.translate(predicate).map { fp =>
      new ParquetReader(files, batchSize, projectedNames, parquetSchema_, partitions, Some(fp))
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

  private[parquet] def defaultConf: Configuration = {
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
    * inside `hif`, with the total `rowCount` pre-computed for fast COUNT(*).
    *
    * Most partitions cover several row groups (packed up to
    * [[DefaultTargetBytesPerPartition]]). A file whose total compressed size
    * fits in one target becomes one partition spanning all of its row groups.
    */
  private[parquet] final case class PartitionMeta(
      hif: HadoopInputFile,
      rowCount: Long,
      startRowGroup: Int,
      endRowGroup: Int
  )

  def fromPath(pathOrGlob: String, batchSize: Int = ColumnarBatch.DefaultCapacity): ParquetReader =
    fromPath(pathOrGlob, batchSize, DefaultTargetBytesPerPartition)

  def fromPath(
      pathOrGlob: String,
      batchSize: Int,
      targetBytesPerPartition: Long
  ): ParquetReader = {
    val files = PathGlob.expand(pathOrGlob).toIndexedSeq
    if (files.isEmpty)
      throw new IllegalArgumentException(s"No Parquet files matched '$pathOrGlob'")
    val (schema, parts) = readFooters(files, math.max(1L, targetBytesPerPartition))
    new ParquetReader(files, batchSize, projectedNames = None, schema, parts, pushdownFilter = None)
  }

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
      targetBytesPerPartition: Long
  ): (Schema, IndexedSeq[PartitionMeta]) = {
    val conf = defaultConf
    val tasks: Seq[Callable[(MessageType, IndexedSeq[PartitionMeta])]] = files.map { f =>
      new Callable[(MessageType, IndexedSeq[PartitionMeta])] {
        def call(): (MessageType, IndexedSeq[PartitionMeta]) = {
          val hif = HadoopInputFile.fromPath(new HPath(f.toUri), conf)
          val reader = ParquetFileReader.open(hif)
          try {
            val blocks = reader.getRowGroups
            val parts = packRowGroups(hif, blocks, targetBytesPerPartition)
            (reader.getFooter.getFileMetaData.getSchema, parts)
          } finally reader.close()
        }
      }
    }
    val results = Scheduler.submitAndAwaitAll(tasks)
    val footers = files.map(_.toString).zip(results.map(_._1))
    val schema = ParquetColumns.viewSchema(footers)
    footers.foreach { case (label, mt) => ParquetColumns.resolve(schema, mt, label) }
    val allPartitions = results.flatMap(_._2).toIndexedSeq
    (schema, allPartitions)
  }

  /** Greedily pack contiguous row groups into partitions whose summed
    * `totalByteSize` stays close to `target`. A row group that is by itself
    * larger than `target` becomes its own partition (we never split a row
    * group across partitions — there's no public API to read part of one).
    */
  private def packRowGroups(
      hif: HadoopInputFile,
      blocks: java.util.List[org.apache.parquet.hadoop.metadata.BlockMetaData],
      target: Long
  ): IndexedSeq[PartitionMeta] = {
    val n = blocks.size
    if (n == 0) return IndexedSeq.empty
    val out = mutable.ArrayBuffer.empty[PartitionMeta]
    var startGroup = 0
    var accBytes = 0L
    var accRows = 0L
    var i = 0
    while (i < n) {
      val b = blocks.get(i)
      val groupBytes = b.getTotalByteSize
      val groupRows = b.getRowCount
      // If adding this group would push us past target AND we already have at
      // least one group in the partition, close out the current partition first.
      if (accBytes > 0L && accBytes + groupBytes > target) {
        out += PartitionMeta(hif, accRows, startGroup, i)
        startGroup = i
        accBytes = 0L
        accRows = 0L
      }
      accBytes += groupBytes
      accRows += groupRows
      i += 1
    }
    if (accBytes > 0L) out += PartitionMeta(hif, accRows, startGroup, n)
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
    metricsRecorder: ParquetReader.MetricsRecorder = null
) extends Iterator[ColumnarBatch] {

  // Open once: same handle drives schema lookup, the column request,
  // skipNextRowGroup seeks, and the eventual readNextRowGroup loop.
  private val reader: ParquetFileReader = ParquetFileReader.open(hif)
  private val fileLabel: String = hif.getPath.toUri.getPath
  private val columns: ParquetColumns.FileColumns =
    try ParquetColumns.resolve(schema, reader.getFooter.getFileMetaData.getSchema, fileLabel)
    catch { case e: Throwable => reader.close(); throw e }
  // `setRequestedSchema` must come before any read/skip — it tells parquet-mr
  // which column chunks to actually fetch. The skip loop then advances the
  // row-group cursor without reading data (O(1) metadata advance per call) so
  // we can land on `startRowGroup` without paying the full read cost the old
  // `HParquetReader` approach incurred.
  locally {
    reader.setRequestedSchema(columns.requested)
    var i = 0
    while (i < startRowGroup) { reader.skipNextRowGroup(); i += 1 }
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
  // boundaries because `Binary` references point into the page buffers
  // freed when the next row group is read. Null entries mean "not a string
  // column" — populated lazily in `decodeColumn`.
  private val stringInternCache: Array[java.util.HashMap[org.apache.parquet.io.api.Binary, String]] =
    new Array[java.util.HashMap[org.apache.parquet.io.api.Binary, String]](columnDescriptors.length)

  private val groupsToProcess: Int = math.max(0, endRowGroup - startRowGroup)
  private var groupsAdvanced: Int = 0
  // Column readers + remaining row count for the row group currently being
  // decoded. Reset whenever we advance to a new row group.
  private var columnReaders: Array[org.apache.parquet.column.ColumnReader] = _
  private var currentRowsRemaining: Long = 0L
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
        val pages: PageReadStore = reader.readNextRowGroup()
        if (pages == null) {
          closeQuietly()
          return false
        }
        // ColumnReadStoreImpl needs a GroupConverter for the (unused-by-us)
        // record-assembly path; supply a no-op one so we never pay for record
        // materialization — we drive each column reader directly.
        val crs = new ColumnReadStoreImpl(
          pages,
          ParquetPartitionIterator.NoopGroupConverter,
          columns.requested,
          reader.getFileMetaData.getCreatedBy
        )
        columnReaders = columnDescriptors.map(crs.getColumnReader)
        currentRowsRemaining = pages.getRowCount
        // Reset the per-column intern caches — Binary refs from the prior
        // row group's page buffers are no longer valid once that row group's
        // pages are released.
        java.util.Arrays.fill(stringInternCache.asInstanceOf[Array[AnyRef]], null)
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

  override def next(): ColumnarBatch = {
    if (!hasNext) throw new NoSuchElementException
    val rowsThisBatch = math.min(batchSize.toLong, currentRowsRemaining).toInt
    val out = new ColumnarBatch(schema, rowsThisBatch)
    val ncols = schema.length
    var c = 0
    while (c < ncols) {
      decodeColumn(out, c, rowsThisBatch)
      c += 1
    }
    out.setNumRows(rowsThisBatch)
    currentRowsRemaining -= rowsThisBatch.toLong
    out
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
      case ParquetColumns.Decoding.Ints =>
        val v = vec.asInstanceOf[IntVector]
        var r = 0
        while (r < rowCount) {
          if (cr.getCurrentDefinitionLevel < maxDef) v.setNull(r)
          else v.set(r, cr.getInteger)
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
                  throw new ArithmeticException(
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
          else v.set(r, ParquetPartitionIterator.int96ToMicros(cr.getBinary.getBytes))
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
              cache.put(bin, s)
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
            if (unscaled.length == 0) throw new ParquetDecodingException(
              s"Parquet file '$fileLabel' column '${schema.fields(col).name}' holds a DECIMAL with no bytes")
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

  // Parquet's INT96 timestamp encoding: 8 bytes nanos-of-day (little-endian)
  // followed by 4 bytes julian day. Convert to micros since epoch the same
  // way Spark / Hive / DuckDB do.
  // The TIMESTAMP(MILLIS) values that have a microsecond value: times 1000,
  // anything past these overflows a long.
  private[parquet] val MaxMillis: Long = Long.MaxValue / 1000L
  private[parquet] val MinMillis: Long = Long.MinValue / 1000L

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
    (julianDay.toLong - JulianEpochOffsetDays) * MicrosPerDay + nanos / NanosPerMicro
  }
}
