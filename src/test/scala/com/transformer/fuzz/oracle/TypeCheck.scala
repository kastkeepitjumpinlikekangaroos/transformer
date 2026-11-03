package com.transformer.fuzz.oracle

import com.transformer.core.{DataType, ExecutionOptions, Schema}
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.{DataGen, Rng, RowOracle}
import com.transformer.sql.plan._

import java.util.Locale
import scala.util.Try

/** One rule across the integer types (TypeCheck, PVLDB 2026): the same
  * values stored as INT, as BIGINT and as DECIMAL(10,0) give the same
  * answers.
  *
  * Each INT column of a generated environment is stored again as a BIGINT
  * ([[widened]]) and as a DECIMAL(10,0), and the query runs over each. Where
  * the INT form answers, each wider form answers the same, as numbers: a
  * comparison, a join, GROUP BY, DISTINCT, ORDER BY, MIN, MAX, COUNT, SUM,
  * `+`, `-` and `*` mean one thing over all three. An INT overflow may become
  * an answer in the wider type, never the reverse. Two rules differ by design
  * and are left out: integer `/` truncates where DECIMAL's does not, so a
  * query with `/` is not run as DECIMAL (between INT and BIGINT it stays), and
  * AVG and the statistics of an integer are DOUBLEs where a DECIMAL's AVG is a
  * DECIMAL, so a query with one is not run at all. A wider form the binder
  * refuses (a DECIMAL beside a DOUBLE no query wrote, an integer argument a
  * function takes as an INT only) has nothing to check.
  *
  * One more difference is a finding held for the user (plans/testing/16,
  * T1), not failed: an INT or a BIGINT compared with a FLOAT or DOUBLE
  * column is read as a DOUBLE, and a DECIMAL as a decimal, the float cast to
  * it, which NaN and the infinities fail (`Cannot cast NaN to
  * decimal(10,0)`), where arithmetic, CASE, COALESCE and GREATEST refuse a
  * DECIMAL beside a float as the query binds.
  *
  * The wider answer is read in the INT answer's column types (a BIGINT or a
  * DECIMAL that is an INT, a DECIMAL that is a DOUBLE) and compared as a
  * multiset ([[RowOracle.multisetEquals]], a DOUBLE within its tolerance).
  * Only a layout-invariant query is checked: a DECIMAL key hashes another
  * way than an INT, so a join emits its rows in another order, which a
  * tie-sensitive window reads.
  */
object TypeCheck {

  /** The two wider forms an INT column is stored as. */
  val Wider: Seq[DataType] = Seq(DataType.LongType, DataType.DecimalType(10, 0))

  /** `env` with each INT column stored as `to`. */
  def widened(env: RelEnv, to: DataType): RelEnv = RelEnv(env.relations.map { r =>
    val fields = r.schema.fields
    val ints = fields.indices.filter(i => fields(i).dataType == DataType.IntType).toSet
    if (ints.isEmpty) r
    else {
      val schema = Schema(fields.map(f => if (f.dataType == DataType.IntType) f.copy(dataType = to) else f))
      val rows = r.dataset.rows.map(row => row.indices.map { i =>
        if (!ints(i) || row(i) == null) row(i)
        else to match {
          case DataType.LongType => row(i).asInstanceOf[Number].longValue: Any
          case _ => java.math.BigDecimal.valueOf(row(i).asInstanceOf[Number].longValue): Any
        }
      }.toArray)
      r.copy(dataset = DataGen.Dataset(schema, rows))
    }
  })

  /** Whether `env` stores an INT column. */
  def holdsAnInt(env: RelEnv): Boolean = env.relations.exists(_.schema.fields.exists(_.dataType == DataType.IntType))

  /** Whether `sql` takes an AVG or a statistic, whose type is a DOUBLE over
    * an integer and a DECIMAL over a DECIMAL. */
  def averages(sql: String): Boolean = {
    val upper = sql.toUpperCase(Locale.ROOT)
    Seq("AVG(", "STDDEV", "VAR_", "VARIANCE", "COVAR", "CORR(").exists(upper.contains)
  }

  /** How one wider form fared: `held`, `wider answered` (the INT form failed
    * on its data and the wider one answered), `both failed`, `refused` (the
    * binder refused the wider form), `moved` (a WHERE or ON condition that
    * can fail is there to move), `float as a decimal` (finding T1, above) or
    * `not run`. */
  final case class Form(to: DataType, kind: String)

  /** What one case checked: nothing (`skipped`, with why), or each form. */
  final case class Outcome(skipped: Option[String], rows: Int, forms: Vector[Form])

  /** Run `mc`'s body over its INT columns and over each wider form of them;
    * an [[AssertionError]] says which form answered otherwise. */
  def check(mc: MetaCase): Outcome =
    if (!mc.query.isLayoutInvariant) Outcome(Some("not layout-invariant"), 0, Vector.empty)
    else check(mc.env, mc.query.renderBody, mc.query.movableFailure)

  /** [[check]] of `sql` over `env`, a WHERE or ON condition of which can fail
    * where `movable` says. */
  def check(env: RelEnv, sql: String, movable: Boolean): Outcome = {
    val opts = ExecutionOptions.Default
    if (!holdsAnInt(env)) return Outcome(Some("no INT column"), 0, Vector.empty)
    if (averages(sql)) return Outcome(Some("an AVG or a statistic"), 0, Vector.empty)
    val base =
      try RelEngine.answer(env.catalog(), sql, opts)
      catch { case _: IllegalArgumentException => return Outcome(Some("the INT form does not bind"), 0, Vector.empty) }
    val forms = Wider.toVector.map { to =>
      if (to.isInstanceOf[DataType.DecimalType] && sql.contains("/")) Form(to, "not run")
      else {
        val wide =
          try Right(RelEngine.answer(widened(env, to).catalog(), sql, opts))
          catch { case _: IllegalArgumentException => Left("refused") }
        def fail(why: String): Nothing = throw new AssertionError(
          s"[type-check] the INT columns stored as ${name(to)} answer otherwise: $why\n  body = $sql\n  env  = $env")
        (base, wide) match {
          case (_, Left(_)) => Form(to, "refused")
          case (Right(a), Right(Right(b))) =>
            val read = b.rows.map(row => row.indices.map(i => in(row(i), a.schema.fields(i).dataType)).toArray)
            RowOracle.multisetEquals(a.schema, a.rows, read).foreach(fail)
            Form(to, "held")
          case (Right(_), Right(Left(failure))) =>
            if (movable) Form(to, "moved")
            else if (to.isInstanceOf[DataType.DecimalType] && FloatAsDecimal.findFirstIn(failure).isDefined)
              Form(to, "float as a decimal")
            else fail(s"it fails on its data ($failure) where the INT form answers")
          case (Left(_), Right(Right(_))) => Form(to, "wider answered")
          case (Left(_), Right(Left(_))) => Form(to, "both failed")
        }
      }
    }
    Outcome(None, base.fold(_ => 0, _.rows.length), forms)
  }

  /** The failure of a float a DECIMAL comparison reads as a decimal
    * (finding T1). */
  private val FloatAsDecimal = """^Cannot cast (?:NaN|-?Infinity) to decimal\(""".r

  private def name(t: DataType): String = t match {
    case DataType.LongType => "BIGINT"
    case d: DataType.DecimalType => s"DECIMAL(${d.precision},${d.scale})"
    case other => other.toString
  }

  /** `v`, a wider form's value, read as a value of `t`, the INT form's type
    * for its column, where it is one exactly; otherwise as it is, which then
    * compares unequal. */
  private def in(v: Any, t: DataType): Any = (v, t) match {
    case (null, _) => null
    case (n: java.lang.Long, DataType.IntType) if n >= Int.MinValue && n <= Int.MaxValue => n.intValue
    case (d: java.math.BigDecimal, DataType.IntType) => Try(d.intValueExact: Any).getOrElse(v)
    case (d: java.math.BigDecimal, DataType.LongType) => Try(d.longValueExact: Any).getOrElse(v)
    case (n: Number, DataType.DoubleType) if !n.isInstanceOf[java.lang.Double] => n.doubleValue
    case (n: Number, DataType.FloatType) if !n.isInstanceOf[java.lang.Float] => n.floatValue
    case (n: java.lang.Long, _: DataType.DecimalType) => java.math.BigDecimal.valueOf(n.longValue)
    case (n: Integer, _: DataType.DecimalType) => java.math.BigDecimal.valueOf(n.longValue)
    case _ => v
  }

  /** The sites `plan`, the INT form's, reaches with an INT value: a
    * comparison, a join's equality, a GROUP BY key, DISTINCT, an ORDER BY key
    * (a sort's or a window's), MIN, MAX, COUNT, SUM, and `+`, `-` and `*`. */
  def sites(plan: LogicalPlan): Set[String] = {
    val out = scala.collection.mutable.HashSet.empty[String]
    def isInt(e: Expr): Boolean = e.dataType == DataType.IntType
    def expr(e: Expr): Unit = {
      e match {
        case BinOpExpr("=" | "<>" | "<" | "<=" | ">" | ">=", l, r, _) if isInt(l) || isInt(r) => out += "comparison"
        case BinOpExpr(op @ ("+" | "-" | "*"), _, _, t) if t == DataType.IntType => out += op
        case _ => ()
      }
      com.transformer.fuzz.Shrinker.childrenOf(e).foreach(expr)
    }
    def agg(a: AggExpr): Unit = {
      if (a.args.exists(isInt)) a match {
        case _: AggExprMin => out += "MIN"
        case _: AggExprMax => out += "MAX"
        case _: AggExprCount => out += "COUNT"
        case _: AggExprSum => out += "SUM"
        case AggExprDistinct(_: AggExprSum) => out += "SUM"
        case _ => ()
      }
      a.args.foreach(expr)
    }
    def node(p: LogicalPlan): Unit = {
      p match {
        case LogicalJoin(_, _, cond, _) =>
          def eqs(c: Expr): Seq[Expr] = c match {
            case BinOpExpr("AND", l, r, _) => eqs(l) ++ eqs(r)
            case other => Seq(other)
          }
          if (eqs(cond).exists { case BinOpExpr("=", l, r, _) => isInt(l) && isInt(r); case _ => false }) out += "join"
          expr(cond)
        case LogicalAggregate(_, keys, aggs) =>
          if (keys.exists(k => isInt(k._1))) out += "GROUP BY"
          keys.foreach(k => expr(k._1))
          aggs.foreach(a => agg(a._1))
        case LogicalDistinct(c) => if (c.outputSchema.fields.exists(_.dataType == DataType.IntType)) out += "DISTINCT"
        case LogicalUnion(l, _, false) => if (l.outputSchema.fields.exists(_.dataType == DataType.IntType)) out += "DISTINCT"
        case LogicalSort(_, keys) =>
          if (keys.exists(k => isInt(k.expr))) out += "ORDER BY"
          keys.foreach(k => expr(k.expr))
        case LogicalFilter(_, pred) => expr(pred)
        case LogicalProject(_, ps) => ps.foreach(x => expr(x._1))
        case LogicalWindow(_, ws) => ws.foreach { w =>
          if (w.spec.orderKeys.exists(k => isInt(k.expr))) out += "ORDER BY"
          (w.spec.partitionKeys ++ w.spec.orderKeys.map(_.expr)).foreach(expr)
          w.fn match {
            case WindowFnAgg(a) => agg(a)
            case WindowFnLag(v, _, d) => (v +: d.toSeq).foreach(expr)
            case WindowFnLead(v, _, d) => (v +: d.toSeq).foreach(expr)
            case _ => ()
          }
        }
        case _ => ()
      }
      p.children.foreach(node)
    }
    node(plan)
    out.toSet
  }

  val Sites: Seq[String] = Seq("comparison", "join", "GROUP BY", "DISTINCT", "ORDER BY", "MIN", "MAX", "COUNT", "SUM",
    "+", "-", "*")

  // ---------------------------------------------------------------------------
  // Queries aimed at each site
  // ---------------------------------------------------------------------------

  /** A query aimed at the sites over two tables of INT columns, `r0(k, a, b,
    * s)` and `r1(k, a, d)`, whose values sit around zero and at INT's edges
    * (and its square root, whose product is at the edge), so arithmetic
    * overflows as an INT now and then and answers as a wider type. */
  final case class TargetedCase(env: RelEnv, sql: String) {
    override def toString: String = s"TargetedCase(\n  sql = $sql\n  env = $env)"
  }

  private val IntPool: IndexedSeq[Any] =
    IndexedSeq(-2, -1, 0, 1, 2, 3, 46340, 46341, 65536, Int.MaxValue, Int.MinValue, Int.MaxValue - 1)

  def generateTargeted(rng: Rng): TargetedCase = {
    val dataRng = rng.split()
    def ints(nullProb: Double, narrow: Boolean): Any =
      if (dataRng.bool(nullProb)) null
      else if (narrow) dataRng.between(0, 3)
      else if (dataRng.bool(0.3)) dataRng.oneOf(IntPool) else dataRng.between(-3, 5)
    def relation(name: String, fields: Seq[(String, DataType)]): Relation = {
      val n = dataRng.between(0, 10)
      val rows = (0 until n).map { _ =>
        fields.map {
          case ("k", _) => ints(0.15, narrow = true)
          case (_, DataType.IntType) => ints(0.15, narrow = false)
          case (_, DataType.DoubleType) => if (dataRng.bool(0.15)) null else dataRng.oneOf(Seq(-1.5, 0.0, 2.5, 46340.5))
          case (_, _) => if (dataRng.bool(0.15)) null else dataRng.oneOf(Seq("a", "b", ""))
        }.toArray[Any]
      }
      Relation(name, DataGen.Dataset(Schema(fields.map { case (n, t) => com.transformer.core.Field(n, t) }.toVector), rows),
        Vector(0))
    }
    val env = RelEnv(Vector(
      relation("r0", Seq("k" -> DataType.IntType, "a" -> DataType.IntType, "b" -> DataType.IntType,
        "s" -> DataType.StringType)),
      relation("r1", Seq("k" -> DataType.IntType, "a" -> DataType.IntType, "d" -> DataType.DoubleType))))
    val q = rng.split()
    def arith(): String = q.oneOf(Seq("+", "-", "*"))
    def cmp(): String = q.oneOf(Seq("=", "<>", "<", "<=", ">", ">="))
    def value(alias: String): String = q.weighted[() => String](
      (2, () => s"$alias.a"), (2, () => s"$alias.a ${arith()} $alias.k"),
      (1, () => s"$alias.a ${arith()} ${q.oneOf(Seq("1", "2", "46341", "2147483647"))}"))()
    def kind(): String = q.oneOf(Seq("INNER", "LEFT", "RIGHT", "FULL"))
    val sql = q.weighted[() => String](
      (3, () => s"SELECT ${value("t0")} AS x, t1.a ${arith()} t0.b AS y FROM r0 AS t0 ${kind()} JOIN r1 AS t1 " +
        s"ON t0.k = t1.k WHERE ${value("t0")} ${cmp()} ${q.oneOf(Seq("t1.a", "t1.d", "2", "1.5"))}"),
      (3, () => s"SELECT t0.k AS g, ${q.oneOf(Seq("MIN", "MAX", "COUNT", "SUM"))}(${value("t0")}) AS v, " +
        s"${q.oneOf(Seq("MIN", "MAX", "COUNT", "SUM"))}(t0.b) AS w, COUNT(*) AS n FROM r0 AS t0 GROUP BY t0.k" +
        (if (q.bool(0.3)) s" HAVING COUNT(*) ${cmp()} 1" else "")),
      (2, () => s"SELECT DISTINCT ${value("t0")} AS v FROM r0 AS t0"),
      (2, () => s"SELECT t0.a AS a, t0.b AS b FROM r0 AS t0 ORDER BY t0.a${if (q.bool()) " DESC" else ""}, t0.b " +
        s"LIMIT ${q.oneOf(Seq(1, 2, 5))}"),
      (1, () => s"SELECT t0.a AS a FROM r0 AS t0 WHERE t0.a IN (t0.b, ${q.oneOf(Seq("1", "2147483647", "0"))})"))()
    TargetedCase(env, sql)
  }
}
