package com.transformer.fuzz

import com.transformer.core.DataType

/** Scalar normalization + comparison shared by the parity oracles.
  *
  * This lifts the `normalize` / value-compare logic from
  * `com.transformer.sql.plan.ExprBatchTest` — the hand-written precedent this
  * harness generalizes — into one reusable place. The row path
  * (`Expr.eval`) returns the operand's declared boxed shape (e.g. a
  * `java.lang.Double` for arithmetic on `FloatType`, since `Ops.arith` widens
  * to double); the vector path (`Expr.evalVec`) returns a vector typed by
  * `expr.dataType` whose `getBoxed` matches that schema type. [[normalize]]
  * coerces both to the declared `dataType` so a `Double` from `eval` and a
  * `Float` stored by `evalVec` compare equal.
  *
  * Comparison is EXACT after normalization, not tolerance-based: the two paths
  * run bit-identical arithmetic (float ops compute in double then narrow on
  * both sides; non-overridden funcs route `evalVec` through `eval`), so any
  * value difference is a real divergence, never a rounding artifact. A
  * tolerance would mask exactly the bugs this fuzzer exists to find.
  */
object RowOracle {

  /** Coerce a boxed value to the shape implied by `dt`, so cross-boxing between
    * the row and vector paths (Float vs Double, Int vs Long, LocalDate vs
    * epoch-day) does not register as a mismatch. NULL passes through. */
  def normalize(v: Any, dt: DataType): Any = {
    if (v == null) return null
    dt match {
      case DataType.IntType => v.asInstanceOf[Number].intValue
      case DataType.LongType => v.asInstanceOf[Number].longValue
      case DataType.FloatType => v.asInstanceOf[Number].floatValue
      case DataType.DoubleType => v.asInstanceOf[Number].doubleValue
      case DataType.BooleanType =>
        v match {
          case b: Boolean => b
          case b: java.lang.Boolean => b.booleanValue
          case _ => v
        }
      case DataType.DateType =>
        v match {
          case d: java.time.LocalDate => d.toEpochDay.toInt
          case n: Number => n.intValue
          case _ => v
        }
      case DataType.TimestampType =>
        v match {
          case i: java.time.Instant => i.getEpochSecond * 1000000L + i.getNano / 1000L
          case n: Number => n.longValue
          case _ => v
        }
      case _ => v
    }
  }

  /** NULL-aware exact equality of two boxed values once both are normalized to
    * `dt`. Both NULL is equal; one NULL is not.
    *
    * Float/Double use `compare`, not `==`: when both paths produce `NaN` they
    * AGREE, but Scala's `==` on boxed doubles is primitive `NaN == NaN` (false).
    * `compare` treats `NaN == NaN` as equal while still distinguishing `-0.0`
    * from `0.0`, so a genuine bit-level divergence is still caught. */
  def scalarEquals(a: Any, b: Any, dt: DataType): Boolean = {
    val na = normalize(a, dt)
    val nb = normalize(b, dt)
    if (na == null || nb == null) return na == null && nb == null
    (na, nb) match {
      case (x: java.lang.Double, y: java.lang.Double) => java.lang.Double.compare(x, y) == 0
      case (x: java.lang.Float, y: java.lang.Float) => java.lang.Float.compare(x, y) == 0
      case _ => na == nb
    }
  }

  /** Render a boxed value with its runtime class for failure messages. */
  def describe(v: Any): String =
    if (v == null) "null" else s"$v (${v.getClass.getName})"
}
