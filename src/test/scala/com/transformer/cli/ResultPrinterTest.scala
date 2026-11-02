package com.transformer.cli

import com.transformer.cli.ResultPrinter.BoxColumn
import com.transformer.core._
import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets

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

  @Test def formatsAreNamedInAnyCase(): Unit = {
    assertEquals(Some(OutputFormat.JsonLines), OutputFormat.named("JSONL"))
    assertEquals(None, OutputFormat.named("xml"))
    assertEquals("table, line, csv, tsv, json, jsonl or markdown", OutputFormat.names)
  }
}
