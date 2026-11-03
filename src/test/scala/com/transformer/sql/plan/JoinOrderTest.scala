package com.transformer.sql.plan

import com.transformer.core._
import com.transformer.fuzz.{DataGen, RowOracle}
import com.transformer.fuzz.oracle.RelEngine
import com.transformer.sql.exec.{CteResolver, PhysicalPlanner}
import org.junit.Assert._
import org.junit.Test

/** [[JoinOrder]] brings in, where the next written table has no equality to
  * the tables joined so far, the first later table that has one; keeps every
  * other order as written; and changes no answer. */
class JoinOrderTest {

  private def table(rows: Int, f: Int => Array[Any]): MaterializedView =
    DataGen.Dataset(Schema(Vector(Field("k", DataType.IntType), Field("j", DataType.IntType), Field("v", DataType.StringType))),
      (0 until rows).map(f)).evenPartitions(2, 64)

  private val catalog: Catalog = {
    val c = new Catalog
    c.register("a", table(12, i => Array[Any](i % 5, i % 3, s"a$i")))
    c.register("b", table(9, i => Array[Any](i % 4, i % 3, s"b$i")))
    c.register("c", table(10, i => Array[Any](i % 5, if (i == 7) null else i % 3, s"c$i")))
    c.register("d", table(6, i => Array[Any](i % 2, i, s"d$i")))
    c
  }

  /** The tables of the inner joins under `plan`, in the order they join. */
  private def order(plan: LogicalPlan): Seq[String] = plan match {
    case LogicalScan(name, _, alias) => Seq(alias.getOrElse(name))
    case LogicalJoin(l, r, _, _) => order(l) ++ order(r)
    case other => other.children.flatMap(order)
  }

  private def optimized(sql: String): LogicalPlan =
    LogicalOptimizer.optimize(CteResolver.resolve(LogicalBuilder.build(sql, catalog), ExecutionOptions.Default))

  private def assertSameAnswer(sql: String): Unit = {
    val expected = RelEngine.runRows(catalog, sql, ExecutionOptions.Default)
    val physical = PhysicalPlanner.plan(CteResolver.resolve(LogicalBuilder.build(sql, catalog), ExecutionOptions.Default),
      ExecutionOptions.Default)
    val q = new ExecutedQuery(physical.outputSchema, (0 until physical.numPartitions).map(physical.execute))
    RowOracle.multisetEquals(q.schema, expected, RelEngine.collectRows(q)).foreach(d => fail(s"$sql: $d"))
  }

  @Test def aTableWithNoKeyWaitsForOneWithOne(): Unit = {
    val sql = "SELECT a.v, b.v, c.v FROM a, b, c WHERE a.k = c.k AND b.j = c.j"
    val plan = optimized(sql)
    assertEquals(Seq("a", "c", "b"), order(plan))
    // Both joins hash: each has an equality between its sides.
    def joins(p: LogicalPlan): Seq[LogicalJoin] = p match {
      case j: LogicalJoin => j +: p.children.flatMap(joins)
      case other => other.children.flatMap(joins)
    }
    assertTrue(joins(plan).forall(_.condition != LitExpr(true, DataType.BooleanType)))
    assertEquals(Seq("v", "v", "v"), plan.outputSchema.fieldNames)
    assertSameAnswer(sql)
  }

  @Test def anOrderThatHashesEveryJoinItCanIsKept(): Unit = {
    assertEquals(Seq("a", "c", "b"), order(optimized("SELECT * FROM a, c, b WHERE a.k = c.k AND b.j = c.j")))
    assertEquals(Seq("a", "b", "c"), order(optimized("SELECT * FROM a JOIN b ON a.j = b.j JOIN c ON c.k = a.k")))
    // No table has an equality with the first; the order is as written.
    assertEquals(Seq("a", "b", "c"), order(optimized("SELECT * FROM a, b, c WHERE b.k = c.k")))
    // No equality at all: a cross join stays one.
    assertEquals(Seq("a", "b"), order(optimized("SELECT * FROM a, b WHERE a.k < b.k")))
  }

  @Test def theColumnsKeepTheirWrittenOrder(): Unit = {
    val sql = "SELECT * FROM a, b, c WHERE a.k = c.k AND b.j = c.j"
    val plan = optimized(sql)
    assertEquals(LogicalBuilder.build(sql, catalog).main.outputSchema, plan.outputSchema)
    assertSameAnswer(sql)
    assertSameAnswer("SELECT c.v, a.k, b.v FROM a, b, c, d WHERE a.k = c.k AND b.j = c.j AND d.k = b.k % 2 ORDER BY 1")
  }

  /** An outer join joins nothing across it: each side's runs are ordered on
    * their own. */
  @Test def anOuterJoinIsABarrier(): Unit = {
    val sql = "SELECT a.v, b.v, c.v, d.v FROM a, b, c LEFT JOIN d ON d.k = c.k WHERE a.k = c.k AND b.j = c.j"
    // The LEFT JOIN's left side is the run a, b, c, which reorders under it.
    assertEquals(Seq("a", "c", "b", "d"), order(optimized(sql)))
    assertSameAnswer(sql)
    assertSameAnswer("SELECT a.v, d.v FROM a LEFT JOIN b ON a.k = b.k, c, d WHERE c.j = d.j AND d.k = a.k")
  }

  /** A run of inner joins nested on the right as well as the left keeps its
    * answer: each join's condition reads its tables where the run puts them.
    * The binder builds no such tree (it refuses a parenthesized join, and a
    * CTE's join sits under its projection), but the pass takes any run of
    * inner joins, as a plan built in code may hold. Here `c` has the key to
    * `a`, so the run reorders to a, c, b. */
  @Test def aJoinNestedOnTheRightKeepsItsAnswer(): Unit = {
    def scan(n: String) = LogicalScan(n, catalog.get(n).get, Some(n))
    def col(i: Int, n: String) = ColRefExpr(i, n, DataType.IntType)
    def eq(l: Expr, r: Expr) = BinOpExpr("=", l, r, DataType.BooleanType)
    // a JOIN (b JOIN c ON b.k = c.k) ON a.j = c.j, each table's columns k, j, v.
    val bc = LogicalJoin(scan("b"), scan("c"), eq(col(0, "k"), col(3, "k")), JoinKind.Inner)
    val plan = LogicalJoin(scan("a"), bc, eq(col(1, "j"), col(7, "j")), JoinKind.Inner)
    val reordered = LogicalOptimizer.joinOrder(plan)
    assertEquals(Seq("a", "c", "b"), order(reordered))
    def rows(p: LogicalPlan): Vector[Array[Any]] = {
      val physical = PhysicalPlanner.plan(p, ExecutionOptions.Default)
      RelEngine.collectRows(new ExecutedQuery(physical.outputSchema, (0 until physical.numPartitions).map(physical.execute)))
    }
    RowOracle.multisetEquals(plan.outputSchema, rows(plan), rows(reordered)).foreach(d => fail(d))
  }

  /** A condition that reads RAND() would be drawn for other rows in another
    * order, so its run stays as written. */
  @Test def aRunWhoseConditionsReadRandStaysAsWritten(): Unit =
    assertEquals(Seq("a", "b", "c"),
      order(optimized("SELECT * FROM a, b, c WHERE a.k = c.k AND b.j = c.j AND a.j + RAND() > b.j")))

  /** Tables of 6000 rows each, past the nested-loop guard's 5000: written
    * with the unkeyed pair first the query was refused; ordered, both joins
    * hash and it runs. */
  @Test def aQueryTheGuardRefusedInItsWrittenOrderRuns(): Unit = {
    val big = new Catalog
    Seq("x", "y", "z").foreach(n => big.register(n, table(6000, i => Array[Any](i, i % 7, s"$n$i"))))
    val q = RelEngine.runRows(big, "SELECT COUNT(*) FROM x, y, z WHERE x.k = z.k AND y.k = z.k", ExecutionOptions.Default)
    assertEquals(6000L, q.head(0))
  }
}
