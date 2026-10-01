package com.transformer.sql.plan

import net.sf.jsqlparser.expression.{
  Alias, AllValue, AnalyticExpression, AnalyticType, CaseExpression, CastExpression, DateValue,
  DoubleValue, Function, LongValue, NotExpression, NullValue, SignedExpression, StringValue,
  TimeValue, TimestampValue, TrimFunction, WhenClause, WindowElement, WindowOffset, WindowRange
}
import net.sf.jsqlparser.expression.operators.arithmetic.{
  Addition, Concat, Division, Modulo, Multiplication, Subtraction
}
import net.sf.jsqlparser.expression.operators.conditional.{AndExpression, OrExpression}
import net.sf.jsqlparser.expression.operators.relational.{
  Between, EqualsTo, ExpressionList, GreaterThan, GreaterThanEquals, InExpression,
  IsNullExpression, LikeExpression, MinorThan, MinorThanEquals, NotEqualsTo,
  ParenthesedExpressionList
}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.Statement
import net.sf.jsqlparser.statement.create.table.ColDataType
import net.sf.jsqlparser.statement.select.{
  AllColumns, AllTableColumns, Distinct, GroupByElement, Join, Limit, Offset, OrderByElement,
  ParenthesedSelect, PlainSelect, SelectItem, SetOperationList, UnionOp, WithItem
}

import java.lang.reflect.{Field => JField, InvocationTargetException, Method, Modifier}
import java.util.concurrent.ConcurrentHashMap
import scala.jdk.CollectionConverters._

/** Thrown for a clause JSqlParser parses but the engine does not implement.
  * It is an [[IllegalArgumentException]] like every other bind-time rejection;
  * `rejection` is the [[UnsupportedClauses]] entry that fired. */
final class UnsupportedClauseException(val rejection: UnsupportedClauses.Rejection, message: String)
  extends IllegalArgumentException(message)

/** The rejection pass that runs before [[LogicalBuilder]] binds a statement,
  * and the audit it is built from.
  *
  * The binder walks the JSqlParser AST by calling the getters it knows about,
  * so a clause carried by a getter it never calls does not error: it vanishes,
  * and the query answers a different question from the one asked
  * (`JOIN ... USING` used to run as a cartesian product). This object lists,
  * for every AST class the binder reaches, each getter the binder does not
  * read, as either
  *
  *   - a [[Rejection]]: the getter carries a clause the engine does not
  *     implement, and [[reject]] throws an [[UnsupportedClauseException]]
  *     naming it; or
  *   - inert: the getter carries nothing the binder could lose (a spelling, a
  *     structural accessor, another view of a value the binder does read), with
  *     the reason.
  *
  * A getter the binder does read needs no entry. `UnsupportedClausesTest`
  * holds the three sets to account: it derives what `LogicalBuilder` reads from
  * its compiled bytecode and fails when a public getter or field of an audited
  * class is in none of the sets, or in two. That is the part that survives a
  * JSqlParser upgrade: a new getter fails the test until someone decides what
  * it means.
  *
  * [[reject]] walks the statement reflectively and descends only through the
  * getters the binder reads, so it inspects exactly the nodes binding would
  * visit, in every position they can appear in. A node whose class has no
  * audit stops the walk: the binder rejects those by name.
  */
object UnsupportedClauses {

  /** One clause the engine does not implement, and the getters that carry it.
    *
    * `partial` marks a clause whose getters the binder does read, honouring some
    * uses and not others (`DISTINCT` in `COUNT(DISTINCT x)` but not in
    * `SUM(DISTINCT x)`); the rejection covers the uses it does not honour.
    *
    * `example` is SQL that reaches this entry, written against `t(a, b, s)` and
    * `u(a, b)`; `UnsupportedClausesTest` runs every one. `None` means no SELECT
    * text JSqlParser 5.0 accepts reaches the getter in a position the walk
    * visits, and the entry stands guard against a grammar change. */
  final class Rejection private[UnsupportedClauses] (
      val getters: Seq[String],
      val clause: String,
      val advice: String,
      val example: Option[String],
      val partial: Boolean,
      present: AnyRef => Boolean,
      describe: AnyRef => String) {

    /** Whether `node` uses the clause. */
    def appliesTo(node: AnyRef): Boolean = present(node)

    def message(node: AnyRef): String = {
      val what = describe(node)
      if (advice.isEmpty) s"$what is not supported" else s"$what is not supported; $advice"
    }

    override def toString: String = s"Rejection($clause: ${getters.mkString(", ")})"
  }

  /** What the pass knows about one AST class: the clauses it rejects, checked
    * in order, and the getters it ignores, each with the reason. */
  final case class Audit(rejections: Seq[Rejection], inert: Map[String, String])

  /** Throw an [[UnsupportedClauseException]] for the first clause in
    * `statement` that the binder would otherwise drop. */
  def reject(statement: Statement): Unit = new Walk().visit(statement)

  // ---------------------------------------------------------------------------
  // Building entries
  // ---------------------------------------------------------------------------

  /** A clause that is present when any of `getters` returns something: non-null,
    * `true`, a non-empty collection or string, or a non-zero number. */
  private def rejects(getters: String*)(clause: String, advice: String = "", example: String = null): Rejection =
    new Rejection(getters, clause, advice, Option(example), partial = false,
      node => getters.exists(g => isPresent(read(node, g))), _ => clause)

  /** A clause whose presence needs more than "the getter returned something",
    * optionally described per node. */
  private def rejectsWhen[N <: AnyRef](getters: String*)(
      clause: String,
      advice: String = "",
      example: String = null,
      partial: Boolean = false,
      describe: N => String = null)(test: N => Boolean): Rejection =
    new Rejection(getters, clause, advice, Option(example), partial,
      node => test(node.asInstanceOf[N]),
      node => if (describe == null) clause else describe(node.asInstanceOf[N]))

  private def audit(rejections: Seq[Rejection], inert: Seq[(String, String)] = Nil): Audit =
    Audit(rejections.toVector, inert.toMap)

  private def isPresent(v: Any): Boolean = v match {
    case null                       => false
    case b: java.lang.Boolean       => b.booleanValue
    case c: java.util.Collection[_] => !c.isEmpty
    case n: java.lang.Number        => n.longValue != 0L
    case s: String                  => s.nonEmpty
    case _                          => true
  }

  private def upper(name: String): String = if (name == null) "" else name.toUpperCase

  // ---------------------------------------------------------------------------
  // Entries shared by several classes
  // ---------------------------------------------------------------------------

  // Clauses every SELECT shape inherits from `Select`.
  private val FetchClause = rejects("getFetch")("FETCH FIRST / FETCH NEXT",
    advice = "write LIMIT n", example = "SELECT a FROM t FETCH FIRST 2 ROWS ONLY")
  private val LimitByClause = rejects("getLimitBy")("LIMIT n BY",
    example = "SELECT a FROM t LIMIT 1 BY a")
  private val LockingClause =
    rejects("getForMode", "getForUpdateTable", "getWait", "isNoWait", "isSkipLocked")(
      "FOR UPDATE / FOR SHARE", advice = "the engine never locks rows; remove the clause",
      example = "SELECT a FROM t FOR UPDATE")
  private val ForClause = rejects("getForClause")("FOR XML / FOR JSON / FOR BROWSE",
    example = "SELECT a FROM t FOR JSON AUTO")
  private val IsolationClause = rejects("getIsolation")("WITH UR / CS / RS / RR",
    example = "SELECT a FROM t WITH UR")
  private val SiblingsClause = rejects("isOracleSiblings")("ORDER SIBLINGS BY",
    advice = "write ORDER BY", example = "SELECT a FROM t ORDER SIBLINGS BY a")

  private val SelectTail: Seq[Rejection] = Seq(
    FetchClause, LimitByClause, LockingClause, ForClause, IsolationClause, SiblingsClause)

  // Accessors on `Select` that return the node itself, or cast it.
  private val SelectCasts: Seq[(String, String)] = Seq(
    "getSelectBody" -> "returns this node",
    "getPlainSelect" -> "a cast of this node, or of the query inside the parentheses",
    "getSetOperationList" -> "a cast of this node, or of the query inside the parentheses",
    "getValues" -> "a cast of this node, or of the query inside the parentheses")

  // ORDER BY / LIMIT / OFFSET written after a parenthesized query. The binder
  // reads them on a PlainSelect and a SetOperationList, never on the parentheses.
  private val ParenthesizedTail = rejects("getOrderByElements", "getLimit", "getOffset")(
    "ORDER BY / LIMIT / OFFSET after a parenthesized query",
    advice = "move it inside the parentheses",
    example = "(SELECT a FROM t) ORDER BY a LIMIT 1")
  private val ParenthesizedPivot = rejects("getPivot", "getUnPivot")("PIVOT / UNPIVOT")

  private val ParenthesizedInert: Seq[(String, String)] = SelectCasts ++ Seq(
    "getAlias" -> ("nothing can refer to an alias on a top-level query, a set-operation arm or a " +
      "CTE body, and in FROM the binder rejects the derived table whole"))

  // Every comparison, and IN, can carry Oracle's outer-join and PRIOR markers.
  private val OracleOuterJoin = rejects("getOldOracleJoinSyntax")("The Oracle outer-join marker (+)",
    advice = "write LEFT JOIN or RIGHT JOIN with ON",
    example = "SELECT t.a FROM t, u WHERE t.a = u.a(+)")
  private val OraclePrior = rejects("getOraclePriorPosition")("PRIOR",
    example = "SELECT a FROM t WHERE PRIOR a = b")
  private val Comparison: Audit = audit(Seq(OracleOuterJoin, OraclePrior),
    Seq("getStringExpression" -> "the operator as written, e.g. <> or !="))

  private val Arithmetic: Audit = audit(Nil, Seq("getStringExpression" -> "the operator as written"))

  private val StarModifiers: Seq[Rejection] = Seq(
    rejectsWhen[AllColumns]("getExceptColumns", "getExceptKeyword")(
      "* EXCEPT (...) / * EXCLUDE (...)",
      advice = "list the columns you want",
      example = "SELECT * EXCEPT (a) FROM t",
      describe = c => s"* ${Option(c.getExceptKeyword).map(_.toUpperCase).getOrElse("EXCEPT")} (...)"
    )(c => isPresent(c.getExceptColumns) || isPresent(c.getExceptKeyword)),
    rejects("getReplaceExpressions")("* REPLACE (...)",
      advice = "list the columns you want", example = "SELECT * REPLACE (a + 1 AS a) FROM t"))

  private val ListShape: Seq[(String, String)] = Seq(
    "getExpressions" -> "returns this list",
    "isUsingBrackets" -> "whether the list was parenthesized")

  private val RankingFunctions = Set("ROW_NUMBER", "RANK", "DENSE_RANK")
  private val DistinctAggregates = Set("COUNT", "SUM", "AVG", "MIN", "MAX")
  private val OffsetFunctions = Set("LAG", "LEAD")

  // ---------------------------------------------------------------------------
  // Statements and FROM
  // ---------------------------------------------------------------------------

  private val PlainSelectAudit = audit(
    Seq(
      rejects("getTop")("TOP n", advice = "write LIMIT n", example = "SELECT TOP 2 a FROM t"),
      rejects("getSkip", "getFirst")("SKIP n / FIRST n", advice = "write LIMIT n",
        example = "SELECT FIRST 2 a FROM t"),
      rejects("getQualify")("QUALIFY",
        advice = "compute the window function in a WITH clause and filter on it in the outer WHERE",
        example = "SELECT a, ROW_NUMBER() OVER (ORDER BY a) AS n FROM t QUALIFY n = 1"),
      rejects("getWindowDefinitions")("WINDOW <name> AS (...)",
        advice = "write the window specification inline in OVER (...)",
        example = "SELECT ROW_NUMBER() OVER w FROM t WINDOW w AS (ORDER BY a)"),
      rejects("getIntoTables", "getIntoTempTable", "isUseWithNoLog")("SELECT ... INTO",
        advice = "a query only returns rows", example = "SELECT a INTO v FROM t"),
      rejects("getOracleHierarchical")("START WITH / CONNECT BY",
        example = "SELECT a FROM t START WITH a = 1 CONNECT BY PRIOR a = b"),
      rejects("getLateralViews")("LATERAL VIEW",
        example = "SELECT a FROM t LATERAL VIEW explode(b) e AS c"),
      // JSqlParser 5.0 never sets it: FOR XML PATH parses into getForClause.
      rejects("getForXmlPath")("FOR XML PATH"),
      rejects("getBigQuerySelectQualifier")("SELECT AS STRUCT / SELECT AS VALUE",
        example = "SELECT AS STRUCT a FROM t"),
      rejects("getKsqlWindow", "isEmitChanges")("WINDOW TUMBLING / HOPPING / SESSION or EMIT CHANGES",
        example = "SELECT a FROM t WINDOW TUMBLING (SIZE 30 SECONDS)"),
      rejects("getOracleHint")("An optimizer hint /*+ ... */",
        example = "SELECT /*+ INDEX(t) */ a FROM t"),
      rejects("getMySqlHintStraightJoin")("SELECT STRAIGHT_JOIN",
        example = "SELECT STRAIGHT_JOIN a FROM t"),
      rejects("getMySqlSqlCacheFlag")("SQL_CACHE / SQL_NO_CACHE",
        example = "SELECT SQL_NO_CACHE a FROM t"),
      rejects("getMySqlSqlCalcFoundRows")("SQL_CALC_FOUND_ROWS",
        example = "SELECT SQL_CALC_FOUND_ROWS a FROM t"),
      rejects("getOptimizeFor")("OPTIMIZE FOR n ROWS", example = "SELECT a FROM t OPTIMIZE FOR 5 ROWS"),
      rejects("isUsingFinal")("FROM ... FINAL", example = "SELECT a FROM t FINAL"),
      rejects("isUsingOnly")("FROM ONLY", example = "SELECT a FROM ONLY t")
    ) ++ SelectTail,
    SelectCasts :+ ("isUseBrackets" -> "always false in JSqlParser 5.0; a parenthesized query parses as ParenthesedSelect"))

  private val TableAudit = audit(
    Seq(
      rejectsWhen[Table]("getSchemaName", "getCatalogName", "getDatabase")(
        "A qualified table name",
        advice = "the catalog is flat; name the table without a schema or database",
        example = "SELECT a FROM other.t"
      )(t => t.getNameParts != null && t.getNameParts.size > 1),
      // getDBLinkName returns the table's own name when there is no link, so
      // presence is the '@' in the written name.
      rejectsWhen[Table]("getDBLinkName")("A database link (t@link)",
        example = "SELECT a FROM t@remote"
      )(t => t.getNameParts != null && t.getNameParts.asScala.exists(_.contains("@"))),
      rejects("getPivot")("PIVOT", example = "SELECT * FROM t PIVOT (SUM(a) FOR b IN (1, 2))"),
      rejects("getUnPivot")("UNPIVOT", example = "SELECT * FROM t UNPIVOT (v FOR k IN (a, b))"),
      rejects("getSampleClause")("TABLESAMPLE", example = "SELECT a FROM t TABLESAMPLE SYSTEM (10)"),
      rejects("getIndexHint")("An index hint (USE / FORCE / IGNORE INDEX)",
        example = "SELECT a FROM t USE INDEX (i)"),
      rejects("getSqlServerHints")("A table hint WITH (...)", example = "SELECT a FROM t WITH (NOLOCK)")
    ),
    Seq(
      "getNameParts" -> "the parts of the written name, which the two checks above read",
      "getNamePartDelimiters" -> "the separators between those parts",
      "getFullyQualifiedName" -> "those parts joined"))

  private val JoinAudit = audit(
    Seq(
      rejects("getUsingColumns")("JOIN ... USING (...)",
        advice = "write the join condition with ON", example = "SELECT * FROM t JOIN u USING (a)"),
      rejects("isNatural")("NATURAL JOIN",
        advice = "write the join condition with ON", example = "SELECT * FROM t NATURAL JOIN u"),
      rejects("isSemi")("SEMI JOIN", example = "SELECT t.a FROM t LEFT SEMI JOIN u ON t.a = u.a"),
      rejects("isApply")("CROSS APPLY / OUTER APPLY", example = "SELECT t.a FROM t CROSS APPLY u"),
      // LEFT / RIGHT / FULL OUTER JOIN reach the binder through isLeft / isRight /
      // isFull; a bare OUTER JOIN sets none of them and would run as INNER.
      rejectsWhen[Join]("isOuter")("OUTER JOIN without LEFT, RIGHT or FULL",
        advice = "say which side the join preserves",
        example = "SELECT t.a FROM t OUTER JOIN u ON t.a = u.a"
      )(j => j.isOuter && !j.isLeft && !j.isRight && !j.isFull),
      rejects("isStraight")("STRAIGHT_JOIN", example = "SELECT t.a FROM t STRAIGHT_JOIN u ON t.a = u.a"),
      rejects("isGlobal")("GLOBAL JOIN", example = "SELECT t.a FROM t GLOBAL JOIN u ON t.a = u.a"),
      rejects("getJoinHint")("A join hint such as INNER HASH JOIN",
        example = "SELECT t.a FROM t INNER HASH JOIN u ON t.a = u.a"),
      rejects("isWindowJoin", "getJoinWindow")("A windowed join (WITHIN ...)")
    ),
    Seq(
      "getOnExpression" -> "the first of getOnExpressions, which the binder reads",
      "getFromItem" -> "the same node as getRightItem",
      "isInnerJoin" -> "derived from the join-kind flags the binder reads"))

  // ---------------------------------------------------------------------------
  // Expressions
  // ---------------------------------------------------------------------------

  private val FunctionAudit = audit(
    Seq(
      rejectsWhen[Function]("isDistinct")("DISTINCT inside an aggregate other than COUNT, SUM, AVG, MIN or MAX",
        example = "SELECT STDDEV(DISTINCT a) FROM t",
        partial = true,
        describe = f => s"${upper(f.getName)}(DISTINCT ...)"
      )(f => f.isDistinct && !DistinctAggregates(upper(f.getName))),
      rejectsWhen[Function]("isUnique")("UNIQUE inside a function call",
        advice = "write DISTINCT",
        example = "SELECT COUNT(UNIQUE a) FROM t",
        describe = f => s"${upper(f.getName)}(UNIQUE ...)"
      )(_.isUnique),
      rejectsWhen[Function]("getNullHandling", "isIgnoreNulls")("IGNORE NULLS",
        example = "SELECT MAX(a IGNORE NULLS) FROM t"
      )(f => f.isIgnoreNulls || f.getNullHandling == Function.NullHandling.IGNORE_NULLS),
      rejects("getOrderByElements")("ORDER BY inside a function call",
        example = "SELECT SUM(a ORDER BY b) FROM t"),
      rejects("getLimit")("LIMIT inside a function call", example = "SELECT SUM(a LIMIT 2) FROM t"),
      rejects("getHavingClause")("HAVING MIN / MAX inside a function call",
        example = "SELECT MAX(a HAVING MAX b) FROM t"),
      rejects("getKeep")("KEEP (DENSE_RANK FIRST / LAST ...)",
        example = "SELECT MAX(a) KEEP (DENSE_RANK FIRST ORDER BY b) FROM t"),
      // getAttributeName is attributeColumn.toString() and throws when there is
      // no attribute; getAttribute covers both of the fields it can live in.
      rejectsWhen[Function]("getAttribute", "getAttributeName", "getAttributeColumn")(
        "A field of a function result, f(...).field", example = "SELECT UPPER(s).x FROM t"
      )(_.getAttribute != null),
      rejectsWhen[Function]("getNamedParameters")(
        "A call with keyword arguments, such as SUBSTRING(x FROM i FOR n)",
        advice = "write the arguments separated by commas",
        example = "SELECT SUBSTRING(s FROM 1 FOR 2) FROM t",
        describe = f => s"${upper(f.getName)} with keyword arguments"
      )(f => isPresent(f.getNamedParameters))
    ),
    Seq(
      "getMultipartName" -> "the parts behind getName, which the binder reads",
      "isAllColumns" -> ("the ALL quantifier of COUNT(ALL x), which is the default; the star of " +
        "COUNT(*) is an AllColumns parameter, not this flag"),
      "isEscaped" -> "the JDBC escape {fn ...} around an ordinary call",
      "isIgnoreNullsOutside" -> "where IGNORE / RESPECT NULLS was written; the keyword itself is checked above"))

  private val AnalyticAudit = audit(
    Seq(
      rejects("getFilterExpression")("FILTER (WHERE ...)",
        example = "SELECT SUM(a) FILTER (WHERE a > 1) FROM t"),
      rejectsWhen[AnalyticExpression]("getType")("WITHIN GROUP (ORDER BY ...)",
        example = "SELECT SUM(a) WITHIN GROUP (ORDER BY a) FROM t"
      )(ae => ae.getType == AnalyticType.WITHIN_GROUP || ae.getType == AnalyticType.WITHIN_GROUP_OVER),
      rejects("getWindowName")("A named window, OVER <name>",
        advice = "write the window specification inline in OVER (...)",
        example = "SELECT ROW_NUMBER() OVER w FROM t"),
      rejectsWhen[AnalyticExpression]("getNullHandling", "isIgnoreNulls")("IGNORE NULLS",
        example = "SELECT LAG(a) IGNORE NULLS OVER (ORDER BY b) FROM t"
      )(ae => ae.isIgnoreNulls || ae.getNullHandling == Function.NullHandling.IGNORE_NULLS),
      rejectsWhen[AnalyticExpression]("isDistinct")("DISTINCT inside a window function other than COUNT",
        example = "SELECT SUM(DISTINCT a) OVER () FROM t",
        partial = true,
        describe = ae => s"${upper(ae.getName)}(DISTINCT ...) OVER (...)"
      )(ae => ae.isDistinct && !"COUNT".equalsIgnoreCase(ae.getName)),
      rejectsWhen[AnalyticExpression]("getExpression")("An argument to ROW_NUMBER / RANK / DENSE_RANK",
        example = "SELECT ROW_NUMBER(a) OVER (ORDER BY a) FROM t",
        partial = true,
        describe = ae => s"${upper(ae.getName)} with an argument"
      )(ae => RankingFunctions(upper(ae.getName)) && ae.getExpression != null),
      rejectsWhen[AnalyticExpression]("getOffset", "getDefaultValue")(
        "A second or third argument to a window function other than LAG / LEAD",
        example = "SELECT SUM(a, 2) OVER () FROM t",
        partial = true,
        describe = ae => s"${upper(ae.getName)} with more than one argument"
      )(ae => !OffsetFunctions(upper(ae.getName)) && (ae.getOffset != null || ae.getDefaultValue != null)),
      rejectsWhen[AnalyticExpression]("isUnique")("UNIQUE inside a window function",
        advice = "write DISTINCT",
        example = "SELECT COUNT(UNIQUE a) OVER () FROM t"
      )(_.isUnique),
      rejects("getFuncOrderBy")("ORDER BY inside a window function's arguments",
        example = "SELECT ARRAY_AGG(a ORDER BY a) OVER () FROM t"),
      rejects("getLimit")("LIMIT inside a window function's arguments",
        example = "SELECT SUM(a LIMIT 2) OVER () FROM t"),
      rejects("getHavingClause")("HAVING MIN / MAX inside a window function",
        example = "SELECT ANY_VALUE(a HAVING MAX b) OVER () FROM t"),
      rejects("getKeep")("KEEP (DENSE_RANK FIRST / LAST ...)",
        example = "SELECT MAX(a) KEEP (DENSE_RANK FIRST ORDER BY b) OVER () FROM t")
    ),
    Seq(
      "getWindowDefinition" -> "holds the PARTITION BY, ORDER BY and frame the binder reads through their own getters",
      "isAllColumns" -> ("the ALL quantifier of COUNT(ALL x) OVER (...), which is the default; the star of " +
        "COUNT(*) OVER is an AllColumns argument, not this flag"),
      "isIgnoreNullsOutside" -> "where IGNORE / RESPECT NULLS was written; the keyword itself is checked above",
      "isPartitionByBrackets" -> "whether the PARTITION BY list was parenthesized"))

  private val LikeAudit = audit(
    Seq(
      rejects("getEscape")("LIKE ... ESCAPE", example = "SELECT a FROM t WHERE s LIKE 'a!_b' ESCAPE '!'"),
      // The binder reads the keyword for LIKE and ILIKE; the other spellings are
      // different operators it does not implement.
      rejectsWhen[LikeExpression]("getLikeKeyWord")("RLIKE / REGEXP",
        advice = "the engine has no regular-expression matching",
        example = "SELECT a FROM t WHERE s RLIKE 'a.b'",
        partial = true,
        describe = lk => String.valueOf(lk.getLikeKeyWord)
      )(lk => lk.getLikeKeyWord == LikeExpression.KeyWord.RLIKE ||
        lk.getLikeKeyWord == LikeExpression.KeyWord.REGEXP),
      rejectsWhen[LikeExpression]("getLikeKeyWord")("SIMILAR TO",
        example = "SELECT a FROM t WHERE s SIMILAR TO 'a%'",
        partial = true
      )(_.getLikeKeyWord == LikeExpression.KeyWord.SIMILAR_TO)
    ),
    Seq(
      "isCaseInsensitive" -> "true exactly when the keyword is ILIKE, which the binder reads through getLikeKeyWord",
      "getStringExpression" -> "the keyword as written",
      "isUseBinary" -> "MySQL's LIKE BINARY asks for a case-sensitive match, which this engine's LIKE always is"))

  private val CastAudit = audit(
    Seq(
      // `keyword` is a public field: CAST, TRY_CAST or SAFE_CAST, and null for
      // `x::T` and typed literals like INT '1'.
      rejectsWhen[CastExpression]("keyword")("TRY_CAST / SAFE_CAST",
        advice = "write CAST, which fails on a value it cannot convert",
        example = "SELECT TRY_CAST(s AS INT) FROM t",
        describe = c => upper(c.keyword)
      )(c => c.keyword != null && !"CAST".equalsIgnoreCase(c.keyword)),
      rejects("getColumnDefinitions")("CAST to a ROW type", example = "SELECT CAST(a AS ROW(x INT)) FROM t"),
      rejects("getFormat")("CAST ... FORMAT", example = "SELECT CAST(s AS DATE FORMAT 'YYYY-MM-DD') FROM t")
    ),
    Seq(
      "isUseCastKeyword" -> "CAST(x AS T) versus x::T",
      "isImplicitCast" -> "a typed literal such as INT '1', which binds as the cast it is",
      "isBLOB" -> "a test on the target type in getColDataType",
      "isDate" -> "a test on the target type in getColDataType",
      "isDecimal" -> "a test on the target type in getColDataType",
      "isFloat" -> "a test on the target type in getColDataType",
      "isInteger" -> "a test on the target type in getColDataType",
      "isText" -> "a test on the target type in getColDataType",
      "isTime" -> "a test on the target type in getColDataType",
      "isTimeStamp" -> "a test on the target type in getColDataType"))

  private val ColDataTypeAudit = audit(Seq(
    rejects("getCharacterSet")("CHARACTER SET in a CAST",
      example = "SELECT CAST(s AS CHAR(10) CHARACTER SET utf8) FROM t"),
    rejects("getArrayData")("An array type in a CAST", example = "SELECT CAST(a AS INT[]) FROM t"),
    // The type names the binder knows keep their arguments inside getDataType
    // ("VARCHAR (1)"), which it rejects by name; a few others fill this list.
    rejects("getArgumentsStringList")("A type argument in a CAST, such as VARCHAR2(5)",
      example = "SELECT CAST(s AS VARCHAR2(5)) FROM t")))

  // ---------------------------------------------------------------------------
  // The audit
  // ---------------------------------------------------------------------------

  /** Every AST class the binder reaches. A class not listed here is one the
    * binder rejects by name, so the walk stops at it. */
  val audits: Map[Class[_], Audit] = Map(
    // Statements
    classOf[PlainSelect] -> PlainSelectAudit,
    classOf[SetOperationList] -> audit(SelectTail, SelectCasts),
    classOf[ParenthesedSelect] -> audit(
      Seq(ParenthesizedTail, ParenthesizedPivot) ++ SelectTail, ParenthesizedInert),
    // A CTE: `WITH c AS (...)`. Its own tail is unreachable from SQL text (a
    // clause after the parentheses belongs to the enclosing query), but it
    // inherits the getters, so they stay rejected.
    classOf[WithItem] -> audit(Seq(ParenthesizedTail, ParenthesizedPivot) ++ SelectTail, SelectCasts),
    classOf[UnionOp] -> audit(Nil, Seq("isDistinct" -> "UNION DISTINCT is UNION; the binder dedups every union that is not ALL")),
    classOf[SelectItem[_]] -> audit(Nil),
    classOf[Alias] -> audit(
      Seq(rejects("getAliasColumns")("A column list in an alias, AS x(c, ...)",
        advice = "alias each column in the SELECT list", example = "SELECT * FROM t AS x(c)")),
      Seq("isUseAs" -> "whether AS was written")),
    classOf[Table] -> TableAudit,
    classOf[Join] -> JoinAudit,
    classOf[OrderByElement] -> audit(
      Seq(
        rejects("isMysqlWithRollup")("ORDER BY ... WITH ROLLUP",
          example = "SELECT a FROM t ORDER BY a WITH ROLLUP")),
      Seq("isAscDescPresent" -> "whether ASC / DESC was written; isAsc carries the direction")),
    classOf[Limit] -> audit(Seq(
      rejects("isLimitAll")("LIMIT ALL", advice = "leave the LIMIT out", example = "SELECT a FROM t LIMIT ALL"),
      rejects("isLimitNull")("LIMIT NULL", advice = "leave the LIMIT out", example = "SELECT a FROM t LIMIT NULL"),
      // Reached only through Select.getLimitBy, which is rejected above it.
      rejects("getByExpressions")("LIMIT n BY"))),
    classOf[Offset] -> audit(Nil, Seq("getOffsetParam" -> "the noise word ROW / ROWS after the count")),
    classOf[Distinct] -> audit(
      Seq(rejects("getOnSelectItems")("DISTINCT ON (...)", example = "SELECT DISTINCT ON (a) a, b FROM t")),
      Seq("isUseUnique" -> "SELECT UNIQUE is a spelling of SELECT DISTINCT")),
    classOf[GroupByElement] -> audit(
      Seq(
        rejects("getGroupingSets")("GROUPING SETS",
          advice = "GROUP BY each set separately and combine the results with UNION ALL",
          example = "SELECT a, COUNT(*) FROM t GROUP BY GROUPING SETS ((a), ())"),
        rejects("isMysqlWithRollup")("GROUP BY ... WITH ROLLUP",
          advice = "GROUP BY each level separately and combine the results with UNION ALL",
          example = "SELECT a, COUNT(*) FROM t GROUP BY a WITH ROLLUP")),
      Seq(
        "getGroupByExpressions" -> "the same list as getGroupByExpressionList",
        "isUsingBrackets" -> "whether the GROUP BY list was parenthesized")),

    // Function calls and window functions
    classOf[Function] -> FunctionAudit,
    classOf[AnalyticExpression] -> AnalyticAudit,
    classOf[WindowElement] -> audit(Nil),
    classOf[WindowRange] -> audit(Nil),
    classOf[WindowOffset] -> audit(Nil),
    classOf[TrimFunction] -> audit(Nil),
    classOf[CastExpression] -> CastAudit,
    classOf[ColDataType] -> ColDataTypeAudit,
    classOf[CaseExpression] -> audit(Nil, Seq("isUsingBrackets" -> "whether the CASE was parenthesized")),
    classOf[WhenClause] -> audit(Nil),

    // Operators
    classOf[Addition] -> Arithmetic,
    classOf[Subtraction] -> Arithmetic,
    classOf[Multiplication] -> Arithmetic,
    classOf[Division] -> Arithmetic,
    classOf[Modulo] -> Arithmetic,
    classOf[Concat] -> Arithmetic,
    classOf[AndExpression] -> audit(Nil, Seq(
      "getStringExpression" -> "the operator as written",
      "isUseOperator" -> "&& is a spelling of AND")),
    classOf[OrExpression] -> Arithmetic,
    classOf[NotExpression] -> audit(Nil, Seq("isExclamationMark" -> "! is a spelling of NOT")),
    classOf[SignedExpression] -> audit(Nil),
    classOf[EqualsTo] -> Comparison,
    classOf[NotEqualsTo] -> Comparison,
    classOf[GreaterThan] -> Comparison,
    classOf[GreaterThanEquals] -> Comparison,
    classOf[MinorThan] -> Comparison,
    classOf[MinorThanEquals] -> Comparison,
    classOf[InExpression] -> audit(Seq(
      OracleOuterJoin, OraclePrior,
      rejects("isGlobal")("GLOBAL IN", example = "SELECT a FROM t WHERE a GLOBAL IN (1, 2)"))),
    classOf[Between] -> audit(Nil),
    classOf[LikeExpression] -> LikeAudit,
    classOf[IsNullExpression] -> audit(
      Seq(rejects("isUseNotNull")("x NOTNULL", advice = "write x IS NOT NULL",
        example = "SELECT a FROM t WHERE s NOTNULL")),
      Seq("isUseIsNull" -> "x ISNULL is a spelling of x IS NULL; isNot carries the negation")),

    // Leaves
    classOf[Column] -> audit(
      Seq(rejects("getArrayConstructor")("An array subscript a[...]", example = "SELECT a[1] FROM t")),
      Seq(
        "getFullyQualifiedName" -> "the table and column names joined",
        "getTableDelimiter" -> "the separator written between table and column",
        "getCommentText" -> "a comment attached to the column")),
    classOf[AllColumns] -> audit(StarModifiers),
    classOf[AllTableColumns] -> audit(StarModifiers),
    classOf[LongValue] -> audit(Nil, Seq(
      "getStringValue" -> "the literal as written",
      "getBigIntegerValue" -> "the same value as getValue")),
    classOf[DoubleValue] -> audit(Nil),
    classOf[StringValue] -> audit(
      Seq(rejectsWhen[StringValue]("getPrefix")("A prefixed string literal such as E'...' or B'...'",
        example = "SELECT E'a' FROM t",
        describe = sv => s"${sv.getPrefix}'...'"
      )(sv => sv.getPrefix != null && !sv.getPrefix.equalsIgnoreCase("N"))),
      Seq("getNotExcapedValue" -> "the literal before quote unescaping")),
    classOf[NullValue] -> audit(Nil),
    classOf[DateValue] -> audit(Nil),
    classOf[TimestampValue] -> audit(Nil, Seq("getRawValue" -> "the literal as written")),
    classOf[TimeValue] -> audit(Nil, Seq("getValue" -> "the binder rejects TIME literals outright")),
    classOf[AllValue] -> audit(Nil),
    classOf[ExpressionList[_]] -> audit(Nil, ListShape),
    classOf[ParenthesedExpressionList[_]] -> audit(Nil, ListShape)
  )

  /** Subclasses of audited classes that never reach the binder through their
    * parent's case, and why. `UnsupportedClausesTest` requires every subclass
    * in the JSqlParser jar to be audited itself or listed here. */
  val unreachable: Map[String, String] = Map(
    "net.sf.jsqlparser.statement.select.LateralSubSelect" ->
      "LATERAL (...) parses only as a FROM item, and the binder accepts only tables there",
    "net.sf.jsqlparser.statement.select.TableFunction" ->
      "a function call in FROM; the binder accepts only tables there",
    "net.sf.jsqlparser.expression.Parenthesis" ->
      "deprecated; nothing in JSqlParser 5.0 constructs it",
    "net.sf.jsqlparser.expression.RowConstructor" ->
      "ROW(...) is only ever an element of a list, where the binder rejects it by name",
    "net.sf.jsqlparser.expression.operators.relational.NamedExpressionList" ->
      "only Function.getNamedParameters holds one, and that getter is rejected")

  // ---------------------------------------------------------------------------
  // Reflection
  // ---------------------------------------------------------------------------

  /** The getters of `cls` the audit covers: public, non-static and no-arg,
    * named `get*` / `is*`, declared by a JSqlParser class. `getASTNode` and
    * `getParent` are parser plumbing on every node and carry no SQL. */
  def getters(cls: Class[_]): Seq[Method] =
    cls.getMethods.toSeq.filter { m =>
      m.getParameterCount == 0 && !Modifier.isStatic(m.getModifiers) &&
        isJSqlParserClass(m.getDeclaringClass) &&
        m.getName.matches("(get|is)[A-Z].*") &&
        m.getName != "getASTNode" && m.getName != "getParent"
    }.sortBy(_.getName)

  /** The public instance fields of `cls` declared by JSqlParser classes. */
  def fields(cls: Class[_]): Seq[JField] =
    cls.getFields.toSeq.filter(f => !Modifier.isStatic(f.getModifiers) && isJSqlParserClass(f.getDeclaringClass))

  def isJSqlParserClass(cls: Class[_]): Boolean = cls.getName.startsWith("net.sf.jsqlparser.")

  /** The audited class `cls` is checked as: itself, or its nearest audited
    * superclass. */
  def auditedClass(cls: Class[_]): Option[Class[_]] = {
    var c: Class[_] = cls
    while (c != null && !audits.contains(c)) c = c.getSuperclass
    Option(c)
  }

  private val readers = new ConcurrentHashMap[(Class[_], String), AnyRef => Any]()

  /** The value of the getter or public field `member` on `node`. */
  private def read(node: AnyRef, member: String): Any = {
    val cls = node.getClass
    readers.computeIfAbsent((cls, member), _ => {
      val method = cls.getMethods.find(m => m.getName == member && m.getParameterCount == 0)
      method.map(m => (n: AnyRef) => invoke(m, n))
        .getOrElse { val f = cls.getField(member); (n: AnyRef) => f.get(n): Any }
    })(node)
  }

  /** Call a getter, surfacing whatever it throws as itself rather than wrapped
    * in reflection's InvocationTargetException — the same exception binding
    * would have met calling it directly. */
  private def invoke(m: Method, node: AnyRef): AnyRef =
    try m.invoke(node)
    catch { case e: InvocationTargetException if e.getCause != null => throw e.getCause }

  /** For each class the walk meets: its audit, and the getters to descend
    * through — every covered getter that is neither inert nor carries a
    * clause the binder never honours, and that can return an AST node. */
  private final case class Plan(audit: Audit, descend: Seq[Method])

  private val plans = new ConcurrentHashMap[Class[_], Option[Plan]]()

  private def planFor(cls: Class[_]): Option[Plan] =
    plans.computeIfAbsent(cls, _ => auditedClass(cls).map { audited =>
      val a = audits(audited)
      val skip = a.inert.keySet ++ a.rejections.filterNot(_.partial).flatMap(_.getters)
      val descend = getters(audited).filter { m =>
        !skip(m.getName) && (
          classOf[java.util.Collection[_]].isAssignableFrom(m.getReturnType) ||
            (isJSqlParserClass(m.getReturnType) && !m.getReturnType.isEnum))
      }
      Plan(a, descend)
    })

  private final class Walk {
    private val seen = java.util.Collections.newSetFromMap(
      new java.util.IdentityHashMap[AnyRef, java.lang.Boolean])

    def visit(value: AnyRef): Unit = {
      if (value == null || !seen.add(value)) return
      planFor(value.getClass).foreach { p =>
        p.audit.rejections.foreach { r =>
          if (r.appliesTo(value)) throw new UnsupportedClauseException(r, r.message(value))
        }
        p.descend.foreach(m => visit(invoke(m, value)))
      }
      value match {
        case c: java.util.Collection[_] => c.asScala.foreach(e => visit(e.asInstanceOf[AnyRef]))
        case _                          => ()
      }
    }
  }
}
