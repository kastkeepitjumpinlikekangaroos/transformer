# Plan 14: deep and unbalanced SQL overflows the stack, stalls the parser, and can hang a job

> Status: **done** 2026-10-30 · decided by the user, both the recommendation ·
> Effort: 1-2 days · Risk: low for the hang (a `catch` and a `finally`), medium
> for the depth limit (a limit is a new refusal)
>
> **The first item hung a job forever with no error.** It was fixed first.

## How this was found

[Plan 05](05-frontend-robustness-fuzzer.md)'s mutation mode nests an expression
2500 deep and truncates or unbalances parentheses; its clean-rejection property
then met a `StackOverflowError`, which is not an `Exception`, and parse errors
that took seconds. Following the overflow into the job runner found the hang.
The fuzz campaign's out-of-memory failure found the last item. Measured at
`4b6d882` on the JUnit main thread (a pool worker's stack is no deeper).

## Problem

1. **An `Error` in a task hangs the whole job.** A task whose SQL is `SELECT a +
   a + ... AS v FROM t` with 2500 terms overflows the binder's stack, and
   `job.run()` never returns: the main thread waits in `LinkedBlockingQueue.take`
   in `DataJob.runUnifiedDag`'s event loop, the workers are idle, and nothing is
   logged (killed after 150 s). The task's `Callable` posts `TaskCompleted` only
   after `runOneTask` returns, and `runOneTask` catches `NonFatal`, which a
   `StackOverflowError` is not; the error ends the `Callable`, `ForkJoinPool`
   keeps it on a future nobody joins, and the event never arrives. Any
   `VirtualMachineError` or `LinkageError` thrown in a task does the same (an
   `OutOfMemoryError` on the task's own thread), and so does one in an input's
   load, whose `Callable` has the same shape. `oomHint` exists for an input whose
   load fails out of memory, but only reaches one that arrives wrapped.
2. **The binder recurses once per operator.** A left-deep chain of 2000 `+` terms
   or 3000 `OR` terms throws `StackOverflowError` from `LogicalBuilder.build`
   (1000 `+` and 1000 `AND` terms bind). A query builder that writes `id = 1 OR id
   = 2 OR ...` reaches that. Through `SqlEngine.execute` the error reaches the
   caller as itself; in a job it is item 1. (`CleanRejection.Defect.DeepNesting`)
3. **The parser recurses once per nesting level.** 3000 nested parentheses or
   1000 nested calls (`ABS(ABS(...))`) overflow inside JSqlParser, reported as
   `SQL parse error: java.lang.StackOverflowError` (1000 parentheses and 300
   calls parse). (`DeepNesting`)
4. **Unclosed parentheses run the parser into its timeout.** JSqlParser
   backtracks on each one: `SELECT (a +` fails in 44 ms, `SELECT ((a +` in 109
   ms, `SELECT (((a +` in 7.0 s, and `SELECT ((((a +` hits JSqlParser's six-second
   timeout and fails after 8 s with `SQL parse error: Time out occurred.`, which
   names nothing (the parser's thread keeps working for another ~5 s after the
   error). `SELECT a FROM t WHERE (((a =` takes 4.8 s; the clean-rejection corpus
   has single texts over 4 s. A typo in the GUI's SQL view stalls it that long.
   Balanced nesting is fast (300 parentheses in 41 ms), except around a name:
   2500 parentheses around a table take 5.7 s. (`ParseTimeout`)
5. **The nested-loop guard bounds the smaller side only.**
   `PhysicalPlanner.enforceNestedLoopGuard` refuses a non-equi join when both
   sides exceed 5000 rows, so a chain of them multiplies past it: `SELECT x.c0
   FROM t x JOIN t y ON x.c0 JOIN t y ON x.c0` over 300 rows is 27 million rows,
   which ran the fuzz campaign's 1.2 GB heap out (seed 245683). The guard also
   throws `UnsupportedOperationException` (plan 10).

## Proposed fix

- `runUnifiedDag`: post `TaskCompleted` (and `InputCompleted`) in a `finally`,
  and turn any `Throwable` into a failed result with its message; let a fatal
  `VirtualMachineError` other than `StackOverflowError` still fail the job, but
  as an error the job reports, never as a wait.
- A depth limit in the binder, counted on the way down, with a refusal that
  names it (`the expression nests N deep; the limit is M`); or bind a left-deep
  chain of one operator iteratively, which is the shape query builders write.
- Check the text's parentheses before JSqlParser sees it — a scan outside
  strings and comments, as `SqlTextGen.Lexer` does — and refuse an unbalanced
  text at once with the column of the unmatched parenthesis. That ends item 4's
  common case without touching the parser; a timeout that remains says the text
  took too long to parse and why.
- The nested-loop guard bounds the estimated product of a join's sides.

## Decisions

- The binder's depth limit (and whether chains bind iteratively instead): a new
  refusal for SQL that binds today when shallow enough.

## Verification

- A `DataJobTest` regression first, failing (hanging) at HEAD: a job whose task
  overflows the binder finishes, with that task Failed and its message recorded,
  and its dependents Skipped. Run it with a timeout.
- `SqlEngineTest`: the depth refusal, the unbalanced-parentheses refusal (and its
  time), the nested-loop product.
- Delete `DeepNesting` and `ParseTimeout` from `CleanRejection.Defect`, then run
  `frontend_fuzz_campaign` at 100000 seeds.

## Docs to update

`docs/gotchas.md` (an `Error` in a task, the depth limit, the parse-time check),
`README.md` (the limits a query must stay inside).

## Outcome

The user decided two questions (2026-10-30), the recommendation both times:

1. **A fixed limit of 256 levels, with AND and OR chains regrouped.** A query
   nests at most 256 levels of the tree JSqlParser parses it into, the same on
   every machine; an AND or OR chain binds at any length, regrouped into a
   balanced tree first. Measured before deciding, at `-Xss1m` (Linux's default
   thread stack; macOS gives 2 MB) and cold: the shallowest overflow was about
   600 levels, 300 nested CASEs or 300 levels of `a + (...)`, and warm JIT
   frames moved it (a 5000-term OR chain once got past the binder).
   Declined: counting every level of a chain (an OR list past 250 terms
   refused), and no fixed limit (catch the overflow, which a machine's stack
   and JIT decide).
2. **The nested-loop guard keeps its rule and adds chains**: a join with no
   equality is still refused when both sides exceed 5000 rows, and now also
   over the output of another such join when the two would pair more than 25
   million rows. Declined: bounding every product at 25 million, which would
   refuse a range join of 100 buckets against a million rows that runs today.
   (The user first asked why equality-free joins are supported at all: CROSS
   JOIN and the comma join, which plan 12 made the way to pair every row; the
   comma spelling of an equi-join, `FROM t, u WHERE t.a = u.a`, which plans as
   an equality-free join under a filter; and range and interval joins.)

What landed:

- **No `Error` leaves a run waiting.** `DataJob`'s task and input workers
  catch every `Throwable` and post their completion in a `finally`. A stack
  overflow, a `LinkageError` or any other `Error` fails its task, with a
  message saying what happened; the JVM's own failure, a
  `VirtualMachineError` other than a stack overflow such as running out of
  memory, stops the run: the failing worker sets a stop that the loop and
  every worker read, so no task starts after it, each that had not started is
  Skipped saying why, the running ones finish, and `JobResult.error` and
  `job.json`'s `errorMessage` name it. An input that runs out of memory
  loading stays a failed input, as plan 08 has every failure to load.
- **The depth limit** lives in `SqlParser.parse`, so every caller has it:
  after JSqlParser parses, `regroupChains` rebuilds each AND and OR chain as a
  balanced tree of its own nodes (associative, NULLs included; the same text
  when rendered), and `refuseDeepNesting` walks the tree without recursion,
  through every getter of every node class by reflection as `toString` and
  `TablesNamesFinder` walk it, and refuses past 256 with the depth it found.
  JSqlParser's own stack overflow is the same refusal. `TaskDag` gives a task
  too deep to read no tables, so the DAG builds and the task fails alone when
  it runs.
- **Parentheses** are checked before the parser sees them, lexed by the
  parser's own token manager: one that is never closed or closes nothing is a
  parse error naming its line and column, at once, and nesting past 256 is
  refused before the parser recurses. A parse that still runs past
  JSqlParser's time limit says so and why (25 nested CASTs do).
- **The nested-loop guard** bounds a join with no equality by the product of
  its sides (`rowsAtMost`), which its cardinality estimate understated.

Found on the way and fixed with it:

- **Two passes rebuilt AND chains left-deep.** `FilterPushdown` and the
  planner's join residual split a predicate into conjuncts and folded them
  back with `reduceLeft`, undoing the regrouping: a 5000-term WHERE then
  overflowed `ColumnProjectionPushdown`. Both use
  `JoinSideAnalysis.conjunction`, a balanced tree.
- **A UNION of many arms** bound as a left-deep chain: 2000 arms took fifteen
  seconds and 5000 overflowed `FilterPushdown`. Each run of UNION or UNION
  ALL now binds as a balanced tree (`balancedUnion`), associative within the
  run and with its arms in order: 5000 arms take half a second.

The fuzzer's records are gone, the proof the fixes landed:
`CleanRejection.Defect` is empty (its recognizers are data now, so the empty
list leaves no dead code), the deep-nesting and unclosed-parenthesis repros
come out clean (`FrontendFuzzTest.literalsAndDeepTextAreRefusedCleanly`), and
`JobDagGen` throws every other injected failure as a `LinkageError`, which the
DAG property's liveness watchdog would report as a hang against the old
runner. The eleven regressions in `SqlEngineTest`, `JoinSwapTest`,
`DataJobTest`, `TaskDagTest` and `FrontendFuzzTest` each fail at the commit
before; the three job tests hang there until their 60-second timeout.

Verified: `bazel test //...` 53/53; `frontend_fuzz_campaign` at 100002 seeds of
each property (three blocks of 33334, 197 to 235 seconds each, faster than
before since unbalanced text no longer waits on the parser); the three DAG
scheduler campaigns at 5000 seeds each; the metamorphic, sharded and
mode-differential campaigns at 2500 seeds each, since unions and conjunctions
now bind as other trees; all green; jaffle-shop 15/15 with every validation
passing. Against the old `DataJob` the DAG property hangs on its injected
`LinkageError`s until bazel's 900-second timeout, with a thread dump.

