package com.transformer.sql.plan

import com.transformer.core._

/** One sort key's order over typed vectors: [[SortKey.compare]]'s typed twin,
  * which [[SortKey.order]] builds for the key's type. `compare(a, i, b, j)`
  * orders row `i` of `a` against row `j` of `b`, two vectors of the key's
  * type (a NULL-typed key's are never read), with the sign `SortKey.compare`
  * gives their boxed values: a NULL goes where `nullsFirst` says, whatever the
  * direction, two NULLs tie, and DESC swaps the operands. Each type's values
  * compare through the primitive `Ops.cmp` uses for them (`Ops.floatCompare`,
  * `Ops.stringCompare`, `Ops.binaryCompare`, `Ops.decimalCompare`, or
  * `compare` on the int or long a vector stores), so no type's order is
  * written twice, and nothing is boxed. `sort_key_order_test` holds the two
  * forms equal on every type. Several keys compare one after another, the
  * first that differs deciding. */
abstract class KeyOrder(val asc: Boolean, val nullsFirst: Boolean) {

  def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int

  /** Where a NULL goes, given that `aNull` or `bNull` is. */
  protected final def nulls(aNull: Boolean, bNull: Boolean): Int =
    if (aNull) { if (bNull) 0 else if (nullsFirst) -1 else 1 }
    else if (nullsFirst) 1 else -1
}

object KeyOrder {

  /** The order of a key of type `t`. */
  def apply(t: DataType, asc: Boolean, nullsFirst: Boolean): KeyOrder = t match {
    case DataType.IntType => new IntOrder(asc, nullsFirst)
    case DataType.LongType => new LongOrder(asc, nullsFirst)
    case DataType.FloatType => new FloatOrder(asc, nullsFirst)
    case DataType.DoubleType => new DoubleOrder(asc, nullsFirst)
    case DataType.BooleanType => new BooleanOrder(asc, nullsFirst)
    case DataType.DateType => new DateOrder(asc, nullsFirst)
    case DataType.TimestampType => new TimestampOrder(asc, nullsFirst)
    case DataType.StringType => new StringOrder(asc, nullsFirst)
    case DataType.BinaryType => new BinaryOrder(asc, nullsFirst)
    case _: DataType.DecimalType => new DecimalOrder(asc, nullsFirst)
    case DataType.NullType => new NullOrder(asc, nullsFirst)
  }

  /** INT: both integral, so `Ops.cmp` compares the longs they widen to,
    * which order as the ints do. */
  private final class IntOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[IntVector]
      val y = b.asInstanceOf[IntVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) Integer.compare(x.values(i), y.values(j))
      else Integer.compare(y.values(j), x.values(i))
    }
  }

  private final class LongOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[LongVector]
      val y = b.asInstanceOf[LongVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) java.lang.Long.compare(x.values(i), y.values(j))
      else java.lang.Long.compare(y.values(j), x.values(i))
    }
  }

  /** FLOAT: `Ops.cmp` compares the doubles the floats widen to, exactly. */
  private final class FloatOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[FloatVector]
      val y = b.asInstanceOf[FloatVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) Ops.floatCompare(x.values(i).toDouble, y.values(j).toDouble)
      else Ops.floatCompare(y.values(j).toDouble, x.values(i).toDouble)
    }
  }

  private final class DoubleOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[DoubleVector]
      val y = b.asInstanceOf[DoubleVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) Ops.floatCompare(x.values(i), y.values(j))
      else Ops.floatCompare(y.values(j), x.values(i))
    }
  }

  /** BOOLEAN: false first, as `Boolean.compareTo` has it. */
  private final class BooleanOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[BooleanVector]
      val y = b.asInstanceOf[BooleanVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) java.lang.Boolean.compare(x.values(i), y.values(j))
      else java.lang.Boolean.compare(y.values(j), x.values(i))
    }
  }

  /** DATE: days since the epoch, which order as the dates do. */
  private final class DateOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[DateVector]
      val y = b.asInstanceOf[DateVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) Integer.compare(x.values(i), y.values(j))
      else Integer.compare(y.values(j), x.values(i))
    }
  }

  /** TIMESTAMP: microseconds since the epoch, which order as the instants
    * they hold exactly do. */
  private final class TimestampOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[TimestampVector]
      val y = b.asInstanceOf[TimestampVector]
      val xn = x.nulls.get(i)
      val yn = y.nulls.get(j)
      if (xn || yn) nulls(xn, yn)
      else if (asc) java.lang.Long.compare(x.values(i), y.values(j))
      else java.lang.Long.compare(y.values(j), x.values(i))
    }
  }

  private final class StringOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[StringVector].values(i)
      val y = b.asInstanceOf[StringVector].values(j)
      if (x == null || y == null) nulls(x == null, y == null)
      else if (asc) Ops.stringCompare(x, y)
      else Ops.stringCompare(y, x)
    }
  }

  private final class BinaryOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[BinaryVector].values(i)
      val y = b.asInstanceOf[BinaryVector].values(j)
      if (x == null || y == null) nulls(x == null, y == null)
      else if (asc) Ops.binaryCompare(x, y)
      else Ops.binaryCompare(y, x)
    }
  }

  /** DECIMAL: by value, whatever the scale a vector holds a value at. */
  private final class DecimalOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = {
      val x = a.asInstanceOf[DecimalVector].values(i)
      val y = b.asInstanceOf[DecimalVector].values(j)
      if (x == null || y == null) nulls(x == null, y == null)
      else if (asc) Ops.decimalCompare(x, y)
      else Ops.decimalCompare(y, x)
    }
  }

  /** NULL: every value is NULL, and two NULLs tie, so neither vector is read;
    * a spill run stores such a column as a BOOLEAN with no values
    * (`Spill.storedType`). */
  private final class NullOrder(asc: Boolean, nullsFirst: Boolean) extends KeyOrder(asc, nullsFirst) {
    def compare(a: ColumnVector, i: Int, b: ColumnVector, j: Int): Int = 0
  }
}
