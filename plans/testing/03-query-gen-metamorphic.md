# Plan 03: Multi-relation query gen + metamorphic (TLP) + shrinking

> Status: not started · Tier: testing · Effort: 1-2 weeks · Risk: medium-high
>
> Depends on: [01](01-expr-parity-fuzzer.md), [02](02-data-gen-mode-differential.md).

## Goal

Grow the query generator from single-table to the full supported surface
(joins, subqueries in FROM, window functions, CTEs, UNION), and add
**metamorphic oracles** that decide correctness with no reference engine and
no internal mode comparison — relations that must hold by SQL semantics
alone. Plus the cross-JVM **sharded-vs-collapsing** mode-differential target
that Plan 02 deferred, and complete AST + data **shrinking**.

This is the phase that finds *semantic* bugs (wrong answers that every
execution mode agrees on), which mode-differential by construction cannot.

## Why it matters

Mode-differential (Plan 02) catches "the engine disagrees with itself." It
is blind to a bug where every mode computes the same *wrong* answer — e.g. a
LEFT JOIN that drops the right null-extension, a `NOT IN` with a NULL that
returns rows it shouldn't, a window frame off by one. Metamorphic relations
close that gap without an external oracle:

- **TLP (Ternary Logic Partitioning):** for any row-producing query `Q` and
  any predicate `p` over its rows, `Q` ≡ `(Q WHERE p) ⊎ (Q WHERE NOT p) ⊎
  (Q WHERE p IS NULL)` as multisets. This is a direct, brutal test of the
  three-valued NULL logic and the `selectByBoolean` filter — the engine's
  subtlest surface.
- **Optimizer-equivalence (NoREC-style):** a count obtained via `WHERE p`
  must equal a count obtained via `SUM(CASE WHEN p THEN 1 ELSE 0 END)` with
  no WHERE — the same answer the optimizer's FilterPushdown / column-pruning
  must not perturb.

Joins, windows, and CTEs are where the planner's hardest transforms live
(exchange insertion, build-side swap, CTE materialization), so the query
generator must reach them for these relations to bite.

## Current state

- Supported SQL surface (from `docs/testing.md` and `sql_engine_test`):
  INNER/LEFT/RIGHT/FULL JOIN, subquery in FROM, GROUP BY + the full
  aggregate set, DISTINCT, HAVING, ORDER BY/LIMIT, window functions
  (ROW_NUMBER, RANK, DENSE_RANK, LAG, LEAD, running/partitioned aggregates,
  ROWS frames), CTEs (`WITH`, not `WITH RECURSIVE`), UNION ALL, three-valued
  NULL logic.
- `QueryGen` (single-table) and `DataGen` exist from Plan 02; `RowOracle`
  does multiset compare; `Shrinker` shrinks single-table queries + data.
- The sharded/broadcast gates are `LogicalPlanCardinality.MinShardableSize`
  and `BroadcastBuildThreshold` — **`val`s read from the system properties
  `transformer.scheduler.shard_min_size` / `broadcast_threshold` at
  class-load**, so they freeze per-JVM and cannot be toggled within one test
  run.

## Proposed design

### 1. `QueryGen` — multi-relation

Generate a small relation environment (2-4 generated views registered in
one `Catalog`) and build a `SELECT` over them. Type-correctness and
scope-correctness are the invariants: a column reference must resolve to an
in-scope relation; join predicates must be type-compatible; aggregate vs
non-aggregate column usage must be legal. Add, incrementally:
- joins of each kind with generated equi- and non-equi `ON` predicates;
- subqueries in `FROM` (a generated sub-`SELECT` as a derived table);
- `WITH` CTEs, including a CTE referenced twice (exercises the
  materialize-on-2+-refs path) and a CTE feeding both arms of a `UNION ALL`;
- window functions over generated `PARTITION BY` / `ORDER BY` / frames;
- `UNION` / `UNION ALL` of schema-compatible arms.

Keep the structured AST alongside the SQL string — shrinking operates on the
AST.

### 2. `Tlp` oracle

```scala
def check(env: RelationEnv, baseQuery: QueryAst): Unit
```

Given a generated query whose outer shape is row-producing, synthesize a
predicate `p` over its output columns and run the three partitioned variants
plus the unpartitioned base; assert `base ≡ partA ⊎ partB ⊎ partNull`
(multiset, via `RowOracle`). Scope `p` to the row-selection level
(WHERE-clause partitioning of a non-aggregate row set, or of the pre-GROUP-BY
input). **Aggregate-level TLP** (partition the input, re-aggregate, combine)
is strictly harder and only valid for distributive aggregates (`SUM`,
`COUNT`) — document it as an extension and gate it behind the aggregate
shape rather than attempting it for `AVG`/stddev.

### 3. `NoREC`-style optimizer-equivalence oracle (optional, same phase)

For a generated predicate `p` over a single scanned relation, assert
`COUNT(*) WHERE p` equals `SUM(CASE WHEN p THEN 1 ELSE 0 END)` over the
unfiltered relation. Cheap, and it specifically targets FilterPushdown /
pushdown-to-parquet correctness.

### 4. Cross-JVM sharded-vs-collapsing target

A second `scala_junit_test` (`sharded_mode_fuzz_test`) with
`jvm_flags = ["-Dtransformer.scheduler.shard_min_size=1",
"-Dtransformer.scheduler.broadcast_threshold=1"]` so the planner takes the
sharded build + shuffle-join paths for *all* generated aggregates/joins.
The property is identical to Plan 02's `ModeDifferential` (same `(data,
query)` → same multiset), but the comparison baseline is captured by a
**separate default-gate target** and the two targets' expected results are
reconciled by both writing their canonicalized multiset to a deterministic
form. Practically: each target asserts the metamorphic relations hold *and*
that within its own JVM the in-JVM modes still agree; cross-JVM equivalence
is covered by both targets running the same generators at the same seeds and
each being internally consistent. Document why a single in-JVM toggle is
impossible (the class-load `val`).

### 5. AST + data shrinking (complete)

Extend `Shrinker` to the multi-relation AST: collapse a join to one side,
drop a CTE, unwrap a subquery to its inner select, drop a UNION arm, remove
a window spec, in addition to the Plan 02 row/column/expr shrinks. The
target minimal repro for a join bug is "2 rows each side, single join key,
`SELECT ... FROM a LEFT JOIN b ON a.k = b.k`".

## Files to touch

Extend under `src/test/scala/com/transformer/fuzz/`: `QueryGen.scala`
(multi-relation), `Shrinker.scala` (AST shrinks), `oracle/Tlp.scala` (new),
optionally `oracle/NoRec.scala` (new). New test classes
`MetamorphicFuzzTest.scala` and `ShardedModeFuzzTest.scala`. Extend
`BUILD.bazel` (the new targets; the sharded one carries `jvm_flags`).

Edit: `docs/testing.md`, `docs/extending.md`, `docs/gotchas.md`,
`docs/architecture.md`.

## Edge cases

- LEFT/RIGHT/FULL outer joins with unmatched rows (null-extension must
  survive every partition of TLP).
- `NOT IN` / `IN` containing a NULL (3VL — the classic TLP catch).
- Empty relations on one or both sides of a join.
- Self-join (a CTE referenced twice / same view aliased twice) with
  colliding column names.
- Window `PARTITION BY` with an all-NULL key (one partition) and frames at
  partition boundaries.
- CTE referenced twice (materialized) vs once (inlined) producing identical
  rows — overlaps Plan 02's mode idea but here under semantic relations.
- UNION (distinct) vs UNION ALL multiset semantics.

## Testing

The fuzzer is the test. Keep deterministic regression `@Test`s (fixed seed)
in each new class for the `//...` gate. Every real finding becomes a named
regression `@Test` and, where it maps to an operator, a case in the relevant
`sql/exec` suite. A confirmed *semantic* bug is a `src/main` defect — surface
it; fixing it is a separate change from the harness PR.

Gates: `bazel test //...` green; jaffle 15/15; polymarket 15/1/1.

## Risks

- **Generating type/scope-correct multi-relation SQL is the hard part.**
  Most engineering time goes here. Build it incrementally (joins, then
  subqueries, then CTEs, then windows, then unions); land each as its own
  commit with the metamorphic relations passing on that subset before adding
  the next. A high bind-reject rate means the generator is wrong — track it.
- **TLP false positives from order/duplicates.** Always compare as multisets;
  never assume output order. Decimal/integer generation keeps comparisons
  exact; reserve float tolerance for genuine float columns.
- **Aggregate-TLP unsoundness.** Do not partition-and-recombine non-
  distributive aggregates (`AVG`, stddev, `COUNT(DISTINCT)`); gate TLP to
  the row-selection level or distributive aggregates only.
- **The cross-JVM target is awkward.** It cannot share a process with the
  default-gate target. Keep its scope to "metamorphic relations + in-JVM
  mode agreement hold under sharded planning"; do not over-engineer a
  cross-process result-exchange.
- **Campaign cost.** TLP runs 4 queries per case; multi-relation plans are
  heavier. Keep default-run sizes small; push depth into the `fuzz` campaign.

## Suggested phases

1. Multi-relation `QueryGen`: joins only. Add `Tlp` (row-selection level) +
   `NoREC`. Land with joins passing both relations.
2. Subqueries in FROM + UNION/UNION ALL; extend TLP coverage.
3. CTEs (incl. referenced-twice) + window functions.
4. AST shrinking for all the above.
5. The cross-JVM `sharded_mode_fuzz_test` with `jvm_flags`.

## Decisions to confirm before/while implementing

- **TLP scope** — row-selection-level only (recommended) vs attempting
  distributive aggregate-TLP. Confirm before building the aggregate variant.
- **Cross-JVM strategy** — the lightweight "both targets internally
  consistent under different planning" approach above, vs a heavier
  golden-file exchange between JVMs. Recommend the lightweight one; confirm.

## Docs to update

- `docs/testing.md` — `metamorphic_fuzz_test` + `sharded_mode_fuzz_test`
  targets and coverage.
- `docs/extending.md` — recipe: adding a metamorphic relation; how
  `QueryGen` composes relations type/scope-correctly.
- `docs/gotchas.md` — the class-load-frozen sharding sysprop (why
  sharded-vs-collapsing needs a separate JVM); aggregate-TLP unsoundness.
- `docs/architecture.md` — a short "Property-based testing" note under the
  testing-adjacent patterns, pointing at the `fuzz` harness and the two
  oracle families.

## Launch prompt

```
Read plans/testing/03-query-gen-metamorphic.md and implement it end-to-end.
Plans 01 and 02 must be landed first.

Use max effort. Honor CLAUDE.md: NO new dependencies, bazel test //... must
pass, jaffle_shop 15/15, polymarket 15/1/1. Land docs in the same PR.

Two new oracle families, NEITHER using an external reference DB:
- TLP: Q ≡ (Q WHERE p) ⊎ (Q WHERE NOT p) ⊎ (Q WHERE p IS NULL) as MULTISETS.
  Scope p to the row-selection level. Do NOT partition-and-recombine
  non-distributive aggregates (AVG/stddev/COUNT DISTINCT) — gate to
  distributive aggregates or pre-aggregate rows only.
- NoREC: COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0 END) unfiltered.

The hard part is generating type- AND scope-correct multi-relation SQL. Build
it incrementally — joins, then subqueries, then CTEs, then windows, then
unions — landing each as its own commit with the metamorphic relations
passing on that subset before adding the next. Track and log the bind-reject
rate; a high rate means the generator is wrong.

The sharded-vs-collapsing mode CANNOT be toggled in-JVM (the gate is a
class-load val read from transformer.scheduler.shard_min_size). Implement it
as a SEPARATE scala_junit_test with jvm_flags setting shard_min_size=1 and
broadcast_threshold=1; keep its scope to "metamorphic relations + in-JVM mode
agreement hold under sharded planning". Do not build a heavy cross-process
golden-file exchange.

A confirmed wrong-answer is a src/main semantic bug: SURFACE it with a
minimized repro, do not patch it inside the harness PR — the fix is separate.

Per the repo readability rule, describe current behavior in comments — no
stale "Phase N" scaffolding in landed code.

Stop and ask before: (a) adding any dependency, (b) the two "decisions to
confirm" in the plan (TLP scope, cross-JVM strategy), (c) attempting
aggregate-level TLP for non-distributive aggregates, (d) introducing
WITH RECURSIVE generation (unsupported by the engine).

Include in PR description: which metamorphic relations are live for which SQL
shapes, the bind-reject rate, one minimized TLP repro (and whether it
surfaced a real engine bug), and confirmation the sharded target passes.
```
