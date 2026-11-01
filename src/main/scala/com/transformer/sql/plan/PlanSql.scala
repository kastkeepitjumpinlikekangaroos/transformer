package com.transformer.sql.plan

import com.transformer.core.{Catalog, DataType}
import com.transformer.sql.parse.SqlParser
import net.sf.jsqlparser.parser.{CCJSqlParserUtil, ParserKeywordsUtils}
import net.sf.jsqlparser.schema.{Column, Table}
import net.sf.jsqlparser.statement.select.PlainSelect

import java.util.Locale
import scala.collection.mutable
import scala.util.control.NonFatal

/** A plan printed as SQL that [[LogicalBuilder.build]] reads back to the same
  * plan: binding's inverse, as DuckDB prints a statement and parses it again
  * to verify it.
  *
  * The binder builds each SELECT as a fixed stack of nodes, top down: LIMIT,
  * OFFSET, the projection that drops the columns ORDER BY computed, the sort,
  * DISTINCT, the SELECT list, the window functions, HAVING, the aggregate,
  * WHERE, and the FROM clause's left-deep tree of joins over tables. The
  * printer reads the same stack off a plan and prints one SELECT for it. A
  * node that fits no slot of the stack, which the optimizer makes (a filter
  * under a join, a pruned scan) and a nested WITH leaves (a query where a
  * table would be), is printed as a CTE of its own that the SELECT reads; a
  * CTE read once is inlined again when the text is bound, so the plan is the
  * one printed.
  *
  * Read back, the plan is the same as the one printed but for names, which
  * the printer chooses: every column is qualified by its FROM item's alias,
  * so ORDER BY's rules for output names never apply, and a CTE's columns are
  * renamed where two share a name. Each cast the binder inserted is printed,
  * but a cast to DECIMAL or BINARY, which SQL cannot write here and the binder
  * inserts again wherever the operands' types call for it; each literal is printed so
  * that it binds to its own type wherever it stands (`CAST('5' AS BIGINT)`,
  * since a bare `5` is an INT), a negative number as the negation of its
  * magnitude.
  */
object PlanSql {

  /** A plan with no SQL this engine reads back: a scan of a view no catalog
    * holds (a materialized CTE), a value no literal writes, a column whose
    * name its source repeats. */
  final class UnprintablePlanException(message: String) extends RuntimeException(message)

  /** `built`, its CTEs and its main query, as SQL. `catalog` holds the tables
    * its scans read. */
  def print(built: BuiltQuery, catalog: Catalog): String = print(built.main, catalog, built.ctes)

  /** `plan` as SQL, its scans reading `catalog`'s tables or the CTEs `ctes`
    * defines; a scan of a table's pruned view reads the table's columns it
    * keeps. */
  def print(plan: LogicalPlan, catalog: Catalog, ctes: Seq[CteDef] = Nil): String =
    new Printer(catalog, ctes, plan).statement().mkString("\n")

  /** `e` as SQL, each column it reads written as `column` names it. */
  def expression(e: Expr, column: Int => String): String = expr(e, i => Fragment(column(i), Atomic)).text

  /** The aggregate `a` as SQL, each column it reads written as `column` names it. */
  def aggregate(a: AggExpr, column: Int => String): String = aggregateCall(a, i => Fragment(column(i), Atomic))

  /** The window function of `w`, with its OVER clause, as SQL. */
  def window(w: WindowDef, column: Int => String): String = windowCall(w, i => Fragment(column(i), Atomic))

  /** `name` as an identifier the parser reads back as `name`: bare when it is
    * a plain word that reads back as itself wherever the printer writes a
    * name, in double quotes otherwise. */
  def identifier(name: String): String =
    if (PlainWord.pattern.matcher(name).matches() && !Reserved(name.toUpperCase(Locale.ROOT)) && readsBack(name)) name
    else SqlParser.quote(name)

  private val PlainWord = "[A-Za-z_][A-Za-z0-9_]*".r

  /** The words JSqlParser reserves somewhere, and the three it reads as
    * literals. A name among them is quoted wherever it stands. */
  private lazy val Reserved: Set[String] =
    ParserKeywordsUtils.ALL_RESERVED_KEYWORDS.iterator.map(_(0).toString.toUpperCase(Locale.ROOT)).toSet ++
      Set("TRUE", "FALSE", "NULL")

  /** Whether the plain word `name` reads back as a name in every place the
    * printer writes one: a CTE's, a table's, an alias, a qualifier and a
    * column. A word no keyword list holds may still be read as something
    * else in one of them: `FROM t CURRENT_TIMESTAMP` is a table aliased so,
    * and `CURRENT_TIMESTAMP.c1` the clock. Each word is parsed once. */
  private def readsBack(name: String): Boolean = {
    val key = name.toLowerCase(Locale.ROOT)
    val known = ReadsBack.get(key)
    if (known != null) known.booleanValue
    else {
      val reads =
        try CCJSqlParserUtil.parse(s"WITH $name AS (SELECT 1 AS $name) SELECT $name.$name AS $name FROM $name AS $name") match {
          case ps: PlainSelect =>
            def same(written: String) = written != null && written.equalsIgnoreCase(name)
            val item = ps.getSelectItems.get(0)
            val (column, table) = item.getExpression match {
              case c: Column => (c.getColumnName, Option(c.getTable).map(_.getName).orNull)
              case _ => (null, null)
            }
            val from = ps.getFromItem match {
              case t: Table => same(t.getName) && t.getAlias != null && same(t.getAlias.getName)
              case _ => false
            }
            val cte = Option(ps.getWithItemsList).exists(l => l.size == 1 && same(l.get(0).getAlias.getName))
            same(column) && same(table) && item.getAlias != null && same(item.getAlias.getName) && from && cte
          case _ => false
        } catch { case NonFatal(_) => false }
      if (ReadsBack.size >= ReadsBackLimit) ReadsBack.clear()
      ReadsBack.put(key, reads)
      reads
    }
  }

  private val ReadsBack = new java.util.concurrent.ConcurrentHashMap[String, java.lang.Boolean]()
  private val ReadsBackLimit = 4096

  // ---------------------------------------------------------------------------
  // Expressions
  // ---------------------------------------------------------------------------

  /** Printed SQL and how tightly it binds, so an operator puts parentheses
    * around an operand only where the parser would otherwise read another
    * tree. Grouping parentheses bind to nothing of their own, so any that are
    * not needed read back as the same tree. */
  private final case class Fragment(text: String, prec: Int)

  private val Atomic = 100 // a literal, a column, a call, a CAST, a CASE
  private val Unary = 90 // a sign
  private val Mul = 80
  private val Add = 70
  private val Concat = 60
  private val Compare = 50 // also IS NULL, IN, LIKE, BETWEEN
  private val Not = 40
  private val And = 30
  private val Or = 20

  private type Scope = Int => Fragment

  private def top(e: Expr, scope: Scope): String = expr(e, scope).text

  private def parens(f: Fragment): String = s"(${f.text})"

  /** An operand of a comparison, IS NULL, IN, LIKE or BETWEEN: bare when it
    * is arithmetic or tighter. */
  private def operand(f: Fragment): String = if (f.prec >= Add) f.text else parens(f)

  private def expr(e: Expr, scope: Scope): Fragment = e match {
    case ColRefExpr(i, _, _) => scope(i)
    case LitExpr(v, t) => literal(v, t)
    // SQL here writes no cast to DECIMAL or BINARY; the binder casts to one
    // wherever the operands' types call for it, which they do again.
    case CastExpr(c, _: DataType.DecimalType | DataType.BinaryType) => expr(c, scope)
    case CastExpr(c, t) => Fragment(s"CAST(${top(c, scope)} AS ${typeName(t)})", Atomic)
    case UnaryOpExpr("-", c, _) =>
      val inner = expr(c, scope)
      Fragment("-" + (if (inner.prec == Atomic) inner.text else parens(inner)), Unary)
    // NOT negates what follows it whole, but the binder refuses one the
    // parser hangs on an operator's left side, so its operand is in
    // parentheses unless it is atomic.
    case UnaryOpExpr("NOT", c, _) =>
      val inner = expr(c, scope)
      Fragment("NOT " + (if (inner.prec == Atomic) inner.text else parens(inner)), Not)
    case UnaryOpExpr(_, c, _) => expr(c, scope)
    case b @ BinOpExpr("AND" | "OR", _, _, _) => chain(b, scope)
    case BinOpExpr("||", l, r, _) =>
      val (lf, rf) = (expr(l, scope), expr(r, scope))
      val left = if (lf.prec == Concat || lf.prec >= Unary) lf.text else parens(lf)
      val right = if (rf.prec >= Unary) rf.text else parens(rf)
      Fragment(s"$left || $right", Concat)
    case BinOpExpr(op @ ("*" | "/" | "%"), l, r, _) => arithmetic(op, Mul, l, r, scope)
    case BinOpExpr(op @ ("+" | "-"), l, r, _) => arithmetic(op, Add, l, r, scope)
    case BinOpExpr(op, l, r, _) => Fragment(s"${operand(expr(l, scope))} $op ${operand(expr(r, scope))}", Compare)
    case FuncExpr(name, args, _) => Fragment(s"$name(${args.map(top(_, scope)).mkString(", ")})", Atomic)
    case CaseExpr(branches, elseExpr, _) =>
      val whens = branches.map { case (w, t) => s"WHEN ${top(w, scope)} THEN ${top(t, scope)}" }
      Fragment(s"CASE ${whens.mkString(" ")}${elseExpr.fold("")(x => s" ELSE ${top(x, scope)}")} END", Atomic)
    // Only an operand that reads RAND() binds as one, and it binds so again.
    case SwitchCaseExpr(subject, branches, elseExpr, _) =>
      val whens = branches.map(b => s"WHEN ${top(b.when, scope)} THEN ${top(b.value, scope)}")
      Fragment(s"CASE ${top(subject, scope)} ${whens.mkString(" ")}${elseExpr.fold("")(x => s" ELSE ${top(x, scope)}")} END",
        Atomic)
    case BetweenExpr(c, low, high) =>
      Fragment(s"${operand(expr(c, scope))} BETWEEN ${operand(expr(low, scope))} AND ${operand(expr(high, scope))}",
        Compare)
    case IsNullExpr(c, negated) => Fragment(s"${operand(expr(c, scope))} IS ${if (negated) "NOT " else ""}NULL", Compare)
    case InListExpr(c, items, negated) =>
      Fragment(s"${operand(expr(c, scope))} ${if (negated) "NOT " else ""}IN " +
        items.map(i => operand(expr(i, scope))).mkString("(", ", ", ")"), Compare)
    case LikeExpr(c, pattern, negated, syntax) =>
      val keyword = if (syntax.caseInsensitive) "ILIKE" else "LIKE"
      val escape = syntax.escape match {
        case LikeSyntax.Default.escape => ""
        case None => " ESCAPE ''"
        case Some(ch) => s" ESCAPE ${stringLiteral(ch.toString)}"
      }
      Fragment(s"${operand(expr(c, scope))} ${if (negated) "NOT " else ""}$keyword ${operand(expr(pattern, scope))}$escape",
        Compare)
  }

  /** `l op r` for an arithmetic `op` binding at `prec`: the parser reads a
    * run of one level left to right, so a right operand of the same level is
    * in parentheses, and a sign after an operator always is. */
  private def arithmetic(op: String, prec: Int, l: Expr, r: Expr, scope: Scope): Fragment = {
    val (lf, rf) = (expr(l, scope), expr(r, scope))
    val left = if (lf.prec >= prec) lf.text else parens(lf)
    val right = if (rf.prec > prec && rf.prec != Unary) rf.text else parens(rf)
    Fragment(s"$left $op $right", prec)
  }

  /** An AND or OR. The parser regroups a chain of one of them written without
    * parentheses as a balanced tree, so a tree of that shape prints as the
    * chain, and any other prints its two sides with parentheses around a side
    * of the same operator, which the regrouping does not reach into. An AND
    * among the operands of an OR is in parentheses, for the reader. */
  private def chain(b: BinOpExpr, scope: Scope): Fragment = {
    val op = b.op
    val prec = if (op == "AND") And else Or
    def side(e: Expr): String = {
      val f = expr(e, scope)
      if (f.prec > prec && !(op == "OR" && f.prec == And)) f.text else parens(f)
    }
    if (regrouped(b, op)) Fragment(operands(b, op).map(side).mkString(s" $op "), prec)
    else {
      def half(e: Expr): String = e match {
        case inner @ BinOpExpr(`op`, _, _, _) => parens(chain(inner, scope))
        case other => side(other)
      }
      Fragment(s"${half(b.left)} $op ${half(b.right)}", prec)
    }
  }

  private def operands(e: Expr, op: String): Seq[Expr] = e match {
    case BinOpExpr(`op`, l, r, _) => operands(l, op) ++ operands(r, op)
    case other => Seq(other)
  }

  /** Whether the `op` tree at `e` is the one the parser regroups its operands
    * into: each node's left side holds half its operands, rounded down. */
  private def regrouped(e: Expr, op: String): Boolean = {
    def count(x: Expr): Int = x match {
      case BinOpExpr(`op`, l, r, _) => count(l) + count(r)
      case _ => 1
    }
    def check(x: Expr, n: Int): Boolean = x match {
      case BinOpExpr(`op`, l, r, _) =>
        val left = count(l)
        left == n / 2 && check(l, left) && check(r, n - left)
      case _ => n == 1
    }
    check(e, count(e))
  }

  /** A value as a literal that binds to its own type wherever it stands. */
  private def literal(v: Any, t: DataType): Fragment = {
    def atomic(s: String) = Fragment(s, Atomic)
    def negation(magnitude: String) = Fragment("-" + magnitude, Unary)
    if (v == null) atomic("NULL")
    else t match {
      case DataType.BooleanType => atomic(if (v.asInstanceOf[Boolean]) "TRUE" else "FALSE")
      case DataType.StringType => atomic(stringLiteral(v.toString))
      case DataType.IntType =>
        val i = v.asInstanceOf[Number].intValue
        if (i >= 0) atomic(i.toString)
        else if (i == Int.MinValue) atomic(s"CAST('$i' AS INT)")
        else negation((-i).toString)
      // A number an INT holds is written as an INT, so a BIGINT one is cast.
      case DataType.LongType =>
        val l = v.asInstanceOf[Number].longValue
        if (l > Int.MaxValue) atomic(l.toString)
        else if (l == Long.MinValue) Fragment(l.toString, Unary) // the binder reads this one as a BIGINT whole
        else if (l <= Int.MinValue.toLong) negation((-l).toString)
        else atomic(s"CAST('$l' AS BIGINT)")
      case DataType.FloatType => atomic(s"CAST('${v.asInstanceOf[Number].floatValue}' AS FLOAT)")
      case DataType.DoubleType =>
        val d = v.asInstanceOf[Number].doubleValue
        if (d.isNaN || d.isInfinite) atomic(s"CAST('$d' AS DOUBLE)")
        else if (d < 0 || (d == 0.0 && 1.0 / d < 0)) negation(java.lang.Double.toString(-d))
        else atomic(java.lang.Double.toString(d))
      // A DECIMAL literal stands beside a DECIMAL, where a number is the
      // decimal of its digits, or past a BIGINT, where an integer is one.
      case _: DataType.DecimalType =>
        val d = v.asInstanceOf[java.math.BigDecimal]
        val digits = (if (d.scale < 0) d.setScale(0) else d).abs.toPlainString
        if (d.signum < 0) negation(digits) else atomic(digits)
      case DataType.DateType => atomic(s"DATE '$v'")
      case DataType.TimestampType => atomic(s"TIMESTAMP '$v'")
      case other => throw new UnprintablePlanException(s"No literal writes a ${other.name.toUpperCase(Locale.ROOT)}")
    }
  }

  /** `s` in quotes, each quote doubled. The parser reads a backslash before a
    * quote as neither, so no literal holds that pair. */
  private def stringLiteral(s: String): String = {
    if (s.contains("\\'"))
      throw new UnprintablePlanException(s"No literal writes the text ${s.take(40)}, which holds a backslash and a quote")
    "'" + s.replace("'", "''") + "'"
  }

  private def typeName(t: DataType): String = t match {
    case DataType.IntType => "INT"
    case DataType.LongType => "BIGINT"
    case DataType.FloatType => "FLOAT"
    case DataType.DoubleType => "DOUBLE"
    case DataType.StringType => "VARCHAR"
    case DataType.BooleanType => "BOOLEAN"
    case DataType.DateType => "DATE"
    case DataType.TimestampType => "TIMESTAMP"
    case other => throw new UnprintablePlanException(s"No CAST writes a ${other.name.toUpperCase(Locale.ROOT)}")
  }

  private def aggregateCall(a: AggExpr, scope: Scope): String = a match {
    case AggExprCountStar() => "COUNT(*)"
    case AggExprCount(c, distinct) => s"COUNT(${if (distinct) "DISTINCT " else ""}${top(c, scope)})"
    case AggExprDistinct(inner) => s"${inner.name}(DISTINCT ${inner.args.map(top(_, scope)).mkString(", ")})"
    case other => s"${other.name}(${other.args.map(top(_, scope)).mkString(", ")})"
  }

  private def windowCall(w: WindowDef, scope: Scope): String = {
    def valueAt(value: Expr, offset: Int, default: Option[Expr]): String = default match {
      case Some(d) => s"${top(value, scope)}, $offset, ${top(d, scope)}"
      case None if offset == 1 => top(value, scope)
      case None => s"${top(value, scope)}, $offset"
    }
    val fn = w.fn match {
      case WindowFnRowNumber() => "ROW_NUMBER()"
      case WindowFnRank() => "RANK()"
      case WindowFnDenseRank() => "DENSE_RANK()"
      case WindowFnLag(value, offset, default) => s"LAG(${valueAt(value, offset, default)})"
      case WindowFnLead(value, offset, default) => s"LEAD(${valueAt(value, offset, default)})"
      case WindowFnAgg(a) => aggregateCall(a, scope)
    }
    val spec = w.spec
    val partition =
      if (spec.partitionKeys.isEmpty) None else Some("PARTITION BY " + spec.partitionKeys.map(top(_, scope)).mkString(", "))
    val order = if (spec.orderKeys.isEmpty) None else Some("ORDER BY " + spec.orderKeys.map(sortKey(_, scope)).mkString(", "))
    val frame =
      if (spec.frame == WindowFrame.defaultFor(spec.orderKeys.nonEmpty)) None
      else Some(s"${if (spec.frame.frameType == FrameType.Rows) "ROWS" else "RANGE"} BETWEEN " +
        s"${frameBound(spec.frame.start)} AND ${frameBound(spec.frame.end)}")
    s"$fn OVER (${(partition ++ order ++ frame).mkString(" ")})"
  }

  private def frameBound(b: FrameBound): String = {
    def offset(v: java.math.BigDecimal) = if (v.scale < 0) v.toString else v.toPlainString
    b match {
      case FrameBound.UnboundedPreceding => "UNBOUNDED PRECEDING"
      case FrameBound.UnboundedFollowing => "UNBOUNDED FOLLOWING"
      case FrameBound.CurrentRow => "CURRENT ROW"
      case FrameBound.Preceding(n) => s"$n PRECEDING"
      case FrameBound.Following(n) => s"$n FOLLOWING"
      case FrameBound.ValuePreceding(v) => s"${offset(v)} PRECEDING"
      case FrameBound.ValueFollowing(v) => s"${offset(v)} FOLLOWING"
    }
  }

  /** A sort key with its direction, and its NULL placement where it is not
    * the default one (first under ASC, last under DESC). */
  private def sortKey(k: SortKey, scope: Scope): String = sortKeyText(top(k.expr, scope), k)

  private def sortKeyText(key: String, k: SortKey): String =
    key + (if (k.asc) "" else " DESC") +
      (if (k.nullsFirst == k.asc) "" else if (k.nullsFirst) " NULLS FIRST" else " NULLS LAST")

  // ---------------------------------------------------------------------------
  // Plans
  // ---------------------------------------------------------------------------

  /** A table, a CTE or a query a SELECT reads in its FROM clause: the text
    * that names it there, the alias its columns are qualified by, and their
    * names. */
  private final case class FromItem(sql: String, alias: String, columns: IndexedSeq[String])

  /** One SELECT, read off the top of a plan: its list (`select`, of which
    * the first `outputs` are its columns and the rest ORDER BY keys it
    * computes), DISTINCT, the sort over the list, and the plan below the
    * list. */
  private final case class Select(
      select: Seq[(Expr, String)],
      outputs: Int,
      distinct: Boolean,
      sort: Seq[SortKey],
      below: LogicalPlan)

  private final class Printer(catalog: Catalog, cteDefs: Seq[CteDef], root: LogicalPlan) {
    /** Each CTE of the WITH clause, as lines, in the order they must be defined. */
    private val defined = mutable.ArrayBuffer.empty[Vector[String]]
    /** The CTE names a printed query reads, by the view its scans hold, with
      * the column names it was printed with. */
    private val cteColumns = new java.util.IdentityHashMap[PendingCteView, (String, IndexedSeq[String])]()
    /** Names a CTE the printer adds may not take: every table, CTE and alias
      * the plan names, in lower case. */
    private val taken = mutable.HashSet.empty[String]
    private var nextName = 1

    private def reserveNames(p: LogicalPlan): Unit = {
      p match {
        case LogicalScan(name, _, alias) => taken += name.toLowerCase(Locale.ROOT); alias.foreach(taken += _.toLowerCase(Locale.ROOT))
        case _ => ()
      }
      p.children.foreach(reserveNames)
    }
    (root +: cteDefs.map(_.body)).foreach(reserveNames)
    cteDefs.foreach(c => taken += c.name.toLowerCase(Locale.ROOT))

    private def freshName(): String = {
      var name = s"q$nextName"
      while (taken(name)) { nextName += 1; name = s"q$nextName" }
      nextName += 1
      taken += name
      name
    }

    def statement(): Vector[String] = {
      cteDefs.foreach { cte =>
        val names = unique(cte.body.outputSchema.fieldNames)
        define(identifier(cte.name), query(cte.body, names))
        cteColumns.put(cte.pending, (cte.name, names))
      }
      val main = query(root, root.outputSchema.fieldNames)
      if (defined.isEmpty) main
      else {
        // `WITH a AS (` ... `), b AS (` ... `)`, the main query after.
        val ctes = defined.foldLeft(Vector.empty[String]) { (acc, lines) =>
          if (acc.isEmpty) ("WITH " + lines.head) +: lines.tail
          else (acc.init :+ s"${acc.last}, ${lines.head}") ++ lines.tail
        }
        ctes ++ main
      }
    }

    private def define(name: String, body: Vector[String]): Unit =
      defined += ((s"$name AS (" +: body.map("  " + _)) :+ ")")

    /** `plan` as a query, its output columns named `names`. */
    def query(plan: LogicalPlan, names: IndexedSeq[String]): Vector[String] = {
      val (limit, rest0) = plan match {
        case LogicalLimit(c, n) => (Some(n), c)
        case other => (None, other)
      }
      val (offset, rest) = rest0 match {
        case LogicalOffset(c, n) => (Some(n), c)
        case other => (None, other)
      }
      val tail = limit.map(n => s"LIMIT $n").toVector ++ offset.map(n => s"OFFSET $n")
      rest match {
        case LogicalSort(u: LogicalUnion, keys) => union(u, names) ++ orderByOutputs(keys, names) ++ tail
        case u: LogicalUnion => union(u, names) ++ tail
        case other => select(other, names) ++ tail
      }
    }

    /** A union, its arms joined by one operator where its tree is the one the
      * binder builds from such a chain, and in parentheses otherwise. */
    private def union(u: LogicalUnion, names: IndexedSeq[String]): Vector[String] = {
      val operator = if (u.all) "UNION ALL" else "UNION"
      def arms(p: LogicalPlan): Seq[LogicalPlan] = p match {
        case LogicalUnion(l, r, all) if all == u.all => arms(l) ++ arms(r)
        case other => Seq(other)
      }
      def balanced(p: LogicalPlan, n: Int): Boolean = p match {
        case LogicalUnion(l, r, all) if all == u.all =>
          val left = arms(l).size
          left == n / 2 && balanced(l, left) && balanced(r, n - left)
        case _ => n == 1
      }
      val leaves = if (balanced(u, arms(u).size)) arms(u) else Seq(u.left, u.right)
      leaves.zipWithIndex.toVector.flatMap { case (arm, i) =>
        val armNames = if (i == 0) names else arm.outputSchema.fieldNames
        val lines = arm match {
          case LogicalUnion(_, _, _) | LogicalLimit(_, _) | LogicalOffset(_, _) | LogicalSort(_, _) =>
            ("(" +: query(arm, armNames).map("  " + _)) :+ ")"
          case _ => select(arm, armNames)
        }
        if (i == 0) lines else operator +: lines
      }
    }

    /** ORDER BY over a query's output columns, `names`: a key that is one of
      * them by its position, and an expression of them by their names. */
    private def orderByOutputs(keys: Seq[SortKey], names: IndexedSeq[String]): Vector[String] =
      Vector("ORDER BY " + keys.map { k =>
        k.expr match {
          case ColRefExpr(i, _, _) => sortKeyText((i + 1).toString, k)
          case other => sortKey(k.copy(expr = other), outputScope(names))
        }
      }.mkString(", "))

    private def outputScope(names: IndexedSeq[String]): Scope = i => {
      if (names.count(_.equalsIgnoreCase(names(i))) > 1)
        throw new UnprintablePlanException(s"ORDER BY reads ${names(i)}, a name two output columns have")
      Fragment(identifier(names(i)), Atomic)
    }

    /** The SELECT at the top of `plan`, as the binder builds one. */
    private def readSelect(plan: LogicalPlan): Select = {
      def identity(p: LogicalPlan): Seq[(Expr, String)] =
        p.outputSchema.fields.zipWithIndex.map { case (f, i) => (ColRefExpr(i, f.name, f.dataType), f.name) }
      def trims(trim: Seq[(Expr, String)], extended: Seq[(Expr, String)]): Boolean =
        trim.size < extended.size && trim.zipWithIndex.forall {
          case ((ColRefExpr(i, _, _), _), j) => i == j
          case _ => false
        }
      plan match {
        case LogicalProject(LogicalSort(LogicalProject(below, extended), keys), trim) if trims(trim, extended) =>
          Select(extended, trim.size, distinct = false, keys, below)
        case LogicalSort(LogicalDistinct(LogicalProject(below, list)), keys) => Select(list, list.size, true, keys, below)
        case LogicalSort(LogicalProject(below, list), keys) => Select(list, list.size, false, keys, below)
        case LogicalDistinct(LogicalProject(below, list)) => Select(list, list.size, true, Nil, below)
        case LogicalProject(below, list) => Select(list, list.size, false, Nil, below)
        case LogicalSort(LogicalDistinct(below), keys) => Select(identity(below), below.outputSchema.length, true, keys, below)
        case LogicalSort(below, keys) => Select(identity(below), below.outputSchema.length, false, keys, below)
        case LogicalDistinct(below) => Select(identity(below), below.outputSchema.length, true, Nil, below)
        case below => Select(identity(below), below.outputSchema.length, false, Nil, below)
      }
    }

    /** `plan` as one SELECT, with whatever below its stack as CTEs. */
    private def select(plan: LogicalPlan, names: IndexedSeq[String]): Vector[String] = {
      val s = readSelect(plan)
      val (windows, belowWindows) = s.below match {
        case LogicalWindow(c, ws) => (ws, c)
        case other => (Nil, other)
      }
      val (having, aggregate, belowAggregate) = belowWindows match {
        case LogicalFilter(a: LogicalAggregate, p) => (Some(p), Some(a), a.child)
        case a: LogicalAggregate => (None, Some(a), a.child)
        case other => (None, None, other)
      }
      val (where, from) = belowAggregate match {
        case LogicalFilter(c, p) => (Some(p), c)
        case other => (None, other)
      }

      val aliases = mutable.HashSet.empty[String]
      val (fromLines, items) = fromClause(from, aliases)
      val fromScope = columnScope(items)
      val aggScope: Scope = aggregate match {
        case Some(a) => i =>
          if (i < a.groupKeys.size) expr(a.groupKeys(i)._1, fromScope)
          else Fragment(aggregateCall(a.aggregates(i - a.groupKeys.size)._1, fromScope), Atomic)
        case None => fromScope
      }
      val selectScope: Scope =
        if (windows.isEmpty) aggScope
        else {
          val base = belowWindows.outputSchema.length
          i => if (i < base) aggScope(i) else Fragment(windowCall(windows(i - base), aggScope), Atomic)
        }

      val list = s.select.take(s.outputs).zip(names).map { case ((e, _), name) =>
        s"${top(e, selectScope)} AS ${identifier(name)}"
      }
      if (list.isEmpty) throw new UnprintablePlanException("A query returns no columns")
      val head = (if (s.distinct) "SELECT DISTINCT " else "SELECT ") + list.mkString(", ")
      val clauses = Vector.newBuilder[String]
      clauses += head
      clauses ++= fromLines
      where.foreach(p => clauses += s"WHERE ${top(p, fromScope)}")
      aggregate.foreach { a =>
        if (a.groupKeys.nonEmpty) clauses += "GROUP BY " + a.groupKeys.map(k => top(k._1, fromScope)).mkString(", ")
        // With no key and no aggregate the query is one only by its GROUP BY.
        else if (a.aggregates.isEmpty) clauses += "GROUP BY ()"
      }
      having.foreach(p => clauses += s"HAVING ${top(p, aggScope)}")
      if (s.sort.nonEmpty) {
        val keys = s.sort.map { k =>
          k.expr match {
            case ColRefExpr(i, _, _) if i < s.outputs => sortKeyText((i + 1).toString, k)
            case keyExpr if s.distinct => sortKey(k.copy(expr = keyExpr), outputScope(names))
            // A key past the output columns is one ORDER BY computes, and a
            // key over the list reads what the list's items read.
            case keyExpr => sortKey(k.copy(expr = keyExpr), i => expr(s.select(i)._1, selectScope))
          }
        }
        clauses += "ORDER BY " + keys.mkString(", ")
      }
      clauses.result()
    }

    /** The FROM clause of a SELECT reading `p`: a left-deep tree of joins over
      * FROM items, or one item. A SELECT with no FROM reads no table. */
    private def fromClause(p: LogicalPlan, aliases: mutable.HashSet[String]): (Vector[String], Seq[FromItem]) = p match {
      case s: LogicalScan if LogicalBuilder.readsNoTable(s) => (Vector.empty, Nil)
      case LogicalJoin(l, r, cond, kind) =>
        val (lines, leftItems) = fromClause(l, aliases)
        if (leftItems.isEmpty) throw new UnprintablePlanException("A join reads the row of a query with no FROM")
        val right = fromItem(r, aliases)
        val items = leftItems :+ right
        def on = s" ON ${top(cond, columnScope(items))}"
        val join = kind match {
          case JoinKind.Inner if cond == LitExpr(true, DataType.BooleanType) => s"CROSS JOIN ${right.sql}"
          case JoinKind.Inner => s"JOIN ${right.sql}$on"
          case JoinKind.Left => s"LEFT JOIN ${right.sql}$on"
          case JoinKind.Right => s"RIGHT JOIN ${right.sql}$on"
          case JoinKind.Full => s"FULL JOIN ${right.sql}$on"
        }
        (lines :+ join, items)
      case other =>
        val item = fromItem(other, aliases)
        (Vector(s"FROM ${item.sql}"), Seq(item))
    }

    /** A table, a CTE, or a query printed as a CTE, as one FROM item. */
    private def fromItem(p: LogicalPlan, aliases: mutable.HashSet[String]): FromItem = {
      def item(name: String, preferred: String, columns: IndexedSeq[String]): FromItem = {
        var alias = preferred
        var n = 2
        while (aliases(alias.toLowerCase(Locale.ROOT))) { alias = s"${preferred}_$n"; n += 1 }
        aliases += alias.toLowerCase(Locale.ROOT)
        val sql = if (alias.equalsIgnoreCase(name)) identifier(name) else s"${identifier(name)} AS ${identifier(alias)}"
        FromItem(sql, alias, columns)
      }
      p match {
        case LogicalScan(_, pending: PendingCteView, alias) =>
          val (name, columns) = Option(cteColumns.get(pending)).getOrElse(
            throw new UnprintablePlanException(s"The plan reads CTE ${pending.cteName}, which it does not define"))
          item(name, alias.getOrElse(name), columns)
        case s @ LogicalScan(name, view, alias) if !LogicalBuilder.readsNoTable(s) =>
          val table = catalog.get(name).getOrElse(throw new UnprintablePlanException(
            s"The plan reads $name, which is no table of the catalog (a CTE's materialized rows)"))
          if (table.schema.fields == view.schema.fields) item(name, alias.getOrElse(name), view.schema.fieldNames)
          else {
            // A view the optimizer pruned to some of the table's columns.
            val tableAlias = alias.getOrElse(name)
            val columns = view.schema.fieldNames
            if (!columns.forall(c => table.schema.fieldNames.contains(c)))
              throw new UnprintablePlanException(s"The plan reads columns of $name the table does not have")
            val names = unique(columns)
            val list = columns.zip(names).map { case (c, n) =>
              s"${identifier(tableAlias)}.${identifier(c)} AS ${identifier(n)}"
            }
            val name2 = freshName()
            define(name2, Vector(s"SELECT ${list.mkString(", ")}", s"FROM ${
              if (tableAlias.equalsIgnoreCase(name)) identifier(name) else s"${identifier(name)} AS ${identifier(tableAlias)}"
            }"))
            item(name2, name2, names)
          }
        case other =>
          val names = unique(other.outputSchema.fieldNames)
          val body = query(other, names)
          val name = freshName()
          define(name, body)
          item(name, name, names)
      }
    }

    /** The columns `items` hold, one after another, each qualified by its
      * item's alias. A name its item holds twice cannot be read. */
    private def columnScope(items: Seq[FromItem]): Scope = {
      val at = items.flatMap(it => it.columns.indices.map(j => (it, j))).toIndexedSeq
      i => {
        val (it, j) = at(i)
        val name = it.columns(j)
        if (it.columns.count(_.equalsIgnoreCase(name)) > 1)
          throw new UnprintablePlanException(s"The plan reads ${it.alias}.$name, a name its source has twice")
        Fragment(s"${identifier(it.alias)}.${identifier(name)}", Atomic)
      }
    }

    /** `names` with each one another of them repeats, regardless of case,
      * given a suffix. */
    private def unique(names: IndexedSeq[String]): IndexedSeq[String] = {
      val seen = mutable.HashSet.empty[String]
      names.map { n =>
        var name = n
        var k = 2
        while (seen(name.toLowerCase(Locale.ROOT))) { name = s"${n}_$k"; k += 1 }
        seen += name.toLowerCase(Locale.ROOT)
        name
      }
    }
  }
}
