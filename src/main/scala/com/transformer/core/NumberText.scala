package com.transformer.core

/** The text of a number, read one way wherever text becomes one: a CSV field
  * (`CsvValues`) and a CAST from a string, which reads a string literal beside
  * a number too (`Casts`). The grammar is plain and ASCII:
  *
  *   - INT and BIGINT: an optional sign and ASCII digits (`007` is 7), within
  *     the type's range.
  *   - FLOAT and DOUBLE: a plain decimal number, optionally signed, with a
  *     point, digits either side of it, and an exponent (`1.5`, `.5`, `5.`,
  *     `-1e-5`), or `NaN`, `Infinity` and `-Infinity`, which is how the CSV
  *     writer prints those. A number the type cannot hold is none of its
  *     values: one past its largest finite value (`1e400`, or `1e40` for a
  *     FLOAT), which would read as an infinity, or a nonzero one so small it
  *     would read as 0 (`1e-400`).
  *   - DECIMAL: a plain decimal number, read exactly.
  *
  * Java's own grammar is not used: it reads `1d`, `0x1p3`, ` 1` (for a float)
  * and digits of any script as numbers. Each reader returns the value or
  * throws [[NotANumber]], which carries no stack; the caller names the text
  * and the type in an error of its own. */
object NumberText {

  /** Text that is no value of the type it was read as. */
  object NotANumber extends RuntimeException(null, null, false, false)

  private def isDigit(c: Char): Boolean = c >= '0' && c <= '9'

  /** An optional sign and one or more ASCII digits. */
  def isInteger(s: String): Boolean = {
    val start = if (s.nonEmpty && (s.charAt(0) == '+' || s.charAt(0) == '-')) 1 else 0
    start < s.length && (start until s.length).forall(i => isDigit(s.charAt(i)))
  }

  /** A plain decimal number: an optional sign, digits with an optional point
    * (at least one digit either side of it), and an optional exponent. */
  def isDecimal(s: String): Boolean = {
    var i = 0
    val n = s.length
    if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
    var digits = 0
    while (i < n && isDigit(s.charAt(i))) { i += 1; digits += 1 }
    if (i < n && s.charAt(i) == '.') {
      i += 1
      while (i < n && isDigit(s.charAt(i))) { i += 1; digits += 1 }
    }
    if (digits == 0) return false
    if (i < n && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      i += 1
      if (i < n && (s.charAt(i) == '+' || s.charAt(i) == '-')) i += 1
      val exponentStart = i
      while (i < n && isDigit(s.charAt(i))) i += 1
      if (i == exponentStart) return false
    }
    i == n
  }

  /** The words a FLOAT or DOUBLE also takes, for the values that are no
    * number. */
  def isSpecialFloat(s: String): Boolean = s == "NaN" || s == "Infinity" || s == "-Infinity"

  def int(s: String): Int = {
    if (!isInteger(s)) throw NotANumber
    try Integer.parseInt(s) catch { case _: NumberFormatException => throw NotANumber }
  }

  def long(s: String): Long = {
    if (!isInteger(s)) throw NotANumber
    try java.lang.Long.parseLong(s) catch { case _: NumberFormatException => throw NotANumber }
  }

  def double(s: String): Double =
    if (isSpecialFloat(s)) java.lang.Double.parseDouble(s)
    else if (!isDecimal(s)) throw NotANumber
    else {
      val d = java.lang.Double.parseDouble(s)
      if (d.isInfinite || (d == 0.0 && hasNonzeroDigit(s))) throw NotANumber
      d
    }

  def float(s: String): Float =
    if (isSpecialFloat(s)) java.lang.Float.parseFloat(s)
    else if (!isDecimal(s)) throw NotANumber
    else {
      val f = java.lang.Float.parseFloat(s)
      if (f.isInfinite || (f == 0.0f && hasNonzeroDigit(s))) throw NotANumber
      f
    }

  def decimal(s: String): java.math.BigDecimal = {
    if (!isDecimal(s)) throw NotANumber
    // An exponent past an INT's range is no BigDecimal.
    try new java.math.BigDecimal(s) catch { case _: NumberFormatException => throw NotANumber }
  }

  /** Whether a plain decimal number's digits, before its exponent, are other
    * than zeros. */
  private def hasNonzeroDigit(s: String): Boolean = {
    var i = 0
    while (i < s.length && s.charAt(i) != 'e' && s.charAt(i) != 'E') {
      val c = s.charAt(i)
      if (c >= '1' && c <= '9') return true
      i += 1
    }
    false
  }
}
