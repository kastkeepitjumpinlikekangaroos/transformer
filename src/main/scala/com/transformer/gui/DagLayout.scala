package com.transformer.gui

import com.transformer.job.TaskDag

/** Position of one node in the rendered DAG. Coordinates are in *world* (un-zoomed,
  * un-panned) space.
  *
  * `x`/`y` mark the node's top-left corner; `width`/`height` are the rendered size.
  */
final case class NodeBox(
    index: Int,
    x: Double,
    y: Double,
    width: Double,
    height: Double
) {
  def centerX: Double = x + width / 2.0
  def centerY: Double = y + height / 2.0
  def right: Double = x + width
  def bottom: Double = y + height
  def contains(px: Double, py: Double): Boolean =
    px >= x && px <= right && py >= y && py <= bottom
}

/** A pre-computed 2D placement of a [[TaskDag]] for rendering.
  *
  * Layout strategy: each node is assigned a *layer* equal to the longest path
  * from any root to it (so direct roots are layer 0, their dependents are layer 1,
  * a node downstream of both a layer-1 and a layer-2 node lands in layer 3, etc).
  * Layers are laid out left-to-right; within a layer, nodes are ordered by their
  * declared index so the layout is stable and predictable.
  *
  * No crossing-minimization or fancy routing — for the DAG sizes we expect (tens
  * of tasks, not hundreds), simple-and-readable beats clever-and-tangled.
  */
final class DagLayout(
    val nodes: IndexedSeq[NodeBox],
    val width: Double,
    val height: Double
) {

  /** Find the node whose box contains the given world-space point. Returns the
    * topmost match (later-drawn nodes win); since nodes never overlap here, this
    * is unique when it exists.
    */
  def hitTest(worldX: Double, worldY: Double): Option[NodeBox] =
    nodes.find(_.contains(worldX, worldY))

  def boxFor(taskIndex: Int): Option[NodeBox] = nodes.lift(taskIndex)
}

object DagLayout {

  /** Default node size (in world-space pixels). The canvas may zoom this up/down. */
  val NodeWidth: Double = 200.0
  val NodeHeight: Double = 60.0
  val LayerSpacing: Double = 80.0
  val NodeSpacing: Double = 24.0
  val Padding: Double = 40.0

  def compute(dag: TaskDag): DagLayout = {
    val n = dag.nodes.size
    if (n == 0) return new DagLayout(IndexedSeq.empty, 0.0, 0.0)

    val layer = Array.fill(n)(-1)
    // Resolve layers in topological order. dag.nodes is already in declared
    // order, but declared order is NOT necessarily topological — a task can
    // reference a later-declared task only if validations support it. To be
    // safe we iterate Kahn-style.
    val inDeg = Array.tabulate(n)(i => dag.nodes(i).deps.size)
    val ready = scala.collection.mutable.Queue.empty[Int]
    var i = 0
    while (i < n) { if (inDeg(i) == 0) { layer(i) = 0; ready.enqueue(i) }; i += 1 }
    while (ready.nonEmpty) {
      val u = ready.dequeue()
      dag.dependents(u).foreach { v =>
        inDeg(v) -= 1
        val candidate = layer(u) + 1
        if (candidate > layer(v)) layer(v) = candidate
        if (inDeg(v) == 0) ready.enqueue(v)
      }
    }
    // Any node still at -1 belongs to a cycle — TaskDag.build would have thrown
    // before this, but be defensive.
    i = 0
    while (i < n) { if (layer(i) < 0) layer(i) = 0; i += 1 }

    // Group indices by layer, sorted by declared index within each layer.
    val maxLayer = layer.max
    val byLayer: Array[Vector[Int]] =
      Array.tabulate(maxLayer + 1) { lyr =>
        (0 until n).filter(layer(_) == lyr).toVector
      }

    val rowsInTallestLayer = byLayer.iterator.map(_.size).maxOption.getOrElse(0)

    val boxes = new Array[NodeBox](n)
    var lyr = 0
    while (lyr <= maxLayer) {
      val nodesInLayer = byLayer(lyr)
      val x = Padding + lyr * (NodeWidth + LayerSpacing)
      // Center each layer vertically against the tallest layer for a tidy diagram.
      val totalLayerHeight = nodesInLayer.size * NodeHeight +
        math.max(0, nodesInLayer.size - 1) * NodeSpacing
      val targetHeight = rowsInTallestLayer * NodeHeight +
        math.max(0, rowsInTallestLayer - 1) * NodeSpacing
      val yStart = Padding + (targetHeight - totalLayerHeight) / 2.0
      var k = 0
      while (k < nodesInLayer.size) {
        val idx = nodesInLayer(k)
        val y = yStart + k * (NodeHeight + NodeSpacing)
        boxes(idx) = NodeBox(idx, x, y, NodeWidth, NodeHeight)
        k += 1
      }
      lyr += 1
    }

    val totalW = Padding * 2 + (maxLayer + 1) * NodeWidth + maxLayer * LayerSpacing
    val totalH = Padding * 2 + rowsInTallestLayer * NodeHeight +
      math.max(0, rowsInTallestLayer - 1) * NodeSpacing

    new DagLayout(boxes.toIndexedSeq, totalW, totalH)
  }
}
