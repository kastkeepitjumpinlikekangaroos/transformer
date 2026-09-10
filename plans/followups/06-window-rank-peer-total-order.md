# Plan 06: `RANK` / `DENSE_RANK` rank tied rows separately when the order key is `NaN`

> Status: **done** 2026-09-10 · Effort: ~half a day · Risk: low (one comparison
> site, no format or API change)

## How this was found

By the fuzzers, at campaign volume, immediately after [plan 05](05-minmax-nan-order-dependence.md)
widened `DataGen` and `MetaQueryGen.payloadDomain` to draw `NaN` / `-0.0` /
`±Infinity`. `metamorphic_fuzz_campaign` and `sharded_mode_fuzz_campaign` both
fail on the same seed:

```
bazel test //src/test/scala/com/transformer/fuzz:metamorphic_fuzz_campaign \
  --test_tag_filters= --nocache_test_results \
  --test_env=FUZZ_SEED=16502 --test_env=FUZZ_SEEDS=1
```

(The `--test_env=` prefixes are load-bearing and this file originally omitted
them — see the corrections at the bottom.)

The bug is **pre-existing** — `WindowExec` is untouched by plan 05, the failing
query contains no aggregate at all, and `Ops.eq(NaN, NaN)` has always been
false. Plan 05's corpus widening only made it reachable, which is the widening
doing its job. It is the **third** instance of one bug family: plan 03 was
`evalVec`'s ordering comparisons, plan 05 was `MinMaxState`'s accumulator, and
this is the window peer test. Each was an ordering site that reached for raw
IEEE instead of the engine's one total order.

## Problem

`WindowExec.sameKey` (`src/main/scala/com/transformer/sql/exec/WindowExec.scala:573-583`)
decides whether two rows are ORDER BY **peers**, and it compares with `Ops.eq`:

```scala
val same = (av == null && bv == null) || (av != null && bv != null && Ops.eq(av, bv))
```

Its only two callers are `RANK` (`:441`) and `DENSE_RANK` (`:453`), both reading
`orderKeysByRow`. But the rows were *sorted* by `Ops.cmp` (`:405`). The two
comparisons disagree on exactly the values plan 03 reconciled everywhere else:

| values | sort (`Ops.cmp`) | peer test (`Ops.eq`) | effect on `RANK`/`DENSE_RANK` |
|---|---|---|---|
| `NaN`, `NaN` | tied | **not** equal | tied rows get **different** ranks |
| `-0.0`, `0.0` | `-0.0` first | **equal** | strictly-ordered rows get the **same** rank |

Both directions are wrong, and the `NaN` direction produces a plainly incorrect
answer: `DENSE_RANK()` over a partition whose rows all tie must return `1` for
every row.

### The counterexample, minimized

```sql
SELECT DISTINCT t1.c3 AS o1,
       DENSE_RANK() OVER (PARTITION BY t0.c1 ORDER BY t1.c2 ASC, t0.c1 DESC) AS o3
FROM r0 AS t0 INNER JOIN r2 AS t1 ON t0.c0 = t1.c0
```

```
r0 = [(c0=1, c1=NaN, c2=1), (c0=1, c1=NaN, c2=1)]
r2 = [(c0=1, c1=2, c2=0.0, c3=0.5), (c0=1, c1=2, c2=0.0, c3=1.0)]
```

All four joined rows carry the same order key `(0.0, NaN)`, so every row is a
peer of every other and `DENSE_RANK` must be `1` throughout. Instead ranks `2`
and `3` appear. Because the rank then depends on the order rows arrive in,
commuting the join changes which `c3` gets which rank, and the
**join-commutativity** relation reports the disagreement:

```
baseline: {o1=1.0, o3=2}  {o1=0.5, o3=3}
swapped:  {o1=0.5, o3=2}  {o1=1.0, o3=3}
```

Join commutativity is the relation that catches it because it is the only one
that varies which side of a join is the build side, and therefore the order the
window sees its input in. The wrong answer is present in a single run too — it
does not need the flip — but nothing else in the suite looks at a rank over
tied `NaN` rows.

## Proposed fix

Compare peers with the comparison that decided the order:

```scala
val same = (av == null && bv == null) || (av != null && bv != null && Ops.cmp(av, bv) == 0)
```

Keep the null branch as it is — the sort places nulls together, so two nulls are
peers, which is what the existing branch already says. `Ops.cmp` boxes, but this
is not a hot accumulator loop: it runs once per row per rank function over an
already-materialized partition, and the operands are already boxed `Array[Any]`
order keys, so there is no primitive slot to preserve here and no reason to
reach for `java.lang.Double.compare` as plans 03 and 05 did.

`ROW_NUMBER` is unaffected (no peer concept). `LAG`/`LEAD` are unaffected. Frame
handling is `ROWS`-based (see `docs/gotchas.md` on `RANGE`-as-`ROWS`), so no
frame boundary consults `sameKey`.

## What "done" requires

- **A deterministic regression** in `src/test/scala/com/transformer/sql/exec/WindowExecSpillTest.scala`:
  `RANK` and `DENSE_RANK` over a partition whose order key is `NaN` on every row
  must return `1` for every row, and a `-0.0` / `0.0` order key must rank the
  two rows `1` and `2`. Confirm both fail against the current comparison first.
- **Check whether the fuzzers already cover it** once fixed. Seed 16502 becomes
  green by construction; the open question is whether the corpus reaches a
  `NaN`-keyed window ORDER BY often enough to keep covering it. Measure before
  adding a guard — plan 05's lesson was that the shape may be reached while the
  value never is, so tally both halves and only then decide whether
  `MetamorphicFuzzTest` needs a coverage guard for it.
- **Re-run all three campaigns** at campaign volume. Plan 05 left
  `metamorphic_fuzz_campaign` and `sharded_mode_fuzz_campaign` red at seed
  16502; both must go green. `mode_differential_fuzz_campaign` was already green
  at 20000 seeds and has no window functions in its generator.
- **Docs.** `docs/gotchas.md` — the entry this plan is linked from moves to
  fixed. `README.md`'s `NaN` / `-0.0` paragraph under **SQL features** lists what
  follows the total order; add `RANK`/`DENSE_RANK` peers to it once true.
  `docs/conventions.md` already carries the general rule ("anything that ORDERS
  values compares through the one total order") — this is its third citation,
  worth naming there.

## Watch out for

- **Do not "fix" it by gating the generator.** Narrowing `MetaQueryGen` so a
  float window ORDER BY key cannot be `NaN` would turn both campaigns green over
  a corpus that cannot reach the bug. That is the exact failure mode
  `docs/testing.md` records three times over.
- `sameKey` is **not** used for `PARTITION BY`. Partitioning goes through the
  key codecs, which are bit-level consistent on `NaN` and `-0.0` (see
  `docs/architecture.md` §2a) — that side is already right, and this plan should
  not disturb it.
- The `-0.0` half of the fix is a behaviour change with no failing seed behind
  it: `-0.0` and `0.0` currently share a rank and afterwards will not. It is the
  same total order `ORDER BY` already sorts by, so the fix makes the rank agree
  with the row order the user sees — but state it in the commit message rather
  than letting it ride along silently.

## Files to touch

- `src/main/scala/com/transformer/sql/exec/WindowExec.scala` — `sameKey`.
- `src/test/scala/com/transformer/sql/exec/WindowExecSpillTest.scala` — the
  deterministic regression.
- `docs/gotchas.md`, `README.md`, `docs/conventions.md` — as above.

## What was done

`sameKey` compares with `Ops.cmp(av, bv) == 0`, exactly as proposed, so the peer
test is now the tie predicate of the sort that produced the order. Both nulls
stay peers. Nothing else in `WindowExec` moved.

Three deterministic regressions in `window_exec_spill_test`, each run on the
heap path and the spill path: an all-`NaN` partition ranks every row 1; a mixed
partition ranks the tied `NaN` rows last and equally under ASC and first and
equally under DESC; `-0.0` and `0.0` rank 1 and 2. All three fail against the
old comparison (ranks 1,2,3 / 1,3,4 / 1,1 respectively).

**Coverage, measured before deciding on a guard.** Both halves are reached, but
this is the thinnest chain in either corpus. Over 20000 seeds of
`MetaQueryGen.generate`: a `RANK`/`DENSE_RANK` window on 428 seeds (2.1%), one
ORDERED BY a float column on 108 (0.54%), one whose float key actually carries a
`NaN` on 12 (0.06%) and a `-0.0` on 8 (0.04%). At the 2000-seed block its
sibling guards use, the `NaN` count is **1** — enough to reach the bug (seed
16502 is in the campaign's range) but not enough for an assertion to tell rare
from gone. So `rankWindowsReachIeeeSpecialOrderKeys` runs over the campaign's
own 20000 seeds, which costs 0.5s because it generates without executing, and
asserts bounds ~3x below each measured rate. The generator was NOT re-weighted:
the shape and the value both land, and tuning the corpus to make a guard
comfortable is the failure mode this repo keeps recording.

**Campaigns.** All three at `FUZZ_SEEDS=20000`, run as eight disjoint 2500-seed
blocks each (`FUZZ_SEED=0,2500,…,17500`, the split `docs/testing.md` describes),
24 blocks green with no failure: `metamorphic_fuzz_campaign` (~14 min/block),
`sharded_mode_fuzz_campaign` (~15 min/block) — seed 16502 lands in the
15000-17499 block of both — and `mode_differential_fuzz_campaign` (~3 min/block),
which is untouched by this change because `QueryGen` emits no window function at
all and so never reaches `WindowExec`.

## Corrections found while implementing

Two claims in this file were wrong; both are corrected inline above.

1. **The repro command does not reproduce anything.**
   `FUZZ_SEED=16502 FUZZ_SEEDS=1 bazel test <target>` sets the variables in the
   bazel CLIENT process; the test action runs sandboxed and never sees them, so
   that command runs the DEFAULT 80-seed budget and PASSES. Run as written, the
   first thing this plan asks you to confirm — that the seed is red today —
   comes back green, which is the worst possible first data point for a plan
   whose premise is a known-red seed. The working form is
   `--test_env=FUZZ_SEED=16502 --test_env=FUZZ_SEEDS=1`, which `docs/testing.md`
   had right all along and `docs/gotchas.md` had wrong in the same way (both now
   say so explicitly). The env-var-vs-`--jvmopt` distinction the BUILD file
   documents is about cache invalidation; this is a separate trap on the same
   line.

2. **"Check whether the fuzzers already cover it" understated how thin the
   coverage is.** The plan anticipated the plan-05 failure mode (shape reached,
   value never) and it does not apply here — both halves land. But the joint
   rate is 0.06% of seeds, roughly 20x thinner than plan 05's `NaN`-into-`MIN`
   /`MAX` rate, so the 2000-seed block that plan's guards use is not a
   measurement here at all: it returns a count of 1, which no bound can be set
   against. The guard therefore reads a 20000-seed block. Getting there also
   needed a small generator-side addition the plan did not anticipate:
   `WinItem` recorded a window's SQL but not its ORDER BY columns, so there was
   nothing structured to measure. `WinItem.orderCols` now carries them (every
   ordered shape fills it in, and the guard asserts that, so a future shape that
   forgets cannot silently drop out of the tally). It is metadata only — no rng
   draw moved, and the existing shape tallies are bit-identical across the
   change.

A third thing worth knowing, not a wrong claim: the plan says `Ops.cmp` boxes
but the site is cold, and that is right — but the stronger reason to prefer it
over a hand-rolled `java.lang.Double.compare` here is that `sameKey` must agree
with `sortPartition` on EVERY type, not just floats, and the only way to
guarantee that is to call the same comparison the comparator calls. A
float-special-cased peer test would have re-created the same class of bug for
`String` or `Boolean` keys the day either comparison changed.

## Launch prompt

```
Read plans/followups/06-window-rank-peer-total-order.md and implement it
end-to-end. Then read plans/followups/README.md for the workflow gates.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop deploy
jar exits 0 with 15/15 Succeeded. Land docs in the same commit.

Add the deterministic regression FIRST (RANK and DENSE_RANK over a partition
whose ORDER BY key is NaN on every row must give every row rank 1) and
confirm it fails against current code. Then change the comparison. Then
re-run the metamorphic and sharded campaigns at campaign volume and confirm
seed 16502 is green -- it is red today, on purpose, as plan 05 landed the
corpus that reaches it.

Verify every "this is how it works today" claim in this file against the code
before relying on it; plan files in this repo have carried stale claims, and
this one was written from a single reproduction rather than from a fix.

Do NOT narrow the generator to make the campaigns green. Stop and ask before
changing Ops.cmp or Ops.eq themselves, or before touching how PARTITION BY
keys are compared (that goes through the key codecs and is already correct).
```
