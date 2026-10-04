# Plan 13: literals and quoted identifiers are read as written, not as SQL reads them

> Status: **open** · Effort: 1 day · Risk: medium (the quote escape changes the
> value of literals that run today; quoted identifiers need a decision)

## How this was found

[Plan 05](05-frontend-robustness-fuzzer.md)'s probing and its equivalent-spelling
property (`oracle/SyntaxEquivalence`), which runs two spellings of one query and
requires one answer: a spelling the binder reads wrongly answers differently
from its twin. The first item is also plan 01's admitted blind spot — "whether
an inert reason is true" — a second time: the audit lists the getter that holds
the right value as inert, with a reason that is backwards. Measured at `4b6d882`.

## Problem

1. **A doubled quote inside a string literal stays doubled.** `SELECT
   LENGTH('it''s')` is 5, not 4; `WHERE name = 'O''Brien'` never matches the
   value `O'Brien`. The binder reads `StringValue.getValue`, the text between the
   quotes as written; `getNotExcapedValue` is the value with each `''` made one
   quote, and `UnsupportedClauses` lists it as inert with the reason "the literal
   before quote unescaping", which is the other way round. `ESCAPE ''''` reads
   two characters and is refused. A wrong answer with no error; SQL's only way to
   write a quote in a string. (Known disagreement "a quote inside a literal".)
2. **A double-quoted identifier keeps its quotes.** `SELECT "a" FROM t` fails
   with `Unknown column '"a"'`, and `SELECT a AS "x y" FROM t` names its column
   `"x y"`, quotes included, which is the header a CSV output gets. Backquotes
   behave the same. (Known disagreement "quoted identifier".)
3. **An integer literal past BIGINT is a raw `NumberFormatException`** at bind
   time (carried over from plan 05): `SELECT 99999999999999999999 FROM t`, `LIMIT
   99999999999999999999`, and the smallest BIGINT, `-9223372036854775808`, which
   cannot be written at all (JSqlParser reads the sign apart, and the digits do
   not fit). (`CleanRejection.Defect.NumberLiteralOutOfRange`)
4. **A number literal past DOUBLE's range is silently `Infinity`** (`SELECT
   1e400` is `Infinity`, `1E-400` is `0.0`), and beside a DECIMAL it then fails
   while running: `WHERE dec > 1e400` with `Cannot cast Infinity to
   decimal(9,2)`, `COALESCE(dec, 1e400)` with a `NumberFormatException` from
   `BigDecimal`. (`NumberLiteralOutOfRange`)
5. **Two string literals side by side** are a literal and an alias: `SELECT 'a'
   'b' FROM t` returns `a` in a column named `'b'`, quotes included.
   (`SqlTextGen.BeyondTheTable`, "string literals side by side")

## Proposed fix

- Read `StringValue.getNotExcapedValue` wherever the binder reads a string
  literal's value (`LogicalBuilder`'s literal case and the ESCAPE character), and
  in `UnsupportedClauses` move `getNotExcapedValue` out of the inert list (the
  binder will read it) and list `getValue` as inert: "the literal as written,
  with each quote doubled".
- Unquote a quoted identifier wherever the binder resolves a column, a table or
  an alias, and name output columns without the quotes (see the decision on
  case).
- An integer literal past BIGINT, and a number literal whose double is infinite
  or rounds a non-zero to zero, is a bind error naming the literal (or a
  DECIMAL; see the decision).
- A string literal as an alias is refused.

## Decisions

- **Case in quoted identifiers.** Standard SQL makes `"A"` and `a` different
  names and the engine's unquoted names are case-insensitive today; treating a
  quoted name case-insensitively is the smaller change and keeps every query that
  runs today running.
- **A big integer literal**: refuse, or read it as a DECIMAL as Postgres does
  (`99999999999999999999` is a `numeric`), which the decimal arithmetic landed
  on 2026-10-04 can now carry.
- `'a' 'b'`: refuse, or concatenate as standard SQL does across a newline. The
  user's strictness preference says refuse.

## Verification

- A regression per item in `SqlEngineTest`, failing at HEAD: `LENGTH('it''s')`
  is 4 and `s = 'it''s'` matches the value `it's`; `SELECT "a" FROM t` reads `a`
  and `AS "x y"` names the column `x y`; the literal refusals.
- `unsupported_clauses_test` passes with `getNotExcapedValue` read.
- Delete "a quote inside a literal" and "quoted identifier" from
  `SyntaxEquivalence.KnownDefects` and `NumberLiteralOutOfRange` from
  `CleanRejection.Defect`; clear the `knownPlan` of "string literals side by
  side" in `SqlTextGen.BeyondTheTable`.

## Docs to update

`README.md` (literals and identifiers in the SQL section), `docs/gotchas.md`.
