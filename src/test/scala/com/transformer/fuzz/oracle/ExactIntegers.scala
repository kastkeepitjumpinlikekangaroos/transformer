package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.{DataGen, Rng, RowOracle}
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.plan._

import scala.util.control.NonFatal

/** INT and BIGINT arithmetic held to its meaning rather than to itself.
  *
  * Every other expression and SQL property compares the engine with the engine
  * (two evaluation paths, two layouts, two spellings), and a wrong answer that
  * is wrong the same way everywhere passes them all: integer arithmetic wrapped
  * round on both paths, in every layout, for as long as the engine had it. Here
  * the harness works the answer out itself, exactly, in `BigInt`, and holds the
  * engine to it: a result that fits its type is that value, and one that does
  * not, at any node, is an error (plans/robustness/18).
  *
  *   - [[checkExpr]]: an INT / BIGINT expression tree (`+ - * / %`, unary
  *     minus, `ABS`, `ROUND` / `TRUNC` / `FLOOR` / `CEIL`, and CASTs to an
  *     integer from an integer, a DOUBLE or a DECIMAL) over values at the edges where arithmetic overflows. Per row,
  *     `eval` must give the exact value or throw where the reference
  *     overflows; `evalVec` must give every row's value, or throw when any row
  *     overflows.
  *   - [[checkSums]]: `SUM` over BIGINT edge values, global, grouped,
  *     `DISTINCT` and as a window, in every execution layout
  *     ([[ModeDifferential.allModes]]): the exact sums, or an error in every
  *     layout when a sum does not fit. A SUM that checked its partial sums
  *     would fail in one layout and not another.
  */
object ExactIntegers {

  /** What a node is on a row: its exact value (`None` for NULL), or an
    * overflow, which nothing above it can undo. */
  private sealed trait Exact
  private case object Overflow extends Exact
  private final case class Value(v: Option[BigInt]) extends Exact

  private val IntRange = (BigInt(Int.MinValue), BigInt(Int.MaxValue))
  private val LongRange = (BigInt(Long.MinValue), BigInt(Long.MaxValue))

  private def fit(v: BigInt, t: DataType): Exact = {
    val (lo, hi) = t match {
      case DataType.IntType => IntRange
      case DataType.LongType => LongRange
      case other => throw new IllegalStateException(s"not an integer type: $other")
    }
    if (v < lo || v > hi) Overflow else Value(Some(v))
  }

  /** A whole number's exact value, or a fraction truncated toward zero, as a
    * CAST to an integer reads one; NaN and the infinities have none. */
  private def truncated(v: Any): Option[BigInt] = v match {
    case i: java.lang.Integer => Some(BigInt(i.intValue))
    case l: java.lang.Long => Some(BigInt(l.longValue))
    case d: java.math.BigDecimal => Some(BigInt(d.setScale(0, java.math.RoundingMode.DOWN).toBigInteger))
    case n: java.lang.Number =>
      val d = n.doubleValue
      if (d.isNaN || d.isInfinite) None
      else Some(BigInt(new java.math.BigDecimal(d).setScale(0, java.math.RoundingMode.DOWN).toBigInteger))
    case other => throw new IllegalStateException(s"not a number: $other")
  }

  private def exact(e: Expr, b: ColumnarBatch, row: Int): Exact = e match {
    case LitExpr(null, _) => Value(None)
    case LitExpr(v, DataType.IntType | DataType.LongType) => Value(truncated(v))
    case ColRefExpr(i, _, DataType.IntType | DataType.LongType) =>
      Value(if (b.column(i).isNull(row)) None else truncated(b.column(i).getBoxed(row)))
    case BinOpExpr(op, l, r, t) =>
      (exact(l, b, row), exact(r, b, row)) match {
        case (Overflow, _) | (_, Overflow) => Overflow
        case (Value(Some(x)), Value(Some(y))) => op match {
          case "+" => fit(x + y, t)
          case "-" => fit(x - y, t)
          case "*" => fit(x * y, t)
          // BigInt's / and % truncate toward zero, as the JVM's do, and a
          // division by zero is NULL.
          case "/" => if (y == 0) Value(None) else fit(x / y, t)
          case "%" => if (y == 0) Value(None) else fit(x % y, t)
          case other => throw new IllegalStateException(s"unexpected operator $other")
        }
        case _ => Value(None)
      }
    case UnaryOpExpr("-", c, t) => exact(c, b, row) match {
      case Value(Some(x)) => fit(-x, t)
      case other => other
    }
    case FuncExpr("ABS", Seq(c), t) => exact(c, b, row) match {
      case Value(Some(x)) => fit(x.abs, t)
      case other => other
    }
    // An integer rounded at a negative scale, by the rule each names; at no
    // scale, or one of 0 or more, it is itself.
    case FuncExpr(fn @ ("ROUND" | "TRUNC" | "FLOOR" | "CEIL"), args, t) =>
      val scale = args.lift(1).collect { case LitExpr(k: java.lang.Number, _) => k.intValue }.getOrElse(0)
      exact(args.head, b, row) match {
        case Value(Some(x)) if scale < 0 =>
          val mode = fn match {
            case "ROUND" => java.math.RoundingMode.HALF_UP
            case "TRUNC" => java.math.RoundingMode.DOWN
            case "FLOOR" => java.math.RoundingMode.FLOOR
            case _ => java.math.RoundingMode.CEILING
          }
          fit(BigInt(new java.math.BigDecimal(x.bigInteger).setScale(scale, mode).toBigIntegerExact), t)
        case other => other
      }
    case CastExpr(c, t) => c.dataType match {
      case DataType.IntType | DataType.LongType => exact(c, b, row) match {
        case Value(Some(x)) => fit(x, t)
        case other => other
      }
      case _ => // a DOUBLE or DECIMAL leaf
        c.eval(b, row) match {
          case null => Value(None)
          case v => truncated(v).map(fit(_, t)).getOrElse(Overflow)
        }
    }
    case other => throw new IllegalStateException(s"unexpected node $other")
  }

  /** `expr` over `batch`, held to [[exact]] on every row and on both paths. */
  def checkExpr(expr: Expr, batch: ColumnarBatch): Unit = {
    val n = batch.numRows
    val expected = (0 until n).map(exact(expr, batch, _))
    def same(got: Any, want: Option[BigInt]): Boolean =
      (got, want) match {
        case (null, None) => true
        case (g: java.lang.Number, Some(w)) => BigInt(g.longValue) == w
        case _ => false
      }
    var row = 0
    while (row < n) {
      val got: Either[Throwable, Any] =
        try Right(expr.eval(batch, row)) catch { case NonFatal(t) => Left(t) }
      (expected(row), got) match {
        case (Overflow, Left(_: ArithmeticException)) => ()
        case (Overflow, Left(t)) =>
          throw new AssertionError(s"row $row of $expr overflows, but eval threw $t")
        case (Overflow, Right(v)) =>
          throw new AssertionError(s"row $row of $expr overflows, but eval gave ${RowOracle.describe(v)}")
        case (Value(w), Left(t)) =>
          throw new AssertionError(s"row $row of $expr is ${w.getOrElse("NULL")}, but eval threw $t")
        case (Value(w), Right(v)) =>
          if (!same(v, w)) throw new AssertionError(
            s"row $row of $expr is ${w.getOrElse("NULL")}, but eval gave ${RowOracle.describe(v)}")
      }
      row += 1
    }
    val overflows = expected.contains(Overflow)
    val vec: Either[Throwable, ColumnVector] =
      try Right(expr.evalVec(batch)) catch { case NonFatal(t) => Left(t) }
    vec match {
      case Left(_: ArithmeticException) if overflows => ()
      case Left(t) => throw new AssertionError(s"evalVec of $expr threw $t, but no row overflows")
      case Right(_) if overflows =>
        throw new AssertionError(s"row ${expected.indexOf(Overflow)} of $expr overflows, but evalVec gave a column")
      case Right(v) =>
        (0 until n).foreach { r =>
          val got = if (v.isNull(r)) null else v.getBoxed(r)
          val want = expected(r).asInstanceOf[Value].v
          if (!same(got, want)) throw new AssertionError(
            s"row $r of $expr is ${want.getOrElse("NULL")}, but evalVec gave ${RowOracle.describe(got)}")
        }
    }
  }

  /** Whether a case reaches both halves: a row whose exact value overflows,
    * and one whose value is near its type's edge (past half its range) and
    * fits. Read off the reference alone. */
  final case class Coverage(overflows: Boolean, nearTheEdge: Boolean)

  def coverage(expr: Expr, batch: ColumnarBatch): Coverage = {
    val half = expr.dataType match {
      case DataType.IntType => BigInt(Int.MaxValue) / 2
      case _ => BigInt(Long.MaxValue) / 2
    }
    val outcomes = (0 until batch.numRows).map(exact(expr, batch, _))
    Coverage(outcomes.contains(Overflow), outcomes.exists {
      case Value(Some(v)) => v.abs > half
      case _ => false
    })
  }

  // ---- SUM in every layout ------------------------------------------------

  /** The queries [[checkSums]] runs over `t(g INT, x BIGINT)`, each with the
    * exact answer the reference gives it. */
  private val SumQueries: Seq[String] = Seq(
    "SELECT SUM(x) AS s FROM t",
    "SELECT g, SUM(x) AS s FROM t GROUP BY g",
    "SELECT g, SUM(DISTINCT x) AS s FROM t GROUP BY g",
    "SELECT g, SUM(x) OVER (PARTITION BY g) AS s FROM t")

  /** Whether a dataset reaches both halves of [[checkSums]]: a group whose
    * sum does not fit a BIGINT, and one whose sum fits though a running sum in
    * row order passes the range on the way. */
  final case class SumCoverage(overflows: Boolean, fitsOnlyAtTheEnd: Boolean)

  def sumCoverage(d: DataGen.Dataset): SumCoverage = {
    val gi = d.schema.indexOf("g")
    val xi = d.schema.indexOf("x")
    def outside(v: BigInt): Boolean = v < LongRange._1 || v > LongRange._2
    val groups = d.rows.groupBy(r => Option(r(gi))).values.map(_.flatMap(r => Option(r(xi)).map(v => BigInt(v.asInstanceOf[Long]))))
    SumCoverage(
      groups.exists(xs => outside(xs.sum)),
      groups.exists(xs => !outside(xs.sum) && xs.scanLeft(BigInt(0))(_ + _).exists(outside)))
  }

  /** `SUM` over `d`'s `x`, the exact sums or an error, the same in every
    * layout. */
  def checkSums(d: DataGen.Dataset): Unit = {
    val gi = d.schema.indexOf("g")
    val xi = d.schema.indexOf("x")
    def sumOf(rows: Seq[Array[Any]], distinct: Boolean): Option[BigInt] = {
      val xs = rows.flatMap(r => Option(r(xi)).map(v => BigInt(v.asInstanceOf[Long])))
      if (xs.isEmpty) None else Some((if (distinct) xs.distinct else xs).sum)
    }
    def fits(v: Option[BigInt]): Boolean = v.forall(s => s >= LongRange._1 && s <= LongRange._2)
    val groups = d.rows.groupBy(r => Option(r(gi)))
    SumQueries.foreach { sql =>
      // Each query's exact rows, sorted, or None when a sum does not fit.
      val expected: Option[Seq[String]] = sql match {
        case s if !s.contains("GROUP BY") && !s.contains("OVER") =>
          val total = sumOf(d.rows, distinct = false)
          if (fits(total)) Some(Seq(total.map(_.toString).getOrElse("NULL"))) else None
        case s if s.contains("OVER") =>
          val perGroup = groups.map { case (g, rs) => g -> sumOf(rs, distinct = false) }
          if (perGroup.values.forall(fits)) Some(d.rows.map { r =>
            s"${Option(r(gi)).getOrElse("NULL")}|${perGroup(Option(r(gi))).map(_.toString).getOrElse("NULL")}"
          }.sorted)
          else None
        case s =>
          val distinct = s.contains("DISTINCT")
          val perGroup = groups.map { case (g, rs) => g -> sumOf(rs, distinct) }
          if (perGroup.values.forall(fits)) Some(perGroup.toSeq.map { case (g, v) =>
            s"${g.getOrElse("NULL")}|${v.map(_.toString).getOrElse("NULL")}"
          }.sorted)
          else None
      }
      ModeDifferential.allModes(d).foreach { case (label, view, opts) =>
        val c = new Catalog
        c.register("t", view)
        val got: Either[Throwable, Seq[String]] =
          try {
            val q = SqlEngine.execute(sql, c, opts)
            val out = Seq.newBuilder[String]
            q.batches.foreach { b =>
              (0 until b.numRows).foreach { r =>
                out += (0 until b.schema.length).map { col =>
                  if (b.column(col).isNull(r)) "NULL" else b.column(col).getBoxed(r).toString
                }.mkString("|")
              }
            }
            Right(out.result().sorted)
          } catch { case NonFatal(t) => Left(t) }
        (expected, got) match {
          case (None, Left(_: ArithmeticException)) => ()
          case (None, other) => throw new AssertionError(
            s"[$label] $sql: a sum does not fit BIGINT, but the query gave $other\n  data = $d")
          case (Some(want), Right(rows)) =>
            if (rows != want) throw new AssertionError(
              s"[$label] $sql: expected ${want.mkString(", ")} but got ${rows.mkString(", ")}\n  data = $d")
          case (Some(_), Left(t)) => throw new AssertionError(
            s"[$label] $sql: every sum fits BIGINT, but the query threw $t\n  data = $d", t)
        }
      }
    }
  }
}
