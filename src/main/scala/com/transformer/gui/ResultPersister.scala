package com.transformer.gui

import com.transformer.core.{ColumnarBatch, MaterializedView}
import com.transformer.job.{OutputDirectory, OutputFilePath, TaskRunRecord, TaskRunStatus}

import java.nio.file.Paths
import java.time.Instant
import scala.util.control.NonFatal

/** Writes a materialized interactive-SQL result to disk the way a job's
  * `SQLTask.outputFile` is written: through [[OutputDirectory]], so a
  * directory holding part files no record names is refused, a `Running`
  * record marks the directory before anything in it changes, and the parts
  * (CSV or Parquet) are staged and committed together.
  *
  * The result's partition layout drives output partitioning, capped by
  * [[OutputFilePath.maxPartitions]] just like the SQLTask runner does.
  *
  * A `Succeeded` [[TaskRunRecord]] naming the parts it committed is stamped
  * last, so the output shows up as a normal Succeeded run in the GUI's
  * historical-runs picker — wall-clock time stands in for `executionTime`
  * since interactive queries have no job-level temporal variables. A write
  * that fails leaves a `Failed` record and no part file, and its error is
  * rethrown.
  */
object ResultPersister {

  /** What the record names the run. */
  val TaskName: String = "interactive query"

  def persist(view: MaterializedView, cfg: PersistConfig): Long = {
    val ofp = OutputFilePath(
      path = cfg.outputDir,
      options = if (cfg.format == "csv") Map("header" -> cfg.csvHeader.toString) else Map.empty,
      format = Some(cfg.format),
      maxPartitions = cfg.maxPartitions
    )
    val out = new OutputDirectory(Paths.get(ofp.path))
    val started = Instant.now()
    def record(status: TaskRunStatus, files: Seq[String], rows: Long, error: Option[String]): TaskRunRecord = {
      val now = Instant.now()
      TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = TaskName,
        status = status,
        errorMessage = error,
        executionTime = started,
        startedAt = started,
        finishedAt = if (status == TaskRunStatus.Running) None else Some(now),
        writtenAt = now,
        rowsProduced = rows,
        format = ofp.detectedFormat,
        outputFiles = files,
        validations = Nil,
        options = ofp.options,
        schema = if (TaskRunStatus.wroteOutput(status)) Some(view.schema) else None
      )
    }
    try {
      out.open(record(TaskRunStatus.Running, Nil, 0L, None))
      val written = out.write(ofp.detectedFormat, view.schema, coalescedPartitions(view, ofp.maxPartitions), ofp.options)
      out.finish(record(TaskRunStatus.Succeeded, written.files, written.rows, None))
      written.rows
    } catch {
      case e: Throwable =>
        val message = Option(e.getMessage).getOrElse(e.toString)
        try out.fail(record(TaskRunStatus.Failed, Nil, 0L, Some(message))) catch { case NonFatal(_) => () }
        throw e
    }
  }

  /** Map the result's source partitions to at most `maxPartitions` output
    * partitions using contiguous chunking. Same semantics as
    * `DataJob.coalescedPartitions`.
    */
  private def coalescedPartitions(
      view: MaterializedView,
      maxPartitions: Option[Int]
  ): IndexedSeq[Iterator[ColumnarBatch]] = {
    val n = view.numPartitions
    if (n == 0) return IndexedSeq.empty
    val cap = maxPartitions.getOrElse(n)
    val k = math.max(1, math.min(cap, n))
    if (k == n) (0 until n).map(view.readPartition)
    else {
      val perGroup = (n + k - 1) / k
      (0 until k).map { g =>
        val from = g * perGroup
        val to = math.min(n, from + perGroup)
        (from until to).iterator.flatMap(p => view.readPartition(p))
      }
    }
  }
}
