package com.transformer.job

import com.transformer.core.ExecutionOptions
import com.transformer.write.csv.CsvWriteOptions
import com.transformer.write.parquet.ParquetWriter

import java.util.Locale

/** Description of one output target.
  *
  * `path` is always interpreted as a *directory* — the job writes one or more
  * `part-NNNNN.<ext>` files into it. This matches Spark / Hive conventions and
  * lets downstream readers parallelize across files. `path` is templated against
  * [[com.transformer.temporal.TemporalVariables]] at job run time, so
  * `output/day={{ today }}/result` works directly.
  *
  *   - With no `maxPartitions` set (default), one part file is written per source
  *     partition (one per input CSV / one per Parquet row group, etc.). Files are
  *     written in parallel.
  *   - With `maxPartitions = Some(k)`, source partitions are coalesced into at
  *     most `k` part files using contiguous chunking. `k = 1` collapses
  *     everything into a single part file (the closest thing to the old single-
  *     file behaviour).
  *
  * Cloud destinations (`gs://`, `s3://`) are recognized but unsupported in v1.
  *
  * `options` holds the writer's options and the task's execution options
  * ([[ExecutionOptions.Keys]]). A format other than csv or parquet, an option
  * neither reads and a value that is none of its option's are errors when
  * the output is declared, where each used to be ignored or fall back to a
  * default (plans/robustness/27).
  */
final case class OutputFilePath(
    path: String,
    options: Map[String, String] = Map.empty,
    format: Option[String] = None,
    maxPartitions: Option[Int] = None
) {
  if (maxPartitions.exists(_ < 1))
    throw new IllegalArgumentException(
      s"OutputFilePath.maxPartitions must be >= 1 if set; got ${maxPartitions.get}"
    )
  OutputFilePath.check(this)

  def detectedFormat: String = format.map(_.toLowerCase(Locale.ROOT)).getOrElse {
    val lower = path.toLowerCase(Locale.ROOT)
    if (lower.endsWith(".parquet")) "parquet"
    else if (lower.endsWith(".csv")) "csv"
    else if (lower.contains(".parquet")) "parquet"
    else if (lower.contains(".csv")) "csv"
    else "csv"
  }

  def isCloud: Boolean = path.startsWith("gs://") || path.startsWith("s3://")
}

object OutputFilePath {

  /** The keys of a table's `output.json`. */
  val ConfigKeys: Seq[String] = Seq("partitionBy", "format", "maxPartitions", "options")

  /** The option keys an output of `format` reads: its writer's and the
    * task's execution options. */
  def optionKeys(format: String): Seq[String] =
    (if (format == "parquet") ParquetWriter.Keys else CsvWriteOptions.Keys) ++ ExecutionOptions.Keys

  private def check(out: OutputFilePath): Unit = {
    InputFilePath.checkFormat(out.format, "output")
    val format = out.detectedFormat
    InputFilePath.refuseUnknown(out.options, optionKeys(format), s"a $format output")
    if (format == "parquet") ParquetWriter.checkOptions(out.options) else CsvWriteOptions.fromMap(out.options)
    ExecutionOptions.fromOutputOptions(out.options)
    ()
  }
}
