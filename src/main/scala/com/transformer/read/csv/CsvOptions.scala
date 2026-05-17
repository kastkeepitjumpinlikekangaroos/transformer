package com.transformer.read.csv

import com.transformer.core.{ColumnarBatch, Field}

import java.nio.charset.{Charset, StandardCharsets}

/** Configuration for [[CsvReader]].
  *
  * Defaults follow RFC 4180: comma delimiter, double-quote, doubled-quote escape,
  * header on first line, empty field = null.
  *
  * `columns` is required when `inferSchema` is `false`.
  */
final case class CsvOptions(
    inferSchema: Boolean = true,
    header: Boolean = true,
    delimiter: Char = ',',
    quote: Char = '"',
    escape: Char = '"',
    nullValue: String = "",
    charset: Charset = StandardCharsets.UTF_8,
    columns: Option[Seq[Field]] = None,
    inferenceSampleRows: Int = 1000,
    batchSize: Int = ColumnarBatch.DefaultCapacity
)

object CsvOptions {
  /** Parse a flat string-keyed option bag as supplied through `InputFilePath.options`. */
  def fromMap(raw: Map[String, String]): CsvOptions = {
    def bool(key: String, default: Boolean): Boolean =
      raw.get(key).map(_.trim.toLowerCase) match {
        case None | Some("") => default
        case Some("true") | Some("1") | Some("yes") => true
        case Some("false") | Some("0") | Some("no") => false
        case Some(other) =>
          throw new IllegalArgumentException(s"Invalid boolean for option '$key': '$other'")
      }
    def chr(key: String, default: Char): Char = raw.get(key) match {
      case None => default
      case Some(s) if s.length == 1 => s.charAt(0)
      case Some(s) if s == "\\t" => '\t'
      case Some(other) =>
        throw new IllegalArgumentException(s"Option '$key' must be a single character, got: '$other'")
    }
    CsvOptions(
      inferSchema = bool("inferSchema", default = true),
      header = bool("header", default = true),
      delimiter = chr("delimiter", default = ','),
      quote = chr("quote", default = '"'),
      escape = chr("escape", default = '"'),
      nullValue = raw.getOrElse("nullValue", ""),
      charset = raw.get("charset").map(Charset.forName).getOrElse(StandardCharsets.UTF_8),
      columns = None
    )
  }
}
