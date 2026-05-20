package com.transformer.bench

import com.transformer.core._

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Microbenchmarks for [[ColumnVector.allocate]] (per-type allocation
  * cost) and [[ColumnarBatch.selectByBoolean]] (FilterExec's hot path).
  *
  * Two surfaces:
  *
  *   - **Per-type vector allocation.** [[ColumnVector.allocate]] is
  *     called once per output column per batch by every operator that
  *     materializes a fresh [[ColumnarBatch]] (Project, Filter, scans).
  *     Allocation cost is dominated by the underlying primitive array
  *     plus the `java.util.BitSet` null tracker. We benchmark each
  *     primitive vector type at a representative capacity (1024 rows,
  *     same as [[ColumnarBatch.DefaultCapacity / 8]] — JMH's per-trial
  *     setup costs dwarf bigger sizes).
  *   - **`selectByBoolean` paths.**  Two cases — **fast path** when the
  *     boolean mask passes every row (returns `this` without a copy),
  *     and **full-copy path** when the mask drops half the rows (must
  *     copy each column into a freshly-allocated batch). These are
  *     [[FilterExec]]'s two ends of the cost curve; tracking both
  *     catches regressions in either branch.
  *
  * Why no IntVector / DoubleVector / BooleanVector / StringVector
  * dedicated select benchmarks: the per-column copy in `select`
  * delegates to each column's `copyTo`, which is dominated by the
  * `Array.copy` of the primitive payload. The full-copy bench below
  * already exercises every column type at once via [[BenchFixtures]].
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class ColumnarBatchBench {

  private final val Capacity: Int = 1024
  private final val BatchRows: Int = 1024

  private var batch: ColumnarBatch = _
  private var allPassMask: BooleanVector = _
  private var halfMask: BooleanVector = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    batch = BenchFixtures.fixedWidthBatch(BatchRows, seed = 0xC0FFEEL)

    // All-pass mask exercises the `selectByBoolean` fast path: when every
    // row passes the predicate, the method returns `this` without a copy.
    val allValues = new Array[Boolean](BatchRows)
    java.util.Arrays.fill(allValues, true)
    allPassMask = new BooleanVector(allValues, new java.util.BitSet(BatchRows), BatchRows)

    // Half mask drops 50% of rows on a fixed pattern (even rows pass).
    // The pattern is deterministic so the branch predictor can warm.
    val halfValues = new Array[Boolean](BatchRows)
    var i = 0
    while (i < BatchRows) {
      halfValues(i) = (i & 1) == 0
      i += 1
    }
    halfMask = new BooleanVector(halfValues, new java.util.BitSet(BatchRows), BatchRows)
  }

  /** Allocate a fresh IntVector at the typical 1024-row capacity. */
  @Benchmark
  def allocateIntVector(bh: Blackhole): Unit = {
    bh.consume(ColumnVector.allocate(DataType.IntType, Capacity))
  }

  /** Allocate a fresh LongVector — same shape as Int, different array
    * size (8 bytes per row vs 4). */
  @Benchmark
  def allocateLongVector(bh: Blackhole): Unit = {
    bh.consume(ColumnVector.allocate(DataType.LongType, Capacity))
  }

  /** Allocate a fresh DoubleVector. */
  @Benchmark
  def allocateDoubleVector(bh: Blackhole): Unit = {
    bh.consume(ColumnVector.allocate(DataType.DoubleType, Capacity))
  }

  /** Allocate a fresh BooleanVector. */
  @Benchmark
  def allocateBooleanVector(bh: Blackhole): Unit = {
    bh.consume(ColumnVector.allocate(DataType.BooleanType, Capacity))
  }

  /** Allocate a fresh StringVector — no BitSet, just a reference array
    * (null is in-band). Should be cheaper than the primitive vectors. */
  @Benchmark
  def allocateStringVector(bh: Blackhole): Unit = {
    bh.consume(ColumnVector.allocate(DataType.StringType, Capacity))
  }

  /** `selectByBoolean` fast path — every row passes, the method returns
    * the input batch without any per-column copy. Measures just the
    * mask scan + early-return branch. */
  @Benchmark
  def selectByBooleanAllPass(bh: Blackhole): Unit = {
    bh.consume(batch.selectByBoolean(allPassMask))
  }

  /** `selectByBoolean` full-copy path — half the rows pass, every
    * column is copied row-by-row into a freshly-allocated batch.
    * Dominant cost in FilterExec when the predicate is selective. */
  @Benchmark
  def selectByBooleanHalfMask(bh: Blackhole): Unit = {
    bh.consume(batch.selectByBoolean(halfMask))
  }
}
