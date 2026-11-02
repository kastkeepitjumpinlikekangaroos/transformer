package com.transformer.gui

import javafx.geometry.VPos
import javafx.scene.SnapshotParameters
import javafx.scene.canvas.{Canvas, GraphicsContext}
import javafx.scene.image.WritableImage
import javafx.scene.input.MouseEvent
import javafx.scene.paint.Color
import javafx.scene.shape.{ArcType, StrokeLineCap, StrokeLineJoin}
import javafx.scene.text.{Font, FontWeight, TextAlignment}

/** A query's result drawn as a chart ([[ChartData]]): bars, lines, areas,
  * dots or a pie. Bars, lines, areas and dots stand on axes of round ticks
  * (dates and times on calendar ones), under a legend when there are two
  * series or more; a pie has its slices listed beside it with their values
  * and shares.
  *
  * The pointer reads what is under it: a bar's band, every series' value at
  * the X nearest it on a line or an area, the dot nearest it within reach,
  * a slice. Marks are thin and the frame recedes: bars at most 24px wide,
  * rounded at their end, lines 2px, dots ringed in the background colour,
  * gridlines a hairline, and each series in the next of eight colours
  * ([[ChartCanvas.SeriesColors]]), every one past the seventh combined in
  * grey as Other.
  *
  * It paints when what it shows or its size changes, never on a timer;
  * [[image]] paints the same chart at a scale, for a PNG.
  */
final class ChartCanvas extends Canvas(800, 400) {
  import ChartCanvas._
  import Theme.Palette

  private var data: Option[ChartData] = None
  private var message: (String, Seq[String]) = ("", Nil)
  private var hover: Option[Hover] = None
  private var pointer: (Double, Double) = (0.0, 0.0)
  /** Where the last paint put things, for the pointer to find them. */
  private var frame: Option[Frame] = None

  widthProperty().addListener((_, _, _) => render())
  heightProperty().addListener((_, _, _) => render())

  addEventHandler(MouseEvent.MOUSE_MOVED, (e: MouseEvent) => {
    pointer = (e.getX, e.getY)
    val now = frame.flatMap(hit(_, e.getX, e.getY))
    if (now.isDefined || hover.isDefined) {
      hover = now
      render()
    }
  })
  addEventHandler(MouseEvent.MOUSE_EXITED, (_: MouseEvent) => if (hover.isDefined) {
    hover = None
    render()
  })

  override def isResizable: Boolean = true
  override def prefWidth(h: Double): Double = getWidth
  override def prefHeight(w: Double): Double = getHeight

  /** The chart shown, if one is. */
  def chart: Option[ChartData] = data

  def show(d: ChartData): Unit = {
    data = Some(d)
    hover = None
    render()
  }

  /** A title and lines under it, centred, in place of a chart. */
  def showMessage(title: String, lines: String*): Unit = {
    data = None
    message = (title, lines)
    hover = None
    render()
  }

  def render(): Unit = frame = paint(getGraphicsContext2D, getWidth, getHeight, hover)

  /** The chart as it shows, without the pointer's reading, `scale` times
    * its size: painted again, so text and lines stay sharp. */
  def image(scale: Double): WritableImage = {
    val w = getWidth
    val h = getHeight
    val canvas = new Canvas(math.ceil(w * scale), math.ceil(h * scale))
    val gc = canvas.getGraphicsContext2D
    gc.scale(scale, scale)
    val _ = paint(gc, w, h, None)
    val params = new SnapshotParameters()
    params.setFill(Palette.Background)
    canvas.snapshot(params, null)
  }

  // ==================== painting ====================

  private def paint(gc: GraphicsContext, w: Double, h: Double, hover: Option[Hover]): Option[Frame] = {
    // A context keeps what the last paint set; each paint starts from the defaults.
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setTextBaseline(VPos.BASELINE)
    gc.setLineWidth(1)
    gc.setLineCap(StrokeLineCap.SQUARE)
    gc.setLineJoin(StrokeLineJoin.MITER)
    gc.setFill(Palette.Background)
    gc.fillRect(0, 0, w, h)
    data match {
      case _ if w < MinWidth || h < MinHeight =>
        if (h >= 24) {
          gc.setFont(LabelFont)
          gc.setFill(Palette.TextMuted)
          gc.setTextAlign(TextAlignment.CENTER)
          gc.setTextBaseline(VPos.CENTER)
          gc.fillText("Too small to draw: make this pane taller", w / 2, h / 2)
        }
        None
      case None =>
        GraphCanvas.paintMessage(gc, w, h, message._1, Palette.Text, message._2)
        None
      case Some(d) if d.chartType == ChartType.Pie => Some(paintPie(gc, d, w, h, hover))
      case Some(d) => Some(paintAxes(gc, d, w, h, hover))
    }
  }

  private def paintAxes(gc: GraphicsContext, d: ChartData, w: Double, h: Double, hover: Option[Hover]): AxesFrame = {
    val f = axesFrame(d, w, h)
    gc.setLineWidth(1)

    paintLegend(gc, d, f.legend)
    gc.setFont(LabelFont)
    gc.setFill(Palette.TextMuted)
    gc.setTextBaseline(VPos.TOP)
    if (f.titled && d.yTitle.nonEmpty) {
      gc.setTextAlign(TextAlignment.LEFT)
      gc.fillText(GraphCanvas.fit(d.yTitle, LabelFont, w - 2 * Pad), Pad, f.yTitleTop)
    }
    if (f.titled && d.xTitle.nonEmpty) {
      gc.setTextAlign(TextAlignment.CENTER)
      gc.setTextBaseline(VPos.BOTTOM)
      gc.fillText(GraphCanvas.fit(d.xTitle, LabelFont, f.plotW), f.plotX + f.plotW / 2, h - Pad + 4)
    }

    // A hovered band shows behind its bars.
    hover.foreach {
      case Hover.Band(i) =>
        gc.setFill(Palette.Surface)
        gc.fillRect(f.plotX + i * f.slotW, f.plotY, f.slotW, f.plotH)
      case _ => ()
    }

    // Gridlines and Y's ticks, then the baseline: zero, or the bottom.
    gc.setFont(TickFont)
    gc.setTextAlign(TextAlignment.RIGHT)
    gc.setTextBaseline(VPos.CENTER)
    f.y.values.indices.foreach { i =>
      val y = crisp(f.yAt(f.y.values(i)))
      gc.setStroke(Palette.BorderMuted)
      gc.strokeLine(f.plotX, y, f.plotX + f.plotW, y)
      gc.setFill(Palette.TextMuted)
      gc.fillText(f.yLabels(i), f.plotX - 8, y)
    }
    gc.setStroke(Palette.Border)
    val base = crisp(f.yAt(f.baseline))
    gc.strokeLine(f.plotX, base, f.plotX + f.plotW, base)
    paintXLabels(gc, f, w)

    d.chartType match {
      case ChartType.Bar => paintBars(gc, f, hover)
      case ChartType.Line => paintLines(gc, f, area = false, hover)
      case ChartType.Area => paintLines(gc, f, area = true, hover)
      case _ => paintDots(gc, f, hover)
    }
    hover.foreach(hv => tip(f, hv).foreach { case (title, rows) => paintTip(gc, w, h, title, rows) })
    f
  }

  private def paintXLabels(gc: GraphicsContext, f: AxesFrame, w: Double): Unit = {
    gc.setFont(TickFont)
    gc.setFill(Palette.TextMuted)
    val top = f.plotY + f.plotH + 6
    if (f.xAxis.rotated) {
      gc.setTextAlign(TextAlignment.RIGHT)
      gc.setTextBaseline(VPos.CENTER)
      f.xAxis.labels.foreach { l =>
        gc.save()
        gc.translate(l.x + 3, top + 2)
        gc.rotate(-45)
        gc.fillText(l.text, 0, 0)
        gc.restore()
      }
    } else {
      gc.setTextAlign(TextAlignment.CENTER)
      gc.setTextBaseline(VPos.TOP)
      f.xAxis.labels.foreach { l =>
        // A label at an end keeps inside the canvas.
        val half = GraphCanvas.textWidth(l.text, TickFont) / 2
        gc.fillText(l.text, math.max(half + 2, math.min(w - half - 2, l.x)), top)
      }
    }
  }

  private def paintBars(gc: GraphicsContext, f: AxesFrame, hover: Option[Hover]): Unit = {
    val d = f.data
    val n = d.series.size
    val gap = if (f.slotW / n >= 8) 2.0 else 0.0
    val width = math.max(1.0, math.min(MaxBar, (f.slotW * 0.8 - gap * (n - 1)) / n))
    val group = width * n + gap * (n - 1)
    val y0 = f.yAt(f.baseline)
    d.series.indices.foreach { s =>
      val color = seriesColor(d, s)
      d.series(s).points.foreach { p =>
        val band = p.x.toInt
        val x = f.plotX + band * f.slotW + (f.slotW - group) / 2 + s * (width + gap)
        gc.setFill(if (hover.contains(Hover.Band(band))) lift(color) else color)
        bar(gc, x, width, y0, f.yAt(p.y))
      }
    }
  }

  /** A bar `w` wide from the baseline `y0` to `y1`, rounded at `y1`. */
  private def bar(gc: GraphicsContext, x: Double, w: Double, y0: Double, y1: Double): Unit = {
    val h = math.abs(y1 - y0)
    if (h >= 0.5) {
      val r = math.min(EndRadius, math.min(w / 2, h))
      val toward = if (y1 < y0) r else -r
      gc.beginPath()
      gc.moveTo(x, y0)
      gc.lineTo(x, y1 + toward)
      gc.arcTo(x, y1, x + r, y1, r)
      gc.lineTo(x + w - r, y1)
      gc.arcTo(x + w, y1, x + w, y1 + toward, r)
      gc.lineTo(x + w, y0)
      gc.closePath()
      gc.fill()
    }
  }

  private def paintLines(gc: GraphicsContext, f: AxesFrame, area: Boolean, hover: Option[Hover]): Unit = {
    val d = f.data
    val baseY = f.yAt(f.baseline)
    val most = d.series.map(_.points.size).max
    // Each point gets a dot while they stand well apart.
    val dots = most <= 1 || f.plotW / most >= 14
    d.series.indices.foreach { s =>
      val points = d.series(s).points
      if (points.nonEmpty) {
        val color = seriesColor(d, s)
        val xs = points.map(p => f.xAt(p.x))
        val ys = points.map(p => f.yAt(p.y))
        if (area) {
          gc.setFill(color.deriveColor(0, 1, 1, AreaOpacity))
          gc.beginPath()
          gc.moveTo(xs.head, baseY)
          xs.indices.foreach(i => gc.lineTo(xs(i), ys(i)))
          gc.lineTo(xs.last, baseY)
          gc.closePath()
          gc.fill()
        }
        gc.setStroke(color)
        gc.setLineWidth(2)
        gc.setLineCap(StrokeLineCap.ROUND)
        gc.setLineJoin(StrokeLineJoin.ROUND)
        gc.beginPath()
        gc.moveTo(xs.head, ys.head)
        xs.indices.drop(1).foreach(i => gc.lineTo(xs(i), ys(i)))
        gc.stroke()
        if (dots) xs.indices.foreach(i => dot(gc, xs(i), ys(i), color, ring = true))
      }
    }
    gc.setLineWidth(1)
    hover.foreach {
      case Hover.AtX(x) =>
        val sx = crisp(f.xAt(x))
        gc.setStroke(Palette.TextSubtle)
        gc.strokeLine(sx, f.plotY, sx, f.plotY + f.plotH)
        d.series.indices.foreach { s =>
          d.series(s).points.find(_.x == x)
            .foreach(p => dot(gc, f.xAt(p.x), f.yAt(p.y), seriesColor(d, s), ring = true))
        }
      case _ => ()
    }
  }

  private def paintDots(gc: GraphicsContext, f: AxesFrame, hover: Option[Hover]): Unit = {
    val d = f.data
    val total = d.series.map(_.points.size).sum
    // Many dots go without rings, a little see-through, so crowds read as density.
    val crowded = total > CrowdedDots
    d.series.indices.foreach { s =>
      val color = seriesColor(d, s)
      val shade = if (crowded) color.deriveColor(0, 1, 1, 0.7) else color
      d.series(s).points.foreach(p => dot(gc, f.xAt(p.x), f.yAt(p.y), shade, ring = !crowded))
    }
    hover.foreach {
      case Hover.Dot(s, i) =>
        val p = d.series(s).points(i)
        val (x, y) = (f.xAt(p.x), f.yAt(p.y))
        gc.setFill(Palette.Background)
        gc.fillOval(x - 8, y - 8, 16, 16)
        gc.setFill(lift(seriesColor(d, s)))
        gc.fillOval(x - 6, y - 6, 12, 12)
      case _ => ()
    }
  }

  /** A dot 8px across, in a 2px ring of the background when `ring`. */
  private def dot(gc: GraphicsContext, x: Double, y: Double, color: Color, ring: Boolean): Unit = {
    if (ring) {
      gc.setFill(Palette.Background)
      gc.fillOval(x - 6, y - 6, 12, 12)
    }
    gc.setFill(color)
    gc.fillOval(x - 4, y - 4, 8, 8)
  }

  private def paintLegend(gc: GraphicsContext, d: ChartData, items: IndexedSeq[LegendItem]): Unit = {
    gc.setFont(LabelFont)
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setTextBaseline(VPos.CENTER)
    items.foreach { item =>
      val cy = item.y + LegendRow / 2
      gc.setFill(item.color)
      gc.setStroke(item.color)
      d.chartType match {
        case ChartType.Line =>
          gc.setLineWidth(2)
          gc.setLineCap(StrokeLineCap.ROUND)
          gc.strokeLine(item.x + 1, cy, item.x + 13, cy)
          gc.setLineWidth(1)
        case ChartType.Scatter => gc.fillOval(item.x + 3, cy - 4, 8, 8)
        case _ => gc.fillRoundRect(item.x + 2, cy - 5, 10, 10, 4, 4)
      }
      gc.setFill(Palette.Text)
      gc.fillText(item.name, item.x + 20, cy)
    }
  }

  private def paintPie(gc: GraphicsContext, d: ChartData, w: Double, h: Double, hover: Option[Hover]): PieFrame = {
    val labels = categories(d)
    val values = d.series.head.points.map(_.y)
    val total = values.sum
    val valueTexts = values.map(ChartScale.value)
    val shares = values.map(ChartScale.percent(_, total))

    // The list of slices beside the pie: a swatch, the name, the value, the share.
    val nameW = math.min(math.max(w * 0.3, 80), (labels :+ d.xTitle).map(GraphCanvas.textWidth(_, LabelFont)).max)
    val valueW = (valueTexts :+ d.yTitle).map(GraphCanvas.textWidth(_, BoldFont)).max
    val shareW = shares.map(GraphCanvas.textWidth(_, LabelFont)).max
    val listW = 20 + nameW + 18 + valueW + 14 + shareW
    val r = math.max(24.0, math.min(h - Pad - 4, w - 3 * Pad - listW - 16) / 2)
    val cx = Pad + 8 + r
    val cy = h / 2
    val listX = cx + r + 8 + 2 * Pad
    val listTop = math.max(Pad / 2, cy - (labels.size + 1) * PieRow / 2)

    val starts = values.scanLeft(0.0)((a, v) => a + 360 * v / total)
    labels.indices.foreach { i =>
      val rr = if (hover.contains(Hover.Slice(i))) r + 5 else r
      gc.setFill(sliceColor(d, i))
      gc.fillArc(cx - rr, cy - rr, 2 * rr, 2 * rr, 90 - starts(i), -(starts(i + 1) - starts(i)), ArcType.ROUND)
    }
    // A 2px gap of the background between slices.
    if (labels.size > 1) {
      gc.setStroke(Palette.Background)
      gc.setLineWidth(2)
      starts.init.foreach { a =>
        val rad = math.toRadians(90 - a)
        gc.strokeLine(cx, cy, cx + (r + 6) * math.cos(rad), cy - (r + 6) * math.sin(rad))
      }
      gc.setLineWidth(1)
    }

    gc.setTextBaseline(VPos.CENTER)
    gc.setFont(LabelFont)
    gc.setFill(Palette.TextMuted)
    gc.setTextAlign(TextAlignment.LEFT)
    gc.fillText(GraphCanvas.fit(d.xTitle, LabelFont, nameW), listX + 20, listTop + PieRow / 2)
    gc.setTextAlign(TextAlignment.RIGHT)
    gc.fillText(d.yTitle, listX + 20 + nameW + 18 + valueW, listTop + PieRow / 2)
    labels.indices.foreach { i =>
      val cyRow = listTop + (i + 1.5) * PieRow
      gc.setFill(sliceColor(d, i))
      gc.fillRoundRect(listX + 2, cyRow - 5, 10, 10, 4, 4)
      gc.setTextAlign(TextAlignment.LEFT)
      gc.setFont(LabelFont)
      gc.setFill(Palette.Text)
      gc.fillText(GraphCanvas.fit(labels(i), LabelFont, nameW), listX + 20, cyRow)
      gc.setTextAlign(TextAlignment.RIGHT)
      gc.setFont(BoldFont)
      gc.fillText(valueTexts(i), listX + 20 + nameW + 18 + valueW, cyRow)
      gc.setFont(LabelFont)
      gc.setFill(Palette.TextMuted)
      gc.fillText(shares(i), listX + listW, cyRow)
    }

    val f = PieFrame(d, cx, cy, r, starts)
    hover.foreach(hv => tip(f, hv).foreach { case (title, rows) => paintTip(gc, w, h, title, rows) })
    f
  }

  /** The pointer's reading: a box by the pointer with a title (the X, or the
    * slice), then a line per series, its value first. */
  private def paintTip(gc: GraphicsContext, w: Double, h: Double, title: String, rows: Seq[TipRow]): Unit = {
    val titleW = GraphCanvas.textWidth(title, TickFont)
    val rowWs = rows.map(r => 18 + GraphCanvas.textWidth(r.value, BoldFont) +
      (if (r.name.isEmpty) 0 else 8 + GraphCanvas.textWidth(r.name, TickFont)))
    val tw = 20 + (titleW +: rowWs).max
    val th = 14 + 15 + rows.size * 19.0
    val (px, py) = pointer
    val tx = math.max(4, if (px + 16 + tw <= w - 4) px + 16 else px - 16 - tw)
    val ty = math.max(4, math.min(h - th - 4, py - th / 2))
    gc.setFill(Palette.SurfaceRaised)
    gc.fillRoundRect(tx, ty, tw, th, 12, 12)
    gc.setStroke(Palette.Border)
    gc.setLineWidth(1)
    gc.strokeRoundRect(tx + 0.5, ty + 0.5, tw - 1, th - 1, 12, 12)
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setTextBaseline(VPos.CENTER)
    gc.setFont(TickFont)
    gc.setFill(Palette.TextMuted)
    gc.fillText(title, tx + 10, ty + 7 + 8)
    rows.iterator.zipWithIndex.foreach { case (row, i) =>
      val cy = ty + 7 + 15 + i * 19.0 + 10
      row.color.foreach { c =>
        gc.setStroke(c)
        gc.setLineWidth(2)
        gc.setLineCap(StrokeLineCap.ROUND)
        gc.strokeLine(tx + 11, cy, tx + 21, cy)
        gc.setLineWidth(1)
      }
      gc.setFont(BoldFont)
      gc.setFill(Palette.Text)
      gc.fillText(row.value, tx + 28, cy)
      if (row.name.nonEmpty) {
        gc.setFont(TickFont)
        gc.setFill(Palette.TextMuted)
        gc.fillText(row.name, tx + 28 + GraphCanvas.textWidth(row.value, BoldFont) + 8, cy)
      }
    }
  }

  // ==================== layout ====================

  private def axesFrame(d: ChartData, w: Double, h: Double): AxesFrame = {
    // A short chart leaves the axes untitled, as the choices above it name
    // them, and keeps close to its edges, so its plot keeps what height
    // there is.
    val titled = h >= CompactHeight
    val edge = if (titled) Pad else Pad / 2
    val legend = legendItems(d, w, edge)
    val legendH = if (legend.isEmpty) 0.0 else legend.map(_.y).max - edge + LegendRow + 10
    val yTitleTop = edge + legendH
    val plotY = yTitleTop + (if (titled && d.yTitle.nonEmpty) 26 else 6)
    val xTitleH = if (titled && d.xTitle.nonEmpty) 20.0 else 0.0

    // About a tick every 44px of the height the plot will have.
    val (yMin, yMax) = yExtent(d)
    val roughH = h - plotY - edge - xTitleH - 24
    val y = ChartScale.niceTicks(yMin, yMax, math.max(2, (roughH / 44).toInt + 1))
    val yLabels = ChartScale.tickLabels(y)
    val left = Pad + yLabels.map(GraphCanvas.textWidth(_, TickFont)).max + 10
    val (xLower, xUpper) = xExtent(d)
    val maxLabel = if (titled) MaxLabel else MaxLabel / 2
    val firstTry = xAxis(d, left, w - left - Pad - 6, xLower, xUpper, maxLabel)
    // Labels set aslant hang left of their band: the first must not leave the canvas.
    val plotX = if (!firstTry.rotated) left else math.max(left, Pad + firstTry.overhang)
    val plotW = math.max(40.0, w - plotX - Pad - 6)
    val axis = if (plotX == left) firstTry else xAxis(d, plotX, plotW, xLower, xUpper, maxLabel)
    val plotH = math.max(30.0, h - plotY - edge - xTitleH - axis.height)
    AxesFrame(d, legend, titled, yTitleTop, plotX, plotY, plotW, plotH, xLower, xUpper, y, yLabels, axis)
  }

  private def legendItems(d: ChartData, w: Double, top: Double): IndexedSeq[LegendItem] =
    if (d.series.size < 2) IndexedSeq.empty
    else {
      var x = Pad
      var y = top
      d.series.indices.map { s =>
        val name = GraphCanvas.fit(d.series(s).name, LabelFont, 240)
        val itemW = 20 + GraphCanvas.textWidth(name, LabelFont)
        if (x > Pad && x + itemW > w - Pad) {
          x = Pad
          y += LegendRow
        }
        val item = LegendItem(name, seriesColor(d, s), x, y)
        x += itemW + 18
        item
      }
    }

  /** The least and greatest value up Y; a bar or an area stands on zero, so
    * it is in range. */
  private def yExtent(d: ChartData): (Double, Double) = {
    val ys = d.series.iterator.flatMap(_.points.iterator.map(_.y)).toSeq
    val (lo, hi) = if (ys.isEmpty) (0.0, 1.0) else (ys.min, ys.max)
    if (d.chartType == ChartType.Bar || d.chartType == ChartType.Area) (math.min(lo, 0.0), math.max(hi, 0.0))
    else (lo, hi)
  }

  /** The range along X: the values' own, widened around one value, and a
    * little either side for dots, which would be cut at the edges. */
  private def xExtent(d: ChartData): (Double, Double) = d.xScale match {
    case _: XScale.Categories => (0.0, 1.0)
    case scale =>
      val xs = d.series.iterator.flatMap(_.points.iterator.map(_.x)).toSeq
      val (lo, hi) = (xs.min, xs.max)
      val (a, b) =
        if (hi > lo) (lo, hi)
        else scale match {
          case XScale.Time(true) => (lo - Day, hi + Day)
          case _: XScale.Time => (lo - Hour, hi + Hour)
          case _ => (lo - math.max(1.0, math.abs(lo) / 10), hi + math.max(1.0, math.abs(hi) / 10))
        }
      if (d.chartType == ChartType.Scatter) (a - (b - a) * 0.03, b + (b - a) * 0.03) else (a, b)
  }

  /** X's labels for a plot `plotW` wide at `plotX`: a band's label under
    * each band, set aslant and only every so many when they would run into
    * each other; ticks along a number or a time axis, as many as fit. */
  private def xAxis(d: ChartData, plotX: Double, plotW: Double, lower: Double, upper: Double,
      maxLabel: Double): XAxis = {
    def at(v: Double) = plotX + (v - lower) / (upper - lower) * plotW
    d.xScale match {
      case XScale.Categories(labels, _) =>
        val slot = plotW / labels.size
        val texts = labels.map(GraphCanvas.fit(_, TickFont, maxLabel))
        val widths = texts.map(GraphCanvas.textWidth(_, TickFont))
        val widest = widths.max
        def label(i: Int) = XLabel(texts(i), plotX + (i + 0.5) * slot)
        if (widest + 8 <= slot) XAxis(texts.indices.map(label), rotated = false, 20, 0)
        else {
          val every = math.max(1, math.ceil(LabelSpacing / slot).toInt)
          XAxis(texts.indices.filter(_ % every == 0).map(label), rotated = true,
            12 + widest * Sin45 + 6, widths.head * Sin45 - slot / 2)
        }
      case XScale.Numbers =>
        fitted(plotW) { most =>
          val ticks = ChartScale.niceTicks(lower, upper, most)
          val labels = ChartScale.tickLabels(ticks)
          ticks.values.indices.filter(i => ticks.values(i) >= lower && ticks.values(i) <= upper)
            .map(i => XLabel(labels(i), at(ticks.values(i))))
        }
      case XScale.Time(dateOnly) =>
        fitted(plotW) { most =>
          val ticks = ChartScale.timeTicks(lower, upper, most, dateOnly)
          ticks.values.indices.map(i => XLabel(ticks.labels(i), at(ticks.values(i))))
        }
    }
  }

  /** The most ticks along `plotW` whose labels stand clear of each other. */
  private def fitted(plotW: Double)(labels: Int => IndexedSeq[XLabel]): XAxis = {
    var most = math.max(2, (plotW / 64).toInt)
    var shown = labels(most)
    def width(l: XLabel) = GraphCanvas.textWidth(l.text, TickFont)
    def clear(ls: IndexedSeq[XLabel]) =
      ls.sliding(2).forall {
        case Seq(a, b) => b.x - a.x >= (width(a) + width(b)) / 2 + 14
        case _ => true
      }
    while (most > 2 && !clear(shown)) {
      most -= 1
      shown = labels(most)
    }
    XAxis(shown, rotated = false, 20, 0)
  }

  // ==================== the pointer ====================

  private def hit(f: Frame, mx: Double, my: Double): Option[Hover] = f match {
    case a: AxesFrame =>
      val d = a.data
      val inside = mx >= a.plotX - 8 && mx <= a.plotX + a.plotW + 8 && my >= a.plotY - 8 && my <= a.plotY + a.plotH + 8
      if (!inside) None
      else d.chartType match {
        case ChartType.Bar =>
          val band = math.floor((mx - a.plotX) / a.slotW).toInt
          Some(Hover.Band(math.max(0, math.min(categories(d).size - 1, band))))
        case ChartType.Line | ChartType.Area =>
          val xs = d.series.iterator.flatMap(_.points.iterator.map(_.x))
          if (xs.isEmpty) None else Some(Hover.AtX(xs.minBy(x => math.abs(a.xAt(x) - mx))))
        case _ =>
          var best: Option[Hover] = None
          var bestDistance = Reach * Reach
          d.series.indices.foreach { s =>
            val points = d.series(s).points
            points.indices.foreach { i =>
              val dx = a.xAt(points(i).x) - mx
              val dy = a.yAt(points(i).y) - my
              val distance = dx * dx + dy * dy
              if (distance <= bestDistance) {
                bestDistance = distance
                best = Some(Hover.Dot(s, i))
              }
            }
          }
          best
      }
    case p: PieFrame =>
      val dx = mx - p.cx
      val dy = my - p.cy
      if (dx * dx + dy * dy > (p.r + 5) * (p.r + 5)) None
      else {
        // Degrees clockwise from twelve o'clock, as the slices go.
        val a = ((90 - math.toDegrees(math.atan2(-dy, dx))) % 360 + 360) % 360
        val i = p.starts.lastIndexWhere(_ <= a)
        Some(Hover.Slice(math.max(0, math.min(p.starts.size - 2, i))))
      }
  }

  /** What the pointer's reading says: a title, and a line per series. */
  private def tip(f: Frame, hover: Hover): Option[(String, Seq[TipRow])] = {
    val d = f.data
    def name(s: Int) = if (d.series.size > 1) d.series(s).name else d.yTitle
    hover match {
      case Hover.Band(band) =>
        val rows = d.series.indices.flatMap(s => d.series(s).points.find(_.x == band)
          .map(p => TipRow(Some(seriesColor(d, s)), ChartScale.value(p.y), name(s))))
        Some(categories(d)(band) -> rows)
      case Hover.AtX(x) =>
        val rows = d.series.indices.flatMap(s => d.series(s).points.find(_.x == x)
          .map(p => TipRow(Some(seriesColor(d, s)), ChartScale.value(p.y), name(s))))
        Some(xText(d, x) -> rows)
      case Hover.Dot(s, i) =>
        val p = d.series(s).points(i)
        Some(xText(d, p.x) -> Seq(TipRow(Some(seriesColor(d, s)), ChartScale.value(p.y), name(s))))
      case Hover.Slice(i) =>
        val values = d.series.head.points.map(_.y)
        Some(categories(d)(i) -> Seq(TipRow(Some(sliceColor(d, i)), ChartScale.value(values(i)),
          s"${ChartScale.percent(values(i), values.sum)} of ${d.yTitle}")))
    }
  }

  private def xText(d: ChartData, x: Double): String = d.xScale match {
    case XScale.Categories(labels, _) => labels(x.toInt)
    case XScale.Numbers => ChartScale.value(x)
    case XScale.Time(dateOnly) => ChartScale.timeValue(x, dateOnly)
  }
}

object ChartCanvas {
  import Theme.Palette

  /** The series' colours, in the order series take them. Eight hues whose
    * neighbours stay apart for colour-blind eyes as well as others, each at
    * least 3:1 against the background. They are not the status colours. */
  val SeriesColors: IndexedSeq[Color] =
    IndexedSeq("#3987e5", "#d95926", "#199e70", "#c98500", "#d55181", "#008300", "#9085e9", "#e66767").map(Color.web)

  /** Other's colour: the series, or the slices, past the first seven. */
  val OtherColor: Color = Palette.TextSubtle

  private val TickFont: Font = Font.font("System", FontWeight.NORMAL, 11)
  private val LabelFont: Font = Font.font("System", FontWeight.NORMAL, 12)
  private val BoldFont: Font = Font.font("System", FontWeight.SEMI_BOLD, 12)
  private val Pad = 16.0
  /** Below this height a chart leaves its axes untitled; below MinHeight, or
    * narrower than MinWidth, it is not drawn at all. */
  private val CompactHeight = 240.0
  private val MinHeight = 70.0
  private val MinWidth = 120.0
  private val LegendRow = 18.0
  private val PieRow = 22.0
  private val MaxBar = 24.0
  private val EndRadius = 4.0
  private val MaxLabel = 120.0
  /** How far apart labels set aslant stand, at the least. */
  private val LabelSpacing = 20.0
  private val AreaOpacity = 0.14
  private val CrowdedDots = 1500
  /** How far from a dot the pointer still reads it. */
  private val Reach = 24.0
  private val Sin45 = math.sqrt(0.5)
  private val Day = 86400000.0
  private val Hour = 3600000.0

  private def seriesColor(d: ChartData, s: Int): Color =
    if (d.series(s).other) OtherColor else SeriesColors(s % SeriesColors.size)

  private def sliceColor(d: ChartData, i: Int): Color = d.xScale match {
    case XScale.Categories(labels, true) if i == labels.size - 1 => OtherColor
    case _ => SeriesColors(i % SeriesColors.size)
  }

  private def categories(d: ChartData): IndexedSeq[String] = d.xScale match {
    case XScale.Categories(labels, _) => labels
    case _ => IndexedSeq.empty
  }

  /** A 1px line at `v` drawn on whole pixels. */
  private def crisp(v: Double): Double = math.floor(v) + 0.5

  /** `c` lit a little, for the mark under the pointer. */
  private def lift(c: Color): Color = c.interpolate(Color.WHITE, 0.22)

  private sealed trait Hover
  private object Hover {
    final case class Band(index: Int) extends Hover
    final case class AtX(x: Double) extends Hover
    final case class Dot(series: Int, point: Int) extends Hover
    final case class Slice(index: Int) extends Hover
  }

  private final case class TipRow(color: Option[Color], value: String, name: String)
  private final case class LegendItem(name: String, color: Color, x: Double, y: Double)
  private final case class XLabel(text: String, x: Double)

  /** X's labels, whether they are set aslant, the height they take under
    * the plot, and how far the first reaches left of its band's centre. */
  private final case class XAxis(labels: IndexedSeq[XLabel], rotated: Boolean, height: Double, overhang: Double)

  /** Where a paint put a chart. */
  private sealed trait Frame { def data: ChartData }

  private final case class AxesFrame(
      data: ChartData,
      legend: IndexedSeq[LegendItem],
      titled: Boolean,
      yTitleTop: Double,
      plotX: Double,
      plotY: Double,
      plotW: Double,
      plotH: Double,
      xLower: Double,
      xUpper: Double,
      y: ChartScale.Ticks,
      yLabels: IndexedSeq[String],
      xAxis: XAxis) extends Frame {

    def slotW: Double = plotW / math.max(1, categories(data).size)

    def xAt(x: Double): Double = data.xScale match {
      case _: XScale.Categories => plotX + (x + 0.5) * slotW
      case _ => plotX + (x - xLower) / (xUpper - xLower) * plotW
    }

    def yAt(v: Double): Double = plotY + plotH - (v - y.lower) / (y.upper - y.lower) * plotH

    /** Where bars and areas stand: zero when it is in range, else the bottom. */
    def baseline: Double = math.max(y.lower, math.min(y.upper, 0.0))
  }

  /** A pie's centre and radius, and where each slice starts, in degrees
    * clockwise from twelve o'clock (the last entry is 360). */
  private final case class PieFrame(data: ChartData, cx: Double, cy: Double, r: Double, starts: IndexedSeq[Double])
    extends Frame
}
