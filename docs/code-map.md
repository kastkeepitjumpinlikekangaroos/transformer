# Code map

Where the bulk of the code lives, and the external reference material that's
useful when a request is ambiguous. Keep this list trimmed to the actually-
biggest files (or the ones that disproportionately attract changes) — it's a
navigation hint, not a comprehensive directory listing.

## File-size hot spots

- `sql/plan/LogicalBuilder.scala` (~855 LOC) — biggest file. Pattern matches
  every JSqlParser expression node. If you're adding a syntax feature, this
  is probably where it lands.
- `gui/JobSession.scala` (~795 LOC) — mutable FX-thread state for the GUI;
  also tracks per-input UI state (Pending/Loading/Loaded/Failed) now that
  inputs flow through the unified scheduler.
- `sql/exec/AggregateExec.scala` (~790 LOC) — every `AggState` subclass plus
  the codec / LongHashMap GROUP BY paths. Primitive states (`CountStarState`,
  `CountState`, `CountIfState`, `LongSumState`, `DoubleSumState`, `AvgState`,
  `MinMaxState`) override `updateBatch` to read typed `ColumnVector`s
  directly for the no-GROUP-BY fast path.
- `job/DataJob.scala` (~720 LOC) — runner orchestration: unified input + task
  DAG scheduler (`runUnifiedDag`), writeOutput, validation re-read, per-status
  `_run.json` writes + per-failure `_validation-<slug>.csv` sample writes +
  per-job `job.json` write + consistency checks.
- `sql/plan/Expr.scala` (~560 LOC) — `Expr` ADT plus `eval` and `evalVec`
  per subtype. `FuncExpr`, `CaseExpr`, `InListExpr`, `LikeExpr` each carry
  an `evalVec` override; the rest of the hot subtypes delegate to `VecOps`.
- `sql/plan/Funcs.scala` (~550 LOC) — scalar function registry. `Funcs.apply`
  is the row-form dispatcher; `Funcs.applyVec` + the `VecFuncs` object
  carry the vectorized implementations for the hot subset (COALESCE,
  string ops, ABS/FLOOR/CEIL/ROUND/TRUNC, IF, NULLIF, SUBSTRING).
- `gui/ResultsTabPane.scala` (~360 LOC) — partition picker + background
  output loader + run-log rendering.
- `core/ColumnarBatch.scala` (~320 LOC) — defines ten `ColumnVector`
  subclasses. Adding a new `DataType` requires a new vector + companion case
  in `ColumnVector.allocate`.
- `gui/DagCanvas.scala` (~300 LOC) — Canvas drawing + pan/zoom/click; renders
  per-input load state alongside per-task status.
- `sql/exec/JoinExec.scala` (~410 LOC) — equi-join build + probe paths with
  build/probe role mirroring, the LongHashMap fast path, and per-batch
  `evalVec` key extraction for computed join keys on both sides.
- `sql/exec/WindowExec.scala` (~410 LOC) — partition, sort, frame computation
  for every supported window function; pre-computes per-spec partition/order
  keys per row during the single materialization pass.
- `core/HashKeys.scala` (~520 LOC) — `KeyCodec` (`PackedBytesCodec`,
  `ObjectArrayCodec`, `EmptyKeyCodec`) + `BytesKey` / `ObjectArrayKey`
  wrappers + `LongHashMap[V]` (open-addressing primitive-long-keyed map for
  the single-Long fast path in HashAggregate / HashJoin). Used by every
  pipeline-breaking operator that keys into a HashMap (HashAggregate /
  HashJoin / Distinct / WindowExec partition keys). See
  [architecture.md §2a](architecture.md#2a-keycodec--packed-keys-for-pipeline-breakers).
- `core/Scheduler.scala` (~80 LOC) — the shared `ForkJoinPool` every parallel
  call site funnels through. Daemon threads, default size `2 × availableProcessors`
  (override via `transformer.scheduler.parallelism` system property or
  `TRANSFORMER_SCHEDULER_PARALLELISM` env var).

### Test suites

- `src/test/scala/com/transformer/sql/plan/ExprBatchTest.scala` (~1030 LOC)
  — the parity gate for every `Expr.evalVec` override. New overrides extend
  this first; see [testing.md](testing.md) for the coverage matrix.

## Useful pointers

- **The brief:** [`INIT.md`](../INIT.md) at the repo root. Read it if a
  request is unclear about intended behavior.
- **The reference project:** `~/grid-game` — Bazel + rules_scala setup,
  BUILD file conventions, and JavaFX-on-Bazel wiring (platform classifier
  jars, Canvas + GraphicsContext rendering, mutable-state-with-manual-
  refresh UI pattern) match this repo's GUI module directly.
- **JSqlParser docs:** search `net.sf.jsqlparser` on Maven Central / GitHub.
  The jar at
  `/private/var/tmp/_bazel_owenchristie/.../jsqlparser-5.0.jar` can be `unzip
  -l`'d to inspect available classes — useful when guessing class names.
