package com.transformer.sql.exec

import com.transformer.core.{CatalogView, ColumnarBatch, DataType, ExecutionOptions, Schema}
import com.transformer.core.metrics.{MeteredIterator, MetricsNode}
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan._

import scala.collection.mutable

/** Logical → physical conversion. Applies a small set of rewrites first
  * (projection pruning, predicate pushdown for join keys, equality split,
  * build-side selection for hash joins).
  *
  * Accepts [[ExecutionOptions]] (defaults to [[ExecutionOptions.Default]] for
  * callers that don't carry per-task config). Spill-capable operators
  * (`SortExec`, `HashAggregateExec`, `DistinctExec`, and the grace-hash /
  * bucketed-window paths) read the `opts` value at construction to decide
  * whether to spill to disk; with spill disabled (the default) the cost is a
  * single boolean check per operator.
  *
  * Metrics: when `opts.metricsEnabled` is true, each operator's `execute(p)`
  * is wrapped in a [[MeteredIterator]] via a one-shot recursive walk in
  * [[plan]]. The planner-time wrap means the disabled path is a single branch
  * + load (`if (opts.metricsEnabled)`) and the per-row inner loops are
  * untouched. Use [[planWithMetrics]] when the caller wants both the wrapped
  * tree and the root [[MetricsNode]] for snapshotting after the iterator
  * drains.
  */
/** The nested-loop guard's refusal ([[PhysicalPlanner]]): a join with no
  * equality between its sides over inputs too large to pair. Which joins have
  * one depends on the order the joins run in, which the optimizer chooses
  * ([[com.transformer.sql.plan.JoinOrder]]), so a plan the optimizer did not
  * order may meet it where the optimized plan does not. */
final class NestedLoopGuardException(message: String) extends IllegalArgumentException(message)

object PhysicalPlanner {

  def plan(logical: LogicalPlan, opts: ExecutionOptions = ExecutionOptions.Default): PhysicalPlan = {
    val raw = planInner(logical, opts)
    if (opts.metricsEnabled) wrapWithMetrics(raw)._1 else raw
  }

  /** Variant of [[plan]] that returns both the planned tree and the root
    * [[MetricsNode]] for the operator tree. The `MetricsNode` is `Some` iff
    * `opts.metricsEnabled` is true; when `None` no wrapping was performed
    * and the caller does not need to capture a tree.
    *
    * The returned root carries a pre-order set of child nodes (recursively),
    * one per operator. Each operator also holds a reference to its own
    * [[MetricsNode]] (threaded in by [[wrapChildren]]) so it can populate its
    * custom counters.
    */
  def planWithMetrics(logical: LogicalPlan, opts: ExecutionOptions): (PhysicalPlan, Option[MetricsNode]) = {
    val raw = planInner(logical, opts)
    if (opts.metricsEnabled) {
      val (wrapped, node) = wrapWithMetrics(raw)
      (wrapped, Some(node))
    } else (raw, None)
  }

  /** Materialize every [[ExchangeExec]] in `plan` bottom-up (post-order:
    * children before parents), BEFORE any consumer of the plan runs. Both
    * engine drain paths call this right after planning ([[SqlEngine]]'s two
    * execute paths and [[CteResolver]]'s CTE-body materialization), so by the
    * time consumer tasks exist every exchange is published and `execute(s)`
    * is a plain array read.
    *
    * This ordering is the K>1 sharded-execution liveness fix
    * (plans/bugfixes/02f). Every deadlock cycle observed at campaign scale
    * (02e) runs through a thread waiting on an exchange that is not yet
    * materialized — the one wait in the engine that does not follow the
    * task-creation tree, so the one wait `ForkJoinTask.get()` work-helping
    * cannot rescue once helpJoin has inlined a consumer of the exchange
    * beneath the materializer's own frames. Materializing in dependency
    * order removes that wait entirely: when an exchange materializes here,
    * every exchange in its child subtree is already published, so its
    * `Scheduler` fan-out is a pure task tree (work-helping completes it —
    * the `core:scheduler_test` invariant) and no readiness wait exists
    * anywhere in flight. Plans with no exchanges (sharding off — the
    * shipping default) walk and find nothing.
    *
    * Shared subtrees (CTE inlining shares plan nodes, making the tree a DAG)
    * are visited once, by identity, so a diamond-shared exchange
    * materializes exactly once. [[MeteredPlan]] wrappers are transparent. */
  def preMaterializeExchanges(plan: PhysicalPlan): Unit = {
    val visited = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[PhysicalPlan, java.lang.Boolean])
    def walk(p: PhysicalPlan): Unit = {
      if (!visited.add(p)) return
      childrenOf(p).foreach(walk)
      p match {
        case e: ExchangeExec => e.materializeNow()
        case _               => ()
      }
    }
    walk(plan)
  }

  /** Immediate children of a physical operator, for [[preMaterializeExchanges]]'
    * walk. Mirrors [[wrapChildren]]'s arity knowledge — a new operator with
    * children needs a branch in BOTH places (here, a missed branch means a
    * subtree's exchanges escape pre-materialization and fall back to the lazy
    * latch path, whose nesting hazards `ExchangeExec.ensureMaterialized`
    * documents). */
  private def childrenOf(plan: PhysicalPlan): Seq[PhysicalPlan] = plan match {
    case m: MeteredPlan           => Seq(m.underlying)
    case f: FilterExec            => Seq(f.child)
    case p: ProjectExec           => Seq(p.child)
    case l: LocalLimitExec        => Seq(l.child)
    case g: GlobalLimitExec       => Seq(g.child)
    case o: OffsetExec            => Seq(o.child)
    case a: HashAggregateExec     => Seq(a.child)
    case j: HashJoinExec          => Seq(j.left, j.right)
    case s: SortExec              => Seq(s.child)
    case t: TopNExec              => Seq(t.child)
    case d: DistinctExec          => Seq(d.child)
    case e: ExchangeExec          => Seq(e.child)
    case w: WindowExec            => Seq(w.child)
    case u: UnionExec             => Seq(u.left, u.right)
    case _                        => Seq.empty // leaves: ScanExec, CountStarMetadataExec
  }

  private def planInner(logical: LogicalPlan, opts: ExecutionOptions): PhysicalPlan = logical match {
    case LogicalScan(_, view, _) => ScanExec(view, stopped = opts.stopped)
    case LogicalFilter(LogicalScan(name, view, schema), pred) =>
      // Best-effort: try to push the predicate into the scan view. The original
      // FilterExec stays in place — pushdown only enables row-group skipping
      // (stats can prove non-matching groups, never prove matching ones).
      val pushed = tryPushdown(view, pred)
      FilterExec(ScanExec(pushed.getOrElse(view), stopped = opts.stopped), pred)
    case LogicalFilter(child, pred) => FilterExec(planInner(child, opts), pred)
    case LogicalProject(child, projs) => ProjectExec(planInner(child, opts), projs)
    // Fast path: `SELECT COUNT(*) FROM <view>` with no WHERE, no GROUP BY, no HAVING.
    // The view can answer from metadata (parquet footer, in-memory row count) so
    // we skip the entire scan + per-row aggregation pipeline.
    case LogicalAggregate(LogicalScan(_, view, _), Seq(), Seq((AggExprCountStar(), name)))
        if view.exactRowCount.isDefined =>
      CountStarMetadataExec(view.exactRowCount.get, name)
    case LogicalAggregate(child, gks, aggs) =>
      val physChild = planInner(child, opts)
      if (gks.isEmpty || !shouldShardForSize(child) || gks.exists(k => Expr.readsRandom(k._1))) {
        // No GROUP BY, OR the estimated input is small enough that the
        // exchange-shard overhead would dominate the per-shard parallelism,
        // OR a key reads RAND(), which the exchange would read a second time.
        // Fall back to the collapsing aggregate (fan out + merge).
        HashAggregateExec(physChild, gks, aggs, opts)
      } else {
        val partitionBy = gks.iterator.map(_._1).toSeq
        val exchanged = ExchangeExec(
          physChild,
          partitionBy,
          ExchangeExec.defaultNumShards,
          HashPartitioner.NullsToLast)
        HashAggregateExec(exchanged, gks, aggs, opts)
      }
    case LogicalSort(child, keys) => SortExec(planInner(child, opts), keys, opts)
    case LogicalDistinct(child) =>
      val physChild = planInner(child, opts)
      if (shouldShardForSize(child)) buildShardedDistinct(physChild, opts)
      else DistinctExec(physChild, opts)
    case LogicalUnion(l, r, all) =>
      val u = UnionExec(planInner(l, opts), planInner(r, opts))
      if (all) u
      else if (shouldShardUnion(l, r)) buildShardedDistinct(u, opts)
      else DistinctExec(u, opts)
    case LogicalLimit(child, n) =>
      topN(child, n, opts).getOrElse {
        val p = planInner(child, opts)
        if (p.numPartitions <= 1) LocalLimitExec(p, n)
        else GlobalLimitExec(LocalLimitExec(p, n), n)
      }
    case LogicalOffset(child, n) => OffsetExec(planInner(child, opts), n)
    case LogicalJoin(l, r, cond, kind) =>
      val left = planInner(l, opts)
      val right = planInner(r, opts)
      val (leftKeys, rightKeys, extra) = splitEqualityKeys(cond, l.outputSchema.length, r.outputSchema.length)
      if (leftKeys.isEmpty && rightKeys.isEmpty) enforceNestedLoopGuard(l, r)
      val buildRight = shouldBuildRight(l, r, kind)
      if (leftKeys.isEmpty || !shouldShardJoin(l, r) || (leftKeys ++ rightKeys).exists(Expr.readsRandom)) {
        // Either non-equi (no keys to shard by) OR the smaller side is
        // broadcast-shaped (fits in heap) so we keep the historic shape:
        // build one side, stream the other. Sharding both sides forces an
        // extra pass over the *large* side just to redistribute rows; with
        // a small build that pass dominates the join itself. A key that
        // reads RAND() is read once, so it is never sharded.
        HashJoinExec(left, right, leftKeys, rightKeys, extra, kind, buildRight, opts)
      } else {
        // Equi-join with both sides large enough to benefit from sharded
        // build+probe. Hash-partition both sides by their respective keys so
        // matching keys collocate. NullsToZero policy — NULL keys never
        // match (SQL 3VL) but must still land in some shard so outer-join
        // unmatched emission sees them.
        val K = ExchangeExec.defaultNumShards
        val leftExch = ExchangeExec(left, leftKeys, K, HashPartitioner.NullsToZero)
        val rightExch = ExchangeExec(right, rightKeys, K, HashPartitioner.NullsToZero)
        HashJoinExec(leftExch, rightExch, leftKeys, rightKeys, extra, kind, buildRight, opts)
      }
    case LogicalWindow(child, windows) =>
      val physChild = planInner(child, opts)
      buildWindow(physChild, child, windows, opts)
  }

  /** Wrap a window's child in `ExchangeExec` when sharding is both safe
    * (every [[WindowSpec]] agrees on the same non-empty PARTITION BY key
    * set) AND cardinality-justified (input estimate ≥
    * [[LogicalPlanCardinality.MinShardableSize]]).
    *
    * Disagreeing PARTITION BYs would each need a different sharding — no
    * single exchange satisfies both — so we fall back to the historic
    * collapsing window. Empty PARTITION BY means the window spans the
    * whole result; sharding would split rows that must be computed
    * together. Below the cardinality threshold, the exchange overhead
    * isn't worth the parallelism (the same cardinality gate as aggregates). */
  private def buildWindow(
      physChild: PhysicalPlan,
      logicalChild: LogicalPlan,
      windows: Seq[WindowDef],
      opts: ExecutionOptions): WindowExec = {
    val distinctParts = windows.map(_.spec.partitionKeys).distinct
    val canShard =
      distinctParts.length == 1 && distinctParts.head.nonEmpty &&
      !distinctParts.head.exists(Expr.readsRandom) && shouldShardForSize(logicalChild)
    if (canShard) {
      val partitionBy = distinctParts.head
      val exchanged = ExchangeExec(
        physChild,
        partitionBy,
        ExchangeExec.defaultNumShards,
        HashPartitioner.NullsToLast)
      WindowExec(exchanged, windows, opts)
    } else {
      WindowExec(physChild, windows, opts)
    }
  }

  /** Wrap a physical plan in `ExchangeExec → DistinctExec` so dedup runs
    * per-shard. The exchange's `partitionBy` is the full column set the
    * dedup keys on — every output column becomes a [[ColRefExpr]], so two
    * rows are equal iff they collide on every column, the same predicate
    * DistinctExec's per-shard HashSet uses. */
  private def buildShardedDistinct(child: PhysicalPlan, opts: ExecutionOptions): DistinctExec = {
    val schema = child.outputSchema
    val ncols = schema.length
    val partitionBy = (0 until ncols).map { i =>
      val f = schema.fields(i)
      ColRefExpr(i, f.name, f.dataType)
    }
    val exchanged = ExchangeExec(
      child,
      partitionBy,
      ExchangeExec.defaultNumShards,
      HashPartitioner.NullsToLast)
    DistinctExec(exchanged, opts)
  }

  /** Cardinality gate for aggregate / distinct sharding: insert an
    * `ExchangeExec` only when the planner can prove the input is at least
    * [[LogicalPlanCardinality.MinShardableSize]] rows. Unknown size (`None`)
    * defaults to NOT sharding — the exchange's full materialization +
    * scatter overhead is a real cost, and silently paying it on inputs
    * whose size we can't reason about (streaming CSV) is the wrong default.
    * Callers with known-large inputs (parquet, in-memory views) get the
    * sharded parallelism; everyone else gets the collapsing path (fan out
    * across the child's partitions and merge). */
  private def shouldShardForSize(child: LogicalPlan): Boolean =
    LogicalPlanCardinality.estimate(child).exists(_ >= LogicalPlanCardinality.MinShardableSize)

  /** Cardinality gate for [[LogicalUnion]]'s downstream `DistinctExec`. The
    * union's row count is the sum of the two sides; we shard the dedup only
    * when that sum is large enough to justify the exchange. Unknown on
    * either side defaults to not sharding (conservative). */
  private def shouldShardUnion(l: LogicalPlan, r: LogicalPlan): Boolean = {
    val total = for {
      lc <- LogicalPlanCardinality.estimate(l)
      rc <- LogicalPlanCardinality.estimate(r)
    } yield lc + rc
    total.exists(_ >= LogicalPlanCardinality.MinShardableSize)
  }

  /** Cardinality gate for hash-join sharding: shard both sides only when
    * the *smaller* side is above [[LogicalPlanCardinality.BroadcastBuildThreshold]].
    * Below that, the smaller side fits in heap and the historic broadcast
    * shape (build small once, stream-probe large) is faster than scattering
    * the large side just to redistribute rows. */
  private def shouldShardJoin(l: LogicalPlan, r: LogicalPlan): Boolean = {
    (LogicalPlanCardinality.estimate(l), LogicalPlanCardinality.estimate(r)) match {
      case (Some(lc), Some(rc)) =>
        math.min(lc, rc) >= LogicalPlanCardinality.BroadcastBuildThreshold
      case _ =>
        // Either side unknown — broadcast via the historic collapsing path
        // rather than risk the perf trap of scattering a huge unknown input.
        false
    }
  }

  /** Minimum size ratio at which we'll swap the join build side. Keeps near-
    * equal estimates pinned to the default plan — the win below this ratio is
    * a wash and not worth the risk that the estimator was wrong.
    */
  private val JoinSwapRatio: Double = 2.0

  /** Refuse a nested-loop-style join (no equality conjuncts) when both
    * estimated sides exceed this row count. Below the threshold the
    * degenerate-hash path is fine — small × small is cheap. Above it, the
    * planner forces the user to add equality keys rather than silently
    * planning an O(N*M) join over millions of rows. */
  private val NestedLoopMaxRows: Long = 5000L

  /** Refuse a join with no equality conjuncts over the output of another such
    * join when the two sides would pair more rows than this: as many as two
    * sides of [[NestedLoopMaxRows]] do. A chain of such joins multiplies,
    * which bounding each side alone does not see: three tables of 300 rows
    * pair 27 million. */
  private val NestedLoopMaxPairs: Long = NestedLoopMaxRows * NestedLoopMaxRows

  /** Decide which side of a join to build into the hash table.
    *
    * Returns `true` when the right side should be built (the historic shape).
    * Returns `false` when the planner should swap to building the left side.
    *
    * The decision is driven by [[LogicalPlanCardinality.estimate]] for inner
    * joins (build the smaller side, with a threshold to ignore near-equal
    * sizes), and pinned by join kind for outer joins (the preserved side
    * stays the probe, so a RIGHT outer always swaps and a LEFT outer never
    * does — there's no symmetric "build smaller" call to make once the
    * preservation requirement is fixed). FULL outer stays at the default
    * because both sides emit unmatched rows regardless of build choice.
    *
    * If estimates are unavailable for either side (e.g. CSV inputs with no
    * exactRowCount), fall back to `true` — no information beats a guess.
    */
  private def shouldBuildRight(l: LogicalPlan, r: LogicalPlan, kind: JoinKind): Boolean = kind match {
    case JoinKind.Inner =>
      (LogicalPlanCardinality.estimate(l), LogicalPlanCardinality.estimate(r)) match {
        case (Some(lc), Some(rc)) if lc.toDouble * JoinSwapRatio <= rc.toDouble => false
        case _ => true
      }
    case JoinKind.Left  => true
    case JoinKind.Right => false
    case JoinKind.Full  => true
  }

  /** Throw when a non-equi join would have to pair two inputs known to be
    * large. The check never refuses a plan it cannot size: a side counts only
    * when its rows are known exactly ([[provenRows]]), so streaming CSV
    * inputs, and a side behind a filter, a grouping or a join condition, get
    * a degenerate-hash plan. When both sides are known and the smaller
    * exceeds [[NestedLoopMaxRows]] we bail — at that point the user has the
    * information to add equality keys and we have no business silently
    * materializing a cartesian product. The sizes in that message are facts:
    * they were estimates, a filter keeping a guessed share of its input, so
    * a CROSS JOIN of two one-row filters over 60,000-row tables was refused
    * as pairing 6000 rows with 6000.
    */
  private def enforceNestedLoopGuard(l: LogicalPlan, r: LogicalPlan): Unit = {
    (provenRows(l), provenRows(r)) match {
      case (Some(lc), Some(rc)) if math.min(lc, rc) > NestedLoopMaxRows =>
        throw new NestedLoopGuardException(
          s"A join without an equality between its two sides in ON is refused when both sides hold more than " +
            s"$NestedLoopMaxRows rows (left=$lc, right=$rc); add one to the ON condition")
      case _ => ()
    }
    // A side that is itself such a join, or a join on an equality over one,
    // holds up to the product of the pairing's sides, where its estimate says
    // the larger. One small side and one large (100 buckets against a
    // million rows) still runs.
    (rowsAtMost(l), rowsAtMost(r)) match {
      case (Some((lb, lPaired)), Some((rb, rPaired))) if (lPaired || rPaired) && times(lb, rb) > NestedLoopMaxPairs =>
        throw new NestedLoopGuardException(
          s"A join without an equality between its two sides in ON is refused over the output of another such " +
            s"join when the two would pair more than $NestedLoopMaxPairs rows (left=$lb, right=$rb); add an " +
            "equality to one of the ON conditions")
      case _ => ()
    }
  }

  /** How many rows `p` produces, when the plan proves it: a scan's exact
    * count, kept by the operators that keep every row (a projection, a sort,
    * a window), cut by LIMIT and OFFSET, added by UNION ALL, one for an
    * aggregate with no GROUP BY, and the product of a CROSS JOIN's sides.
    * `None` where a filter, a grouping, DISTINCT or a join condition decides
    * how many rows there are. */
  private def provenRows(p: LogicalPlan): Option[Long] = p match {
    case LogicalScan(_, view, _) => view.exactRowCount
    case LogicalProject(child, _) => provenRows(child)
    case LogicalSort(child, _) => provenRows(child)
    case LogicalWindow(child, _) => provenRows(child)
    case LogicalLimit(child, n) => provenRows(child).map(math.min(_, n))
    case LogicalOffset(child, n) => provenRows(child).map(c => math.max(0L, c - n))
    case LogicalUnion(l, r, true) => for (lc <- provenRows(l); rc <- provenRows(r)) yield lc + rc
    case LogicalAggregate(_, Seq(), _) => Some(1L)
    case LogicalJoin(l, r, LitExpr(true, _), JoinKind.Inner) =>
      for (lc <- provenRows(l); rc <- provenRows(r)) yield times(lc, rc)
    case _ => None
  }

  /** At most how many rows `p` produces, for [[enforceNestedLoopGuard]], and
    * whether that is the product of a join with no equality between its sides,
    * which pairs every row of one side with every row of the other that its
    * condition keeps, so up to all of them, or holds such a product's rows
    * through a join on an equality. `None` where a size is unknown. */
  private def rowsAtMost(p: LogicalPlan): Option[(Long, Boolean)] = p match {
    case LogicalJoin(l, r, cond, _)
        if splitEqualityKeys(cond, l.outputSchema.length, r.outputSchema.length)._1.isEmpty =>
      for ((lc, _) <- rowsAtMost(l); (rc, _) <- rowsAtMost(r)) yield (times(lc, rc), true)
    // A join on an equality keeps, by its estimate, its larger side's rows, so
    // it is sized from its sides' bounds: the estimator sizes a pairing below
    // it as that pairing's larger side.
    case LogicalJoin(l, r, _, kind) =>
      for ((lc, lPaired) <- rowsAtMost(l); (rc, rPaired) <- rowsAtMost(r))
        yield (LogicalPlanCardinality.joinEstimate(lc, rc, kind), lPaired || rPaired)
    // Each keeps at most the rows it reads.
    case LogicalProject(child, _) => rowsAtMost(child)
    case LogicalFilter(child, _) => rowsAtMost(child)
    case LogicalSort(child, _) => rowsAtMost(child)
    case LogicalWindow(child, _) => rowsAtMost(child)
    case LogicalDistinct(child) => rowsAtMost(child)
    case LogicalOffset(child, _) => rowsAtMost(child)
    case LogicalLimit(child, n) => rowsAtMost(child).map { case (c, paired) => if (n < c) (n, false) else (c, paired) }
    case other => LogicalPlanCardinality.estimate(other).map((_, false))
  }

  private def times(a: Long, b: Long): Long = if (a != 0 && b > Long.MaxValue / a) Long.MaxValue else a * b

  /** `child` under a `LIMIT n` as a [[TopNExec]] when it is a sort, below any
    * projections and an OFFSET the binder places between them: the sort keeps
    * only the rows the limit can return, with an `OFFSET m` the first `n + m`,
    * and the offset and projections run on those. A projection keeps its
    * input's row count, so the rows it sees are the ones the limit would have
    * kept. None for any other shape, for `LIMIT 0`, and past
    * [[TopNExec.MaxRows]] rows, where the sort, which can spill, runs whole. */
  private def topN(child: LogicalPlan, n: Long, opts: ExecutionOptions): Option[PhysicalPlan] = child match {
    case LogicalSort(c, keys) if n >= 1 && n <= TopNExec.MaxRows && !keys.exists(k => Expr.readsRandom(k.expr)) =>
      Some(TopNExec(planInner(c, opts), keys, n.toInt))
    case LogicalProject(c, projections) => topN(c, n, opts).map(ProjectExec(_, projections))
    case LogicalOffset(c, m) if m <= TopNExec.MaxRows - n => topN(c, n + m, opts).map(OffsetExec(_, m))
    case _ => None
  }

  /** Try to push a bound predicate into the underlying view. Today only
    * [[ParquetReader]] participates — its `withPushdownFilter` translates the
    * expression to a parquet `FilterPredicate` and returns a new view that
    * skips row groups whose column statistics rule them out. Other view
    * implementations return None and the planner stays on the original. */
  private def tryPushdown(view: CatalogView, predicate: Expr): Option[CatalogView] = view match {
    case p: ParquetReader => p.withPushdownFilter(predicate)
    case _                => None
  }

  /** Split an AND-chain of conjuncts into (leftKey, rightKey) equality pairs and a
    * residual predicate. References on the right side of the join begin at column
    * index `leftWidth` in the combined schema.
    */
  private def splitEqualityKeys(cond: Expr, leftWidth: Int, rightWidth: Int): (Seq[Expr], Seq[Expr], Option[Expr]) = {
    val conjuncts = collectConjuncts(cond)
    val leftKeys = mutable.ArrayBuffer.empty[Expr]
    val rightKeys = mutable.ArrayBuffer.empty[Expr]
    val rest = mutable.ArrayBuffer.empty[Expr]
    conjuncts.foreach {
      case BinOpExpr("=", l, r, _) =>
        (JoinSideAnalysis.sideOf(l, leftWidth), JoinSideAnalysis.sideOf(r, leftWidth)) match {
          case (JoinSide.LeftOnly, JoinSide.RightOnly) =>
            leftKeys += l
            rightKeys += JoinSideAnalysis.shiftToRight(r, leftWidth)
          case (JoinSide.RightOnly, JoinSide.LeftOnly) =>
            leftKeys += r
            rightKeys += JoinSideAnalysis.shiftToRight(l, leftWidth)
          case _ => rest += BinOpExpr("=", l, r, DataType.BooleanType)
        }
      case other => rest += other
    }
    val residual = if (rest.isEmpty) None else Some(JoinSideAnalysis.conjunction(rest.toSeq))
    (leftKeys.toSeq, rightKeys.toSeq, residual)
  }

  private def collectConjuncts(e: Expr): Seq[Expr] = e match {
    case BinOpExpr("AND", l, r, _) => collectConjuncts(l) ++ collectConjuncts(r)
    case other => Seq(other)
  }

  // ----------------------------------------------------------------------
  // Metrics wrapping.
  //
  // When opts.metricsEnabled is true, [[plan]] runs the raw planner first
  // (planInner), then post-walks the result to:
  //   1. Assign each operator a stable pre-order id.
  //   2. Build a matching MetricsNode tree.
  //   3. Substitute each operator's children with metered wrappers, so that
  //      when the parent operator pulls from `child.execute(p)` the iterator
  //      it sees is a MeteredIterator and the child's counters get populated.
  //   4. Wrap the operator itself in a MeteredPhysicalPlan so the parent
  //      (or the SqlEngine, at the root) reads metered output.
  //
  // Disabled-path discipline: the only added cost when metricsEnabled is
  // false is the single `if (opts.metricsEnabled)` branch in [[plan]] above.
  // No allocation, no traversal, no override calls on the operator interior.
  // ----------------------------------------------------------------------

  /** Recursively wrap an un-metered plan tree, returning the wrapped root
    * plus its root metrics node. Mutable accumulator for monotonically
    * increasing operator ids — pre-order traversal so ids match the natural
    * read order of `_perf.json`. */
  private[exec] def wrapWithMetrics(root: PhysicalPlan): (PhysicalPlan, MetricsNode) = {
    val idCounter = new java.util.concurrent.atomic.AtomicInteger(0)
    walkAndWrap(root, idCounter)
  }

  /** Per-operator counter name array used to size [[MetricsNode]] at wrap
    * time. Each operator that owns custom counters exposes an
    * `IdxCounterNames` constant in its companion object. New operators not
    * listed here fall back to an empty array — their iterator-level counters
    * still populate, just no custom slots. */
  private def counterNamesFor(plan: PhysicalPlan): Array[String] = plan match {
    case _: HashAggregateExec => HashAggregateExec.IdxCounterNames
    case _: HashJoinExec      => HashJoinExec.IdxCounterNames
    case _: SortExec          => SortExec.IdxCounterNames
    case _: DistinctExec      => DistinctExec.IdxCounterNames
    case _: WindowExec        => WindowExec.IdxCounterNames
    case e: ExchangeExec      => ExchangeExec.counterNamesFor(e.numShards)
    case _: ScanExec          => ScanExec.IdxCounterNames
    case _                    => MeteredPlan.EmptyCounterNames
  }

  private def walkAndWrap(
      plan: PhysicalPlan,
      idCounter: java.util.concurrent.atomic.AtomicInteger): (PhysicalPlan, MetricsNode) = {
    val id = idCounter.getAndIncrement()
    val name = plan.getClass.getSimpleName
    val node = new MetricsNode(id, name, partition = 0, counterNames = counterNamesFor(plan))
    // Wrap children first (pre-order id assignment is already done above).
    // Pass the node down so each rebuilt operator gets a MetricsNode it can
    // populate custom counters into. The MeteredPlan wrapper still handles
    // framework-level row/batch counts via MeteredIterator.
    val (wrappedPlan, childNodes) = wrapChildren(plan, idCounter, node)
    node.children = childNodes
    (new MeteredPlan(wrappedPlan, node), node)
  }

  /** Rebuild a [[PhysicalPlan]] with its immediate children replaced by
    * metered wrappers AND with `metricsNode = node` threaded into the
    * operator's constructor so custom counters reach the same node the
    * wrapping [[MeteredPlan]] reads at snapshot time. Returns the rebuilt
    * plan plus the child metrics nodes (already populated with their own
    * subtrees).
    *
    * Pattern-matched per operator: each branch knows the operator's
    * child-arity and reconstructs the case class with new children. New
    * operators added in the future need a branch here OR a fallback that
    * leaves them unwrapped (the default below). Leaving an operator
    * unwrapped is safe — it just means its children's iterators aren't
    * metered. (New operators with children also need a [[childrenOf]]
    * branch, where the fallback is NOT safe for exchange-bearing subtrees.)
    */
  private def wrapChildren(
      plan: PhysicalPlan,
      idCounter: java.util.concurrent.atomic.AtomicInteger,
      node: MetricsNode): (PhysicalPlan, Vector[MetricsNode]) = plan match {
    case s: ScanExec =>
      // Leaf — no children to wrap. Rebuild with the metrics node so
      // ParquetReader-backed views can populate scan-side counters.
      (ScanExec(s.view, node, s.stopped), Vector.empty)
    case _: CountStarMetadataExec =>
      (plan, Vector.empty)
    case FilterExec(child, pred) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (FilterExec(wc, pred), Vector(cn))
    case ProjectExec(child, projs) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (ProjectExec(wc, projs), Vector(cn))
    case LocalLimitExec(child, n) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (LocalLimitExec(wc, n), Vector(cn))
    case GlobalLimitExec(child, n) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (GlobalLimitExec(wc, n), Vector(cn))
    case OffsetExec(child, n) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (OffsetExec(wc, n), Vector(cn))
    case HashAggregateExec(child, gks, aggs, opts, _) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (HashAggregateExec(wc, gks, aggs, opts, node), Vector(cn))
    case HashJoinExec(left, right, lk, rk, extra, kind, br, opts, _) =>
      val (wl, ln) = walkAndWrap(left, idCounter)
      val (wr, rn) = walkAndWrap(right, idCounter)
      (HashJoinExec(wl, wr, lk, rk, extra, kind, br, opts, node), Vector(ln, rn))
    case TopNExec(child, keys, n) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (TopNExec(wc, keys, n), Vector(cn))
    case SortExec(child, keys, opts, _) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (SortExec(wc, keys, opts, node), Vector(cn))
    case DistinctExec(child, opts, _) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (DistinctExec(wc, opts, node), Vector(cn))
    case ExchangeExec(child, keys, k, policy, _) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (ExchangeExec(wc, keys, k, policy, node), Vector(cn))
    case WindowExec(child, windows, opts, _) =>
      val (wc, cn) = walkAndWrap(child, idCounter)
      (WindowExec(wc, windows, opts, node), Vector(cn))
    case UnionExec(left, right) =>
      val (wl, ln) = walkAndWrap(left, idCounter)
      val (wr, rn) = walkAndWrap(right, idCounter)
      (UnionExec(wl, wr), Vector(ln, rn))
    case other =>
      // Future operators land here until they get an explicit branch. The
      // iterator they emit is still wrapped (by walkAndWrap above), but
      // their children are not visible to the metrics tree.
      (other, Vector.empty)
  }
}

/** Iterator-wrap delegator placed at planner time over every operator when
  * `opts.metricsEnabled` is true. Delegates schema and partition count to
  * the wrapped operator; intercepts `execute(p)` to wrap the returned
  * iterator in a [[MeteredIterator]].
  *
  * The node holds the per-operator counter container; the iterator increments
  * its framework-managed fields (rowsIn / rowsOut / batches / wallNanos).
  * Custom counters are populated by the operator itself, which receives the
  * same [[MetricsNode]] through its constructor.
  *
  * Why a separate class instead of overriding inside each operator: the
  * disabled path stays byte-for-byte the un-wrapped tree, and the wrap pass
  * runs only when the user opted in.
  */
private[exec] final class MeteredPlan(inner: PhysicalPlan, val node: MetricsNode) extends PhysicalPlan {
  def outputSchema: Schema = inner.outputSchema
  def numPartitions: Int = inner.numPartitions
  def execute(partition: Int): Iterator[ColumnarBatch] = {
    // The MetricsNode is shared across this operator's partitions (the planner
    // builds one tree); row/batch counters accumulate across partitions,
    // mirroring the per-partition fan-out operators already use. Per-partition
    // breakdowns aren't recorded today — see MetricsNode.partition.
    new MeteredIterator(inner.execute(partition), node)
  }
  /** Expose the wrapped plan for tests that need to assert the planner
    * produced the right shape. */
  def underlying: PhysicalPlan = inner
}

private[exec] object MeteredPlan {
  /** Counter-name array for operators that declare no custom counters — only
    * the framework-managed iterator counters are populated. Operators with
    * custom counters supply their own `IdxCounterNames` instead. */
  val EmptyCounterNames: Array[String] = Array.empty
}
