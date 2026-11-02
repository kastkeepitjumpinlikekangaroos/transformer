package com.transformer.cli

import com.transformer.core._
import com.transformer.job.{Json => JsonText}
import com.transformer.read.csv.CsvValues
import com.transformer.write.csv.{CsvRowWriter, CsvWriteOptions}
import org.jline.utils.WCWidth

import java.io.{BufferedWriter, OutputStreamWriter, PrintStream}
import java.util.Locale
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** How `transformer query` prints a result. */
sealed trait OutputFormat {
  def name: String
  /** Whether a result after another is set apart by a blank line. */
  def setApart: Boolean = false
}

object OutputFormat {
  /** A box around the rows, each column's name and type above them: at most
    * the `--max-rows` first and last rows, and as many columns as fit a
    * terminal. A value is shown as the CSV writer writes it, NULL as `NULL`
    * and a control character as an escape. */
  case object Table extends OutputFormat { val name = "table"; override def setApart = true }
  /** A line per value, `column = value`, a blank line between rows. */
  case object Line extends OutputFormat { val name = "line"; override def setApart = true }
  /** CSV as the writer writes a file, with a header. */
  case object Csv extends OutputFormat { val name = "csv" }
  /** CSV with a tab between fields. */
  case object Tsv extends OutputFormat { val name = "tsv" }
  /** A JSON array of an object per row. */
  case object Json extends OutputFormat { val name = "json" }
  /** A JSON object per row, a line each. */
  case object JsonLines extends OutputFormat { val name = "jsonl" }
  /** A Markdown table. */
  case object Markdown extends OutputFormat { val name = "markdown"; override def setApart = true }

  val All: Seq[OutputFormat] = Seq(Table, Line, Csv, Tsv, Json, JsonLines, Markdown)

  def named(name: String): Option[OutputFormat] = All.find(_.name == name.toLowerCase(Locale.ROOT))

  /** Every format's name, for a message: `table, line, ... or markdown`. */
  val names: String = All.init.map(_.name).mkString(", ") + " or " + All.last.name
}

object ResultPrinter {

  /** The widest a table shows a value, in terminal columns; a longer one is
    * cut short with an ellipsis. */
  val MaxCellWidth = 40

  /** Print the rows `batches` hold, columns `schema`, on `out` in `format`. A
    * table shows at most `maxRows` rows (0: every one) and fits `width`
    * terminal columns when given; every other format prints every row. */
  def print(out: PrintStream, schema: Schema, batches: Iterator[ColumnarBatch], format: OutputFormat, maxRows: Int,
      width: Option[Int]): Unit = format match {
    case OutputFormat.Table => table(out, schema, batches, maxRows, width)
    case OutputFormat.Line => line(out, schema, batches)
    case OutputFormat.Csv => delimited(out, schema, batches, ',', "CSV")
    case OutputFormat.Tsv => delimited(out, schema, batches, '\t', "TSV")
    case OutputFormat.Json => json(out, schema, batches, array = true)
    case OutputFormat.JsonLines => json(out, schema, batches, array = false)
    case OutputFormat.Markdown => markdown(out, schema, batches)
  }

  /** Each row's first value as a line: the plan EXPLAIN returns, as a table
    * shows it. */
  def lines(out: PrintStream, batches: Iterator[ColumnarBatch]): Unit =
    batches.foreach { b =>
      var r = 0
      while (r < b.numRows) { out.println(Option(text(b.column(0), r)).getOrElse("")); r += 1 }
    }

  /** A result of text columns `names` holding `rows`, as a listing prints. */
  def strings(names: Seq[String], rows: Seq[Seq[String]]): (Schema, Iterator[ColumnarBatch]) = {
    val schema = Schema(names.map(n => Field(n, DataType.StringType)).toVector)
    val batch = new ColumnarBatch(schema, math.max(1, rows.size))
    rows.iterator.zipWithIndex.foreach { case (row, r) => row.indices.foreach(c => batch.column(c).setBoxed(r, row(c))) }
    batch.setNumRows(rows.size)
    (schema, Iterator(batch))
  }

  /** Row `row` of `v` as the CSV writer writes it, or null for NULL. */
  private def text(v: ColumnVector, row: Int): String = if (v.isNull(row)) null else CsvValues.format(v, row)

  /** `s` as a table, a line or a Markdown cell shows it: NULL for null, and a
    * control character escaped, so a value holds one line. */
  private[cli] def shown(s: String): String = {
    if (s == null) return "NULL"
    if (!s.exists(c => Character.isISOControl(c))) return s
    val sb = new java.lang.StringBuilder(s.length + 8)
    s.foreach {
      case '\n' => sb.append('\\').append('n')
      case '\r' => sb.append('\\').append('r')
      case '\t' => sb.append('\\').append('t')
      case c if Character.isISOControl(c) => sb.append('\\').append('u').append("%04x".formatLocal(Locale.ROOT, c.toInt))
      case c => sb.append(c)
    }
    sb.toString
  }

  // ---------------------------------------------------------------------------
  // table
  // ---------------------------------------------------------------------------

  private def table(out: PrintStream, schema: Schema, batches: Iterator[ColumnarBatch], maxRows: Int,
      width: Option[Int]): Unit = {
    val n = schema.length
    val headCount = if (maxRows == 0) Int.MaxValue else (maxRows + 1) / 2
    val tailCount = if (maxRows == 0) 0 else maxRows - headCount
    val head = mutable.ArrayBuffer.empty[IndexedSeq[String]]
    val tail = new java.util.ArrayDeque[IndexedSeq[String]]()
    def cells(b: ColumnarBatch, r: Int): IndexedSeq[String] = (0 until n).map(c => shown(text(b.column(c), r)))
    var total = 0L
    while (batches.hasNext) {
      val b = batches.next()
      var r = 0
      while (r < b.numRows && head.size < headCount) { head += cells(b, r); r += 1 }
      // Only a batch's last rows can stay among the last ones shown.
      r = math.max(r, b.numRows - tailCount)
      while (r < b.numRows) {
        tail.addLast(cells(b, r))
        if (tail.size > tailCount) tail.removeFirst()
        r += 1
      }
      total += b.numRows
    }
    val columns = schema.fields.map(f => BoxColumn(f.name, DataType.sqlName(f.dataType), DataType.isNumeric(f.dataType)))
    val unicode = out.charset().newEncoder().canEncode(Unicode.all)
    box(columns, head.toSeq, tail.asScala.toSeq, total, width, unicode).foreach(out.println)
  }

  /** A column of a table: its name, its type, and whether it holds numbers,
    * which line up on the right. */
  private[cli] final case class BoxColumn(name: String, typeName: String, numeric: Boolean)

  private final case class Glyphs(h: String, v: String, tl: String, tm: String, tr: String, ml: String, mm: String,
      mr: String, bl: String, bm: String, br: String, ellipsis: String) {
    def all: String = h + v + tl + tm + tr + ml + mm + mr + bl + bm + br + ellipsis
  }
  private val Unicode = Glyphs("─", "│", "┌", "┬", "┐", "├", "┼", "┤", "└", "┴", "┘", "…")
  private val Ascii = Glyphs("-", "|", "+", "+", "+", "+", "+", "+", "+", "+", "+", "...")

  /** The lines of a table of `columns`: `head`, the first rows, then `tail`,
    * the last, with a row of ellipses between when `total` is more than both;
    * then how many rows there are, and how many were shown. Past `width`, the
    * columns in the middle give way to one of ellipses. With `unicode` the
    * box is drawn in box-drawing characters, else in ASCII. */
  private[cli] def box(columns: IndexedSeq[BoxColumn], head: Seq[IndexedSeq[String]], tail: Seq[IndexedSeq[String]],
      total: Long, width: Option[Int], unicode: Boolean): Vector[String] = {
    val g = if (unicode) Unicode else Ascii
    def cut(s: String): String = fit(s, MaxCellWidth, g.ellipsis)
    val names = columns.map(c => cut(c.name))
    val types = columns.map(c => cut(c.typeName))
    val rows = (head ++ tail).map(_.map(cut))
    val n = columns.size
    val widths = (0 until n).map(i => (Iterator(names(i), types(i)) ++ rows.iterator.map(_(i))).map(displayWidth).max)
    val elided = displayWidth(g.ellipsis)

    // Every column, or as many of the first and last, taken in turn, as fit
    // beside a column of ellipses standing for the rest.
    def cost(i: Int): Int = widths(i) + 3
    val (left, right) = width match {
      case Some(w) if widths.indices.map(cost).sum + 1 > w =>
        var budget = w - 1 - (elided + 3)
        val l = mutable.ArrayBuffer.empty[Int]
        val r = mutable.ArrayBuffer.empty[Int]
        var lo = 0
        var hi = n - 1
        var fromLeft = true
        var fits = true
        while (fits && lo <= hi) {
          val i = if (fromLeft) lo else hi
          if (cost(i) > budget) fits = false
          else {
            budget -= cost(i)
            if (fromLeft) { l += i; lo += 1 } else { r.prepend(i); hi -= 1 }
            fromLeft = !fromLeft
          }
        }
        if (l.isEmpty) l += 0 // the first column shows, however wide
        (l.toVector, r.toVector.filterNot(l.contains))
      case _ => ((0 until n).toVector, Vector.empty[Int])
    }
    val hidden = n - left.size - right.size
    val shownColumns: Vector[Option[Int]] =
      left.map(Some(_)) ++ (if (hidden > 0) Vector(None) else Vector.empty) ++ right.map(Some(_))
    def widthOf(c: Option[Int]): Int = c.fold(elided)(widths)
    def pad(s: String, c: Option[Int]): String = {
      val spaces = " " * (widthOf(c) - displayWidth(s))
      if (c.exists(columns(_).numeric)) spaces + s else s + spaces
    }
    def rule(l: String, m: String, r: String): String = shownColumns.map(c => g.h * (widthOf(c) + 2)).mkString(l, m, r)
    def line(value: Int => String): String =
      shownColumns.map(c => " " + pad(c.fold(g.ellipsis)(value), c) + " ").mkString(g.v, g.v, g.v)

    val out = Vector.newBuilder[String]
    out += rule(g.tl, g.tm, g.tr)
    out += line(names)
    out += line(types)
    if (rows.nonEmpty) {
      out += rule(g.ml, g.mm, g.mr)
      head.indices.foreach(r => out += line(rows(r)))
      if (total > head.size + tail.size) out += line(_ => g.ellipsis)
      tail.indices.foreach(r => out += line(rows(head.size + r)))
    }
    out += rule(g.bl, g.bm, g.br)
    val shownRows = head.size + tail.size
    val counted = (if (total == 1) "1 row" else s"$total rows") + (if (total > shownRows) s" ($shownRows shown)" else "")
    out += (if (hidden > 0) s"$counted, $n columns (${n - hidden} shown)" else counted)
    out.result()
  }

  /** How many terminal columns `s` takes: two for a wide character, none for
    * a combining one. */
  private[cli] def displayWidth(s: String): Int = s.codePoints.map(cp => math.max(0, WCWidth.wcwidth(cp))).sum

  /** `s` cut to `max` terminal columns, ending in `ellipsis` when cut. */
  private def fit(s: String, max: Int, ellipsis: String): String =
    if (displayWidth(s) <= max) s
    else {
      val room = max - displayWidth(ellipsis)
      val sb = new java.lang.StringBuilder
      var used = 0
      val it = s.codePoints.iterator
      var full = false
      while (it.hasNext && !full) {
        val cp = it.next()
        val w = math.max(0, WCWidth.wcwidth(cp))
        if (used + w > room) full = true
        else { sb.appendCodePoint(cp); used += w }
      }
      sb.append(ellipsis).toString
    }

  // ---------------------------------------------------------------------------
  // line, csv, tsv, json, jsonl, markdown
  // ---------------------------------------------------------------------------

  private def line(out: PrintStream, schema: Schema, batches: Iterator[ColumnarBatch]): Unit = {
    val names = schema.fieldNames
    val w = names.map(displayWidth).foldLeft(0)(math.max)
    var first = true
    batches.foreach { b =>
      var r = 0
      while (r < b.numRows) {
        if (!first) out.println()
        first = false
        names.indices.foreach { c =>
          out.println(" " * (w - displayWidth(names(c))) + names(c) + " = " + shown(text(b.column(c), r)))
        }
        r += 1
      }
    }
  }

  private def delimited(out: PrintStream, schema: Schema, batches: Iterator[ColumnarBatch], delimiter: Char,
      what: String): Unit = {
    val writer = new BufferedWriter(new OutputStreamWriter(out, out.charset()))
    val rows = new CsvRowWriter(writer, schema, CsvWriteOptions(delimiter = delimiter), s"the result as $what")
    batches.foreach { b => rows.write(b); writer.flush() }
    writer.flush()
  }

  private def json(out: PrintStream, schema: Schema, batches: Iterator[ColumnarBatch], array: Boolean): Unit = {
    val names = schema.fieldNames
    Schema.repeatedName(names).foreach { positions =>
      throw new IllegalArgumentException(
        s"The result has more than one column named '${names(positions.head)}', columns " +
          s"${Schema.describePositions(names, positions)}, and a JSON object holds a name once, names being read " +
          "regardless of case; give each its own name with AS")
    }
    val keys = names.map(n => "\"" + JsonText.escape(n) + "\":")
    var first = true
    if (array) out.print("[")
    batches.foreach { b =>
      var r = 0
      while (r < b.numRows) {
        val sb = new java.lang.StringBuilder
        if (array && !first) sb.append(",\n")
        sb.append('{')
        var c = 0
        while (c < names.size) {
          if (c > 0) sb.append(',')
          sb.append(keys(c)).append(jsonValue(b.column(c), r))
          c += 1
        }
        sb.append('}')
        if (!array) sb.append('\n')
        out.print(sb)
        first = false
        r += 1
      }
    }
    if (array) out.print("]\n")
    out.flush()
  }

  /** Row `row` of `v` as a JSON value: a number as a number, NaN and the
    * infinities, which JSON has no numbers for, as strings, a boolean as
    * itself, and any other value as a string of its CSV text. */
  private def jsonValue(v: ColumnVector, row: Int): String =
    if (v.isNull(row)) "null"
    else v match {
      case i: IntVector => i.get(row).toString
      case l: LongVector => l.get(row).toString
      case d: DecimalVector => d.get(row).toPlainString
      case f: FloatVector =>
        val x = f.get(row)
        if (java.lang.Float.isFinite(x)) java.lang.Float.toString(x) else "\"" + java.lang.Float.toString(x) + "\""
      case d: DoubleVector =>
        val x = d.get(row)
        if (java.lang.Double.isFinite(x)) java.lang.Double.toString(x) else "\"" + java.lang.Double.toString(x) + "\""
      case b: BooleanVector => b.get(row).toString
      case other => "\"" + JsonText.escape(CsvValues.format(other, row)) + "\""
    }

  private def markdown(out: PrintStream, schema: Schema, batches: Iterator[ColumnarBatch]): Unit = {
    def cell(s: String): String = shown(s).replace("|", "\\|")
    val names = schema.fieldNames.map(cell)
    val numeric = schema.fields.map(f => DataType.isNumeric(f.dataType))
    val rows = mutable.ArrayBuffer.empty[IndexedSeq[String]]
    batches.foreach { b =>
      var r = 0
      while (r < b.numRows) { rows += names.indices.map(c => cell(text(b.column(c), r))); r += 1 }
    }
    // A separator row needs three dashes to read as one.
    val widths = names.indices.map { c =>
      (Iterator(names(c)) ++ rows.iterator.map(_(c))).map(displayWidth).foldLeft(3)(math.max)
    }
    def pad(s: String, c: Int): String = {
      val spaces = " " * (widths(c) - displayWidth(s))
      if (numeric(c)) spaces + s else s + spaces
    }
    def line(values: Int => String): String = names.indices.map(values).mkString("| ", " | ", " |")
    out.println(line(c => pad(names(c), c)))
    out.println(line(c => if (numeric(c)) "-" * (widths(c) - 1) + ":" else "-" * widths(c)))
    rows.foreach(row => out.println(line(c => pad(row(c), c))))
  }

}
