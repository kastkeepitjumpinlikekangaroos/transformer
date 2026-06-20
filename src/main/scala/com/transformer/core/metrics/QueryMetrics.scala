package com.transformer.core.metrics

/** Per-query measurements: SQL pipeline timings plus the operator-tree
  * snapshot. Captured once per `SqlExecutor.execute` call when
  * `opts.metricsEnabled` is true.
  *
  * The five `*Nanos` fields are *not* a partition of total execute time. They
  * cover the SQL compile + plan phase (parse → CTE-materialize → optimize →
  * physical-plan) and the wall time the executor spent constructing the
  * per-partition iterators + draining them at the query boundary. The operator
  * tree's [[OperatorMetrics.wallNanos]] gives the per-operator share of execute
  * time — that is the field consumers should use to attribute query latency
  * to individual operators.
  *
  * @param parseNanos        Time spent in `LogicalBuilder.build`.
  * @param cteMaterializeNanos Time spent in `CteResolver.resolve` — the whole
  *                          CTE resolve pass, including the sub-executions that
  *                          materialize any CTE referenced `>= 2` times. Those
  *                          body executions run unmeasured (they don't appear
  *                          in `operatorTree`); their cost is this single
  *                          number. Zero for queries with no CTEs and for
  *                          all-inlined CTEs.
  * @param optimizeNanos     Time spent in `LogicalOptimizer.optimize`.
  * @param physicalPlanNanos Time spent in `PhysicalPlanner.plan` including
  *                          the metrics-wrap pass.
  * @param executeNanos      Time spent constructing the per-partition
  *                          iterators in `SqlEngine.execute` (not the
  *                          drain — that is the consumer's wall time).
  * @param operatorTree      Root of the operator-metrics tree.
  */
final case class QueryMetrics(
    parseNanos: Long,
    cteMaterializeNanos: Long,
    optimizeNanos: Long,
    physicalPlanNanos: Long,
    executeNanos: Long,
    operatorTree: OperatorMetrics) {

  /** Serialize to the `_perf.json` operator-tree payload. The wrapping
    * task-level fields (taskName, timestamps, cpu/gc samples) are added by
    * [[TaskMetricsRecord.serialize]]. */
  def toJsonFragment: String = QueryMetrics.serializeFragment(this)
}

object QueryMetrics {

  /** Hand-rolled JSON serializer for a [[QueryMetrics]] embedded inside a
    * parent task record. Mirrors the [[com.transformer.job.TaskRunRecord]]
    * approach — one source of truth for the schema, no Circe/Jackson dep.
    *
    * Output shape:
    *
    * {{{
    *   {
    *     "parseNanos": 123,
    *     "cteMaterializeNanos": 0,
    *     "optimizeNanos": 45,
    *     "physicalPlanNanos": 67,
    *     "executeNanos": 890,
    *     "operatorTree": { ... }
    *   }
    * }}}
    */
  private[metrics] def serializeFragment(q: QueryMetrics): String = {
    val sb = new java.lang.StringBuilder()
    sb.append("{\n")
    sb.append("  \"parseNanos\": ").append(q.parseNanos).append(",\n")
    sb.append("  \"cteMaterializeNanos\": ").append(q.cteMaterializeNanos).append(",\n")
    sb.append("  \"optimizeNanos\": ").append(q.optimizeNanos).append(",\n")
    sb.append("  \"physicalPlanNanos\": ").append(q.physicalPlanNanos).append(",\n")
    sb.append("  \"executeNanos\": ").append(q.executeNanos).append(",\n")
    sb.append("  \"operatorTree\": ")
    serializeOperator(sb, q.operatorTree, indent = 2)
    sb.append('\n')
    sb.append('}')
    sb.toString
  }

  /** Render an [[OperatorMetrics]] subtree into `sb` with the given base
    * indent. Recursive; the tree depth in practice is bounded by SQL
    * complexity (rarely more than 10-20 deep). */
  private[metrics] def serializeOperator(sb: java.lang.StringBuilder, op: OperatorMetrics, indent: Int): Unit = {
    val pad = spaces(indent)
    val pad2 = spaces(indent + 2)
    sb.append("{\n")
    sb.append(pad2).append("\"id\": ").append(op.id).append(",\n")
    sb.append(pad2).append("\"name\": \"").append(escape(op.name)).append("\",\n")
    sb.append(pad2).append("\"partition\": ").append(op.partition).append(",\n")
    sb.append(pad2).append("\"rowsIn\": ").append(op.rowsIn).append(",\n")
    sb.append(pad2).append("\"rowsOut\": ").append(op.rowsOut).append(",\n")
    sb.append(pad2).append("\"batchesIn\": ").append(op.batchesIn).append(",\n")
    sb.append(pad2).append("\"batchesOut\": ").append(op.batchesOut).append(",\n")
    sb.append(pad2).append("\"wallNanos\": ").append(op.wallNanos).append(",\n")
    sb.append(pad2).append("\"counters\": {")
    if (op.counters.length > 0) {
      sb.append('\n')
      var i = 0
      while (i < op.counters.length) {
        sb.append(spaces(indent + 4))
        sb.append('"').append(escape(op.counterNames(i))).append("\": ").append(op.counters(i))
        if (i < op.counters.length - 1) sb.append(',')
        sb.append('\n')
        i += 1
      }
      sb.append(pad2)
    }
    sb.append("},\n")
    sb.append(pad2).append("\"children\": [")
    if (op.children.nonEmpty) {
      sb.append('\n')
      var j = 0
      while (j < op.children.length) {
        sb.append(spaces(indent + 4))
        serializeOperator(sb, op.children(j), indent + 4)
        if (j < op.children.length - 1) sb.append(',')
        sb.append('\n')
        j += 1
      }
      sb.append(pad2)
    }
    sb.append("]\n")
    sb.append(pad).append('}')
  }

  private def spaces(n: Int): String = {
    val sb = new java.lang.StringBuilder(n)
    var i = 0
    while (i < n) { sb.append(' '); i += 1 }
    sb.toString
  }

  private[metrics] def escape(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c             => sb.append(c)
      }
      i += 1
    }
    sb.toString
  }
}
