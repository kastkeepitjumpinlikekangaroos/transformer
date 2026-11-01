package com.transformer.bench

import com.transformer.core._
import com.transformer.read.parquet.ParquetReader
import com.transformer.write.parquet.ParquetWriter
import org.junit.Assert._
import org.junit.Test

import java.nio.file.Files

/** What one small spill file costs to write and read back, warm: a few rows
  * written by [[ParquetWriter]] and read by [[ParquetReader]], as every
  * spilling operator does once per flush. It was 12 ms, almost none of it the
  * data: Hadoop's local file system ran `chmod` in a process of its own for
  * each file written, and parquet-mr built a fresh Hadoop `Configuration`
  * for each file opened, twice a read (plans/testing/05, W8). The bound is a
  * fifth of that, far above the half millisecond it costs since, so a
  * regression to either fails it on any machine. Tagged `perf`. */
class SpillFileCostTest {

  @Test def aSmallSpillFileCostsUnderTwoMilliseconds(): Unit = {
    val schema = Spill.positionalSchema(Schema(Vector(Field("a", DataType.IntType), Field("s", DataType.StringType))))
    val dir = Files.createTempDirectory("spill-cost")
    def batch(): ColumnarBatch = {
      val b = new ColumnarBatch(schema, 4)
      (0 until 4).foreach { r => b.column(0).setBoxed(r, r); b.column(1).setBoxed(r, s"v$r") }
      b.setNumRows(4)
      b
    }
    def roundTrip(n: Int, tag: String): Double = {
      val t0 = System.nanoTime()
      (0 until n).foreach { i =>
        val f = dir.resolve(s"$tag-$i.parquet")
        ParquetWriter.writeAll(f, schema, Iterator.single(batch()))
        val r = ParquetReader.fromPath(f.toString)
        (0 until r.numPartitions).foreach(p => r.readPartition(p).foreach(_ => ()))
      }
      (System.nanoTime() - t0) / 1e6 / n
    }
    roundTrip(100, "warmup")
    val ms = roundTrip(500, "measured")
    println(f"[spill-file-cost] $ms%.2f ms a file, written and read back")
    assertTrue(f"a small spill file costs $ms%.2f ms to write and read back", ms < 2.0)
  }
}
