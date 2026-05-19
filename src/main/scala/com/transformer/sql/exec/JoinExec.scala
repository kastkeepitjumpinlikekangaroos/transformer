package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util
import java.util.concurrent.Callable
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Hash join. Right side is the build side: materialized into a multi-map from
  * the join key to row arrays. Left side is the probe side: streamed.
  *
  * v1: equi-join only. The planner extracts equality conjuncts from the JOIN ON
  * condition (`a.x = b.y AND ...`). Non-equi-join predicates fall back to NestedLoop.
  */
final case class HashJoinExec(
    left: PhysicalPlan,
    right: PhysicalPlan,
    leftKeys: Seq[Expr],
    rightKeys: Seq[Expr],
    extra: Option[Expr],
    kind: JoinKind
) extends PhysicalPlan {

  val outputSchema: Schema = Schema(left.outputSchema.fields ++ right.outputSchema.fields)

  def numPartitions: Int = 1
  private val nLeftCols = left.outputSchema.length
  private val nRightCols = right.outputSchema.length
  private val nCols = nLeftCols + nRightCols

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    require(partition == 0)
    val build = buildSide()
    val matchedRight: util.HashSet[Int] =
      if (kind == JoinKind.Right || kind == JoinKind.Full) new util.HashSet[Int]()
      else null

    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val probeIter = probeIterator(build, matchedRight)

    val unmatchedRightTail: Iterator[ColumnarBatch] =
      if (matchedRight != null) emitUnmatchedRight(build, matchedRight)
      else Iterator.empty

    probeIter ++ unmatchedRightTail
  }

  /** Materialize the right side: list of full-row arrays plus a multi-map from key. */
  private def buildSide(): BuildSide = {
    val tasks: Seq[Callable[mutable.ArrayBuffer[Array[Any]]]] =
      (0 until right.numPartitions).map { p =>
        new Callable[mutable.ArrayBuffer[Array[Any]]] {
          def call(): mutable.ArrayBuffer[Array[Any]] = collectPartition(right, p)
        }
      }
    val partials = Scheduler.submitAndAwaitAll(tasks)
    val rows = mutable.ArrayBuffer.empty[Array[Any]]
    partials.foreach(rows ++= _)
    val keyMap = new util.HashMap[Seq[Any], util.ArrayList[Int]]()
    val rightSchema = right.outputSchema
    val rb = new RowBuf(rightSchema)
    var i = 0
    while (i < rows.length) {
      rb.set(rows(i))
      val key: Seq[Any] = rightKeys.map(_.eval(rb.batch, 0))
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

  private def probeIterator(build: BuildSide, matchedRight: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema

    val joinedRows = mutable.ArrayBuffer.empty[Array[Any]]
    val matchedSet = if (matchedRight != null) matchedRight else new util.HashSet[Int]()

    val tasks: Seq[Callable[(mutable.ArrayBuffer[Array[Any]], util.HashSet[Int])]] =
      (0 until left.numPartitions).map { lp =>
        new Callable[(mutable.ArrayBuffer[Array[Any]], util.HashSet[Int])] {
          def call(): (mutable.ArrayBuffer[Array[Any]], util.HashSet[Int]) = {
            val local = mutable.ArrayBuffer.empty[Array[Any]]
            val localMatched = new util.HashSet[Int]()
            val leftIt = left.execute(lp)
            val leftSchema = left.outputSchema
            val lb = new RowBuf(leftSchema)
            while (leftIt.hasNext) {
              val b = leftIt.next()
              var r = 0
              while (r < b.numRows) {
                lb.setFromBatch(b, r)
                val leftRow = new Array[Any](leftSchema.length)
                var c = 0
                while (c < leftSchema.length) {
                  leftRow(c) = if (b.column(c).isNull(r)) null else b.column(c).getBoxed(r)
                  c += 1
                }
                val key: Seq[Any] = leftKeys.map(_.eval(lb.batch, 0))
                val matches = if (key.exists(_ == null)) null else build.keyMap.get(key)
                var matchedAny = false
                if (matches != null && !matches.isEmpty) {
                  val it = matches.iterator()
                  while (it.hasNext) {
                    val rightIdx = it.next()
                    val combined = mergeRow(leftRow, build.rows(rightIdx))
                    if (passesExtra(combined)) {
                      local += combined
                      matchedAny = true
                      localMatched.add(rightIdx)
                    }
                  }
                }
                if (!matchedAny && (kind == JoinKind.Left || kind == JoinKind.Full)) {
                  local += mergeRow(leftRow, new Array[Any](nRightCols))
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

    if (matchedRight != null) {
      matchedSet.iterator().asScala.foreach(matchedRight.add)
    }

    rowsToBatches(joinedRows.toArray, schema, capacity)
  }

  private def emitUnmatchedRight(build: BuildSide, matched: util.HashSet[Int]): Iterator[ColumnarBatch] = {
    val capacity = ColumnarBatch.DefaultCapacity
    val schema = outputSchema
    val unmatched = mutable.ArrayBuffer.empty[Array[Any]]
    var i = 0
    while (i < build.rows.length) {
      if (!matched.contains(i)) {
        val nullsLeft = new Array[Any](nLeftCols)
        unmatched += mergeRow(nullsLeft, build.rows(i))
      }
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

  private def mergeRow(l: Array[Any], r: Array[Any]): Array[Any] = {
    val out = new Array[Any](nCols)
    System.arraycopy(l, 0, out, 0, nLeftCols)
    System.arraycopy(r, 0, out, nLeftCols, nRightCols)
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
