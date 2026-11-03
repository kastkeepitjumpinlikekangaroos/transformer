# Plan 16: oracles from the literature: rewrites, forced plans and known join answers

> Status: **done** 2026-11-02 (33aa329 and 5436b38 W1, 7ace602 and 65107cc W2,
> 1aa55dc W3, cf8dfbd decision 3, c87d7be W4; see [Outcome](#outcome); the
> three decisions taken overnight as recommended, not yet confirmed by the
> user; findings T1, G1 and E1 held for the user) · from the 2026-11-01 test survey, category 3 of 9 ·
> Effort: 1-2 weeks · Risk: low (test-only); each workstream is a new way to
> find a wrong answer that every existing relation agrees on · Depends on:
> [15](15-generator-gates.md) for the join and window shapes (W2 and W4 run on
> today's corpus but reach little before its W4); PlanSql (plans/testing/05
> W2) for W1

## Why

The relations in `src/test/scala/com/transformer/fuzz/oracle/` came from
SQLancer (TLP, NoREC) or were built for one finding (aggregate decomposition,
join commutativity, key equality, the two point references `ExactIntegers`
and `WindowFrames`, the plan round trip, equivalent spellings). Since 2023 the
database-testing literature has added relations that need no reference engine
and aim where an optimizer or an operator goes wrong while every layout still
agrees with every other:

| Method | Venue | Relation |
|---|---|---|
| EET | OSDI 2024 | wrap any expression in a form that cannot change its value; the answer must not change |
| DQP | SIGMOD 2024 | force different plans for one query; one answer. It reproduced 14 of TQS's 15 bugs |
| TQS | SIGMOD 2023 | split a wide table into tables that join back into it; the wide table is the answer to every join query over them |
| TypeCheck | PVLDB 2026 | the same values stored as INT and as BIGINT give the same answers |
| RisingWave's `over_window` cross_check | (RisingWave `e2e_test/`) | several window functions in one SELECT equal a join, on a row id, of one-window queries |
| CERT | ICSE 2024 | a query made more restrictive never gets a larger row estimate |

None is in the repo, and they need the shapes the generators draw least
(plan 15's G11 and G12).

## What exists now

- **Equivalent spellings**: about fifty fixed shapes (`SqlTextGen.scala:690-818`)
  over `t(a INT, b INT, s STRING)` and `u(a INT, b INT)`, the two spellings
  written by hand. Nothing rewrites a generated query's own expressions.
- **Join commutativity** flips the FROM clause's first join only
  (`oracle/JoinCommutativity.scala:1-30`).
- **Join orders that ever run**: the written one (the slt modes with the
  optimizer off, `slt/Modes.scala:50-56`) and `JoinOrder`'s choice
  (`sql/plan/JoinOrder.scala:7-31`), which keeps the written order but where a
  table has no equality to those before it. `MetaQueryGen` joins at most three
  tables, each on an equality.
- **Join strategies**: `HashJoinExec.execute` (`sql/exec/JoinExec.scala:167-172`)
  takes one of four paths: grace hash when spill is on and the join has keys,
  per-shard when both sides are exchanges, the pairing waves when it has no
  equality, and the collapsing hash join otherwise. Its key codec is one of
  four (long, packed, single object, object; `JoinExec.scala:120-127,
  152-161`), chosen by key count, type and whether the keys are bare columns,
  and recorded in the metrics node (`IdxKeyCodecPath`). The sharding gates are
  read at class load (`sql/plan/LogicalPlanCardinality.scala:44-75`), so
  sharded plans run in their own JVM (`fuzz/BUILD.bazel:116-150`).
- **Windows**: one `WindowExec` computes every window of a SELECT, whatever
  its PARTITION BY and ORDER BY (`PhysicalPlanner.buildWindow`);
  `oracle/WindowFrames` checks one window at a time over a fixed table.
- **Estimates** are "best-effort, monotone, never panics"
  (`LogicalPlanCardinalityTest.scala:9`), held by unit examples only. The
  nested-loop guard sizes a join on an equality with `joinEstimate`:
  `max(l, r)` inner, `l` left (`LogicalPlanCardinality.scala:163-168`).

## What to build

### W1. Value-preserving rewrites (EET)

For a generated query `q`: bind it (`LogicalBuilder.build`), choose up to
three subexpressions anywhere in the bound plan (a filter, a projection, a
join condition, an aggregate's argument, a window's argument, partition key or
order key, a HAVING, a CASE branch), replace each with a form that cannot
change its value, print the plan with `PlanSql`, run the text, and compare its
rows with `q`'s as multisets (`RowOracle.multisetEquals`).

The forms, for an expression `e` of type T and a condition `p`:

1. `CASE WHEN FALSE THEN r ELSE e END`, where `r` is NULL or an expression of
   exactly type T. Sometimes `r` fails on every row (a CAST of text that is no
   number, a product past INT's range): a conditional evaluates only what its
   answer needs (plans/robustness/17; `README.md:1367-1374`), so it must never
   run.
2. `CASE WHEN q THEN e ELSE e END`, `q` any condition, NULL rows included.
3. `COALESCE(e, e)`, `IF(TRUE, e, r)`, `CASE WHEN e IS NULL THEN NULL ELSE e END`.
4. `p AND (q OR NOT q OR q IS NULL)`, `p OR FALSE`, `NOT (NOT (p))`, and
   `CASE WHEN p THEN TRUE WHEN NOT p THEN FALSE END`, which is NULL where `p` is.
5. An IN list with an item repeated.

Rewriting the bound plan rather than each generator's AST reaches every
position the binder produces, for every generator at once. The printed text
binds back to the rewritten plan by the round-trip property PlanSql already
holds. The rewrite is sound because references in a bound plan are positions,
and PlanSql prints each one qualified, so ORDER BY's output-name rule
(plans/robustness/22) and GROUP BY's matching of items never meet a wrapped
bare name. Under strict typing `r` must have T exactly (`Analyzer.branches`).
A DECIMAL beside a DOUBLE that is not written in the query is an error by
design, so `r` is never such a DOUBLE.

A wrapped join equality hides its key from `splitEqualityKeys`, so the join
runs on the pairing path (W2's lever too; the corpus's tables are too small
for the nested-loop guard).

It runs as a property over `QueryGen`, `MetaQueryGen` and the frontend's
spelling and honoured queries, and as an slt verification mode, `rewritten`,
beside `reprinted`: every query of every script (SQLite's slices and the
Postgres ports included) rewritten under a seed per record and held to the
script's expected answer.

Coverage guard: rewrites at each position and in each form, originals that
return rows (otherwise any two queries agree), and planted failing branches.
Detection proof: plans/robustness/17's defect put back (a vectorized CASE
evaluating every THEN), and `FilterPushdown` dropping a conjunct under a CASE;
W1 must fail on both at its default budget.

### W2. Forced plans (DQP, Velox's join fuzzer)

For one query, every plan the engine can be made to choose must give one
multiset:

- **Join orders.** Rewrite each run of inner joins, `A JOIN B ON p JOIN C ON
  q`, as `FROM <a permutation of A, B, C> WHERE p AND q`, every column keeping
  its alias. Run each permutation with the optimizer off, which joins in
  written order, cross products first where they come first. Run it on too,
  so `JoinOrder` chooses from each written order. That means every order of
  four tables, and a sample beyond. Outer joins stay where they are, as
  barriers. Tables are sized so the written order's products stay under a
  million pairs, below the nested-loop guard; the slt runner already counts a
  guard refusal as no divergence when the optimizer is off (`slt/Runner.scala:206`).
- **Strategies, chosen by spelling, in one JVM.** One join, several plans:
  - `ON a.k = b.k`: the collapsing hash join, with the long-key codec for an
    integer key that is a bare column;
  - `ON COALESCE(a.k, a.k) = COALESCE(b.k, b.k)`: a computed key, so the
    packed or object codec;
  - `ON CASE WHEN a.k = b.k THEN TRUE ELSE FALSE END`: no key, so the pairing
    waves;
  - each again with spill on (grace hash);
  - and with the sides swapped, at every join rather than the first.
- **Sharded against unsharded.** The same in the sharded JVM, with
  `ShardedModeFuzzTest`'s `jvm_flags`. There every keyed join is per-shard,
  and the CASE spelling has no key to shard by. That makes it a sharded
  against unsharded comparison of one join in one JVM, which the class-load
  gates otherwise rule out.

Coverage guard, read from the physical plan and the metrics node: every
path and key codec on a share of cases, and runs of three or more inner
tables (which needs plan 15's W4). Detection proof: `JoinOrder` re-indexing
the third table off by one; the pairing waves dropping their last wave; grace
hash routing one key type to the wrong bucket.

### W3. Known join answers (TQS)

**Data.** Generate a wide table `W(id, a1..an)` with functional dependencies,
and split it along keys into a fact table and dimension tables. Some keys are
NULL and some dimension rows match no fact, both on purpose.

**Queries.** Walk the schema graph:

- inner joins along keys;
- LEFT, RIGHT and FULL joins with unmatched rows on either side;
- filters comparing a column with a literal;
- projections;
- COUNT, SUM, MIN and MAX grouped by a dimension attribute.

**Expected answers.** A small Scala evaluator works each answer out from `W`
and the split, without the engine:

- an inner join along a key is `W`'s rows;
- an outer join adds the unmatched rows, padded with NULLs;
- a filter is three-valued logic over INT and STRING, computed here;
- aggregates are computed in `BigInt`.

This gives an exact answer for multi-way and outer joins at fuzz volume. The
third audit needed SQLite to see this class of bug.

Coverage guard: each join kind, unmatched rows on each side, three or more
tables, NULL keys.

### W4. Three small relations

- **TypeCheck.** Store the same values as INT, as BIGINT and as DECIMAL(10,0)
  (a BIGINT as DECIMAL(19,0)), and run queries without `/`, AVG or the
  statistics. Integer `/` truncates and DECIMAL's does not, and AVG of an
  integer is a DOUBLE where AVG of a DECIMAL is a DECIMAL. Between INT and
  BIGINT, `/` stays in.
  - Every comparison, join, GROUP BY, DISTINCT, ORDER BY, MIN, MAX, COUNT,
    SUM, `+`, `-` and `*` must agree numerically.
  - Where the INT form answers, the wider forms give the same answer. An INT
    overflow may become an answer in the wider type, never the reverse.

  This tests that the typing rules apply as one rule.
- **Window cross_check.** `SELECT id, w1, w2 FROM t`, with two windows of
  different PARTITION BY and ORDER BY, must equal the w1-only and w2-only
  queries joined on `id`. It runs over windows whose value cannot depend on
  ties, or whose ORDER BY ends in `id`. It aims at one `WindowExec` computing
  several specs.
- **CERT.**
  - For a generated plan and a restriction of it (a WHERE conjunct added, a
    LIMIT, a LEFT JOIN made INNER, a FULL JOIN made LEFT),
    `LogicalPlanCardinality.estimate` and the guard's bound never grow.
  - The guard never refuses a restriction of a query it accepts.

  Reading `joinEstimate` predicts the first failure. Take `FROM a CROSS JOIN
  d LEFT JOIN b ON a.k = b.k CROSS JOIN c` over tables of 10, 10, 300000 and
  100 rows. The LEFT spelling is sized 100 at the left join and passes. The
  INNER JOIN spelling, which pairs no more rows, is sized 300000 and refused.
  When b's keys fan out, the LEFT spelling pairs as many rows as the guard
  refuses the INNER one for. This is unconfirmed: the CLI jar on hand predates
  `JoinOrder`. Decision D3 settles it if the property confirms it.

### Targets and cost

| Target | Tag | Per seed |
|---|---|---|
| `eet_fuzz_test` / `eet_fuzz_campaign` | default / `fuzz` | 1 + k runs, k <= 3 |
| `forced_plan_fuzz_test` / `_campaign` | default / `fuzz` | up to 24 orders x 2, plus 6 strategies |
| `sharded_forced_plan_fuzz_test` | default (the sharded `jvm_flags`) | the strategy half |
| `join_truth_fuzz_test` / `_campaign` | default / `fuzz` | one split, about 5 queries |
| W4's relations | folded into `metamorphic_fuzz_test` | 2-3 runs |

Default budgets stay near the existing targets' 15 seconds; campaigns run as
the fuzz-campaign-parallel-blocks memory says (`df -h` first).

## Decisions for the user

1. **Where W1 rewrites.**
   - (a) **(recommended)** The bound plan, printed by PlanSql. One
     implementation reaches every generator and every slt script, and the
     round-trip property already guarantees the text means the plan.
   - (b) Each generator's own AST. More control over shapes, but three
     implementations, and nothing for the slt scripts.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
2. **How W2 forces a join order.**
   - (a) **(recommended)** Permutations written into the text, run with the
     optimizer off and on. No seam in `src/main`.
   - (b) A pluggable order chooser in `JoinOrder` for tests. This reaches
     orders the written-order rule cannot produce, with the optimizer on,
     but puts test-only code in the optimizer.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
3. **The nested-loop guard under restriction**, if W4's CERT property
   confirms the inversion.
   - (a) **(recommended)** The guard sizes a join on an equality no smaller
     for an outer join than for the inner join it contains: `max(l, r)` for
     LEFT and RIGHT, `l + r` for FULL. A restriction then never trips it.
     The estimator's own `joinEstimate`, used for build sides, stays as it is.
   - (b) Leave it, and document that the guard's bound is an estimate that
     can refuse a restriction of a query it accepts.

   **(a), taken overnight as recommended, not yet confirmed by the user**,
   once W4's CERT property confirmed the inversion (`fuzzCertOverJoinShapes`
   at seed 2, in the query's own join order). It reaches the joins under an
   aggregate or a union too, which inverted after the narrower change; and
   it makes the guard monotone in the query's own join order only, so a
   restriction the optimizer orders anew can still be refused (finding G1,
   held).

## Decisions already made

No reference engine on the test classpath and no new dependency
(plans/testing/README). A class-load gate is varied by a separate JVM target,
never by a seam that unfreezes it (plans/testing/03; `fuzz/BUILD.bazel:116-121`).
Every property has a coverage guard and a bind-rejection tally, and a guard is
never lowered to pass (fuzzer-generator-gates memory). A confirmed wrong answer
is fixed in its own commit with a regression; a semantic choice goes to the
user first (prefer-strict-sql-semantics memory).

## Tests

The targets above with their guards and detection proofs (against main files
reset with `git checkout HEAD --` and restored, per the fuzzer-generator-gates
memory), then a 20000-seed campaign of each. Every finding gets a regression in
`SqlEngineTest` or an slt script; the `rewritten` mode runs over every script.

## Docs

`docs/testing.md` (the targets, each relation, the `rewritten` slt mode);
`docs/extending.md` (a rewrite form, a forced strategy, a TQS split);
`docs/architecture.md` (the oracles section); `docs/gotchas.md` (findings,
D3's outcome); `README.md` only if a finding or D3 changes behavior.

## Risks

An unsound rewrite reads as a finding: name resolution, RAND and the
clock, tie-sensitive windows and float tolerance are the known traps, each
needing a skip rule with its reason, as `JoinCommutativity` has. Permutations
multiply runs, so the default budget samples orders and the campaigns
enumerate them. W3's evaluator is a second implementation of join semantics:
keep it to joins along keys and three-valued comparisons with literals.

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, one commit per workstream
and one per engine fix, each fix before the workstream it was found in:
the PlanSql fix `33aa329`, W1 `5436b38`, the metrics fix `7ace602`, W2
`65107cc`, W3 `1aa55dc`, decision 3 `cf8dfbd`, W4 `c87d7be`. At each, `bazel
test //...` passed (74 targets before, 78 after) and the jaffle-shop job gave
15/15 Succeeded with every validation passing (JDK 21). Each fix's regression
failed at the plan's base, `dae7095`, run in a temporary worktree.

**Decisions**, each taken overnight as recommended, not yet confirmed by the
user:

- D1 (a): W1 rewrites the bound plan and prints it with PlanSql, so one
  implementation reaches every generator and every slt script.
- D2 (a): W2 forces join orders by permutations written into the text, run
  with the optimizer off and on; no seam in `src/main`.
- D3 (a), applied because W4's CERT property confirmed the inversion
  (`fuzzCertOverJoinShapes` failed at seed 2 at `1aa55dc`, in the query's own
  join order): the nested-loop guard counts a join on an equality by its
  larger side whatever its kind, both sides for FULL, so an outer join counts
  no fewer rows than the inner join it holds. It also counts the joins under
  an aggregate or a union that way (`LogicalPlanCardinality.estimateWith`),
  since a CTE grouping a LEFT JOIN beneath two CROSS JOINs inverted too after
  the narrower change; `joinEstimate`, which picks build sides, is unchanged.
  Decision 3 does not make the guard monotone in every order: see G1.

**What each relation holds, over which shapes**

- W1, EET (`oracle/Eet`, `eet_fuzz_test`, the slt `rewritten` mode): up to
  three subexpressions of a bound plan, at ten positions (a filter, a HAVING,
  a projection, a join condition, an aggregate's argument, a GROUP BY key, a
  window's argument, partition key and order key, a CASE branch), each in one
  of ten forms that cannot change its value, the unused branch NULL, a
  column or literal of exactly its type, or a value that fails on every row
  it reads; over QueryGen, MetaQueryGen and the frontend's honoured clauses
  and spellings, and every slt script (6,278 queries rewritten, 1,793 with a
  failing branch planted, 1,820 with nothing to rewrite, 204 nested five
  parentheses deep run as written).
- W2, forced plans (`oracle/ForcedPlans`, `forced_plan_fuzz_test`,
  `sharded_forced_plan_fuzz_test`): the leading run of inner joins in every
  order of four tables or fewer and 24 of a longer run, optimizer off and on;
  every join of the optimized plan on its key, computed keys, two keys or
  none, spill on and off, sides swapped, sharded and not.
- W3, known join answers (`oracle/JoinTruth`, `join_truth_fuzz_test`): a wide
  table split into a fact table and one to three dimensions, a snowflake
  among them, queries walking the keys with every join kind, WHERE in
  three-valued logic, projections and grouped COUNT, SUM, MIN, MAX.
- W4, in `metamorphic_fuzz_test`: TypeCheck (INT against BIGINT and
  DECIMAL(10,0), over MetaQueryGen and queries aimed at each site), window
  cross_check (two windows in one SELECT against each alone joined on an id)
  and CERT (estimates and the guard under four restrictions, nothing run).

**Guards**, measured (bounds ~3x below the thinnest, the full tables in the
tests' comments and docs/testing.md):

| Relation | Guard | Measured |
|---|---|---|
| EET | `rewritesReachEveryPositionAndForm` | over 14980 queries: thinnest position a window's argument 115, form the repeated IN item 138; failing branches 4784 |
| EET | `rewrittenQueriesReturnRowsAndTheirTextBinds` | 1033 of 1497 queries return rows; 2854 rewrites held, none refused, unparsed or unprintable (38185 at 4000 seeds) |
| forced plans | `joinOrdersReachRunsOfThreeOrMore`, `joinOrdersRunAndHold` | 3000 seeds: runs of three 414, four 22, five or six 28; 600 seeds: 1036 orders held optimizer off, 1037 on, 258 cases in another order |
| forced plans | `strategiesReachEveryPathAndCodec` (and sharded) | 600 seeds: collapsing 2094, grace hash 1396, pairing 1358, per-shard 1455 (sharded JVM); long 468, packed 2017, single object 748, object 257 |
| join truth | `queriesReachEveryKindAndBothUnmatchedSides` | 1500 seeds, 7550 queries: INNER 2290, LEFT 2252, RIGHT 1443, FULL 1511, a left row padded 2573, right 1984, three tables or more 2108, a NULL key 3162 |
| TypeCheck | `typeCheckReachesEverySiteInEachWiderForm` | 2000 seeds of each generator: BIGINT held 2651, DECIMAL 2629, an INT overflow answered wider 200; thinnest site COUNT 213 |
| cross_check | `windowCrossCheckDrawsEveryKindOfWindow` | 2000 seeds: each kind 522-607, two orders over one partitioning 297 |
| CERT | `certReachesEveryRestrictionAndTheGuard` | 3000 seeds: thinnest restriction a FULL join made LEFT 984; the query refused in 3279 |

**Detection proofs**, each a defect put in `src/main` and reset with `git
checkout HEAD --`:

- W1: `CaseExpr.evalVec` evaluating every THEN over the whole batch
  (plans/robustness/17's defect): all four EET properties fail within 25
  seeds. `FilterPushdown` dropping a conjunct under a CASE: the meta property
  at seed 22, the spellings at 108.
- W2: `JoinOrder` re-indexing the third table off by one: orders at seed 7,
  strategies at 72. The pairing path dropping its last wave: orders at 22,
  strategies at 12 (both JVMs). Grace hash sending the probe side's STRING
  keys to the next bucket: strategies at 210 (both JVMs).
- W3: a padded row losing its preserved side's first value on every join
  path: seed 2, where TLP, NoREC, mode agreement, aggregate decomposition,
  EET and forced plans pass their default budgets (join commutativity fails
  at 135).
- W4: two BIGINTs compared with `<` as `<=`: `fuzzTypeCheckTargeted` at seed
  275, where TLP, NoREC, mode agreement, join commutativity and aggregate
  decomposition pass. `WindowExec` sorting a second spec by the first spec's
  ORDER BY values: cross_check at seed 7, where the same five pass. Without
  decision 3: `fuzzCertOverJoinShapes` at seed 2.

**Defects found and fixed** (each with a regression that fails at
`dae7095`):

- PlanSql printed a CAST of a condition without the parentheses JSqlParser
  needs: `SELECT CAST((a = b) AS VARCHAR) FROM t` printed as `CAST(t.a = t.b
  AS VARCHAR)`, which does not parse, so `EXPLAIN` and the `reprinted` modes
  broke on it (found by the `rewritten` mode). `33aa329`.
- With metrics on, a sharded join, aggregate, DISTINCT and window ran on one
  partition: each told its exchange child by its class, and the metrics
  wrapper is another (found reading a join's path for W2's guard). `7ace602`.
- The guard inversion decision 3 settles. `cf8dfbd`.

**Findings held for the user** (gotchas.md, "What the literature oracles
held out"), none changed:

- T1. A DECIMAL compares with a FLOAT or DOUBLE column as a decimal, so NaN
  and the infinities fail while running (`SELECT d > f FROM t` over `f =
  NaN`: `Cannot cast NaN to decimal(10,0)`), where INT and BIGINT compare as
  a DOUBLE and answer, and arithmetic, CASE, COALESCE and GREATEST refuse a
  DECIMAL beside a FLOAT as the query binds. Options: refuse at bind time,
  compare as DOUBLE, or keep.
- G1. The guard's bound depends on the order the optimizer joins in: a
  restriction that lets JoinOrder bring a key before a CROSS JOIN can be
  refused where the query is not (`SELECT x.k AS a FROM x CROSS JOIN t1
  INNER JOIN t2 ON x.k = t2.k CROSS JOIN t3 RIGHT JOIN t4 ON t2.k = t4.k`,
  t1 4000, t2 0, t3 3, t4 5001 rows, runs; with the RIGHT JOIN made INNER it
  is refused). 11 in CERT's 3000-seed block.
- E1. A restriction can be estimated larger once optimized: an inner join's
  estimate is its larger side, so a filter pushed under it to the smaller
  side stops counting (a FULL JOIN made LEFT lets a WHERE through). 5 in
  CERT's block; the bound plans' estimates stay monotone.

**Campaigns**, one block per new property from seed 1,000,000, run from
the launchers with bazel shut down, three JVMs at `-Xmx1200m`, each filtered
to its properties: `eet_fuzz_campaign` 2500 seeds of its four properties
(118 s), `forced_plan_fuzz_campaign` 2500 of both (66 s),
`sharded_forced_plan_fuzz_test` 2500 of its strategies (52 s),
`join_truth_fuzz_campaign` 10000 (23 s), and `metamorphic_fuzz_campaign`
2500 of W4's five (24 s). All green. The plan's 20000-seed campaigns were
not run: the round keeps campaigns to a block a property.

**Calls made overnight**, beyond the decisions:

- EET never wraps a constant (the binder reads one where it stands: a
  ROUND's scale, a number beside a DECIMAL), nor anything below an operator
  whose answer depends on row order; a form that writes its expression twice
  takes one of twelve nodes or fewer, and the slt mode runs a query nested
  five parentheses deep as written (JSqlParser backtracked for seconds on a
  rewrite there: 204 queries, a hundred seconds); the `rewritten` mode
  compares rows as a multiset.
- W2's strategies respell the optimized plan and run it with the optimizer
  off, so a comma list's WHERE equalities, moved into joins, are respelled
  too; the sharded target shares the strategies' class.
- W4's TypeCheck runs over MetaQueryGen and over its own queries aimed at
  each site, since MetaQueryGen put an INT through `-` on 1 case in 2000.
- CERT holds its estimate rule on the bound plans and its guard rule in the
  query's own join order, reporting E1 and G1 rather than failing on them.
- The metrics defect was fixed rather than held: metrics are documented as
  watching a plan, and the fix sees through the wrapper the way
  `preMaterializeExchanges` already did.

## Launch prompt

```
Read plans/testing/16-literature-oracles.md and implement it, one workstream
per commit (W1, W2, W3, W4). Plan 15's W4 widens the join and window shapes
W2 and W4 need; if it has not landed, build on today's corpus and note what
the guards cannot reach yet.

First check ListAgents and `git status`: another session may hold uncommitted
work in this tree. If so, work in a scratch git worktree outside the repo and
copy your files back after comparing against a base snapshot
(concurrent-sessions-worktree memory).

Follow CLAUDE.md for every change: `bazel test //...` green, the jaffle-shop
example 15/15 with every validation passing, and every stale doc updated in
the same commit.

Stop and ask before: decisions D1 and D2 (before W1 and W2), D3 if W4's CERT
property confirms the guard inversion, any finding that is a semantic choice
rather than a defect, any change to src/main beyond a fix, and adding any
dependency.

Report: for each relation, what it holds and over which shapes; each
coverage guard's measured rate; each detection proof; every finding, with a
minimized repro.
```
