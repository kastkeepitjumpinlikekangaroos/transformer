package com.transformer.gui

import com.transformer.core.{ColumnarBatch, Schema}
import com.transformer.job.{InputFilePath, InputResolver, ParquetReaderHook, RunMarker, TaskStatus, Validation}
import com.transformer.read.csv.{CsvOptions, CsvReader}

import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.{FXCollections, ObservableList}
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout.{HBox, Priority, VBox}
import javafx.scene.text.Font

import java.nio.file.{Files, Path, Paths}
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** Bottom panel: three tabs.
  *
  *  - **Output data** — rows from the selected/activated task's persisted output,
  *    or an activated input's raw data. Loaded on demand on a background thread.
  *  - **Validations** — per-validation cards (name + status + SQL + failing rows
  *    sample) for the currently-selected task. Always reflects the current
  *    selection; no explicit load step needed.
  *  - **Run log** — overall job summary, per-task statuses, and error details.
  *
  * Output loading is triggered explicitly via [[loadTaskOutput]] / [[loadInputData]]
  * (the canvas's double-click handler). A load-token guards against stale loads
  * when the user activates a new node before the previous load completes.
  */
final class ResultsTabPane(session: JobSession) extends TabPane {

  private val MaxPreviewRows = 1000

  // ---- Output data tab ----
  private val outputTable = new TableView[Array[Any]]()
  outputTable.setPlaceholder(new Label(
    "Double-click a task or input node in the DAG to load its data.\n" +
      "Tasks without persisted output cannot be previewed — they will show a message instead."
  ))
  outputTable.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;")

  // Partition picker — only visible when the currently-activated task has 2+
  // historical runs on disk.
  private val pickerLabel = new Label("Run:")
  pickerLabel.setStyle("-fx-text-fill: #c5cad8; -fx-padding: 0 6 0 0;")
  private val pickerCombo = new ComboBox[RunChoice]()
  pickerCombo.setPrefWidth(560)
  private val pickerRow = new HBox(pickerLabel, pickerCombo)
  pickerRow.setAlignment(Pos.CENTER_LEFT)
  pickerRow.setPadding(new Insets(4, 8, 4, 8))
  pickerRow.setVisible(false)
  pickerRow.setManaged(false)

  // Setting items / value triggers the selection listener, which would
  // re-enter loadFromPath in unwanted ways during programmatic setup.
  private var pickerSuppressed: Boolean = false
  pickerCombo.valueProperty().addListener((_, _, newVal) => {
    if (!pickerSuppressed && newVal != null) loadFromPath(newVal.path.toString, Some(newVal))
  })

  private val outputStatus = new Label("")
  outputStatus.setStyle("-fx-text-fill: #c5cad8; -fx-padding: 4 8;")
  outputStatus.setWrapText(true)
  private val outputBox = new VBox(pickerRow, outputStatus, outputTable)
  VBox.setVgrow(outputTable, Priority.ALWAYS)
  private val outputTab = new Tab("Output data", outputBox)
  outputTab.setClosable(false)

  // ---- Validations tab ----
  private val validationsHeaderLabel = new Label("Select a task to see its validations.")
  validationsHeaderLabel.setStyle("-fx-text-fill: #c5cad8; -fx-padding: 4 8;")
  validationsHeaderLabel.setWrapText(true)
  private val validationsContainer = new VBox(10)
  validationsContainer.setPadding(new Insets(8))
  validationsContainer.setStyle("-fx-background-color: #2a2c38;")
  private val validationsScroll = new ScrollPane(validationsContainer)
  validationsScroll.setFitToWidth(true)
  validationsScroll.setStyle("-fx-background: #2a2c38; -fx-background-color: #2a2c38;")
  private val validationsBox = new VBox(validationsHeaderLabel, validationsScroll)
  VBox.setVgrow(validationsScroll, Priority.ALWAYS)
  private val validationsTab = new Tab("Validations", validationsBox)
  validationsTab.setClosable(false)

  // ---- Run log tab ----
  private val runLog = new TextArea("(no run yet)")
  runLog.setEditable(false)
  runLog.setWrapText(false)
  runLog.setFont(Font.font("Monospaced", 12))
  runLog.setStyle("-fx-control-inner-background: #1e1e2a; -fx-text-fill: #d4d4d4;")
  private val runLogTab = new Tab("Run log", runLog)
  runLogTab.setClosable(false)

  getTabs.addAll(outputTab, validationsTab, runLogTab)
  setPadding(Insets.EMPTY)
  setStyle("-fx-background-color: #2a2c38;")

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
        outputTable.getColumns.clear()
        outputTable.getItems.clear()
        outputStatus.setText(noOutputMessage(nameOpt, state.collect { case UiTaskState.Done(r) => r.status }))
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
      outputTable.getColumns.clear()
      outputTable.getItems.clear()
      outputStatus.setText("(unknown input)")
      return
    }
    val renderedPath = session.renderedInputPath(inputIndex).getOrElse(input.path)
    loadInputFromResolver(input, renderedPath)
  }

  private def populatePicker(runs: Seq[(Path, RunMarker)], planned: Option[String]): Unit = {
    pickerSuppressed = true
    try {
      pickerCombo.getItems.clear()
      runs.foreach { case (p, m) =>
        pickerCombo.getItems.add(toChoice(p, m, planned.contains(p.toString)))
      }
    } finally pickerSuppressed = false
    val shouldShow = runs.size >= 2
    pickerRow.setVisible(shouldShow)
    pickerRow.setManaged(shouldShow)
  }

  private val pickerTimeFmt: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)

  private def toChoice(path: Path, marker: RunMarker, isPlanned: Boolean = false): RunChoice =
    RunChoice(
      path = path,
      marker = marker,
      label = {
        val wrote = pickerTimeFmt.format(marker.writtenAt)
        val exec = pickerTimeFmt.format(marker.executionTime)
        val pin = if (isPlanned) "  ← current execution time" else ""
        f"$wrote  •  exec=$exec  •  ${marker.rowsProduced}%,d row(s)  •  $path$pin"
      }
    )

  /** Start a background load of `path` and apply the outcome to the table when
    * it completes (guarded by the load-token so stale loads can't clobber a
    * fresher one).
    */
  private def loadFromPath(path: String, choice: Option[RunChoice], explicitName: Option[String] = None): Unit = {
    val token = loadToken.incrementAndGet()
    outputStatus.setText("Loading…")
    outputTable.getColumns.clear()
    outputTable.getItems.clear()
    val nameOpt = explicitName.orElse(
      currentTaskIndex.flatMap(i => session.dag.flatMap(_.nodes.lift(i))).map(_.task.displayName)
    )
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
    outputTable.getColumns.clear()
    outputTable.getItems.clear()
    val nameOpt = Some(s"input ${input.viewName}")
    val worker = new Thread(new Runnable {
      def run(): Unit = {
        val outcome =
          try {
            val resolved = input.copy(path = renderedPath)
            LoadOutcome.Success(collectRows(InputResolver.resolve(resolved)))
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

  private def noOutputMessage(name: Option[String], status: Option[TaskStatus]): String = {
    val who = name.map(n => s"'$n'").getOrElse("This task")
    val why = status match {
      case Some(TaskStatus.Succeeded) => "succeeded but has no outputFile set"
      case Some(TaskStatus.Failed(_)) => "failed before producing output"
      case Some(TaskStatus.ValidationFailed(_)) => "completed but output may be incomplete (see Run log)"
      case Some(TaskStatus.Skipped(_)) => "was skipped"
      case Some(TaskStatus.Pending) | None => "hasn't run yet — press Run to produce its output"
    }
    s"$who: no persisted output available — $why."
  }

  private def applyOutcome(
      name: Option[String],
      path: String,
      outcome: LoadOutcome,
      choice: Option[RunChoice]
  ): Unit = {
    outputTable.getColumns.clear()
    outputTable.getItems.clear()
    outcome match {
      case LoadOutcome.Success(loaded) =>
        val items: ObservableList[Array[Any]] = FXCollections.observableArrayList(loaded.rows: _*)
        // Build columns from the schema.
        var i = 0
        while (i < loaded.schema.length) {
          val col = new TableColumn[Array[Any], String](loaded.schema.fields(i).name)
          val colIdx = i
          col.setCellValueFactory(features => {
            val row = features.getValue
            val cell = if (row != null && colIdx < row.length) row(colIdx) else null
            new ReadOnlyStringWrapper(formatCell(cell))
          })
          col.setPrefWidth(140)
          outputTable.getColumns.add(col)
          i += 1
        }
        outputTable.setItems(items)
        val truncated = if (loaded.truncated) " (preview truncated)" else ""
        val who = name.map(n => s"'$n'").getOrElse("Task")
        val runInfo = choice.map { c =>
          val wrote = pickerTimeFmt.format(c.marker.writtenAt)
          val exec = pickerTimeFmt.format(c.marker.executionTime)
          s" • exec=$exec • written=$wrote"
        }.getOrElse("")
        outputStatus.setText(f"$who • $path • ${items.size}%,d row(s) loaded$truncated$runInfo")
      case LoadOutcome.Failed(err) =>
        outputStatus.setText(s"Failed to load $path:\n$err")
    }
  }

  private def loadFromDisk(path: String): LoadedRows = {
    val p = Paths.get(path)
    if (!Files.exists(p)) throw new RuntimeException(s"output path does not exist: $path")
    detectFormat(p) match {
      case "parquet" =>
        ParquetReaderHook.get match {
          case Some(reader) => collectRows(reader(path))
          case None => throw new RuntimeException(
            "parquet read module not on classpath — add //src/main/scala/com/transformer/read/parquet " +
              "to the launcher deps to preview parquet output"
          )
        }
      case _ => // CSV (and default)
        val view = CsvReader.fromPath(path, CsvOptions())
        collectRows(view)
    }
  }

  private def detectFormat(p: Path): String = {
    val s = p.toString.toLowerCase
    if (s.endsWith(".parquet") || s.contains(".parquet")) "parquet"
    else if (s.endsWith(".csv") || s.contains(".csv")) "csv"
    else "csv"
  }

  private def collectRows(view: com.transformer.core.CatalogView): LoadedRows = {
    val schema = view.schema
    val rows = scala.collection.mutable.ArrayBuffer.empty[Array[Any]]
    var truncated = false
    var p = 0
    val nPartitions = view.numPartitions
    while (p < nPartitions && rows.size < MaxPreviewRows) {
      val it = view.readPartition(p)
      while (it.hasNext && rows.size < MaxPreviewRows) {
        val batch = it.next()
        appendBatch(batch, rows)
      }
      if (it.hasNext) truncated = true
      p += 1
    }
    if (p < nPartitions) truncated = true
    if (rows.size > MaxPreviewRows) {
      rows.remove(MaxPreviewRows, rows.size - MaxPreviewRows)
      truncated = true
    }
    LoadedRows(schema, rows.toSeq, truncated)
  }

  private def appendBatch(batch: ColumnarBatch, out: scala.collection.mutable.ArrayBuffer[Array[Any]]): Unit = {
    val width = batch.schema.length
    var r = 0
    while (r < batch.numRows && out.size < MaxPreviewRows) {
      val arr = new Array[Any](width)
      var c = 0
      while (c < width) {
        val col = batch.column(c)
        arr(c) = if (col.isNull(r)) null else col.getBoxed(r)
        c += 1
      }
      out += arr
      r += 1
    }
  }

  private def formatCell(v: Any): String = v match {
    case null => ""
    case s: String => s
    case bd: java.math.BigDecimal => bd.toPlainString
    case bytes: Array[Byte] => s"<binary ${bytes.length} bytes>"
    case other => String.valueOf(other)
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
                validationsContainer.getChildren.add(buildValidationCard(v, rendered, state))
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
    l.setStyle("-fx-text-fill: #9ba2b8; -fx-padding: 8; -fx-font-style: italic;")
    l.setWrapText(true)
    l
  }

  private def buildValidationCard(v: Validation, renderedSql: String, state: Option[UiTaskState]): javafx.scene.Node = {
    val (statusText, statusStyle, failureSample) = validationStatusFor(v, state)
    // Header: name + status pill + open-in-editor.
    val nameLabel = new Label(v.name)
    nameLabel.setStyle("-fx-text-fill: #e8ecf5; -fx-font-weight: bold; -fx-font-size: 13px;")
    val statusPill = new Label(statusText)
    statusPill.setStyle(statusStyle)
    val headerSpacer = new javafx.scene.layout.Region()
    HBox.setHgrow(headerSpacer, Priority.ALWAYS)
    val cardHeader = new HBox(8, nameLabel, statusPill, headerSpacer)
    cardHeader.setAlignment(Pos.CENTER_LEFT)

    // SQL: a small SqlView showing the rendered query.
    val sqlView = new SqlView(showOpenInEditor = true)
    sqlView.setSql(renderedSql)
    sqlView.setSourceFile(v.sqlFile.map(Paths.get(_)))
    sqlView.setPrefHeight(140)

    val cardChildren = new java.util.ArrayList[javafx.scene.Node]()
    cardChildren.add(cardHeader)
    cardChildren.add(sqlView)
    failureSample.foreach { sample =>
      val sampleLabel = new Label("Failing rows (first 10):")
      sampleLabel.setStyle("-fx-text-fill: #f8a3a3; -fx-font-size: 11px; -fx-font-weight: bold;")
      val sampleArea = new TextArea(sample)
      sampleArea.setEditable(false)
      sampleArea.setWrapText(false)
      sampleArea.setPrefRowCount(math.min(10, math.max(2, sample.count(_ == '\n') + 1)))
      sampleArea.setFont(Font.font("Monospaced", 11))
      sampleArea.setStyle("-fx-control-inner-background: #3a2128; -fx-text-fill: #f8a3a3;")
      cardChildren.add(sampleLabel)
      cardChildren.add(sampleArea)
    }

    val card = new VBox(6)
    card.setPadding(new Insets(10))
    card.setStyle("-fx-background-color: #34384a; -fx-background-radius: 6;")
    card.getChildren.addAll(cardChildren)
    card
  }

  /** Returns (status label, CSS for the status pill, optional sample CSV of failing rows). */
  private def validationStatusFor(v: Validation, state: Option[UiTaskState]): (String, String, Option[String]) = {
    def pill(bg: String, fg: String): String =
      s"-fx-background-color: $bg; -fx-text-fill: $fg; -fx-padding: 2 10; " +
        "-fx-background-radius: 10; -fx-font-size: 11px; -fx-font-weight: bold;"
    val pass    = pill("#1f5b30", "#ffffff")
    val fail    = pill("#5a2828", "#ffffff")
    val neutral = pill("#3a3f55", "#d7dcec")
    val running = pill("#2b4f7a", "#ffffff")
    state match {
      case Some(UiTaskState.Done(result)) =>
        result.status match {
          case TaskStatus.Succeeded =>
            ("PASSED", pass, None)
          case TaskStatus.ValidationFailed(fs) =>
            fs.find(_.validationName == v.name) match {
              case Some(f) =>
                val plural = if (f.rowCount == 1) "" else "s"
                (s"FAILED • ${f.rowCount} row$plural", fail, Some(f.sampleRowsCsv))
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
      case Some(UiTaskState.Running) => ("RUNNING", running, None)
      case Some(UiTaskState.Pending) | None => ("PENDING", neutral, None)
    }
  }

  private def refreshRunLog(): Unit = {
    val sb = new StringBuilder()
    session.runState match {
      case RunState.Idle =>
        val dag = session.dag
        val cachedTasks = dag.toSeq.flatMap(_.nodes).count(n => session.markerFor(n.index).isDefined)
        val totalTasks = dag.map(_.nodes.size).getOrElse(0)
        if (cachedTasks > 0) {
          sb.append(s"$cachedTasks of $totalTasks task(s) restored from _SUCCESS markers (previous run). Press Run to refresh.\n")
        } else {
          sb.append("(no run yet)\n")
        }
      case RunState.Running => sb.append("Running…\n")
      case RunState.LoadFailed(err) => sb.append(s"Load failed:\n$err\n")
      case RunState.Done(result) =>
        sb.append(if (result.succeeded) "Run succeeded.\n" else "Run finished with failure(s).\n")
        result.error.foreach(e => sb.append(s"\nJob-level error: $e\n"))
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

  private def describeStatus(status: TaskStatus): String = status match {
    case TaskStatus.Succeeded => "succeeded"
    case TaskStatus.Failed(_) => "failed"
    case TaskStatus.ValidationFailed(fs) => s"validation failed (${fs.size})"
    case TaskStatus.Skipped(_) => "skipped"
    case TaskStatus.Pending => "pending"
  }
}

private final case class LoadedRows(schema: Schema, rows: Seq[Array[Any]], truncated: Boolean)

private sealed trait LoadOutcome
private object LoadOutcome {
  final case class Success(loaded: LoadedRows) extends LoadOutcome
  final case class Failed(error: String) extends LoadOutcome
}

/** One entry in the partition picker. ComboBox renders entries by their
  * `toString`, so the label goes there.
  */
private final case class RunChoice(path: Path, marker: RunMarker, label: String) {
  override def toString: String = label
}
