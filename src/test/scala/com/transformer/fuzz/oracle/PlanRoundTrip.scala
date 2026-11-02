package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, Field, Schema}
import com.transformer.sql.exec.CteResolver
import com.transformer.sql.parse.NestingLimitException
import com.transformer.sql.plan._

import java.util.Locale

/** A plan printed as SQL binds back to itself: DuckDB's re-parse
  * verification, over [[PlanSql]] and [[LogicalBuilder]].
  *
  * For a query `q` that binds to `p`, `print(p)` must bind back to a plan
  * equal to `p` once each is [[normalize]]d, with the columns `p` returns, by
  * name and type; and printing that plan must give the same text again. A
  * plan the printer cannot write, and printed text that nests deeper than the
  * parser's limit, are verdicts of their own, which a caller counts.
  *
  * The optimized plan is printed too ([[optimizedSql]]): run with the
  * optimizer off, it must answer as `q` does, which checks each pass through
  * SQL the binder reads afresh.
  */
object PlanRoundTrip {

  sealed trait Verdict
  /** `q` printed and bound back to its own plan. */
  final case class Same(printed: String) extends Verdict
  /** The printer cannot write the plan, for `reason`. */
  final case class Unprintable(reason: String) extends Verdict
  /** The printed text nests past the parser's limit, which the query itself
    * was within: casts and parentheses the printer writes add levels. */
  final case class TooDeep(reason: String) extends Verdict

  /** Check `sql`, which binds over `catalog`; an [[AssertionError]] says how
    * the round trip failed. */
  def check(sql: String, catalog: Catalog): Verdict = {
    val built = LogicalBuilder.build(sql, catalog)
    val printed =
      try PlanSql.print(built, catalog)
      catch { case e: PlanSql.UnprintablePlanException => return Unprintable(e.getMessage) }
    def fail(why: String): Nothing =
      throw new AssertionError(s"[plan round trip] $why\n  query:   $sql\n  printed:\n${indent(printed)}")
    val rebuilt =
      try LogicalBuilder.build(printed, catalog)
      catch {
        case e: NestingLimitException => return TooDeep(e.getMessage)
        case e: Exception => fail(s"the printed query does not bind: $e")
      }
    val (was, is) = (built.main.outputSchema, rebuilt.main.outputSchema)
    if (was.fields.map(f => (f.name, f.dataType)) != is.fields.map(f => (f.name, f.dataType)))
      fail(s"the printed query returns ${columns(is)}, not ${columns(was)}")
    val (expected, actual) = (normalize(inlined(built)), normalize(inlined(rebuilt)))
    if (expected != actual) fail(s"it binds to another plan\n  plan:    $expected\n  rebound: $actual")
    val reprinted =
      try PlanSql.print(rebuilt, catalog)
      catch { case e: PlanSql.UnprintablePlanException => fail(s"its own plan does not print: ${e.getMessage}") }
    if (reprinted != printed) fail(s"printing its plan gives other text:\n${indent(reprinted)}")
    Same(printed)
  }

  /** `sql`'s plan as the optimizer leaves it, printed: the main query and each
    * CTE read twice or more, each optimized, as [[CteResolver.planned]] leaves
    * them. None when the printer cannot write it. */
  def optimizedSql(sql: String, catalog: Catalog): Option[String] = {
    val planned = CteResolver.planned(LogicalBuilder.build(sql, catalog))
    val optimized = BuiltQuery(LogicalOptimizer.optimize(planned.main),
      planned.ctes.map(c => c.copy(body = LogicalOptimizer.optimize(c.body))))
    try Some(PlanSql.print(optimized, catalog))
    catch { case _: PlanSql.UnprintablePlanException => None }
  }

  /** `built`'s main plan with every scan of one of its CTEs replaced by the
    * CTE's body, however often it is read: what its text means, whichever
    * CTEs it was written with. */
  def inlined(built: BuiltQuery): LogicalPlan = {
    val bodies = built.ctes.map(_.body).toArray
    var main = built.main
    built.ctes.indices.foreach { i =>
      val cte = built.ctes(i)
      ((i + 1) until bodies.length).foreach(j => bodies(j) = substitute(bodies(j), cte.pending, bodies(i)))
      main = substitute(main, cte.pending, bodies(i))
    }
    main
  }

  private def substitute(plan: LogicalPlan, pending: PendingCteView, body: LogicalPlan): LogicalPlan = plan match {
    case LogicalScan(_, view, _) if view eq pending => body
    case other => other.withChildren(other.children.map(substitute(_, pending, body)))
  }

  /** What a scan reads, for comparing plans: a table's name and columns. */
  final case class ShapeView(schema: Schema) extends CatalogView {
    def numPartitions: Int = 0
    def readPartition(p: Int): Iterator[ColumnarBatch] = Iterator.empty
  }

  /** `plan` with what binding it again may change set aside, so two plans
    * that mean the same thing are equal:
    *   - names the binder or the printer chooses: a column reference's, a
    *     scan's alias, a projection's, an aggregate's and a window's output
    *     names (the plan's own output names are compared apart);
    *   - a projection that passes every column of its input on in order,
    *     which a CTE's `SELECT` list adds over a query that ends in none;
    *   - a projection of some of a scan's columns, which is a scan of those
    *     columns, as the optimizer prunes one;
    *   - a negation of a number, which is the negative number: the printer
    *     writes `-5` for the literal -5, and the binder reads the negation.
    */
  def normalize(plan: LogicalPlan): LogicalPlan = plan match {
    case LogicalScan(name, view, _) =>
      LogicalScan(name.toLowerCase(Locale.ROOT), ShapeView(shape(view.schema)), None)
    case LogicalProject(child, projections) =>
      val c = normalize(child)
      val ps = projections.map { case (e, _) => (erase(e), "") }
      val identity = ps.size == c.outputSchema.length && ps.zipWithIndex.forall {
        case ((ColRefExpr(i, _, _), _), j) => i == j
        case _ => false
      }
      (c, ps) match {
        case _ if identity => c
        case (LogicalScan(name, ShapeView(schema), _), _) if ps.forall(_._1.isInstanceOf[ColRefExpr]) =>
          LogicalScan(name, ShapeView(Schema(ps.map(p => schema.fields(p._1.asInstanceOf[ColRefExpr].index)).toVector)), None)
        case _ => LogicalProject(c, ps)
      }
    case LogicalFilter(child, predicate) => LogicalFilter(normalize(child), erase(predicate))
    case LogicalAggregate(child, keys, aggregates) =>
      LogicalAggregate(normalize(child), keys.map(k => (erase(k._1), "")), aggregates.map(a => (eraseAgg(a._1), "")))
    case LogicalJoin(left, right, condition, kind) => LogicalJoin(normalize(left), normalize(right), erase(condition), kind)
    case LogicalSort(child, keys) => LogicalSort(normalize(child), keys.map(k => k.copy(expr = erase(k.expr))))
    case LogicalLimit(child, n) => LogicalLimit(normalize(child), n)
    case LogicalOffset(child, n) => LogicalOffset(normalize(child), n)
    case LogicalDistinct(child) => LogicalDistinct(normalize(child))
    case LogicalUnion(left, right, all) => LogicalUnion(normalize(left), normalize(right), all)
    case LogicalWindow(child, windows) =>
      LogicalWindow(normalize(child), windows.map { w =>
        val spec = WindowSpec(w.spec.partitionKeys.map(erase), w.spec.orderKeys.map(k => k.copy(expr = erase(k.expr))),
          w.spec.frame)
        val fn = w.fn match {
          case WindowFnLag(v, offset, default) => WindowFnLag(erase(v), offset, default.map(erase))
          case WindowFnLead(v, offset, default) => WindowFnLead(erase(v), offset, default.map(erase))
          case WindowFnAgg(a) => WindowFnAgg(eraseAgg(a))
          case other => other
        }
        WindowDef(spec, fn, "")
      })
  }

  private def shape(s: Schema): Schema =
    Schema(s.fields.map(f => Field(f.name.toLowerCase(Locale.ROOT), f.dataType)))

  /** A float's bits, which tell -0.0 from 0.0 and hold NaN equal to itself,
    * where Scala's `==` on two boxed floats does neither. */
  final case class FloatBits(bits: Long)

  private def erase(e: Expr): Expr = e match {
    case ColRefExpr(i, _, t) => ColRefExpr(i, "", t)
    case LitExpr(d: java.lang.Double, t) => LitExpr(FloatBits(java.lang.Double.doubleToLongBits(d)), t)
    case LitExpr(f: java.lang.Float, t) => LitExpr(FloatBits(java.lang.Float.floatToIntBits(f).toLong), t)
    case l: LitExpr => l
    case CastExpr(c, t) => CastExpr(erase(c), t)
    case DecimalCastExpr(c, t) => DecimalCastExpr(erase(c), t)
    case UnaryOpExpr("-", c, t) => c match {
      case LitExpr(v, lt) if lt == t => negated(v, t).map(erase).getOrElse(UnaryOpExpr("-", erase(c), t))
      case _ => UnaryOpExpr("-", erase(c), t)
    }
    case UnaryOpExpr(op, c, t) => UnaryOpExpr(op, erase(c), t)
    case BinOpExpr(op, l, r, t) => BinOpExpr(op, erase(l), erase(r), t)
    case FuncExpr(n, args, t) => FuncExpr(n, args.map(erase), t)
    case CaseExpr(branches, elseExpr, t) =>
      CaseExpr(branches.map { case (w, v) => (erase(w), erase(v)) }, elseExpr.map(erase), t)
    case SwitchCaseExpr(operand, branches, elseExpr, t) =>
      SwitchCaseExpr(erase(operand), branches.map(b => b.copy(when = erase(b.when), value = erase(b.value))),
        elseExpr.map(erase), t)
    case BetweenExpr(c, low, high) => BetweenExpr(erase(c), erase(low), erase(high))
    case IsNullExpr(c, negated) => IsNullExpr(erase(c), negated)
    case InListExpr(c, items, negated) => InListExpr(erase(c), items.map(erase), negated)
    case l: LikeExpr => l.copy(child = erase(l.child), pattern = erase(l.pattern))
  }

  /** `-v` as a literal of `t`, where it is one. */
  private def negated(v: Any, t: com.transformer.core.DataType): Option[LitExpr] = (v, t) match {
    case (null, _) => None
    case (i: Integer, com.transformer.core.DataType.IntType) if i != Int.MinValue => Some(LitExpr(-i.intValue, t))
    case (l: java.lang.Long, com.transformer.core.DataType.LongType) if l != Long.MinValue => Some(LitExpr(-l.longValue, t))
    case (f: java.lang.Float, com.transformer.core.DataType.FloatType) => Some(LitExpr(-f.floatValue, t))
    case (d: java.lang.Double, com.transformer.core.DataType.DoubleType) => Some(LitExpr(-d.doubleValue, t))
    case (d: java.math.BigDecimal, _: com.transformer.core.DataType.DecimalType) => Some(LitExpr(d.negate, t))
    case _ => None
  }

  private def eraseAgg(a: AggExpr): AggExpr = a match {
    case c: AggExprCountStar => c
    case AggExprCount(c, distinct) => AggExprCount(erase(c), distinct)
    case AggExprSum(c) => AggExprSum(erase(c))
    case AggExprAvg(c) => AggExprAvg(erase(c))
    case AggExprMin(c) => AggExprMin(erase(c))
    case AggExprMax(c) => AggExprMax(erase(c))
    case AggExprCountIf(c) => AggExprCountIf(erase(c))
    case AggExprStddev(c, sample) => AggExprStddev(erase(c), sample)
    case AggExprVariance(c, sample) => AggExprVariance(erase(c), sample)
    case AggExprCovar(x, y, sample) => AggExprCovar(erase(x), erase(y), sample)
    case AggExprCorr(x, y) => AggExprCorr(erase(x), erase(y))
    case AggExprDistinct(inner) => AggExprDistinct(eraseAgg(inner))
  }

  private def columns(s: Schema): String = s.fields.map(f => s"${f.name} ${f.dataType}").mkString("(", ", ", ")")

  private def indent(text: String): String = text.split("\n").map("    " + _).mkString("\n")
}
