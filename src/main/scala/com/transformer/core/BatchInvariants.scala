package com.transformer.core

/** What every batch an operator emits must be, checked against the schema
  * the operator declares, and `--config=verify`'s switch for checking them.
  *
  * Under `-Dtransformer.verify=true` ([[Settings.Verify]]), read once per JVM
  * as `transformer.hash_zero` is ([[KeyHash]]), the physical planner wraps every
  * operator it builds in a check of each batch it emits
  * (`com.transformer.sql.exec.VerifiedPlan`), so a value its type cannot hold
  * fails the test at the operator that made it, rather than at a writer far
  * downstream: that is how plans/robustness/09 was found, a cast between two
  * DECIMAL types keeping its value as it was, which only the spill mode's
  * parquet writer refused. DuckDB verifies every vector in its debug builds
  * the same way. Off, nothing is checked and nothing is wrapped.
  *
  * The invariants, for a batch and the schema its operator declares:
  *
  *   1. It has a column per field, each vector of the field's type, a
  *      DECIMAL's precision and scale included.
  *   2. Its rows fit every column: `numRows` is at most each vector's
  *      capacity and the length of its value array.
  *   3. A DECIMAL value fits its declared type: set to the declared scale it
  *      needs no rounding, and has at most the declared precision. A smaller
  *      scale than declared is allowed (1.5 in a DECIMAL(9,2)), as the
  *      writers store it at the declared scale.
  *   4. A DATE and a TIMESTAMP lie within their types' ranges
  *      (plans/robustness/25). This holds by construction, so nothing checks
  *      it: a `DateVector` stores an `Int` of days and a `TimestampVector` a
  *      `Long` of microseconds, and the types' ranges are exactly those, every
  *      value entering the engine narrowed through `DateVector.epochDay` or
  *      `TimestampVector.epochMicros`.
  *   5. A STRING holds text: no half of a surrogate pair, which UTF-8 cannot
  *      encode, so no file can hold one (decision 2 of plans/testing/17).
  *   6. Its own schema names the columns as the operator's does, in the same
  *      types: a consumer that reads `batch.schema` reads what the plan says.
  *
  * The checks run at operator boundaries only, never inside expression
  * evaluation, where a comparison's operands may be cast under a type that
  * does not hold them ([[com.transformer.sql.plan.Analyzer]]).
  */
object BatchInvariants {

  /** Whether every operator's output is checked: `-Dtransformer.verify=true`. */
  val enabled: Boolean = Settings.Verify.get

  /** How `batch` breaks an invariant: what, and where when it is one value. */
  final case class Violation(what: String, column: Int = -1, row: Int = -1)

  /** The first way `batch` breaks the invariants against `schema`, the
    * schema its operator declares, or None. One pass over the batch. */
  def violation(batch: ColumnarBatch, schema: Schema): Option[Violation] = {
    if (batch.columns.length != schema.length)
      return Some(Violation(s"the batch has ${batch.columns.length} columns where the operator declares ${schema.length}"))
    val n = batch.numRows
    var c = 0
    while (c < schema.length) {
      val field = schema.fields(c)
      val vector = batch.columns(c)
      if (vector.dataType != field.dataType)
        return Some(Violation(s"the vector is ${vector.dataType} where the operator declares ${field.dataType}", c))
      if (n > vector.capacity || n > storedLength(vector))
        return Some(Violation(s"the batch has $n rows where the vector holds ${math.min(vector.capacity, storedLength(vector))}", c))
      val own = batch.schema.fields(c)
      if (own.name != field.name || own.dataType != field.dataType)
        return Some(Violation(s"the batch's own schema calls it '${own.name}' ${own.dataType} where the operator " +
          s"declares '${field.name}' ${field.dataType}", c))
      val bad = vector match {
        case d: DecimalVector => firstUnfit(d, n)
        case s: StringVector => firstHalfPair(s, n)
        case _ => None
      }
      if (bad.isDefined) return bad.map(_.copy(column = c))
      c += 1
    }
    None
  }

  /** How many values `v`'s array holds; a vector of no values holds any
    * number of NULLs. */
  private def storedLength(v: ColumnVector): Int = v match {
    case x: IntVector => x.values.length
    case x: LongVector => x.values.length
    case x: FloatVector => x.values.length
    case x: DoubleVector => x.values.length
    case x: BooleanVector => x.values.length
    case x: StringVector => x.values.length
    case x: BinaryVector => x.values.length
    case x: DateVector => x.values.length
    case x: TimestampVector => x.values.length
    case x: DecimalVector => x.values.length
    case _: NullVectorImpl => Int.MaxValue
  }

  private def firstUnfit(d: DecimalVector, n: Int): Option[Violation] = {
    var r = 0
    while (r < n) {
      val v = d.values(r)
      if (v != null) {
        val why = unfit(v, d.precision, d.scale)
        if (why != null) return Some(Violation(s"${v.toPlainString} $why", row = r))
      }
      r += 1
    }
    None
  }

  /** Why `v` is no value of a DECIMAL(`precision`, `scale`), or null when it
    * is one: the writers' test (`ParquetWriter`'s `unscaledAtScale`). */
  def unfit(v: java.math.BigDecimal, precision: Int, scale: Int): String = {
    val atScale =
      if (v.scale == scale) v
      else try v.setScale(scale) catch { case _: ArithmeticException => null }
    if (atScale == null) s"has more digits after the point than the ${scale} of a DECIMAL($precision,$scale)"
    else if (atScale.precision > precision) s"has more digits than the $precision of a DECIMAL($precision,$scale)"
    else null
  }

  private def firstHalfPair(s: StringVector, n: Int): Option[Violation] = {
    var r = 0
    while (r < n) {
      val v = s.values(r)
      if (v != null) {
        val at = halfPair(v)
        if (at >= 0) return Some(Violation(
          s"'${escaped(v)}' holds half a surrogate pair, U+${"%04X".formatLocal(java.util.Locale.ROOT, v.charAt(at).toInt)} at char $at, which is no text",
          row = r))
      }
      r += 1
    }
    None
  }

  /** Refuse `batch` when a STRING in it holds half a surrogate pair: what a
    * view built in Scala, `source`, gives must be text (decision 2 of
    * plans/testing/17). */
  def requireText(batch: ColumnarBatch, source: => String): Unit = {
    var c = 0
    while (c < batch.columns.length) {
      batch.columns(c) match {
        case s: StringVector =>
          firstHalfPair(s, batch.numRows).foreach { v =>
            throw new IllegalArgumentException(
              s"Column '${batch.schema.fields(c).name}' of $source, row ${v.row} of a batch: ${v.what}; a STRING " +
                "holds text, which a file can hold")
          }
        case _ => ()
      }
      c += 1
    }
  }

  /** Where `s` holds half a surrogate pair, a high one with no low one after
    * it or a low one with no high one before it: the char's index, or -1
    * when `s` is text. */
  def halfPair(s: String): Int = {
    var i = 0
    val n = s.length
    while (i < n) {
      val ch = s.charAt(i)
      if (Character.isHighSurrogate(ch)) {
        if (i + 1 < n && Character.isLowSurrogate(s.charAt(i + 1))) i += 2
        else return i
      } else if (Character.isLowSurrogate(ch)) return i
      else i += 1
    }
    -1
  }

  /** `s` for a message: every char outside printable ASCII as a `\\u`
    * escape, and a long one cut short. */
  def escaped(s: String): String = {
    val shown = if (s.length <= 80) s else s.take(80) + "..."
    val sb = new java.lang.StringBuilder
    shown.foreach { ch =>
      if (ch >= 0x20 && ch < 0x7f) sb.append(ch)
      else sb.append("\\u").append("%04x".formatLocal(java.util.Locale.ROOT, ch.toInt))
    }
    sb.toString
  }

  /** A value for a message: a STRING escaped, others as they print. */
  def show(v: Any): String = v match {
    case null => "NULL"
    case s: String => s"'${escaped(s)}'"
    case b: java.math.BigDecimal => b.toPlainString
    case bytes: Array[Byte] => bytes.map(x => "%02x".formatLocal(java.util.Locale.ROOT, x & 0xff)).mkString("X'", "", "'")
    case other => other.toString
  }
}
