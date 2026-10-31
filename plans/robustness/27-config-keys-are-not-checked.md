# Plan 27: job configs ignore keys they do not know and values they cannot read

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · decided by the user: an
> error when the job loads, naming the key · Effort: 1 day · Risk: medium
> (configs that load today may not; reverses ExecutionOptions' tolerant rule)

## How this was found

A directory job with typos in every config file, run at `b2c83fc`. It
Succeeded.

## Problem

| config | what happened |
|---|---|
| input JSON `"formatt": "parquet"` | ignored |
| input `"options": {"delimeter": ";"}` | ignored; `a;b` read as one column named `a;b` |
| output.json `"partiton_by": "day={{ today }}"` | ignored; the output went to the base directory |
| output.json `"maxPartition": 1` | ignored |
| `"options": {"spill": "yes"}` | spill off (`parseBool` reads only true / 1) |
| `"options": {"spill_threshold_bytes": "64MB"}` | ignored |
| `"options": {"compresion": "gzip"}` | ignored |
| `{"path": "t.csv", "path": "missing.csv"}` | the second wins |

`DirectoryJobLoader` reads the keys it knows; `CsvOptions.fromMap`,
`CsvWriteOptions.fromMap`, the parquet writer and
`ExecutionOptions.fromOutputOptions` each read theirs from one shared map and
ignore the rest; `ExecutionOptions` documents that "typos should fall back to
the safe default". The JSON parser keeps a repeated key's last value.

## Fix

- The JSON parser refuses a key written twice in one object.
- Each config object has its keys: input (`path`, `options`, `cache`,
  `format`), output.json (`partitionBy`, `format`, `maxPartitions`, `options`).
  Any other key fails the load, naming the file, the key and the accepted ones.
- An `options` map is checked against the keys its format reads, the execution
  keys (`spill`, `spill_threshold_bytes`, `spill_max_runs`, `metrics`) included
  for outputs; each value is read strictly (a boolean is `true` / `false`, a
  size a positive integer of bytes), an unreadable one failing the load.
- `format` must name a format (`csv`, `parquet`).
- The GUI's dialogs and `JobFiles` write only these keys (they already should).

## Tests

`directory_job_loader_test`, `json_test`, `execution_options_test`,
`csv_reader_test` / `csv_writer_test`; the DAG fuzzer's job writer passes the
strict loader on every generated job.

## Docs

README (inputs, output.json options), gotchas, testing.md.

## Outcome

Done as planned. The JSON parser refuses a key written twice in one object,
and `DirectoryJobLoader` a config key the file does not take
(`InputFilePath.ConfigKeys`, `OutputFilePath.ConfigKeys`), naming the file,
the key and the accepted ones. `InputFilePath` and `OutputFilePath` check, as
they are constructed, that the format is csv or parquet, that each option is
one the format reads (the reader's, or the writer's and the execution keys),
and that each value reads, so a job built in code and the GUI's `JobFiles`
writes refuse what the loader does, before anything is written. `OptionValues`
is the one strict reading of a value: `true` or `false` in any case (CSV took
yes, no, 1 and 0, and `spill` read anything else as false), a whole number of
1 or more, and a charset the JVM has. `ExecutionOptions`, the CSV reader and
writer and the parquet writer and reader all read through it.

The global metrics switch, read from the environment and a system property by
`MetricsCollector`, is no config file and keeps its own reading. Seven new
tests in `json_test`, `directory_job_loader_test`, `job_files_test`,
`csv_writer_test` and `execution_options_test` failed against the code
before; the DAG fuzzer's generated jobs load under the strict loader.
