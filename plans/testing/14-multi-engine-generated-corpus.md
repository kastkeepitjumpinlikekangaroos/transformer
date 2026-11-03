# Plan 14: a generated corpus whose answers Postgres and DuckDB agree on

> Status: **done** 2026-11-02 (febca22 W1 and W2, 98e672e W5, a6c67ac W3 and
> W6; see [Outcome](#outcome); every open decision taken overnight as
> recommended, not yet confirmed by the user) · from the 2026-11-01 test survey, category 2 of 9
> (answers from outside the engine, part 5) · Effort: 4-6 days · Risk: low for
> the engine (test data only); medium for the tool (normalization decides what
> counts as agreement) · Depends on: [08](08-skips-cite-decisions.md) (the
> decision ids a by-design difference cites); gains from
> [15](15-generator-gates.md), whose wider generators it reuses

## Why

Every fuzz property compares the engine with itself, so a wrong answer that is
wrong the same way in every mode passes them all (plans/testing/04, "Why").
Answers from outside the engine reach the engine as data: SQLite's corpus
slices, and the Postgres ports of plans/testing/05. Both are fixed sets of
queries written by hand. SQLite's corpus dates from 2008. It predates window
functions and CTEs, and SQLite has no DECIMAL, DATE or TIMESTAMP type, so
its 5.3 million records, which found no wrong answer here, say nothing about
most of what this engine adds. The Postgres ports cover window frames,
numerics, CASE, booleans and floats, about 500 records.

The third audit (memory note `sqlite-differential-audit`) ran generated
queries through the engine and SQLite side by side and found what the
self-agreeing fuzzers had not (the GROUP BY key refusals). Its harness was a
throwaway: a probe jar and a Python generator in a scratch directory, gone
with the session. This plan makes the method permanent without putting an
engine on the test classpath. An offline tool generates queries over what
SQLite's corpus never reaches. It runs each one in Postgres and in DuckDB and
keeps the records where both agree, once their outputs are normalized, and
where the engine's documented semantics apply. It writes those records as
sqllogictest scripts with exact column types, and the existing runner then
checks them forever, under all fourteen executions and every whole-suite
configuration. This is how SQLite's own corpus was made: five engines agreed
on each answer.

## What exists now

- **Generators** (`fuzz/`): `QueryGen` (one table) and `MetaQueryGen` (joins of
  every kind, windows, CTEs, UNION, a top-n shape ordered by every column) are
  seeded and type- and scope-correct by construction; `MetaQueryGen` leaves out
  `RAND()` and the clock; `DataGen` draws all ten types. They render the
  engine's dialect (`SqlRender`). The repo holds no `.py` or `.sh` file, and
  `tools/` only `parquet_peek`.
- **The runner** (`slt/`): exact column types, `rowsort`/`nosort`,
  `hash-threshold`, `skipif`/`onlyif`; `Database` does the DDL and INSERTs the
  engine lacks; `Values.exact` prints a DOUBLE with `Double.toString` and a
  DECIMAL with `toPlainString`. The scripts hold 6,043 query and statement
  records, and `slt_test` takes about 41 s on four shards.
- **The engine's surface** (README): joins, GROUP BY / HAVING, ROWS and RANGE
  frames with numeric offsets, `ROW_NUMBER`/`RANK`/`DENSE_RANK`/`LAG`/`LEAD`,
  aggregates and their DISTINCT forms, non-recursive CTEs, UNION, DISTINCT,
  ORDER BY / LIMIT / OFFSET, about 40 scalar functions. No date or time
  function exists (`EXTRACT` and `INTERVAL` are refused) and no CAST to
  DECIMAL, so DATE and TIMESTAMP are reached through comparison, BETWEEN,
  ordering, grouping, joins, MIN/MAX and casts to and from text.
- `psql`, `duckdb` and Python's `duckdb` are not installed here (2026-11-01).

## What to build

### W1. A portable renderer for the generators' queries and data

The generators' AST printed in the dialect the three engines share, per engine
where spellings differ, and nothing outside the intersection generated. Type
names per engine in DDL and CAST (a FLOAT is `REAL` in Postgres, a DOUBLE
`DOUBLE PRECISION`, a DECIMAL `NUMERIC(p,s)`, a STRING `TEXT`, a BINARY
`BYTEA`); `IF` printed as a CASE, `COUNT_IF(p)` as `SUM(CASE WHEN p THEN 1
ELSE 0 END)` for Postgres, a function one engine lacks (Postgres's `LOG2`)
never drawn. Every ORDER BY key, in a query and in a window, states its NULL
placement: the engine puts NULLs first for ASC, Postgres and DuckDB last.
Every LIKE writes its `ESCAPE`, since Postgres escapes with a backslash by
default. DuckDB runs with `SET integer_division = true`, so `INT / INT`
truncates as in Postgres and the engine; Postgres runs in a cluster made with
`--locale=C --encoding=UTF8`, `TimeZone = 'UTC'`. Done when every generated
query binds in all three engines or is counted as refused by the one that
refuses it, per shape: a high rate means the renderer is wrong.

### W2. The tool (decision 1)

`slt/AgreedCorpus.scala`, a `testonly` `scala_binary` (it reuses the fuzz
generators), never part of `bazel test`. For a seed range it generates data
and queries by W3's quotas, loads the data into a throwaway Postgres database
and a DuckDB file, runs each query through `psql -X -A --csv` and `duckdb
-csv` as subprocesses from `PATH` (no dependency: CLAUDE.md allows no embedded
database, even in a tool), normalizes both answers (W4), runs the query
through the engine in process (`slt.Runner`, baseline), and sorts each record
into one of:

- **kept**: both references agree, and so does the engine;
- **by design**: both agree, and the engine differs by a decision in plan 08's
  ledger; vendored `skipif transformer` with the id (decision 4);
- **engine finding**: both agree and the engine differs with no decision; a
  report with the seed and a shrunk repro, not vendored until decided;
- **engine refusal** of a query both answer: by design if the README lists it,
  else a finding;
- **references disagree**: dropped, counted per feature, samples reported;
- **both error**: vendored as `statement error` if the engine refuses cleanly
  too, as an overflow does.

It writes `src/test/slt/agreed/<feature>.test`, each header giving the seeds,
the tool's commit, both references' exact versions and the normalization rules
in force. Expected values are printed by `Values.exact` from the references'
values read into the engine's types, so a DOUBLE prints as the runner prints
it, with no second implementation of Java's float printing. Done when two runs
from a clean checkout write byte-identical scripts.

### W3. The first corpus, by feature

The quotas count records kept after agreement, not records generated:

| Feature | Records kept |
|---|---|
| Window frames, ROWS and RANGE, numeric offsets, peers, `LAG`/`LEAD` with defaults | 600 |
| DECIMAL arithmetic, comparison, SUM / AVG / MIN / MAX, ROUND / TRUNC over decimals | 500 |
| Outer joins (LEFT, RIGHT, FULL on equalities), with NULL keys and unmatched rows | 400 |
| String functions (`LENGTH`, `UPPER`, `LOWER`, `TRIM`, `SUBSTRING`, `CONCAT`, `||`, `LIKE`) | 400 |
| DATE and TIMESTAMP comparison, BETWEEN, ordering, grouping, joins, casts to and from text | 300 |
| CTEs, UNION / UNION ALL, DISTINCT, COUNT(DISTINCT) | 400 |
| ORDER BY / LIMIT / OFFSET, top-n over every type | 400 |

That makes about 3,000 records (decision 2). Results of more than eight values
are hashed (`hash-threshold 8`), as SQLite's corpus does, which keeps the
corpus near 1 MB. The tables are small (5 to 40 rows) and are made by
`DataGen` with its NULL densities, so the 14 executions of each record stay
cheap.

### W4. Normalization (decision 3)

- **NULL, BOOLEAN, integers**: by value (`t`/`f` and `true`/`false` are
  booleans; `SUM(INT)` is a BIGINT in Postgres and a HUGEINT in DuckDB). The
  column type written is the engine's; plans 09 and 10 pin types separately.
- **DECIMAL**: by value (`compareTo`), printed at the engine's result scale
  (Spark's rules), kept only when that is the references' exact value or, for
  a quotient, the exact quotient rounded half away from zero, the engine's
  ROUND rule. Postgres's `numeric` is exact; DuckDB divides decimals in
  DOUBLE, so for a quotient its agreement is checked to 15 significant digits
  and the digits come from Postgres.
- **DOUBLE**: exact when both references give the same shortest value, else at
  15 significant digits written as the runner's `T` (plans/testing/05's
  precedent), else dropped. **FLOAT** only in comparisons, ordering and
  projections: the engines widen a REAL differently in arithmetic.
- **Text**: exact. Both references compare in code point order (Postgres's C
  collation); the engine orders by UTF-16 code unit (the third audit's
  decision), so data mixing U+E000-U+FFFF with characters past U+FFFF is kept
  to a small labelled by-design quota. `UPPER` and `LOWER` over ASCII only:
  the engine's Java case mapping (U+00DF to `SS`), Postgres's C ctype (ASCII
  alone) and DuckDB's all differ.
- **DATE, TIMESTAMP, NaN, the infinities, -0.0**: read as values and printed by
  `Values.exact`; Postgres's `timestamp` without time zone in a UTC session is
  DuckDB's `TIMESTAMP`. All three sort NaN above every number and call `-0.0`
  equal to `0.0`.
- **Row order**: `rowsort` unless the query orders totally (`nosort`); a window
  whose answer depends on row order (ROWS frames, `ROW_NUMBER`, `LAG`, `LEAD`)
  orders by a unique key.
- **Known by-design inputs are not generated** (the engine's own scripts cover
  them): division or `%` by a possible zero (NULL here, an error in Postgres,
  DuckDB's answer by version and operator); a CAST of a fraction to an integer
  (truncated here, rounded there); `LN`/`LOG10`/`SQRT` outside their domain
  (NULL here); a DOUBLE too small to hold (0 here, an error in Postgres);
  `ROUND` of a DOUBLE (half to even in Postgres, so the references disagree).

### W5. Triage of the first corpus

Every engine finding gets a minimized repro and a Findings row, and is fixed in
its own commit, with a decision where a semantic choice is involved, before its
records are vendored. The references' disagreements are summarized as places
the engine's documentation should take a position on.

### W6. A guard against a corpus that shrinks silently

A test (`slt:agreed_corpus_test`, or a `Corpus` check run in `slt_test`) counts
the vendored records per feature, from a comment line each script carries, and
fails if one falls below its quota. This follows the fuzzers' coverage guards
(memory note `fuzzer-generator-gates`): a regeneration that silently drops a
feature is what those guards exist for.

### Setup, once, for whoever regenerates

```bash
brew install duckdb postgresql@18      # or a venv with `pip install duckdb`
initdb -D <scratch>/pg --locale=C --encoding=UTF8 -U postgres
pg_ctl -D <scratch>/pg -o "-p 54329 -k <scratch>" -l <scratch>/pg.log start
bazel run //src/test/scala/com/transformer/slt:agreed_corpus -- \
    --seeds 0:20000 --pg-port 54329 --pg-socket <scratch> --out src/test/slt/agreed
pg_ctl -D <scratch>/pg stop
```

Regeneration is deliberate, never in CI. A new seed range is written as new
records. A script is regenerated in place only to re-triage after a decision
changes, and its diff then shows every record the decision moved. A
different major version of either reference means a full re-triage, noted in
the headers.

## Decisions for the user

1. **What generates the queries.**
   - (a) **(recommended)** The fuzz generators, through W1's renderer, in a
     `testonly` Scala tool running `psql` and `duckdb` as subprocesses: one
     generator with its coverage guards, expected values printed by the
     runner's own `Values.exact`, no new language and no dependency.
   - (b) A Python generator and driver under `tools/`, the third audit's
     method: independent of the engine's typing assumptions, but a second
     generator to keep in step, and the repo's first Python.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
2. **Size.**
   - (a) **(recommended)** About 3,000 records near 1 MB, half again the
     records the scripts hold, measured against `slt_test`'s time before
     landing (expected to add well under half its 41 s).
   - (b) About 10,000, most of them in a `fuzz`-tagged slt target outside the
     default suite.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
3. **What agreement means for a DOUBLE.**
   - (a) **(recommended)** Exact when both references give the same shortest
     value, else 15 significant digits as `T`, else dropped.
   - (b) SQLite's `R` (three decimals) everywhere. Simpler, but it loses every
     digit of a large or small magnitude.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
4. **A record both references answer one way and the engine another, by a
   documented decision.**
   - (a) **(recommended)** Vendored with `skipif transformer` and the decision
     id, so plan 08's stale-skip check shows when a rule changes, and the
     record shows how far the rule reaches.
   - (b) Kept out at generation by a gate: a smaller corpus that records
     nothing of the rule.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
5. **The references.** (a) **(recommended)** Postgres and DuckDB: Postgres is
   the reference most decisions cite, DuckDB independent code, and their
   disagreements visible; (b) SQLite as a third vote, whose loose typing (no
   DECIMAL, integer `%`, case-insensitive LIKE) would drop most decimal, date
   and string records.

   **(a), taken overnight as recommended, not yet confirmed by the user.**

## Decisions already made

- No reference database on the test classpath; answers from outside are data
  (`plans/testing/README.md`, "Design decisions (locked)"). CLAUDE.md allows no
  embedded database, so the references' JDBC drivers are out even in a tool;
  their command-line clients are not.
- The engine's semantics decide triage, not the references: integer and
  DECIMAL overflow are errors, DECIMAL follows Spark's result types, division
  by zero is NULL, a CAST to an integer truncates, text is never coerced, one
  strict rule types every branch, NaN = NaN and -0.0 = 0.0, ORDER BY reads
  output names first, ROUND is exact and half away from zero (memory notes
  `decimal-arithmetic-semantics`, `branch-typing-decisions`,
  `argument-type-decisions`, `audit-2026-10-31-decisions`, `audit-3-decisions`,
  `plans-as-sql-round-decisions`).
- An expected error must be a clean one (plans/testing/04 decision 7), and a
  new run-time error joins `CleanRejection.DataErrors` and `ExprParity`'s owned
  set in the same commit (memory note `new-runtime-error-checklist`).

## Tests

`slt_test` runs `src/test/slt/agreed/` under every verification mode and
whole-suite configuration, as it does every script; W6's guard; the tool is
checked by running it twice (W2); each engine fix W5 lands brings its own
regression, in the script and in the unit test of the code it touches.

## Docs

docs/testing.md (the agreed corpus: what it is, how it was made, quotas, the
guard, the regeneration recipe); docs/extending.md (adding a quota or a
normalization rule); docs/gotchas.md (where the references disagree with each
other, and the by-design differences, each pointing at its decision); README
only where W5's findings change user-visible behaviour.

## Risks

- **Normalization decides what agreement is.** A rule too loose turns a real
  difference into agreement; each rule is a named function in the tool, listed
  in every script's header, and changing one regenerates under review.
- **The generators' typing comes from the engine**, so a shape the engine
  wrongly refuses is never generated; W2 counts the engine's refusals of
  queries both references answer, which shows them.
- **Version drift**: versions are pinned in the headers; a major upgrade of
  either reference is a full re-triage.
- **Run time**: fourteen executions a record; W3's small tables and hashed
  results are the lever, measured before landing.

## Launch prompt

```
Read plans/testing/14-multi-engine-generated-corpus.md and implement it.
Plan 08 should have landed (ledger ids for by-design records); plan 15's
widened generators help but are not required.

Use max effort. Follow CLAUDE.md's required workflow for every commit:
bazel test //... green, the jaffle-shop example 15/15 with every validation
passing, every stale doc updated in the same commit. Commits: W1+W2 (the tool),
W3+W6 (the corpus and its guard), then one per engine finding W5 fixes.

Before editing, run ListAgents and git status: if another session has
uncommitted work here, develop in a scratch git worktree outside the repo
(memory note concurrent-sessions-worktree) and copy back after comparing
against a base snapshot. Postgres and DuckDB must be installed first (Setup in
the plan); they are not on this machine as of 2026-11-01. Keep the Postgres
cluster in a scratch directory and stop it when done.

Stop and ask before: adding any dependency (the plan needs none), the five
decisions if the user has not taken them, changing an engine rule to match the
references, vendoring more than the size budget, or putting either reference
on any Bazel classpath.

Report: records generated, kept, by design, findings, refusals, and dropped
because the references disagreed, per feature; the corpus size and the time
it adds to slt_test; every engine finding with its repro and what was decided.
```

## Findings

| # | Finding | Minimized repro | What became of it |
|---|---|---|---|
| F1 | The plan of a `BETWEEN` over two DECIMAL types does not bind back: the binder compares the three operands as the one DECIMAL that holds them all, `PlanSql` printed the two comparisons it expands to without their DECIMAL casts, and bound again each compared its own pair, another plan. The `reprinted` modes failed the query and `EXPLAIN` printed SQL that did not bind back; the answers were right. | `CREATE TABLE b (i INT, d2 DECIMAL(9,2), d18 DECIMAL(38,18))`; `SELECT d2 FROM b WHERE d2 BETWEEN d18 AND i` under the `reprinted` mode | Fixed in `98e672e` (no semantic choice: the printer writes the expansion back as the `BETWEEN`), with `types/decimal.test` and `PlanSqlTest`, both failing at `febca22`. 8 of the decimal feature's first 600 queries. |
| A1 | A `SUBSTRING` position before the first character counts from the first: `SUBSTRING('abc', 0, 2)` is `ab`, `SUBSTRING('abc', 0, 1)` is `a`, where Postgres and DuckDB give `a` and the empty string, as the standard counts the positions before the first character toward the length. A negative start parts the two references (DuckDB counts it from the end). | `SELECT SUBSTRING('abc', 0, 2)` | Held for the user (gotchas.md, "What the agreed corpus held out"): keep it with a ledger row (recommended: the README states it, as Spark has it), or count as the standard does. 12 records held. |
| D3 | `CONCAT` of a NULL is NULL, where both references skip the NULL argument (finding D3 of plans/testing/12). | `SELECT CONCAT('a', NULL, 'b')` | Already held for the user there; 35 records held under it. |
| C3 | A `TIMESTAMP` cast to text is its ISO instant, `2024-03-10T07:00:00.000001Z`, where both references write `2024-03-10 07:00:00.000001` (finding C3 of plans/testing/12). | `SELECT CAST(TIMESTAMP '2024-03-10 07:00:00.000001' AS STRING)` | Already held for the user there; 9 records held under it. |
| — | A `STDDEV_SAMP` of 64 BIGINTs agreed with both references to 15 digits in the baseline and moved one digit under the `spill` mode, the order Welford's partial results merge in. | (outer_join case 24 of the report) | No defect: the `double-precision` rule, which the runner already allows for in CockroachDB's and DataFusion's statistics. Not written. |

Each held record is held only once Postgres, given the query with the engine's
rule written in (`PortableSql.HeldRules`: `CONCAT` as `||`, a start before 1 as
1, a timestamp's text as its ISO instant), gives the engine's answer, so no
other difference can hide under a held id. The tool's first passes, before
its normalization was finished, also reported differences of its own making (a
DECIMAL compared at each reference's scale, a one-column row of the empty
string read as no row, a row of spaces ending a record's block), each fixed in
the tool before W1+W2 landed; no other engine finding came up.

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, in the plan's grouping:
W1 and W2 `febca22`, the engine fix of W5 `98e672e` (before its records were
written, as W5 asks), W3 and W6 `a6c67ac`. At each, `bazel test //...` passed
(73 targets, 74 with W6's guard) and the jaffle-shop job gave 15/15
Succeeded with every validation passing. W5's regressions failed at
`febca22`, checked in a temporary worktree. The references were Homebrew's
PostgreSQL 17.11 (the user allowed `postgresql@17`, where the plan's Setup
named 18), in a cluster made under the scratchpad with `--locale=C
--encoding=UTF8`, `TimeZone=UTC`, listening on 127.0.0.1:54329 (the
scratchpad's path is too long for a Unix socket), and Homebrew's DuckDB 2.0.0,
run with `-no-agent`. Neither is on any classpath; the cluster was stopped
once the corpus was written.

**The corpus**, written at `98e672e` from seeds 0 to 2000, 1.05 MB:

| feature | quota | cases | queries | kept | by design | held | finding | outside | references disagree |
|---|---|---|---|---|---|---|---|---|---|
| `window` | 600 | 79 | 604 | 604 | | | | | |
| `decimal` | 500 | 84 | 602 | 500 | 48 | | | 50 | 4 |
| `outer_join` | 400 | 65 | 499 | 406 | 1 | | 1 | 89 | 2 |
| `string` | 400 | 70 | 525 | 400 | | 36 | | 86 | 3 |
| `datetime` | 300 | 47 | 341 | 303 | | 9 | | 29 | |
| `cte_union_distinct` | 400 | 59 | 465 | 401 | 1 | | | 61 | 2 |
| `order_limit` | 400 | 54 | 417 | 400 | 2 | | | 15 | |
| all | 3,000 | 458 | 3,453 | 3,014 | 52 | 45 | 1 | 330 | 11 |

No query both references answered did the engine refuse, and none raised an
error in both references. By design: 51 `decimal-result-type` (a DECIMAL the
engine rounds inside a query to the type its rules give, each confirmed by
Postgres computing the query with the engine's types written in) and one
`double-precision` (a `VAR_SAMP`'s last digits), each with its twin. Held: 45
(A1 12, D3 35, C3 9; 11 under both A1 and D3). Outside, by why: arithmetic on
a FLOAT 169, `MIN` or `MAX` of a BOOLEAN or BINARY 58, a window `MetaQueryGen`
orders by no unique key 40, `%` of a DOUBLE 24, a float's text 11, a CAST of a
fraction to an integer 9, a BOOLEAN cast to a number 9, a BIGINT position or
scale 6, `ROUND` of a DOUBLE 4. The references disagreed on 11: DuckDB's
DECIMAL quotients in DOUBLE (4), a `SUBSTRING` from a negative position (3), a
`STDDEV` past its 15th digit (1), a `CORR` of no variance, NULL or NaN (1),
and a lone NULL beside a `bytea`, which Postgres types as text (2); gotchas.md
says which way the engine goes on each.

**Time**: the seven scripts' 14 executions a record take 117 s of `slt_test`'s
265 s across its four shards (191 s before); its longest shard went from 60.2 s
to 74.4 s, about a quarter more, under the half the plan expected. The scripts
pass under `batch1`, `batch2`, `sharded`, `serial`, `zone` and `hashzero`.
Generating the whole corpus takes about 45 s on eight threads; two runs from a
clean checkout, at different thread counts and batch sizes, wrote the same
bytes. `agreed_corpus_test` fails on a `window.test` cut to 218 of its 600
records. One block of `plan_roundtrip_fuzz_campaign` (3,000 seeds) ran after
the printer changed, green in a minute.

**Calls made overnight**, beyond the five decisions:

- **The renderer transforms where it can and refuses where it must.** A
  divisor is `NULLIF(<divisor>, 0)`, so a division by a possible zero is never
  generated rather than never kept; a literal's type is a CAST (a decimal one
  of its own digits, `CAST(0.07 AS DECIMAL(2,2))`, so `double-literal` never
  arises); a lone DECIMAL NULL is `CAST(NULL AS DECIMAL(p,s))`, since Postgres
  types a bare one meeting nothing as text. What the references part on by
  their rules is `Outside`, counted by why.
- **One SQL text for all three.** The engine reads every portable spelling
  (`INTEGER`, `REAL`, `DOUBLE PRECISION`, `TEXT`), so each record's SQL is the
  text both references ran; only a BINARY column's DDL and values are spelled
  per engine.
- **By design is confirmed, not guessed.** `decimal-result-type` is cited only
  where Postgres, given the query with each DECIMAL node the engine computes
  rounded to the type the engine binds it to (`typingProbes` binds each node
  alone), gives the engine's answer; a quotient or average at the top of a
  column, rounded half away from zero at its scale, is kept, as the plan's
  rule says. `double-precision` is cited for a statistic whose engine answer
  agrees to 15 digits, its twin printing the DOUBLE as `T`.
- **Held findings the same way**: a record is held under a finding only where
  Postgres with the engine's rule written in gives the engine's answer.
  `concat-null` and `timestamp-text` are plans/testing/12's D3 and C3, held
  there already; `substring-before-start` is new, A1.
- **Every written record runs under the runner first**, under every mode, so
  a record one mode answers otherwise is a finding rather than a script that
  fails.
- **Size**: tables of at most 32 rows and eight queries a case, which brought
  the corpus from 1.15 MB to 1.05 MB at the same quotas; no -0.0 in the data,
  since which of two tied zeros a group, a `DISTINCT`, a `MIN` or a cutoff
  keeps is each reference's own choice.
- **The tool's references run as one Postgres session a case (its tables
  `TEMP`, failures read from psql's `ERROR` and `LAST_ERROR_MESSAGE`) and one
  DuckDB process a query** (`DESCRIBE` giving its column types, which says
  when DuckDB computed a DECIMAL in DOUBLE).
- **`agreed/` joined `LedgerTest.Ports`**: it keeps the references' answers,
  the engine's only as twins, and pins no ledger rule.
- **Not done**: no shrinking of a finding's repro beyond the generated case,
  since there was one new finding and its repro is one line; the generators'
  own gates (plans/testing/15) are untouched, the feature producers living in
  `AgreedGen` until that plan folds the shapes in.
