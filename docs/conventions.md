# Conventions

Repo-wide rules that aren't enforced by the compiler but bite if you miss
them. Paired with [gotchas.md](gotchas.md) for the language-/library-/JVM-
specific traps.

- **Scala 2.13.16.** rules_scala pins this. Match the version in the reference
  project `~/grid-game`.
- **JDK 21** via `.bazelrc` for the build toolchain. The deploy jar runs on
  JDK 21 or newer — tested through JDK 25. Don't downgrade hadoop-common
  below 3.4.3 without restoring a JDK-23 runtime ceiling (see
  [gotchas.md](gotchas.md)).
- **`sealed trait` lives in the same file as its case classes/objects.** Scala
  enforces this; if you split, you'll get the "illegal inheritance from sealed
  trait" error. The original `PhysicalPlan` was `sealed`; it's now an open
  trait because the operator implementations are in separate files
  (`PhysicalPlan.scala`, `AggregateExec.scala`, etc.).
- **`ColumnarBatch` is `final`** — don't subclass it. Use the factory
  `RowView.apply(schema, values)` or the mutable `RowBuf` for 1-row batches.
- **No emojis** in code or docs unless explicitly requested.
- **No `*/` inside Scaladoc** — the Scala lexer closes the comment at the first
  `*/`. Glob examples like `data/*.csv` inside a `/** */` block are a footgun;
  use `'*'.csv` or move the example to plain prose. We hit this twice.
- **Don't pre-declare `private val`s used by class-body initializers below
  them.** Scala initializes vals in source order. Put lookup tables above the
  code that reads them (see `CsvWriter`'s `needsQuotingChars` issue we fixed).
- **Prefer dependencies on direct uses.** rules_scala uses strict-deps; if you
  call into a type that lives in `core`, you must list `//src/main/scala/com/transformer/core`
  even when an indirect dep already brings it in. The error message is "Symbol
  'term com.transformer.core' is missing from the classpath."
- **Never use Calcite, DuckDB, or any other embedded SQL engine.** JSqlParser
  is the only SQL dep, and only as an AST source. The whole point of the
  project is that the planner + executor are hand-built — see the opening
  paragraph of [CLAUDE.md](../CLAUDE.md). If a feature looks impossible
  without one, stop and ask the user.
- **Selectivity / cardinality constants live as named `private[plan] val`s**
  in `LogicalPlanCardinality.scala` (`SelectivityEq`, `SelectivityRange`,
  `SelectivityIsNull`, …). New shapes added to `filterSelectivity` should
  follow the same pattern — name the constant, document the source ("from
  Spark's defaults" / "from profiling jaffle_shop"), and pin tests to the
  named constant rather than the literal value so the test stays meaningful
  when the constant moves. The same convention applies to the planner's
  `JoinSwapRatio` / `NestedLoopMaxRows` thresholds.
- **Logical-plan rewrites are explicit passes called in order, not a rule
  engine.** `LogicalOptimizer.optimize` calls each pass by name (`FilterPushdown`,
  `ColumnProjectionPushdown`) in a fixed order — adding a new pass is one
  more line. Don't introduce a generic optimizer-pass framework. A rewrite
  that touches `ColRefExpr` indices must do its own remap on every
  affected expression and validate the result (`ColumnProjectionPushdown.verify`
  is the model: walk the rewritten tree, assert every `ColRefExpr` matches
  the child schema by index range and `dataType`). Shared helpers around
  join-level expressions (`sideOf`, `shiftToRight`) live in
  `JoinSideAnalysis.scala` so the planner and any new pass can reuse them
  without duplicating the recursive Expr traversal.
- **Pipeline-breaking operators that key into a HashMap go through
  `core/HashKeys.scala`'s `KeyCodec`, not raw `Seq[Any]`.** That's the
  shared abstraction `HashAggregateExec`, `HashJoinExec`, `DistinctExec`,
  and `WindowExec` use to skip per-row Seq allocation + boxed-element walk
  hashing. New per-row hashing in the engine should land on the same path —
  `KeyCodec.forColumns` picks the packed-bytes or object-array
  representation from key types; the ColRefExpr fast path
  (`encodeFromBatch`) is the boxing-free fast path. See
  [architecture.md §2a](architecture.md#2a-keycodec--packed-keys-for-pipeline-breakers).
- **Hot `Expr` subtypes override `evalVec`; everything else inherits the
  default boxed loop.** The rule of thumb: anything that shows up in a
  pipeline operator's per-row inner loop (Filter predicate, Project
  expression, GROUP BY / JOIN / WINDOW key) is hot enough to override.
  Every new override extends `ExprBatchTest` first with NULL-handling
  parity, divide-by-zero parity, and per-type result-shape parity — never
  the other way around. The property-based `ExprParityFuzzTest` is the
  generative net over that gate; keep `ExprGen` in sync when you add an
  `Expr` node (see [extending.md](extending.md#add-a-property--generator)).
  See
  [architecture.md §5a](architecture.md#5a-vectorized-expression-evaluation-evalvec).
  The same pattern applies to `AggState.updateBatch`: primitive states
  override; everything else loops the default per-row `update`. See
  [architecture.md §5c](architecture.md#5c-vectorized-aggregate-state-updates-no-group-by-fast-path).
- **Spill-capable operators thread `ExecutionOptions` through their
  constructor.** `SortExec`, `HashAggregateExec`, and `DistinctExec`
  follow the pattern: an `opts: ExecutionOptions` field, an `spillEnabled`
  guard derived from `opts.spillEnabled` (plus operator-specific
  capability checks like `AggStateSerde.allSpillable`), a lazy
  `spillThresholdBytes` from `Spill.effectiveThresholdBytes`, and a
  `wrapWithSpillCleanup` wrapper around the output iterator. The same
  per-batch input-byte accumulator drives the flush decision; flushed
  state lives in temp parquet files under a per-operator
  `OperatorSpillDir` and is folded back at emit time. New operators
  considering spill MUST add a `*SpillTest` proving bit-equal output
  against the non-spill path at a 1-byte threshold. See
  [architecture.md §2c](architecture.md#2c-spill-to-disk-for-breakers-opt-in)
  and `plans/perf/09-spill-to-disk.md`.
- **Prefer work-helping over managed blocking for nested pool waits.**
  `Scheduler.submitAndAwaitAll` awaits its child tasks with `ForkJoinTask.get()`,
  which from a pool worker work-*helps* — it steals and runs the very sub-tasks it
  awaits, so a deep nested fan-out (breaker over exchange over breaker ...)
  materialises on one worker's stack without starving the pool. Do NOT wrap these
  pool-task waits in `ForkJoinPool.managedBlock` as "insurance": under deep fan-out
  its compensation spawns unbounded spare threads and can wedge the pool —
  plans/bugfixes/02b tried exactly that and was reverted (see gotchas.md). Never
  hold a JVM monitor across a pool-blocking call either (a monitor is invisible to
  work-stealing); the in-tree exemplar of the compliant pattern is
  `ExchangeExec.ensureMaterialized` — a CAS claim + published `CountDownLatch`, so
  no monitor is held across the pool-driven `materialize()` and the loser-wait
  stays a plain (uncompensated) `await()`. The helping mechanism itself is pinned
  by `//src/test/scala/com/transformer/core:scheduler_test`. Know its limit:
  helping is a tree-shape guarantee only — helpJoin can inline non-descendant
  tasks under a worker's stack, and combined with an exactly-once gate that can
  still deadlock K>1 sharded plans (sharding stays off by default; see
  gotchas.md). See
  [architecture.md §3](architecture.md#3-parallel-execution) and
  [gotchas.md](gotchas.md).

## Counter discipline

Per-operator counters wired into the instrumentation framework (see
[architecture.md §2d](architecture.md#2d-per-operator-instrumentation-opt-in))
must follow these rules. Deviations get caught by `OperatorCountersTest`
or by the `DisabledOverheadBench` perf gate; either way it's faster to
land it right than fix it later.

- **Counters are `Array[LongAdder]`, never `Map[String, Long]`.** Storage
  is sized at `MetricsNode` construction from the operator's
  `IdxCounterNames` array. Hash-map storage would re-allocate on every
  insert and box the names; the array layout keeps inserts allocation-free
  and the JIT can const-fold the index constants. See
  `core/metrics/MetricsNode.scala`.
- **`Idx<Name>: Int` constants live in the operator's companion object,
  named identically to the JSON key.** Counter constants are
  `final val IdxName: Int = N` so the JIT can const-fold the array
  offset; the parallel `IdxCounterNames: Array[String]` ships the names
  used as keys in `_perf.json`'s counter map. Per-operator unit tests
  in `OperatorCountersTest` assert
  `IdxCounterNames.length == highest Idx<Name> + 1` — adding a new
  counter without bumping the names array trips the test immediately.
- **No allocation on the per-row hot path.** Counter writes are
  `if (metricsNode != null) metricsNode.counters(IdxX).add(d)` — one
  branch, one array load, one `LongAdder.add` (allocation-free in
  steady state). Don't read `getClass.getSimpleName` or build a
  formatted string on the hot loop; do that work in
  `PhysicalPlanner.plan` when the `MetricsNode` is constructed and
  ship the result via the counter-names array.
- **`AggStateSerde`'s on-disk format must never change for
  instrumentation.** Spill files written by a metered run must be
  binary-compatible with an unmetered restore. Timing instrumentation
  goes through the additive `SerdeStats` overload that
  `AggStateSerde.serialize(state, stats)` /
  `deserialize(bytes, stats)` accept — never inside the encoded bytes.
- **Disabled-path cost is the most important invariant.** When
  `opts.metricsEnabled = false`, the operator tree returned by
  `PhysicalPlanner.plan` must be byte-for-byte the un-wrapped tree
  it was before this work; the only added cost is one branch +
  load in `PhysicalPlanner.plan`. The
  `DisabledOverheadBench` microbench under `benchmarks/micro/` gates
  this at < 1%.
