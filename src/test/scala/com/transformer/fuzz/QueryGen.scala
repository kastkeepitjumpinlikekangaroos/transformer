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
  *     [ORDER BY <cols>] [LIMIT n] [OFFSET m]`, exercising `ProjectExec` /
  *     `FilterExec` / `DistinctExec` / `SortExec` / `LocalLimitExec` +
  *     `GlobalLimitExec` / `OffsetExec`;
  *   - '''aggregation''' — `SELECT <group cols>, <aggs> FROM t [WHERE p]
  *     [GROUP BY ...] [HAVING h] [ORDER BY <group cols>] [LIMIT n] [OFFSET m]`
  *     (or a global aggregate with no GROUP BY → one row), exercising
  *     `HashAggregateExec`.
  *
  * The expression generators ([[genValue]] / [[genPredicate]]) are written for
  * this property rather than reusing the bound-`Expr` shapes from [[ExprGen]],
  * for two reasons:
  *
  *   - '''Paren-free, but for decimal arithmetic.''' Expressions render without
  *     grouping parentheses and rely on operator precedence, so the generators
  *     only build trees that stay type-correct under a precedence-only reparse:
  *     arithmetic operands are numeric, `||` operands are string, comparisons
  *     are numeric-vs-numeric or string-vs-string (never boolean), and `NOT`
  *     wraps a single atom — so the reparsed grouping may differ but the result
  *     type never does. (Every mode runs the identical text, so a regrouped
  *     reparse is harmless.) Decimal arithmetic is the exception, rendered
  *     with the parentheses its shape needs (see [[SqlRender]]).
  *
  *   - '''Total by construction.''' Like [[ExprGen]], nothing here can throw for
  *     in-range input: casts are numeric->numeric or anything->string (never the
  *     string->numeric parse that can throw), and integer divide-by-zero returns
  *     NULL, as a NULL `ROUND` scale or `SUBSTRING` position does. So a query
  *     that binds also runs, in every mode.
  *
  * Excluded so the result multiset stays mode-invariant: `RAND()` and the clock
  * functions (`CURRENT_DATE`/`CURRENT_TIMESTAMP`) — each execution of a query
  * reads the clock afresh, so two modes legitimately disagree. `LIMIT` and `OFFSET` ARE generated, but only over a '''total'''
  * ORDER BY (every output column an order key), where rows tied at a cutoff are
  * identical rows and the kept slice is therefore a layout-independent multiset;
  * a bare `LIMIT` or `OFFSET` would keep a different SUBSET of the tied rows per
  * partition/merge order. The soundness condition is checked in `render`, so a
  * shrink that drops an order key or a projection item drops the `LIMIT` and
  * `OFFSET` with it rather than manufacturing a false counterexample.
  */
object QueryGen {

  /** The single registered view name every generated query selects from. */
  val ViewName: String = "t"

  private val Numerics: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType)
  /** The types a projected value or an `IS NULL` operand takes: every type
    * but BOOLEAN, which is a predicate here. Draw with `ExprGen.pick`. */
  private val ValueTypes: Seq[DataType] = ExprGen.GenTypes.filterNot(_ == DataType.BooleanType)
  /** The value types a CAST to STRING reads: all but BINARY, which casts to
    * nothing else. */
  private val TextTypes: Seq[DataType] = ValueTypes.filterNot(_ == DataType.BinaryType)
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
    def orderBy: Vector[OrderKey]
  }

  /** One ORDER BY key. `nullsFirst` is an explicit `NULLS FIRST` /
    * `NULLS LAST`; `None` writes no clause and gets the engine's default, NULL
    * as the smallest value (first under ASC, last under DESC). */
  final case class OrderKey(col: ColRefExpr, asc: Boolean, nullsFirst: Option[Boolean] = None) {
    def placesNullsFirst: Boolean = nullsFirst.getOrElse(asc)
    def render: String =
      s"${col.name} ${if (asc) "ASC" else "DESC"}" +
        nullsFirst.map(first => if (first) " NULLS FIRST" else " NULLS LAST").getOrElse("")
  }

  /** `SELECT [DISTINCT] <items> FROM view [WHERE p] [ORDER BY <cols>] [LIMIT n]
    * [OFFSET m]`. */
  final case class ProjectQuery(
      distinct: Boolean,
      projection: Vector[(Expr, String)],
      where: Option[Expr],
      orderBy: Vector[OrderKey],
      limit: Option[Long] = None,
      offset: Option[Long] = None) extends GenQuery {

    /** ORDER BY is a TOTAL order over the output: every projected item is a bare
      * column and every one of them is an order key. Two rows that tie on such a
      * key are identical rows, so which of them top-`n` keeps cannot change the
      * result multiset. */
    private def totallyOrdered: Boolean =
      projection.forall(_._1.isInstanceOf[ColRefExpr]) &&
        projection.map(_._2).toSet == orderBy.map(_.col.name).toSet

    def render(view: String): String = {
      val sel = projection.iterator.map { case (e, name) =>
        val rendered = SqlRender.expr(e)
        if (rendered == name) name else s"$rendered AS $name"
      }.mkString(", ")
      val d = if (distinct) "DISTINCT " else ""
      val w = where.map(p => s" WHERE ${SqlRender.expr(p)}").getOrElse("")
      val o = SqlRender.orderByClause(orderBy)
      s"SELECT $d$sel FROM $view$w$o${limitAndOffset(totallyOrdered, limit, offset)}"
    }
  }

  /** `SELECT <group cols>, <aggs> FROM view [WHERE p] [GROUP BY ...] [HAVING h]
    * [ORDER BY <group cols>] [LIMIT n]`. Empty `groupKeys` is a global aggregate
    * (one row, no GROUP BY / ORDER BY). */
  final case class AggregateQuery(
      groupKeys: Vector[ColRefExpr],
      aggregates: Vector[(AggSpec, String)],
      where: Option[Expr],
      having: Option[HavingSpec],
      groupByOrdinals: Boolean,
      orderBy: Vector[OrderKey],
      limit: Option[Long] = None,
      offset: Option[Long] = None) extends GenQuery {

    /** ORDER BY covers every GROUP BY key, and the keys uniquely identify an
      * output row — so the order is total and top-`n` selects the same groups
      * under every layout. */
    private def totallyOrdered: Boolean =
      groupKeys.nonEmpty && orderBy.map(_.col.index).toSet == groupKeys.map(_.index).toSet

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
      s"SELECT $sel FROM $view$w$g$h$o${limitAndOffset(totallyOrdered, limit, offset)}"
    }
  }

  /** ` LIMIT n OFFSET m`, either part optional — rendered only over a total
    * order, the soundness condition both clauses share. */
  private def limitAndOffset(totallyOrdered: Boolean, limit: Option[Long], offset: Option[Long]): String =
    if (!totallyOrdered) ""
    else limit.map(n => s" LIMIT $n").getOrElse("") + offset.map(m => s" OFFSET $m").getOrElse("")

  /** An aggregate to render into SELECT. */
  sealed trait AggSpec { def render: String }
  case object CountStar extends AggSpec { def render = "COUNT(*)" }
  final case class Count(arg: Expr, distinct: Boolean) extends AggSpec {
    def render: String = s"COUNT(${if (distinct) "DISTINCT " else ""}${SqlRender.expr(arg)})"
  }
  final case class Sum(arg: Expr, distinct: Boolean = false) extends AggSpec {
    def render: String = s"SUM(${if (distinct) "DISTINCT " else ""}${SqlRender.expr(arg)})"
  }
  final case class Avg(arg: Expr, distinct: Boolean = false) extends AggSpec {
    def render: String = s"AVG(${if (distinct) "DISTINCT " else ""}${SqlRender.expr(arg)})"
  }
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
    // A top-N shape: project bare columns only and order by all of them, the
    // total order LIMIT needs (see `ProjectQuery.totallyOrdered`).
    val topN = rng.bool(0.2)

    val orderCols: Vector[Int] =
      if (topN) distinctIndices(rng, n, rng.between(1, math.min(3, n)))
      else if (rng.bool(0.5)) distinctIndices(rng, n, rng.between(1, math.min(2, n)))
      else Vector.empty
    val extraBare: Vector[Int] =
      if (topN) Vector.empty else distinctIndices(rng, n, rng.between(0, math.min(2, n)))
    val bareCols: Vector[Int] = (orderCols ++ extraBare).distinct

    val computed: Vector[(Expr, String)] =
      if (topN) Vector.empty
      else (0 until rng.between(0, 3)).iterator.map { i =>
        (genValue(rng, schema, ExprGen.pick(rng, ValueTypes), rng.between(2, 4)), s"p$i")
      }.toVector

    val bareItems: Vector[(Expr, String)] = bareCols.map(i => colRef(schema, i)).map(cr => (cr: Expr, cr.name))
    val projection0 = bareItems ++ computed
    val projection = if (projection0.nonEmpty) projection0
      else Vector((colRef(schema, 0): Expr, schema.fields(0).name))

    val where = if (rng.bool(0.6)) Some(genPredicate(rng, schema, rng.between(2, 4))) else None
    val orderBy = orderCols.map(i => OrderKey(colRef(schema, i), rng.bool()))
    val (limit, offset) = withOffset(rng, topN, if (topN) Some(genLimit(rng)) else None)

    ProjectQuery(distinct, projection, where, withNullPlacement(rng, orderBy), limit, offset)
  }

  /** A LIMIT row count spanning the interesting cutoffs: none at all, one row,
    * a handful (a cut mid-batch and mid-partition), and past the end of the
    * data (the limit never binds). */
  private def genLimit(rng: Rng): Long = rng.weighted(
    (1, 0L), (3, 1L), (4, 2L), (4, 5L), (2, 17L), (2, 1000L))

  /** Give a top-n shape an OFFSET some of the time — beside its LIMIT, or in
    * place of it. Called after every other draw for the query and only for a
    * top-n shape, so every other query is the draw sequence it always was.
    * Shared with [[MetaQueryGen]]. */
  private[fuzz] def withOffset(rng: Rng, topN: Boolean, limit: Option[Long]): (Option[Long], Option[Long]) =
    if (!topN) (limit, None)
    else rng.weighted((5, 0), (4, 1), (1, 2)) match {
      case 0 => (limit, None)
      case 1 => (limit, Some(genOffset(rng)))
      case _ => (None, Some(genOffset(rng)))
    }

  /** Give some ORDER BY keys an explicit `NULLS FIRST` / `NULLS LAST`. Called
    * after every other draw for the query, and draws nothing when there is no
    * ORDER BY, so every other query is the draw sequence it always was. Shared
    * with [[MetaQueryGen]]. */
  private[fuzz] def genNullPlacement(rng: Rng): Option[Boolean] =
    rng.weighted[Option[Boolean]]((2, None), (1, Some(true)), (1, Some(false)))

  private def withNullPlacement(rng: Rng, keys: Vector[OrderKey]): Vector[OrderKey] =
    keys.map(k => k.copy(nullsFirst = genNullPlacement(rng)))

  /** Make some SUM / AVG aggregates `SUM(DISTINCT x)` / `AVG(DISTINCT x)`. Drawn
    * after every other draw for the query, and only for those two, so every
    * other query is the draw sequence it always was. */
  private def withDistinctInputs(rng: Rng, aggs: Vector[(AggSpec, String)]): Vector[(AggSpec, String)] =
    aggs.map {
      case (s: Sum, name) => (s.copy(distinct = rng.bool(0.4)), name)
      case (a: Avg, name) => (a.copy(distinct = rng.bool(0.4)), name)
      case other => other
    }

  /** An OFFSET spanning the cutoffs [[genLimit]] does: zero (a no-op), one row, a
    * handful (a cut mid-batch and mid-partition), and past the end of the data
    * (nothing left). Weighted to the small skips, because generated results are
    * small and a skip only tests anything when it lands inside one. */
  private def genOffset(rng: Rng): Long = rng.weighted(
    (1, 0L), (5, 1L), (4, 2L), (2, 5L), (1, 17L), (1, 1000L))

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
    // A top-N shape orders by EVERY group key, the total order LIMIT needs (see
    // `AggregateQuery.totallyOrdered`); the ordinary shape orders by a subset.
    val topN = groupKeys.nonEmpty && rng.bool(0.2)
    val orderBy =
      if (topN) groupKeys.map(cr => OrderKey(cr, rng.bool()))
      else if (groupKeys.nonEmpty && rng.bool(0.4))
        distinctSubset(rng, groupKeys, rng.between(1, math.min(2, groupKeys.length))).map(cr => OrderKey(cr, rng.bool()))
      else Vector.empty
    val (limit, offset) = withOffset(rng, topN, if (topN) Some(genLimit(rng)) else None)
    val placedOrderBy = withNullPlacement(rng, orderBy)

    AggregateQuery(groupKeys, withDistinctInputs(rng, aggregates), where, having, groupByOrdinals,
      placedOrderBy, limit, offset)
  }

  private def genAggSpec(rng: Rng, schema: Schema, numeric: Vector[Int], integral: Vector[Int]): AggSpec = {
    val n = schema.length
    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => AggSpec)]
    options += ((3, () => CountStar))
    options += ((2, () => Count(colRef(schema, rng.between(0, n - 1)), distinct = rng.bool(0.5))))
    // MIN/MAX over any column type, Boolean included — Booleans order FALSE <
    // TRUE and round-trip through spill like any other primitive.
    options += ((2, () => Min(colRef(schema, rng.between(0, n - 1)))))
    options += ((2, () => Max(colRef(schema, rng.between(0, n - 1)))))
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
    * numeric expression (still well-conditioned given the narrow domains), of
    * which over a DECIMAL half are a conditional of two decimal types
    * ([[genDecimalBranches]]), so a partial sum a spill writes holds them. */
  private def numericArg(rng: Rng, schema: Schema, numeric: Vector[Int]): Expr =
    if (rng.bool(0.7)) colRef(schema, rng.oneOf(numeric))
    else schema.fields(rng.oneOf(numeric)).dataType match {
      case t: DataType.DecimalType if rng.bool(0.5) =>
        val decimalCols = (0 until schema.length).filter(i => schema.fields(i).dataType.isInstanceOf[DataType.DecimalType])
        genDecimalBranches(rng, schema, t, decimalCols, 2)
      case t => genValue(rng, schema, t, rng.between(1, 2))
    }

  /** A HAVING over `COUNT(*)` or over a group key compared with a literal.
    * A BINARY key has no literal to compare with (SQL cannot write one), so it
    * is never the key. */
  private def genHaving(rng: Rng, groupKeys: Vector[ColRefExpr]): HavingSpec = {
    val comparable = groupKeys.filter(_.dataType != DataType.BinaryType)
    if (comparable.isEmpty || rng.bool(0.5))
      HavingSpec(HavingCountStar, rng.oneOf(Seq(">", ">=", "<", "<=", "=", "<>")), rng.between(0, 3).toString)
    else {
      val key = rng.oneOf(comparable)
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
    else if (Numerics.contains(target)) genNumericExpr(rng, schema, target, depth)
    else genOtherExpr(rng, schema, target, depth)
  }

  /** A column, a literal or a NULL of `target`. A BINARY has no literal (SQL
    * cannot write a byte array), so it is a column or a NULL. */
  private def genLeafValue(rng: Rng, schema: Schema, target: DataType): Expr = {
    val cols = schema.fields.zipWithIndex.filter(_._1.dataType == target)
    def column: Expr = { val (f, idx) = rng.oneOf(cols); ColRefExpr(idx, f.name, target) }
    if (rng.bool(NullLitProb)) LitExpr(null, target)
    else if (cols.nonEmpty && rng.bool(ColRefProb)) column
    else if (target == DataType.BinaryType) if (cols.nonEmpty) column else LitExpr(null, target)
    else LitExpr(genLitValue(rng, target), target)
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
          1 -> (() => FuncExpr("ROUND", roundArgs(rng, schema, sub(rng.oneOf(Numerics))), DataType.DoubleType)))
      case _ => Seq.empty
    }
    rng.weighted(base ++ extra: _*)()
  }

  /** ROUND args: the value, and half the time a scale. */
  private def roundArgs(rng: Rng, schema: Schema, value: Expr): Seq[Expr] =
    if (rng.bool(0.5)) Seq(value, integerArg(rng, schema, rng.between(-1, 3))) else Seq(value)

  /** A scale, a position or a length: mostly `small`, else a NULL, which makes
    * the answer NULL, or an INT or BIGINT column or literal, which can hold
    * NULL or lie past any string's end. A leaf, since a computed value's type
    * is a hint here and the argument takes an integer only. */
  private def integerArg(rng: Rng, schema: Schema, small: Int): Expr =
    rng.weighted[() => Expr](
      (6, () => LitExpr(small, DataType.IntType)),
      (2, () => LitExpr(null, DataType.IntType)),
      (2, () => genLeafValue(rng, schema, rng.oneOf(Seq(DataType.IntType, DataType.LongType)))))()

  private def genStringExpr(rng: Rng, schema: Schema, depth: Int): Expr = {
    def sub(t: DataType): Expr = genValue(rng, schema, t, depth - 1)
    val producers: Seq[(Int, () => Expr)] = Seq(
      // `||` and CONCAT join text only, so their operands are strings, which
      // also keeps a precedence reparse from meeting a string with an
      // arithmetic `+`. Other types reach them through a CAST to STRING.
      3 -> (() => BinOpExpr("||", sub(DataType.StringType), sub(DataType.StringType), DataType.StringType)),
      // UPPER/LOWER only: TRIM parses to a TrimFunction node the binder rejects.
      3 -> (() => FuncExpr(rng.oneOf(Seq("UPPER", "LOWER")), Seq(sub(DataType.StringType)), DataType.StringType)),
      3 -> (() => FuncExpr("CONCAT", Seq.fill(rng.between(1, 3))(sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("SUBSTRING", substringArgs(rng, schema, sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("COALESCE", Seq.fill(rng.between(1, 3))(sub(DataType.StringType)), DataType.StringType)),
      2 -> (() => FuncExpr("IF", Seq(genPredicate(rng, schema, depth - 1), sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(DataType.StringType), sub(DataType.StringType)), DataType.StringType)),
      1 -> (() => genCase(rng, schema, DataType.StringType, depth)),
      1 -> (() => CastExpr(sub(ExprGen.pick(rng, TextTypes)), DataType.StringType)))
    rng.weighted(producers: _*)()
  }

  /** SUBSTRING args: the string, a position, and half the time a length. */
  private def substringArgs(rng: Rng, schema: Schema, s: Expr): Seq[Expr] = {
    val start = integerArg(rng, schema, rng.between(-1, 6))
    if (rng.bool(0.5)) Seq(s, start, integerArg(rng, schema, rng.between(0, 6))) else Seq(s, start)
  }

  /** A DECIMAL, DATE, TIMESTAMP or BINARY value: the conditional forms every
    * type has, arithmetic and `ABS` of a DECIMAL, and a CAST between DATE and
    * TIMESTAMP. The engine has no arithmetic on the last three, and no CAST
    * into a DECIMAL or a BINARY, since the binder reads no type name for one. */
  private def genOtherExpr(rng: Rng, schema: Schema, target: DataType, depth: Int): Expr = {
    def sub(t: DataType): Expr = genValue(rng, schema, t, depth - 1)
    val base: Seq[(Int, () => Expr)] = Seq(
      2 -> (() => FuncExpr("COALESCE", Seq.fill(rng.between(1, 3))(sub(target)), target)),
      2 -> (() => FuncExpr("IF", Seq(genPredicate(rng, schema, depth - 1), sub(target), sub(target)), target)),
      1 -> (() => FuncExpr("NULLIF", Seq(sub(target), sub(target)), target)),
      1 -> (() => genCase(rng, schema, target, depth)))
    val decimalCols = (0 until schema.length).filter(i => schema.fields(i).dataType.isInstanceOf[DataType.DecimalType])
    val extra: Seq[(Int, () => Expr)] = target match {
      case _: DataType.DecimalType if decimalCols.nonEmpty => Seq(
        4 -> (() => genDecimalArith(rng, schema, target, decimalCols, depth)),
        5 -> (() => genDecimalBranches(rng, schema, target, decimalCols, depth)),
        1 -> (() => FuncExpr("ABS", Seq(sub(target)), target)))
      case _: DataType.DecimalType => Seq(1 -> (() => FuncExpr("ABS", Seq(sub(target)), target)))
      case DataType.DateType => Seq(1 -> (() => CastExpr(sub(DataType.TimestampType), target)))
      case DataType.TimestampType => Seq(1 -> (() => CastExpr(sub(DataType.DateType), target)))
      case _ => Seq.empty
    }
    rng.weighted(base ++ extra: _*)()
  }

  /** `(l op r)` over decimals, for the binder to type (`Analyzer.arithmetic`).
    * One side is an anchor, a DECIMAL column or arithmetic over one, which SQL
    * types as a DECIMAL whatever it meets; the other is an anchor, a number,
    * an integer leaf or a NULL, each of which a DECIMAL beside it reads as one.
    * Other decimal values are not sides: SQL reads a number as a DOUBLE until
    * it meets a DECIMAL, so `ABS(1.0)` or `COALESCE(1.0, NULL)` is a DOUBLE,
    * which the binder refuses beside a DECIMAL as it refuses a FLOAT or DOUBLE
    * column, and an integer-typed expression here can be a FLOAT (`c + 0` over
    * a FLOAT column). `SqlRender` gives decimal arithmetic the parentheses its
    * shape needs, so a reparse cannot group an integer column with a number
    * either. The values are `DataGen.DecimalPools`', all small, so no answer
    * overflows. */
  private def genDecimalArith(
      rng: Rng, schema: Schema, target: DataType, decimalCols: Seq[Int], depth: Int,
      topOps: Seq[String] = ArithOps): Expr = {
    def anchor(d: Int): Expr =
      if (d <= 1 || rng.bool(0.6)) colRef(schema, rng.oneOf(decimalCols)) else node(d - 1)
    def other(d: Int): Expr = rng.weighted(
      (3, () => anchor(d)),
      (2, () => {
        val t = schema.fields(rng.oneOf(decimalCols)).dataType.asInstanceOf[DataType.DecimalType]
        LitExpr(rng.oneOf(decimalLiterals(t)), t): Expr
      }),
      (2, () => genLeafValue(rng, schema, rng.oneOf(Seq(DataType.IntType, DataType.LongType)))),
      (1, () => LitExpr(null, target)))()
    def node(d: Int, ops: Seq[String] = ArithOps): Expr = {
      val (l, r) = if (rng.bool()) (anchor(d), other(d)) else (other(d), anchor(d))
      BinOpExpr(rng.oneOf(ops), l, r, target)
    }
    node(depth, topOps)
  }

  /** A COALESCE, IF or CASE with a decimal product or quotient as one value
    * and, usually first, a leaf: mostly the decimal column with the fewest
    * fractional digits, else a leaf of `target`. The two are DECIMALs of
    * different types, which the list's type holds both of, and the product's
    * or quotient's answers mostly have more fractional digits than the leaf's
    * type keeps: the shape that once took the first value's type and kept
    * the answer as it was, which a spill could not write
    * (`plans/robustness/09-branch-result-types.md`). Conditionals over values
    * drawn independently meet it on a few seeds in a thousand, and mostly
    * answer with the leaf, so it is drawn on purpose: half the time the
    * arithmetic is a column by a number whose digits do not cancel (a column
    * often holds one value at several scales, and `c / c` is 1), under a
    * condition that takes it wherever that column has a value. */
  private def genDecimalBranches(
      rng: Rng, schema: Schema, target: DataType, decimalCols: Seq[Int], depth: Int): Expr = {
    def scale(i: Int): Int = schema.fields(i).dataType.asInstanceOf[DataType.DecimalType].scale
    val leaf =
      if (rng.bool(0.7)) colRef(schema, decimalCols.minBy(scale))
      else genLeafValue(rng, schema, target)
    val leafFirst = rng.bool(0.75)
    val (arith, cond) =
      if (rng.bool(0.5)) {
        val c = colRef(schema, rng.oneOf(decimalCols))
        (BinOpExpr(rng.oneOf(Seq("*", "/")), c, rng.oneOf(UnevenNumbers), target), IsNullExpr(c, negated = !leafFirst))
      } else
        (genDecimalArith(rng, schema, target, decimalCols, depth - 1, topOps = Seq("*", "/")),
          genPredicate(rng, schema, depth - 1))
    val (a, b) = if (leafFirst) (leaf, arith) else (arith, leaf)
    // A COALESCE reaches its arithmetic only where the leaf is NULL, which
    // over the leaf's own column makes the answer NULL too.
    rng.weighted[() => Expr](
      (1, () => FuncExpr("COALESCE", Seq(a, b), target)),
      (2, () => FuncExpr("IF", Seq(cond, a, b), target)),
      (2, () => CaseExpr(Seq((cond, a)), Some(b), target)))()
  }

  /** Numbers a decimal is multiplied or divided by in [[genDecimalBranches]],
    * as the DECIMALs of their digits: each leaves a remainder of a quotient,
    * or fractional digits in a product, for the pools' every non-zero value. */
  private[fuzz] val UnevenNumbers: Seq[LitExpr] = Seq("0.3333", "0.07", "3", "7").map { s =>
    val d = new java.math.BigDecimal(s)
    LitExpr(d, DataType.DecimalType(math.max(d.precision, d.scale), d.scale))
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
    * JSqlParser reads `NOT a >= b` as `(NOT a) >= b` in some places (the
    * argument of a one-argument call, after a second NOT), which the binder
    * refuses. A comparison is negated instead by flipping its relop; IS NULL /
    * IN / LIKE carry their own negation. */
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
    def colsWhere(p: DataType => Boolean): Vector[Int] =
      (0 until n).filter(i => p(schema.fields(i).dataType)).toVector
    val boolCols = colsWhere(_ == DataType.BooleanType)
    val inCols = colsWhere(t => t != DataType.BooleanType && !(t == DataType.FloatType || t == DataType.DoubleType))
    val temporalCols = colsWhere(t => t == DataType.DateType || t == DataType.TimestampType)
    val decimalCols = colsWhere(_.isInstanceOf[DataType.DecimalType])
    val binaryCols = colsWhere(_ == DataType.BinaryType)

    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => Expr)]
    options += ((4, () => BinOpExpr(rng.oneOf(RelOps), sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics)), DataType.BooleanType)))
    options += ((3, () => BinOpExpr(rng.oneOf(RelOps), sub(DataType.StringType), sub(DataType.StringType), DataType.BooleanType)))
    options += ((2, () => IsNullExpr(sub(ExprGen.pick(rng, ValueTypes)), rng.bool())))
    options += ((2, () => LikeExpr(sub(DataType.StringType), LitExpr(rng.oneOf(LikePatternPool), DataType.StringType), rng.bool())))
    if (inCols.nonEmpty) options += ((2, () => genInList(rng, schema, rng.oneOf(inCols))))
    if (boolCols.nonEmpty) options += ((2, () => colRef(schema, rng.oneOf(boolCols))))
    // The four types the corpus once lacked, each where a column of it is in
    // scope, so a comparison of one has data behind it.
    if (temporalCols.nonEmpty) options += ((3, () => {
      val t = schema.fields(rng.oneOf(temporalCols)).dataType
      BinOpExpr(rng.oneOf(RelOps), sub(t), temporalOperand(rng, schema, t, depth), DataType.BooleanType)
    }))
    if (decimalCols.nonEmpty) options += ((3, () => {
      val t = schema.fields(rng.oneOf(decimalCols)).dataType
      BinOpExpr(rng.oneOf(RelOps), sub(t), decimalOperand(rng, schema, t, depth), DataType.BooleanType)
    }))
    // Decimal arithmetic, compared, so it reaches a filter as well as a
    // projection.
    if (decimalCols.nonEmpty) options += ((2, () => {
      val t = schema.fields(rng.oneOf(decimalCols)).dataType
      val arith = genDecimalArith(rng, schema, t, decimalCols, math.max(1, depth))
      BinOpExpr(rng.oneOf(RelOps), arith, decimalOperand(rng, schema, t, depth), DataType.BooleanType)
    }))
    if (binaryCols.nonEmpty) options += ((2, () =>
      BinOpExpr(rng.oneOf(RelOps), sub(DataType.BinaryType), sub(DataType.BinaryType), DataType.BooleanType)))
    options += ((1, () => LitExpr(rng.bool(), DataType.BooleanType)))
    rng.weighted(options.toSeq: _*)()
  }

  /** The other side of a comparison with a DATE or TIMESTAMP: an expression of
    * the same type, or a literal written as a quoted ISO string, which the
    * binder reads as the type it meets. The string is the form a user writes
    * (`d = '2024-02-29'`), and the binder's own path for it. */
  private def temporalOperand(rng: Rng, schema: Schema, t: DataType, depth: Int): Expr =
    if (rng.bool(0.3)) LitExpr(SqlRender.temporalText(genLitValue(rng, t)), DataType.StringType)
    else genValue(rng, schema, t, math.max(0, depth - 1))

  /** The other side of a comparison with a DECIMAL of type `t`: a decimal
    * expression, of its own declared type or another one in scope, or an
    * integer column or literal, which the analyzer widens to it exactly. Never
    * anything that can be a FLOAT or a DOUBLE: the widening CAST refuses `NaN`
    * and the infinities, which float columns hold, so the query would fail
    * mid-drain, by the engine's design (see `Casts`). That rules out an
    * integer EXPRESSION too, since the target type is only a hint here and
    * `c + 0` over a FLOAT column is a FLOAT; a leaf is exactly its type. A
    * decimal's own literals are double literals, but finite ones. */
  private def decimalOperand(rng: Rng, schema: Schema, t: DataType, depth: Int): Expr = {
    val decimals = (t +: schema.fields.map(_.dataType).filter(_.isInstanceOf[DataType.DecimalType])).distinct
    rng.weighted(
      (3, () => genValue(rng, schema, rng.oneOf(decimals), math.max(0, depth - 1))),
      (1, () => genLeafValue(rng, schema, rng.oneOf(Seq(DataType.IntType, DataType.LongType)))))()
  }

  /** `col [NOT] IN (...)`: literal items, a NULL now and then, and for a
    * BINARY, which has no literal, the BINARY columns in scope. A DATE or
    * TIMESTAMP item is now and then a quoted ISO string. */
  private def genInList(rng: Rng, schema: Schema, colIdx: Int): Expr = {
    val col = colRef(schema, colIdx)
    val binaryCols = (0 until schema.length).filter(i => schema.fields(i).dataType == DataType.BinaryType).toVector
    val nItems = rng.between(1, 4)
    val items = (0 until nItems).map { _ =>
      if (rng.bool(0.15)) LitExpr(null, col.dataType)
      else col.dataType match {
        case DataType.BinaryType => colRef(schema, rng.oneOf(binaryCols))
        case t @ (DataType.DateType | DataType.TimestampType) if rng.bool(0.3) =>
          LitExpr(SqlRender.temporalText(genLitValue(rng, t)), DataType.StringType)
        case t => LitExpr(genLitValue(rng, t), t)
      }
    }
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
    case DataType.DateType => rng.oneOf(ExprGen.DatePool): Any
    case DataType.TimestampType => rng.oneOf(ExprGen.TimestampPool): Any
    case d: DataType.DecimalType => rng.oneOf(decimalLiterals(d)): Any
    case other => throw new IllegalArgumentException(s"genLitValue: unsupported $other")
  }

  /** The decimals a literal of `dt` is drawn from: the column pool's
    * non-negative values that SQL can write (see `SqlRender.writesExactly`),
    * which drops `1.000000000000000001`, a number that reads back as `1.0`.
    * A DECIMAL no column is declared as, an aggregate's or arithmetic's, draws
    * from every pool. Shared with `MetaQueryGen`. */
  private[fuzz] def decimalLiterals(dt: DataType.DecimalType): IndexedSeq[java.math.BigDecimal] =
    DataGen.DecimalPools.getOrElse(dt, DataGen.DecimalPools.values.flatten.toIndexedSeq)
      .filter(v => v.signum >= 0 && SqlRender.writesExactly(v))

  /** A small in-domain literal of `dt`, rendered to SQL — used for HAVING
    * right-hand sides. */
  private def litSqlOfType(rng: Rng, dt: DataType): String = dt match {
    case DataType.IntType | DataType.LongType => rng.oneOf(Seq("-1", "0", "1", "2"))
    case DataType.FloatType | DataType.DoubleType => rng.oneOf(Seq("0.0", "0.5", "1.0", "2.0"))
    case DataType.BooleanType => rng.oneOf(Seq("TRUE", "FALSE"))
    case DataType.StringType => rng.oneOf(Seq("'a'", "'b'", "''", "'abc'"))
    case DataType.DateType | DataType.TimestampType | _: DataType.DecimalType =>
      SqlRender.expr(LitExpr(genLitValue(rng, dt), dt))
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

  // ---- window frames ------------------------------------------------------

  /** One window aggregate over `t(id, g, k, x)` for `oracle/WindowFrames`,
    * which works out each row's frame from SQL's definition. `id` is unique,
    * `g` the partition, `k` the ORDER BY key (an INT, BIGINT, DOUBLE or
    * DECIMAL with ties, NULLs and, for a DOUBLE, NaN and the infinities) and
    * `x` the BIGINT the aggregate reads. A ROWS frame orders by `k` and then
    * `id`, so its rows have one order; a RANGE frame orders by `k` alone,
    * since its frames are sets of values that ties cannot change. */
  final case class WindowFrameCase(
      dataset: DataGen.Dataset,
      fn: String,
      partitioned: Boolean,
      order: Option[WindowOrder],
      frame: Option[GenFrame]) {
    def sql: String = {
      val partition = if (partitioned) "PARTITION BY g " else ""
      val orderBy = order.map { o =>
        val key = s"k ${if (o.asc) "ASC" else "DESC"}${o.nullsFirst.map(f => if (f) " NULLS FIRST" else " NULLS LAST").getOrElse("")}"
        if (frame.exists(_.rows)) s"ORDER BY $key, id " else s"ORDER BY $key "
      }.getOrElse("")
      s"SELECT id, $fn OVER ($partition$orderBy${frame.map(_.sql).getOrElse("")}) AS w FROM $ViewName"
    }
    override def toString: String = s"WindowFrameCase(\n  sql = $sql\n  data = $dataset)"
  }

  /** The ORDER BY key's direction, and where its NULLs go when the query says
    * (`None`: first under ASC, last under DESC). */
  final case class WindowOrder(asc: Boolean, nullsFirst: Option[Boolean]) {
    def nullsFirstNow: Boolean = nullsFirst.getOrElse(asc)
  }

  /** A frame clause: ROWS or RANGE, and its two bounds, `kind` each of
    * `UNBOUNDED PRECEDING`, `PRECEDING`, `CURRENT ROW`, `FOLLOWING` and
    * `UNBOUNDED FOLLOWING` with an offset for the two in between. `short`
    * writes the start alone (`ROWS 2 PRECEDING`), whose end is CURRENT ROW. */
  final case class GenFrame(rows: Boolean, start: GenBound, end: GenBound, short: Boolean) {
    def sql: String = {
      val kind = if (rows) "ROWS" else "RANGE"
      if (short) s"$kind ${start.sql}" else s"$kind BETWEEN ${start.sql} AND ${end.sql}"
    }
  }
  final case class GenBound(kind: Int, offset: java.math.BigDecimal) {
    def sql: String = kind match {
      case 0 => "UNBOUNDED PRECEDING"
      case 1 => s"${offset.toPlainString} PRECEDING"
      case 2 => "CURRENT ROW"
      case 3 => s"${offset.toPlainString} FOLLOWING"
      case _ => "UNBOUNDED FOLLOWING"
    }
  }

  private val WindowKeyDomains: Seq[(DataType, Seq[Any])] = Seq(
    DataType.IntType -> (-2 to 4).map(i => i: Any),
    DataType.LongType -> (-2 to 4).map(i => i.toLong: Any),
    DataType.DoubleType -> Seq[Any](-1.5, 0.0, 0.5, 1.0, 2.5, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity),
    DataType.DecimalType(9, 2) -> Seq("-1.50", "0.00", "0.50", "1.00", "2.25").map(v => new java.math.BigDecimal(v): Any))

  def generateWindowFrameCase(rng: Rng): WindowFrameCase = {
    val dataRng = rng.split()
    val qRng = rng.split()
    val (keyType, domain) = qRng.oneOf(WindowKeyDomains)
    val schema = Schema(Vector(Field("id", DataType.IntType), Field("g", DataType.IntType),
      Field("k", keyType), Field("x", DataType.LongType)))
    val n = dataRng.between(0, 20)
    val rows = (0 until n).map { i =>
      Array[Any](i,
        if (dataRng.bool(0.1)) null else dataRng.between(0, 1),
        if (dataRng.bool(0.12)) null else dataRng.oneOf(domain),
        if (dataRng.bool(0.1)) null else dataRng.between(-3, 9).toLong)
    }
    val fn = qRng.oneOf(Seq("SUM(x)", "COUNT(x)", "COUNT(*)", "MIN(x)", "MAX(x)", "AVG(x)"))
    val order =
      if (qRng.bool(0.85)) Some(WindowOrder(qRng.bool(), qRng.weighted((2, None), (1, Some(true)), (1, Some(false)))))
      else None
    val frame: Option[GenFrame] = qRng.weighted[() => Option[GenFrame]](
      (2, () => None),
      (3, () => if (order.isEmpty) None else Some(genFrame(qRng, rows = true))),
      (3, () => Some(genFrame(qRng, rows = false, offsets = order.isDefined)))
    )()
    WindowFrameCase(DataGen.Dataset(schema, rows), fn, qRng.bool(0.7), order, frame)
  }

  /** Two bounds SQL allows: a start that is not UNBOUNDED FOLLOWING, an end
    * that is not UNBOUNDED PRECEDING, and a start no later in the order of
    * kinds than its end. A RANGE offset needs an ORDER BY (`offsets`). */
  private def genFrame(r: Rng, rows: Boolean, offsets: Boolean = true): GenFrame = {
    def offset(): java.math.BigDecimal =
      if (rows) java.math.BigDecimal.valueOf(r.between(0, 3).toLong)
      else new java.math.BigDecimal(r.oneOf(Seq("0", "1", "2", "1.5")))
    def bound(kinds: Seq[Int]): GenBound = GenBound(r.oneOf(kinds), offset())
    val kinds = if (offsets) Seq(0, 1, 2, 3, 4) else Seq(0, 2, 4)
    val start = bound(kinds.filter(_ != 4))
    val end = bound(kinds.filter(k => k != 0 && k >= start.kind))
    // The short form writes a start whose end is CURRENT ROW.
    if (start.kind <= 2 && r.bool(0.2)) GenFrame(rows, start, GenBound(2, java.math.BigDecimal.ZERO), short = true)
    else GenFrame(rows, start, end, short = false)
  }
}

/** Renders a bound [[Expr]] (and ORDER BY clauses) to SQL text the engine's
  * parser accepts. Renders '''paren-free''': binary/unary operators emit no
  * surrounding parens and rely on operator precedence. [[QueryGen]] only builds
  * trees that stay type-correct under a precedence-only reparse, so a
  * regrouped reparse is a different-but-valid expression — harmless because
  * every mode runs the same text. Function-call, IN-list, CAST and CASE syntax
  * is unaffected (those parentheses are not grouping parens).
  *
  * Decimal arithmetic is the exception: it renders with the grouping
  * parentheses its shape needs, so the text reparses to exactly the generated
  * tree. Its type depends on what each side is (`Analyzer.arithmetic`), and a
  * regrouped reparse could join an integer column with a number, a DOUBLE,
  * which the binder refuses beside a DECIMAL. Only the parentheses precedence
  * needs, since JSqlParser rejects some statements with three stacked ones. */
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
    case BinOpExpr(op, l, r, _: DataType.DecimalType) if ArithmeticOps(op) =>
      s"${decimalSide(l, op, left = true)} ${binOp(op)} ${decimalSide(r, op, left = false)}"
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
    case LikeExpr(c, p, neg, syntax) =>
      s"${expr(c)} ${if (neg) "NOT " else ""}${if (syntax.caseInsensitive) "ILIKE" else "LIKE"} ${expr(p)}"
  }

  private val ArithmeticOps: Set[String] = Set("+", "-", "*", "/", "%")

  /** A side of decimal arithmetic under `parentOp`, in parentheses when it is
    * decimal arithmetic that precedence would otherwise regroup: one that
    * binds more loosely, or as tightly on the right (`a - (b - c)`). */
  private def decimalSide(side: Expr, parentOp: String, left: Boolean): String = side match {
    case BinOpExpr(op, _, _, _: DataType.DecimalType) if ArithmeticOps(op) &&
        (precedence(op) < precedence(parentOp) || (!left && precedence(op) == precedence(parentOp))) =>
      s"(${expr(side)})"
    case _ => expr(side)
  }

  private def precedence(op: String): Int = if (op == "+" || op == "-") 1 else 2

  def orderByClause(keys: Vector[QueryGen.OrderKey]): String =
    if (keys.isEmpty) "" else " ORDER BY " + keys.map(_.render).mkString(", ")

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
    case DataType.DateType => "DATE"
    case DataType.TimestampType => "TIMESTAMP"
    case other => throw new IllegalArgumentException(s"SqlRender: no SQL type name for $other")
  }

  /** A typed NULL, `CAST(NULL AS t)`. The binder reads no type name for a
    * DECIMAL or a BINARY, so their NULL is the untyped one, which takes the type
    * of what it meets (the other side of a comparison, COALESCE's or CASE's
    * other values) and is NULL-typed where it meets nothing. */
  private def nullLit(dt: DataType): String = dt match {
    case DataType.NullType | DataType.BinaryType | _: DataType.DecimalType => "NULL"
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
    case DataType.DateType => s"DATE '$v'"
    case DataType.TimestampType => s"TIMESTAMP '${temporalText(v)}'"
    case _: DataType.DecimalType =>
      val d = v.asInstanceOf[java.math.BigDecimal]
      require(writesExactly(d), s"SqlRender: no literal reads back as the decimal ${d.toPlainString}")
      d.toPlainString
    // BINARY: SQL has no literal for a byte array, and the generators never
    // build one; its NULL renders through `nullLit`.
    case other => throw new IllegalArgumentException(s"SqlRender: cannot render literal of $other")
  }

  /** A TIMESTAMP literal's text, in UTC as every time in the engine is, to the
    * microsecond a `TimestampVector` holds. */
  private val TimestampText: java.time.format.DateTimeFormatter =
    java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS").withZone(java.time.ZoneOffset.UTC)

  /** A DATE or TIMESTAMP value as the text of its literal: `2024-02-29`, or
    * `2024-03-10 07:00:00.000001`. */
  def temporalText(v: Any): String = v match {
    case i: java.time.Instant => TimestampText.format(i)
    case other => other.toString
  }

  /** Whether `d` can be written as a literal. SQL has no DECIMAL literal: a
    * number with a point reads as a DOUBLE, which the analyzer widens to the
    * decimal it meets as the decimal it prints as, so only a value that a
    * double holds exactly survives the trip (`1.000000000000000001` comes back
    * `1.0`). Spelling is not kept either (`1.00` comes back `1.0`), but that is
    * the same value. */
  def writesExactly(d: java.math.BigDecimal): Boolean =
    java.math.BigDecimal.valueOf(d.doubleValue).compareTo(d) == 0

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
