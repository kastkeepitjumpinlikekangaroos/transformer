package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, ColumnarBatch, DataType, ExecutionOptions}
import com.transformer.fuzz.SqlTextGen
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.plan._

import scala.util.control.NonFatal
import scala.util.matching.Regex

/** The clean-rejection property for one SQL text: exactly one of
  *
  *   - it binds and runs to the last row;
  *   - it fails at bind time with an `IllegalArgumentException` — the
  *     parser's (`SQL parse error: ...`), the rejection pass's (an
  *     `UnsupportedClauseException`) or the binder's — whose message names
  *     something the user wrote;
  *   - it binds, and then fails while it runs because of a value: a string a
  *     CAST cannot read, a decimal that does not fit its type.
  *
  * Anything else is a finding: an internal exception class reaching the
  * caller (`NullPointerException`, `ClassCastException`, `MatchError`,
  * `IllegalStateException`, ...), a message that carries an internal marker (an
  * aggregate slot name, a JVM local, a qualified or a bare class name, an
  * object's identity hash, an internal invariant's text), and a failure at run
  * time that is not about a value, which is a rejection that waited for the
  * data — the shape that made a failing job task cascade every task below it to
  * `Skipped` (plans/bugfixes 03).
  *
  * It needs no notion of what the right answer is, only of what a good failure
  * looks like, so it can run over text that does not parse, which is most of
  * the mutation corpus.
  *
  * '''Known defects.''' The defects this property has found are listed in
  * [[Defect]], each with the plan that records it. A failure that is one is
  * counted as it ([[KnownFailure]]) rather than reported. It is recognized
  * from what was thrown, never from the generator's intent, so a defect is
  * excused only where it is the cause. Removing an entry is the proof its fix landed; each entry
  * carries a repro, and `FrontendFuzzTest.everyKnownDefectStillReproduces`
  * fails when one stops showing on it, so an entry cannot outlive its fix.
  */
object CleanRejection {

  sealed abstract class Phase(val name: String) { override def toString: String = name }
  object Phase {
    /** Parsing, the rejection pass and binding: `LogicalBuilder.build`. */
    case object Bind extends Phase("bind")
    /** Planning, CTE materialization and draining every row. */
    case object Run extends Phase("run")
  }

  /** How one text came out. */
  sealed trait Outcome
  /** It bound and ran to the last row. */
  case object Ran extends Outcome
  /** It failed cleanly. `kind` is `parse`, `unsupported clause`, `bind` or
    * `data`. */
  final case class Failed(phase: Phase, kind: String, message: String) extends Outcome
  /** Its failure is a known defect. */
  final case class KnownFailure(defect: Defect, detail: String) extends Outcome

  /** A defect this property found, the plan that records it, and SQL that
    * shows it over [[SqlTextGen.ReferenceTables]]. `recognize` says whether a
    * throwable is the defect, from what was thrown alone, and what to tally it
    * by. */
  sealed abstract class Defect(val name: String, val plan: String, val repro: String) {
    def recognize(thrown: Throwable): Option[String]
    override def toString: String = name
  }
  object Defect {
    /** None today. The last were plans/robustness/13's literal out of its
      * type's range, and plans/robustness/14's deep expression overflowing the
      * stack and unclosed parentheses running the parser into its timeout. */
    val All: Vector[Defect] = Vector.empty
  }

  // ---------------------------------------------------------------------------
  // The property
  // ---------------------------------------------------------------------------

  /** Bind `sql`, and if it binds, run it to the last row; classify a failure.
    * Throws `AssertionError` on a failure that is not clean. */
  def check(sql: String, catalog: () => Catalog): Outcome = {
    val bound =
      try Right(LogicalBuilder.build(sql, catalog()))
      catch { case e: Throwable if catchable(e) => Left(e) }
    bound match {
      case Left(e) => classify(sql, Phase.Bind, e)
      case Right(_) =>
        try {
          RelEngine.collectRows(SqlEngine.execute(sql, catalog(), ExecutionOptions.Default))
          Ran
        } catch { case e: Throwable if catchable(e) => classify(sql, Phase.Run, e) }
    }
  }

  /** Every non-fatal throwable, and a `StackOverflowError`, which recursion
    * over a deep expression throws and which unwinds cleanly. */
  def catchable(e: Throwable): Boolean = NonFatal(e) || e.isInstanceOf[StackOverflowError]

  /** Classify what `sql` threw in `phase`. Separate from [[check]] so the
    * classification can be tested on throwables made to order. */
  def classify(sql: String, phase: Phase, thrown: Throwable): Outcome = {
    recognize(thrown) match {
      case Some((defect, detail)) => return KnownFailure(defect, detail)
      case None => ()
    }
    def violation(why: String): Nothing = {
      val err = new AssertionError(
        s"[clean-rejection] $why\n  phase   = $phase\n  thrown  = ${chain(thrown)}\n  message = " +
          s"${SqlTextGen.clip(String.valueOf(thrown.getMessage))}\n  sql     = ${SqlTextGen.clip(sql)}")
      err.initCause(thrown)
      throw err
    }

    val cleanClass = thrown match {
      case _: IllegalArgumentException => true
      case _: ArithmeticException => phase == Phase.Run
      case _ => false
    }
    if (!cleanClass) violation(s"an internal exception class reached the caller: ${thrown.getClass.getName}")

    val message = thrown.getMessage
    if (message == null || message.trim.isEmpty || message == "null") violation("the failure carries no message")
    leaks(sql, message).headOption.foreach(l => violation(s"the message carries $l"))

    val kind = thrown match {
      case u: UnsupportedClauseException =>
        if (!u.getMessage.contains("is not supported")) violation("an unsupported clause's message does not say so")
        "unsupported clause"
      case _ if message.startsWith("SQL parse error:") => "parse"
      case _ if phase == Phase.Run =>
        if (!DataErrors.exists(_.findFirstIn(message).isDefined))
          violation("a failure while running that is not about a value: a rejection that waited for the data")
        "data"
      case _ =>
        if (!namesTheSql(sql, message)) violation("the message names nothing the SQL says")
        "bind"
    }
    Failed(phase, kind, message)
  }

  // ---------------------------------------------------------------------------
  // What a clean message is
  // ---------------------------------------------------------------------------

  /** Internal markers: what a message shows only when it was written for the
    * engine's own authors. A marker the SQL itself contains is the user's
    * word (a column named `_agg0`, an alias in CamelCase) and does not count. */
  private val Markers: Vector[(String, Regex)] = Vector(
    "an internal slot name" -> """\b_(?:agg|k)\d+\b""".r,
    "a JVM local-variable name" -> """<local\d+>""".r,
    "an anonymous class" -> """\$anon|\$\$Lambda|\$Lambda\$""".r,
    "a qualified class name" ->
      """\b(?:java|javax|jdk|sun|scala|net\.sf\.jsqlparser|com\.transformer)(?:\.[A-Za-z_$][\w$]*)+""".r,
    "a NullPointerException's detail" -> """Cannot invoke "|because "|because the return value of""".r,
    "an object's identity hash" -> """\w@[0-9a-f]{5,8}\b""".r,
    "a Scala collection's rendering" -> """\b(?:Some|Vector|List|ArrayBuffer|ArraySeq|WrappedArray|HashMap|Map|Set)\(""".r,
    "an internal invariant's text" ->
      """requirement failed|assertion failed|empty\.head|None\.get|key not found|next on empty iterator|Index -?\d+ out of bounds""".r,
    "a class name" -> """\b[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]+)+\b""".r)

  /** The internal markers in `message` that `sql` does not contain. */
  def leaks(sql: String, message: String): Vector[String] =
    Markers.flatMap { case (what, re) =>
      re.findAllIn(message).filterNot(m => sql.contains(m)).toVector.headOption.map(m => s"$what ('$m')")
    }

  /** The run-time failures that are about a value, and so belong at run time:
    * a CAST that cannot read its input (a string, or a float's `NaN` or
    * infinity, which no DECIMAL, INT or BIGINT holds and which a comparison
    * with a DECIMAL widens to it), and a decimal or integer answer that does
    * not fit its type, each an error by design (plans/robustness/18). */
  private val DataErrors: Vector[Regex] = Vector(
    """^Cannot cast (?:'.*'|NaN|-?Infinity) to [a-z]+(?:\(\d+,\d+\))?$""".r,
    """^Cannot cast (?:NaN|-?Infinity) to (?:INT|BIGINT): it has no integer value$""".r,
    """^Decimal overflow: \S+ does not fit decimal\(\d+,\d+\)$""".r,
    """^Integer overflow: .+ does not fit (?:INT|BIGINT)$""".r)

  /** SQL's own vocabulary, which a message may name a construct by even when
    * the query does not spell it that way (`Only SELECT statements are
    * supported` for an INSERT). */
  private val SqlVocabulary: Set[String] = Set(
    "SELECT", "FROM", "WHERE", "GROUP", "HAVING", "ORDER", "LIMIT", "OFFSET", "FETCH", "TOP", "UNION", "JOIN",
    "WITH", "CTE", "CAST", "CASE", "DISTINCT", "WINDOW", "OVER", "PARTITION", "AGGREGATE", "COLUMN", "TABLE",
    "VIEW", "FUNCTION", "EXPRESSION", "LITERAL", "ESCAPE", "LIKE", "ILIKE", "TRIM", "ALL", "NULL", "INT",
    "BIGINT", "LONG", "DOUBLE", "FLOAT", "STRING", "BOOLEAN", "DATE", "TIMESTAMP", "DECIMAL", "BINARY", "STATEMENT",
    "SUBQUERIES", "SUBQUERY", "PRECEDING", "FOLLOWING", "RECURSIVE", "STAR", "ARITHMETIC", "AND", "OR", "NOT")

  /** Whether `message` names something `sql` says, or a construct by SQL's
    * own word for it. An empty SQL text says nothing to name. */
  def namesTheSql(sql: String, message: String): Boolean = {
    val sqlWords = SqlTextGen.Lexer.words(sql)
    if (sqlWords.isEmpty) return true
    val words = """[A-Za-z0-9_]+""".r.findAllIn(message).map(_.toUpperCase(java.util.Locale.ROOT)).toSet
    words.exists(w => w.length >= 2 && (sqlWords(w) || SqlVocabulary(w)))
  }

  // ---------------------------------------------------------------------------
  // Known defects
  // ---------------------------------------------------------------------------

  private def recognize(thrown: Throwable): Option[(Defect, String)] =
    Defect.All.iterator.flatMap(d => d.recognize(thrown).map(d -> _)).nextOption()

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** `t` and its causes, outermost first. */
  def causes(t: Throwable): Vector[Throwable] = {
    val out = Vector.newBuilder[Throwable]
    var c = t
    var n = 0
    while (c != null && n < 20) { out += c; c = c.getCause; n += 1 }
    out.result()
  }

  def chain(t: Throwable): String = causes(t).map(_.getClass.getName).mkString(" <- ")
}

/** Walks a bound [[LogicalPlan]]: every expression in it, and the decimal
  * values of a branch list. */
object BoundPlan {

  def nodes(p: LogicalPlan): Iterator[LogicalPlan] = Iterator(p) ++ p.children.iterator.flatMap(nodes)

  def exprs(p: LogicalPlan): Iterator[Expr] = nodes(p).flatMap(ownExprs).flatMap(subtree)

  /** For a CASE, COALESCE or IF of DECIMAL type with a value that is
    * arithmetic of a DECIMAL type other than its first DECIMAL value's, that
    * first type, each value read under the CAST the binder puts on it: the
    * shape that once took the first value's type and kept the arithmetic's
    * answer as it was (plans/robustness/09). */
  def decimalArithmeticBeside(e: Expr): Option[DataType.DecimalType] = {
    val values = e match {
      case CaseExpr(branches, elseExpr, _: DataType.DecimalType) => branches.map(_._2) ++ elseExpr
      case FuncExpr("COALESCE", args, _: DataType.DecimalType) => args
      case FuncExpr("IF", args, _: DataType.DecimalType) => args.drop(1)
      case _ => Nil
    }
    val written = values.map {
      case CastExpr(v, t) if t == e.dataType => v
      case v => v
    }
    written.map(_.dataType).collectFirst { case d: DataType.DecimalType => d }.filter { first =>
      written.exists {
        case BinOpExpr(op, _, _, t: DataType.DecimalType) => Set("+", "-", "*", "/", "%")(op) && t != first
        case _ => false
      }
    }
  }

  /** Whether `e` answers on some row of `batch` with more fractional digits
    * than `first` keeps. Only over columns `batch` has, so an expression that
    * reads another schema (an aggregate's output, a join's) is not
    * evaluated. */
  def outgrows(e: Expr, first: DataType.DecimalType, batch: ColumnarBatch): Boolean = {
    val fields = batch.schema.fields
    subtree(e).forall {
      case c: ColRefExpr => c.index < fields.length && fields(c.index).dataType == c.dataType
      case _ => true
    } && (0 until batch.numRows).exists { row =>
      scala.util.Try(e.eval(batch, row)).toOption.exists {
        case d: java.math.BigDecimal => d.stripTrailingZeros.scale > first.scale
        case _ => false
      }
    }
  }

  private def ownExprs(p: LogicalPlan): Iterator[Expr] = p match {
    case LogicalProject(_, projections) => projections.iterator.map(_._1)
    case LogicalFilter(_, predicate) => Iterator(predicate)
    case LogicalAggregate(_, keys, aggregates, having) =>
      keys.iterator.map(_._1) ++ aggregates.iterator.flatMap(_._1.args) ++ having.iterator
    case LogicalJoin(_, _, condition, _) => Iterator(condition)
    case LogicalSort(_, keys) => keys.iterator.map(_.expr)
    case LogicalWindow(_, windows) => windows.iterator.flatMap { w =>
      w.spec.partitionKeys.iterator ++ w.spec.orderKeys.iterator.map(_.expr) ++ (w.fn match {
        case WindowFnLag(child, _, default) => Iterator(child) ++ default.iterator
        case WindowFnLead(child, _, default) => Iterator(child) ++ default.iterator
        case WindowFnAgg(agg) => agg.args.iterator
        case _ => Iterator.empty
      })
    }
    case _ => Iterator.empty
  }

  def subtree(e: Expr): Iterator[Expr] = Iterator(e) ++ children(e).iterator.flatMap(subtree)

  private def children(e: Expr): Seq[Expr] = e match {
    case _: LitExpr | _: ColRefExpr => Nil
    case CastExpr(c, _) => Seq(c)
    case UnaryOpExpr(_, c, _) => Seq(c)
    case BinOpExpr(_, l, r, _) => Seq(l, r)
    case FuncExpr(_, args, _) => args
    case CaseExpr(branches, elseE, _) => branches.flatMap { case (w, t) => Seq(w, t) } ++ elseE
    case IsNullExpr(c, _) => Seq(c)
    case InListExpr(c, items, _) => c +: items
    case LikeExpr(c, p, _, _) => Seq(c, p)
  }
}
