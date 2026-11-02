package com.transformer.sql.plan

import com.transformer.core.{Catalog, ColumnarBatch, DataType, Field, MaterializedView, Schema}
import com.transformer.sql.parse.SqlParser

import net.sf.jsqlparser.expression.{
  AllValue, AnalyticExpression, BooleanValue, CaseExpression, CastExpression, DateValue, DoubleValue,
  Expression, Function, LongValue, NotExpression, NullValue,
  SignedExpression, StringValue, TimeKeyExpression, TimestampValue, TimeValue, TrimFunction,
  WhenClause, WindowElement, WindowOffset
}
import net.sf.jsqlparser.expression.operators.arithmetic.{
  Addition, Concat, Division, Modulo, Multiplication, Subtraction
}
import net.sf.jsqlparser.expression.operators.conditional.{AndExpression, OrExpression}
import net.sf.jsqlparser.expression.operators.relational.{
  Between, EqualsTo, ExpressionList, GreaterThan, GreaterThanEquals, InExpression,
  IsNullExpression, LikeExpression, MinorThan, MinorThanEquals, NotEqualsTo,
  ParenthesedExpressionList
}
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select._

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.DynamicVariable
import scala.util.control.NonFatal

/** A top-level CTE definition collected during build: its name, the unique
  * [[PendingCteView]] every reference scan points at, and the bound body plan.
  * [[com.transformer.sql.exec.CteResolver]] decides per def whether to inline
  * the body or materialize it once (when referenced `>= 2` times). */
final case class CteDef(name: String, pending: PendingCteView, body: LogicalPlan)

/** Result of [[LogicalBuilder.build]]: the main query plan plus the ordered
  * list of outermost-scope CTE definitions. `ctes` is in declaration order;
  * because a CTE can only reference earlier siblings (recursion is rejected),
  * resolving them left-to-right means every dependency is already resolved.
  * For a query with no top-level `WITH`, `ctes` is empty and `main` is
  * byte-identical to the pre-CTE-materialization plan. */
final case class BuiltQuery(main: LogicalPlan, ctes: Seq[CteDef])

/** Builds a bound [[LogicalPlan]] from a SQL string, given a [[Catalog]]. */
object LogicalBuilder {

  type Sources = Seq[(Option[String], Schema)]

  /** A name in CTE scope resolves either to a materialization-candidate
    * (outermost-scope) CTE — referenced via its [[PendingCteView]] so the
    * resolver can count references and choose inline-vs-materialize — or to an
    * already-inlined nested CTE body (the historic behaviour, preserved for
    * CTEs declared inside another CTE body). */
  private sealed trait CteScopeEntry
  private final case class PendingCte(name: String, pending: PendingCteView) extends CteScopeEntry
  private final case class InlineCte(plan: LogicalPlan) extends CteScopeEntry

  /** The instant CURRENT_DATE and CURRENT_TIMESTAMP read while [[build]] binds
    * a statement. There is one per statement, as SQL specifies, so every row,
    * every partition and every reference to a CTE sees the same value. */
  private val statementStart = new DynamicVariable[Instant](null)

  /** The SELECT list each PlainSelect of the statement being bound expanded
    * to, an item per output column with each `*` written out as its columns,
    * which a UNION reads its arms' written values from ([[unifyArms]]). */
  private val expandedSelects = new DynamicVariable[java.util.IdentityHashMap[PlainSelect, Seq[Expression]]](null)

  def build(sql: String, catalog: Catalog): BuiltQuery = {
    val select = SqlParser.parseSelect(sql)
    // Everything below reads the AST through the getters it knows about, so a
    // clause carried by any other getter would vanish without an error. The
    // pass rejects those first; see UnsupportedClauses.
    UnsupportedClauses.reject(select)
    // A TimestampVector holds microseconds, so a clock literal truncated to
    // them equals a stored copy of itself on the row path too.
    statementStart.withValue(Instant.now().truncatedTo(ChronoUnit.MICROS)) {
      expandedSelects.withValue(new java.util.IdentityHashMap[PlainSelect, Seq[Expression]]()) {
        val collector = mutable.ArrayBuffer.empty[CteDef]
        val main = PlanValidator.bound(buildAnySelect(select, catalog, Map.empty, Some(collector)))
        collector.foreach(cte => PlanValidator.bound(cte.body))
        BuiltQuery(main, collector.toSeq)
      }
    }
  }

  /** Dispatch over the SELECT subtype: a single `PlainSelect`, a UNION /
    * INTERSECT / EXCEPT [[SetOperationList]], or a parenthesized wrapper.
    *
    * CTE definitions in the WITH clause are extracted here and accumulated
    * into `cteScope` before the inner query body is planned. Each CTE is
    * built in order so later CTEs can reference earlier ones in the same
    * WITH clause. The resolved scope is threaded into `fromItem` so that any
    * reference to a CTE name in FROM / JOIN is satisfied by the pre-built
    * plan rather than a catalog lookup. One WITH defines a name once, as
    * standard SQL requires (a second definition used to replace the first);
    * a WITH inside a CTE body may reuse an outer name, which it shadows there.
    *
    * `collector` distinguishes the outermost WITH clause (where defs become
    * materialization candidates — `Some(buf)`, each emitting a [[PendingCteView]]
    * placeholder the resolver later inlines or materializes) from nested WITH
    * clauses inside a CTE body (`None` — inlined unconditionally, the historic
    * behaviour). Only the top-level `build` call passes `Some`; every recursive
    * call (CTE bodies, set-operation arms, parenthesized inner) passes `None`.
    */
  private def buildAnySelect(
      sel: Select,
      catalog: Catalog,
      outerCteScope: Map[String, CteScopeEntry],
      collector: Option[mutable.ArrayBuffer[CteDef]]): LogicalPlan = {
    val withItems = Option(sel.getWithItemsList).map(_.asScala.toSeq).getOrElse(Nil)
    val _ = withItems.map(item => unquoted(item.getAlias.getName)).foldLeft(Set.empty[String]) { (defined, name) =>
      if (defined(name.toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException(s"WITH defines '$name' twice; give each query its own name")
      defined + name.toLowerCase(Locale.ROOT)
    }
    val cteScope: Map[String, CteScopeEntry] = withItems.foldLeft(outerCteScope) { (scope, item) =>
      if (item.isRecursive)
        throw new IllegalArgumentException("Recursive CTEs (WITH RECURSIVE) are not supported")
      val cteName = unquoted(item.getAlias.getName)
      // A CTE body is always a nested scope: its own WITH (if any) inlines.
      val cteBody = buildAnySelect(item.getSelect, catalog, scope, None)
      // If the CTE has an explicit column alias list — WITH cte(a, b) AS (...) —
      // wrap the body in a rename projection so the chosen names take effect.
      val colAliases = Option(item.getWithItemList).map(_.asScala.toSeq).getOrElse(Nil)
      val ctePlan: LogicalPlan = if (colAliases.isEmpty) cteBody else {
        if (colAliases.size != cteBody.outputSchema.length)
          throw new IllegalArgumentException(
            s"CTE '$cteName' declares ${colAliases.size} column alias(es) but its body " +
              s"produces ${cteBody.outputSchema.length} column(s)")
        val renames: Seq[(Expr, String)] = cteBody.outputSchema.fields.zipWithIndex.map { case (f, i) =>
          val alias = unquoted(colAliases(i).getExpression.asInstanceOf[Column].getColumnName)
          (ColRefExpr(i, f.name, f.dataType): Expr, alias)
        }
        LogicalProject(cteBody, renames)
      }
      collector match {
        case Some(buf) =>
          // Outermost scope: emit a placeholder the resolver resolves later.
          val pending = new PendingCteView(cteName, ctePlan.outputSchema)
          buf += CteDef(cteName, pending, ctePlan)
          scope + (cteName.toLowerCase(Locale.ROOT) -> PendingCte(cteName, pending))
        case None =>
          // Nested scope: inline directly, as before.
          scope + (cteName.toLowerCase(Locale.ROOT) -> InlineCte(ctePlan))
      }
    }

    sel match {
      case ps: PlainSelect => buildSelect(ps, catalog, cteScope)
      case sol: SetOperationList => buildSetOperationList(sol, catalog, cteScope)
      case wrap: ParenthesedSelect => buildAnySelect(wrap.getSelect, catalog, cteScope, None)
      case other => throw new IllegalArgumentException(UnsupportedClauses.refusal(other))
    }
  }

  /** Fold a `SetOperationList` into a left-associative chain of
    * [[LogicalUnion]]s, then apply any outer ORDER BY / OFFSET / LIMIT.
    *
    * JSqlParser attaches a trailing `ORDER BY` / `LIMIT` / `OFFSET` to the
    * `SetOperationList` when the tail starts with `ORDER BY`, and otherwise to
    * the *last arm* (`... UNION ALL SELECT a FROM u LIMIT 2 OFFSET 1`), and it
    * can split one tail across both (`OFFSET 1 ROWS FETCH NEXT 2 ROWS ONLY`
    * leaves the OFFSET on the arm). In standard SQL (Postgres / BigQuery /
    * Snowflake) every one of those clauses applies to the union as a whole, so
    * we hoist each one — unless the user disambiguated by wrapping the last
    * arm in parens (then it's a `ParenthesedSelect`, our `case ps:
    * PlainSelect` doesn't match, and the clause stays inside the arm). A
    * clause both carry was written twice after the last arm, which JSqlParser
    * splits between them (`... SELECT a FROM u LIMIT 1 LIMIT 2` gives the arm
    * `LIMIT 1`), and is refused.
    *
    * Each [[UnionOp]] contributes its own `ALL` flag; the planner wraps
    * non-ALL unions with a [[LogicalDistinct]] downstream. Each column takes
    * one type across every arm ([[unifyArms]]). INTERSECT / EXCEPT / MINUS
    * aren't implemented in v1 — reject with a clear message instead of
    * producing a wrong plan.
    */
  private def buildSetOperationList(sol: SetOperationList, catalog: Catalog, cteScope: Map[String, CteScopeEntry]): LogicalPlan = {
    val selects = sol.getSelects.asScala.toVector
    val ops = sol.getOperations.asScala.toVector
    if (selects.size < 2 || ops.size != selects.size - 1) {
      throw new IllegalArgumentException(
        s"Malformed set operation list: ${selects.size} selects, ${ops.size} operations")
    }

    // Detect-and-strip the hoistable trailing clauses on the last bare arm.
    // We mutate the AST in place; nothing downstream holds an earlier snapshot.
    val lastArm = selects.last
    val (hoistedOrderBy, hoistedLimit, hoistedOffset) = lastArm match {
      case ps: PlainSelect =>
        def hoist[C](clause: String, onArm: Option[C], onList: Option[C]): Option[C] = {
          if (onArm.isDefined && onList.isDefined) throw SqlParser.writtenTwice(clause)
          onArm
        }
        val ob = hoist("ORDER BY", Option(ps.getOrderByElements).filter(!_.isEmpty),
          Option(sol.getOrderByElements).filter(!_.isEmpty))
        val li = hoist("LIMIT", Option(ps.getLimit), Option(sol.getLimit))
        val off = hoist("OFFSET", Option(ps.getOffset), Option(sol.getOffset))
        if (ob.isDefined) ps.setOrderByElements(null)
        if (li.isDefined) ps.setLimit(null)
        if (off.isDefined) ps.setOffset(null)
        (ob, li, off)
      case _ => (None, None, None)
    }

    // Before the last arm such a clause has no meaning SQL gives it: the
    // grammar puts ORDER BY, LIMIT, OFFSET and FETCH after the last arm, for
    // the whole union, and inside parentheses for one arm. It was applied to
    // its arm, as Postgres, MySQL and SQLite all refuse to guess.
    selects.init.foreach {
      case ps: PlainSelect =>
        val clause =
          if (Option(ps.getOrderByElements).exists(!_.isEmpty)) Some("ORDER BY")
          else if (ps.getLimit != null) Some("LIMIT")
          else if (ps.getOffset != null) Some("OFFSET")
          else if (ps.getFetch != null) Some("FETCH")
          else None
        clause.foreach(c => throw new IllegalArgumentException(
          s"$c before the last arm of a UNION is not supported; put the arm in parentheses, (SELECT ... $c ...), " +
            "to apply it to that arm, or write it after the last arm to apply it to the whole union"))
      case _ => ()
    }

    val unions: Seq[UnionOp] = ops.map {
      case u: UnionOp => u
      case other => throw new IllegalArgumentException(UnsupportedClauses.refusal(other))
    }
    val arms = selects.map(buildAnySelect(_, catalog, cteScope, None))
    arms.tail.foreach { arm =>
      if (arm.outputSchema.length != arms.head.outputSchema.length)
        throw new IllegalArgumentException(
          s"UNION arms have different column counts: ${arms.head.outputSchema.length} vs ${arm.outputSchema.length}")
    }
    val typed = unifyArms(arms, selects)
    // JSqlParser sets `all=true` only for explicit UNION ALL; bare UNION (and
    // the rarely-used UNION DISTINCT) both dedup. Each run of one kind binds as
    // a balanced tree of it: bound left-deep, a UNION nested as deep as it had
    // arms, which overflowed the optimizer's stack past a few thousand and
    // took fifteen seconds at two thousand. Within a run the operation is
    // associative, and the arms keep their order, so the tree answers as the
    // chain does; a run's first operand is everything before it.
    var union = typed.head
    var run = 0
    while (run < unions.size) {
      val all = unions(run).isAll
      var end = run
      while (end < unions.size && unions(end).isAll == all) end += 1
      union = balancedUnion(union +: typed.slice(run + 1, end + 1), all)
      run = end
    }

    // ORDER BY / LIMIT bind against the union's output (column names come from
    // the leftmost arm — that's what `LogicalUnion.outputSchema` exposes), so
    // an ORDER BY ordinal is a position in that schema.
    val orderBy = Option(sol.getOrderByElements).filter(!_.isEmpty).orElse(hoistedOrderBy)
    val afterSort: LogicalPlan = orderBy match {
      case Some(list) =>
        LogicalSort(union, list.asScala.map(bindOrderKey(_, union.outputSchema)).toSeq)
      case None => union
    }

    // FETCH needs no hoist: JSqlParser always parks it on the list itself.
    applyOffsetAndLimit(afterSort,
      Option(sol.getLimit).orElse(hoistedLimit), Option(sol.getOffset).orElse(hoistedOffset),
      Option(sol.getFetch), top = None)
  }

  /** `plans` under one kind of UNION, as a balanced tree. */
  private def balancedUnion(plans: Seq[LogicalPlan], all: Boolean): LogicalPlan =
    if (plans.size == 1) plans.head
    else {
      val (left, right) = plans.splitAt(plans.size / 2)
      LogicalUnion(balancedUnion(left, all), balancedUnion(right, all), all)
    }

  /** The arms of a UNION with each column under the one type its values have
    * in every arm ([[Analyzer.branches]]), so the union holds what its schema,
    * the first arm's, declares. An arm that ends in its SELECT list has that
    * list retyped, so a string literal or a number written there is read as
    * the type it meets, as in a CASE, and beside a DECIMAL a number is the
    * decimal it is written as ([[decimalConstant]]); any other arm is wrapped
    * in a projection that casts its output. An arm whose columns keep their
    * types is kept as it is. `written` holds each arm as parsed. */
  private def unifyArms(arms: Seq[LogicalPlan], written: Seq[Select]): Seq[LogicalPlan] = {
    val offered: Seq[Seq[Expr]] = arms.map {
      case LogicalProject(_, projections) => projections.map(_._1)
      case arm => arm.outputSchema.fields.zipWithIndex.map { case (f, i) => ColRefExpr(i, f.name, f.dataType) }
    }
    // The SELECT list each arm that ends in one wrote, an item per column.
    def plain(s: Select): Option[PlainSelect] = s match {
      case ps: PlainSelect => Some(ps)
      case p: ParenthesedSelect => plain(p.getSelect)
      case _ => None
    }
    // A `*` among them is written out as its columns, so a number after one
    // is still read as written: `SELECT *, 0.1000000000000000000001` lost its
    // digits beside a DECIMAL arm, read through a DOUBLE.
    val items: Seq[Option[Seq[Expression]]] = arms.zip(written).map {
      case (LogicalProject(_, projections), arm) =>
        plain(arm).flatMap(ps => Option(expandedSelects.value).flatMap(selects => Option(selects.get(ps))))
          .filter(_.size == projections.size)
      case _ => None
    }
    val names = arms.head.outputSchema.fieldNames
    val columns: Seq[Seq[Expr]] = names.indices.map { c =>
      val values = offered.map(_(c))
      val beside =
        if (!values.exists(_.dataType.isInstanceOf[DataType.DecimalType])) values
        else values.zip(items).map { case (v, list) => list.flatMap(l => decimalConstant(l(c))).getOrElse(v) }
      Analyzer.branches(s"UNION column ${c + 1} (${names(c)})", beside)._1
    }
    arms.indices.map { a =>
      val retyped = columns.map(_(a))
      arms(a) match {
        case arm if retyped == offered(a) => arm
        case LogicalProject(child, projections) =>
          LogicalProject(child, projections.map(_._2).zip(retyped).map { case (n, e) => (e, n) })
        case arm => LogicalProject(arm, arm.outputSchema.fieldNames.zip(retyped).map { case (n, e) => (e, n) })
      }
    }
  }

  /** OFFSET, then the row count, in the order SQL applies them: skip `offset`
    * rows of the whole result, then keep `count` of what is left. MySQL's
    * `LIMIT offset, count` carries its offset inside the Limit; the standard
    * `OFFSET n [ROWS]` arrives as a clause of its own. The count has three
    * spellings, of which a query may use one: `LIMIT n`, the standard
    * `FETCH FIRST n ROWS ONLY`, and T-SQL's `TOP n`. */
  private def applyOffsetAndLimit(
      plan: LogicalPlan,
      limit: Option[Limit],
      offset: Option[Offset],
      fetch: Option[Fetch],
      top: Option[Top]): LogicalPlan = {
    val limitOffset = limit.flatMap(l => Option(l.getOffset))
    if (limitOffset.isDefined && offset.isDefined)
      throw new IllegalArgumentException("OFFSET given twice: as LIMIT offset, count and as OFFSET")
    val skip = offset.map(o => rowCount("OFFSET", o.getOffset))
      .orElse(limitOffset.map(rowCount("OFFSET", _)))
    // T-SQL refuses this pair too: nothing says whether TOP counts before or
    // after the skip.
    if (top.isDefined && skip.isDefined)
      throw new IllegalArgumentException("TOP n with OFFSET is not supported; write LIMIT n OFFSET m")
    val counts: Seq[(String, Long)] =
      limit.flatMap(l => Option(l.getRowCount)).map(c => "LIMIT" -> rowCount("LIMIT", c)).toSeq ++
        // `FETCH FIRST ROW ONLY` leaves the count out, and it means one.
        fetch.map(f => "FETCH FIRST" -> Option(f.getExpression).fold(1L)(rowCount("FETCH FIRST", _))) ++
        top.map(t => "TOP" -> rowCount("TOP", t.getExpression))
    if (counts.size > 1)
      throw new IllegalArgumentException(s"${counts.map(_._1).mkString(" and ")} both limit the rows; write one")
    val afterOffset = skip.filter(_ > 0).fold(plan)(LogicalOffset(plan, _))
    counts.headOption.fold(afterOffset) { case (_, n) => LogicalLimit(afterOffset, n) }
  }

  /** A row count, as LIMIT, OFFSET and a ROWS frame's offset are: a
    * non-negative integer literal a BIGINT holds. JSqlParser parses a negative
    * one as a minus over the number. */
  private def rowCount(clause: String, e: Expression): Long = e match {
    case IntegerLiteral(v) if v.signum >= 0 =>
      if (v.bitLength < 64) v.longValue
      else throw new IllegalArgumentException(
        s"$clause ${Decimals.shownNumber(v.toString)} is larger than the largest BIGINT, ${Long.MaxValue}")
    case _ => throw new IllegalArgumentException(s"$clause must be a non-negative integer literal")
  }

  // ---------------------------------------------------------------------------
  // SELECT / FROM
  // ---------------------------------------------------------------------------

  private def buildSelect(ps: PlainSelect, catalog: Catalog, cteScope: Map[String, CteScopeEntry]): LogicalPlan = {
    val (from, sources) = buildFromAndJoins(ps, catalog, cteScope)

    // WHERE
    val afterWhere: LogicalPlan = Option(ps.getWhere) match {
      case Some(w) => LogicalFilter(from, Analyzer.condition(bindExpr(w, sources), "WHERE"))
      case None => from
    }

    // Expand SELECT items into bound expressions / names, with special handling for `*`.
    val rawSelectItems: Seq[SelectItem[_ <: Expression]] =
      Option(ps.getSelectItems).map(_.asScala.toSeq).getOrElse(Nil)
        .asInstanceOf[Seq[SelectItem[_ <: Expression]]]
    val expandedItems: Seq[(Expression, String)] = rawSelectItems.flatMap { si =>
      val expr: Expression = si.getExpression
      expr match {
        // AllTableColumns is a subtype of AllColumns; match it first.
        case atc: net.sf.jsqlparser.statement.select.AllTableColumns =>
          val tableName = unquoted(atc.getTable.getName)
          sources.find(_._1.exists(_.equalsIgnoreCase(tableName))).map(_._2) match {
            case Some(schema) => schema.fields.map(f => (columnNamed(Some(tableName), f.name), f.name))
            case None =>
              throw new IllegalArgumentException(s"Unknown table in '$tableName.*'")
          }
        case _: net.sf.jsqlparser.statement.select.AllColumns if ps.getFromItem == null =>
          // There is nothing to expand, and quietly dropping the star would
          // turn `SELECT *, 1 AS x` into `SELECT 1 AS x`.
          throw new IllegalArgumentException("SELECT * needs a FROM clause")
        case _: net.sf.jsqlparser.statement.select.AllColumns =>
          sources.flatMap { case (alias, schema) => schema.fields.map(f => (columnNamed(alias, f.name), f.name)) }
        case other =>
          val alias = Option(si.getAlias).map(aliasName).getOrElse(deriveAlias(other))
          Seq((other, alias))
      }
    }

    Option(expandedSelects.value).foreach(_.put(ps, expandedItems.map(_._1)))

    // `GROUP BY ()` groups by no key: every row is one group, as with an
    // aggregate and no GROUP BY. It used to be dropped, so `SELECT 1 FROM t
    // GROUP BY ()` returned a row for each row of t.
    val hasGroupBy = ps.getGroupBy != null
    // An aggregate anywhere outside a FROM makes the query one: in the SELECT
    // list, inside a window function's arguments or keys (`SUM(COUNT(*))
    // OVER ()`), and in ORDER BY (`SELECT 1 FROM t ORDER BY COUNT(*)`), as in
    // Postgres; the last two were refused as aggregates out of place.
    val containsAgg = expandedItems.exists { case (e, _) => containsAggregate(e) } ||
      Option(ps.getOrderByElements).exists(_.asScala.exists(obe => containsAggregate(obe.getExpression)))
    // A HAVING makes the query an aggregate too, over one group when there is
    // no GROUP BY. Only the aggregate path reads it, so without this term
    // `SELECT 1 AS x FROM t HAVING COUNT(*) > 10` returned every row.
    val isAggregation = hasGroupBy || containsAgg || ps.getHaving != null

    // Stage 1: aggregation. `binderFactory` produces a binder parameterized by a
    // window resolver and an output-column resolver — we call it with the empty
    // ones (for window-spec collection), with the window resolver (for the
    // final projections) and with both (for ORDER BY).
    val (preWindow, binderFactory): (LogicalPlan, BinderFactory) =
      if (isAggregation) buildAggregation(afterWhere, sources, ps, expandedItems)
      else {
        val factory: BinderFactory = (wr, outputs) => e => bindExprWithAggs(e, sources, {
          case c: Column => outputs(c)
          case _ => None
        }, wr)
        (afterWhere, factory)
      }

    val preWindowBinder: Expression => Expr = binderFactory(_ => None, _ => None)

    // Stage 2: collect every distinct `OVER (...)` referenced by SELECT items
    // or ORDER BY. The window-function call's internal expressions (partition
    // keys, order keys, function args) bind against the post-aggregation plan
    // via `preWindowBinder`.
    // As an aggregate is, each window function is computed once however it
    // is spelled, each spelling finding its column by its text.
    val windowDefs = mutable.ArrayBuffer.empty[WindowDef]
    val windowOf = mutable.HashMap.empty[String, Int]
    def collectWindows(e: Expression): Unit = e match {
      case ae: AnalyticExpression =>
        val key = canonicalSql(ae)
        if (!windowOf.contains(key)) {
          val wd = bindAnalytic(ae, preWindowBinder)
          windowOf(key) = windowDefs.indexWhere(_.copy(outputName = "") == wd) match {
            case -1 => windowDefs += wd.copy(outputName = s"_win${windowDefs.size}"); windowDefs.size - 1
            case i => i
          }
        }
      case _ => childExpressions(e).foreach(collectWindows)
    }
    expandedItems.foreach { case (e, _) => collectWindows(e) }
    Option(ps.getOrderByElements).foreach(_.asScala.foreach(obe => collectWindows(obe.getExpression)))

    val (preProject, windowResolver): (LogicalPlan, AnalyticExpression => Option[Expr]) =
      if (windowDefs.isEmpty) (preWindow, _ => None)
      else {
        val baseLen = preWindow.outputSchema.length
        val windowResolver: AnalyticExpression => Option[Expr] = ae =>
          windowOf.get(canonicalSql(ae)).map { i =>
            val wd = windowDefs(i)
            ColRefExpr(baseLen + i, wd.outputName, wd.fn.resultType)
          }
        (LogicalWindow(preWindow, windowDefs.toSeq), windowResolver)
      }
    val exprBinder: Expression => Expr = binderFactory(windowResolver, _ => None)

    val projections: Seq[(Expr, String)] = expandedItems.map { case (e, name) =>
      (exprBinder(e), name)
    }
    val projected: LogicalPlan = LogicalProject(preProject, projections)

    val hasDistinct: Boolean = ps.getDistinct != null
    val orderByList: Option[Seq[OrderByElement]] =
      Option(ps.getOrderByElements).map(_.asScala.toSeq).filter(_.nonEmpty)

    val afterSort: LogicalPlan = (orderByList, hasDistinct) match {
      // Without DISTINCT a key is an output column or an expression over the
      // input ([[OrderScope]]), and the sort runs over the projection.
      case (Some(list), false) =>
        val scope = new OrderScope(expandedItems, projections, sources)
        sortOverProjection(preProject, projections, list,
          scope.key(_, binderFactory(windowResolver, scope.outputColumn)))
      // With DISTINCT we hoist Sort above Distinct so per-shard dedup can't
      // scramble the user's requested order, and bind the keys against the
      // post-project schema (SQL's restriction that ORDER BY of a DISTINCT must
      // reference projected columns makes this binding well-defined). That scope
      // is the SELECT list 1:1 by position, so an ordinal points straight at a
      // field rather than re-binding through a source expression.
      case (Some(list), true) =>
        val projSchema = Schema(
          projections.iterator.map { case (e, n) => Field(n, e.dataType) }.toVector)
        LogicalSort(LogicalDistinct(projected), list.map(bindOrderKey(_, projSchema)))
      case (None, distinct) => if (distinct) LogicalDistinct(projected) else projected
    }

    applyOffsetAndLimit(afterSort, Option(ps.getLimit), Option(ps.getOffset),
      Option(ps.getFetch), Option(ps.getTop))
  }

  /** The sort of a SELECT without DISTINCT, over its projection: `key` gives
    * each ORDER BY key as an output position (Left) or an expression over the
    * projection's input (Right). The projection computes each expression
    * beside the output columns, under the name `_orderN`, the sort reads them
    * there, and a projection above the sort drops them again. So a key that
    * names an output column sorts by the values the query returns, `RAND()`'s
    * among them, rather than by the expression computed a second time. */
  private def sortOverProjection(
      preProject: LogicalPlan,
      projections: Seq[(Expr, String)],
      list: Seq[OrderByElement],
      key: Expression => Either[Int, Expr]): LogicalPlan = {
    val keys = list.map(obe => key(obe.getExpression))
    val computed = keys.collect { case Right(e) => e }
    val extended = projections ++ computed.zipWithIndex.map { case (e, j) => (e, s"_order$j") }
    def column(i: Int): ColRefExpr = ColRefExpr(i, extended(i)._2, extended(i)._1.dataType)
    val computedAt = Iterator.from(projections.size)
    val sortKeys = list.zip(keys).map {
      case (obe, Left(i)) => sortKey(column(i), obe)
      case (obe, Right(_)) => sortKey(column(computedAt.next()), obe)
    }
    val sorted = LogicalSort(LogicalProject(preProject, extended), sortKeys)
    if (computed.isEmpty) sorted
    else LogicalProject(sorted, projections.indices.map(i => (column(i), projections(i)._2)))
  }

  /** How ORDER BY reads a name in a SELECT without DISTINCT, where both the
    * output columns and the input's are in scope.
    *
    *   - A bare name (in grouping parentheses or not) is the output column of
    *     that name when the SELECT list has one, as SQL has it: `SELECT -b AS
    *     b ... ORDER BY b` sorts by `-b`, and `SELECT s, COUNT(*) AS n ...
    *     ORDER BY n` by the count. Two output columns of the name are an error
    *     unless they are one column of the scope. A name no output column has
    *     is an input column.
    *   - Inside an expression a name is an input column, or the output column
    *     of a name the input does not have (`ORDER BY total * 2`). A name that
    *     is both, an output column that is not the input column of its name
    *     (`SELECT -b AS b ... ORDER BY b + 0`), is refused: Postgres reads
    *     the input column there, Spark the output, and the two orders differ.
    *   - A position (`ORDER BY 2`) is the output column there.
    *
    * Names inside an aggregate's arguments or a window are the input's, as
    * everywhere. Until this the sort bound every name against the input, so
    * the first query sorted by `b` and the second refused `n`. */
  private final class OrderScope(
      expandedItems: Seq[(Expression, String)],
      projections: Seq[(Expr, String)],
      sources: Sources) {

    /** The output positions named `name`. */
    private def named(name: String): Seq[Int] = {
      val lower = name.toLowerCase(Locale.ROOT)
      expandedItems.indices.filter(i => expandedItems(i)._2.toLowerCase(Locale.ROOT) == lower)
    }

    /** The input column an unqualified `name` reads: None when the input has
      * none, `Some(None)` when it has several. */
    private def inputColumn(name: String): Option[Option[ColRefExpr]] =
      try Analyzer.findColumn(None, name, sources).map(Some(_))
      catch { case _: IllegalArgumentException => Some(None) }

    /** Whether output column `i` is the input column `name` reads. */
    private def isInputColumn(i: Int, name: String): Boolean = {
      def strip(e: Expression): Expression = e match {
        case Grouped(inner) => strip(inner)
        case other => other
      }
      (strip(expandedItems(i)._1), inputColumn(name)) match {
        case (c: Column, Some(Some(input))) =>
          val q = Option(c.getTable).map(t => unquoted(t.getName))
          scala.util.Try(Analyzer.findColumn(q, unquoted(c.getColumnName), sources)).toOption.flatten.contains(input)
        case _ => false
      }
    }

    private def positions(outputs: Seq[Int]): String = {
      val shown = outputs.map(i => (i + 1).toString)
      shown.init.mkString(", ") + " and " + shown.last
    }

    /** The one output column `outputs` stand for: the only one, or several
      * that are one column of the scope (`SELECT a, t.a`). An error naming
      * them otherwise. */
    private def oneOutput(written: Expression, name: String, outputs: Seq[Int]): Int = {
      val exprs = outputs.map(projections(_)._1)
      if (outputs.size == 1 || (exprs.distinct.size == 1 && exprs.head.isInstanceOf[ColRefExpr])) outputs.head
      else throw new IllegalArgumentException(
        s"ORDER BY $written is ambiguous: output columns ${positions(outputs)} are each named $name; " +
          "order by the position of the one you mean")
    }

    /** `name` written with the table of the input column it reads, when one
      * column does and its source has a name. */
    private def qualified(name: String): Option[String] = inputColumn(name).flatten.flatMap { col =>
      val starts = sources.scanLeft(0)(_ + _._2.length)
      sources.indices.find(s => col.index >= starts(s) && col.index < starts(s + 1))
        .flatMap(s => sources(s)._1).map(table => s"$table.${col.name}")
    }

    /** The output column an unqualified name inside an ORDER BY expression
      * reads, for the binder: one the input does not have. [[key]] has
      * refused every name that could read either, and every name several
      * output columns have, before binding asks. */
    def outputColumn(c: Column): Option[Expr] =
      if (c.getTable != null) None
      else {
        val name = unquoted(c.getColumnName)
        named(name).headOption.filter(_ => inputColumn(name).isEmpty).map(projections(_)._1)
      }

    /** The names `e` reads outside any aggregate's arguments and any window,
      * which are read in the input. */
    private def namesRead(e: Expression): Seq[Column] = e match {
      case c: Column => if (c.getTable == null) Seq(c) else Nil
      case f: Function if isAggFn(f.getName) => Nil
      case other => childExpressions(other).flatMap(namesRead)
    }

    /** The output position `e` names (Left) or the expression it is, bound
      * by `bind` (Right). */
    def key(e: Expression, bind: Expression => Expr): Either[Int, Expr] = {
      def bare(x: Expression): Option[Column] = x match {
        case Grouped(inner) => bare(inner)
        case c: Column if c.getTable == null => Some(c)
        case _ => None
      }
      selectListOrdinal("ORDER BY", e, expandedItems.size) match {
        case Some(i) => Left(i)
        case None => bare(e).map(c => unquoted(c.getColumnName)).filter(named(_).nonEmpty) match {
          case Some(name) => Left(oneOutput(e, name, named(name)))
          case None =>
            namesRead(e).foreach { c =>
              val name = unquoted(c.getColumnName)
              val outputs = named(name)
              if (outputs.nonEmpty) {
                if (inputColumn(name).isDefined) {
                  outputs.find(!isInputColumn(_, name)).foreach { other =>
                    val item = expandedItems(other)._1
                    throw new IllegalArgumentException(
                      s"ORDER BY $e is ambiguous: $name is an input column and also output column ${other + 1}, " +
                        s"$item; write the input column with its table${qualified(name).fold("")(q => s" ($q)")}, " +
                        s"or $item, in place of $name")
                  }
                } else oneOutput(e, name, outputs)
              }
            }
            val bound = bind(e)
            refuseConstantKey("ORDER BY", e, bound, position = true)
            Right(bound)
        }
      }
    }
  }

  // ---------------------------------------------------------------------------
  // Aggregation
  // ---------------------------------------------------------------------------

  /** A "binder factory": given an `AnalyticExpression => Option[Expr]` window
    * resolver and a `Column => Option[Expr]` resolver for the output columns
    * a name may read ([[OrderScope.outputColumn]]), returns a function that
    * binds JSqlParser expressions to bound [[Expr]] trees over the
    * post-aggregation (or pre-aggregation) schema.
    */
  private type BinderFactory = (AnalyticExpression => Option[Expr], Column => Option[Expr]) => (Expression => Expr)

  /** Build aggregation pieces but do not place the final projection. Returns the
    * pre-projection plan (with HAVING applied) and a rebinder factory used for
    * both window collection (with an empty windowResolver) and the final
    * projection / ORDER BY (with a populated windowResolver).
    */
  private def buildAggregation(
      child: LogicalPlan,
      sources: Sources,
      ps: PlainSelect,
      expandedItems: Seq[(Expression, String)]): (LogicalPlan, BinderFactory) = {

    val rawGroupExprs: Seq[Expression] = Option(ps.getGroupBy)
      .flatMap(g => Option(g.getGroupByExpressionList))
      .map(_.asScala.toSeq)
      .getOrElse(Nil)
    rawGroupExprs.foreach(refuseGroupingFunction)
    val groupExprs: Seq[Expression] = rawGroupExprs.map(resolveGroupOrdinal(_, expandedItems))
    val groupBound: Seq[(Expr, String)] = rawGroupExprs.zip(groupExprs).map { case (written, e) =>
      val b = bindExpr(e, sources)
      // A position names an output column, whatever that holds.
      if (selectListOrdinal("GROUP BY", written, expandedItems.size).isEmpty)
        refuseConstantKey("GROUP BY", written, b, position = true)
      (b, deriveAlias(e))
    }

    // Collect every distinct aggregate referenced by SELECT, HAVING or ORDER BY,
    // assign synthetic names. ORDER BY may name one the SELECT list does not
    // (`SELECT g ... GROUP BY g ORDER BY SUM(a)`), and the sort's rebind needs
    // its slot; so may a window function (`SUM(COUNT(*)) OVER ()`, which sums
    // the groups' counts), whose own function is no group aggregate. A name
    // is one no GROUP BY key has, so a column of the input named `_agg0` is
    // no aggregate's; each aggregate is read at its position.
    val keyNames = groupBound.map(_._2.toLowerCase(Locale.ROOT)).toSet
    def aggregateName(i: Int): String =
      Iterator.from(0).map(n => if (n == 0) s"_agg$i" else s"_agg${i}_$n")
        .find(name => !keyNames(name.toLowerCase(Locale.ROOT))).get
    // Each aggregate is computed once, however it is spelled: `COUNT(*)` and
    // `count( * )`, `SUM(a)` and `SUM(t.a)` bind to one aggregate and read one
    // slot, which each spelling finds by its text. They were a slot each,
    // which the plan, printed as SQL (PlanSql), wrote alike, and which bound
    // again as one.
    val slots = mutable.ArrayBuffer.empty[(AggExpr, String)]
    val slotOf = mutable.HashMap.empty[String, Int]
    def collect(e: Expression): Unit = e match {
      case ae: AnalyticExpression => windowInputs(ae).foreach(collect)
      case f: Function if isAggFn(f.getName) =>
        val key = canonicalSql(f)
        if (!slotOf.contains(key)) {
          val bound = bindAgg(f, sources)
          slotOf(key) = slots.indexWhere(_._1 == bound) match {
            case -1 => slots += ((bound, aggregateName(slots.size))); slots.size - 1
            case i => i
          }
        }
      case _ => childExpressions(e).foreach(collect)
    }
    expandedItems.foreach { case (e, _) => collect(e) }
    Option(ps.getHaving).foreach(collect)
    Option(ps.getOrderByElements).foreach(_.asScala.foreach(obe => collect(obe.getExpression)))

    val agg = LogicalAggregate(child, groupBound, slots.toSeq)

    // A name binds through the resolver below, never by the output's names:
    // `_agg0` names no aggregate a query can write, and a name two inputs
    // have is as ambiguous here as in WHERE.
    val aggOutSources: Sources = Seq((None, Schema(agg.outputSchema.fields.take(groupBound.size))))
    val aggOutFieldNames: IndexedSeq[String] = agg.outputSchema.fieldNames
    def output(i: Int): Expr = ColRefExpr(i, aggOutFieldNames(i), agg.outputSchema.fields(i).dataType)
    val aggregateAt: Map[String, Int] = slotOf.iterator.map { case (key, i) => key -> (groupBound.size + i) }.toMap

    /** The GROUP BY key `e` is, written another way: in parentheses, with or
      * without its table, in another case or spacing, which bind to the same
      * expression over the input. It is found wherever it is written, so
      * `SELECT UPPER(s) || '!' ... GROUP BY UPPER(s)` and `HAVING a % 2 = 1`
      * over `GROUP BY a % 2` read the key; they were refused, since a key
      * was matched by its text and only as a whole SELECT item. A column is
      * [[groupedColumn]]'s. */
    def groupKey(e: Expression): Option[Expr] = e match {
      case _ if groupBound.isEmpty => None
      case _: Column | _: AnalyticExpression => None
      case _ =>
        val bound = try Some(bindExpr(e, sources)) catch { case NonFatal(_) => None }
        bound.flatMap(b => groupBound.indexWhere(_._1 == b) match {
          case -1 => None
          case i => Some(output(i))
        })
    }

    /** A column outside an aggregate's arguments: the GROUP BY key it is,
      * however it is written (`t.a` for a key written `a`), or an error. It
      * resolves in the input, as it does in WHERE, so a name two inputs have
      * is ambiguous here too; a column of the input that is no key is the
      * error that says the rule, and one the input does not have is its
      * unknown column. */
    def groupedColumn(c: Column): Expr = {
      val qualifier = Option(c.getTable).map(t => unquoted(t.getName))
      val name = unquoted(c.getColumnName)
      Analyzer.findColumn(qualifier, name, sources) match {
        case Some(input) => groupBound.indexWhere(_._1 == input) match {
          case -1 => throw new IllegalArgumentException(
            s"Column '${qualifier.fold("")(_ + ".")}$name' must appear in GROUP BY or inside an aggregate")
          case i => output(i)
        }
        case None => throw Analyzer.unknownColumn(qualifier, name, sources)
      }
    }

    val aggResolver: Expression => Option[Expr] = {
      case f: Function if isAggFn(f.getName) => aggregateAt.get(canonicalSql(f)).map(output)
      case c: Column => Some(groupedColumn(c))
      case other => groupKey(other)
    }

    val factory: BinderFactory = { (windowResolver, outputs) =>
      // A name the aggregate's output does not hold may be an output column
      // ORDER BY reads before it is a GROUP BY key written another way.
      val resolver: Expression => Option[Expr] = {
        case c: Column => outputs(c).orElse(aggResolver(c))
        case other => aggResolver(other)
      }
      // An aggregate and a GROUP BY key are the resolver's, at any depth.
      e: Expression => bindExprWithAggs(e, aggOutSources, resolver, windowResolver)
    }

    val havingBinder = factory(_ => None, _ => None)
    val afterHaving: LogicalPlan = Option(ps.getHaving) match {
      case Some(h) =>
        LogicalFilter(agg, Analyzer.condition(havingBinder(h), "HAVING"))
      case None => agg
    }

    (afterHaving, factory)
  }

  /** `ROLLUP(...)` and `CUBE(...)` as GROUP BY items, which JSqlParser parses
    * as calls, so binding would report an unknown function. Each is a grouping
    * the engine does not implement, refused as GROUPING SETS and WITH ROLLUP
    * are. */
  private def refuseGroupingFunction(e: Expression): Unit = e match {
    case Grouped(inner) => refuseGroupingFunction(inner)
    case f: Function if f.getName.equalsIgnoreCase("ROLLUP") => throw new IllegalArgumentException(
      "GROUP BY ROLLUP (...) is not supported; GROUP BY each level separately and combine the results with UNION ALL")
    case f: Function if f.getName.equalsIgnoreCase("CUBE") => throw new IllegalArgumentException(
      "GROUP BY CUBE (...) is not supported; GROUP BY each combination separately and combine the results with " +
        "UNION ALL")
    case _ => ()
  }

  // ---------------------------------------------------------------------------
  // FROM + JOINs
  // ---------------------------------------------------------------------------

  /** One row and no columns, which a constant is computed over. */
  private lazy val EmptyRow: ColumnarBatch = {
    val row = new ColumnarBatch(Schema(Vector.empty), 1)
    row.setNumRows(1)
    row
  }

  /** What a SELECT with no FROM reads: one row and no columns, so the SELECT
    * list is evaluated once and no column name resolves. Operators never write
    * to their input batches, so one view serves every query. */
  private lazy val NoFromRow: MaterializedView = new MaterializedView(EmptyRow.schema, IndexedSeq(IndexedSeq(EmptyRow)))

  /** Whether `scan` is what a SELECT with no FROM reads. */
  def readsNoTable(scan: LogicalScan): Boolean = scan.view eq NoFromRow

  private def buildFromAndJoins(ps: PlainSelect, catalog: Catalog, cteScope: Map[String, CteScopeEntry]): (LogicalPlan, Sources) = {
    val (root, rootSources) =
      if (ps.getFromItem == null) (LogicalScan("(no FROM)", NoFromRow, None), Nil: Sources)
      else fromItem(ps.getFromItem, catalog, cteScope)
    val joins = Option(ps.getJoins).map(_.asScala.toSeq).getOrElse(Nil)

    var plan = root
    var srcs = rootSources
    val conditions = joinConditions(joins)
    joins.zip(conditions).foreach { case (j, on) =>
      val (rightPlan, rightSources) = fromItem(j.getRightItem, catalog, cteScope)
      // A name a FROM gives two of its tables names neither: `x.b` read
      // whichever of `t x JOIN u x` had a column `b`. Postgres refuses it too.
      rightSources.flatMap(_._1).foreach { name =>
        if (srcs.exists(_._1.exists(_.equalsIgnoreCase(name))))
          throw new IllegalArgumentException(
            s"FROM names two tables $name; give each its own alias (FROM t a JOIN t b ON ...)")
      }
      val combinedSources = srcs ++ rightSources
      // A CROSS JOIN and a table after a comma pair every row.
      val condition: Expr = on.fold[Expr](LitExpr(true, DataType.BooleanType))(e =>
        Analyzer.condition(bindExpr(e, combinedSources), "ON"))
      val kind: JoinKind =
        if (j.isFull) JoinKind.Full
        else if (j.isLeft) JoinKind.Left
        else if (j.isRight) JoinKind.Right
        else JoinKind.Inner
      plan = LogicalJoin(plan, rightPlan, condition, kind)
      srcs = combinedSources
    }
    (plan, srcs)
  }

  /** Each join's ON, which SQL gives it exactly as many of as its kind takes:
    * one for JOIN, INNER, LEFT, RIGHT and FULL JOIN, none for CROSS JOIN and a
    * table after a comma. JSqlParser takes any number on any of them. A JOIN
    * with none ran as a cross join (and a LEFT JOIN with none has no meaning
    * at all), a CROSS JOIN or a comma with one ran as an inner join on it, and
    * a join's second ON was ANDed with its first. The standard's nested join,
    * `t JOIN u JOIN v ON c1 ON c2`, arrives as a join with no ON followed by
    * one with both, and is refused as itself. USING and NATURAL are refused
    * before binding. */
  private def joinConditions(joins: Seq[Join]): Seq[Option[Expression]] = {
    val ons = joins.map(j => Option(j.getOnExpressions).map(_.asScala.toSeq).getOrElse(Nil))
    def pairsEveryRow(j: Join) = j.isCross || j.isSimple
    val nested = joins.indices.exists(i =>
      ons(i).isEmpty && !pairsEveryRow(joins(i)) && ons.drop(i + 1).exists(_.size > 1))
    if (nested) throw new IllegalArgumentException(
      "A nested join (JOIN ... JOIN ... ON ... ON ...) is not supported; give each JOIN its own ON")
    joins.zip(ons).map { case (j, on) =>
      if (pairsEveryRow(j)) {
        if (on.nonEmpty) throw new IllegalArgumentException(
          s"${if (j.isCross) "CROSS JOIN" else "A table after a comma"} takes no ON; write JOIN ... ON to join on " +
            "a condition")
        None
      } else {
        val kind =
          if (j.isFull) "FULL JOIN" else if (j.isLeft) "LEFT JOIN" else if (j.isRight) "RIGHT JOIN"
          else if (j.isInner) "INNER JOIN" else "JOIN"
        on match {
          case Seq(condition) => Some(condition)
          case Seq() if j.isFull || j.isLeft || j.isRight => throw new IllegalArgumentException(
            s"$kind needs an ON condition")
          case Seq() => throw new IllegalArgumentException(
            s"$kind needs an ON condition; write CROSS JOIN to pair every row")
          case _ => throw new IllegalArgumentException(s"$kind takes one ON; combine the conditions with AND")
        }
      }
    }
  }

  private def fromItem(item: FromItem, catalog: Catalog, cteScope: Map[String, CteScopeEntry]): (LogicalPlan, Sources) = item match {
    case t: net.sf.jsqlparser.schema.Table =>
      val name = unquoted(t.getName)
      val alias = Option(t.getAlias).map(aliasName).orElse(Some(name))
      cteScope.get(name.toLowerCase(Locale.ROOT)) match {
        case Some(PendingCte(cteName, pending)) =>
          // Placeholder scan; the resolver swaps it for the inlined body or a
          // scan over the materialized result. Alias handling is identical to a
          // real table, so column resolution downstream is unchanged.
          (LogicalScan(cteName, pending, alias), Seq((alias, pending.schema)))
        case Some(InlineCte(ctePlan)) =>
          (ctePlan, Seq((alias, ctePlan.outputSchema)))
        case None =>
          val view = catalog(name)
          (LogicalScan(name, view, alias), Seq((alias, view.schema)))
      }
    case other => throw new IllegalArgumentException(UnsupportedClauses.refusal(other, " in FROM"))
  }

  // ---------------------------------------------------------------------------
  // Expression binding
  // ---------------------------------------------------------------------------

  /** Matches a grouping paren, yielding the expression inside it. JSqlParser
    * parses `(expr)` as a single-element [[ParenthesedExpressionList]] rather
    * than as a node of the inner expression's own type, so every walk over the
    * AST — binding, child traversal, post-aggregation rebinding, alias
    * derivation, ordinal recognition — has to see through it, or a paren the
    * user wrote for grouping changes what the query means.
    *
    * Two or more elements is a row-value constructor (`(a, b) = (c, d)`), which
    * this binder does not support: it deliberately does not match here, so it
    * reaches the caller's catch-all and is refused as a row value.
    */
  private object Grouped {
    def unapply(e: Expression): Option[Expression] = e match {
      case pel: ParenthesedExpressionList[_] if pel.size() == 1 =>
        Some(pel.get(0).asInstanceOf[Expression])
      case _ => None
    }
  }

  /** `left`, the left side of a comparison, arithmetic, `||`, LIKE, IS NULL,
    * IN or BETWEEN, unless it is a NOT. SQL's NOT negates the whole of one
    * (`NOT a = 1` is `NOT (a = 1)`), but JSqlParser hangs the second NOT of
    * `NOT NOT a = 1` on the left side instead: `NOT ((NOT a) = 1)`.
    * Parentheses always reach the AST, so a NOT that is an operator's left
    * side was written without them, and binding it answers another question:
    * `NOT NOT flag IS NULL` kept the rows where `flag` is not NULL. AND and
    * OR bind more loosely than NOT, so `NOT a AND b` is `(NOT a) AND b`, as
    * written, and neither comes here. */
  private def leftSide(left: Expression): Expression = left match {
    case _: NotExpression => throw new IllegalArgumentException(
      "NOT before the left side of an operator is not supported here; write NOT (...) around what it negates")
    case _ => left
  }

  def bindExpr(e: Expression, sources: Sources): Expr =
    bindExprWithAggs(e, sources, _ => None, _ => None)

  /** Variant used inside aggregation, where `sources` is the aggregate's
    * output: `aggResolver` is asked first for every node but a window. In an
    * aggregate query it answers a column with the GROUP BY key it reads, an
    * aggregate with a ColRef into the output — letting expressions like
    * `COUNT(*) > 1` (in HAVING / ORDER BY) bind cleanly — and a GROUP BY key
    * written as an expression with the key, wherever it is; elsewhere it
    * answers a name ORDER BY reads as an output column. `windowResolver`
    * plays the same role for [[AnalyticExpression]]s after [[LogicalWindow]]
    * has appended their output columns to the schema.
    */
  def bindExprWithAggs(
      e: Expression,
      sources: Sources,
      aggResolver: Expression => Option[Expr],
      windowResolver: AnalyticExpression => Option[Expr] = _ => None): Expr = {
    def b(x: Expression): Expr = bindExprWithAggs(x, sources, aggResolver, windowResolver)
    object Resolved {
      def unapply(x: Expression): Option[Expr] = x match {
        case _: AnalyticExpression => None
        case _ => aggResolver(x)
      }
    }
    e match {
    case Grouped(inner) => b(inner)
    case Resolved(resolved) => resolved
    case c: Column =>
      val q = Option(c.getTable).map(t => unquoted(t.getName))
      val name = unquoted(c.getColumnName)
      Analyzer.findColumn(q, name, sources)
        .getOrElse(throw Analyzer.unknownColumn(q, name, sources))
    // TRUE and FALSE are literals: a column named either is written in quotes.
    case bv: BooleanValue => LitExpr(bv.getValue, DataType.BooleanType)
    case IntegerLiteral(v) => integerLiteral(v)
    case d: DoubleValue => doubleLiteral(d)
    // The value, each doubled quote one: `getValue` is the text between the
    // quotes as written, so 'it''s' was five characters.
    case s: StringValue => LitExpr(s.getNotExcapedValue, DataType.StringType)
    case _: NullValue => LitExpr(null, DataType.NullType)
    // `{d '...'}` and `{ts '...'}` are read as `DATE '...'` and `TIMESTAMP
    // '...'` are, by the CAST from text: JSqlParser reads them leniently, so
    // `{d '2026-02-30'}` was 2026-03-02. A time with no zone is UTC.
    case d: DateValue =>
      Analyzer.readLiteral(SqlParser.escapeLiteralText(d).getOrElse(d.getValue.toLocalDate.toString), DataType.DateType)
    case t: TimestampValue => Analyzer.readLiteral(SqlParser.stringLiteralValue(t.getRawValue), DataType.TimestampType)
    case _: TimeValue => throw new IllegalArgumentException("TIME literal not supported")
    // UnsupportedClauses has already rejected every keyword but the clock ones.
    case tk: TimeKeyExpression =>
      val keyword = UnsupportedClauses.clockKeyword(tk.getStringValue)
      clockLiteral(FunctionCatalog.lookup(keyword).filter(_.kind == FunctionCatalog.Kind.Clock)
        .getOrElse(throw new IllegalStateException(s"$keyword is no clock keyword")))
    case se: SignedExpression =>
      val sign = se.getSign
      val inner = b(se.getExpression)
      sign match {
        case '-' =>
          Analyzer.checkArithmetic(inner.dataType)
          UnaryOpExpr("-", inner, inner.dataType)
        // A plus is arithmetic on a number too, as a minus is: `+'abc'` and
        // `+TRUE` were their operands.
        case '+' =>
          Analyzer.checkArithmetic(inner.dataType)
          inner
        case other => throw new IllegalArgumentException(s"The $other operator is not supported")
      }
    case n: NotExpression =>
      UnaryOpExpr("NOT", Analyzer.condition(b(n.getExpression), "NOT"), DataType.BooleanType)

    case _: AllValue => throw new IllegalArgumentException("ALL is not supported in this position")

    case a: Addition => binopGeneric("+", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Subtraction => binopGeneric("-", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Multiplication => binopGeneric("*", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Division => binopGeneric("/", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Modulo => binopGeneric("%", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Concat =>
      val l = b(leftSide(a.getLeftExpression)); val r = b(a.getRightExpression)
      Seq(l, r).foreach(e => Analyzer.checkText(e.dataType, "||"))
      BinOpExpr("||", l, r, DataType.StringType)

    case a: AndExpression =>
      val l = Analyzer.condition(b(a.getLeftExpression), "AND")
      val r = Analyzer.condition(b(a.getRightExpression), "AND")
      BinOpExpr("AND", l, r, DataType.BooleanType)
    case a: OrExpression =>
      val l = Analyzer.condition(b(a.getLeftExpression), "OR")
      val r = Analyzer.condition(b(a.getRightExpression), "OR")
      BinOpExpr("OR", l, r, DataType.BooleanType)

    case x: EqualsTo => cmpOpGeneric("=", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: NotEqualsTo => cmpOpGeneric("<>", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: GreaterThan => cmpOpGeneric(">", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: GreaterThanEquals => cmpOpGeneric(">=", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: MinorThan => cmpOpGeneric("<", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: MinorThanEquals => cmpOpGeneric("<=", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)

    case in: InExpression =>
      val left = leftSide(in.getLeftExpression)
      val leftBound = b(left)
      val items: Seq[Expression] = in.getRightExpression match {
        case el: ExpressionList[_] => el.asScala.asInstanceOf[Iterable[Expression]].toSeq
        case _: Select => throw new IllegalArgumentException(
          "IN with a subquery is not supported; name the subquery in a WITH clause and JOIN it")
        case _ => throw new IllegalArgumentException("IN takes a list of values in parentheses")
      }
      // Standard SQL and Postgres have no empty list, and the parser's
      // acceptance of one gave `NULL NOT IN ()` NULL beside `1 NOT IN ()`
      // TRUE, where a list of nothing holds no value of either.
      if (items.isEmpty) throw new IllegalArgumentException(
        s"${if (in.isNot) "NOT IN" else "IN"} needs at least one value in its list")
      val (operands, _) = Analyzer.unify(comparedBesideDecimals(left +: items, leftBound +: items.map(b)))
      InListExpr(operands.head, operands.tail, in.isNot)

    case bw: Between =>
      val (operands, _) = Analyzer.unify(bindCompared(
        Seq(leftSide(bw.getLeftExpression), bw.getBetweenExpressionStart, bw.getBetweenExpressionEnd), b))
      // Its two comparisons, which read the operand twice, unless the
      // operand differs each time it is read.
      val expr: Expr =
        if (Expr.readsRandom(operands(0))) BetweenExpr(operands(0), operands(1), operands(2))
        else BinOpExpr("AND",
          BinOpExpr(">=", operands(0), operands(1), DataType.BooleanType),
          BinOpExpr("<=", operands(0), operands(2), DataType.BooleanType),
          DataType.BooleanType)
      if (bw.isNot) UnaryOpExpr("NOT", expr, DataType.BooleanType) else expr

    case lk: LikeExpression =>
      val s = b(leftSide(lk.getLeftExpression)); val p = b(lk.getRightExpression)
      // UnsupportedClauses has already rejected every keyword but LIKE and ILIKE.
      val syntax = LikeSyntax(
        caseInsensitive = lk.getLikeKeyWord == LikeExpression.KeyWord.ILIKE,
        escape = Option(lk.getEscape) match {
          case None => LikeSyntax.Default.escape
          case Some(esc: StringValue) if esc.getNotExcapedValue.length <= 1 => esc.getNotExcapedValue.headOption
          case Some(_) => throw new IllegalArgumentException("ESCAPE must be a single-character string literal")
        })
      Seq(s, p).foreach(e => Analyzer.checkText(e.dataType, if (syntax.caseInsensitive) "ILIKE" else "LIKE"))
      val like = LikeExpr(Analyzer.implicitCast(s, DataType.StringType), Analyzer.implicitCast(p, DataType.StringType),
        lk.isNot, syntax)
      // A constant pattern is read now, so one that ends in its escape
      // character is an error before any row is read.
      constantValue(like.pattern).foreach {
        case pattern: String => LikeExpr.toRegex(pattern, syntax)
        case _ => ()
      }
      like

    case isn: IsNullExpression =>
      IsNullExpr(b(leftSide(isn.getLeftExpression)), negated = isn.isNot)

    case c: CaseExpression =>
      val switch = Option(c.getSwitchExpression).map(s => (s, b(s)))
      val whens = c.getWhenClauses.asScala.toSeq.map(_.asInstanceOf[WhenClause])
      // A simple CASE compares its operand with each WHEN, each pair typed as
      // a comparison's two sides are.
      val compared: Seq[(Expr, Expr, DataType)] = switch.toSeq.flatMap { case (written, s) =>
        whens.map { whenExpr =>
          val when = whenExpr.getWhenExpression
          val pair = comparedBesideDecimals(Seq(written, when), Seq(s, b(when)))
          Analyzer.promotePair(pair(0), pair(1))
        }
      }
      val conditions: Seq[Expr] =
        if (switch.isDefined) compared.map { case (sl, wr, _) => BinOpExpr("=", sl, wr, DataType.BooleanType) }
        else whens.map(w => Analyzer.condition(b(w.getWhenExpression), "CASE WHEN"))
      val elseE = Option(c.getElseExpression)
      val (values, resultType) = bindBranches("CASE", whens.map(_.getThenExpression) ++ elseE, b)
      val elseValue = elseE.map(_ => values.last)
      switch match {
        // An operand that differs each time it is read is read once.
        case Some((_, s)) if Expr.readsRandom(s) =>
          SwitchCaseExpr(s, compared.zip(values).map { case ((_, when, t), value) => SwitchBranch(t, when, value) },
            elseValue, resultType)
        case _ => CaseExpr(conditions.zip(values), elseValue, resultType)
      }

    case ce: CastExpression =>
      val inner = b(ce.getLeftExpression)
      val target = parseTypeName(ce.getColDataType.getDataType)
      Analyzer.checkCast(inner.dataType, target)
      (inner, target) match {
        case (_, decimal: DataType.DecimalType) =>
          DecimalCastExpr(decimalCastOperand(ce.getLeftExpression, inner, decimal), decimal)
        // A string literal is read now, so bad text fails before the query
        // runs. `DATE '...'` and `TIMESTAMP '...'` literals are this cast.
        case (LitExpr(s: String, DataType.StringType), _) => Analyzer.readLiteral(s, target)
        case _ => CastExpr(inner, target)
      }

    case tf: TrimFunction =>
      // JSqlParser gives TRIM a node of its own so it can carry the extended
      // `TRIM([LEADING|TRAILING|BOTH] [remstr] FROM str)` grammar and the
      // two-argument `TRIM(str, remstr)`. `Funcs` implements the single-argument
      // whitespace trim only, and in every extended form the string to trim
      // lives in `getFromExpression` while `getExpression` holds the remstr — so
      // binding `getExpression` unconditionally would trim the wrong operand on
      // `TRIM(BOTH ' ' FROM x)` and hit a null on `TRIM(LEADING FROM x)`. Reject
      // the extended forms instead of answering the wrong question quietly.
      if (tf.getTrimSpecification != null || tf.isUsingFromKeyword ||
          tf.getFromExpression != null || tf.getExpression == null)
        throw new IllegalArgumentException(FunctionCatalog.OnlySingleArgumentTrim)
      val trimmed = Seq(b(tf.getExpression))
      val trim = FunctionCatalog.Trim
      trim.paramsAt(1).zip(trimmed).foreach { case (param, arg) => Analyzer.checkArgument(trim.name, param, arg) }
      FuncExpr(trim.name, trimmed, trim.resultType(trimmed))

    // The resolver has declined it (Resolved, above).
    case f: Function =>
      if (FunctionCatalog.isAggregate(f.getName))
        throw new IllegalArgumentException(FunctionCatalog.aggregateOutOfPlace(f.getName))
      bindScalarFunctionWithAggs(f, sources, aggResolver, windowResolver)

    case ae: AnalyticExpression =>
      windowResolver(ae).getOrElse(throw new IllegalArgumentException(
        s"Window function '${ae.getName}' used in non-window position"))

    case other => throw new IllegalArgumentException(UnsupportedClauses.refusal(other))
    }
  }

  private def binopGeneric(op: String, l: Expression, r: Expression, sources: Sources,
                           aggResolver: Expression => Option[Expr],
                           windowResolver: AnalyticExpression => Option[Expr]): Expr = {
    val operands = bindOperands(Seq(leftSide(l), r), bindExprWithAggs(_, sources, aggResolver, windowResolver))
    Analyzer.arithmetic(op, operands(0), operands(1))
  }

  /** `values`, bound by `bind`, under the one type of a result they all
    * become ([[Analyzer.branches]]), each constant beside a DECIMAL the
    * decimal it is written as ([[bindOperands]]), so `COALESCE(price, 1 +
    * 0.05)` is a DECIMAL as `price + (1 + 0.05)` is. */
  private def bindBranches(what: String, values: Seq[Expression], bind: Expression => Expr): (Seq[Expr], DataType) =
    Analyzer.branches(what, bindOperands(values, bind))

  /** `values`, written as they are, bound by `bind` as the operands of
    * arithmetic or the values of one result. See [[besideDecimals]]. */
  private def bindOperands(values: Seq[Expression], bind: Expression => Expr): Seq[Expr] =
    besideDecimals(values, values.map(bind), decimalConstant)

  /** `values`, written as they are, bound by `bind` as operands a comparison
    * reads: the two of a comparison, an IN list with its left side, a
    * BETWEEN's three. A comparison keeps no value, so a number no DECIMAL
    * holds (`price < 1e300`) stays the DOUBLE it is, which a DECIMAL compares
    * as the decimal it prints as. */
  private def bindCompared(values: Seq[Expression], bind: Expression => Expr): Seq[Expr] =
    comparedBesideDecimals(values, values.map(bind))

  private def comparedBesideDecimals(values: Seq[Expression], bound: Seq[Expr]): Seq[Expr] =
    besideDecimals(values, bound, v =>
      try decimalConstant(v)
      catch { case _: IllegalArgumentException => None }) // more digits than a DECIMAL holds

  /** `bound`, the operands `values` were bound as, with each constant the
    * decimal it is written as when one of them is a DECIMAL (`constant`, which
    * is [[decimalConstant]]). Bound alone a number is a DOUBLE, which keeps
    * about seventeen digits and the shortest scale that prints it: `price *
    * 1.0000000000000000001` was `price * 1.0`, `price = 0.120000000000000000001`
    * held for a `price` of 0.12, and 1.10 was a DECIMAL(2,1). */
  private def besideDecimals(
      values: Seq[Expression],
      bound: Seq[Expr],
      constant: Expression => Option[Expr]): Seq[Expr] =
    if (!bound.exists(_.dataType.isInstanceOf[DataType.DecimalType])) bound
    else values.zip(bound).map { case (v, e) => constant(v).getOrElse(e) }

  /** `e` as decimal arithmetic when it is a constant, numbers written in the
    * query and `+ - * / %` and negation on them alone. Bound on its own
    * `1 + 0.05` is a DOUBLE, which arithmetic refuses beside a DECIMAL; beside
    * one it is computed in DECIMAL, so `price * (1 + 0.05)` is as exact as
    * `price * 1.05`. Each number is the decimal of its digits as written
    * (`Decimals.literal`), so 2.50 is a DECIMAL(3,2) as in standard SQL, and
    * each step is typed as `Decimals.resultType` says. A constant keeps the
    * value it has on its own: arithmetic on integers alone is an integer's, as
    * `10 / 4` is 2 anywhere. A CAST is no constant, since `CAST(1 AS DOUBLE)`
    * asks for a DOUBLE. */
  private def decimalConstant(e: Expression): Option[Expr] = {
    def step(op: String, l: Expression, r: Expression): Option[Expr] =
      for (x <- decimalConstant(l); y <- decimalConstant(r)) yield {
        def declared(c: Expr) = c.dataType.asInstanceOf[DataType.DecimalType]
        BinOpExpr(op, x, y, Decimals.resultType(op, declared(x), declared(y)))
      }
    e match {
      case Grouped(inner) => decimalConstant(inner)
      case l: LongValue => Some(Decimals.literal(l.getBigIntegerValue))
      case d: DoubleValue => Some(Decimals.literal(writtenDigits(d)))
      case s: SignedExpression if s.getSign == '+' => decimalConstant(s.getExpression)
      case s: SignedExpression if s.getSign == '-' =>
        decimalConstant(s.getExpression).map(c => UnaryOpExpr("-", c, c.dataType))
      case _ if integerConstant(e) =>
        val integer = bindExpr(e, Seq.empty)
        Some(CastExpr(integer, Decimals.ofIntegral(integer.dataType)))
      case a: Addition => step("+", a.getLeftExpression, a.getRightExpression)
      case a: Subtraction => step("-", a.getLeftExpression, a.getRightExpression)
      case a: Multiplication => step("*", a.getLeftExpression, a.getRightExpression)
      case a: Division => step("/", a.getLeftExpression, a.getRightExpression)
      case a: Modulo => step("%", a.getLeftExpression, a.getRightExpression)
      case _ => None
    }
  }

  /** The operand of `CAST(e AS DECIMAL(p,s))`, `e` written as it is and
    * `bound` as it binds alone. A number written in the query is the decimal
    * it is written as ([[decimalConstant]]), as it is beside a DECIMAL, so a
    * long one loses no digit to a DOUBLE before the cast fits it; one a
    * DECIMAL cannot hold (`1e-400`) stays the DOUBLE it is. A string literal
    * is read now, so text that is no number, or one too long for the type,
    * fails before the query runs, and stays the text it is in the plan, which
    * prints back as the cast it was written as. */
  private def decimalCastOperand(e: Expression, bound: Expr, target: DataType.DecimalType): Expr = bound match {
    case LitExpr(s: String, DataType.StringType) =>
      val _ = Casts.toWrittenDecimal(s, target)
      bound
    case _ =>
      try decimalConstant(e).getOrElse(bound)
      catch { case _: IllegalArgumentException => bound } // more digits than a DECIMAL holds
  }

  /** Whether `e` is a constant of INT and BIGINT literals alone, which
    * [[decimalConstant]] leaves to integer arithmetic. */
  private def integerConstant(e: Expression): Boolean = e match {
    case Grouped(inner) => integerConstant(inner)
    case IntegerLiteral(v) => v.bitLength < 64
    case s: SignedExpression => integerConstant(s.getExpression)
    case a: Addition => integerConstant(a.getLeftExpression) && integerConstant(a.getRightExpression)
    case a: Subtraction => integerConstant(a.getLeftExpression) && integerConstant(a.getRightExpression)
    case a: Multiplication => integerConstant(a.getLeftExpression) && integerConstant(a.getRightExpression)
    case a: Division => integerConstant(a.getLeftExpression) && integerConstant(a.getRightExpression)
    case a: Modulo => integerConstant(a.getLeftExpression) && integerConstant(a.getRightExpression)
    case _ => false
  }

  private def cmpOpGeneric(op: String, l: Expression, r: Expression, sources: Sources,
                           aggResolver: Expression => Option[Expr],
                           windowResolver: AnalyticExpression => Option[Expr]): Expr = {
    val operands = bindCompared(Seq(leftSide(l), r), bindExprWithAggs(_, sources, aggResolver, windowResolver))
    val (le, re, _) = Analyzer.promotePair(operands(0), operands(1))
    BinOpExpr(op, le, re, DataType.BooleanType)
  }

  /** An integer literal's value. A minus written before the literal belongs
    * to it only where the number alone is no BIGINT: `-9223372036854775808`,
    * the smallest BIGINT, parses as a minus over 9223372036854775808, which on
    * its own is a DECIMAL. Any other minus binds as the negation it is. */
  private object IntegerLiteral {
    private val SmallestBigintMagnitude = java.math.BigInteger.ONE.shiftLeft(63)

    def unapply(e: Expression): Option[java.math.BigInteger] = e match {
      case l: LongValue => Some(l.getBigIntegerValue)
      case s: SignedExpression if s.getSign == '-' => s.getExpression match {
        case l: LongValue if l.getBigIntegerValue == SmallestBigintMagnitude => Some(SmallestBigintMagnitude.negate)
        case _ => None
      }
      case _ => None
    }
  }

  /** An integer written in the query: an INT if one holds it, else a BIGINT,
    * else the DECIMAL of its digits, as Postgres reads one (and as CSV
    * inference types a column of them). Past 38 digits no DECIMAL holds it,
    * and it is an error. */
  private def integerLiteral(v: java.math.BigInteger): LitExpr =
    if (v.bitLength < 32) LitExpr(v.intValue, DataType.IntType)
    else if (v.bitLength < 64) LitExpr(v.longValue, DataType.LongType)
    else Decimals.literal(new java.math.BigDecimal(v))

  /** A number written with a point or an exponent: a DOUBLE, as long as one
    * holds it. A number past a DOUBLE's range was Infinity, and a non-zero
    * one too small for a DOUBLE was 0, both silently; each is refused, and
    * no DECIMAL (38 digits) holds either. */
  private def doubleLiteral(d: DoubleValue): LitExpr = {
    val v = d.getValue
    val shown = Decimals.shownNumber(d.toString)
    if (v.isInfinite) throw new IllegalArgumentException(
      s"The number $shown is too large for a DOUBLE, which holds at most ${Double.MaxValue}")
    if (v == 0.0 && writtenDigits(d).signum != 0) throw new IllegalArgumentException(
      s"The number $shown is too small for a DOUBLE, which would read it as 0")
    LitExpr(v, DataType.DoubleType)
  }

  /** The digits of a number written with a point or an exponent, exactly.
    * JSqlParser keeps them as the node's text, which its `toString` returns;
    * `getValue` is the DOUBLE they round to. */
  private def writtenDigits(d: DoubleValue): java.math.BigDecimal = new java.math.BigDecimal(d.toString)

  /** A clock keyword, CURRENT_DATE or CURRENT_TIMESTAMP: the statement's
    * start, in UTC like every time in the engine. */
  private def clockLiteral(clock: FunctionCatalog.Entry): Expr = {
    val now = statementStart.value
    if (now == null) throw new IllegalStateException(s"${clock.name} was bound outside LogicalBuilder.build")
    clock.clock.get(now)
  }

  /** A call outside OVER of a scalar, flow, operator or clock function,
    * bound by its entry in [[FunctionCatalog]]: its arguments as its
    * parameters say ([[bindArguments]]), checked against them, and typed by
    * its result rule. A name no entry has, or an arity its entry does not
    * take, is refused, by the entry's own message where it has one. */
  private def bindScalarFunctionWithAggs(f: Function, sources: Sources,
                                          aggResolver: Expression => Option[Expr],
                                          windowResolver: AnalyticExpression => Option[Expr]): Expr = {
    def bind(x: Expression): Expr = bindExprWithAggs(x, sources, aggResolver, windowResolver)
    val name = f.getName.toUpperCase(Locale.ROOT)
    val params: Seq[Expression] = Option(f.getParameters)
      .map(_.asInstanceOf[ExpressionList[_]].asScala.asInstanceOf[Iterable[Expression]].toSeq)
      .getOrElse(Nil)
    val entry = FunctionCatalog.call(name).filter(_.arity.admits(params.size))
    val args: Seq[Expr] = entry match {
      case Some(e) => bindArguments(name, e.paramsAt(params.size), params, bind)
      case None => params.map(bind)
    }
    entry match {
      // `CURRENT_DATE ()`, with a space, parses as a call rather than the keyword.
      case Some(e) if e.kind == FunctionCatalog.Kind.Clock => clockLiteral(e)
      // MOD(a, b) is a % b, typed and computed as that is.
      case Some(e) if e.kind == FunctionCatalog.Kind.Operator =>
        Analyzer.arithmetic(e.operator.get, args(0), args(1))
      case _ =>
        FunctionCatalog.call(name).flatMap(_.refusedArities.get(args.size))
          .foreach(message => throw new IllegalArgumentException(message))
        val signature = Funcs.signature(name, args).getOrElse(
          throw new IllegalArgumentException(FunctionCatalog.unknownFunction(f.getName, args.map(_.dataType))))
        signature.params.zip(args).foreach { case (param, arg) => Analyzer.checkArgument(name, param, arg) }
        entry.flatMap(_.bindCheck).foreach(check => check(i => if (i < args.size) constantValue(args(i)) else None))
        FuncExpr(name, args, signature.returnType)
    }
  }

  /** The arguments `params` of a call of `name`, each bound as its parameter
    * `kinds` says, in the order written: a condition as every condition is,
    * the values the call may answer with together, as a CASE's values meet
    * ([[bindBranches]]), the two NULLIF compares as a comparison's sides
    * ([[bindNullIf]]), an operator's operands as arithmetic's
    * ([[bindOperands]]), and any other on its own. */
  private def bindArguments(
      name: String,
      kinds: Seq[Funcs.Param],
      params: Seq[Expression],
      bind: Expression => Expr): Seq[Expr] = {
    val bound = new Array[Expr](params.size)
    def together(kind: Funcs.Param)(bindAll: Seq[Expression] => Seq[Expr]): Unit = {
      val at = kinds.indices.filter(kinds(_) == kind)
      bindAll(at.map(params)).zip(at).foreach { case (e, j) => bound(j) = e }
    }
    kinds.indices.foreach { i =>
      if (bound(i) == null) kinds(i) match {
        case Funcs.Param.Condition => bound(i) = Analyzer.condition(bind(params(i)), name)
        case Funcs.Param.Branch => together(Funcs.Param.Branch)(values => bindBranches(name, values, bind)._1)
        case Funcs.Param.Compared => together(Funcs.Param.Compared)(bindNullIf(_, bind))
        case Funcs.Param.Operand => together(Funcs.Param.Operand)(bindOperands(_, bind))
        case _ => bound(i) = bind(params(i))
      }
    }
    bound.toSeq
  }

  /** NULLIF's two arguments, compared as a comparison's two sides are; the
    * first is returned as it is, but for a string literal, which is read as
    * the type it meets. */
  private def bindNullIf(params: Seq[Expression], bind: Expression => Expr): Seq[Expr] = {
    val (first, second) = (bind(params(0)), bind(params(1)))
    val (compared, _) = Analyzer.unify(comparedBesideDecimals(params, Seq(first, second)))
    Seq(if (Analyzer.isStringLiteral(first)) compared(0) else first, compared(1))
  }

  /** An aggregate call, bound by its entry in [[FunctionCatalog]]: `COUNT(*)`
    * where the entry takes a star, each argument as its parameter says
    * ([[aggregateArgument]]), and an arity the entry does not take refused,
    * by its own message where it has one. */
  private def bindAgg(f: Function, sources: Sources): AggExpr = {
    val upper = f.getName.toUpperCase(Locale.ROOT)
    val params: Seq[Expression] = Option(f.getParameters)
      .map(_.asInstanceOf[ExpressionList[_]].asScala.asInstanceOf[Iterable[Expression]].toSeq)
      .getOrElse(Nil)
    val distinct = f.isDistinct
    // The star in COUNT(*) is an AllColumns parameter. `f.isAllColumns` is
    // something else: the ALL quantifier of COUNT(ALL x), which is the default
    // and changes nothing, so it is never read.
    val isStarArg = params.size == 1 && params.head.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns]
    FunctionCatalog.aggregate(upper) match {
      case Some(e) if e.star && isStarArg => e.aggregate.get(Nil, false)
      case Some(e) if e.refusedArities.contains(params.size) =>
        throw new IllegalArgumentException(e.refusedArities(params.size))
      case Some(e) if e.arity.admits(params.size) =>
        val args = e.paramsAt(params.size).zip(params).map { case (kind, p) =>
          aggregateArgument(upper, kind, bindExpr(p, sources))
        }
        e.aggregate.get(args, distinct)
      case _ => throw new IllegalArgumentException(FunctionCatalog.unsupportedAggregate(f.getName, params.size))
    }
  }

  /** `e`, an argument of the aggregate `fn`, of the kind `kind` says: a number
    * for one that adds its values up (SUM, AVG and the statistics), a truth
    * value for COUNT_IF's, any value for MIN's, MAX's and COUNT's. */
  private def aggregateArgument(fn: String, kind: Funcs.Param, e: Expr): Expr = kind match {
    case Funcs.Param.Number => numberArg(fn, e)
    case Funcs.Param.Condition => Analyzer.condition(e, fn)
    case _ => e
  }

  /** `e`, the argument of the aggregate `fn`, which must be a number. */
  private def numberArg(fn: String, e: Expr): Expr = {
    Analyzer.checkNumber(e.dataType, fn)
    e
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private def isAggFn(name: String): Boolean = FunctionCatalog.isAggregate(name)

  /** Whether `e` holds a group aggregate. A window aggregate is none, but its
    * arguments and keys may hold one, which it reads per group. */
  private def containsAggregate(e: Expression): Boolean = e match {
    case ae: AnalyticExpression => windowInputs(ae).exists(containsAggregate)
    case f: Function if isAggFn(f.getName) => true
    case _ => childExpressions(e).exists(containsAggregate)
  }

  private def childExpressions(e: Expression): Seq[Expression] = e match {
    case Grouped(inner) => Seq(inner)
    case n: NotExpression => Seq(n.getExpression)
    case s: SignedExpression => Seq(s.getExpression)
    case a: Addition => Seq(a.getLeftExpression, a.getRightExpression)
    case a: Subtraction => Seq(a.getLeftExpression, a.getRightExpression)
    case a: Multiplication => Seq(a.getLeftExpression, a.getRightExpression)
    case a: Division => Seq(a.getLeftExpression, a.getRightExpression)
    case a: Modulo => Seq(a.getLeftExpression, a.getRightExpression)
    case a: Concat => Seq(a.getLeftExpression, a.getRightExpression)
    case a: AndExpression => Seq(a.getLeftExpression, a.getRightExpression)
    case a: OrExpression => Seq(a.getLeftExpression, a.getRightExpression)
    case x: EqualsTo => Seq(x.getLeftExpression, x.getRightExpression)
    case x: NotEqualsTo => Seq(x.getLeftExpression, x.getRightExpression)
    case x: GreaterThan => Seq(x.getLeftExpression, x.getRightExpression)
    case x: GreaterThanEquals => Seq(x.getLeftExpression, x.getRightExpression)
    case x: MinorThan => Seq(x.getLeftExpression, x.getRightExpression)
    case x: MinorThanEquals => Seq(x.getLeftExpression, x.getRightExpression)
    case b: Between => Seq(b.getLeftExpression, b.getBetweenExpressionStart, b.getBetweenExpressionEnd)
    case lk: LikeExpression => Seq(lk.getLeftExpression, lk.getRightExpression)
    case isn: IsNullExpression => Seq(isn.getLeftExpression)
    case in: InExpression =>
      val rest = in.getRightExpression match {
        case el: ExpressionList[_] => el.asScala.asInstanceOf[Iterable[Expression]].toSeq
        case _ => Nil
      }
      in.getLeftExpression +: rest
    case c: CaseExpression =>
      Option(c.getSwitchExpression).toSeq ++
        c.getWhenClauses.asScala.flatMap(w =>
          Seq(w.asInstanceOf[WhenClause].getWhenExpression, w.asInstanceOf[WhenClause].getThenExpression)) ++
        Option(c.getElseExpression).toSeq
    case ce: CastExpression => Seq(ce.getLeftExpression)
    // Both TRIM operands: the extended forms this binder rejects still have to
    // report their children, so an aggregate under one is classified before the
    // binder gets to the rejection.
    case tf: TrimFunction => Option(tf.getExpression).toSeq ++ Option(tf.getFromExpression).toSeq
    case f: Function =>
      Option(f.getParameters)
        .map(_.asInstanceOf[ExpressionList[_]].asScala.asInstanceOf[Iterable[Expression]].toSeq)
        .getOrElse(Nil)
    // Window functions own their inner expressions (partition keys, order keys,
    // function arg); they are NOT children of the outer expression's scope.
    case _: AnalyticExpression => Nil
    case _ => Nil
  }

  // ---------------------------------------------------------------------------
  // Window functions
  // ---------------------------------------------------------------------------

  /** The expressions a window function reads: its arguments, its PARTITION BY
    * keys and its ORDER BY keys. */
  private def windowInputs(ae: AnalyticExpression): Seq[Expression] =
    Option(ae.getExpression).filterNot(_.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns]).toSeq ++
      Option(ae.getOffset).toSeq ++ Option(ae.getDefaultValue).toSeq ++
      Option(ae.getPartitionExpressionList).map(_.asScala.asInstanceOf[Iterable[Expression]].toSeq).getOrElse(Nil) ++
      Option(ae.getOrderByElements).map(_.asScala.toSeq.map(_.getExpression)).getOrElse(Nil)

  /** Convert a JSqlParser [[AnalyticExpression]] into a bound [[WindowDef]].
    * The window's internal expressions (partition keys, order keys, function
    * arg) bind via `binder` against the post-aggregation (or raw-source) schema.
    * `outputName` is filled in by the caller — we pass an empty placeholder.
    */
  private def bindAnalytic(ae: AnalyticExpression, binder: Expression => Expr): WindowDef = {
    val partitionExprs: Seq[Expression] = Option(ae.getPartitionExpressionList)
      .map(_.asScala.asInstanceOf[Iterable[Expression]].toSeq).getOrElse(Nil)
    val partitionKeys: Seq[Expr] = partitionExprs.map { e =>
      val key = binder(e)
      refuseConstantKey("PARTITION BY", e, key, position = false)
      key
    }

    val orderEls = Option(ae.getOrderByElements).map(_.asScala.toSeq).getOrElse(Nil)
    val orderKeys: Seq[SortKey] = orderEls.map { obe =>
      val key = binder(obe.getExpression)
      refuseConstantKey("ORDER BY", obe.getExpression, key, position = false)
      sortKey(key, obe)
    }

    val frame = bindFrame(Option(ae.getWindowElement), orderKeys)
    val fnName = ae.getName.toUpperCase(Locale.ROOT)

    val argExpr: Option[Expression] = Option(ae.getExpression).filterNot(_.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns])
    // As for aggregates, the star is an AllColumns argument; `ae.isAllColumns`
    // is the ALL quantifier and is never read.
    val isStarArg: Boolean =
      Option(ae.getExpression).exists(_.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns])

    // LAG's and LEAD's value and default are one result, as a CASE's values
    // are: the default is what a row with no row that far away gets.
    def valueAndDefault(): (Expr, Option[Expr]) = {
      val arg = argExpr.getOrElse(throw new IllegalArgumentException(FunctionCatalog.requiresArgument(fnName)))
      Option(ae.getDefaultValue) match {
        case Some(default) =>
          val (values, _) = bindBranches(fnName, Seq(arg, default), binder)
          (values(0), Some(values(1)))
        case None => (binder(arg), None)
      }
    }

    // The window function the name is, by its entry in FunctionCatalog: a
    // ranking function, an offset function, or an aggregate that runs as one,
    // its argument checked as an aggregate's is.
    val fn: WindowFn = FunctionCatalog.window(fnName) match {
      case Some(e) if e.kind == FunctionCatalog.Kind.Ranking => e.ranking.get()
      case Some(e) if e.kind == FunctionCatalog.Kind.Offset =>
        val (arg, default) = valueAndDefault()
        val offset = Option(ae.getOffset).map(extractNonNegativeInt(s"$fnName offset")).getOrElse(1)
        e.offset.get(arg, offset, default)
      case Some(e) =>
        if (e.star && isStarArg) WindowFnAgg(e.aggregate.get(Nil, false))
        else {
          val arg = argExpr.getOrElse(throw new IllegalArgumentException(
            e.refusedArities.getOrElse(0, FunctionCatalog.requiresArgument(fnName))))
          val bound = aggregateArgument(fnName, e.paramsAt(1).head, binder(arg))
          WindowFnAgg(e.aggregate.get(Seq(bound),
            ae.isDistinct && e.windowDistinct == FunctionCatalog.DistinctUse.Honoured))
        }
      case None => throw new IllegalArgumentException(FunctionCatalog.unsupportedWindow(fnName))
    }
    // A ranking function and LAG / LEAD read no frame, so one written on them
    // cannot change the answer.
    if (ae.getWindowElement != null && !fn.respectsFrame)
      throw new IllegalArgumentException(
        s"$fnName takes no window frame; it reads the partition in its ORDER BY, so leave out the ROWS or RANGE clause")

    WindowDef(WindowSpec(partitionKeys, orderKeys, frame), fn, outputName = "")
  }

  /** A window's frame, held to what SQL allows: a start that is not
    * UNBOUNDED FOLLOWING, an end that is not UNBOUNDED PRECEDING, and a start
    * that does not come after its end in the order UNBOUNDED PRECEDING, n
    * PRECEDING, CURRENT ROW, n FOLLOWING, UNBOUNDED FOLLOWING (two offsets on
    * one side may still make an empty frame, `2 PRECEDING AND 1 PRECEDING`).
    * A RANGE offset measures the ORDER BY value, so it needs exactly one ORDER
    * BY key, of a number, and is a number written in the query. Each refusal
    * names the rule; these frames used to answer NULL on every row. */
  private def bindFrame(we: Option[WindowElement], orderKeys: Seq[SortKey]): WindowFrame = we match {
    case None => WindowFrame.defaultFor(orderKeys.nonEmpty)
    case Some(elem) =>
      val frameType: FrameType = elem.getType match {
        case WindowElement.Type.ROWS => FrameType.Rows
        case WindowElement.Type.RANGE => FrameType.Range
      }
      def bound(wo: WindowOffset): FrameBound = bindFrameBound(wo, frameType, orderKeys)
      // Two shapes: BETWEEN ... AND ... → range; or single bound → offset.
      val (start, end): (FrameBound, FrameBound) =
        Option(elem.getRange) match {
          case Some(range) => (bound(range.getStart), bound(range.getEnd))
          case None =>
            val s = Option(elem.getOffset).map(bound).getOrElse(FrameBound.UnboundedPreceding)
            (s, FrameBound.CurrentRow)
        }
      if (start == FrameBound.UnboundedFollowing)
        throw new IllegalArgumentException("A window frame cannot start at UNBOUNDED FOLLOWING, after every row")
      if (end == FrameBound.UnboundedPreceding)
        throw new IllegalArgumentException("A window frame cannot end at UNBOUNDED PRECEDING, before every row")
      if (FrameBound.rank(start) > FrameBound.rank(end))
        throw new IllegalArgumentException(
          s"A window frame cannot start at ${describe(start)} and end at ${describe(end)}, which comes before it")
      WindowFrame(frameType, start, end)
  }

  private def describe(b: FrameBound): String = b match {
    case FrameBound.UnboundedPreceding => "UNBOUNDED PRECEDING"
    case FrameBound.UnboundedFollowing => "UNBOUNDED FOLLOWING"
    case FrameBound.CurrentRow => "CURRENT ROW"
    case FrameBound.Preceding(n) => s"$n PRECEDING"
    case FrameBound.Following(n) => s"$n FOLLOWING"
    case FrameBound.ValuePreceding(v) => s"${v.toPlainString} PRECEDING"
    case FrameBound.ValueFollowing(v) => s"${v.toPlainString} FOLLOWING"
  }

  private def bindFrameBound(wo: WindowOffset, frameType: FrameType, orderKeys: Seq[SortKey]): FrameBound = {
    def offset(side: String, e: Expression): FrameBound = frameType match {
      case FrameType.Rows =>
        val n = rowCount(s"$side offset", e)
        if (side == "PRECEDING") FrameBound.Preceding(n) else FrameBound.Following(n)
      case FrameType.Range =>
        if (orderKeys.size != 1)
          throw new IllegalArgumentException(
            s"A RANGE frame with an offset ($side) needs exactly one ORDER BY key, the value it measures; " +
              s"this window orders by ${orderKeys.size}")
        val keyType = orderKeys.head.expr.dataType
        if (!DataType.isNumeric(keyType))
          throw new IllegalArgumentException(
            s"A RANGE frame with an offset ($side) measures a number, and this window orders by a " +
              s"${keyType.name.toUpperCase(Locale.ROOT)}; use a ROWS frame")
        val v: java.math.BigDecimal = e match {
          case IntegerLiteral(i) => new java.math.BigDecimal(i)
          case d: DoubleValue => writtenDigits(d)
          case _ => throw new IllegalArgumentException(s"A RANGE frame's $side offset must be a number written in the query")
        }
        if (v.signum < 0)
          throw new IllegalArgumentException(s"A RANGE frame's $side offset must not be negative")
        // A FLOAT or DOUBLE key measures in DOUBLE, which holds no such
        // offset, as it holds no such literal: an infinite offset made an
        // infinite key's frame NaN, missing its own row.
        if ((keyType == DataType.FloatType || keyType == DataType.DoubleType) && v.doubleValue.isInfinite)
          throw new IllegalArgumentException(
            s"A RANGE frame's $side offset ${Decimals.shownNumber(v.toString)} is too large for a DOUBLE, which " +
              s"holds at most ${Double.MaxValue}")
        if (side == "PRECEDING") FrameBound.ValuePreceding(v) else FrameBound.ValueFollowing(v)
    }
    wo.getType match {
      case WindowOffset.Type.CURRENT => FrameBound.CurrentRow
      case WindowOffset.Type.PRECEDING =>
        Option(wo.getExpression).map(offset("PRECEDING", _)).getOrElse(FrameBound.UnboundedPreceding)
      case WindowOffset.Type.FOLLOWING =>
        Option(wo.getExpression).map(offset("FOLLOWING", _)).getOrElse(FrameBound.UnboundedFollowing)
      case WindowOffset.Type.EXPR =>
        throw new IllegalArgumentException("A window frame bound must be UNBOUNDED PRECEDING, n PRECEDING, " +
          "CURRENT ROW, n FOLLOWING or UNBOUNDED FOLLOWING")
    }
  }

  private def extractNonNegativeInt(label: String)(e: Expression): Int = e match {
    case IntegerLiteral(v) if v.signum >= 0 =>
      if (v.bitLength < 32) v.intValue
      else throw new IllegalArgumentException(
        s"$label ${Decimals.shownNumber(v.toString)} is larger than the largest INT, ${Int.MaxValue}")
    case _ => throw new IllegalArgumentException(s"$label must be a non-negative integer literal")
  }

  /** A bare integer literal in `GROUP BY` / `ORDER BY` is a 1-based reference
    * into the SELECT list rather than a constant. Returns the 0-based position,
    * or None when `e` is an ordinary expression. Matches BigQuery / Postgres /
    * MySQL: only a *bare* integer counts, so `+1` stays a literal (JSqlParser
    * gives it as a `SignedExpression`), while grouping parens are transparent —
    * `ORDER BY (1)` is position 1, as it is in Postgres and MySQL, whose
    * grammars drop a redundant paren before the positional check ever runs.
    * (JSqlParser drops it only in `GROUP BY`, so `ORDER BY` needs [[Grouped]]
    * to reach the same answer.) */
  private def selectListOrdinal(clause: String, e: Expression, size: Int): Option[Int] = e match {
    case Grouped(inner) => selectListOrdinal(clause, inner, size)
    case lv: LongValue =>
      val v = lv.getBigIntegerValue
      if (v.signum < 1 || v.compareTo(java.math.BigInteger.valueOf(size.toLong)) > 0)
        throw new IllegalArgumentException(
          s"$clause position ${Decimals.shownNumber(v.toString)} is not in select list (size $size)")
      Some(v.intValue - 1)
    case _ => None
  }

  /** Resolve `GROUP BY N` to the SELECT-list expression it names. A GROUP BY
    * position may not point at an aggregate. */
  private def resolveGroupOrdinal(
      e: Expression,
      expandedItems: Seq[(Expression, String)]): Expression =
    selectListOrdinal("GROUP BY", e, expandedItems.size) match {
      case Some(i) =>
        val (expr, _) = expandedItems(i)
        if (containsAggregate(expr))
          throw new IllegalArgumentException(
            s"GROUP BY position ${i + 1} cannot refer to aggregate expression in select list")
        expr
      case None => e
    }

  /** Bind one ORDER BY key where the sort's scope IS its output schema, 1:1 by
    * position — above a `DISTINCT` or above a `UNION`. An ordinal indexes that
    * schema directly; anything else binds against it as a single source. */
  private def bindOrderKey(obe: OrderByElement, schema: Schema): SortKey = {
    val key = selectListOrdinal("ORDER BY", obe.getExpression, schema.length) match {
      case Some(i) =>
        val f = schema.fields(i)
        ColRefExpr(i, f.name, f.dataType)
      case None =>
        val sources: Sources = Seq((None, schema))
        val bound = bindExpr(obe.getExpression, sources)
        refuseConstantKey("ORDER BY", obe.getExpression, bound, position = true)
        bound
    }
    sortKey(key, obe)
  }

  /** Refuse a sort, group or partition key that holds one value before any
    * row is read ([[isConstant]]): it cannot order, group or partition
    * anything, and `ORDER BY 'b'` is what a query that meant the column `"b"`
    * says (MySQL's habit; Postgres refuses it too). Where a position is a key,
    * the advice names that spelling as well. */
  private def refuseConstantKey(clause: String, written: Expression, bound: Expr, position: Boolean): Unit =
    if (isConstant(bound)) {
      val verb = clause match {
        case "GROUP BY" => "groups"
        case "PARTITION BY" => "partitions"
        case _ => "orders"
      }
      def strip(e: Expression): Expression = e match {
        case Grouped(inner) => strip(inner)
        case other => other
      }
      val name = strip(written) match {
        case s: StringValue => s"${SqlParser.quote(s.getNotExcapedValue)} in quotes"
        case _ => "in double quotes if it needs them"
      }
      throw new IllegalArgumentException(
        s"$clause $written reads no column, so it $verb nothing; a column is written as its name ($name)" +
          (if (position) s", and a position as a bare number ($clause 2)" else ""))
    }

  /** Whether `e` holds one value before any row is read: it reads no column
    * and no `RAND()`, whose value differs by row. A literal, a CAST of one,
    * arithmetic on constants and a clock keyword, which binds to one
    * literal, are constant; so is `RAND(seed)`, one value for one seed. */
  private def isConstant(e: Expr): Boolean = e match {
    case _: ColRefExpr => false
    case _: LitExpr => true
    case FuncExpr(name, args, _) if FunctionCatalog.readsRandom(name, args.size) => false
    case FuncExpr(_, args, _) => args.forall(isConstant)
    case CastExpr(c, _) => isConstant(c)
    case DecimalCastExpr(c, _) => isConstant(c)
    case UnaryOpExpr(_, c, _) => isConstant(c)
    case BinOpExpr(_, l, r, _) => isConstant(l) && isConstant(r)
    case CaseExpr(branches, elseExpr, _) =>
      branches.forall { case (w, t) => isConstant(w) && isConstant(t) } && elseExpr.forall(isConstant)
    case IsNullExpr(c, _) => isConstant(c)
    case InListExpr(c, items, _) => isConstant(c) && items.forall(isConstant)
    case LikeExpr(c, pattern, _, _) => isConstant(c) && isConstant(pattern)
    // Each reads RAND(), so neither is constant; the walk says so all the same.
    case SwitchCaseExpr(operand, branches, elseExpr, _) =>
      isConstant(operand) && branches.forall(b => isConstant(b.when) && isConstant(b.value)) && elseExpr.forall(isConstant)
    case BetweenExpr(c, low, high) => isConstant(c) && isConstant(low) && isConstant(high)
  }

  /** The value of a constant `e` ([[isConstant]]), computed now. None when
    * `e` reads a row, or when computing it fails, which then fails on the
    * first row, as it did. */
  private def constantValue(e: Expr): Option[Any] =
    if (!isConstant(e)) None
    else try Some(e.eval(EmptyRow, 0)) catch { case NonFatal(_) => None }

  /** The bound key with the element's direction and NULL placement: an
    * explicit NULLS FIRST / NULLS LAST wins, and without one a NULL sorts as
    * the smallest value (first under ASC, last under DESC). */
  private def sortKey(expr: Expr, obe: OrderByElement): SortKey = obe.getNullOrdering match {
    case OrderByElement.NullOrdering.NULLS_FIRST => SortKey(expr, obe.isAsc, nullsFirst = true)
    case OrderByElement.NullOrdering.NULLS_LAST => SortKey(expr, obe.isAsc, nullsFirst = false)
    case _ => SortKey(expr, obe.isAsc)
  }

  /** The text two expressions share when they are the same expression, which
    * matches a SELECT item, a HAVING or an ORDER BY key to a GROUP BY key, an
    * aggregate or a window, and names a SELECT item with no alias. It is the
    * expression as JSqlParser renders it, read as the binder reads it
    * ([[canonicalText]]). JSqlParser's `toString` throws for a few nodes
    * the binder refuses (a ROW-type CAST under a node the rejection pass does
    * not audit); such a node matches only itself, so binding goes on to refuse
    * it by name. */
  private def canonicalSql(e: Expression): String =
    try canonicalText(e.toString)
    catch { case NonFatal(_) => s"#${System.identityHashCode(e)}" }

  /** `sql` with each name as the binder resolves it, regardless of case and
    * without its quotes, and each string literal as written. Lower-casing the
    * whole text made `GROUP BY s || 'A'` the key `SELECT s || 'a'` read, so
    * the query returned `s || 'A'`; and `"a"` matched no `a`. A name in quotes
    * that no bare word spells (`"x y"`, or `"true"`, which bare is the
    * literal) keeps its quotes. */
  private def canonicalText(sql: String): String = {
    val out = new StringBuilder(sql.length)
    var plain = 0 // where the text not yet copied starts
    var i = 0
    def copyPlain(until: Int): Unit = out ++= sql.substring(plain, until).toLowerCase(Locale.ROOT)
    while (i < sql.length) {
      val q = sql.charAt(i)
      if (q == '\'' || q == '"' || q == '`') {
        copyPlain(i)
        // To just past the closing quote; a doubled quote is inside.
        var end = i + 1
        var closed = false
        while (end < sql.length && !closed) {
          if (sql.charAt(end) != q) end += 1
          else if (end + 1 < sql.length && sql.charAt(end + 1) == q) end += 2
          else { end += 1; closed = true }
        }
        val token = sql.substring(i, end)
        if (q == '\'') out ++= token
        else {
          val name = SqlParser.unquote(token).toLowerCase(Locale.ROOT)
          val bare = Character.isLetter(name.head) || name.head == '_'
          out ++= (if (bare && name.forall(c => Character.isLetterOrDigit(c) || c == '_' || c == '$') &&
              !LiteralWords(name)) name else SqlParser.quote(name))
        }
        i = end
        plain = end
      } else i += 1
    }
    copyPlain(sql.length)
    out.toString
  }

  /** The words JSqlParser reads as literals when they are not quoted. */
  private val LiteralWords = Set("true", "false", "null")

  private def deriveAlias(e: Expression): String = e match {
    case Grouped(inner) => deriveAlias(inner)
    case c: Column => unquoted(c.getColumnName)
    case _ => canonicalSql(e)
  }

  /** The name an identifier written as `written` names ([[SqlParser.unquote]]). */
  private def unquoted(written: String): String = SqlParser.unquote(written)

  /** The name an alias gives a column or a table. An alias is a name, and
    * JSqlParser also takes a string literal in its place, which named the
    * column with the quotes, and made two literals side by side (`SELECT 'a'
    * 'b'`) a literal and its alias; a string there is refused. */
  private def aliasName(alias: net.sf.jsqlparser.expression.Alias): String = {
    val written = alias.getName
    if (written.startsWith("'")) {
      val text = written.substring(1, written.length - 1).replace("''", "'")
      throw new IllegalArgumentException(
        s"An alias is a name, not a string: write AS ${SqlParser.quote(text)} for $written, and join strings with ||")
    }
    unquoted(written)
  }

  /** A column the binder resolves to exactly the field `name` of the source
    * `table`, whatever characters the name holds: each part is written in
    * quotes, which binding reads off again. */
  private def columnNamed(table: Option[String], name: String): Expression =
    new Column((table.toSeq :+ name).map(SqlParser.quote).asJava)

  /** The type a CAST names, by [[FunctionCatalog.CastTargets]]. */
  private def parseTypeName(s: String): DataType =
    FunctionCatalog.castTarget(s).getOrElse(throw new IllegalArgumentException(FunctionCatalog.unsupportedCastTarget(s)))
}
