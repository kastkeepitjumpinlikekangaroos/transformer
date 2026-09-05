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
  * Column types are exactly [[ExprGen]]'s set — Int/Long/Float/Double/Boolean/
  * String — so every column can be referenced by a generated predicate or
  * aggregate argument. Date/Timestamp/Decimal are intentionally out of scope for
  * this property (see docs/extending.md).
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
      row.iterator.map(v => if (v == null) "NULL" else v.toString).mkString("(", ",", ")")
  }

  // ---- schema + data ------------------------------------------------------

  /** All supported column types — identical to [[ExprGen]]'s `GenTypes` so any
    * column can appear inside a generated expression. */
  val ColumnTypes: Seq[DataType] = Seq(
    DataType.IntType, DataType.LongType, DataType.FloatType, DataType.DoubleType,
    DataType.BooleanType, DataType.StringType)

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
        if (dataRng.bool(spec.nullProb)) null else dataRng.oneOf(spec.domain)
      }
    }
    Dataset(schema, rows)
  }

  def genSchema(rng: Rng): Schema = {
    val numCols = rng.between(1, 4)
    Schema((0 until numCols).map(i => Field(s"c$i", rng.oneOf(ColumnTypes))).toVector)
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

  // Small, well-separated pools. Doubles/floats are spaced well above the
  // comparison tolerance so a Double group key never gets merged with a
  // neighbour by RowOracle's tolerant compare.
  private val DoublePool: IndexedSeq[Double] =
    IndexedSeq(0.0, 0.25, 0.5, 1.0, 2.0, -1.0, -2.5, 3.5, 10.0, 100.0)
  private val StringPool: IndexedSeq[String] =
    IndexedSeq("", "a", "b", "c", "d", "ab", "abc", "x", "y", "z")

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
      pickSubset(rng, DoublePool.map(_.toFloat))
    case DataType.DoubleType =>
      pickSubset(rng, DoublePool)
    case DataType.BooleanType =>
      rng.oneOf(Seq(
        IndexedSeq[Any](true, false),
        IndexedSeq[Any](true),
        IndexedSeq[Any](false)))
    case DataType.StringType =>
      pickSubset(rng, StringPool)
    case other =>
      throw new IllegalArgumentException(s"DataGen.genDomain: unsupported $other")
  }

  /** A non-empty contiguous slice of `pool`, sized for collisions (2..min(pool,6)). */
  private def pickSubset[A](rng: Rng, pool: IndexedSeq[A]): IndexedSeq[Any] = {
    val size = rng.between(2, math.min(pool.length, 6))
    val start = rng.between(0, pool.length - size)
    pool.slice(start, start + size).map(_.asInstanceOf[Any])
  }

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
