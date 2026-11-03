# Plan 13: parquet and CSV files other programs wrote

> Status: **done** 2026-11-02 (7f53028 W1, e4d87d8 W2, fc32cdd decision 4,
> 48c3447 decision 2, 9d89d5f decision 3, 0767eca W3, 177b54f W4, 86e6bc7 W5;
> see [Outcome](#outcome); every open decision taken overnight as recommended,
> not yet confirmed by the user) · from the 2026-11-01 test survey, category 2 of 9
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

   **(a), taken overnight as recommended, not yet confirmed by the user.**
2. **Page checksums.**
   - (a) Verify a page's CRC when the file carries one, and refuse a page that
     fails, naming the file, column and row group **(recommended)**: malformed
     input fails the read, as it does for CSV; CRC32 costs far less than the
     decompression beside it.
   - (b) Leave verification off, as parquet-mr and pyarrow do by default.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
3. **Text an older writer left unannotated.**
   - (a) An input option, `binaryAsString` (Spark's name), that reads every
     unannotated `BINARY` column as `STRING`, bytes that are no UTF-8 failing
     the read naming the row **(recommended)**: explicit and off by default,
     where today such a column can never be used as text.
   - (b) Leave it: `BINARY`, as the mapping says.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
4. **The Hadoop LZ4 and Brotli codecs.**
   - (a) Add `org.lz4:lz4-java` (Apache-2.0, small, with a pure-Java
     fallback) so the legacy LZ4 codec reads, and refuse Brotli cleanly,
     naming the codec **(recommended)**: older Hadoop and Spark wrote LZ4,
     while Brotli needs a native library.
   - (b) Refuse both cleanly; no dependency.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
5. **Which writers make W4's files.** (a) pyarrow alone **(recommended)**: one
   install, and Arrow C++'s encodings, the commonest after Spark's; (b)
   pyarrow, DuckDB and Spark, each a separate offline install.

   **(a), taken overnight as recommended, not yet confirmed by the user.**
   The library is `at.yawk.lz4:lz4-java` 1.12.1, where `org.lz4:lz4-java`'s
   coordinates relocate past 1.8.0 (Claude's call: every release before
   1.12.1 has a published flaw in decompressing untrusted input).

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

## Outcome

Landed overnight on `testing-round-3`, 2026-11-02, a commit per workstream
and one per decision that changes the reader: W1 `7f53028`, W2 `e4d87d8`,
decision 4 `fc32cdd`, decision 2 `48c3447`, decision 3 `9d89d5f`, W3
`0767eca`, W4 `177b54f`, W5 `86e6bc7`. At each, `bazel test //...` passed (71
targets, then 72 with W3 and 73 with W5) and the jaffle-shop job gave 15/15
Succeeded with every validation passing; each fix's test failed at its base
commit, checked in a temporary worktree. Sources were fetched fresh into the
scratchpad as data: parquet-testing at `eedfad3`, DuckDB at `71f47d4`
(sparse), PapaParse at `1fcb83c`, csv-spectrum at `d30e80f`; pyarrow 26.0.0
and DuckDB 2.0.0 ran from a venv there, never on the test classpath.

**The tallies**, apache/parquet-testing's 118 files read by the engine and by
pyarrow 26 value by value:

| | 1440326 (base) | e4d87d8 (W1, W2) | HEAD |
|---|---|---|---|
| read, every value equal to pyarrow's | 62 | 62 | 62 |
| read wrong (as empty) | 2 | 0 | 0 |
| refused by a documented rule, naming the column | 23 (1 naming the file) | | |
| refused in parquet-mr's words | 15 | 0 | 0 |
| refused with an internal error's text | 16 | 0 | 0 |
| refused in the reader's words, naming the file | | 56 | 56 |

At HEAD the two Hadoop-LZ4 files read (decision 4) and the two whose page
CRC is wrong are refused (decision 2), so the counts stand where they were.

**Each finding**, before and after:

1. A row group whose footer gives it 0 bytes: read as no rows, `COUNT(*)` 0.
   Now every group lands in one partition (W1). `nation.dict-malformed.parquet`
   is not read as pyarrow reads it, though: parquet-mr fails on its row group
   (a chunk whose stated length leaves out its dictionary page header), so it
   is refused cleanly, and read alone its `name` column comes back wrong
   (finding P1 below). `ARROW-RS-GH-6229-DICTHEADER.parquet` is refused
   cleanly.
2. Internal text (16 files): none; each is named in the reader's words, a
   logical type newer than parquet-mr by name and column (decision 1).
3. parquet-mr's words (15 files): none.
4. Page checksums: verified when a page carries one (decision 2); the two
   corrupt files are refused, naming column and row group. polymarket, 14
   Succeeded, 2 ValidationFailed, 1 Skipped as expected: 87 s and 83 s
   before, 86 s and 83 s after (its inputs carry no CRC; its task outputs do).
5. Unannotated `BINARY`: an input's `binaryAsString` reads it as text
   (decision 3); Impala's `alltypes_*` `string_col` reads as a `STRING` under
   it.
6. CSV: the empty-file message quotes its path, and a gzipped file is refused
   as compressed, naming it.

**W3**, `src/test/data/parquet-testing/`: 107 of parquet-testing's files
(all of `bad_data/`, the 74 of `data/` under 100 KB, the three larger ones the
plan names, `aes256/`, two geospatial files, the four `*_expect.csv`).
`MANIFEST`: 59 read (their CSV text frozen, or upstream's expect CSV's values),
27 refused by five new ledger rules (`refused-parquet-nested`, `-type`,
`-annotation`, `-checksum`, `-timestamp-range`), 21 refused for what they are
(16 encrypted). **W4**, `pyarrow/`: 26 files pyarrow wrote for the type
table, every codec and layout (`make_files.py` beside them); 25 read equal to
pyarrow, Brotli refused by `refused-parquet-codec`. **W5**,
`src/test/data/csv/`: DuckDB's 244 AFL and 40 fuzzing inputs (27 read, 257
refused cleanly naming file and line, none hung) and 60 files of its semantic
directories, which DuckDB 2.0 reads under the engine's options as the engine
does but where a ledger rule parts them; 101 PapaParse parser cases and 24
writer cases whose options the engine has, and csv-spectrum's 12 pairs: 69
agree with the other parser, 24 differ by five new CSV rules, 38 are refused
by five more, 24 writer cases write PapaParse's text.

**Size and time**: the fixtures add 3.0 MB (parquet-testing's directory 2.67
MB: 2.10 MB of upstream parquet, 0.29 MB of its expect CSVs, 0.10 MB of
pyarrow's files, 0.14 MB of expect files; the CSV directory 0.35 MB), the
budget the plan set; the deploy jar grows 0.9 MB with lz4-java.
`parquet_testing_test` takes about 1.5-2 s (133 files) and `csv_corpus_test`
0.6 s (481 inputs), each green under `batch1`, `batch2`, `zone` and
`hashzero`. One block of `parquet_fuzz_campaign` (2,000 seeds from 100000)
ran with Hadoop's LZ4 among the generator's codecs (in 392 of its seeds), green in 3.5 min.

**Calls made overnight**, beyond the five decisions:

- The ledger's `pinned by` may name a fixture manifest, which pins a rule
  about files with a line `by-design <id>`; `ledger_test` reads the
  manifests. Sixteen rules joined: six `refused-parquet-...`, five
  `refused-csv-...`, and `csv-empty-null`, `csv-blank-line`,
  `csv-text-after-quote`, `csv-line-ends`, `csv-inference`, each a rule the
  README states. `refused-parquet-annotation` (a `UUID` on an `INT32`
  refused, where pyarrow drops the annotation) is Claude's, by the README's
  rule that no annotation falls through.
- A parquet refusal is `ParquetReader.Unreadable`, an
  `IllegalArgumentException`: `TIME`, `INTERVAL`, nested and repeated
  columns were an `UnsupportedOperationException`; a nested column no longer
  prints its group's schema text; a codec the classpath lacks fails as the
  view opens; a decode-time refusal names the file as the view was given it.
- Expect files are the engine's CSV text, gzipped past 8 KB; the four
  upstream `*_expect.csv` are compared by value, not header.
- DuckDB's answers for its semantic directories came from DuckDB 2.0 run
  under the engine's options rather than from each `.test` file, whose
  options mostly differ; PapaParse's escape defaults to its quote, as
  PapaParse's does, and a case without a header reads every column as text.

**Findings held for the user** (gotchas.md, "What the external file fixtures
held out"):

- **P1** (likely defect, silent): parquet-mr reads a column chunk whose stated
  length leaves out its dictionary page header alone wrong:
  `SELECT name FROM t` over `nation.dict-malformed.parquet` answers `ALGERIA`
  for rows 8-24. Recommended: check a chunk's page headers against its
  length, refusing one that overruns it.
- **P2**: `LZ4` written as bare blocks (early parquet-cpp) is refused as a page
  that does not decode; pyarrow falls back to bare blocks. Recommended: keep
  refusing.
- **P3**: a `STRING` column whose bytes are not UTF-8 reads with U+FFFD, where
  CSV and `binaryAsString` fail the read. Recommended: fail the read.
- **C1**: a CSV escape before a character other than the quote is dropped
  (`"c\d"` reads `cd`), where PapaParse keeps it. Recommended: keep it as
  text, as the README's rule reads.

**Left undone**: DuckDB's larger `error/` and `encodings/` files, its
`rejects/`, `auto/` and other directories (the budget); PapaParse's 46 parse
and 37 writer cases whose options the engine has not (comments, empty-line
skipping, typing, previews, functions); the rest of parquet-testing's
`geospatial/` and its `variant/` and `shredded_variant/` directories.
