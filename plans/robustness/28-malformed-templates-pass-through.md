# Plan 28: a malformed template expression is left in the text

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · Effort: half a day ·
> Risk: low

## Problem

`TemplateRenderer` substitutes what its pattern matches and leaves every other
`{{ ... }}` as it is. Measured at `b2c83fc`:

| template | rendered |
|---|---|
| `'{{ today - 1d }}'` | `'{{ today - 1d }}'` |
| `'{{ today * 2 }}'`, `'{{ today \| ds }}'`, `'{{}}'`, `'{{ today'` | unchanged |
| `'{{ today 5 }}'` | `'20260106'` (read as + 5, "defensively") |
| `'{{ today + 99999999999999999999 }}'` | `NumberFormatException` |

A job whose task filtered `WHERE CAST(day AS STRING) = '{{ today - 1d }}'`
Succeeded with 0 rows: the filter compared against the template's own text. In
a path, the same typo becomes a directory named `{{ today - 1d }}`.

## Fix

After substitution no `{{` or `}}` may remain: a template expression the
grammar does not match fails, naming it and the grammar (`{{ name }}`, `{{ name
+ N }}`, `{{ name - N }}`). `{{ today 5 }}` is refused. An offset no long holds,
or one that moves the time past what `java.time` represents, is the same clean
error. (Airflow renders its own `{{ }}` before transformer sees the text, so a
remaining pair is always a mistake.)

## Tests

`template_renderer_test`; a `data_job_test` case for a task and an output path.

## Docs

README (temporal templating), testing.md.

## Outcome

Done as planned, with one narrowing. The pattern takes an offset only as a
sign and a number together, so `{{ today 5 }}` and `{{ today + }}` match
nothing, and after substitution any `{{` left is an error naming the
expression (up to its `}}`, or the end of the text) and the grammar. A `}}`
alone is not refused, as the plan's "no `{{` or `}}` may remain" would: nested
JSON in a SQL string literal ends in `}}`, and never holds a `{{`. An offset
past a BIGINT and one that moves the time past what java.time represents are
clean errors. SQL and output paths render when the job's DAG is built, so a
malformed template fails the job before any task runs.

`anExpressionTheGrammarDoesNotReadIsAnError` and
`aMalformedTemplateFailsTheJobBeforeItRuns` failed against the code before.
