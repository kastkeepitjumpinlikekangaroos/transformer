# CSV files and cases other programs wrote

Three sources, each under its own licence, and `MANIFEST`, which says what
the engine does with each input; `read/csv:csv_corpus_test` holds every input
to its line.

## What is here

- `duckdb/`: files of [DuckDB](https://github.com/duckdb/duckdb)'s `data/csv/`
  at commit `71f47d410f6acceabd4c0d1329ed0f7b2f315f93` (2026-10-30), as they
  are there, under the MIT licence (`duckdb/LICENSE`): all of `afl/` (244 AFL
  crash inputs) and `fuzzing/` (40); all of `unescaped_quotes/`, `headers/`
  and `null/`; and the small files of `comments/`, `error/` and `encodings/`
  (not `comments/big.csv`, `error.csv` and `midline_big.csv`, nor `error/`'s
  and `encodings/`' files past a few KB).
- `papaparse/`: cases of [PapaParse](https://github.com/mholt/PapaParse)'s
  `tests/test-cases.js` at commit `1fcb83c64acf61ae636b556b0eadeebaca0aea33`
  (2026-10-27), under the MIT licence (`papaparse/LICENSE`): of its 63 core
  parser cases 46, of its 98 parse cases 55 and of its 61 writer ("unparse")
  cases 24, those whose options the reader or the writer has
  (`left-out.json` gives each other case and why). Each parse case is
  `NNN.csv`, its text, and `NNN.json`, its options and PapaParse's rows and
  error codes; each writer case is `unparse/NNN.json`, its rows, options and
  PapaParse's text. `read_test_cases.py` read the JavaScript literals as data
  and `write_cases.py` wrote the cases, once, outside the build: PapaParse's
  `quoteChar` and `escapeChar` became the reader's `quote` and `escape`, its
  escape defaulting to its quote as PapaParse's does.
- `csv-spectrum/`: the 12 pairs of
  [csv-spectrum](https://github.com/maxogden/csv-spectrum) at commit
  `d30e80f8b99d2eecb3778f1d7b9ed1cb425502ec` (2024-07-04), `csvs/` and the
  JSON of each in `json/`, as they are there, under the BSD-2-Clause licence
  its `package.json` names (it ships no licence file).
- `expect/`: the engine's CSV text of each input it reads, or reads otherwise
  than the other parser.

Budget: 0.35 MB here, of the 3 MB plans/testing/13 allows its fixtures in
all, with `src/test/data/parquet-testing/`'s 2.7 MB. A file added past it
needs that plan's budget raised first.

## How each line of MANIFEST was made

`csv_corpus_test`'s report (`CSV_CORPUS_REPORT=1`, below) printed what the
engine does with every input, and each line was made from it:

- A DuckDB file is read with the default options. For `afl/` and `fuzzing/`
  the rule is to read or to be refused cleanly, naming the file and line,
  within 60 s; the line pins which, with the message's text. Every other
  DuckDB file was read with DuckDB 2.0.0 too, under the engine's options
  (`header=true, delim=',', quote='"', escape='"', strict_mode=true,
  allow_quoted_nulls=false`, every column text): DuckDB reads what the engine
  reads, value for value, but where the ledger's rules part them (a header
  name DuckDB makes up, a ragged row its sniffer skips, a gzipped file it
  decompresses, a quote left open that the engine reads as text after a
  closing quote and DuckDB, strict, refuses). Those refusals cite the rule.
- A case is read with its options (no header: every column a `STRING`, as
  many as PapaParse's first row has) and compared with the other parser's
  rows: `agrees` where they are the same, value for value; otherwise the
  ledger rule that makes the engine's reading its own (`differs by-design`,
  its text frozen in `expect/`, or `refused by-design`). A writer case writes
  the rows with the engine's writer under the case's options, `\r\n` ending
  each line as PapaParse's do.
- "held" marks an input read otherwise with no rule behind it: a finding.

To remake a line: run `bazel test //src/test/scala/com/transformer/read/csv:csv_corpus_test
--test_env=CSV_CORPUS_REPORT=1 --test_filter=reportWhatTheEngineDoes`, read
the input's JSON line in the test log (its rows, its CSV text, and whether
the other parser reads the same), compare it with the other parser or DuckDB
again, and only then write the line and its expect file.
