package com.transformer.bench

import com.transformer.core._
import com.transformer.read.parquet.ParquetReader
import com.transformer.write.parquet.ParquetWriter

import org.openjdk.jmh.annotations._
import org.openjdk.jmh.infra.Blackhole

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

/** Parquet decode microbenchmark. Writes a 1M-row fixture to a temp
  * directory at trial setup, then measures the per-batch decode cost of
  * reading it back through [[ParquetReader]] / [[ParquetPartitionIterator]].
  *
  * Schema is intentionally narrow (one Long + one Double + one String)
  * so each batch's decode work is dominated by parquet-mr's column
  * readers and our [[ColumnarBatch]] copy-out — not by NULL handling or
  * decimal precision branches. Use this benchmark as a regression
  * watchpoint when the parquet path changes (e.g. row-group skipping,
  * the BatchWriteSupport / decoder fast-paths).
  *
  * The fixture is hermetic: the temp directory is created at
  * `@Setup(Level.Trial)`, deleted at `@TearDown(Level.Trial)`. JMH's
  * fork lifecycle ensures the directory is owned by the running fork
  * and reclaimed when it exits.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(1)
@Warmup(iterations = 2, time = 2, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 3, time = 3, timeUnit = TimeUnit.SECONDS)
class ParquetDecodeBench {

  private final val FixtureRows: Int = 1_000_000

  private var tempDir: Path = _
  private var parquetPath: Path = _

  @Setup(Level.Trial)
  def writeFixture(): Unit = {
    tempDir = Files.createTempDirectory("transformer-bench-parquet-")
    parquetPath = tempDir.resolve("fixture.parquet")

    val schema = Schema(Vector(
      Field("id", DataType.LongType),
      Field("value", DataType.DoubleType),
      Field("name", DataType.StringType)))

    val capacity = ColumnarBatch.DefaultCapacity
    val rng = new java.util.SplittableRandom(0xFEEDFACEL)
    val batches: Iterator[ColumnarBatch] = new Iterator[ColumnarBatch] {
      private var produced = 0
      def hasNext: Boolean = produced < FixtureRows
      def next(): ColumnarBatch = {
        val take = math.min(capacity, FixtureRows - produced)
        val out = new ColumnarBatch(schema, math.max(1, take))
        val idCol = out.column(0).asInstanceOf[LongVector]
        val valCol = out.column(1).asInstanceOf[DoubleVector]
        val nameCol = out.column(2).asInstanceOf[StringVector]
        var r = 0
        while (r < take) {
          idCol.set(r, (produced + r).toLong)
          valCol.set(r, rng.nextDouble())
          nameCol.set(r, ParquetDecodeBench.Vocab(rng.nextInt(ParquetDecodeBench.Vocab.length)))
          r += 1
        }
        out.setNumRows(take)
        produced += take
        out
      }
    }
    ParquetWriter.writeAll(parquetPath, schema, batches)
  }

  @TearDown(Level.Trial)
  def cleanup(): Unit = {
    if (tempDir != null) {
      try BenchFixtures.deleteRecursively(tempDir) catch { case _: Throwable => () }
    }
  }

  /** Read every partition of the fixture file end to end and count the
    * rows. Measures parquet-mr's per-batch decode + our copy into
    * ColumnVectors. */
  @Benchmark
  def decodeFullScan(bh: Blackhole): Unit = {
    val reader = ParquetReader.fromPath(parquetPath.toString)
    var total = 0L
    var p = 0
    while (p < reader.numPartitions) {
      val it = reader.readPartition(p)
      while (it.hasNext) {
        val batch = it.next()
        total += batch.numRows
        bh.consume(batch)
      }
      p += 1
    }
    bh.consume(total)
  }

  /** Variant that projects only the `id` Long column. Exercises the
    * `withProjectedColumns` path. The decode cost should be roughly 1/3
    * of `decodeFullScan` for a 3-column schema. */
  @Benchmark
  def decodeProjectedLong(bh: Blackhole): Unit = {
    val reader = ParquetReader.fromPath(parquetPath.toString)
    val projected = reader.withProjectedColumns(Seq("id")).getOrElse(reader)
    var total = 0L
    var p = 0
    while (p < projected.numPartitions) {
      val it = projected.readPartition(p)
      while (it.hasNext) {
        val batch = it.next()
        total += batch.numRows
        bh.consume(batch)
      }
      p += 1
    }
    bh.consume(total)
  }
}

private[bench] object ParquetDecodeBench {
  /** Small vocabulary so the StringVector column compresses well
    * (parquet dictionary encoding). Realistic for category-like
    * dimensions. */
  val Vocab: Array[String] = Array(
    "alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf",
    "hotel", "india", "juliet", "kilo", "lima", "mike", "november",
    "oscar", "papa", "quebec", "romeo", "sierra", "tango")
}
