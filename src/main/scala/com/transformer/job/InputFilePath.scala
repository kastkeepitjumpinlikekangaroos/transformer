package com.transformer.job

import com.transformer.core.OptionValues
import com.transformer.read.csv.CsvOptions

import java.util.Locale

/** Description of one input source.
  *
  * `path` may be a literal file, a directory, or a glob. Cloud paths (`gs://`, `s3://`)
  * are recognized but unsupported in v1 — they raise on job run.
  *
  * `options` is a flat string-keyed bag; semantics depend on the file format.
  * For CSV see [[com.transformer.read.csv.CsvOptions.fromMap]]. A format
  * other than csv or parquet, an option the format does not read and a value
  * that is none of its option's are errors when the input is declared, from
  * a directory job's config file, from code or from the GUI: each used to be
  * ignored, or to fall back to a default (plans/robustness/27).
  */
final case class InputFilePath(
    path: String,
    viewName: String,
    options: Map[String, String] = Map.empty,
    cache: Boolean = true,
    format: Option[String] = None
) {
  InputFilePath.check(this)

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

object InputFilePath {

  /** The keys of an input's config file; its directory gives `viewName`. */
  val ConfigKeys: Seq[String] = Seq("path", "options", "cache", "format")

  /** The formats an input is read as and an output written as. */
  val Formats: Seq[String] = Seq("csv", "parquet")

  /** A parquet input's target bytes per partition. */
  val ParquetPartitionSizeKey: String = "read_partition_size_bytes"

  /** The option keys an input of `format` reads. */
  def optionKeys(format: String): Seq[String] =
    if (format == "parquet") Seq(ParquetPartitionSizeKey) else CsvOptions.Keys

  /** `format`, when it names one of [[Formats]]; `role` says whose. */
  private[job] def checkFormat(format: Option[String], role: String): Unit =
    format.foreach { f =>
      if (!Formats.contains(f.toLowerCase(Locale.ROOT))) throw new IllegalArgumentException(
        s"Unsupported format '$f'; an $role is ${Formats.mkString(" or ")}")
    }

  /** An option `accepted` does not hold, named with the keys it does. */
  private[job] def refuseUnknown(options: Map[String, String], accepted: Seq[String], whose: String): Unit =
    OptionValues.unknown(options, accepted).headOption.foreach { key =>
      throw new IllegalArgumentException(s"Unknown option '$key' for $whose, which reads ${accepted.mkString(", ")}")
    }

  private def check(in: InputFilePath): Unit = {
    checkFormat(in.format, "input")
    val format = in.detectedFormat
    refuseUnknown(in.options, optionKeys(format), s"a $format input")
    if (format == "parquet") in.options.get(ParquetPartitionSizeKey).foreach(OptionValues.positiveLong(ParquetPartitionSizeKey, _))
    else { val _ = CsvOptions.fromMap(in.options) }
  }
}
