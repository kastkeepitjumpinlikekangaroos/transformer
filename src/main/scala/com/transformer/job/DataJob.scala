package com.transformer.job

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, Schema, SqlExecutor, SqlExecutorRegistry}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.{Callable, Executors, LinkedBlockingQueue, TimeUnit}
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

    // Phase 1: load every input into the catalog in parallel.
    val inputPool = Executors.newFixedThreadPool(math.max(1, math.min(inputs.size, Runtime.getRuntime.availableProcessors)))
    try {
      val futures = inputs.map { in =>
        inputPool.submit(new Callable[(String, CatalogView)] {
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
        inputPool.shutdownNow()
        return JobResult(succeeded = false, tasks = Nil, error = Some(s"Failed to load inputs: ${e.getMessage}"))
    } finally {
      inputPool.shutdown()
      inputPool.awaitTermination(1, TimeUnit.MINUTES)
    }

    // Phase 2: build a DAG from each task's SQL (main + validations) and execute it
    // in parallel — independent branches run concurrently; downstream tasks of a failed
    // task are Skipped; independent siblings keep going.
    val dag =
      try TaskDag.build(sql, catalog.viewNames.iterator.map(_.toLowerCase).toSet, vars)
      catch {
        case NonFatal(e) =>
          return JobResult(succeeded = false, tasks = Nil, error = Some(e.getMessage))
      }
    val results = runDag(dag, executor, catalog, vars)

    val validationFailures: Seq[(String, Seq[ValidationFailure])] =
      results.iterator.collect {
        case TaskResult(name, TaskStatus.ValidationFailed(fs), _, _, _, _) => name -> fs
      }.toSeq
    if (validationFailures.nonEmpty) writeValidationDiagnostics(validationFailures, vars)

    JobResult(
      succeeded = results.forall(_.succeeded),
      tasks = results.toSeq,
      error = None
    )
  }

  private def runDag(
      dag: TaskDag,
      executor: SqlExecutor,
      catalog: Catalog,
      vars: TemporalVariables
  ): Array[TaskResult] = {
    val n = dag.nodes.size
    val results = Array.ofDim[TaskResult](n)
    if (n == 0) return results
    val inDegree = Array.tabulate(n)(i => dag.nodes(i).deps.size)
    val completed = new LinkedBlockingQueue[Int]()
    val taskPool = Executors.newFixedThreadPool(
      math.max(1, math.min(n, Runtime.getRuntime.availableProcessors))
    )
    try {
      def submit(i: Int): Unit = {
        taskPool.submit(new Callable[Void] {
          def call(): Void = {
            try {
              val depResults = dag.nodes(i).deps.iterator.map(results).toSeq
              val failedDeps = depResults.filter(_.status != TaskStatus.Succeeded)
              results(i) =
                if (failedDeps.nonEmpty) {
                  val names = failedDeps.map(_.taskName).mkString(", ")
                  val now = Instant.now()
                  TaskResult(dag.nodes(i).task.displayName,
                    TaskStatus.Skipped(s"upstream failure: $names"),
                    0L, None, now, now)
                } else runOneTask(dag.nodes(i), executor, catalog, vars)
            } catch {
              case t: Throwable =>
                // Defensive — runOneTask catches NonFatal itself. This only fires on
                // Fatal or an impl bug; convert to Failed so the scheduler can never
                // deadlock waiting on a worker that swallowed its own exception.
                val now = Instant.now()
                results(i) = TaskResult(
                  dag.nodes(i).task.displayName,
                  TaskStatus.Failed(s"orchestration error: ${t.getMessage}"),
                  0L, None, now, now
                )
            } finally {
              completed.put(i)
            }
            null
          }
        })
        ()
      }
      var i = 0
      while (i < n) {
        if (inDegree(i) == 0) submit(i)
        i += 1
      }
      var done = 0
      while (done < n) {
        val finished = completed.take()
        done += 1
        dag.dependents(finished).foreach { j =>
          inDegree(j) -= 1
          if (inDegree(j) == 0) submit(j)
        }
      }
    } finally {
      taskPool.shutdown()
      taskPool.awaitTermination(1, TimeUnit.MINUTES)
    }
    results
  }

  private def runOneTask(
      node: TaskDagNode,
      executor: SqlExecutor,
      catalog: Catalog,
      vars: TemporalVariables
  ): TaskResult = {
    val task = node.task
    val started = Instant.now()
    val taskName = task.displayName
    try {
      val q = executor.execute(node.renderedMainSql, catalog)

      val outputPath: Option[String] = task.outputFile.map { ofp =>
        val rendered = TemplateRenderer.render(ofp.path, vars)
        if (ofp.isCloud) throw new UnsupportedOperationException(
          s"Cloud output paths not yet implemented (v1.1). Got: '${ofp.path}'"
        )
        writeOutput(ofp, rendered, q.schema, q.batches)
        rendered
      }

      if (task.viewName.isDefined || task.validations.nonEmpty) {
        val name = task.viewName.getOrElse(s"__task_${node.index}")
        val materialized = materializeIfNeeded(q.schema, outputPath)
        catalog.replace(name, materialized)
        val failures = runValidations(node, executor, catalog)
        if (failures.nonEmpty)
          TaskResult(taskName, TaskStatus.ValidationFailed(failures), q.rowsProduced, outputPath, started, Instant.now())
        else
          TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, Instant.now())
      } else {
        while (q.batches.hasNext) q.batches.next()
        TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, Instant.now())
      }
    } catch {
      case NonFatal(e) =>
        TaskResult(taskName, TaskStatus.Failed(e.getMessage), 0L, None, started, Instant.now())
    }
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
      node: TaskDagNode,
      executor: SqlExecutor,
      catalog: Catalog): Seq[ValidationFailure] = {
    node.task.validations.iterator.zip(node.renderedValidationSqls.iterator).flatMap { case (v, sql) =>
      val q = executor.execute(sql, catalog)
      val rows = mutable.ArrayBuffer.empty[ColumnarBatch]
      while (q.batches.hasNext) rows += q.batches.next()
      val total = rows.iterator.map(_.numRows.toLong).sum
      if (total == 0L) None
      else Some(ValidationFailure(v.name, total, sampleAsCsv(q.schema, rows.toSeq, maxRows = 10)))
    }.toSeq
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
