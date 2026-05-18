package com.transformer.gui

import com.transformer.job.{InputFilePath, SQLTask, TaskDag}
import com.transformer.temporal.TemporalVariables

import org.junit.Assert._
import org.junit.Test

import java.time.Instant

class DagLayoutTest {

  private val vars = TemporalVariables(Instant.parse("2026-01-01T00:00:00Z"))

  private def task(sql: String, viewName: String): SQLTask =
    SQLTask(sqlString = Some(sql), viewName = Some(viewName), name = Some(viewName))

  private def input(name: String, path: String = "/tmp/x.csv"): InputFilePath =
    InputFilePath(path = path, viewName = name)

  @Test def emptyEverything(): Unit = {
    val dag = TaskDag.build(Seq.empty, Set.empty, vars)
    val layout = DagLayout.compute(dag, IndexedSeq.empty)
    assertTrue(layout.inputBoxes.isEmpty)
    assertTrue(layout.taskBoxes.isEmpty)
    assertEquals(0.0, layout.width, 0.0001)
    assertEquals(0.0, layout.height, 0.0001)
  }

  @Test def tasksOnlyMatchesLegacyCompute(): Unit = {
    val a = task("SELECT * FROM events", "a")
    val b = task("SELECT * FROM a", "b")
    val dag = TaskDag.build(Seq(a, b), Set("events"), vars)
    val layout = DagLayout.compute(dag)
    assertTrue(layout.inputBoxes.isEmpty)
    assertEquals(2, layout.taskBoxes.size)
    // Task `a` is layer 0, `b` is layer 1, so they have different x.
    assertNotEquals(layout.taskBoxes(0).x, layout.taskBoxes(1).x)
    assertEquals(NodeKind.Task, layout.taskBoxes(0).kind)
  }

  @Test def inputsAreLaidOutAsLayerZero(): Unit = {
    val a = task("SELECT * FROM raw_a", "a")
    val b = task("SELECT * FROM a JOIN raw_b ON a.k = raw_b.k", "b")
    val inputs = IndexedSeq(input("raw_a"), input("raw_b"))
    val dag = TaskDag.build(Seq(a, b), Set("raw_a", "raw_b"), vars)
    val layout = DagLayout.compute(dag, inputs)
    assertEquals(2, layout.inputBoxes.size)
    assertEquals(2, layout.taskBoxes.size)
    val inputX = layout.inputBoxes(0).x
    val taskAX = layout.taskBoxes(0).x
    val taskBX = layout.taskBoxes(1).x
    // Inputs (layer 0) sit left of any task. b depends on a, so b is right of a.
    assertTrue(s"input.x=$inputX task_a.x=$taskAX", inputX < taskAX)
    assertTrue(s"task_a.x=$taskAX task_b.x=$taskBX", taskAX < taskBX)
    // Sanity: inputs are tagged correctly.
    assertEquals(NodeKind.Input, layout.inputBoxes(0).kind)
    assertEquals(NodeKind.Task,  layout.taskBoxes(0).kind)
  }

  @Test def inputNameLookupIsLowercased(): Unit = {
    val a = task("SELECT * FROM events", "a")
    val inputs = IndexedSeq(input("Events"))
    val dag = TaskDag.build(Seq(a), Set("events"), vars)
    val layout = DagLayout.compute(dag, inputs)
    // The lookup must succeed using the lowercased name we'd get from TaskDag.inputDeps.
    assertTrue(layout.boxForInputName("events").isDefined)
    assertTrue(layout.boxForInputName("EVENTS").isEmpty)
  }

  @Test def taskDagNodeRecordsInputDeps(): Unit = {
    val a = task("SELECT * FROM raw_a", "a")
    val b = task("SELECT * FROM a", "b")
    val dag = TaskDag.build(Seq(a, b), Set("raw_a"), vars)
    assertEquals(Set("raw_a"), dag.nodes(0).inputDeps)
    assertEquals(Set.empty[String], dag.nodes(1).inputDeps)
  }

  @Test def inputDepsCoverValidationSql(): Unit = {
    val v = com.transformer.job.Validation(
      name = "check",
      sqlString = Some("SELECT * FROM raw_b WHERE x IS NULL")
    )
    val a = SQLTask(
      sqlString = Some("SELECT * FROM raw_a"),
      viewName = Some("a"),
      validations = Seq(v)
    )
    val dag = TaskDag.build(Seq(a), Set("raw_a", "raw_b"), vars)
    assertEquals(Set("raw_a", "raw_b"), dag.nodes(0).inputDeps)
  }
}
