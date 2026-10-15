# Plan 06: the file-format layer has no property coverage and no external oracle

> Status: **done** 2026-10-14 (see [Outcome](#outcome)) · Effort: 3-4 days ·
> Risk: low (test-only), though what it found changed the CSV reader, writer and
> inference, and two parquet pushdown rules

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

> **Landed with [plan 03](03-parquet-external-format-fidelity.md) on
> 2026-10-03:** the helper is `read/parquet:external_parquet`
> (`ExternalParquet.write` from parquet's own schema text, `read` back through
> parquet-mr's reader and stringifiers, `footerSchema`), testonly and usable
> from any test package. `parquet_external_format_test` uses it for every
> timestamp unit, every decimal layout, the unsigned, text, binary, FLOAT16,
> TIME, INTERVAL and REPEATED annotations, projection, and a glob's column
> matching. What remains of this section is the property that drives it
> (generated schemas, values and row-group sizes; multiple row groups with a
> straddling null; a single-row group; an empty file) and the partition-axis
> property below.

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

The external parquet fixture helper already exists (read/parquet:external_parquet,
landed with plans/robustness/03); build the generated parquet property on it.

Then the CSV text property, then inference round-trip last (it is the one most
likely to turn into a documentation task rather than a fix).

Every property needs a coverage guard asserting the adversarial shapes are
actually reached, with observed rates in the failure message. See docs/testing.md.

Stop and ask before: changing CSV inference behaviour. Anything
InferenceRoundTrip reveals gets documented first and discussed before it gets
"fixed" — the ladder's normalizations may well be deliberate.
```

## Outcome

Landed 2026-10-14, in the order the launch prompt asked for: the parquet
property, then the CSV text property, then inference. Each found defects, and
each defect was fixed ahead of the property that found it, with a named
regression that comments the old answer.

**The parquet property** (`fuzz/parquet_fuzz_test`, campaign
`parquet_fuzz_campaign`). `ExternalParquet` gained a `Layout` (dictionary or
plain, a dictionary small enough to overflow into plain pages, page size and
row limit, data pages v1 or v2, byte-stream-split floats, every codec the
classpath carries, ZSTD and LZ4_RAW included) and `writeRowGroups`, which cuts
row groups exactly by writing each alone and joining them with parquet-mr's
`appendFile`. `ParquetGen` draws a glob of one to three files whose columns
each have an engine type stored in one of 33 layouts, values drawn as stored
bits from each type's edges, and a way to read them (batch size, partition
target, projection, pushdown). `oracle/ParquetDifferential` first asks
parquet-mr to read every file as the generator says, by its own stringifier,
then holds our reader to the generator's spec reading: schema, every row in
order, `exactRowCount`, partitions as whole runs of row groups (the partition
axis this plan asked for), a projection past the first group, and pushdown
soundness, which the plan did not ask for and which found both defects:

- **A string range pushed against statistics in UTF-8 order** (seed 1203): the
  file orders strings by code point and the engine by UTF-16 code unit, so
  `s < '\uFFFD'` skipped the group holding an emoji the engine orders below
  it. A range now pushes only against a literal below U+D800, where both
  orders agree; equality and IN on any literal.
- **A negative bound on an unsigned 8- or 16-bit column** (seeds 9494 and
  18048, in a campaign): those read as `INT` but their file orders them
  unsigned, so `u8 > '-1'` skipped every group. They push no comparison now
  (`Decoding.pushesComparisons`, renamed from `storesEngineValue`, which no
  longer said what it decides).

NaN and signed zeros were probed too: parquet-mr's statistics handle both the
way the engine's total order needs.

**The CSV properties** (`fuzz/csv_fuzz_test`, campaign `csv_fuzz_campaign`).
`CsvTextGen` draws an option bag of the kind a job passes (any delimiter,
quote and escape, past ASCII too, a null string that is empty or a word, every
line ending, UTF-8, UTF-16 and ISO-8859-1) and three kinds of case, for three
oracles: `CsvRoundTrip.checkData` (data written by `CsvWriter`, read back the
way a job re-reads a task's output), `CsvRoundTrip.checkText` (text written
here from fields it records, through the parser and the reader, malformed
text included) and `InferenceRoundTrip`. A probe of the CSV layer before any
of them ran found most of what they then confirmed, and the user decided the
semantics (recorded in the README's CSV section):

1. NULL is the null string written unquoted (the PostgreSQL rule); a quoted
   field is a value, and the writer quotes a value equal to the null string.
   An empty string used to read back as NULL.
2. A blank line is a row in a one-column file. A NULL there writes as a blank
   line, which the reader skipped: three rows written, two read back, in a
   job's re-read too.
3. Malformed text fails, naming the file and line: an unclosed quote (which
   took the rest of the file into one field), a row with extra or missing
   fields (dropped or padded before).
4. Charsets fail loudly: the writer printed `?` for a character its charset
   lacks, the reader U+FFFD for bytes that are no text.
5. Inference joins types properly: `INT`, `BIGINT`, `DOUBLE` widen, a `DATE`
   widens to a `TIMESTAMP`, any other mix is a `STRING`. The old ladder typed
   `1.5` and `true` as a `BOOLEAN` and read 1.5 as false.
6. Past the sample, values parse strictly and fail by name; a `BOOLEAN` reads
   only `true` and `false` (it read `yes` as false).
7. Numbers are plain decimals, not Java's grammar (`1d`, `0x1p3`, ` 1`).
8. An integer past `BIGINT` infers `DECIMAL(38,0)` (it was a `DOUBLE` that lost
   digits).

Fixed without asking, as plain bugs: a `BINARY` column could not be read back
from the Base64 the writer writes; the writer did not escape a separate escape
character; a delimiter past ASCII crashed the writer; a UTF-8 byte-order mark
began the first column's name; a job re-read its CSV output without the
`escape` option; a declared `DECIMAL` kept CSV text's own scale; a value that
did not parse threw a bare `NumberFormatException`; and, found by the data
property (seed 32), a CSV output of no partitions wrote no file, so a task over
an empty parquet input failed the moment a downstream task re-read it. The
text of each type now lives in `read/csv/CsvValues`, for the writer, the reader
and inference alike.

Decided along the way, and worth a look: a `BINARY` has an empty value like a
`STRING` (the empty byte string reads back as itself, not NULL); an empty
column name is quoted, so a header is never a blank line; an inferred
`DECIMAL(38,0)` column reads any plain decimal its scale holds exactly (`5.` as
5), as a declared one does; and the line named for bytes that are no text comes
from decoding the file again, because the parser's reader decodes thousands of
characters ahead of it.

**Where this plan was wrong, or silent.**

- *Inference's fixed point is too weak a claim on its own.* The old ladder read
  `1.5` as the `BOOLEAN` false and then reached a fixed point at once, so "the
  second and third reads agree" passes over it. `InferenceRoundTrip` also holds
  the first read to the agreed rules, restated with a grammar of its own: each
  type is the join over the sample, each value is what its text says, and a
  value past the sample that does not fit fails by name. The fixed point is
  then checked as the plan asked, and the property prints which written types
  come back as which, the table the README's CSV section documents.
- *The external fixture helper* existed (plan 03) and needed only exact row
  groups and layouts; the parquet property is the generated half the plan
  described, plus parquet-mr reading every file back, so the generator's own
  reading of the spec is checked against parquet-mr as well as our reader.
- *A generator's model of text is easy to get wrong.* The text property's first
  failures were all in its own model: a lone `\r` before an empty line ending in
  `\n` makes one `\r\n`, a byte-order mark has no Latin-1 bytes, a field with
  the delimiter `x` in it splits. Each oracle has a check that it reports what
  it is shown, and none was weakened to pass.

**Verification.** `bazel test //...` green (53 targets with the throwaway probe,
52 without it), jaffle-shop 15/15, and the scala and directory examples
succeed. Campaigns on the final code, run from snapshots of the launchers:
`parquet_fuzz_campaign` 30000 seeds green after both pushdown fixes (each
10000-seed block about fifteen minutes beside two others),
`csv_fuzz_campaign` 60000 seeds of each property green (about 2.7 minutes for a
20000-seed block of all three).

**Still open, left as follow-ups:**
- SQL's `CAST` still reads numbers with Java's grammar, so `CAST('1d' AS
  DOUBLE)` is 1.0 where a CSV field `1d` is no number.
- The parquet writer writes half a surrogate pair as `?` (`Binary.fromString`
  replaces it), where the CSV writer now fails.
- CSV inference reads `2024-01-01 12:00:00` (SQL's space form, which `CAST`
  takes) as a `STRING`.
- `inferenceSampleRows` and an explicit schema are programmatic only; a
  directory job's option bag cannot set either, so a value past the sample
  that does not fit can only be fixed in the data.

