package com.transformer.read.csv

import com.transformer.core.{ColumnarBatch, Field, OptionValues, Schema}

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
) {
  CsvOptions.checkSeparators(delimiter, quote, escape, nullValue)
}

object CsvOptions {
  /** Parse a flat string-keyed option bag as supplied through `InputFilePath.options`. */
  /** The option keys a CSV input reads ([[fromMap]]). */
  val Keys: Seq[String] = Seq("inferSchema", "header", "delimiter", "quote", "escape", "nullValue", "charset")

  /** The options [[Keys]] name in `raw`, each read strictly ([[OptionValues]]);
    * a key it does not read is the caller's to refuse, since an output's map,
    * read back by [[forWrittenFile]], carries its writer's keys too. */
  def fromMap(raw: Map[String, String]): CsvOptions = {
    def bool(key: String, default: Boolean): Boolean = raw.get(key).map(OptionValues.bool(key, _)).getOrElse(default)
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
      charset = raw.get("charset").map(OptionValues.charset("charset", _)).getOrElse(StandardCharsets.UTF_8),
      columns = None
    )
  }

  /** How to read back a file `CsvWriter` wrote from `schema` under the option
    * bag `raw`, the one its `CsvWriteOptions.fromMap` read: every option the
    * writer honours, under the same keys, and the writer's schema rather
    * than one inferred from the text, so each value reads back as the value
    * it was. */
  def forWrittenFile(raw: Map[String, String], schema: Schema): CsvOptions =
    fromMap(raw).copy(inferSchema = false, columns = Some(schema.fields))

  /** The separators a CSV file can be read and written with: a delimiter,
    * quote and escape that are not line breaks and that a field can tell
    * apart, and a null string that a written NULL can stand as unquoted
    * without being split, quoted or ended. */
  def checkSeparators(delimiter: Char, quote: Char, escape: Char, nullValue: String): Unit = {
    def show(c: Char): String = c match {
      case '\t' => "a tab"
      case '\n' | '\r' => "a line break"
      case other => s"'$other'"
    }
    for ((name, c) <- Seq("delimiter" -> delimiter, "quote" -> quote, "escape" -> escape))
      if (c == '\n' || c == '\r') throw new IllegalArgumentException(s"CSV option '$name' cannot be a line break")
    if (delimiter == quote) throw new IllegalArgumentException(
      s"CSV options 'delimiter' and 'quote' are both ${show(delimiter)}")
    if (delimiter == escape) throw new IllegalArgumentException(
      s"CSV options 'delimiter' and 'escape' are both ${show(delimiter)}")
    if (nullValue.exists(c => c == delimiter || c == '\n' || c == '\r') || nullValue.headOption.contains(quote))
      throw new IllegalArgumentException(
        s"CSV option 'nullValue' ('$nullValue') cannot hold the delimiter or a line break, or start with the quote")
  }
}
