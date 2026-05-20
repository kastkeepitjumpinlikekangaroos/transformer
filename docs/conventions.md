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
