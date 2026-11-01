package com.transformer.sql.exec

import com.transformer.core._
import com.transformer.sql.plan._

import java.util.Locale

/** What `EXPLAIN` returns: a query's plan, without running any of it, as one
  * STRING column, `plan`, a row per line.
  *
  *   - `EXPLAIN <query>` shows the plan the optimizer makes, as a tree of
  *     operators ([[PlanTree]]) and as SQL ([[PlanSql]]). A CTE the query
  *     reads twice or more is materialized before the query runs, so the tree
  *     scans it and its own plan follows.
  *   - `EXPLAIN VERBOSE <query>` shows the plan as bound first, as a tree and
  *     as SQL, then the optimized plan, then the physical operators that run
  *     it.
  *
  * The logical sections depend only on the query and the views' columns, so a
  * test can hold a query to its plan under any whole-suite configuration; the
  * physical one also depends on the views' sizes and the JVM's sharding gates.
  */
object Explain {

  val Column: String = "plan"

  /** The lines `EXPLAIN [VERBOSE] query` returns. */
  def lines(query: String, catalog: Catalog, opts: ExecutionOptions, verbose: Boolean): Vector[String] = {
    val built = LogicalBuilder.build(query, catalog)
    val planned = CteResolver.planned(built)
    val optimized = BuiltQuery(LogicalOptimizer.optimize(planned.main),
      planned.ctes.map(c => c.copy(body = LogicalOptimizer.optimize(c.body))))
    val out = Vector.newBuilder[String]
    if (verbose) {
      out += "== Bound Logical Plan =="
      out ++= trees(built)
      out += "== Bound SQL =="
      out ++= sql(built, catalog)
    }
    out += "== Optimized Logical Plan =="
    out ++= trees(optimized)
    out += "== Optimized SQL =="
    out ++= sql(optimized, catalog)
    if (verbose) {
      out += "== Physical Plan =="
      out ++= physical(optimized, opts)
    }
    out.result()
  }

  /** `EXPLAIN [VERBOSE] query` as a query's result. */
  def execute(query: String, catalog: Catalog, opts: ExecutionOptions, verbose: Boolean): ExecutedQuery = {
    val text = lines(query, catalog, opts, verbose)
    val schema = Schema(Vector(Field(Column, DataType.StringType)))
    val batch = ColumnarBatch.fromColumns(schema,
      Array[ColumnVector](new StringVector(text.toArray, math.max(1, text.size))), text.size)
    new ExecutedQuery(schema, IndexedSeq(Iterator.single(batch)))
  }

  /** The main plan's tree, then each CTE's, which the main plan scans. */
  private def trees(q: BuiltQuery): Vector[String] =
    PlanTree.lines(q.main) ++ q.ctes.toVector.flatMap { cte =>
      s"CTE ${PlanSql.identifier(cte.name)}:" +: PlanTree.lines(cte.body).map("  " + _)
    }

  private def sql(q: BuiltQuery, catalog: Catalog): Vector[String] =
    try PlanSql.print(q, catalog).split("\n").toVector
    catch { case e: PlanSql.UnprintablePlanException => Vector(s"(no SQL form: ${e.getMessage})") }

  /** The physical operators of `q`: each CTE it materializes first, then the
    * main query, whose scan of such a CTE reads rows EXPLAIN never computes. */
  private def physical(q: BuiltQuery, opts: ExecutionOptions): Vector[String] = {
    val noMetrics = opts.copy(metricsEnabled = false)
    val stand = q.ctes.map(c => c.pending -> new UnrunCte(c.name, c.pending.schema))
    def unpend(p: LogicalPlan): LogicalPlan = p match {
      case LogicalScan(name, view: PendingCteView, alias) =>
        stand.collectFirst { case (pending, unrun) if pending eq view => LogicalScan(name, unrun, alias) }.getOrElse(p)
      case other => other.withChildren(other.children.map(unpend))
    }
    def plan(p: LogicalPlan): Vector[String] = {
      val logical = unpend(p)
      describe(PhysicalPlanner.plan(logical, noMetrics), names(logical))
    }
    q.ctes.toVector.flatMap(cte => s"CTE ${PlanSql.identifier(cte.name)}, materialized:" +: plan(cte.body).map("  " + _)) ++
      plan(q.main)
  }

  /** A CTE the query materializes before it runs, which EXPLAIN plans but
    * never runs. */
  private final class UnrunCte(val name: String, val schema: Schema) extends CatalogView {
    def numPartitions: Int = 1
    def readPartition(p: Int): Iterator[ColumnarBatch] =
      throw new IllegalStateException(s"EXPLAIN does not run CTE '$name'")
  }

  /** The name each view `p` scans is read by. */
  private def names(p: LogicalPlan): java.util.IdentityHashMap[CatalogView, String] = {
    val out = new java.util.IdentityHashMap[CatalogView, String]()
    def visit(x: LogicalPlan): Unit = {
      x match {
        case LogicalScan(name, view, _) => out.put(view, name)
        case _ => ()
      }
      x.children.foreach(visit)
    }
    visit(p)
    out
  }

  /** `plan` as an indented tree of its operators, each with the partitions
    * it runs in. */
  private def describe(plan: PhysicalPlan, names: java.util.IdentityHashMap[CatalogView, String]): Vector[String] = {
    val out = Vector.newBuilder[String]
    def columns(p: PhysicalPlan): Int => String = {
      val fields = p.outputSchema.fieldNames
      i => PlanSql.identifier(fields(i))
    }
    def expr(e: Expr, p: PhysicalPlan): String = PlanSql.expression(e, columns(p))
    def keys(ks: Seq[SortKey], p: PhysicalPlan): String = ks.map { k =>
      expr(k.expr, p) + (if (k.asc) "" else " DESC") +
        (if (k.nullsFirst == k.asc) "" else if (k.nullsFirst) " NULLS FIRST" else " NULLS LAST")
    }.mkString(", ")
    def visit(p: PhysicalPlan, depth: Int): Unit = {
      val (line, children) = p match {
        case ScanExec(view, _) =>
          val name = Option(names.get(view)).fold("a view")(n => PlanSql.identifier(n))
          (s"Scan $name: ${view.schema.fieldNames.map(PlanSql.identifier).mkString(", ")}", Nil)
        case CountStarMetadataExec(count, column) =>
          (s"Count from the view's metadata: $count AS ${PlanSql.identifier(column)}", Nil)
        case FilterExec(c, predicate) => (s"Filter: ${expr(predicate, c)}", Seq(c))
        case ProjectExec(c, projections) =>
          val list = projections.map { case (e, n) =>
            val text = expr(e, c)
            if (text == PlanSql.identifier(n)) text else s"$text AS ${PlanSql.identifier(n)}"
          }
          (s"Project: ${list.mkString(", ")}", Seq(c))
        case HashAggregateExec(c, groupKeys, aggregates, _, _) =>
          val parts = (if (groupKeys.isEmpty) Nil else Seq("GROUP BY " + groupKeys.map(k => expr(k._1, c)).mkString(", "))) ++
            Seq(aggregates.map { case (a, n) => s"${PlanSql.aggregate(a, columns(c))} AS ${PlanSql.identifier(n)}" }
              .mkString(", ")).filter(_.nonEmpty)
          (s"HashAggregate: ${if (parts.isEmpty) "one group" else parts.mkString("; ")}", Seq(c))
        case HashJoinExec(l, r, leftKeys, rightKeys, extra, kind, buildRight, _, _) =>
          val both: Int => String = { val all = (l.outputSchema.fieldNames ++ r.outputSchema.fieldNames); i => PlanSql.identifier(all(i)) }
          val kindName = kind.toString.toUpperCase(Locale.ROOT)
          val on = leftKeys.zip(rightKeys).map { case (lk, rk) => s"${expr(lk, l)} = ${expr(rk, r)}" } ++
            extra.map(PlanSql.expression(_, both))
          val build = if (buildRight) "builds the right side" else "builds the left side"
          if (leftKeys.isEmpty) (s"NestedLoopJoin $kindName: ${on.mkString(" AND ")}; $build", Seq(l, r))
          else (s"HashJoin $kindName: ${on.mkString(" AND ")}; $build", Seq(l, r))
        case SortExec(c, ks, _, _) => (s"Sort: ${keys(ks, c)}", Seq(c))
        case LocalLimitExec(c, n) => (s"Limit (each partition): $n", Seq(c))
        case GlobalLimitExec(c, n) => (s"Limit: $n", Seq(c))
        case OffsetExec(c, n) => (s"Offset: $n", Seq(c))
        case DistinctExec(c, _, _) => ("Distinct", Seq(c))
        case UnionExec(l, r) => ("Union ALL", Seq(l, r))
        case WindowExec(c, windows, _, _) =>
          (s"Window: ${windows.map(w => s"${PlanSql.window(w, columns(c))} AS ${PlanSql.identifier(w.outputName)}").mkString(", ")}",
            Seq(c))
        case ExchangeExec(c, partitionBy, shards, _, _) =>
          (s"Exchange: hash of ${partitionBy.map(expr(_, c)).mkString(", ")} into $shards shards", Seq(c))
        case other => (other.getClass.getSimpleName, Nil)
      }
      out += s"${"  " * depth}$line [${p.numPartitions} partition${if (p.numPartitions == 1) "" else "s"}]"
      children.foreach(visit(_, depth + 1))
    }
    visit(plan, 0)
    out.result()
  }
}
