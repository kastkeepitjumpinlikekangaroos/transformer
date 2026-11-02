# Testing plans — index and launch prompts

A hand-rolled property-based testing (PBT) harness for the SQL engine, so
we can fuzz orders of magnitude more cases than we can write by hand, built
bottom-up in plans 01-03. Plans 04 and 05 added answers from outside the
engine and plans as SQL. Plans 06-19 are the round the 2026-11-01 survey
proposed ([below](#plans-06-19-the-2026-11-01-survey)). Each file is
self-contained: read it, paste the launch prompt at its bottom into a fresh
Claude session, and the agent has what it needs.

## Design decisions (locked)

- **Hand-rolled generators + shrinkers.** No ScalaCheck, no Hedgehog, no new
  Maven dependency. Matches the project's build-it-ourselves philosophy
  (own JSON parser, own CSV state machine, own everything) and the
  no-heavy-deps rule. The harness is test-only Scala under
  `src/test/scala/com/transformer/fuzz/`.
- **Metamorphic + mode-differential oracles.** No external reference DB
  (SQLite/DuckDB/H2 are out — they conflict with the CLAUDE.md
  no-embedded-DB rule and bring SQL-dialect-mismatch noise). Correctness is
  decided by relations that must hold *within* the engine:
  - **Mode-differential** — a fixed `(data, query)` must produce the same
    result multiset across spill on/off, metrics on/off, partition/batch
    layout, and (cross-JVM) sharded-vs-collapsing. This is the generated
    generalization of the existing `*SpillTest` bit-equal parity checks.
  - **Metamorphic** — TLP (`WHERE p` ⊎ `WHERE NOT p` ⊎ `WHERE p IS NULL`
    == unfiltered) and optimizer-equivalence (NoREC-style) relations that
    need no oracle at all.

  Expected answers written outside the engine came later, as data rather
  than as an engine on the test classpath: [04](04-sql-logic-tests-and-whole-suite-configurations.md)'s
  sqllogictest scripts, this repo's own and slices of SQLite's corpus,
  whose answers five engines agreed on.

These two strategies target precisely the engine's novel, highest-risk
surfaces: three-valued NULL logic, the optimizer passes (FilterPushdown,
column pruning, CTE materialization, the sharding/broadcast gates), and the
parallel exchange / spill machinery.

## Priority and dependency table

| # | Plan | Effort | Risk | Depends on |
|---|------|--------|------|------------|
| [01](01-expr-parity-fuzzer.md) | Expr parity fuzzer (`eval` vs `evalVec`) | ~1 day | low | — |
| [02](02-data-gen-mode-differential.md) | DataGen + mode-differential over single-table SQL | 3-5 days | medium | 01 (shared harness) |
| [03](03-query-gen-metamorphic.md) | Multi-relation query gen + metamorphic (TLP) + shrinking | 1-2 weeks | medium-high | 01, 02 |
| [04](04-sql-logic-tests-and-whole-suite-configurations.md) | sqllogictest scripts under eleven verification modes, SQLite's corpus, the whole suite at batch capacity 1 / 2, sharded, serial, in another zone (**done** 2026-11-01) | 1 day | low | — |
| [05](05-plans-as-sql.md) | Plans as SQL: plan validation after every pass, a printer the binder reads back, the round trip as an slt mode and a fuzz property, EXPLAIN and golden plans; then plan 04's next round (join order, a streaming pairing, hash zero, CSV column order, spill cost, Postgres ports) (**done** 2026-11-01, the Postgres ports' five findings decided and landed) | several days | low-medium | 04 |
| [06](06-a-failed-run-leaves-no-partial-output.md) | A failed run leaves no partial output, and its rerun needs no cleanup: the defect the survey found, with the commit protocol the user chose, fault points at every write and a crash campaign (**done** 2026-11-01 overnight on `testing-round-3`: 5c2c5bc, 261d510, 0857a0e; one finding held out, a halt as a directory is created leaves it with no record) | 2-3 days | medium | none |
| [07](07-compiler-warnings.md) | The compiler warns, and a warning fails the build (**done** 2026-11-02 overnight on `testing-round-3`: d4cf5a1, 7519079, 0eff1a9, 029c683, 1c885d5, b8ede39, 9991420, b0cb011, ab3dba2; every decision taken as recommended, unconfirmed; one finding held out, VimEngine's `:join` on the last line runs a macro on where Neovim stops it) | 2-3 days | low | none; land early |
| [08](08-skips-cite-decisions.md) | Every skip names its decision, and a stale skip fails (**done** 2026-11-02 overnight on `testing-round-3`: 8f51068, 333fcda, c05ecaf, 140ebbb; every decision taken as recommended, unconfirmed; 19 rules in gotchas.md's ledger, 446 skips cite them, none was stale) | 1-2 days | low | none; before 10-14 |
| [09](09-semantics-tables.md) | The engine's rules as tables: one function catalog, the cast matrix, the coercion tables, a function sweep (**done** 2026-11-02 overnight on `testing-round-3`: 20254f4, 876c6ea, 5012f9d, 909ec92, cd291f5; both decisions taken as recommended, unconfirmed; 61 catalog entries, 9 tables of 9557 lines; 14 findings held for the user, none changed) | 4-6 days | medium (W1) | 08; pairs with 10 |
| [10](10-spark-typing-ports.md) | Spark's decimal and type-coercion tests, with Spark's ANSI answers (**done** 2026-11-02 overnight on `testing-round-3`: 64b7fa9, d1bf116, d5ff49c, b01cee5, fdf580d, 368c631; the three decisions taken as recommended, unconfirmed: `CAST(x AS DECIMAL(p,s))`, an INT or BIGINT beside a FLOAT a DOUBLE, BIGINT's decimal kept at 19 digits; 1,590 of 2,953 statements in 13 scripts, 619 answering as Spark does, 492 errors both raise, 464 skips by 14 rules; 45 held for the user, 3 of them a lowercase `like` no Turkish locale parses) | 3-5 days, +1-2 for CAST to DECIMAL | medium | 08; pairs with 09 |
| [11](11-postgres-ports-round-two.md) | PostgreSQL's integer, text, date and SELECT regression tests (**done** 2026-11-02 overnight on `testing-round-3`: 8f122ce, 6db2cd3, c3643a8, 911a797, 9a1f36d, d6b6769, bbb4fa9, 7ddbcf7, 77c4fc6, 7a41251, cbff308; the five decisions taken as recommended, unconfirmed; a checker holds all fifteen Postgres ports to Postgres's output, 911 records, none differs; ten new ports hold 616 of 1,470 statements, 393 answering as Postgres does, 147 skipped by seven rules, `int-text` and `date-text-iso` new; 94 held for the user: numerology's literals and GROUP BY an output name (51), and 43 both engines refuse for different reasons; an alias `minus`, which does not parse, is written quoted and reported) | 3-5 days | low | 08 |
| [12](12-cockroachdb-and-datafusion-corpora.md) | CockroachDB's and DataFusion's logic tests, and TPC-H with answers (**done** 2026-11-02 overnight on `testing-round-3`: 2f716e6, 7a841a6, 2fa4dd8, 6f1c821, 585200c; the five decisions taken as recommended, unconfirmed; 30 CockroachDB files (1,196 of 3,464 records run with its answers), 27 DataFusion files (2,470 of 6,494), eleven TPC-H queries with RisingWave's answers (ten as they are, Q14 by `decimal-result-type`); 611 skips by the ledger's rules, nine rows new; every other record held by what it needs; 64 findings held for the user, C1-C7 and D1-D7, C1 a silently dropped alias on `t.*`; the whole trees audited, no internal error) | 1-2 weeks | medium (the runner) | 08; 11 first is better |
| [13](13-external-file-fixtures.md) | Parquet and CSV files other programs wrote (**done** 2026-11-02 overnight on `testing-round-3`: 7f53028, e4d87d8, fc32cdd, 48c3447, 9d89d5f, 0767eca, 177b54f, 86e6bc7; the five decisions taken as recommended, unconfirmed, lz4-java as `at.yawk.lz4` 1.12.1; a zero-byte row group read, every parquet refusal in the reader's words naming the file, page CRCs checked, `binaryAsString`, Hadoop's LZ4 read; 133 parquet files (107 of parquet-testing, 26 pyarrow wrote) and 481 CSV inputs (DuckDB's, PapaParse's, csv-spectrum's) each held to a manifest line, 3.0 MB; 16 ledger rules pinned by the manifests; findings P1-P3 and C1 held, P1 a silent wrong answer in parquet-mr) | 3-4 days | low-medium | 08 |
| [14](14-multi-engine-generated-corpus.md) | A generated corpus whose answers Postgres and DuckDB agree on (**done** 2026-11-02 overnight on `testing-round-3`: febca22, 98e672e, a6c67ac; the five decisions taken as recommended, unconfirmed; `src/test/slt/agreed/`, 3,014 queries the fuzz generators drew, kept where PostgreSQL 17.11 and DuckDB 2.0.0 agree and the engine does too under every mode, seven features each held to its quota by `agreed_corpus_test`, 1.05 MB; 52 by design under `decimal-result-type` and `double-precision`, confirmed by Postgres computing the engine's types; one engine defect fixed, a `BETWEEN` over two DECIMAL types whose plan did not bind back; 45 records held, A1 new (a `SUBSTRING` position before the first character) and plans/testing/12's D3 and C3) | 4-6 days | low-medium | 08; gains from 15 |
| [15](15-generator-gates.md) | The fuzz generators draw what they leave out today (**done** 2026-11-02 overnight on `testing-round-3`: 3c8ee89, 394b70e, 96ca67f, 2ba017f, 7606c78, 8065a8d, 50e67c3, 49b1760; both decisions taken as recommended, unconfirmed: layouts agree on a failure on the data but where a WHERE or ON condition can move, and UPPER and LOWER map one character to one; every gate open but G7 (floats at the ends of their range, by its reason) and G16 (plan 06's): text past ASCII, every catalog function, joins of up to six tables in three styles, RANGE offsets, CTEs over CTEs, every type in the frontend catalog, negation and NOT, values at the edges and CASTs that can fail, each with its guard and a detection proof; one finding held, F1, ILIKE's fold parting from LOWER and UPPER over a dotless i and a dotted capital I) | 3-5 days | low-medium | none |
| [16](16-literature-oracles.md) | Oracles from the literature: rewrites, forced plans and known join answers (**done** 2026-11-02 overnight on `testing-round-3`: 33aa329, 5436b38, 7ace602, 65107cc, 1aa55dc, cf8dfbd, c87d7be; the three decisions taken as recommended, unconfirmed, decision 3 after W4's CERT property confirmed the inversion; EET's value-preserving rewrites of any subexpression of a bound plan over every generator and as the slt `rewritten` mode (6,278 script queries), every join order written into the text with the optimizer off and on and every join respelled onto each path and key codec, sharded and not, known answers for multi-way and outer joins from a split wide table, and TypeCheck, window cross_check and CERT, each with its guard and detection proof; three defects fixed: a CAST of a condition printed as SQL that does not parse, metrics running a sharded plan unsharded, and the guard's outer-join inversion; three findings held: T1 a DECIMAL compared with a float failing on NaN, G1 the guard's bound following the optimizer's join order, E1 an optimized restriction estimated larger) | 1-2 weeks | low | 15 (its W4), PlanSql |
| [17](17-whole-suite-runtime-checks.md) | Whole-suite runtime checks: batch invariants, leaks and chaos scheduling (**done** 2026-11-02 overnight on `testing-round-3`: ee326f9 W1, 0ab531f W2, f520f05 W3; the five decisions taken as recommended, unconfirmed; `--config=verify` checks every batch every operator emits against its schema (found a UNION ALL's right side named as itself, which CSV output refused, and ABS(NULL) a DOUBLE vector, both fixed; a STRING holds no half of a surrogate pair, refused where one can come in), `--config=leaks` audits what each test JVM leaves open, with the close protocol releasing what a LIMIT, a failure or a stop leaves (found a CSV writer's staged file left open when its header could not be written, fixed), `--config=chaos` perturbs every thread handoff by a seed (green at five seeds and over both DAG campaigns, nothing found); suite 83/83 under each, jaffle 15/15, polymarket 97 s; TopNExec on main needs a walk branch on merge) | about a week | low-medium | none; W3 after 06 |
| [18](18-one-rule-per-concept-in-bytecode.md) | One rule per concept, held in bytecode | 2-3 days | low | none |
| [19](19-mutation-testing.md) | Mutation testing measures the net | 3-4 days | low | last, after 06-18 |

## Recommended sequence

Plans 01-03 strictly in order. Phase 01 builds the shared core (`Rng`, `Shrinker`,
`Props`, `RowOracle`) against an oracle we already trust (`ExprBatchTest`),
so the plumbing is proven before it carries a harder property. Phase 02 adds
the data generator and the mode-differential runner. Phase 03 grows the
query generator to joins/window/CTE/union and adds the metamorphic oracles
plus full AST+data shrinking — the bulk of the work. Plans 06-19 have their
own order, below.

## Plans 06-19: the 2026-11-01 survey

After plan 05 the user asked what more the engine could borrow from other
projects' testing, and how to keep making things consistent. A survey read
the whole test suite against nine kinds of check other engines use, and
checked outside corpora's licenses at their sources. The user took all nine;
each plan is one of them, or one source of outside answers:

| # | Survey category | Plans |
|---|---|---|
| 1 | What a run leaves when it fails | 06 |
| 2 | More answers from outside the engine | 10 (Spark), 11 (Postgres), 12 (CockroachDB, DataFusion, TPC-H), 13 (parquet and CSV files), 14 (a generated corpus Postgres and DuckDB agree on) |
| 3 | Oracles from the literature | 16 |
| 4 | Runtime checks over the whole suite | 17 |
| 5 | What the generators leave out | 15 |
| 6 | The engine's rules as tables | 09 |
| 7 | One rule per concept, in bytecode | 18 |
| 8 | Skips tied to decisions | 08 |
| 9 | Hygiene: compiler warnings, mutation testing | 07, 19 |

**Order.** 06 first: it fixes a confirmed defect in the part of the product
nothing else tests. 07 next, so later code is held to its warnings, then 08,
so every skip the ports add cites a decision from its first record. Then
09 with 10, one round on typing: the same rules, held once to their own
tables and once to Spark's answers. Then 11, 12, 13 and 14, the outside
answers; 15, then 16, which builds on 15's shapes; 17 and 18; 19 last, to
measure the net the others built. 07, 13, 15, 17 and 18 depend on no other
plan and can move earlier.

Two sessions should not run plans that edit the same files at once. The slt
runner is shared by 08, 10, 11, 12 and 14. The fuzz generators are shared by
15, 16 and 14's renderer. The output writers and `DataJob` are shared by 06
and 17's W3. And every plan edits `docs/testing.md`.

**Decisions.** Each plan lists its open decisions, each with a recommended
option. Plan 06's are settled: the user chose option (c) for the commit
protocol, and that a final record that cannot be written fails the task.

**Defects found while writing the plans**, each in the plan that fixes it:

| Defect | Status | Plan |
|---|---|---|
| A task that fails after its parts are committed (a later partition, a validation that throws, a record that cannot be written, a crash) leaves part files and no record, and the next run refuses the task until someone deletes them | fixed (5c2c5bc) | 06 |
| The GUI's `ResultPersister` writes into a directory of stray part files and records every `part-*` there as its output | fixed (5c2c5bc, before 07's warnings could) | 06 |
| An INT or BIGINT beside a FLOAT is typed FLOAT: `CAST(16777217 AS INT) = CAST(16777216 AS FLOAT)` is TRUE, and `COALESCE(CAST(NULL AS FLOAT), 2147483647)` is 2.1474836E9 | fixed (d1bf116, decision 2 taken overnight as recommended) | 10, decision 2 |
| A lowercase `like` (and `ilike`, `similar to`, `rlike`) is a parse error under a Turkish default locale: JSqlParser 5.2 upper-cases the keyword with the JVM's locale, `LİKE`, and the message names the parser's class | confirmed with the parser alone, held for the user | 10, W5 |
| A parquet row group whose footer reports 0 bytes is never read: 25 rows read as none (`ParquetReader.packRowGroups`) | confirmed against pyarrow | 13, W1 |
| Parquet refusals in parquet-mr's, Thrift's or the JVM's words: a NullPointerException's message for an unknown logical type, a missing LZ4 class | confirmed | 13, W2 |
| UPPER and LOWER map case in full while ILIKE folds a character at a time: `'ß' ILIKE 'SS'` is false, `UPPER('ß') LIKE UPPER('SS')` true | confirmed | 15, decision 2 |
| `SELECT 0.0e` answers `0.0` in a column `e`; `1_000_000` is refused as an unknown column | confirmed against Postgres's output, held for the user with numerology.sql's other literals (51 records) | 11, W4 |
| `GROUP BY` an output column's name is refused as an unknown column, where Postgres groups by it | confirmed, held for the user | 11, W4 |
| An alias named `minus` is a parse error in the parser's words: JSqlParser reserves `MINUS` | confirmed, held for the user | 11, W4 |
| The plan round trip folded one negation of a literal, so `-(CAST('-9223372036854775807' AS BIGINT))` failed the `reprinted` mode with a right answer | fixed in the test oracle (9a1f36d) | 11, W3 |
| Gates gone stale: TLP never partitions on a float column, QueryGen never draws TRIM, the expression fuzzer never draws 23 of the 40 scalar functions | the 23 functions drawn (20254f4); the rest open | 15, 09 |
| An alias after a qualified star is dropped silently: `SELECT kv.* AS foo FROM kv` answers `kv`'s columns under their own names | confirmed against CockroachDB's answer, held for the user | 12, C1 |
| An abandoned query (a LIMIT, an error) keeps its files open until garbage collection and its spill directories until the JVM exits | suspected from the code | 17, decision 4 |
| The nested-loop guard sizes an outer join smaller than the inner join it contains, so a restriction of an accepted query can be refused | confirmed by CERT and fixed (cf8dfbd, decision 3 taken overnight as recommended); a restriction the optimizer orders anew can still be refused, held as G1 | 16, decision 3 |
| Parquet and spill write half a surrogate pair as `?` where `CsvWriter` refuses it, and gotchas.md says otherwise | read in the code | 17, decision 2 |
| The plan of a CAST of a condition prints as SQL JSqlParser does not parse (`CAST(t.a = t.b AS VARCHAR)`), so `EXPLAIN` and the `reprinted` modes break on it | fixed (33aa329) | 16, W1 |
| With metrics on, a sharded join, aggregate, DISTINCT and window run on one partition, each telling its exchange child by its class | fixed (7ace602) | 16, W2 |
| A DECIMAL compared with a FLOAT or DOUBLE column fails on NaN while running, where INT and BIGINT compare as a DOUBLE and arithmetic refuses the pair as it binds | confirmed, held for the user (T1) | 16, W4 |
| A coverage guard's failure message prints `${entry.example.get}` (a missing `s`), `MODULE.bazel` names a file rules_scala 7 does not have, conventions.md misnames the direct-deps check | fixed (029c683, 9991420) | 07 |
| `Row.getInt` and its siblings, and the row path's `ABS`, throw a bare `MatchError` on a value they have no case for | fixed (0eff1a9) | 07 |
| VimEngine's `:join` on the last line ignores the failure, so a macro runs on where Neovim stops it | confirmed against Neovim 0.11.4, held for the user | 07 |
| Nine `transformer.*` properties and seven `TRANSFORMER_*` variables are read in eight classes, three of them documented nowhere | read in the bytecode | 18, decision 3 |

## Shared module (introduced in 01, extended in 02/03)

```
src/test/scala/com/transformer/fuzz/
  BUILD.bazel              # testonly scala_library `fuzz` + one scala_junit_test per property
  Rng.scala                # SplittableRandom wrapper; the seed is the repro key
  Props.scala              # property runner: N seeds -> on failure, shrink + print repro
  Shrinker.scala           # generic shrink combinators + data/AST shrinkers
  RowOracle.scala          # canonical row normalization + multiset compare (float tol, NULLs)
  DataGen.scala            # (02) Schema + MaterializedView: cols/types/rows/NULL density/partitions
  ExprGen.scala            # (01) typed random Expr trees over a schema
  QueryGen.scala           # (02 single-table, 03 multi-relation) typed random SELECT
  oracle/
    ExprParity.scala       # (01) eval vs evalVec, reusing ExprBatchTest's normalize/compare
    ModeDifferential.scala # (02) same (data,sql) across modes -> multiset-equal
    Tlp.scala              # (03) ternary-logic-partitioning metamorphic relation
```

## Required workflow gates (applies to every plan)

From `CLAUDE.md`. The harness is test-only code, so the e2e gates are
unaffected but must stay green:

1. `bazel test //...` green (the new property targets run a small fixed-seed
   batch here — see each plan's tagging note).
2. `java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar` exits 0,
   15/15 Succeeded.
3. `java -jar bazel-bin/examples/polymarket/polymarket_deploy.jar` matches
   its pattern, 14 Succeeded / 2 ValidationFailed / 1 Skipped (the launcher
   exits 0 only then; it needs the dataset in `~/Downloads/archive/`).
4. Docs updated where claims are now stale — primarily `docs/testing.md`
   (new targets) and `docs/extending.md` (a "add a generator / property"
   recipe); `docs/gotchas.md` for the cross-JVM class-load-frozen sysprop
   constraint.

Project-wide rules from `CLAUDE.md`:
- Scala 2.13.16, JDK 21; no version bumps without asking.
- No emojis.
- **No new dependencies** — the whole point of the hand-rolled decision.
- Prefer editing existing files to creating new ones (the harness is
  genuinely new, so new files are justified; docs are edits).
- No backwards-compatibility shims unless asked.
- Per the repo's readability rule: describe current behavior in comments;
  do not leave stale "Phase N" scaffolding comments in the landed code.

## Tagging

Default `bazel test //...` runs each property at a small fixed seed count so
regressions surface in the normal gate. Long fuzz campaigns are opt-in via a
`fuzz` tag, excluded by default exactly like `-perf` — add `,-fuzz` to the
`--test_tag_filters` line in `.bazelrc` and run campaigns with
`bazel test //src/test/scala/com/transformer/fuzz/... --test_tag_filters=fuzz`.

## Launch prompts

Each prompt is embedded at the bottom of its own plan file. Open a fresh
session, prefer `--effort max`, and paste the prompt — the agent reads the
plan from disk and acts.
