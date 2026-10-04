# Plan 10: a failing query's message names the engine's internals, not the user's SQL

> Status: **open** · Effort: 1 day · Risk: low (messages and exception classes
> only; no query that runs today changes), but one decision first: where a
> pooled failure is unwrapped (see [Decisions](#decisions))

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
