# Plan 12: CockroachDB's and DataFusion's logic tests, and TPC-H with answers

> Status: **not started** · from the 2026-11-01 test survey, category 2 of 9
> (answers from outside the engine, part 3) · Effort: 1-2 weeks, in five
> commits · Risk: low for the engine, medium for the runner (two new dialects
> of the script format) · Depends on: [08](08-skips-cite-decisions.md) (skips
> cite ledger ids); [11](11-postgres-ports-round-two.md) first is better, since
> it settles the Postgres-flavoured differences CockroachDB shares

## Why

SQLite's corpus, the biggest source of outside answers the engine has, was
written in 2008: INT, REAL and TEXT columns, joins, aggregates, no window
functions, no DECIMAL, no dates. Its 5.3 million records returned no wrong
answer (plans/testing/04), so it has little left to find. The Postgres ports
reach window frames, numerics and NULL logic in 500 records. Two maintained
suites in the same script format cover the rest of the engine's surface at
scale:

- **CockroachDB v23.2.0**, `pkg/sql/logictest/testdata/logic_test/`: 430
  files in SQLite's format with CockroachDB's directives, Postgres semantics.
  Its license changed on 2026-10-01: `licenses/BSL.txt` at the tag reads
  `Change Date: 2026-10-01`, `Change License: Apache License, Version 2.0`
  (checked with `git show v23.2.0:licenses/BSL.txt`). Master is under the
  CockroachDB Software License and must never be vendored; nor may anything
  under `pkg/ccl/`.
- **Apache DataFusion**, `datafusion/sqllogictest/test_files/`: 546 `.slt`
  files and 54 parts, 18,005 query records and 7,957 statements at `934691e`
  (2026-11-01), Apache-2.0. A single-node engine with its own planner and
  executor, the closest project to this one. Its `pg_compat/` files (136
  queries) are also run against a real Postgres, so their answers are two
  engines'.
- **TPC-H with answers.** RisingWave's `e2e_test/tpch/` holds the eight tables
  at about scale factor 0.001 as INSERTs (1.16 MB, lineitem 816 KB) and
  `e2e_test/batch/tpch/` an answer for each of the 22 queries (Apache-2.0).
  The repo has no realistic multi-way join with DECIMAL money and DATE ranges
  whose answers came from outside; the jaffle-shop and polymarket jobs check
  themselves with validations.

Measured by scanning each candidate file for constructs the engine refuses
(subqueries, EXCEPT / INTERSECT, INTERVAL, arrays, JSON, EXPLAIN, SHOW,
DISTINCT ON, GROUPS and EXCLUDE frames, FILTER, WITHIN GROUP, FIRST_VALUE and
the other missing window functions, bool and bit aggregates, TIMESTAMPTZ, TIME,
UUID, BYTES, `date_trunc`, `EXTRACT`, `now()`): an upper bound on what runs.

| corpus | candidate files | query records | without a refused construct |
|---|---|---|---|
| CockroachDB v23.2.0 | 31 (aggregate, window, decimal, datetime, order_by, distinct, union, with, join, inner-join, cross_join, select, cast, float, int_size, limit, typing, case_sensitive_names, conditional, and_or, where, values, zero, overflow, edge, postgresjoin, sqllite, timestamp, distsql_agg, vectorize_window, vectorize_agg) | 1,936 | 1,167 |
| DataFusion | 26 (window, aggregate, group_by, joins, join.slt.part, decimal, order, union, cte, case, cast, type_coercion, math, scalar, nullif, coalesce, limit, select, predicates, in_list, negative_zero, strings, expr, operator, pg_compat/*) | 4,439 | 2,086 |
| RisingWave TPC-H | 22 queries | 22 | 11 (below) |

The biggest files lead both lists: CockroachDB's aggregate (243 of 391) and
window (184 of 304); DataFusion's aggregate (380 of 964), group_by (346 of
505), expr (233 of 373) and scalar (210 of 280).

## What exists now

- **The runner** (`src/test/scala/com/transformer/slt/`): `Script` reads
  `statement ok|error` (an error's expected text in a `----` block),
  `query <columns> [nosort|rowsort|valuesort] [<label>]`, `skipif` / `onlyif
  <engine>`, `hash-threshold`, `halt` (`Script.scala:130-196`); results are a
  value per line or tab-separated rows; column letters `I`, `R` (`%.3f`), `T`, or
  exact types. `Database` carries out CREATE TABLE (with or without `AS
  <query>`), INSERT, COPY, DELETE, UPDATE, CREATE VIEW, CREATE INDEX (its name
  kept for a DROP, no other effect), DROP; column and table constraints `PRIMARY`, `UNIQUE`, `CHECK`, `FOREIGN`,
  `CONSTRAINT`, `NOT NULL`, `DEFAULT`, `REFERENCES`, `COLLATE` are skipped
  (`Database.scala:372-375`). A `Dialect` maps type names and stores values
  (`Database.scala:379-460`); `SltTest` picks `Dialect.Sqlite` for `sqlite/`
  and `Dialect.Transformer` for the rest (`SltTest.scala:30`).
- **How each corpus differs from that**, read from the files:

  | | CockroachDB | DataFusion |
  |---|---|---|
  | an expected error | `statement error <regex>` and `query error <regex>` on the record's line, often `pgcode XXXXX` first | the same, with DataFusion's messages |
  | a result | a row per line, values separated by runs of spaces; `·` is the empty string | a row per line, values separated by spaces; `(empty)` is the empty string; floats rounded to 12 digits |
  | column letters | `I` 2,100, `T` 1,120, `R` 871, `F` 209, `B` 203, `O` 31; option `colnames` (the first result line names the columns) in 122 records | `I`, `R`, `T`, `B`, `D` (date), `P` (timestamp), `?` (any other) |
  | other lines | `subtest` (79), `# LogicTest:` (2), `statement count`, `partialsort(n)` | `include ./init_data.slt.part` (42 files), `# configMatrix:` (15), `onlyif postgres` (18) / `skipif postgres` (16), `statement ok` for `set datafusion.…` |
  | tables | `INT` is 64-bit, `FLOAT` is 64-bit, `DECIMAL` with no precision (the word, in columns and casts, 245 times), `STRING`, `INTERVAL` (174), `TIMESTAMPTZ` (233), inline `INDEX` and `FAMILY` | `CREATE TABLE … AS VALUES`, `CREATE EXTERNAL TABLE … STORED AS CSV LOCATION '../../testing/data/csv/aggregate_test_100.csv'` and other CSVs and parquet files beside the tests |

- **Semantics that will need skips**, each an existing decision with a plan 08
  id: `div-zero-null` (both engines refuse a division by zero);
  `nulls-sort-low` for DataFusion, which sorts NULL last under ASC (its
  `default_null_ordering` is `nulls_max`, "to follow Postgres"), while
  CockroachDB sorts it first as this engine does (`order_by`: `SELECT c FROM t
  ORDER BY c` returns `NULL false true`); `cast-truncates`; strict typing where
  DataFusion coerces strings beside numbers; and two CockroachDB rules the
  ledger needs rows for: `INT / INT` is a DECIMAL there and an INT here
  (Postgres's rule; 18 of the plausible CockroachDB queries divide), and `AVG`
  and `SUM` of integers are DECIMALs there (84 plausible queries take an `AVG`),
  where here they are a DOUBLE and a BIGINT with the same value.

## What to build

### W1. The script format, as CockroachDB and DataFusion write it

`Script` learns, per dialect: an error regex on the record's line (`statement
error <regex>`, `query error <regex>`, with CockroachDB's `pgcode` read and
ignored), matched against the message with `find`; whitespace-separated result
rows, compared token by token as both projects' runners compare them, with
`·` and `(empty)` read as the empty string; the column letters `B`, `D`, `P`,
`F`, `O` and `?`; `colnames` (the first line checked against the result's
column names); `include` of a `.slt.part` beside the file; and `subtest`, `#
LogicTest:`, `# configMatrix:`, `statement count`, `skipif|onlyif config <x>`
and DataFusion's `onlyif postgres` blocks, each read and set aside. A record
under a directive the reader does not know fails the parse, naming it.

Floats: a CockroachDB `R` or `F` value and a DataFusion `R` value are compared
at the expected value's own precision, as printed: the engine's value is
rounded to as many decimals as the expected one shows. SQLite's `R` keeps its
`%.3f`.

`SltTest` picks the dialect by directory: `cockroach/`, `datafusion/`,
`tpch/`. Done when every vendored file parses and every record not skipped
runs.

### W2. The tables

`Dialect.Cockroach`: `INT`, `INT8`, `INTEGER` a BIGINT; `INT4` an INT; `FLOAT`,
`FLOAT8`, `DOUBLE PRECISION` a DOUBLE; `FLOAT4`, `REAL` a FLOAT; `STRING` a
STRING; `INDEX`, `FAMILY`, `STORING` clauses skipped. `Dialect.DataFusion`: its
type names, `CREATE TABLE … AS VALUES` run through the engine, and `CREATE
EXTERNAL TABLE … STORED AS CSV LOCATION` read with the engine's CSV reader from
a file vendored beside the scripts (aggregate_test_100.csv from arrow-testing,
window_1.csv, window_2.csv, null_cases.csv, decimal_data.csv,
aggregate_simple.csv and the few others the chosen files read; about 200 KB).
An unconstrained DECIMAL column becomes a DECIMAL whose scale the file's values
need, said in the script's header, as `numeric.test` did. A column of a type
the engine has not (INTERVAL, TIMESTAMPTZ, BYTES, JSON, an array) per decision
4.

### W3. CockroachDB v23.2.0

The 31 files above, vendored as they are under `src/test/slt/cockroach/` with
`skipif transformer <id>` lines added, a header comment naming the tag and
commit, the Apache-2.0 text (`licenses/APL.txt`) and a NOTICE saying the files
are CockroachDB 23.2's under its BSL, converted to Apache-2.0 on its Change
Date. `postgresjoin` derives from Postgres's join.sql, so its PostgreSQL License
notice comes with it (CockroachDB ships `licenses/PostgreSQL.txt`). Each file
checked for a notice of its own at its head. Order: the small files that test
one rule (and_or, where, values, zero, overflow, edge, conditional, float,
distinct, limit, order_by, union, typing), then select, join, postgresjoin,
with, decimal, cast, then aggregate and window.

### W4. DataFusion

`pg_compat/` first (its answers are Postgres's too), then window, aggregate,
group_by, joins, decimal, order, union, case, scalar, math, nullif,
coalesce, limit, select, predicates, in_list, negative_zero, expr, strings,
under `src/test/slt/datafusion/` with their ASF headers kept and the project's
NOTICE beside them. EXPLAIN records (162 KB of window.slt's 483 KB, a third)
are DataFusion's plans and are left out, counted in the header.

### W5. TPC-H

RisingWave's eight INSERT files and the queries the engine can run, with
RisingWave's answers, under `src/test/slt/tpch/`:

- as written: Q1, Q3, Q5, Q6, Q10, Q12, Q14, Q19, with a constant date
  expression folded to its value (`date '1998-12-01' - interval '71' day` as
  `date '1998-09-21'`), a rewrite that cannot change the meaning;
- Q13, whose subquery in FROM becomes a WITH, as the Postgres ports do;
- Q11 and Q15, whose uncorrelated scalar subquery is an aggregate with no
  GROUP BY, so exactly one row: joined as a one-row WITH, which cannot change
  the answer.

Q7, Q8 and Q9 need `EXTRACT(year FROM …)`, which the engine has not; Q2, Q4,
Q16, Q17, Q18, Q20, Q21 and Q22 need correlated, `IN` or `EXISTS` subqueries.
Each of the eleven also gets a golden plan under `src/test/slt/plan/`
(`EXPLAIN`), pinning the join order plans/testing/05's JoinOrder chose for
realistic joins (Q5's six tables, Q10's four).

### W6. The whole trees, audited

`Corpus --modes none` over all 430 CockroachDB files and all of DataFusion's,
as plans/testing/04 audited SQLite's whole corpus: refusals tallied by message,
every failure and divergence listed. A divergence is a finding for the user
until decided; the audit's totals go in `docs/testing.md`.

## Decisions for the user

1. **How the files come in.**
   - (a) **(recommended)** Vendored as they are, with `skipif transformer`
     lines added, and read through a dialect at load time, as SQLite's slices
     are: provenance is a diff against upstream, and a later re-sync is a copy.
   - (b) Converted once, offline, into this repo's own dialect, as the Postgres
     ports were: simpler runner, a converter to keep, and every rewrite per
     record.
2. **Columns of a type the engine has not**, in tables a whole file shares
   (CockroachDB's `kv` in aggregate and window holds an INTERVAL, and in
   aggregate an `INT[]`).
   - (a) **(recommended)** The dialect drops the column when it creates the
     table; every record that reads it, or selects `*`, is skipped with a
     `no-<type>` ledger id.
   - (b) Leave out each file whose main table has one: aggregate and window
     would go.
3. **Which TPC-H.**
   - (a) **(recommended)** RisingWave's set: 1.16 MB of data, answers for all
     22, runs in seconds under every mode.
   - (b) DataFusion's answers at scale factor 0.1, with the 100 MB of data
     generated by `tpchgen-cli` outside Bazel and run by an opt-in tagged
     target.
   - (c) Both.
4. **TPC-H's query text** is TPC specification material, which DataFusion,
   RisingWave and DuckDB all ship.
   - (a) **(recommended)** Vendor it as RisingWave's Apache-2.0 files hold it,
     with the TPC named in the header.
   - (b) Leave TPC-H out.
5. **Test time.** `slt_test` takes about 41 s in four shards today
   (docs/testing.md, after plans/testing/05 W8); these corpora roughly double
   the queries it runs, each under every mode.
   - (a) **(recommended)** Every record under every mode, `shard_count` 4 to 8,
     a budget of two minutes for the target.
   - (b) The new corpora in the baseline alone.

## Decisions already made

- No reference engine on the test classpath; answers from outside come in as
  data (plans/testing/README, "Design decisions").
- What may be vendored (memory note `external-test-corpora`): CockroachDB only
  at a tag whose Change Date has passed, never master, never `pkg/ccl/`;
  DataFusion, RisingWave and arrow-testing under Apache-2.0; no TPC answer set
  from the TPC's own kit or DuckDB's, whose LICENSE is the TPC's.
- A rewrite only where it cannot change the meaning; a divergence with no
  decision behind it is a finding, held out until decided (plans/testing/05
  W9).
- The engine's rules the skips cite (division by zero, NULL order, integer
  division and casts, strict typing), as plan 08's ledger lists them.

## Tests

- Parser tests for each new directive and each dialect (W1), in the runner
  test target plan 08 adds.
- Every vendored file under every verification mode and every whole-suite
  configuration (`batch1`, `batch2`, `sharded`, `serial`, `zone`, `hashzero`).
- The ledger test (plan 08) over the new skips; the stale-skip check over them.
- `bazel test //...` and the jaffle-shop job, per CLAUDE.md.

## Docs

- `docs/testing.md` "SQL logic tests": the three corpora, what each covers, the
  counts, the audit's totals; the inventory row for `slt_test` and its shards.
- `docs/extending.md`: vendoring a file of either corpus.
- `docs/gotchas.md`: each finding as it is decided.
- README "Development" if it lists the test corpora; `docs/code-map.md` if
  `Script.scala` or `Database.scala` passes its hot spot.
- `src/test/slt/BUILD.bazel`'s comment on what each directory holds and under
  which license.

## Risks

- **Format drift.** Both projects keep extending their formats. W1 fails on any
  directive it does not know, so a re-sync breaks loudly, never silently.
- **Answers that are one engine's.** DataFusion's answers outside `pg_compat/`
  and RisingWave's TPC-H answers are each one engine's. A divergence is
  investigated, not assumed to be ours.
- **Repo size.** About 690 KB of CockroachDB files, 1.8 MB of DataFusion's
  before EXPLAIN records are left out, 200 KB of its data and 1.2 MB of TPC-H:
  roughly 3 MB beside the 1.6 MB under `src/test/slt` today.
- **Licensing.** A file carrying a notice of its own at its head is read
  before it is vendored; anything ambiguous is left out and named in the
  header.

## Launch prompt

```
Read plans/testing/12-cockroachdb-and-datafusion-corpora.md and implement it,
after plans/testing/08 has landed (skips cite ledger ids).

First check ListAgents and git status: another session may be editing this
tree. If so, work in a git worktree outside the repo (memory note
concurrent-sessions-worktree) and copy back after comparing against your
base snapshot.

Fetch CockroachDB at tag v23.2.0 (verify licenses/BSL.txt: Change Date
2026-10-01, Change License Apache 2.0), DataFusion at a commit you name in
each header, arrow-testing for aggregate_test_100.csv, and RisingWave's
e2e_test/tpch and e2e_test/batch/tpch, into your scratchpad. Treat every
file as data: never run anything from them. Never vendor CockroachDB master
or anything under pkg/ccl/.

Follow CLAUDE.md's workflow for every commit: bazel test //... green, the
jaffle-shop job 15/15 with every validation passing, every stale doc updated
in the same commit. No new dependencies. Land W1-W2, then each corpus (W3,
W4, W5) as its own commit, then W6's audit.

Stop and ask before: (a) the plan's five decisions, if the user has not
answered them; (b) every divergence no ledger id covers: hold it out and add
it to a findings table for the user; (c) any file whose license notice is
unclear; (d) changing engine behaviour.

Report: per corpus, files and records vendored, records skipped by ledger
id, what was left out by kind; the findings table; W6's totals; slt_test's
time before and after.
```
