package com.transformer.fuzz.oracle

import com.transformer.core.{ColumnVector, ColumnarBatch}
import com.transformer.fuzz.RowOracle
import com.transformer.sql.plan.Expr

import scala.util.control.NonFatal

/** The parity property for one (`Expr`, `ColumnarBatch`) pair: the per-row
  * `eval` path and the column-at-a-time `evalVec` path must AGREE on every row.
  *
  * This is the generated generalization of
  * `com.transformer.sql.plan.ExprBatchTest.assertParity`: it asserts
  * `evalVec(batch).dataType == expr.dataType`, then for each row compares
  * `eval(batch, i)` against `evalVec(batch).getBoxed(i)` after normalizing both
  * to the declared `dataType` (via [[RowOracle]], the lifted precedent logic).
  *
  * "Agree" means, per row: the same normalized value, both NULL, or both throw
  * the same exception class. The throw arm is defensive — [[com.transformer.fuzz.ExprGen]]
  * is total by construction, so for generated input neither path throws — but
  * it makes the oracle correct on its own terms and able to catch a regression
  * that introduces a throwing divergence. Because `evalVec` evaluates eagerly
  * over the whole batch while `eval` is per-row, a `evalVec` exception is
  * compared at batch granularity: it is agreement only if `eval` also throws
  * the same class at some row (and never cleanly disagrees before then).
  *
  * Failures throw `AssertionError` (so the harness stays free of a JUnit
  * dependency); [[com.transformer.fuzz.Props]] catches it, minimizes, and
  * reports the seed.
  */
object ExprParity {

  private sealed trait Outcome
  private final case class Produced(value: Any) extends Outcome
  private final case class Threw(cls: Class[_]) extends Outcome

  def check(expr: Expr, batch: ColumnarBatch): Unit = {
    val vecResult: Either[Class[_], ColumnVector] =
      try Right(expr.evalVec(batch))
      catch { case NonFatal(t) => Left(t.getClass) }

    vecResult match {
      case Right(vec) => checkColumn(expr, batch, vec)
      case Left(vecExClass) => checkVecThrew(expr, batch, vecExClass)
    }
  }

  /** `evalVec` produced a column: assert its type, then compare every row. */
  private def checkColumn(expr: Expr, batch: ColumnarBatch, vec: ColumnVector): Unit = {
    if (vec.dataType != expr.dataType) {
      throw new AssertionError(
        s"evalVec.dataType mismatch: expr.dataType=${expr.dataType} but vec.dataType=${vec.dataType} for $expr")
    }
    val n = batch.numRows
    var i = 0
    while (i < n) {
      val byVec: Any = if (vec.isNull(i)) null else vec.getBoxed(i)
      evalOutcome(expr, batch, i) match {
        case Produced(byEval) =>
          if (!RowOracle.scalarEquals(byEval, byVec, expr.dataType)) {
            throw new AssertionError(
              s"parity mismatch at row $i for $expr (dataType=${expr.dataType}): " +
                s"eval=${RowOracle.describe(byEval)} vs evalVec=${RowOracle.describe(byVec)}")
          }
        case Threw(cls) =>
          throw new AssertionError(
            s"divergence at row $i for $expr (dataType=${expr.dataType}): " +
              s"eval threw ${cls.getName} but evalVec produced ${RowOracle.describe(byVec)}")
      }
      i += 1
    }
  }

  /** `evalVec` threw for the whole batch: agreement requires the row path to
    * also throw, with the same class, at the first row where it throws. */
  private def checkVecThrew(expr: Expr, batch: ColumnarBatch, vecExClass: Class[_]): Unit = {
    val n = batch.numRows
    var i = 0
    while (i < n) {
      evalOutcome(expr, batch, i) match {
        case Threw(cls) =>
          if (cls != vecExClass) {
            throw new AssertionError(
              s"divergence at row $i for $expr: evalVec threw ${vecExClass.getName} " +
                s"but eval threw ${cls.getName}")
          }
          return // both throw the same class at the first throwing row -> agree
        case Produced(_) =>
          () // vec aborted before materializing this row; cannot compare it
      }
      i += 1
    }
    throw new AssertionError(
      s"divergence for $expr: evalVec threw ${vecExClass.getName} but eval never throws (row path is total)")
  }

  private def evalOutcome(expr: Expr, batch: ColumnarBatch, row: Int): Outcome =
    try Produced(expr.eval(batch, row))
    catch { case NonFatal(t) => Threw(t.getClass) }
}
