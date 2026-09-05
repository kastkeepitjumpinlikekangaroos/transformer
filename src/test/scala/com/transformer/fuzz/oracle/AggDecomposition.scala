package com.transformer.fuzz.oracle

import com.transformer.core.{DataType, ExecutionOptions}
import com.transformer.fuzz.MetaQueryGen.{MetaCase, Relation}
import com.transformer.fuzz.RowOracle

/** Aggregate decomposition: aggregating a relation in one shot must equal
  * grouping it by an arbitrary key and re-aggregating the per-group results.
  *
  * {{{
  *   SELECT MIN(v) FROM r
  *     ≡ WITH g AS (SELECT k, MIN(v) AS m FROM r GROUP BY k) SELECT MIN(m) FROM g
  *   SELECT COUNT(*) FROM r
  *     ≡ WITH g AS (SELECT k, COUNT(*) AS c FROM r GROUP BY k) SELECT SUM(c) FROM g
  * }}}
  *
  * Both sides are decided by SQL semantics alone — no reference engine — and the
  * grouping key is arbitrary, so the identity holds for any `k`. What it targets
  * is the aggregate's PARTIAL/FINAL machinery: the one-shot side keeps a single
  * accumulator, while the grouped side builds one accumulator per group, spills
  * and restores them, merges partials across partitions, and feeds the results
  * back through a second aggregate. A state whose update / merge / finish /
  * spill-serde paths disagree about where a value lives shows up here as a plain
  * wrong answer.
  *
  * Run under spill as well as in heap, because the spill round-trip is the step
  * the one-shot side never takes: a global aggregate holds one state and never
  * flushes, so a serde defect is invisible until the grouped side restores it.
  * (That is exactly how `MIN`/`MAX` over a `Boolean` column used to break —
  * `MinMaxState` accumulated into the boxed slot and serialized the long slot,
  * so a spilled group came back NULL.)
  *
  * Aggregates chosen for exactness: `MIN`/`MAX` over every column type,
  * `COUNT(*)` and `COUNT(v)` (re-aggregated with `SUM`, normalizing the
  * empty-relation `SUM` NULL to 0), and `SUM` over INTEGRAL columns only —
  * a float `SUM` legitimately differs in the last bits when the addition
  * regroups, and this relation reports exact equality.
  */
object AggDecomposition {

  private val SpillOn: ExecutionOptions =
    ExecutionOptions(spillEnabled = true, spillThresholdBytes = Some(1L))

  /** One decomposition to check: the SQL for both sides, the type its scalar
    * result compares as, and whether a NULL result normalizes to 0 (the
    * `SUM`-of-`COUNT`s case over an empty relation). */
  private final case class Pair(label: String, oneShot: String, grouped: String,
      dataType: DataType, nullIsZero: Boolean)

  def check(mc: MetaCase): RelEngine.Verdict = {
    val rel = mc.env.relations.head
    if (rel.keyCols.isEmpty) return RelEngine.Skipped
    val pairs = decompositions(rel)
    if (pairs.isEmpty) return RelEngine.Skipped
    // Both guards above are unreachable for the current generator (every relation
    // gets >= 1 key column and `decompositions` always yields the COUNT(*) pair).
    // `aggDecompositionCoversEveryColumnType` asserts that stays true, so a
    // generator change cannot quietly turn this relation into a no-op.

    Seq(("heap", ExecutionOptions.Default), ("spill", SpillOn)).foreach { case (mode, opts) =>
      pairs.foreach { p =>
        val a =
          try scalar(mc, p.oneShot, opts, p)
          catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
        val b =
          try scalar(mc, p.grouped, opts, p)
          catch { case e: IllegalArgumentException => return RelEngine.Rejected(RelEngine.rejectReason(e)) }
        if (!RowOracle.scalarEquals(a, b, p.dataType)) {
          throw new AssertionError(
            s"[agg-decomposition] $mode ${p.label} disagrees: one-shot=${RowOracle.describe(a)} " +
              s"grouped=${RowOracle.describe(b)}\n  one-shot = ${p.oneShot}\n  grouped  = ${p.grouped}")
        }
      }
    }
    RelEngine.Held
  }

  private def scalar(mc: MetaCase, sql: String, opts: ExecutionOptions, p: Pair): Any = {
    val rows = RelEngine.runRows(mc.env.catalog(), sql, opts)
    val v = rows.headOption.map(_(0)).orNull
    if (v == null && p.nullIsZero) java.lang.Long.valueOf(0L) else v
  }

  /** The columns `rel`'s decompositions aggregate over: the group key itself and
    * the last field, rather than every column — each pair costs two executions per
    * mode, and the relation's power comes from the variety across seeds, not from
    * re-running the same state machinery over sibling columns of one relation. */
  private def aggColumns(rel: Relation): Seq[Int] =
    Seq(rel.keyCols.head, rel.schema.fields.length - 1).distinct

  /** What `check` would exercise for `mc`, without running a single query: `None`
    * when the relation has nothing to check (the [[RelEngine.Skipped]] path),
    * otherwise the column types its `MIN`/`MAX` decompositions aggregate over.
    *
    * `MIN`/`MAX` is the aggregate whose accumulator slot is chosen from the column
    * type, so a type silently dropping out of the corpus is exactly what would let
    * a slot bug back in — which is how `MIN`/`MAX` over a `Boolean` column stayed
    * broken under spill. The coverage guard in `MetamorphicFuzzTest` reads this. */
  private[fuzz] def coveredTypes(mc: MetaCase): Option[Set[DataType]] = {
    val rel = mc.env.relations.head
    if (rel.keyCols.isEmpty || decompositions(rel).isEmpty) None
    else Some(aggColumns(rel).map(i => rel.schema.fields(i).dataType).toSet)
  }

  /** The decompositions checked for `rel`, grouped on its first key column. */
  private def decompositions(rel: Relation): Seq[Pair] = {
    val fields = rel.schema.fields
    val keyIdx = rel.keyCols.head
    val aggCols = aggColumns(rel)
    val k = s"t.${fields(keyIdx).name}"
    val r = s"${rel.name} t"

    def pair(label: String, inner: String, outer: String, dt: DataType, nullIsZero: Boolean = false): Pair =
      Pair(label,
        s"SELECT $inner AS a FROM $r",
        s"WITH g AS (SELECT $k AS gk, $inner AS m FROM $r GROUP BY $k) SELECT $outer AS a FROM g",
        dt, nullIsZero)

    val countPairs = Seq(
      pair("COUNT(*)", "COUNT(*)", "SUM(m)", DataType.LongType, nullIsZero = true))

    val perColumn = aggCols.flatMap { i =>
      val c = s"t.${fields(i).name}"
      val dt = fields(i).dataType
      val minMax = Seq(
        pair(s"MIN($c)", s"MIN($c)", "MIN(m)", dt),
        pair(s"MAX($c)", s"MAX($c)", "MAX(m)", dt))
      val counts = Seq(
        pair(s"COUNT($c)", s"COUNT($c)", "SUM(m)", DataType.LongType, nullIsZero = true))
      // SUM only over integral columns: a float SUM re-associates when the rows
      // are grouped, so the two sides differ in the last bits by design.
      val sums =
        if (DataType.isIntegral(dt)) Seq(pair(s"SUM($c)", s"SUM($c)", "SUM(m)", DataType.LongType))
        else Seq.empty
      minMax ++ counts ++ sums
    }
    countPairs ++ perColumn
  }
}
