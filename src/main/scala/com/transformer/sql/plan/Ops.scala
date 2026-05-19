package com.transformer.sql.plan

import com.transformer.core._

import java.util

/** Binary operator evaluation, plus NULL-safe equality and ordering helpers. */
object Ops {

  /** Evaluate `lv <op> rv`. Caller guarantees neither side is null. */
  def apply(op: String, lv: Any, rv: Any, lt: DataType, rt: DataType, out: DataType): Any = op match {
    case "+" => arith(lv, rv, out, Plus)
    case "-" => arith(lv, rv, out, Minus)
    case "*" => arith(lv, rv, out, Times)
    case "/" => arith(lv, rv, out, Div)
    case "%" => arith(lv, rv, out, Mod)
    case "="  | "==" => eq(lv, rv)
    case "<>" | "!=" => !eq(lv, rv)
    case "<"  => cmp(lv, rv) < 0
    case "<=" => cmp(lv, rv) <= 0
    case ">"  => cmp(lv, rv) > 0
    case ">=" => cmp(lv, rv) >= 0
    case "||" => lv.toString + rv.toString
    case other => throw new IllegalStateException(s"Unknown binary op '$other'")
  }

  // ---- Equality (type-coercing within numerics; exact for strings/booleans) ----
  def eq(a: Any, b: Any): Boolean = (a, b) match {
    case (x: java.lang.Number, y: java.lang.Number) =>
      x.doubleValue == y.doubleValue
    case (x: String, y: String) => x == y
    case (x: java.lang.Boolean, y: java.lang.Boolean) => x.booleanValue == y.booleanValue
    case (x, y) => x == y
  }

  def cmp(a: Any, b: Any): Int = (a, b) match {
    case (x: java.lang.Number, y: java.lang.Number) =>
      java.lang.Double.compare(x.doubleValue, y.doubleValue)
    case (x: String, y: String) => x.compareTo(y)
    case (x: java.lang.Boolean, y: java.lang.Boolean) => x.compareTo(y)
    case (x: java.lang.Comparable[Any] @unchecked, y) => x.compareTo(y)
    case (x, y) => x.toString.compareTo(y.toString)
  }

  private sealed trait Arith
  private object Plus extends Arith
  private object Minus extends Arith
  private object Times extends Arith
  private object Div extends Arith
  private object Mod extends Arith

  private def arith(lv: Any, rv: Any, out: DataType, op: Arith): Any = {
    out match {
      case DataType.IntType =>
        val l = lv.asInstanceOf[Number].intValue
        val r = rv.asInstanceOf[Number].intValue
        op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => if (r == 0) null else l / r
          case Mod => if (r == 0) null else l % r
        }
      case DataType.LongType =>
        val l = lv.asInstanceOf[Number].longValue
        val r = rv.asInstanceOf[Number].longValue
        op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => if (r == 0L) null else l / r
          case Mod => if (r == 0L) null else l % r
        }
      case DataType.FloatType | DataType.DoubleType =>
        val l = lv.asInstanceOf[Number].doubleValue
        val r = rv.asInstanceOf[Number].doubleValue
        op match {
          case Plus => l + r
          case Minus => l - r
          case Times => l * r
          case Div => l / r
          case Mod => l % r
        }
      case other => throw new IllegalStateException(s"Arithmetic on $other not supported")
    }
  }
}

/** Type-promoting casts at value level. Only invoked for non-null values. */
object Casts {
  def cast(v: Any, from: DataType, to: DataType): Any = {
    if (v == null) return null
    if (from == to) return v
    to match {
      case DataType.StringType => v.toString
      case DataType.IntType =>
        v match {
          case s: String => s.toInt
          case n: Number => n.intValue
          case b: Boolean => if (b) 1 else 0
          case _ => v.toString.toInt
        }
      case DataType.LongType =>
        v match {
          case s: String => s.toLong
          case n: Number => n.longValue
          case b: Boolean => if (b) 1L else 0L
          case _ => v.toString.toLong
        }
      case DataType.FloatType =>
        v match {
          case s: String => s.toFloat
          case n: Number => n.floatValue
          case _ => v.toString.toFloat
        }
      case DataType.DoubleType =>
        v match {
          case s: String => s.toDouble
          case n: Number => n.doubleValue
          case _ => v.toString.toDouble
        }
      case DataType.BooleanType =>
        v match {
          case b: Boolean => b
          case s: String => java.lang.Boolean.parseBoolean(s)
          case n: Number => n.doubleValue != 0.0
          case _ => v.toString.equalsIgnoreCase("true")
        }
      case DataType.DateType =>
        v match {
          case d: java.time.LocalDate => d.toEpochDay.toInt
          case s: String => java.time.LocalDate.parse(s).toEpochDay.toInt
          case n: Number => n.intValue
          case _ => throw new IllegalArgumentException(s"Cannot cast $v to date")
        }
      case other => throw new IllegalArgumentException(s"CAST to $other not implemented")
    }
  }
}

/** Column-at-a-time variants of [[Ops]]. Each helper allocates one output
  * vector and runs a single tight loop that reads from primitive arrays —
  * the JIT eliminates boxing once it has profiled the call site, and the
  * loops are shapes HotSpot autovectorizes on AVX. The price is more code
  * (one specialized loop per (op, output type) pair) but no codegen step.
  *
  * Generic fallbacks via `getBoxed` cover the cross-type combinations that
  * the analyzer doesn't normalize through an explicit CAST — slower than the
  * specialized paths but still vectorized at the per-Expr-node level (one
  * dispatch per batch, not per row).
  */
object VecOps {

  /** Vectorized arithmetic. The returned vector's [[ColumnVector.dataType]]
    * matches `outType` — required because downstream operators (writers, casts,
    * negate, ...) dispatch on the declared schema type and would crash if
    * given the wrong concrete vector. Float arithmetic is done in double
    * precision (matching [[Ops.apply]]) then narrowed at store time. */
  def arith(op: String, l: ColumnVector, r: ColumnVector, outType: DataType, n: Int): ColumnVector = {
    val cap = math.max(1, n)
    outType match {
      case DataType.LongType => longArith(op, l, r, n, cap)
      case DataType.IntType => intArith(op, l, r, n, cap)
      case DataType.DoubleType => doubleArith(op, l, r, n, cap)
      case DataType.FloatType => floatArith(op, l, r, n, cap)
      case other => throw new IllegalStateException(s"Arithmetic on $other not supported")
    }
  }

  /** Vectorized comparison. Returns a BooleanVector. */
  def compare(op: String, l: ColumnVector, r: ColumnVector, lt: DataType, rt: DataType, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    val bothNumeric = isNumeric(lt) && isNumeric(rt)
    if (bothNumeric) numericCompare(op, l, r, values, nulls, n)
    else genericCompare(op, l, r, values, nulls, n)
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized string concatenation (`||`). Coerces each non-null operand via
    * `toString` to match [[Ops.apply]]'s `||` behaviour. */
  def concat(l: ColumnVector, r: ColumnVector, n: Int): StringVector = {
    val cap = math.max(1, n)
    val values = new Array[String](cap)
    var i = 0
    while (i < n) {
      if (l.isNull(i) || r.isNull(i)) values(i) = null
      else values(i) = String.valueOf(l.getBoxed(i)) + String.valueOf(r.getBoxed(i))
      i += 1
    }
    new StringVector(values, cap)
  }

  /** Three-valued AND. `false AND null = false`; `true AND null = null`. */
  def and(l: ColumnVector, r: ColumnVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    val lv = l.asInstanceOf[BooleanVector]
    val rv = r.asInstanceOf[BooleanVector]
    var i = 0
    while (i < n) {
      val lNull = lv.isNull(i)
      val rNull = rv.isNull(i)
      val lf = !lNull && !lv.values(i)
      val rf = !rNull && !rv.values(i)
      if (lf || rf) values(i) = false
      else if (lNull || rNull) nulls.set(i)
      else values(i) = true
      i += 1
    }
    new BooleanVector(values, nulls, cap)
  }

  /** Three-valued OR. `true OR null = true`; `false OR null = null`. */
  def or(l: ColumnVector, r: ColumnVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    val lv = l.asInstanceOf[BooleanVector]
    val rv = r.asInstanceOf[BooleanVector]
    var i = 0
    while (i < n) {
      val lNull = lv.isNull(i)
      val rNull = rv.isNull(i)
      val lt = !lNull && lv.values(i)
      val rt = !rNull && rv.values(i)
      if (lt || rt) values(i) = true
      else if (lNull || rNull) nulls.set(i)
      else values(i) = false
      i += 1
    }
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized unary negation. Output type matches operand type. */
  def negate(c: ColumnVector, outType: DataType, n: Int): ColumnVector = {
    val cap = math.max(1, n)
    outType match {
      case DataType.IntType =>
        val cv = c.asInstanceOf[IntVector]
        val values = new Array[Int](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new IntVector(values, nulls, cap)
      case DataType.LongType =>
        val cv = c.asInstanceOf[LongVector]
        val values = new Array[Long](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new LongVector(values, nulls, cap)
      case DataType.FloatType =>
        val cv = c.asInstanceOf[FloatVector]
        val values = new Array[Float](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new FloatVector(values, nulls, cap)
      case DataType.DoubleType =>
        val cv = c.asInstanceOf[DoubleVector]
        val values = new Array[Double](cap)
        val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
        var i = 0
        while (i < n) { if (!cv.isNull(i)) values(i) = -cv.values(i); i += 1 }
        new DoubleVector(values, nulls, cap)
      case other => throw new IllegalStateException(s"Unary - on $other")
    }
  }

  /** Vectorized boolean negation. */
  def not(c: ColumnVector, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val cv = c.asInstanceOf[BooleanVector]
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap); nulls.or(cv.nulls)
    var i = 0
    while (i < n) { if (!cv.isNull(i)) values(i) = !cv.values(i); i += 1 }
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized IS NULL / IS NOT NULL. Returns BooleanVector with no nulls. */
  def isNull(c: ColumnVector, negated: Boolean, n: Int): BooleanVector = {
    val cap = math.max(1, n)
    val values = new Array[Boolean](cap)
    val nulls = new util.BitSet(cap)
    var i = 0
    while (i < n) { values(i) = c.isNull(i) ^ negated; i += 1 }
    new BooleanVector(values, nulls, cap)
  }

  /** Vectorized CAST. Falls back to the per-row [[Casts.cast]] for combinations
    * not covered by a fast path; the inner loop still avoids per-row Expr
    * dispatch. */
  def cast(c: ColumnVector, fromType: DataType, toType: DataType, n: Int): ColumnVector = {
    if (fromType == toType) return c
    val cap = math.max(1, n)
    val out = ColumnVector.allocate(toType, cap)
    var i = 0
    while (i < n) {
      if (c.isNull(i)) out.setNull(i)
      else {
        val v = Casts.cast(c.getBoxed(i), fromType, toType)
        if (v == null) out.setNull(i) else out.setBoxed(i, v)
      }
      i += 1
    }
    out
  }

  // ---- helpers ----

  private def isNumeric(t: DataType): Boolean = t match {
    case DataType.IntType | DataType.LongType | DataType.FloatType | DataType.DoubleType => true
    case _ => false
  }

  private def readLong(v: ColumnVector, i: Int): Long = v match {
    case lv: LongVector => lv.values(i)
    case iv: IntVector => iv.values(i).toLong
    case other => other.getBoxed(i).asInstanceOf[Number].longValue
  }

  private def readInt(v: ColumnVector, i: Int): Int = v match {
    case iv: IntVector => iv.values(i)
    case lv: LongVector => lv.values(i).toInt
    case other => other.getBoxed(i).asInstanceOf[Number].intValue
  }

  private def readDouble(v: ColumnVector, i: Int): Double = v match {
    case dv: DoubleVector => dv.values(i)
    case fv: FloatVector => fv.values(i).toDouble
    case lv: LongVector => lv.values(i).toDouble
    case iv: IntVector => iv.values(i).toDouble
    case other => other.getBoxed(i).asInstanceOf[Number].doubleValue
  }

  private def longArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): LongVector = {
    val values = new Array[Long](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) + readLong(r, i)
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) - readLong(r, i)
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readLong(l, i) * readLong(r, i)
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readLong(r, i)
            if (rv == 0L) nulls.set(i) else values(i) = readLong(l, i) / rv
          }
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readLong(r, i)
            if (rv == 0L) nulls.set(i) else values(i) = readLong(l, i) % rv
          }
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown long arith op '$other'")
    }
    new LongVector(values, nulls, cap)
  }

  private def intArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): IntVector = {
    val values = new Array[Int](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readInt(l, i) + readInt(r, i)
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readInt(l, i) - readInt(r, i)
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readInt(l, i) * readInt(r, i)
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readInt(r, i)
            if (rv == 0) nulls.set(i) else values(i) = readInt(l, i) / rv
          }
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else {
            val rv = readInt(r, i)
            if (rv == 0) nulls.set(i) else values(i) = readInt(l, i) % rv
          }
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown int arith op '$other'")
    }
    new IntVector(values, nulls, cap)
  }

  private def doubleArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): DoubleVector = {
    val values = new Array[Double](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) + readDouble(r, i)
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) - readDouble(r, i)
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) * readDouble(r, i)
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) / readDouble(r, i)
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) % readDouble(r, i)
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown double arith op '$other'")
    }
    new DoubleVector(values, nulls, cap)
  }

  /** Float arithmetic. Math is performed in double precision (matching
    * [[Ops.apply]]'s `DataType.FloatType | DataType.DoubleType` branch) and
    * narrowed to float at store time. */
  private def floatArith(op: String, l: ColumnVector, r: ColumnVector, n: Int, cap: Int): FloatVector = {
    val values = new Array[Float](cap)
    val nulls = new util.BitSet(cap)
    op match {
      case "+" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) + readDouble(r, i)).toFloat
          i += 1
        }
      case "-" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) - readDouble(r, i)).toFloat
          i += 1
        }
      case "*" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) * readDouble(r, i)).toFloat
          i += 1
        }
      case "/" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) / readDouble(r, i)).toFloat
          i += 1
        }
      case "%" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = (readDouble(l, i) % readDouble(r, i)).toFloat
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown float arith op '$other'")
    }
    new FloatVector(values, nulls, cap)
  }

  /** Numeric comparison via double promotion — matches [[Ops.cmp]]'s
    * cross-numeric semantics. */
  private def numericCompare(
      op: String,
      l: ColumnVector,
      r: ColumnVector,
      values: Array[Boolean],
      nulls: util.BitSet,
      n: Int): Unit = {
    op match {
      case "=" | "==" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) == readDouble(r, i)
          i += 1
        }
      case "<>" | "!=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) != readDouble(r, i)
          i += 1
        }
      case "<" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) < readDouble(r, i)
          i += 1
        }
      case "<=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) <= readDouble(r, i)
          i += 1
        }
      case ">" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) > readDouble(r, i)
          i += 1
        }
      case ">=" =>
        var i = 0
        while (i < n) {
          if (l.isNull(i) || r.isNull(i)) nulls.set(i)
          else values(i) = readDouble(l, i) >= readDouble(r, i)
          i += 1
        }
      case other => throw new IllegalStateException(s"Unknown numeric compare op '$other'")
    }
  }

  /** Generic comparison — falls back through [[Ops.cmp]] / [[Ops.eq]] for
    * strings, booleans, dates, etc. Per-row but one dispatch level shallower
    * than the original `Expr.eval` tree (no BinOpExpr wrapper). */
  private def genericCompare(
      op: String,
      l: ColumnVector,
      r: ColumnVector,
      values: Array[Boolean],
      nulls: util.BitSet,
      n: Int): Unit = {
    val isEq = op == "=" || op == "=="
    val isNe = op == "<>" || op == "!="
    var i = 0
    while (i < n) {
      if (l.isNull(i) || r.isNull(i)) nulls.set(i)
      else {
        val a = l.getBoxed(i)
        val b = r.getBoxed(i)
        values(i) =
          if (isEq) Ops.eq(a, b)
          else if (isNe) !Ops.eq(a, b)
          else {
            val c = Ops.cmp(a, b)
            op match {
              case "<" => c < 0
              case "<=" => c <= 0
              case ">" => c > 0
              case ">=" => c >= 0
              case other => throw new IllegalStateException(s"Unknown generic compare op '$other'")
            }
          }
      }
      i += 1
    }
  }
}
