package com.transformer.sql.plan

import com.transformer.core._
import com.transformer.fuzz.DataGen
import com.transformer.fuzz.oracle.CleanRejection
import com.transformer.slt.Values

import java.math.{BigDecimal => JBigDecimal}
import java.time.{Instant, LocalDate}
import scala.util.control.NonFatal

/** What the semantics tables under `src/test/semantics` are made of: the
  * kinds of operand a rule meets (a column of each of the engine's types, and
  * the literals a query writes), each kind's edge values, a query bound over
  * one-column tables, an expression evaluated on both paths, a cell printed
  * as one stable line, and a diff of two tables.
  *
  * A cell is a value of its result type, printed as an slt exact column
  * prints it ([[Values.exact]], a STRING quoted), or an error: `bind error`
  * when the query is refused before it runs, `run error` when it fails on
  * the value. A value or a run error is the same from `Expr.eval` on a row as
  * from `Expr.evalVec` on a batch of that row, and from `evalVec` on a batch
  * of all of an operand kind's values; where it is not, the cell says the
  * paths disagree, and the tables' test fails. */
object Semantics {

  // ---------------------------------------------------------------------------
  // Operand kinds and their edge values
  // ---------------------------------------------------------------------------

  /** What an operand is: a column of a type, or a literal the query writes,
    * which binds by what is written and, a number, by what it meets. */
  sealed abstract class Kind(val label: String) { override def toString: String = label }
  final case class Column(t: DataType) extends Kind(DataType.sqlName(t))
  case object NullLiteral extends Kind("NULL literal")
  case object TextLiteral extends Kind("text literal")
  case object IntegerLiteral extends Kind("integer literal")
  case object DecimalLiteral extends Kind("decimal literal")

  /** The ten types, DECIMAL at two precisions. */
  val ColumnTypes: Seq[DataType] = Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
    DataType.DecimalType(5, 2), DataType.DecimalType(38, 10), DataType.BooleanType, DataType.StringType,
    DataType.DateType, DataType.TimestampType, DataType.BinaryType)

  val Kinds: Seq[Kind] = ColumnTypes.map(Column) ++ Seq(NullLiteral, TextLiteral, IntegerLiteral, DecimalLiteral)

  /** One operand: a value of a column (`value`), or a literal (`sql`); `shown`
    * is how a table writes it. */
  final case class Operand(kind: Kind, value: Any, shown: String) {
    /** The SQL that reads this operand, `column` naming its column. */
    def sql(column: String): String = kind match {
      case _: Column => column
      case _ => shown
    }
    def isColumn: Boolean = kind.isInstanceOf[Column]
  }

  private def column(t: DataType)(values: Any*): Seq[Operand] =
    (null +: values).map(v => Operand(Column(t), v, showValue(v, t)))

  private def decimals(t: DataType.DecimalType)(values: String*): Seq[Operand] =
    column(t)(values.map(new JBigDecimal(_)): _*)

  private def literals(kind: Kind)(texts: String*): Seq[Operand] = texts.map(Operand(kind, null, _))

  /** Text that is almost a value of some type, and text that is one. */
  val Texts: Seq[String] = Seq("", " 5", "5d", "1e400", "0x10", "+5", "007", "nan", "TRUE", "true ", "2026-02-30",
    "2026-01-01 05:30:21+02:00", "1", "5", "-2.5", "2147483648", "NaN", "-Infinity", "true", "2026-01-01")

  /** The first and last instants a TIMESTAMP holds, as a vector boxes them. */
  private def micros(m: Long): Instant = Instant.ofEpochSecond(m / 1000000L, (m % 1000000L) * 1000L)

  /** Each kind's edge values: NULL; zeros, NaN and the infinities; 2.7, -2.7
    * and 2.5, which a cast to an integer truncates; each integer type's
    * smallest and largest; 16777217, the first INT a FLOAT loses, and
    * 9007199254740993, the first BIGINT a DOUBLE loses; numbers past a FLOAT's
    * range and below its smallest; a DECIMAL's largest; the first and last
    * DATE and TIMESTAMP; a few bytes; the empty string and text that is almost
    * a value. */
  def edges(kind: Kind): Seq[Operand] = kind match {
    case Column(t @ DataType.IntType) => column(t)(0, Int.MinValue, Int.MaxValue, 16777217)
    case Column(t @ DataType.LongType) =>
      column(t)(0L, Long.MinValue, Long.MaxValue, 16777217L, 2147483648L, 9007199254740993L)
    case Column(t @ DataType.FloatType) => column(t)(0.0f, -0.0f, Float.NaN, Float.PositiveInfinity,
      Float.NegativeInfinity, 2.7f, -2.7f, 2.5f, 16777216.0f, Float.MaxValue)
    case Column(t @ DataType.DoubleType) => column(t)(0.0, -0.0, Double.NaN, Double.PositiveInfinity,
      Double.NegativeInfinity, 2.7, -2.7, 2.5, 16777217.0, 2147483648.0, 9.3e18, 1.0e300, 1.0e-300)
    case Column(t @ DataType.DecimalType(5, 2)) => decimals(t)("0.00", "2.70", "-2.70", "2.50", "999.99", "-999.99")
    case Column(t @ DataType.DecimalType(38, 10)) => decimals(t)("0.0000000000", "2.7000000000", "-2.7000000000",
      "2.5000000000", "0.0000000001", "2147483648.0000000000", "9999999999999999999999999999.9999999999",
      "-9999999999999999999999999999.9999999999")
    case Column(t @ DataType.BooleanType) => column(t)(true, false)
    case Column(t @ DataType.StringType) => column(t)(Texts: _*)
    case Column(t @ DataType.DateType) => column(t)(LocalDate.ofEpochDay(Int.MinValue), LocalDate.ofEpochDay(0),
      LocalDate.parse("2026-01-01"), LocalDate.ofEpochDay(Int.MaxValue))
    case Column(t @ DataType.TimestampType) => column(t)(micros(Long.MinValue), Instant.EPOCH,
      Instant.parse("2026-01-01T05:30:21.123456Z"), micros(Long.MaxValue))
    case Column(t @ DataType.BinaryType) => column(t)(Array.emptyByteArray, Array[Byte](0x00, 0xff.toByte))
    case Column(other) => throw new IllegalArgumentException(s"no edge values for $other")
    case NullLiteral => literals(NullLiteral)("NULL")
    case TextLiteral => literals(TextLiteral)(Texts.map(quote): _*)
    case IntegerLiteral => literals(IntegerLiteral)("0", "16777217", "2147483647", "2147483648", "-2147483648",
      "9223372036854775807", "-9223372036854775808", "9223372036854775808",
      "99999999999999999999999999999999999999")
    case DecimalLiteral => literals(DecimalLiteral)("0.0", "-0.0", "2.7", "-2.7", "2.5", "1.5e10", "1e300",
      "0.00000000000000000000000000000000000001")
  }

  /** A kind's typical value: a number, text or date that every function of
    * the kind's type takes. */
  def typical(kind: Kind): Operand = kind match {
    case Column(t) => Operand(kind, typicalValue(t), showValue(typicalValue(t), t))
    case NullLiteral => Operand(kind, null, "NULL")
    case TextLiteral => Operand(kind, null, "'abc'")
    case IntegerLiteral => Operand(kind, null, "2")
    case DecimalLiteral => Operand(kind, null, "2.5")
  }

  private def typicalValue(t: DataType): Any = t match {
    case DataType.IntType => 2
    case DataType.LongType => 2L
    case DataType.FloatType => 2.5f
    case DataType.DoubleType => 2.5
    case d: DataType.DecimalType => new JBigDecimal("2.5").setScale(d.scale)
    case DataType.BooleanType => true
    case DataType.StringType => "abc"
    case DataType.DateType => LocalDate.parse("2026-01-01")
    case DataType.TimestampType => Instant.parse("2026-01-01T05:30:21.123456Z")
    case DataType.BinaryType => Array[Byte](0x00, 0xff.toByte)
    case other => throw new IllegalArgumentException(s"no typical value for $other")
  }

  def quote(s: String): String = "'" + s.replace("'", "''") + "'"

  // ---------------------------------------------------------------------------
  // Outcomes and how a table prints them
  // ---------------------------------------------------------------------------

  sealed trait Outcome
  final case class Value(t: DataType, v: Any) extends Outcome
  final case class BindError(e: Throwable) extends Outcome
  final case class RunError(e: Throwable) extends Outcome
  /** The row path and the vector path answered differently. */
  final case class Diverged(byRow: String, byVector: String) extends Outcome

  def show(o: Outcome): String = o match {
    case Value(t, v) => s"${DataType.sqlName(t)} ${showValue(v, t)}"
    case BindError(e) => s"bind error: ${message(e)}"
    case RunError(e) => s"run error: ${message(e)}"
    case Diverged(r, v) => s"paths disagree: eval $r, evalVec $v"
  }

  /** A value as an slt exact column prints it, a STRING quoted so that its
    * spaces show. */
  def showValue(v: Any, t: DataType): String = normalize(v, t) match {
    case null => "NULL"
    case s: String => if (s.isEmpty) "''" else quote(Values.exact(s))
    case other => Values.exact(other)
  }

  /** `v` as a value of `t` is boxed: the row path may compute a FLOAT as a
    * `Float` or a narrowed `Double`, which print alike once narrowed. */
  private def normalize(v: Any, t: DataType): Any = (v, t) match {
    case (null, _) => null
    case (n: Number, DataType.IntType) => n.intValue
    case (n: Number, DataType.LongType) => n.longValue
    case (n: Number, DataType.FloatType) => n.floatValue
    case (n: Number, DataType.DoubleType) => n.doubleValue
    case _ => v
  }

  /** An error's message on one line. */
  def message(e: Throwable): String = {
    val m = String.valueOf(e.getMessage)
    val firstLine = m.linesIterator.nextOption().getOrElse("")
    if (e.isInstanceOf[IllegalArgumentException] || e.isInstanceOf[ArithmeticException]) firstLine
    else s"${e.getClass.getSimpleName}: $firstLine"
  }

  // ---------------------------------------------------------------------------
  // Binding and evaluating
  // ---------------------------------------------------------------------------

  /** A table `t` of `columns`, for a query to bind over. */
  def catalog(columns: Seq[(String, DataType)]): Catalog = {
    val c = new Catalog
    val schema = Schema(columns.map { case (n, t) => Field(n, t) }.toVector)
    c.register("t", DataGen.Dataset(schema, IndexedSeq.empty).singlePartition())
    c
  }

  /** `sql` bound over `t(columns)`: its plan, or the error that refused it. */
  def bindPlan(sql: String, columns: Seq[(String, DataType)]): Either[Throwable, LogicalPlan] =
    try Right(LogicalBuilder.build(sql, catalog(columns)).main)
    catch { case NonFatal(e) => Left(e) }

  /** The one expression `SELECT <expr> AS v FROM t` projects, or the error
    * that refused it. */
  def bindExpr(sql: String, columns: Seq[(String, DataType)]): Either[Throwable, Expr] =
    bindPlan(sql, columns).map {
      case LogicalProject(_, Seq((e, _))) => e
      case other => throw new IllegalStateException(s"`$sql` bound as $other, not one projection")
    }

  /** A batch of `rows` over `columns`. */
  def batch(columns: Seq[(String, DataType)], rows: Seq[Seq[Any]]): ColumnarBatch = {
    val schema = Schema(columns.map { case (n, t) => Field(n, t) }.toVector)
    val vectors = columns.indices.map { c =>
      val vector = ColumnVector.allocate(columns(c)._2, math.max(1, rows.size))
      rows.indices.foreach(r => vector.setBoxed(r, rows(r)(c)))
      vector
    }.toArray
    ColumnarBatch.fromColumns(schema, vectors, rows.size)
  }

  private def attempt(f: => Any): Either[Throwable, Any] =
    try Right(f) catch { case NonFatal(e) => Left(e) }

  /** `e` on one row holding `row`: the row path on it and the vector path on
    * a batch of it, which must agree. */
  def evaluate(e: Expr, columns: Seq[(String, DataType)], row: Seq[Any]): Outcome = {
    val b = batch(columns, Seq(row))
    val byRow = attempt(e.eval(b, 0))
    val byVector = attempt { val v = e.evalVec(b); if (v.isNull(0)) null else v.getBoxed(0) }
    agree(e.dataType, byRow, byVector)
  }

  private def agree(t: DataType, byRow: Either[Throwable, Any], byVector: Either[Throwable, Any]): Outcome = {
    def shown(x: Either[Throwable, Any]) = x.fold(err => s"error ${message(err)}", v => showValue(v, t))
    (byRow, byVector) match {
      case (Right(a), Right(b)) if showValue(a, t) == showValue(b, t) => Value(t, a)
      case (Left(a), Left(b)) if a.getClass == b.getClass && message(a) == message(b) => RunError(a)
      case _ => Diverged(shown(byRow), shown(byVector))
    }
  }

  /** `outcomes`, each of `e` on one of `rows`, held to `evalVec` on one batch
    * of all of them: each row its value, or, where some row fails, the batch
    * failing with one of those rows' errors. Returns the outcomes with any row
    * the batch disagrees on marked [[Diverged]]. */
  def acrossABatch(e: Expr, columns: Seq[(String, DataType)], rows: Seq[Seq[Any]], outcomes: Seq[Outcome]): Seq[Outcome] =
    if (rows.size < 2) outcomes
    else attempt(e.evalVec(batch(columns, rows))) match {
      case Right(vector: ColumnVector) => outcomes.zipWithIndex.map {
        case (o @ Value(t, v), i) =>
          val b = if (vector.isNull(i)) null else vector.getBoxed(i)
          if (showValue(b, t) == showValue(v, t)) o else Diverged(showValue(v, t), s"${showValue(b, t)} in a batch")
        case (RunError(err), _) => Diverged(s"error ${message(err)}", "a value in a batch of every row")
        case (o, _) => o
      }
      case Right(other) => throw new IllegalStateException(s"evalVec gave $other")
      case Left(err) =>
        val failing = outcomes.collect { case RunError(x) => message(x) }
        if (failing.contains(message(err))) outcomes
        else outcomes.map {
          case Value(t, v) => Diverged(showValue(v, t), s"error ${message(err)} in a batch")
          case o => o
        }
    }

  // ---------------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------------

  /** An error a cell shows, with the SQL that raised it and when, for the
    * test to hold to `CleanRejection`'s rule. */
  final case class Raised(sql: String, phase: CleanRejection.Phase, error: Throwable)

  /** A table as generated: its lines, every error a cell shows, and the cells
    * whose two paths disagree, or (the function sweep's) whose NULL or error
    * its entry does not explain. */
  final case class Generated(lines: Vector[String], raised: Vector[Raised], diverged: Vector[String])

  /** The errors among `outcomes` of `sql`. */
  def raised(sql: String, outcomes: Seq[Outcome]): Seq[Raised] = outcomes.collect {
    case BindError(e) => Raised(sql, CleanRejection.Phase.Bind, e)
    case RunError(e) => Raised(sql, CleanRejection.Phase.Run, e)
  }

  /** `fields` padded to `widths`, with `outcome` after an arrow. */
  def line(fields: Seq[String], widths: Seq[Int], outcome: Outcome): String =
    fields.zipAll(widths, "", 0).map { case (f, w) => f.padTo(w, ' ') + " " }.mkString + "-> " + show(outcome)

  /** The header every table starts with: what it holds, then how to read and
    * regenerate it. */
  def header(file: String, about: Seq[String]): Vector[String] =
    (Seq(s"# $file: generated by //src/test/scala/com/transformer/sql/plan:semantics_tables_test, which fails on any",
      "# difference. Regenerate with",
      "#   bazel run //src/test/scala/com/transformer/sql/plan:semantics_tables -- --update",
      "# in the commit that changes a rule, and review the diff: every cell the rule reaches, and no other.",
      "#") ++ about.map(l => if (l.isEmpty) "#" else s"# $l") :+ "").toVector

  /** `actual` against `expected`, the lines of `file`, as a unified diff of
    * their differing lines with three lines of context, or None when they
    * agree. Lines are matched as the longest common subsequence of the lines
    * the two hold once each, as patience diff does. */
  def diff(file: String, expected: Seq[String], actual: Seq[String]): Option[String] =
    if (expected == actual) None
    else {
      val ops = Diff.lines(expected.toIndexedSeq, actual.toIndexedSeq)
      Some(Diff.unified(file, ops))
    }
}

/** A line diff: the longest increasing run of lines both sides hold once
  * each, then the same between those anchors, recursively, as patience diff
  * finds it; lines between anchors that match neither way are a deletion and
  * an insertion. */
private object Diff {

  sealed trait Op
  final case class Keep(line: String, a: Int, b: Int) extends Op
  final case class Delete(line: String, a: Int) extends Op
  final case class Insert(line: String, b: Int) extends Op

  def lines(a: IndexedSeq[String], b: IndexedSeq[String]): Vector[Op] = {
    val out = Vector.newBuilder[Op]
    def go(a0: Int, a1: Int, b0: Int, b1: Int): Unit = {
      // Equal lines at either end match as they are.
      var s = 0
      var e = 0
      while (a0 + s < a1 && b0 + s < b1 && a(a0 + s) == b(b0 + s)) s += 1
      while (a1 - e > a0 + s && b1 - e > b0 + s && a(a1 - 1 - e) == b(b1 - 1 - e)) e += 1
      (0 until s).foreach(i => out += Keep(a(a0 + i), a0 + i, b0 + i))
      val (x0, x1, y0, y1) = (a0 + s, a1 - e, b0 + s, b1 - e)
      val anchors = uniqueCommon(a, x0, x1, b, y0, y1)
      if (anchors.isEmpty) {
        (x0 until x1).foreach(i => out += Delete(a(i), i))
        (y0 until y1).foreach(j => out += Insert(b(j), j))
      } else {
        var pa = x0
        var pb = y0
        anchors.foreach { case (i, j) =>
          go(pa, i, pb, j)
          out += Keep(a(i), i, j)
          pa = i + 1; pb = j + 1
        }
        go(pa, x1, pb, y1)
      }
      (0 until e).reverse.foreach(k => out += Keep(a(a1 - 1 - k), a1 - 1 - k, b1 - 1 - k))
    }
    go(0, a.size, 0, b.size)
    out.result()
  }

  /** The longest run of lines that each range holds once, in the same order
    * in both: the longest increasing subsequence of their positions. */
  private def uniqueCommon(a: IndexedSeq[String], a0: Int, a1: Int, b: IndexedSeq[String], b0: Int, b1: Int)
      : Seq[(Int, Int)] = {
    def once(s: IndexedSeq[String], from: Int, until: Int): Map[String, Int] =
      (from until until).groupBy(s(_)).collect { case (l, Seq(i)) => l -> i }
    val inB = once(b, b0, b1)
    val pairs = once(a, a0, a1).toSeq.flatMap { case (l, i) => inB.get(l).map(i -> _) }.sortBy(_._1)
    // Patience sorting: piles of b positions, each pile's top the smallest.
    val tops = scala.collection.mutable.ArrayBuffer.empty[Int]
    val back = new Array[Int](pairs.size)
    val topIndex = scala.collection.mutable.ArrayBuffer.empty[Int]
    pairs.indices.foreach { k =>
      val j = pairs(k)._2
      var lo = 0
      var hi = tops.size
      while (lo < hi) { val mid = (lo + hi) / 2; if (tops(mid) < j) lo = mid + 1 else hi = mid }
      back(k) = if (lo > 0) topIndex(lo - 1) else -1
      if (lo == tops.size) { tops += j; topIndex += k } else { tops(lo) = j; topIndex(lo) = k }
    }
    if (topIndex.isEmpty) Nil
    else {
      val run = scala.collection.mutable.ListBuffer.empty[(Int, Int)]
      var k = topIndex.last
      while (k >= 0) { run.prepend(pairs(k)); k = back(k) }
      run.toList
    }
  }

  /** `ops` as a unified diff of `file`, with three lines of context. */
  def unified(file: String, ops: Vector[Op]): String = {
    val changed = ops.indices.filter(i => !ops(i).isInstanceOf[Keep])
    val context = 3
    val hunks = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
    changed.foreach { i =>
      val (from, until) = (math.max(0, i - context), math.min(ops.size, i + context + 1))
      if (hunks.nonEmpty && from <= hunks.last._2) hunks(hunks.size - 1) = (hunks.last._1, until)
      else hunks += ((from, until))
    }
    val sb = new StringBuilder(s"--- a/src/test/semantics/$file\n+++ b/src/test/semantics/$file\n")
    hunks.foreach { case (from, until) =>
      val slice = ops.slice(from, until)
      def start(f: Op => Option[Int]) = slice.flatMap(f).headOption.getOrElse(0) + 1
      val aStart = start { case Keep(_, a, _) => Some(a); case Delete(_, a) => Some(a); case _ => None }
      val bStart = start { case Keep(_, _, b) => Some(b); case Insert(_, b) => Some(b); case _ => None }
      val aCount = slice.count(o => !o.isInstanceOf[Insert])
      val bCount = slice.count(o => !o.isInstanceOf[Delete])
      sb ++= s"@@ -$aStart,$aCount +$bStart,$bCount @@\n"
      slice.foreach {
        case Keep(l, _, _) => sb ++= s" $l\n"
        case Delete(l, _) => sb ++= s"-$l\n"
        case Insert(l, _) => sb ++= s"+$l\n"
      }
    }
    sb.result()
  }
}
