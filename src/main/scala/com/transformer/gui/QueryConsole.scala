package com.transformer.gui

import atlantafx.base.theme.{Styles, Tweaks}
import com.transformer.core.{Catalog, MaterializedView, SqlExecutorRegistry}

import javafx.geometry.Pos
import javafx.scene.control.{Button, Label, SplitPane, Tooltip}
import javafx.scene.input.{KeyCode, KeyCodeCombination, KeyCombination, KeyEvent}
import javafx.scene.layout.{HBox, Priority, Region, VBox}
import javafx.stage.Stage

import java.util.concurrent.atomic.AtomicLong
import scala.collection.mutable
import scala.util.control.NonFatal

/** A SQL console: an editor with Run query and Persist results… above it,
  * and the result's rows in a [[DataGrid]] beside it. ⌘⏎ (Ctrl+Enter)
  * anywhere in it runs the query. The job's SQL console and the data viewer's
  * query tabs are each one.
  *
  * The query runs on a worker thread against the catalog `prepare` gives.
  * `prepare` itself runs on the FX thread as the query starts, so it can read
  * the host's state there; the function it returns builds the catalog for the
  * query's text, on the worker, where reading files does not hold up the
  * window. The result is materialized, so Persist can write it after; the
  * grid shows its first [[DataGrid.PreviewRows]] rows. A query started later
  * always wins over one still running.
  */
final class QueryConsole(
    owner: () => Stage,
    prepare: () => String => Catalog,
    persistDir: () => Option[String]
) extends SplitPane {

  private lazy val shortcutHint: String =
    if (System.getProperty("os.name", "").toLowerCase.contains("mac")) "⌘⏎" else "Ctrl+Enter"

  // ---- Editor --------------------------------------------------------------
  val editor = new SqlEditor()
  editor.setOnWrite(() => Some(s"Nothing to write here: $shortcutHint runs the query"))
  VBox.setVgrow(editor, Priority.ALWAYS)

  // ---- Action row ----------------------------------------------------------
  private val runButton = new Button("Run query", Icons.play)
  runButton.getStyleClass.addAll(Styles.ACCENT, Styles.SMALL, "run-button")
  runButton.setOnAction(_ => runQuery())
  runButton.setTooltip(new Tooltip(s"Run the query: $shortcutHint"))
  private val runHint = new Label(shortcutHint)
  runHint.getStyleClass.add("shortcut-hint")
  private val persistButton = new Button("Persist results…")
  persistButton.getStyleClass.add(Styles.SMALL)
  persistButton.setOnAction(_ => onPersist())
  persistButton.setDisable(true)
  private val actionSpacer = new Region()
  HBox.setHgrow(actionSpacer, Priority.ALWAYS)
  private val statusLabel = new Label("")
  statusLabel.getStyleClass.add("muted")
  statusLabel.setWrapText(true)
  private val actionRow = new HBox(8, runButton, runHint, persistButton, actionSpacer, statusLabel)
  actionRow.setAlignment(Pos.CENTER_LEFT)

  // ---- Results -------------------------------------------------------------
  private val results = new DataGrid()
  results.getStyleClass.add(Styles.BORDERED)
  results.setPlaceholderText(s"No result yet. Write a query and press Run query ($shortcutHint).")

  // ---- Side by side: the editor (left) and the rows (right) ----------------
  private val editorColumn = new VBox(8, actionRow, editor)
  editorColumn.setMinWidth(280)
  editorColumn.setPrefWidth(460)
  private val resultsColumn = new VBox(results)
  VBox.setVgrow(results, Priority.ALWAYS)
  getItems.addAll(editorColumn, resultsColumn)
  getStyleClass.add(Tweaks.EDGE_TO_EDGE)
  setDividerPositions(0.4)

  // The shortcut is the console's, whichever control in it has focus.
  private val runCombo = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN)
  addEventFilter(KeyEvent.KEY_PRESSED, (ev: KeyEvent) => if (runCombo.`match`(ev)) { runQuery(); ev.consume() })

  private val loadToken = new AtomicLong(0L)
  // The last query's materialized result, until the text changes: Persist
  // never writes rows the editor's query did not produce.
  private var lastResult: Option[MaterializedView] = None

  editor.textProperty.addListener((_, _, _) => {
    lastResult = None
    persistButton.setDisable(true)
  })

  /** Replace the editor's text with `sql`. */
  def setQuery(sql: String): Unit = editor.setText(sql)

  /** Put `text` in at the editor's caret and focus it. */
  def insertAtCaret(text: String): Unit = editor.insertAtCaret(text)

  def focusEditor(): Unit = editor.focusEditor()

  /** Run the editor's query, as Run query does. */
  def runQuery(): Unit = {
    val sql = editor.text
    if (sql == null || sql.trim.isEmpty) {
      setStatus("Enter a query first.", error = true)
      return
    }
    val catalogFor =
      try prepare()
      catch {
        case NonFatal(e) =>
          setStatus(Option(e.getMessage).getOrElse(e.toString), error = true)
          return
      }
    // The engine registers itself on first use; touch it so it has.
    if (!SqlExecutorRegistry.isInstalled) try com.transformer.sql.exec.SqlEngine.init() catch { case _: Throwable => () }
    val token = loadToken.incrementAndGet()
    setStatus("Running…", error = false)
    persistButton.setDisable(true)
    lastResult = None
    val worker = new Thread(() => {
      val outcome = try {
        val start = System.nanoTime()
        val executed = SqlExecutorRegistry.get.execute(sql, catalogFor(sql))
        val partitions = (0 until executed.numPartitions).map(p => executed.partition(p).toIndexedSeq)
        val mv = new MaterializedView(executed.schema, partitions)
        QueryConsole.Outcome.Success(mv, (System.nanoTime() - start) / 1000000L)
      } catch {
        case NonFatal(e) => QueryConsole.Outcome.Failed(Option(e.getMessage).getOrElse(e.toString))
      }
      FxHelpers.onFx {
        if (loadToken.get() == token) applyOutcome(outcome, sql)
      }
    }, s"transformer-gui-sql-console-$token")
    worker.setDaemon(true)
    worker.start()
  }

  private def applyOutcome(outcome: QueryConsole.Outcome, sql: String): Unit = outcome match {
    case QueryConsole.Outcome.Success(mv, durationMs) =>
      val rows = mutable.ArrayBuffer.empty[Array[Any]]
      var total = 0L
      var p = 0
      while (p < mv.numPartitions) {
        mv.readPartition(p).foreach { b =>
          total += b.numRows
          DataGrid.append(b, rows, DataGrid.PreviewRows)
        }
        p += 1
      }
      results.show(mv.schema, rows.toSeq)
      lastResult = Some(mv)
      persistButton.setDisable(false)
      val shown = if (total > rows.size) f" (showing the first ${rows.size}%,d)" else ""
      setStatus(f"$total%,d row(s) • $durationMs%,d ms$shown", error = false)
    case QueryConsole.Outcome.Failed(err) =>
      results.clear()
      lastResult = None
      persistButton.setDisable(true)
      setStatus("Query failed — see error popup.", error = true)
      SqlErrorDialog.show(owner(), "Query failed", err, Some(sql))
  }

  private def onPersist(): Unit = {
    val result = lastResult.getOrElse {
      setStatus("No result to persist — run a query first.", error = true)
      return
    }
    PersistDialog.show(owner(), persistDir(), defaultFormat = "csv").foreach { cfg =>
      setStatus("Persisting…", error = false)
      persistButton.setDisable(true)
      val worker = new Thread(() => {
        val outcome =
          try Right(ResultPersister.persist(result, cfg))
          catch { case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.toString)) }
        FxHelpers.onFx {
          persistButton.setDisable(false)
          outcome match {
            case Right(rows) => setStatus(f"Persisted $rows%,d row(s) → ${cfg.outputDir}", error = false)
            case Left(err) =>
              setStatus("Persist failed — see error popup.", error = true)
              SqlErrorDialog.show(owner(), "Persist failed", err, None)
          }
        }
      }, "transformer-gui-sql-console-persist")
      worker.setDaemon(true)
      worker.start()
    }
  }

  private def setStatus(text: String, error: Boolean): Unit = {
    statusLabel.setText(text)
    statusLabel.pseudoClassStateChanged(Styles.STATE_DANGER, error)
  }
}

object QueryConsole {
  private sealed trait Outcome
  private object Outcome {
    final case class Success(view: MaterializedView, durationMs: Long) extends Outcome
    final case class Failed(error: String) extends Outcome
  }
}
