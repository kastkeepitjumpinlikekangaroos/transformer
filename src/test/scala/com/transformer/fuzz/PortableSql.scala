package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.plan._

import java.math.{BigDecimal => JBigDecimal}
import scala.collection.mutable

/** The generators' queries and data printed as SQL that the engine,
  * PostgreSQL and DuckDB each read the same way, for the corpus of answers the
  * two references agree on (plans/testing/14, W1, and
  * `slt.AgreedCorpus`, which runs what this prints).
  *
  * One text serves all three: every type a query names is spelled as each of
  * them reads it (`INTEGER`, `BIGINT`, `REAL`, `DOUBLE PRECISION`,
  * `DECIMAL(p,s)`, `TEXT`, `BOOLEAN`, `DATE`, `TIMESTAMP`), and only a table's
  * `BINARY` column and its values are spelled per engine ([[Engine]]). Beyond
  * the spelling, the text says what [[SqlRender]] leaves to a default the
  * three do not share:
  *
  *   - Every operand that is itself an operator is in parentheses, so no
  *     engine's precedence can group it otherwise than the generator built it
  *     (`||` and `IS NULL` bind differently in each).
  *   - A literal says its type: a `BIGINT`, `REAL` or `DOUBLE` one, and a
  *     decimal one as `CAST(<digits> AS DECIMAL(p,s))` of its own digits,
  *     where Postgres reads a number with a point as a numeric, DuckDB as a
  *     `DECIMAL` and the engine as a `DOUBLE` unless it meets a `DECIMAL`
  *     (`double-literal`). A NULL of a type is `CAST(NULL AS <type>)`, of a
  *     `BINARY` the bare `NULL`, since nothing casts to one in the engine.
  *   - Every `ORDER BY` key, in a query and in a window, names where its NULLs
  *     go: the engine puts them first under `ASC`, Postgres and DuckDB last.
  *   - Every `LIKE` names its escape, `ESCAPE '\'`, which is the engine's and
  *     Postgres's default and DuckDB has none of.
  *   - `IF(c, a, b)` is `CASE WHEN c THEN a ELSE b END`, and `COUNT_IF(p)`
  *     `COUNT(CASE WHEN p THEN 1 END)`, which neither reference spells.
  *   - A `/` or `%` divides by `NULLIF(<divisor>, 0)` unless the divisor is a
  *     literal that is not 0, so no query divides by zero: the engine's NULL
  *     there is a decision (`div-zero-null`) that the engine's own scripts
  *     pin, and both references raise an error.
  *
  * A query outside what the three share by their rules throws [[Outside]],
  * naming why; the tool counts each reason. Those are arithmetic on a
  * `FLOAT` (each widens a `REAL` its own way), `%` of a `DOUBLE` (Postgres has
  * none), `ROUND` of a `FLOAT` or `DOUBLE` (Postgres rounds half to even), a
  * `CAST` of a fraction to an integer (`cast-truncates`), the text of a
  * float (each prints one its own way), a `BOOLEAN` cast to a number other
  * than an `INTEGER`, and `MIN` or `MAX` of a `BOOLEAN` or a `BINARY`, which
  * Postgres has not; from `MetaQueryGen`, a window whose answer depends on
  * the order of rows its `ORDER BY` ties, which it writes with no unique key.
  *
  * [[Variant.EngineTyped]] prints a query for Postgres with each `DECIMAL`
  * the engine computes rounded to the scale the engine types it with
  * (`decimal-result-type`), a quotient's and an average's to forty places
  * first, so Postgres computes what the engine's rules say the answer is.
  */
object PortableSql {

  /** Where the three spell a thing differently: a `BINARY` column's type and
    * value. */
  sealed trait Engine
  object Engine {
    case object Transformer extends Engine
    case object Postgres extends Engine
    case object DuckDb extends Engine
  }

  /** A query outside what the three engines share, and why. */
  final class Outside(val reason: String) extends RuntimeException(reason, null, false, false)

  /** How a query is printed: as written, the text all three run, or for
    * Postgres with each `DECIMAL` node in `types` rounded to its scale. A
    * node is keyed by itself: an [[Expr]], a `QueryGen.AggSpec` or a
    * `MetaQueryGen.WinItem`. */
  sealed trait Variant
  object Variant {
    case object Plain extends Variant
    /** For Postgres: each `DECIMAL` node in `types` rounded to its scale,
      * and the engine's own rule written in for each held finding in
      * `held` ([[HeldRules]]). */
    final case class EngineTyped(types: Map[AnyRef, DataType.DecimalType], held: Set[String] = Set.empty) extends Variant
  }

  /** The findings of the first corpus not yet decided, each with how the
    * engine-typed variant writes the engine's rule in Postgres's SQL, so a
    * record the engine answers by that rule can be told from a new finding:
    *
    *   - `concat-null`: `CONCAT` is NULL where an argument is NULL, as `||`
    *     is, where Postgres and DuckDB skip the NULL argument;
    *   - `substring-before-start`: a `SUBSTRING` position before the first
    *     character counts from the first, `SUBSTRING('abc', 0, 2)` is `ab`,
    *     where Postgres and DuckDB count the positions before it, `a`;
    *   - `timestamp-text`: a `TIMESTAMP` cast to text is its ISO instant,
    *     `2024-03-10T07:00:00.000001Z`, where both print
    *     `2024-03-10 07:00:00.000001`. */
  val HeldRules: Seq[String] = Seq("concat-null", "substring-before-start", "timestamp-text")

  /** The held findings whose construct a query uses, so the engine-typed
    * variant writes their rules in. */
  def heldIn(shape: Shape): Set[String] = {
    val found = mutable.Set.empty[String]
    val text = try query(shape) catch { case _: Outside => "" }
    if (text.contains("CONCAT(")) found += "concat-null"
    if (text.contains("SUBSTRING(")) found += "substring-before-start"
    if (timestampText(shape)) found += "timestamp-text"
    found.toSet
  }

  /** A query of either generator: `QueryGen`'s over its one table, or
    * `MetaQueryGen`'s over named relations. */
  sealed trait Shape
  object Shape {
    final case class Single(query: QueryGen.GenQuery) extends Shape
    final case class Multi(query: MetaQueryGen.MetaQuery) extends Shape
  }

  /** A forty-place zero: added to a divisor or an average's argument in the
    * engine-typed variant, it makes Postgres keep forty places of the
    * quotient, past every scale a `DECIMAL(38,s)` has, before the `ROUND`. */
  private val PlacesZero: String = "0." + "0" * 40

  // ---- types and values --------------------------------------------------------

  def typeName(t: DataType, engine: Engine): String = t match {
    case DataType.IntType => "INTEGER"
    case DataType.LongType => "BIGINT"
    case DataType.FloatType => "REAL"
    case DataType.DoubleType => "DOUBLE PRECISION"
    case DataType.StringType => "TEXT"
    case DataType.BooleanType => "BOOLEAN"
    case DataType.DateType => "DATE"
    case DataType.TimestampType => "TIMESTAMP"
    case DataType.DecimalType(p, s) => s"DECIMAL($p,$s)"
    case DataType.BinaryType => engine match {
      case Engine.Transformer => "BINARY"
      case Engine.Postgres => "BYTEA"
      case Engine.DuckDb => "BLOB"
    }
    case other => throw new IllegalArgumentException(s"no portable type for $other")
  }

  /** `CREATE [TEMP] TABLE name (...)`: a Postgres table is temporary, since
    * each Postgres session the tool opens is its own and dies with it. */
  def createTable(name: String, schema: Schema, engine: Engine): String = {
    val temp = if (engine == Engine.Postgres) "TEMP " else ""
    s"CREATE ${temp}TABLE $name (${schema.fields.map(f => s"${f.name} ${typeName(f.dataType, engine)}").mkString(", ")})"
  }

  /** One `INSERT` of every row, or None for no rows. */
  def insert(name: String, schema: Schema, rows: Seq[Array[Any]], engine: Engine): Option[String] =
    if (rows.isEmpty) None
    else Some(s"INSERT INTO $name VALUES " + rows.map { r =>
      schema.fields.indices.map(c => value(r(c), schema.fields(c).dataType, engine)).mkString("(", ", ", ")")
    }.mkString(", "))

  /** A value of a table as the three write it in an `INSERT`: a number as
    * its digits, a NaN, an infinity or -0.0 as text cast to the type, and a
    * byte array as each engine writes one. */
  def value(v: Any, t: DataType, engine: Engine): String =
    if (v == null) "NULL"
    else t match {
      case DataType.IntType | DataType.LongType => v.toString
      case DataType.FloatType => floatValue(v.asInstanceOf[Float].toDouble, java.lang.Float.toString(v.asInstanceOf[Float]), t)
      case DataType.DoubleType => floatValue(v.asInstanceOf[Double], java.lang.Double.toString(v.asInstanceOf[Double]), t)
      case _: DataType.DecimalType => v.asInstanceOf[JBigDecimal].toPlainString
      case DataType.StringType => quote(v.asInstanceOf[String])
      case DataType.BooleanType => if (v.asInstanceOf[Boolean]) "TRUE" else "FALSE"
      case DataType.DateType => s"DATE '$v'"
      case DataType.TimestampType => s"TIMESTAMP '${SqlRender.temporalText(v)}'"
      case DataType.BinaryType =>
        val hex = v.asInstanceOf[Array[Byte]].map(b => f"${b & 0xff}%02x").mkString
        engine match {
          case Engine.Transformer => s"x'$hex'"
          case Engine.Postgres => s"'\\x$hex'"
          case Engine.DuckDb => s"from_hex('$hex')"
        }
      case other => throw new IllegalArgumentException(s"no portable value of $other")
    }

  private def floatValue(d: Double, shortest: String, t: DataType): String =
    if (d.isNaN) s"CAST('NaN' AS ${typeName(t, Engine.Transformer)})"
    else if (d.isInfinite) s"CAST('${if (d > 0) "Infinity" else "-Infinity"}' AS ${typeName(t, Engine.Transformer)})"
    else if (d == 0.0 && 1.0 / d < 0) s"CAST('-0' AS ${typeName(t, Engine.Transformer)})"
    else new JBigDecimal(shortest).toPlainString

  def quote(s: String): String = "'" + s.replace("'", "''") + "'"

  // ---- what a node is ------------------------------------------------------------

  private val Arithmetic: Set[String] = Set("+", "-", "*", "/", "%")

  /** The type a node has, read from its leaves upward as the engine types
    * it, as far as [[Outside]]'s checks need: a generator's `dataType` on an
    * operator is only the type it aimed at. */
  def typeOf(e: Expr): DataType = e match {
    case LitExpr(_, t) => t
    case ColRefExpr(_, _, t) => t
    case CastExpr(_, t) => t
    case DecimalCastExpr(_, t) => t
    case UnaryOpExpr("NOT", _, _) => DataType.BooleanType
    case UnaryOpExpr(_, c, _) => typeOf(c)
    case BinOpExpr(op, l, r, _) if Arithmetic(op) => widen(typeOf(l), typeOf(r))
    case BinOpExpr("||", _, _, _) => DataType.StringType
    case BinOpExpr(_, _, _, _) => DataType.BooleanType
    case FuncExpr(name, args, t) => name match {
      case "ABS" | "ROUND" | "TRUNC" | "NULLIF" => typeOf(args.head)
      case "FLOOR" | "CEIL" => typeOf(args.head) match {
        case DataType.FloatType => DataType.DoubleType
        case other => other
      }
      case "COALESCE" => args.map(typeOf).reduce(widen)
      case "IF" => args.tail.map(typeOf).reduce(widen)
      case "LENGTH" => DataType.IntType
      case _ => t
    }
    case CaseExpr(branches, elseE, _) => (branches.map(_._2) ++ elseE).map(typeOf).reduce(widen)
    case SwitchCaseExpr(_, branches, elseE, _) => (branches.map(_.value) ++ elseE).map(typeOf).reduce(widen)
    case _ => DataType.BooleanType
  }

  private def widen(a: DataType, b: DataType): DataType = (a, b) match {
    case (DataType.NullType, x) => x
    case (x, DataType.NullType) => x
    case (d: DataType.DecimalType, _) => d
    case (_, d: DataType.DecimalType) => d
    case (DataType.FloatType, DataType.FloatType) => DataType.FloatType
    case (DataType.DoubleType | DataType.FloatType, _) | (_, DataType.DoubleType | DataType.FloatType) => DataType.DoubleType
    case (DataType.LongType, _) | (_, DataType.LongType) => DataType.LongType
    case _ => a
  }

  private def isFloating(t: DataType): Boolean = t == DataType.FloatType || t == DataType.DoubleType

  /** An operator, which an operand of another is parenthesized as. */
  private def isOperator(e: Expr): Boolean = e match {
    case _: BinOpExpr | _: UnaryOpExpr | _: IsNullExpr | _: InListExpr | _: LikeExpr | _: BetweenExpr => true
    case LitExpr(n: Number, _) => n.doubleValue < 0 || (n.doubleValue == 0.0 && 1.0 / n.doubleValue < 0)
    case _ => false
  }

  // ---- expressions -----------------------------------------------------------------

  def expr(e: Expr, variant: Variant = Variant.Plain): String = new Printer(variant).expr(e)

  private final class Printer(variant: Variant) {

    private def typed(key: AnyRef): Option[DataType.DecimalType] = variant match {
      case Variant.EngineTyped(types, _) => types.get(key)
      case Variant.Plain => None
    }

    private def holds(id: String): Boolean = variant match {
      case Variant.EngineTyped(_, held) => held(id)
      case Variant.Plain => false
    }

    def expr(e: Expr): String = {
      val sql = raw(e)
      typed(e).fold(sql)(t => s"ROUND($sql, ${t.scale})")
    }

    /** `e` as an operand of an operator. */
    def operand(e: Expr): String =
      if (isOperator(e) && typed(e).isEmpty) s"(${raw(e)})" else expr(e)

    private def raw(e: Expr): String = e match {
      case LitExpr(null, t) => nullLiteral(t)
      case LitExpr(v, t) => literal(v, t)
      case ColRefExpr(_, name, _) => name
      case CastExpr(c, t) =>
        castCheck(c, t)
        if (holds("timestamp-text") && t == DataType.StringType && typeOf(c) == DataType.TimestampType)
          s"(REPLACE(CAST(${expr(c)} AS TEXT), ' ', 'T') || 'Z')"
        else s"CAST(${expr(c)} AS ${typeName(t, Engine.Transformer)})"
      case DecimalCastExpr(c, t) =>
        castCheck(c, t)
        s"CAST(${expr(c)} AS DECIMAL(${t.precision},${t.scale}))"
      case UnaryOpExpr("NOT", c, _) => s"NOT ${operand(c)}"
      case UnaryOpExpr("-", c, _) => s"-${operand(c)}"
      case UnaryOpExpr(op, _, _) => throw new IllegalArgumentException(s"PortableSql: unknown unary operator '$op'")
      case b @ BinOpExpr(op, l, r, _) if Arithmetic(op) => arithmetic(b, op, l, r)
      case BinOpExpr(op, l, r, _) => s"${operand(l)} ${binOp(op)} ${operand(r)}"
      case FuncExpr(name, args, _) => function(name, args)
      case CaseExpr(branches, elseE, _) =>
        val ws = branches.map { case (c, v) => s"WHEN ${expr(c)} THEN ${expr(v)}" }.mkString(" ")
        s"CASE $ws${elseE.map(x => s" ELSE ${expr(x)}").getOrElse("")} END"
      case SwitchCaseExpr(operandE, branches, elseE, _) =>
        val ws = branches.map(b => s"WHEN ${expr(b.when)} THEN ${expr(b.value)}").mkString(" ")
        s"CASE ${expr(operandE)} $ws${elseE.map(x => s" ELSE ${expr(x)}").getOrElse("")} END"
      case IsNullExpr(c, neg) => s"${operand(c)} IS ${if (neg) "NOT " else ""}NULL"
      case InListExpr(c, items, neg) =>
        s"${operand(c)} ${if (neg) "NOT " else ""}IN (${items.map(expr).mkString(", ")})"
      case LikeExpr(c, p, neg, syntax) =>
        val escape = syntax.escape.getOrElse(throw new Outside("a LIKE with no escape character"))
        s"${operand(c)} ${if (neg) "NOT " else ""}${if (syntax.caseInsensitive) "ILIKE" else "LIKE"} ${operand(p)} " +
          s"ESCAPE ${quote(escape.toString)}"
      case BetweenExpr(c, low, high) => s"${operand(c)} BETWEEN ${operand(low)} AND ${operand(high)}"
    }

    private def arithmetic(node: Expr, op: String, l: Expr, r: Expr): String = {
      if (Seq(l, r).exists(x => typeOf(x) == DataType.FloatType))
        throw new Outside("arithmetic on a FLOAT, which each engine widens its own way")
      if (op == "%" && Seq(l, r).exists(x => isFloating(typeOf(x))))
        throw new Outside("% of a DOUBLE, which Postgres has not")
      val right =
        if (op != "/" && op != "%") operand(r)
        else {
          val guarded = r match {
            case LitExpr(n: Number, _) if !isZero(n) => operand(r)
            case LitExpr(d: JBigDecimal, _) if d.signum != 0 => operand(r)
            case _ => s"NULLIF(${expr(r)}, 0)"
          }
          if (op == "/" && typed(node).isDefined) s"($guarded + $PlacesZero)" else guarded
        }
      s"${operand(l)} $op $right"
    }

    private def function(name: String, args: Seq[Expr]): String = name match {
      case "IF" => s"CASE WHEN ${expr(args(0))} THEN ${expr(args(1))} ELSE ${expr(args(2))} END"
      case "CONCAT" if holds("concat-null") => args.map(operand).mkString("(", " || ", ")")
      case "SUBSTRING" if holds("substring-before-start") =>
        val start = s"CASE WHEN ${operand(args(1))} < 1 THEN 1 ELSE ${expr(args(1))} END"
        s"SUBSTRING(${(Seq(expr(args.head), start) ++ args.drop(2).map(expr)).mkString(", ")})"
      case "ROUND" | "TRUNC" if isFloating(typeOf(args.head)) =>
        throw new Outside(s"$name of a FLOAT or DOUBLE, which Postgres rounds half to even")
      case "ROUND" | "TRUNC" | "SUBSTRING" if args.drop(1).exists(a => typeOf(a) == DataType.LongType) =>
        throw new Outside(s"a BIGINT $name position, length or scale, which Postgres takes as an INTEGER only")
      case _ => s"$name(${args.map(expr).mkString(", ")})"
    }

    /** A `QueryGen.AggSpec` as the three spell it. */
    def aggregate(a: QueryGen.AggSpec): String = {
      def distinct(d: Boolean): String = if (d) "DISTINCT " else ""
      val sql = a match {
        case QueryGen.CountStar => "COUNT(*)"
        case QueryGen.Count(arg, d) => s"COUNT(${distinct(d)}${expr(arg)})"
        case QueryGen.Sum(arg, d) => s"SUM(${distinct(d)}${expr(arg)})"
        case QueryGen.Avg(arg, d) =>
          if (typed(a).isDefined) s"AVG(${distinct(d)}${operand(arg)} + $PlacesZero)"
          else s"AVG(${distinct(d)}${expr(arg)})"
        case QueryGen.Min(arg) => minMax("MIN", arg)
        case QueryGen.Max(arg) => minMax("MAX", arg)
        case QueryGen.CountIf(arg) => s"COUNT(CASE WHEN ${expr(arg)} THEN 1 END)"
        case QueryGen.Stat(fn, arg) => s"$fn(${expr(arg)})"
        case QueryGen.Bivar(fn, x, y) => s"$fn(${expr(x)}, ${expr(y)})"
      }
      typed(a).fold(sql)(t => s"ROUND($sql, ${t.scale})")
    }

    private def minMax(fn: String, arg: Expr): String = {
      minMaxCheck(typeOf(arg))
      s"$fn(${expr(arg)})"
    }

    /** A window item of `MetaQueryGen`, whose text it writes itself: its
      * `ORDER BY` keys given their NULL placement, and in the engine-typed
      * variant a `SUM` or `AVG` of a `DECIMAL` rounded to its scale. */
    def window(w: MetaQueryGen.WinItem): String = {
      if (w.tieSensitive) throw new Outside("a window whose answer depends on the order of rows its ORDER BY ties")
      if (w.sql.startsWith("MIN(") || w.sql.startsWith("MAX(")) minMaxCheck(w.dataType)
      val placed = placeNulls(w.sql)
      typed(w) match {
        case None => placed
        case Some(t) =>
          val averaged = WindowAvg.findFirstMatchIn(placed) match {
            case Some(m) => s"AVG((${m.group(1)}) + $PlacesZero) OVER ${m.group(2)}"
            case None => placed
          }
          s"ROUND($averaged, ${t.scale})"
      }
    }
  }

  private val WindowAvg = """^AVG\((.+?)\) OVER (\(.*\))$""".r

  /** Each `<key> ASC` or `<key> DESC` of a window's text that names no NULL
    * placement given the engine's: first under `ASC`, last under `DESC`. */
  def placeNulls(windowSql: String): String =
    """([\w.]+) (ASC|DESC)\b(?! NULLS)""".r.replaceAllIn(windowSql, m =>
      s"${m.group(1)} ${m.group(2)} NULLS ${if (m.group(2) == "ASC") "FIRST" else "LAST"}")

  private def minMaxCheck(t: DataType): Unit =
    if (t == DataType.BooleanType || t == DataType.BinaryType)
      throw new Outside("MIN or MAX of a BOOLEAN or BINARY, which Postgres has not")

  private def castCheck(c: Expr, target: DataType): Unit = {
    val source = typeOf(c)
    val integerTarget = target == DataType.IntType || target == DataType.LongType
    if (integerTarget && (isFloating(source) || source.isInstanceOf[DataType.DecimalType]))
      throw new Outside("a CAST of a fraction to an integer (cast-truncates)")
    if (target == DataType.StringType && isFloating(source))
      throw new Outside("the text of a FLOAT or DOUBLE, which each engine prints its own way")
    if (source == DataType.BooleanType && DataType.isNumeric(target) && target != DataType.IntType)
      throw new Outside("a BOOLEAN cast to a number other than an INTEGER, which Postgres has not")
  }

  private def isZero(n: Number): Boolean = n match {
    case d: JBigDecimal => d.signum == 0
    case other => other.doubleValue == 0.0
  }

  private def binOp(op: String): String = op match {
    case "==" => "="
    case "!=" => "<>"
    case other => other
  }

  /** A NULL of its type: Postgres types a bare one it meets nothing beside
    * as text (`COALESCE(NULL)`), which no numeric branch then meets. A
    * `BINARY`'s is bare, since nothing casts to one in the engine. */
  private def nullLiteral(t: DataType): String = t match {
    case DataType.NullType | DataType.BinaryType => "NULL"
    case other => s"CAST(NULL AS ${typeName(other, Engine.Transformer)})"
  }

  private def literal(v: Any, t: DataType): String = t match {
    case DataType.IntType => v.toString
    case DataType.LongType => s"CAST($v AS BIGINT)"
    case DataType.FloatType | DataType.DoubleType =>
      val d = v.asInstanceOf[Number].doubleValue
      if (d.isNaN || d.isInfinite || (d == 0.0 && 1.0 / d < 0)) floatValue(d, d.toString, t)
      else s"CAST(${new JBigDecimal(java.lang.Double.toString(d)).toPlainString} AS ${typeName(t, Engine.Transformer)})"
    case DataType.BooleanType => if (v.asInstanceOf[Boolean]) "TRUE" else "FALSE"
    case DataType.StringType => quote(v.toString)
    case DataType.DateType => s"DATE '$v'"
    case DataType.TimestampType => s"TIMESTAMP '${SqlRender.temporalText(v)}'"
    case _: DataType.DecimalType =>
      val d = v.asInstanceOf[JBigDecimal]
      val scale = math.max(0, d.scale)
      s"CAST(${d.toPlainString} AS DECIMAL(${math.max(math.max(d.precision, scale), 1)},$scale))"
    case other => throw new IllegalArgumentException(s"PortableSql: no literal of $other")
  }

  private def orderKey(name: String, asc: Boolean, nullsFirst: Boolean): String =
    s"$name ${if (asc) "ASC" else "DESC"} NULLS ${if (nullsFirst) "FIRST" else "LAST"}"

  // ---- queries ---------------------------------------------------------------------

  /** The query's text, as written (`Plain`) or in the engine-typed variant. */
  def query(shape: Shape, variant: Variant = Variant.Plain): String = shape match {
    case Shape.Single(q) => single(q, new Printer(variant))
    case Shape.Multi(q) =>
      val p = new Printer(variant)
      val ctes = if (q.ctes.isEmpty) "" else "WITH " + q.ctes.map(c => s"${c.name} AS (${mainSelect(c.body, p)})").mkString(", ") + " "
      ctes + mainSelect(q, p)
  }

  /** Whether the query orders its result totally, so its rows compare in the
    * order it returns them: every output column is an `ORDER BY` key, or a
    * grouped query orders by every group key. */
  def ordersTotally(shape: Shape): Boolean = shape match {
    case Shape.Single(q: QueryGen.ProjectQuery) => projectTotal(q)
    case Shape.Single(q: QueryGen.AggregateQuery) => aggregateTotal(q)
    case Shape.Multi(q) => q.totalOrderKeys.isDefined && (q.limit.isDefined || q.offset.isDefined)
  }

  private def projectTotal(q: QueryGen.ProjectQuery): Boolean =
    q.orderBy.nonEmpty && q.projection.forall(_._1.isInstanceOf[ColRefExpr]) &&
      q.projection.map(_._2).toSet == q.orderBy.map(_.col.name).toSet

  private def aggregateTotal(q: QueryGen.AggregateQuery): Boolean =
    q.groupKeys.nonEmpty && q.orderBy.map(_.col.index).toSet == q.groupKeys.map(_.index).toSet

  private def single(q: QueryGen.GenQuery, p: Printer): String = {
    val view = QueryGen.ViewName
    def orderBy(keys: Vector[QueryGen.OrderKey]): String =
      if (keys.isEmpty) "" else " ORDER BY " + keys.map(k => orderKey(k.col.name, k.asc, k.placesNullsFirst)).mkString(", ")
    def limitOffset(total: Boolean, limit: Option[Long], offset: Option[Long]): String =
      if (!total) "" else limit.map(n => s" LIMIT $n").getOrElse("") + offset.map(m => s" OFFSET $m").getOrElse("")
    q match {
      case pq: QueryGen.ProjectQuery =>
        val sel = pq.projection.map { case (e, name) =>
          val r = p.expr(e)
          if (r == name) name else s"$r AS $name"
        }.mkString(", ")
        val where = pq.where.map(w => s" WHERE ${p.expr(w)}").getOrElse("")
        s"SELECT ${if (pq.distinct) "DISTINCT " else ""}$sel FROM $view$where${orderBy(pq.orderBy)}" +
          limitOffset(projectTotal(pq), pq.limit, pq.offset)
      case aq: QueryGen.AggregateQuery =>
        val sel = (aq.groupKeys.map(_.name) ++ aq.aggregates.map { case (a, n) => s"${p.aggregate(a)} AS $n" }).mkString(", ")
        val where = aq.where.map(w => s" WHERE ${p.expr(w)}").getOrElse("")
        val group =
          if (aq.groupKeys.isEmpty) ""
          else if (aq.groupByOrdinals) s" GROUP BY ${aq.groupKeys.indices.map(_ + 1).mkString(", ")}"
          else s" GROUP BY ${aq.groupKeys.map(_.name).mkString(", ")}"
        val having = aq.having.map(h => s" HAVING ${h.render}").getOrElse("")
        s"SELECT $sel FROM $view$where$group$having${orderBy(aq.orderBy)}" +
          limitOffset(aggregateTotal(aq), aq.limit, aq.offset)
    }
  }

  private def mainSelect(q: MetaQueryGen.MetaQuery, p: Printer): String = {
    val core = q.core match {
      case MetaQueryGen.ProjectCore(items, distinct, windows) =>
        val sel = items.map { case (e, n) => s"${p.expr(e)} AS $n" } ++ windows.map(w => s"${p.window(w)} AS ${w.name}")
        (if (distinct) "DISTINCT " else "") + sel.mkString(", ")
      case MetaQueryGen.AggCore(keys, aggs, _) =>
        (keys.map { case (e, n) => s"${p.expr(e)} AS $n" } ++ aggs.map { case (a, n) => s"${p.aggregate(a)} AS $n" }).mkString(", ")
    }
    val from = (q.from.root.render +: q.from.joins.map { j =>
      s"${joinKeyword(j.kind)} JOIN ${j.leaf.render} ON ${p.expr(j.on)}"
    }).mkString(" ")
    val where = q.where.map(w => s" WHERE ${p.expr(w)}").getOrElse("")
    val (group, having) = q.core match {
      case MetaQueryGen.AggCore(keys, _, h) =>
        (if (keys.isEmpty) "" else " GROUP BY " + keys.map { case (e, _) => p.expr(e) }.mkString(", "),
          h.map(x => s" HAVING ${p.expr(x)}").getOrElse(""))
      case _ => ("", "")
    }
    val topN = q.totalOrderKeys match {
      case Some(keys) if q.limit.isDefined || q.offset.isDefined =>
        val first = q.nullsFirst.getOrElse(true)
        s" ORDER BY ${keys.map(k => orderKey(k, asc = true, first)).mkString(", ")}" +
          q.limit.map(n => s" LIMIT $n").getOrElse("") + q.offset.map(m => s" OFFSET $m").getOrElse("")
      case _ => ""
    }
    val arm = s"SELECT $core FROM $from$where$group$having$topN"
    q.setOp match {
      case None => arm
      case Some((rhs, all)) => s"$arm UNION ${if (all) "ALL " else ""}${mainSelect(rhs, p)}"
    }
  }

  private def joinKeyword(k: JoinKind): String = k match {
    case JoinKind.Inner => "INNER"
    case JoinKind.Left => "LEFT"
    case JoinKind.Right => "RIGHT"
    case JoinKind.Full => "FULL"
  }

  // ---- what the engine-typed variant needs ---------------------------------------

  /** Each node of the query that may compute a `DECIMAL`, with a query that
    * binds it alone over the tables it reads (`SELECT <node> AS v FROM ...`),
    * from whose one column the tool reads the type the engine gives it. A
    * node the engine does not type as a `DECIMAL` is left as it is. Bare
    * columns, literals and casts compute nothing, and `ROUND` and `TRUNC`
    * name their own scale. */
  def typingProbes(shape: Shape): Vector[(AnyRef, String)] = {
    val out = Vector.newBuilder[(AnyRef, String)]
    val plain = new Printer(Variant.Plain)
    // `probe` makes the query that binds a node alone over the tables the
    // node's arm reads, with the WITH clause when the arm may read a CTE.
    def exprNodes(e: Expr, probe: String => String): Unit = {
      val computes = e match {
        case BinOpExpr(op, _, _, _) => Arithmetic(op)
        case FuncExpr(name, _, _) => Set("ABS", "COALESCE", "IF", "NULLIF")(name)
        case _: CaseExpr | _: SwitchCaseExpr => true
        case UnaryOpExpr("-", _, _) => true
        case _ => false
      }
      if (computes && touchesDecimal(e)) out += (e: AnyRef) -> probe(expr(e))
      children(e).foreach(exprNodes(_, probe))
    }
    def aggNodes(a: QueryGen.AggSpec, probe: String => String): Unit = a match {
      case QueryGen.Sum(arg, _) =>
        if (touchesDecimal(arg)) out += (a: AnyRef) -> probe(plain.aggregate(a))
        exprNodes(arg, probe)
      case QueryGen.Avg(arg, _) =>
        if (touchesDecimal(arg)) out += (a: AnyRef) -> probe(plain.aggregate(a))
        exprNodes(arg, probe)
      case QueryGen.Count(arg, _) => exprNodes(arg, probe)
      case QueryGen.Min(arg) => exprNodes(arg, probe)
      case QueryGen.Max(arg) => exprNodes(arg, probe)
      case QueryGen.CountIf(arg) => exprNodes(arg, probe)
      case QueryGen.Stat(_, arg) => exprNodes(arg, probe)
      case QueryGen.Bivar(_, x, y) => exprNodes(x, probe); exprNodes(y, probe)
      case QueryGen.CountStar => ()
    }
    shape match {
      case Shape.Single(q) =>
        val probe = (node: String) => s"SELECT $node AS v FROM ${QueryGen.ViewName}"
        q match {
          case pq: QueryGen.ProjectQuery =>
            pq.projection.foreach { case (e, _) => exprNodes(e, probe) }
            pq.where.foreach(exprNodes(_, probe))
          case aq: QueryGen.AggregateQuery =>
            aq.aggregates.foreach { case (a, _) => aggNodes(a, probe) }
            aq.where.foreach(exprNodes(_, probe))
        }
      case Shape.Multi(q) =>
        val withClause =
          if (q.ctes.isEmpty) "" else "WITH " + q.ctes.map(c => s"${c.name} AS (${mainSelect(c.body, plain)})").mkString(", ") + " "
        def arm(a: MetaQueryGen.MetaQuery, prefix: String): Unit = {
          val from = (a.from.root.render +: a.from.joins.map { j =>
            s"${joinKeyword(j.kind)} JOIN ${j.leaf.render} ON ${plain.expr(j.on)}"
          }).mkString(" ")
          val probe = (node: String) => s"${prefix}SELECT $node AS v FROM $from"
          a.from.joins.foreach(j => exprNodes(j.on, probe))
          a.where.foreach(exprNodes(_, probe))
          a.core match {
            case MetaQueryGen.ProjectCore(items, _, windows) =>
              items.foreach { case (e, _) => exprNodes(e, probe) }
              windows.foreach { w =>
                if ((w.sql.startsWith("SUM(") || w.sql.startsWith("AVG(")) && w.dataType.isInstanceOf[DataType.DecimalType])
                  out += (w: AnyRef) -> probe(plain.window(w))
              }
            case MetaQueryGen.AggCore(keys, aggs, having) =>
              keys.foreach { case (e, _) => exprNodes(e, probe) }
              aggs.foreach { case (ag, _) => aggNodes(ag, probe) }
              having.foreach(exprNodes(_, probe))
          }
          a.setOp.foreach { case (rhs, _) => arm(rhs, prefix) }
        }
        // A CTE body reads base relations alone.
        q.ctes.foreach(c => arm(c.body, ""))
        arm(q, withClause)
    }
    out.result().distinctBy(_._1)
  }


  /** Whether `e` reads a `DECIMAL`: a column, a literal or a CAST of one. */
  private def touchesDecimal(e: Expr): Boolean =
    typeOf(e).isInstanceOf[DataType.DecimalType] || children(e).exists(touchesDecimal)

  /** Whether a query computes a statistic (`STDDEV`, `VARIANCE`, `COVAR`,
    * `CORR`), which the engine computes in DOUBLE (`double-precision`). */
  def usesStatistic(shape: Shape): Boolean = {
    def stat(a: QueryGen.AggSpec): Boolean = a.isInstanceOf[QueryGen.Stat] || a.isInstanceOf[QueryGen.Bivar]
    def arm(q: MetaQueryGen.MetaQuery): Boolean = (q.core match {
      case MetaQueryGen.AggCore(_, aggs, _) => aggs.exists(a => stat(a._1))
      case _ => false
    }) || q.ctes.exists(c => arm(c.body)) || q.setOp.exists(x => arm(x._1))
    shape match {
      case Shape.Single(aq: QueryGen.AggregateQuery) => aq.aggregates.exists(a => stat(a._1))
      case Shape.Single(_) => false
      case Shape.Multi(q) => arm(q)
    }
  }

  /** Whether a query casts a `TIMESTAMP` to text anywhere. */
  private def timestampText(shape: Shape): Boolean = {
    def in(e: Expr): Boolean = (e match {
      case CastExpr(c, DataType.StringType) => typeOf(c) == DataType.TimestampType
      case _ => false
    }) || children(e).exists(in)
    def aggIn(a: QueryGen.AggSpec): Boolean = a match {
      case QueryGen.CountStar => false
      case QueryGen.Count(x, _) => in(x)
      case QueryGen.Sum(x, _) => in(x)
      case QueryGen.Avg(x, _) => in(x)
      case QueryGen.Min(x) => in(x)
      case QueryGen.Max(x) => in(x)
      case QueryGen.CountIf(x) => in(x)
      case QueryGen.Stat(_, x) => in(x)
      case QueryGen.Bivar(_, x, y) => in(x) || in(y)
    }
    def arm(q: MetaQueryGen.MetaQuery): Boolean =
      q.where.exists(in) || q.from.joins.exists(j => in(j.on)) || (q.core match {
        case MetaQueryGen.ProjectCore(items, _, _) => items.exists(i => in(i._1))
        case MetaQueryGen.AggCore(keys, aggs, having) => keys.exists(k => in(k._1)) || aggs.exists(a => aggIn(a._1)) || having.exists(in)
      }) || q.ctes.exists(c => arm(c.body)) || q.setOp.exists(x => arm(x._1))
    shape match {
      case Shape.Single(pq: QueryGen.ProjectQuery) => pq.projection.exists(i => in(i._1)) || pq.where.exists(in)
      case Shape.Single(aq: QueryGen.AggregateQuery) => aq.aggregates.exists(a => aggIn(a._1)) || aq.where.exists(in)
      case Shape.Multi(q) => arm(q)
    }
  }

  /** The operands of a node. */
  private[fuzz] def children(e: Expr): Seq[Expr] = e match {
    case CastExpr(c, _) => Seq(c)
    case DecimalCastExpr(c, _) => Seq(c)
    case UnaryOpExpr(_, c, _) => Seq(c)
    case BinOpExpr(_, l, r, _) => Seq(l, r)
    case FuncExpr(_, args, _) => args
    case CaseExpr(branches, elseE, _) => branches.flatMap { case (c, v) => Seq(c, v) } ++ elseE
    case SwitchCaseExpr(op, branches, elseE, _) => op +: (branches.flatMap(b => Seq(b.when, b.value)) ++ elseE)
    case IsNullExpr(c, _) => Seq(c)
    case InListExpr(c, items, _) => c +: items
    case LikeExpr(c, p, _, _) => Seq(c, p)
    case BetweenExpr(c, lo, hi) => Seq(c, lo, hi)
    case _ => Nil
  }

  /** Every output column that is a quotient of `DECIMAL`s, a `/` or an
    * `AVG`, at the top of its item: such a column's answer is the exact
    * quotient rounded half away from zero to its scale, which is the
    * engine's rule rather than a difference from the references. */
  def quotientColumns(shape: Shape): Set[Int] = {
    def quotient(e: Expr): Boolean = e match {
      case BinOpExpr("/", _, _, _) => touchesDecimal(e)
      case _ => false
    }
    def aggQuotient(a: QueryGen.AggSpec): Boolean = a match {
      case QueryGen.Avg(arg, _) => touchesDecimal(arg)
      case _ => false
    }
    shape match {
      case Shape.Single(pq: QueryGen.ProjectQuery) => pq.projection.indices.filter(i => quotient(pq.projection(i)._1)).toSet
      case Shape.Single(aq: QueryGen.AggregateQuery) =>
        aq.aggregates.indices.filter(i => aggQuotient(aq.aggregates(i)._1)).map(_ + aq.groupKeys.length).toSet
      case Shape.Multi(q) => q.core match {
        case MetaQueryGen.ProjectCore(items, _, windows) =>
          items.indices.filter(i => quotient(items(i)._1)).toSet ++
            windows.indices.filter(i => windows(i).sql.startsWith("AVG(") && windows(i).dataType.isInstanceOf[DataType.DecimalType])
              .map(_ + items.length)
        case MetaQueryGen.AggCore(keys, aggs, _) =>
          aggs.indices.filter(i => aggQuotient(aggs(i)._1)).map(_ + keys.length).toSet
      }
    }
  }
}
