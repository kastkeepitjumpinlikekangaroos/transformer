package com.transformer.sql.plan

import com.transformer.core.{DataType, Schema}

import scala.collection.mutable

/** Orders the tables of each run of inner joins so that each join, where it
  * can, has an equality between the tables joined so far and the one it adds,
  * which the physical planner hashes on.
  *
  * The binder joins tables in the order FROM writes them, so `FROM a, b, c
  * WHERE a.k = c.k AND b.j = c.j` paired every row of `a` with every row of
  * `b` before `c` came in with the two equalities, and the nested-loop guard
  * refused it past a few thousand rows, where joining `c` second hashes both
  * joins. The order is the written one but where the next table has no
  * equality with the tables joined so far: then the first later table that
  * has one comes in first. There is no cost model, so a query whose written
  * order already hashes every join it can keeps its plan.
  *
  * A run of inner joins is flattened into its tables (anything that is not an
  * inner join: a scan, a filter over one, an outer join, which joins nothing
  * across it) and the conjuncts of its conditions, and rebuilt left-deep in
  * the new order, each conjunct at the first join that has every table it
  * reads. The run's columns come out in the new order, so the expressions
  * above that read them are re-indexed, up to the projection or aggregate
  * that makes the block's own columns (a union's arm, or the plan's root, gets
  * a projection putting them back). Inner joins associate and commute, so the
  * answer is the same; only which rows a condition is evaluated on is not, so a
  * run whose conditions read RAND(), whose rows would change, keeps its order.
  * Runs after FilterPushdown, which moves the WHERE's conjuncts over two tables
  * into the joins' conditions, where this pass reads them.
  */
object JoinOrder {

  /** Where each column of a rewritten plan's output went: the new index of
    * the column that was at index `i`, or None when none moved. */
  private type Moves = Option[IndexedSeq[Int]]

  def apply(plan: LogicalPlan): LogicalPlan = {
    val (rewritten, moves) = rewrite(plan)
    inOrder(rewritten, moves, plan.outputSchema)
  }

  /** `p`, whose columns moved as `moves` says, with a projection putting them
    * back in the order of `schema`. */
  private def inOrder(p: LogicalPlan, moves: Moves, schema: Schema): LogicalPlan = moves match {
    case None => p
    case Some(to) => LogicalProject(p, schema.fields.indices.map { i =>
      val f = schema.fields(i)
      (ColRefExpr(to(i), f.name, f.dataType): Expr, f.name)
    })
  }

  private def moved(e: Expr, moves: Moves): Expr = moves.fold(e)(to => JoinSideAnalysis.remapColumns(e, to))

  private def movedAgg(a: AggExpr, moves: Moves): AggExpr = a match {
    case c: AggExprCountStar => c
    case AggExprCount(c, distinct) => AggExprCount(moved(c, moves), distinct)
    case AggExprSum(c) => AggExprSum(moved(c, moves))
    case AggExprAvg(c) => AggExprAvg(moved(c, moves))
    case AggExprMin(c) => AggExprMin(moved(c, moves))
    case AggExprMax(c) => AggExprMax(moved(c, moves))
    case AggExprCountIf(c) => AggExprCountIf(moved(c, moves))
    case AggExprStddev(c, sample) => AggExprStddev(moved(c, moves), sample)
    case AggExprVariance(c, sample) => AggExprVariance(moved(c, moves), sample)
    case AggExprCovar(x, y, sample) => AggExprCovar(moved(x, moves), moved(y, moves), sample)
    case AggExprCorr(x, y) => AggExprCorr(moved(x, moves), moved(y, moves))
    case AggExprDistinct(inner) => AggExprDistinct(movedAgg(inner, moves))
  }

  private def identity(n: Int): IndexedSeq[Int] = 0 until n

  /** `plan` with each run under it ordered, and where its columns went. */
  private def rewrite(plan: LogicalPlan): (LogicalPlan, Moves) = plan match {
    case run @ LogicalJoin(_, _, _, JoinKind.Inner) => reorder(run)
    case LogicalJoin(l, r, condition, kind) =>
      val (nl, ml) = rewrite(l)
      val (nr, mr) = rewrite(r)
      val width = l.outputSchema.length
      val moves =
        if (ml.isEmpty && mr.isEmpty) None
        else Some(ml.getOrElse(identity(width)) ++ mr.getOrElse(identity(r.outputSchema.length)).map(_ + width))
      (LogicalJoin(nl, nr, moved(condition, moves), kind), moves)
    case LogicalFilter(c, predicate) =>
      val (nc, m) = rewrite(c)
      (LogicalFilter(nc, moved(predicate, m)), m)
    case LogicalSort(c, keys) =>
      val (nc, m) = rewrite(c)
      (LogicalSort(nc, keys.map(k => k.copy(expr = moved(k.expr, m)))), m)
    case LogicalLimit(c, n) => val (nc, m) = rewrite(c); (LogicalLimit(nc, n), m)
    case LogicalOffset(c, n) => val (nc, m) = rewrite(c); (LogicalOffset(nc, n), m)
    case LogicalDistinct(c) => val (nc, m) = rewrite(c); (LogicalDistinct(nc), m)
    case LogicalWindow(c, windows) =>
      val (nc, m) = rewrite(c)
      val ws = windows.map { w =>
        val spec = WindowSpec(w.spec.partitionKeys.map(moved(_, m)),
          w.spec.orderKeys.map(k => k.copy(expr = moved(k.expr, m))), w.spec.frame)
        val fn = w.fn match {
          case WindowFnLag(v, offset, default) => WindowFnLag(moved(v, m), offset, default.map(moved(_, m)))
          case WindowFnLead(v, offset, default) => WindowFnLead(moved(v, m), offset, default.map(moved(_, m)))
          case WindowFnAgg(a) => WindowFnAgg(movedAgg(a, m))
          case other => other
        }
        w.copy(spec = spec, fn = fn)
      }
      // The window's own columns follow the child's, which keep their width.
      val width = c.outputSchema.length
      (LogicalWindow(nc, ws), m.map(to => to ++ (width until width + windows.length)))
    case LogicalProject(c, projections) =>
      val (nc, m) = rewrite(c)
      (LogicalProject(nc, projections.map { case (e, n) => (moved(e, m), n) }), None)
    case LogicalAggregate(c, keys, aggregates) =>
      val (nc, m) = rewrite(c)
      (LogicalAggregate(nc, keys.map { case (e, n) => (moved(e, m), n) }, aggregates.map { case (a, n) => (movedAgg(a, m), n) }),
        None)
    // Both arms of a union return their columns in one order.
    case LogicalUnion(l, r, all) =>
      val (nl, ml) = rewrite(l)
      val (nr, mr) = rewrite(r)
      (LogicalUnion(inOrder(nl, ml, l.outputSchema), inOrder(nr, mr, r.outputSchema), all), None)
    case s: LogicalScan => (s, None)
  }

  private def reorder(run: LogicalJoin): (LogicalPlan, Moves) = {
    val tables = mutable.ArrayBuffer.empty[LogicalPlan]
    val tableMoves = mutable.ArrayBuffer.empty[Moves]
    val conjuncts = mutable.ArrayBuffer.empty[Expr]
    // Each condition reads the columns of the tables under its join, which are
    // consecutive in the run's output: `offset` is where they start.
    def flatten(p: LogicalPlan, offset: Int): Unit = p match {
      case LogicalJoin(l, r, condition, JoinKind.Inner) =>
        flatten(l, offset)
        flatten(r, offset + l.outputSchema.length)
        conjuncts ++= split(condition).filterNot(_ == True).map(JoinSideAnalysis.remapColumns(_, _ + offset))
      case table =>
        val (t, m) = rewrite(table)
        tables += t
        tableMoves += m
    }
    flatten(run, 0)

    val widths = tables.map(_.outputSchema.length).toIndexedSeq
    val starts = widths.scanLeft(0)(_ + _)
    def tableOf(column: Int): Int = starts.lastIndexWhere(_ <= column, widths.length - 1)
    def tablesRead(e: Expr): Set[Int] = JoinSideAnalysis.columnsRead(e).map(tableOf)
    val reads = conjuncts.map(tablesRead).toIndexedSeq
    // Where a column of table `t` is in `t` as rewritten.
    def within(t: Int, column: Int): Int = tableMoves(t).fold(column)(_(column))

    // An equality one side of which reads only tables joined so far and the
    // other only `table`: a key the join adding `table` hashes on.
    def keyed(joined: Set[Int], table: Int): Boolean = conjuncts.exists {
      case BinOpExpr("=", l, r, _) =>
        val (lt, rt) = (tablesRead(l), tablesRead(r))
        def key(one: Set[Int], other: Set[Int]) = one.nonEmpty && one.subsetOf(joined) && other == Set(table)
        key(lt, rt) || key(rt, lt)
      case _ => false
    }
    val order = mutable.ArrayBuffer(0)
    val remaining = mutable.ArrayBuffer.range(1, tables.length)
    while (remaining.nonEmpty) {
      val joined = order.toSet
      val next =
        if (keyed(joined, remaining.head)) remaining.head
        else remaining.find(keyed(joined, _)).getOrElse(remaining.head)
      order += next
      remaining -= next
    }
    val reordered = order != tables.indices && !conjuncts.exists(Expr.readsRandom)

    // Where each table's columns start in the run's output.
    val newStarts = new Array[Int](tables.length)
    (if (reordered) order else tables.indices).foldLeft(0) { (at, t) => newStarts(t) = at; at + widths(t) }
    val to: Int => Int = column => { val t = tableOf(column); newStarts(t) + within(t, column - starts(t)) }
    val runMoves: Moves =
      if (!reordered && tableMoves.forall(_.isEmpty)) None
      else Some((0 until starts.last).map(to))

    if (!reordered) {
      // As written, each condition reading its tables as they were rewritten.
      val next = tables.iterator
      def asWritten(p: LogicalPlan, offset: Int): LogicalPlan = p match {
        case LogicalJoin(l, r, condition, JoinKind.Inner) =>
          val (nl, nr) = (asWritten(l, offset), asWritten(r, offset + l.outputSchema.length))
          // The join's columns start at `offset` still: the order and the
          // tables' widths are the same.
          val local = if (runMoves.isEmpty) condition
            else JoinSideAnalysis.remapColumns(condition, c => to(c + offset) - offset)
          LogicalJoin(nl, nr, local, JoinKind.Inner)
        case _ => next.next()
      }
      (asWritten(run, 0), runMoves)
    } else {
      val placed = mutable.BitSet.empty
      var joined = Set(order.head)
      var plan: LogicalPlan = tables(order.head)
      order.tail.foreach { t =>
        joined += t
        val here = conjuncts.indices.filter(i => !placed(i) && reads(i).subsetOf(joined))
        here.foreach(placed += _)
        val condition =
          if (here.isEmpty) True
          else JoinSideAnalysis.conjunction(here.map(i => JoinSideAnalysis.remapColumns(conjuncts(i), to)))
        plan = LogicalJoin(plan, tables(t), condition, JoinKind.Inner)
      }
      (plan, runMoves)
    }
  }

  private val True: Expr = LitExpr(true, DataType.BooleanType)

  private def split(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => split(l) ++ split(r)
    case other => Seq(other)
  }
}
