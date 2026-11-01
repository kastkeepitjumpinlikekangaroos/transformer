package com.transformer.gui

import atlantafx.base.theme.Styles

import javafx.beans.property.{ReadOnlyDoubleProperty, ReadOnlyDoubleWrapper}
import javafx.geometry.{Insets, Pos}
import javafx.scene.canvas.{Canvas, GraphicsContext}
import javafx.scene.control.{Button, Label, Tooltip}
import javafx.scene.input.{MouseButton, MouseEvent, ScrollEvent}
import javafx.scene.layout.{HBox, Region, StackPane}
import javafx.scene.paint.Color
import javafx.scene.shape.ArcType
import javafx.scene.text.{Font, FontWeight, Text, TextAlignment}

/** A canvas that draws a graph of cards joined by edges, which the user pans
  * by dragging and zooms by scrolling: the job's DAG ([[DagCanvas]]) and the
  * data viewer's lineage draw on it, so both look and move alike.
  *
  * A subclass says how big its graph is ([[contentSize]]), paints it in world
  * coordinates ([[paintContent]]), paints what shows with no graph
  * ([[paintEmpty]]), and handles a click at a world point ([[clicked]]). The
  * canvas keeps a pan offset and a zoom factor and converts between world
  * and screen coordinates; world units are pixels at zoom 1. It paints only
  * when [[render]] is called, on a change, never on a timer.
  */
abstract class GraphCanvas extends Canvas(800, 600) {
  import GraphCanvas._
  import Theme.Palette

  private var panX: Double = 0.0
  private var panY: Double = 0.0
  private val zoomWrapper = new ReadOnlyDoubleWrapper(1.0)
  protected def zoom: Double = zoomWrapper.get

  private var dragging: Boolean = false
  private var lastDragX: Double = 0.0
  private var lastDragY: Double = 0.0

  /** The zoom factor, 1 at world scale. */
  def zoomProperty: ReadOnlyDoubleProperty = zoomWrapper.getReadOnlyProperty

  widthProperty().addListener((_, _, _) => render())
  heightProperty().addListener((_, _, _) => render())

  // A primary drag pans; JavaFX sends no MOUSE_CLICKED after a drag, so a pan
  // never also selects.
  addEventHandler(MouseEvent.MOUSE_PRESSED, (e: MouseEvent) => if (e.getButton == MouseButton.PRIMARY) {
    dragging = true
    lastDragX = e.getX
    lastDragY = e.getY
  })
  addEventHandler(MouseEvent.MOUSE_DRAGGED, (e: MouseEvent) => if (dragging) {
    panX += e.getX - lastDragX
    panY += e.getY - lastDragY
    lastDragX = e.getX
    lastDragY = e.getY
    render()
  })
  addEventHandler(MouseEvent.MOUSE_RELEASED, (_: MouseEvent) => dragging = false)
  addEventHandler(MouseEvent.MOUSE_CLICKED, (e: MouseEvent) => if (e.getButton == MouseButton.PRIMARY) {
    val (wx, wy) = screenToWorld(e.getX, e.getY)
    clicked(wx, wy, e.getClickCount)
  })
  addEventHandler(ScrollEvent.SCROLL, (e: ScrollEvent) =>
    zoomAround(e.getX, e.getY, if (e.getDeltaY > 0) 1.1 else 1.0 / 1.1))

  override def isResizable: Boolean = true
  override def prefWidth(h: Double): Double = getWidth
  override def prefHeight(w: Double): Double = getHeight

  // ==================== what a subclass provides ====================

  /** The graph's width and height in world units, or None when there is no
    * graph to draw. */
  protected def contentSize: Option[(Double, Double)]

  /** Paint the graph; the context is already panned and zoomed. */
  protected def paintContent(gc: GraphicsContext): Unit

  /** Paint what the canvas shows with no graph, in screen coordinates. */
  protected def paintEmpty(gc: GraphicsContext, w: Double, h: Double): Unit

  /** A click at world point `(wx, wy)`; `clicks` is 2 for a double-click. */
  protected def clicked(wx: Double, wy: Double, clicks: Int): Unit

  /** The line of help at the bottom left while a graph shows. */
  protected def hint: String

  // ==================== painting ====================

  def render(): Unit = {
    val gc = getGraphicsContext2D
    val w = getWidth
    val h = getHeight
    gc.setFill(Palette.Background)
    gc.fillRect(0, 0, w, h)
    drawGrid(gc, w, h)
    if (contentSize.isEmpty) paintEmpty(gc, w, h)
    else {
      gc.save()
      gc.translate(panX, panY)
      gc.scale(zoom, zoom)
      paintContent(gc)
      gc.restore()
      gc.setFont(HintFont)
      gc.setFill(Palette.TextSubtle)
      gc.setTextAlign(TextAlignment.LEFT)
      gc.fillText(hint, 14, h - 14)
    }
  }

  /** A dot every 24 world pixels, or 48 when zoomed far out, moving with the
    * pan so the canvas feels like a surface. */
  private def drawGrid(gc: GraphicsContext, w: Double, h: Double): Unit = {
    var step = 24.0 * zoom
    while (step < 12.0) step *= 2
    gc.setFill(Palette.Grid)
    var x = ((panX % step) + step) % step
    while (x < w) {
      var y = ((panY % step) + step) % step
      while (y < h) {
        gc.fillRect(x, y, 1.5, 1.5)
        y += step
      }
      x += step
    }
  }

  /** A title and lines under it, centred: what shows with nothing to draw. */
  protected def paintMessage(gc: GraphicsContext, w: Double, h: Double, title: String, titleColor: Color,
      lines: Seq[String]): Unit = {
    gc.setTextAlign(TextAlignment.CENTER)
    gc.setFill(titleColor)
    gc.setFont(Font.font("System", FontWeight.SEMI_BOLD, 15))
    gc.fillText(title, w / 2.0, h / 2.0 - 8)
    gc.setFill(Palette.TextMuted)
    gc.setFont(Font.font("System", FontWeight.NORMAL, 12))
    lines.iterator.zipWithIndex.foreach { case (line, i) => gc.fillText(line, w / 2.0, h / 2.0 + 16 + i * 17.0) }
    gc.setTextAlign(TextAlignment.LEFT)
  }

  /** An edge from the right side of one card, at `(x1, y1)`, to the left side
    * of another, at `(x2, y2)`, with an arrowhead. The curve leaves and
    * enters level, so it bends cleanly between layers at different heights. */
  protected def drawEdge(gc: GraphicsContext, x1: Double, y1: Double, x2: Double, y2: Double, color: Color): Unit = {
    val endX = x2 - 2
    val midX = (x1 + endX) / 2.0
    gc.setStroke(color)
    gc.beginPath()
    gc.moveTo(x1, y1)
    gc.bezierCurveTo(midX, y1, midX, y2, endX, y2)
    gc.stroke()
    val ah = 6.0
    gc.beginPath()
    gc.moveTo(endX + 1, y2)
    gc.lineTo(endX - ah, y2 - ah / 2.0 - 0.5)
    gc.lineTo(endX - ah, y2 + ah / 2.0 + 0.5)
    gc.closePath()
    gc.setFill(color)
    gc.fill()
  }

  /** The card a node is drawn on: a raised surface with a bar down its left
    * edge in `accent`, and an accent border inside a soft ring when
    * selected. */
  protected def drawCard(gc: GraphicsContext, x: Double, y: Double, w: Double, h: Double, accent: Color,
      selected: Boolean): Unit = {
    if (selected) {
      gc.setStroke(Ring)
      gc.setLineWidth(5)
      gc.strokeRoundRect(x - 3.5, y - 3.5, w + 7, h + 7, Radius * 2 + 7, Radius * 2 + 7)
    }
    gc.setFill(Palette.Surface)
    gc.fillRoundRect(x, y, w, h, Radius * 2, Radius * 2)
    gc.save()
    roundRectPath(gc, x, y, w, h, Radius)
    gc.clip()
    gc.setFill(accent)
    gc.fillRect(x, y, 4, h)
    gc.restore()
    gc.setStroke(if (selected) Palette.Accent else Palette.Border)
    gc.setLineWidth(if (selected) 2.0 else 1.0)
    gc.strokeRoundRect(x, y, w, h, Radius * 2, Radius * 2)
  }

  /** A small cylinder centred at `(cx, cy)`: the mark of data read from a
    * file rather than made by a query. */
  protected def drawCylinder(gc: GraphicsContext, cx: Double, cy: Double, color: Color): Unit = {
    gc.setStroke(color)
    gc.setLineWidth(1.4)
    gc.strokeOval(cx - 6, cy - 9, 12, 5)
    gc.strokeLine(cx - 6, cy - 6.5, cx - 6, cy + 6.5)
    gc.strokeLine(cx + 6, cy - 6.5, cx + 6, cy + 6.5)
    gc.strokeArc(cx - 6, cy + 4, 12, 5, 180, 180, ArcType.OPEN)
  }

  protected def roundRectPath(gc: GraphicsContext, x: Double, y: Double, w: Double, h: Double, r: Double): Unit = {
    val right = x + w
    val bottom = y + h
    gc.beginPath()
    gc.moveTo(x + r, y)
    gc.lineTo(right - r, y)
    gc.arcTo(right, y, right, y + r, r)
    gc.lineTo(right, bottom - r)
    gc.arcTo(right, bottom, right - r, bottom, r)
    gc.lineTo(x + r, bottom)
    gc.arcTo(x, bottom, x, bottom - r, r)
    gc.lineTo(x, y + r)
    gc.arcTo(x, y, x + r, y, r)
    gc.closePath()
  }

  /** `s`, its first line, cut with an ellipsis to fit `maxWidth` in `font`. */
  protected def fit(s: String, font: Font, maxWidth: Double): String = {
    val line = s.takeWhile(_ != '\n')
    if (textWidth(line, font) <= maxWidth) return line
    var lo = 0
    var hi = line.length
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (textWidth(line.substring(0, mid) + "…", font) <= maxWidth) lo = mid else hi = mid - 1
    }
    line.substring(0, lo) + "…"
  }

  private val measure = new Text()
  protected def textWidth(s: String, font: Font): Double = {
    measure.setText(s)
    measure.setFont(font)
    measure.getLayoutBounds.getWidth
  }

  // ==================== panning and zooming ====================

  /** Zoom by `factor`, keeping the world point under `(sx, sy)` where it is. */
  private def zoomAround(sx: Double, sy: Double, factor: Double): Unit = {
    val newZoom = math.max(MinZoom, math.min(MaxZoom, zoom * factor))
    if (newZoom != zoom) {
      val (wx, wy) = screenToWorld(sx, sy)
      zoomWrapper.set(newZoom)
      val (nx, ny) = worldToScreen(wx, wy)
      panX += sx - nx
      panY += sy - ny
      render()
    }
  }

  def zoomIn(): Unit = zoomAround(getWidth / 2.0, getHeight / 2.0, 1.2)
  def zoomOut(): Unit = zoomAround(getWidth / 2.0, getHeight / 2.0, 1.0 / 1.2)

  protected def screenToWorld(sx: Double, sy: Double): (Double, Double) =
    ((sx - panX) / zoom, (sy - panY) / zoom)

  protected def worldToScreen(wx: Double, wy: Double): (Double, Double) =
    (wx * zoom + panX, wy * zoom + panY)

  /** Zoom and pan so the whole graph shows, centred; back to world scale at
    * the origin when there is none. Call [[render]] after. */
  def fitToWindow(): Unit = contentSize match {
    case Some((cw, ch)) if cw > 0 && ch > 0 && getWidth > 0 && getHeight > 0 =>
      val w = getWidth
      val h = getHeight
      zoomWrapper.set(math.max(MinZoom, math.min(1.5, math.min(w / cw, h / ch) * 0.95)))
      panX = (w - cw * zoom) / 2.0
      panY = (h - ch * zoom) / 2.0
    case _ =>
      panX = 0.0
      panY = 0.0
      zoomWrapper.set(1.0)
  }

  /** Pan so the world point `(wx, wy)` sits at the centre, keeping the zoom. */
  def centerOn(wx: Double, wy: Double): Unit = {
    panX = getWidth / 2.0 - wx * zoom
    panY = getHeight / 2.0 - wy * zoom
    render()
  }
}

object GraphCanvas {
  val TitleFont: Font = Font.font("System", FontWeight.SEMI_BOLD, 13)
  val StatusFont: Font = Font.font("System", FontWeight.NORMAL, 11)
  val TagFont: Font = Font.font("System", FontWeight.BOLD, 9)
  private val HintFont: Font = Font.font("System", FontWeight.NORMAL, 11)
  private val Radius = 10.0
  private val MinZoom = 0.2
  private val MaxZoom = 3.0
  private val Ring: Color = Theme.Palette.Accent.deriveColor(0, 1, 1, 0.28)
}

/** A [[GraphCanvas]] with what floats over it: zoom controls at the bottom
  * right, and [[summary]] at the top left for the host to fill. */
class GraphOverlay(canvas: GraphCanvas) extends StackPane {

  getStyleClass.add("dag-pane")
  canvas.widthProperty().bind(widthProperty())
  canvas.heightProperty().bind(heightProperty())
  // The canvas is as large as the pane, never what makes it larger.
  setMinSize(0, 0)

  protected val summary = new HBox()
  summary.getStyleClass.add("dag-summary")
  summary.setPickOnBounds(false)
  summary.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE)
  StackPane.setAlignment(summary, Pos.TOP_LEFT)
  StackPane.setMargin(summary, new Insets(12))

  private val zoomLabel = new Label()
  zoomLabel.getStyleClass.add("zoom-label")
  private def control(icon: javafx.scene.Node, tip: String)(f: => Unit): Button = {
    val b = new Button(null, icon)
    b.getStyleClass.addAll(Styles.FLAT, Styles.SMALL)
    b.setTooltip(new Tooltip(tip))
    b.setFocusTraversable(false)
    b.setOnAction(_ => f)
    b
  }
  private val controls = new HBox(
    control(Icons.minus, "Zoom out (⌘-)")(canvas.zoomOut()),
    zoomLabel,
    control(Icons.plus, "Zoom in (⌘=)")(canvas.zoomIn()),
    control(Icons.fit, "Fit to window (⌘0)") { canvas.fitToWindow(); canvas.render() }
  )
  controls.getStyleClass.add("dag-controls")
  controls.setAlignment(Pos.CENTER)
  controls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE)
  StackPane.setAlignment(controls, Pos.BOTTOM_RIGHT)
  StackPane.setMargin(controls, new Insets(12))

  getChildren.addAll(canvas, summary, controls)

  canvas.zoomProperty.addListener((_, _, z) => zoomLabel.setText(f"${z.doubleValue * 100}%.0f%%"))
  zoomLabel.setText(f"${canvas.zoomProperty.get * 100}%.0f%%")

  /** Show the zoom controls only while there is a graph to zoom. */
  protected def setControlsVisible(visible: Boolean): Unit = controls.setVisible(visible)

  /** A count in [[summary]]: `n label` beside a dot of `color`. */
  protected def count(label: String, color: Color, n: Int): Unit =
    if (n > 0) {
      val l = new Label(s"$n $label", new javafx.scene.shape.Circle(3.5, color))
      l.getStyleClass.add("count")
      summary.getChildren.add(l)
    }
}
