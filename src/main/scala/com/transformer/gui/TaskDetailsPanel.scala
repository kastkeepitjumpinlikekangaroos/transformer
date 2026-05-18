package com.transformer.gui

import com.transformer.job.{InputFilePath, RunMarker, TaskStatus, Validation}

import java.nio.file.Paths
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Button, Label, ScrollPane, Separator, Tooltip}
import javafx.scene.layout.{FlowPane, HBox, Priority, VBox}
import javafx.scene.text.{Font, FontWeight}

/** Right-side panel: shows full details for the currently-selected node — either
  * a SQLTask or an input view.
  *
  * Task layout, top → bottom:
  *   * Header: task name + colored status pill + duration/rows chips
  *   * "Reads from": dep chips (tasks + inputs)
  *   * "Writes to": output path + provenance + historical-runs hint
  *   * Error panel: only visible when status is Failed / ValidationFailed
  *   * Validations preview + "Inspect" button to jump to the bottom tab
  *   * Source SQL pane (syntax-highlighted, with Copy + Open in editor)
  *   * Rendered SQL pane (syntax-highlighted, with Copy)
  *
  * Input layout swaps SQL views for input options and a "used by" task list.
  */
final class TaskDetailsPanel(session: JobSession) extends VBox {

  private val PanelBg     = "#2a2c38"
  private val MutedText   = "#9ba2b8"
  private val Heading     = "#e8ecf5"
  private val PathColor   = "#8ab4f8"

  private val TimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

  /** Optional callback wired up by [[GuiApp]]: tells the bottom pane to switch
    * to the Validations tab and load the selected task's validations.
    */
  private var onInspectValidations: Int => Unit = _ => ()
  def setOnInspectValidations(handler: Int => Unit): Unit = { onInspectValidations = handler }

  setSpacing(10)
  setPadding(new Insets(14))
  setPrefWidth(440)
  setStyle(s"-fx-background-color: $PanelBg;")

  // ---- Header row -----------------------------------------------------------
  private val nameLabel = new Label("(no task selected)")
  nameLabel.setStyle(s"-fx-text-fill: $Heading;")
  nameLabel.setFont(Font.font("Sans", FontWeight.BOLD, 17))
  nameLabel.setWrapText(true)
  HBox.setHgrow(nameLabel, Priority.ALWAYS)
  nameLabel.setMaxWidth(Double.MaxValue)

  private val statusPill = new Label("")
  styleChip(statusPill, ChipPalette.Neutral)
  statusPill.setVisible(false)
  statusPill.setManaged(false)

  private val header = new HBox(8, nameLabel, statusPill)
  header.setAlignment(Pos.CENTER_LEFT)

  // ---- Stat chips (rows, duration, validations) -----------------------------
  private val statChips = new FlowPane(6, 4)
  statChips.setAlignment(Pos.CENTER_LEFT)

  // ---- Dependencies (used as "Reads from" / "Used by" depending on mode) ----
  private val depsHeader = sectionLabel("Reads from")
  private val depsBox = new FlowPane(6, 4)
  depsBox.setAlignment(Pos.CENTER_LEFT)
  private val noDepsLabel = mutedLabel("(no upstream tasks)")

  // ---- Output path ----------------------------------------------------------
  private val outputHeader = sectionLabel("Writes to")
  private val outputPathLabel = new Label("")
  outputPathLabel.setStyle(s"-fx-text-fill: $PathColor; -fx-font-size: 12px; -fx-font-family: monospace;")
  outputPathLabel.setWrapText(true)
  private val outputMetaLabel = mutedLabel("")
  outputMetaLabel.setStyle(s"-fx-text-fill: $MutedText; -fx-font-size: 11px;")
  outputMetaLabel.setWrapText(true)
  private val historyHintLabel = mutedLabel("")
  historyHintLabel.setStyle("-fx-text-fill: #b9c2db; -fx-font-size: 11px; -fx-font-style: italic;")
  historyHintLabel.setWrapText(true)
  private val outputBox = new VBox(3, outputPathLabel, outputMetaLabel, historyHintLabel)

  // ---- Error block (only visible when relevant) ----------------------------
  private val errorLabel = new Label("")
  errorLabel.setStyle("-fx-text-fill: #f8a3a3; -fx-font-family: monospace; -fx-font-size: 11px;")
  errorLabel.setWrapText(true)
  private val errorScroll = new ScrollPane(errorLabel)
  errorScroll.setFitToWidth(true)
  errorScroll.setPrefHeight(90)
  errorScroll.setStyle("-fx-background: #3a2128; -fx-background-color: #3a2128;")
  errorScroll.setVisible(false)
  errorScroll.setManaged(false)

  // ---- Validations summary + inspect button --------------------------------
  private val validationsHeader = sectionLabel("Validations")
  private val validationsSummary = new FlowPane(6, 4)
  validationsSummary.setAlignment(Pos.CENTER_LEFT)
  private val inspectValidationsButton = new Button("Inspect validations")
  inspectValidationsButton.setStyle(
    "-fx-background-color: #3a3f55; -fx-text-fill: #d7dcec; " +
      "-fx-background-radius: 4; -fx-padding: 4 10; -fx-font-size: 11px;"
  )
  inspectValidationsButton.setOnAction(_ =>
    session.selectedTaskIndex.foreach(onInspectValidations)
  )
  private val validationsRow = new HBox(8, validationsSummary, inspectValidationsButton)
  validationsRow.setAlignment(Pos.CENTER_LEFT)
  private val validationsBox = new VBox(4, validationsHeader, validationsRow)
  validationsBox.setVisible(false)
  validationsBox.setManaged(false)

  // ---- SQL views ------------------------------------------------------------
  private val sourceSqlHeader = sectionLabel("Source SQL")
  private val sourceSqlView = new SqlView(showOpenInEditor = true)
  private val renderedSqlHeader = sectionLabel("Rendered SQL")
  private val renderedSqlView = new SqlView(showOpenInEditor = false)
  sourceSqlView.setPrefHeight(220)
  renderedSqlView.setPrefHeight(220)
  VBox.setVgrow(sourceSqlView, Priority.ALWAYS)
  VBox.setVgrow(renderedSqlView, Priority.ALWAYS)

  // ---- Input-only widgets ---------------------------------------------------
  private val inputOptionsHeader = sectionLabel("Options")
  private val inputOptionsBox = new VBox(2)
  private val inputOptionsContainer = new VBox(4, inputOptionsHeader, inputOptionsBox)
  inputOptionsContainer.setVisible(false)
  inputOptionsContainer.setManaged(false)

  // ---- Assemble -------------------------------------------------------------
  getChildren.addAll(
    header,
    statChips,
    new Separator(),
    depsHeader,
    depsBox,
    outputHeader,
    outputBox,
    errorScroll,
    validationsBox,
    inputOptionsContainer,
    new Separator(),
    sourceSqlHeader,
    sourceSqlView,
    renderedSqlHeader,
    renderedSqlView
  )

  session.addListener(() => refresh())
  refresh()

  // --------------------------------------------------------------------------

  private def refresh(): Unit = {
    session.selection match {
      case Some(Selection.Task(i)) =>
        showTask(i)
      case Some(Selection.Input(i)) =>
        showInput(i)
      case None =>
        showNothing()
    }
  }

  private def showTask(i: Int): Unit = {
    val dag = session.dag.getOrElse { showNothing(); return }
    if (i < 0 || i >= dag.nodes.size) { showNothing(); return }
    val node = dag.nodes(i)
    val task = node.task
    setSqlMode(visible = true)
    setInputMode(visible = false)
    nameLabel.setText(task.displayName)
    depsHeader.setText("Reads from")
    renderStatus(i)
    renderStatChips(i)
    renderTaskDeps(node)
    renderOutput(i)
    updateErrorPanel(i)
    renderValidationsSummary(i)
    sourceSqlView.setSql(safeLoad(task.loadSql _))
    sourceSqlView.setSourceFile(task.sqlFile.map(Paths.get(_)))
    renderedSqlView.setSql(node.renderedMainSql)
    renderedSqlView.setSourceFile(None)
  }

  private def showInput(i: Int): Unit = {
    val inputs = session.inputs
    if (i < 0 || i >= inputs.size) { showNothing(); return }
    val in = inputs(i)
    setSqlMode(visible = false)
    setInputMode(visible = true)
    nameLabel.setText(in.viewName)
    depsHeader.setText("Used by")
    // Static "input" pill so the user can tell at a glance.
    statusPill.setText(s"INPUT • ${in.detectedFormat.toUpperCase}")
    styleChip(statusPill, ChipPalette.Input)
    statusPill.setVisible(true); statusPill.setManaged(true)
    statChips.getChildren.clear()
    statChips.getChildren.add(metricChip(if (in.cache) "cached in memory" else "streamed"))
    renderInputUsedBy(i)
    renderInputPath(i, in)
    setErrorText("")
    validationsBox.setVisible(false); validationsBox.setManaged(false)
    renderInputOptions(in)
  }

  private def showNothing(): Unit = {
    setSqlMode(visible = true)
    setInputMode(visible = false)
    nameLabel.setText("(nothing selected)")
    statusPill.setVisible(false); statusPill.setManaged(false)
    statChips.getChildren.clear()
    depsHeader.setText("Reads from")
    depsBox.getChildren.setAll(noDepsLabel)
    outputHeader.setText("Writes to")
    outputPathLabel.setText("Click a task node to inspect it.")
    outputPathLabel.setTooltip(null)
    outputMetaLabel.setText("Double-click a task node to load its output rows.")
    outputMetaLabel.setVisible(true); outputMetaLabel.setManaged(true)
    historyHintLabel.setText("")
    historyHintLabel.setVisible(false); historyHintLabel.setManaged(false)
    setErrorText("")
    validationsBox.setVisible(false); validationsBox.setManaged(false)
    sourceSqlView.clear()
    sourceSqlView.setSourceFile(None)
    renderedSqlView.clear()
    renderedSqlView.setSourceFile(None)
  }

  private def setSqlMode(visible: Boolean): Unit = {
    sourceSqlHeader.setVisible(visible); sourceSqlHeader.setManaged(visible)
    sourceSqlView.setVisible(visible);   sourceSqlView.setManaged(visible)
    renderedSqlHeader.setVisible(visible); renderedSqlHeader.setManaged(visible)
    renderedSqlView.setVisible(visible);   renderedSqlView.setManaged(visible)
  }

  private def setInputMode(visible: Boolean): Unit = {
    inputOptionsContainer.setVisible(visible)
    inputOptionsContainer.setManaged(visible)
  }

  private def renderStatus(i: Int): Unit = {
    val states = session.taskStates
    if (i >= states.size) {
      statusPill.setVisible(false); statusPill.setManaged(false)
      return
    }
    val (label, palette) = states(i) match {
      case UiTaskState.Pending => ("pending", ChipPalette.Neutral)
      case UiTaskState.Running => ("running", ChipPalette.Running)
      case UiTaskState.Done(result) =>
        result.status match {
          case TaskStatus.Succeeded            => ("succeeded", ChipPalette.Success)
          case TaskStatus.Failed(_)            => ("failed", ChipPalette.Failed)
          case TaskStatus.ValidationFailed(_)  => ("validation failed", ChipPalette.Warning)
          case TaskStatus.Skipped(_)           => ("skipped", ChipPalette.Neutral)
          case TaskStatus.Pending              => ("pending", ChipPalette.Neutral)
        }
    }
    statusPill.setText(label.toUpperCase)
    styleChip(statusPill, palette)
    statusPill.setVisible(true); statusPill.setManaged(true)
  }

  private def renderStatChips(i: Int): Unit = {
    statChips.getChildren.clear()
    val states = session.taskStates
    if (i >= states.size) return
    states(i) match {
      case UiTaskState.Done(result) if result.status == TaskStatus.Succeeded =>
        statChips.getChildren.add(metricChip(f"${result.rowsProduced}%,d rows"))
        statChips.getChildren.add(metricChip(s"${result.durationMillis} ms"))
        validationsCountChip(i).foreach(statChips.getChildren.add)
      case UiTaskState.Done(result) =>
        statChips.getChildren.add(metricChip(s"${result.durationMillis} ms"))
        validationsCountChip(i).foreach(statChips.getChildren.add)
      case UiTaskState.Running =>
        statChips.getChildren.add(metricChip("running…"))
      case UiTaskState.Pending =>
        validationsCountChip(i).foreach(statChips.getChildren.add)
    }
  }

  private def validationsCountChip(i: Int): Option[Label] = {
    val n = session.dag.flatMap(_.nodes.lift(i)).map(_.task.validations.size).getOrElse(0)
    if (n == 0) None else Some(metricChip(s"$n validation${if (n == 1) "" else "s"}"))
  }

  private def renderTaskDeps(node: com.transformer.job.TaskDagNode): Unit = {
    depsBox.getChildren.clear()
    val dag = session.dag.getOrElse(return)
    val inputs = session.inputs
    val inputNameToIdx = inputs.iterator.zipWithIndex.map { case (in, i) =>
      in.viewName.toLowerCase -> i
    }.toMap
    if (node.deps.isEmpty && node.inputDeps.isEmpty) {
      depsBox.getChildren.add(noDepsLabel)
      return
    }
    // Inputs first (the data lineage roots), then upstream tasks.
    node.inputDeps.toSeq.sorted.foreach { name =>
      inputNameToIdx.get(name).foreach { idx =>
        val chip = inputChip(inputs(idx).viewName)
        chip.setOnMouseClicked(_ => session.select(Some(Selection.Input(idx))))
        depsBox.getChildren.add(chip)
      }
    }
    node.deps.toSeq.sorted.foreach { d =>
      val name = dag.nodes(d).task.displayName
      val chip = depChip(name)
      chip.setOnMouseClicked(_ => session.select(Some(Selection.Task(d))))
      depsBox.getChildren.add(chip)
    }
  }

  private def renderInputUsedBy(i: Int): Unit = {
    depsBox.getChildren.clear()
    val dag = session.dag.getOrElse {
      depsBox.getChildren.add(mutedLabel("(no DAG loaded)"))
      return
    }
    val inputs = session.inputs
    if (i >= inputs.size) {
      depsBox.getChildren.add(mutedLabel("(unknown input)"))
      return
    }
    val viewLower = inputs(i).viewName.toLowerCase
    val users = dag.nodes.iterator.filter(_.inputDeps.contains(viewLower)).toSeq
    if (users.isEmpty) {
      depsBox.getChildren.add(mutedLabel("(not used by any task)"))
      return
    }
    users.foreach { node =>
      val chip = depChip(node.task.displayName)
      val idx = node.index
      chip.setOnMouseClicked(_ => session.select(Some(Selection.Task(idx))))
      depsBox.getChildren.add(chip)
    }
  }

  private def renderInputPath(i: Int, in: InputFilePath): Unit = {
    outputHeader.setText("File")
    val rendered = session.renderedInputPath(i).getOrElse(in.path)
    outputPathLabel.setText(rendered)
    outputPathLabel.setTooltip(new Tooltip(rendered))
    val source =
      if (rendered != in.path) s"format: ${in.detectedFormat} • template: ${in.path}"
      else s"format: ${in.detectedFormat}"
    outputMetaLabel.setText(source)
    outputMetaLabel.setVisible(true); outputMetaLabel.setManaged(true)
    historyHintLabel.setText("Double-click the input node to preview its rows in the Output data tab.")
    historyHintLabel.setVisible(true); historyHintLabel.setManaged(true)
  }

  private def renderInputOptions(in: InputFilePath): Unit = {
    inputOptionsBox.getChildren.clear()
    if (in.options.isEmpty) {
      inputOptionsBox.getChildren.add(mutedLabel("(default options)"))
    } else {
      in.options.toSeq.sortBy(_._1).foreach { case (k, v) =>
        val l = new Label(s"$k = $v")
        l.setStyle("-fx-text-fill: #d7dcec; -fx-font-size: 11px; -fx-font-family: monospace;")
        inputOptionsBox.getChildren.add(l)
      }
    }
  }

  private def renderOutput(i: Int): Unit = {
    val planned = session.plannedOutputPathFor(i)
    val written = session.outputPathFor(i)
    val state = session.taskStates.lift(i)
    val (verb, path) = (planned, state, written) match {
      case (Some(p), Some(UiTaskState.Done(_)), Some(actual)) if actual == p => ("Wrote to", Some(p))
      case (Some(p), Some(UiTaskState.Done(_)), Some(actual))                => ("Wrote to", Some(actual))
      case (Some(p), _, _)                                                   => ("Will write to", Some(p))
      case (None, _, _)                                                      => ("Output", None)
    }
    path match {
      case Some(p) =>
        outputPathLabel.setText(p)
        outputPathLabel.setTooltip(new Tooltip(p))
      case None =>
        outputPathLabel.setText("(no outputFile — nothing will be persisted)")
        outputPathLabel.setTooltip(null)
    }
    outputHeader.setText(verb)
    outputMetaLabel.setText(formatMarkerMeta(session.markerFor(i)))
    val total = session.historicalRunsFor(i).size
    historyHintLabel.setText(
      if (total >= 2) s"$total historical run${if (total == 1) "" else "s"} on disk — double-click the node to browse them in the Output tab."
      else ""
    )
    outputMetaLabel.setVisible(outputMetaLabel.getText.nonEmpty)
    outputMetaLabel.setManaged(outputMetaLabel.getText.nonEmpty)
    historyHintLabel.setVisible(historyHintLabel.getText.nonEmpty)
    historyHintLabel.setManaged(historyHintLabel.getText.nonEmpty)
  }

  /** Compact one-line `2 rows • 1 part file • written 2026-…` summary of the
    * task's most recent `_SUCCESS` marker, plus a note if the marker's
    * executionTime differs from the session's.
    */
  private def formatMarkerMeta(marker: Option[RunMarker]): String = marker match {
    case None => ""
    case Some(m) =>
      val plural = if (m.outputFiles.size == 1) "" else "s"
      val mismatch =
        if (m.executionTime != session.executionTime)
          s" • this run's exec=${TimeFmt.format(m.executionTime)} differs from selected ${TimeFmt.format(session.executionTime)}"
        else ""
      f"${m.rowsProduced}%,d rows • ${m.outputFiles.size} part file$plural • written ${TimeFmt.format(m.writtenAt)}$mismatch"
  }

  private def updateErrorPanel(i: Int): Unit = {
    val states = session.taskStates
    if (i >= states.size) { setErrorText(""); return }
    states(i) match {
      case UiTaskState.Done(result) =>
        result.status match {
          case TaskStatus.Failed(reason) =>
            setErrorText(s"Error:\n$reason")
          case TaskStatus.ValidationFailed(fs) =>
            val body = fs.iterator
              .map(f => s"• ${f.validationName} — ${f.rowCount} failing row(s)\n${indent(f.sampleRowsCsv, "    ")}")
              .mkString("\n\n")
            setErrorText(s"Validation failures:\n$body")
          case TaskStatus.Skipped(reason) =>
            setErrorText(s"Skipped: $reason")
          case _ =>
            setErrorText("")
        }
      case _ =>
        setErrorText("")
    }
  }

  private def renderValidationsSummary(i: Int): Unit = {
    val dag = session.dag.getOrElse {
      validationsBox.setVisible(false); validationsBox.setManaged(false); return
    }
    val node = dag.nodes.lift(i).getOrElse {
      validationsBox.setVisible(false); validationsBox.setManaged(false); return
    }
    val validations = node.task.validations
    if (validations.isEmpty) {
      validationsBox.setVisible(false); validationsBox.setManaged(false); return
    }
    validationsBox.setVisible(true); validationsBox.setManaged(true)
    validationsSummary.getChildren.clear()
    val state = session.taskStates.lift(i)
    validations.foreach { v =>
      val (label, palette) = validationStatusFor(v, state)
      validationsSummary.getChildren.add(smallChip(s"${v.name}: $label", palette))
    }
  }

  /** Per-validation status derived from the surrounding task's status. The
    * runner doesn't keep per-validation outcomes for the passing ones, but a
    * passing task implies all its validations passed.
    */
  private def validationStatusFor(v: Validation, state: Option[UiTaskState]): (String, ChipPalette) = state match {
    case Some(UiTaskState.Done(result)) =>
      result.status match {
        case TaskStatus.Succeeded => ("passed", ChipPalette.Success)
        case TaskStatus.ValidationFailed(fs) =>
          fs.find(_.validationName == v.name) match {
            case Some(f) => (s"failed (${f.rowCount} row${if (f.rowCount == 1) "" else "s"})", ChipPalette.Failed)
            case None    => ("passed", ChipPalette.Success)
          }
        case TaskStatus.Failed(_)  => ("not run", ChipPalette.Neutral)
        case TaskStatus.Skipped(_) => ("not run", ChipPalette.Neutral)
        case TaskStatus.Pending    => ("pending", ChipPalette.Neutral)
      }
    case Some(UiTaskState.Running) => ("running", ChipPalette.Running)
    case Some(UiTaskState.Pending) | None => ("pending", ChipPalette.Neutral)
  }

  private def setErrorText(s: String): Unit = {
    errorLabel.setText(s)
    val visible = s.nonEmpty
    errorScroll.setVisible(visible)
    errorScroll.setManaged(visible)
  }

  private def safeLoad(f: () => String): String =
    try f() catch { case t: Throwable => s"(failed to load SQL: ${t.getMessage})" }

  private def sectionLabel(text: String): Label = {
    val l = new Label(text)
    l.setStyle(s"-fx-text-fill: $Heading; -fx-font-size: 11px; -fx-font-weight: bold;")
    l
  }

  private def mutedLabel(text: String): Label = {
    val l = new Label(text)
    l.setStyle(s"-fx-text-fill: $MutedText; -fx-font-size: 11px;")
    l
  }

  private def metricChip(text: String): Label = {
    val l = new Label(text)
    l.setStyle(
      "-fx-background-color: #34384a; " +
        "-fx-text-fill: #d7dcec; " +
        "-fx-padding: 1 8; " +
        "-fx-background-radius: 10; " +
        "-fx-font-size: 11px;"
    )
    l
  }

  private def depChip(text: String): Label = {
    val l = new Label(text)
    l.setStyle(
      "-fx-background-color: #2b4f7a; " +
        "-fx-text-fill: #d7dcec; " +
        "-fx-padding: 2 10; " +
        "-fx-background-radius: 10; " +
        "-fx-border-color: #4a90e2; " +
        "-fx-border-radius: 10; " +
        "-fx-font-size: 11px; " +
        "-fx-font-family: monospace; " +
        "-fx-cursor: hand;"
    )
    l
  }

  private def inputChip(text: String): Label = {
    val l = new Label(text)
    l.setStyle(
      "-fx-background-color: #2b4a3a; " +
        "-fx-text-fill: #d7dcec; " +
        "-fx-padding: 2 10; " +
        "-fx-background-radius: 10; " +
        "-fx-border-color: #5fa17a; " +
        "-fx-border-radius: 10; " +
        "-fx-font-size: 11px; " +
        "-fx-font-family: monospace; " +
        "-fx-cursor: hand;"
    )
    l
  }

  private def smallChip(text: String, palette: ChipPalette): Label = {
    val l = new Label(text)
    l.setStyle(
      s"-fx-background-color: ${palette.bg}; " +
        s"-fx-text-fill: ${palette.fg}; " +
        "-fx-padding: 1 8; " +
        "-fx-background-radius: 10; " +
        "-fx-font-size: 11px;"
    )
    l
  }

  private def styleChip(label: Label, palette: ChipPalette): Unit = {
    label.setStyle(
      s"-fx-background-color: ${palette.bg}; " +
        s"-fx-text-fill: ${palette.fg}; " +
        "-fx-padding: 3 10; " +
        "-fx-background-radius: 10; " +
        "-fx-font-size: 11px; " +
        "-fx-font-weight: bold;"
    )
  }

  private def indent(s: String, prefix: String): String =
    s.split('\n').iterator.map(prefix + _).mkString("\n")
}

private final case class ChipPalette(bg: String, fg: String)
private object ChipPalette {
  val Neutral = ChipPalette("#3a3f55", "#d7dcec")
  val Running = ChipPalette("#2b4f7a", "#ffffff")
  val Success = ChipPalette("#1f5b30", "#ffffff")
  val Warning = ChipPalette("#7a5a22", "#ffffff")
  val Failed  = ChipPalette("#5a2828", "#ffffff")
  val Input   = ChipPalette("#2b4a3a", "#9bd1ad")
}
