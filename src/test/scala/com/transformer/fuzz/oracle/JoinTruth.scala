package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, DataType, ExecutionOptions, Field, Schema}
import com.transformer.fuzz.{DataGen, Rng, RowOracle}
import com.transformer.sql.plan.JoinKind

import scala.collection.mutable

/** Known join answers (TQS, SIGMOD 2023): a wide table split into tables that
  * join back into it, so the answer to a join query over them is known
  * without the engine.
  *
  * '''Data.''' A wide table `W` holds, for each fact `id`, a measure and a
  * key into each dimension, and every attribute of the dimension row it keys,
  * a functional dependency on that key: so `W` splits along the keys into a
  * fact table and dimension tables ([[Split]]), and a dimension may key another
  * (a snowflake, `F -> D1 -> D2`). Some keys are NULL, some name no dimension
  * row, and some dimension rows no fact keys, all on purpose: those are the
  * rows an outer join pads.
  *
  * '''Queries.''' A walk over the schema graph ([[TruthQuery]]): tables joined
  * along their keys, each join INNER, LEFT, RIGHT or FULL; a WHERE of
  * comparisons of a column with a literal, IS NULL, AND, OR and NOT; and either
  * a projection of columns or COUNT(*), COUNT, SUM, MIN and MAX grouped by one
  * column.
  *
  * '''Expected answers.''' [[expected]] works each one out from the tables by
  * itself: a join along a key pairs the rows whose keys are equal and not
  * NULL, an outer join adds the rows of its preserved side that met none,
  * padded with NULLs; a WHERE keeps the rows its three-valued condition is
  * TRUE for; a SUM is added in `BigInt`. It is a second implementation of
  * join semantics, so it stays to joins along keys, comparisons with
  * literals and these four aggregates. The engine's answer must equal it as
  * a multiset.
  */
object JoinTruth {

  // ---------------------------------------------------------------------------
  // Tables
  // ---------------------------------------------------------------------------

  final case class Table(name: String, columns: Vector[(String, DataType)], rows: Vector[Vector[Any]]) {
    def schema: Schema = Schema(columns.map { case (n, t) => Field(n, t) })
    def index(column: String): Int = columns.indexWhere(_._1 == column)
  }

  /** `from.column` holds keys of `to`'s key column `to.key`. */
  final case class Edge(from: String, column: String, to: String, key: String)

  /** The fact table and the dimension tables `W` splits into, and the keys
    * between them. */
  final case class Split(tables: Vector[Table], edges: Vector[Edge]) {
    def table(name: String): Table = tables.find(_.name == name).get
    def catalog(layout: DataGen.Dataset => com.transformer.core.MaterializedView = _.evenPartitions(2, 3)): Catalog = {
      val c = new Catalog
      tables.foreach(t => c.register(t.name, layout(DataGen.Dataset(t.schema, t.rows.map(_.toArray[Any])))))
      c
    }
    override def toString: String = tables.map { t =>
      s"${t.name}(${t.columns.map(_._1).mkString(", ")}): " + t.rows.map(_.map(v => if (v == null) "NULL" else v.toString)
        .mkString("(", ", ", ")")).mkString(" ")
    }.mkString("\n    ")
  }

  private val Words: Vector[String] = Vector("a", "b", "c", "d", "e", "ab", "ba")

  /** A wide table and its split: one fact table `f` and one to three
    * dimensions, each keyed by the fact or by the dimension before it. */
  def genSplit(rng: Rng): Split = {
    val dims = rng.between(1, 3)
    val edges = mutable.ArrayBuffer.empty[Edge]
    val tables = mutable.ArrayBuffer.empty[Table]
    // Build the dimensions last first, so a dimension's key into another can
    // read that one's keys.
    val dimKeys = mutable.Map.empty[String, Vector[Int]]
    val parentOf = (1 to dims).map { j => j -> (if (j > 1 && rng.bool(0.4)) s"d${j - 1}" else "f") }.toMap
    def keyInto(target: String): Any = {
      val keys = dimKeys(target)
      rng.weighted[() => Any](
        (2, () => null), // no key
        (2, () => keys.lastOption.getOrElse(0) + 1 + rng.between(0, 2)), // a key no row has
        (12, () => if (keys.isEmpty) null else rng.oneOf(keys.take(math.max(1, keys.length - rng.between(0, 2))))))()
    }
    (dims to 1 by -1).foreach { j =>
      val name = s"d$j"
      val n = rng.between(0, 6)
      val keys = (1 to n).toVector
      dimKeys(name) = keys
      val children = (j + 1 to dims).filter(c => parentOf(c) == name)
      val cols = Vector(s"k$j" -> DataType.IntType, s"a$j" -> DataType.IntType, s"s$j" -> DataType.StringType) ++
        children.map(c => s"k$c" -> DataType.IntType)
      val rows = keys.map { k =>
        Vector[Any](k, if (rng.bool(0.15)) null else rng.between(-2, 4), if (rng.bool(0.15)) null else rng.oneOf(Words)) ++
          children.map(c => keyInto(s"d$c"))
      }
      tables.prepend(Table(name, cols, rows))
      children.foreach(c => edges += Edge(name, s"k$c", s"d$c", s"k$c"))
    }
    val factDims = (1 to dims).filter(j => parentOf(j) == "f")
    val factCols = Vector("id" -> DataType.IntType, "m" -> DataType.IntType, "t" -> DataType.StringType) ++
      factDims.map(j => s"k$j" -> DataType.IntType)
    val factRows = (1 to rng.between(0, 12)).toVector.map { id =>
      Vector[Any](id, if (rng.bool(0.15)) null else rng.between(-5, 9), if (rng.bool(0.15)) null else rng.oneOf(Words)) ++
        factDims.map(j => keyInto(s"d$j"))
    }
    factDims.foreach(j => edges += Edge("f", s"k$j", s"d$j", s"k$j"))
    Split((Table("f", factCols, factRows) +: tables).toVector, edges.toVector)
  }

  // ---------------------------------------------------------------------------
  // Queries
  // ---------------------------------------------------------------------------

  /** A column of a FROM item, by its alias. */
  final case class Col(alias: String, column: String, dataType: DataType) {
    def sql: String = s"$alias.$column"
  }

  /** `<kind> JOIN <table> AS <alias> ON left = right`, `right` a column of
    * the table this join adds. */
  final case class JoinStep(kind: JoinKind, table: String, alias: String, left: Col, right: Col)

  sealed trait Pred { def sql: String }
  final case class Cmp(col: Col, op: String, literal: Any) extends Pred {
    def sql: String = s"${col.sql} $op ${literalSql(literal)}"
  }
  final case class IsNull(col: Col, negated: Boolean) extends Pred {
    def sql: String = s"${col.sql} IS ${if (negated) "NOT " else ""}NULL"
  }
  final case class And(a: Pred, b: Pred) extends Pred { def sql: String = s"(${a.sql} AND ${b.sql})" }
  final case class Or(a: Pred, b: Pred) extends Pred { def sql: String = s"(${a.sql} OR ${b.sql})" }
  final case class Not(p: Pred) extends Pred { def sql: String = s"NOT (${p.sql})" }

  sealed trait Agg { def sql: String; def dataType: DataType }
  case object CountStar extends Agg { def sql = "COUNT(*)"; def dataType: DataType = DataType.LongType }
  final case class Count(col: Col) extends Agg { def sql = s"COUNT(${col.sql})"; def dataType: DataType = DataType.LongType }
  final case class Sum(col: Col) extends Agg { def sql = s"SUM(${col.sql})"; def dataType: DataType = DataType.LongType }
  final case class Min(col: Col) extends Agg { def sql = s"MIN(${col.sql})"; def dataType: DataType = col.dataType }
  final case class Max(col: Col) extends Agg { def sql = s"MAX(${col.sql})"; def dataType: DataType = col.dataType }

  sealed trait Output
  final case class Projection(cols: Vector[Col]) extends Output
  final case class Grouped(key: Col, aggs: Vector[Agg]) extends Output

  final case class TruthQuery(rootTable: String, rootAlias: String, joins: Vector[JoinStep], where: Option[Pred],
      output: Output) {
    def sql: String = {
      val select = output match {
        case Projection(cols) => cols.zipWithIndex.map { case (c, i) => s"${c.sql} AS o$i" }.mkString(", ")
        case Grouped(key, aggs) => (s"${key.sql} AS g" +: aggs.zipWithIndex.map { case (a, i) => s"${a.sql} AS o$i" }).mkString(", ")
      }
      val from = s"$rootTable AS $rootAlias" + joins.map { j =>
        s" ${kindSql(j.kind)} JOIN ${j.table} AS ${j.alias} ON ${j.left.sql} = ${j.right.sql}"
      }.mkString
      val group = output match {
        case Grouped(key, _) => s" GROUP BY ${key.sql}"
        case _ => ""
      }
      s"SELECT $select FROM $from${where.map(" WHERE " + _.sql).getOrElse("")}$group"
    }
    def tableCount: Int = 1 + joins.length
  }

  private def kindSql(k: JoinKind): String = k match {
    case JoinKind.Inner => "INNER"
    case JoinKind.Left => "LEFT"
    case JoinKind.Right => "RIGHT"
    case JoinKind.Full => "FULL"
  }

  private def literalSql(v: Any): String = v match {
    case s: String => "'" + s + "'"
    case other => other.toString
  }

  /** A split and about five queries over it. */
  final case class TruthCase(split: Split, queries: Vector[TruthQuery]) {
    override def toString: String =
      s"TruthCase(\n  ${queries.map(_.sql).mkString("\n  ")}\n  tables:\n    $split)"
  }

  def generate(rng: Rng): TruthCase = {
    val split = genSplit(rng.split())
    val qRng = rng.split()
    TruthCase(split, Vector.fill(qRng.between(4, 6))(genQuery(qRng, split)))
  }

  private def colsOf(split: Split, table: String, alias: String): Vector[Col] =
    split.table(table).columns.map { case (n, t) => Col(alias, n, t) }

  /** A walk from a table along the keys: each step adds a table one key
    * joins to the tables before it, by any kind of join. */
  def genQuery(rng: Rng, split: Split): TruthQuery = {
    val names = split.tables.map(_.name)
    val root = rng.oneOf(names)
    var joined = Vector(root)
    val steps = Vector.newBuilder[JoinStep]
    val target = rng.between(1, names.length)
    var stop = false
    while (joined.length < target && !stop) {
      val options = split.edges.collect {
        case e if joined.contains(e.from) && !joined.contains(e.to) =>
          (e.to, Col(alias(e.from), e.column, DataType.IntType), Col(alias(e.to), e.key, DataType.IntType))
        case e if joined.contains(e.to) && !joined.contains(e.from) =>
          (e.from, Col(alias(e.to), e.key, DataType.IntType), Col(alias(e.from), e.column, DataType.IntType))
      }
      if (options.isEmpty) stop = true
      else {
        val (table, left, right) = rng.oneOf(options)
        val kind = rng.weighted((3, JoinKind.Inner), (3, JoinKind.Left), (2, JoinKind.Right), (2, JoinKind.Full))
        steps += JoinStep(kind, table, alias(table), left, right)
        joined :+= table
      }
    }
    val scope = joined.flatMap(t => colsOf(split, t, alias(t)))
    val where = if (rng.bool(0.6)) Some(genPred(rng, split, scope, rng.between(1, 2))) else None
    val output =
      if (rng.bool(0.5)) Projection(Vector.fill(rng.between(1, 4))(rng.oneOf(scope)))
      else {
        val ints = scope.filter(_.dataType == DataType.IntType)
        def agg(): Agg = rng.weighted[() => Agg](
          (2, () => CountStar), (2, () => Count(rng.oneOf(scope))), (2, () => Sum(rng.oneOf(ints))),
          (1, () => Min(rng.oneOf(scope))), (1, () => Max(rng.oneOf(scope))))()
        Grouped(rng.oneOf(scope.filterNot(c => c.alias == "f" && c.column == "id")), Vector.fill(rng.between(1, 3))(agg()))
      }
    TruthQuery(root, alias(root), steps.result(), where, output)
  }

  private def alias(table: String): String = table

  /** A condition over `scope`: comparisons of a column with a literal its
    * values sit around, IS NULL, AND, OR and NOT. */
  private def genPred(rng: Rng, split: Split, scope: Vector[Col], depth: Int): Pred =
    if (depth <= 0 || rng.bool(0.5)) {
      val c = rng.oneOf(scope)
      if (rng.bool(0.2)) IsNull(c, rng.bool())
      else {
        val values = split.table(c.alias).rows.map(_(split.table(c.alias).index(c.column))).filter(_ != null)
        val literal: Any =
          if (values.nonEmpty && rng.bool(0.7)) rng.oneOf(values)
          else if (c.dataType == DataType.StringType) rng.oneOf(Words) else rng.between(-3, 7)
        Cmp(c, rng.oneOf(Seq("=", "<>", "<", "<=", ">", ">=")), literal)
      }
    } else rng.weighted[() => Pred](
      (2, () => And(genPred(rng, split, scope, depth - 1), genPred(rng, split, scope, depth - 1))),
      (2, () => Or(genPred(rng, split, scope, depth - 1), genPred(rng, split, scope, depth - 1))),
      (1, () => Not(genPred(rng, split, scope, depth - 1))))()

  // ---------------------------------------------------------------------------
  // The expected answer
  // ---------------------------------------------------------------------------

  /** What the joins of a query paired: for each join, whether rows of its
    * left (the tables before it) and of its right met no row and were
    * padded, and whether a key it read was NULL. */
  final case class JoinFacts(kind: JoinKind, leftPadded: Boolean, rightPadded: Boolean, nullKey: Boolean)

  /** The rows `q` returns over `split`, with their types, and what its joins
    * paired. */
  def expected(split: Split, q: TruthQuery): (Vector[DataType], Vector[Array[Any]], Vector[JoinFacts]) = {
    var cols: Vector[Col] = colsOf(split, q.rootTable, q.rootAlias)
    var rows: Vector[Vector[Any]] = split.table(q.rootTable).rows
    val facts = Vector.newBuilder[JoinFacts]
    q.joins.foreach { j =>
      val rightCols = colsOf(split, j.table, j.alias)
      val rightRows = split.table(j.table).rows
      val li = cols.indexWhere(c => c.alias == j.left.alias && c.column == j.left.column)
      val ri = rightCols.indexWhere(_.column == j.right.column)
      val matchedRight = mutable.BitSet.empty
      var leftPadded, nullKey = false
      val out = Vector.newBuilder[Vector[Any]]
      rows.foreach { l =>
        val lk = l(li)
        if (lk == null) nullKey = true
        var met = false
        rightRows.indices.foreach { r =>
          val rk = rightRows(r)(ri)
          if (lk != null && rk != null && lk == rk) { met = true; matchedRight += r; out += l ++ rightRows(r) }
        }
        if (!met && (j.kind == JoinKind.Left || j.kind == JoinKind.Full)) {
          leftPadded = true
          out += l ++ Vector.fill(rightCols.length)(null)
        }
      }
      var rightPadded = false
      if (j.kind == JoinKind.Right || j.kind == JoinKind.Full) rightRows.indices.foreach { r =>
        if (rightRows(r)(ri) == null) nullKey = true
        if (!matchedRight(r)) { rightPadded = true; out += Vector.fill(cols.length)(null) ++ rightRows(r) }
      }
      facts += JoinFacts(j.kind, leftPadded, rightPadded, nullKey)
      cols = cols ++ rightCols
      rows = out.result()
    }
    def at(c: Col): Int = cols.indexWhere(x => x.alias == c.alias && x.column == c.column)
    val kept = q.where.fold(rows)(p => rows.filter(r => truth(p, r, at).contains(true)))
    val (types, result) = q.output match {
      case Projection(out) => (out.map(_.dataType), kept.map(r => out.map(c => r(at(c))).toArray[Any]))
      case Grouped(key, aggs) =>
        val groups = mutable.LinkedHashMap.empty[Any, mutable.ArrayBuffer[Vector[Any]]]
        kept.foreach(r => groups.getOrElseUpdate(r(at(key)), mutable.ArrayBuffer.empty) += r)
        (key.dataType +: aggs.map(_.dataType), groups.toVector.map { case (k, rs) =>
          (k +: aggs.map(a => aggregate(a, rs.toVector, at))).toArray[Any]
        })
    }
    (types, result, facts.result())
  }

  /** `p` over `row` in three-valued logic: `None` is UNKNOWN. */
  private def truth(p: Pred, row: Vector[Any], at: Col => Int): Option[Boolean] = p match {
    case IsNull(c, negated) => Some((row(at(c)) == null) != negated)
    case Cmp(c, op, literal) =>
      row(at(c)) match {
        case null => None
        case v =>
          val cmp = (v, literal) match {
            case (a: Int, b: Int) => Integer.compare(a, b)
            case (a: String, b: String) => a.compareTo(b)
            case (a, b) => throw new IllegalStateException(s"no comparison of $a and $b")
          }
          Some(op match {
            case "=" => cmp == 0
            case "<>" => cmp != 0
            case "<" => cmp < 0
            case "<=" => cmp <= 0
            case ">" => cmp > 0
            case ">=" => cmp >= 0
          })
      }
    case And(a, b) => (truth(a, row, at), truth(b, row, at)) match {
      case (Some(false), _) | (_, Some(false)) => Some(false)
      case (Some(true), Some(true)) => Some(true)
      case _ => None
    }
    case Or(a, b) => (truth(a, row, at), truth(b, row, at)) match {
      case (Some(true), _) | (_, Some(true)) => Some(true)
      case (Some(false), Some(false)) => Some(false)
      case _ => None
    }
    case Not(x) => truth(x, row, at).map(!_)
  }

  private def aggregate(a: Agg, rows: Vector[Vector[Any]], at: Col => Int): Any = {
    def values(c: Col) = rows.map(_(at(c))).filter(_ != null)
    def ordered(c: Col)(pick: (Any, Any) => Boolean): Any = values(c).reduceOption((x, y) => if (pick(x, y)) x else y).orNull
    def less(x: Any, y: Any): Boolean = (x, y) match {
      case (i: Int, j: Int) => i < j
      case (s: String, t: String) => s.compareTo(t) < 0
      case _ => throw new IllegalStateException(s"no order of $x and $y")
    }
    a match {
      case CountStar => rows.length.toLong
      case Count(c) => values(c).length.toLong
      case Sum(c) =>
        val vs = values(c)
        if (vs.isEmpty) null else vs.foldLeft(BigInt(0))((s, v) => s + v.asInstanceOf[Int]).toLong
      case Min(c) => ordered(c)((x, y) => less(x, y) || x == y)
      case Max(c) => ordered(c)((x, y) => less(y, x) || x == y)
    }
  }

  // ---------------------------------------------------------------------------
  // The relation
  // ---------------------------------------------------------------------------

  /** What one query checked: how many tables it joined and what its joins
    * paired, and how many rows the answer held. */
  final case class Checked(tables: Int, facts: Vector[JoinFacts], rows: Int)

  /** Run every query of `tc` and hold its answer to [[expected]]. An
    * [[AssertionError]] names the first query that answers otherwise. */
  def check(tc: TruthCase, opts: ExecutionOptions = ExecutionOptions.Default): Vector[Checked] = {
    val catalog = tc.split.catalog()
    tc.queries.map { q =>
      val (types, want, facts) = expected(tc.split, q)
      val got =
        try RelEngine.runRows(catalog, q.sql, opts)
        catch {
          case scala.util.control.NonFatal(e) =>
            throw new AssertionError(s"[join-truth] the engine refuses or fails: $e\n  query: ${q.sql}", e)
        }
      val schema = Schema(types.zipWithIndex.map { case (t, i) => Field(s"c$i", t) })
      RowOracle.multisetEquals(schema, want, got).foreach { diff =>
        throw new AssertionError(s"[join-truth] the engine's answer is not the known one.\n  query: ${q.sql}\n$diff")
      }
      Checked(q.tableCount, facts, want.length)
    }
  }
}
