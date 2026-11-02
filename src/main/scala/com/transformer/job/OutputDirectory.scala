package com.transformer.job

import com.transformer.core.{ColumnarBatch, Faults, Schema, WrittenParts}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}

import java.io.IOException
import java.nio.file.Path
import scala.util.control.NonFatal

/** One run's write of an output directory, in the order every writer of one
  * follows: a job's task (`DataJob`) and the GUI's saved query result
  * (`gui.ResultPersister`). The record is the commit: a reader trusts the
  * directory only when its `_run.json` says `Succeeded` or `ValidationFailed`,
  * and reads exactly the part files it names.
  *
  *   1. '''Check''' ([[open]]). A directory holding part files and no
  *      `_run.json` is refused: they are someone else's.
  *   2. '''Mark.''' A `Running` record replaces the earlier record in one
  *      rename. From then on the directory is the job's, whatever the run
  *      leaves in it, and the next run clears it.
  *   3. '''Clear.''' The earlier run's files go, all but the record
  *      ([[TaskRunRecord.clear]]).
  *   4. '''Stage''' ([[write]]). Each partition writes its part under a
  *      hidden temp name.
  *   5. '''Commit.''' Once every partition has succeeded, the parts are
  *      renamed to `part-NNNNN.*`, in partition order.
  *   6. The caller reads the output back and validates it.
  *   7. '''Record''' ([[finish]]). The final record replaces `Running`: the
  *      run's last write in the directory. One that cannot be written fails
  *      the run.
  *
  * A run that ends without output ([[settle]]) — it failed, or its task was
  * skipped — leaves its record and nothing else: once the directory is
  * marked, whatever the run wrote there goes, staged or committed, then its
  * `Failed` or `Skipped` record replaces `Running`. One that had not marked it
  * yet marks it first, as step 2 does, then clears the earlier run's files.
  * So a run that dies after step 2, or while it settles, leaves the `Running`
  * record, and the next run's step 3 deletes whatever is left; and nothing
  * deletes a file a record names before the record is replaced.
  */
final class OutputDirectory(val dir: Path) {

  private var marked = false

  /** Steps 1 to 3: refuse a directory of part files no record names, mark it
    * with `running`, a `Running` record, and delete the earlier run's files. */
  def open(running: TaskRunRecord): Unit = {
    val stray = TaskRunRecord.unrecordedPartFiles(dir)
    if (stray.nonEmpty) throw new IllegalArgumentException(
      s"Output directory '$dir' already holds ${stray.mkString(", ")}, which no run record names, so a reader " +
        "of the directory would read them as part of this output; delete them or write to another directory")
    mark(running)
    TaskRunRecord.clear(dir)
  }

  /** Steps 4 and 5: stage each of `partitions` as a part file in `format`
    * and commit them together. Returns the part files committed, which the
    * final record names. */
  def write(
      format: String,
      schema: Schema,
      partitions: IndexedSeq[Iterator[ColumnarBatch]],
      options: Map[String, String]): WrittenParts =
    format match {
      case "csv" => CsvWriter.writePartitioned(dir, schema, partitions, CsvWriteOptions.fromMap(options))
      case "parquet" => TParquetWriter.writePartitioned(dir, schema, partitions, options)
      case other => throw new IllegalArgumentException(s"Unsupported output format '$other' for '$dir'")
    }

  /** Step 7: `last`, the run's final record, replaces `Running`. A reader
    * outside the run trusts the directory only through it, so a run whose
    * record cannot be written did not finish for any reader: this throws,
    * naming the write. */
  def finish(last: TaskRunRecord): Unit =
    try record(last)
    catch {
      case NonFatal(e) =>
        throw new IOException(
          s"Could not write ${dir.resolve(TaskRunRecord.FileName)}, which records the run: " +
            Option(e.getMessage).getOrElse(e.toString), e)
    }

  /** The run ends without output, with `last`, its `Failed` or `Skipped`
    * record. Unmarked, the directory is marked `Running` first, from `last`;
    * then every file a run writes there but the record goes, this run's
    * staged and committed parts and an earlier run's alike, and `last`
    * replaces `Running`. A failure part way leaves `Running`, which the next
    * run clears. In a directory of part files no record names this writes
    * nothing, as a record there would make those files the job's to delete. */
  def settle(last: TaskRunRecord): Unit = {
    if (!marked) {
      if (TaskRunRecord.unrecordedPartFiles(dir).nonEmpty) return
      mark(last.copy(status = TaskRunStatus.Running, errorMessage = None, finishedAt = None, rowsProduced = 0L,
        outputFiles = Nil, validations = Nil, schema = None))
    }
    TaskRunRecord.clear(dir)
    record(last)
  }

  /** Step 2: `running` replaces the directory's record. */
  private def mark(running: TaskRunRecord): Unit = {
    record(running)
    marked = true
  }

  /** Write `r` as the directory's record: a fault point of its own for each
    * status ([[Faults]]). */
  private def record(r: TaskRunRecord): Unit = {
    Faults.at(r.status match {
      case TaskRunStatus.Running => Faults.RunningRecord
      case TaskRunStatus.Succeeded | TaskRunStatus.ValidationFailed => Faults.FinalRecord
      case TaskRunStatus.Failed => Faults.FailedRecord
      case TaskRunStatus.Skipped => Faults.SkippedRecord
    })
    TaskRunRecord.write(dir, r)
  }
}
