# Code map

Where the bulk of the code lives, and the external reference material that's
useful when a request is ambiguous. Keep this list trimmed to the actually-
biggest files (or the ones that disproportionately attract changes) — it's a
navigation hint, not a comprehensive directory listing.

## File-size hot spots

- `sql/plan/LogicalBuilder.scala` (~700 LOC) — biggest file. Pattern matches
  every JSqlParser expression node. If you're adding a syntax feature, this
  is probably where it lands.
- `sql/exec/WindowExec.scala` (~345 LOC) — partition, sort, frame computation
  for every supported window function.
- `job/DataJob.scala` (~600 LOC) — runner orchestration: input materialization
  pool, DAG scheduler, writeOutput, validation re-read, per-status
  `_run.json` writes + per-failure `_validation-<slug>.csv` sample writes +
  per-job `job.json` write + consistency checks, parquet hooks.
- `gui/ResultsTabPane.scala` (~360 LOC) — partition picker + background
  output loader + run-log rendering.
- `core/ColumnarBatch.scala` (~320 LOC) — defines ten `ColumnVector`
  subclasses. Adding a new `DataType` requires a new vector + companion case
  in `ColumnVector.allocate`.
- `gui/JobSession.scala` (~305 LOC) — mutable FX-thread state for the GUI.
- `gui/DagCanvas.scala` (~300 LOC) — Canvas drawing + pan/zoom/click.
- `sql/exec/AggregateExec.scala` (~255 LOC) — adding new aggregates means
  adding an `AggState`.

## Useful pointers

- **The brief:** [`INIT.md`](../INIT.md) at the repo root. Read it if a
  request is unclear about intended behavior.
- **The reference project:** `~/grid-game` — Bazel + rules_scala setup,
  BUILD file conventions, and JavaFX-on-Bazel wiring (platform classifier
  jars, Canvas + GraphicsContext rendering, mutable-state-with-manual-
  refresh UI pattern) match this repo's GUI module directly.
- **JSqlParser docs:** search `net.sf.jsqlparser` on Maven Central / GitHub.
  The jar at
  `/private/var/tmp/_bazel_owenchristie/.../jsqlparser-5.0.jar` can be `unzip
  -l`'d to inspect available classes — useful when guessing class names.
