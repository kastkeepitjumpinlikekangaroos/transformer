# Plan 14: deep and unbalanced SQL overflows the stack, stalls the parser, and can hang a job

> Status: **open** · Effort: 1-2 days · Risk: low for the hang (a `catch` and a
> `finally`), medium for the depth limit (a limit is a new refusal)
>
> **The first item hangs a job forever with no error.** It wants fixing before
> the others.

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
