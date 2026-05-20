package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import scala.collection.mutable

/** Window-function projection: materializes the child, groups rows by spec,
  * sorts within partition, and writes one output column per [[WindowDef]].
  *
  * Pipeline breaker (numPartitions=1). All child rows are buffered in memory;
  * this matches the codebase's v1 stance ("no spill-to-disk").
  *
  * Output schema is `child.outputSchema ++ windowOutputs` — original column
  * indices are preserved so projections bound against the child can still
  * reference them by index.
  *
  * Vectorization: during the single materialization pass we also eval each
  * [[WindowSpec]]'s partition keys and order keys once per scan batch via
  * [[Expr.evalVec]] and stash the per-row values alongside the boxed rows.
  * Downstream partitioning + sorting + rank tie-detection read from those
  * cached values instead of re-running per-row `Expr.eval` against a
  * [[RowBuf]]. LAG/LEAD args and aggregate args still go through the
  * per-row path — a follow-up.
  */
final case class WindowExec(child: PhysicalPlan, windows: Seq[WindowDef]) extends PhysicalPlan {

  val outputSchema: Schema = Schema(
    child.outputSchema.fields ++ windows.map(w => Field(w.outputName, w.fn.resultType))
  )

  def numPartitions: Int = 1

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val childSchema = child.outputSchema
    val childWidth = childSchema.length
    val nWindows = windows.size

    // Distinct specs in declaration order. One spec → one partitioning pass.
    val specs: Array[WindowSpec] = windows.iterator.map(_.spec).toArray.distinct
    val nSpecs = specs.length
    val specPartKeyExprs: Array[Array[Expr]] = specs.map(_.partitionKeys.toArray)
    val specOrderKeyExprs: Array[Array[Expr]] = specs.map(_.orderKeys.iterator.map(_._1).toArray)

    // Per-spec per-row key caches. partKeysByRow(si) and orderKeysByRow(si)
    // are parallel to `rows`. Each element is the boxed key values for one
    // row under that spec.
    val partKeysByRow: Array[mutable.ArrayBuffer[Array[Any]]] =
      Array.tabulate(nSpecs)(_ => mutable.ArrayBuffer.empty[Array[Any]])
    val orderKeysByRow: Array[mutable.ArrayBuffer[Array[Any]]] =
      Array.tabulate(nSpecs)(_ => mutable.ArrayBuffer.empty[Array[Any]])

    // 1. Materialize every child row. Same pass: for each batch, evalVec each
    // spec's partition keys + order keys once, then per-row stash the boxed
    // values. This eliminates the per-row Expr.eval against RowBuf the old
    // `processSpec` paid for non-ColRef keys.
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    val it = (0 until child.numPartitions).iterator.flatMap(child.execute)
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
      // 2. Group windows by spec; one partitioning + sort pass per spec.
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

    // 3. Emit batches.
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

    val orderKeys = spec.orderKeys
    partitions.values.foreach { part =>
      if (orderKeys.nonEmpty) sortPartition(part, orderKeysByRow, orderKeys)
      computeFunctions(part, rows, orderKeysByRow, spec, fns, out, buf)
    }
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
