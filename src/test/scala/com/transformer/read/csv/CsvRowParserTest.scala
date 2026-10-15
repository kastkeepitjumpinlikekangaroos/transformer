package com.transformer.read.csv

import org.junit.Assert._
import org.junit.Test

import java.io.{ByteArrayInputStream, InputStreamReader, StringReader}
import java.nio.charset.{CodingErrorAction, StandardCharsets}

class CsvRowParserTest {

  private def parseAll(input: String, opts: CsvOptions = CsvOptions()): List[Array[String]] = {
    val parser = new CsvRowParser(new StringReader(input), opts)
    val out = collection.mutable.ListBuffer.empty[Array[String]]
    var row = parser.readRow()
    while (row.isDefined) { out += row.get; row = parser.readRow() }
    out.toList
  }

  @Test def simpleRow(): Unit = {
    val r = parseAll("a,b,c\n")
    assertEquals(1, r.size)
    assertArrayEquals(Array[Object]("a", "b", "c"), r.head.asInstanceOf[Array[Object]])
  }

  @Test def trailingFieldEmpty(): Unit = {
    // An unquoted empty field is the null string, so NULL.
    val r = parseAll("a,b,\n")
    assertEquals(1, r.size)
    assertArrayEquals(Array[Object]("a", "b", null), r.head.asInstanceOf[Array[Object]])
  }

  @Test def quotedFieldWithEmbeddedDelimiter(): Unit = {
    val r = parseAll("\"hello, world\",foo\n")
    assertEquals(1, r.size)
    assertArrayEquals(Array[Object]("hello, world", "foo"), r.head.asInstanceOf[Array[Object]])
  }

  @Test def quotedFieldWithDoubledQuoteEscape(): Unit = {
    val r = parseAll("\"he said \"\"hi\"\"\",ok\n")
    assertArrayEquals(Array[Object]("he said \"hi\"", "ok"), r.head.asInstanceOf[Array[Object]])
  }

  @Test def quotedFieldWithEmbeddedNewline(): Unit = {
    val r = parseAll("\"line1\nline2\",b\n")
    assertEquals(1, r.size)
    assertArrayEquals(Array[Object]("line1\nline2", "b"), r.head.asInstanceOf[Array[Object]])
  }

  @Test def crlfLineEndings(): Unit = {
    val r = parseAll("a,b\r\nc,d\r\n")
    assertEquals(2, r.size)
    assertArrayEquals(Array[Object]("a", "b"), r(0).asInstanceOf[Array[Object]])
    assertArrayEquals(Array[Object]("c", "d"), r(1).asInstanceOf[Array[Object]])
  }

  @Test def lastLineWithoutNewline(): Unit = {
    val r = parseAll("a,b\nc,d")
    assertEquals(2, r.size)
    assertArrayEquals(Array[Object]("c", "d"), r(1).asInstanceOf[Array[Object]])
  }

  /** A blank line is a row of one unquoted empty field, NULL by default, and
    * says so: whether it is a row is the reader's call, which knows how many
    * columns the file has. It used to be skipped here, so a one-column file
    * lost every NULL row. */
  @Test def aBlankLineIsARowOfOneEmptyField(): Unit = {
    val parser = new CsvRowParser(new StringReader("a\n\n\"\"\nb\n"), CsvOptions())
    def next(): (Seq[String], Boolean) = { val r = parser.readRow().get.toSeq; (r, parser.blank) }
    assertEquals((Seq("a"), false), next())
    assertEquals((Seq(null), true), next())
    assertEquals((Seq(""), false), next())  // quoted: the empty string, and no blank line
    assertEquals((Seq("b"), false), next())
    assertEquals(None, parser.readRow())
    // Under another null string, the blank line's field is the empty string.
    val other = new CsvRowParser(new StringReader("\n"), CsvOptions(nullValue = "NULL"))
    assertEquals(Seq(""), other.readRow().get.toSeq)
    assertTrue(other.blank)
  }

  @Test def emptyInputReturnsNothing(): Unit = {
    assertEquals(0, parseAll("").size)
    assertEquals(1, parseAll("\n").size)
    assertEquals(3, parseAll("\n\n\n").size)
  }

  /** NULL is the null string written without quotes; a quoted field is always
    * its text. Every empty field used to be NULL, and so was the text of the
    * null string even in quotes. */
  @Test def anUnquotedNullStringIsNullAndAQuotedOneIsText(): Unit = {
    assertEquals(Seq("a", "", null), parseAll("a,\"\",\n").head.toSeq)
    val opts = CsvOptions(nullValue = "NULL")
    assertEquals(Seq(null, "NULL", "", "NULLS"), parseAll("NULL,\"NULL\",,NULLS\n", opts).head.toSeq)
  }

  /** A UTF-8 file may start with a byte-order mark, which Java's decoder keeps
    * as U+FEFF; it is not text, and used to begin the first column's name. */
  @Test def aByteOrderMarkIsNotText(): Unit = {
    val r = parseAll(s"${0xfeff.toChar}id,name\n1,a\n")
    assertEquals(Seq("id", "name"), r.head.toSeq)
    // Only before the first row: inside the file it is a character.
    assertEquals(Seq("1", s"${0xfeff.toChar}a"), parseAll("1," + 0xfeff.toChar + "a\n").head.toSeq)
  }

  /** A quote left open at the end of the file used to take the rest of the file
    * into one field: three rows read as one. */
  @Test def anUnclosedQuoteFailsNamingItsLine(): Unit = {
    val parser = new CsvRowParser(new StringReader("a,b\n1,\"x\n2,y\n3,z\n"), CsvOptions(), "rows.csv")
    parser.readRow()
    val msg = failure(parser.readRow())
    assertTrue(msg, msg.contains("rows.csv") && msg.contains("line 2") && msg.contains("never closed"))
  }

  /** Bytes that are no text in the charset fail, where the decoder used to
    * read them as U+FFFD (`CsvReader` names the line). */
  @Test def bytesThatAreNoTextFail(): Unit = {
    val bytes = "a\nb".getBytes(StandardCharsets.UTF_8) ++ Array[Byte](0xff.toByte, 0x0a)
    val in = new InputStreamReader(new ByteArrayInputStream(bytes),
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT))
    val parser = new CsvRowParser(in, CsvOptions(), "bytes.csv")
    parser.readRow()
    val msg = failure(parser.readRow())
    assertTrue(msg, msg.contains("bytes.csv") && msg.contains("UTF-8"))
  }

  @Test def eachRowKnowsTheLineItStartsOn(): Unit = {
    val parser = new CsvRowParser(new StringReader("a\r\n\"b\nc\",d\r\n\ne\rf"), CsvOptions())
    val lines = Iterator.continually(parser.readRow()).takeWhile(_.isDefined).map(_ => parser.line).toList
    assertEquals(List(1L, 2L, 4L, 5L, 6L), lines)
  }

  /** With an escape of its own, the escape escapes itself: the writer escapes
    * both, so `"a\\b"` is `a\b`. */
  @Test def anEscapeEscapesItself(): Unit = {
    val opts = CsvOptions(escape = '\\')
    assertEquals(Seq("a\\b", "c\\"), parseAll("\"a\\\\b\",\"c\\\\\"\n", opts).head.toSeq)
  }

  private def failure(body: => Any): String =
    try { body; fail("expected a failure"); "" }
    catch {
      case e: AssertionError => throw e
      case e: IllegalArgumentException => e.getMessage
    }

  @Test def alternateDelimiterTab(): Unit = {
    val r = parseAll("a\tb\tc\n", CsvOptions(delimiter = '\t', header = false))
    assertArrayEquals(Array[Object]("a", "b", "c"), r.head.asInstanceOf[Array[Object]])
  }

  @Test def separateEscapeCharacter(): Unit = {
    val opts = CsvOptions(escape = '\\')
    val r = parseAll("\"hello\\\" world\",b\n", opts)
    assertArrayEquals(Array[Object]("hello\" world", "b"), r.head.asInstanceOf[Array[Object]])
  }
}
