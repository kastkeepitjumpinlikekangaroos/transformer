package com.transformer.sql.exec

import com.transformer.core.{BooleanVector, ColumnVector, ColumnarBatch, DataType, DoubleVector, Field, IntVector, LongVector, Schema, StringVector}
import com.transformer.sql.plan._
import org.junit.Assert._
import org.junit.Test

class AggStateSerdeTest {

  // ---- helpers -------------------------------------------------------------

  /** Build a single-arg ColRefExpr for the given column index + type. */
  private def colRef(idx: Int, dt: DataType, name: String = "x"): ColRefExpr =
    ColRefExpr(idx, name, dt)

  /** Build a 1-column LongVector batch from a Seq[Option[Long]]. */
  private def longBatch(vals: Seq[Option[Long]]): ColumnarBatch = {
    val schema = Schema(Field("x", DataType.LongType))
    val b = new ColumnarBatch(schema, math.max(1, vals.size))
    val v = b.column(0).asInstanceOf[LongVector]
    vals.zipWithIndex.foreach { case (o, i) => o match {
      case Some(x) => v.set(i, x)
      case None    => v.setNull(i)
    }}
    b.setNumRows(vals.size)
    b
  }

  /** Build a 1-column DoubleVector batch. */
  private def doubleBatch(vals: Seq[Option[Double]]): ColumnarBatch = {
    val schema = Schema(Field("x", DataType.DoubleType))
    val b = new ColumnarBatch(schema, math.max(1, vals.size))
    val v = b.column(0).asInstanceOf[DoubleVector]
    vals.zipWithIndex.foreach { case (o, i) => o match {
      case Some(x) => v.set(i, x)
      case None    => v.setNull(i)
    }}
    b.setNumRows(vals.size)
    b
  }

  /** Build a 1-column IntVector batch. */
  private def intBatch(vals: Seq[Option[Int]]): ColumnarBatch = {
    val schema = Schema(Field("x", DataType.IntType))
    val b = new ColumnarBatch(schema, math.max(1, vals.size))
    val v = b.column(0).asInstanceOf[IntVector]
    vals.zipWithIndex.foreach { case (o, i) => o match {
      case Some(x) => v.set(i, x)
      case None    => v.setNull(i)
    }}
    b.setNumRows(vals.size)
    b
  }

  /** Build a 1-column BooleanVector batch. */
  private def booleanBatch(vals: Seq[Option[Boolean]]): ColumnarBatch = {
    val schema = Schema(Field("x", DataType.BooleanType))
    val b = new ColumnarBatch(schema, math.max(1, vals.size))
    val v = b.column(0).asInstanceOf[BooleanVector]
    vals.zipWithIndex.foreach { case (o, i) => o match {
      case Some(x) => v.set(i, x)
      case None    => v.setNull(i)
    }}
    b.setNumRows(vals.size)
    b
  }

  /** Build a 2-column DoubleVector batch (for covar/corr). */
  private def doubleDoubleBatch(xs: Seq[Option[Double]], ys: Seq[Option[Double]]): ColumnarBatch = {
    assert(xs.size == ys.size)
    val schema = Schema(Field("x", DataType.DoubleType), Field("y", DataType.DoubleType))
    val b = new ColumnarBatch(schema, math.max(1, xs.size))
    val vx = b.column(0).asInstanceOf[DoubleVector]
    val vy = b.column(1).asInstanceOf[DoubleVector]
    xs.zip(ys).zipWithIndex.foreach { case ((ox, oy), i) =>
      ox match { case Some(x) => vx.set(i, x); case None => vx.setNull(i) }
      oy match { case Some(y) => vy.set(i, y); case None => vy.setNull(i) }
    }
    b.setNumRows(xs.size)
    b
  }

  private def updateAll(state: AggState, argVecs: Array[ColumnVector], n: Int): Unit = {
    var r = 0
    while (r < n) { state.updateAt(argVecs, r); r += 1 }
  }

  /** Run `state` over `batch`, serialize, deserialize, and assert finish()
    * agrees. Returns the (live, restored) pair so the caller can do extra
    * post-roundtrip checks (eg. merge with another state). */
  private def roundtrip(agg: AggExpr, batch: ColumnarBatch): (AggState, AggState) = {
    val live = AggState.init(agg)
    val argVecs = Array(batch.column(0))
    updateAll(live, argVecs, batch.numRows)

    val bytes = AggStateSerde.serializeBytes(live)
    val restored = AggStateSerde.deserializeBytes(agg, bytes)
    assertEquals(s"finish() must match after roundtrip on $agg", live.finish(), restored.finish())
    (live, restored)
  }

  // ---- per-state roundtrip -------------------------------------------------

  @Test def countStarRoundtrip(): Unit = {
    val agg = AggExprCountStar()
    val live = AggState.init(agg)
    val argVecs = Array(intBatch(Seq(Some(1), Some(2), Some(3))).column(0))
    updateAll(live, argVecs, 3)
    val bytes = AggStateSerde.serializeBytes(live)
    val restored = AggStateSerde.deserializeBytes(agg, bytes)
    assertEquals(3L, restored.finish())
  }

  @Test def countNonDistinctRoundtripCountsNullsCorrectly(): Unit = {
    val agg = AggExprCount(colRef(0, DataType.LongType), distinct = false)
    val (_, restored) = roundtrip(agg, longBatch(Seq(Some(1L), None, Some(2L), None, Some(3L))))
    assertEquals(3L, restored.finish())
  }

  @Test def countIfRoundtrip(): Unit = {
    val agg = AggExprCountIf(BinOpExpr(">", colRef(0, DataType.LongType), LitExpr(0L, DataType.LongType), DataType.BooleanType))
    val live = AggState.init(agg)
    // CountIf reads the predicate evaluated for each row — but for the SerDe
    // we only need to populate `c`. Manually drive it with a Boolean column.
    val boolSchema = Schema(Field("p", DataType.BooleanType))
    val b = new ColumnarBatch(boolSchema, 5)
    val bv = b.column(0).asInstanceOf[com.transformer.core.BooleanVector]
    bv.set(0, true); bv.setNull(1); bv.set(2, false); bv.set(3, true); bv.set(4, true)
    b.setNumRows(5)
    updateAll(live, Array(b.column(0)), 5)
    val bytes = AggStateSerde.serializeBytes(live)
    val restored = AggStateSerde.deserializeBytes(agg, bytes)
    assertEquals(3L, restored.finish())
  }

  @Test def longSumRoundtripIncludingNullOnlyInput(): Unit = {
    val agg = AggExprSum(colRef(0, DataType.LongType))
    val (_, restored) = roundtrip(agg, longBatch(Seq(Some(10L), Some(20L), Some(-5L))))
    assertEquals(25L, restored.finish())

    // null-only input: finish should be null both pre- and post-roundtrip
    val (_, restoredNull) = roundtrip(agg, longBatch(Seq(None, None, None)))
    assertNull(restoredNull.finish())
  }

  @Test def doubleSumRoundtrip(): Unit = {
    val agg = AggExprSum(colRef(0, DataType.DoubleType))
    val (_, restored) = roundtrip(agg, doubleBatch(Seq(Some(1.5), Some(2.5), Some(-1.0))))
    assertEquals(3.0, restored.finish().asInstanceOf[Double], 1e-12)
  }

  @Test def avgRoundtrip(): Unit = {
    val agg = AggExprAvg(colRef(0, DataType.DoubleType))
    val (_, restored) = roundtrip(agg, doubleBatch(Seq(Some(2.0), Some(4.0), Some(6.0), None)))
    assertEquals(4.0, restored.finish().asInstanceOf[Double], 1e-12)
  }

  @Test def minMaxRoundtripLongType(): Unit = {
    val aggMin = AggExprMin(colRef(0, DataType.LongType))
    val (_, restoredMin) = roundtrip(aggMin, longBatch(Seq(Some(7L), Some(3L), Some(9L))))
    assertEquals(java.lang.Long.valueOf(3L), restoredMin.finish())

    val aggMax = AggExprMax(colRef(0, DataType.LongType))
    val (_, restoredMax) = roundtrip(aggMax, longBatch(Seq(Some(7L), Some(3L), Some(9L))))
    assertEquals(java.lang.Long.valueOf(9L), restoredMax.finish())
  }

  @Test def minMaxRoundtripStringType(): Unit = {
    val schema = Schema(Field("s", DataType.StringType))
    val b = new ColumnarBatch(schema, 3)
    val v = b.column(0).asInstanceOf[StringVector]
    v.set(0, "banana"); v.set(1, "apple"); v.set(2, "cherry")
    b.setNumRows(3)

    val aggMin = AggExprMin(colRef(0, DataType.StringType))
    val (_, restoredMin) = roundtrip(aggMin, b)
    assertEquals("apple", restoredMin.finish())

    val aggMax = AggExprMax(colRef(0, DataType.StringType))
    val (_, restoredMax) = roundtrip(aggMax, b)
    assertEquals("cherry", restoredMax.finish())
  }

  /** `BooleanType` is the one MIN/MAX result type whose accumulator slot is not
    * obvious from the vector: it rides the `longCur` slot as 0/1, the same slot
    * the spill format writes. Round-trip, and the merge of a restored partial
    * with a live one, must both preserve it. */
  @Test def minMaxRoundtripBooleanType(): Unit = {
    val aggMin = AggExprMin(colRef(0, DataType.BooleanType))
    val (_, restoredMin) = roundtrip(aggMin, booleanBatch(Seq(Some(true), Some(false), Some(true))))
    assertEquals(java.lang.Boolean.FALSE, restoredMin.finish())

    val aggMax = AggExprMax(colRef(0, DataType.BooleanType))
    val (_, restoredMax) = roundtrip(aggMax, booleanBatch(Seq(Some(false), Some(true), Some(false))))
    assertEquals(java.lang.Boolean.TRUE, restoredMax.finish())

    // All-true input under MIN, so the answer is the value the `longCur` slot
    // would report if it were never written (0 == false) — the shape that made
    // the old boxed-slot mismatch look like a plausible NULL.
    val (_, restoredAllTrue) = roundtrip(aggMin, booleanBatch(Seq(Some(true), Some(true))))
    assertEquals(java.lang.Boolean.TRUE, restoredAllTrue.finish())

    // Null-only input stays NULL across the round-trip.
    val (_, restoredNull) = roundtrip(aggMax, booleanBatch(Seq(None, None)))
    assertNull(restoredNull.finish())

    // A restored partial merged with an in-memory partial.
    val partA = AggState.init(aggMax)
    val first = booleanBatch(Seq(Some(false)))
    updateAll(partA, Array(first.column(0)), first.numRows)
    val restoredA = AggStateSerde.deserializeBytes(aggMax, AggStateSerde.serializeBytes(partA))
    val partB = AggState.init(aggMax)
    val second = booleanBatch(Seq(Some(true)))
    updateAll(partB, Array(second.column(0)), second.numRows)
    restoredA.merge(partB)
    assertEquals(java.lang.Boolean.TRUE, restoredA.finish())
  }

  @Test def minMaxNoInputSerializesAsHasValueFalse(): Unit = {
    val agg = AggExprMin(colRef(0, DataType.LongType))
    val empty = AggState.init(agg)
    val bytes = AggStateSerde.serializeBytes(empty)
    val restored = AggStateSerde.deserializeBytes(agg, bytes)
    assertNull(restored.finish())
  }

  @Test def varianceAndStddevRoundtrip(): Unit = {
    val aggVarSample = AggExprVariance(colRef(0, DataType.DoubleType), sample = true)
    val (live, restored) = roundtrip(aggVarSample, doubleBatch(Seq(Some(1.0), Some(2.0), Some(3.0), Some(4.0))))
    assertEquals(live.finish().asInstanceOf[Double], restored.finish().asInstanceOf[Double], 1e-12)

    val aggStddevPop = AggExprStddev(colRef(0, DataType.DoubleType), sample = false)
    val (l2, r2) = roundtrip(aggStddevPop, doubleBatch(Seq(Some(1.0), Some(2.0), Some(3.0), Some(4.0))))
    assertEquals(l2.finish().asInstanceOf[Double], r2.finish().asInstanceOf[Double], 1e-12)
  }

  @Test def covarRoundtrip(): Unit = {
    val agg = AggExprCovar(colRef(0, DataType.DoubleType), colRef(1, DataType.DoubleType), sample = true)
    val live = AggState.init(agg)
    val batch = doubleDoubleBatch(Seq(Some(1.0), Some(2.0), Some(3.0), Some(4.0)),
                                  Seq(Some(2.0), Some(4.0), Some(6.0), Some(8.0)))
    val argVecs = Array(batch.column(0), batch.column(1))
    updateAll(live, argVecs, batch.numRows)
    val bytes = AggStateSerde.serializeBytes(live)
    val restored = AggStateSerde.deserializeBytes(agg, bytes)
    assertEquals(live.finish().asInstanceOf[Double], restored.finish().asInstanceOf[Double], 1e-12)
  }

  @Test def corrRoundtrip(): Unit = {
    val agg = AggExprCorr(colRef(0, DataType.DoubleType), colRef(1, DataType.DoubleType))
    val live = AggState.init(agg)
    val batch = doubleDoubleBatch(Seq(Some(1.0), Some(2.0), Some(3.0), Some(4.0), Some(5.0)),
                                  Seq(Some(2.0), Some(4.1), Some(6.2), Some(7.9), Some(10.0)))
    val argVecs = Array(batch.column(0), batch.column(1))
    updateAll(live, argVecs, batch.numRows)
    val bytes = AggStateSerde.serializeBytes(live)
    val restored = AggStateSerde.deserializeBytes(agg, bytes)
    assertEquals(live.finish().asInstanceOf[Double], restored.finish().asInstanceOf[Double], 1e-12)
  }

  // ---- merge after roundtrip ------------------------------------------------

  /** Partial accumulation → spill → restore → merge with a new partial → finish
    * must agree with running everything in one shot. Mirrors the actual spill
    * flow: flush partials to disk, continue accumulating in memory, then
    * combine at emit time. */
  @Test def restoredStateMergesWithInMemoryPartial(): Unit = {
    val agg = AggExprAvg(colRef(0, DataType.DoubleType))

    val all = AggState.init(agg)
    val first = doubleBatch(Seq(Some(2.0), Some(4.0)))
    val second = doubleBatch(Seq(Some(6.0), Some(8.0), Some(10.0)))
    updateAll(all, Array(first.column(0)), first.numRows)
    updateAll(all, Array(second.column(0)), second.numRows)

    val partA = AggState.init(agg)
    updateAll(partA, Array(first.column(0)), first.numRows)
    val partARestored = AggStateSerde.deserializeBytes(agg, AggStateSerde.serializeBytes(partA))

    val partB = AggState.init(agg)
    updateAll(partB, Array(second.column(0)), second.numRows)

    partARestored.merge(partB)
    assertEquals(all.finish().asInstanceOf[Double], partARestored.finish().asInstanceOf[Double], 1e-12)
  }

  // ---- spillability gating --------------------------------------------------

  @Test def countDistinctIsNotSpillable(): Unit = {
    val cd = AggExprCount(colRef(0, DataType.LongType), distinct = true)
    assertFalse(AggStateSerde.isSpillable(cd))
    val state = AggState.init(cd)
    try {
      AggStateSerde.serializeBytes(state); fail("expected UnsupportedOperationException")
    } catch { case _: UnsupportedOperationException => () }
    try {
      AggStateSerde.deserializeBytes(cd, Array.emptyByteArray); fail("expected UnsupportedOperationException")
    } catch { case _: UnsupportedOperationException => () }
  }

  @Test def allSpillableRejectsMixedAggregateSet(): Unit = {
    val ok = AggExprSum(colRef(0, DataType.LongType))
    val notOk = AggExprCount(colRef(0, DataType.LongType), distinct = true)
    assertTrue(AggStateSerde.allSpillable(Seq(ok)))
    assertFalse(AggStateSerde.allSpillable(Seq(ok, notOk)))
  }
}
