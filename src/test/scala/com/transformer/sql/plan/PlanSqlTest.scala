package com.transformer.sql.plan

import com.transformer.core._
import com.transformer.fuzz.{DataGen, RowOracle}
import com.transformer.fuzz.oracle.{PlanRoundTrip, RelEngine}
import com.transformer.sql.exec.{CteResolver, PhysicalPlanner}
import org.junit.Assert._
import org.junit.Test

import java.time.{Instant, LocalDate}

/** [[PlanSql]] prints each construct the binder binds as SQL that binds back
  * to the same plan ([[PlanRoundTrip.check]]) and answers as the query does;
  * the optimized plan, printed and run with the optimizer off, answers so too.
  * The fuzz property (`plan_roundtrip_fuzz_test`) and the slt runner's
  * `reprinted` mode hold the same over generated queries and every script. */
class PlanSqlTest {

  private def dec(s: String) = new java.math.BigDecimal(s)
  private def ts(s: String) = Instant.parse(s)

  private val catalog: Catalog = {
    val c = new Catalog
    val t = Schema(Vector(
      Field("a", DataType.IntType), Field("b", DataType.LongType), Field("d", DataType.DoubleType),
      Field("s", DataType.StringType), Field("f", DataType.BooleanType), Field("dt", DataType.DateType),
      Field("ts", DataType.TimestampType), Field("m", DataType.DecimalType(10, 2))))
    c.register("t", DataGen.Dataset(t, IndexedSeq(
      Array[Any](1, 10L, 1.5, "x", true, LocalDate.parse("2024-01-01"), ts("2024-01-01T00:00:00Z"), dec("1.50")),
      Array[Any](2, 20L, -2.5, "it's", false, LocalDate.parse("2023-06-30"), ts("2023-06-30T12:00:00.5Z"), dec("-3.25")),
      Array[Any](-3, null, null, null, null, null, null, null),
      Array[Any](2, 30L, 0.0, "a%b", true, LocalDate.parse("2024-02-29"), ts("2024-02-29T23:59:59Z"), dec("100.00")),
      Array[Any](null, 40L, 9.75, "xy", false, LocalDate.parse("1999-12-31"), ts("1999-12-31T23:59:59.999999Z"), dec("0.01"))
    )).evenPartitions(2, 2))
    val u = Schema(Vector(Field("a", DataType.IntType), Field("x", DataType.StringType)))
    c.register("u", DataGen.Dataset(u, IndexedSeq(
      Array[Any](1, "one"), Array[Any](2, "two"), Array[Any](2, "deux"), Array[Any](7, null))).singlePartition())
    val v = Schema(Vector(Field("k", DataType.IntType), Field("select", DataType.StringType), Field("x y", DataType.IntType)))
    c.register("v", DataGen.Dataset(v, IndexedSeq(Array[Any](1, "s", 2), Array[Any](2, null, 3))).singlePartition())
    c
  }

  private val queries: Seq[String] = Seq(
    "SELECT a, s FROM t",
    "SELECT * FROM t",
    "SELECT a + 1 AS x, -a AS n, a * (b + 1) AS p, (a - 1) - 2 AS l, a - (1 - 2) AS r, -(-a) AS nn FROM t",
    "SELECT a FROM t WHERE a > 1 AND s LIKE 'x%' AND f OR d < 2.5",
    "SELECT 1 AS i, -1 AS ni, 2147483648 AS big, -2147483648 AS ib, -2147483649 AS nb, CAST(5 AS BIGINT) AS c5, " +
      "1.5 AS d, -1.5 AS nd, 1e300 AS e, CAST('NaN' AS DOUBLE) AS nan, CAST('-Infinity' AS DOUBLE) AS ninf, " +
      "CAST(1.5 AS FLOAT) AS fl, 'it''s' AS q, TRUE AS tr, NULL AS nl, DATE '2024-02-29' AS dd, " +
      "TIMESTAMP '2024-01-01 12:34:56.123456' AS tt, -9223372036854775808 AS minb, 12345678901234567890123 AS hugeint " +
      "FROM t",
    "SELECT m * 1.05 AS p, m + 5 AS q, m = 3 AS e, COALESCE(m, 0) AS c, m IN (1, 2.5) AS i, " +
      "CASE WHEN f THEN m ELSE 0 END AS w, m - 0.001 AS r, m * -2 AS n FROM t",
    "SELECT CAST(a AS DOUBLE) AS x, CAST(s AS VARCHAR) AS y, CAST(ts AS DATE) AS z, a + b AS w, d > a AS v, a < '10' AS u FROM t",
    // A written CAST to DECIMAL prints as itself, beside the binder's own,
    // which prints as its operand; a string literal under one stays text.
    "SELECT CAST(m AS DECIMAL(4,1)) AS x, CAST(a AS NUMERIC(12,2)) * m AS y, CAST('-1.5e2' AS DECIMAL(5,1)) AS z, " +
      "CAST(1.0000000000000000001 AS DECIMAL(20,19)) AS w, CAST(NULL AS DECIMAL(3,0)) AS n, " +
      "CAST(d AS DECIMAL(10,4)) = m AS e, CAST(f AS DECIMAL(1,0)) + 1.5 AS b FROM t",
    "SELECT ABS(a) AS a1, ROUND(d, 2) AS r, COALESCE(a, 0) AS c, IF(f, a, 0) AS i, NULLIF(a, 1) AS n, UPPER(s) AS u, " +
      "TRIM(s) AS tr, SUBSTRING(s, 1, 2) AS sb, CONCAT(s, 'x') AS cc, s || 'x' || s AS cat, LENGTH(s) AS l, " +
      "GREATEST(a, 1) AS g, LEAST(b, 5) AS le FROM t",
    "SELECT CASE WHEN a > 1 THEN 'x' WHEN a < 0 THEN 'y' ELSE 'z' END AS c1, CASE a WHEN 1 THEN 'one' END AS c2 FROM t",
    "SELECT a IN (1, 2) AS i, a NOT IN (3) AS ni, a BETWEEN 1 AND 3 AS bt, s IS NULL AS sn, s IS NOT NULL AS snn, " +
      "s LIKE 'a%' AS l1, s NOT ILIKE 'X_' ESCAPE '!' AS l2, s LIKE 'a\\%b' AS l3, s LIKE 'x' ESCAPE '' AS l4 FROM t",
    "SELECT s, COUNT(*) AS n, COUNT(DISTINCT a) AS da, SUM(a) AS sa, AVG(d) AS ad, MIN(s) AS mn, MAX(dt) AS mx, " +
      "COUNT_IF(f) AS ci, STDDEV_SAMP(d) AS sd, VAR_POP(d) AS vp, COVAR_SAMP(d, a) AS cv, CORR(d, a) AS co, " +
      "SUM(DISTINCT a) AS sda, AVG(DISTINCT m) AS adm FROM t GROUP BY s HAVING COUNT(*) > 0 ORDER BY 2 DESC, 1",
    "SELECT a % 2 AS k, COUNT(*) AS n FROM t GROUP BY a % 2",
    "SELECT UPPER(s) || '!' AS k FROM t GROUP BY UPPER(s)",
    "SELECT 1 AS one FROM t GROUP BY ()",
    "SELECT COUNT(*) AS n FROM t",
    "SELECT a, ROW_NUMBER() OVER (PARTITION BY s ORDER BY a DESC NULLS LAST) AS rn, " +
      "SUM(a) OVER (ORDER BY a ROWS BETWEEN 2 PRECEDING AND CURRENT ROW) AS rs, " +
      "AVG(d) OVER (ORDER BY a RANGE BETWEEN 1.5 PRECEDING AND 2 FOLLOWING) AS ra, " +
      "LAG(a, 2, 0) OVER (ORDER BY a) AS lg, LEAD(s) OVER (ORDER BY a) AS ld, RANK() OVER (ORDER BY a) AS rk, " +
      "DENSE_RANK() OVER (ORDER BY b NULLS FIRST) AS dr, COUNT(*) OVER () AS c, " +
      "MAX(a) OVER (PARTITION BY f ORDER BY a ROWS BETWEEN UNBOUNDED PRECEDING AND UNBOUNDED FOLLOWING) AS mw FROM t",
    "SELECT s, SUM(COUNT(*)) OVER () AS total FROM t GROUP BY s",
    "SELECT a FROM t ORDER BY b + 1, s DESC",
    "SELECT -a AS a FROM t ORDER BY t.a",
    "SELECT COUNT(*) AS n FROM t GROUP BY a ORDER BY a",
    "SELECT DISTINCT a, s FROM t ORDER BY s DESC, a",
    "SELECT DISTINCT a + 1 AS x FROM t ORDER BY x",
    "SELECT a FROM t ORDER BY a LIMIT 3 OFFSET 1",
    "SELECT a FROM t ORDER BY a OFFSET 2",
    "SELECT t.a, u.x FROM t JOIN u ON t.a = u.a",
    "SELECT t.a, u.x FROM t LEFT JOIN u ON t.a = u.a AND u.x <> 'two'",
    "SELECT t.a, u.x FROM t RIGHT JOIN u ON t.a = u.a",
    "SELECT t.a, u.x FROM t FULL JOIN u ON t.a = u.a",
    "SELECT t.a, u.x FROM t CROSS JOIN u",
    "SELECT t.a, u.x FROM t, u WHERE t.a = u.a AND t.f",
    "SELECT x.a, y.b FROM t x JOIN t y ON x.a = y.a + 1",
    "SELECT a FROM t UNION SELECT a FROM u",
    "SELECT a FROM t UNION ALL SELECT a FROM u UNION ALL SELECT k FROM v",
    "SELECT a FROM t UNION ALL SELECT a FROM u UNION ALL SELECT k FROM v UNION ALL SELECT 4",
    "SELECT a FROM t UNION ALL SELECT a FROM u UNION SELECT k FROM v",
    "(SELECT a FROM t ORDER BY a LIMIT 2) UNION ALL SELECT a FROM u ORDER BY 1 LIMIT 3",
    "SELECT a FROM t UNION ALL SELECT b FROM t",
    "SELECT m FROM t UNION ALL SELECT 2.5",
    "WITH c AS (SELECT a FROM t) SELECT * FROM c",
    "WITH c AS (SELECT a FROM t) SELECT x.a FROM c x JOIN c y ON x.a = y.a",
    "WITH c AS (WITH d AS (SELECT a, s FROM t WHERE a > 0) SELECT s FROM d) SELECT * FROM c",
    "WITH c(p, q) AS (SELECT a, s FROM t) SELECT p FROM c WHERE q IS NOT NULL",
    "WITH c AS (SELECT a FROM t), e AS (SELECT a FROM c WHERE a > 1) SELECT * FROM e UNION ALL SELECT * FROM c",
    "SELECT 1 AS one",
    "SELECT 1 AS one WHERE FALSE",
    "SELECT v.\"select\", v.\"x y\" FROM v",
    "SELECT a, a FROM t",
    "WITH c(p, q) AS (SELECT t.a, u.a FROM t JOIN u ON t.a = u.a) SELECT q, p FROM c",
    "WITH c AS (SELECT t.a, u.a FROM t JOIN u ON t.a = u.a) SELECT COUNT(*) AS n FROM c",
    "SELECT a FROM t WHERE a = 1 OR a = 2 OR a = 3 OR a = 4 OR a = 5",
    "SELECT a FROM t WHERE (a > 1 AND b > 2) AND (s = 'x' AND f)",
    "SELECT a FROM t WHERE NOT (a = 1) AND NOT f AND NOT (NOT f)",
    "SELECT a FROM t WHERE dt < '2024-01-01' AND ts >= '2024-01-01 06:00:00'",
    "SELECT a + NULL AS x, m + NULL AS y, NULL = a AS z FROM t",
    // A BETWEEN over two DECIMAL types compares its three operands as the
    // DECIMAL that holds them all, where its two comparisons alone would
    // each compare a pair as another (found by the agreed corpus,
    // plans/testing/14).
    "SELECT a FROM t WHERE m BETWEEN CAST(b AS DECIMAL(25,5)) AND a",
    "SELECT m BETWEEN a AND CAST(d AS DECIMAL(20,4)) AS x, m NOT BETWEEN CAST(b AS DECIMAL(25,5)) AND 2.5 AS y " +
      "FROM t WHERE a > 0 AND m BETWEEN CAST(a AS DECIMAL(20,4)) AND b AND f",
    // A CAST of a condition, whose operand the parser reads only in
    // parentheses (found by the `rewritten` slt mode, plans/testing/16).
    "SELECT CAST((a = 1) AS VARCHAR) AS e, CAST((s LIKE 'x%') AS INT) AS l, CAST((f AND a > 1) AS VARCHAR) AS c, " +
      "CAST((s IS NULL) AS VARCHAR) AS n, CAST((a IN (1, 2)) AS VARCHAR) AS i, CAST((a BETWEEN 1 AND 2) AS VARCHAR) AS b, " +
      "CAST((f OR NOT f) AS VARCHAR) AS o, CAST((NOT f) AS VARCHAR) AS nf FROM t",
    // A table aliased by a word no keyword list holds but the parser reads as
    // the clock where it qualifies a column (found by the round-trip campaign).
    "SELECT a, COUNT(*) AS n FROM t CURRENT_TIMESTAMP GROUP BY a",
    "SELECT CASE FLOOR(RAND() * 3) WHEN 0 THEN 'a' WHEN 1 THEN 'b' END IS NULL AS c, " +
      "RAND() BETWEEN 0.25 AND 0.75 AS bt FROM t WHERE RAND() >= 0"
  )

  @Test def everyConstructRoundTrips(): Unit = queries.foreach { sql =>
    PlanRoundTrip.check(sql, catalog) match {
      case PlanRoundTrip.Same(printed) =>
        if (!sql.contains("RAND()")) assertSameAnswer(sql, printed, optimize = true)
      case other => fail(s"$sql\n  did not round trip: $other")
    }
  }

  @Test def theOptimizedPlanPrintsAndAnswersAsTheQueryDoes(): Unit = queries.filterNot(_.contains("RAND()")).foreach { sql =>
    val printed = PlanRoundTrip.optimizedSql(sql, catalog).getOrElse(fail(s"$sql: its optimized plan did not print"))
    assertSameAnswer(sql, printed.toString, optimize = false)
  }

  /** The text pins the printer's forms: one SELECT per stack, a CTE for a
    * filter the optimizer pushed under a join, qualified columns, explicit
    * casts but to DECIMAL, literals of their own type. */
  @Test def thePrintedTextIsReadable(): Unit = {
    def printed(sql: String) = PlanSql.print(LogicalBuilder.build(sql, catalog), catalog)
    assertEquals(
      "SELECT t.a AS a, CAST(t.a AS BIGINT) + t.b AS x\nFROM t\nWHERE t.a > 1 AND t.s IS NOT NULL\nORDER BY 2 DESC\nLIMIT 3",
      printed("SELECT a, a + b AS x FROM t WHERE a > 1 AND s IS NOT NULL ORDER BY x DESC LIMIT 3"))
    assertEquals(
      "SELECT t.s AS s, COUNT(*) AS n\nFROM t\nGROUP BY t.s\nHAVING SUM(t.m) > 0",
      printed("SELECT s, COUNT(*) AS n FROM t GROUP BY s HAVING SUM(m) > 0"))
    assertEquals(
      "SELECT CAST('5' AS BIGINT) AS b, -5 AS n, t.m * 1.05 AS p\nFROM t",
      printed("SELECT CAST('5' AS BIGINT) AS b, CAST('-5' AS INT) AS n, m * 1.05 AS p FROM t"))
    assertEquals(
      "SELECT CAST(t.m AS DECIMAL(4,1)) AS x, CAST('1.5' AS DECIMAL(2,1)) AS y, t.m = t.a AS z\nFROM t",
      printed("SELECT CAST(m AS NUMERIC(4, 1)) AS x, CAST('1.5' AS DECIMAL(2,1)) AS y, m = a AS z FROM t"))
    val built = LogicalBuilder.build("SELECT t.a, u.x FROM t JOIN u ON t.a = u.a WHERE u.x = 'two'", catalog)
    assertEquals(
      "WITH q1 AS (\n  SELECT u.a AS a, u.x AS x\n  FROM u\n  WHERE u.x = 'two'\n)\n" +
        "SELECT t.a AS a, q1.x AS x\nFROM t\nJOIN q1 ON t.a = q1.a",
      PlanSql.print(LogicalOptimizer.optimize(built.main), catalog))
  }

  /** Two spellings of one aggregate or one window function are computed
    * once: they were a slot each, which the printer wrote alike and which
    * bound again as one (found by `plan_roundtrip_fuzz_campaign`). */
  @Test def anAggregateOrWindowIsComputedOnceHoweverItIsSpelled(): Unit = {
    def find[A](p: LogicalPlan, f: PartialFunction[LogicalPlan, A]): A =
      f.lift(p).getOrElse(p.children.iterator.map(c => find(c, f)).next())
    val agg = LogicalBuilder.build(
      "SELECT s, Count(*) AS a, COUNT(*) AS b, count( * ) AS c, SUM(a) AS d, SUM(t.a) AS e FROM t GROUP BY s " +
        "ORDER BY COUNT(*)", catalog).main
    assertEquals(Seq("COUNT", "SUM"), find(agg, { case a: LogicalAggregate => a.aggregates.map(_._1.name) }))
    val win = LogicalBuilder.build(
      "SELECT ROW_NUMBER() OVER (ORDER BY a) AS r1, ROW_NUMBER() OVER (ORDER BY t.a) AS r2 FROM t", catalog).main
    assertEquals(1, find(win, { case w: LogicalWindow => w.windows.size }))
    Seq("SELECT s, count( * ) AS a, COUNT(*) AS b FROM t GROUP BY s ORDER BY COUNT(*), s",
      "SELECT ROW_NUMBER() OVER (ORDER BY a) AS r1, ROW_NUMBER() OVER (ORDER BY t.a) AS r2 FROM t"
    ).foreach(sql => assertTrue(sql, PlanRoundTrip.check(sql, catalog).isInstanceOf[PlanRoundTrip.Same]))
  }

  @Test def aPlanThePrinterCannotWriteSaysWhy(): Unit = {
    val built = LogicalBuilder.build("WITH c AS (SELECT a FROM t) SELECT x.a FROM c x JOIN c y ON x.a = y.a", catalog)
    val resolved = CteResolver.resolve(built, ExecutionOptions.Default)
    val e = try { PlanSql.print(resolved, catalog); null } catch { case e: PlanSql.UnprintablePlanException => e }
    assertNotNull("a materialized CTE's scan has no SQL", e)
    assertTrue(e.getMessage, e.getMessage.contains("no table of the catalog"))
  }

  /** `printed` answers as `sql` does; run with the optimizer off when
    * `optimize` is false, so an optimized plan's text is what runs. */
  private def assertSameAnswer(sql: String, printed: String, optimize: Boolean): Unit = {
    val expected = RelEngine.runRows(catalog, sql, ExecutionOptions.Default)
    val (schema, rows) =
      try {
        val q =
          if (optimize) RelEngine.execute(catalog, printed, ExecutionOptions.Default)
          else {
            val physical = PhysicalPlanner.plan(CteResolver.resolve(LogicalBuilder.build(printed, catalog),
              ExecutionOptions.Default), ExecutionOptions.Default)
            PhysicalPlanner.preMaterializeExchanges(physical)
            new ExecutedQuery(physical.outputSchema, (0 until physical.numPartitions).map(physical.execute))
          }
        (q.schema, RelEngine.collectRows(q))
      } catch { case e: Exception => fail(s"$sql\n  printed:\n$printed\n  fails: $e"); throw e }
    RowOracle.multisetEquals(schema, expected, rows).foreach { diff =>
      fail(s"$sql\n  printed:\n$printed\n  answers otherwise: $diff")
    }
  }
}
