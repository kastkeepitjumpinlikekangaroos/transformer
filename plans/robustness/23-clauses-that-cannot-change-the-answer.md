# Plan 23: sort, group and partition keys that cannot change the answer, and patterns with a dangling escape

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · Effort: half a day ·
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

## Outcome

Done as planned. `LogicalBuilder.isConstant` decides what reads no column (no
column reference and no `RAND()`; `RAND(seed)` is one value for one seed), and
`refuseConstantKey` refuses such a key at every site: ORDER BY without and with
DISTINCT, after a UNION, GROUP BY, and a window's PARTITION BY and ORDER BY,
naming the key, a column's spelling and, where positions are keys, a
position's. An ordinal, and a name of an output column, read a column whatever
it holds, so they still sort and group. `LikeExpr.toRegex` refuses a pattern
that ends in its escape character and `Funcs.substring` a negative length;
`constantValue` computes a constant pattern or length when the query binds,
so written as one either is a bind error, and computed it fails on the row.

`aKeyThatReadsNoColumnIsRefused` and `aDanglingEscapeOrANegativeLengthIsAnError`
failed against the code before on their first query. The frontend fuzzer's
`BeyondTheTable` refuses each shape in every context, and its clean-rejection
oracle takes the two run-time errors as being about a value. Two generators
had to follow: `QueryGen` takes the `ABS` of a SUBSTRING length it reads from a
column, whose values reach -2, and `ExprParity.checkTotal` lets a tree fail on
its own negative length. `nullTypedColumnsSpillEndToEnd` groups by a NULL-typed
key that reads a column, where it grouped by `NULL`.
