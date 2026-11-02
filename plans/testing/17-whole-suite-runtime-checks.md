# Plan 17: whole-suite runtime checks: batch invariants, leaks and chaos scheduling

> Status: **done** 2026-11-02 (ee326f9 W1, 0ab531f W2, f520f05 W3; see
> [Outcome](#outcome); the five decisions taken overnight as recommended, not
> yet confirmed by the user; no finding held) · from the 2026-11-01 test survey, category 4 of 9 ·
> Effort: about a week (W1 2 days, W2 2-3 days with the close protocol, W3
> 2 days) · Risk: low for the checks, which are off by default and read once
> per JVM; medium for W2's close protocol, which runs through every operator
> · Depends on: none; W3 is worth most after
> [06](06-a-failed-run-leaves-no-partial-output.md), whose write protocol it
> should shake

## Why

Six whole-suite configurations in `.bazelrc` (`batch1`, `batch2`, `sharded`,
`serial`, `zone`, `hashzero`) each vary a setting no test can (docs/testing.md,
"Whole-suite configurations"). None checks the engine's own state while the
suite runs. DuckDB verifies every vector in its debug builds. Spark fails a
test that leaves a stream open (`DebugFilesystem.assertNoOpenStreams`) or
leaks task memory. This repo checks a plan after every pass (`PlanValidator`,
plans/testing/05 W1), but not:

- the batches the plan produces;
- what a test leaves behind;
- any interleaving beyond the one the pool's timing happens to give.

The evidence:

- **A batch can hold values its declared type cannot.** Nothing notices
  until a writer refuses one. That is how plans/robustness/09 was found: "a
  cast between two decimal types keeps the value as it is ... so the result
  holds values its declared type does not". The spill mode failed, far from
  the CASE that made the value.
  - `ColumnarBatch` checks only its column count and its row count
    (`core/ColumnarBatch.scala:12-25, 139-158`).
  - The one check that a decimal fits its type is the parquet writer's
    (`write/parquet/ParquetWriter.scala:386-402`).
- **Resources outlive the query that opened them.** This comes from reading
  the code; W2's audit measures it.
  - A parquet partition closes its file only once its last row group is read
    (`ParquetPartitionIterator.closeQuietly`, called from `loadNextGroup`).
  - A CSV partition closes its file only when `hasNext` finds no row
    (`read/csv/CsvReader.scala:304-309, 347-350`).
  - An operator's spill directory is deleted when its output is drained
    (`core/Spill.scala:252-265`: "The Spill shutdown hook covers the
    abandoned-iterator case").
  - LIMIT stops pulling (`LimitExec.truncate`), and a failing query stops
    mid-drain. Each leaves files open until garbage collection, and spill
    directories until the JVM exits. So does a query the `transformer query`
    shell stops: that shell is uncommitted work on 2026-11-01, and it stops a
    query by throwing from its scans.
  - In one job run this costs little. In the GUI, the data viewer or a shell,
    it accumulates.
- **The tests' spill isolation is read once per JVM.** `Spill.baseDir` and
  `Spill.rootDir` are lazy vals (`core/Spill.scala:48, 61`).
  - The per-test `transformer.spill.dir` set in `@Before`
    (`fuzz/ModeDifferentialFuzzTest.scala:45-53`, `core/SpillTest.scala:13-21`,
    and the `sql/exec/*SpillTest` classes) takes effect only for the first test
    in a JVM.
  - Later tests spill into the first test's directory. Its `@After` already
    deleted it, and `Files.createDirectories` makes it again.
  - The hooks delete what they find rather than check that it is empty.
- **Concurrency defects needed volume to show.**
  - The K>1 sharded deadlock reproduced only at 20000 seeds
    (plans/bugfixes/02e; the comment at `fuzz/BUILD.bazel:126-143`).
  - The `serial` configuration exposed a test that held only by timing: the
    DAG guard that two tasks overlap.
  - Nothing in the suite varies interleavings on purpose.

## What exists now

- **JVM-wide settings read once:** `transformer.batch_capacity`
  (`ColumnarBatch.scala:120-133`) and `transformer.hash_zero`
  (`KeyHash.zero`, `core/HashKeys.scala:233-246`). Each has a `.bazelrc`
  config that sets `JAVA_TOOL_OPTIONS` for every test JVM.
- **A walk that wraps every operator.** When metrics are on,
  `PhysicalPlanner.wrapWithMetrics` rebuilds the tree with every operator's
  output wrapped in a `MeteredIterator` (`walkAndWrap`, `wrapChildren`,
  `MeteredPlan`). An operator `wrapChildren` has no branch for is left
  unwrapped, which "is safe" for metrics.
- **Where a comparison reads a value its type cannot hold, by design.**
  `Analyzer.branches` says a comparison only reads its operands, so they may
  be cast under a type that does not hold them (`sql/plan/Analyzer.scala:102-110`).
  `ExprBatchTest.scala:367-369` pins INT 5000 read as DECIMAL(3,2).
- **A decimal may sit at a smaller scale than declared.** 1.5 in a
  DECIMAL(9,2) is allowed (`fuzz/CsvTextGen.scala:165-167`).
- **Hang detection:** the DAG fuzzer's watchdog fails a run past two minutes
  with a thread dump (`fuzz/oracle/DagSchedule.scala:52-66, 94`).
- **The pool's threads are daemons** (`core/Scheduler.scala:41-49`).

## What to build

### W1. `--config=verify`: every batch checked at every operator

**Setting.** `-Dtransformer.verify=true`, read once per JVM into a final val
as `KeyHash.zero` is. In `.bazelrc`:
`test:verify --test_env=JAVA_TOOL_OPTIONS=-Dtransformer.verify=true`.

**Hook.** Generalize the metrics walk to take a decorator, and wrap each
operator's `execute(p)` in a verifying iterator. When metrics and verify are
both on, both wrappers apply.

- Under verify no operator may go unwrapped. A test builds every operator
  the planner can build and asserts `wrapChildren` has a branch for each, or
  the walk throws on an operator it does not know.
- The scans' output is covered: `ScanExec` is an operator.
- So is what the job runner writes and materializes: a query's root output.

**Invariants**, checked against the operator's `outputSchema`:

1. The column count matches, and each vector's `dataType` equals its field's
   type, a DECIMAL's precision and scale included.
2. `numRows` is at most every column's capacity.
3. A DECIMAL value fits its declared type. Set to the declared scale it needs
   no rounding, and has at most the declared precision. A smaller scale than
   declared is allowed.
4. A DATE's epoch day and a TIMESTAMP's epoch microsecond lie within the
   storable range (plans/robustness/25).
5. A STRING holds no half of a surrogate pair, if decision D2 says so.
6. Field names match the schema's, or the verifier reports which operator
   renames them. Measure first: an operator that renames on purpose gets an
   exception with its reason.

The check runs at operator boundaries only, never inside expression
evaluation, where a comparison's operands may be cast under a type that does
not hold them (above).

**On a violation:** an `AssertionError` naming the operator (its class and its
line in `PlanTree`'s rendering of the plan), the column, the row and the
value, so the test fails where the value was made rather than where it is
written.

**Cost.** One pass over each batch, under the flag only.

**Would have caught:** plans/robustness/09 at the CASE's projection.

**Done when:** `bazel test //... --config=verify` and the jaffle-shop example
under it pass, or every finding is decided.

### W2. `--config=leaks`: what a test leaves behind, and a close protocol

**Audit.** Under `-Dtransformer.audit_leaks=true`, the engine counts what it
opens and records each opener's stack, as Spark's `DebugFilesystem` does:

- the files `CsvPartitionIterator`, `ParquetPartitionIterator` and the
  writers open;
- the operator spill directories `Spill.openOperatorDir` makes.

At JVM exit a shutdown hook reports every one still open, with its stack, and
every directory left under `Spill.rootDir`. It also reports engine-started
threads still alive that are not the pool's daemons. Then it ends the JVM
with a non-zero status, so Bazel fails the target (decision D3).

**Expect it to fire.** The abandoned iterators above, then the close
protocol (decision D4):

- `ExecutedQuery` gains `close()`.
- A LIMIT closes the input it stops reading, and a failing drain and a stop
  close what they abandon.
- Readers close their file, and operators their spill directories, on close.
- The job runner, the GUI's query console and the data viewer call it.

**Make the tests' spill isolation real.** The directory is read once, so set
it once per target, in `jvm_flags`. The `@After` hooks then check the
directory is empty rather than delete it.

**Done when:** the suite and the jaffle-shop example pass under
`--config=leaks`, with every leak fixed or decided.

### W3. `--config=chaos`: interleavings on purpose

**Setting.** `-Dtransformer.chaos=<seed>`, read once. At named points the
engine yields, or sleeps 0 to 2 ms, by a seeded per-thread random number
generator. It is a final val check when off.

**Points:**

- `Scheduler.submit`, before the callable runs (`core/Scheduler.scala:91-98`),
  and `Submitted.await`, before `get` (`:133-137`);
- `ExchangeExec`'s claim and publish (`sql/exec/ExchangeExec.scala:94-96,
  159-176`);
- `DataJob`'s event queue and its task submissions
  (`job/DataJob.scala:175, 192, 244`);
- the partitioned writers' commit, which plan 06's staged commit becomes;
- spill directory creation;
- task output materialization.

**Runs:** the whole suite under a few seeds, and the
`sharded_dag_scheduler_fuzz_campaign` and `starved_dag_scheduler_fuzz_campaign`
under chaos.

**When it fires:**

- A hang fails with the watchdog's thread dump.
- A test that asserted a timing says so with `assumeTrue`, as under `serial`.
- A real race is a `src/main` defect, reported with the seed and the points it
  passed.

Interleavings do not reproduce exactly from a seed, so a failure's report
carries the thread dump and the last points each thread passed.

**Done when:** the suite is green under chaos at five seeds, and both DAG
campaigns at their usual budgets.

## Decisions for the user

1. **What W1 verifies.** *Taken overnight as recommended, not yet confirmed
   by the user: (a).*
   - (a) **(recommended)** Every operator's output. A bad value is named
     where it was made.
   - (b) The root output only. Cheaper, but a value a later operator drops
     or re-casts is never seen.
2. **Half a surrogate pair in a STRING.** *Taken overnight as recommended,
   not yet confirmed by the user: (a).*
   - (a) **(recommended)** Not a value. The verifier flags one, and the paths
     that can make one refuse it: a literal through the Scala API, a view built
     in Scala. A file cannot hold one: UTF-8 has no encoding for it. Then every
     writer agrees. Today `CsvWriter` refuses one, while the parquet writer, and
     so spill, write it as `?` (`ParquetWriter.scala:368`, parquet-mr's
     `Binary.fromString`).
   - (b) A value every writer refuses, spill included. Whether a query fails
     would then depend on whether it spilled.
   - (c) Leave it as `docs/gotchas.md:2006-2012` documents.
3. **How W2's audit fails a target.** *Taken overnight as recommended, not
   yet confirmed by the user: (a).*
   - (a) **(recommended)** The exit audit with each opener's stack, ending the
     JVM non-zero. No test class changes, and the same `JAVA_TOOL_OPTIONS`
     pattern as every other configuration.
   - (b) A JUnit rule in every test class. Per-test attribution, but 79
     targets to edit.
   - (c) Both.
4. **Abandoned iterators.** *Taken overnight as recommended, not yet
   confirmed by the user: (a).*
   - (a) **(recommended)** The close protocol above. Files and spill
     directories are released when a consumer stops: LIMIT, an error, a stop.
   - (b) The audit counts only fully drained queries, and the leak is
     documented.
5. **Chaos in the default suite.** *Taken overnight as recommended, not yet
   confirmed by the user: (a).*
   - (a) **(recommended)** No: on demand, like the other configurations.
   - (b) A single-seed smoke run in `//...`.

## Decisions already made

- Whole-suite settings are system properties read once per JVM and set
  through `.bazelrc` configs. A test that holds only under the default says
  so with `assumeTrue` and its reason, and is never left red
  (docs/testing.md, "Whole-suite configurations").
- No new dependency (no jcstress, Lincheck or bytecode agent) and no
  backwards-compatibility shim: callers use `ExecutedQuery.close()` (CLAUDE.md).

## Tests

- **W1:** a hand-built batch holding a DECIMAL past its precision, a date past
  its range, and a column of the wrong vector type each fail the verifier with
  their message; the walk refuses an operator it has no branch for.
- **W2:** a LIMIT over a parquet, a CSV and a spilled sort, a query failing
  mid-drain, and a stopped query each release what they opened, with the
  audit counting zero after.
- **W3:** the points fire at the configured rate, and the DAG fuzzer's
  watchdog reports a deliberately wedged pool with its points.
- **Then:** the suite and jaffle-shop under each new configuration.

## Docs

`docs/testing.md` (the three configurations, the audit's report, chaos at
several seeds); `docs/architecture.md` (the verify walk, the close protocol,
the chaos points); `docs/conventions.md` (a new operator needs a branch in the
plan walk, a new resource registers with the audit, a new handoff gets a
chaos point); `docs/extending.md` (adding an operator); `docs/gotchas.md`
(findings, the once-read spill directory); `README.md` (`ExecutedQuery.close()`
under "Embedding in Scala").

## Risks

False positives come from by-design states (a decimal at a smaller scale,
an operator that renames, a comparison's read-only cast): measure before
asserting. Verify slows the suite, which is why it is opt-in. Chaos failures
do not replay exactly, so the thread dump and the last points passed carry the
diagnosis. The close protocol touches every operator that holds a resource:
land it with W2's audit as its test.

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, one commit per workstream:
W1 `ee326f9`, W2 `0ab531f`, W3 `f520f05`. At each, `bazel test //...` passed
(78 targets before, 79 after W1, 80 after W2, 83 after W3) and the jaffle-shop
job gave 15/15 Succeeded with every validation passing (JDK 21). At `f520f05`
all 83 passed by default (234 s), under `verify` (226 s), under `leaks`
(229 s) and under `chaos` at seeds 1 to 5. Each
regression failed at its workstream's base, run in a temporary worktree: W1's
four `SqlEngineTest` cases at `077c903`, W2's five base-observable
`ClosingTest` cases and `CsvWriterTest`'s staged-file case at `ee326f9`.

**Decisions**, each taken overnight as recommended, not yet confirmed by the
user:

1. (a) W1 verifies every operator's output.
2. (a) Half a surrogate pair is no text: the verifier flags one, a string
   literal holding one is refused at bind time, a `MaterializedView` built in
   Scala when it is built, and a `CatalogView` of one's own when a scan reads
   it (`CatalogView.stringsAreText`, which the readers and `MaterializedView`
   say for themselves). No reader gives one and no function makes one of text,
   so no writer meets one.
3. (a) The audit is an exit hook, ending the JVM with status 117.
4. (a) The close protocol (`core/Closing`, architecture §3h).
5. (a) Chaos runs on demand only.

**What each configuration found.**

- `verify` (231 s for the suite): a `UNION ALL` passed its right side's
  batches through under that side's names, so `CsvWriter` refused `SELECT a AS
  x FROM t UNION ALL SELECT k FROM u` ("Batch schema Schema(k:int?) does not
  match writer schema Schema(x:int?)"), in a job and in `transformer query
  --output`; and `ABS(NULL)` was a `DOUBLE` vector under a column declared
  `NULL` (`SELECT ABS(NULL)`). Both fixed. The rest were the checks' own: the
  tests that read a physical plan's shape see through the wrappers now, and
  `ResultPersisterTest`'s failing save, which held half a surrogate pair,
  fails with a fault instead. Invariant 4 (a DATE or TIMESTAMP past its range)
  holds by construction, the vectors storing an `Int` of days and a `Long` of
  microseconds, the types' ranges exactly, so nothing checks it and the
  plan's test of a date past its range cannot be built.
- `leaks` (262 s the first run, six targets failed in eight JVMs, 4 to 66
  files each; 227 s green): files a test or an oracle read in part or whose
  read failed, and one defect, fixed: a CSV writer whose header the charset
  cannot encode (a column name with an accented letter, written as US-ASCII)
  left its staged file
  open and on disk. The close protocol itself replaced what the plan's
  evidence named: LIMIT, a failing drain and a stopped query left files open
  until garbage collection and spill directories until exit, the data
  viewer's preview left the partition it read in part open, and
  `Scheduler.submitAndAwaitAll` returned at the first failure while its other
  tasks went on reading and spilling. The fourteen test classes that set the
  once-read spill directory per test moved nothing after the first; Bazel
  sets `java.io.tmpdir` to each target's `TEST_TMPDIR`, under which the
  default spill directory lies, so they set nothing now (rather than set it
  in `jvm_flags`, which would say the same thing again) and check after each
  test that no operator directory is left. Under the audit polymarket ran in
  78 s and left nothing open.
- `chaos` (258 to 296 s a seed, against about 250 without it): nothing. The
  suite was green at seeds 1 to 5, and the sharded and starved DAG-scheduler
  campaigns at their 2000-seed block under seed 11 (120 s and 127 s, every
  invariant and the hydration oracle holding). No race, no hang, and no test
  asserted a timing it upset. The points are reached (`job/chaos_job_test`
  passes every one in one job) and the watchdog's thread dump names each
  thread's last points (`fuzz/dag_watchdog_test`, over a pool wedged on
  purpose). The jaffle-shop job gave 15/15 under chaos, and under all three
  checks at once.

**Tests that say what they assume.** None needed an `assumeTrue`: no test
asserted something only the default makes true under verify, leaks or chaos.
The tests that read a physical plan's shape (`JoinSwapTest`,
`CteMaterializationTest`, `MetricsPlanWrapTest`, `ForcedPlans`) see through the
wrappers instead, and the fourteen spill test classes check that each test
leaves no operator directory behind.

**Polymarket**, after W2: exit 0, 14 Succeeded, 2 ValidationFailed, 1 Skipped,
97 s (`-Xmx12g`, JDK 21), output deleted after.

**Calls taken without asking.** The verifier names an operator by its line
of the physical plan as `EXPLAIN VERBOSE` prints it (`PlanTree` renders
logical plans only), with the whole plan after; the walk refuses an operator
it has no branch for under verify only, so a metrics-only walk keeps its
documented fallback; `PlanWrapper` and `ExchangeExec.feeds` are public so the
fuzz oracles can see through the wrappers; an aggregate, a DISTINCT and a
grace-hash join release their spill directory before they emit, as they fold
or join every run first; a failed read closes the partitions no one has read
yet, never one another thread is reading; the audit names engine threads by
the `transformer-` prefix the pool, the hooks and the query shell's worker
use; a chaos point's three actions are a third each.

**For the user.** Merging `main` brings `TopNExec` (a87f97f), which needs a
branch in the plan walk (`wrapChildren`, `childrenOf`), the close protocol
(its child drained in `Closing.draining`), and a query in `VerifyTest` that
builds it; until then `--config=verify` fails every `ORDER BY ... LIMIT`
query and `verify_test` names the class.

## Launch prompt

```
Read plans/testing/17-whole-suite-runtime-checks.md and implement it, one
workstream per commit (W1, W2, W3).

First check ListAgents and `git status`: another session may hold uncommitted
work in this tree (on 2026-11-01 one was editing PhysicalPlan.scala,
PhysicalPlanner.scala, ParquetReader.scala, ExecutionOptions.scala and the
CLI). If so, work in a scratch git worktree outside the repo and copy your
files back after comparing against a base snapshot
(concurrent-sessions-worktree memory).

Follow CLAUDE.md for every change: `bazel test //...` green, the jaffle-shop
example 15/15 with every validation passing, every stale doc updated in the
same commit. Run the whole suite under each new configuration before calling
a workstream done.

Stop and ask before: decisions D1-D5 (D1 and D2 before W1, D3 and D4 before
W2, D5 before W3); any check that fires on behavior a plan or the README calls
intended; adding any dependency.

Report: what each configuration found, with repros; the suite's run time
under each; and which tests now say what they assume.
```
