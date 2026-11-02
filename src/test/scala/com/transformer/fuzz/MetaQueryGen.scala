package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.plan._

/** Generator of a multi-relation `SELECT` over a small environment of generated
  * relations, for the metamorphic oracles (`oracle/Tlp`, `oracle/NoRec`,
  * `oracle/MetaModeDifferential`).
  *
  * Where [[QueryGen]] (Plan 02) generates a single-table query for the
  * mode-differential property, this generator reaches the planner's hardest
  * transforms — equi-joins of every kind, including self-joins — so the
  * metamorphic relations bite where the subtlest semantic bugs live (LEFT/RIGHT/
  * FULL null-extension, 3VL join keys).
  *
  * Three invariants make a generated query a fair oracle input:
  *
  *   - '''Scope-correct by construction.''' The FROM scope grows left-to-right as
  *     joins are added; every generated column reference is drawn from the scope
  *     in effect at that point, so a reference always resolves. Columns render
  *     '''alias-qualified''' (`t0.c0`) so a self-join's colliding names stay
  *     unambiguous — the qualifier rides in the [[ColRefExpr]] `name` (its
  *     `index` is unused; the engine re-binds from the rendered text).
  *
  *   - '''Type-correct by construction.''' Equi-join keys are drawn same-typed;
  *     arithmetic operands are numeric, `||` operands string, comparisons
  *     same-category — the same discipline [[QueryGen]] uses so a precedence-only
  *     reparse stays type-correct. Every one of the ten types is a column type
  *     and a join-key type; a DATE, TIMESTAMP or BINARY meets only its own type
  *     and does no arithmetic, and a DECIMAL meets decimals and integer columns
  *     or literals, in a comparison or in arithmetic.
  *
  *   - '''Total + paren-free.''' Nothing here builds a tree that throws for
  *     in-range input, and expressions render without grouping parens but for
  *     decimal arithmetic, which takes the ones its shape needs (`SqlRender`).
  *     So a query that binds also runs, in every mode.
  *
  * Excluded so the output multiset is well-defined for the metamorphic relations:
  * `RAND()` and the clock functions, which are mode-dependent. `LIMIT` and
  * `OFFSET` ARE generated, but only in the '''top-n''' shape — a bare,
  * type-reliable projection ordered by every one of its columns (see
  * [[MetaQuery.totalOrderKeys]]). Rows tied at a cutoff are then identical rows,
  * so the kept multiset is the same under every layout, every build-side swap,
  * and in each of TLP's four separate executions; the one tie that is not two
  * identical values, -0.0 beside 0.0 in a float key, `RowOracle` compares as
  * equal, as it does every float column. The relations share a narrow
  * join-key domain so equi-joins actually match — wide-random keys would make
  * every outer join all-unmatched and hide the null-extension bugs the oracles
  * target.
  */
object MetaQueryGen {

  // ---- relation environment ----------------------------------------------

  /** One generated base relation: a name (its catalog key) plus the column
    * indices eligible as equi-join keys (drawn from the env's shared key domain,
    * so cross-relation joins collide). */
  final case class Relation(name: String, dataset: DataGen.Dataset, keyCols: Vector[Int]) {
    def schema: Schema = dataset.schema
    def asSource: FromSource = FromSource(name, schema.fields.toVector, keyCols)
  }

  /** Something a FROM clause can scan by name: a base relation or a CTE. Carries
    * its column fields and the indices eligible as equi-join keys (key-typed
    * columns), so the generator can build matching equi-joins regardless of
    * whether the leaf is a table or a CTE. */
  final case class FromSource(name: String, fields: Vector[Field], keyCols: Vector[Int])

  /** A `WITH` common table expression: a name and a body query over the base
    * relations. Referenced from the outer FROM by name (a CTE referenced twice is
    * materialized once by the engine; referenced once it is inlined). `keyCols`
    * are the body's key-typed output columns, so a CTE is joinable like a table. */
  final case class CteDef(name: String, body: MetaQuery, keyCols: Vector[Int]) {
    def fields: Vector[Field] = body.outputSchema.map { case (n, t) => Field(n, t) }
    def asSource: FromSource = FromSource(name, fields, keyCols)
    def render: String = s"$name AS (${body.renderBody})"
  }

  /** A handful of relations sharing a join-key type + domain. Registered into a
    * fresh [[Catalog]] per execution; [[reshape]] lays every relation out under a
    * chosen partition/batch layout so the mode-agreement oracle can replay the
    * same rows through different physical shapes. */
  final case class RelEnv(relations: Vector[Relation]) {
    def relation(name: String): Relation = relations.find(_.name == name).get

    /** Register every relation as a single-partition view. */
    def catalog(): Catalog = catalog(_.singlePartition())

    /** Register every relation, laid out by `layout`. */
    def catalog(layout: DataGen.Dataset => MaterializedView): Catalog = {
      val c = new Catalog
      relations.foreach(r => c.register(r.name, layout(r.dataset)))
      c
    }

    override def toString: String =
      relations.map(r => s"${r.name}${r.dataset.toString.stripPrefix("Dataset")}").mkString("RelEnv(", "; ", ")")
  }

  // ---- FROM scope ---------------------------------------------------------

  /** A column visible in the FROM scope. Renders alias-qualified so a self-join's
    * duplicate column names stay distinct. */
  final case class ScopeCol(alias: String, col: String, dataType: DataType) {
    def qualified: String = s"$alias.$col"
    /** A bound-shaped reference whose `name` carries the qualifier; the engine
      * re-binds from the rendered SQL, so the `index` is never read. */
    def ref: Expr = ColRefExpr(0, qualified, dataType)
  }

  // ---- FROM clause (left-deep joins) -------------------------------------

  /** A relation occurrence in FROM: its catalog name, the alias it is bound under
    * here, and its columns projected into the scope under that alias. */
  final case class FromLeaf(relName: String, alias: String, cols: Vector[ScopeCol]) {
    def render: String = s"$relName AS $alias"
  }

  /** One join step: `<KIND> JOIN <leaf> ON <pred>`. Left-deep, matching how the
    * binder folds `ps.getJoins` into a chain of [[LogicalJoin]]s. Its condition
    * reads only this leaf and the ones before it, whichever [[JoinStyle]]
    * writes it. */
  final case class JoinItem(kind: JoinKind, leaf: FromLeaf, on: Expr)

  /** How a FROM writes its joins: `JOIN ... ON` of any kind; or a comma list or
    * `CROSS JOIN`s, inner joins whose conditions sit in the WHERE, where
    * `FilterPushdown` moves each to the first join that has every table it
    * reads and `JoinOrder` orders the tables (plans/testing/05). */
  sealed abstract class JoinStyle(val name: String) { override def toString: String = name }
  object JoinStyle {
    case object On extends JoinStyle("JOIN ... ON")
    case object Comma extends JoinStyle("a comma list")
    case object Cross extends JoinStyle("CROSS JOIN")
  }

  final case class FromClause(root: FromLeaf, joins: Vector[JoinItem], style: JoinStyle = JoinStyle.On) {
    /** Columns in scope at the end of the FROM clause (root then each join leaf,
      * left-to-right). */
    def scope: Vector[ScopeCol] = root.cols ++ joins.flatMap(_.leaf.cols)

    def render: String = {
      val sb = new StringBuilder(root.render)
      joins.foreach { j =>
        style match {
          case JoinStyle.On =>
            sb.append(' ').append(joinKeyword(j.kind)).append(" JOIN ").append(j.leaf.render)
              .append(" ON ").append(SqlRender.expr(j.on))
          case JoinStyle.Comma => sb.append(", ").append(j.leaf.render)
          case JoinStyle.Cross => sb.append(" CROSS JOIN ").append(j.leaf.render)
        }
      }
      sb.toString
    }

    /** The conditions a comma list or `CROSS JOIN`s write in the WHERE: each
      * join's, but where it is TRUE. */
    def whereConditions: Vector[Expr] =
      if (style == JoinStyle.On) Vector.empty else joins.map(_.on).filterNot(_ == TrueLit)
  }

  private val TrueLit: Expr = LitExpr(true, DataType.BooleanType)

  private def joinKeyword(k: JoinKind): String = k match {
    case JoinKind.Inner => "INNER"
    case JoinKind.Left => "LEFT"
    case JoinKind.Right => "RIGHT"
    case JoinKind.Full => "FULL"
  }

  // ---- the query core (projection vs aggregation) ------------------------

  /** One window-function SELECT item, rendered straight to SQL (the engine has no
    * window `Expr` node — windows live at the logical-plan level). `dataType` is
    * the function's reliable output type (`ROW_NUMBER`/`RANK`/`DENSE_RANK` -> Long,
    * `LAG`/`LEAD` -> the argument's type, aggregate windows per the aggregate).
    *
    * `tieSensitive` says whether two rows tied on the window's ORDER BY can get
    * DIFFERENT values from this function. That decides whether a query carrying it
    * has a layout-invariant output multiset ([[MetaQuery.isLayoutInvariant]]), and
    * so whether the mode-agreement oracle may run it:
    *
    *   - Tie-INsensitive: `RANK`/`DENSE_RANK` (peers share a rank, and the rank of
    *     a row depends only on the multiset of order-key values in its partition),
    *     an aggregate window with NO ORDER BY (the frame is the whole partition,
    *     so the value depends only on the partition's row multiset), and one with
    *     an ORDER BY and the default frame, RANGE UNBOUNDED PRECEDING to CURRENT
    *     ROW, whose frame takes every peer of the current row (the rows ordered
    *     up to and including its value, a multiset no layout changes).
    *   - Tie-sensitive: `ROW_NUMBER`, `LAG`/`LEAD`, and an aggregate window with a
    *     ROWS frame, which counts rows, so which of two peers comes first decides
    *     what each sees, and that varies with the partition fan-out. (Before
    *     plans/robustness/20 RANGE frames ran as ROWS, and every running
    *     aggregate was tie-sensitive.)
    *
    * `orderCols` are the window's ORDER BY columns, empty for the shapes with no
    * ORDER BY. They are carried for coverage accounting: the peer test a rank
    * function applies to them is a float-comparison site, so the coverage guard
    * needs to know which columns reach it (it also asserts every ordered shape
    * fills this in). Hand-written regressions may leave it empty. */
  final case class WinItem(
      sql: String,
      name: String,
      dataType: DataType,
      tieSensitive: Boolean,
      orderCols: Vector[ScopeCol] = Vector.empty)

  sealed trait QueryCore {
    /** Output column names + types — the schema of the query body, which the
      * mode-agreement oracle compares over. */
    def outputSchema: Vector[(String, DataType)]
    /** Output columns whose declared type is RELIABLE — the only columns TLP
      * may partition on. A bare column reference / group key / aggregate /
      * window output binds to its declared type exactly; a computed expression
      * does not, because its paren-free SQL can reparse to a wider type (e.g. an
      * `Int`-labelled `-1.0 % c` reparses to `Double`), so a literal or another
      * column of its declared type may not be what it compares with. Every
      * type partitions, floats included: a comparison of two non-NULL values is
      * TRUE or FALSE, NaN among them (see [[TlpPred]]), so it is UNKNOWN exactly
      * when an operand is NULL. */
    def tlpCandidates: Vector[(String, DataType)]
    /** Whether this core projects a window function at all — the shape-coverage
      * tally reads this. */
    def hasWindow: Boolean
    /** Whether this core projects a window function whose value can differ between
      * rows tied on its ORDER BY. Such a query's output multiset is layout-
      * dependent, so it is checked by TLP on a fixed layout only; a query whose
      * windows are all tie-insensitive is checked by the mode-agreement oracle
      * like any other shape (see [[WinItem.tieSensitive]]). */
    def hasTieSensitiveWindow: Boolean
    def renderSelect: String
    def groupSql: String
    def havingSql: String
  }

  /** `SELECT [DISTINCT] <items>, <window items>`. */
  final case class ProjectCore(
      items: Vector[(Expr, String)],
      distinct: Boolean,
      windows: Vector[WinItem] = Vector.empty) extends QueryCore {
    def outputSchema: Vector[(String, DataType)] =
      items.map { case (e, n) => (n, e.dataType) } ++ windows.map(w => (w.name, w.dataType))
    def tlpCandidates: Vector[(String, DataType)] =
      items.collect { case (e, n) if typeReliable(e) && isPartitionable(e.dataType) => (n, e.dataType) } ++
        windows.collect { case w if isPartitionable(w.dataType) => (w.name, w.dataType) }
    def hasWindow: Boolean = windows.nonEmpty
    def hasTieSensitiveWindow: Boolean = windows.exists(_.tieSensitive)
    def renderSelect: String = {
      val itemSql = items.iterator.map { case (e, n) => s"${SqlRender.expr(e)} AS $n" }
      val winSql = windows.iterator.map(w => s"${w.sql} AS ${w.name}")
      val sel = (itemSql ++ winSql).mkString(", ")
      if (distinct) s"DISTINCT $sel" else sel
    }
    def groupSql: String = ""
    def havingSql: String = ""
  }

  /** `SELECT <group cols>, <aggs> [GROUP BY ...] [HAVING ...]`. Empty `groupKeys`
    * is a global aggregate (one output row). */
  final case class AggCore(
      groupKeys: Vector[(Expr, String)],
      aggs: Vector[(QueryGen.AggSpec, String)],
      having: Option[Expr]) extends QueryCore {
    def outputSchema: Vector[(String, DataType)] =
      groupKeys.map { case (e, n) => (n, e.dataType) } ++ aggs.map { case (a, n) => (n, aggType(a)) }
    def tlpCandidates: Vector[(String, DataType)] =
      groupKeys.collect { case (e, n) if typeReliable(e) && isPartitionable(e.dataType) => (n, e.dataType) } ++
        aggs.collect { case (a, n) if isPartitionable(aggType(a)) => (n, aggType(a)) }
    def hasWindow: Boolean = false
    def hasTieSensitiveWindow: Boolean = false
    def renderSelect: String = {
      val g = groupKeys.map { case (e, n) => s"${SqlRender.expr(e)} AS $n" }
      val a = aggs.map { case (spec, n) => s"${spec.render} AS $n" }
      (g ++ a).mkString(", ")
    }
    def groupSql: String =
      if (groupKeys.isEmpty) "" else " GROUP BY " + groupKeys.map { case (e, _) => SqlRender.expr(e) }.mkString(", ")
    def havingSql: String = having.map(h => s" HAVING ${SqlRender.expr(h)}").getOrElse("")
  }

  /** The result type of an aggregate spec (the engine's `AggExpr.resultType`). */
  private def aggType(spec: QueryGen.AggSpec): DataType = spec match {
    case QueryGen.CountStar | _: QueryGen.Count | _: QueryGen.CountIf => DataType.LongType
    case QueryGen.Sum(arg, _) => AggExprSum(arg).resultType
    case QueryGen.Avg(arg, _) => AggExprAvg(arg).resultType
    case _: QueryGen.Stat | _: QueryGen.Bivar => DataType.DoubleType
    case QueryGen.Min(arg) => arg.dataType
    case QueryGen.Max(arg) => arg.dataType
  }

  // ---- the metamorphic case ----------------------------------------------

  /** A predicate over the query's OUTPUT columns, in the three TLP partitions.
    * Each shape renders all three variants paren-free. A comparison is NULL
    * exactly when an operand is NULL, over every type: a float's NaN equals
    * NaN and orders above every other number, and -0.0 equals 0.0
    * (plans/robustness/21), so no non-NULL row falls into none of the three
    * partitions. A float column was kept out of them until that held. */
  sealed trait TlpPred {
    def truePred: String
    def falsePred: String
    def nullPred: String
  }
  /** Boolean output column `b`: `b` / `NOT b` / `b IS NULL`. */
  final case class TlpBool(col: String) extends TlpPred {
    def truePred: String = col
    def falsePred: String = s"NOT $col"
    def nullPred: String = s"$col IS NULL"
  }
  /** `x op y` over two output columns: `x op y` / `x negOp y` / `x IS NULL OR y IS NULL`. */
  final case class TlpCmpCols(x: String, op: String, negOp: String, y: String) extends TlpPred {
    def truePred: String = s"$x $op $y"
    def falsePred: String = s"$x $negOp $y"
    def nullPred: String = s"$x IS NULL OR $y IS NULL"
  }
  /** `x op lit` over one output column: `x op lit` / `x negOp lit` / `x IS NULL`. */
  final case class TlpCmpLit(x: String, op: String, negOp: String, lit: String) extends TlpPred {
    def truePred: String = s"$x $op $lit"
    def falsePred: String = s"$x $negOp $lit"
    def nullPred: String = s"$x IS NULL"
  }

  /** A generated relation environment + a row-producing query over it, plus the
    * TLP partition predicate chosen over the query's output columns. The seed
    * reproduces all three exactly. */
  final case class MetaCase(env: RelEnv, query: MetaQuery, tlp: Option[TlpPred]) {
    def bodySql: String = query.renderBody
    override def toString: String =
      s"MetaCase(\n  body = $bodySql\n  tlp  = ${tlp.getOrElse("<none>")}\n  env  = $env)"
  }

  /** The query body (the text that becomes a CTE body / the base query).
    *
    * `ctes` are the leading `WITH` definitions (empty for a plain query); `setOp`
    * carries an optional `UNION` / `UNION ALL` right arm — a second [[MetaQuery]]
    * over the same sources producing a schema-compatible output (the binder takes
    * the union's column names + types from the LEFT arm, so [[outputSchema]] /
    * [[tlpCandidates]] are this arm's). */
  final case class MetaQuery(
      from: FromClause,
      where: Option[Expr],
      core: QueryCore,
      setOp: Option[(MetaQuery, Boolean)] = None,
      ctes: Vector[CteDef] = Vector.empty,
      limit: Option[Long] = None,
      offset: Option[Long] = None,
      nullsFirst: Option[Boolean] = None) {
    def outputSchema: Vector[(String, DataType)] = core.outputSchema
    def tlpCandidates: Vector[(String, DataType)] = core.tlpCandidates
    /** The WHERE: a comma list's or `CROSS JOIN`s' join conditions, then the
      * query's own predicate, in parentheses beside them, so an OR in it
      * cannot take a join condition with it. */
    def whereSql: String = {
      val joined = from.whereConditions.map(SqlRender.expr)
      val own = where.map(w => if (joined.isEmpty) SqlRender.expr(w) else s"(${SqlRender.expr(w)})")
      val all = joined ++ own
      if (all.isEmpty) "" else all.mkString(" WHERE ", " AND ", "")
    }

    /** The SOURCE columns behind every projected item, when the SELECT list is all
      * bare, type-reliable column references — the condition under which `ORDER
      * BY` over all of them is a TOTAL order on the output, so two rows tied at a
      * `LIMIT` cutoff are identical rows (or, in a float column, -0.0 and 0.0,
      * which `RowOracle` compares as equal) and top-`n` yields one fixed multiset.
      * `None` (and so no `LIMIT`) for every other shape.
      *
      * The keys are the SOURCE columns, qualified (`t0.c0`), which an `ORDER BY`
      * without `DISTINCT` reads in the input whatever the output names are; the
      * output alias (`ORDER BY o0`) would mean the same here. `DISTINCT` is
      * excluded because past the dedup the scope is the projection, where the
      * source name no longer resolves, along with windows and aggregates (no
      * source column to name) and a `UNION` (the clause would bind to the whole
      * set operation, not to this arm); ordering those by output names or
      * positions would give them a total order too.
      *
      * Deriving the keys instead of storing them keeps every shrink sound: drop a
      * projection item and the order shrinks with it, still total; make the list
      * non-bare and the `LIMIT` disappears rather than manufacturing a false
      * counterexample. */
    def totalOrderKeys: Option[Vector[String]] = core match {
      case ProjectCore(items, false, windows) if windows.isEmpty && items.nonEmpty && setOp.isEmpty =>
        val cols = items.collect { case (c: ColRefExpr, _) if isPartitionable(c.dataType) => c.name }
        if (cols.length == items.length) Some(cols.distinct) else None
      case _ => None
    }

    /** ` ORDER BY <every output column's source> [LIMIT n] [OFFSET m]` — empty
      * unless a `LIMIT` or `OFFSET` was generated AND [[totalOrderKeys]] still
      * holds. The two clauses share that soundness condition: a cutoff that falls
      * among tied rows keeps the same multiset only when tied rows are identical. */
    def topNSql: String = totalOrderKeys match {
      case Some(keys) if limit.isDefined || offset.isDefined =>
        val placement = nullsFirst.map(first => if (first) " NULLS FIRST" else " NULLS LAST").getOrElse("")
        s" ORDER BY ${keys.map(_ + placement).mkString(", ")}" +
          limit.map(n => s" LIMIT $n").getOrElse("") + offset.map(m => s" OFFSET $m").getOrElse("")
      case _ => ""
    }

    /** Whether this query actually renders a `LIMIT` or `OFFSET`. The
      * shape-coverage guard reads this rather than `limit.isDefined`: a generated
      * limit that fails the soundness condition renders nothing, and counting the
      * field would overstate the coverage. */
    def hasTopN: Boolean = topNSql.nonEmpty

    /** Whether this query actually renders an `OFFSET`. */
    def hasOffset: Boolean = topNSql.contains(" OFFSET ")

    /** Whether this query actually renders an explicit `NULLS FIRST` / `NULLS LAST`. */
    def hasNullPlacement: Boolean = topNSql.contains(" NULLS ")

    /** The `WITH` definitions without the `WITH` keyword (empty when no CTEs). */
    def cteClause: String = if (ctes.isEmpty) "" else ctes.map(_.render).mkString(", ")

    /** The `SELECT ... [UNION ...]` part, excluding any leading `WITH`. */
    def mainSelectSql: String = {
      val armSql = s"SELECT ${core.renderSelect} FROM ${from.render}$whereSql${core.groupSql}${core.havingSql}$topNSql"
      setOp match {
        case None => armSql
        case Some((rhs, all)) => s"$armSql UNION ${if (all) "ALL " else ""}${rhs.mainSelectSql}"
      }
    }

    def renderBody: String = if (ctes.isEmpty) mainSelectSql else s"WITH $cteClause $mainSelectSql"

    /** The TLP base: the query's output wrapped in a CTE `q` so a partition
      * predicate can filter it. Any existing `WITH` definitions are HOISTED to sit
      * beside `q` (rather than nesting a `WITH` inside `q`'s body), which the
      * binder resolves identically. Append ` WHERE <partition>` for a partition. */
    def tlpBase: String = {
      val qDef = s"q AS ($mainSelectSql)"
      val withClause = if (ctes.isEmpty) qDef else s"$cteClause, $qDef"
      s"WITH $withClause SELECT * FROM q"
    }

    /** Whether the output multiset is independent of the physical input layout.
      * Projection / join / aggregate / distinct / union / CTE bodies are
      * layout-invariant, and so is a window function whose value cannot differ
      * between rows tied on its ORDER BY — `RANK`/`DENSE_RANK`, and aggregate
      * windows with no ORDER BY. Only a TIE-SENSITIVE window (`ROW_NUMBER`,
      * `LAG`/`LEAD`, a running aggregate) is layout-dependent, and only those
      * queries are left to TLP on a fixed layout.
      *
      * The distinction is what lets `WindowExec` be checked across layouts, spill
      * and — in the sharded target — against `ExchangeExec`-partitioned windows,
      * which excluding every window from the oracle previously prevented. */
    def isLayoutInvariant: Boolean =
      !core.hasTieSensitiveWindow && setOp.forall(_._1.isLayoutInvariant) &&
        ctes.forall(_.body.isLayoutInvariant)

    /** Whether any arm of the query projects a layout-invariant window — the
      * coverage signal that windows really are reaching the mode-agreement
      * oracle rather than all being filtered out as tie-sensitive. */
    def hasInvariantWindow: Boolean =
      (core.hasWindow && !core.hasTieSensitiveWindow) ||
        setOp.exists(_._1.hasInvariantWindow) || ctes.exists(_.body.hasInvariantWindow)

    /** Whether any arm of the query (this body, a union arm, or a CTE body)
      * contains a join. Read by the shape-coverage guard, which asserts the
      * generator keeps reaching join plans. (It was once also a gate that skipped
      * the spill mode for joins, around the grace-hash spill NPE; that engine bug
      * is fixed and spill now runs for every query — see
      * [[oracle.MetaModeDifferential]].) */
    def hasAnyJoin: Boolean =
      from.joins.nonEmpty || setOp.exists(_._1.hasAnyJoin) || ctes.exists(_.body.hasAnyJoin)
  }

  /** A single relation plus a predicate over its columns (aliased `t`) — the
    * input to the NoREC optimizer-equivalence relation. */
  final case class NoRecCase(relation: Relation, predicate: Expr) {
    def alias: String = "t"
    override def toString: String =
      s"NoRecCase(\n  rel  = ${relation.name}${relation.dataset.toString.stripPrefix("Dataset")}\n" +
        s"  pred = ${SqlRender.expr(predicate)})"
  }

  // ---- top-level generation ----------------------------------------------

  def generate(rng: Rng): MetaCase = {
    val env = genEnv(rng.split())
    val q = genQuery(rng.split(), env)
    val tlp = genTlpPred(rng.split(), q.tlpCandidates)
    MetaCase(env, q, tlp)
  }

  /** Generate a single relation + a predicate over it, for the NoREC relation. */
  def generateNoRec(rng: Rng): NoRecCase = {
    val keyType = ExprGen.pick(rng, KeyTypes)
    val keyDomain = sharedKeyDomain(rng.split(), keyType)
    val rel = genRelation(rng.split(), "r0", keyType, keyDomain)
    val scope = rel.schema.fields.map(f => ScopeCol("t", f.name, f.dataType)).toVector
    val pred = genPredicate(rng.split(), scope, rng.between(1, 3))
    NoRecCase(rel, pred)
  }

  // ---- relation environment generation -----------------------------------

  /** Join-key column types: every one of the ten. A DATE or TIMESTAMP key takes
    * the `LongHashMap` fast path INT and BIGINT take; a BINARY or DECIMAL key
    * takes the object codecs, the grace-hash spill buckets and shard routing,
    * each of which once split keys `=` calls equal (plan 02); a FLOAT or DOUBLE
    * key holds NaN and both zeros, which `=` calls equal to NaN and to each
    * other (plans/robustness/21), and a BOOLEAN key has two values. Draw with
    * `ExprGen.pick`. `private[fuzz]` for the coverage guards. */
  private[fuzz] val KeyTypes: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType, DataType.BooleanType,
      DataType.StringType, DataType.DateType, DataType.TimestampType, DataType.BinaryType) ++ ExprGen.DecimalTypes
  /** Payload (non-key) column types: all ten, `ExprGen.GenTypes`. Draw with
    * `ExprGen.pick`. `private[fuzz]` so the coverage guards can assert every
    * one of them still reaches the oracles. */
  private[fuzz] val PayloadTypes: Seq[DataType] = ExprGen.GenTypes
  private val StringPool: IndexedSeq[String] = IndexedSeq("a", "b", "c", "d", "")
  private val DoublePool: IndexedSeq[Double] = IndexedSeq(0.0, 0.5, 1.0, 2.0, -1.0, 10.0)

  /** Two to six relations over one key type and domain. An environment of
    * four or more holds small relations, of ten rows at most, so a FROM may
    * join up to six of them (`maxLeavesOf`). */
  private[fuzz] def genEnv(rng: Rng): RelEnv = {
    val keyType = ExprGen.pick(rng, KeyTypes)
    val keyDomain = sharedKeyDomain(rng, keyType)
    val n = rng.weighted((5, 2), (4, 3), (1, 4), (1, 5), (1, 6))
    val relations = (0 until n).map(i => genRelation(rng.split(), s"r$i", keyType, keyDomain, small = n > 3)).toVector
    RelEnv(relations)
  }

  /** The shared join-key value pool — small so cross-relation keys collide. */
  private def sharedKeyDomain(rng: Rng, keyType: DataType): IndexedSeq[Any] = keyType match {
    case DataType.IntType =>
      val w = rng.between(2, 4); val base = rng.oneOf(Seq(0, 1)); (0 until w).map(i => (base + i): Any)
    case DataType.LongType =>
      val w = rng.between(2, 4); val base = rng.oneOf(Seq(0L, 1L)); (0 until w).map(i => (base + i): Any)
    case DataType.StringType => stringDomain(rng)
    case DataType.BooleanType => IndexedSeq[Any](true, false)
    case DataType.DoubleType => slice(rng, FloatKeyPool.map(d => d: Any))
    case DataType.FloatType => slice(rng, FloatKeyPool.map(d => d.toFloat: Any))
    case other => slice(rng, poolOf(other))
  }

  /** A float key's values, ordered so a slice of neighbours usually holds two
    * of NaN, -0.0 and 0.0, the keys whose equality IEEE and Java's boxes get
    * wrong: NaN beside a number and beside -0.0, -0.0 beside 0.0. */
  private val FloatKeyPool: IndexedSeq[Double] =
    IndexedSeq(1.0, Double.NaN, -0.0, 0.0, 2.5, Double.PositiveInfinity)

  /** The pool a DATE, TIMESTAMP, BINARY or DECIMAL column takes its values from:
    * `ExprGen`'s pools for the first three, whose neighbours are the pairs a
    * comparison of the type gets wrong, and `DataGen.DecimalPools` for a
    * DECIMAL, whose values a `SUM` adds exactly (see there). */
  private def poolOf(dt: DataType): IndexedSeq[Any] = dt match {
    case DataType.DateType => ExprGen.DatePool.toIndexedSeq
    case DataType.TimestampType => ExprGen.TimestampPool.toIndexedSeq
    case DataType.BinaryType => ExprGen.BinaryPool.toIndexedSeq
    case d: DataType.DecimalType => DataGen.DecimalPools(d)
    case other => throw new IllegalArgumentException(s"no pool for $other")
  }

  /** 2-4 neighbouring values of `pool`, so keys collide and the neighbours
    * meet. */
  private def slice(rng: Rng, pool: IndexedSeq[Any]): IndexedSeq[Any] = {
    val w = rng.between(2, 4)
    val start = rng.between(0, pool.length - w)
    pool.slice(start, start + w)
  }

  private def genRelation(rng: Rng, name: String, keyType: DataType, keyDomain: IndexedSeq[Any],
      small: Boolean = false): Relation = {
    val nKeys = rng.between(1, 2)
    val nPayload = rng.between(1, 3)
    val keyFields = (0 until nKeys).map(i => Field(s"c$i", keyType))
    val payloadFields = (0 until nPayload).map(i => Field(s"c${nKeys + i}", ExprGen.pick(rng, PayloadTypes)))
    val schema = Schema((keyFields ++ payloadFields).toVector)
    val keyCols = (0 until nKeys).toVector

    // Per-column (nullProb, domain): key columns draw from the shared domain
    // (sometimes nullable, exercising 3VL join keys + outer null-extension).
    val specs: IndexedSeq[(Double, IndexedSeq[Any])] = schema.fields.zipWithIndex.map { case (f, idx) =>
      if (idx < nKeys) (rng.weighted((5, 0.0), (2, 0.2)), keyDomain)
      else (payloadNullProb(rng), payloadDomain(rng, f.dataType))
    }
    val numRows = rng.weighted((2, 0), (3, 1), (5, 2), (2, 3)) match {
      case 0 => 0
      case 1 => 1
      case 2 => if (small) rng.between(2, 6) else rng.between(2, 10)
      case _ => if (small) rng.between(7, 10) else rng.between(11, 24)
    }
    val rows = IndexedSeq.tabulate(numRows) { _ =>
      Array.tabulate[Any](schema.length) { c =>
        val (nullProb, domain) = specs(c)
        if (rng.bool(nullProb)) null else DataGen.fresh(rng.oneOf(domain))
      }
    }
    Relation(name, DataGen.Dataset(schema, rows), keyCols)
  }

  private def payloadNullProb(rng: Rng): Double = rng.weighted((5, 0.0), (3, 0.2), (1, 0.5))

  /** A payload column's candidate non-null values. Float/Double columns can also
    * carry `NaN` / `-0.0` / an infinity, through `DataGen.withSpecialDoubles` —
    * the pool and the rate live there, so this corpus and the mode-differential
    * one widen together. A float KEY column draws from `FloatKeyPool` instead,
    * which holds NaN and both zeros on most slices. */
  private def payloadDomain(rng: Rng, dt: DataType): IndexedSeq[Any] = dt match {
    case DataType.IntType => val b = rng.oneOf(Seq(-1, 0, 1)); (0 until rng.between(2, 4)).map(i => (b + i): Any)
    case DataType.LongType => val b = rng.oneOf(Seq(-1L, 0L, 1L)); (0 until rng.between(2, 4)).map(i => (b + i): Any)
    case DataType.FloatType =>
      DataGen.withSpecialDoubles(
        rng, DoublePool.take(rng.between(2, 4)).map(_.toFloat.asInstanceOf[Any]), _.toFloat)
    case DataType.DoubleType =>
      DataGen.withSpecialDoubles(
        rng, DoublePool.take(rng.between(2, 4)).map(_.asInstanceOf[Any]), identity)
    case DataType.BooleanType => IndexedSeq[Any](true, false)
    case DataType.StringType => stringDomain(rng)
    case other => slice(rng, poolOf(other))
  }

  /** A STRING column's values: the first 2-4 of the ASCII pool, or on a
    * share of columns 2-4 neighbours of [[TextPool]], the text past ASCII. */
  private def stringDomain(rng: Rng): IndexedSeq[Any] =
    if (rng.bool(TextProb)) TextPool.slice(rng, 4)
    else StringPool.take(rng.between(2, 4)).map(_.asInstanceOf[Any])

  /** How often a STRING column, key or payload, draws from [[TextPool]]. */
  private val TextProb = 0.35

  // ---- query generation ---------------------------------------------------

  private[fuzz] def genQuery(rng: Rng, env: RelEnv): MetaQuery = {
    // CTEs are scanned by the outer query like tables. A CTE referenced twice is
    // materialized once by the engine, referenced once it is inlined — both
    // covered because the outer FROM draws CTE names from the same source pool as
    // base relations (so the same CTE can be picked for two leaves / both union
    // arms). A CTE's body reads a base relation or a CTE defined before it.
    val ctes = genCtes(rng, env)
    val sources = env.relations.map(_.asSource) ++ ctes.map(_.asSource)
    val sizes = sourceSizes(env, ctes)
    val maxLeaves = maxLeavesOf(env)
    val q =
      if (rng.bool(0.2)) genUnion(rng, sources, sizes, maxLeaves)
      else genSelect(rng, sources, sizes, maxLeaves, topNAllowed = true)
    q.copy(ctes = ctes)
  }

  /** How many tables a FROM may join: up to six over small relations (an
    * environment of four or more, whose relations hold ten rows at most), up
    * to three otherwise. */
  private def maxLeavesOf(env: RelEnv): Int = if (env.relations.length > 3) 6 else 3

  /** Each source's rows, a CTE's as many as the source its body reads. */
  private def sourceSizes(env: RelEnv, ctes: Vector[CteDef]): Map[String, Int] =
    ctes.foldLeft(env.relations.map(r => r.name -> r.dataset.numRows).toMap) { (sizes, c) =>
      sizes + (c.name -> sizes.getOrElse(c.body.from.root.relName, 0))
    }

  /** About as many rows as a FROM's joins pair before the next is added: an
    * equality over the narrow key domain keeps about half of each pairing,
    * a join with none keeps them all. A join is not added past
    * [[JoinBudget]], so a chain of six, or a join with no equality, stays
    * small enough to run a dozen times a case. */
  private val JoinBudget = 2000.0

  private def genSelect(rng: Rng, sources: Vector[FromSource], sizes: Map[String, Int], maxLeaves: Int,
      topNAllowed: Boolean): MetaQuery = {
    val from = genFrom(rng, sources, sizes, maxLeaves)
    val scope = from.scope
    val where = if (rng.bool(0.6)) Some(genPredicate(rng, scope, rng.between(1, 3))) else None
    // A top-n query needs a core that satisfies `MetaQuery.totalOrderKeys`, which a
    // freely-generated one rarely does (a computed item, a window, or DISTINCT each
    // break it), so the shape is generated deliberately rather than hoped for.
    val topN = topNAllowed && rng.bool(0.2) && scope.exists(c => isPartitionable(c.dataType))
    val core =
      if (topN) genTopNCore(rng, scope)
      else if (rng.bool(0.5)) genAggCore(rng, scope)
      else genProjectCore(rng, scope)
    val (limit, offset) = QueryGen.withOffset(rng, topN, if (topN) Some(genLimit(rng)) else None)
    val nullsFirst = if (topN) QueryGen.genNullPlacement(rng) else None
    MetaQuery(from, where, core, limit = limit, offset = offset, nullsFirst = nullsFirst)
  }

  /** A `LIMIT` row count spanning the interesting cutoffs: none at all, one row, a
    * handful (a cut mid-batch and mid-partition), and past the end of the data
    * (the limit never binds). Mirrors [[QueryGen]]'s. */
  private def genLimit(rng: Rng): Long = rng.weighted(
    (1, 0L), (3, 1L), (4, 2L), (4, 5L), (2, 17L), (2, 1000L))

  /** The projection a `LIMIT` is sound over: 1-3 bare, type-reliable columns,
    * no DISTINCT, no window, no computed item — so the rendered `ORDER BY`
    * covers every output column and the order is total. */
  private def genTopNCore(rng: Rng, scope: Vector[ScopeCol]): ProjectCore = {
    val orderable = scope.filter(c => isPartitionable(c.dataType))
    val cols = distinctSubset(rng, orderable, rng.between(1, math.min(3, orderable.length)))
    ProjectCore(cols.zipWithIndex.map { case (c, i) => (c.ref, s"o$i") }, distinct = false)
  }

  /** A `UNION` / `UNION ALL` of two arms of one output schema. Either two
    * single-source arms that each project one bare key column (every source
    * shares the env's key type, so both output `[o0: keyType]`), or any core
    * over any FROM (joins, an aggregate, windows, a computed item) beside the
    * same core with a WHERE of its own, the same schema by construction. No
    * arm has a `LIMIT`, which a union takes only as a whole. UNION (distinct)
    * and UNION ALL (multiset) are both generated; when the source pool holds a
    * CTE, both arms can reference it. */
  private def genUnion(rng: Rng, sources: Vector[FromSource], sizes: Map[String, Int], maxLeaves: Int): MetaQuery = {
    val all = rng.bool()
    if (rng.bool(0.4)) {
      val arm1 = genUnionArm(rng, sources)
      val arm2 = genUnionArm(rng, sources)
      arm1.copy(setOp = Some((arm2, all)))
    } else {
      val arm1 = genSelect(rng, sources, sizes, maxLeaves, topNAllowed = false)
      val ownWhere = if (rng.bool(0.7)) Some(genPredicate(rng, arm1.from.scope, rng.between(1, 2))) else None
      arm1.copy(setOp = Some((arm1.copy(where = ownWhere), all)))
    }
  }

  private def genUnionArm(rng: Rng, sources: Vector[FromSource]): MetaQuery = {
    val src = rng.oneOf(sources.filter(_.keyCols.nonEmpty))
    val alias = "t0"
    val cols = src.fields.map(f => ScopeCol(alias, f.name, f.dataType)).toVector
    val keyCol = cols(rng.oneOf(src.keyCols))
    val where = if (rng.bool(0.5)) Some(genPredicate(rng, cols, rng.between(1, 2))) else None
    val core = ProjectCore(Vector((keyCol.ref, "o0")), distinct = rng.bool(0.3))
    MetaQuery(FromClause(FromLeaf(src.name, alias, cols), Vector.empty), where, core)
  }

  /** 0-3 CTEs, each a projection of one source's key column(s) plus an
    * optional extra column, so every CTE exposes a key-typed (joinable)
    * output. A CTE's source is a base relation or, now and then, a CTE
    * defined before it, which the engine then inlines or materializes inside
    * another. */
  private def genCtes(rng: Rng, env: RelEnv): Vector[CteDef] =
    if (!rng.bool(0.35)) Vector.empty
    else (0 until rng.between(1, 3)).foldLeft(Vector.empty[CteDef]) { (done, i) =>
      done :+ genCte(rng, env, done, s"w$i")
    }

  private def genCte(rng: Rng, env: RelEnv, earlier: Vector[CteDef], name: String): CteDef = {
    val src: FromSource =
      if (earlier.nonEmpty && rng.bool(0.4)) rng.oneOf(earlier).asSource
      else rng.oneOf(env.relations).asSource
    val alias = "t0"
    val cols = src.fields.map(f => ScopeCol(alias, f.name, f.dataType))
    val keyType = cols(src.keyCols.head).dataType
    val projKeys = distinctSubset(rng, src.keyCols.map(cols), rng.between(1, src.keyCols.length))
    val extra = if (rng.bool(0.5)) Vector(rng.oneOf(cols)) else Vector.empty
    val projected = projKeys ++ extra
    val items = projected.zipWithIndex.map { case (c, i) => (c.ref, s"o$i") }
    val where = if (rng.bool(0.5)) Some(genPredicate(rng, cols, rng.between(1, 2))) else None
    val body = MetaQuery(FromClause(FromLeaf(src.name, alias, cols), Vector.empty), where,
      ProjectCore(items, distinct = rng.bool(0.2)))
    val keyCols = body.outputSchema.zipWithIndex.collect { case ((_, t), i) if t == keyType => i }.toVector
    CteDef(name, body, keyCols)
  }

  /** A left-deep FROM over the source pool (base relations + CTEs): a root
    * source plus up to `maxLeaves - 1` joins, written as `JOIN ... ON`, as a
    * comma list or as `CROSS JOIN`s ([[JoinStyle]]). The same source may
    * appear under several fresh aliases (a self-join, or a CTE referenced
    * twice). A join is not added once the rows the joins pair would pass
    * [[JoinBudget]]. */
  private def genFrom(rng: Rng, sources: Vector[FromSource], sizes: Map[String, Int], maxLeaves: Int): FromClause = {
    var aliasN = 0
    def freshAlias(): String = { val a = s"t$aliasN"; aliasN += 1; a }
    def leafOf(src: FromSource): (FromLeaf, Vector[Int]) = {
      val alias = freshAlias()
      val cols = src.fields.map(f => ScopeCol(alias, f.name, f.dataType))
      (FromLeaf(src.name, alias, cols), src.keyCols)
    }
    val nJoins = math.min(maxLeaves - 1, rng.weighted((2, 0), (5, 1), (2, 2), (1, 3), (1, 4), (1, 5)))
    val style = if (nJoins == 0) JoinStyle.On else rng.weighted[JoinStyle]((6, JoinStyle.On), (2, JoinStyle.Comma),
      (1, JoinStyle.Cross))
    val picked = (0 to nJoins).map(_ => leafOf(rng.oneOf(sources))).toVector
    val joins = if (style == JoinStyle.On) joinsOn(rng, picked) else joinsInWhere(rng, picked)
    within(FromClause(picked.head._1, joins, style), sizes)
  }

  /** The joins of `JOIN ... ON`, each of any kind: most on an equality with a
    * table before it, now and then two, and at most one on no equality at all
    * ([[genPairingOn]]), which the engine pairs row by row. A table whose ON
    * has no equality, before one whose ON has equalities with both it and the
    * tables before it, is a run `JoinOrder` reorders where the joins are
    * inner. */
  private def joinsOn(rng: Rng, picked: Vector[(FromLeaf, Vector[Int])]): Vector[JoinItem] = {
    var pairing = false
    picked.indices.drop(1).map { i =>
      val (leaf, keyCols) = picked(i)
      val before = picked.take(i).map(_._1)
      val kind = rng.weighted(
        (4, JoinKind.Inner), (3, JoinKind.Left), (2, JoinKind.Right), (2, JoinKind.Full))
      val on =
        if (!pairing && rng.bool(0.15)) { pairing = true; genPairingOn(rng, before.flatMap(_.cols), leaf) }
        else genJoinOn(rng, before, leaf, keyCols)
      JoinItem(kind, leaf, on)
    }.toVector
  }

  /** The joins of a comma list or `CROSS JOIN`s: inner joins whose equalities
    * make a random spanning tree over the tables, each equality written on the
    * join of the later of its two tables. So a table's equalities may all be
    * with tables after it, and `JoinOrder` brings one of those in first; now
    * and then an edge has no equality, and that join pairs every row. */
  private def joinsInWhere(rng: Rng, picked: Vector[(FromLeaf, Vector[Int])]): Vector[JoinItem] = {
    val n = picked.length
    val order = shuffle(rng, (0 until n).toVector)
    val conds = Array.fill(n)(Vector.empty[Expr])
    (1 until n).foreach { k =>
      val (a, b) = (order(k), order(rng.between(0, k - 1)))
      val (lo, hi) = (math.min(a, b), math.max(a, b))
      if (!rng.bool(0.08)) keyEquality(rng, picked(lo), picked(hi)).foreach(e => conds(hi) = conds(hi) :+ e)
    }
    (1 until n).map { i =>
      val on = if (conds(i).isEmpty) TrueLit else conds(i).reduceLeft((l, r) => BinOpExpr("AND", l, r, DataType.BooleanType))
      JoinItem(JoinKind.Inner, picked(i)._1, on)
    }.toVector
  }

  /** `l.k = r.k` over a key column of each, or another pair of one type, or
    * nothing when the two share no type. */
  private def keyEquality(rng: Rng, l: (FromLeaf, Vector[Int]), r: (FromLeaf, Vector[Int])): Option[Expr] = {
    val keyPairs = for { i <- l._2; j <- r._2 if l._1.cols(i).dataType == r._1.cols(j).dataType } yield (l._1.cols(i), r._1.cols(j))
    val anyPairs = for { a <- l._1.cols; b <- r._1.cols if a.dataType == b.dataType } yield (a, b)
    val pairs = if (keyPairs.nonEmpty) keyPairs else anyPairs
    if (pairs.isEmpty) None
    else { val (a, b) = rng.oneOf(pairs); Some(BinOpExpr("=", a.ref, b.ref, DataType.BooleanType)) }
  }

  private def shuffle[A](rng: Rng, xs: Vector[A]): Vector[A] = distinctSubset(rng, xs, xs.length)

  /** `from` with its joins up to the first that would pair past
    * [[JoinBudget]] rows. */
  private def within(from: FromClause, sizes: Map[String, Int]): FromClause = {
    def size(leaf: FromLeaf): Double = math.max(1, sizes.getOrElse(leaf.relName, 1)).toDouble
    var rows = size(from.root)
    val kept = from.joins.takeWhile { j =>
      val equality = conjuncts(j.on).exists { case BinOpExpr("=", _, _, _) => true; case _ => false }
      rows = rows * size(j.leaf) / (if (equality) 2.0 else 1.0)
      rows <= JoinBudget
    }
    from.copy(joins = kept)
  }

  private def conjuncts(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => conjuncts(l) ++ conjuncts(r)
    case other => Seq(other)
  }

  /** An ON with no equality: a comparison of a column before the join with
    * one of the joined table, of one type, or TRUE where they share none. The
    * engine pairs every row of one side with every row of the other
    * (`JoinExec`'s pairing path), which the nested-loop guard never refuses
    * at these sizes. */
  private def genPairingOn(rng: Rng, leftScope: Vector[ScopeCol], rightLeaf: FromLeaf): Expr = {
    val pairs = for { l <- leftScope; r <- rightLeaf.cols if r.dataType == l.dataType } yield (l, r)
    if (pairs.isEmpty) TrueLit
    else {
      val (l, r) = rng.oneOf(pairs)
      BinOpExpr(rng.oneOf(Seq("<", "<=", ">", ">=", "<>")), l.ref, r.ref, DataType.BooleanType)
    }
  }

  /** An ON predicate: an equi-join between a same-typed (earlier table, joined
    * table) column pair — preferring the joined source's key columns so the
    * join matches — now and then a second equality with another earlier
    * table, and an optional extra comparison conjunct. */
  private def genJoinOn(rng: Rng, before: Vector[FromLeaf], rightLeaf: FromLeaf, rightKeyColIdx: Vector[Int]): Expr = {
    val leftScope = before.flatMap(_.cols)
    val rightKeyCols = rightKeyColIdx.map(i => rightLeaf.cols(i))
    // Same-typed pairs (leftScopeCol, rightLeafCol), keys first for match likelihood.
    def pairsWith(scope: Vector[ScopeCol]): Vector[(ScopeCol, ScopeCol)] = {
      val keyPairs = for {
        l <- scope if l.dataType == rightKeyCols.headOption.map(_.dataType).getOrElse(DataType.NullType)
        r <- rightKeyCols if r.dataType == l.dataType
      } yield (l, r)
      val anyPairs = for { l <- scope; r <- rightLeaf.cols if r.dataType == l.dataType } yield (l, r)
      if (keyPairs.nonEmpty) keyPairs else anyPairs
    }
    val pairs = pairsWith(leftScope)
    if (pairs.isEmpty) {
      // No comparable columns — degenerate to a TRUE (cross) join.
      TrueLit
    } else {
      val (l, r) = rng.oneOf(pairs)
      val equi: Expr = BinOpExpr("=", l.ref, r.ref, DataType.BooleanType)
      // A second equality with another table before, so a table this join
      // follows need not be the one it keys on.
      val others = before.filterNot(_.alias == l.alias)
      val second: Option[Expr] =
        if (others.nonEmpty && rng.bool(0.3)) pairsWith(rng.oneOf(others).cols).headOption.map { case (a, b) =>
          BinOpExpr("=", a.ref, b.ref, DataType.BooleanType)
        } else None
      val keyed = second.fold(equi)(s => BinOpExpr("AND", equi, s, DataType.BooleanType))
      if (rng.bool(0.25)) {
        val extra = genAtom(rng, leftScope ++ rightLeaf.cols, 1)
        BinOpExpr("AND", keyed, extra, DataType.BooleanType)
      } else keyed
    }
  }

  private def genProjectCore(rng: Rng, scope: Vector[ScopeCol]): ProjectCore = {
    val distinct = rng.bool(0.4)
    // Always project a couple of bare columns (TLP partitions on a bare
    // output column); occasionally add a shallow computed column.
    val bareCount = rng.between(1, math.min(3, scope.length))
    val bare = distinctSubset(rng, scope, bareCount)
    val bareItems: Vector[(Expr, String)] = bare.zipWithIndex.map { case (sc, i) => (sc.ref, s"o$i") }
    // A math function of a numeric column, now and then, whose type is its
    // own whatever the column's, so TLP may partition on it, and on the rows
    // its domain answers NULL over.
    val numeric = scope.filter(c => DataType.isNumeric(c.dataType))
    val computed: Vector[(Expr, String)] =
      if (rng.bool(0.4)) {
        val t = ExprGen.pick(rng, ComputedTypes)
        val e = if (numeric.nonEmpty && rng.bool(0.3)) genDomainCall(rng, numeric) else genValue(rng, scope, t, 2)
        Vector((e, s"o${bareItems.length}"))
      } else Vector.empty
    val items = bareItems ++ computed
    // Sometimes append window-function columns. A window whose value can differ
    // between rows tied on its ORDER BY leaves the query TLP-only; the
    // tie-insensitive shapes stay layout-invariant and reach every oracle.
    val windows: Vector[WinItem] =
      if (rng.bool(0.3)) (0 until rng.between(1, 2)).map(i => genWindowItem(rng, scope, items.length + i)).toVector
      else Vector.empty
    ProjectCore(items, distinct, windows)
  }

  // ---- window functions ---------------------------------------------------

  /** One `<fn>() OVER (PARTITION BY ... ORDER BY ... [frame])` column. The
    * OVER parentheses are function syntax, not grouping parens, so this stays
    * paren-free-safe. An aggregate's argument is a column or a call that
    * keeps its type (`ABS` of a number, `UPPER` of a string), so the output
    * type is exact; a PARTITION BY or ORDER BY key is a column or now and then
    * an expression over one ([[keyText]]). The frames: none (the whole
    * partition), the default (RANGE from the first row to the current one's
    * last peer), ROWS with offsets either side, and RANGE with offsets, over a
    * single number key, the value it measures. */
  private def genWindowItem(rng: Rng, scope: Vector[ScopeCol], idx: Int): WinItem = {
    val name = s"o$idx"
    val partCols = distinctSubset(rng, scope, rng.between(0, math.min(2, scope.length)))
    val orderCols = distinctSubset(rng, scope, rng.between(1, math.min(2, scope.length)))
    val partBy = if (partCols.isEmpty) "" else "PARTITION BY " + partCols.map(keyText(rng, _)).mkString(", ")
    def direction(): String = if (rng.bool()) "ASC" else "DESC"
    val orderBy = "ORDER BY " + orderCols.map(c => s"${keyText(rng, c)} ${direction()}").mkString(", ")
    def over(body: String): String = s"OVER (${Seq(partBy, body).filter(_.nonEmpty).mkString(" ")})"

    val numeric = scope.filter(c => DataType.isNumeric(c.dataType))
    /** A number argument and the type a SUM of it is: a column, or `ABS` of
      * one, which keeps its type. */
    def numberArg(): (String, DataType) = {
      val c = rng.oneOf(numeric)
      (if (rng.bool(0.25)) s"ABS(${c.qualified})" else c.qualified, c.dataType)
    }
    /** Any argument of MIN or MAX: a column, or `UPPER` of a string one. */
    def anyArg(): (String, DataType) = {
      val c = rng.oneOf(scope)
      (if (c.dataType == DataType.StringType && rng.bool(0.3)) s"UPPER(${c.qualified})" else c.qualified, c.dataType)
    }
    val choices = scala.collection.mutable.ArrayBuffer.empty[(Int, () => WinItem)]
    // Tie-sensitive: the value depends on which of two tied rows comes first.
    choices += ((3, () => WinItem(s"ROW_NUMBER() ${over(orderBy)}", name, DataType.LongType,
      tieSensitive = true, orderCols)))
    choices += ((2, () => { val c = rng.oneOf(scope)
      WinItem(s"${rng.oneOf(Seq("LAG", "LEAD"))}(${c.qualified}) ${over(orderBy)}", name, c.dataType,
        tieSensitive = true, orderCols) }))
    // LAG and LEAD with an offset and a default, which takes the value's type
    // by the branch rule: the value itself, or a literal of its type where
    // SQL writes one that keeps it.
    choices += ((2, () => { val c = rng.oneOf(scope)
      val default = c.dataType match {
        case DataType.IntType | DataType.LongType | DataType.DoubleType | DataType.StringType | DataType.DateType |
            DataType.TimestampType | DataType.BooleanType if rng.bool(0.6) =>
          SqlRender.expr(LitExpr(genLit(rng, c.dataType), c.dataType))
        case _ => c.qualified
      }
      WinItem(s"${rng.oneOf(Seq("LAG", "LEAD"))}(${c.qualified}, ${rng.between(1, 3)}, $default) ${over(orderBy)}",
        name, c.dataType, tieSensitive = true, orderCols) }))
    // Tie-insensitive: peers share a rank, and a no-ORDER-BY aggregate window
    // frames the whole partition. These are the shapes the mode-agreement oracle
    // may run, which is how the sharded window path gets differential coverage.
    choices += ((3, () => WinItem(s"${rng.oneOf(Seq("RANK", "DENSE_RANK"))}() ${over(orderBy)}", name,
      DataType.LongType, tieSensitive = false, orderCols)))
    choices += ((3, () => WinItem(s"COUNT(*) ${over("")}", name, DataType.LongType, tieSensitive = false)))
    if (numeric.nonEmpty) {
      // Running aggregates over the default RANGE frame: peers share a frame.
      choices += ((2, () => { val (a, t) = numberArg()
        WinItem(s"SUM($a) ${over(orderBy)}", name, sumType(t), tieSensitive = false, orderCols) }))
      choices += ((1, () => { val (a, t) = numberArg()
        WinItem(s"AVG($a) ${over(orderBy)}", name, avgType(t), tieSensitive = false, orderCols) }))
      // A ROWS frame counts rows, so which of two peers comes first decides
      // what each sees.
      choices += ((2, () => { val (a, t) = numberArg()
        val f = QueryGen.genFrame(rng, rows = true)
        WinItem(s"SUM($a) OVER (${Seq(partBy, orderBy, f.sql).filter(_.nonEmpty).mkString(" ")})",
          name, sumType(t), tieSensitive = true, orderCols) }))
      // A RANGE frame with offsets measures one number key: its frames are
      // sets of values, which ties cannot change.
      choices += ((3, () => {
        val k = rng.oneOf(numeric)
        val key = if (rng.bool(0.3)) s"ABS(${k.qualified})" else k.qualified
        val f = QueryGen.genFrame(rng, rows = false)
        val rangeOrder = s"ORDER BY $key ${direction()}"
        val body = Seq(partBy, rangeOrder, f.sql).filter(_.nonEmpty).mkString(" ")
        rng.weighted[() => WinItem](
          (3, () => { val (a, t) = numberArg(); WinItem(s"SUM($a) OVER ($body)", name, sumType(t), tieSensitive = false, Vector(k)) }),
          (1, () => { val (a, t) = numberArg(); WinItem(s"AVG($a) OVER ($body)", name, avgType(t), tieSensitive = false, Vector(k)) }),
          (1, () => WinItem(s"COUNT(*) OVER ($body)", name, DataType.LongType, tieSensitive = false, Vector(k))),
          (1, () => { val (a, t) = anyArg()
            WinItem(s"${rng.oneOf(Seq("MIN", "MAX"))}($a) OVER ($body)", name, t, tieSensitive = false, Vector(k)) }))()
      }))
      // Whole-partition aggregate windows: no ORDER BY, so no frame to split peers.
      choices += ((2, () => { val (a, t) = numberArg()
        WinItem(s"SUM($a) ${over("")}", name, sumType(t), tieSensitive = false) }))
      choices += ((1, () => { val (a, t) = numberArg()
        WinItem(s"AVG($a) ${over("")}", name, avgType(t), tieSensitive = false) }))
    }
    choices += ((1, () => { val (a, t) = anyArg()
      WinItem(s"${rng.oneOf(Seq("MIN", "MAX"))}($a) ${over(orderBy)}", name, t,
        tieSensitive = false, orderCols) }))
    choices += ((2, () => { val (a, t) = anyArg()
      WinItem(s"${rng.oneOf(Seq("MIN", "MAX"))}($a) ${over("")}", name, t, tieSensitive = false) }))
    rng.weighted(choices.toSeq: _*)()
  }

  /** A PARTITION BY or ORDER BY key over `c`: the column, or now and then an
    * expression of it, `ABS` or `+ 1` of a number, `UPPER` or `LENGTH` of a
    * string. */
  private def keyText(rng: Rng, c: ScopeCol): String =
    if (!rng.bool(0.25)) c.qualified
    else c.dataType match {
      case t if DataType.isNumeric(t) => if (rng.bool()) s"ABS(${c.qualified})" else s"${c.qualified} + 1"
      case DataType.StringType => if (rng.bool()) s"UPPER(${c.qualified})" else s"LENGTH(${c.qualified})"
      case _ => c.qualified
    }

  /** A window SUM's and AVG's types over a column of `dt`, as the engine's
    * `AggExprSum` and `AggExprAvg` decide them. */
  private def sumType(dt: DataType): DataType = AggExprSum(ColRefExpr(0, "", dt)).resultType
  private def avgType(dt: DataType): DataType = AggExprAvg(ColRefExpr(0, "", dt)).resultType

  private def genAggCore(rng: Rng, scope: Vector[ScopeCol]): AggCore = {
    // Group keys: bare scope columns of any type, a float's NaN and both zeros
    // among them. A global aggregate (no keys) is allowed too.
    val groupable = scope.filter(c => isPartitionable(c.dataType))
    val keys: Vector[ScopeCol] =
      if (groupable.nonEmpty && rng.bool(0.8))
        distinctSubset(rng, groupable, rng.between(1, math.min(2, groupable.length)))
      else Vector.empty
    // Now and then a key is a math function of a numeric column, so the rows
    // its domain answers NULL over make a group of their own.
    val numeric = scope.filter(c => DataType.isNumeric(c.dataType))
    val keyExprs: Vector[Expr] = keys.map(_.ref) ++
      (if (numeric.nonEmpty && rng.bool(0.25)) Vector(genDomainCall(rng, numeric)) else Vector.empty)
    val groupKeys: Vector[(Expr, String)] = keyExprs.zipWithIndex.map { case (e, i) => (e, s"o$i") }
    val nAgg = rng.between(1, 2)
    val aggs: Vector[(QueryGen.AggSpec, String)] =
      (0 until nAgg).map(i => (genAggSpec(rng, scope), s"o${groupKeys.length + i}")).toVector
    // HAVING over a grouped query: `COUNT(*) cmp <small int>` — the canonical
    // filter-over-aggregate shape, always bindable and paren-free.
    val having =
      if (groupKeys.nonEmpty && rng.bool(0.3))
        Some(BinOpExpr(rng.oneOf(Seq(">", ">=", "<", "<=")), countStarRef,
          LitExpr(rng.between(0, 2), DataType.IntType), DataType.BooleanType))
      else None
    AggCore(groupKeys, aggs, having)
  }

  /** A bound shape that renders to `COUNT(*)` for HAVING (the binder rebinds the
    * aggregate from the rendered text; only the rendered SQL matters, so the
    * `index` is unused). */
  private val countStarRef: Expr = ColRefExpr(0, "COUNT(*)", DataType.LongType)

  private def genAggSpec(rng: Rng, scope: Vector[ScopeCol]): QueryGen.AggSpec = {
    val numeric = scope.filter(c => DataType.isNumeric(c.dataType))
    val integral = scope.filter(c => DataType.isIntegral(c.dataType))
    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => QueryGen.AggSpec)]
    options += ((3, () => QueryGen.CountStar))
    options += ((2, () => QueryGen.Count(rng.oneOf(scope).ref, distinct = rng.bool(0.5))))
    options += ((2, () => QueryGen.Min(rng.oneOf(scope).ref)))
    options += ((2, () => QueryGen.Max(rng.oneOf(scope).ref)))
    if (numeric.nonEmpty) {
      options += ((3, () => QueryGen.Sum(rng.oneOf(numeric).ref)))
      options += ((3, () => QueryGen.Avg(rng.oneOf(numeric).ref)))
    }
    if (integral.nonEmpty)
      options += ((1, () => QueryGen.Stat(rng.oneOf(Seq("STDDEV_SAMP", "STDDEV_POP", "VAR_SAMP", "VAR_POP")),
        rng.oneOf(integral).ref)))
    rng.weighted(options.toSeq: _*)()
  }

  // ---- scope-aware scalar generators (paren-free, total) ------------------

  private val Numerics: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType)
  /** The types an `IS NULL` operand takes: every type but BOOLEAN. */
  private val ValueTypes: Seq[DataType] = ExprGen.GenTypes.filterNot(_ == DataType.BooleanType)
  /** The types a computed projection item takes: INT, BIGINT, STRING and
    * DOUBLE, whose arithmetic and `||` it was built for, and the four that take
    * only the conditional forms here. */
  private val ComputedTypes: Seq[DataType] =
    Seq(DataType.IntType, DataType.LongType, DataType.StringType, DataType.DoubleType, DataType.DateType,
      DataType.TimestampType, DataType.BinaryType) ++ ExprGen.DecimalTypes
  private val ArithOps = Seq("+", "-", "*", "/", "%")
  private val RelOps = Seq("<", "<=", ">", ">=", "=", "<>")

  private def leafProb(depth: Int): Double = if (depth <= 1) 0.6 else 0.35

  /** A non-boolean value expression of `target` over `scope`. A DATE,
    * TIMESTAMP or BINARY takes only `COALESCE`, which the engine has no
    * arithmetic for, and a DECIMAL `COALESCE`, `ABS`, arithmetic
    * ([[genDecimalArith]]) and arithmetic beside a leaf in a conditional
    * ([[genDecimalBranches]]). */
  private def genValue(rng: Rng, scope: Vector[ScopeCol], target: DataType, depth: Int): Expr = {
    if (depth <= 0 || rng.bool(leafProb(depth))) genLeafValue(rng, scope, target)
    else if (!Numerics.contains(target) && target != DataType.StringType) {
      def coalesce(): Expr =
        FuncExpr(rng.weighted((3, "COALESCE"), (1, "GREATEST"), (1, "LEAST")),
          Seq.fill(rng.between(1, 3))(genValue(rng, scope, target, depth - 1)), target)
      target match {
        case _: DataType.DecimalType =>
          val decimalCols = scope.filter(_.dataType.isInstanceOf[DataType.DecimalType])
          val abs = (3, () => FuncExpr("ABS", Seq(genValue(rng, scope, target, depth - 1)), target))
          val arith = (4, () => genDecimalArith(rng, scope, target, decimalCols, depth))
          val branches = (3, () => genDecimalBranches(rng, scope, target, decimalCols, depth))
          val options = Seq((5, () => coalesce()), abs) ++ (if (decimalCols.isEmpty) Nil else Seq(arith, branches))
          rng.weighted[() => Expr](options: _*)()
        case _ => coalesce()
      }
    }
    else if (target == DataType.StringType) genText(rng, scope, depth)
    else {
      def sub(t: DataType): Expr = genValue(rng, scope, t, depth - 1)
      val options = Seq[(Int, () => Expr)](
        (4, () => BinOpExpr(rng.oneOf(ArithOps), sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics)), target)),
        (2, () => FuncExpr("COALESCE", Seq.fill(rng.between(1, 3))(sub(target)), target)),
        (1, () => FuncExpr("ABS", Seq(sub(target)), target)),
        (1, () => FuncExpr("MOD", Seq(sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics))), target)),
        (1, () => FuncExpr(rng.oneOf(Seq("GREATEST", "LEAST")), Seq.fill(rng.between(1, 3))(sub(target)), target)),
        (1, () => FuncExpr("NULLIF", Seq(sub(target), sub(target)), target)),
        (1, () => FuncExpr("IF", Seq(genPredicate(rng, scope, depth - 1), sub(target), sub(target)), target)),
        // A rounding function of any number, and ROUND and TRUNC to a scale
        // a few places either way.
        (1, () => {
          val name = rng.oneOf(Seq("ROUND", "TRUNC", "FLOOR", "CEIL"))
          val scale = if ((name == "ROUND" || name == "TRUNC") && rng.bool()) Seq(LitExpr(rng.between(-1, 2), DataType.IntType))
            else Nil
          FuncExpr(name, sub(rng.oneOf(Numerics)) +: scale, target)
        }))
      val byTarget = target match {
        case DataType.IntType => Seq[(Int, () => Expr)](
          (2, () => FuncExpr("LENGTH", Seq(sub(DataType.StringType)), DataType.IntType)),
          (1, () => FuncExpr("SIGN", Seq(signArg(rng, scope)), DataType.IntType)))
        case DataType.DoubleType => Seq[(Int, () => Expr)]((4, () => genMathCall(rng, scope, depth)))
        case _ => Nil
      }
      rng.weighted(options ++ byTarget: _*)()
    }
  }

  /** A STRING value: `||`, `CONCAT`, `UPPER`, `LOWER`, `TRIM` and `SUBSTRING`
    * of strings, so the text the pool draws reaches each function the
    * engine's character rules are about. A `SUBSTRING` position or length is
    * a small literal, a length never negative, which is an error. */
  private def genText(rng: Rng, scope: Vector[ScopeCol], depth: Int): Expr = {
    def sub(): Expr = genValue(rng, scope, DataType.StringType, depth - 1)
    rng.weighted[() => Expr](
      (3, () => BinOpExpr("||", sub(), sub(), DataType.StringType)),
      (1, () => FuncExpr("CONCAT", Seq.fill(rng.between(1, 3))(sub()), DataType.StringType)),
      (1, () => FuncExpr(rng.oneOf(Seq("GREATEST", "LEAST")), Seq.fill(rng.between(1, 3))(sub()), DataType.StringType)),
      (3, () => FuncExpr(rng.oneOf(Seq("UPPER", "LOWER")), Seq(sub()), DataType.StringType)),
      (1, () => FuncExpr("TRIM", Seq(sub()), DataType.StringType)),
      (2, () => {
        val position = LitExpr(rng.between(-1, 4), DataType.IntType)
        val args = if (rng.bool()) Seq(sub(), position, LitExpr(rng.between(0, 3), DataType.IntType)) else Seq(sub(), position)
        FuncExpr("SUBSTRING", args, DataType.StringType)
      }))()
  }

  /** A COALESCE, IF or CASE with a decimal product or quotient as one value
    * and a leaf as the other, as `QueryGen.genDecimalBranches` draws it: two
    * DECIMALs of different types, the shape that once took the first value's
    * type and kept the arithmetic's extra digits, which a spill could not
    * write (`plans/robustness/09-branch-result-types.md`). */
  private def genDecimalBranches(
      rng: Rng, scope: Vector[ScopeCol], target: DataType, decimalCols: Vector[ScopeCol], depth: Int): Expr = {
    val leaf =
      if (rng.bool(0.7)) decimalCols.minBy(_.dataType.asInstanceOf[DataType.DecimalType].scale).ref
      else genLeafValue(rng, scope, target)
    val leafFirst = rng.bool(0.75)
    val (arith, cond) =
      if (rng.bool(0.5)) {
        val c = rng.oneOf(decimalCols).ref
        (BinOpExpr(rng.oneOf(Seq("*", "/")), c, rng.oneOf(QueryGen.UnevenNumbers), target),
          IsNullExpr(c, negated = !leafFirst))
      } else
        (genDecimalArith(rng, scope, target, decimalCols, depth - 1, topOps = Seq("*", "/")),
          genPredicate(rng, scope, depth - 1))
    val (a, b) = if (leafFirst) (leaf, arith) else (arith, leaf)
    rng.weighted[() => Expr](
      (1, () => FuncExpr("COALESCE", Seq(a, b), target)),
      (2, () => FuncExpr("IF", Seq(cond, a, b), target)),
      (2, () => CaseExpr(Seq((cond, a)), Some(b), target)))()
  }

  /** `(l op r)` over decimals, as `QueryGen.genDecimalArith` builds it: one
    * side an anchor, a DECIMAL column in scope or arithmetic over one, which
    * SQL types as a DECIMAL whatever it meets, and the other an anchor, a
    * number, an integer leaf or a NULL. Never a FLOAT or DOUBLE, or a decimal
    * value SQL would type as one (`ABS(1.0)`), which the binder refuses beside
    * a DECIMAL. */
  private def genDecimalArith(
      rng: Rng, scope: Vector[ScopeCol], target: DataType, decimalCols: Vector[ScopeCol], depth: Int,
      topOps: Seq[String] = ArithOps): Expr = {
    def anchor(d: Int): Expr = if (d <= 1 || rng.bool(0.6)) rng.oneOf(decimalCols).ref else node(d - 1)
    def other(d: Int): Expr = rng.weighted(
      (3, () => anchor(d)),
      (2, () => {
        val t = rng.oneOf(decimalCols).dataType
        LitExpr(genLit(rng, t), t): Expr
      }),
      (2, () => genLeafValue(rng, scope, rng.oneOf(Seq(DataType.IntType, DataType.LongType)))),
      (1, () => LitExpr(null, target)))()
    def node(d: Int, ops: Seq[String] = ArithOps): Expr = {
      val (l, r) = if (rng.bool()) (anchor(d), other(d)) else (other(d), anchor(d))
      BinOpExpr(rng.oneOf(ops), l, r, target)
    }
    node(depth, topOps)
  }

  /** A column, a NULL or a literal of `target`. A BINARY has no literal (SQL
    * cannot write a byte array), so it is a column or a NULL. */
  private def genLeafValue(rng: Rng, scope: Vector[ScopeCol], target: DataType): Expr = {
    val cols = scope.filter(_.dataType == target)
    if (cols.nonEmpty && rng.bool(0.8)) rng.oneOf(cols).ref
    else if (rng.bool(0.15) || target == DataType.BinaryType) LitExpr(null, target)
    else LitExpr(genLit(rng, target), target)
  }

  /** A boolean predicate over `scope`. Paren-free: AND/OR compose by precedence;
    * comparisons are same-category; `NOT` wraps only a boolean leaf. */
  private def genPredicate(rng: Rng, scope: Vector[ScopeCol], depth: Int): Expr = {
    if (depth <= 0 || rng.bool(leafProb(depth))) genAtom(rng, scope, depth)
    else rng.weighted(
      (3, () => BinOpExpr("AND", genPredicate(rng, scope, depth - 1), genPredicate(rng, scope, depth - 1), DataType.BooleanType)),
      (3, () => BinOpExpr("OR", genPredicate(rng, scope, depth - 1), genPredicate(rng, scope, depth - 1), DataType.BooleanType)),
      (2, () => UnaryOpExpr("NOT", genBoolLeaf(rng, scope), DataType.BooleanType)),
      (5, () => genAtom(rng, scope, depth)))()
  }

  private def genBoolLeaf(rng: Rng, scope: Vector[ScopeCol]): Expr = {
    val boolCols = scope.filter(_.dataType == DataType.BooleanType)
    if (boolCols.nonEmpty && rng.bool(0.8)) rng.oneOf(boolCols).ref else LitExpr(rng.bool(), DataType.BooleanType)
  }

  private def genAtom(rng: Rng, scope: Vector[ScopeCol], depth: Int): Expr = {
    def sub(t: DataType): Expr = genValue(rng, scope, t, math.max(0, depth - 1))
    val boolCols = scope.filter(_.dataType == DataType.BooleanType)
    val strCols = scope.filter(_.dataType == DataType.StringType)
    val temporalCols = scope.filter(c => c.dataType == DataType.DateType || c.dataType == DataType.TimestampType)
    val decimalCols = scope.filter(_.dataType.isInstanceOf[DataType.DecimalType])
    val binaryCols = scope.filter(_.dataType == DataType.BinaryType)
    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => Expr)]
    options += ((4, () => BinOpExpr(rng.oneOf(RelOps), sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics)), DataType.BooleanType)))
    if (strCols.nonEmpty)
      options += ((2, () => BinOpExpr(rng.oneOf(RelOps), sub(DataType.StringType), sub(DataType.StringType), DataType.BooleanType)))
    // A DATE or TIMESTAMP against its own type, a BINARY against a BINARY, and a
    // DECIMAL against a decimal expression or an integer leaf: never anything
    // that can be a FLOAT or DOUBLE, whose NaN the widening CAST refuses, which
    // rules out an integer expression too, since a target type here is a hint
    // and `c + 0` over a FLOAT column is a FLOAT.
    if (temporalCols.nonEmpty) options += ((2, () => {
      val t = rng.oneOf(temporalCols).dataType
      BinOpExpr(rng.oneOf(RelOps), sub(t), sub(t), DataType.BooleanType)
    }))
    if (decimalCols.nonEmpty) options += ((2, () => {
      val t = rng.oneOf(decimalCols).dataType
      val other =
        if (rng.bool(0.75)) sub(rng.oneOf(decimalCols).dataType)
        else genLeafValue(rng, scope, rng.oneOf(Seq(DataType.IntType, DataType.LongType)))
      BinOpExpr(rng.oneOf(RelOps), sub(t), other, DataType.BooleanType)
    }))
    // Decimal arithmetic, compared, so it reaches a filter and a join
    // condition as well as a projection.
    if (decimalCols.nonEmpty) options += ((2, () => {
      val t = rng.oneOf(decimalCols).dataType
      val arith = genDecimalArith(rng, scope, t, decimalCols, math.max(1, depth))
      BinOpExpr(rng.oneOf(RelOps), arith, sub(rng.oneOf(decimalCols).dataType), DataType.BooleanType)
    }))
    if (binaryCols.nonEmpty)
      options += ((1, () => BinOpExpr(rng.oneOf(RelOps), sub(DataType.BinaryType), sub(DataType.BinaryType), DataType.BooleanType)))
    options += ((2, () => IsNullExpr(sub(ExprGen.pick(rng, ValueTypes)), rng.bool())))
    if (strCols.nonEmpty) options += ((3, () => genTextAtom(rng, strCols)))
    if (boolCols.nonEmpty) options += ((2, () => rng.oneOf(boolCols).ref))
    options += ((1, () => LitExpr(rng.bool(), DataType.BooleanType)))
    rng.weighted(options.toSeq: _*)()
  }

  /** A predicate over a string column, as it is or through `UPPER`, `LOWER`,
    * `TRIM` or `SUBSTRING`: its `LENGTH` compared with a small number, it
    * compared with a literal, or it `LIKE` or `ILIKE` a pattern, a pattern
    * now and then of [[TextPool.Patterns]]. So a column of the text past
    * ASCII meets each function its characters are about in a filter, a join
    * condition and a NoREC predicate. */
  private def genTextAtom(rng: Rng, strCols: Vector[ScopeCol]): Expr = {
    val col = rng.oneOf(strCols).ref
    def int(n: Int): Expr = LitExpr(n, DataType.IntType)
    val text: Expr = rng.weighted[() => Expr](
      (2, () => col),
      (3, () => FuncExpr(rng.oneOf(Seq("UPPER", "LOWER")), Seq(col), DataType.StringType)),
      (1, () => FuncExpr("TRIM", Seq(col), DataType.StringType)),
      (2, () => FuncExpr("SUBSTRING", Seq(col, int(rng.between(1, 3))) ++
        (if (rng.bool()) Seq(int(rng.between(0, 2))) else Nil), DataType.StringType)))()
    rng.weighted[() => Expr](
      (2, () => BinOpExpr(rng.oneOf(RelOps), FuncExpr("LENGTH", Seq(text), DataType.IntType), int(rng.between(0, 3)),
        DataType.BooleanType)),
      (1, () => BinOpExpr(rng.oneOf(RelOps), text, LitExpr(genLit(rng, DataType.StringType), DataType.StringType),
        DataType.BooleanType)),
      (3, () => {
        val pattern = if (rng.bool(0.5)) rng.oneOf(TextPool.Patterns) else rng.oneOf(Seq("%", "a%", "%a%", "_"))
        LikeExpr(text, LitExpr(pattern, DataType.StringType), rng.bool(), LikeSyntax(caseInsensitive = rng.bool(0.4)))
      }))()
  }

  /** A call of one of `QueryGen.DoubleFunctions`, the catalog's functions
    * that answer a DOUBLE whatever they are given, each number argument a
    * numeric value in scope or one of `QueryGen.MathEdges`, the values a
    * domain has its edge at, or NaN or an infinity; `RAND` with a literal
    * seed. */
  private def genMathCall(rng: Rng, scope: Vector[ScopeCol], depth: Int): Expr = {
    // PI, E and RAND take no number, which genDomainCall draws the others
    // over too, so they are drawn here four times as often.
    val entry = rng.weighted(QueryGen.DoubleFunctions.map(e =>
      (if (e.params.contains(Funcs.Param.Number)) 1 else 4, e)): _*)
    val n = if (entry.name == "RAND") 1 else entry.arity.min
    val args = entry.paramsAt(n).map {
      case Funcs.Param.Number => rng.weighted[() => Expr](
        (3, () => genValue(rng, scope, rng.oneOf(Numerics), math.max(0, depth - 1))),
        (2, () => rng.oneOf(QueryGen.MathEdges)),
        (1, () => floatSpecialLiteral(rng.oneOf(Seq("NaN", "Infinity", "-Infinity")), DataType.DoubleType)))()
      case _ => LitExpr(rng.between(0, 9), DataType.IntType)
    }
    FuncExpr(rng.oneOf(entry.names), args, DataType.DoubleType)
  }

  /** `SIGN`'s argument: an integer or decimal column in scope, or a float one
    * guarded to its finite values, since `SIGN` of NaN is an error. */
  private def signArg(rng: Rng, scope: Vector[ScopeCol]): Expr = {
    val numeric = scope.filter(c => DataType.isNumeric(c.dataType))
    if (numeric.isEmpty) LitExpr(rng.between(-2, 2), DataType.IntType)
    else {
      val c = rng.oneOf(numeric)
      if (c.dataType == DataType.FloatType || c.dataType == DataType.DoubleType) {
        val finite = BinOpExpr("<", FuncExpr("ABS", Seq(c.ref), c.dataType), LitExpr(1.0e9, DataType.DoubleType),
          DataType.BooleanType)
        CaseExpr(Seq((finite, c.ref)), None, c.dataType)
      } else c.ref
    }
  }

  /** A call of a column in scope that answers a DOUBLE whatever the column
    * is, as an output column TLP may partition on and a GROUP BY key: a math
    * function of one number, or `POWER`, `LOG` or `ATAN2` of it and an edge
    * value, so the rows its domain answers NULL over (`SQRT` of a negative)
    * reach a partition and a group of their own. */
  private def genDomainCall(rng: Rng, numeric: Vector[ScopeCol]): Expr = {
    val c = rng.oneOf(numeric).ref
    val unary = QueryGen.DoubleFunctions.filter(e => e.arity.min == 1 && e.arity.max.contains(1) && e.name != "RAND")
    rng.weighted[() => Expr](
      (4, () => FuncExpr(rng.oneOf(unary).name, Seq(c), DataType.DoubleType)),
      (1, () => FuncExpr("POWER", Seq(c, rng.oneOf(QueryGen.MathEdges)), DataType.DoubleType)),
      (1, () => FuncExpr("LOG", Seq(rng.oneOf(QueryGen.MathEdges), c), DataType.DoubleType)),
      (1, () => FuncExpr("ATAN2", Seq(c, rng.oneOf(QueryGen.MathEdges)), DataType.DoubleType)))()
  }

  /** Whether `e`, an output column's expression, binds to its declared type
    * whatever its arguments are: a call of a catalog function whose result is
    * one type, as a math function's is a DOUBLE. */
  private def typeReliable(e: Expr): Boolean = e match {
    case _: ColRefExpr => true
    case FuncExpr(name, _, _) => FunctionCatalog.lookup(name).exists(_.result.isInstanceOf[FunctionCatalog.Result.Fixed])
    case _ => false
  }

  private def genLit(rng: Rng, dt: DataType): Any = dt match {
    case DataType.IntType => rng.between(0, 3): Any
    case DataType.LongType => rng.between(0, 3).toLong: Any
    case DataType.FloatType => rng.oneOf(DoublePool).toFloat: Any
    case DataType.DoubleType => rng.oneOf(DoublePool): Any
    case DataType.BooleanType => rng.bool(): Any
    case DataType.StringType => (if (rng.bool(0.25)) rng.oneOf(TextPool.Values) else rng.oneOf(StringPool)): Any
    case DataType.DateType => rng.oneOf(ExprGen.DatePool): Any
    case DataType.TimestampType => rng.oneOf(ExprGen.TimestampPool): Any
    case d: DataType.DecimalType => rng.oneOf(QueryGen.decimalLiterals(d)): Any
    case other => throw new IllegalArgumentException(s"genLit: unsupported $other")
  }

  // ---- TLP partition-predicate selection ---------------------------------

  /** The output types eligible as TLP partition keys, top-n keys and GROUP BY
    * keys: every value type. A comparison of two non-NULL values of one is TRUE
    * or FALSE, never UNKNOWN, floats included (see [[TlpPred]]), so the three
    * partitions cover every row. */
  private def isPartitionable(dt: DataType): Boolean = dt != DataType.NullType
  /** The partitionable types TLP compares with `<`, `=` and the rest: all but
    * BOOLEAN, which partitions as a predicate of its own. */
  private def isOrdered(dt: DataType): Boolean = isPartitionable(dt) && dt != DataType.BooleanType
  /** The ordered types with a literal: all but BINARY, which SQL cannot write,
    * so a BINARY output column partitions against another BINARY column. */
  private def hasLiteral(dt: DataType): Boolean = isOrdered(dt) && dt != DataType.BinaryType

  /** Choose a TLP partition predicate over the query's type-reliable output
    * columns ([[QueryCore.tlpCandidates]]), or `None` when there are none. */
  private def genTlpPred(rng: Rng, cand: Vector[(String, DataType)]): Option[TlpPred] = {
    if (cand.isEmpty) return None
    val boolCols = cand.collect { case (n, DataType.BooleanType) => n }
    val orderedCols = cand.filter { case (_, t) => isOrdered(t) }
    val choices = scala.collection.mutable.ArrayBuffer.empty[(Int, () => TlpPred)]
    if (boolCols.nonEmpty) choices += ((2, () => TlpBool(rng.oneOf(boolCols))))
    val literalCols = orderedCols.filter { case (_, t) => hasLiteral(t) }
    if (literalCols.nonEmpty) {
      choices += ((2, () => {
        val (n, t) = rng.oneOf(literalCols)
        val (op, neg) = rng.oneOf(cmpPairs)
        TlpCmpLit(n, op, neg, SqlRender.expr(tlpLiteral(rng, t)))
      }))
    }
    if (orderedCols.nonEmpty) {
      // Two same-typed ordered columns -> column/column comparison.
      val byType = orderedCols.groupBy(_._2).values.filter(_.length >= 2).toSeq
      if (byType.nonEmpty) choices += ((2, () => {
        val pair = rng.oneOf(byType)
        val a = rng.oneOf(pair)
        val b = rng.oneOf(pair.filterNot(_._1 == a._1))
        val (op, neg) = rng.oneOf(cmpPairs)
        TlpCmpCols(a._1, op, neg, b._1)
      }))
    }
    if (choices.isEmpty) None else Some(rng.weighted(choices.toSeq: _*)())
  }

  /** The literal a TLP partition compares a column of `t` with: one of its
    * pool's, and for a FLOAT or DOUBLE now and then NaN, -0.0 or an infinity,
    * which SQL writes as a CAST of its text (`CAST('NaN' AS DOUBLE)`), so the
    * partition's own constant sits at the ends of the order and beside 0.0. */
  private def tlpLiteral(rng: Rng, t: DataType): Expr = t match {
    case DataType.FloatType | DataType.DoubleType if rng.bool(0.3) =>
      floatSpecialLiteral(rng.oneOf(Seq("NaN", "-0.0", "Infinity", "-Infinity")), t)
    case _ => LitExpr(genLit(rng, t), t)
  }

  /** A FLOAT or DOUBLE literal SQL has no plain spelling for: NaN and the
    * infinities as a CAST of their text, and -0.0 as the negative number it
    * is written as. */
  private[fuzz] def floatSpecialLiteral(text: String, t: DataType): Expr =
    if (text == "-0.0") LitExpr(if (t == DataType.FloatType) -0.0f else -0.0, t)
    else CastExpr(LitExpr(text, DataType.StringType), t)

  /** (op, negatedOp) pairs: for non-null operands exactly one of the two holds. */
  private val cmpPairs: Seq[(String, String)] =
    Seq("=" -> "<>", "<>" -> "=", "<" -> ">=", "<=" -> ">", ">" -> "<=", ">=" -> "<")

  // ---- helpers ------------------------------------------------------------

  private def distinctSubset[A](rng: Rng, xs: Vector[A], count: Int): Vector[A] = {
    val want = math.max(0, math.min(count, xs.length))
    val pool = scala.collection.mutable.ArrayBuffer.from(xs)
    val out = Vector.newBuilder[A]
    var k = 0
    while (k < want && pool.nonEmpty) { out += pool.remove(rng.between(0, pool.length - 1)); k += 1 }
    out.result()
  }
}
