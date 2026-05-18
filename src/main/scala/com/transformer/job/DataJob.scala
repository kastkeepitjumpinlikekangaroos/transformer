package com.transformer.job

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, ExecutedQuery, MaterializedView, Schema, SqlExecutor, SqlExecutorRegistry}
import com.transformer.read.parquet.ParquetReader
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.{Callable, Executors, LinkedBlockingQueue, TimeUnit}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Top-level user-facing entry point. See README/example app for usage.
  *
  * Output paths refer to *directories*. Each task writes one or more
  * `part-NNNNN.<ext>` files inside its directory (one per source partition by
  * default; capped by [[OutputFilePath.maxPartitions]] when set). Part files
  * are written in parallel.
  */
final case class DataJob(
    inputs: Seq[InputFilePath],
    sql: Seq[SQLTask],
    temporalVariables: Option[TemporalVariables] = None,
    validationResultsOutput: Option[OutputFilePath] = None
) {

  def run(): JobResult = {
    // Touch the SQL engine to make sure it has self-registered.
    com.transformer.sql.exec.SqlEngine.init()
    run(SqlExecutorRegistry.get, TaskProgressListener.NoOp)
  }

  def run(executor: SqlExecutor): JobResult = run(executor, TaskProgressListener.NoOp)

  /** Build (and validate) the SQLTask DAG without running any I/O.
    *
    * Useful for UI surfaces that want to visualize a job's structure before the user
    * presses Run. Returns the same [[TaskDag]] the runner builds internally — so the
    * same load-time errors (cycles, unknown refs, duplicate viewNames, duplicate
    * output paths, self-cycles) surface here as [[IllegalArgumentException]].
    */
  def buildDag(): TaskDag = {
    val vars = temporalVariables.getOrElse(TemporalVariables())
    val inputViewNames = inputs.iterator.map(_.viewName.toLowerCase).toSet
    TaskDag.build(sql, inputViewNames, vars)
  }

  /** Run with an explicit executor and progress listener.
    *
    * `listener` callbacks fire from the runner's worker threads — implementations that
    * touch UI state should marshal back to the UI thread themselves (e.g.
    * `Platform.runLater`).
    */
  def run(executor: SqlExecutor, listener: TaskProgressListener): JobResult = {
    val vars = temporalVariables.getOrElse(TemporalVariables())
    val catalog = new Catalog

    // Phase 1: resolve every input in parallel, then drain inputs marked
    // `cache = true` into memory on the same shared pool. Inputs marked
    // `cache = false` register their raw streaming CatalogView (e.g. the
    // ParquetReader itself) — each query re-reads from disk instead of
    // holding all decompressed data in heap. The pool is sized to all
    // available cores so independent (input, partition) reads saturate the
    // CPU even with a mix of small + large inputs.
    val cores = math.max(1, Runtime.getRuntime.availableProcessors)
    val inputPool = Executors.newFixedThreadPool(cores)
    try {
      // 1a: resolve in parallel (schema inference for CSV, footer reads for Parquet).
      val resolveFutures = inputs.map { in =>
        inputPool.submit(new Callable[(String, CatalogView)] {
          def call(): (String, CatalogView) = {
            val rendered = in.copy(path = TemplateRenderer.render(in.path, vars))
            (in.viewName, InputResolver.resolve(rendered))
          }
        })
      }
      val resolved: Seq[(String, CatalogView)] = resolveFutures.map(_.get())

      // 1b: drain `cache = true` inputs across every partition on the same pool —
      // one Callable per partition across every cached view, so the pool naturally
      // interleaves small and large inputs. `cache = false` inputs skip this step.
      val cachedPairs: Seq[(Int, CatalogView)] = inputs.iterator.zipWithIndex.collect {
        case (in, i) if in.cache => (i, resolved(i)._2)
      }.toSeq
      val cachedMaterialized: IndexedSeq[MaterializedView] =
        if (cachedPairs.isEmpty) IndexedSeq.empty
        else MaterializedView.materializeManyInParallel(cachedPairs.map(_._2), inputPool)
      val materializedByIdx: Map[Int, MaterializedView] =
        cachedPairs.iterator.map(_._1).zip(cachedMaterialized.iterator).toMap

      resolved.iterator.zipWithIndex.foreach { case ((name, raw), i) =>
        catalog.register(name, materializedByIdx.getOrElse(i, raw))
      }
    } catch {
      case NonFatal(e) =>
        inputPool.shutdownNow()
        val hint = oomHint(e)
        return JobResult(succeeded = false, tasks = Nil,
          error = Some(s"Failed to load inputs: ${e.getMessage}$hint"))
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
    val results = runDag(dag, executor, catalog, vars, listener)

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
      vars: TemporalVariables,
      listener: TaskProgressListener
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
                } else {
                  try listener.onTaskStart(i, dag.nodes(i).task.displayName)
                  catch { case _: Throwable => () }
                  runOneTask(dag.nodes(i), executor, catalog, vars)
                }
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
              try listener.onTaskFinish(i, results(i))
              catch { case _: Throwable => () }
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

      val writtenOutput: Option[(OutputFilePath, String)] = task.outputFile.map { ofp =>
        val rendered = TemplateRenderer.render(ofp.path, vars)
        if (ofp.isCloud) throw new UnsupportedOperationException(
          s"Cloud output paths not yet implemented (v1.1). Got: '${ofp.path}'"
        )
        writeOutput(ofp, rendered, q)
        (ofp, rendered)
      }

      if (task.viewName.isDefined || task.validations.nonEmpty) {
        val name = task.viewName.getOrElse(s"__task_${node.index}")
        val materialized = materializeIfNeeded(q.schema, writtenOutput)
        catalog.replace(name, materialized)
        val failures = runValidations(node, executor, catalog)
        val outputPath = writtenOutput.map(_._2)
        if (failures.nonEmpty)
          TaskResult(taskName, TaskStatus.ValidationFailed(failures), q.rowsProduced, outputPath, started, Instant.now())
        else {
          maybeWriteMarker(writtenOutput, q.rowsProduced, vars)
          TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, Instant.now())
        }
      } else {
        if (writtenOutput.isEmpty) {
          // No output, no downstream view: drain the result so any side-effects of
          // execution (and `rowsProduced`) still happen.
          while (q.batches.hasNext) q.batches.next()
        }
        maybeWriteMarker(writtenOutput, q.rowsProduced, vars)
        TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, writtenOutput.map(_._2), started, Instant.now())
      }
    } catch {
      case NonFatal(e) =>
        TaskResult(taskName, TaskStatus.Failed(e.getMessage), 0L, None, started, Instant.now())
    }
  }

  /** Stamp a [[RunMarker]] into the task's output directory iff the task wrote
    * one. Skipped silently if writing the marker itself fails — a missing
    * marker should never poison an otherwise-successful run.
    */
  private def maybeWriteMarker(
      writtenOutput: Option[(OutputFilePath, String)],
      rowsProduced: Long,
      vars: TemporalVariables
  ): Unit = writtenOutput.foreach { case (ofp, renderedPath) =>
    try {
      val dir = Paths.get(renderedPath)
      val files = RunMarker.listPartFiles(dir)
      RunMarker.write(dir, RunMarker(
        executionTime = vars.executionTime,
        writtenAt = Instant.now(),
        rowsProduced = rowsProduced,
        format = ofp.detectedFormat,
        outputFiles = files
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Drains `q` into a directory of part files at `renderedPath`. Parts are
    * coalesced down to [[OutputFilePath.maxPartitions]] if that's set and the
    * source produced more partitions than the cap.
    */
  private def writeOutput(
      ofp: OutputFilePath, renderedPath: String, q: ExecutedQuery): Unit = {
    val parts = coalescedPartitions(q, ofp.maxPartitions)
    val dir = Paths.get(renderedPath)
    // If a prior successful run is stamped in this directory, wipe its files
    // first so a format / partition-count change doesn't leave stale outputs
    // that break the next reader (e.g. `.csv` files inside a parquet dir).
    RunMarker.clearIfMarked(dir)
    ofp.detectedFormat match {
      case "csv" =>
        CsvWriter.writePartitioned(dir, q.schema, parts, CsvWriteOptions.fromMap(ofp.options))
        ()
      case "parquet" =>
        TParquetWriter.writePartitioned(dir, q.schema, parts, ofp.options)
        ()
      case other =>
        throw new IllegalArgumentException(s"Unsupported output format '$other' for '$renderedPath'")
    }
  }

  /** Map the executor's `numPartitions` source partitions to at most
    * `maxPartitions` output partitions using contiguous chunking. With
    * `maxPartitions = None` (default) we use one output partition per source
    * partition. `maxPartitions = Some(k)` with k >= numPartitions is a no-op
    * (we never inflate beyond the natural partitioning).
    */
  private def coalescedPartitions(
      q: ExecutedQuery,
      maxPartitions: Option[Int]
  ): IndexedSeq[Iterator[ColumnarBatch]] = {
    val n = q.numPartitions
    if (n == 0) return IndexedSeq.empty
    val cap = maxPartitions.getOrElse(n)
    val k = math.max(1, math.min(cap, n))
    if (k == n) (0 until n).map(q.partition)
    else {
      val perGroup = (n + k - 1) / k
      (0 until k).map { g =>
        val from = g * perGroup
        val to = math.min(n, from + perGroup)
        (from until to).iterator.flatMap(p => q.partition(p))
      }
    }
  }

  /** Re-read the just-written output as a view so validations and downstream
    * tasks can stream over it. Both CSV and Parquet readers treat a bare
    * directory as "every file inside", which is exactly the part-file layout
    * we produce.
    */
  private def materializeIfNeeded(schema: Schema, writtenOutput: Option[(OutputFilePath, String)]): CatalogView = {
    writtenOutput match {
      case Some((ofp, dir)) =>
        ofp.detectedFormat match {
          case "csv" =>
            val opts = CsvWriteOptions.fromMap(ofp.options)
            com.transformer.read.csv.CsvReader.fromPath(
              dir,
              com.transformer.read.csv.CsvOptions(
                inferSchema = false,
                columns = Some(schema.fields),
                header = opts.header,
                delimiter = opts.delimiter,
                quote = opts.quote,
                nullValue = opts.nullValue,
                charset = opts.charset
              )
            )
          case "parquet" =>
            ParquetReader.fromPath(dir)
          case other =>
            throw new IllegalArgumentException(s"Cannot re-read output of unknown format '$other': $dir")
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

  /** Detect an OOM anywhere in the cause chain and append a hint pointing at the
    * `cache: false` escape hatch. Empty string when the failure isn't memory-related.
    *
    * Why: input materialization runs futures on a pool, so a real OOM in the
    * drain loop comes back as `ExecutionException` whose cause is the OOM —
    * `getMessage` alone hides what actually went wrong.
    */
  private def oomHint(t: Throwable): String = {
    var cur: Throwable = t
    while (cur != null) {
      if (cur.isInstanceOf[OutOfMemoryError]) {
        return "\nHint: an input is too large to cache in memory. Set `cache: false` " +
          "on the input (in its config.json, or via InputFilePath(cache = false)) so it streams " +
          "from disk on each query instead of being fully materialized."
      }
      cur = cur.getCause
    }
    ""
  }
}

