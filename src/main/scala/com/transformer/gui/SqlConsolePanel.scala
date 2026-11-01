package com.transformer.gui

import atlantafx.base.theme.Styles

import javafx.geometry.{Insets, Pos}
import javafx.scene.control._
import javafx.scene.layout.{FlowPane, HBox, Priority, VBox}

import java.io.File

/** The job's SQL console: a chip per view a query can read (the inputs, and
  * the task outputs on disk) above a [[QueryConsole]].
  *
  * Each query runs against [[JobSession.buildInteractiveCatalog]], built
  * afresh as the query starts, so it reflects the current execution time,
  * output dir and any in-session run results. Persist writes under the
  * output directory, in `interactive/`, unless the user picks another.
  */
final class SqlConsolePanel(session: JobSession, owner: () => javafx.stage.Stage) extends VBox {

  getStyleClass.add("panel")
  setSpacing(10)
  setPadding(new Insets(12, 14, 12, 14))

  // ---- Available views row -------------------------------------------------
  private val viewsHeader = new Label("VIEWS")
  viewsHeader.getStyleClass.add("section-label")
  private val refreshButton = new Button("Refresh", Icons.refresh)
  refreshButton.getStyleClass.addAll(Styles.SMALL, Styles.FLAT)
  refreshButton.setTooltip(new Tooltip("Read the inputs and the task outputs on disk again"))
  refreshButton.setOnAction(_ => refreshViewsListing())

  private val viewsFlow = new FlowPane(6, 4)
  viewsFlow.setPadding(new Insets(0))
  viewsFlow.setAlignment(Pos.CENTER_LEFT)
  HBox.setHgrow(viewsFlow, Priority.ALWAYS)
  private val viewsHeaderRow = new HBox(10, viewsHeader, viewsFlow, refreshButton)
  viewsHeaderRow.setAlignment(Pos.CENTER_LEFT)

  private val console = new QueryConsole(
    owner,
    prepare = () => {
      val cat = session.buildInteractiveCatalog()
      _ => cat.catalog
    },
    persistDir = () => session.effectiveOutputDirRendered.map(base => new File(base, "interactive").getAbsolutePath)
  )
  console.setQuery("SELECT *\nFROM <view>\nLIMIT 100")
  VBox.setVgrow(console, Priority.ALWAYS)

  getChildren.addAll(viewsHeaderRow, viewsFlow, console)

  session.addListener(() => refreshViewsListing())
  refreshViewsListing()

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
      l.getStyleClass.add("hint")
      viewsFlow.getChildren.add(l)
    } else {
      cat.views.foreach(v => viewsFlow.getChildren.add(viewChip(v)))
    }
    if (cat.errors.nonEmpty) {
      val warn = new Label(s"${cat.errors.size} view(s) failed to resolve — hover for details")
      warn.getStyleClass.addAll("chip", "danger")
      warn.setTooltip(new Tooltip(cat.errors.mkString("\n")))
      viewsFlow.getChildren.add(warn)
    }
  }

  private def viewChip(spec: InteractiveViewSpec): Label = {
    val l = new Label(spec.name)
    l.getStyleClass.addAll("chip", spec.kind match {
      case ViewKind.Input => "input"
      case ViewKind.Task  => "task"
    })
    val cols = spec.schema.mkString(", ")
    val tooltip = new StringBuilder()
    tooltip.append(spec.kind match {
      case ViewKind.Input => "Input view"
      case ViewKind.Task  => "Task output"
    })
    spec.path.foreach(p => tooltip.append('\n').append(p))
    if (cols.nonEmpty) tooltip.append("\nColumns: ").append(cols)
    l.setTooltip(new Tooltip(tooltip.toString))
    l.setOnMouseClicked(_ => console.insertAtCaret(spec.name))
    l
  }
}
