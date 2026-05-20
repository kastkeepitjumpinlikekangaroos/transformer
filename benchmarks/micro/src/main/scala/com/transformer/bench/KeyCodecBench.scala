package com.transformer.bench

import com.transformer.core._

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Microbenchmarks for [[KeyCodec]] and [[LongHashMap]].
  *
  * Covers the three hash-path encodings used by HashAggregateExec,
  * HashJoinExec, DistinctExec, ExchangeExec, and WindowExec:
  *
  *   - **Packed bytes** ([[PackedBytesCodec]]) — fixed-width key columns. Per
  *     `encodeFromBatch` cost is the relevant signal because the operator
  *     pays it on every row, every batch.
  *   - **Object array** ([[ObjectArrayCodec]]) — at least one variable-width
  *     key column (String / Binary / Decimal). Boxes column values into a
  *     fresh `Array[AnyRef]` per row.
  *   - **Single-Long primitive** ([[LongHashMap.getOrInsert]]) — the
  *     fast-path used by HashAggregateExec when the group-by reduces to one
  *     Long-fittable column. No `Key` allocation at all.
  *
  * The performance gain from plan 02 (packed aggregate keys) lives here:
  * `packedEncode` should run 3-5× faster than `objectArrayEncode` on the
  * same fixed-width payload, and `longHashInsertWarm` should be the
  * cheapest path of the three because it skips the Key allocation
  * entirely.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class KeyCodecBench {

  // Realistic operator batch size — same default as ColumnarBatch.
  private final val BatchRows: Int = 1024

  private var packedBatch: ColumnarBatch = _
  private var packedCodec: KeyCodec = _

  private var objBatch: ColumnarBatch = _
  private var objCodec: KeyCodec = _

  private var longMapWarm: LongHashMap[java.lang.Long] = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    // Packed path: two Long columns + a Double payload. The codec only
    // looks at the two key columns; the payload exists so the
    // ColumnarBatch shape matches what an operator would see.
    packedBatch = BenchFixtures.fixedWidthBatch(BatchRows, seed = 0x517L)
    packedCodec = KeyCodec.forColumns(Array(0, 2), Array(DataType.LongType, DataType.BooleanType))

    objBatch = BenchFixtures.variableWidthBatch(BatchRows, seed = 0x517L)
    objCodec = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.StringType))

    // Warm LongHashMap: same key set inserted in @Setup, so the
    // `longHashInsertWarm` benchmark measures the steady-state probe
    // path (every probe hits an existing entry).
    longMapWarm = new LongHashMap[java.lang.Long](initialCapacity = 2048)
    var i = 0
    while (i < BatchRows) {
      // Pull keys from the packed batch so the benchmark probes the same
      // distribution it built on.
      val k = packedBatch.column(0).asInstanceOf[LongVector].values(i)
      val boxed: java.lang.Long = java.lang.Long.valueOf(k)
      longMapWarm.put(k, boxed)
      i += 1
    }
  }

  /** Encode every row of the batch via [[PackedBytesCodec.encodeFromBatch]].
    * One Blackhole consume per row keeps the JIT from dead-code-eliminating
    * the unused [[BytesKey]] allocations. */
  @Benchmark
  def packedEncode(bh: Blackhole): Unit = {
    var r = 0
    while (r < BatchRows) {
      bh.consume(packedCodec.encodeFromBatch(packedBatch, r))
      r += 1
    }
  }

  /** Same shape but through [[ObjectArrayCodec]] — the variable-width
    * path that pays one `Array[AnyRef]` + one `ObjectArrayKey` per row
    * plus per-element boxing for the String column. */
  @Benchmark
  def objectArrayEncode(bh: Blackhole): Unit = {
    var r = 0
    while (r < BatchRows) {
      bh.consume(objCodec.encodeFromBatch(objBatch, r))
      r += 1
    }
  }

  /** Cold [[LongHashMap]] insert path. A fresh map is allocated per
    * @Benchmark invocation so JMH measures the steady-state probe+grow
    * cost on every batch. The benchmark inserts every row of the packed
    * batch into the map — this exercises rehash growth twice (the map
    * starts at 16, doubles through 32, 64, 128, 256, 512, 1024, 2048
    * before fitting BatchRows entries). */
  @Benchmark
  def longHashInsertCold(bh: Blackhole): Unit = {
    val map = new LongHashMap[java.lang.Long](initialCapacity = 16)
    val kCol = packedBatch.column(0).asInstanceOf[LongVector]
    var r = 0
    while (r < BatchRows) {
      val k = kCol.values(r)
      val v: java.lang.Long = java.lang.Long.valueOf(k)
      map.getOrInsert(k, v)
      r += 1
    }
    bh.consume(map.size)
  }

  /** Warm [[LongHashMap]] lookup path — the map is pre-populated at
    * @Setup, so every probe in this benchmark hits an existing entry.
    * This is the dominant path during steady-state aggregation when the
    * group key cardinality has stabilized. */
  @Benchmark
  def longHashInsertWarm(bh: Blackhole): Unit = {
    val map = longMapWarm
    val kCol = packedBatch.column(0).asInstanceOf[LongVector]
    var r = 0
    while (r < BatchRows) {
      val k = kCol.values(r)
      bh.consume(map.get(k))
      r += 1
    }
  }
}
