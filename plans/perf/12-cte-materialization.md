# Plan 12: Materialize multiply-referenced CTEs once

> Status: done (Phases 1-3 landed; Phase 4 refinements deferred) · Tier: 2 · Effort: 3-5 days · Risk: medium

## Goal

Today CTEs are **inlined**: each reference to a `WITH` name is replaced with
the CTE's logical-plan subtree at build time (`LogicalBuilder.fromItem`
resolves the name from a `cteScope: Map[String, LogicalPlan]` and returns the
body plan). A CTE referenced N times is therefore planned and executed N
times. For an expensive body (aggregation, join, wide scan) referenced more
than once, that is wasted work.

This plan makes a CTE referenced **N ≥ 2 times compute exactly once**: execute
its body a single time into an in-memory `MaterializedView`, then have every
reference scan that shared result. Single-reference CTEs stay inlined (zero
overhead, streaming preserved) — materialization is opt-in by reference count,
not unconditional.

Non-goal: CTE-result caching *across* SQL statements / tasks (that is what the
multi-task pipeline + `viewName` chaining already does). This plan is about
sharing within a single query.

## Why it matters

`WITH c AS (<expensive>) SELECT ... FROM c x JOIN c y ON ...` currently builds
two independent physical subtrees for `c` and runs `<expensive>` twice. The
same shape appears whenever a query self-joins a derived relation, fans a
staging CTE into several downstream branches, or references a CTE in both arms
of a `UNION`.

Two payoffs:

1. **Performance.** One execution of the body instead of N. The win scales
   with body cost × (N − 1). A 27M-row filter+aggregate referenced twice goes
   from two full passes to one pass + two cheap in-memory replays.
2. **Semantics (an optimization fence).** A CTE body containing a
   non-deterministic function (`RAND()`, `CURRENT_TIMESTAMP`) is, under
   inlining, evaluated independently per reference — so `c x JOIN c y` can see
   *different* values on each side. Materializing once gives every reference
   the same rows, which is the behaviour users expect from a named relation
   (and what Postgres' `MATERIALIZED` CTEs guarantee). This is a latent
   correctness improvement, not just a speedup.

A bonus falls out for free: a `MaterializedView` reports
`exactRowCount`, so the main planner gets an exact cardinality for the CTE
scan (better hash-join build-side selection via
[plan 05](05-join-planner.md)'s `LogicalPlanCardinality`), and
`SELECT COUNT(*) FROM cte` can hit the metadata fast-path in
`PhysicalPlanner`.

## Current state

- `LogicalBuilder.buildAnySelect` reads `Select.getWithItemsList`, builds each
  CTE body into a `LogicalPlan`, and accumulates `cteScope: Map[String,
  LogicalPlan]`. `fromItem` consults the scope before the catalog and returns
  the **body plan object** directly — that object is shared across reference
  sites (so the plan is technically a DAG of shared immutable subtrees), but
  `PhysicalPlanner.planInner` walks it as a tree and re-plans the body at each
  occurrence. Net effect: recomputed N times. See `docs/architecture.md` §6b
  and `docs/gotchas.md` ("CTEs are supported but inlined").
- `MaterializedView` (`core/MaterializedView.scala`) already does exactly the
  "drain a `CatalogView`'s partitions into memory, replay on scan" job, with a
  parallel `materializeInParallel(view)` over the shared `Scheduler` pool and
  a `materializeManyInParallel(views)` for independent inputs. It reports
  `exactRowCount`. This is the materialization primitive we reuse — the job
  runner already uses it to eagerly load task inputs.
- `LogicalScan(viewName, view, alias)` is the only leaf node and carries an
  arbitrary `CatalogView`. We exploit this: a materialized CTE becomes a plain
  `LogicalScan` over a `MaterializedView` — no new logical node type.
- `SqlEngine.execute` runs `build → optimize → physical-plan → execute`. There
  is no phase between build and optimize today; we add the CTE resolve/
  materialize pass there.

## Proposed design

The whole feature is a new pass that runs **after** logical build and
**before** the main optimize/plan, plus a small builder change to defer the
inline decision. The optimizer, physical planner, `FilterPushdown`,
`ColumnProjectionPushdown`, and `LogicalPlanCardinality` are **untouched** —
by the time they see the plan, every CTE reference is either an inlined body
subtree or a `LogicalScan` over a `MaterializedView`. No new `sealed
LogicalPlan` subtype (which would force every exhaustive match to grow a case).

### 1. Defer the inline decision: pending-view placeholders

The builder can't decide inline-vs-materialize during its single pass, because
the decision needs the reference count, which isn't known until the whole
enclosing query is built. So stop returning the body from `fromItem`; instead
emit a placeholder scan and collect the definitions for a later pass.

A placeholder `CatalogView` that throws if it ever reaches execution
(`sql/plan/PendingCteView.scala`):

```scala
/** Stand-in for an unresolved CTE reference. Every instance is unique per
  * CTE definition; the resolver swaps each LogicalScan over it for either the
  * inlined body or a scan over the materialized result. Reaching execute()
  * means the resolver missed a reference — a builder bug, not a user error. */
final class PendingCteView(val cteName: String, val schema: Schema) extends CatalogView {
  private def unresolved: Nothing =
    throw new IllegalStateException(s"Unresolved CTE '$cteName' reached execution")
  def numPartitions: Int = unresolved
  def readPartition(p: Int): Iterator[ColumnarBatch] = unresolved
}
```

`fromItem` returns a scan over the pending view (note: identical alias handling
to a real table, so column resolution downstream is unchanged):

```scala
cteScope.get(name.toLowerCase) match {
  case Some(cte) => (LogicalScan(name, cte.pending, alias), Seq((alias, cte.pending.schema)))
  case None      => /* catalog lookup, as today */
}
```

where `cteScope` now maps a name to a small record carrying the body plan and
its unique pending view.

### 2. Builder returns the main plan plus an ordered CTE list

`LogicalBuilder.build` changes from returning `LogicalPlan` to a wrapper:

```scala
final case class CteDef(name: String, pending: PendingCteView, body: LogicalPlan)
final case class BuiltQuery(main: LogicalPlan, ctes: Seq[CteDef])
```

- `ctes` is in **post-order**: a nested `WITH` inside a CTE body emits its
  inner defs *before* the outer def, and sibling CTEs in declaration order.
  Because recursion is rejected and a CTE can only reference earlier siblings
  or enclosing-scope names, this guarantees: when we resolve `ctes(i)`, every
  def it depends on has already been resolved. References to `ctes(i)` can only
  appear in `ctes(j>i).body` or `main` — never earlier.
- For a query with no `WITH`, `ctes` is empty and `main` is byte-identical to
  today's plan. This keeps the common path a no-op.

`build`'s old `LogicalPlan` return is used by `SqlEngine` only; audit other
callers (GUI SQL console goes through `SqlEngine.execute`, so it's covered — 
confirm during Phase 1).

### 3. The resolve/materialize pass (`sql/exec/CteResolver.scala`)

Lives in `sql/exec` because materialization needs `PhysicalPlanner` + the
executor. One linear pass over the post-ordered defs:

```scala
object CteResolver {
  def resolve(built: BuiltQuery, opts: ExecutionOptions): LogicalPlan = {
    if (built.ctes.isEmpty) return built.main          // fast no-op path

    // Mutable working copies; each step rewrites the not-yet-processed
    // bodies and the main plan in place.
    val bodies = built.ctes.map(_.body).toArray
    var main = built.main

    built.ctes.iterator.zipWithIndex.foreach { case (cte, i) =>
      val resolvedBody = bodies(i)                      // deps already substituted
      // Count references across everything that can still mention this CTE.
      val consumers = bodies.drop(i + 1) :+ main
      val refs = consumers.iterator.map(p => countScans(p, cte.pending)).sum

      val replacement: LogicalPlan =
        if (refs >= 2) materialize(cte.name, resolvedBody, opts)   // LogicalScan(MV)
        else resolvedBody                                          // inline (refs 0 or 1)

      // refs == 0: dead CTE; replacement is unused, nothing executed. (We still
      // skip materialize() for refs<2, so an unused CTE is never run.)
      var j = i + 1
      while (j < bodies.length) { bodies(j) = substitute(bodies(j), cte.pending, replacement); j += 1 }
      main = substitute(main, cte.pending, replacement)
    }
    main
  }

  private def materialize(name: String, body: LogicalPlan, opts: ExecutionOptions): LogicalPlan = {
    val physical = PhysicalPlanner.plan(LogicalOptimizer.optimize(body), opts)
    val mv = MaterializedView.materializeInParallel(new PhysicalPlanView(physical))
    LogicalScan(name, mv, Some(name))
  }
}
```

Helpers:

- `countScans(plan, pending)` — structural walk counting `LogicalScan` nodes
  whose `view eq pending` (identity, unambiguous: one `PendingCteView` per def).
- `substitute(plan, pending, replacement)` — structural rewrite replacing each
  such scan with `replacement`. For materialization, `replacement` is a
  `LogicalScan(MV)`; the *same* MV object is shared by every site, so the body
  runs once and each site replays in-memory. For inline (`refs == 1`),
  `replacement` is the body subtree.
- `PhysicalPlanView(physical)` — trivial `CatalogView` adapter so
  `MaterializedView.materializeInParallel` (which wants a `CatalogView`) can
  drain a physical plan:

  ```scala
  final class PhysicalPlanView(p: PhysicalPlan) extends CatalogView {
    def schema: Schema = p.outputSchema
    def numPartitions: Int = p.numPartitions
    def readPartition(part: Int): Iterator[ColumnarBatch] = p.execute(part)
  }
  ```

`countScans` / `substitute` need to reconstruct every `LogicalPlan` node type.
Rather than hand-roll the walk twice (it already exists informally in
`FilterPushdown.rewrite` and `ColumnProjectionPushdown`), add one small,
non-generic helper to the trait:

```scala
sealed trait LogicalPlan {
  def outputSchema: Schema
  def children: Seq[LogicalPlan]
  def withChildren(c: Seq[LogicalPlan]): LogicalPlan   // NEW — per-node reconstruct
}
```

`children` already exists; `withChildren` is its inverse. Then `substitute`
and `countScans` are five lines each over `children` / `withChildren`. This is
not a rule engine — it's the structural-recursion primitive the codebase has
been open-coding. (Optional refactor: `FilterPushdown` /
`ColumnProjectionPushdown` could later adopt it, but that is out of scope here.)

### 4. Wire into `SqlEngine`

Both paths gain one line between build and optimize:

```scala
val built     = LogicalBuilder.build(sql, catalog)
val resolved  = CteResolver.resolve(built, opts)     // NEW
val optimized = LogicalOptimizer.optimize(resolved)
val physical  = PhysicalPlanner.plan(optimized, opts)
```

In `executeMeasured`, bracket `resolve` in its own `System.nanoTime` delta and
add `cteMaterializeNanos` to `QueryMetrics` (the body sub-executions run
unmeasured — they don't pollute the main operator tree; their cost is the
single materialize-phase number). Update the `_perf.json` writer / any metrics
test that asserts the phase set.

### 5. Policy: when to materialize

- **`refs >= 2` → materialize.** The case this plan targets.
- **`refs <= 1` → inline.** Preserves streaming and zero overhead for the
  common single-use CTE; `refs == 0` (declared-but-unused) is never executed.
- **Size cap (decision to confirm).** Materializing buffers the whole body in
  heap. Option A: always materialize at `refs >= 2` (simplest; recompute-twice
  of a huge body is also expensive, so materialize-once is usually still the
  better of two heavy options). Option B: gate on
  `LogicalPlanCardinality.estimate(body)` — skip materialization (fall back to
  inline-N) when the estimate exceeds a heap budget and the input is
  spill-incapable. Recommendation: **ship Option A**, document the heap
  implication, and leave Option B / spill-backed materialization (route through
  the [plan 09](09-spill-to-disk.md) spillable view) as a follow-up.

### 6. Refinements (optional, Phase 4)

- **Project to the used columns before materializing.** If references use
  different column subsets, materialize the *union* of referenced columns
  (wrap `body` in a `LogicalProject` of that union) so the MV doesn't buffer
  columns no consumer reads. Without this we materialize the body's full
  output.
- **Materialize independent CTEs in parallel** via
  `MaterializedView.materializeManyInParallel` when a scope has several
  mutually-independent `refs>=2` CTEs (no edges between them).
- **COUNT(\*) fast-path** already works once the CTE is an MV scan — no code,
  just note it in tests.

## Files to touch

- **New**: `src/main/scala/com/transformer/sql/plan/PendingCteView.scala` —
  the placeholder `CatalogView`.
- **New**: `src/main/scala/com/transformer/sql/exec/CteResolver.scala` —
  the resolve/materialize pass + `PhysicalPlanView` adapter (or a sibling
  `PhysicalPlanView.scala`).
- **New**: `src/test/scala/com/transformer/sql/exec/CteMaterializationTest.scala`
  — compute-once proof + policy + fence tests (see Testing).
- **Modified**: `src/main/scala/com/transformer/sql/plan/LogicalBuilder.scala`
  — `cteScope` holds `CteDef`s; `fromItem` emits pending-view scans; `build`
  returns `BuiltQuery`; defs collected in post-order across nested scopes.
- **Modified**: `src/main/scala/com/transformer/sql/plan/LogicalPlan.scala` —
  add `withChildren` to the trait + each case class; define `CteDef` /
  `BuiltQuery` (here or in `LogicalBuilder`).
- **Modified**: `src/main/scala/com/transformer/sql/exec/SqlEngine.scala` —
  call `CteResolver.resolve`; add the `cteMaterializeNanos` phase to the
  measured path.
- **Modified**: `src/main/scala/com/transformer/core/metrics/QueryMetrics.scala`
  (and the `_perf.json` writer / `MetricsPlanWrapTest`-adjacent asserts) — new
  phase field. Only if Phase 3 is included.
- **Modified**: `src/main/scala/com/transformer/core/MaterializedView.scala` —
  only if we add a `fromPhysical` convenience instead of a standalone adapter.

## Edge cases

1. **`refs == 0` (unused CTE).** No reference scans; never materialized, never
   executed. Parity with today (inlining also never runs it).
2. **`refs == 1`.** Inlined — byte-identical plan to today. This is the
   regression-critical path; jaffle's `order_items_summary` CTE is single-ref
   and must stay inlined and unchanged.
3. **Self-join over a CTE** (`FROM c x JOIN c y`). Two scans over one MV;
   per-site aliases (`x`, `y`) live on the `LogicalScan`, so the join's column
   resolution is unaffected. Body runs once.
4. **Dependent CTEs** (`WITH a AS (...), b AS (SELECT ... FROM a) ...`, both
   `refs>=2`). Post-order + linear pass resolves `a` first, rewriting `b.body`
   to scan `a`'s MV *before* `b` is materialized, so `b`'s single execution
   reads the already-materialized `a`.
5. **Column-alias list** (`WITH c(x, y) AS (...)`). The rename `LogicalProject`
   is part of the body, so the MV's schema carries the aliased names — the
   bound consumer column indices already match (they were bound against the
   CTE output schema at build time). Invariant: `MV.schema == body.outputSchema`
   exactly, so swapping body→MV-scan never shifts a column index.
6. **CTE in both `UNION` arms.** References in each arm count toward the same
   pending view; materialized once, both arms scan the MV.
7. **Nested `WITH`** (a CTE body that itself has a `WITH`). Post-order emission
   handles it: inner defs resolve first. **Decision to confirm:** support this
   in v1, or restrict materialization to the outermost scope and inline nested
   CTEs (documented limitation). The post-order formulation makes full support
   only marginally harder; default to supporting it but de-scope if the builder
   change balloons.
8. **Non-deterministic body, `refs>=2`.** Materialization makes all references
   consistent (the fence benefit). `refs==1` stays inlined — already a single
   evaluation, so already consistent.
9. **CTE shadowing a catalog view.** Scope is still checked before the catalog;
   the shadowing CTE materializes like any other. Unchanged precedence.
10. **Empty CTE result.** MV with zero rows across its partitions; scans yield
    nothing. Fine.
11. **Scheduler nesting.** `materialize()` submits to the shared `Scheduler`
    pool, and the main query later submits to it too — the job runner already
    nests this (eager input materialization, then query execution), so the
    pattern is proven. No new pool, no nested-`invokeAll` deadlock as long as
    materialization fully completes before the main execute (it does — `resolve`
    is synchronous).

## Testing

### Correctness (regression)
- **All existing `SqlEngineTest` CTE cases must produce identical output** with
  the new pass active. Materialization must be result-invisible.
- The single-ref and zero-ref paths stay byte-identical plans (Phase 1 lands
  the inline-only resolver first to lock this in before materialization exists).

### The key new test — "compute once"
- A counting `CatalogView` wrapper that increments an `AtomicInteger` per
  `readPartition`. Register it as the CTE's underlying source. Run
  `WITH c AS (SELECT ... FROM counted) SELECT ... FROM c x JOIN c y JOIN c z`
  (3 refs). Assert the underlying source was read **once per partition, not 3×**,
  and the result equals the inlined-equivalent query.
- Negative control: the same source under a **single-reference** CTE is read
  exactly once and the plan is the inline shape (assert via a planner-level
  check that no `MaterializedView` scan was introduced — e.g. resolve a
  `BuiltQuery` and inspect the resolved plan).

### Behavioural
- Dependent CTEs both `refs>=2`: each underlying source read once; `b` sees
  materialized `a`.
- `UNION` arms sharing a CTE: source read once.
- Nested `WITH` (if in scope for v1): inner CTE materialized/computed once.
- **Determinism fence**: `WITH c AS (SELECT RAND() AS r, id FROM t) SELECT
  x.r = y.r AS same FROM c x JOIN c y ON x.id = y.id` — every `same` is true
  with materialization (under inlining it would generally be false). Lock the
  semantics in.
- Empty CTE referenced twice → empty result, source read once.
- `SELECT COUNT(*) FROM c` where `c` is materialized → metadata fast-path
  returns the MV row count without a scan.

### End-to-end (the CLAUDE.md gates)
- `bazel test //...` green.
- jaffle_shop deploy jar: 15/15 Succeeded (the in-query CTE there is single-ref
  → inlined → unchanged).
- polymarket deploy jar: 15/1/1 unchanged.

### Performance
- Microbench (or the plan 11 harness): a CTE with an aggregate body referenced
  2× and 4×, materialized vs inlined — wall time should drop toward
  `1×body + N×(cheap replay)` instead of `N×body`. Numbers belong in the PR
  description, not the docs.

## Risks

1. **Builder refactor ripple.** Changing `build`'s return type and threading
   defs through nested scopes is the riskiest part. Mitigation: Phase 1 ships
   the new structure with an **inline-only** resolver (no materialization) and
   must pass every existing CTE test byte-identically before any materialization
   lands. Keep `BuiltQuery.ctes` empty for non-CTE queries so the no-CTE path is
   provably unchanged.
2. **Memory.** Materializing a large body into heap can OOM. Mitigation:
   document the heap cost; offer the size-cap policy (§5 Option B) or
   spill-backed materialization (plan 09) as a follow-up; default conservative
   on the cap decision (see "decisions to confirm").
3. **Index/schema drift on substitution.** A wrong `withChildren` reconstruction
   could mis-shape the plan. Mitigation: assert `MV.schema == body.outputSchema`
   in `materialize`; add a debug assertion in `substitute` that the rewritten
   node's `outputSchema` is unchanged (replacement preserves schema by
   construction).
4. **Double execution under metrics.** The measured path must not count CTE
   body work twice or drop it. Mitigation: one explicit `cteMaterializeNanos`
   phase; bodies run unmeasured.
5. **Interaction with plan 04 / plan 09.** A materialized CTE feeds a normal
   tree, so sharded downstream plans and spill are orthogonal. Spill-backed
   materialization is an explicit future tie-in, not a dependency.

## Suggested phases

1. **Phase 1 — structure, no materialization.** Add `PendingCteView`,
   `CteDef`/`BuiltQuery`, `LogicalPlan.withChildren`; builder emits pending-view
   scans and returns `BuiltQuery`; `CteResolver.resolve` **inlines every CTE**
   (substitute body at each site, regardless of count). Wire into `SqlEngine`.
   Gate: all existing tests + both e2e examples pass byte-identically. This
   de-risks the refactor in isolation.
2. **Phase 2 — materialize `refs>=2`.** Add ref-counting, `PhysicalPlanView`,
   the `materialize` path, and the compute-once + negative-control tests. Gate:
   tests + e2e.
3. **Phase 3 — metrics + fence.** Add `cteMaterializeNanos`; add the
   determinism-fence test and COUNT(\*)-fast-path test.
4. **Phase 4 — refinements (optional).** Used-column projection before
   materialize; parallel independent materialization; size-cap policy. Land only
   if profiling or a real workload justifies each.

## Decisions to confirm before/while implementing

- **Nested-`WITH` materialization**: support in v1 (recommended, post-order
  makes it cheap) or restrict to the outermost scope and document?
- **Size-cap policy**: always materialize at `refs>=2` (recommended) or gate on
  a cardinality/heap budget?
- **Single-ref non-deterministic fence**: leave single-ref CTEs inlined
  (recommended — already single-eval) or force-materialize for strict fence
  semantics (a behavioural change)?

## Docs to update

- `README.md` — replace the "CTEs are inlined ... recomputed N times" caveat
  in the "SQL features" section with "multiply-referenced CTEs are materialized
  once; single-use CTEs are inlined".
- `docs/gotchas.md` — update the CTE bullet under "What's intentionally NOT
  done": inlining is no longer unconditional; note the heap cost of
  materialization and the `refs>=2` policy.
- `docs/architecture.md` §6b — describe the resolve/materialize pass, the
  pending-view mechanism, the `refs>=2` policy, and the `MV.schema ==
  body.outputSchema` invariant.
- `docs/testing.md` — the new `CteMaterializationTest` target and what it
  covers.
- `docs/code-map.md` — `LogicalBuilder` growth + the new `CteResolver` /
  `PendingCteView` files.
- `docs/extending.md` — the "Add a SQL operator" recipe already mentions CTE
  inlining; note materialization as the shared-result option.
- `plans/perf/README.md` — index row + launch prompt (added with this plan).

## Launch prompt

```
Read plans/perf/12-cte-materialization.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy SQL deps (JSqlParser is AST-only;
reuse MaterializedView, do NOT add an embedded DB), bazel test //... must pass,
jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy jar must hit
15/1/1. Land docs in the same PR per CLAUDE.md "Required workflow".

Follow the 4-phase plan. Phase 1 (structure, inline-only resolver) MUST pass
every existing CTE test byte-identically before you add any materialization —
land it as its own commit. Phase 2 (materialize refs>=2) is the landmark; its
compute-once test (a counting CatalogView asserting the source is read once
under an N-reference CTE) is the proof the feature works.

Reuse MaterializedView.materializeInParallel via a small PhysicalPlanView
adapter. Do NOT add a new sealed LogicalPlan subtype — resolve every CTE
reference to either an inlined body or a LogicalScan over a MaterializedView
before the optimizer/physical-planner run, so their exhaustive matches stay
untouched.

Spawn a parallel sub-agent for: writing the counting-view compute-once and
determinism-fence tests while the main agent implements the resolver.

Stop and ask before: (a) the three "decisions to confirm" in the plan
(nested-WITH scope, size-cap policy, single-ref fence), (b) adding spill-backed
materialization (that's a plan 09 tie-in, defer), (c) changing the public
shape of CatalogView or PhysicalPlan, (d) introducing a generic optimizer-rule
framework — keep withChildren a plain structural primitive.

Include in PR description: the compute-once test result, jaffle + polymarket
e2e status, and wall-time before/after on a 2x- and 4x-referenced aggregate CTE.
```
