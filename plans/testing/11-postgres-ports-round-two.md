# Plan 11: PostgreSQL's integer, text, date and SELECT regression tests

> Status: **done** 2026-11-02 (8f122ce W1, 6db2cd3, c3643a8, 911a797, 9a1f36d,
> d6b6769, bbb4fa9, 7ddbcf7 W2, 77c4fc6, 7a41251, cbff308 W3; see
> [Outcome](#outcome); the five decisions taken overnight as recommended, not
> yet confirmed by the user) · from the 2026-11-01 test survey, category 2 of 9
> (answers from outside the engine, part 2) · Effort: 3-5 days, the larger
> files a day each · Risk: low for the scripts; each answer no decision covers
> is a finding for the user · Depends on: [08](08-skips-cite-decisions.md)
> (skips cite ledger ids)

## Why

Postgres is the reference the engine's semantics follow most often: integer
overflow is an error, a name in `ORDER BY` is an output column first, a
logarithm of zero has no answer. The first five ports (plans/testing/05, W9:
`window`, `numeric`, `case`, `boolean`, `float8`, 500 records, 58 skipped)
found five answers no decision covered, each decided and landed. They left the
parts of Postgres's suite that test the other half of the engine's surface:

- integer arithmetic at INT's and BIGINT's limits, with Postgres's own answers
  (`int4.sql`, `int8.sql`);
- FLOAT, the 32-bit float, at its edges (`float4.sql`);
- the binder's `GROUP BY`, `HAVING` and `ORDER BY` rules over columns the
  SELECT list does not name, ordinals, and ambiguous names in a self-join
  (`select_implicit.sql`, `select_having.sql`, `select.sql`);
- NULL in long `IN` and `NOT IN` lists (`expressions.sql`);
- how a number is read from the query text (`numerology.sql`);
- `LIKE`, `ILIKE` and `SUBSTRING` (`strings.sql`), DATE literals and
  comparisons (`date.sql`).

`numerology.sql` already has findings. Run through the CLI jar built at 09:20
on 2026-11-01, one query per job:

| query | engine | Postgres 18 |
|---|---|---|
| `SELECT 0.0e` | one row, column `e`, value `0.0` | error: trailing junk after numeric literal |
| `SELECT 0.a` | one row, column `a`, value `0.0` | error: trailing junk after numeric literal |
| `SELECT 1_000_000`, `SELECT 1_2_3` | `Unknown column '1_000_000'. Available: []` | 1000000, 123 |
| `SELECT 0b100101`, `SELECT 0o273` | `Unknown column '0b100101'. Available: []` | 37, 187 |
| `SELECT 123abc`, `SELECT 100_` | `Unknown column '123abc'. Available: []` | error: trailing junk |
| `SELECT 0x42F` | `A hexadecimal literal is not supported` | 1071 |

The first two are silent misreads: a number immediately followed by a letter
is taken as the number and an alias. The rest are refusals whose message names
a column where the text is a number Postgres 16 reads (or refuses as malformed).

## What exists now

- **The ports** (`src/test/slt/postgres/`): each header names its source file
  and commit (REL_18_6, `724edf9`), says what it leaves out by kind with counts,
  and says which rewrites it makes; each record names its source line. A
  statement is rewritten only where the rewrite cannot change what it means
  (`x::type` as a CAST, a named WINDOW inlined, a subquery in FROM as a WITH, a
  VALUES list as a table, `|/` `||/` `@` `^` as `SQRT` `CBRT` `ABS` `POWER`); a
  value whose type differs by design is written as the engine prints it; a
  DOUBLE is compared exactly, as SQLite's `T` (15 digits) or as `R` (three
  places), by how Postgres printed it (`docs/testing.md` "SQL logic tests",
  `docs/extending.md` "A port of another engine's tests").
- **Fixtures**: `postgres/data/tenk.data` (tenk1, 10,000 rows), loaded by `COPY
  t FROM 'file'` in Postgres's text format (`Database.scala`); `postgres/COPYRIGHT`
  names the commit. The other fixture tables of `test_setup.sql` (INT2_TBL,
  INT4_TBL, INT8_TBL, FLOAT8_TBL, TEXT_TBL, VARCHAR_TBL, CHAR_TBL, onek) are
  five-row INSERTs or `onek.data` (58 KB).
- **Column types** a port can declare (`Dialect.Transformer.columnType`,
  `Database.scala:394-410`): the CAST names, `INTEGER`, `REAL` (a FLOAT),
  `VARCHAR(n)` / `CHAR(n)` / `TEXT` (a STRING), `BOOL`, `LONG`, `DOUBLE
  PRECISION`, `DECIMAL(p,s)`. No SMALLINT.
- **The surface these files lean on** (README "SQL features"): string functions
  `LENGTH`, `UPPER`, `LOWER`, one-argument `TRIM` of spaces, `SUBSTRING`,
  `CONCAT`, `||`; no date function (no `EXTRACT`, `date_trunc`, `to_char`), no
  date arithmetic, text casts to DATE only from an ISO date; no SMALLINT,
  INTERVAL or TIME; no CAST to DECIMAL; no subqueries, EXCEPT or INTERSECT; no
  bitwise operators, factorial, `gcd`, `lcm` or `generate_series`.
- **The checker W9 used**, a Python script that re-derived every expected block
  from the `.out` file, lived in that session's scratchpad and is gone. The
  memory note `plans-as-sql-round-decisions` says it is worth redoing.

## The files

Measured on Postgres master (`180f3dc`, the survey's clone) with a statement
splitter; REL_18_6's files may differ by a few statements. "Plausible" counts
the queries that use no construct the engine refuses and no function it lacks,
an upper bound on what runs.

| file | statements | queries | plausible | adds | value / effort |
|---|---|---|---|---|---|
| `select_having` | 23 | 11 | 11 | HAVING with and without GROUP BY; `WHERE 1/a = 1` meets a = 0 | high / low |
| `select_implicit` | 44 | 28 | 28 | GROUP BY and ORDER BY on columns not selected, ordinals, a self-join's ambiguous names, the errors Postgres raises for each | high / low |
| `int4` | 101 | 93 | 51, +21 where SMALLINT only types a value | overflow at INT's limits (both engines refuse), INT min / -1, `%` with negatives, CAST from DOUBLE and DECIMAL | high / low |
| `int8` | 174 | 167 | 94, +28 | the same for BIGINT, INT beside BIGINT, CAST to INT | high / medium |
| `numerology` | 89 | 69 | ~25 to run, ~45 that must fail | how a number is read; the findings above | high / low |
| `expressions` | 81 | 44 | ~16 | NULL in `IN` and `NOT IN` lists of ten values (a one-row table stands in for its plpgsql identity function) | medium / low |
| `select` | 91 | 52 | 42 | ORDER BY over NULLs (the default placement differs), VALUES in FROM, `ORDER BY x USING >` (as DESC); needs onek | medium / medium |
| `float4` | 100 | 64 | 41-52 | FLOAT text at its range's ends, arithmetic, printing (only the format differs: Postgres `1.2345679e+20`, Java `1.2345679E20`) | medium / medium |
| `strings` | 664 | 540 | ~55 | the LIKE, ILIKE and SUBSTRING sections; the rest is functions the engine lacks | medium / high |
| `date` | 271 | 248 | ~25 | DATE_TBL comparisons, ISO literals, the range's ends; ~120 records read the DateStyle formats the engine refuses by design | low / medium |
| `text` | 74 | 72 | ~7 | functions the engine lacks | leave out |
| `int2` | 76 | 68 | 13 | SMALLINT's range, a type the engine has not | leave out |

Outside the list, from the same scan, for a later round: `aggregates` (590
statements, ~57 plausible queries over tenk1, onek and agg.data's aggtest),
`join` (1,118, ~67 over J1_TBL and J2_TBL, inner, outer and cross joins),
`select_distinct` (101, ~22), `union` (216, ~22 without EXCEPT or INTERSECT).

## What to build

### W1. The checker, kept this time

`slt:pg_expected`, a `scala_binary` in the slt package beside `corpus`: given a
ported script and its `.out` file, it reads psql's aligned tables (header,
separator, rows, `(N rows)`, `ERROR:` lines) in statement order, matches each
record to its source line by the `# int4.sql:42` comment the port writes,
prints Postgres's values as the record's columns print them (Java's
`Double.toString` / `Float.toString` for an exact column, `R`, `T`), and
reports every expected block that differs from what Postgres printed, and every
`statement error` whose statement Postgres ran. Done when it re-derives the five
existing ports with no difference. Decision 4 says whether it also runs in
`bazel test`.

### W2. Fixtures

`onek.data` vendored beside `tenk.data`; each other fixture table as its own
script's statements, from `test_setup.sql`, with each value stored as Postgres
stores it (`'   0  '` as 0, `'  123   '` as 123), the numeric port's rule.

### W3. The ports

In the order of the table: `select_having`, `select_implicit`, `int4`, `int8`,
`numerology` (the half that runs and the half that must fail), `expressions`,
`select`, `float4`, `strings` (the LIKE, ILIKE and SUBSTRING sections), `date`
(ISO literals and comparisons). One commit per file, each passing every
verification mode and every whole-suite configuration (`batch1`, `batch2`,
`sharded`, `serial`, `zone`, `hashzero`), each header counting what it leaves
out by kind, each skip citing its ledger id.

### W4. The findings

Every answer no decision covers is held out of its script, as W9 held out its
four, and goes to the user in one table: the query, Postgres's answer, the
engine's, and the options. The numerology rows above are the first.

## Semantic differences the skips will cite

Each is a decision already made; plan 08's ledger gives it an id.

- `cast-truncates`: a CAST from DOUBLE or DECIMAL to an integer truncates;
  Postgres rounds (int4.sql's table of -2.5 to 2.5, int8.sql's float casts).
- `div-zero-null`: `int8 / 0`, `int8 % 0`, select_having's `1/a`.
- `int-text` (a new ledger row for an existing rule, README "Types in CAST"):
  text casts to an integer by CSV's grammar, so no spaces around, no `0x`, `0o`
  or `0b`, no `_` between digits, all of which Postgres 16 reads.
- `double-text`: float4's `' NaN '`, `'infinity'`, `'   -3.0'`.
- `nulls-sort-low`: select.sql's `ORDER BY f1` over NULLs, Postgres placing
  them last.
- `date-text-iso` (a new row for an existing rule): date.sql's DateStyle forms.
- `date-range`: a DATE here holds about 5.8 million years either side of 1970
  (an INT of days); Postgres's runs from 4713 BC to 5874897 AD, so
  `'5874898-01-01'` is a date here and an error there.
- An overflow's message differs: such records are `statement error` without
  text, as W9 wrote them.
- A float printed in another format but with the same value is written as the
  engine prints it, with no skip (W9's rule).

## Decisions for the user

1. **Which Postgres.**
   - (a) **(recommended)** REL_18_6, `724edf9`, the commit the five ports name:
     one COPYRIGHT, one version, and a later bump re-ports everything at once.
   - (b) The newest release at porting time, each header naming its own commit.
2. **SMALLINT values.**
   - (a) **(recommended)** Write a SMALLINT value as an INT where the record's
     answer cannot depend on SMALLINT's range (`f1 <> int2 '0'`, `f1 * int2 '2'`
     below the limits), and leave out each record that tests the range;
     `int2.sql` is left out. That is the existing rule: a rewrite that cannot
     change the meaning.
   - (b) Leave out every record that names int2 (49 of int4's and int8's).
3. **CHAR(n) columns** (select_having, select_implicit: `c char(8)` holding
   four letters). Postgres pads a CHAR(8) to eight and compares without the pad.
   - (a) **(recommended)** Declare them STRING and store each value as
     Postgres's cast of it to text reads (unpadded); a record whose printed
     answer shows the pad is skipped as `no-char-type`.
   - (b) Store them padded, as Postgres stores them, so the printed values
     match and the comparisons do not.
4. **Whether the `.out` files are vendored.**
   - (a) **(recommended)** Vendor the `.out` file of each ported script under
     `postgres/expected/` (PostgreSQL License; about 260 KB for this plan's
     files, 410 KB for the first five) and run the checker in `slt_test`, so an
     expected block edited by hand fails the build, and "every answer is
     Postgres's" is checked, not asserted.
   - (b) Keep the checker a tool run when porting.
5. **Scope.**
   - (a) **(recommended)** This plan's ten files; aggregates, join,
     select_distinct and union as a plan of their own after it.
   - (b) All fourteen now.

**Taken overnight as recommended, not yet confirmed by the user** (2026-11-02):
decision 1 (a), REL_18_6 at `724edf9` for every port; decision 2 (a), a
SMALLINT value written as an INT where the answer cannot depend on SMALLINT's
range, the records that test it left out and `int2.sql` with them; decision 3
(a), a CHAR(n) column a STRING holding each value unpadded (no record's answer
shows the pad, so no `no-char-type` rule was needed); decision 4 (a), the
`.out` files vendored under `postgres/expected/` and the checker run in
`slt_test`; decision 5 (a), this plan's ten files.

## Decisions already made

- W9's porting rules (above), and that a finding is held out until decided.
- The semantics the skips cite: integer overflow is an error and a CAST to an
  integer truncates (plans/robustness/18), division by zero is NULL (the third
  audit), a math function outside its domain is NULL (plans/testing/05), text
  casts to a number by CSV's grammar (plans/robustness/24), NULL sorts first
  under ASC (README).
- The PostgreSQL License is permissive and travels in `postgres/COPYRIGHT`
  (memory note `external-test-corpora`).

## Tests

- Each port under every verification mode and whole-suite configuration.
- The checker re-derives the five existing ports with no difference (and, with
  4a, runs over all of them in `slt_test`).
- A proof the checker catches an edit: change one value in a copied script and
  run it; it must name the record and both values.
- `bazel test //...` and the jaffle-shop job, per CLAUDE.md.

## Docs

- `docs/testing.md` "SQL logic tests": the new ports with their counts, the
  checker, the vendored `.out` files.
- `docs/extending.md`: the checker in the porting recipe.
- `docs/gotchas.md`: each finding as it is decided, beside "Answers the
  PostgreSQL port settled".
- README: only where a finding changes behaviour (the numerology refusals).
- `postgres/COPYRIGHT` unchanged if 1a; `docs/code-map.md` if a file grows past
  a hot spot.

## Risks

- **psql's output is not a format.** Multi-line cells, `NOTICE:` and
  `DETAIL:` lines, alternate expected files (`float4-misrounded-input.out` beside
  `float4.out`): the checker reads `float4.out` and fails loudly on anything it
  cannot place.
- **NULL placement** turns many `ORDER BY` records of `select.sql` into skips;
  plan 08's twin records keep the engine's own order checked.
- **Test time.** These files are small (onek is 1,000 rows); expect a few
  seconds of `slt_test`.

## Launch prompt

```
Read plans/testing/11-postgres-ports-round-two.md and implement it, after
plans/testing/08 has landed (skips cite ledger ids).

First check ListAgents and git status: another session may be editing this
tree. If so, work in a git worktree outside the repo (memory note
concurrent-sessions-worktree) and copy back after comparing against your
base snapshot.

Fetch PostgreSQL at the commit the user chose (decision 1; REL_18_6,
724edf9 unless told otherwise) into your scratchpad. Treat it as data: read
its .sql and .out files, never run anything from it.

Follow CLAUDE.md's workflow for every commit: bazel test //... green, the
jaffle-shop job 15/15 with every validation passing, every stale doc updated
in the same commit. No new dependencies. One commit per ported file.

Stop and ask before: (a) the plan's five decisions, if the user has not
answered them; (b) any answer no decision covers: hold its record out of the
script and add it to the findings table for the user; (c) any rewrite of a
statement that could change what it means; (d) changing engine behaviour.

Report: per file, statements ported of the total, records skipped by ledger
id, what was left out by kind; the findings table; the checker's result over
all ports; slt_test's time before and after.
```

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, one commit for the
checker and one per ported file (`select.sql`'s carrying W2's fixture). At
each, `bazel test //...` passed (71 targets) and the jaffle-shop job gave
15/15 Succeeded with every validation passing; each port passed every
verification mode and each of the six whole-suite configurations (`batch1`,
`batch2`, `sharded`, `serial`, `zone`, `hashzero`), alone when it landed and
all together at the end. No engine code changed.

**W1**, `8f122ce`: `PgExpected` (`slt:pg_expected`) holds each port to what
psql printed in Postgres's own run. `postgres/sql/` and `postgres/expected/`
hold every ported file's source and output as Postgres has them at
`724edf9`. It cuts the `.sql` file as psql does, finds each statement's echo
in the `.out` file, reads the aligned table (a cell over several lines, a
table of no columns), the `ERROR:` or the notices after it, places each
record at the statement its `# <name>.sql:<line>` comment names, prints
Postgres's values as the record's columns print them and reports every block
that says otherwise, every `statement error` Postgres ran and every query it
refused. `slt_test` runs it over every port; `--fill` writes Postgres's
values into a new port and `--list` prints the source's statements for a
header's counts. It re-derives the five first ports with no difference
(371 records), once two of their comments named the right statements
(numeric's six check groups, window's VALUES table); `pg_expected_test`
proves it names an edited value with both values, an error Postgres never
raised and a query it refused.

**W2**, in `7ddbcf7`: `postgres/data/onek.data`, loaded by `COPY` as
test_setup.sql loads it; every other fixture table is each port's own
statements, each value stored as Postgres stores it.

**W3**, a commit per file. Per file: statements in its script of the file's,
skipped by rule, left out by need, and held out.

| file | commit | here | skipped, by rule | left out, by need | held |
|---|---|---|---|---|---|
| select_having | `6db2cd3` | 23 of 23 | - | - | - |
| select_implicit | `c3643a8` | 44 of 44 | - | - | - |
| int4 | `911a797` | 76 of 94 | `int-text` 14, `cast-truncates` 2 | gcd() and lcm() 6, pg_input_is_valid() 4, `<<` 2 | 6 |
| int8 | `9a1f36d` | 123 of 174 | `int-text` 14, `div-zero-null` 4, `cast-truncates` 2 | to_char() 24, gcd() and lcm() 6, bitwise operators 4, pg_input_is_valid() 4, generate_series() 3, CAST to SMALLINT 2, oid 2 | 6 |
| numerology | `d6b6769` | 34 of 89 | `refused-hex-literal` 9, `cast-truncates` 2 | PREPARE 3, a DO block 1 | 51 |
| expressions | `bbb4fa9` | 15 of 81 | - | a type of the test's own and its operators 30, SQL value functions and now() 19, typmod views 10, PL/pgSQL functions 4, EXPLAIN over points 3 | - |
| select | `7ddbcf7` | 48 of 87 | `nulls-sort-low` 9 | EXPLAIN 11, planner settings 10, a set-returning function 4, whole-row references 3, a table of no columns 3, partitioned tables 3, subqueries 2, inheritance 2, FOR UPDATE 1 | - |
| float4 | `77c4fc6` | 63 of 100 | `double-text` 6, `div-zero-null` 1 | float4send() 17, the bit-pattern type 11, pg_input_is_valid() 4, CAST to SMALLINT 4, numeric NaN 1 | - |
| strings | `7a41251` | 64 of 507 | - | regular expressions 146, bytea 109, string literal syntax 78, string functions the engine lacks 54, TOAST 35, char(n), varchar and name 14, outside the sections ported 5, \pset 2 | - |
| date | `cbff308` | 126 of 271 | `date-text-iso` 84 | date_part() and EXTRACT 71, date_trunc() 10, make_date() 10, date arithmetic 8, DateStyle settings 5, pg_input_is_valid() 5, infinite dates 5 | 31 |

In all 1,470 statements: 616 in ten scripts, 760 left out by need, 94 held
out. Of the 616, 540 hold Postgres's answers (the rest make tables), 393
answered as Postgres does and 147 skipped by seven rules, each followed by
its twin but under `refused-hex-literal`; the checker places and agrees with
all 540, and with the five first ports' 371, 911 in fifteen ports. Two rules
joined the ledger, each a row of a rule already decided: `int-text` (text
casts to an integer by CSV's grammar, plans/robustness/24) and
`date-text-iso` (a DATE reads only ISO text, the strict CAST of
plans/robustness/05), both pinned in `types/casts.test`; `nulls-sort-low` is
cited for the first time. `date-range` was not needed: `5874898-01-01`,
written without a sign, is no date to the engine, so its record is held, not
skipped. `slt_test`'s four shards took 24.4 to 31.6 s before the round and
25.1 to 33.8 s after it.

**Fixed on the way** (test code, no engine change): the `reprinted` mode
failed on `-(CAST('-9223372036854775807' AS BIGINT))`, whose answer was
right. The printer writes the negation of that BIGINT literal as
`-(-9223372036854775807)`, which binds as two negations, and
`PlanRoundTrip.normalize` folded only one; it now folds a chain of them
innermost first (`9a1f36d`, pinned in `plan_roundtrip_fuzz_test`; one
2000-seed block of `plan_roundtrip_fuzz_campaign` green).

**W4, the findings**, each held out of its script, none settled by a
semantic change (docs/gotchas.md, "What the second PostgreSQL port held
out"):

| # | query | Postgres | engine | options (recommended first) |
|---|---|---|---|---|
| 1 | `SELECT 0.0e`, `0.a`, `0.0a`, `.0a`, `0.0e1a` (numerology.sql:48-52) | error: trailing junk after numeric literal | 0.0, in a column `e` or `a` | (a) refuse a numeric literal followed by a letter or `_`, naming the text; (b) keep |
| 2 | `SELECT .000_005` (numerology.sql:78) | 0.000005 | 0.0, in a column `_005` | as 1 |
| 3 | `SELECT 0b100101`, `0o273`, `1_000_000`, `0x1EEE_FFFF`, `1_000.000_005` and the rest (26) | 37, 187, 1000000, ... | `Unknown column '0b100101'. Available: []`, or a parse error in the parser's words | (a) refuse each by name, as a hexadecimal literal is; (b) read them as Postgres 16 does; (c) keep |
| 4 | `SELECT 123abc`, `0b`, `1x`, `100_` and the rest (18) | error: trailing junk, invalid binary integer | an unknown column, or a parse error elsewhere | settles with 1 and 3 |
| 5 | `SELECT f1 AS two, ... GROUP BY two` (numerology.sql:172) | groups by the output column | `Unknown column 'two'` | (a) accept it under ORDER BY's rule (an output column first, refused where ambiguous); (b) keep refusing and say so in the README |
| 6 | `SELECT q1 - q2 AS minus FROM INT8_TBL` (int8.sql:66, 69) | runs | `SQL parse error: Encountered unexpected token: "minus" "MINUS"` (JSqlParser reserves MINUS) | (a) document the words a name must quote and name the word in the refusal; (b) keep. The port writes `AS "minus"` |
| 7 | 43 records both engines refuse for different reasons: int4.sql's and int8.sql's six texts one past the range in base 2, 8 or 16; date.sql's 31 DateStyle texts with a field out of range, and the two dates past an end of Postgres's range | out of range | no integer or ISO date of the engine's grammar | (a) leave them out; (b) keep them as errors both raise |

**Decisions, taken overnight as recommended, not yet confirmed by the
user**: 1 (a), 2 (a), 3 (a), 4 (a) and 5 (a), recorded under the decisions
above.

**Claude's calls within them** (revisit if the user objects):

- The `.sql` files are vendored beside the `.out` files: each record cites
  its line of the `.sql` file, and psql's echo leaves out blank lines.
- A record with no comment comes from the statement after its
  predecessor's; one under a comment that cites no line is the port's own (a
  table made for a VALUES list), and so is any uncommented record after it.
- A record both engines refuse is kept as `statement error` where the
  reason is one (text that is no value of its type, a value past its type,
  an ambiguous name) and held out where it is not, as plan 10 held Spark's.
- `int2` values and `TEMP_INT2`'s column are INT where the answer cannot
  depend on SMALLINT's range (a `WHERE` there keeps every value within it);
  a CAST to SMALLINT, which tests the range, is left out.
- Rewrites that cannot change a meaning, each said in its record: `AS
  "minus"`; VALUES as a query as the UNION ALL of its rows' SELECTs, which
  Postgres's documentation says it means; `TABLE t` as `SELECT * FROM t`;
  `SUBSTRING(x FROM s FOR n)` as `SUBSTRING(x, s, n)`; `ORDER BY x USING >`
  as `DESC`; a PL/pgSQL identity function as a one-row table's column;
  `'2040-04-10 BC'` stored as `'-2039-04-10'`.
- strings.sql is ported to the plan's sections only; five statements outside
  them the engine might run (`length()` and four concatenations) are left for
  a later round, and counted so in its header.
- The CHAR(n) columns of select_having and select_implicit are the runner's
  STRING as written; no answer depends on the pad, so no `no-char-type` rule
  was needed.
