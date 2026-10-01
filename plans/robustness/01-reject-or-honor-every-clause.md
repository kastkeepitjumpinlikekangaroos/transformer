# Plan 01: the binder silently drops every clause it does not read

> Status: **half 1 done** 2026-10-01 (every unread clause rejected, plus the
> reflection test); half 2 in progress · Effort: 2-3 days · Risk: medium (it
> changes what the engine accepts — queries that "worked" will start erroring,
> which is the point)
>
> The table below was right at `d4bb409` and re-verified at `e743778` before
> any change: every "got" reproduces. One row's SQL has a typo — the semi-join
> row's `ON t.a = u.a` has no `u` in scope and fails with `Unknown column
> 'u.a'`; `t LEFT SEMI JOIN s u ON t.a = u.a` reproduces the `(1)(2)(3)(4)`.
> Eighteen was an undercount: see ["What landed"](#what-landed).

## How this was found

A manual audit on 2026-09-10, from the other direction than the fuzzers take:
instead of generating SQL the engine supports and checking the answer, take
SQL **outside** the documented grammar and check what the engine does with it.
Every row in the table below was executed against `SqlEngine.execute` at commit
`d4bb409` through a throwaway probe target and the "got" column is real output,
not a reading of the code.

The structural cause is one line of design: `LogicalBuilder` walks the
JSqlParser AST by **pulling the getters it knows about**. A clause it never
reads does not error — it simply vanishes. `javap` on jsqlparser 5.0 lists 29
no-arg getters on `PlainSelect`, 21 on `Join`, 18 on `Select`, 18 on `Function`,
5 on `OrderByElement` and 5 on `Limit`; `LogicalBuilder` reads 67 distinct
getter names across every node type it binds, and the unread remainder is the
defect surface.

## Problem

Eighteen clauses parse, run, and return a wrong answer with no warning. Five of
them are badly wrong:

| SQL | Correct | Got | Severity |
|---|---|---|---|
| `t JOIN s USING (a)` | 3 rows | **8 rows (cartesian)** | the join condition is dropped |
| `t NATURAL JOIN s` | 3 rows | **8 rows (cartesian)** | same |
| `t LEFT SEMI JOIN s ON t.a = u.a` | `(2)(3)` | `(1)(2)(3)(4)` | semi becomes left outer |
| `SUM(DISTINCT a)` over `1,2,2,3` | `6` | **`8`** | DISTINCT dropped |
| `SUM(a) FILTER (WHERE a > 1)` | one row, `7` | **five rows of `8`** | filter dropped *and* the aggregate stops collapsing |
| `... ORDER BY a LIMIT 2 OFFSET 1` | `(2)(2)` | `(1)(2)` | wrong page |
| `... OFFSET 1` (no LIMIT) | 3 rows | 4 rows | `Select.getOffset` unread |
| `COUNT(ALL a)` with a NULL in `a` | `4` | **`5`** | binds as `COUNT(*)`, counts the NULL |
| `ORDER BY a NULLS LAST` | NULL last | NULL first | `OrderByElement.getNullOrdering` unread |
| `SELECT TOP 2 a FROM t` | 2 rows | 4 rows | `PlainSelect.getTop` unread |
| `... FETCH FIRST 2 ROWS ONLY` | 2 rows | 4 rows | `Select.getFetch` unread |
| `... QUALIFY n = 1` | 1 row | 4 rows | `PlainSelect.getQualify` unread |
| `SELECT DISTINCT ON (g) a` | 2 rows | 4 rows | `Distinct.getOnSelectItems` unread |
| `WHERE s ILIKE 'ab'` vs `'Ab'` | `(1)` | 0 rows | binds as case-sensitive `LIKE` |
| `WHERE s LIKE 'A!_b' ESCAPE '!'` | `(4)` | 0 rows | escape char ignored |
| `LAG(s) IGNORE NULLS OVER (...)` | skips NULLs | plain LAG | `Function.getNullHandling` unread |
| `GROUP BY a WITH ROLLUP` | + rollup row | no rollup row | unread |
| `SELECT a INTO newtab FROM t` | reject | rows returned, no table | unread |
| `SELECT ROW_NUMBER() OVER w ... WINDOW w AS (ORDER BY a)` | ordered | window spec dropped | `getWindowDefinitions` unread |

Two adjacent defects from the same probe, same root:

- **`SELECT 1 AS x`** (no FROM) dies with
  `NullPointerException: Cannot invoke "Object.getClass()" because "<local6>" is null`.
  An internal exception class reaching the user is its own bug — every other
  unsupported shape in the audit produced a clean `IllegalArgumentException`.
- **`GROUP BY GROUPING SETS ((g),())`** errors with
  `Unknown column 'g'. Available: [_agg0]`, leaking an internal aggregate slot
  name into a user-facing message.

This is not a new bug class for this repo — it is the class
[`plans/followups/01`](../followups/01-order-by-ordinal.md) already fixed once
(`ORDER BY <ordinal>` parsed and silently did not sort) and
[`plans/followups/02`](../followups/02-binder-grouping-parens-and-trim.md)
fixed from the rejection side. What neither of those did was ask the general
question: *what else is being dropped?* The answer is eighteen things.

## Why the fuzzers miss it

Structurally, and no amount of seed budget changes it. `QueryGen` and
`MetaQueryGen` build a typed AST of their own and render it to SQL, so the
corpus is **exactly the grammar the generator knows** — by construction it can
never emit `OFFSET`, `USING`, `FILTER` or `NULLS LAST`. The generators are
tuned for semantic depth inside the supported surface; this defect lives
entirely outside it. Nothing in the repo generates SQL *text* the engine is not
already expected to handle, which is why [plan 05](05-frontend-robustness-fuzzer.md)
is the matching testing plan rather than a generator tweak.

The README's grammar (§SQL features) is honest about what is supported, so none
of these clauses is a documentation bug. The project's own norm is
reject-or-document — `TRIM`'s extended forms are "rejected with a clear error",
`RANGE`-as-`ROWS` is a documented caveat. Silent dropping is the outlier.

## Proposed fix

Two halves, in this order, and the first half is the one that matters.

**Half 1 — nothing is silently dropped.** Add a rejection pass over the parsed
statement, before binding, that walks the getters `LogicalBuilder` does not
consume and throws a clear `IllegalArgumentException` naming the clause and
pointing at the supported alternative. Structure it so the audit is mechanical
rather than a list someone maintains by memory: a single `UnsupportedClauses`
object with one entry per unread getter, and a test that fails when a jsqlparser
upgrade adds a getter the table does not mention (see verification). Wording
should match the house voice already used for `WITH RECURSIVE` and the `TRIM`
forms.

Decide per clause between *reject* and *honor*. Reject is always safe and is
the right answer for `QUALIFY`, `ROLLUP`/`CUBE`/`GROUPING SETS`, `WITH ROLLUP`,
`INTO`, `LATERAL`, `SEMI`/`APPLY` joins, `IGNORE NULLS`, named `WINDOW`
definitions and the vendor hint getters.

**Half 2 — honor the ones that are cheap and clearly in scope.** These are
small, self-contained, and each removes a wrong answer rather than trading it
for an error:

1. **`OFFSET`** — read `Limit.getOffset` and `Select.getOffset`, add a skip
   count to `LogicalLimit` (or a `LogicalOffset` above it). Note `GlobalLimitExec`
   / `LocalLimitExec` already cooperate over partitions; the offset has to be
   applied **globally**, after the merge, so the local push-down must not skip
   per partition.
2. **`NULLS FIRST` / `NULLS LAST`** — read `OrderByElement.getNullOrdering` and
   carry a per-key null placement into `LogicalSort`. Today the placement is a
   fixed consequence of direction (NULLs first under ASC, last under DESC), in
   `SortExec.rowOrdering` and `WindowExec`'s twin at `:405`. Both sites change
   together, same as every other ordering change in this repo.
3. **`SUM`/`AVG`/`MIN`/`MAX(DISTINCT x)`** — `Function.isDistinct` is already
   read for `COUNT`; route the others through the same de-duplication, or reject
   them. `MIN`/`MAX(DISTINCT x)` is a no-op and can simply be accepted.
4. **`COUNT(ALL x)`** — must bind as `COUNT(x)` (NULL-skipping), not `COUNT(*)`.
5. **`ILIKE`** — either lower both sides or reject; today it silently claims to
   be case-insensitive and is not.
6. **`LIKE ... ESCAPE c`** — honor the escape character in the pattern compiler
   or reject the clause.
7. **`SELECT <expr>` with no FROM** — a one-row virtual scan, or a clean
   rejection. Either way, not an NPE.
8. **`FETCH FIRST n ROWS ONLY` / `TOP n`** — both are spellings of `LIMIT`;
   honoring them is a few lines, and they are the two most likely to appear in
   SQL pasted from another warehouse.

`FILTER (WHERE p)` deserves its own note: the probe shows it does not merely
drop the predicate, it also breaks the aggregate's collapsing (five rows of
`(8)` instead of one row). Whatever shape the binder is taking for it is wrong
twice over, so **reject it** in half 1 and treat honoring it as a separate
feature request.

## Verification

- A regression per row of the table above, asserting the new behaviour
  (a clean error with a specific message, or the correct answer for the honored
  clauses). These belong in `SqlEngineTest` alongside the existing reject-path
  cases for `WITH RECURSIVE` and `TRIM`.
- An **AST-completeness test** that reflects over `PlainSelect`, `Select`,
  `Join`, `OrderByElement`, `Limit`, `Distinct` and `Function`, and asserts
  every `get*`/`is*` method is either in `LogicalBuilder`'s consumed set or in
  the `UnsupportedClauses` table. This is the part that survives a jsqlparser
  upgrade; without it the audit rots the moment the dependency moves. Keep the
  assertion message explicit about what to do when it fires.
- `bazel test //...` green; jaffle-shop 15/15.
- The honored clauses need a fuzzer reach too — see
  [plan 05](05-frontend-robustness-fuzzer.md), and widen `QueryGen` to emit
  `OFFSET` and `NULLS LAST` once they work, so they stay working.

## Docs to update

`README.md` §SQL features (the grammar block gains `OFFSET` and the null
ordering; add a short "rejected, not ignored" list), `docs/gotchas.md` (the
whole class, plus the reflection test as the guard), `docs/testing.md` (the new
test target and what it covers), `docs/extending.md` (the recipe: when you teach
`LogicalBuilder` a new getter, move it from the rejection table to the consumed
set — the test enforces that it lands in exactly one).

## What landed

### Half 1 — nothing is silently dropped

`sql/plan/UnsupportedClauses.scala` holds the audit and the pass;
`LogicalBuilder.build` calls `UnsupportedClauses.reject` between parsing and
binding. Three departures from the design above, each for a reason found while
building it:

- **The consumed set is not a list.** "LogicalBuilder's consumed set" would have
  been one more table to keep in sync by hand, which is the failure this plan
  exists to remove. `unsupported_clauses_test` reads it out of `LogicalBuilder`'s
  compiled class files instead: the constant pool names every JSqlParser class
  the binder matches on and every getter it calls (a call through a supertype,
  like `Select.getWithItemsList`, counts for every subclass; a call through a
  subclass, like `WithItem.getAlias`, does not count for the parent). The audit
  then only lists what the binder does *not* read, as a rejection or as inert
  with the reason, and the test fails on any getter in neither list or in both.
- **The audit covers every class the binder reaches, not the seven named
  above.** Fifty-six classes, found mechanically: every concrete class the
  binder's bytecode names, plus every concrete class a getter it calls returns
  (`Distinct` arrives that way — the binder tests it for null and never looks
  inside, which is the whole `DISTINCT ON` bug). Every JSqlParser subclass of an
  audited class must be audited too, or listed as unreachable with the reason,
  because a `case f: Function` arm binds a subclass of `Function` as well.
  Public fields are audited with getters: `TRY_CAST` differs from `CAST` only in
  `CastExpression.keyword`, which has no getter.
- **The pass walks the AST reflectively** and descends only through getters the
  binder reads, so it checks exactly the nodes binding visits, in every position
  — `NULLS LAST` inside `OVER (...)` meets the same entry as in `ORDER BY`. A
  rejection is an `UnsupportedClauseException` (an `IllegalArgumentException`)
  naming the clause and carrying its entry, which is what plan 05's generator
  can read.

**Eighteen was an undercount.** Classifying every getter turned up 93
rejections; the ones that gave wrong answers rather than merely vendor hints
being ignored: a bare `OUTER JOIN` and Oracle's `a = b(+)` ran as INNER joins;
`x NOTNULL` ran as `x IS NULL` (JSqlParser stores that negation in
`isUseNotNull`, not `isNot`); `RLIKE` / `REGEXP` / `SIMILAR TO` ran as `LIKE`;
MySQL's `LIMIT 1, 2` dropped the offset; `ORDER BY` / `LIMIT` / `OFFSET` written
after a parenthesized query were dropped; `COUNT(UNIQUE x)` ignored `UNIQUE`;
`ROW_NUMBER(a)` ignored its argument and `SUM(a, 2) OVER ()` its second one;
`SUM(DISTINCT a) OVER ()` ignored `DISTINCT`; `COUNT(ALL a) OVER ()` counted
NULLs like the plain form; `SUM(a) WITHIN GROUP (ORDER BY a)` computed a running
sum; `CAST(x AS INT[])` dropped the array; `t.s[1]` dropped the subscript;
`* EXCEPT` / `* REPLACE` / `* EXCLUDE` were dropped; `PIVOT`, `UNPIVOT`,
`TABLESAMPLE`, `CONNECT BY`, `LATERAL VIEW` and `TOP n PERCENT` were dropped;
`E'...'` / `B'...'` string prefixes were dropped; `FROM other.t` silently
resolved to `t`; and `SKIP n` / `FIRST n` were dropped. `COUNT(ALL x)` was also
misdiagnosed above: the binder does not ignore `ALL`, it misreads
`Function.isAllColumns` — JSqlParser's flag for the `ALL` quantifier — as the
star.

The adjacent defects: `SELECT 1 AS x` is now a plain rejection ("SELECT without
FROM is not supported") rather than an NPE, and `GROUPING SETS` is rejected by
name before binding, so the `_agg0` leak cannot happen.

Three things the reflection test cannot see, recorded so nobody trusts it for
them: a getter the binder reads in one context and ignores in another (these
are the six *partial* rejections, found by reading the binder — `DISTINCT`
outside `COUNT`, `ALL` in `COUNT`, the ranking functions' argument, the second
and third window-function arguments outside `LAG`/`LEAD`, `DISTINCT` and `ALL`
under `OVER`); whether an *inert* reason is true (`Table.getDBLinkName` looks
like a clause and returns the table's own name on every plain table, which the
negative-control corpus caught); and SQL JSqlParser refuses to parse.

Four of the 93 entries have no example, because no SELECT text JSqlParser 5.0
accepts reaches the getter where the walk looks: `getForXmlPath` (the parser
never sets it; `FOR XML PATH` lands in `getForClause`), `PIVOT` / `UNPIVOT` on a
parenthesized query (does not parse), a KSQL windowed join (no parsing form
found), and `Limit.getByExpressions` (reached only through `Select.getLimitBy`,
which is rejected first). They stand guard against a grammar change. Every other
entry's example runs in the test and must reach that entry and no other.

Regressions: `sql_engine_test` rejects every row of the table end-to-end on the
plan's own data (each case comments the wrong answer it used to give), plus the
worst of the new finds; all 12 new test methods fail against the parent binder.
`unsupported_clauses_test` is new. `bazel test //...` green; jaffle_shop 15/15.

### Half 2 — honour what is cheap

One clause per commit, each replacing its half-1 rejection test with one that
asserts the answer.

**OFFSET.** A new `LogicalOffset` node sits below `LogicalLimit` —
`LIMIT n OFFSET m` binds as `LogicalLimit(LogicalOffset(child, m), n)` — so every
existing match on `LogicalLimit` stays valid. It plans to `OffsetExec`, which
concatenates its child's partitions and skips globally: the per-partition shape
of the limit push-down would drop `m` rows from every partition, which
`offsetIsGlobalAcrossPartitionsAndBatches` pins (53 rows, not 32). Both spellings
bind, `OFFSET m [ROWS]` and MySQL's `LIMIT m, n`. A UNION's OFFSET is hoisted off
the last arm like its LIMIT, because JSqlParser parks the tail on the arm when it
has no ORDER BY, and splits `OFFSET ... FETCH` across the arm and the list. After
a parenthesized query OFFSET stays rejected, alongside ORDER BY and LIMIT there.
One subtlety of the reflection test shaped the binder: it credits a getter call
made through a supertype to every subclass, so reading `getOffset` through a
`Select` reference would have blessed `ParenthesedSelect` too while leaving it
unread. The binder reads it through `PlainSelect` and `SetOperationList`
references, and the parenthesized rejection stands.

The plan's fuzzer reach: `QueryGen` and `MetaQueryGen` emit OFFSET on their
top-n shapes — beside the LIMIT or alone, under the same total-order soundness
condition, drawn after every other draw so non-top-n seeds are unchanged (every
other shape tally is bit-identical). The coverage guards count it and, per the
house rule, check the value half too: `offsetIsGeneratedAndObservable` runs each
OFFSET query without its tail and requires skips that land strictly inside the
result, since an OFFSET of 0 or past the end tests nothing. The first
distribution put too much weight on large skips for these small datasets (7 of
36 observable in mode-differential); weighting it toward 1 and 2 gave 18 of 48.

**NULLS FIRST / NULLS LAST.** Sort keys were `(Expr, Boolean)` pairs with the
NULL placement a fixed consequence of the direction, written out twice — in
`SortExec.rowOrdering` and again in `WindowExec.sortPartition`. They are now a
`SortKey(expr, asc, nullsFirst)` whose `compare` is the one null-aware comparison
both sites call, so the twin is gone rather than updated in step. The two-argument
`SortKey(expr, asc)` keeps the old default (`nullsFirst = asc`: NULL is the
smallest value), and the binder only departs from it for an explicit clause, so
no existing query changes — the "stop and ask" condition never arose. The binder
reads `getNullOrdering` at all four sites (below a Project, above a DISTINCT,
above a UNION, inside OVER). Mutation check: reverting `compare` to the
direction-only placement fails six targets.

Fuzzer reach: `QueryGen`'s ORDER BY keys became an `OrderKey` with an optional
placement (drawn last, so no earlier draw moved), `MetaQueryGen`'s top-n ORDER BY
carries one placement for all its keys, and `ModeDifferential`'s sortedness check
now asserts where the primary key's NULLs land — the default included, which is
new coverage for every ordered query the corpus already had. Observability is
thin by nature (placement is visible only when a result holds NULL and non-NULL
keys, or, for the metamorphic top-n, only when moving the NULLs changes what the
LIMIT keeps), so the guards' bounds separate "rare" from "gone": 18 of 125 placed
primary keys show it in mode-differential, and flipping the placement changes 14
of 152 metamorphic answers.

**SUM / AVG / MIN / MAX(DISTINCT x).** `MIN` / `MAX` ignore the flag (DISTINCT
cannot move an extreme). `SUM` and `AVG` wrap their aggregate in
`AggExprDistinct`, whose `DistinctState` keeps the distinct non-NULL values under
the same `equals` as `COUNT(DISTINCT)` (so the two agree on what distinct means),
unions them in `merge`, and at `finish` replays them into the inner aggregate's
own state in `Ops.cmp` order, so a DOUBLE sum does not depend on arrival or merge
order. Like `COUNT(DISTINCT)` it is not spillable, and the operator falls back to
the heap path. DISTINCT inside `STDDEV`, `VARIANCE`, `COUNT_IF`, `COVAR` and `CORR`
stays a partial rejection, and so does any DISTINCT under `OVER` but `COUNT`'s.
A mutation check found my own operator-level test too weak at first: with every
partition holding the same values, a `merge` that dropped the other partial still
produced the right sum. Its partitions now overlap only partly. `QueryGen` marks
some SUM / AVG aggregates DISTINCT (drawn last); observability is low in these
small datasets (a group must repeat a value), so that guard reads 2000 seeds:
26 of 193 answers change when DISTINCT is dropped.

Found along the way, outside this plan's table: an aggregate named only in ORDER
BY (`SELECT g FROM t GROUP BY g ORDER BY SUM(a)`) was never collected, and
binding died with an internal `NoSuchElementException`. Fixed in its own commit.

**COUNT(ALL x).** The plan read this as `ALL` being ignored; the binder in fact
misread `Function.isAllColumns` — JSqlParser's flag for the `ALL` quantifier — as
the star, binding `COUNT(ALL a)` as `COUNT(*)`. `ALL` is the default quantifier,
so the fix is to stop reading the flag: the star is found only as an
`AllColumns` parameter, in `bindAgg` and in `bindAnalytic` alike, and the audit
moves `isAllColumns` from two partial rejections to inert.

**ILIKE.** Not by lowering both sides, which the plan offered: with a letter as
the ESCAPE character, lower-casing the pattern changes which characters escape.
`LikeExpr` gained a `LikeSyntax` options value (`caseInsensitive` now, the escape
character next) and an ILIKE pattern compiles with `CASE_INSENSITIVE |
UNICODE_CASE`, so both evaluation paths and the literal fast path are unchanged.
Four places rebuilt `LikeExpr` from its parts — two in `ColumnProjectionPushdown`,
`JoinSideAnalysis`, and the fuzz `Shrinker` — and each would have reset the new
field to its default, quietly turning ILIKE back into LIKE: this plan's bug class,
inside the engine. They use `copy` now; a mutation that restores one of them
fails `sql_engine_test`. RLIKE / REGEXP and SIMILAR TO become partial rejections,
since the binder reads the keyword for LIKE and ILIKE. Fuzzer reach: `ExprGen`
makes some LIKE nodes ILIKE, under the eval / evalVec parity property.

**LIKE ... ESCAPE.** `LikeSyntax.escape` replaces the hard-wired backslash in the
pattern compiler: `Some('\\')` by default, as before, the named character for
`ESCAPE 'c'`, and `None` for `ESCAPE ''`, which turns escaping off. The escape test
runs before the wildcards, so `%` and `_` work as escape characters, and a trailing
escape stands for itself, matching the old trailing-backslash behaviour. An ESCAPE
that is not a one-character string literal is a clean error. Because ILIKE folds
case in the compiled regex rather than by lower-casing, a letter used as the escape
character still escapes only itself. `ExprGen` draws the escape character too.

## Launch prompt

```
Read plans/robustness/01-reject-or-honor-every-clause.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop deploy jar
exits 0 with 15/15 Succeeded, docs in the same commit.

Re-verify the table FIRST. Write a throwaway probe target that runs each SQL
string in it through SqlEngine.execute and prints the result, and confirm the
"got" column still matches at HEAD before changing anything — the plan's table
was captured at d4bb409. Delete the probe before committing.

Then do half 1 (reject everything unread, plus the reflection test) and land it
as its own commit. Then half 2, one honored clause per commit, each with its
regression.

Stop and ask before: honoring FILTER (WHERE), implementing GROUPING SETS /
ROLLUP / CUBE, or changing the default NULL placement for ORDER BY keys that
carry no explicit NULLS clause — that last one is a user-visible semantic
change to existing queries, not a gap.
```
