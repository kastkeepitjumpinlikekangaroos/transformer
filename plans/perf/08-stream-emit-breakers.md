# Plan 08: Stream emit from breakers

> Status: not started · Tier: 3 · Effort: 2-3 days · Risk: medium

## Goal

Stop fully materializing breaker output before emitting any batch.
Replace the "collect all rows into `ArrayBuffer[Array[Any]]`, then emit
batches" pattern in `HashJoinExec`, `DistinctExec`, and `WindowExec`
with a bounded blocking queue: each parallel shard pushes
`ColumnarBatch`es into the queue; the output iterator pulls from it.

The point is not to do less work — it's to **overlap** breaker CPU with
downstream writer I/O. On join + parquet-write workloads, downstream
sits idle while the breaker fully finishes; this plan lets the writer
start consuming as soon as the first shard finishes.

## Why it matters

Today's pattern (see `JoinExec.scala:101-158`):

```scala
private def probeIterator(build: BuildSide, matchedRight: ...): Iterator[ColumnarBatch] = {
  val joinedRows = mutable.ArrayBuffer.empty[Array[Any]]
  val tasks: Seq[Callable[...]] = (0 until left.numPartitions).map { lp => ... }
  Scheduler.submitAndAwaitAll(tasks).foreach { case (rows, m) =>
    joinedRows ++= rows
    matchedSet.addAll(m)
  }
  rowsToBatches(joinedRows.toArray, schema, capacity)
}
```

The `submitAndAwaitAll` blocks until **every** probe shard is done. Only
then does `rowsToBatches` start producing output batches. The downstream
writer can't begin its work — typically partitioned parquet writes —
until then.

For a polymarket join task that takes 30s of CPU and 20s of write, this
adds up to 50s wall. With streaming emit it becomes ~max(30, 20) = 30s.
Roughly 30-40% wall-time improvement on join-heavy pipelines.

Same pattern in:
- `DistinctExec.execute` (`DistinctExec.scala:13-26`): collects all
  partials, merges, then emits.
- `WindowExec.execute` (`WindowExec.scala:26-67`): fully materializes
  child rows, then processes specs, then emits.
- `HashAggregateExec.execute` (`AggregateExec.scala:29-48`): same. But
  here the per-shard aggregation already happens in parallel; the merge
  step is sequential. Streaming requires the merge to be incremental.

`SortExec` is a special case — a global sort fundamentally can't emit
any row until all input is seen (the smallest input row might be the
last one read). Skip SortExec for this plan; covered partially by
plan 01 (k-way merge already streams from the merge).

## Current state

`Scheduler.submitAndAwaitAll` is the synchronization point. It returns
`IndexedSeq[T]` only after every task is done. Suitable for breakers
that need a global result; not suitable for streaming emit.

## Proposed design

### Bounded queue + producer-consumer

Introduce a helper in `core/`:

```scala
final class StreamingResults(capacityBatches: Int) {
  private val queue = new java.util.concurrent.ArrayBlockingQueue[ColumnarBatch](capacityBatches)
  private val sentinel = ... // distinguished "done" marker (or use AtomicInteger of remaining producers)
  private val remaining = new AtomicInteger(0)

  def setProducerCount(n: Int): Unit = remaining.set(n)
  def push(batch: ColumnarBatch): Unit = queue.put(batch)
  def producerDone(): Unit =
    if (remaining.decrementAndGet() == 0) queue.put(SentinelBatch)

  def iterator: Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] {
    private var nextItem: ColumnarBatch = _
    private var ended = false
    private def advance(): Unit = {
      if (!ended) {
        val taken = queue.take()
        if (taken eq SentinelBatch) { ended = true; nextItem = null }
        else nextItem = taken
      }
    }
    advance()
    def hasNext: Boolean = !ended && nextItem != null
    def next(): ColumnarBatch = { val v = nextItem; advance(); v }
  }
}
```

Producers (the parallel shard tasks) push batches into the queue.
Consumer (the iterator returned by `execute`) pulls.

### `HashJoinExec` integration

```scala
def execute(partition: Int): Iterator[ColumnarBatch] = {
  require(partition == 0)
  val build = buildSide()    // still blocking
  val streaming = new StreamingResults(capacityBatches = Scheduler.parallelism * 2)
  streaming.setProducerCount(left.numPartitions)

  (0 until left.numPartitions).foreach { lp =>
    Scheduler.submit(new Callable[Unit] {
      def call(): Unit = {
        try {
          val leftIt = left.execute(lp)
          while (leftIt.hasNext) {
            val outBatch = probeBatch(leftIt.next(), build)  // emit one batch at a time
            if (outBatch.numRows > 0) streaming.push(outBatch)
          }
        } finally streaming.producerDone()
      }
    })
  }

  // For outer joins, emit unmatched right rows after probe drains.
  // Strategy: a final producer task that waits for all probe tasks to
  // finish (via a CountDownLatch on producerDone), then emits unmatched.

  streaming.iterator
}
```

### `DistinctExec` integration

Distinct must dedupe across input partitions. Streaming emit requires
all partials to be merged before any output is correct. Two options:

a. **Collect partials in parallel** (current behavior), then merge
   eagerly and emit while merging. The merge step iterates the partials
   linearly — emit a batch every `DefaultCapacity` rows. Reduces "last
   row emitted" time only slightly; mostly removes the
   `rowsToBatches` overhead.

b. **Lazy global dedupe** via a concurrent HashSet across producers.
   Each producer adds rows to a shared `ConcurrentHashMap.newKeySet()`;
   the first time a key is added (atomic `addAndCheck`), the producer
   pushes that row to the queue. Cleaner streaming behavior. Trade-off:
   contention on the shared set.

Recommend option (b) if `ConcurrentHashMap` overhead is acceptable —
benchmark first.

### `WindowExec` integration

The window function partitions rows by PARTITION BY keys. Within a
partition, all rows must be visible before LAG/LEAD/RANK can compute.
But across partitions, work is independent.

Steps:
1. Read input fully into shards bucketed by PARTITION BY key (already
   plan 04 territory if that lands first).
2. Each shard processes its rows for all WindowDefs sequentially, then
   pushes its output batches to the streaming queue.

Without plan 04, WindowExec must fully materialize before any output —
streaming buys nothing here. Skip Window in this plan unless plan 04 is
landed first.

### `HashAggregateExec` integration

Similar to Distinct: the per-shard partial maps must be merged before
emit (different keys land in different partials). If plan 04 lands
(shards = key-disjoint), each shard can emit its final map independently,
which IS streaming. Without plan 04, this is hard — defer.

### Backpressure

Bounded queue size matters. Too small (1-2 batches) and producers block
waiting for the consumer. Too large (1000s) and we just buffered the
entire result. Default: `2 × Scheduler.parallelism` — enough to keep
all producers busy, small enough to avoid full materialization.

Configurable via system property `transformer.scheduler.streaming_queue_size`.

### Cancellation

Today, if the consumer iterator is abandoned mid-stream, the producer
tasks have already completed (because of `submitAndAwaitAll`). After this
change, abandoned consumers leave producers blocked on `queue.put`.

Add a `close()` method to `StreamingResults` that drains the queue and
sets a cancellation flag; producers check the flag inside their inner
loop. Wire `close()` to a `finally` block in any consumer that may
abandon (writers do not; validations might).

In practice, no current call site abandons mid-stream — the writers
fully drain. Add `close()` as a future-safety measure but don't gate
the plan on it.

## Files to touch

- **New**: `src/main/scala/com/transformer/core/StreamingResults.scala`.
- **New**: `src/test/scala/com/transformer/core/StreamingResultsTest.scala`.
- **Modified**: `src/main/scala/com/transformer/sql/exec/JoinExec.scala`
  (the main target).
- **Modified**: `src/main/scala/com/transformer/sql/exec/DistinctExec.scala`.
- **Modified (conditional, after plan 04)**:
  `src/main/scala/com/transformer/sql/exec/WindowExec.scala`,
  `src/main/scala/com/transformer/sql/exec/AggregateExec.scala`.

## Edge cases

1. **Outer joins.** Unmatched-right rows can only be emitted after all
   probe tasks finish. Use a `CountDownLatch` to gate the
   unmatched-right emitter on probe completion.
2. **Empty input.** No producers → queue gets the sentinel immediately;
   iterator's hasNext returns false. Handle the `setProducerCount(0)` case.
3. **Producer exception.** A producer that throws must signal failure so
   the consumer doesn't deadlock. `StreamingResults` should expose a
   `pushError(Throwable)` that the iterator rethrows on the next
   `hasNext`. Wrap each producer's `call()` in try/catch that pushes the
   error then signals done.
4. **Batch ownership.** A batch pushed into the queue is owned by the
   consumer. Producers must not mutate after push. `probeBatch` already
   returns a fresh `ColumnarBatch`; safe.
5. **Memory amplification.** If the queue holds K batches at 8192 rows
   × N cols × 8 bytes = ~64KB/batch × K. With K=32 and 12 columns, that's
   ~24MB of in-flight memory — modest. But for very wide schemas (100s of
   columns), reduce K accordingly.
6. **Order preservation.** Concurrent push from multiple producers yields
   non-deterministic batch order. Today, output order from joins is also
   not strongly ordered (it's left-partition-iteration order followed by
   unmatched-right). Document the change; if any downstream relies on
   order it must add an explicit ORDER BY.

## Testing

### Correctness
- All existing breaker tests must pass.
- Add streaming-specific tests:
  - Multi-producer push with high contention; verify all rows pulled
    exactly once.
  - Producer exception propagates to consumer.
  - Empty-input cases.
- Outer-join correctness: unmatched-right rows still emitted exactly
  once after probe completes.

### End-to-end
- jaffle_shop: 15/15 Succeeded.
- polymarket: 15/1/1.

### Performance
- A join task with non-trivial parquet write. Measure wall time
  before/after.
- async-profiler flame graph: confirm writer threads are now active
  during join CPU work (not idle).
- Latency to first output batch: should drop dramatically (from
  "after full join completes" to "after first probe shard finishes").

## Risks

1. **Deadlock.** Bounded queue + uncoordinated producer/consumer is
   classic deadlock territory. Mitigation: use `ArrayBlockingQueue` (well-
   tested), ensure sentinel is always pushed in `finally`, test with
   small queue sizes.
2. **Error propagation correctness.** Producer-side exceptions getting
   swallowed leads to silent data loss. Mitigation: dedicated test;
   verify the exception class and message round-trip to the consumer.
3. **Order-dependent downstream.** Anything that depended on
   "join output is consistent across runs" may flake. Mitigation:
   joins were already non-deterministic in batch order; document; tests
   that compare row sets, not sequences.
4. **Overlap doesn't materialize.** If the writer was already starved
   by CPU on the join side, overlap is theoretical. Mitigation: profile
   first; this plan only matters if writer wall time is a non-trivial
   fraction of total.
5. **WindowExec / HashAggregate without plan 04.** These breakers do not
   benefit from streaming without first sharding. Skip those branches
   unless plan 04 has landed.

## Suggested phases

1. **Phase 1**: build `StreamingResults` with tests.
2. **Phase 2**: integrate into `HashJoinExec` for inner joins only.
   Measure.
3. **Phase 3**: extend to outer joins (CountDownLatch for unmatched-right).
4. **Phase 4**: integrate into `DistinctExec` (option a — merge-step
   streaming, no concurrent set).
5. **Phase 5 (post plan 04)**: streaming `HashAggregateExec` and
   `WindowExec`.

## Docs to update

- `docs/architecture.md` §2 — update the "Pipeline breakers ... materialize
  across partitions in parallel" paragraph to reflect that some breakers
  now stream output incrementally.
- `docs/gotchas.md` — note any new ordering nondeterminism.
- `docs/extending.md` — pattern for new breakers that want to stream.

## Launch prompt

```
Read plans/perf/08-stream-emit-breakers.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Before starting: profile a polymarket join + parquet-write task with
async-profiler to confirm the writer is genuinely starved by breaker CPU.
If writer wall time is <10% of total, the optimization is not worth the
complexity — stop and report.

Follow the 5 phases. Phase 5 is conditional on plan 04 (hash-partition
breakers) being landed; skip otherwise.

Concurrency correctness is the #1 risk. Use ArrayBlockingQueue; ensure
sentinel push in finally; test with queue size = 1 to flush out
deadlocks; test producer-exception propagation.

Spawn parallel sub-agents for: (a) building the multi-producer
contention tests in parallel with implementation, (b) measuring writer
overlap with async-profiler before/after.

Stop and ask before: (a) introducing a cancellation API on iterators
beyond StreamingResults.close, (b) touching SortExec (out of scope), (c)
modifying anything outside JoinExec, DistinctExec, and the new
StreamingResults file in Phases 1-4.

Include in PR description: latency-to-first-batch on a polymarket join
task before/after; total wall time before/after; flame graph
confirming writer threads now active during breaker CPU.
```
