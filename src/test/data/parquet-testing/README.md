# Parquet files other programs wrote

Files of [apache/parquet-testing](https://github.com/apache/parquet-testing) at
commit `eedfad3f086678d282a00d8ff3cf8932d5d29af5` (2026-10-27), as they are
there, under the Apache License 2.0 (`LICENSE.txt`, upstream's). Written by
parquet-cpp, Arrow, parquet-mr, parquet-rs, Impala, Spark and others; `bad_data/`
holds files that once crashed a reader. `MANIFEST` says what the engine does
with each, and `read/parquet:parquet_testing_test` holds every file to its line.

## What is here

- `bad_data/`: all 24 files (`variants/` included).
- `data/`: every file of upstream's `data/` under 100 KB (74, the encrypted
  ones among them); `overflow_i16_page_cnt.parquet` (more pages in a chunk
  than an INT16 counts), `alltypes_tiny_pages.parquet` (page indexes) and
  `alp_extended.zstd.parquet` (the ALP encoding); `aes256/`, all five; two of
  `geospatial/` (a `GEOMETRY` and a `GEOGRAPHY` column); and the four
  `*_expect.csv`, upstream's own reading of the four files beside them.
- Left out: the `_larger` copies, `alltypes_tiny_pages_plain.parquet`, the
  rest of `geospatial/`, `variant/`, `shredded_variant/`, the bloom filter
  binaries and upstream's READMEs.
- `expect/`: the engine's CSV text of each file it reads (below).

Budget: 2.6 MB here (2.1 MB of parquet, 0.3 MB of upstream's expect CSVs,
0.1 MB of `expect/`), of the 3 MB plans/testing/13 allows its fixtures in all.
A file added past it needs that plan's budget raised first.

## How each line of MANIFEST was made

Each file was read twice, once by the engine (`transformer query --table
t=<file> --format jsonl "SELECT COUNT(*) ...; SELECT * FROM t"`) and once by
pyarrow 26.0.0, in a process of its own, outside the repo; each value was
compared by type (a `FLOAT` as a 32-bit float, a timestamp as its
microseconds, a `BINARY` as its bytes, an `INT96` read by pyarrow in
microseconds), and the engine's `COUNT(*)` with its rows.

- `reads`: the engine read every value equal to pyarrow's. The expect file is
  then the engine's CSV text of `SELECT * FROM t` (`--format csv`), frozen,
  gzipped when past 8 KB; the four upstream `*_expect.csv` are used as they
  are, their values compared and their header not (one names a column with a
  space before it, another without the colon the file has).
- `refused by-design <id>`: the engine refuses the file by a rule of the
  ledger in `docs/gotchas.md` ("Answers that differ from other engines by
  design"), whose refusal text the message must hold; pyarrow reads most of
  these (nested columns, `TIME`, `GEOMETRY`, a corrupt page CRC).
- `refused <text>`: the file is refused for what it is (encrypted, corrupt,
  an encoding no reader here decodes), with that text. "held" marks one
  pyarrow reads: a finding no decision covers yet.

A line is remade when the engine's reading of its file changes on purpose:
compare the engine's reading with pyarrow's again, as above, and only then
write the expect file anew. Two readers agreeing is the bar, so a value both
read wrong stays wrong here.
