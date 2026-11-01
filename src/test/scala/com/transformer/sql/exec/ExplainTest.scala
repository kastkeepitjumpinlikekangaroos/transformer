package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.job.TaskDag
import org.junit.Assert._
import org.junit.Test

/** `EXPLAIN` returns a query's plan as rows of one STRING column without
  * running any of it; `EXPLAIN VERBOSE` adds the plan as bound and the
  * physical operators; the options EXPLAIN takes elsewhere are refused. */
class ExplainTest {

  /** A view that fails if read: EXPLAIN must plan over it without reading. */
  private final class Unreadable(val schema: Schema) extends CatalogView {
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] = throw new AssertionError("EXPLAIN read a row")
  }

  private val catalog: Catalog = {
    val c = new Catalog
    c.register("t", new Unreadable(Schema(Vector(Field("a", DataType.IntType), Field("s", DataType.StringType)))))
    c.register("u", new Unreadable(Schema(Vector(Field("a", DataType.IntType), Field("x", DataType.StringType)))))
    c
  }

  private def rows(sql: String): Vector[String] = {
    val q = SqlEngine.execute(sql, catalog, ExecutionOptions.Default)
    assertEquals(Seq("plan"), q.schema.fieldNames)
    assertEquals(Seq(DataType.StringType), q.schema.fields.map(_.dataType))
    q.batches.flatMap(b => (0 until b.numRows).map(r => b.column(0).getBoxed(r).toString)).toVector
  }

  @Test def explainShowsTheOptimizedPlanAsATreeAndAsSql(): Unit = assertEquals(Vector(
    "== Optimized Logical Plan ==",
    "Project: t.a, u.x",
    "  Join INNER: t.a = u.a",
    "    Scan t: a, s",
    "    Filter: u.x = 'two'",
    "      Scan u: a, x",
    "== Optimized SQL ==",
    "WITH q1 AS (",
    "  SELECT u.a AS a, u.x AS x",
    "  FROM u",
    "  WHERE u.x = 'two'",
    ")",
    "SELECT t.a AS a, q1.x AS x",
    "FROM t",
    "JOIN q1 ON t.a = q1.a"
  ), rows("EXPLAIN SELECT t.a, u.x FROM t, u WHERE t.a = u.a AND u.x = 'two'"))

  @Test def explainVerboseAddsTheBoundPlanAndThePhysicalOperators(): Unit = {
    val lines = rows("explain  /* a comment */ verbose SELECT s, COUNT(*) AS n FROM t GROUP BY s ORDER BY n DESC")
    val sections = lines.filter(_.startsWith("=="))
    assertEquals(Seq("== Bound Logical Plan ==", "== Bound SQL ==", "== Optimized Logical Plan ==",
      "== Optimized SQL ==", "== Physical Plan =="), sections)
    val physical = lines.dropWhile(_ != "== Physical Plan ==").tail
    assertTrue(physical.mkString("\n"), physical.exists(_.contains("HashAggregate: GROUP BY s; COUNT(*) AS _agg0")))
    assertEquals(physical.mkString("\n"), Vector(
      "Sort: n DESC [1 partition]",
      "  Project: s, _agg0 AS n [1 partition]",
      "    HashAggregate: GROUP BY s; COUNT(*) AS _agg0 [1 partition]",
      "      Scan t: a, s [1 partition]"), physical)
  }

  /** A CTE read twice is materialized before the query runs: the tree scans
    * it and shows its plan, and nothing is run to show either. */
  @Test def aMaterializedCteIsShownBesideThePlanAndNotRun(): Unit = {
    val lines = rows("EXPLAIN VERBOSE WITH c AS (SELECT a FROM t WHERE a > 0) SELECT x.a FROM c x JOIN c y ON x.a = y.a")
    assertTrue(lines.mkString("\n"), lines.contains("CTE c:"))
    assertTrue(lines.mkString("\n"), lines.exists(_.contains("Scan CTE c AS x: a")))
    assertTrue(lines.mkString("\n"), lines.contains("CTE c, materialized:"))
    assertTrue(lines.mkString("\n"), lines.exists(_.startsWith("WITH c AS (")))
  }

  @Test def theApiReturnsTheSameLines(): Unit =
    assertEquals(rows("EXPLAIN SELECT a FROM t"), SqlEngine.explain("SELECT a FROM t", catalog))

  @Test def otherOptionsAreRefused(): Unit = Seq("ANALYZE", "FORMAT JSON", "COSTS", "VERBOSE ANALYZE").foreach { o =>
    val e = try { rows(s"EXPLAIN $o SELECT a FROM t"); null } catch { case e: IllegalArgumentException => e }
    assertNotNull(o, e)
    assertTrue(e.getMessage, e.getMessage.contains("is not supported: EXPLAIN shows a query's plan without running it"))
  }

  /** A query the binder refuses is refused under EXPLAIN in its own words;
    * one the physical planner would refuse still shows its logical plan. */
  @Test def aRefusalIsTheQuerysOwn(): Unit = {
    val e = try { rows("EXPLAIN SELECT nope FROM t"); null } catch { case e: IllegalArgumentException => e }
    assertTrue(String.valueOf(e), e != null && e.getMessage.contains("Unknown column 'nope'"))
  }

  /** The job runner reads an EXPLAIN's views from its query. */
  @Test def anExplainReadsItsQuerysViews(): Unit =
    assertEquals(Set("t"), TaskDag.viewsRead("EXPLAIN WITH c AS (SELECT * FROM t) SELECT * FROM c"))
}
