# Plan 02e: The K>1 hard deadlock is REAL — reproduced during the 02d landing

> Status: DONE (investigation record, 2026-07-23) · Tier: evidence write-up ·
> This is the record of what the 02d landing actually found, superseding 02a's
> refutation. Read [`02a`](02a-reproduce-or-refute-deadlock.md) "Resolution"
> for the original (small-budget) refutation and
> [`02d`](02d-reland-monitor-free-exchange.md) for the plan whose gates
> surfaced this.
>
> **TL;DR.** While collecting 02d's *pre-change* wall-time baseline, the
> 20000-seed K=4 sharded campaign hard-deadlocked HEAD (the `synchronized`
> monitor exchange) — the historical "workers parked on two exchange
> monitors, 0% CPU" observation was real all along, just needing campaign
> volume. Mechanism: `ForkJoinTask.get()`'s **helpJoin runs tasks from
> stealers' queues that need not be descendants of the awaited task**, so a
> *consumer* of an exchange can be inlined beneath a frame of that exchange's
> own materialization — closing a winner → shard-task → inlined-consumer →
> exchange wait cycle. The cycle is monitor-agnostic, so 02d's CAS+latch does
> not eliminate it (02d shipped as a perf + convention fix). A second variant
> — the guest landing on the winner's *own* stack — was silently survived by
> the old monitor's reentrancy and turned into a **self-deadlock** by the
> first CAS+latch cut (the default-seed fuzzer timed out); fixed the same day
> via winner-reentrancy detection + private materialize.

## Environment

JDK 21.0.6 (Bazel `remotejdk21_macos_aarch64`), macOS arm64, 8 cores,
`Scheduler.parallelism` default (16). Sharding flags as in the
`sharded_mode_fuzz_campaign` target: `shard_min_size=1`,
`broadcast_threshold=1`, `shard_count=4`.

## Finding 1 — cross-thread cycle: hard deadlock on the PRE-change code

Command (the 02d wall-time baseline, run on HEAD = `b66369d`, monitor DCL
exchange):

```bash
bazel test //src/test/scala/com/transformer/fuzz:sharded_mode_fuzz_campaign \
    --test_tag_filters= --test_env=FUZZ_SEEDS=20000 \
    --nocache_test_results --test_timeout=1800
```

Timeline: healthy for ~3 minutes (~3:01 of JVM CPU accumulated, heavy worker
churn — thread ids into the #41000s, the same I/O-compensation churn 02a saw),
then flatlined at 0.0% CPU. Two `jstack` dumps 90 s apart
([`02e-evidence/baseline-hang-jstack.txt`](02e-evidence/baseline-hang-jstack.txt),
[`...jstack2.txt`](02e-evidence/baseline-hang-jstack2.txt)) show a
**byte-identical wait set** — a permanent wedge, not slowness.

State at the wedge (16 workers; two exchanges, call them E1 `0x716165368`
and E2 `0x7161648e8`; E1's subtree contains a join J' whose probe side reads
E2):

| Thread | State |
|---|---|
| #40117 | **E2's winner.** Runs a J' probe task → `E2.ensureMaterialized` (holds E2's monitor) → `materialize()` → `submitAndAwaitAll` → parked in `get()` awaiting E2 shard task `0x715efe878` |
| #40149 | **Claims `0x715efe878`** (E2's shard task, stolen normally). Inside it, an inner join fans build tasks → `get()` → **`helpJoin` inlines a J' probe task** (a *consumer* of E2, pulled from a stealer's queue) beneath the shard-task frame → the guest calls `E2.execute` → **BLOCKED entering E2's monitor** |
| #40156 | E1's winner; help-ran its own E1 shard task inline (`tryRemoveAndExec` — benign, descendant), descended into J' fan-out, parked awaiting J' probe task `0x7161e61c8` |
| #40031 | Claims `0x7161e61c8`; probes E2 → BLOCKED entering E2's monitor |
| #28, #40154, #41050 | Aggregate partial tasks → `E1.execute` → BLOCKED entering E1's monitor |
| 9 workers | Idle in `awaitWork` — every remaining task is claimed by a blocked thread |
| main | Parked in the top-level collapsing aggregate's `submitAndAwaitAll` |

The self-contained cycle is `{#40117, #40149}`:
E2's winner awaits shard task `S` → `S`'s thread hosts an inlined E2
*consumer* → the consumer waits for E2 → held by the winner. Everything else
dangles off it (E1's winner waits into it transitively, three more workers
pile up on E1).

**Why 02a's proof failed.** "Monitor acquisition follows the plan's acyclic
parent→child topology, so no cycle" implicitly assumed a worker's stack only
descends the plan tree. `helpJoin` (JDK 21 `ForkJoinPool.helpJoin`, frame at
line 2076 in the dump) runs tasks from stealers' queues wholesale while
chasing the awaited task — including tasks from a *different region of the
plan*. One stack can therefore interleave producer frames (below) with
consumer frames (above) of the same exchange, and lock/latch order stops
following the plan topology. 02a's probes (H1 default seeds ~5 s, H5 1500
seeds, deterministic H2/H3 shapes) simply never hit the required steal
interleaving; 20000 seeds does, reliably within minutes.

## Finding 2 — winner-stack variant: the naive CAS+latch REGRESSED default seeds

02d's first cut (CAS claim + `CountDownLatch`, plain `ready.await()` losers,
no reentrancy handling) made `bazel test //...` hang: `sharded_mode_fuzz_test`
— the *default-seed* target, green in ~5 s for months — **TIMEOUT at 300 s**.
The Bazel runner's timeout dump
([`02e-evidence/newcode-selfdeadlock-test.log`](02e-evidence/newcode-selfdeadlock-test.log))
shows one worker with `ensureMaterialized` **twice on the same stack**:

- bottom: shard task of exchange EA → its child join's build side reads
  exchange EB → this thread becomes **EB's winner** (`ensureMaterialized` →
  `materialize()` → `submitAndAwaitAll` → `get()`);
- `helpJoin` inlines a **sibling EA shard task** (stolen from another queue)
  whose join also needs EB;
- top: the guest's `EB.ensureMaterialized` loses the CAS **to its own carrier
  thread** and parks on `EB.ready` — a latch only this thread can count down.

The old `synchronized` monitor survived this identical interleaving by
accident: monitors are reentrant, so the guest entered and
**double-materialized** (duplicate work, live — and silently racy: the inner
`shards` assignment was later overwritten by the outer). The latch is not
reentrant, so the naive port turned "wasteful but live" into a
deterministic-enough self-deadlock that default seed volume hits it.

Fix (in `ensureMaterialized`): record the winning thread in a `@volatile
claimer`; a caller that lost the CAS but *is* the claimer thread materializes
a **private unpublished copy** and returns it, leaving publication to the
outer winner frame. The guest's sub-tasks only compute `child` partitions
(the plan is acyclic — the child subtree cannot read this exchange back), so
the local build cannot re-park on `ready`. Verified: `sharded_mode_fuzz_test`
green 3× uncached (~4 s each) after the fix.

## What 02d actually shipped, in light of this

- **B lands as a perf + convention fix**: no JVM monitor across the
  pool-blocking `materialize()`; losers wake together (cliff gone); plus the
  reentrancy escape above. **It is NOT a liveness fix.**
- **The cross-thread cycle (Finding 1) remains open.** It is monitor-agnostic
  — replaying the same schedule under CAS+latch parks the guest on `ready`
  instead of the monitor, with the same cycle. Mitigations: sharding is off by
  default; the default-seed K=4 fuzzer is deterministic and green in seconds;
  long sharded campaigns must run under `--test_timeout` and may wedge.
- **Parent plan 02 Option D (non-blocking / event-driven breaker
  materialization) is the real fix**, and its trigger is no longer
  hypothetical. Reopen it before sharding is ever promoted toward a shipping
  default. Note for its design: it must break the "winner passively awaits a
  task that can transitively wait on the winner" shape — e.g. losers/guests
  work-share the materialization from a task queue instead of parking, or
  materialization completes via continuations rather than blocking fan-out.

## Post-change campaign probe (CAS+latch + reentrancy escape)

Same 20000-seed command as Finding 1, run against the landed 02d code:
**wedged as predicted** (~1 minute in; CPU time frozen across samples; dump in
[`02e-evidence/postchange-hang-jstack.txt`](02e-evidence/postchange-hang-jstack.txt)),
confirming empirically that B does not eliminate the cross-thread cycle — the
guests now park on `ready` latches (24 `CountDownLatch` waits in the dump)
instead of monitors. Two refinements the dump adds:

- **Descendant-only helping also feeds the wait chains.** Thread #40029 is an
  exchange EX's *winner* that help-ran its own EX shard task inline (the
  legitimate, tree-preserving kind of helping), descended EX's child subtree
  into a *nested* exchange EY, and parked there as an ordinary loser. So a
  winner's await graph acquires claimed-task edges through both descendant
  helping (walking into nested exchanges as loser) and helpJoin's
  non-descendant inlining; at K>1 nesting these edges close into cycles.
- **The reentrancy escape fires in the wild.** The dump shows a frame at the
  winner-reentrancy branch (`ensureMaterialized`'s private-materialize path)
  actively rebuilding a private copy — the Finding 2 fix doing its job while
  the cross-thread wedge forms elsewhere.

Conclusion: at K>1, short deterministic budgets are green; campaign volume
wedges, before AND after 02d. The characterization in `docs/gotchas.md`
("perf + convention fix, not a liveness fix; campaigns must run under
`--test_timeout`") is the accurate one.

## Repro

- Cross-thread cycle: the Finding 1 command on the pre-02d code
  (`git checkout b66369d`); wedged ~3 min in on this machine. Probabilistic —
  needs campaign volume, not a specific seed.
- Self-deadlock: `git checkout` the 02d tree with the `claimer` check removed,
  then `bazel test //src/test/scala/com/transformer/fuzz:sharded_mode_fuzz_test
  --nocache_test_results` — times out within the default budget.
- Diagnosis: `jstack <test JVM>` twice, ≥60 s apart; identical wait sets plus
  `ExchangeExec` frames as above confirm the wedge. The Bazel JUnit runner also
  writes a full dump into `test.log` on `--test_timeout` expiry.
