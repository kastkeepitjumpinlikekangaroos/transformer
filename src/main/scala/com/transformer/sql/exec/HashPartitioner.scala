package com.transformer.sql.exec

import com.transformer.core._

/** Compute the shard index for a row given its pre-evaluated key column
  * vectors. The intra-JVM analog of Spark's "ExchangeKeyHash" — the deciding
  * function for which downstream partition a row lands in after a pipeline
  * breaker.
  *
  * The hash here is independent of [[KeyCodec]]'s hash (which sizes the
  * intra-shard map). All callers that need to land matching keys on the same
  * shard — both sides of a join exchange, the build and probe inside one
  * shard, repeated exchanges over the same logical key set — must route
  * through this object so they share one pinned hash. The function uses
  * Murmur3-style mixing per cell and 31-style accumulation across cells so
  * column reordering changes the shard for the same multiset of values
  * (callers must keep key column order stable across build / probe).
  *
  * NULL handling has two policies:
  *
  *   - [[NullsToLast]] (default): NULLs route to shard `K - 1`. Use for
  *     GROUP BY / DISTINCT / PARTITION BY, where SQL treats NULL keys as a
  *     single group and they need to land together.
  *   - [[NullsToZero]]: NULLs route to shard `0`. Use for equi-join probes,
  *     where a NULL key can never match by SQL three-valued logic but the
  *     row must still surface in some shard so the outer-join "unmatched
  *     probe" emission sees it. Picking shard 0 is arbitrary but pinned.
  *
  * Empty key list collapses to shard 0 — the operator never sees this in
  * practice because the planner skips ExchangeExec when there are no
  * partitioning keys, but the defensive zero keeps the function total.
  */
object HashPartitioner {

  sealed trait NullPolicy
  case object NullsToLast extends NullPolicy
  case object NullsToZero extends NullPolicy

  /** Shard index for row `row` of pre-evaluated key vectors. Returns a value
    * in `[0, numShards)`. */
  def shardIdx(
      keyVecs: Array[ColumnVector],
      row: Int,
      numShards: Int,
      nullPolicy: NullPolicy): Int = {
    require(numShards > 0, s"numShards must be positive, got $numShards")
    val nKeys = keyVecs.length
    if (nKeys == 0) return 0
    var anyNull = false
    var i = 0
    while (i < nKeys && !anyNull) {
      if (keyVecs(i).isNull(row)) anyNull = true
      i += 1
    }
    if (anyNull) nullPolicy match {
      case NullsToLast => numShards - 1
      case NullsToZero => 0
    } else {
      val h = hashRow(keyVecs, row)
      val nonNeg = h & 0x7FFFFFFF
      nonNeg % numShards
    }
  }

  /** The pinned cross-column hash. Per-column hashing dispatches on the
    * vector's concrete subtype so primitive cells stay unboxed; reference
    * cells (String, Binary, Decimal) go through their `hashCode` /
    * `Arrays.hashCode`. Cross-column combination uses Murmur3's 32-bit
    * finalizer applied to a 31-style accumulator — the finalizer spreads
    * bits enough that contiguous small-integer keys don't cluster after the
    * `% numShards`. */
  def hashRow(keyVecs: Array[ColumnVector], row: Int): Int = {
    var h = 1
    var i = 0
    while (i < keyVecs.length) {
      h = 31 * h + hashCell(keyVecs(i), row)
      i += 1
    }
    fmix32(h)
  }

  /** Hash one cell. NULL is the caller's concern — this is only reached for
    * non-null cells. Float/Double go through `floatToIntBits` /
    * `doubleToLongBits` so the bit pattern (not the IEEE value) drives the
    * hash; this matches [[PackedBytesCodec]]'s byte layout so a row's
    * partitioner-shard membership is consistent with the codec's bucket
    * computation downstream. */
  def hashCell(col: ColumnVector, row: Int): Int = col match {
    case v: IntVector       => fmix32(v.values(row))
    case v: LongVector      => fmix64ToInt(v.values(row))
    case v: FloatVector     => fmix32(java.lang.Float.floatToIntBits(v.values(row)))
    case v: DoubleVector    => fmix64ToInt(java.lang.Double.doubleToLongBits(v.values(row)))
    case v: BooleanVector   => if (v.values(row)) 1231 else 1237
    case v: DateVector      => fmix32(v.values(row))
    case v: TimestampVector => fmix64ToInt(v.values(row))
    case v: StringVector    => v.values(row).hashCode
    case v: BinaryVector    => java.util.Arrays.hashCode(v.values(row))
    case v: DecimalVector   => v.values(row).hashCode
    case _                  => col.getBoxed(row).hashCode
  }

  /** Murmur3 32-bit finalizer. Spreads bits so the resulting modulo
    * distribution is even even when the input is a contiguous range of small
    * integers (the pathological case for `Int.hashCode % K`). */
  private def fmix32(h0: Int): Int = {
    var h = h0
    h ^= (h >>> 16)
    h *= 0x85ebca6b
    h ^= (h >>> 13)
    h *= 0xc2b2ae35
    h ^= (h >>> 16)
    h
  }

  /** Fold a 64-bit value into an Int via Murmur64 mixing. Avoids the
    * pathological clustering plain `Long.hashCode` (xor of halves) shows on
    * dense Long keys. */
  private def fmix64ToInt(v: Long): Int = {
    var h = v
    h ^= (h >>> 33)
    h *= 0xff51afd7ed558ccdL
    h ^= (h >>> 33)
    h *= 0xc4ceb9fe1a85ec53L
    h ^= (h >>> 33)
    ((h ^ (h >>> 32)) & 0xffffffffL).toInt
  }
}
