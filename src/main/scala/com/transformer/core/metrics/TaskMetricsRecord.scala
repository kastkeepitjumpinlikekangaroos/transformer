package com.transformer.core.metrics

import java.nio.file.{Files, Path, StandardCopyOption}
import java.time.Instant

/** Per-task performance record written into each task's output directory
  * alongside `_run.json` when `ExecutionOptions.metricsEnabled` is true.
  *
  * File name: `_perf.json` (the `_` prefix means
  * [[com.transformer.read.csv.PathGlob]] and friends skip it when re-reading
  * the directory as data, same as `_run.json`).
  *
  * Schema versioning: this file is **developer-facing**. Unlike `_run.json` —
  * which the GUI hydrates and a future GUI integration may render — `_perf.json`
  * is consumed by `benchmarks/macro/` (Sub-plan 4) and ad-hoc developer
  * tooling, and is free to evolve. Each release bumps [[SchemaVersion]] if the
  * schema changes; consumers should check it and reject older versions rather
  * than try to back-fill missing fields.
  *
  * On rerun the file is overwritten in place (most-recent-only), matching
  * `_run.json`'s policy. Historical perf data lives in
  * `benchmarks/macro/` outputs and the checked-in
  * `benchmarks/baseline/jaffle_shop.json` (Sub-plan 4).
  *
  * @param schemaVersion   Always [[SchemaVersion]] when written.
  * @param taskName        Task display name, matches `_run.json.taskName`.
  * @param executionTime   Job-level execution time, same as `_run.json`'s
  *                        field. Lets consumers cross-reference perf data
  *                        with the corresponding `_run.json` without
  *                        re-parsing both files.
  * @param enqueuedAt      Task enqueue time (queue admission).
  * @param startedAt       Task body actually started executing.
  * @param finishedAt      Task body returned.
  * @param query           Per-query timings + the operator-metrics tree.
  *                        `None` when the metrics framework failed to
  *                        capture (e.g. a partial failure inside SqlEngine
  *                        before the tree was materialized).
  * @param cpuNanos        Per-thread CPU time consumed by the task body, as
  *                        `finishedThreadCpu - startedThreadCpu`. -1L
  *                        when the JVM doesn't support thread CPU sampling.
  * @param allocBytes      Bytes allocated by the task thread during the body.
  *                        -1L on non-HotSpot JVMs.
  * @param gcDeltaCount    Number of GC collections that fired across the
  *                        whole process during the task body. Process-level,
  *                        not thread-level — single-node, shared GC.
  * @param gcDeltaNanos    Cumulative GC wall time that fired during the task
  *                        body.
  */
final case class TaskMetricsRecord(
    schemaVersion: Int,
    taskName: String,
    executionTime: Instant,
    enqueuedAt: Instant,
    startedAt: Instant,
    finishedAt: Instant,
    query: Option[QueryMetrics],
    cpuNanos: Long,
    allocBytes: Long,
    gcDeltaCount: Long,
    gcDeltaNanos: Long
)

object TaskMetricsRecord {

  val FileName: String = "_perf.json"
  val SchemaVersion: Int = 1

  /** Atomic write of `record` to `<dir>/_perf.json`. Creates `dir` if needed.
    * Mirrors [[com.transformer.job.TaskRunRecord.write]] — temp-then-rename
    * with a fallback for filesystems that don't support atomic move.
    *
    * Callers should wrap this in a `try/catch NonFatal` so a failed perf
    * write never poisons an otherwise-successful task. */
  def write(dir: Path, record: TaskMetricsRecord): Unit = {
    if (!Files.isDirectory(dir)) Files.createDirectories(dir)
    val text = serialize(record)
    val target = dir.resolve(FileName)
    val tmp = dir.resolve(FileName + ".tmp")
    Files.writeString(tmp, text)
    try {
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case _: java.nio.file.AtomicMoveNotSupportedException =>
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
    }
  }

  /** Read `<dir>/_perf.json`. Returns None when missing or unparseable —
    * consumers (benchmarks/macro/, developer scripts) treat both the same.
    *
    * Intentionally permissive: the developer-facing schema may evolve, and a
    * macro-bench run scanning hundreds of `_perf.json` files should skip
    * unreadable ones rather than abort. Use [[read]]'s `None` result to
    * detect drift; do not try to reconstruct partial state. */
  def read(dir: Path): Option[TaskMetricsRecord] = {
    val p = dir.resolve(FileName)
    if (!Files.isRegularFile(p)) None
    else {
      try Some(parse(Files.readString(p)))
      catch { case _: Throwable => None }
    }
  }

  // ---- serialization -----------------------------------------------------

  private def serialize(r: TaskMetricsRecord): String = {
    val sb = new java.lang.StringBuilder()
    sb.append("{\n")
    sb.append("  \"schemaVersion\": ").append(r.schemaVersion).append(",\n")
    sb.append("  \"taskName\": \"").append(escape(r.taskName)).append("\",\n")
    sb.append("  \"executionTime\": \"").append(escape(r.executionTime.toString)).append("\",\n")
    sb.append("  \"enqueuedAt\": \"").append(escape(r.enqueuedAt.toString)).append("\",\n")
    sb.append("  \"startedAt\": \"").append(escape(r.startedAt.toString)).append("\",\n")
    sb.append("  \"finishedAt\": \"").append(escape(r.finishedAt.toString)).append("\",\n")
    sb.append("  \"cpuNanos\": ").append(r.cpuNanos).append(",\n")
    sb.append("  \"allocBytes\": ").append(r.allocBytes).append(",\n")
    sb.append("  \"gcDeltaCount\": ").append(r.gcDeltaCount).append(",\n")
    sb.append("  \"gcDeltaNanos\": ").append(r.gcDeltaNanos).append(",\n")
    sb.append("  \"query\": ")
    r.query match {
      case Some(qm) => sb.append(QueryMetrics.serializeFragment(qm))
      case None     => sb.append("null")
    }
    sb.append('\n')
    sb.append("}\n")
    sb.toString
  }

  // ---- parsing (developer-facing only; reuses the job module's Json) ----

  /** Minimal hand-rolled parser sufficient for tests and developer tooling.
    * Reuses the [[com.transformer.job.Json]] helpers — this file is in the
    * `core/metrics` package which is below `job` in the deps DAG, so we
    * can't import `Json`. We re-implement just enough here to read what we
    * wrote.
    *
    * Failure mode: throws on malformed JSON or missing required fields; the
    * caller in [[read]] catches everything and returns None. */
  private[metrics] def parse(text: String): TaskMetricsRecord = {
    val v = JsonMini.parse(text).asObject
    val sv = v.field("schemaVersion").asLong.toInt
    val taskName = v.field("taskName").asString
    val executionTime = Instant.parse(v.field("executionTime").asString)
    val enqueuedAt = Instant.parse(v.field("enqueuedAt").asString)
    val startedAt = Instant.parse(v.field("startedAt").asString)
    val finishedAt = Instant.parse(v.field("finishedAt").asString)
    val cpuNanos = v.field("cpuNanos").asLong
    val allocBytes = v.field("allocBytes").asLong
    val gcDeltaCount = v.field("gcDeltaCount").asLong
    val gcDeltaNanos = v.field("gcDeltaNanos").asLong
    val query = v.optField("query").flatMap { jv =>
      if (jv.isNull) None else Some(parseQuery(jv.asObject))
    }
    TaskMetricsRecord(
      schemaVersion = sv,
      taskName = taskName,
      executionTime = executionTime,
      enqueuedAt = enqueuedAt,
      startedAt = startedAt,
      finishedAt = finishedAt,
      query = query,
      cpuNanos = cpuNanos,
      allocBytes = allocBytes,
      gcDeltaCount = gcDeltaCount,
      gcDeltaNanos = gcDeltaNanos
    )
  }

  private def parseQuery(o: JsonMini.JObj): QueryMetrics = QueryMetrics(
    parseNanos = o.field("parseNanos").asLong,
    optimizeNanos = o.field("optimizeNanos").asLong,
    physicalPlanNanos = o.field("physicalPlanNanos").asLong,
    executeNanos = o.field("executeNanos").asLong,
    operatorTree = parseOperator(o.field("operatorTree").asObject)
  )

  private def parseOperator(o: JsonMini.JObj): OperatorMetrics = {
    val counters = o.field("counters").asObject
    val (names, vals) = {
      val ns = scala.collection.mutable.ArrayBuffer.empty[String]
      val vs = scala.collection.mutable.ArrayBuffer.empty[Long]
      counters.fields.foreach { case (k, v) =>
        ns += k
        vs += v.asLong
      }
      (ns.toArray, vs.toArray)
    }
    val children = o.field("children").asArray.iterator
      .map(c => parseOperator(c.asObject))
      .toVector
    OperatorMetrics(
      id = o.field("id").asLong.toInt,
      name = o.field("name").asString,
      partition = o.field("partition").asLong.toInt,
      rowsIn = o.field("rowsIn").asLong,
      rowsOut = o.field("rowsOut").asLong,
      batchesIn = o.field("batchesIn").asLong,
      batchesOut = o.field("batchesOut").asLong,
      wallNanos = o.field("wallNanos").asLong,
      counters = vals,
      counterNames = names,
      children = children
    )
  }

  private def escape(s: String): String = {
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
