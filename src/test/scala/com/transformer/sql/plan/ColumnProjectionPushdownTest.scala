package com.transformer.sql.plan

import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

/** Unit tests for [[ColumnProjectionPushdown]]'s join pruning.
  *
  * Each test wires up scans whose underlying [[CatalogView]]s support
  * projection (so pruning actually fires) and asserts: (a) each side's scan
  * decodes only the columns the join needs, (b) the join condition's
  * [[ColRefExpr]] indices were remapped to the new combined schema, (c)
  * the parent's expressions were updated through the returned remap.
  */
class ColumnProjectionPushdownTest {

  // ---------------------------------------------------------------------------
  // Test view that implements withProjectedColumns
  // ---------------------------------------------------------------------------

  /** A view advertising `schema` whose `withProjectedColumns` returns a new
    * view with only the requested columns (in the original schema order, per
    * the trait contract). Read paths are unreachable — projection pushdown
    * only inspects the schema. */
  private final class ProjectableView(val schema: Schema) extends CatalogView {
    def numPartitions: Int = 0
    def readPartition(p: Int): Iterator[ColumnarBatch] =
      throw new AssertionError("projection pushdown should not read data")
    override def withProjectedColumns(names: Seq[String]): Option[CatalogView] = {
      val keep = schema.fields.filter(f => names.contains(f.name))
      Some(new ProjectableView(Schema(keep)))
    }
  }

  private def colInt(i: Int, n: String): ColRefExpr =
    ColRefExpr(i, n, DataType.IntType)
  private def colStr(i: Int, n: String): ColRefExpr =
    ColRefExpr(i, n, DataType.StringType)

  // ---------------------------------------------------------------------------
  // Phase 3 tests
  // ---------------------------------------------------------------------------

  @Test def joinPrunesBothSidesKeepingProjectedAndJoinKeyColumns(): Unit = {
    // l: (id, name, age), r: (user_id, score, country)
    // Query: SELECT l.name, r.score FROM l JOIN r ON l.id = r.user_id
    // Expected: l's scan keeps (id, name); r's scan keeps (user_id, score).
    val lSchema = Schema(Vector(
      Field("id", DataType.IntType),
      Field("name", DataType.StringType),
      Field("age", DataType.IntType)))
    val rSchema = Schema(Vector(
      Field("user_id", DataType.IntType),
      Field("score", DataType.IntType),
      Field("country", DataType.StringType)))
    val l = LogicalScan("l", new ProjectableView(lSchema), Some("l"))
    val r = LogicalScan("r", new ProjectableView(rSchema), Some("r"))
    // Combined indices: id=0, name=1, age=2, user_id=3, score=4, country=5. leftWidth=3.
    val cond = BinOpExpr("=", colInt(0, "id"), colInt(3, "user_id"), DataType.BooleanType)
    val join = LogicalJoin(l, r, cond, JoinKind.Inner)
    val proj = LogicalProject(join, Seq(
      (colStr(1, "name"), "name"),
      (colInt(4, "score"), "score")))

    val out = ColumnProjectionPushdown(proj)

    out match {
      case LogicalProject(LogicalJoin(newL: LogicalScan, newR: LogicalScan, newCond, JoinKind.Inner), newProjs) =>
        assertEquals(Vector("id", "name"), newL.outputSchema.fieldNames)
        assertEquals(Vector("user_id", "score"), newR.outputSchema.fieldNames)

        // New combined: id=0, name=1, user_id=2, score=3. leftWidth=2.
        newCond match {
          case BinOpExpr("=", ColRefExpr(0, "id", _), ColRefExpr(2, "user_id", _), _) => ()
          case other => fail(s"condition not remapped: $other")
        }

        newProjs match {
          case Seq((ColRefExpr(1, "name", _), "name"), (ColRefExpr(3, "score", _), "score")) => ()
          case other => fail(s"projections not remapped: $other")
        }
      case other => fail(s"expected pruned Project(Join(Scan,Scan)), got $other")
    }
  }

  @Test def joinKeepsJoinKeyColumnsEvenIfParentDoesNotReferenceThem(): Unit = {
    // SELECT l.name FROM l JOIN r ON l.id = r.user_id — parent needs only
    // l.name, but the join condition still references l.id and r.user_id,
    // so those must survive on their respective sides.
    val lSchema = Schema(Vector(
      Field("id", DataType.IntType),
      Field("name", DataType.StringType)))
    val rSchema = Schema(Vector(
      Field("user_id", DataType.IntType),
      Field("score", DataType.IntType)))
    val l = LogicalScan("l", new ProjectableView(lSchema), Some("l"))
    val r = LogicalScan("r", new ProjectableView(rSchema), Some("r"))
    val cond = BinOpExpr("=", colInt(0, "id"), colInt(2, "user_id"), DataType.BooleanType)
    val join = LogicalJoin(l, r, cond, JoinKind.Inner)
    val proj = LogicalProject(join, Seq((colStr(1, "name"), "name")))

    val out = ColumnProjectionPushdown(proj)
    out match {
      case LogicalProject(LogicalJoin(newL: LogicalScan, newR: LogicalScan, _, _), _) =>
        // l keeps id (for join key) + name (for parent).
        assertEquals(Vector("id", "name"), newL.outputSchema.fieldNames)
        // r keeps only user_id (for join key); score is pruned.
        assertEquals(Vector("user_id"), newR.outputSchema.fieldNames)
      case other => fail(s"expected pruned Project(Join), got $other")
    }
  }

  @Test def selectStarKeepsEveryColumn(): Unit = {
    // SELECT * FROM l JOIN r — every column is needed, no pruning.
    val lSchema = Schema(Vector(Field("a", DataType.IntType), Field("b", DataType.IntType)))
    val rSchema = Schema(Vector(Field("c", DataType.IntType), Field("d", DataType.IntType)))
    val l = LogicalScan("l", new ProjectableView(lSchema), Some("l"))
    val r = LogicalScan("r", new ProjectableView(rSchema), Some("r"))
    val cond = BinOpExpr("=", colInt(0, "a"), colInt(2, "c"), DataType.BooleanType)
    val join = LogicalJoin(l, r, cond, JoinKind.Inner)
    // SELECT * unfolds to four named projections.
    val proj = LogicalProject(join, Seq(
      (colInt(0, "a"), "a"),
      (colInt(1, "b"), "b"),
      (colInt(2, "c"), "c"),
      (colInt(3, "d"), "d")))

    val out = ColumnProjectionPushdown(proj)
    out match {
      case LogicalProject(LogicalJoin(newL: LogicalScan, newR: LogicalScan, _, _), _) =>
        assertEquals(Vector("a", "b"), newL.outputSchema.fieldNames)
        assertEquals(Vector("c", "d"), newR.outputSchema.fieldNames)
      case other => fail(s"expected unchanged shape, got $other")
    }
  }

  @Test def selfJoinKeepsNeededColumnsOnBothSides(): Unit = {
    // SELECT a.x, b.x FROM t a JOIN t b ON a.k = b.k. Self-join with shared
    // column names. Name-based pruning is pessimistic here: both sides keep
    // `x` even when only one is actually needed by the projection — but the
    // RESULT must still be correct.
    val tSchema = Schema(Vector(
      Field("k", DataType.IntType),
      Field("x", DataType.IntType),
      Field("y", DataType.IntType)))   // y is referenced by neither side
    val a = LogicalScan("t", new ProjectableView(tSchema), Some("a"))
    val b = LogicalScan("t", new ProjectableView(tSchema), Some("b"))
    // Combined: a.k=0, a.x=1, a.y=2, b.k=3, b.x=4, b.y=5. leftWidth=3.
    val cond = BinOpExpr("=", colInt(0, "k"), colInt(3, "k"), DataType.BooleanType)
    val join = LogicalJoin(a, b, cond, JoinKind.Inner)
    val proj = LogicalProject(join, Seq(
      (colInt(1, "x"), "a_x"),
      (colInt(4, "x"), "b_x")))

    val out = ColumnProjectionPushdown(proj)
    out match {
      case LogicalProject(LogicalJoin(newA: LogicalScan, newB: LogicalScan, newCond, _), newProjs) =>
        // Both sides keep k (join key) and x (parent + collides with other
        // side's x in the needed-names set). y is pruned.
        assertEquals(Vector("k", "x"), newA.outputSchema.fieldNames)
        assertEquals(Vector("k", "x"), newB.outputSchema.fieldNames)

        // New combined: a.k=0, a.x=1, b.k=2, b.x=3. leftWidth=2.
        newCond match {
          case BinOpExpr("=", ColRefExpr(0, "k", _), ColRefExpr(2, "k", _), _) => ()
          case other => fail(s"condition not remapped: $other")
        }
        newProjs match {
          case Seq((ColRefExpr(1, "x", _), "a_x"), (ColRefExpr(3, "x", _), "b_x")) => ()
          case other => fail(s"projections not remapped: $other")
        }
      case other => fail(s"expected pruned self-join, got $other")
    }
  }

  @Test def joinUnderFilterPrunesBothSides(): Unit = {
    // The output of [[LogicalOptimizer]] often has a filter directly above a
    // join (filter pushdown didn't have a clean way to move it). The
    // projection-through-join pass must still prune both children based on
    // (parent + filter + cond) refs.
    val lSchema = Schema(Vector(
      Field("id", DataType.IntType),
      Field("name", DataType.StringType),
      Field("age", DataType.IntType)))
    val rSchema = Schema(Vector(
      Field("user_id", DataType.IntType),
      Field("score", DataType.IntType),
      Field("country", DataType.StringType)))
    val l = LogicalScan("l", new ProjectableView(lSchema), Some("l"))
    val r = LogicalScan("r", new ProjectableView(rSchema), Some("r"))
    val cond = BinOpExpr("=", colInt(0, "id"), colInt(3, "user_id"), DataType.BooleanType)
    val join = LogicalJoin(l, r, cond, JoinKind.Inner)
    // Cross-side filter (cannot push) — references age and country.
    val cross = BinOpExpr(">",
      BinOpExpr("+", colInt(2, "age"), colInt(4, "score"), DataType.IntType),
      LitExpr(100, DataType.IntType),
      DataType.BooleanType)
    val filt = LogicalFilter(join, cross)
    val proj = LogicalProject(filt, Seq((colStr(1, "name"), "name")))

    val out = ColumnProjectionPushdown(proj)
    out match {
      case LogicalProject(LogicalFilter(LogicalJoin(newL: LogicalScan, newR: LogicalScan, _, _), newCross), _) =>
        // Filter needs age (left) and score (right); join cond needs id /
        // user_id; project needs name. Country is pruned on right.
        assertTrue("left keeps id,name,age", newL.outputSchema.fieldNames.toSet == Set("id", "name", "age"))
        assertTrue("right keeps user_id,score", newR.outputSchema.fieldNames.toSet == Set("user_id", "score"))
        // The Filter's predicate should also have been remapped against the
        // new combined schema.
        val combined = Schema(newL.outputSchema.fields ++ newR.outputSchema.fields)
        assertValidExprIndices(newCross, combined)
      case other => fail(s"expected Project(Filter(Join)) with both sides pruned, got $other")
    }
  }

  // ---------------------------------------------------------------------------
  // Verifier: confirm the verifier itself catches deliberate breakage.
  // ---------------------------------------------------------------------------

  @Test def verifierRejectsOutOfRangeColRef(): Unit = {
    // Construct a plan whose Filter references an index beyond the child's
    // schema. The verifier should reject it at apply() time.
    val lSchema = Schema(Vector(Field("a", DataType.IntType)))
    val l = LogicalScan("l", new ProjectableView(lSchema), Some("l"))
    // Index 5 doesn't exist.
    val bad = LogicalFilter(l, BinOpExpr("=", colInt(5, "missing"),
      LitExpr(1, DataType.IntType), DataType.BooleanType))
    val ex = try { ColumnProjectionPushdown(bad); null }
      catch { case e: IllegalStateException => e }
    assertNotNull("expected verifier to reject out-of-range ColRef", ex)
    assertTrue(ex.getMessage, ex.getMessage.contains("out of range"))
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def assertValidExprIndices(e: Expr, schema: Schema): Unit = e match {
    case ColRefExpr(i, n, _) =>
      assertTrue(s"ColRef index $i ('$n') out of range [0, ${schema.length})",
        i >= 0 && i < schema.length)
    case BinOpExpr(_, l, r, _) => assertValidExprIndices(l, schema); assertValidExprIndices(r, schema)
    case UnaryOpExpr(_, c, _) => assertValidExprIndices(c, schema)
    case CastExpr(c, _) => assertValidExprIndices(c, schema)
    case FuncExpr(_, args, _) => args.foreach(assertValidExprIndices(_, schema))
    case CaseExpr(branches, elseE, _) =>
      branches.foreach { case (a, b) =>
        assertValidExprIndices(a, schema); assertValidExprIndices(b, schema)
      }
      elseE.foreach(assertValidExprIndices(_, schema))
    case IsNullExpr(c, _) => assertValidExprIndices(c, schema)
    case InListExpr(c, items, _) =>
      assertValidExprIndices(c, schema); items.foreach(assertValidExprIndices(_, schema))
    case LikeExpr(s, p, _) => assertValidExprIndices(s, schema); assertValidExprIndices(p, schema)
    case _: LitExpr => ()
  }
}
