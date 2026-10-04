package com.transformer.fuzz.oracle

import com.transformer.core.{ExecutionOptions, Schema}
import com.transformer.fuzz.{RowOracle, SqlTextGen}
import com.transformer.fuzz.SqlTextGen.SpellingCase

/** Equivalent spellings agree: two ways of writing one query
  * ([[SqlTextGen.SpellingShapes]]) produce the same column types and the same
  * multiset of rows, compared with [[RowOracle.multisetEquals]].
  *
  * This is a metamorphic relation over SYNTAX, an axis the other relations do
  * not vary: they change how a query executes (layout, spill, the build side)
  * or its algebra (partitioning, decomposition), and always write it the same
  * way. A binder that misreads one spelling — reads `(a + b) * c` without its
  * parentheses, a `LIMIT` but not a `FETCH FIRST`, an inert spelling the audit
  * calls harmless but is not — answers differently for the two.
  *
  * '''Observable.''' Two spellings agree on data that cannot tell them apart:
  * any two filters agree on an empty table. Each shape carries a third query
  * that drops what the pair is about (the filter, the limit, the NULL
  * placement), and a case is observable when the pair's answer differs from
  * it; a shape that is spelling alone has none, and is observable when its
  * answer holds two distinct rows, so there is something to agree on. The
  * guard requires a share of observable cases for every shape.
  *
  * '''Known defects''' are shapes recorded as disagreeing, each with its plan
  * ([[Known]]); a case of one counts as it, and `FrontendFuzzTest` fails when
  * one stops disagreeing on its repro, so the record cannot outlive the fix.
  */
object SyntaxEquivalence {

  sealed trait Verdict
  /** The spellings agreed. `observable` as above. */
  final case class Agreed(observable: Boolean) extends Verdict
  /** The engine refused both spellings at bind time. */
  final case class BothRefused(reason: String) extends Verdict
  /** A known defect's shape disagreed, as recorded. */
  final case class KnownDisagreement(known: Known) extends Verdict

  /** A shape whose spellings disagree today, and the plan that records why.
    * `repro` is the pair over [[SqlTextGen.ReferenceTables]]. */
  final case class Known(shape: String, why: String, plan: String, repro: (String, String))

  private val Plans = "plans/robustness/"

  val KnownDefects: Vector[Known] = Vector(
    Known("IF numeric branches",
      "IF takes the wider of its branches' types and CASE its first's, so IF(p, 1, 2.5) is 2.5 where CASE is 2",
      Plans + "09-branch-result-types.md",
      ("SELECT IF(a > 1, 1, 2.5) AS v FROM t", "SELECT CASE WHEN a > 1 THEN 1 ELSE 2.5 END AS v FROM t")),
    Known("COALESCE numeric branches",
      "COALESCE takes the wider of its arguments' types and CASE its first branch's",
      Plans + "09-branch-result-types.md",
      ("SELECT COALESCE(a, 2.5) AS v FROM t", "SELECT CASE WHEN a IS NOT NULL THEN a ELSE 2.5 END AS v FROM t")),
    Known("quoted identifier",
      "a double-quoted identifier keeps its quotes, so \"a\" names no column",
      Plans + "13-literals-are-read-as-written.md",
      ("SELECT a, b FROM t", "SELECT \"a\", \"b\" FROM t")),
    Known("a quote inside a literal",
      "a string literal keeps both quotes of a doubled one, so 'it''s' is five characters",
      Plans + "13-literals-are-read-as-written.md",
      ("SELECT LENGTH('it''s') AS n FROM t", "SELECT 4 AS n FROM t")),
    Known("NOT NOT",
      "JSqlParser reads NOT NOT a = x as a comparison of NOT a with x, which then fails to bind",
      Plans + "12-text-the-binder-never-sees.md",
      ("SELECT a, b, s FROM t WHERE NOT NOT a = 1", "SELECT a, b, s FROM t WHERE a = 1")))

  def known(shape: String): Option[Known] = KnownDefects.find(_.shape == shape)

  private final case class Answer(schema: Schema, rows: Vector[Array[Any]])

  private def run(sc: SpellingCase, sql: String): Either[String, Answer] =
    try {
      val q = RelEngine.execute(sc.tables.catalog(), sql, ExecutionOptions.Default)
      Right(Answer(q.schema, RelEngine.collectRows(q)))
    } catch { case e: IllegalArgumentException => Left(RelEngine.rejectReason(e)) }

  /** Whether two answers differ: in their column types, or in their rows. */
  private def differ(a: Answer, b: Answer): Option[String] = {
    val ta = a.schema.fields.map(_.dataType)
    val tb = b.schema.fields.map(_.dataType)
    if (ta != tb) Some(s"column types differ: ${ta.mkString("(", ", ", ")")} vs ${tb.mkString("(", ", ", ")")}")
    else RowOracle.multisetEquals(a.schema, a.rows, b.rows)
  }

  def check(sc: SpellingCase): Verdict = {
    val left = run(sc, sc.left)
    val right = run(sc, sc.right)
    val disagreement: Option[String] = (left, right) match {
      case (Left(l), Left(_)) => return BothRefused(l)
      case (Left(l), Right(_)) => Some(s"the engine refuses the left spelling and runs the right: $l")
      case (Right(_), Left(r)) => Some(s"the engine runs the left spelling and refuses the right: $r")
      case (Right(l), Right(r)) => differ(l, r)
    }
    disagreement match {
      case Some(why) =>
        known(sc.shape) match {
          case Some(k) => KnownDisagreement(k)
          case None => throw new AssertionError(s"[syntax-equivalence] ${sc.shape}: the spellings disagree: $why\n  $sc")
        }
      case None =>
        val answer = left.toOption.get
        val observable = sc.degenerate match {
          case Some(d) => run(sc, d).toOption.forall(other => differ(answer, other).isDefined)
          case None => answer.rows.map(_.toSeq).distinct.size >= 2
        }
        Agreed(observable)
    }
  }
}
