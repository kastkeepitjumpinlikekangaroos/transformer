package com.transformer.gui

import com.transformer.job.{InputFilePath, JobFiles, TaskRunRecord, TaskRunStatus, TaskStatus, Validation}

import java.nio.file.{Path, Paths}
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Alert, Button, ButtonType, Label, ScrollPane, Separator, Toggle, ToggleButton, ToggleGroup, Tooltip}
import javafx.scene.layout.{FlowPane, HBox, Priority, Region, VBox}
import javafx.scene.text.{Font, FontWeight}
import javafx.stage.Stage
import scala.util.control.NonFatal

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
final class TaskDetailsPanel(session: JobSession, ownerStage: () => Stage) extends VBox {

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
  setStyle(s"-fx-background-color: $PanelBg;")

  // ---- Toolbar (edit attrs / add / delete) ----------------------------------
  // Buttons whose enabled-state depends on the kind of node selected (task vs
  // input vs nothing). Edit/Save/Cancel-SQL live in the SQL header row below,
  // so they sit right next to the SQL viewer they act on. See
  // [[updateToolbarState]] for the truth table.
  private val editAttrsButton      = makeToolButton("Edit attrs…")
  private val editSqlButton        = makeToolButton("Edit SQL")
  private val saveSqlButton        = makePrimaryToolButton("Save SQL")
  private val cancelSqlButton      = makeToolButton("Cancel")
  private val addTableButton       = makeToolButton("+ Table…")
  private val addValidationButton  = makeToolButton("+ Validation…")
  private val deleteButton         = makeToolButton("Delete…")

  editAttrsButton.setOnAction(_ => onEditAttrs())
  editSqlButton.setOnAction(_ => onBeginSqlEdit())
  saveSqlButton.setOnAction(_ => onSaveSqlEdit())
  cancelSqlButton.setOnAction(_ => onCancelSqlEdit())
  addTableButton.setOnAction(_ => onAddTable())
  addValidationButton.setOnAction(_ => onAddValidation())
  deleteButton.setOnAction(_ => onDeleteSelected())

  // Save/Cancel only appear while editing SQL.
  saveSqlButton.setVisible(false);   saveSqlButton.setManaged(false)
  cancelSqlButton.setVisible(false); cancelSqlButton.setManaged(false)

  private val toolbar = new HBox(6,
    editAttrsButton, addTableButton, addValidationButton, deleteButton
  )
  toolbar.setAlignment(Pos.CENTER_LEFT)

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

  // ---- SQL view (Source / Rendered toggle) ----------------------------------
  // One SqlView swapped between the on-disk source and the template-rendered
  // body via a two-button toggle group. Keeps the panel short enough to live
  // in a bottom tab instead of needing the full window height for two stacked
  // viewers.
  private val sourceToggle = new ToggleButton("Source")
  private val renderedToggle = new ToggleButton("Rendered")
  private val sqlToggleGroup = new ToggleGroup()
  Seq(sourceToggle, renderedToggle).foreach { tb =>
    tb.setToggleGroup(sqlToggleGroup)
    tb.setStyle(
      "-fx-background-color: #3a3f55; -fx-text-fill: #d7dcec; " +
        "-fx-padding: 2 12; -fx-font-size: 11px; -fx-background-radius: 4;"
    )
  }
  sourceToggle.setSelected(true)
  private val sqlHeader = sectionLabel("SQL")
  private val sqlHeaderSpacer = new Region()
  HBox.setHgrow(sqlHeaderSpacer, Priority.ALWAYS)
  // Edit / Save / Cancel sit on the right side of the SQL header so the
  // controls are immediately adjacent to the viewer they act on. The toolbar
  // up top is reserved for whole-task actions.
  private val sqlHeaderRow = new HBox(8,
    sqlHeader, sourceToggle, renderedToggle,
    sqlHeaderSpacer,
    editSqlButton, saveSqlButton, cancelSqlButton
  )
  sqlHeaderRow.setAlignment(Pos.CENTER_LEFT)
  private val sqlView = new SqlView(showOpenInEditor = true)
  sqlView.setPrefHeight(280)
  VBox.setVgrow(sqlView, Priority.ALWAYS)

  // Backing state — the SqlView is reused, but we keep both bodies around so
  // toggling between Source / Rendered is instant (no reload of the source
  // file or re-render of templates).
  private var currentSourceSql: String = ""
  private var currentRenderedSql: String = ""
  private var currentSourceFile: Option[Path] = None

  // Inline edit-mode bookkeeping. Only the task's main SQL is edited in place;
  // attribute changes and validation edits open their own modal dialogs.
  // Selection changes while editing are treated as an implicit cancel.
  private var sqlEditing: Boolean = false
  private var editingViewName: Option[String] = None

  // Keep one toggle always selected (forbid the unselected-both state JavaFX
  // otherwise allows when the user clicks an already-active toggle).
  sqlToggleGroup.selectedToggleProperty().addListener((_, oldT: Toggle, newT: Toggle) => {
    if (newT == null && oldT != null) oldT.setSelected(true)
    else updateSqlView()
  })

  private def updateSqlView(): Unit = {
    if (renderedToggle.isSelected) {
      sqlView.setSql(currentRenderedSql)
      sqlView.setSourceFile(None)
    } else {
      sqlView.setSql(currentSourceSql)
      sqlView.setSourceFile(currentSourceFile)
    }
  }

  // ---- Input-only widgets ---------------------------------------------------
  private val inputOptionsHeader = sectionLabel("Options")
  private val inputOptionsBox = new VBox(2)
  private val inputOptionsContainer = new VBox(4, inputOptionsHeader, inputOptionsBox)
  inputOptionsContainer.setVisible(false)
  inputOptionsContainer.setManaged(false)

  // ---- Assemble -------------------------------------------------------------
  getChildren.addAll(
    toolbar,
    new Separator(),
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
    sqlHeaderRow,
    sqlView
  )

  session.addListener(() => refresh())
  refresh()

  // --------------------------------------------------------------------------

  private def refresh(): Unit = {
    // If the user moved the selection while editing SQL, drop the edit silently —
    // the alternative (auto-saving or blocking the navigation) is more surprising.
    if (sqlEditing) {
      val stillOnTarget = session.selectedTaskIndex.flatMap { idx =>
        session.dag.flatMap(_.nodes.lift(idx)).flatMap(_.task.viewName)
      }
      if (stillOnTarget != editingViewName) cancelSqlEditState()
    }
    session.selection match {
      case Some(Selection.Task(i)) =>
        showTask(i)
      case Some(Selection.Input(i)) =>
        showInput(i)
      case None =>
        showNothing()
    }
    updateToolbarState()
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
    currentSourceSql = safeLoad(task.loadSql _)
    currentRenderedSql = node.renderedMainSql
    currentSourceFile = task.sqlFile.map(Paths.get(_))
    updateSqlView()
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
    currentSourceSql = ""
    currentRenderedSql = ""
    currentSourceFile = None
    updateSqlView()
  }

  private def setSqlMode(visible: Boolean): Unit = {
    sqlHeaderRow.setVisible(visible); sqlHeaderRow.setManaged(visible)
    sqlView.setVisible(visible);      sqlView.setManaged(visible)
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
    outputMetaLabel.setText(formatRecordMeta(session.taskRecordFor(i)))
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
    * task's most recent `_run.json` record, plus a note if the recorded
    * executionTime differs from the session's and a status tag for non-
    * Succeeded outcomes (Validation failures, runtime errors, skips).
    */
  private def formatRecordMeta(record: Option[TaskRunRecord]): String = record match {
    case None => ""
    case Some(r) =>
      val plural = if (r.outputFiles.size == 1) "" else "s"
      val mismatch =
        if (r.executionTime != session.executionTime)
          s" • this run's exec=${TimeFmt.format(r.executionTime)} differs from selected ${TimeFmt.format(session.executionTime)}"
        else ""
      val statusTag = r.status match {
        case TaskRunStatus.Succeeded        => ""
        case TaskRunStatus.ValidationFailed => " • validation failed"
        case TaskRunStatus.Failed           => " • failed"
        case TaskRunStatus.Skipped          => " • skipped"
      }
      f"${r.rowsProduced}%,d rows • ${r.outputFiles.size} part file$plural • written ${TimeFmt.format(r.writtenAt)}$statusTag$mismatch"
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
      val chip = smallChip(s"${v.name}: $label", palette)
      chip.setTooltip(new Tooltip("Click to edit or delete this validation"))
      chip.setStyle(chip.getStyle + " -fx-cursor: hand;")
      chip.setOnMouseClicked(_ => openValidationEditor(i, v))
      validationsSummary.getChildren.add(chip)
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

  // ---- Toolbar action handlers ----------------------------------------------

  /** True only when the GUI has a writeable job dir loaded. Without one, no
    * action button can do anything useful. */
  private def hasJobDir: Boolean = session.jobDir.isDefined

  private def updateToolbarState(): Unit = {
    val taskSelected = session.selectedTaskIndex.isDefined
    val inputSelected = session.selectedInputIndex.isDefined
    val anySelected = taskSelected || inputSelected

    // Edit/save/cancel-SQL: only meaningful for tasks.
    if (sqlEditing) {
      editAttrsButton.setVisible(false);   editAttrsButton.setManaged(false)
      editSqlButton.setVisible(false);     editSqlButton.setManaged(false)
      saveSqlButton.setVisible(true);      saveSqlButton.setManaged(true)
      cancelSqlButton.setVisible(true);    cancelSqlButton.setManaged(true)
      addTableButton.setDisable(true)
      addValidationButton.setDisable(true)
      deleteButton.setDisable(true)
    } else {
      editAttrsButton.setVisible(true);    editAttrsButton.setManaged(true)
      editSqlButton.setVisible(true);      editSqlButton.setManaged(true)
      saveSqlButton.setVisible(false);     saveSqlButton.setManaged(false)
      cancelSqlButton.setVisible(false);   cancelSqlButton.setManaged(false)
      editAttrsButton.setDisable(!hasJobDir || !anySelected)
      editSqlButton.setDisable(!hasJobDir || !taskSelected)
      // "+ Table" is enabled whenever a job is loaded (a selected node just
      // pre-fills the FROM stub; no selection is fine too).
      addTableButton.setDisable(!hasJobDir)
      addValidationButton.setDisable(!hasJobDir || !taskSelected)
      deleteButton.setDisable(!hasJobDir || !anySelected)
    }
  }

  private def onEditAttrs(): Unit = {
    val dir = session.jobDir.getOrElse(return)
    session.selection match {
      case Some(Selection.Task(i)) =>
        session.dag.flatMap(_.nodes.lift(i)).foreach { node =>
          AddTableDialog.showEdit(ownerStage(), dir, node.task).foreach { name =>
            session.reloadPreservingSelection(Some(name))
          }
        }
      case Some(Selection.Input(i)) =>
        session.inputs.lift(i).foreach { in =>
          AddInputDialog.showEdit(ownerStage(), dir, in).foreach { name =>
            session.reloadPreservingSelection(Some(name))
          }
        }
      case None => ()
    }
  }

  private def onBeginSqlEdit(): Unit = {
    if (!session.jobDir.isDefined) return
    val idx = session.selectedTaskIndex.getOrElse(return)
    val task = session.dag.flatMap(_.nodes.lift(idx)).map(_.task).getOrElse(return)
    val viewName = task.viewName.getOrElse(return)
    // Force the Source toggle on — editing the rendered SQL would be confusing
    // (the user's writing the file, not the post-template version). Disabling
    // the toggle while editing avoids the trap of clicking it mid-edit and
    // having setSql() clobber the edit buffer.
    sourceToggle.setSelected(true)
    sourceToggle.setDisable(true)
    renderedToggle.setDisable(true)
    sqlEditing = true
    editingViewName = Some(viewName)
    sqlView.setEditable(true)
    updateToolbarState()
  }

  private def onSaveSqlEdit(): Unit = {
    val dir = session.jobDir.getOrElse { cancelSqlEditState(); return }
    val viewName = editingViewName.getOrElse { cancelSqlEditState(); return }
    val newSql = sqlView.getCurrentSql
    try {
      JobFiles.writeMainSql(dir, viewName, newSql)
    } catch {
      case NonFatal(e) =>
        FxHelpers.showError(ownerStage(), "Couldn't save SQL",
          Option(e.getMessage).getOrElse(e.toString))
        return
    }
    cancelSqlEditState()
    session.reloadPreservingSelection(Some(viewName))
  }

  private def onCancelSqlEdit(): Unit = {
    cancelSqlEditState()
    refresh()
  }

  /** Clear edit-mode state without touching the rest of the UI. Used both by
    * the explicit Cancel button and by [[refresh]] when the user navigates
    * away mid-edit.
    */
  private def cancelSqlEditState(): Unit = {
    sqlEditing = false
    editingViewName = None
    sqlView.setEditable(false)
    sourceToggle.setDisable(false)
    renderedToggle.setDisable(false)
    updateToolbarState()
  }

  private def onAddTable(): Unit = {
    val dir = session.jobDir.getOrElse(return)
    val upstream = currentUpstreamViewName
    AddTableDialog.showAdd(ownerStage(), dir, upstream).foreach { name =>
      session.reloadPreservingSelection(Some(name))
    }
  }

  private def onAddValidation(): Unit = {
    val dir = session.jobDir.getOrElse(return)
    val idx = session.selectedTaskIndex.getOrElse(return)
    val viewName = session.dag.flatMap(_.nodes.lift(idx)).flatMap(_.task.viewName).getOrElse(return)
    AddValidationDialog.showAdd(ownerStage(), dir, viewName).foreach { _ =>
      session.reloadPreservingSelection(Some(viewName))
    }
  }

  private def onDeleteSelected(): Unit = {
    val dir = session.jobDir.getOrElse(return)
    session.selection match {
      case Some(Selection.Task(i)) =>
        session.dag.flatMap(_.nodes.lift(i)).flatMap(_.task.viewName).foreach { name =>
          if (confirmDelete(s"Delete table '$name'?",
              s"$name's main.sql, output.json, and validations/ will be removed from disk.")) {
            try {
              JobFiles.deleteTable(dir, name)
              session.reloadPreservingSelection(None)
            } catch {
              case NonFatal(e) =>
                FxHelpers.showError(ownerStage(), "Couldn't delete table",
                  Option(e.getMessage).getOrElse(e.toString))
            }
          }
        }
      case Some(Selection.Input(i)) =>
        session.inputs.lift(i).foreach { in =>
          if (confirmDelete(s"Delete input '${in.viewName}'?",
              s"${in.viewName}'s config.json will be removed from disk (the data file itself is not touched).")) {
            try {
              JobFiles.deleteInput(dir, in.viewName)
              session.reloadPreservingSelection(None)
            } catch {
              case NonFatal(e) =>
                FxHelpers.showError(ownerStage(), "Couldn't delete input",
                  Option(e.getMessage).getOrElse(e.toString))
            }
          }
        }
      case None => ()
    }
  }

  /** The viewName for the currently-selected node, regardless of kind. Used to
    * pre-fill the upstream FROM stub when adding a new table. */
  private def currentUpstreamViewName: Option[String] =
    session.selectedTaskIndex.flatMap(i =>
      session.dag.flatMap(_.nodes.lift(i)).flatMap(_.task.viewName)
    ).orElse(
      session.selectedInputIndex.flatMap(i => session.inputs.lift(i).map(_.viewName))
    )

  /** Open a validation in its edit dialog (with a Delete button). Triggered by
    * clicking the validation's chip in the summary row. */
  private def openValidationEditor(taskIndex: Int, v: Validation): Unit = {
    val dir = session.jobDir.getOrElse(return)
    val tableViewName = session.dag.flatMap(_.nodes.lift(taskIndex))
      .flatMap(_.task.viewName).getOrElse(return)
    AddValidationDialog.showEdit(ownerStage(), dir, tableViewName, v.name, v.sqlFile).foreach { _ =>
      session.reloadPreservingSelection(Some(tableViewName))
    }
  }

  private def confirmDelete(header: String, body: String): Boolean = {
    val alert = new Alert(Alert.AlertType.CONFIRMATION, body)
    alert.initOwner(ownerStage())
    alert.setHeaderText(header)
    alert.setTitle("Confirm delete")
    val result = alert.showAndWait()
    result.isPresent && result.get() == ButtonType.OK
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

  private def makeToolButton(text: String): Button = makeToolButtonStyled(text, primary = false)
  private def makePrimaryToolButton(text: String): Button = makeToolButtonStyled(text, primary = true)

  /** Toolbar button styling. Primary variant uses the same blue as the main
    * Run button to visually mark the destructive-confirmation flow's "yes,
    * do it" action.
    */
  private def makeToolButtonStyled(text: String, primary: Boolean): Button = {
    val b = new Button(text)
    val (base, hover, press) =
      if (primary) ("#3d6ee8", "#4f7ef0", "#2c5dc5")
      else         ("#3a3f55", "#4a5070", "#2a2f45")
    val fg = if (primary) "#ffffff" else "#d7dcec"
    def style(bg: String): String =
      s"-fx-background-color: $bg; " +
        s"-fx-text-fill: $fg; " +
        "-fx-background-radius: 4; " +
        "-fx-padding: 4 12; " +
        "-fx-font-size: 11px; " +
        "-fx-cursor: hand;"
    b.setStyle(style(base))
    b.setOnMouseEntered(_ => if (!b.isDisabled) b.setStyle(style(hover)))
    b.setOnMouseExited(_ => b.setStyle(style(base)))
    b.setOnMousePressed(_ => if (!b.isDisabled) b.setStyle(style(press)))
    b.setOnMouseReleased(_ => b.setStyle(style(if (b.isHover && !b.isDisabled) hover else base)))
    b
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
