package com.transformer.job

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, Schema, SqlExecutor, SqlExecutorRegistry}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.{Executors, TimeUnit}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Top-level user-facing entry point. See README/example app for usage. */
final case class DataJob(
    inputs: Seq[InputFilePath],
    sql: Seq[SQLTask],
    temporalVariables: Option[TemporalVariables] = None,
    validationResultsOutput: Option[OutputFilePath] = None
) {

  def run(): JobResult = {
    // Touch the SQL engine to make sure it has self-registered.
    com.transformer.sql.exec.SqlEngine.init()
    run(SqlExecutorRegistry.get)
  }

  /** Run with an explicit executor — useful for tests, advanced wiring, or alternate engines. */
  def run(executor: SqlExecutor): JobResult = {
    val vars = temporalVariables.getOrElse(TemporalVariables())
    val catalog = new Catalog
    val taskResults = mutable.ArrayBuffer.empty[TaskResult]

    // Phase 1: load every input into the catalog in parallel.
    val pool = Executors.newFixedThreadPool(math.max(1, math.min(inputs.size, Runtime.getRuntime.availableProcessors)))
    try {
      val futures = inputs.map { in =>
        pool.submit(new java.util.concurrent.Callable[(String, CatalogView)] {
          def call(): (String, CatalogView) = {
            val rendered = in.copy(path = TemplateRenderer.render(in.path, vars))
            (in.viewName, InputResolver.resolve(rendered))
          }
        })
      }
      futures.foreach { f =>
        val (name, view) = f.get()
        catalog.register(name, view)
      }
    } catch {
      case NonFatal(e) =>
        pool.shutdownNow()
        return JobResult(succeeded = false, tasks = taskResults.toSeq, error = Some(s"Failed to load inputs: ${e.getMessage}"))
    } finally {
      pool.shutdown()
      pool.awaitTermination(1, TimeUnit.MINUTES)
    }

    val validationFailures = mutable.ArrayBuffer.empty[(String, Seq[ValidationFailure])]

    // Phase 2: execute SQL tasks sequentially.
    var aborted = false
    val sqlIter = sql.iterator
    while (sqlIter.hasNext && !aborted) {
      val task = sqlIter.next()
      val started = Instant.now()
      val taskName = task.displayName
      try {
        val renderedSql = TemplateRenderer.render(task.loadSql(), vars)
        val q = executor.execute(renderedSql, catalog)

        val outputPath: Option[String] = task.outputFile.map { ofp =>
          val rendered = TemplateRenderer.render(ofp.path, vars)
          if (ofp.isCloud) throw new UnsupportedOperationException(
            s"Cloud output paths not yet implemented (v1.1). Got: '${ofp.path}'"
          )
          writeOutput(ofp, rendered, q.schema, q.batches)
          rendered
        }

        // For validations we need the result registered as a view. If the user didn't
        // give it a viewName, we still publish under a synthetic one for validation.
        if (task.viewName.isDefined || task.validations.nonEmpty) {
          val name = task.viewName.getOrElse(s"__task_${taskResults.length}")
          val materialized = materializeIfNeeded(q.schema, outputPath)
          catalog.replace(name, materialized)
          val failures = runValidations(task, name, executor, catalog, vars)
          if (failures.nonEmpty) {
            validationFailures += (taskName -> failures)
            taskResults += TaskResult(taskName, TaskStatus.ValidationFailed(failures), q.rowsProduced, outputPath, started, Instant.now())
            aborted = true
          } else {
            taskResults += TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, Instant.now())
          }
        } else {
          // Drain any batches the writer didn't already consume.
          while (q.batches.hasNext) q.batches.next()
          taskResults += TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, Instant.now())
        }
      } catch {
        case NonFatal(e) =>
          taskResults += TaskResult(taskName, TaskStatus.Failed(e.getMessage), 0L, None, started, Instant.now())
          aborted = true
      }
    }

    if (validationFailures.nonEmpty) {
      writeValidationDiagnostics(validationFailures.toSeq, vars)
    }

    JobResult(
      succeeded = taskResults.forall(_.succeeded) && !aborted,
      tasks = taskResults.toSeq,
      error = None
    )
  }

  private def writeOutput(
      ofp: OutputFilePath, renderedPath: String, schema: Schema,
      batches: Iterator[ColumnarBatch]): Unit = {
    ofp.detectedFormat match {
      case "csv" =>
        val target = Paths.get(renderedPath)
        CsvWriter.writeAll(target, schema, batches, CsvWriteOptions.fromMap(ofp.options))
      case "parquet" =>
        val target = Paths.get(renderedPath)
        ParquetWriterHook.get match {
          case Some(fn) => fn(target, schema, batches, ofp.options)
          case None =>
            throw new UnsupportedOperationException(
              "Parquet output requires the parquet write module on the classpath."
            )
        }
      case other =>
        throw new IllegalArgumentException(s"Unsupported output format '$other' for '$renderedPath'")
    }
  }

  /** If the task already wrote to disk, register a re-readable view backed by the
    * just-written file (so validations work even on huge results). Otherwise, we
    * need to materialize the result in memory — which can fail for large queries.
    *
    * v1 always re-reads from the output file when available; otherwise an in-memory
    * cached view is built (small queries only).
    */
  private def materializeIfNeeded(schema: Schema, outputPath: Option[String]): CatalogView = {
    outputPath match {
      case Some(p) =>
        // Re-read the just-written file so validations can stream over it.
        val pathLower = p.toLowerCase
        if (pathLower.endsWith(".csv")) {
          com.transformer.read.csv.CsvReader.fromPath(
            p, com.transformer.read.csv.CsvOptions(inferSchema = false, columns = Some(schema.fields))
          )
        } else if (pathLower.endsWith(".parquet")) {
          ParquetReaderHook.get match {
            case Some(fn) => fn(p)
            case None => throw new UnsupportedOperationException(
              "Parquet validation re-read requires the parquet read module on the classpath."
            )
          }
        } else {
          throw new IllegalArgumentException(s"Cannot re-read output of unknown format: $p")
        }
      case None =>
        throw new UnsupportedOperationException(
          "v1 requires an outputFile to run validations or publish a SQLTask as a downstream view. " +
            "Set SQLTask.outputFile or remove validations/viewName."
        )
    }
  }

  private def runValidations(
      task: SQLTask,
      taskViewName: String,
      executor: SqlExecutor,
      catalog: Catalog,
      vars: TemporalVariables): Seq[ValidationFailure] = {
    task.validations.flatMap { v =>
      val sql = TemplateRenderer.render(v.loadSql(), vars)
      val q = executor.execute(sql, catalog)
      val rows = mutable.ArrayBuffer.empty[ColumnarBatch]
      while (q.batches.hasNext) rows += q.batches.next()
      val total = rows.iterator.map(_.numRows.toLong).sum
      if (total == 0L) None
      else {
        // Build a small CSV sample of the failing rows.
        val sample = sampleAsCsv(q.schema, rows.toSeq, maxRows = 10)
        Some(ValidationFailure(v.name, total, sample))
      }
    }
  }

  private def sampleAsCsv(schema: Schema, batches: Seq[ColumnarBatch], maxRows: Int): String = {
    val sb = new java.lang.StringBuilder()
    sb.append(schema.fieldNames.mkString(",")).append('\n')
    var emitted = 0
    val it = batches.iterator
    while (emitted < maxRows && it.hasNext) {
      val b = it.next()
      var r = 0
      while (r < b.numRows && emitted < maxRows) {
        var c = 0
        while (c < schema.length) {
          if (c > 0) sb.append(',')
          val v = b.column(c)
          if (!v.isNull(r)) sb.append(String.valueOf(v.getBoxed(r)))
          c += 1
        }
        sb.append('\n')
        emitted += 1
        r += 1
      }
    }
    sb.toString
  }

  private def writeValidationDiagnostics(failures: Seq[(String, Seq[ValidationFailure])], vars: TemporalVariables): Unit = {
    val outPath: Path = validationResultsOutput match {
      case Some(ofp) =>
        if (ofp.isCloud) {
          System.err.println(s"Cloud validation paths not supported in v1; ignoring '${ofp.path}'.")
          return
        }
        Paths.get(TemplateRenderer.render(ofp.path, vars))
      case None =>
        Paths.get(TemplateRenderer.render("validation_results/{{ epoch_millis }}.csv", vars))
    }
    if (outPath.getParent != null) Files.createDirectories(outPath.getParent)
    val sb = new java.lang.StringBuilder()
    sb.append("task,validation,row_count,sample\n")
    failures.foreach { case (taskName, vfs) =>
      vfs.foreach { vf =>
        sb.append(quote(taskName)).append(',')
        sb.append(quote(vf.validationName)).append(',')
        sb.append(vf.rowCount).append(',')
        sb.append(quote(vf.sampleRowsCsv)).append('\n')
      }
    }
    Files.writeString(outPath, sb.toString)
  }

  private def quote(s: String): String = "\"" + s.replace("\"", "\"\"") + "\""
}

/** Hooks the parquet write module installs into so DataJob doesn't pull parquet in
  * at compile time.
  */
object ParquetWriterHook {
  @volatile private var writer: Option[(Path, Schema, Iterator[ColumnarBatch], Map[String, String]) => Unit] = None
  def install(f: (Path, Schema, Iterator[ColumnarBatch], Map[String, String]) => Unit): Unit = {
    writer = Some(f)
  }
  def get: Option[(Path, Schema, Iterator[ColumnarBatch], Map[String, String]) => Unit] = writer
}

/** Hook installed by the parquet read module. Used to re-read just-written parquet
  * files for validations.
  */
object ParquetReaderHook {
  @volatile private var reader: Option[String => CatalogView] = None
  def install(f: String => CatalogView): Unit = { reader = Some(f) }
  def get: Option[String => CatalogView] = reader
}
