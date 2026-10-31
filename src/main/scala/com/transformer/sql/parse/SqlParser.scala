package com.transformer.sql.parse

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.{CCJSqlParserConstants, CCJSqlParserUtil, Token}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select

import scala.util.control.NonFatal

object SqlParser {

  /** Parse one statement, refusing text the statement JSqlParser returns
    * would leave out: a second statement after `;`
    * ([[refuseTextAfterTheStatement]]), and a repeated `ORDER BY`, `LIMIT`,
    * `OFFSET` or `FETCH` ([[refuseLostTailClauses]]). */
  def parse(sql: String): Statement = {
    val s = sql.trim
    if (s.isEmpty) throw new IllegalArgumentException("Empty SQL statement")
    val statement =
      try CCJSqlParserUtil.parse(s)
      catch {
        case e: JSQLParserException =>
          throw new IllegalArgumentException(s"SQL parse error: ${parserMessage(e)}", e)
      }
    refuseTextAfterTheStatement(s)
    statement match {
      case select: Select => refuseLostTailClauses(s, select)
      case _ => ()
    }
    statement
  }

  /** What the parser said went wrong (`Encountered unexpected token: "FROM"
    * ... at line 1, column 10`). JSqlParser parses on a thread of its own and
    * gives its exception the message of what that thread threw, which is the
    * `toString` of the parser's own exception and starts with its class name;
    * the innermost exception that has a message says the same without it. */
  private def parserMessage(e: JSQLParserException): String = {
    var message = e.getMessage
    var cause = e.getCause
    while (cause != null) {
      if (cause.getMessage != null) message = cause.getMessage
      cause = cause.getCause
    }
    message
  }

  /** Convenience: parse and require a `SELECT` (the only kind v1 executes).
    * Returns the abstract [[Select]] base — callers dispatch over `PlainSelect`
    * vs `SetOperationList` (UNION) vs `ParenthesedSelect` themselves rather
    * than going through `Select#getPlainSelect`, which `ClassCastException`s
    * on set operations.
    */
  def parseSelect(sql: String): Select = parse(sql) match {
    case sel: Select => sel
    case _ => throw new IllegalArgumentException("Only SELECT statements are supported")
  }

  /** The refusal of a clause a query's tail carries twice. A query takes one
    * of each, wherever JSqlParser puts the second. */
  def writtenTwice(clause: String): IllegalArgumentException =
    new IllegalArgumentException(s"$clause is written twice for one query; keep one")

  /** The name an identifier written as `written` names. JSqlParser keeps an
    * identifier as written, quotes and all; in double quotes or backquotes
    * the name is the text between them, each doubled quote one quote, so
    * `"x y"` names `x y` and `"a""b"` names `a"b`. Quotes let a name hold a
    * space, a symbol or a keyword; they do not make it case-sensitive, since
    * the engine matches every name it resolves without regard to case. */
  def unquote(written: String): String =
    if (written.length >= 2 && IdentifierQuotes(written.head) && written.last == written.head) {
      val q = written.head.toString
      val name = written.substring(1, written.length - 1).replace(q + q, q)
      if (name.isEmpty) throw new IllegalArgumentException(s"A name in quotes cannot be empty: $written")
      name
    } else written

  /** `name` as an identifier [[unquote]] reads back as exactly `name`, whatever
    * characters it holds. */
  def quote(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

  private val IdentifierQuotes: Set[Char] = Set('"', '`')

  /** JSqlParser parses one statement and stops at the `;` after it, so
    * anything written after that would never run: a second query, or `DROP
    * TABLE t`. A trailing `;` is fine, and so are more of them and a comment.
    * The parser has read the text up to that `;`, so lexing can fail only
    * past it, on text that is no statement either. */
  private def refuseTextAfterTheStatement(sql: String): Unit = {
    val afterTheStatement = tokens(sql).dropWhile(_.kind != CCJSqlParserConstants.ST_SEMICOLON)
    val more = try afterTheStatement.exists(_.kind != CCJSqlParserConstants.ST_SEMICOLON)
    catch { case NonFatal(_) => true }
    if (more) throw new IllegalArgumentException("Only one statement is supported; remove the text after ';'")
  }

  /** The keyword each clause of a query's tail starts with, and its name. */
  private val TailClauses: Seq[(Int, String)] = Seq(
    CCJSqlParserConstants.K_ORDER -> "ORDER BY",
    CCJSqlParserConstants.K_LIMIT -> "LIMIT",
    CCJSqlParserConstants.K_OFFSET -> "OFFSET",
    CCJSqlParserConstants.K_FETCH -> "FETCH")

  /** JSqlParser parses a query's ORDER BY, LIMIT, OFFSET and FETCH in more
    * than one production (the query's own, and the one around it), and when a
    * clause is written twice the second sets the same field again: the query
    * keeps the last and nothing records the first, so `ORDER BY a DESC ORDER
    * BY a ASC LIMIT 1` returned the smallest `a`. The statement then renders
    * with fewer of that clause's keyword than the text has, which is how the
    * loss shows. The two are lexed alike, so a column named `limit` or
    * `offset` counts in both. A statement that does not render holds a node
    * the binder refuses by name (a CAST to a ROW type), so the check leaves
    * it to that refusal. The other place a second clause goes, the last arm
    * of a UNION, the binder checks. */
  private def refuseLostTailClauses(sql: String, select: Select): Unit = {
    val kept = try Some(keywordCounts(select.toString)) catch { case NonFatal(_) => None }
    kept.foreach { k =>
      val written = keywordCounts(sql)
      TailClauses.foreach { case (kind, clause) =>
        if (written.getOrElse(kind, 0) > k.getOrElse(kind, 0)) throw writtenTwice(clause)
      }
    }
  }

  private def keywordCounts(text: String): Map[Int, Int] =
    tokens(text).map(_.kind).toSeq.groupBy(identity).map { case (kind, all) => kind -> all.size }

  /** `text` as the parser's tokens, comments left out. A parser of its own
    * lexes it, so each token is read under the parser's feature configuration,
    * which the lexer consults for a string holding `\'`; a bare token manager
    * has none. */
  private def tokens(text: String): Iterator[Token] = {
    val parser = CCJSqlParserUtil.newParser(text)
    Iterator.continually(parser.getNextToken()).takeWhile(_.kind != CCJSqlParserConstants.EOF)
  }
}
