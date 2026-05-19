package com.transformer.core

import java.util

/** Columnar in-memory representation. One [[ColumnarBatch]] holds up to `capacity`
  * rows. `numRows` is the number of logically valid rows in the batch.
  *
  * Operators receive batches, produce batches. Numeric columns use unboxed
  * primitive arrays; reference columns (string, binary, decimal) store nulls
  * inline as `null`.
  */
final class ColumnarBatch private (
    val schema: Schema,
    val capacity: Int,
    val columns: Array[ColumnVector]) {
  require(capacity > 0, s"capacity must be positive, got $capacity")
  require(columns.length == schema.length,
    s"columns.length ${columns.length} != schema.length ${schema.length}")

  /** Allocate a fresh batch with empty (default-allocated) columns. */
  def this(schema: Schema, capacity: Int) =
    this(schema, capacity, Array.tabulate(schema.length)(i =>
      ColumnVector.allocate(schema.fields(i).dataType, capacity)))

  private var _numRows: Int = 0

  def numRows: Int = _numRows
  def setNumRows(n: Int): Unit = {
    require(n >= 0 && n <= capacity, s"numRows $n out of range [0,$capacity]")
    _numRows = n
  }
  def incrementRows(): Int = { _numRows += 1; _numRows }

  def column(i: Int): ColumnVector = columns(i)
  def column(name: String): ColumnVector = columns(schema.indexOf(name))

  /** Materialize row `i` as a plain [[Row]] (boxed). Used at API boundaries only. */
  def rowAt(i: Int): Row = {
    require(i >= 0 && i < _numRows, s"row $i out of range [0,${_numRows})")
    val arr = new Array[Any](schema.length)
    var c = 0
    while (c < schema.length) {
      arr(c) = if (columns(c).isNull(i)) null else columns(c).getBoxed(i)
      c += 1
    }
    Row(schema, arr)
  }

  /** Returns a new batch containing only the rows indicated by `mask` (length must equal numRows). */
  def select(mask: Array[Boolean]): ColumnarBatch = {
    require(mask.length == _numRows, s"mask length ${mask.length} != numRows ${_numRows}")
    var kept = 0
    var i = 0
    while (i < _numRows) { if (mask(i)) kept += 1; i += 1 }
    val out = new ColumnarBatch(schema, math.max(1, kept))
    var c = 0
    while (c < schema.length) {
      val src = columns(c)
      val dst = out.columns(c)
      var srcRow = 0
      var dstRow = 0
      while (srcRow < _numRows) {
        if (mask(srcRow)) {
          src.copyTo(srcRow, dst, dstRow)
          dstRow += 1
        }
        srcRow += 1
      }
      c += 1
    }
    out.setNumRows(kept)
    out
  }
}

object ColumnarBatch {
  /** Capacity of one batch in rows. Larger batches amortize per-batch overhead
    * (operator dispatch, writer flush boundaries, iterator hasNext checks) at
    * the cost of more memory held in flight per partition. 8192 is comfortable
    * for the wide parquet schemas this library targets — N concurrent writers
    * × 8192 rows × ~12 columns × 8B ≈ low MB of in-flight batch memory. Bump
    * higher only after profiling.
    */
  val DefaultCapacity: Int = 8192

  /** Build a batch around pre-built column vectors. Capacity is the smallest
    * column capacity across all columns (each column must hold at least
    * `numRows` valid rows). Used by vectorized [[ProjectExec]] to assemble
    * output directly from `Expr.evalVec` results without copying.
    */
  def fromColumns(schema: Schema, columns: Array[ColumnVector], numRows: Int): ColumnarBatch = {
    require(columns.length == schema.length,
      s"columns.length ${columns.length} != schema.length ${schema.length}")
    val cap = if (columns.length == 0) math.max(1, numRows) else {
      var m = Int.MaxValue
      var i = 0
      while (i < columns.length) {
        if (columns(i).capacity < m) m = columns(i).capacity
        i += 1
      }
      m
    }
    require(numRows >= 0 && numRows <= cap,
      s"numRows $numRows out of range [0,$cap]")
    val b = new ColumnarBatch(schema, cap, columns)
    b.setNumRows(numRows)
    b
  }
}

/** Sealed ADT for typed columns. Operators dispatch by pattern-matching once per batch,
  * not per row.
  */
sealed trait ColumnVector {
  def dataType: DataType
  def capacity: Int
  def isNull(row: Int): Boolean
  def setNull(row: Int): Unit
  def getBoxed(row: Int): Any
  def setBoxed(row: Int, value: Any): Unit
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit
}

object ColumnVector {
  def allocate(dt: DataType, capacity: Int): ColumnVector = dt match {
    case DataType.IntType       => new IntVector(new Array[Int](capacity), new util.BitSet(capacity), capacity)
    case DataType.LongType      => new LongVector(new Array[Long](capacity), new util.BitSet(capacity), capacity)
    case DataType.FloatType     => new FloatVector(new Array[Float](capacity), new util.BitSet(capacity), capacity)
    case DataType.DoubleType    => new DoubleVector(new Array[Double](capacity), new util.BitSet(capacity), capacity)
    case DataType.BooleanType   => new BooleanVector(new Array[Boolean](capacity), new util.BitSet(capacity), capacity)
    case DataType.StringType    => new StringVector(new Array[String](capacity), capacity)
    case DataType.BinaryType    => new BinaryVector(new Array[Array[Byte]](capacity), capacity)
    case DataType.DateType      => new DateVector(new Array[Int](capacity), new util.BitSet(capacity), capacity)
    case DataType.TimestampType => new TimestampVector(new Array[Long](capacity), new util.BitSet(capacity), capacity)
    case DataType.NullType      => new NullVectorImpl(capacity)
    case d: DataType.DecimalType => new DecimalVector(new Array[java.math.BigDecimal](capacity), d.precision, d.scale, capacity)
  }
}

final class IntVector(val values: Array[Int], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.IntType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = 0 }
  def set(row: Int, v: Int): Unit = { nulls.clear(row); values(row) = v }
  def get(row: Int): Int = values(row)
  def getBoxed(row: Int): Any = if (isNull(row)) null else java.lang.Integer.valueOf(values(row))
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case i: Int => set(row, i)
    case n: Number => set(row, n.intValue)
    case s: String => set(row, s.toInt)
    case other => throw new IllegalArgumentException(s"Cannot set IntVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: IntVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class LongVector(val values: Array[Long], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.LongType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = 0L }
  def set(row: Int, v: Long): Unit = { nulls.clear(row); values(row) = v }
  def get(row: Int): Long = values(row)
  def getBoxed(row: Int): Any = if (isNull(row)) null else java.lang.Long.valueOf(values(row))
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case l: Long => set(row, l)
    case n: Number => set(row, n.longValue)
    case s: String => set(row, s.toLong)
    case other => throw new IllegalArgumentException(s"Cannot set LongVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: LongVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class FloatVector(val values: Array[Float], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.FloatType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = 0f }
  def set(row: Int, v: Float): Unit = { nulls.clear(row); values(row) = v }
  def get(row: Int): Float = values(row)
  def getBoxed(row: Int): Any = if (isNull(row)) null else java.lang.Float.valueOf(values(row))
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case f: Float => set(row, f)
    case n: Number => set(row, n.floatValue)
    case s: String => set(row, s.toFloat)
    case other => throw new IllegalArgumentException(s"Cannot set FloatVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: FloatVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class DoubleVector(val values: Array[Double], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.DoubleType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = 0.0 }
  def set(row: Int, v: Double): Unit = { nulls.clear(row); values(row) = v }
  def get(row: Int): Double = values(row)
  def getBoxed(row: Int): Any = if (isNull(row)) null else java.lang.Double.valueOf(values(row))
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case d: Double => set(row, d)
    case n: Number => set(row, n.doubleValue)
    case s: String => set(row, s.toDouble)
    case other => throw new IllegalArgumentException(s"Cannot set DoubleVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: DoubleVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class BooleanVector(val values: Array[Boolean], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.BooleanType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = false }
  def set(row: Int, v: Boolean): Unit = { nulls.clear(row); values(row) = v }
  def get(row: Int): Boolean = values(row)
  def getBoxed(row: Int): Any = if (isNull(row)) null else java.lang.Boolean.valueOf(values(row))
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case b: Boolean => set(row, b)
    case s: String => set(row, java.lang.Boolean.parseBoolean(s))
    case other => throw new IllegalArgumentException(s"Cannot set BooleanVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: BooleanVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class StringVector(val values: Array[String], val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.StringType
  def isNull(row: Int): Boolean = values(row) == null
  def setNull(row: Int): Unit = { values(row) = null }
  def set(row: Int, v: String): Unit = { values(row) = v }
  def get(row: Int): String = values(row)
  def getBoxed(row: Int): Any = values(row)
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case s: String => set(row, s)
    case other => set(row, other.toString)
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: StringVector => d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class BinaryVector(val values: Array[Array[Byte]], val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.BinaryType
  def isNull(row: Int): Boolean = values(row) == null
  def setNull(row: Int): Unit = { values(row) = null }
  def set(row: Int, v: Array[Byte]): Unit = { values(row) = v }
  def get(row: Int): Array[Byte] = values(row)
  def getBoxed(row: Int): Any = values(row)
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case b: Array[Byte] => set(row, b)
    case other => throw new IllegalArgumentException(s"Cannot set BinaryVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: BinaryVector => d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

/** Date stored as days since the Unix epoch (1970-01-01). */
final class DateVector(val values: Array[Int], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.DateType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = 0 }
  def set(row: Int, daysSinceEpoch: Int): Unit = { nulls.clear(row); values(row) = daysSinceEpoch }
  def get(row: Int): Int = values(row)
  def getBoxed(row: Int): Any =
    if (isNull(row)) null else java.time.LocalDate.ofEpochDay(values(row).toLong)
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case d: java.time.LocalDate => set(row, d.toEpochDay.toInt)
    case i: Int => set(row, i)
    case s: String => set(row, java.time.LocalDate.parse(s).toEpochDay.toInt)
    case other => throw new IllegalArgumentException(s"Cannot set DateVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: DateVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

/** Timestamp stored as microseconds since the Unix epoch (UTC). */
final class TimestampVector(val values: Array[Long], val nulls: util.BitSet, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.TimestampType
  def isNull(row: Int): Boolean = nulls.get(row)
  def setNull(row: Int): Unit = { nulls.set(row); values(row) = 0L }
  def set(row: Int, microsSinceEpoch: Long): Unit = { nulls.clear(row); values(row) = microsSinceEpoch }
  def get(row: Int): Long = values(row)
  def getBoxed(row: Int): Any =
    if (isNull(row)) null
    else java.time.Instant.ofEpochSecond(values(row) / 1000000L, (values(row) % 1000000L) * 1000L)
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case i: java.time.Instant => set(row, i.getEpochSecond * 1000000L + i.getNano / 1000L)
    case l: Long => set(row, l)
    case s: String => set(row, {
      val i = java.time.Instant.parse(s)
      i.getEpochSecond * 1000000L + i.getNano / 1000L
    })
    case other => throw new IllegalArgumentException(s"Cannot set TimestampVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: TimestampVector =>
      if (isNull(srcRow)) d.setNull(dstRow) else d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

final class DecimalVector(val values: Array[java.math.BigDecimal], val precision: Int, val scale: Int, val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.DecimalType(precision, scale)
  def isNull(row: Int): Boolean = values(row) == null
  def setNull(row: Int): Unit = { values(row) = null }
  def set(row: Int, v: java.math.BigDecimal): Unit = { values(row) = v }
  def get(row: Int): java.math.BigDecimal = values(row)
  def getBoxed(row: Int): Any = values(row)
  def setBoxed(row: Int, value: Any): Unit = value match {
    case null => setNull(row)
    case bd: java.math.BigDecimal => set(row, bd)
    case s: String => set(row, new java.math.BigDecimal(s))
    case n: Number => set(row, java.math.BigDecimal.valueOf(n.doubleValue))
    case other => throw new IllegalArgumentException(s"Cannot set DecimalVector from ${other.getClass.getName}")
  }
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst match {
    case d: DecimalVector => d.set(dstRow, values(srcRow))
    case other => other.setBoxed(dstRow, getBoxed(srcRow))
  }
}

/** Column with no data (all nulls). Used for the NULL literal type. */
final class NullVectorImpl(val capacity: Int) extends ColumnVector {
  def dataType: DataType = DataType.NullType
  def isNull(row: Int): Boolean = true
  def setNull(row: Int): Unit = ()
  def getBoxed(row: Int): Any = null
  def setBoxed(row: Int, value: Any): Unit = ()
  def copyTo(srcRow: Int, dst: ColumnVector, dstRow: Int): Unit = dst.setNull(dstRow)
}
