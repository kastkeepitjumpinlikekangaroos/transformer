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

  /** Print `q`'s rows on `io.out` in `format`, as [[print]] prints them from
    * its batches in order, reading its partitions on the pool
    * ([[ParallelDrain]]), at most [[ParallelDrain.inFlight]] at once, under
    * `stop`, the statement's own. A table and markdown read every partition,
    * each in the order its pool task reads it, then print; every other format
    * prints the batches in order as fills read them ahead, its text written
    * on this thread. A result of one partition is read here, as its batches
    * come. */
  def print(io: Io, q: ExecutedQuery, stop: Cancel, format: OutputFormat, maxRows: Int, width: Option[Int]): Unit = {
    val inFlight = ParallelDrain.inFlight(q.numPartitions)
    format match {
      case OutputFormat.Table | OutputFormat.Markdown if q.numPartitions > 1 =>
        // Nothing prints until every partition is read: an output that has
        // closed stops the statement first.
        io.stopIfClosed()
        if (format == OutputFormat.Table) { val _ = tableOfPartitions(io.out, q, inFlight, stop.drainStop, maxRows, width) }
        else {
          val batches = ParallelDrain.eachPartition(q, inFlight, stop.drainStop)((_, it) => it.toVector)
          markdown(io.out, q.schema, batches.iterator.flatten)
        }
      case _ =>
        val ordered = ParallelDrain.inOrder(q, inFlight, ParallelDrain.ChunkBatches, stop.drainStop)
        try print(io.out, q.schema, io.printing(ordered), format, maxRows, width)
        finally ordered.close()
    }
  }

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
  private[cli] def text(v: ColumnVector, row: Int): String = if (v.isNull(row)) null else CsvValues.format(v, row)

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
    printBox(out, schema, head.toSeq, tail.asScala.toSeq, total, width)
  }

  private def printBox(out: PrintStream, schema: Schema, head: Seq[IndexedSeq[String]], tail: Seq[IndexedSeq[String]],
      total: Long, width: Option[Int]): Unit = {
    val columns = schema.fields.map(f => BoxColumn(f.name, DataType.sqlName(f.dataType), DataType.isNumeric(f.dataType)))
    val unicode = out.charset().newEncoder().canEncode(Unicode.all)
    box(columns, head, tail, total, width, unicode).foreach(out.println)
  }

  /** `q` as a table, as [[table]] prints it from its batches in order, its
    * partitions read on `inFlight` pool tasks at once: each keeps its count
    * and its first and last rows ([[TableSlices]]), and once all are read the
    * rows shown are found among them and their text made here. Returns the
    * slices, which say how many rows they held. */
  private[cli] def tableOfPartitions(out: PrintStream, q: ExecutedQuery, inFlight: Int, stop: ParallelDrain.Stop,
      maxRows: Int, width: Option[Int]): TableSlices = {
    val n = q.schema.length
    // The rows `table` keeps: the first half of `maxRows`, one more when it
    // is odd, and the last; every row at 0.
    val half = (maxRows + 1) / 2
    val headCount = if (maxRows == 0) Long.MaxValue else half.toLong
    val tailCount = if (maxRows == 0) 0L else (maxRows - half).toLong
    val slices = new TableSlices(q.numPartitions, headCount, tailCount)
    val _ = ParallelDrain.eachPartition(q, inFlight, stop)((p, it) => slices.read(p, it))
    def cells(b: ColumnarBatch, r: Int): IndexedSeq[String] = (0 until n).map(c => shown(text(b.column(c), r)))
    val (head, tail, total) = slices.shown(cells)
    printBox(out, q.schema, head, tail, total, width)
    slices
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

/** What a table of a result of several partitions keeps while they are read
  * on several threads at once ([[ResultPrinter.tableOfPartitions]]): each
  * partition's row count, and its first `head` and last `tail` rows, held as
  * the values the batches hold, not yet as text. Counting rows from 0 across
  * the partitions in order, a table shows row g when g < head or
  * g >= total - tail, and each such row is among its partition's first
  * `head` or last `tail` rows.
  *
  * A partition counts its rows as it reads them, and each that finishes lets
  * go of what can no longer show, in every partition finished so far: the
  * first rows of a partition once those before it have counted `head` rows,
  * its last rows once those after it have counted `tail`. So a slow
  * partition leaves about `head + tail` rows held, not those of every
  * partition finished while it reads.
  */
private[cli] final class TableSlices(n: Int, head: Long, tail: Long) {
  import TableSlices._

  private val counts = new Counts(n)
  // The finished partitions' slices, the frontiers and the rows held; each
  // guarded by `this`. The partitions from `headFrom` on show none of their
  // first rows, and those before `tailTo` none of their last.
  private val slices = new Array[Slice](n)
  private var headFrom = n
  private var tailTo = 0
  private var held = 0L
  private var mostHeld = 0L

  /** Read partition `p` to its end, keeping its count and what of it may
    * show. */
  def read(p: Int, it: Iterator[ColumnarBatch]): Unit = {
    val keeping = new Keeping(head, tail)
    while (it.hasNext) {
      val b = it.next()
      keeping.add(b)
      counts.add(p, b.numRows.toLong)
    }
    finished(p, keeping.slice())
  }

  private def finished(p: Int, s: Slice): Unit = synchronized {
    slices(p) = s
    held += s.rows
    val h = math.min(headFrom, counts.firstReaching(head))
    var q = h
    while (q < headFrom) {
      if (slices(q) != null) held -= slices(q).dropFirst()
      q += 1
    }
    headFrom = h
    val t = math.max(tailTo, n - counts.lastReaching(tail))
    q = tailTo
    while (q < t) {
      if (slices(q) != null) held -= slices(q).dropLast()
      q += 1
    }
    tailTo = t
    if (p >= headFrom) held -= s.dropFirst()
    if (p < tailTo) held -= s.dropLast()
    mostHeld = math.max(mostHeld, held)
  }

  /** The most rows the finished partitions held at once, a row among both a
    * partition's first and last rows counted twice. */
  def mostRowsHeld: Long = synchronized(mostHeld)

  /** Once every partition is read: the rows the table shows, the first ones
    * and the last, each as `cells` makes it, and how many rows there are. */
  def shown(cells: (ColumnarBatch, Int) => IndexedSeq[String])
      : (Vector[IndexedSeq[String]], Vector[IndexedSeq[String]], Long) = synchronized {
    val total = slices.iterator.map(_.count).sum
    // Rows from here on show among the last.
    val tailFrom = math.max(head, total - tail)
    val first = Vector.newBuilder[IndexedSeq[String]]
    val last = Vector.newBuilder[IndexedSeq[String]]
    var before = 0L
    slices.foreach { s =>
      if (before < head) s.firstRows(math.min(s.count, head - before), (b, r) => first += cells(b, r))
      val from = math.max(before, tailFrom) - before
      if (from < s.count) s.lastRows(s.count - from, (b, r) => last += cells(b, r))
      before += s.count
    }
    (first.result(), last.result(), total)
  }
}

private[cli] object TableSlices {

  /** A partition's row count and the rows of it that may show: its first
    * rows and its last, each as batches holding just those rows, in order;
    * either let go once it cannot show. */
  final class Slice(val count: Long, private var first: Vector[ColumnarBatch], private var last: Vector[ColumnarBatch]) {
    def rows: Long = rowsIn(first) + rowsIn(last)

    /** Let go of the first rows; returns how many there were. */
    def dropFirst(): Long = { val r = rowsIn(first); first = null; r }

    /** Let go of the last rows; returns how many there were. */
    def dropLast(): Long = { val r = rowsIn(last); last = null; r }

    /** The partition's first `k` rows, in order, each given to `row`. */
    def firstRows(k: Long, row: (ColumnarBatch, Int) => Unit): Unit = each(first, 0L, k, row)

    /** The partition's last `k` rows, in order, each given to `row`. */
    def lastRows(k: Long, row: (ColumnarBatch, Int) => Unit): Unit = {
      val kept = rowsIn(last)
      each(last, kept - k, kept, row)
    }

    /** Rows [from, until) of `batches`, read as one run of rows. */
    private def each(batches: Vector[ColumnarBatch], from: Long, until: Long, row: (ColumnarBatch, Int) => Unit): Unit = {
      var offset = 0L
      batches.foreach { b =>
        var r = math.max(0L, from - offset)
        val end = math.min(b.numRows.toLong, until - offset)
        while (r < end) { row(b, r.toInt); r += 1 }
        offset += b.numRows
      }
    }
  }

  private def rowsIn(batches: Vector[ColumnarBatch]): Long =
    if (batches == null) 0L else batches.iterator.map(_.numRows.toLong).sum

  /** A partition's first `head` and last `tail` rows as it is read: ranges
    * of the batches it reads, which the reader changes no more once it has
    * returned them. */
  final class Keeping(head: Long, tail: Long) {
    private val first = Vector.newBuilder[Rows]
    private var firstKept = 0L
    private val last = mutable.ArrayDeque.empty[Rows]
    private var lastKept = 0L
    private var count = 0L

    def add(b: ColumnarBatch): Unit = {
      val n = b.numRows
      if (n > 0) {
        if (firstKept < head) {
          val take = math.min(n.toLong, head - firstKept).toInt
          first += new Rows(b, 0, take)
          firstKept += take
        }
        if (tail > 0) {
          val from = math.max(0L, n - tail).toInt
          last.append(new Rows(b, from, n))
          lastKept += n - from
          // The first range goes once the others hold the last rows without it.
          while (lastKept - last.head.size >= tail) lastKept -= last.removeHead().size
        }
        count += n
      }
    }

    /** What was kept, once the partition is read: its first rows and last,
      * each range copied to a batch of its own unless it is a whole batch. */
    def slice(): Slice = {
      // The ranges kept hold at least the last `tail` rows, and the first of
      // them may start before them.
      var extra = lastKept - math.min(tail, count)
      val lastRows = last.toVector.map { r =>
        val trimmed = if (extra > 0) new Rows(r.batch, r.from + extra.toInt, r.until) else r
        extra = 0
        trimmed.copied
      }
      new Slice(count, first.result().map(_.copied), lastRows)
    }
  }

  /** Rows [from, until) of `batch`. */
  final class Rows(val batch: ColumnarBatch, val from: Int, val until: Int) {
    def size: Int = until - from

    /** These rows as a batch: `batch` itself when they are all of it, else a
      * copy, each column a vector of the type `batch`'s is. */
    def copied: ColumnarBatch =
      if (from == 0 && until == batch.numRows) batch
      else {
        val columns = Array.tabulate[ColumnVector](batch.schema.length) { c =>
          val src = batch.column(c)
          val dst = ColumnVector.allocate(src.dataType, math.max(1, size))
          var r = 0
          while (r < size) { src.copyTo(from + r, dst, r); r += 1 }
          dst
        }
        ColumnarBatch.fromColumns(batch.schema, columns, size)
      }
  }

  /** Each partition's rows counted so far, written by the thread reading
    * it, and the rows of the first or last partitions together. Two Fenwick
    * trees, one over the partitions in order and one over them in reverse.
    * A count only grows, so a sum read while others count is at most what
    * it will be: a partition let go of by it is let go of rightly. */
  final class Counts(n: Int) {
    private val forward = new java.util.concurrent.atomic.AtomicLongArray(n + 1)
    private val backward = new java.util.concurrent.atomic.AtomicLongArray(n + 1)

    def add(p: Int, rows: Long): Unit = if (rows > 0) {
      bump(forward, p + 1, rows)
      bump(backward, n - p, rows)
    }

    private def bump(tree: java.util.concurrent.atomic.AtomicLongArray, at: Int, rows: Long): Unit = {
      var i = at
      while (i <= n) {
        val _ = tree.getAndAdd(i, rows)
        i += i & -i
      }
    }

    /** The fewest first partitions that hold `rows` rows together, or
      * `n + 1` when all of them hold fewer. */
    def firstReaching(rows: Long): Int = reaching(forward, rows)

    /** The fewest last partitions that hold `rows` rows together, or `n + 1`
      * when all of them hold fewer. */
    def lastReaching(rows: Long): Int = reaching(backward, rows)

    private def reaching(tree: java.util.concurrent.atomic.AtomicLongArray, rows: Long): Int =
      if (rows <= 0) 0
      else {
        var at = 0
        var left = rows
        var step = Integer.highestOneBit(n)
        while (step > 0) {
          val i = at + step
          if (i <= n) {
            val v = tree.get(i)
            if (v < left) { at = i; left -= v }
          }
          step >>>= 1
        }
        at + 1
      }
  }
}
