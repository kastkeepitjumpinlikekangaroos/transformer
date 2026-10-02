# Robustness plans — what the fuzzers cannot reach

Eight plans from an audit on 2026-09-10, run from the opposite direction to the
fuzz harness. The five existing fuzzers generate inputs the engine is *expected*
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
| [02](02-total-order-for-decimal-and-binary.md) | One total order for `Decimal` and `Binary` | `Ops.eq`/`Ops.cmp`; the repo's own bug class, in the types the float work never reached | open | 2-3 days | medium | — |
| [03](03-parquet-external-format-fidelity.md) | Parquet timestamp units and `DECIMAL` | silent 1000x timestamp error; decimals read as unscaled ints or raw bytes | open | 1-2 days | low-med | — |
| [04](04-widen-fuzz-corpus-to-every-type.md) | Widen the fuzz corpus to all ten types | the guard that keeps 02 and 03 fixed | open | 2-3 days | low | 02 (else red) |
| [05](05-frontend-robustness-fuzzer.md) | Frontend robustness fuzzer | "nothing silently dropped" + "errors are user-facing" as properties | open | 3-5 days | low | 01 (shares its clause table) |
| [06](06-io-roundtrip-and-external-fixtures.md) | I/O round-trip properties + external parquet fixtures | the helper 03 needs, plus CSV state-machine fuzzing | open | 3-4 days | low | — (03 wants its fixture helper) |
| [07](07-hydration-oracle-on-the-dag-corpus.md) | Hydration oracle on the existing DAG corpus | the read side of the records the DAG fuzzer already writes | open | 2 days | low | — |
| [08](08-unconsumed-input-failure-is-swallowed.md) | An unconsumed input's load failure is swallowed | carried over, needs a product decision first | open | half a day | low | — |

**Done:** plan 01. Eighteen was an undercount: classifying every getter of every
AST class the binder reaches turned up 93 rejections. Every clause the binder
does not read now fails at bind time naming itself, and the eight cheap ones the
plan listed are honoured instead (OFFSET, NULLS FIRST / LAST, DISTINCT inside
SUM / AVG / MIN / MAX, COUNT(ALL x), ILIKE, LIKE ... ESCAPE, a SELECT with no
FROM, FETCH FIRST and TOP). `sql/plan:unsupported_clauses_test`
derives what the binder reads from its bytecode and keeps the audit honest across
JSqlParser upgrades. Two more silent drops turned up along the way, fixed in their
own commits: an aggregate named only in ORDER BY, and a HAVING with no GROUP BY.

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

## Known outstanding, deliberately NOT planned here

The audit also re-confirmed the inventory of things that are **already known and
documented** as limitations. None of these is a silent wrong answer, so none
became a plan; they are listed so the set above is not mistaken for the whole
picture. All are in [`docs/gotchas.md`](../../docs/gotchas.md) §"What's
intentionally NOT done" unless noted.

- **`RANGE` window frames execute with `ROWS` semantics.** Confirmed wrong with
  ties: `RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` over a tied `ORDER BY`
  gives `10, 30` where RANGE requires `30, 30`. Documented in README and gotchas.
  The only documented *wrong answer* in the engine — promote it to a plan if a
  workload needs RANGE.
- **Numeric overflow wraps silently.** `SUM` over two `Long.MaxValue` rows returns
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
- **`CAST(x AS TIMESTAMP)` is not implemented** (`CAST … AS DATE` is) and
  **decimal arithmetic throws**. Both surface as exceptions rather than wrong
  answers; the decimal one throws an `IllegalStateException`, which is the wrong
  class for a missing feature — fold that into
  [02](02-total-order-for-decimal-and-binary.md)'s docs pass.
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
  coercion. They still have zero fuzz coverage, which [04](04-widen-fuzz-corpus-to-every-type.md)
  fixes.

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
