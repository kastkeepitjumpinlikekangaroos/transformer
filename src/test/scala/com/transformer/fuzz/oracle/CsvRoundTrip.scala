package com.transformer.fuzz.oracle

import com.transformer.core._
import com.transformer.fuzz.CsvTextGen
import com.transformer.fuzz.CsvTextGen.{Blank, DataCase, Malformed, Row, TextCase}
import com.transformer.fuzz.RowOracle
import com.transformer.read.csv.{CsvOptions, CsvReader, CsvRowParser}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}

import java.io.StringReader
import java.nio.file.{Files, Path}

/** The CSV round-trip properties: what `CsvWriter` writes reads back as what
  * it was, and CSV text reads as what it says.
  *
  * '''Data.''' A [[DataCase]] is written by `CsvWriter.writePartitioned` under
  * its option bag and read back by `CsvReader` with
  * `CsvOptions.forWrittenFile`, which is how a job reads a task's CSV output
  * for the tasks after it. Every value must come back as it was written
  * ([[RowOracle.identical]]): a decimal at its column's declared scale, `NaN`
  * and `-0.0` as themselves, an empty string apart from NULL, a NULL row of a
  * one-column table, bytes through Base64. A value the charset cannot encode,
  * or half of a surrogate pair, must instead fail the write, naming its row
  * and column. The schema is the writer's, so inference is out of the picture
  * ([[InferenceRoundTrip]] has it).
  *
  * '''Text.''' A [[TextCase]] is text written here, from fields whose values and
  * quoting the case records, so what it says is known without parsing it. It
  * must read, through `CsvRowParser` and through `CsvReader` with a schema of
  * strings, as exactly those fields: a quoted field as its text (with text
  * after its closing quote joined on), an unquoted one as NULL where it is the
  * null string, a blank line as a row in a one-column file and nothing in a
  * wider one, a header and the blank lines before it skipped, a byte-order mark
  * dropped. A malformed case must fail naming the file and the line: an
  * unclosed quote (the line it opens on), a row with an extra or a missing
  * field (the line the row starts on), bytes that are no text in the charset
  * (their line).
  */
object CsvRoundTrip {

  /** What a data case exercised, for the coverage guards. */
  final case class DataReport(writeFailed: Boolean, rows: Int)

  def checkData(c: DataCase): DataReport = {
    val dir = Files.createTempDirectory("csv-fuzz-")
    try {
      val out = dir.resolve("out")
      val schema = c.schema
      val parts = slices(c.rows, c.partitions).map(rows => Iterator.single(batch(schema, rows)).filter(_.numRows > 0))
      val unwritable = firstUnwritable(c)
      val failure =
        try { CsvWriter.writePartitioned(out, schema, parts, CsvWriteOptions.fromMap(c.format.options)); None }
        catch { case e: IllegalArgumentException => Some(String.valueOf(e.getMessage)) }
      (unwritable, failure) match {
        case (Some((where, column)), Some(msg)) =>
          if (!(msg.contains(where) && msg.contains(s"'$column'")))
            fail(s"the write failed without naming $where, column '$column': $msg")
          return DataReport(writeFailed = true, c.rows.length)
        case (Some((where, column)), None) =>
          fail(s"wrote $where, column '$column', which ${c.format.charset.name} cannot hold, without failing")
        case (None, Some(msg)) => fail(s"the write failed: $msg")
        case (None, None) => ()
      }
      val view = CsvReader.fromPath(out.toString, CsvOptions.forWrittenFile(c.format.options, schema))
      val got = rowsOf(view)
      val want = c.rows.map(_.zipWithIndex.map { case (v, i) => readBack(v, schema.fields(i).dataType) })
      compare("read back", schema.fieldNames, want, got)
      DataReport(writeFailed = false, c.rows.length)
    } finally deleteRecursively(dir)
  }

  /** `rows` cut into `n` contiguous slices, the trailing ones empty when there
    * are fewer rows than slices. */
  private def slices(rows: Vector[Vector[Any]], n: Int): IndexedSeq[Vector[Vector[Any]]] =
    if (n == 0) IndexedSeq.empty
    else {
      val size = math.max(1, (rows.length + n - 1) / n)
      (0 until n).map(i => rows.slice(i * size, (i + 1) * size))
    }

  private def batch(schema: Schema, rows: Vector[Vector[Any]]): ColumnarBatch = {
    val b = new ColumnarBatch(schema, math.max(1, rows.length))
    rows.zipWithIndex.foreach { case (row, r) =>
      row.zipWithIndex.foreach { case (v, col) => if (v == null) b.column(col).setNull(r) else b.column(col).setBoxed(r, v) }
    }
    b.setNumRows(rows.length)
    b
  }

  /** The first value the writer must refuse, in writing order: a header name
    * or a string holding half of a surrogate pair or a character the charset
    * lacks. As `(where, column)` the way the writer's message names it. */
  private def firstUnwritable(c: DataCase): Option[(String, String)] = {
    def holds(s: String): Boolean = {
      var i = 0
      while (i < s.length) {
        val ch = s.charAt(i)
        if (Character.isHighSurrogate(ch) && i + 1 < s.length && Character.isLowSurrogate(s.charAt(i + 1))) i += 2
        else if (Character.isSurrogate(ch)) return false
        else i += 1
      }
      c.format.unicode || c.format.charset.newEncoder().canEncode(s)
    }
    val header =
      if (c.format.header) c.schema.fields.find(f => !holds(f.name)).map(f => ("the header", f.name)) else None
    header.orElse {
      // Rows are numbered across the whole write, as one stream of rows.
      c.rows.iterator.zipWithIndex.flatMap { case (row, r) =>
        row.indices.find(i => row(i) match {
          case s: String => !holds(s)
          case _ => false
        }).map(i => (s"row ${r + 1 - partitionStart(c, r)}", c.schema.fields(i).name))
      }.nextOption()
    }
  }

  /** The index of the first row of the part file row `r` is written to: the
    * writer numbers rows within each file. */
  private def partitionStart(c: DataCase, r: Int): Int = {
    val size = math.max(1, (c.rows.length + c.partitions - 1) / math.max(1, c.partitions))
    (r / size) * size
  }

  /** What a value of `dt` reads back as: itself, a decimal at the column's
    * declared scale. */
  private def readBack(v: Any, dt: DataType): Any = (v, dt) match {
    case (null, _) => null
    case (d: java.math.BigDecimal, t: DataType.DecimalType) => d.setScale(t.scale)
    case (other, _) => other
  }

  // ---- text ------------------------------------------------------------------

  /** What a text case exercised, for the coverage guards. */
  final case class TextReport(failedAsExpected: Boolean, rows: Int, blankRows: Int, skippedBlanks: Int)

  def checkText(c: TextCase): TextReport = {
    val dir = Files.createTempDirectory("csv-text-fuzz-")
    try {
      val file = dir.resolve("text.csv")
      Files.write(file, CsvTextGen.encode(c))
      val laid = CsvTextGen.layout(c)
      val f = c.format
      val blankValue: String = if (f.nullValue.isEmpty) null else ""
      def value(t: CsvTextGen.TextField): String =
        if (t.quoted) t.value + t.trailing else if (t.value == f.nullValue) null else t.value
      val options = CsvOptions.fromMap(f.options).copy(inferSchema = false,
        columns = Some((0 until c.width).map(i => Field(s"c$i", DataType.StringType))))

      c.malformed match {
        case Some(m) =>
          val (line, what) = m match {
            case Malformed.UnclosedQuote => (laid.lineOf(laid.unclosedQuote.get), "never closed")
            case Malformed.ExtraField(at) => (laid.lineOf(laid.lines(at)), s"has ${c.width + 1} field")
            case Malformed.MissingField(at) => (laid.lineOf(laid.lines(at)), s"has ${c.width - 1} field")
            case Malformed.InvalidBytes(_) => (laid.lineOf(laid.invalidByte.get), s"not valid ${f.charset.name}")
          }
          val msg =
            try { rowsOf(CsvReader.fromPath(file.toString, options)); fail(s"$m read without failing") }
            catch {
              case e: AssertionError => throw e
              case e: IllegalArgumentException => String.valueOf(e.getMessage)
            }
          val atLine = if (m == Malformed.UnclosedQuote) s"opens on line $line" else s"line $line"
          if (!(msg.contains("text.csv") && msg.contains(atLine) && msg.contains(what)))
            fail(s"$m failed without naming the file, '$atLine' and '$what': $msg")
          TextReport(failedAsExpected = true, 0, 0, 0)

        case None =>
          // The parser's rows: the header, then every line, each blank line a
          // row of one empty field that says it is blank.
          val records = Vector.newBuilder[(Seq[String], Boolean, Long)]
          c.header.foreach { h =>
            (0 until c.leadingBlanks).foreach(k => records += ((Seq(blankValue), true, k + 1L)))
            records += ((h.fields.map(value), false, laid.lineOf(laid.header.get)))
          }
          c.lines.indices.foreach { i =>
            val start = laid.lineOf(laid.lines(i))
            c.lines(i) match {
              case Blank("") if i == c.lines.length - 1 => ()
              case Blank(_) => records += ((Seq(blankValue), true, start))
              case r: Row =>
                val blank = r.fields.length == 1 && !r.fields.head.quoted && r.fields.head.value.isEmpty
                records += ((r.fields.map(value), blank, start))
            }
          }
          val parser = new CsvRowParser(new StringReader(laid.text), CsvOptions.fromMap(f.options))
          val parsed = Iterator.continually(parser.readRow()).takeWhile(_.isDefined)
            .map(r => (r.get.toSeq, parser.blank, parser.line)).toVector
          val wantRecords = records.result()
          if (parsed != wantRecords) {
            val i = parsed.indices.find(i => i >= wantRecords.length || parsed(i) != wantRecords(i)).getOrElse(parsed.length)
            fail(s"the parser reads record $i as ${parsed.lift(i).fold("nothing")(showRecord)}, " +
              s"not ${wantRecords.lift(i).fold("nothing")(showRecord)} (${parsed.length} records, not ${wantRecords.length})")
          }
          // The reader's rows: the header and the blank lines before it gone,
          // and a blank line a row only in a one-column file.
          val dataRecords = wantRecords.drop(if (c.header.isDefined) c.leadingBlanks + 1 else 0)
          val want = dataRecords.filterNot { case (_, blank, _) => blank && c.width > 1 }.map(_._1.toVector)
          compare("the reader", (0 until c.width).map(i => s"c$i").toVector, want, rowsOf(CsvReader.fromPath(file.toString, options)))
          TextReport(failedAsExpected = false, want.length, dataRecords.count(r => r._2 && c.width == 1),
            dataRecords.count(r => r._2 && c.width > 1))
      }
    } finally deleteRecursively(dir)
  }

  private def showRecord(r: (Seq[String], Boolean, Long)): String =
    s"${r._1.map(CsvTextGen.show).mkString("(", ", ", ")")}${if (r._2) " (blank)" else ""} on line ${r._3}"

  // ---- shared ------------------------------------------------------------------

  private[fuzz] def rowsOf(view: CatalogView): Vector[Vector[Any]] =
    (0 until view.numPartitions).iterator.flatMap { p =>
      view.readPartition(p).flatMap(b => (0 until b.numRows).iterator.map(r =>
        Vector.tabulate(view.schema.length)(col => b.column(col).getBoxed(r))))
    }.toVector

  private def compare(label: String, names: Vector[String], want: Vector[Vector[Any]], got: Vector[Vector[Any]]): Unit = {
    def row(r: Vector[Any]): String = r.map(CsvTextGen.showValue).mkString("(", ", ", ")")
    if (got.length != want.length) {
      val i = want.indices.find(i => i >= got.length || !sameRow(want(i), got(i))).getOrElse(want.length)
      fail(s"$label returns ${got.length} rows, not ${want.length}; first difference at row $i: " +
        s"wanted ${want.lift(i).fold("nothing")(row)}, got ${got.lift(i).fold("nothing")(row)}")
    }
    want.indices.foreach { r =>
      want(r).indices.foreach { col =>
        if (!RowOracle.identical(want(r)(col), got(r)(col))) fail(
          s"$label row $r column '${names(col)}' is ${describe(got(r)(col))}, not ${describe(want(r)(col))}")
      }
    }
  }

  private def sameRow(a: Vector[Any], b: Vector[Any]): Boolean =
    a.length == b.length && a.indices.forall(i => RowOracle.identical(a(i), b(i)))

  private def describe(v: Any): String = v match {
    case s: String => s"${CsvTextGen.show(s)} (String)"
    case other => RowOracle.describe(other)
  }

  private def fail(msg: String): Nothing = throw new AssertionError(s"[csv-round-trip] $msg")

  private[fuzz] def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    Files.deleteIfExists(p)
  }
}
