package com.transformer.job

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

  def detectedFormat: String = format.map(_.toLowerCase).getOrElse {
    val lower = path.toLowerCase
    if (lower.endsWith(".parquet")) "parquet"
    else if (lower.endsWith(".csv")) "csv"
    else if (lower.contains(".parquet")) "parquet"
    else if (lower.contains(".csv")) "csv"
    else "csv"
  }

  def isCloud: Boolean = path.startsWith("gs://") || path.startsWith("s3://")
}
