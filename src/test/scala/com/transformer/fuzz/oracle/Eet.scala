package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, DataType, ExecutionOptions, Schema}
import com.transformer.fuzz.{QueryGen, Rng, RowOracle, Shrinker}
import com.transformer.sql.exec.NestedLoopGuardException
import com.transformer.sql.parse.NestingLimitException
import com.transformer.sql.plan._

import scala.collection.mutable

/** Value-preserving rewrites, EET's relation (OSDI 2024): wrap a
  * subexpression of a query in a form that cannot change its value, and the
  * answer must not change.
  *
  * The rewrite works on the bound plan, so it reaches every position the
  * binder makes, for every generator and every slt script at once: up to
  * three subexpressions anywhere in it ([[Positions]]: a filter, a HAVING, a
  * projection, a join condition, an aggregate's argument, a GROUP BY key, a
  * window's argument, partition key or order key, or a CASE branch inside any
  * of them) are each replaced by one of the [[Forms]], and the plan is printed
  * as SQL ([[PlanSql]]), which binds back to it, and run in the query's place.
  * For an expression `e` of type T and a condition `p`, the forms are:
  *
  *   - `CASE WHEN FALSE THEN r ELSE e END` and `IF(TRUE, e, r)`, where `r` is
  *     NULL, a column or a literal of exactly T, or a value that fails on
  *     every row it reads (a CAST of text that is no value of T): a
  *     conditional evaluates only what its answer needs, so `r` never runs;
  *   - `CASE WHEN q THEN e ELSE e END`, `q` a condition over the columns in
  *     scope that is NULL where its column is;
  *   - `COALESCE(e, e)` and `CASE WHEN e IS NULL THEN NULL ELSE e END`, which,
  *     like `CASE WHEN q`, write `e` twice, so take an `e` of twelve nodes or
  *     fewer: twice a deep condition, JSqlParser backtracks to its time limit;
  *   - for a condition, `p AND (q OR NOT q OR q IS NULL)`, `p OR FALSE`,
  *     `NOT (NOT (p))` and `CASE WHEN p THEN TRUE WHEN NOT p THEN FALSE END`,
  *     NULL where `p` is;
  *   - an IN list with one of its items written twice.
  *
  * The rewrite is sound because a bound plan reads columns by position and
  * PlanSql writes each one qualified, so no rule that reads a bare name, ORDER
  * BY's output names or GROUP BY's matching of its items, meets a wrapped one;
  * and `r` has T exactly, as the binder's one rule for branches requires
  * (`Analyzer.branches`), so no wrapped expression changes type. A constant,
  * an expression that reads no column, is never wrapped: the binder reads
  * one where it stands (a ROUND's scale fixes its result's type, a number
  * beside a DECIMAL is the decimal of its digits, a LIKE pattern is compiled
  * as it binds), which a form around it would hide.
  *
  * Where a rewrite could change the order rows reach an operator whose answer
  * depends on it, nothing below that operator is rewritten ([[frozen]]): a
  * window function two tied rows can get different values from (`ROW_NUMBER`,
  * `LAG`, `LEAD`, an aggregate over a ROWS frame), whose own keys are frozen
  * too, and a LIMIT or OFFSET whose ORDER BY does not cover every output
  * column, which may keep either of two tied rows. A rewritten join condition
  * can move a join off its hash path, and a pushed filter onto another side,
  * either of which orders rows another way. A query that reads RAND() or the
  * clock is not rewritten at all.
  *
  * The two answers must agree as [[RelEngine.answer]]s: as multisets, or both
  * failing on their data, but where a WHERE or ON condition of the query that
  * can fail is there to move (decision 1 of plans/testing/15), since a
  * rewritten condition may be evaluated on rows the query's was not.
  */
object Eet {

  /** The slots a rewrite stands in, by name. A CASE branch is any of them
    * whose expression is a WHEN, THEN or ELSE of a CASE. */
  val Positions: Seq[String] = Seq("filter", "having", "projection", "join condition", "aggregate argument",
    "group key", "window argument", "window partition key", "window order key", "CASE branch")

  /** The forms, by name. */
  val Forms: Seq[String] = Seq("CASE WHEN FALSE", "IF(TRUE)", "CASE WHEN q", "COALESCE", "CASE WHEN IS NULL",
    "AND a tautology", "OR FALSE", "NOT NOT", "CASE of a condition", "IN list repeat")

  /** One rewrite: the slot it stands in, whether its expression is a branch
    * of a CASE, the form, and whether its unused branch fails on every row it
    * reads. */
  final case class Rewrite(position: String, inCaseBranch: Boolean, form: String, failingBranch: Boolean)

  /** A query with its rewrites, and its plan printed. */
  final case class Rewritten(built: BuiltQuery, rewrites: Vector[Rewrite]) {
    def printed(catalog: Catalog): String = PlanSql.print(built, catalog)
  }

  /** Whether `sql`, bound as `built`, reads RAND() or the clock, so that two
    * runs of it, or of it and its rewrite, may answer differently. The binder
    * reads the clock as it binds, into a literal, so the text says where. */
  def nondeterministic(sql: String, built: BuiltQuery): Boolean = {
    val upper = sql.toUpperCase(java.util.Locale.ROOT)
    upper.contains("CURRENT_DATE") || upper.contains("CURRENT_TIMESTAMP") ||
      (built.main +: built.ctes.map(_.body)).exists(p => planExprs(p).exists(Expr.readsRandom))
  }

  /** Every expression of `p`, in any slot, its CTEs' apart. */
  def planExprs(p: LogicalPlan): Seq[Expr] = {
    val here: Seq[Expr] = p match {
      case LogicalFilter(_, pred) => Seq(pred)
      case LogicalProject(_, ps) => ps.map(_._1)
      case LogicalAggregate(_, keys, aggs) => keys.map(_._1) ++ aggs.flatMap(_._1.args)
      case LogicalJoin(_, _, cond, _) => Seq(cond)
      case LogicalSort(_, keys) => keys.map(_.expr)
      case LogicalWindow(_, ws) =>
        ws.flatMap(w => w.spec.partitionKeys ++ w.spec.orderKeys.map(_.expr) ++ windowArgs(w.fn))
      case _ => Nil
    }
    here ++ p.children.flatMap(planExprs)
  }

  private def windowArgs(fn: WindowFn): Seq[Expr] = fn match {
    case WindowFnLag(v, _, d) => v +: d.toSeq
    case WindowFnLead(v, _, d) => v +: d.toSeq
    case WindowFnAgg(a) => a.args
    case _ => Nil
  }

  /** Whether any WHERE or ON condition of `built` can fail on its data
    * (`QueryGen.mayFail`): what the optimizer may evaluate on rows the
    * written order would not, so a run of the query and a run of its rewrite
    * may part on whether they fail. */
  def movableFailure(built: BuiltQuery): Boolean = {
    def conditions(p: LogicalPlan): Seq[Expr] = (p match {
      case LogicalFilter(_, pred) => Seq(pred)
      case LogicalJoin(_, _, cond, _) => Seq(cond)
      case _ => Nil
    }) ++ p.children.flatMap(conditions)
    (built.main +: built.ctes.map(_.body)).flatMap(conditions).exists(QueryGen.mayFail)
  }

  /** Whether a window function's value at a row can depend on which of two
    * rows its ORDER BY ties comes first. */
  def tieSensitive(w: WindowDef): Boolean = w.fn match {
    case _: WindowFnRowNumber | _: WindowFnLag | _: WindowFnLead => true
    case _: WindowFnRank | _: WindowFnDenseRank => false
    case WindowFnAgg(_) =>
      w.spec.orderKeys.nonEmpty && w.spec.frame.frameType == FrameType.Rows && !w.spec.frame.isWholePartition
  }

  // ---------------------------------------------------------------------------
  // The rewrite
  // ---------------------------------------------------------------------------

  /** One expression slot of a plan: the expression, its position, the columns
    * in scope (`scope`, of which a rewrite's own condition or branch reads the
    * first `readable`: a projection over a window reads the window's input,
    * never its values), and whether it is frozen. */
  private final case class Slot(position: String, scope: Schema, readable: Int, frozen: Boolean)

  /** `built` with up to `maxRewrites` subexpressions rewritten, each at a
    * position and in a form `rng` draws. `keepConditions` keeps every WHERE
    * and ON condition as written, so each join hashes on the equalities it
    * did. */
  def rewrite(built: BuiltQuery, rng: Rng, maxRewrites: Int = 3, keepConditions: Boolean = false): Rewritten = {
    // First pass: count the candidates, in a fixed order.
    var count = 0
    val inLists = mutable.ArrayBuffer.empty[Int]
    val countAll: (Expr, Slot, Boolean) => Expr = { (e, slot, _) =>
      if (eligible(e, slot, keepConditions)) {
        if (e.isInstanceOf[InListExpr]) inLists += count
        count += 1
      }
      e
    }
    mapAll(built, countAll)
    if (count == 0) return Rewritten(built, Vector.empty)
    val k = math.min(count, rng.between(1, maxRewrites))
    val chosen = mutable.HashSet.empty[Int]
    // An IN list is one node among many, and the one its form needs: one is
    // chosen on half the plans that have one.
    if (inLists.nonEmpty && rng.bool()) chosen += rng.oneOf(inLists.toSeq)
    while (chosen.size < k) chosen += rng.nextInt(count)
    // Second pass: rewrite the chosen ones, innermost first, so a rewrite
    // inside another is kept by it.
    var index = 0
    val done = Vector.newBuilder[Rewrite]
    val rewriteChosen: (Expr, Slot, Boolean) => Expr = { (e, slot, inCase) =>
      if (!eligible(e, slot, keepConditions)) e
      else {
        val here = index
        index += 1
        if (!chosen(here)) e
        else {
          val (out, form, failing) = wrap(e, slot, rng)
          done += Rewrite(slot.position, inCase, form, failing)
          out
        }
      }
    }
    val out = mapAll(built, rewriteChosen)
    Rewritten(out, done.result())
  }

  private def eligible(e: Expr, slot: Slot, keepConditions: Boolean): Boolean =
    !slot.frozen && !(keepConditions && (slot.position == "join condition" || slot.position == "filter")) &&
      readsAColumn(e) && (e match {
        // The binder's own casts to a DECIMAL or a BINARY, which PlanSql leaves
        // to the binder to insert again.
        case CastExpr(_, _: DataType.DecimalType | DataType.BinaryType) => false
        case _: SwitchCaseExpr | _: BetweenExpr => false
        case _ => e.dataType != DataType.NullType
      })

  private def readsAColumn(e: Expr): Boolean = e match {
    case _: ColRefExpr => true
    case other => Shrinker.childrenOf(other).exists(readsAColumn)
  }

  /** `built` with `f` applied to every subexpression of every slot, post
    * order, with whether it is a branch of a CASE. */
  private def mapAll(built: BuiltQuery, f: (Expr, Slot, Boolean) => Expr): BuiltQuery = {
    val ctes = built.ctes.map(c => c.copy(body = mapPlan(c.body, frozen = false, f)))
    BuiltQuery(mapPlan(built.main, frozen = false, f), ctes)
  }

  private def mapExpr(e: Expr, slot: Slot, inCase: Boolean, f: (Expr, Slot, Boolean) => Expr): Expr = {
    val rebuilt = e match {
      case CaseExpr(branches, elseExpr, t) =>
        CaseExpr(branches.map { case (w, v) => (mapExpr(w, slot, inCase = true, f), mapExpr(v, slot, inCase = true, f)) },
          elseExpr.map(mapExpr(_, slot, inCase = true, f)), t)
      case _: LitExpr | _: ColRefExpr => e
      case other =>
        val kids = Shrinker.childrenOf(other)
        Shrinker.rebuild(other, kids.map(mapExpr(_, slot, inCase = false, f)))
    }
    f(rebuilt, slot, inCase)
  }

  private def mapAgg(a: AggExpr, slot: Slot, f: (Expr, Slot, Boolean) => Expr): AggExpr = {
    def m(e: Expr) = mapExpr(e, slot, inCase = false, f)
    a match {
      case c: AggExprCountStar => c
      case AggExprCount(c, distinct) => AggExprCount(m(c), distinct)
      case AggExprSum(c) => AggExprSum(m(c))
      case AggExprAvg(c) => AggExprAvg(m(c))
      case AggExprMin(c) => AggExprMin(m(c))
      case AggExprMax(c) => AggExprMax(m(c))
      case AggExprCountIf(c) => AggExprCountIf(m(c))
      case AggExprStddev(c, sample) => AggExprStddev(m(c), sample)
      case AggExprVariance(c, sample) => AggExprVariance(m(c), sample)
      case AggExprCovar(x, y, sample) => AggExprCovar(m(x), m(y), sample)
      case AggExprCorr(x, y) => AggExprCorr(m(x), m(y))
      case AggExprDistinct(inner) => AggExprDistinct(mapAgg(inner, slot, f))
    }
  }

  /** Whether the rows `p` keeps can depend on the order its input arrives
    * in: a LIMIT or OFFSET over a sort whose keys do not cover every output
    * column, or over no sort. */
  private def keepsTiedRowsByOrder(p: LogicalPlan): Boolean = {
    def sortOf(x: LogicalPlan): Option[(LogicalSort, Int)] = x match {
      case LogicalLimit(c, _) => sortOf(c)
      case LogicalOffset(c, _) => sortOf(c)
      case LogicalProject(s @ LogicalSort(_, _), trim) => Some((s, trim.size))
      case s @ LogicalSort(c, _) => Some((s, c.outputSchema.length))
      case _ => None
    }
    sortOf(p) match {
      case Some((LogicalSort(_, keys), outputs)) =>
        val covered = keys.collect { case SortKey(ColRefExpr(i, _, _), _, _) => i }.toSet
        !(0 until outputs).forall(covered)
      case None => true
    }
  }

  private def mapPlan(p: LogicalPlan, frozen: Boolean, f: (Expr, Slot, Boolean) => Expr): LogicalPlan = p match {
    case s: LogicalScan => s
    case LogicalFilter(child, pred) =>
      val position = child match {
        case _: LogicalAggregate => "having"
        case _ => "filter"
      }
      val scope = child.outputSchema
      LogicalFilter(mapPlan(child, frozen, f), mapExpr(pred, Slot(position, scope, scope.length, frozen), inCase = false, f))
    // The projection that drops the keys ORDER BY computed: PlanSql reads it
    // as part of its SELECT only while each item is the column at its place.
    case LogicalProject(sort @ LogicalSort(LogicalProject(_, extended), _), trim)
        if trim.size < extended.size && trim.zipWithIndex.forall { case ((ColRefExpr(i, _, _), _), j) => i == j; case _ => false } =>
      LogicalProject(mapPlan(sort, frozen, f), trim)
    case LogicalProject(child, projections) =>
      val scope = child.outputSchema
      val readable = child match {
        case LogicalWindow(c, _) => c.outputSchema.length
        case _ => scope.length
      }
      val slot = Slot("projection", scope, readable, frozen)
      LogicalProject(mapPlan(child, frozen, f), projections.map { case (e, n) => (mapExpr(e, slot, inCase = false, f), n) })
    case LogicalAggregate(child, keys, aggs) =>
      val scope = child.outputSchema
      val keySlot = Slot("group key", scope, scope.length, frozen)
      val argSlot = Slot("aggregate argument", scope, scope.length, frozen)
      LogicalAggregate(mapPlan(child, frozen, f), keys.map { case (e, n) => (mapExpr(e, keySlot, inCase = false, f), n) },
        aggs.map { case (a, n) => (mapAgg(a, argSlot, f), n) })
    case LogicalJoin(l, r, cond, kind) =>
      val scope = Schema(l.outputSchema.fields ++ r.outputSchema.fields)
      val slot = Slot("join condition", scope, scope.length, frozen)
      val (nl, nr) = (mapPlan(l, frozen, f), mapPlan(r, frozen, f))
      LogicalJoin(nl, nr, mapExpr(cond, slot, inCase = false, f), kind)
    case LogicalSort(child, keys) => LogicalSort(mapPlan(child, frozen, f), keys)
    case l @ LogicalLimit(child, n) => LogicalLimit(mapPlan(child, frozen || keepsTiedRowsByOrder(l), f), n)
    case o @ LogicalOffset(child, n) => LogicalOffset(mapPlan(child, frozen || keepsTiedRowsByOrder(o), f), n)
    case LogicalDistinct(child) => LogicalDistinct(mapPlan(child, frozen, f))
    case LogicalUnion(l, r, all) => LogicalUnion(mapPlan(l, frozen, f), mapPlan(r, frozen, f), all)
    case LogicalWindow(child, windows) =>
      val scope = child.outputSchema
      val ordered = windows.exists(tieSensitive)
      val argSlot = Slot("window argument", scope, scope.length, frozen)
      val partSlot = Slot("window partition key", scope, scope.length, frozen || ordered)
      val orderSlot = Slot("window order key", scope, scope.length, frozen || ordered)
      def arg(e: Expr) = mapExpr(e, argSlot, inCase = false, f)
      val ws = windows.map { w =>
        val fn = w.fn match {
          case WindowFnLag(v, offset, d) => WindowFnLag(arg(v), offset, d)
          case WindowFnLead(v, offset, d) => WindowFnLead(arg(v), offset, d)
          case WindowFnAgg(a) => WindowFnAgg(mapAgg(a, argSlot, f))
          case other => other
        }
        val spec = WindowSpec(w.spec.partitionKeys.map(mapExpr(_, partSlot, inCase = false, f)),
          w.spec.orderKeys.map(k => k.copy(expr = mapExpr(k.expr, orderSlot, inCase = false, f))), w.spec.frame)
        w.copy(spec = spec, fn = fn)
      }
      LogicalWindow(mapPlan(child, frozen || ordered, f), ws)
  }

  // ---------------------------------------------------------------------------
  // The forms
  // ---------------------------------------------------------------------------

  private val True: Expr = LitExpr(true, DataType.BooleanType)
  private val False: Expr = LitExpr(false, DataType.BooleanType)

  private def not(p: Expr): Expr = UnaryOpExpr("NOT", p, DataType.BooleanType)

  /** `e` in a form `rng` draws among those its type takes, its name, and
    * whether its unused branch fails on every row it reads. */
  private def wrap(e: Expr, slot: Slot, rng: Rng): (Expr, String, Boolean) = {
    val t = e.dataType
    // A form that writes its expression twice doubles a deep condition's
    // text, past which JSqlParser backtracks to its time limit: those take
    // an expression of a few nodes.
    val small = nodes(e) <= SmallExpression
    val forms = mutable.ArrayBuffer[String]("CASE WHEN FALSE", "IF(TRUE)")
    if (small) forms ++= Seq("CASE WHEN q", "COALESCE", "CASE WHEN IS NULL")
    if (t == DataType.BooleanType) forms ++= Seq("AND a tautology", "OR FALSE", "NOT NOT")
    if (t == DataType.BooleanType && small) forms += "CASE of a condition"
    e match {
      case InListExpr(_, items, _) if items.nonEmpty => forms ++= Seq.fill(3)("IN list repeat")
      case _ => ()
    }
    val form = rng.oneOf(forms.toSeq)
    form match {
      case "CASE WHEN FALSE" =>
        val (r, failing) = branch(t, slot, rng)
        (CaseExpr(Seq((False, r)), Some(e), t), form, failing)
      case "IF(TRUE)" =>
        val (r, failing) = branch(t, slot, rng)
        (FuncExpr("IF", Seq(True, e, r), t), form, failing)
      case "CASE WHEN q" => (CaseExpr(Seq((condition(slot, rng), e)), Some(e), t), form, false)
      case "COALESCE" => (FuncExpr("COALESCE", Seq(e, e), t), form, false)
      case "CASE WHEN IS NULL" => (CaseExpr(Seq((IsNullExpr(e, negated = false), LitExpr(null, t))), Some(e), t), form, false)
      case "AND a tautology" =>
        val (q, column) = conditionOn(slot, rng)
        // `q IS NULL` is its column's: JSqlParser reads `(a = a) IS NULL`
        // in some chains and not in others.
        val unknown = column.fold[Expr](False)(c => IsNullExpr(c, negated = false))
        val tautology = BinOpExpr("OR", BinOpExpr("OR", q, not(q), DataType.BooleanType), unknown, DataType.BooleanType)
        (BinOpExpr("AND", e, tautology, DataType.BooleanType), form, false)
      case "OR FALSE" => (BinOpExpr("OR", e, False, DataType.BooleanType), form, false)
      case "NOT NOT" => (not(not(e)), form, false)
      case "CASE of a condition" => (CaseExpr(Seq((e, True), (not(e), False)), None, DataType.BooleanType), form, false)
      case "IN list repeat" =>
        val InListExpr(c, items, negated) = e: @unchecked
        val at = rng.between(0, items.length)
        (InListExpr(c, items.patch(at, Seq(rng.oneOf(items)), 0), negated), form, false)
    }
  }

  /** The most nodes an expression a form writes twice may have. */
  private val SmallExpression = 12

  private def nodes(e: Expr): Int = 1 + Shrinker.childrenOf(e).map(nodes).sum

  /** A condition over a column in scope, never failing: the column IS NULL
    * or IS NOT NULL, a BOOLEAN column, or the column equal to itself, which
    * is NULL where the column is. */
  private def condition(slot: Slot, rng: Rng): Expr = conditionOn(slot, rng)._1

  /** [[condition]], and the column it is NULL with, if any. */
  private def conditionOn(slot: Slot, rng: Rng): (Expr, Option[Expr]) = {
    if (slot.readable == 0) return (if (rng.bool()) True else LitExpr(null, DataType.BooleanType), None)
    val i = rng.nextInt(slot.readable)
    val f = slot.scope.fields(i)
    val c = ColRefExpr(i, f.name, f.dataType)
    val options = mutable.ArrayBuffer[(Int, () => Expr)]((2, () => IsNullExpr(c, negated = rng.bool())))
    if (f.dataType == DataType.BooleanType) options += ((2, () => c))
    if (f.dataType != DataType.NullType) options += ((3, () => BinOpExpr("=", c, c, DataType.BooleanType)))
    (rng.weighted(options.toSeq: _*)(), Some(c))
  }

  /** A branch of exactly type `t` the form never takes: NULL, a column or a
    * literal of `t`, or a value that fails on every row whose column it reads
    * is not NULL, and whether it is the last. */
  private def branch(t: DataType, slot: Slot, rng: Rng): (Expr, Boolean) = {
    val readable = slot.scope.fields.take(slot.readable).zipWithIndex
    val sameType = readable.filter(_._1.dataType == t)
    val textual = readable.filter { case (f, _) => f.dataType != DataType.BinaryType && f.dataType != DataType.NullType }
    val options = mutable.ArrayBuffer[(Int, () => (Expr, Boolean))]((2, () => (LitExpr(null, t), false)))
    if (sameType.nonEmpty)
      options += ((2, () => { val (f, i) = rng.oneOf(sameType); (ColRefExpr(i, f.name, f.dataType), false) }))
    literalOf(t, rng).foreach(l => options += ((1, () => (l, false))))
    val castable = t match {
      case DataType.BinaryType => false
      // A DECIMAL CAST writes only the types that hold 38 digits or fewer.
      case d: DataType.DecimalType => d.precision <= 38 && d.scale <= d.precision
      case _ => true
    }
    if (textual.nonEmpty && castable)
      options += ((4, () => {
        val (f, i) = rng.oneOf(textual)
        val col: Expr = ColRefExpr(i, f.name, f.dataType)
        val asText = if (f.dataType == DataType.StringType) col else CastExpr(col, DataType.StringType)
        val text = BinOpExpr("||", LitExpr("x", DataType.StringType), asText, DataType.StringType)
        val failing: Expr = t match {
          case d: DataType.DecimalType => DecimalCastExpr(text, d)
          case DataType.StringType => CastExpr(CastExpr(text, DataType.IntType), DataType.StringType)
          case other => CastExpr(text, other)
        }
        (failing, true)
      }))
    rng.weighted(options.toSeq: _*)()
  }

  /** A literal PlanSql writes so that it binds to `t` wherever it stands, for
    * the types where one does. */
  private def literalOf(t: DataType, rng: Rng): Option[Expr] = t match {
    case DataType.IntType => Some(LitExpr(rng.between(-3, 7), t))
    case DataType.LongType => Some(LitExpr(rng.between(-3, 7).toLong, t))
    case DataType.DoubleType => Some(LitExpr(rng.oneOf(Seq(0.5, 2.0, -1.25)), t))
    case DataType.FloatType => Some(LitExpr(rng.oneOf(Seq(0.5f, 2.0f, -1.25f)), t))
    case DataType.StringType => Some(LitExpr(rng.oneOf(Seq("", "a", "zz")), t))
    case DataType.BooleanType => Some(LitExpr(rng.bool(), t))
    case _ => None
  }

  // ---------------------------------------------------------------------------
  // The relation
  // ---------------------------------------------------------------------------

  /** How one rewritten query fared: `held`, `rejected` (the printed text did
    * not bind), `unparsed` (JSqlParser did not read it, or ran past its time
    * limit: it reads three stacked parentheses in some chains and not in
    * others, and a form doubles an expression), `unprintable`, `too deep`
    * (its text nests past the parser's limit), `refused` (the nested-loop
    * guard refused a join a rewrite moved off its keys) or `moved` (one
    * failed on its data, the other answered, with a condition there to
    * move). */
  final case class Variant(rewrites: Vector[Rewrite], kind: String, why: String = "", text: String = "")

  /** What one query gave: nothing to check (`skipped`, with why), or the
    * rows the query returned and each variant's fate. */
  final case class Outcome(skipped: Option[String], rows: Int, variants: Vector[Variant])

  /** Check `sql` over `catalog`: run it, then `variants` rewrites of it, each
    * of which must answer as it does. A query the engine refuses has nothing
    * to check, and neither has one whose answer two runs may differ on. An
    * [[AssertionError]] says how a rewrite answered otherwise. */
  def check(sql: String, catalog: Catalog, rng: Rng, variants: Int,
      opts: ExecutionOptions = ExecutionOptions.Default): Outcome = {
    val built =
      try LogicalBuilder.build(sql, catalog)
      catch { case _: IllegalArgumentException => return Outcome(Some("refused"), 0, Vector.empty) }
    if (nondeterministic(sql, built)) return Outcome(Some("reads RAND() or the clock"), 0, Vector.empty)
    val base =
      try RelEngine.answer(catalog, sql, opts)
      catch { case _: IllegalArgumentException => return Outcome(Some("refused while planned"), 0, Vector.empty) }
    val movable = movableFailure(built)
    val results = (0 until variants).map { _ =>
      val rw = rewrite(built, rng.split())
      if (rw.rewrites.isEmpty) Variant(rw.rewrites, "nothing to rewrite")
      else {
        val printed =
          try Right(rw.printed(catalog))
          catch { case e: PlanSql.UnprintablePlanException => Left(Variant(rw.rewrites, "unprintable", e.getMessage)) }
        def failWith(text: String, why: String): Nothing =
          throw new AssertionError(s"[eet] a value-preserving rewrite answers otherwise: $why\n  query:     $sql\n" +
            s"  rewrites:  ${rw.rewrites.map(r => s"${r.form} at ${r.position}").mkString(", ")}\n  rewritten:\n" +
            text.split("\n").map("    " + _).mkString("\n"))
        val other = printed.flatMap { text =>
          try Right((text, RelEngine.answer(catalog, text, opts)))
          catch {
            case e: NestingLimitException => Left(Variant(rw.rewrites, "too deep", e.getMessage, text))
            case e: NestedLoopGuardException => Left(Variant(rw.rewrites, "refused", e.getMessage, text))
            case e: IllegalArgumentException if unparsed(e) => Left(Variant(rw.rewrites, "unparsed", e.getMessage, text))
            case e: IllegalArgumentException => Left(Variant(rw.rewrites, "rejected", RelEngine.rejectReason(e), text))
          }
        }
        other match {
          case Left(v) => v
          case Right((text, answer)) =>
            def fail(why: String): Nothing = failWith(text, why)
            (base, answer) match {
              case (Right(a), Right(b)) =>
                val (was, is) = (a.schema.fields.map(_.dataType), b.schema.fields.map(_.dataType))
                if (was != is) fail(s"it returns columns of types ${is.mkString(", ")}, not ${was.mkString(", ")}")
                RowOracle.multisetEquals(a.schema, a.rows, b.rows).foreach(d => fail(d))
                Variant(rw.rewrites, "held")
              case (Left(_), Left(_)) => Variant(rw.rewrites, "held")
              case _ if movable => Variant(rw.rewrites, "moved")
              case (Left(failure), Right(_)) => fail(s"it answers where the query fails on its data ($failure)")
              case (Right(_), Left(failure)) => fail(s"it fails on its data ($failure) where the query answers")
            }
        }
      }
    }.toVector
    Outcome(None, rowsOf(base), results)
  }

  private def rowsOf(answer: Either[String, RelEngine.Answer]): Int = answer.fold(_ => 0, _.rows.length)

  /** Whether `e` is JSqlParser not reading a text: a parse error, or its
    * time limit. */
  def unparsed(e: IllegalArgumentException): Boolean =
    Option(e.getMessage).exists(_.startsWith("SQL parse error"))
}
