# Plan 12: text the parser accepts and the binder never sees

> Status: **open** · Effort: 1 day · Risk: medium (each item makes a query that
> runs today an error)

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
