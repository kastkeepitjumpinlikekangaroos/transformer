package com.transformer.sql.parse

import net.sf.jsqlparser.parser.CCJSqlParserUtil
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select

object SqlParser {

  def parse(sql: String): Statement = {
    val s = sql.trim
    if (s.isEmpty) throw new IllegalArgumentException("Empty SQL statement")
    try CCJSqlParserUtil.parse(s)
    catch {
      case e: net.sf.jsqlparser.JSQLParserException =>
        throw new IllegalArgumentException(s"SQL parse error: ${e.getMessage}", e)
    }
  }

  /** Convenience: parse and require a `SELECT` (the only kind v1 executes).
    * Returns the abstract [[Select]] base — callers dispatch over `PlainSelect`
    * vs `SetOperationList` (UNION) vs `ParenthesedSelect` themselves rather
    * than going through `Select#getPlainSelect`, which `ClassCastException`s
    * on set operations.
    */
  def parseSelect(sql: String): Select = parse(sql) match {
    case sel: Select => sel
    case other =>
      throw new IllegalArgumentException(s"Only SELECT statements are supported (got ${other.getClass.getSimpleName})")
  }
}
