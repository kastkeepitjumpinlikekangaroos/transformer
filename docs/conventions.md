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
- **Source files are plain text.** Spell a control character in code (`0.toChar`,
  or an escape inside a string literal), never as the raw character:
  `job/Json.scala` once held a raw NUL in a char literal, which made git, `file`
  and editing tools treat the whole file as binary. Build test text past ASCII
  from code points (`new String(Character.toChars(0x1F600))`) for the same
  reason.
- **Case folding and formatting name `Locale.ROOT`.** `toLowerCase` and
  `toUpperCase` with no argument, `String.format`, an `f"..."` interpolator and
  `DateTimeFormatter.ofPattern(pattern)` read the JVM's default locale, under
  which a Turkish machine folds `I` to a dotless i (so `min`, a view called
  `Items` and `CAST(x AS int)` stop matching) and some locales print digits that
  are not ASCII (a part file named in Thai numerals). Every such call in a module
  a job runs names `Locale.ROOT`: `name.toLowerCase(Locale.ROOT)`,
  `"part-%05d.csv".formatLocal(Locale.ROOT, i)`.
  `job:locale_independence_test` runs under tr-TR and fails on any compiled call
  of the default-locale forms. The desktop GUI and the data viewer fold names,
  keywords and keys the same way, and the test scans their jars for
  `toLowerCase()` and `toUpperCase()`; their display text may format a number in
  the user's locale, but text something parses, such as CSS, names
  `Locale.ROOT` too.
- **No `*/` inside Scaladoc** — the Scala lexer closes the comment at the first
  `*/`. Glob examples like `data/*.csv` inside a `/** */` block are a footgun;
  use `'*'.csv` or move the example to plain prose. We hit this twice.
- **Don't pre-declare `private val`s used by class-body initializers below
  them.** Scala initializes vals in source order. Put lookup tables above the
  code that reads them (`CsvWriter` writes its header in its class body, so
  its encoders are declared above that block).
- **A file format's text for each type lives in one place, for both
  directions.** `read/csv/CsvValues` holds what the CSV writer prints for a
  value of each type, what the reader parses (strictly, and the same for a
  declared column as for an inferred one), and what inference recognizes, so a
  value the writer prints is one the reader reads back; the writer depends on
  `read/csv` for it. A type a format gains gets its print, its parse and its
  inference there together, and `fuzz/csv_fuzz_test` checks they compose.
- **Prefer dependencies on direct uses.** rules_scala uses strict-deps; if you
  call into a type that lives in `core`, you must list `//src/main/scala/com/transformer/core`
  even when an indirect dep already brings it in. The error message is "Symbol
  'term com.transformer.core' is missing from the classpath."
- **The GUI's session state builds without JavaFX.** `JobSession` and
  `DagLayout` are their own target, `//src/main/scala/com/transformer/gui:session`,
  with no JavaFX dependency; `gui` exports it, so a panel sees it as before. Keep
  every `javafx.*` import out of both files: the session tests and the fuzz
  harness depend on `gui:session` precisely so they run without the toolkit, and
  a fuzz target never takes a JavaFX dependency. State a panel needs from a
  toolkit type stays in the panel.
- **The vim engine builds without JavaFX too.** `VimKey`, `VimCommand`,
  `VimText` and `VimEngine` are `//src/main/scala/com/transformer/gui:vim`, with
  no dependency; the SQL editor implements `VimBuffer` over its text area, and
  `VimKeyEvents` turns JavaFX key events into `VimKey`s. Keep JavaFX out of the
  four files, so `gui/vim_engine_test` drives the engine with strings. A case
  there states what Neovim (with its defaults and `shiftwidth=2 expandtab`)
  leaves from the same text and keys: check a new one against `nvim --clean`
  rather than memory of vim, which is how its cursor rules were settled.
- **What a chart draws builds without JavaFX.** `ChartData` (what to draw and
  the series a result's rows make) and `ChartScale` (ticks and labels) are
  `//src/main/scala/com/transformer/gui:chart`, and `gui/chart_test` drives
  them with batches. `ChartPane` and `ChartCanvas` lay out, paint and ask; a
  rule about what a chart draws (how rows combine, the order of bands, what
  folds into Other) goes into `ChartData`, with a test, not into the canvas.
- **GUI colours and spacing live in `gui/transformer.css`, in the theme's
  looked-up colours.** A control takes style classes (AtlantaFX's `Styles` and
  `Tweaks` constants, or the GUI's own classes), never `setStyle` with literal
  colours; the canvas paints with `Theme.Palette`, which holds the same
  colours. A chart's series take `ChartCanvas.SeriesColors`: eight hues in a
  fixed order, checked as a set for colour-blind separation and for 3:1
  against the background, and apart from the status colours. A ninth series
  is never a ninth colour; it joins Other, in grey. Every `Scene`, `Dialog`
  and `Alert` the GUI makes goes through `Theme.style`: the theme reaches it
  as the user-agent stylesheet, the GUI's own classes only through that call. The data viewer's classes are in the
  same file, under their own heading.
- **What both GUIs show alike is one component, in `gui:components`.** The job
  GUI and the data viewer share the theme, the SQL editor (its highlighting
  and vim keys), the SQL console (`QueryConsole`, with its chart,
  `ChartPane`), the grid rows show in (`DataGrid`) and the canvas graphs are
  drawn on (`GraphCanvas`). A piece one
  app needs from the other moves there, public, rather than being copied, and
  it takes what it needs from the app as arguments (a catalog builder, a
  default directory, the window's shortcuts), never an app's session. What
  only one app does stays in `gui` or in `viewer`.
- **The data viewer's model builds without JavaFX.** `RunFolder`, `Lineage`,
  `LineageLayout` and `ViewerSession` are `//src/main/scala/com/transformer/viewer:model`,
  so `viewer/model_test` runs real jobs into a folder and reads them back with
  no toolkit; keep `javafx.*` imports out of those four files, as out of
  `gui:session`. The viewer reads only what runs record, never a job
  directory: what it needs to know about a run goes into `job.json` or
  `_run.json`.
- **The CLI's logic stays out of the entry point.** `cli/Cli.scala` (parsing,
  the headless run, exit codes) has no JavaFX dependency, and `cli_test`
  drives it with captured streams. `TransformerMain` only dispatches: `gui` to
  `GuiApp`, everything else to `Cli`, then calls `System.exit`. A new
  subcommand goes into `Cli.parse` and gets its own test, not a branch in
  `main`.
- **`site/` is user documentation.** Its commands, CLI output samples and
  benchmark numbers make the same claims as README. Update both together, and
  quote output and timings from a real run (with machine and date) rather than
  writing them by hand.
- **Never use Calcite, DuckDB, or any other embedded SQL engine.** JSqlParser
  is the only SQL dep, and only as an AST source. The whole point of the
  project is that the planner + executor are hand-built — see the opening
  paragraph of [CLAUDE.md](../CLAUDE.md). If a feature looks impossible
  without one, stop and ask the user.
- **A rewrite that rebuilds an `Expr` with new children uses `copy`.**
  `l.copy(child = ..., pattern = ...)`, never `LikeExpr(newChild, newPattern,
  l.negated)`: rebuilding from listed parts silently resets any field the
  rewrite did not name to its default, which is how an ILIKE would turn back
  into a LIKE inside `ColumnProjectionPushdown`. The pushdown, `JoinSideAnalysis`
  and the fuzz `Shrinker` all rebuild `LikeExpr` this way.
- **Every JSqlParser getter the binder does not read is accounted for, and a
  clause the engine cannot honour is rejected, never half-bound.**
  `LogicalBuilder` reads the AST through getters, so an unread getter is a
  clause that silently vanishes. `sql/plan/UnsupportedClauses.scala` lists each
  one as a rejection (with an example that reaches it) or as inert (with the
  reason), and `sql/plan:unsupported_clauses_test` derives what the binder reads
  from its bytecode and fails on any getter in neither list or in both. When a
  node carries something the runtime does not implement, reject it with a message
  naming the clause rather than binding the part you understand —
  `TrimFunction`'s extended forms and `COUNT(ALL x)` are the cautionary cases.
  See [extending.md](extending.md#honor-a-clause-the-binder-rejects).
- **Text JSqlParser accepts and leaves out of the AST is refused where it
  shows.** The audit sees getters, not text the parser never turns into one: a
  second statement, the first of a repeated clause, a NOT hung on the wrong
  operand. Refuse such a shape in `SqlParser` when only the text shows it, or
  in the binder when the AST carries a shape no faithful parse produces, and
  add it to `SqlTextGen.BeyondTheTable`, which requires the refusal in every
  context. To find text the AST lacks, compare the text with what the
  statement renders back rather than scanning the text for keywords:
  JSqlParser takes `limit`, `offset` and `order` as column names.
- **Prefer a bind-time error to another engine's permissive coercion.** When
  SQL could mean more than one thing, or means something only by an engine's
  implicit conversion (a number or a timestamp compared as text, a date
  promoted to a midnight, an integer read as an epoch, text read as false),
  make it an error that names the CAST or typed literal to write, rather than
  copying whichever engine is most lenient. A literal whose reading is
  unambiguous is read at bind time, so a bad one fails before the query runs.
  `Analyzer.unify` holds the rule for values that meet, `Analyzer.branches`
  the same rule for values that become one result (whose type must hold each
  of them), and `Analyzer.checkCast` and `Casts` the rules for CAST; follow
  them when adding a type, or a construct whose answer is one of its values.
  A construct that reads a truth value takes a BOOLEAN through
  `Analyzer.condition`, never an implicit cast to one, and a function declares
  what each of its arguments must be beside its arity (`Funcs.signature`), so
  nothing is read as the type a function wanted.
- **A failure the user caused is an `IllegalArgumentException` in SQL's words,
  raised at bind time unless a value causes it.** Never an internal exception
  class (`UnsupportedOperationException`, `IllegalStateException`, a
  `ClassCastException` from a type the binder let through), never a JSqlParser
  class name or a node's `toString` in the message (it can throw, and it names
  the parser's classes), never an internal slot name such as `_agg0`. A failure
  that waits for the rows is right only when a value causes it — a string a CAST
  cannot read, a decimal that overflows its type; anything the binder could know
  is refused before the first row, so a job task fails at its start and not
  part-way through. Refuse a construct the binder has no case for with
  `UnsupportedClauses.refusal`, which names it in SQL's words, and let a failure
  on the pool reach the caller as itself: wait for pool work with
  `Scheduler.Submitted.await` or `submitAndAwaitAll`, never `ForkJoinTask.get`,
  whose `ExecutionException` carries a chain of class names for a message.
  `oracle/CleanRejection` (`fuzz/frontend_fuzz_test`) holds every failure to
  this; its known defects are where the engine does not yet
  ([plans/robustness/14](../plans/robustness/14-pathological-nesting.md)).
- **A literal is its value and a name is what it names, read from what was
  written, never from a getter that lost part of it.** JSqlParser keeps both as
  text: a string literal with each quote doubled (`StringValue.getValue`; the
  value is `getNotExcapedValue`), an identifier with its quotes, an integer as a
  `long` that throws past BIGINT (`LongValue.getValue`; `getBigIntegerValue`
  holds every one), a number with a point as the DOUBLE it rounds to
  (`DoubleValue.getValue`; the digits are its `toString`). Match every name the
  binder resolves (a column, a table, an alias, a CTE, a job's table reference
  in `TaskDag`) through `SqlParser.unquote`, build a name that must read back
  exactly with `SqlParser.quote`, and read a number beside a DECIMAL as the
  decimal it is written as, through `LogicalBuilder.besideDecimals`, wherever
  values meet. A text the binder compares two expressions by
  (`LogicalBuilder.canonicalSql`) folds only what SQL folds: names, never a
  literal. Where two spellings must be one expression, a GROUP BY key and the
  SELECT item that reads it, compare the bound expressions, which see through
  parentheses, qualifiers and case, rather than the text.
- **A slot the binder makes is addressed by its position, never by a name.**
  A name the binder invents (`_agg0`, `_win0`, `_order0`) is one a query can
  write too, so a lookup by name finds the user's column first; resolve a
  user's name against the input, as WHERE does, and reach an aggregate, a
  window or a sort key through the index the binder put it at.
- **An operator that reads an expression twice must not be given one that
  reads `RAND()`** (`Expr.readsRandom`): an exchange or a spill bucket that
  reads a key to route a row, and the breaker that reads it again, see two
  different values. Keep such a key on a path that reads it once, and bind a
  construct that reads an operand more than once (a simple CASE, BETWEEN) to
  a node that reads it once when it reads `RAND()`.
- **Text is counted in characters, Unicode code points**, as SQL counts it
  (`Funcs.length`, `Funcs.substring`), never in Java's UTF-16 chars, and a
  pattern is read a code point at a time: half a surrogate pair is no text,
  and a spill or a file writes it as `?`.
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
  `JoinOrder`, `ColumnProjectionPushdown`) in a fixed order, each through a method of
  `LogicalOptimizer` that hands its output to `PlanValidator.afterPass` —
  adding a new pass is one more method and line. Don't introduce a generic
  optimizer-pass framework. A rewrite that touches `ColRefExpr` indices must
  do its own remap on every affected expression; the validator then checks
  every `ColRefExpr` against its input's schema by index range and
  `dataType`, and that the pass kept the plan's output. Shared helpers around
  join-level expressions (`sideOf`, `shiftToRight`) live in
  `JoinSideAnalysis.scala` so the planner and any new pass can reuse them
  without duplicating the recursive Expr traversal.
- **A bound plan prints as SQL that binds back to it.** `PlanSql` is
  binding's inverse, and the round trip (`plan_sql_test`,
  `plan_roundtrip_fuzz_test`, the slt `reprinted` mode) holds every plan the
  binder makes to it. A change to what the binder makes for some SQL, or a new
  node, keeps the two in step in the same commit: the printer writes the new
  shape as the SQL that binds to it, and the binder binds a construct by what
  it means rather than how it is spelled (each aggregate and window function is
  computed once, whatever spelling reads it), since the printer writes one
  spelling of each.
- **A walk over the parsed tree or the bound plan stays within
  `SqlParser.MaxNesting`'s margin, and a rewrite rebuilds a long list
  balanced.** Every walk recurses about a level at a time, and the limit (256
  levels) is half the depth the shallowest walk overflowed at on a 1 MB
  thread stack, before the JIT. A walk that recurses several frames a level,
  or a big frame, eats that margin: measure it at `-Xss1m`. A pass that
  splits a predicate into its conjuncts and joins what it keeps uses
  `JoinSideAnalysis.conjunction`, a balanced tree, never a left fold, which
  turns a regrouped 5000-term WHERE back into a 5000-level chain; the same
  for any list a rewrite turns into nested binary nodes (`balancedUnion`
  does it for a UNION's arms). See [gotchas.md](gotchas.md).
- **Every key hash goes through `KeyHash`.** A new hash site (a map keyed by
  row values, a shard or a spill bucket chosen by a hash) wraps its hash in
  `KeyHash(h)`, so `--config=hashzero` makes it 0 with every other one, and the
  whole suite under that config shows whether the site tells keys of one hash
  apart. A test that asserts hashes spread keys says so with
  `assumeFalse(KeyHash.zero)`.
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
  the other way around. On floating-point operands, `NaN` / `-0.0` parity
  too: equality and ordering both go through `Ops.floatCompare`, in both
  paths, so a vectorized helper may not reach for the raw `<` or `==` just
  because the operands are `double`. Two integers
  compare as longs and a decimal exactly, in both paths, so neither may be
  read through `readDouble`. The
  property-based `ExprParityFuzzTest` is the generative net over that gate;
  keep `ExprGen` in sync when you add an `Expr` node (see
  [extending.md](extending.md#add-a-property--generator)).
  See
  [architecture.md §5a](architecture.md#5a-vectorized-expression-evaluation-evalvec).
  The same pattern applies to `AggState.updateBatch`: primitive states
  override; everything else loops the default per-row `update`. See
  [architecture.md §5c](architecture.md#5c-vectorized-aggregate-state-updates-no-group-by-fast-path).
- **Anything that ORDERS values compares through the one total order,
  wherever it lives.** Boxed: `Ops.cmp`. Primitive `double` slot:
  `Ops.floatCompare`, the same order without the boxing (`MinMaxState` keeps
  `java.lang.Double.compare`, finer only in ordering `-0.0` below the `0.0` it
  equals, so that of two equal zeros its answer does not move with row order). The
  rule is not confined to `evalVec` — `SortKey.compare` (the one comparison
  `SortExec.rowOrdering` and `WindowExec`'s partition sort both call, which is
  also where NULL placement lives — never re-implement a null-aware comparison
  at a new sort site), `Funcs.pickByOrdering` and `MinMaxState`'s accumulator
  are all ordering sites,
  and `MinMaxState`'s was the one that reached for the raw `<` because its slot
  was a primitive. Every comparison against `NaN` is false in both directions,
  so a raw `<` silently makes the answer depend on the order values arrive in.
  An accumulator makes that worse than an expression does: it also depends on
  partition fan-out and spill flush order, which no `eval`/`evalVec` parity gate
  can see.

  **A TIE test is an ordering site too**, and it is the one this rule has been
  broken at three times now: `evalVec`'s comparisons, `MinMaxState`'s
  accumulator, and `WindowExec.sameKey`, the `RANK`/`DENSE_RANK` peer test, which
  asked `Ops.eq` whether two rows tied while the sort beside it asked `Ops.cmp`.
  Where a tie decides an answer, compare with the comparison that produced the
  order, `cmp(a, b) == 0`. Since plans/robustness/21 `eq` is the same test (it
  differed on `NaN` and `-0.0` / `0.0`, and the bug hid until the corpus
  carried those values), but the sort's own comparison is the one that cannot
  drift from it. See [gotchas.md](gotchas.md).

  **Anything that HASHES, GROUPS or DE-DUPLICATES values agrees with `Ops.eq`**,
  and that is the fourth time the family has bitten: GROUP BY collapsed byte
  arrays `=` called different and split `1.0` from `1.00`, which `=` called
  equal. The JDK's own equality is not SQL's for three types the engine carries: a
  byte array's `equals` and `hashCode` are its identity, a `BigDecimal`'s
  include its scale, and a `double` cannot hold every `long` past 2^53. So
  compare and hash a byte array by content (`Arrays.equals`, `Arrays.hashCode`,
  `Arrays.compareUnsigned`), a decimal by value (`compareTo`; turn it into a key
  with `KeyCodec.decimalKey`, and route it with `HashPartitioner.decimalHash`),
  and two integers as longs, never through `doubleValue`. Every hash site has to
  answer the same question as `=`, including the ones that only route rows: a
  shard (`HashPartitioner.hashCell`) or a spill bucket
  (`HashJoinExec.columnHashCode`) that separates two equal keys loses the match
  as surely as a hash map would. A float keys and hashes as
  `KeyCodec.floatKeyBits` / `floatKeyValue`, `-0.0` as `0.0`
  ([architecture.md §2a](architecture.md#2a-keycodec--packed-keys-for-pipeline-breakers));
  its raw bits and `Double.equals` separate two values `=` calls equal.
- **Integer arithmetic goes through `Integers`.** An INT or BIGINT result
  past its type is an error naming the operation (`Integers` in `Ops.scala`),
  on the row path, in `VecOps`' loops, in `ABS` and in every CAST to an
  integer; plain `+`, `*` or `Number.intValue` wraps or saturates silently. An
  aggregate that adds integers keeps the exact sum and checks it once, at the
  end, so whether it fails does not depend on row order or partitions
  (`LongSumState`'s 128-bit sum).
- **A date or a timestamp enters a vector through `DateVector.epochDay` and
  `TimestampVector.epochMicros`.** They give the INT of days or the BIGINT of
  microseconds a value is stored as, and an error naming one past it; plain
  `toEpochDay.toInt` and `getEpochSecond * 1000000L` wrap it round to another
  date. A CAST checks its result the same way (`Casts.storable`), since on the
  row path the value stays a `LocalDate` or an `Instant`, which reach further
  than the column it lands in.
- **A decimal result is computed exactly and fitted to its declared type,
  once.** An operation or aggregate that produces a `DECIMAL` declares its type
  through `Decimals` (`resultType`, `sumType`, `avgType`, or a rule of the same
  shape), computes in `BigDecimal`, which does not round, and fits the answer
  with `Decimals.fit` (rounded half up to the scale, an `ArithmeticException`
  past the precision) as late as it can: an aggregate accumulates exactly and
  fits in `finish`, so its answer cannot move with row order, merge order or
  spill. Two mistakes this rules out, both made before: computing through
  `doubleValue` while declaring a `DECIMAL` (`SUM` and `ABS` did), and storing a
  value its declared type does not hold, which the parquet writer refuses rather
  than rounds, so the query fails only when it spills.
- **Spill-capable operators thread `ExecutionOptions` through their
  constructor.** `SortExec`, `HashAggregateExec`, and `DistinctExec`
  follow the pattern: an `opts: ExecutionOptions` field, an `spillEnabled`
  guard derived from `opts.spillEnabled` (plus operator-specific
  capability checks like `AggStateSerde.allSpillable`), a lazy
  `spillThresholdBytes` from `Spill.effectiveThresholdBytes`, and an
  output iterator wrapped by `OperatorSpillDir.closeOnDrain` (each
  operator's `wrapWithSpillCleanup` delegates to it). The same
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
  tasks under a worker's stack, and combined with an exactly-once readiness gate
  that deadlocks K>1 sharded plans (see gotchas.md). See
  [architecture.md §3](architecture.md#3-parallel-execution) and
  [gotchas.md](gotchas.md).
- **A worker posts what its scheduler waits for in a `finally`, and turns
  every `Throwable` into its work's failure.** `DataJob.runUnifiedDag` waits
  for one event per input and per task; a worker that catches `NonFatal`
  alone lets an `Error` (a stack overflow, a `LinkageError`, running out of
  memory) end it without one, and the run waits for ever. Whether a failure
  stops the run is a separate decision (`stopsTheJob`: the JVM's own failures
  but a stack overflow), made where the failure is caught.
- **Never let a pool task wait on a lazily-shared breaker result — publish it
  first.** Engine plans pre-materialize every `ExchangeExec` bottom-up via
  `PhysicalPlanner.preMaterializeExchanges` before consumers exist (both
  `SqlEngine` execute paths + `CteResolver.materialize` call it right after
  planning), so no worker ever parks on exchange readiness — the K>1 liveness
  fix (plans/bugfixes/02f). Two obligations follow: (a) a new physical operator
  with children needs a `PhysicalPlanner.childrenOf` branch (a missed branch
  silently exempts its subtree's exchanges from the pass); (b) a new
  exchange-like structure — any once-computed result that multiple pool tasks
  might wait on — must either be wired into the pass or be published eagerly
  before pool tasks can reach it (CTE materialization is the in-tree exemplar).
  Draining lazily-nested exchanges from pool tasks without the pass reproduces
  the 02e wedge (`sql/exec:exchange_deadlock_stress_test` with `STRESS_LAZY=1`
  demonstrates it on demand).

- **A test that depends on a JVM-wide default says so.** The whole suite also
  runs at a batch capacity of 1 and 2 rows, sharded, on one thread and in
  another time zone and locale (`--config=batch1` and the rest,
  [testing.md](testing.md#whole-suite-configurations)). A test that asserts
  something only the default makes true (a batch of 8192 rows, a plan shape the
  default gates choose, two threads at once) starts with `assumeTrue` and the
  reason, or reads its expectation from the setting (`ColumnarBatch.DefaultCapacity`);
  one that merely read the first batch of a scan reads them all.
- **A semantics decision lands with a sqllogictest script.** Write the queries
  it decides, with their expected results checked by hand, in
  `src/test/slt/<area>/`, so every mode is held to them (`slt/slt_test`);
  `--complete` fills a new script's results from the engine, for checking.
- **Every file of a glob is read by its own column names.** A parquet file by
  its footer's, a CSV file by its header's, matched to the first file's names
  regardless of case; a file that names other columns is refused, naming both.
  Reading by position read one column's values into another wherever two
  files ordered them differently.

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
