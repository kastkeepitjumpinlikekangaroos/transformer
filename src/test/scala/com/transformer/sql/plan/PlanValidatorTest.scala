package com.transformer.sql.plan

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** [[PlanValidator]] refuses each kind of malformed plan it checks for, and
  * passes the plans the binder and the optimizer make. */
class PlanValidatorTest {

  private val t = LogicalScan("t", new MaterializedView(Schema(Vector(
    Field("a", DataType.IntType), Field("s", DataType.StringType), Field("f", DataType.BooleanType))),
    IndexedSeq.empty), Some("t"))

  private def a: ColRefExpr = ColRefExpr(0, "a", DataType.IntType)
  private def s: ColRefExpr = ColRefExpr(1, "s", DataType.StringType)
  private def f: ColRefExpr = ColRefExpr(2, "f", DataType.BooleanType)
  private def int(v: Int): LitExpr = LitExpr(v, DataType.IntType)
  private def eq(l: Expr, r: Expr): Expr = BinOpExpr("=", l, r, DataType.BooleanType)

  private def refusal(plan: LogicalPlan): String =
    try {
      PlanValidator.check(plan, "under test", ctesPending = false)
      fail(s"expected the plan to be refused: $plan")
      ""
    } catch { case e: IllegalStateException => e.getMessage }

  private def assertRefused(plan: LogicalPlan, words: String*): Unit = {
    val message = refusal(plan)
    words.foreach(w => assertTrue(s"[$message] should say [$w]", message.contains(w)))
  }

  @Test def aColumnPastTheInputIsRefused(): Unit =
    assertRefused(LogicalFilter(t, eq(ColRefExpr(5, "missing", DataType.IntType), int(1))),
      "under test", "Filter.predicate", "column 5 ('missing') is past the 3 columns")

  @Test def aColumnReadAsAnotherTypeIsRefused(): Unit =
    assertRefused(LogicalProject(t, Seq((ColRefExpr(1, "s", DataType.IntType), "s"))),
      "Project.projection 's'", "read as int", "holds a string")

  /** A window's keys and arguments are checked too, which the projection
    * pass's own check, before this one, skipped. */
  @Test def aWindowKeyPastTheInputIsRefused(): Unit = {
    val w = WindowDef(WindowSpec(Seq(ColRefExpr(7, "p", DataType.IntType)), Nil, WindowFrame.defaultFor(false)),
      WindowFnRowNumber(), "_win0")
    assertRefused(LogicalWindow(t, Seq(w)), "Window.window '_win0' PARTITION BY", "column 7")
  }

  @Test def aJoinConditionReadsBothSides(): Unit = {
    val ok = LogicalJoin(t, t, eq(a, ColRefExpr(3, "a", DataType.IntType)), JoinKind.Inner)
    PlanValidator.check(ok, "under test", ctesPending = false)
    assertRefused(LogicalJoin(t, t, eq(a, ColRefExpr(6, "a", DataType.IntType)), JoinKind.Inner),
      "Join.condition", "past the 6 columns")
  }

  @Test def aConditionThatIsNoBooleanIsRefused(): Unit = {
    assertRefused(LogicalFilter(t, a), "Filter.predicate", "a condition is a int")
    assertRefused(LogicalFilter(t, BinOpExpr("AND", f, s, DataType.BooleanType)), "a condition is a string")
    assertRefused(LogicalProject(t, Seq((CaseExpr(Seq((a, s)), None, DataType.StringType), "c"))),
      "a condition is a int")
  }

  @Test def aComparisonOfTwoTypesIsRefused(): Unit =
    assertRefused(LogicalFilter(t, BinOpExpr("<", a, LitExpr(1L, DataType.LongType), DataType.BooleanType)),
      "< meets operands of types int, long")

  @Test def unionArmsOfOtherTypesAreRefused(): Unit =
    assertRefused(LogicalUnion(LogicalProject(t, Seq((a, "x"))), LogicalProject(t, Seq((s, "x"))), all = true),
      "Union", "the arms return (x int) and (x string)")

  @Test def aCteScanIsPendingOnlyBeforeResolution(): Unit = {
    val pending = LogicalScan("c", new PendingCteView("c", t.outputSchema), Some("c"))
    PlanValidator.check(pending, "as bound", ctesPending = true)
    assertRefused(pending, "the scan of CTE 'c' was never resolved")
  }

  @Test def aPassThatChangesTheOutputIsRefused(): Unit = {
    val before = LogicalProject(t, Seq((a, "a")))
    val after = LogicalProject(t, Seq((s, "a")))
    val message = try { PlanValidator.afterPass("SomePass", before, after); "" }
      catch { case e: IllegalStateException => e.getMessage }
    assertTrue(message, message.contains("after SomePass") && message.contains("changed from (a int) to (a string)"))
  }

  /** The node a problem is at is named by its path from the root. */
  @Test def theProblemIsNamedByItsPath(): Unit =
    assertRefused(LogicalLimit(LogicalSort(LogicalProject(t, Seq((ColRefExpr(9, "z", DataType.IntType), "z"))),
      Nil), 3), "Limit > Sort > Project.projection 'z'")

  /** The binder's plans pass, through every stage, which every other test of
    * the engine relies on; here a few shapes that read each check. */
  @Test def boundAndOptimizedPlansPass(): Unit = {
    val catalog = new Catalog
    catalog.register("t", t.view)
    Seq(
      "SELECT a, s FROM t WHERE f AND a > 1 ORDER BY s",
      "SELECT a, COUNT(*) AS n FROM t GROUP BY a HAVING COUNT(*) > 1",
      "SELECT a, ROW_NUMBER() OVER (PARTITION BY s ORDER BY a) AS r FROM t",
      "SELECT x.a FROM t x JOIN t y ON x.a = y.a WHERE y.f",
      "WITH c AS (SELECT a FROM t) SELECT * FROM c UNION ALL SELECT a FROM c",
      "SELECT CASE WHEN f THEN s END AS c, a IN (1, 2) AS i FROM t"
    ).foreach { sql =>
      val built = LogicalBuilder.build(sql, catalog)
      val main = built.ctes.foldLeft(built.main)((plan, cte) => substitute(plan, cte.pending, cte.body))
      LogicalOptimizer.optimize(PlanValidator.resolved(built.main, main))
    }
  }

  private def substitute(plan: LogicalPlan, pending: PendingCteView, body: LogicalPlan): LogicalPlan = plan match {
    case LogicalScan(_, view, _) if view eq pending => body
    case other => other.withChildren(other.children.map(substitute(_, pending, body)))
  }
}
