package com.transformer.gui

import com.transformer.core.SqlExecutorRegistry
import com.transformer.job.{TaskProgressListener, TaskResult}

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout.{HBox, Priority, VBox}
import javafx.scene.text.{Font, FontWeight}
import javafx.stage.Stage

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}
import scala.util.control.NonFatal

/** Left-side panel: job-dir status, execution-time picker, output-dir override,
  * and the Run button.
  *
  * Mutations flow into [[JobSession]] (which notifies the rest of the UI).
  * Pressing Run launches a worker thread that calls `dataJob.run` with a
  * [[TaskProgressListener]] marshalling task events back onto the FX thread.
  */
final class ControlsPanel(session: JobSession, owner: () => Stage) extends VBox {

  setSpacing(8)
  setPadding(new Insets(12))
  setPrefWidth(280)
  setStyle("-fx-background-color: #2a2c38;")

  // ---- Job directory section ----
  private val jobDirField = new TextField()
  jobDirField.setEditable(false)
  jobDirField.setPromptText("(no job loaded)")
  HBox.setHgrow(jobDirField, Priority.ALWAYS)
  private val openButton = new Button("Open…")
  openButton.setOnAction(_ => onOpen())
  private val openRow = new HBox(6, jobDirField, openButton)
  openRow.setAlignment(Pos.CENTER_LEFT)

  // ---- Execution time section ----
  private val datePicker = new DatePicker()
  datePicker.setMaxWidth(Double.MaxValue)
  private val hourSpinner = makeIntSpinner(0, 23, 0)
  private val minuteSpinner = makeIntSpinner(0, 59, 0)
  private val secondSpinner = makeIntSpinner(0, 59, 0)
  private val timeRow = new HBox(4, hourSpinner, new Label(":"), minuteSpinner, new Label(":"), secondSpinner)
  timeRow.setAlignment(Pos.CENTER_LEFT)
  private val resetTimeBtn = new Button("Reset to now")
  resetTimeBtn.setOnAction(_ => setExecutionUiToInstant(Instant.now()))
  datePicker.valueProperty().addListener((_, _, _) => pushExecutionTime())
  hourSpinner.valueProperty().addListener((_, _, _) => pushExecutionTime())
  minuteSpinner.valueProperty().addListener((_, _, _) => pushExecutionTime())
  secondSpinner.valueProperty().addListener((_, _, _) => pushExecutionTime())

  // ---- Output dir override section ----
  private val outputDirField = new TextField()
  outputDirField.setPromptText("<jobDir>/output (default)")
  HBox.setHgrow(outputDirField, Priority.ALWAYS)
  private val outputDirBrowse = new Button("Choose…")
  outputDirBrowse.setOnAction(_ => onChooseOutputDir())
  outputDirField.focusedProperty().addListener((_, _, isFocused) => {
    if (!isFocused) pushOutputDir()
  })
  outputDirField.setOnAction(_ => pushOutputDir())
  private val outputRow = new HBox(6, outputDirField, outputDirBrowse)
  outputRow.setAlignment(Pos.CENTER_LEFT)

  // ---- Run button + status ----
  private val runButton = new Button("Run pipeline")
  runButton.setMaxWidth(Double.MaxValue)
  runButton.setFont(Font.font("Sans", FontWeight.BOLD, 14))
  runButton.setStyle("-fx-padding: 10 0; -fx-background-color: #3d6ee8; -fx-text-fill: white;")
  runButton.setOnAction(_ => onRun())
  private val statusLabel = new Label("Ready.")
  statusLabel.setWrapText(true)
  statusLabel.setStyle("-fx-text-fill: #c5cad8;")

  // Assemble.
  getChildren.addAll(
    sectionHeader("Job directory"),
    openRow,
    new Separator(),
    sectionHeader("Execution time (UTC)"),
    datePicker,
    timeRow,
    resetTimeBtn,
    new Separator(),
    sectionHeader("Output directory"),
    outputRow,
    new Separator(),
    runButton,
    statusLabel
  )

  // Initialize pickers to "now"; this will fire push when a job is opened.
  setExecutionUiToInstant(session.executionTime)

  // Re-sync the panel whenever session state changes (e.g. user opened a job
  // from the menu, or a run finished).
  session.addListener(() => refreshFromSession())
  refreshFromSession()

  // -------------------- internal helpers --------------------

  private def sectionHeader(text: String): Label = {
    val l = new Label(text)
    l.setStyle("-fx-text-fill: #c5cad8; -fx-font-size: 11px;")
    l
  }

  private def makeIntSpinner(min: Int, max: Int, init: Int): Spinner[Integer] = {
    val s = new Spinner[Integer](min, max, init)
    s.setEditable(true)
    s.setPrefWidth(70)
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
    runButton.setDisable(!session.canRun)
    statusLabel.setText(statusMessage)
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
      def onTaskStart(taskIndex: Int, taskName: String): Unit =
        FxHelpers.onFx(session.markTaskRunning(taskIndex))
      def onTaskFinish(taskIndex: Int, result: TaskResult): Unit =
        FxHelpers.onFx(session.markTaskFinished(taskIndex, result))
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
