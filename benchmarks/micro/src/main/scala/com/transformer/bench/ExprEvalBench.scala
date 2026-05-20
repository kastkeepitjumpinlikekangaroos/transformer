package com.transformer.bench

import com.transformer.core._
import com.transformer.sql.plan._

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Microbenchmarks for [[Expr.evalVec]] on the hot subset of expression
  * subtypes. Compares the vectorized override (each subtype's
  * `override def evalVec`) against the default per-row fallback which
  * loops `eval` and writes via `setBoxed`.
  *
  * The point isn't to compare absolute numbers — it's to make sure the
  * gap between the vectorized and the boxed paths stays wide. Plan 03
  * (vectorized expression evaluation) established the gap; this
  * benchmark is the regression watchpoint.
  *
  * `Expr` is a sealed trait, so we can't introduce a wrapper subclass to
  * force the default path. Instead, the boxed-loop variants below
  * re-implement the default `Expr.evalVec` body inline against the same
  * expression node: we call `expr.eval(batch, row)` in a per-row loop and
  * write the boxed result via `setBoxed`. That measures the same per-row
  * dispatch + boxing cost the default path pays, against the same
  * expression subtree the vectorized variant evaluates.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class ExprEvalBench {

  private final val BatchRows: Int = 1024

  private var batch: ColumnarBatch = _

  // Vectorized expressions — the override path.
  private var litLong: Expr = _
  private var colLong: Expr = _
  private var arithBinOp: Expr = _
  private var compareBinOp: Expr = _
  private var isNullExpr: Expr = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    batch = BenchFixtures.fixedWidthBatch(BatchRows, seed = 0xABCDEFL)

    litLong = LitExpr(7L, DataType.LongType)
    colLong = ColRefExpr(0, "k", DataType.LongType)
    // (k + 1) — typical Project expression. Both sides are LongType.
    arithBinOp = BinOpExpr("+", colLong, LitExpr(1L, DataType.LongType), DataType.LongType)
    // (k < 0) — typical Filter predicate.
    compareBinOp = BinOpExpr("<", colLong, LitExpr(0L, DataType.LongType), DataType.BooleanType)
    // IS NOT NULL over the v column.
    isNullExpr = IsNullExpr(ColRefExpr(1, "v", DataType.DoubleType), negated = true)
  }

  /** Vectorized [[LitExpr.evalVec]] — broadcast the constant via
    * `Arrays.fill`. */
  @Benchmark
  def litVec(bh: Blackhole): Unit = {
    bh.consume(litLong.evalVec(batch))
  }

  /** Vectorized [[ColRefExpr.evalVec]] — zero-copy alias. */
  @Benchmark
  def colRefVec(bh: Blackhole): Unit = {
    bh.consume(colLong.evalVec(batch))
  }

  /** Vectorized [[BinOpExpr.evalVec]] for arithmetic, dispatched into
    * `VecOps.arith`. */
  @Benchmark
  def binOpArithVec(bh: Blackhole): Unit = {
    bh.consume(arithBinOp.evalVec(batch))
  }

  /** Vectorized [[BinOpExpr.evalVec]] for comparison, dispatched into
    * `VecOps.compare`. */
  @Benchmark
  def binOpCompareVec(bh: Blackhole): Unit = {
    bh.consume(compareBinOp.evalVec(batch))
  }

  /** Vectorized [[IsNullExpr.evalVec]] — typically a BitSet walk of
    * the child's null mask. */
  @Benchmark
  def isNullVec(bh: Blackhole): Unit = {
    bh.consume(isNullExpr.evalVec(batch))
  }

  /** Boxed fallback for the literal — what `Expr.evalVec`'s default body
    * does when a subtype doesn't override. Inlined here against
    * `litLong.eval(batch, row)` so we A/B the vectorized override
    * against the per-row dispatch + boxing cost on the same node. */
  @Benchmark
  def litBoxed(bh: Blackhole): Unit = {
    bh.consume(ExprEvalBench.boxedLoop(litLong, batch))
  }

  /** Boxed fallback for arithmetic. Same shape as `litBoxed` but the
    * inner `eval` call goes through `BinOpExpr.eval` (which itself
    * recurses into the child expressions per row). */
  @Benchmark
  def binOpArithBoxed(bh: Blackhole): Unit = {
    bh.consume(ExprEvalBench.boxedLoop(arithBinOp, batch))
  }

  /** Boxed fallback for IS NOT NULL. */
  @Benchmark
  def isNullBoxed(bh: Blackhole): Unit = {
    bh.consume(ExprEvalBench.boxedLoop(isNullExpr, batch))
  }
}

private[bench] object ExprEvalBench {
  /** Mirror of [[Expr.evalVec]]'s default per-row body. Implements the
    * same allocate / loop / setBoxed pattern (see `Expr.scala`) so the
    * benchmark measures the boxed-path cost without requiring a
    * subclass of the sealed `Expr` trait. */
  def boxedLoop(expr: Expr, batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val out = ColumnVector.allocate(expr.dataType, math.max(1, n))
    var i = 0
    while (i < n) {
      val v = expr.eval(batch, i)
      if (v == null) out.setNull(i) else out.setBoxed(i, v)
      i += 1
    }
    out
  }
}
