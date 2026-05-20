package com.transformer.bench

import com.transformer.core._
import com.transformer.sql.exec._
import com.transformer.sql.plan._

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Round-trip throughput for every spillable [[AggState]] subtype.
  *
  * Plan 09 (spill to disk) calls [[AggStateSerde.serializeBytes]] /
  * [[AggStateSerde.deserializeBytes]] on every spill+restore event,
  * once per (group, aggregate). On a workload with thousands of
  * groups and several spill rounds the cost compounds — a 10ns
  * regression per state-type means seconds of extra wall time. This
  * benchmark is the regression watchpoint for the serde format.
  *
  * One `@Benchmark` per spillable subtype. We pre-populate the state
  * at `@Setup(Trial)` with a small but realistic workload (Welford-
  * accumulator states track 100 samples; counters track 1024 hits)
  * so the serialized payload is shaped like what a real operator
  * would emit at a spill flush.
  *
  * The instrumented overloads of `serializeBytes` / `deserializeBytes`
  * (the variants that take a [[AggStateSerde.SerdeStats]] accumulator)
  * deliberately are NOT exercised here — the goal is to measure the
  * format throughput, not the timing-instrumentation overhead.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class AggStateSerdeBench {

  // States carrying realistic accumulator content. Each is populated
  // once via the `update` row path at @Setup, then the `@Benchmark`
  // methods round-trip them through the serde.
  private var countStarState: AggState = _
  private var countStarAgg: AggExpr = _

  private var countState: AggState = _
  private var countAgg: AggExpr = _

  private var sumLongState: AggState = _
  private var sumLongAgg: AggExpr = _

  private var sumDoubleState: AggState = _
  private var sumDoubleAgg: AggExpr = _

  private var avgState: AggState = _
  private var avgAgg: AggExpr = _

  private var minState: AggState = _
  private var minAgg: AggExpr = _

  private var maxState: AggState = _
  private var maxAgg: AggExpr = _

  private var countIfState: AggState = _
  private var countIfAgg: AggExpr = _

  private var momentState: AggState = _
  private var momentAgg: AggExpr = _

  private var covarState: AggState = _
  private var covarAgg: AggExpr = _

  private var corrState: AggState = _
  private var corrAgg: AggExpr = _

  // Pre-serialized payloads — `@Benchmark` methods for the deserialize
  // path re-read these so each invocation deserializes a freshly-shaped
  // byte array (deterministic in size, no GC noise from a varying input).
  private var countStarBytes: Array[Byte] = _
  private var countBytes: Array[Byte] = _
  private var sumLongBytes: Array[Byte] = _
  private var sumDoubleBytes: Array[Byte] = _
  private var avgBytes: Array[Byte] = _
  private var minBytes: Array[Byte] = _
  private var maxBytes: Array[Byte] = _
  private var countIfBytes: Array[Byte] = _
  private var momentBytes: Array[Byte] = _
  private var covarBytes: Array[Byte] = _
  private var corrBytes: Array[Byte] = _

  // 1024-row fixture used to populate every state. The bivariate
  // aggregates read columns 0 (Long) and 1 (Double).
  private final val FixtureRows: Int = 1024

  @Setup(Level.Trial)
  def setUp(): Unit = {
    val batch = BenchFixtures.fixedWidthBatch(FixtureRows, seed = 0xA66E6AL)

    val longCol = ColRefExpr(0, "k", DataType.LongType)
    val doubleCol = ColRefExpr(1, "v", DataType.DoubleType)
    val boolCol = ColRefExpr(2, "flag", DataType.BooleanType)

    // Build (agg, state) pairs and feed each through `updateBatchAt` once
    // so the serialized payload reflects realistic accumulator contents.
    countStarAgg = AggExprCountStar()
    countStarState = AggState.init(countStarAgg)
    populate(countStarState, countStarAgg, batch)

    countAgg = AggExprCount(longCol, distinct = false)
    countState = AggState.init(countAgg)
    populate(countState, countAgg, batch)

    sumLongAgg = AggExprSum(longCol)
    sumLongState = AggState.init(sumLongAgg)
    populate(sumLongState, sumLongAgg, batch)

    sumDoubleAgg = AggExprSum(doubleCol)
    sumDoubleState = AggState.init(sumDoubleAgg)
    populate(sumDoubleState, sumDoubleAgg, batch)

    avgAgg = AggExprAvg(doubleCol)
    avgState = AggState.init(avgAgg)
    populate(avgState, avgAgg, batch)

    minAgg = AggExprMin(longCol)
    minState = AggState.init(minAgg)
    populate(minState, minAgg, batch)

    maxAgg = AggExprMax(longCol)
    maxState = AggState.init(maxAgg)
    populate(maxState, maxAgg, batch)

    countIfAgg = AggExprCountIf(boolCol)
    countIfState = AggState.init(countIfAgg)
    populate(countIfState, countIfAgg, batch)

    momentAgg = AggExprStddev(doubleCol, sample = true)
    momentState = AggState.init(momentAgg)
    populate(momentState, momentAgg, batch)

    covarAgg = AggExprCovar(longCol, doubleCol, sample = true)
    covarState = AggState.init(covarAgg)
    populate(covarState, covarAgg, batch)

    corrAgg = AggExprCorr(longCol, doubleCol)
    corrState = AggState.init(corrAgg)
    populate(corrState, corrAgg, batch)

    // Pre-serialize for the deserialize-side benchmarks.
    countStarBytes = AggStateSerde.serializeBytes(countStarState)
    countBytes = AggStateSerde.serializeBytes(countState)
    sumLongBytes = AggStateSerde.serializeBytes(sumLongState)
    sumDoubleBytes = AggStateSerde.serializeBytes(sumDoubleState)
    avgBytes = AggStateSerde.serializeBytes(avgState)
    minBytes = AggStateSerde.serializeBytes(minState)
    maxBytes = AggStateSerde.serializeBytes(maxState)
    countIfBytes = AggStateSerde.serializeBytes(countIfState)
    momentBytes = AggStateSerde.serializeBytes(momentState)
    covarBytes = AggStateSerde.serializeBytes(covarState)
    corrBytes = AggStateSerde.serializeBytes(corrState)
  }

  // Build the per-arg vectors once and call `updateBatchAt`. Mirrors the
  // AggregateExec hot path so the accumulator state at serialize time is
  // representative.
  private def populate(state: AggState, agg: AggExpr, batch: ColumnarBatch): Unit = {
    val args = agg.args
    val n = args.length
    val argVecs = new Array[ColumnVector](n)
    var i = 0
    val it = args.iterator
    while (i < n) {
      argVecs(i) = it.next().evalVec(batch)
      i += 1
    }
    state.updateBatchAt(argVecs, batch.numRows)
  }

  // ----------------------------------------------------------------------
  // COUNT(*) — fixed 8 bytes (one Long).
  // ----------------------------------------------------------------------
  @Benchmark def serializeCountStar(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(countStarState))
  @Benchmark def deserializeCountStar(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(countStarAgg, countStarBytes))

  // ----------------------------------------------------------------------
  // COUNT(col) — fixed 8 bytes.
  // ----------------------------------------------------------------------
  @Benchmark def serializeCount(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(countState))
  @Benchmark def deserializeCount(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(countAgg, countBytes))

  // ----------------------------------------------------------------------
  // SUM(long) — boolean (sawAny) + 8-byte sum.
  // ----------------------------------------------------------------------
  @Benchmark def serializeSumLong(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(sumLongState))
  @Benchmark def deserializeSumLong(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(sumLongAgg, sumLongBytes))

  // ----------------------------------------------------------------------
  // SUM(double) — same shape as SUM(long) but with a Double payload.
  // ----------------------------------------------------------------------
  @Benchmark def serializeSumDouble(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(sumDoubleState))
  @Benchmark def deserializeSumDouble(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(sumDoubleAgg, sumDoubleBytes))

  // ----------------------------------------------------------------------
  // AVG — count + running sum, fixed 16 bytes.
  // ----------------------------------------------------------------------
  @Benchmark def serializeAvg(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(avgState))
  @Benchmark def deserializeAvg(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(avgAgg, avgBytes))

  // ----------------------------------------------------------------------
  // MIN / MAX — a typed extremum with one primitive slot (Long here).
  // The MinMaxState encoding tags the value type alongside the bytes,
  // so this is slightly more work than a raw Long write.
  // ----------------------------------------------------------------------
  @Benchmark def serializeMin(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(minState))
  @Benchmark def deserializeMin(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(minAgg, minBytes))

  @Benchmark def serializeMax(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(maxState))
  @Benchmark def deserializeMax(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(maxAgg, maxBytes))

  // ----------------------------------------------------------------------
  // COUNT_IF — fixed 8 bytes.
  // ----------------------------------------------------------------------
  @Benchmark def serializeCountIf(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(countIfState))
  @Benchmark def deserializeCountIf(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(countIfAgg, countIfBytes))

  // ----------------------------------------------------------------------
  // MomentState (STDDEV/VAR) — Long n + Double mean + Double m2, 24 bytes.
  // ----------------------------------------------------------------------
  @Benchmark def serializeMoment(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(momentState))
  @Benchmark def deserializeMoment(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(momentAgg, momentBytes))

  // ----------------------------------------------------------------------
  // CovarState — Long n + 3 Doubles, 32 bytes. Bivariate state.
  // ----------------------------------------------------------------------
  @Benchmark def serializeCovar(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(covarState))
  @Benchmark def deserializeCovar(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(covarAgg, covarBytes))

  // ----------------------------------------------------------------------
  // CorrState — Long n + 5 Doubles, 48 bytes. Largest spillable state.
  // ----------------------------------------------------------------------
  @Benchmark def serializeCorr(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.serializeBytes(corrState))
  @Benchmark def deserializeCorr(bh: Blackhole): Unit =
    bh.consume(AggStateSerde.deserializeBytes(corrAgg, corrBytes))
}
