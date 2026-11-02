package com.transformer.sql.parse

import net.sf.jsqlparser.JSQLParserException
import net.sf.jsqlparser.expression.{BinaryExpression, Expression}
import net.sf.jsqlparser.expression.operators.conditional.{AndExpression, OrExpression}
import net.sf.jsqlparser.parser.{CCJSqlParserConstants, CCJSqlParserUtil, Token}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.select.Select

import java.lang.reflect.{Method, Modifier}
import java.util.concurrent.{ConcurrentHashMap, TimeoutException}
import scala.collection.mutable
import scala.util.control.NonFatal

/** The refusal of SQL that nests deeper than [[SqlParser.MaxNesting]]. */
final class NestingLimitException(message: String) extends IllegalArgumentException(message)

object SqlParser {

  /** How deep a statement may nest, in the levels of the tree JSqlParser
    * parses it into: an operator, a call and its arguments, a CASE and its
    * WHEN, a pair of parentheses, a SELECT and its items each take one. Every
    * walk the engine makes over that tree, and over the plan it binds,
    * recurses once a level or so, and the shallowest overflow measured was at
    * about 600 levels on a 1 MB thread stack, Linux's default (macOS gives a
    * thread 2 MB), before the JIT had compiled anything. Within 256 every
    * walk keeps that margin on any machine, so a query either binds
    * everywhere or is refused everywhere. */
  val MaxNesting: Int = 256

  /** Parse one statement, refusing text the statement JSqlParser returns
    * would leave out: a second statement after `;`
    * ([[refuseTextAfterTheStatement]]), and a repeated `ORDER BY`, `LIMIT`,
    * `OFFSET` or `FETCH` ([[refuseLostTailClauses]]); and refusing a
    * statement too deep to walk: parentheses that do not balance or nest past
    * the limit, before JSqlParser sees them ([[refuseUnbalancedParentheses]]),
    * and a tree deeper than [[MaxNesting]] once AND and OR chains are
    * regrouped ([[regroupChains]], [[refuseDeepNesting]]), both of which walk
    * the tree without recursing. A check that recurses through the tree runs
    * once its depth is known to be within the limit. */
  def parse(sql: String): Statement = {
    val s = sql.trim
    if (s.isEmpty) throw new IllegalArgumentException("Empty SQL statement")
    refuseUnbalancedParentheses(s)
    val statement =
      try CCJSqlParserUtil.parse(s)
      catch { case e: JSQLParserException => throw parseFailure(s, e) }
    refuseTextAfterTheStatement(s)
    regroupChains(statement)
    refuseDeepNesting(statement)
    statement match {
      case select: Select => refuseLostTailClauses(s, select)
      case _ => ()
    }
    statement
  }

  /** The refusal for what JSqlParser threw on `sql`. It recurses once a
    * level, so text deep enough overflows its stack, and on some nesting
    * (CASTs, IFs and calls in calls) it backtracks for long enough to time
    * out: 25 nested CASTs do. It cannot read two signs in a row as an
    * operand's (`SELECT - -3`, `20 / - - 96`), and says so as an unexpected
    * token somewhere else, often the SELECT; that refusal names the signs. */
  private def parseFailure(sql: String, e: JSQLParserException): IllegalArgumentException = {
    val all = Iterator.iterate[Throwable](e)(_.getCause).takeWhile(_ != null).take(20).toSeq
    if (all.exists(_.isInstanceOf[StackOverflowError]))
      new NestingLimitException(s"The query nests too deeply to parse; the limit is $MaxNesting levels")
    else if (all.exists(_.isInstanceOf[TimeoutException]))
      new IllegalArgumentException("SQL parse error: the parser ran past its time limit, which deeply nested " +
        "CASTs, calls and parentheses can make it do; write the expression with less nesting", e)
    else stackedSigns(sql).getOrElse(new IllegalArgumentException(s"SQL parse error: ${parserMessage(e)}", e))
  }

  /** The refusal of two signs in a row, when they are why `sql` does not
    * parse: the first run of them, if the text with each run written as the
    * one sign it amounts to parses. A run whose first sign is an operator's,
    * `5 - -3`, parses as it is and never comes here. */
  private def stackedSigns(sql: String): Option[IllegalArgumentException] = {
    val runs = signRuns(sql)
    if (runs.isEmpty) return None
    val folded = new StringBuilder
    var copied = 0
    runs.foreach { case (from, until) =>
      folded ++= sql.substring(copied, from)
      folded += (if (sql.substring(from, until).count(_ == '-') % 2 == 1) '-' else '+')
      copied = until
    }
    folded ++= sql.substring(copied)
    val parses = try { CCJSqlParserUtil.parse(folded.result()); true } catch { case NonFatal(_) => false }
    if (!parses) None
    else {
      val (from, until) = runs.head
      val line = 1 + sql.substring(0, from).count(_ == '\n')
      val column = from - sql.lastIndexOf('\n', from - 1)
      Some(new IllegalArgumentException(
        s"SQL parse error: two signs in a row, '${sql.substring(from, until).filterNot(_.isWhitespace).mkString(" ")}' " +
          s"at line $line, column $column, cannot be parsed; put the second sign and what it applies to in " +
          "parentheses, as in -(-3)"))
    }
  }

  /** Where each run of two or more `+` / `-` signs is in `sql`, with only
    * spaces between them, from its first sign to just past its last: outside
    * string literals, quoted names and comments, which are read as the parser
    * reads them (`--` starts a comment, so `--3` is no run). */
  private def signRuns(sql: String): Seq[(Int, Int)] = {
    val runs = mutable.ArrayBuffer.empty[(Int, Int)]
    val n = sql.length
    def skipQuoted(open: Int, close: Char): Int = {
      var i = open + 1
      while (i < n && !(sql(i) == close && !(i + 1 < n && sql(i + 1) == close && close != ']'))) {
        if (sql(i) == close) i += 2 else i += 1
      }
      i + 1
    }
    var i = 0
    while (i < n) {
      val c = sql(i)
      if (c == '\'' || c == '"' || c == '`') i = skipQuoted(i, c)
      else if (c == '[') i = skipQuoted(i, ']')
      else if (c == '-' && i + 1 < n && sql(i + 1) == '-') {
        while (i < n && sql(i) != '\n') i += 1
      } else if (c == '/' && i + 1 < n && sql(i + 1) == '*') {
        val close = sql.indexOf("*/", i + 2)
        i = if (close < 0) n else close + 2
      } else if (c == '+' || c == '-') {
        var j = i + 1
        var last = i
        var signs = 1
        var k = j
        while (k < n && (sql(k).isWhitespace || sql(k) == '+' || sql(k) == '-') &&
            !(sql(k) == '-' && k + 1 < n && sql(k + 1) == '-')) {
          if (sql(k) == '+' || sql(k) == '-') { signs += 1; last = k }
          k += 1
        }
        if (signs >= 2) runs += ((i, last + 1))
        j = last + 1
        i = j
      } else i += 1
    }
    runs.toSeq
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
  /** An EXPLAIN statement: the query it explains, and whether it asks for
    * VERBOSE. */
  final case class Explained(query: String, verbose: Boolean)

  /** `sql` as an EXPLAIN statement, or None for SQL that is not one. The
    * query is the text after `EXPLAIN` and `VERBOSE`, which binds as any
    * query does. The other options EXPLAIN takes elsewhere (ANALYZE, which runs
    * the query, COSTS, BUFFERS, FORMAT, PLAN FOR) are refused. */
  def explained(sql: String): Option[Explained] = {
    val first =
      try tokens(sql).take(3).toVector
      catch { case NonFatal(_) => return None } // the query's own parse says why
    if (!first.headOption.exists(_.kind == CCJSqlParserConstants.K_EXPLAIN)) return None
    val verbose = first.lift(1).exists(_.kind == CCJSqlParserConstants.K_VERBOSE)
    first.drop(if (verbose) 2 else 1).headOption.filter(t => ExplainOptions(t.kind)).foreach { t =>
      throw new IllegalArgumentException(
        s"EXPLAIN ${t.image.toUpperCase(java.util.Locale.ROOT)} is not supported: EXPLAIN shows a query's plan " +
          "without running it, and EXPLAIN VERBOSE adds the plan as bound and the operators that run it")
    }
    val afterExplain = skipBlank(sql, 0) + "EXPLAIN".length
    val at = if (verbose) skipBlank(sql, afterExplain) + "VERBOSE".length else afterExplain
    Some(Explained(sql.substring(at), verbose))
  }

  private val ExplainOptions: Set[Int] = Set(CCJSqlParserConstants.K_ANALYZE, CCJSqlParserConstants.K_COSTS,
    CCJSqlParserConstants.K_BUFFERS, CCJSqlParserConstants.K_FORMAT, CCJSqlParserConstants.K_PLAN)

  /** Where the text from `from` on next holds something other than white
    * space and comments. */
  private def skipBlank(sql: String, from: Int): Int = {
    var i = from
    var moved = true
    while (moved) {
      moved = false
      while (i < sql.length && sql.charAt(i).isWhitespace) { i += 1; moved = true }
      if (sql.startsWith("--", i)) {
        while (i < sql.length && sql.charAt(i) != '\n') i += 1
        moved = true
      } else if (sql.startsWith("/*", i)) {
        val close = sql.indexOf("*/", i + 2)
        i = if (close < 0) sql.length else close + 2
        moved = true
      }
    }
    i
  }

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

  /** The text between the quotes of a JDBC escape literal, `{d '...'}`, as it
    * is written, read off the tokens the node was parsed from: the node keeps
    * only the date JSqlParser read the text as, leniently. None for a node
    * built without the parser. */
  def escapeLiteralText(literal: net.sf.jsqlparser.expression.DateValue): Option[String] =
    Option(literal.getASTNode).flatMap { node =>
      Iterator.iterate(node.jjtGetFirstToken)(_.next).takeWhile(_ != null).take(4)
        .find(_.image.startsWith("'")).map(token => stringLiteralValue(token.image))
    }

  /** A string literal's value: the text between its quotes, each doubled
    * quote one. */
  def stringLiteralValue(literal: String): String =
    if (literal.length >= 2 && literal.head == '\'' && literal.last == '\'')
      literal.substring(1, literal.length - 1).replace("''", "'")
    else literal

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

  /** Parentheses that do not balance, refused before JSqlParser sees them:
    * it backtracks on each one left open, so `SELECT ((((a +` failed after its
    * time limit, and `(((a +` took seven seconds. Parentheses nested past
    * [[MaxNesting]] are refused here too, before the parser recurses through
    * them. The text is lexed as the parser lexes it, so a parenthesis in a
    * string, a quoted name or a comment is no parenthesis; text the lexer
    * cannot read is left to the parser to report, and so is the text after
    * the statement's `;` ([[refuseTextAfterTheStatement]]). */
  private def refuseUnbalancedParentheses(sql: String): Unit = {
    val open = new java.util.ArrayDeque[Token]()
    val lexed = tokens(sql)
    var unreadable = false
    def next(): Option[Token] =
      try { if (lexed.hasNext) Some(lexed.next()) else None }
      catch { case NonFatal(_) => unreadable = true; None }
    var t = next()
    while (t.exists(_.kind != CCJSqlParserConstants.ST_SEMICOLON)) {
      val token = t.get
      if (token.image == "(") {
        open.push(token)
        if (open.size > MaxNesting) throw new NestingLimitException(
          s"The query nests more than $MaxNesting parentheses deep (at line ${token.beginLine}, column " +
            s"${token.beginColumn}); the limit is $MaxNesting levels")
      } else if (token.image == ")") {
        if (open.isEmpty) throw new IllegalArgumentException(
          s"SQL parse error: the ')' at line ${token.beginLine}, column ${token.beginColumn} closes no '('")
        open.pop()
      }
      t = next()
    }
    if (!open.isEmpty && !unreadable) throw new IllegalArgumentException(
      s"SQL parse error: the '(' at line ${open.peek.beginLine}, column ${open.peek.beginColumn} is never closed")
  }

  /** Each chain of one AND or OR operator regrouped as a balanced tree of the
    * same operator. JSqlParser builds `a OR b OR c` as `(a OR b) OR c`, one
    * level a term, so a list of conditions a query builder writes (`id = 1 OR
    * id = 2 OR ...`) nests as deep as it is long, and past a few hundred terms
    * overflowed the binder's stack. AND and OR are associative, also over
    * NULL, so the regrouped tree answers as the written one does, and the
    * nodes are reused in their order, so it renders as the same text. Other
    * operators keep their shape: `+` on doubles is not associative, nor is a
    * DECIMAL sum's type. */
  private def regroupChains(statement: Statement): Unit = {
    val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean])
    val todo = new java.util.ArrayDeque[AnyRef]()
    todo.push(statement)
    while (!todo.isEmpty) {
      val node = todo.pop()
      if (seen.add(node)) node match {
        case head: BinaryExpression if head.isInstanceOf[AndExpression] || head.isInstanceOf[OrExpression] =>
          val (links, operands) = chain(head)
          links.foreach(seen.add)
          if (operands.size > 2) balance(head, links, operands)
          operands.foreach(todo.push)
        case _ => Tree.children(node).foreach(todo.push)
      }
    }
  }

  /** The nodes of the chain of `head`'s operator below and including it, and
    * its operands, left to right. */
  private def chain(head: BinaryExpression): (Seq[BinaryExpression], Seq[Expression]) = {
    val links = mutable.ArrayBuffer.empty[BinaryExpression]
    val operands = mutable.ArrayBuffer.empty[Expression]
    val todo = new java.util.ArrayDeque[Expression]()
    todo.push(head)
    while (!todo.isEmpty) todo.pop() match {
      case link: BinaryExpression if link.getClass == head.getClass =>
        links += link
        todo.push(link.getRightExpression)
        todo.push(link.getLeftExpression)
      case operand => operands += operand
    }
    (links.toSeq, operands.toSeq)
  }

  /** `operands` under `links`, the n - 1 nodes of their chain, as a balanced
    * tree rooted at `head`, which its parent holds. Recursion is as deep as the
    * tree, a logarithm of the chain's length. */
  private def balance(head: BinaryExpression, links: Seq[BinaryExpression], operands: Seq[Expression]): Unit = {
    val spare = links.iterator.filter(_ ne head)
    def subtree(from: Int, until: Int): Expression =
      if (until - from == 1) operands(from) else join(spare.next(), from, until)
    def join(link: BinaryExpression, from: Int, until: Int): BinaryExpression = {
      val mid = (from + until) >>> 1
      link.setLeftExpression(subtree(from, mid))
      link.setRightExpression(subtree(mid, until))
      link
    }
    val _ = join(head, 0, operands.size)
  }

  /** Refuse a statement whose tree nests deeper than [[MaxNesting]], walking
    * it without recursion, so a statement of any depth is measured before any
    * walk that does recurse reaches it: this parser's own rendering of it
    * ([[refuseLostTailClauses]]), the rejection pass and the binder, and the
    * job runner's search for the tables a task reads. */
  private def refuseDeepNesting(statement: Statement): Unit = {
    val seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap[AnyRef, java.lang.Boolean])
    val todo = new java.util.ArrayDeque[(AnyRef, Int)]()
    todo.push((statement, 1))
    var deepest = 0
    while (!todo.isEmpty) {
      val (node, depth) = todo.pop()
      if (seen.add(node)) {
        deepest = math.max(deepest, depth)
        Tree.children(node).foreach(child => todo.push((child, depth + 1)))
      }
    }
    if (deepest > MaxNesting) throw new NestingLimitException(
      s"The query nests $deepest levels deep; the limit is $MaxNesting. Group a long chain of + or || in " +
        "parentheses, or compute part of the expression in a WITH clause")
  }

  /** The nodes a parsed statement holds below one of its nodes: what its
    * public getters return that JSqlParser parses SQL into, alone or in a
    * collection, and the elements of a node that is itself a list. Read by
    * reflection, so a node of any class is walked, as `toString` and
    * `TablesNamesFinder` walk it, not only those the binder reads. */
  private object Tree {
    private val getters = new ConcurrentHashMap[Class[_], Array[Method]]()

    def children(node: AnyRef): Seq[AnyRef] = {
      val out = mutable.ArrayBuffer.empty[AnyRef]
      def add(value: Any): Unit = value match {
        case null => ()
        case c: java.util.Collection[_] if !isNode(c) => c.forEach(e => if (isNode(e)) out += e.asInstanceOf[AnyRef])
        case n: AnyRef if isNode(n) => out += n
        case _ => ()
      }
      gettersOf(node.getClass).foreach { m =>
        // A getter that casts its node to another shape throws on the wrong one.
        try add(m.invoke(node)) catch { case _: ReflectiveOperationException | _: RuntimeException => () }
      }
      node match {
        case list: java.util.Collection[_] => list.forEach(e => if (isNode(e)) out += e.asInstanceOf[AnyRef])
        case _ => ()
      }
      out.toSeq
    }

    private def isNode(value: Any): Boolean =
      value != null && value.getClass.getName.startsWith("net.sf.jsqlparser.") && !value.getClass.isEnum

    /** Public no-argument getters a JSqlParser class declares that can return
      * a node, except the parser's plumbing (`getASTNode`, `getParent`). */
    private def gettersOf(cls: Class[_]): Array[Method] =
      getters.computeIfAbsent(cls, c => c.getMethods.filter { m =>
        val returns = m.getReturnType
        m.getParameterCount == 0 && !Modifier.isStatic(m.getModifiers) &&
          m.getDeclaringClass.getName.startsWith("net.sf.jsqlparser.") &&
          m.getName.matches("(get|is)[A-Z].*") && m.getName != "getASTNode" && m.getName != "getParent" &&
          (returns == classOf[Object] || classOf[java.util.Collection[_]].isAssignableFrom(returns) ||
            (returns.getName.startsWith("net.sf.jsqlparser.") && !returns.isEnum))
      })
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
