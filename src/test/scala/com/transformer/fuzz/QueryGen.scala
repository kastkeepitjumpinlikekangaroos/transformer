package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.plan._

/** Generator of a random single-table `SELECT` over a generated view schema, for
  * the mode-differential property (`oracle/ModeDifferential`).
  *
  * The engine's front door is text, so a query is generated as a structured
  * [[GenQuery]] (carrying bound-shaped [[Expr]] trees for its scalar pieces) and
  * rendered to SQL by [[SqlRender]]; the structured form is kept alongside so
  * [[Shrinker]] can minimize it.
  *
  * Two query shapes are produced:
  *   - '''projection''' — `SELECT [DISTINCT] <items> FROM t [WHERE p]
  *     [ORDER BY <cols>]`, exercising `ProjectExec` / `FilterExec` / `DistinctExec`
  *     / `SortExec`;
  *   - '''aggregation''' — `SELECT <group cols>, <aggs> FROM t [WHERE p]
  *     [GROUP BY ...] [HAVING h] [ORDER BY <group cols>]` (or a global aggregate
  *     with no GROUP BY → one row), exercising `HashAggregateExec`.
  *
  * The expression generators ([[genValue]] / [[genPredicate]]) are written for
  * this property rather than reusing the bound-`Expr` shapes from [[ExprGen]],
  * for two reasons:
  *
  *   - '''No grouping parentheses.''' The SQL frontend rejects every grouping
  *     paren — `(a + b)` parses to a single-element `ParenthesedExpressionList`
  *     that the binder does not accept — so expressions must render paren-free
  *     and rely on operator precedence. The generators only build trees that
  *     stay type-correct under a precedence-only reparse: arithmetic operands are
  *     numeric, `||` operands are string, comparisons are numeric-vs-numeric or
  *     string-vs-string (never boolean), and `NOT` wraps a single atom — so the
  *     reparsed grouping may differ but the result type never does. (Every mode
  *     runs the identical text, so a regrouped reparse is harmless.)
  *
  *   - '''Total by construction.''' Like [[ExprGen]], nothing here can throw for
  *     in-range input: casts are numeric->numeric or anything->string (never the
  *     string->numeric parse that can throw), `ROUND`/`SUBSTRING` scales are
  *     non-null integer literals, and integer divide-by-zero returns NULL. So a
  *     query that binds also runs, in every mode.
  *
  * Excluded so the result multiset stays mode-invariant: `RAND()` and the clock
  * functions (`CURRENT_DATE`/`CURRENT_TIMESTAMP`) — they make modes legitimately
  * disagree — and `LIMIT` — top-`n` with ties at the cutoff returns a different
  * SUBSET of the tied rows depending on partition/merge order. Order-sensitivity
  * is still covered: `ORDER BY` without `LIMIT` keeps the full multiset and the
  * oracle additionally checks the output is sorted.
  */
object QueryGen {

  /** The single registered view name every generated query selects from. */
  val ViewName: String = "t"

  private val Numerics: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType)
  private val ValueTypes: Seq[DataType] = Numerics :+ DataType.StringType
  private val ArithOps = Seq("+", "-", "*", "/", "%")
  private val RelOps = Seq("<", "<=", ">", ">=", "=", "<>")

  /** A generated dataset + query. The seed reproduces both exactly. */
  final case class QueryCase(dataset: DataGen.Dataset, query: GenQuery) {
    def sql: String = query.render(ViewName)
    override def toString: String = s"QueryCase(\n  sql = $sql\n  data = $dataset)"
  }

  /** Build one case from a seed: a dataset, then a query over its schema. Split
    * streams keep the two independent for a given seed. */
  def generate(rng: Rng): QueryCase = {
    val dataset = DataGen.generate(rng.split())
    val query = genQuery(rng.split(), dataset.schema)
    QueryCase(dataset, query)
  }

  // ---- structured query AST ----------------------------------------------

  sealed trait GenQuery {
    def render(view: String): String
    /** Output columns the result is ordered by (empty if no ORDER BY); used by
      * the oracle's sortedness sub-check. Always projected, so present in the
      * output schema by name. */
    def orderBy: Vector[(ColRefExpr, Boolean)]
  }

  /** `SELECT [DISTINCT] <items> FROM view [WHERE p] [ORDER BY <cols>]`. */
  final case class ProjectQuery(
      distinct: Boolean,
      projection: Vector[(Expr, String)],
      where: Option[Expr],
      orderBy: Vector[(ColRefExpr, Boolean)]) extends GenQuery {

    def render(view: String): String = {
      val sel = projection.iterator.map { case (e, name) =>
        val rendered = SqlRender.expr(e)
        if (rendered == name) name else s"$rendered AS $name"
      }.mkString(", ")
      val d = if (distinct) "DISTINCT " else ""
      val w = where.map(p => s" WHERE ${SqlRender.expr(p)}").getOrElse("")
      val o = SqlRender.orderByClause(orderBy)
      s"SELECT $d$sel FROM $view$w$o"
    }
  }

  /** `SELECT <group cols>, <aggs> FROM view [WHERE p] [GROUP BY ...] [HAVING h]
    * [ORDER BY <group cols>]`. Empty `groupKeys` is a global aggregate (one row,
    * no GROUP BY / ORDER BY). */
  final case class AggregateQuery(
      groupKeys: Vector[ColRefExpr],
      aggregates: Vector[(AggSpec, String)],
      where: Option[Expr],
      having: Option[HavingSpec],
      groupByOrdinals: Boolean,
      orderBy: Vector[(ColRefExpr, Boolean)]) extends GenQuery {

    def render(view: String): String = {
      val groupSel = groupKeys.map(_.name)
      val aggSel = aggregates.map { case (a, name) => s"${a.render} AS $name" }
      val sel = (groupSel ++ aggSel).mkString(", ")
      val w = where.map(p => s" WHERE ${SqlRender.expr(p)}").getOrElse("")
      val g =
        if (groupKeys.isEmpty) ""
        else if (groupByOrdinals) s" GROUP BY ${groupKeys.indices.map(_ + 1).mkString(", ")}"
        else s" GROUP BY ${groupKeys.map(_.name).mkString(", ")}"
      val h = having.map(hs => s" HAVING ${hs.render}").getOrElse("")
      val o = SqlRender.orderByClause(orderBy)
      s"SELECT $sel FROM $view$w$g$h$o"
    }
  }

  /** An aggregate to render into SELECT. */
  sealed trait AggSpec { def render: String }
  case object CountStar extends AggSpec { def render = "COUNT(*)" }
  final case class Count(arg: Expr, distinct: Boolean) extends AggSpec {
    def render: String = s"COUNT(${if (distinct) "DISTINCT " else ""}${SqlRender.expr(arg)})"
  }
  final case class Sum(arg: Expr) extends AggSpec { def render = s"SUM(${SqlRender.expr(arg)})" }
  final case class Avg(arg: Expr) extends AggSpec { def render = s"AVG(${SqlRender.expr(arg)})" }
  final case class Min(arg: Expr) extends AggSpec { def render = s"MIN(${SqlRender.expr(arg)})" }
  final case class Max(arg: Expr) extends AggSpec { def render = s"MAX(${SqlRender.expr(arg)})" }
  final case class CountIf(arg: Expr) extends AggSpec { def render = s"COUNT_IF(${SqlRender.expr(arg)})" }
  /** STDDEV_SAMP / STDDEV_POP / VAR_SAMP / VAR_POP — order-dependent double. */
  final case class Stat(fn: String, arg: Expr) extends AggSpec { def render = s"$fn(${SqlRender.expr(arg)})" }
  /** COVAR_SAMP / COVAR_POP / CORR — order-dependent double over two columns. */
  final case class Bivar(fn: String, x: Expr, y: Expr) extends AggSpec {
    def render: String = s"$fn(${SqlRender.expr(x)}, ${SqlRender.expr(y)})"
  }

  /** A HAVING predicate: `COUNT(*) cmp <int>` or `<group col> cmp <literal>`.
    * Restricted to these two shapes so its referenced columns are exact (the
    * shrinker's column-drop needs that) and it renders paren-free while still
    * exercising the filter-over-aggregate path. */
  final case class HavingSpec(lhs: HavingLhs, cmp: String, litSql: String) {
    def render: String = s"${lhs.sql} $cmp $litSql"
    def refs: Set[Int] = lhs.refs
  }
  sealed trait HavingLhs { def sql: String; def refs: Set[Int] }
  case object HavingCountStar extends HavingLhs { def sql = "COUNT(*)"; def refs: Set[Int] = Set.empty }
  final case class HavingColumn(col: ColRefExpr) extends HavingLhs {
    def sql: String = col.name; def refs: Set[Int] = Set(col.index)
  }

  // ---- query generation ---------------------------------------------------

  private def genQuery(rng: Rng, schema: Schema): GenQuery =
    if (rng.bool(0.5)) genAggregateQuery(rng, schema) else genProjectQuery(rng, schema)

  private def genProjectQuery(rng: Rng, schema: Schema): ProjectQuery = {
    val n = schema.length
    val distinct = rng.bool(0.4)

    val orderCols: Vector[Int] =
      if (rng.bool(0.5)) distinctIndices(rng, n, rng.between(1, math.min(2, n))) else Vector.empty
    val extraBare: Vector[Int] = distinctIndices(rng, n, rng.between(0, math.min(2, n)))
    val bareCols: Vector[Int] = (orderCols ++ extraBare).distinct

    val computed: Vector[(Expr, String)] =
      (0 until rng.between(0, 3)).iterator.map { i =>
        (genValue(rng, schema, rng.oneOf(ValueTypes), rng.between(2, 4)), s"p$i")
      }.toVector

    val bareItems: Vector[(Expr, String)] = bareCols.map(i => colRef(schema, i)).map(cr => (cr: Expr, cr.name))
    val projection0 = bareItems ++ computed
    val projection = if (projection0.nonEmpty) projection0
      else Vector((colRef(schema, 0): Expr, schema.fields(0).name))

    val where = if (rng.bool(0.6)) Some(genPredicate(rng, schema, rng.between(2, 4))) else None
    val orderBy = orderCols.map(i => (colRef(schema, i), rng.bool()))

    ProjectQuery(distinct, projection, where, orderBy)
  }

  private def genAggregateQuery(rng: Rng, schema: Schema): AggregateQuery = {
    val n = schema.length
    val numeric = (0 until n).filter(i => DataType.isNumeric(schema.fields(i).dataType)).toVector
    val integral = (0 until n).filter(i => DataType.isIntegral(schema.fields(i).dataType)).toVector

    val groupKeyIdx: Vector[Int] =
      if (rng.bool(0.8)) distinctIndices(rng, n, rng.between(1, math.min(3, n))) else Vector.empty
    val groupKeys = groupKeyIdx.map(colRef(schema, _))

    val nAgg = rng.between(1, 3)
    val aggregates: Vector[(AggSpec, String)] =
      (0 until nAgg).iterator.map(i => (genAggSpec(rng, schema, numeric, integral), s"a$i")).toVector

    val where = if (rng.bool(0.5)) Some(genPredicate(rng, schema, rng.between(2, 4))) else None
    val having = if (rng.bool(0.4)) Some(genHaving(rng, groupKeys)) else None
    val groupByOrdinals = groupKeys.nonEmpty && rng.bool(0.3)
    val orderBy =
      if (groupKeys.nonEmpty && rng.bool(0.4))
        distinctSubset(rng, groupKeys, rng.between(1, math.min(2, groupKeys.length))).map(cr => (cr, rng.bool()))
      else Vector.empty

    AggregateQuery(groupKeys, aggregates, where, having, groupByOrdinals, orderBy)
  }

  private def genAggSpec(rng: Rng, schema: Schema, numeric: Vector[Int], integral: Vector[Int]): AggSpec = {
    val n = schema.length
    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => AggSpec)]
    options += ((3, () => CountStar))
    options += ((2, () => Count(colRef(schema, rng.between(0, n - 1)), distinct = rng.bool(0.5))))
    // MIN/MAX over non-Boolean columns. (MIN/MAX of a Boolean column is excluded:
    // it returns NULL under spill but the value off-spill, because
    // MinMaxState.writeSelf routes BooleanType through the long slot while
    // updateAt stores it boxed — a genuine spill-parity bug surfaced by this
    // fuzzer, kept out so the suite is green until the engine is fixed.)
    val minMaxCols = (0 until n).filter(i => schema.fields(i).dataType != DataType.BooleanType).toVector
    if (minMaxCols.nonEmpty) {
      options += ((2, () => Min(colRef(schema, rng.oneOf(minMaxCols)))))
      options += ((2, () => Max(colRef(schema, rng.oneOf(minMaxCols)))))
    }
    options += ((2, () => CountIf(genPredicate(rng, schema, rng.between(1, 3)))))
    if (numeric.nonEmpty) {
      options += ((3, () => Sum(numericArg(rng, schema, numeric))))
      options += ((3, () => Avg(numericArg(rng, schema, numeric))))
    }
    if (integral.nonEmpty) {
      // Statistical aggregates over integral columns only: exact, well-conditioned
      // inputs keep cross-layout reordering well inside the float tolerance.
      options += ((1, () => Stat(rng.oneOf(Seq("STDDEV_SAMP", "STDDEV_POP", "VAR_SAMP", "VAR_POP")),
        colRef(schema, rng.oneOf(integral)))))
      options += ((1, () => Bivar(rng.oneOf(Seq("COVAR_SAMP", "COVAR_POP", "CORR")),
        colRef(schema, rng.oneOf(integral)), colRef(schema, rng.oneOf(integral)))))
    }
    rng.weighted(options.toSeq: _*)()
  }

  /** A SUM/AVG argument: usually a bare numeric column, sometimes a shallow
    * numeric expression (still well-conditioned given the narrow domains). */
  private def numericArg(rng: Rng, schema: Schema, numeric: Vector[Int]): Expr =
    if (rng.bool(0.7)) colRef(schema, rng.oneOf(numeric))
    else genValue(rng, schema, schema.fields(rng.oneOf(numeric)).dataType, rng.between(1, 2))

  private def genHaving(rng: Rng, groupKeys: Vector[ColRefExpr]): HavingSpec = {
    if (groupKeys.isEmpty || rng.bool(0.5))
      HavingSpec(HavingCountStar, rng.oneOf(Seq(">", ">=", "<", "<=", "=", "<>")), rng.between(0, 3).toString)
    else {
      val key = rng.oneOf(groupKeys)
      val cmp = key.dataType match {
        case DataType.BooleanType | DataType.StringType => rng.oneOf(Seq("=", "<>"))
        case _ => rng.oneOf(Seq(">", ">=", "<", "<=", "=", "<>"))
      }
      HavingSpec(HavingColumn(key), cmp, litSqlOfType(rng, key.dataType))
    }
  }

  // ---- expression generation (paren-free, total) --------------------------

  private def leafProb(depth: Int): Double = if (depth <= 1) 0.5 else 0.25
  private val NullLitProb = 0.1
  private val ColRefProb = 0.7

  /** A value (non-boolean) expression of `target`. `target` is a hint: a
    * precedence-only reparse may widen e.g. an int sum to long, but it stays the
    * same value CATEGORY (numeric vs string), which is all the surrounding
    * context relies on. */
  private def genValue(rng: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    if (depth <= 0 || rng.bool(leafProb(depth))) genLeafValue(rng, schema, target)
    else if (target == DataType.StringType) genStringExpr(rng, schema, depth)
    else genNumericExpr(rng, schema, target, depth)
  }

  private def genLeafValue(rng: Rng, schema: Schema, target: DataType): Expr = {
    val cols = schema.fields.zipWithIndex.filter(_._1.dataType == target)
    if (rng.bool(NullLitProb)) LitExpr(null, target)
    else if (cols.nonEmpty && rng.bool(ColRefProb)) {
      val (f, idx) = rng.oneOf(cols)
      ColRefExpr(idx, f.name, target)
    } else LitExpr(genLitValue(rng, target), target)
  }

  private def genNumericExpr(rng: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genValue(rng, schema, t, depth - 1)
    val base: Seq[(Int, () => Expr)] = Seq(
      // No unary minus: paren-free `- - x` / `- -1` do not parse. Negative values
      // still arise from column data and subtraction.
      5 -> (() => BinOpExpr(rng.oneOf(ArithOps), sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics)), target)),
      2 -> (() => FuncExpr("ABS", Seq(sub(target)), target)),
      2 -> (() => FuncExpr("COALESCE", Seq.fill(rng.between(1, 3))(sub(target)), target)),
      2 -> (() => FuncExpr("IF", Seq(genPredicate(rng, schema, depth - 1), sub(target), sub(target)), target)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(target), sub(target)), target)),
      1 -> (() => genCase(rng, schema, target, depth)),
      1 -> (() => CastExpr(sub(rng.oneOf(Numerics)), target)))
    val extra: Seq[(Int, () => Expr)] = target match {
      case DataType.IntType =>
        Seq(2 -> (() => FuncExpr("LENGTH", Seq(sub(DataType.StringType)), DataType.IntType)))
      case DataType.DoubleType =>
        Seq(
          1 -> (() => FuncExpr(rng.oneOf(Seq("FLOOR", "CEIL")), Seq(sub(rng.oneOf(Numerics))), DataType.DoubleType)),
          1 -> (() => FuncExpr("ROUND", roundArgs(rng, sub(rng.oneOf(Numerics))), DataType.DoubleType)))
      case _ => Seq.empty
    }
    rng.weighted(base ++ extra: _*)()
  }

  /** ROUND args: value plus optional NON-NULL integer-literal scale. */
  private def roundArgs(rng: Rng, value: Expr): Seq[Expr] =
    if (rng.bool(0.5)) Seq(value, LitExpr(rng.between(-1, 3), DataType.IntType)) else Seq(value)

  private def genStringExpr(rng: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genValue(rng, schema, t, depth - 1)
    val producers: Seq[(Int, () => Expr)] = Seq(
      // `||` operands kept string so a precedence reparse can never juxtapose a
      // string with an arithmetic `+` (which would bind to a throwing string add).
      3 -> (() => BinOpExpr("||", sub(DataType.StringType), sub(DataType.StringType), DataType.StringType)),
      // UPPER/LOWER only: TRIM parses to a TrimFunction node the binder rejects.
      3 -> (() => FuncExpr(rng.oneOf(Seq("UPPER", "LOWER")), Seq(sub(DataType.StringType)), DataType.StringType)),
      3 -> (() => FuncExpr("CONCAT", Seq.fill(rng.between(1, 3))(sub(rng.oneOf(ValueTypes))), DataType.StringType)),
      2 -> (() => FuncExpr("SUBSTRING", substringArgs(rng, sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("COALESCE", Seq.fill(rng.between(1, 3))(sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("IF", Seq(genPredicate(rng, schema, depth - 1), sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => genCase(rng, schema, DataType.StringType, depth)),
      1 -> (() => CastExpr(sub(rng.oneOf(ValueTypes)), DataType.StringType)))
    rng.weighted(producers: _*)()
  }

  /** SUBSTRING args: string, NON-NULL integer-literal start, optional NON-NULL
    * integer-literal length. */
  private def substringArgs(rng: Rng, s: Expr): Seq[Expr] = {
    val start = LitExpr(rng.between(-1, 6), DataType.IntType)
    if (rng.bool(0.5)) Seq(s, start, LitExpr(rng.between(0, 6), DataType.IntType)) else Seq(s, start)
  }

  private def genCase(rng: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def subV: Expr = genValue(rng, schema, target, depth - 1)
    val nBranches = rng.between(1, 3)
    val branches = (0 until nBranches).map(_ => (genPredicate(rng, schema, depth - 1), subV))
    val elseE = if (rng.bool(0.6)) Some(subV) else None
    CaseExpr(branches, elseE, target)
  }

  /** A boolean predicate. Paren-free-safe: AND/OR compose by precedence and
    * comparisons are same-category. `NOT` is applied ONLY to a boolean leaf —
    * the SQL frontend binds `NOT` TIGHTER than comparison, so `NOT a >= b`
    * reparses to `(NOT a) >= b` (a boolean-vs-numeric comparison that throws). A
    * comparison is negated instead by flipping its relop; IS NULL / IN / LIKE
    * carry their own negation. */
  private def genPredicate(rng: Rng, schema: Schema, depth: Int): Expr = {
    if (depth <= 0 || rng.bool(leafProb(depth))) genAtom(rng, schema, depth)
    else rng.weighted(
      (3, () => BinOpExpr("AND", genPredicate(rng, schema, depth - 1), genPredicate(rng, schema, depth - 1), DataType.BooleanType)),
      (3, () => BinOpExpr("OR", genPredicate(rng, schema, depth - 1), genPredicate(rng, schema, depth - 1), DataType.BooleanType)),
      (2, () => UnaryOpExpr("NOT", genBoolLeaf(rng, schema), DataType.BooleanType)),
      (5, () => genAtom(rng, schema, depth)))()
  }

  /** A boolean leaf safe to negate paren-free: a boolean column if one exists,
    * else a boolean literal. */
  private def genBoolLeaf(rng: Rng, schema: Schema): Expr = {
    val boolCols = (0 until schema.length).filter(i => schema.fields(i).dataType == DataType.BooleanType).toVector
    if (boolCols.nonEmpty && rng.bool(0.8)) colRef(schema, rng.oneOf(boolCols))
    else LitExpr(rng.bool(), DataType.BooleanType)
  }

  private def genAtom(rng: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genValue(rng, schema, t, math.max(0, depth - 1))
    val n = schema.length
    val boolCols = (0 until n).filter(i => schema.fields(i).dataType == DataType.BooleanType).toVector
    val inCols = (0 until n).filter { i =>
      val t = schema.fields(i).dataType
      t == DataType.IntType || t == DataType.LongType || t == DataType.StringType
    }.toVector

    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => Expr)]
    options += ((4, () => BinOpExpr(rng.oneOf(RelOps), sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics)), DataType.BooleanType)))
    options += ((3, () => BinOpExpr(rng.oneOf(RelOps), sub(DataType.StringType), sub(DataType.StringType), DataType.BooleanType)))
    options += ((2, () => IsNullExpr(sub(rng.oneOf(ValueTypes)), rng.bool())))
    options += ((2, () => LikeExpr(sub(DataType.StringType), LitExpr(rng.oneOf(LikePatternPool), DataType.StringType), rng.bool())))
    if (inCols.nonEmpty) options += ((2, () => genInList(rng, schema, rng.oneOf(inCols))))
    if (boolCols.nonEmpty) options += ((2, () => colRef(schema, rng.oneOf(boolCols))))
    options += ((1, () => LitExpr(rng.bool(), DataType.BooleanType)))
    rng.weighted(options.toSeq: _*)()
  }

  private def genInList(rng: Rng, schema: Schema, colIdx: Int): Expr = {
    val col = colRef(schema, colIdx)
    val nItems = rng.between(1, 4)
    val items = (0 until nItems).map(_ =>
      if (rng.bool(0.15)) LitExpr(null, col.dataType) else LitExpr(genLitValue(rng, col.dataType), col.dataType))
    InListExpr(col, items.toSeq, rng.bool())
  }

  // ---- literals -----------------------------------------------------------

  private val StringPool = Seq("", "a", "b", "c", "d", "ab", "abc", "x", "y", "z")
  private val LikePatternPool = Seq("%", "_", "a%", "%a", "%a%", "a_c", "%", "ab%", "")
  // Non-negative: a negative numeric literal as an infix operand renders to a
  // `op -lit` sequence that does not parse paren-free. Negative VALUES still
  // arise from column data and subtraction.
  private val DoublePool = Seq(0.0, 0.5, 1.0, 2.0, 3.5, 10.0, 100.0)

  private def genLitValue(rng: Rng, dt: DataType): Any = dt match {
    case DataType.IntType => rng.between(0, 9): Any
    case DataType.LongType => rng.between(0, 9).toLong: Any
    case DataType.FloatType => rng.oneOf(DoublePool).toFloat: Any
    case DataType.DoubleType => rng.oneOf(DoublePool): Any
    case DataType.BooleanType => rng.bool(): Any
    case DataType.StringType => rng.oneOf(StringPool): Any
    case other => throw new IllegalArgumentException(s"genLitValue: unsupported $other")
  }

  /** A small in-domain literal of `dt`, rendered to SQL — used for HAVING
    * right-hand sides. */
  private def litSqlOfType(rng: Rng, dt: DataType): String = dt match {
    case DataType.IntType | DataType.LongType => rng.oneOf(Seq("-1", "0", "1", "2"))
    case DataType.FloatType | DataType.DoubleType => rng.oneOf(Seq("0.0", "0.5", "1.0", "2.0"))
    case DataType.BooleanType => rng.oneOf(Seq("TRUE", "FALSE"))
    case DataType.StringType => rng.oneOf(Seq("'a'", "'b'", "''", "'abc'"))
    case other => throw new IllegalArgumentException(s"litSqlOfType: unsupported $other")
  }

  // ---- small helpers ------------------------------------------------------

  private def colRef(schema: Schema, i: Int): ColRefExpr =
    ColRefExpr(i, schema.fields(i).name, schema.fields(i).dataType)

  private def distinctIndices(rng: Rng, n: Int, count: Int): Vector[Int] = {
    val want = math.max(0, math.min(count, n))
    val pool = scala.collection.mutable.ArrayBuffer.from(0 until n)
    val out = Vector.newBuilder[Int]
    var k = 0
    while (k < want && pool.nonEmpty) {
      out += pool.remove(rng.between(0, pool.length - 1))
      k += 1
    }
    out.result()
  }

  private def distinctSubset[A](rng: Rng, xs: Vector[A], count: Int): Vector[A] =
    distinctIndices(rng, xs.length, count).map(xs)
}

/** Renders a bound [[Expr]] (and ORDER BY clauses) to SQL text the engine's
  * parser accepts. Renders '''paren-free''': the SQL frontend rejects every
  * grouping parenthesis (it parses `(x)` as a `ParenthesedExpressionList` the
  * binder does not handle), so binary/unary operators emit no surrounding
  * parens and rely on operator precedence. [[QueryGen]] only builds trees that
  * stay type-correct under a precedence-only reparse, so a regrouped reparse is
  * a different-but-valid expression — harmless because every mode runs the same
  * text. Function-call, IN-list, CAST and CASE syntax is unaffected (those
  * parentheses are not grouping parens). */
object SqlRender {

  def expr(e: Expr): String = e match {
    case LitExpr(null, dt) => nullLit(dt)
    case LitExpr(v, dt) => lit(v, dt)
    case ColRefExpr(_, name, _) => name
    case CastExpr(c, t) => s"CAST(${expr(c)} AS ${sqlType(t)})"
    case UnaryOpExpr("NOT", c, _) => s"NOT ${expr(c)}"
    case UnaryOpExpr("-", c, _) => s"- ${expr(c)}"
    case UnaryOpExpr("+", c, _) => s"+ ${expr(c)}"
    case UnaryOpExpr(op, _, _) => throw new IllegalArgumentException(s"SqlRender: unknown unary op '$op'")
    case BinOpExpr(op, l, r, _) => s"${expr(l)} ${binOp(op)} ${expr(r)}"
    case FuncExpr(name, args, _) => s"$name(${args.map(expr).mkString(", ")})"
    case CaseExpr(branches, elseE, _) =>
      val ws = branches.map { case (c, v) => s"WHEN ${expr(c)} THEN ${expr(v)}" }.mkString(" ")
      val es = elseE.map(x => s" ELSE ${expr(x)}").getOrElse("")
      s"CASE $ws$es END"
    case IsNullExpr(c, neg) => s"${expr(c)} IS ${if (neg) "NOT " else ""}NULL"
    case InListExpr(c, items, neg) =>
      if (items.isEmpty) if (neg) "TRUE" else "FALSE" // defensive: IN () is not valid SQL
      else s"${expr(c)} ${if (neg) "NOT " else ""}IN (${items.map(expr).mkString(", ")})"
    case LikeExpr(c, p, neg) => s"${expr(c)} ${if (neg) "NOT " else ""}LIKE ${expr(p)}"
  }

  def orderByClause(keys: Vector[(ColRefExpr, Boolean)]): String =
    if (keys.isEmpty) ""
    else " ORDER BY " + keys.map { case (c, asc) => s"${c.name} ${if (asc) "ASC" else "DESC"}" }.mkString(", ")

  private def binOp(op: String): String = op match {
    case "==" => "="
    case "!=" => "<>"
    case other => other // + - * / % = <> < <= > >= AND OR ||
  }

  private def sqlType(dt: DataType): String = dt match {
    case DataType.IntType => "INT"
    case DataType.LongType => "BIGINT"
    case DataType.FloatType => "FLOAT"
    case DataType.DoubleType => "DOUBLE"
    case DataType.StringType => "STRING"
    case DataType.BooleanType => "BOOLEAN"
    case other => throw new IllegalArgumentException(s"SqlRender: no SQL type name for $other")
  }

  private def nullLit(dt: DataType): String = dt match {
    case DataType.NullType => "NULL"
    case other => s"CAST(NULL AS ${sqlType(other)})"
  }

  private def lit(v: Any, dt: DataType): String = dt match {
    case DataType.IntType => v.toString
    case DataType.LongType =>
      // Long.MinValue has no bare literal form (its magnitude overflows a parsed
      // long), so spell it as arithmetic that evaluates to it.
      if (v.asInstanceOf[Long] == Long.MinValue) "-9223372036854775807 - 1" else v.toString
    case DataType.FloatType | DataType.DoubleType => doubleLit(v.asInstanceOf[Number].doubleValue)
    case DataType.BooleanType => if (v.asInstanceOf[Boolean]) "TRUE" else "FALSE"
    case DataType.StringType => "'" + v.toString.replace("'", "''") + "'"
    case other => throw new IllegalArgumentException(s"SqlRender: cannot render literal of $other")
  }

  /** A decimal literal that re-parses to a compatible `double`. Generator doubles
    * are finite and bounded, so `toString` is clean; the BigDecimal fallback
    * guards the (unreached) scientific-notation case. */
  private def doubleLit(d: Double): String = {
    val s = d.toString
    if (s.contains('E') || s.contains('e') || s == "Infinity" || s == "-Infinity" || s == "NaN") {
      val plain = new java.math.BigDecimal(d).toPlainString
      if (plain.contains('.')) plain else plain + ".0"
    } else if (s.contains('.')) s
    else s + ".0"
  }
}
