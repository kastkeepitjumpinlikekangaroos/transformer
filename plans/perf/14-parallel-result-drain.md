# Plan 14: Read a printed result's partitions in parallel

> Status: **not started** (written 2026-11-01 against `32e89b1`: top-n landed
> in `a87f97f`, `--spill` and `QuerySession.optionsFor` in `a408d07`, plan 13
> in `32e89b1`) · six questions for the user, each with a recommended answer
> · Effort: 3-4 days, one more for Q2 · Risk: medium: the CLI's read path
> becomes concurrent, every way a statement stops must stop all of it, and
> every format must print byte for byte what it prints now

## Goal

`transformer query` reads a result of several partitions on several cores,
prints and writes it in today's order, holds a fixed number of partitions in
flight whatever the result's size, and leaves nothing running once a statement
ends, whether its output closed, Ctrl-C stopped it or a partition failed.

Not the goal: how the engine partitions a result (plan 13 changes that for
joins), text formatted on several threads, one file written by several (Q3).

## Why

Measured 2026-11-01 (JDK 21, `-Xmx2g`, Apple M4, 8 cores, 16 GB):
`transformer query --table ob=<one 2.1 GB orderbook day> "SELECT * FROM ob"`
(326M rows, 11 columns; the table format keeps only the first and last rows)
took 57 s at 1.0 GB peak RSS. A grouped aggregate over the same file, which
fans out inside the engine, takes 5-7 s; DuckDB 2.0 is 6-13x faster than us on
scans and aggregates. EXPLAIN VERBOSE, rerun for this plan with the CLI jar:
`Project: ... [18 partitions]` over `Scan ob: ... [18 partitions]`, as README's
Limitations says ("reads a result one partition at a time").

## What the code does now

- **One thread reads every partition.** `QuerySession.query` runs
  `SqlEngine.execute(withViews(sql), catalog, optionsFor(io.cancel))` and
  prints `io.printing(executed.batches)` through `ResultPrinter.print`; `copy`
  writes `io.cancel.watch(executed.batches)` through `CsvWriter.writeAll` or
  `ParquetWriter.writeAll`. `ExecutedQuery.batches` is `(0 until
  numPartitions).iterator.flatMap(partition)`, pulled by the statement's
  thread (the shell's `transformer-query` thread; `main` for a one-off
  command). For a scan, pulling is the work: `ParquetPartitionIterator.next`
  decodes a batch on the calling thread.
- **What `execute` leaves.** Both `SqlEngine` execute paths call
  `physical.execute(p)` for every partition before returning: a collapsing
  breaker at the root does all its work there, on the pool (why the aggregate
  is fast); a scan only opens its file (`ParquetPartitionIterator`'s
  constructor opens its reader and skips to its first row group;
  `CsvPartitionIterator` reads a row). Left to stream: scans and their filters
  and projections, `UnionExec`'s concatenation of its sides' partitions, the
  pairing join's waves, a sharded breaker's emit. A CSV file is a partition;
  parquet row groups pack into ~256 MB partitions
  (`ParquetReader.DefaultTargetBytesPerPartition`, by `getTotalByteSize`).
  Collapsing breakers, `GlobalLimitExec`, `OffsetExec`, `TopNExec` and
  EXPLAIN return one partition, so a LIMIT always does.
- **Readers of every partition.** Jobs read them in parallel, a pool task a
  partition waiting for nobody (`CsvWriter.writePartitioned`,
  `ParquetWriter.writePartitioned` in a window of `defaultWriteParallelism()`,
  `min(cores, maxHeap / 256 MB)`, `MaterializedView.fromQuery`).
  `QueryConsole.runQuery` (the GUI's console and each data-viewer query tab,
  `ViewerTabs`) materializes them one after another on its worker thread;
  `DataJob.runValidations` keeps every batch of a validation's result to count
  it and sample 10 rows. `ExecutedQuery.partition` allows concurrent reads "so
  long as each partition is drained by exactly one thread".
- **The pool's waits.** A pool worker waits only in `Submitted.await`
  (`ForkJoinTask.get`, which work-helps) or on the exchange's latch, which
  `preMaterializeExchanges` keeps engine plans from reaching.
  `ForkJoinPool.managedBlock`, tried in plans/bugfixes/02b, spawned spare
  threads and wedged the pool. JDK 21 calls it by itself:
  `AbstractQueuedSynchronizer.ConditionObject.await` (so `ArrayBlockingQueue`
  and `LinkedBlockingQueue`'s `put` and `take`) and `LinkedTransferQueue` (so
  `SynchronousQueue`) call it (checked with `javap` and the repo remotejdk21's
  `src.zip`, Zulu 21.0.6). A worker parked on a bounded queue for a slow
  consumer, plan 08's `StreamingResults`, is 02b again.

## Design

### Two drains, in core

`core/ParallelDrain.scala` (new) reads an `ExecutedQuery` on `Scheduler.pool`
two ways. Both take the statement's stop, `stopped` (asked before each batch)
and `stop` (called at a partition's first failure and on an early close), and
read a result of one partition on the caller's thread, as `batches` does: an
aggregate, a sort, a LIMIT, EXPLAIN and the listings read exactly as now.

- **`eachPartition(q, inFlight)(f: (Int, Iterator[ColumnarBatch]) => A):
  IndexedSeq[A]`**, for formats needing no row order: `inFlight` pool tasks
  each take the next untaken partition (an `AtomicInteger`: in order, no
  head-of-line wait) and run `f` over it to its end, until none is left; the
  caller waits with `Submitted.await` and gets results in partition order.
- **`inOrder(q, readAhead, chunk): Iterator[ColumnarBatch] with
  AutoCloseable`**, for formats that need it. A *fill* is a pool task that
  pulls at most `chunk` batches of one partition, returns them and ends; a
  partition has at most one fill at a time. The iterator decides every fill on
  its caller's thread: it keeps a window of `readAhead` partitions from the one
  it reads, submits a partition's first fill as it enters the window and the
  read partition's next fill as it takes a chunk of it, and awaits only a chunk
  it needs. Nothing is submitted before the first `hasNext` (a statement
  refused before reading, as json's repeated name or COPY's, reads nothing
  ahead). `close()` calls `stop` and waits for the fills in flight.

Fills move a partition between pool threads one at a time, each completion
before the next submission, as `PairingCursor` moves already;
`ExecutedQuery.partition`'s scaladoc ("drained by exactly one thread") is to
say "one thread at a time", and
`ParallelDrain`'s what that needs: no iterator state tied to a thread (the one
`ThreadLocal` in `src/main` is `Funcs`' RNG for unseeded `RAND()`), no batch
changed after `next()` returns it (`MaterializedView.fromQuery` needs that too).

### Why nothing in the pool waits for the consumer

The deadlock history (gotchas.md's K>1 bullet, plans/bugfixes/02*) reduces to
one rule: a pool wait is on the waiter's own descendants. The drains add none.

- A fill or `eachPartition` task waits only for tasks its partition's own
  operators submit (the pairing join's waves, a breaker under a union): a tree,
  which `Submitted.await` work-helps; exchanges are published before `execute`
  returns. Never for the consumer, a lock, a queue or another partition.
- The consumer waits only in `Submitted.await`, on the statement's thread,
  which parks without holding a worker (on a worker it would work-help: a tree
  await, as `scheduler_test` pins).
- A slow consumer (a terminal, a pipe into `less`) leaves no fill running: a
  partition's next fill is submitted once its chunk is taken. Waiting
  partitions hold a chunk, not a thread; at most `readAhead` fills run.

Refused: pool tasks pushing into a bounded queue (a slow consumer parks up to
`readAhead` workers in `put`, through `managedBlock`); the drain's own threads
on queues (outside §3's one pool and `transformer.scheduler.parallelism`, a
join on every exit path, and a `take` that compensates on a pool worker).

### How much it holds

K = `min(partitions, Scheduler.parallelism, availableProcessors, maxHeap /
256 MB)` (`ParallelDrain.inFlight`; 256 MB as `ParquetWriter.PerWriterHeapMb`
reserves a writer): 8 at `-Xmx2g` here, 4 at `-Xmx1g`, 1 under
`--config=serial`; `chunk` is 8 batches (65,536 rows). `eachPartition` holds K
readers (a row group and a batch each) and what `f` keeps; `inOrder` K readers
and at most (K + 1) x `chunk` batches, two chunks of the partition read and
one of each ahead: at about 0.7 MB an orderbook batch (11 columns, 8 bytes a
value) plus strings, ~50 MB at K = 8. Today: one reader, one batch, 1.0 GB
peak RSS, mostly heap the JVM grew into. The unknown is the row group
parquet-mr holds while decoding (as far as the code shows): measure.

### Each format

| Format | Drain | Each partition gives | Then |
|---|---|---|---|
| `table` | `eachPartition` | its count, first H and last T rows as cells (H = `(maxRows + 1) / 2`, T the rest) | row g shows when g < H or g >= total - T; each such row is among its partition's first H or last T |
| `markdown` | `eachPartition` | every row as cells | concatenated in partition order, as held now |
| `line`, `csv`, `tsv`, `json`, `jsonl` | `inOrder` | | today's printers over the ordered iterator |
| EXPLAIN, listings, one partition | none | | as now |

Cells are cut as `box` cuts them (`fit` to `MaxCellWidth` is idempotent; the
glyphs follow the stream's charset, known before the read); `maxRows = 0`
keeps every row, as now. Lest a slow first partition leave every later slice
waiting, each partition publishes its count as it reads, and as each finishes
a waiting slice drops its first rows once the partitions before it have
counted H rows, and its last once those after it have counted T: about
(H + T) rows a partition in flight, whatever the partition count.
`CsvValues.format` and `shown` keep no state. Output is today's, byte for byte.

### Expected gain

| Result | Today | After | Bound |
|---|---|---|---|
| table, markdown | one core decodes | K partitions at once | 18 over 8 in 3 waves: 6x at best |
| line, csv, tsv, json, jsonl | decode and format on one thread | decode on the pool, format on the statement's | 2x for few large partitions; up to Kx for many small |
| COPY, `--output` | decode and encode on one thread | the same split | 2x: one thread encodes |

Ordered formats gain less because a partition is one iterator: the one printed
advances a fill at a time and each ahead holds a chunk (a third of the next
ahead would hold about 500 MB here); formatting inside fills would put the
printed partition's decode and format back on one thread. Still sequential: a
partition's own batches; work inside `execute(p)`, such as a sharded breaker's
per-shard build, run a shard after another (as far as the code shows);
`DataJob.runValidations` (a follow-up: `eachPartition` counts and samples it).

### Stopping

Each statement gets a stop of its own, `Cancel.child()`, cancelled when it or
the session's `Cancel` is; `optionsFor` puts its `stopped` in the statement's
`ExecutionOptions`, so every scan of every partition stops at its next batch
(§3g). The drains cancel the child, never `io.cancel`, which mutes the shell's
output and ends a statement silently.

- **Closed output, Ctrl-C.** `Io.printing` cancels `io.cancel` on
  `out.checkError()` before each batch of an ordered format, as now, and the
  shell's handler on Ctrl-C: scans stop, the iterator throws
  `QueryStoppedException`, and after `close()` the statement ends silently.
  Table and markdown print only after reading, so they check `checkError`
  first: a script piped into `head` still stops at its next statement.
- **A LIMIT.** One partition: read on the statement's thread, nothing ahead.
- **A partition fails.** Its task records the failure and cancels the child;
  the rest stop at their next batch. The consumer throws, at its next `hasNext`
  or when its tasks return, the lowest-numbered partition's failure that is not
  a stop (`QueryStoppedException` among its causes, as `QuerySession.stopped`
  reads it), through `Scheduler.rethrowable` (itself; a fatal error wrapped),
  and `QuerySession.report` says it. A stop never replaces a recorded failure;
  with the session's `Cancel` set, the stop wins.
- **The consumer fails** (a value `CsvRowWriter` cannot write, a full disk):
  `finally close()` cancels the child and waits; its exception propagates.
- **On return** nothing of the statement runs: `close()` and `eachPartition`
  wait for all they submitted (after a second Ctrl-C the shell's prompt
  returns while the statement's thread waits there, as in its work today). No
  thread is interrupted.

`copy` writes `io.cancel.watch(inOrder(...))` through `writeAll`: one file,
today's bytes, decoded ahead on the pool and encoded on the statement's
thread; Q2 and Q3 are the ways past that thread.

## Questions for the user

Work proceeds on each recommended answer unless the user says otherwise.

1. **What a failing partition stops.** Recommended: everything at once, as
   asked: one failure reports today's error; several, the lowest-numbered of
   those that failed before the stop reached them (it can vary between runs);
   an ordered format may print fewer rows before it. Or: partitions before it
   finish first (today's error and rows always; up to K - 1 partitions read
   on; those after it stop only between drained batches).
2. **A directory of part files, written in parallel as a job's table.**
   Recommended: yes, behind an option: `COPY (query) TO 'dir' (FORMAT parquet,
   PER_PARTITION_OUTPUT true)` (DuckDB's `PER_THREAD_OUTPUT`, for this engine's
   unit). It writes `dir/part-00000.parquet`, ..., a file a partition, through
   `writePartitioned`: 18 for an orderbook day, one for an aggregate; parquet
   skips empty partitions (one header-only file when all are), CSV writes a
   header-only file for each empty one. It accepts `parquet_write_parallelism`,
   refuses a non-empty directory, leaves no file when it fails or stops (the
   CLI removes what it wrote; plans/testing/06 has since landed, and
   `job.OutputDirectory` stages parts and commits them together, as the
   GUI's `ResultPersister` writes through it, so the CLI could too), writes no `_run.json` (so `--table` reads it as a directory, in
   name order, which is partition order) and says `Wrote N rows to dir (18
   files)`; `--output` stays one file. About Kx faster. Or: a trailing `/`
   instead; or no directory form.
3. **One file written in parallel.** Recommended: a follow-up, if Step 2 shows
   a one-file COPY bound by its writer: parts written in parallel beside the
   target, joined in order (CSV parts after the first's header; parquet row
   groups copied unencoded by parquet-mr 1.14.3's
   `ParquetFileWriter.appendFile`), costing the result's size again on disk.
4. **Where the drains live.** Recommended: core (their liveness argument is
   the pool's, they test beside `scheduler_test`, the console, the viewer and
   validations read results too); the CLI keeps the table's slices. Or: `cli`.
5. **The console and the viewer.** Recommended: `QueryConsole` materializes
   through `eachPartition` (each partition's batches, in order) with a stop of
   its own in its options, so a failure stops the rest (Step 3, skipped if plan
   13's Step 5 landed it). Or: `MaterializedView.fromQuery`, a job's path,
   parallel but leaving the other partitions running after a failure.
6. **Order with plan 13.** Recommended: Steps 1 and 2 before plan 13's Step 2,
   so a printed join keeps today's parallel probe (plan 13's Q3 accepts losing
   it for one-stream readers). Either order is correct.

## Work, in landable commits

Every commit: `bazel test //...` green; the jaffle-shop jar 15/15 with every
validation passing (DataJob is untouched, but CLAUDE.md asks); the new targets
and `cli_test` green under every `.bazelrc` config; docs with it.

**Step 1: `eachPartition`; table and markdown through it.** `ParallelDrain`
(`eachPartition`, `inFlight`, the failure record, the stop) and
`ExecutedQuery.partition`'s scaladoc; `Cancel.child()` and `optionsFor`;
`ResultPrinter.print` given the `ExecutedQuery` and the child, table and
markdown through `eachPartition` (slices, pruning, `checkError` first), other
formats through `batches` until Step 2, listings as one-partition results.
Measure. Docs: architecture §3, §3g, §11, module map; conventions; gotchas;
testing.md; extending.md's "An output format"; README's Limitations bullet.

**Step 2: `inOrder`; line, csv, tsv, json, jsonl, COPY and `--output`
through it**, closed in a `finally`. Measure. Docs: README (Limitations,
Output), architecture §11, testing.md, extending.md's "A statement of the
CLI's own" (a child stop, a drain closed in `finally`; its `io.cancel.options`
is already stale at `32e89b1`).

**Step 3 (Q5): the console.** `QueryConsole.runQuery` through `eachPartition`;
no test drives it (JavaFX), so check a multi-partition table's count and first
rows by hand in the console and a viewer tab. Docs: the module map's entry.

**Step 4 (Q2): COPY to a directory.** The option in `writeOptions` (with
`parquet_write_parallelism`), the directory check, partitions wrapped to
cancel the child on failure, `writePartitioned`, removal on failure, the
message; `QuerySessionTest` cases; measure. Docs: README's COPY bullet,
`QuerySession.Help`, architecture §11, testing.md.

## Tests

`core:parallel_drain_test` (own JVM at `-Dtransformer.scheduler.parallelism=2`,
asserted as `parallelismPinnedTo2ByJvmFlags` does) and its twin
`parallel_drain_serial_test` at 1, over synthetic `ExecutedQuery`s whose
partitions record each pull's thread and any overlapping pull:

- **Order.** `inOrder` yields `batches`' sequence by identity for 0, 1, 2, 3,
  17 and 100 partitions of 0, 1, chunk - 1, chunk, chunk + 1 and many batches,
  `readAhead` and `chunk` each 1, 2 and 8; `eachPartition` returns results in
  order. No two pulls of a partition overlap, though its fills move threads.
- **Bounded, no worker held.** With endless partitions and a stalled consumer,
  pulled minus taken stays within (readAhead + 1) x chunk; `eachPartition`
  opens at most `inFlight`; at one thread, a task submitted meanwhile runs.
- **Stops and failures.** `stopped` ends the iterator with
  `QueryStoppedException`; after `close()`, early or late, nothing is pulled
  (two samples 200 ms apart) and `Scheduler.pool.awaitQuiescence` holds.
  Partition 5 of 8 throwing at its third batch reaches the consumer as itself
  (class, message, waiter's stack suppressed) and the rest stop; of two
  failures the lower-numbered is reported (forced by a latch); a stop never
  replaces a failure; an `Error` arrives as `Scheduler.rethrowable` wraps it.
  The same for `eachPartition`.
- **Liveness.** A partition whose `next()` runs a nested `submitAndAwaitAll`
  drains at one and two threads, `readAhead` above them, and from a pool task.

`cli_test`: every format prints a several-partition result byte for byte as
`ResultPrinter` over `batches` does (kept as the reference): partitions of 0,
1, H - 1, H, H + 1 and many rows, some empty, `maxRows` 0, 1, 2, 3, 40 and past
the total, and a slow first partition before 10,000 one-row ones (slices
bounded). Over `withEndless`'s table with 4 partitions: cancel stops table and
csv, served batches bounded; a closing output stops csv; a partition throwing
at its third batch fails the statement with its message, its siblings stop,
and nothing is read after `runScript` returns; a script whose first table
closed the output stops at its second statement; `LIMIT 5` returns after a
batch or two; `ShellTest`'s Ctrl-C stops a table and the next statement runs.
COPY and `--output` of several partitions write the one-thread write's CSV
text, and parquet that reads back in order.

Whole suite: `bazel test //...`, again with `--config=serial`; the new targets
and `cli_test` under `batch1`, `batch2`, `sharded`, `serial`, `zone` and
`hashzero`. Differential: the base commit's jar and the new one print the same
bytes (`shasum`) over the orderbook day for a selective WHERE drawing rows from
many partitions (every format, a COPY to CSV) and for jaffle's tables.

## Measurement

One run at a time (the machine is shared), each twice, keeping the second
(warm page cache), with bazel's JDK 21 (the system java is 17):

```bash
JAVA21=/private/var/tmp/_bazel_owenchristie/a24a66ef502a5253b6412a2c5b3a60de/external/rules_java~~toolchains~remotejdk21_macos_aarch64/bin/java
OB=$HOME/Downloads/archive/orderbook/orderbook_2026-03-06.parquet
/usr/bin/time -l $JAVA21 -Xmx2g -jar bazel-bin/transformer_deploy.jar query \
  --table ob=$OB "SELECT * FROM ob"
```

- Step 1: 57 s and 1.0 GB before; expected 10-15 s (6x at best, 9.5 s), no
  OutOfMemoryError, peak RSS ("maximum resident set size") reported; at
  `-Xmx1g` (K = 4) it completes; the grouped aggregate stays at 5-7 s.
- Step 2: `--format csv > /dev/null` and `COPY (SELECT * FROM ob) TO
  '<scratch>/ob.parquet'`, before and after (2x at best; delete the file).
- Step 4: that COPY with `PER_PARTITION_OUTPUT true`.

If K readers do not fit `-Xmx2g`, raise the 256 MB and say so in the commit;
under 3x on Step 1, profile before tuning.

## Docs to update

- README.md: Limitations' "one partition at a time" bullet rewritten (table
  and markdown read K partitions at once; ordered formats and COPY read ahead,
  bounded by the thread that prints or writes; Q1's error rule); Output; COPY
  if Q2. site/ gives no CLI timing; recheck after measuring.
- docs/architecture.md: module map (`ParallelDrain.scala`, `QueryConsole`), §2
  (who reads the flat view), §3 (the drains, the fill rule, why no queue), §3g
  (the child stop), §11. docs/conventions.md, beside "Prefer work-helping over
  managed blocking": no pool task waits for a consumer, and no blocking queue
  or condition wait on a pool worker (JDK 21 compensates it). docs/gotchas.md:
  that trap; what stays sequential. testing.md and extending.md as the steps
  say; code-map.md only if a file passes a hot spot.
- plans/perf/README.md: row 14; plans/perf/08: the queue plan 13 narrowed it to
  is this plan, without a queue.

## Risks

- **Memory.** K readers' row groups are unmeasured; the 256 MB rule mirrors
  the writer's and may be raised. Running out says so, as now.
- **CPU and expectations.** A CLI scan takes K cores, as an aggregate does;
  only table and markdown gain several-fold on few large partitions.
- **Errors.** Q1 changes which error and how many rows show when several
  partitions fail; tests pin the single-failure case.
- **The handoff.** Thread-affine iterator state, or a batch reused after
  `next()` (plan 03 Phase 8's pooling), would corrupt results; none exists.
- **Unchanged.** Every partition's file opens in `SqlEngine.execute`; a
  stopped statement leaves unread partitions' readers to the collector (as far
  as the code shows). A blocking queue added later would bring 02b back; the
  conventions bullet and the no-worker-held test guard against it.

## Relation to plans 08 and 13

Plan 13 narrows plan 08's queue to "an ordered parallel drain for one-stream
consumers" that answers its deadlock question (a producer blocked on `put`
beneath a work-helping consumer). This is that drain, answering with no
producer that waits. After plan 13 a broadcast join reaches the CLI as its
probe side's 18 partitions, read in parallel here with the lazy probe inside
fills; both plans touch `QueryConsole` (its Step 5, this Step 3): the second
to land keeps one drain.

## Launch prompt

```
Read plans/perf/14-parallel-result-drain.md and implement it in step order,
one commit per step.

Use max effort. Honor CLAUDE.md: bazel test //... green, the jaffle-shop jar
15/15 with every validation passing, docs in the same commit. Peer sessions
edit this tree: develop in a git worktree (a local .bazelignore with .claude
while an agent worktree lives under it), copy back after comparing against a
base snapshot. Run jars with bazel's remotejdk21; the system java is 17.
Steps 1 and 2 proceed on the recommended answers to Q1 and Q4 unless the user
has said otherwise; confirm Q5 before Step 3 and Q2 before Step 4.

Liveness comes first: nothing in Scheduler.pool waits for the consumer. No
BlockingQueue, SynchronousQueue or Condition on a pool worker (JDK 21's await
calls ForkJoinPool.managedBlock, 02b's regression), no thread of the drain's
own, no monitor held across a pool wait; every wait is Submitted.await. Keep
scheduler_test and exchange_deadlock_stress_test green and run the new targets
under every .bazelrc config. Output comes second:
every format prints byte for byte what it printed (compare the base commit's
jar on the orderbook day). Comments say what the code does, never which step
added it.

Stop and ask before: (a) changing how the engine partitions a result or any
operator, (b) reading ahead more than (K + 1) x chunk batches, (c) formatting
text on pool threads, (d) touching DataJob.

Report: the 326M-row SELECT * at -Xmx2g (wall time, peak RSS) before and after
Step 1, csv to /dev/null and a COPY before and after Step 2, beside today's
57 s and 1.0 GB.
```
