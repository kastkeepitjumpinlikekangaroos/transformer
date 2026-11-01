package com.transformer.slt

import com.transformer.slt.Script.{Column, SortMode}

import java.math.{BigDecimal => JBigDecimal, RoundingMode}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** How a result is printed, sorted and hashed for comparison with a script.
  *
  * SQLite's letters print a value the way SQLite's own runner did when it
  * wrote the expected results, so a script from its corpus compares as
  * written:
  *
  *   - `I` reads the value as `sqlite3_column_int` does: a whole number cut to
  *     its low 32 bits, a real truncated toward zero, a boolean 1 or 0.
  *   - `R` reads it as a double and prints `%.3f`, rounding a tie of the
  *     double's exact binary value away from zero, as SQLite's printf does
  *     (`0.0625` is `0.063`, where C's printf gives `0.062`).
  *   - `T` is the text, `(empty)` for the empty string, every byte of its
  *     UTF-8 outside printable ASCII an `@`.
  *
  * An exact column ([[Column.Exact]]) prints the engine's value as it is: a
  * number in full (a double as Java's shortest round-trip text, so `0.1 + 0.2`
  * is `0.30000000000000004`; a decimal with its scale, `2.50`), a boolean
  * `true` or `false`, a date `2026-01-31`, a timestamp as an ISO instant, bytes
  * as `x'00ff'`, and text as written, with a tab, a line break and every other
  * control character as `\t`, `\n`, `\r` or `\xHH` (and a backslash doubled),
  * `(empty)` for the empty string. NULL is `NULL` in every column.
  */
object Values {

  def format(v: Any, column: Column): String =
    if (v == null) "NULL"
    else column match {
      case Column.Integer => sqliteInt(v).toString
      case Column.Real => real(sqliteDouble(v))
      case Column.Text => sqliteText(v)
      case Column.Exact(_) => exact(v)
    }

  /** Each row's printed values, in the order the engine returned them. */
  def formatRows(rows: Seq[Array[Any]], columns: Seq[Column]): Vector[Vector[String]] =
    rows.iterator.map(r => columns.indices.map(i => format(r(i), columns(i))).toVector).toVector

  /** The printed values in the order the script compares them: as returned
    * (`nosort`), rows ordered by their values (`rowsort`), or every value
    * ordered on its own (`valuesort`), each comparison by UTF-8 bytes as C's
    * `strcmp` orders them. */
  def arrange(rows: Vector[Vector[String]], sort: SortMode): Vector[String] = arrangeRows(rows, sort).flatten

  /** [[arrange]] keeping rows apart, as a completed script writes them; under
    * `valuesort` each value is a row of its own. */
  def arrangeRows(rows: Vector[Vector[String]], sort: SortMode): Vector[Vector[String]] = sort match {
    case SortMode.NoSort => rows
    case SortMode.RowSort => rows.sortWith((a, b) => compareRows(a, b) < 0)
    case SortMode.ValueSort => rows.flatten.sortWith((a, b) => strcmp(a, b) < 0).map(Vector(_))
  }

  /** `N values hashing to H`, as the script writes a hashed result: the MD5 of
    * every value followed by a line break. */
  def hash(values: Seq[String]): String = {
    val md = MessageDigest.getInstance("MD5")
    values.foreach { v => md.update(v.getBytes(StandardCharsets.UTF_8)); md.update('\n'.toByte) }
    md.digest().map(b => f"${b & 0xff}%02x").mkString
  }

  def hashLine(values: Seq[String]): String = s"${values.length} values hashing to ${hash(values)}"

  private def compareRows(a: Vector[String], b: Vector[String]): Int = {
    var i = 0
    while (i < a.length && i < b.length) {
      val c = strcmp(a(i), b(i))
      if (c != 0) return c
      i += 1
    }
    a.length - b.length
  }

  private def strcmp(a: String, b: String): Int = {
    val x = a.getBytes(StandardCharsets.UTF_8)
    val y = b.getBytes(StandardCharsets.UTF_8)
    java.util.Arrays.compareUnsigned(x, y)
  }

  // ---- SQLite's letters ------------------------------------------------------

  private def sqliteInt(v: Any): Int = v match {
    case i: java.lang.Integer => i.intValue
    case l: java.lang.Long => l.longValue.toInt
    case b: java.lang.Boolean => if (b.booleanValue) 1 else 0
    case d: java.lang.Double => doubleToLong(d.doubleValue).toInt
    case f: java.lang.Float => doubleToLong(f.doubleValue).toInt
    case d: JBigDecimal => doubleToLong(d.doubleValue).toInt
    case s: String => leadingInteger(s).toInt
    case other => leadingInteger(other.toString).toInt
  }

  /** SQLite's REAL to INTEGER: truncation, clamped to the 64-bit range, NaN 0. */
  private def doubleToLong(d: Double): Long =
    if (java.lang.Double.isNaN(d)) 0L
    else if (d <= Long.MinValue.toDouble) Long.MinValue
    else if (d >= Long.MaxValue.toDouble) Long.MaxValue
    else d.toLong

  /** The integer a text starts with, as SQLite reads text as a number (`12ab`
    * is 12, `ab` is 0). */
  private def leadingInteger(s: String): Long = {
    val t = s.trim
    var i = 0
    if (i < t.length && (t(i) == '-' || t(i) == '+')) i += 1
    while (i < t.length && Character.isDigit(t(i))) i += 1
    try java.lang.Long.parseLong(t.substring(0, i)) catch { case _: NumberFormatException => 0L }
  }

  private def sqliteDouble(v: Any): Double = v match {
    case n: java.lang.Number => n.doubleValue
    case b: java.lang.Boolean => if (b.booleanValue) 1.0 else 0.0
    case s: String => try s.trim.toDouble catch { case _: NumberFormatException => leadingInteger(s).toDouble }
    case other => leadingInteger(other.toString).toDouble
  }

  private def real(d: Double): String =
    if (java.lang.Double.isNaN(d)) "NaN"
    else if (d.isInfinite) { if (d > 0) "Inf" else "-Inf" }
    else {
      val text = new JBigDecimal(d).setScale(3, RoundingMode.HALF_UP).toPlainString
      if (d < 0 || (d == 0.0 && 1.0 / d < 0)) { if (text.startsWith("-")) text else "-" + text } else text
    }

  private def sqliteText(v: Any): String = {
    val text = v match {
      case s: String => s
      case d: java.lang.Double => sqliteRealText(d.doubleValue)
      case f: java.lang.Float => sqliteRealText(f.doubleValue)
      case b: java.lang.Boolean => if (b.booleanValue) "1" else "0"
      case d: JBigDecimal => d.toPlainString
      case bytes: Array[Byte] => new String(bytes, StandardCharsets.ISO_8859_1)
      case other => other.toString
    }
    if (text.isEmpty) "(empty)"
    else {
      val bytes = text.getBytes(StandardCharsets.UTF_8)
      val sb = new StringBuilder(bytes.length)
      bytes.foreach(b => sb += (if (b >= ' ' && b <= '~') b.toChar else '@'))
      sb.result()
    }
  }

  /** A REAL as SQLite writes it as text (`%!.15g`): `1.5`, `100.0`, `1.0e+20`. */
  private def sqliteRealText(d: Double): String =
    if (java.lang.Double.isNaN(d) || d.isInfinite) real(d)
    else if (d == math.rint(d) && math.abs(d) < 1e15) f"${d.toLong}%d.0"
    else {
      val g = new JBigDecimal(d).round(new java.math.MathContext(15, RoundingMode.HALF_EVEN)).stripTrailingZeros
      if (math.abs(d) >= 1e15 || math.abs(d) < 1e-4) {
        val e = g.precision - g.scale - 1
        val digits = g.unscaledValue.abs.toString
        val mant = if (digits.length == 1) digits + ".0" else digits.head + "." + digits.tail
        (if (d < 0) "-" else "") + mant + "e" + (if (e < 0) "-" else "+") + f"${math.abs(e)}%02d"
      } else g.toPlainString
    }

  // ---- exact columns ---------------------------------------------------------

  def exact(v: Any): String = v match {
    case s: String => if (s.isEmpty) "(empty)" else escape(s)
    case d: java.lang.Double => java.lang.Double.toString(d.doubleValue)
    case f: java.lang.Float => java.lang.Float.toString(f.floatValue)
    case d: JBigDecimal => d.toPlainString
    case b: java.lang.Boolean => b.toString
    case bytes: Array[Byte] => bytes.map(b => f"${b & 0xff}%02x").mkString("x'", "", "'")
    case other => other.toString
  }

  private def escape(s: String): String = {
    val sb = new StringBuilder(s.length)
    s.foreach {
      case '\\' => sb ++= "\\\\"
      case '\t' => sb ++= "\\t"
      case '\n' => sb ++= "\\n"
      case '\r' => sb ++= "\\r"
      case c if c < ' ' || c == 0x7f => sb ++= f"\\x${c.toInt}%02x"
      case c => sb += c
    }
    sb.result()
  }
}
