# Plan 10: Spark's decimal and type-coercion tests, with Spark's ANSI answers

> Status: **done** 2026-11-02 (64b7fa9 W1, d1bf116 decision 2, d5ff49c W2,
> b01cee5 W3, fdf580d W4, 368c631 W5; see [Outcome](#outcome); the three
> decisions taken overnight as recommended, not yet confirmed by the user) ·
> from the 2026-11-01 test survey, category 2 of 9
> (answers from outside the engine, part 1) · Effort: 3-5 days, plus 1-2 for
> CAST to DECIMAL · Risk: low for the port; medium for decisions 1 and 2,
> which change what queries return · Depends on: 08 (every skip cites a
> decision id); pairs with 09, whose tables pin the engine's own cells where
> this plan checks them against Spark

## Why

On 2026-10-04 the user chose Spark's and SQL Server's decimal result types,
with overflow an error. `types/decimal.test` and hand cases check that choice;
no answer from Spark itself is in the repo.

Spark's `SQLQueryTestSuite` golden files (Apache-2.0) record each query's
result schema and output. The top-level `results/` are ANSI answers
(`SQLQueryTestSuite.scala:354` sets ANSI on for every test outside
`nonansi/`). Counted 2026-11-01 at `apache/spark@b981235`, under
`sql/core/src/test/resources/sql-tests/`:

- **`decimalArithmeticOperations.sql`**: 50 queries and 5 statements that make
  or fill tables.
  - 21 are errors under ANSI (`NUMERIC_VALUE_OUT_OF_RANGE`, `DIVIDE_BY_ZERO`,
    `REMAINDER_BY_ZERO`); none are under `nonansi/`.
  - 7 use `CAST(... AS DECIMAL(p,s))`, and 16 use the `BD` literal suffix.
- **`typeCoercion/native/`**: 18 files, 2,878 queries, 1,043 of them errors
  under ANSI. Most are type-pair matrices over constants (`SELECT cast(1 as
  T1) op cast(2 as T2) FROM t`) for comparisons, `IN`, `CASE`, `IF`, `UNION`,
  arithmetic, division, string promotion, window frames, `||` and booleans.
  This is plan 09's coercion matrix, answered by another engine.

Of the 2,928 queries:

- **881 run as written.**
- **866 more need only CAST to DECIMAL.** 704 of them are in
  `decimalPrecision.sql`, the decimal × every-type matrix.
- **1,181 need something the engine lacks:**
  - TINYINT (305), SMALLINT (307), INTERVAL (42)
  - CAST to BINARY and `X''` literals (306)
  - arrays and maps (29)
  - Spark-only functions and operators (`pmod`, `div`, `<=>`, `date()`,
    `string()`)
  - the literal suffixes `D`, `L`, `Y` and `S`

## A first run, and what it found

Each of the 855 runnable `typeCoercion/native` queries ran in its own
`transformer query` JVM over a one-row table `t`. The jar was built from
f309205 on 2026-11-01, with another session's uncommitted query shell, which
changes no typing. Eight ran at a time, and the run took 90 s. Each result was
compared with Spark's ANSI schema and output:

| Outcome | Queries |
|---|---|
| Same type and value | 225 |
| Refused by both | 301 |
| Refused here, answered by Spark | 279 |
| Same value, another type | 38 |
| Another type and value | 9 |
| Only a timestamp's printed form differs | 3 |
| Answered here, refused by Spark | 0 |

The 279 refused only here are each by a recorded decision:

| Reason | Queries |
|---|---|
| Spark-only functions | 61 |
| A STRING expression beside a number | 72 |
| Hex literals | 24 |
| Timestamp text cast to DATE | 18 |
| `'1'` cast to BOOLEAN | 14 |
| A constant window key | 13 |
| Text arithmetic, a STRING branch, a subquery in FROM | the rest |

Of the 47 with another type, 35 are already decided:

- **9 are division.** `/` keeps its operands' type, as in Postgres, so it is
  integer division for integers; Spark's `/` is always a DOUBLE.
- **26 read a string literal as the other operand's type,** where Spark reads
  it as BIGINT or DOUBLE.

Two findings remain:

- **An INT or BIGINT beside a FLOAT is a FLOAT here** (12 records). Spark's
  ANSI goldens type `CASE`, `IF` and `UNION` of the two as DOUBLE, and its
  analyzed plans compare an INT with a FLOAT as DOUBLE
  (`analyzer-results/typeCoercion/native/inConversion.sql.out:409`). This
  engine makes it a FLOAT in all three coercion families
  (`DataType.widerNumeric` in `core/DataType.scala`), and a FLOAT does not
  hold every INT. Each of these was run with the CLI jar:
  - `CAST(16777217 AS INT) = CAST(16777216 AS FLOAT)` is TRUE
  - so is `CAST(16777217 AS INT) IN (CAST(16777216 AS FLOAT))`
  - `COALESCE(CAST(NULL AS FLOAT), 2147483647)` is 2.1474836E9

  The README's "Values that become one result take a type that holds each of
  them" does not hold here (decision 2).
- **BIGINT counts as DECIMAL(19,0) here and as DECIMAL(20,0) in Spark.** This
  is computed, not run, since there is no CAST to DECIMAL. The engine's rule is
  `Decimals.ofIntegral` (`Ops.scala:458-462`), as the README says. In Spark,
  `cast(1 as bigint) + cast(1 as decimal(10, 0))` is `decimal(21,0)`
  (`results/typeCoercion/native/decimalPrecision.sql.out:125`), where this
  engine's rule gives DECIMAL(20,0). Every BIGINT × DECIMAL cell is one digit
  apart (decision 3).

Every other decimal formula matches Spark's. `Decimals.resultType`
(`Ops.scala:484-499`) and `bounded` (`:533-535`) give Spark's type for each
case checked by hand:

| Expression | Type |
|---|---|
| `a + b` over DECIMAL(38,18) | DECIMAL(38,17) |
| `a * 10` | DECIMAL(38,15) |
| `b / 10` | DECIMAL(38,18) |
| `cast(1 as int) / cast(1 as decimal(10, 0))` | DECIMAL(21,11) |
| `12345678912345.123456789123 / 0.000000012345678` | DECIMAL(38,9) |

## What exists now

- **The runner** (`src/test/scala/com/transformer/slt/`) takes exact column
  types, `statement error` with message text, and `skipif`/`onlyif`, under 13
  verification modes and the plan round trip. Its `Database` creates
  `DECIMAL(p,s)` and `BINARY` columns (`Database.scala:388`) and runs `INSERT`
  and `COPY`.
- **The port precedent is `src/test/slt/postgres/`.** Its header names the
  source, the commit and how much is ported; each record names its source line;
  each rewrite is named; each skip gives a reason; `COPYRIGHT` sits beside it.
- **There is no CAST to DECIMAL.** `parseTypeName` refuses it (`Unsupported
  CAST target type: DECIMAL (10, 2)`), as README's Limitations says.
- **The binder's own decimal casts read a number exactly, for comparisons**
  (`Ops.scala:312-328`): an INT takes the target's scale with no digit check,
  and a FLOAT or DOUBLE becomes the decimal it prints as, unfitted. `PlanSql`
  prints such a cast as its operand, since the binder inserts it again
  (`PlanSql.scala:150`).
- **There is no converter.** `tools/` holds only `parquet_peek`, and the build
  has `rules_java`, `rules_jvm_external` and `rules_scala`, but no Python.

## What to build

### W1. `CAST(x AS DECIMAL(p,s))` (decision 1, recommended)

- **Syntax.** `parseTypeName` reads `DECIMAL(p,s)` and `NUMERIC(p,s)`, with
  1 <= p <= 38 and 0 <= s <= p. A bare `DECIMAL` is refused, naming the two
  numbers to write.
- **What the cast does.** It is a user's cast, a node of its own or a flag on
  `CastExpr`, on both evaluation paths. Any number rounds half up to the scale
  and is an error past the precision (`Decimals.fit`). Text is read by CSV's
  decimal grammar, then fitted. NaN and the infinities are errors, a BOOLEAN is
  1 or 0 as for every number type, and DATE, TIMESTAMP and BINARY are refused.
  The binder's exact casts stay as they are.
- **Its error is already clean**: `Decimal overflow: ... does not fit
  decimal(p,s)` is in `CleanRejection.DataErrors` (`CleanRejection.scala:181`).
  Check `ExprParity`'s owned set (the new-runtime-error checklist).
- **The rest follows.** `PlanSql` prints the user's cast, which the binder will
  not insert again; the round-trip property and the `reprinted` mode hold it.
  The generators' CAST targets gain DECIMAL. Plan 09's cast matrix regenerates
  with a DECIMAL column, and its diff is the review. README: "Types in CAST"
  and Limitations.

### W2. The converter

- **Where it lives.** `//src/test/scala/com/transformer/slt:spark_import` is a
  `bazel run` target beside `:corpus`. It reads `.sql.out` files and writes
  scripts under `src/test/slt/spark/`. The scripts are vendored, so no test
  needs it.
- **What a record holds.** Spark's SQL, each rewrite named in a comment.
  Column types from `-- !query schema` (`int`, `bigint`, `float`, `double`,
  `decimal(p,s)`, `string`, `boolean`, `date` and `timestamp` map to the
  engine's types of those names). Values from `-- !query output`. `rowsort`
  unless the query ends in ORDER BY, since Spark sorts any result its plan does
  not (`SQLQueryTestHelper.isSemanticallySorted`).
- **Rewrites that cannot change what a record means.**
  - `CREATE TEMPORARY VIEW t AS SELECT 1` becomes a one-row table, and `USING
    parquet` is dropped.
  - `cast('2017-12-11 09:30:00' as date)` becomes `DATE '2017-12-11'`. Spark's
    cast drops the time, and this engine reads only an ISO date as a DATE, by
    decision. Without the rewrite the record would test that cast instead of
    its subject.
  - After W1, a Spark decimal literal becomes `CAST('...' AS DECIMAL(p,s))` of
    Spark's literal type. That covers a number with a point and no exponent,
    and the `BD` suffix: in Spark `10.3` is `decimal(3,1)`, while here it is a
    DOUBLE unless it stands beside a DECIMAL. A number with an exponent is a
    DOUBLE in both engines and stays as written.
- **Printing.** Spark's `2017-12-12 09:30:00` becomes slt's
  `2017-12-12T09:30:00Z`, and an empty string `(empty)`. A record that moves a
  value between DATE and TIMESTAMP is reviewed by hand, after confirming the
  suite's time zone (Spark's test base may pin `America/Los_Angeles`).
- **Errors.** An ANSI error becomes `statement error`, with Spark's error
  class in a comment. The engine's own message is pinned only after review. A
  record both engines refuse for different reasons is a finding, not an
  agreement.
- **Left out,** and counted in each script's header ("N of the file's M
  statements are here"): any record needing a type, literal or function the
  engine lacks.
- **Skipped** (`skipif transformer`, with the decision's id from plan 08's
  ledger): a record the engine answers otherwise by decision. That covers `/`
  keeping its operands' type; division and remainder by zero being NULL; a string
  literal read as the other operand's type; refused text coercions; text cast
  to BOOLEAN or DATE by CSV's grammar; a refused constant window key; a DECIMAL
  beside a non-literal DOUBLE in arithmetic and branches; and the outcomes of
  decisions 2 and 3.
- **Attribution.** `src/test/slt/spark/` holds the Apache License 2.0 text and
  Spark's NOTICE with the commit, as `postgres/COPYRIGHT` does. Each script
  names its source file and says every answer is Spark's, never the engine's.
  `src/test/slt/BUILD.bazel`'s comment names the directory.

### W3. `spark/decimal_arithmetic.test`

The first half of `decimalArithmeticOperations.sql`: division and remainder
by zero over `t` (skipped by decision; `pmod` left out); `decimals_test`, two
DECIMAL(38,18) columns, under `+ - * /` and with an integer constant; the
constant expressions; the `UNION ALL` of decimal casts. The second half
(`allowPrecisionLoss=false`) is left out: past 38 digits this engine always
gives way on scale, which is Spark's default (`Decimals.bounded`). Without W1,
only the `decimals_test` queries and `2.35E10 * 1.0` port.

### W4. `spark/type_coercion/*.test`

One script per `typeCoercion/native` file with a runnable record, 13 in all.
`arrayJoin`, `mapconcat`, `mapZipWith` and `elt` have none, and
`dateTimeOperations`' one record goes into `binaryComparison`'s script. With
W1, `decimalPrecision.sql`'s 704 records join.

### W5. Checks

A second reader, not the converter, re-derives each expected block from the
`.sql.out` and compares cell by cell, as the float8 port's checker did. Every
record passes under every verification mode and under `--config=batch1`,
`sharded`, `zone` and `hashzero`. Under plan 08's stale-skip mode, every
skipped record still diverges.

## Decisions for the user

1. **`CAST(x AS DECIMAL(p,s))`.**
   - **(a) Add it with W1's semantics (recommended).** It doubles the port (866
     records, `decimalPrecision.sql` among them, the file about the rules the
     user adopted). It lets Spark's decimal literals keep their types, so the
     constant queries test the result-type rules. It lifts a README limitation
     ("a query cannot choose a decimal result's type"), and it helps plan 11's
     Postgres ports, which write `::numeric(p,s)`.
   - **(b) Don't add it.** Port the 881 records that run as written.
   - **(c) Add it only to the test `Database`, as typed columns.** That tests
     SQL a user cannot write.
2. **An INT or BIGINT beside a FLOAT.**
   - **(a) A DOUBLE in comparisons, branches and arithmetic (recommended).**
     It is Spark ANSI's type for comparisons and branches, and it holds every
     INT exactly. `16777217 = CAST(16777216 AS FLOAT)` becomes false, the
     README's claim becomes true for INT, and the three families keep one
     widening rule. It changes queries that run today: `int_col * float_col`
     becomes a DOUBLE, and so does an output column holding it.
   - **(b) Keep FLOAT.** Correct the README's claim and skip Spark's 12
     records under a new decision id.
   - **(c) Refuse the mix without a CAST.** The strictest option, though Spark
     accepts the mix (and Postgres too, to confirm in plan 11).

   A BIGINT beside a DOUBLE loses digits past 2^53 in every engine. Either way,
   the README needs a qualifier there.
3. **BIGINT's decimal.**
   - **(a) Keep DECIMAL(19,0) (recommended).** It is SQL Server's, it holds
     every BIGINT, its types are a digit tighter, and the README says so.
     Spark's BIGINT × DECIMAL records are skipped under its id.
   - **(b) Spark's DECIMAL(20,0).** Exact parity with Spark, one digit wider.

**Taken overnight as recommended, not yet confirmed by the user** (2026-11-02):
decision 1 (a), `CAST(x AS DECIMAL(p,s))` with W1's semantics; decision 2
(a), an INT or BIGINT beside a FLOAT is a DOUBLE in comparisons, branches and
arithmetic; decision 3 (a), BIGINT's decimal stays DECIMAL(19,0).

## Decisions already made

- **Spark's answers are the expected values, never the engine's,** as in the
  Postgres ports. A record the engine answers otherwise is skipped under a
  decision id, or held back for the user when no decision covers it.
- **No new dependency.** The converter is Scala beside `Corpus`. Claude's
  call: the build has no Python toolchain.
- **Neither `nonansi/` nor the `allowPrecisionLoss=false` half is ported.**
  The engine's overflow is an error, and its scale gives way past 38 digits
  (2026-10-04).
- **Spark's `postgreSQL/` ports (33 files, 1,322 queries) are no workstream
  here**: plan 11 ports Postgres's originals with Postgres's answers, Postgres
  being the first reference. After plan 11, one is worth a look: Spark's
  `numeric.sql` (97 queries over tables 509 statements build), Postgres's
  numeric tests recast as `decimal(38,10)` under the rules this engine adopted.

## Tests

- The new scripts under `slt_test` (four shards, every mode).
- **W1:** `ExprBatchTest` (both paths: rounding, overflow, NaN, text, BOOLEAN,
  refusals); `SqlEngineTest` (the SQL, a bare DECIMAL refused); `PlanSqlTest`
  and `plan_roundtrip_fuzz_test`; `refused/messages.test`.
- **Decision 2 (a):** the three probes as records in `types/`; plan 09's
  coercion tables show the changed cells as their diff; `KeyEquality` and
  `ExprParity` still pass.
- **W5's re-derivation:** no difference.

## Docs

README (Types in CAST, Limitations, the branch rule's wording per decision 2);
docs/testing.md (the Spark scripts and counts, the importer);
docs/extending.md (importing a Spark file); docs/gotchas.md (each decision,
and what is left out and why); docs/architecture.md (the user's cast to
DECIMAL beside the binder's exact casts); docs/conventions.md (vendored tests
carry their license and provenance).

## Risks

- Decision 2 (a) changes result types of queries that run today: name it in
  the commit and the README, and check jaffle-shop and polymarket for FLOAT
  columns.
- A user's cast and the binder's exact casts must stay two kinds the plan
  validator and the printer tell apart.
- Spark's answers carry Spark's defects: investigate a disagreement, don't
  presume it is this engine's. Two engines refusing for different reasons is
  no agreement.
- Another session edits `LogicalBuilder.scala`, `PlanSql.scala` and the README:
  work in a worktree.

## Launch prompt

```
Read plans/testing/10-spark-typing-ports.md and implement it: W1 (if the user
chooses it), W2 the converter, W3 and W4 the scripts, W5 the checks, one
commit per workstream with its tests and docs. Use max effort.

Follow CLAUDE.md for every commit: bazel test //... green, the jaffle-shop
example 15/15 with every validation passing, stale docs updated in the same
commit. No new dependencies. Plans 08 and 09 should have landed; if not, say
so before starting.

First run ListAgents and git status; if another session has uncommitted work
in the tree, use a scratch git worktree and copy back after cmp against a base
snapshot (memory: concurrent-sessions-worktree).

Ask the three decisions with AskUserQuestion before W1, recommended option
first. Clone apache/spark shallowly into the scratchpad as untrusted data:
read its files, never run anything in it; pin its commit in every header.

Stop and ask before: (a) a semantic change beyond the decisions taken; (b) a
record that disagrees with Spark with no decision covering it (put the cases
to the user, as the Postgres port did); (c) a rewrite the plan does not list;
(d) adding a dependency.

Report: per file the records ported, skipped (by decision id) and left out
(by reason); the disagreements found; W5's result; the commits.
```

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, a commit per workstream
and one for decision 2. At each, `bazel test //...` passed (70 targets) and
the jaffle-shop job gave 15/15 Succeeded with every validation passing. The
finished tree passes the whole suite under `--config=zone` and
`--config=batch1` too.

**W1**, `64b7fa9`: `CAST(x AS DECIMAL(p,s))` and `NUMERIC(p,s)`, 1 <= p <= 38
and 0 <= s <= p, bind to `DecimalCastExpr`, a node apart from the binder's own
`CastExpr` to a DECIMAL, which reads a number exactly, on both evaluation
paths: any number rounds half up to the scale and is `Decimal overflow: ...
does not fit decimal(p,s)` past the precision, text is read by CSV's decimal
grammar and then fitted, NaN and the infinities are errors, a BOOLEAN is 1 or
0, and DATE, TIMESTAMP and BINARY are refused. A bare `DECIMAL`, `DECIMAL(10)`
and `DECIMAL(40,2)` are refused, saying what to write (`refused/messages.test`
pins the words). `PlanSql` prints the written cast as itself and the binder's
as its operand, and `PlanValidator` holds a written one to a type a query can
write. `ExprGen`, `QueryGen` and `SqlTextGen` draw the cast, and
`writtenDecimalCastsRoundAndOverflow` holds its rounding and overflow on both
paths (over 100000 seeds: the shape on 1043, a rounding on 35, an overflow on
84). It settles plan 09's finding 12 (the refusal named `DECIMAL (10, 2)`),
and README's limitation that a query cannot choose a decimal result's type is
gone.

**Decision 2**, `d1bf116`: an INT or BIGINT beside a FLOAT is a DOUBLE in
comparisons, branches and arithmetic (`DataType.widerNumeric`), which settles
plan 09's finding 2: `CAST(16777217 AS INT) = CAST(16777216 AS FLOAT)` is
false and `COALESCE(CAST(NULL AS FLOAT), 2147483647)` is 2.147483647E9
(`types/float_equality.test`). `int_col * float_col`, and a FLOAT column times
an integer literal, are DOUBLEs now, and so is an output column holding one.
jaffle-shop has no FLOAT column. polymarket's parquet files have many (prices,
sizes, spreads), but its SQL meets them only with 0 and 1 in comparisons, which
a FLOAT and a DOUBLE compare alike, and with FLOATs and DOUBLEs in results;
read in its SQL, not run.

**The tables' changed cells**, each reviewed: `casts.txt` (900 to 1003 lines)
gained the DECIMAL(10,2) column's values for its 15 refusals: a number fitted
(`DOUBLE 2.7 -> DECIMAL(10,2) 2.70`, `-0.0 -> 0.00`, `1.0E-300 -> 0.00`), past
the precision a run error (`Decimal overflow: 2147483647 does not fit
decimal(10,2)`, a DOUBLE named as it prints, `1.0E300`), NaN and the
infinities `Cannot cast NaN to decimal(10,2)`, text that is no number or too
long `Cannot cast '...' to decimal(10,2)` (at bind time for a text literal),
and DATE, TIMESTAMP and BINARY bind errors. `comparisons.txt`,
`branches.txt` and `arithmetic.txt` each changed exactly the INT, BIGINT and
integer-literal x FLOAT rows, both orders, from FLOAT to DOUBLE (6 lines each),
and `functions-math.txt` `MOD(x, 2)` and `MOD(2, x)` over a FLOAT, from FLOAT to
DOUBLE (22 lines; `MOD(2.7f, 2)` is 0.7000000476837158, the FLOAT's binary
value less 2).

**W2**, `d5ff49c`: `SparkImport` (`bazel run //src/test/scala/com/transformer/slt:spark_import`)
writes `src/test/slt/spark/` from Spark's input and golden files (fetched as a
shallow sparse clone of `apache/spark` at b981235, read as data);
`spark/LICENSE` and `spark/NOTICE` hold Spark's Apache License 2.0 text and
notice; nine rules joined the ledger, each decided before and pinned:
`refused-text-comparison`, `refused-text-branch`,
`refused-date-beside-timestamp`, `refused-decimal-beside-float`,
`refused-implicit-text`, `refused-implicit-number`, `division-keeps-type`,
`text-literal-typed` and `bigint-decimal` (decision 3). **W3**, `b01cee5`:
`spark/decimal_arithmetic.test`. **W4**, `fdf580d`: twelve scripts under
`spark/type_coercion/`. **W5**, `368c631`: the converter also runs every record
in the `zone` configuration's locale and time zone, which held out three
records (finding 1), and the checks below.

**Per file**: statements, those in its script, skipped by rule, left out by
need, and held out.

| source | statements | here | skipped, by rule | left out, by need | held |
|---|---|---|---|---|---|
| decimalArithmeticOperations | 55 | 26 | `div-zero-null` 2, `double-literal` 1 | allowPrecisionLoss=false half 26, `pmod` 2, `div` 1 | - |
| binaryComparison | 265 | 121 | `refused-text-comparison` 72 | BINARY 48, `date()` and `timestamp()` 48, SMALLINT 24, TINYINT 24 | - |
| booleanEquality | 97 | 37 | `refused-text-comparison` 4 | `<=>` 36, BINARY 8, SMALLINT 8, TINYINT 8 | - |
| caseWhenCoercion | 145 | 82 | `refused-text-branch` 16, `refused-decimal-beside-float` 4, `refused-date-beside-timestamp` 2, `bigint-decimal` 2 | TINYINT 23, SMALLINT 21, BINARY 19 | - |
| decimalPrecision | 1145 | 673 | `refused-decimal-beside-float` 80, `bigint-decimal` 27, `refused-text-comparison` 24, `refused-text-arithmetic` 20 | BINARY 104, TINYINT 104, SMALLINT 104, `<=>` 64, `pmod` 64 | 32 |
| division | 145 | 82 | `refused-text-arithmetic` 10, `division-keeps-type` 5, `refused-decimal-beside-float` 4, `bigint-decimal` 2 | TINYINT 23, SMALLINT 21, BINARY 19 | - |
| ifCoercion | 145 | 82 | `refused-text-branch` 16, `refused-decimal-beside-float` 4, `refused-date-beside-timestamp` 2, `bigint-decimal` 2 | TINYINT 23, SMALLINT 21, BINARY 19 | - |
| implicitTypeCasts | 47 | 27 | `refused-implicit-text` 12, `refused-text-comparison` 8, `text-literal-typed` 3, `division-keeps-type` 1 | Spark's date and time functions 13, SMALLINT 2, TIME 2 | 3 |
| inConversion | 289 | 156 | `refused-text-comparison` 31, `refused-date-beside-timestamp` 4, `bool-text` 2 | TINYINT 46, SMALLINT 42, BINARY 38 | 7 |
| promoteStrings | 316 | 198 | `refused-text-comparison` 22, `text-literal-typed` 16, `bool-text` 12, `refused-implicit-number` 7, `division-keeps-type` 6, `refused-text-arithmetic` 2 | BINARY 26, TINYINT 26, SMALLINT 26, `pmod`, `skewness` and `kurtosis` 19, `<=>` 17, text minus a date or timestamp (an INTERVAL) 4 | - |
| stringCastAndExpressions | 32 | 8 | - | Spark's date, JSON and hash functions 17, arrays, structs and maps 3, BINARY, TINYINT, SMALLINT and a bare DECIMAL 1 each | - |
| widenSetOperationTypes | 145 | 82 | `refused-text-branch` 16, `refused-decimal-beside-float` 4, `refused-date-beside-timestamp` 2, `bigint-decimal` 2 | TINYINT 23, SMALLINT 21, BINARY 19 | - |
| windowFrameCoercion | 25 | 16 | `window-constant-key` 15 | BINARY, SMALLINT and TINYINT 2 each | 3 |
| arrayJoin, concat, dateTimeOperations, elt, mapconcat, mapZipWith | 102 | 0 | - | INTERVAL 33, views over arrays and maps 30, Spark's functions 20, BINARY, TINYINT and SMALLINT 9, SETs 4, literal suffixes 4, TIME 1 | - |

In all, 2,953 statements: 1,590 in thirteen scripts (619 queries answering
as Spark does, type and value; 492 errors both engines raise for one reason,
each pinned with the engine's words up to its advice; 28 building tables; 464
skipped by 14 rules, the 98 under a rule that is no refusal each followed by
its twin), 45 held out, and the other 1,318 left out: 1,317 by what they need,
and dateTimeOperations' view, which none of its records the engine runs reads.
`slt_test` runs the thirteen scripts in about 6.5 s.

**W5's re-derivation**: a second reader, a Python script outside the repo
written apart from the converter (it splits a golden file as Spark's own
reader does, maps each record to its input line, undoes the converter's
rewrites checking each decimal literal's type, and re-derives the columns, the
sort and every value), compared all 1,575 of Spark's records in the scripts,
1,141 answer cells: no difference. Planted, it caught a changed value, a
changed column type and a wrong literal type. Every script passes under every
verification mode and under `--config=batch1`, `sharded`, `zone` and
`hashzero`, and every skipped record still diverges as its rule says (plan
08's check, part of `slt_test`).

**Fuzz**, one block each for what changed (the plan's default seeds pass in
`bazel test //...`): expression parity 200000 seeds, frontend clean rejection
20000, mode-differential 2500 and key equality, window frames and exact sums
400, plan round trip 2000, metamorphic 2500 (and 500, to check the budget
arrived); all green.

**Findings held for the user** (no engine semantics changed for any):

1. **A lowercase `like` does not parse under a Turkish default locale.**
   JSqlParser 5.2 upper-cases `like`, `ilike`, `not like`, `similar to` and
   `rlike` with the JVM's default locale to look up `LikeExpression.KeyWord`,
   and `LİKE` is no constant, so the query fails with `SQL parse error: No
   enum constant net.sf.jsqlparser...LikeExpression.KeyWord.LİKE`, a message
   that also leaks the parser's class. Upper-case `LIKE` parses in every
   locale. Reproduced with the parser alone at 5.2, the version the base
   commit pins (`sql/parse` is unchanged since it); the `zone` configuration
   found it, as no script of this repo wrote `like` in lower case. Three
   records of implicitTypeCasts.sql are held out. Recommendation: have
   `SqlParser` hand JSqlParser those keywords in upper case, or set the
   parse's locale another way the user prefers, and keep a lowercase `like`
   in a script `zone` runs.
2. **A record both engines refuse, for different reasons** (42), in each the
   engine refusing by a decided rule before it reaches what Spark refuses: 32
   timestamps decimalPrecision.sql writes with the operator for their dashes
   (`cast('2017*12*11 09:30:00.0' as timestamp) * ...`), text Spark's analyzer
   never reads, refusing the operator's types first; 7 `cast('1' as boolean)
   IN (...)` beside a number or a date, where Spark refuses the list's types
   and the engine `'1'` first (`bool-text`); 3 RANGE frames over a STRING, a
   BOOLEAN or a TIMESTAMP, where Spark refuses the frame and the engine
   `PARTITION BY 1` first (`window-constant-key`). No answer differs.
   Recommendation: leave them out.
3. **Plan 09's finding 11 again**: `'1' + cast(1 as decimal(10, 0))` is
   refused as `Cannot compare DECIMAL(10,0) with STRING`, a comparison's words
   for arithmetic, in 18 of Spark's records (`+ - * / %` beside a string
   literal in promoteStrings.sql and implicitTypeCasts.sql), skipped as
   `refused-text-comparison`, whose rule names it. Recommendation as there:
   arithmetic's own words.
4. **The plan's counts were a first run's**: dateTimeOperations.sql and
   concat.sql have no record the engine can run (each needs INTERVAL, BINARY,
   TINYINT, SMALLINT, TIME, `encode()` or `string()`), so twelve
   type_coercion scripts, not thirteen, and dateTimeOperations adds nothing
   to binaryComparison's; and Spark's `<=>`, which the first run's 881 seem to
   have counted, is left out as an operator the engine lacks.

**Decisions, taken overnight as recommended, not yet confirmed by the user**:
1 (a), 2 (a) and 3 (a), recorded under the decisions above.

**Claude's calls within them** (revisit if the user objects):

- The written cast is a node of its own, `DecimalCastExpr`, rather than a flag
  on `CastExpr`, so the compiler made every pass that walks an expression
  name it, and the two kinds cannot be confused by a rewrite that rebuilds a
  `CastExpr`.
- A number written in the query under the cast is the decimal it is written
  as (`decimalConstant`), as beside a DECIMAL, so `CAST(1.0000000000000000001
  AS DECIMAL(20,19))` keeps its last digit; one no DECIMAL holds stays the
  DOUBLE it binds as alone. A string literal under it is read when the query
  is bound and kept as its text in the plan, so the plan prints back as written.
- Text past the type is `Cannot cast '...' to decimal(p,s)`, an
  `IllegalArgumentException` as text past an INT is, since a literal's is
  raised at bind time where an `ArithmeticException` is no clean refusal; a
  number past it is `Decimal overflow`, its FLOAT or DOUBLE named as it
  prints. NaN and the infinities take the binder's cast's words, `Cannot cast
  NaN to decimal(p,s)`. `DECIMAL(10)` is refused like a bare `DECIMAL`.
- Decision 2 reaches an integer literal too, which is an INT, so a FLOAT
  column times 2 is a DOUBLE.
- The converter runs each record on the engine to sort it: an answer like
  Spark's is kept, a difference a ledger rule explains is skipped with its
  twin, and anything else is held. A rule other than a refusal reads the SQL
  and both answers, so a record is put down to it only where its construct is
  there and the engine answers as it says; a `refused-` rule explains any
  record the engine refuses in its words, an error Spark raises for another
  reason too (`cast(1 as string) IN (cast(ts))`, which Spark refuses on the
  text '1').
- Text minus a date or a timestamp, Spark's temporal difference, is left out
  as needing INTERVAL (4 records), as are records reading a view left out.
- An agreed error pins the engine's words up to its first semicolon, the
  reason before the advice; the header says the words are the engine's.
- A timestamp Spark prints in the suite's zone is written as the instant of
  that local time in UTC: no record moves a time between zones, so every
  value keeps (and the `zone` configuration passes).
- `refused-text-comparison`'s rule takes in a string literal beside a DECIMAL
  (a DECIMAL reads none), refused in the same words; `refused-text-branch` is
  its own rule, decided on another day in other words.
