# Plan 12: text the parser accepts and the binder never sees

> Status: **done** 2026-10-18 (see [Outcome](#outcome)) · Effort: 1 day · Risk:
> medium (each item makes a query that ran an error)

## How this was found

[Plan 01](01-reject-or-honor-every-clause.md) made the binder refuse every AST
getter it does not read, and its reflection test keeps that true. What that
cannot reach is text that never becomes a getter's value at all: JSqlParser
stops at the first statement, keeps the last of a repeated clause, and accepts a
JOIN with no condition, so the AST the binder walks is already missing part of
what the user wrote. [Plan 05](05-frontend-robustness-fuzzer.md)'s mutation
mode found the shape (its `duplicate-clause` and `inject-token` mutations), and
probing around it found the rest. Each item is in
`SqlTextGen.BeyondTheTable`, which `FrontendFuzzTest.noSilentDropRefusesWhatTheTableCannotList`
holds to account: while its `knownPlan` is this file it must still bind, and
once fixed it must be refused in every context. Measured at `4b6d882` against
`t(a INT, b INT, s STRING)` holding five rows and `u(a INT, b INT)`.

## Problem

1. **A statement after `;` is ignored, whatever it is.** `SELECT a FROM t;
   SELECT b FROM t` returns `t.a`; `SELECT a FROM t; garbage` and `SELECT a FROM
   t; DROP TABLE t` run too. `SqlParser.parse` calls `CCJSqlParserUtil.parse`,
   which parses one statement and leaves the rest. `docs/gotchas.md` says
   "SqlParser.parseSelect only accepts a SELECT", which reads as a refusal.
2. **A repeated tail clause keeps its last.** `ORDER BY a DESC ORDER BY a ASC
   LIMIT 1` returns the smallest `a`; `LIMIT 1 LIMIT 2` two rows; `OFFSET 1 OFFSET
   2` skips two; `FETCH FIRST 1 ROWS ONLY FETCH FIRST 3 ROWS ONLY` three rows.
   JSqlParser sets the field twice. (A repeated WHERE, GROUP BY or HAVING does not
   parse, and LIMIT beside FETCH FIRST is already refused by name.)
3. **A CTE name defined twice keeps the second.** `WITH q AS (SELECT a FROM t), q
   AS (SELECT b AS a FROM u) SELECT * FROM q` returns `u.b`; standard SQL
   refuses the second definition.
4. **A JOIN with no condition is a cross join.** `t JOIN u`, `t INNER JOIN u` and
   `t LEFT JOIN u` each return all 15 pairs. Standard SQL requires ON or USING
   after every join but CROSS and NATURAL; MySQL reads a bare JOIN as a cross
   join, and a LEFT JOIN with no condition has no meaning in either.
5. **`NOT NOT a = 1` is read as a comparison of `NOT a` with 1**, which then
   fails to bind (`Cannot compare BOOLEAN with INT`), where SQL reads `NOT (NOT (a
   = 1))`. A single `NOT a = 1` is read correctly. Recorded as a known
   disagreement of `oracle/SyntaxEquivalence` ("NOT NOT").

## Proposed fix

- Parse with `CCJSqlParserUtil.parseStatements`, or check the parser consumed the
  whole input, and refuse anything after the first statement: `only one statement
  is supported; remove the text after ';'`. A trailing `;` alone stays fine.
- A repeated tail clause leaves no trace in the AST, so it has to be found in the
  text: a scan of the top-level tokens (outside parentheses, strings and
  comments) for ORDER BY, LIMIT, OFFSET and FETCH appearing twice at one depth.
  `SqlTextGen.Lexer` is a model of the scan that needs no parser.
- `buildAnySelect` refuses a WITH name already in scope.
- A JOIN with no ON is refused unless it is CROSS or NATURAL (NATURAL is refused
  by name already), or read as CROSS JOIN: see the decision.
- `NOT NOT`: the binder can see `EqualsTo(NotExpression(x), y)` where the text
  had no parentheses; re-associating is fragile, refusing with `write NOT (NOT
  (...))` is not.

## Decisions

- **A bare JOIN**: refuse (standard SQL, and the user's preference for
  strictness), or read it as CROSS JOIN, as MySQL does and as it runs today. A
  LEFT / RIGHT / FULL JOIN with no condition should be refused either way.

## Verification

- A regression per item in `SqlEngineTest`, failing at HEAD.
- Clear each construct's `knownPlan` in `SqlTextGen.BeyondTheTable`, so
  `noSilentDropRefusesWhatTheTableCannotList` requires it to be refused in every
  context; delete "NOT NOT" from `SyntaxEquivalence.KnownDefects`.

## Docs to update

`docs/gotchas.md` (the multi-statement line, and a JOIN's condition), `README.md`
(the grammar: one statement; JOIN needs ON).

## Outcome

The user's decisions, 2026-10-18, each the recommended option:

- **A JOIN with no condition is refused**, as standard SQL, Postgres, BigQuery
  and SQL Server refuse it, rather than read as the cross join MySQL and Spark
  read it as, and as it ran. The error points at `CROSS JOIN` and `JOIN ... ON`.
  A LEFT, RIGHT or FULL JOIN with none is refused too.
- **Every join takes exactly the ON its kind takes**, which probing turned up
  beyond this plan: JSqlParser also accepts `t CROSS JOIN u ON c` and
  `t, u ON c`, each of which ran as an inner join on `c`, and two ONs on one
  join, `t JOIN u ON c1 ON c2`, which the binder ANDed. All three are refused.

What landed:

- **One statement.** The parser stops at the `;` after the first statement,
  so `SqlParser.parse` lexes the text and refuses anything after that `;` but
  more of them and comments: `Only one statement is supported; remove the text
  after ';'`. It lexes through `CCJSqlParserUtil.newParser(text).getNextToken()`,
  since a bare `CCJSqlParserTokenManager` has no feature configuration, which
  the lexer reads for a string holding `\'`. `TaskDag` parses through the same
  façade, so a task with a second statement fails its job when the DAG is
  built, as a parse error does; the DAG used to be built from the first
  statement alone, without the second's tables.
- **A tail clause comes once.** The text scan proposed above would refuse
  valid SQL: JSqlParser takes `limit`, `offset` and `order` as column names, so
  `SELECT limit FROM t LIMIT 1` has two LIMIT tokens at one depth. A repeated
  clause is lost because two productions set one field, so the statement
  rendered back with `toString` has one fewer of that clause's keyword than the
  text, and `SqlParser.refuseLostTailClauses` compares the two counts, lexed
  alike, in which a column named `limit` appears on both sides. Over the 1344
  SQL strings in the repo it flagged only this plan's repros; a statement that
  does not render (a CAST to a ROW type) is left to the binder, which refuses
  that node by name. Probing found a shape no count sees: after a UNION's bare
  last arm a repeated LIMIT or OFFSET is split, not overwritten, the first on
  the arm and the second on the union, and the binder applied the arm's inside
  the arm (`... UNION ALL SELECT a FROM u LIMIT 1 LIMIT 2` kept one row of `u`,
  then two of the union). `buildSetOperationList` refuses a clause both carry.
  `LIMIT 1 OFFSET 2 LIMIT 3`, which `PlainSelect`'s own grammar overwrites, is
  caught by the count. The message is `LIMIT is written twice for one query;
  keep one`.
- **A WITH defines a name once**, compared without case as references resolve:
  `WITH defines 'q' twice; give each query its own name`. A WITH inside a CTE
  body may still reuse an outer name, which it shadows there, as standard SQL
  allows; "a WITH name already in scope", read literally, would have refused
  that too.
- **Join conditions**, in `LogicalBuilder.joinConditions`: `JOIN needs an ON
  condition; write CROSS JOIN to pair every row` (LEFT, RIGHT and FULL say only
  that they need one), `CROSS JOIN takes no ON; write JOIN ... ON to join on a
  condition` and its comma twin, and `JOIN takes one ON; combine the conditions
  with AND`. The standard's nested join, `t JOIN u JOIN v ON c1 ON c2`, which
  JSqlParser gives as a join with no ON followed by one with two, is refused as
  itself rather than as the first join's missing ON.
- **NOT** was broader than recorded. Over a BOOLEAN, `NOT NOT f IS NULL` did
  not fail to bind: it kept the rows where `f` is not NULL. And a single NOT is
  misread the same way as the argument of any call that takes one argument
  (`COUNT_IF`, `MAX`, `UPPER`, a one-argument `COALESCE`) and of a window
  function: `COUNT_IF(NOT f > TRUE)` counted none of the two rows it holds for,
  and `COUNT_IF(NOT status = 'done')` failed with `NOT takes a BOOLEAN, not
  STRING`. A call with two or more arguments, and every clause, read a single
  NOT as SQL does. Parentheses always reach the AST, so a NOT that is an
  operator's left operand was written without them, and `LogicalBuilder.leftSide`
  refuses it at each operator the binder binds (the comparisons, arithmetic,
  `||`, LIKE, IS NULL, IN, BETWEEN): `NOT before the left side of an operator
  is not supported here; write NOT (...) around what it negates`. AND and OR do
  not ask: `NOT a AND b` is `(NOT a) AND b`, as written.

Regressions, each failing at the base commit (`6a438e7`, with its two engine
files checked out under the new tests): in `sql_engine_test`, one per item
(`onlyOneStatementRuns`, `aTailClauseComesOnce`, `aWithDefinesANameOnce`,
`aJoinTakesTheConditionItsKindTakes`, `aNotNegatesWhatFollowsIt`);
`task_dag_test`'s `aSecondStatementFailsTheDag`; and `frontend_fuzz_test`'s
`noSilentDropRefusesWhatTheTableCannotList`, each of whose 23 refused
constructs bound at the base in every context it sits in. No existing test,
example job or generator wrote any of the refused shapes.

The fuzzer. Every entry of `SqlTextGen.BeyondTheTable` this plan recorded lost
its `knownPlan`, and the list grew from ten to 24 with the shapes the probing
found (a second statement that changes data, LIMIT on both sides of an OFFSET,
each repeated clause after a UNION's last arm, a CTE named twice in two cases,
INNER and FULL JOIN with no ON, CROSS JOIN and a comma with one, two ONs, the
nested join, `NOT NOT TRUE IS NULL`, `COUNT_IF(NOT FALSE > TRUE)`), 23 of them
required to be refused in every context they sit in, and `checkBeyond` now
requires the refusal to be clean, as `CleanRejection` judges one. "NOT NOT" left
`SyntaxEquivalence.KnownDefects`; its spelling shape keeps the parenthesized
spellings (`NOT NOT (a = x)`, `NOT (NOT a = x)`). A 20000-seed probe of both SQL
generators' bind rejections gave the same tally at the base commit as after
(plan 09's DECIMAL-beside-DOUBLE refusals alone: 21 for `QueryGen`, 8 of 40000
probes for `MetaQueryGen`), so the rendering comparison refused no generated
query.

The campaigns, with bazel shut down and three JVMs at a time, all green:
`frontend_fuzz_campaign`'s three properties at 20000 seeds in two blocks of
10000 (79 and 70 seconds), `mode_differential_fuzz_campaign` at 20000 seeds in
eight blocks of 2500 (168 to 207 seconds each), and
`metamorphic_fuzz_campaign`'s five properties at 5000 seeds in two blocks of
2500 (852 and 877 seconds). `bazel test //...` is green, and the jaffle-shop
job ran its 15 tasks to Succeeded with every validation passing.
