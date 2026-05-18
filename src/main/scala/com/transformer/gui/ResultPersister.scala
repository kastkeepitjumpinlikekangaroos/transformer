package com.transformer.gui

import com.transformer.core.{ColumnarBatch, MaterializedView}
import com.transformer.job.{OutputFilePath, RunMarker}
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
  * On success a [[RunMarker]] is stamped into the directory so the output
  * shows up as a normal "run" in the GUI's historical-runs picker — using
  * the wall-clock time as `executionTime` since interactive queries have no
  * job-level temporal variables.
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
    // Wipe a prior successful run's files so a format / partition-count change
    // doesn't leave stale outputs in the directory.
    RunMarker.clearIfMarked(dir)
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
    writeMarker(ofp, rowsWritten)
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

  private def writeMarker(ofp: OutputFilePath, rowsProduced: Long): Unit = try {
    val dir = Paths.get(ofp.path)
    val files = RunMarker.listPartFiles(dir)
    val now = Instant.now()
    RunMarker.write(dir, RunMarker(
      executionTime = now,
      writtenAt = now,
      rowsProduced = rowsProduced,
      format = ofp.detectedFormat,
      outputFiles = files
    ))
  } catch { case NonFatal(_) => () }
}
