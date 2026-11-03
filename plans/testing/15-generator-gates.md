# Plan 15: the fuzz generators draw what they leave out today

> Status: **done** 2026-11-02 (3c8ee89 W1, 394b70e decision 2, 96ca67f W2,
> 2ba017f W3, 7606c78 W4, 8065a8d W5, 50e67c3 W6, 49b1760 W7; see
> [Outcome](#outcome); both decisions taken overnight as recommended, not yet
> confirmed by the user; finding F1 held for the user) · from the 2026-11-01 test survey, category 5 of 9 ·
> Effort: 3-5 days · Risk: low for the generators; medium for what they find
> (one finding is already confirmed below) · Depends on: none.
> [06](06-a-failed-run-leaves-no-partial-output.md) opens the DAG fuzzer's
> failure gate, and [16](16-literature-oracles.md) builds on the join and
> window shapes opened here

## Why

Every fuzz property holds the engine to a relation over what its generator
draws. A value or a shape no generator draws is one no property checks, and
the suite stays green over it. It has happened twice: `QueryGen` kept
BOOLEAN out of MIN/MAX over a spill defect, and `ExprGen` kept NaN out of
comparisons over a parity defect (fuzzer-generator-gates memory note).

The 2026-11-01 survey read every exclusion in
`src/test/scala/com/transformer/fuzz/`. The widest is text: the SQL corpora
draw ASCII only (`DataGen.scala:219-220`, `MetaQueryGen.scala:469`,
`ExprGen.scala:144-146`, `SqlTextGen.scala:71-73`), while the engine's string
rules are about everything else: LENGTH and SUBSTRING count code points (the
third audit), strings order by UTF-16 code unit (`Ops.cmp`, `Ops.scala:64`),
UPPER and LOWER map case by Unicode's full rules (`Funcs.scala:89-91, 373`)
and ILIKE folds case a character at a time (`Expr.scala:816-818`). Only the
I/O generators reach past ASCII (`CsvTextGen.scala:113-122`,
`ParquetGen.scala:363-382`).

That gate already hides a defect. Measured on 2026-11-01 with the CLI jar,
over a CSV column holding U+00DF (sharp s), U+0130 (capital I with dot above),
`stra` U+00DF `e`, and `ab`:

| s | `s ILIKE 'SS'` | `UPPER(s) LIKE UPPER('SS')` | `s ILIKE '_'` | `LOWER(s) LIKE LOWER('_')` | `LENGTH(s)` | `LENGTH(UPPER(s))` | `LENGTH(LOWER(s))` |
|---|---|---|---|---|---|---|---|
| U+00DF | false | **true** | true | true | 1 | **2** | 1 |
| U+0130 | false | false | true | **false** | 1 | 1 | **2** |
| `stra` U+00DF `e` | false | false | false | false | 6 | **7** | 6 |
| `ab` | false | false | false | false | 2 | 2 | 2 |

UPPER is Java's `toUpperCase(Locale.ROOT)`, which maps U+00DF to `SS`, and
LOWER maps U+0130 to U+0069 U+0307; ILIKE compiles a regex with
`CASE_INSENSITIVE | UNICODE_CASE`, which compares one character with one. So
`s ILIKE p`, `LOWER(s) LIKE LOWER(p)` and `UPPER(s) LIKE UPPER(p)` answer
differently, and a string's length changes under UPPER. The frontend fuzzer's
"ILIKE" spelling shape (`SqlTextGen.scala:795`) holds those three spellings
to one answer. It passes because its pool is ASCII. README says UPPER and
LOWER "map case by Unicode's own rules" (`README.md:1341-1344`) and calls
ILIKE LIKE's case-insensitive form (`:1356`); neither says the two differ.

Two more gates rest on reasons that no longer hold:

- **TLP never partitions on a FLOAT or DOUBLE column**, "so a comparison is
  UNKNOWN exactly when an operand is NULL (no NaN can fall into none of the
  three partitions)" (`oracle/Tlp.scala:31`, `MetaQueryGen.isPartitionable`,
  `:1022-1029`). Since plans/robustness/21 a float has one equality and one
  order (`README.md:1578-1590`): a comparison of two non-NULL floats is TRUE
  or FALSE.
- **`QueryGen` never draws TRIM**: "TRIM parses to a TrimFunction node the
  binder rejects" (`QueryGen.scala:465`). `TRIM(x)` binds since
  plans/followups/02 (2026-09-06; `README.md:1290`,
  `src/test/slt/functions/null_arguments.test:13`), and the third audit
  changed what it strips.

## What exists now

Every gate the survey found, by generator. A value gate limits the data a
shape sees; a shape gate limits the trees or queries built. Both kinds sit
far apart in a file, and opening one without the other is green and vacuous
(fuzzer-generator-gates memory).

| # | Generator | Gate | Where | What it hides | Why it is there |
|---|---|---|---|---|---|
| G1 | DataGen, MetaQueryGen, ExprGen, SqlTextGen | strings are ASCII | `DataGen.scala:219-220`, `MetaQueryGen.scala:469`, `ExprGen.scala:144-146`, `SqlTextGen.scala:71-73` | code-point LENGTH and SUBSTRING across layouts, case mapping, the ILIKE split above, LIKE's `_` on a character past U+FFFF, string order where U+E000-U+FFFF meets a pair past U+FFFF (`docs/gotchas.md:595-608`) | narrow pools so keys collide; nothing stated against non-ASCII |
| G2 | MetaQueryGen, Tlp | no float output column partitions TLP; no float GROUP BY key | `MetaQueryGen.scala:795-797, 1022-1029`, `oracle/Tlp.scala:31, 44` | TLP over float comparisons, NaN and -0.0 rows in all three partitions | NaN was neither TRUE nor FALSE; it is now |
| G3 | QueryGen | no TRIM | `QueryGen.scala:465` | TRIM's spaces-only rule in every layout and in the round trip | the binder refused TRIM; it no longer does |
| G4 | DataGen, MetaQueryGen | integer domains are a few values around zero | `DataGen.scala:248-256`, `MetaQueryGen.scala:550-551` | an overflow inside a join, a window, an aggregate or a sharded path | on purpose: an overflow would be filtered out of one TLP partition and not another (plans/robustness/18, Outcome); `oracle/ExactIntegers` covers trees and SUM in fixed shapes |
| G5 | DataGen | decimals are small: "no generated arithmetic overflows its declared type" | `DataGen.scala:276-296` | DECIMAL overflow in SUM, AVG and windows, and in the spill and shard paths; the AVG(DECIMAL) overflow past about 10^34 the third audit kept | the layout properties would have to reason about errors; `ExprGen`'s pools run to the edge but "nothing here sums or spills" (`ExprGen.scala:530-537`) |
| G6 | ExprGen (shared by DataGen, MetaQueryGen) | dates 1969-2024, instants around the epoch and 2024 | `ExprGen.scala:506-519` | the storable range's ends (plans/robustness/25) and years before 1 in operators; `CsvTextGen` draws year 10000, 0 and -5 (`CsvTextGen.scala:158-161`), so only CSV sees them | the pools were built for DST and sub-millisecond pairs |
| G7 | DataGen, MetaQueryGen | floats are small, plus NaN, -0.0 and the infinities on a quarter of columns | `DataGen.scala:217-218, 232-243` | overflow to an infinity and underflow to 0 inside aggregates | a float SUM near the range's end differs with row order (Infinity against a finite value), which no layout relation can call equal |
| G8 | ExprGen, QueryGen | only casts that cannot throw; a float reaches an integer cast only under `CASE WHEN ABS(x) < 1e9` | `ExprGen.scala:16-31, 862-888`; `QueryGen.scala:38-43, 407-420` | whether both evaluation paths fail alike on a failing cast, and failing casts inside IN, comparisons and aggregates | totality keeps the property "every row agrees, and neither path throws"; the one exception is the guarded cast of plans/robustness/17 |
| G9 | ExprGen, QueryGen, MetaQueryGen | no RAND | `ExprGen.scala:20`, `QueryGen.scala:44-46` | `RAND(k)` with a literal seed, which is the same value on every row (`Funcs.scala:194`) | unseeded RAND has no parity or layout meaning |
| G10 | QueryGen, MetaQueryGen, ExprGen | the math functions, GREATEST and LEAST are never drawn | compare `Funcs.signature` (`Funcs.scala:41-69`) with the producers (`QueryGen.scala:421-475`, `ExprGen.scala:600-760`) | the domain rule of plans/testing/05 (LN, LOG, SQRT, ASIN, ACOS, POWER outside their domain are NULL) in any layout, TLP or round-trip property; GREATEST/LEAST answers (only the frontend's typing mode draws them, for rejection, `SqlTextGen.scala:946-947`) | they take the row loop on both of `ExprParity`'s paths (`Funcs.applyVec` covers exactly what `ExprGen` draws), so parity cannot diverge; the SQL generators were never extended |
| G11 | MetaQueryGen | at most three relations; every ON an equality from scope (TRUE when nothing compares); JOIN ... ON only; keys never FLOAT, DOUBLE or BOOLEAN | `MetaQueryGen.scala:457-464, 545-548, 668, 690-707` | `JoinOrder` (it changes a plan only where a table has no equality to those before it), the pairing waves (a join with no equality), comma joins and CROSS JOIN with WHERE equalities, NaN keys in the metamorphic corpus (`oracle/KeyEquality` has fixed shapes) | "What a NaN KEY does is a separate question", answered since by plans/robustness/21 |
| G12 | MetaQueryGen | ROWS frames with offsets 0 and 1 only; no RANGE offset; LAG/LEAD without offset or default; window arguments bare columns | `MetaQueryGen.scala:733-736, 751, 785-787` | RANGE offsets in every layout (`oracle/WindowFrames` uses one fixed table), LAG's default under the branch rule (plans/robustness/09), windows over expressions | output types stay exact |
| G13 | MetaQueryGen | CTE bodies read base relations only; UNION arms project one bare column from one source | `MetaQueryGen.scala:567-570, 609-634` | a CTE over a CTE (materialized or inlined twice), UNION of computed and windowed arms | resolution order stays simple |
| G14 | SqlTextGen, QueryGen | spellings and honoured clauses run over `t(a INT, b INT, s STRING)` and `u(a INT, b INT)`; IN never over a float column | `SqlTextGen.scala:57-59`; `QueryGen.scala:628` | every equivalent spelling over DECIMAL, DOUBLE, DATE, TIMESTAMP and BINARY columns; `IN` with NaN at the SQL level | the clause table's examples are written against that catalog; the IN gate states no reason |
| G15 | QueryGen | no unary minus; NOT only over a boolean leaf | `QueryGen.scala:423, 600-604` | negation of a computed value, NOT over AND and OR inside an expression | rendering without parentheses, which `SqlRender` already gives decimal arithmetic |
| G16 | JobDagGen | a failure is thrown before the engine runs | `JobDagGen.scala:420-447` | a task failing mid-drain or mid-write | plan [06](06-a-failed-run-leaves-no-partial-output.md) opens it, with fault points at every write |

`CsvTextGen` and `ParquetGen` need no change: they already draw extremes,
non-ASCII text and every layout their format allows.

## What to build

In the order below: the stale gates first, then the text pool, whose finding
is already known, then the shapes plan 16 needs, then the edges, which need
decision D1.

### W1. Remove the stale gates (G2, G3, G11's key types, G14's IN gate)

TLP partitions on float output columns, `MetaQueryGen` draws float GROUP BY
keys and FLOAT, DOUBLE and BOOLEAN join keys, `QueryGen` draws TRIM and IN
over float columns. Each removal adds a coverage guard asserting the shape and
its values (a NaN row reaching a float partition predicate; TRIM over a value
with spaces at an end). Re-measure every existing guard afterwards, since
widening a corpus dilutes the old ones, and grow the seed block rather than
lower a bound (memory note). Done when: these gates are gone, the guards
hold, and the default suite is green.

### W2. A non-ASCII string pool for the SQL corpora (G1)

One shared pool, used by `DataGen`, `MetaQueryGen`, `ExprGen` and the frontend
catalog, holding ASCII neighbours of each class so values still collide:

| Class | Code points | Why |
|---|---|---|
| two UTF-8 bytes, one UTF-16 unit | U+00E9, U+00FC | the baseline: anything that counts bytes |
| case maps that change length | U+00DF (UPPER is `SS`), U+0130 (LOWER is two code points), U+FB01 (UPPER is `FI`) | the confirmed ILIKE split; `LENGTH(UPPER(s))` |
| locale-sensitive case pairs | U+0049, U+0069, U+0131 | must not move under `--config=zone`'s tr-TR |
| a combining mark after a letter | U+0065 U+0301 | two code points, one character on screen: LENGTH is 2 and SUBSTRING may split them, by the code-point rule |
| spaces TRIM keeps | U+00A0, U+0009, U+2003 | the spaces-only rule of the third audit |
| UTF-16 order against code point order | U+E000, U+FFFD beside U+1F600 | UTF-16 sorts the pair below them, code points above (`docs/gotchas.md:599-608`); ORDER BY, MIN/MAX, window order and RANK stay one order in every layout and through spill |
| past U+FFFF | U+1F600, U+1D11E | one code point, two UTF-16 units: LENGTH 1, LIKE `_` matches one, SUBSTRING never splits the pair |
| fullwidth | U+FF21 (LOWER is U+FF41) | case mapping outside Latin-1 |

Not drawn: half a surrogate pair. The parquet writer, and so spill, writes one
as `?` (`ParquetWriter.scala:368`, parquet-mr's `Binary.fromString`), while
`CsvWriter` refuses it; one in the corpus would fail the spill mode on that
alone. What a STRING may hold is plan [17](17-whole-suite-runtime-checks.md)'s
decision D2, and the pool follows it.

Write the pool as code points (`new String(Character.toChars(0x1F600))`,
`s"${0xdf.toChar}"`), never as a backslash-u escape: the editing tools decode
such an escape into the raw character (unicode-escapes-get-decoded memory),
and the repo's source is ASCII. After editing, scan the diff for non-ASCII
with perl (`git diff -U0 | grep '^+' | perl -ne 'print if /[^\x00-\x7F]/'`);
macOS `grep` has no `-P`.

A coverage guard asserts each class reaches LENGTH, SUBSTRING, UPPER, LOWER,
LIKE, ILIKE, ORDER BY, GROUP BY and MIN/MAX on a share of seeds, in the
mode-differential, metamorphic and frontend corpora. Done when: the guard
holds, and every property is green or each disagreement is decided (D2 is the
one known now).

### W3. Functions the SQL corpora never draw (G9, G10)

`QueryGen` and `MetaQueryGen` draw POWER, CBRT, two-argument LOG, LOG10,
LOG2, the trigonometric and hyperbolic functions, ATAN2, DEGREES, RADIANS,
SIGN, PI, E, GREATEST, LEAST, MOD and `RAND(k)` with a literal seed, each with
arguments at its domain's edges (0, a negative, 1, -1, the infinities, NaN)
where a column's domain carries them. The list is checked against
`Funcs.signature` by a test that fails when the registry accepts a name the
generators do not draw. Plan [09](09-semantics-tables.md) turns the registry
into data; once it lands the generators draw from it and the list goes.
Coverage guard: each function on a share of seeds, and NULL-from-domain rows
reaching a TLP partition and a GROUP BY key.

### W4. Join, window, CTE and UNION shapes (G11, G12, G13)

`MetaQueryGen` draws two to six relations; FROM written as JOIN ... ON, as
a comma list with WHERE equalities, and as CROSS JOIN with WHERE equalities;
an ON with no equality over the corpus's small tables (the nested-loop guard
never refuses at 24 rows); orders in which a table has no equality with the
ones before it, so `JoinOrder` moves it. Windows draw RANGE frames with
offsets over the key types RANGE takes, LAG and LEAD with an offset and a
default, and an expression as an argument or a key. A CTE may read a CTE; a
UNION arm may be any core. After the change, probe 20000 seeds for binds and
read the rejection tally (the SQL fuzzers count bind rejections rather than
fail on them, so a generator gone wrong thins the corpus silently; memory
note). Coverage guards: a run of three or more inner tables that `JoinOrder`
reorders, a join on the pairing path, each frame kind, a CTE over a CTE.

### W5. Every type in the frontend catalog (G14)

The frontend catalog gains DECIMAL, DOUBLE, DATE, TIMESTAMP and BINARY
columns, and the spelling shapes that read them: BETWEEN over a DECIMAL and a
DATE, IN over a DOUBLE holding NaN and -0.0, the conditionals over a DECIMAL
beside an INT, ORDER BY NULLS LAST over a TIMESTAMP. The honoured-clause
contexts run over the new columns where the clause takes them.

### W6. Rendering without the old limits (G15)

`SqlRender` parenthesizes a unary minus and a NOT over a compound predicate
(the parser refuses `- -3` and misreads `NOT NOT a >= b`; both get clear
refusals, since plans/testing/04 and plans/robustness/12), and `QueryGen` draws both.

### W7. Values at the edges in the layout corpora (G4-G8)

A share of columns draws its type's edges: INT and BIGINT at and one inside
their limits, decimals at the end of their precision, dates and instants at
the storable range's ends and before 1970, and `ExprGen` draws casts that can
fail under `ExprParity.check`'s rule that both paths fail alike. The layout and
metamorphic oracles learn the error rule of decision D1. Every error they now
accept is one `CleanRejection.DataErrors` lists (`oracle/CleanRejection.scala:178-188`)
and `ExprParity`'s owned set allows (new-runtime-error-checklist memory). The
float edges of G7 stay closed, by its reason. Coverage guard: an overflow, a
decimal past its precision and a date past its range each reach a join, a
window, an aggregate and spill.

### Proving detection

Each workstream shows its corpus catches a defect it was blind to, with the
defect put back in a copy of the main files (reset with `git checkout HEAD --`,
then restored; memory note): W1, `Ops.floatCompare` made IEEE's; W2, the ILIKE
split itself; W3, SQRT of a negative NaN again; W4, `JoinOrder` re-indexing the
third table off by one; W7, one SUM merge path back to plain `+`.

## Decisions for the user

1. **What the layout and metamorphic properties require of a query that can
   fail on its data** (W7).
   - (a) **(recommended)** Every layout must agree on whether a query fails
     and, when it answers, on the answer, except where the failure comes from
     a WHERE or ON condition the optimizer may move. README says to guard such
     an expression with a CASE, "the optimizer may move one condition of a
     WHERE ahead of another" (`README.md:1367-1374`). TLP checks nothing when
     Q fails, since a partition's filter pushed into Q may skip the failing
     rows; when Q answers, every partition must answer and add up to it. This
     holds the engine to what it promises and no more.
   - (b) The same, and a moved condition must never fail where the written
     order would not. Stricter than Postgres or Spark promise. It would forbid
     pushing a condition that can fail below a join, which is a planner
     change.
   - (c) Keep the edges out of the layout corpora; widen only the point
     oracles (`ExactIntegers`, `WindowFrames`).

   **(a), taken overnight as recommended, not yet confirmed by the user.**
2. **Case rules once text is not ASCII** (W2, the confirmed split).
   - (a) **(recommended)** UPPER and LOWER map one character to one
     character (Unicode's simple case mapping: U+00DF stays U+00DF, U+0130
     lowers to U+0069). A string keeps its length, and ILIKE, `LOWER(s) LIKE
     LOWER(p)` and `UPPER(s) LIKE UPPER(p)` agree. Postgres's default libc
     collations behave this way, and SQL's character functions then agree with
     each other, as the code-point rule made LENGTH and SUBSTRING do.
   - (b) ILIKE matches as `LOWER(s) LIKE LOWER(p)`, with full case mapping on
     both sides, which is how Spark defines `ilike`. UPPER('U+00DF') stays
     `SS`.
   - (c) Keep both rules, document the difference, and drop the spelling
     shape's UPPER and LOWER spellings.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
   One split remains, which (a) does not settle: finding F1 below.

## Decisions already made

Generators are hand-rolled, with no new dependency (plans/testing/README). A
gate whose defect is fixed is removed, and a re-enabled shape gets a guard over
its shape and its values (fuzzer-generator-gates memory). A run-time error the
corpus can now reach joins `CleanRejection.DataErrors` and `ExprParity`'s owned
set in the same commit (new-runtime-error-checklist memory). A disagreement is
settled by choosing one rule, not by loosening an oracle
(prefer-strict-sql-semantics memory).

## Tests

Coverage guards for each gate opened, bind-rejection probes at 20000 seeds
after W1, W4, W5 and W6, the detection proofs above, and a campaign of each
fuzz target at its usual budget afterwards: build, `bazel shutdown`, run the
launchers from `<target>.runfiles/_main` with `FUZZ_SEED` and `FUZZ_SEEDS`,
three JVMs at `-Xmx1200m`, `df -h` first (fuzz-campaign-parallel-blocks
memory). Every finding gets a regression in `SqlEngineTest` or an slt script.

## Docs

`docs/testing.md` (what each generator draws, the guards, the pool);
`docs/gotchas.md` (D2's outcome; its entry saying a CSV file writes half a
surrogate pair as `?`, `:2006-2012`, is stale, since `CsvWriter` refuses it);
`README.md` (UPPER, LOWER and ILIKE, if D2 changes them); `docs/extending.md`
(adding a value class to a pool: its guard and the error checklist);
`docs/conventions.md` (test pools are written as code points, never as
backslash-u escapes); `docs/architecture.md` (the oracles' error rule, if D1
adds one).

## Risks

Widening dilutes the existing guards, and a generator change can collapse
the corpus into bind rejections that only the 20000-seed probes see. Each
opened gate can surface findings that need the user (D2 is one; expect more
from W2 and W7). The default budgets stay as they are; volume goes into the
campaigns.

## Launch prompt

```
Read plans/testing/15-generator-gates.md and implement it, workstream by
workstream, in the order given (W1 first).

First check ListAgents and `git status`: another session may hold uncommitted
work in this tree. If so, work in a scratch git worktree outside the repo and
copy your files back after comparing against a base snapshot
(concurrent-sessions-worktree memory).

Follow CLAUDE.md for every change: `bazel test //...` green, the jaffle-shop
example 15/15 with every validation passing, and every stale doc updated in
the same commit. Keep commits one per workstream; a fix to src/main that a
widened corpus finds gets its own commit with its regression.

Stop and ask before: decision D1 (before W7) and decision D2 (as soon as W2
reproduces the ILIKE split); any engine finding that is a semantic choice
rather than a defect; adding any dependency; lowering a coverage guard's
bound instead of growing its seed block.

Report: which gates are open, each guard's measured rate, the bind-rejection
tally at 20000 seeds, each detection proof, and every finding with its repro.
```

## Findings

| # | Finding | Minimized repro | What became of it |
|---|---|---|---|
| D2 | The split the plan confirmed: `UPPER` mapped U+00DF to `SS` and `LOWER` mapped U+0130 to two code points (Java's full case mapping), so `LENGTH(UPPER(s))` changed and `UPPER(s) LIKE UPPER('SS')` matched a sharp s that `s ILIKE 'SS'` did not. | `SELECT UPPER('<U+00DF>'), LENGTH(LOWER('<U+0130>'))`: `SS` and 2 | Decision 2 (a), `394b70e`: `Funcs.upper` and `Funcs.lower` map each code point by Unicode's simple mapping on both paths. `SqlEngineTest.upperAndLowerMapOneCharacterToOne` fails at its base (`SS`, an i with a combining dot, `FI`, 7). Four cells of `src/test/semantics/functions-text.txt` moved: `UPPER('<U+00DF>')` is `<U+00DF>`, not `SS` (STRING and text literal), and `LOWER('<U+0130>i')` is `ii`, not U+0069 U+0307 `i` (STRING and text literal); the record `SELECT LENGTH(...), UPPER('<U+00DF>'), LOWER('<U+0130>'), CONCAT()` of `slt/semantics/functions.test` reads `3 <U+00DF> i (empty)`, not `3 SS i<U+0307> (empty)`. README and gotchas.md say so. |
| F1 | What decision 2 leaves: `ILIKE` folds a letter to the small form of its capital, where `LOWER` and `UPPER` map it alone, so the three case-insensitive spellings part over a letter whose capital and small forms are not one pair. Over a dotless i (U+0131), `ILIKE 'i'` and the `UPPER` spelling are TRUE and the `LOWER` spelling FALSE; over a dotted capital I (U+0130), `ILIKE 'i'` and the `LOWER` spelling are TRUE and the `UPPER` spelling FALSE. | `SELECT '<U+0131>' ILIKE 'i', LOWER('<U+0131>') LIKE LOWER('i'), UPPER('<U+0131>') LIKE UPPER('i')`: true, false, true; the same over U+0130: true, true, false (the CLI at `49b1760`) | Held for the user: a `SyntaxEquivalence.Known` (`IlikeOverUnpairedCase`) with `onlyWhere`, so the ILIKE shape is excused over such a letter only, and `theIlikeDisagreementIsKnownOnlyOverAnUnpairedLetter` holds it to its own values; README and gotchas.md state the residue. A rule to choose: `ILIKE` as `LOWER(s) LIKE LOWER(p)` (Postgres's), as `UPPER(s) LIKE UPPER(p)`, or the fold kept and stated. |

No other engine finding came up. The generators' own gaps the guards found
on the way: W3's `everyCatalogFunctionIsDrawn` found `QueryGen` never drew
`TRUNC` and `MetaQueryGen` never drew `NULLIF` or the rounding functions;
W7's `AggDecomposition` met a global aggregate's failure on its data thrown
while its query was planned, which the oracles caught only around the drain
(now `RelEngine.answer`, gotchas.md).

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, one commit per workstream
and one for decision 2, in the plan's order: W1 `3c8ee89`, decision 2
`394b70e`, W2 `96ca67f`, W3 `2ba017f`, W4 `7606c78`, W5 `8065a8d`, W6
`50e67c3`, W7 `49b1760`. At each, `bazel test //...` passed (74 targets) and
the jaffle-shop job gave 15/15 Succeeded with every validation passing (JDK
21). Every gate of the table is open but G7, kept closed by its reason
(gotchas.md, "What's intentionally NOT done"), and G16, plan 06's.

**Decisions**, both taken overnight as recommended, not yet confirmed by the
user: D1 (a), every layout agrees on whether a query fails on its data and on
its answer, but where a WHERE or ON condition that can fail is there to move
(`QueryGen.mayFail`, `movableFailure`), TLP checking nothing when the base
fails; D2 (a), simple case mapping.

**Guards**, measured at `49b1760` (each bound ~3x below; blocks grew where W7
thinned them, no bound lowered):

| Gate | Guard | Measured |
|---|---|---|
| G2, G11 keys (W1) | `tlpPartitionsFloatColumnsHoldingNaNAndNegativeZero`, `floatAndBooleanKeysJoinAndGroupOverNaNAndBothZeros` | 5000 seeds: a float TLP partition 1243, NaN 102, -0.0 56, a special constant 276; 6000 seeds: a float join key 952, NaN both sides 312, -0.0 beside 0.0 404, a boolean key 432, a float GROUP BY key 353 holding NaN 76 and both zeros 50 |
| G3, G14 IN (W1) | `trimReachesValuesWithSpacesAtAnEnd`, `floatInListsReachNaNAndNegativeZero` | TRIM 709 of 14000, over a spaced value 120; IN over a float 327 of 9000, NaN 117, -0.0 100 |
| G1 (W2) | three `textReachesEveryStringSite` | thinnest cell: mode-differential 32 of 130000 (floor 9), metamorphic 23 of 150000 (floor 7), frontend 39 of 140000 (floor 12) |
| G9, G10 (W3) | three `everyCatalogFunctionIsDrawn`, `domainNullsReachATlpPartitionAndAGroupByKey` | rarest function: `PI` 81 of 57000, `E` 66 of 40000, `LN` 30 of 46000; 20000 seeds: a TLP partition over a math function 500, over a domain NULL 157, a GROUP BY key 1010 and 315 |
| G11-G13 (W4) | `joinsReachJoinOrderAndThePairingPath`, `windowsReachEveryFrameKind`, `ctesReadCtesAndUnionArmsTakeAnyCore` | 3000 seeds: a comma list 534, `CROSS JOIN` 252, four tables or more 171, a run `JoinOrder` reorders 178, the pairing path 444; 12000 seeds: RANGE offsets 112 (95 layout-invariant), ROWS 106, LAG/LEAD with a default 115; 4000 seeds: a CTE over a CTE 498, a UNION arm with a join 373, an aggregate 219, a window 66 |
| G14 (W5) | `everyTypeOfTheCatalogMeetsItsValues` | thinnest, DISTINCT over a DOUBLE holding NaN and -0.0, 95 of 40000 |
| G15 (W6) | `negationsAndNotsOverCompoundsAreDrawnAndBind` | a negation of a computed value 221 of 7000, a NOT over AND or OR 181, none refused as text |
| G4-G6 (W7) | `dataFailuresReachAJoinAWindowAnAggregateAndSpill` | 15000 seeds, 4854 at the edges, 556 failing: an integer overflow in a join 155, a window 42, an aggregate 140, a spill 69; a decimal past its precision 100, 29, 72, 56; a date past a TIMESTAMP's range 105, 29, 81, 33 |
| G8 (W7) | `failingCastsAreDrawnAndFail`, `failingCastsReachAnInAComparisonAndAnAggregate` | 40000 expression seeds: text drawn 3331, failing 2729; a float to an integer 984 and 204; to a DECIMAL 218 and 38; a DATE to a TIMESTAMP 468 and 73. 4000 query seeds: under an IN 373 and 135, in a comparison 134 and 49, in an aggregate 497 and 205 |

Grown blocks: before W7, `nullScalesAndPositionsReachTheirFunctions` to
8000, the mode-differential predicate and site tallies to 15000, the
metamorphic type tally to 30000 and its OFFSET and NULL-placement guards to
3000; at W7 twelve more (eight mode-differential, the metamorphic text tally,
three expression-parity), each comment naming the count that thinned.

**Bind rejections at 20000 seeds**: after W6 the mode-differential corpus
refused 115 queries and after W7 141 (0.71%), every one a branch list mixing
a DECIMAL and a DOUBLE, the corpus's known refusal; the metamorphic corpus
refused 6 of 40000 probes, the same; after W5 the frontend refused no
spelling and no honoured clause. W7's first draft cast literals, which the
binder folds and fails at bind time (3.7% refused); the text a CAST reads is
a column's since.

**Detection proofs**, each a defect put back in `src/main`, the new corpus
failing and the corpus before passing (reset with `git checkout HEAD --`,
then restored):

- W1, `Ops.floatCompare` made IEEE's: `fuzzTlp` fails at seed 36 on a NaN
  group key; 2000 seeds of the corpus before pass.
- W2, the split itself: with Java's full case mapping put back,
  `fuzzSyntaxEquivalence` fails at seed 1488 (`LENGTH(LOWER(s))` 2 over
  U+0130); 6000 seeds before pass.
- W3, `SQRT` of a negative NaN again: `fuzzCatalogNulls` fails at seed 59
  (`SELECT SQRT(c0)` over -1); 5000 seeds before pass, and the layout, TLP
  and round-trip properties pass too.
- W4, `JoinOrder` re-indexing the third table off by one: `fuzzTlp` fails at
  seed 12 and `fuzzJoinCommutativity` at seed 1, on a comma list whose second
  table has no equality with the first; 2000 seeds before pass.
- W7, `IntegerAvgState.merge` adding the low words alone, AVG's sum merge
  path: over 4000 seeds `fuzzModeDifferential` fails at seed 2261 (`AVG` of
  two smallest BIGINTs over four partitions) and `fuzzModeAgreement` at seed
  1521; 4000 seeds of the corpus before pass, `fuzzExactIntegerSums` among
  them, which pins `LongSumState`'s merge and not AVG's.

**Campaigns**, after W7, one block per target from seed 900000, three JVMs
at `-Xmx1200m` from the launchers with bazel shut down, each filtered to its
properties: `mode_differential` 2500 seeds (185 s), `metamorphic` 2500 (85
s), `sharded_mode` 2500 (91 s), `plan_roundtrip` 2500 (54 s), `frontend`
2500 (17 s), `expr_parity` 50000 (32 s). All green.

**Calls made overnight**, beyond the two decisions:

- **The edges are a dataset's, not a column's.** 40% of datasets and
  environments are at the edges, 70% of their columns of a type with edges
  drawing them, from a stream split after the others, so every other dataset
  is the one it was; a column at the edges alone was rarely read by a call
  that fails there (15 failing cases in 6000).
- **Where a failure is raised is read off its stack.** The W7 guard counts
  the operator a frame of the failing exception's stack names (a join, a
  window, an aggregate, a spill method), since a failure in a projection over
  a join's output is raised with no join on the stack.
- **The proof's mutant is AVG's merge**, not SUM's: `ExactIntegers` already
  holds `LongSumState` to the exact sum in every layout, so the corpus before
  catches a SUM merge mutant too.
- **Text to INT stays the guarded shapes'.** `ExprGen` draws text read as
  every other type, so a failure of a CAST of text to INT still means a guard
  did not keep its rows off (`ExprParity.owned`).
- **The agreed corpus was not regenerated**: AgreedGen draws from the widened
  generators the next time it is, and its doc says what it will meet.
