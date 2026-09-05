# Plan 03 implementation handoff — remaining work

> Status: in progress · Branch: `plan-03-metamorphic` (4 commits landed, off `main`)
>
> Parent plan: [03-query-gen-metamorphic.md](03-query-gen-metamorphic.md). Read it
> for intent. This file is the live execution state so a fresh session can finish
> without re-deriving anything.

## What is DONE (committed on `plan-03-metamorphic`)

```
258167c metamorphic fuzz pt4: multi-relation AST + data shrinking
9a58d63 metamorphic fuzz pt3: CTEs (incl. referenced-twice) + window functions
f75a5cc metamorphic fuzz pt2: UNION / UNION ALL arms
bc5d977 metamorphic fuzz pt1: TLP + NoREC over multi-relation joins
```

New files under `src/test/scala/com/transformer/fuzz/`:
- `MetaQueryGen.scala` — multi-relation generator. A `RelEnv` of 2-3 base
  relations sharing one narrow join-key type/domain; a `MetaQuery` AST
  (`FromClause` left-deep joins, `where`, `QueryCore` = `ProjectCore` |
  `AggCore`, optional `setOp` UNION arm, optional `ctes`). Generates
  scope-/type-correct, paren-free, total SQL. Also `NoRecCase` (single relation +
  predicate). Reuses `QueryGen.AggSpec` + `SqlRender`.
- `oracle/RelEngine.scala` — shared run helpers + the `Verdict` ADT
  (`Held` / `Skipped` / `Rejected`).
- `oracle/Tlp.scala` — output-level TLP via a CTE wrapper
  (`MetaQuery.tlpBase`): `WITH [cte defs,] q AS (<body>) SELECT * FROM q
  [WHERE <partition>]`. Partition key restricted to type-reliable non-float
  output columns (`QueryCore.tlpCandidates`). Runs all four variants on ONE
  fixed single-partition layout.
- `oracle/NoRec.scala` — `COUNT(*) WHERE p == SUM(CASE WHEN p THEN 1 ELSE 0
  END)`, with empty-relation NULL→0 normalization.
- `oracle/MetaModeDifferential.scala` — multi-relation in-JVM mode agreement
  (layouts + metrics + spill). **Spill mode is gated off for any query with a
  join** (`hasAnyJoin`) — see the engine bug below.
- `MetamorphicFuzzTest.scala` — `fuzzTlp`, `fuzzNoRec`, `fuzzModeAgreement`,
  `bindRejectRateIsLow`, `generatorCoversShapes`, `shrinkerTerminatesAndReduces`,
  `sameSeedReproduces`, and deterministic regressions.
- Extended `Shrinker.scala` (`metaCase`, `noRecCase`) and `RowOracle.scala`
  (Float now uses the NaN-aware tolerant comparator — latent harness bug fixed).
- `BUILD.bazel` — `metamorphic_fuzz_test` (default gate) +
  `metamorphic_fuzz_campaign` (`fuzz`-tagged).

Decisions confirmed with the user (do NOT re-litigate):
- **TLP scope** = output-level via CTE wrapper (sound for every shape, no
  aggregate decomposition). Not the input-level or aggregate-decomposition forms.
- **Cross-JVM** = lightweight separate `scala_junit_test` with `jvm_flags`. No
  golden-file exchange.

Engine reality established empirically (binder + probes):
- **Derived-table subqueries in FROM are unsupported** (binder rejects every
  non-`Table` FROM item: `Unsupported FROM item: ParenthesedSelect`). NOT
  generated; the TLP CTE wrapper gives output-level partitioning without them.
- CTEs fully supported; `WITH <defs>, q AS (...) SELECT * FROM q` (hoisted)
  binds identically to nesting.
- `WITH RECURSIVE` rejected → never generated.

Health: bind-reject rate **0.0000** over 500 seeds. Coverage over 2000 seeds:
joins=1234, unions=416, ctes=706, windows=221 (216 TLP-partitioned on the window
column), aggregates=792. Campaigns green to 30k seeds.

## Findings (surfaced, NOT fixed — per the plan, fixes are separate)

1. **Real `src/main` bug — grace-hash join spill NPE.** Spilling JOIN-derived
   rows crashes: a spilled bucket is read back with a null parquet `DataPage`,
   NPE in `HashJoinExec.probeBucket` → `ParquetPartitionIterator.loadNextGroup`
   (`ParquetReader.scala:435`). 2-way joins over base scans are fine; the bug
   needs join-derived rows under spill. Minimal repro (`spill_threshold_bytes=1`):
   ```
   a(k,v)={(1,1)}, b(k,v)={(1,9)}
   SELECT t2.v FROM a t0 JOIN b t1 ON t0.k=t1.k JOIN b t2 ON t1.k=t2.k
   ```
   Documented inline in `MetaModeDifferential.scala`; harness stays green by
   skipping the spill mode for join queries. **This wants its own fix PR.**
2. **Latent harness bug — FIXED in commit 1.** `RowOracle.multisetEquals` treated
   Float as an exact key, so a computed Float `NaN` (float divide-by-zero) failed
   to match itself (Scala `==` on boxed `NaN` is `false`). Float now uses the
   NaN-aware tolerant comparator like Double.
3. **Generator self-correction — done in commit 1.** TLP partition keys are
   restricted to type-reliable non-float columns; a computed `Int`-labelled column
   can reparse to `Double` (`-1.0 % c`) and its `NaN` would fall into no partition.

## What is LEFT

### A. Commit 5 — cross-JVM `ShardedModeFuzzTest` (`jvm_flags`)

The sharding gates `LogicalPlanCardinality.{MinShardableSize, BroadcastBuildThreshold}`
are `val`s read from system properties at class load, so they cannot be toggled
in-JVM. Add a SECOND target that forces the sharded build + shuffle-join paths.

1. New `src/test/scala/com/transformer/fuzz/ShardedModeFuzzTest.scala`:
   - Same spill-dir `@Before`/`@After` setup as `MetamorphicFuzzTest` (the spill
     mode still runs for non-join queries; factor a shared base/trait or
     duplicate the ~15 lines).
   - `@Test fuzzTlp`, `@Test fuzzNoRec`, `@Test fuzzModeAgreement` — identical
     bodies to `MetamorphicFuzzTest` (same generators + oracles + `Shrinker`),
     small budgets. Scope = "metamorphic relations + in-JVM mode agreement hold
     under sharded planning"; do NOT re-add bind-reject / coverage there.
   - **`@Test shardingGateIsActive`** asserting
     `LogicalPlanCardinality.MinShardableSize == 1L` and
     `BroadcastBuildThreshold == 1L` (both are public `val`s) — proves the
     `jvm_flags` took effect, so the target genuinely exercises the sharded path
     rather than silently running the default gate.
2. BUILD targets (default-gate `sharded_mode_fuzz_test` + `fuzz`-tagged
   `sharded_mode_fuzz_campaign`), each with
   `jvm_flags = ["-Dtransformer.scheduler.shard_min_size=1",
   "-Dtransformer.scheduler.broadcast_threshold=1"]`.
3. Run `sharded_mode_fuzz_test`, then a 10k-seed campaign.
   - **Watch for NEW findings.** Under sharded planning, joins shuffle
     (`ExchangeExec`) and aggregates/distinct shard — paths the default gate never
     exercised. A sharded shuffle-join multiset mismatch, or a sharded-aggregate
     **+ spill** crash on non-join queries (a new spill path), would be a real
     finding: surface with a minimized repro, gate to keep green, do NOT fix here.
   - Note: join queries already skip spill (the gate above), so the known
     nested-join spill crash is not re-triggered under sharded planning.

### B. Docs + gates (parent plan's "Docs to update" + CLAUDE.md required workflow)

Land in the same PR:
- `docs/testing.md` — add `metamorphic_fuzz_test` + `sharded_mode_fuzz_test` rows
  to the inventory; extend the "Property-based testing (fuzz)" section with
  `MetaQueryGen`, `Tlp`, `NoRec`, `MetaModeDifferential`, `RelEngine`, and the two
  metamorphic relations + which SQL shapes are live.
- `docs/extending.md` — recipe: "add a metamorphic relation"; how `MetaQueryGen`
  composes relations type/scope-correctly (qualifier-in-`ColRefExpr.name`,
  `tlpCandidates` reliability rule, the CTE-wrapper TLP base).
- `docs/gotchas.md` — class-load-frozen sharding sysprop (why sharded needs a
  separate JVM); aggregate-TLP unsoundness (and why output-level TLP is sound);
  the grace-hash join spill crash (known bug, gated); derived-table subqueries
  unsupported; Float-NaN multiset comparison.
- `docs/architecture.md` — short "Property-based testing" note pointing at the
  `fuzz` harness + the oracle families (expr-parity, mode-differential, TLP,
  NoREC). README.md SQL surface is unchanged (test-only change) — confirm, likely
  no edit.

Gates (CLAUDE.md):
- `bazel test //...` green. (`.bazelrc` filters `-perf,-fuzz`, so the campaigns
  and the perf test are excluded by default; the two default-gate fuzz targets
  run.) The sharded target is a normal (untagged) target → part of `//...`.
- jaffle 15/15:
  ```
  bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
  java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar
  ```
- polymarket 15/1/1 (needs `~/Downloads/archive/` dataset; `-Xmx12g`). If the
  dataset is absent locally, note it in the PR rather than skipping silently.

### C. PR description must include
- Which metamorphic relations are live for which SQL shapes (TLP: projection /
  join / aggregate / window / union / CTE output; NoREC: single-relation
  predicate; mode-agreement: non-window multi-relation, spill gated off for
  joins).
- Bind-reject rate (0.0000) + coverage tallies.
- One minimized TLP repro and whether it surfaced a real engine bug — **TLP held
  on every shape**; the real engine bug (grace-hash join spill NPE) was surfaced
  by the mode-agreement oracle. Include the minimal repro above. Also mention the
  RowOracle Float-NaN fix and the TLP type-reliability self-correction.
- Confirmation the sharded target passes (and any NEW sharded-only findings).

## Commands

```bash
# default gate
bazel test //src/test/scala/com/transformer/fuzz:metamorphic_fuzz_test
bazel test //src/test/scala/com/transformer/fuzz:sharded_mode_fuzz_test   # commit 5

# campaign (env-var budget reliably re-runs the action; --jvmopt may hit cache)
bazel test //src/test/scala/com/transformer/fuzz:metamorphic_fuzz_campaign \
  --test_tag_filters= --test_env=FUZZ_SEEDS=10000 --nocache_test_results --test_timeout=2400

# reproduce a single failing seed
... --test_env=FUZZ_SEED=<seed> --test_env=FUZZ_SEEDS=1
```

## Guardrails (unchanged from the launch prompt)
- No new dependencies. Test-only — do NOT patch `src/main`; surface real bugs
  with a minimized repro and gate the harness green.
- No aggregate-level TLP for non-distributive aggregates; no `WITH RECURSIVE`.
- Comments describe current behavior (no stale "Phase N" scaffolding).
