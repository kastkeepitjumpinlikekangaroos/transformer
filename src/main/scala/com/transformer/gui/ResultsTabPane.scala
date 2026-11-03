package com.transformer.gui

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.job.{InputFilePath, InputResolver, TaskRunRecord, TaskRunStatus, TaskStatus, Validation}

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout.{HBox, Priority, VBox}

import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** Bottom panel: five tabs.
  *
  *  - **Task details** — selected task/input metadata + a single Source/Rendered-
  *    toggle SQL viewer. Hosts the `TaskDetailsPanel` that used to live in the
  *    right-side BorderPane slot.
  *  - **Output data** — rows from the selected/activated task's persisted output,
  *    or an activated input's raw data. Loaded on demand on a background thread.
  *  - **Validations** — per-validation cards (name + status + SQL + failing rows
  *    sample) for the currently-selected task. Always reflects the current
  *    selection; no explicit load step needed.
  *  - **SQL console** — interactive ad-hoc SQL over the inputs and task outputs
  *    with an optional Persist button that writes the result to disk via the
  *    library's standard CSV/Parquet writers.
  *  - **Run log** — overall job summary, per-task statuses, and error details.
  *
  * Output loading is triggered explicitly via [[loadTaskOutput]] / [[loadInputData]]
  * (the canvas's double-click handler). A load-token guards against stale loads
  * when the user activates a new node before the previous load completes.
  */
final class ResultsTabPane(
    session: JobSession,
    owner: () => javafx.stage.Stage,
    details: TaskDetailsPanel
) extends TabPane {

  // ---- Output data tab ----
  private val outputTable = new DataGrid()
  outputTable.setPlaceholderText(
    "Double-click a task or input node in the DAG to load its data.\n" +
      "Tasks without persisted output cannot be previewed — they will show a message instead."
  )
  outputTable.getStyleClass.add(Tweaks.EDGE_TO_EDGE)

  // Partition picker — only visible when the currently-activated task has 2+
  // historical runs on disk.
  private val pickerLabel = new Label("Run")
  pickerLabel.getStyleClass.add("section-label")
  private val pickerCombo = new ComboBox[RunChoice]()
  pickerCombo.setPrefWidth(600)
  private val pickerRow = new HBox(8, pickerLabel, pickerCombo)
  pickerRow.setAlignment(Pos.CENTER_LEFT)
  pickerRow.setPadding(new Insets(8, 12, 0, 12))
  pickerRow.setVisible(false)
  pickerRow.setManaged(false)

  // Setting items / value triggers the selection listener, which would
  // re-enter loadFromPath in unwanted ways during programmatic setup.
  private var pickerSuppressed: Boolean = false
  pickerCombo.valueProperty().addListener((_, _, newVal) => {
    if (!pickerSuppressed && newVal != null) loadFromPath(newVal.path.toString, Some(newVal))
  })

  private val outputStatus = new Label("")
  outputStatus.getStyleClass.add("muted")
  outputStatus.setPadding(new Insets(8, 12, 8, 12))
  outputStatus.setWrapText(true)
  private val outputBox = new VBox(pickerRow, outputStatus, outputTable)
  outputBox.getStyleClass.add("panel")
  VBox.setVgrow(outputTable, Priority.ALWAYS)
  private val outputTab = new Tab("Output data", outputBox)
  outputTab.setClosable(false)

  // ---- Validations tab ----
  private val validationsHeaderLabel = new Label("Select a task to see its validations.")
  validationsHeaderLabel.getStyleClass.add("card-title")
  validationsHeaderLabel.setPadding(new Insets(14, 16, 0, 16))
  validationsHeaderLabel.setWrapText(true)
  private val validationsContainer = new VBox(12)
  validationsContainer.setPadding(new Insets(12, 16, 16, 16))
  validationsContainer.getStyleClass.add("panel")
  private val validationsScroll = new ScrollPane(validationsContainer)
  validationsScroll.getStyleClass.addAll("panel-scroll", Tweaks.EDGE_TO_EDGE)
  validationsScroll.setFitToWidth(true)
  private val validationsBox = new VBox(validationsHeaderLabel, validationsScroll)
  validationsBox.getStyleClass.add("panel")
  VBox.setVgrow(validationsScroll, Priority.ALWAYS)
  private val validationsTab = new Tab("Validations", validationsBox)
  validationsTab.setClosable(false)

  // ---- Task details tab ----
  // The details panel scrolls its own left column, and its SQL fills the rest.
  private val detailsTab = new Tab("Task details", details)
  detailsTab.setClosable(false)

  // ---- SQL console tab ----
  private val sqlConsole = new SqlConsolePanel(session, owner)
  private val sqlConsoleTab = new Tab("SQL console", sqlConsole)
  sqlConsoleTab.setClosable(false)

  // ---- Run log tab ----
  private val runLog = new TextArea("(no run yet)")
  runLog.setEditable(false)
  runLog.setWrapText(false)
  runLog.getStyleClass.addAll("log-view", Tweaks.EDGE_TO_EDGE)
  private val runLogTab = new Tab("Run log", runLog)
  runLogTab.setClosable(false)

  getTabs.addAll(detailsTab, outputTab, validationsTab, sqlConsoleTab, runLogTab)
  setPadding(Insets.EMPTY)
  getStyleClass.add("results-tabs")

  session.addListener(() => {
    refreshRunLog()
    refreshValidationsTab()
  })
  refreshValidationsTab()

  private val loadToken = new AtomicLong(0L)
  private var currentTaskIndex: Option[Int] = None

  /** Activate the output tab, populate the partition picker, and load the
    * task's persisted output on a background thread. Safe to call from the FX
    * thread.
    *
    * If the task has 2+ historical runs the picker is shown above the table;
    * selecting another partition re-loads the table for that run.
    */
  def loadTaskOutput(taskIndex: Int): Unit = {
    getSelectionModel.select(outputTab)
    currentTaskIndex = Some(taskIndex)

    val nameOpt = session.dag.flatMap(_.nodes.lift(taskIndex)).map(_.task.displayName)
    val runs = session.historicalRunsFor(taskIndex)
    val planned = session.plannedOutputPathFor(taskIndex)
    val state = session.taskStates.lift(taskIndex)

    populatePicker(runs, planned)

    runs.headOption match {
      case Some((path, marker)) =>
        // Select either the planned path (if it's one of the runs) or the latest.
        val initial = runs.find { case (p, _) => planned.contains(p.toString) }
          .getOrElse((path, marker))
        pickerSuppressed = true
        try pickerCombo.getSelectionModel.select(toChoice(initial._1, initial._2))
        finally pickerSuppressed = false
        loadFromPath(initial._1.toString, Some(toChoice(initial._1, initial._2)), nameOpt)
      case None =>
        outputTable.clear()
        outputStatus.setText(noOutputMessage(nameOpt, state))
    }
  }

  /** Activate the output tab and load the raw rows of an input view on a
    * background thread. Inputs don't have run history, so the partition picker
    * stays hidden.
    */
  def loadInputData(inputIndex: Int): Unit = {
    getSelectionModel.select(outputTab)
    currentTaskIndex = None
    populatePicker(Nil, None)
    val input = session.inputs.lift(inputIndex).getOrElse {
      outputTable.clear()
      outputStatus.setText("(unknown input)")
      return
    }
    val renderedPath = session.renderedInputPath(inputIndex).getOrElse(input.path)
    loadInputFromResolver(input, renderedPath)
  }

  private def populatePicker(runs: Seq[(Path, TaskRunRecord)], planned: Option[String]): Unit = {
    pickerSuppressed = true
    try {
      pickerCombo.getItems.clear()
      runs.foreach { case (p, r) =>
        pickerCombo.getItems.add(toChoice(p, r, planned.contains(p.toString)))
      }
    } finally pickerSuppressed = false
    val shouldShow = runs.size >= 2
    pickerRow.setVisible(shouldShow)
    pickerRow.setManaged(shouldShow)
  }

  private val pickerTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

  private def toChoice(path: Path, record: TaskRunRecord, isPlanned: Boolean = false): RunChoice =
    RunChoice(
      path = path,
      record = record,
      label = {
        val wrote = pickerTimeFmt.format(record.writtenAt)
        val exec = pickerTimeFmt.format(record.executionTime)
        val pin = if (isPlanned) "  ← current execution time" else ""
        val outcome = record.status match {
          case TaskRunStatus.Running          => "unfinished"
          case TaskRunStatus.Succeeded        => f"${record.rowsProduced}%,d row(s)"
          case TaskRunStatus.ValidationFailed => f"${record.rowsProduced}%,d row(s)  •  validation failed"
          case TaskRunStatus.Failed           => "failed"
          case TaskRunStatus.Skipped          => "skipped"
        }
        s"$wrote  •  exec=$exec  •  $outcome  •  $path$pin"
      }
    )

  /** Start a background load of `path` and apply the outcome to the table when
    * it completes (guarded by the load-token so stale loads can't clobber a
    * fresher one).
    */
  private def loadFromPath(path: String, choice: Option[RunChoice], explicitName: Option[String] = None): Unit = {
    val token = loadToken.incrementAndGet()
    outputTable.clear()
    val nameOpt = explicitName.orElse(
      currentTaskIndex.flatMap(i => session.dag.flatMap(_.nodes.lift(i))).map(_.task.displayName)
    )
    // A run that never finished left nothing a reader can trust: no preview.
    if (choice.exists(_.record.status == TaskRunStatus.Running)) {
      val who = nameOpt.map(n => s"'$n'").getOrElse("Task")
      outputStatus.setText(s"$who • $path • unfinished: the run writing it stopped before it finished, or is " +
        "still running in another process, so there are no rows to show; the next run clears the directory.")
      return
    }
    outputStatus.setText("Loading…")
    val worker = new Thread(new Runnable {
      def run(): Unit = {
        val outcome =
          try LoadOutcome.Success(loadFromDisk(path))
          catch { case NonFatal(e) => LoadOutcome.Failed(Option(e.getMessage).getOrElse(e.toString)) }
        FxHelpers.onFx {
          if (loadToken.get() == token) applyOutcome(nameOpt, path, outcome, choice)
        }
      }
    }, s"transformer-gui-output-loader-$token")
    worker.setDaemon(true)
    worker.start()
  }

  /** Load via [[InputResolver]] so cloud paths, parquet options, and CSV
    * options all behave the same way they would for a real run.
    */
  private def loadInputFromResolver(input: InputFilePath, renderedPath: String): Unit = {
    val token = loadToken.incrementAndGet()
    outputStatus.setText("Loading…")
    outputTable.clear()
    val nameOpt = Some(s"input ${input.viewName}")
    val worker = new Thread(new Runnable {
      def run(): Unit = {
        val outcome =
          try {
            val resolved = input.copy(path = renderedPath)
            LoadOutcome.Success(DataGrid.sample(InputResolver.resolve(resolved)))
          } catch {
            case NonFatal(e) => LoadOutcome.Failed(Option(e.getMessage).getOrElse(e.toString))
          }
        FxHelpers.onFx {
          if (loadToken.get() == token) applyOutcome(nameOpt, renderedPath, outcome, None)
        }
      }
    }, s"transformer-gui-input-loader-$token")
    worker.setDaemon(true)
    worker.start()
  }

  private def noOutputMessage(name: Option[String], state: Option[UiTaskState]): String = {
    val who = name.map(n => s"'$n'").getOrElse("This task")
    val why = state match {
      case Some(UiTaskState.Done(r)) => r.status match {
        case TaskStatus.Succeeded => "succeeded but has no outputFile set"
        case TaskStatus.Failed(_) => "failed before producing output"
        case TaskStatus.ValidationFailed(_) => "completed but output may be incomplete (see Run log)"
        case TaskStatus.Skipped(_) => "was skipped"
        case TaskStatus.Pending => "hasn't run yet — press Run to produce its output"
      }
      case Some(UiTaskState.Unfinished(_)) => "its run stopped before it finished, or is still running"
      case Some(UiTaskState.Running) | Some(UiTaskState.Queued) => "it is running"
      case Some(UiTaskState.Pending) | None => "hasn't run yet — press Run to produce its output"
    }
    s"$who: no persisted output available — $why."
  }

  private def applyOutcome(
      name: Option[String],
      path: String,
      outcome: LoadOutcome,
      choice: Option[RunChoice]
  ): Unit = {
    outputTable.clear()
    outcome match {
      case LoadOutcome.Success(loaded) =>
        outputTable.show(loaded.schema, loaded.rows)
        val truncated = if (loaded.truncated) " (preview truncated)" else ""
        val who = name.map(n => s"'$n'").getOrElse("Task")
        val runInfo = choice.map { c =>
          val wrote = pickerTimeFmt.format(c.record.writtenAt)
          val exec = pickerTimeFmt.format(c.record.executionTime)
          s" • exec=$exec • written=$wrote"
        }.getOrElse("")
        outputStatus.setText(f"$who • $path • ${loaded.rows.size}%,d row(s) loaded$truncated$runInfo")
      case LoadOutcome.Failed(err) =>
        outputStatus.setText(s"Failed to load $path:\n$err")
    }
  }

  /** A task output's first rows, read as [[JobSession.readOutputAsView]]
    * reads it: through its `_run.json` when it has one. */
  private def loadFromDisk(path: String): RowSample = {
    if (!Files.exists(Paths.get(path))) throw new RuntimeException(s"output path does not exist: $path")
    DataGrid.sample(JobSession.readOutputAsView(path))
  }

  // -------------------- validations tab --------------------

  /** Switch to the Validations tab. Used by the right-side details panel's
    * "Inspect validations" button.
    */
  def focusValidations(taskIndex: Int): Unit = {
    session.select(Some(Selection.Task(taskIndex)))
    getSelectionModel.select(validationsTab)
  }

  private def refreshValidationsTab(): Unit = {
    validationsContainer.getChildren.clear()
    session.selection match {
      case Some(Selection.Task(i)) =>
        val dag = session.dag.getOrElse {
          validationsHeaderLabel.setText("(no DAG loaded)")
          return
        }
        dag.nodes.lift(i) match {
          case Some(node) =>
            val task = node.task
            validationsHeaderLabel.setText(
              s"Validations for: ${task.displayName} " +
                s"(${task.validations.size} declared)"
            )
            if (task.validations.isEmpty) {
              validationsContainer.getChildren.add(emptyHint(
                "This task has no validations. Add files under " +
                  "tables/<viewName>/validations/*.sql to define some."
              ))
            } else {
              val state = session.taskStates.lift(i)
              task.validations.iterator.zipWithIndex.foreach { case (v, vi) =>
                val rendered = node.renderedValidationSqls.lift(vi).getOrElse("")
                validationsContainer.getChildren.add(buildValidationCard(v, rendered, state, task.viewName))
              }
            }
          case None =>
            validationsHeaderLabel.setText("(unknown task)")
        }
      case Some(Selection.Input(_)) =>
        validationsHeaderLabel.setText("Inputs do not have validations. Click a task node to see its validations.")
      case None =>
        validationsHeaderLabel.setText("Select a task to see its validations.")
    }
  }

  private def emptyHint(text: String): javafx.scene.Node = {
    val l = new Label(text)
    l.getStyleClass.add("hint")
    l.setWrapText(true)
    l
  }

  private def buildValidationCard(
      v: Validation,
      renderedSql: String,
      state: Option[UiTaskState],
      tableViewName: Option[String]
  ): javafx.scene.Node = {
    val (statusText, tone, failureSample) = validationStatusFor(v, state)
    // Header: name + status pill + Edit button (right-aligned, next to the SQL
    // viewer this validation card owns).
    val nameLabel = new Label(v.name)
    nameLabel.getStyleClass.add("card-title")
    val statusPill = new Label(statusText)
    statusPill.getStyleClass.addAll("pill", tone.css)
    val headerSpacer = new javafx.scene.layout.Region()
    HBox.setHgrow(headerSpacer, Priority.ALWAYS)
    val editButton = makeValidationEditButton()
    editButton.setDisable(session.jobDir.isEmpty || tableViewName.isEmpty)
    editButton.setOnAction(_ => tableViewName.foreach(name => openValidationEditor(name, v)))

    // SQL: a SqlView showing the rendered query, as tall as the query.
    val sqlView = new SqlView()
    sqlView.setSql(renderedSql)
    sqlView.fitLines(12)

    val cardHeader = new HBox(8, nameLabel, statusPill, headerSpacer)
    cardHeader.getChildren.addAll(sqlView.actions: _*)
    cardHeader.getChildren.add(editButton)
    cardHeader.setAlignment(Pos.CENTER_LEFT)

    val cardChildren = new java.util.ArrayList[javafx.scene.Node]()
    cardChildren.add(cardHeader)
    cardChildren.add(sqlView)
    failureSample.filter(_.isEmpty).foreach { _ =>
      // A failed validation's rows live beside the task's _run.json; restored
      // from job.json, the task has none.
      val note = new Label("Failing rows not recorded: this task was restored from job.json, which holds no sample rows.")
      note.getStyleClass.add("hint")
      note.setWrapText(true)
      cardChildren.add(note)
    }
    failureSample.filter(_.nonEmpty).foreach { sample =>
      val sampleLabel = new Label("FAILING ROWS (FIRST 10)")
      sampleLabel.getStyleClass.add("section-label")
      val sampleArea = new TextArea(sample)
      sampleArea.setEditable(false)
      sampleArea.setWrapText(false)
      sampleArea.setPrefRowCount(math.min(10, math.max(2, sample.count(_ == '\n') + 1)))
      sampleArea.getStyleClass.addAll("failing-rows", "mono")
      cardChildren.add(sampleLabel)
      cardChildren.add(sampleArea)
    }

    val card = new VBox()
    card.getStyleClass.add("card")
    card.getChildren.addAll(cardChildren)
    card
  }

  /** Opens the validation edit dialog. Mirrors [[TaskDetailsPanel.openValidationEditor]]
    * so users can trigger the edit from either the right-side chip or the card
    * sitting next to the SQL viewer.
    */
  private def openValidationEditor(tableViewName: String, v: Validation): Unit = {
    val dir = session.jobDir.getOrElse(return)
    AddValidationDialog.showEdit(owner(), dir, tableViewName, v.name, v.sqlFile).foreach { _ =>
      session.reloadPreservingSelection(Some(tableViewName))
    }
  }

  private def makeValidationEditButton(): Button = {
    val b = new Button("Edit…", Icons.edit)
    b.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
    b.setTooltip(new Tooltip("Edit this validation's name, SQL, or delete it"))
    b
  }

  /** Returns (status label, the status pill's tone, optional sample CSV of failing rows). */
  private def validationStatusFor(v: Validation, state: Option[UiTaskState]): (String, Tone, Option[String]) = {
    val pass    = Tone.Success
    val fail    = Tone.Danger
    val neutral = Tone.Neutral
    val running = Tone.Running
    state match {
      case Some(UiTaskState.Done(result)) =>
        result.status match {
          case TaskStatus.Succeeded =>
            ("PASSED", pass, None)
          case TaskStatus.ValidationFailed(fs) =>
            fs.find(_.validationName == v.name) match {
              case Some(f) =>
                val plural = if (f.rowCount == 1) "" else "s"
                (s"FAILED · ${f.rowCount} row$plural", fail, Some(f.sampleRowsCsv))
              // A task restored from a job.json that predates failed validation
              // names: it failed some, which ones is not recorded.
              case None if fs.isEmpty =>
                ("UNKNOWN (restored from job.json)", neutral, None)
              case None =>
                ("PASSED", pass, None)
            }
          case TaskStatus.Failed(_) =>
            ("NOT RUN (task failed)", neutral, None)
          case TaskStatus.Skipped(_) =>
            ("NOT RUN (task skipped)", neutral, None)
          case TaskStatus.Pending =>
            ("PENDING", neutral, None)
        }
      case Some(UiTaskState.Unfinished(_)) => ("NOT RUN (task unfinished)", neutral, None)
      case Some(UiTaskState.Running) => ("RUNNING", running, None)
      case Some(UiTaskState.Queued)  => ("QUEUED", running, None)
      case Some(UiTaskState.Pending) | None => ("PENDING", neutral, None)
    }
  }

  private def refreshRunLog(): Unit = {
    val sb = new StringBuilder()
    session.runState match {
      case RunState.Idle =>
        val dag = session.dag
        val cachedTasks = dag.toSeq.flatMap(_.nodes).count(n => session.taskRecordFor(n.index).isDefined)
        val totalTasks = dag.map(_.nodes.size).getOrElse(0)
        if (cachedTasks > 0) {
          sb.append(s"$cachedTasks of $totalTasks task(s) restored from _run.json records (previous run). Press Run to refresh.\n")
        } else {
          sb.append("(no run yet)\n")
        }
        appendJobWarnings(sb, session.jobRecord.map(_.warnings).getOrElse(Nil))
        if (session.hydrationWarnings.nonEmpty) {
          sb.append(s"\n${session.hydrationWarnings.size} record(s) could not be read back:\n")
          session.hydrationWarnings.foreach(w => sb.append(s"  [warn] $w\n"))
        }
      case RunState.Running => sb.append("Running…\n")
      case RunState.LoadFailed(err) => sb.append(s"Load failed:\n$err\n")
      case RunState.Done(result) =>
        sb.append(if (result.succeeded) "Run succeeded.\n" else "Run finished with failure(s).\n")
        result.error.foreach(e => sb.append(s"\nJob-level error: $e\n"))
        appendJobWarnings(sb, result.warnings)
        if (result.tasks.nonEmpty) {
          sb.append(s"\n${result.tasks.size} task(s):\n")
          result.tasks.foreach { tr =>
            sb.append(s"  • ${tr.taskName} — ${describeStatus(tr.status)} (${tr.durationMillis} ms)")
            tr.outputPath.foreach(p => sb.append(s"\n      output: $p"))
            sb.append('\n')
            tr.status match {
              case TaskStatus.Failed(reason) => sb.append(s"      error: $reason\n")
              case TaskStatus.ValidationFailed(fs) =>
                fs.foreach { f =>
                  sb.append(s"      validation '${f.validationName}': ${f.rowCount} failing row(s)\n")
                }
              case TaskStatus.Skipped(reason) => sb.append(s"      reason: $reason\n")
              case _ => ()
            }
          }
        }
    }
    runLog.setText(sb.toString)
  }

  private def appendJobWarnings(sb: StringBuilder, warnings: Seq[String]): Unit = {
    if (warnings.nonEmpty) {
      sb.append(s"\n${warnings.size} warning(s):\n")
      warnings.foreach(w => sb.append(s"  [warn] $w\n"))
    }
  }

  private def describeStatus(status: TaskStatus): String = status match {
    case TaskStatus.Succeeded => "succeeded"
    case TaskStatus.Failed(_) => "failed"
    case TaskStatus.ValidationFailed(fs) => if (fs.isEmpty) "validation failed" else s"validation failed (${fs.size})"
    case TaskStatus.Skipped(_) => "skipped"
    case TaskStatus.Pending => "pending"
  }
}

private sealed trait LoadOutcome
private object LoadOutcome {
  final case class Success(loaded: RowSample) extends LoadOutcome
  final case class Failed(error: String) extends LoadOutcome
}

/** One entry in the partition picker. ComboBox renders entries by their
  * `toString`, so the label goes there.
  */
private final case class RunChoice(path: Path, record: TaskRunRecord, label: String) {
  override def toString: String = label
}
