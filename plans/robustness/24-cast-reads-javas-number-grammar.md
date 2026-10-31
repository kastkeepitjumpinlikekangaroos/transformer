# Plan 24: CAST reads text with Java's number grammar

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · Effort: half a day ·
> Risk: low-medium (text that cast before may not) · Follows the user's CSV
> decision (plain decimals only, 2026-10-14) and plan 13's literal rule

## How this was found

`docs/gotchas.md` records "SQL's CAST reads numbers with Java's grammar and CSV
with a plain one". The audit measured the difference at `b2c83fc`.

## Problem

| text | CAST to | answer |
|---|---|---|
| `' 5'`, `'5 '` | DOUBLE | 5.0 (but INT: an error) |
| `'5d'`, `'5f'` | DOUBLE, FLOAT | 5.0 |
| `'0x1p3'` | DOUBLE | 8.0 |
| `'\u0661\u0662'` (Arabic-Indic digits one, two) | INT, BIGINT | 12 |
| `'1e400'`, `'1e40'` | DOUBLE, FLOAT | Infinity |
| `'1e-400'` | DOUBLE | 0.0 |
| `d = ' 1e20'` (a literal read as the other side's type) | DOUBLE | matches 1e20 |

`Casts.parseNumber` calls `toInt` / `toDouble` / `new BigDecimal`, so a CAST,
and every string literal read as a number at bind time, accepts what the JDK
does: surrounding whitespace for floats only, type suffixes, hexadecimal
floats, any Unicode digit, and a value no DOUBLE holds as an infinity or a zero
(plan 13 made the same number written as a literal an error).

## Fix

One number grammar for text, CSV's (`CsvValues`): an optional sign and ASCII
digits for INT and BIGINT; for FLOAT and DOUBLE also a point and an exponent,
plus `NaN`, `Infinity`, `-Infinity`; for DECIMAL a plain decimal. A value its
type cannot hold is an error naming it (`'1e400'`, `'1e40'` for FLOAT), as is a
nonzero value that rounds to zero. Both read through one helper so they cannot
drift. CAST to BOOLEAN is unchanged (true / false in any case).

## Tests

`ExprBatchTest` / `SqlEngineTest` regressions for each row; the CSV fuzz target
gains a property that `CAST(s AS T)` succeeds exactly when CSV reads `s` as a
`T`, with the same value.

## Docs

README (CAST), gotchas (the Java-grammar entry), testing.md.

## Outcome

Done as planned. `core/NumberText` holds the grammar (an optional sign and
ASCII digits; a plain decimal, or `NaN`, `Infinity` and `-Infinity`) and reads
each type through it, refusing a float past its type's range or a nonzero one
that would read as 0. `CsvValues` and `Casts.parseNumber` both call it, so the
CSV reader now refuses `1e400` too, and inference reads a number no DOUBLE
holds as text. Its errors are the ones each reader gave before: a CSV field
names its file, line and column, and a CAST `Cannot cast '<text>' to <type>`.

`castReadsANumberAsCsvDoes` covers the table above (a CAST of each text, and
the literal beside a DOUBLE column), `aNumberTheTypeCannotHoldIsNoValueOfIt`
the reader, and `csv_fuzz_test`'s `fuzzCastReadsTextAsCsvDoes` holds `Casts.cast`
and `CsvValues.parseInto` to one answer over text that tells the grammars
apart. Against the code before each failed, the property at seed 0 (`0x1p-5`
a FLOAT to the CAST and nothing to CSV); the inference property's own grammar
moved with the reader's, and failed at seed 18 against it.
