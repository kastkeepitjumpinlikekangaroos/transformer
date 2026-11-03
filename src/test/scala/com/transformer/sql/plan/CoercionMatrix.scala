package com.transformer.sql.plan

import com.transformer.core.DataType
import com.transformer.fuzz.oracle.CleanRejection
import com.transformer.sql.plan.Semantics._

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** The coercion tables, `comparisons.txt`, `branches.txt` and
  * `arithmetic.txt`: where two operands of the kinds [[Semantics.Kinds]]
  * meet, in every construct of each of the three families that decide how,
  * the type they meet as (a comparison) or the result's type (a branch, a
  * piece of arithmetic), or the refusal. Each cell is read off the plan
  * `LogicalBuilder.build` binds over one-column tables, with no data.
  *
  * A row is an ordered pair and a column a construct, so "every construct in
  * a family agrees" shows as identical columns; a refusal is a code whose
  * message is listed once under its table. A text literal is one the other
  * operand's type reads ([[text]]), so the cell shows the type rule rather
  * than the text's own failure, which the cast matrix shows. */
object CoercionMatrix {

  /** One construct of a family: its column's heading, the query it writes
    * over the SQL of its two operands, and the name its refusals give it,
    * which a cell's code leaves out, so one rule is one code in every
    * column. */
  final case class Construct(heading: String, query: (String, String) => String, what: Option[String] = None) {
    /** `message` with this construct's own name as `<construct>`. */
    def generic(message: String): String = what match {
      case Some(w) if message.startsWith(w + " ") => "<construct>" + message.substring(w.length)
      case _ => message
    }
  }

  /** A family and its constructs, and how a cell is read off the plan. */
  final case class Family(file: String, about: Seq[String], constructs: Seq[Construct], read: LogicalPlan => DataType)

  private def select(e: String) = s"SELECT $e AS v FROM t"

  /** The type operands meet as in a bound comparison: the type of the first
    * comparison, IN, BETWEEN, NULLIF or CASE switch the plan holds. */
  private def metAs(plan: LogicalPlan): DataType = {
    def find(e: Expr): Option[DataType] = e match {
      case BinOpExpr(op, l, _, _) if Set("=", "<", ">=", "<=")(op) => Some(l.dataType)
      case InListExpr(child, _, _) => Some(child.dataType)
      case BetweenExpr(child, _, _) => Some(child.dataType)
      case FuncExpr("NULLIF", Seq(_, second), _) => Some(second.dataType)
      case SwitchCaseExpr(_, branches, _, _) => branches.headOption.map(_.compareAs)
      case other => children(other).iterator.flatMap(find).nextOption()
    }
    projected(plan).flatMap(find).getOrElse(throw new IllegalStateException(s"no comparison in $plan"))
  }

  /** The type of the one column a bound query returns. */
  private def resultType(plan: LogicalPlan): DataType = plan.outputSchema.fields.head.dataType

  private def projected(plan: LogicalPlan): Option[Expr] = plan match {
    case LogicalProject(_, Seq((e, _))) => Some(e)
    case other => other.children.iterator.flatMap(projected).nextOption()
  }

  private def children(e: Expr): Seq[Expr] = e match {
    case _: LitExpr | _: ColRefExpr => Nil
    case CastExpr(c, _) => Seq(c)
    case DecimalCastExpr(c, _) => Seq(c)
    case UnaryOpExpr(_, c, _) => Seq(c)
    case BinOpExpr(_, l, r, _) => Seq(l, r)
    case FuncExpr(_, args, _) => args
    case CaseExpr(branches, elseE, _) => branches.flatMap { case (w, t) => Seq(w, t) } ++ elseE
    case IsNullExpr(c, _) => Seq(c)
    case InListExpr(c, items, _) => c +: items
    case LikeExpr(c, p, _, _) => Seq(c, p)
    case SwitchCaseExpr(o, branches, elseE, _) => o +: (branches.flatMap(b => Seq(b.when, b.value)) ++ elseE)
    case BetweenExpr(c, low, high) => Seq(c, low, high)
  }

  val Comparisons: Family = Family("comparisons.txt", Seq(
    "Where two operands are compared, the type they meet as (Analyzer.unify): =, <, BETWEEN (each operand once as",
    "the operand and once as a bound), IN, NULLIF (which returns its first operand as it is, so the cell is the",
    "type its two are compared as) and a CASE switch."),
    Seq(
      Construct("x = y", (x, y) => select(s"$x = $y")),
      Construct("x < y", (x, y) => select(s"$x < $y")),
      Construct("x BTW y AND y", (x, y) => select(s"$x BETWEEN $y AND $y")),
      Construct("x BTW x AND y", (x, y) => select(s"$x BETWEEN $x AND $y")),
      Construct("x IN (y)", (x, y) => select(s"$x IN ($y)")),
      Construct("NULLIF(x, y)", (x, y) => select(s"NULLIF($x, $y)")),
      Construct("CASE x WHEN y", (x, y) => select(s"CASE $x WHEN $y THEN 1 END"))),
    metAs)

  val Branches: Family = Family("branches.txt", Seq(
    "Where two values become one result, its type (Analyzer.branches): a CASE's THEN and ELSE, COALESCE, IF,",
    "GREATEST, LEAST, LAG's value and default, and a column of a UNION's two arms."),
    Seq(
      Construct("CASE THEN x ELSE y", (x, y) => select(s"CASE WHEN c THEN $x ELSE $y END"), Some("CASE")),
      Construct("COALESCE(x, y)", (x, y) => select(s"COALESCE($x, $y)"), Some("COALESCE")),
      Construct("IF(c, x, y)", (x, y) => select(s"IF(c, $x, $y)"), Some("IF")),
      Construct("GREATEST(x, y)", (x, y) => select(s"GREATEST($x, $y)"), Some("GREATEST")),
      Construct("LEAST(x, y)", (x, y) => select(s"LEAST($x, $y)"), Some("LEAST")),
      Construct("LAG(x, 1, y)", (x, y) => select(s"LAG($x, 1, $y) OVER (ORDER BY k)"), Some("LAG")),
      Construct("x UNION y", (x, y) => s"SELECT $x AS v FROM t UNION ALL SELECT $y FROM t",
        Some("UNION column 1 (v)"))),
    resultType)

  val Arithmetic: Family = Family("arithmetic.txt", Seq(
    "Arithmetic, the result's type (Analyzer.arithmetic): + - * / % and MOD, which binds as %."),
    Seq("+", "-", "*", "/", "%").map(op => Construct(s"x $op y", (x, y) => select(s"$x $op $y"))) :+
      Construct("MOD(x, y)", (x, y) => select(s"MOD($x, $y)")),
    resultType)

  val Families: Seq[Family] = Seq(Comparisons, Branches, Arithmetic)

  /** The text literal written beside an operand of `other`'s kind: text that
    * kind's type reads, so the cell is the type rule, not the text's own
    * failure. */
  def text(other: Kind): String = other match {
    case Column(DataType.BooleanType) => "'true'"
    case Column(DataType.DateType) | Column(DataType.TimestampType) => "'2026-01-01'"
    case Column(t) if DataType.isNumeric(t) => "'1'"
    case IntegerLiteral | DecimalLiteral => "'1'"
    case _ => "'x'"
  }

  /** The SQL of an operand of `kind` beside one of `other`, named `column`
    * when it is a column. */
  def operand(kind: Kind, other: Kind, column: String): String = kind match {
    case _: Column => column
    case NullLiteral => "NULL"
    case TextLiteral => text(other)
    case IntegerLiteral => "1"
    case DecimalLiteral => "2.5"
  }

  /** One cell: the type, or the refusal. */
  sealed trait Cell
  final case class Typed(t: DataType) extends Cell
  final case class Refused(e: Throwable) extends Cell

  /** A family's cells: for every ordered pair of kinds, the cell of each
    * construct, with the SQL it bound. */
  def cells(family: Family): Seq[((Kind, Kind), Seq[(String, Cell)])] = cached.getOrElseUpdate(family.file, compute(family))

  private val cached = scala.collection.concurrent.TrieMap.empty[String, Seq[((Kind, Kind), Seq[(String, Cell)])]]

  private def compute(family: Family): Seq[((Kind, Kind), Seq[(String, Cell)])] =
    for (a <- Kinds; b <- Kinds) yield {
      val columns = Seq("x" -> typeOf(a), "y" -> typeOf(b), "c" -> DataType.BooleanType, "k" -> DataType.IntType)
      val (x, y) = (operand(a, b, "x"), operand(b, a, "y"))
      (a, b) -> family.constructs.map { construct =>
        val sql = construct.query(x, y)
        sql -> (bindPlan(sql, columns) match {
          case Right(plan) => Typed(family.read(plan))
          case Left(e) => Refused(e)
        })
      }
    }

  private def typeOf(kind: Kind): DataType = kind match {
    case Column(t) => t
    case _ => DataType.IntType
  }

  /** A refusal's code: the first hex digits of its message's digest, so a
    * message keeps its code whatever else a rule change adds or removes. */
  def code(message: String): String = {
    val digest = MessageDigest.getInstance("MD5").digest(message.getBytes(StandardCharsets.UTF_8))
    "R" + digest.take(3).map(b => f"${b & 0xff}%02x").mkString
  }

  private val Widths = (16, 15)

  /** A cell as its table shows it: a type, or its refusal's message, with
    * the construct's own name left out. */
  def shown(construct: Construct, c: Cell): String = c match {
    case Typed(t) => DataType.sqlName(t)
    case Refused(e) => construct.generic(message(e))
  }

  def generate(family: Family): Generated = {
    val all = cells(family)
    val messages = all.flatMap { case (_, cs) => family.constructs.zip(cs).collect {
      case (construct, (_, r: Refused)) => shown(construct, r)
    } }.distinct.sorted
    val codes = messages.map(m => m -> code(m)).toMap
    require(codes.values.toSet.size == codes.size, s"two refusals of ${family.file} share a code")
    val (kindWidth, cellWidth) = Widths
    def row(fields: Seq[String]): String =
      (fields.take(2).map(_.padTo(kindWidth, ' ')) ++ fields.drop(2).map(_.padTo(cellWidth, ' '))).mkString(" ")
        .replaceAll("\\s+$", "")
    val heading = row(Seq("x", "y") ++ family.constructs.map(_.heading))
    val rows = all.map { case ((a, b), cs) =>
      row(Seq(a.label, b.label) ++ family.constructs.zip(cs).map { case (construct, (_, cell)) =>
        cell match {
          case r: Refused => codes(shown(construct, r))
          case typed => shown(construct, typed)
        }
      })
    }
    val refusals = "# Refusals, by code:" +: messages.map(m => s"${codes(m)}  $m")
    val raised = all.flatMap(_._2).collect { case (sql, Refused(e)) => Raised(sql, CleanRejection.Phase.Bind, e) }
    Generated(header(family.file, family.about ++ About) ++ (heading +: rows) ++ ("" +: refusals),
      raised.toVector, Vector.empty)
  }

  private val About = Seq(
    "",
    "A row is an ordered pair of kinds of operand: x, then y. A column is a construct. A cell is a type, or the",
    "code of the refusal the query binds to, its message listed under the table. An operand is a column of its",
    "kind's type, or the literal NULL, 1 (an integer), 2.5 (a decimal) or a text the other operand's type",
    "reads ('1' beside a number, 'true' beside a BOOLEAN, '2026-01-01' beside a DATE or a TIMESTAMP, 'x' beside",
    "anything else), so a cell shows the type rule, and casts.txt each text's own reading. A cell is read off the",
    "bound plan, with no data. In a refusal, <construct> stands for the name its message gives the construct.")
}
