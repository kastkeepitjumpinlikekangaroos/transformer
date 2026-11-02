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
  private final case class Threw(error: Throwable) extends Outcome {
    def cls: Class[_] = error.getClass
  }

  def check(expr: Expr, batch: ColumnarBatch): Unit = {
    val vecResult: Either[Throwable, ColumnVector] =
      try Right(expr.evalVec(batch))
      catch { case NonFatal(t) => Left(t) }

    vecResult match {
      case Right(vec) => checkColumn(expr, batch, vec)
      case Left(vecError) => checkVecThrew(expr, batch, vecError)
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
        case thrown @ Threw(_) =>
          throw new AssertionError(
            s"divergence at row $i for $expr (dataType=${expr.dataType}): " +
              s"eval threw ${thrown.cls.getName} but evalVec produced ${RowOracle.describe(byVec)}")
      }
      i += 1
    }
  }

  /** `evalVec` threw for the whole batch: agreement requires the row path to
    * also throw, with the same class, at the first row where it throws, or
    * both to fail on a value ([[owned]]): with two such failures on different
    * rows, an overflow in one operand and a negative SUBSTRING length in
    * another, the vector path meets them operand by operand and the row path
    * row by row, so each may report another, and the query fails either way. */
  private def checkVecThrew(expr: Expr, batch: ColumnarBatch, vecError: Throwable): Unit = {
    val vecExClass = vecError.getClass
    val n = batch.numRows
    var i = 0
    while (i < n) {
      evalOutcome(expr, batch, i) match {
        case thrown @ Threw(_) =>
          if (thrown.cls != vecExClass && !(owned(vecError) && owned(thrown.error))) {
            throw new AssertionError(
              s"divergence at row $i for $expr: evalVec threw ${vecExClass.getName} " +
                s"but eval threw ${thrown.cls.getName}")
          }
          return // both throw at the first throwing row -> agree
        case Produced(_) =>
          () // vec aborted before materializing this row; cannot compare it
      }
      i += 1
    }
    throw new AssertionError(
      s"divergence for $expr: evalVec threw ${vecExClass.getName} but eval never throws (row path is total)")
  }

  /** A tree the generator built fails only where its own values do: an INT
    * or BIGINT result past its type, or a CAST of NaN or an infinity to an
    * integer, each an `ArithmeticException` (plans/robustness/18), as are a
    * DOUBLE past a FLOAT's range under a CAST and SIGN of NaN
    * (plans/robustness/30), a negative SUBSTRING length and a LIKE pattern
    * that ends in its escape character (plans/robustness/23), and the CASTs
    * that can fail the generator draws on purpose (text to a type other than
    * INT, a float to an integer or a DECIMAL, a DATE to a TIMESTAMP past its
    * range: plans/testing/15), which [[check]] holds the two paths to agree
    * on. Nothing else may throw. The
    * generator's other partial shapes are CASTs of a guard column under a
    * conditional that keeps them off the rows they fail on, so any other throw
    * means a conditional evaluated an operand where its answer did not need
    * it — which [[check]] cannot see when both paths do so (`IF` and
    * `COALESCE` once read every argument on both). */
  def checkTotal(expr: Expr, batch: ColumnarBatch): Unit = {
    try expr.evalVec(batch)
    catch {
      case NonFatal(t) if owned(t) => ()
      case NonFatal(t) => throw new AssertionError(s"evalVec threw on a total tree $expr: $t", t)
    }
    var i = 0
    while (i < batch.numRows) {
      try expr.eval(batch, i)
      catch {
        case NonFatal(t) if owned(t) => ()
        case NonFatal(t) => throw new AssertionError(s"eval threw at row $i of a total tree $expr: $t", t)
      }
      i += 1
    }
  }

  /** A failure on a value a generated tree's own data can cause, which
    * [[checkTotal]] allows and [[checkVecThrew]] lets the two paths report
    * differently: an `ArithmeticException`, or an `IllegalArgumentException`
    * whose message is one of `CleanRejection.DataErrors`, which lists every
    * failure about a value (a CAST that cannot read its input, a date past
    * its range, a negative SUBSTRING length, a LIKE pattern ending in its
    * escape), so the two lists cannot drift apart, but a CAST of text to INT,
    * the guarded shapes' ([[TextToInt]]). The semantics tables hold every
    * run-time error a function declares to it. */
  def owned(t: Throwable): Boolean = t match {
    case _: ArithmeticException => true
    case e: IllegalArgumentException =>
      CleanRejection.isDataMessage(e.getMessage) && !TextToInt.matches(String.valueOf(e.getMessage))
    case _ => false
  }

  /** The failure of a CAST of text to INT, which only the guarded shapes
    * draw: a generated tree that fails this way evaluated a guarded CAST on a
    * row its guard keeps it off, so it is never owned. The CASTs of text the
    * generator draws unguarded name another type. */
  private val TextToInt = "^Cannot cast '.*' to int$".r

  private def evalOutcome(expr: Expr, batch: ColumnarBatch, row: Int): Outcome =
    try Produced(expr.eval(batch, row))
    catch { case NonFatal(t) => Threw(t) }
}
