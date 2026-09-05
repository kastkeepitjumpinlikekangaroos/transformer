package com.transformer.fuzz.oracle

import com.transformer.core.ExecutionOptions
import com.transformer.fuzz.MetaQueryGen._
import com.transformer.fuzz.RowOracle
import com.transformer.sql.plan.JoinKind

/** Join commutativity: swapping a join's two sides and flipping its kind must not
  * change the result.
  *
  *   `A INNER JOIN B ON p`  ≡  `B INNER JOIN A ON p`
  *   `A LEFT  JOIN B ON p`  ≡  `B RIGHT JOIN A ON p`
  *   `A RIGHT JOIN B ON p`  ≡  `B LEFT  JOIN A ON p`
  *   `A FULL  JOIN B ON p`  ≡  `B FULL  JOIN A ON p`
  *
  * Only the FROM clause's first join is rewritten; the ON predicate, the SELECT
  * list, WHERE, GROUP BY and HAVING are all reused VERBATIM. That is sound because
  * every generated column reference is alias-qualified and the aliases travel with
  * their leaves, so the rewritten query has the identical scope — and because the
  * SELECT list is explicit (never `*`), the output column ORDER is identical too.
  * The two runs must therefore agree as multisets, exactly.
  *
  * What this catches that the other relations do not: [[MetaModeDifferential]]
  * runs one query text under many modes, and [[Tlp]] partitions one query's
  * output — neither ever changes which side of a join is the BUILD side. This
  * relation does, on every seed: the planner picks the build side from relation
  * cardinality (`shouldBuildRight`) and re-derives the null-extension direction
  * from the join kind, so a swap-side/null-extension mismatch shows up here as a
  * wrong answer that every execution mode agrees on.
  *
  * Skipped when there is no join to commute, or when the query is not
  * layout-invariant (a window function's tie-break follows the row order the join
  * emits, which the swap legitimately changes).
  */
object JoinCommutativity {

  def check(mc: MetaCase): RelEngine.Verdict = {
    val q = mc.query
    if (q.from.joins.isEmpty || !q.isLayoutInvariant) return RelEngine.Skipped
    val body = q.renderBody
    val swappedBody = q.copy(from = commuteFirstJoin(q.from)).renderBody

    val baseQ =
      try RelEngine.execute(mc.env.catalog(), body, ExecutionOptions.Default)
      catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
    val schema = baseQ.schema
    val baseRows = RelEngine.collectRows(baseQ)

    val swapped =
      try RelEngine.execute(mc.env.catalog(), swappedBody, ExecutionOptions.Default)
      catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
    val swappedRows = RelEngine.collectRows(swapped)

    RowOracle.multisetEquals(schema, baseRows, swappedRows).foreach { diff =>
      throw new AssertionError(
        s"[join-commutativity] commuted join disagrees.\n  body    = $body\n  swapped = $swappedBody\n$diff")
    }
    RelEngine.Held
  }

  /** Swap the FROM root with the first join's leaf, flipping that join's kind.
    * Any further joins keep their kind and ON predicate: they see the same set of
    * aliases in scope, so a left-deep chain stays bindable and its semantics are
    * unchanged. */
  private def commuteFirstJoin(from: FromClause): FromClause = {
    val first = from.joins.head
    val rest = from.joins.tail
    FromClause(first.leaf, JoinItem(flip(first.kind), from.root, first.on) +: rest)
  }

  private def flip(k: JoinKind): JoinKind = k match {
    case JoinKind.Left => JoinKind.Right
    case JoinKind.Right => JoinKind.Left
    case JoinKind.Inner => JoinKind.Inner
    case JoinKind.Full => JoinKind.Full
  }
}
