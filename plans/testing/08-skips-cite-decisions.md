# Plan 08: every skip names its decision, and a stale skip fails

> Status: **not started** · from the 2026-11-01 test survey, category 8 of 9 ·
> Effort: 1-2 days · Risk: low (test code, the scripts and the docs; no engine
> change) · Depends on: nothing. Lands before plans 10-14, so the skips their
> ports add cite a decision from their first record.

## Why

A `skipif transformer` record says the engine answers otherwise by design.
Nothing holds it to that:

- **The reason is never read.** It is a comment after `#` on the skip line,
  and `Script.tokenize` stops at the first token that starts with `#`
  (`src/test/scala/com/transformer/slt/Script.scala:204-205`), so a skip is
  `Condition(skip = true, "transformer")` and nothing more
  (`Script.scala:136-138`). The runner records it as `Skipped("its conditions
  exclude this engine")` without running it (`Runner.scala:44`).
- **A stale skip goes unnoticed.** When the engine starts to answer a skipped
  record as the other engine does (a refused construct lands, a rule is
  changed), the record stays skipped and its answer stays unchecked. The fuzz
  harness has the opposite rule for its own known defects: `FrontendFuzzTest`
  fails when a known defect stops reproducing (`everyKnownDefectStillReproduces`,
  `FrontendFuzzTest.scala:133`) or a known disagreement stops disagreeing
  (`everyKnownDisagreementStillDisagrees`, `:485`), "so the record cannot
  outlive the fix".
- **The scale is about to grow.** There are 446 skips today
  (`grep -rh '^skipif transformer' src/test/slt | wc -l`): 388 in SQLite's
  slices and 58 in the five Postgres ports. Plans 10-12 port Spark's,
  Postgres's, CockroachDB's and DataFusion's tests, each with its own
  divergences; free text at that scale cannot be audited.

Every reason in the scripts today, clustered (counts measured on `f309205`):

| cluster | skips | where | what the engine does |
|---|---|---|---|
| a subquery: `IN` with one (150), any other (56), `EXISTS` (29), in `FROM` (13) | 248 | sqlite | refuses |
| `EXCEPT` (100), `INTERSECT` (14) | 114 | sqlite | refuses |
| arithmetic on a STRING | 15 | sqlite | refuses |
| an empty `IN` / `NOT IN` list | 8 | sqlite | refuses |
| a hexadecimal literal (2), a parenthesized join (1) | 3 | sqlite | refuses |
| a math function outside its domain: logarithms (16), powers (5), a square root (1) | 22 | postgres | NULL where Postgres refuses |
| text casts to BOOLEAN from `true` and `false` alone | 12 | postgres | refuses where Postgres reads `t`, `yes`, ... |
| a division or remainder by zero | 5 | postgres | NULL where Postgres refuses |
| a CAST to an integer truncates | 5 | postgres | truncates where Postgres rounds |
| text casts to DOUBLE by CSV's grammar | 4 | postgres | refuses `nan`, `infinity`, spaces |
| past DOUBLE's range an infinity, below it 0 | 4 | postgres | no error where Postgres refuses |
| a number with a point is a DOUBLE, and a DOUBLE's precision | 4 | postgres | another value past 15-17 digits |
| a DECIMAL quotient's scale (Spark's and SQL Server's rules) | 1 | postgres | six places |
| a window key that reads no column is refused | 1 | postgres | refuses |

So the skips make two kinds of claim. 388 say "the engine refuses this", which
running the record checks: it must still be refused, cleanly, by that rule.
58 say "the engine answers otherwise, by decision X", which running the record
checks only halfway: the other engine's answer must still be wrong here, but
nothing says the engine's own answer is the one X gives. Every skipped record
is a query (412 `query`, 34 `statement error`, all SELECTs), so each can be run
without changing a table.

## What exists now

- `Script.Condition(skip, engine)`; `skipif` / `onlyif` lines may chain (18
  skipped records also carry `skipif mysql` or `skipif postgresql`).
- No `onlyif transformer` record exists anywhere, so at no skipped record is
  the engine's own answer written down. A few decisions are pinned elsewhere:
  `types/float_equality.test` pins underflow to 0, `types/integer_overflow.test`
  overflow, `refused/messages.test` the wording of 48 refusals.
- The decisions themselves are spread over README sections ("Scalar
  functions", "Types in CAST", "A NULL sorts as the smallest value", the DECIMAL
  rules), `docs/gotchas.md` ("Answers the PostgreSQL port settled", "What's
  intentionally NOT done"), `plans/robustness/09-30`, `plans/testing/04-05` and
  the memory notes. None gives a decision a name a script could cite.
- `Corpus` tallies refusals by message for an audit (`Corpus.normalizeMessage`)
  and counts skips as one number.
- Docs that describe skips: `docs/testing.md` "SQL logic tests" (the format,
  the Postgres ports' 58 skips, the SQLite slices' reasons), `docs/extending.md`
  around line 594 (`skipif transformer # <why>`).

## What to build

### W1. A ledger of the divergences, each with an id

One table, a row per rule: a stable id (lower-case words joined by `-`), the
rule in one sentence, where the docs state it (README or gotchas section),
where it was decided (plan, date), and the script record that pins the engine's
own answer. The first rows cover today's clusters and what plans 10-12 will
meet first:

| id | rule |
|---|---|
| `refused-subquery`, `refused-except-intersect`, `refused-text-arithmetic`, `refused-empty-in`, `refused-hex-literal`, `refused-parenthesized-join` | the construct is refused; each row carries the text its refusal must contain |
| `div-zero-null` | a division or `%` by zero is NULL, for every numeric type |
| `math-domain-null` | a math function outside its domain is NULL |
| `cast-truncates` | a CAST to an integer truncates toward zero |
| `bool-text` | text casts to BOOLEAN from `true` and `false` alone |
| `double-text` | text casts to a FLOAT or DOUBLE by CSV's grammar |
| `double-range` | past DOUBLE's range an infinity, below it 0 |
| `double-literal` | a number written with a point is a DOUBLE |
| `double-precision` | a DOUBLE holds 15-17 significant digits |
| `decimal-result-type` | DECIMAL result types follow Spark's and SQL Server's rules |
| `window-constant-key` | a window key that reads no column is refused |
| `nulls-sort-low` | NULL sorts first under ASC (Postgres, DataFusion and CockroachDB sort it last) |
| `int-is-int` | an integer literal that fits an INT is an INT; SQLite's integers are 64-bit |
| `branch-double` | a CASE or COALESCE with a DOUBLE branch is a DOUBLE (plans/robustness/09) |

Done when every row's pin record exists and passes, and the ledger is the only
place the list is kept.

### W2. A skip cites an id

`skipif transformer <id> [# comment]`, for example
`skipif transformer div-zero-null # an integer divided by zero is NULL`.
`Script.Condition` gains the id; a `skipif transformer` or `onlyif transformer`
with no id, or with one the ledger does not have, is a `ParseError` naming the
file, the line and the nearest id. Conditions on other engines keep their free
form (SQLite's corpus writes `skipif mysql # not compatible`). The runner's
`Skipped` outcome carries the id.

### W3. A stale skip fails

Each `skipif transformer` record runs once, in the baseline alone (the
verification modes compare runs with each other, not with a skip), and must
still diverge as its id says:

- a `refused-*` id: the record is refused cleanly (`Runner.clean`), with the
  ledger row's text in the message;
- any other id: the record does not pass: a query does not return the expected
  answer, and a `statement error` record still runs (the engine answers where
  the other engine refused).

A skip that passes fails its script: `line N: skipped as div-zero-null, but
the engine now gives the expected answer; remove the skip`. 446 baseline runs
over tables of at most a few thousand rows add seconds to `slt_test`. The
`Corpus` audit gets the same check and reports stale skips in their own
section, and its report tallies skips by id.

### W4. Every existing skip migrated

A one-off rewrite of the 446 skip lines from their reasons to ids (the table
in Why maps each reason), checked by W2 and W3, then the pins W1 needs where a
decision has none. The 14 reasons of the Postgres ports keep their comments,
which say the particular case.

## Decisions for the user

1. **Where the ledger lives.**
   - (a) **(recommended)** A section of `docs/gotchas.md`, "Answers that
     differ from other engines by design", which `slt_test` reads (the root
     package exports the file as data). Gotchas is where intentional
     non-features live already, CLAUDE.md prefers an existing file, and a
     reader and the build see one list.
   - (b) A data file under `src/test/slt/` that gotchas.md points to: simpler
     to parse, but two places to keep in step.
   - (c) A Scala object in the slt package: compile-checked, and the docs still
     need their own copy.
2. **What the engine's own answer at a value skip is held to.**
   - (a) **(recommended)** Each of the 58 value skips (and every one a later
     port adds) is followed by an `onlyif transformer <id>` record with the
     engine's answer at that spot, checked under every verification mode like
     any record. W3 alone proves only that the answers still differ: a NULL
     that became a wrong number would still differ.
   - (b) Only the ledger's pin records hold the engine's answer: fewer records,
     and a regression at one spot shows only if a pin covers it.
   Refusal skips need no twin; W3 checks the refusal and `refused/messages.test`
   its words.
3. **What a stale skip does.**
   - (a) **(recommended)** Fails `slt_test`, as the known-defect lists fail
     `FrontendFuzzTest`.
   - (b) Is reported by `Corpus` only. Cheaper to live with, and the skip can
     outlive its reason indefinitely.

## Decisions already made

- A port keeps the other engine's answers, never this engine's; a record the
  engine answers otherwise with no decision behind it stays out of the script
  as a finding until it is settled (`docs/extending.md`, plans/testing/05 W9).
- Expected errors must be clean ones (`Runner.clean`, the slt round's decision
  7 in the memory note `slt-round-decisions`).
- Strict semantics over another engine's coercions (memory note
  `prefer-strict-sql-semantics`); each id names a decision the user already
  made, listed in the memory notes and plans; this plan adds none.
- No new dependency; prefer editing an existing file (CLAUDE.md).

## Tests

- A new `slt/runner_test` (JUnit, `RunnerTest.scala`) over inline scripts:
  a skip with no id, or an unknown one, is a parse error naming the line; a
  skipped record that now passes fails its script with the stale message; a
  refusal skip refused for another reason fails; a value skip that still
  diverges passes; an `onlyif transformer` twin is run and checked. The runner
  has no unit target today; every check of it is a script.
- A ledger test: every id is cited by a skip or pinned by a record, and every
  row names a pin that exists.
- Proof of detection, in a worktree at the base commit: make `CAST('t' AS
  BOOLEAN)` true and run `slt_test`; boolean.test's ten `bool-text` skips must
  go stale and fail it. Revert.
- `bazel test //...` and the jaffle-shop job, per CLAUDE.md.

## Docs

- `docs/testing.md` "SQL logic tests": the skip syntax, the ledger, the stale
  check, the twins; the counts by id in place of the free-text list.
- `docs/extending.md` (the script recipe): `skipif transformer <id>`, and
  adding a ledger row with its pin for a new rule.
- `docs/gotchas.md`: the ledger section (decision 1a).
- `docs/conventions.md`: every skip cites a ledger id, and a new rule gets a
  row and a pin in the commit that decides it.
- `docs/code-map.md` if `Runner.scala` grows past its hot spot.
- README: no user-visible change.

## Risks

- **A markdown table as build input** (1a) couples the doc's formatting to the
  test. Keep the table's columns fixed and the parse strict, failing with the
  line it could not read.
- **A coarse comparison can call a needed skip stale.** A DOUBLE checked as
  SQLite's `R` (three places) may pass by accident where the values differ past
  them. Value skips should compare exactly where the type allows; W3's failure
  message says which comparison passed.
- **Chained conditions.** A record with `skipif transformer` and `skipif mysql`
  must keep both; the rewrite touches only the transformer line.

## Launch prompt

```
Read plans/testing/08-skips-cite-decisions.md and implement it.

First check ListAgents and git status: another session may be editing this
tree. If so, work in a git worktree outside the repo (memory note
concurrent-sessions-worktree; agent-worktrees-break-bazel-all if one lives
under .claude/), and copy back after comparing against your base snapshot.

Follow CLAUDE.md's workflow: bazel test //... green, the jaffle-shop job 15/15
with every validation passing, and every stale doc updated in the same
commit. No new dependencies. Comments describe current behaviour.

Stop and ask before: (a) the plan's three decisions, if the user has not
answered them; (b) changing any expected answer in a vendored script (the
other engine's answers are never edited); (c) adding a ledger id for a rule
no recorded decision covers: that is a finding for the user, not a skip.

Report: the ledger's ids with their counts, how many skips each script has
by id, the stale skips the first run found (each one is news: say what
changed), the proof-of-detection result, and slt_test's time before and
after.
```
