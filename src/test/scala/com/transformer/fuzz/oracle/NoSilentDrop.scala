package com.transformer.fuzz.oracle

import com.transformer.core.{DataType, ExecutionOptions, Field, Schema}
import com.transformer.fuzz.{RowOracle, SqlTextGen}
import com.transformer.fuzz.SqlTextGen.{ClauseCase, Context, HonouredCase, RowEffect}
import com.transformer.sql.plan.{LogicalBuilder, UnsupportedClauseException}

import scala.util.Try

/** Nothing the parser accepts is silently dropped: a clause either changes
  * the outcome or is refused. Two halves, by what the engine does with the
  * clause.
  *
  * '''A clause the engine does not implement''' — an entry of
  * `UnsupportedClauses.audits`, spliced into a context or a position
  * ([[ClauseCase]]) — must be refused at bind time. The rejection pass runs
  * before binding and reads no data, so the verdict needs none: a spliced
  * query that binds has its clause in the AST and nothing that refused it, and
  * whatever it then answers, the clause took no part. That is the finding,
  * whatever the rows are, which is also why this half does not compare
  * answers: there is no answer of the clause's to compare with. What can make
  * it vacuous is a splice that never reaches the entry (a context its example
  * does not parse in, a position the walk does not visit), which
  * `FrontendFuzzTest` guards against by counting, per entry, the contexts and
  * positions where its own rejection fires.
  *
  * '''A clause the engine honours''' — one plan 01 taught the binder, or one
  * its probing found read in one context and dropped in another
  * ([[HonouredCase]]) — must keep its meaning in every context: the context's
  * answer is the query's own answer at top level, through the context's
  * effect on rows (the same rows, each twice, or their count). When the clause
  * changes the answer at top level (it is OBSERVABLE: the query with it and
  * the same query without it differ), a context that answers what the query
  * without it answers has dropped it.
  *
  * The soundness condition: a clause does not change every answer. `OFFSET 0`
  * skips nothing, `NULLS LAST` moves nothing in a column with no NULL, ILIKE
  * and LIKE agree on text with no letters, and over such data a dropped
  * clause and a kept one look alike. The check is still sound there (the
  * context must still answer as the query does), but it says nothing about
  * the clause; [[HonouredVerdict.observable]] records which cases did, and
  * the guard requires a healthy share for every clause, so a corpus whose data
  * cannot tell a clause from its absence fails rather than passing vacuously.
  */
object NoSilentDrop {

  // ---- the clause table ------------------------------------------------------

  /** How a spliced entry was refused. */
  sealed trait TableVerdict
  /** By the rejection pass, naming this entry. */
  case object RefusedByItsEntry extends TableVerdict
  /** By something else: the parser, the binder, or another entry. Not a
    * drop, but not this entry's doing either, so the guard counts it apart. */
  final case class RefusedOtherwise(reason: String) extends TableVerdict

  def checkEntry(c: ClauseCase): TableVerdict = {
    val catalog = SqlTextGen.ReferenceTables.catalog()
    try LogicalBuilder.build(c.sql, catalog)
    catch {
      case u: UnsupportedClauseException =>
        return if (u.rejection eq c.entry) RefusedByItsEntry else RefusedOtherwise(s"another entry: ${u.rejection.clause}")
      case e: IllegalArgumentException => return RefusedOtherwise(RelEngine.rejectReason(e))
      case e: Throwable if CleanRejection.catchable(e) => return RefusedOtherwise(e.getClass.getSimpleName)
    }
    val ran = Try(RelEngine.runRows(SqlTextGen.ReferenceTables.catalog(), c.sql, ExecutionOptions.Default).size)
      .fold(e => s"then failed while running: ${e.getClass.getSimpleName}", n => s"and returned $n rows")
    throw new AssertionError(
      s"[no-silent-drop] ${c.entry.clause} was not refused: the query bound with the clause in it $ran.\n" +
        s"  entry    = ${c.entry}\n  position = ${c.position}\n  context  = ${c.context}\n  sql      = ${c.sql}")
  }

  // ---- beyond the table --------------------------------------------------------

  /** How a construct beyond the table came out in one context. */
  sealed trait BeyondVerdict
  case object Refused extends BeyondVerdict
  /** It bound, as its plan records. */
  final case class KnownDrop(plan: String) extends BeyondVerdict

  /** A construct the clause table cannot list ([[SqlTextGen.Beyond]]) must be
    * refused at bind time; one that binds is a drop, known when its plan
    * records it. */
  def checkBeyond(b: SqlTextGen.Beyond, context: Context): BeyondVerdict = {
    val sql = context.wrap(b.sql)
    try LogicalBuilder.build(sql, SqlTextGen.ReferenceTables.catalog())
    catch { case e: Throwable if CleanRejection.catchable(e) => return Refused }
    b.knownPlan match {
      case Some(plan) => KnownDrop(plan)
      case None => throw new AssertionError(
        s"[no-silent-drop] ${b.name} was not refused: the query bound with part of its text unread.\n" +
          s"  context = $context\n  sql     = $sql")
    }
  }

  // ---- honoured clauses -------------------------------------------------------

  /** A context kept a clause's meaning. `observable`: the clause changes the
    * answer at top level; `observableInContext`: it changes what the context
    * answers too, which a context that counts rows can hide. */
  final case class HonouredVerdict(observable: Boolean, observableInContext: Boolean)

  /** `None` when the engine refused one of the queries: the generator's own
    * SQL in a context that does not take it, counted by the guard. */
  def checkHonoured(hc: HonouredCase): Option[HonouredVerdict] = {
    def run(sql: String): Option[(Schema, Vector[Array[Any]])] =
      try {
        val q = RelEngine.execute(hc.tables.catalog(), sql, ExecutionOptions.Default)
        Some((q.schema, RelEngine.collectRows(q)))
      } catch { case _: IllegalArgumentException => None }
    val result = for {
      (schema, withRows) <- run(hc.honoured.withSql)
      (_, withoutRows) <- run(hc.honoured.withoutSql)
      (contextSchema, contextRows) <- run(hc.contextSql)
    } yield {
      val effectSchema = hc.context.effect match {
        case RowEffect.Counted => Schema(Vector(Field("n", DataType.LongType)))
        case _ => schema
      }
      val expected = effect(hc.context, withRows)
      val expectedWithout = effect(hc.context, withoutRows)
      val observable = RowOracle.multisetEquals(schema, withRows, withoutRows).isDefined
      val observableInContext = RowOracle.multisetEquals(effectSchema, expected, expectedWithout).isDefined
      RowOracle.multisetEquals(contextSchema, expected, contextRows).foreach { diff =>
        val dropped = observableInContext && RowOracle.multisetEquals(contextSchema, expectedWithout, contextRows).isEmpty
        val what =
          if (dropped) s"${hc.honoured.clause} is silently dropped in ${hc.context}: the context answers what the query without it answers"
          else s"${hc.context} changes the answer of a query with ${hc.honoured.clause}"
        throw new AssertionError(s"[no-silent-drop] $what.\n  $hc\n$diff")
      }
      HonouredVerdict(observable, observableInContext)
    }
    result
  }

  /** The rows a context should answer, from the query's own. */
  def effect(context: Context, rows: Vector[Array[Any]]): Vector[Array[Any]] = context.effect match {
    case RowEffect.Same => rows
    case RowEffect.Doubled => rows ++ rows
    case RowEffect.Counted => Vector(Array[Any](rows.size.toLong))
  }
}
