package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan.{LogicalBuilder, LogicalOptimizer}
import org.junit.Assert._
import org.junit.Test

import scala.jdk.CollectionConverters._

/** `--config=verify` ([[BatchInvariants]]): each invariant fails a hand-built
  * batch with its message; the plan walk wraps every operator the planner
  * builds in a [[VerifiedPlan]] and refuses one it has no branch for; and a
  * batch that breaks an invariant fails at the operator that emitted it,
  * naming the operator, the column, the row and the value. Runs with
  * `-Dtransformer.verify=true` and the sharding gates at their smallest, so
  * the planner builds every operator it has. */
class VerifyTest {

  private def batch(schema: Schema, rows: Seq[Any]*): ColumnarBatch = {
    val b = new ColumnarBatch(schema, math.max(1, rows.size))
    rows.zipWithIndex.foreach { case (row, r) =>
      row.indices.foreach(c => if (row(c) == null) b.column(c).setNull(r) else b.column(c).setBoxed(r, row(c)))
    }
    b.setNumRows(rows.size)
    b
  }

  private def schemaOf(fields: (String, DataType)*): Schema = Schema(fields.map { case (n, t) => Field(n, t) }.toVector)

  private def violation(b: ColumnarBatch, declared: Schema): BatchInvariants.Violation =
    BatchInvariants.violation(b, declared).getOrElse(throw new AssertionError(s"no violation in $declared"))

  @Test def theSuiteRunsThisTargetUnderVerify(): Unit =
    assertTrue("the target's jvm_flags set -Dtransformer.verify=true", BatchInvariants.enabled)

  @Test def aDecimalPastItsPrecisionFailsTheCheck(): Unit = {
    val s = schemaOf("d" -> DataType.DecimalType(4, 2))
    val v = violation(batch(s, Seq(new java.math.BigDecimal("1.25")), Seq(new java.math.BigDecimal("123.45"))), s)
    assertEquals((0, 1), (v.column, v.row))
    assertEquals("123.45 has more digits than the 4 of a DECIMAL(4,2)", v.what)
  }

  @Test def aDecimalPastItsScaleFailsTheCheckAndOneAtASmallerScaleIsAllowed(): Unit = {
    val s = schemaOf("d" -> DataType.DecimalType(9, 2))
    assertEquals("1.5 in a DECIMAL(9,2) is 1.50", None,
      BatchInvariants.violation(batch(s, Seq(new java.math.BigDecimal("1.5")), Seq(new java.math.BigDecimal("2.500"))), s))
    val v = violation(batch(s, Seq(new java.math.BigDecimal("1.255"))), s)
    assertEquals("1.255 has more digits after the point than the 2 of a DECIMAL(9,2)", v.what)
  }

  @Test def aColumnOfTheWrongVectorTypeFailsTheCheck(): Unit = {
    val b = batch(schemaOf("x" -> DataType.LongType), Seq(1L))
    val v = violation(b, schemaOf("x" -> DataType.IntType))
    assertEquals(0, v.column)
    assertEquals("the vector is long where the operator declares int", v.what)
    val d = violation(batch(schemaOf("d" -> DataType.DecimalType(10, 2)), Seq(new java.math.BigDecimal("1.00"))),
      schemaOf("d" -> DataType.DecimalType(10, 3)))
    assertEquals("the vector is decimal(10,2) where the operator declares decimal(10,3)", d.what)
  }

  @Test def aBatchOfAnotherWidthFailsTheCheck(): Unit = {
    val v = violation(batch(schemaOf("x" -> DataType.IntType), Seq(1)),
      schemaOf("x" -> DataType.IntType, "y" -> DataType.IntType))
    assertEquals("the batch has 1 columns where the operator declares 2", v.what)
  }

  @Test def aBatchWithMoreRowsThanAColumnHoldsFailsTheCheck(): Unit = {
    val s = schemaOf("x" -> DataType.IntType)
    val short = new IntVector(new Array[Int](2), new java.util.BitSet(), 2)
    val wide = new IntVector(new Array[Int](4), new java.util.BitSet(), 4)
    val b = ColumnarBatch.fromColumns(schemaOf("x" -> DataType.IntType, "y" -> DataType.IntType),
      Array[ColumnVector](wide, short), 2)
    assertEquals(None, BatchInvariants.violation(b, schemaOf("x" -> DataType.IntType, "y" -> DataType.IntType)))
    val lying = new IntVector(new Array[Int](1), new java.util.BitSet(), 4)
    val bad = ColumnarBatch.fromColumns(s, Array[ColumnVector](lying), 3)
    assertEquals("the batch has 3 rows where the vector holds 1", violation(bad, s).what)
  }

  @Test def halfASurrogatePairFailsTheCheck(): Unit = {
    val s = schemaOf("s" -> DataType.StringType)
    val high = 0xd83d.toChar
    val low = 0xde00.toChar
    assertEquals("a whole pair is text", None, BatchInvariants.violation(batch(s, Seq(s"a$high${low}b")), s))
    Seq(s"a$high", s"$low", s"x${high}y", s"$low$high").foreach { bad =>
      val v = violation(batch(s, Seq("fine"), Seq(bad)), s)
      assertEquals(1, v.row)
      assertTrue(v.what, v.what.contains("holds half a surrogate pair"))
    }
    assertEquals(-1, BatchInvariants.halfPair(s"$high$low"))
    assertEquals(1, BatchInvariants.halfPair(s"a${low}b"))
    assertEquals(2, BatchInvariants.halfPair(s"ab$high"))
  }

  @Test def aBatchWhoseOwnSchemaNamesAnotherColumnFailsTheCheck(): Unit = {
    val v = violation(batch(schemaOf("a" -> DataType.IntType), Seq(1)), schemaOf("b" -> DataType.IntType))
    assertEquals("the batch's own schema calls it 'a' int where the operator declares 'b' int", v.what)
  }

  // ---- The plan walk ----------------------------------------------------

  private val catalog: Catalog = {
    val c = new Catalog
    val t = schemaOf("a" -> DataType.IntType, "k" -> DataType.IntType, "d" -> DataType.DecimalType(6, 2),
      "s" -> DataType.StringType)
    c.register("t", new MaterializedView(t, IndexedSeq(
      IndexedSeq(batch(t, Seq(1, 1, new java.math.BigDecimal("1.50"), "x"), Seq(2, 2, null, "y"))),
      IndexedSeq(batch(t, Seq(3, 1, new java.math.BigDecimal("2.25"), null), Seq(4, 3, new java.math.BigDecimal("0.5"), "z"))))))
    val u = schemaOf("k" -> DataType.IntType, "name" -> DataType.StringType)
    c.register("u", new MaterializedView(u, IndexedSeq(IndexedSeq(batch(u, Seq(1, "one"), Seq(2, "two"), Seq(5, "five"))))))
    c
  }

  /** A query that makes the planner build each of its operators. */
  private val queries = Seq(
    "SELECT COUNT(*) FROM t",
    "SELECT a + 1 AS x, d * 2 AS dd FROM t WHERE a > 1",
    "SELECT a FROM t LIMIT 3",
    "SELECT a FROM t ORDER BY a OFFSET 1",
    "SELECT k, SUM(d) AS s, COUNT(*) AS n FROM t GROUP BY k",
    "SELECT t.a, u.name FROM t JOIN u ON t.k = u.k",
    "SELECT t.a, u.name FROM t JOIN u ON t.k < u.k",
    "SELECT DISTINCT k FROM t",
    "SELECT a AS v FROM t UNION ALL SELECT k FROM u",
    "SELECT a, ROW_NUMBER() OVER (PARTITION BY k ORDER BY a) AS rn FROM t")

  private def planned(sql: String): PhysicalPlan =
    PhysicalPlanner.plan(LogicalOptimizer.optimize(CteResolver.resolve(LogicalBuilder.build(sql, catalog),
      ExecutionOptions.Default)))

  /** Every operator class in `plan`, each checked to sit inside a
    * [[VerifiedPlan]], as each of its children does. */
  private def operators(plan: PhysicalPlan, sql: String): Set[Class[_]] = plan match {
    case v: VerifiedPlan =>
      val op = v.underlying
      val children = op match {
        case f: FilterExec => Seq(f.child)
        case p: ProjectExec => Seq(p.child)
        case l: LocalLimitExec => Seq(l.child)
        case g: GlobalLimitExec => Seq(g.child)
        case o: OffsetExec => Seq(o.child)
        case a: HashAggregateExec => Seq(a.child)
        case j: HashJoinExec => Seq(j.left, j.right)
        case s: SortExec => Seq(s.child)
        case d: DistinctExec => Seq(d.child)
        case e: ExchangeExec => Seq(e.child)
        case w: WindowExec => Seq(w.child)
        case u: UnionExec => Seq(u.left, u.right)
        case _: ScanExec | _: CountStarMetadataExec => Nil
        case other => fail(s"$sql: no case here for ${other.getClass}"); Nil
      }
      children.foldLeft(Set[Class[_]](op.getClass))((acc, c) => acc ++ operators(c, sql))
    case other => fail(s"$sql: ${other.getClass.getSimpleName} is not verified"); Set.empty
  }

  /** Every class in the exec package that is a [[PhysicalPlan]] and not a
    * wrapper: the operators the planner can build. */
  private def operatorClasses: Set[Class[_]] = {
    val jar = new java.util.jar.JarFile(new java.io.File(classOf[PhysicalPlan].getProtectionDomain.getCodeSource.getLocation.toURI))
    try jar.entries().asScala.map(_.getName)
      .filter(n => n.startsWith("com/transformer/sql/exec/") && n.endsWith(".class") && !n.contains("$"))
      .map(n => Class.forName(n.stripSuffix(".class").replace('/', '.')))
      .filter(c => classOf[PhysicalPlan].isAssignableFrom(c) && !c.isInterface &&
        c != classOf[MeteredPlan] && c != classOf[VerifiedPlan])
      .toSet
    finally jar.close()
  }

  @Test def everyOperatorThePlannerBuildsIsVerifiedAndAnswers(): Unit = {
    val seen = queries.flatMap { sql =>
      val ops = operators(planned(sql), sql)
      val q = SqlEngine.execute(sql, catalog, ExecutionOptions.Default)
      q.batches.foreach(_ => ())
      ops
    }.toSet
    val all = operatorClasses
    assertTrue(s"found the operators by reflection: $all", all.size >= 14)
    assertEquals("the queries build every operator, so the walk has a branch for each",
      all.map(_.getSimpleName).toSeq.sorted, seen.map(_.getSimpleName).toSeq.sorted)
  }

  @Test def theWalkRefusesAnOperatorItHasNoBranchFor(): Unit = {
    val schema = schemaOf("x" -> DataType.IntType)
    val unknown = new PhysicalPlan {
      def outputSchema: Schema = schema
      def numPartitions: Int = 1
      def execute(p: Int): Iterator[ColumnarBatch] = Iterator.empty
    }
    val e = assertThrows(classOf[IllegalStateException], () => { val _ = PhysicalPlanner.decorate(FilterExec(unknown,
      com.transformer.sql.plan.LitExpr(true, DataType.BooleanType)), metrics = false) })
    assertTrue(e.getMessage, e.getMessage.startsWith("The plan walk has no branch for"))
  }

  @Test def aBatchThatBreaksAnInvariantFailsAtTheOperatorThatEmittedIt(): Unit = {
    // A view built in Scala holding a DECIMAL(4,1) value with two places:
    // the scan emits it, so the scan is named.
    val s = schemaOf("d" -> DataType.DecimalType(4, 1))
    val c = new Catalog
    c.register("bad", new MaterializedView(s, IndexedSeq(IndexedSeq(batch(s, Seq(new java.math.BigDecimal("1.0")),
      Seq(new java.math.BigDecimal("12.34")))))))
    val e = assertThrows(classOf[AssertionError], () =>
      SqlEngine.execute("SELECT d FROM bad WHERE d IS NOT NULL", c, ExecutionOptions.Default).batches.foreach(_ => ()))
    val first = e.getMessage.linesIterator.next()
    assertEquals("ScanExec (line 3 of the plan, `Scan a view: d [1 partition]`), partition 0, column 0 'd', row 1 of a " +
      "batch of 2, value 12.34: 12.34 has more digits after the point than the 1 of a DECIMAL(4,1)", first)
    assertEquals("the whole plan follows, the operator's line marked", Seq("The plan:", "  Project: d [1 partition]",
      "    Filter: d IS NOT NULL [1 partition]", ">     Scan a view: d [1 partition]"), e.getMessage.linesIterator.drop(1).toSeq)
  }
}
