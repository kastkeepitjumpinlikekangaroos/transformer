package com.transformer.core

import org.junit.Assert._
import org.junit.Assume.assumeTrue
import org.junit.Test

import java.util.concurrent.{CountDownLatch, TimeUnit}
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

  /** A run record writes a column's type by its name; every type reads back
    * from it, and a name no type has reads as none. */
  @Test def dataTypeNamesReadBack(): Unit = {
    val types = Seq(DataType.StringType, DataType.IntType, DataType.LongType, DataType.FloatType,
      DataType.DoubleType, DataType.BooleanType, DataType.DateType, DataType.TimestampType, DataType.BinaryType,
      DataType.NullType, DataType.DecimalType(10, 2), DataType.DecimalType(38, 0))
    types.foreach(t => assertEquals(t.name, Some(t), DataType.fromName(t.name)))
    Seq("bigint", "decimal(10)", "decimal(1,2,3)", "LONG", "").foreach(n =>
      assertEquals(n, None, DataType.fromName(n)))
  }

  /** The wider type, but an INT or BIGINT beside a FLOAT is a DOUBLE, which
    * holds every INT (plans/testing/10, decision 2). */
  @Test def widerNumericPicksWiderRank(): Unit = {
    assertEquals(DataType.DoubleType, DataType.widerNumeric(DataType.IntType, DataType.DoubleType))
    assertEquals(DataType.LongType, DataType.widerNumeric(DataType.LongType, DataType.IntType))
    assertEquals(DataType.FloatType, DataType.widerNumeric(DataType.FloatType, DataType.FloatType))
    assertEquals(DataType.DoubleType, DataType.widerNumeric(DataType.FloatType, DataType.DoubleType))
    for (i <- Seq(DataType.IntType, DataType.LongType)) {
      assertEquals(DataType.DoubleType, DataType.widerNumeric(DataType.FloatType, i))
      assertEquals(DataType.DoubleType, DataType.widerNumeric(i, DataType.FloatType))
    }
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

  /** `gather` writes each selected source row, NULL for a negative index or a
    * NULL source value, into consecutive rows from its offset, a column at a
    * time, and leaves every other row as it was: for every type, NULLs,
    * `NullType`, `DECIMAL` and `BINARY` among them. A source of another class
    * is copied as `copyTo` copies a cell. */
  @Test def gatherWritesTheSelectedRowsOfEveryType(): Unit = {
    def value(t: DataType, i: Int): Any = t match {
      case DataType.IntType => i * 7
      case DataType.LongType => i * 1000000007L
      case DataType.FloatType => i + 0.5f
      case DataType.DoubleType => i - 0.25
      case DataType.BooleanType => i % 2 == 0
      case DataType.StringType => s"s$i"
      case DataType.BinaryType => Array[Byte](i.toByte, 7)
      case DataType.DateType => java.time.LocalDate.of(2020, 1, 1).plusDays(i.toLong)
      case DataType.TimestampType => java.time.Instant.ofEpochSecond(1700000000L + i)
      case _: DataType.DecimalType => new java.math.BigDecimal(s"$i.25")
      case DataType.NullType => null
    }
    def fill(t: DataType, n: Int, from: Int, nulls: Set[Int]): ColumnVector = {
      val v = ColumnVector.allocate(t, n)
      (0 until n).foreach(r => if (nulls(r)) v.setNull(r) else v.setBoxed(r, value(t, from + r)))
      v
    }
    def same(a: Any, b: Any): Boolean = (a, b) match {
      case (x: Array[Byte], y: Array[Byte]) => java.util.Arrays.equals(x, y)
      case _ => a == b
    }
    val types = Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType, DataType.BooleanType,
      DataType.StringType, DataType.BinaryType, DataType.DateType, DataType.TimestampType, DataType.DecimalType(10, 2),
      DataType.NullType)
    val sel = Array(4, 3, -1, 0, 5, 3, 1)
    types.foreach { t =>
      val src = fill(t, 6, 0, nulls = Set(1, 5))
      val dst = fill(t, 9, 100, nulls = Set(0, 8))
      val before = (0 until 9).map(dst.getBoxed)
      // Rows 1 to 5 of the selection, written from row 2.
      dst.gather(src, sel, 1, 6, 2)
      (0 until 9).foreach { r =>
        val expected =
          if (r >= 2 && r < 7) { val i = sel(r - 1); if (i < 0) null else src.getBoxed(i) }
          else before(r)
        assertTrue(s"$t row $r: ${dst.getBoxed(r)}, expected $expected", same(dst.getBoxed(r), expected))
        assertEquals(s"$t row $r null", expected == null, dst.isNull(r))
      }
    }
    // A source of another class, copied cell by cell.
    val ints = fill(DataType.IntType, 6, 0, nulls = Set(2))
    val longs = ColumnVector.allocate(DataType.LongType, 4)
    longs.gather(ints, Array(1, 2, -1, 0), 0, 4, 0)
    assertEquals(Seq[Any](7L, null, null, 0L), (0 until 4).map(longs.getBoxed))
  }

  private val EveryType: Seq[DataType] = Seq(DataType.IntType, DataType.LongType, DataType.FloatType,
    DataType.DoubleType, DataType.BooleanType, DataType.StringType, DataType.BinaryType, DataType.DateType,
    DataType.TimestampType, DataType.DecimalType(10, 2), DataType.NullType)

  /** Row `i`'s value of type `t`, distinct for every `i`. */
  private def valueOf(t: DataType, i: Int): Any = t match {
    case DataType.IntType => i * 7
    case DataType.LongType => i * 1000000007L
    case DataType.FloatType => i + 0.5f
    case DataType.DoubleType => i - 0.25
    case DataType.BooleanType => i % 2 == 0
    case DataType.StringType => s"s$i"
    case DataType.BinaryType => Array[Byte](i.toByte, 7)
    case DataType.DateType => java.time.LocalDate.of(2020, 1, 1).plusDays(i.toLong)
    case DataType.TimestampType => java.time.Instant.ofEpochSecond(1700000000L + i)
    case _: DataType.DecimalType => new java.math.BigDecimal(s"$i.25")
    case DataType.NullType => null
  }

  /** `n` rows of `t`, row `r` holding `valueOf(t, from + r)` or NULL. */
  private def filled(t: DataType, n: Int, from: Int, nulls: Int => Boolean): ColumnVector = {
    val v = ColumnVector.allocate(t, n)
    (0 until n).foreach(r => if (nulls(r)) v.setNull(r) else v.setBoxed(r, valueOf(t, from + r)))
    v
  }

  private def sameValue(a: Any, b: Any): Boolean = (a, b) match {
    case (x: Array[Byte], y: Array[Byte]) => java.util.Arrays.equals(x, y)
    case _ => a == b
  }

  /** `copyRange` writes a run of source rows into consecutive rows from its
    * offset, a column at a time, NULLs where the source holds them (a NULL
    * bit cleared where it does not, one row past a byte or a word of the
    * mask included), and leaves every other row as it was: for every type,
    * `NullType`, `DECIMAL` and `BINARY` among them. A source of another class
    * is copied as `copyTo` copies a cell. */
  @Test def copyRangeWritesARunOfRowsOfEveryType(): Unit = {
    for (t <- EveryType; (from, until, at) <- Seq((0, 0, 0), (3, 4, 0), (0, 70, 0), (5, 69, 2), (1, 66, 70))) {
      val src = filled(t, 70, 0, r => r % 3 == 1 || r == 64)
      val dst = filled(t, 140, 1000, r => r % 2 == 0)
      val before = (0 until 140).map(dst.getBoxed)
      dst.copyRange(src, from, until, at)
      (0 until 140).foreach { r =>
        val expected = if (r >= at && r < at + until - from) src.getBoxed(from + r - at) else before(r)
        assertTrue(s"$t [$from, $until) at $at, row $r: ${dst.getBoxed(r)}, expected $expected",
          sameValue(dst.getBoxed(r), expected))
        assertEquals(s"$t row $r null", expected == null, dst.isNull(r))
      }
    }
    val ints = filled(DataType.IntType, 6, 0, _ == 2)
    val longs = ColumnVector.allocate(DataType.LongType, 4)
    longs.copyRange(ints, 1, 5, 0)
    assertEquals(Seq[Any](7L, null, 21L, 28L), (0 until 4).map(longs.getBoxed))
  }

  /** `gatherChunks` writes each selected row of a set of chunks, an index
    * naming its chunk in its high bits and its row in the `shift` low ones,
    * into consecutive rows from its offset, NULLs among them, and leaves every
    * other row as it was: for every type. A chunk of another class is copied
    * as `copyTo` copies a cell. */
  @Test def gatherChunksWritesTheSelectedRowsOfEveryType(): Unit = {
    val shift = 3
    // Three chunks of up to 8 rows: chunk c's row r holds value 10 * c + r.
    val sel = Array((2 << shift) | 1, (0 << shift) | 7, (1 << shift) | 0, (0 << shift) | 0, (2 << shift) | 5,
      (1 << shift) | 3, (0 << shift) | 2)
    for (t <- EveryType) {
      val chunks = Array.tabulate[ColumnVector](3)(c => filled(t, 8, 10 * c, r => (r + c) % 4 == 3))
      val dst = filled(t, 9, 100, _ == 8)
      val before = (0 until 9).map(dst.getBoxed)
      // Entries 1 to 5 of the selection, written from row 2.
      dst.gatherChunks(chunks, shift, sel, 1, 6, 2)
      (0 until 9).foreach { r =>
        val expected =
          if (r >= 2 && r < 7) { val e = sel(r - 1); chunks(e >>> shift).getBoxed(e & 7) }
          else before(r)
        assertTrue(s"$t row $r: ${dst.getBoxed(r)}, expected $expected", sameValue(dst.getBoxed(r), expected))
        assertEquals(s"$t row $r null", expected == null, dst.isNull(r))
      }
    }
    val ints = Array[ColumnVector](filled(DataType.IntType, 4, 0, _ == 1))
    val longs = ColumnVector.allocate(DataType.LongType, 3)
    longs.gatherChunks(ints, 2, Array(3, 1, 0), 0, 3, 0)
    assertEquals(Seq[Any](21L, null, 0L), (0 until 3).map(longs.getBoxed))
  }

  /** A typed getter reads a number or its text, and fails on any other
    * value as `getString` does, with a cast that names the column and the
    * value: its match had no case for one, so it threw a bare MatchError. */
  @Test def rowGettersNameAValueTheyCannotRead(): Unit = {
    val row = Row(Schema(Field("n", DataType.IntType), Field("flag", DataType.BooleanType), Field("z", DataType.IntType)),
      Array[Any](java.lang.Integer.valueOf(7), java.lang.Boolean.TRUE, null))
    assertEquals(7, row.getInt(0))
    assertEquals(7L, row.getLong(0))
    assertEquals(7.0, row.getDouble(0), 0.0)
    assertTrue(row.getBoolean(1))
    for ((getter, read) <- Seq[(String, Int => Any)](
        "getInt" -> row.getInt, "getLong" -> row.getLong, "getDouble" -> row.getDouble)) {
      val e = assertThrows(classOf[ClassCastException], () => read(1))
      assertEquals(s"Row.$getter: column 1 (flag) holds true, a java.lang.Boolean", e.getMessage)
    }
    val e = assertThrows(classOf[ClassCastException], () => row.getBoolean(0))
    assertEquals("Row.getBoolean: column 0 (n) holds 7, a java.lang.Integer", e.getMessage)
    val nul = assertThrows(classOf[ClassCastException], () => row.getInt(2))
    assertEquals("Row.getInt: column 2 (z) holds NULL", nul.getMessage)
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

  /** A date or an instant past what its vector stores is an error naming it,
    * where `toEpochDay.toInt` and `getEpochSecond * 1000000L` wrapped it round
    * to another (plans/robustness/25). The first and last of each fit, the
    * earliest microsecond too, whose product overflows before its fraction
    * brings it back. */
  @Test def aVectorRefusesADateOrInstantPastItsRange(): Unit = {
    val dates = new ColumnarBatch(Schema(Field("d", DataType.DateType)), 1).column(0)
    val times = new ColumnarBatch(Schema(Field("ts", DataType.TimestampType)), 1).column(0)
    val lastDay = java.time.LocalDate.ofEpochDay(Int.MaxValue)
    val firstMicro = java.time.Instant.EPOCH.plus(Long.MinValue, java.time.temporal.ChronoUnit.MICROS)
    val lastMicro = java.time.Instant.EPOCH.plus(Long.MaxValue, java.time.temporal.ChronoUnit.MICROS)
    for (d <- Seq(lastDay, java.time.LocalDate.ofEpochDay(Int.MinValue))) {
      dates.setBoxed(0, d)
      assertEquals(d, dates.getBoxed(0))
    }
    for (i <- Seq(firstMicro, lastMicro)) {
      times.setBoxed(0, i)
      assertEquals(i, times.getBoxed(0))
    }
    assertEquals(Long.MinValue, TimestampVector.epochMicros(firstMicro))
    def refused(body: => Unit): String =
      try { body; fail("accepted"); "" } catch { case e: IllegalArgumentException => e.getMessage }
    assertEquals(s"${lastDay.plusDays(1)} is past the range of a DATE, about 5.8 million years either side of 1970",
      refused(dates.setBoxed(0, lastDay.plusDays(1))))
    assertTrue(refused(dates.setBoxed(0, java.time.LocalDate.MAX)).startsWith("+999999999-12-31 is past the range of a DATE"))
    assertEquals(s"${lastMicro.plusNanos(1000)} is past the range of a TIMESTAMP, about 292,000 years either side of 1970",
      refused(times.setBoxed(0, lastMicro.plusNanos(1000))))
    assertTrue(refused(times.setBoxed(0, firstMicro.minusNanos(1000))).contains("is past the range of a TIMESTAMP"))
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
    val mv = MaterializedView.materializeInParallel(src)
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
  }

  @Test def materializedViewIsReIterable(): Unit = {
    val schema = Schema(Field("x", DataType.IntType))
    val src = new CountingView(schema, IndexedSeq(IndexedSeq(intBatch(schema, Seq(7, 8, 9)))))
    val mv = MaterializedView.materializeInParallel(src)
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
  }

  @Test def materializeManyInParallelActuallyParallelizesAcrossViewsAndPartitions(): Unit = {
    assumeTrue("requires a pool of 2 or more threads", Scheduler.parallelism >= 2)
    // 3 views, each with 2 partitions. Each partition read blocks until at
    // least N other partition reads are in flight. The shared Scheduler pool's
    // ForkJoinPool grows compensation threads when workers block on Future.get,
    // so this resolves even with parallelism = availableProcessors lower than
    // totalPartitions.
    val schema = Schema(Field("x", DataType.IntType))
    val totalPartitions = 6
    val inFlight = new AtomicInteger(0)
    val peakInFlight = new AtomicInteger(0)
    val allInFlight = new CountDownLatch(totalPartitions)

    class BlockingView(val schema: Schema, parts: Int) extends CatalogView {
      def numPartitions: Int = parts
      def readPartition(p: Int): Iterator[ColumnarBatch] = {
        val now = inFlight.incrementAndGet()
        var observed = peakInFlight.get()
        while (now > observed && !peakInFlight.compareAndSet(observed, now)) observed = peakInFlight.get()
        allInFlight.countDown()
        try {
          if (!allInFlight.await(10, TimeUnit.SECONDS))
            throw new AssertionError(s"timed out waiting for all $totalPartitions reads to start (inFlight=${inFlight.get})")
          Iterator.single(intBatch(schema, Seq(p)))
        } finally {
          inFlight.decrementAndGet()
        }
      }
    }

    val views = Seq.fill(3)(new BlockingView(schema, 2))
    val mvs = MaterializedView.materializeManyInParallel(views)
    assertEquals(3, mvs.length)
    mvs.foreach(mv => assertEquals(2, mv.numPartitions))
    assertEquals(s"expected $totalPartitions concurrent reads, peaked at ${peakInFlight.get}",
      totalPartitions, peakInFlight.get())
  }

  @Test def materializeManyInParallelHandlesEmptyView(): Unit = {
    val empty = new CatalogView {
      def schema: Schema = Schema(Field("x", DataType.IntType))
      def numPartitions: Int = 0
      def readPartition(p: Int): Iterator[ColumnarBatch] =
        throw new IllegalStateException("should not be called")
    }
    val mvs = MaterializedView.materializeManyInParallel(Seq(empty))
    assertEquals(1, mvs.length)
    assertEquals(0, mvs.head.numPartitions)
    assertEquals(0L, mvs.head.totalRows)
  }
}
