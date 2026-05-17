package com.transformer.read.csv

import org.junit.Assert._
import org.junit.Test

import java.io.StringReader

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
    val r = parseAll("a,b,\n")
    assertEquals(1, r.size)
    assertArrayEquals(Array[Object]("a", "b", ""), r.head.asInstanceOf[Array[Object]])
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

  @Test def blankLinesSkipped(): Unit = {
    val r = parseAll("a,b\n\nc,d\n\n")
    assertEquals(2, r.size)
  }

  @Test def emptyInputReturnsNothing(): Unit = {
    assertEquals(0, parseAll("").size)
    assertEquals(0, parseAll("\n").size)
    assertEquals(0, parseAll("\n\n\n").size)
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
