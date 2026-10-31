# Plan 16: names, functions and case folding depend on the JVM's locale

> Status: planned · found by the 2026-10-31 audit · Effort: half a day ·
> Risk: low (a mechanical change, one test target under a Turkish locale)

## How this was found

A grep of `src/main` for `toLowerCase` / `toUpperCase` without a `Locale`
found 68 calls, then a probe ran queries with `Locale.setDefault(tr-TR)`.
Measured at `b2c83fc`.

## Problem

`String.toLowerCase()` and `toUpperCase()` use the JVM's default locale. Under
a Turkish or Azerbaijani one, `I` lowers to a dotless i (U+0131) and `i` uppers
to a dotted I (U+0130), so every name the engine folds to compare stops matching
(below, `\u0131` and `\u0130` stand for the two):

| query (catalog view `Items`, columns `ID`, `title`) | under tr-TR |
|---|---|
| `SELECT id FROM items` | `View 'items' is not registered. Known views: [\u0131tems]` |
| `SELECT min(ID) AS m FROM ITEMS` | `Unknown function 'min(int)'` |
| `SELECT count(DISTINCT title), sin(0) FROM Items` | `Unknown function 'sin(int)'` |
| `SELECT CAST(ID AS int) FROM Items` | `Unsupported CAST target type: \u0130NT` |
| `SELECT UPPER(title), LOWER(title) FROM Items` | `\u0130STANBUL`, `\u0131owa` (`ISTANBUL`, `iowa` elsewhere) |

The same folding decides catalog and CTE names (`Catalog`, `LogicalBuilder`,
`TaskDag`, `DataJob`), column names (`Schema.indexOf`, `Analyzer`), function
and type names (`Funcs`, `LogicalBuilder.parseTypeName`), template variables
(`TemplateRenderer`), option values (`CsvOptions`, `ExecutionOptions`,
`ParquetWriter`'s codec) and formats. A job that runs on one machine fails on
another, or answers differently there: `UPPER` and `LOWER` are data.

## Fix

Every case conversion in `src/main` that compares, looks up or computes data
names `Locale.ROOT` (the GUI's display-only ones too, so the rule has no
exceptions). `UPPER` / `LOWER` in SQL are then the same on every machine.
`String.format` and the `f""` interpolator also read the default locale (digits
in some locales are not ASCII); the ones that build file names, template
values and JSON escapes name `Locale.ROOT` too.

A test target runs with `-Duser.language=tr -Duser.country=TR` and covers name
resolution, functions, CAST type names, `UPPER` / `LOWER`, template variables
and a directory job, so the rule cannot regress silently. A grep-based check in
the same test fails on any new locale-less conversion in `src/main`.

## Docs

README (`UPPER` / `LOWER` are locale-independent), gotchas (the pitfall),
testing.md (the new target), conventions.md (the rule).
