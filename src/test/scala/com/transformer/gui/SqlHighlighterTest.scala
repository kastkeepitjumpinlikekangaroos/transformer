package com.transformer.gui

import com.transformer.gui.SqlHighlighter.{Kind, Token}

import org.junit.Assert._
import org.junit.Test

class SqlHighlighterTest {

  private def kinds(sql: String): Seq[Kind] =
    SqlHighlighter.tokenize(sql).filterNot(_.kind == Kind.Whitespace).map(_.kind)

  private def texts(sql: String): Seq[String] =
    SqlHighlighter.tokenize(sql).filterNot(_.kind == Kind.Whitespace).map(_.text)

  @Test def emptyAndNull(): Unit = {
    assertTrue(SqlHighlighter.tokenize("").isEmpty)
    assertTrue(SqlHighlighter.tokenize(null).isEmpty)
  }

  @Test def keywordsAreCaseInsensitive(): Unit = {
    val ks = kinds("SELECT select Select SeLeCt")
    assertEquals(Seq(Kind.Keyword, Kind.Keyword, Kind.Keyword, Kind.Keyword), ks)
  }

  @Test def functionsAreCaseInsensitive(): Unit = {
    val ks = kinds("COUNT count Count")
    assertEquals(Seq(Kind.Function, Kind.Function, Kind.Function), ks)
  }

  @Test def identifiersStayIdentifiers(): Unit = {
    val toks = SqlHighlighter.tokenize("foo bar123 _baz").filterNot(_.kind == Kind.Whitespace)
    assertEquals(Seq(Kind.Identifier, Kind.Identifier, Kind.Identifier), toks.map(_.kind))
    assertEquals(Seq("foo", "bar123", "_baz"), toks.map(_.text))
  }

  @Test def integerAndDecimalAndScientific(): Unit = {
    assertEquals(Seq(Kind.NumberLit), kinds("42"))
    assertEquals(Seq(Kind.NumberLit), kinds("3.14"))
    assertEquals(Seq(Kind.NumberLit), kinds(".5"))
    assertEquals(Seq(Kind.NumberLit), kinds("1.5e10"))
    assertEquals(Seq(Kind.NumberLit), kinds("2.0E-3"))
  }

  @Test def stringLiteralAndEscapedQuote(): Unit = {
    val toks = SqlHighlighter.tokenize("'hello' 'O''Brien'")
      .filterNot(_.kind == Kind.Whitespace)
    assertEquals(Seq(Kind.StringLit, Kind.StringLit), toks.map(_.kind))
    assertEquals(Seq("'hello'", "'O''Brien'"), toks.map(_.text))
  }

  @Test def unterminatedStringConsumesToEnd(): Unit = {
    val toks = SqlHighlighter.tokenize("'unclosed").filterNot(_.kind == Kind.Whitespace)
    assertEquals(1, toks.size)
    assertEquals(Kind.StringLit, toks.head.kind)
    assertEquals("'unclosed", toks.head.text)
  }

  @Test def lineComment(): Unit = {
    val toks = SqlHighlighter.tokenize("SELECT 1 -- trailing comment\nFROM t")
      .filterNot(_.kind == Kind.Whitespace)
    val kinds = toks.map(_.kind)
    assertEquals(Seq(Kind.Keyword, Kind.NumberLit, Kind.Comment, Kind.Keyword, Kind.Identifier), kinds)
    assertEquals("-- trailing comment", toks(2).text)
  }

  @Test def blockComment(): Unit = {
    val toks = SqlHighlighter.tokenize("/* a\nmulti-line */ SELECT")
      .filterNot(_.kind == Kind.Whitespace)
    assertEquals(Seq(Kind.Comment, Kind.Keyword), toks.map(_.kind))
    assertEquals("/* a\nmulti-line */", toks.head.text)
  }

  @Test def unclosedBlockCommentConsumesToEnd(): Unit = {
    val toks = SqlHighlighter.tokenize("SELECT /* never closes").filterNot(_.kind == Kind.Whitespace)
    assertEquals(Seq(Kind.Keyword, Kind.Comment), toks.map(_.kind))
    assertEquals("/* never closes", toks(1).text)
  }

  @Test def templatePlaceholder(): Unit = {
    val toks = SqlHighlighter.tokenize("'{{ today }}' AS d").filterNot(_.kind == Kind.Whitespace)
    assertEquals(
      Seq(Kind.StringLit, Kind.Keyword, Kind.Identifier),
      toks.map(_.kind)
    )
    // Template inside a string is part of the string — verify the string text
    // contains the placeholder verbatim.
    assertEquals("'{{ today }}'", toks.head.text)
  }

  @Test def topLevelTemplate(): Unit = {
    val toks = SqlHighlighter.tokenize("FROM events_{{ today }}").filterNot(_.kind == Kind.Whitespace)
    val k = toks.map(_.kind)
    assertEquals(Kind.Keyword, k.head)
    assertTrue("expected a Template token somewhere", k.contains(Kind.Template))
    val template = toks.find(_.kind == Kind.Template).get
    assertEquals("{{ today }}", template.text)
  }

  @Test def punctuationIsTagged(): Unit = {
    val toks = SqlHighlighter.tokenize("a, b; (c) = *").filterNot(_.kind == Kind.Whitespace)
    val k = toks.map(_.kind)
    assertEquals(
      Seq(
        Kind.Identifier, Kind.Punct, Kind.Identifier, Kind.Punct,
        Kind.Punct, Kind.Identifier, Kind.Punct, Kind.Punct, Kind.Punct
      ),
      k
    )
  }

  @Test def fullSelectQueryRoundTrips(): Unit = {
    val sql =
      """SELECT
        |  '{{ today }}' AS execution_date,
        |  tier,
        |  COUNT(*) AS event_count,
        |  SUM(amount) AS total_spend
        |FROM enriched_events
        |WHERE event = 'buy'
        |GROUP BY tier
        |ORDER BY tier""".stripMargin
    val toks = SqlHighlighter.tokenize(sql)
    // Reconstructing the original from token texts must be lossless.
    assertEquals(sql, toks.iterator.map(_.text).mkString)
    // Some sanity checks on key tokens.
    val keywords = toks.filter(_.kind == Kind.Keyword).map(_.text.toUpperCase).toSet
    assertTrue(keywords.contains("SELECT"))
    assertTrue(keywords.contains("FROM"))
    assertTrue(keywords.contains("WHERE"))
    assertTrue(keywords.contains("GROUP"))
    assertTrue(keywords.contains("BY"))
    assertTrue(keywords.contains("ORDER"))
    assertTrue(keywords.contains("AS"))
    val funcs = toks.filter(_.kind == Kind.Function).map(_.text.toUpperCase).toSet
    assertEquals(Set("COUNT", "SUM"), funcs)
    // The string literal should NOT be split by the embedded template.
    val strings = toks.filter(_.kind == Kind.StringLit).map(_.text)
    assertEquals(Seq("'{{ today }}'", "'buy'"), strings)
  }

  @Test def splitIntoLinesPreservesContent(): Unit = {
    val sql = "SELECT a\nFROM t\n\nWHERE x = 1"
    val lines = SqlHighlighter.splitIntoLines(SqlHighlighter.tokenize(sql))
    assertEquals(4, lines.size)
    assertEquals("SELECT a", lines(0).map(_.text).mkString)
    assertEquals("FROM t", lines(1).map(_.text).mkString)
    assertEquals("", lines(2).map(_.text).mkString)
    assertEquals("WHERE x = 1", lines(3).map(_.text).mkString)
  }

  @Test def windowFunctionKeywords(): Unit = {
    val sql = "SELECT RANK() OVER (PARTITION BY cat ORDER BY x ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW)"
    val toks = SqlHighlighter.tokenize(sql).filterNot(_.kind == Kind.Whitespace)
    val funcs = toks.filter(_.kind == Kind.Function).map(_.text.toUpperCase).toSet
    val keywords = toks.filter(_.kind == Kind.Keyword).map(_.text.toUpperCase).toSet
    assertTrue("RANK should be a function", funcs.contains("RANK"))
    assertTrue("OVER", keywords.contains("OVER"))
    assertTrue("PARTITION", keywords.contains("PARTITION"))
    assertTrue("ROWS", keywords.contains("ROWS"))
    assertTrue("UNBOUNDED", keywords.contains("UNBOUNDED"))
    assertTrue("PRECEDING", keywords.contains("PRECEDING"))
    assertTrue("CURRENT", keywords.contains("CURRENT"))
    assertTrue("ROW", keywords.contains("ROW"))
  }

  @Test def splitIntoLinesHandlesBlockCommentSpanningLines(): Unit = {
    val sql = "SELECT /* hi\nthere */ 1"
    val lines = SqlHighlighter.splitIntoLines(SqlHighlighter.tokenize(sql))
    assertEquals(2, lines.size)
    assertEquals("SELECT /* hi", lines.head.map(_.text).mkString)
    assertEquals("there */ 1", lines(1).map(_.text).mkString)
    // Both halves should still carry the Comment kind for their respective text.
    assertEquals(Kind.Comment, lines.head.last.kind)
    assertEquals(Kind.Comment, lines(1).head.kind)
  }
}
