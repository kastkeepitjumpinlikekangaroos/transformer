package com.transformer.job

import com.transformer.core.CatalogView
import com.transformer.read.csv.{CsvOptions, CsvReader}
import com.transformer.read.parquet.ParquetReader

/** Resolves an [[InputFilePath]] into a concrete [[CatalogView]] backed by a reader. */
object InputResolver {

  def resolve(input: InputFilePath): CatalogView = {
    if (input.isCloud) {
      throw new UnsupportedOperationException(
        s"Cloud paths are not yet implemented (v1.1). Got: '${input.path}'. " +
          s"For v1, download files locally first or use a local path."
      )
    }
    input.detectedFormat match {
      case "csv" =>
        CsvReader.fromPath(input.path, CsvOptions.fromMap(input.options))
      case "parquet" =>
        ParquetReader.fromPath(input.path)
      case other =>
        throw new IllegalArgumentException(
          s"Unsupported format '$other' for path '${input.path}'. Supported: csv, parquet."
        )
    }
  }
}
