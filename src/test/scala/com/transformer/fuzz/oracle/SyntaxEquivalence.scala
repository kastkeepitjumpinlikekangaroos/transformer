package com.transformer.fuzz.oracle

import com.transformer.core.{ExecutionOptions, Schema}
import com.transformer.fuzz.RowOracle
import com.transformer.fuzz.SqlTextGen.SpellingCase
import com.transformer.fuzz.{SqlTextGen, TextPool}

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
    * `repro` is the pair over [[SqlTextGen.ReferenceTables]]. `onlyWhere`,
    * when set, says which cases the disagreement belongs to, read off what
    * the case holds, never off the generator's intent: a disagreement of the
    * shape anywhere else is still a failure. */
  final case class Known(shape: String, why: String, plan: String, repro: (String, String),
      onlyWhere: Option[SpellingCase => Boolean] = None) {
    def applies(sc: SpellingCase): Boolean = onlyWhere.forall(_(sc))
  }

  /** The ILIKE shape over a letter whose capital and small forms are not one
    * pair (`TextPool.unpairedCase`): `ILIKE` folds a character to the small
    * form of its capital, `LOWER` to its small form and `UPPER` to its
    * capital, so a dotless i and a dotted capital I match `i` under ILIKE and
    * part under the other two (plans/testing/15, finding F1, held for a
    * decision). Recognized from the case's text and its `t.s` values. */
  private val IlikeOverUnpairedCase = Known("ILIKE",
    "ILIKE folds a letter to the small form of its capital, which LOWER and UPPER do not, for a dotless i and a " +
      "dotted capital I",
    "plans/testing/15 (finding F1)",
    (s"SELECT a FROM t WHERE '${TextPool.text(0x131)}' ILIKE 'i'",
      s"SELECT a FROM t WHERE LOWER('${TextPool.text(0x131)}') LIKE LOWER('i')"),
    Some(sc => SqlTextGen.holdsUnpairedCase(sc)))

  /** The last two before plans/testing/15, a quoted identifier and a quote
    * inside a literal, were fixed by plans/robustness/13. */
  val KnownDefects: Vector[Known] = Vector(IlikeOverUnpairedCase)

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
        known(sc.shape).filter(_.applies(sc)) match {
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
