# Plan 01: Expr parity fuzzer (`eval` vs `evalVec`)

> Status: not started · Tier: testing · Effort: ~1 day · Risk: low

## Goal

Generate typed random `Expr` trees over a generated `ColumnarBatch` and
assert that the two evaluation paths agree on every row:
`expr.eval(batch, row)` (boxed, per-row) vs
`expr.evalVec(batch).getBoxed(row)` (vectorized, column-at-a-time). This is
the generated generalization of the hand-written
`src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala`.

Landing this also builds the shared harness core — `Rng`, `Props`,
`Shrinker`, `RowOracle` — against an oracle we already trust, so the
plumbing is proven before Phases 02/03 put it under harder properties.

## Why it matters

`evalVec` is a parallel implementation of every hot `Expr` node (see
`docs/architecture.md` §5a). The *only* thing keeping it honest is
`ExprBatchTest`, which checks a fixed, hand-enumerated set of trees with
hand-placed NULLs. Every new override is "a correctness gamble" (the plan-03
launch prompt's own words) until someone remembers to add the matching
fixture. A fuzzer that generates trees — nested, NULL-saturated, mixed-type
— turns that from "cases we thought of" into "cases the generator finds",
and it does so on the cheapest possible surface: no catalog, no planner, no
parallelism, just `Expr` over one batch.

The bugs this catches are real and subtle: NULL 3VL divergence between the
row and vector paths, divide-by-zero handling, cross-numeric promotion
(`IntVector + LongVector`), FloatType narrowing on store, short-circuit
differences in `CaseExpr`/`AND`/`OR`.

## Current state

- `Expr` lives in `src/main/scala/com/transformer/sql/plan/Expr.scala`.
  Scalar nodes: `LitExpr(value, dataType)`, `ColRefExpr(index, name,
  dataType)`, `CastExpr(child, target)`, `UnaryOpExpr(op, child, dataType)`,
  `BinOpExpr(op, left, right, dataType)`, `FuncExpr(name, args, dataType)`,
  `CaseExpr(branches, elseExpr, dataType)`, `IsNullExpr(child, negated)`,
  `InListExpr(child, items, negated)`, `LikeExpr(child, pattern, negated)`.
- Op tokens and the NULL-free comparators/arithmetic live in
  `sql/plan/Ops.scala` (`Ops`/`VecOps`); scalar function names in
  `sql/plan/Funcs.scala` (`Funcs`/`VecFuncs`). The `evalVec` fast-path
  function list is enumerated in `docs/architecture.md` §5a.
- `ExprBatchTest.assertParity(expr, batch)` is the precedent oracle: it
  builds `vec = expr.evalVec(b)`, asserts `vec.dataType == expr.dataType`,
  then per row compares `normalize(eval(b,i), dt)` against
  `normalize(getBoxed(i), dt)`. `normalize` coerces to the declared
  `dataType` so Float-vs-Double and Int-vs-Long shapes compare equal.
- Batches are built with `ColumnVector.allocate(dt, cap)` + typed
  `set`/`setNull`, wrapped via `ColumnarBatch.fromColumns(schema, cols,
  numRows)`.
- No PBT infrastructure exists. Tests are JUnit 4 `scala_junit_test` with
  `suffixes = ["Test"]`.

## Proposed design

### 1. `Rng` — seeded, splittable, the repro key

Thin wrapper over `java.util.SplittableRandom`. Exposes `nextInt(bound)`,
`nextLong`, `nextDouble`, `oneOf(seq)`, `weighted((w, a)*)`, `bool(p)`,
`split()` (for independent sub-streams so generator refactors don't perturb
unrelated draws), and a recursion-depth budget so trees terminate. The
top-level seed is a `Long`; every failure prints it. Re-running with the
same seed reproduces the exact tree + batch.

### 2. `Props` — the property runner

```scala
def forAll[A](name: String, gen: Rng => A, shrink: A => Iterator[A])
             (prop: A => Unit): Unit
```

Loops `N` seeds (default from a system property, small in the default test
run). For each, generates `a`, runs `prop(a)`; on `Throwable` it greedily
shrinks (repeatedly replace `a` with the first smaller candidate that still
fails) and then fails the JUnit test with: the seed, the minimized value's
`toString`, and the original exception. One reusable runner for all three
phases.

### 3. `Shrinker` — generic combinators (Expr shrinker here)

Hand-rolled integrated-style shrinking: `Iterator[A]` of strictly-smaller
candidates. Combinators: `int`, `list` (drop elements / shrink elements),
`oneOfShrink`. The `Expr` shrinker:
- replace a node with one of its children of compatible type (the single
  most effective move — collapses `f(g(x))` toward `x`);
- replace a subtree with a literal of its type (including a NULL literal —
  surfaces NULL-handling minimal cases);
- shrink literal magnitudes toward 0 / `""`;
- drop `CaseExpr` branches, `InListExpr` items, `FuncExpr` args (down to
  arity floors).

### 4. `ExprGen` — typed random Expr trees

`gen(rng, schema, targetType, depth): Expr` returns a tree whose
`dataType` is assignable to `targetType`. Type-correctness is the core
invariant — the generator tracks each subtree's result type so parents
compose validly (arithmetic over numerics, `AND`/`OR`/`NOT` over booleans,
`LIKE` over strings, comparisons returning boolean, `CAST` between
representative pairs). Leaves: `ColRefExpr` into the batch schema, or
`LitExpr` (including NULL literals at a tuned rate). Internal nodes drawn by
weight from the node list above; depth budget forces termination. Cover the
full `Funcs.applyVec` fast-path list and the operator set that overrides
`evalVec` first, since those are the high-value targets.

### 5. `RowOracle` — scalar normalization + compare

Lift `ExprBatchTest`'s `normalize(value, dataType)` into the shared
`RowOracle` (Phase 02 reuses it for whole-row multiset compares). Provide
`scalarEquals(a, b, dt)` with float/double tolerance and NULL-aware
equality. The property: for every row, the two paths agree — same
normalized value, both NULL, or **both throw the same exception class**
(divide-by-zero must diverge in neither direction). Also assert
`vec.dataType == expr.dataType`, exactly as the precedent does.

### 6. `ExprParity` oracle + the test target

`oracle/ExprParity.scala` holds `check(expr, batch)`. The test class
`ExprParityFuzzTest` builds a random schema+batch (controlled NULL
placement, including all-null and no-null columns, and the default capacity
boundary) and a random typed `Expr`, then runs `ExprParity.check` under
`Props.forAll`.

## Files to touch

New, under `src/test/scala/com/transformer/fuzz/`:
- `Rng.scala`, `Props.scala`, `Shrinker.scala`, `RowOracle.scala`,
  `ExprGen.scala`, `oracle/ExprParity.scala`, `ExprParityFuzzTest.scala`,
  `BUILD.bazel`.

`BUILD.bazel`: a testonly `scala_library` named `fuzz` (the shared core +
generators) and a `scala_junit_test` `expr_parity_fuzz_test` depending on
it plus `//src/main/scala/com/transformer/core`,
`//src/main/scala/com/transformer/sql/plan`, `@maven//:junit_junit`.

Edit: `docs/testing.md`, `docs/extending.md` (see Docs section).

## Edge cases

- All-null and no-null columns (the no-null branch hits `IsNullExpr`'s
  empty-bitset fast path).
- The `ColumnarBatch.DefaultCapacity` (8192) boundary and a 1-row batch.
- Divide-by-zero and modulo-by-zero (both integral and floating).
- Cross-numeric operands (`IntVector` op `LongVector`, `Float` op `Double`).
- NULL literal operands on every binary/func/case shape.
- Empty `CaseExpr` else (NULL default); every-row-claimed short-circuit.
- `LikeExpr` literal-pattern fast path vs column-pattern slow path.

## Testing

The fuzzer *is* the test. Add one deterministic regression `@Test` that
locks a hand-written tree (so the file isn't purely generative and a
zero-seed smoke always runs). When the fuzzer finds a real divergence,
distill it into a named regression `@Test` here and (if it reflects a gap)
into `ExprBatchTest` proper.

Gate: `bazel test //...` green (runs the small default-seed batch).

## Risks

- **Generator bugs masquerading as engine bugs.** Mitigate: the generator
  asserts its own type invariant (`gen` result `dataType` matches the
  requested target) before handing the tree to the oracle.
- **Flaky non-determinism.** The only documented nondeterministic node is
  `RAND()` — exclude it from `ExprGen` (it has no parity meaning). Everything
  else is pure; same seed must reproduce exactly.
- **Shrinker loops.** Every shrink step must produce a strictly-smaller
  value (by a size metric); cap total shrink steps as a backstop.

## Suggested phases

1. `Rng` + `Props` + `Shrinker` core, proven on a trivial throwaway
   property (e.g. "a generated Int list reverses twice to itself") to shake
   out the shrink loop.
2. `ExprGen` + `RowOracle` + `ExprParity` + `ExprParityFuzzTest`; cover the
   `evalVec`-overriding nodes + the `Funcs.applyVec` list.
3. Tune NULL rate / depth / type mix until a deliberately reverted
   `evalVec` override (temporarily break one in a scratch branch) is caught
   within the default seed budget — proof of sensitivity. Revert the break.

## Docs to update

- `docs/testing.md` — new `fuzz` library + `expr_parity_fuzz_test` target;
  note the `fuzz` tag for long campaigns and that the default run is a
  small fixed-seed batch.
- `docs/extending.md` — new recipe "Add a property / generator": how `Rng`,
  `Props`, and `Shrinker` compose, and how to add an `Expr` node to
  `ExprGen` when you add one to the engine.
- `.bazelrc` — add `,-fuzz` to `--test_tag_filters` once a `fuzz`-tagged
  campaign target exists (may defer to Phase 02 if no tagged target lands
  here).

## Launch prompt

```
Read plans/testing/01-expr-parity-fuzzer.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: NO new dependencies (hand-rolled is the whole
point), bazel test //... must pass, jaffle_shop deploy jar must hit 15/15
Succeeded, polymarket deploy jar must hit 15/1/1 (both unaffected by test-only
code but must stay green). Land docs in the same PR.

This phase builds the shared harness core (Rng, Props, Shrinker, RowOracle)
plus ExprGen and the ExprParity oracle. The oracle is a generated
generalization of src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala
— read that file first and reuse its normalize/compare logic; do not
reinvent it.

CRITICAL: the generator must be type-correct by construction (track each
subtree's result DataType) and must exclude RAND() (no parity meaning). The
property is that eval and evalVec AGREE per row — same normalized value, both
NULL, or both throw the same exception class. Same seed must reproduce exactly.

Prove sensitivity before declaring done: temporarily revert one evalVec
override in a scratch branch and confirm the fuzzer catches it within the
default seed budget, then restore.

Per the repo readability rule, describe current behavior in comments — no
stale "Phase N" scaffolding left in the landed code.

Stop and ask before: (a) adding any Maven dependency, (b) changing anything
under src/main (this phase is test-only), (c) modifying ExprBatchTest's
existing cases (extend, don't rewrite).

Include in PR description: the sensitivity-check result (which override you
broke and that it was caught + the seed), and the default seed count chosen.
```
