# Plan 20: RANGE frames, and the default frame, run as ROWS

> Status: **done** 2026-10-31 · found by the 2026-10-31 audit · decided by the user:
> standard RANGE, with value offsets · Effort: 2 days · Risk: medium (changes
> the answer of every running aggregate over a tied ORDER BY)

## How this was found

`docs/gotchas.md` listed RANGE-as-ROWS as the one documented wrong answer and
claimed it "differs only when the ORDER BY has ties". The audit measured it at
`b2c83fc` over `w(g, k, x)` = (a,1,10) (a,1,20) (a,2,30) (a,3,40) (a,3,50)
(b,1,1); the claim is false for offset frames.

## Problem

| window | answer for a's rows | standard SQL |
|---|---|---|
| `SUM(x) OVER (PARTITION BY g ORDER BY k)` | 10, 30, 60, 100, 150 | 30, 30, 60, 150, 150 |
| `COUNT(*) OVER (PARTITION BY g ORDER BY k)` | 1, 2, 3, 4, 5 | 2, 2, 3, 5, 5 |
| `... RANGE BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW` | as the first row | as the first row |
| `SUM(x) ... RANGE BETWEEN 1 PRECEDING AND 1 FOLLOWING` | 30, 60, 90, 120, 90 | 60, 60, 150, 120, 120 |

The default frame with an ORDER BY is `RANGE UNBOUNDED PRECEDING`, so every
running aggregate over a tied key is wrong, and which tied row gets which total
depends on the order the rows reached the window: the fuzzers had to classify
every running aggregate as layout-dependent (`WinItem.tieSensitive`) and keep
it out of mode agreement. `WindowExec.frameBoundsFor` maps every bound to a row
offset whatever the frame type.

Beside it, frames the engine accepts and then evaluates as nothing:

| frame | answer | Postgres |
|---|---|---|
| `ROWS BETWEEN 1 FOLLOWING AND 1 PRECEDING` | NULL on every row | error: frame starting from following row cannot have preceding rows |
| `ROWS BETWEEN UNBOUNDED FOLLOWING AND CURRENT ROW` | NULL | error: frame start cannot be UNBOUNDED FOLLOWING |
| `ROWS BETWEEN CURRENT ROW AND UNBOUNDED PRECEDING` | NULL | error: frame end cannot be UNBOUNDED PRECEDING |
| `ROW_NUMBER() OVER (ORDER BY k ROWS BETWEEN 1 PRECEDING AND CURRENT ROW)` | the frame ignored | ignored (Spark refuses) |
| `ROWS BETWEEN CURRENT ROW AND 2147483647 FOLLOWING` | runs for minutes; `r + k.toInt` overflows, so every row but the first gets an empty frame | the partition's tail |

and the frame loop aggregates each row's frame from scratch, O(n^2) for a
running total over one partition.

## Decision and fix

- **RANGE peers**: a `CURRENT ROW` bound of a RANGE frame is the first (start) or
  last (end) peer of the current row, peers being the rows the ORDER BY ties
  (`Ops.cmp == 0` on every key, the test RANK uses). The default frame with an
  ORDER BY is therefore peer-inclusive.
- **RANGE offsets**: `n PRECEDING` / `n FOLLOWING` in a RANGE frame bound the
  ORDER BY value, `k - n` .. `k + n` (reversed under DESC). They need exactly one
  ORDER BY key of a numeric type and a non-negative numeric literal offset, and
  are refused otherwise, naming the rule (as Postgres requires; DATE and
  TIMESTAMP keys would need an INTERVAL the engine does not have). A NULL key's
  frame is its NULL peers; NaN is a peer of NaN only.
- **Refused**: a frame whose start is `UNBOUNDED FOLLOWING`, whose end is
  `UNBOUNDED PRECEDING`, or that starts after the current row and ends before
  it (`FOLLOWING .. PRECEDING`, `CURRENT ROW .. PRECEDING`, `FOLLOWING .. CURRENT
  ROW`); a frame clause on `ROW_NUMBER`, `RANK`, `DENSE_RANK`, `LAG` or `LEAD`,
  which have no frame. A frame both of whose bounds are offsets on one side
  (`2 PRECEDING AND 1 PRECEDING`) stays legal and is empty at a partition's edge.
- **Bounds in long arithmetic, clamped to the partition**, so no offset
  overflows or loops past the partition.
- **Incremental frames**: a frame that starts at `UNBOUNDED PRECEDING` grows one
  aggregate state as its end advances (each row reads `finish` of the state so
  far), O(n) per partition; other frames keep aggregating their rows.

## Tests

`window_exec_spill_test` and `SqlEngineTest` regressions for each table row
above, on the heap and the spill path. A new property, a brute-force window
reference in the fuzz harness: random partitions with tied and NULL keys, a
random frame (ROWS or RANGE, every bound kind, offsets), every aggregate; the
reference computes each row's frame from the definition and the engine must
agree (`RowOracle` tolerance for floats). Running aggregates over a RANGE frame
become tie-insensitive (`WinItem.tieSensitive`), so they flow through mode
agreement and join commutativity; the coverage guard measures it.

## Docs

README (window functions), gotchas (the RANGE entry moves from "not done" to a
fixed entry), architecture (window frames), testing.md.

## Outcome

Done as planned. `FrameBound` gained `ValuePreceding` / `ValueFollowing`
(a BigDecimal offset, so `1.5 PRECEDING` measures exactly) beside the ROWS
counts, and the binder refuses the frames SQL does not allow and a frame on
a function that reads none. `WindowExec.frameBounds` gives each row's inclusive
positions: ROWS bounds clamped in long arithmetic, RANGE bounds by binary
search under `SortKey.compare`, the sort's own comparison, so a NULL key's
offset frame is its NULL peers and a NaN's its NaN peers without a special
case. A frame anchored at UNBOUNDED PRECEDING (or FOLLOWING) grows one
aggregate state as the current row moves (every `AggState.finish` reads its
state without changing it), so a running total is one pass, where it was
O(n^2) per partition.

`QueryGen.generateWindowFrameCase` and `oracle/WindowFrames` hold every frame
kind to SQL's definition, computed as a set filter rather than by search, in
every layout of `ModeDifferential.allModes`, spill included; against the code
before it failed at seed 2 (`RANGE BETWEEN CURRENT ROW AND CURRENT ROW` summed
one row, not its peers). `MetaQueryGen` now classifies a running aggregate
over the default frame as tie-insensitive, so those windows flow through mode
agreement and join commutativity, sharded too.
