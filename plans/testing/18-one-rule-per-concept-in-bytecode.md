# Plan 18: one rule per concept, held in bytecode

> Status: **done** 2026-11-02 (3721860 W1 and the migration, 15439bd and
> 608df77 the rule groups, 3f7831e decision 4, a620a3a decision 3; see
> [Outcome](#outcome); the four decisions taken overnight as recommended, not
> yet confirmed by the user; three sites held for the user, none a defect) ·
> from the 2026-11-01 test survey, category 7 of 9 ·
> Effort: 2-3 days · Risk: low (test code, plus the two small main-code
> changes of decisions 3 and 4) · Depends on: none. If
> [06](06-a-failed-run-leaves-no-partial-output.md)'s fault hook halts the JVM
> from main code, it is an allowlist entry of R7

## Why

The engine's one-rule-per-concept decisions live in docs/conventions.md as
prose, and prose holds only the code its author remembers it for.

The ordering rule records its own failure history. "Anything that ORDERS
values compares through the one total order" (docs/conventions.md:286-329 at
HEAD) was broken three times:

- `evalVec`'s comparisons
- `MinMaxState`'s accumulator, which "reached for the raw `<` because its slot
  was a primitive"
- `WindowExec.sameKey`

The equality rule beside it was broken a fourth time: GROUP BY over byte
arrays and decimals. A fuzzer found each one after the fact.

A test over the compiled classes holds code not yet written.
`job:locale_independence_test` already does that for one rule: no class of a
job's modules calls a default-locale conversion or format (plan 16), and no
desktop module folds case in the default locale (plans/testing/04,
decision 6). This plan does the same for the other rules that a compiled
class can show.

## What exists now

- **The locale scan.**
  - LocaleIndependenceTest.scala:135-146: eight banned `(owner, name,
    descriptor)` triples, each with its advice.
  - :149-152: twelve classes, one per module a job runs, whose jars are read.
  - :156-159: five desktop class files, read through the target's
    `runtime_deps`.
  - :161 and :172: the two tests.
  - :213-241: `memberRefs`, a constant-pool reader (JVMS 4.4) that returns
    every Methodref and InterfaceMethodref, without a bytecode library.
- **A second copy of that reader.** UnsupportedClausesTest.scala:259-316
  (`ConstantPool`, :294) returns class names and `(owner, member)` pairs, for
  its drift audit of the binder against JSqlParser.
- **What a constant pool cannot show.** It says what a class calls, not
  where. It holds no opcodes: a raw `<` between two doubles is a `dcmpg`
  instruction in a method's Code attribute, not a reference.
- **Behavioural guards of the same rules:**
  - `fuzz/oracle/KeyEquality` (equality)
  - `--config=hashzero` with `hash_zero_test` (KeyHash)
  - `--config=zone` (locale and time zone)
  - `fuzz/oracle/ExactIntegers` (integer arithmetic)

  Each catches a broken rule once a generated input reaches it. A bytecode
  rule catches the call at compile time, however rare its input.

## What the compiled classes hold today

Measured 2026-11-01 at HEAD `f309205`, over main, examples, benchmarks and
tools compiled in the scratchpad ([07](07-compiler-warnings.md)'s method). The
counts came from a constant-pool reader over every class, and, for opcodes,
from `javap -c` over the 655 classes of the engine and job modules (everything
but `gui`, `viewer` and `bench`). "Engine" below means `core`, `sql`, `read`,
`write`, `job` and `temporal`.

| rule | what it bans | where it is today | verdict |
|---|---|---|---|
| R1 locale | the eight default-locale calls | nowhere (held since plan 16) | migrate as is |
| R2 float ordering and equality | `Double.compare`, `Float.compare` (and `compareTo`, `equals`) in engine code; a raw `dcmpl`/`dcmpg`/`fcmpl`/`fcmpg` between two values, neither a constant | **calls:** `MinMaxState` (`merge`, `updateAt`, `updateBatchAt`) and `Funcs.pickByOrdering`, both the documented tie-break that orders `-0.0` below the `0.0` `Ops.cmp` calls equal (conventions.md:286-296). **Raw ordering:** `Ops.floatCompare` (the one sanctioned site) and `PhysicalPlanner.shouldBuildRight` (size estimates). **Raw equality:** `Ops.floatCompare`, `Funcs.apply`'s POWER test `math.floor(y) != y`, `JsonValue.stringValue`'s integrality test, the synthesized `equals` of `JsonNumber` and `JsonMini.JNum` | worth it: about nine allowlisted methods beside `Ops.floatCompare` (the four tie-breaks, and five raw comparisons that order or match no two SQL values: estimates, integrality tests, JSON numbers), and it would have caught the `MinMaxState` and `evalVec` breaks |
| R3 float key bits | `doubleToLongBits`, `doubleToRawLongBits`, `floatToIntBits`, `floatToRawIntBits` | `KeyCodec` (sanctioned: `-0.0` as `0.0`) and `HashPartitioner.decimalHash` (the bits of a decimal's `doubleValue`, equal for equal decimals) | worth it: two entries |
| R4 string ordering | `String.compareTo` / `compareToIgnoreCase`, `StringOps.compare` and its `<` family, in engine code | `Ops` only. The desktop modules have their own: `gui.DataGrid.ValueOrdering`, which is `Ops.cmp`'s order but for `-0.0` below `0.0` through `Double.compareTo`; `ChartData.Builder`; `viewer.RunFolder` (folder names) | worth it: zero violations to hold |
| R5 clock | `System.currentTimeMillis`, `java.time.*.now`, `Clock.system*` | 10 classes: `DataJob` and an anonymous class of it (record timestamps); `LogicalBuilder` (the statement's start, read by `CURRENT_DATE` and `CURRENT_TIMESTAMP`); `TemporalVariables` (the default execution time); `Cli`; gui `ControlsPanel`, `JobSession`, `ResultPersister`; `bench.MacroBenchRunner`; the polymarket example. `System.nanoTime` (durations, 14 classes) is left alone | worth it: a new clock read inside an operator would make a query's answer move with the time |
| R6 randomness | `java.util.Random`, `ThreadLocalRandom`, `scala.util.Random`, `Math.random`, `UUID.randomUUID`, `SplittableRandom` | engine: `Funcs` (`RAND()`) and its `ThreadLocal` initializer; five `bench` classes | worth it: `RAND()` is gated off the sharded and spill paths (audit 3), and a second source would not be |
| R7 library code does not print, exit or sleep | `System.exit`, `Runtime.exit`/`halt`, `printStackTrace`, `System.out`/`err`, `Console.println`, `Thread.sleep`, in engine code | one: DataJob.scala:879 prints "Cloud job-run paths not supported in v1; ignoring ..." to standard error. Every other problem a run meets is a warning in its result and in `job.json` (plans/robustness/08). `cli` and the examples print and exit, as they should | worth it (decision 4) |
| R8 settings | `System.getProperty` / `getenv` of a `transformer.*` or `TRANSFORMER_*` name outside one place | 9 system properties and 7 environment variables, read in 8 classes: `ColumnarBatch`, `KeyHash`, `Scheduler`, `Spill`, `MetricsCollector`, `DataJob`, `ExchangeExec`, `LogicalPlanCardinality`. `transformer.hash_zero` and `transformer.spill.dir` have no environment twin. `TRANSFORMER_MACRO_FORCE_SPILL`, `TRANSFORMER_SCHEDULER_BROADCAST_THRESHOLD` and `TRANSFORMER_SCHEDULER_SHARD_MIN_SIZE` appear in no doc at HEAD, and README names 2 of the 9 properties | worth it (decision 3) |
| R9 dates enter vectors through one door | `LocalDate.toEpochDay`, `Instant.getEpochSecond`/`getNano`, `ChronoUnit.between` outside `DateVector` and `TimestampVector` (conventions.md:337-342) | the two owners only. The other direction, out of a vector or into a duration, is not banned: `CsvValues` (`ofEpochDay`, `ofEpochSecond`, for a value's text), `TaskResult.durationMillis` (JobResult.scala:50, 59) and `TemplateRenderer`'s `epoch_millis` (TemplateRenderer.scala:98, `toEpochMilli`) | worth it: zero to hold, and the into-vector direction is what wrapped dates round (plan 25) |
| R10 default charset and zone | `String.getBytes()`, `new String(bytes)`, `InputStreamReader` / `OutputStreamWriter` / `FileReader` / `FileWriter` / `PrintStream` without a charset, `Charset.defaultCharset`, `TimeZone.getDefault`, `ZoneId.systemDefault`, `Clock.systemDefaultZone` | nowhere | worth it: zero to hold. `--config=zone` tests the zone only where a test reaches |

Not worth a rule, measured:

- **KeyHash** (every key hash goes through it, conventions.md:251-256). A
  constant pool cannot show it. `HashJoinExec`'s companion computes
  per-column hashes (`Arrays.hashCode`, `Object.hashCode`), and the class wraps
  them in `KeyHash(HashJoinExec.fmix32(h))` once combined (JoinExec.scala:909).
  Of the 59 classes in `core`, `sql`, `read` and `write` that call a hash
  function, 53 do so only in a case class's synthesized `hashCode` or in a
  string `match`, which compiles to a switch on `hashCode`. Of the other six,
  five name `KeyHash`; the sixth is that companion, which complies through its
  class. Holding this takes data flow. `--config=hashzero` and
  `hash_zero_test` hold it by behaviour.
- **`BoxesRunTime.equals`** (Scala's `==` on `Any`) in 9 engine classes. It is
  case-class equality of plan nodes and literals (`LitExpr`, `BetweenExpr`,
  `FrameBound`), which the plan comparisons already handle (PlanRoundTrip
  compares float literals by their bits).
- **Integer arithmetic through `Integers`** (conventions.md:330). Raw `iadd`
  and `ladd` are every loop counter. `ExactIntegers` holds the rule by meaning.

## What to build

**W1. One class-file reader, test-only.**
`src/test/scala/com/transformer/bytecode/` (a `testonly` `scala_library`,
`classfile`) reads:

- the constant pool: classes, member references, string constants;
- each `method_info`;
- each Code attribute, decoded instruction by instruction. That takes the
  JVMS opcode-length table, the padding of `tableswitch` and `lookupswitch`,
  and the `wide` prefix: about 150 lines.

Each reference and each comparison opcode is attributed to its method. A
lambda's body is the static method `$anonfun$<enclosing>$N`, so an allowlist
entry names it by its enclosing method.

The two existing readers (LocaleIndependenceTest.scala:213-241,
UnsupportedClausesTest.scala:294-316) are replaced by it. The reader's own
test compiles a fixture object in the test target, holding:

- a `Double.compare` call;
- a raw `<` between two doubles;
- a comparison against `0.0`, and one against an `Int` constant widened by
  `i2d`;
- a string `match`.

Each must be found at the method that holds it.

**W2. The rules as data.** In `BytecodeRules.scala`, each rule has:

- a name;
- its scope (which jars, which class prefixes);
- what it bans (member references, or an opcode pattern);
- the advice the failure prints;
- an allowlist of `(class, method) -> reason`.

An allowlist entry whose method no longer makes the call fails the test, as
SyntaxEquivalence's Known list fails a fixed shape, so an exception cannot
outlive its reason. The test target reads the jars the locale test reads (the
twelve modules and the desktop `runtime_deps`), and runs under tr-TR as that
one does.

**W3. The rules** decision 2 chooses, from the table above, each with
today's allowlist. Each rule gets a sabotage check, recorded in this plan's
Outcome: in a scratch worktree, a violating call fails the test, naming the
rule and its advice.

**W4. The main-code changes the rules ask for:**

- R7's line becomes what decision 4 says.
- R8's settings become what decision 3 says.

Each is its own commit, with tests and docs.

Done when `bazel test //...` (and under `--config=zone`) is green with every
chosen rule live, the two old readers are gone, and the jaffle-shop job
passes.

## Decisions for the user

1. **How fine an exception is.** *Taken overnight as recommended, not yet
   confirmed by the user: (a).*
   - **(a) Per method, through the Code attribute (recommended).** R2's opcode
     half needs it, and so does allowlisting `Funcs.pickByOrdering` without
     exempting every other function in `Funcs`.
   - **(b) Per class, constant pool only.** Cheaper, but R2 keeps only its
     call half, and `Funcs` and `LogicalBuilder` are exempt whole.
2. **Which rules.** *Taken overnight as recommended, not yet confirmed by the
   user: (a).*
   - **(a) R1-R10 as the table recommends, none of the three "not worth it"
     (recommended).**
   - **(b) Constant-pool rules only:** R1, R3-R10, and R2's call half.
   - **(c) Also KeyHash,** as an allowlist of every class that calls a hash
     function, which a new hash site must join.
3. **Settings (R8).** *Taken overnight as recommended, not yet confirmed by
   the user: (a).*
   - **(a) One `Settings` object in `core` (recommended).** It reads every
     `transformer.*` property and its `TRANSFORMER_*` twin, each with its
     default and its line in one table in README and docs/testing.md. The rule
     is then that only `Settings` reads them. `hash_zero` and `spill.dir` gain
     their twins, and the three undocumented variables get their lines.
   - **(b) Scan only.** No code change: the rule requires each name found in a
     constant pool to appear in that table.
   - **(c) Leave settings out.**
4. **R7's one offence.** *Taken overnight as recommended, not yet confirmed
   by the user: (a).*
   - **(a) An unsupported cloud `jobRunOutput` becomes a run warning
     (recommended).** That is plans/robustness/08's rule: a warning never fails
     a run, and every problem a run meets is in its result and `job.json`.
   - **(b) Allowlist it.**

## Decisions already made

- **No bytecode library**, no new dependency (CLAUDE.md;
  plans/testing/README.md:11 and :94).
  - The JDK's internal ASM (`jdk.internal.org.objectweb.asm`) needs
    `--add-exports` and is not an API.
  - `java.lang.classfile` is final only from JDK 24, and the JDK is pinned at
    21.
- **The locale rule moves as it is.** The desktop modules fold case in
  `Locale.ROOT`, and their display text may format a number in the user's
  locale (plans/testing/04, decision 6).
- **Decided exceptions.** `MinMaxState`'s and `pickByOrdering`'s
  `Double.compare` are decided exceptions, documented in conventions.md:286-296.
- **The wall clock.** `CURRENT_DATE` and `CURRENT_TIMESTAMP` read the wall
  clock at the statement's start, not the execution time (README, "SQL
  features"), so `LogicalBuilder` is an allowlisted clock reader.
- **`RAND()`.** It is the engine's one random source, gated off the sharded
  and spill paths (audit 3).

## Tests

- **The reader.** The fixture test of W1.
- **The rules.** `bytecode_rules_test` (W2, W3), with each rule's sabotage
  check run once in a scratch worktree and recorded.
- **The migration.** `locale_independence_test` keeps its behavioural tests;
  its two scans become rules R1 in the new target, or call the shared reader
  in place (decide when writing W2, by which reads better).
- **Decision 4's warning.** A `data_job_test` case: a job whose `jobRunOutput`
  is a cloud path succeeds with one warning naming it, and prints nothing to
  standard error.
- **Decision 3's settings.**
  - A `core` test: each setting's property and its environment twin are read,
    and the property wins.
  - The rule: no other class reads either.
  - A doc test: every setting is in the table.

## Docs

- `docs/conventions.md`: each rule's bullet says `bytecode_rules_test` holds
  it, and how to add an allowlist entry (a method and a reason).
- `docs/extending.md`: "Add a bytecode rule".
- `docs/testing.md`: the inventory gains `bytecode_rules_test` and the shared
  reader.
- `docs/architecture.md`: the module map gains `src/test/.../bytecode/`.
- `README.md`: the settings table (decision 3a), and the warning (decision 4a)
  where cloud paths are described.
- `docs/gotchas.md`:
  - A constant pool does not say which method calls.
  - Lambdas compile to `$anonfun$` methods.
  - A string `match` calls `hashCode`.
- No change to `site/` or `docs/code-map.md` unless the reader passes a hot
  spot's size.

## Risks

- **Compiler output moves.** Lambda names, specialized `$mc...$sp` methods and
  constant folding shift with a Scala patch version. Scala is pinned at 2.13.16,
  and an allowlist entry matches a method name by prefix so that a specialized
  copy is covered.
- **The opcode heuristic misses some constants.** A constant operand is
  recognized only as the instruction just before the comparison, or before the
  load before it (an `iconst` or `bipush` widened by `i2d` counts). It misses a
  constant reached through a local `val`, and such a site needs an allowlist
  entry. Today the heuristic puts 27 comparison methods on the constant side
  and 7 on the two-value side, all named above.
- **A rule holds calls, not meaning.** A `Double.compare` reached through a
  helper in a module the rule does not scan is invisible. The behavioural
  guards (KeyEquality, the whole-suite configs) stay.
- **Cost.** It is negligible. The locale test's two scans took 0.07 s in its
  last run, and the whole target 0.7 s (its log prints `Time: 0,688`, the
  tr-TR spelling).

## Outcome

Done 2026-11-02, overnight on `testing-round-3`, in the commits the launch
prompt asks for: one for the reader and the migration, one per rule group,
one per main-code change.

| Commit | What landed |
|---|---|
| 3721860 | W1 and the migration: `bytecode:classfile` (the reader) and `classfile_test`; `bytecode_rules_test` with the rules as data and R1; `locale_independence_test` keeps its behavioural tests, `unsupported_clauses_test` reads the binder through the shared reader |
| 15439bd | R2, R3, R4 and R9: one float order and equality, one float key's bits, one string order, one door into a date vector |
| 608df77 | R5, R6 and R10: the wall clock, randomness, the default charset and zone |
| 3f7831e | Decision 4 and R7: a cloud `jobRunOutput` is a run warning; the engine does not print, exit or sleep |
| a620a3a | Decision 3 and R8: `core/Settings` reads every setting and its environment twin; README's Settings table; `core:settings_test` |

Every commit: `bazel test //...` green (85 targets, 86 with `settings_test`),
and green again under `--config=zone`; the jaffle-shop job 15/15 Succeeded
with every validation passing. No fuzz campaign ran: the plan adds no fuzz
property, and the default-budget fuzz targets run in every suite.

### Decisions, taken overnight as recommended, not yet confirmed by the user

1. **(a)** Exceptions per method, through the Code attribute.
2. **(a)** R1-R10 as the table recommends, none of the three "not worth it".
3. **(a)** One `Settings` object in `core`: every `transformer.*` property and
   its `TRANSFORMER_*` twin, each with its default, in one table in README
   (docs/testing.md points at it).
4. **(a)** An unsupported cloud `jobRunOutput` is a run warning.

### Calls made while writing it

- **R1 moved into `bytecode_rules_test`** (the choice W2 left open) as two
  rules: R1, the eight default-locale calls in the modules a job runs, and
  R1 (desktop), the two case folds in the desktop modules. The desktop scope
  gained `gui:chart`, which the locale test's list of desktop class files
  missed; nothing in it breaks the rule.
- **Scopes.** The engine (core, sql, read, write, job, temporal) for R2, R3,
  R4, R7, R8 and R9; every module a job runs (the engine, cli, gui:session)
  for R1, R5 and R6; every module, the desktop's too, for R10.
- **One test holds every rule** in `BytecodeRules.All`, so a rule added there
  is held with no new test method. Beside it: each module is a jar of its own;
  every `com.transformer` class a scanned class names is in a scanned module,
  so a new module cannot go unread; every reference a pool holds is found in
  a method's code (the reader's soundness, also checked over the Scala library
  and `java.base`); an entry covers its method's lambdas and goes stale.
- **An entry names a method as written** and covers, by prefix, its lambdas
  (`$anonfun$m$1`), local defs, default arguments and specialized copies; `*`
  covers a whole class, used where every banned use of a class has the one
  reason: R5's `DataJob` (eight methods stamping records) and
  `TemporalVariables$` (two default arguments), R8's `Settings$`.
- **R8 bans every system property and environment read in the engine**, not
  only one of a `transformer.*` name, which a constant pool cannot always
  show (`System.getProperty(PropertyName)`), and any string constant shaped
  as a setting's name. The spill directory's default, which read
  `java.io.tmpdir`, moved into `Settings`.
- **One reading rule for every setting**, within decision 3: the property,
  else its twin; trimmed, a blank value unset; a flag on at `true` or `1` in
  any case. Before, `hash_zero`, `verify` and `audit_leaks` were
  `Boolean.getBoolean` (exactly `true`, in any case, untrimmed), so `=1` now
  turns them on; and a blank `batch_capacity` or scheduler property now falls
  through to its twin where it was taken as set and ignored. Each owner reads
  its setting when it did before.
- **No shims** (CLAUDE.md): `MetricsCollector.PropertyName`, `EnvVarName` and
  `parseBool`, `DataJob.ForceSpillPropertyName` and `ForceSpillEnvVarName`,
  `Spill.SpillDirProperty` and `Faults.PropertyName` are gone, their users
  reading `Settings`; `DataJob.forceSpillFromSysprop` is `DataJob.forceSpill`.
  The macro bench reads `Settings` and no longer depends on `job`.
- **R2 lands with eleven entries**, ten allowed and one held, at the plan's
  "about ten": `MinMaxState`'s three methods are one tie-break, and the two
  JSON `equals` one reason.
- The warning reads `the job-run record was not written: '<path>' is a cloud
  path, which v1 does not support`, the run's last.
- The sabotage checks ran in the work tree, which this run had to itself: a
  violating call added, the target run, the edit reverted.

### The rules, re-measured at 17755af

| Rule | Modules | Bans | Allowed (reason) | Held for the user |
|---|---|---|---|---|
| R1 | job modules | `toLowerCase()`, `toUpperCase()`, `String.format`, `formatted`, `f"..."`, `DateTimeFormatter.ofPattern(p)`, `NumberFormat.getInstance()` / `getNumberInstance()` | none | none |
| R1 (desktop) | desktop | `toLowerCase()`, `toUpperCase()` | none | none |
| R2 | engine | `Double` / `Float` `compare`, `compareTo`, `equals`; a raw `fcmp`/`dcmp` between two values | `Ops.floatCompare` (the order); `MinMaxState.updateAt`, `updateBatchAt`, `merge` and `Funcs.pickByOrdering` (the decided zero tie-break); `Funcs.apply` (POWER's whole-exponent test); `PhysicalPlanner.shouldBuildRight` (two estimates); `JsonValue.stringValue` (a whole JSON number); `JsonNumber.equals`, `JsonMini.JNum.equals` (synthesized) | `FunctionCatalog.Entries` |
| R3 | engine | `doubleToLongBits`, `doubleToRawLongBits`, `floatToIntBits`, `floatToRawIntBits` | `KeyCodec.floatKeyBits`, `doubleKeyBits` (a key's bits, -0.0 as 0.0); `HashPartitioner.decimalHash` (a decimal's double) | none |
| R4 | engine | `String.compareTo`, `compareToIgnoreCase`, `StringOps` `compare` and `<` family | `Ops.cmp` (the order) | none |
| R5 | job modules | `System.currentTimeMillis`, `java.time` `now()`, `Clock.system*` | `LogicalBuilder.build` (the statement's start); `TemporalVariables$` (the default execution time); `DataJob`, `DataJob$$anon$2.call` (record stamps); `Cli.parse` (`run`'s default execution time); `JobSession.<init>` (the session's) | none |
| R6 | job modules | `Random`, `ThreadLocalRandom`, `SplittableRandom`, `scala.util.Random`, `Math.random`, `UUID.randomUUID` | `Funcs.apply` (`RAND()`); `Funcs$$anon$1.initialValue` (its generator) | `Chaos.draw`, `Chaos$ThreadState.<init>` |
| R7 | engine | `System.out` / `err`, `println`, `printStackTrace`, `System.exit`, `Runtime.exit` / `halt`, `sys.exit`, `Thread.sleep`, `TimeUnit.sleep` | `Faults.fire` (plan 06's armed halt, as this plan foresaw) | `LeakAudit.exit` |
| R8 | engine | `System.getProperty`, `getProperties`, `getenv`; `Boolean.getBoolean`, `Integer.getInteger`, `Long.getLong`; `sys.props`, `sys.env`; a `transformer.*` or `TRANSFORMER_*` name | `Settings$` (every setting), `Settings$Setting.value` (the read) | none |
| R9 | engine | `LocalDate.toEpochDay`, `Instant.getEpochSecond`, `getNano`, `ChronoUnit.between` | `DateVector.epochDay`, `TimestampVector.epochMicros` | none |
| R10 | all | `String.getBytes()`, `new String(bytes)`, a reader, writer or `PrintStream` without a charset, `Charset.defaultCharset`, `TimeZone.getDefault`, `ZoneId.systemDefault`, `Clock.systemDefaultZone` | none | none |

Against the plan's table (f309205): the job modules hold 33 methods with a
float comparison against a constant and 9 with one between two values (the
plan counted 27 and 7); the two-value ones are the R2 entries above,
`FunctionCatalog`'s lambda and `gui:session`'s `NodeBox` (layout geometry,
outside R2's engine scope). R8 found thirteen properties and seven twins in
twelve classes: plans 06 and 17 added `transformer.fault`, `verify`,
`audit_leaks` and `chaos`. Outside every rule's scope, as the table expects:
the desktop's 29 locale formats (display text), its own orderings
(`DataGrid.ValueOrdering`, `ChartData.Builder.before`, `RunFolder.group`), its
date positions (`ChartData.Builder.position`, `ChartScale.firstBoundary`), its
clock reads (`ControlsPanel`, `ResultPersister`), and the CLI's two property
reads (`os.name`, `user.home`).

### Sabotage checks

Each a violating call in the work tree, the target run, the call removed;
each failure named the rule, the site with its source line, what to write
instead, and the advice:

- **R1**: `name.toLowerCase()` and `String.format("%d", ...)` in
  `core.Catalog`, `name.toUpperCase()` in `gui.VimKey`: `Rule R1, locale: case
  folds and formats in the modules a job runs: 2 uses the rule bans:
  com.transformer.core.Catalog.sabotageR1 (Catalog.scala:81):
  java/lang/String.toLowerCase()Ljava/lang/String; [write
  toLowerCase(Locale.ROOT)] ... Why: A module a job runs folds case and
  formats in Locale.ROOT ...`, and `Rule R1 (desktop) ... com.transformer.gui.VimKey$.sabotageR1 (VimKey.scala:15)`.
- **R2**: `java.lang.Double.compare(a, b)` and `a < b` on doubles in
  `SqlEngine`: `Rule R2, float ordering and equality: 2 uses ...
  java/lang/Double.compare [write Ops.floatCompare (a double slot) or Ops.cmp
  (boxed)] ... a raw <, <=, >, >=, == or != between two floats or doubles
  [write Ops.floatCompare(a, b)] Why: Anything that orders or matches two
  values compares through the one total order ...`.
- **R3**: `Double.doubleToLongBits(d)` in `Catalog`: `... [write
  KeyCodec.doubleKeyBits] Why: A float key's bits are ...`.
- **R4**: `a < b` on strings in `LogicalBuilder`:
  `scala/collection/StringOps$.$less$extension [write Ops.cmp(a, b)]`.
- **R5**: `System.currentTimeMillis()` in `SqlEngine`, `LocalDate.now()` in
  `TemplateRenderer`: `Rule R5, clock: 2 uses ... Why: No module a job runs
  reads the wall clock but where a decision says ...`.
- **R6**: `scala.util.Random.nextInt()` in `Catalog`,
  `UUID.randomUUID()` in `DataJob`: `Rule R6, randomness: 2 uses ...
  scala/util/Random$ (any member) ... java/util/UUID.randomUUID`.
- **R7**: `println`, `printStackTrace()`, `Thread.sleep(1)` and
  `System.exit(1)` in `SqlEngine`, `ParquetReader`, `Catalog` and `DataJob`:
  `Rule R7, library code does not print, exit or sleep: 4 uses ...`. The
  `DataJob` line decision 4 removed was R7's one site before it.
- **R8**: `System.getProperty("transformer.join.algorithm")` in `SqlEngine`,
  `sys.env.get("HOME")` in `DataJob`, a constant `TRANSFORMER_CSV_STRICT` in
  `CsvReader`: `Rule R8, settings: 4 uses ... a transformer.* setting's name
  [write a Settings entry] ... java/lang/System.getProperty ... a
  TRANSFORMER_* setting's name ... scala/sys/package$.env`.
- **R9**: `d.toEpochDay.toInt` in `CsvReader`: `java/time/LocalDate.toEpochDay
  [write DateVector.epochDay(d)]`.
- **R10**: `s.getBytes()` in `CsvWriter`, `ZoneId.systemDefault()` in
  `gui.GuiApp`: `Rule R10, default charset and zone: 2 uses ...
  [write getBytes(StandardCharsets.UTF_8)] ... [write an explicit zone,
  ZoneOffset.UTC]`.

A stale entry is held by `allowlistEntriesCoverTheirMethodsAndGoStale`: an
entry naming a method that does nothing the rule bans fails with `Allowlist
entries whose method no longer does what the rule bans (remove each)`.

### What the rules found that the plan did not list

None is a defect; each is in its rule's `held` list, with why, and in
gotchas.md ("What the bytecode rules hold out"), for the user to decide:

- **R2: `FunctionCatalog.Entries`**, POWER's whole-exponent test,
  `math.floor(y) != y`, in the catalog's description of when POWER is NULL:
  the test `Funcs.apply` makes, written a second time by plan 09.
- **R6: `Chaos.draw` and `Chaos$ThreadState`**, the chaos points'
  per-thread `SplittableRandom` (plan 17): it moves when a thread runs, never
  a value, and is off unless `transformer.chaos` is set.
- **R7: `LeakAudit.exit`**, the leak audit's report at exit (plan 17):
  printed to standard error, then a halt with status 117, under
  `transformer.audit_leaks` only.

Decision 4's site was a finding the plan listed, fixed by the decision: the
cloud `jobRunOutput` line printed to standard error and gave no warning,
which `data_job_test`'s new case failed on before the change.

## Launch prompt

```
Read plans/testing/18-one-rule-per-concept-in-bytecode.md and implement it.

Follow CLAUDE.md's required workflow for every commit: bazel test //... green
(and --config=zone, since the rules run under tr-TR), the jaffle-shop job
15/15 Succeeded, every stale doc updated in the same commit. One commit for
the reader and the migration, one per rule group, one per main-code change.

Before editing, run ListAgents and git status: if another session has
uncommitted work in this tree, develop in a git worktree OUTSIDE the repo (see
the memory notes on concurrent sessions and on agent worktrees breaking
bazel //...).

No bytecode library: the reader is hand-written, as the locale test's is.
Re-measure the table in this plan against the current HEAD before writing the
allowlists; the counts are from f309205.

Stop and ask before: (a) a rule the plan marks "not worth it", (b) any
allowlist entry the plan does not list, (c) changing behaviour beyond
decisions 3 and 4, (d) a rule whose allowlist grows past about ten entries.

Report at the end: each rule with its allowlist and reasons, each sabotage
check and the failure it printed, and any violation the rules found that the
plan did not list (plainly, with a pointer: a found violation may be a
defect).
```
