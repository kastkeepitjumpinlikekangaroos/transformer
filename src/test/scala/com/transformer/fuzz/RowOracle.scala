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
  //     column is bit-identical and compares exactly: integer `SUM` accumulates in
  //     `Long`, `COUNT` is exact, `MIN`/`MAX` are order-independent reductions, and
  //     projected/group-key values are copied straight from the (identical) input.
  //     The exact columns carry the discriminating identity, so a tolerance on the
  //     floating-point columns cannot mask a wrong group or a dropped row.

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
    // Int-vs-Long / Float boxing differences never split a group, with NULL mapped
    // to a sentinel.
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
