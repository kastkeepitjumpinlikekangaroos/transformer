# Plan 02c: Un-gate the sharded fuzzer, validate end-to-end, update docs

> Status: not started · Tier: validation + docs · Effort: 1 day · Risk: medium
> · **Gated by [`02b`](02b-implement-fix.md)** (and a green default-K fuzzer).
>
> Parent design doc: [`02-sharded-execution-deadlock.md`](02-sharded-execution-deadlock.md).
> This is the parent's Phase 4. Do not start until 02b's fix is in and the
> default-K sharded fuzzer runs without hanging on a scratch build.
>
> **Note (post-02a/02b):** the hard deadlock was refuted and C (growable pool)
> was dropped, so there is no `materializationPool` — exchange fan-out stays on
> `Scheduler.pool`. The §4 project-doc updates already landed with 02b (gotchas,
> architecture, conventions, testing). What remains here is the un-gate + the
> multi-shard fuzzer validation, and confirming the throughput cliff is gone.

## 1. Remove the `shard_count=1` pin

In `src/test/scala/com/transformer/fuzz/BUILD.bazel`, both
`sharded_mode_fuzz_test` and `sharded_mode_fuzz_campaign` carry:

```
"-Dtransformer.scheduler.shard_count=1",
```

with a block comment explaining the deadlock workaround (and the same rationale
is duplicated in `ShardedModeFuzzTest.scala`'s class scaladoc, lines ~26-42).
Remove the flag from both targets and rewrite both prose blocks: the K=1 pin is
gone; the fuzzer now runs at the default shard count (`= Scheduler.parallelism`)
and exercises real multi-shard nesting. Keep the two sharding flags
(`shard_min_size=1`, `broadcast_threshold=1`).

Decision to make here: leave shard count at the default, or pin a specific
`K > 2` (e.g. `shard_count=4`) so the target is deterministic across machines
with different core counts. Recommendation: pin a modest `K` (4) so CI behaviour
doesn't drift with `availableProcessors`, and note why in the comment.

## 2. Run the sharded campaign at default K

- `bazel test //src/test/scala/com/transformer/fuzz:sharded_mode_fuzz_test`
  — the default budget, part of `bazel test //...`.
- A larger campaign to shake out timing-sensitive residue:
  `bazel test //src/test/scala/com/transformer/fuzz:sharded_mode_fuzz_campaign
  --test_env=FUZZ_SEEDS=20000 --nocache_test_results --test_timeout=1800`.
  The metamorphic relations (TLP, NoREC) and in-JVM mode agreement must hold
  under multi-shard plans, with no hang. Watch wall time — a sudden cliff
  (vs the K=1 run) flags the monitor-serialisation throughput issue 02b/B was
  meant to remove; investigate before declaring done.

## 3. Standard gates (CLAUDE.md required workflow)

- `bazel test //...` fully green (includes the `scheduler_test` compensation
  guard and the exchange exactly-once concurrency tests).
- **jaffle_shop 15/15** — the non-sharded regression guard. Sharding is off
  there (`MinShardableSize = Long.MaxValue`), so no `ExchangeExec` is ever built;
  the only code path 02b changed on this run is `submitAndAwaitAll`'s
  `awaitCompensated` wrap. Confirm exit 0,
  `/tmp/transformer-jaffle-out/`, 15/15 Succeeded with validations passing:

  ```bash
  bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
  java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar
  ```

- Before/after wall-time spot check on jaffle_shop (or a non-sharded synthetic):
  the compensation/await change must be ~free when nesting is shallow.

## 4. Documentation — DONE when 02b landed (2026-07-08)

Reconciled in the same commit as 02b's code, tensed to 02a's refutation
("hardened", not "deadlock fixed"):

- **`docs/gotchas.md`** — the sharded-execution entry now reads as the throughput
  cliff + latent footgun 02a proved it to be, marked hardened: `ExchangeExec` is
  monitor-free (CAS + latch) and `submitAndAwaitAll` compensates. Keeps the
  "never hold a monitor across a `Scheduler.pool` wait" rule and the
  sharding-off-by-default + pin-retained-for-speed notes.
- **`docs/architecture.md`** — §2b describes monitor-free CAS+latch
  materialization (was DCL) and downgrades the "unfixed deadlock" caveat; §3
  states the managed-blocking compensation truth alongside work-helping. No
  two-pool split (C dropped) — there is one `Scheduler.pool`.
- **`docs/conventions.md`** — concurrency convention added: never hold a JVM
  monitor across a `Scheduler.pool`-blocking call; publish via a CAS-claim +
  latch and await through `Scheduler.awaitLatch`.
- **`docs/testing.md`** — registers `scheduler_test` (compensation guard) and the
  two new `ExchangeExecTest` concurrency cases.
- **`README.md`** — unchanged; it never claimed sharding was safe/unsafe at K>1
  (no user-visible line to correct).

Still TODO in this sub-plan: after un-gating (§1), note that the sharded fuzzer
now covers multi-shard nesting.

## 5. Honesty gate (parent plan's explicit instruction)

02a refuted the hard deadlock, so nothing claims a repro turned green. A+B
shipped; C was dropped as unjustified (not deferred with a residual depth cap —
there was no observed depth at which the compute pool starves, because
work-helping + compensation cover it). If the multi-shard campaign (§2) ever
reveals a real wall-time cliff or thread-churn problem, reopen C with a
profile-backed justification.

## Acceptance

- `shard_count=1` pin removed; sharded fuzzer (default + campaign) green at
  multi-shard nesting, no hang.
- `bazel test //...` green; jaffle_shop 15/15; wall-time neutral on the
  non-sharded path.
- All five doc files above reconciled with what actually shipped, in the same
  commit as the code, with an honest residual statement.
