package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.CsvTextGen
import com.transformer.fuzz.CsvTextGen.{InferenceCase, Layout}
import com.transformer.fuzz.RowOracle
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}

import java.nio.file.{Files, Path}
import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.time.temporal.ChronoUnit
import scala.util.Try

/** The inference round trip: data written to CSV and read back with its types
  * inferred, then written and inferred again, and again. A glob's later files
  * may write the columns in other orders, their headers' names in another
  * case ([[CsvTextGen.Layout]]): the glob is read by each file's header's
  * names, in the first file's order (plans/testing/04, finding 3), so every
  * expectation below holds by name, whatever order a file writes.
  *
  * '''The first read''' is held to what inference promises, restated here with
  * a grammar of this file's own:
  *
  *   - each column's type is the narrowest every sampled field reads as (the
  *     first `sampleRows` rows of the files, in order): numbers widen from INT to
  *     BIGINT to DECIMAL(38,0) to DOUBLE, a DATE widens to a TIMESTAMP, and
  *     any other mix is a STRING;
  *   - every value read is what its text says under that type (`007` is 7,
  *   `2024-01-01` in a TIMESTAMP column its midnight), so inference never
  *     picks a type a sampled text cannot be read as, which the old ladder did
  *     (`1.5` read as the BOOLEAN false);
  *   - a field past the sample that does not read as its column's type fails
  *     the read, naming its file, column and text.
  *
  * '''Then a fixed point.''' The first read's rows, written again and read
  * again, and once more: the second and third reads agree, schema and rows.
  * Normalizing is allowed (`007` is written back as `7`); changing again is
  * not. Which columns changed type or value on the first pass is reported, the
  * table the README documents.
  */
object InferenceRoundTrip {

  /** One column's first pass: its written type, its inferred one, and whether
    * any value read back differently from the one written. */
  final case class Step(from: DataType, to: DataType, changedValue: Boolean)

  final case class Report(steps: Seq[Step], failedAsExpected: Boolean, fixedAfterOnePass: Boolean)

  def check(c: InferenceCase): Report = {
    val dir = Files.createTempDirectory("csv-infer-fuzz-")
    try {
      val options = CsvOptions.fromMap(c.format.options).copy(inferenceSampleRows = c.sampleRows)
      val written = dir.resolve("pass1")
      val layouts = Layout(c.schema.fields.indices.toVector, upper = false) +: c.layouts
      write(written, c.schema, slices(c.rows, c.files), c.format, layouts)

      // The text of every field as written, read with no inference at all,
      // which reads a file's fields by their position in it, each put back
      // in the schema's order.
      val asText = CsvReader.fromPath(written.toString, CsvOptions.forWrittenFile(c.format.options,
        Schema(c.schema.fields.map(_.copy(dataType = DataType.StringType)))))
      val textByFile = (0 until asText.numPartitions).map { p =>
        val order = layouts(p).order
        CsvRoundTrip.rowsOf(single(asText, p)).map(row => c.schema.fields.indices.map(i => row(order.indexOf(i))).toVector)
      }
      val sample = textByFile.flatten.take(c.sampleRows)
      val types = c.schema.fields.indices.map { i =>
        sample.flatMap(row => Option(row(i).asInstanceOf[String]).filter(_.nonEmpty).map(classify)).reduceOption(join)
          .getOrElse(DataType.StringType)
      }

      val first = CsvReader.fromPath(written.toString, options)
      val inferred = first.schema.fields.map(_.dataType)
      if (inferred != types.toVector) fail(s"inferred ${inferred.mkString(", ")} where the sample reads as ${types.mkString(", ")}")

      // The first field, in reading order, that does not read as its type.
      val unreadable = textByFile.iterator.zipWithIndex.flatMap { case (rows, f) =>
        rows.iterator.flatMap(row => row.indices.find(i => denote(row(i).asInstanceOf[String], types(i)).isLeft)
          .map(i => (f, c.schema.fields(i).name, row(i).asInstanceOf[String], types(i))))
      }.nextOption()
      unreadable match {
        case Some((f, column, text, t)) =>
          val msg =
            try { CsvRoundTrip.rowsOf(first); fail(s"read '$text' in column '$column' as ${t} without failing") }
            catch {
              case e: AssertionError => throw e
              case e: IllegalArgumentException => String.valueOf(e.getMessage)
            }
          val file = f"part-$f%05d.csv"
          val excerpt = if (text.length <= 80) text else text.take(80) + "..."
          if (!(msg.contains(file) && msg.contains(s"'$column'") && msg.contains(s"'$excerpt'") && msg.contains(t.name) &&
              msg.contains("inferred")))
            fail(s"the read failed without naming $file, column '$column', '$text' and ${t.name}: $msg")
          Report(Nil, failedAsExpected = true, fixedAfterOnePass = false)

        case None =>
          val firstRows = CsvRoundTrip.rowsOf(first)
          val wantRows = textByFile.flatten.map(row => row.indices.map(i => denote(row(i).asInstanceOf[String], types(i)).toOption.get).toVector).toVector
          compare("the first read", first.schema, wantRows, firstRows)
          val steps = c.schema.fields.indices.map { i =>
            Step(c.schema.fields(i).dataType, types(i),
              c.rows.indices.exists(r => !RowOracle.identical(c.rows(r)(i), firstRows(r)(i))))
          }

          // Written again, read again; written once more, read once more.
          val second = rewrite(dir.resolve("pass2"), first, c, options)
          val third = rewrite(dir.resolve("pass3"), second, c, options)
          if (second.schema != third.schema)
            fail(s"inference keeps changing: the second read is ${second.schema}, the third ${third.schema}")
          compare("the third read, against the second", second.schema, CsvRoundTrip.rowsOf(second), CsvRoundTrip.rowsOf(third))
          val fixedAfterOne = second.schema == first.schema &&
            sameRows(firstRows, CsvRoundTrip.rowsOf(second))
          Report(steps, failedAsExpected = false, fixedAfterOne)
      }
    } finally CsvRoundTrip.deleteRecursively(dir)
  }

  private def rewrite(to: Path, from: CsvReader, c: InferenceCase, options: CsvOptions): CsvReader = {
    val parts = (0 until from.numPartitions).map(p => from.readPartition(p).toVector).map(_.iterator)
    CsvWriter.writePartitioned(to, from.schema, parts, CsvWriteOptions.fromMap(c.format.options))
    CsvReader.fromPath(to.toString, options)
  }

  /** Each part file, its columns in its layout's order under its header's
    * names. */
  private def write(to: Path, schema: Schema, parts: IndexedSeq[Vector[Vector[Any]]], f: CsvTextGen.Format,
      layouts: IndexedSeq[Layout]): Unit = {
    Files.createDirectories(to)
    parts.indices.foreach { p =>
      val Layout(order, upper) = layouts(p)
      val fileSchema = Schema(order.map { i =>
        val field = schema.fields(i)
        if (upper) field.copy(name = field.name.toUpperCase(java.util.Locale.ROOT)) else field
      })
      val rows = parts(p).map(row => order.map(row))
      val b = new ColumnarBatch(fileSchema, math.max(1, rows.length))
      rows.zipWithIndex.foreach { case (row, r) =>
        row.zipWithIndex.foreach { case (v, col) => if (v == null) b.column(col).setNull(r) else b.column(col).setBoxed(r, v) }
      }
      b.setNumRows(rows.length)
      CsvWriter.writeAll(to.resolve("part-%05d.csv".formatLocal(java.util.Locale.ROOT, p)), fileSchema,
        Iterator.single(b).filter(_.numRows > 0), CsvWriteOptions.fromMap(f.options))
    }
  }

  private def slices(rows: Vector[Vector[Any]], n: Int): IndexedSeq[Vector[Vector[Any]]] = {
    val size = math.max(1, (rows.length + n - 1) / n)
    (0 until n).map(i => rows.slice(i * size, (i + 1) * size))
  }

  /** Partition `p` of `view`, alone. */
  private def single(view: CatalogView, p: Int): CatalogView = new CatalogView {
    def schema: Schema = view.schema
    def numPartitions: Int = 1
    def readPartition(q: Int): Iterator[ColumnarBatch] = view.readPartition(p)
  }

  // ---- the grammar, restated -------------------------------------------------

  private val IntegerText = "[+-]?[0-9]+".r
  private val DecimalText = "[+-]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][+-]?[0-9]+)?".r
  private val SpecialDouble = Set("NaN", "Infinity", "-Infinity")
  private val WideInteger: DataType = DataType.DecimalType(38, 0)

  /** The engine value `text` reads as in a column of type `t`, or Left when
    * it reads as none. NULL reads as NULL, and the empty text as NULL in a
    * column of any type but STRING and BINARY. A DECIMAL column reads any
    * plain decimal that its scale holds exactly in its digits, as a declared
    * one does: `1.0` and `1e5` are values of a DECIMAL(38,0), though only
    * integer text infers one ([[classify]]). */
  private[fuzz] def denote(text: String, t: DataType): Either[String, Any] =
    if (text == null) Right(null)
    else if (text.isEmpty && t != DataType.StringType && t != DataType.BinaryType) Right(null)
    else t match {
      case DataType.StringType => Right(text)
      case DataType.IntType => integer(text).filter(_.isValidInt).map(_.toInt).toRight(text)
      case DataType.LongType => integer(text).filter(_.isValidLong).map(_.toLong).toRight(text)
      case d: DataType.DecimalType =>
        if (!DecimalText.matches(text)) Left(text)
        else Try(new java.math.BigDecimal(text).setScale(d.scale)).toOption.filter(_.precision <= d.precision).toRight(text)
      // A number no DOUBLE holds, past its largest or so small it would be 0,
      // is none of its values (plans/robustness/24).
      case DataType.DoubleType =>
        if (SpecialDouble(text)) Right(text.toDouble)
        else if (!DecimalText.matches(text)) Left(text)
        else {
          val d = text.toDouble
          val zero = text.takeWhile(c => c != 'e' && c != 'E').forall(c => c == '0' || c == '.' || c == '+' || c == '-')
          if (d.isInfinite || (d == 0.0 && !zero)) Left(text) else Right(d)
        }
      case DataType.BooleanType =>
        if (text.equalsIgnoreCase("true")) Right(true) else if (text.equalsIgnoreCase("false")) Right(false) else Left(text)
      case DataType.DateType => Try(LocalDate.parse(text)).toOption.toRight(text)
      case DataType.TimestampType =>
        Try(Instant.parse(text)).orElse(Try(LocalDateTime.parse(text).toInstant(ZoneOffset.UTC)))
          .orElse(Try(LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant))
          .map(_.truncatedTo(ChronoUnit.MICROS)).toOption.toRight(text)
      case other => Left(s"no inferred column is $other")
    }

  private def integer(text: String): Option[BigInt] = if (IntegerText.matches(text)) Some(BigInt(text)) else None

  /** The narrowest type `text` reads as, for inference: integer text is an
    * INT, a BIGINT or, past one, a DECIMAL(38,0); any other number a DOUBLE,
    * if one holds it. */
  private[fuzz] def classify(text: String): DataType =
    if (IntegerText.matches(text)) {
      val i = BigInt(text)
      if (i.isValidInt) DataType.IntType
      else if (i.isValidLong) DataType.LongType
      else if (new java.math.BigDecimal(i.bigInteger).precision <= 38) WideInteger
      else if (denote(text, DataType.DoubleType).isRight) DataType.DoubleType
      else DataType.StringType
    } else Seq(DataType.DoubleType, DataType.BooleanType, DataType.DateType, DataType.TimestampType)
      .find(t => denote(text, t).isRight).getOrElse(DataType.StringType)

  private[fuzz] def join(a: DataType, b: DataType): DataType = {
    val numbers = Seq(DataType.IntType, DataType.LongType, WideInteger, DataType.DoubleType)
    if (a == b) a
    else if (numbers.contains(a) && numbers.contains(b)) numbers(math.max(numbers.indexOf(a), numbers.indexOf(b)))
    else if (Set(a, b) == Set[DataType](DataType.DateType, DataType.TimestampType)) DataType.TimestampType
    else DataType.StringType
  }

  // ---- comparison ----------------------------------------------------------------

  private def compare(label: String, schema: Schema, want: Vector[Vector[Any]], got: Vector[Vector[Any]]): Unit = {
    if (want.length != got.length) fail(s"$label returns ${got.length} rows, not ${want.length}")
    want.indices.foreach { r =>
      want(r).indices.foreach { col =>
        if (!RowOracle.identical(want(r)(col), got(r)(col))) fail(
          s"$label row $r column '${schema.fields(col).name}' (${schema.fields(col).dataType}) is " +
            s"${RowOracle.describe(got(r)(col))}, not ${RowOracle.describe(want(r)(col))}")
      }
    }
  }

  private def sameRows(a: Vector[Vector[Any]], b: Vector[Vector[Any]]): Boolean =
    a.length == b.length && a.indices.forall(r => a(r).length == b(r).length &&
      a(r).indices.forall(c => RowOracle.identical(a(r)(c), b(r)(c))))

  private def fail(msg: String): Nothing = throw new AssertionError(s"[inference-round-trip] $msg")
}
