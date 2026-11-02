# Plan 11: PostgreSQL's integer, text, date and SELECT regression tests

> Status: **not started** · from the 2026-11-01 test survey, category 2 of 9
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
