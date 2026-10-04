# Plan 11: functions and conditions bind over arguments they cannot take

> Status: **open** · Effort: 1-2 days · Risk: medium (queries that fail while
> running today would fail at bind time instead; the lenient text coercions in
> [Decisions](#decisions) change queries that run today)

## How this was found

Plan 05 recorded the first half by hand (`SUM` / `AVG` over a non-number, `ROUND`
over a STRING, `ABS` over a DATE). Its clean-rejection property then found the
rest, once `SqlTextGen`'s typing queries offered the binder functions,
aggregates, CASTs and conditions over columns of all ten types: `QueryGen` is
type-correct by construction, so no corpus had ever asked the binder to refuse a
type. The property's rule is that rejection is total: what the engine cannot run
must fail at bind time, not part-way through the rows inside a worker (the shape
that made a failing job task cascade every task below it to `Skipped`, plans
bugfixes 03). Each item is a known defect of `oracle/CleanRejection`, with a
repro. Measured at `4b6d882` against `t(a INT, b INT, s STRING)` and `v(d DATE,
ts TIMESTAMP, bin BINARY, dec DECIMAL(9,2))`.

## Problem

1. **The numeric aggregates take any type.** `SUM`, `AVG`, `STDDEV`,
   `VARIANCE`, `COVAR_*`, `CORR` (and `SUM` / `AVG(DISTINCT)`) over a STRING,
   DATE, TIMESTAMP, BINARY or BOOLEAN column bind, and fail inside the aggregate's
   worker with a `ClassCastException` (wrapped, plan 10). `SELECT SUM(s) FROM t`,
   `SELECT AVG(ts) FROM v`, `SELECT SUM(bin) FROM v`. (`FunctionOverWrongType`)
2. **The scalar math functions check arity only.** `Funcs.returnType` matches on
   the argument count, so `ROUND`, `FLOOR`, `CEIL`, `TRUNC`, `SQRT`, `EXP`, `LN`,
   `LOG`, `POWER`, the trigonometric functions, `SIGN`, `DEGREES` and `RADIANS`
   bind over a string or a date and throw `ClassCastException: class
   java.lang.String cannot be cast to class java.lang.Number`. `ABS` over a DATE
   or a STRING binds and answers **NULL for every row**: a wrong answer, not an
   error (`SELECT ABS(s) FROM t`, `SELECT ABS(d) FROM v`). (`FunctionOverWrongType`)
3. **`SUBSTRING` takes a non-number position or length**: `SUBSTRING(s, 'x')`
   throws `ClassCastException` while running. (`FunctionOverWrongType`)
4. **A NULL scale, position or length throws.** `ROUND(a, NULL)`, `ROUND(a, b)`
   over a NULL `b`, `SUBSTRING(s, NULL)`: `NullPointerException: Cannot invoke
   "java.lang.Number.intValue()" because the return value of
   "com.transformer.core.ColumnVector.getBoxed(int)" is null`. SQL's answer is
   NULL. `QueryGen` ("`ROUND`/`SUBSTRING` scales are non-null integer literals")
   and `ExprGen` ("excludes ... NULL `ROUND`/`SUBSTRING` scale args") exclude the
   shape for totality, which is a generator gate in front of a defect: the
   exclusions go with the fix. (`NullFunctionArgument`)
5. **A condition that is not a predicate binds as it is.** WHERE, HAVING, CASE
   WHEN, `COUNT_IF` and every AND / OR / NOT operand get the implicit cast to
   BOOLEAN (`Analyzer.implicitCast`); a join's ON condition and IF's first
   argument do not. `SELECT t.a FROM t JOIN u ON t.a` dies in
   `HashJoinExec.passesExtra` with `ClassCastException: class java.lang.Integer
   cannot be cast to class java.lang.Boolean`, and `SELECT IF(s, 'y', 'n') FROM
   t` in `VecFuncs.iff` with `StringVector cannot be cast to BooleanVector`.
   (`ConditionNotBoolean`)
6. **The lenient text coercions**, named to the user on 2026-10-03 as follow-ups
   of the typing work and not fuzzed: `UPPER`, `LOWER`, `TRIM` and `LENGTH` over a
   number read its text (`LENGTH(a)` is 1); `s LIKE a` and `a LIKE 'x'` compare as
   text; `WHERE a` over an INT is truthiness and `WHERE 'true'` keeps every row;
   `a + 1 || 'x'` concatenates a number.

## Proposed fix

- Argument types in `Funcs`, next to the arity: a number for the math functions,
  an integer for a scale, position or length; a NULL literal there makes the
  answer NULL, as a NULL column value does. `Funcs.takesText` is the model.
- The numeric aggregates check their argument in `LogicalBuilder.bindAgg`, where
  `COUNT_IF` already casts its own to BOOLEAN.
- A join condition and IF's condition take `Analyzer.implicitCast(_,
  BooleanType)`, like every other condition; or, if the user prefers, a bind
  error for anything that is not already a predicate (see the decision).
- Each refusal names the function, the argument and its type, in the voice of
  `UPPER on BINARY is not supported; a byte array is not text`.

## Decisions

- **Implicit cast or refusal for a non-boolean condition.** WHERE's truthiness
  is today's behaviour, so the consistent fix casts; the strict one refuses every
  condition that is not a predicate, WHERE included, which changes queries that
  run today.
- **The lenient text coercions** (item 6): each is a coercion the user's
  strictness preference would refuse at bind time with the CAST named; each
  changes queries that run today, so each is the user's call.

## Verification

- A regression per item in `SqlEngineTest`, failing at HEAD: the refusals at
  bind time, `ABS` over a DATE refused rather than NULL, `ROUND(a, NULL)` NULL.
- Delete `FunctionOverWrongType`, `NullFunctionArgument` and
  `ConditionNotBoolean` from `CleanRejection.Defect`; remove the NULL-scale
  exclusions from `QueryGen` and `ExprGen`, and run `frontend_fuzz_campaign`,
  `expr_parity_fuzz_campaign` and `mode_differential_fuzz_campaign` at 20000
  seeds.

## Docs to update

`README.md` (the function list and its argument types), `docs/gotchas.md`,
`docs/testing.md` (the generator exclusions removed).
