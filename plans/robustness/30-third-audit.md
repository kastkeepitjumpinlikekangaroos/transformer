# Plan 30: the third audit

> Status: **done** 2026-10-31 · found by a differential run against SQLite and
> four parallel probes (windows, aggregates, joins and set operations, scalar
> expressions) · four semantics decided by the user, the recommended option
> each time · Risk: medium (two of them change answers of queries that run)

## How it was found

Plans 16-29 closed the second audit's list the same day. A deterministic wrong
answer agrees with itself in every mode, layout and spelling, so the fuzz
properties, which compare the engine with itself, cannot see one; another
engine can. A generator wrote well-typed queries in the dialect SQLite and the
engine share (expressions over INT, DOUBLE, STRING and DATE columns with
NULLs, three-valued conditions, every join kind, GROUP BY / HAVING, windows
with ROWS and RANGE frames, UNION, DISTINCT, ORDER BY / LIMIT / OFFSET, CTEs)
over small tables split across partitions and batches, some written to
parquet, and ran each through the engine (a throwaway probe printing JSON) and
through SQLite. About 3000 queries in default, spill and sharded modes
returned no wrong row; the one systematic difference was a refusal (below),
and `%` on a DOUBLE, where SQLite truncates both operands to integers. Four
probes then read the operators and the binder around them and confirmed each
finding by running it.

The operators came out clean: joins (4,800 checks against SQLite, `JOIN ON p`
against `CROSS JOIN WHERE p` on 726 key-type pairs), windows (159 frames
against SQLite, all ten types as keys), aggregates (a Python reference over
3k-row tables, every aggregate, spilled and sharded), and expressions (3,800
integer and three-valued expressions against SQLite on both evaluation paths,
580 DECIMAL operations against Python's `decimal`). The defects were in the
binder, the planner, a few functions, and the spill directory.

## Findings and fixes

Silent wrong answers:

1. **`_agg0` named an aggregate.** The aggregate output named its slots
   `_agg0`, `_agg1`, ... and found each by `indexOf(name)`; a column of the
   input named `_agg0` was found first, so `SELECT _agg0, SUM(x) FROM t GROUP
   BY _agg0` returned the key typed INT, and `HAVING _agg0 > 25` read the
   hidden SUM on a table with no such column. Slots are addressed by
   position, their names avoid every GROUP BY key's, and a column of an
   aggregate query resolves in the input (so a name two inputs have is
   ambiguous there as in WHERE; it bound to the key's name).
2. **`GROUP BY ()` was dropped**: `SELECT 1 FROM t GROUP BY ()` returned a row
   per row of `t`. It groups by nothing now.
3. **A RAND() key under sharding or spill was read twice**, once to route a
   row to a shard or bucket and once to group, partition or match it there:
   `GROUP BY RAND() > 0.5` made four groups. Such keys stay on the paths that
   read them once (`Expr.readsRandom`).
4. **A RAND() operand of a simple CASE or BETWEEN was read per comparison**
   (decision 4 below).
5. **LIKE never matched a character outside the Basic Multilingual Plane**:
   `Pattern.quote` of each UTF-16 char split an emoji, so `v LIKE 'x' || e`
   was FALSE where `v = 'x' || e` was TRUE. The pattern is read by code point.
6. **Half a character did not survive spill**: `SUBSTRING` cut an emoji in
   two, and a spill or file wrote the half as `?`, so `MAX(SUBSTRING(s,1,1))`
   changed with spill (decision 3 below).
7. **`CAST(decimal AS STRING)` was scientific** (`1.00E-7`); it is the digits.
8. **`{d '2026-02-30'}` was 2026-03-02**, read by `java.sql.Date.valueOf`; the
   JDBC escapes are read from their token text by the strict CAST.
9. **A UNION arm with `*` read a later literal through a DOUBLE** beside a
   DECIMAL arm, so `0.1000000000000000000001` became `0.1`.
10. **`CAST(1e300 AS FLOAT)` was Infinity and `CAST(1e-50 AS FLOAT)` 0**, where
    the same text is refused; both are errors now (`Float overflow`,
    `Float underflow`).
11. **`SIGN(NaN)` was 0**; it is an error (NaN has no sign an INT can say).
12. **AVG of BIGINTs depended on the partition layout**, summed as doubles;
    it divides the exact 128-bit sum once.
13. **`GREATEST(-0.0, 0.0)` was -0.0 and `GREATEST(0.0, -0.0)` 0.0**; ties
    break as MAX and MIN do.
14. **A RANGE offset no DOUBLE holds** (`1e309 PRECEDING`) over a FLOAT or
    DOUBLE key made an infinite key's frame NaN; it is refused.
15. **A CTE only unused CTEs read was run**, and could fail the query.
16. **The spill sweep deleted every `transformer*` entry of the temp
    directory** beside its root (the jaffle example's output among them), and
    a JVM's exit deleted the root another JVM was spilling into. Each JVM
    spills in a directory of its own; the sweep deletes only dead JVMs'
    directories inside the base.

Refusals of valid SQL, and accepted text with a guessed meaning:

17. **A GROUP BY key was matched by its text, as a whole SELECT item**:
    `SELECT UPPER(s) || '!' ... GROUP BY UPPER(s)`, `CASE WHEN a % 2 = 0 ...`
    over `GROUP BY a % 2`, `HAVING a % 2 = 1`, `SELECT t.a + 1 ... GROUP BY
    a + 1`, and `GROUP BY (a % 2), s` (JSqlParser keeps a key's parentheses
    only beside another key) were refused. The SQLite run found this one. A
    key is found by its bound expression at every node.
18. **An aggregate only inside a window or ORDER BY** (`SUM(COUNT(*)) OVER
    ()`, `ORDER BY COUNT(*)`) did not make the query an aggregate.
19. **`FROM t, u WHERE t.id = u.id` was a nested loop**, refused past 5000
    rows a side with advice a comma join cannot follow: a WHERE conjunct
    touching both sides of an inner join now joins its condition.
20. **The nested-loop guard's first rule read estimates**, refusing two
    one-row filters over large tables as "6000 rows to 6000"; it reads proven
    counts. The chain rule, the user's worst-case bound, is unchanged.
21. **`t x JOIN u x` was accepted**, a qualified name reading whichever table
    had the column; a FROM naming two tables alike is refused.
22. **`SELECT a FROM t LIMIT 1 UNION ALL ...` limited the arm**; a tail clause
    before a UNION's last arm is refused, asking for parentheses.
23. **`NULL AS n` in a parquet output failed only with a row to write**, as
    `Writing null to Parquet not supported in v1`; it fails first, naming the
    column and the CAST.
24. **A unary `+` took any type** (`+'abc'`, `+TRUE`); it is arithmetic.
25. **`ROUND(d, -1)` on a DECIMAL kept the input's scale**, the `-1` not read
    as written.

## Decisions (the user, 2026-10-31, the recommended option each time)

1. Division and `%` by zero are **NULL for FLOAT and DOUBLE** too, as for the
   other number types (Spark); they were IEEE's infinity and NaN.
2. **TRIM strips spaces only** (U+0020), as SQL, Postgres and Spark; Java's
   `trim` took every char up to U+0020.
3. **LENGTH and SUBSTRING count code points**, as Postgres, Spark and DuckDB.
   String ordering stays UTF-16 (not asked).
4. **A simple CASE operand and a BETWEEN operand are read once per row**
   (Postgres), through `SwitchCaseExpr` and `BetweenExpr`, which the binder
   makes only for an operand that reads RAND(), so every other BETWEEN keeps
   the two comparisons parquet pushdown reads.

## Considered and not changed

- **AVG of a wide DECIMAL can overflow.** `AVG` over `DECIMAL(38,0)` is
  `DECIMAL(38,4)` (the user's rule, plan 04's follow-up), so a mean past about
  10^34 overflows it. Keeping every integer digit instead was tried and
  reverted: it gave the mean of a `DECIMAL(38,0)` no fractional digits at all.
- **Two identical `RAND()` aggregates are one** (`SUM(RAND()), SUM(RAND())`),
  matched by text as ORDER BY matches a SELECT item. Left.
- **`RAND(seed)` is one value for the query**, as documented, where MySQL's is
  a per-row sequence. Left.
- **`SUBSTRING` from a position below 1 keeps its whole length** (`SUBSTRING
  ('abc', 0, 2)` is `ab`), as documented; the standard gives `a`. Left.
- **`LOG2(536870912)` is 29.000000000000004**, a double's division of logs.

## Tests

`sql_engine_test` (one test per finding above, each recording the old answer),
`join_swap_test`, `filter_pushdown_test`, `cte_materialization_test`,
`spill_test`, `parquet_roundtrip_test`, `sharded_mode_fuzz_test` (the RAND
keys under forced sharding). The frontend oracle learned the FLOAT and SIGN
run-time errors. The bind-reject guards at 20000 seeds (0.11% and 0.02%) show
no rejection by the new refusals.

Campaign blocks on fresh seeds afterwards: expression parity 300,000,
mode-differential 7,500 (and KeyEquality, window frames and exact sums 300),
frontend 3,000, metamorphic and sharded 1,200 each, all green. On the way they
found three things the default budgets never reach, each fixed:

- **A LIKE over a literal pattern compiled it before reading a row**, so
  `NULL LIKE 'ab!' ESCAPE '!'` failed on the vector path and was NULL on the
  row path (expression parity, seed 503393; SQL refuses the pattern at bind
  time, so only a generated tree reached it). It compiles at the first row with
  a string to match.
- **`ExprParity` did not own the LIKE escape error** plan 23 made a run-time
  one (seed 505916), and demanded one exception class where a tree with two
  value errors on different rows meets them in another order on each path
  (seed 530301); both are owned now, and two owned errors agree.
- **`QueryGen` cast DOUBLE columns holding NaN to BIGINT**, an error since plan
  18 (mode-differential, seed 503831); a float-to-integer cast is guarded to
  the values in range.

## Docs

README (SQL features, joins, CTEs, parquet, spill), architecture (§2b, §2c,
§5a, joins, §9), conventions, gotchas ("Defects the third audit found"),
testing.md, code-map.
