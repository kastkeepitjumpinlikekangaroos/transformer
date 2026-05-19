package com.transformer.gui

import com.transformer.core.SqlExecutorRegistry
import com.transformer.job.{JobRunRecord, TaskProgressListener, TaskResult, TaskRunStatus}

import javafx.geometry.{Insets, Orientation, Pos}
import javafx.scene.control._
import javafx.scene.layout.{HBox, Priority, VBox}
import javafx.stage.Stage

import java.nio.file.Path
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal

/** Top horizontal panel: job-dir status, execution-time picker, output-dir
  * override, and a compact Run button.
  *
  * Mutations flow into [[JobSession]] (which notifies the rest of the UI).
  * Pressing Run launches a worker thread that calls `dataJob.run` with a
  * [[TaskProgressListener]] marshalling task events back onto the FX thread.
  */
final class ControlsPanel(session: JobSession, owner: () => Stage) extends HBox {

  setSpacing(12)
  setPadding(new Insets(8, 12, 8, 12))
  setAlignment(Pos.CENTER_LEFT)
  setStyle("-fx-background-color: #2a2c38;")

  // ---- Job directory section ----
  private val jobDirField = new TextField()
  jobDirField.setEditable(false)
  jobDirField.setPromptText("(no job loaded)")
  jobDirField.setPrefColumnCount(22)
  private val openButton = new Button("Open…")
  openButton.setOnAction(_ => onOpen())
  private val newProjectButton = new Button("New…")
  newProjectButton.setStyle("-fx-background-color: #3a3f55; -fx-text-fill: #d7dcec;")
  newProjectButton.setOnAction(_ => onNewProject())
  private val jobDirRow = new HBox(6, jobDirField, openButton, newProjectButton)
  jobDirRow.setAlignment(Pos.CENTER_LEFT)
  private val jobDirSection = section("Job directory", jobDirRow)

  // ---- Execution time section ----
  private val datePicker = new DatePicker()
  datePicker.setPrefWidth(130)
  private val hourSpinner   = makeIntSpinner(0, 23, 0)
  private val minuteSpinner = makeIntSpinner(0, 59, 0)
  private val secondSpinner = makeIntSpinner(0, 59, 0)
  private val resetTimeBtn = new Button("Now")
  resetTimeBtn.setOnAction(_ => setExecutionUiToInstant(Instant.now()))
  datePicker.valueProperty().addListener((_, _, _) => pushExecutionTime())
  hourSpinner.valueProperty().addListener((_, _, _) => pushExecutionTime())
  minuteSpinner.valueProperty().addListener((_, _, _) => pushExecutionTime())
  secondSpinner.valueProperty().addListener((_, _, _) => pushExecutionTime())
  private val timeRow = new HBox(
    4,
    datePicker, hourSpinner, new Label(":"), minuteSpinner, new Label(":"), secondSpinner, resetTimeBtn
  )
  timeRow.setAlignment(Pos.CENTER_LEFT)
  private val timeSection = section("Execution time (UTC)", timeRow)

  // ---- Output dir override section ----
  private val outputDirField = new TextField()
  outputDirField.setPromptText("<jobDir>/output (default)")
  outputDirField.setPrefColumnCount(20)
  private val outputDirBrowse = new Button("Choose…")
  outputDirBrowse.setOnAction(_ => onChooseOutputDir())
  outputDirField.focusedProperty().addListener((_, _, isFocused) => {
    if (!isFocused) pushOutputDir()
  })
  outputDirField.setOnAction(_ => pushOutputDir())
  // Always-on hint showing the path the job will actually write under. Now a
  // tooltip on the field — the multi-line label didn't fit horizontally, but
  // the rendered path is still useful when debugging templated outputs.
  private val effectiveOutputTip = new Tooltip("")
  outputDirField.setTooltip(effectiveOutputTip)
  private val outputRow = new HBox(6, outputDirField, outputDirBrowse)
  outputRow.setAlignment(Pos.CENTER_LEFT)
  private val outputSection = section("Output directory", outputRow)

  // ---- Run picker section ----
  // Visible only when 2+ run snapshots exist at the parent of the rendered
  // jobRunOutput path — i.e. the user has templated `outputDir` to vary by
  // run (e.g. `/data/runs/{{ today }}`) and multiple snapshots are on disk.
  // Selecting an entry rehydrates the GUI from that run's job.json without
  // re-running anything.
  private val runPickerCombo = new ComboBox[JobRunChoice]()
  runPickerCombo.setPrefWidth(360)
  private var runPickerSuppressed: Boolean = false
  runPickerCombo.getSelectionModel.selectedItemProperty().addListener((_, _, choice) => {
    if (!runPickerSuppressed && choice != null) session.selectRun(choice.dir)
  })
  private val runPickerRow = new HBox(6, runPickerCombo)
  runPickerRow.setAlignment(Pos.CENTER_LEFT)
  private val runPickerSection = section("Inspect run", runPickerRow)
  // Default hidden — refreshFromSession reveals it when 2+ runs exist.
  runPickerSection.setVisible(false)
  runPickerSection.setManaged(false)

  // ---- Run button + status ----
  private val runButton = new Button("Run")
  runButton.setStyle("-fx-background-color: #3d6ee8; -fx-text-fill: white;")
  runButton.setTooltip(new Tooltip("Run pipeline (⌘R)"))
  runButton.setOnAction(_ => onRun())
  private val statusLabel = new Label("Ready.")
  statusLabel.setStyle("-fx-text-fill: #c5cad8;")
  statusLabel.setMaxWidth(Double.MaxValue)
  HBox.setHgrow(statusLabel, Priority.ALWAYS)

  // Assemble.
  getChildren.addAll(
    jobDirSection,
    new Separator(Orientation.VERTICAL),
    timeSection,
    new Separator(Orientation.VERTICAL),
    outputSection,
    new Separator(Orientation.VERTICAL),
    runPickerSection,
    runButton,
    statusLabel
  )

  // Initialize pickers to "now"; this will fire push when a job is opened.
  setExecutionUiToInstant(session.executionTime)

  // Re-sync the panel whenever session state changes (e.g. user opened a job
  // from the menu, or a run finished).
  session.addListener(() => refreshFromSession())
  refreshFromSession()

  /** Public entrypoint so the CMD+R accelerator wired in [[GuiApp]] fires the
    * same code path as the Run button. No-op when the button is disabled
    * (no job loaded, or a run is already in flight). */
  def triggerRun(): Unit = if (!runButton.isDisabled) onRun()

  // -------------------- internal helpers --------------------

  private def sectionHeader(text: String): Label = {
    val l = new Label(text)
    l.setStyle("-fx-text-fill: #c5cad8; -fx-font-size: 11px;")
    l
  }

  private def section(headerText: String, body: javafx.scene.Node): VBox = {
    val box = new VBox(2, sectionHeader(headerText), body)
    box.setAlignment(Pos.CENTER_LEFT)
    box
  }

  private def makeIntSpinner(min: Int, max: Int, init: Int): Spinner[Integer] = {
    val s = new Spinner[Integer](min, max, init)
    s.setEditable(true)
    s.setPrefWidth(60)
    s
  }

  private def setExecutionUiToInstant(t: Instant): Unit = {
    val ldt = t.atZone(ZoneOffset.UTC).toLocalDateTime
    // Avoid triggering pushes for each individual setter — set in a "silent"
    // batch by temporarily noting the target and applying.
    pushSuppressed = true
    try {
      datePicker.setValue(ldt.toLocalDate)
      hourSpinner.getValueFactory.setValue(ldt.getHour)
      minuteSpinner.getValueFactory.setValue(ldt.getMinute)
      secondSpinner.getValueFactory.setValue(ldt.getSecond)
    } finally pushSuppressed = false
    pushExecutionTime()
  }

  private var pushSuppressed: Boolean = false

  private def pushExecutionTime(): Unit = {
    if (pushSuppressed) return
    val date = Option(datePicker.getValue).getOrElse(LocalDate.now(ZoneOffset.UTC))
    val time = LocalTime.of(
      Option(hourSpinner.getValue).map(_.intValue).getOrElse(0),
      Option(minuteSpinner.getValue).map(_.intValue).getOrElse(0),
      Option(secondSpinner.getValue).map(_.intValue).getOrElse(0)
    )
    val instant = LocalDateTime.of(date, time).toInstant(ZoneOffset.UTC)
    session.setExecutionTime(instant)
  }

  private def pushOutputDir(): Unit = {
    val text = outputDirField.getText
    session.setOutputDirOverride(if (text == null || text.trim.isEmpty) None else Some(text))
  }

  private def onOpen(): Unit = {
    val initial = session.jobDir.map(_.toFile)
    FxHelpers.chooseDirectory(owner(), "Open job directory", initial).foreach { f =>
      session.openJobDir(f.toPath)
    }
  }

  private def onNewProject(): Unit = {
    NewProjectDialog.show(owner()).foreach(p => session.openJobDir(p))
  }

  private def onChooseOutputDir(): Unit = {
    val initial = session.effectiveOutputDir.map(new java.io.File(_))
    FxHelpers.chooseDirectory(owner(), "Choose output directory", initial).foreach { f =>
      outputDirField.setText(f.getAbsolutePath)
      pushOutputDir()
    }
  }

  private def refreshFromSession(): Unit = {
    jobDirField.setText(session.jobDir.map(_.toString).getOrElse(""))
    // Only overwrite the output field if the user hasn't set it manually.
    val override_ = session.outputDirOverride
    if (override_.isEmpty && outputDirField.getText.isEmpty) {
      outputDirField.setPromptText(
        session.effectiveOutputDir.map(s => s"$s (default)").getOrElse("<jobDir>/output (default)")
      )
    }
    effectiveOutputTip.setText(effectiveOutputText)
    runButton.setDisable(!session.canRun)
    statusLabel.setText(statusMessage)
    refreshRunPicker()
    // The "New…" button is only really useful before a job is loaded; once
    // one is open it just clutters the panel.
    val hasJob = session.jobDir.isDefined
    newProjectButton.setVisible(!hasJob)
    newProjectButton.setManaged(!hasJob)
  }

  private val runPickerTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

  private def refreshRunPicker(): Unit = {
    val runs = session.availableRuns
    // Hide the section entirely when there's no choice to make: zero runs
    // (nothing to inspect) or one run (the default — picker would be noise).
    val show = runs.size >= 2
    runPickerSection.setVisible(show)
    runPickerSection.setManaged(show)
    runPickerSuppressed = true
    try {
      runPickerCombo.getItems.clear()
      runs.foreach { case (dir, rec) =>
        runPickerCombo.getItems.add(JobRunChoice(dir, rec, runPickerTimeFmt))
      }
      // Highlight whichever run the session considers active.
      val activeDir = session.selectedRunDir.orElse(runs.headOption.map(_._1))
      activeDir.foreach { d =>
        val idx = runs.indexWhere(_._1 == d)
        if (idx >= 0) runPickerCombo.getSelectionModel.select(idx)
      }
    } finally runPickerSuppressed = false
  }

  private def effectiveOutputText: String = {
    val baseRendered = session.effectiveOutputDirRendered
    val source =
      if (session.outputDirOverride.isDefined) "from field"
      else if (session.jobDir.isDefined) "default <jobDir>/output"
      else "open a job dir first"
    baseRendered match {
      case Some(p) => s"Effective output dir ($source):\n$p"
      case None    => s"Effective output dir: $source"
    }
  }

  private def statusMessage: String = session.runState match {
    case RunState.Idle =>
      session.dag match {
        case Some(d) => s"${d.nodes.size} task(s) loaded. Press Run."
        case None => "Open a job directory to begin."
      }
    case RunState.Running => "Running…"
    case RunState.Done(result) =>
      if (result.succeeded) s"Run succeeded (${result.tasks.size} task(s))."
      else {
        val failed = result.tasks.count(t => !t.succeeded)
        s"Run finished with $failed failure(s)."
      }
    case RunState.LoadFailed(err) => s"Load failed: $err"
  }

  private def onRun(): Unit = {
    val job = session.dataJob.getOrElse(return)
    session.beginRun()

    val listener = new TaskProgressListener {
      override def onTaskEnqueued(taskIndex: Int, taskName: String): Unit =
        FxHelpers.onFx(session.markTaskQueued(taskIndex))
      def onTaskStart(taskIndex: Int, taskName: String): Unit =
        FxHelpers.onFx(session.markTaskRunning(taskIndex))
      def onTaskFinish(taskIndex: Int, result: TaskResult): Unit =
        FxHelpers.onFx(session.markTaskFinished(taskIndex, result))
      override def onInputStart(inputIndex: Int, viewName: String): Unit =
        FxHelpers.onFx(session.markInputLoading(inputIndex))
      override def onInputFinish(inputIndex: Int, viewName: String, succeeded: Boolean, errorMessage: Option[String]): Unit =
        FxHelpers.onFx(session.markInputFinished(inputIndex, succeeded, errorMessage))
    }

    val worker = new Thread(new Runnable {
      def run(): Unit = {
        val result =
          try {
            com.transformer.sql.exec.SqlEngine.init()
            job.run(SqlExecutorRegistry.get, listener)
          } catch {
            case NonFatal(e) =>
              com.transformer.job.JobResult(
                succeeded = false,
                tasks = Nil,
                error = Some(Option(e.getMessage).getOrElse(e.toString))
              )
          }
        FxHelpers.onFx(session.endRun(result))
      }
    }, "transformer-gui-runner")
    worker.setDaemon(true)
    worker.start()
  }
}

/** Label format requested by the run-picker UX: "exec=<isoTime> •
  * finished=<wallClock UTC> • <succeeded>/<total> succeeded". Each ComboBox
  * row renders via `toString`, so the label lives there. */
private final case class JobRunChoice(dir: Path, record: JobRunRecord, fmt: DateTimeFormatter) {
  override def toString: String = {
    val total = record.tasks.size
    val succeeded = record.tasks.count(_.status == TaskRunStatus.Succeeded)
    val exec = record.executionTime.toString
    val finished = fmt.format(record.finishedAt)
    f"exec=$exec  •  finished=$finished  •  $succeeded%d/$total%d succeeded"
  }
}
