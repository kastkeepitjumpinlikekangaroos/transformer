# Robustness plans — what the fuzzers cannot reach

Eight plans from an audit on 2026-09-10, run from the opposite direction to the
fuzz harness, six that came after: 09 from widening the SQL corpora to
decimal arithmetic, and 10 to 14 from plan 05's frontend fuzzer, and 16 to 29
from a second audit on 2026-10-31 (see [The second audit](#the-second-audit-2026-10-31)). The five existing fuzzers generate inputs the engine is *expected*
to handle and ask whether the answer is right. This audit asked what the
generators **structurally cannot produce**, and then checked those surfaces by
hand.

Three blind spots, and each one was hiding real defects:

| blind spot | why the fuzzers cannot see it | what was behind it |
|---|---|---|
| SQL **outside** the documented grammar | `QueryGen` / `MetaQueryGen` render their own typed AST, so the corpus *is* the supported grammar | 18 clauses that parse, run and return a wrong answer — [01](01-reject-or-honor-every-clause.md) |
| 4 of the engine's 10 `DataType`s | `DataGen.ColumnTypes` and `ExprGen.GenTypes` list six | `BinaryType` `=` is reference equality and its `ORDER BY` is identity-hash ordering; `DecimalType` comparison is `doubleValue`-lossy; `GROUP BY` disagrees with `=` for both — [02](02-total-order-for-decimal-and-binary.md) |
| bytes transformer did not write | every round-trip fixture is produced by our own writer, so writer and reader agree while both disagree with the format | parquet timestamps off by 1000x for any non-MICROS file; `DECIMAL` has no mapping and reads as int or raw bytes — [03](03-parquet-external-format-fidelity.md) |

Plans 01-03 and 08 are `src/main` defects. Plans 04-07 are the testing work that
makes each blind spot visible from now on — they are in this directory rather
than in [`plans/testing/`](../testing/README.md) because each exists to keep a
specific finding here fixed, and reading them next to the defect is the point.

Everything below was **executed** against commit `d4bb409`, not inferred: the
audit used throwaway probe targets that ran each case through `SqlEngine.execute`
and printed real output. The probes were deleted; each plan carries the SQL and
the measured result so a future session can re-derive them in minutes. Baseline
at the time of the audit: `bazel test //...` 46/46 green.

## Priority and dependency table

| # | Plan | What | Status | Effort | Risk | Depends on |
|---|------|------|--------|--------|------|------------|
| [01](01-reject-or-honor-every-clause.md) | Reject or honor every clause the parser accepts | 18 silently-dropped clauses, incl. `USING`/`NATURAL` JOIN silently becoming a **cartesian product** | **done** 2026-10-01 | 2-3 days | medium | — |
| [02](02-total-order-for-decimal-and-binary.md) | One total order for `Decimal` and `Binary` | `Ops.eq`/`Ops.cmp`; the repo's own bug class, in the types the float work never reached | **done** 2026-10-03 | 2-3 days | medium | — |
| [03](03-parquet-external-format-fidelity.md) | Parquet timestamp units and `DECIMAL` | silent 1000x timestamp error; decimals read as unscaled ints or raw bytes | **done** 2026-10-03 | 1-2 days | low-med | — |
| [04](04-widen-fuzz-corpus-to-every-type.md) | Widen the fuzz corpus to all ten types | the guard that keeps 02 and 03 fixed | **done** 2026-10-03 | 2-3 days | low | 02 (else red) |
| [05](05-frontend-robustness-fuzzer.md) | Frontend robustness fuzzer | "nothing silently dropped" + "errors are user-facing" as properties | **done** 2026-10-04 | 3-5 days | low | 01 (shares its clause table) |
| [06](06-io-roundtrip-and-external-fixtures.md) | I/O round-trip properties + external parquet fixtures | the helper 03 needs, plus CSV state-machine fuzzing | **done** 2026-10-14 | 3-4 days | low | — (03 wants its fixture helper) |
| [07](07-hydration-oracle-on-the-dag-corpus.md) | Hydration oracle on the existing DAG corpus | the read side of the records the DAG fuzzer already writes | **done** 2026-10-15 | 2 days | low | — |
| [08](08-unconsumed-input-failure-is-swallowed.md) | An unconsumed input's load failure is swallowed | the run said `succeeded` and nothing recorded the failure; every failed input is now a warning, and a warning never fails a run | **done** 2026-10-15 | half a day | low | — |
| [09](09-branch-result-types.md) | `CASE`, `COALESCE`, `IF`, `GREATEST` and `UNION` take their first branch's type | a wider decimal in a later branch was kept under a narrower declared type, so writing or spilling it failed; `THEN 1 ELSE 2.5` was 2; values that become one result now take a type that holds each | **done** 2026-10-16 | 1-2 days | medium | — |
| [10](10-error-messages-name-internals.md) | A failing query's message names the engine's internals | every parse error named JSqlParser's class, and the binder's refusals the AST class; pooled failures arrived wrapped in `ExecutionException` (sometimes with no message), which is what a job recorded; refusals now say what SQL calls the construct, and a pooled failure arrives as itself | **done** 2026-10-17 | 1 day | low | — |
| [11](11-functions-bind-over-arguments-they-cannot-take.md) | Functions and conditions bind over arguments they cannot take | `SUM` over a string, the math functions over a date, a NULL `ROUND` scale, a non-boolean `ON` failed mid-run; `ABS` of a date was NULL and `WHERE d` kept no rows; every condition is now a BOOLEAN, every argument the kind its function declares, and only a STRING is text | **done** 2026-10-18 | 1-2 days | medium | — |
| [12](12-text-the-binder-never-sees.md) | Text the parser accepts and the binder never sees | a statement after `;` was ignored; a repeated `ORDER BY` / `LIMIT` kept its last, or split with a UNION's last arm; a CTE named twice kept its second; a JOIN with no ON was a cross join; `NOT NOT f IS NULL` kept the wrong rows; each is refused now | **done** 2026-10-18 | 1 day | medium | — |
| [13](13-literals-are-read-as-written.md) | Literals and quoted identifiers are read as written | `'it''s'` was five characters; `"a"` named no column; `99999999999999999999` was a raw `NumberFormatException`; `1e400` was `Infinity`; a number beside a DECIMAL went through a DOUBLE; a string literal served as an alias; each literal and name now means what SQL writes | **done** 2026-10-30 | 1 day | medium | — |
| [14](14-pathological-nesting.md) | Deep and unbalanced SQL | a task whose SQL overflowed the binder's stack **hung its job forever**; unclosed parentheses ran the parser into its timeout; a chain of joins without an equality multiplied past the guard; no `Error` leaves a run waiting now, a query nests at most 256 levels, the same everywhere, and unbalanced text fails at once | **done** 2026-10-30 | 1-2 days | low (the hang) | — |
| [15](15-what-a-reopened-run-shows-without-its-record.md) | What a reopened run shows for a task without its record | `job.json`'s entry carried no timestamps, failed validation names or output, so such a task reopened with a 0 ms duration and every validation card PASSED; a record naming another task was trusted; nothing told the user | **done** 2026-10-15 | half a day | low | — |

**Done:** plan 01. Eighteen was an undercount: classifying every getter of every
AST class the binder reaches turned up 93 rejections. Every clause the binder
does not read now fails at bind time naming itself, and the eight cheap ones the
plan listed are honoured instead (OFFSET, NULLS FIRST / LAST, DISTINCT inside
SUM / AVG / MIN / MAX, COUNT(ALL x), ILIKE, LIKE ... ESCAPE, a SELECT with no
FROM, FETCH FIRST and TOP). `sql/plan:unsupported_clauses_test`
derives what the binder reads from its bytecode and keeps the audit honest across
JSqlParser upgrades. Two more silent drops turned up along the way, fixed in their
own commits: an aggregate named only in ORDER BY, and a HAVING with no GROUP BY.

**Done:** plan 02. Decimals compare by value and byte arrays by unsigned
content in `Ops`, its vectorized twin and every hash or dedup site (the codecs,
the DISTINCT aggregates' sets, shard routing and spill buckets), so GROUP BY,
DISTINCT and joins agree with `=`; a decimal key reads back at its declared
scale. Beyond the plan: numbers now widen to a decimal (the CAST was missing),
two BIGINTs past 2^53 compare as longs, and a BINARY is never read as text.
Details in the plan's Outcome section.

**Done:** plan 03. Every fixture is now written by parquet-mr itself
(`read/parquet:external_parquet`), and run against the old reader first: 29 of
32 failed. Timestamps read in their file's unit, decimals in all four layouts
(and write, so a `DECIMAL` column spills), and every other annotation is
decided rather than read as its physical type. The fixtures also caught a
defect the plan did not list: a projected scan rebuilt the file's schema from
the writer's mapping, so it decoded `INT96` as `INT64` and threw on any
`FIXED_LEN_BYTE_ARRAY`, and a glob whose files ordered columns differently
swapped their values. Details in the plan's Outcome section.

**Done:** plan 04. Every fuzz corpus draws all ten types (DECIMAL at three
declared types, one per parquet layout), every oracle compares with the
engine's own equality (`RowOracle`: content for a byte array, value for a
decimal), and one coverage guard per type and per property asserts each type
reaches its sites with the values its comparison gets wrong. The widened
corpora found two `src/main` defects, both fixed first: `ABS` of a decimal
computed in DOUBLE (and `MAX(ABS(k))` returned NULL), and a NULL-typed column,
the only NULL a DECIMAL or BINARY has, failed every spill. The metamorphic,
sharded and mode-differential campaigns are green at 20000 seeds each. Details
in the plan's Outcome section. Its loose ends (quarter-step decimals, no
decimal arithmetic, a lowered guard bound) were closed on 2026-10-04 once the
engine computed decimals exactly; that work found
[09](09-branch-result-types.md). See the plan's Follow-up.

**Done:** plan 05. `fuzz/frontend_fuzz_test` writes SQL text, which no other
corpus does: mutated queries, the `UnsupportedClauses` table spliced into every
context and position (read from `src/main`, so it cannot drift from the
rejection pass), honoured clauses beside their clause-free twins, and pairs of
equivalent spellings. Its three properties found five families of defects, now
plans 10 to 14, each recorded in the fuzzer with a repro that fails the suite
once the defect is gone. Plan 14's is the most severe: one deep expression in a
task hangs the whole job, silently. Details in the plan's What landed section.

**Done:** plan 06. The file formats have properties of their own:
`fuzz/parquet_fuzz_test` writes globs with parquet-mr in every layout parquet
allows and holds our reader to the format and parquet-mr to the same reading;
`fuzz/csv_fuzz_test` round-trips data through the CSV writer and reader, parses
text it wrote itself, and holds inference to its rules and to a fixed point.
They found two parquet pushdown rules that dropped rows (a string range, an
unsigned narrow integer) and, with a probe beside them, a dozen CSV defects:
NULL rows lost in one-column files, empty strings read as NULL, BINARY
unreadable, malformed text read silently, inference typing `1.5` and `true` as
a BOOLEAN. All fixed; the CSV semantics were the user's decisions. Details in
the plan's Outcome section.

**Done:** plan 07. `fuzz/dag_scheduler_fuzz_test` reopens every generated run
in a `JobSession`, as the GUI opens a job directory, and holds what it rebuilds
to what the runs returned; then damages the tree and reopens it; and half the
cases carry adversarial task names, failure messages and validation names.
`JobSession` and `DagLayout` build without JavaFX as `gui:session`, so a fuzz
target reaches them with no toolkit dependency. Five defects, all fixed: a
reopened task's enqueue time dropped, a Failed or Skipped task's phantom output,
newest-first orders cut to the millisecond, half a surrogate pair failing every
record write, and colliding validation sample files. What a reopened run shows
without a task's record was the user's decision, [15](15-what-a-reopened-run-shows-without-its-record.md),
landed the same day. Details in the plan's Outcome section.

**Done:** plan 08. An input that failed to load and that no task read used to
vanish: the run said `succeeded`, with no warning and nothing in `job.json`.
The user chose to warn and still succeed, for every input that fails to load,
read or not, since a skipped task names only the first failure it met. The
warnings list every failed input in declared order, an unread one's saying no
task reads it, and a warning never fails a run. The DAG fuzzer's generator
had given every input a reader, so it never drew the shape; it now declares an
input no task reads, and `DagSchedule` holds the warnings to one per failed
input. Details in the plan's Outcome section.

**Done:** plan 09. Values that become one result (a `CASE`'s, `COALESCE`'s,
`IF`'s, `GREATEST`'s and `LEAST`'s, a `LAG` or `LEAD` default beside its value,
each column of a `UNION`'s arms) meet by the comparison rule through
`Analyzer.branches`, with one type that holds each: two decimals as the
`DECIMAL` with the longer integer part and the larger scale, and every other
mix a bind error naming the CAST, which the user chose knowing it changes
queries that run today. A CAST between DECIMALs now fits its target, and two
decimals compare as a type that holds both. `NULLIF` compares as `=` does. The
`inBranch` gate is gone; the corpora had drawn the shape so rarely that both
SQL generators now draw it on purpose, and run against the base commit the
mode-differential property fails within 1300 seeds. Details in the plan's
Outcome section.

**Done:** plan 10. A parse error says what the parser said, without its class;
a construct the binder has no case for is refused by what SQL calls it, from a
table in `UnsupportedClauses` that a test keeps complete, and no message renders
a node through JSqlParser's `toString`, which threw for a ROW-type CAST; a
failure on the pool reaches the caller, and a job's `_run.json`, as the task's
own exception, a fatal error still wrapped so the job runner records it. An
ungrouped column's error says the rule, `ROLLUP` and `CUBE` are refused as
groupings, `CAST(boolean AS DOUBLE)` is 1 or 0, and the nested-loop guard throws
the `IllegalArgumentException` every refusal is. Two defects turned up on the
way and were fixed with it: a window over a grouped query lost the groups'
aggregates (`NoSuchElementException`), and a GROUP BY key written another way
was an unknown column. Details in the plan's Outcome section.

**Done:** plan 11. Every condition (WHERE, HAVING, ON, CASE WHEN, IF, COUNT_IF,
AND, OR, NOT) is a BOOLEAN, or NULL, and anything else is a bind error naming
the comparison or the CAST; a function declares beside its arity what each
argument must be (`Funcs.signature`), and the aggregates that add up take a
number. The user chose the strict side of both decisions, knowing each changes
queries that run today: `WHERE i` over an INT, `WHERE 'true'` and `NOT i` are
errors, and only a STRING is text, so `LENGTH(12)`, `i LIKE '1%'` and
`'id-' || id` each name `CAST(x AS STRING)`. A NULL scale, position, length or
seed is NULL. Found on the way and fixed with it: `WHERE d` over a DATE kept no
rows, a BIGINT `SUBSTRING` position wrapped round, `ROUND` ignored a third
argument, and `ROUND` and `TRUNC` answered wrongly at a double's edges
(`ROUND(1e10, 10)` was 922337203.68). The NULL-scale exclusions left `QueryGen`
and `ExprGen`, each with a coverage guard. Details in the plan's Outcome section.

**Done:** plan 12. Text the parser accepts and leaves out of the AST is
refused where it shows: `SqlParser` refuses a statement after `;` and a
repeated `ORDER BY`, `LIMIT`, `OFFSET` or `FETCH`, which the statement rendered
back holds one fewer of (a scan for the keywords would have refused a column
named `limit`); the binder refuses a LIMIT or OFFSET split between a UNION's
last arm and the union, a WITH naming one query twice, and a NOT hung on an
operator's left side. The user chose to refuse a JOIN with no ON, and with it
the ONs JSqlParser accepts where SQL takes none (after CROSS JOIN or a comma)
and a second ON on one join, which had been ANDed. The NOT item was broader
than recorded: over a BOOLEAN, `NOT NOT f IS NULL` kept the non-NULL rows, and a
lone NOT as a one-argument call's argument (`COUNT_IF(NOT f > TRUE)`) was read
the same way. `BeyondTheTable` grew from ten constructs to 24, each refused
cleanly in every context but plan 13's. Details in the plan's Outcome section.

**Done:** plan 13. A string literal is its value, so `'it''s'` is `it's`; a
name in double quotes or backquotes is the name between them, matched without
regard to case as every name is (the user's choice); an integer past BIGINT is
the DECIMAL of its digits and `-9223372036854775808` the smallest BIGINT; a
number no DOUBLE holds is refused rather than Infinity or 0; a string literal
where an alias goes is refused; and a number beside a DECIMAL keeps its
written digits and scale, which the user chose knowing it changes the type of
`price * 1.10`. Found on the way and fixed with it: a GROUP BY key matched a
SELECT item that differed in a literal's case, since the binder lower-cased
the text it matches by (a silent wrong answer), and a job refused a CTE
spelled other than its references, which JSqlParser's `TablesNamesFinder`
named as tables. Details in the plan's Outcome section.

**Done:** plan 14. No `Error` leaves a job waiting: each worker posts its
completion in a `finally` and turns whatever it caught into its task's or
input's failure, and a task that runs out of memory stops the run, saying so.
A query nests at most 256 levels, the same on every machine (the user's
choice, over catching the overflow), with AND and OR chains regrouped into
balanced trees so a query builder's list of conditions binds at any length;
JSqlParser's own overflow is the same refusal, and parentheses that do not
balance fail at once, naming the one at fault, where they ran the parser into
its time limit. The nested-loop guard also bounds a chain of joins without an
equality (the user kept the old rule beside it, so a small range join against
a large table still runs). Found on the way: two passes folded AND chains back
left-deep, undoing the regrouping, and a UNION of 2000 arms took fifteen
seconds and 5000 overflowed; both now build balanced trees. Details in the
plan's Outcome section.

Plans 01 to 15 are done; 16 to 29, from the second audit, are below.

**Suggested order.** [01](01-reject-or-honor-every-clause.md) first — it has the
worst single defect in the set (a dropped join condition is a cartesian product,
which is a wrong answer *and* a resource problem). Then
[02](02-total-order-for-decimal-and-binary.md) immediately followed by
[04](04-widen-fuzz-corpus-to-every-type.md); landing 02 without 04 leaves the fix
unguarded, and landing 04 without 02 turns the suite red.
[07](07-hydration-oracle-on-the-dag-corpus.md) is the best value-per-day in the
directory — the corpus, the generator and the shrinker all already exist, so it
is one oracle plus one mutation mode. [08](08-unconsumed-input-failure-is-swallowed.md)
is half a day and can go any time, but needs a decision from the user before code.

Plans 01 and 05 both touch the unsupported-clause table; 02 and 04 both touch
`Ops` semantics and `RowOracle`; 03 and 06 both want the external parquet fixture
helper. Those three pairs should be branched in sequence, not off `main` in
parallel. 07 and 08 are independent of everything.

## The second audit (2026-10-31)

Every plan above was done, every fuzz campaign green, and the question was again
what the harness cannot see. The answer this time was not a surface the
generators cannot reach but a kind of defect no relation they check can detect:
**a deterministic wrong answer**. Mode differential, TLP, NoREC, join
commutativity and aggregate decomposition compare the engine with itself; a
result that is wrong the same way in every mode, layout and spelling passes all
of them. Integer overflow wraps alike everywhere; RANGE-as-ROWS gives the same
wrong running total under every layout once the sort is fixed; `ORDER BY b`
resolves `b` the same way in every relation the corpus builds.

The audit ran ~250 probe queries and a dozen directory jobs through
`SqlEngine.execute` and `DataJob.run` at `b2c83fc`, printing results (the probe
target was deleted; each plan carries its SQL and the measured answer). It found
fourteen defects, nine of them silent wrong answers. The user decided the eight
semantic questions on 2026-10-31, the recommended option each time.

| # | Plan | What | Status | Risk |
|---|------|------|--------|------|
| [16](16-locale-dependent-names.md) | Names and case folding depend on the JVM locale | under tr-TR, `min()`, view names and `CAST(x AS int)` fail; `UPPER` differs | **done** 2026-10-31 | low |
| [17](17-case-evaluates-every-branch.md) | CASE, IF and COALESCE evaluate every branch | a guarded CAST or overflow fails on rows the guard excludes; `eval` and `evalVec` disagree | **done** 2026-10-31 | medium |
| [18](18-integer-overflow-wraps.md) | Integer arithmetic, SUM and narrowing CASTs wrap | `2147483647 + 1` is negative, `SUM` of BIGINTs goes negative, `CAST(NaN AS INT)` is 0 | **done** 2026-10-31 | medium |
| [19](19-rounding-through-double.md) | ROUND, TRUNC, FLOOR and CEIL compute in DOUBLE | `ROUND(1.005, 2)` on a DECIMAL is 1.0; `FLOOR` of a BIGINT past 2^53 changes it | **done** 2026-10-31 | medium |
| [20](20-range-frames-run-as-rows.md) | RANGE frames, and the default frame, run as ROWS | tied rows get different running totals, in arbitrary order; RANGE offsets count rows | **done** 2026-10-31 | medium |
| [21](21-two-float-equalities.md) | Floats have two equalities | a hash join matches NaN to NaN and misses -0.0 = 0.0, which the same predicate as a filter does not | **done** 2026-10-31 | medium |
| [22](22-order-by-sees-input-columns.md) | ORDER BY resolves names against input columns | `SELECT -b AS b ... ORDER BY b` sorts by the input; `ORDER BY n` on `COUNT(*) AS n` is refused | **done** 2026-10-31 | medium |
| [23](23-clauses-that-cannot-change-the-answer.md) | Constant sort, group and partition keys | `ORDER BY 'b'` sorts nothing; a pattern ending in its escape is FALSE | **done** 2026-10-31 | low |
| [24](24-cast-reads-javas-number-grammar.md) | CAST reads Java's number grammar | `' 5'`, `'5d'`, hex, Arabic-Indic digits cast; `'1e400'` is Infinity | **done** 2026-10-31 | low |
| [25](25-dates-past-the-storable-range.md) | Dates and timestamps past the storable range wrap | `CAST('+999999999-12-31' AS DATE)` is `+466210-05-14` | **done** 2026-10-31 | low |
| [26](26-duplicate-column-names.md) | Outputs and CSV headers repeat column names | the defect fails the task downstream, naming neither | planned | low |
| [27](27-config-keys-are-not-checked.md) | Job configs ignore unknown keys and unreadable values | a job with `delimeter`, `partiton_by`, `spill: yes` succeeded | planned | medium |
| [28](28-malformed-templates-pass-through.md) | A malformed `{{ }}` stays in the text | `'{{ today - 1d }}'` filtered every row out, silently | planned | low |
| [29](29-stale-files-join-an-output.md) | Files the run did not write join a task's output | a stray part file doubled a downstream task's rows | planned | low |

**Order.** 16 first (small and independent). 17 before 18 and 19, which add
run-time errors a CASE must be able to guard. 20 to 22 are independent of each
other but all touch the SQL docs. 23 to 26 are small frontend and I/O
refusals; 27 to 29 are the job runner. Each lands as its own commit with its
tests and docs.

**The testing lesson.** Each plan that fixes a deterministic wrong answer adds a
property that tests meaning rather than self-agreement: a brute-force window
reference (20), exact integer arithmetic checked in `BigInt` (18), `JOIN ON p`
against `CROSS JOIN WHERE p` and GROUP BY keys pairwise distinct under `=` (21),
guarded-error shapes in the expression corpus (17), CAST against CSV's reading
(24), and a test target under a Turkish locale (16).

## Known outstanding, deliberately NOT planned here

The audit also re-confirmed the inventory of things that are **already known and
documented** as limitations. None of these is a silent wrong answer, so none
became a plan; they are listed so the set above is not mistaken for the whole
picture. All are in [`docs/gotchas.md`](../../docs/gotchas.md) §"What's
intentionally NOT done" unless noted.

- **`RANGE` window frames execute with `ROWS` semantics** (now [20](20-range-frames-run-as-rows.md)). Confirmed wrong with
  ties: `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` over a tied `ORDER BY`
  gives `10, 30` where RANGE requires `30, 30`. Documented in README and gotchas.
  The only documented *wrong answer* in the engine — promote it to a plan if a
  workload needs RANGE.
- **Numeric overflow wraps silently** (now [18](18-integer-overflow-wraps.md)). `SUM` over two `Long.MaxValue` rows returns
  `-2`; `int * int` wraps. Matches Spark's non-ANSI default, but is undocumented
  and untested — worth one line in the README and a regression either way.
- **Spill caveats**: `COUNT(DISTINCT …)` silently falls back to the in-memory
  path; grace hash and window spill use a fixed 16 buckets with no recursive
  bucketing, so a hot key can still OOM; `WindowExec` spill needs a non-empty
  `PARTITION BY` consistent across every window spec.
- **Residual deadlock shape**: directly-constructed, lazily-**nested** exchanges
  drained from pool tasks can still wedge. Engine plans cannot produce that shape
  since [`plans/bugfixes/02f`](../bugfixes/02f-nonblocking-materialization.md)
  pre-materializes bottom-up, and `exchange_deadlock_stress_test` keeps the red
  instrument available via `STRESS_LAZY=1`.
- **No subqueries** (scalar, `IN`, `EXISTS`, derived tables in `FROM`) and **no
  `EXCEPT`/`INTERSECT`** — all five reject cleanly with a specific message, which
  is the correct behaviour for an unimplemented feature.
- **`CAST(x AS TIMESTAMP)` was not implemented** (`CAST … AS DATE` was) and
  **decimal arithmetic threw**. Both surfaced as exceptions rather than wrong
  answers. `CAST(x AS TIMESTAMP)` landed on 2026-10-02 (`5e25f20`), and decimal
  arithmetic, an exact decimal `SUM` and a decimal `AVG` on 2026-10-04
  (`8eccd9b`), after which the SQL corpora draw both (see plan 04's
  Outcome).
- **`LENGTH` and `SUBSTRING` count UTF-16 code units**, so a string of one
  astral character plus `ab` has `LENGTH` 4 rather than 3, and `SUBSTRING(s, 1, 1)`
  returns half a surrogate pair. Deliberate-by-default (it is what Java does);
  undocumented.
- **No whole-stage codegen, no dynamic column-value partitioning, no
  `INFORMATION_SCHEMA`, no multi-statement SQL, CTEs buffer in heap.** All by
  design; see gotchas.
- **Date and Timestamp are fine.** Worth recording as a negative result: both are
  inferred from plain CSV and both came through the audit correct for `=`, `>`,
  `BETWEEN`, `IN`, `ORDER BY`, `GROUP BY` and `MIN`/`MAX`, including string-literal
  coercion. They had zero fuzz coverage until [04](04-widen-fuzz-corpus-to-every-type.md),
  which made both join keys, sort keys and group keys in every corpus.

## Shared context

- The fuzz campaign mechanics are in [`docs/testing.md`](../../docs/testing.md)
  §Property-based testing. Two facts that are cheap to re-lose: **`--test_env=`
  is load-bearing** (a bare `FUZZ_SEEDS=N bazel test` sets the variable in the
  bazel client only and the run silently uses the default budget), and campaign
  volume does not fit four JVMs on a 16GB box — `bazel shutdown` first, then three
  JVMs at `-Xmx1200m` over disjoint seed blocks.
- The convention from [`plans/bugfixes/README.md`](../bugfixes/README.md) applies
  to everything here: **removing a gate is the proof the fix landed.** If a
  finding has to be parked, park it with a plan file and a named gate, never a
  silent filter in a generator.
- The house rule for coverage guards, stated at length in `docs/testing.md`:
  covering the **shape** is not covering the **value**. Every property added by
  these plans needs a guard asserting both halves, with the observed rates in the
  failure message so a later reader can tell rare from gone.
