# Plan 01: the binder silently drops every clause it does not read

> Status: **not started** · Effort: 2-3 days · Risk: medium (it changes what
> the engine accepts — queries that "worked" will start erroring, which is the
> point)

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
