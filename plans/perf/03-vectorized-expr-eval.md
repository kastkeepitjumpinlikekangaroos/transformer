# Plan 03: Vectorized expression evaluation

> Status: **partial — Phases 1-4 landed in main; Phases 0/5/6/7 remain** · Tier: 1 (structural) · Remaining effort: 1-3 weeks · Risk: medium

## TL;DR

The expression-side foundation of `evalVec` is shipped: the trait method
exists with a default boxed-loop fallback, and the high-frequency Expr
subtypes (`LitExpr`, `ColRefExpr`, `CastExpr`, `UnaryOpExpr`,
`BinOpExpr`, `IsNullExpr`) override it via a `VecOps` helper. `FilterExec`
and `ProjectExec` use `evalVec` in their hot loops.

**What's still missing** is the part that actually moves the needle on
agg- and join-heavy workloads:
1. **No parity tests reference `evalVec`** — every existing override is
   only validated indirectly through `SqlEngineTest`.
2. **`FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr` have no
   `evalVec` override** — they fall through to the boxed default.
3. **No breaker uses `evalVec` for key extraction.** `HashAggregateExec`,
   `HashJoinExec`, `WindowExec`, and `SortExec` still loop `eval(batch,
   row)` row-by-row, so Filter/Project see a vectorized world but the
   downstream breaker doesn't.

Result: the API spec from this plan is implemented, but the performance
promise is not yet realized. The remaining work is the higher-impact
half. **Do Phase 0 first** — without parity tests every new override is
a correctness gamble.

## Why it matters (re-stated)

`Expr.eval(batch, row): Any` returns boxed `Any`. For arithmetic on
`Long`/`Double`, every operation allocates a `java.lang.Long` /
`java.lang.Double` and pays dynamic dispatch inside `BinOpExpr`. For a
27M-row Polymarket scan with a 3-column projection containing 5
arithmetic ops, that's ~400M box allocations and ~400M virtual calls
per query — *if* the operator uses the row path.

The wins from Phases 1-4 already apply to `FilterExec` and
`ProjectExec`. The remaining wins (Phases 5-7) apply to GROUP BY key
extraction, JOIN key extraction, WINDOW partition/order keys, and SORT
comparator — which dominate after Filter/Project on agg- and join-heavy
plans.

## Status: what's already in the codebase

### Trait (`src/main/scala/com/transformer/sql/plan/Expr.scala:16-42`)

```scala
sealed trait Expr {
  def dataType: DataType
  def eval(batch: ColumnarBatch, row: Int): Any
  def evalVec(batch: ColumnarBatch): ColumnVector = {
    val n = batch.numRows
    val out = ColumnVector.allocate(dataType, math.max(1, n))
    var i = 0
    while (i < n) {
      val v = eval(batch, i)
      if (v == null) out.setNull(i) else out.setBoxed(i, v)
      i += 1
    }
    out
  }
}
```

The default impl is exactly what Phase 1 specified: per-row loop into
the allocated output vector. Subtypes override for speed; unoverridden
subtypes are correct but slow.

### Per-subtype overrides (status)

| Expr subtype           | `evalVec` override | Location              |
|------------------------|--------------------|-----------------------|
| `LitExpr`              | ✅ done — primitive `Arrays.fill` broadcast | `Expr.scala:50-90` |
| `ColRefExpr`           | ✅ done — zero-copy column reference | `Expr.scala:100` |
| `CastExpr`             | ✅ done — `VecOps.cast` | `Expr.scala:110-113` |
| `UnaryOpExpr`          | ✅ done — `+`, `-`, `NOT` | `Expr.scala:135-143` |
| `BinOpExpr`            | ✅ done — `AND`, `OR`, `\|\|`, all comparisons, `+`/`-`/`*`/`/`/`%` | `Expr.scala:175-187` |
| `IsNullExpr`           | ✅ done — `VecOps.isNull` | `Expr.scala:218-219` |
| `FuncExpr`             | ❌ row path only | `Expr.scala:190-195` |
| `CaseExpr`             | ❌ row path only | `Expr.scala:198-208` |
| `InListExpr`           | ❌ row path only | `Expr.scala:223-239` |
| `LikeExpr`             | ❌ row path only | `Expr.scala:241-253` |

### `VecOps` (`src/main/scala/com/transformer/sql/plan/Ops.scala:152-...`)

Helper surface that the overrides delegate to. Already implemented:
`arith`, `compare`, `concat`, `and`, `or`, `negate`, `not`, `isNull`,
`cast`. Plus private helpers per primitive type (`longArith`,
`intArith`, `doubleArith`, `floatArith`, `numericCompare`,
`genericCompare`).

### Operator adoption

| Operator              | Uses `evalVec`? | Row-path call sites still present |
|-----------------------|-----------------|-----------------------------------|
| `FilterExec`          | ✅ yes — predicate | `PhysicalPlan.scala:50` |
| `ProjectExec`         | ✅ yes — per projection | `PhysicalPlan.scala:82` |
| `HashAggregateExec`   | ❌ no | GROUP BY key extraction `AggregateExec.scala:58`; every `AggState.update` body — lines 157, 166, 176, 194, 208, 223, 241-242, 279, 322-323, 366-367 |
| `HashJoinExec`        | ❌ no | build-side key `JoinExec.scala:98`; probe-side key `:154`; residual extra predicate `:257` |
| `WindowExec`          | ❌ no | partition key `WindowExec.scala:85`; order-key comparator `:110-112`; LAG/LEAD arg `:198, 202`; order-key collection `:289` |
| `SortExec`            | ❌ no | comparator `SortExec.scala:170-171` |
| `DistinctExec`        | n/a — reads columns via `getBoxed` directly; no Expr eval |

### Test coverage

`grep -rln evalVec src/test/` returns nothing. **No file in the test
tree references `evalVec`.** Existing overrides are validated only
indirectly through whatever `SqlEngineTest` cases happen to exercise
`FilterExec` / `ProjectExec` end-to-end. This is the largest unflagged
risk in the codebase right now.

## What's still missing

### Gap 1 — Parity tests (urgent)

There is no `ExprBatchTest` or equivalent. The plan from day one called
for `evalVec(b)(i) == eval(b, i)` parity tests per subtype, per NULL
configuration, per boundary value. None exist. **Land this before any
further override**, or the next override silently corrupts results.

### Gap 2 — `FuncExpr.evalVec`

`FuncExpr.eval` (`Expr.scala:191-194`) dispatches to `Funcs.apply` per
row. Hot functions in the codebase's example workloads include
`DATE_TRUNC`, `COALESCE`, `CONCAT`, numeric (`ABS`, `FLOOR`, `CEIL`,
`ROUND`), and string (`LOWER`, `UPPER`, `LENGTH`, `TRIM`). Jaffle Shop
uses `DATE_TRUNC` and `COALESCE` heavily. Polymarket's
`stg_orderbook` likely uses date functions on the timestamp column.

The override should:
- Switch on `name` once outside the row loop.
- For each known function, evaluate args once per batch (each arg
  yields a `ColumnVector`) and produce the result vector with a
  monomorphic inner loop.
- For unknown functions, fall back to the default per-row loop.

### Gap 3 — `CaseExpr.evalVec`

`CaseExpr.eval` (`Expr.scala:199-207`) evaluates branches lazily per
row. Vectorized version:
- Evaluate every branch's predicate to a `BooleanVector` mask.
- Evaluate every branch's value vector eagerly (this is the trade-off:
  branches may do work that the row path skipped; acceptable for
  side-effect-free Exprs which is all we have).
- Walk masks in order to fill the output vector; use the else branch
  (or NULL) where no mask matches.

### Gap 4 — `InListExpr.evalVec`

`InListExpr.eval` (`Expr.scala:225-238`) walks the items list per row.
Vectorized version:
- If all items are literals, pre-build a `HashSet[Any]` (or typed set)
  once outside the row loop; inner loop is `set.contains(value)`.
- Otherwise, evaluate each item vector once per batch; inner loop is
  per-row scan but stays primitive.
- Propagate NULLs per SQL `IN` semantics (NULL in items → result NULL
  for non-matching values).

### Gap 5 — `LikeExpr.evalVec`

`LikeExpr.eval` (`Expr.scala:243-253`) already caches compiled regex
patterns globally (`LikeExpr.cache` at `Expr.scala:256`). Vectorized
version:
- Hoist the pattern compile outside the row loop (today it's once per
  row through the cache lookup — fast but unnecessary).
- Inner loop applies the precompiled `Matcher` per row.
- When the pattern is a literal `LitExpr`, compile once at plan time
  (or first eval).

### Gap 6 — Breaker key extraction

The big one. After Phases 1-4, `FilterExec` and `ProjectExec` hand a
fully-decoded `ColumnarBatch` to the next operator. If that next
operator is `HashAggregateExec` and the GROUP BY expression is
`market_id` (a `ColRefExpr`), the current code at
`AggregateExec.scala:58` does:

```scala
val key: Seq[Any] = groupKeys.map { case (e, _) => e.eval(batch, r) }
```

That's `e.eval(batch, r): Any` — boxed, row-by-row, even though
`ColRefExpr.evalVec` returns the underlying column zero-copy. The
batch just got vectorized for nothing on the way into the breaker.

The fix is to call `evalVec` **once per batch per group-key**, then read
the per-row primitive in the inner loop:

```scala
val keyVecs: Array[ColumnVector] = groupKeys.map(_._1.evalVec(batch)).toArray
var r = 0
while (r < batch.numRows) {
  val key = codec.encode(keyVecs, r)   // coordinate with plan 02
  ...
}
```

The same pattern applies to:
- `JoinExec.scala:98` (build-side key extraction)
- `JoinExec.scala:154` (probe-side key extraction)
- `WindowExec.scala:85` (partition key)
- `WindowExec.scala:289` (order-key collection)

For `SortExec.scala:170-171` (comparator) and `WindowExec.scala:110-112`
(order-key comparator), pre-computing sort-key columns once per batch
during the buffer build is the right shape — touched on in plan 01 Phase 3.

For `AggState.update` (10+ call sites in `AggregateExec.scala`), the
update function takes a single `(batch, row)` pair. Vectorizing this
means changing the `AggState` API to `updateVec(agg, vec, nullMask, n)`
where supported, falling back to per-row otherwise. SUM, AVG,
COUNT, COUNT_IF, MIN, MAX over primitives all vectorize cleanly.
Moment / Covar / Corr states are stateful per-row but still benefit
from the input vector being read primitive-per-row instead of boxed.

For `JoinExec.scala:257` (residual extra predicate over a single
joined row), this is the `RowBuf`-backed path; per-row eval is
fundamental. Leave on the row path.

## Proposed design

### Phase 0 — Parity tests (do this first)

Create `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala`.

For every Expr subtype that overrides `evalVec`, plus a representative
sample of inputs:
- Build a `ColumnarBatch` of N=64 rows with mixed NULL placements
  (no NULL, every-other NULL, all NULL).
- Compute `eval(batch, i)` for `i in [0, N)` and accumulate into a
  reference `Array[Any]`.
- Compute `vec = evalVec(batch)`.
- Assert `(vec.isNull(i) && refArr(i) == null) || (vec.getBoxed(i) == refArr(i))` for every `i`.

Cover at minimum:
- Each primitive type (Int, Long, Float, Double, Boolean, String).
- `LitExpr` of each type, including NULL literal.
- `ColRefExpr` to each column type, with and without NULLs in the
  source column.
- `CastExpr` between every supported pair.
- `UnaryOpExpr`: `+`, `-`, `NOT`.
- `BinOpExpr`: `AND`/`OR` truth table including 3VL, `||` (string
  concat) with NULL handling, every comparison op, every arithmetic op
  with both sides NULL, one side NULL, neither side NULL.
- `IsNullExpr`: with all-null / no-null / mixed-null input.

Once Phase 0 lands, every subsequent override adds its own subtype
parity case to the same suite — there's now a pattern to follow.

### Phases 5-6 — Remaining Expr overrides

Land in this order, **each gated on Phase 0 expanded to cover the
new subtype before merging**:

5a. `FuncExpr.evalVec` — switch on `name`; implement vectorized paths
    for the hot functions (`DATE_TRUNC`, `COALESCE`, `ABS`, `FLOOR`,
    `CEIL`, `ROUND`, `LOWER`, `UPPER`, `LENGTH`, `TRIM`, `CONCAT`).
    Add VecOps helpers as needed (`VecOps.coalesce`, `VecOps.abs`,
    etc.). For unknown functions, the default boxed loop fires —
    correct, just slow.

5b. `CaseExpr.evalVec` — evaluate every branch eagerly into vectors;
    walk masks in order.

5c. `InListExpr.evalVec` — literal-items fast path with a precomputed
    `HashSet`; column-items general path.

5d. `LikeExpr.evalVec` — hoist pattern compile out of the row loop;
    monomorphic inner loop.

### Phase 7 — Operator integration

Three sub-PRs, in this order:

7a. **`HashAggregateExec`**. Compute `keyVecs: Array[ColumnVector]`
    once per batch in `partialAggregate` (`AggregateExec.scala:50-71`).
    Per-row key materialization stays — that's plan 02's job (packed
    keys consuming the column vectors).

    Also: extend the `AggState` API with a default `updateVec` that
    falls through to the row path. Override on primitive paths
    (LongSumState, DoubleSumState, AvgState, CountState, CountStarState,
    CountIfState, MinMaxState) for column-at-a-time updates over the
    arg vector + null mask. Moment/Covar/Corr states still read primitive
    rows from the arg vectors instead of boxed `eval` results.

7b. **`HashJoinExec`**. Build-side: compute `keyVecs` per build batch
    in `collectPartition` / `buildSide` (`JoinExec.scala:52-100`).
    Probe-side: same in the per-partition probe loop (`JoinExec.scala:104-160`).
    Coordinate with plan 02's codec.

7c. **`WindowExec`**. Compute partition keys + order keys per batch as
    rows stream in. The current code at `WindowExec.scala:33-48`
    already buffers `Array[Any]` rows; the key cache should be
    `Array[Array[Any]]` of pre-extracted keys, sized to row count.

`SortExec`'s comparator pre-computation belongs to plan 01 Phase 3; do
not duplicate here. Leave `JoinExec.scala:257` (residual predicate)
on the row path.

### Phase 8 — Intermediate vector pooling (deferred)

Unchanged from the original plan: only land if async-profiler shows
`ColumnVector.allocate` in the flame graph above ~3% of CPU. Most
workloads won't reach this.

## Files to touch

### Existing files (Phase 0, expand as Phases 5-7 land)
- **New**: `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala`
- **New (maybe)**: `src/test/scala/com/transformer/sql/plan/BUILD.bazel` test target if not already there. Verify; `LogicalPlanCardinalityTest.scala` is already in this dir so the BUILD probably exists.

### Phase 5
- **Modified**: `src/main/scala/com/transformer/sql/plan/Expr.scala` — new
  `evalVec` overrides on `FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr`.
- **Modified**: `src/main/scala/com/transformer/sql/plan/Ops.scala` — new
  `VecOps` helpers (`coalesce`, `abs`, string ops, `dateTrunc`, etc.).
- **Modified**: `ExprBatchTest.scala` — parity cases for each new override.

### Phase 7
- **Modified**: `src/main/scala/com/transformer/sql/exec/AggregateExec.scala`
  — vectorized key extraction + `AggState.updateVec`.
- **Modified**: `src/main/scala/com/transformer/sql/exec/JoinExec.scala` —
  vectorized build- + probe-side key extraction.
- **Modified**: `src/main/scala/com/transformer/sql/exec/WindowExec.scala`
  — vectorized partition/order key extraction.
- **Existing tests**: every operator's test file may need additional
  parity cases. `SqlEngineTest` will catch most regressions.

## Edge cases (mostly unchanged from original)

1. **NULL propagation**. Already handled correctly in the existing
   `VecOps.arith` / `VecOps.compare` / `VecOps.and` / `VecOps.or`.
   New overrides (Func, Case, InList, Like) must match `eval`'s NULL
   semantics. Verify via Phase 0 parity tests.
2. **Divide by zero**. `VecOps.arith` for `/` and `%` should mark
   the null bit per row when divisor is zero, matching `eval` returning
   NULL. Check whether the current `longArith` / `intArith` /
   `doubleArith` / `floatArith` already do this — if not, fix as a
   correctness bug surfaced during Phase 0.
3. **Integer overflow**. Existing `eval` allows silent wraparound on
   Long; preserve in vectorized path.
4. **String comparison** is lexicographic; existing `genericCompare` in
   `Ops.scala` covers it. No SIMD, but no per-element dispatch either.
5. **CASE with constant predicates**. Eager evaluation of every branch
   does extra work the row path skipped. Acceptable for side-effect-
   free Exprs (all of them today). If `FuncExpr` ever becomes
   side-effectful, revisit.
6. **`COALESCE(a, b, c)`** is vectorizable as `merge(a, b, c)` over null
   masks. Implement as a `VecOps.coalesce(vecs: ColumnVector*)`.
7. **Window function aggregate args** (`WindowExec.computeAggOverPartition`
   at `WindowExec.scala:210-256`). The current code is per-row through
   `RowBuf`. Vectorized AggState.updateVec helps once the buffer is
   materialized per partition — but the partition itself is built
   row-by-row from the global `rows` ArrayBuffer. Plan 04 (sharding)
   changes this; treat Window vectorization as a smaller follow-up.
8. **`LIKE` with column patterns** — rare. Compile per row only when
   needed; the existing cache helps. Document as slow path.
9. **`InListExpr` with one item** — degenerate; reduce to `=` at plan
   time? Out of scope; the general path handles it correctly.
10. **Decimal columns**. Boxed `BigDecimal` regardless. `evalVec`
    default fallback fires correctly. Don't add Decimal fast paths.

## Testing

### Phase 0 (gate every later phase on this)
- `ExprBatchTest` covers every existing override + a representative
  matrix of inputs (see above).
- `bazel test //src/test/scala/com/transformer/sql/plan:plan_test`
  (or whatever target hosts it) green.

### Phases 5-6 (per-override)
- Add parity case to `ExprBatchTest` for the new subtype.
- Add `SqlEngineTest` case that exercises it end-to-end (jaffle uses
  `DATE_TRUNC`, `COALESCE`, `CASE WHEN` — wire those up).

### Phase 7 (operator integration)
- All existing `SqlEngineTest` cases pass.
- Add cases that GROUP BY a mix of types (Long, String, mixed),
  JOIN on multi-column keys, WINDOW with multi-column PARTITION BY.

### End-to-end (every phase)
- jaffle_shop deploy jar: 15/15 Succeeded.
- polymarket deploy jar: 15-Succeeded / 1-ValidationFailed / 1-Skipped.

### Performance (Phase 7 measurable target)
- HashAggregate-only wall time on Polymarket `stg_orderbook → int_*`
  tasks: target ≥20% reduction (the GROUP BY hot loop today is boxed
  through `eval`).
- HashJoin wall time on Polymarket `mart_market_summary`: target ≥15%.
- Polymarket end-to-end: target ≥10% reduction on top of Phase 1-4 baseline.
- jaffle_shop end-to-end: target ≥10% (DATE_TRUNC / COALESCE are
  everywhere).

## Risks

1. **Behavioral drift in NULL handling** — the highest risk for new
   overrides. Mitigation: Phase 0 first; every override extends the
   parity test before merging.
2. **CaseExpr eager evaluation** — branches do work the row path
   skipped. Mitigation: every Expr is side-effect-free today; document
   and add an assertion if FuncExpr ever changes that.
3. **`AggState.updateVec` semantics drift** from `update`. Mitigation:
   default `updateVec` falls through to a loop over `update`; only
   override per state type with explicit parity test.
4. **Memory pressure** — intermediate vectors for nested expressions
   (`a + b * c` allocates 3). Plan 8 (pooling) deferred; monitor in profile.
5. **Order of operations: plan 02 (packed keys) interacts with Phase 7.**
   The codec API must accept `Array[ColumnVector]` from `evalVec`, not
   loop `eval` per row. If plan 02 has already landed assuming row
   eval, Phase 7 has to refactor it. Coordinate.
6. **Window vectorization is awkward without plan 04**. Phase 7c gets
   only a modest win on Window until sharding lands. Acceptable; the
   bigger wins are 7a and 7b.

## Suggested phases

Re-numbered to reflect what's done:

- ✅ Phase 1 — API + default impl + Filter/Project switchover (done)
- ✅ Phase 2 — `ColRefExpr`, `LitExpr` overrides (done)
- ✅ Phase 3 — `BinOpExpr` arithmetic + comparison + AND/OR (done)
- ✅ Phase 4 — `UnaryOpExpr`, `CastExpr`, `IsNullExpr` (done)
- 🟡 **Phase 0 — Parity tests (DO THIS FIRST)**
- 🟡 Phase 5 — `FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr` overrides
- 🟡 Phase 7 — Operator integration:
  - 7a. `HashAggregateExec` key extraction + `AggState.updateVec`
  - 7b. `HashJoinExec` build + probe key extraction
  - 7c. `WindowExec` partition + order key extraction
- ⏸ Phase 8 — Intermediate vector pooling (deferred; only if profiles demand)

`SortExec` comparator vectorization is intentionally **omitted** here —
it belongs to plan 01 Phase 3 (k-way merge with pre-computed sort
keys). Don't duplicate.

## Docs to update

- `docs/architecture.md` §5 (Expression evaluation) — note the dual
  `eval` / `evalVec` paths and which operators use which. Confirm the
  current section reflects what's in the code; update if drifted.
- `docs/conventions.md` — pattern for when to override `evalVec` and
  when the default is fine (rule of thumb: override anything that
  shows up in operator hot loops or in batch-eval flame graphs).
- `docs/extending.md` — adding a new Expr subtype means: (a) implement
  `eval`; (b) override `evalVec` if it'll be hot; (c) add a parity
  case to `ExprBatchTest`.
- `docs/gotchas.md` — note that `FuncExpr`/`CaseExpr`/`InListExpr`/`LikeExpr`
  fall back to the boxed default until they get overrides; remove this
  caveat as each override lands.
- `docs/code-map.md` — `VecOps` (in `Ops.scala`), `ExprBatchTest`.
- `docs/testing.md` — `ExprBatchTest` and its target.

## Launch prompt

```
Read plans/perf/03-vectorized-expr-eval.md and finish the remaining work.

CONTEXT: Phases 1-4 of this plan are already landed in main — evalVec
exists on the Expr trait with a default boxed-loop fallback, and the
common Expr subtypes (LitExpr, ColRefExpr, CastExpr, UnaryOpExpr,
BinOpExpr, IsNullExpr) override it via VecOps. FilterExec and ProjectExec
use evalVec. Do NOT re-do that work.

WHAT'S REMAINING (in order):
- Phase 0: parity tests (ExprBatchTest). NONE exist today; this is the
  urgent gap. Land before any new override.
- Phase 5: evalVec overrides on FuncExpr, CaseExpr, InListExpr, LikeExpr.
- Phase 7: operator integration in HashAggregateExec, HashJoinExec,
  WindowExec for batch-at-a-time key extraction (and AggState.updateVec
  for primitive aggregate states).

Use max effort. Honor CLAUDE.md: no new heavy deps, no codegen libs,
bazel test //... must pass at every phase, jaffle_shop and polymarket
deploy jars must pass at every phase. Land docs in the same PRs.

CRITICAL ORDER: Phase 0 first. Without parity tests every new override
is a correctness gamble — the codebase has no test that compares
evalVec(b)(i) against eval(b, i). Build the test infrastructure in
Phase 0 and extend it as each later phase adds an override.

COORDINATION:
- Plan 02 (packed keys): the codec must consume Array[ColumnVector]
  from evalVec. If plan 02 has already landed assuming row eval,
  Phase 7 has to refactor it.
- Plan 01 (k-way merge): SortExec comparator vectorization is plan 01
  Phase 3. Do NOT duplicate here.

Phase boundaries:
- Phase 5 lands as one PR (all four remaining Expr overrides + their
  parity cases).
- Phase 7 lands as three PRs: 7a (HashAggregate), 7b (HashJoin),
  7c (WindowExec).

Spawn parallel sub-agents for: (a) building ExprBatchTest while
implementing Phase 5, (b) profiling polymarket between phases to track
cumulative speedup.

Stop and ask before: (a) changing the Expr trait shape beyond what
exists, (b) modifying ColumnarBatch storage layout, (c) adding
vectorization for SortExec comparator (that's plan 01), (d) adding any
caching or pooling for intermediate ColumnVectors (that's Phase 8,
deferred).

Include in each PR description: parity test status, before/after wall
time on the relevant workload (Phase 5 = jaffle_shop with DATE_TRUNC/
COALESCE; Phase 7a = polymarket stg→int aggregate tasks; Phase 7b =
polymarket mart_* join tasks), async-profiler flame graph confirming
the boxed eval call is gone from the relevant hot loop.
```
