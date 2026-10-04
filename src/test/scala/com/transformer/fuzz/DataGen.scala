package com.transformer.fuzz

import com.transformer.core._

/** Generator of an in-memory dataset and the lever that re-lays it out under a
  * different physical layout — the data foundation of the mode-differential
  * property (`oracle/ModeDifferential`).
  *
  * Two design choices make the generated data a good adversary for the
  * "every mode agrees" property:
  *
  *   - '''Keys collide on purpose.''' Each column draws from a small candidate
  *     domain (a handful of integers around zero, a tiny string alphabet, a few
  *     well-separated doubles). Wide-random values make every row unique, which
  *     would turn every GROUP BY into one row per group and every DISTINCT into
  *     a no-op — hiding exactly the aggregation/dedup bugs this property exists
  *     to catch. Narrow domains force real grouping and deduping.
  *
  *   - '''Layout is decoupled from data.''' A [[Dataset]] is just logical rows;
  *     [[Dataset.reshape]] rebuilds an equivalent [[MaterializedView]] with a
  *     chosen partition count and batch size. The oracle replays the SAME rows
  *     through single-partition, many-tiny-partition, and with-empty-partition
  *     layouts (and sub-[[ColumnarBatch.DefaultCapacity]] batches) — the lever
  *     that exercises the cross-partition merge and empty-iterator paths.
  *
  * Null density is tunable per column (none / sprinkled / ~half / all), so a
  * GROUP BY key column can be entirely NULL (one collapsed group) or mixed.
  *
  * Columns take all ten of the engine's value types, [[ExprGen]]'s `GenTypes`.
  * DATE, TIMESTAMP and BINARY columns draw slices of `ExprGen`'s pools, so the
  * pairs those pools put side by side (two byte arrays of one content, two one
  * high bit apart, instants under a millisecond apart) collide here as keys.
  * Every BINARY cell is its own array, as in any vector the engine reads, so
  * equal content never shares an object. A DECIMAL column draws from
  * [[DecimalPools]], which keeps to values a `SUM` can add: see there.
  */
object DataGen {

  /** A logical dataset: a schema and its rows, each row an `Array[Any]` aligned
    * to schema field order with SQL NULL as `null`. Carries no partition state —
    * the physical layout is chosen per-mode via [[reshape]] and friends. */
  final case class Dataset(schema: Schema, rows: IndexedSeq[Array[Any]]) {

    def numRows: Int = rows.length

    /** Rebuild a [[MaterializedView]] with the given per-partition row counts
      * (which must sum to [[numRows]]; a zero entry is an empty partition) and a
      * batch size. */
    def reshape(partitionSizes: IndexedSeq[Int], batchRows: Int): MaterializedView = {
      require(partitionSizes.sum == rows.length,
        s"partitionSizes sum ${partitionSizes.sum} != numRows ${rows.length}")
      val bsz = math.max(1, batchRows)
      var offset = 0
      val parts: IndexedSeq[IndexedSeq[ColumnarBatch]] = partitionSizes.map { sz =>
        val slice = rows.slice(offset, offset + sz)
        offset += sz
        if (slice.isEmpty) IndexedSeq.empty[ColumnarBatch]
        else slice.grouped(bsz).map(g => buildBatch(schema, g)).toIndexedSeq
      }
      new MaterializedView(schema, parts)
    }

    /** One partition, standard batch size — the canonical baseline layout. */
    def singlePartition(batchRows: Int = ColumnarBatch.DefaultCapacity): MaterializedView =
      reshape(IndexedSeq(rows.length), batchRows)

    /** `numPartitions` near-even contiguous partitions. When `numRows <
      * numPartitions` the trailing partitions come out empty. */
    def evenPartitions(numPartitions: Int, batchRows: Int): MaterializedView =
      reshape(evenSizes(rows.length, numPartitions), batchRows)

    /** `numPartitions` partitions with rows placed only in odd-indexed slots, so
      * the layout has a leading empty partition, interior empties, and (for even
      * counts) a trailing empty — the empty-iterator edge at every position. */
    def withEmptyPartitions(numPartitions: Int, batchRows: Int): MaterializedView =
      reshape(emptyInterleavedSizes(rows.length, numPartitions), batchRows)

    override def toString: String = {
      val cols = schema.fields.map(f => s"${f.name}:${f.dataType}").mkString(", ")
      val preview = rows.take(6).map(renderRow).mkString("; ")
      val more = if (rows.length > 6) s" ... (+${rows.length - 6} rows)" else ""
      s"Dataset(numRows=${rows.length}, schema=[$cols], rows=[$preview$more])"
    }

    private def renderRow(row: Array[Any]): String =
      row.iterator.map(RowOracle.show).mkString("(", ",", ")")
  }

  // ---- schema + data ------------------------------------------------------

  /** Every column type: [[ExprGen]]'s `GenTypes`, so any shape one generator
    * builds over a type the other can as well. Draw with `ExprGen.pick`. */
  val ColumnTypes: Seq[DataType] = ExprGen.GenTypes

  /** Build one dataset from a single seed. Splits into independent sub-streams so
    * a change to one part of generation does not perturb the rest for a seed. */
  def generate(rng: Rng): Dataset = {
    val structRng = rng.split()
    val dataRng = rng.split()
    val schema = genSchema(structRng)
    val numRows = genRowCount(structRng)
    val specs = schema.fields.map(f => genColumnSpec(structRng, f.dataType))
    val rows = IndexedSeq.tabulate(numRows) { _ =>
      Array.tabulate[Any](schema.length) { c =>
        val spec = specs(c)
        if (dataRng.bool(spec.nullProb)) null else fresh(dataRng.oneOf(spec.domain))
      }
    }
    Dataset(schema, rows)
  }

  def genSchema(rng: Rng): Schema = {
    val numCols = rng.between(1, 4)
    Schema((0 until numCols).map(i => Field(s"c$i", ExprGen.pick(rng, ColumnTypes))).toVector)
  }

  /** A cell's own copy of a drawn value: a byte array is cloned, so two cells
    * of one content are two objects and a comparison by identity cannot pass
    * for one by content. Every other value is immutable and shared. Shared with
    * `MetaQueryGen`, which builds its relations from its own pools. */
  private[fuzz] def fresh(v: Any): Any = v match {
    case a: Array[Byte] => a.clone()
    case other => other
  }

  /** Row count, weighted small for a fast default run, but covering the 0-row and
    * 1-row boundaries and enough rows to fill several tiny batches per partition. */
  private def genRowCount(rng: Rng): Int = rng.weighted(
    (1, 0), // empty input
    (2, 1), // single row
    (6, 2), // a handful — heavy grouping/dedup
    (4, 3), // tens
    (1, 4)  // low hundreds (multi-batch even at DefaultCapacity-sized batches)
  ) match {
    case 0 => 0
    case 1 => 1
    case 2 => rng.between(2, 12)
    case 3 => rng.between(13, 60)
    case _ => rng.between(120, 300)
  }

  /** Per-column NULL density + value domain. */
  private final case class ColumnSpec(nullProb: Double, domain: IndexedSeq[Any])

  private def genColumnSpec(rng: Rng, dt: DataType): ColumnSpec = {
    // Mostly low null density; sometimes ~half; occasionally all-NULL (a column
    // that collapses to a single NULL group / dedups to one NULL row). The
    // weights ARE the distribution — `weighted` returns the chosen value, so
    // there is no tag to re-map (re-mapping a Double tag through an Int-literal
    // match silently collapsed most of this distribution onto all-NULL; see
    // `generatedCorpusCoversTheInterestingShapes`).
    ColumnSpec(rng.weighted((5, 0.0), (4, 0.15), (2, 0.5), (1, 1.0)), genDomain(rng, dt))
  }

  // Small, well-separated pools. The finite doubles/floats are spaced well above
  // the comparison tolerance so a Double group key never gets merged with a
  // neighbour by RowOracle's tolerant compare.
  private val DoublePool: IndexedSeq[Double] =
    IndexedSeq(0.0, 0.25, 0.5, 1.0, 2.0, -1.0, -2.5, 3.5, 10.0, 100.0)
  private val StringPool: IndexedSeq[String] =
    IndexedSeq("", "a", "b", "c", "d", "ab", "abc", "x", "y", "z")

  /** IEEE values a Float/Double column's domain can carry on top of
    * [[DoublePool]]. Ordered by how much of the engine they exercise, because
    * [[withSpecialDoubles]] takes a prefix: `NaN` is what raw IEEE comparisons
    * get wrong (every comparison against it is false in both directions), `-0.0`
    * is the value that is `== 0.0` yet sorts below it, and the infinities cover
    * the saturating end of arithmetic. `+0.0` is not here — [[DoublePool]]
    * already has it, and listing it again would only dilute the rest. Same pool
    * and same reasoning as `ExprGen.SpecialDoubles`, which widened the
    * EXPRESSION corpus; this widens the DATA corpus, which is what the
    * layout-differential properties aggregate over. */
  private[fuzz] val SpecialDoubles: IndexedSeq[Double] =
    IndexedSeq(Double.NaN, -0.0, Double.PositiveInfinity, Double.NegativeInfinity)

  /** Probability that a Float/Double column's domain carries IEEE specials at
    * all. A quarter of float columns is low enough to leave most of the corpus
    * on ordinary finite arithmetic and high enough that `MIN`/`MAX` over a
    * `NaN`-carrying column lands on a workable share of seeds — the shape is
    * only reached on ~7% of them to begin with. The joint rate is measured by
    * `ModeDifferentialFuzzTest.generatorReachesMinMaxOverIeeeSpecialValues` and
    * `MetamorphicFuzzTest.aggDecompositionReachesIeeeSpecialValues`, which are
    * what stop it drifting back to zero. */
  private val SpecialValueProb = 0.25

  /** A column's candidate non-null values: a small slice of a pool (or a narrow
    * integer window), guaranteeing collisions. */
  private def genDomain(rng: Rng, dt: DataType): IndexedSeq[Any] = dt match {
    case DataType.IntType =>
      val w = rng.oneOf(Seq(2, 3, 5, 8))
      val base = rng.oneOf(Seq(-2, -1, 0))
      (0 until w).map(i => (base + i): Any)
    case DataType.LongType =>
      val w = rng.oneOf(Seq(2, 3, 5, 8))
      val base = rng.oneOf(Seq(-2L, -1L, 0L))
      (0 until w).map(i => (base + i): Any)
    case DataType.FloatType =>
      withSpecialDoubles(rng, pickSubset(rng, DoublePool.map(_.toFloat)), _.toFloat)
    case DataType.DoubleType =>
      withSpecialDoubles(rng, pickSubset(rng, DoublePool), identity)
    case DataType.BooleanType =>
      rng.oneOf(Seq(
        IndexedSeq[Any](true, false),
        IndexedSeq[Any](true),
        IndexedSeq[Any](false)))
    case DataType.StringType =>
      pickSubset(rng, StringPool)
    case DataType.DateType => pickSubset(rng, ExprGen.DatePool.toIndexedSeq)
    case DataType.TimestampType => pickSubset(rng, ExprGen.TimestampPool.toIndexedSeq)
    case DataType.BinaryType => pickSubset(rng, ExprGen.BinaryPool.toIndexedSeq)
    case d: DataType.DecimalType => pickSubset(rng, DecimalPools(d))
    case other =>
      throw new IllegalArgumentException(s"DataGen.genDomain: unsupported $other")
  }

  /** A decimal column's candidate values, for each of `ExprGen.DecimalTypes`.
    * Each holds one value at several scales (`1`, `1.0`, `1.00`), which `=`
    * calls equal, side by side so a slice usually takes two of them, and a
    * negative; the widest also holds `1.000000000000000001` and `...002`, which
    * are different values and the same double as `1`.
    *
    * Every value fits its declared precision and scale, and as a double every
    * one is a small multiple of a quarter (`1.000000000000000001` is `1.0`).
    * That is for `SUM`, which keeps a decimal's type but adds in DOUBLE (a gap
    * `docs/gotchas.md` records): over these values the double sum is exact in
    * any order, so a SUM agrees across layouts to the last digit and the
    * result fits the declared scale, which a spill writes it at and refuses to
    * round to. A value off either would fail a spill the in-memory run passes,
    * or move a SUM with the order rows arrive in, which is the gap itself and
    * not the properties' business. The pools in `ExprGen` add nothing up, so
    * they run to the edge of their precision instead. */
  private[fuzz] val DecimalPools: Map[DataType, IndexedSeq[java.math.BigDecimal]] = Map[DataType, Seq[String]](
    DataType.DecimalType(9, 2) ->
      Seq("-2.50", "-0.25", "0", "0.00", "1", "1.0", "1.00", "1.25", "2.5", "100.75"),
    DataType.DecimalType(18, 4) ->
      Seq("-2.5", "-0.25", "0", "0.0000", "1", "1.0", "1.0000", "1.25", "2.5", "100.75"),
    DataType.DecimalType(38, 18) ->
      Seq("-2.5", "0", "1", "1.0", "1.000000000000000000", "1.000000000000000001", "1.000000000000000002",
        "1.25", "2.5")
  ).map { case (t, xs) => t -> xs.map(new java.math.BigDecimal(_)).toIndexedSeq }

  /** `base` plus a prefix of [[SpecialDoubles]], on [[SpecialValueProb]] of the
    * columns it is asked about. A prefix rather than a random subset so `NaN` —
    * the one value whose absence made `MIN`/`MAX` order-dependence invisible for
    * as long as it did — is present whenever specials are on at all, while the
    * infinities ride along less often. `narrow` maps a `Double` into the
    * column's own boxed type.
    *
    * Shared with `MetaQueryGen.payloadDomain`: the metamorphic and sharded
    * fuzzers build their relations from their own value pools, not from
    * [[generate]], so widening this generator alone would have left the
    * aggregate-decomposition relation — the one that drives `MinMaxState.merge`
    * — on an all-finite corpus. */
  private[fuzz] def withSpecialDoubles(
      rng: Rng, base: IndexedSeq[Any], narrow: Double => Any): IndexedSeq[Any] =
    if (!rng.bool(SpecialValueProb)) base
    else base ++ SpecialDoubles.take(rng.between(1, SpecialDoubles.length)).map(narrow)

  /** A non-empty contiguous slice of `pool`, sized for collisions (2..min(pool,6)). */
  private def pickSubset[A](rng: Rng, pool: IndexedSeq[A]): IndexedSeq[Any] = {
    val size = rng.between(2, math.min(pool.length, 6))
    val start = rng.between(0, pool.length - size)
    pool.slice(start, start + size).map(_.asInstanceOf[Any])
  }

  // ---- the values that matter ---------------------------------------------

  /** A pair of values a comparison of their type is easy to get wrong on: the
    * value half every type coverage guard asserts beside the shape half, since
    * a guard that saw only the shape would pass over a corpus that cannot break
    * it. */
  sealed abstract class ValueTag(val description: String) {
    override def toString: String = description
  }

  object ValueTag {
    /** Two distinct byte arrays of one content: equal to `=`, unequal by
      * identity. */
    case object BytesOfOneContent extends ValueTag("bytes of one content")
    /** Two byte arrays that differ only in one byte's high bit: they order one
      * way unsigned and the other way signed. */
    case object BytesOneHighBitApart extends ValueTag("bytes one high bit apart")
    /** One decimal value at two scales: equal to `=`, unequal to
      * `BigDecimal.equals`. */
    case object DecimalAtTwoScales extends ValueTag("a decimal at two scales")
    /** Two decimal values that are one double: unequal to `=`, equal through
      * `doubleValue`. */
    case object DecimalsOfOneDouble extends ValueTag("decimals of one double")
    /** Two instants under a millisecond apart: unequal to `=`, equal in
      * milliseconds. */
    case object InstantsUnderAMillisecondApart extends ValueTag("instants under a millisecond apart")

    /** The tags a value of `kind` (`ExprGen.kind`) can carry. */
    def of(kind: String): Seq[ValueTag] = kind match {
      case "binary" => Seq(BytesOfOneContent, BytesOneHighBitApart)
      case "decimal" => Seq(DecimalAtTwoScales, DecimalsOfOneDouble)
      case "timestamp" => Seq(InstantsUnderAMillisecondApart)
      case _ => Seq.empty
    }
  }

  /** The tags any two of `cells` carry, NULLs ignored. Linear in the cells, not
    * pairwise, so a guard can read every column of a corpus. */
  def valueTags(cells: Iterable[Any]): Set[ValueTag] = {
    import ValueTag._
    val values = cells.iterator.filter(_ != null).toVector
    val tags = Set.newBuilder[ValueTag]

    val arrays = values.collect { case a: Array[Byte] => a }
    if (arrays.nonEmpty) {
      val byContent = arrays.groupBy(new RowOracle.Bytes(_))
      if (byContent.values.exists(g => g.exists(_ ne g.head))) tags += BytesOfOneContent
      val contents = byContent.keySet
      val oneHighBitApart = contents.exists { k =>
        k.bytes.indices.exists { i =>
          val flipped = k.bytes.clone()
          flipped(i) = (flipped(i) ^ 0x80).toByte
          contents(new RowOracle.Bytes(flipped))
        }
      }
      if (oneHighBitApart) tags += BytesOneHighBitApart
    }

    val decimals = values.collect { case d: java.math.BigDecimal => d }
    if (decimals.nonEmpty) {
      if (decimals.groupBy(_.stripTrailingZeros).values.exists(_.map(_.scale).distinct.size > 1))
        tags += DecimalAtTwoScales
      if (decimals.groupBy(_.doubleValue).values.exists(_.map(_.stripTrailingZeros).distinct.size > 1))
        tags += DecimalsOfOneDouble
    }

    val micros = values.collect { case i: java.time.Instant => i.getEpochSecond * 1000000L + i.getNano / 1000L }
      .distinct.sorted
    if (micros.iterator.zip(micros.iterator.drop(1)).exists { case (x, y) => y - x < 1000L })
      tags += InstantsUnderAMillisecondApart

    tags.result()
  }

  /** The tags two values carry, as [[valueTags]] reads them. */
  def pairTags(a: Any, b: Any): Set[ValueTag] = valueTags(Seq(a, b))

  // ---- layout helpers -----------------------------------------------------

  /** `k` near-even contiguous sizes summing to `total`. */
  private def evenSizes(total: Int, k: Int): IndexedSeq[Int] = {
    require(k >= 1, s"k must be >= 1, got $k")
    val base = total / k
    val rem = total % k
    IndexedSeq.tabulate(k)(i => base + (if (i < rem) 1 else 0))
  }

  /** `k` sizes summing to `total`, with rows placed only in odd-indexed slots
    * (falling back to slot 0 if there are none), so even slots are empty. */
  private def emptyInterleavedSizes(total: Int, k: Int): IndexedSeq[Int] = {
    require(k >= 1, s"k must be >= 1, got $k")
    val active = (1 until k by 2).toVector match {
      case v if v.nonEmpty => v
      case _ => Vector(0)
    }
    val even = evenSizes(total, active.length)
    val sizes = Array.fill(k)(0)
    active.iterator.zipWithIndex.foreach { case (p, i) => sizes(p) = even(i) }
    sizes.toIndexedSeq
  }

  /** Materialize a slice of logical rows into one [[ColumnarBatch]]. */
  private def buildBatch(schema: Schema, rows: Seq[Array[Any]]): ColumnarBatch = {
    val n = rows.length
    val cap = math.max(1, n)
    val cols = new Array[ColumnVector](schema.length)
    var c = 0
    while (c < schema.length) {
      val col = ColumnVector.allocate(schema.fields(c).dataType, cap)
      var r = 0
      while (r < n) {
        val v = rows(r)(c)
        if (v == null) col.setNull(r) else col.setBoxed(r, v)
        r += 1
      }
      cols(c) = col
      c += 1
    }
    ColumnarBatch.fromColumns(schema, cols, n)
  }
}
