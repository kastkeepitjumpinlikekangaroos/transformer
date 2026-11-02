package com.transformer.gui

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.job.{InputFilePath, JobFiles, TaskRunRecord, TaskRunStatus, TaskStatus, Validation}

import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import javafx.geometry.{Insets, Pos}
import javafx.scene.control.{Alert, Button, ButtonType, Label, ScrollPane, Toggle, ToggleButton, ToggleGroup, Tooltip}
import javafx.scene.layout.{FlowPane, HBox, Priority, Region, VBox}
import javafx.stage.Stage
import scala.util.control.NonFatal

/** The Task details tab: everything about the selected node — either a
  * SQLTask or an input view.
  *
  * A header across the top: the task's name, status pill, and the actions on
  * it (edit attributes, add a table or a validation, delete). Below it, two
  * columns. On the left, scrolling on its own:
  *   * Stat chips (rows, duration, queue wait, validations)
  *   * "Reads from": dep chips (tasks + inputs)
  *   * "Writes to": output path + provenance + historical-runs hint
  *   * Error panel: only visible when status is Failed / ValidationFailed
  *   * Validations summary + "Inspect" button to jump to the Validations tab
  * On the right, the SQL, Source or Rendered, editable in place (Edit SQL /
  * Save / Cancel, or vim's `:w` and `:q`).
  *
  * An input shows its options and its "used by" list, across the width.
  */
final class TaskDetailsPanel(session: JobSession, ownerStage: () => Stage) extends VBox {

  private val TimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

  /** Optional callback wired up by [[GuiApp]]: tells the bottom pane to switch
    * to the Validations tab and load the selected task's validations.
    */
  private var onInspectValidations: Int => Unit = _ => ()
  def setOnInspectValidations(handler: Int => Unit): Unit = { onInspectValidations = handler }

  getStyleClass.add("panel")
  setSpacing(14)
  setPadding(new Insets(16, 20, 20, 20))

  // ---- Actions (edit attrs / add / delete) ---------------------------------
  // Their enabled-state depends on the kind of node selected (task vs input vs
  // nothing). Edit/Save/Cancel-SQL live beside the SQL they act on. See
  // [[updateToolbarState]] for the truth table.
  private val editAttrsButton      = actionButton("Edit attributes…")
  private val editSqlButton        = actionButton("Edit SQL", Icons.edit)
  private val saveSqlButton        = actionButton("Save")
  private val cancelSqlButton      = actionButton("Cancel")
  private val addTableButton       = actionButton("Table…", Icons.plus)
  private val addValidationButton  = actionButton("Validation…", Icons.plus)
  private val deleteButton         = actionButton("Delete…")
  saveSqlButton.getStyleClass.add(Styles.ACCENT)
  cancelSqlButton.getStyleClass.add(Styles.FLAT)
  deleteButton.getStyleClass.addAll(Styles.DANGER, Styles.FLAT)
  addTableButton.setTooltip(new Tooltip("Add a table (its FROM starts at the selected node)"))
  addValidationButton.setTooltip(new Tooltip("Add a validation to this table"))

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

  private val toolbar = new HBox(6, editAttrsButton, addTableButton, addValidationButton, deleteButton)
  toolbar.setAlignment(Pos.CENTER_RIGHT)

  // ---- Header row -----------------------------------------------------------
  private val nameLabel = new Label("(no task selected)")
  nameLabel.getStyleClass.add("panel-title")
  nameLabel.setWrapText(true)

  private val statusPill = new Label("")
  setTone(statusPill, Tone.Neutral)
  statusPill.setVisible(false)
  statusPill.setManaged(false)

  private val headerSpacer = new Region()
  HBox.setHgrow(headerSpacer, Priority.ALWAYS)
  private val header = new HBox(10, nameLabel, statusPill, headerSpacer, toolbar)
  header.setAlignment(Pos.CENTER_LEFT)

  // ---- Stat chips (rows, duration, validations) -----------------------------
  private val statChips = new FlowPane(6, 4)
  statChips.setAlignment(Pos.CENTER_LEFT)

  // ---- Dependencies (used as "Reads from" / "Used by" depending on mode) ----
  private val depsHeader = sectionLabel("READS FROM")
  private val depsBox = new FlowPane(6, 4)
  depsBox.setAlignment(Pos.CENTER_LEFT)
  private val noDepsLabel = hintLabel("(no upstream tasks)")
  private val depsSection = new VBox(6, depsHeader, depsBox)

  // ---- Output path ----------------------------------------------------------
  private val outputHeader = sectionLabel("WRITES TO")
  private val outputPathLabel = new Label("")
  outputPathLabel.getStyleClass.add("path-label")
  outputPathLabel.setWrapText(true)
  private val outputMetaLabel = mutedLabel("")
  outputMetaLabel.setWrapText(true)
  private val historyHintLabel = hintLabel("")
  historyHintLabel.setWrapText(true)
  private val outputSection = new VBox(4, outputHeader, outputPathLabel, outputMetaLabel, historyHintLabel)

  // ---- Error block (only visible when relevant) ----------------------------
  private val errorLabel = new Label("")
  errorLabel.getStyleClass.addAll("error-text", "mono")
  errorLabel.setWrapText(true)
  private val errorScroll = new ScrollPane(errorLabel)
  errorScroll.getStyleClass.add("error-box")
  errorScroll.setFitToWidth(true)
  errorScroll.setPrefHeight(110)
  errorScroll.setVisible(false)
  errorScroll.setManaged(false)

  // ---- Validations summary + inspect button --------------------------------
  // The button sits at the right of the section's header, at its full width;
  // the chips wrap below it across the whole column.
  private val validationsHeader = sectionLabel("VALIDATIONS")
  private val validationsSummary = new FlowPane(6, 4)
  validationsSummary.setAlignment(Pos.CENTER_LEFT)
  private val inspectValidationsButton = actionButton("Inspect validations")
  inspectValidationsButton.getStyleClass.add(Styles.FLAT)
  inspectValidationsButton.setMinWidth(Region.USE_PREF_SIZE)
  inspectValidationsButton.setOnAction(_ =>
    session.selectedTaskIndex.foreach(onInspectValidations)
  )
  private val validationsHeaderSpacer = new Region()
  HBox.setHgrow(validationsHeaderSpacer, Priority.ALWAYS)
  private val validationsHeaderRow = new HBox(10, validationsHeader, validationsHeaderSpacer, inspectValidationsButton)
  validationsHeaderRow.setAlignment(Pos.CENTER_LEFT)
  private val validationsBox = new VBox(6, validationsHeaderRow, validationsSummary)
  validationsBox.setVisible(false)
  validationsBox.setManaged(false)

  // ---- SQL (Source / Rendered toggle) ---------------------------------------
  // One SqlView swapped between the on-disk source and the template-rendered
  // body via a two-button toggle group, so the tab needs one viewer's height.
  private val sourceToggle = new ToggleButton("Source")
  private val renderedToggle = new ToggleButton("Rendered")
  private val sqlToggleGroup = new ToggleGroup()
  sourceToggle.getStyleClass.addAll(Styles.LEFT_PILL, Styles.SMALL)
  renderedToggle.getStyleClass.addAll(Styles.RIGHT_PILL, Styles.SMALL)
  Seq(sourceToggle, renderedToggle).foreach { tb =>
    tb.setToggleGroup(sqlToggleGroup)
    tb.setFocusTraversable(false)
  }
  sourceToggle.setSelected(true)
  renderedToggle.setTooltip(new Tooltip("The SQL with its {{ templates }} rendered for the execution time"))
  private val sqlToggles = new HBox(0, sourceToggle, renderedToggle)
  private val sqlHeader = sectionLabel("SQL")
  private val sqlHeaderSpacer = new Region()
  HBox.setHgrow(sqlHeaderSpacer, Priority.ALWAYS)
  // Edit / Save / Cancel sit on the right side of the SQL header so the
  // controls are immediately adjacent to the viewer they act on.
  private val sqlView = new SqlView()
  private val sqlHeaderRow = new HBox(10)
  sqlHeaderRow.getChildren.addAll(sqlHeader, sqlToggles, sqlHeaderSpacer)
  sqlHeaderRow.getChildren.addAll(sqlView.actions: _*)
  sqlHeaderRow.getChildren.addAll(editSqlButton, cancelSqlButton, saveSqlButton)
  sqlHeaderRow.setAlignment(Pos.CENTER_LEFT)
  sqlView.setPrefHeight(240)
  VBox.setVgrow(sqlView, Priority.ALWAYS)
  // Vim's :w saves the edit and :q cancels it, refusing to drop changes
  // without the !.
  sqlView.setOnWrite(() => if (sqlEditing) saveSqlEdit() else Some("Not editing: press Edit SQL first"))
  sqlView.setOnQuit(force =>
    if (!sqlEditing) Some("Not editing")
    else if (!force && sqlView.isModified) Some("E37: No write since last change (add ! to override)")
    else { onCancelSqlEdit(); None }
  )

  // Backing state — the SqlView is reused, but we keep both bodies around so
  // toggling between Source / Rendered is instant (no reload of the source
  // file or re-render of templates).
  private var currentSourceSql: String = ""
  private var currentRenderedSql: String = ""

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

  private def updateSqlView(): Unit =
    sqlView.setSql(if (renderedToggle.isSelected) currentRenderedSql else currentSourceSql)

  // ---- Input-only widgets ---------------------------------------------------
  private val inputOptionsHeader = sectionLabel("OPTIONS")
  private val inputOptionsBox = new VBox(2)
  private val inputOptionsContainer = new VBox(6, inputOptionsHeader, inputOptionsBox)
  inputOptionsContainer.setVisible(false)
  inputOptionsContainer.setManaged(false)


  // ---- Empty state ----------------------------------------------------------
  private val emptyState = new Label(
    "Click a node in the DAG to see its details here. Double-click it to open its rows."
  )
  emptyState.getStyleClass.add("empty-state")
  emptyState.setWrapText(true)

  // ---- Assemble -------------------------------------------------------------
  private val infoColumn = new VBox(16,
    statChips,
    depsSection,
    outputSection,
    errorScroll,
    validationsBox,
    inputOptionsContainer
  )
  infoColumn.getStyleClass.add("panel")
  infoColumn.setPadding(new Insets(0, 8, 8, 0))
  private val infoScroll = new ScrollPane(infoColumn)
  infoScroll.getStyleClass.addAll("panel-scroll", Tweaks.EDGE_TO_EDGE)
  infoScroll.setFitToWidth(true)
  infoScroll.setPrefWidth(460)
  infoScroll.setMinWidth(300)
  private val sqlColumn = new VBox(8, sqlHeaderRow, sqlView)
  HBox.setHgrow(sqlColumn, Priority.ALWAYS)
  private val body = new HBox(20, infoScroll, sqlColumn)
  VBox.setVgrow(body, Priority.ALWAYS)

  getChildren.addAll(header, emptyState, body)

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
    setEmpty(empty = false)
    setSqlMode(visible = true)
    setInputMode(visible = false)
    nameLabel.setText(task.displayName)
    depsHeader.setText("READS FROM")
    renderStatus(i)
    renderStatChips(i)
    renderTaskDeps(node)
    renderOutput(i)
    updateErrorPanel(i)
    renderValidationsSummary(i)
    currentSourceSql = safeLoad(task.loadSql _)
    currentRenderedSql = node.renderedMainSql
    updateSqlView()
  }

  private def showInput(i: Int): Unit = {
    val inputs = session.inputs
    if (i < 0 || i >= inputs.size) { showNothing(); return }
    val in = inputs(i)
    setEmpty(empty = false)
    setSqlMode(visible = false)
    setInputMode(visible = true)
    nameLabel.setText(in.viewName)
    depsHeader.setText("USED BY")
    // Static "input" pill so the user can tell at a glance.
    statusPill.setText(s"INPUT · ${in.detectedFormat.toUpperCase(Locale.ROOT)}")
    setTone(statusPill, Tone.Input)
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
    setEmpty(empty = true)
    setSqlMode(visible = false)
    setInputMode(visible = false)
    nameLabel.setText("Nothing selected")
    statusPill.setVisible(false); statusPill.setManaged(false)
    statChips.getChildren.clear()
    depsHeader.setText("READS FROM")
    depsBox.getChildren.setAll(noDepsLabel)
    outputHeader.setText("WRITES TO")
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
    updateSqlView()
  }

  /** With nothing selected only the header and the empty state show. */
  private def setEmpty(empty: Boolean): Unit = {
    emptyState.setVisible(empty)
    emptyState.setManaged(empty)
    body.setVisible(!empty)
    body.setManaged(!empty)
  }

  /** The SQL column, beside the details; without it (an input) the details
    * take the width. */
  private def setSqlMode(visible: Boolean): Unit = {
    sqlColumn.setVisible(visible)
    sqlColumn.setManaged(visible)
    HBox.setHgrow(infoScroll, if (visible) Priority.NEVER else Priority.ALWAYS)
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
    val (label, tone) = states(i) match {
      case UiTaskState.Pending => ("pending", Tone.Neutral)
      case UiTaskState.Queued  => ("queued", Tone.Running)
      case UiTaskState.Running => ("running", Tone.Running)
      case UiTaskState.Unfinished(_) => ("unfinished", Tone.Warning)
      case UiTaskState.Done(result) =>
        result.status match {
          case TaskStatus.Succeeded            => ("succeeded", Tone.Success)
          case TaskStatus.Failed(_)            => ("failed", Tone.Danger)
          case TaskStatus.ValidationFailed(_)  => ("validation failed", Tone.Warning)
          case TaskStatus.Skipped(_)           => ("skipped", Tone.Neutral)
          case TaskStatus.Pending              => ("pending", Tone.Neutral)
        }
    }
    statusPill.setText(label.toUpperCase(Locale.ROOT))
    setTone(statusPill, tone)
    statusPill.setVisible(true); statusPill.setManaged(true)
  }

  private def renderStatChips(i: Int): Unit = {
    statChips.getChildren.clear()
    val states = session.taskStates
    if (i >= states.size) return
    states(i) match {
      case UiTaskState.Done(result) if result.status == TaskStatus.Succeeded =>
        statChips.getChildren.add(metricChip(f"${result.rowsProduced}%,d rows"))
        statChips.getChildren.add(metricChip(durationText(result)))
        if (result.queueWaitMillis > 0L)
          statChips.getChildren.add(metricChip(s"${result.queueWaitMillis} ms queued"))
        validationsCountChip(i).foreach(statChips.getChildren.add)
      case UiTaskState.Done(result) =>
        statChips.getChildren.add(metricChip(durationText(result)))
        if (result.queueWaitMillis > 0L)
          statChips.getChildren.add(metricChip(s"${result.queueWaitMillis} ms queued"))
        validationsCountChip(i).foreach(statChips.getChildren.add)
      case UiTaskState.Queued =>
        statChips.getChildren.add(metricChip("queued…"))
      case UiTaskState.Running =>
        statChips.getChildren.add(metricChip("running…"))
      // No duration and no rows: its run never finished.
      case UiTaskState.Pending | UiTaskState.Unfinished(_) =>
        validationsCountChip(i).foreach(statChips.getChildren.add)
    }
  }

  /** A task's duration, or that it is unknown: a task restored from a
    * `job.json` written before entries carried timestamps has them at the
    * epoch. */
  private def durationText(result: com.transformer.job.TaskResult): String =
    if (result.startedAt == java.time.Instant.EPOCH) "duration unknown" else s"${result.durationMillis} ms"

  private def validationsCountChip(i: Int): Option[Label] = {
    val n = session.dag.flatMap(_.nodes.lift(i)).map(_.task.validations.size).getOrElse(0)
    if (n == 0) None else Some(metricChip(s"$n validation${if (n == 1) "" else "s"}"))
  }

  private def renderTaskDeps(node: com.transformer.job.TaskDagNode): Unit = {
    depsBox.getChildren.clear()
    val dag = session.dag.getOrElse(return)
    val inputs = session.inputs
    val inputNameToIdx = inputs.iterator.zipWithIndex.map { case (in, i) =>
      in.viewName.toLowerCase(Locale.ROOT) -> i
    }.toMap
    if (node.deps.isEmpty && node.inputDeps.isEmpty) {
      depsBox.getChildren.add(noDepsLabel)
      return
    }
    // Inputs first (the data lineage roots), then upstream tasks.
    node.inputDeps.toSeq.sorted.foreach { name =>
      inputNameToIdx.get(name).foreach { idx =>
        val c = chip(inputs(idx).viewName, "input")
        c.setOnMouseClicked(_ => session.select(Some(Selection.Input(idx))))
        depsBox.getChildren.add(c)
      }
    }
    node.deps.toSeq.sorted.foreach { d =>
      val c = chip(dag.nodes(d).task.displayName, "task")
      c.setOnMouseClicked(_ => session.select(Some(Selection.Task(d))))
      depsBox.getChildren.add(c)
    }
  }

  private def renderInputUsedBy(i: Int): Unit = {
    depsBox.getChildren.clear()
    val dag = session.dag.getOrElse {
      depsBox.getChildren.add(hintLabel("(no DAG loaded)"))
      return
    }
    val inputs = session.inputs
    if (i >= inputs.size) {
      depsBox.getChildren.add(hintLabel("(unknown input)"))
      return
    }
    val viewLower = inputs(i).viewName.toLowerCase(Locale.ROOT)
    val users = dag.nodes.iterator.filter(_.inputDeps.contains(viewLower)).toSeq
    if (users.isEmpty) {
      depsBox.getChildren.add(hintLabel("(not used by any task)"))
      return
    }
    users.foreach { node =>
      val c = chip(node.task.displayName, "task")
      val idx = node.index
      c.setOnMouseClicked(_ => session.select(Some(Selection.Task(idx))))
      depsBox.getChildren.add(c)
    }
  }

  private def renderInputPath(i: Int, in: InputFilePath): Unit = {
    outputHeader.setText("FILE")
    val rendered = session.renderedInputPath(i).getOrElse(in.path)
    outputPathLabel.setText(rendered)
    outputPathLabel.setTooltip(new Tooltip(rendered))
    val source =
      if (rendered != in.path) s"format: ${in.detectedFormat} · template: ${in.path}"
      else s"format: ${in.detectedFormat}"
    outputMetaLabel.setText(source)
    outputMetaLabel.setVisible(true); outputMetaLabel.setManaged(true)
    historyHintLabel.setText("Double-click the input node to preview its rows in the Output data tab.")
    historyHintLabel.setVisible(true); historyHintLabel.setManaged(true)
  }

  private def renderInputOptions(in: InputFilePath): Unit = {
    inputOptionsBox.getChildren.clear()
    if (in.options.isEmpty) {
      inputOptionsBox.getChildren.add(hintLabel("(default options)"))
    } else {
      in.options.toSeq.sortBy(_._1).foreach { case (k, v) =>
        val l = new Label(s"$k = $v")
        l.getStyleClass.addAll("option-label", "mono")
        inputOptionsBox.getChildren.add(l)
      }
    }
  }

  private def renderOutput(i: Int): Unit = {
    val planned = session.plannedOutputPathFor(i)
    val written = session.outputPathFor(i)
    val state = session.taskStates.lift(i)
    val (verb, path) = (planned, state, written) match {
      case (Some(p), Some(UiTaskState.Done(_)), Some(actual)) if actual == p => ("WROTE TO", Some(p))
      case (Some(p), Some(UiTaskState.Done(_)), Some(actual))                => ("WROTE TO", Some(actual))
      case (Some(p), Some(UiTaskState.Unfinished(_)), _)                     => ("UNFINISHED IN", Some(p))
      case (Some(p), _, _)                                                   => ("WILL WRITE TO", Some(p))
      case (None, _, _)                                                      => ("OUTPUT", None)
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
    outputMetaLabel.setText(session.summaryReasonFor(i) match {
      case Some(reason) => s"Restored from job.json: $reason."
      case None         => formatRecordMeta(session.taskRecordFor(i))
    })
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

  /** Compact one-line `2 rows · 1 part file · written 2026-…` summary of the
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
          s" · this run's exec=${TimeFmt.format(r.executionTime)} differs from selected ${TimeFmt.format(session.executionTime)}"
        else ""
      val written = f"${r.rowsProduced}%,d rows · ${r.outputFiles.size} part file$plural · written ${TimeFmt.format(r.writtenAt)}"
      r.status match {
        case TaskRunStatus.Running          => s"unfinished · its run started ${TimeFmt.format(r.startedAt)}$mismatch"
        case TaskRunStatus.Succeeded        => s"$written$mismatch"
        case TaskRunStatus.ValidationFailed => s"$written · validation failed$mismatch"
        case TaskRunStatus.Failed           => s"$written · failed$mismatch"
        case TaskRunStatus.Skipped          => s"$written · skipped$mismatch"
      }
  }

  private def updateErrorPanel(i: Int): Unit = {
    val states = session.taskStates
    if (i >= states.size) { setErrorText(""); return }
    states(i) match {
      case UiTaskState.Done(result) =>
        result.status match {
          case TaskStatus.Failed(reason) =>
            setErrorText(s"Error:\n$reason")
          case TaskStatus.ValidationFailed(fs) if fs.isEmpty =>
            setErrorText("Validation failures: which ones is not recorded (restored from job.json).")
          case TaskStatus.ValidationFailed(fs) =>
            val body = fs.iterator
              .map { f =>
                val sample =
                  if (f.sampleRowsCsv.isEmpty) "    (sample rows not recorded: restored from job.json)"
                  else indent(f.sampleRowsCsv, "    ")
                s"• ${f.validationName} — ${f.rowCount} failing row(s)\n$sample"
              }
              .mkString("\n\n")
            setErrorText(s"Validation failures:\n$body")
          case TaskStatus.Skipped(reason) =>
            setErrorText(s"Skipped: $reason")
          case _ =>
            setErrorText("")
        }
      case UiTaskState.Unfinished(_) =>
        setErrorText("Unfinished: the run that started this task stopped before it finished, or is still " +
          "running in another process. Its _run.json says Running, so its output directory holds nothing a " +
          "reader can trust; the next run clears it.")
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
      val (label, tone) = validationStatusFor(v, state)
      val c = chip(s"${v.name}: $label", tone.css)
      c.getStyleClass.add("clickable")
      c.setTooltip(new Tooltip("Click to edit or delete this validation"))
      c.setOnMouseClicked(_ => openValidationEditor(i, v))
      validationsSummary.getChildren.add(c)
    }
  }

  /** Per-validation status derived from the surrounding task's status. The
    * runner doesn't keep per-validation outcomes for the passing ones, but a
    * passing task implies all its validations passed.
    */
  private def validationStatusFor(v: Validation, state: Option[UiTaskState]): (String, Tone) = state match {
    case Some(UiTaskState.Done(result)) =>
      result.status match {
        case TaskStatus.Succeeded => ("passed", Tone.Success)
        case TaskStatus.ValidationFailed(fs) =>
          fs.find(_.validationName == v.name) match {
            case Some(f)            => (s"failed (${f.rowCount} row${if (f.rowCount == 1) "" else "s"})", Tone.Danger)
            case None if fs.isEmpty => ("unknown", Tone.Neutral)
            case None               => ("passed", Tone.Success)
          }
        case TaskStatus.Failed(_)  => ("not run", Tone.Neutral)
        case TaskStatus.Skipped(_) => ("not run", Tone.Neutral)
        case TaskStatus.Pending    => ("pending", Tone.Neutral)
      }
    case Some(UiTaskState.Unfinished(_)) => ("not run", Tone.Neutral)
    case Some(UiTaskState.Running) => ("running", Tone.Running)
    case Some(UiTaskState.Queued)  => ("queued", Tone.Running)
    case Some(UiTaskState.Pending) | None => ("pending", Tone.Neutral)
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

  private def onSaveSqlEdit(): Unit = saveSqlEdit()

  /** Write the edit to the task's main.sql; None once written, or why not. */
  private def saveSqlEdit(): Option[String] = {
    val dir = session.jobDir.getOrElse { cancelSqlEditState(); return Some("No job directory") }
    val viewName = editingViewName.getOrElse { cancelSqlEditState(); return Some("Not editing") }
    val newSql = sqlView.getCurrentSql
    try {
      JobFiles.writeMainSql(dir, viewName, newSql)
    } catch {
      case NonFatal(e) =>
        val msg = Option(e.getMessage).getOrElse(e.toString)
        FxHelpers.showError(ownerStage(), "Couldn't save SQL", msg)
        return Some(s"Not saved: $msg")
    }
    cancelSqlEditState()
    session.reloadPreservingSelection(Some(viewName))
    None
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
    Theme.style(alert)
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
    l.getStyleClass.add("section-label")
    l
  }

  private def mutedLabel(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add("muted")
    l
  }

  private def hintLabel(text: String): Label = {
    val l = new Label(text)
    l.getStyleClass.add("hint")
    l
  }

  private def metricChip(text: String): Label = chip(text, "metric")

  private def chip(text: String, kind: String): Label = {
    val l = new Label(text)
    l.getStyleClass.addAll("chip", kind)
    l
  }

  private def actionButton(text: String, icon: javafx.scene.Node = null): Button = {
    val b = new Button(text, icon)
    b.getStyleClass.add(Styles.SMALL)
    b
  }

  private def setTone(label: Label, tone: Tone): Unit =
    label.getStyleClass.setAll("label", "pill", tone.css)

  private def indent(s: String, prefix: String): String =
    s.split('\n').iterator.map(prefix + _).mkString("\n")
}
