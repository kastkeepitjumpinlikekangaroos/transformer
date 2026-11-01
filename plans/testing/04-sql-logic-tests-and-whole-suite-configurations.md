# Plan 04: SQL logic tests and whole-suite configurations

> Status: **done** 2026-11-01 · borrowed from how DuckDB and Spark SQL test
> their engines · four decisions by the user, the recommended option each
> time · Risk: low for the tests; medium for what they changed (a JVM-wide
> setting, a parser upgrade, two CSV rules)

## Why

Every property in `src/test/scala/com/transformer/fuzz/` compares the engine
with itself: across execution modes, across spellings, across a metamorphic
rewrite. The second and third audits (plans/robustness/16-30) showed what
that cannot see: a deterministic wrong answer agrees with itself everywhere.
The third audit found such answers by running generated queries through
SQLite in a throwaway harness. DuckDB and Spark SQL both keep that kind of
check permanent, and both multiply it:

| | DuckDB | Spark SQL |
|---|---|---|
| Queries with answers from outside the engine, as readable text | sqllogictest scripts (`test/sql/**.test`), plus SQLite's own corpus, 7.2M queries whose answers five engines agreed on, run nightly with a skip list; TPC-H answers per scale factor | `SQLQueryTestSuite`: 382 `.sql` inputs with golden `.sql.out` results, 33 of them ported from Postgres's regression tests with each divergence marked |
| Every such query run several ways, held to one answer | query verification (unoptimized, re-parsed, prepared, external...; in 2.0 `debug_verify_statement`) | `--CONFIG_DIM` directives: each file under every combination of codegen and join strategy, against one golden file |
| The whole suite rerun under a setting no test can vary | CI builds at `STANDARD_VECTOR_SIZE=2`, `HASH_ZERO`, `ALTERNATIVE_VERIFY`; test configs `force_external`, `verification_projection`, `verify_functions` | shuffle partitions, AQE on/off, ANSI on/off, `testFallbackStartsAt` forcing spill |
| Errors | an expected error that is internal fails the test | error classes with SQLSTATEs in `error-conditions.json`, `checkError` per class |

Sources (checked by a research pass on 2026-11-01, links pinned to commits):
DuckDB `test/sqlite/sqllogic_test_runner.cpp`, `src/main/client_verify.cpp`,
`test/configs/`, `test/sqlite/test_sqllogictest.cpp` at
`github.com/duckdb/duckdb@71f47d4`; Spark `SQLQueryTestSuite.scala`,
`ExpressionEvalHelper.scala`, `RuleExecutor.scala` at
`github.com/apache/spark@4fecd73`; SQLancer's DuckDB oracles.

## What was built

1. **Whole-suite configurations** (DuckDB's vector-size-2 build and test
   configs). `ColumnarBatch.DefaultCapacity`, the batch size every reader and
   operator emits, is read from `transformer.batch_capacity` (default 8192).
   `.bazelrc` has `--config=batch1`, `batch2`, `sharded`, `serial` and `zone`,
   each setting `JAVA_TOOL_OPTIONS` for every test JVM. Fifteen tests that
   asserted something only the default makes true were changed: the CSV
   reader and writer tests and the sort batch-size test read whatever batches
   come; the volume spill tests, two plan-shape tests and three concurrency
   tests say what they need with `assumeTrue`.
2. **A sqllogictest runner**, `src/test/scala/com/transformer/slt/`: `Script`
   (SQLite's format as DuckDB extends it, plus exact column types such as
   `DECIMAL(13,4)`), `Database` (the DDL the engine lacks), `Values`
   (printing, sorting and hashing as SQLite's runner did, its `%.3f` rounding
   included), `Modes` (eleven verification modes: one-row batches, four
   partitions, empty partitions between, spill alone and over three
   partitions, metrics, parquet files read back, columns reversed with NULL
   columns between as in DuckDB's `verification_projection`, the logical
   optimizer off, and each of its two passes alone), `Runner`, `SltTest` (four
   shards) and `Corpus` (audits a directory; `--complete` fills a new script's
   results, as SQLite's completion mode does). An expected error must be a
   clean one, as DuckDB fails a test on an internal error.
3. **This repo's own scripts**, `src/test/slt/<area>/`, their results checked
   by hand: three-valued logic, integer overflow, float equality, decimals,
   ORDER BY, GROUP BY, window frames, CASE typing, joins, UNION, CTEs, every
   function over NULL arguments (DuckDB's `verify_functions`), and the binder's
   refusals with their words (`refused/messages.test`, the user-facing
   messages no test read, Spark's error discipline).
4. **SQLite's corpus**: sixteen slices vendored under `src/test/slt/sqlite/`
   (about 1,900 queries the engine runs, every record it answers otherwise by
   design marked `skipif transformer`), and an audit of the whole corpus with
   `Corpus`.

## Findings

| # | What | How it was found | Now |
|---|---|---|---|
| 1 | `SELECT 1 FROM t GROUP BY ()` failed whenever spill was on: an aggregate without keys spilled its one state, which had no columns for parquet | `slt_test`'s spill mode | an aggregate without keys never spills |
| 2 | CSV inference read the glob's first file alone: a header-only first part file (a sharded GROUP BY of two groups across four shards writes two) typed every column STRING, so the next job's `SUM(customers)` was refused | `--config=sharded`, through the data viewer's three-job fixture | the sample is the first rows of the files in order (decision 1) |
| 3 | A later CSV file's header was dropped unchecked and its fields read in the first file's order, so files ordering their columns differently swapped values | reading the reader for 2 | read by each file's header's names (decision 2) |
| 4 | An empty IN list ran, `NULL NOT IN ()` NULL beside `1 NOT IN ()` TRUE | SQLite's `evidence/in2.test` | refused (decision 3) |
| 5 | JSqlParser 5.0 could not parse four parentheses deep inside a call, `COALESCE((((a > 1))), FALSE)` | the runner's own DELETE rewrite over the corpus | JSqlParser 5.2 (decision 4) |
| 6 | No JSqlParser version parses two signs in a row (`SELECT - -3`), and the error named the SELECT | SQLite's corpus, which writes them in most queries | the refusal names the signs and says to write `-(-3)` (decision 4) |
| 7 | The nested-loop guard's chain bound stopped at a join on an equality: it sized one with the estimator, which sizes a pairing below it as the pairing's larger side, so `FROM a, b, c, d WHERE a.k = c.k` over 1000-row tables passed to pair a billion rows | SQLite's `select5.test`, which never finished: 308 of its 732 queries were let through to pair more than 25 million rows, up to 10^23 | a join on an equality is sized from its sides' bounds; 521 of the 732 are refused, the rest pair at most ten million, and the file runs in 155 s, 209 passing and two running a 2 GB heap out |
| 8 | The desktop GUI folded case in the JVM's locale: under tr-TR the SQL highlighter read `PARTITION` as `partıtıon`, no keyword, the task panel showed no task using an input named with an I, vim's Ctrl-I was Ctrl-ı, and the caret's CSS printed `1,50px` | `--config=zone`, through `gui:sql_highlighter_test`; two tests did the same (`parquet_fuzz_test`, and `parquet_external_format_test` comparing parquet-mr's schema string) | `Locale.ROOT`; `locale_independence_test` scans the GUI's and the viewer's jars for case folding |

The upgrade to 5.2 changed three more things, each now tested:
`COUNT_IF(NOT f > TRUE)`, which 5.0 misparsed as `(NOT f) > TRUE` and the
binder refused, parses as SQL reads it and runs; TRUE and FALSE are literals
in every position (a column named `true` is written in quotes); and 5.2's new
syntax (`PREFERRING`, `TABLESAMPLE` after a query, `AS MATERIALIZED`, a CTE
that changes a table, `ON OVERFLOW TRUNCATE`, `TABLE` before an argument, `IS
UNKNOWN`) is refused by the rejection pass, whose audit test listed every new
getter. Not 5.3, which the user picked: it parses 25 nested CASTs in 80 ms
where 5.2 times out, but refuses seventeen nested parentheses around a value,
which 5.0 to 5.2 read at the 256 levels the engine allows. 5.2 still cannot
read three around a `BETWEEN` bound (13 of the corpus's records; gotchas.md).

Negative results worth keeping: at batch capacities of 1 and 2 the whole
suite, fuzzers included, found no batch-boundary defect; on one thread
nothing hung; in a UTC-3:30 zone nothing changed, and under the Turkish
locale the engine, the readers, the writers and the job runner did not;
every function returns NULL for a NULL argument; no answer moved when the
columns were stored in another order; and SQLite's corpus, 5.3 million
records, returned no wrong answer (the totals are in docs/testing.md, "SQL
logic tests"). Its differences were all by design: an integer literal is an INT
here and a product past INT is an error (Postgres's rule), where SQLite has
64-bit integers; a CASE or COALESCE with a DOUBLE branch is a DOUBLE here
(plans/robustness/09), so a division after it is not integer division; and
subqueries, EXCEPT / INTERSECT, arithmetic on text and SQLite's other loose
typing are refused.

## Decisions (the user, 2026-11-01)

1. CSV inference samples the first 1000 rows across the glob's files in order.
2. A CSV glob's later files are read by their headers' names; one naming other
   columns fails. Declared columns keep skipping headers.
3. An empty IN list is refused.
4. Upgrade JSqlParser (landed as 5.2, not the 5.3 chosen, for the reason
   above), and refuse two signs in a row naming them.

Made without asking, to put back what a rule already said, each open to the
user's veto:

5. The nested-loop guard's bound reaches through joins on an equality
   (finding 7). It refuses on a bound, so 67 of select5's queries that pair at
   most 25 million rows in fact are refused too, as a chain of joins without an
   equality over filtered tables already was.
6. The desktop GUI folds case in `Locale.ROOT`, and the locale scan reads its
   jars and the viewer's for case folding (finding 8), the conventions' rule
   for a job's modules; display text may still format numbers in the user's
   locale.
7. The runner's expected errors and refusals must be clean as the fuzz
   harness's clean-rejection property has it, which splits binding from
   running at `LogicalBuilder.build`: an overflow on a value raised while the
   engine plans (an aggregate without keys is computed then) is a clean
   failure on the data, not an internal error.

## Not adopted, and why

- **Re-parse and serialize verification** (DuckDB's PARSED, DESERIALIZED): the
  engine has no plan-to-SQL printer and no plan serialization.
- **Golden plans** (Spark's PlanStabilitySuite): the engine has no EXPLAIN.
  Plan 30's comma join that never hashed is the class it would catch.
- **TPC-H / TPC-DS answers**: most queries need subqueries; the jaffle-shop and
  polymarket examples cover realistic pipelines.
- **A golden file per query** (Spark): the answer sits beside its query in a
  sqllogictest script instead, which reads better and diffs the same.

## Candidates for a next round

- **Join ordering** (found by SQLite's `select5.test`, which joins 4 to 64
  ten-row tables, each query spelled in three FROM orders): the planner joins
  in the order written, so a spelling that names a table before the one it
  joins to pairs every row. With finding 7 fixed the guard refuses 521 of the
  file's 732 queries, where SQLite answers each in milliseconds by ordering
  the joins so each step joins on an equality, and 67 of those pair at most 25
  million rows in fact: the bound cannot see a filter or an equality shrink a
  pairing. Of the rest, five pair ten million rows on their way to one
  (`join-17-4`, seventeen tables, in 11 s and 2.3 GB). Reordering inner and
  comma joins greedily by which tables an equality connects changes no answer
  (inner joins associate and commute) but re-indexes every column reference
  above the joins, as ColumnProjectionPushdown already does. The corpus audit
  runs `select5.test` on its own, since its widest joins fill the heap a file
  beside it shares.
- **A pairing that streams**: the collapsing path (a join without an
  equality) holds every row it pairs, in a buffer and then an array, before
  its first batch, so the 25 million pairs the guard allows fill a heap of a
  few GB when rows are wide: two of select5's joins, of 21 and 25 tables, run
  a 2 GB heap out at ten million pairs. Probing batch by batch would make a
  pairing cost time, not memory.
- **Every hash zero** (DuckDB's `HASH_ZERO`): a JVM-wide setting under which
  key hashing returns 0, so every exchange, join and aggregate meets only
  collisions and one shard; needs a hook where keys are hashed.
- **Plan validation after each optimizer pass** (Spark's
  `planChangeValidation`, DuckDB's `debug_verify_column_bindings`): the output
  schema kept and every column reference resolving, after FilterPushdown and
  ColumnProjectionPushdown.
- **Ported Postgres regression tests** for the areas whose semantics follow
  Postgres (window frames, numeric, NULL handling), as Spark ported 33.
- **The CSV fuzzer writing a glob's files with their columns in different
  orders**, a property over finding 3.
- **A spill file's fixed cost**: each costs about 25 ms of parquet and Hadoop
  setup, so a run of a thousand spills spends most of its time opening files.
- **JSqlParser 5.3** once it reads deep parentheses again.

## Docs

README (CSV inference and globs, IN lists, signs, boolean literals, the
nested-loop guard and join order, Development), testing.md (whole-suite
configurations, SQL logic tests, the inventory), architecture (batch
capacity, keyless aggregates, the guard's bound, a section on the two nets),
conventions (three rules, and the locale rule's reach into the GUI), extending
(a sqllogictest script), gotchas (the parser entries, the guard, the locale
scan, "Defects the SQL logic tests found"), code-map.
