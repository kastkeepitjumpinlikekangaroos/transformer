package com.transformer.gui

import com.transformer.job.{InputFilePath, TaskDag, TaskStatus}

import javafx.scene.canvas.{Canvas, GraphicsContext}
import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.paint.Color
import javafx.scene.text.{Font, FontWeight, TextAlignment}

/** JavaFX Canvas that draws the loaded [[com.transformer.job.TaskDag]] (plus its
  * input views) and lets the user pan, zoom, single-click to select, and
  * double-click to inspect a task's output or an input's contents.
  *
  * Coordinates: the layout assigns world-space positions to every node. The
  * canvas keeps a pan offset `(panX, panY)` and a `zoom` factor, and converts
  * between world ↔ screen on the fly. World units are pixels at zoom=1.
  *
  * Render is called explicitly on state changes (layout/run/selection); no
  * AnimationTimer — for a DAG visualizer the redraw rate is low and we'd rather
  * avoid burning CPU when nothing's happening.
  */
final class DagCanvas(session: JobSession) extends Canvas(800, 600) {

  // Pan/zoom state. Reset whenever a new DAG loads.
  private var panX: Double = 0.0
  private var panY: Double = 0.0
  private var zoom: Double = 1.0
  private var lastFitDagIdentity: Option[TaskDag] = None

  // Mouse drag tracking.
  private var dragging: Boolean = false
  private var lastDragX: Double = 0.0
  private var lastDragY: Double = 0.0

  // External callbacks fired on double-click.
  private var onTaskActivated: Int => Unit = _ => ()
  private var onInputActivated: Int => Unit = _ => ()
  def setOnTaskActivated(handler: Int => Unit): Unit = { onTaskActivated = handler }
  def setOnInputActivated(handler: Int => Unit): Unit = { onInputActivated = handler }

  // Always redraw when the session changes.
  session.addListener(() => { autoFitIfNewDag(); render() })

  // Repaint when the canvas itself resizes.
  widthProperty().addListener((_, _, _) => render())
  heightProperty().addListener((_, _, _) => render())

  // Mouse: pan via right-button or middle-button drag, click/double-click to select/activate.
  addEventHandler(MouseEvent.MOUSE_PRESSED, (e: MouseEvent) => handleMousePressed(e))
  addEventHandler(MouseEvent.MOUSE_DRAGGED, (e: MouseEvent) => handleMouseDragged(e))
  addEventHandler(MouseEvent.MOUSE_RELEASED, (e: MouseEvent) => handleMouseReleased(e))
  addEventHandler(MouseEvent.MOUSE_CLICKED, (e: MouseEvent) => handleMouseClicked(e))
  addEventHandler(ScrollEvent.SCROLL, (e: ScrollEvent) => handleScroll(e))

  override def isResizable: Boolean = true
  override def prefWidth(h: Double): Double = getWidth
  override def prefHeight(w: Double): Double = getHeight

  // -------------------- rendering --------------------

  def render(): Unit = {
    val gc = getGraphicsContext2D
    val w = getWidth
    val h = getHeight

    // Background.
    gc.setFill(Color.web("#1e1e2a"))
    gc.fillRect(0, 0, w, h)

    session.layout match {
      case None => drawHelpText(gc, w, h)
      case Some(layout) =>
        gc.save()
        gc.translate(panX, panY)
        gc.scale(zoom, zoom)
        drawEdges(gc, layout)
        drawInputNodes(gc, layout)
        drawTaskNodes(gc, layout)
        gc.restore()
        drawHud(gc, w, h)
    }
  }

  private def drawHelpText(gc: GraphicsContext, w: Double, h: Double): Unit = {
    gc.setFill(Color.web("#7e8aa6"))
    gc.setFont(Font.font("Sans", FontWeight.NORMAL, 14))
    gc.setTextAlign(TextAlignment.CENTER)
    val msg = session.runState match {
      case RunState.LoadFailed(err) => s"Failed to load job:\n$err"
      case _ => "Open a job directory (File menu) to see its DAG."
    }
    val lines = msg.split('\n')
    var i = 0
    while (i < lines.length) {
      gc.fillText(lines(i), w / 2.0, h / 2.0 + i * 18.0)
      i += 1
    }
    gc.setTextAlign(TextAlignment.LEFT)
  }

  private def drawEdges(gc: GraphicsContext, layout: DagLayout): Unit = {
    val dag = session.dag.get
    gc.setLineWidth(1.5)
    var i = 0
    while (i < dag.nodes.size) {
      val toBox = layout.taskBoxes(i)
      // Task → Task edges (existing).
      gc.setStroke(Color.web("#5b6175"))
      dag.nodes(i).deps.foreach { d =>
        val fromBox = layout.taskBoxes(d)
        drawEdge(gc, fromBox, toBox, Color.web("#5b6175"))
      }
      // Input → Task edges (new). Subtler colour so the eye can distinguish
      // "data lineage" from "task ordering".
      val inputEdgeColor = Color.web("#3f6f5c")
      gc.setStroke(inputEdgeColor)
      dag.nodes(i).inputDeps.foreach { name =>
        layout.boxForInputName(name).foreach { fromBox =>
          drawEdge(gc, fromBox, toBox, inputEdgeColor)
        }
      }
      i += 1
    }
  }

  private def drawEdge(gc: GraphicsContext, from: NodeBox, to: NodeBox, color: Color): Unit = {
    val x1 = from.right
    val y1 = from.centerY
    val x2 = to.x
    val y2 = to.centerY
    // Bezier curve with horizontal tangents so the line bends out of the node
    // edge cleanly even when the y coordinates differ between layers.
    val midX = (x1 + x2) / 2.0
    gc.beginPath()
    gc.moveTo(x1, y1)
    gc.bezierCurveTo(midX, y1, midX, y2, x2, y2)
    gc.stroke()
    // Arrowhead at (x2, y2) pointing right.
    val ah = 7.0
    gc.beginPath()
    gc.moveTo(x2, y2)
    gc.lineTo(x2 - ah, y2 - ah / 2.0)
    gc.lineTo(x2 - ah, y2 + ah / 2.0)
    gc.closePath()
    gc.setFill(color)
    gc.fill()
  }

  private def drawInputNodes(gc: GraphicsContext, layout: DagLayout): Unit = {
    val inputs = session.inputs
    val selected = session.selectedInputIndex
    var i = 0
    while (i < layout.inputBoxes.size && i < inputs.size) {
      drawInputNode(gc, layout.inputBoxes(i), inputs(i), selected.contains(i))
      i += 1
    }
  }

  private def drawInputNode(gc: GraphicsContext, box: NodeBox, in: InputFilePath, isSelected: Boolean): Unit = {
    val fill = Color.web("#2b4a3a")
    val stroke = Color.web("#5fa17a")
    gc.setFill(fill)
    gc.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12)
    gc.setStroke(if (isSelected) Color.web("#ffd166") else stroke)
    gc.setLineWidth(if (isSelected) 3.0 else 1.5)
    gc.strokeRoundRect(box.x, box.y, box.width, box.height, 12, 12)
    // Small "INPUT" tag on the top-left corner so inputs are unmistakable.
    gc.setFill(Color.web("#9bd1ad"))
    gc.setFont(Font.font("Sans", FontWeight.BOLD, 9))
    gc.setTextAlign(TextAlignment.LEFT)
    gc.fillText(s"INPUT • ${in.detectedFormat.toUpperCase}", box.x + 12, box.y + 14)
    // View name (the headline).
    gc.setFill(Color.web("#f7f7fb"))
    gc.setFont(Font.font("Sans", FontWeight.BOLD, 13))
    gc.setTextAlign(TextAlignment.CENTER)
    gc.fillText(truncate(in.viewName, 26), box.centerX, box.y + 32, box.width - 16)
    // File name footer (basename of the resolved path).
    gc.setFill(Color.web("#c5cad8"))
    gc.setFont(Font.font("Sans", FontWeight.NORMAL, 11))
    gc.fillText(truncate(basename(in.path), 30), box.centerX, box.y + 50, box.width - 16)
    gc.setTextAlign(TextAlignment.LEFT)
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
    val (fill, stroke, statusLine) = nodeColors(state)
    gc.setFill(fill)
    gc.fillRoundRect(box.x, box.y, box.width, box.height, 12, 12)
    gc.setStroke(if (isSelected) Color.web("#ffd166") else stroke)
    gc.setLineWidth(if (isSelected) 3.0 else 1.5)
    gc.strokeRoundRect(box.x, box.y, box.width, box.height, 12, 12)
    // Task title (truncated if too long).
    gc.setFill(Color.web("#f7f7fb"))
    gc.setFont(Font.font("Sans", FontWeight.BOLD, 13))
    gc.setTextAlign(TextAlignment.CENTER)
    gc.fillText(truncate(label, 26), box.centerX, box.y + 22, box.width - 16)
    // Status text.
    gc.setFill(Color.web("#c5cad8"))
    gc.setFont(Font.font("Sans", FontWeight.NORMAL, 11))
    gc.fillText(statusLine, box.centerX, box.y + 42, box.width - 16)
    gc.setTextAlign(TextAlignment.LEFT)
  }

  private def nodeColors(state: UiTaskState): (Color, Color, String) = state match {
    case UiTaskState.Pending => (Color.web("#3a3f55"), Color.web("#5b6175"), "pending")
    case UiTaskState.Running => (Color.web("#2b4f7a"), Color.web("#4a90e2"), "running…")
    case UiTaskState.Done(result) =>
      result.status match {
        case TaskStatus.Succeeded =>
          (Color.web("#244c2f"), Color.web("#4caf50"),
            f"succeeded • ${result.rowsProduced}%,d rows • ${result.durationMillis} ms")
        case TaskStatus.Failed(_) =>
          (Color.web("#5a2828"), Color.web("#e57373"),
            s"failed • ${result.durationMillis} ms")
        case TaskStatus.ValidationFailed(fs) =>
          (Color.web("#5a4322"), Color.web("#ffa726"),
            s"validation failed (${fs.size}) • ${result.durationMillis} ms")
        case TaskStatus.Skipped(_) =>
          (Color.web("#34384a"), Color.web("#7a8095"), "skipped")
        case TaskStatus.Pending =>
          (Color.web("#3a3f55"), Color.web("#5b6175"), "pending")
      }
  }

  private def drawHud(gc: GraphicsContext, w: Double, h: Double): Unit = {
    val state = f"zoom ${zoom * 100}%.0f%% • pan ($panX%.0f, $panY%.0f)"
    val hint = "drag right/middle to pan • scroll to zoom • click to select • double-click to view output"
    gc.setFont(Font.font("Sans", FontWeight.BOLD, 11))
    gc.setFill(Color.web("#9ba2b8"))
    gc.fillText(state, 12, h - 26)
    gc.setFont(Font.font("Sans", FontWeight.NORMAL, 11))
    gc.setFill(Color.web("#6a708a"))
    gc.fillText(hint, 12, h - 10)
  }

  private def truncate(s: String, max: Int): String =
    if (s.length <= max) s else s.substring(0, max - 1) + "…"

  // -------------------- interaction --------------------

  private def handleMousePressed(e: MouseEvent): Unit = {
    val button = e.getButton
    if (button == MouseButton.MIDDLE || button == MouseButton.SECONDARY ||
        (button == MouseButton.PRIMARY && e.isAltDown)) {
      dragging = true
      lastDragX = e.getX
      lastDragY = e.getY
    }
  }

  private def handleMouseDragged(e: MouseEvent): Unit = {
    if (dragging) {
      panX += e.getX - lastDragX
      panY += e.getY - lastDragY
      lastDragX = e.getX
      lastDragY = e.getY
      render()
    }
  }

  private def handleMouseReleased(e: MouseEvent): Unit = {
    if (dragging) dragging = false
  }

  private def handleMouseClicked(e: MouseEvent): Unit = {
    if (e.getButton != MouseButton.PRIMARY) return
    val (wx, wy) = screenToWorld(e.getX, e.getY)
    session.layout.flatMap(_.hitTest(wx, wy)) match {
      case Some(box) =>
        val sel = box.kind match {
          case NodeKind.Task => Selection.Task(box.index)
          case NodeKind.Input => Selection.Input(box.index)
        }
        session.select(Some(sel))
        if (e.getClickCount >= 2) {
          box.kind match {
            case NodeKind.Task => onTaskActivated(box.index)
            case NodeKind.Input => onInputActivated(box.index)
          }
        }
      case None =>
        // Clicked empty space — only clear selection on a single click; a stray
        // double-click on whitespace shouldn't surprise the user.
        if (e.getClickCount == 1) session.select(None)
    }
  }

  private def handleScroll(e: ScrollEvent): Unit = {
    val factor = if (e.getDeltaY > 0) 1.1 else 1.0 / 1.1
    val newZoom = math.max(0.2, math.min(3.0, zoom * factor))
    if (newZoom != zoom) {
      // Zoom around the mouse pointer for a natural feel.
      val (wx, wy) = screenToWorld(e.getX, e.getY)
      zoom = newZoom
      val (sx, sy) = worldToScreen(wx, wy)
      panX += e.getX - sx
      panY += e.getY - sy
      render()
    }
  }

  private def screenToWorld(sx: Double, sy: Double): (Double, Double) =
    ((sx - panX) / zoom, (sy - panY) / zoom)

  private def worldToScreen(wx: Double, wy: Double): (Double, Double) =
    (wx * zoom + panX, wy * zoom + panY)

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

  def fitToWindow(): Unit = {
    session.layout match {
      case Some(layout) if layout.width > 0 && layout.height > 0 =>
        val w = getWidth
        val h = getHeight
        if (w > 0 && h > 0) {
          val zx = w / layout.width
          val zy = h / layout.height
          zoom = math.max(0.2, math.min(1.5, math.min(zx, zy) * 0.95))
          panX = (w - layout.width * zoom) / 2.0
          panY = (h - layout.height * zoom) / 2.0
        }
      case _ =>
        panX = 0.0; panY = 0.0; zoom = 1.0
    }
  }
}
