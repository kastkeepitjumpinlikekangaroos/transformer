# Plan 02: DataGen + mode-differential over single-table SQL

> Status: not started · Tier: testing · Effort: 3-5 days · Risk: medium
>
> Depends on: [01](01-expr-parity-fuzzer.md) (shared `Rng`/`Props`/`Shrinker`/`RowOracle`).

## Goal

Generate an in-memory dataset and a random single-table SQL query, then run
the *same* `(data, query)` under every execution mode the engine exposes and
assert the result multisets are identical. This is the generated
generalization of the hand-written `*SpillTest` parity checks (which already
assert "bit-equal output with spill on vs off" on fixed fixtures).

Modes varied:
- **spill** on (1-byte threshold) vs off — per-call via `ExecutionOptions`.
- **metrics** on vs off — per-call via `ExecutionOptions`.
- **partition / batch layout** — the same logical rows rebuilt as a
  `MaterializedView` with a different partition count and different batch
  boundaries (including empty partitions and tiny batches).

(Sharded-vs-collapsing is class-load-frozen and lands as a separate
cross-JVM target in Phase 03 — see its Risks section.)

## Why it matters

The engine has many code paths that are *supposed* to be result-equivalent
but are wildly different implementations: spill vs in-memory, the
`LongHashMap` single-Long fast path vs the codec path, single-partition vs
many-partition fan-out, the all-pass `selectByBoolean` fast path. Today each
equivalence is pinned by a few hand-written fixtures. A generator that
sprays datasets (varying NULL density, group cardinality, key collisions,
partition skew) through random aggregations/filters/distincts exercises
those equivalences across inputs nobody enumerated — and it needs no oracle
beyond "the engine must agree with itself."

This is also the phase that builds `DataGen`, the data foundation every
later property depends on.

## Current state

- Entry point: `SqlEngine.execute(sql, catalog, opts)` (and the 2-arg shim).
  Pipeline: parse → bind → CTE-resolve → optimize → plan → execute.
- In-memory inputs: `MaterializedView(schema, partitionBatches:
  IndexedSeq[IndexedSeq[ColumnarBatch]])` is a `CatalogView`. Build columns
  with `ColumnVector.allocate(dt, cap)` + typed `set`/`setNull`, wrap with
  `ColumnarBatch.fromColumns(schema, cols, numRows)`, register in a
  `Catalog` via `register(name, view)`.
- Outputs: `ExecutedQuery` exposes `schema` (`fieldNames`), `numPartitions`,
  `partition(i)`, and the flat `batches` iterator. `SqlEngineTest`'s
  `collectAllRows(q): Seq[Map[String, Any]]` is the conversion pattern.
- Mode knobs: `ExecutionOptions(spillEnabled, spillThresholdBytes,
  spillMaxRuns, metricsEnabled)` — all per-call. `ExecutionOptions.Default`
  disables both.
- Precedent parity tests: `hash_aggregate_spill_test`,
  `sort_exec_spill_test`, `distinct_exec_spill_test`,
  `hash_join_spill_test`, `window_exec_spill_test` — each asserts spill-on
  vs spill-off equality (multiset / float-tolerance), and `sort_exec_test`
  establishes that the K-way merge is *not* stable, so order-sensitive
  comparison must be multiset + a separate sortedness check.

## Proposed design

### 1. `DataGen` — schema + in-memory view

`genSchema(rng): Schema` — pick column count and per-column `DataType` from
the ADT (`Int/Long/Float/Double/Boolean/String/Date/Timestamp/Decimal`),
with `nullable` flags. `genView(rng, schema, opts): MaterializedView` —
generate `rowCount` rows with:
- **tunable NULL density** per column (0%, sprinkled, ~50%, 100%);
- **tunable value domains** — narrow integer ranges and a small string
  alphabet on purpose, so GROUP BY / JOIN keys actually collide and DISTINCT
  actually dedups (wide-random values make every row unique and hide bugs);
- **tunable partition layout** — `numPartitions`, rows-per-batch (including
  sub-`DefaultCapacity` batches and empty partitions).

A `Dataset` value object carries the logical rows once, plus a
`reshape(numPartitions, batchRows)` that rebuilds an equivalent
`MaterializedView` with a different physical layout — the lever for the
layout mode.

### 2. `QueryGen` (single-table) — typed SELECT over one view

Generate a `SELECT` whose every expression type-checks against the view
schema (reuse `ExprGen` from Plan 01 for projection / WHERE / HAVING
predicates). Coverage for this phase:
- projection list (columns, arithmetic, `CASE`, scalar funcs, aliases);
- optional `WHERE` (a generated boolean `Expr`);
- optional `GROUP BY` (subset of columns, or positional ordinals) with
  aggregates drawn from the `AggExpr` family (`COUNT(*)`, `COUNT[DISTINCT]`,
  `SUM`, `AVG`, `MIN`, `MAX`, `COUNT_IF`, the statistical aggregates) and
  optional `HAVING`;
- optional `DISTINCT`;
- optional `ORDER BY ... [LIMIT n]`.

Emit as a SQL string (the engine's front door is text). Keep a structured
form alongside it for shrinking.

### 3. `ModeDifferential` oracle

```scala
def check(view: MaterializedView, sql: String): Unit
```

Runs `sql` against a fresh `Catalog` registering `view` under a fixed name,
once per mode configuration:
- `ExecutionOptions.Default`;
- spill on: `ExecutionOptions(spillEnabled = true, spillThresholdBytes =
  Some(1L))`;
- metrics on: `ExecutionOptions(metricsEnabled = true)`;
- layout variants: `view.reshape(1, …)`, `reshape(K, tiny)`, `reshape(K,
  withEmptyPartitions)`.

Collect each run via the `collectAllRows` pattern and compare all results to
the baseline with `RowOracle.multisetEquals` (float tolerance, NULL-aware).
For queries with a total `ORDER BY`, additionally assert each run's output
is sorted by the order keys (not positional equality — the merge isn't
stable).

### 4. `RowOracle.multisetEquals`

Extend Plan 01's `RowOracle`: canonicalize each row to a comparable key
(normalize numerics to a common type, NULLs to a sentinel, round
floats/doubles to a tolerance bucket), build multisets, compare with a
readable diff (first N rows present-in-A-not-B and vice versa) for failure
messages.

### 5. Data + query shrinking

Extend `Shrinker`: shrink the `Dataset` (drop rows, drop unreferenced
columns, shrink value magnitudes, collapse partitions to 1) and the query
(drop projection items, drop WHERE/HAVING conjuncts, remove ORDER
BY/LIMIT/DISTINCT, drop GROUP BY keys, simplify expressions via the Plan 01
`Expr` shrinker) — always preserving the failure. A minimal repro is
typically "2 rows, 1 column, `SELECT SUM(x) FROM t GROUP BY x`".

## Files to touch

New under `src/test/scala/com/transformer/fuzz/`: `DataGen.scala`,
`QueryGen.scala` (single-table cut), `oracle/ModeDifferential.scala`,
`ModeDifferentialFuzzTest.scala`. Extend `RowOracle.scala`, `Shrinker.scala`.
Extend `BUILD.bazel` with the `mode_differential_fuzz_test` target (add the
`fuzz` tag to a long-campaign variant; keep the default target small) and
update `.bazelrc`'s `--test_tag_filters` to `-perf,-fuzz`.

Edit: `docs/testing.md`, `docs/extending.md`, `docs/gotchas.md`.

## Edge cases

- Empty view (0 rows) and single-row view.
- Empty result (WHERE matches nothing; aggregate over empty input → the
  `COUNT=0` / NULL-aggregate row).
- All-NULL group key (must collapse to one group — exercises the
  NULLs-to-last routing and the codec/LongHashMap sidecar slot).
- High group-repeat factor (few distinct keys, many rows) vs all-unique
  keys (stresses both the fast path and the spill-run path).
- Single-Long key vs multi-column / string key (LongHashMap vs codec).
- `COUNT(DISTINCT …)` — note it silently disables spill (per
  `docs/architecture.md` §2c); the spill-on run still equals spill-off, just
  via the in-memory path. The property still holds; don't special-case it.
- Float/Double aggregates — compare with tolerance; sum-order differences
  across partition layouts are expected and absorbed by the tolerance.

## Testing

The fuzzer is the test. Add a couple of deterministic regression `@Test`s
(fixed seed) so a zero-config smoke always runs in `bazel test //...`.
Distill any real divergence the fuzzer finds into a named regression `@Test`
here, and — if it reflects a genuine operator bug — into the matching
`*SpillTest` so the operator suite covers it directly.

Gates: `bazel test //...` green; jaffle 15/15; polymarket 15/1/1 (both
unaffected but must stay green).

## Risks

- **Generator emits invalid SQL the engine rejects at bind time.** Decide
  the policy up front: a setup-time `IllegalArgumentException` from the
  *generator's own* malformed SQL is a generator bug to fix, not a finding.
  The property only fires for queries that bind successfully; count and log
  the bind-reject rate so a generator that mostly emits garbage is visible.
- **Nondeterminism leaking in.** Exclude `RAND()` and any wall-clock /
  templating from `QueryGen` — they make modes legitimately disagree.
- **Float tolerance hiding real bugs (false negatives).** Keep the tolerance
  tight; prefer integral/decimal-heavy generation for aggregates so most
  comparisons are exact, and reserve tolerance for genuine float columns.
- **Slow campaigns.** Each property runs the query ~6×; keep default-run row
  counts small and push scale into the `fuzz`-tagged campaign.

## Suggested phases

1. `DataGen` + `Dataset.reshape`; a smoke test that a generated view
   round-trips through `SELECT * ` unchanged (multiset) across layouts.
2. Single-table `QueryGen` (projection + WHERE first), wired to
   `ModeDifferential` with only the layout + metrics modes.
3. Add the spill-on mode and GROUP BY / aggregates / DISTINCT / ORDER BY;
   tune domains so keys collide. Prove sensitivity by temporarily forcing
   the `LongHashMap` path off (or a 1-byte spill threshold against a known
   pre-fix commit, if one exists) and confirming a catch.
4. Data + query shrinking; verify minimized repros are genuinely small.

## Docs to update

- `docs/testing.md` — `data_gen` / `mode_differential_fuzz_test` targets and
  what they cover; the `fuzz` tag and the `-perf,-fuzz` default filter.
- `docs/extending.md` — extend the Plan 01 recipe: how to add a SQL shape to
  `QueryGen` and a mode to `ModeDifferential`.
- `docs/gotchas.md` — the comparison gotchas (unstable K-way merge → multiset
  not positional; float tolerance; `COUNT(DISTINCT)` disables spill but parity
  still holds).

## Launch prompt

```
Read plans/testing/02-data-gen-mode-differential.md and implement it
end-to-end. Plan 01 (the shared Rng/Props/Shrinker/RowOracle harness) must be
landed first.

Use max effort. Honor CLAUDE.md: NO new dependencies, bazel test //... must
pass, jaffle_shop 15/15, polymarket 15/1/1 (both unaffected by test-only code
but must stay green). Land docs in the same PR.

The property: a fixed (data, query) must produce the same result MULTISET
across spill on/off, metrics on/off, and partition/batch layout. This is the
generated generalization of the existing *SpillTest parity checks — read
hash_aggregate_spill_test and sort_exec_spill_test first to match how they
compare (multiset, NOT positional, because the K-way merge is unstable; float
tolerance for Double aggregates).

Build DataGen to make GROUP BY / DISTINCT keys actually COLLIDE (narrow value
domains, small string alphabet) — wide-random values hide aggregation bugs.
Generate empty partitions and sub-DefaultCapacity batches on purpose.

Exclude RAND() and any clock/templating from QueryGen (modes would
legitimately disagree). A bind-time rejection of the generator's own
malformed SQL is a generator bug to fix, not a finding — log the reject rate.

Per the repo readability rule, describe current behavior in comments — no
stale "Phase N" scaffolding in landed code.

Stop and ask before: (a) adding any dependency, (b) touching src/main (this
is test-only — if you believe an operator has a real bug, surface it, don't
patch it under cover of the harness), (c) loosening float tolerance to make a
failure pass, (d) adding the sharded-vs-collapsing mode (that's Plan 03's
cross-JVM target — the gate is a class-load val).

Include in PR description: the sensitivity-check result + seed, the bind
reject rate on the default run, and one example minimized repro.
```
