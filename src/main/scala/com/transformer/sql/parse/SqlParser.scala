package com.transformer.sql.parse

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select

object SqlParser {

  def parse(sql: String): Statement = {
    val s = sql.trim
    if (s.isEmpty) throw new IllegalArgumentException("Empty SQL statement")
    try CCJSqlParserUtil.parse(s)
    catch {
      case e: JSQLParserException =>
        throw new IllegalArgumentException(s"SQL parse error: ${parserMessage(e)}", e)
    }
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
}
