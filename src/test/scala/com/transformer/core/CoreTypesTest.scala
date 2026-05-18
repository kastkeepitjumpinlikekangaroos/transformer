package com.transformer.core

import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger

class CoreTypesTest {

  @Test def schemaIndexOfIsCaseInsensitive(): Unit = {
    val s = Schema(Field("Id", DataType.IntType), Field("Name", DataType.StringType))
    assertEquals(0, s.indexOf("id"))
    assertEquals(1, s.indexOf("NAME"))
    assertEquals(-1, s.indexOf("missing"))
  }

  @Test def schemaSelectReorders(): Unit = {
    val s = Schema(Field("a", DataType.IntType), Field("b", DataType.StringType), Field("c", DataType.DoubleType))
    val sub = s.select(Seq("c", "a"))
    assertEquals(Vector("c", "a"), sub.fieldNames)
  }

  @Test def widerNumericPicksWiderRank(): Unit = {
    assertEquals(DataType.DoubleType, DataType.widerNumeric(DataType.IntType, DataType.DoubleType))
    assertEquals(DataType.LongType, DataType.widerNumeric(DataType.LongType, DataType.IntType))
    assertEquals(DataType.FloatType, DataType.widerNumeric(DataType.FloatType, DataType.IntType))
  }

  @Test def intVectorNullsRoundtrip(): Unit = {
    val s = Schema(Field("x", DataType.IntType))
    val b = new ColumnarBatch(s, 4)
    val v = b.column(0).asInstanceOf[IntVector]
    v.set(0, 7); v.setNull(1); v.set(2, -3); v.set(3, Int.MaxValue)
    b.setNumRows(4)
    assertEquals(7, v.get(0))
    assertTrue(v.isNull(1))
    assertEquals(-3, v.get(2))
    assertEquals(Int.MaxValue, v.get(3))
    assertNull(b.rowAt(1)(0))
    assertEquals(java.lang.Integer.valueOf(7), b.rowAt(0)(0))
  }

  @Test def stringVectorNullsUseInlineNull(): Unit = {
    val s = Schema(Field("name", DataType.StringType))
    val b = new ColumnarBatch(s, 3)
    val v = b.column(0).asInstanceOf[StringVector]
    v.set(0, "alice"); v.setNull(1); v.set(2, "")
    b.setNumRows(3)
    assertEquals("alice", v.get(0))
    assertTrue(v.isNull(1))
    assertFalse(v.isNull(2))
    assertEquals("", v.get(2))
  }

  @Test def selectFiltersRows(): Unit = {
    val s = Schema(Field("x", DataType.IntType), Field("s", DataType.StringType))
    val b = new ColumnarBatch(s, 4)
    val xs = b.column(0).asInstanceOf[IntVector]
    val ss = b.column(1).asInstanceOf[StringVector]
    xs.set(0, 1); ss.set(0, "a")
    xs.set(1, 2); ss.set(1, "b")
    xs.setNull(2); ss.set(2, "c")
    xs.set(3, 4); ss.setNull(3)
    b.setNumRows(4)

    val out = b.select(Array(true, false, true, true))
    assertEquals(3, out.numRows)
    val ox = out.column(0).asInstanceOf[IntVector]
    val os = out.column(1).asInstanceOf[StringVector]
    assertEquals(1, ox.get(0)); assertEquals("a", os.get(0))
    assertTrue(ox.isNull(1));   assertEquals("c", os.get(1))
    assertEquals(4, ox.get(2)); assertTrue(os.isNull(2))
  }

  @Test def catalogIsCaseInsensitive(): Unit = {
    val cat = new Catalog
    cat.register("MyView", new CatalogView {
      def schema: Schema = Schema(Field("x", DataType.IntType))
      def numPartitions: Int = 1
      def readPartition(p: Int): Iterator[ColumnarBatch] = Iterator.empty
    })
    assertTrue(cat.get("myview").isDefined)
    assertTrue(cat.get("MYVIEW").isDefined)
    assertTrue(cat.get("missing").isEmpty)
  }

  @Test def catalogRejectsDuplicateRegister(): Unit = {
    val cat = new Catalog
    val v = new CatalogView {
      def schema: Schema = Schema.empty
      def numPartitions: Int = 0
      def readPartition(p: Int): Iterator[ColumnarBatch] = Iterator.empty
    }
    cat.register("x", v)
    try { cat.register("X", v); fail("expected duplicate-register failure") }
    catch { case _: IllegalStateException => () }
  }

  @Test def dateVectorBoxesAsLocalDate(): Unit = {
    val s = Schema(Field("d", DataType.DateType))
    val b = new ColumnarBatch(s, 2)
    val v = b.column(0).asInstanceOf[DateVector]
    v.setBoxed(0, "2026-01-01")
    v.setNull(1)
    b.setNumRows(2)
    val boxed = v.getBoxed(0).asInstanceOf[java.time.LocalDate]
    assertEquals(java.time.LocalDate.of(2026, 1, 1), boxed)
    assertTrue(v.isNull(1))
  }

  @Test def timestampVectorRoundtripsMicros(): Unit = {
    val s = Schema(Field("ts", DataType.TimestampType))
    val b = new ColumnarBatch(s, 1)
    val v = b.column(0).asInstanceOf[TimestampVector]
    val instant = java.time.Instant.parse("2026-01-01T05:30:21Z")
    v.setBoxed(0, instant)
    b.setNumRows(1)
    assertEquals(instant, v.getBoxed(0))
  }

  // --- MaterializedView -------------------------------------------------

  private def intBatch(schema: Schema, vals: Seq[Int]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, math.max(1, vals.size))
    val col = b.column(0).asInstanceOf[IntVector]
    vals.zipWithIndex.foreach { case (v, i) => col.set(i, v) }
    b.setNumRows(vals.size)
    b
  }

  /** Single-use partition iterator backed by a fixed batch list. Tracks
    * `partitionReads` so tests can assert each partition is read exactly once
    * during materialization. */
  private class CountingView(val schema: Schema, perPartition: IndexedSeq[IndexedSeq[ColumnarBatch]])
      extends CatalogView {
    val partitionReads = new AtomicInteger(0)
    val consumed = new Array[Boolean](perPartition.length)
    def numPartitions: Int = perPartition.length
    def readPartition(p: Int): Iterator[ColumnarBatch] = {
      synchronized {
        if (consumed(p))
          throw new IllegalStateException(s"partition $p already consumed (CatalogViews are single-use here)")
        consumed(p) = true
      }
      partitionReads.incrementAndGet()
      perPartition(p).iterator
    }
  }

  @Test def materializedViewPreservesSchemaAndPartitionLayout(): Unit = {
    val schema = Schema(Field("x", DataType.IntType))
    val src = new CountingView(schema, IndexedSeq(
      IndexedSeq(intBatch(schema, Seq(1, 2)), intBatch(schema, Seq(3))),
      IndexedSeq(intBatch(schema, Seq(4, 5, 6)))
    ))
    val pool = Executors.newFixedThreadPool(2)
    try {
      val mv = MaterializedView.materializeInParallel(src, pool)
      assertEquals(schema, mv.schema)
      assertEquals(2, mv.numPartitions)
      assertEquals(2, src.partitionReads.get())
      assertEquals(6L, mv.totalRows)

      def values(p: Int): Seq[Int] = {
        val it = mv.readPartition(p)
        val out = scala.collection.mutable.ArrayBuffer.empty[Int]
        while (it.hasNext) {
          val b = it.next()
          val col = b.column(0).asInstanceOf[IntVector]
          var r = 0
          while (r < b.numRows) { out += col.get(r); r += 1 }
        }
        out.toSeq
      }
      assertEquals(Seq(1, 2, 3), values(0))
      assertEquals(Seq(4, 5, 6), values(1))
    } finally {
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test def materializedViewIsReIterable(): Unit = {
    val schema = Schema(Field("x", DataType.IntType))
    val src = new CountingView(schema, IndexedSeq(IndexedSeq(intBatch(schema, Seq(7, 8, 9)))))
    val pool = Executors.newFixedThreadPool(1)
    try {
      val mv = MaterializedView.materializeInParallel(src, pool)
      // Re-reading should not throw and should yield the same rows.
      def take(): Vector[Int] = {
        val it = mv.readPartition(0)
        val out = scala.collection.mutable.ArrayBuffer.empty[Int]
        while (it.hasNext) {
          val b = it.next()
          val col = b.column(0).asInstanceOf[IntVector]
          var r = 0
          while (r < b.numRows) { out += col.get(r); r += 1 }
        }
        out.toVector
      }
      assertEquals(Vector(7, 8, 9), take())
      assertEquals(Vector(7, 8, 9), take())
      assertEquals(Vector(7, 8, 9), take())
      assertEquals(1, src.partitionReads.get()) // source only read once
    } finally {
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test def materializeManyInParallelActuallyParallelizesAcrossViewsAndPartitions(): Unit = {
    // 3 views, each with 2 partitions. Each partition read blocks until at
    // least N other partition reads are in flight, where N = total - 1.
    // If reads were sequential, the latch would never count down enough.
    val schema = Schema(Field("x", DataType.IntType))
    val totalPartitions = 6
    val inFlight = new AtomicInteger(0)
    val peakInFlight = new AtomicInteger(0)
    val allInFlight = new CountDownLatch(totalPartitions)

    class BlockingView(val schema: Schema, parts: Int) extends CatalogView {
      def numPartitions: Int = parts
      def readPartition(p: Int): Iterator[ColumnarBatch] = {
        val now = inFlight.incrementAndGet()
        // Update peak.
        var observed = peakInFlight.get()
        while (now > observed && !peakInFlight.compareAndSet(observed, now)) observed = peakInFlight.get()
        allInFlight.countDown()
        try {
          // Wait for every other partition reader to start. This is the proof
          // of concurrency: serial execution would deadlock here.
          if (!allInFlight.await(5, TimeUnit.SECONDS))
            throw new AssertionError(s"timed out waiting for all $totalPartitions reads to start (inFlight=${inFlight.get})")
          Iterator.single(intBatch(schema, Seq(p)))
        } finally {
          inFlight.decrementAndGet()
        }
      }
    }

    val views = Seq.fill(3)(new BlockingView(schema, 2))
    val pool = Executors.newFixedThreadPool(totalPartitions)
    try {
      val mvs = MaterializedView.materializeManyInParallel(views, pool)
      assertEquals(3, mvs.length)
      mvs.foreach(mv => assertEquals(2, mv.numPartitions))
      assertEquals(s"expected $totalPartitions concurrent reads, peaked at ${peakInFlight.get}",
        totalPartitions, peakInFlight.get())
    } finally {
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }

  @Test def materializeManyInParallelHandlesEmptyView(): Unit = {
    val schema = Schema(Field("x", DataType.IntType))
    val empty = new CatalogView {
      def schema: Schema = Schema(Field("x", DataType.IntType))
      def numPartitions: Int = 0
      def readPartition(p: Int): Iterator[ColumnarBatch] =
        throw new IllegalStateException("should not be called")
    }
    val pool = Executors.newFixedThreadPool(2)
    try {
      val mvs = MaterializedView.materializeManyInParallel(Seq(empty), pool)
      assertEquals(1, mvs.length)
      assertEquals(0, mvs.head.numPartitions)
      assertEquals(0L, mvs.head.totalRows)
    } finally {
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.SECONDS)
    }
  }
}
