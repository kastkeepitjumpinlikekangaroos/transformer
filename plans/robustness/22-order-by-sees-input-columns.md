# Plan 22: ORDER BY resolves a name against the input columns first

> Status: planned · found by the 2026-10-31 audit · decided by the user:
> output names first, conflicts refused · Effort: 1 day · Risk: medium (changes
> the order of queries whose alias shadows an input column)

## How this was found

README says `ORDER BY s` "does not" reach an aliased expression and points at
the ordinal. The audit probed what the named form does instead, at `b2c83fc`,
over `t(a, b, s)` = (1,30,p) (2,20,q) (3,10,r) (3,5,r).

## Problem

| query | answer | standard SQL (Postgres, Spark) |
|---|---|---|
| `SELECT -b AS b FROM t ORDER BY b` | -5, -10, -20, -30 | -30, -20, -10, -5 |
| `SELECT b AS a FROM t ORDER BY a` | 30, 20, 10, 5 | 5, 10, 20, 30 |
| `SELECT a AS b, b AS a FROM t ORDER BY a` | sorted by input a | sorted by output a |
| `SELECT a, b AS a FROM t ORDER BY a` | sorted by input a | error: ORDER BY a is ambiguous |
| `SELECT s, COUNT(*) AS n FROM t GROUP BY s ORDER BY n` | error: unknown column 'n' | sorted by the count |
| `SELECT a + b AS total FROM t ORDER BY total` | error: unknown column 'total' | sorted by the sum |
| `SELECT s, SUM(b) AS a FROM t GROUP BY s ORDER BY a` | error: 'a' must appear in GROUP BY | sorted by the sum |

The first four are silent wrong answers; the rest refuse the most common
ORDER BY there is. `DISTINCT` and `UNION` already sort by the output.

## Decision

- A bare name in ORDER BY is an output column when exactly one output column
  has that name; an input column otherwise. Two output columns with the name
  is an error naming them.
- In an expression (`ORDER BY b + 0`), where Postgres reads input columns and
  Spark output aliases, a name that is an output alias of something other than
  the input column of that name is refused as ambiguous, naming both readings;
  a name that is only one of the two means that one.
- Positions (`ORDER BY 2`) are unchanged.

## Fix

`LogicalBuilder`'s ORDER BY binding (`buildSelect` / `buildAggregation`'s sort
sites): resolve a bare `Column` against the projection's output names first,
binding the projected expression (so it works in grouped queries, where the
output is an aggregate), and check expressions for the conflict.

## Tests

`SqlEngineTest` regressions for each row above, grouped and ungrouped, with
DISTINCT and UNION. `SyntaxEquivalence` gains `ORDER BY <alias>` and `ORDER BY
<ordinal>` as one spelling.

## Docs

README (ORDER BY), gotchas ("A named ORDER BY key binds against the scope at
that point in the plan" is rewritten), testing.md.
