package com.transformer.job

/** Description of one output target.
  *
  * `path` is templated against [[com.transformer.temporal.TemporalVariables]] at job
  * run time, so `output/day={{ today }}/result.csv` works directly. Cloud destinations
  * (`gs://`, `s3://`) are recognized but unsupported in v1.
  */
final case class OutputFilePath(
    path: String,
    options: Map[String, String] = Map.empty,
    format: Option[String] = None
) {
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
