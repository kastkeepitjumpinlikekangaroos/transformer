package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util
import java.util.concurrent.Callable
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Hash join.
  *
  * One side is the build side: materialized into row arrays plus a multi-map
  * from join key to row indices. The other side is the probe side: streamed.
  *
  * Equi-join only — the planner extracts equality conjuncts from the JOIN ON
  * condition (`a.x = b.y AND …`). Other predicates become `extra`, applied
  * after the per-key match. Joins with no equality conjuncts degenerate to a
  * cartesian product (every probe row matches the single empty-key bucket
  * holding every build row) — see [[PhysicalPlanner]]'s size guard for the
  * cutoff above which that's rejected.
  *
  * `buildRight` selects which side is built:
  *   - `true` (default): right is built, left is probed. Cheapest when right
  *     is smaller — the historic shape.
  *   - `false`: left is built, right is probed. The planner picks this when
  *     `LogicalPlanCardinality.estimate(left)` is materially below `estimate(right)`
  *     (so the smaller side ends up in the hash) or when the join kind is
  *     RIGHT outer (so the preserved side stays the streaming probe). Output
  *     schema and column order are unchanged — `left.outputSchema ++ right.outputSchema`
  *     either way — so downstream operators don't see the swap.
  */
final case class HashJoinExec(
    left: PhysicalPlan,
    right: PhysicalPlan,
    leftKeys: Seq[Expr],
    rightKeys: Seq[Expr],
    extra: Option[Expr],
    kind: JoinKind,
    buildRight: Boolean = true
) extends PhysicalPlan {

  val outputSchema: Schema = Schema(left.outputSchema.fields ++ right.outputSchema.fields)

  def numPartitions: Int = 1
  private val nLeftCols = left.outputSchema.length
  private val nRightCols = right.outputSchema.length
  private val nCols = nLeftCols + nRightCols

  private val probeIsLeft: Boolean = buildRight
  private val buildIsLeft: Boolean = !buildRight

  private val preserveBuildOuter: Boolean =
    if (buildIsLeft) kind == JoinKind.Left || kind == JoinKind.Full
    else kind == JoinKind.Right || kind == JoinKind.Full

  private val preserveProbeOuter: Boolean =
    if (probeIsLeft) kind == JoinKind.Left || kind == JoinKind.Full
    else kind == JoinKind.Right || kind == JoinKind.Full

  private val buildPlan: PhysicalPlan = if (buildRight) right else left
  private val probePlan: PhysicalPlan = if (buildRight) left else right
  private val buildKeys: Seq[Expr]    = if (buildRight) rightKeys else leftKeys
  private val probeKeys: Seq[Expr]    = if (buildRight) leftKeys else rightKeys

  // Key encoding. Both sides share one codec (its bit/byte layout depends only
  // on key TYPES, not column positions). The codec's bound indices are set up
  // for the probe side because that's the side that uses [[KeyCodec.encodeFromBatch]];
  // the build side reads from already-materialized `Array[Any]` rows and goes
  // through [[KeyCodec.encodeBoxed]] (indices unused).
  private val nKeys: Int = buildKeys.length
  private val keyTypes: Array[DataType] = buildKeys.iterator.map(_.dataType).toArray
  private val buildKeyExprs: Array[Expr] = buildKeys.toArray
  private val probeKeyExprs: Array[Expr] = probeKeys.toArray
  private val buildKeysAreColRefs: Boolean = buildKeyExprs.forall(_.isInstanceOf[ColRefExpr])
  private val probeKeysAreColRefs: Boolean = probeKeyExprs.forall(_.isInstanceOf[ColRefExpr])
  private val buildKeyColIndices: Array[Int] =
    if (buildKeysAreColRefs) buildKeyExprs.map(_.asInstanceOf[ColRefExpr].index) else null
  private val probeKeyColIndices: Array[Int] =
    if (probeKeysAreColRefs) probeKeyExprs.map(_.asInstanceOf[ColRefExpr].index) else null
  private val keyCodec: KeyCodec = {
    val indices =
      if (probeKeysAreColRefs) probeKeyColIndices
      else new Array[Int](nKeys)
    KeyCodec.forColumns(indices, keyTypes)
  }

  // Fast path: single fixed-width-numeric-fittable ColRef join key on BOTH
  // sides → swap out the AnyRef keymap for [[LongHashMap]]. Probe-side reads
  // the primitive long straight from the source `ColumnVector` (no boxing),
  // build-side unboxes once from the materialized `Array[Any]` row.
  private val useJoinLongKey: Boolean =
    nKeys == 1 &&
    buildKeysAreColRefs && probeKeysAreColRefs &&
    KeyCodec.isLongFittable(keyTypes(0))
  private val buildKeyLongIdx: Int =
    if (useJoinLongKey) buildKeyColIndices(0) else -1
  private val probeKeyLongIdx: Int =
    if (useJoinLongKey) probeKeyColIndices(0) else -1

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val build = buildSide()
    val matchedBuild: util.HashSet[Int] =
      if (preserveBuildOuter) new util.HashSet[Int]() else null

    val probeIter = probeIterator(build, matchedBuild)

    val unmatchedBuildTail: Iterator[ColumnarBatch] =
      if (matchedBuild != null) emitUnmatchedBuild(build, matchedBuild)
      else Iterator.empty

    probeIter ++ unmatchedBuildTail
  }

  /** Materialize the build side: list of full-row arrays plus a multi-map
    * from key. For non-ColRef build keys we evaluate each key expression
    * once per scan batch via `evalVec` (instead of per row through `RowBuf`)
    * and stash the encoded key alongside the materialized row. */
  private def buildSide(): BuildSide = {
    val tasks: Seq[Callable[CollectedBuildPartition]] =
      (0 until buildPlan.numPartitions).map { p =>
        new Callable[CollectedBuildPartition] {
          def call(): CollectedBuildPartition = collectBuildPartition(p)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    val keys: mutable.ArrayBuffer[AnyRef] =
      if (buildKeysAreColRefs || useJoinLongKey) null
      else mutable.ArrayBuffer.empty[AnyRef]
    partials.foreach { part =>
      rows ++= part.rows
      if (keys != null) keys ++= part.keys
    }
    if (useJoinLongKey) buildLongKeyMap(rows)
    else buildCodecKeyMap(rows, keys)
  }

  private def buildLongKeyMap(rows: mutable.ArrayBuffer[Array[Any]]): BuildSide = {
    val keyMap = new LongHashMap[util.ArrayList[Int]]()
    val keyType = keyTypes(0)
    var i = 0
    while (i < rows.length) {
      val v = rows(i)(buildKeyLongIdx)
      if (v != null) {
        // NULL build keys can never match (equi-join, 3VL) — drop them from
        // the keymap; the row stays in `rows` so outer-join "unmatched build"
        // emission still surfaces it.
        val k = KeyCodec.boxedToLong(v, keyType)
        val list = keyMap.getOrInsert(k, new util.ArrayList[Int]())
        list.add(i)
      }
      i += 1
    }
    BuildSide(rows, null, keyMap)
  }

  private def buildCodecKeyMap(
      rows: mutable.ArrayBuffer[Array[Any]],
      precomputedKeys: mutable.ArrayBuffer[AnyRef]): BuildSide = {
    val keyMap = new util.HashMap[AnyRef, util.ArrayList[Int]]()
    if (buildKeysAreColRefs) {
      // ColRef path: keys derive directly from the materialized row array.
      // No Expr.eval involved here even before vectorization — keep it.
      val keyBuf = new Array[Any](nKeys)
      var i = 0
      while (i < rows.length) {
        val row = rows(i)
        var k = 0
        while (k < nKeys) { keyBuf(k) = row(buildKeyColIndices(k)); k += 1 }
        val key = keyCodec.encodeBoxed(keyBuf)
        val list = keyMap.computeIfAbsent(key, _ => new util.ArrayList[Int]())
        list.add(i)
        i += 1
      }
    } else {
      // Computed-key path: keys were already encoded once per source batch
      // by `collectBuildPartition` using `evalVec`. Walk the parallel array
      // and slot each one into the keymap.
      var i = 0
      while (i < rows.length) {
        val key = precomputedKeys(i)
        val list = keyMap.computeIfAbsent(key, _ => new util.ArrayList[Int]())
        list.add(i)
        i += 1
      }
    }
    BuildSide(rows, keyMap, null)
  }

  /** Materialize one build-side partition. Also pre-encodes the key for each
    * row when build keys are computed (non-ColRef), eliminating the per-row
    * `RowBuf`-driven `Expr.eval` that `buildCodecKeyMap` used to do over the
    * already-materialized boxed row array. */
  private def collectBuildPartition(p: Int): CollectedBuildPartition = {
    val rowsBuf = mutable.ArrayBuffer.empty[Array[Any]]
    val keysBuf: mutable.ArrayBuffer[AnyRef] =
      if (buildKeysAreColRefs || useJoinLongKey) null
      else mutable.ArrayBuffer.empty[AnyRef]
    val keyBufRow: Array[Any] = if (keysBuf != null) new Array[Any](nKeys) else null
    val it = buildPlan.execute(p)
    val nCols = buildPlan.outputSchema.length
    while (it.hasNext) {
      val b = it.next()
      val nrows = b.numRows
      val keyVecs: Array[ColumnVector] =
        if (keysBuf != null) Array.tabulate(nKeys)(i => buildKeyExprs(i).evalVec(b))
        else null
      var r = 0
      while (r < nrows) {
        val arr = new Array[Any](nCols)
        var c = 0
        while (c < nCols) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        rowsBuf += arr
        if (keysBuf != null) {
          var k = 0
          while (k < nKeys) {
            val kv = keyVecs(k)
            keyBufRow(k) = if (kv.isNull(r)) null else kv.getBoxed(r)
            k += 1
          }
          keysBuf += keyCodec.encodeBoxed(keyBufRow)
        }
        r += 1
      }
    }
    CollectedBuildPartition(rowsBuf, keysBuf)
  }

  private def probeIterator(build: BuildSide, matchedBuild: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema

    val joinedRows = mutable.ArrayBuffer.empty[Array[Any]]
    val matchedSet = if (matchedBuild != null) matchedBuild else new util.HashSet[Int]()
    val probeSchema = probePlan.outputSchema

    val tasks: Seq[Callable[(mutable.ArrayBuffer[Array[Any]], util.HashSet[Int])]] =
      (0 until probePlan.numPartitions).map { pp =>
        new Callable[(mutable.ArrayBuffer[Array[Any]], util.HashSet[Int])] {
          def call(): (mutable.ArrayBuffer[Array[Any]], util.HashSet[Int]) = {
            val local = mutable.ArrayBuffer.empty[Array[Any]]
            val localMatched = new util.HashSet[Int]()
            val probeIt = probePlan.execute(pp)
            val keyBuf: Array[Any] = if (probeKeysAreColRefs) null else new Array[Any](nKeys)
            while (probeIt.hasNext) {
              val b = probeIt.next()
              val nrows = b.numRows
              // For non-ColRef probe keys, hoist Expr.eval out of the per-row
              // loop. ColRef keys go through `encodeFromBatchSkipIfAnyNull`
              // which already reads typed primitives directly; the long-key
              // fast path reads its single primitive even more directly.
              val probeKeyVecs: Array[ColumnVector] =
                if (probeKeysAreColRefs || useJoinLongKey) null
                else Array.tabulate(nKeys)(i => probeKeyExprs(i).evalVec(b))
              var r = 0
              while (r < nrows) {
                val probeRow = new Array[Any](probeSchema.length)
                var c = 0
                while (c < probeSchema.length) {
                  probeRow(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
                  c += 1
                }
                val matches: util.ArrayList[Int] =
                  if (useJoinLongKey) {
                    val col = b.column(probeKeyLongIdx)
                    if (col.isNull(r)) null
                    else build.keyMapLong.get(KeyCodec.readAsLong(col, r))
                  } else {
                    val key: AnyRef =
                      if (probeKeysAreColRefs) keyCodec.encodeFromBatchSkipIfAnyNull(b, r)
                      else {
                        var k = 0
                        var anyNull = false
                        while (k < nKeys) {
                          val kv = probeKeyVecs(k)
                          val v: Any = if (kv.isNull(r)) null else kv.getBoxed(r)
                          if (v == null) anyNull = true
                          keyBuf(k) = v
                          k += 1
                        }
                        if (anyNull) null else keyCodec.encodeBoxed(keyBuf)
                      }
                    if (key == null) null else build.keyMap.get(key)
                  }
                var matchedAny = false
                if (matches != null && !matches.isEmpty) {
                  val it = matches.iterator()
                  while (it.hasNext) {
                    val buildIdx = it.next()
                    val combined = mergeMatch(probeRow, build.rows(buildIdx))
                    if (passesExtra(combined)) {
                      local += combined
                      matchedAny = true
                      localMatched.add(buildIdx)
                    }
                  }
                }
                if (!matchedAny && preserveProbeOuter) {
                  local += mergeUnmatchedProbe(probeRow)
                }
                r += 1
              }
            }
            (local, localMatched)
          }
        }
      }
    Scheduler.submitAndAwaitAll(tasks).foreach { case (rows, m) =>
      joinedRows ++= rows
      matchedSet.addAll(m)
    }

    if (matchedBuild != null) {
      matchedSet.iterator().asScala.foreach(matchedBuild.add)
    }

    rowsToBatches(joinedRows.toArray, schema, capacity)
  }

  private def emitUnmatchedBuild(build: BuildSide, matched: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val unmatched = mutable.ArrayBuffer.empty[Array[Any]]
    var i = 0
    while (i < build.rows.length) {
      if (!matched.contains(i)) unmatched += mergeUnmatchedBuild(build.rows(i))
      i += 1
    }
    rowsToBatches(unmatched.toArray, schema, capacity)
  }

  private def rowsToBatches(rows: Array[Array[Any]], schema: Schema, capacity: Int): Iterator[ColumnarBatch] = {
    var pos = 0
    new Iterator[ColumnarBatch] {
      def hasNext: Boolean = pos < rows.length
      def next(): ColumnarBatch = {
        val take = math.min(capacity, rows.length - pos)
        val out = new ColumnarBatch(schema, take max 1)
        var r = 0
        while (r < take) {
          val row = rows(pos + r)
          var c = 0
          while (c < schema.length) {
            if (row(c) == null) out.column(c).setNull(r) else out.column(c).setBoxed(r, row(c))
            c += 1
          }
          r += 1
        }
        out.setNumRows(take)
        pos += take
        out
      }
    }
  }

  private def mergeMatch(probeRow: Array[Any], buildRow: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    if (probeIsLeft) {
      System.arraycopy(probeRow, 0, out, 0, nLeftCols)
      System.arraycopy(buildRow, 0, out, nLeftCols, nRightCols)
    } else {
      System.arraycopy(buildRow, 0, out, 0, nLeftCols)
      System.arraycopy(probeRow, 0, out, nLeftCols, nRightCols)
    }
    out
  }

  private def mergeUnmatchedProbe(probeRow: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    if (probeIsLeft) System.arraycopy(probeRow, 0, out, 0, nLeftCols)
    else System.arraycopy(probeRow, 0, out, nLeftCols, nRightCols)
    out
  }

  private def mergeUnmatchedBuild(buildRow: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    if (buildIsLeft) System.arraycopy(buildRow, 0, out, 0, nLeftCols)
    else System.arraycopy(buildRow, 0, out, nLeftCols, nRightCols)
    out
  }

  private def passesExtra(row: Array[Any]): Boolean = extra match {
    case None => true
    case Some(e) =>
      val view = RowView(outputSchema, row)
      val v = e.eval(view, 0)
      v != null && v.asInstanceOf[Boolean]
  }
}

/** Materialized build side. Exactly one of `keyMap` / `keyMapLong` is non-null
  * — `keyMapLong` when the equi-join key is a single fixed-width-numeric ColRef
  * on both sides, `keyMap` otherwise. */
private[exec] final case class BuildSide(
    rows: mutable.ArrayBuffer[Array[Any]],
    keyMap: util.HashMap[AnyRef, util.ArrayList[Int]],
    keyMapLong: LongHashMap[util.ArrayList[Int]])

/** One partition's worth of materialized build rows plus the pre-encoded
  * keys for those rows (when build keys are computed expressions — `keys` is
  * null for the ColRef and single-long fast paths). */
private[exec] final case class CollectedBuildPartition(
    rows: mutable.ArrayBuffer[Array[Any]],
    keys: mutable.ArrayBuffer[AnyRef])
