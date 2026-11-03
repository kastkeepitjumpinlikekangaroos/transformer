# Plan 19: mutation testing measures the net

> Status: **done** 2026-11-02 (3f78452 W1, a2455f1 W2 and W3, 1757963 the
> smoke campaign, 0dd493b, 15ddd6a, 06b60fd and a2744ff the survivors by
> file; see [Outcome](#outcome); the four decisions taken overnight as
> recommended, not yet confirmed by the user; 248 mutants, 53 survivors, 35 of
> them now killed by a test, 8 in the ledger, 9 held open as believed
> equivalent, 1 a latent defect held for the user) · from the 2026-11-01 test
> survey, category 9 of 9
> (part 2; part 1 is [07](07-compiler-warnings.md)) · Effort: 3-4 days, about
> a day of it machine time · Risk: low (a tool and a report; the tests that
> survivors ask for are ordinary tests) · Depends on: runs last in the series,
> after [06](06-a-failed-run-leaves-no-partial-output.md) to
> [18](18-one-rule-per-concept-in-bytecode.md) have grown the suites. It runs
> faster after [07](07-compiler-warnings.md)'s W4 has pruned unused BUILD
> dependencies

## Why

Every number this repo reports about its tests counts what the tests ran: the
seeds, the 5.3 million SQLite records, the zero divergences. None says what
the tests would catch. A mutant is a small deliberate defect: `<` for `<=`,
`&&` for `||`, a dropped `!`, a deleted cleanup call. A suite that passes with
a mutant in place has a blind spot exactly there, and a list of surviving
mutants is a list of the tests to write next.

The repo already checks its tests this way, one at a time, by hand.
plans/robustness/09:144 and 11:163 keep "Regressions, each failing at the base
commit (checked in a worktree there with the new tests copied in)".
plans/testing/05-plans-as-sql.md:206-213 records that `hash_zero_test`'s first
draft "used no packed key, so a `BytesKey` that trusted equal hashes passed it,
and it was widened until that sabotage failed it". The memory note on
generator gates says to prove a widened generator's detection against a HEAD
worktree.

This plan makes that check systematic over the code where a wrong answer
costs the most, and keeps the tool so it can run again after each round.

## What exists now

- **No mutation tool, no coverage measurement.** There is no mutation tool,
  no JaCoCo configuration, and no script for either.
- **The default suite.** The test BUILD files hold 79 test rules, 14 of
  them tagged `fuzz` or `perf` and left out by
  `--test_tag_filters=-perf,-fuzz`. The rest ran as 70 targets
  (`slt_test` in four shards). Their JUnit time, summed from the last runs'
  `test.log` files on 2026-11-01, is 543 s: `fuzz` 300 s, `slt` 142 s, `sql`
  72 s, `read` 8 s, `job` 7 s, `core` 5 s, `gui` 4 s. The longest targets are
  `parquet_fuzz_test` (58 s), `frontend_fuzz_test` (53 s),
  `mode_differential_fuzz_test` (48 s), `slt_test`'s shards (31-39 s each)
  and `sql_engine_test` (11 s).
  JUnit prints its total in the JVM's locale, so `locale_independence_test`'s
  log under tr-TR says `Time: 0,688`, which a parser reading `,` as a
  thousands separator takes for 688 s.
- **Rebuild cost.** rules_scala builds an interface jar for each Scala 2
  library (`bazel-bin/.../sql/plan/plan-ijar.jar`). A mutant that changes only
  a method body recompiles its own library, the dependents' compile actions
  hit the cache, and the test targets that depend on it run again.
  - `sql/plan` is one library, all 18 of its files (8,246 lines).
  - It is a direct dependency of `read/parquet`, `sql/exec`, `cli` and
    `benchmarks/micro`. Seven test packages name it in their BUILD files,
    whose 66 test rules include the fuzz campaigns.
- **The machine.** 8 cores and 16 GB. 22 GiB of disk was free on
  2026-11-01, and the repo's output base takes 2.7 GB.

## What to build

**W1. The mutator.** `//tools/mutation:mutate`, a `scala_binary` beside
`//tools/parquet_peek`, package `com.transformer.tools.mutation`.

It reads a Scala source with a small lexer that knows string literals
(triple-quoted and interpolated ones included), character literals, line and
block comments, and backquoted names. It never mutates inside a string, a
character literal, a comment or a type, and lists every site of each
operator:

| operator | mutation | what it asks of the tests |
|---|---|---|
| relational | `<` and `<=`, `>` and `>=` swapped, `==` and `!=` swapped (not `<-`, `=>`, `<:`, `>:`, `<<`, `>>`) | a boundary row |
| logical | `&&` and `||` swapped; a `!` dropped | each half of a condition |
| arithmetic | `+` and `-`, `*` and `/` swapped; `+ 1` to `+ 0`, `- 1` to `- 0` | an off-by-one |
| constant | `0` and `1` swapped; `true` and `false` swapped | a flag or a starting value |
| extremum | `math.max` and `math.min` swapped | a clamp |
| checked arithmetic | `Math.addExact(a, b)` to `(a + b)`, and the other `*Exact` | an overflow |
| statement | a statement that is a call on its own line deleted (`spillDir.foreach(_.close())`, `w.abort()`) | cleanup and resource release |
| refusal | `throw new IllegalArgumentException(...)` to `()` where it types | a refusal's own test |

A mutant that does not compile is stillborn: it is counted and dropped.
`+` beside a string is skipped, since it would only make stillborn mutants.

The mutator's own test, `//tools/mutation:mutator_test`, runs on fixture
sources. Each operator must find its sites and no other, nothing inside a
literal or a comment may change, and each mutant of the fixture must differ
from it in one token.

**W2. Which tests can kill a mutant.** For a source file, the Bazel package
that compiles it is found, then the test targets that depend on it:

`bazel query 'kind(test, rdeps(//..., <package>))'`, without the `perf` and
`fuzz` tags.

The targets are run in three stages:

1. the tests of the file's own package (seconds);
2. `sql_engine_test`, `slt_test` and the `job` tests;
3. the rest, the default-budget fuzz tests among them.

A mutant is killed at the first stage in which a test fails, and the later
stages are not run. Its outcome is killed (by which target and test),
survived, timed out (past three times the stage's baseline: a mutated loop
condition can loop forever), or stillborn.

For the exchange and scheduler files there is a fourth stage under
`--config=sharded`, since their defects show only when rows move between
shards.

**W3. The runner.** It runs in a git worktree outside the repo (the
scratchpad), at a clean commit, with its own Bazel output base (Bazel keys an
output base by the workspace's path). It builds once to warm the cache.

For each mutant it writes the mutated file, runs the stages, and restores the
file, which puts every action back on the cache. A killing test is re-run once
on the unmutated file, so a flaky test is not counted as a kill.

Outcomes go to a TSV file: file, line, operator, the original and mutated
token, outcome, the killing target and test, and seconds.

The deploy jar is built once in the repo, copied to the scratchpad and run
with the output base's JDK 21 (system `java` is 17), as the probe jars of the
audits were.

**W4. The campaign.** Take a stratified sample per file (decision 2) over
the files where a wrong answer is silent, at today's sizes:

| file (`src/main/scala/com/transformer/`) | lines | holds |
|---|---|---|
| `sql/plan/Ops.scala` | 1,255 | `Ops.cmp`/`eq`, `Casts`, `Integers`, `VecOps` |
| `sql/plan/Funcs.scala` | 644 | every scalar function, `Rounding` |
| `sql/plan/Analyzer.scala` | 377 | type unification, branch typing |
| `sql/plan/JoinOrder.scala` | 216 | join order |
| `sql/plan/FilterPushdown.scala` | 126 | pushdown |
| `sql/plan/ColumnProjectionPushdown.scala` | 337 | pruning |
| `sql/exec/WindowExec.scala` | 748 | frames, peers |
| `sql/exec/AggregateExec.scala` | 1,993 | aggregate states, spill |
| `sql/exec/SortExec.scala` | 499 | sort, k-way merge |
| `sql/exec/JoinExec.scala` | 1,152 | hash join, pairing waves |
| `core/HashKeys.scala` | 990 | key codecs, hash maps |
| `read/parquet/ParquetFilterTranslator.scala` | 327 | row-group pruning |
| `read/csv/CsvRowParser.scala` | 218 | the CSV state machine |
| `read/csv/CsvValues.scala` | 184 | text of each type |
| `job/TaskRunRecord.scala` | 436 | output records, stray parts |
| `job/DataJob.scala` | 1,068 | the commit protocol (after plan 06) |

**Cost, estimated from the measured numbers above.** Recompiling `sql/plan`
takes about 10 s. Stage 1 is about 10 s of JUnit time. Stage 2 is about 220 s,
some 40 s of wall time on 8 cores with `slt_test`'s shards side by side.
Stage 3 is about 300 s, some 60-70 s of wall time.

So a mutant killed in stage 1 costs about 25 s, and one that survives about
2.5 minutes. Most mutants die early. If half die in stage 1 and a fifth
survive, a mutant averages about a minute: 200 mutants in 3-4 hours on one
worktree. A second worktree in parallel halves the wall time, at about 3 GB of
disk and a second Bazel server's memory.

**W5. Survivors.** Each survivor gets one of two outcomes before the plan is
done:

- **A test** that kills it, in the target nearest the code: a unit test, or
  an slt record for SQL semantics. The test must pass at HEAD and fail with
  the mutant applied, which is the base-commit practice turned around.
- **An equivalent-mutant note**, for a mutant that changes no behaviour, for
  example `<` to `<=` where equality cannot occur. The note goes in
  `tools/mutation/equivalent.tsv`: the file, the line's text (not its number,
  so the entry survives edits), the operator and the reason. Later campaigns
  skip it.

The report goes in this plan's Outcome, and in `docs/testing.md` under
"Mutation testing": killed, survived, timed out and stillborn per file and
per operator, and every survivor, with its diff and what became of it.

## Decisions for the user

1. **The mutation engine.** *Taken overnight as recommended, not yet
   confirmed by the user: (a).*
   - **(a) Hand-rolled, on source, through Bazel (recommended).** Every test
     runs as Bazel runs it, with its runfiles (the slt scripts, the vendored
     corpora), its `jvm_flags` (tr-TR, sharding) and the whole-suite
     configurations. It needs no tool from outside the repo. It is the slower
     engine, but stays within the budget above.
   - **(b) PIT (pitest), run offline from the scratchpad on Bazel's jars.**
     It mutates bytecode in memory and runs only the tests that cover a line:
     thousands of mutants an hour. But its tests run on one classpath without
     Bazel's runfiles, `jvm_flags` or `test_env`, so the slt and locale
     targets need adapting. And Scala's generated code (pattern matching,
     case-class methods, specialization, lazy vals) makes mutants with no
     source line to fix.
   - **(c) Both:** PIT for a wide first pass, the hand-rolled runner to
     confirm each survivor through Bazel.
2. **Scope and budget.** *Taken overnight as recommended, not yet confirmed
   by the user: (a), the smoke campaign's every site of `CsvRowParser.scala`
   counted inside the 250 (see [Outcome](#outcome)).*
   - **(a) About 15 mutants per file over the 16 files above, stratified by
     operator: about 250 mutants, 4-5 hours (recommended).**
   - **(b) Every site of every operator in those files:** thousands of
     mutants, days of machine time.
   - **(c) Five files only** (`Ops`, `Funcs`, `WindowExec`, `AggregateExec`,
     `JoinOrder`).
3. **After the campaign.** *Taken overnight as recommended, not yet
   confirmed by the user: (a).*
   - **(a) The tool and the equivalent-mutant ledger are kept, and the
     campaign runs again at the end of each testing round. There is no kill
     floor in CI, since it is too slow and flakes on equivalent mutants
     (recommended).**
   - **(b) A tagged, opt-in target, like the fuzz campaigns, that fails when a
     file's kill rate drops below its last recorded one.**
   - **(c) A one-off report; the tool is deleted.**
4. **What a survivor costs.** *Taken overnight as recommended, not yet
   confirmed by the user: (a).*
   - **(a) Every survivor gets a test or an equivalent-mutant note
     (recommended).**
   - **(b) Only survivors in the SQL-semantics files (`sql/plan`, `sql/exec`);
     the rest are listed.**

## Decisions already made

- No new dependency in the harness (plans/testing/README.md:11 and :94), so
  the default engine is hand-rolled, and PIT is a decision, not a default.
- Worktrees live outside the repo. One under `.claude/worktrees` breaks
  `bazel test //...` in the main tree unless a `.bazelignore` names it
  (memory note on agent worktrees).
- Before a long machine run: check `df`, and keep at most about three heavy
  JVMs at once. The disk filled on 2026-10-16 and ENOSPC looked like a test
  failure (memory note on fuzz campaigns in parallel).
- A regression test fails at the base commit (plans/robustness/09:144,
  11:163); a survivor's test fails with its mutant.
- The default suite, not the campaigns, decides a kill (`.bazelrc`'s
  `--test_tag_filters=-perf,-fuzz`).
- Comments describe current behaviour (the readability memory note): the
  tool's comments say what it does, not which plan made it.
- Scala 2.13.16 and JDK 21 stay pinned (CLAUDE.md).

## Tests

- **`mutator_test`.** Lexer cases: interpolated strings with nested braces,
  triple quotes, character literals such as `'<'`, `//` inside a string,
  nested block comments, backquoted names. One fixture per operator, with its
  expected sites.
- **A smoke campaign.** `read/csv/CsvRowParser.scala` (218 lines, one
  package, fast tests), every site, end to end. It proves staging, timeouts,
  the TSV and restoring the file, and its survivors are the first W5 items.
- **The flaky-kill check.** A target that fails at random (a fixture under
  the tool's tests) is not counted as a kill.

## Docs

- `docs/testing.md`: a "Mutation testing" section. It covers how to build and
  run the tool, the stages, the TSV and the ledger, the latest campaign's
  table, and the localized `Time:` line when parsing JUnit logs.
- `docs/extending.md`: "Add a mutation operator", and "Run mutation testing
  on a new module".
- `docs/conventions.md`: the survivor rule (decision 4).
- `docs/gotchas.md`: interface jars make a body-only mutant cheap; JUnit
  prints its time in the JVM's locale; a mutated loop condition can hang a
  test, so every stage has a timeout.
- `README.md`, Development: a line pointing at the section. `docs/code-map.md`:
  the tool's files, if they pass a hot spot's size. No change to `site/` or
  `docs/architecture.md` (no engine change).

## Risks

- **Disk and memory.** Each worktree's output base is about 3 GB and each
  Bazel server and its test JVMs take several GB of RAM, with 22 GiB of disk
  free on 2026-11-01. Run one worktree unless `df` shows room for two.
- **Flaky tests read as kills.** Re-running the killing test on the original
  file guards it. The timing-sensitive tests (the DAG scheduler guard under
  one thread, plans/testing/05) are known to need it.
- **Equivalent mutants.** They inflate the survivor count, and triage is
  manual. The ledger keeps that a one-time cost per mutant.
- **Hangs and memory blow-ups from mutated loops.** Per-stage timeouts, and
  the test JVM's own heap limit.
- **A shared tree.** The campaign must run at a clean commit in its own
  worktree. A peer's uncommitted edits in the main tree must not leak into a
  mutant's baseline.

## Outcome

Done 2026-11-02, overnight on `testing-round-3`, in the commits the launch
prompt asks for: the mutator and its test; the runner; the smoke campaign's
tests; then the survivors' tests in groups by file.

| Commit | What landed |
|---|---|
| 3f78452 | W1: `//tools/mutation`'s lexer and mutator (eight operators), the stratified sample, the ledger `equivalent.tsv`, `mutate sites`, `sample` and `apply`; `mutator_test` over a fixture per operator |
| a2455f1 | W2 and W3: the runner, `mutate run` and `report`: stages from `bazel query`, an uncached baseline that sets each stage's timeout, each killing target run again on the unmutated file, outcomes from the build event protocol and `test.xml`; `runner_test`; the flaky fixture |
| 1757963 | The smoke campaign's tests (`CsvRowParserTest`, two ledger lines); the runner names every target that kills a mutant, a stopped campaign puts its file back and records nothing for it, a cut build event line is skipped, `sample` names a stale ledger entry |
| 0dd493b | The survivors of `sql/plan`'s six files: `analyzer_test` (new), `JoinOrderTest`, `ColumnProjectionPushdownTest`; one ledger line |
| 15ddd6a | The survivors of `sql/exec`'s four files: `WindowExecSpillTest`, `HashAggregateSpillTest`, `SortExecSpillTest`, `ClosingTest`, `OperatorCountersTest`; one ledger line |
| 06b60fd | The survivors of `HashKeys`, `ParquetFilterTranslator` and `CsvValues`: `HashKeysTest`, `ParquetFilterTranslatorTest`; one ledger line |
| a2744ff | The survivors of `TaskRunRecord` and `DataJob`: `DataJobTest`; three ledger lines |

Every commit: `bazel test //...` green (86 targets at the base, 88 with the
tool's two tests, 89 with `analyzer_test`), and the jaffle-shop job 15/15
Succeeded with every validation passing. No fuzz campaign ran: the plan adds
no fuzz property.

### Decisions, taken overnight as recommended, not yet confirmed by the user

1. **(a)** A hand-rolled mutator on source, each mutant run through Bazel: no
   PIT, no tool from outside the repo.
2. **(a)** About 250 mutants, stratified by operator. The smoke campaign's
   every site of `CsvRowParser.scala` (88 mutants) is inside the 250, so each
   of the other fifteen files got 11 rather than 15 (`FilterPushdown.scala` 7
   and `ParquetFilterTranslator.scala` 10, every site they have): 248.
3. **(a)** The tool and the ledger are kept; the campaign runs again at the
   end of each testing round; no kill floor in CI.
4. **(a)** Every survivor gets a test or a ledger line, but where the launch
   prompt's quarter rule holds a believed-equivalent one open (below).

### Calls made while doing it

- **The operators.** A `refusal` is any `throw` that is the body of an `if`
  with no `else`, where `()` types: an `IllegalArgumentException`, an
  `ArithmeticException` or a helper's (`throw overflow(...)`). The off-by-one
  takes `+= 1` and `-= 1` too. A `statement` is never a block's value, which
  would not compile, but where the block's value goes nowhere (a `while`,
  `for`, `foreach`, `finally`, an `if` with no `else`, a method of `: Unit`).
  An `else` block's last statement is left out by that rule.
- **No sharded stage.** None of the sixteen files is an exchange or a
  scheduler; `--sharded` is there for the next campaign that takes one.
- **The runner keeps a disk cache**, pruned after each mutant to what the
  unmutated tree built, so putting a file back costs no compile.
- **Stop and resume.** The main campaign was stopped twice (SIGTERM; the hook
  puts the file back and records nothing for the mutant in progress) to check
  survivors' tests in its worktree, and resumed.
- **The flaky-kill check** through Bazel starts from a hand-written
  `baseline.tsv`, since a test that fails at random has none green. Five runs
  over the fixture's ten mutants: 31 passes; of 19 failures, 12 failed again
  on the unmutated file and killed nothing, 7 passed and counted. One rerun
  refuses a flaky failure with the test's own chance of failing, as the plan
  says; no target failed on an unmutated file in the campaign.
- **Tests of code no query reaches.** Four survivors were changes no query
  can make the engine take: `Analyzer.implicitCast`'s BINARY check and
  `branches`' reading of a written number (the binder refuses or converts
  first), a right-nested join run (a parenthesized join is refused) and a
  frame ending at UNBOUNDED PRECEDING (refused). Each got a test of the
  helper's own contract, built in code, rather than a ledger line.
- **The quarter rule** kept nine believed-equivalent survivors out of the
  ledger: a file with one or two survivors may put none there. They are
  listed open in gotchas.md, for the user to move.

### What the campaign found

248 mutants, on 8 cores: the smoke campaign's 88 at 7bd5dd0 in 1 h 23 min
(1.3 h of mutants after a 4 min baseline), the other 160 at 1757963 in
4 h 17 min (4.1 h of mutants). A killed mutant cost 11 s in the CSV parser and
40 s elsewhere; a survivor about 4.3 minutes. 176 mutants died at stage 1, 11
at stage 2 (every one to `slt_test`), 6 at stage 3 (each to a fuzz test,
`csv_fuzz_test` or `dag_scheduler_fuzz_test`). The CSV corpus test
(`csv_corpus_test`) caught 69 of the CSV parser's 74 caught mutants; in the
other files `sql_engine_test` caught 25, `semantics_tables_test` 20,
`join_order_test` 17 and `plan_sql_test` 16.

| file | mutants | killed | timed out | survived | stillborn | killed or timed out, of those that compiled |
|---|---:|---:|---:|---:|---:|---:|
| `core/HashKeys.scala` | 11 | 6 | 1 | 4 | 0 | 64% |
| `job/DataJob.scala` | 11 | 2 | 0 | 9 | 0 | 18% |
| `job/TaskRunRecord.scala` | 11 | 7 | 0 | 4 | 0 | 64% |
| `read/csv/CsvRowParser.scala` | 88 | 71 | 3 | 14 | 0 | 84% |
| `read/csv/CsvValues.scala` | 11 | 9 | 0 | 2 | 0 | 82% |
| `read/parquet/ParquetFilterTranslator.scala` | 10 | 9 | 0 | 1 | 0 | 90% |
| `sql/exec/AggregateExec.scala` | 11 | 8 | 0 | 2 | 1 | 80% |
| `sql/exec/JoinExec.scala` | 11 | 7 | 0 | 4 | 0 | 64% |
| `sql/exec/SortExec.scala` | 11 | 6 | 1 | 3 | 1 | 70% |
| `sql/exec/WindowExec.scala` | 11 | 8 | 1 | 2 | 0 | 82% |
| `sql/plan/Analyzer.scala` | 11 | 9 | 0 | 2 | 0 | 82% |
| `sql/plan/ColumnProjectionPushdown.scala` | 11 | 7 | 0 | 4 | 0 | 64% |
| `sql/plan/FilterPushdown.scala` | 7 | 7 | 0 | 0 | 0 | 100% |
| `sql/plan/Funcs.scala` | 11 | 9 | 1 | 1 | 0 | 91% |
| `sql/plan/JoinOrder.scala` | 11 | 10 | 0 | 1 | 0 | 91% |
| `sql/plan/Ops.scala` | 11 | 9 | 2 | 0 | 0 | 100% |
| **all** | 248 | 184 | 9 | 53 | 2 | 78% |

| operator | mutants | killed | timed out | survived | stillborn | killed or timed out, of those that compiled |
|---|---:|---:|---:|---:|---:|---:|
| `arithmetic` | 27 | 16 | 5 | 6 | 0 | 78% |
| `checked` | 1 | 1 | 0 | 0 | 0 | 100% |
| `constant` | 61 | 43 | 1 | 17 | 0 | 72% |
| `extremum` | 11 | 10 | 0 | 1 | 0 | 91% |
| `logical` | 39 | 31 | 1 | 7 | 0 | 82% |
| `refusal` | 11 | 8 | 0 | 3 | 0 | 73% |
| `relational` | 64 | 47 | 2 | 15 | 0 | 77% |
| `statement` | 34 | 28 | 0 | 4 | 2 | 88% |
| **all** | 248 | 184 | 9 | 53 | 2 | 78% |

Of the 53 survivors, 35 now have a test, each checked to fail with its mutant
applied and pass without, in the campaign's worktree; 8 are in the ledger; 9
stay open, believed equivalent; one shows a latent defect:

| mutant | where | operator | the line | mutated | what became of it |
|---|---|---|---|---|---|
| m002 | `read/csv/CsvRowParser.scala:37` | constant | `private var rowLine: Long = 1L` | `private var rowLine: Long = 0L` | test: `CsvRowParserTest.beforeItsFirstRowAParserIsOnLineOne` |
| m003 | `read/csv/CsvRowParser.scala:38` | constant | `private var rowBlank: Boolean = false` | `private var rowBlank: Boolean = true` | test: `CsvRowParserTest.beforeItsFirstRowAParserIsOnLineOne` |
| m013 | `read/csv/CsvRowParser.scala:70` | constant | `var quoteOpenedOn = 0L` | `var quoteOpenedOn = 1L` | ledger |
| m051 | `read/csv/CsvRowParser.scala:131` | relational | `if (n >= 0) { cur.append(n.toChar); countLine(n) }` | `if (n > 0) { cur.append(n.toChar); countLine(n) }` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m052 | `read/csv/CsvRowParser.scala:131` | constant | `if (n >= 0) { cur.append(n.toChar); countLine(n) }` | `if (n >= 1) { cur.append(n.toChar); countLine(n) }` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m054 | `read/csv/CsvRowParser.scala:135` | relational | `if (n >= 0 && n.toChar == quote) {` | `if (n > 0 && n.toChar == quote) {` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m055 | `read/csv/CsvRowParser.scala:135` | constant | `if (n >= 0 && n.toChar == quote) {` | `if (n >= 1 && n.toChar == quote) {` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m058 | `read/csv/CsvRowParser.scala:138` | relational | `if (n >= 0) reader.unread(n)` | `if (n > 0) reader.unread(n)` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m059 | `read/csv/CsvRowParser.scala:138` | constant | `if (n >= 0) reader.unread(n)` | `if (n >= 1) reader.unread(n)` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m071 | `read/csv/CsvRowParser.scala:173` | constant | `private var quotedField: Boolean = false` | `private var quotedField: Boolean = true` | ledger |
| m078 | `read/csv/CsvRowParser.scala:185` | relational | `if (n >= 0 && n.toChar != '\n') reader.unread(n)` | `if (n > 0 && n.toChar != '\n') reader.unread(n)` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m079 | `read/csv/CsvRowParser.scala:185` | constant | `if (n >= 0 && n.toChar != '\n') reader.unread(n)` | `if (n >= 1 && n.toChar != '\n') reader.unread(n)` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m085 | `read/csv/CsvRowParser.scala:195` | relational | `if (n >= 0) reader.unread(n)` | `if (n > 0) reader.unread(n)` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m086 | `read/csv/CsvRowParser.scala:195` | constant | `if (n >= 0) reader.unread(n)` | `if (n >= 1) reader.unread(n)` | test: `CsvRowParserTest.aNulIsACharacterWhereverItIs` |
| m104 | `sql/plan/Funcs.scala:217` | relational | `if (start >= characters \|\| length <= 0) ""` | `if (start >= characters \|\| length < 0) ""` | open, believed equivalent |
| m112 | `sql/plan/Analyzer.scala:71` | statement | `checkBinaryCast(e.dataType, to)` | `(deleted)` | test: `AnalyzerTest.anImplicitCastOfABinaryToAnotherTypeIsRefused` |
| m117 | `sql/plan/Analyzer.scala:185` | logical | `case LitExpr(n: Number, t) if !isDecimal(t) => Some(Decimals.literal(n))` | `case LitExpr(n: Number, t) if isDecimal(t) => Some(Decimals.literal(n))` | test: `AnalyzerTest.aNumberBesideADecimalIsTheDecimalItIsWrittenAs` |
| m123 | `sql/plan/JoinOrder.scala:133` | arithmetic | `flatten(r, offset + l.outputSchema.length)` | `flatten(r, offset - l.outputSchema.length)` | test: `JoinOrderTest.aJoinNestedOnTheRightKeepsItsAnswer` |
| m141 | `sql/plan/ColumnProjectionPushdown.scala:169` | relational | `while (i < n) { b += (i -> i); i += 1 }` | `while (i <= n) { b += (i -> i); i += 1 }` | ledger |
| m142 | `sql/plan/ColumnProjectionPushdown.scala:252` | constant | `case DataType.BooleanType => 1` | `case DataType.BooleanType => 0` | test: `ColumnProjectionPushdownTest.aScanThatFeedsNoColumnKeepsTheNarrowestFirst` |
| m143 | `sql/plan/ColumnProjectionPushdown.scala:253` | constant | `case DataType.NullType => 1` | `case DataType.NullType => 0` | test: `ColumnProjectionPushdownTest.aScanThatFeedsNoColumnKeepsTheNarrowestFirst` |
| m145 | `sql/plan/ColumnProjectionPushdown.scala:277` | statement | `branches.foreach { case (a, c) => go(a); go(c) }` | `(deleted)` | test: `ColumnProjectionPushdownTest.aJoinConditionKeepsTheColumnsOfItsCase` |
| m155 | `sql/exec/WindowExec.scala:617` | constant | `case FrameBound.UnboundedPreceding => if (isStart) 0 else -1` | `case FrameBound.UnboundedPreceding => if (isStart) 1 else -1` | open, believed equivalent |
| m156 | `sql/exec/WindowExec.scala:617` | constant | `case FrameBound.UnboundedPreceding => if (isStart) 0 else -1` | `case FrameBound.UnboundedPreceding => if (isStart) 0 else -0` | test: `WindowExecSpillTest.aFrameEndingAtUnboundedPrecedingIsEmpty` |
| m163 | `sql/exec/AggregateExec.scala:297` | relational | `if (stats != null) {` | `if (stats == null) {` | test: `HashAggregateSpillTest.spillAndMetricsComposeReportingNonZeroSpillCounters` |
| m164 | `sql/exec/AggregateExec.scala:388` | constant | `var peakBytes: Long = 0L` | `var peakBytes: Long = 1L` | test: `OperatorCountersTest.hashAggregateExecOverNoRowsBuffersNoBytes` |
| m175 | `sql/exec/SortExec.scala:165` | arithmetic | `val take = math.min(capacity, totalRows - produced)` | `val take = math.min(capacity, totalRows + produced)` | test: `SortExecSpillTest.aRunLongerThanABatchReadsBackWhole` |
| m176 | `sql/exec/SortExec.scala:277` | logical | `if (done \|\| heap.isEmpty)` | `if (done && heap.isEmpty)` | test: `SortExecSpillTest.theMergeHasNothingAfterItsEndOrItsClose` |
| m177 | `sql/exec/SortExec.scala:278` | refusal | `throw new NoSuchElementException("SortExec merge exhausted")` | `()` | test: `SortExecSpillTest.theMergeHasNothingAfterItsEndOrItsClose` |
| m184 | `sql/exec/JoinExec.scala:67` | constant | `buildRight: Boolean = true,` | `buildRight: Boolean = false,` | test: `OperatorCountersTest.hashJoinExecBuildsItsRightSideByDefault` |
| m185 | `sql/exec/JoinExec.scala:220` | relational | `val tBuild = if (metricsNode != null) System.nanoTime() else 0L` | `val tBuild = if (metricsNode == null) System.nanoTime() else 0L` | test: `OperatorCountersTest.hashJoinExecPopulatesInMemoryCounters` |
| m190 | `sql/exec/JoinExec.scala:795` | relational | `val sideName = if (side == HashJoinExec.SideBuild) "build" else "probe"` | `val sideName = if (side != HashJoinExec.SideBuild) "build" else "probe"` | ledger |
| m191 | `sql/exec/JoinExec.scala:833` | statement | `writers.foreach(w => if (w != null) w.abort())` | `(deleted)` | test: `ClosingTest.aSpilledHashJoinWhoseInputFailsDiscardsItsBuckets` |
| m196 | `core/HashKeys.scala:279` | refusal | `throw new IllegalStateException(` | `() ...` | test: `HashKeysTest.bytesKeyScratchRefusesAnotherWidth` |
| m197 | `core/HashKeys.scala:310` | logical | `if (k.hash != hash \|\| k.values.length != values.length) return false` | `if (k.hash != hash && k.values.length != values.length) return false` | test: `HashKeysTest.objectArrayKeysOfOtherWidthsDifferUnderOneHash` |
| m202 | `core/HashKeys.scala:616` | extremum | `private var threshold: Int = math.max(1, (capacity * loadFactor).toInt)` | `private var threshold: Int = math.min(1, (capacity * loadFactor).toInt)` | open, believed equivalent |
| m203 | `core/HashKeys.scala:659` | relational | `if (_size > threshold) resize()` | `if (_size >= threshold) resize()` | ledger |
| m215 | `read/parquet/ParquetFilterTranslator.scala:318` | relational | `private def ordersAlike(literal: String): Boolean = literal.forall(_ < Character.MIN_SURROGATE)` | `private def ordersAlike(literal: String): Boolean = literal.forall(_ <= Character.MIN_SURROGATE)` | test: `ParquetFilterTranslatorTest.aStringRangeStopsPushingAtTheFirstSurrogate` |
| m221 | `read/csv/CsvValues.scala:151` | arithmetic | `val digits = s.length - (if (s.charAt(0) == '+' \|\| s.charAt(0) == '-') 1 else 0)` | `val digits = s.length + (if (s.charAt(0) == '+' \|\| s.charAt(0) == '-') 1 else 0)` | open, believed equivalent |
| m222 | `read/csv/CsvValues.scala:151` | logical | `val digits = s.length - (if (s.charAt(0) == '+' \|\| s.charAt(0) == '-') 1 else 0)` | `val digits = s.length - (if (s.charAt(0) == '+' && s.charAt(0) == '-') 1 else 0)` | open, believed equivalent |
| m227 | `job/TaskRunRecord.scala:68` | logical | `def expectsDataFiles: Boolean = TaskRunStatus.wroteOutput(status) && outputFiles.nonEmpty` | `def expectsDataFiles: Boolean = TaskRunStatus.wroteOutput(status) \|\| outputFiles.nonEmpty` | test: `DataJobTest.aRecordExpectsDataFilesOnlyWhenItsStatusWroteThem` |
| m228 | `job/TaskRunRecord.scala:124` | constant | `val SchemaVersion: Int = 1` | `val SchemaVersion: Int = 0` | test: `DataJobTest.aRunRecordSaysItsFormatIsVersionOne` |
| m235 | `job/TaskRunRecord.scala:402` | relational | `if (lastSep < 0) (".", pattern)` | `if (lastSep <= 0) (".", pattern)` | **shows a latent defect, held for the user** |
| m236 | `job/TaskRunRecord.scala:403` | arithmetic | `else (before.substring(0, lastSep), pattern.substring(lastSep + 1))` | `else (before.substring(0, lastSep), pattern.substring(lastSep - 1))` | ledger |
| m238 | `job/DataJob.scala:327` | statement | `Chaos.at(Chaos.JobEvent)` | `(deleted)` | open, believed equivalent |
| m239 | `job/DataJob.scala:334` | arithmetic | `remainingTasks -= 1` | `remainingTasks -= 0` | ledger |
| m240 | `job/DataJob.scala:436` | refusal | `if (ofp.isCloud) throw new UnsupportedOperationException(` | `if (ofp.isCloud) () ...` | test: `DataJobTest.aCloudOutputPathFailsItsTask` |
| m241 | `job/DataJob.scala:562` | relational | `val allocDelta = if (alloc0 < 0L \|\| alloc1 < 0L) -1L else alloc1 - alloc0` | `val allocDelta = if (alloc0 <= 0L \|\| alloc1 < 0L) -1L else alloc1 - alloc0` | open, believed equivalent |
| m242 | `job/DataJob.scala:562` | logical | `val allocDelta = if (alloc0 < 0L \|\| alloc1 < 0L) -1L else alloc1 - alloc0` | `val allocDelta = if (alloc0 < 0L && alloc1 < 0L) -1L else alloc1 - alloc0` | ledger |
| m243 | `job/DataJob.scala:562` | relational | `val allocDelta = if (alloc0 < 0L \|\| alloc1 < 0L) -1L else alloc1 - alloc0` | `val allocDelta = if (alloc0 < 0L \|\| alloc1 <= 0L) -1L else alloc1 - alloc0` | open, believed equivalent |
| m244 | `job/DataJob.scala:562` | constant | `val allocDelta = if (alloc0 < 0L \|\| alloc1 < 0L) -1L else alloc1 - alloc0` | `val allocDelta = if (alloc0 < 0L \|\| alloc1 < 1L) -1L else alloc1 - alloc0` | open, believed equivalent |
| m247 | `job/DataJob.scala:820` | arithmetic | `val from = g * perGroup` | `val from = g / perGroup` | test: `DataJobTest.maxPartitionsCoalescesPartsBelowSourcePartitions` |
| m248 | `job/DataJob.scala:1005` | logical | `if (path.startsWith("gs://") \|\| path.startsWith("s3://")) path` | `if (path.startsWith("gs://") && path.startsWith("s3://")) path` | test: `DataJobTest.jobRunRecordKeepsACloudInputsPathAsWritten` |

**The defect, held for the user** (`m235`, gotchas.md "What the mutation
campaign held out"). `TaskRunRecord.discover`, the GUI's partition picker,
takes its walk's root from `splitAtFirstWildcard`, which for a pattern whose
first templated segment sits right under `/` gives the empty string, read as
the working directory: `discover("/day={{ today }}")` walks the working
directory, and `/day=20260101/_run.json` is never found. The mutant
`lastSep <= 0` gives `"."`, the same directory, so it survived. Unreachable in
a test without writing under `/`; no test that would fail on it landed, and
the engine is unchanged.

**What else the survivors showed.** `TaskRunRecord.expectsDataFiles` has no
caller. The CSV parser's checks that a character read is not the end of the
input (`n >= 0`) had no test with a NUL in five of the places they guard, and
none with a NUL quote, which `CsvOptions` accepts. No test spilled a sort run
longer than a batch, called the k-way merge past its end, or failed a spilled
hash join's input after a bucket had opened its file (that leaked the file
until garbage collection). Four metrics counters' meanings had no test.

## Launch prompt

```
Read plans/testing/19-mutation-testing.md and implement it. This plan runs
LAST in the series (plans/testing/06-18 first); if any of them is not done,
say so and ask whether to proceed.

Follow CLAUDE.md's required workflow for every commit: bazel test //...
green, the jaffle-shop job 15/15 Succeeded, every stale doc updated in the
same commit. Commits: the mutator and its test; the runner; the smoke
campaign's tests; then the survivors' tests in groups by file.

Before editing, run ListAgents and git status. Mutants run in a git worktree
OUTSIDE the repo, at a clean commit, with its own Bazel output base; never in
the main tree. Check df before the campaign and keep to one worktree unless
there is room for two.

Stop and ask before: (a) using PIT or any tool from outside the repo unless
decision 1 chose it, (b) running more than the budget decision 2 chose,
(c) changing src/main code to kill a survivor (a survivor asks for a test; a
survivor that reveals a defect is reported first, with a repro), (d) marking
more than a quarter of a file's survivors as equivalent.

Report at the end: the per-file and per-operator table (killed, survived,
timed out, stillborn), each survivor with its diff and its test or
equivalence note, any defect a survivor revealed, and the campaign's
wall-clock time.
```
