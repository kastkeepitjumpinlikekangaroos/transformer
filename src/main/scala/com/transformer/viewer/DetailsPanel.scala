package com.transformer.viewer

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.gui.{Icons, SqlView, Tone}
import com.transformer.job.TaskRunStatus

import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.{Button, Hyperlink, Label, ScrollPane, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout.{FlowPane, HBox, Priority, Region, VBox}

import java.time.Duration

/** An inspector's Details view: what the run recorded about its subject.
  *
  *   * A table: its rows, timings and format, the name queries read it by,
  *     where it is, what it reads and what reads it, its validations and
  *     error, and beside them the SQL that made it.
  *   * An input: where its rows came from (a file outside the folder, or
  *     another job's table), its format, and what reads it.
  *   * A dataset: its run, the datasets it reads and that read it, what it
  *     read from outside the folder, its error and its warnings.
  *
  * A chip that names a table, an input or a dataset opens it (`open`), and
  * View lineage graph turns to the Lineage view (`showLineage`). It is built
  * for one subject as the session holds it now; the inspector builds another
  * when either changes.
  */
final class DetailsPanel(
    session: ViewerSession,
    subject: ViewerSelection,
    open: ViewerSelection => Unit,
    showLineage: () => Unit
) extends HBox {
  import DetailsPanel._

  getStyleClass.add("panel")
  setSpacing(18)
  setPadding(new Insets(16, 20, 16, 20))

  private val facts = new VBox(14)
  facts.setPadding(new Insets(2, 8, 8, 0))
  private val factsScroll = new ScrollPane(facts)
  factsScroll.setFitToWidth(true)
  factsScroll.getStyleClass.addAll("panel-scroll", Tweaks.EDGE_TO_EDGE)
  factsScroll.setPrefWidth(520)
  HBox.setHgrow(factsScroll, Priority.ALWAYS)
  getChildren.add(factsScroll)

  (session.lineage, subject) match {
    case (Some(l), ViewerSelection.OfNode(t: NodeId.Table)) => showTable(l, t)
    case (Some(l), ViewerSelection.OfNode(i: NodeId.Input)) => showInput(l, i)
    case (Some(l), ViewerSelection.OfDataset(d)) => showDataset(l, d)
    case (None, _) => ()
  }

  // ==================== a table ====================

  private def showTable(l: Lineage, n: NodeId.Table): Unit = {
    val table = l.shownRuns(n.dataset).table(n.name)
    val record = table.flatMap(_.record)

    table.foreach { t =>
      val s = t.summary
      val stats = flow()
      if (wrote(s.status)) stats.getChildren.add(chip(f"${s.rowsProduced}%,d rows"))
      record.foreach(r => stats.getChildren.add(chip(r.format.toUpperCase(java.util.Locale.ROOT))))
      for (a <- s.startedAt; b <- s.finishedAt if a != java.time.Instant.EPOCH)
        stats.getChildren.add(chip(s"took ${Duration.between(a, b).toMillis} ms"))
      record.foreach(r => stats.getChildren.add(chip(s"written ${LineageCanvas.RunTime.format(r.writtenAt)}")))
      facts.getChildren.add(stats)
    }
    sqlNameSection(n)
    facts.getChildren.add(section("LOCATION", pathLabel(table.flatMap(_.dir).map(_.toString)
      .orElse(table.flatMap(_.recordedDir).map(_ + "  (not on disk)")).getOrElse("(no output directory)"))))
    lineageSections(l, n)
    record.filter(_.validations.nonEmpty).foreach { r =>
      val f = flow()
      // A task that failed or was skipped never ran its validations.
      val ran = wrote(r.status)
      r.validations.foreach { v =>
        val (text, t) =
          if (!ran) (s"${v.name} · not run", Tone.Neutral)
          else if (v.passed) (s"${v.name} · passed", Tone.Success)
          else (f"${v.name} · ${v.failedRowCount}%,d failing", Tone.Danger)
        f.getChildren.add(toneChip(text, t))
      }
      facts.getChildren.add(section("VALIDATIONS", f))
    }
    table.flatMap(_.summary.errorMessage).orElse(record.flatMap(_.errorMessage)).foreach { err =>
      val e = new Label(err)
      e.getStyleClass.addAll("error-text", "mono")
      e.setWrapText(true)
      val box = new VBox(e)
      box.getStyleClass.add("error-box")
      facts.getChildren.add(section(
        if (table.exists(_.summary.status == TaskRunStatus.Skipped)) "WHY IT WAS SKIPPED" else "ERROR", box))
    }
    record.flatMap(_.sql) match {
      case Some(sql) =>
        val sqlView = new SqlView()
        sqlView.setSql(sql)
        val spacer = new Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)
        val header = new HBox(10, sectionLabel("SQL THAT MADE IT"), spacer)
        header.getChildren.addAll(sqlView.actions: _*)
        header.setAlignment(Pos.CENTER_LEFT)
        VBox.setVgrow(sqlView, Priority.ALWAYS)
        val column = new VBox(8, header, sqlView)
        column.setPrefWidth(560)
        HBox.setHgrow(column, Priority.ALWAYS)
        getChildren.add(column)
      case None =>
        facts.getChildren.add(hint(
          if (record.isEmpty) "This table's _run.json is not on disk, so neither its columns nor its SQL are known."
          else "This run's records are from before they kept the SQL that made each table; run the job again to see it."))
    }
  }

  // ==================== an input ====================

  private def showInput(l: Lineage, n: NodeId.Input): Unit = {
    val input = l.inputRecord(n)
    l.sources.getOrElse(n, InputSource.External) match {
      case InputSource.External =>
        facts.getChildren.add(section("READ FROM OUTSIDE THE FOLDER", pathLabel(input.map(_.path).getOrElse(""))))
        sqlNameSection(n)
      case InputSource.Tables(ms) =>
        val f = flow()
        ms.foreach { m =>
          f.getChildren.add(navChip(s"${l.folder.datasets(m.dataset).name}.${m.table}", "task",
            Some(ViewerSelection.OfNode(NodeId.Table(m.dataset, m.table))).filter(_ => m.inShownRun)))
          if (!m.shown) f.getChildren.add(hint(s"its run in ${m.runDir.getFileName}, not the one shown"))
        }
        facts.getChildren.add(section("THE OUTPUT OF", f))
        facts.getChildren.add(section("PATH", pathLabel(input.map(_.path).getOrElse(""))))
    }
    input.foreach { in =>
      val f = flow()
      f.getChildren.add(chip(in.format.toUpperCase(java.util.Locale.ROOT)))
      in.options.toSeq.sorted.foreach { case (k, v) => f.getChildren.add(chip(s"$k: $v")) }
      facts.getChildren.add(section("FORMAT", f))
    }
    lineageSections(l, n)
  }

  // ==================== a dataset ====================

  private def showDataset(l: Lineage, d: Int): Unit = {
    val dataset = l.folder.datasets(d)
    val run = l.shownRuns(d)
    val stats = flow()
    stats.getChildren.add(chip(s"${run.tables.size} ${if (run.tables.size == 1) "table" else "tables"}"))
    stats.getChildren.add(chip(f"${run.tables.map(_.summary.rowsProduced).sum}%,d rows"))
    stats.getChildren.add(chip(s"executed for ${LineageCanvas.RunTime.format(run.record.executionTime)}"))
    stats.getChildren.add(chip(s"took ${Duration.between(run.record.startedAt, run.record.finishedAt).toMillis} ms"))
    if (dataset.runs.size > 1) stats.getChildren.add(chip(s"${dataset.runs.size} runs"))
    facts.getChildren.add(stats)
    facts.getChildren.add(section("RUN DIRECTORY", pathLabel(run.dir.toString)))
    facts.getChildren.add(section("SQL NAMES", mono(s"${dataset.sqlName}__<table>")))

    def datasets(ids: Seq[Int]): Node =
      if (ids.isEmpty) hint("none in this folder")
      else {
        val f = flow()
        ids.foreach(i => f.getChildren.add(navChip(l.folder.datasets(i).name, "clickable", Some(ViewerSelection.OfDataset(i)))))
        f
      }
    facts.getChildren.add(section("READS TABLES OF", datasets(l.upstreamDatasets(d))))
    facts.getChildren.add(section("ITS TABLES ARE READ BY", datasets(l.downstreamDatasets(d))))
    val outside = run.inputs.filter(in => l.sources.get(NodeId.Input(d, in.name)).contains(InputSource.External))
    if (outside.nonEmpty) {
      val f = flow()
      outside.foreach(in => f.getChildren.add(nodeChip(l, NodeId.Input(d, in.name), d)))
      facts.getChildren.add(section("READS FROM OUTSIDE THE FOLDER", f))
    }
    if (run.hasLineage) facts.getChildren.add(lineageLink())
    run.record.errorMessage.foreach(e => facts.getChildren.add(section("ERROR", wrapped(e, "error-text"))))
    if (run.record.warnings.nonEmpty) {
      val w = new VBox(4)
      run.record.warnings.foreach(x => w.getChildren.add(wrapped(x, "muted")))
      facts.getChildren.add(section(s"WARNINGS (${run.record.warnings.size})", w))
    }
    if (!run.hasLineage) facts.getChildren.add(section("LINEAGE NOT RECORDED", notRecorded(dataset, run)))
  }

  // ==================== pieces ====================

  private def sqlNameSection(n: NodeId): Unit = session.queryCatalog.sqlName(n).foreach { name =>
    val copy = new Button(null, Icons.copy)
    copy.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
    copy.setTooltip(new Tooltip("Copy the name"))
    copy.setOnAction(_ => {
      val content = new ClipboardContent()
      content.putString(name)
      Clipboard.getSystemClipboard.setContent(content)
      ()
    })
    val row = new HBox(6, mono(name), copy)
    row.setAlignment(Pos.CENTER_LEFT)
    facts.getChildren.add(section("SQL NAME", row))
  }

  /** What `n` reads and what reads it, a chip each, and how much of the
    * folder its whole lineage holds, with the way to its graph; or, for a
    * run that did not record its lineage, that it did not, and what other
    * jobs' runs say reads `n`. */
  private def lineageSections(l: Lineage, n: NodeId): Unit = {
    def chips(nodes: Seq[NodeId], empty: String): Node =
      if (nodes.isEmpty) hint(empty)
      else {
        val f = flow()
        nodes.foreach(m => f.getChildren.add(nodeChip(l, m, n.dataset)))
        f
      }
    val run = l.shownRuns(n.dataset)
    if (!run.hasLineage) {
      facts.getChildren.add(section("LINEAGE NOT RECORDED", notRecorded(l.folder.datasets(n.dataset), run)))
      // Another job's run may still say it reads this table.
      if (l.downstream(n).nonEmpty) facts.getChildren.add(section("READ BY OTHER JOBS", chips(l.downstream(n), "")))
    } else {
      facts.getChildren.add(section("READS FROM", chips(l.upstream(n),
        n match {
          case _: NodeId.Table => "nothing: its rows come from its SQL alone"
          case _: NodeId.Input => "nothing in this folder"
        })))
      facts.getChildren.add(section("READ BY", chips(l.downstream(n),
        n match {
          case _: NodeId.Input => "no table of this job reads it"
          case _ => "nothing in this folder"
        })))
      val ancestors = l.ancestors(n).size
      val descendants = l.descendants(n).size
      val summary = hint(s"Its whole lineage: ${count(ancestors, "node")} it is made from, $descendants made from it.")
      val row = new HBox(10, summary, lineageLink())
      row.setAlignment(Pos.CENTER_LEFT)
      facts.getChildren.add(row)
    }
  }

  /** Why `run` shows no lineage, in a notice. */
  private def notRecorded(dataset: Dataset, run: DatasetRun): Node = {
    val box = new VBox(wrapped(LineageCanvas.notRecorded(dataset, run), "notice-text"))
    box.getStyleClass.add("lineage-notice")
    box
  }

  private def lineageLink(): Hyperlink = {
    val link = new Hyperlink("View lineage graph", Icons.lineage)
    link.setOnAction(_ => showLineage())
    link
  }

  /** A chip for `m`, named with its dataset when that is not `here`; it
    * opens `m`. */
  private def nodeChip(l: Lineage, m: NodeId, here: Int): Label = {
    val dataset = if (m.dataset == here) "" else s"${l.folder.datasets(m.dataset).name}."
    m match {
      case t: NodeId.Table =>
        val status = l.shownRuns(t.dataset).table(t.name).map(_.summary.status)
        val c = navChip(dataset + t.name, "task", Some(ViewerSelection.OfNode(t)))
        status.filterNot(_ == TaskRunStatus.Succeeded).foreach(s => c.getStyleClass.add(tone(s).css))
        c
      case i: NodeId.Input => navChip(dataset + i.name, "input", Some(ViewerSelection.OfNode(i)))
    }
  }

  private def navChip(text: String, kind: String, target: Option[ViewerSelection]): Label = {
    val c = chip(text)
    c.getStyleClass.add(kind)
    target.foreach(t => {
      c.getStyleClass.add("clickable")
      c.setOnMouseClicked(_ => open(t))
    })
    c
  }
}

object DetailsPanel {

  /** Whether a task with `s` wrote its output. */
  def wrote(s: TaskRunStatus): Boolean = s == TaskRunStatus.Succeeded || s == TaskRunStatus.ValidationFailed

  def statusText(s: TaskRunStatus): String = s match {
    case TaskRunStatus.Succeeded => "SUCCEEDED"
    case TaskRunStatus.ValidationFailed => "VALIDATION FAILED"
    case TaskRunStatus.Failed => "FAILED"
    case TaskRunStatus.Skipped => "SKIPPED"
  }

  def tone(s: TaskRunStatus): Tone = s match {
    case TaskRunStatus.Succeeded => Tone.Success
    case TaskRunStatus.ValidationFailed => Tone.Warning
    case TaskRunStatus.Failed => Tone.Danger
    case TaskRunStatus.Skipped => Tone.Neutral
  }

  private def count(n: Int, noun: String): String = s"$n ${if (n == 1) noun else noun + "s"}"

  private def section(title: String, content: Node): VBox = new VBox(6, sectionLabel(title), content)

  private def sectionLabel(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add("section-label")
    l
  }

  private def flow(): FlowPane = {
    val f = new FlowPane(6, 6)
    f.setAlignment(Pos.CENTER_LEFT)
    f
  }

  private def chip(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add("chip")
    l
  }

  private def toneChip(text: String, t: Tone): Label = {
    val l = chip(text)
    l.getStyleClass.add(t.css)
    l
  }

  private def mono(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.addAll("mono", "option-label")
    l
  }

  private def pathLabel(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add("path-label")
    l.setWrapText(true)
    l
  }

  private def hint(text: String): Label = wrapped(text, "hint")

  private def wrapped(text: String, style: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add(style)
    l.setWrapText(true)
    l
  }
}
