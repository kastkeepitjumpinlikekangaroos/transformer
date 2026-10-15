package com.transformer.fuzz

import com.transformer.fuzz.ParquetGen.{Kind, ParquetCase, Pred}
import com.transformer.fuzz.oracle.ParquetDifferential
import com.transformer.read.parquet.ExternalParquet.Layout
import org.apache.parquet.column.ParquetProperties.WriterVersion
import org.apache.parquet.hadoop.metadata.CompressionCodecName
import org.junit.Assert._
import org.junit.Test

import java.nio.file.Files
import java.time.temporal.ChronoUnit
import scala.collection.mutable

/** Property-based gate over the parquet reader against files it did not
  * write: parquet-mr writes every file ([[ParquetGen]]), and
  * [[ParquetDifferential]] holds `ParquetReader` to what the format says the
  * file holds and parquet-mr to the same reading.
  *
  * `parquet_roundtrip_test` writes its fixtures with our own writer, so the
  * reader and writer agree with each other while both can disagree with the
  * format, which is how a MILLIS timestamp read 1000x too small. The hand
  * fixtures in `parquet_external_format_test` fixed that for the cases someone
  * thought of; this generates the rest: every layout parquet allows for each
  * engine type, in globs that store one column several ways, cut into row
  * groups anywhere and laid out every way parquet-mr can lay them out, read
  * with every batch size and partition target, through projections and
  * pushed-down predicates.
  *
  * Budgets are small (each case writes and reads real files) and scale with
  * `-Dfuzz.seeds=N` / `FUZZ_SEEDS=N` against `parquet_fuzz_campaign`.
  */
class ParquetFuzzTest {
  import ParquetFuzzTest.{Shapes, compareCodePoints}

  /** Default budget. A case writes one to three files of a few row groups and
    * reads them back several times. */
  private val DefaultParquetSeeds: Int = 200

  @Test def fuzzExternalParquet(): Unit =
    Props.forAll[ParquetCase](
      name = "parquet-differential",
      gen = ParquetGen.generate,
      shrink = Shrinker.parquetCase,
      count = Props.seedCountOr(DefaultParquetSeeds)
    ) { c => ParquetDifferential.check(c); () }

  // ---- corpus health --------------------------------------------------------


  private def kindLabel(k: Kind): String = k match {
    case Kind.Int32(bits, signed) => s"int32${bits.fold("")(b => s"($b,$signed)")}"
    case Kind.Int64(annotated) => if (annotated) "int64(64,true)" else "int64"
    case Kind.Timestamp(unit, utc) => s"timestamp($unit,$utc)"
    case Kind.Text(logical) => logical.toLowerCase
    case Kind.Bytes(logical, fixed) => logical.fold(if (fixed.isDefined) "fixed" else "binary")(_.toLowerCase)
    case Kind.Decimal(_, _, layout, _) => s"decimal-$layout"
    case other => other.toString
  }

  /** Every layout the generator can draw, so a kind dropping out of
    * `drawKind` fails the guard rather than going quiet. */
  private val AllKinds: Set[String] = Set(
    "boolean", "int32", "int32(8,true)", "int32(16,true)", "int32(32,true)", "int32(8,false)", "int32(16,false)",
    "int32 (INTEGER(32,false))", "int64", "int64(64,true)", "int64 (INTEGER(64,false))", "int32 (DATE)",
    "timestamp(Millis,true)", "timestamp(Millis,false)", "timestamp(Micros,true)", "timestamp(Micros,false)",
    "timestamp(Nanos,true)", "timestamp(Nanos,false)", "int96", "float", "fixed_len_byte_array(2) (FLOAT16)",
    "double", "string", "enum", "json", "binary", "bson", "fixed", "uuid",
    "decimal-int32", "decimal-int64", "decimal-fixed", "decimal-binary")

  /** The values a reader gets wrong in each kind, named when a column of the
    * case holds one. */
  private def valueTags(k: Kind, v: Any): Seq[String] = (k, v) match {
    case (Kind.Float32, f: Float) =>
      Seq(if (f.isNaN) Some("float NaN") else None,
        if (java.lang.Float.floatToRawIntBits(f) == java.lang.Float.floatToRawIntBits(-0.0f)) Some("float -0.0") else None).flatten
    case (Kind.Float64, d: Double) =>
      Seq(if (d.isNaN) Some("double NaN") else None,
        if (java.lang.Double.doubleToRawLongBits(d) == java.lang.Double.doubleToRawLongBits(-0.0)) Some("double -0.0") else None).flatten
    case (Kind.Float16, b: Array[Byte]) =>
      val bits = (b(0) & 0xff) | ((b(1) & 0xff) << 8)
      Seq(if ((bits & 0x7c00) == 0 && (bits & 0x3ff) != 0) Some("half subnormal") else None,
        if ((bits & 0x7c00) == 0x7c00 && (bits & 0x3ff) != 0) Some("half NaN") else None).flatten
    case (Kind.UInt32, i: Int) if i < 0 => Seq("uint32 past Int.MaxValue")
    case (Kind.UInt64, l: Long) if l < 0 => Seq("uint64 past Long.MaxValue")
    case (Kind.Timestamp(ChronoUnit.NANOS, _), l: Long) if l < 0 && l % 1000 != 0 => Seq("nanos before 1970 off a microsecond")
    case (Kind.Timestamp(ChronoUnit.MILLIS, _), l: Long) if l < 0 => Seq("millis before 1970")
    case (Kind.Date, d: Int) if d < -719162 => Seq("date before year 1")
    case (Kind.Decimal(p, _, _, _), _) =>
      val e = k.expected(v).asInstanceOf[java.math.BigDecimal]
      if (e.unscaledValue.abs == java.math.BigInteger.TEN.pow(p).subtract(java.math.BigInteger.ONE))
        Seq("decimal at its precision's end")
      else if (e.signum < 0) Seq("negative decimal") else Nil
    case (Kind.Text(_), s: String) if s.codePoints.anyMatch(_ > 0xffff) => Seq("astral text")
    case (Kind.Bytes(_, _), b: Array[Byte]) if b.exists(_ < 0) => Seq("bytes with a high bit")
    case _ => Nil
  }

  private def shapes(c: ParquetCase): Shapes = {
    val files = c.files
    val kindsByName = files.flatMap(_.columns).groupBy(_.name)
    def straddles(f: ParquetGen.PqFile): Boolean = f.groups.sliding(2).exists {
      case Seq(a, b) => f.columns.indices.exists(i => a.rows.last(i) == null && b.rows.head(i) == null)
      case _ => false
    }
    val comparable = Set[com.transformer.core.DataType](com.transformer.core.DataType.IntType,
      com.transformer.core.DataType.LongType, com.transformer.core.DataType.FloatType,
      com.transformer.core.DataType.DoubleType, com.transformer.core.DataType.DateType,
      com.transformer.core.DataType.StringType, com.transformer.core.DataType.BooleanType,
      com.transformer.core.DataType.BinaryType)
    def cmpColumns(p: Pred): Set[String] = p match {
      case Pred.Cmp(n, _, _) => Set(n)
      case Pred.In(n, _, _) => Set(n)
      case Pred.Not(q) => cmpColumns(q)
      case Pred.And(a, b) => cmpColumns(a) ++ cmpColumns(b)
      case Pred.Or(a, b) => cmpColumns(a) ++ cmpColumns(b)
      case _ => Set.empty
    }
    def negativeBounds(p: Pred): Seq[String] = p match {
      case Pred.Cmp(n, op, lit: Int) if lit < 0 && Set("<", "<=", ">", ">=").contains(op) => Seq(n)
      case Pred.Not(q) => negativeBounds(q)
      case Pred.And(a, b) => negativeBounds(a) ++ negativeBounds(b)
      case Pred.Or(a, b) => negativeBounds(a) ++ negativeBounds(b)
      case _ => Nil
    }
    def stringRanges(p: Pred): Seq[(String, String)] = p match {
      case Pred.Cmp(n, op, lit: String) if Set("<", "<=", ">", ">=").contains(op) => Seq(n -> lit)
      case Pred.Not(q) => stringRanges(q)
      case Pred.And(a, b) => stringRanges(a) ++ stringRanges(b)
      case Pred.Or(a, b) => stringRanges(a) ++ stringRanges(b)
      case _ => Nil
    }
    val groups = files.flatMap(_.groups)
    val floatColumnsWithSplit = files.exists(f => f.groups.exists(_.layout.byteStreamSplit) &&
      f.columns.exists(col => col.kind == Kind.Float32 || col.kind == Kind.Float64))
    Shapes(
      kinds = files.flatMap(_.columns.map(col => kindLabel(col.kind))).toSet,
      multiGroupFile = files.exists(_.groups.length > 1),
      nullAcrossGroups = files.exists(straddles),
      oneRowGroup = groups.exists(_.rows.length == 1),
      emptyFile = files.exists(_.groups.isEmpty),
      glob = files.length > 1,
      columnStoredTwoWays = kindsByName.exists(_._2.map(_.kind).distinct.length > 1),
      reorderedColumns = files.drop(1).exists(f => f.columns.map(_.name).filter(c.viewColumns.contains) != c.viewColumns),
      extraColumn = files.drop(1).exists(_.columns.exists(col => !c.viewColumns.contains(col.name))),
      requiredAndOptional = kindsByName.exists(_._2.map(_.required).distinct.length > 1),
      plainEncoding = groups.exists(!_.layout.dictionary),
      dictionaryOverflow = groups.exists(g => g.layout.dictionary && g.layout.dictionaryPageSize < 1024 && g.rows.length > 20),
      manyPagesPerGroup = groups.exists(g => g.layout.pageRowLimit < g.rows.length),
      pagesV2 = groups.exists(_.layout.writerVersion == WriterVersion.PARQUET_2_0),
      byteStreamSplitFloats = floatColumnsWithSplit,
      codecs = groups.map(_.layout.codec).toSet,
      unreadable = files.exists(f => f.groups.exists(_.rows.exists(row =>
        row.indices.exists(i => row(i) != null && f.columns(i).kind.unreadable(row(i)))))),
      pushdownOnComparable = c.read.pushdown.exists(p =>
        cmpColumns(p).exists(n => comparable.contains(files.head.columns.find(_.name == n).get.kind.engineType))),
      negativeBoundOnUnsigned = c.read.pushdown.exists(p => negativeBounds(p).exists(n =>
        files.exists(_.columns.exists(col => col.name == n && (col.kind match {
          case Kind.Int32(Some(b), false) => b < 32
          case _ => false
        }))))),
      stringRangeOrderedApart = c.read.pushdown.exists(p => stringRanges(p).exists { case (n, lit) =>
        files.exists { f =>
          val i = f.columns.indexWhere(_.name == n)
          f.groups.exists(_.rows.exists(row => row(i) match {
            case s: String => Integer.signum(s.compareTo(lit)) != Integer.signum(compareCodePoints(s, lit))
            case _ => false
          }))
        }
      }),
      values = files.flatMap(f => f.groups.flatMap(_.rows.flatMap(row => row.indices.flatMap(i =>
        if (row(i) == null) Nil else valueTags(f.columns(i).kind, row(i)))))).toSet)
  }

  /** Coverage guard, read off generated cases alone. The property is only as
    * good as what it puts in front of the reader: every layout of every type,
    * the row-group shapes partition and decoding bugs live in (a NULL either
    * side of a boundary, a one-row group, an empty file), the globs that store
    * one column several ways, every page and encoding choice, and the values
    * each type is misread on. Each count is printed beside its bound. */
  @Test def generatorReachesEveryLayoutShapeAndValue(): Unit = {
    val n = Props.seedCountOr(4000)
    val kindCounts = mutable.Map.empty[String, Int].withDefaultValue(0)
    val shapeCounts = mutable.Map.empty[String, Int].withDefaultValue(0)
    val valueCounts = mutable.Map.empty[String, Int].withDefaultValue(0)
    var i = 0
    while (i < n) {
      val s = shapes(ParquetGen.generate(new Rng(Props.baseSeed + i)))
      s.kinds.foreach(k => kindCounts(k) += 1)
      s.values.foreach(v => valueCounts(v) += 1)
      s.codecs.foreach(codec => shapeCounts(s"codec $codec") += 1)
      Seq(
        "file of several row groups" -> s.multiGroupFile, "NULLs either side of a group boundary" -> s.nullAcrossGroups,
        "one-row group" -> s.oneRowGroup, "empty file" -> s.emptyFile, "glob" -> s.glob,
        "column stored two ways in a glob" -> s.columnStoredTwoWays, "later file reorders columns" -> s.reorderedColumns,
        "later file has an extra column" -> s.extraColumn, "column required in one file only" -> s.requiredAndOptional,
        "plain encoding" -> s.plainEncoding, "dictionary overflowing into plain pages" -> s.dictionaryOverflow,
        "several pages per row group" -> s.manyPagesPerGroup, "data pages v2" -> s.pagesV2,
        "byte-stream-split floats" -> s.byteStreamSplitFloats, "unreadable MILLIS value" -> s.unreadable,
        "pushdown on a comparable column" -> s.pushdownOnComparable,
        "string range ordered apart by UTF-8 and UTF-16" -> s.stringRangeOrderedApart,
        "negative bound on an unsigned 8- or 16-bit column" -> s.negativeBoundOnUnsigned
      ).foreach { case (label, hit) => if (hit) shapeCounts(label) += 1 }
      i += 1
    }
    println(s"[parquet-differential] over $n seeds:")
    (AllKinds.toSeq.sorted ++ kindCounts.keys.filterNot(AllKinds).toSeq).foreach(k => println(f"  kind  ${kindCounts(k)}%6d  $k"))
    shapeCounts.toSeq.sortBy(_._1).foreach { case (k, v) => println(f"  shape ${v}%6d  $k") }
    valueCounts.toSeq.sortBy(_._1).foreach { case (k, v) => println(f"  value ${v}%6d  $k") }

    assertEquals("a kind the guard does not know", Set.empty, kindCounts.keySet.toSet -- AllKinds)
    AllKinds.foreach(k => assertTrue(s"kind $k reached on ${kindCounts(k)} of $n seeds", kindCounts(k) >= n / 200))
    // Measured over 20000 seeds, each 4 to 60 times its bound here. The
    // rarest are the shapes of the two pushdown defects this property found:
    // a string range whose literal and a held value order apart in UTF-8 and
    // UTF-16 (80), and a negative bound on an unsigned 8- or 16-bit column
    // (46); then an unreadable MILLIS value (326).
    Seq("file of several row groups" -> n / 100, "NULLs either side of a group boundary" -> n / 100,
      "one-row group" -> n / 100, "empty file" -> n / 100, "glob" -> n / 100,
      "column stored two ways in a glob" -> n / 100, "later file reorders columns" -> n / 100,
      "later file has an extra column" -> n / 100, "column required in one file only" -> n / 100,
      "plain encoding" -> n / 100, "dictionary overflowing into plain pages" -> n / 100,
      "several pages per row group" -> n / 100, "data pages v2" -> n / 100, "byte-stream-split floats" -> n / 100,
      "unreadable MILLIS value" -> n / 200, "pushdown on a comparable column" -> n / 100,
      "string range ordered apart by UTF-8 and UTF-16" -> n / 1000,
      "negative bound on an unsigned 8- or 16-bit column" -> n / 2000
    ).foreach { case (label, bound) =>
      assertTrue(s"$label on ${shapeCounts(label)} of $n seeds, under $bound", shapeCounts(label) >= bound)
    }
    Seq(CompressionCodecName.UNCOMPRESSED, CompressionCodecName.SNAPPY, CompressionCodecName.GZIP,
      CompressionCodecName.ZSTD, CompressionCodecName.LZ4_RAW).foreach { codec =>
      assertTrue(s"codec $codec on ${shapeCounts(s"codec $codec")} of $n seeds", shapeCounts(s"codec $codec") >= n / 100)
    }
    Seq("float NaN", "float -0.0", "double NaN", "double -0.0", "half subnormal", "half NaN", "uint32 past Int.MaxValue",
      "uint64 past Long.MaxValue", "nanos before 1970 off a microsecond", "millis before 1970", "date before year 1",
      "decimal at its precision's end", "negative decimal", "astral text", "bytes with a high bit").foreach { v =>
      assertTrue(s"$v on ${valueCounts(v)} of $n seeds", valueCounts(v) >= n / 200)
    }
  }

  /** Coverage guard over real reads: a share of cases must read a projection
    * through partitions that start past the first row group, span several
    * partitions, and offer the reader a predicate it accepts, some of which
    * skip row groups (a pushdown check that never sees a skipped group checks
    * nothing). And a case holding a value no engine value holds (a MILLIS
    * value past a long of microseconds) must be reported as a failed read,
    * which is the only thing such a case checks: those cases are picked out
    * of the corpus by what they hold, since about one in sixty has one. */
  /** Whether a column of the view holds a value no engine value holds (a
    * later file's extra column is never read). */
  private def unreadableInView(c: ParquetCase): Boolean = c.files.exists(f => f.groups.exists(_.rows.exists(row =>
    row.indices.exists(i => c.viewColumns.contains(f.columns(i).name) && row(i) != null &&
      f.columns(i).kind.unreadable(row(i))))))

  @Test def readsReachLaterGroupsSkippedGroupsAndFailures(): Unit = {
    val n = Props.seedCountOr(100)
    var projectedLater, pushed, skipped, multiPartition = 0
    var i = 0
    while (i < n) {
      val r = ParquetDifferential.check(ParquetGen.generate(new Rng(Props.baseSeed + 100000 + i)))
      if (r.projectedPastFirstGroup) projectedLater += 1
      if (r.pushed) pushed += 1
      if (r.pushdownSkippedRows) skipped += 1
      if (r.partitions > 1) multiPartition += 1
      i += 1
    }
    println(s"[parquet-differential] over $n read cases: projectedPastFirstGroup=$projectedLater " +
      s"pushed=$pushed pushdownSkippedRows=$skipped multiPartition=$multiPartition")
    // Measured over 200 cases: 11, 66, 23 and 92.
    assertTrue(s"projectedPastFirstGroup=$projectedLater of $n", projectedLater >= n / 50)
    assertTrue(s"pushed=$pushed of $n", pushed >= n / 10)
    assertTrue(s"pushdownSkippedRows=$skipped of $n", skipped >= n / 50)
    assertTrue(s"multiPartition=$multiPartition of $n", multiPartition >= n / 5)

    val unreadable = Iterator.from(0).map(k => ParquetGen.generate(new Rng(Props.baseSeed + 200000 + k)))
      .take(2000).filter(unreadableInView).take(5).toVector
    assertEquals("cases holding an unreadable value in 2000 seeds", 5, unreadable.length)
    unreadable.foreach(c => assertTrue(s"not reported as a failed read: $c", ParquetDifferential.check(c).failedAsExpected))
  }

  // ---- the oracle itself -----------------------------------------------------

  /** The oracle must report a reader that misreads a value. The files are
    * written from one case and checked against a second that differs in one
    * stored value, which is what a reader decoding that value wrongly looks
    * like from outside. */
  @Test def theOracleReportsAMisreadValue(): Unit = {
    var seed = 0L
    var checked = 0
    while (checked < 20 && seed < 2000) {
      val c = ParquetGen.generate(new Rng(seed))
      val anyUnreadable = c.files.exists(f => f.groups.exists(_.rows.exists(row =>
        row.indices.exists(i => row(i) != null && f.columns(i).kind.unreadable(row(i))))))
      val target = if (anyUnreadable) None else for {
        (f, fi) <- c.files.zipWithIndex.find(_._1.numRows > 0)
        (g, gi) <- f.groups.zipWithIndex.find(_._2 >= 0)
        col <- f.columns.indices.find(i => c.viewColumns.contains(f.columns(i).name) && g.rows.head(i) != null &&
          !f.columns(i).kind.unreadable(g.rows.head(i)))
      } yield (fi, gi, col)
      target.foreach { case (fi, gi, col) =>
        val f = c.files(fi)
        val g = f.groups(gi)
        val kind = f.columns(col).kind
        val original = g.rows.head(col)
        Iterator.continually(kind.draw(new Rng(seed * 31 + 7))).take(50).find { v =>
          !kind.unreadable(v) && !RowOracle.identical(kind.expected(v), kind.expected(original))
        }.foreach { other =>
          val changed = c.copy(files = c.files.updated(fi, f.copy(groups = f.groups.updated(gi,
            g.copy(rows = g.rows.updated(0, g.rows.head.updated(col, other)))))))
          val dir = Files.createTempDirectory("parquet-oracle-")
          try {
            ParquetGen.writeTo(dir, c)
            val reported = try { ParquetDifferential.checkReader(dir, changed); false } catch {
              case _: AssertionError => true
            }
            assertTrue(s"seed $seed: the oracle missed ${kind} read as ${ParquetGen.show(original)} " +
              s"where ${ParquetGen.show(other)} was expected", reported)
            checked += 1
          } finally ParquetDifferential.deleteRecursively(dir)
        }
      }
      seed += 1
    }
    assertTrue(s"only $checked cases had a value to change", checked >= 20)
  }

  /** The seed is the repro key: the same seed must rebuild the same case. */
  @Test def sameSeedReproduces(): Unit = {
    val a = ParquetGen.generate(new Rng(4242L))
    val b = ParquetGen.generate(new Rng(4242L))
    assertEquals(a.toString, b.toString)
  }

  /** The shrinker must terminate and strictly reduce, or `Props.minimize`
    * loops until its step cap. */
  @Test def shrinkerTerminatesAndReduces(): Unit = {
    var i = 0
    while (i < 60) {
      val c = ParquetGen.generate(new Rng(i.toLong))
      val m = Shrinker.parquetSize(c)
      Shrinker.parquetCase(c).take(80).foreach { s =>
        assertTrue(s"shrink did not reduce: $m -> ${Shrinker.parquetSize(s)}", Shrinker.parquetSize(s) < m)
        assertTrue(s"shrink left no view column: $s", s.viewColumns.nonEmpty)
        s.read.projection.foreach(p => assertTrue(s"projection names a dropped column: $s", p.forall(s.viewColumns.contains)))
        s.read.pushdown.foreach(p => assertTrue(s"predicate names a dropped column: $s",
          ParquetGen.columnsOf(p).forall(s.viewColumns.contains)))
      }
      i += 1
    }
  }

  /** The layout any group may take is a real choice: parquet-mr honours each
    * knob, which the guard above would not notice if it ignored one. Pinned on
    * the two that change the footer: a row limit of one makes a page per row,
    * and the joined file keeps every group's size. */
  @Test def externalParquetCutsRowGroupsExactly(): Unit = {
    val dir = Files.createTempDirectory("parquet-groups-")
    try {
      val schema = com.transformer.read.parquet.ExternalParquet.schema("message m { optional int32 a; }")
      val path = dir.resolve("g.parquet")
      com.transformer.read.parquet.ExternalParquet.writeRowGroups(path, schema, Seq(
        (Seq(Seq(1)), Layout()),
        (Seq(Seq(2), Seq(null), Seq(4)), Layout(pageRowLimit = 1, dictionary = false)),
        (Seq(Seq(null), Seq(6)), Layout(writerVersion = WriterVersion.PARQUET_2_0, codec = CompressionCodecName.ZSTD))))
      assertEquals(Seq(1L, 3L, 2L), com.transformer.read.parquet.ExternalParquet.rowGroupSizes(path))
      assertEquals(Seq("1", "2", null, "4", null, "6"), com.transformer.read.parquet.ExternalParquet.read(path).map(_.head))
      val empty = dir.resolve("e.parquet")
      com.transformer.read.parquet.ExternalParquet.writeRowGroups(empty, schema, Seq.empty)
      assertEquals(Seq.empty, com.transformer.read.parquet.ExternalParquet.rowGroupSizes(empty))
    } finally ParquetDifferential.deleteRecursively(dir)
  }
}

object ParquetFuzzTest {

  /** Two strings in code point order, which is UTF-8 byte order: the order a
    * parquet file's statistics are in. */
  def compareCodePoints(a: String, b: String): Int = {
    val x = a.codePoints.toArray
    val y = b.codePoints.toArray
    java.util.Arrays.compare(x, y)
  }
  /** What a generated case puts in front of the reader, read off the case
    * alone (no files are written), for the guards below. */
  final case class Shapes(
      kinds: Set[String],
      multiGroupFile: Boolean,
      nullAcrossGroups: Boolean,
      oneRowGroup: Boolean,
      emptyFile: Boolean,
      glob: Boolean,
      columnStoredTwoWays: Boolean,
      reorderedColumns: Boolean,
      extraColumn: Boolean,
      requiredAndOptional: Boolean,
      plainEncoding: Boolean,
      dictionaryOverflow: Boolean,
      manyPagesPerGroup: Boolean,
      pagesV2: Boolean,
      byteStreamSplitFloats: Boolean,
      codecs: Set[CompressionCodecName],
      unreadable: Boolean,
      pushdownOnComparable: Boolean,
      stringRangeOrderedApart: Boolean,
      negativeBoundOnUnsigned: Boolean,
      values: Set[String])
}
