package com.transformer.bench

import com.transformer.core._

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Microbenchmarks for [[Spill.estimateBytes]].
  *
  * Plan 09's spill-capable operators call `estimateBytes` per input
  * batch as part of the flush-trigger check. The contract is that the
  * estimate is cheap — well under 1% of the typical per-batch
  * aggregate work (which is on the order of tens of microseconds).
  * This bench is the regression watchpoint for that invariant.
  *
  * Four batch shapes:
  *
  *   - **small fixed-width** — 100 rows × 4 Int columns. Tests the
  *     primitive-only short path.
  *   - **large fixed-width** — 8192 rows × 8 Long columns. Same path,
  *     larger fixture so the per-batch fixed overheads (schema length
  *     read, mul math) dilute and the inner column-loop cost dominates.
  *   - **variable-width** — 8192 rows × 4 String columns with ~16
  *     character payloads. Exercises the per-row string length scan;
  *     this is the *expensive* path because it iterates rows-per-column.
  *   - **mixed** — 4 Int + 2 String + 2 Long, 8192 rows. Realistic
  *     for jaffle-shape workloads where most columns are fixed-width
  *     but a couple of dimension keys are strings.
  *
  * Per the plan: a single `estimateBytes` call should be **well under
  * 1%** of typical per-batch aggregate work (~10s of microseconds).
  * Even on the variable-width worst case the cost should sit in the
  * hundreds-of-nanoseconds range.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class SpillEstimateBench {

  private var smallFixed: ColumnarBatch = _
  private var largeFixed: ColumnarBatch = _
  private var variableWidth: ColumnarBatch = _
  private var mixed: ColumnarBatch = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    smallFixed = buildSmallFixed()
    largeFixed = buildLargeFixed()
    variableWidth = buildVariableWidth()
    mixed = buildMixed()
  }

  /** 100 rows × 4 Int columns — primitive-only short path. */
  @Benchmark
  def estimateSmallFixed(bh: Blackhole): Unit = {
    bh.consume(Spill.estimateBytes(smallFixed))
  }

  /** 8192 rows × 8 Long columns — large primitive batch. */
  @Benchmark
  def estimateLargeFixed(bh: Blackhole): Unit = {
    bh.consume(Spill.estimateBytes(largeFixed))
  }

  /** 8192 rows × 4 String columns × ~16 chars each. Hot path: the
    * per-row string length scan multiplied by four columns. */
  @Benchmark
  def estimateVariableWidth(bh: Blackhole): Unit = {
    bh.consume(Spill.estimateBytes(variableWidth))
  }

  /** 8192 rows × (4 Int + 2 String + 2 Long) — realistic jaffle shape. */
  @Benchmark
  def estimateMixed(bh: Blackhole): Unit = {
    bh.consume(Spill.estimateBytes(mixed))
  }

  // -----------------------------------------------------------------
  // Fixture builders — kept private to this bench class. Each returns
  // a `ColumnarBatch` whose numRows == capacity.
  // -----------------------------------------------------------------

  private def buildSmallFixed(): ColumnarBatch = {
    val rows = 100
    val schema = Schema(Vector(
      Field("a", DataType.IntType),
      Field("b", DataType.IntType),
      Field("c", DataType.IntType),
      Field("d", DataType.IntType)))
    val b = new ColumnarBatch(schema, rows)
    val rng = new java.util.SplittableRandom(0x5AA1000L)
    var i = 0
    while (i < rows) {
      b.column(0).asInstanceOf[IntVector].set(i, rng.nextInt())
      b.column(1).asInstanceOf[IntVector].set(i, rng.nextInt())
      b.column(2).asInstanceOf[IntVector].set(i, rng.nextInt())
      b.column(3).asInstanceOf[IntVector].set(i, rng.nextInt())
      i += 1
    }
    b.setNumRows(rows)
    b
  }

  private def buildLargeFixed(): ColumnarBatch = {
    val rows = 8192
    val schema = Schema(Vector.tabulate(8)(i => Field(s"l$i", DataType.LongType)))
    val b = new ColumnarBatch(schema, rows)
    val rng = new java.util.SplittableRandom(0xB16F1DEDL)
    var c = 0
    while (c < 8) {
      val col = b.column(c).asInstanceOf[LongVector]
      var r = 0
      while (r < rows) { col.set(r, rng.nextLong()); r += 1 }
      c += 1
    }
    b.setNumRows(rows)
    b
  }

  private def buildVariableWidth(): ColumnarBatch = {
    val rows = 8192
    val schema = Schema(Vector.tabulate(4)(i => Field(s"s$i", DataType.StringType)))
    val b = new ColumnarBatch(schema, rows)
    // Mostly ~16-char strings so the per-string length contribution
    // dominates the per-row 24-byte object-header constant.
    val rng = new java.util.SplittableRandom(0xAB1DE10L)
    var c = 0
    while (c < 4) {
      val col = b.column(c).asInstanceOf[StringVector]
      var r = 0
      while (r < rows) {
        col.set(r, SpillEstimateBench.makeString(rng, length = 16))
        r += 1
      }
      c += 1
    }
    b.setNumRows(rows)
    b
  }

  private def buildMixed(): ColumnarBatch = {
    val rows = 8192
    val schema = Schema(Vector(
      Field("i0", DataType.IntType),
      Field("i1", DataType.IntType),
      Field("i2", DataType.IntType),
      Field("i3", DataType.IntType),
      Field("s0", DataType.StringType),
      Field("s1", DataType.StringType),
      Field("l0", DataType.LongType),
      Field("l1", DataType.LongType)))
    val b = new ColumnarBatch(schema, rows)
    val rng = new java.util.SplittableRandom(0xA11EDL)
    var r = 0
    while (r < rows) {
      b.column(0).asInstanceOf[IntVector].set(r, rng.nextInt())
      b.column(1).asInstanceOf[IntVector].set(r, rng.nextInt())
      b.column(2).asInstanceOf[IntVector].set(r, rng.nextInt())
      b.column(3).asInstanceOf[IntVector].set(r, rng.nextInt())
      b.column(4).asInstanceOf[StringVector].set(r, SpillEstimateBench.makeString(rng, length = 16))
      b.column(5).asInstanceOf[StringVector].set(r, SpillEstimateBench.makeString(rng, length = 16))
      b.column(6).asInstanceOf[LongVector].set(r, rng.nextLong())
      b.column(7).asInstanceOf[LongVector].set(r, rng.nextLong())
      r += 1
    }
    b.setNumRows(rows)
    b
  }
}

private[bench] object SpillEstimateBench {
  /** Generate a deterministic ASCII string of the given length using the
    * passed RNG. Avoids per-call allocations from `Random.alphanumeric`. */
  def makeString(rng: java.util.SplittableRandom, length: Int): String = {
    val chars = new Array[Char](length)
    var i = 0
    while (i < length) {
      // Lowercase ASCII so the resulting String's `.length` matches its
      // byte-count under UTF-8 (no multi-byte surprises in the estimate).
      chars(i) = ('a' + rng.nextInt(26)).toChar
      i += 1
    }
    new String(chars)
  }
}
