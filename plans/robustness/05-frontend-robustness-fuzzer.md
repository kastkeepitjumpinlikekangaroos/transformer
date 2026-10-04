# Plan 05: nothing fuzzes the SQL frontend, because every generator writes SQL the engine already supports

> Status: **done** 2026-10-04 (`fuzz/frontend_fuzz_test`, campaign
> `frontend_fuzz_campaign`; findings in plans [10](10-error-messages-name-internals.md)
> to [14](14-pathological-nesting.md)) · Effort: 3-5 days · Risk: low
> (test-only), but it is the plan most likely to produce a long list of findings
> — see [What landed](#what-landed)

## How this was found

By asking what the existing three SQL fuzzers structurally *cannot* produce.
`QueryGen` and `MetaQueryGen` build a typed AST and render it (`SqlRender`), so
the corpus is exactly the grammar the generator knows. Every oracle in the repo
then asks "is the answer right?" — and a query the engine mis-binds or silently
truncates never gets built in the first place.

[Plan 01](01-reject-or-honor-every-clause.md) is what that blind spot was hiding:
eighteen clauses that parse, run and return a wrong answer. Two earlier bugs were
in the same blind spot and were also found by hand, not by fuzzing —
[`followups/01`](../followups/01-order-by-ordinal.md) (`ORDER BY <ordinal>` parsed
and silently did not sort) and [`followups/02`](../followups/02-binder-grouping-parens-and-trim.md)
(the binder rejected `(a + b) * c`). Three hand-found frontend bug families in one
area is the signal that the area needs a generator.

## Problem

There is no property anywhere in the repo over **SQL text the engine is not
already expected to handle**. Concretely, nothing asserts any of:

1. **Nothing is silently dropped.** Every clause the parser accepts is either
   reflected in the answer or rejected with a clear error. This is plan 01's
   entire finding class, and a fuzzer is how it stays fixed.
2. **Errors are user-facing.** A rejection carries an `IllegalArgumentException`
   with a message naming the construct — never an internal exception class and
   never an internal identifier. Two counterexamples found by hand at `d4bb409`:
   `SELECT 1 AS x` (no FROM) dies with
   `NullPointerException: Cannot invoke "Object.getClass()" because "<local6>" is null`,
   and `GROUP BY GROUPING SETS ((g),())` reports
   `Unknown column 'g'. Available: [_agg0]`, leaking an aggregate slot name.
   (Plan 01 fixed both: the first reads one row now, the second is rejected by
   name.)
3. **Rejection is total.** Anything the binder does not fully support fails at
   *bind* time, not mid-drain inside a worker. A mid-drain failure in a job is
   what made runner bug 03 cascade every downstream task to `Skipped`.
4. **Equivalent spellings agree.** `a BETWEEN x AND y` vs `a >= x AND a <= y`;
   `NOT (a = b)` vs `a <> b`; `a IN (1)` vs `a = 1`; parenthesized vs bare
   (`followups/02`'s class); `t JOIN s ON p` vs `t, s WHERE p`; and the row-count
   spellings plan 01 taught the binder, `LIMIT n` vs `FETCH FIRST n ROWS ONLY` vs
   `TOP n`, which no generator emits yet. These are metamorphic relations over
   *syntax*, which is a different axis from the five existing relations (all of
   which vary execution or algebra, never spelling).

### What plan 01 left for this plan

Plan 01 landed on 2026-10-01, so property 1 holds for every clause its audit
knows. The table this plan's unsupported-clause mode needs exists now:
`UnsupportedClauses.audits` in `src/main`, where every rejection names its clause
and all but four carry an `example` SQL string that reaches it (plan 01 says why
those four have none).

Probing around the audit turned up failures that property 2 should catch. They
were found by hand at `2f837fc`, and all but the CAST one are **not fixed**; each
wants a regression in `SqlEngineTest` when it is:

- **A raw `NumberFormatException` at bind time.** `SELECT 99999999999999999999
  AS big FROM t` fails with `java.lang.NumberFormatException: For input string:
  "99999999999999999999"`: an integer literal too large for a `long` reaches the
  number parser unguarded.
- **Raw parse exceptions mid-drain from CAST.** `SELECT CAST(s AS INT) FROM t`
  over a non-numeric `s` fails while the result drains, with
  `java.lang.NumberFormatException: For input string: "x"`; `BIGINT`, `FLOAT`
  and `DOUBLE` behave the same. It depends on the data, so it cannot be a
  bind-time rejection; the defect is the internal exception class, and a message
  that names neither the cast nor the column. (CAST to DATE and TIMESTAMP have
  since said `Cannot cast 'x' to date`, the shape the others want.) **Fixed
  2026-10-03**, with the rule that reads a string literal beside a number as
  that number: every numeric CAST now fails with `IllegalArgumentException:
  Cannot cast 'x' to int` (or `long`, `float`, `double`), a CAST to BOOLEAN reads
  only `true` and `false` where it read any other text as false, and a string
  literal under a CAST is read at bind time.
  `SqlEngineTest.castReadsNumbersAndBooleansStrictly` is the regression.
- **The `_agg0` slot name leaks from the ungrouped-column error.**
  `SELECT a, COUNT(*) AS c FROM t` (a column outside the aggregate, no GROUP BY)
  reports `Unknown column 'a'. Available: [_agg0]`, and so does
  `SELECT COUNT(*) AS c FROM t HAVING a > 1`. Plan 01 removed the same leak from
  `GROUPING SETS` by rejecting it by name; the ordinary path still has it, and
  it should say that `a` must be grouped or aggregated.
- **`ROLLUP` and `CUBE` read as unknown functions.** `SELECT a, SUM(a) FROM t
  GROUP BY ROLLUP(a)` reports `Unknown function 'ROLLUP(int)'`, and `CUBE(a)`
  reports `Unknown function 'CUBE(int)'`. JSqlParser parses both as function
  calls inside a plain GROUP BY, so the binder looks them up as scalar functions.
  The message should name the unsupported grouping construct, as the `GROUPING
  SETS` rejection does. Implementing them is a decision for the user, not a fix.

Plan 04 probed one more family at `dbf42b1`, which the corpora cannot reach
because they only aggregate numbers: **an aggregate or a math function binds
over a non-number.** `SUM` or `AVG` over a DATE, TIMESTAMP, BINARY or STRING
binds and then fails while the result drains with a raw
`ClassCastException`, and so does `ROUND` over a STRING; `ABS` over a DATE or
STRING binds and answers NULL for every row, holding a `DoubleVector` under the
argument's type. Each should be a bind error naming the type.

Two clauses plan 01 honoured without widening a generator to reach them, so the
existing fuzzers are blind to both:

- `MetaQueryGen` draws a NULL placement for its top-level ORDER BY, but
  `genWindowItem` renders the ORDER BY inside `OVER (...)` with `ASC` / `DESC`
  only, so `NULLS FIRST` / `NULLS LAST` inside a window is covered by
  `SqlEngineTest` alone.
- No generator emits `FETCH FIRST n ROWS ONLY` or `TOP n`; they are the property
  4 spellings above.

## Proposed fix

Three properties, sharing one new generator, following the existing harness
shape (`Rng` / `Props.forAll` / `Shrinker`, no new dependency):

**`SqlTextGen`** — generates SQL *text*, not a typed AST, over a fixed small
catalog. Three modes, so each property gets the corpus it needs:

- *unsupported-clause mode* — takes a supported query and splices in one clause
  from a table of constructs the engine does not implement. The table is the
  generator's payload, and it should be the **same table** plan 01's rejection
  pass is built from, `UnsupportedClauses.audits`, read from `src/main` so the
  two cannot drift. Any new entry in that table becomes fuzz coverage
  automatically.
- *equivalent-spelling mode* — emits a pair of strings that must mean the same
  thing, for property 4.
- *mutation mode* — takes a generated-valid query and applies one textual
  mutation (drop a token, duplicate a clause, swap a keyword, truncate mid-string,
  unbalance a paren, inject a unicode identifier or a 10k-character literal).
  Most of these are parse errors and that is fine — the property is about *how*
  they fail.

**`oracle/CleanRejection.check`** — for any generated SQL, exactly one of:
binds and runs; or fails with a `SqlParseException`/`IllegalArgumentException`
whose message mentions the offending construct. A `NullPointerException`,
`MatchError`, `ClassCastException`, `IndexOutOfBoundsException`,
`UnsupportedOperationException` or `IllegalStateException` is a finding, as is a
message containing an internal marker (`_agg`, `_k`, `<local`, `$anon`, a bare
class name). This property alone would have caught both counterexamples above,
and it needs no notion of what the right answer is — only of what a good failure
looks like.

**`oracle/NoSilentDrop.check`** — for unsupported-clause mode: splicing a clause
the engine does not implement must **change the outcome**. Either the query now
errors, or its result differs from the same query without the clause. A clause
that parses and leaves the answer bit-identical is the defect, and this is the
oracle that catches it generically — no per-clause expected answer needed. It has
a soundness condition worth stating in the code: some clauses legitimately do not
change a *particular* result (`OFFSET 0`, `NULLS LAST` on a NULL-free column), so
the generator must produce data that makes the clause observable, and the coverage
guard must assert it does.

**`oracle/SyntaxEquivalence.check`** — the two spellings from
equivalent-spelling mode produce the same multiset, via `RowOracle.multisetEquals`.

Coverage guards, per the house rule: assert the unsupported-clause table is fully
reached; assert the mutation corpus actually produces a mix of parse errors, bind
errors and valid queries (a mutator that only ever produces garbage tests
nothing); assert each equivalence pair shape is generated and that its inputs make
the distinction observable.

## Ordering against plan 01

Either order works, and they argue for different things:

- **Plan 01 first** (recommended, and what happened: plan 01 landed 2026-10-01)
  — the rejection pass gives `SqlTextGen` a table to read, `NoSilentDrop` starts
  green, and the fuzzer's job is to keep it that way and to find the clauses the
  manual audit missed.
- **This plan first** — the properties start red with a list of findings, which is
  good evidence but leaves the suite red, and this repo's convention
  (`plans/bugfixes/README.md`) is to remove a gate as proof a fix landed rather
  than to keep a campaign deliberately failing.

## Verification

- `CleanRejection` green over the mutation corpus at a campaign budget — that is
  the property most likely to find something unexpected.
- `NoSilentDrop` green over the full clause table, with the coverage guard
  asserting every entry was reached and was observable.
- `SyntaxEquivalence` green, with a non-vacuity guard (it must actually run on a
  meaningful share of seeds, the way `JoinCommutativity`'s guard does).
- A `fuzz`-tagged campaign target alongside the default small-seed target, same
  pattern as the existing five.
- Distill anything found into a named regression in `SqlEngineTest`, per the
  standing rule in `docs/testing.md`.

## Docs to update

`docs/testing.md` (the new targets, the generator and three oracles in the
inventory, the campaign line), `docs/extending.md` (the "add a metamorphic
relation" recipe gains the syntax-level axis), `docs/gotchas.md` (the blind spot
itself: generator-rendered SQL can only ever cover the supported grammar, which is
why the frontend needs a text-level corpus).

## What landed

`src/test/scala/com/transformer/fuzz/`: `SqlTextGen` (the generator),
`oracle/CleanRejection`, `oracle/NoSilentDrop` and `oracle/SyntaxEquivalence`,
`FrontendFuzzTest`, and text shrinkers in `Shrinker`; `frontend_fuzz_test` in the
default run (about 30 s) and the `fuzz`-tagged `frontend_fuzz_campaign`. No
`src/main` change, and no SQL-parsing dependency: `SqlTextGen.Lexer` cuts text
into tokens that render back exactly and knows nothing of the grammar, and the
fuzz library reads `UnsupportedClauses.audits` without JSqlParser on its
compile classpath.

### CleanRejection first

It ran first, as the launch prompt asked, and found the most: thirteen defects,
recorded as `CleanRejection.Defect`. Two things shaped it.

- **The corpus had to offer types.** The first 20000-seed survey reached the
  run-time path once: mutated `QueryGen` text either breaks the parser or stays
  type-correct, so nothing offered the binder a type it cannot run. Two more
  bases fixed that, typing queries (a function, aggregate, CAST, UNION, branch
  list or join condition over a column of any of the ten types) and casts of
  text through every pooled operator; they found plan 11's families and most of
  plan 09's run-time manifestations.
- **A known defect is excused in the oracle, never gated out of the generator.**
  A clean-rejection finding is a failure shape (every parse error carries the
  parser's class), and a generator gate around it would remove the shape from
  the corpus wholesale. Each `Defect` is recognized from the throwable and, for
  a run-time failure, the bound plan (`BoundPlan`), so it is excused only where
  it is the cause; a wrapper is unwrapped and a class name replaced, and the rest
  is still checked. Each names its plan and carries a repro, and
  `everyKnownDefectStillReproduces` fails when one stops reproducing, so an entry
  goes with its fix, the gate-removal contract by other means.

The mutations are the plan's seven plus three: inject a token (how a `;` and the
trailing statement reach the corpus), a boundary literal, and deep nesting (how
the stack overflow does). Mutations draw their base from `QueryGen`,
`MetaQueryGen`, the other three modes and the two bases above, so they land near
every construct the corpus knows.

### NoSilentDrop, in three halves

- **The clause table** (`AllClauseCases`): every entry with an example, read from
  `src/main`, in eight contexts (top level, a CTE body, a second CTE, a nested
  WITH, a materialized CTE, both arms of a parenthesized UNION, a counted CTE, a
  bare UNION), and for the entries on expression classes the fragment cut from
  the example at every position (15 for a value, 12 for a predicate, 4 for a
  star): 4848 cases. Each must be refused at bind time. **This half does not
  compare answers, a departure from the plan's wording**: an entry is a clause
  the engine does not implement, so there is no answer of its to observe, and the
  rejection pass reads no data; a spliced query that binds has its clause in the
  AST unread whatever it then returns. What can make this half vacuous is a
  splice that never reaches its entry, so its guard asserts each example fires
  its own rejection in nearly every context and each fragment at nearly every
  position where a supported query binds (`everySpliceSiteIsAValidHost`). The
  table is finite, so it is enumerated rather than sampled. All 84 entries with
  examples are refused everywhere; 49 cases are refused by the parser instead
  (JSqlParser will not parse an `IGNORE NULLS` or `KEEP` call in a CTE whose
  outer query is `SELECT COUNT(*)`, nor a tail clause before a bare UNION).
- **Honoured clauses** (`HonouredTemplates`, 55 positions of the clauses plan 01
  taught the binder and the core ones every context must keep), each in six
  contexts: the context must answer the query's own answer through its effect on
  rows, and one that answers the clause-free query's answer is reported as
  dropping it. **This is where observability lives**, as the plan's soundness
  note (`OFFSET 0`, `NULLS LAST` on a NULL-free column) describes: plan 01 took
  those clauses out of the table by honouring them. `observable` records whether
  the query with the clause and without it differ at top level, and the guard
  requires it on a share of every clause's cases; the thinnest, DISTINCT inside
  an aggregate in HAVING or ORDER BY, needed coarser groups before a group
  repeated a value often enough.
- **Text beyond the table** (`BeyondTheTable`): what JSqlParser drops before any
  getter (a statement after `;`, a repeated tail clause, a CTE named twice, a
  JOIN with no ON, two string literals side by side). Each must be refused; all
  ten bind today, recorded against plans 12 and 13.

### SyntaxEquivalence

51 shapes: the plan's (BETWEEN, negation, IN, parentheses redundant and needed,
a join written five ways, `LIMIT` / `FETCH FIRST` / `TOP`), every spelling the
audit calls inert (`UNIQUE`, `!=`, `&&`, `!`, `ISNULL`, `::`, a typed literal,
`{fn ...}`, `LIKE BINARY`, `N'...'`, `OFFSET 1 ROW`, `FETCH NEXT`, `UNION
DISTINCT`, `LEFT OUTER`), NULL placement written as a sort key (the window
`NULLS FIRST` / `NULLS LAST` this plan listed as unreached), the default `ALL`
quantifier against plan 01's `COUNT(*)` misreading, and the conditional
functions as CASE. Column types are compared as well as rows. Each shape's
degenerate query drops its subject, and the guard requires every shape
observable against it on a share of seeds. Five shapes disagree today and are
recorded: `IF` and `COALESCE` against CASE over numbers (plan 09), a quoted
identifier and a quote inside a literal (plan 13), and `NOT NOT` (plan 12).

### Findings

| plan | what | severity |
|---|---|---|
| [14](14-pathological-nesting.md) | a task whose SQL overflows the binder's stack **hangs its job forever**; 2000-operator chains overflow; unclosed parentheses take seconds and time out; the nested-loop guard bounds one side | the hang is the worst finding of the plan |
| [12](12-text-the-binder-never-sees.md) | a statement after `;`, a repeated ORDER BY / LIMIT / OFFSET / FETCH, a CTE named twice, a JOIN with no ON, `NOT NOT` | silent wrong answers |
| [13](13-literals-are-read-as-written.md) | `'it''s'` keeps both quotes (and the audit's inert reason for `getNotExcapedValue` is backwards), quoted identifiers keep their quotes, number literals out of range, `'a' 'b'` | silent wrong answers |
| [11](11-functions-bind-over-arguments-they-cannot-take.md) | aggregates and math functions over any type, a NULL scale, a non-boolean ON or IF condition, `ABS` of a date answering NULL | rejection is not total |
| [10](10-error-messages-name-internals.md) | parse errors and rejections name JSqlParser classes, pooled failures arrive wrapped (sometimes with no message), the `_agg0` leak, a rejection whose rendering throws | messages |
| [09](09-branch-result-types.md) | its mixed branch types, seen at run time: `COALESCE(timestamp, date)` fails with `Cannot set TimestampVector from java.time.LocalDate`, `CASE ... THEN ts ELSE d END` makes a date midnight | already planned |

Of the plan's own list, the CAST item was fixed before this landed, and the
other four (the big literal, the `_agg0` leak, `ROLLUP` / `CUBE` as unknown
functions, functions over non-numbers) are now in plans 13, 10 and 11; `ROLLUP`
and `CUBE` still read as unknown functions, a clean rejection with the wrong
words, recorded in plan 10's scope. No regression lands in `SqlEngineTest` yet:
each would assert the right answer, which needs the fix; each plan names the
regression its fix adds, and the fuzzer's repros are the executable record
meanwhile.

### Campaigns

The default run in `bazel test //...`: 2000 clean-rejection seeds and a further
1500 in its guard's own block, 1000 honoured and 1000 spelling seeds, the clause
table enumerated, and the guards over fixed blocks, about 30 s, of which eight
are the parse-timeout repro waiting out JSqlParser's timeout. Campaigns ran as 50000- and 60000-seed blocks in three JVMs at
`-Xmx1200m`, from a snapshot of the launcher. The early ones found the defects
above one or two at a time (the `IF` condition, the ROW CAST under `IS FALSE`,
the mixed `COALESCE` of a timestamp and a date), each becoming an entry and a
plan item; one ran a JVM out of memory on a duplicated non-equi join over 300
rows, which is plan 14's nested-loop item, after which typing queries draw at
most 16 rows. The last, 180000 seeds of each property (seeds 1000000 to
1179999), was green, about seven minutes a 60000-seed block.

## Launch prompt

```
Read plans/robustness/05-frontend-robustness-fuzzer.md and implement it
end-to-end. Read plans/robustness/01 first — they share the unsupported-clause
table, and landing 01 first is recommended.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15, docs
in the same commit.

Build CleanRejection first and run it before the other two — it is the cheapest
and the most likely to find something. Read the clause table from src/main rather
than copying it into the test tree, so the fuzzer cannot drift from the rejection
pass.

Every property needs a coverage guard that asserts the shape AND that the clause
is observable in the data; a NoSilentDrop over data where the clause cannot change
the answer is green and vacuous. See docs/testing.md on why that is the failure
mode this repo guards hardest against.

Stop and ask before: adding a SQL-parsing dependency of any kind, or changing
src/main behaviour — findings from this plan get plan files, not drive-by fixes.
```
