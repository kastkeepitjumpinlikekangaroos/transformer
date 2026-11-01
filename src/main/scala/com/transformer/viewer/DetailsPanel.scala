package com.transformer.viewer

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.core.Schema
import com.transformer.gui.{DataGrid, FxHelpers, Icons, SqlView, Tone}
import com.transformer.job.{TaskRunRecord, TaskRunStatus}

import javafx.geometry.{Insets, Pos}
import javafx.scene.Node
import javafx.scene.control.{Button, ComboBox, Label, ScrollPane, Tooltip}
import javafx.scene.input.{Clipboard, ClipboardContent}
import javafx.scene.layout.{FlowPane, GridPane, HBox, Priority, Region, VBox}

import java.time.Duration
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** The Details tab: everything about what is selected.
  *
  *   * A table: its status and rows, the name queries read it by, where it is,
  *     what it reads and what reads it (across jobs too), its columns, its
  *     validations and error, and beside them the SQL that made it.
  *   * An input: where its rows came from (a file outside the folder, or
  *     another job's table) and what reads it.
  *   * A dataset: its run (a picker when it has several), the datasets it
  *     reads and that read it, its tables and its warnings.
  *   * Nothing: the folder.
  *
  * A chip that names a table, an input or a dataset selects it.
  */
final class DetailsPanel(session: ViewerSession) extends VBox {
  import DetailsPanel._

  private var onSample: NodeId => Unit = _ => ()
  private var onQuery: NodeId => Unit = _ => ()
  def setOnSample(f: NodeId => Unit): Unit = onSample = f
  def setOnQuery(f: NodeId => Unit): Unit = onQuery = f

  getStyleClass.add("panel")
  setSpacing(12)
  setPadding(new Insets(16, 20, 16, 20))

  private val title = new Label()
  title.getStyleClass.add("panel-title")
  title.setMinWidth(0)
  private val pill = new Label()
  private val actions = new HBox(6)
  actions.setAlignment(Pos.CENTER_RIGHT)
  private val headerSpacer = new Region()
  HBox.setHgrow(headerSpacer, Priority.ALWAYS)
  private val header = new HBox(10, title, pill, headerSpacer, actions)
  header.setAlignment(Pos.CENTER_LEFT)
  private val subtitle = new Label()
  subtitle.getStyleClass.add("muted")

  private val facts = new VBox(14)
  facts.setPadding(new Insets(2, 8, 8, 0))
  private val factsScroll = new ScrollPane(facts)
  factsScroll.setFitToWidth(true)
  factsScroll.getStyleClass.addAll("panel-scroll", Tweaks.EDGE_TO_EDGE)
  HBox.setHgrow(factsScroll, Priority.ALWAYS)
  factsScroll.setPrefWidth(520)

  // The SQL that made a table, beside its facts.
  private val sqlView = new SqlView()
  private val sqlHeaderSpacer = new Region()
  HBox.setHgrow(sqlHeaderSpacer, Priority.ALWAYS)
  private val sqlHeader = new HBox(10, sectionLabel("SQL THAT MADE IT"), sqlHeaderSpacer)
  sqlHeader.getChildren.addAll(sqlView.actions: _*)
  sqlHeader.setAlignment(Pos.CENTER_LEFT)
  VBox.setVgrow(sqlView, Priority.ALWAYS)
  private val sqlColumn = new VBox(8, sqlHeader, sqlView)
  sqlColumn.setPrefWidth(560)
  HBox.setHgrow(sqlColumn, Priority.ALWAYS)

  private val body = new HBox(18, factsScroll)
  VBox.setVgrow(body, Priority.ALWAYS)
  getChildren.addAll(header, subtitle, body)

  /** What the panel shows, so a listener call that changes nothing it shows
    * keeps it as it is (and keeps its scroll). */
  private var shown: Option[(Option[ViewerSelection], Option[Lineage])] = None
  private val loadToken = new AtomicLong()

  session.addListener(() => refresh())
  refresh()

  private def refresh(): Unit = {
    val now = (session.selection, session.lineage)
    if (shown.exists(s => s._1 == now._1 && (s._2 eq now._2.orNull))) return
    shown = Some(now)
    loadToken.incrementAndGet()
    facts.getChildren.clear()
    actions.getChildren.clear()
    body.getChildren.setAll(factsScroll)
    pill.setVisible(false)
    pill.setManaged(false)
    (session.lineage, session.selection) match {
      case (Some(l), Some(ViewerSelection.OfNode(t: NodeId.Table))) => showTable(l, t)
      case (Some(l), Some(ViewerSelection.OfNode(i: NodeId.Input))) => showInput(l, i)
      case (Some(l), Some(ViewerSelection.OfDataset(d))) => showDataset(l, d)
      case (Some(l), None) => showFolder(l)
      case (None, _) => showNothing()
    }
  }

  // ==================== a table ====================

  private def showTable(l: Lineage, n: NodeId.Table): Unit = {
    val dataset = l.folder.datasets(n.dataset)
    val run = l.shownRuns(n.dataset)
    val table = run.table(n.name)
    val record = table.flatMap(_.record)
    title.setText(n.name)
    subtitle.setText(s"A table of ${dataset.name} · run of ${LineageCanvas.RunTime.format(run.record.executionTime)}")
    table.foreach(t => setPill(statusText(t.summary.status), tone(t.summary.status)))
    addSampleActions(n)

    table.foreach { t =>
      val s = t.summary
      val stats = flow()
      if (s.status == TaskRunStatus.Succeeded || s.status == TaskRunStatus.ValidationFailed)
        stats.getChildren.add(chip(f"${s.rowsProduced}%,d rows"))
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
    record.flatMap(_.schema) match {
      case Some(schema) => facts.getChildren.add(section("COLUMNS", columns(schema)))
      case None => loadColumns(n)
    }
    record.filter(_.validations.nonEmpty).foreach { r =>
      val f = flow()
      // A task that failed or was skipped never ran its validations.
      val ran = r.status == TaskRunStatus.Succeeded || r.status == TaskRunStatus.ValidationFailed
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
      facts.getChildren.add(section(if (table.exists(_.summary.status == TaskRunStatus.Skipped)) "WHY IT WAS SKIPPED" else "ERROR", box))
    }
    record.flatMap(_.sql) match {
      case Some(sql) =>
        sqlView.setSql(sql)
        body.getChildren.setAll(factsScroll, sqlColumn)
      case None =>
        facts.getChildren.add(hint(
          if (record.isEmpty) "This table's _run.json is not on disk, so neither its columns nor its SQL are known."
          else "This run's records are from before they kept the SQL that made each table; run the job again to see it."))
    }
  }

  // ==================== an input ====================

  private def showInput(l: Lineage, n: NodeId.Input): Unit = {
    val dataset = l.folder.datasets(n.dataset)
    val input = l.inputRecord(n)
    title.setText(n.name)
    subtitle.setText(s"An input of ${dataset.name}")
    setPill("INPUT", Tone.Input)
    val source = l.sources.getOrElse(n, InputSource.External)
    source match {
      case InputSource.External =>
        addSampleActions(n)
        facts.getChildren.add(section("READ FROM OUTSIDE THE FOLDER", pathLabel(input.map(_.path).getOrElse(""))))
        sqlNameSection(n)
      case InputSource.Tables(ms) =>
        val f = flow()
        ms.foreach { m =>
          val c = navChip(s"${l.folder.datasets(m.dataset).name}.${m.table}", "task",
            Some(ViewerSelection.OfNode(NodeId.Table(m.dataset, m.table))).filter(_ => m.inShownRun))
          f.getChildren.add(c)
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
    val readers = flow()
    l.downstream(n).foreach(m => readers.getChildren.add(nodeChip(l, m, n.dataset)))
    facts.getChildren.add(section("READ BY", if (readers.getChildren.isEmpty) hint("no table of this job reads it") else readers))
    if (source == InputSource.External) loadColumns(n)
  }

  // ==================== a dataset ====================

  private def showDataset(l: Lineage, d: Int): Unit = {
    val dataset = l.folder.datasets(d)
    val run = l.shownRuns(d)
    title.setText(dataset.name)
    subtitle.setText(run.dir.toString)
    val (_, verdict) = LineageCanvas.runStatus(run)
    setPill(verdict.toUpperCase(java.util.Locale.ROOT).takeWhile(_ != ' '),
      if (!run.record.succeeded) Tone.Danger else if (run.record.warnings.nonEmpty) Tone.Warning else Tone.Success)
    if (dataset.runs.size > 1) actions.getChildren.addAll(runPicker(d): _*)

    val stats = flow()
    stats.getChildren.add(chip(s"${run.tables.size} ${if (run.tables.size == 1) "table" else "tables"}"))
    stats.getChildren.add(chip(f"${run.tables.map(_.summary.rowsProduced).sum}%,d rows"))
    stats.getChildren.add(chip(s"executed for ${LineageCanvas.RunTime.format(run.record.executionTime)}"))
    stats.getChildren.add(chip(s"took ${Duration.between(run.record.startedAt, run.record.finishedAt).toMillis} ms"))
    if (dataset.runs.size > 1) stats.getChildren.add(chip(s"${dataset.runs.size} runs"))
    facts.getChildren.add(stats)
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

    val tables = flow()
    run.tables.foreach(t => tables.getChildren.add(nodeChip(l, NodeId.Table(d, t.name), d)))
    facts.getChildren.add(section("TABLES", tables))
    val outside = run.inputs.filter(in => l.sources.get(NodeId.Input(d, in.name)).contains(InputSource.External))
    if (outside.nonEmpty) {
      val f = flow()
      outside.foreach(in => f.getChildren.add(nodeChip(l, NodeId.Input(d, in.name), d)))
      facts.getChildren.add(section("READS FROM OUTSIDE THE FOLDER", f))
    }
    run.record.errorMessage.foreach(e => facts.getChildren.add(section("ERROR", wrapped(e, "error-text"))))
    if (run.record.warnings.nonEmpty) {
      val w = new VBox(4)
      run.record.warnings.foreach(x => w.getChildren.add(wrapped(x, "muted")))
      facts.getChildren.add(section(s"WARNINGS (${run.record.warnings.size})", w))
    }
    if (!run.hasLineage) facts.getChildren.add(hint(
      "This run's job.json is from before runs recorded their lineage: what each table reads, and the inputs. " +
        "Run the job again to see them."))
  }

  private def runPicker(d: Int): Seq[Node] = {
    val dataset = session.datasets(d)
    val combo = new ComboBox[String]()
    dataset.runs.zipWithIndex.foreach { case (r, i) =>
      val (_, verdict) = LineageCanvas.runStatus(r)
      combo.getItems.add(s"${LineageCanvas.RunTime.format(r.record.executionTime)} · $verdict · ${r.dir.getFileName}" +
        (if (i == 0) " (latest)" else ""))
    }
    combo.getSelectionModel.select(session.shownRunIndex(d))
    combo.getSelectionModel.selectedIndexProperty().addListener((_, _, i) =>
      if (i.intValue >= 0) session.showRun(d, i.intValue))
    val label = new Label("Run")
    label.getStyleClass.add("section-label")
    Seq(label, combo)
  }

  // ==================== the folder ====================

  private def showFolder(l: Lineage): Unit = {
    title.setText(Option(l.folder.root.getFileName).map(_.toString).getOrElse(l.folder.root.toString))
    subtitle.setText(l.folder.root.toString)
    val stats = flow()
    stats.getChildren.add(chip(s"${l.shownRuns.size} ${if (l.shownRuns.size == 1) "dataset" else "datasets"}"))
    stats.getChildren.add(chip(s"${l.shownRuns.map(_.tables.size).sum} tables"))
    stats.getChildren.add(chip(s"${l.edges.count { case (a, b) => a.dataset != b.dataset }} reads across datasets"))
    facts.getChildren.add(stats)
    facts.getChildren.add(wrapped(
      "Click a dataset or a table, in the lineage above or the list beside it, to see it here; a table lights up " +
        "everything it is made from and everything made from it, across jobs. Double-click a table for a sample of " +
        "its rows. The Query tab runs SQL over every table, each named <dataset>__<table>.", "hint"))
    if (l.folder.warnings.nonEmpty) {
      val w = new VBox(4)
      l.folder.warnings.foreach(x => w.getChildren.add(wrapped(x, "muted")))
      facts.getChildren.add(section("COULD NOT READ", w))
    }
  }

  private def showNothing(): Unit = {
    title.setText(if (session.scanning.isDefined) "Looking for job outputs…" else "No folder open")
    subtitle.setText(session.scanning.map(_.toString).getOrElse("File > Open Folder (⌘O) opens a folder of job outputs."))
  }

  // ==================== pieces ====================

  private def addSampleActions(n: NodeId): Unit = if (session.queryCatalog.sqlName(n).isDefined) {
    val sample = new Button("Sample rows", Icons.table)
    sample.getStyleClass.add(Styles.SMALL)
    sample.setOnAction(_ => onSample(n))
    sample.setTooltip(new Tooltip("Open the first rows in a tab of their own (or double-click it)"))
    val query = new Button("Query", Icons.play)
    query.getStyleClass.addAll(Styles.SMALL, Styles.ACCENT)
    query.setOnAction(_ => onQuery(n))
    query.setTooltip(new Tooltip("Query it in a new tab"))
    actions.getChildren.addAll(sample, query)
  }

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

  /** What `n` reads and what reads it, a chip each. */
  private def lineageSections(l: Lineage, n: NodeId): Unit = {
    def chips(nodes: Seq[NodeId], empty: String): Node =
      if (nodes.isEmpty) hint(empty)
      else {
        val f = flow()
        nodes.foreach(m => f.getChildren.add(nodeChip(l, m, n.dataset)))
        f
      }
    val run = l.shownRuns(n.dataset)
    facts.getChildren.add(section("READS FROM", chips(l.upstream(n),
      if (run.hasLineage) "nothing: its rows come from its SQL alone" else "not recorded by this run")))
    facts.getChildren.add(section("READ BY", chips(l.downstream(n), "nothing in this folder")))
    val ancestors = l.ancestors(n).size
    val descendants = l.descendants(n).size
    if (ancestors + descendants > 0) facts.getChildren.add(hint(
      s"Its whole lineage: $ancestors node(s) it is made from, $descendants made from it, lit in the graph above."))
  }

  /** A chip for `m`, named with its dataset when that is not `here`; it
    * selects `m`. */
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
      c.setOnMouseClicked(_ => session.select(Some(t)))
    })
    c
  }

  /** Columns a record did not keep: read from the data, on a worker. */
  private def loadColumns(n: NodeId): Unit = session.queryCatalog.view(n) match {
    case None => ()
    case Some(_) =>
      val placeholder = hint("reading…")
      val sectionBox = section("COLUMNS", placeholder)
      facts.getChildren.add(sectionBox)
      val token = loadToken.get()
      val catalog = session.queryCatalog
      val worker = new Thread(() => {
        val result = try Right(catalog.view(n).get.schema) catch { case NonFatal(e) => Left(String.valueOf(e.getMessage)) }
        FxHelpers.onFx {
          if (loadToken.get() == token) sectionBox.getChildren.set(1, result match {
            case Right(schema) => columns(schema)
            case Left(err) => wrapped(err, "error-text")
          })
        }
      }, "transformer-viewer-columns")
      worker.setDaemon(true)
      worker.start()
  }

  private def columns(schema: Schema): Node = {
    val grid = new GridPane()
    grid.getStyleClass.add("columns-grid")
    schema.fields.iterator.zipWithIndex.foreach { case (f, i) =>
      val name = mono(f.name)
      val t = new Label(DataGrid.typeName(f.dataType))
      t.getStyleClass.addAll("column-type-name", "mono")
      grid.add(name, 0, i)
      grid.add(t, 1, i)
    }
    grid
  }

  private def setPill(text: String, t: Tone): Unit = {
    pill.setText(text)
    pill.getStyleClass.setAll("label", "pill", t.css)
    pill.setVisible(true)
    pill.setManaged(true)
  }
}

object DetailsPanel {

  private def statusText(s: TaskRunStatus): String = s match {
    case TaskRunStatus.Succeeded => "SUCCEEDED"
    case TaskRunStatus.ValidationFailed => "VALIDATION FAILED"
    case TaskRunStatus.Failed => "FAILED"
    case TaskRunStatus.Skipped => "SKIPPED"
  }

  private def tone(s: TaskRunStatus): Tone = s match {
    case TaskRunStatus.Succeeded => Tone.Success
    case TaskRunStatus.ValidationFailed => Tone.Warning
    case TaskRunStatus.Failed => Tone.Danger
    case TaskRunStatus.Skipped => Tone.Neutral
  }

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
