# Plan 09: Spill-to-disk for breakers

> Status: not started · Tier: cross-cutting · Effort: 3-6 weeks · Risk: high
>
> Prereqs satisfied: plan 01 (k-way merge in SortExec) and plan 04
> (ExchangeExec / hash-partition breakers) are both landed. The grace-hash
> Phase 6 and sort-spill Phase 3 can proceed without further dependencies.

## Goal

Add an opt-in spill-to-disk path for the breakers that hold all input
in memory today: `HashAggregateExec`, `HashJoinExec`, `SortExec`,
`DistinctExec`, `WindowExec`. When the in-memory key set or row buffer
exceeds a threshold, spill partials to local temp parquet files and
fold them back in during the merge step.

**This plan is about not crashing on large data — not about going faster.**
Sub-heap workloads (Jaffle Shop, current Polymarket setup) will be
slightly slower or unchanged. Above-heap workloads will go from OOM to
"completes". Pick this plan if production data growth is squeezing you;
deprioritize otherwise.

## Why it matters

`docs/gotchas.md:166-168` explicitly calls this out:

> **No spill-to-disk** for hash-aggregate/hash-join/sort. v1 holds all
> keys in memory. Document this if exposed to users; consider adding a
> `RowsToDiskOnPressure` operator post-v1.

`DataJob.scala` has an `oomHint` (per `docs/architecture.md` §3b) that
points users at `cache: false` — but that only helps inputs. Once data
flows through an aggregate, join, sort, distinct, or window, the entire
intermediate result is in heap.

Concrete failure modes:
- Polymarket full-day orderbook (~131M rows) historically could not run
  without the shipped 6-hour filter. The picture has shifted with the
  parquet per-row-group String intern cache (see
  `docs/architecture.md` §3c) — foreign-key string columns like
  `market_id` now share a small dictionary of `String` refs across rows,
  so a `GROUP BY market_id` aggregator over 105M rows holds ~13K
  `String` refs × per-key state, not 105M unique `String` objects. The
  remaining heap pressure on full-day workloads is more likely the
  materialized intermediate view (`stg_orderbook`'s cached output ≈
  hundreds of millions of typed cells in heap) than the aggregator's
  key set. Re-test before invoking this as motivation; spill on the
  cached materialized view is a separate concern that doesn't fit the
  per-operator spill model below.
- High-cardinality joins (eg. enriching every order with every
  customer's history) currently OOM at ~10M rows on the build side.
  With spill, scale.

## Current state

All breakers hold their full working set in heap. The concrete in-memory
shapes — current as of the KeyCodec + LongHashMap rewrites — are:

- `HashAggregateExec`: either
  - `java.util.LinkedHashMap[AnyRef, Array[AggState]]` with codec keys
    (`BytesKey` for fixed-width-only GROUP BY columns; `ObjectArrayKey`
    when any GROUP BY column is variable-width — String / Binary /
    Decimal / NullType), OR
  - `LongHashMap[Array[AggState]]` when GROUP BY is a single
    fixed-width-numeric ColRef (Int / Long / Date / Timestamp / Boolean
    — `KeyCodec.isLongFittable`). Stores primitive `long` keys
    unboxed.
- `HashJoinExec`: build-side `ArrayBuffer[Array[Any]]` of materialized
  rows, plus either `util.HashMap[AnyRef, util.ArrayList[Int]]` (codec
  keys) OR `LongHashMap[util.ArrayList[Int]]` (single-long key fast
  path) of key → row indices. All unbounded.
- `SortExec`: `ArrayBuffer[Array[Any]]` per partition, then concatenated
  — unbounded. (Plan 01's k-way merge avoids one *output* O(N) buffer
  but the per-partition sort buffers are still in heap.)
- `DistinctExec`: `util.LinkedHashSet[AnyRef]` with codec keys —
  unbounded.
- `WindowExec`: `ArrayBuffer[Array[Any]]` of all child rows — unbounded.

`ColumnProjectionPushdown`, `cache: false`, and parquet predicate
pushdown can shrink inputs but cannot shrink intermediate aggregates,
joined builds, or sorted buffers.

## Proposed design

### Shared spill infrastructure

A new module `core/Spill.scala`:

```scala
object Spill {
  /** Threshold in bytes after which an operator should spill. Default:
    * 25% of -Xmx. Override via transformer.spill.threshold_bytes. */
  val thresholdBytes: Long = ...

  /** Temp dir for spill files. Default: java.io.tmpdir/transformer-spill.
    * Override via transformer.spill.dir. */
  def tempDir(): Path = ...

  /** Estimate heap usage for a `ColumnarBatch` (sum of column sizes +
    * object overhead approximation). */
  def estimateBytes(b: ColumnarBatch): Long = ...
}
```

### Per-operator spill design

#### HashAggregate spill

When the in-memory map's estimated size crosses threshold:
1. Drain the current map to a temp parquet file (one row per group,
   columns = groupKeys ++ partial-aggregate-state-serialization).
2. Reset the in-memory map; continue aggregating.
3. At merge time, read each spilled parquet file as a stream, and merge
   into a global hash-aggregate over (spilled-partials + in-memory).

Two map representations to handle:
- **Codec map** (`LinkedHashMap[AnyRef, Array[AggState]]`): drain by
  walking entries, using `KeyCodec.decode(key, outBatch, baseCol, row)`
  to materialize the GROUP BY columns back into the spill batch's
  typed columns, then appending the serialized AggStates.
- **`LongHashMap[Array[AggState]]`**: drain via `forEach((isNull, k,
  states))`. Spill schema is a single fixed-width-numeric key column
  (Long/Int/Date/Timestamp/Boolean) plus the null sidecar slot, plus
  serialized AggStates. Simpler than the codec path since there's
  exactly one primitive key column.

The serialization of AggState is the hard part. Each `AggState` subtype
needs a `serialize()` / `deserialize()` that round-trips through a fixed
schema. `MomentState`/`CovarState`/`CorrState` are easy (3-5 doubles +
a count). `LongSumState`/`DoubleSumState`/`AvgState`/`CountState`/
`CountStarState`/`CountIfState` are likewise straightforward.
`MinMaxState` carries primitive-specialized slots `(hasValue: Boolean,
longCur: Long, doubleCur: Double, currentBoxed: Any)` — pick the
canonical slot from `dt`: primitive Int/Long/Date/Timestamp use
`longCur`, Float/Double use `doubleCur`, reference types
(String/Binary/Decimal) use `currentBoxed`. `CountDistinctState` (a
HashSet) is harder: the spilled representation must somehow preserve
the set — eg. serialize the HashSet's contents as a parquet
`LIST<value>` column. Heavy.

Recommend: support spill only for the simple AggStates first
(Count, Sum, Avg, Min, Max, CountIf, Moment, Covar, Corr). For
CountDistinct, fall back to non-spill (and document the heap limit).

#### HashJoin spill

The standard literature pattern is **grace hash join**: hash-partition
both sides by join key into K disk-resident buckets at threshold
crossings. At join time, load one (left-bucket, right-bucket) pair into
memory at a time and do the in-memory hash join. The K buckets serialize
the join.

This requires the hash-partitioning machinery from plan 04. If plan 04
is landed, grace hash extends naturally: each shard's partition can
spill its build side to disk when too big.

Without plan 04: the build side is one big in-memory ArrayBuffer.
Spilling it row-by-row to disk doesn't help — the hash map of keys is
the bottleneck, not the row buffer.

Recommend: gate HashJoin spill on plan 04 landing first.

#### Sort spill (external sort)

External merge sort:
1. Each per-partition sort runs as today.
2. When the per-partition `ArrayBuffer` exceeds threshold, sort it in
   memory and write to a temp parquet file ("run").
3. Reset; continue.
4. At merge time, do a K-way heap merge (per plan 01) over **all runs
   from all partitions** simultaneously. Reads stream.

Memory bound: one batch per active run + a comparator-key per heap
entry. With 100 runs × 8192 rows × 12 cols × 8 bytes ≈ 80MB streaming.

Sort spill is the cleanest case to implement — runs are independent and
the merge is heap-based. Recommend doing this **after plan 01** lands.

#### Distinct spill

Same shape as HashAggregate: spill partials at threshold, then merge.
Distinct's "partial" is a HashSet — serialize as a one-column parquet
file. Merge by streaming dedupe via a HashSet of seen keys (which can
itself spill recursively, but probably doesn't need to in v1).

#### Window spill

Hardest. Window needs full visibility within a partition. If a partition
itself exceeds threshold, spill within the partition is non-trivial —
LAG/LEAD across spill boundaries needs careful read-ahead.

Recommend: defer Window spill until last. If a single PARTITION BY key
produces >threshold rows, the user has bigger problems (skew); document
and skip.

### Threshold detection

Two strategies:
- **Heap-pressure trigger**: monitor `Runtime.totalMemory - freeMemory`
  vs `maxMemory`; spill when 70% full. JVM-wide, doesn't account for
  *which* operator is the culprit.
- **Per-operator byte count**: each operator tracks bytes added; spills
  when its own count crosses threshold. Per-operator; doesn't react to
  outside pressure.

Recommend: per-operator with a heap-pressure backstop. Default per-op
threshold = `maxHeap / (4 × Scheduler.parallelism)`; backstop = JVM
70% full.

### Temp file lifecycle

- Each operator owns a directory under `Spill.tempDir()`:
  `${tempDir}/op-${operatorId}-${execId}/`.
- Files deleted on operator's iterator close (or on JVM shutdown via
  Runtime.addShutdownHook).
- Crash-safety: stale temp dirs from prior runs cleaned up at process
  start (lazy cleanup; bounded by directory walk depth).

### Configuration — per-task opt-in, default off

Spill is **disabled by default**. Each task opts in via the existing
`output.json` `options` map. Matches how the directory loader already
configures `partitionBy`, `parquet_write_parallelism`, and
`compression` per-task.

```json
{
  "format": "parquet",
  "partitionBy": "day={{ today }}",
  "options": {
    "spill": "true",
    "spill_threshold_bytes": "1073741824"
  }
}
```

| Option                   | Type    | Default                                                | Notes |
|--------------------------|---------|--------------------------------------------------------|-------|
| `spill`                  | bool    | **`false`**                                            | Master switch. When false, this task never spills; `Spill.estimateBytes` is not even called inside its operators. |
| `spill_threshold_bytes`  | long    | `min(1 GiB, maxHeap / 4 / Scheduler.parallelism)`      | Per-operator. Crossing this triggers a flush of partial state to a spill file. |
| `spill_max_runs`         | int     | `1000`                                                 | Abort if one operator spills more than this many times — signals a stuck loop / pathological data. |

A single global system property remains for infrastructure-only
concerns:

| System property              | Default                                | Notes |
|------------------------------|----------------------------------------|-------|
| `transformer.spill.dir`      | `${java.io.tmpdir}/transformer-spill`  | Temp directory for spill files. Global because it's a filesystem location, not a behavior toggle. |

Why no `transformer.spill.enabled` global override? Two reasons:
1. Spill on a task that doesn't need it adds overhead with no payoff.
   Per-task opt-in matches actual need.
2. A global "force all tasks to spill" flag is useful only for stress
   testing. Test that path with per-task config in a synthetic job;
   no need for the global.

### Plumbing per-task options into operators

Spill is decided at operator construction, but operators are built by
`PhysicalPlanner`, which today knows nothing about `SQLTask.options`.
Thread an `ExecutionOptions` value through the executor call:

```scala
final case class ExecutionOptions(
    spillEnabled: Boolean = false,
    spillThresholdBytes: Option[Long] = None,
    spillMaxRuns: Int = 1000)

trait SqlExecutor {
  def execute(sql: String, catalog: Catalog): ExecutedQuery =
    execute(sql, catalog, ExecutionOptions())
  def execute(sql: String, catalog: Catalog, opts: ExecutionOptions): ExecutedQuery
}
```

`DataJob.runOneTask` extracts the relevant entries from
`node.task.options` once and passes the `ExecutionOptions` into
`executor.execute`. The planner stashes it on a context and passes the
narrow subset each spill-capable operator needs.

Ad-hoc queries from the GUI's SQL Console always use defaults — no
spill. A console user who runs a query big enough to spill is doing
ad-hoc exploration and should either narrow the query or promote it to
a task. Document the limit; don't try to be clever about it.

## Files to touch

### Configuration plumbing (Phase 1 — before any operator spill)
- **New**: `src/main/scala/com/transformer/core/ExecutionOptions.scala` —
  the case class above.
- **New**: `src/main/scala/com/transformer/core/Spill.scala` — temp dir,
  byte estimation. Stateless utilities; defaults sourced from
  `ExecutionOptions`, not system properties.
- **Modified**: `src/main/scala/com/transformer/core/SqlExecutor.scala` —
  add the `execute(sql, catalog, opts)` overload; keep the 2-arg form
  as a default-opts shim so the GUI and `SqlExecutorRegistry` callers
  don't need to change.
- **Modified**: `src/main/scala/com/transformer/sql/exec/SqlEngine.scala`
  — implement the new overload, pass `opts` to `PhysicalPlanner`.
- **Modified**: `src/main/scala/com/transformer/sql/exec/PhysicalPlanner.scala`
  — accept `opts: ExecutionOptions`, plumb to constructed operators.
- **Modified**: `src/main/scala/com/transformer/job/DataJob.scala` —
  `runOneTask` reads `node.task.options` for `spill` /
  `spill_threshold_bytes` / `spill_max_runs` and builds `ExecutionOptions`.
- **Modified**: `src/main/scala/com/transformer/job/SQLTask.scala` /
  `DirectoryJobLoader.scala` — confirm the existing `options: Map[String, String]`
  shape carries through (it should; mirrors `parquet_write_parallelism`
  plumbing).

### Per-operator spill (Phases 2+)
- **New**: `src/main/scala/com/transformer/core/AggStateSerde.scala` —
  serialize/deserialize each spillable AggState subtype.
- **Modified**: `AggregateExec.scala` — spill path, gated on `opts.spillEnabled`.
- **Modified**: `SortExec.scala` — external merge sort.
- **Modified**: `DistinctExec.scala` — spill path.
- **Modified (after plan 04)**: `JoinExec.scala` — grace hash join.
- **Modified**: `WindowExec.scala` — partition-internal spill (last
  phase; optional).

### Tests
- **New**: `src/test/scala/com/transformer/core/ExecutionOptionsTest.scala`
  — round-trip from `output.json` options map to `ExecutionOptions`.
- **New**: `src/test/scala/com/transformer/sql/exec/SpillTest.scala` —
  parity tests with tiny thresholds. Each spill-capable operator must
  produce bit-equal output under (spill enabled, low threshold) vs
  (spill disabled, normal heap).

## Edge cases

1. **Disk full** during spill. Catch IOException, abort the task, surface
   a clear error.
2. **Spill file outlives JVM** (crash). Stale-dir cleanup at startup.
3. **Concurrent operators spilling to same temp dir.** Use unique
   operator+exec IDs in directory names.
4. **CountDistinct** without serialization. Fall back to non-spill,
   document.
5. **Decimal AggState serialization** — BigDecimal serializes cleanly
   as `LONG` (unscaled) + `INT` (scale).
6. **Reread is per-batch, not per-row.** Sort's external merge reads
   one batch at a time from each run; HashAggregate's merge does the
   same. Sized for memory bound.
7. **Spill in tests is slow.** Don't unconditionally test spill on
   every CI run; have a separate "stress" target invoked manually.
8. **Filesystem semantics on tmpfs vs disk.** Spill is most useful on
   disk (where heap > tmpfs). Default location should be real disk.
9. **Task declares `spill: true` but the operator that would spill
   doesn't exist yet** (e.g. task uses HashJoin; Phase 6 not yet
   landed). Treat as a no-op — the task runs without spill on that
   operator. Don't error at validation time; the option means "spill
   if you support it." Adding a new spillable operator becomes
   transparent to existing configs.
10. **Ad-hoc SQL Console queries** always use `ExecutionOptions()`
    defaults → no spill. A console user hitting heap on an exploratory
    query should narrow the query or promote it to a task. Documented
    limitation.
11. **`spill: true` typo recovery.** Treat any value other than
    case-insensitive `"true"` / `"1"` as false. Don't throw on
    unexpected input — the user has plenty of ways to break their
    own config; silently ignoring `spill: "yes"` keeps the failure
    mode "task ran without spill" instead of "task didn't run at all."

## Testing

### Correctness
- For each spill-capable operator, run with `spill.threshold_bytes = 1024`
  (force spill on every batch). Compare result against the same query
  with spill disabled. Bit-equal output.
- Test with multiple spill rounds: 100k rows × tiny threshold → many
  spills.
- Test with single-row inputs (no spill triggers).

### End-to-end
- Run the existing jaffle_shop and polymarket pipelines with spill
  forced ON via the low threshold; expect them still to pass.
- New stress test: polymarket full-day orderbook (no 6-hour filter).
  Currently OOMs; with spill should complete.

### Performance
- Slowdown overhead at sub-threshold sizes (spill enabled but never
  triggered): ≤5%. The bookkeeping should be cheap.
- Throughput at exactly-at-threshold: 2-5× slower than in-memory only
  (disk I/O cost).
- Above-heap: comparison against `OutOfMemoryError`.

## Risks

1. **AggState serialization is a large new surface area.** Each subtype
   needs a stable encoding. Mitigation: skip CountDistinct (document);
   keep the rest simple (count + doubles).
2. **Grace hash join requires plan 04.** Strict prerequisite.
3. **Sort spill requires plan 01.** K-way merge over runs is the merge
   primitive; build on that.
4. **Performance regression at sub-threshold.** Every batch check adds
   bookkeeping. Mitigation: bookkeeping is a single Long compare per
   batch — should be free.
5. **Test flakiness** under tight thresholds. Mitigation: deterministic
   thresholds and seeds.
6. **Disk-full mid-spill** corrupts the operator's state. Mitigation:
   atomic temp+rename pattern (already in `CsvWriter`/`ParquetWriter`).
7. **CountDistinct fallback OOM.** Mitigation: document; user has to
   pre-aggregate or sample.

## Suggested phases

1. **Phase 0 (prereq)**: plan 01 (k-way merge) and plan 04
   (hash-partition) are both landed as of `perf pt 4`. Phase 0 is
   satisfied — proceed directly to Phase 1.
2. **Phase 1**: configuration plumbing only. Adds `ExecutionOptions`,
   threads it through `SqlExecutor` → `SqlEngine` → `PhysicalPlanner` →
   operator constructors, and reads `options.spill` / `options.spill_threshold_bytes`
   from `output.json` in `DataJob.runOneTask`. No operator spill yet —
   plumbing only. Lands as a no-op refactor verified by all existing
   tests passing.
3. **Phase 2**: `Spill.scala` core utilities + `AggStateSerde` for
   simple AggStates (Count, Sum, Avg, Min, Max, CountIf, Moment,
   Covar, Corr). Still no operator integration.
4. **Phase 3**: `SortExec` external merge sort. Most independent;
   smallest blast radius; first operator to actually exercise spill.
5. **Phase 4**: `HashAggregateExec` spill.
6. **Phase 5**: `DistinctExec` spill (mostly free given Phase 4).
7. **Phase 6**: `HashJoinExec` grace hash join (requires plan 04).
8. **Phase 7 (optional)**: `WindowExec` partition-internal spill.
9. **Phase 8**: stress-test target + docs.

Phase 1 separately and first matters: it lets you land the config API
in main and start writing tasks that *declare* `spill: true` even
before any operator implements spill. The operators come online one
by one; tasks already configured pick them up automatically.

Strongly recommend this is the **last** plan to land — it depends on 01
and 04, and most workloads don't need it. Land it when production data
growth actually demands it.

## Docs to update

- `docs/architecture.md` — new section on spill.
- `docs/gotchas.md` — remove the "No spill-to-disk" entry from "What's
  intentionally NOT done".
- `docs/conventions.md` — pattern for new operators considering spill.
- `docs/extending.md` — recipe for adding a new spill-capable operator.
- `docs/code-map.md` — `core/Spill.scala`, `core/AggStateSerde.scala`.
- `README.md` — if spill is exposed as a user-visible config, document
  the system properties.

## Launch prompt

```
Read plans/perf/09-spill-to-disk.md and implement it end-to-end.

PREREQUISITES: plans 01 (k-way merge) and 04 (hash-partition breakers)
must be landed. If they aren't, stop and ask before starting — phases of
this plan strictly depend on them.

Use max effort. This is a multi-week structural change. Honor CLAUDE.md:
no new heavy deps (use stdlib + existing parquet stack for spill format),
bazel test //... must pass, jaffle_shop deploy jar must hit 15/15
Succeeded, polymarket deploy jar must hit 15/1/1. Land docs in the same
PRs.

CONFIGURATION IS PER-TASK, DEFAULT OFF. Spill is opted into via
output.json's options.spill = "true" — never via a global system
property. The only system property is transformer.spill.dir (temp
directory location). If you find yourself wanting to add a global
"enable spill everywhere" flag, stop and ask.

Phase 1 lands FIRST as a config-plumbing-only PR (ExecutionOptions
threaded through SqlExecutor → SqlEngine → PhysicalPlanner → operators
+ DataJob.runOneTask reading options.spill from output.json). All
existing tests pass; no behavior change yet. This unblocks downstream
phases and lets the codebase declare spill in output.json before any
operator supports it.

Follow the 9 phases. CountDistinct AggState is excluded from spill in
v1 — document the heap limit instead of building serialization for
HashSets.

Critical correctness gate: every spill-capable operator must produce
bit-equal output with spill enabled (low threshold) vs disabled.
Build that parity check into the test suite for every Phase.

Spawn parallel sub-agents for: (a) writing the AggStateSerde tests in
parallel with implementation, (b) writing the stress-test target for
above-heap workloads, (c) verifying temp-file cleanup correctness
across normal and crash exits.

Stop and ask before: (a) attempting CountDistinct spill, (b) introducing
any new spill format beyond parquet, (c) modifying anything outside
core/ and the operator files in scope, (d) adding a global
"enable spill" toggle, (e) auto-enabling spill on heap pressure (deferred
future enhancement).

Include in PR description: result-equivalence test results, wall-time
overhead with spill: false (must be ≤1% — bookkeeping only when the
option is off), wall-time with spill: true at sub-threshold sizes
(must be ≤5%), and at-least-one above-heap workload completing
successfully via per-task opt-in (e.g. a synthetic high-cardinality
GROUP BY in a test job).
```
