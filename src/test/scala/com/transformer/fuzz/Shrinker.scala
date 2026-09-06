package com.transformer.fuzz

import com.transformer.core.{DataType, Schema}
import com.transformer.fuzz.MetaQueryGen.{AggCore, MetaCase, MetaQuery, NoRecCase, ProjectCore, QueryCore, RelEnv}
import com.transformer.fuzz.QueryGen._
import com.transformer.sql.plan._

/** Hand-rolled, integrated-style shrinking: given a failing value, produce an
  * `Iterator` of strictly-smaller candidates to try. [[Props]] greedily walks
  * the iterator, replacing the failing value with the first smaller candidate
  * that still fails, until nothing smaller fails — yielding a minimal
  * counterexample.
  *
  * Generic combinators ([[int]], [[long]], [[string]], [[list]]) compose the
  * scalar moves; [[expr]] is the [[Expr]]-tree shrinker used by the parity
  * fuzzer. Every candidate is strictly smaller by [[size]] (node count for
  * trees, magnitude/length for scalars), and [[Props]] additionally rejects any
  * candidate whose size did not decrease — so the shrink loop always terminates.
  */
object Shrinker {

  // ---- scalar combinators -------------------------------------------------

  /** Integers shrink toward 0 by repeated halving. */
  def int(n: Int): Iterator[Int] =
    if (n == 0) Iterator.empty
    else {
      val half = n / 2
      (Iterator(0) ++ (if (half != 0) Iterator(half) else Iterator.empty)).distinct
    }

  def long(n: Long): Iterator[Long] =
    if (n == 0L) Iterator.empty
    else {
      val half = n / 2L
      (Iterator(0L) ++ (if (half != 0L) Iterator(half) else Iterator.empty)).distinct
    }

  /** Strings shrink toward "" by dropping the second half, then the tail char. */
  def string(s: String): Iterator[String] =
    if (s.isEmpty) Iterator.empty
    else (Iterator("", s.substring(0, s.length / 2), s.substring(0, s.length - 1)))
      .filter(_ != s)
      .distinct

  /** Lists shrink by dropping one element (down to `minLen`), then by shrinking
    * individual elements with `shrinkElem`. */
  def list[A](xs: Seq[A], minLen: Int, shrinkElem: A => Iterator[A]): Iterator[Seq[A]] = {
    val drops: Iterator[Seq[A]] =
      if (xs.length <= minLen) Iterator.empty
      else xs.indices.iterator.map(i => xs.patch(i, Nil, 1))
    val shrinks: Iterator[Seq[A]] =
      xs.indices.iterator.flatMap(i => shrinkElem(xs(i)).map(e => xs.updated(i, e)))
    drops ++ shrinks
  }

  // ---- Expr-tree shrinker -------------------------------------------------

  /** Node count, the strict-decrease metric for the shrink loop. */
  def size(e: Expr): Int = 1 + childrenOf(e).iterator.map(size).sum

  /** Strictly-smaller, still-type-correct, still-arity-valid candidates for an
    * `Expr`. Moves, in the order [[Props]] tries them:
    *   1. replace the whole node with the simplest literal of its type, then
    *      with a NULL literal of its type — collapses any subtree to a leaf and
    *      surfaces NULL-handling minimal cases;
    *   2. replace the node with one of its same-typed children — collapses
    *      `f(g(x))` toward `x`, the single most effective structural move;
    *   3. node-specific element drops (CASE branches, IN-list items, ELSE,
    *      variadic COALESCE/CONCAT args) down to the arity floor;
    *   4. shrink one child in place (integrated recursion), keeping the child's
    *      declared type so the rebuilt parent stays valid;
    *   5. for a literal, shrink its magnitude toward 0 / "".
    */
  def expr(e: Expr): Iterator[Expr] = {
    val replaceWhole: Iterator[Expr] = e match {
      case LitExpr(_, _) => Iterator.empty // covered by magnitude shrink below
      case _ => Iterator(simplestLit(e.dataType), LitExpr(null, e.dataType))
    }

    val collapseToChild: Iterator[Expr] =
      childrenOf(e).iterator.filter(_.dataType == e.dataType)

    val structuralDrops: Iterator[Expr] = e match {
      case InListExpr(child, items, neg) if items.length > 1 =>
        items.indices.iterator.map(i => InListExpr(child, items.patch(i, Nil, 1), neg))
      case CaseExpr(branches, elseExpr, dt) =>
        val dropBranch =
          if (branches.length > 1)
            branches.indices.iterator.map(i => CaseExpr(branches.patch(i, Nil, 1), elseExpr, dt))
          else Iterator.empty
        val dropElse = if (elseExpr.isDefined) Iterator(CaseExpr(branches, None, dt)) else Iterator.empty
        dropBranch ++ dropElse
      case FuncExpr(name, args, dt) if isVariadic(name) && args.length > 1 =>
        args.indices.iterator.map(i => FuncExpr(name, args.patch(i, Nil, 1), dt))
      case _ => Iterator.empty
    }

    val shrinkOneChild: Iterator[Expr] = {
      val kids = childrenOf(e)
      kids.indices.iterator.flatMap { i =>
        expr(kids(i)).map(smaller => rebuild(e, kids.updated(i, smaller)))
      }
    }

    val magnitude: Iterator[Expr] = e match {
      case LitExpr(v, dt) => shrinkLitValue(v, dt).map(nv => LitExpr(nv, dt))
      case _ => Iterator.empty
    }

    // Keep only strictly-smaller candidates under the well-founded order
    // (node count, then total literal magnitude). Structural moves drop the
    // node count; a literal magnitude shrink keeps the count but lowers the
    // magnitude — both strictly decrease the pair, so the shrink loop
    // terminates.
    (replaceWhole ++ collapseToChild ++ structuralDrops ++ shrinkOneChild ++ magnitude)
      .filter { c =>
        val sc = size(c)
        val se = size(e)
        sc < se || (sc == se && magnitudeOf(c) < magnitudeOf(e))
      }
  }

  /** Total literal magnitude in a tree — the tie-breaker that lets a same-size
    * literal shrink (`42 -> 0`, `"abc" -> ""`) still count as strictly smaller. */
  private def magnitudeOf(e: Expr): Long = e match {
    case LitExpr(v, _) => litMagnitude(v)
    case _ => childrenOf(e).iterator.map(magnitudeOf).sum
  }

  private def litMagnitude(v: Any): Long = v match {
    case null => 0L
    case b: Boolean => if (b) 1L else 0L
    case i: Int => math.abs(i.toLong)
    case l: Long => if (l == Long.MinValue) Long.MaxValue else math.abs(l)
    case f: Float => if (f == 0.0f) 0L else 1L
    case d: Double => if (d == 0.0) 0L else 1L
    case s: String => s.length.toLong
    case _ => 0L
  }

  private def isVariadic(name: String): Boolean = name.toUpperCase match {
    case "COALESCE" | "CONCAT" => true
    case _ => false
  }

  /** The simplest non-null literal of a type — the collapse target. */
  private def simplestLit(dt: DataType): LitExpr = dt match {
    case DataType.IntType => LitExpr(0, dt)
    case DataType.LongType => LitExpr(0L, dt)
    case DataType.FloatType => LitExpr(0.0f, dt)
    case DataType.DoubleType => LitExpr(0.0, dt)
    case DataType.BooleanType => LitExpr(false, dt)
    case DataType.StringType => LitExpr("", dt)
    case _ => LitExpr(null, dt)
  }

  private def shrinkLitValue(v: Any, dt: DataType): Iterator[Any] = v match {
    case null => Iterator.empty
    case i: Int => int(i).map(x => x: Any)
    case l: Long => long(l).map(x => x: Any)
    case f: Float => if (f == 0.0f) Iterator.empty else Iterator(0.0f: Any)
    case d: Double => if (d == 0.0) Iterator.empty else Iterator(0.0: Any)
    case b: Boolean => if (b) Iterator(false: Any) else Iterator.empty
    case s: String => string(s).map(x => x: Any)
    case _ => Iterator.empty
  }

  /** Immediate `Expr` children, flattened left-to-right. Paired with
    * [[rebuild]] so the recursion can swap one child and reassemble the node. */
  private def childrenOf(e: Expr): Seq[Expr] = e match {
    case LitExpr(_, _) => Nil
    case ColRefExpr(_, _, _) => Nil
    case CastExpr(c, _) => Seq(c)
    case UnaryOpExpr(_, c, _) => Seq(c)
    case BinOpExpr(_, l, r, _) => Seq(l, r)
    case FuncExpr(_, args, _) => args
    case CaseExpr(branches, elseExpr, _) =>
      branches.flatMap { case (c, v) => Seq(c, v) } ++ elseExpr.toSeq
    case IsNullExpr(c, _) => Seq(c)
    case InListExpr(c, items, _) => c +: items
    case LikeExpr(c, p, _) => Seq(c, p)
  }

  // ---- QueryCase shrinker (mode-differential) -----------------------------

  /** Strictly-smaller candidates for a `(dataset, query)` case. Every move
    * shrinks a well-founded measure (row count, column count, clause count,
    * projection/aggregate count, or expression node count / literal magnitude),
    * so the greedy loop terminates. Any move that would produce an unbindable
    * query is harmless: the oracle returns `Rejected` for it, which the property
    * treats as a pass, so the shrinker never settles on it.
    *
    * Moves, in roughly most-reductive-first order: drop dataset rows; strip query
    * clauses (DISTINCT / WHERE / HAVING / ORDER BY); drop a projection item,
    * aggregate, or GROUP BY key; simplify a contained expression via [[expr]];
    * drop a trailing column the query no longer references. The canonical minimal
    * counterexample this converges to is a couple of rows, one column, and a
    * single aggregate. */
  def queryCase(qc: QueryCase): Iterator[QueryCase] = {
    val ds = qc.dataset
    val sameQuery = (rows: IndexedSeq[Array[Any]]) =>
      QueryCase(DataGen.Dataset(ds.schema, rows), qc.query)
    val sameData = (q: GenQuery) => QueryCase(ds, q)

    val dropRows: Iterator[QueryCase] = shrinkRows(ds.rows).map(sameQuery)
    val simplerQuery: Iterator[QueryCase] = shrinkQuery(qc.query).map(sameData)
    val dropColumn: Iterator[QueryCase] = dropTrailingColumn(qc)

    dropRows ++ simplerQuery ++ dropColumn
  }

  /** Drop rows: the second half, the first half, then single-row removals. The
    * halves reach a small dataset in log steps; the single drops fine-tune. */
  private def shrinkRows(rows: IndexedSeq[Array[Any]]): Iterator[IndexedSeq[Array[Any]]] = {
    if (rows.isEmpty) return Iterator.empty
    val half = rows.length / 2
    val halves: Iterator[IndexedSeq[Array[Any]]] =
      if (half > 0) Iterator(rows.take(half), rows.drop(half)) else Iterator.empty
    val singles: Iterator[IndexedSeq[Array[Any]]] =
      rows.indices.iterator.map(i => rows.patch(i, Nil, 1))
    halves ++ singles
  }

  /** Drop the last column when the query no longer references it — no reindexing
    * needed because every surviving `ColRefExpr` points below the dropped index. */
  private def dropTrailingColumn(qc: QueryCase): Iterator[QueryCase] = {
    val schema = qc.dataset.schema
    val last = schema.length - 1
    if (last < 1 || referencedColumns(qc.query).contains(last)) Iterator.empty
    else {
      val newSchema = Schema(schema.fields.dropRight(1))
      val newRows = qc.dataset.rows.map(_.dropRight(1))
      Iterator(QueryCase(DataGen.Dataset(newSchema, newRows), qc.query))
    }
  }

  private def shrinkQuery(q: GenQuery): Iterator[GenQuery] = q match {
    case p: ProjectQuery => shrinkProject(p)
    case a: AggregateQuery => shrinkAggregate(a)
  }

  private def shrinkProject(p: ProjectQuery): Iterator[GenQuery] = {
    val orderNames = p.orderBy.map(_._1.name).toSet
    val dropLimit = if (p.limit.isDefined) Iterator[GenQuery](p.copy(limit = None)) else Iterator.empty
    val dropDistinct = if (p.distinct) Iterator[GenQuery](p.copy(distinct = false)) else Iterator.empty
    val dropWhere = if (p.where.isDefined) Iterator[GenQuery](p.copy(where = None)) else Iterator.empty
    val dropOrder = if (p.orderBy.nonEmpty) Iterator[GenQuery](p.copy(orderBy = Vector.empty)) else Iterator.empty
    // Drop a projection item that is not an order key (order keys must stay
    // projected while ORDER BY references them).
    val dropItem: Iterator[GenQuery] =
      if (p.projection.length <= 1) Iterator.empty
      else p.projection.indices.iterator.collect {
        case i if !orderNames.contains(p.projection(i)._2) =>
          p.copy(projection = p.projection.patch(i, Nil, 1))
      }
    val simplifyItem: Iterator[GenQuery] =
      p.projection.indices.iterator.flatMap { i =>
        val (e, name) = p.projection(i)
        expr(e).map(se => p.copy(projection = p.projection.updated(i, (se, name))))
      }
    val simplifyWhere: Iterator[GenQuery] =
      p.where.iterator.flatMap(w => expr(w).map(sw => p.copy(where = Some(sw))))

    dropLimit ++ dropDistinct ++ dropWhere ++ dropOrder ++ dropItem ++ simplifyItem ++ simplifyWhere
  }

  private def shrinkAggregate(a: AggregateQuery): Iterator[GenQuery] = {
    val orderIdx = a.orderBy.map(_._1.index).toSet
    val havingIdx = a.having.toSet.flatMap((h: HavingSpec) => h.refs)
    val pinned = orderIdx ++ havingIdx // group keys these clauses still reference

    val dropLimit = if (a.limit.isDefined) Iterator[GenQuery](a.copy(limit = None)) else Iterator.empty
    val dropWhere = if (a.where.isDefined) Iterator[GenQuery](a.copy(where = None)) else Iterator.empty
    val dropHaving = if (a.having.isDefined) Iterator[GenQuery](a.copy(having = None)) else Iterator.empty
    val dropOrder = if (a.orderBy.nonEmpty) Iterator[GenQuery](a.copy(orderBy = Vector.empty)) else Iterator.empty
    val dropAgg: Iterator[GenQuery] =
      if (a.aggregates.length <= 1) Iterator.empty
      else a.aggregates.indices.iterator.map(i => a.copy(aggregates = a.aggregates.patch(i, Nil, 1)))
    // Drop a GROUP BY key not pinned by ORDER BY / HAVING. Removing the last key
    // turns the query into a (valid) global aggregate.
    val dropKey: Iterator[GenQuery] =
      a.groupKeys.indices.iterator.collect {
        case i if !pinned.contains(a.groupKeys(i).index) =>
          val keys = a.groupKeys.patch(i, Nil, 1)
          a.copy(groupKeys = keys, groupByOrdinals = a.groupByOrdinals && keys.nonEmpty)
      }
    val simplifyArg: Iterator[GenQuery] =
      a.aggregates.indices.iterator.flatMap { i =>
        val (spec, name) = a.aggregates(i)
        shrinkAggSpec(spec).map(s => a.copy(aggregates = a.aggregates.updated(i, (s, name))))
      }
    val simplifyWhere: Iterator[GenQuery] =
      a.where.iterator.flatMap(w => expr(w).map(sw => a.copy(where = Some(sw))))

    dropLimit ++ dropWhere ++ dropHaving ++ dropOrder ++ dropAgg ++ dropKey ++ simplifyArg ++ simplifyWhere
  }

  /** Simplify an aggregate's argument expression(s). Bare-column args yield
    * nothing (a `ColRefExpr` does not shrink), so only computed args reduce. */
  private def shrinkAggSpec(spec: AggSpec): Iterator[AggSpec] = spec match {
    case CountStar => Iterator.empty
    case Count(arg, d) => expr(arg).map(Count(_, d))
    case Sum(arg) => expr(arg).map(Sum(_))
    case Avg(arg) => expr(arg).map(Avg(_))
    case Min(arg) => expr(arg).map(Min(_))
    case Max(arg) => expr(arg).map(Max(_))
    case CountIf(arg) => expr(arg).map(CountIf(_))
    case Stat(fn, arg) => expr(arg).map(Stat(fn, _))
    case Bivar(fn, x, y) => expr(x).map(Bivar(fn, _, y)) ++ expr(y).map(Bivar(fn, x, _))
  }

  /** Column indices the query references (so [[dropTrailingColumn]] never drops a
    * live column). */
  private def referencedColumns(q: GenQuery): Set[Int] = q match {
    case p: ProjectQuery =>
      p.projection.iterator.flatMap { case (e, _) => exprCols(e) }.toSet ++
        p.where.iterator.flatMap(exprCols).toSet ++
        p.orderBy.iterator.map(_._1.index).toSet
    case a: AggregateQuery =>
      a.groupKeys.iterator.map(_.index).toSet ++
        a.aggregates.iterator.flatMap { case (s, _) => aggCols(s) }.toSet ++
        a.where.iterator.flatMap(exprCols).toSet ++
        a.having.iterator.flatMap(_.refs).toSet ++
        a.orderBy.iterator.map(_._1.index).toSet
  }

  private def exprCols(e: Expr): Set[Int] = e match {
    case ColRefExpr(i, _, _) => Set(i)
    case _ => childrenOf(e).iterator.flatMap(exprCols).toSet
  }

  private def aggCols(spec: AggSpec): Set[Int] = spec match {
    case CountStar => Set.empty
    case Count(arg, _) => exprCols(arg)
    case Sum(arg) => exprCols(arg)
    case Avg(arg) => exprCols(arg)
    case Min(arg) => exprCols(arg)
    case Max(arg) => exprCols(arg)
    case CountIf(arg) => exprCols(arg)
    case Stat(_, arg) => exprCols(arg)
    case Bivar(_, x, y) => exprCols(x) ++ exprCols(y)
  }

  /** Reassemble `e` from a new child list of the same length and shape as
    * [[childrenOf]] returned. */
  private def rebuild(e: Expr, kids: Seq[Expr]): Expr = e match {
    case LitExpr(_, _) => e
    case ColRefExpr(_, _, _) => e
    case CastExpr(_, t) => CastExpr(kids.head, t)
    case UnaryOpExpr(op, _, dt) => UnaryOpExpr(op, kids.head, dt)
    case BinOpExpr(op, _, _, dt) => BinOpExpr(op, kids(0), kids(1), dt)
    case FuncExpr(name, _, dt) => FuncExpr(name, kids, dt)
    case CaseExpr(branches, elseExpr, dt) =>
      val nPaired = branches.length * 2
      val paired = kids.take(nPaired).grouped(2).map(p => (p(0), p(1))).toSeq
      val newElse = if (elseExpr.isDefined) Some(kids(nPaired)) else None
      CaseExpr(paired, newElse, dt)
    case IsNullExpr(_, neg) => IsNullExpr(kids.head, neg)
    case InListExpr(_, items, neg) => InListExpr(kids.head, kids.drop(1).take(items.length), neg)
    case LikeExpr(_, _, neg) => LikeExpr(kids(0), kids(1), neg)
  }

  // ---- multi-relation MetaCase / NoRecCase shrinkers (metamorphic) ---------

  /** Strictly-smaller candidates for a metamorphic `(env, query)` case. Each move
    * shrinks a well-founded measure — relation rows, join count, CTE count, union
    * arm, window/projection/aggregate count, or a contained expression's node
    * count — so the greedy loop terminates. A move that yields an unbindable query
    * (e.g. dropping a join a projection still references) or one whose TLP key
    * vanished is harmless: the oracle returns `Rejected` / `Skipped`, which the
    * property treats as a pass, so the shrinker never settles on it. The canonical
    * minimum a join bug converges to is "a couple of rows each side, one join key,
    * `... FROM a LEFT JOIN b ON a.k = b.k`". */
  def metaCase(mc: MetaCase): Iterator[MetaCase] = {
    val shrinkEnvRows: Iterator[MetaCase] = mc.env.relations.indices.iterator.flatMap { i =>
      val rel = mc.env.relations(i)
      shrinkRows(rel.dataset.rows).map { rows =>
        val ds = DataGen.Dataset(rel.dataset.schema, rows)
        mc.copy(env = RelEnv(mc.env.relations.updated(i, rel.copy(dataset = ds))))
      }
    }
    val shrinkQuery: Iterator[MetaCase] = shrinkMetaQuery(mc.query).map(q => mc.copy(query = q))
    shrinkEnvRows ++ shrinkQuery
  }

  /** Strictly-smaller candidates for a NoREC `(relation, predicate)` case: drop
    * relation rows, then simplify the predicate. */
  def noRecCase(nc: NoRecCase): Iterator[NoRecCase] = {
    val dropRows: Iterator[NoRecCase] = shrinkRows(nc.relation.dataset.rows).map { rows =>
      nc.copy(relation = nc.relation.copy(dataset = DataGen.Dataset(nc.relation.dataset.schema, rows)))
    }
    val simplerPred: Iterator[NoRecCase] = expr(nc.predicate).map(p => nc.copy(predicate = p))
    dropRows ++ simplerPred
  }

  private def shrinkMetaQuery(q: MetaQuery): Iterator[MetaQuery] = {
    val dropLimit: Iterator[MetaQuery] = if (q.limit.isDefined) Iterator(q.copy(limit = None)) else Iterator.empty
    val unwrapUnion: Iterator[MetaQuery] = q.setOp.iterator.map(_ => q.copy(setOp = None))
    val dropWhere: Iterator[MetaQuery] = if (q.where.isDefined) Iterator(q.copy(where = None)) else Iterator.empty
    val dropCte: Iterator[MetaQuery] = q.ctes.indices.iterator.map(i => q.copy(ctes = q.ctes.patch(i, Nil, 1)))
    val dropJoin: Iterator[MetaQuery] = q.from.joins.indices.iterator.map(i =>
      q.copy(from = q.from.copy(joins = q.from.joins.patch(i, Nil, 1))))
    val shrinkCoreQ: Iterator[MetaQuery] = shrinkCore(q.core).map(c => q.copy(core = c))
    val simplifyWhere: Iterator[MetaQuery] =
      q.where.iterator.flatMap(w => expr(w).map(sw => q.copy(where = Some(sw))))
    val simplifyOn: Iterator[MetaQuery] = q.from.joins.indices.iterator.flatMap { i =>
      val j = q.from.joins(i)
      expr(j.on).map(so => q.copy(from = q.from.copy(joins = q.from.joins.updated(i, j.copy(on = so)))))
    }
    dropLimit ++ unwrapUnion ++ dropWhere ++ dropJoin ++ dropCte ++ shrinkCoreQ ++ simplifyWhere ++ simplifyOn
  }

  private def shrinkCore(core: QueryCore): Iterator[QueryCore] = core match {
    case p: ProjectCore =>
      val dropWindow: Iterator[QueryCore] =
        p.windows.indices.iterator.map(i => p.copy(windows = p.windows.patch(i, Nil, 1)))
      val dropItem: Iterator[QueryCore] =
        if (p.items.length <= 1) Iterator.empty
        else p.items.indices.iterator.map(i => p.copy(items = p.items.patch(i, Nil, 1)))
      val simplifyItem: Iterator[QueryCore] = p.items.indices.iterator.flatMap { i =>
        val (e, n) = p.items(i)
        expr(e).map(se => p.copy(items = p.items.updated(i, (se, n))))
      }
      dropWindow ++ dropItem ++ simplifyItem
    case a: AggCore =>
      val dropHaving: Iterator[QueryCore] = if (a.having.isDefined) Iterator(a.copy(having = None)) else Iterator.empty
      val dropAgg: Iterator[QueryCore] =
        if (a.aggs.length <= 1) Iterator.empty
        else a.aggs.indices.iterator.map(i => a.copy(aggs = a.aggs.patch(i, Nil, 1)))
      val dropKey: Iterator[QueryCore] =
        a.groupKeys.indices.iterator.map(i => a.copy(groupKeys = a.groupKeys.patch(i, Nil, 1)))
      dropHaving ++ dropAgg ++ dropKey
  }

  // ---- JobCase shrinker (DAG scheduler) -----------------------------------

  /** Strictly-smaller candidates for a generated job DAG. Every move reduces a
    * well-founded measure — task count, edge count, injected-failure count, or
    * per-task output config — so the greedy loop terminates. Dropping the LAST
    * task keeps the remaining indices (and hence every `t<j>` reference) valid,
    * which is why only the tail is peeled; edges are then removed one at a time,
    * so a scheduling bug converges to the smallest DAG that still shows it.
    * Dropping an input is unsafe (the surviving tasks reference it by name), so
    * an input is healed rather than removed. */
  def jobCase(jc: JobDagGen.JobCase): Iterator[JobDagGen.JobCase] = {
    // A task the tail depends on cannot be dropped, so peel only from the end.
    val dropLastTask: Iterator[JobDagGen.JobCase] =
      if (jc.tasks.length <= 1) Iterator.empty
      else Iterator(JobDagGen.JobCase(jc.inputs, jc.tasks.init))

    val dropValidationEdge: Iterator[JobDagGen.JobCase] =
      jc.tasks.indices.iterator.filter(jc.tasks(_).validationTaskSrc.isDefined).map { i =>
        jc.copy(tasks = jc.tasks.updated(i, jc.tasks(i).copy(validationTaskSrc = None)))
      }

    val healInput: Iterator[JobDagGen.JobCase] =
      jc.inputs.indices.iterator.filter(jc.inputs(_).broken).map { i =>
        jc.copy(inputs = jc.inputs.updated(i, jc.inputs(i).copy(broken = false)))
      }

    val healTask: Iterator[JobDagGen.JobCase] =
      jc.tasks.indices.iterator.filter(jc.tasks(_).outcome != JobDagGen.Ok).map { i =>
        jc.copy(tasks = jc.tasks.updated(i, jc.tasks(i).copy(outcome = JobDagGen.Ok)))
      }

    val dropEdge: Iterator[JobDagGen.JobCase] = jc.tasks.indices.iterator.flatMap { i =>
      val t = jc.tasks(i)
      val dropTaskEdge = t.taskSrcs.indices.iterator
        .filter(_ => t.sources > 1)
        .map(k => jc.copy(tasks = jc.tasks.updated(i, t.copy(taskSrcs = t.taskSrcs.patch(k, Nil, 1)))))
      val dropInputEdge = t.inputSrcs.indices.iterator
        .filter(_ => t.sources > 1)
        .map(k => jc.copy(tasks = jc.tasks.updated(i, t.copy(inputSrcs = t.inputSrcs.patch(k, Nil, 1)))))
      dropTaskEdge ++ dropInputEdge
    }

    val dropOutput: Iterator[JobDagGen.JobCase] =
      jc.tasks.indices.iterator.filter(jc.tasks(_).output.isDefined).map { i =>
        jc.copy(tasks = jc.tasks.updated(i, jc.tasks(i).copy(output = None)))
      }

    val dropValidation: Iterator[JobDagGen.JobCase] =
      jc.tasks.indices.iterator.filter(jc.tasks(_).passingValidation).map { i =>
        jc.copy(tasks = jc.tasks.updated(i, jc.tasks(i).copy(passingValidation = false)))
      }

    val shrinkRowCount: Iterator[JobDagGen.JobCase] =
      jc.inputs.indices.iterator.filter(jc.inputs(_).rows > 1).map { i =>
        jc.copy(inputs = jc.inputs.updated(i, jc.inputs(i).copy(rows = jc.inputs(i).rows - 1)))
      }

    dropLastTask ++ dropEdge ++ dropValidationEdge ++ healInput ++ healTask ++ dropOutput ++
      dropValidation ++ shrinkRowCount
  }
}
