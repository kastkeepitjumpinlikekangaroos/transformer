# Plan 06: Expand parquet predicate pushdown

> Status: not started · Tier: 3 · Effort: 2-3 days · Risk: low

## Goal

Push more predicate shapes into parquet's `FilterApi` so row-group skipping
fires on more queries. Specifically: `IS NULL` / `IS NOT NULL`, `IN (lit, ...)`,
and `BETWEEN` (decomposable to `c >= lo AND c <= hi`).

Each translation lets parquet skip a row group whose column statistics
prove the predicate can't match — entire 128MB-row chunks decoded to
zero rows.

## Why it matters

The Polymarket orderbook input is ~131M rows in a single 1GB file with
139 row groups. Many queries filter on a column with known stats (eg.
`asset_id = '0x...'` → ~1% selectivity → 138 row groups skip). The
current translator handles `=`, `<`, `<=`, `>`, `>=`, `!=` for numeric
and string columns, but skips:

- `IS NULL` (null-counts in stats — parquet-mr's `FilterApi.eq(col, null)`).
- `IS NOT NULL` (`FilterApi.notEq(col, null)`).
- `IN (lit, lit, ...)` — fall back to `c = lit1 OR c = lit2 OR ...`.
- `BETWEEN` — JSqlParser produces it as `c >= lo AND c <= hi` already,
  so this may "just work" already; verify.
- Decimal columns (currently fall through to `None`).

`docs/gotchas.md:111-118` documents the current limitation. The
translator comments at `ParquetFilterTranslator.scala:27-36` explicitly
list IS NULL / IN as "not pushed".

## Current state

`src/main/scala/com/transformer/read/parquet/ParquetFilterTranslator.scala`:

- Handles `=`, `!=`, `<`, `<=`, `>`, `>=` for Int, Long, Float, Double,
  Boolean, String, Binary, Date, Timestamp.
- Skips decimal (line 165): `case _: DataType.DecimalType => None`.
- Skips NULL literals at line 87: `if (value == null) return None`.
- `translateOne` has no case for `IsNullExpr` or `InListExpr`.

`PhysicalPlanner.plan` only calls translation for the shape
`LogicalFilter(LogicalScan(_, view, _), pred)` (`PhysicalPlanner.scala:16-21`).
That's fine for now.

## Proposed design

### 1. Translate `IsNullExpr`

```scala
case IsNullExpr(ColRefExpr(_, name, dt), negate) =>
  nullPredicate(name, dt, negate)
```

```scala
private def nullPredicate(name: String, dt: DataType, negate: Boolean): Option[FilterPredicate] = {
  dt match {
    case DataType.IntType | DataType.DateType =>
      val col = FilterApi.intColumn(name)
      if (negate) Some(FilterApi.notEq(col, null.asInstanceOf[java.lang.Integer]))
      else Some(FilterApi.eq(col, null.asInstanceOf[java.lang.Integer]))
    case DataType.LongType | DataType.TimestampType =>
      val col = FilterApi.longColumn(name)
      if (negate) Some(FilterApi.notEq(col, null.asInstanceOf[java.lang.Long]))
      else Some(FilterApi.eq(col, null.asInstanceOf[java.lang.Long]))
    case DataType.FloatType => /* ... */
    case DataType.DoubleType => /* ... */
    case DataType.StringType | DataType.BinaryType => /* binaryColumn */
    case DataType.BooleanType => /* booleanColumn */
    case _ => None
  }
}
```

The translator's docstring at lines 28-30 currently rationalizes the
omission as "column null-counts rarely flip whole groups in real
datasets." That's true for production data but very wrong for sparse
columns (eg. polymarket's `data` JSON-blob column is empty in many
rows). Cost is one extra match arm per type.

The "erasure gymnastics" the docstring mentions: `FilterApi.eq` takes
`Operators.SupportsEqNotEq<T>` and the literal type T must match. Pass
a typed `null` (`null.asInstanceOf[java.lang.Long]`) — parquet-mr
handles that as "match nulls".

### 2. Translate `InListExpr` over literals

```scala
case InListExpr(ColRefExpr(_, name, dt), items, negate) if items.forall(_.isInstanceOf[LitExpr]) =>
  val lits = items.map(_.asInstanceOf[LitExpr].value)
  // Decompose to OR of equalities (or NOT(OR) for NOT IN).
  val eqs = lits.flatMap(v => compareToLiteral(name, dt, "=", v))
  if (eqs.length != lits.length) None  // bail if any item didn't translate
  else if (negate) Some(FilterApi.not(eqs.reduceLeft(FilterApi.or)))
  else Some(eqs.reduceLeft(FilterApi.or))
```

Note the `if (eqs.length != lits.length) None` guard: if any literal in
the IN list is NULL (which `compareToLiteral` returns None for), bail
on the whole IN — partial translation would over-prune.

### 3. Decimal columns

Decimal columns in parquet are stored as `INT32`, `INT64`, or
`FIXED_LEN_BYTE_ARRAY` depending on precision/scale. The literal in
the predicate is a `java.math.BigDecimal`. Translate to the matching
parquet column type by reading the parquet schema (the view exposes
`schema`), inferring the storage representation, and emitting the
matching `intColumn` / `longColumn` / `binaryColumn` filter.

Lower priority than IS NULL / IN. May not be worth the complexity if
your workload has no decimal columns. Polymarket appears to be all
floats; jaffle_shop uses some decimals (totals/totals_amount). Profile
first.

### 4. (Defer) `LIKE 'prefix%'`

Parquet doesn't expose a public LIKE filter, but `LIKE 'abc%'` can be
expressed as `c >= 'abc' AND c < 'abd'` (next-string trick). Some
engines (DuckDB) do this. Out of scope for v1 of this plan.

### 5. (Verify) `BETWEEN`

JSqlParser turns `c BETWEEN x AND y` into `c >= x AND c <= y` at the
AST level. `LogicalBuilder` should be lowering it to a `BinOpExpr("AND", ...)`
of two `BinOpExpr` comparisons. Both sides already translate. Confirm
with an explicit test; if it doesn't fire, add a normalization step.

## Files to touch

- **Modified**: `src/main/scala/com/transformer/read/parquet/ParquetFilterTranslator.scala`
  — new translation cases.
- **New tests**: `src/test/scala/com/transformer/read/parquet/ParquetFilterTranslatorTest.scala`
  if it doesn't exist; otherwise extend.
- **Maybe modified**: `src/main/scala/com/transformer/read/parquet/ParquetReader.scala`
  to ensure `withPushdownFilter` covers the new branches (it just calls
  the translator — no change needed unless we add LIKE).

## Edge cases

1. **NULL literal in IN list** (`x IN (1, NULL, 3)`). SQL says NULL in
   the list means a non-matching key returns NULL (not false). We must
   not push this as `OR x = NULL` (always false). Bail on the whole IN
   when any literal is NULL.
2. **Empty IN list** — JSqlParser may not emit this; SQL grammar
   requires non-empty. Defensive: return None.
3. **IS NULL on non-existent column** — fail at binding, before the
   translator sees it. No new case.
4. **Mixed-type IN list** (`x IN (1, '2')`) — should fail at binding
   (type unification). Translator sees `LitExpr`s of one type.
5. **`NOT IN`** with NULLs in the list — SQL says it returns NULL for
   every row. Bail on translation; let row-eval handle it.
6. **Float NaN** — `FilterApi.eq(col, Float.NaN)` likely doesn't work
   reliably; parquet stats may not handle NaN per IEEE rules. Existing
   code doesn't special-case this; don't introduce regressions.
7. **`IS NOT NULL` on a column with no nulls** — parquet's stats expose
   `numNulls = 0`; the row group is kept. Fine.
8. **Stats unreliability**. Parquet stats can be missing (some writers
   skip them) or wrong (older writers had bugs with min/max for strings
   with non-UTF8 bytes). `FilterExec` always runs on top — incorrect
   stats can only over-prune, which would be a correctness bug. Trust
   parquet-mr's handling; our translator just builds the predicate.

## Testing

### Correctness
- Build a fixture parquet file with a column that has nulls scattered
  across row groups. Verify:
  - `WHERE c IS NULL` returns only the null rows.
  - `WHERE c IS NOT NULL` returns the non-null rows.
  - `WHERE c IN (a, b, c)` matches set membership.
  - `WHERE c NOT IN (a, b)` excludes those values.
- For each, confirm both: (a) result correctness; (b) parquet did
  skip row groups (via parquet-mr's read counter, if exposed).

### End-to-end
- jaffle_shop: 15/15. Adds nothing new but must not regress.
- polymarket: 15/1/1. The `stg_orderbook` task filters orderbook on a
  date range and asset_id — verify timing improves.

### Performance
- A polymarket query that adds `WHERE c IS NOT NULL` over a sparse
  column. Expect row-group-skip count to increase; wall time to drop.

## Risks

1. **Parquet-mr null-handling subtleties.** Verify `FilterApi.eq(col, null)`
   actually filters as "value is null", not "no row matches". The
   parquet-mr Javadoc covers this; test before relying.
2. **Decimal pushdown is fiddly** (3 storage types depending on precision).
   Skip in v1 unless profiles demand it.
3. **Over-pruning bugs are silent.** If the translation is wrong, you
   skip row groups that contain matching rows — query returns wrong
   results. Mitigation: keep `FilterExec` above scan (already done at
   `PhysicalPlanner.scala:21`), and add a test that runs each new
   shape against a brute-force scan and compares.

## Suggested phases

1. **Phase 1**: `IS NULL` / `IS NOT NULL`. Smallest change, highest hit
   rate on sparse columns.
2. **Phase 2**: `IN` over literals. Decompose to OR of `=` translations.
3. **Phase 3**: confirm `BETWEEN` works; add an explicit test.
4. **Phase 4 (optional)**: decimal pushdown if profiling shows need.

## Docs to update

- `docs/gotchas.md` — update the "predicate pushdown into parquet is
  best-effort partial" passage to reflect the new supported shapes.
- `docs/architecture.md` §3d (Parquet predicate pushdown) — update
  supported list.

## Launch prompt

```
Read plans/perf/06-expand-parquet-pushdown.md and implement it end-to-end.

Use max effort. Honor CLAUDE.md: no new heavy deps, bazel test //... must
pass, jaffle_shop deploy jar must hit 15/15 Succeeded, polymarket deploy
jar must hit 15/1/1. Land docs in the same PR.

Follow the 4 phases. Land Phase 1 (IS NULL/IS NOT NULL) and Phase 2 (IN)
together; they share a small amount of code. Phase 3 (BETWEEN) is
verification — add a test and update docs if it already works.

Critical correctness gate: for every new translation shape, add a test
that runs the same query both with and without pushdown (use a feature
flag or compare against in-memory results) and asserts identical row
sets. Over-pruning is the silent failure mode.

Skip Phase 4 (decimal) unless polymarket profiling shows decimal-column
filters are a hotspot — they shouldn't be on this dataset.

Stop and ask before: (a) attempting LIKE pushdown, (b) modifying anything
outside ParquetFilterTranslator and its test, (c) removing the FilterExec
above the scan.

Include in PR description: before/after row-group skip count and wall
time on a polymarket task that exercises the new shape.
```
