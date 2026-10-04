package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, DataType, ExecutionOptions}
import com.transformer.fuzz.SqlTextGen
import com.transformer.sql.exec.SqlEngine
import com.transformer.sql.plan._

import java.util.concurrent.{CompletionException, ExecutionException}
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
  * [[Defect]], each with the plan that records it. A failure that IS one is
  * counted as it ([[KnownFailure]]) rather than reported; a failure that
  * merely carries one on the way — a wrapper around it, a class name in its
  * message — is unwrapped or excused just that far and checked for everything
  * else. Both are recognized from what was thrown and, for a run-time failure,
  * from the bound plan, never from the generator's intent, so a defect is
  * excused only where it is the cause. Removing an entry is the proof its fix
  * landed; each entry carries a repro, and
  * `FrontendFuzzTest.everyKnownDefectStillReproduces` fails when one stops
  * showing on it, so an entry cannot outlive its fix.
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
    * `data`; `defects` are the known defects it carried on the way. */
  final case class Failed(phase: Phase, kind: String, message: String, defects: Vector[Defect]) extends Outcome
  /** Its failure is a known defect. */
  final case class KnownFailure(defect: Defect, detail: String) extends Outcome

  private val Plans = "plans/robustness/"
  val MessagesPlan: String = Plans + "10-error-messages-name-internals.md"
  val ArgumentTypesPlan: String = Plans + "11-functions-bind-over-arguments-they-cannot-take.md"
  val LiteralsPlan: String = Plans + "13-literals-are-read-as-written.md"
  val PathologicalInputPlan: String = Plans + "14-pathological-nesting.md"
  val BranchTypesPlan: String = Plans + "09-branch-result-types.md"

  /** A defect this property found, the plan that records it, and SQL that
    * shows it over [[SqlTextGen.ReferenceTables]]. */
  sealed abstract class Defect(val name: String, val plan: String, val repro: String) {
    override def toString: String = name
  }
  object Defect {
    /** Every parse error: `SQL parse error: net.sf.jsqlparser.parser.ParseException: Encountered ...`. */
    case object ParserClassInParseError extends Defect("a parse error names the parser's exception class", MessagesPlan,
      "SELECT FROM t")
    /** `Unsupported expression: ExistsExpression: EXISTS (...)`, `Unsupported
      * FROM item: ParenthesedSelect`, `LIMIT must be an integer literal, got
      * SignedExpression`, and the rest of the binder's messages that name the
      * JSqlParser class they met. */
    case object ParserClassInRejection extends Defect("a rejection names a JSqlParser class", MessagesPlan,
      "SELECT * FROM (SELECT a FROM t) x")
    /** A run-time failure inside a pooled operator arrives as an
      * `ExecutionException` whose message is the chain of class names. */
    case object PoolWrappedFailure extends Defect("a run-time failure arrives wrapped in ExecutionException", MessagesPlan,
      "SELECT SUM(CAST(s AS INT)) AS v FROM t")
    /** `SELECT a, COUNT(*) FROM t`: `Unknown column 'a'. Available: [_agg0]`. */
    case object AggSlotInUnknownColumn extends Defect("an ungrouped column's error lists aggregate slots", MessagesPlan,
      "SELECT a, COUNT(*) AS c FROM t")
    /** `SELECT 99999999999999999999 FROM t` is a raw `NumberFormatException`
      * at bind time; `1e400` silently becomes `Infinity`, which fails while
      * running beside a DECIMAL (`Cannot cast Infinity to decimal(9,2)`, or a
      * `NumberFormatException` with no message). */
    case object NumberLiteralOutOfRange extends Defect("a number literal outside its type's range", LiteralsPlan,
      "SELECT 99999999999999999999 AS big FROM t")
    /** `CAST(TRUE AS DOUBLE)`: a raw `NumberFormatException` while it runs. */
    case object BooleanCastToNumber extends Defect("CAST of a BOOLEAN to FLOAT or DOUBLE is a NumberFormatException",
      MessagesPlan, "SELECT CAST(TRUE AS DOUBLE) AS v FROM t")
    /** `SUM(s)`, `ROUND(d)`, `SUBSTRING(s, 'x')`: the scalar functions check
      * their arity only and the numeric aggregates nothing, so each binds over
      * any type and fails while it runs. */
    case object FunctionOverWrongType extends Defect("a function binds over an argument type it cannot take",
      ArgumentTypesPlan, "SELECT SUM(s) AS v FROM t")
    /** `t JOIN u ON t.a`, `IF(s, 'y', 'n')`: a join's ON condition or IF's
      * condition that is not a predicate binds as it is, where WHERE, CASE
      * WHEN and every AND operand get a cast to BOOLEAN, and it dies on its
      * first row with a `ClassCastException`. */
    case object ConditionNotBoolean extends Defect("a condition that is not a predicate binds",
      ArgumentTypesPlan, "SELECT t.a FROM t JOIN u ON t.a")
    /** `WHERE CAST(a AS ROW(x INT)) IS FALSE`: a node the rejection pass does
      * not audit is refused by the binder's catch-all, whose message renders
      * the node with JSqlParser's `toString`, and that throws a
      * `NullPointerException` for a ROW-type CAST inside it. */
    case object RenderingThrows extends Defect("rendering a refused expression throws inside JSqlParser",
      MessagesPlan, "SELECT a FROM t WHERE CAST(a AS ROW(x INT)) IS FALSE")
    /** `ROUND(a, NULL)`, `SUBSTRING(s, b)` over a NULL `b`: a
      * `NullPointerException` while it runs. */
    case object NullFunctionArgument extends Defect("a NULL scale, position or length is a NullPointerException",
      ArgumentTypesPlan, "SELECT ROUND(a, NULL) AS v FROM t")
    /** `SELECT a FROM t UNION SELECT s FROM t`: arms of different types bind,
      * and comparing their values fails while it runs. */
    case object MixedBranchTypes extends Defect("values of different types meet in one column", BranchTypesPlan,
      "SELECT a FROM t UNION SELECT s FROM t")
    /** A 2000-term `+` or `OR` chain overflows the binder's stack, 1000
      * nested calls or 3000 nested parentheses the parser's. */
    case object DeepNesting extends Defect("a deep expression overflows the stack", PathologicalInputPlan,
      "SELECT 0" + " + 0" * 2500 + " AS v FROM t")
    /** Four unclosed parentheses (`SELECT ((((a +`) send JSqlParser into
      * backtracking until its six-second timeout, and the error then names
      * nothing: `SQL parse error: Time out occurred.` */
    case object ParseTimeout extends Defect("unclosed parentheses run the parser into its timeout",
      PathologicalInputPlan, "SELECT ((((a +")

    val All: Vector[Defect] = Vector(ParserClassInParseError, ParserClassInRejection, PoolWrappedFailure,
      AggSlotInUnknownColumn, NumberLiteralOutOfRange, BooleanCastToNumber, FunctionOverWrongType,
      ConditionNotBoolean, RenderingThrows, NullFunctionArgument, MixedBranchTypes, DeepNesting, ParseTimeout)
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
      case Left(e) => classify(sql, Phase.Bind, e, None)
      case Right(built) =>
        try {
          RelEngine.collectRows(SqlEngine.execute(sql, catalog(), ExecutionOptions.Default))
          Ran
        } catch { case e: Throwable if catchable(e) => classify(sql, Phase.Run, e, Some(built)) }
    }
  }

  /** Every non-fatal throwable, and a `StackOverflowError`, which recursion
    * over a deep expression throws and which unwinds cleanly. */
  def catchable(e: Throwable): Boolean = NonFatal(e) || e.isInstanceOf[StackOverflowError]

  /** Classify what `sql` threw in `phase`; `built` is its bound plan when it
    * bound. Separate from [[check]] so the classification can be tested on
    * throwables made to order. */
  def classify(sql: String, phase: Phase, thrown: Throwable, built: Option[BuiltQuery]): Outcome = {
    recognize(sql, thrown, built) match {
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

    val defects = Vector.newBuilder[Defect]
    val e = unwrapPool(thrown)
    if (e ne thrown) defects += Defect.PoolWrappedFailure

    val cleanClass = e match {
      case _: IllegalArgumentException => true
      case _: ArithmeticException => phase == Phase.Run
      case _ => false
    }
    if (!cleanClass) violation(s"an internal exception class reached the caller: ${e.getClass.getName}")

    val raw = e.getMessage
    if (raw == null || raw.trim.isEmpty || raw == "null") violation("the failure carries no message")
    val (message, excused) = normalize(raw)
    defects ++= excused
    leaks(sql, message).headOption.foreach(l => violation(s"the message carries $l"))

    val kind = e match {
      case u: UnsupportedClauseException =>
        if (!u.getMessage.contains("is not supported")) violation("an unsupported clause's message does not say so")
        "unsupported clause"
      case _ if raw.startsWith("SQL parse error:") => "parse"
      case _ if phase == Phase.Run =>
        if (!DataErrors.exists(_.findFirstIn(message).isDefined))
          violation("a failure while running that is not about a value: a rejection that waited for the data")
        "data"
      case _ =>
        if (!namesTheSql(sql, message)) violation("the message names nothing the SQL says")
        "bind"
    }
    Failed(phase, kind, message, defects.result())
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
    * infinity, which no DECIMAL holds and which a comparison with one widens
    * to it), and a decimal answer with more integer digits than its type
    * holds, which is an error by design. */
  private val DataErrors: Vector[Regex] = Vector(
    """^Cannot cast (?:'.*'|NaN|-?Infinity) to [a-z]+(?:\(\d+,\d+\))?$""".r,
    """^Decimal overflow: \S+ does not fit decimal\(\d+,\d+\)$""".r)

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

  /** JSqlParser's exception class, which the parse-error wrapper keeps at the
    * front of every message. */
  private val ParseErrorPrefix: Regex = """^SQL parse error: (?:[a-z][\w$]*\.)+[A-Z][\w$]*: """.r

  /** The binder's messages that name the AST class they met; group 2 is the
    * class. */
  private val ClassNamingRejections: Vector[Regex] = Vector(
    """^(Unsupported expression: )([A-Z]\w*)(: )""".r,
    """^(Unsupported FROM item: )([A-Z]\w*)$""".r,
    """^(Unsupported SELECT shape: )([A-Z]\w*)$""".r,
    """^(Unsupported set operation: )([A-Z]\w*)( \()""".r,
    """^(IN subqueries not supported \(got )([A-Z]\w*)(\))""".r,
    """^((?:LIMIT|OFFSET|TOP|FETCH FIRST) must be an integer literal, got )([A-Z]\w*)$""".r,
    """^(Only SELECT statements are supported \(got )([A-Z]\w*)(\))""".r)

  /** `message` with the known defects in it excused: the parser's class name
    * cut from the front of a parse error, a JSqlParser class name in one of
    * the binder's messages replaced by `<class>`. */
  def normalize(message: String): (String, Vector[Defect]) =
    ParseErrorPrefix.findPrefixMatchOf(message) match {
      case Some(m) => ("SQL parse error: " + message.substring(m.end), Vector(Defect.ParserClassInParseError))
      case None =>
        ClassNamingRejections.iterator.flatMap(_.findFirstMatchIn(message)).nextOption() match {
          case Some(m) =>
            (message.substring(0, m.start(2)) + "<class>" + message.substring(m.end(2)), Vector(Defect.ParserClassInRejection))
          case None => (message, Vector.empty)
        }
    }

  // ---------------------------------------------------------------------------
  // Known defects that end the check
  // ---------------------------------------------------------------------------

  private def recognize(sql: String, thrown: Throwable, built: Option[BuiltQuery]): Option[(Defect, String)] = {
    val all = causes(thrown)
    val root = all.last
    val message = Option(thrown.getMessage).getOrElse("")
    if (all.exists(_.isInstanceOf[StackOverflowError]) || all.exists(c => String.valueOf(c.getMessage).contains("java.lang.StackOverflowError")))
      return Some(Defect.DeepNesting -> chain(thrown))
    if (thrown.isInstanceOf[IllegalArgumentException] && AggSlotList.findFirstIn(message).isDefined)
      return Some(Defect.AggSlotInUnknownColumn -> message)
    if (thrown.isInstanceOf[IllegalArgumentException] && all.exists(_.isInstanceOf[java.util.concurrent.TimeoutException]))
      return Some(Defect.ParseTimeout -> message)
    val plans = built.toVector.flatMap(b => b.main +: b.ctes.map(_.body))
    def anyExpr(p: Expr => Boolean): Boolean = plans.exists(plan => BoundPlan.exprs(plan).exists(p))
    def anyAgg(p: AggExpr => Boolean): Boolean = plans.exists(plan => BoundPlan.aggs(plan).exists(p))
    val numberInput = root match {
      case nfe: NumberFormatException => NumberInput.findFirstMatchIn(String.valueOf(nfe.getMessage)).map(_.group(1))
      case _ => None
    }
    numberInput match {
      case Some(digits) if digits.nonEmpty && digits.forall(_.isDigit) && digits.length >= 19 && sql.contains(digits) =>
        return Some(Defect.NumberLiteralOutOfRange -> s"${digits.take(30)}${if (digits.length > 30) "..." else ""}")
      case Some("true" | "false") if anyExpr(isBooleanCastToFloat) =>
        return Some(Defect.BooleanCastToNumber -> numberInput.get)
      case _ => ()
    }
    val infiniteLiteral = SqlTextGen.Lexer.lex(sql).exists(t =>
      t.kind == SqlTextGen.Lexer.Kind.Number && scala.util.Try(t.text.toDouble).toOption.exists(_.isInfinite))
    if (built.isDefined && infiniteLiteral && (root.isInstanceOf[NumberFormatException] ||
        all.exists(c => String.valueOf(c.getMessage).contains("Infinity"))))
      return Some(Defect.NumberLiteralOutOfRange -> s"${root.getClass.getSimpleName}: ${root.getMessage}")
    root match {
      // Not by the message: HotSpot throws a hot implicit ClassCastException
      // preallocated, with none.
      case _: ClassCastException if plans.exists(BoundPlan.hasNonBooleanJoinCondition) || anyExpr(isIfOverNonBoolean) =>
        Some(Defect.ConditionNotBoolean -> String.valueOf(root.getMessage))
      case _: NullPointerException if root.getStackTrace.exists(f =>
          f.getClassName.startsWith("net.sf.jsqlparser.") && f.getMethodName == "toString") ||
          String.valueOf(root.getMessage).contains("net.sf.jsqlparser.") =>
        Some(Defect.RenderingThrows -> String.valueOf(root.getMessage))
      case _: ClassCastException if anyExpr(isFunctionOverWrongType) || anyAgg(isAggOverWrongType) =>
        Some(Defect.FunctionOverWrongType -> String.valueOf(root.getMessage))
      case _: ClassCastException | _: NumberFormatException | _: java.time.format.DateTimeParseException |
          _: IllegalArgumentException if anyExpr(isMixedBranches) || plans.exists(BoundPlan.hasMixedUnion) =>
        Some(Defect.MixedBranchTypes -> s"${root.getClass.getSimpleName}: ${root.getMessage}")
      case _: NullPointerException if anyExpr(hasNullableSecondaryArgument) =>
        Some(Defect.NullFunctionArgument -> String.valueOf(root.getMessage))
      case _ => None
    }
  }

  private val AggSlotList: Regex = """^Unknown column '[^']*'\. Available: \[[^\]]*\b_agg\d+\b""".r
  private val NumberInput: Regex = """For input string: "([^"]*)"""".r

  private val NumericFunctions: Set[String] = Set("ABS", "ROUND", "FLOOR", "CEIL", "CEILING", "TRUNC", "TRUNCATE",
    "POW", "POWER", "SQRT", "CBRT", "EXP", "LN", "LOG", "LOG10", "LOG2", "SIN", "COS", "TAN", "ASIN", "ACOS", "ATAN",
    "ATAN2", "SINH", "COSH", "TANH", "DEGREES", "RADIANS", "SIGN")
  /** The functions whose arguments after the first are a scale, a position
    * or a length, read as numbers on every row. */
  private val SecondaryNumberArgs: Set[String] = Set("ROUND", "TRUNC", "TRUNCATE", "SUBSTRING", "LOG", "POW", "POWER", "ATAN2")

  private def numericOrNull(t: DataType): Boolean = DataType.isNumeric(t) || t == DataType.NullType

  private def isFunctionOverWrongType(e: Expr): Boolean = e match {
    case FuncExpr(name, args, _) if NumericFunctions(name.toUpperCase) => args.exists(a => !numericOrNull(a.dataType))
    case FuncExpr(name, args, _) if name.equalsIgnoreCase("SUBSTRING") => args.drop(1).exists(a => !numericOrNull(a.dataType))
    case _ => false
  }

  private def isAggOverWrongType(a: AggExpr): Boolean = a match {
    case AggExprDistinct(inner) => isAggOverWrongType(inner)
    case _: AggExprSum | _: AggExprAvg | _: AggExprStddev | _: AggExprVariance | _: AggExprCovar | _: AggExprCorr =>
      a.args.exists(x => !numericOrNull(x.dataType))
    case _ => false
  }

  private def isIfOverNonBoolean(e: Expr): Boolean = e match {
    case FuncExpr(name, cond +: _, _) if name.equalsIgnoreCase("IF") =>
      cond.dataType != DataType.BooleanType && cond.dataType != DataType.NullType
    case _ => false
  }

  private def isBooleanCastToFloat(e: Expr): Boolean = e match {
    case CastExpr(child, DataType.FloatType | DataType.DoubleType) => child.dataType == DataType.BooleanType
    case _ => false
  }

  /** A branch list whose values are of more than one kind: a CASE, COALESCE,
    * IF, NULLIF, GREATEST or LEAST mixing a number with text, a date with a
    * timestamp, or either with a boolean. A function's arguments arrive as
    * they are; a CASE's branches arrive under the cast the binder puts on each
    * to its result type, so a branch's kind is what is under that cast. */
  private def isMixedBranches(e: Expr): Boolean = {
    def kinds(types: Seq[DataType]): Set[String] = types.filterNot(_ == DataType.NullType).map(BoundPlan.kind).toSet
    e match {
      case CaseExpr(branches, elseE, resultType) =>
        val values = (branches.map(_._2) ++ elseE).map {
          case CastExpr(child, `resultType`) => child.dataType
          case other => other.dataType
        }
        kinds(values).size > 1
      case FuncExpr(name, args, _) => name.toUpperCase match {
        case "COALESCE" | "GREATEST" | "LEAST" | "NULLIF" => kinds(args.map(_.dataType)).size > 1
        case "IF" => kinds(args.drop(1).map(_.dataType)).size > 1
        case _ => false
      }
      case _ => false
    }
  }

  private def hasNullableSecondaryArgument(e: Expr): Boolean = e match {
    case FuncExpr(name, args, _) if SecondaryNumberArgs(name.toUpperCase) =>
      args.drop(1).exists {
        case LitExpr(v, _) => v == null
        case _ => true
      }
    case _ => false
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  /** The failure inside the wrappers a pooled operator adds: the
    * `ExecutionException` of `ForkJoinTask.get`; the copy of the task's
    * exception `ForkJoinTask` makes to carry the waiting thread's stack (the
    * same class, the original as its cause, and as its message the
    * original's `toString`, or none when the class has no constructor taking
    * a cause); and the `RuntimeException` it wraps a task's checked exception
    * in, which is how a nested fan-out's `ExecutionException` travels up
    * through its parent's task. */
  def unwrapPool(t: Throwable): Throwable = {
    var e = t
    var unwrapping = true
    while (unwrapping) {
      val c = e.getCause
      val copy = c != null && e.getClass == c.getClass && (e.getMessage == null || e.getMessage == c.toString)
      val wrap = c != null && e.getClass == classOf[RuntimeException] && e.getMessage == c.toString
      if (c != null && (e.isInstanceOf[ExecutionException] || e.isInstanceOf[CompletionException] || copy || wrap)) e = c
      else unwrapping = false
    }
    e
  }

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

/** Walks a bound [[LogicalPlan]]: every expression in it, every aggregate,
  * and the union arms whose column types differ. */
object BoundPlan {

  def nodes(p: LogicalPlan): Iterator[LogicalPlan] = Iterator(p) ++ p.children.iterator.flatMap(nodes)

  def exprs(p: LogicalPlan): Iterator[Expr] = nodes(p).flatMap(ownExprs).flatMap(subtree)

  def aggs(p: LogicalPlan): Iterator[AggExpr] = nodes(p).flatMap {
    case a: LogicalAggregate => a.aggregates.iterator.map(_._1)
    case w: LogicalWindow => w.windows.iterator.collect { case WindowDef(_, WindowFnAgg(agg), _) => agg }
    case _ => Iterator.empty
  }

  /** A UNION whose arms disagree on a column's type. The union takes its
    * first arm's types and keeps the other arm's values as they are, so an
    * INT column can hold a decimal or a string, and comparing them fails. */
  def hasMixedUnion(p: LogicalPlan): Boolean = nodes(p).exists {
    case LogicalUnion(l, r, _) =>
      l.outputSchema.fields.zip(r.outputSchema.fields).exists { case (a, b) =>
        a.dataType != DataType.NullType && b.dataType != DataType.NullType && a.dataType != b.dataType
      }
    case _ => false
  }

  /** A join whose ON condition is not a BOOLEAN. */
  def hasNonBooleanJoinCondition(p: LogicalPlan): Boolean = nodes(p).exists {
    case j: LogicalJoin => j.condition.dataType != DataType.BooleanType
    case _ => false
  }

  /** The kind of value a type holds: the numbers widen to one another, and
    * every other type, a DATE and a TIMESTAMP included, is a kind of its own,
    * as `Analyzer.unify` has it. */
  def kind(t: DataType): String = if (DataType.isNumeric(t)) "number" else t.name

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
