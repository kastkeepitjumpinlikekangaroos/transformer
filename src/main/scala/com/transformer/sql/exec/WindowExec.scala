package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.core.metrics.MetricsNode
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}

import java.nio.file.Path
import scala.collection.mutable

/** Window-function projection: materializes the child, groups rows by spec,
  * sorts within partition, and writes one output column per [[WindowDef]].
  *
  * Two execution modes, selected by whether `child` is an [[ExchangeExec]]:
  *
  *   - **Per-shard** (child is `ExchangeExec`): the planner has wrapped the
  *     child in an exchange partitioned by the windows' shared PARTITION BY
  *     keys (only possible when every [[WindowSpec]] in `windows` shares the
  *     same non-empty `partitionKeys`). Rows belonging to the same window
  *     partition land in the same shard, so each shard processes its rows
  *     independently — no cross-shard coordination. `numPartitions = K`.
  *   - **Collapsing** (child is anything else): no exchange. The operator
  *     reads every child partition, materializes all rows into one buffer,
  *     and emits as a single output partition. Reached when PARTITION BY is
  *     empty (whole-result window), when window specs disagree on their
  *     PARTITION BY keys (no single sharding satisfies both), or when the
  *     planner's cardinality estimate is below
  *     [[com.transformer.sql.plan.LogicalPlanCardinality.MinShardableSize]].
  *
  * Output schema is `child.outputSchema ++ windowOutputs` — original column
  * indices are preserved so projections bound against the child can still
  * reference them by index.
  *
  * ## Spill
  *
  * Window's correctness requires full visibility within each partition. The
  * spill design exploits the same property: route every input row to
  * `hash(partitionKey) % K` (K=16) disk parquet buckets, then process each
  * bucket independently — every row sharing a partition key collocates in
  * the same bucket, so the in-memory window computation per bucket sees a
  * complete view of every partition it contains.
  *
  * Gated on `opts.spillEnabled && canSpill`, where `canSpill` requires all
  * window specs to agree on the same non-empty PARTITION BY key set (the
  * same gate the planner uses for sharded windows). When specs disagree or
  * the window spans the whole result (empty PARTITION BY), spill is
  * silently disabled — the in-memory path runs unchanged.
  *
  * Limitations:
  *   - A single PARTITION BY key whose rows exceed heap will still OOM at
  *     the bucket-processing step. Recursive sub-bucketing is v1.1.
  *   - LAG / LEAD / aggregate frames all stay correct because the bucket
  *     holds every row of every partition it contains. No cross-bucket
  *     reads are ever needed.
  *
  * Vectorization: during the single materialization pass we also eval each
  * [[WindowSpec]]'s partition keys and order keys once per scan batch via
  * [[Expr.evalVec]] and stash the per-row values alongside the boxed rows.
  * Downstream partitioning + sorting + rank tie-detection read from those
  * cached values instead of re-running per-row `Expr.eval` against a
  * [[RowBuf]]. LAG/LEAD args and aggregate args still go through the
  * per-row path — a follow-up.
  */
final case class WindowExec(
    child: PhysicalPlan,
    windows: Seq[WindowDef],
    opts: ExecutionOptions = ExecutionOptions.Default,
    metricsNode: MetricsNode = null
) extends PhysicalPlan {

  val outputSchema: Schema = Schema(
    child.outputSchema.fields ++ windows.map(w => Field(w.outputName, w.fn.resultType))
  )

  /** True when the planner has wrapped `child` in an `ExchangeExec`
    * partitioned by the shared PARTITION BY keys of every WindowSpec. The
    * planner only does this when all specs agree on a single non-empty
    * PARTITION BY key set; the operator just trusts the wrapper. */
  private val isSharded: Boolean = child.isInstanceOf[ExchangeExec]

  def numPartitions: Int = if (isSharded) child.numPartitions else 1

  /** Spill-eligibility check. We can only safely bucket child rows when
    * every WindowSpec agrees on the same non-empty PARTITION BY key set
    * — same gate the planner uses for sharded windows. Empty PARTITION
    * BY (whole-result window) means a single global partition that can't
    * be bucketed without losing visibility; mixed PARTITION BYs would
    * each need a different bucketing. */
  private val canSpill: Boolean = {
    val distinctParts = windows.map(_.spec.partitionKeys).distinct
    distinctParts.length == 1 && distinctParts.head.nonEmpty
  }

  private val spillEnabled: Boolean = opts.spillEnabled && canSpill

  /** Bucket count for the spill path. Fixed at 16 — same default as
    * [[HashJoinExec]]'s grace hash. Power-of-two so `& (K-1)` works for
    * the modulo. Recursive sub-bucketing for hot keys is v1.1. */
  private val SpillBuckets: Int = 16

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition >= 0 && partition < numPartitions,
      s"partition $partition out of range [0,$numPartitions)")
    if (spillEnabled) executeSpilled(partition)
    else {
      val it: Iterator[ColumnarBatch] =
        if (isSharded) child.execute(partition)
        else (0 until child.numPartitions).iterator.flatMap(child.execute)
      processBatches(it)
    }
  }

  /** Spill execute path: route every input row into K disk parquet buckets
    * keyed by `hash(partitionKey) % K`, then run the in-memory window
    * pipeline over each bucket independently. Bucket iterators chain so
    * peak memory is one bucket's materialized state at a time. */
  private def executeSpilled(partition: Int): Iterator[ColumnarBatch] = {
    val K = SpillBuckets
    val spillDir = Spill.openOperatorDir("window")
    val childParts: Seq[Int] =
      if (isSharded) Seq(partition) else 0 until child.numPartitions

    val bucketFiles = routeChildToBuckets(childParts, K, spillDir)

    val concat = bucketFiles.iterator.flatMap { file =>
      if (file == null) Iterator.empty
      else {
        val reader = ParquetReader.fromPath(file.toString)
        val batchIt = (0 until reader.numPartitions).iterator.flatMap(reader.readPartition)
        processBatches(batchIt)
      }
    }
    spillDir.closeOnDrain(concat)
  }

  /** Route every input row into K bucket files by
    * `fmix32(hash(partitionKey)) % K`. Returns the K file paths (null
    * entries for buckets that received no rows). The routing key is the
    * shared PARTITION BY key set, guaranteed non-empty by [[canSpill]]. */
  private def routeChildToBuckets(
      parts: Seq[Int],
      K: Int,
      spillDir: OperatorSpillDir): Array[Path] = {
    val schema = child.outputSchema
    // Buckets are read back by index (see processBatches); positional names
    // keep a duplicate-named child schema round-trippable. See
    // Spill.positionalSchema.
    val spillFileSchema = Spill.positionalSchema(schema)
    val ncols = schema.length
    val routingKeyExprs = windows.head.spec.partitionKeys.toArray
    val nKeys = routingKeyExprs.length

    val files = new Array[Path](K)
    val writers = new Array[TParquetWriter](K)
    val pending = Array.fill(K)(new ColumnarBatch(schema, ColumnarBatch.DefaultCapacity))
    val pendingCounts = new Array[Int](K)
    var peakBuffered: Long = 0L

    def flushBucket(b: Int): Unit = {
      val n = pendingCounts(b)
      if (n == 0) return
      val out = pending(b)
      out.setNumRows(n)
      if (writers(b) == null) {
        files(b) = spillDir.newSpillFile(s".window.b$b.parquet")
        writers(b) = new TParquetWriter(files(b), spillFileSchema, Map.empty)
      }
      writers(b).write(out)
      if (metricsNode != null) metricsNode.counters(WindowExec.IdxPartitionSpillEvents).increment()
      pending(b) = new ColumnarBatch(schema, ColumnarBatch.DefaultCapacity)
      pendingCounts(b) = 0
    }

    parts.foreach { p =>
      val it = child.execute(p)
      while (it.hasNext) {
        val batch = it.next()
        val nrows = batch.numRows
        if (nrows > 0) {
          // Evaluate the partition-key columns once per batch.
          val keyVecs = new Array[ColumnVector](nKeys)
          var k = 0
          while (k < nKeys) { keyVecs(k) = routingKeyExprs(k).evalVec(batch); k += 1 }
          var r = 0
          while (r < nrows) {
            val bucket = bucketIndex(keyVecs, r, K)
            val dst = pending(bucket)
            val dstRow = pendingCounts(bucket)
            var c = 0
            while (c < ncols) {
              batch.column(c).copyTo(r, dst.column(c), dstRow)
              c += 1
            }
            pendingCounts(bucket) = dstRow + 1
            if (pendingCounts(bucket) > peakBuffered) peakBuffered = pendingCounts(bucket).toLong
            if (pendingCounts(bucket) >= ColumnarBatch.DefaultCapacity) flushBucket(bucket)
            r += 1
          }
        }
      }
    }
    var b = 0
    while (b < K) { flushBucket(b); b += 1 }
    b = 0
    while (b < K) {
      val w = writers(b)
      if (w != null) w.close()
      b += 1
    }
    if (metricsNode != null) {
      metricsNode.counters(WindowExec.IdxPeakBufferedRows).add(peakBuffered)
      var total: Long = 0L
      var i = 0
      while (i < K) {
        val f = files(i)
        if (f != null) total += f.toFile.length()
        i += 1
      }
      metricsNode.counters(WindowExec.IdxBytesSpilled).add(total)
    }
    files
  }

  /** Bucket index = `fmix32(combinedHash(partitionKeys)) % K`. Mirrors the
    * mixing strategy used by [[HashJoinExec]]'s grace hash (same shape as
    * `HashPartitioner.fmix32`). NULL partition keys deterministically
    * land in bucket 0 — equivalent rows go together so window semantics
    * survive (NULL is its own partition under SQL's `GROUP BY` rules). */
  private def bucketIndex(keyVecs: Array[ColumnVector], row: Int, K: Int): Int = {
    var h = 1
    var anyNull = false
    var k = 0
    val nKeys = keyVecs.length
    while (k < nKeys) {
      val col = keyVecs(k)
      if (col.isNull(row)) anyNull = true
      else h = 31 * h + HashJoinExec.columnHashCode(col, row)
      k += 1
    }
    if (anyNull) return 0
    val mixed = HashJoinExec.fmix32(h)
    (mixed & 0x7FFFFFFF) % K
  }

  /** The original execute() body, parameterized by a batch iterator. Used
    * by both the in-memory path (whole-input iterator) and the spill path
    * (one iterator per bucket file). Memory bound = the iterator's full
    * row content; correctness depends on every row sharing a partition
    * key collocating in this iterator's input. */
  private def processBatches(it: Iterator[ColumnarBatch]): Iterator[ColumnarBatch] = {
    val childSchema = child.outputSchema
    val childWidth = childSchema.length
    val nWindows = windows.size

    // Distinct specs in declaration order. One spec → one partitioning pass.
    val specs: Array[WindowSpec] = windows.iterator.map(_.spec).toArray.distinct
    val nSpecs = specs.length
    val specPartKeyExprs: Array[Array[Expr]] = specs.map(_.partitionKeys.toArray)
    val specOrderKeyExprs: Array[Array[Expr]] = specs.map(_.orderKeys.iterator.map(_._1).toArray)

    val partKeysByRow: Array[mutable.ArrayBuffer[Array[Any]]] =
      Array.tabulate(nSpecs)(_ => mutable.ArrayBuffer.empty[Array[Any]])
    val orderKeysByRow: Array[mutable.ArrayBuffer[Array[Any]]] =
      Array.tabulate(nSpecs)(_ => mutable.ArrayBuffer.empty[Array[Any]])

    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    while (it.hasNext) {
      val b = it.next()
      val nrows = b.numRows
      val partVecs: Array[Array[ColumnVector]] = Array.tabulate(nSpecs) { si =>
        specPartKeyExprs(si).map(_.evalVec(b))
      }
      val orderVecs: Array[Array[ColumnVector]] = Array.tabulate(nSpecs) { si =>
        specOrderKeyExprs(si).map(_.evalVec(b))
      }
      var r = 0
      while (r < nrows) {
        val arr = new Array[Any](childWidth)
        var c = 0
        while (c < childWidth) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        rows += arr

        var si = 0
        while (si < nSpecs) {
          partKeysByRow(si) += readVecsAt(partVecs(si), r)
          orderKeysByRow(si) += readVecsAt(orderVecs(si), r)
          si += 1
        }
        r += 1
      }
    }

    val n = rows.length
    val windowValues = Array.ofDim[Any](n, nWindows)

    if (n > 0) {
      val specFns: Map[WindowSpec, IndexedSeq[(WindowFn, Int)]] =
        windows.zipWithIndex.groupBy { case (w, _) => w.spec }
          .map { case (s, ws) => s -> ws.map { case (w, i) => (w.fn, i) }.toIndexedSeq }

      val buf = new RowBuf(childSchema)
      var si = 0
      while (si < nSpecs) {
        val spec = specs(si)
        val fns = specFns(spec)
        processSpec(
          rows,
          partKeysByRow(si).toArray,
          orderKeysByRow(si).toArray,
          spec,
          fns,
          windowValues,
          buf)
        si += 1
      }
    }

    emit(rows, windowValues, childSchema, childWidth, nWindows)
  }

  /** Snapshot the per-row values from a batch's pre-computed key vectors. */
  private def readVecsAt(vecs: Array[ColumnVector], r: Int): Array[Any] = {
    val len = vecs.length
    val out = new Array[Any](len)
    var i = 0
    while (i < len) {
      out(i) = if (vecs(i).isNull(r)) null else vecs(i).getBoxed(r)
      i += 1
    }
    out
  }

  // ---------------------------------------------------------------------------

  private def processSpec(
      rows: mutable.ArrayBuffer[Array[Any]],
      partKeysByRow: Array[Array[Any]],
      orderKeysByRow: Array[Array[Any]],
      spec: WindowSpec,
      fns: Seq[(WindowFn, Int)],
      out: Array[Array[Any]],
      buf: RowBuf): Unit = {

    val n = rows.length

    // Partition rows by partition key. Keys are pre-encoded values from the
    // materialization pass; the codec only handles encoding into its hashmap
    // wrapper here. EmptyKeyCodec collapses the no-PARTITION-BY case.
    val partKeyTypes = spec.partitionKeys.iterator.map(_.dataType).toArray
    val partCodec: KeyCodec = KeyCodec.forColumns(
      new Array[Int](partKeyTypes.length),
      partKeyTypes)

    val partitions = mutable.LinkedHashMap.empty[AnyRef, mutable.ArrayBuffer[Int]]
    var i = 0
    while (i < n) {
      val key = partCodec.encodeBoxed(partKeysByRow(i))
      val bucket = partitions.getOrElseUpdate(key, mutable.ArrayBuffer.empty)
      bucket += i
      i += 1
    }

    if (metricsNode != null) {
      var peakRows: Long = 0L
      partitions.values.foreach { p =>
        val sz = p.length.toLong
        if (sz > peakRows) peakRows = sz
      }
      metricsNode.counters(WindowExec.IdxPartitionCount).add(partitions.size.toLong)
      metricsNode.counters(WindowExec.IdxPeakPartitionRows).add(peakRows)
    }

    val orderKeys = spec.orderKeys
    val tFrame = if (metricsNode != null) System.nanoTime() else 0L
    partitions.values.foreach { part =>
      if (orderKeys.nonEmpty) sortPartition(part, orderKeysByRow, orderKeys)
      computeFunctions(part, rows, orderKeysByRow, spec, fns, out, buf)
    }
    if (metricsNode != null) metricsNode.counters(WindowExec.IdxFrameEvalNanos).add(System.nanoTime() - tFrame)
  }

  private def sortPartition(
      part: mutable.ArrayBuffer[Int],
      orderKeysByRow: Array[Array[Any]],
      orderKeys: Seq[(Expr, Boolean)]): Unit = {
    val orderArr: Array[(Expr, Boolean)] = orderKeys.toArray
    val nKeys = orderArr.length
    val ord = new java.util.Comparator[Integer] {
      def compare(a: Integer, b: Integer): Int = {
        val ka = orderKeysByRow(a)
        val kb = orderKeysByRow(b)
        var k = 0
        while (k < nKeys) {
          val asc = orderArr(k)._2
          val va = ka(k)
          val vb = kb(k)
          val c = (va, vb) match {
            case (null, null) => 0
            case (null, _) => if (asc) -1 else 1
            case (_, null) => if (asc) 1 else -1
            case _ => if (asc) Ops.cmp(va, vb) else Ops.cmp(vb, va)
          }
          if (c != 0) return c
          k += 1
        }
        0
      }
    }
    val arr = part.toArray
    val boxed: Array[Integer] = arr.map(Integer.valueOf)
    java.util.Arrays.sort(boxed, ord)
    var i = 0
    while (i < boxed.length) { part(i) = boxed(i).intValue; i += 1 }
  }

  private def computeFunctions(
      part: mutable.ArrayBuffer[Int],
      rows: mutable.ArrayBuffer[Array[Any]],
      orderKeysByRow: Array[Array[Any]],
      spec: WindowSpec,
      fns: Seq[(WindowFn, Int)],
      out: Array[Array[Any]],
      buf: RowBuf): Unit = {
    val n = part.size
    fns.foreach { case (fn, outCol) =>
      fn match {
        case _: WindowFnRowNumber =>
          var r = 0
          while (r < n) { out(part(r))(outCol) = (r + 1).toLong; r += 1 }

        case _: WindowFnRank =>
          var r = 0
          var rank = 1L
          var prevKey: Array[Any] = null
          while (r < n) {
            val key = orderKeysByRow(part(r))
            if (prevKey != null && !sameKey(key, prevKey)) rank = (r + 1).toLong
            out(part(r))(outCol) = rank
            prevKey = key
            r += 1
          }

        case _: WindowFnDenseRank =>
          var r = 0
          var rank = 1L
          var prevKey: Array[Any] = null
          while (r < n) {
            val key = orderKeysByRow(part(r))
            if (prevKey != null && !sameKey(key, prevKey)) rank += 1
            out(part(r))(outCol) = rank
            prevKey = key
            r += 1
          }

        case lag: WindowFnLag =>
          computeLagLead(part, rows, lag.child, -lag.offset, lag.default, outCol, out, buf)

        case lead: WindowFnLead =>
          computeLagLead(part, rows, lead.child, lead.offset, lead.default, outCol, out, buf)

        case wa: WindowFnAgg =>
          computeAggOverPartition(part, rows, spec, wa.agg, outCol, out, buf)
      }
    }
  }

  private def computeLagLead(
      part: mutable.ArrayBuffer[Int],
      rows: mutable.ArrayBuffer[Array[Any]],
      arg: Expr,
      delta: Int,
      default: Option[Expr],
      outCol: Int,
      out: Array[Array[Any]],
      buf: RowBuf): Unit = {
    val n = part.size
    var r = 0
    while (r < n) {
      val target = r + delta
      val rowIdx = part(r)
      val v: Any =
        if (target >= 0 && target < n) {
          buf.set(rows(part(target)))
          arg.eval(buf.batch, 0)
        } else default match {
          case Some(d) =>
            buf.set(rows(rowIdx))
            d.eval(buf.batch, 0)
          case None => null
        }
      out(rowIdx)(outCol) = v
      r += 1
    }
  }

  private def computeAggOverPartition(
      part: mutable.ArrayBuffer[Int],
      rows: mutable.ArrayBuffer[Array[Any]],
      spec: WindowSpec,
      agg: AggExpr,
      outCol: Int,
      out: Array[Array[Any]],
      buf: RowBuf): Unit = {
    val n = part.size

    // Fast path: frame is the entire partition — one aggregation value broadcast
    // to every row in the partition. This also covers the no-ORDER-BY default.
    if (isWholePartitionFrame(spec.frame)) {
      val state = AggState.init(agg)
      var r = 0
      while (r < n) {
        buf.set(rows(part(r)))
        state.update(agg, buf.batch, 0)
        r += 1
      }
      val v = state.finish()
      r = 0
      while (r < n) { out(part(r))(outCol) = v; r += 1 }
      return
    }

    // Per-row frame: compute the [lo, hi] inclusive row range, aggregate over it.
    // Without an ORDER BY, the running-aggregate default still resolves to the
    // entire partition above. With ORDER BY, "RANGE BETWEEN UNBOUNDED PRECEDING
    // AND CURRENT ROW" (the SQL standard default) is approximated as ROWS — a
    // simplification documented in CLAUDE.md.
    var r = 0
    while (r < n) {
      val (lo, hi) = frameBoundsFor(r, n, spec.frame)
      val state = AggState.init(agg)
      var k = lo
      while (k <= hi) {
        if (k >= 0 && k < n) {
          buf.set(rows(part(k)))
          state.update(agg, buf.batch, 0)
        }
        k += 1
      }
      out(part(r))(outCol) = state.finish()
      r += 1
    }
  }

  private def isWholePartitionFrame(f: WindowFrame): Boolean =
    f.start == FrameBound.UnboundedPreceding && f.end == FrameBound.UnboundedFollowing

  /** Translate frame bounds to inclusive [lo, hi] row indices within a partition
    * of size `n`, given a current-row index `r`. Out-of-range indices are
    * clamped by the caller's `k >= 0 && k < n` check.
    */
  private def frameBoundsFor(r: Int, n: Int, frame: WindowFrame): (Int, Int) = {
    val lo: Int = frame.start match {
      case FrameBound.UnboundedPreceding => 0
      case FrameBound.CurrentRow => r
      case FrameBound.Preceding(k) => r - k.toInt
      case FrameBound.Following(k) => r + k.toInt
      case FrameBound.UnboundedFollowing => n  // empty frame
    }
    val hi: Int = frame.end match {
      case FrameBound.UnboundedPreceding => -1 // empty frame
      case FrameBound.CurrentRow => r
      case FrameBound.Preceding(k) => r - k.toInt
      case FrameBound.Following(k) => r + k.toInt
      case FrameBound.UnboundedFollowing => n - 1
    }
    (lo, hi)
  }

  private def sameKey(a: Array[Any], b: Array[Any]): Boolean = {
    if (a.length != b.length) return false
    var i = 0
    while (i < a.length) {
      val av = a(i); val bv = b(i)
      val same = (av == null && bv == null) || (av != null && bv != null && Ops.eq(av, bv))
      if (!same) return false
      i += 1
    }
    true
  }

  // ---------------------------------------------------------------------------

  private def emit(
      rows: mutable.ArrayBuffer[Array[Any]],
      windowValues: Array[Array[Any]],
      childSchema: Schema,
      childWidth: Int,
      nWindows: Int): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val totalRows = rows.length
    var produced = 0
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = produced < totalRows
      def next(): ColumnarBatch = {
        val take = math.min(capacity, totalRows - produced)
        val out = new ColumnarBatch(schema, math.max(1, take))
        var r = 0
        while (r < take) {
          val srcRow = rows(produced + r)
          val winRow = windowValues(produced + r)
          var c = 0
          while (c < childWidth) {
            val v = srcRow(c)
            if (v == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, v)
            c += 1
          }
          var w = 0
          while (w < nWindows) {
            val v = winRow(w)
            if (v == null) out.column(childWidth + w).setNull(r) else out.column(childWidth + w).setBoxed(r, v)
            w += 1
          }
          r += 1
        }
        out.setNumRows(take)
        produced += take
        out
      }
    }
  }
}

object WindowExec {
  // ---- Counter indices ------------------------------------------------------
  // In-memory path counters.
  final val IdxPartitionCount: Int       = 0
  final val IdxPeakPartitionRows: Int    = 1
  final val IdxFrameEvalNanos: Int       = 2
  // Spill (per-partition bucket spill) counters.
  final val IdxPartitionSpillEvents: Int = 3
  final val IdxBytesSpilled: Int         = 4
  final val IdxPeakBufferedRows: Int     = 5

  /** Name array parallel to the Idx* constants above. */
  val IdxCounterNames: Array[String] = Array(
    "partitionCount",
    "peakPartitionRows",
    "frameEvalNanos",
    "partitionSpillEvents",
    "bytesSpilled",
    "peakBufferedRows")
}
