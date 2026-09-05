# Plan 07: Push predicates and projections through joins

> Status: not started · Tier: 3 · Effort: 2-4 days · Risk: medium

## Goal

Push down `LogicalFilter` above `LogicalJoin` into each child where the
predicate references only that side's columns. Push down `LogicalProject`
above `LogicalJoin` into both children, pruning columns that neither the
join keys nor the projection need.

Today, joins force a full scan of both sides because the planner only
prunes columns under `LogicalScan` (`ColumnProjectionPushdown.scala:110-113`)
and only pushes filters directly above scans (`PhysicalPlanner.scala:16-22`).

## Why it matters

Two specific wins, both currently observable in jaffle_shop:

1. **Filter through join**. A query like
   `SELECT * FROM orders JOIN customers ON ... WHERE customers.region = 'US'`
   today reads ALL customers, joins, then filters. Push the filter into
   the customers scan and read only the US ones — eliminates the join
   work for non-US customers entirely. On a 50/50 distribution that's
   a 2× reduction in build-side rows.

2. **Projection through join**. A query like
   `SELECT customer_id, sum(total) FROM orders JOIN customers ON ... GROUP BY ...`
   today projects `customers.*` through the join despite needing only
   `customer_id`. Decoding customers' string columns (`name`, `email`,
   etc.) is wasted I/O and decode CPU. On Polymarket, the orderbook
   has ~12 columns and many tasks need only 3-4; pruning the rest is
   5-20× decode speedup per `docs/architecture.md` §3 ("ColumnProjectionPushdown")
   — but only when it actually fires through joins.

`ColumnProjectionPushdown` already skips joins (line 110):

```scala
case j: LogicalJoin =>
  // Join references columns by combined-output indices; pruning either
  // side shifts everything to its right. Doable but invasive — skip for now.
  (j, identityRemap(j.outputSchema.length))
```

The "doable but invasive" comment is correct — the index remapping is the
hard part. This plan tackles it.

## Current state

### Filter pushdown
- `PhysicalPlanner.plan` (`PhysicalPlanner.scala:16-22`) only pushes
  filters into scans. A `LogicalFilter(LogicalJoin(...), pred)` is left
  intact; the filter runs after the join.
- No conjunct-splitting logic at the join level.

### Projection pushdown
- `ColumnProjectionPushdown.rewrite` skips `LogicalJoin` at line 110-113.
- Result: joins always read every column of both sides, regardless of
  what the projection above the join needs.

## Proposed design

### Part 1: Filter through join

Add a new logical-plan rewrite pass `FilterPushdown` that runs before
`PhysicalPlanner`. Pattern: `LogicalFilter(LogicalJoin(l, r, cond, kind), pred)`.

For each conjunct in `pred`:
- Compute which side(s) it references via `sideOf` (already exists in
  `PhysicalPlanner.scala:96-111`; lift to a shared utility).
- **Inner join**: push left-side conjuncts to left child as `LogicalFilter`,
  same for right. Conjuncts on both sides stay above the join.
- **Left outer join**: push left-side conjuncts (preserve left's rows)
  to left child. Conjuncts on the right side **cannot** be pushed — they
  would turn unmatched rows (which got null on the right side) into
  filtered-out rows, changing the join's semantics. Move them into the
  join condition itself? No — that's still wrong. Leave them above.
- **Right outer join**: symmetric.
- **Full outer join**: nothing pushable (both sides have null-extended rows).

When a conjunct lands inside a child, recurse: if the child is another
join, push further.

The result: filters get pushed as close to scans as possible. With plan 06's
expanded parquet pushdown, more of them then translate into row-group skips.

### Part 2: Projection through join

Extend `ColumnProjectionPushdown.rewrite` to handle `LogicalJoin`.

The key insight is that the join's output is `left.outputSchema ++
right.outputSchema` — left columns at indices [0, leftWidth), right
at [leftWidth, leftWidth+rightWidth). When the parent only references
some columns, we can:
1. Compute which left columns and which right columns the parent needs
   (using `colRefNames` already in the file).
2. Add the join's own column references (keys + extra predicate columns)
   to the needed set.
3. Recursively rewrite each child with that side's needed columns.
4. Compute the new `JoinExec`'s leftWidth (which has shrunk) and update
   the parent's `ColRefExpr` indices via a remap.

```scala
case LogicalJoin(l, r, cond, kind) =>
  val leftSchema = l.outputSchema
  val rightSchema = r.outputSchema
  val leftWidth = leftSchema.length

  // Names parent wants that come from left, vs right.
  val parentLeftNeeded = neededByParent.filter(leftSchema.fieldNames.contains)
  val parentRightNeeded = neededByParent.filter(rightSchema.fieldNames.contains)

  // Plus the cols the join condition itself references on each side.
  val condRefs = colRefNames(cond)
  val leftCondNeeded = condRefs.filter(leftSchema.fieldNames.contains)
  val rightCondNeeded = condRefs.filter(rightSchema.fieldNames.contains)

  val leftNeeded = parentLeftNeeded ++ leftCondNeeded
  val rightNeeded = parentRightNeeded ++ rightCondNeeded

  val (newLeft, leftRemap) = rewrite(l, leftNeeded)
  val (newRight, rightRemap) = rewrite(r, rightNeeded)

  // The new join's left width is newLeft.outputSchema.length. Rebuild
  // the join condition with shifted indices.
  val newLeftWidth = newLeft.outputSchema.length
  val newCond = remapJoinCondition(cond, leftWidth, leftRemap, newLeftWidth, rightRemap)

  // Build a remap from old combined indices to new combined indices.
  val combinedRemap = combineRemaps(leftRemap, rightRemap, leftWidth, newLeftWidth)

  (LogicalJoin(newLeft, newRight, newCond, kind), combinedRemap)
```

`remapJoinCondition` walks the condition Expr; for each `ColRefExpr(i)`:
- If `i < leftWidth`: use `leftRemap(i)` as the new index.
- Else: use `newLeftWidth + rightRemap(i - leftWidth)`.

`combineRemaps` builds the parent's remap: each old left index `i` maps
to `leftRemap(i)`; each old right index `leftWidth + j` maps to
`newLeftWidth + rightRemap(j)`.

### Part 3: Sequencing

Run `FilterPushdown` first (logical-plan rewrite), then
`ColumnProjectionPushdown`. Filter pushdown can change which columns
are needed downstream of a join, so projection pruning must run after.

Both happen before `PhysicalPlanner.plan`. Add a `LogicalOptimizer` that
runs all passes:

```scala
object LogicalOptimizer {
  def optimize(plan: LogicalPlan): LogicalPlan = {
    val withFiltersPushed = FilterPushdown(plan)
    val withProjectionsPushed = ColumnProjectionPushdown(withFiltersPushed)
    withProjectionsPushed
  }
}
```

`SqlEngine.execute` calls `LogicalOptimizer.optimize` before planning.

## Files to touch

- **New**: `src/main/scala/com/transformer/sql/plan/FilterPushdown.scala`.
- **Modified**: `src/main/scala/com/transformer/sql/plan/ColumnProjectionPushdown.scala`
  — handle `LogicalJoin`.
- **New**: `src/main/scala/com/transformer/sql/plan/LogicalOptimizer.scala`
  (or extend an existing analyzer entry point).
- **Modified**: `src/main/scala/com/transformer/sql/exec/SqlEngine.scala`
  — call the optimizer before planning.
- **New tests**: 
  - `src/test/scala/com/transformer/sql/plan/FilterPushdownTest.scala`
  - extend `src/test/scala/com/transformer/sql/plan/ColumnProjectionPushdownTest.scala`
    (if it exists; otherwise create).

## Edge cases

### Filter pushdown
1. **Outer join filter semantics.** Critical: cannot push a filter on
   the outer-null-extended side. `LEFT JOIN` followed by `WHERE r.x = 1`
   acts as an inner join (filters out unmatched-right rows). Pushing
   `r.x = 1` into r would change the result. **Solution**: don't push
   right-side filters under a LEFT JOIN, or convert the LEFT JOIN to
   INNER first (only safe if the filter is null-rejecting). Be very
   conservative — easier to skip the optimization than to corrupt
   results.
2. **Filter references both sides** (`l.x + r.y > 0`) — can't push,
   stays above.
3. **NULL-aware predicates** (`r.x IS NULL`) on outer-null-extended
   side: matches the null-extended rows, do NOT push.
4. **Filter on aggregate** (HAVING) — not above a join, doesn't apply.
5. **Multiple stacked filters** — flatten the AND chain, push each
   conjunct independently.

### Projection pushdown through join
1. **Join condition columns must survive.** If the user query writes
   `SELECT l.a FROM l JOIN r ON l.b = r.b` we must keep `l.b` and `r.b`
   in their respective scans even though the projection doesn't
   reference them.
2. **Star projections** (`SELECT *`) — the parent needs every column;
   the rewrite does nothing.
3. **Index remap consistency.** The hardest part. The combined join
   output schema's positions shift after pruning. Every ancestor's
   `ColRefExpr` must be remapped. Use the existing `rewriteExpr` helper
   and extend to walk into join conditions.
4. **Aliased columns.** `LogicalProject` may rename; the rewrite uses
   field *names*, not aliases. Verify alias handling.
5. **Self-join.** Both sides share schema. Field-name lookup ambiguity:
   `colRefNames` returns a set of strings — `l.x` and `r.x` collide.
   Today's code happens to work because column indices disambiguate at
   the SQL level; we must keep doing that. May need to use
   `Set[(side, name)]` instead of `Set[String]`. Verify with a
   self-join test.

## Testing

### Correctness
- For every existing `SqlEngineTest` case that uses joins: result must
  be unchanged with the optimizer enabled.
- New tests for `FilterPushdown`:
  - Inner join with conjuncts on each side: assert both pushed.
  - Inner join with cross-side conjunct: assert kept above.
  - LEFT JOIN with right-side filter: assert NOT pushed.
  - LEFT JOIN with left-side filter: assert pushed.
- New tests for projection pushdown through join:
  - `SELECT l.a, r.b FROM l JOIN r ON l.k = r.k`: assert l's scan
    reads only (a, k); r's reads only (b, k).
  - `SELECT * FROM l JOIN r`: no pruning.
  - Self-join: verify both sides keep needed columns.

### End-to-end
- jaffle_shop: 15/15. Expected improvement on `mart_customers`
  (joins customers + orders + order_items).
- polymarket: 15/1/1. Expected improvement on `mart_market_summary`
  (joins trades + markets + snapshots).

### Performance
- Microbenchmark: a join where one side has a 50%-selective filter; 
  measure rows decoded on that side before/after.
- Polymarket mart task wall times before/after.
- Decoded bytes count from parquet (if accessible via parquet-mr stats)
  before/after.

## Risks

1. **Outer-join filter pushdown is the #1 correctness landmine.** Default
   to not pushing on outer joins; only push when the filter is provably
   null-rejecting on the inner side. Add many tests.
2. **Index remap bugs.** The remap covers join cond + parent expressions;
   missing a case silently mis-routes column reads. Mitigation: assert
   `outputSchema` consistency at every rewrite step; add a debug-mode
   verification that the rewritten plan re-types correctly.
3. **Self-join column-name ambiguity.** Mitigation: dedicated self-join
   tests + per-side name tracking.
4. **Interaction with plan 04 (sharding).** If the join's child becomes
   a hash-partitioned ExchangeExec, the projection pruning still applies
   below it (Exchange just shuffles rows; doesn't change schema). Should
   compose cleanly.
5. **Optimizer pass ordering.** Filter pushdown changes the column set
   downstream; projection pushdown must rerun after. Pin the order;
   don't add per-pass repeats unless profiling shows benefit.

## Suggested phases

1. **Phase 1**: extract `sideOf` from `PhysicalPlanner` to a shared
   utility in `sql/plan/`. Write `FilterPushdown` for **inner joins
   only**. Test thoroughly.
2. **Phase 2**: extend `FilterPushdown` to LEFT / RIGHT outer joins
   with the conservative pushable-side-only logic.
3. **Phase 3**: extend `ColumnProjectionPushdown.rewrite` to handle
   `LogicalJoin`. This is the gnarly remap work.
4. **Phase 4**: tie both passes together via `LogicalOptimizer`.
5. **Phase 5 (optional, deferred)**: Union pruning. `ColumnProjectionPushdown`
   also skips `LogicalUnion`; if a union is ever a bottleneck, similar
   remap work applies.

## Docs to update

- `docs/architecture.md` — add a "Logical plan optimization" section
  describing the two passes and their order.
- `docs/gotchas.md` — remove the implicit fiction in
  `ColumnProjectionPushdown.scala:110-113` ("doable but invasive —
  skip for now"). Reflect the new state.
- `docs/conventions.md` — pattern for adding new logical-plan rewrites.
- `docs/extending.md` — recipe for a new logical-plan optimizer pass.

## Launch prompt

```
Read plans/perf/07-push-through-joins.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Follow the 5 phases. Phase 2 (outer-join filter pushdown) is the
correctness landmine — be conservative. Default to NOT pushing on outer
joins unless the filter is provably null-rejecting on the inner side;
when in doubt, leave the filter above the join.

For Phase 3, the index remap is the hard part. Add a debug-mode
assertion at every rewrite step that the rewritten plan's outputSchema
re-types correctly — catch index errors at plan time, not run time.

Spawn parallel sub-agents for: (a) building the comprehensive outer-join
correctness test matrix in parallel with Phase 2 implementation, (b)
profiling jaffle mart_* tasks before and after to quantify the win.

Stop and ask before: (a) pushing a filter on the null-extended side of
an outer join under any condition, (b) introducing a generic
optimizer-pass framework — keep it simple, two explicit passes called
in order, (c) touching anything outside sql/plan/ except SqlEngine.

Include in PR description: jaffle and polymarket mart task wall times
before/after; bytes decoded from parquet on the joined sides before/
after.
```
