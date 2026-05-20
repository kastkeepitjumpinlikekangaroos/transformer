package com.transformer.bench

import com.transformer.core._
import com.transformer.read.parquet.ParquetReader
import com.transformer.write.parquet.ParquetWriter

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** K-way merge benchmark over `K` sorted spilled parquet runs.
  *
  * The benchmark approximates plan 09 Phase 3's external merge-sort
  * merge phase: at flush time SortExec writes K sorted runs to disk
  * (via parquet); at finish time it does a K-way heap-merge of those
  * runs, emitting one row at a time in sorted order until every run
  * is drained.
  *
  * `SortExec`'s exact merge entry point is `private[exec]` (see
  * `SortExec.scala` — `DiskRunCursor`, `MergeHeap`). Per the Sub-plan
  * 3 escalation, the benchmark drops down to a minimal direct
  * heap-merge via [[java.util.PriorityQueue]] over the parquet rows
  * we wrote at `@Setup`, rather than open the visibility of SortExec
  * internals. The merge shape (K cursors, primitive Long key compare,
  * emit min, advance min) is the same — what differs is the comparator
  * and the row representation, both of which are dominated by the
  * heap-pop/sift cost the production code pays too.
  *
  * # Parameters
  *
  *   - `K` — number of runs. 2 / 8 / 32 covers the small / medium /
  *     large-run regimes spill operators actually produce.
  *   - `N` — total rows across all runs. 1000 / 10000 covers a single
  *     small spill (e.g. 1k rows) and a multi-flush large spill
  *     (10k rows × 32 runs = real ExternalMerge territory).
  *
  * Each `@Setup(Trial)` writes K sorted parquet files into a temp
  * directory; `@TearDown(Trial)` deletes them.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 2, timeUnit = TimeUnit.SECONDS)
class ExternalSortMergeBench {

  @Param(Array("2", "8", "32"))
  var K: Int = _

  @Param(Array("1000", "10000"))
  var N: Int = _

  private var tempDir: Path = _
  private var runPaths: Array[Path] = _
  private final val Schema_ : Schema = Schema(Vector(
    Field("k", DataType.LongType),
    Field("v", DataType.DoubleType)))

  @Setup(Level.Trial)
  def writeRuns(): Unit = {
    tempDir = Files.createTempDirectory("bench-sortmerge-")
    runPaths = new Array[Path](K)
    val perRun = math.max(1, N / K)
    val rng = new java.util.SplittableRandom(0xF00DDEEDL)
    var r = 0
    while (r < K) {
      // Generate a sorted run by drawing perRun random longs and sorting.
      // Runs are independently random but each is internally sorted —
      // matches the shape SortExec produces (each in-memory chunk is
      // sorted by Arrays.sort before the writer flushes it).
      val keys = new Array[Long](perRun)
      val values = new Array[Double](perRun)
      var i = 0
      while (i < perRun) {
        keys(i) = rng.nextLong()
        values(i) = rng.nextDouble()
        i += 1
      }
      java.util.Arrays.sort(keys)
      val path = tempDir.resolve(s"run-$r.parquet")
      runPaths(r) = path
      val batches: Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] {
        private final val Cap = ColumnarBatch.DefaultCapacity
        private var emitted = 0
        def hasNext: Boolean = emitted < perRun
        def next(): ColumnarBatch = {
          val take = math.min(Cap, perRun - emitted)
          val out = new ColumnarBatch(Schema_, math.max(1, take))
          val kCol = out.column(0).asInstanceOf[LongVector]
          val vCol = out.column(1).asInstanceOf[DoubleVector]
          var j = 0
          while (j < take) {
            kCol.set(j, keys(emitted + j))
            vCol.set(j, values(emitted + j))
            j += 1
          }
          out.setNumRows(take)
          emitted += take
          out
        }
      }
      ParquetWriter.writeAll(path, Schema_, batches)
      r += 1
    }
  }

  @TearDown(Level.Trial)
  def cleanup(): Unit = {
    if (tempDir != null) {
      try BenchFixtures.deleteRecursively(tempDir) catch { case _: Throwable => () }
    }
  }

  /** Time the merge phase only: open all K runs, drain them through a
    * heap that pops the minimum (Long key) row each step. Returns the
    * row count via the Blackhole so the JIT can't DCE the loop. */
  @Benchmark
  def mergeKRuns(bh: Blackhole): Unit = {
    val cursors = openCursors()
    // PriorityQueue ordered by the Long key. We allocate the comparator
    // once outside the loop; the per-element cost is one Long compare.
    val pq = new java.util.PriorityQueue[ExternalSortMergeBench.Cursor](
      math.max(1, cursors.length),
      ExternalSortMergeBench.CursorOrder)
    var i = 0
    while (i < cursors.length) {
      val c = cursors(i)
      if (c.advance()) pq.add(c)
      i += 1
    }
    var emitted = 0L
    while (!pq.isEmpty) {
      val head = pq.poll()
      // Consume key + value to keep the load in the live set.
      bh.consume(head.key)
      bh.consume(head.value)
      emitted += 1
      if (head.advance()) pq.add(head)
    }
    bh.consume(emitted)
  }

  /** Open one [[ExternalSortMergeBench.Cursor]] per run path. Each
    * cursor pulls batches from the underlying [[ParquetReader]]
    * partition iterator and exposes a row-by-row `advance()` over them. */
  private def openCursors(): Array[ExternalSortMergeBench.Cursor] = {
    val out = new Array[ExternalSortMergeBench.Cursor](runPaths.length)
    var i = 0
    while (i < runPaths.length) {
      val reader = ParquetReader.fromPath(runPaths(i).toString)
      // Each run was written as a single-partition file. Take partition 0.
      val it = reader.readPartition(0)
      out(i) = new ExternalSortMergeBench.Cursor(it)
      i += 1
    }
    out
  }
}

private[bench] object ExternalSortMergeBench {
  /** Per-run row cursor: holds the active batch + current row index.
    * `advance()` returns false when the run is exhausted. Defined on
    * the companion object so the comparator below can name it as a
    * non-path-dependent type. */
  final class Cursor(it: Iterator[ColumnarBatch]) {
    private var current: ColumnarBatch = _
    private var currentKey: LongVector = _
    private var currentValue: DoubleVector = _
    private var row: Int = 0
    var key: Long = 0L
    var value: Double = 0.0

    /** Pull the next row into `key` / `value`, returning false if the
      * cursor is drained. */
    def advance(): Boolean = {
      if (current == null || row >= current.numRows) {
        if (!it.hasNext) return false
        current = it.next()
        currentKey = current.column(0).asInstanceOf[LongVector]
        currentValue = current.column(1).asInstanceOf[DoubleVector]
        row = 0
        if (current.numRows == 0) return advance()
      }
      key = currentKey.values(row)
      value = currentValue.values(row)
      row += 1
      true
    }
  }

  /** Comparator over [[Cursor]] by `.key`. Built once and reused across
    * benchmark invocations. */
  val CursorOrder: java.util.Comparator[Cursor] =
    new java.util.Comparator[Cursor] {
      def compare(a: Cursor, b: Cursor): Int =
        java.lang.Long.compare(a.key, b.key)
    }
}
