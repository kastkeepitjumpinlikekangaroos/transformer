package com.transformer.viewer

import atlantafx.base.theme.Styles
import com.transformer.gui.{GraphCanvas, GraphOverlay, Theme}
import com.transformer.job.TaskRunStatus

import javafx.geometry.{Insets, Pos, VPos}
import javafx.scene.canvas.GraphicsContext
import javafx.scene.control.{Label, ToggleButton, ToggleGroup, Tooltip}
import javafx.scene.input.{MouseEvent, ScrollEvent}
import javafx.scene.layout.{HBox, Region, StackPane, VBox}
import javafx.scene.paint.Color
import javafx.scene.text.{Font, FontWeight, TextAlignment}

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import scala.collection.mutable

/** The lineage of what an inspector shows, drawn on a [[GraphCanvas]]: each
  * dataset a box with its name and run in its header, its inputs and tables
  * as cards inside, as the job GUI draws a job's DAG.
  *
  * It draws its subject's whole lineage, everything the subject is made from
  * and everything made from it, across jobs; or, with [[setWholeFolder]],
  * every dataset of the folder, the subject's lineage lit and the rest
  * dimmed. The subject has the selection ring. An edge within a box is what
  * a table's SQL reads; an edge between boxes, in the input colour, is one
  * job reading another's table. A click on a node lights that node's own
  * lineage; a double-click on a node or a dataset's box opens it
  * ([[setOnOpen]]).
  *
  * The graph opens fitted to the canvas, every upstream and downstream of
  * the subject in view, and stays fitted as the canvas resizes until the
  * user pans or zooms; Fit fits it again. Zoomed out, as a long lineage is
  * to fit, a card shows its name alone, set large enough to read and
  * wrapped onto a second line where it must be, its status still the colour
  * of its edge.
  */
final class LineageCanvas(session: ViewerSession) extends GraphCanvas {
  import GraphCanvas.{StatusFont, TagFont, TitleFont}
  import LineageCanvas._
  import Theme.Palette

  private var onOpen: ViewerSelection => Unit = _ => ()
  def setOnOpen(f: ViewerSelection => Unit): Unit = onOpen = f

  private var subject: Option[ViewerSelection] = None
  private var whole = false
  private var layout: Option[LineageLayout] = None
  /** The subject's lineage, which the whole folder lights. */
  private var subjectLineage: Set[NodeId] = Set.empty
  /** A node clicked in the graph, whose own lineage is lit. */
  private var picked: Option[NodeId] = None
  /** Names as [[wrapped]] splits them, by name, font size and width: kept
    * while the zoom stays, as it does through a pan. */
  private val wraps = mutable.HashMap.empty[(String, Double, Double), Seq[String]]
  /** Whether the graph is fitted again as the canvas resizes: until the user
    * pans or zooms, and again after Fit. */
  private var fitted = true

  widthProperty().addListener((_, _, _) => if (fitted) { fitToWindow(); render() })
  heightProperty().addListener((_, _, _) => if (fitted) { fitToWindow(); render() })
  addEventHandler(MouseEvent.MOUSE_DRAGGED, (_: MouseEvent) => fitted = false)
  addEventHandler(ScrollEvent.SCROLL, (_: ScrollEvent) => fitted = false)

  override def zoomIn(): Unit = { fitted = false; super.zoomIn() }
  override def zoomOut(): Unit = { fitted = false; super.zoomOut() }
  override def fitToWindow(): Unit = { fitted = true; super.fitToWindow() }

  def wholeFolder: Boolean = whole

  /** Draw `sel`'s lineage, fitted to the canvas. */
  def show(sel: ViewerSelection): Unit = {
    subject = Some(sel)
    picked = None
    relayout()
  }

  /** Draw every dataset of the folder (true), or the subject's lineage alone. */
  def setWholeFolder(on: Boolean): Unit = if (on != whole) {
    whole = on
    relayout()
  }

  /** Lay the subject out again in the lineage the session holds now: another
    * run shown, or the folder read again. */
  def refresh(): Unit = {
    picked = picked.filter(n => session.lineage.exists(_.nodes.contains(n)))
    relayout()
  }

  /** The nodes the graph draws: the subject's lineage, or every node. */
  def drawn: Set[NodeId] = layout.map(_.nodeBoxes.keySet).getOrElse(Set.empty)

  /** The datasets the graph draws a box for. */
  def drawnDatasets: Set[Int] = layout.map(_.datasetBoxes.keySet).getOrElse(Set.empty)

  private def relayout(): Unit = {
    val lineage = session.lineage
    subjectLineage = (for (l <- lineage; s <- subject) yield s match {
      case ViewerSelection.OfNode(n) => l.around(n)
      case ViewerSelection.OfDataset(d) => l.aroundDataset(d)
    }).getOrElse(Set.empty)
    layout =
      if (whole) session.layout
      else if (subject.isEmpty) None
      else lineage.map(l => LineageLayout.compute(l, Some(subjectLineage)))
    fitToWindow()
    render()
  }

  protected def contentSize: Option[(Double, Double)] =
    layout.filter(_.datasetBoxes.nonEmpty).map(l => (l.width, l.height))

  protected def hint: String =
    "Drag to pan · scroll to zoom · click a node to light its lineage · double-click it to open it"

  protected def paintEmpty(gc: GraphicsContext, w: Double, h: Double): Unit =
    paintMessage(gc, w, h, "No lineage to draw", Palette.Text, Seq(subject match {
      case Some(ViewerSelection.OfDataset(d)) =>
        s"${session.datasets.lift(d).map(_.name).getOrElse("This dataset")} has no inputs or tables in the run shown."
      case _ => "There is nothing here to draw."
    }))

  // -------------------- painting --------------------

  protected def paintContent(gc: GraphicsContext): Unit = for {
    lineage <- session.lineage
    layout <- layout
  } {
    val lit = litNodes(lineage)
    // Zoomed out, each card shows its name alone, in this font.
    val names = Option.when(zoom < CompactBelow)(
      Font.font(TitleFont.getFamily, FontWeight.SEMI_BOLD, math.min(CompactNameMax, CompactNamePx / zoom)))
    layout.datasetBoxes.foreach { case (d, box) => drawDataset(gc, lineage, d, box, names.isDefined) }
    drawEdges(gc, lineage, layout, lit)
    layout.nodeBoxes.foreach { case (n, b) =>
      gc.setGlobalAlpha(if (lit.forall(_.contains(n))) 1.0 else Dimmed)
      val ringed = subject.contains(ViewerSelection.OfNode(n)) || picked.contains(n)
      n match {
        case t: NodeId.Table => drawTable(gc, lineage, t, b, ringed, names)
        case i: NodeId.Input => drawInput(gc, lineage, i, b, ringed, names)
      }
      gc.setGlobalAlpha(1.0)
    }
  }

  /** The nodes drawn at full strength, the rest dimmed: a picked node's
    * lineage, else, across the whole folder, the subject's; None when
    * everything is drawn alike. */
  private def litNodes(lineage: Lineage): Option[Set[NodeId]] =
    picked.map(lineage.around).orElse(if (whole && subject.isDefined) Some(subjectLineage) else None)

  private def drawDataset(gc: GraphicsContext, lineage: Lineage, d: Int, box: Box, compact: Boolean): Unit = {
    val dataset = lineage.folder.datasets(d)
    val run = lineage.shownRuns(d)
    val selected = subject.contains(ViewerSelection.OfDataset(d))
    gc.setFill(BoxFill)
    gc.fillRoundRect(box.x, box.y, box.width, box.height, BoxRadius * 2, BoxRadius * 2)
    gc.setStroke(if (selected) Palette.Accent else Palette.Border)
    gc.setLineWidth(if (selected) 2.0 else 1.0)
    gc.strokeRoundRect(box.x, box.y, box.width, box.height, BoxRadius * 2, BoxRadius * 2)

    val (color, verdict) = runStatus(run)
    gc.setFill(color)
    gc.fillOval(box.x + 18, box.y + 17, 8, 8)
    // Zoomed out, the name and the line under it are set larger, to read.
    val (nameFont, statusFont, statusY) =
      if (compact) (Font.font(DatasetFont.getFamily, FontWeight.BOLD, math.min(22.0, 10.5 / zoom)),
        Font.font(StatusFont.getFamily, math.min(15.0, 7.0 / zoom)), box.y + 44)
      else (DatasetFont, StatusFont, box.y + 41)
    val nameWidth = box.width - 52
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setFill(Palette.Text)
    gc.setFont(nameFont)
    gc.setTextBaseline(VPos.CENTER)
    gc.fillText(fit(dataset.name, nameFont, nameWidth), box.x + 34, box.y + 21)
    gc.setTextBaseline(VPos.BASELINE)
    gc.setFill(Palette.TextMuted)
    gc.setFont(statusFont)
    // Runs are numbered oldest first: the latest of three is run 3 of 3. A
    // run without lineage says so first, where a narrow box cannot cut it.
    val runs = if (dataset.runs.size > 1) s" · run ${dataset.runs.size - dataset.runs.indexOf(run)} of ${dataset.runs.size}" else ""
    val lineageNote = if (run.hasLineage) "" else "lineage not recorded · "
    gc.fillText(fit(s"$lineageNote$verdict · ${RunTime.format(run.record.executionTime)}$runs", statusFont, nameWidth),
      box.x + 34, statusY)
  }

  private def drawEdges(gc: GraphicsContext, lineage: Lineage, layout: LineageLayout, lit: Option[Set[NodeId]]): Unit = {
    val strong = scala.collection.mutable.ArrayBuffer.empty[(Box, Box, Color)]
    gc.setLineWidth(1.5)
    lineage.edges.foreach { case (from, to) =>
      for (a <- layout.nodeBoxes.get(from); b <- layout.nodeBoxes.get(to)) {
        val across = from.dataset != to.dataset
        val inLit = lit.exists(f => f.contains(from) && f.contains(to))
        val color =
          if (across) Palette.Input
          else from match {
            case _: NodeId.Input => InputEdge
            case _ => Palette.Border
          }
        if (inLit) strong += ((a, b, if (across) Palette.Input else Palette.Accent))
        else {
          gc.setGlobalAlpha(if (lit.isEmpty) 1.0 else Dimmed)
          drawEdge(gc, a.right, a.centerY, b.x, b.centerY, if (across && lit.isEmpty) AcrossEdge else color)
          gc.setGlobalAlpha(1.0)
        }
      }
    }
    gc.setLineWidth(2.2)
    strong.foreach { case (a, b, c) => drawEdge(gc, a.right, a.centerY, b.x, b.centerY, c) }
  }

  /** A table's card: its name, and its status and rows; or, `compact`, its
    * name alone in that font. */
  private def drawTable(gc: GraphicsContext, lineage: Lineage, n: NodeId.Table, b: Box, ringed: Boolean,
      compact: Option[Font]): Unit = {
    val (accent, line) = lineage.shownRuns(n.dataset).table(n.name) match {
      case Some(t) => tableStatus(t.status, t.summary.rowsProduced)
      case None => (Palette.TextSubtle, "")
    }
    drawCard(gc, b.x, b.y, b.width, b.height, accent, ringed)
    val textX = b.x + 18
    val textW = b.right - textX - 10
    compact match {
      case Some(font) => drawName(gc, n.name, font, textX, textW, b)
      case None =>
        gc.setTextAlign(TextAlignment.LEFT)
        gc.setFill(Palette.Text)
        gc.setFont(TitleFont)
        gc.fillText(fit(n.name, TitleFont, textW), textX, b.y + 25)
        gc.setFill(accent)
        gc.fillOval(textX, b.y + 37, 7, 7)
        gc.setFill(Palette.TextMuted)
        gc.setFont(StatusFont)
        gc.fillText(fit(line, StatusFont, textW - 12), textX + 12, b.y + 44)
    }
  }

  /** An input's card: its format, its name, and its file or the table it
    * reads; or, `compact`, its name alone in that font. */
  private def drawInput(gc: GraphicsContext, lineage: Lineage, n: NodeId.Input, b: Box, ringed: Boolean,
      compact: Option[Font]): Unit = {
    val record = lineage.inputRecord(n)
    val footer = lineage.sources.get(n) match {
      case Some(InputSource.Tables(ms)) =>
        ms.headOption.map(m => s"from ${lineage.folder.datasets(m.dataset).name}.${m.table}").getOrElse("")
      case _ => record.map(r => basename(r.path)).getOrElse("")
    }
    drawCard(gc, b.x, b.y, b.width, b.height, Palette.Input, ringed)
    drawCylinder(gc, b.x + 22, b.centerY, Palette.Input)
    val textX = b.x + 38
    val textW = b.right - textX - 10
    compact match {
      case Some(font) => drawName(gc, n.name, font, textX, textW, b)
      case None =>
        gc.setTextAlign(TextAlignment.LEFT)
        gc.setFill(Palette.Input)
        gc.setFont(TagFont)
        gc.fillText(s"INPUT · ${record.map(_.format.toUpperCase(java.util.Locale.ROOT)).getOrElse("")}", textX, b.y + 17)
        gc.setFill(Palette.Text)
        gc.setFont(TitleFont)
        gc.fillText(fit(n.name, TitleFont, textW), textX, b.y + 34)
        gc.setFill(Palette.TextMuted)
        gc.setFont(StatusFont)
        gc.fillText(fit(footer, StatusFont, textW), textX, b.y + 50)
    }
  }

  /** `name` alone in `font`, from `x` and no wider than `width`, in one line
    * or two, centred down card `b`. */
  private def drawName(gc: GraphicsContext, name: String, font: Font, x: Double, width: Double, b: Box): Unit = {
    val lines = wrapped(name, font, width)
    val lineHeight = font.getSize * 1.15
    val top = b.centerY - lineHeight * lines.size / 2.0
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setTextBaseline(VPos.CENTER)
    gc.setFill(Palette.Text)
    gc.setFont(font)
    lines.iterator.zipWithIndex.foreach { case (line, i) => gc.fillText(line, x, top + lineHeight * (i + 0.5)) }
    gc.setTextBaseline(VPos.BASELINE)
  }

  /** `s` in at most two lines of `font` no wider than `width`, broken after
    * an underscore, a dot, a dash or a space where one falls on the first
    * line; the second line ends in an ellipsis when the rest does not fit. */
  private def wrapped(s: String, font: Font, width: Double): Seq[String] = {
    if (wraps.size > 4096) wraps.clear()
    wraps.getOrElseUpdate((s, font.getSize, width),
      if (textWidth(s, font) <= width) Seq(s)
      else {
        val n = fitting(s, font, width)
        val break = s.lastIndexWhere(c => c == '_' || c == '.' || c == '-' || c == ' ', n - 1)
        val cut = if (break >= 1) break + 1 else n
        Seq(s.substring(0, cut), fit(s.substring(cut), font, width))
      })
  }

  /** How many of `s`'s first characters fit in `width` in `font`; at least
    * one. */
  private def fitting(s: String, font: Font, width: Double): Int = {
    var lo = 1
    var hi = s.length
    while (lo < hi) {
      val mid = (lo + hi + 1) / 2
      if (textWidth(s.substring(0, mid), font) <= width) lo = mid else hi = mid - 1
    }
    lo
  }

  private def basename(path: String): String = {
    val cleaned = path.replace('\\', '/').stripSuffix("/")
    cleaned.substring(cleaned.lastIndexOf('/') + 1)
  }

  // -------------------- interaction --------------------

  protected def clicked(wx: Double, wy: Double, clicks: Int): Unit =
    layout.flatMap(_.hitTest(wx, wy)) match {
      case Some(Right(node)) =>
        if (clicks >= 2) onOpen(ViewerSelection.OfNode(node))
        else {
          picked = if (picked.contains(node)) None else Some(node)
          render()
        }
      case Some(Left(d)) =>
        if (clicks >= 2) onOpen(ViewerSelection.OfDataset(d))
        else unpick()
      case None => unpick()
    }

  private def unpick(): Unit = if (picked.nonEmpty) {
    picked = None
    render()
  }
}

object LineageCanvas {
  import Theme.Palette

  private val DatasetFont: Font = Font.font("System", FontWeight.BOLD, 14)
  private val BoxRadius = 14.0
  private val BoxFill: Color = Palette.Background.interpolate(Palette.Surface, 0.55)
  private val InputEdge: Color = Palette.Input.deriveColor(0, 1, 0.75, 0.45)
  private val AcrossEdge: Color = Palette.Input.deriveColor(0, 1, 1, 0.8)
  /** How strongly what is outside the lit lineage is drawn. */
  private val Dimmed = 0.28
  /** Below this zoom a card shows its name alone ([[LineageCanvas]]): a
    * card's 13px name would read at under 10px. */
  private val CompactBelow = 0.75
  /** The size on screen a card's name is set in when it shows alone, unless
    * that is more than [[CompactNameMax]] on the card. */
  private val CompactNamePx = 9.5
  /** The most a name may take on the card, two lines of it still fitting
    * its height. */
  private val CompactNameMax = 24.0

  val RunTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  /** A run's colour and verdict: green when every task succeeded, amber with
    * warnings, red when a task did not. */
  def runStatus(run: DatasetRun): (Color, String) =
    if (!run.record.succeeded) (Palette.Danger, "failed")
    else if (run.record.warnings.nonEmpty) (Palette.Warning, s"succeeded with ${run.record.warnings.size} warning(s)")
    else (Palette.Success, "succeeded")

  /** Why `run` of `dataset` shows no lineage: it was written before runs
    * recorded what each table reads. */
  def notRecorded(dataset: Dataset, run: DatasetRun): String =
    s"${dataset.name}'s run of ${RunTime.format(run.record.executionTime)} was written before runs recorded what each " +
      "table reads, so what its tables are made from, and what reads them, is not known. The job's next run records it."

  /** A table's colour and status line. */
  def tableStatus(status: TaskRunStatus, rows: Long): (Color, String) = status match {
    case TaskRunStatus.Running => (Palette.Warning, "unfinished")
    case TaskRunStatus.Succeeded => (Palette.Success, f"$rows%,d rows")
    case TaskRunStatus.ValidationFailed => (Palette.Warning, f"validation failed · $rows%,d rows")
    case TaskRunStatus.Failed => (Palette.Danger, "failed")
    case TaskRunStatus.Skipped => (Palette.TextSubtle.darker(), "skipped")
  }
}

/** The lineage canvas with zoom controls and, at the top left, the switch
  * between the subject's lineage and the whole folder, counts of what the
  * graph holds, and, under them, a notice when a run it draws did not record
  * its lineage, which is why its tables stand alone. */
final class LineageOverlay private (session: ViewerSession, val canvas: LineageCanvas) extends GraphOverlay(canvas) {
  import Theme.Palette

  def this(session: ViewerSession) = this(session, new LineageCanvas(session))

  private var subject: Option[ViewerSelection] = None

  private val noticeTitle = new Label("Lineage not recorded")
  noticeTitle.getStyleClass.add("notice-title")
  private val noticeText = new Label()
  noticeText.getStyleClass.add("notice-text")
  noticeText.setWrapText(true)
  private val notice = new VBox(noticeTitle, noticeText)
  notice.getStyleClass.add("lineage-notice")
  notice.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE)
  notice.setPrefWidth(460)
  notice.setVisible(false)
  StackPane.setAlignment(notice, Pos.TOP_LEFT)
  StackPane.setMargin(notice, new Insets(54, 12, 12, 12))
  getChildren.add(notice)

  private val group = new ToggleGroup()
  private def toggle(text: String, pill: String, tip: String): ToggleButton = {
    val b = new ToggleButton(text)
    b.getStyleClass.addAll(Styles.SMALL, pill)
    b.setToggleGroup(group)
    b.setFocusTraversable(false)
    b.setTooltip(new Tooltip(tip))
    b
  }
  private val itsLineage = toggle("Its lineage", Styles.LEFT_PILL,
    "Only what it is made from and what is made from it, across jobs")
  private val wholeFolder = toggle("Whole folder", Styles.RIGHT_PILL,
    "Every dataset in the folder, its lineage lit")
  itsLineage.setSelected(true)
  private val switch = new HBox(itsLineage, wholeFolder)
  group.selectedToggleProperty().addListener((_, before, now) =>
    if (now == null) before.setSelected(true) // one of the two is always on
    else {
      canvas.setWholeFolder(now eq wholeFolder)
      refresh()
    })

  /** Draw `sel`'s lineage. */
  def show(sel: ViewerSelection): Unit = {
    subject = Some(sel)
    canvas.show(sel)
    refresh()
  }

  /** Lay out again in the lineage the session holds now. */
  def update(): Unit = {
    canvas.refresh()
    refresh()
  }

  private def refresh(): Unit = {
    summary.getChildren.setAll(switch)
    val lineage = session.lineage
    setControlsVisible(canvas.drawn.nonEmpty)
    val unrecorded = lineage.toSeq.flatMap(l => canvas.drawnDatasets.toSeq.sorted.filterNot(l.shownRuns(_).hasLineage)
      .map(d => (l.folder.datasets(d), l.shownRuns(d))))
    notice.setVisible(unrecorded.nonEmpty)
    unrecorded match {
      case Seq((dataset, run)) => noticeText.setText(LineageCanvas.notRecorded(dataset, run))
      case _ if unrecorded.nonEmpty =>
        noticeText.setText(s"The runs shown of ${unrecorded.map(_._1.name).mkString(", ")} were written before runs " +
          "recorded what each table reads, so their tables stand without what they read. Each job's next run records it.")
      case _ => ()
    }
    for (l <- lineage; s <- subject) {
      if (canvas.wholeFolder) {
        val tables = l.shownRuns.flatMap(_.tables)
        def inStatus(st: TaskRunStatus): Int = tables.count(_.status == st)
        count(if (l.shownRuns.size == 1) "dataset" else "datasets", Palette.Accent, l.shownRuns.size)
        count(if (tables.size == 1) "table" else "tables", Palette.Success, tables.size)
        count("failed", Palette.Danger, inStatus(TaskRunStatus.Failed))
        count("validation failed", Palette.Warning, inStatus(TaskRunStatus.ValidationFailed))
        count("skipped", Palette.TextSubtle.darker(), inStatus(TaskRunStatus.Skipped))
        count("unfinished", Palette.Warning, inStatus(TaskRunStatus.Running))
        count("reads across datasets", Palette.Input, l.edges.count { case (a, b) => a.dataset != b.dataset })
      } else s match {
        case ViewerSelection.OfNode(n) =>
          count("upstream", Palette.Input, l.ancestors(n).size)
          count("downstream", Palette.Accent, l.descendants(n).size)
          val datasets = canvas.drawn.map(_.dataset).size
          if (datasets > 1) count("datasets", Palette.TextMuted, datasets)
        case ViewerSelection.OfDataset(d) =>
          count(if (l.upstreamDatasets(d).size == 1) "dataset it reads" else "datasets it reads", Palette.Input,
            l.upstreamDatasets(d).size)
          count(if (l.downstreamDatasets(d).size == 1) "dataset reads it" else "datasets read it", Palette.Accent,
            l.downstreamDatasets(d).size)
      }
    }
  }
}
