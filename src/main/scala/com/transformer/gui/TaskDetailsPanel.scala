package com.transformer.gui

import com.transformer.job.TaskStatus

import javafx.geometry.Insets
import javafx.scene.control.{Label, ScrollPane, Separator, TextArea}
import javafx.scene.layout.{Priority, VBox}
import javafx.scene.text.{Font, FontWeight}

/** Right-side panel: shows full details for the currently-selected task.
  *
  * Sections (top → bottom):
  *   * Name + status pill
  *   * Dependencies (comma-separated viewNames)
  *   * Error / validation summary (only when relevant)
  *   * Source SQL (read-only monospace)
  *   * Rendered SQL (post-template, read-only monospace)
  */
final class TaskDetailsPanel(session: JobSession) extends VBox {

  setSpacing(8)
  setPadding(new Insets(12))
  setPrefWidth(420)
  setStyle("-fx-background-color: #2a2c38;")

  private val nameLabel = new Label("(no task selected)")
  nameLabel.setStyle("-fx-text-fill: #f7f7fb;")
  nameLabel.setFont(Font.font("Sans", FontWeight.BOLD, 16))
  nameLabel.setWrapText(true)

  private val statusLabel = new Label("")
  statusLabel.setStyle("-fx-text-fill: #c5cad8;")
  statusLabel.setWrapText(true)

  private val depsLabel = new Label("")
  depsLabel.setStyle("-fx-text-fill: #c5cad8; -fx-font-size: 11px;")
  depsLabel.setWrapText(true)

  private val errorLabel = new Label("")
  errorLabel.setStyle("-fx-text-fill: #f8a3a3; -fx-font-family: monospace;")
  errorLabel.setWrapText(true)
  private val errorScroll = new ScrollPane(errorLabel)
  errorScroll.setFitToWidth(true)
  errorScroll.setStyle("-fx-background-color: transparent;")
  errorScroll.setPrefHeight(80)
  errorScroll.setVisible(false)
  errorScroll.setManaged(false)

  private val sourceSqlArea = makeCodeArea("Source SQL (from main.sql)")
  private val renderedSqlArea = makeCodeArea("Rendered SQL (post-template)")

  VBox.setVgrow(sourceSqlArea, Priority.ALWAYS)
  VBox.setVgrow(renderedSqlArea, Priority.ALWAYS)

  getChildren.addAll(
    nameLabel,
    statusLabel,
    depsLabel,
    errorScroll,
    new Separator(),
    sectionLabel("Source SQL"),
    sourceSqlArea,
    sectionLabel("Rendered SQL"),
    renderedSqlArea
  )

  session.addListener(() => refresh())
  refresh()

  private def refresh(): Unit = {
    val sel = session.selectedTaskIndex
    val dag = session.dag

    (sel, dag) match {
      case (Some(i), Some(d)) if i >= 0 && i < d.nodes.size =>
        val node = d.nodes(i)
        val task = node.task
        nameLabel.setText(task.displayName)
        statusLabel.setText(formatStatus(i))
        depsLabel.setText(formatDeps(node))
        sourceSqlArea.setText(safeLoad(task.loadSql _))
        renderedSqlArea.setText(node.renderedMainSql)
        updateErrorPanel(i)
      case _ =>
        nameLabel.setText("(no task selected)")
        statusLabel.setText("Click a node to view its details. Double-click to load its output rows.")
        depsLabel.setText("")
        sourceSqlArea.clear()
        renderedSqlArea.clear()
        setErrorText("")
    }
  }

  private def formatStatus(i: Int): String = {
    val states = session.taskStates
    if (i >= states.size) return ""
    states(i) match {
      case UiTaskState.Pending => "Status: pending"
      case UiTaskState.Running => "Status: running…"
      case UiTaskState.Done(result) =>
        val base = result.status match {
          case TaskStatus.Succeeded => f"Status: succeeded • ${result.rowsProduced}%,d rows • ${result.durationMillis} ms"
          case TaskStatus.Failed(_) => s"Status: failed • ${result.durationMillis} ms"
          case TaskStatus.ValidationFailed(fs) => s"Status: validation failed (${fs.size}) • ${result.durationMillis} ms"
          case TaskStatus.Skipped(_) => "Status: skipped"
          case TaskStatus.Pending => "Status: pending"
        }
        val outPath = result.outputPath.map(p => s"\nOutput path: $p").getOrElse("")
        base + outPath
    }
  }

  private def formatDeps(node: com.transformer.job.TaskDagNode): String = {
    val depNames = session.dag match {
      case Some(d) => node.deps.toSeq.sorted.map(i => d.nodes(i).task.displayName)
      case None => Nil
    }
    if (depNames.isEmpty) "Dependencies: (none)"
    else s"Dependencies: ${depNames.mkString(", ")}"
  }

  private def updateErrorPanel(i: Int): Unit = {
    val states = session.taskStates
    if (i >= states.size) return setErrorText("")
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
    l.setStyle("-fx-text-fill: #c5cad8; -fx-font-size: 11px;")
    l
  }

  private def makeCodeArea(prompt: String): TextArea = {
    val a = new TextArea()
    a.setEditable(false)
    a.setWrapText(false)
    a.setPromptText(prompt)
    a.setFont(Font.font("Monospaced", 12))
    a.setStyle("-fx-control-inner-background: #1e1e2a; -fx-text-fill: #d4d4d4;")
    a
  }

  private def indent(s: String, prefix: String): String =
    s.split('\n').iterator.map(prefix + _).mkString("\n")
}
