package com.transformer.bench

import com.transformer.core._
import com.transformer.sql.plan.Ops

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.util.concurrent.TimeUnit

/** Microbenchmarks for the SortExec row comparator. SortExec's production
  * comparator is package-private; this bench reproduces its semantics
  * (per-key NULL-handling + asc/desc) over `Array[Any]` rows so the
  * benchmark sees the same comparison cost without reaching into
  * package internals.
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
  * The benchmark performs `BatchRows × CompareCount` comparisons per
  * invocation; consume each result to defeat dead-code elimination. We
  * don't go through `java.util.Arrays.sort` here — that would mix
  * `Arrays.sort`'s internal tournament with the comparator and dilute
  * the signal.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class SortComparatorBench {

  private final val BatchRows: Int = 1024

  // Rows materialized as `Array[Any]` per the SortExec internal format.
  private var rows: Array[Array[Any]] = _
  private var rowsWithNulls: Array[Array[Any]] = _

  private var singleKeyLong: java.util.Comparator[Array[Any]] = _
  private var multiKeyMixed: java.util.Comparator[Array[Any]] = _
  private var multiKeyNullsLast: java.util.Comparator[Array[Any]] = _

  @Setup(Level.Trial)
  def setUp(): Unit = {
    val rng = new java.util.SplittableRandom(0xDEC0DEL)
    rows = Array.fill(BatchRows) {
      // (k: Long, s: String, d: Double)
      Array[Any](
        java.lang.Long.valueOf(rng.nextLong()),
        SortComparatorBench.Words(rng.nextInt(SortComparatorBench.Words.length)),
        java.lang.Double.valueOf(rng.nextDouble()))
    }
    rowsWithNulls = Array.fill(BatchRows) {
      val k: Any = if (rng.nextInt(20) == 0) null else java.lang.Long.valueOf(rng.nextLong())
      val s: Any = if (rng.nextInt(20) == 0) null else SortComparatorBench.Words(rng.nextInt(SortComparatorBench.Words.length))
      val d: Any = if (rng.nextInt(20) == 0) null else java.lang.Double.valueOf(rng.nextDouble())
      Array[Any](k, s, d)
    }

    singleKeyLong = SortComparatorBench.comparatorFor(
      Array((0, true)))                  // ORDER BY col0 ASC
    multiKeyMixed = SortComparatorBench.comparatorFor(
      Array((0, true), (1, false), (2, true))) // col0 ASC, col1 DESC, col2 ASC
    multiKeyNullsLast = SortComparatorBench.comparatorFor(
      Array((0, true), (2, true)))               // col0 ASC, col2 ASC over null-bearing rows
  }

  /** ORDER BY col0 ASC over Long-only keys. The hottest single-key
    * path in the engine — comparator dominates time. */
  @Benchmark
  def singleKeyLongCompare(bh: Blackhole): Unit = {
    val n = BatchRows
    var i = 0
    while (i < n - 1) {
      bh.consume(singleKeyLong.compare(rows(i), rows(i + 1)))
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
      bh.consume(multiKeyMixed.compare(rows(i), rows(i + 1)))
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
      bh.consume(multiKeyNullsLast.compare(rowsWithNulls(i), rowsWithNulls(i + 1)))
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

  /** Build a comparator equivalent to `SortExec.rowOrdering` for the
    * given (columnIndex, ascending) keys. Mirrors the SortExec
    * implementation so the benchmark sees the same NULL handling and
    * `Ops.cmp` dispatch the engine would. */
  def comparatorFor(keys: Array[(Int, Boolean)]): java.util.Comparator[Array[Any]] = {
    new java.util.Comparator[Array[Any]] {
      def compare(a: Array[Any], b: Array[Any]): Int = {
        var i = 0
        while (i < keys.length) {
          val (idx, asc) = keys(i)
          val va = a(idx)
          val vb = b(idx)
          val c =
            if (va == null && vb == null) 0
            else if (va == null) if (asc) -1 else 1
            else if (vb == null) if (asc) 1 else -1
            else if (asc) Ops.cmp(va, vb) else Ops.cmp(vb, va)
          if (c != 0) return c
          i += 1
        }
        0
      }
    }
  }
}
