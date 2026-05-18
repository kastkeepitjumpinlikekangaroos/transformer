# CLAUDE.md

Short navigation guide for future Claude sessions working on this repo. Read
this first, then follow the pointers below to the detail you need.

## What this project is

`transformer` is a single-node, Spark-inspired ETL library in Scala 2.13. The
project's *interesting* part is that the SQL engine is hand-built — parser
façade (JSqlParser), logical planner, physical planner, parallel executor —
no Spark, no Calcite, no DuckDB. The non-SQL parts (CSV, Parquet, templating,
job runner) are deliberately thin and use stdlib + a few targeted Maven deps.

The user explicitly chose "JSqlParser for AST only, build our own planner +
executor" over Calcite/DuckDB shortcuts. **Honor that direction.** If you
find yourself wanting to add `org.apache.calcite` or wrap something like
DuckDB, stop and ask — that conflicts with the project's purpose.

## Required workflow for every change

This is the most important section in this file. Every change you land in
this repo must clear all three steps below; a PR that skips any of them is
not done.

**1. Unit tests must pass.** Run `bazel test //...` and ensure every test
is green. Any change to a module should land with a test added or updated
under the corresponding `src/test/scala/...` target. New behaviour without a
test is not done. The full test inventory and which target covers what live
in [docs/testing.md](docs/testing.md).

**2. The jaffle-shop end-to-end example must pass.** It's the largest
realistic job in the repo (15-task DAG, ~150k rows, 26 DBT data_tests ported
as validations) and the fastest way to catch regressions unit tests miss —
planner edge cases, runner orchestration, directory-loader behaviour, GUI
hydration when the GUI changes. Run it after any non-trivial change to SQL,
the runner, or the directory loader:

```bash
bazel build //examples/jaffle_shop:jaffle_shop_deploy.jar
java -jar bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar
# Should exit 0, write to /tmp/transformer-jaffle-out/, and report
# 15/15 tasks Succeeded with all validations passing.
```

**3. Update every doc whose claims are now stale.** Land the doc updates in
the same commit as the code. After a change, walk this list and revise where
needed:

- [`README.md`](README.md) — user-visible features, limitations, SQL surface,
  file format support, templating variables.
- [`docs/architecture.md`](docs/architecture.md) — module map (new files),
  cross-cutting patterns (new operators, hooks, batching invariants).
- [`docs/conventions.md`](docs/conventions.md) — new patterns established by
  the change.
- [`docs/extending.md`](docs/extending.md) — new extension points or revised
  recipes.
- [`docs/gotchas.md`](docs/gotchas.md) — new pitfalls discovered, or features
  moved from "not done" to "done".
- [`docs/testing.md`](docs/testing.md) — new test targets and what they cover.
- [`docs/code-map.md`](docs/code-map.md) — when a file grows past or shrinks
  below a hot spot.

A change that adds a feature without updating user docs is not done.

## Where to look for what

- [README.md](README.md) — user-facing intro. Status, quick start, programmatic
  example, directory-loader layout, run markers, GUI overview, supported file
  formats, SQL features, temporal templating, limitations. Start here for
  anything user-visible.
- [docs/README.md](docs/README.md) — index of contributor docs (the files
  below).
- [docs/architecture.md](docs/architecture.md) — mental model, module map,
  and cross-cutting patterns (hooks, `ColumnarBatch` + `RowBuf`, parallel
  execution, output-as-directory, input caching, `_SUCCESS` markers,
  expression evaluation, window functions, DAG scheduling).
- [docs/conventions.md](docs/conventions.md) — Scala / Bazel / docs
  conventions (sealed traits, val ordering, strict-deps, no emojis, etc.).
- [docs/extending.md](docs/extending.md) — recipes for adding scalar
  functions, aggregates, window functions, file formats, directory-loader
  config fields, GUI panels, SQL operators, cloud support.
- [docs/gotchas.md](docs/gotchas.md) — known JSqlParser / Bazel / JVM /
  Hadoop / JavaFX pitfalls plus what's intentionally NOT done.
- [docs/testing.md](docs/testing.md) — build/test commands and the test
  inventory.
- [docs/code-map.md](docs/code-map.md) — file-size hot spots and pointers to
  external reference material.
- [INIT.md](INIT.md) — the original brief. Re-read when intended behaviour
  is ambiguous.

## Repo-wide rules

- **Scala 2.13.16 / JDK 21.** Pinned by Bazel; don't bump without asking.
- **No emojis** in code or docs unless explicitly requested.
- **No new heavy SQL dependencies.** No Calcite, no DuckDB, no embedded
  databases. JSqlParser is for AST only.
- **Prefer editing existing files** to creating new ones. Don't add a new
  doc/module if an existing one already covers the area.
- **No backwards-compatibility shims** unless asked. Change the code; remove
  the old path.

Full coding conventions are in [docs/conventions.md](docs/conventions.md).
