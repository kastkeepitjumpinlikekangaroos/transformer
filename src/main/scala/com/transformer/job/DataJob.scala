package com.transformer.job

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, ExecutedQuery, MaterializedView, Scheduler, Schema, SqlExecutor, SqlExecutorRegistry}
import com.transformer.read.parquet.ParquetReader
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}
import com.transformer.write.csv.{CsvWriteOptions, CsvWriter}
import com.transformer.write.parquet.{ParquetWriter => TParquetWriter}

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.{Callable, LinkedBlockingQueue}
import scala.collection.mutable
import scala.util.control.NonFatal

/** Top-level user-facing entry point. See README/example app for usage.
  *
  * Output paths refer to *directories*. Each task writes one or more
  * `part-NNNNN.<ext>` files inside its directory (one per source partition by
  * default; capped by [[OutputFilePath.maxPartitions]] when set). Part files
  * are written in parallel.
  *
  * Scheduling model: inputs and SQL tasks are scheduled together on a single
  * shared [[com.transformer.core.Scheduler]] work-stealing pool. Each input
  * load is a top-level node; each SQL task waits for its referenced input
  * loads and any upstream task results before it becomes eligible. Independent
  * branches (e.g. `raw_features → stg_features → ...` and
  * `raw_orderbook → stg_orderbook → ...`) run concurrently from the very first
  * worker tick — there is no pre-task barrier where every input must finish
  * loading before any SQL runs.
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

    val inputViewNames = inputs.iterator.map(_.viewName.toLowerCase).toSet
    val dag =
      try TaskDag.build(sql, inputViewNames, vars)
      catch {
        case NonFatal(e) =>
          return JobResult(succeeded = false, tasks = Nil, error = Some(e.getMessage))
      }

    val results = runUnifiedDag(dag, executor, catalog, vars, listener)
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

  /** Unified completion-driven scheduler over inputs + SQL tasks.
    *
    * Lifecycle of one task:
    *   1. Initial state: `pendingTaskDeps(i)` + `pendingInputDeps(i)` count what's
    *      still outstanding.
    *   2. When an input load completes (success OR failure), every SQL task that
    *      referenced it sees its `pendingInputDeps` decremented.
    *   3. When a task completes, every dependent's `pendingTaskDeps` decrements.
    *   4. A task with both counts at 0 fires `onTaskEnqueued`, captures
    *      `enqueuedAt`, and submits to the pool. The worker stamps `startedAt`
    *      when it picks the task up — so queue-wait time is observable.
    *   5. If any upstream (input or task) failed, the task is marked Skipped
    *      immediately when it becomes "ready" — no work is submitted to the pool
    *      for it.
    *
    * The main scheduler thread is the calling thread; workers complete and push
    * events onto a [[LinkedBlockingQueue]] which the scheduler drains in order.
    * That gives us serialized state updates without locks while keeping the work
    * itself fully parallel on the shared pool.
    */
  private def runUnifiedDag(
      dag: TaskDag,
      executor: SqlExecutor,
      catalog: Catalog,
      vars: TemporalVariables,
      listener: TaskProgressListener
  ): Array[TaskResult] = {
    val nTasks = dag.nodes.size
    val taskResults = Array.ofDim[TaskResult](nTasks)

    // ---- Input-dep bookkeeping ---------------------------------------------
    // For each lowercased input name → set of task indices that consume it.
    // Mutating these sets is single-threaded (only the scheduler loop touches them).
    val taskDependents: Array[mutable.Set[Int]] =
      Array.fill(nTasks)(mutable.Set.empty[Int])
    dag.nodes.iterator.foreach { node =>
      node.deps.foreach(d => taskDependents(d) += node.index)
    }

    val pendingTaskDeps = Array.tabulate(nTasks)(i => dag.nodes(i).deps.size)
    val pendingInputDeps = Array.tabulate(nTasks)(i => dag.nodes(i).inputDeps.size)
    // Once any of a task's upstreams (task or input) fails, this flips true and
    // the task is Skipped when it becomes ready. We carry the failure reason
    // through so the recorded "Skipped" record names the actual failing upstream.
    val upstreamFailed = Array.fill(nTasks)(false)
    val upstreamFailReason = Array.fill[Option[String]](nTasks)(None)

    val inputDependents: Map[String, Vector[Int]] = {
      val builder = mutable.Map.empty[String, mutable.ArrayBuffer[Int]]
      dag.nodes.iterator.foreach { node =>
        node.inputDeps.foreach { name =>
          builder.getOrElseUpdate(name, mutable.ArrayBuffer.empty[Int]) += node.index
        }
      }
      builder.iterator.map { case (k, v) => k -> v.toVector }.toMap
    }

    // ---- Event queue --------------------------------------------------------
    sealed trait Event
    final case class InputCompleted(name: String, success: Boolean, error: Option[String]) extends Event
    final case class TaskCompleted(index: Int, result: TaskResult) extends Event

    val events = new LinkedBlockingQueue[Event]()

    // ---- Input loading ------------------------------------------------------
    val nInputs = inputs.size

    // Submit one Callable per input. The input load resolves the path, optionally
    // materializes for cache=true, registers the view in the catalog, and posts
    // an `InputCompleted` event onto the scheduler queue.
    inputs.iterator.zipWithIndex.foreach { case (in, i) =>
      val key = in.viewName.toLowerCase
      Scheduler.submit(new Callable[Unit] {
        def call(): Unit = {
          try listener.onInputStart(i, in.viewName) catch { case _: Throwable => () }
          val (success, error) =
            try {
              val rendered = in.copy(path = TemplateRenderer.render(in.path, vars))
              val raw = InputResolver.resolve(rendered)
              val view: CatalogView =
                if (in.cache) MaterializedView.materializeInParallel(raw)
                else raw
              catalog.register(in.viewName, view)
              (true, None)
            } catch {
              case NonFatal(e) =>
                val hint = oomHint(e)
                (false, Some(s"failed to load input '${in.viewName}': ${e.getMessage}$hint"))
            }
          try listener.onInputFinish(i, in.viewName, success, error)
          catch { case _: Throwable => () }
          events.put(InputCompleted(key, success, error))
        }
      })
    }

    // ---- Task submission ----------------------------------------------------
    def maybeSubmit(i: Int): Unit = {
      if (pendingTaskDeps(i) == 0 && pendingInputDeps(i) == 0 && taskResults(i) == null) {
        val node = dag.nodes(i)
        val enqueuedAt = Instant.now()
        if (upstreamFailed(i)) {
          val reason = upstreamFailReason(i).getOrElse("upstream failure")
          writeSkippedRecord(node, reason, enqueuedAt, vars)
          val tr = TaskResult(
            node.task.displayName, TaskStatus.Skipped(reason), 0L, None,
            startedAt = enqueuedAt, finishedAt = enqueuedAt, enqueuedAt = enqueuedAt
          )
          taskResults(i) = tr
          try listener.onTaskFinish(i, tr) catch { case _: Throwable => () }
          // Propagate Skipped to downstream
          taskDependents(i).foreach { d =>
            if (!upstreamFailed(d)) {
              upstreamFailed(d) = true
              upstreamFailReason(d) = Some(s"upstream skipped: ${node.task.displayName}")
            }
            pendingTaskDeps(d) -= 1
            maybeSubmit(d)
          }
        } else {
          try listener.onTaskEnqueued(i, node.task.displayName)
          catch { case _: Throwable => () }
          val needsView = taskDependents(i).nonEmpty
          Scheduler.submit(new Callable[Unit] {
            def call(): Unit = {
              try listener.onTaskStart(i, node.task.displayName)
              catch { case _: Throwable => () }
              val result = runOneTask(node, executor, catalog, vars, enqueuedAt, needsView)
              try listener.onTaskFinish(i, result) catch { case _: Throwable => () }
              events.put(TaskCompleted(i, result))
            }
          })
        }
      }
    }

    def handleInputCompletion(name: String, success: Boolean, error: Option[String]): Unit = {
      inputDependents.getOrElse(name, Vector.empty).foreach { i =>
        pendingInputDeps(i) -= 1
        if (!success && !upstreamFailed(i)) {
          upstreamFailed(i) = true
          upstreamFailReason(i) = error.orElse(Some(s"upstream input '$name' failed"))
        }
        maybeSubmit(i)
      }
    }

    def handleTaskCompletion(i: Int, result: TaskResult): Unit = {
      taskDependents(i).foreach { d =>
        if (!result.succeeded && !upstreamFailed(d)) {
          upstreamFailed(d) = true
          upstreamFailReason(d) = Some(s"upstream ${describeUpstreamFailure(result)}: ${result.taskName}")
        }
        pendingTaskDeps(d) -= 1
        maybeSubmit(d)
      }
    }

    // Kick off any tasks that had zero deps from the start (no task-deps AND no
    // input-deps). Such tasks are rare but possible — e.g. a task with a literal
    // SELECT or a UNION of constants — and the loop below assumes the initial
    // ready set has already been kicked.
    var i = 0
    while (i < nTasks) {
      maybeSubmit(i)
      i += 1
    }

    // ---- Drain events until every input + task is resolved ------------------
    var remainingInputs = nInputs
    var remainingTasks = nTasks - taskResults.count(_ != null)
    while (remainingInputs > 0 || remainingTasks > 0) {
      events.take() match {
        case InputCompleted(name, success, error) =>
          remainingInputs -= 1
          handleInputCompletion(name, success, error)
        case TaskCompleted(idx, result) =>
          taskResults(idx) = result
          remainingTasks -= 1
          handleTaskCompletion(idx, result)
      }
      // The early-skip path inside maybeSubmit may have synchronously filled
      // taskResults for transitively-skipped descendants; recount.
      remainingTasks = nTasks - taskResults.count(_ != null)
    }

    taskResults
  }

  private def describeUpstreamFailure(result: TaskResult): String = result.status match {
    case TaskStatus.Failed(_)           => "failed"
    case TaskStatus.ValidationFailed(_) => "validation failed"
    case TaskStatus.Skipped(_)          => "skipped"
    case _                              => "unsuccessful"
  }

  private def runOneTask(
      node: TaskDagNode,
      executor: SqlExecutor,
      catalog: Catalog,
      vars: TemporalVariables,
      enqueuedAt: Instant,
      hasDownstreamConsumers: Boolean
  ): TaskResult = {
    val task = node.task
    val started = Instant.now()
    val taskName = task.displayName
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

      // Re-publish the freshly-written output to the catalog ONLY when something
      // downstream still needs to read it: a DAG dependent task, or this task's
      // own validations. A task with `viewName` declared but no consumers and no
      // validations doesn't need the parquet/CSV re-read — we'd just be re-opening
      // footers and discarding the view. For the polymarket workload that re-read
      // dominates the per-task tail latency on the leaf mart_* tables.
      val needsCatalogView = hasDownstreamConsumers || task.validations.nonEmpty
      if (needsCatalogView) {
        val name = task.viewName.getOrElse(s"__task_${node.index}")
        val materialized = materializeIfNeeded(q.schema, writtenOutput)
        catalog.replace(name, materialized)
        val failures = runValidations(node, executor, catalog)
        val outputPath = writtenOutput.map(_._2)
        val finished = Instant.now()
        if (failures.nonEmpty) {
          writeValidationFailedRecord(node, writtenOutput, q.rowsProduced, failures, enqueuedAt, started, finished, vars)
          TaskResult(taskName, TaskStatus.ValidationFailed(failures), q.rowsProduced, outputPath, started, finished, enqueuedAt)
        } else {
          writeSucceededRecord(node, writtenOutput, q.rowsProduced, enqueuedAt, started, finished, vars)
          TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, outputPath, started, finished, enqueuedAt)
        }
      } else {
        if (writtenOutput.isEmpty) {
          while (q.batches.hasNext) q.batches.next()
        }
        val finished = Instant.now()
        writeSucceededRecord(node, writtenOutput, q.rowsProduced, enqueuedAt, started, finished, vars)
        TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, writtenOutput.map(_._2), started, finished, enqueuedAt)
      }
    } catch {
      case NonFatal(e) =>
        val finished = Instant.now()
        val msg = Option(e.getMessage).getOrElse(e.toString)
        writeFailedRecord(node, msg, enqueuedAt, started, finished, vars)
        TaskResult(taskName, TaskStatus.Failed(msg), 0L, None, started, finished, enqueuedAt)
    }
  }

  /** Stamp a Succeeded [[TaskRunRecord]] when the task ran cleanly. Silently
    * swallows write failures — a missing record should never poison an
    * otherwise-successful run. */
  private def writeSucceededRecord(
      node: TaskDagNode,
      writtenOutput: Option[(OutputFilePath, String)],
      rowsProduced: Long,
      enqueuedAt: Instant,
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
        validations = validations,
        enqueuedAt = Some(enqueuedAt)
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
      enqueuedAt: Instant,
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
        validations = validations,
        enqueuedAt = Some(enqueuedAt)
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Stamp a Failed [[TaskRunRecord]] for a task that threw during execution.
    * Renders the task's outputFile path on a best-effort basis — if rendering
    * itself throws, the failure is recorded only in [[JobRunRecord]]. */
  private def writeFailedRecord(
      node: TaskDagNode,
      errorMessage: String,
      enqueuedAt: Instant,
      startedAt: Instant,
      finishedAt: Instant,
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
        ),
        enqueuedAt = Some(enqueuedAt)
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
        ),
        enqueuedAt = Some(now)
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
    TaskRunRecord.clearIfMarked(dir)
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
    * Why: input materialization runs on a shared pool, so a real OOM in the drain
    * loop comes back wrapped in `ExecutionException` whose cause is the OOM —
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
