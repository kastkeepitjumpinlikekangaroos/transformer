# Plan 23: sort, group and partition keys that cannot change the answer, and patterns with a dangling escape

> Status: planned · found by the 2026-10-31 audit · Effort: half a day ·
> Risk: low (new refusals of text that does nothing)

## How this was found

Plan 01's rule, "a clause that parses must change the outcome or error", applied
to keys rather than clauses. Measured at `b2c83fc`.

## Problem

| query | answer |
|---|---|
| `SELECT a, b FROM t ORDER BY 'b'` | not sorted (the key is the text `b`) |
| `SELECT a FROM t ORDER BY +1`, `ORDER BY -1`, `ORDER BY 1.5`, `ORDER BY NULL`, `ORDER BY TRUE`, `ORDER BY 1 + 0` | not sorted |
| `SELECT COUNT(*) FROM t GROUP BY 1.5`, `GROUP BY NULL`, `GROUP BY CURRENT_DATE` | one group |
| `ROW_NUMBER() OVER (PARTITION BY 'a' ORDER BY 'b')` | one partition, arbitrary order |
| `s LIKE '%\'` (pattern ends in its escape) | FALSE on every row |
| `'ab' LIKE 'a_' ESCAPE '_'` | FALSE |
| `SUBSTRING('abc', 2, -1)` | `''` |

`ORDER BY 'b'` is what a user writes who meant the column `"b"` (a MySQL habit);
Postgres refuses a non-integer constant in ORDER BY and GROUP BY. A pattern that
ends in its escape escapes nothing; Postgres refuses it, and a negative
SUBSTRING length.

## Fix

A key of ORDER BY, GROUP BY, a window's PARTITION BY or ORDER BY that reads no
column and is not `RAND()` (a constant: a literal, a CAST of one, arithmetic on
constants, a clock keyword) is refused, naming the key and pointing at a
quoted name or an ordinal. A bare integer stays an ordinal. `LIKE` / `ILIKE`
with a constant pattern ending in its escape character is a bind error; a
non-constant one fails on the row. A negative constant SUBSTRING length is a
bind error, a negative one at run time an error naming it.

## Tests

`SqlEngineTest` regressions; the frontend fuzzer's `BeyondTheTable` (text the
binder must refuse) gains the constant-key shapes in every context.

## Docs

README (ORDER BY / GROUP BY, LIKE, SUBSTRING), gotchas, testing.md.
