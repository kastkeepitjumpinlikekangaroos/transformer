# Plan 06: the file-format layer has no property coverage and no external oracle

> Status: **not started** · Effort: 3-4 days · Risk: low (test-only)

## How this was found

Inventorying which modules the fuzz harness reaches. All five existing fuzzers
enter through `SqlEngine` or `DataJob`, over in-memory `MaterializedView`s — so
`read/csv`, `write/csv`, `read/parquet` and `write/parquet` are covered only by
hand-written example-based tests, even though they are the layer that touches
bytes the engine did not produce.

[Plan 03](03-parquet-external-format-fidelity.md) is what that hid: the parquet
reader ignores the timestamp unit and has no DECIMAL mapping, and
`parquet_roundtrip_test` cannot see either because it writes its fixtures with
`ParquetWriter`. Writer and reader share one assumption, so they agree with each
other while both disagree with the format.

## Problem

**A self-consistent round-trip is not an oracle for an external format.** That is
the reusable lesson and it applies to both halves of the I/O layer:

- `read/csv` — `CsvRowParser` is a 134-line hand-written state machine and
  `CsvSchemaInferer` is a 95-line type-widening ladder (`Int -> Long -> Double ->
  Boolean -> Date -> Timestamp -> String`). Both are covered by example tests
  only. Nothing generates adversarial CSV text.
- `write/csv` — `CsvWriter.formatField` decides quoting; `cellToString` formats
  all ten types (Base64 for Binary, `toPlainString` for Decimal). Nothing asserts
  the pair compose: that `read(write(rows))` returns the rows for *generated* data
  rather than for the handful in `csv_writer_test`.
- `read/parquet` — 643 lines including row-group splitting, partition boundaries
  that do not start at row group 0, predicate pushdown and INT96 decoding. The
  fixtures are all self-written.
- The **inference round-trip is not idempotent and nobody has measured where.**
  Read a CSV, write it back, read it again: a column of `007` infers as Int and
  writes `7`; `1.50` infers Double and writes `1.5`; `TRUE` infers Boolean and
  writes `true`; an integer beyond `Long` range widens to Double and loses digits.
  Some of these are the right call for an inferring reader (Spark does the same),
  but which ones are deliberate is undocumented, and the only way to find out
  today is to read the ladder.

## Proposed fix

Three properties plus one fixture helper. All test-only, all using the existing
`Rng` / `Props.forAll` / `Shrinker` harness.

**1. `CsvTextGen` + `oracle/CsvRoundTrip`.** Generate a `Dataset` (reuse
`DataGen`, widened by [plan 04](04-widen-fuzz-corpus-to-every-type.md)) plus a
writer configuration, write it, read it back with an **explicit schema** (so
inference is out of the picture), and assert the rows survive. Then the harder
direction: generate CSV *text* — embedded delimiters, embedded quotes, `""`
escapes, CRLF and bare LF, a quoted field containing a newline, a trailing field
with no newline, a BOM, ragged rows, an empty final line, unicode including
astral characters, a field longer than any internal buffer — and assert the parser
either produces a row count and field count consistent with the text or fails with
a clear error. The state machine is the target; a crash, a hang, or a silent field
merge is the finding.

Two things the property must pin deliberately because they are judgement calls,
not bugs: an empty unquoted field versus NULL (indistinguishable in CSV — decide
and assert), and a quoted empty field versus an unquoted one.

**2. `oracle/InferenceRoundTrip`.** Write generated data, read it with
**inference**, write it again, read again, and assert the *second* and *third*
reads agree — i.e. inference reaches a fixed point after one pass. That is a
weaker claim than "the text survives" and it is the right one: it allows the
deliberate normalizations (`007 -> 7`) while catching the case where repeated
round-trips keep changing the data, and it tells you exactly which inference steps
are lossy. Whatever the property reveals gets written into the README's CSV
section as documented behaviour rather than quietly fixed.

**3. External parquet fixtures.** A test helper that writes parquet with
parquet-mr directly from a `MessageType` the helper constructs, so a test can
produce a file transformer's own writer cannot: each timestamp unit, each decimal
physical layout, `JSON`/`ENUM`/`UUID` annotations, unsigned integers, multiple row
groups with nulls straddling the boundaries, a single-row group, an empty file, a
file whose row groups have different sizes. This is the helper
[plan 03](03-parquet-external-format-fidelity.md) needs for its regressions, and
once it exists a property can drive it: generate a schema and values, write with
parquet-mr, read with `ParquetReader`, assert equality — a genuine differential
against an independent implementation, which is the only external oracle available
without adding a dependency (parquet-mr is already a dep of `write/parquet`).

Worth driving the **partition axis** from that property too: `ParquetReader`
splits row groups across partitions by target bytes, and
`parquet_roundtrip_test` covers the multi-row-group split with one fixture. A
generated version — random row-group sizes against random `targetBytesPerPartition`
— asserts that per-partition row counts sum to the total and that column
projection works for partitions not starting at row group 0. Those two invariants
are already the ones the hand test checks; generating them is cheap.

## Why this is worth doing even though it found nothing yet

[Plan 03](03-parquet-external-format-fidelity.md) was found by reading, and
reading does not scale to 643 lines of reader plus a 134-line state machine. The
two defects it found are both *interpretation* bugs — the code does exactly what
it says, and what it says is wrong about the format. That class is invisible to
every self-consistent test and is exactly what an independent-implementation
differential catches.

## Verification

- Each property green at a campaign budget, with coverage guards: the CSV text
  generator must actually reach each adversarial shape (assert the rates); the
  parquet property must reach multiple row groups, a straddling null, and each
  annotation.
- `bazel test //...` green; jaffle-shop 15/15 (it reads CSV and writes both
  formats, so it is a real smoke test for this layer).
- Findings become named regressions in `csv_row_parser_test` /
  `csv_reader_test` / `parquet_roundtrip_test`, and engine fixes get their own
  plan files.

## Docs to update

`docs/testing.md` (new targets, generators, oracles, the external-fixture helper),
`README.md` (the CSV inference section gains whatever `InferenceRoundTrip`
establishes as deliberate — leading zeros, numeric formatting, empty-vs-NULL),
`docs/gotchas.md` (the self-consistent-round-trip trap, stated once as a general
rule), `docs/extending.md` (the recipe for adding a file format gains: a new
format needs a round-trip property and, if the format is external, a fixture
written by something other than our own writer).

## Launch prompt

```
Read plans/robustness/06-io-roundtrip-and-external-fixtures.md and implement it
end-to-end.

Use max effort. Honor CLAUDE.md: bazel test //... green, jaffle_shop 15/15, docs
in the same commit.

Build the external parquet fixture helper FIRST — plans/robustness/03 needs it and
it is the piece that makes this plan more than a restatement of existing tests.
Confirm it can produce a MILLIS-timestamp file and a FIXED_LEN_BYTE_ARRAY decimal
file that ParquetWriter cannot.

Then the CSV text property, then inference round-trip last (it is the one most
likely to turn into a documentation task rather than a fix).

Every property needs a coverage guard asserting the adversarial shapes are
actually reached, with observed rates in the failure message. See docs/testing.md.

Stop and ask before: changing CSV inference behaviour. Anything
InferenceRoundTrip reveals gets documented first and discussed before it gets
"fixed" — the ladder's normalizations may well be deliberate.
```
