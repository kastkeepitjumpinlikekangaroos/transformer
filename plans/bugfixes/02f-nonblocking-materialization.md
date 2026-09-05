# Plan 02f: Non-blocking exchange materialization (parent Option D, made concrete)

> Status: LANDED (2026-07-23, D-stage-sequential approved and implemented —
> see "Landing record" at the bottom) ·
> Tier: liveness fix (the formerly-OPEN cross-thread helpJoin cycle) · Risk: medium
>
> Parent design doc: [`02`](02-sharded-execution-deadlock.md) (Option D).
> Evidence base: [`02e`](02e-campaign-deadlock-reproduced.md) (the reproduced
> campaign wedge + thread dumps) and [`02d`](02d-reland-monitor-free-exchange.md)
> (the CAS+latch that shipped as a perf/convention fix, NOT a liveness fix).
> Red gate: `//src/test/scala/com/transformer/sql/exec:exchange_deadlock_stress_test`
> (Phase 1 below) — wedges HEAD in seconds, 7/7 trials — plus the 20000-seed
> K=4 campaign 02d had to drop.
>
> **TL;DR.** Every deadlock cycle in this engine must pass through an
> exchange-readiness wait — it is the only wait edge that does not follow the
> task-creation tree. The recommended fix is therefore not a cleverer wait but
> the removal of worker-side readiness waits altogether: **pre-materialize
> every `ExchangeExec` bottom-up (post-order) at the engine's two drain choke
> points, before any consumer task exists.** Consumers then only ever read
> published shards; `ensureMaterialized`'s CAS+latch+escape survives unchanged
> as the direct-construction fallback but never sees contention in engine
> execution. ~2 call sites + one plan walk. The cooperative work-queue shape
> was evaluated and is strictly weaker (its "all units claimed, result
> unpublished" tail admits no sound wait — see below); the full
> continuation/async shape is the upgrade path, not the first landing.

## The design constraint (from 02e)

No thread may passively park on exchange readiness while carrying claimed
tasks a winner can await. Losers and guests must either help materialize, or
materialization must complete via continuations instead of blocking fan-out.
02d's `claimer` winner-reentrancy escape must be preserved or made
structurally unnecessary.

Scope locks (do not relitigate): no `ForkJoinPool.managedBlock` (02b's
regression), no growable pool (parent option C stays dropped), no new heavy
deps, and **sharding stays off by default regardless of outcome** — flipping
the sharding gates is a separate perf decision, not part of this fix.

## Phase 1 (DONE): the red test — `ExchangeDeadlockStressTest`

The 02e wait graph pins the exact plan shape that wedges, so the stress test
rebuilds it from the real exec classes (no SQL, no fuzz harness):

```
K driver pool-tasks drain E1.execute(s)      (the dump's aggregate partials)
E1 = ExchangeExec(J1, K)
J1 = collapsing HashJoinExec, probe side = E2, build side = 1-row scan
     -> probeAcrossAllPartitions spawns K consumer tasks of E2
E2 = ExchangeExec(union of 2 J2 subtrees, K)
J2 = collapsing HashJoinExec, build side = 4 partitions (+ ~200us spin)
     -> buildSideAcrossAllPartitions's .get() is where helpJoin inlines
        one of J1's probe tasks beneath the E2-shard-task frames
```

Fresh instances per iteration; K pool-task drivers per iteration; per-iteration
stall deadline via `ForkJoinTask.get(timeout)`; on a stall the test dumps every
thread holding transformer/FJP frames and classifies the wedge
(`ensureMaterialized` + `helpJoin` frames = the 02e family) before failing.

**Determinism was investigated and is not achievable from public API**: the
cycle needs (i) an E2 shard task stolen before the winner's `tryRemoveAndExec`
reaches it, (ii) an unclaimed consumer task persisting in a queue as helpJoin
bait, and (iii) helpJoin's eligibility scan (steal-chain walk + base-of-queue
pick, `ForkJoinPool.helpJoin` JDK 21) selecting it — all three ride FJP's
`ThreadLocalRandom` victim selection and queue-index assignment. Forcing the
first two with latch-gated leaves still leaves (iii) a lottery, and any
arrangement deep enough to rig it would pin unstable JDK internals. A cyclic
test child (a child plan that consumes its own exchange) WOULD deadlock
deterministically but tests an illegal plan the fix's correctness argument
explicitly excludes (plans are acyclic) — a false red. So per the launch
constraint the red test is a bounded stress loop with a measured hit rate.

**Measured hit rate** (8-core arm64 mac, JDK 21.0.6, `parallelism=16` pinned,
K=4, 2 groups x 3000 iterations budget, HEAD = post-02d CAS+latch):

- 7/7 trials wedged, both groups in 6 of 7; wedge iteration per group:
  0, 67, 74, 89, 97, 102, 105, 108, 150, 158(1-group), 159, 208, 230(1-group),
  250, 364, 417 — median ~130, i.e. per-iteration hit probability ~1/150.
  P(a 6000-iteration run stays green on HEAD) ~ (1-1/150)^6000 ~ e^-40.
- Wall time to red: 5-15 s (fail-fast deadline 4-10 s dominates). Compare the
  campaign: 20000 seeds, ~1-3 minutes to wedge, ~20 min budget.
- Captured stacks reproduce 02e exactly on HEAD: the E2 winner parked in
  `get()` awaiting its shard task; the shard-task host blocked inside
  `helpJoin -> doExec -> probeOnePartition -> ensureMaterialized ->
  ready.await()` (CountDownLatch — the monitor-agnostic replay); plus the
  descendant-helping chain (E1's winner ran its own shard task inline via
  `tryRemoveAndExec` and parked in J1's probe fan-out) and E1-loser pile-up.
- Sensitivity: `parallelism=8` mostly completes (1 hit in 2 trials, at
  iteration 1506); the wedge wants idle workers ready to steal a fresh shard
  task while consumer bait persists. Hence the BUILD pins
  `-Dtransformer.scheduler.parallelism=16` so the hit rate does not collapse
  on smaller CI hosts. Single-group runs also hit (iterations 158, 230) —
  cross-traffic helps but is not required.

The target is tagged `fuzz` (excluded from the default `bazel test //...`,
which must stay green) and is EXPECTED TO FAIL until this plan lands. Red
gate command:

```bash
bazel test //src/test/scala/com/transformer/sql/exec:exchange_deadlock_stress_test \
    --test_tag_filters= --nocache_test_results --test_timeout=420
```

## Why any fix must remove readiness waits (the wait-edge taxonomy)

At a wedge, threads wait through exactly three edge types:

1. **Tree awaits** — `submitAndAwaitAll`'s `.get()` on tasks the waiting frame
   itself submitted. Helping (tryRemoveAndExec / helpJoin) or parking.
2. **Readiness waits** — parking on `ExchangeExec.ready` (or blocking on its
   pre-02d monitor) for a result some OTHER thread will publish.
3. **Hosting edges** — helpJoin inlines a stolen task beneath a waiting frame
   (need not be a descendant of the awaited task; 02e Finding 1), and
   descendant helping walks a winner into its own subtree. Irreversible: the
   host cannot resume until the guest returns, even if the awaited task
   completes meanwhile.

Type-1 edges point from a creator frame to a task it created; the carrier of
that task parks only on tasks created deeper (its own frames' fan-outs, or a
guest's — either way created below the hosting point). So any chain of type-1
and type-3 edges descends the dynamic task-creation tree strictly and can
never cycle. **Every deadlock cycle therefore contains at least one type-2
edge.** The inventory of type-2 structures was verified for this design:

- `ExchangeExec.ready` — the only lazy shared-result latch in the engine.
- `HashJoinExec` has NO cached build side: the collapsing build is per-call
  (`buildSideAcrossAllPartitions` on each `executeCollapsing`), per-shard
  builds are per-partition. No cross-consumer wait.
- CTE materialization (`CteResolver.materialize`) is EAGER at resolve time,
  before the main plan is planned or drained — already stage-shaped; no latch.
- Distinct/Aggregate/Sort/Window fan out per call; no shared readiness state.

Kill worker-side type-2 waits on the exchange and the cycle family dies — that
is the whole design space. Point escapes cannot get there: extending 02d's
`claimer` check to "am I above an in-progress unit of THIS exchange on this
thread" fixes the captured single-exchange shape but not the cross-exchange
variant (T1 runs an E1 unit and hosts a guest that parks on E2's latch; T2
runs an E2 unit and hosts a guest that parks on E1's latch — neither park is
above a same-exchange unit, and the cycle closes across the pair). Runtime
wait-graph cycle detection would have to track hosting edges inside FJP —
fragile, rejected.

## Option D-coop: cooperative unit queue (evaluated, NOT chosen)

The 02e sketch: exchange materialization becomes a shared work queue of units
(one per child partition). Every consumer — winner, loser, helpJoin guest
alike — claims units via an atomic counter and runs them inline; per-unit
results land in slots; the last unit's completer combines, publishes, and
opens the latch ("last worker out publishes"). No distinguished winner ever
awaits a claimed task, and a guest landing anywhere just helps — subsuming the
reentrancy escape.

It is genuinely better than the status quo, but the tail case is unsound as a
wait: when a consumer finds **all units claimed but the result unpublished**,
there is no safe way for a pool worker to wait — not parking, not spinning:

- The waiting worker always carries claimed work (at minimum the FJP task it
  is executing — a probe task, an aggregate partial). Concretely: unit-runner
  U (thread running an E unit) parks in a join's tree await on subtask T_j;
  T_j's carrier X hosts a helpJoin guest that consumes E; all E units are
  claimed, so the guest waits for publication. E cannot publish without U's
  unit; U cannot resume without T_j; T_j cannot complete while its carrier
  hosts the waiting guest. The guest's wait closes the cycle whether it parks
  or spins — "help, then wait" re-creates 02e with extra steps.
- So the tail must be: **never wait — privately materialize the remainder**
  (re-run the in-progress units' child partitions locally, unpublished). That
  terminates (private work descends the acyclic plan; nested unready
  exchanges recurse the same way), but:
  - worst-case duplicate work compounds per blocked consumer and per nesting
    level (bounded by consumers x depth — fine for fuzz shapes, ugly for the
    workloads sharding is meant to serve);
  - private re-execution assumes the child subtree is deterministic. A
    non-deterministic child (`RAND()`) gives the private consumer a DIFFERENT
    multiset than the published one. (The shipped 02d escape already carries
    this hazard on its rare path; D-coop makes it a routine path.)
- Failure propagation and exactly-once get more intricate: any unit-runner may
  be the one that observes the failure or completes last, so failure latching,
  publication CAS, and "failed materialization is never retried"
  (`exchange_exec_test` pins all three) must hold across N cooperating
  threads plus the private-remainder path.

Verdict: workable, keeps laziness, smallest blast radius (ExchangeExec only) —
but it trades a structural guarantee for an escape hatch that duplicates work
and adds a determinism precondition. Not chosen while a strictly stronger,
simpler option exists.

## Option D-stage: bottom-up stage pre-materialization (RECOMMENDED)

The continuation shape, reduced to what this engine actually needs. A full
push-based/CPS rewrite of every operator is the multi-week re-architecture the
parent doc scoped out; but the ONLY waits that must become "continuations" are
breaker-to-breaker readiness waits, and those disappear entirely if exchanges
are materialized in dependency order before anything consumes them — the
parent doc's own "stage-by-stage (bottom-up) materialization ... how a real
staged engine avoids this entirely" note, and the same shape CTE
materialization already has in-tree.

Mechanism:

1. `PhysicalPlanner` gains `preMaterializeExchanges(plan: PhysicalPlan): Unit`:
   post-order DFS over the physical tree (visited-set by node identity — CTE
   inlining shares subtrees, so the "tree" is a DAG), collecting every
   `ExchangeExec` children-first; then, sequentially in that order, call the
   exchange's existing materialization entry (`execute(0)` or a small
   package-private `materializeNow()` alias for clarity). Unwrap `MeteredPlan`
   while walking (it already exposes its child for planner passes) so metrics
   wrapping does not hide exchanges.
2. Call it at the engine's two drain choke points, verified exhaustive
   (`grep PhysicalPlanner.plan` — no other production call sites):
   - `SqlEngine.executeUnmeasured` / `executeMeasured`, immediately after
     planning, inside the execute-timed region;
   - `CteResolver.materialize`, after planning the CTE body (a body's own
     nested exchanges currently materialize lazily during
     `materializeInParallel` — the same hazard, same fix).
3. `ExchangeExec` is UNCHANGED. In engine execution the pre-materialization
   pass is the first and only toucher: the CAS winner is the pass's calling
   thread, there are no concurrent consumers yet, so no loser ever parks and
   the reentrancy escape is structurally unreachable. Both stay in the code
   as the direct-construction fallback (tests, embedders), with comments
   updated to say exactly that.

Why the pass itself cannot deadlock: when stage k materializes, every exchange
in its child subtree is already published (post-order), so its fan-out is a
pure task tree — no task in it, nor any helpJoin guest inlined beneath the
pass's `.get()` (guests are other stages' tree tasks or other queries'
consumer tasks, whose own exchanges are ready), ever performs a readiness
wait. Tree awaits complete by work-helping — the invariant `scheduler_test`
pins. By the taxonomy above, removing worker-side type-2 edges leaves only
tree awaits (acyclic) and external drains (parked threads that hold nothing).
Concurrent queries interleave freely: each query's pass runs on its own
calling thread; cross-query helpJoin inlining runs guests that always return.

Parity with pinned semantics:

- **Exactly-once publication**: unchanged code; the pass makes the winner
  deterministic (its own thread). `exchange_exec_test` untouched and still
  meaningful (it exercises the direct-use path).
- **Failure propagation**: a child failure surfaces as today's wrapped
  `RuntimeException("ExchangeExec materialization failed", cause)`, now
  thrown from `SqlEngine.execute` (at pass time) instead of from the first
  consumer's drain. Same exception shape, earlier and more deterministic
  surfacing; a failed exchange is still never retried. Job runner semantics
  unchanged (the task fails either way).
- **Laziness**: nominally lost, actually a no-op. Every current consumer
  (writers, GUI drain, fuzz oracles) drains fully, and today the FIRST
  `execute(s)` on any shard already triggers full materialization — even
  under a LIMIT. Plans with zero exchanges (the shipping default — sharding
  off) walk, find nothing, and skip: jaffle_shop unaffected.
- **Metrics**: stage work moves into `executeNanos` (it currently hides in
  the caller's post-return drain, which `executeNanos` never captured) —
  more truthful; note it in the metrics scaladoc.
- **Memory**: identical — the exchange holds the same materialized shards
  either way.
- **Throughput**: sibling stages materialize sequentially instead of racing.
  Each stage's internal fan-out (child partitions x shards) already saturates
  the pool, so this costs only when many tiny stages queue behind each other;
  concurrent queries still overlap (one pass per query thread). If a profile
  ever shows a real cliff, the upgrade path is D-stage-async: compose stages
  with `CompletableFuture.allOf(...).thenRunAsync(pool)` so independent
  stages overlap (stdlib only). Not now — it re-adds coordinator-blocking
  subtleties with no observed need.

Residual footgun, stated honestly: code that hand-builds an exec graph with
nested exchanges and drains it from pool tasks WITHOUT the pass still runs the
lazy latch path and can still hit 02e — that is the direct-construction
fallback working as designed. The stress test itself becomes the guard: after
this plan it drives the same graphs THROUGH the pass wiring (see gates) and
must be green; `docs/gotchas.md` keeps a trimmed entry saying "engine plans
are immune via pre-materialization; direct lazy nesting from pool tasks is
not, don't do it".

Also evaluated and rejected:

- **Virtual-thread substrate** (tasks on vthreads; parking is cheap; no
  helpJoin, so no hosting edges): JDK-21-native and genuinely cycle-free, but
  it swaps out the entire scheduler, its file-I/O pinning compensation is a
  growable carrier pool in all but name (scope lock), and per-parked-stack
  heap cost is unbounded under deep fan-outs. Out of scope.
- **Generalized reentrancy escape / deadlock detector**: see taxonomy section.

## Implementation plan

Phase A — the pass (small, self-contained):
- `PhysicalPlanner.scala`: `preMaterializeExchanges` (post-order DFS,
  identity-visited, MeteredPlan-transparent) + scaladoc stating the liveness
  argument in one paragraph.
- `SqlEngine.scala`: call it in both execute paths (execute-timed region).
- `CteResolver.scala`: call it in `materialize` after planning the body.
- `ExchangeExec.scala`: comment updates only — `ensureMaterialized` becomes
  "direct-construction fallback; engine plans arrive pre-materialized via
  PhysicalPlanner.preMaterializeExchanges" and the OPEN-hazard paragraph is
  rewritten to point here.

Phase B — flip the red gate and prove it:
- Pre-change: record the stress test red (done above, 7/7).
- Post-change: the stress test must drive the pass. Its topology is built
  directly (not via the planner), so add a pass invocation to the test's
  iteration (`PhysicalPlanner.preMaterializeExchanges(e1)` before submitting
  drivers) — the test then encodes the NEW contract: "graphs reaching
  consumers pre-materialized never wedge". Keep one small companion case that
  documents the residual: the raw lazy path still exists (no assertion that
  it wedges — probabilistic — just the pass-wired main case green).
  Green proof: >= 5 uncached runs at the pinned config, all green; then
  un-tag from `fuzz` into the default suite with a CI-sized default budget
  (target <= ~30 s wall; keep the full budget reachable via `STRESS_*` env
  for campaign use).
- The dropped 02d gate returns: `sharded_mode_fuzz_campaign`
  `FUZZ_SEEDS=20000` K=4 under `--test_timeout=1800` must COMPLETE (this is
  the empirical closure of 02e — record wall time in this file).
- Standard CLAUDE.md gates: `bazel test //...` green (including
  `scheduler_test`, `exchange_exec_test` untouched), jaffle_shop 15/15
  (zero-exchange no-op guard for the default path).

Phase C — docs, same commit as the code:
- `docs/gotchas.md` — the "Sharded execution at K>1" entry: cross-thread
  cycle moves from OPEN to eliminated-for-engine-execution (pre-materialized
  stages); keep "sharding off by default", "never `managedBlock`", and the
  direct-construction residual note; drop the "campaigns may wedge" caveat
  once the campaign gate passes.
- `docs/architecture.md` — parallel-execution section gains the stage
  pre-materialization model (why consumers never wait on exchanges) and the
  wait-edge taxonomy in two sentences.
- `docs/conventions.md` — new rule: any future lazily-shared breaker result
  must either be wired into `preMaterializeExchanges` or never be awaited
  from pool tasks.
- `docs/testing.md` — register `exchange_deadlock_stress_test` (and its
  budget knobs) + the campaign command.
- `plans/bugfixes/README.md` — 02f row; close out the bug-02 saga.
- `README.md` — only if it acquires a K>1 claim; it currently makes none.

Risks:
1. **A new drain path bypasses the choke points** (someone adds a third
   `PhysicalPlanner.plan` + drain site). Mitigation: the conventions rule +
   the pass lives one line below `plan()`'s call sites by convention; the
   stress test reddens again if lazy nested contention returns to engine use.
2. **Shared-subtree double-visit or missed exchange under wrappers**:
   identity-visited DFS + MeteredPlan unwrapping, unit-tested with a diamond
   (two parents, one exchange — materializes once) and a metrics-wrapped
   tree.
3. **Eager surfacing changes observable failure timing** for pathological
   queries (failure previously hidden until a shard was pulled). Accepted and
   documented; no known consumer depends on deferred failure.
4. **Wall-time regression on sharded fuzz** from sequential stages.
   Measured at the campaign gate; expectation is neutral-to-faster (no latch
   convoys, no helpJoin rescue churn). If it regresses materially, D-stage-
   async is the escalation, not a revert to lazy.

Acceptance:
- Stress test green >= 5x uncached at pinned config, promoted to the default
  suite; raw-lazy residual documented.
- 20000-seed K=4 campaign completes under `--test_timeout=1800`; wall time
  recorded here.
- `bazel test //...` green; jaffle_shop 15/15; docs reconciled per Phase C.
- `ExchangeExec` materialization logic byte-identical (comments aside);
  exactly-once + failure pins untouched and green.

## Landing record (2026-07-23)

D-stage-sequential was approved at the design checkpoint and implemented the
same day. What landed, exactly as specced in Phase A/B/C above:

- `PhysicalPlanner.preMaterializeExchanges` (post-order DFS, identity-visited,
  `MeteredPlan`-transparent via a new `childrenOf` sibling of `wrapChildren`)
  + call sites in `SqlEngine.executeUnmeasured` / `executeMeasured` (inside
  the execute-timed region; `QueryMetrics.executeNanos` scaladoc updated) and
  `CteResolver.materialize`. `ExchangeExec` gained only a package-private
  `materializeNow()` alias + rewritten comments; the CAS+latch+`claimer`
  machinery is byte-identical and now documented as the direct-construction
  fallback.
- `ExchangeExecTest` gained the two pass cases (diamond-shared exchange
  publishes once; pass sees through `MeteredPlan`); the pre-existing
  exactly-once + failure-propagation pins are untouched and green.
- `ExchangeDeadlockStressTest` default mode now applies the pass per
  iteration (the engine drain contract) and joined the default suite
  (`fuzz` tag removed); `STRESS_LAZY=1` preserves the historical red
  instrument.

Gate results (8-core arm64 mac, JDK 21.0.6):

- Stress test: **5/5 uncached green at ~2.0s** on the exact pinned config
  that wedged 7/7 pre-fix; `STRESS_LAZY=1` still wedges in ~5s (red
  instrument intact, classification shows `ensureMaterialized` + `helpJoin`).
- `bazel test //...`: 45/45 green (stress test now included).
- jaffle_shop: exit 0, 15/15 Succeeded (zero-exchange no-op guard).
- `sharded_mode_fuzz_campaign`, `FUZZ_SEEDS=20000`, K=4,
  `--test_timeout=1800`: **PASSED in 719.1s** (~12 min wall; JVM CPU ~100%
  throughout — the pre-fix runs flatlined to 0% within ~1-3 minutes). This
  is the gate 02d had to drop because its baseline never finished; its
  completion is the empirical closure of 02e.

## Launch prompt

```
Read plans/bugfixes/02f-nonblocking-materialization.md (this file), its Phase 1
red-test results, and prereqs: 02e (the wedge), 02d (what shipped), the
docs/gotchas.md K>1 entry, and ExchangeExec.ensureMaterialized's comment.
Implement Phase A exactly (PhysicalPlanner.preMaterializeExchanges + the
SqlEngine x2 and CteResolver call sites; ExchangeExec comment-only), then
Phase B gates in order: stress test wired through the pass and green 5x
uncached, campaign FUZZ_SEEDS=20000 K=4 completes, bazel test //... green,
jaffle_shop 15/15. Then Phase C docs in the same commit. Scope locks: no
managedBlock, no growable pool, no new deps, sharding stays off by default.
Use max effort. Stop and ask before deviating from D-stage-sequential.
```
