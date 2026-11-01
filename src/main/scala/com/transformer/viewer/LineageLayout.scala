package com.transformer.viewer

import scala.collection.mutable

/** A rectangle in world coordinates. */
final case class Box(x: Double, y: Double, width: Double, height: Double) {
  def right: Double = x + width
  def bottom: Double = y + height
  def centerX: Double = x + width / 2.0
  def centerY: Double = y + height / 2.0
  def contains(px: Double, py: Double): Boolean = px >= x && px <= right && py >= y && py <= bottom
}

/** Where the lineage canvas draws each dataset and each node: of the whole
  * folder, or of the nodes in one table's lineage.
  *
  * A dataset is a box with a header. Inside it, its inputs stand in the first
  * column and its tables in columns after them, each table one column right
  * of the furthest table it reads, as in the job's own DAG. The boxes stand
  * in columns too: a dataset one column right of the furthest dataset whose
  * tables it reads, so data flows left to right across jobs as within one.
  * In a column, a box stands where its inputs are level with the tables they
  * read, as near as the boxes above it allow, so an edge between columns runs
  * nearly flat; nodes in a column of a box are ordered by where what they read
  * stands. Datasets that read each other in a cycle keep the order they were
  * found in.
  */
final class LineageLayout(
    val datasetBoxes: Map[Int, Box],
    val nodeBoxes: Map[NodeId, Box],
    val width: Double,
    val height: Double
) {
  /** The node at a world point, else the dataset whose box holds it. */
  def hitTest(wx: Double, wy: Double): Option[Either[Int, NodeId]] =
    nodeBoxes.collectFirst { case (n, b) if b.contains(wx, wy) => Right(n) }
      .orElse(datasetBoxes.collectFirst { case (d, b) if b.contains(wx, wy) => Left(d) })
}

object LineageLayout {
  val NodeWidth: Double = 200.0
  val NodeHeight: Double = 60.0
  /** Between a box's columns of nodes, and between its nodes in a column. */
  val ColumnGap: Double = 70.0
  val NodeGap: Double = 20.0
  /** A box's room for its name above its nodes, and its padding. */
  val HeaderHeight: Double = 52.0
  val BoxPadding: Double = 22.0
  /** Between columns of boxes, and between boxes in a column. */
  val BoxColumnGap: Double = 110.0
  val BoxGap: Double = 44.0
  val Margin: Double = 40.0
  /** The narrowest box, so a dataset with one table still has room for its
    * name. */
  val MinBoxWidth: Double = 260.0

  /** Lay out `only`'s nodes, and a box for each dataset they are in; every
    * node and every dataset when `only` is None. */
  def compute(lineage: Lineage, only: Option[Set[NodeId]] = None): LineageLayout = {
    def drawn(node: NodeId): Boolean = only.forall(_.contains(node))
    val datasets = lineage.shownRuns.indices.filter(d => only.forall(_.exists(_.dataset == d)))
    if (datasets.isEmpty) return new LineageLayout(Map.empty, Map.empty, 0.0, 0.0)
    val edges = lineage.edges.filter { case (a, b) => drawn(a) && drawn(b) }

    // Each dataset's nodes, column by column, and the size of its box.
    val inner = datasets.map(d => d -> innerColumns(lineage, d, drawn)).toMap
    val boxSizes = inner.map { case (d, cols) =>
      val tallest = cols.map(_.size).maxOption.getOrElse(0)
      val w = cols.size * NodeWidth + math.max(0, cols.size - 1) * ColumnGap + 2 * BoxPadding
      val h = HeaderHeight + tallest * NodeHeight + math.max(0, tallest - 1) * NodeGap + BoxPadding
      d -> (math.max(MinBoxWidth, w), math.max(HeaderHeight + BoxPadding, h))
    }

    // Where each node stands in its box, from the box's top left: each column
    // centred on the box's tallest.
    val offsets = mutable.LinkedHashMap.empty[NodeId, (Double, Double)]
    datasets.foreach { d =>
      val cols = inner(d)
      val tallest = cols.map(_.size).maxOption.getOrElse(0)
      val fullHeight = tallest * NodeHeight + math.max(0, tallest - 1) * NodeGap
      cols.iterator.zipWithIndex.foreach { case (col, c) =>
        val colHeight = col.size * NodeHeight + math.max(0, col.size - 1) * NodeGap
        val top = HeaderHeight + (fullHeight - colHeight) / 2.0
        col.iterator.zipWithIndex.foreach { case (node, i) =>
          offsets(node) = (BoxPadding + c * (NodeWidth + ColumnGap), top + i * (NodeHeight + NodeGap))
        }
      }
    }

    // Columns of boxes: one right of the furthest dataset a dataset reads.
    // `datasets(i)` is box i.
    val box = datasets.zipWithIndex.toMap
    val reads: Map[Int, Seq[Int]] = edges.collect {
      case (t: NodeId.Table, i: NodeId.Input) if t.dataset != i.dataset => (box(t.dataset), box(i.dataset))
    }.distinct.groupBy(_._2).map { case (b, es) => b -> es.map(_._1) }
    val column = longestPath(datasets.size, i => reads.getOrElse(i, Nil))
    val columns = (0 to column.max).map(c => datasets.indices.filter(column(_) == c))
    val y = new Array[Double](datasets.size)
    val x = new Array[Double](datasets.size)
    // The top a box would take for its inputs to stand level with the tables
    // they read in the columns before it; None for one that reads none.
    def wantedTop(i: Int): Option[Double] = barycenter(edges.collect {
      case (t: NodeId.Table, in: NodeId.Input)
          if in.dataset == datasets(i) && t.dataset != in.dataset && column(box(t.dataset)) < column(i) =>
        y(box(t.dataset)) + offsets(t)._2 - offsets(in)._2
    })
    var left = Margin
    var bottom = 0.0
    columns.foreach { members =>
      // Stacked in the order of where they would stand, else by name, each
      // as near that place as the boxes above it allow.
      val ordered = members.sortBy(i => (wantedTop(i).getOrElse(Double.MaxValue), i))
      var top = Margin
      ordered.foreach { i =>
        x(i) = left
        y(i) = math.max(top, wantedTop(i).getOrElse(top))
        top = y(i) + boxSizes(datasets(i))._2 + BoxGap
      }
      bottom = math.max(bottom, top - BoxGap)
      left += members.map(i => boxSizes(datasets(i))._1).maxOption.getOrElse(0.0) + BoxColumnGap
    }
    val datasetBoxes = datasets.indices.map { i =>
      val (w, h) = boxSizes(datasets(i))
      datasets(i) -> Box(x(i), y(i), w, h)
    }.toMap
    val nodeBoxes = offsets.map { case (node, (dx, dy)) =>
      val b = datasetBoxes(node.dataset)
      node -> Box(b.x + dx, b.y + dy, NodeWidth, NodeHeight)
    }
    val width = left - BoxColumnGap + Margin
    new LineageLayout(datasetBoxes, nodeBoxes.toMap, width, bottom + Margin)
  }

  /** A dataset's drawn nodes in columns: its inputs first, then its tables,
    * each one column right of the furthest table it reads. */
  private def innerColumns(lineage: Lineage, d: Int, drawn: NodeId => Boolean): IndexedSeq[IndexedSeq[NodeId]] = {
    val run = lineage.shownRuns(d)
    val inputs = run.inputs.map(i => NodeId.Input(d, i.name): NodeId).filter(drawn).toIndexedSeq
    val tables = run.tables.map(t => NodeId.Table(d, t.name): NodeId).filter(drawn)
    val tableIndex = tables.zipWithIndex.toMap
    val readsTables: Int => Seq[Int] = i =>
      lineage.upstream(tables(i)).flatMap(tableIndex.get).filter(_ != i)
    val depth = longestPath(tables.size, readsTables)
    val tableColumns = (0 to depth.maxOption.getOrElse(-1)).map(c => tables.indices.filter(depth(_) == c))
    val all = (if (inputs.nonEmpty) IndexedSeq(inputs) else IndexedSeq.empty) ++
      tableColumns.map(_.map(tables))
    // Order each column by where the nodes it reads stand in the columns
    // before it: one sweep, left to right.
    val position = mutable.Map.empty[NodeId, Double]
    all.map { col =>
      val ordered =
        if (position.isEmpty) col
        else col.zipWithIndex.sortBy { case (node, i) =>
          (barycenter(lineage.upstream(node).flatMap(position.get)).getOrElse(Double.MaxValue), i)
        }.map(_._1)
      ordered.zipWithIndex.foreach { case (node, i) => position(node) = i.toDouble }
      ordered
    }
  }

  /** For each of `n` items, the length of the longest chain of items it reads
    * (`reads(i)`), counting from 0; an item in a cycle counts the chain up to
    * where it closes. */
  private def longestPath(n: Int, reads: Int => Seq[Int]): IndexedSeq[Int] = {
    val depth = Array.fill(n)(-1)
    val onStack = Array.fill(n)(false)
    def visit(i: Int): Int = {
      if (depth(i) >= 0) return depth(i)
      onStack(i) = true
      val d = reads(i).filterNot(onStack).map(j => visit(j) + 1).maxOption.getOrElse(0)
      onStack(i) = false
      depth(i) = d
      d
    }
    (0 until n).map(visit)
  }

  private def barycenter(values: Seq[Double]): Option[Double] =
    if (values.isEmpty) None else Some(values.sum / values.size)
}
