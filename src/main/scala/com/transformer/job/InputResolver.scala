package com.transformer.job

import com.transformer.core.CatalogView
import com.transformer.read.csv.{CsvOptions, CsvReader}

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
        // Hook for the Parquet reader, wired in Phase 12.
        ParquetResolverHook
          .resolve(input)
          .getOrElse(throw new UnsupportedOperationException(
            "Parquet support is not wired up yet. Add the parquet module to the job's classpath."
          ))
      case other =>
        throw new IllegalArgumentException(
          s"Unsupported format '$other' for path '${input.path}'. Supported: csv, parquet."
        )
    }
  }
}

/** Optional Parquet hook so the job module does not depend on the parquet module
  * directly. Phase 12 installs a real resolver via [[ParquetResolverHook.install]].
  */
object ParquetResolverHook {
  @volatile private var resolver: Option[InputFilePath => CatalogView] = None

  def install(r: InputFilePath => CatalogView): Unit = {
    resolver = Some(r)
  }

  def resolve(input: InputFilePath): Option[CatalogView] = resolver.map(_(input))
}
