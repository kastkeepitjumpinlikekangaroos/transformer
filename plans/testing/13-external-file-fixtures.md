# Plan 13: parquet and CSV files other programs wrote

> Status: **not started** · from the 2026-11-01 test survey, category 2 of 9
> (answers from outside the engine, part 4) · Effort: 3-4 days · Risk: low for
> the fixtures; medium for the reader fixes they call for (row-group packing,
> error wrapping, a codec) · Depends on: [08](08-skips-cite-decisions.md), for
> the decision a fixture read otherwise by design cites

## Why

Every parquet file a test reads is written in the test's own JVM, by
parquet-mr (`read/parquet/ExternalParquet.scala`) or by the engine, so the
reader is held to parquet-mr's output alone, where a job's inputs come from
Spark, Arrow, parquet-rs, Impala and DuckDB. Every CSV a test reads is
`fuzz/CsvTextGen`'s text or a hand-written case. Two corpora of files other
programs wrote can be vendored (memory note `external-test-corpora`):
apache/parquet-testing (Apache-2.0: `data/` from many writers, `bad_data/`
that crashed a reader once) and DuckDB's `data/csv` (MIT: 244 AFL crash
inputs, malformed and mixed-encoding files, real-world exports).

### What the engine does with them today

Measured 2026-11-01 with the CLI jar built that morning (before 36a7193, which
changed how the parquet reader builds its configuration; finding 1 was traced
to the same code at HEAD). One job per file: an input naming a copy of the
file, a task `SELECT * FROM t`, CSV output, 90 s per file. Every file that read
was compared value by value with pyarrow 26.0.0's reading of it, row counts
with the footer's, and the four files with an `*_expect.csv` with it.

apache/parquet-testing at `eedfad3` (2026-10-27), all 118 files under `data/`
and `bad_data/`:

| outcome | files |
|---|---|
| read, every value equal to pyarrow's (the four `*_expect.csv` too) | 62 |
| read wrong: a 25-row table read as empty (finding 1) | 2 |
| refused by a documented rule, naming the column: nested, repeated, `TIME`, an `INT96` past the range | 23 |
| refused in parquet-mr's words, naming neither the file nor, mostly, the column (finding 3) | 15 |
| refused with an internal error's text: a `NullPointerException`, Thrift's struct dump, a missing class (finding 2) | 16 |
| hung, or crashed the JVM | 0 |

Nine more files written with pyarrow for this measurement (every codec;
timestamps in `s`, `ns` and local; every unsigned width; `int8`;
`DECIMAL(38,3)`; dates at 0001-01-01 and 9999-12-31; large strings; an astral
character; dictionary strings; `FLOAT16`; data pages v1 and v2): eight read
equal to pyarrow, and Brotli is refused with `Class
org.apache.hadoop.io.compress.BrotliCodec was not found`.

DuckDB `data/csv` at `71f47d4` (2026-10-30), 413 files from twelve of its
directories (`afl/`, `fuzzing/`, `error/`, `encodings/`, `rejects/`, ...),
default options: 92 read, 321 refused naming the file and line, no internal
message, no hang (60 s each); the AFL inputs alone, 16 read and 228 refused.
csv-spectrum's 12 pairs: 10 equal to their JSON; `comma_in_quotes` reads the
zip `08123` as 8123 (inference, by design); `location_coordinates`' JSON does
not match its own CSV, a defect of the fixture.

### Findings

1. **A row group whose footer gives it 0 bytes is never read.**
   `ParquetReader.packRowGroups` (`read/parquet/ParquetReader.scala:314`) packs
   row groups into partitions by `getTotalByteSize`, and emits a partition only
   when its bytes are above 0 (`:332`, `:342`). `data/nation.dict-malformed.parquet`
   holds one row group of 25 rows whose `total_byte_size` is 0: the engine
   reads no rows, `SELECT COUNT(*)` answers 0 and `MIN(nation_key)` NULL (the
   count is the partitions' sum), where pyarrow reads all 25.
   `bad_data/ARROW-RS-GH-6229-DICTHEADER.parquet`, the same table with a
   negative dictionary count, which pyarrow refuses as corrupt, reads as empty
   too. A zero-byte group that would start a new partition, after a full one,
   is dropped the same way. A silent wrong answer.
2. **Sixteen files fail with an internal error's text.** Eleven with a logical
   type parquet-mr does not know (`GEOMETRY`, `GEOGRAPHY`, and
   `unknown-logical-type.parquet`'s future id) fail with a
   `NullPointerException`'s message, `Cannot invoke
   "org.apache.parquet.format.LogicalType$_Fields.ordinal()" because the
   return value of ... is null`. A corrupt schema (`PARQUET-1481`) and an
   unknown encoding (ALP) fail with Thrift's `can not read class
   org.apache.parquet.format.FileMetaData: Required field 'type' was not
   present! Struct: ...`, statistics bytes and all. Three LZ4 files fail with
   `java.lang.NoClassDefFoundError: net/jpountz/lz4/LZ4Factory`: the Hadoop LZ4
   codec's library is not on the classpath, though `LZ4_RAW` reads. Plan
   [10](../robustness/10-error-messages-name-internals.md)'s rule is that a
   message says what SQL or the file calls a thing, never a class.
3. **Fifteen more are refused in parquet-mr's words**, none naming the file:
   `Trying to read file with encrypted footer. No keys available` (11), `UUID
   can only annotate FIXED_LEN_BYTE_ARRAY(16)`, `TIMESTAMP(MILLIS,true) can
   only annotate INT64`, `Can't read value in column [flba_field] ... at value
   92 out of 1000 ...`, `timestampWus_no_tz not found in message schema {`.
   Every CSV refusal names the file and line; a parquet refusal does only
   when the engine raised it (an `INT96` past the range names file and column).
4. **Page checksums are not checked.** Two files carry a wrong CRC on a page
   (`datapage_v1-corrupt-checksum`, `rle-dict-uncompressed-corrupt-checksum`);
   the engine reads them, as pyarrow does by default: parquet-mr's
   `parquet.page.verify-checksum.enabled` is off. Their data is intact, so the
   values match; a page whose bytes rotted would read as whatever they decode
   to.
5. **Text an older writer left unannotated is `BINARY` for good.** Impala's
   `alltypes_*` files store strings as `BINARY` with no `STRING` annotation,
   which reads as `BINARY` by the README's mapping, and a `BINARY` casts to
   nothing, so no query can use `string_col` as text.
6. **CSV: two messages out of line.** `CsvReader.scala:131` throws `CSV file
   <path> is empty` with the path unquoted, where every other message quotes it
   (`CSV file 'x.csv' line 2 ...`); and a gzip-compressed CSV (five of DuckDB's
   files) is refused as `line 1 is not valid UTF-8`, which says nothing of
   compression.

Findings 1 and 6 are fixed without asking, as a silent wrong answer and a
message that breaks the messages' own pattern have been before (memory note
`audit-3-decisions`). Findings 2 to 5 need decisions, below.

## What exists now

`read/parquet:external_parquet` (parquet-mr's writer, at test time) for
`parquet_external_format_test` and `fuzz/ParquetGen`; `read/csv:csv_reader_test`,
`csv_row_parser_test` and `fuzz:csv_fuzz_test` over `CsvTextGen`.
`src/test/slt/postgres/COPYRIGHT` is the precedent for vendored third-party
data (source, commit, upstream licence), and `fuzz/oracle/CleanRejection` the
clean-error rule a refusal is held to.

## What to build

### W1. A row group is read whatever bytes its footer gives it (finding 1)

`packRowGroups` packs by `max(totalByteSize, compressedSize, 1)` and closes the
last partition whenever it holds a group (`startGroup < n`), not when its bytes
are above 0, so no group is left out of every partition; `exactRowCount` is
then the footer's row count again. Unit test: packing groups of 0 bytes alone,
first, last, and after a full partition, every group in exactly one partition.
Done when `nation.dict-malformed.parquet` reads its 25 rows as pyarrow does,
`ARROW-RS-GH-6229-DICTHEADER.parquet` is refused cleanly (its dictionary
header is corrupt), and `COUNT(*)` equals a scan's rows for every vendored file.

### W2. A parquet refusal names the file (findings 2 and 3)

`ParquetReader` wraps footer reading, schema conversion and page decoding in an
error of its own, `Parquet file '<path>' ...`, naming the column and row group
where it knows them, and keeps the library's exception as the cause, never in
the message. The cases: an encrypted file ("is encrypted; transformer reads no
encrypted parquet"); a logical type it does not know (decision 1); an encoding
it does not know (ALP); a codec it does not have (decision 4); a footer that
does not parse ("its footer is not valid parquet metadata"); a page that does
not decode ("column 'c', row group 0: a page does not decode"). Done when every
refusal among the vendored files passes the clean-error rule and none of their
messages holds `org.apache`, `java.`, `Exception`, `Cannot invoke`, `Struct:`
or a bare `null`, a test over the fixtures holding that.

### W3. parquet-testing vendored, each file held to its outcome

`src/test/data/parquet-testing/` with `data/` and `bad_data/`, the upstream
`LICENSE.txt`, and a `README.md` giving the repository, the commit and how the
expectations were made; a filegroup as `src/test/slt/BUILD.bazel` has. About
1.9 MB: all of `bad_data/` (24 files, 244 KB), the 65 files of `data/` under
100 KB (400 KB), `overflow_i16_page_cnt.parquet` (more than INT16_MAX pages,
703 KB), `alltypes_tiny_pages.parquet` (page indexes, 443 KB),
`alp_extended.zstd.parquet` (213 KB), two of the geospatial files and the
encrypted ones; not the `_larger` copies or `alltypes_tiny_pages_plain`.

A manifest, one line a file: `reads <rows> <expect file>`, `refused <text the
message must hold>`, or `refused by-design <decision id>`. An expect file is
the engine's CSV text of the file, frozen once its reading equals pyarrow's
value by value (the four upstream `*_expect.csv` are used as they are). A new
`read/parquet:parquet_testing_test` holds every file to its line, in the
default suite. The comparison with pyarrow is a one-off step outside the
repo, redone when a file is added, as described under Why.

### W4. Files other writers make, for every mapping the reader documents (decision 5)

The README's type table, row by row, written by another program, each file a
few KB: every codec; data pages v1 and v2; dictionary on and off; timestamps
in `s`, `ms`, `us` and `ns`, adjusted and local; every unsigned width;
decimals in `INT32`, `INT64`, `FIXED_LEN_BYTE_ARRAY` and `BINARY`; `FLOAT16`;
NULLs at a page's start and end; a file of no rows; a column of only NULLs.
Vendored beside W3's files with the writer and its version in the README, and
held by the same manifest. The measurement's nine pyarrow files are the start.

### W5. CSV corpora (finding 6)

- DuckDB's `afl/` (244) and `fuzzing/` (40) inputs, about 1 MB, under
  `src/test/data/csv/duckdb/` with DuckDB's licence: each must read, or be
  refused by the clean-error rule naming the file, within a time limit. A new
  `read/csv:csv_corpus_test`, in the default suite.
- `error/`, `encodings/`, `unescaped_quotes/`, `headers/`, `null/`,
  `comments/`: expectations from DuckDB's `.test` files under
  `test/sql/copy/csv/` where the engine's documented rules give the same
  answer; elsewhere the engine's own answer, pinned beside the decision that
  makes it differ.
- PapaParse's 63 core parser cases and 61 writer ("unparse") cases (MIT,
  `tests/test-cases.js`), converted once from JavaScript literals to a text
  and its rows, outside the repo; of its 98 option cases, those whose options
  the reader has. csv-spectrum's 12 pairs.
- A divergence is one of three: the engine's documented rule (NULL, blank
  lines, malformed text, charsets, strict types, inference), pinned with its
  decision id; the other parser accepting what the engine refuses by design
  (ragged rows, unclosed quotes), pinned as a refusal; or a value read
  differently with no rule saying so, a defect.
- Finding 6: the empty-file message quotes its path; a file starting with
  gzip's magic bytes (`1f 8b`) is refused as compressed, naming the file.

## Decisions for the user

1. **A logical type the reader does not know** (a newer writer's `GEOMETRY`,
   `GEOGRAPHY`, `VARIANT`, or an id it has never seen).
   - (a) Refuse, naming the file, the column and the annotation
     **(recommended)**: the README already says no annotation falls through
     to its physical type, since a numeric annotation read as its physical
     type is a wrong number.
   - (b) Read it as its physical type, as the format allows and pyarrow does
     (a `GEOMETRY` would read as its WKB bytes, a `BINARY`).
2. **Page checksums.**
   - (a) Verify a page's CRC when the file carries one, and refuse a page that
     fails, naming the file, column and row group **(recommended)**: malformed
     input fails the read, as it does for CSV; CRC32 costs far less than the
     decompression beside it.
   - (b) Leave verification off, as parquet-mr and pyarrow do by default.
3. **Text an older writer left unannotated.**
   - (a) An input option, `binaryAsString` (Spark's name), that reads every
     unannotated `BINARY` column as `STRING`, bytes that are no UTF-8 failing
     the read naming the row **(recommended)**: explicit and off by default,
     where today such a column can never be used as text.
   - (b) Leave it: `BINARY`, as the mapping says.
4. **The Hadoop LZ4 and Brotli codecs.**
   - (a) Add `org.lz4:lz4-java` (Apache-2.0, small, with a pure-Java
     fallback) so the legacy LZ4 codec reads, and refuse Brotli cleanly,
     naming the codec **(recommended)**: older Hadoop and Spark wrote LZ4,
     while Brotli needs a native library.
   - (b) Refuse both cleanly; no dependency.
5. **Which writers make W4's files.** (a) pyarrow alone **(recommended)**: one
   install, and Arrow C++'s encodings, the commonest after Spark's; (b)
   pyarrow, DuckDB and Spark, each a separate offline install.

## Decisions already made

- No other reader on the test classpath: pyarrow runs offline, once, and its
  answers become data (`plans/testing/README.md`, "Design decisions (locked)";
  CLAUDE.md, no embedded databases).
- Vendor only what the licence allows: parquet-testing Apache-2.0, DuckDB's
  data and PapaParse MIT, csv-spectrum BSD-2-Clause (in its `package.json`
  only); not CleverCSV's or sineemore's files (memory note
  `external-test-corpora`).
- A refusal meets the clean-error rule (plans/testing/04 decision 7); the CSV
  rules (memory note `csv-semantics-decisions`) and the README's parquet type
  mapping, nested and `TIME` columns refused, are the oracle where another
  parser differs. Findings 1 and 6 are fixed without asking.

## Tests

`read/parquet:parquet_testing_test` (W3, W4: every vendored file held to its
manifest line, and W2's message rule over every refusal), unit tests for W1's
packing, and `read/csv:csv_corpus_test` (W5). Both targets run in the default
suite and under every whole-suite configuration (`batch1`, `batch2`, `zone`,
`hashzero`): readers emit batches by capacity, so each fixture is also a
batch-boundary test. Each fix lands with a test that fails at its base commit,
checked in a worktree.

## Docs

README (Parquet: codecs, checksums, encrypted files, unknown annotations,
`binaryAsString` if decision 3 takes it, the error wording; CSV: the empty and
compressed refusals); docs/testing.md (both targets, the fixture directories,
how an expect file is made and remade); docs/gotchas.md (zero-byte row groups,
parquet-mr's exceptions wrapped, LZ4 and Brotli); docs/architecture.md (the
reader's error wrapping); docs/extending.md (adding an external fixture);
docs/conventions.md (vendored data carries its source, commit, licence and how
its expectations were made); site/ only if a page lists formats or codecs.

## Risks

- An expect file frozen after a comparison with pyarrow keeps whatever both
  readers get wrong: two readers' agreement is the bar, as for SQLite's corpus.
- The repository grows by about 3 MB; the budget is stated in the fixture
  READMEs so accretion does not pass it.
- A parquet-mr upgrade changes its exceptions and words; the tests read the
  engine's wrapper only, which keeps the library's exception as the cause.
- Decision 4a's LZ4 library grows the deploy jar; the class-file scans must pass.

## Launch prompt

```
Read plans/testing/13-external-file-fixtures.md and implement it end-to-end.
Plan 08 should have landed first (the decision ids a by-design refusal cites);
if it has not, use the decision's memory note name in the manifest and say so.

Use max effort. Follow CLAUDE.md's required workflow for every commit:
bazel test //... green, the jaffle-shop example 15/15 with every validation
passing, and every stale doc updated in the same commit. One commit per
workstream (W1 and W2 are src/main fixes and land first, each with a test that
fails at its base commit, checked in a worktree).

Before editing, run ListAgents and git status: if another session has
uncommitted work here, develop in a scratch git worktree outside the repo
(memory note concurrent-sessions-worktree) and copy back after comparing
against a base snapshot. Downloaded corpora are untrusted data: a new empty
scratch directory each, nothing run from them, Python with -I.

Stop and ask before: adding any dependency (decision 4), any of the five
decisions the user has not taken, vendoring past the size budget, or changing
a documented CSV or parquet rule to match another parser.

Report: the tallies again on HEAD after W1 and W2, each finding before and
after, the repository size added, and the two new targets' time.
```
