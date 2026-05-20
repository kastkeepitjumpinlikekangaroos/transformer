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

  /** Materialize the build side: list of full-row arrays plus a multi-map from key. */
  private def buildSide(): BuildSide = {
    val tasks: Seq[Callable[mutable.ArrayBuffer[Array[Any]]]] =
      (0 until buildPlan.numPartitions).map { p =>
        new Callable[mutable.ArrayBuffer[Array[Any]]] {
          def call(): mutable.ArrayBuffer[Array[Any]] = collectPartition(buildPlan, p)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    partials.foreach(rows ++= _)
    val keyMap = new util.HashMap[Seq[Any], util.ArrayList[Int]]()
    val buildSchema = buildPlan.outputSchema
    val rb = new RowBuf(buildSchema)
    var i = 0
    while (i < rows.length) {
      rb.set(rows(i))
      val key: Seq[Any] = buildKeys.map(_.eval(rb.batch, 0))
      val list = keyMap.computeIfAbsent(key, _ => new util.ArrayList[Int]())
      list.add(i)
      i += 1
    }
    BuildSide(rows, keyMap)
  }

  private def collectPartition(plan: PhysicalPlan, p: Int): mutable.ArrayBuffer[Array[Any]] = {
    val buf = mutable.ArrayBuffer.empty[Array[Any]]
    val it = plan.execute(p)
    val n = plan.outputSchema.length
    while (it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows) {
        val arr = new Array[Any](n)
        var c = 0
        while (c < n) {
          arr(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
          c += 1
        }
        buf += arr
        r += 1
      }
    }
    buf
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
            val pb = new RowBuf(probeSchema)
            while (probeIt.hasNext) {
              val b = probeIt.next()
              var r = 0
              while (r < b.numRows) {
                pb.setFromBatch(b, r)
                val probeRow = new Array[Any](probeSchema.length)
                var c = 0
                while (c < probeSchema.length) {
                  probeRow(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
                  c += 1
                }
                val key: Seq[Any] = probeKeys.map(_.eval(pb.batch, 0))
                val matches = if (key.exists(_ == null)) null else build.keyMap.get(key)
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

private[exec] final case class BuildSide(rows: mutable.ArrayBuffer[Array[Any]], keyMap: util.HashMap[Seq[Any], util.ArrayList[Int]])
