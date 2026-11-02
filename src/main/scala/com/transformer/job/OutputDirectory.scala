package com.transformer.job

import com.transformer.core.{ColumnarBatch, Schema, WrittenParts}
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
  * A run that fails after step 2 deletes what it wrote, staged and committed,
  * then writes its `Failed` record ([[fail]]), so a failed task's directory
  * holds its record and nothing else. A run that dies after step 2 leaves the
  * `Running` record, and the next run's step 3 deletes whatever it left.
  *
  * A run that writes no output — a task skipped, or one that failed before
  * step 2 — replaces the record first and only then deletes the earlier
  * run's files ([[replace]]). Nothing deletes a file a record names before
  * the record is replaced.
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
    TaskRunRecord.write(dir, running)
    marked = true
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

  /** Step 7: `record`, the run's final record, replaces `Running`. A reader
    * outside the run trusts the directory only through it, so a run whose
    * record cannot be written did not finish for any reader: this throws,
    * naming the write. */
  def finish(record: TaskRunRecord): Unit =
    try TaskRunRecord.write(dir, record)
    catch {
      case NonFatal(e) =>
        throw new IOException(
          s"Could not write ${dir.resolve(TaskRunRecord.FileName)}, which records the run: " +
            Option(e.getMessage).getOrElse(e.toString), e)
    }

  /** The run failed. Once it has marked the directory, everything it wrote
    * there goes, staged and committed, and then `record`, its `Failed`
    * record, replaces `Running`; a failure while deleting leaves `Running`,
    * which the next run clears. A run that failed before marking writes
    * `record` as [[replace]] does. */
  def fail(record: TaskRunRecord): Unit =
    if (!marked) replace(record)
    else {
      TaskRunRecord.clear(dir)
      TaskRunRecord.write(dir, record)
    }

  /** A record for a run that wrote no output: `record` replaces the earlier
    * record, and then the earlier run's files are deleted. In a directory of
    * part files no record names this writes nothing, as a record there would
    * make those files the job's to delete. */
  def replace(record: TaskRunRecord): Unit =
    if (TaskRunRecord.unrecordedPartFiles(dir).isEmpty) {
      TaskRunRecord.write(dir, record)
      marked = true
      TaskRunRecord.clear(dir)
    }
}
