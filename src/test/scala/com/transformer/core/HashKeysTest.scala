package com.transformer.core

import org.junit.Assert._
import org.junit.Test

class HashKeysTest {

  // ---------------------------------------------------------------------------
  // Selection: forColumns picks PackedBytesCodec for fixed-width-only keys,
  // ObjectArrayCodec when any column is variable-width, EmptyKeyCodec for zero.
  // ---------------------------------------------------------------------------

  @Test def forColumnsPicksPackedBytesForFixedWidthOnly(): Unit = {
    val c = KeyCodec.forColumns(Array(0, 1), Array(DataType.LongType, DataType.IntType))
    assertTrue(c.isInstanceOf[PackedBytesCodec])
  }

  @Test def forColumnsPicksObjectArrayWhenStringPresent(): Unit = {
    val c = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.LongType))
    assertTrue(c.isInstanceOf[ObjectArrayCodec])
  }

  @Test def forColumnsPicksObjectArrayForDecimal(): Unit = {
    val c = KeyCodec.forColumns(Array(0), Array(DataType.DecimalType(10, 2)))
    assertTrue(c.isInstanceOf[ObjectArrayCodec])
  }

  @Test def forColumnsPicksObjectArrayForBinary(): Unit = {
    val c = KeyCodec.forColumns(Array(0), Array(DataType.BinaryType))
    assertTrue(c.isInstanceOf[ObjectArrayCodec])
  }

  @Test def forColumnsReturnsEmptyKeyCodecForZero(): Unit = {
    val c = KeyCodec.forColumns(Array.empty[Int], Array.empty[DataType])
    assertSame(EmptyKeyCodec, c)
  }

  @Test def forColumnsRejectsMismatchedLengths(): Unit = {
    try {
      KeyCodec.forColumns(Array(0, 1), Array(DataType.IntType))
      fail("expected IAE")
    } catch { case _: IllegalArgumentException => () }
  }

  // ---------------------------------------------------------------------------
  // EmptyKeyCodec: every encode returns the same key, decode is a no-op,
  // safe to use as a map key. Empty-group COUNT(*) relies on this.
  // ---------------------------------------------------------------------------

  @Test def emptyCodecCollapsesToSentinel(): Unit = {
    val c = EmptyKeyCodec
    val b = new ColumnarBatch(Schema(Field("x", DataType.IntType)), 1)
    b.setNumRows(1)
    val k1 = c.encodeFromBatch(b, 0)
    val k2 = c.encodeFromBatch(b, 0)
    val k3 = c.encodeBoxed(new Array[Any](0))
    val k4 = c.encodeFromBatchSkipIfAnyNull(b, 0)
    assertSame(k1, k2)
    assertSame(k1, k3)
    assertSame(k1, k4)
  }

  // ---------------------------------------------------------------------------
  // BytesKey & ObjectArrayKey: structural equality + cached hash, distinct
  // instances with same content compare equal.
  // ---------------------------------------------------------------------------

  @Test def bytesKeyEqualsByContent(): Unit = {
    val a = new BytesKey(Array[Byte](1, 2, 3))
    val b = new BytesKey(Array[Byte](1, 2, 3))
    val c = new BytesKey(Array[Byte](1, 2, 4))
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, c)
  }

  @Test def objectArrayKeyEqualsByContent(): Unit = {
    val a = new ObjectArrayKey(Array[AnyRef](java.lang.Long.valueOf(1L), "hello"))
    val b = new ObjectArrayKey(Array[AnyRef](java.lang.Long.valueOf(1L), new String("hello")))
    val c = new ObjectArrayKey(Array[AnyRef](java.lang.Long.valueOf(1L), "world"))
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, c)
  }

  @Test def objectArrayKeyHandlesNullElement(): Unit = {
    val a = new ObjectArrayKey(Array[AnyRef](java.lang.Long.valueOf(1L), null))
    val b = new ObjectArrayKey(Array[AnyRef](java.lang.Long.valueOf(1L), null))
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test def objectArrayKeyTreatsByteArrayElementsStructurally(): Unit = {
    val a = new ObjectArrayKey(Array[AnyRef](Array[Byte](1, 2, 3): AnyRef, "x"))
    val b = new ObjectArrayKey(Array[AnyRef](Array[Byte](1, 2, 3): AnyRef, "x"))
    val c = new ObjectArrayKey(Array[AnyRef](Array[Byte](1, 2, 4): AnyRef, "x"))
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertNotEquals(a, c)
  }

  // ---------------------------------------------------------------------------
  // PackedBytesCodec: round-trip every supported primitive type, including NULL
  // sentinels and the fast encodeFromBatch path.
  // ---------------------------------------------------------------------------

  @Test def packedRoundTripLong(): Unit = {
    val schema = Schema(Field("x", DataType.LongType))
    val codec = KeyCodec.forColumns(Array(0), Array(DataType.LongType))
    val src = new ColumnarBatch(schema, 4)
    val v = src.column(0).asInstanceOf[LongVector]
    v.set(0, 42L); v.set(1, -1L); v.setNull(2); v.set(3, Long.MaxValue)
    src.setNumRows(4)

    val out = new ColumnarBatch(schema, 4)
    out.setNumRows(4)
    var r = 0
    while (r < 4) {
      val key = codec.encodeFromBatch(src, r)
      codec.decode(key, out, 0, r)
      r += 1
    }
    val ov = out.column(0).asInstanceOf[LongVector]
    assertEquals(42L, ov.values(0))
    assertEquals(-1L, ov.values(1))
    assertTrue(ov.isNull(2))
    assertEquals(Long.MaxValue, ov.values(3))
  }

  @Test def packedRoundTripAllFixedWidthTypes(): Unit = {
    val schema = Schema(
      Field("i", DataType.IntType),
      Field("l", DataType.LongType),
      Field("f", DataType.FloatType),
      Field("d", DataType.DoubleType),
      Field("b", DataType.BooleanType),
      Field("dt", DataType.DateType),
      Field("ts", DataType.TimestampType)
    )
    val codec = KeyCodec.forColumns(
      Array(0, 1, 2, 3, 4, 5, 6),
      Array(DataType.IntType, DataType.LongType, DataType.FloatType,
            DataType.DoubleType, DataType.BooleanType, DataType.DateType, DataType.TimestampType))
    assertTrue(codec.isInstanceOf[PackedBytesCodec])

    val src = new ColumnarBatch(schema, 1)
    src.column(0).asInstanceOf[IntVector].set(0, Int.MinValue)
    src.column(1).asInstanceOf[LongVector].set(0, Long.MaxValue)
    src.column(2).asInstanceOf[FloatVector].set(0, 1.5f)
    src.column(3).asInstanceOf[DoubleVector].set(0, math.Pi)
    src.column(4).asInstanceOf[BooleanVector].set(0, true)
    src.column(5).asInstanceOf[DateVector].set(0, 12345)
    src.column(6).asInstanceOf[TimestampVector].set(0, 1700000000000000L)
    src.setNumRows(1)

    val key = codec.encodeFromBatch(src, 0)
    val out = new ColumnarBatch(schema, 1); out.setNumRows(1)
    codec.decode(key, out, 0, 0)
    assertEquals(Int.MinValue, out.column(0).asInstanceOf[IntVector].values(0))
    assertEquals(Long.MaxValue, out.column(1).asInstanceOf[LongVector].values(0))
    assertEquals(1.5f, out.column(2).asInstanceOf[FloatVector].values(0), 0.0f)
    assertEquals(math.Pi, out.column(3).asInstanceOf[DoubleVector].values(0), 0.0)
    assertTrue(out.column(4).asInstanceOf[BooleanVector].values(0))
    assertEquals(12345, out.column(5).asInstanceOf[DateVector].values(0))
    assertEquals(1700000000000000L, out.column(6).asInstanceOf[TimestampVector].values(0))
  }

  @Test def packedNullsGroupTogether(): Unit = {
    val schema = Schema(Field("x", DataType.LongType), Field("y", DataType.IntType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.LongType, DataType.IntType))
    val b = new ColumnarBatch(schema, 3)
    val xs = b.column(0).asInstanceOf[LongVector]
    val ys = b.column(1).asInstanceOf[IntVector]
    xs.setNull(0); ys.set(0, 5)
    xs.setNull(1); ys.set(1, 5)
    xs.set(2, 0L); ys.set(2, 5)
    b.setNumRows(3)
    val k0 = codec.encodeFromBatch(b, 0)
    val k1 = codec.encodeFromBatch(b, 1)
    val k2 = codec.encodeFromBatch(b, 2)
    assertEquals(k0, k1)
    assertEquals(k0.hashCode(), k1.hashCode())
    assertNotEquals(k0, k2) // NULL distinct from 0
  }

  @Test def packedSkipIfAnyNullReturnsJavaNull(): Unit = {
    val schema = Schema(Field("x", DataType.LongType), Field("y", DataType.IntType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.LongType, DataType.IntType))
    val b = new ColumnarBatch(schema, 2)
    val xs = b.column(0).asInstanceOf[LongVector]
    val ys = b.column(1).asInstanceOf[IntVector]
    xs.set(0, 1L); ys.setNull(0)
    xs.set(1, 1L); ys.set(1, 2)
    b.setNumRows(2)
    assertNull(codec.encodeFromBatchSkipIfAnyNull(b, 0))
    assertNotNull(codec.encodeFromBatchSkipIfAnyNull(b, 1))
  }

  @Test def packedBoxedAndDirectAgree(): Unit = {
    val schema = Schema(Field("x", DataType.LongType), Field("y", DataType.IntType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.LongType, DataType.IntType))
    val b = new ColumnarBatch(schema, 1)
    b.column(0).asInstanceOf[LongVector].set(0, 42L)
    b.column(1).asInstanceOf[IntVector].set(0, 7)
    b.setNumRows(1)
    val direct = codec.encodeFromBatch(b, 0)
    val boxed = codec.encodeBoxed(Array[Any](java.lang.Long.valueOf(42L), java.lang.Integer.valueOf(7)))
    assertEquals(direct, boxed)
    assertEquals(direct.hashCode(), boxed.hashCode())
  }

  @Test def packedDistinguishesColumnOrder(): Unit = {
    val schema = Schema(Field("a", DataType.IntType), Field("b", DataType.IntType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.IntType, DataType.IntType))
    val b = new ColumnarBatch(schema, 2)
    val xs = b.column(0).asInstanceOf[IntVector]
    val ys = b.column(1).asInstanceOf[IntVector]
    xs.set(0, 1); ys.set(0, 2)
    xs.set(1, 2); ys.set(1, 1)
    b.setNumRows(2)
    val k0 = codec.encodeFromBatch(b, 0)
    val k1 = codec.encodeFromBatch(b, 1)
    assertNotEquals(k0, k1)
  }

  // ---------------------------------------------------------------------------
  // ObjectArrayCodec: round-trip strings + binary + decimal + null, including
  // structural equality for distinct-instance strings (covers
  // "Equal-but-different-instance Strings hash to the same key" from the plan).
  // ---------------------------------------------------------------------------

  @Test def objectArrayRoundTripString(): Unit = {
    val schema = Schema(Field("s", DataType.StringType), Field("l", DataType.LongType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.LongType))
    assertTrue(codec.isInstanceOf[ObjectArrayCodec])
    val src = new ColumnarBatch(schema, 3)
    val ss = src.column(0).asInstanceOf[StringVector]
    val ls = src.column(1).asInstanceOf[LongVector]
    ss.set(0, "alice"); ls.set(0, 1L)
    ss.setNull(1);      ls.set(1, 2L)
    ss.set(2, "bob");   ls.setNull(2)
    src.setNumRows(3)

    val out = new ColumnarBatch(schema, 3); out.setNumRows(3)
    var r = 0
    while (r < 3) {
      val key = codec.encodeFromBatch(src, r)
      codec.decode(key, out, 0, r)
      r += 1
    }
    val oss = out.column(0).asInstanceOf[StringVector]
    val ols = out.column(1).asInstanceOf[LongVector]
    assertEquals("alice", oss.get(0));  assertEquals(1L, ols.values(0))
    assertTrue(oss.isNull(1));          assertEquals(2L, ols.values(1))
    assertEquals("bob", oss.get(2));    assertTrue(ols.isNull(2))
  }

  @Test def objectArrayDifferentInstanceStringsCompareEqual(): Unit = {
    val schema = Schema(Field("s", DataType.StringType))
    val codec = KeyCodec.forColumns(Array(0), Array(DataType.StringType))
    val src = new ColumnarBatch(schema, 2)
    val ss = src.column(0).asInstanceOf[StringVector]
    ss.set(0, new String("foo".toCharArray)) // forces a fresh String instance
    ss.set(1, "foo")
    src.setNumRows(2)
    val k0 = codec.encodeFromBatch(src, 0)
    val k1 = codec.encodeFromBatch(src, 1)
    assertEquals(k0, k1)
    assertEquals(k0.hashCode(), k1.hashCode())
  }

  @Test def objectArrayRoundTripBinary(): Unit = {
    val schema = Schema(Field("b", DataType.BinaryType))
    val codec = KeyCodec.forColumns(Array(0), Array(DataType.BinaryType))
    val src = new ColumnarBatch(schema, 1)
    src.column(0).asInstanceOf[BinaryVector].set(0, Array[Byte](7, 8, 9))
    src.setNumRows(1)
    val key = codec.encodeFromBatch(src, 0)

    val src2 = new ColumnarBatch(schema, 1)
    src2.column(0).asInstanceOf[BinaryVector].set(0, Array[Byte](7, 8, 9))
    src2.setNumRows(1)
    val key2 = codec.encodeFromBatch(src2, 0)
    assertEquals(key, key2)
    assertEquals(key.hashCode(), key2.hashCode())

    val out = new ColumnarBatch(schema, 1); out.setNumRows(1)
    codec.decode(key, out, 0, 0)
    val decoded = out.column(0).asInstanceOf[BinaryVector].get(0)
    assertArrayEquals(Array[Byte](7, 8, 9), decoded)
  }

  @Test def objectArrayRoundTripDecimal(): Unit = {
    val dt = DataType.DecimalType(10, 2)
    val schema = Schema(Field("d", dt))
    val codec = KeyCodec.forColumns(Array(0), Array(dt))
    val src = new ColumnarBatch(schema, 2)
    val dv = src.column(0).asInstanceOf[DecimalVector]
    dv.set(0, new java.math.BigDecimal("123.45"))
    dv.set(1, null)
    src.setNumRows(2)
    val k0 = codec.encodeFromBatch(src, 0)
    val k1 = codec.encodeFromBatch(src, 1)
    assertNotEquals(k0, k1)

    val out = new ColumnarBatch(schema, 2); out.setNumRows(2)
    codec.decode(k0, out, 0, 0)
    codec.decode(k1, out, 0, 1)
    val od = out.column(0).asInstanceOf[DecimalVector]
    assertEquals(new java.math.BigDecimal("123.45"), od.get(0))
    assertTrue(od.isNull(1))
  }

  @Test def objectArraySkipIfAnyNullReturnsJavaNull(): Unit = {
    val schema = Schema(Field("s", DataType.StringType), Field("l", DataType.LongType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.LongType))
    val b = new ColumnarBatch(schema, 2)
    b.column(0).asInstanceOf[StringVector].setNull(0)
    b.column(1).asInstanceOf[LongVector].set(0, 1L)
    b.column(0).asInstanceOf[StringVector].set(1, "x")
    b.column(1).asInstanceOf[LongVector].set(1, 2L)
    b.setNumRows(2)
    assertNull(codec.encodeFromBatchSkipIfAnyNull(b, 0))
    assertNotNull(codec.encodeFromBatchSkipIfAnyNull(b, 1))
  }

  @Test def objectArrayBoxedAndDirectAgree(): Unit = {
    val schema = Schema(Field("s", DataType.StringType), Field("l", DataType.LongType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.LongType))
    val b = new ColumnarBatch(schema, 1)
    b.column(0).asInstanceOf[StringVector].set(0, "abc")
    b.column(1).asInstanceOf[LongVector].set(0, 99L)
    b.setNumRows(1)
    val direct = codec.encodeFromBatch(b, 0)
    val boxed = codec.encodeBoxed(Array[Any]("abc", java.lang.Long.valueOf(99L)))
    assertEquals(direct, boxed)
    assertEquals(direct.hashCode(), boxed.hashCode())
  }

  @Test def objectArrayDistinguishesColumnOrder(): Unit = {
    val schema = Schema(Field("a", DataType.StringType), Field("b", DataType.StringType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.StringType))
    val b = new ColumnarBatch(schema, 2)
    b.column(0).asInstanceOf[StringVector].set(0, "x")
    b.column(1).asInstanceOf[StringVector].set(0, "y")
    b.column(0).asInstanceOf[StringVector].set(1, "y")
    b.column(1).asInstanceOf[StringVector].set(1, "x")
    b.setNumRows(2)
    val k0 = codec.encodeFromBatch(b, 0)
    val k1 = codec.encodeFromBatch(b, 1)
    assertNotEquals(k0, k1)
  }

  // ---------------------------------------------------------------------------
  // HashMap usability: keys from distinct rows that should match really do
  // bucket together when used as java.util.HashMap keys (exercises hashCode +
  // equals in concert). This is the property both DistinctExec and
  // HashAggregateExec rely on.
  // ---------------------------------------------------------------------------

  @Test def packedKeysWorkAsHashMapKeys(): Unit = {
    val schema = Schema(Field("a", DataType.LongType), Field("b", DataType.IntType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.LongType, DataType.IntType))
    val b1 = new ColumnarBatch(schema, 1)
    b1.column(0).asInstanceOf[LongVector].set(0, 7L)
    b1.column(1).asInstanceOf[IntVector].set(0, 3)
    b1.setNumRows(1)
    val b2 = new ColumnarBatch(schema, 1)
    b2.column(0).asInstanceOf[LongVector].set(0, 7L)
    b2.column(1).asInstanceOf[IntVector].set(0, 3)
    b2.setNumRows(1)
    val map = new java.util.HashMap[AnyRef, Int]()
    map.put(codec.encodeFromBatch(b1, 0), 42)
    assertEquals(java.lang.Integer.valueOf(42), map.get(codec.encodeFromBatch(b2, 0)))
  }

  @Test def objectArrayKeysWorkAsHashMapKeys(): Unit = {
    val schema = Schema(Field("s", DataType.StringType), Field("l", DataType.LongType))
    val codec = KeyCodec.forColumns(Array(0, 1), Array(DataType.StringType, DataType.LongType))
    val b1 = new ColumnarBatch(schema, 1)
    b1.column(0).asInstanceOf[StringVector].set(0, "hi")
    b1.column(1).asInstanceOf[LongVector].set(0, 100L)
    b1.setNumRows(1)
    val b2 = new ColumnarBatch(schema, 1)
    b2.column(0).asInstanceOf[StringVector].set(0, new String("hi".toCharArray))
    b2.column(1).asInstanceOf[LongVector].set(0, 100L)
    b2.setNumRows(1)
    val map = new java.util.HashMap[AnyRef, Int]()
    map.put(codec.encodeFromBatch(b1, 0), 7)
    assertEquals(java.lang.Integer.valueOf(7), map.get(codec.encodeFromBatch(b2, 0)))
  }
}
