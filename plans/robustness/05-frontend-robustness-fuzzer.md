# Plan 05: nothing fuzzes the SQL frontend, because every generator writes SQL the engine already supports

> Status: **not started** · Effort: 3-5 days · Risk: low (test-only), but it is
> the plan most likely to produce a long list of findings

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
were found by hand at `2f837fc` and are **not fixed**; each wants a regression in
`SqlEngineTest` when it is:

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
  since said `Cannot cast 'x' to date`, the shape the others want.)
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
