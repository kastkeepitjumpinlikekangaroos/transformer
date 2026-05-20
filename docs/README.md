# transformer docs

Deeper reference for contributors. The top-level [README.md](../README.md) is
the user-facing intro; [CLAUDE.md](../CLAUDE.md) is the short navigation
guide for Claude sessions and links here for detail.

| File | What's in it |
|---|---|
| [architecture.md](architecture.md) | Mental model, module map, and the cross-cutting patterns every contributor should internalise (hooks, `ColumnarBatch` + `RowBuf`, parallel execution, output-as-directory, input caching, per-task `_run.json` + per-job `job.json` records, expression evaluation, window functions, DAG scheduling, per-operator instrumentation). |
| [conventions.md](conventions.md) | Scala / Bazel / docs conventions enforced across the repo (sealed traits in one file, val initialisation order, strict-deps, no emojis, counter discipline, etc.). |
| [extending.md](extending.md) | Step-by-step recipes for adding scalar functions, aggregates, window functions, file formats, directory-loader config fields, GUI panels, SQL operators, instrumentation counters, and cloud support. |
| [testing.md](testing.md) | Build / test commands, the full test inventory, and the testing workflow expected on every change (unit tests + jaffle end-to-end + perf-tagged regression guard). |
| [gotchas.md](gotchas.md) | Known JSqlParser / Bazel / JVM / Hadoop / JavaFX pitfalls, plus what's intentionally NOT done in v1 (subqueries, RANGE-as-ROWS, etc.). |
| [code-map.md](code-map.md) | File-size hot spots and pointers to external reference material (`INIT.md`, the `~/grid-game` reference project, JSqlParser jar). |
| [benchmarking.md](benchmarking.md) | How to enable per-operator instrumentation (`_perf.json` schema, per-task / sysprop / env-var toggles), run the JMH microbench harness under `benchmarks/micro/`, drive the macro-bench runner under `benchmarks/macro/`, and run the perf-tagged regression guard against the checked-in jaffle baselines. |

Whenever you change the project, check whether any of these files (or the
top-level [README.md](../README.md)) is now stale and update it in the same
commit — see CLAUDE.md's "Required workflow" section.
