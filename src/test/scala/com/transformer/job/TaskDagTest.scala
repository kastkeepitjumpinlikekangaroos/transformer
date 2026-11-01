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

  /** A view only a validation reads is a dependency (the validation waits for
    * it) but not where the task's rows come from: `mainDeps` and
    * `mainInputDeps` hold what the main SQL reads, the lineage a run records. */
  @Test def mainDepsLeaveOutWhatOnlyValidationsRead(): Unit = {
    val a = task("SELECT * FROM events", viewName = "a")
    val b = task("SELECT * FROM a", viewName = "b",
      validations = Seq(Validation("v", sqlString = Some("SELECT * FROM users u JOIN c ON u.id = c.id"))))
    val c = task("SELECT * FROM events", viewName = "c")
    val dag = TaskDag.build(Seq(a, b, c), Set("events", "users"), vars)
    val nb = dag.nodes(1)
    assertEquals((Set(0, 2), Set("users")), (nb.deps, nb.inputDeps))
    assertEquals((Set(0), Set.empty[String]), (nb.mainDeps, nb.mainInputDeps))
    assertEquals((Set.empty[Int], Set("events")), (dag.nodes(0).mainDeps, dag.nodes(0).mainInputDeps))
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

  /** A task's SQL is one statement. The parser stopped at the first `;`, so
    * the DAG was built from that statement alone: a second one's tables were
    * neither a dependency nor checked, and the task ran the first only. */
  @Test def aSecondStatementFailsTheDag(): Unit = {
    val a = task("SELECT * FROM events; SELECT * FROM users", viewName = "result")
    val msg = assertIae { TaskDag.build(Seq(a), Set("events"), vars) }
    assertEquals("Only one statement is supported; remove the text after ';'", msg)
    // A trailing `;` ends the one statement.
    val dag = TaskDag.build(Seq(task("SELECT * FROM events;", viewName = "result")), Set("events"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
  }

  /** A task's references are the names the binder resolves: without their
    * quotes, regardless of case, and none of them a CTE the SQL defines.
    * `"events"` was a view named with its quotes, and a CTE written `Q` or
    * `"q"` but read as `q` was a table, so each failed the DAG as an unknown
    * view although the task binds. */
  @Test def referencesAreTheNamesTheBinderResolves(): Unit = {
    val a = task("SELECT * FROM \"Events\" JOIN `users` ON 1 = 1", viewName = "a")
    val b = task("WITH Q AS (SELECT * FROM a) SELECT * FROM q", viewName = "b")
    val c = task("WITH \"q\" AS (SELECT * FROM \"B\") SELECT * FROM q UNION ALL (WITH r AS (SELECT * FROM a) " +
      "SELECT * FROM \"R\")", viewName = "c")
    val dag = TaskDag.build(Seq(a, b, c), Set("events", "users"), vars)
    assertEquals(Set("events", "users"), dag.nodes(0).inputDeps)
    assertEquals(Set(0), dag.nodes(1).deps)
    assertEquals(Set(0, 1), dag.nodes(2).deps)
    // A name the SQL defines nowhere is still unknown.
    val unknown = assertIae { TaskDag.build(Seq(task("WITH q AS (SELECT * FROM events) SELECT * FROM \"p\"",
      viewName = "d")), Set("events"), vars) }
    assertTrue(unknown, unknown.startsWith("Task 'd' references unknown view 'p'."))
  }

  /** A task whose SQL nests past the limit reads no tables here, so the DAG
    * still builds and the rest of the job runs; its own run refuses it (see
    * DataJobTest). Finding its tables recursed through the whole expression,
    * which overflowed on one deep enough. */
  @Test def aTaskTooDeepToReadBuildsTheDag(): Unit = {
    val deep = task("SELECT k" + " + k" * 2999 + " AS v FROM events", viewName = "deep")
    val after = task("SELECT * FROM deep", viewName = "after")
    val dag = TaskDag.build(Seq(deep, after), Set("events"), vars)
    assertEquals(Set.empty[String], dag.nodes(0).inputDeps)
    assertEquals(Set(0), dag.nodes(1).deps)
  }

  @Test def templateRenderedBeforeExtraction(): Unit = {
    val a = task("SELECT * FROM events_{{ today }}", viewName = "result")
    val dag = TaskDag.build(Seq(a), Set("events_20260101"), vars)
    assertEquals(Set.empty[Int], dag.nodes(0).deps)
    assertTrue(dag.nodes(0).renderedMainSql.contains("events_20260101"))
    assertFalse(dag.nodes(0).renderedMainSql.contains("{{"))
  }
}
