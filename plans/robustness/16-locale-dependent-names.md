# Plan 16: names, functions and case folding depend on the JVM's locale

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · Effort: half a day ·
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

## Outcome

Done as planned. 59 case conversions and 18 formats in 22 files of the
modules a job runs (core,
sql, read, write, job, temporal, cli, and the GUI's `JobSession` and
`DagLayout`) name `Locale.ROOT`, and the `f"..."` formats that build part file
names, template values, JSON escapes, CSV error messages and the CLI's task
lines are `formatLocal(Locale.ROOT, ...)`; the template's date patterns are
`ofPattern(p, Locale.ROOT)`.

`job/locale_independence_test` runs under tr-TR. Against the code before, six of
its seven tests failed: `UPPER('istanbul')` and `LOWER('Iowa')`, the
`{{ ISO_DATE }}` variable, the directory job (its task reading `Indexed` as
`indexed` failed the DAG), the view lookup, part file names under a Thai-numeral
format locale, and the scan, which named `Catalog`'s `toLowerCase()` first.
The scan reads every module jar's constant pools for `String.toLowerCase()`,
`toUpperCase()`, `String.format`, `formatted`, `StringOps.format` (what `f"..."`
compiles to), `DateTimeFormatter.ofPattern(p)` and `NumberFormat.getInstance()`.

Not changed: the JavaFX GUI's display code (pill labels, the SQL highlighter's
keyword match, the OS-name check), which no job runs; another session was
rewriting those files at the time.
