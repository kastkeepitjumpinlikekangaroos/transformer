package com.transformer.fuzz

import com.transformer.core._
import com.transformer.read.parquet.ParquetReader
import com.transformer.sql.plan.{ColRefExpr, SortKey}
import com.transformer.write.parquet.ParquetWriter
import org.junit.Assert._
import org.junit.Test

import java.math.BigDecimal
import java.nio.file.Files
import scala.collection.mutable

/** The typed order a sort compares keys by ([[SortKey.order]]) agrees with
  * the boxed one ([[SortKey.compare]]) on every type, ASC and DESC, NULLS
  * FIRST and LAST: the sign of `order.compare(a, i, b, j)` is the sign of
  * `compare` of the two values boxed, for every pair of a pool and for random
  * pairs. Each pool holds the values a type's order is easy to get wrong on:
  * NaNs of several bit patterns, which tie and sort above +Infinity; -0.0 and
  * 0.0, which tie; the infinities and each type's extremes; `1`, `1.0` and
  * `1.00`, which tie, below `1.000000000000000001`; `01` against `81` and a
  * prefix before what it starts; `""` and prefixes first, U+E000 and U+FFFD
  * above U+1F600 (which UTF-16 stores below U+E000), and lone surrogates;
  * dates and timestamps before 1970 and at the storable extremes; and NULLs.
  * Again over each pool spilled and read back, as a sort's run is, against
  * itself and against the vector it was written from. Text past ASCII is built
  * from code points. */
class SortKeyOrderTest {

  private val Directions: Seq[(Boolean, Boolean)] =
    for (asc <- Seq(true, false); nullsFirst <- Seq(true, false)) yield (asc, nullsFirst)

  private val Dec = DataType.DecimalType(38, 18)

  private val Types: Seq[DataType] = Seq(DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
    DataType.BooleanType, DataType.StringType, DataType.BinaryType, DataType.DateType, DataType.TimestampType,
    DataType.DecimalType(9, 2), Dec, DataType.NullType)

  private def text(codePoints: Int*): String = TextPool.text(codePoints: _*)
  private def bytes(xs: Int*): Array[Byte] = xs.map(_.toByte).toArray
  private def dec(s: String): BigDecimal = new BigDecimal(s)

  /** A pool for `t`, its values as a vector's `setBoxed` takes them, NULL
    * among them. */
  private def pool(t: DataType): IndexedSeq[Any] = (t match {
    case DataType.IntType =>
      Seq[Any](Int.MinValue, Int.MinValue + 1, -1, 0, 1, 2, Int.MaxValue - 1, Int.MaxValue) ++ ExprGen.EdgeInts
    case DataType.LongType =>
      Seq[Any](Long.MinValue, Long.MinValue + 1, -1L, 0L, 1L, 9007199254740992L, 9007199254740993L,
        Long.MaxValue - 1, Long.MaxValue) ++ ExprGen.EdgeLongs
    case DataType.FloatType =>
      Seq[Any](Float.NaN, java.lang.Float.intBitsToFloat(0x7fc00001), java.lang.Float.intBitsToFloat(0xffc00000),
        java.lang.Float.intBitsToFloat(0x7f800001), -0.0f, 0.0f, Float.PositiveInfinity, Float.NegativeInfinity,
        Float.MaxValue, -Float.MaxValue, Float.MinPositiveValue, -Float.MinPositiveValue, 1.0f, -1.0f, 16777216.0f,
        16777217.0f)
    case DataType.DoubleType =>
      Seq[Any](Double.NaN, java.lang.Double.longBitsToDouble(0x7ff8000000000001L),
        java.lang.Double.longBitsToDouble(0xfff8000000000000L), java.lang.Double.longBitsToDouble(0x7ff0000000000001L),
        -0.0, 0.0, Double.PositiveInfinity, Double.NegativeInfinity, Double.MaxValue, -Double.MaxValue,
        Double.MinPositiveValue, -Double.MinPositiveValue, 1.0, -1.0, 9007199254740992.0, 9007199254740994.0)
    case DataType.BooleanType => Seq[Any](true, false, true)
    case DataType.StringType =>
      Seq[Any]("", "", "a", "ab", "abc", "b", "B", " ", "a ", text(0), text(0x7f), text(0xe9), text(0xe000),
        text(0xfffd), text(0xffff), text(0x1f600), text(0x61, 0x1f600), text(0x1f600, 0x61), text(0xd83d),
        text(0xde00), text(0xd83d, 0x61), text(0x61, 0xde00)) ++ TextPool.Values
    case DataType.BinaryType =>
      Seq[Any](bytes(), bytes(), bytes(0x00), bytes(0x01), bytes(0x81), bytes(0x7f), bytes(0x80), bytes(0xff),
        bytes(0x01, 0x02), bytes(0x01, 0x82), bytes(0x01, 0x02, 0x00), bytes(0x01, 0x02, 0xff)) ++
        ExprGen.BinaryPool.map(_.clone())
    case d: DataType.DecimalType =>
      val own = if (d.scale >= 18) Seq("1.000000000000000001", "1.000000000000000002", "-0.000000000000000001")
                else Seq("0.01", "-0.01")
      (Seq("0", "0.0", "0.00", "1", "1.0", "1.00", "-1", "-1.5", "100", "99.99") ++ own).map(dec) ++
        Seq[Any](dec("1E+2"), dec("1").setScale(d.scale)) ++ ExprGen.DecimalPools.getOrElse(d, Nil)
    case DataType.DateType =>
      Seq[Any](Int.MinValue, Int.MinValue + 1, -719162, -1, 0, 1, Int.MaxValue - 1, Int.MaxValue) ++
        ExprGen.DatePool ++ DataGen.DateEdges
    case DataType.TimestampType =>
      Seq[Any](Long.MinValue, Long.MinValue + 1, -1000001L, -1L, 0L, 1L, Long.MaxValue - 1, Long.MaxValue) ++
        ExprGen.TimestampPool ++ DataGen.TimestampEdges
    case DataType.NullType => Seq[Any](null, null)
  }).toIndexedSeq ++ IndexedSeq(null, null)

  /** A random value of `t`, NULL a tenth of the time: random bits for the
    * numbers, so NaNs of every pattern; code units of every kind for text. */
  private def random(t: DataType, rng: java.util.SplittableRandom): Any =
    if (rng.nextInt(10) == 0) null else t match {
      case DataType.IntType => if (rng.nextBoolean()) rng.nextInt() else rng.nextInt(5) - 2
      case DataType.LongType => if (rng.nextBoolean()) rng.nextLong() else rng.nextLong(5) - 2
      case DataType.FloatType => java.lang.Float.intBitsToFloat(rng.nextInt())
      case DataType.DoubleType => java.lang.Double.longBitsToDouble(rng.nextLong())
      case DataType.BooleanType => rng.nextBoolean()
      case DataType.StringType =>
        val units = Array(0x61, 0x62, 0x41, 0x20, 0xe9, 0xe000, 0xfffd, 0xffff, 0xd83d, 0xde00, 0xdbff, 0xdc00)
        new String(Array.fill(rng.nextInt(4))(units(rng.nextInt(units.length)).toChar))
      case DataType.BinaryType => Array.fill(rng.nextInt(4))(Seq(0x00, 0x01, 0x7f, 0x80, 0xff)(rng.nextInt(5)).toByte)
      case d: DataType.DecimalType =>
        BigDecimal.valueOf(rng.nextLong(-100000L, 100000L), rng.nextInt(math.min(d.scale, 6) + 1))
      case DataType.DateType => if (rng.nextBoolean()) rng.nextInt() else rng.nextInt(5) - 2
      case DataType.TimestampType => if (rng.nextBoolean()) rng.nextLong() else rng.nextLong(5) - 2
      case DataType.NullType => null
    }

  private def vector(t: DataType, values: IndexedSeq[Any]): ColumnVector = {
    val v = ColumnVector.allocate(t, math.max(1, values.length))
    values.indices.foreach(r => if (values(r) == null) v.setNull(r) else v.setBoxed(r, values(r)))
    v
  }

  private def boxed(v: ColumnVector, r: Int): Any = if (v.isNull(r)) null else v.getBoxed(r)

  /** Every row of `a` against every row of `b` under every direction. */
  private def checkPairs(label: String, t: DataType, a: ColumnVector, na: Int, b: ColumnVector, nb: Int): Unit =
    for ((asc, nullsFirst) <- Directions) {
      val key = SortKey(ColRefExpr(0, "k", t), asc, nullsFirst)
      val order = key.order
      var i = 0
      while (i < na) {
        var j = 0
        while (j < nb) {
          val x = boxed(a, i)
          val y = boxed(b, j)
          val expected = Integer.signum(key.compare(x, y))
          val got = Integer.signum(order.compare(a, i, b, j))
          if (got != expected)
            fail(s"$label, $t ${if (asc) "ASC" else "DESC"} NULLS ${if (nullsFirst) "FIRST" else "LAST"}: " +
              s"${RowOracle.describe(x)} against ${RowOracle.describe(y)}: the typed order says $got, compare $expected")
          j += 1
        }
        i += 1
      }
    }

  /** `v`'s first `n` rows written as a sort writes a run, and read back: each
    * batch read copied to a vector of exactly its rows. */
  private def spilledAndReadBack(t: DataType, v: ColumnVector, n: Int): Vector[ColumnVector] = {
    val dir = Files.createTempDirectory("sort-key-order")
    val file = dir.resolve("run.parquet")
    try {
      val schema = Schema(Vector(Field("k", t)))
      val batch = ColumnarBatch.fromColumns(schema, Array(v), n)
      val _ = ParquetWriter.writeAll(file, Spill.positionalSchema(schema), Iterator(batch))
      val reader = ParquetReader.fromPath(file.toString)
      val out = mutable.ArrayBuffer.empty[ColumnVector]
      (0 until reader.numPartitions).foreach { p =>
        val it = reader.readPartition(p)
        try it.foreach { b =>
          if (b.numRows > 0) {
            val own = ColumnVector.allocate(b.column(0).dataType, b.numRows)
            own.copyRange(b.column(0), 0, b.numRows, 0)
            out += own
          }
        } finally Closing.close(it)
      }
      out.toVector
    } finally {
      Files.deleteIfExists(file)
      Files.deleteIfExists(dir)
    }
  }

  @Test def theTypedOrderAgreesWithCompareOnEveryPairOfEachPool(): Unit =
    for (t <- Types) {
      val values = pool(t)
      val v = vector(t, values)
      checkPairs("pool", t, v, values.length, v, values.length)
    }

  @Test def theTypedOrderAgreesWithCompareOnRandomPairs(): Unit = {
    val rng = new java.util.SplittableRandom(20261103L)
    for (t <- Types; _ <- 0 until 20) {
      val a = IndexedSeq.fill(60)(random(t, rng))
      val b = IndexedSeq.fill(60)(random(t, rng))
      checkPairs("random", t, vector(t, a), a.length, vector(t, b), b.length)
    }
  }

  /** A run spilled and read back is compared as the vector a sort reads it
    * into, against itself and against rows still in memory. A NULL-typed
    * column reads back as a BOOLEAN holding only NULLs (`Spill.storedType`),
    * and half a surrogate pair as `?`, which compares as what it now is. */
  @Test def theTypedOrderAgreesOverRunsSpilledAndReadBack(): Unit =
    for (t <- Types) {
      val values = pool(t)
      val v = vector(t, values)
      val back = spilledAndReadBack(t, v, values.length)
      assertEquals(s"$t rows read back", values.length, back.map(_.capacity).sum)
      for (r <- back) {
        checkPairs("read back", t, r, r.capacity, r, r.capacity)
        checkPairs("read back against memory", t, r, r.capacity, v, values.length)
        checkPairs("memory against read back", t, v, values.length, r, r.capacity)
      }
    }

  /** -0.0 and 0.0 tie, and every NaN ties every other, whatever its bits. */
  @Test def zerosTieAndNaNsTieAboveInfinity(): Unit = {
    val d = vector(DataType.DoubleType, IndexedSeq(-0.0, 0.0, Double.NaN,
      java.lang.Double.longBitsToDouble(0xfff8000000000001L), Double.PositiveInfinity))
    val order = SortKey(ColRefExpr(0, "d", DataType.DoubleType), asc = true).order
    assertEquals(0, order.compare(d, 0, d, 1))
    assertEquals(0, order.compare(d, 2, d, 3))
    assertTrue(order.compare(d, 4, d, 2) < 0)
    assertTrue(order.compare(d, 3, d, 4) > 0)
  }

  /** By UTF-16 code unit, as `Ops.cmp` orders strings: U+1F600 (D83D DE00)
    * sorts below U+E000 and U+FFFD, which code points put below it. */
  @Test def stringsOrderByCodeUnit(): Unit = {
    val s = vector(DataType.StringType, IndexedSeq(text(0x1f600), text(0xe000), text(0xfffd), "", "a"))
    val order = SortKey(ColRefExpr(0, "s", DataType.StringType), asc = true).order
    assertTrue(order.compare(s, 0, s, 1) < 0)
    assertTrue(order.compare(s, 0, s, 2) < 0)
    assertTrue(order.compare(s, 3, s, 4) < 0)
  }
}
