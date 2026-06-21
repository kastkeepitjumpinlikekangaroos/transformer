package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.oracle.ExprParity
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

/** Property-based parity gate for `Expr.eval` vs `Expr.evalVec` — the generated
  * generalization of [[com.transformer.sql.plan.ExprBatchTest]].
  *
  * [[fuzzExprParity]] generates a random typed `Expr` over a random
  * `ColumnarBatch` and asserts the two evaluation paths agree on every row,
  * across a small fixed-seed batch by default (`Props.DefaultSeeds`). Override
  * the budget for a long campaign:
  * {{{
  *   bazel test //src/test/scala/com/transformer/fuzz:expr_parity_fuzz_test \
  *     --jvmopt='-Dfuzz.seeds=200000' --test_output=streamed
  * }}}
  * A failure prints the seed; reproduce a single case with
  * `-Dfuzz.seed=<seed> -Dfuzz.seeds=1`.
  *
  * The hand-written [[regressionComposite]] locks a fixed tree so a meaningful
  * case always runs even with `-Dfuzz.seeds=0`. When the fuzzer finds a real
  * divergence, distill it into a named regression here and (if it reflects a
  * gap) into `ExprBatchTest` proper.
  */
class ExprParityFuzzTest {

  @Test def fuzzExprParity(): Unit = {
    Props.forAll[ExprGen.ExprCase](
      name = "expr-parity",
      gen = ExprGen.generate,
      shrink = c => Shrinker.expr(c.expr).map(e => new ExprGen.ExprCase(c.batch, e))
    ) { c =>
      ExprParity.check(c.expr, c.batch)
    }
  }

  /** Same seed reproduces the exact tree and batch — the repro contract the
    * failure messages rely on. */
  @Test def sameSeedReproduces(): Unit = {
    val seed = 123456789L
    val a = ExprGen.generate(new Rng(seed))
    val b = ExprGen.generate(new Rng(seed))
    assertEquals("expr", a.expr, b.expr) // Expr nodes are case classes -> structural equality
    assertEquals("schema", a.batch.schema, b.batch.schema)
    assertEquals("numRows", a.batch.numRows, b.batch.numRows)
    val n = a.batch.numRows
    var col = 0
    while (col < a.batch.schema.length) {
      var i = 0
      while (i < n) {
        val av = if (a.batch.column(col).isNull(i)) null else a.batch.column(col).getBoxed(i)
        val bv = if (b.batch.column(col).isNull(i)) null else b.batch.column(col).getBoxed(i)
        assertEquals(s"cell ($col,$i)", av, bv)
        i += 1
      }
      col += 1
    }
  }

  /** The generator's own type invariant holds across a block of seeds:
    * `generate` asserts `expr.dataType == target` internally, so a violation
    * would throw here. */
  @Test def generatorTypeInvariantHolds(): Unit = {
    var seed = 0L
    while (seed < 500L) {
      val c = ExprGen.generate(new Rng(seed))
      assertTrue(s"non-null expr for seed $seed", c.expr.dataType != null)
      seed += 1
    }
  }

  /** A fixed composite that exercises arithmetic, ABS, COALESCE, an ordering
    * comparison, AND/OR, LIKE, and IS NULL together — a deterministic smoke
    * independent of the seed budget. */
  @Test def regressionComposite(): Unit = {
    val n = 6
    val a = ColumnVector.allocate(DataType.IntType, n)
    Seq[Any](0, 1, 2, 3, null, 5).zipWithIndex.foreach {
      case (null, i) => a.setNull(i)
      case (v, i) => a.setBoxed(i, v)
    }
    val s = ColumnVector.allocate(DataType.StringType, n)
    Seq[Any]("foo", "bar", null, "fizz", "foobar", "f").zipWithIndex.foreach {
      case (null, i) => s.setNull(i)
      case (v, i) => s.setBoxed(i, v)
    }
    val schema = Schema(Vector(Field("a", DataType.IntType), Field("s", DataType.StringType)))
    val batch = ColumnarBatch.fromColumns(schema, Array(a, s), n)

    val aRef = ColRefExpr(0, "a", DataType.IntType)
    val sRef = ColRefExpr(1, "s", DataType.StringType)
    // COALESCE(ABS(a - 3), 0) > 2
    val absDiff = FuncExpr("ABS",
      Seq(BinOpExpr("-", aRef, LitExpr(3, DataType.IntType), DataType.IntType)), DataType.IntType)
    val coalesced = FuncExpr("COALESCE",
      Seq(absDiff, LitExpr(0, DataType.IntType)), DataType.IntType)
    val gt = BinOpExpr(">", coalesced, LitExpr(2, DataType.IntType), DataType.BooleanType)
    // s LIKE 'f%' OR s IS NULL
    val like = LikeExpr(sRef, LitExpr("f%", DataType.StringType), negated = false)
    val isNull = IsNullExpr(sRef, negated = false)
    val or = BinOpExpr("OR", like, isNull, DataType.BooleanType)
    val composite = BinOpExpr("AND", gt, or, DataType.BooleanType)

    ExprParity.check(composite, batch)
  }

  /** The shrinker collapses a tall tree toward a minimal failing leaf — a unit
    * check on the harness itself, using an always-failing predicate so we can
    * observe the minimized result rather than the (passing) parity property. */
  @Test def shrinkerMinimizesToLeaf(): Unit = {
    val deep = BinOpExpr("+",
      BinOpExpr("*", LitExpr(7, DataType.IntType), LitExpr(9, DataType.IntType), DataType.IntType),
      LitExpr(42, DataType.IntType),
      DataType.IntType)
    // Greedily follow the first strictly-smaller candidate to a fixpoint.
    var current: Expr = deep
    var progressed = true
    while (progressed) {
      progressed = false
      val it = Shrinker.expr(current)
      if (it.hasNext) { current = it.next(); progressed = true }
    }
    assertEquals("shrinks to a single node", 1, Shrinker.size(current))
    assertTrue("minimal node is a literal", current.isInstanceOf[LitExpr])
  }
}
