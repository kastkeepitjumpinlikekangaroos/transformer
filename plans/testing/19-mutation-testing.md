# Plan 19: mutation testing measures the net

> Status: **not started** · from the 2026-11-01 test survey, category 9 of 9
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

1. **The mutation engine.**
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
2. **Scope and budget.**
   - **(a) About 15 mutants per file over the 16 files above, stratified by
     operator: about 250 mutants, 4-5 hours (recommended).**
   - **(b) Every site of every operator in those files:** thousands of
     mutants, days of machine time.
   - **(c) Five files only** (`Ops`, `Funcs`, `WindowExec`, `AggregateExec`,
     `JoinOrder`).
3. **After the campaign.**
   - **(a) The tool and the equivalent-mutant ledger are kept, and the
     campaign runs again at the end of each testing round. There is no kill
     floor in CI, since it is too slow and flakes on equivalent mutants
     (recommended).**
   - **(b) A tagged, opt-in target, like the fuzz campaigns, that fails when a
     file's kill rate drops below its last recorded one.**
   - **(c) A one-off report; the tool is deleted.**
4. **What a survivor costs.**
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
