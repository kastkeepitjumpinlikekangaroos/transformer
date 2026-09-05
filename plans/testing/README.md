# Property-based testing plans — index and launch prompts

A hand-rolled property-based testing (PBT) harness for the SQL engine, so
we can fuzz orders of magnitude more cases than we can write by hand. Three
phases, built bottom-up. Each file is self-contained: read it, paste the
launch prompt at its bottom into a fresh Claude session, and the agent has
what it needs.

## Design decisions (locked)

- **Hand-rolled generators + shrinkers.** No ScalaCheck, no Hedgehog, no new
  Maven dependency. Matches the project's build-it-ourselves philosophy
  (own JSON parser, own CSV state machine, own everything) and the
  no-heavy-deps rule. The harness is test-only Scala under
  `src/test/scala/com/transformer/fuzz/`.
- **Metamorphic + mode-differential oracles.** No external reference DB
  (SQLite/DuckDB/H2 are out — they conflict with the CLAUDE.md
  no-embedded-DB rule and bring SQL-dialect-mismatch noise). Correctness is
  decided by relations that must hold *within* the engine:
  - **Mode-differential** — a fixed `(data, query)` must produce the same
    result multiset across spill on/off, metrics on/off, partition/batch
    layout, and (cross-JVM) sharded-vs-collapsing. This is the generated
    generalization of the existing `*SpillTest` bit-equal parity checks.
  - **Metamorphic** — TLP (`WHERE p` ⊎ `WHERE NOT p` ⊎ `WHERE p IS NULL`
    == unfiltered) and optimizer-equivalence (NoREC-style) relations that
    need no oracle at all.

These two strategies target precisely the engine's novel, highest-risk
surfaces: three-valued NULL logic, the optimizer passes (FilterPushdown,
column pruning, CTE materialization, the sharding/broadcast gates), and the
parallel exchange / spill machinery.

## Priority and dependency table

| # | Plan | Effort | Risk | Depends on |
|---|------|--------|------|------------|
| [01](01-expr-parity-fuzzer.md) | Expr parity fuzzer (`eval` vs `evalVec`) | ~1 day | low | — |
| [02](02-data-gen-mode-differential.md) | DataGen + mode-differential over single-table SQL | 3-5 days | medium | 01 (shared harness) |
| [03](03-query-gen-metamorphic.md) | Multi-relation query gen + metamorphic (TLP) + shrinking | 1-2 weeks | medium-high | 01, 02 |

## Recommended sequence

Strictly in order. Phase 01 builds the shared core (`Rng`, `Shrinker`,
`Props`, `RowOracle`) against an oracle we already trust (`ExprBatchTest`),
so the plumbing is proven before it carries a harder property. Phase 02 adds
the data generator and the mode-differential runner. Phase 03 grows the
query generator to joins/window/CTE/union and adds the metamorphic oracles
plus full AST+data shrinking — the bulk of the work.

## Shared module (introduced in 01, extended in 02/03)

```
src/test/scala/com/transformer/fuzz/
  BUILD.bazel              # testonly scala_library `fuzz` + one scala_junit_test per property
  Rng.scala                # SplittableRandom wrapper; the seed is the repro key
  Props.scala              # property runner: N seeds -> on failure, shrink + print repro
  Shrinker.scala           # generic shrink combinators + data/AST shrinkers
  RowOracle.scala          # canonical row normalization + multiset compare (float tol, NULLs)
  DataGen.scala            # (02) Schema + MaterializedView: cols/types/rows/NULL density/partitions
  ExprGen.scala            # (01) typed random Expr trees over a schema
  QueryGen.scala           # (02 single-table, 03 multi-relation) typed random SELECT
  oracle/
    ExprParity.scala       # (01) eval vs evalVec, reusing ExprBatchTest's normalize/compare
    ModeDifferential.scala # (02) same (data,sql) across modes -> multiset-equal
    Tlp.scala              # (03) ternary-logic-partitioning metamorphic relation
```

## Required workflow gates (applies to every plan)

From `CLAUDE.md`. The harness is test-only code, so the e2e gates are
unaffected but must stay green:

1. `bazel test //...` green (the new property targets run a small fixed-seed
   batch here — see each plan's tagging note).
2. `java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar` exits 0,
   15/15 Succeeded.
3. `java -jar bazel-bin/examples/polymarket/polymarket_deploy.jar` matches
   the 15-Succeeded / 1-ValidationFailed / 1-Skipped pattern.
4. Docs updated where claims are now stale — primarily `docs/testing.md`
   (new targets) and `docs/extending.md` (a "add a generator / property"
   recipe); `docs/gotchas.md` for the cross-JVM class-load-frozen sysprop
   constraint.

Project-wide rules from `CLAUDE.md`:
- Scala 2.13.16, JDK 21; no version bumps without asking.
- No emojis.
- **No new dependencies** — the whole point of the hand-rolled decision.
- Prefer editing existing files to creating new ones (the harness is
  genuinely new, so new files are justified; docs are edits).
- No backwards-compatibility shims unless asked.
- Per the repo's readability rule: describe current behavior in comments;
  do not leave stale "Phase N" scaffolding comments in the landed code.

## Tagging

Default `bazel test //...` runs each property at a small fixed seed count so
regressions surface in the normal gate. Long fuzz campaigns are opt-in via a
`fuzz` tag, excluded by default exactly like `-perf` — add `,-fuzz` to the
`--test_tag_filters` line in `.bazelrc` and run campaigns with
`bazel test //src/test/scala/com/transformer/fuzz/... --test_tag_filters=fuzz`.

## Launch prompts

Each prompt is embedded at the bottom of its own plan file. Open a fresh
session, prefer `--effort max`, and paste the prompt — the agent reads the
plan from disk and acts.
