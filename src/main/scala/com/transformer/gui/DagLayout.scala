package com.transformer.gui

import com.transformer.job.{InputFilePath, TaskDag}

/** Whether a [[NodeBox]] represents an input view or a SQLTask. */
sealed trait NodeKind
object NodeKind {
  case object Input extends NodeKind
  case object Task extends NodeKind
}

/** Position of one node in the rendered DAG. Coordinates are in *world* (un-zoomed,
  * un-panned) space.
  *
  * `kind` tags the node as either an input or a task; `index` then indexes into the
  * appropriate vector (inputs or [[TaskDag.nodes]]).
  *
  * `x`/`y` mark the node's top-left corner; `width`/`height` are the rendered size.
  */
final case class NodeBox(
    kind: NodeKind,
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

/** A pre-computed 2D placement of a [[TaskDag]] plus its input views for rendering.
  *
  * Layout strategy: inputs occupy layer 0. Tasks are layered left-to-right by the
  * longest path from any source (input or root task) to them: a task that reads
  * directly from an input or has no upstream lands at layer 1, a task that reads
  * a layer-1 task lands at layer 2, etc. Within a layer, nodes are ordered by
  * their declared index so the layout is stable.
  *
  * No crossing-minimization or fancy routing — for the DAG sizes we expect (tens
  * of tasks, not hundreds), simple-and-readable beats clever-and-tangled.
  */
final class DagLayout(
    val taskBoxes: IndexedSeq[NodeBox],
    val inputBoxes: IndexedSeq[NodeBox],
    inputNameToIndex: Map[String, Int],
    val width: Double,
    val height: Double
) {

  /** Flat view of every box (inputs first, then tasks). Used by callers that
    * iterate the whole graph (rendering, hit-testing).
    */
  val nodes: IndexedSeq[NodeBox] = (inputBoxes ++ taskBoxes).toIndexedSeq

  /** Find the node whose box contains the given world-space point. Returns the
    * topmost match (later-drawn nodes win); since nodes never overlap here, this
    * is unique when it exists.
    */
  def hitTest(worldX: Double, worldY: Double): Option[NodeBox] =
    nodes.find(_.contains(worldX, worldY))

  def boxForTask(taskIndex: Int): Option[NodeBox] = taskBoxes.lift(taskIndex)
  def boxForInput(inputIndex: Int): Option[NodeBox] = inputBoxes.lift(inputIndex)

  /** Resolve a (lowercased) input viewName to its box, if any. Used by the
    * canvas to draw edges from inputs to tasks given a task's [[TaskDagNode.inputDeps]].
    */
  def boxForInputName(viewNameLower: String): Option[NodeBox] =
    inputNameToIndex.get(viewNameLower).flatMap(inputBoxes.lift)
}

object DagLayout {

  /** Default node size (in world-space pixels). The canvas may zoom this up/down. */
  val NodeWidth: Double = 200.0
  val NodeHeight: Double = 60.0
  val LayerSpacing: Double = 80.0
  val NodeSpacing: Double = 24.0
  val Padding: Double = 40.0

  /** Lay out a TaskDag with no input nodes (legacy convenience). */
  def compute(dag: TaskDag): DagLayout = compute(dag, IndexedSeq.empty)

  /** Lay out a TaskDag alongside its declared inputs.
    *
    * Inputs are matched to task `inputDeps` by lowercased viewName. Inputs not
    * referenced by any task are still drawn so the user sees the full pipeline.
    */
  def compute(dag: TaskDag, inputs: IndexedSeq[InputFilePath]): DagLayout = {
    val taskCount = dag.nodes.size
    val inputCount = inputs.size
    val inputNameToIdx: Map[String, Int] =
      inputs.iterator.zipWithIndex.map { case (in, i) => in.viewName.toLowerCase -> i }.toMap

    if (taskCount == 0 && inputCount == 0) {
      return new DagLayout(IndexedSeq.empty, IndexedSeq.empty, inputNameToIdx, 0.0, 0.0)
    }

    // Task layer = 1 + max(layer of every upstream) where upstreams are either
    // other tasks or inputs (inputs sit at layer 0). A task whose only upstreams
    // are inputs lands at layer 1. A task with neither input deps nor task deps
    // still lands at layer 1 alongside the input-rooted tasks so it doesn't
    // mingle with input nodes.
    val firstTaskLayer: Int = if (inputCount > 0) 1 else 0
    val taskLayer = Array.fill(taskCount)(-1)
    val inDeg = Array.tabulate(taskCount)(i => dag.nodes(i).deps.size)
    val ready = scala.collection.mutable.Queue.empty[Int]
    var i = 0
    while (i < taskCount) {
      if (inDeg(i) == 0) { taskLayer(i) = firstTaskLayer; ready.enqueue(i) }
      i += 1
    }
    while (ready.nonEmpty) {
      val u = ready.dequeue()
      dag.dependents(u).foreach { v =>
        inDeg(v) -= 1
        val candidate = taskLayer(u) + 1
        if (candidate > taskLayer(v)) taskLayer(v) = candidate
        if (inDeg(v) == 0) ready.enqueue(v)
      }
    }
    // Any task left at -1 belongs to a cycle — TaskDag.build would have thrown
    // before this, but be defensive.
    i = 0
    while (i < taskCount) { if (taskLayer(i) < 0) taskLayer(i) = firstTaskLayer; i += 1 }

    val maxTaskLayer = if (taskCount == 0) -1 else taskLayer.max
    val maxLayer = math.max(if (inputCount > 0) 0 else -1, maxTaskLayer)

    // Collect indices per layer. Inputs all go to layer 0 (declared order).
    val inputsByLayer: Array[Vector[Int]] =
      if (inputCount == 0) Array.empty
      else Array((0 until inputCount).toVector)
    val tasksByLayer: Array[Vector[Int]] =
      if (taskCount == 0) Array.empty
      else Array.tabulate(maxTaskLayer + 1) { lyr =>
        (0 until taskCount).filter(taskLayer(_) == lyr).toVector
      }

    def nodesAt(layer: Int): Int =
      (if (inputCount > 0 && layer == 0) inputCount else 0) +
        (if (taskCount > 0 && layer >= 0 && layer < tasksByLayer.length) tasksByLayer(layer).size else 0)

    val rowsInTallestLayer =
      (0 to math.max(0, maxLayer)).iterator.map(nodesAt).maxOption.getOrElse(0)
    val targetHeight = rowsInTallestLayer * NodeHeight +
      math.max(0, rowsInTallestLayer - 1) * NodeSpacing

    val inputBoxes = new Array[NodeBox](inputCount)
    val taskBoxes = new Array[NodeBox](taskCount)
    var lyr = 0
    while (lyr <= maxLayer) {
      val inputsInLayer = if (lyr == 0 && inputCount > 0) inputsByLayer(0) else Vector.empty[Int]
      val tasksInLayer =
        if (taskCount > 0 && lyr >= 0 && lyr < tasksByLayer.length) tasksByLayer(lyr) else Vector.empty[Int]
      val total = inputsInLayer.size + tasksInLayer.size
      if (total > 0) {
        val x = Padding + lyr * (NodeWidth + LayerSpacing)
        val totalLayerHeight = total * NodeHeight + math.max(0, total - 1) * NodeSpacing
        val yStart = Padding + (targetHeight - totalLayerHeight) / 2.0
        var slot = 0
        // Inputs first (top of the layer), then tasks.
        val inputIt = inputsInLayer.iterator
        while (inputIt.hasNext) {
          val idx = inputIt.next()
          val y = yStart + slot * (NodeHeight + NodeSpacing)
          inputBoxes(idx) = NodeBox(NodeKind.Input, idx, x, y, NodeWidth, NodeHeight)
          slot += 1
        }
        val taskIt = tasksInLayer.iterator
        while (taskIt.hasNext) {
          val idx = taskIt.next()
          val y = yStart + slot * (NodeHeight + NodeSpacing)
          taskBoxes(idx) = NodeBox(NodeKind.Task, idx, x, y, NodeWidth, NodeHeight)
          slot += 1
        }
      }
      lyr += 1
    }

    // Fallback: any task we somehow missed (shouldn't happen) gets a default box
    // at layer firstTaskLayer so we never produce nulls.
    i = 0
    while (i < taskCount) {
      if (taskBoxes(i) == null) {
        val x = Padding + firstTaskLayer * (NodeWidth + LayerSpacing)
        taskBoxes(i) = NodeBox(NodeKind.Task, i, x, Padding, NodeWidth, NodeHeight)
      }
      i += 1
    }
    i = 0
    while (i < inputCount) {
      if (inputBoxes(i) == null) {
        inputBoxes(i) = NodeBox(NodeKind.Input, i, Padding, Padding, NodeWidth, NodeHeight)
      }
      i += 1
    }
    val layerCount = math.max(0, maxLayer + 1)
    val totalW =
      if (layerCount == 0) 0.0
      else Padding * 2 + layerCount * NodeWidth + math.max(0, layerCount - 1) * LayerSpacing
    val totalH =
      if (rowsInTallestLayer == 0) 0.0
      else Padding * 2 + rowsInTallestLayer * NodeHeight +
        math.max(0, rowsInTallestLayer - 1) * NodeSpacing

    new DagLayout(taskBoxes.toIndexedSeq, inputBoxes.toIndexedSeq, inputNameToIdx, totalW, totalH)
  }
}
