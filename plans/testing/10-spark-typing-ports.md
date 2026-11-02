# Plan 10: Spark's decimal and type-coercion tests, with Spark's ANSI answers

> Status: **not started** · from the 2026-11-01 test survey, category 2 of 9
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
