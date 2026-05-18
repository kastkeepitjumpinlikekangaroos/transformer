package com.transformer.job

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, ExecutedQuery, MaterializedView, Schema, SqlExecutor, SqlExecutorRegistry}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}

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
    jobRunOutput: Option[OutputFilePath] = None
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
    val runStarted = Instant.now()
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
    val runFinished = Instant.now()

    val warnings = runConsistencyChecks(results.toSeq)
    val succeeded = results.forall(_.succeeded)
    writeJobRecord(results.toSeq, vars, runStarted, runFinished, succeeded, None, warnings)

    JobResult(
      succeeded = succeeded,
      tasks = results.toSeq,
      error = None,
      warnings = warnings
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
                  val reason = s"upstream failure: $names"
                  val now = Instant.now()
                  val node = dag.nodes(i)
                  writeSkippedRecord(node, reason, now, vars)
                  TaskResult(node.task.displayName,
                    TaskStatus.Skipped(reason),
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
                val node = dag.nodes(i)
                val msg = s"orchestration error: ${t.getMessage}"
                writeFailedRecord(node, msg, now, now, vars)
                results(i) = TaskResult(
                  node.task.displayName,
                  TaskStatus.Failed(msg),
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
    // Track the rendered output path even on failure so we can stamp the
    // _run.json into the same directory the data would have landed in.
    var renderedOutputForRecord: Option[(OutputFilePath, String)] = None
    try {
      val q = executor.execute(node.renderedMainSql, catalog)

      val writtenOutput: Option[(OutputFilePath, String)] = task.outputFile.map { ofp =>
        if (ofp.isCloud) throw new UnsupportedOperationException(
          s"Cloud output paths not yet implemented (v1.1). Got: '${ofp.path}'"
        )
        val rendered = TemplateRenderer.render(ofp.path, vars)
        renderedOutputForRecord = Some((ofp, rendered))
        writeOutput(ofp, rendered, q)
        (ofp, rendered)
      }

      if (task.viewName.isDefined || task.validations.nonEmpty) {
        val name = task.viewName.getOrElse(s"__task_${node.index}")
        val materialized = materializeIfNeeded(q.schema, writtenOutput)
        catalog.replace(name, materialized)
        val failures = runValidations(node, executor, catalog)
        val outputPath = writtenOutput.map(_._2)
        val finished = Instant.now()
        if (failures.nonEmpty) {
          writeValidationFailedRecord(node, writtenOutput, q.rowsProduced, failures, started, finished, vars)
          TaskResult(taskName, TaskStatus.ValidationFailed(failures), q.rowsProduced, outputPath, started, finished)
        } else {
          writeSucceededRecord(node, writtenOutput, q.rowsProduced, started, finished, vars)
          TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, finished)
        }
      } else {
        if (writtenOutput.isEmpty) {
          // No output, no downstream view: drain the result so any side-effects of
          // execution (and `rowsProduced`) still happen.
          while (q.batches.hasNext) q.batches.next()
        }
        val finished = Instant.now()
        writeSucceededRecord(node, writtenOutput, q.rowsProduced, started, finished, vars)
        TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, writtenOutput.map(_._2), started, finished)
      }
    } catch {
      case NonFatal(e) =>
        val finished = Instant.now()
        val msg = Option(e.getMessage).getOrElse(e.toString)
        writeFailedRecord(node, msg, started, finished, vars)
        TaskResult(taskName, TaskStatus.Failed(msg), 0L, None, started, finished)
    }
  }

  /** Stamp a Succeeded [[TaskRunRecord]] when the task ran cleanly. Silently
    * swallows write failures — a missing record should never poison an
    * otherwise-successful run. */
  private def writeSucceededRecord(
      node: TaskDagNode,
      writtenOutput: Option[(OutputFilePath, String)],
      rowsProduced: Long,
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): Unit = writtenOutput.foreach { case (ofp, renderedPath) =>
    try {
      val dir = Paths.get(renderedPath)
      val files = TaskRunRecord.listPartFiles(dir)
      val validations = node.task.validations.map { v =>
        ValidationRecord(name = v.name, passed = true, failedRowCount = 0L, sampleFile = None)
      }
      TaskRunRecord.write(dir, TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = node.task.displayName,
        status = TaskRunStatus.Succeeded,
        errorMessage = None,
        executionTime = vars.executionTime,
        startedAt = startedAt,
        finishedAt = finishedAt,
        writtenAt = Instant.now(),
        rowsProduced = rowsProduced,
        format = ofp.detectedFormat,
        outputFiles = files,
        validations = validations
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Stamp a ValidationFailed [[TaskRunRecord]] alongside per-validation
    * `_validation-<slug>.csv` sample files. Part files are left on disk — the
    * data was successfully produced, only the integrity checks disagreed.
    */
  private def writeValidationFailedRecord(
      node: TaskDagNode,
      writtenOutput: Option[(OutputFilePath, String)],
      rowsProduced: Long,
      failures: Seq[ValidationFailure],
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): Unit = writtenOutput.foreach { case (ofp, renderedPath) =>
    try {
      val dir = Paths.get(renderedPath)
      val files = TaskRunRecord.listPartFiles(dir)
      val failureByName = failures.iterator.map(f => f.validationName -> f).toMap
      val validations = node.task.validations.map { v =>
        failureByName.get(v.name) match {
          case Some(f) =>
            val sampleName =
              try Some(TaskRunRecord.writeValidationSample(dir, v.name, f.sampleRowsCsv))
              catch { case NonFatal(_) => None }
            ValidationRecord(
              name = v.name,
              passed = false,
              failedRowCount = f.rowCount,
              sampleFile = sampleName
            )
          case None =>
            ValidationRecord(name = v.name, passed = true, failedRowCount = 0L, sampleFile = None)
        }
      }
      TaskRunRecord.write(dir, TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = node.task.displayName,
        status = TaskRunStatus.ValidationFailed,
        errorMessage = None,
        executionTime = vars.executionTime,
        startedAt = startedAt,
        finishedAt = finishedAt,
        writtenAt = Instant.now(),
        rowsProduced = rowsProduced,
        format = ofp.detectedFormat,
        outputFiles = files,
        validations = validations
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Stamp a Failed [[TaskRunRecord]] for a task that threw during execution.
    * Renders the task's outputFile path on a best-effort basis — if rendering
    * itself throws, the failure is recorded only in [[JobRunRecord]]. */
  private def writeFailedRecord(
      node: TaskDagNode,
      errorMessage: String,
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): Unit = node.task.outputFile.foreach { ofp =>
    if (ofp.isCloud) return
    try {
      val rendered = TemplateRenderer.render(ofp.path, vars)
      val dir = Paths.get(rendered)
      // Wipe any prior data so the directory's state matches "tried, failed".
      TaskRunRecord.clearIfMarked(dir)
      TaskRunRecord.write(dir, TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = node.task.displayName,
        status = TaskRunStatus.Failed,
        errorMessage = Some(errorMessage),
        executionTime = vars.executionTime,
        startedAt = startedAt,
        finishedAt = finishedAt,
        writtenAt = Instant.now(),
        rowsProduced = 0L,
        format = ofp.detectedFormat,
        outputFiles = Nil,
        validations = node.task.validations.map(v =>
          ValidationRecord(v.name, passed = false, failedRowCount = 0L, sampleFile = None)
        )
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Stamp a Skipped [[TaskRunRecord]] for a task whose upstream failed. */
  private def writeSkippedRecord(
      node: TaskDagNode,
      reason: String,
      now: Instant,
      vars: TemporalVariables
  ): Unit = node.task.outputFile.foreach { ofp =>
    if (ofp.isCloud) return
    try {
      val rendered = TemplateRenderer.render(ofp.path, vars)
      val dir = Paths.get(rendered)
      TaskRunRecord.clearIfMarked(dir)
      TaskRunRecord.write(dir, TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = node.task.displayName,
        status = TaskRunStatus.Skipped,
        errorMessage = Some(reason),
        executionTime = vars.executionTime,
        startedAt = now,
        finishedAt = now,
        writtenAt = Instant.now(),
        rowsProduced = 0L,
        format = ofp.detectedFormat,
        outputFiles = Nil,
        validations = node.task.validations.map(v =>
          ValidationRecord(v.name, passed = false, failedRowCount = 0L, sampleFile = None)
        )
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
    // If a prior run is recorded in this directory, wipe its files first so a
    // format / partition-count / status change doesn't leave stale outputs
    // (e.g. `.csv` files inside a parquet dir, or a stale Failed marker
    // beside fresh data).
    TaskRunRecord.clearIfMarked(dir)
    ofp.detectedFormat match {
      case "csv" =>
        CsvWriter.writePartitioned(dir, q.schema, parts, CsvWriteOptions.fromMap(ofp.options))
        ()
      case "parquet" =>
        ParquetWriterHook.get match {
          case Some(fn) => fn(dir, q.schema, parts, ofp.options)
          case None =>
            throw new UnsupportedOperationException(
              "Parquet output requires the parquet write module on the classpath."
            )
        }
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
            ParquetReaderHook.get match {
              case Some(fn) => fn(dir)
              case None => throw new UnsupportedOperationException(
                "Parquet validation re-read requires the parquet read module on the classpath."
              )
            }
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

  /** Build the per-job [[JobRunRecord]] and atomically write it to
    * `jobRunOutput` (when configured). Each task's full record lives in its
    * own output directory; this file aggregates them with a `runFile`
    * pointer per task so the GUI can load one file to find everything. */
  private def writeJobRecord(
      results: Seq[TaskResult],
      vars: TemporalVariables,
      startedAt: Instant,
      finishedAt: Instant,
      succeeded: Boolean,
      errorMessage: Option[String],
      warnings: Seq[String]
  ): Unit = jobRunOutput.foreach { ofp =>
    if (ofp.isCloud) {
      System.err.println(s"Cloud job-run paths not supported in v1; ignoring '${ofp.path}'.")
      return
    }
    try {
      val target = Paths.get(TemplateRenderer.render(ofp.path, vars))
      val summaries = results.map { r =>
        val (status, errMsg) = r.status match {
          case TaskStatus.Succeeded               => (TaskRunStatus.Succeeded,        None)
          case TaskStatus.ValidationFailed(_)     => (TaskRunStatus.ValidationFailed, None)
          case TaskStatus.Failed(msg)             => (TaskRunStatus.Failed,           Some(msg))
          case TaskStatus.Skipped(reason)         => (TaskRunStatus.Skipped,          Some(reason))
          case TaskStatus.Pending                 => (TaskRunStatus.Failed,           Some("never ran"))
        }
        val failedValidationCount = r.status match {
          case TaskStatus.ValidationFailed(fs) => fs.size
          case _                               => 0
        }
        val runFile = r.outputPath.map(p => Paths.get(p).resolve(TaskRunRecord.FileName).toString)
        JobTaskSummary(
          taskName = r.taskName,
          status = status,
          runFile = runFile,
          rowsProduced = r.rowsProduced,
          failedValidationCount = failedValidationCount,
          errorMessage = errMsg
        )
      }
      JobRunRecord.write(target, JobRunRecord(
        schemaVersion = JobRunRecord.SchemaVersion,
        succeeded = succeeded,
        errorMessage = errorMessage,
        executionTime = vars.executionTime,
        startedAt = startedAt,
        finishedAt = finishedAt,
        tasks = summaries,
        warnings = warnings
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Compare each task's in-memory [[TaskResult]] to the on-disk state and
    * emit a human-readable warning per drift. These are non-fatal —
    * surfaced in the GUI's run-log panel and persisted in
    * [[JobRunRecord.warnings]] so the user notices, but the run still
    * "succeeded" if every task's status is Succeeded. */
  private def runConsistencyChecks(results: Seq[TaskResult]): Seq[String] = {
    val warnings = mutable.ArrayBuffer.empty[String]
    results.foreach { r =>
      r.outputPath.foreach { outPath =>
        val dir = Paths.get(outPath)
        TaskRunRecord.read(dir) match {
          case None =>
            // We just wrote a record for every terminal-status task with an
            // outputFile; not seeing one here means a write failed.
            warnings += s"task '${r.taskName}': missing _run.json in $outPath"
          case Some(rec) =>
            rec.outputFiles.foreach { f =>
              if (!Files.isRegularFile(dir.resolve(f)))
                warnings += s"task '${r.taskName}': declared part file missing on disk: $f"
            }
            rec.validations.foreach { v =>
              v.sampleFile.foreach { sf =>
                if (!Files.isRegularFile(dir.resolve(sf)))
                  warnings += s"task '${r.taskName}': declared validation sample missing: $sf"
              }
            }
        }
      }
    }
    warnings.toSeq
  }

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

/** Hooks the parquet write module installs into so DataJob doesn't pull parquet in
  * at compile time. The hook receives the *output directory* and the executor's
  * per-partition iterators (already coalesced to OutputFilePath.maxPartitions).
  */
object ParquetWriterHook {
  @volatile private var writer: Option[(Path, Schema, IndexedSeq[Iterator[ColumnarBatch]], Map[String, String]) => Long] = None
  def install(f: (Path, Schema, IndexedSeq[Iterator[ColumnarBatch]], Map[String, String]) => Long): Unit = {
    writer = Some(f)
  }
  def get: Option[(Path, Schema, IndexedSeq[Iterator[ColumnarBatch]], Map[String, String]) => Long] = writer
}

/** Hook installed by the parquet read module. Used to re-read just-written parquet
  * directories for validations.
  */
object ParquetReaderHook {
  @volatile private var reader: Option[String => CatalogView] = None
  def install(f: String => CatalogView): Unit = { reader = Some(f) }
  def get: Option[String => CatalogView] = reader
}
