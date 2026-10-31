# Plan 13: literals and quoted identifiers are read as written, not as SQL reads them

> Status: **done** 2026-10-30 · decided by the user, all four the
> recommendation · Effort: 1 day · Risk: medium (the quote escape changes the
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

## Outcome

The user chose the recommendation on each question (2026-10-30), the fourth
one found while probing:

1. **Quoted names are case-insensitive**, as every bare name is (Spark and
   DuckDB read them so): `"a"`, `"A"` and `a` are one column, and quotes only
   let a name hold a space, a symbol or a keyword.
2. **An integer past BIGINT is the DECIMAL of its digits**, as Postgres reads
   one and as CSV inference already typed such values; past 38 digits it is
   refused, and `-9223372036854775808` is the smallest BIGINT.
3. **A string literal where an alias goes is refused**: `'a' 'b'`, `AS 'x y'`
   (which ran, naming the column with its quotes) and `FROM t AS 'x'`.
4. **A number beside a DECIMAL keeps its written digits exactly**: it was read
   through a DOUBLE, so `k * 1.0000000000000000001` was `k * 1.0`; now 2.50 is
   a DECIMAL(3,2), as in standard SQL, Postgres and Spark. Chosen knowing
   `price * 1.10` changes from DECIMAL(12,3) to DECIMAL(13,4).

What landed:

- **A string literal is its value.** The binder reads
  `StringValue.getNotExcapedValue`, for a literal and an `ESCAPE`, and the
  audit lists `getValue` as inert, "the literal as written, each quote in it
  doubled".
- **A name is what it names.** `SqlParser.unquote` is the one rule, applied
  wherever the binder matches a name: a column and its qualifier, a table and
  its alias, a column alias, a CTE and its column list, `t.*`. A `*` expanded
  into its columns writes each name with `SqlParser.quote`, so a field whose
  name holds quotes reads back as itself. An empty `""` is refused, a quoted
  `"true"` is a name, not the literal, and an `@` inside quotes is part of a
  table's name, not a database link. `TaskDag` reads a job's table references
  through the same rule.
- **Numbers.** `LongValue.getBigIntegerValue` replaces `getValue`, which the
  audit now lists as inert (it throws past BIGINT); `LogicalBuilder`'s
  `IntegerLiteral` folds the one minus that makes `-9223372036854775808` a
  BIGINT. A LIMIT, OFFSET, FETCH or TOP past BIGINT, an ordinal out of range
  and a LAG or LEAD offset or frame bound past INT are refused by name. A
  DOUBLE literal that is infinite, or zero although its digits are not, is
  refused; `Decimals.shownNumber` keeps a ten-thousand-digit literal out of
  the message.
- **A number beside a DECIMAL** is read from its digits
  (`LogicalBuilder.besideDecimals`, over the DoubleValue's own text) in
  arithmetic, `MOD`, a comparison, `BETWEEN`, `IN`, a CASE switch, `NULLIF`,
  the values of a CASE, COALESCE, IF, GREATEST and LEAST, and each column of a
  UNION's arms. A comparison already read a DOUBLE as the decimal it prints
  as, so only a number with more digits than a DOUBLE keeps answered wrongly
  there: `k = 0.120000000000000000001` held for 0.12.

Found on the way and fixed with it:

- **A GROUP BY key matched a SELECT item that differed in a literal's case**,
  a silent wrong answer: the binder matched expressions by their text
  lower-cased whole, so `SELECT s || 'a' ... GROUP BY s || 'A'` returned
  `s || 'A'`. `LogicalBuilder.canonicalText` lower-cases only what is no
  literal and reads each name unquoted, so the query is the ungrouped-column
  error it should be, and `SELECT "b" + 1 ... GROUP BY b + 1` matches. The
  same text names a SELECT item with no alias, so `SELECT 'ABC'` is a column
  named `'ABC'` rather than `'abc'`.
- **A job refused a CTE spelled other than its references.** `TaskDag`
  extracts each task's tables with JSqlParser's `TablesNamesFinder`, which
  keeps quotes and leaves out a CTE only where a reference is spelled exactly
  as the CTE, so `WITH Q AS (...) SELECT * FROM q` failed the whole job with
  "references unknown view 'q'" although the task binds. `TaskDag` takes out
  the CTE names itself.
- **`MOD(k, 1 - 0.95)` was a bind error** where `k % (1 - 0.95)` ran: `MOD`'s
  arguments did not meet as `%`'s operands do.

The fuzzer's records are gone, the proof the fixes landed:
`SyntaxEquivalence.KnownDefects` is empty, `CleanRejection.Defect` has lost
`NumberLiteralOutOfRange` (and its recognizer, with it, the bound plan it
read), and `BeyondTheTable` requires string literals side by side refused in
every context, beside two new entries for a string as a column and as a table
alias. The quoted-identifier shape has seven spellings now (double quotes,
backquotes, another case, quoted qualifiers, a quoted table alias, a quoted
CTE and column alias), and the quote shape is observable, since its pair
answered one constant row. The eight regressions in `SqlEngineTest` and
`TaskDagTest` each fail at the commit before.

Verified: `bazel test //...` 53/53; `frontend_fuzz_campaign` at 90000 seeds of
each property (three blocks of 30000) and the three DAG scheduler campaigns at
5000 seeds each, all green; jaffle-shop 15/15 with every validation passing.
