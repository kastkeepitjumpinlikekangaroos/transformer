package com.transformer.bench

import com.transformer.core._

import java.nio.file.{Files, Path}

/** Shared fixture builders for the JMH microbenchmarks. Centralised here so
  * a SortComparatorBench, an ExprEvalBench, and an AggStateSerdeBench all
  * build their `ColumnarBatch` inputs the same way — keeping the bench
  * surface comparable run-over-run.
  *
  * Every method here is deterministic given the same `seed`. JMH's
  * benchmark machinery shares no state across forks; each `@Setup`
  * rebuilds whatever it needs by calling these builders with a fixed seed.
  */
private[bench] object BenchFixtures {

  /** Recursively delete `p`. No-op when `p` doesn't exist or any nested
    * `Files.delete` throws — bench cleanup is best-effort, the temp
    * directory belongs to the fork and a leftover doesn't break anything
    * other than disk usage. */
  def deleteRecursively(p: Path): Unit = {
    if (!Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val stream = Files.list(p)
      try {
        val it = stream.iterator()
        while (it.hasNext) deleteRecursively(it.next())
      } finally stream.close()
    }
    try Files.deleteIfExists(p) catch { case _: Throwable => () }
    ()
  }

  /** Build a `ColumnarBatch` of `numRows` rows whose `key` column is a
    * dense Long, the `value` column is a Double, and the `flag` column
    * is a Boolean. Useful for KeyCodec / SortComparator / Spill micros.
    * Nulls are sprinkled at ~5% across both nullable columns to exercise
    * the null-handling branches without dominating timings. */
  def fixedWidthBatch(numRows: Int, seed: Long): ColumnarBatch = {
    val schema = Schema(Vector(
      Field("k", DataType.LongType),
      Field("v", DataType.DoubleType),
      Field("flag", DataType.BooleanType)))
    val batch = new ColumnarBatch(schema, math.max(1, numRows))
    val kCol = batch.column(0).asInstanceOf[LongVector]
    val vCol = batch.column(1).asInstanceOf[DoubleVector]
    val fCol = batch.column(2).asInstanceOf[BooleanVector]
    val rng = new java.util.SplittableRandom(seed)
    var i = 0
    while (i < numRows) {
      val k = rng.nextLong()
      val v = rng.nextDouble()
      val flag = (k & 1L) == 0L
      // 5% nulls in v, no nulls in k (key columns), 2% nulls in flag.
      if ((k & 0xff) < 0x0d) vCol.setNull(i) else vCol.set(i, v)
      if ((k & 0xff) >= 0xfb) fCol.setNull(i) else fCol.set(i, flag)
      kCol.set(i, k)
      i += 1
    }
    batch.setNumRows(numRows)
    batch
  }

  /** Variant where every key column is a String — exercises the
    * ObjectArrayCodec / variable-width path that KeyCodecBench compares
    * against the packed Long path. */
  def variableWidthBatch(numRows: Int, seed: Long): ColumnarBatch = {
    val schema = Schema(Vector(
      Field("k1", DataType.StringType),
      Field("k2", DataType.StringType),
      Field("v", DataType.DoubleType)))
    val batch = new ColumnarBatch(schema, math.max(1, numRows))
    val k1 = batch.column(0).asInstanceOf[StringVector]
    val k2 = batch.column(1).asInstanceOf[StringVector]
    val vCol = batch.column(2).asInstanceOf[DoubleVector]
    val rng = new java.util.SplittableRandom(seed)
    // A small vocabulary so the codec sees realistic hash-table reuse.
    val vocab1 = Array("alpha", "bravo", "charlie", "delta", "echo", "foxtrot",
      "golf", "hotel", "india", "juliet", "kilo", "lima")
    val vocab2 = Array("us", "uk", "fr", "de", "jp", "br", "in", "cn", "au", "ca")
    var i = 0
    while (i < numRows) {
      val a = vocab1(rng.nextInt(vocab1.length))
      val b = vocab2(rng.nextInt(vocab2.length))
      k1.set(i, a)
      k2.set(i, b)
      vCol.set(i, rng.nextDouble())
      i += 1
    }
    batch.setNumRows(numRows)
    batch
  }

  /** Two-Long column batch where the second column is the join-side
    * payload (typical: `ts`, `value` paired with a hash key in a probe
    * benchmark). */
  def twoLongBatch(numRows: Int, seed: Long): ColumnarBatch = {
    val schema = Schema(Vector(
      Field("k", DataType.LongType),
      Field("v", DataType.LongType)))
    val batch = new ColumnarBatch(schema, math.max(1, numRows))
    val kCol = batch.column(0).asInstanceOf[LongVector]
    val vCol = batch.column(1).asInstanceOf[LongVector]
    val rng = new java.util.SplittableRandom(seed)
    var i = 0
    while (i < numRows) {
      kCol.set(i, rng.nextLong())
      vCol.set(i, rng.nextLong())
      i += 1
    }
    batch.setNumRows(numRows)
    batch
  }
}
