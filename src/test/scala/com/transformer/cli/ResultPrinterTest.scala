package com.transformer.cli

import com.transformer.cli.ResultPrinter.BoxColumn
import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayOutputStream, PrintStream}

class ResultPrinterTest {

  /** One batch of `rows`, columns `fields`. */
  private def batch(fields: Seq[(String, DataType)], rows: Seq[Seq[Any]]): (Schema, Iterator[ColumnarBatch]) = {
    val schema = Schema(fields.map { case (n, t) => Field(n, t) }.toVector)
    val b = new ColumnarBatch(schema, math.max(1, rows.size))
    rows.iterator.zipWithIndex.foreach { case (row, r) => row.indices.foreach(c => b.column(c).setBoxed(r, row(c))) }
    b.setNumRows(rows.size)
    (schema, Iterator(b))
  }

  /** What `print` writes for `rows` in `format`, through a stream of
    * `charset`: box-drawing characters when it encodes them, else ASCII. */
  private def printed(fields: Seq[(String, DataType)], rows: Seq[Seq[Any]], format: OutputFormat,
      maxRows: Int = 40, width: Option[Int] = None, charset: String = "US-ASCII"): String = {
    val (schema, batches) = batch(fields, rows)
    val bytes = new ByteArrayOutputStream()
    val out = new PrintStream(bytes, true, charset)
    ResultPrinter.print(out, schema, batches, format, maxRows, width)
    out.flush()
    bytes.toString(charset)
  }

  private def lines(s: String*): String = s.mkString("", "\n", "\n")

  private val mixed = Seq("name" -> DataType.StringType, "n" -> DataType.IntType, "x" -> DataType.DoubleType)

  private def chars(codePoints: Int*): String = codePoints.map(cp => new String(Character.toChars(cp))).mkString

  // ---- table ----------------------------------------------------------------------

  @Test def aTableHeadsEachColumnWithItsNameAndTypeAndAlignsNumbersRight(): Unit =
    assertEquals(lines(
      "+--------+-----+--------+",
      "| name   |   n |      x |",
      "| STRING | INT | DOUBLE |",
      "+--------+-----+--------+",
      "| a      |   1 |    1.5 |",
      "| NULL   |  22 |   NULL |",
      "+--------+-----+--------+",
      "2 rows"), printed(mixed, Seq(Seq("a", 1, 1.5), Seq(null, 22, null)), OutputFormat.Table))

  @Test def aTableOfNoRowsIsItsHeader(): Unit =
    assertEquals(lines(
      "+-----+",
      "|   n |",
      "| INT |",
      "+-----+",
      "0 rows"), printed(Seq("n" -> DataType.IntType), Nil, OutputFormat.Table))

  @Test def aTableShowsTheFirstAndLastRowsPastMaxRows(): Unit = {
    val ten = (1 to 10).map(i => Seq(i))
    assertEquals(lines(
      "+-----+",
      "|   i |",
      "| INT |",
      "+-----+",
      "|   1 |",
      "|   2 |",
      "| ... |",
      "|   9 |",
      "|  10 |",
      "+-----+",
      "10 rows (4 shown)"), printed(Seq("i" -> DataType.IntType), ten, OutputFormat.Table, maxRows = 4))
    // An odd limit shows one more of the first rows; 0 shows them all.
    assertTrue(printed(Seq("i" -> DataType.IntType), ten, OutputFormat.Table, maxRows = 3)
      .contains(lines("|   1 |", "|   2 |", "| ... |", "|  10 |")))
    assertTrue(printed(Seq("i" -> DataType.IntType), ten, OutputFormat.Table, maxRows = 0).endsWith(lines("+-----+", "10 rows")))
    assertTrue(printed(Seq("i" -> DataType.IntType), ten.take(1), OutputFormat.Table).endsWith(lines("1 row")))
  }

  @Test def theLastRowsAreFoundAcrossBatches(): Unit = {
    val schema = Schema(Vector(Field("i", DataType.IntType)))
    val batches = (0 until 5).map { k =>
      val b = new ColumnarBatch(schema, 3)
      (0 until 3).foreach(r => b.column(0).setBoxed(r, k * 3 + r + 1))
      b.setNumRows(3)
      b
    }
    val bytes = new ByteArrayOutputStream()
    val out = new PrintStream(bytes, true, "US-ASCII")
    ResultPrinter.print(out, schema, batches.iterator, OutputFormat.Table, maxRows = 6, width = None)
    assertTrue(bytes.toString("US-ASCII"),
      bytes.toString("US-ASCII").contains(lines("|   1 |", "|   2 |", "|   3 |", "| ... |", "|  13 |", "|  14 |", "|  15 |")))
  }

  @Test def aLongValueIsCutShort(): Unit = {
    val shown = printed(Seq("s" -> DataType.StringType), Seq(Seq("x" * 50)), OutputFormat.Table)
    assertTrue(shown, shown.contains("| " + "x" * 37 + "... |"))
  }

  @Test def columnsInTheMiddleGiveWayToTheWidth(): Unit = {
    val fields = (1 to 5).map(i => s"c$i" -> DataType.StringType)
    assertEquals(lines(
      "+------------+------------+-----+------------+",
      "| c1         | c2         | ... | c5         |",
      "| STRING     | STRING     | ... | STRING     |",
      "+------------+------------+-----+------------+",
      "| xxxxxxxxxx | xxxxxxxxxx | ... | xxxxxxxxxx |",
      "+------------+------------+-----+------------+",
      "1 row, 5 columns (3 shown)"), printed(fields, Seq(Seq.fill(5)("x" * 10)), OutputFormat.Table, width = Some(50)))
    // Wide enough, every column shows.
    assertTrue(printed(fields, Seq(Seq.fill(5)("x" * 10)), OutputFormat.Table, width = Some(66)).endsWith(lines("1 row")))
  }

  @Test def aWideCharacterTakesTwoColumns(): Unit = {
    val nihon = chars(0x65E5, 0x672C)
    assertEquals(4, ResultPrinter.displayWidth(nihon))
    assertTrue(printed(Seq("w" -> DataType.StringType), Seq(Seq(nihon), Seq("ab")), OutputFormat.Table, charset = "UTF-8")
      .contains(chars(0x2502) + " " + nihon + "   " + chars(0x2502)))
  }

  @Test def aTerminalThatEncodesThemGetsBoxDrawingCharacters(): Unit = {
    val drawn = ResultPrinter.box(Vector(BoxColumn("i", "INT", numeric = true)), Seq(Vector("1")), Seq(Vector("9")),
      total = 9, width = None, unicode = true)
    assertEquals(chars(0x250C) + chars(0x2500) * 5 + chars(0x2510), drawn.head)
    assertEquals(chars(0x2502) + "   " + chars(0x2026) + " " + chars(0x2502), drawn(5))
    assertEquals("9 rows (2 shown)", drawn.last)
  }

  @Test def aControlCharacterShowsAsAnEscape(): Unit = {
    assertEquals("a\\nb\\tc\\r" + "\\" + "u0001", ResultPrinter.shown("a\nb\tc\r" + 1.toChar))
    assertEquals("NULL", ResultPrinter.shown(null))
    assertEquals("plain", ResultPrinter.shown("plain"))
  }

  // ---- the other formats ---------------------------------------------------------------

  private val typed = Seq("s" -> DataType.StringType, "i" -> DataType.IntType, "d" -> DataType.DoubleType,
    "b" -> DataType.BooleanType)
  private val typedRows = Seq(Seq("a,b", 1, 2.5, true), Seq(null, null, Double.NaN, false))

  @Test def csvAndTsvAreWhatTheCsvWriterWrites(): Unit = {
    assertEquals(lines("s,i,d,b", "\"a,b\",1,2.5,true", ",,NaN,false"), printed(typed, typedRows, OutputFormat.Csv))
    assertEquals(lines("s\ti\td\tb", "a,b\t1\t2.5\ttrue", "\t\tNaN\tfalse"), printed(typed, typedRows, OutputFormat.Tsv))
    // An empty string is quoted, a NULL is not: each reads back as itself.
    assertEquals(lines("s", "\"\"", ""), printed(Seq("s" -> DataType.StringType), Seq(Seq(""), Seq(null)), OutputFormat.Csv))
  }

  @Test def jsonWritesNumbersAsNumbersAndTheRestAsStrings(): Unit = {
    assertEquals(
      "[{\"s\":\"a,b\",\"i\":1,\"d\":2.5,\"b\":true},\n{\"s\":null,\"i\":null,\"d\":\"NaN\",\"b\":false}]\n",
      printed(typed, typedRows, OutputFormat.Json))
    assertEquals(
      lines("{\"s\":\"a,b\",\"i\":1,\"d\":2.5,\"b\":true}", "{\"s\":null,\"i\":null,\"d\":\"NaN\",\"b\":false}"),
      printed(typed, typedRows, OutputFormat.JsonLines))
    assertEquals("[]\n", printed(typed, Nil, OutputFormat.Json))
    assertEquals("", printed(typed, Nil, OutputFormat.JsonLines))
    assertEquals(lines("{\"d\":\"2026-01-02\",\"q\":\"say \\\"hi\\\"\\n\"}"),
      printed(Seq("d" -> DataType.DateType, "q" -> DataType.StringType),
        Seq(Seq(java.time.LocalDate.of(2026, 1, 2).toEpochDay.toInt, "say \"hi\"\n")), OutputFormat.JsonLines))
  }

  @Test def jsonRefusesTwoColumnsOfOneName(): Unit =
    try {
      printed(Seq("a" -> DataType.IntType, "A" -> DataType.IntType), Seq(Seq(1, 2)), OutputFormat.Json)
      fail("expected a refusal")
    } catch {
      case e: IllegalArgumentException => assertTrue(e.getMessage, e.getMessage.contains("a JSON object holds a name once"))
    }

  @Test def markdownIsATableOfEveryRow(): Unit =
    assertEquals(lines(
      "| s    |    i |   d | b     |",
      "| ---- | ---: | --: | ----- |",
      "| a,b  |    1 | 2.5 | true  |",
      "| NULL | NULL | NaN | false |"), printed(typed, typedRows, OutputFormat.Markdown))

  @Test def aPipeInAMarkdownCellIsEscaped(): Unit =
    assertTrue(printed(Seq("s" -> DataType.StringType), Seq(Seq("a|b")), OutputFormat.Markdown).contains("| a\\|b |"))

  @Test def lineModeShowsAValueALine(): Unit =
    assertEquals(lines("s = a,b", "i = 1", "d = 2.5", "b = true", "", "s = NULL", "i = NULL", "d = NaN", "b = false"),
      printed(typed, typedRows, OutputFormat.Line))

  // ---- a result of several partitions, read on the pool ----------------------------------

  private val partitionedFields = Vector(Field("n", DataType.IntType), Field("s", DataType.StringType),
    Field("d", DataType.DoubleType), Field("q", DataType.DecimalType(9, 2)))
  private val partitionedSchema = Schema(partitionedFields)

  /** Row `g` of a partitioned result: NULLs, a value too long for a cell,
    * control characters, a pipe, NaN, a wide character. */
  private def partitionedRow(g: Int): Seq[Any] = Seq(
    g,
    if (g % 7 == 3) null
    else s"v$g" + (if (g % 11 == 5) "x" * 50 else "") + (if (g % 13 == 4) "\n|\t" else "") +
      (if (g % 17 == 6) chars(0x65E5) else ""),
    if (g % 5 == 2) Double.NaN else g * 0.5,
    if (g % 6 == 1) null else java.math.BigDecimal.valueOf(g.toLong, 2))

  /** A result of partitions of `sizes` rows, `perBatch` rows a batch, its
    * rows numbered across the partitions in order; the first sleeps `slow`
    * ms before its first batch. */
  private def partitioned(sizes: Seq[Int], perBatch: Int, slow: Long = 0L): ExecutedQuery = {
    val starts = sizes.scanLeft(0)(_ + _)
    val parts = sizes.indices.map { p =>
      val batches = (starts(p) until starts(p + 1)).grouped(perBatch).map { rows =>
        val b = new ColumnarBatch(partitionedSchema, rows.size)
        rows.zipWithIndex.foreach { case (g, r) => partitionedRow(g).zipWithIndex.foreach { case (v, c) => b.column(c).setBoxed(r, v) } }
        b.setNumRows(rows.size)
        b
      }
      if (p == 0 && slow > 0) new Iterator[ColumnarBatch] {
        private var slept = false
        def hasNext: Boolean = {
          if (!slept) { Thread.sleep(slow); slept = true }
          batches.hasNext
        }
        def next(): ColumnarBatch = batches.next()
      } else batches
    }
    new ExecutedQuery(partitionedSchema, parts)
  }

  /** What `print` writes for `q`, read on the pool, through a stream of
    * `charset`. */
  private def printedInParallel(q: ExecutedQuery, format: OutputFormat, maxRows: Int, width: Option[Int],
      charset: String): String = {
    val bytes = new ByteArrayOutputStream()
    val io = new Io(new PrintStream(bytes, true, charset), new PrintStream(new ByteArrayOutputStream(), true, charset))
    try ResultPrinter.print(io, q, io.cancel.child(), format, maxRows, width) finally q.close()
    io.out.flush()
    bytes.toString(charset)
  }

  /** What `print` writes for `q`'s batches in order, on this thread: the
    * reference. */
  private def printedInOrder(q: ExecutedQuery, format: OutputFormat, maxRows: Int, width: Option[Int],
      charset: String): String = {
    val bytes = new ByteArrayOutputStream()
    val out = new PrintStream(bytes, true, charset)
    try ResultPrinter.print(out, q.schema, q.batches, format, maxRows, width) finally q.close()
    out.flush()
    bytes.toString(charset)
  }

  /** Every format prints a result of several partitions, read on the pool,
    * byte for byte as it prints its batches in order: partitions of 0, 1,
    * H - 1, H, H + 1 and many rows, H the first rows a table shows, some
    * empty, a row or seven a batch, every row or 1, 2, 3, 40 or past the
    * total; a table also at a width its columns give way to, and in ASCII. */
  @Test(timeout = 120000) def aResultOfSeveralPartitionsPrintsAsItsBatchesInOrderDo(): Unit =
    for (perBatch <- Seq(1, 7); charset <- Seq("UTF-8", "US-ASCII")) {
      for (maxRows <- Seq(0, 1, 2, 3, 40, 1000, 5000); width <- Seq(None, Some(50))) {
        val h = if (maxRows == 0) 20 else (maxRows + 1) / 2
        val sizes = Seq(0, 1, h - 1, h, h + 1, 0, 600, 2, 0, 1)
        val where = s"table, maxRows $maxRows, width $width, $perBatch a batch, $charset"
        assertEquals(where,
          printedInOrder(partitioned(sizes, perBatch), OutputFormat.Table, maxRows, width, charset),
          printedInParallel(partitioned(sizes, perBatch), OutputFormat.Table, maxRows, width, charset))
      }
      val sizes = Seq(0, 1, 19, 20, 21, 0, 600, 2, 0, 1)
      for (format <- OutputFormat.All.filterNot(_ == OutputFormat.Table))
        assertEquals(s"$format, $perBatch a batch, $charset",
          printedInOrder(partitioned(sizes, perBatch), format, 40, None, charset),
          printedInParallel(partitioned(sizes, perBatch), format, 40, None, charset))
    }

  /** Results of no partition, of one, and of several that are all empty
    * print as they do in order. */
  @Test def emptyResultsPrintAsInOrder(): Unit =
    for (sizes <- Seq(Seq.empty[Int], Seq(0), Seq(5), Seq(0, 0, 0)); format <- OutputFormat.All)
      assertEquals(s"$format of $sizes",
        printedInOrder(partitioned(sizes, 2), format, 40, None, "UTF-8"),
        printedInParallel(partitioned(sizes, 2), format, 40, None, "UTF-8"))

  /** A slow first partition before 10,000 of a row each: the partitions
    * finished while it reads let go of the rows that cannot show, so the
    * table holds a bounded number of rows, not one or two a partition. */
  @Test(timeout = 120000) def aSlowFirstPartitionLeavesTheRowsHeldBounded(): Unit = {
    val sizes = 5 +: Seq.fill(10000)(1)
    val bytes = new ByteArrayOutputStream()
    val out = new PrintStream(bytes, true, "UTF-8")
    val q = partitioned(sizes, 1, slow = 300L)
    val slices =
      try ResultPrinter.tableOfPartitions(out, q, ParallelDrain.inFlight(q.numPartitions),
        new Cancel().drainStop, maxRows = 40, width = None)
      finally q.close()
    out.flush()
    assertEquals(printedInOrder(partitioned(sizes, 1), OutputFormat.Table, 40, None, "UTF-8"), bytes.toString("UTF-8"))
    assertTrue(s"the finished partitions held ${slices.mostRowsHeld} rows at once", slices.mostRowsHeld <= 400)
  }

  @Test def formatsAreNamedInAnyCase(): Unit = {
    assertEquals(Some(OutputFormat.JsonLines), OutputFormat.named("JSONL"))
    assertEquals(None, OutputFormat.named("xml"))
    assertEquals("table, line, csv, tsv, json, jsonl or markdown", OutputFormat.names)
  }
}
