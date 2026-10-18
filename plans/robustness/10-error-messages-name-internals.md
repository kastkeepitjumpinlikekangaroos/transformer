# Plan 10: a failing query's message names the engine's internals, not the user's SQL

> Status: **done** 2026-10-17 (see [Outcome](#outcome)) · Effort: 1 day · Risk:
> low (messages and exception classes; no query that ran changes its answer)

## How this was found

By [plan 05](05-frontend-robustness-fuzzer.md)'s clean-rejection property
(`oracle/CleanRejection`), which runs mutated SQL, the clause table spliced
everywhere and queries over every column type, and requires every failure to be
an `IllegalArgumentException` whose message names something the user wrote and
carries no internal marker. Each finding below is a known defect of that oracle
(`CleanRejection.Defect`), recognized from what was thrown, and each carries the
repro `FrontendFuzzTest.everyKnownDefectStillReproduces` runs: removing the entry
is the proof the fix landed, and the test fails until it is removed. Measured at
`4b6d882` against `t(a INT, b INT, s STRING)`.

## Problem

1. **Every parse error names the parser's exception class.**
   `SELECT a FROM` fails with `SQL parse error: net.sf.jsqlparser.parser.ParseException:
   Encountered unexpected token: "FROM" ...`, and a lexical error with
   `... net.sf.jsqlparser.parser.TokenMgrException: Lexical error at line 1,
   column 21 ...`. `SqlParser.parse` builds its message from
   `JSQLParserException.getMessage`, which is the cause's `toString`. This is about
   40% of the clean-rejection corpus. (`ParserClassInParseError`)
2. **The binder's catch-all messages name the JSqlParser class they met.**
   `Unsupported expression: ExistsExpression: EXISTS (SELECT 1 FROM u)`, and the
   same for `IsDistinctExpression`, `IsBooleanExpression`, `IntervalExpression`,
   `JdbcParameter`, `JdbcNamedParameter`, `ArrayConstructor`, `HexValue`,
   `ParenthesedExpressionList`; `Unsupported FROM item: ParenthesedSelect` for
   every derived table; `Unsupported set operation: ExceptOp` (and `IntersectOp`,
   `MinusOp`); `IN subqueries not supported (got ParenthesedSelect)`; `LIMIT must
   be an integer literal, got SignedExpression` (`Column`, `DoubleValue`; also
   for OFFSET, TOP and FETCH FIRST); `Only SELECT statements are supported (got
   Insert)`. (`ParserClassInRejection`)
3. **Rendering the refused node can throw.** The catch-all interpolates the node
   (`$other`), which is JSqlParser's `toString`, and that throws for some nodes:
   `SELECT a FROM t WHERE CAST(a AS ROW(x INT)) IS FALSE` fails with
   `NullPointerException: Cannot invoke "net.sf.jsqlparser.statement.create.table.ColDataType.toString()"
   because "this.colDataType" is null`. `IS FALSE` is not audited, so the
   rejection pass stops there, and the ROW-type CAST under it, which the pass
   would refuse by name, is rendered instead. `LogicalBuilder.canonicalSql`, used
   to rebind a HAVING, calls the same `toString`. (`RenderingThrows`)
4. **A failure while running inside a pooled operator arrives wrapped.**
   `SELECT SUM(CAST(s AS INT)) FROM t` over a non-numeric `s` reaches the caller
   as `ExecutionException` whose message is `java.lang.IllegalArgumentException:
   java.lang.IllegalArgumentException: Cannot cast 'x' to int`. `ForkJoinTask`
   re-creates a task's exception on the waiting thread (the same class, the
   original as its cause, its `toString` as the message), and
   `Scheduler.submitAndAwaitAll` documents that it does not unwrap. A nested
   fan-out adds `java.lang.RuntimeException: java.util.concurrent.ExecutionException: ...`
   (`ForkJoinTask` wraps a task's checked exception), and an exception class with
   no cause constructor arrives as a copy with **no message at all**: the decimal
   overflow `Decimal overflow: ... does not fit decimal(38,18)` under a sort comes
   back as `ExecutionException: java.lang.ArithmeticException`. `DataJob` records
   `e.getMessage` in `_run.json`, so a task's recorded error is that chain of
   class names. (`PoolWrappedFailure`)
5. **The ungrouped-column error lists aggregate slots** (carried over from plan
   05): `SELECT a, COUNT(*) AS c FROM t` and `SELECT COUNT(*) AS c FROM t HAVING
   a > 1` report `Unknown column 'a'. Available: [_agg0]`; with no aggregate in
   the SELECT list (`SELECT a FROM t WHERE a = 1 HAVING a = 1`) the list is
   empty: `Available: []`. (`AggSlotInUnknownColumn`)
6. **`CAST(TRUE AS DOUBLE)`** (or FLOAT) fails while running with a raw
   `NumberFormatException: For input string: "true"`; named to the user as a
   follow-up on 2026-10-03 and never planned. (`BooleanCastToNumber`)
7. **`ROLLUP` and `CUBE` read as unknown functions** (carried over from plan
   05): `GROUP BY ROLLUP(a)` reports `Unknown function 'ROLLUP(int)'`, and
   `CUBE(a)` the same, because JSqlParser parses both as calls inside a plain
   GROUP BY. The message is clean by the property's rules (no internal marker,
   it names what the user wrote), so it has no `CleanRejection` entry; it names
   the wrong thing. It should name the grouping construct, as the `GROUPING SETS`
   rejection does. Implementing them is a separate decision.
8. Found while reading, not by the corpus (whose tables are never that large):
   `PhysicalPlanner.enforceNestedLoopGuard` refuses a non-equi join over more
   than 5000 rows a side with an `UnsupportedOperationException`, an internal
   class, rather than the `IllegalArgumentException` every other rejection is.

## Proposed fix

- `SqlParser.parse`: the message is the parse exception's own message, without
  its class; `Encountered ... at line 1, column 10` already names the token and
  the place.
- The binder's catch-alls name the construct in SQL's words, and never render a
  node they cannot trust: a short map from the JSqlParser classes that reach them
  to their SQL (`EXISTS`, `IS DISTINCT FROM`, `IS TRUE`, `INTERVAL`, a parameter,
  an array, a derived table, `EXCEPT`), with the text the user wrote when it can
  be cut from the input by position. `LogicalBuilder.canonicalSql` must not
  throw.
- Unwrap a pooled failure so the caller gets the task's own exception (see the
  decision). Whichever layer does it, the decimal overflow must arrive with its
  message.
- The ungrouped-column error says the rule: `a must appear in GROUP BY or inside
  an aggregate`.
- `CAST(boolean AS DOUBLE)`: see the decision.
- The nested-loop guard throws `IllegalArgumentException`.
- `ROLLUP(...)` and `CUBE(...)` as a GROUP BY item are refused by name, with the
  advice `GROUPING SETS` gives.

## Decisions

- **Where to unwrap.** `Scheduler.submitAndAwaitAll` could rethrow the task's
  own throwable (keeping the waiting thread's stack as a suppressed exception),
  which fixes every caller of the API and changes the documented "NOT unwrapped"
  contract; or `DataJob` could record the root cause, which fixes `_run.json` and
  the GUI but leaves `SqlEngine.execute` callers with the wrapper. Recommended:
  the first.
- **`CAST(boolean AS DOUBLE)`**: a bind error naming the CAST, as `CAST(int AS
  DATE)` is (the strictness the user prefers), or `1.0` / `0.0` as `CAST(boolean
  AS INT)` already gives 1 / 0. Recommended: match INT, since the engine already
  answers that one.

## Verification

- A regression per item in `SqlEngineTest`, each failing at HEAD: the parse
  error's message has no class name, each catch-all names its construct, the ROW
  CAST under IS FALSE is a clean rejection, a run-time cast failure under an
  aggregate and the decimal overflow under a sort reach the caller as
  `IllegalArgumentException` / `ArithmeticException` with their own message, the
  ungrouped-column message, the boolean CAST, `ROLLUP` / `CUBE` refused by
  name, and a job whose task fails while
  running records the plain message in `_run.json` (`DataJobTest`).
- Delete `ParserClassInParseError`, `ParserClassInRejection`, `RenderingThrows`,
  `PoolWrappedFailure`, `AggSlotInUnknownColumn` and `BooleanCastToNumber` from
  `CleanRejection.Defect`, then run `frontend_fuzz_campaign` at 100000 seeds.

## Docs to update

`docs/gotchas.md` (the error-message entry), `docs/testing.md` (the defects
`CleanRejection` no longer excuses), `README.md` if the job record's error text
is described there.

## Outcome

The user's decisions, 2026-10-17, each the recommended option:

- **The scheduler rethrows.** A pool task that fails makes the thread waiting
  for it throw the task's own exception, the waiting thread's stack added to it
  as a suppressed exception. One refinement the plan did not foresee was in the
  question: a fatal error (an `OutOfMemoryError`, a `StackOverflowError`) stays
  wrapped in an `ExecutionException`, because `DataJob`'s task runner catches
  only `NonFatal` failures, and an unwrapped fatal error would escape it and
  hang the job, plan 14's first item, where wrapped it is recorded as a failed
  task.
- **`CAST(boolean AS DOUBLE)` and `AS FLOAT` are 1.0 and 0.0**, as
  `CAST(boolean AS INT)` already gave 1 and 0.

What landed:

- **Parse errors.** `SqlParser` reads the innermost message in the parser's
  exception chain. JSqlParser parses on a thread of its own and gives its
  exception the message of what that thread threw, which is the parser's
  exception's `toString`; the innermost message has no class in it. A parse
  error now reads `SQL parse error: Encountered unexpected token: "#" ... at
  line 1, column 29.`, and `Only SELECT statements are supported` names no
  statement class.
- **Refusals by name.** `UnsupportedClauses.refusal` names a construct the
  binder has no case for by what SQL calls it, with advice where there is
  something to write instead, from a table keyed by the class JSqlParser parses
  it as (`Constructs`, about sixty entries): `EXISTS is not supported; name the
  subquery in a WITH clause and JOIN it`, `A parenthesized join in FROM is not
  supported; write the joins without the parentheses`, `EXCEPT is not
  supported; only UNION and UNION ALL are supported`, an operator the binder
  does not implement as it is written (`The DIV operator is not supported`). It
  covers every expression, FROM item and set operation the audit does not, and
  the audited classes the binder refuses where it meets them: a query where a
  value goes, a row value, a star as an argument, a parenthesized FROM item
  (named by what it holds). The binder's catch-alls for expressions, FROM
  items, SELECT shapes and set operations all go through it, and IN with a
  subquery, a non-literal row count, a frame bound, ESCAPE, TRIM and a LAG
  offset say their rule without rendering the node.
  `UnsupportedClausesTest.everyConstructTheBinderRefusesHasAName` requires an
  entry for every such class, so a JSqlParser upgrade cannot bring one in
  nameless. The table lives in `UnsupportedClauses` rather than
  `LogicalBuilder` because the bytecode audit counts every class
  `LogicalBuilder` matches on as one the binder reaches.
- **Nothing is rendered through JSqlParser.** No message renders a node, and
  `canonicalSql` (the text that matches a SELECT item, a HAVING or an ORDER BY
  key to its GROUP BY key, aggregate or window, and names an unaliased column)
  matches only the node itself when `toString` throws, so binding goes on to
  refuse the node by name: `CAST(a AS ROW(x INT)) IS FALSE` is `IS FALSE is
  not supported` in WHERE, the SELECT list, HAVING, GROUP BY, ORDER BY and a
  window.
- **Pooled failures.** `Scheduler.submit` returns a `Submitted`, whose `await`
  rethrows what the callable threw (`Scheduler.rethrowable`); every wait on the
  pool goes through it: `submitAndAwaitAll`, the CSV and parquet writers,
  `MaterializedView.materializeManyInParallel`, and `ExchangeExec`'s latched
  failure, which every caller now gets as itself rather than behind
  `ExchangeExec materialization failed`. The wait is the same
  `ForkJoinTask.get()`, so work-helping is unchanged.
- **The ungrouped column.** In an aggregate query a column the output does not
  hold by name resolves against the input: a GROUP BY key written another way
  is that key, another column of the input is `Column 'b' must appear in GROUP
  BY or inside an aggregate`, and a column the input does not have is unknown
  among the input's columns.
- **`ROLLUP (...)` and `CUBE (...)`** in GROUP BY are refused as groupings,
  with GROUPING SETS' kind of advice (each level, or each combination,
  combined with UNION ALL). Outside GROUP BY a call to either is still an
  unknown function.
- **The nested-loop guard** throws `IllegalArgumentException`.

Found on the way and fixed in the same change:

- **A window in a grouped query lost the groups' aggregates.** `SELECT a,
  SUM(COUNT(*)) OVER () FROM t GROUP BY a` threw `NoSuchElementException: key
  not found: count(*)`, an internal class with an internal invariant's text,
  and so did an aggregate in a window's ORDER BY, unless the SELECT list held
  the same aggregate: `buildAggregation` collected aggregates from everything
  but window functions. It now collects them from a window's inputs too. No
  corpus drew an aggregate inside a window.
- **A GROUP BY key written another way was an unknown column**: `SELECT t.a,
  COUNT(*) FROM t GROUP BY a` failed with `Unknown column 't.a'. Available: [a,
  _agg0]`. The rule's message would have been false for it, so the key now
  binds.
- **The writers unwrapped a fatal error to a raw copy** (`e.getCause` of the
  pool's wrapper), which escaped the task runner's `NonFatal` catch: plan 14's
  hang, reachable through a partition writer. On `Submitted.await` a fatal
  error reaches the runner wrapped, and the task fails.

Regressions, each failing at the base commit (checked in a worktree there with
the new tests copied in): in `sql_engine_test`, the parse errors, 33 refusals by
name, the ROW cast under `IS FALSE` in six positions, a CAST failure inside an
aggregate, a DISTINCT, a sort and a join and a decimal overflow inside an
aggregate and a sort's pooled tasks (two rows a partition, so the sort compares
inside its tasks; with one, the overflow happened in the final merge on the
calling thread and was never wrapped), the ungrouped column and the key written
another way, the window over a grouped query, the boolean CAST on both paths,
and ROLLUP / CUBE; the guard's class in `join_swap_test`; and a task failing
inside the SUM's fan-out recording `Cannot cast 'x' to int` in `data_job_test`,
where it recorded `java.lang.IllegalArgumentException: Cannot cast 'x' to int`.
`scheduler_test` pins the new contract (own exception from outside the pool and
two fan-outs deep, a checked exception, the waiter's stack suppressed, a fatal
error wrapped) and `unsupported_clauses_test` the table's completeness.

The fuzzer. The six defects are gone from `CleanRejection.Defect`, and with
them everything that excused a defect carried by an otherwise clean failure:
the pool unwrapping, the parse-error prefix and the class names cut from the
binder's messages, `Failed.defects`. `cleanRejectionClassifiesFailures` now
calls each of the old shapes a finding.

The campaigns. `frontend_fuzz_campaign` ran 100000 seeds of each property in three
blocks, about three and a half minutes each beside two others. One block met
seed 54624, a mutated query holding `IF(NULL, DATE '2024-03-10', '2024-03-10')`,
which dies on the vector path with `ClassCastException: NullVectorImpl cannot
be cast to BooleanVector` because IF's condition is never cast to BOOLEAN
(`IF(NULL, 1, 2)` does the same). That is plan 11's `ConditionNotBoolean`, whose
recognizer had left a NULL condition out; plan 09's typing is what let the
seed's text bind. It is recorded in plan 11, the recognizer covers a NULL
condition, and the block was green on a second run.
`starved_dag_scheduler_fuzz_campaign`, the pool-starvation hunter, was green at
20000 seeds; it ran because every wait on the pool now goes through
`Submitted.await`.
