# Plan 05: Smarter join planner

> Status: not started · Tier: 2 · Effort: 2-4 days · Risk: low-medium

## Goal

Use `CatalogView.exactRowCount` and child-plan size estimates to make the
planner choose:
- Which side of a join is the build side (today: always the right side).
- Whether to use hash-join, nested-loop, or (after plan 04 lands)
  partitioned hash-join.
- Which conjuncts of the ON clause are equality keys vs residual.

Result: better wall time on joins where the right side is the larger one,
and graceful degradation when a tiny side meets a giant side.

## Why it matters

`HashJoinExec` materializes the entire right side into an in-memory
`ArrayBuffer[Array[Any]]` plus a `HashMap[Seq[Any], ArrayList[Int]]` of
keys (`JoinExec.scala:52-74`). If the right side is the *larger* one,
that's a strict pessimization vs. building on the left.

For jaffle_shop and polymarket today the join tree is small enough that
right-as-build is fine. But:
- A jaffle join like `stg_orders LEFT JOIN customers` puts ~150k orders
  on the right (build) and ~10k customers on the left (probe). Build
  is 15× larger than necessary.
- Polymarket `int_market_summary JOIN markets` has ~5M trades joined to
  124k markets — wrong-way build is 40× the right size.

`PhysicalPlanner` does no size estimation today (`PhysicalPlanner.scala:40-44`):

```scala
case LogicalJoin(l, r, cond, kind) =>
  val left = plan(l)
  val right = plan(r)
  val (leftKeys, rightKeys, extra) = splitEqualityKeys(cond, ...)
  HashJoinExec(left, right, leftKeys, rightKeys, extra, kind)
```

It just plumbs whatever order the SQL writer used.

## Current state

- `HashJoinExec` always uses the right side as build (`JoinExec.scala:11-23`).
- No size estimation in any planner pass.
- `CatalogView.exactRowCount` is implemented for parquet (via footer
  metadata) and in-memory `MaterializedView` (already counted). CSV does
  not implement it.
- Non-equi joins fall back to nested-loop (per `docs/gotchas.md`'s
  "v1: equi-join only" — actually the comment is in `JoinExec.scala:13`).
- Estimation through filters/projections: nonexistent. We only know
  scan sizes, not what survives.

## Proposed design

### 1. A size estimator pass

Add a `LogicalPlanCardinality` utility that estimates row count for
each node:

```scala
object LogicalPlanCardinality {
  def estimate(plan: LogicalPlan): Option[Long] = plan match {
    case LogicalScan(_, view, _) => view.exactRowCount
    case LogicalFilter(child, pred) =>
      estimate(child).map(n => (n * filterSelectivity(pred)).toLong)
    case LogicalProject(child, _) => estimate(child)
    case LogicalLimit(child, n) => estimate(child).map(c => math.min(c, n))
    case LogicalDistinct(child) => estimate(child).map(_ / 2)  // crude
    case LogicalAggregate(child, gks, _, _) =>
      estimate(child).map(n => if (gks.isEmpty) 1L else math.min(n, ndvHint(gks, n)))
    case LogicalJoin(l, r, _, kind) =>
      for { lc <- estimate(l); rc <- estimate(r) } yield joinEstimate(lc, rc, kind)
    case LogicalUnion(l, r, _) =>
      for { lc <- estimate(l); rc <- estimate(r) } yield lc + rc
    case LogicalSort(child, _) => estimate(child)
    case LogicalWindow(child, _) => estimate(child)
  }

  private def filterSelectivity(pred: Expr): Double = pred match {
    case BinOpExpr("AND", l, r, _) => filterSelectivity(l) * filterSelectivity(r)
    case BinOpExpr("OR", l, r, _) => 1.0 - (1.0 - filterSelectivity(l)) * (1.0 - filterSelectivity(r))
    case BinOpExpr("=", _, _, _) => 0.1   // best-guess constant
    case BinOpExpr(">" | "<" | ">=" | "<=", _, _, _) => 0.3
    case BinOpExpr("!=" | "<>", _, _, _) => 0.9
    case _: IsNullExpr => 0.1
    case _: LikeExpr => 0.5
    case _ => 0.5  // unknown — assume half
  }

  // Crude — proper NDV estimation would need sampling.
  private def ndvHint(gks: Seq[(Expr, String)], inputRows: Long): Long =
    math.max(1L, inputRows / math.max(1, gks.length * 100))

  private def joinEstimate(l: Long, r: Long, kind: JoinKind): Long = kind match {
    case JoinKind.Inner => math.max(l, r)
    case JoinKind.Left => l
    case JoinKind.Right => r
    case JoinKind.Full => l + r
  }
}
```

These constants are deliberately crude. Spark uses sketches; this codebase
doesn't have that infrastructure and shouldn't acquire it. The estimator's
only job is to discriminate "1000 rows" from "1M rows" so we pick the
right build side.

### 2. Use the estimate in `PhysicalPlanner`

```scala
case LogicalJoin(l, r, cond, kind) =>
  val left = plan(l)
  val right = plan(r)
  val (leftKeys, rightKeys, extra) = splitEqualityKeys(cond, ...)
  val swap = shouldSwapBuildSide(l, r, kind)
  if (swap)
    HashJoinExec(right, left, rightKeys, leftKeys, extra, kind.swapped)
  else
    HashJoinExec(left, right, leftKeys, rightKeys, extra, kind)
```

`shouldSwapBuildSide(l, r, kind)`:
- Compute `estimate(l)` and `estimate(r)`.
- For Inner: swap iff `lc < rc` (build smaller side).
- For Left outer: keep left as probe (preserves left's unmatched rows). Don't swap.
- For Right outer: same logic, mirrored. Always swap so right becomes the probe.
- For Full outer: no benefit from swap (both sides emit unmatched). Skip.

`JoinKind.swapped` flips Left↔Right but Inner and Full are self-inverse.
This already kind-of exists implicitly; make it explicit:

```scala
object JoinKind {
  def swap(k: JoinKind): JoinKind = k match {
    case Inner => Inner
    case Left => Right
    case Right => Left
    case Full => Full
  }
}
```

### 3. Threshold for nested-loop fallback

When the smaller side is tiny (say < 1000 rows) AND the join condition is
non-equi, nested-loop is fine. Don't error. Today, nested-loop exists for
non-equi but it's not aware of size — it'll happily nested-loop 1M × 1M.

Add a threshold (`NestedLoopMaxRows = 5000`); above it, refuse a nested-loop
plan and throw a clear `UnsupportedOperationException` ("non-equi join over
>5k rows requires equality keys").

### 4. (Optional, defer) Sort-merge join

Out of scope for plan 05. The hash-join model is fine for in-memory; a
sort-merge variant is only useful when both sides spill (plan 09 territory).

### 5. (After plan 04 lands) Build-side hint propagates to ExchangeExec

When the planner picks a swap, it must also swap the leftKeys/rightKeys
for ExchangeExec on each side. Coordinate with plan 04.

## Files to touch

- **New**: `src/main/scala/com/transformer/sql/plan/LogicalPlanCardinality.scala`.
- **New**: `src/test/scala/com/transformer/sql/plan/LogicalPlanCardinalityTest.scala`.
- **Modified**: `src/main/scala/com/transformer/sql/exec/PhysicalPlanner.scala`.
- **Modified**: `src/main/scala/com/transformer/sql/plan/LogicalPlan.scala` —
  `JoinKind` companion with `.swap`.
- **Modified**: `src/main/scala/com/transformer/sql/exec/JoinExec.scala` —
  consume swapped kind correctly. The body already encodes the asymmetry
  in `JoinKind.Right` / `JoinKind.Full` branches; verify those still fire
  when the user-written SQL says `LEFT JOIN` but the planner swapped.
- **Modified existing test**: `SqlEngineTest.scala` — assert join outputs
  unchanged regardless of build side.

## Edge cases

1. **Outer join correctness post-swap.** Swapping a `LEFT JOIN` to right-as-probe
   is fine *semantically* because `LEFT JOIN` after swap becomes `RIGHT JOIN`.
   But the SQL contract is "every left-side row appears". Verify post-swap
   that the planner uses the right output shape and column order.
2. **No exactRowCount** (CSV-only inputs). Estimator returns None; planner
   falls back to current behavior (no swap). This is OK — most polymarket
   inputs are parquet.
3. **Filter selectivity estimates are wildly wrong** for queries with
   highly selective filters on the build side. Mitigation: this is a
   v1 heuristic; the worst case is "we don't swap when we should have".
4. **Cardinality changes per-task.** A task's input view is sized at
   plan time. `exactRowCount` is from footer metadata — reliable.
5. **`SELECT ... FROM small_view JOIN big_view`** — without estimation,
   the user is forced to write joins in the right order. With estimation,
   they can write either way. Document this as a feature.
6. **Self-join.** Estimation: both sides equal. No swap. Fine.
7. **Multi-way join chain** (A JOIN B JOIN C). The planner sees them as
   left-deep; estimation should walk through. The current code already
   handles `LogicalJoin(LogicalJoin(...), c)`.

## Testing

### Correctness
- All existing `SqlEngineTest` cases must pass unchanged.
- Add planner-level tests:
  - Force swap via known sizes (in-memory views with explicit row counts);
    verify the resulting `HashJoinExec` has swapped sides.
  - Outer join swap correctness: `LEFT JOIN` on `small ⋈ big` with
    swap should produce same output as without swap.
- Cardinality estimator unit tests: scan, filter, project, aggregate,
  join, union, distinct, limit.

### End-to-end
- jaffle_shop: 15/15 Succeeded; expect noticeable improvement on the
  `stg_orders → mart_orders` chain.
- polymarket: 15/1/1; expect improvement on `mart_market_summary` and
  related `*_JOIN markets` tasks.

### Performance
- Microbenchmark: join 150k × 10k with build on the wrong side vs right
  side. Should see ~10× speedup with swap.
- Polymarket joined tasks: measure individual task wall times before/after.

## Risks

1. **Outer join orientation drift.** Swapping `LEFT JOIN` requires
   flipping the unmatched-row semantics. Mitigation: explicit
   `JoinKind.swap`; tests per kind.
2. **Bad estimates leading to worse plans.** Mitigation: only swap when
   the size ratio exceeds a threshold (eg. 2×) — small differences are
   wash anyway.
3. **No statistics for CSV.** Many CSV-only test fixtures. Estimator
   returns None; planner falls back. No regression.
4. **Interaction with plan 04.** If both land, the planner has more to
   decide: swap + partition both sides. Sequence them carefully.

## Suggested phases

1. **Phase 1**: build `LogicalPlanCardinality` with tests. No planner
   integration yet.
2. **Phase 2**: add `JoinKind.swap` and modify `PhysicalPlanner.plan`
   to swap when beneficial. Threshold-gated.
3. **Phase 3**: add nested-loop size guard.
4. **Phase 4**: profile + tune the selectivity constants if needed.

## Docs to update

- `docs/architecture.md` — add a section on plan-time cardinality
  estimation.
- `docs/conventions.md` — pattern for selectivity defaults.
- `docs/extending.md` — when adding a new operator, decide whether to
  contribute a `LogicalPlanCardinality` case.
- `docs/gotchas.md` — note that joins may now plan with sides swapped
  vs how the user wrote them.

## Launch prompt

```
Read plans/perf/05-join-planner.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Follow the 4-phase plan. Phase 2 (planner integration) is the landmark;
land it in its own PR with side-by-side join timings on jaffle_shop and
polymarket. Selectivity constants in Phase 4 should be left at the
plan's defaults unless profiling shows a regression.

Spawn a parallel sub-agent for: writing the planner-level swap-correctness
tests (forced sizes via in-memory views) while implementing the
estimator.

Stop and ask before: (a) introducing any new statistics-collection
infrastructure (sketches, sampling), (b) changing the JoinKind ADT, (c)
adding a sort-merge join variant — that's plan 09 territory.

Include in PR description: jaffle_shop join task wall times before/
after, polymarket mart_* task wall times before/after, list of joins
where the planner now swaps build side.
```
