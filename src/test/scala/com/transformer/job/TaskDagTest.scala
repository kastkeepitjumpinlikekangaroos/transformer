package com.transformer.job

import com.transformer.temporal.TemporalVariables
import org.junit.Assert._
import org.junit.Test

import java.time.Instant

class TaskDagTest {

  private val vars = TemporalVariables(Instant.parse("2026-01-01T00:00:00Z"))

  private def task(
      sql: String,
      viewName: String = null,
      validations: Seq[Validation] = Nil,
      name: String = null,
      outputPath: String = null
  ): SQLTask = SQLTask(
    sqlString = Some(sql),
    viewName = Option(viewName),
    validations = validations,
    name = Option(name),
    outputFile = Option(outputPath).map(p => OutputFilePath(p))
  )

  private def assertIae(body: => Any): String = {
    try {
      body
      throw new AssertionError("Expected IllegalArgumentException; call succeeded")
    } catch {
      case e: IllegalArgumentException => e.getMessage
    }
  }

  @Test def tablesNamesFinderSmokeTest(): Unit = {
    val t = task(
      "SELECT a.x FROM Events a JOIN USERS u ON a.uid = u.id",
      viewName = "result"
    )
    val dag = TaskDag.build(Seq(t), Set("events", "users"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
  }

  @Test def independentRoots(): Unit = {
    val a = task("SELECT * FROM events", viewName = "ea")
    val b = task("SELECT * FROM users", viewName = "ub")
    val dag = TaskDag.build(Seq(a, b), Set("events", "users"), vars)
    assertTrue(dag.nodes(0).deps.isEmpty)
    assertTrue(dag.nodes(1).deps.isEmpty)
    assertEquals(Set.empty[Int], dag.dependents(0))
    assertEquals(Set.empty[Int], dag.dependents(1))
  }

  @Test def linearChain(): Unit = {
    val a = task("SELECT * FROM events", viewName = "a")
    val b = task("SELECT * FROM a", viewName = "b")
    val c = task("SELECT * FROM b", viewName = "c")
    val dag = TaskDag.build(Seq(a, b, c), Set("events"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
    assertEquals(Set(0), dag.nodes(1).deps)
    assertEquals(Set(1), dag.nodes(2).deps)
    assertEquals(Set(1), dag.dependents(0))
    assertEquals(Set(2), dag.dependents(1))
    assertEquals(Set.empty[Int], dag.dependents(2))
  }

  @Test def diamond(): Unit = {
    val a = task("SELECT * FROM events", viewName = "a")
    val b = task("SELECT * FROM a", viewName = "b")
    val c = task("SELECT * FROM a", viewName = "c")
    val d = task(
      "SELECT b.x, c.y FROM b JOIN c ON b.k = c.k",
      viewName = "d"
    )
    val dag = TaskDag.build(Seq(a, b, c, d), Set("events"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
    assertEquals(Set(0), dag.nodes(1).deps)
    assertEquals(Set(0), dag.nodes(2).deps)
    assertEquals(Set(1, 2), dag.nodes(3).deps)
    assertEquals(Set(1, 2), dag.dependents(0))
    assertEquals(Set(3), dag.dependents(1))
    assertEquals(Set(3), dag.dependents(2))
  }

  @Test def cycleDetected(): Unit = {
    val a = task("SELECT * FROM b", viewName = "a", name = "a_task")
    val b = task("SELECT * FROM a", viewName = "b", name = "b_task")
    val msg = assertIae { TaskDag.build(Seq(a, b), Set.empty, vars) }
    assertTrue(msg, msg.contains("Cycle"))
    assertTrue(msg, msg.contains("a_task"))
    assertTrue(msg, msg.contains("b_task"))
  }

  @Test def unknownReferenceThrows(): Unit = {
    val a = task("SELECT * FROM ghost", viewName = "a", name = "a_task")
    val msg = assertIae { TaskDag.build(Seq(a), Set("events"), vars) }
    assertTrue(msg, msg.contains("ghost"))
    assertTrue(msg, msg.contains("a_task"))
  }

  @Test def duplicateViewNameThrows(): Unit = {
    val a = task("SELECT * FROM events", viewName = "x")
    val b = task("SELECT * FROM events", viewName = "x")
    val msg = assertIae { TaskDag.build(Seq(a, b), Set("events"), vars) }
    assertTrue(msg, msg.toLowerCase.contains("duplicate"))
  }

  @Test def viewNameCollidesWithInputThrows(): Unit = {
    val a = task("SELECT * FROM events", viewName = "events")
    val msg = assertIae { TaskDag.build(Seq(a), Set("events"), vars) }
    assertTrue(msg, msg.contains("collides"))
  }

  @Test def mainSqlSelfReferenceThrows(): Unit = {
    val a = task("SELECT * FROM a", viewName = "a", name = "a_task")
    val msg = assertIae { TaskDag.build(Seq(a), Set("events"), vars) }
    assertTrue(msg, msg.toLowerCase.contains("self-cycle"))
  }

  @Test def validationCanReferenceSelfWithoutCycle(): Unit = {
    val a = task(
      "SELECT * FROM events",
      viewName = "a",
      validations = Seq(Validation(
        name = "v",
        sqlString = Some("SELECT * FROM a WHERE x IS NULL")
      ))
    )
    val dag = TaskDag.build(Seq(a), Set("events"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
  }

  @Test def validationReferencingPeerCreatesDep(): Unit = {
    val a = task("SELECT * FROM events", viewName = "a")
    val b = task(
      "SELECT * FROM events",
      viewName = "b",
      validations = Seq(Validation(
        name = "v",
        sqlString = Some("SELECT b.* FROM b JOIN a ON b.k = a.k")
      ))
    )
    val dag = TaskDag.build(Seq(a, b), Set("events"), vars)
    assertEquals(Set(0), dag.nodes(1).deps)
    assertEquals(Set(1), dag.dependents(0))
  }

  @Test def duplicateOutputPathThrows(): Unit = {
    val a = task("SELECT * FROM events", viewName = "a", outputPath = "/tmp/x.csv")
    val b = task("SELECT * FROM events", viewName = "b", outputPath = "/tmp/x.csv")
    val msg = assertIae { TaskDag.build(Seq(a, b), Set("events"), vars) }
    assertTrue(msg, msg.contains("/tmp/x.csv"))
  }

  @Test def emptyTasksReturnsEmptyDag(): Unit = {
    val dag = TaskDag.build(Seq.empty, Set.empty, vars)
    assertEquals(0, dag.nodes.size)
    assertEquals(0, dag.dependents.size)
  }

  @Test def templateRenderedBeforeExtraction(): Unit = {
    val a = task("SELECT * FROM events_{{ today }}", viewName = "result")
    val dag = TaskDag.build(Seq(a), Set("events_20260101"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
    assertTrue(dag.nodes(0).renderedMainSql.contains("events_20260101"))
    assertFalse(dag.nodes(0).renderedMainSql.contains("{{"))
  }
}
