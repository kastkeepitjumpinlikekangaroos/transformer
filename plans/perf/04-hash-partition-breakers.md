# Plan 04: Hash-partition between pipeline breakers

> Status: not started · Tier: 1 (structural) · Effort: 4-8 weeks · Risk: high

## Goal

Stop collapsing every pipeline breaker (`HashAggregateExec`,
`HashJoinExec`, `DistinctExec`, `WindowExec`, `SortExec`) to
`numPartitions = 1`. Instead, shard rows by `hash(key) % K` into K
independent buckets, with `K = Scheduler.parallelism` by default. The
breaker's output becomes K partitions, downstream operators stay parallel.

This is the intra-JVM analog of Spark's shuffle exchange — no
serialization, no disk, no network. It opens up real downstream
parallelism for the first time.

## Why it matters

Today, after any pipeline breaker, the entire downstream plan runs
single-threaded until the writer fans back out by output partition. From
the prior comparison:

> Spark's shuffle hash-partitions or range-partitions data by the
> join/group/sort key, so each downstream task gets a key-disjoint slice
> and can compute its slice independently. transformer doesn't do that —
> instead, every pipeline breaker funnels through numPartitions=1.

The current architecture-doc passage even describes this:

> Pipeline breakers (HashAggregate/HashJoin/Sort/Distinct/GlobalLimit)
> collapse to numPartitions = 1 and materialize across partitions in
> parallel via an `Executors.newFixedThreadPool`.

The cost is most visible at:
- **HashAggregate merge step** (`AggregateExec.scala:73-85`) — single
  thread merges all K partial maps. For a 100M-distinct-key aggregate
  this is the wall.
- **HashJoin build** (`JoinExec.scala:52-74`) — single combined build
  map of the entire right side.
- **Downstream operators after a breaker** — eg. a Project / Filter
  after a join runs on one thread, even if the data is 10s of millions
  of rows.

Hash-partitioning everywhere enables:
- K parallel partial aggregates → K parallel final maps (downstream sees K partitions).
- K-shard hash joins (both sides partitioned by join key; probe in parallel).
- K-shard distinct.
- Window: K shards by PARTITION BY columns; rows with the same window
  key all land in the same shard.

## Current state

All breakers hardcode `numPartitions = 1`:
- `AggregateExec.scala:27`
- `JoinExec.scala:28`
- `SortExec.scala:14`
- `DistinctExec.scala:12`
- `WindowExec.scala:24`
- `GlobalLimitExec` in `PhysicalPlan.scala:114` (intentional — limits
  can't be sharded; leave as-is)

The fan-out parallelism inside each breaker comes from
`Scheduler.submitAndAwaitAll` over the *input* partition count, but the
merge collapses to 1.

## Proposed design

### Add an `ExchangeExec`

```scala
final case class ExchangeExec(
    child: PhysicalPlan,
    partitionBy: Seq[Expr],
    numShards: Int
) extends PhysicalPlan {
  def outputSchema: Schema = child.outputSchema
  def numPartitions: Int = numShards

  def execute(partition: Int): Iterator[ColumnarBatch] = {
    // Reads pre-shuffled bucket `partition` from the materialized
    // shuffle. Bucket built lazily on first call to any partition.
  }
}
```

The exchange is computed eagerly on first `execute` call: spin up tasks
over the child's partitions; each task hash-partitions its input batches
into K bucket-buffers; merge bucket-buffers across child partitions;
expose per-shard iterators.

Layout: an in-memory `Array[Array[ColumnarBatch]]` indexed by `(shardIdx,
batchIdx)`. ColumnarBatches stay in their original columnar form — no
row materialization.

### Per-operator integration

#### HashAggregateExec

Before:
```
ScanExec (P partitions)
  → HashAggregate (1 partition)
```

After:
```
ScanExec (P partitions)
  → ExchangeExec by groupKeys (K shards)
  → HashAggregate per-shard (K partitions)
```

The aggregate now runs K times in parallel, each over its own key-disjoint
shard. No merge step needed across shards — each shard's map is final.

Down side: when input partitions are very skewed by key, one shard
gets all the rows. Mitigation: salt the hash with `i % salt` for the
known-skew case (deferred — not in v1 of this change).

#### HashJoinExec

Before:
```
left (PL partitions)            right (PR partitions)
  ↘                           ↙
   HashJoinExec (1 partition, build entire right side)
```

After:
```
left (PL partitions)               right (PR partitions)
  ↓                                  ↓
ExchangeExec by leftKeys (K)    ExchangeExec by rightKeys (K)
  ↘                              ↙
   HashJoinExec per-shard (K partitions, build right shard, probe left shard)
```

Each shard builds and probes independently — no cross-shard
coordination. Outer-join unmatched rows handled per shard.

#### DistinctExec

Identical pattern: exchange by all output columns; dedupe per shard.

#### WindowExec

Exchange by `spec.partitionKeys` (the SQL `PARTITION BY`). Rows with
the same partition key land in the same shard; the window function runs
correctly per shard with no cross-shard coordination.

Caveat: when `PARTITION BY` is empty, the whole result is one logical
partition — fallback to the current single-partition behavior. Don't
exchange when there are no partition keys.

#### SortExec

Range-partition is the correct primitive (so shards have disjoint
ranges and the concatenation is sorted), not hash. Defer this — sort is
less of a bottleneck after plan 01 (k-way merge). Leave `SortExec` on
`numPartitions=1` for now.

### Choosing K

Default: `K = Scheduler.parallelism` (2× cores). Override via
`transformer.scheduler.shard_count` system property or config.

If the input has `numPartitions < K`, still produce K output shards —
small inputs gain nothing from over-sharding but lose nothing either.
If `numPartitions > K`, the exchange consolidates to K. Either way the
exchange is the canonical "K" point.

For very low-cardinality keys (eg. GROUP BY a 2-valued column), most
shards will be empty. Detection requires runtime sampling we don't
want to do — accept it as a real cost.

### NULL handling

Hash NULLs to a deterministic bucket (eg. `hash = -1 → bucket K-1`).
SQL `GROUP BY` groups all NULLs together; this preserves that.

For joins, NULL keys never match, but they must still route to *some*
bucket (so the iterator sees them and produces null-extended rows for
outer joins). Route all NULL-key rows to bucket 0.

### Memory model

The exchange materializes the entire child output across K shards
before any downstream operator starts. This is the same memory the
current `HashAggregate.partialAggregate` already pays — buffering is
just done differently. No new spill semantics are introduced (see plan 09
for spill).

### Compatibility shim

To avoid breaking every operator on day 1, introduce `numPartitions` as
already public and let callers handle K > 1 explicitly. Most callers
are operator-internal; only the writer pipeline at
`DataJob.writeOutput` needs the multi-partition iterator path — it
already supports it (see "Output is always a directory of part files"
in architecture.md §3a).

## Files to touch

- **New**: `src/main/scala/com/transformer/sql/exec/ExchangeExec.scala`.
- **New**: `src/main/scala/com/transformer/sql/exec/HashPartitioner.scala`
  — pure function from `(keyCols, row) → shardIdx`. Reuse the
  KeyCodec from plan 02 for the hash; plan 04 should not land before
  plan 02.
- **Modified**: `PhysicalPlanner.plan` — insert `ExchangeExec` above each
  pipeline breaker. Become responsible for picking K.
- **Modified**: `AggregateExec`, `JoinExec`, `DistinctExec`, `WindowExec`
  — change `numPartitions = 1` to `numPartitions = K`, refactor
  `execute(partition)` to operate on a single shard's data.
- **Modified**: `PhysicalPlan.scala` — `GlobalLimitExec` stays at 1; review
  the `LogicalLimit` planning path to ensure GlobalLimit-above-Aggregate
  still emerges.
- **Modified**: `DataJob.writeOutput` — already multi-partition aware via
  `writePartitioned`; verify no implicit assumption of `numPartitions = 1`
  past a breaker.

## Edge cases

1. **Empty input**. Every shard is empty. Breakers must still emit the
   "empty input → one all-aggregates-zero row" output if applicable
   (`AggregateExec.scala:42-46`). Pick a designated shard (eg. shard 0)
   to emit it; other shards emit nothing.
2. **No GROUP BY** (`SELECT SUM(x) FROM t`). Don't exchange — all rows go
   to one shard. Same for empty PARTITION BY in Window.
3. **Outer joins**. Each shard handles its own unmatched-right rows
   independently (no global state needed). Verify left/right/full
   outer semantics shard-by-shard.
4. **Skew**. A 100M-row GROUP BY where 80% of rows share one key sends
   80% to one shard. No salting in v1; document as a known limitation.
5. **CountDistinct in aggregate**. `CountDistinctState` (`AggregateExec.scala:163-171`)
   maintains a per-key HashSet. With sharding by group key, the
   per-key sets stay correctly scoped. With sharding by something else
   (we don't), it'd be a problem.
6. **Insertion-order semantics**. `LinkedHashMap` in HashAggregate
   preserves group-encounter order. Sharded execution can return groups
   in non-deterministic order across shards. Document the change; any
   downstream sort that depended on encounter-order will need an
   explicit ORDER BY (most plans already do this).
7. **Hashing consistency**. The hash function must be identical on
   build and probe in joins, and across exchanges. Pin it.

## Testing

### Correctness
- All existing SqlEngineTest cases must pass with K > 1.
- Add sharding-specific tests:
  - GROUP BY with K=4, verify result is same as K=1.
  - JOIN with K=4 over multi-column key, including NULL keys.
  - Window with PARTITION BY and K=4.
  - Aggregate with no GROUP BY (K should collapse to 1).
- Property test (manual): for K ∈ {1, 2, 8, 32}, every query should
  produce structurally equal output sets.

### End-to-end
- jaffle_shop: 15/15 Succeeded.
- polymarket: 15/1/1 pattern.

### Performance
- Microbenchmark: 27M-row GROUP BY (Long, String) → measure
  HashAggregate end-to-end with K = {1, 4, 8, 16}.
- Polymarket end-to-end wall time. Target ≥40% reduction (this is the
  whole point — single-threaded post-breaker work is the wall).
- jaffle_shop end-to-end. May not improve significantly (small data),
  but must not regress.

## Risks

1. **Correctness drift in joins.** Outer-join unmatched-right semantics
   per shard are subtle. Mitigation: dedicated tests per join kind ×
   sharded.
2. **Skew leading to OOM on one shard.** Without salting, a single hot
   key still loads one shard. Spark's AQE has skew splitting. We don't.
   Document and defer; the user can usually anticipate skew.
3. **Insertion-order semantics change.** Some users may depend on
   `LinkedHashMap` ordering for `GROUP BY` output. After this change,
   output ordering across shards is non-deterministic. Mitigation:
   document; recommend explicit ORDER BY downstream.
4. **Multi-partition output increases part-file count.** Today a breaker
   collapses to 1 output partition, so writers emit one part file. After,
   K shards → K part files. `OutputFilePath.maxPartitions` already
   caps this; just verify it still works.
5. **Memory amplification.** The exchange materializes child output
   fully. If child is large and was previously consumed streamingly by
   the breaker, we now buffer twice. Mitigation: have the breaker
   consume directly from its child while sharding inline, instead of
   going through a separate ExchangeExec. The standalone exchange
   operator is conceptually cleaner but may be a perf trap.
6. **Hashing cost.** Computing `hash(key) % K` per row is itself an
   `Expr.eval`-equivalent cost. Plan 03 (vectorized eval) and plan 02
   (packed keys) should land first so the hashing is fast.
7. **Implementation complexity.** This is the biggest change in the
   suite. Plan to spend the early phases stabilizing one operator
   (HashAggregate) end-to-end before moving to the others.

## Suggested phases

1. **Phase 1**: prototype `ExchangeExec` as a standalone operator. Tests
   for the exchange itself.
2. **Phase 2**: integrate into `HashAggregateExec` only. Make
   GROUP BY produce K-partition output. All other breakers untouched.
   Confirm jaffle + polymarket green.
3. **Phase 3**: integrate into `DistinctExec`.
4. **Phase 4**: integrate into `HashJoinExec`. Outer join correctness is
   the hardest part.
5. **Phase 5**: integrate into `WindowExec` (when PARTITION BY is non-empty).
6. **Phase 6**: planner-side decision logic — when to insert ExchangeExec
   vs collapse to 1, based on estimated input size.
7. **Phase 7 (optional)**: range-partition for SortExec. Coordinate with
   plan 01.
8. **Phase 8 (optional)**: skew-handling salting.

Strongly prefer landing this **after** plans 02 (packed keys) and 03
(vectorized eval) — the hashing cost depends on both.

## Docs to update

- `docs/architecture.md` §2 — replace the "Pipeline breakers collapse to
  numPartitions = 1" passage with the new model.
- `docs/architecture.md` §3 (Parallel execution) — describe how
  Exchange interacts with the shared Scheduler pool.
- `docs/architecture.md` §6 (Window functions) — note that windows now
  fan out by PARTITION BY.
- `docs/gotchas.md` — remove the "always collapse to 1" claim; add a
  note on skew.
- `docs/conventions.md` — pattern for new operators that may need to
  shard.
- `docs/extending.md` — recipe for plumbing a new breaker through
  Exchange.
- `docs/code-map.md` — add `ExchangeExec`, `HashPartitioner`.
- `docs/testing.md` — new test targets.

## Launch prompt

```
Read plans/perf/04-hash-partition-breakers.md and implement it end-to-end.

PREREQUISITE: plans 02 (packed keys) and 03 (vectorized eval) must be
landed first — hashing cost depends on both. If they aren't done, stop
and ask before proceeding.

This is the most invasive change in the perf workstream. Use max effort.
Honor CLAUDE.md: no new heavy deps, bazel test //... must pass after
EVERY phase, jaffle_shop deploy jar must hit 15/15 Succeeded after every
phase, polymarket deploy jar must hit 15/1/1. Land docs in the same PRs.

Follow the 8 phases. Phases 2 (HashAggregate) and 4 (HashJoin) are the
landmark phases — pause after each for a separate PR review and
performance numbers before moving on.

Spawn parallel sub-agents for: (a) property-testing K ∈ {1, 2, 8, 32}
equivalence in parallel with implementation, (b) profiling memory
pressure during the exchange materialization.

Stop and ask before: (a) changing the shape of CatalogView or
PhysicalPlan, (b) adding any skew-handling logic (defer to a separate
plan), (c) range-partitioning SortExec — that's Phase 7 and only after
the rest is stable.

Include in each PR description: before/after wall time on polymarket
end-to-end, K-by-K scaling chart (wall time vs K), async-profiler flame
graph confirming downstream operators are now running parallel.
```
