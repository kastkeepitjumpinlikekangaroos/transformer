# transformer docs

Deeper reference for contributors. The top-level [README.md](../README.md) is
the user-facing intro; [CLAUDE.md](../CLAUDE.md) is the short navigation
guide for Claude sessions and links here for detail.

| File | What's in it |
|---|---|
| [architecture.md](architecture.md) | Mental model, module map, and the cross-cutting patterns every contributor should internalise (hooks, `ColumnarBatch` + `RowBuf`, parallel execution, output-as-directory, input caching, `_SUCCESS` markers, expression evaluation, window functions, DAG scheduling). |
| [conventions.md](conventions.md) | Scala / Bazel / docs conventions enforced across the repo (sealed traits in one file, val initialisation order, strict-deps, no emojis, etc.). |
| [extending.md](extending.md) | Step-by-step recipes for adding scalar functions, aggregates, window functions, file formats, directory-loader config fields, GUI panels, SQL operators, and cloud support. |
| [testing.md](testing.md) | Build / test commands, the full test inventory, and the testing workflow expected on every change (unit tests + jaffle end-to-end). |
| [gotchas.md](gotchas.md) | Known JSqlParser / Bazel / JVM / Hadoop / JavaFX pitfalls, plus what's intentionally NOT done in v1 (no spill-to-disk, no subqueries, RANGE-as-ROWS, etc.). |
| [code-map.md](code-map.md) | File-size hot spots and pointers to external reference material (`INIT.md`, the `~/grid-game` reference project, JSqlParser jar). |

Whenever you change the project, check whether any of these files (or the
top-level [README.md](../README.md)) is now stale and update it in the same
commit — see CLAUDE.md's "Required workflow" section.
