package com.transformer.bench

import com.transformer.core._
import com.transformer.sql.plan.{ColRefExpr, KeyOrder, SortKey}

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Microbenchmarks for the comparison a sort makes: each key's typed order
  * (`SortKey.order`, a [[KeyOrder]]) over two rows of typed vectors, the
  * first key that differs deciding, as `SortExec`'s run sort, its merge and
  * `TopNExec`'s heap compare rows.
  *
  * Three benchmark dimensions:
  *
  *   - **single-key Long** — the common case for "order by id" sorts.
  *     Pure primitive compare per row pair.
  *   - **multi-key, mixed ASC/DESC** — exercise the early-exit on the
  *     first non-equal key.
  *   - **multi-key with NULLs** — exercise the NULL-first / NULL-last
  *     branches.
  *
  * The benchmark performs `BatchRows - 1` comparisons per invocation (each
  * row against the next); consume each result to defeat dead-code
  * elimination. We don't go through a sort here — that would mix the sort's
  * own work with the comparison and dilute the signal.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class SortComparatorBench {

  private final val BatchRows: Int = 1024

  // (k: Long, s: String, d: Double), without and with NULLs.
  private var rows: Array[ColumnVector] = _
  private var rowsWithNulls: Array[ColumnVector] = _

  private var singleKeyLong: SortComparatorBench.Keys = _
  private var multiKeyMixed: SortComparatorBench.Keys = _
  private var multiKeyNullsLast: SortComparatorBench.Keys = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    val rng = new java.util.SplittableRandom(0xDEC0DEL)
    def columns(nullEvery: Int): Array[ColumnVector] = {
      val k = new LongVector(new Array[Long](BatchRows), new java.util.BitSet(BatchRows), BatchRows)
      val s = new StringVector(new Array[String](BatchRows), BatchRows)
      val d = new DoubleVector(new Array[Double](BatchRows), new java.util.BitSet(BatchRows), BatchRows)
      var r = 0
      while (r < BatchRows) {
        if (nullEvery > 0 && rng.nextInt(nullEvery) == 0) k.setNull(r) else k.set(r, rng.nextLong())
        if (nullEvery > 0 && rng.nextInt(nullEvery) == 0) s.setNull(r)
        else s.set(r, SortComparatorBench.Words(rng.nextInt(SortComparatorBench.Words.length)))
        if (nullEvery > 0 && rng.nextInt(nullEvery) == 0) d.setNull(r) else d.set(r, rng.nextDouble())
        r += 1
      }
      Array(k, s, d)
    }
    rows = columns(0)
    rowsWithNulls = columns(20)

    singleKeyLong = SortComparatorBench.keys(Array((0, true)))                       // ORDER BY col0 ASC
    multiKeyMixed = SortComparatorBench.keys(Array((0, true), (1, false), (2, true))) // col0 ASC, col1 DESC, col2 ASC
    multiKeyNullsLast = SortComparatorBench.keys(Array((0, true), (2, true)))         // col0 ASC, col2 ASC over NULLs
  }

  /** ORDER BY col0 ASC over Long-only keys. The hottest single-key
    * path in the engine — comparator dominates time. */
  @Benchmark
  def singleKeyLongCompare(bh: Blackhole): Unit = {
    val n = BatchRows
    var i = 0
    while (i < n - 1) {
      bh.consume(singleKeyLong.compare(rows, i, i + 1))
      i += 1
    }
  }

  /** Multi-key compare with mixed ASC/DESC across heterogeneous types.
    * Worst case for early-exit (every key must be inspected when the
    * first is equal — which happens rarely on random data, but the
    * branch cost still shows up). */
  @Benchmark
  def multiKeyMixedCompare(bh: Blackhole): Unit = {
    val n = BatchRows
    var i = 0
    while (i < n - 1) {
      bh.consume(multiKeyMixed.compare(rows, i, i + 1))
      i += 1
    }
  }

  /** Multi-key compare with NULLs in both key columns. NULL handling is
    * a per-row branch; this benchmark exercises it on ~5% of rows. */
  @Benchmark
  def multiKeyWithNullsCompare(bh: Blackhole): Unit = {
    val n = BatchRows
    var i = 0
    while (i < n - 1) {
      bh.consume(multiKeyNullsLast.compare(rowsWithNulls, i, i + 1))
      i += 1
    }
  }
}

private[bench] object SortComparatorBench {
  /** Small string vocabulary so string compares find common prefixes
    * — realistic for category-like dimensions. */
  val Words: Array[String] = Array(
    "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
    "hotel", "india", "juliet", "kilo", "lima", "mike", "november",
    "oscar", "papa", "quebec", "romeo", "sierra", "tango")

  private val Types: Array[DataType] = Array(DataType.LongType, DataType.StringType, DataType.DoubleType)

  /** The typed orders of (columnIndex, ascending) keys, compared as a sort
    * compares them: the first key that differs decides. */
  final class Keys(columns: Array[Int], orders: Array[KeyOrder]) {
    def compare(cols: Array[ColumnVector], a: Int, b: Int): Int = {
      var k = 0
      while (k < orders.length) {
        val v = cols(columns(k))
        val c = orders(k).compare(v, a, v, b)
        if (c != 0) return c
        k += 1
      }
      0
    }
  }

  def keys(spec: Array[(Int, Boolean)]): Keys =
    new Keys(spec.map(_._1), spec.map { case (col, asc) => SortKey(ColRefExpr(col, s"c$col", Types(col)), asc).order })
}
