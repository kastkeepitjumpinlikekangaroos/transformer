package com.transformer.gui

import com.transformer.job.{InputFilePath, TaskDag, TaskStatus}

import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.TextAlignment

/** The [[GraphCanvas]] that draws the loaded [[com.transformer.job.TaskDag]]
  * (plus its input views): single-click selects a node, double-click inspects
  * a task's output or an input's contents.
  *
  * Each node is a card: a bar down its left edge in the colour of its status
  * (an input's in the input colour), its name, and a status line. The edges
  * into and out of the selected node are drawn in the accent colour, so its
  * lineage stands out.
  *
  * Render is called explicitly on state changes (layout/run/selection); no
  * AnimationTimer — for a DAG visualizer the redraw rate is low and we'd rather
  * avoid burning CPU when nothing's happening.
  */
final class DagCanvas(session: JobSession) extends GraphCanvas {
  import GraphCanvas.{StatusFont, TagFont, TitleFont}
  import Theme.Palette

  private var lastFitDagIdentity: Option[TaskDag] = None

  // External callbacks fired on double-click.
  private var onTaskActivated: Int => Unit = _ => ()
  private var onInputActivated: Int => Unit = _ => ()
  def setOnTaskActivated(handler: Int => Unit): Unit = { onTaskActivated = handler }
  def setOnInputActivated(handler: Int => Unit): Unit = { onInputActivated = handler }

  // Always redraw when the session changes.
  session.addListener(() => { autoFitIfNewDag(); render() })

  private val EdgeColor = Palette.Border
  private val InputEdgeColor = Palette.Input.deriveColor(0, 1, 0.75, 0.45)

  protected def contentSize: Option[(Double, Double)] = session.layout.map(l => (l.width, l.height))

  protected def hint: String = "Drag to pan · scroll to zoom · double-click a node to open its rows"

  protected def paintContent(gc: GraphicsContext): Unit = session.layout.foreach { layout =>
    drawEdges(gc, layout)
    drawInputNodes(gc, layout)
    drawTaskNodes(gc, layout)
  }

  protected def paintEmpty(gc: GraphicsContext, w: Double, h: Double): Unit = session.runState match {
    case RunState.LoadFailed(err) =>
      paintMessage(gc, w, h, "The job could not be loaded", Palette.Danger, err.split('\n').take(6).toSeq)
    case _ =>
      paintMessage(gc, w, h, "Open a job directory to see its DAG", Palette.Text,
        Seq("File > Open Job Directory, or File > New Project"))
  }

  /** Which node is selected, as the edges see it. */
  private def selectedTask: Option[Int] = session.selectedTaskIndex
  private def selectedInput: Option[String] =
    session.selectedInputIndex.flatMap(i => session.inputs.lift(i)).map(_.viewName.toLowerCase)

  private def drawEdges(gc: GraphicsContext, layout: DagLayout): Unit = {
    val dag = session.dag.get
    val lit = scala.collection.mutable.ArrayBuffer.empty[(NodeBox, NodeBox, Color)]
    gc.setLineWidth(1.5)
    var i = 0
    while (i < dag.nodes.size) {
      val toBox = layout.taskBoxes(i)
      dag.nodes(i).deps.foreach { d =>
        val fromBox = layout.taskBoxes(d)
        if (selectedTask.contains(i) || selectedTask.contains(d)) lit += ((fromBox, toBox, Palette.Accent))
        else edge(gc, fromBox, toBox, EdgeColor)
      }
      // Input → task edges: data lineage, in the input colour.
      dag.nodes(i).inputDeps.foreach { name =>
        layout.boxForInputName(name).foreach { fromBox =>
          if (selectedTask.contains(i) || selectedInput.contains(name)) lit += ((fromBox, toBox, Palette.Input))
          else edge(gc, fromBox, toBox, InputEdgeColor)
        }
      }
      i += 1
    }
    gc.setLineWidth(2.0)
    lit.foreach { case (from, to, color) => edge(gc, from, to, color) }
  }

  private def edge(gc: GraphicsContext, from: NodeBox, to: NodeBox, color: Color): Unit =
    drawEdge(gc, from.right, from.centerY, to.x, to.centerY, color)

  private def drawInputNodes(gc: GraphicsContext, layout: DagLayout): Unit = {
    val inputs = session.inputs
    val selected = session.selectedInputIndex
    var i = 0
    while (i < layout.inputBoxes.size && i < inputs.size) {
      drawInputNode(gc, layout.inputBoxes(i), inputs(i), session.inputStateFor(i), selected.contains(i))
      i += 1
    }
  }

  private def drawInputNode(
      gc: GraphicsContext,
      box: NodeBox,
      in: InputFilePath,
      state: UiInputState,
      isSelected: Boolean
  ): Unit = {
    val (accent, footer) = state match {
      case UiInputState.Pending     => (Palette.Input, basename(in.path))
      case UiInputState.Loading     => (Palette.Accent, "loading…")
      case UiInputState.Loaded      => (Palette.Input, basename(in.path))
      case UiInputState.Failed(err) => (Palette.Danger, s"failed: $err")
    }
    card(gc, box, accent, isSelected)
    // A small cylinder: the input is data, not a query.
    drawCylinder(gc, box.x + 22, box.centerY, accent)
    val textX = box.x + 38
    val textW = box.right - textX - 10
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setFill(accent)
    gc.setFont(TagFont)
    gc.fillText(s"INPUT · ${in.detectedFormat.toUpperCase}", textX, box.y + 17)
    gc.setFill(Palette.Text)
    gc.setFont(TitleFont)
    gc.fillText(fit(in.viewName, TitleFont, textW), textX, box.y + 34)
    gc.setFill(Palette.TextMuted)
    gc.setFont(StatusFont)
    gc.fillText(fit(footer, StatusFont, textW), textX, box.y + 50)
  }

  private def basename(path: String): String = {
    val cleaned = path.replace('\\', '/')
    val slash = cleaned.lastIndexOf('/')
    if (slash < 0) cleaned else cleaned.substring(slash + 1)
  }

  private def drawTaskNodes(gc: GraphicsContext, layout: DagLayout): Unit = {
    val dag = session.dag.get
    val states = session.taskStates
    val selected = session.selectedTaskIndex
    var i = 0
    while (i < layout.taskBoxes.size) {
      val box = layout.taskBoxes(i)
      val task = dag.nodes(i).task
      val state = if (i < states.size) states(i) else UiTaskState.Pending
      drawTaskNode(gc, box, task.displayName, state, selected.contains(i))
      i += 1
    }
  }

  private def drawTaskNode(
      gc: GraphicsContext,
      box: NodeBox,
      label: String,
      state: UiTaskState,
      isSelected: Boolean
  ): Unit = {
    val (accent, statusLine) = nodeStatus(state)
    card(gc, box, accent, isSelected)
    val textX = box.x + 18
    val textW = box.right - textX - 10
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setFill(Palette.Text)
    gc.setFont(TitleFont)
    gc.fillText(fit(label, TitleFont, textW), textX, box.y + 25)
    gc.setFill(accent)
    gc.fillOval(textX, box.y + 37, 7, 7)
    gc.setFill(Palette.TextMuted)
    gc.setFont(StatusFont)
    gc.fillText(fit(statusLine, StatusFont, textW - 12), textX + 12, box.y + 44)
  }

  private def card(gc: GraphicsContext, box: NodeBox, accent: Color, isSelected: Boolean): Unit =
    drawCard(gc, box.x, box.y, box.width, box.height, accent, isSelected)

  /** A task's colour and status line. */
  private def nodeStatus(state: UiTaskState): (Color, String) = state match {
    case UiTaskState.Pending => (Palette.TextSubtle, "pending")
    case UiTaskState.Queued  => (Palette.Done, "queued…")
    case UiTaskState.Running => (Palette.Accent, "running…")
    case UiTaskState.Done(result) =>
      // A task restored from a job.json that predates entry timestamps has
      // them at the epoch: its duration is unknown, not 0 ms.
      val durLine =
        if (result.startedAt == java.time.Instant.EPOCH) "duration unknown"
        else if (result.queueWaitMillis > 0L) f"${result.durationMillis} ms (+${result.queueWaitMillis} ms queued)"
        else s"${result.durationMillis} ms"
      result.status match {
        case TaskStatus.Succeeded =>
          (Palette.Success, f"${result.rowsProduced}%,d rows · $durLine")
        case TaskStatus.Failed(_) =>
          (Palette.Danger, s"failed · $durLine")
        case TaskStatus.ValidationFailed(fs) =>
          val count = if (fs.isEmpty) "" else s" (${fs.size})"
          (Palette.Warning, s"validation failed$count · $durLine")
        case TaskStatus.Skipped(_) =>
          (Palette.TextSubtle.darker(), "skipped")
        case TaskStatus.Pending =>
          (Palette.TextSubtle, "pending")
      }
  }

  // -------------------- interaction --------------------

  protected def clicked(wx: Double, wy: Double, clicks: Int): Unit =
    session.layout.flatMap(_.hitTest(wx, wy)) match {
      case Some(box) =>
        val sel = box.kind match {
          case NodeKind.Task => Selection.Task(box.index)
          case NodeKind.Input => Selection.Input(box.index)
        }
        session.select(Some(sel))
        if (clicks >= 2) {
          box.kind match {
            case NodeKind.Task => onTaskActivated(box.index)
            case NodeKind.Input => onInputActivated(box.index)
          }
        }
      case None =>
        // Clicked empty space — only clear selection on a single click; a stray
        // double-click on whitespace shouldn't surprise the user.
        if (clicks == 1) session.select(None)
    }

  /** When a fresh DAG is loaded (i.e. identity changed) recenter the view so the
    * whole graph is visible. We keep the existing pan/zoom for in-place updates
    * (running, selection) so the user's view isn't yanked around mid-interaction.
    */
  private def autoFitIfNewDag(): Unit = {
    val current = session.dag
    if (current ne lastFitDagIdentity.orNull) {
      lastFitDagIdentity = current
      fitToWindow()
    }
  }
}

/** The DAG canvas with what floats over it: a count of the tasks in each
  * status at the top left, and zoom controls at the bottom right. */
final class DagOverlay(session: JobSession, canvas: DagCanvas) extends GraphOverlay(canvas) {
  import Theme.Palette

  session.addListener(() => refreshSummary())
  refreshSummary()

  private def refreshSummary(): Unit = {
    summary.getChildren.clear()
    val dag = session.dag.getOrElse {
      setControlsVisible(false)
      return
    }
    setControlsVisible(true)
    val states = dag.nodes.indices.map(i => session.taskStates.lift(i).getOrElse(UiTaskState.Pending))
    def done(p: TaskStatus => Boolean): Int = states.count {
      case UiTaskState.Done(r) => p(r.status)
      case _ => false
    }
    count("succeeded", Palette.Success, done(_ == TaskStatus.Succeeded))
    count("failed", Palette.Danger, done(_.isInstanceOf[TaskStatus.Failed]))
    count("validation failed", Palette.Warning, done(_.isInstanceOf[TaskStatus.ValidationFailed]))
    count("skipped", Palette.TextSubtle.darker(), done(_.isInstanceOf[TaskStatus.Skipped]))
    count("running", Palette.Accent, states.count(_ == UiTaskState.Running))
    count("queued", Palette.Done, states.count(_ == UiTaskState.Queued))
    count("pending", Palette.TextSubtle, states.count(_ == UiTaskState.Pending) + done(_ == TaskStatus.Pending))
    count(if (session.inputs.size == 1) "input" else "inputs", Palette.Input, session.inputs.size)
  }
}
