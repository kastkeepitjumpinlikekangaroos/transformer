# Plan 28: a malformed template expression is left in the text

> Status: planned · found by the 2026-10-31 audit · Effort: half a day ·
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
