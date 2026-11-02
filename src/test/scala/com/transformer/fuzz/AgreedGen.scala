package com.transformer.fuzz

import com.transformer.core._
import com.transformer.fuzz.PortableSql.Shape
import com.transformer.sql.plan._

import java.math.{BigDecimal => JBigDecimal}

/** The cases of the corpus whose answers Postgres and DuckDB agree on
  * (plans/testing/14, W3): for each of its features, tables and queries
  * over them, drawn from `QueryGen` and `MetaQueryGen` where those reach the
  * feature, and built here from their pools and node types where they do not
  * (`TRIM`, `ROUND` and `TRUNC` of a decimal, `LAG` and `LEAD` with a
  * default, `BETWEEN`, a date's and a timestamp's text, a top-n over a float
  * key). `slt.AgreedCorpus` prints each case with [[PortableSql]] and runs it
  * in the engine and both references.
  *
  * A case is a few tables and up to [[QueriesPerCase]] queries over them, each
  * of which reaches the case's feature. Tables keep at most [[MaxRows]] rows,
  * and a float column holds no -0.0: where it ties 0.0, which of the two a
  * group, a `DISTINCT`, a `MIN` or a cutoff keeps is each reference's own
  * choice, and the engine's is a rule of its own.
  */
object AgreedGen {

  /** A table of a case, by the name its queries read. */
  final case class Table(name: String, data: DataGen.Dataset)

  /** Tables and the queries over them. */
  final case class Case(tables: Vector[Table], queries: Vector[Shape])

  /** One feature of the corpus: its script's name, the records it keeps
    * (the quota W6's guard holds it to), and what it covers. */
  sealed abstract class Feature(val id: String, val quota: Int, val title: String)

  object Feature {
    case object Window extends Feature("window", 600,
      "window frames, ROWS and RANGE with numeric offsets, peers, ROW_NUMBER, RANK, DENSE_RANK, LAG and LEAD with defaults")
    case object Decimal extends Feature("decimal", 500,
      "DECIMAL arithmetic and comparison, SUM, AVG, MIN and MAX, ROUND and TRUNC over decimals")
    case object OuterJoin extends Feature("outer_join", 400,
      "LEFT, RIGHT and FULL joins on equalities, with NULL keys and unmatched rows")
    case object Text extends Feature("string", 400,
      "LENGTH, UPPER, LOWER, TRIM, SUBSTRING, CONCAT, || and LIKE")
    case object Temporal extends Feature("datetime", 300,
      "DATE and TIMESTAMP comparison, BETWEEN, ordering, grouping, joins and casts to and from text")
    case object SetOps extends Feature("cte_union_distinct", 400,
      "CTEs, UNION and UNION ALL, DISTINCT and COUNT(DISTINCT)")
    case object TopN extends Feature("order_limit", 400,
      "ORDER BY, LIMIT and OFFSET, a top-n over every type")

    val All: Seq[Feature] = Seq(Window, Decimal, OuterJoin, Text, Temporal, SetOps, TopN)

    def byId(id: String): Option[Feature] = All.find(_.id == id)
  }

  val QueriesPerCase: Int = 8
  val MaxRows: Int = 32
  /** How many queries a producer draws for a case before it stops looking
    * for ones that reach the feature. */
  private val MaxDraws: Int = 60

  def generate(feature: Feature, rng: Rng): Case = {
    val c = draw(feature, rng)
    // A case of empty tables answers every query alike: one is enough.
    if (c.tables.forall(_.data.rows.isEmpty)) c.copy(queries = c.queries.take(1)) else c
  }

  private def draw(feature: Feature, rng: Rng): Case = feature match {
    case Feature.Window => window(rng)
    case Feature.Decimal => if (rng.bool(0.5)) singleTable(rng, hasType(isDecimal), readsDecimal) else decimal(rng)
    case Feature.OuterJoin => multiTable(rng, _ => true, outerJoin)
    case Feature.Text => if (rng.bool(0.5)) singleTable(rng, hasType(_ == DataType.StringType), readsText) else text(rng)
    case Feature.Temporal => rng.weighted[() => Case](
      (2, () => temporal(rng)),
      (1, () => singleTable(rng, hasType(isTemporal), readsTemporal)),
      (1, () => multiTable(rng, env => isTemporal(env.relations.head.schema.fields.head.dataType), _ => true)))()
    case Feature.SetOps =>
      if (rng.bool(0.7)) multiTable(rng, _ => true, setOps) else singleTable(rng, _ => true, setOps)
    case Feature.TopN => rng.weighted[() => Case](
      (2, () => topN(rng)),
      (1, () => singleTable(rng, _ => true, hasTopN)),
      (1, () => multiTable(rng, _ => true, hasTopN)))()
  }

  // ---- tables ------------------------------------------------------------------

  /** A dataset as the corpus keeps it: at most [[MaxRows]] rows, and 0.0 for
    * -0.0 in a float column. */
  def tidy(d: DataGen.Dataset): DataGen.Dataset = {
    val floats = d.schema.fields.indices.filter(c =>
      d.schema.fields(c).dataType == DataType.DoubleType || d.schema.fields(c).dataType == DataType.FloatType)
    val rows = d.rows.take(MaxRows).map { r =>
      val out = r.clone()
      floats.foreach { c =>
        out(c) match {
          case x: java.lang.Double if x.doubleValue == 0.0 => out(c) = java.lang.Double.valueOf(0.0)
          case x: java.lang.Float if x.floatValue == 0.0f => out(c) = java.lang.Float.valueOf(0.0f)
          case _ => ()
        }
      }
      out
    }
    DataGen.Dataset(d.schema, rows)
  }

  private def isDecimal(t: DataType): Boolean = t.isInstanceOf[DataType.DecimalType]
  private def isTemporal(t: DataType): Boolean = t == DataType.DateType || t == DataType.TimestampType
  private def hasType(p: DataType => Boolean): Schema => Boolean = s => s.fields.exists(f => p(f.dataType))

  // ---- QueryGen's and MetaQueryGen's queries, kept where they reach the feature --

  /** A DataGen table whose schema `wanted` takes, and QueryGen's queries over
    * it that `keep` takes. */
  private def singleTable(rng: Rng, wanted: Schema => Boolean, keep: Shape => Boolean): Case = {
    val data = Iterator.continually(DataGen.generate(rng.split())).take(200).find(d => wanted(d.schema))
      .getOrElse(DataGen.generate(rng.split()))
    val queries = Iterator.continually(Shape.Single(QueryGen.genQuery(rng.split(), data.schema)): Shape)
      .take(MaxDraws).filter(keep).take(QueriesPerCase).toVector
    Case(Vector(Table(QueryGen.ViewName, tidy(data))), queries)
  }

  /** MetaQueryGen's relations, an environment `wanted` takes, and its queries
    * over them that `keep` takes. */
  private def multiTable(rng: Rng, wanted: MetaQueryGen.RelEnv => Boolean, keep: Shape => Boolean): Case = {
    val env = Iterator.continually(MetaQueryGen.genEnv(rng.split())).take(200).find(wanted)
      .getOrElse(MetaQueryGen.genEnv(rng.split()))
    val queries = Iterator.continually(Shape.Multi(MetaQueryGen.genQuery(rng.split(), env)): Shape)
      .take(MaxDraws).filter(keep).take(QueriesPerCase).toVector
    Case(env.relations.map(r => Table(r.name, tidy(r.dataset))), queries)
  }

  // ---- what a query reaches ------------------------------------------------------

  /** The arms of a query: itself, its CTE bodies and its UNION arms. */
  private def arms(q: MetaQueryGen.MetaQuery): Vector[MetaQueryGen.MetaQuery] =
    (q +: q.ctes.flatMap(c => arms(c.body))) ++ q.setOp.toVector.flatMap { case (rhs, _) => arms(rhs) }

  private def aggArgs(a: QueryGen.AggSpec): Seq[Expr] = a match {
    case QueryGen.CountStar => Nil
    case QueryGen.Count(arg, _) => Seq(arg)
    case QueryGen.Sum(arg, _) => Seq(arg)
    case QueryGen.Avg(arg, _) => Seq(arg)
    case QueryGen.Min(arg) => Seq(arg)
    case QueryGen.Max(arg) => Seq(arg)
    case QueryGen.CountIf(arg) => Seq(arg)
    case QueryGen.Stat(_, arg) => Seq(arg)
    case QueryGen.Bivar(_, x, y) => Seq(x, y)
  }

  /** Every expression a query computes: its projected items, conditions and
    * aggregates' arguments, bare columns included. */
  private def exprs(shape: Shape): Seq[Expr] = shape match {
    case Shape.Single(pq: QueryGen.ProjectQuery) => pq.projection.map(_._1) ++ pq.where
    case Shape.Single(aq: QueryGen.AggregateQuery) => aq.aggregates.flatMap(a => aggArgs(a._1)) ++ aq.where
    case Shape.Multi(q) => arms(q).flatMap { a =>
      a.from.joins.map(_.on) ++ a.where ++ (a.core match {
        case MetaQueryGen.ProjectCore(items, _, _) => items.map(_._1)
        case MetaQueryGen.AggCore(keys, aggs, having) => keys.map(_._1) ++ aggs.flatMap(x => aggArgs(x._1)) ++ having
      })
    }
  }

  private def aggregates(shape: Shape): Seq[QueryGen.AggSpec] = shape match {
    case Shape.Single(aq: QueryGen.AggregateQuery) => aq.aggregates.map(_._1)
    case Shape.Single(_) => Nil
    case Shape.Multi(q) => arms(q).flatMap(_.core match {
      case MetaQueryGen.AggCore(_, aggs, _) => aggs.map(_._1)
      case _ => Nil
    })
  }

  private def subtrees(e: Expr): Iterator[Expr] = Iterator.single(e) ++ (e match {
    case CastExpr(c, _) => subtrees(c)
    case DecimalCastExpr(c, _) => subtrees(c)
    case UnaryOpExpr(_, c, _) => subtrees(c)
    case BinOpExpr(_, l, r, _) => subtrees(l) ++ subtrees(r)
    case FuncExpr(_, args, _) => args.iterator.flatMap(subtrees)
    case CaseExpr(branches, elseE, _) => branches.iterator.flatMap { case (c, v) => subtrees(c) ++ subtrees(v) } ++ elseE.iterator.flatMap(subtrees)
    case IsNullExpr(c, _) => subtrees(c)
    case InListExpr(c, items, _) => subtrees(c) ++ items.iterator.flatMap(subtrees)
    case LikeExpr(c, p, _, _) => subtrees(c) ++ subtrees(p)
    case BetweenExpr(c, lo, hi) => subtrees(c) ++ subtrees(lo) ++ subtrees(hi)
    case _ => Iterator.empty
  })

  private def isLeaf(e: Expr): Boolean = e.isInstanceOf[ColRefExpr] || e.isInstanceOf[LitExpr]

  /** Whether some computed node of the query (not a bare column) has an
    * operand of a type `p` takes, or an aggregate takes a value of one. */
  private def computesOn(shape: Shape, p: DataType => Boolean, aggs: Boolean): Boolean =
    exprs(shape).exists(e => subtrees(e).exists(n => !isLeaf(n) && PortableSql.children(n).exists(c => p(PortableSql.typeOf(c))))) ||
      (aggs && aggregates(shape).exists(a => aggArgs(a).exists(x => p(PortableSql.typeOf(x)))))

  private def readsDecimal(shape: Shape): Boolean = computesOn(shape, isDecimal, aggs = true)
  private def readsText(shape: Shape): Boolean = computesOn(shape, _ == DataType.StringType, aggs = false)
  private def readsTemporal(shape: Shape): Boolean = computesOn(shape, isTemporal, aggs = true)

  private def outerJoin(shape: Shape): Boolean = shape match {
    case Shape.Multi(q) => arms(q).exists(_.from.joins.exists(_.kind != JoinKind.Inner))
    case _ => false
  }

  private def setOps(shape: Shape): Boolean = shape match {
    case Shape.Multi(q) =>
      q.ctes.nonEmpty || arms(q).exists(a => a.setOp.isDefined || (a.core match {
        case MetaQueryGen.ProjectCore(_, distinct, _) => distinct
        case MetaQueryGen.AggCore(_, aggs, _) => aggs.exists(x => distinctAgg(x._1))
      }))
    case Shape.Single(pq: QueryGen.ProjectQuery) => pq.distinct
    case Shape.Single(aq: QueryGen.AggregateQuery) => aq.aggregates.exists(x => distinctAgg(x._1))
  }

  private def distinctAgg(a: QueryGen.AggSpec): Boolean = a match {
    case QueryGen.Count(_, d) => d
    case QueryGen.Sum(_, d) => d
    case QueryGen.Avg(_, d) => d
    case _ => false
  }

  private def hasTopN(shape: Shape): Boolean = shape match {
    case Shape.Multi(q) => q.hasTopN
    case Shape.Single(_) => PortableSql.ordersTotally(shape) && (shape match {
      case Shape.Single(pq: QueryGen.ProjectQuery) => pq.limit.isDefined || pq.offset.isDefined
      case Shape.Single(aq: QueryGen.AggregateQuery) => aq.limit.isDefined || aq.offset.isDefined
      case _ => false
    })
  }

  // ---- shared pieces of the queries built here ------------------------------------

  private def col(schema: Schema, i: Int, qualifier: String = ""): ColRefExpr =
    ColRefExpr(i, qualifier + schema.fields(i).name, schema.fields(i).dataType)

  private def lit(v: Any, t: DataType): LitExpr = LitExpr(v, t)

  private def shuffle[A](rng: Rng, xs: Vector[A]): Vector[A] = {
    val buf = scala.collection.mutable.ArrayBuffer.from(xs)
    buf.indices.reverse.foreach { i =>
      val j = rng.between(0, i)
      val tmp = buf(i)
      buf(i) = buf(j)
      buf(j) = tmp
    }
    buf.toVector
  }
  private def int(i: Int): LitExpr = LitExpr(i, DataType.IntType)
  private def bool(op: String, l: Expr, r: Expr): Expr = BinOpExpr(op, l, r, DataType.BooleanType)
  private val RelOps = Seq("<", "<=", ">", ">=", "=", "<>")

  private def where(rng: Rng, p: Double, atom: () => Expr): Option[Expr] =
    if (!rng.bool(p)) None
    else if (rng.bool(0.3)) Some(bool(rng.oneOf(Seq("AND", "OR")), atom(), atom()))
    else Some(atom())

  /** `SELECT <items> FROM t [WHERE ...]`, with items named `p0`, `p1`, ... */
  private def project(items: Seq[Expr], cond: Option[Expr]): Shape =
    Shape.Single(QueryGen.ProjectQuery(distinct = false, items.zipWithIndex.map { case (e, i) => (e, s"p$i") }.toVector,
      cond, Vector.empty))

  private def table(rng: Rng, columns: Seq[(DataType, Double, IndexedSeq[Any])]): DataGen.Dataset = {
    val schema = Schema(columns.zipWithIndex.map { case ((t, _, _), i) => Field(s"c$i", t) }.toVector)
    val n = rng.between(0, 24)
    DataGen.Dataset(schema, (0 until n).map { _ =>
      columns.map { case (_, nulls, domain) => if (rng.bool(nulls)) null else DataGen.fresh(rng.oneOf(domain)) }.toArray[Any]
    })
  }

  // ---- windows -------------------------------------------------------------------

  /** `QueryGen`'s window table, `t(id, g, k, x)` (`id` unique, `g` a
    * partition, `k` an INT, BIGINT, DOUBLE or DECIMAL key with ties, NULLs,
    * and for a DOUBLE NaN and the infinities, `x` a BIGINT), and queries of
    * one or two windows each: an aggregate over a frame as `QueryGen` draws
    * it, `ROW_NUMBER`, `RANK` or `DENSE_RANK`, or `LAG` or `LEAD` with an
    * offset and a default. A window whose answer depends on the order of rows
    * its `ORDER BY` ties (a `ROWS` frame, `ROW_NUMBER`, `LAG`, `LEAD`) orders
    * by `id` after its key. */
  private def window(rng: Rng): Case = {
    val data = QueryGen.generateWindowFrameCase(rng.split()).dataset
    val schema = data.schema
    val keyType = schema.fields(2).dataType
    val leaf = MetaQueryGen.FromLeaf("t", "t", schema.fields.map(f => MetaQueryGen.ScopeCol("t", f.name, f.dataType)))
    val queries = (0 until QueriesPerCase).map { _ =>
      val n = if (rng.bool(0.3)) 2 else 1
      val wins = (0 until n).map(i => windowItem(rng, keyType, s"w$i")).toVector
      val items = Vector((col(schema, 0, "t."): Expr, "id"))
      Shape.Multi(MetaQueryGen.MetaQuery(MetaQueryGen.FromClause(leaf, Vector.empty), None,
        MetaQueryGen.ProjectCore(items, distinct = false, wins))): Shape
    }.toVector
    Case(Vector(Table("t", tidy(data))), queries)
  }

  private def windowItem(rng: Rng, keyType: DataType, name: String): MetaQueryGen.WinItem = {
    val partition = if (rng.bool(0.6)) "PARTITION BY t.g " else ""
    val asc = rng.bool()
    val nullsFirst = rng.weighted[Option[Boolean]]((2, None), (1, Some(true)), (1, Some(false))).getOrElse(asc)
    val key = s"t.k ${if (asc) "ASC" else "DESC"} NULLS ${if (nullsFirst) "FIRST" else "LAST"}"
    val integral = keyType == DataType.IntType || keyType == DataType.LongType
    def item(sql: String, t: DataType) = MetaQueryGen.WinItem(sql, name, t, tieSensitive = false)
    rng.weighted[() => MetaQueryGen.WinItem](
      (6, () => {
        val fn = rng.oneOf(Seq("SUM(t.x)", "COUNT(t.x)", "COUNT(*)", "MIN(t.x)", "MAX(t.x)", "AVG(t.x)"))
        val t = if (fn.startsWith("AVG")) DataType.DoubleType else DataType.LongType
        val ordered = rng.bool(0.85)
        val frame: Option[QueryGen.GenFrame] = rng.weighted[() => Option[QueryGen.GenFrame]](
          (2, () => None),
          (3, () => if (ordered) Some(QueryGen.genFrame(rng, rows = true)) else None),
          (3, () => Some(QueryGen.genFrame(rng, rows = false, offsets = ordered))))()
        // Postgres finds no in_range for an integer key and a fraction.
        val fitted = frame.map { f =>
          def fit(b: QueryGen.GenBound) =
            if (integral && b.offset.scale > 0) b.copy(offset = b.offset.setScale(0, java.math.RoundingMode.DOWN)) else b
          f.copy(start = fit(f.start), end = fit(f.end))
        }
        val order =
          if (!ordered) ""
          else if (fitted.exists(_.rows)) s"ORDER BY $key, t.id ASC "
          else s"ORDER BY $key "
        item(s"$fn OVER ($partition$order${fitted.map(_.sql).getOrElse("")})".replace(" )", ")"), t)
      }),
      (2, () => item(s"ROW_NUMBER() OVER (${partition}ORDER BY $key, t.id ASC)", DataType.LongType)),
      (2, () => item(s"${rng.oneOf(Seq("RANK", "DENSE_RANK"))}() OVER (${partition}ORDER BY $key)", DataType.LongType)),
      (3, () => {
        val fn = rng.oneOf(Seq("LAG", "LEAD"))
        val ofKey = rng.bool(0.3)
        val (arg, t) = if (ofKey) ("t.k", keyType) else ("t.x", DataType.LongType)
        val default: Option[String] =
          if (!rng.bool(0.6)) None
          else if (rng.bool(0.2)) Some("NULL")
          else Some(PortableSql.expr(LitExpr(windowDefault(rng, t), t)))
        val offset = if (default.isDefined || rng.bool(0.5)) Some(rng.between(1, 3)) else None
        val args = (Seq(arg) ++ offset.map(_.toString) ++ default).mkString(", ")
        item(s"$fn($args) OVER (${partition}ORDER BY $key, t.id ASC)", t)
      }))()
  }

  private def windowDefault(rng: Rng, t: DataType): Any = t match {
    case DataType.IntType => rng.oneOf(Seq(0, 1, 100))
    case DataType.LongType => rng.oneOf(Seq(0L, -1L, 100L))
    case DataType.DoubleType => rng.oneOf(Seq(0.0, 0.5, 100.0))
    case DataType.DecimalType(_, s) => new JBigDecimal(rng.oneOf(Seq("0", "0.50", "-1.25"))).setScale(math.min(s, 2))
    case other => throw new IllegalArgumentException(s"no window default of $other")
  }

  // ---- decimals --------------------------------------------------------------------

  private val DecimalColumnTypes: Seq[DataType.DecimalType] =
    Seq(DataType.DecimalType(9, 2), DataType.DecimalType(18, 4), DataType.DecimalType(38, 18))

  /** `t(c0 INT, c1..c3 DECIMAL)` from `DataGen`'s decimal pools, and
    * projections, conditions and aggregates of arithmetic, `ROUND`, `TRUNC`,
    * `ABS` and a CAST between scales over them. */
  private def decimal(rng: Rng): Case = {
    val types = (0 until rng.between(2, 3)).map(_ => rng.oneOf(DecimalColumnTypes))
    val data = table(rng, (DataType.IntType, 0.1, (0 to 3).map(i => i: Any)) +:
      types.map(t => (t: DataType, rng.oneOf(Seq(0.0, 0.15, 0.3)), DataGen.DecimalPools(t).map(v => v: Any))))
    val schema = data.schema
    val decimals = (1 until schema.length).toVector
    def dcol(): Expr = col(schema, rng.oneOf(decimals))
    def operand(depth: Int): Expr = rng.weighted[() => Expr](
      (5, () => dcol()),
      (2, () => col(schema, 0)),
      (2, () => {
        val v = rng.oneOf(Seq("0.07", "0.3333", "3", "2.5", "100", "0.001"))
        val d = new JBigDecimal(v)
        lit(d, DataType.DecimalType(math.max(d.precision, d.scale), d.scale))
      }),
      (2, () => if (depth > 0) arith(depth - 1) else dcol()))()
    def arith(depth: Int): Expr = {
      val (l, r) = if (rng.bool()) (dcol(), operand(depth)) else (operand(depth), dcol())
      BinOpExpr(rng.oneOf(Seq("+", "-", "*", "/", "%")), l, r, DataType.DecimalType(38, 6))
    }
    def value(): Expr = rng.weighted[() => Expr](
      (4, () => arith(rng.between(0, 1))),
      (3, () => FuncExpr(rng.oneOf(Seq("ROUND", "TRUNC")),
        Seq(if (rng.bool(0.6)) dcol() else arith(0), int(rng.between(-1, 4))),
        DataType.DecimalType(38, 6))),
      (1, () => FuncExpr("ROUND", Seq(dcol()), DataType.DecimalType(38, 0))),
      (1, () => FuncExpr("ABS", Seq(arith(0)), DataType.DecimalType(38, 6))),
      (1, () => DecimalCastExpr(rng.oneOf(Seq(dcol(), arith(0))), rng.oneOf(Seq(DataType.DecimalType(10, 1),
        DataType.DecimalType(12, 3), DataType.DecimalType(20, 0), DataType.DecimalType(30, 8))))),
      (1, () => FuncExpr("COALESCE", Seq(dcol(), arith(0)), DataType.DecimalType(38, 6))))()
    def condition(): Expr = rng.weighted[() => Expr](
      (3, () => bool(rng.oneOf(RelOps), value(), operand(0))),
      (2, () => BetweenExpr(dcol(), operand(0), operand(0))),
      (1, () => InListExpr(dcol(), Seq.fill(rng.between(1, 3))(operand(0)), rng.bool(0.3))),
      (1, () => IsNullExpr(arith(0), rng.bool())))()
    val queries = (0 until QueriesPerCase).map { _ =>
      if (rng.bool(0.65)) project(Seq.fill(rng.between(1, 3))(value()), where(rng, 0.3, () => condition()))
      else {
        val aggs = Seq.fill(rng.between(1, 3)) {
          val arg = if (rng.bool(0.6)) dcol() else arith(0)
          rng.oneOf(Seq[QueryGen.AggSpec](QueryGen.Sum(arg, rng.bool(0.2)), QueryGen.Avg(arg, rng.bool(0.2)),
            QueryGen.Min(arg), QueryGen.Max(arg)))
        }.zipWithIndex.map { case (a, i) => (a, s"a$i") }.toVector
        val grouped = rng.bool(0.6)
        val keys = if (grouped) Vector(col(schema, 0)) else Vector.empty
        val order = if (grouped && rng.bool(0.5)) Vector(QueryGen.OrderKey(col(schema, 0), rng.bool())) else Vector.empty
        Shape.Single(QueryGen.AggregateQuery(keys, aggs, where(rng, 0.3, () => condition()), None,
          groupByOrdinals = false, order)): Shape
      }
    }.toVector
    Case(Vector(Table(QueryGen.ViewName, data)), queries)
  }

  // ---- text ------------------------------------------------------------------------

  /** ASCII only: the engine maps case by Unicode's rules, Postgres's C ctype
    * maps ASCII alone, and DuckDB's maps the rest its own way. */
  private val TextPool: IndexedSeq[String] = IndexedSeq("", "a", "b", "A", "ab", "aB", "abc", " a", "a ", " a b ", "  ",
    "a%c", "a_c", "abcdef", "Hello World", "hello", "x'y", "a\\b", "%", "_")
  private val LikePatterns: Seq[String] = Seq("%", "_", "a%", "%a", "%a%", "a_c", "a\\%c", "a\\_c", "%\\%%", "",
    "A%", "%b%", "_b%", "h%o", "% a%", "a\\\\b")

  /** `t(c0 TEXT, c1 TEXT, c2 INT)` from an ASCII pool with spaces, wildcards
    * and a quote, and projections, conditions and aggregates of the string
    * functions, `||` and `LIKE` / `ILIKE` over them. */
  private def text(rng: Rng): Case = {
    val data = table(rng, Seq((DataType.StringType, 0.15, TextPool.map(s => s: Any)),
      (DataType.StringType, 0.15, TextPool.map(s => s: Any)), (DataType.IntType, 0.1, (-1 to 5).map(i => i: Any))))
    val schema = data.schema
    def s(): Expr = rng.weighted[() => Expr]((6, () => col(schema, rng.between(0, 1))),
      (1, () => lit(rng.oneOf(TextPool), DataType.StringType)))()
    def position(): Expr = rng.weighted[() => Expr]((8, () => int(rng.between(1, 5))), (1, () => int(0)),
      (1, () => col(schema, 2)))()
    def str(depth: Int): Expr = {
      def sub(): Expr = if (depth > 0 && rng.bool(0.4)) str(depth - 1) else s()
      rng.weighted[() => Expr](
        (3, () => FuncExpr("TRIM", Seq(sub()), DataType.StringType)),
        (2, () => FuncExpr(rng.oneOf(Seq("UPPER", "LOWER")), Seq(sub()), DataType.StringType)),
        (3, () => FuncExpr("SUBSTRING", if (rng.bool(0.6)) Seq(sub(), position(), int(rng.between(0, 4)))
          else Seq(sub(), position()), DataType.StringType)),
        (2, () => FuncExpr("CONCAT", Seq.fill(rng.between(2, 3))(sub()), DataType.StringType)),
        (3, () => BinOpExpr("||", sub(), sub(), DataType.StringType)))()
    }
    def value(): Expr = rng.weighted[() => Expr](
      (5, () => str(rng.between(0, 2))),
      (2, () => FuncExpr("LENGTH", Seq(if (rng.bool()) s() else str(0)), DataType.IntType)),
      (1, () => CaseExpr(Seq((like(), str(0))), Some(s()), DataType.StringType)))()
    def like(): Expr = LikeExpr(if (rng.bool(0.7)) s() else str(0), lit(rng.oneOf(LikePatterns), DataType.StringType),
      rng.bool(0.3), LikeSyntax(caseInsensitive = rng.bool(0.25)))
    def condition(): Expr = rng.weighted[() => Expr](
      (4, () => like()),
      (2, () => bool(rng.oneOf(RelOps), if (rng.bool()) s() else str(0), s())),
      (1, () => bool(rng.oneOf(RelOps), FuncExpr("LENGTH", Seq(str(0)), DataType.IntType), int(rng.between(0, 4)))),
      (1, () => InListExpr(str(0), Seq.fill(rng.between(1, 3))(lit(rng.oneOf(TextPool), DataType.StringType)), rng.bool(0.3))))()
    val queries = (0 until QueriesPerCase).map { _ =>
      if (rng.bool(0.75)) project(Seq.fill(rng.between(1, 3))(value()), where(rng, 0.4, () => condition()))
      else {
        val aggs = Vector[(QueryGen.AggSpec, String)](
          (rng.oneOf(Seq[QueryGen.AggSpec](QueryGen.Count(str(0), distinct = rng.bool()), QueryGen.Min(str(0)),
            QueryGen.Max(s()), QueryGen.CountIf(like()))), "a0"))
        Shape.Single(QueryGen.AggregateQuery(Vector(col(schema, 2)), aggs, where(rng, 0.3, () => condition()), None,
          groupByOrdinals = false, Vector.empty)): Shape
      }
    }.toVector
    Case(Vector(Table(QueryGen.ViewName, data)), queries)
  }

  // ---- dates and timestamps ------------------------------------------------------

  /** ISO dates and timestamps as text, which a CAST reads in all three. */
  private val TemporalTexts: IndexedSeq[String] = IndexedSeq("2024-02-29", "1970-01-01", "1969-12-31", "2024-03-10",
    "2024-03-10 07:00:00", "2024-03-10 06:59:59.999999", "2024-02-29 12:00:00", "1970-01-01 00:00:00.000001")

  /** `t(c0 DATE, c1 TIMESTAMP, c2 INT, c3 TEXT)` from `ExprGen`'s pools, `c3`
    * ISO text, and comparisons, `BETWEEN`, `IN`, casts between the two and to
    * and from text, `MIN`, `MAX`, grouping and a top-n over them. */
  private def temporal(rng: Rng): Case = {
    val data = table(rng, Seq((DataType.DateType, 0.15, ExprGen.DatePool.toIndexedSeq),
      (DataType.TimestampType, 0.15, ExprGen.TimestampPool.toIndexedSeq), (DataType.IntType, 0.1, (0 to 3).map(i => i: Any)),
      (DataType.StringType, 0.15, TemporalTexts.map(s => s: Any))))
    val schema = data.schema
    def date(): Expr = rng.weighted[() => Expr]((5, () => col(schema, 0)), (2, () => lit(rng.oneOf(ExprGen.DatePool), DataType.DateType)),
      (2, () => CastExpr(col(schema, 1), DataType.DateType)),
      (1, () => CastExpr(lit(rng.oneOf(TemporalTexts.filter(_.length == 10)), DataType.StringType), DataType.DateType)))()
    def ts(): Expr = rng.weighted[() => Expr]((5, () => col(schema, 1)), (2, () => lit(rng.oneOf(ExprGen.TimestampPool), DataType.TimestampType)),
      (2, () => CastExpr(col(schema, 0), DataType.TimestampType)),
      (1, () => CastExpr(col(schema, 3), DataType.TimestampType)))()
    def textOf(): Expr = rng.weighted[() => Expr]((3, () => CastExpr(date(), DataType.StringType)),
      (1, () => CastExpr(ts(), DataType.StringType)))()
    def condition(): Expr = rng.weighted[() => Expr](
      (3, () => bool(rng.oneOf(RelOps), date(), if (rng.bool(0.3)) lit(rng.oneOf(ExprGen.DatePool).toString, DataType.StringType) else date())),
      (3, () => bool(rng.oneOf(RelOps), ts(), ts())),
      (2, () => BetweenExpr(if (rng.bool()) date() else col(schema, 0), date(), date())),
      (1, () => BetweenExpr(ts(), ts(), ts())),
      (2, () => InListExpr(col(schema, 0), Seq.fill(rng.between(1, 3))(
        if (rng.bool(0.3)) lit(rng.oneOf(ExprGen.DatePool).toString, DataType.StringType) else lit(rng.oneOf(ExprGen.DatePool), DataType.DateType)),
        rng.bool(0.3))),
      (1, () => IsNullExpr(if (rng.bool()) date() else ts(), rng.bool())))()
    def value(): Expr = rng.weighted[() => Expr]((3, () => date()), (3, () => ts()), (2, () => textOf()),
      (1, () => FuncExpr("COALESCE", Seq(col(schema, 0), date()), DataType.DateType)),
      (1, () => CaseExpr(Seq((condition(), ts())), Some(ts()), DataType.TimestampType)))()
    val queries = (0 until QueriesPerCase).map { _ =>
      rng.weighted[() => Shape](
        (5, () => project(Seq.fill(rng.between(1, 3))(value()), where(rng, 0.5, () => condition()))),
        (2, () => {
          val keyIdx = rng.oneOf(Seq(0, 1, 2))
          val aggs = Vector[(QueryGen.AggSpec, String)]((QueryGen.CountStar, "a0"),
            (rng.oneOf(Seq[QueryGen.AggSpec](QueryGen.Min(col(schema, 0)), QueryGen.Max(col(schema, 1)),
              QueryGen.Min(ts()), QueryGen.Max(date()), QueryGen.Count(col(schema, 1), distinct = true))), "a1"))
          val order = if (rng.bool(0.5)) Vector(QueryGen.OrderKey(col(schema, keyIdx), rng.bool(),
            rng.weighted[Option[Boolean]]((2, None), (1, Some(true)), (1, Some(false))))) else Vector.empty
          Shape.Single(QueryGen.AggregateQuery(Vector(col(schema, keyIdx)), aggs, where(rng, 0.3, () => condition()), None,
            groupByOrdinals = false, order))
        }),
        (2, () => {
          val cols = Vector(0, 1, 2).filter(_ => rng.bool(0.7)) match {
            case v if v.isEmpty => Vector(rng.oneOf(Seq(0, 1)))
            case v => v
          }
          val keys = cols.map(i => QueryGen.OrderKey(col(schema, i), rng.bool(),
            rng.weighted[Option[Boolean]]((2, None), (1, Some(true)), (1, Some(false)))))
          val (limit, offset) = rng.weighted[(Option[Long], Option[Long])]((3, (Some(rng.between(0, 6).toLong), None)),
            (2, (Some(rng.between(1, 6).toLong), Some(rng.between(0, 4).toLong))), (1, (None, Some(rng.between(1, 4).toLong))))
          Shape.Single(QueryGen.ProjectQuery(distinct = rng.bool(0.3), cols.map(i => (col(schema, i): Expr, schema.fields(i).name)),
            where(rng, 0.3, () => condition()), shuffle(rng, keys), limit, offset))
        }))()
    }.toVector
    Case(Vector(Table(QueryGen.ViewName, data)), queries)
  }

  // ---- top-n over every type --------------------------------------------------------

  /** A `DataGen` table of any types, and `SELECT <columns> ... ORDER BY`
    * every one of them, each its own direction and NULL placement, with a
    * `LIMIT`, an `OFFSET` or both: a float key with its NaN and infinities, a
    * `BOOLEAN`, a `BINARY` and every other type `QueryGen`'s top-n leaves
    * out. */
  private def topN(rng: Rng): Case = {
    val data = tidy(DataGen.generate(rng.split()))
    val schema = data.schema
    val queries = (0 until QueriesPerCase).map { _ =>
      val cols = (0 until schema.length).filter(_ => rng.bool(0.7)).toVector match {
        case v if v.isEmpty => Vector(rng.between(0, schema.length - 1))
        case v => shuffle(rng, v)
      }
      val keys = cols.map(i => QueryGen.OrderKey(col(schema, i), rng.bool(),
        rng.weighted[Option[Boolean]]((2, None), (1, Some(true)), (1, Some(false)))))
      val (limit, offset) = rng.weighted[(Option[Long], Option[Long])]((3, (Some(rng.between(0, 8).toLong), None)),
        (3, (Some(rng.between(1, 8).toLong), Some(rng.between(0, 5).toLong))), (1, (None, Some(rng.between(1, 6).toLong))))
      Shape.Single(QueryGen.ProjectQuery(distinct = rng.bool(0.2), cols.map(i => (col(schema, i): Expr, schema.fields(i).name)),
        None, shuffle(rng, keys), limit, offset)): Shape
    }.toVector
    Case(Vector(Table(QueryGen.ViewName, data)), queries)
  }
}
