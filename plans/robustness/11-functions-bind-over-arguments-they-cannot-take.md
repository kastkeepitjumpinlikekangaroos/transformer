# Plan 11: functions and conditions bind over arguments they cannot take

> Status: **done** 2026-10-18 (see [Outcome](#outcome)) · Effort: 1-2 days ·
> Risk: medium (queries that failed while running fail at bind time instead;
> the strict side of both [Decisions](#decisions) changes queries that ran)

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
   t` in `VecFuncs.iff` with `StringVector cannot be cast to BooleanVector`. A
   NULL condition dies there too, `IF(NULL, 1, 2)` with `NullVectorImpl cannot
   be cast to BooleanVector`, though SQL's answer (and the row path's) is 2:
   [plan 10](10-error-messages-name-internals.md)'s campaign found it at seed
   54624, once plan 09's typing let that seed's `IF(NULL, DATE '2024-03-10',
   '2024-03-10')` bind; the cast to BOOLEAN either option of the decision below
   makes covers it. (`ConditionNotBoolean`)
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

## Outcome

The user's decisions, 2026-10-18, each the recommended option:

- **Every condition is a BOOLEAN.** WHERE, HAVING, ON, CASE WHEN, IF's first
  argument, COUNT_IF and each side of AND, OR and NOT take a BOOLEAN, or NULL,
  and anything else is a bind error naming the comparison or the
  `CAST(x AS BOOLEAN)` to write. Chosen over casting ON and IF as WHERE did,
  knowing it changes queries that run today: `WHERE i` over an INT,
  `WHERE 'true'`, `CASE WHEN i`, `NOT i`, `HAVING COUNT(*)` and `COUNT_IF(i)`
  are errors. No example job used any of them.
- **All three lenient text coercions are refused.** `UPPER`, `LOWER`, `TRIM`,
  `LENGTH` and `SUBSTRING`, `LIKE` and `ILIKE`, and `||` and `CONCAT` take a
  STRING, or NULL, and name `CAST(x AS STRING)` for anything else. Keeping `||`
  and `CONCAT` lenient, as Postgres does, was offered and declined.
- **ROUND's and TRUNC's edges**, found while probing the plan's cases, are
  fixed in the same change (below).

What landed:

- **Conditions.** `Analyzer.condition` binds every condition: a BOOLEAN as it
  is, NULL cast to one, anything else refused (`WHERE takes a BOOLEAN, not
  INT; compare it with a value, or CAST it to BOOLEAN for TRUE where it is not
  0`; for a string literal, `write TRUE or FALSE`). A join's ON condition was
  cast only when JSqlParser gave it as several expressions, so a single one
  reached `HashJoinExec.passesExtra` as it was, and IF's was never cast, so
  `VecFuncs.iff` cast a NULL's or a string's vector to a `BooleanVector`. The
  implicit cast every other condition took read a DATE or TIMESTAMP through
  `Casts`' fallback, its text compared with `"true"`, so `WHERE d` kept no
  rows: a wrong answer the plan did not list.
- **Argument kinds.** `Funcs.signature` replaces `returnType` and `takesText`:
  each case gives the arities, a `Param` per argument (`Number`,
  `Integer(role)`, `Text`, `AnyValue` for the flow functions) and the return
  type, and `bindScalarFunctionWithAggs` checks every argument through
  `Analyzer.checkArgument`, in the voice the plan asked for: `SQRT on STRING
  is not supported; CAST it to a number`, `AVG on DATE is not supported; a date
  is not a number`, `ROUND's scale must be an integer, not DOUBLE; CAST it to
  INT`, `LENGTH on INT is not supported; CAST it to STRING`. `ROUND(x, 1.5)`
  used to cut its scale with `intValue`, `RAND(1.5)` its seed, and `ROUND` took
  any number of arguments and ignored all but two; each is an error now. The
  aggregates that add their values up (`SUM`, `AVG`, `STDDEV`, `VARIANCE`,
  `COVAR_*`, `CORR`, with DISTINCT and as windows) check their arguments in
  `bindAgg` and `bindAnalytic` (`numberArg`).
- **NULL arguments.** A NULL scale, position, length or seed answers NULL on
  both paths, written in the query or in a column; `RAND(NULL)` had thrown the
  same `NullPointerException` as `ROUND`'s. `ABS` lost its `case _ => null`,
  through which `ABS` of a date was NULL.
- **BIGINT positions.** `Funcs.substring`, shared by both paths, reads a
  position and a length as longs: `SUBSTRING(s, 3000000000)` had wrapped to a
  negative INT and answered the whole string, and `SUBSTRING(s, 1, 4294967297)`
  to a length of 1.
- **ROUND and TRUNC at a double's edges.** Both paths compute them as
  `Funcs.round` and `Funcs.trunc`, over `Funcs.atScale`.
  `math.round` of the scaled value clamped to a long past 2^63, so
  `ROUND(1e10, 10)` was 922337203.6854776 and `ROUND` of an infinity 9.2e18,
  and it read NaN as 0; `math.pow(10, scale)` overflowed to an infinity at a
  scale of 400, which made `ROUND` 0 and `TRUNC` NaN, and underflowed to 0 at
  -400, which made both NaN. A value whose digits at the scale reach 2^52 now
  comes back as it is, a scale too negative gives 0, and NaN and the
  infinities are their own answer. Ordinary rounding is as it was: half up,
  toward positive infinity, so `ROUND(-2.5)` is -2.

Regressions, each failing at the base commit (checked in a worktree at
`3f1567e` with the new tests copied in): in `sql_engine_test`, one per item
(`anAggregateThatAddsUpTakesANumber`, `aMathFunctionTakesANumber`,
`aScalePositionLengthOrSeedIsAnInteger`, `aNullScalePositionLengthOrSeedIsNull`,
`aPositionOrLengthPastAnIntIsTheBigintItIs`, `everyConditionIsABoolean`,
`onlyTextIsReadAsText`, `roundAndTruncAnswerAtTheEdges`) and
`binaryIsNotReadAsText`'s new message; in `expr_batch_test`, both paths pinned
on a NULL scale, position and length, on BIGINT positions against a reference
computed in BigInt, and at the edges; and `frontend_fuzz_test`'s
`argumentsOfTheWrongTypeAreRefusedCleanly`. At the base commit
`fuzzCleanRejection` also failed on its default budget, at seed 27 (`STDDEV`
over a string column), once the defects were gone from the oracle. No existing
test leaned on a coercion this removes, and no example job's SQL did.

The fuzzer. `FunctionOverWrongType`, `NullFunctionArgument` and
`ConditionNotBoolean` are gone from `CleanRejection.Defect`, with their
recognizers and `BoundPlan.hasNonBooleanJoinCondition`. `SqlTextGen`'s typing
queries now put a column of any type in each place a condition sits and under
`||` and `LIKE`, and offer `RAND`. The NULL-scale exclusions are gone from
`QueryGen` and `ExprGen`: a scale, position or length is a small literal, a NULL
or an INT or BIGINT (a leaf in `QueryGen`, whose computed types are hints; any
subtree in `ExprGen`), and `||` and `CONCAT` join strings, which other types
reach through a CAST. A guard in each holds the re-enabled shape and its value:
`integerArgumentsReachNullAndTheEdges` (a NULL beside a value on 202 of 20000
seeds, past an edge on 38) and `nullScalesAndPositionsReachTheirFunctions` (a
NULL beside a value on 108 of 6000). A 20000-seed probe of both SQL generators'
bind rejections found only plan 09's DECIMAL-beside-DOUBLE refusals (21 for
`QueryGen`, 8 of 40000 probes for `MetaQueryGen`), none of this change's.

The campaigns, with bazel shut down and three JVMs at a time, all green:
`frontend_fuzz_campaign`'s three properties at 20000 seeds in two blocks of
10000 (84 and 70 seconds), `expr_parity_fuzz_campaign` at 100000 seeds (10
seconds), and `mode_differential_fuzz_campaign` at 20000 seeds in eight blocks
of 2500 (177 to 245 seconds each). `bazel test //...` is green, and the
jaffle-shop job ran its 15 tasks to Succeeded with every validation passing.
