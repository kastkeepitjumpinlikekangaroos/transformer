# Plan 25: dates and timestamps past the storable range wrap

> Status: planned · found by the 2026-10-31 audit · Effort: half a day ·
> Risk: low

## Problem

A DATE is stored as an `Int` of days since 1970 and a TIMESTAMP as a `Long` of
microseconds, but text is parsed into `LocalDate` / `Instant`, whose ranges are
wider, and narrowed without a check (`toEpochDay.toInt`,
`getEpochSecond * 1000000L`). Measured at `b2c83fc`:

| query | answer |
|---|---|
| `CAST('+999999999-12-31' AS DATE)` | `+466210-05-14` |
| `CAST('+100000000-01-01' AS TIMESTAMP)` | `+41257-07-29T18:47:46.673664Z` |

## Fix

Every place a date or a timestamp enters the engine checks it fits its type and
fails naming the value otherwise: CAST from text and the DATE / TIMESTAMP
literals (`Casts`), CSV reading (`CsvValues`), the vectors' boxed setters, and
the parquet reader's conversions (a MILLIS or INT96 value whose microseconds
overflow a long). The range is the type's: about 5.8 million years either side
of 1970 for DATE, about 292,000 for TIMESTAMP.

## Tests

`SqlEngineTest`, `csv_reader_test` and the parquet external-format test.

## Docs

README (DATE / TIMESTAMP), testing.md.
