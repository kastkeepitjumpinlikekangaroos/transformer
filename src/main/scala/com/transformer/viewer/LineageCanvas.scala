package com.transformer.viewer

import com.transformer.gui.{GraphCanvas, GraphOverlay, Theme}
import com.transformer.job.TaskRunStatus

import javafx.scene.canvas.GraphicsContext
import javafx.scene.paint.Color
import javafx.scene.text.{Font, FontWeight, TextAlignment}

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** The lineage of every dataset in the folder, drawn on a [[GraphCanvas]]:
  * each dataset a box with its name and run in its header, its inputs and
  * tables as cards inside, as the job GUI draws a job's DAG.
  *
  * An edge within a box is what a table's SQL reads; an edge between boxes,
  * in the input colour, is one job reading another's table. Selecting a node
  * lights its whole lineage, everything it derives from and everything
  * derived from it, across jobs, and dims the rest. A click selects a node or
  * a dataset; a double-click on a node opens a sample of its rows.
  */
final class LineageCanvas(session: ViewerSession) extends GraphCanvas {
  import GraphCanvas.{StatusFont, TagFont, TitleFont}
  import LineageCanvas._
  import Theme.Palette

  private var onActivate: NodeId => Unit = _ => ()
  def setOnActivate(f: NodeId => Unit): Unit = onActivate = f

  private var fittedFolder: Option[RunFolder] = None

  session.addListener(() => {
    // A folder just opened fits the window; another run of a dataset, or a
    // selection, keeps the view where the user put it.
    if (session.folder ne fittedFolder.orNull) {
      fittedFolder = session.folder
      fitToWindow()
    }
    render()
  })

  protected def contentSize: Option[(Double, Double)] =
    session.layout.filter(_.datasetBoxes.nonEmpty).map(l => (l.width, l.height))

  protected def hint: String =
    "Drag to pan · scroll to zoom · click a table to trace its lineage · double-click it for a sample of its rows"

  protected def paintEmpty(gc: GraphicsContext, w: Double, h: Double): Unit =
    (session.scanning, session.folder) match {
      case (Some(dir), _) => paintMessage(gc, w, h, "Looking for job outputs…", Palette.Text, Seq(dir.toString))
      case (None, Some(f)) =>
        paintMessage(gc, w, h, "No job outputs here", Palette.Text,
          Seq(f.root.toString, "A job output is a directory with a job.json, as `transformer run` writes one.") ++
            f.warnings.take(4))
      case (None, None) =>
        paintMessage(gc, w, h, "Open a folder of job outputs", Palette.Text,
          Seq("File > Open Folder, or pass the folder as the viewer's argument"))
    }

  // -------------------- painting --------------------

  protected def paintContent(gc: GraphicsContext): Unit = for {
    lineage <- session.lineage
    layout <- session.layout
  } {
    val focus = focusSet(lineage)
    lineage.shownRuns.indices.foreach(d => drawDataset(gc, lineage, layout, d))
    drawEdges(gc, lineage, layout, focus)
    lineage.nodes.foreach { n =>
      layout.nodeBoxes.get(n).foreach { b =>
        gc.setGlobalAlpha(if (focus.forall(_.contains(n))) 1.0 else Dimmed)
        n match {
          case t: NodeId.Table => drawTable(gc, lineage, t, b)
          case i: NodeId.Input => drawInput(gc, lineage, i, b)
        }
        gc.setGlobalAlpha(1.0)
      }
    }
  }

  /** The nodes in the selected node's lineage, itself included; None when no
    * node is selected, and everything is drawn alike. */
  private def focusSet(lineage: Lineage): Option[Set[NodeId]] = session.selection.collect {
    case ViewerSelection.OfNode(n) => lineage.ancestors(n) ++ lineage.descendants(n) + n
  }

  private def drawDataset(gc: GraphicsContext, lineage: Lineage, layout: LineageLayout, d: Int): Unit = {
    val box = layout.datasetBoxes(d)
    val dataset = lineage.folder.datasets(d)
    val run = lineage.shownRuns(d)
    val selected = session.selection.contains(ViewerSelection.OfDataset(d))
    gc.setFill(BoxFill)
    gc.fillRoundRect(box.x, box.y, box.width, box.height, BoxRadius * 2, BoxRadius * 2)
    gc.setStroke(if (selected) Palette.Accent else Palette.Border)
    gc.setLineWidth(if (selected) 2.0 else 1.0)
    gc.strokeRoundRect(box.x, box.y, box.width, box.height, BoxRadius * 2, BoxRadius * 2)

    val (color, verdict) = runStatus(run)
    gc.setFill(color)
    gc.fillOval(box.x + 18, box.y + 17, 8, 8)
    gc.setTextAlign(TextAlignment.LEFT)
    gc.setFill(Palette.Text)
    gc.setFont(DatasetFont)
    val nameWidth = box.width - 52
    gc.fillText(fit(dataset.name, DatasetFont, nameWidth), box.x + 34, box.y + 25)
    gc.setFill(Palette.TextMuted)
    gc.setFont(StatusFont)
    // Runs are numbered oldest first: the latest of three is run 3 of 3.
    val runs = if (dataset.runs.size > 1) s" · run ${dataset.runs.size - dataset.runs.indexOf(run)} of ${dataset.runs.size}" else ""
    val lineageNote = if (run.hasLineage) "" else " · lineage not recorded"
    gc.fillText(fit(s"$verdict · ${RunTime.format(run.record.executionTime)}$runs$lineageNote", StatusFont, nameWidth),
      box.x + 34, box.y + 41)
  }

  private def drawEdges(gc: GraphicsContext, lineage: Lineage, layout: LineageLayout, focus: Option[Set[NodeId]]): Unit = {
    val lit = scala.collection.mutable.ArrayBuffer.empty[(Box, Box, Color)]
    gc.setLineWidth(1.5)
    lineage.edges.foreach { case (from, to) =>
      for (a <- layout.nodeBoxes.get(from); b <- layout.nodeBoxes.get(to)) {
        val across = from.dataset != to.dataset
        val inFocus = focus.exists(f => f.contains(from) && f.contains(to))
        val color =
          if (across) Palette.Input
          else from match {
            case _: NodeId.Input => InputEdge
            case _ => Palette.Border
          }
        if (inFocus) lit += ((a, b, if (across) Palette.Input else Palette.Accent))
        else {
          gc.setGlobalAlpha(if (focus.isEmpty) 1.0 else Dimmed)
          drawEdge(gc, a.right, a.centerY, b.x, b.centerY, if (across && focus.isEmpty) AcrossEdge else color)
          gc.setGlobalAlpha(1.0)
        }
      }
    }
    gc.setLineWidth(2.2)
    lit.foreach { case (a, b, c) => drawEdge(gc, a.right, a.centerY, b.x, b.centerY, c) }
  }

  private def drawTable(gc: GraphicsContext, lineage: Lineage, n: NodeId.Table, b: Box): Unit = {
    val summary = lineage.shownRuns(n.dataset).table(n.name).map(_.summary)
    val (accent, line) = summary match {
      case Some(s) => tableStatus(s.status, s.rowsProduced)
      case None => (Palette.TextSubtle, "")
    }
    drawCard(gc, b.x, b.y, b.width, b.height, accent, session.selection.contains(ViewerSelection.OfNode(n)))
    val textX = b.x + 18
    val textW = b.right - textX - 10
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

  private def drawInput(gc: GraphicsContext, lineage: Lineage, n: NodeId.Input, b: Box): Unit = {
    val record = lineage.inputRecord(n)
    val footer = lineage.sources.get(n) match {
      case Some(InputSource.Tables(ms)) =>
        ms.headOption.map(m => s"from ${lineage.folder.datasets(m.dataset).name}.${m.table}").getOrElse("")
      case _ => record.map(r => basename(r.path)).getOrElse("")
    }
    drawCard(gc, b.x, b.y, b.width, b.height, Palette.Input, session.selection.contains(ViewerSelection.OfNode(n)))
    drawCylinder(gc, b.x + 22, b.centerY, Palette.Input)
    val textX = b.x + 38
    val textW = b.right - textX - 10
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

  private def basename(path: String): String = {
    val cleaned = path.replace('\\', '/').stripSuffix("/")
    cleaned.substring(cleaned.lastIndexOf('/') + 1)
  }

  // -------------------- interaction --------------------

  protected def clicked(wx: Double, wy: Double, clicks: Int): Unit =
    session.layout.flatMap(_.hitTest(wx, wy)) match {
      case Some(Right(node)) =>
        session.select(Some(ViewerSelection.OfNode(node)))
        if (clicks >= 2) onActivate(node)
      case Some(Left(d)) => session.select(Some(ViewerSelection.OfDataset(d)))
      case None => if (clicks == 1) session.select(None)
    }

  /** Pan so `node` is in the middle of the canvas, as the tree selects it. */
  def reveal(node: NodeId): Unit = session.layout.flatMap(_.nodeBoxes.get(node)).foreach { b =>
    val (sx, sy) = worldToScreen(b.centerX, b.centerY)
    if (sx < 0 || sy < 0 || sx > getWidth || sy > getHeight) centerOn(b.centerX, b.centerY)
  }
}

object LineageCanvas {
  import Theme.Palette

  private val DatasetFont: Font = Font.font("System", FontWeight.BOLD, 14)
  private val BoxRadius = 14.0
  private val BoxFill: Color = Palette.Background.interpolate(Palette.Surface, 0.55)
  private val InputEdge: Color = Palette.Input.deriveColor(0, 1, 0.75, 0.45)
  private val AcrossEdge: Color = Palette.Input.deriveColor(0, 1, 1, 0.8)
  /** How strongly what is outside the selected node's lineage is drawn. */
  private val Dimmed = 0.28

  val RunTime: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC)

  /** A run's colour and verdict: green when every task succeeded, amber with
    * warnings, red when a task did not. */
  def runStatus(run: DatasetRun): (Color, String) =
    if (!run.record.succeeded) (Palette.Danger, "failed")
    else if (run.record.warnings.nonEmpty) (Palette.Warning, s"succeeded with ${run.record.warnings.size} warning(s)")
    else (Palette.Success, "succeeded")

  /** A table's colour and status line. */
  def tableStatus(status: TaskRunStatus, rows: Long): (Color, String) = status match {
    case TaskRunStatus.Succeeded => (Palette.Success, f"$rows%,d rows")
    case TaskRunStatus.ValidationFailed => (Palette.Warning, f"validation failed · $rows%,d rows")
    case TaskRunStatus.Failed => (Palette.Danger, "failed")
    case TaskRunStatus.Skipped => (Palette.TextSubtle.darker(), "skipped")
  }
}

/** The lineage canvas with zoom controls, and at the top left how many
  * datasets and tables the folder holds and how many did not succeed. */
final class LineageOverlay(session: ViewerSession, canvas: LineageCanvas) extends GraphOverlay(canvas) {
  import Theme.Palette

  session.addListener(() => refresh())
  refresh()

  private def refresh(): Unit = {
    summary.getChildren.clear()
    val lineage = session.lineage.filter(_.shownRuns.nonEmpty)
    setControlsVisible(lineage.isDefined)
    lineage.foreach { l =>
      val tables = l.shownRuns.flatMap(_.tables)
      def inStatus(s: TaskRunStatus): Int = tables.count(_.summary.status == s)
      count(if (l.shownRuns.size == 1) "dataset" else "datasets", Palette.Accent, l.shownRuns.size)
      count(if (tables.size == 1) "table" else "tables", Palette.Success, tables.size)
      count("failed", Palette.Danger, inStatus(TaskRunStatus.Failed))
      count("validation failed", Palette.Warning, inStatus(TaskRunStatus.ValidationFailed))
      count("skipped", Palette.TextSubtle.darker(), inStatus(TaskRunStatus.Skipped))
      count("reads across datasets", Palette.Input, l.edges.count { case (a, b) => a.dataset != b.dataset })
    }
  }
}
