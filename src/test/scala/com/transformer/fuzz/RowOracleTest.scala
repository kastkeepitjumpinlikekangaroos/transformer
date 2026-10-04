package com.transformer.fuzz

import com.transformer.core._
import com.transformer.sql.plan.Ops
import org.junit.Assert._
import org.junit.Test

import java.math.BigDecimal
import java.time.{Instant, LocalDate}

/** The comparator every parity and metamorphic property ends in, checked on its
  * own. A comparator that is too loose makes a property vacuous and one that is
  * too strict makes it red on a correct engine, and from a failing property
  * alone there is no telling which happened, so each type's equality is pinned
  * here: by content for a byte array, by value and not by scale for a decimal,
  * and with no conversion for a date or a timestamp. The engine decides what
  * equal means, so the last check holds `scalarEquals` to `Ops.eq` on every
  * type but the two floating ones, where the oracle departs from it on purpose
  * (`NaN` agrees with `NaN`).
  */
class RowOracleTest {

  private def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
  private def dec(s: String): BigDecimal = new BigDecimal(s)
  private val Dec = DataType.DecimalType(38, 18)

  // ---- byte arrays --------------------------------------------------------

  @Test def binaryComparesByContentNotIdentity(): Unit = {
    val a = bytes(1, 2)
    val b = bytes(1, 2)
    assertNotSame(a, b)
    assertTrue("two arrays with one content", RowOracle.scalarEquals(a, b, DataType.BinaryType))
    assertTrue("two empty arrays", RowOracle.scalarEquals(bytes(), bytes(), DataType.BinaryType))
    assertFalse("a prefix", RowOracle.scalarEquals(bytes(1), bytes(1, 0), DataType.BinaryType))
    assertFalse("bytes that differ only in the high bit",
      RowOracle.scalarEquals(bytes(0x01), bytes(0x81), DataType.BinaryType))
    assertFalse("the empty array is not NULL", RowOracle.scalarEquals(bytes(), null, DataType.BinaryType))
  }

  /** Rows group by the content of their byte arrays: the same multiset in
    * another order, with every cell its own array, is equal, and one byte
    * array more or less is not. */
  @Test def binaryRowsGroupByContent(): Unit = {
    val s = Schema(Vector(Field("b", DataType.BinaryType), Field("n", DataType.IntType)))
    val a = Vector(Array[Any](bytes(1, 2), 1), Array[Any](bytes(1, 2), 1), Array[Any](bytes(), 2))
    val b = Vector(Array[Any](bytes(), 2), Array[Any](bytes(1, 2), 1), Array[Any](bytes(1, 2), 1))
    assertEquals(None, RowOracle.multisetEquals(s, a, b))
    val other = Vector(Array[Any](bytes(1, 2), 1), Array[Any](bytes(1, 0x82), 1), Array[Any](bytes(), 2))
    val diff = RowOracle.multisetEquals(s, a, other)
    assertTrue("a different byte array is a different row", diff.isDefined)
    assertTrue(s"the diff shows the bytes, not an identity: ${diff.get}", diff.get.contains("X'0182'"))
    assertFalse(s"the diff shows the bytes, not an identity: ${diff.get}", diff.get.contains("[B@"))
  }

  // ---- decimals -----------------------------------------------------------

  /** `=` compares decimals by value (plan 02), so the oracle does: the scale is
    * a spelling the engine is free to change. */
  @Test def decimalComparesByValueNotScale(): Unit = {
    assertTrue(RowOracle.scalarEquals(dec("1.0"), dec("1.00"), Dec))
    assertTrue(RowOracle.scalarEquals(dec("1"), dec("1.000000000000000000"), Dec))
    assertTrue(RowOracle.scalarEquals(dec("0"), dec("0.000"), Dec))
    assertTrue(RowOracle.scalarEquals(dec("100"), dec("1E+2"), Dec))
    assertFalse(RowOracle.scalarEquals(dec("-2.5"), dec("2.5"), Dec))
  }

  /** By value, and exactly: two decimals that are one double are still two
    * values, so no comparison here may pass through a double. */
  @Test def decimalComparisonIsExact(): Unit = {
    val a = dec("1.000000000000000001")
    val b = dec("1.000000000000000002")
    assertEquals("the pair is one double", a.doubleValue, b.doubleValue, 0.0)
    assertFalse(RowOracle.scalarEquals(a, b, Dec))
    assertFalse(RowOracle.scalarEquals(a, dec("1"), Dec))
    // A decimal column holds decimals: a DOUBLE where one belongs is a wrong
    // answer even when it is the same number.
    assertFalse(RowOracle.scalarEquals(dec("1.5"), java.lang.Double.valueOf(1.5), Dec))
  }

  @Test def decimalRowsGroupByValue(): Unit = {
    val s = Schema(Vector(Field("d", Dec)))
    val a = Vector(Array[Any](dec("1.0")), Array[Any](dec("1.5")), Array[Any](dec("1.5")))
    val respelled = Vector(Array[Any](dec("1.50")), Array[Any](dec("1.000")), Array[Any](dec("1.500000")))
    assertEquals(None, RowOracle.multisetEquals(s, a, respelled))
    val collided = Vector(Array[Any](dec("1.000000000000000001")), Array[Any](dec("1.5")), Array[Any](dec("1.5")))
    assertTrue("1 and 1.000000000000000001 are different rows",
      RowOracle.multisetEquals(s, a, collided).isDefined)
    val fewer = Vector(Array[Any](dec("1.0")), Array[Any](dec("1.0")), Array[Any](dec("1.5")))
    assertTrue("the multiplicity of each value counts", RowOracle.multisetEquals(s, a, fewer).isDefined)
  }

  // ---- dates and timestamps -----------------------------------------------

  /** A DATE is a `LocalDate` and a TIMESTAMP an `Instant` on both paths, and
    * the comparison takes them as such: an epoch-day `Int` or an epoch-micros
    * `Long` where one belongs is a divergence, since `Ops.eq` calls it unequal
    * to the date or the instant it counts. */
  @Test def datesAndTimestampsCompareAsTheEngineBoxesThem(): Unit = {
    val leapDay = LocalDate.of(2024, 2, 29)
    assertTrue(RowOracle.scalarEquals(leapDay, LocalDate.of(2024, 2, 29), DataType.DateType))
    assertFalse(RowOracle.scalarEquals(leapDay, LocalDate.of(2024, 3, 1), DataType.DateType))
    assertFalse("an epoch day is not a date",
      RowOracle.scalarEquals(leapDay, Integer.valueOf(leapDay.toEpochDay.toInt), DataType.DateType))

    val t = Instant.parse("2024-03-10T02:30:00.000001Z")
    assertTrue(RowOracle.scalarEquals(t, Instant.parse("2024-03-10T02:30:00.000001Z"), DataType.TimestampType))
    assertFalse("one microsecond apart",
      RowOracle.scalarEquals(t, Instant.parse("2024-03-10T02:30:00.000002Z"), DataType.TimestampType))
    assertFalse("epoch microseconds are not an instant",
      RowOracle.scalarEquals(t, java.lang.Long.valueOf(t.getEpochSecond * 1000000L + 1L), DataType.TimestampType))
  }

  // ---- agreement with the engine ------------------------------------------

  /** For every type but FLOAT and DOUBLE, two values are equal to the oracle
    * exactly when `=` calls them equal. Each pool holds the pairs its type's
    * equality is easy to get wrong on: BIGINTs one apart past 2^53, decimals at
    * several scales and two that are one double, byte arrays equal in content
    * and a high-bit pair, a leap day, and instants a microsecond apart. */
  @Test def agreesWithOpsEqOnEveryNonFloatType(): Unit = {
    val pools: Seq[(DataType, Seq[Any])] = Seq(
      DataType.IntType -> Seq(0, 1, -1, Int.MaxValue),
      DataType.LongType -> Seq(0L, 9007199254740992L, 9007199254740993L, Long.MinValue),
      DataType.BooleanType -> Seq(true, false),
      DataType.StringType -> Seq("", "a", "A", "ab"),
      DataType.DateType -> Seq(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 3, 1), LocalDate.of(1969, 12, 31)),
      DataType.TimestampType -> Seq(
        Instant.parse("2024-03-10T02:30:00Z"), Instant.parse("2024-03-10T02:30:00.000001Z"),
        Instant.parse("2024-03-10T02:30:00.000999Z"), Instant.parse("1969-12-31T23:59:59.999999Z")),
      DataType.BinaryType -> Seq(bytes(), bytes(), bytes(1, 2), bytes(1, 2), bytes(1, 0x82), bytes(0x01), bytes(0x81)),
      Dec -> Seq(dec("1"), dec("1.0"), dec("1.00"), dec("1.000000000000000001"), dec("1.000000000000000002"),
        dec("-2.5"), dec("0"), dec("0.000")))
    for ((dt, pool) <- pools; a <- pool; b <- pool) {
      assertEquals(s"$dt: ${RowOracle.describe(a)} vs ${RowOracle.describe(b)}",
        Ops.eq(a, b), RowOracle.scalarEquals(a, b, dt))
    }
  }

  // ---- whole-row multiset comparison --------------------------------------

  private val intLong = Schema(Vector(Field("c0", DataType.IntType), Field("c1", DataType.LongType)))

  @Test def multisetEqualsIgnoresRowOrder(): Unit = {
    val a = Vector(Array[Any](1, 10L), Array[Any](2, 20L))
    val b = Vector(Array[Any](2, 20L), Array[Any](1, 10L))
    assertEquals(None, RowOracle.multisetEquals(intLong, a, b))
  }

  @Test def multisetEqualsTolerantOnDoubleExactElsewhere(): Unit = {
    val s = Schema(Vector(Field("k", DataType.IntType), Field("v", DataType.DoubleType)))
    val a = Vector(Array[Any](1, 1.0), Array[Any](2, 2.0))
    val withinTol = Vector(Array[Any](1, 1.0 + 1e-12), Array[Any](2, 2.0 - 1e-12))
    assertEquals(None, RowOracle.multisetEquals(s, a, withinTol))
    // A gross divergence on the Double column must still be caught.
    val outsideTol = Vector(Array[Any](1, 1.0), Array[Any](2, 2.5))
    assertTrue(RowOracle.multisetEquals(s, a, outsideTol).isDefined)
    // A wrong exact (group) key is caught regardless of the Double tolerance.
    val wrongKey = Vector(Array[Any](1, 1.0), Array[Any](3, 2.0))
    assertTrue(RowOracle.multisetEquals(s, a, wrongKey).isDefined)
  }

  @Test def multisetEqualsCatchesCountAndNullDifferences(): Unit = {
    val a = Vector(Array[Any](1, 10L), Array[Any](1, 10L))
    val fewer = Vector(Array[Any](1, 10L))
    assertTrue(RowOracle.multisetEquals(intLong, a, fewer).isDefined)
    val nullVsValue = Vector(Array[Any](null, 10L), Array[Any](1, 10L))
    assertTrue(RowOracle.multisetEquals(intLong, a, nullVsValue).isDefined)
  }
}
