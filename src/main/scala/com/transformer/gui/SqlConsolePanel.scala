package com.transformer.gui

import com.transformer.core.{Catalog, ColumnarBatch, MaterializedView, Schema, SqlExecutorRegistry}

import javafx.beans.property.ReadOnlyStringWrapper
import javafx.collections.{FXCollections, ObservableList}
import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination, KeyEvent}
import javafx.scene.layout.{FlowPane, HBox, Priority, VBox}
import javafx.scene.text.Font

import java.util.concurrent.atomic.AtomicLong
import scala.util.control.NonFatal

/** Interactive SQL console.
  *
  * Lets the user write a one-off query against the loaded job's inputs and
  * any task outputs already persisted to disk. The result is materialized in
  * memory so the rows can be browsed in a table and (optionally) written out
  * via the Persist button — reusing the library's standard CSV / Parquet
  * writers and OutputFilePath semantics.
  *
  * Lifecycle:
  *   * On Run we call [[JobSession.buildInteractiveCatalog]] every time,
  *     so the catalog reflects the current execution time, output dir, and
  *     any in-session run results.
  *   * The query runs on a worker thread (see `loadToken`); a stale result
  *     can never clobber a newer one.
  *   * `lastResult` retains the most recent materialized view so Persist can
  *     replay its partitions into the writer.
  */
final class SqlConsolePanel(session: JobSession, owner: () => javafx.stage.Stage) extends VBox {

  private val MaxPreviewRows = 1000

  private val PanelBg = "#2a2c38"

  setSpacing(8)
  setPadding(new Insets(8))
  setStyle(s"-fx-background-color: $PanelBg;")

  // ---- Available views row -------------------------------------------------
  private val viewsHeader = new Label("Available views")
  viewsHeader.setStyle("-fx-text-fill: #e8ecf5; -fx-font-size: 11px; -fx-font-weight: bold;")
  private val refreshButton = new Button("Refresh")
  refreshButton.setStyle(
    "-fx-background-color: #3a3f55; -fx-text-fill: #d7dcec; " +
      "-fx-background-radius: 4; -fx-padding: 2 10; -fx-font-size: 11px;"
  )
  refreshButton.setOnAction(_ => refreshViewsListing())
  private val viewsSpacer = new javafx.scene.layout.Region()
  HBox.setHgrow(viewsSpacer, Priority.ALWAYS)
  private val viewsHeaderRow = new HBox(8, viewsHeader, viewsSpacer, refreshButton)
  viewsHeaderRow.setAlignment(Pos.CENTER_LEFT)

  private val viewsFlow = new FlowPane(6, 4)
  viewsFlow.setPadding(new Insets(0))
  viewsFlow.setAlignment(Pos.CENTER_LEFT)

  // ---- Editor --------------------------------------------------------------
  private val editor = new TextArea("SELECT *\nFROM <view>\nLIMIT 100")
  editor.setFont(Font.font("Monospaced", 13))
  editor.setStyle(
    "-fx-control-inner-background: #1e1e2a; -fx-text-fill: #d4d4d4; " +
      "-fx-highlight-fill: #264f78; -fx-highlight-text-fill: #ffffff;"
  )
  editor.setPrefRowCount(12)
  editor.setPrefColumnCount(36)
  editor.setWrapText(false)
  VBox.setVgrow(editor, Priority.ALWAYS)

  // ---- Action row ----------------------------------------------------------
  private val shortcutHint: String =
    if (System.getProperty("os.name", "").toLowerCase.contains("mac")) "⌘⏎" else "Ctrl+Enter"
  private val runButton = primaryButton(s"Run query  ($shortcutHint)")
  runButton.setOnAction(_ => runQuery())
  runButton.setTooltip(new Tooltip(s"Run the query — keyboard: $shortcutHint"))
  private val persistButton = secondaryButton("Persist results…")
  persistButton.setOnAction(_ => onPersist())
  persistButton.setDisable(true)
  private val actionSpacer = new javafx.scene.layout.Region()
  HBox.setHgrow(actionSpacer, Priority.ALWAYS)
  private val statusLabel = new Label("")
  statusLabel.setStyle("-fx-text-fill: #c5cad8; -fx-font-size: 11px;")
  statusLabel.setWrapText(true)
  private val actionRow = new HBox(8, runButton, persistButton, actionSpacer, statusLabel)
  actionRow.setAlignment(Pos.CENTER_LEFT)

  // ---- Results table -------------------------------------------------------
  private val resultsTable = new TableView[Array[Any]]()
  resultsTable.setPlaceholder(new Label(
    s"No result yet. Pick a view above and press Run query ($shortcutHint)."
  ))
  resultsTable.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;")

  // ---- Side-by-side: editor (left) + results table (right) -----------------
  private val editorColumn = new VBox(8, editor, actionRow)
  editorColumn.setMinWidth(280)
  editorColumn.setPrefWidth(420)
  private val resultsColumn = new VBox(resultsTable)
  VBox.setVgrow(resultsTable, Priority.ALWAYS)
  private val workSplit = new SplitPane(editorColumn, resultsColumn)
  workSplit.setDividerPositions(0.36)
  VBox.setVgrow(workSplit, Priority.ALWAYS)

  // Cmd/Ctrl+Enter at the panel level so the shortcut fires regardless of
  // which control inside the console currently has focus.
  this.addEventFilter(KeyEvent.KEY_PRESSED, (ev: KeyEvent) => {
    val combo = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
    if (combo.`match`(ev)) { runQuery(); ev.consume() }
  })

  // Assemble.
  getChildren.addAll(viewsHeaderRow, viewsFlow, workSplit)

  private val loadToken = new AtomicLong(0L)
  // The most recent successful run's materialized view, or None. Cleared on
  // editor edits so we never persist results that don't match the editor text.
  private var lastResult: Option[InteractiveResult] = None

  editor.textProperty().addListener((_, _, _) => {
    lastResult = None
    persistButton.setDisable(true)
  })

  session.addListener(() => refreshViewsListing())
  refreshViewsListing()

  // -------------------- listing --------------------

  /** Rebuild the chips row by inspecting `session.buildInteractiveCatalog()`.
    *
    * IMPORTANT: this opens every input + persisted-output parquet/CSV via
    * [[InputResolver.resolve]] / [[JobSession.readOutputAsView]] to read
    * footers for the schema chips. Those reads run on the shared
    * [[com.transformer.core.Scheduler]] pool. If a job is currently running,
    * the pool's workers are tied up scanning + writing partitions and any
    * footer read submitted from the FX thread would queue behind them — the
    * FX thread blocks waiting for its `.get()` call, and the GUI freezes.
    *
    * Skip the rebuild while a run is in progress. [[JobSession.endRun]]
    * calls [[JobSession.notifyListeners]] after flipping `_runState`, so this
    * listener fires again the moment the run finishes and we get one clean
    * rebuild then. The chip row stays at its pre-run snapshot during the
    * run, which is correct anyway: outputs aren't readable on disk until
    * their owning task completes.
    */
  private def refreshViewsListing(): Unit = {
    if (session.isRunning) return
    viewsFlow.getChildren.clear()
    val cat = session.buildInteractiveCatalog()
    if (cat.views.isEmpty) {
      val l = new Label(
        if (session.dataJob.isEmpty) "(open a job to see available views)"
        else "(no input or task-output views — run the pipeline first)"
      )
      l.setStyle("-fx-text-fill: #9ba2b8; -fx-font-size: 11px; -fx-font-style: italic;")
      viewsFlow.getChildren.add(l)
    } else {
      cat.views.foreach { v =>
        val chip = viewChip(v)
        viewsFlow.getChildren.add(chip)
      }
    }
    if (cat.errors.nonEmpty) {
      val warn = new Label(s"${cat.errors.size} view(s) failed to resolve — hover for details")
      warn.setStyle("-fx-text-fill: #f8a3a3; -fx-font-size: 11px;")
      warn.setTooltip(new Tooltip(cat.errors.mkString("\n")))
      viewsFlow.getChildren.add(warn)
    }
  }

  private def viewChip(spec: InteractiveViewSpec): Label = {
    val l = new Label(spec.name)
    val (bg, border) = spec.kind match {
      case ViewKind.Input => ("#2b4a3a", "#5fa17a")
      case ViewKind.Task  => ("#2b4f7a", "#4a90e2")
    }
    l.setStyle(
      s"-fx-background-color: $bg; -fx-text-fill: #d7dcec; -fx-padding: 2 10; " +
        s"-fx-background-radius: 10; -fx-border-color: $border; -fx-border-radius: 10; " +
        "-fx-font-size: 11px; -fx-font-family: monospace; -fx-cursor: hand;"
    )
    val cols = spec.schema.mkString(", ")
    val tooltip = new StringBuilder()
    tooltip.append(spec.kind match {
      case ViewKind.Input => "Input view"
      case ViewKind.Task  => "Task output"
    })
    spec.path.foreach(p => tooltip.append('\n').append(p))
    if (cols.nonEmpty) tooltip.append("\nColumns: ").append(cols)
    l.setTooltip(new Tooltip(tooltip.toString))
    l.setOnMouseClicked(_ => insertAtCaret(spec.name))
    l
  }

  private def insertAtCaret(text: String): Unit = {
    val caret = editor.getCaretPosition
    editor.insertText(caret, text)
    editor.requestFocus()
  }

  // -------------------- run --------------------

  private def runQuery(): Unit = {
    val sql = editor.getText
    if (sql == null || sql.trim.isEmpty) {
      setStatusError("Enter a query first.")
      return
    }
    if (!SqlExecutorRegistry.isInstalled) {
      // Trigger self-install — same trick ControlsPanel uses before Run.
      try com.transformer.sql.exec.SqlEngine.init() catch { case _: Throwable => () }
    }
    val token = loadToken.incrementAndGet()
    setStatusRunning()
    persistButton.setDisable(true)
    lastResult = None
    val cat = session.buildInteractiveCatalog()
    val worker = new Thread(new Runnable {
      def run(): Unit = {
        val outcome = try {
          val start = System.nanoTime()
          val executed = SqlExecutorRegistry.get.execute(sql, cat.catalog)
          val partitions = (0 until executed.numPartitions).map(p =>
            executed.partition(p).toIndexedSeq
          ).toIndexedSeq
          val mv = new MaterializedView(executed.schema, partitions)
          val durationMs = (System.nanoTime() - start) / 1_000_000L
          QueryOutcome.Success(mv, durationMs)
        } catch {
          case NonFatal(e) =>
            QueryOutcome.Failed(Option(e.getMessage).getOrElse(e.toString))
        }
        FxHelpers.onFx {
          if (loadToken.get() == token) applyOutcome(outcome, sql)
        }
      }
    }, s"transformer-gui-sql-console-$token")
    worker.setDaemon(true)
    worker.start()
  }

  private def applyOutcome(outcome: QueryOutcome, sql: String): Unit = {
    resultsTable.getColumns.clear()
    resultsTable.getItems.clear()
    outcome match {
      case QueryOutcome.Success(mv, durationMs) =>
        val (rows, truncated, total) = previewRows(mv)
        var i = 0
        while (i < mv.schema.length) {
          val col = new TableColumn[Array[Any], String](mv.schema.fields(i).name)
          val colIdx = i
          col.setCellValueFactory(features => {
            val r = features.getValue
            val cell = if (r != null && colIdx < r.length) r(colIdx) else null
            new ReadOnlyStringWrapper(formatCell(cell))
          })
          col.setPrefWidth(140)
          resultsTable.getColumns.add(col)
          i += 1
        }
        val items: ObservableList[Array[Any]] = FXCollections.observableArrayList(rows: _*)
        resultsTable.setItems(items)
        lastResult = Some(InteractiveResult(mv, total))
        persistButton.setDisable(false)
        val truncNote = if (truncated) s" (preview truncated to $MaxPreviewRows of $total rows)" else ""
        setStatusOk(f"$total%,d row(s) • ${durationMs}%,d ms$truncNote")
      case QueryOutcome.Failed(err) =>
        lastResult = None
        persistButton.setDisable(true)
        setStatusError("Query failed — see error popup.")
        SqlErrorDialog.show(owner(), "Query failed", err, Some(sql))
    }
  }

  /** Pull up to MaxPreviewRows rows out of `mv` for display. Returns
    * (rows, truncated, totalRows).
    */
  private def previewRows(mv: MaterializedView): (Seq[Array[Any]], Boolean, Long) = {
    val rows = scala.collection.mutable.ArrayBuffer.empty[Array[Any]]
    var total = 0L
    var truncated = false
    var p = 0
    while (p < mv.numPartitions) {
      val it = mv.readPartition(p)
      while (it.hasNext) {
        val b = it.next()
        total += b.numRows.toLong
        var r = 0
        while (r < b.numRows && rows.size < MaxPreviewRows) {
          val arr = new Array[Any](b.schema.length)
          var c = 0
          while (c < b.schema.length) {
            val col = b.column(c)
            arr(c) = if (col.isNull(r)) null else col.getBoxed(r)
            c += 1
          }
          rows += arr
          r += 1
        }
        if (rows.size >= MaxPreviewRows && r < b.numRows) truncated = true
      }
      p += 1
    }
    if (total > rows.size.toLong) truncated = true
    (rows.toSeq, truncated, total)
  }

  private def formatCell(v: Any): String = v match {
    case null => ""
    case s: String => s
    case bd: java.math.BigDecimal => bd.toPlainString
    case bytes: Array[Byte] => s"<binary ${bytes.length} bytes>"
    case other => String.valueOf(other)
  }

  // -------------------- persist --------------------

  private def onPersist(): Unit = {
    val result = lastResult.getOrElse {
      setStatusError("No result to persist — run a query first.")
      return
    }
    PersistDialog.show(owner(), session, defaultFormat = "csv") match {
      case Some(cfg) => persistResult(result, cfg)
      case None => () // cancelled
    }
  }

  private def persistResult(result: InteractiveResult, cfg: PersistConfig): Unit = {
    setStatusRunning("Persisting…")
    persistButton.setDisable(true)
    val worker = new Thread(new Runnable {
      def run(): Unit = {
        val outcome = try {
          val rows = ResultPersister.persist(result.view, cfg)
          PersistOutcome.Success(cfg.outputDir, rows)
        } catch {
          case NonFatal(e) =>
            PersistOutcome.Failed(Option(e.getMessage).getOrElse(e.toString))
        }
        FxHelpers.onFx {
          persistButton.setDisable(false)
          outcome match {
            case PersistOutcome.Success(dir, rows) =>
              setStatusOk(f"Persisted $rows%,d row(s) → $dir")
            case PersistOutcome.Failed(err) =>
              setStatusError("Persist failed — see error popup.")
              SqlErrorDialog.show(owner(), "Persist failed", err, None)
          }
        }
      }
    }, "transformer-gui-sql-console-persist")
    worker.setDaemon(true)
    worker.start()
  }

  // -------------------- status helpers --------------------

  private def setStatusRunning(text: String = "Running…"): Unit = {
    statusLabel.setText(text)
    statusLabel.setStyle("-fx-text-fill: #b9c2db; -fx-font-size: 11px;")
  }
  private def setStatusOk(text: String): Unit = {
    statusLabel.setText(text)
    statusLabel.setStyle("-fx-text-fill: #c5cad8; -fx-font-size: 11px;")
  }
  private def setStatusError(text: String): Unit = {
    statusLabel.setText(text)
    statusLabel.setStyle("-fx-text-fill: #f8a3a3; -fx-font-size: 11px;")
  }

  private def primaryButton(label: String): Button = {
    val b = new Button(label)
    b.setStyle(
      "-fx-background-color: #3d6ee8; -fx-text-fill: white; " +
        "-fx-padding: 6 16; -fx-background-radius: 4; -fx-font-size: 12px; -fx-font-weight: bold;"
    )
    b
  }

  private def secondaryButton(label: String): Button = {
    val b = new Button(label)
    b.setStyle(
      "-fx-background-color: #3a3f55; -fx-text-fill: #d7dcec; " +
        "-fx-padding: 6 16; -fx-background-radius: 4; -fx-font-size: 12px;"
    )
    b
  }
}

private final case class InteractiveResult(view: MaterializedView, totalRows: Long)

private sealed trait QueryOutcome
private object QueryOutcome {
  final case class Success(view: MaterializedView, durationMs: Long) extends QueryOutcome
  final case class Failed(error: String) extends QueryOutcome
}

private sealed trait PersistOutcome
private object PersistOutcome {
  final case class Success(dir: String, rows: Long) extends PersistOutcome
  final case class Failed(error: String) extends PersistOutcome
}
