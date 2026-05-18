package com.transformer.gui

import com.transformer.core.{ColumnarBatch, MaterializedView}
import com.transformer.job.{OutputFilePath, TaskRunRecord, TaskRunStatus}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}

import java.nio.file.Paths
import java.time.Instant
import scala.util.control.NonFatal

/** Writes a materialized interactive-SQL result to disk via the same library
  * machinery a real `SQLTask.outputFile` would use:
  *
  *   - CSV: [[CsvWriter.writePartitioned]] (atomic temp+rename per part file).
  *   - Parquet: [[TParquetWriter.writePartitioned]].
  *
  * The result's partition layout drives output partitioning, capped by
  * [[OutputFilePath.maxPartitions]] just like the SQLTask runner does.
  *
  * On success a [[TaskRunRecord]] is stamped into the directory so the
  * output shows up as a normal Succeeded run in the GUI's historical-runs
  * picker — wall-clock time stands in for `executionTime` since interactive
  * queries have no job-level temporal variables.
  */
object ResultPersister {

  def persist(view: MaterializedView, cfg: PersistConfig): Long = {
    val ofp = OutputFilePath(
      path = cfg.outputDir,
      options = if (cfg.format == "csv") Map("header" -> cfg.csvHeader.toString) else Map.empty,
      format = Some(cfg.format),
      maxPartitions = cfg.maxPartitions
    )
    val coalesced = coalescedPartitions(view, ofp.maxPartitions)
    val dir = Paths.get(ofp.path)
    // Wipe a prior run's files so a format / partition-count change doesn't
    // leave stale outputs in the directory.
    TaskRunRecord.clearIfMarked(dir)
    val started = Instant.now()
    val rowsWritten = ofp.detectedFormat match {
      case "csv" =>
        CsvWriter.writePartitioned(
          dir, view.schema, coalesced, CsvWriteOptions.fromMap(ofp.options)
        )
      case "parquet" =>
        TParquetWriter.writePartitioned(dir, view.schema, coalesced, ofp.options)
      case other =>
        throw new IllegalArgumentException(s"Unsupported output format '$other' for '${ofp.path}'")
    }
    writeRunRecord(ofp, rowsWritten, started)
    rowsWritten
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

  private def writeRunRecord(ofp: OutputFilePath, rowsProduced: Long, started: Instant): Unit = try {
    val dir = Paths.get(ofp.path)
    val files = TaskRunRecord.listPartFiles(dir)
    val now = Instant.now()
    TaskRunRecord.write(dir, TaskRunRecord(
      schemaVersion = TaskRunRecord.SchemaVersion,
      taskName = "interactive query",
      status = TaskRunStatus.Succeeded,
      errorMessage = None,
      executionTime = now,
      startedAt = started,
      finishedAt = now,
      writtenAt = now,
      rowsProduced = rowsProduced,
      format = ofp.detectedFormat,
      outputFiles = files,
      validations = Nil
    ))
  } catch { case NonFatal(_) => () }
}
