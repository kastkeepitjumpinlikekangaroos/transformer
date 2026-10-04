package com.transformer.fuzz

import com.transformer.core.{DataType, Schema}

import scala.collection.mutable

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
  *
  * Exact means the engine's own equality, which is not the JVM's for two
  * types:
  *
  *   - '''A byte array''' is equal to another with the same content. Scala's
  *     `==` on two arrays is reference equality and their `hashCode` is their
  *     identity, so comparing BINARY cells with either would call every pair
  *     of cells different (each cell holds its own array): falsely red. SQL
  *     `=` compares content ([[com.transformer.sql.plan.Ops.eq]]), and so does
  *     this oracle.
  *   - '''A decimal''' is equal to another of the same VALUE, whatever its
  *     scale: `1.0` equals `1.00`, because that is what `=` means in the
  *     engine (`Ops.eq` compares with `compareTo`, and every GROUP BY,
  *     DISTINCT and join key agrees with it). The scale is how a value is
  *     spelled, and the engine does not keep one spelling: a GROUP BY or
  *     DISTINCT key and `MIN`/`MAX` read back at the column's declared scale,
  *     a spilled value reads back at the declared scale too while the
  *     in-memory run keeps the spelling it arrived with (`1.50` for `1.5`), and
  *     a `LIMIT` cutoff between two tied rows `1.0` and `1.00` keeps either.
  *     `BigDecimal.equals` would report each of those as a divergence. The
  *     comparison is still exact in value: there is no double in it, so
  *     `1.000000000000000001` and `1.000000000000000002`, which are the same
  *     double, are different.
  *
  * A DATE and a TIMESTAMP compare as the `LocalDate` and `Instant` the engine
  * boxes them to on both paths ([[com.transformer.sql.plan.Casts]],
  * `DateVector`, `TimestampVector`), with no conversion: an epoch-day `Int` from
  * one path is not equal to a `LocalDate` from the other, because `Ops.eq`
  * calls them unequal too. Mapping both to epoch days once let `ExprBatchTest`
  * pass an `eval` that returned the `Int`.
  */
object RowOracle {

  /** Coerce a boxed value to the shape implied by `dt`, so cross-boxing between
    * the row and vector paths (Float vs Double, Int vs Long) does not register as
    * a mismatch, and give a byte array and a decimal the `equals` / `hashCode`
    * of the engine's `=` (see the object comment). NULL passes through. A value
    * of the wrong class for `dt` passes through unchanged, so it compares unequal
    * to every value of the right one. */
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
      case DataType.BinaryType =>
        v match {
          case a: Array[Byte] => new Bytes(a)
          case _ => v
        }
      // One spelling per value: stripping trailing zeros maps 1.0, 1.00 and 1
      // to the same BigDecimal, so `equals` and `hashCode` agree with
      // `compareTo` and a multiset key groups the spellings together.
      case _: DataType.DecimalType =>
        v match {
          case d: java.math.BigDecimal => d.stripTrailingZeros
          case _ => v
        }
      case _ => v
    }
  }

  /** A byte array with the content equality SQL `=` gives a BINARY, and a
    * readable rendering for failure messages. */
  final class Bytes(val bytes: Array[Byte]) {
    override def equals(other: Any): Boolean = other match {
      case b: Bytes => java.util.Arrays.equals(bytes, b.bytes)
      case _ => false
    }
    override def hashCode: Int = java.util.Arrays.hashCode(bytes)
    override def toString: String = hex(bytes)
  }

  /** `X'01ff'`, the SQL spelling of a byte string. */
  def hex(bytes: Array[Byte]): String =
    bytes.iterator.map(b => f"${b & 0xff}%02x").mkString("X'", "", "'")

  /** NULL-aware exact equality of two boxed values once both are normalized to
    * `dt`. Both NULL is equal; one NULL is not.
    *
    * Float/Double use `compare`, not `==`: when both paths produce `NaN` they
    * AGREE, but Scala's `==` on boxed doubles is primitive `NaN == NaN` (false).
    * `compare` treats `NaN == NaN` as equal while still distinguishing `-0.0`
    * from `0.0`, so a genuine bit-level divergence is still caught. That is the
    * one place this equality departs from `Ops.eq` on purpose. A decimal
    * compares with `compareTo` and a byte array by content, as `Ops.eq` does. */
  def scalarEquals(a: Any, b: Any, dt: DataType): Boolean = {
    val na = normalize(a, dt)
    val nb = normalize(b, dt)
    if (na == null || nb == null) return na == null && nb == null
    (na, nb) match {
      case (x: java.lang.Double, y: java.lang.Double) => java.lang.Double.compare(x, y) == 0
      case (x: java.lang.Float, y: java.lang.Float) => java.lang.Float.compare(x, y) == 0
      case (x: java.math.BigDecimal, y: java.math.BigDecimal) => x.compareTo(y) == 0
      case _ => na == nb
    }
  }

  /** A boxed value as a failure message shows it: a byte array in hex and a
    * decimal in plain digits with its scale, rather than `[B@1b6d3586` and
    * `1E+2`. */
  def show(v: Any): String = v match {
    case null => "NULL"
    case a: Array[Byte] => hex(a)
    case d: java.math.BigDecimal => d.toPlainString
    case other => other.toString
  }

  /** Render a boxed value with its runtime class for failure messages. */
  def describe(v: Any): String = v match {
    case null => "null"
    case _: Array[Byte] => s"${show(v)} (byte[])"
    case _ => s"${show(v)} (${v.getClass.getName})"
  }

  // ---- whole-row multiset comparison (mode-differential oracle) -----------
  //
  // The mode-differential property (`oracle/ModeDifferential`) runs one fixed
  // (data, query) under spill on/off, metrics on/off, and several partition /
  // batch layouts, then asserts the result *multisets* match. Two reasons this
  // is a multiset compare with a tolerance, unlike the EXACT per-row
  // `scalarEquals` above:
  //
  //   - '''Multiset, not positional.''' The K-way merge in `SortExec` is not
  //     stable (see `sort_exec_test`), and the collapsing aggregate emits
  //     groups in an order that depends on partition fan-out, so two runs that
  //     hold the same rows can emit them in different orders. Equality is
  //     therefore over multisets.
  //
  //   - '''Tolerance on the floating-point (`Double`/`Float`) columns.''' Of the
  //     columns a query can produce, the order-dependent aggregates land in a
  //     `Double` result column: `SUM`/`AVG`/`STDDEV`/`VAR`/`COVAR`/`CORR`
  //     accumulate in `double`, so a different summation order (different layout /
  //     spill flush order) yields a result that differs by rounding. `Float`
  //     columns do NOT reorder (a `Float` reduction stays `Float`, bit-identical
  //     across modes), but a computed `Float` projection can produce `NaN` (a
  //     float divide-by-zero) — and `NaN` cannot be an exact grouping key, because
  //     Scala's `==` on a boxed `NaN` is `false`, so two identical `NaN` rows would
  //     not match. Both float kinds therefore go through the tolerant comparator,
  //     which is `NaN`-aware (and on which a bit-identical `Float` still compares
  //     equal — the tolerance is never actually exercised for it). Every other
  //     column compares exactly, under the engine's equality (`normalize`): integer
  //     `SUM` accumulates in `Long`, `COUNT` is exact, `MIN`/`MAX` are
  //     order-independent reductions, and projected/group-key values are copied
  //     straight from the (identical) input. A decimal column is exact in value but
  //     not in spelling, which spill and the GROUP BY read-back legitimately change
  //     (see the object comment). The exact columns carry the discriminating
  //     identity, so a tolerance on the floating-point columns cannot mask a wrong
  //     group or a dropped row.
  //
  //     A decimal `SUM` is exact too, though it adds in `double` (it keeps its
  //     input's type, a gap `docs/gotchas.md` records): `DataGen.DecimalPools`
  //     holds only values whose doubles are small multiples of a quarter, whose
  //     sums are exact in any order, so the column agrees across layouts to the
  //     last digit.
  //
  //     One thing the tolerance DOES hide, now that `DataGen` draws IEEE specials:
  //     `+0.0` and `-0.0` compare equal here, so a `MIN`/`MAX` that picked the
  //     wrong signed zero under one layout would not be reported. That is a
  //     deliberate trade — reordering a `SUM` over signed zeros legitimately flips
  //     the sign of a zero result (`-0.0 + -0.0` is `-0.0`, `-0.0 + 0.0` is
  //     `0.0`), so the comparator cannot separate them without failing on correct
  //     runs. Signed-zero `MIN`/`MAX` is pinned deterministically instead, by
  //     `HashAggregateSpillTest.minMaxOverSignedZeroFollowsTheTotalOrder`.

  /** Relative tolerance for `Double`-column comparison. Sized for the rounding a
    * different reduction order introduces on a well-conditioned `double` sum
    * (parts-per-million is orders of magnitude above that yet far below any real
    * divergence, which is order-of-magnitude, not ppm). The generator keeps
    * aggregate inputs well-conditioned (narrow integral/decimal domains) so this
    * stays comfortably slack; it is never widened to make a real failure pass. */
  val DefaultRelTol: Double = 1e-6

  /** Absolute tolerance floor, for `Double` results near zero where a relative
    * tolerance vanishes. */
  val DefaultAbsTol: Double = 1e-9

  /** Sentinel standing in for SQL NULL inside an exact comparison key, so a NULL
    * cell groups with other NULLs and never collides with a real value. */
  private object NullKey { override def toString: String = "NULL" }

  /** Tolerant `Double` equality used for the order-dependent aggregate columns.
    * `NaN` agrees only with `NaN`; an infinity agrees only with the same-signed
    * infinity; `+0.0`/`-0.0` agree; finite values agree within
    * `absTol + relTol * max(|a|, |b|)`. */
  def doublesWithinTolerance(a: Double, b: Double, relTol: Double, absTol: Double): Boolean = {
    if (a == b) return true // exact, incl. same-signed Inf and +0.0 == -0.0
    val aNaN = java.lang.Double.isNaN(a)
    val bNaN = java.lang.Double.isNaN(b)
    if (aNaN || bNaN) return aNaN && bNaN
    if (a.isInfinite || b.isInfinite) return false // one Inf, other finite/opposite-Inf
    math.abs(a - b) <= absTol + relTol * math.max(math.abs(a), math.abs(b))
  }

  /** Compare two result sets as multisets: `None` when equal, `Some(diff)` with a
    * readable first-N-rows-each-way message when not. Floating-point (`Double` /
    * `Float`) columns compare with the `NaN`-aware tolerant comparator, all others
    * exactly — see the section comment above. Rows are `Array[Any]` aligned to
    * `schema` field order (NULL as `null`). */
  def multisetEquals(
      schema: Schema,
      baseline: Seq[Array[Any]],
      other: Seq[Array[Any]],
      relTol: Double = DefaultRelTol,
      absTol: Double = DefaultAbsTol): Option[String] = {
    if (baseline.length != other.length)
      return Some(s"row count differs: baseline=${baseline.length}, mode=${other.length}")

    val ncols = schema.length
    val tolerant = Array.tabulate(ncols) { c =>
      val dt = schema.fields(c).dataType
      dt == DataType.DoubleType || dt == DataType.FloatType
    }

    // Identity of a row for grouping: its exact (non-Double) cells, normalized so
    // Int-vs-Long / Float boxing differences never split a group and a byte array
    // or a decimal hashes by the engine's equality, with NULL mapped to a
    // sentinel.
    def exactKey(row: Array[Any]): Vector[Any] = {
      val b = Vector.newBuilder[Any]
      var c = 0
      while (c < ncols) {
        if (!tolerant(c)) {
          val v = row(c)
          b += (if (v == null) NullKey else normalize(v, schema.fields(c).dataType))
        }
        c += 1
      }
      b.result()
    }
    // The Double cells that must match within tolerance once two rows share an
    // exact key.
    def tolTuple(row: Array[Any]): Array[Any] = {
      val out = mutable.ArrayBuffer.empty[Any]
      var c = 0
      while (c < ncols) { if (tolerant(c)) out += row(c); c += 1 }
      out.toArray
    }
    def tolTupleEquals(a: Array[Any], b: Array[Any]): Boolean = {
      if (a.length != b.length) return false
      var i = 0
      while (i < a.length) {
        val x = a(i); val y = b(i)
        if (x == null || y == null) { if (x != null || y != null) return false }
        else if (!doublesWithinTolerance(
            x.asInstanceOf[Number].doubleValue, y.asInstanceOf[Number].doubleValue, relTol, absTol))
          return false
        i += 1
      }
      true
    }

    def group(rows: Seq[Array[Any]]): mutable.LinkedHashMap[Vector[Any], mutable.ArrayBuffer[Array[Any]]] = {
      val m = mutable.LinkedHashMap.empty[Vector[Any], mutable.ArrayBuffer[Array[Any]]]
      rows.foreach(r => m.getOrElseUpdate(exactKey(r), mutable.ArrayBuffer.empty) += r)
      m
    }
    val ga = group(baseline)
    val gb = group(other)

    val onlyBaseline = mutable.ArrayBuffer.empty[Array[Any]]
    val onlyOther = mutable.ArrayBuffer.empty[Array[Any]]

    ga.foreach { case (k, rowsA) =>
      gb.get(k) match {
        case None => onlyBaseline ++= rowsA
        case Some(rowsB) =>
          // Same exact key: greedily match Double-tuples; tiny groups make greedy
          // matching exact in practice (group size is 1 for unique group keys).
          val used = new Array[Boolean](rowsB.length)
          rowsA.foreach { ra =>
            val ta = tolTuple(ra)
            var matched = -1
            var j = 0
            while (j < rowsB.length && matched < 0) {
              if (!used(j) && tolTupleEquals(ta, tolTuple(rowsB(j)))) matched = j
              j += 1
            }
            if (matched >= 0) used(matched) = true else onlyBaseline += ra
          }
          var j = 0
          while (j < rowsB.length) { if (!used(j)) onlyOther += rowsB(j); j += 1 }
      }
    }
    gb.foreach { case (k, rowsB) => if (!ga.contains(k)) onlyOther ++= rowsB }

    if (onlyBaseline.isEmpty && onlyOther.isEmpty) None
    else Some(renderDiff(schema, onlyBaseline.toSeq, onlyOther.toSeq))
  }

  private def renderDiff(schema: Schema, onlyBaseline: Seq[Array[Any]], onlyOther: Seq[Array[Any]]): String = {
    val show = 5
    def block(label: String, rows: Seq[Array[Any]]): String = {
      val head = rows.take(show).map(renderRow(schema, _)).mkString("\n    ", "\n    ", "")
      val more = if (rows.length > show) s"\n    ... (${rows.length - show} more)" else ""
      s"  $label (${rows.length}):$head$more"
    }
    s"""multiset mismatch:
       |${block("in baseline, missing under this mode", onlyBaseline)}
       |${block("under this mode, not in baseline", onlyOther)}""".stripMargin
  }

  private def renderRow(schema: Schema, row: Array[Any]): String =
    schema.fieldNames.iterator.zip(row.iterator)
      .map { case (n, v) => s"$n=${describe(v)}" }.mkString("{", ", ", "}")
}
