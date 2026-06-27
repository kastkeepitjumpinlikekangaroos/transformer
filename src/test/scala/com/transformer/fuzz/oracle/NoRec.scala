package com.transformer.fuzz.oracle

import com.transformer.core.{Catalog, ExecutionOptions}
import com.transformer.fuzz.MetaQueryGen.NoRecCase
import com.transformer.fuzz.SqlRender

/** The NoREC-style optimizer-equivalence relation for one `(relation, predicate)`:
  * the count obtained by a `WHERE`-filtered scan must equal the count obtained by
  * summing a per-row predicate flag over the unfiltered scan.
  * {{{
  *   SELECT COUNT(*) FROM t WHERE p
  *     ==
  *   SELECT SUM(CASE WHEN p THEN 1 ELSE 0 END) FROM t
  * }}}
  * Both count exactly the rows where `p` is TRUE (a NULL/UNKNOWN `p` is filtered
  * out by `WHERE` and contributes the `ELSE 0` on the right), so they must agree.
  * It is cheap, and it specifically targets the optimizer's FilterPushdown /
  * column-pruning / pushdown-to-parquet correctness — a pushdown that drops or
  * keeps the wrong rows breaks the left side while the right side (a plain
  * aggregate over an unfiltered scan) stays correct.
  *
  * Empty-relation edge: `COUNT(*) WHERE p` is `0`, while `SUM(...)` over no rows
  * is SQL NULL — both normalize to `0` here ([[RelEngine.scalarLong]] maps the
  * NULL to `None`), so the relation still holds.
  */
object NoRec {

  def check(rc: NoRecCase): RelEngine.Verdict = {
    val catalog = new Catalog
    catalog.register(rc.relation.name, rc.relation.dataset.singlePartition())

    val from = s"${rc.relation.name} ${rc.alias}"
    val p = SqlRender.expr(rc.predicate)
    val filtered = s"SELECT COUNT(*) AS c FROM $from WHERE $p"
    val summed = s"SELECT SUM(CASE WHEN $p THEN 1 ELSE 0 END) AS c FROM $from"

    val filteredRows =
      try RelEngine.runRows(catalog, filtered, ExecutionOptions.Default)
      catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
    val summedRows = RelEngine.runRows(catalog, summed, ExecutionOptions.Default)

    val byWhere = RelEngine.scalarLong(filteredRows).getOrElse(0L)
    val bySum = RelEngine.scalarLong(summedRows).getOrElse(0L)
    if (byWhere != bySum) {
      throw new AssertionError(
        s"[NoREC] COUNT(*) WHERE p ($byWhere) != SUM(CASE WHEN p THEN 1 ELSE 0 END) ($bySum)\n" +
          s"  filtered = $filtered\n  summed   = $summed")
    }
    RelEngine.Held
  }
}
