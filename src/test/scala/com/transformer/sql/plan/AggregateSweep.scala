package com.transformer.sql.plan

import com.transformer.core._
import com.transformer.fuzz.DataGen
import com.transformer.fuzz.oracle.{ExprParity, RelEngine}
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.plan.FunctionCatalog.{DistinctUse, Entry, Kind => FnKind}
import com.transformer.sql.plan.Semantics._

import java.math.{BigDecimal => JBigDecimal}
import java.time.{Instant, LocalDate}
import scala.util.control.NonFatal

/** The aggregate and window sweep, `aggregates.txt` and `windows.txt`: every
  * aggregate of `FunctionCatalog` over a column of each of the ten types, at
  * each of the data sets that decide its answer (none, all NULL, one value,
  * duplicates beside a NULL, NaN among numbers, both zeros, sums at a
  * BIGINT's edge and a DECIMAL(38,s)'s), as a group's aggregate, with and
  * without DISTINCT where it takes one, and where the catalog says it runs as
  * a window function, over the whole partition, a ROWS frame and a RANGE
  * frame; and the ranking and offset functions over the same data.
  *
  * Each query runs over two layouts, one partition of one batch and one row
  * per partition in batches of one, which take the aggregates' row and merge
  * paths and the windows' too, and must give one answer. A NULL is explained
  * by the entry's NULL rule or its domain over the rows that reach it, and an
  * error is one the entry declares. */
object AggregateSweep {

  val AggregatesFile = "aggregates.txt"
  val WindowsFile = "windows.txt"

  /** One data set: its name and its values, in the order of `k`, which
    * orders the rows. */
  final case class DataSet(name: String, values: Seq[Any])

  private def dec(t: DataType.DecimalType, s: String): JBigDecimal = new JBigDecimal(s).setScale(t.scale)

  private def two(t: DataType): (Any, Any) = t match {
    case DataType.IntType => (2, 5)
    case DataType.LongType => (2L, 5L)
    case DataType.FloatType => (2.5f, -1.0f)
    case DataType.DoubleType => (2.5, -1.0)
    case d: DataType.DecimalType => (dec(d, "2.5"), dec(d, "-1"))
    case DataType.BooleanType => (true, false)
    case DataType.StringType => ("b", "a")
    case DataType.DateType => (LocalDate.parse("2026-01-02"), LocalDate.parse("2026-01-01"))
    case DataType.TimestampType => (Instant.parse("2026-01-01T05:30:21.123456Z"), Instant.EPOCH)
    case DataType.BinaryType => (Array[Byte](0x00, 0xff.toByte), Array.emptyByteArray)
    case other => throw new IllegalArgumentException(s"no values for $other")
  }

  /** The data sets for a column of `t`. */
  def dataSets(t: DataType): Seq[DataSet] = {
    val (v, w) = two(t)
    Seq(DataSet("none", Nil), DataSet("all NULL", Seq(null, null, null)), DataSet("one value", Seq(v)),
      DataSet("duplicates", Seq(v, null, v, w))) ++ (t match {
      case DataType.FloatType => Seq(DataSet("NaN among numbers", Seq(1.5f, Float.NaN, -2.0f)),
        DataSet("both zeros", Seq(0.0f, -0.0f)))
      case DataType.DoubleType => Seq(DataSet("NaN among numbers", Seq(1.5, Double.NaN, -2.0)),
        DataSet("both zeros", Seq(0.0, -0.0)))
      case DataType.IntType => Seq(DataSet("INT's edges", Seq(Int.MaxValue, Int.MaxValue, Int.MinValue)))
      case DataType.LongType => Seq(DataSet("a sum past BIGINT", Seq(Long.MaxValue, 1L)),
        DataSet("a sum past BIGINT and back", Seq(Long.MaxValue, 1L, -1L)))
      case d @ DataType.DecimalType(38, _) =>
        val max = dec(d, "9999999999999999999999999999.9999999999")
        Seq(DataSet("a sum past DECIMAL(38,s)", Seq(max, max)), DataSet("a sum past DECIMAL(38,s) and back",
          Seq(max, max, max.negate)))
      case _ => Nil
    })
  }

  /** `t(k INT, x <t>, y <t>)`, `k` numbering the rows from 1, `y` holding
    * `values` in the other order, laid out as one partition of one batch or
    * as one row per partition in batches of one. */
  private def catalog(t: DataType, values: Seq[Any], rowPerPartition: Boolean): Catalog = {
    val schema = Schema(Vector(Field("k", DataType.IntType), Field("x", t), Field("y", t)))
    val rows = values.indices.map(i => Array[Any](i + 1, values(i), values(values.size - 1 - i)))
    val data = DataGen.Dataset(schema, rows)
    val c = new Catalog
    c.register("t", if (rowPerPartition) data.evenPartitions(math.max(1, rows.size), 1) else data.singlePartition())
    c
  }

  /** `sql` over each layout: its result, the column `v` of every row in the
    * order of `k` (a list where `listed`, else the one row's value), or the
    * error; the two must agree. */
  private def run(sql: String, t: DataType, values: Seq[Any], listed: Boolean): (Outcome, Seq[Any]) = {
    def once(perRow: Boolean): Either[Throwable, (DataType, Seq[Any])] =
      try {
        val q = SqlEngine.execute(sql, catalog(t, values, perRow), ExecutionOptions.Default)
        val rows = RelEngine.collectRows(q)
        Right((q.schema.fields.head.dataType, rows.map(_(0))))
      } catch { case NonFatal(e) => Left(e) }
    def shown(r: Either[Throwable, (DataType, Seq[Any])]) =
      r.fold(e => s"error ${message(e)}", { case (rt, vs) => vs.map(showValue(_, rt)).mkString("[", ", ", "]") })
    val (one, perRow) = (once(perRow = false), once(perRow = true))
    (one, perRow) match {
      case (Right((rt, a)), Right((_, b))) if shown(one) == shown(perRow) || (rt == DataType.DoubleType &&
          a.size == b.size && a.zip(b).forall { case (x, y) => close(x, y) }) =>
        (Value(rt, if (listed || a.size != 1) Rows(a) else a.head), a)
      case (Left(a), Left(b)) if a.getClass == b.getClass && message(a) == message(b) => (RunError(a), Nil)
      case _ => (Diverged(shown(one), shown(perRow) + " over a row per partition"), Nil)
    }
  }

  /** Two DOUBLEs of a statistic, the same to twelve significant digits. */
  private def close(x: Any, y: Any): Boolean = (x, y) match {
    case (a: java.lang.Double, b: java.lang.Double) =>
      a.equals(b) || math.abs(a - b) <= 1e-12 * math.max(math.abs(a), math.abs(b))
    case _ => x == y
  }

  /** A window's values, one per row, shown as a list. */
  final case class Rows(values: Seq[Any])

  private def shown(o: Outcome): String = o match {
    case Value(t, Rows(vs)) => s"${DataType.sqlName(t)} ${vs.map(showValue(_, t)).mkString("[", ", ", "]")}"
    case other => show(other)
  }

  /** The non-NULL arguments of each row of `values` that reach an aggregate
    * of `arity` (one argument, `x`, or two, `x` and `y`). */
  private def reaching(values: Seq[Any], arity: Int): Seq[Seq[Any]] = {
    val pairs = values.indices.map(i => if (arity == 1) Seq(values(i)) else Seq(values(i), values(values.size - 1 - i)))
    pairs.filter(_.forall(_ != null))
  }

  private val Widths = Seq(46, 16, 22)

  def generateAggregates(): Generated = generate(AggregatesFile)

  def generateWindows(): Generated = generate(WindowsFile)

  private def generate(file: String): Generated = {
    val lines = Vector.newBuilder[String]
    val raised = Vector.newBuilder[Raised]
    val wrong = Vector.newBuilder[String]
    def cell(expr: String, kind: String, data: String, sql: String, outcome: Outcome, why: Option[String],
        problem: Option[String]): Unit = {
      val l = Seq(expr, kind, data).zipAll(Widths, "", 0).map { case (f, w) => f.padTo(w, ' ') + " " }.mkString +
        "-> " + shown(outcome) + why.fold("")(w => s" ($w)")
      lines += l
      raised ++= Semantics.raised(sql, Seq(outcome))
      if (outcome.isInstanceOf[Diverged]) wrong += l
      problem.foreach(p => wrong += s"$l: $p")
    }
    /** Sweep `expr` (written over `x`, `y` and `k`) over every type and data
      * set, each NULL held to `explain` (a frame's rows by position) and each
      * error to `entry`. */
    def sweep(expr: String, sqlOf: String => String, entry: Option[Entry],
        explain: (Seq[Any], Int) => Option[String], listed: Boolean, types: Seq[DataType] = ColumnTypes): Unit =
      for (t <- types) {
        val sets = dataSets(t)
        val bound = bindPlan(sqlOf(expr), Seq("k" -> DataType.IntType, "x" -> t, "y" -> t))
        bound match {
          case Left(e) => cell(expr, DataType.sqlName(t), "(any)", sqlOf(expr), BindError(e), None, None)
          case Right(_) => sets.foreach { set =>
            val sql = sqlOf(expr)
            val (outcome, results) = run(sql, t, set.values, listed)
            val (why, problem) = outcome match {
              case Value(_, Rows(vs)) =>
                val unexplained = vs.indices.filter(i => vs(i) == null && explain(set.values, i).isEmpty)
                (None, if (unexplained.isEmpty) None else Some(s"a NULL on row ${unexplained.head + 1} nothing explains"))
              case Value(_, null) if results.size == 1 =>
                explain(set.values, -1) match {
                  case Some(w) => (Some(w), None)
                  case None => (None, Some("a NULL nothing explains"))
                }
              case RunError(err) =>
                val m = message(err)
                if (!entry.exists(_.errors.exists(_.matches(m)))) (None, Some("an error its entry does not declare"))
                else if (!ExprParity.owned(err)) (None, Some("an error ExprParity does not own"))
                else (None, None)
              case _ => (None, None)
            }
            cell(expr, DataType.sqlName(t), set.name, sql, outcome, why, problem)
          }
        }
      }
    def overAll(sql: String) = s"SELECT $sql AS v FROM t"
    def ordered(sql: String) = s"SELECT $sql AS v FROM t ORDER BY k"
    val aggregates = FunctionCatalog.Entries.filter(_.kind == FnKind.Aggregate)
    file match {
      case AggregatesFile =>
        for (e <- aggregates) {
          val arity = e.arity.min
          val args = if (arity == 2) "x, y" else "x"
          // DISTINCT cannot make an empty set of rows less empty, which is all
          // a NULL of these aggregates reads.
          val explain = (values: Seq[Any], _: Int) => e.explainsNullOver(reaching(values, arity))
          sweep(s"${e.name}($args)", overAll, Some(e), explain, listed = false)
          // A star reads no column, so one type's rows show it.
          if (e.star) sweep(s"${e.name}(*)", overAll, Some(e), (_, _) => None, listed = false,
            types = Seq(DataType.IntType))
          if (e.distinct != DistinctUse.Refused)
            sweep(s"${e.name}(DISTINCT $args)", overAll, Some(e), explain, listed = false)
          else {
            val sql = overAll(s"${e.name}(DISTINCT $args)")
            val outcome = bindPlan(sql, Seq("k" -> DataType.IntType, "x" -> DataType.IntType, "y" -> DataType.IntType))
              .fold(BindError, _ => Value(DataType.NullType, "(binds)"))
            cell(s"${e.name}(DISTINCT $args)", "INT", "(any)", sql, outcome, None, None)
          }
        }
      case WindowsFile =>
        // The rows of each frame, by position, for `n` rows and the row `i`:
        // with the keys 1, 2, ..., a RANGE of 1 PRECEDING is a ROWS one.
        val frames: Seq[(String, (Int, Int) => Seq[Int])] = Seq(
          "OVER ()" -> ((n: Int, _: Int) => 0 until n),
          "OVER (ORDER BY k ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)" -> ((_: Int, i: Int) => math.max(0, i - 1) to i),
          "OVER (ORDER BY k RANGE BETWEEN 1 PRECEDING AND CURRENT ROW)" -> ((_: Int, i: Int) => math.max(0, i - 1) to i))
        for (e <- aggregates if e.window; (over, frame) <- frames) {
          val arity = e.arity.min
          sweep(s"${e.name}(x) $over", ordered, Some(e), (values, i) => {
            val rows = frame(values.size, i).map(j => Seq(values(j)))
            e.explainsNullOver(rows.filter(_.forall(_ != null)))
          }, listed = true)
          require(arity == 1, s"${e.name} runs as a window function with $arity arguments")
        }
        for (e <- aggregates if !e.window)
          sweep(s"${e.name}(x, y) OVER ()", ordered, Some(e), (_, _) => None, listed = true)
        for (e <- FunctionCatalog.Entries if e.kind == FnKind.Ranking) {
          // ROW_NUMBER numbers ties in the order of k, so its answer is one.
          val order = if (e.name == "ROW_NUMBER") "x, k" else "x"
          sweep(s"${e.name}() OVER (ORDER BY $order)", ordered, Some(e), (_, _) => None, listed = true)
        }
        for (e <- FunctionCatalog.Entries if e.kind == FnKind.Offset) {
          val step = if (e.name == "LAG") -1 else 1
          def at(values: Seq[Any], i: Int) = values.lift(i + step)
          sweep(s"${e.name}(x) OVER (ORDER BY k)", ordered, Some(e), (values, i) => at(values, i) match {
            case None => Some("no row that far away")
            case Some(null) => Some("the row that far away holds NULL")
            case _ => None
          }, listed = true)
          sweep(s"${e.name}(x, 1, y) OVER (ORDER BY k)", ordered, Some(e), (values, i) => at(values, i) match {
            case None if values(values.size - 1 - i) == null => Some("no row that far away, and a NULL default")
            case Some(null) => Some("the row that far away holds NULL")
            case _ => None
          }, listed = true)
        }
      case other => throw new IllegalArgumentException(s"no table $other")
    }
    Generated(header(file, About(file)) ++ lines.result(), raised.result(), wrong.result())
  }

  private def About(file: String): Seq[String] = Seq(
    if (file == AggregatesFile)
      "The aggregate sweep: every aggregate, with DISTINCT where it takes it, over all of t's rows."
    else
      "The window sweep: every aggregate that runs as a window function, over the whole partition, a ROWS frame and",
    if (file == AggregatesFile) "" else "a RANGE frame, and the ranking and offset functions.",
    "",
    "t(k, x, y): x a column of each type, y its values in the other order, k numbering the rows, at each data set",
    "that decides an answer: none, all NULL, one value, duplicates beside a NULL, NaN among numbers, both zeros, and",
    "sums past a BIGINT's and a DECIMAL(38,s)'s range, one past and back. A two-argument aggregate reads x and y. A",
    "window's cell is its value on each row, in the order of k.",
    "",
    "Each query runs over one partition of one batch and over one row per partition in batches of one, and both give",
    "the cell; a DOUBLE agrees to twelve significant digits, since a statistic merges its partial sums in the order the",
    "layout gives (the cell is the one partition's). A NULL that an empty or too small a set of rows gives names the",
    "rule of the entry that explains it; every NULL is explained by its entry, and every run-time error is one the",
    "entry declares.")
}
