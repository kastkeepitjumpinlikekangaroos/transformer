package com.transformer.job

import java.util.Locale

/** Description of one input source.
  *
  * `path` may be a literal file, a directory, or a glob. Cloud paths (`gs://`, `s3://`)
  * are recognized but unsupported in v1 — they raise on job run.
  *
  * `options` is a flat string-keyed bag; semantics depend on the file format.
  * For CSV see [[com.transformer.read.csv.CsvOptions.fromMap]].
  */
final case class InputFilePath(
    path: String,
    viewName: String,
    options: Map[String, String] = Map.empty,
    cache: Boolean = true,
    format: Option[String] = None
) {
  def detectedFormat: String = format.map(_.toLowerCase(Locale.ROOT)).getOrElse {
    val lower = path.toLowerCase(Locale.ROOT)
    if (lower.endsWith(".parquet")) "parquet"
    else if (lower.endsWith(".csv")) "csv"
    else if (lower.contains(".parquet")) "parquet"  // matches globs like *.parquet
    else if (lower.contains(".csv")) "csv"
    else "csv" // pragmatic default; user can override with `format`
  }

  def isCloud: Boolean = path.startsWith("gs://") || path.startsWith("s3://")
}
