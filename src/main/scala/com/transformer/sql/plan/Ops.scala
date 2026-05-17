package com.transformer.sql.plan

import com.transformer.core.DataType

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
