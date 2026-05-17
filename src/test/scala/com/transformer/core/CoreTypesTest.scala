package com.transformer.core

import org.junit.Assert._
import org.junit.Test

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
}
