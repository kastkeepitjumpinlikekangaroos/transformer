# Plan 03: the parquet reader ignores timestamp units and cannot see DECIMAL

> Status: **not started** · Effort: 1-2 days · Risk: low-medium (it changes how
> existing files are read, which is the fix, but any cached/derived output from a
> MILLIS-timestamp source is wrong today and will change)

## How this was found

A manual audit on 2026-09-10 of the one surface where transformer has to agree
with something outside itself. Every other correctness gate in the repo is
*self*-consistent — `parquet_roundtrip_test` writes with `ParquetWriter` and reads
with `ParquetReader`, so it proves the pair agree with each other, never that
either agrees with the format. Read from the code; no external fixture in the repo
exercises the disagreement, which is the gap.

## Problem

### 1. The timestamp unit is never read (off by 1000x)

`ParquetSchema.primitiveToDataType` maps any `INT64` carrying a
`TimestampLogicalTypeAnnotation` to `DataType.TimestampType`:

```scala
case (PrimitiveTypeName.INT64, _: LogicalTypeAnnotation.TimestampLogicalTypeAnnotation) =>
  DataType.TimestampType
```

The annotation's `getUnit()` — `MILLIS`, `MICROS` or `NANOS` — is never consulted,
and `ParquetReader`'s INT64 branch writes the raw value straight into a vector
whose contract is **microseconds** (`TimestampVector`: "Timestamp stored as
microseconds since the Unix epoch"):

```scala
case DataType.TimestampType =>
  val v = vec.asInstanceOf[TimestampVector]
  ...
  else v.set(r, cr.getLong)          // no unit conversion
```

`ParquetWriter` emits `timestampType(true, TimeUnit.MICROS)`, so transformer
reading its own output is correct and the round-trip test passes. Any externally
written file is not:

| file says | factor | a 2024 timestamp reads as |
|---|---|---|
| `MICROS` | 1 | correct |
| `MILLIS` | 1000x too small | ~1970-01-01, a few hours in |
| `NANOS` | 1000x too large | far future |

MILLIS is what Hive writes and what Spark writes under
`spark.sql.parquet.outputTimestampType=TIMESTAMP_MILLIS`; NANOS is what recent
pandas/pyarrow can write. This is a silent wrong answer on a first-class input
path — `examples/polymarket` reads an external Kaggle parquet dataset, so
externally-produced parquet is not hypothetical here.

### 2. `DECIMAL` has no mapping at all

`primitiveToDataType` has no `DecimalLogicalTypeAnnotation` case. A parquet
decimal is stored as INT32, INT64, `FIXED_LEN_BYTE_ARRAY` or `BINARY` with that
annotation, so each falls through to the physical-type default:

| parquet | read as | what the user gets |
|---|---|---|
| `DECIMAL(9,2)` on INT32 | `IntType` | the **unscaled** integer: `123.45` -> `12345` |
| `DECIMAL(18,4)` on INT64 | `LongType` | same |
| `DECIMAL(38,10)` on `FIXED_LEN_BYTE_ARRAY` | `BinaryType` | raw two's-complement bytes |
| `DECIMAL(38,10)` on `BINARY` | `BinaryType` | same |

Silent data corruption on the single most common warehouse column type — prices,
money, quantities. Note the repo already assumes decimals arrive:
`ParquetFilterTranslatorTest` builds a `DecimalType(18,4)` `price` column, and
[`plans/perf/06`](../perf/06-expand-parquet-pushdown.md) is tracked as "partial
by design" precisely because `ParquetFilterTranslator` returns `None` for
decimal predicates. That note is about a type the reader cannot currently
produce.

`ParquetWriter` also **cannot write** `DecimalType`
(`ParquetSchema`: `case other => throw new UnsupportedOperationException("Writing
$other to Parquet not supported in v1")`), so a decimal column that enters the
engine programmatically fails at the writer.

### 3. Other annotations collapse quietly

Same mechanism, smaller blast radius, worth deciding deliberately rather than by
fall-through: `BINARY` with `JSON`/`BSON` reads as `BinaryType` (should be
`StringType`); `INT32` with `TIME` reads as `IntType`; unsigned `INT64`
(`IntLogicalTypeAnnotation(64, false)`) reads as signed `LongType`, so values
above `Long.MaxValue` come back negative; `FIXED_LEN_BYTE_ARRAY` with `UUID`
reads as `BinaryType`.

## Why the tests miss it

Three reasons, and the first is the generalizable one:

1. **The round-trip oracle is self-consistent.** Writer and reader share one
   assumption (`MICROS`), so they agree while both disagree with a file neither
   produced. A self-round-trip can never catch a wrong interpretation of an
   external format — it can only catch an *internal* inconsistency. This is the
   structural insight that makes [plan 06](06-io-roundtrip-and-external-fixtures.md)
   worth building.
2. **No fixture in the repo carries a non-MICROS timestamp or a decimal column.**
   `parquet_roundtrip_test`'s fixtures are all written by `ParquetWriter`;
   `parquet_filter_translator_test`'s 3-row-group fixture likewise.
3. **The fuzz corpus has no decimal or timestamp columns at all** — see
   [plan 02](02-total-order-for-decimal-and-binary.md) and
   [plan 04](04-widen-fuzz-corpus-to-every-type.md).

## Proposed fix

1. **Read the unit.** Match on `TimestampLogicalTypeAnnotation` and capture
   `getUnit()` alongside the type, then scale at the read site: `MILLIS` x1000,
   `MICROS` x1, `NANOS` /1000 (truncating — document the loss). The unit has to
   travel from schema inference to the column reader; the cleanest route is
   probably a per-column conversion factor resolved once per file rather than
   widening `DataType` (`TimestampType` is a case object and the engine treats
   micros as the single internal representation, which is the right call — keep
   it). Also honor `isAdjustedToUTC` or document that it is ignored.
2. **Map DECIMAL to `DecimalType(precision, scale)`** from the annotation, and
   decode each physical layout: INT32/INT64 via `BigDecimal.valueOf(unscaled, scale)`,
   `FIXED_LEN_BYTE_ARRAY`/`BINARY` via `new BigDecimal(new BigInteger(bytes), scale)`
   (the same encoding `MinMaxState.writeBoxed` already uses for decimals, so the
   codec to copy is in the repo).
3. **Teach `ParquetWriter` to write decimals** — INT32/INT64 for small precision,
   `FIXED_LEN_BYTE_ARRAY` beyond it — so a read-transform-write pipeline over a
   decimal source survives. Without this, fixing the reader turns a corrupted
   read into a failing write, which is better but still broken.
4. **Decide the remaining annotations explicitly.** `JSON`/`BSON`/`ENUM` ->
   `StringType`; `UUID` -> `BinaryType` (fine, but by decision); unsigned
   integers -> reject with a clear message or widen, never silently reinterpret
   the sign; `TIME` -> reject. A fall-through `case _ => throw` already exists —
   the fix is to stop letting broad physical-type cases shadow it.
5. Once decimals can be read, revisit
   [`plans/perf/06`](../perf/06-expand-parquet-pushdown.md)'s decimal `None`
   branches — they become reachable for the first time, so the pushdown gap is
   now a real gap rather than a theoretical one. Out of scope here; note it there.

## Verification

This is the part that has to be different from what the repo does today:
**build the fixtures with parquet-mr directly, not with `ParquetWriter`.** The
test dependency is already available (`ParquetWriter` uses it), so a test helper
can emit a `MessageType` with each annotation and unit and write known values.

- A timestamp fixture per unit (`MILLIS`, `MICROS`, `NANOS`), asserting the same
  wall-clock instant comes back from all three.
- A decimal fixture per physical layout (INT32, INT64, `FIXED_LEN_BYTE_ARRAY`,
  `BINARY`), asserting exact `BigDecimal` equality including scale, and a negative
  value in each (two's-complement sign extension is the easy thing to get wrong).
- An INT96 case — already handled, and worth pinning next to the others since it
  is the other legacy timestamp encoding.
- A decimal write-read round-trip once the writer supports it, plus one
  external-write -> transformer-read -> transformer-write -> external-read cycle
  for a decimal column, which is the only shape that proves both halves.
- An unsigned INT64 fixture asserting the chosen behaviour (reject or widen), not
  a silent negative.

## Docs to update

`README.md` (the parquet section's supported-types list: decimal in, timestamp
units honored), `docs/gotchas.md` (the self-consistent-round-trip trap is the
reusable lesson; remove any claim that decimal columns cannot occur),
`docs/architecture.md` (the reader's type-mapping table), `docs/testing.md`
(the external-fixture helper and what it covers), and a pointer from
`plans/perf/06`.

## Launch prompt

```
Read plans/robustness/03-parquet-external-format-fidelity.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15, docs
in the same commit.

Write the external fixtures FIRST, with parquet-mr directly rather than with
ParquetWriter, and confirm the MILLIS timestamp case and each DECIMAL layout fail
against HEAD before changing the reader. A fixture written by our own writer
cannot catch this class of bug, which is the whole point of the plan.

Do the timestamp unit and the DECIMAL mapping as separate commits.

Stop and ask before: widening DataType.TimestampType to carry a unit (the engine
treats micros as its single internal representation and that should stay true),
or picking a rounding rule for NANOS -> micros other than truncation.
```
