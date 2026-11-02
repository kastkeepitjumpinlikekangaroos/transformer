package com.transformer.fuzz.oracle

import com.transformer.core.ColumnarBatch
import com.transformer.fuzz.RowOracle
import com.transformer.sql.plan.{Expr, FuncExpr, FunctionCatalog}

import scala.util.control.NonFatal

/** A function of the catalog answers NULL exactly where its entry says it
  * does: by its NULL rule, or by its domain (`FunctionCatalog.Entry.explainsNull`),
  * so `SQRT` of a negative, `LN` of zero and `ASIN` past 1 are NULL, as a
  * division by zero is (plans/testing/05), and a function that answers NULL
  * over arguments its entry has an answer for is wrong too.
  *
  * The relations the SQL fuzzers hold the engine to compare it with itself,
  * so they cannot see a function that answers NaN where its domain says NULL
  * in every layout alike: this one reads the rule from the registry, the
  * data the function was written to, and asks every call a generated tree or
  * query makes on every row it is evaluated on. A row where an argument or
  * the call fails is skipped: what fails is `ExprParity`'s and the layout
  * oracles' to judge.
  */
object CatalogNulls {

  /** The calls the registry gives a NULL rule this oracle reads: its scalar
    * and flow functions. */
  private def checked(e: FunctionCatalog.Entry): Boolean =
    e.kind == FunctionCatalog.Kind.Scalar || e.kind == FunctionCatalog.Kind.Flow

  /** Every call of a catalog function in `expr`, evaluated on every row of
    * `batch`, answers NULL where and only where its entry explains a NULL.
    * Throws `AssertionError` naming the call, the row's arguments and both
    * answers. Returns how many (call, row) pairs it checked. */
  def check(expr: Expr, batch: ColumnarBatch): Int = {
    var checkedPairs = 0
    BoundPlan.subtree(expr).foreach {
      case f @ FuncExpr(name, args, _) =>
        FunctionCatalog.lookup(name).filter(checked).foreach { entry =>
          var row = 0
          while (row < batch.numRows) {
            evaluate(args, f, batch, row).foreach { case (values, answer) =>
              checkedPairs += 1
              val explained = entry.explainsNull(values)
              if ((answer == null) != explained.isDefined)
                throw new AssertionError(
                  s"[catalog-nulls] $name answers ${RowOracle.describe(answer)} over " +
                    s"${values.map(RowOracle.describe).mkString("(", ", ", ")")}, where its entry " +
                    explained.fold("explains no NULL")(why => s"says NULL ($why)") + s"\n  call = $f")
            }
            row += 1
          }
        }
      case _ => ()
    }
    checkedPairs
  }

  /** The values of `args` and of `call` on `row`, or None where one fails. */
  private def evaluate(args: Seq[Expr], call: Expr, batch: ColumnarBatch, row: Int): Option[(Seq[Any], Any)] =
    try Some((args.map(_.eval(batch, row)), call.eval(batch, row)))
    catch { case NonFatal(_) => None }
}
