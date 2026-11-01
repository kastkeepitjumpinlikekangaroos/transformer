package com.transformer.gui

import atlantafx.base.theme.Styles
import com.transformer.core.SqlExecutorRegistry
import com.transformer.job.{JobRunRecord, TaskProgressListener, TaskResult, TaskRunStatus, TaskStatus}

import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control._
import javafx.scene.layout.{HBox, Priority, Region, VBox}
import javafx.scene.shape.{Circle, Rectangle}
import javafx.stage.Stage

import java.nio.file.Path
import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import scala.util.control.NonFatal

/** The header across the top of the window: the job that is open, the
  * execution time and output directory a run uses, a picker over the run
  * snapshots on disk when there are two or more, the run's status, and Run.
  *
  * Mutations flow into [[JobSession]] (which notifies the rest of the UI).
  * Pressing Run launches a worker thread that calls `dataJob.run` with a
  * [[TaskProgressListener]] marshalling task events back onto the FX thread.
  */
final class ControlsPanel(session: JobSession, owner: () => Stage) extends HBox {

  // Declared first: the body below calls into code that reads them.
  private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
  private val runPickerTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
  /** Set while both time fields change together, so they push once. */
  private var pushSuppressed: Boolean = false

  getStyleClass.add("app-header")

  // ---- Title ----
  private val mark = new Rectangle(14, 14)
  mark.getStyleClass.add("app-mark")
  private val title = new Label("transformer", mark)
  title.getStyleClass.add("app-title")
  title.setGraphicTextGap(8)
  title.setMinWidth(Region.USE_PREF_SIZE)

  // ---- Job ----
  private val jobName = new Label("No job open")
  jobName.getStyleClass.add("job-name")
  private val jobPath = new Label("")
  jobPath.getStyleClass.add("job-path")
  // A long path keeps its end, the part that names the job.
  jobPath.setTextOverrun(OverrunStyle.LEADING_ELLIPSIS)
  private val jobText = new VBox(0, jobName, jobPath)
  jobText.setAlignment(Pos.CENTER_LEFT)
  jobText.setMaxWidth(300)
  jobText.setMinWidth(120)
  private val openButton = iconButton(Icons.folder, "Open a job directory (⌘O)")(onOpen())
  private val newProjectButton = iconButton(Icons.plus, "New project (⌘N)")(onNewProject())
  private val jobSection = new HBox(10, jobText, openButton, newProjectButton)
  jobSection.setAlignment(Pos.CENTER_LEFT)

  // ---- Execution time ----
  private val datePicker = new DatePicker()
  datePicker.setPrefWidth(142)
  datePicker.setMinWidth(Region.USE_PREF_SIZE)
  private val timeField = new TextField()
  timeField.getStyleClass.add("time-field")
  timeField.setPromptText("HH:mm:ss")
  timeField.setMinWidth(Region.USE_PREF_SIZE)
  timeField.setTooltip(new Tooltip("Time of day, UTC: HH:mm or HH:mm:ss. Enter or leaving the field applies it."))
  private val nowButton = new Button("Now", Icons.clock)
  nowButton.getStyleClass.addAll(Styles.FLAT, Styles.SMALL)
  nowButton.setTooltip(new Tooltip("Set the execution time to now"))
  nowButton.setOnAction(_ => setExecutionUiToInstant(Instant.now()))
  nowButton.setMinWidth(Region.USE_PREF_SIZE)
  datePicker.valueProperty().addListener((_, _, _) => pushExecutionTime())
  timeField.setOnAction(_ => pushExecutionTime())
  timeField.focusedProperty().addListener((_, _, focused) => if (!focused) pushExecutionTime())
  private val timeSection = section("Execution time (UTC)", datePicker, timeField, nowButton)

  // ---- Output dir override ----
  private val outputDirField = new TextField()
  outputDirField.setPromptText("<jobDir>/output (default)")
  outputDirField.setPrefColumnCount(22)
  outputDirField.setMinWidth(140)
  private val outputDirBrowse = iconButton(Icons.folder, "Choose the output directory")(onChooseOutputDir())
  outputDirField.focusedProperty().addListener((_, _, isFocused) => {
    if (!isFocused) pushOutputDir()
  })
  outputDirField.setOnAction(_ => pushOutputDir())
  // The path the job actually writes under, templates rendered.
  private val effectiveOutputTip = new Tooltip("")
  outputDirField.setTooltip(effectiveOutputTip)
  private val outputSection = section("Output directory", outputDirField, outputDirBrowse)

  // ---- Run picker ----
  // Visible only when 2+ run snapshots exist at the parent of the rendered
  // jobRunOutput path — i.e. the user has templated `outputDir` to vary by
  // run (e.g. `/data/runs/{{ today }}`) and multiple snapshots are on disk.
  // Selecting an entry rehydrates the GUI from that run's job.json without
  // re-running anything.
  private val runPickerCombo = new ComboBox[JobRunChoice]()
  runPickerCombo.setPrefWidth(340)
  private var runPickerSuppressed: Boolean = false
  runPickerCombo.getSelectionModel.selectedItemProperty().addListener((_, _, choice) => {
    if (!runPickerSuppressed && choice != null) session.selectRun(choice.dir)
  })
  private val runPickerSection = section("Inspect run", runPickerCombo)
  runPickerSection.setVisible(false)
  runPickerSection.setManaged(false)

  // ---- Status and Run ----
  private val statusDot = new Circle(4)
  statusDot.getStyleClass.add("dot")
  private val statusPill = new Label("", statusDot)
  statusPill.getStyleClass.add("status-pill")
  statusPill.setMinWidth(Region.USE_PREF_SIZE)
  private val statusTip = new Tooltip("")
  private val runButton = new Button("Run", Icons.play)
  runButton.getStyleClass.addAll(Styles.ACCENT, "run-button")
  runButton.setTooltip(new Tooltip("Run the pipeline (⌘R)"))
  runButton.setOnAction(_ => onRun())
  runButton.setMinWidth(Region.USE_PREF_SIZE)

  private val spacer = new Region()
  HBox.setHgrow(spacer, Priority.ALWAYS)

  getChildren.addAll(title, jobSection, timeSection, outputSection, runPickerSection, spacer, statusPill, runButton)

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

  private def section(caption: String, controls: Node*): VBox = {
    val label = new Label(caption)
    label.getStyleClass.add("caption")
    val row = new HBox(controls: _*)
    row.getStyleClass.add("field-group")
    val box = new VBox(3, label, row)
    box.setAlignment(Pos.CENTER_LEFT)
    box
  }

  private def iconButton(icon: Node, tip: String)(f: => Unit): Button = {
    val b = new Button(null, icon)
    b.getStyleClass.addAll(Styles.BUTTON_ICON, Styles.FLAT)
    b.setTooltip(new Tooltip(tip))
    b.setOnAction(_ => f)
    b
  }

  private def setExecutionUiToInstant(t: Instant): Unit = {
    val ldt = t.atZone(ZoneOffset.UTC).toLocalDateTime
    // Set both fields before pushing once, rather than a push per field.
    pushSuppressed = true
    try {
      datePicker.setValue(ldt.toLocalDate)
      timeField.setText(TimeFormat.format(ldt.toLocalTime))
    } finally pushSuppressed = false
    pushExecutionTime()
  }

  /** `H:mm` or `H:mm:ss`, each part in range, or None. */
  private def parseTime(raw: String): Option[LocalTime] = {
    val parts = Option(raw).getOrElse("").trim.split(":", -1)
    if (parts.length < 2 || parts.length > 3 || parts.exists(p => p.isEmpty || p.length > 2 || !p.forall(_.isDigit))) None
    else {
      val n = (parts :+ "0").take(3).map(_.toInt)
      if (n(0) > 23 || n(1) > 59 || n(2) > 59) None else Some(LocalTime.of(n(0), n(1), n(2)))
    }
  }

  private def pushExecutionTime(): Unit = {
    if (pushSuppressed) return
    val date = Option(datePicker.getValue).getOrElse(LocalDate.now(ZoneOffset.UTC))
    parseTime(timeField.getText) match {
      case Some(time) =>
        timeField.pseudoClassStateChanged(Styles.STATE_DANGER, false)
        timeField.setText(TimeFormat.format(time))
        session.setExecutionTime(LocalDateTime.of(date, time).toInstant(ZoneOffset.UTC))
      case None =>
        // Keep the session's time until the field holds one.
        timeField.pseudoClassStateChanged(Styles.STATE_DANGER, true)
    }
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
    session.jobDir match {
      case Some(dir) =>
        jobName.setText(jobNameOf(dir))
        jobPath.setText(dir.toString)
        jobText.setVisible(true)
        Tooltip.install(jobText, new Tooltip(dir.toString))
      case None =>
        jobName.setText("No job open")
        jobPath.setText("File > Open Job Directory")
    }
    // Only overwrite the output field if the user hasn't set it manually.
    val override_ = session.outputDirOverride
    if (override_.isEmpty && outputDirField.getText.isEmpty) {
      outputDirField.setPromptText(
        session.effectiveOutputDir.map(s => s"$s (default)").getOrElse("<jobDir>/output (default)")
      )
    }
    effectiveOutputTip.setText(effectiveOutputText)
    runButton.setDisable(!session.canRun)
    refreshStatus()
    refreshRunPicker()
    // "New" is for before a job is open; once one is, File > New Project has it.
    val hasJob = session.jobDir.isDefined
    newProjectButton.setVisible(!hasJob)
    newProjectButton.setManaged(!hasJob)
  }

  /** A job directory by its last two path segments: `jaffle_shop/job` says more
    * than `job`. */
  private def jobNameOf(dir: Path): String = {
    val n = dir.getNameCount
    if (n >= 2) s"${dir.getName(n - 2)}/${dir.getName(n - 1)}" else dir.toString
  }

  private def refreshStatus(): Unit = {
    val (text, tone, tip) = statusMessage
    statusPill.setText(text)
    statusPill.getStyleClass.setAll("label", "status-pill", tone)
    if (tip.isEmpty) statusPill.setTooltip(null)
    else {
      statusTip.setText(tip)
      statusPill.setTooltip(statusTip)
    }
  }

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

  /** The status pill: its text, its tone (a `.status-pill` class) and a
    * tooltip with what does not fit. */
  private def statusMessage: (String, String, String) = session.runState match {
    case RunState.Idle =>
      session.dag match {
        case Some(d) => (s"${d.nodes.size} task${if (d.nodes.size == 1) "" else "s"}", "neutral", "Press Run (⌘R) to run the job")
        case None => ("No job", "neutral", "")
      }
    case RunState.Running =>
      val total = session.dag.map(_.nodes.size).getOrElse(0)
      val done = session.taskStates.count(_.isInstanceOf[UiTaskState.Done])
      (s"Running · $done/$total", "running", "")
    case RunState.Done(result) =>
      val statuses = result.tasks.map(_.status)
      val failed = statuses.count(_.isInstanceOf[TaskStatus.Failed])
      val invalid = statuses.count(_.isInstanceOf[TaskStatus.ValidationFailed])
      val skipped = statuses.count(_.isInstanceOf[TaskStatus.Skipped])
      if (result.succeeded) (s"Succeeded · ${result.tasks.size} tasks", "success", "")
      else if (result.tasks.isEmpty) ("Run failed", "danger", result.error.getOrElse(""))
      else {
        val parts = Seq(
          if (failed > 0) Some(s"$failed failed") else None,
          if (invalid > 0) Some(s"$invalid validation failed") else None,
          if (skipped > 0) Some(s"$skipped skipped") else None
        ).flatten
        (parts.mkString(" · "), if (failed > 0) "danger" else "warning", result.error.getOrElse(""))
      }
    case RunState.LoadFailed(err) => ("Load failed", "danger", err)
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
