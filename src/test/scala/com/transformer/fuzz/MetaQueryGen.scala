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
  *     reparse stays type-correct.
  *
  *   - '''Total + paren-free.''' Nothing here builds a tree that throws for
  *     in-range input or that needs a grouping paren (the SQL frontend rejects
  *     grouping parens). So a query that binds also runs, in every mode.
  *
  * Excluded so the output multiset is well-defined for the metamorphic relations:
  * `RAND()` and the clock functions, which are mode-dependent. `LIMIT` IS
  * generated, but only in the '''top-n''' shape — a bare, type-reliable,
  * non-float projection ordered by every one of its columns (see
  * [[MetaQuery.totalOrderKeys]]). Rows tied at the cutoff are then identical rows,
  * so the top-`n` multiset is the same under every layout, every build-side swap,
  * and in each of TLP's four separate executions. The relations share a narrow
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
    * binder folds `ps.getJoins` into a chain of [[LogicalJoin]]s. */
  final case class JoinItem(kind: JoinKind, leaf: FromLeaf, on: Expr)

  final case class FromClause(root: FromLeaf, joins: Vector[JoinItem]) {
    /** Columns in scope at the end of the FROM clause (root then each join leaf,
      * left-to-right). */
    def scope: Vector[ScopeCol] = root.cols ++ joins.flatMap(_.leaf.cols)

    def render: String = {
      val sb = new StringBuilder(root.render)
      joins.foreach { j =>
        sb.append(' ').append(joinKeyword(j.kind)).append(" JOIN ").append(j.leaf.render)
          .append(" ON ").append(SqlRender.expr(j.on))
      }
      sb.toString
    }
  }

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
    *     and any aggregate window with NO ORDER BY (the frame is the whole
    *     partition, so the value depends only on the partition's row multiset).
    *   - Tie-sensitive: `ROW_NUMBER`, `LAG`/`LEAD`, and every aggregate window WITH
    *     an ORDER BY. The engine treats RANGE frames as ROWS (see
    *     `plan/Window.scala`), so a running aggregate really does split peers, and
    *     which peer comes first varies with the partition fan-out. */
  final case class WinItem(sql: String, name: String, dataType: DataType, tieSensitive: Boolean)

  sealed trait QueryCore {
    /** Output column names + types — the schema of the query body, which the
      * mode-agreement oracle compares over. */
    def outputSchema: Vector[(String, DataType)]
    /** Output columns whose declared type is RELIABLE and non-float — the only
      * columns TLP may partition on. A bare column reference / group key / aggregate
      * / window output binds to its declared type exactly; a computed expression
      * does not, because its paren-free SQL can reparse to a wider type (e.g. an
      * `Int`-labelled `-1.0 % c` reparses to `Double`, whose `NaN` would fall into
      * none of TLP's three partitions). Restricting to reliable non-float columns
      * keeps a TLP comparison UNKNOWN exactly when an operand is NULL. */
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
      items.collect { case (c: ColRefExpr, n) if isPartitionable(c.dataType) => (n, c.dataType) } ++
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
      groupKeys.collect { case (c: ColRefExpr, n) if isPartitionable(c.dataType) => (n, c.dataType) } ++
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
    case QueryGen.Sum(arg) => arg.dataType match {
      case DataType.IntType | DataType.LongType => DataType.LongType
      case DataType.FloatType | DataType.DoubleType => DataType.DoubleType
      case other => other
    }
    case _: QueryGen.Avg | _: QueryGen.Stat | _: QueryGen.Bivar => DataType.DoubleType
    case QueryGen.Min(arg) => arg.dataType
    case QueryGen.Max(arg) => arg.dataType
  }

  // ---- the metamorphic case ----------------------------------------------

  /** A predicate over the query's OUTPUT columns, in the three TLP partitions.
    * Each shape renders all three variants paren-free; restricted to non-float
    * output columns so a comparison is NULL exactly when an operand is NULL (no
    * NaN can fall into none of the three partitions). */
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
      limit: Option[Long] = None) {
    def outputSchema: Vector[(String, DataType)] = core.outputSchema
    def tlpCandidates: Vector[(String, DataType)] = core.tlpCandidates
    def whereSql: String = where.map(w => s" WHERE ${SqlRender.expr(w)}").getOrElse("")

    /** The SOURCE columns behind every projected item, when the SELECT list is all
      * bare, type-reliable, non-float column references — the condition under which
      * `ORDER BY` over all of them is a TOTAL order on the output, so two rows tied
      * at a `LIMIT` cutoff are identical rows and top-`n` yields one fixed multiset.
      * `None` (and so no `LIMIT`) for every other shape.
      *
      * Ordering by the SOURCE column, not the output alias, is what the engine
      * binds: `ORDER BY` resolves against the scope at that point in the plan, so
      * `ORDER BY o0` over `SELECT t0.c0 AS o0` fails with "Unknown column 'o0'".
      * That is also why `DISTINCT` is excluded — past the dedup the scope is the
      * projection, and the source name no longer resolves — along with windows and
      * aggregates (no source column to name) and a `UNION` (the clause would bind
      * to the whole set operation, not to this arm).
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

    /** ` ORDER BY <every output column's source> LIMIT n` — empty unless a `LIMIT`
      * was generated AND [[totalOrderKeys]] still holds. */
    def topNSql: String = (limit, totalOrderKeys) match {
      case (Some(n), Some(keys)) => s" ORDER BY ${keys.mkString(", ")} LIMIT $n"
      case _ => ""
    }

    /** Whether this query actually renders a `LIMIT`. The shape-coverage guard
      * reads this rather than `limit.isDefined`: a generated limit that fails the
      * soundness condition renders nothing, and counting the field would
      * overstate the coverage. */
    def hasTopN: Boolean = topNSql.nonEmpty

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
    val keyType = rng.oneOf(KeyTypes)
    val keyDomain = sharedKeyDomain(rng.split(), keyType)
    val rel = genRelation(rng.split(), "r0", keyType, keyDomain)
    val scope = rel.schema.fields.map(f => ScopeCol("t", f.name, f.dataType)).toVector
    val pred = genPredicate(rng.split(), scope, rng.between(1, 3))
    NoRecCase(rel, pred)
  }

  // ---- relation environment generation -----------------------------------

  private val KeyTypes: Seq[DataType] = Seq(DataType.IntType, DataType.LongType, DataType.StringType)
  /** Payload (non-key) column types. `private[fuzz]` so the coverage guards can
    * assert every one of them still reaches the oracles. */
  private[fuzz] val PayloadTypes: Seq[DataType] = Seq(
    DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
    DataType.BooleanType, DataType.StringType)
  private val StringPool: IndexedSeq[String] = IndexedSeq("a", "b", "c", "d", "")
  private val DoublePool: IndexedSeq[Double] = IndexedSeq(0.0, 0.5, 1.0, 2.0, -1.0, 10.0)

  private def genEnv(rng: Rng): RelEnv = {
    val keyType = rng.oneOf(KeyTypes)
    val keyDomain = sharedKeyDomain(rng, keyType)
    val n = rng.between(2, 3)
    val relations = (0 until n).map(i => genRelation(rng.split(), s"r$i", keyType, keyDomain)).toVector
    RelEnv(relations)
  }

  /** The shared join-key value pool — small so cross-relation keys collide. */
  private def sharedKeyDomain(rng: Rng, keyType: DataType): IndexedSeq[Any] = keyType match {
    case DataType.IntType =>
      val w = rng.between(2, 4); val base = rng.oneOf(Seq(0, 1)); (0 until w).map(i => (base + i): Any)
    case DataType.LongType =>
      val w = rng.between(2, 4); val base = rng.oneOf(Seq(0L, 1L)); (0 until w).map(i => (base + i): Any)
    case DataType.StringType =>
      val w = rng.between(2, 4); StringPool.take(w).map(_.asInstanceOf[Any])
    case other => throw new IllegalArgumentException(s"unsupported key type $other")
  }

  private def genRelation(rng: Rng, name: String, keyType: DataType, keyDomain: IndexedSeq[Any]): Relation = {
    val nKeys = rng.between(1, 2)
    val nPayload = rng.between(1, 3)
    val keyFields = (0 until nKeys).map(i => Field(s"c$i", keyType))
    val payloadFields = (0 until nPayload).map(i => Field(s"c${nKeys + i}", rng.oneOf(PayloadTypes)))
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
      case 2 => rng.between(2, 10)
      case _ => rng.between(11, 24)
    }
    val rows = IndexedSeq.tabulate(numRows) { _ =>
      Array.tabulate[Any](schema.length) { c =>
        val (nullProb, domain) = specs(c)
        if (rng.bool(nullProb)) null else rng.oneOf(domain)
      }
    }
    Relation(name, DataGen.Dataset(schema, rows), keyCols)
  }

  private def payloadNullProb(rng: Rng): Double = rng.weighted((5, 0.0), (3, 0.2), (1, 0.5))

  private def payloadDomain(rng: Rng, dt: DataType): IndexedSeq[Any] = dt match {
    case DataType.IntType => val b = rng.oneOf(Seq(-1, 0, 1)); (0 until rng.between(2, 4)).map(i => (b + i): Any)
    case DataType.LongType => val b = rng.oneOf(Seq(-1L, 0L, 1L)); (0 until rng.between(2, 4)).map(i => (b + i): Any)
    case DataType.FloatType => DoublePool.take(rng.between(2, 4)).map(_.toFloat.asInstanceOf[Any])
    case DataType.DoubleType => DoublePool.take(rng.between(2, 4)).map(_.asInstanceOf[Any])
    case DataType.BooleanType => IndexedSeq[Any](true, false)
    case DataType.StringType => StringPool.take(rng.between(2, 4)).map(_.asInstanceOf[Any])
    case other => throw new IllegalArgumentException(s"unsupported payload type $other")
  }

  // ---- query generation ---------------------------------------------------

  private def genQuery(rng: Rng, env: RelEnv): MetaQuery = {
    // CTEs are scanned by the outer query like tables. A CTE referenced twice is
    // materialized once by the engine, referenced once it is inlined — both
    // covered because the outer FROM draws CTE names from the same source pool as
    // base relations (so the same CTE can be picked for two leaves / both union
    // arms). CTE bodies read only base relations, so resolution order is simple.
    val ctes = genCtes(rng, env)
    val sources = env.relations.map(_.asSource) ++ ctes.map(_.asSource)
    val q = if (rng.bool(0.2)) genUnion(rng, sources) else genSelect(rng, sources)
    q.copy(ctes = ctes)
  }

  private def genSelect(rng: Rng, sources: Vector[FromSource]): MetaQuery = {
    val from = genFrom(rng, sources)
    val scope = from.scope
    val where = if (rng.bool(0.6)) Some(genPredicate(rng, scope, rng.between(1, 3))) else None
    // A top-n query needs a core that satisfies `MetaQuery.totalOrderKeys`, which a
    // freely-generated one rarely does (a computed item, a window, or DISTINCT each
    // break it), so the shape is generated deliberately rather than hoped for.
    val topN = rng.bool(0.2) && scope.exists(c => isPartitionable(c.dataType))
    val core =
      if (topN) genTopNCore(rng, scope)
      else if (rng.bool(0.5)) genAggCore(rng, scope)
      else genProjectCore(rng, scope)
    MetaQuery(from, where, core, limit = if (topN) Some(genLimit(rng)) else None)
  }

  /** A `LIMIT` row count spanning the interesting cutoffs: none at all, one row, a
    * handful (a cut mid-batch and mid-partition), and past the end of the data
    * (the limit never binds). Mirrors [[QueryGen]]'s. */
  private def genLimit(rng: Rng): Long = rng.weighted(
    (1, 0L), (3, 1L), (4, 2L), (4, 5L), (2, 17L), (2, 1000L))

  /** The projection a `LIMIT` is sound over: 1-3 bare, type-reliable, non-float
    * columns, no DISTINCT, no window, no computed item — so the rendered
    * `ORDER BY` covers every output column and the order is total. */
  private def genTopNCore(rng: Rng, scope: Vector[ScopeCol]): ProjectCore = {
    val orderable = scope.filter(c => isPartitionable(c.dataType))
    val cols = distinctSubset(rng, orderable, rng.between(1, math.min(3, orderable.length)))
    ProjectCore(cols.zipWithIndex.map { case (c, i) => (c.ref, s"o$i") }, distinct = false)
  }

  /** A `UNION` / `UNION ALL` of two single-source arms that both project one bare
    * key column. The arms are schema-compatible by construction: every source
    * shares the env key type, so both arms output `[o0: keyType]`. UNION (distinct)
    * and UNION ALL (multiset) are both generated; TLP over the union output and the
    * dedup-vs-concat distinction are what this exercises. When the source pool
    * holds a CTE, both arms can reference it (a CTE feeding both union arms). */
  private def genUnion(rng: Rng, sources: Vector[FromSource]): MetaQuery = {
    val all = rng.bool()
    val arm1 = genUnionArm(rng, sources)
    val arm2 = genUnionArm(rng, sources)
    arm1.copy(setOp = Some((arm2, all)))
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

  /** 0-2 CTEs, each a single-relation projection of the relation's key column(s)
    * plus an optional extra column, so every CTE exposes a key-typed (joinable)
    * output. CTE bodies read only base relations (not other CTEs). */
  private def genCtes(rng: Rng, env: RelEnv): Vector[CteDef] =
    if (!rng.bool(0.35)) Vector.empty
    else (0 until rng.between(1, 2)).map(i => genCte(rng, env, s"w$i")).toVector

  private def genCte(rng: Rng, env: RelEnv, name: String): CteDef = {
    val rel = rng.oneOf(env.relations)
    val alias = "t0"
    val cols = rel.schema.fields.map(f => ScopeCol(alias, f.name, f.dataType)).toVector
    val keyType = rel.schema.fields(rel.keyCols.head).dataType
    val projKeys = distinctSubset(rng, rel.keyCols.map(cols), rng.between(1, rel.keyCols.length))
    val extra = if (rng.bool(0.5)) Vector(rng.oneOf(cols)) else Vector.empty
    val projected = projKeys ++ extra
    val items = projected.zipWithIndex.map { case (c, i) => (c.ref, s"o$i") }
    val where = if (rng.bool(0.5)) Some(genPredicate(rng, cols, rng.between(1, 2))) else None
    val body = MetaQuery(FromClause(FromLeaf(rel.name, alias, cols), Vector.empty), where,
      ProjectCore(items, distinct = rng.bool(0.2)))
    val keyCols = body.outputSchema.zipWithIndex.collect { case ((_, t), i) if t == keyType => i }.toVector
    CteDef(name, body, keyCols)
  }

  /** A left-deep FROM over the source pool (base relations + CTEs): a root source
    * plus 0-2 joins. The same source may appear under several fresh aliases (a
    * self-join, or a CTE referenced twice). */
  private def genFrom(rng: Rng, sources: Vector[FromSource]): FromClause = {
    var aliasN = 0
    def freshAlias(): String = { val a = s"t$aliasN"; aliasN += 1; a }
    def leafOf(src: FromSource): (FromLeaf, Vector[Int]) = {
      val alias = freshAlias()
      val cols = src.fields.map(f => ScopeCol(alias, f.name, f.dataType)).toVector
      (FromLeaf(src.name, alias, cols), src.keyCols)
    }

    val (root, _) = leafOf(rng.oneOf(sources))
    val nJoins = rng.weighted((2, 0), (5, 1), (2, 2))
    var scope = root.cols
    val joins = Vector.newBuilder[JoinItem]
    var i = 0
    while (i < nJoins) {
      val (leaf, keyCols) = leafOf(rng.oneOf(sources))
      val kind = rng.weighted(
        (4, JoinKind.Inner), (3, JoinKind.Left), (2, JoinKind.Right), (2, JoinKind.Full))
      val on = genJoinOn(rng, scope, leaf, keyCols)
      joins += JoinItem(kind, leaf, on)
      scope = scope ++ leaf.cols
      i += 1
    }
    FromClause(root, joins.result())
  }

  /** An ON predicate: an equi-join between a same-typed (left-scope, right-leaf)
    * column pair — preferring the right source's key columns so the join matches —
    * and an optional extra comparison conjunct. */
  private def genJoinOn(rng: Rng, leftScope: Vector[ScopeCol], rightLeaf: FromLeaf, rightKeyColIdx: Vector[Int]): Expr = {
    val rightKeyCols = rightKeyColIdx.map(i => rightLeaf.cols(i))
    // Same-typed pairs (leftScopeCol, rightLeafCol), keys first for match likelihood.
    val keyPairs = for {
      l <- leftScope if l.dataType == rightKeyCols.headOption.map(_.dataType).getOrElse(DataType.NullType)
      r <- rightKeyCols if r.dataType == l.dataType
    } yield (l, r)
    val anyPairs = for { l <- leftScope; r <- rightLeaf.cols if r.dataType == l.dataType } yield (l, r)
    val pairs = if (keyPairs.nonEmpty) keyPairs else anyPairs
    if (pairs.isEmpty) {
      // No comparable columns — degenerate to a TRUE (cross) join.
      LitExpr(true, DataType.BooleanType)
    } else {
      val (l, r) = rng.oneOf(pairs)
      val equi: Expr = BinOpExpr("=", l.ref, r.ref, DataType.BooleanType)
      if (rng.bool(0.25)) {
        val extra = genAtom(rng, leftScope ++ rightLeaf.cols, 1)
        BinOpExpr("AND", equi, extra, DataType.BooleanType)
      } else equi
    }
  }

  private def genProjectCore(rng: Rng, scope: Vector[ScopeCol]): ProjectCore = {
    val distinct = rng.bool(0.4)
    // Always project a couple of bare columns (TLP needs a non-float output
    // column to partition on); occasionally add a shallow computed column.
    val bareCount = rng.between(1, math.min(3, scope.length))
    val bare = distinctSubset(rng, scope, bareCount)
    val bareItems: Vector[(Expr, String)] = bare.zipWithIndex.map { case (sc, i) => (sc.ref, s"o$i") }
    val computed: Vector[(Expr, String)] =
      if (rng.bool(0.4)) {
        val t = rng.oneOf(Seq(DataType.IntType, DataType.LongType, DataType.StringType, DataType.DoubleType))
        Vector((genValue(rng, scope, t, 2), s"o${bareItems.length}"))
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

  /** One `<fn>() OVER (PARTITION BY ... ORDER BY ... [frame])` column. Aggregate
    * arguments and LAG/LEAD targets are bare columns so the output type is exact.
    * The OVER parentheses are function syntax, not grouping parens, so this stays
    * paren-free-safe. */
  private def genWindowItem(rng: Rng, scope: Vector[ScopeCol], idx: Int): WinItem = {
    val name = s"o$idx"
    val partCols = distinctSubset(rng, scope, rng.between(0, math.min(2, scope.length)))
    val orderCols = distinctSubset(rng, scope, rng.between(1, math.min(2, scope.length)))
    val partBy = if (partCols.isEmpty) "" else "PARTITION BY " + partCols.map(_.qualified).mkString(", ")
    val orderBy = "ORDER BY " + orderCols.map(c => s"${c.qualified} ${if (rng.bool()) "ASC" else "DESC"}").mkString(", ")
    def over(body: String): String = s"OVER (${Seq(partBy, body).filter(_.nonEmpty).mkString(" ")})"

    val numeric = scope.filter(c => DataType.isNumeric(c.dataType))
    val choices = scala.collection.mutable.ArrayBuffer.empty[(Int, () => WinItem)]
    // Tie-sensitive: the value depends on which of two tied rows comes first.
    choices += ((3, () => WinItem(s"ROW_NUMBER() ${over(orderBy)}", name, DataType.LongType, tieSensitive = true)))
    choices += ((2, () => { val c = rng.oneOf(scope)
      WinItem(s"${rng.oneOf(Seq("LAG", "LEAD"))}(${c.qualified}) ${over(orderBy)}", name, c.dataType, tieSensitive = true) }))
    // Tie-insensitive: peers share a rank, and a no-ORDER-BY aggregate window
    // frames the whole partition. These are the shapes the mode-agreement oracle
    // may run, which is how the sharded window path gets differential coverage.
    choices += ((3, () => WinItem(s"${rng.oneOf(Seq("RANK", "DENSE_RANK"))}() ${over(orderBy)}", name, DataType.LongType, tieSensitive = false)))
    choices += ((3, () => WinItem(s"COUNT(*) ${over("")}", name, DataType.LongType, tieSensitive = false)))
    if (numeric.nonEmpty) {
      choices += ((2, () => { val c = rng.oneOf(numeric)
        WinItem(s"SUM(${c.qualified}) ${over(orderBy)}", name, sumType(c.dataType), tieSensitive = true) }))
      choices += ((1, () => { val c = rng.oneOf(numeric)
        WinItem(s"AVG(${c.qualified}) ${over(orderBy)}", name, DataType.DoubleType, tieSensitive = true) }))
      choices += ((1, () => { val c = rng.oneOf(numeric)
        WinItem(s"SUM(${c.qualified}) OVER (${Seq(partBy, orderBy, frame(rng)).filter(_.nonEmpty).mkString(" ")})",
          name, sumType(c.dataType), tieSensitive = true) }))
      // Whole-partition aggregate windows: no ORDER BY, so no frame to split peers.
      choices += ((2, () => { val c = rng.oneOf(numeric)
        WinItem(s"SUM(${c.qualified}) ${over("")}", name, sumType(c.dataType), tieSensitive = false) }))
      choices += ((1, () => { val c = rng.oneOf(numeric)
        WinItem(s"AVG(${c.qualified}) ${over("")}", name, DataType.DoubleType, tieSensitive = false) }))
    }
    choices += ((1, () => { val c = rng.oneOf(scope)
      WinItem(s"${rng.oneOf(Seq("MIN", "MAX"))}(${c.qualified}) ${over(orderBy)}", name, c.dataType, tieSensitive = true) }))
    choices += ((2, () => { val c = rng.oneOf(scope)
      WinItem(s"${rng.oneOf(Seq("MIN", "MAX"))}(${c.qualified}) ${over("")}", name, c.dataType, tieSensitive = false) }))
    rng.weighted(choices.toSeq: _*)()
  }

  /** A `ROWS BETWEEN ... AND ...` frame clause. */
  private def frame(rng: Rng): String =
    s"ROWS BETWEEN ${rng.oneOf(Seq("UNBOUNDED PRECEDING", "1 PRECEDING", "0 PRECEDING"))} " +
      s"AND ${rng.oneOf(Seq("CURRENT ROW", "1 FOLLOWING", "UNBOUNDED FOLLOWING"))}"

  private def sumType(dt: DataType): DataType = dt match {
    case DataType.IntType | DataType.LongType => DataType.LongType
    case _ => DataType.DoubleType
  }

  private def genAggCore(rng: Rng, scope: Vector[ScopeCol]): AggCore = {
    // Group keys: bare non-float scope columns (float group keys are legal but a
    // poor TLP partition source). A global aggregate (no keys) is allowed too.
    val groupable = scope.filter(c => isPartitionable(c.dataType))
    val keys: Vector[ScopeCol] =
      if (groupable.nonEmpty && rng.bool(0.8))
        distinctSubset(rng, groupable, rng.between(1, math.min(2, groupable.length)))
      else Vector.empty
    val groupKeys: Vector[(Expr, String)] = keys.zipWithIndex.map { case (sc, i) => (sc.ref, s"o$i") }
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
  private val ArithOps = Seq("+", "-", "*", "/", "%")
  private val RelOps = Seq("<", "<=", ">", ">=", "=", "<>")

  private def leafProb(depth: Int): Double = if (depth <= 1) 0.6 else 0.35

  /** A non-boolean value expression of `target` over `scope`. */
  private def genValue(rng: Rng, scope: Vector[ScopeCol], target: DataType, depth: Int): Expr = {
    if (depth <= 0 || rng.bool(leafProb(depth))) genLeafValue(rng, scope, target)
    else if (target == DataType.StringType)
      BinOpExpr("||", genValue(rng, scope, DataType.StringType, depth - 1),
        genValue(rng, scope, DataType.StringType, depth - 1), DataType.StringType)
    else rng.weighted(
      (4, () => BinOpExpr(rng.oneOf(ArithOps), genValue(rng, scope, rng.oneOf(Numerics), depth - 1),
        genValue(rng, scope, rng.oneOf(Numerics), depth - 1), target)),
      (2, () => FuncExpr("COALESCE", Seq.fill(rng.between(1, 3))(genValue(rng, scope, target, depth - 1)), target)),
      (1, () => FuncExpr("ABS", Seq(genValue(rng, scope, target, depth - 1)), target)))()
  }

  private def genLeafValue(rng: Rng, scope: Vector[ScopeCol], target: DataType): Expr = {
    val cols = scope.filter(_.dataType == target)
    if (cols.nonEmpty && rng.bool(0.8)) rng.oneOf(cols).ref
    else if (rng.bool(0.15)) LitExpr(null, target)
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
    val options = scala.collection.mutable.ArrayBuffer.empty[(Int, () => Expr)]
    options += ((4, () => BinOpExpr(rng.oneOf(RelOps), sub(rng.oneOf(Numerics)), sub(rng.oneOf(Numerics)), DataType.BooleanType)))
    if (strCols.nonEmpty)
      options += ((2, () => BinOpExpr(rng.oneOf(RelOps), sub(DataType.StringType), sub(DataType.StringType), DataType.BooleanType)))
    options += ((2, () => IsNullExpr(sub(rng.oneOf(Numerics :+ DataType.StringType)), rng.bool())))
    if (strCols.nonEmpty)
      options += ((1, () => LikeExpr(rng.oneOf(strCols).ref, LitExpr(rng.oneOf(Seq("%", "a%", "%a%", "_")), DataType.StringType), rng.bool())))
    if (boolCols.nonEmpty) options += ((2, () => rng.oneOf(boolCols).ref))
    options += ((1, () => LitExpr(rng.bool(), DataType.BooleanType)))
    rng.weighted(options.toSeq: _*)()
  }

  private def genLit(rng: Rng, dt: DataType): Any = dt match {
    case DataType.IntType => rng.between(0, 3): Any
    case DataType.LongType => rng.between(0, 3).toLong: Any
    case DataType.FloatType => rng.oneOf(DoublePool).toFloat: Any
    case DataType.DoubleType => rng.oneOf(DoublePool): Any
    case DataType.BooleanType => rng.bool(): Any
    case DataType.StringType => rng.oneOf(StringPool): Any
    case other => throw new IllegalArgumentException(s"genLit: unsupported $other")
  }

  // ---- TLP partition-predicate selection ---------------------------------

  /** Non-float, comparison-clean output types eligible as TLP partition keys. */
  private def isPartitionable(dt: DataType): Boolean = dt match {
    case DataType.IntType | DataType.LongType | DataType.StringType | DataType.BooleanType => true
    case _ => false
  }
  private def isOrdered(dt: DataType): Boolean = dt match {
    case DataType.IntType | DataType.LongType | DataType.StringType => true
    case _ => false
  }

  /** Choose a TLP partition predicate over the query's type-reliable, non-float
    * output columns ([[QueryCore.tlpCandidates]]), or `None` when there are none. */
  private def genTlpPred(rng: Rng, cand: Vector[(String, DataType)]): Option[TlpPred] = {
    if (cand.isEmpty) return None
    val boolCols = cand.collect { case (n, DataType.BooleanType) => n }
    val orderedCols = cand.filter { case (_, t) => isOrdered(t) }
    val choices = scala.collection.mutable.ArrayBuffer.empty[(Int, () => TlpPred)]
    if (boolCols.nonEmpty) choices += ((2, () => TlpBool(rng.oneOf(boolCols))))
    if (orderedCols.nonEmpty) {
      choices += ((2, () => {
        val (n, t) = rng.oneOf(orderedCols)
        val (op, neg) = rng.oneOf(cmpPairs)
        TlpCmpLit(n, op, neg, SqlRender.expr(LitExpr(genLit(rng, t), t)))
      }))
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
