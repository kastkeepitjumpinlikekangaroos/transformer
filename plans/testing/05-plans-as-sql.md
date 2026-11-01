# Plan 05: plans as SQL, and plan 04's next round

> Status: **in progress** 2026-11-01 · four decisions by the user, the
> recommended option each time · one commit per workstream · Risk: low for the
> printer and the tests; medium for join ordering and the streaming pairing,
> which change how queries run

## Why

SQL becomes a plan in one place, `LogicalBuilder.build`, and nothing turns a
plan back into SQL. So a plan can only be read by reading Scala case classes,
an optimizer pass can only be checked by running what it made, and two of the
verifications DuckDB and Spark rely on were left out of plan 04 for want of
it:

- **Re-parse verification** (DuckDB's `PARSED` statement verifier): print the
  statement, parse the text again, and hold both to one plan and one answer.
  It checks the binder's compositionality, that a query means what its parts
  mean, which no test that writes each query once can see.
- **Golden plans** (Spark's PlanStabilitySuite, DuckDB's `EXPLAIN` records in
  sqllogictest): pin the plan a query gets, so a planner change that makes a
  join stop hashing (plan 30's comma join) fails a test instead of a
  production run.

Plan 04 also left a list of candidates, each found by its runs. This round
takes all of them but JSqlParser 5.3, which still refuses deep parentheses.

## Decisions (the user, 2026-11-01)

1. **EXPLAIN is a statement and an API.** `EXPLAIN SELECT ...` runs like a
   query and returns the plan as rows, so the GUI's query console, the data
   viewer and sqllogictest scripts all get it; `SqlEngine.explain` and
   `PlanSql.print` serve Scala callers. No CLI subcommand.
2. **Joins are ordered by connectivity, greedily.** The written order is kept
   except where the next table has no equality to the tables joined so far;
   then the first later table that has one comes in. No cost model, so a plan
   changes only where it would have paired rows without a key, and a
   projection above restores the written column order.
3. **Every follow-up group is in scope**: join order and a streaming pairing;
   the hash-zero configuration and the CSV column-order property; ported
   Postgres regression tests; a spill file's fixed cost.
4. **One commit per workstream**, each with its tests and docs, after `bazel
   test //...` and the jaffle-shop job pass.

## Workstreams, in landing order

| # | Workstream | Status |
|---|---|---|
| W1 | Plan validation after every pass | **done** 2026-11-01 |
| W2 | `PlanSql`: a plan printed as SQL the binder reads back to it; the round trip as an slt mode and a fuzz property | **done** 2026-11-01 |
| W3 | `EXPLAIN` and golden plans | |
| W4 | Join order by connectivity | |
| W5 | A pairing that streams | |
| W6 | Every hash zero (`--config=hashzero`) | |
| W7 | A CSV glob's files with their columns in other orders | |
| W8 | A spill file's fixed cost | |
| W9 | Ported Postgres regression tests | |

### W1. Plan validation after every pass

Spark's `planChangeValidation`, DuckDB's `debug_verify_column_bindings`.
`ColumnProjectionPushdown` verifies its own output, and only its column
references, skipping windows. A `PlanValidator` checks every node of a plan:
each column reference in range and of its input's type (window keys and
arguments, aggregate arguments, sort keys, join conditions over both sides
included), a union's arms of one width and types, a scan's view still a
`PendingCteView` only before CTE resolution; and a pass's output schema equal
to its input's, names and types. `SqlEngine` runs it after CTE resolution and
after each optimizer pass, always (it walks the plan once, at plan time).

### W2. `PlanSql`

`sql/plan/PlanSql.scala` prints a bound plan, or a `BuiltQuery` with its
CTEs, as SQL that `LogicalBuilder.build` reads back to the same plan.

- **Blocks.** The binder builds a SELECT as a fixed stack: FROM (a left-deep
  join tree of tables), WHERE, aggregate, HAVING, window, the SELECT list,
  DISTINCT, ORDER BY (over the list, with `_orderN` columns for computed
  keys), OFFSET, LIMIT. The printer reads the same stack off the plan, top
  down, and prints one SELECT for it; a node that fits no slot (a filter under
  a join after pushdown, a projection over a projection, a pruned scan) starts
  a new block, printed as a CTE the block reads. Unions print as one chain
  when the tree is the balanced one the binder builds from it, and in
  parentheses otherwise.
- **Names.** Every column is printed qualified by its FROM item's alias, so
  ORDER BY's output-name rules never apply; a CTE whose columns repeat a name
  is printed with a column list that renames them.
- **Types.** Every cast the binder inserted is printed, and every literal in a
  form that binds to its own type wherever it stands: `CAST('…' AS T)` for a
  BIGINT, DECIMAL, DATE, TIMESTAMP, a negative number or a non-finite float,
  since a bare number's type depends on the operands beside it.
- **The property.** For a query `q`: `build(print(build(q)))` equals
  `build(q)` once column-reference names and aliases are set aside, and runs
  to the same answer; printing is a fixpoint. For the optimized plan `o`,
  `print(o)` run with the optimizer off answers as `q` does, and binds back to
  `o` but for the identity projections a CTE adds.
- **Where it runs.** A `reprinted` verification mode in the slt runner
  (every script, SQLite's slices included) and a `PlanRoundTrip` oracle over
  every query generator (`QueryGen`, `MetaQueryGen`, `SqlTextGen`), with a
  coverage guard that the corpus reaches every plan node and expression kind.

### W3. `EXPLAIN` and golden plans

`EXPLAIN <query>` returns one STRING column, `plan`, a row per line: the
optimized plan as an operator tree, each node's expressions printed as SQL,
then the same plan as SQL. `EXPLAIN VERBOSE` adds the plan as bound (tree and
SQL) and the physical operators. The logical sections read no JVM-wide
setting, so a golden plan holds under every whole-suite configuration.
`ANALYZE`, `FORMAT`, `COSTS` and `PLAN FOR` are refused. Golden plans are
sqllogictest scripts under `src/test/slt/plan/`, run in the baseline only:
filter pushdown into join sides and its outer-join barriers, a comma join's
equality becoming its key, projection pruning, and (W4) the join order.

### W4. Join order by connectivity

A logical pass after `FilterPushdown`: flatten each run of inner joins into
its tables and conjuncts, order the tables as decision 2 says, rebuild a
left-deep tree with each conjunct at the lowest join that has its tables, and
restore the written column order with a projection. Outer joins are
barriers. The nested-loop guard's refusals of SQLite's `select5.test` are
measured before and after, and golden plans pin the order.

### W5. A pairing that streams

The collapsing path of a join with no equality holds every pair, in a buffer
and then an array, before its first batch. It probes batch by batch instead,
keeping only the build side and, for a RIGHT or FULL join, which build rows
matched.

### W6. Every hash zero

`-Dtransformer.hash_zero=true`, read once per JVM: every key hash the engine
computes (shard routing, join and aggregate hash maps, spill buckets) is 0,
so every map meets only collisions. `.bazelrc` gets `--config=hashzero`, and
the whole suite runs under it.

### W7. A CSV glob's columns in other orders

A CSV fuzz property writing a glob's files with their columns in different
orders, each with its header, held to the reader returning the columns of the
first file's header, by name (plan 04's finding 3).

### W8. A spill file's fixed cost

Measure what each spill file costs to open and close (plan 04 put it at about
25 ms of parquet and Hadoop setup) and cut what is setup rather than data.

### W9. Ported Postgres regression tests

Postgres's `window`, `numeric` and NULL-handling regression queries the
engine can run, with Postgres's own answers from `expected/*.out`, as
sqllogictest scripts under `src/test/slt/postgres/`; a record the engine
answers otherwise by design is marked with the reason, as Spark marks its
ports.

## Findings

| # | What | How it was found | Now |
|---|---|---|---|
| 1 | `LogicalAggregate` carried a `having` condition nothing set: the binder puts HAVING in a filter above the aggregate, and the projection pass's check read the unused field against the aggregate's input | writing the validator, which would have had to give it a meaning | the field is gone (W1) |
| 2 | The binder kept an aggregate, and a window function, per spelling: `COUNT()` and `COUNT(*)`, `SUM(a)` and `SUM(t.a)` were two slots computed twice; the printer writes one spelling of each, so the printed query bound to one slot | `plan_roundtrip_fuzz_campaign`, seed 4799, a frontend mutation dropping the `*` of `COUNT(*)` | each is computed once, its spellings reading one slot by their text (W2) |
| 3 | A cast of NULL to BINARY, which the binder inserts beside a BINARY, has no SQL to write it | the round-trip guard's tally of unprintable plans | printed as its operand, as a cast to DECIMAL is, and inserted again by the binder (W2) |

W1 found no malformed plan: the whole suite, fuzzers included, and the
jaffle-shop job run with the validator on every stage.

W2: every slt script round-trips, this repo's and the SQLite slices (about
1,900 of SQLite's queries), under the `reprinted` and `optimized-reprinted`
modes; so do 5,000 seeds of each generator's property after finding 2.

### How the printer is built (W2)

- Binding decides some types from how a value is written: a number beside a
  DECIMAL is the decimal of its digits, a string literal beside a column is
  read as that column's type, an integer that an INT holds is an INT. So the
  printer prints the casts the binder inserted (but to DECIMAL and BINARY,
  which SQL here cannot write and the binder inserts again, the operands'
  types being the same), and each literal in a form whose type does not depend
  on where it stands: `CAST('5' AS BIGINT)`, `CAST('1.5' AS FLOAT)`,
  `CAST('NaN' AS DOUBLE)`, `DATE '...'`, `TIMESTAMP '...'`, a DECIMAL as its
  digits (one stands only beside a DECIMAL, or past a BIGINT), a negative
  number as the negation of its magnitude.
- The comparison sets aside what binding chooses afresh: names (column
  references', aliases, the names of projections, aggregates and windows but
  the plan's own output names), a projection of every column in order (a CTE's
  SELECT list over a query that ends in none), a projection of a scan's columns
  (a pruned scan), the negation of a number (the negative number), and which
  CTEs a query was written with (every CTE inlined). Float literals compare by
  their bits, since Scala's `==` on two boxed NaNs is false.
- `CteResolver.planned` decides which CTEs are materialized without running
  them, so the optimized plan prints (and EXPLAIN shows it, W3) with a scan of
  each materialized CTE and its body beside; `resolve` materializes what it
  decided. Optimizer passes may now meet a pending CTE scan; the check after
  CTE resolution still refuses one there.
