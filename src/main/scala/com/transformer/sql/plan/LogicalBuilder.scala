package com.transformer.sql.plan

import com.transformer.core.{Catalog, DataType, Schema}
import com.transformer.sql.parse.SqlParser

import net.sf.jsqlparser.expression.{
  AllValue, AnalyticExpression, CaseExpression, CastExpression, DateValue, DoubleValue,
  Expression, Function, LongValue, NotExpression, NullValue, Parenthesis,
  SignedExpression, StringValue, TimestampValue, TimeValue, WhenClause,
  WindowElement, WindowOffset
}
import net.sf.jsqlparser.expression.operators.arithmetic.{
  Addition, Concat, Division, Modulo, Multiplication, Subtraction
}
import net.sf.jsqlparser.expression.operators.conditional.{AndExpression, OrExpression}
import net.sf.jsqlparser.expression.operators.relational.{
  Between, EqualsTo, ExpressionList, GreaterThan, GreaterThanEquals, InExpression,
  IsNullExpression, LikeExpression, MinorThan, MinorThanEquals, NotEqualsTo
}
import net.sf.jsqlparser.schema.Column
import net.sf.jsqlparser.statement.select._

import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Builds a bound [[LogicalPlan]] from a SQL string, given a [[Catalog]]. */
object LogicalBuilder {

  type Sources = Seq[(Option[String], Schema)]

  def build(sql: String, catalog: Catalog): LogicalPlan = {
    val select = SqlParser.parseSelect(sql)
    buildSelect(select, catalog)
  }

  // ---------------------------------------------------------------------------
  // SELECT / FROM
  // ---------------------------------------------------------------------------

  private def buildSelect(ps: PlainSelect, catalog: Catalog): LogicalPlan = {
    val (from, sources) = buildFromAndJoins(ps, catalog)

    // WHERE
    val afterWhere: LogicalPlan = Option(ps.getWhere) match {
      case Some(w) => LogicalFilter(from, Analyzer.implicitCast(bindExpr(w, sources), DataType.BooleanType))
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
          val tableName = atc.getTable.getName
          sources.find(_._1.exists(_.equalsIgnoreCase(tableName))).map(_._2) match {
            case Some(schema) =>
              schema.fields.map { f =>
                val col = new Column(new net.sf.jsqlparser.schema.Table(tableName), f.name)
                (col.asInstanceOf[Expression], f.name)
              }
            case None =>
              throw new IllegalArgumentException(s"Unknown table in '$tableName.*'")
          }
        case _: net.sf.jsqlparser.statement.select.AllColumns =>
          sources.flatMap { case (alias, schema) =>
            schema.fields.map { f =>
              val col = new Column(f.name)
              alias.foreach(a => col.setTable(new net.sf.jsqlparser.schema.Table(a)))
              (col.asInstanceOf[Expression], f.name)
            }
          }
        case other =>
          val alias = Option(si.getAlias).map(_.getName).getOrElse(deriveAlias(other))
          Seq((other, alias))
      }
    }

    val hasGroupBy = Option(ps.getGroupBy).exists(g => Option(g.getGroupByExpressionList).exists(!_.isEmpty))
    val itemsContainAgg = expandedItems.exists { case (e, _) => containsAggregate(e) }
    val isAggregation = hasGroupBy || itemsContainAgg

    // Stage 1: aggregation. `binderFactory` produces a binder parameterized by a
    // window resolver — we call it twice: once with the empty resolver (for
    // window-spec collection) and once with the populated one (for the final
    // projections / ORDER BY).
    val (preWindow, binderFactory): (LogicalPlan, BinderFactory) =
      if (isAggregation) buildAggregation(afterWhere, sources, ps, expandedItems)
      else {
        val factory: BinderFactory = wr => e => bindExprWithAggs(e, sources, _ => None, wr)
        (afterWhere, factory)
      }

    val preWindowBinder: Expression => Expr = binderFactory(_ => None)

    // Stage 2: collect every distinct `OVER (...)` referenced by SELECT items
    // or ORDER BY. The window-function call's internal expressions (partition
    // keys, order keys, function args) bind against the post-aggregation plan
    // via `preWindowBinder`.
    val windowDefs = mutable.LinkedHashMap.empty[String, WindowDef]
    def collectWindows(e: Expression): Unit = e match {
      case ae: AnalyticExpression =>
        val key = canonicalSql(ae)
        if (!windowDefs.contains(key)) {
          val name = s"_win${windowDefs.size}"
          val wd = bindAnalytic(ae, preWindowBinder).copy(outputName = name)
          windowDefs.put(key, wd)
        }
      case _ => childExpressions(e).foreach(collectWindows)
    }
    expandedItems.foreach { case (e, _) => collectWindows(e) }
    Option(ps.getOrderByElements).foreach(_.asScala.foreach(obe => collectWindows(obe.getExpression)))

    val (preProject, exprBinder): (LogicalPlan, Expression => Expr) =
      if (windowDefs.isEmpty) (preWindow, preWindowBinder)
      else {
        val baseLen = preWindow.outputSchema.length
        val keyToWindow: Map[String, (Int, WindowDef)] = windowDefs.zipWithIndex.map {
          case ((k, wd), i) => k -> (baseLen + i, wd)
        }.toMap
        val windowResolver: AnalyticExpression => Option[Expr] = ae =>
          keyToWindow.get(canonicalSql(ae)).map { case (i, wd) =>
            ColRefExpr(i, wd.outputName, wd.fn.resultType)
          }
        (LogicalWindow(preWindow, windowDefs.values.toSeq), binderFactory(windowResolver))
      }

    val projections: Seq[(Expr, String)] = expandedItems.map { case (e, name) =>
      (exprBinder(e), name)
    }

    val afterSort: LogicalPlan = Option(ps.getOrderByElements) match {
      case Some(list) if !list.isEmpty =>
        val keys = list.asScala.map { obe =>
          (exprBinder(obe.getExpression), obe.isAsc)
        }.toSeq
        LogicalSort(preProject, keys)
      case _ => preProject
    }

    val projected: LogicalPlan = LogicalProject(afterSort, projections)

    val afterDistinct: LogicalPlan = Option(ps.getDistinct) match {
      case Some(_) => LogicalDistinct(projected)
      case None => projected
    }

    Option(ps.getLimit).flatMap(l => Option(l.getRowCount)) match {
      case Some(rc: LongValue) => LogicalLimit(afterDistinct, rc.getValue)
      case Some(other) =>
        throw new IllegalArgumentException(s"LIMIT must be an integer literal, got ${other.getClass.getSimpleName}")
      case None => afterDistinct
    }
  }

  // ---------------------------------------------------------------------------
  // Aggregation
  // ---------------------------------------------------------------------------

  /** A "binder factory": given an `AnalyticExpression => Option[Expr]` window
    * resolver, returns a function that binds JSqlParser expressions to bound
    * [[Expr]] trees over the post-aggregation (or pre-aggregation) schema.
    */
  private type BinderFactory = (AnalyticExpression => Option[Expr]) => (Expression => Expr)

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
    val groupExprs: Seq[Expression] = rawGroupExprs.map(resolveGroupOrdinal(_, expandedItems))
    val groupBound: Seq[(Expr, String)] = groupExprs.map { e =>
      val b = bindExpr(e, sources)
      (b, deriveAlias(e))
    }

    // Collect every distinct aggregate referenced by SELECT or HAVING, assign synthetic names.
    val aggMap = mutable.LinkedHashMap.empty[String, (AggExpr, String)]
    def collect(e: Expression): Unit = e match {
      case _: AnalyticExpression => ()  // window's inner aggregates are scoped to the window
      case f: Function if isAggFn(f.getName) =>
        val key = canonicalSql(f)
        if (!aggMap.contains(key)) {
          val name = s"_agg${aggMap.size}"
          aggMap.put(key, (bindAgg(f, sources), name))
        }
      case _ => childExpressions(e).foreach(collect)
    }
    expandedItems.foreach { case (e, _) => collect(e) }
    Option(ps.getHaving).foreach(collect)

    val agg = LogicalAggregate(child, groupBound, aggMap.values.toSeq, None)

    val aggOutSources: Sources = Seq((None, agg.outputSchema))
    val aggIndexes: Map[String, Int] = {
      val gks = groupBound.zipWithIndex.map { case ((_, _), i) => (canonicalSql(groupExprs(i)), i) }
      val ags = aggMap.toSeq.zipWithIndex.map { case ((k, _), i) => (k, groupBound.size + i) }
      (gks ++ ags).toMap
    }
    val aggOutFieldNames: IndexedSeq[String] = agg.outputSchema.fieldNames

    val aggResolver: Function => Option[Expr] = { f =>
      if (isAggFn(f.getName)) {
        val key = canonicalSql(f)
        aggMap.get(key).map { case (_, name) =>
          val i = aggOutFieldNames.indexOf(name)
          ColRefExpr(i, name, agg.outputSchema.fields(i).dataType): Expr
        }
      } else None
    }

    val factory: BinderFactory = { windowResolver =>
      def rebind(e: Expression): Expr = e match {
        case ae: AnalyticExpression =>
          windowResolver(ae).getOrElse(throw new IllegalArgumentException(
            s"Window function in non-window position: $ae"))
        case f: Function if isAggFn(f.getName) =>
          val key = canonicalSql(f)
          val (_, name) = aggMap(key)
          val i = aggOutFieldNames.indexOf(name)
          ColRefExpr(i, name, agg.outputSchema.fields(i).dataType)
        case _ =>
          val key = canonicalSql(e)
          aggIndexes.get(key) match {
            case Some(i) =>
              ColRefExpr(i, aggOutFieldNames(i), agg.outputSchema.fields(i).dataType)
            case None =>
              bindExprWithAggs(e, aggOutSources, aggResolver, windowResolver)
          }
      }
      rebind
    }

    val havingBinder = factory(_ => None)
    val afterHaving: LogicalPlan = Option(ps.getHaving) match {
      case Some(h) =>
        LogicalFilter(agg, Analyzer.implicitCast(havingBinder(h), DataType.BooleanType))
      case None => agg
    }

    (afterHaving, factory)
  }

  // ---------------------------------------------------------------------------
  // FROM + JOINs
  // ---------------------------------------------------------------------------

  private def buildFromAndJoins(ps: PlainSelect, catalog: Catalog): (LogicalPlan, Sources) = {
    val (root, rootSources) = fromItem(ps.getFromItem, catalog)
    val joins = Option(ps.getJoins).map(_.asScala.toSeq).getOrElse(Nil)

    var plan = root
    var srcs = rootSources
    joins.foreach { j =>
      val (rightPlan, rightSources) = fromItem(j.getRightItem, catalog)
      val combinedSources = srcs ++ rightSources
      val onExprs = Option(j.getOnExpressions).map(_.asScala.toSeq).getOrElse(Nil)
      val condition: Expr =
        if (onExprs.isEmpty) LitExpr(true, DataType.BooleanType)
        else {
          val bound = onExprs.map(e => bindExpr(e, combinedSources))
          bound.reduce[Expr] { (a, b) => BinOpExpr("AND", Analyzer.implicitCast(a, DataType.BooleanType),
            Analyzer.implicitCast(b, DataType.BooleanType), DataType.BooleanType) }
        }
      val kind: JoinKind =
        if (j.isFull) JoinKind.Full
        else if (j.isLeft) JoinKind.Left
        else if (j.isRight) JoinKind.Right
        else if (j.isInner || j.isSimple || j.isCross) JoinKind.Inner
        else JoinKind.Inner
      plan = LogicalJoin(plan, rightPlan, condition, kind)
      srcs = combinedSources
    }
    (plan, srcs)
  }

  private def fromItem(item: FromItem, catalog: Catalog): (LogicalPlan, Sources) = item match {
    case t: net.sf.jsqlparser.schema.Table =>
      val name = t.getName
      val alias = Option(t.getAlias).map(_.getName).orElse(Some(name))
      val view = catalog(name)
      (LogicalScan(name, view, alias), Seq((alias, view.schema)))
    case other =>
      throw new IllegalArgumentException(s"Unsupported FROM item: ${other.getClass.getSimpleName}")
  }

  // ---------------------------------------------------------------------------
  // Expression binding
  // ---------------------------------------------------------------------------

  def bindExpr(e: Expression, sources: Sources): Expr =
    bindExprWithAggs(e, sources, _ => None, _ => None)

  /** Variant used inside aggregation: `aggResolver` returns a ColRef into the
    * aggregate output when called with an aggregate Function — letting expressions
    * like `COUNT(*) > 1` (in HAVING / ORDER BY) bind cleanly. `windowResolver`
    * plays the same role for [[AnalyticExpression]]s after [[LogicalWindow]]
    * has appended their output columns to the schema.
    */
  def bindExprWithAggs(
      e: Expression,
      sources: Sources,
      aggResolver: Function => Option[Expr],
      windowResolver: AnalyticExpression => Option[Expr] = _ => None): Expr = {
    def b(x: Expression): Expr = bindExprWithAggs(x, sources, aggResolver, windowResolver)
    e match {
    case p: Parenthesis => b(p.getExpression)
    case c: Column =>
      val q = Option(c.getTable).map(_.getName)
      val raw = c.getColumnName
      if (q.isEmpty && (raw.equalsIgnoreCase("TRUE") || raw.equalsIgnoreCase("FALSE"))) {
        val matches = sources.exists { case (_, schema) => schema.indexOf(raw) >= 0 }
        if (!matches) LitExpr(raw.equalsIgnoreCase("TRUE"), DataType.BooleanType)
        else Analyzer.resolveColumn(q, raw, sources)
      } else Analyzer.resolveColumn(q, raw, sources)
    case l: LongValue =>
      val v = l.getValue
      if (v >= Int.MinValue && v <= Int.MaxValue) LitExpr(v.toInt, DataType.IntType)
      else LitExpr(v, DataType.LongType)
    case d: DoubleValue => LitExpr(d.getValue, DataType.DoubleType)
    case s: StringValue => LitExpr(s.getValue, DataType.StringType)
    case _: NullValue => LitExpr(null, DataType.NullType)
    case d: DateValue => LitExpr(d.getValue.toLocalDate, DataType.DateType)
    case t: TimestampValue => LitExpr(t.getValue.toLocalDateTime, DataType.TimestampType)
    case _: TimeValue => throw new IllegalArgumentException("TIME literal not supported")
    case se: SignedExpression =>
      val sign = se.getSign
      val inner = b(se.getExpression)
      sign match {
        case '-' => UnaryOpExpr("-", inner, inner.dataType)
        case '+' => inner
        case other => throw new IllegalArgumentException(s"Unknown signed operator '$other'")
      }
    case n: NotExpression =>
      UnaryOpExpr("NOT", Analyzer.implicitCast(b(n.getExpression), DataType.BooleanType), DataType.BooleanType)

    case _: AllValue => throw new IllegalArgumentException("ALL is not supported in this position")

    case a: Addition => binopGeneric("+", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Subtraction => binopGeneric("-", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Multiplication => binopGeneric("*", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Division => binopGeneric("/", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Modulo => binopGeneric("%", a.getLeftExpression, a.getRightExpression, sources, aggResolver, windowResolver)
    case a: Concat =>
      val l = b(a.getLeftExpression); val r = b(a.getRightExpression)
      BinOpExpr("||", l, r, DataType.StringType)

    case a: AndExpression =>
      val l = Analyzer.implicitCast(b(a.getLeftExpression), DataType.BooleanType)
      val r = Analyzer.implicitCast(b(a.getRightExpression), DataType.BooleanType)
      BinOpExpr("AND", l, r, DataType.BooleanType)
    case a: OrExpression =>
      val l = Analyzer.implicitCast(b(a.getLeftExpression), DataType.BooleanType)
      val r = Analyzer.implicitCast(b(a.getRightExpression), DataType.BooleanType)
      BinOpExpr("OR", l, r, DataType.BooleanType)

    case x: EqualsTo => cmpOpGeneric("=", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: NotEqualsTo => cmpOpGeneric("<>", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: GreaterThan => cmpOpGeneric(">", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: GreaterThanEquals => cmpOpGeneric(">=", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: MinorThan => cmpOpGeneric("<", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)
    case x: MinorThanEquals => cmpOpGeneric("<=", x.getLeftExpression, x.getRightExpression, sources, aggResolver, windowResolver)

    case in: InExpression =>
      val left = b(in.getLeftExpression)
      val items: Seq[Expression] = in.getRightExpression match {
        case el: ExpressionList[_] => el.asScala.asInstanceOf[Iterable[Expression]].toSeq
        case other => throw new IllegalArgumentException(s"IN subqueries not supported (got ${other.getClass.getSimpleName})")
      }
      val (boundItems, common) = harmonize(items.map(b), left.dataType)
      val leftCast = Analyzer.implicitCast(left, common)
      InListExpr(leftCast, boundItems, in.isNot)

    case bw: Between =>
      val target = b(bw.getLeftExpression)
      val lo = b(bw.getBetweenExpressionStart)
      val hi = b(bw.getBetweenExpressionEnd)
      val (tL, loL, _) = promoteTriple(target, lo)
      val (tR, hiR, t2) = promoteTriple(tL, hi)
      val expr: Expr = BinOpExpr("AND",
        BinOpExpr(">=", tR, Analyzer.implicitCast(loL, t2), DataType.BooleanType),
        BinOpExpr("<=", tR, Analyzer.implicitCast(hiR, t2), DataType.BooleanType),
        DataType.BooleanType)
      if (bw.isNot) UnaryOpExpr("NOT", expr, DataType.BooleanType) else expr

    case lk: LikeExpression =>
      val s = b(lk.getLeftExpression); val p = b(lk.getRightExpression)
      LikeExpr(Analyzer.implicitCast(s, DataType.StringType), Analyzer.implicitCast(p, DataType.StringType), lk.isNot)

    case isn: IsNullExpression =>
      IsNullExpr(b(isn.getLeftExpression), negated = isn.isNot)

    case c: CaseExpression =>
      val switch = Option(c.getSwitchExpression).map(b)
      val branches: Seq[(Expr, Expr)] = c.getWhenClauses.asScala.toSeq.map { wc =>
        val whenExpr = wc.asInstanceOf[WhenClause]
        val cond = switch match {
          case None =>
            Analyzer.implicitCast(b(whenExpr.getWhenExpression), DataType.BooleanType)
          case Some(s) =>
            val whenBound = b(whenExpr.getWhenExpression)
            val (sl, wr, _) = Analyzer.promotePair(s, whenBound)
            BinOpExpr("=", sl, wr, DataType.BooleanType)
        }
        (cond, b(whenExpr.getThenExpression))
      }
      val elseE = Option(c.getElseExpression).map(b)
      val resultType = {
        val types = branches.map(_._2.dataType) ++ elseE.map(_.dataType)
        types.filterNot(_ == DataType.NullType).headOption.getOrElse(DataType.NullType)
      }
      val unifiedBranches = branches.map { case (cond, v) => (cond, Analyzer.implicitCast(v, resultType)) }
      val unifiedElse = elseE.map(Analyzer.implicitCast(_, resultType))
      CaseExpr(unifiedBranches, unifiedElse, resultType)

    case ce: CastExpression =>
      val inner = b(ce.getLeftExpression)
      val target = parseTypeName(ce.getColDataType.getDataType)
      CastExpr(inner, target)

    case f: Function =>
      aggResolver(f) match {
        case Some(expr) => expr
        case None =>
          if (isAggFn(f.getName))
            throw new IllegalArgumentException(s"Aggregate '${f.getName}' used in non-aggregating position")
          bindScalarFunctionWithAggs(f, sources, aggResolver, windowResolver)
      }

    case ae: AnalyticExpression =>
      windowResolver(ae).getOrElse(throw new IllegalArgumentException(
        s"Window function '${ae.getName}' used in non-window position"))

    case other =>
      throw new IllegalArgumentException(s"Unsupported expression: ${other.getClass.getSimpleName}: $other")
    }
  }

  private def binopGeneric(op: String, l: Expression, r: Expression, sources: Sources,
                           aggResolver: Function => Option[Expr],
                           windowResolver: AnalyticExpression => Option[Expr]): Expr = {
    val (le, re, t) = Analyzer.promotePair(
      bindExprWithAggs(l, sources, aggResolver, windowResolver),
      bindExprWithAggs(r, sources, aggResolver, windowResolver))
    BinOpExpr(op, le, re, t)
  }
  private def cmpOpGeneric(op: String, l: Expression, r: Expression, sources: Sources,
                           aggResolver: Function => Option[Expr],
                           windowResolver: AnalyticExpression => Option[Expr]): Expr = {
    val (le, re, _) = Analyzer.promotePair(
      bindExprWithAggs(l, sources, aggResolver, windowResolver),
      bindExprWithAggs(r, sources, aggResolver, windowResolver))
    BinOpExpr(op, le, re, DataType.BooleanType)
  }

  private def promoteTriple(a: Expr, b: Expr): (Expr, Expr, DataType) = {
    val (la, lb, t) = Analyzer.promotePair(a, b)
    (la, lb, t)
  }

  private def harmonize(items: Seq[Expr], leftType: DataType): (Seq[Expr], DataType) = {
    val allTypes = leftType +: items.map(_.dataType)
    val common =
      if (allTypes.forall(t => t == DataType.NullType || DataType.isNumeric(t)))
        allTypes.filter(_ != DataType.NullType).headOption.getOrElse(DataType.NullType)
      else
        allTypes.find(_ != DataType.NullType).getOrElse(DataType.StringType)
    (items.map(Analyzer.implicitCast(_, common)), common)
  }

  private def bindScalarFunctionWithAggs(f: Function, sources: Sources,
                                          aggResolver: Function => Option[Expr],
                                          windowResolver: AnalyticExpression => Option[Expr]): Expr = {
    val args: Seq[Expr] = Option(f.getParameters)
      .map(_.asInstanceOf[ExpressionList[_]].asScala.asInstanceOf[Iterable[Expression]].toSeq.map(bindExprWithAggs(_, sources, aggResolver, windowResolver)))
      .getOrElse(Nil)
    val argTypes = args.map(_.dataType)
    val rt = Funcs.returnType(f.getName, argTypes)
      .getOrElse(throw new IllegalArgumentException(s"Unknown function '${f.getName}(${argTypes.mkString(",")})'"))
    FuncExpr(f.getName.toUpperCase, args, rt)
  }

  private def bindAgg(f: Function, sources: Sources): AggExpr = {
    val upper = f.getName.toUpperCase
    val params: Seq[Expression] = Option(f.getParameters)
      .map(_.asInstanceOf[ExpressionList[_]].asScala.asInstanceOf[Iterable[Expression]].toSeq)
      .getOrElse(Nil)
    val distinct = f.isDistinct
    val isStarArg = f.isAllColumns ||
      (params.size == 1 && params.head.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns])
    upper match {
      case "COUNT" if isStarArg || params.isEmpty => AggExprCountStar()
      case "COUNT" if params.size == 1 =>
        AggExprCount(bindExpr(params.head, sources), distinct)
      case "SUM" if params.size == 1 =>
        AggExprSum(bindExpr(params.head, sources))
      case "AVG" if params.size == 1 =>
        AggExprAvg(bindExpr(params.head, sources))
      case "MIN" if params.size == 1 =>
        AggExprMin(bindExpr(params.head, sources))
      case "MAX" if params.size == 1 =>
        AggExprMax(bindExpr(params.head, sources))
      case "COUNT_IF" if params.size == 1 =>
        AggExprCountIf(Analyzer.implicitCast(bindExpr(params.head, sources), DataType.BooleanType))
      case _ =>
        throw new IllegalArgumentException(s"Unsupported aggregate: ${f.getName}(${params.size} args)")
    }
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private val AggFns: Set[String] = Set("COUNT", "SUM", "AVG", "MIN", "MAX", "COUNT_IF")
  private def isAggFn(name: String): Boolean = AggFns.contains(name.toUpperCase)

  private def containsAggregate(e: Expression): Boolean = e match {
    case _: AnalyticExpression => false  // window aggregates are not group aggregates
    case f: Function if isAggFn(f.getName) => true
    case _ => childExpressions(e).exists(containsAggregate)
  }

  private def childExpressions(e: Expression): Seq[Expression] = e match {
    case p: Parenthesis => Seq(p.getExpression)
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

  /** Convert a JSqlParser [[AnalyticExpression]] into a bound [[WindowDef]].
    * The window's internal expressions (partition keys, order keys, function
    * arg) bind via `binder` against the post-aggregation (or raw-source) schema.
    * `outputName` is filled in by the caller — we pass an empty placeholder.
    */
  private def bindAnalytic(ae: AnalyticExpression, binder: Expression => Expr): WindowDef = {
    val partitionExprs: Seq[Expression] = Option(ae.getPartitionExpressionList)
      .map(_.asScala.asInstanceOf[Iterable[Expression]].toSeq).getOrElse(Nil)
    val partitionKeys: Seq[Expr] = partitionExprs.map(binder)

    val orderEls = Option(ae.getOrderByElements).map(_.asScala.toSeq).getOrElse(Nil)
    val orderKeys: Seq[(Expr, Boolean)] = orderEls.map(obe => (binder(obe.getExpression), obe.isAsc))

    val frame = bindFrame(Option(ae.getWindowElement), orderEls.nonEmpty)
    val fnName = ae.getName.toUpperCase

    val argExpr: Option[Expression] = Option(ae.getExpression).filterNot(_.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns])
    val isStarArg: Boolean = ae.isAllColumns ||
      Option(ae.getExpression).exists(_.isInstanceOf[net.sf.jsqlparser.statement.select.AllColumns])

    val fn: WindowFn = fnName match {
      case "ROW_NUMBER" => WindowFnRowNumber()
      case "RANK" => WindowFnRank()
      case "DENSE_RANK" => WindowFnDenseRank()
      case "LAG" =>
        val arg = argExpr.map(binder).getOrElse(
          throw new IllegalArgumentException("LAG requires an argument"))
        val offset = Option(ae.getOffset).map(extractNonNegativeInt("LAG offset")).getOrElse(1)
        val default = Option(ae.getDefaultValue).map(binder)
        WindowFnLag(arg, offset, default)
      case "LEAD" =>
        val arg = argExpr.map(binder).getOrElse(
          throw new IllegalArgumentException("LEAD requires an argument"))
        val offset = Option(ae.getOffset).map(extractNonNegativeInt("LEAD offset")).getOrElse(1)
        val default = Option(ae.getDefaultValue).map(binder)
        WindowFnLead(arg, offset, default)
      case "COUNT" =>
        if (isStarArg || argExpr.isEmpty) WindowFnAgg(AggExprCountStar())
        else WindowFnAgg(AggExprCount(binder(argExpr.get), distinct = ae.isDistinct))
      case "SUM" =>
        WindowFnAgg(AggExprSum(binder(argExpr.getOrElse(
          throw new IllegalArgumentException("SUM requires an argument")))))
      case "AVG" =>
        WindowFnAgg(AggExprAvg(binder(argExpr.getOrElse(
          throw new IllegalArgumentException("AVG requires an argument")))))
      case "MIN" =>
        WindowFnAgg(AggExprMin(binder(argExpr.getOrElse(
          throw new IllegalArgumentException("MIN requires an argument")))))
      case "MAX" =>
        WindowFnAgg(AggExprMax(binder(argExpr.getOrElse(
          throw new IllegalArgumentException("MAX requires an argument")))))
      case "COUNT_IF" =>
        val bound = binder(argExpr.getOrElse(
          throw new IllegalArgumentException("COUNT_IF requires an argument")))
        WindowFnAgg(AggExprCountIf(Analyzer.implicitCast(bound, DataType.BooleanType)))
      case other =>
        throw new IllegalArgumentException(s"Unsupported window function: $other")
    }

    WindowDef(WindowSpec(partitionKeys, orderKeys, frame), fn, outputName = "")
  }

  private def bindFrame(we: Option[WindowElement], hasOrderBy: Boolean): WindowFrame = we match {
    case None => WindowFrame.defaultFor(hasOrderBy)
    case Some(elem) =>
      val frameType: FrameType = elem.getType match {
        case WindowElement.Type.ROWS => FrameType.Rows
        case WindowElement.Type.RANGE => FrameType.Range
      }
      // Two shapes: BETWEEN ... AND ... → range; or single bound → offset.
      val (start, end): (FrameBound, FrameBound) =
        Option(elem.getRange) match {
          case Some(range) =>
            (bindFrameBound(range.getStart), bindFrameBound(range.getEnd))
          case None =>
            val s = Option(elem.getOffset).map(bindFrameBound).getOrElse(FrameBound.UnboundedPreceding)
            (s, FrameBound.CurrentRow)
        }
      WindowFrame(frameType, start, end)
  }

  private def bindFrameBound(wo: WindowOffset): FrameBound = wo.getType match {
    case WindowOffset.Type.CURRENT => FrameBound.CurrentRow
    case WindowOffset.Type.PRECEDING =>
      Option(wo.getExpression) match {
        case None => FrameBound.UnboundedPreceding
        case Some(e) => FrameBound.Preceding(extractNonNegativeInt("PRECEDING offset")(e).toLong)
      }
    case WindowOffset.Type.FOLLOWING =>
      Option(wo.getExpression) match {
        case None => FrameBound.UnboundedFollowing
        case Some(e) => FrameBound.Following(extractNonNegativeInt("FOLLOWING offset")(e).toLong)
      }
    case WindowOffset.Type.EXPR =>
      throw new IllegalArgumentException(s"Unsupported frame bound: ${wo}")
  }

  private def extractNonNegativeInt(label: String)(e: Expression): Int = e match {
    case lv: LongValue if lv.getValue >= 0 && lv.getValue <= Int.MaxValue => lv.getValue.toInt
    case _ => throw new IllegalArgumentException(s"$label must be a non-negative integer literal, got: $e")
  }

  /** Reconstruct an Expression bound against a new source set, recursing through
    * `childRewriter` (used to substitute aggregate output column refs).
    */
  private def rebuildExpr(
      e: Expression,
      sources: Sources,
      postAgg: Boolean,
      childRewriter: Expression => Expr): Expr = e match {
    case p: Parenthesis => childRewriter(p.getExpression)
    case _ => bindExpr(e, sources)
  }

  /** Resolve `GROUP BY N` (1-based positional reference) to the underlying
    * SELECT-list expression. Matches BigQuery / Postgres / MySQL behavior.
    * Only bare integer literals are treated as ordinals — `GROUP BY (1)` or
    * `GROUP BY +1` remain literal expressions. */
  private def resolveGroupOrdinal(
      e: Expression,
      expandedItems: Seq[(Expression, String)]): Expression = e match {
    case lv: LongValue =>
      val v = lv.getValue
      if (v < 1 || v > expandedItems.size)
        throw new IllegalArgumentException(
          s"GROUP BY position $v is not in select list (size ${expandedItems.size})")
      val (expr, _) = expandedItems((v - 1).toInt)
      if (containsAggregate(expr))
        throw new IllegalArgumentException(
          s"GROUP BY position $v cannot refer to aggregate expression in select list")
      expr
    case _ => e
  }

  private def canonicalSql(e: Expression): String = e.toString.toLowerCase

  private def deriveAlias(e: Expression): String = e match {
    case c: Column => c.getColumnName
    case _ => e.toString.toLowerCase
  }

  private def parseTypeName(s: String): DataType = s.toUpperCase match {
    case "INT" | "INTEGER" => DataType.IntType
    case "BIGINT" | "LONG" => DataType.LongType
    case "FLOAT" | "REAL" => DataType.FloatType
    case "DOUBLE" | "DOUBLE PRECISION" => DataType.DoubleType
    case "STRING" | "VARCHAR" | "TEXT" | "CHAR" => DataType.StringType
    case "BOOLEAN" | "BOOL" => DataType.BooleanType
    case "DATE" => DataType.DateType
    case "TIMESTAMP" => DataType.TimestampType
    case other => throw new IllegalArgumentException(s"Unsupported CAST target type: $other")
  }
}
