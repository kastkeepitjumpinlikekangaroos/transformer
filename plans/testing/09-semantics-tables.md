# Plan 09: the engine's rules as tables: functions, casts and coercions

> Status: **done** 2026-11-02 (20254f4 W1, 876c6ea and 5012f9d W2, 909ec92 W3,
> cd291f5 W4; see [Outcome](#outcome); both open decisions taken overnight as
> recommended, not yet confirmed by the user) · from the 2026-11-01 test survey, category 6 of 9 ·
> Effort: 4-6 days · Risk: medium for W1, which moves every function's binding
> onto one table; low for the tables, which are tests · Depends on: 08 (a row's
> rule cites its decision id); pairs with 10, which checks the same cells
> against Spark's answers

## Why

Each typing and function rule is written in several places and pinned by
hand-picked cases. Nothing lists a rule's every case in one place, so a second,
unintended exception to a rule is invisible.

- **Functions.** 40 scalar functions (45 names) are a pattern match,
  `Funcs.signature` (`sql/plan/Funcs.scala:37-69`), that nothing can iterate.
  `MOD`, the clock keywords and the refused one-argument `LOG` bind beside it
  (`LogicalBuilder.scala:1365-1376`); 13 aggregates (19 names) bind in
  `bindAgg` (`:1389-1421`) beside a hand-kept name set (`AggFns`,
  `:1442-1447`); 21 window names are a third match (`:1563-1590`).
- **The README's lists are unchecked.** Its `**Aggregates:**`, `**Window
  functions:**`, `**Scalar functions:**` and `**Types in CAST:**` paragraphs
  are written by hand; no test reads them.
- **The generators draw hand-copied subsets.** `SqlTextGen.scala:944-951`
  lists 25 scalar functions, 10 aggregates and 8 CAST targets, and checks only
  binding or a clean refusal. `ExprGen`, whose answers are held on both
  evaluation paths, never draws 23 of the 40 functions (`POWER`, `CBRT`,
  `LOG`, `LOG10`, `LOG2`, the nine trigonometric and hyperbolic functions,
  `ATAN2`, `DEGREES`, `RADIANS`, `SIGN`, `PI`, `E`, `RAND`, `GREATEST`,
  `LEAST`), nor `MOD` (each name grepped in `ExprGen.scala`, 2026-11-01).
- **Casts.** `Casts.cast` (`Ops.scala:249-330`) is the row path, and
  `VecOps.cast` (`:820-834`) runs it row by row. `Analyzer.checkCast`
  (`Analyzer.scala:293-305`) refuses, and `parseTypeName`
  (`LogicalBuilder.scala:1894-1904`) names 8 targets. Cases are hand-picked in
  `SqlEngineTest` and `ExprBatchTest`, and `ExprGen.totalCastSources`
  (`ExprGen.scala:874`) draws only casts that do not throw. Nothing pairs every
  source with every target.
- **Coercions.** Three rules decide where two types meet:
  - `Analyzer.unify` (`Analyzer.scala:94`): comparisons, `BETWEEN`, `IN`,
    `NULLIF` and a `CASE` switch.
  - `Analyzer.branches` (`:111`): `CASE` values, `COALESCE`, `IF`,
    `GREATEST`, `LEAST`, the `LAG`/`LEAD` default, and a `UNION` column via
    `unifyArms` (`LogicalBuilder.scala:286`).
  - `Analyzer.arithmetic` (`:241`).

  They differ on purpose in one place: a DECIMAL beside a DOUBLE column
  compares, but cannot become one result with it (`SqlEngineTest.scala:3171`).
  plans/robustness/09's tests are hand-picked pairs (`:3122-3283`).

What a table would put side by side today (checked 2026-11-01 with the CLI jar
built from f309205):

| Cell | Answer |
|---|---|
| `SIGN(CAST('NaN' AS DOUBLE))` | error: `SIGN of NaN is not defined; ...` |
| `SQRT(CAST('NaN' AS DOUBLE))`, `FLOOR(CAST('NaN' AS DOUBLE))` | NaN |
| `SQRT(-1.0)`, `LN(0)`, `1 / 0`, `1.0 / 0` | NULL |
| `CAST(CAST('NaN' AS DOUBLE) AS INT)` | error: `Cannot cast NaN to INT: it has no integer value` |
| `CAST(16777217 AS INT) = CAST(16777216 AS FLOAT)` | TRUE |
| `COALESCE(CAST(NULL AS FLOAT), 2147483647)` | the FLOAT 2.1474836E9 |

- **Two rules for "no answer"**: NULL where no number answers
  (plans/testing/05, W9), an error where an answer exists but its type cannot
  hold it. A table states both, and would show a third.
- **INT or BIGINT beside FLOAT is a FLOAT** in all three families
  (`DataType.widerNumeric` in `core/DataType.scala`), against the README's
  "Values that become one result take a type that holds each of them". Plan
  10 found it with Spark's answers and holds the decision; these tables pin
  the outcome.
- **Gaps between aggregates and windows**: `COVAR_SAMP`, `COVAR_POP` and
  `CORR` are aggregates but not window functions; `SUM(DISTINCT)` and
  `AVG(DISTINCT)` run as aggregates and are refused in a window
  (`UnsupportedClauses.scala:405-409`). Documented, but only a registry lists
  such gaps together.

## What exists now

- slt: `functions/null_arguments.test` (every function over NULL arguments,
  DuckDB's `verify_functions`), `refused/messages.test` (refusals word for
  word), `types/*.test` (decimals, integer overflow, float equality).
- fuzz: `ExprParity` (`eval` against `evalVec`), `ExactIntegers` (integer
  operators against `BigInt`), `CleanRejection` (typing queries refused at bind
  time or failing cleanly).
- Golden precedent: `src/test/slt/plan/`, scripts filled by `Corpus
  --complete` (`slt/Corpus.scala:20`).

## What to build

### W1. The function registry as data

- **One table in `sql/plan`** (`FunctionCatalog`), one entry per function. Each
  rule names its id in plan 08's ledger:
  name and aliases; kind (scalar, flow, aggregate, ranking, offset, clock);
  arities and each argument's `Funcs.Param`; result-type rule (fixed, first
  argument's, `Rounding.resultType`, the branch rule, SUM's and AVG's decimal
  rules); NULL rule (NULL in gives NULL out, skips NULLs, or flow); domain
  (non-NULL arguments that give NULL); declared run-time errors (`SIGN` of
  NaN, `ABS` of the smallest INT); whether it is deterministic, has a vector
  path (`Funcs.applyVec`), runs as a window function, and honours `DISTINCT`.
- **The binder reads it.** `Funcs.signature` becomes a lookup; `bindAgg`,
  `AggFns` and the window match take names and arities from the catalog; `MOD`
  and the clock keywords keep their own binding, as entries whose kind says so.
  No message changes (`refused/messages.test` and `SqlEngineTest` pin them).
- **The README is held to it.** `sql/plan:function_catalog_test` reads the four
  README lists by their bold labels (never by line; another session edits the
  README) and checks both directions: every registered name and alias is
  documented, and every documented name is registered. The root BUILD exports
  README.md for this test.
- **The generators read it.** `SqlTextGen`'s typing lists and `ExprGen`'s
  function producers draw from the catalog by argument kind, so a new function
  is fuzzed the day it lands. `expr_parity_fuzz_test` gets a guard that every
  scalar entry is drawn.
- **Done when:** no function name is matched as a string in the binder outside
  the catalog, the README, binder and generators agree by test, and no message
  changes.

### W2. The cast matrix

- **Sources:** a column of each of the ten types (DECIMAL at (5,2) and
  (38,10)), the untyped NULL, a string literal, an integer literal and a
  decimal literal.
- **Targets:** the 8 CAST targets, plus `DECIMAL(10,2)` and `BINARY`, which are
  refused today. Plan 10 may add DECIMAL; the table then shows the new column
  as a diff.
- **Edge values:** NULL, 0, -0.0, NaN, the infinities, 2.7, -2.7, 2.5; each
  integer type's MIN and MAX and 16777217 (the first INT a FLOAT loses);
  DECIMAL(38,0)'s largest and DECIMAL(38,38)'s smallest; the first and last
  DATE and TIMESTAMP; a few bytes of BINARY; the empty string and text that is
  almost a value (`' 5'`, `'5d'`, `'1e400'`, `'0x10'`, `'+5'`, `'007'`,
  `'nan'`, `'TRUE'`, `'true '`, `'2026-02-30'`, `'2026-01-01 05:30:21+02:00'`).
- **A cell** is the result type and its value as slt prints an exact column
  (`Values.exact`), or the error, marked bind or run, with its message, from
  `eval` on a row and `evalVec` on a batch (as `ExprBatchTest.assertParity`
  does). Done when every cell has a line, the paths agree, and every error is
  clean by `CleanRejection`'s rule.

### W3. The coercion matrix

- **Operand kinds:** W2's sources. A number written in the query binds
  differently beside a DECIMAL, so the literal kinds stay.
- **Constructs, by family:** comparison (`=`, `<`, `BETWEEN` with each pair as
  operand and as bound, `IN`, `NULLIF`, a `CASE` switch); branch (`CASE`
  `THEN`/`ELSE`, `COALESCE`, `IF`, `GREATEST`, `LEAST`, the `LAG`/`LEAD`
  default, a `UNION` column); arithmetic (`+ - * / %`, `MOD`).
- **Cells are read off the bound plan** (`LogicalBuilder.build` over one-column
  tables), with no data. A cell is the type the operands meet as (comparison),
  the result type (branch, arithmetic), or the refusal.
- **Layout:** one table per family, a row per ordered pair and a column per
  construct; refusal messages appear once under the table, each with a code.
  "Every construct in a family agrees" is then visible as identical columns.
- **Properties the test asserts:**
  - Within a family, every construct gives one cell per pair. `NULLIF` returns
    its first operand's type, so the comparison family's cell is the type the
    operands meet as.
  - A pair gives the same cell in either order, as do `THEN a ELSE b` and
    `THEN b ELSE a`.
  - Every expected difference between families is listed in the test with its
    decision id, and any other difference fails. Today the list is: a DECIMAL
    beside a non-literal FLOAT or DOUBLE compares but is refused in branches and
    arithmetic; and arithmetic declares a decimal's result type
    (`Decimals.resultType`) where branches take the holding type
    (`Decimals.holding`).

  Done when the three tables are golden and the properties hold.

### W4. The function sweep

- **Scalar functions.** For every scalar catalog entry, each argument runs over
  every operand kind and its W2 edge values, with the other arguments at a
  typical value. The two-argument numeric functions (`POWER`, `ATAN2`, `LOG`,
  `ROUND`/`TRUNC` scale, `SUBSTRING` position and length) also run over every
  pair of a small edge set. Variadic functions run at one, two and three
  arguments.
- **Aggregates.** Each runs per type over these data sets: empty, all NULL, one
  value, duplicates, NaN among numbers, both zeros, sums at BIGINT's edge, and
  DECIMAL(38,s) at its edge. Each runs as a GROUP BY aggregate and, where the
  catalog allows, over `OVER ()`, a ROWS frame and a RANGE frame.
- **A cell** is a value; a NULL explained by the entry's NULL or domain rule
  (an unexplained NULL fails); a bind refusal; or a declared run-time error,
  also present in `CleanRejection.DataErrors` and `ExprParity`'s owned set
  (the new-runtime-error checklist). Both evaluation paths, as in W2. Done when
  the sweep is golden and every NULL and error is explained by the catalog.

### Golden files and review (with decision 1's recommended form)

- **Files, test and update:** text under `src/test/semantics/` (`casts.txt`,
  `comparisons.txt`, `branches.txt`, `arithmetic.txt`, `functions-text.txt`,
  `functions-math.txt`, `functions-flow.txt`, `aggregates.txt`,
  `windows.txt`), each a few thousand lines at most.
  `sql/plan:semantics_tables_test` builds them in memory and fails with a
  unified diff. `bazel run //src/test/scala/com/transformer/sql/plan:semantics_tables
  -- --update` rewrites them in the workspace, as `Corpus --complete` rewrites
  scripts.
- **Line format:** one stable, sorted line per cell:

  ```
  CAST(x AS INT)   DOUBLE   2.7    -> INT 2
  CAST(x AS INT)   DOUBLE   NaN    -> run error: Cannot cast NaN to INT: it has no integer value
  CAST(x AS DATE)  INT      1      -> bind error: Cannot cast INT to DATE
  ```

- **Review:** a commit that changes a rule regenerates its tables in the same
  commit; the diff is the review, every cell the rule reaches and no other. The
  first generation is reviewed cell by cell against the README and the
  ledger. About fifty hand-picked cells per table also go under
  `src/test/slt/semantics/`, for the runner's verification modes and the plan
  round trip. The tables are identical under every whole-suite configuration,
  since no rule may read a layout, a zone or a locale.

## Decisions for the user

1. **The tables' form.** (a) **Text tables generated by a test, with an
   `--update` run target and a small slt companion (recommended)**: it reads as
   a table, a rule change is a one-line-per-cell diff, the family properties are
   assertions over the same data, and both evaluation paths are checked per
   cell. (b) slt scripts filled by `Corpus --complete`: the runner, its modes
   and the golden-plan precedent, but 5-10x longer, a change spread over many
   records, and no way to write the family property. (c) Scala assertions only:
   no diff to review.
2. **What the README says of them.** (a) **A test holds its lists to the
   catalog, and it links the tables as the complete reference
   (recommended)**: the prose explains, the tables give every case. (b) The
   test only. (c) Generate its lists from the catalog, rewriting prose the user
   edits by hand.

**Taken overnight as recommended, not yet confirmed by the user** (2026-11-02):
decision 1 (a), text tables generated by a test with an `--update` run target
and an slt companion under `src/test/slt/semantics/`; decision 2 (a),
`function_catalog_test` holds the README's lists to the catalog and the README
links the tables as the complete reference ("Every case, as a table").

## Decisions already made

- **No new dependency.** Everything is hand-built Scala (CLAUDE.md;
  `plans/testing/README.md`).
- **The plan pins behaviour; it does not change it.** A cell that contradicts
  the README or a decision is a finding to stop and ask about, with a
  recommendation. The strict-semantics note predicts answers; it does not
  replace questions. INT/BIGINT beside FLOAT is plan 10's decision 2.
- **The rules stated are decided already:** NULL in gives NULL out
  (`null_arguments.test`); outside a math function's domain is NULL
  (plans/testing/05 W9, 28a51a5); integer overflow is an error
  (plans/robustness/18); division by zero is NULL for every number type (third
  audit); text coercions are refused and every condition is a BOOLEAN
  (2026-10-18); one branch rule (2026-10-16); Spark's and SQL Server's decimal
  types (2026-10-04); literals are read as written (2026-10-30); text is read by
  CSV's grammar (plans/robustness/24); `CAST(boolean AS DOUBLE)` is 1.0 or 0.0
  (plans/robustness/10).
- **One catalog for every kind of function** (Claude's call): two catalogs
  would bring back the second list.
- **A run-time error a cell exposes** follows the new-runtime-error checklist
  in its own commit.

## Tests

- `sql/plan:function_catalog_test`: the README's lists against the catalog;
  every entry binds at each declared arity and is refused at every other with
  the catalog's message.
- `sql/plan:semantics_tables_test`: the golden diff, both paths per cell, the
  family and order properties, every NULL and error explained.
- `src/test/slt/semantics/*.test` under `slt_test`; the coverage guard in
  `expr_parity_fuzz_test`.
- Planted changes in a scratch worktree: text casting to BOOLEAN from `'1'`
  must fail `semantics_tables_test` with a diff of just the cells that rule
  reaches; a function dropped from the README must fail
  `function_catalog_test`.
- The whole suite under `--config=zone` and `--config=batch1`, unchanged.

## Docs

README (link the tables from "SQL features", per decision 2; fix any list the
catalog test finds wrong); docs/testing.md (targets, tables, update command,
review rule); docs/extending.md (a new function is one catalog entry and a
table regeneration); docs/architecture.md (the catalog, the three coercion
families); docs/conventions.md (a rule change regenerates its tables in the
same commit); docs/gotchas.md (each finding as decided); docs/code-map.md if a
hot spot's size changes.

## Risks

- W1 rebinds every function; a changed message is a user-visible regression,
  which the pinned messages and the frontend fuzzer's clean-rejection property
  watch for.
- A golden file freezes what it holds: review the first generation, never
  accept it wholesale.
- Size and time: tables under a few thousand lines, the test under a minute,
  argument pairs sampled rather than multiplied out.
- Another session often edits `LogicalBuilder.scala`, `Funcs.scala` and the
  README: work in a worktree and copy back after comparing to a base snapshot.

## Launch prompt

```
Read plans/testing/09-semantics-tables.md and implement it workstream by
workstream (W1 catalog, W2 casts, W3 coercions, W4 functions), one commit
each with its tests and docs. Use max effort.

Follow CLAUDE.md's workflow for every commit: bazel test //... green, the
jaffle-shop example 15/15 with every validation passing, stale docs updated in
the same commit. No new dependencies.

First run ListAgents and git status. If another session has uncommitted work
here, use a scratch git worktree and copy back after cmp against a base
snapshot (memory: concurrent-sessions-worktree; a worktree under .claude needs
a local .bazelignore).

Before W2, ask the plan's two decisions with AskUserQuestion, recommended
option first.

Stop and ask before: (a) changing any rule a cell shows, even to match the
README; each disagreement is a finding for the user, with a recommendation;
(b) changing any error message; (c) adding a dependency; (d) committing a
golden table you have not reviewed cell by cell.

Report: the catalog's size by kind, each table's line count, the findings
(cell, what the README or a decision says, your recommendation), proof that
each test fails on a planted change, and the commits.
```

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, a commit per workstream and
one for the oracle gap W2's first generation found. At each, `bazel test //...`
passed (69, 69, 70, 70 and 70 targets) and the jaffle-shop job gave 15/15
Succeeded with every validation passing. The finished tree passes the whole
suite under `--config=zone` and `--config=batch1` too, and
`semantics_tables_test` under `batch2`, `sharded`, `serial` and `hashzero`: no
table reads a layout, a zone or a locale.

**W1**, `20254f4`: `sql/plan/FunctionCatalog` holds 61 entries under 72 names:
35 scalar (40 names), 5 flow, 1 operator (`MOD`), 2 clock, 13 aggregate (19
names), 3 ranking and 2 offset functions, and the 8 CAST types under 16 names.
`Funcs.Param` gained `Branch`, `Compared`, `Condition` and `Operand`, so a
call's arguments bind as its parameters say (`LogicalBuilder.bindArguments`);
`bindAgg`, the window match, `UnsupportedClauses`' name sets, `PlanValidator`,
`Expr.readsRandom` and `parseTypeName` read the catalog, and no function name
is matched as a string in the binder. No message changed: each refusal's text
moved into the catalog. `function_catalog_test` found `RANDOM`, `STDEV`,
`STDEVP`, `VAR`, `VARP` and `DOUBLE PRECISION` undocumented and the window list
naming no aggregate, all fixed in the README. `ExprGen` draws its functions from
the catalog (it drew 17 of the 40 before; `everyCatalogFunctionIsDrawn` holds
all 40 to more than 10 of 40000 seeds), and `SqlTextGen`'s typing lists are the
catalog's names. The redrawn corpus moved three coverage guards' blocks (IEEE
4000 to 8000 seeds, types 20000 to 60000, decimal arithmetic 40000 to 60000),
each re-measured. Campaigns: frontend clean-rejection 20000 seeds and expr
parity 200000, green.

**W2**, `876c6ea` and `5012f9d`: the cast matrix's first generation found
`CleanRejection.DataErrors` missing the DATE cast's advice (`Cannot cast '...'
to date; cast it to TIMESTAMP first, ...`), so a frontend case reaching it would
have been reported as a rejection that waited for the data; it is added in its
own commit, as the new-runtime-error checklist asks. Then `casts.txt` and the
machinery: `Semantics`, `CastMatrix`, `SemanticsTables` (`--update`) and
`semantics_tables_test`, which fails with a unified diff (patience matching on
the lines both sides hold once).

**W3**, `909ec92`: `comparisons.txt`, `branches.txt`, `arithmetic.txt`. Every
construct of a family gives one cell per pair (arithmetic's operators but on a
DECIMAL), either order gives one cell, and the families differ only by
`decimal-beside-float` (16 pairs), `decimal-result-type` (48),
`arithmetic-on-numbers` (52) or the held finding `integer-beside-decimal` (4).

**W4**, `cd291f5`: `functions-text.txt`, `functions-math.txt`,
`functions-flow.txt`, `aggregates.txt`, `windows.txt`. Every NULL is explained by
its entry's NULL rule or domain and every run-time error is one its entry
declares; neither needed a catalog change, and no run-time error was new.

**Tables** (9557 lines, generated in about 4 s): `casts.txt` 900,
`comparisons.txt` 314, `branches.txt` 309, `arithmetic.txt` 253,
`functions-text.txt` 853, `functions-math.txt` 3404, `functions-flow.txt` 1116,
`aggregates.txt` 782, `windows.txt` 1626. Every cell of every table was reviewed
by hand against the README and the ledger before its commit. The slt companions
are `src/test/slt/semantics/casts.test`, `coercions.test`, `functions.test` and
`aggregates.test`.

**Proof of detection**, each planted in the work tree and reverted: dropping
`CBRT` from the README fails `function_catalog_test` ("CBRT (scalar) is not in
README's **Scalar functions:** list"); text `'1'` cast to TRUE fails
`semantics_tables_test` with a diff of exactly the two cells the rule reaches
(`STRING '1'` and `text literal '1'` to BOOLEAN); narrowing `SQRT`'s domain fails
it naming 11 unexplained NULLs, and dropping `SIGN`'s declared error naming the 2
cells that raise it.

**Findings**, each reproduced on the base engine (958147b) and none changed; the
cell, what the README or a decision says, and a recommendation:

1. **`-2147483648` written in a query is a BIGINT** (`CAST(-2147483648 AS DATE)`:
   `Cannot cast LONG to DATE`; `casts.txt`'s integer-literal rows), since the
   minus is a negation over 2147483648. README and the ledger's `int-is-int`: a
   whole number an INT holds is an INT. Recommend reading it as the INT, as
   `IntegerLiteral` reads `-9223372036854775808` as the BIGINT.
2. **An INT or BIGINT beside a FLOAT is a FLOAT, a BIGINT beside a DOUBLE a
   DOUBLE**, in all three families (`LEAST(16777217, 16777216.0)` is
   1.6777216E7 in `coercions.test`). README: values that become one result take
   a type that holds each of them. Plan 10's decision 2 holds it.
3. **`integer-beside-decimal`**, held in the test: a comparison types an INT or
   BIGINT beside a DECIMAL as that DECIMAL (`DECIMAL(5,2)`), reading the integer
   exactly, while a branch takes the DECIMAL that holds both (`DECIMAL(12,2)`).
   No decision covers it, and the answers are exact either way. Recommend
   recording it as by design.
4. **`FLOOR` and `CEIL` of a FLOAT read its binary value**, `ROUND` and `TRUNC`
   the decimal it prints as: `FLOOR(CAST(3.4028235E38 AS FLOAT))` is
   3.4028234663852886E38, `ROUND` of it 3.4028235E38. README: a FLOAT is "rounded
   as the decimal it prints as". Recommend FLOOR and CEIL read a FLOAT so too
   (`Rounding.apply` floors `doubleValue`).
5. **`ROUND` and `TRUNC` of -0.0 are 0.0**, `FLOOR` and `CEIL` keep -0.0. No
   decision. Recommend keeping the sign, as C and Postgres do.
6. **`LOG` with a NaN beside an argument outside its domain is NULL**
   (`LOG(0, NaN)`, `LOG(NaN, 0)`), where the README says NaN stays NaN; and
   `LOG(Infinity, Infinity)` is NaN from no NaN. Recommend NaN first, as `SQRT`
   and `LN` have it, and the indeterminate case NULL, or a README sentence.
7. **`RAND(seed)` reads only a seed's low 48 bits** (`java.util.Random`):
   `RAND(0) = RAND(-9223372036854775808)` and `RAND(1) = RAND(9007199254740993)`.
   No decision. Recommend mixing the whole seed, or documenting it.
8. **`CONCAT()` binds and answers `''`.** No decision; Postgres and MySQL refuse
   it. Recommend refusing it, as `COUNT()` is.
9. **Messages name BIGINT `LONG`** (`Cannot cast DATE to LONG`, `Cannot compare
   LONG with STRING`, `LENGTH on LONG is not supported`), and text casts name
   types by their internal names (`Cannot cast '' to long`). Recommend BIGINT,
   `DataType.sqlName`; a message change waits for the user.
10. **A FLOAT past INT is named by its DOUBLE expansion**: `Integer overflow:
    3.4028234663852886E38 does not fit INT` for the FLOAT 3.4028235E38. Recommend
    the FLOAT's own text.
11. **`price + '1'` over a DECIMAL says `Cannot compare DECIMAL(5,2) with
    STRING`**, a comparison's words for arithmetic. Recommend arithmetic's own.
12. **`CAST(x AS DECIMAL(10,2))` is refused naming `DECIMAL (10, 2)`**, as
    JSqlParser renders it; plan 10's W1 replaces the refusal.
13. **`ABS(NULL)` and the flow functions over NULL alone are typed NULL**, where
    `ROUND(NULL)` and the math functions are a DOUBLE. Minor; no decision.
14. **Rules the catalog states that differ from other engines but have no row
    in the ledger**: `SIGN` of NaN an error (Postgres: NaN), `LOG(x)` and
    `COUNT()` refused, `CONCAT` NULL in NULL out (Postgres skips NULLs), and a
    `SUBSTRING` position before the first character counting from the first
    (`SUBSTRING('abc', 0, 2)` is `ab`, Postgres `a`). Each is stated in the
    README. Recommend a ledger row when a port first skips one.

A statistic's last digits differ by layout (`STDDEV_SAMP` over `[MAX, 1, -1]`
is 5.325116328314172E18 over one partition, ...171E18 over a row per partition),
as its merge order differs; the README's "Welford + Chan's parallel merge" and
plan 08's `VARIANCE` note already say so, so the sweep compares them to twelve
digits.

**Decisions, taken overnight as recommended, not yet confirmed by the user:**
1 (a), text tables with an `--update` target and an slt companion; 2 (a), the
README's lists held to the catalog and the tables linked as the complete
reference.

**Claude's calls within them** (revisit if the user objects): the catalog owns
each refusal's text; `MOD` is an entry of kind `operator`; the CAST type names
live in the catalog; `ExprGen` draws `IF` twice as often as the other flow
functions, since its condition is where comparisons come from; `ExprParity.owned`
is public, for the sweep; a table quotes a STRING, writes `(any)` for every
value of a kind refused alike, codes a coercion refusal by its message's
digest and writes a branch refusal's construct as `<construct>`; a text literal
in the coercion tables is one the other operand's type reads; flow functions
are swept at five values a kind and `COUNT(*)` over one type; an exponential, a
logarithm, a power or a trigonometric function's DOUBLE is shown to 15
digits, marked `~`, since Java's `Math` may round its last bit otherwise on
another platform; an aggregate's two paths are one partition and a row per
partition; the cross-family check reads arithmetic's `+`.
