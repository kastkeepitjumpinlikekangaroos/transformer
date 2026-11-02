package com.transformer.fuzz.oracle

import com.transformer.core.ExecutionOptions
import com.transformer.fuzz.MetaQueryGen.{MetaCase, TlpPred}
import com.transformer.fuzz.RowOracle

/** The TLP (Ternary Logic Partitioning) metamorphic relation for one generated
  * `(env, query)`: for any row-producing query `Q` and any predicate `p` over
  * `Q`'s OUTPUT columns,
  * {{{
  *   Q  ≡  (Q WHERE p)  ⊎  (Q WHERE NOT p)  ⊎  (Q WHERE p IS NULL)   (as multisets)
  * }}}
  * This decides correctness with no reference engine — it holds by SQL semantics
  * alone — and it is a direct, brutal test of three-valued NULL logic and the
  * `selectByBoolean` filter, the engine's subtlest surface. It catches the class
  * of bug every execution mode agrees on (a wrong answer mode-differential is
  * blind to): a LEFT JOIN that drops a null-extension, a `NOT IN` with a NULL
  * that returns rows it shouldn't, a comparison whose UNKNOWN result is routed to
  * the wrong partition.
  *
  * '''Output-level, never aggregate decomposition.''' The engine has no
  * derived-table subquery, so `Q` is wrapped in a CTE and `p` filters its output:
  * {{{
  *   WITH q AS ( <Q> ) SELECT * FROM q [WHERE <partition>]
  * }}}
  * Because `p` filters `Q`'s already-computed OUTPUT rows, the relation is sound
  * for EVERY query shape — projection, join, aggregate, window, union — with no
  * partition-and-re-aggregate step (which would be unsound for AVG / stddev /
  * COUNT DISTINCT). The three partitions cover every output row exactly once: a
  * row where `p` is TRUE, FALSE, or UNKNOWN, and `p IS NULL` captures exactly the
  * UNKNOWN rows. A comparison is UNKNOWN exactly when an operand is NULL, over
  * every type, floats included: `NaN` equals `NaN` and orders above every other
  * number, and `-0.0` equals `0.0`, so a `NaN` row falls into a partition like
  * any other. The partition may compare a float column with `NaN`, `-0.0` or an
  * infinity itself.
  *
  * All four queries run against ONE fixed single-partition layout, so the
  * comparison is immune to any layout-dependent output ordering (e.g. a window
  * function's tie-break among equal ORDER BY keys) — cross-layout invariance is
  * [[MetaModeDifferential]]'s job, not TLP's. Comparison is a multiset (output
  * order is never assumed) via [[RowOracle.multisetEquals]].
  */
object Tlp {

  def check(mc: MetaCase): RelEngine.Verdict = mc.tlp match {
    case None => RelEngine.Skipped // no type-reliable output column to partition on
    case Some(pred) =>
      val base = mc.query.tlpBase // WITH [cte defs,] q AS (...) SELECT * FROM q
      val catalog = mc.env.catalog() // one fixed single-partition layout for all four

      // The CTE base doubles as the bind/plan probe: a rejection here is the
      // generator's own un-bindable SQL, not a finding.
      val baseQ =
        try RelEngine.execute(catalog, base, ExecutionOptions.Default)
        catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }

      val schema = baseQ.schema
      val baseRows = RelEngine.collectRows(baseQ)
      // A partition that does not bind names a column the base no longer
      // returns, which only a shrink step makes (one that drops the column
      // the predicate reads): nothing to check, like a base that does not.
      val partRows = Seq(pred.truePred, pred.falsePred, pred.nullPred).flatMap { p =>
        val q =
          try RelEngine.execute(catalog, s"$base WHERE $p", ExecutionOptions.Default)
          catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
        RelEngine.collectRows(q)
      }

      RowOracle.multisetEquals(schema, baseRows, partRows).foreach { diff =>
        throw new AssertionError(
          s"[TLP] base != (p ⊎ NOT p ⊎ p IS NULL).\n" +
            s"  base      = $base\n" +
            s"  partition = ${describe(pred)}\n$diff")
      }
      RelEngine.Held
  }

  private def describe(p: TlpPred): String =
    s"TRUE[${p.truePred}]  FALSE[${p.falsePred}]  NULL[${p.nullPred}]"
}
