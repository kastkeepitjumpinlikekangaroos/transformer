# Plan 13: Stream a hash join's output

> Status: **done, all six steps** (landed 2026-11-03 in one commit on
> `b14cdd5`, an overnight run; see "What landed" at the end). Written
> 2026-11-01 against `a408d07`, which has `TopNExec`; plan 14 landed between
> the writing and the work, so the passages on one-stream consumers and Step 5
> carry notes on what it changed · six decisions for the user below, each
> taken overnight on its recommended answer, none yet confirmed by the user ·
> Effort: 4-6 days · Risk: medium: the join becomes the second operator whose
> shared result the K>1 liveness pass publishes, and the number of partitions
> (so of part files) a broadcast join returns changes

## Goal

A hash join holds its build side and a bounded amount of output, never its
whole result, whatever the number of rows it returns. A join under an
aggregate, a writer or a LIMIT then streams as a scan or a filter does.

Not the goal: overlapping breaker CPU with writer I/O (plan 08's motivation),
or bounding the build side (grace hash does that, with spill on).

## Why

Measured 2026-11-01 (JDK 21, `-Xmx2g`, Apple M4, 8 cores, 16 GB):

```sql
SELECT m.category, COUNT(*), COUNT(DISTINCT ob.market_id), AVG(ob.spread)
FROM ob JOIN markets m ON ob.market_id = m.condition_id
GROUP BY m.category ORDER BY 2 DESC LIMIT 8
```

over one polymarket orderbook day (`ob`: 326M rows, 2.1 GB of parquet, 18
partitions) and 124K markets ran out of the 2 GB heap after 163 s;
119,852,314 rows match. EXPLAIN VERBOSE, rerun for this plan:

```
HashAggregate: GROUP BY category; COUNT(*) AS _agg0, ... [1 partition]
  HashJoin INNER: market_id = condition_id; builds the right side [1 partition]
    Scan ob: market_id, spread [18 partitions]
    Scan markets: condition_id, category [1 partition]
```

The same answer computed aggregate-first, then joined, ran in 10 s at 0.55 GB
peak RSS, and an aggregate over all 5.5 billion orderbook rows (38 GB) runs in
110 s at 1.1 GB. Only the join holds its result; README's Limitations says so
("A join holds its whole result in memory"). plans/testing/05 W5 left keyed
joins off the streaming pairing because "their output is about their input";
this query is the counterexample, since the output is about the probe side,
which is the large side.

The repo holds a second case: `SpillStressTest.hashJoinStressEquiJoinPreservesEveryMatchedPair`
joins 50,000 by 30,000 rows on 23 keys, 65,217,392 joined rows, through the
grace path with spill on, which appends every one to `out` before its first
batch.

## What the code does now

`HashJoinExec.execute` (`sql/exec/JoinExec.scala`) picks one of four paths.
The class scaladoc still describes two, and calls the collapsing one
"non-equi", which it no longer is.

| Path | Taken when | Partitions | Holds before its first batch |
|---|---|---|---|
| `executeGraceHash` | `graceEnabled`: spill on, equi keys, no `RAND()` key | 1, K when sharded | both sides in 16 bucket files each, then every bucket's output in one `out` |
| `executePerShard` | both children are `ExchangeExec`s (smaller side estimated at or above `BroadcastBuildThreshold`, 1M) | K | the shard's build rows and whole output, built and probed eagerly in `execute(p)` |
| `executePairing` | no equality (`nKeys == 0`) | 1 | the build side and one wave: it streams |
| `executeCollapsing` | every other keyed join, the broadcast case | 1 | the build side and the whole output |

- **Build.** `buildSideAcrossAllPartitions` fans `collectBuildPartition` out over
  the build partitions; `appendBuildBatch` boxes each row into an `Array[Any]`;
  `buildLongKeyMap` (`LongHashMap`) or `buildCodecKeyMap` indexes them, NULL keys
  in `rows` but not the map. The `BuildSide` is read-only once built, and the
  parallel probe already reads it from many threads.
- **Probe.** `probeAcrossAllPartitions` submits `probeOnePartition` for every
  probe partition; `probeBatchInto` boxes a probe row once it matches, allocates
  an `Array[Any]` per output row (`mergeMatch`), evaluates `extra` per candidate
  through a `RowView`, and appends to a local buffer. The results are
  concatenated in partition order, copied by `.toArray`, and `rowsToBatches`
  writes each cell with `setBoxed`. All of it runs inside `execute(0)`: for a
  join at the root, inside `SqlEngine.execute`, which calls `execute(p)` for
  every partition before it returns.
- **Outer joins.** Unmatched probe rows (`preserveProbeOuter`) are null-padded
  inline. A join that keeps unmatched build rows (`preserveBuildOuter`) collects
  matched build indices in a `util.HashSet[Int]` and appends `emitUnmatchedBuild`
  after the probe. With `PhysicalPlanner.shouldBuildRight` (INNER by size, LEFT
  builds right, RIGHT builds left, FULL builds right) only FULL keeps unmatched
  build rows in a planned query; a direct construction can make any kind do so.
- **Grace.** `executeGraceHash` routes each side's partitions one after another
  (`bucketSide`, no fan-out) into 16 parquet buckets, runs `processBucketPair`
  for every bucket into one `out`, closes the spill directory, and emits. Spill
  bounds a join's build per bucket, not its output.
- **Pairing, the model.** `executePairing` builds once, then advances up to
  `Scheduler.parallelism` `PairingCursor`s a wave (a probe partition's iterator,
  opened on first use, its batch and row) by as many probe rows as pair about
  `PairsPerTask` (32768) rows; each wave's output is emitted before the next
  reads on, matched sets merge on the consumer thread, and the unmatched build
  rows follow the last wave. A cursor changes threads only across a wave's join.
- **Metrics.** `probeNanos` brackets the probe fan-out (wall time); grace's
  `bucketsLoadedNanos` brackets all bucket work, probing included, where
  docs/benchmarking.md says loading.

Who reads a join's partitions, as far as the code shows. In parallel, one pool
task a partition: `HashAggregateExec`'s collapsing paths, `SortExec`,
`DistinctExec`, `TopNExec`, `ExchangeExec.materialize`,
`MaterializedView.materializeInParallel` and `fromQuery` (CTE bodies, a task
result without an output file), and both writers' `writePartitioned` through
`DataJob.writeOutput` (`coalescedPartitions` caps at `maxPartitions`; Parquet
skips an empty partition, CSV writes a header-only part for it). One after
another: `GlobalLimitExec`, `OffsetExec`, `WindowExec`'s collapsing non-spill
path (`flatMap(child.execute)`), `ExecutedQuery.batches` (validations,
`transformer query`'s `ResultPrinter`, `COPY` and `--output` through
`writeAll`), and the GUI's `QueryConsole`. (Plan 14, landed 2026-11-03 before
this plan's work began, moved `transformer query`'s printing, `COPY`,
`--output` and `QueryConsole` onto the pool: `ParallelDrain.eachPartition`
reads a table, markdown and the console's result a partition a task, and
`ParallelDrain.inOrder` the ordered formats, `COPY` and `--output`, with a
read-ahead of one chunk a partition. Validations still read `batches`.)

## Design

### Recommended: pull each partition, publish the build side first

1. **A resumable probe cursor** (`ProbeCursor`) replaces the loops of
   `probeOnePartition`, `PairingCursor` and `probeBucket`, and `probeBatchInto`'s
   body moves into it, so match semantics stay in one place. It holds a probe
   iterator (opened on its first fill), the batch and its key vectors (evaluated
   once a batch), the row, the place in that row's match list, whether the row
   kept a match, an optional matched-build `java.util.BitSet` and its tallies
   (recorded per fill, so a partly drained partition reports what it probed). A
   fill stops at a row limit (an output batch, `DefaultCapacity`, for the
   broadcast, shard and grace paths) or a work limit (`PairsPerTask` units for a
   wave's task, a unit a probe row read or a candidate pair examined, so output
   never exceeds it), mid-row if need be: a probe row matching ten million build
   rows emits them a batch at a time. Until Step 6 the output is boxed rows in a
   buffer of one batch, made a batch by `rowsToBatches` as now.
2. **The shared build side** of a broadcast join is built once and published
   before any partition iterator exists, by the pass that does this for
   exchanges. `PhysicalPlanner.preMaterializeExchanges` becomes `preMaterialize`
   and matches a small trait (`SharedResult`, say, with `private[exec] def
   materializeNow(): Unit`) that `ExchangeExec` and `HashJoinExec` extend; a
   join's builds when the join runs in its probe side's partitions, and does
   nothing otherwise. `ExchangeExec.ensureMaterialized`'s CAS claim, latch and `claimer`
   reentrancy escape move into one helper both use, so a directly-constructed
   join's lazy fallback is the exchange's proven code. The fallback builds in
   `execute(p)` on the calling thread, not on the first `hasNext`: a caller that
   skips the pass calls `execute(p)` on one thread (as `SqlEngine` and the tests
   do), where deferring would race the build from the consumer's pool tasks.
   Never a Scala `lazy val` or `synchronized`: either holds the operator's
   monitor across the build's pool fan-out, the plans/bugfixes/02d footgun. Never
   built at construction or planning, so EXPLAIN still reads nothing.
3. **Partitions.** `numPartitions` stays a function of the children, since
   EXPLAIN and the planner's LIMIT choice read it before anything runs: K when
   sharded; 1 for grace when not sharded, for a pairing, and for a join that
   keeps unmatched build rows; otherwise `probePlan.numPartitions`, which can be
   0 (a scan of 0 partitions exists today and every consumer handles one).
4. **Output, path by path:**

| Path | Partitions | Holds |
|---|---|---|
| Broadcast, no unmatched build rows (planned INNER, LEFT, RIGHT) | the probe side's | the build side, and a probe batch and an output batch per partition being pulled |
| Broadcast keeping unmatched build rows (planned FULL) | 1, in waves | the build side, one wave (`Scheduler.parallelism` × `PairsPerTask` rows, 524,288 at 16 threads), a BitSet per cursor |
| Pairing (no equality) | 1, in waves (Q6) | as today |
| Sharded | K, each lazy: build shard s on the first pull, stream probe shard s, then its unmatched build rows | the two exchanges, which hold both inputs (unchanged), and per shard being pulled its build and an output batch |
| Grace | 1 (K sharded), lazy: bucket both sides on the first pull, then per bucket load build_k, stream probe_k, emit build_k's unmatched rows | one bucket's build, 16 pending batches a side while bucketing, an output batch; both inputs on disk |

### Deadlock safety

Every wait the new code has, after the pass:

1. **The pass's build fan-out** (`buildSideAcrossAllPartitions`), on the drain
   choke point's thread (`SqlEngine`'s two execute paths,
   `CteResolver.materialize`), in post-order, so every exchange and build below
   it is already published: a pure task tree, which `ForkJoinTask.get()`
   work-helping completes (`core:scheduler_test`). The 02f argument, unchanged.
2. **A wave** (FULL joins, pairings): `submitAndAwaitAll` over cursors reading
   their own probe partitions, a pure tree, as W5's waves are today.
3. **Nothing else.** A broadcast partition's iterator, a shard's and a grace
   bucket's fan nothing out and wait on nothing; the build is read-only after
   its volatile publication.

The 02e wedge needs a task parked on a result only a frame beneath it can
produce. With no readiness wait left in engine execution, helpJoin inlining one
task beneath another can delay a thread but closes no cycle. Pulling also leaves
no producer to block or abandon: work happens on the thread that pulls, so a
consumer that stops (LIMIT, a closed pipe, Ctrl-C, another partition's failure)
leaves nothing running. The lazy fallback does wait, on the latch, with the
exchange's documented hazard: docs/conventions.md's "Never let a pool task wait
on a lazily-shared breaker result" now covers joins, and only plans run without
the pass reach it (`JoinOrderTest` today; it gets the pass). Refusing such a
build on a `ForkJoinWorkerThread` would make a rare wedge an error but also
refuse a safe caller; recommended: mirror the exchange, as 02f left it.

### Joins that keep unmatched build rows

Their unmatched build rows are known only when every probe partition is done.

- **A. One partition, in waves (recommended).** The pairing's waves with the keyed
  cursor; each cursor owns a BitSet, and the tail ORs them after the last wave.
  No state crosses partitions, a second execution is safe, and its only wait is
  a wave (2 above). Costs: the operator above a FULL join stays single-threaded,
  as today, and rows of different probe partitions interleave in wave chunks.
- **B. The last finisher emits.** Each partition publishes its BitSet into a slot
  and decrements a counter; the one that reaches zero ORs them and emits the
  tail. No waits, so no deadlock, and the partitions stay parallel. But it is
  right only if each partition of the instance runs exactly once, which the
  operator cannot enforce: a shared subtree (`UnionExec(j, j)`, the diamond the
  pass dedups) runs each twice, and an early finisher's tail then names build
  rows a later partition matches. The tail also lands in an arbitrary partition.
- **C. A trailing partition that waits for the others.** A readiness wait in a
  pool task, which docs/conventions.md forbids. Rejected.

### Rejected here: a bounded producer/consumer queue

Plan 08's `StreamingResults` (producers `put` into an `ArrayBlockingQueue`, the
iterator `take`s) bounds memory too, but adds a wait that follows no task tree:
a work-helping consumer can inline a producer of its own queue, which blocks on
`put` beneath it, 02e's shape. It also needs cancellation (producers parked for
an abandoned consumer hold workers) and error propagation, and interleaves
batches. Its one gain, a parallel probe under a one-stream consumer, is
throughput, not memory (Q3).

### Columnar output (last step)

Step 6 drops the boxed rows: the build side compacted into one `ColumnarBatch`;
the cursor writing selection vectors (probe row, build row, -1 for a null-padded
side); a column-at-a-time gather (a new `ColumnVector` method; only per-cell
`copyTo` exists) packing output batches; `extra` as `evalVec` over a batch of
candidates (`expr_parity_fuzz_test` holds `evalVec` to `eval`). Output stays in
probe order, and keys are encoded exactly as now (same codec and types, bound to
the build columns for ColRef keys, pre-encoded for computed ones).

## Visible changes and the decisions they need

| What | Before | After |
|---|---|---|
| Partitions of a planned INNER, LEFT or RIGHT broadcast join | 1 | its probe side's (18 for an orderbook day) |
| Part files of a job table over such a join | 1 | one per partition, capped by `maxPartitions` |
| EXPLAIN VERBOSE's join line | `[1 partition]` | `[18 partitions]` |
| Flat row order (partitions in order) of a broadcast join | probe order | the same |
| A FULL join's row order | probe order, then unmatched build rows | wave chunks, then unmatched build rows |
| A DOUBLE aggregate over a join | one partial | a partial per probe partition, merged, as over a multi-partition scan: last digits may differ |
| Where a probe-side failure surfaces | mostly in `SqlEngine.execute`, which ran the eager probe | where its partition is drained (a build failure stays in `SqlEngine.execute`, through the pass) |
| LIMIT without ORDER BY over a join | the whole join first | stops after n rows; an endless probe side now returns |
| `probeNanos`, `bucketsLoadedNanos` | wall time of the fan-out; of all bucket work | probe-loop time summed over partitions; time loading build buckets |
| An abandoned grace join's spill files | none (processed whole first) | kept until drained or the JVM exits, as other spilling operators' |

The work proceeds on the recommended answer to each question unless the user
says otherwise:

1. **Should an INNER, LEFT or RIGHT broadcast join return its probe side's
   partitions?** Recommended: yes. Aggregate partials, writers, sorts, top-n and
   CTE materialization above it run per partition, LIMIT stops early, and a job
   table over it writes a part file per partition (`maxPartitions: 1` folds them
   to one). Or (b) at most `Scheduler.parallelism` partitions of grouped probe
   partitions (fewer files, worse balance), or (c) one partition in waves (no
   file-count change, no early stop, the operator above single-threaded; no
   Step 2).
2. **A FULL join:** recommended A, one partition in waves; B and C above are
   the alternatives.
3. **Consumers that read one stream** (`transformer query` printing, `COPY`,
   `--output`, OFFSET without LIMIT, a validation whose root is a join) probe a
   partition at a time. Recommended: accept it, as README's "transformer query
   reads a result one partition at a time" does for scans, and have the two that
   only materialize (`WindowExec`'s collapsing path, `QueryConsole`) drain in
   parallel (Step 5). Or: an ordered, bounded parallel drain here, plan 08's
   queue narrowed, with its deadlock analysis. (Plan 14 then landed that
   drain, without a queue: `transformer query`'s table and markdown read a
   join's partitions at once, and its ordered formats, `COPY` and `--output`
   read ahead one chunk a partition, so they probe mostly the partition being
   written; `QueryConsole` already drains in parallel. What stays one
   partition at a time: OFFSET without LIMIT, and a validation's rows.)
4. **Columnar output:** recommended in this plan as its last step, landed only if
   it measures faster; or a plan of its own.
5. **The two timers:** recommended as the table says, written into
   docs/benchmarking.md; or wall time from a partition's first batch to its
   last, which counts the consumer's time between pulls.
6. **The pairing join:** recommended unchanged, one partition in waves (its cost
   is per candidate pair, and waves keep a one-stream consumer parallel),
   sharing only the cursor; or the keyed rule (probe partitions unless it keeps
   unmatched build rows).

## Work, in landable commits

Every commit: `bazel test //...` green, the jaffle-shop jar 15/15 with every
validation passing (its inputs are single CSV files, so its joins keep one
partition and its tables one part file; check), the polymarket jar exiting 0
on its pattern (14 succeeded, 2 failed validations, 1 skipped as of
plans/testing/05), and every doc it makes stale updated in the same commit.
Steps 1, 3 and 4 need no decision; Step 2 waits on Q1.

**Step 1: the cursor, and FULL joins in waves.** `ProbeCursor`, which every
path probes through (collapsing, per-shard and grace still drain it whole until
their steps); BitSet matched sets; a broadcast join keeping unmatched build rows
runs in waves (`executeInWaves`, shared with the pairing, whose `rowsPerTask`
gives way to the cursor's bound). Tests: the equivalence matrix, the FULL
bounded-memory case, the existing join tests unchanged. Docs: the
`HashJoinExec` scaladoc, architecture §8, gotchas' non-equi bullet, code-map.

**Step 2: broadcast joins run in their probe side's partitions.** The trait,
the helper, `executeBroadcast`, and `preMaterialize` at every caller of the old
name (`SqlEngine` twice, `CteResolver.materialize`, and the tests
`ExchangeDeadlockStressTest`, `ExchangeExecTest`, slt `Modes`, `PlanSqlTest`,
`PlanRoundTripFuzzTest`), plus `JoinOrderTest`, which runs plans without it.
`JoinSwapTest.equiJoinSmallSidesSkipsExchange` and
`SqlEngineTest.equiJoinOnSmallCsvUsesBroadcastPath` assert one partition only
because their probe has one: assert the probe's count over several. New:
`SqlEngine.execute` alone reads a counting build view once per partition and
the probe not at all; a diamond `UnionExec(j, j)` builds once and answers
twice; the pass reaches a `MeteredPlan`-wrapped join; EXPLAIN VERBOSE shows `[3
partitions]` over `ExplainTest`'s unreadable views; LIMIT over an endless probe
(QuerySessionTest's `withEndless`) returns, which today it never does;
cancelling stops a join's probe at its next batch; a CTE body's join builds
once; the INNER, LEFT and drained-flat bounded-memory cases; the stress test,
whose inner joins now publish builds, green. Measure. Docs: architecture §2,
§2b, §3, §3a, §3g, §8; conventions (publish first, the trait, no `lazy val`);
gotchas (the K>1 bullet, the lazy fallback); extending.md (the trait);
testing.md; README (Limitations, join planning); plans/perf/08's status.

**Step 3: sharded joins stream per shard.** `executePerShard` lazy, a BitSet
tail per shard. Tests: direct construction over two `ExchangeExec`s against
the oracle; `sharded_mode_fuzz_test`; `--config=sharded`; the sharded
bounded-memory case. Docs: architecture §2b, gotchas.

**Step 4: grace streams bucket by bucket.** Bucketing on the first pull,
bucket iterators chained, `spillDir.closeOnDrain`, `bucketsLoadedNanos` around
loading only. Tests: `hash_join_spill_test`; the spill directory gone after a
drain; SpillStressTest's 65M-row join in the bounded heap. Docs: architecture
§2c, README Limitations (spill bounds a join's result too), benchmarking.md.

**Step 5: consumers that read a join's partitions one after another.**
`WindowExec`'s collapsing non-spill path and `QueryConsole` drain partitions in
parallel into buffers concatenated in partition order, so a window's ties and
the console's rows come out as before. Tests: a window over a multi-partition
join; the GUI tests that run the console. Docs: architecture §6, README.
(Plan 14's Step 3 already put `QueryConsole` on `ParallelDrain.eachPartition`,
results in partition order, so this step is the window's, through the same
drain.)

**Step 6: columnar output (Q4).** Tests: all of the above; a `core` test of the
gather for every vector type with NULLs, `NullType`, DECIMAL and BINARY. Docs:
architecture §2 (`RowBuf`) and §5b, gotchas' "What's intentionally NOT done"
(join callbacks no longer boxed), code-map.

## Tests

- **Equivalence matrix**, new, PairingJoinTest's nested-loop oracle extended to
  keys: every kind × both build sides × key path (one long, packed multi-key, one
  string, object multi-key, computed `l.k + 1 = r.k`) × with and without `extra` ×
  NULL keys on both sides × empty build, empty probe, a 0-partition probe and
  empty partitions, over 1, 3 and 5 partitions; each path forced (broadcast via
  `SqlEngine`, FULL, sharded by direct construction over `ExchangeExec`s with
  K = 4, grace with `spillEnabled`). Multisets equal the oracle's; broadcast INNER
  and LEFT also match its probe order. Run under `batch1`, `batch2` (every output
  batch boundary falls mid-match) and `hashzero` (every key collides).
- **Bounded memory.** PairingJoinTest's file and target widened to every
  streaming join (`streaming_join_test`, `-Xmx256m` kept): a probe view whose
  batches are built on the fly, a few million rows on 100 keys, against a build
  of a few thousand, so each case returns 50 to 100 million rows of four columns,
  several GB boxed: INNER under an aggregate, LEFT with unmatched keys, INNER
  drained flat, FULL, sharded (inputs small enough for the exchanges), grace.
  Each asserts its counts. 100 keys keep `hashzero` fast;
  `assumeTrue(ColumnarBatch.DefaultCapacity >= 1024)`, as docs/testing.md asks
  of a test that depends on a default.
- **Liveness.** `core:scheduler_test` untouched; `exchange_deadlock_stress_test`
  green in its default mode (`STRESS_LAZY=1` stays its red instrument);
  `dag_scheduler_fuzz_test`, and `starved_dag_scheduler_fuzz_campaign` once,
  since a join's probe moves into writer tasks.
- **Fuzzers and slt.** `mode_differential_fuzz_test`, `metamorphic_fuzz_test`
  (join commutativity flips the build side; its layouts give 1, 4 and 5
  partitions, some empty; its spill mode drives grace), `sharded_mode_fuzz_test`,
  `plan_roundtrip_fuzz_test`, `slt_test`'s thirteen modes; then
  `metamorphic_fuzz_campaign` and `sharded_mode_fuzz_campaign` at
  `FUZZ_SEEDS=20000`, 02e's volume, as docs/testing.md says. Optional: a tally of
  the join path each metamorphic query took, read from its physical plan, so the
  corpus cannot stop reaching one unseen.
- **Whole suite** under each `.bazelrc` config: `batch1`, `batch2`, `sharded`,
  `serial`, `zone`, `hashzero`.
- **Measurement**, after Step 2 and again after Step 6, with `JDK21` the
  remotejdk21 under `$(bazel info output_base)/external/` (the system `java` is
  17):

  ```bash
  bazel build //:transformer_deploy.jar
  /usr/bin/time -l "$JDK21/bin/java" -Xmx2g -jar bazel-bin/transformer_deploy.jar query \
    --table ob=$HOME/Downloads/archive/orderbook/orderbook_2026-03-06.parquet \
    --table markets=$HOME/Downloads/archive/labels/market_targets.parquet \
    "SELECT m.category, COUNT(*), COUNT(DISTINCT ob.market_id), AVG(ob.spread)
     FROM ob JOIN markets m ON ob.market_id = m.condition_id
     GROUP BY m.category ORDER BY 2 DESC LIMIT 8"
  ```

  Expected: it completes with the aggregate-first form's answer (AVG's last
  digits aside); record wall time and peak RSS beside that form's 10 s and
  0.55 GB (it probes 120M matches, so a small multiple, not parity). Also
  `SELECT ob.market_id, m.category FROM ob JOIN markets m ON ob.market_id =
  m.condition_id LIMIT 10` (expected: the build's time and a probe batch) and
  EXPLAIN VERBOSE (`[18 partitions]`).

## Risks and open questions

- **A one-stream consumer probes a partition at a time (Q3).** A join at the root
  of a printed query, a `COPY`, an OFFSET or a validation's rows loses the
  parallel probe `probeAcrossAllPartitions` gave it. Its printer or single file
  is one stream anyway; measure a `COPY` of a large join before and after Step 2.
  (Measured: see "What landed". With plan 14's read-ahead a `COPY` probes the
  partition it writes and one chunk of each partition ahead, and a selective
  join's chunk is many probe batches, so it ran about twice as long as the
  eager parallel probe did, in less than half the memory.)
- **A build side lives longer.** The pass builds each broadcast join's side in
  `SqlEngine.execute` and the operator keeps it until the result is dropped
  (`DataJob` holds `q` through the validations); today it is garbage once
  `execute(0)` returns, unless the join keeps unmatched build rows. Bounded by
  the broadcast threshold when both sizes are known; a side of unknown size
  (CSV) builds large today too.
- **DOUBLE aggregates over a join** merge per-partition partials, so a test
  comparing one exactly can see its last digits move. The fuzzers tolerate
  doubles; check jaffle's validations and slt aggregates over joins.
- **A failing condition under LIMIT.** Which rows a partition, wave or batch
  evaluates before a LIMIT stops decides whether an overflowing `extra` fails, as
  gotchas.md says of join order; Step 6 evaluates a candidate batch at once.
- **`JoinSwapTest.buildRightFrom`** reads the one Boolean in `HashJoinExec`'s
  product: add no Boolean constructor parameter. A probe of thousands of
  partitions (a CSV glob of thousands of files) makes a join of as many, as a
  scan of it already is.
- **What this plan does not fix.** Build sides: a RIGHT join builds its left side
  whatever its size, so `ob RIGHT JOIN markets` builds the 326M-row orderbook
  (EXPLAIN VERBOSE says "builds the left side"). Exchanges, which hold both
  inputs of a sharded join and anything a sharded operator above a join reads.
  CTE materialization and `fromQuery`, which hold results by design. Sorts
  without spill, windows, grace's hot-key bucket (v1.1).
- **Open:** once a join keeping unmatched build rows streams, LEFT and RIGHT
  joins could build by size instead of by kind (Spark 3's shuffled hash join
  builds either side of an outer join): a follow-up. Whether `PairsPerTask`'s
  32768 units suit keyed waves too: measure a FULL join of the polymarket tables
  before changing it.

## Relation to plan 08

Plan 08 (not started, gated on a profile) puts a bounded queue between parallel
producers and a breaker's iterator to overlap breaker CPU with writer I/O. This
plan's motivation is memory, reached without a queue, and it supersedes plan
08's HashJoinExec phases (2 and 3): with a broadcast join in its probe side's
partitions each writer task pulls its own partition, so probing and writing
overlap anyway, and the unmatched-build barrier plan 08 gated with a latch is
the waves' tail. Plan 08's Distinct, Window and HashAggregate phases and its
profiling gate stand; its queue narrows to Q3's follow-up, an ordered drain for
one-stream consumers, which must answer this plan's deadlock analysis. Its
plans/perf/README.md row ("nothing under `sql/` streams") has been stale since
plans/testing/05 W5's pairing waves.

## What landed (2026-11-03, overnight)

All six steps, in one commit on `b14cdd5` (the overnight run's orchestrator
asked for one commit for the plan, not one a step). Nothing on the
stop-and-ask list was crossed: no queue, `ExchangeExec` changed only by its
claim and latch moving into the shared helper, the build-side choice, the
sharding gates and `PairsPerTask`'s 32768 unchanged, and Step 6 landed on its
measurement.

- **Step 1.** `ProbeCursor`, the one probe every path reads through: a probe
  stream opened by its first fill, the batch it is in and its key vectors
  (evaluated once a batch), the next row, and for the row being matched its
  candidates, the next to examine and whether one kept it. A fill stops at a
  full output batch or at `PairsPerTask` units, mid-row if need be. Matched
  build rows are a `BitSet` per cursor. A join that keeps unmatched build rows
  and a pairing run in waves (`executeInWaves`), on one partition, each wave
  up to `Scheduler.parallelism` cursors advanced by at most `PairsPerTask`
  units, the cursors' `BitSet`s merged on the consumer's thread as each
  finishes, the unmatched build rows after the last wave.
- **Step 2.** `sql/exec/SharedResult.scala`: the trait `SharedResult`
  (`materializeNow`) and `SharedOnce`, which holds `ExchangeExec`'s CAS claim,
  latch and `claimer` escape, moved there unchanged, and a broadcast join's
  build side. `PhysicalPlanner.preMaterializeExchanges` is
  `preMaterialize`, matching the trait, at every caller (`SqlEngine` twice,
  `CteResolver.materialize`, and the tests `ExchangeDeadlockStressTest`,
  `ExchangeExecTest`, slt `Modes`, `PlanSqlTest`, `PlanRoundTripFuzzTest` and
  the forced-plan oracle's `ForcedPlans`); `JoinOrderTest` runs it now. A
  broadcast join (`executeBroadcast`) runs in its probe side's partitions;
  `numPartitions` stays a function of the children. The chaos points
  `exchange-claim` and `exchange-publish` are `shared-claim` and
  `shared-publish`, since both shared results pass them.
- **Step 3.** A sharded join builds shard `s` on its first pull and streams
  it, then the shard's unmatched build rows.
- **Step 4.** Grace hash buckets both sides on its first pull, joins the
  bucket pairs one after another as its output is pulled (`Closing.concat`),
  and its directory goes when the output is drained or closed
  (`closeOnDrain`); `bucketsLoadedNanos` times loading only.
- **Step 5.** `WindowExec`'s collapsing non-spill path reads its child's
  partitions through `ParallelDrain.eachPartition`, reused through a new
  overload over a plan's partitions (each opened when a task takes it and
  closed once read) rather than a second mechanism: it fits the liveness
  rule, since its tasks wait only on their own operators' fan-outs, a tree,
  and the window waits in `Submitted.await`. Each partition's rows and keys
  are collected into a buffer of its own and concatenated in partition order.
  Its stop is a flag of its own or the query's. `QueryConsole` already read a
  result through `eachPartition` (plan 14's Step 3), so it is unchanged.
- **Step 6.** The build side is one batch, its partitions' batches
  compacted, with each key's rows in an unboxed `IntList`; ColRef build keys
  are encoded from it through a codec bound to the build columns, computed
  ones per batch as before. The cursor writes selection vectors (probe row,
  build row, -1 for a NULL-padded side) and each output batch gathers its
  columns by them (`ColumnVector.gather`, new: a column at a time, unboxed
  for a source of its own class, cell by cell otherwise). `extra` is
  evaluated with `evalVec` over a chunk of candidate pairs of one probe
  batch, at most an output batch's room, the columns it reads gathered for
  them. It measured faster (below), so it landed.
- **Tests.** `sql/exec:streaming_join_test` (`PairingJoinTest`'s file and
  target, widened, still at `-Xmx256m`): the equivalence matrix (4800 joins
  built directly on every path against a nested loop, broadcast in its probe
  order, and the same tables through the planner), the shared build side
  (`SqlEngine.execute` reads the build side once and no probe row, a diamond
  builds once and answers twice, the pass reaches a metered join, 32 racing
  first callers build once, a failed build reaches every caller and is not
  retried), a window over a join keeping its ties in partition order, and
  the bounded-memory cases (INNER under an aggregate, LEFT with unmatched
  keys, INNER read flat, FULL in waves, per shard, grace hash: 50 to 80
  million joined rows each) beside the pairings. `explain_test` (`[3
  partitions]`), `cli_test` (a `LIMIT` over a join of an endless table
  returns; cancelling stops a join's probe), `cte_materialization_test` (a
  CTE body's join builds once), `closing_test` (a `LIMIT` over a join closes
  its probe side; a grace join drained, stopped by a `LIMIT` or closed part
  way leaves no directory), `core_types_test` (`gather` over every type,
  NULLs, `NullType`, `DECIMAL`, `BINARY`, and a source of another class);
  `spill_stress_test` now runs at `-Xmx512m`, its 65M-row grace join
  streaming through it.
  `JoinSwapTest.equiJoinSmallSidesSkipsExchange` and
  `SqlEngineTest.equiJoinOnSmallCsvUsesBroadcastPath` assert the probe's
  three partitions. The forced-plan oracle names the paths `broadcast` and
  `in waves` (was `collapsing`), and its guard holds `in waves` to a floor of
  its own (120; 240 joins over the guard's 600 seeds).

### Overnight decisions

Taken overnight as recommended, not yet confirmed by the user:

- **Q1** (a broadcast INNER, LEFT or RIGHT join runs in its probe side's
  partitions): yes. A job table over one writes a part file per probe
  partition (`maxPartitions: 1` folds them).
- **Q2** (a FULL join): A, one partition in waves.
- **Q3** (one-stream consumers): accepted. Plan 14 had already landed the
  ordered drain, so `transformer query`'s formats read a join's partitions on
  the pool; a `COPY` of a join probes mostly the partition it writes
  (measured below).
- **Q4** (columnar output): in this plan as Step 6, landed only because it
  measured faster.
- **Q5** (the two timers): as the table says, in docs/benchmarking.md:
  `probeNanos` the probe loop's time summed over partitions and wave tasks,
  `bucketsLoadedNanos` the time loading build buckets.
- **Q6** (the pairing): unchanged, one partition in waves, sharing the
  cursor.

Calls of my own, for the user to look at:

- **A fill stops at a full output batch**, as the design says: a `LIMIT` over a
  join reads one output batch's worth of probe batches (for the endless
  table of four-row batches in `cli_test`, 2048 of them), not one probe batch.
- **A unit of work is also an unmatched probe row padded**, beside a probe
  row read and a candidate pair examined, so a fill adds at most its units of
  rows.
- **`ParallelDrain.eachPartition` gained an overload** for an operator
  reading its child (Step 5), rather than a second drain.
- **The chaos points were renamed** (`shared-claim`, `shared-publish`).
- **NULL build keys are kept out of the key map**: they were in it under keys
  no probe could look up; the answers are the same.
- **A build side is built even for a probe of no partitions**, so a build
  that fails fails the query as it did, whatever the probe side holds.
- **A residual condition is evaluated a chunk of candidates at a time**
  (Step 6): a condition that fails on a candidate past the rows a `LIMIT`
  keeps can fail the query, as the plan's risks foresaw; docs/gotchas.md
  says so.
- **The forced-plan path tally** splits `collapsing` into `broadcast` and
  `in waves`, with a floor of its own for the second.

### Held for the user

Nothing on the stop-and-ask list. Open, as follow-ups:

- **A one-file `COPY` of a join is slower than the eager probe was** (21 s
  against 10 s for 6M joined rows), in less than half the memory and able to
  write results larger than the heap. Plan 14's `inOrder` reads one chunk a
  partition ahead, and a chunk of a selective join is hundreds of probe
  batches, so the partition being written is probed mostly alone. Ways past
  it: a deeper read-ahead (plan 14's stop-and-ask (b)), or one file written in
  parallel (plan 14's Q3); `PER_PARTITION_OUTPUT true` already writes each
  partition on its own.
- **Building an outer join's smaller side** (a RIGHT join builds its left
  side whatever its size), and **`PairsPerTask` for keyed waves** (measure a
  FULL join of the polymarket tables first), both open in the plan.
- **The console by hand.** `QueryConsole` is unchanged (plan 14's drain); the
  Monocle harness plan 14 left had no Monocle jar left beside it, so no
  headless check of a join in the console was run.

### Found on the way

- **`metamorphic_fuzz_test` failed under `--config=sharded` before this
  plan.** Its guard `dataFailuresReachAJoinAWindowAnAggregateAndSpill` reads
  the operator off each data failure's stack, with floors measured under the
  default sharding gates; with every breaker sharded most of those failures
  are raised as an exchange is materialized, which is none of its sites, so
  199 seeds fail where 556 do and a join is reached on 26 (floor 51). The base
  commit's engine, swapped in for the measurement, gives the same counts. The
  guard now skips itself with the gates open (`assumeTrue`, as
  docs/testing.md asks of a test that depends on a default), and so does this
  plan's own floor for the in-waves path in `forced_plan_fuzz_test` (36 FULL
  joins run in waves with the gates open, the rest sharded).
- **A job's parquet part files differ in their bytes between two runs of
  one jar** (13 of jaffle's 15 tables between two runs of the base jar, at
  the same execution time), so a byte comparison of a job's output says
  nothing; rows read sorted do.
- **`core:parallel_drain_test` failed one run in about fifteen under `chaos`,
  before this plan.** In `ofTwoFailuresTheLowerNumberedIsThrown`'s `inOrder`
  half, plan 14's code, which this plan does not touch, a fill of partition 6
  could fail before partition 2's fill began, so partition 2 was stopped,
  which is no failure, and partition 6's failure was thrown: 3 runs of 48
  with the base commit's `core` swapped in, 6 of 72 with this plan's.
  Partition 6 now fails only once partition 2 is being read, a second latch:
  96 runs of 96.
- **`spill_stress_test` timed out under `hashzero` beside the whole suite**,
  twice. Alone it passes in 131 s, 105 to 115 s of it its DISTINCT case,
  200,000 distinct rows whose every hash collides, a scan per lookup, code
  this plan does not touch: eight GC pauses of 18 ms in all at the target's
  new `-Xmx512m`, and 107 s at `-Xmx4g`. docs/testing.md had it at three and
  a half minutes beside the suite. Its timeout is long now.

### Measurements

The 2026-03-06 orderbook day (2.1 GB, 326M rows, 18 partitions) and the
124K markets, JDK 21 (bazel's remotejdk21), Apple M4, 8 cores, 16 GB,
`-Xmx2g`, one run at a time, each after a warm-up run of the same jar; peak
RSS is `/usr/bin/time -l`'s maximum resident set size. "Before" is the base
commit `b14cdd5`'s jar; Step 2 is this work's jar before Step 6 (Steps 1-5),
which runs the plan's query exactly as Step 2 alone would (it takes no
sharded, grace or window path).

| Statement | Before (`b14cdd5`) | After Step 2 | After Step 6 |
|---|---|---|---|
| the plan's query (`GROUP BY m.category` over the join) | out of heap after 138.5 s, 2.23 GB | 7.6 s, 0.73 GB | 6.8 s, 0.68 GB |
| the aggregate-first form | 6.8 s, 0.52 GB | 6.9 s, 0.52 GB | |
| `... JOIN markets m ... LIMIT 10` | out of heap after 90.4 s, 2.22 GB | 0.9 s, 0.23 GB | 0.9 s, 0.21 GB |
| `COPY` of the join `WHERE ob.timestamp_received % 20 = 0` (about 6M rows) to one parquet file | 10.3 s, 1.97 GB | 21.8 s, 0.85 GB | 21.2 s, 0.69 GB |
| EXPLAIN VERBOSE's join line | `[1 partition]` | `[18 partitions]` | |

- **The plan's query** answers 119,852,314 rows, 12,665 markets and an
  `AVG(spread)` of 0.07940793410537297 after both steps, byte for byte the
  same output; the aggregate-first form gives 0.07940793410537322, the last
  digits the plan foresaw. Step 2 and Step 6 alternated three times: 8.0,
  8.2, 8.1 s against 6.7, 6.9, 6.9 s; peak RSS 0.67 to 0.86 GB.
- **The aggregate-first form**, `WITH a AS (SELECT market_id, COUNT(*) AS n,
  SUM(spread) AS s, COUNT(spread) AS c FROM ob GROUP BY market_id) SELECT
  m.category, SUM(a.n), COUNT(DISTINCT a.market_id), SUM(a.s) / SUM(a.c) FROM
  a JOIN markets m ON a.market_id = m.condition_id GROUP BY m.category ORDER
  BY 2 DESC LIMIT 8`, took 10 s and 0.55 GB when the plan was written
  (`a408d07`); it takes 6.8 s and 0.52 GB at `b14cdd5`. The join, which
  probes 120M matches, now runs as fast as it, where the plan expected a small
  multiple.
- **The `COPY`** wrote the same 5,852,549 bytes from every jar.

### Gates

- **`bazel test //...`**: 93/93 under the default config and under each of
  `sharded`, `serial`, `verify`, `leaks`, `batch1`, `batch2` and `zone`. Under
  `chaos`, 92/93 at first, `parallel_drain_test` failing, and 93/93 once its
  race was fixed; under `hashzero`, 92/93 twice, `spill_stress_test` past its
  five-minute timeout, and 93/93 once its timeout was long (it took 384 s;
  both under "Found on the way"). The two drain targets green after their fix
  under the default config and all nine, `parallel_drain_test` 96 runs of 96
  under `chaos`.
- **Liveness.** `core:scheduler_test` untouched and green;
  `sql/exec:exchange_deadlock_stress_test` green in its default mode in every
  run above. Its red instrument still wedges: with `STRESS_LAZY=1` four runs
  of five wedged (at iterations 10, 205, 529 and 874 of a driver group), each
  classified as the 02e family, `SharedOnce.get` and `helpJoin` frames on the
  stuck stacks; one run of the default 6000 iterations passed. Its joins J2
  now run in their probe side's partitions and build through the shared
  helper, so the lazy mode reaches the hazard through a join's build as well
  as an exchange's.
- **Campaigns** at `FUZZ_SEEDS=20000` (seeds 0 to 19999, eight blocks of
  2500 each, three JVMs at `-Xmx1200m` from the built launchers with bazel
  shut down, the properties only): `metamorphic_fuzz_campaign` and
  `sharded_mode_fuzz_campaign` clean, all sixteen blocks green (86 to 118 s a
  block, against about 10 s at the default budget); and
  `starved_dag_scheduler_fuzz_campaign` once, at 2000 seeds, green (63 s).
- **The jaffle-shop jar**: exit 0, 15/15 Succeeded, every validation passing,
  each table still one part file; its 15 tables hold the same rows, compared
  sorted, as the base jar's at the same execution time (their parquet bytes
  differ between any two runs, the base jar's own included).
- **The polymarket jar** (`-Xmx12g`, as README's polymarket section runs it):
  exit 0, 14 Succeeded, 2 ValidationFailed, 1 Skipped, 0 Failed, in 75 s at
  7.2 GB peak RSS (the base jar: the same pattern, 84 s, 6.1 GB); the five
  tables that join hold the same rows as the base jar's, a part file each
  (their probe sides are aggregates of one partition).

## Launch prompt

```
Read plans/perf/13-stream-hash-join-output.md and implement it in step order,
one commit per step.

Use max effort. Honor CLAUDE.md: bazel test //... green, the jaffle-shop jar
15/15 with every validation passing, the polymarket jar exiting 0, docs in the
same commit. Peer sessions edit this tree: develop in a git worktree. Before
Step 2, confirm the user's answers to the six questions (Q1 decides whether
Step 2 happens); Steps 1, 3 and 4 need none.

Liveness is the first risk. No readiness wait in engine execution: the shared
build side is published by the pre-materialization pass, never a lazy val,
never synchronized, never ForkJoinPool.managedBlock. Keep
exchange_deadlock_stress_test green, and run the metamorphic and sharded
campaigns at FUZZ_SEEDS=20000 before calling Step 2 done.

Stop and ask before: (a) any producer/consumer queue, (b) changing ExchangeExec
beyond moving its CAS claim and latch into the shared helper, (c) changing the
build-side choice, the sharding gates or PairsPerTask, (d) landing Step 6 if it
does not measure faster.

Report: the polymarket query's wall time and peak RSS at -Xmx2g after Step 2
and after Step 6, beside the aggregate-first form's 10 s and 0.55 GB.
```
