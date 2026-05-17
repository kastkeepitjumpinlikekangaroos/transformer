package com.transformer.read.parquet

import com.transformer.core.{CatalogView, ColumnarBatch, Schema}
import com.transformer.job.{InputFilePath, ParquetReaderHook, ParquetResolverHook, ParquetWriterHook}
import com.transformer.read.csv.PathGlob
import com.transformer.write.parquet.{ParquetWriter => TWriter}

import java.nio.file.Path

/** Wires Parquet read+write into the [[ParquetResolverHook]], [[ParquetReaderHook]],
  * and [[ParquetWriterHook]] so the job module can use parquet without compile-time
  * dependency on the parquet libraries.
  *
  * `init()` is idempotent and self-runs on class load.
  */
object ParquetSupport {

  def init(): Unit = {
    ParquetResolverHook.install(resolve)
    ParquetReaderHook.install(reReadOutput)
    ParquetWriterHook.install(writeBatches)
  }

  init()

  private def resolve(input: InputFilePath): CatalogView = {
    if (input.isCloud) throw new UnsupportedOperationException(
      s"Cloud Parquet paths not implemented in v1 (got '${input.path}')"
    )
    ParquetReader.fromPath(input.path)
  }

  private def reReadOutput(path: String): CatalogView = ParquetReader.fromPath(path)

  private def writeBatches(target: Path, schema: Schema, batches: Iterator[ColumnarBatch],
                           options: Map[String, String]): Unit = {
    TWriter.writeAll(target, schema, batches, options)
    ()
  }
}
