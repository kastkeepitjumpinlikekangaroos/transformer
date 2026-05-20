package com.transformer.sql.exec

import com.transformer.sql.plan._

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, DataInput, DataInputStream, DataOutput, DataOutputStream}

/** Serialize / deserialize [[AggState]] for the spill path. Each spillable
  * subtype writes its own accumulator fields via `AggState.writeSelf` /
  * `readSelf`. This object owns:
  *   1. The agg→state factory dispatch on deserialize (the recipient state
  *      type is determined by the originating [[AggExpr]]).
  *   2. `Array[Byte]` convenience wrappers around the streaming pair.
  *   3. The `isSpillable` predicate. Operators must consult this before
  *      enabling spill — `COUNT(DISTINCT …)` deliberately falls back to
  *      the non-spill path in v1 (see plans/perf/09-spill-to-disk.md).
  *
  * Why this file lives in `sql.exec` and not `core`: `AggState` is sealed
  * inside this package; the spill format references its internal fields.
  * The plan's "core/AggStateSerde.scala" filename was incidental — the
  * dependency direction is `sql.exec → core`, not the reverse.
  */
object AggStateSerde {

  /** True when every aggregate in `aggs` can round-trip through
    * `serialize` / `deserialize`. The operator-level switch — if any
    * aggregate is non-spillable, the whole map stays in heap. */
  def allSpillable(aggs: Iterable[AggExpr]): Boolean = aggs.forall(isSpillable)

  /** Per-aggregate spillability check. `COUNT(DISTINCT col)` is the only
    * non-spillable aggregate in v1 — its `HashSet[Any]` doesn't round-trip
    * through a typed schema, and CountDistinct on >threshold cardinality
    * is uncommon enough that the heap fallback is the documented behavior.
    */
  def isSpillable(agg: AggExpr): Boolean = agg match {
    case AggExprCount(_, true) => false
    case _                      => true
  }

  /** Stream the state's accumulator fields into `out`. Delegates to
    * [[AggState.writeSelf]] — throws if `state` is non-spillable. */
  def serialize(state: AggState, out: DataOutput): Unit = state.writeSelf(out)

  /** Construct the right state subtype for `agg` and populate from `in`.
    * `agg` carries the runtime info needed to pick the recipient state:
    *   - `AggExprSum(_)` chooses Long vs Double sum based on child type.
    *   - `AggExprMin`/`AggExprMax` carries `resultType`, which determines
    *     which `MinMaxState` slot is canonical.
    *   - `AggExprStddev`/`AggExprVariance` carries `sample` / `stddev`
    *     flags — not round-tripped, reconstructed here. */
  def deserialize(agg: AggExpr, in: DataInput): AggState = {
    if (!isSpillable(agg))
      throw new UnsupportedOperationException(s"$agg is not spillable in v1")
    val state = AggState.init(agg)
    state.readSelf(in)
    state
  }

  /** Convenience: serialize to a fresh byte array. */
  def serializeBytes(state: AggState): Array[Byte] = {
    val baos = new ByteArrayOutputStream(64)
    val dos = new DataOutputStream(baos)
    state.writeSelf(dos)
    dos.flush()
    baos.toByteArray
  }

  /** Convenience: deserialize a state of the given agg type from a
    * previously-`serializeBytes`'d buffer. */
  def deserializeBytes(agg: AggExpr, bytes: Array[Byte]): AggState =
    deserialize(agg, new DataInputStream(new ByteArrayInputStream(bytes)))
}
