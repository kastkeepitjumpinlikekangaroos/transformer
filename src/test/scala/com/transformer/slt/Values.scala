package com.transformer.slt

import com.transformer.core.DataType
import com.transformer.slt.Script.{Column, Format, SortMode}

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
  *
  * A column of CockroachDB's or sqllogictest-rs's letters ([[Column.Letter]])
  * prints the value as that engine's runner prints a value of the type
  * ([[upstream]]), and [[checkRows]] compares a result with such a record's
  * block as that runner does, a number at the expected value's precision.
  */
object Values {

  def format(v: Any, column: Column): String =
    if (v == null) "NULL"
    else column match {
      case Column.Integer => sqliteInt(v).toString
      case Column.Real => real(sqliteDouble(v))
      case Column.Text => sqliteText(v)
      case Column.Exact(_) => exact(v)
      case Column.Letter(_) => exact(v)
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
        val mant = if (digits.length == 1) digits + ".0" else s"${digits.head}.${digits.tail}"
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

  // ---- CockroachDB's and sqllogictest-rs's results -----------------------------

  /** `v` as `format`'s engine prints a value of its type in a result:
    *
    *   - CockroachDB (its runner prints what lib/pq returns with Go's `%v`):
    *     a float as Go's shortest form, `1e+06` past six digits before the
    *     point; a decimal in full; `true` and `false`; the empty string `·`; a
    *     DATE and a TIMESTAMP as Go's `time.Time`, `2006-01-02 15:04:05.5 +0000
    *     +0000`.
    *   - DataFusion: a float rounded to 12 places, trailing zeros dropped
    *     (`11`, `0.123456789012`); a decimal at its scale; `true` and
    *     `false`; the empty string `(empty)`; a DATE `2006-01-02`, a
    *     TIMESTAMP `2006-01-02T15:04:05.500`.
    *   - RisingWave (Postgres's text): a decimal at its scale, `t` and `f`, a
    *     DATE `2006-01-02`, a TIMESTAMP `2006-01-02 15:04:05.5`.
    *
    * NULL is `NULL` in each. */
  def upstream(v: Any, format: Format): String =
    if (v == null) "NULL"
    else format match {
      case Format.Cockroach => v match {
        case s: String => if (s.isEmpty) "\u00b7" else s
        case d: java.lang.Double => goFloat(d.doubleValue)
        case f: java.lang.Float => goFloat(java.lang.Double.parseDouble(java.lang.Float.toString(f.floatValue)))
        case d: JBigDecimal => d.toPlainString
        case date: java.time.LocalDate => s"$date 00:00:00 +0000 +0000"
        case i: java.time.Instant => goTime(i) + " +0000 +0000"
        case other => exact(other)
      }
      case Format.DataFusion => v match {
        case s: String => if (s.isEmpty) "(empty)" else s.reverse.dropWhile(_ == '\n').reverse.replace("\u0000", "\\0")
        case d: java.lang.Double => dataFusionFloat(d.doubleValue, java.lang.Double.toString(d.doubleValue))
        case f: java.lang.Float => dataFusionFloat(f.doubleValue, java.lang.Float.toString(f.floatValue))
        case d: JBigDecimal => d.toPlainString
        case i: java.time.Instant => dataFusionTime(i)
        case other => exact(other)
      }
      case _ => v match {
        case s: String => if (s.isEmpty) "(empty)" else s
        case b: java.lang.Boolean => if (b.booleanValue) "t" else "f"
        case d: JBigDecimal => d.toPlainString
        case i: java.time.Instant => goTime(i)
        case other => exact(other)
      }
    }

  /** A float as Go's `%v` prints it (`strconv`'s shortest `%g`): the
    * shortest digits that read back as the value, in `%e` form (`1e+06`,
    * `1.5e-05`) when the exponent is below -4 or at least 6, `+Inf`, `-Inf`
    * and `NaN` as Go spells them. */
  def goFloat(d: Double): String =
    if (java.lang.Double.isNaN(d)) "NaN"
    else if (d.isInfinite) { if (d > 0) "+Inf" else "-Inf" }
    else if (d == 0.0) { if (1.0 / d < 0) "-0" else "0" }
    else {
      val shortest = new JBigDecimal(java.lang.Double.toString(d)).stripTrailingZeros
      val exp = shortest.precision - shortest.scale - 1
      if (exp < -4 || exp >= 6) {
        val digits = shortest.unscaledValue.abs.toString
        val mant = if (digits.length == 1) digits else s"${digits.head}.${digits.tail}"
        (if (d < 0) "-" else "") + mant + "e" + (if (exp < 0) "-" else "+") + f"${math.abs(exp)}%02d"
      } else shortest.toPlainString
    }

  /** DataFusion's `float_to_str`: Rust's shortest text (`shortest`) rounded
    * to 12 places, half to even, trailing zeros dropped. */
  private def dataFusionFloat(d: Double, shortest: String): String =
    if (java.lang.Double.isNaN(d)) "NaN"
    else if (d.isInfinite) { if (d > 0) "Infinity" else "-Infinity" }
    else {
      val r = new JBigDecimal(shortest).setScale(12, RoundingMode.HALF_EVEN).stripTrailingZeros
      if (r.signum == 0) "0" else r.toPlainString
    }

  /** Go's `time.Time` without its zone: the fraction of a second as many
    * digits as it needs, none for a whole second. */
  private def goTime(i: java.time.Instant): String = {
    val t = java.time.LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC)
    val base = f"${t.getYear}%04d-${t.getMonthValue}%02d-${t.getDayOfMonth}%02d ${t.getHour}%02d:${t.getMinute}%02d:${t.getSecond}%02d"
    if (t.getNano == 0) base else base + "." + f"${t.getNano}%09d".reverse.dropWhile(_ == '0').reverse
  }

  /** Arrow's timestamp: ISO with a `T`, a fraction of 3, 6 or 9 digits. */
  private def dataFusionTime(i: java.time.Instant): String = {
    val t = java.time.LocalDateTime.ofInstant(i, java.time.ZoneOffset.UTC)
    val base = f"${t.getYear}%04d-${t.getMonthValue}%02d-${t.getDayOfMonth}%02dT${t.getHour}%02d:${t.getMinute}%02d:${t.getSecond}%02d"
    val n = t.getNano
    if (n == 0) base
    else if (n % 1000000 == 0) base + f".${n / 1000000}%03d"
    else if (n % 1000 == 0) base + f".${n / 1000}%06d"
    else base + f".$n%09d"
  }

  /** Whether a result column of type `t` is of the kind a letter names, or
    * what it is instead: `I` an integer (a `DECIMAL` of no places too), `R`
    * and `F` a number, `B` a
    * boolean, `D` a date, `P` a timestamp; any other letter takes any type,
    * as does a column of NULLs. */
  def letterProblem(letter: Char, t: DataType): Option[String] = {
    val ok = t == DataType.NullType || (letter match {
      case 'I' => t == DataType.IntType || t == DataType.LongType || (t match {
        case DataType.DecimalType(_, 0) => true
        case _ => false
      })
      case 'R' | 'F' => DataType.isNumeric(t)
      case 'B' => t == DataType.BooleanType
      case 'D' => t == DataType.DateType
      case 'P' => t == DataType.TimestampType
      case _ => true
    })
    if (ok) None
    else Some(s"is ${Script.typeName(t)}; the record says ${letterName(letter)}")
  }

  def letterName(letter: Char): String = letter match {
    case 'I' => "I (an integer)"
    case 'R' => "R (a decimal or float)"
    case 'F' => "F (a float)"
    case 'B' => "B (a boolean)"
    case 'D' => "D (a date)"
    case 'P' => "P (a timestamp)"
    case c => s"$c"
  }

  /** What a result says that `expected`, a record's block of CockroachDB's or
    * sqllogictest-rs's rows, does not, or None. `names` are the result's
    * column names, `inexact` says which columns are a FLOAT or DOUBLE, and
    * `rows` hold its values. Compared as each runner compares:
    *
    *   - CockroachDB: every value split into words at runs of spaces, and the
    *     words compared one by one (a query with `colnames` puts the column
    *     names first); `rowsort` sorts rows of as many words as the query has
    *     columns, `valuesort` every word, `partialsort` the rows within runs
    *     that agree on its columns, each the same on both sides. A word is
    *     the expected one or, in an `F` or `R` column, the same number
    *     ([[sameNumber]]).
    *   - sqllogictest-rs: a row is its values joined by spaces, a line of the
    *     block, runs of spaces read as one (RisingWave's runner normalizes
    *     them, and DataFusion's writes one); `rowsort` sorts the lines,
    *     `valuesort` the values. Each of the engine's values takes as many of
    *     the line's words as it prints, so a value with spaces lines up, and
    *     an `R` value compares as the same number. */
  def checkRows(expected: Seq[String], columns: Seq[Column], sort: SortMode, partialSort: Seq[Int],
      colNames: Boolean, names: Seq[String], inexact: Seq[Boolean], rows: Seq[Array[Any]], format: Format): Option[String] = {
    val letters = columns.map { case Column.Letter(c) => c; case _ => 'T' }
    val n = letters.length
    val printed: Vector[Vector[String]] = rows.iterator.map { r =>
      letters.indices.map(c => if (letters(c) == '_') "_" else upstream(r(c), format)).toVector
    }.toVector
    if (format == Format.Cockroach) {
      val sorted = sort != SortMode.NoSort || partialSort.nonEmpty
      def words(values: Seq[String]): Vector[String] =
        if (!sorted || n != 1) values.flatMap(v => v.trim.split("\\s+").filter(_.nonEmpty)).toVector
        else values.map(v => v.trim.split("\\s+").filter(_.nonEmpty).mkString(" ")).toVector
      val head = if (colNames) words(names.map(_.replace(' ', '_'))) else Vector.empty
      var actual = head ++ printed.flatMap(words)
      var want = expected.toVector.flatMap { line =>
        val w = line.trim.split("\\s+").filter(_.nonEmpty).toVector
        if (!sorted) w else if (n == 1) Vector(w.mkString(" ")) else w
      }
      def arrange(values: Vector[String]): Vector[String] =
        if (partialSort.nonEmpty) partialSorted(values, n, partialSort.map(_ - 1))
        else sort match {
          case SortMode.RowSort => values.grouped(n).toVector.sortWith((a, b) => compareRows(a, b) < 0).flatten
          case SortMode.ValueSort => values.sortWith((a, b) => strcmp(a, b) < 0)
          case SortMode.NoSort => values
        }
      if (sorted && n > 1 && want.length % n != 0)
        return Some(s"the block's ${want.length} values are no whole number of rows of $n")
      actual = arrange(actual)
      want = arrange(want)
      if (actual.length != want.length) return Runner.diff(want, actual)
      want.indices.find { i =>
        val c = i % n
        want(i) != actual(i) &&
          !((letters(c) == 'F' || letters(c) == 'R') && sameNumber(want(i), actual(i), inexact(c), format, letters(c)))
      }.flatMap(i => Runner.diff(want.drop(i), actual.drop(i)).map(d => s"from value ${i + 1}: $d"))
    } else {
      // A row is its values joined by spaces. Each of the engine's values
      // takes as many of the expected line's words as it prints, so a value
      // with spaces in it lines up, and an `R` value compares as a number.
      def words(s: String): Vector[String] = s.trim.split("\\s+").toVector.filter(_.nonEmpty)
      val actualRows = printed.map(_.map(v => words(v).mkString(" ")))
      def line(row: Vector[String]): String = row.mkString(" ")
      def arrange(rs: Vector[String]): Vector[String] = sort match {
        case SortMode.RowSort => rs.sortWith((a, b) => strcmp(a, b) < 0)
        case SortMode.ValueSort => rs.flatMap(words).sortWith((a, b) => strcmp(a, b) < 0)
        case SortMode.NoSort => rs
      }
      val want = arrange(expected.toVector.map(l => words(l).mkString(" ")))
      if (sort == SortMode.ValueSort) {
        val got = arrange(actualRows.map(line))
        return if (want == got) None else Runner.diff(want, got)
      }
      val got = sort match {
        case SortMode.RowSort => actualRows.sortWith((a, b) => strcmp(line(a), line(b)) < 0)
        case _ => actualRows
      }
      if (want.length != got.length) return Runner.diff(want, got.map(line))
      want.indices.find { i =>
        val e = want(i)
        val row = got(i)
        e != line(row) && {
          val ew = words(e)
          var pos = 0
          val aligned = row.indices.forall { c =>
            val k = math.max(1, words(row(c)).length)
            val piece = ew.slice(pos, pos + k).mkString(" ")
            pos += k
            piece == row(c) || (letters(c) == 'R' && sameNumber(piece, row(c), inexact(c), format, 'R'))
          }
          !(aligned && pos == ew.length)
        }
      }.flatMap(i => Runner.diff(want.drop(i), got.drop(i).map(line)).map(d => s"from row ${i + 1}: $d"))
    }
  }

  /** A result's words as CockroachDB hashes them: each value split at runs
    * of spaces, then rows of `n` words sorted under `rowsort`, every word
    * under `valuesort`. */
  def upstreamWords(n: Int, sort: SortMode, rows: Seq[Array[Any]], format: Format): Vector[String] = {
    val words = rows.toVector.flatMap(r => (0 until n).flatMap(c => upstream(r(c), format).trim.split("\\s+").filter(_.nonEmpty)))
    sort match {
      case SortMode.RowSort if n > 0 => words.grouped(n).toVector.sortWith((a, b) => compareRows(a, b) < 0).flatten
      case SortMode.ValueSort => words.sortWith((a, b) => strcmp(a, b) < 0)
      case _ => words
    }
  }

  /** CockroachDB's partial sort: the rows sorted within each run of rows
    * that agree on the 0-based columns `cols`. */
  private def partialSorted(values: Vector[String], n: Int, cols: Seq[Int]): Vector[String] = {
    val rows = values.grouped(n).toVector
    val out = Vector.newBuilder[Vector[String]]
    var start = 0
    for (i <- 1 to rows.length) {
      if (i == rows.length || cols.exists(c => rows(i)(c) != rows(start)(c))) {
        out ++= rows.slice(start, i).sortWith((a, b) => compareRows(a, b) < 0)
        start = i
      }
    }
    out.result().flatten
  }

  /** Whether `actual`, a value the engine printed, is the number `expected`
    * says, compared at the precision the expected value is written to:
    *
    *   - a FLOAT or DOUBLE (`inexact`) agrees to 15 significant digits,
    *     CockroachDB's own rule for its floats, which a DOUBLE holds whatever
    *     the order its sum was taken in; and in DataFusion's block also when
    *     printed as DataFusion prints a float (a DOUBLE where DataFusion has
    *     a decimal of fewer places is no match, so a DataFusion float `1.5`
    *     is not taken for the engine's `1.53`);
    *   - an exact number (an integer or a DECIMAL) is equal, or, where it
    *     has more places than the expected one shows, equal when rounded
    *     half up to those places (the other engine's type kept fewer).
    *
    * NaN and the infinities match only themselves. */
  def sameNumber(expected: String, actual: String, inexact: Boolean, format: Format, letter: Char): Boolean = {
    def number(s: String): Option[JBigDecimal] =
      try Some(new JBigDecimal(s.trim)) catch { case _: NumberFormatException => None }
    (number(expected), number(actual)) match {
      case (Some(e), Some(a)) =>
        if (a.compareTo(e) == 0) true
        else if (inexact || letter == 'F') floatsMatch(e, a)
        else a.scale > e.scale && e.scale >= 0 && a.setScale(e.scale, RoundingMode.HALF_UP).compareTo(e) == 0
      case _ => false
    }
  }

  /** CockroachDB's `floatcmp.FloatsMatch`: the same sign and power of ten and
    * the same first 15 significant digits. */
  private def floatsMatch(e: JBigDecimal, a: JBigDecimal): Boolean =
    if (e.signum == 0 || a.signum == 0) e.signum == a.signum
    else if (e.signum != a.signum) false
    else {
      val mc = new java.math.MathContext(15, RoundingMode.HALF_EVEN)
      e.round(mc).compareTo(a.round(mc)) == 0
    }
}
