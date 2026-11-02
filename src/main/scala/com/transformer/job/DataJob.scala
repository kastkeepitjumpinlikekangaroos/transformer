package com.transformer.job

import com.transformer.core.{Catalog, CatalogView, ColumnarBatch, ExecutedQuery, ExecutionOptions, MaterializedView, Scheduler, Schema, SqlExecutor, SqlExecutorRegistry}
import com.transformer.core.metrics.{MetricsCollector, TaskMetricsRecord}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}

import java.nio.file.{Files, Paths}
import java.time.Instant
import java.util.Locale
import java.util.concurrent.{Callable, LinkedBlockingQueue}
import java.util.concurrent.atomic.AtomicReference
import scala.collection.mutable
import scala.util.control.NonFatal

/** Top-level user-facing entry point. See README/example app for usage.
  *
  * Output paths refer to *directories*. Each task writes one or more
  * `part-NNNNN.<ext>` files inside its directory (one per source partition by
  * default; capped by [[OutputFilePath.maxPartitions]] when set). Part files
  * are written in parallel under hidden names and renamed together once all
  * are written, between a `Running` record and the task's final one, in the
  * order [[OutputDirectory]] sets out.
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
  import DataJob.TaskRun

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
    val inputViewNames = inputs.iterator.map(_.viewName.toLowerCase(Locale.ROOT)).toSet
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

    val inputViewNames = inputs.iterator.map(_.viewName.toLowerCase(Locale.ROOT)).toSet
    val dag =
      try TaskDag.build(sql, inputViewNames, vars)
      catch {
        case NonFatal(e) =>
          return JobResult(succeeded = false, tasks = Nil, error = Some(e.getMessage))
      }

    val (results, inputFailures, stopped) = runUnifiedDag(dag, executor, catalog, vars, listener)
    val runFinished = Instant.now()

    val warnings = inputWarnings(dag, inputFailures) ++ runConsistencyChecks(results.toSeq)
    // The tasks alone decide the verdict: a warning never fails a run. A run
    // that stopped has a task that failed it.
    val succeeded = results.forall(_.succeeded)
    writeJobRecord(dag, results.toSeq, vars, runStarted, runFinished, succeeded, stopped, warnings)

    JobResult(
      succeeded = succeeded,
      tasks = results.toSeq,
      error = stopped,
      warnings = warnings
    )
  }

  /** Unified completion-driven scheduler over inputs + SQL tasks.
    *
    * Lifecycle of one task:
    *   1. Initial state: `pendingTaskDeps(i)` + `pendingInputDeps(i)` count what's
    *      still outstanding.
    *   2. When an input load completes (success OR failure), every SQL task that
    *      referenced it sees its `pendingInputDeps` decremented. A failure is
    *      also kept for the run's warnings, whether or not any task referenced
    *      the input.
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
    * itself fully parallel on the shared pool. The loop waits for one event per
    * input and per task, so each worker posts its event whatever its work
    * threw: an `Error` that passed it by used to leave the loop waiting for
    * ever, with nothing logged.
    *
    * A task that fails as the JVM does ([[stopsTheJob]]: out of memory, not a
    * stack overflow) stops the run: no task starts after it, each that has not
    * started is Skipped saying why, and the run reports the error. The tasks
    * already running finish.
    *
    * Returns a result per task, in declared order; per input, in declared
    * order, why its load failed (None for an input that loaded); and why the
    * run stopped, if it did.
    */
  private def runUnifiedDag(
      dag: TaskDag,
      executor: SqlExecutor,
      catalog: Catalog,
      vars: TemporalVariables,
      listener: TaskProgressListener
  ): (Array[TaskResult], IndexedSeq[Option[String]], Option[String]) = {
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
    // `cause` is why the load failed, None when it loaded.
    final case class InputCompleted(index: Int, cause: Option[String]) extends Event
    final case class TaskCompleted(index: Int, result: TaskResult) extends Event

    val events = new LinkedBlockingQueue[Event]()
    // Why the run stopped, once a task's failure stopped it. The workers read
    // it as well as the loop, so a task queued before the stop does not start
    // after it.
    val stopped = new AtomicReference[String]()

    // ---- Input loading ------------------------------------------------------
    val nInputs = inputs.size
    // Why each input failed to load, by input index.
    val inputFailures = Array.fill[Option[String]](nInputs)(None)

    // Submit one Callable per input. The input load resolves the path, optionally
    // materializes for cache=true, registers the view in the catalog, and posts
    // an `InputCompleted` event onto the scheduler queue. Whatever the load
    // throws is why it failed, running out of memory included (as caching a
    // large input can), which [[oomHint]] explains.
    inputs.iterator.zipWithIndex.foreach { case (in, i) =>
      Scheduler.submit(new Callable[Unit] {
        def call(): Unit = {
          var cause: Option[String] = Some(s"the load of input '${in.viewName}' ended without a result")
          try {
            try listener.onInputStart(i, in.viewName) catch { case _: Throwable => () }
            cause =
              try {
                val rendered = in.copy(path = TemplateRenderer.render(in.path, vars))
                val raw = InputResolver.resolve(rendered)
                val view: CatalogView =
                  if (in.cache) MaterializedView.materializeInParallel(raw)
                  else raw
                catalog.register(in.viewName, view)
                None
              } catch {
                case e: Throwable => Some(Option(e.getMessage).getOrElse(e.toString) + oomHint(e))
              }
            try listener.onInputFinish(i, in.viewName, cause.isEmpty, cause.map(inputLoadFailure(in.viewName, _)))
            catch { case _: Throwable => () }
          } finally events.put(InputCompleted(i, cause))
        }
      })
    }

    // ---- Task submission ----------------------------------------------------
    def maybeSubmit(i: Int): Unit = {
      if (pendingTaskDeps(i) == 0 && pendingInputDeps(i) == 0 && taskResults(i) == null) {
        val node = dag.nodes(i)
        val enqueuedAt = Instant.now()
        if (upstreamFailed(i) || stopped.get != null) {
          val reason = upstreamFailReason(i).orElse(Option(stopped.get).map(s => s"not run: $s"))
            .getOrElse("upstream failure")
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
              // runOneTask turns what its task throws into the task's result;
              // this is for what its own failure handling throws, such as a
              // second OutOfMemoryError while it writes the task's record.
              val name = node.task.displayName
              var result = failedResult(name, "the task ended without a result", enqueuedAt)
              try {
                Option(stopped.get) match {
                  case Some(stop) =>
                    val reason = s"not run: $stop"
                    writeSkippedRecord(node, reason, enqueuedAt, vars)
                    val now = Instant.now()
                    result = TaskResult(name, TaskStatus.Skipped(reason), 0L, None, now, now, enqueuedAt)
                  case None =>
                    try listener.onTaskStart(i, name)
                    catch { case _: Throwable => () }
                    val run = runOneTask(node, executor, catalog, vars, enqueuedAt, needsView)
                    run.stop.foreach(stopped.compareAndSet(null, _))
                    result = run.result
                }
              } catch {
                case e: Throwable =>
                  result = failedResult(name, failureMessage(e), enqueuedAt)
                  stopReason(name, e).foreach(stopped.compareAndSet(null, _))
              } finally {
                try listener.onTaskFinish(i, result) catch { case _: Throwable => () }
                events.put(TaskCompleted(i, result))
              }
            }
          })
        }
      }
    }

    def handleInputCompletion(index: Int, cause: Option[String]): Unit = {
      // Kept before the fan-out, which reaches only the tasks that read the
      // input: an input no task reads has nothing else to carry its failure.
      inputFailures(index) = cause
      val name = inputs(index).viewName
      inputDependents.getOrElse(name.toLowerCase(Locale.ROOT), Vector.empty).foreach { i =>
        pendingInputDeps(i) -= 1
        if (cause.isDefined && !upstreamFailed(i)) {
          upstreamFailed(i) = true
          upstreamFailReason(i) = cause.map(inputLoadFailure(name, _))
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
        case InputCompleted(index, cause) =>
          remainingInputs -= 1
          handleInputCompletion(index, cause)
        case TaskCompleted(idx, result) =>
          taskResults(idx) = result
          remainingTasks -= 1
          handleTaskCompletion(idx, result)
      }
      // The early-skip path inside maybeSubmit may have synchronously filled
      // taskResults for transitively-skipped descendants; recount.
      remainingTasks = nTasks - taskResults.count(_ != null)
    }

    (taskResults, inputFailures.toIndexedSeq, Option(stopped.get))
  }

  /** Whether `t`, thrown by a task, stops the run rather than failing the task
    * alone: the JVM itself failed (out of memory, an internal error), here or
    * in pool work the task waited on, which arrives wrapped. A stack overflow
    * fails only its task: it unwinds the stack it overflowed and leaves the JVM
    * as it was. */
  private def stopsTheJob(t: Throwable): Boolean =
    causesOf(t).exists(c => c.isInstanceOf[VirtualMachineError] && !c.isInstanceOf[StackOverflowError])

  /** Why the run stops after task `taskName` threw `t`, when it does. */
  private def stopReason(taskName: String, t: Throwable): Option[String] =
    if (!stopsTheJob(t)) None
    else Some(s"the job stopped after task '$taskName' failed: ${failureMessage(t)}")

  /** What a task's failure says: its message, or for an `Error` whose message
    * alone says little, what happened. */
  private def failureMessage(t: Throwable): String = {
    val all = causesOf(t)
    all.collectFirst { case e: VirtualMachineError => e } match {
      case Some(e: StackOverflowError) =>
        s"the query nests too deeply for the thread's stack (${e.getClass.getName})"
      case Some(e: OutOfMemoryError) =>
        s"out of memory (${e.getClass.getName}: ${e.getMessage}); give the JVM more heap with -Xmx, or let the " +
          "task spill to disk (\"spill\": \"true\" in its output options)"
      case Some(e) => e.toString
      case None => Option(t.getMessage).getOrElse(t.toString)
    }
  }

  /** `t` and its causes, outermost first. */
  private def causesOf(t: Throwable): Seq[Throwable] =
    Iterator.iterate(t)(_.getCause).takeWhile(_ != null).take(20).toSeq

  private def failedResult(taskName: String, message: String, enqueuedAt: Instant): TaskResult = {
    val now = Instant.now()
    TaskResult(taskName, TaskStatus.Failed(message), 0L, None, now, now, enqueuedAt)
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
  ): TaskRun = {
    val task = node.task
    val started = Instant.now()
    val taskName = task.displayName
    // Per-task execution knobs (spill, thresholds, metrics) live alongside
    // writer options inside `OutputFilePath.options`. Tasks with no outputFile
    // run with [[ExecutionOptions.Default]] (spill + metrics disabled) — they
    // are memory-only feeders for downstream views.
    val opts: ExecutionOptions = buildExecutionOptions(task)
    // Capture per-thread sampling baselines for the task body. The thread that
    // runs this task body and the thread that signs the metrics snapshot are
    // the same (the executor's scheduler call), so the per-thread CPU /
    // allocation reads here are meaningful. GC is process-wide.
    val tid = Thread.currentThread.getId
    val cpu0 = if (opts.metricsEnabled) MetricsCollector.currentCpuNanos() else -1L
    val alloc0 = if (opts.metricsEnabled) MetricsCollector.currentAllocatedBytes(tid) else -1L
    val gc0 = if (opts.metricsEnabled) MetricsCollector.gcSample() else com.transformer.core.metrics.GcSample.Zero
    // The task's output directory, once the run has started writing it: a
    // failure from then on must leave it holding only the Failed record.
    var output: Option[OutputDirectory] = None
    try {
      val q = executor.execute(node.renderedMainSql, catalog, opts)
      // A task's result is a table other tasks, validations and engines read
      // by name, so it may not repeat one: a downstream task failed on it,
      // naming neither this task nor the columns (plans/robustness/26).
      Schema.repeatedName(q.schema.fieldNames).foreach { positions =>
        val names = q.schema.fieldNames
        throw new IllegalArgumentException(
          s"Task '$taskName' returns more than one column named '${names(positions.head)}', columns " +
            s"${Schema.describePositions(names, positions)}, which a query reading it could not tell apart, " +
            "names being read regardless of case; give each its own name with AS")
      }

      var writtenFiles: Seq[String] = Nil
      val writtenOutput: Option[(OutputFilePath, String)] = task.outputFile.map { ofp =>
        if (ofp.isCloud) throw new UnsupportedOperationException(
          s"Cloud output paths not yet implemented (v1.1). Got: '${ofp.path}'"
        )
        val rendered = TemplateRenderer.render(ofp.path, vars)
        val out = new OutputDirectory(Paths.get(rendered))
        output = Some(out)
        writtenFiles = writeOutput(out, ofp, node, q, vars, enqueuedAt, started)
        (ofp, rendered)
      }

      // The final record: `_perf.json` and the validation samples go before
      // it, as it is the run's last write in the output directory. One that
      // cannot be written throws, and fails the task.
      def finishOutput(failures: Seq[ValidationFailure], finished: Instant): Unit = {
        maybeWritePerfRecord(opts, taskName, writtenOutput, q, vars, enqueuedAt, started, finished,
          cpu0, alloc0, gc0, tid)
        for (out <- output; (ofp, _) <- writtenOutput) {
          if (failures.nonEmpty)
            writeValidationFailedRecord(out, node, ofp, writtenFiles, q.schema, q.rowsProduced, failures,
              enqueuedAt, started, finished, vars)
          else
            writeSucceededRecord(out, node, ofp, writtenFiles, q.schema, q.rowsProduced, enqueuedAt, started,
              finished, vars)
        }
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
        val materialized = materializeIfNeeded(q, writtenOutput, writtenFiles)
        catalog.replace(name, materialized)
        val failures = runValidations(node, executor, catalog, opts)
        val finished = Instant.now()
        finishOutput(failures, finished)
        val status = if (failures.nonEmpty) TaskStatus.ValidationFailed(failures) else TaskStatus.Succeeded
        TaskRun(TaskResult(taskName, status, q.rowsProduced, writtenOutput.map(_._2), started, finished, enqueuedAt),
          stop = None)
      } else {
        if (writtenOutput.isEmpty) {
          while (q.batches.hasNext) q.batches.next()
        }
        val finished = Instant.now()
        finishOutput(Nil, finished)
        TaskRun(TaskResult(taskName, TaskStatus.Succeeded, q.rowsProduced, writtenOutput.map(_._2), started, finished,
          enqueuedAt), stop = None)
      }
    } catch {
      // Whatever the task throws fails it: an Error too, which the event loop
      // would otherwise wait for (see runUnifiedDag).
      case e: Throwable =>
        val finished = Instant.now()
        val msg = failureMessage(e)
        writeFailedRecord(node, output, msg, enqueuedAt, started, finished, vars)
        TaskRun(TaskResult(taskName, TaskStatus.Failed(msg), 0L, None, started, finished, enqueuedAt),
          stopReason(taskName, e))
    }
  }

  /** Build the per-task [[ExecutionOptions]] from the output config's options
    * map, applying the global metrics default when the per-task value is
    * unset.
    *
    * Precedence (highest wins): per-task `options.metrics` if explicitly
    * present in the output config → [[MetricsCollector.globalDefault]]
    * (from sysprop / env). Tasks without an `outputFile` have nowhere to
    * carry per-task config; for them metrics enablement comes solely from
    * the global default.
    *
    * The `transformer.macro.force_spill` sysprop flips `spillEnabled = true`
    * on every task's options when set to `"true"`/`"1"`. Used by the macro
    * bench runner (benchmarks/macro/) to capture the spill-on baseline
    * without modifying the underlying job config. Mirrors the global override
    * pattern that [[MetricsCollector.globalDefault]] uses for metrics: a
    * single sysprop / env override that overrides every per-task config.
    * Has no effect when unset (default behaviour: spill comes from per-task
    * `options.spill` only).
    */
  private[job] def buildExecutionOptions(task: SQLTask): ExecutionOptions = task.outputFile match {
    case Some(ofp) =>
      val base = ExecutionOptions.fromOutputOptions(ofp.options)
      val triState = ExecutionOptions.triState(ofp.options, ExecutionOptions.MetricsKey)
      val metrics =
        if (triState == ExecutionOptions.UnsetTri) MetricsCollector.globalDefault
        else triState == ExecutionOptions.TrueTri
      base.copy(
        metricsEnabled = metrics,
        spillEnabled = base.spillEnabled || DataJob.forceSpillFromSysprop)
    case None =>
      ExecutionOptions.Default.copy(
        metricsEnabled = MetricsCollector.globalDefault,
        spillEnabled = DataJob.forceSpillFromSysprop)
  }

  /** Write `_perf.json` next to the task's `_run.json` when metrics were
    * enabled for this task. No-op when [[ExecutionOptions.metricsEnabled]]
    * is false or when the task has no output directory.
    *
    * Failure is swallowed via `try/catch NonFatal` — a failed perf write
    * must never poison an otherwise-successful task. */
  private def maybeWritePerfRecord(
      opts: ExecutionOptions,
      taskName: String,
      writtenOutput: Option[(OutputFilePath, String)],
      q: ExecutedQuery,
      vars: TemporalVariables,
      enqueuedAt: Instant,
      startedAt: Instant,
      finishedAt: Instant,
      cpu0: Long,
      alloc0: Long,
      gc0: com.transformer.core.metrics.GcSample,
      threadId: Long
  ): Unit = {
    if (!opts.metricsEnabled) return
    writtenOutput.foreach { case (_, renderedPath) =>
      try {
        val cpu1 = MetricsCollector.currentCpuNanos()
        val alloc1 = MetricsCollector.currentAllocatedBytes(threadId)
        val gc1 = MetricsCollector.gcSample()
        val cpuDelta = if (cpu0 < 0L || cpu1 < 0L) -1L else cpu1 - cpu0
        val allocDelta = if (alloc0 < 0L || alloc1 < 0L) -1L else alloc1 - alloc0
        val gcDelta = gc1.delta(gc0)
        val record = TaskMetricsRecord(
          schemaVersion = TaskMetricsRecord.SchemaVersion,
          taskName = taskName,
          executionTime = vars.executionTime,
          enqueuedAt = enqueuedAt,
          startedAt = startedAt,
          finishedAt = finishedAt,
          query = q.metricsSnapshot,
          cpuNanos = cpuDelta,
          allocBytes = allocDelta,
          gcDeltaCount = gcDelta.count,
          gcDeltaNanos = gcDelta.nanos
        )
        TaskMetricsRecord.write(Paths.get(renderedPath), record)
      } catch { case NonFatal(_) => () }
    }
  }

  /** The record a run writes for a task that ran: `status`, the output files
    * it committed, and the rest of what the task's directory says of the
    * run. */
  private def ranRecord(
      node: TaskDagNode,
      ofp: OutputFilePath,
      status: TaskRunStatus,
      files: Seq[String],
      schema: Schema,
      rowsProduced: Long,
      validations: Seq[ValidationRecord],
      enqueuedAt: Instant,
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): TaskRunRecord = TaskRunRecord(
    schemaVersion = TaskRunRecord.SchemaVersion,
    taskName = node.task.displayName,
    status = status,
    errorMessage = None,
    executionTime = vars.executionTime,
    startedAt = startedAt,
    finishedAt = Some(finishedAt),
    writtenAt = Instant.now(),
    rowsProduced = rowsProduced,
    format = ofp.detectedFormat,
    outputFiles = files,
    validations = validations,
    enqueuedAt = Some(enqueuedAt),
    options = ofp.options,
    schema = Some(schema),
    sql = Some(node.renderedMainSql)
  )

  /** Stamp a Succeeded [[TaskRunRecord]], naming the part files `files` the
    * run committed. A record that cannot be written fails the task
    * ([[OutputDirectory.finish]]). */
  private def writeSucceededRecord(
      out: OutputDirectory,
      node: TaskDagNode,
      ofp: OutputFilePath,
      files: Seq[String],
      schema: Schema,
      rowsProduced: Long,
      enqueuedAt: Instant,
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): Unit = {
    val validations = node.task.validations.map { v =>
      ValidationRecord(name = v.name, passed = true, failedRowCount = 0L, sampleFile = None)
    }
    out.finish(ranRecord(node, ofp, TaskRunStatus.Succeeded, files, schema, rowsProduced, validations, enqueuedAt,
      startedAt, finishedAt, vars))
  }

  /** Stamp a ValidationFailed [[TaskRunRecord]] after per-validation
    * `_validation-<slug>.csv` sample files. Part files are left on disk — the
    * data was successfully produced, only the integrity checks disagreed. A
    * sample that cannot be written is recorded without one; a record that
    * cannot be written fails the task.
    */
  private def writeValidationFailedRecord(
      out: OutputDirectory,
      node: TaskDagNode,
      ofp: OutputFilePath,
      files: Seq[String],
      schema: Schema,
      rowsProduced: Long,
      failures: Seq[ValidationFailure],
      enqueuedAt: Instant,
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): Unit = {
    val failureByName = failures.iterator.map(f => f.validationName -> f).toMap
    // Each failed validation's sample gets its own file, even where two names
    // reduce to the same one.
    val taken = mutable.Set.empty[String]
    val validations = node.task.validations.map { v =>
      failureByName.get(v.name) match {
        case Some(f) =>
          val sampleName =
            try Some(TaskRunRecord.writeValidationSample(out.dir, v.name, f.sampleRowsCsv, taken.toSet))
            catch { case NonFatal(_) => None }
          sampleName.foreach(taken += _)
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
    out.finish(ranRecord(node, ofp, TaskRunStatus.ValidationFailed, files, schema, rowsProduced, validations,
      enqueuedAt, startedAt, finishedAt, vars))
  }

  /** Stamp a Failed [[TaskRunRecord]] for a task that threw. `output` is the
    * directory the run had started writing, whose files go first
    * ([[OutputDirectory.fail]]); without one the task's path is rendered on
    * a best-effort basis — if rendering itself throws, the failure is
    * recorded only in [[JobRunRecord]]. A record that cannot be written
    * leaves the directory as it was, `Running` once the run marked it, which
    * the next run clears. */
  private def writeFailedRecord(
      node: TaskDagNode,
      output: Option[OutputDirectory],
      errorMessage: String,
      enqueuedAt: Instant,
      startedAt: Instant,
      finishedAt: Instant,
      vars: TemporalVariables
  ): Unit = node.task.outputFile.foreach { ofp =>
    if (ofp.isCloud) return
    try {
      val out = output.getOrElse(new OutputDirectory(Paths.get(TemplateRenderer.render(ofp.path, vars))))
      out.fail(TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = node.task.displayName,
        status = TaskRunStatus.Failed,
        errorMessage = Some(errorMessage),
        executionTime = vars.executionTime,
        startedAt = startedAt,
        finishedAt = Some(finishedAt),
        writtenAt = Instant.now(),
        rowsProduced = 0L,
        format = ofp.detectedFormat,
        outputFiles = Nil,
        validations = node.task.validations.map(v =>
          ValidationRecord(v.name, passed = false, failedRowCount = 0L, sampleFile = None)
        ),
        enqueuedAt = Some(enqueuedAt),
        options = ofp.options,
        sql = Some(node.renderedMainSql)
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Stamp a Skipped [[TaskRunRecord]] for a task whose upstream failed: it
    * replaces the earlier record, and then the earlier run's files go
    * ([[OutputDirectory.replace]]). */
  private def writeSkippedRecord(
      node: TaskDagNode,
      reason: String,
      now: Instant,
      vars: TemporalVariables
  ): Unit = node.task.outputFile.foreach { ofp =>
    if (ofp.isCloud) return
    try {
      val rendered = TemplateRenderer.render(ofp.path, vars)
      new OutputDirectory(Paths.get(rendered)).replace(TaskRunRecord(
        schemaVersion = TaskRunRecord.SchemaVersion,
        taskName = node.task.displayName,
        status = TaskRunStatus.Skipped,
        errorMessage = Some(reason),
        executionTime = vars.executionTime,
        startedAt = now,
        finishedAt = Some(now),
        writtenAt = Instant.now(),
        rowsProduced = 0L,
        format = ofp.detectedFormat,
        outputFiles = Nil,
        validations = node.task.validations.map(v =>
          ValidationRecord(v.name, passed = false, failedRowCount = 0L, sampleFile = None)
        ),
        enqueuedAt = Some(now),
        options = ofp.options,
        sql = Some(node.renderedMainSql)
      ))
    } catch { case NonFatal(_) => () }
  }

  /** Writes `q` into the task's output directory `out` and returns the part
    * files it committed, one per source partition, coalesced down to
    * [[OutputFilePath.maxPartitions]] if that's set and the source produced
    * more partitions than the cap.
    *
    * The directory is opened as [[OutputDirectory]] says: refused when it
    * holds part files that no record names, as they would join the output
    * for every reader of the directory, and the tasks after this one read
    * them too, where a stray `part-00007.csv` added its row to a downstream
    * count (plans/robustness/29); then marked with a `Running` record and
    * cleared of the earlier run's files. The parts are staged and committed
    * together, so the part files in the directory afterwards are exactly the
    * ones this run wrote.
    */
  private def writeOutput(
      out: OutputDirectory,
      ofp: OutputFilePath,
      node: TaskDagNode,
      q: ExecutedQuery,
      vars: TemporalVariables,
      enqueuedAt: Instant,
      startedAt: Instant): Seq[String] = {
    out.open(TaskRunRecord(
      schemaVersion = TaskRunRecord.SchemaVersion,
      taskName = node.task.displayName,
      status = TaskRunStatus.Running,
      errorMessage = None,
      executionTime = vars.executionTime,
      startedAt = startedAt,
      finishedAt = None,
      writtenAt = Instant.now(),
      rowsProduced = 0L,
      format = ofp.detectedFormat,
      outputFiles = Nil,
      validations = Nil,
      enqueuedAt = Some(enqueuedAt),
      options = ofp.options,
      sql = Some(node.renderedMainSql)
    ))
    out.write(ofp.detectedFormat, q.schema, coalescedPartitions(q, ofp.maxPartitions), ofp.options).files
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

  /** Publish a finished task's result as a view so validations and downstream
    * tasks can read it.
    *
    * A task with an `outputFile` re-reads what it just wrote: both CSV and
    * Parquet readers treat a bare directory as "every file inside", which is
    * exactly the part-file layout we produce, and the re-read keeps the
    * published view backed by disk rather than heap.
    *
    * A task with NO `outputFile` is a memory-only feeder — it has nothing on
    * disk to re-read, so its result is drained into a [[MaterializedView]]
    * instead. That holds the whole result in heap for the rest of the run (the
    * same trade cached inputs and materialized CTEs make), which is why it is
    * only done when something actually needs the view: a downstream task or
    * this task's own validations.
    */
  /** The view the tasks after this one and its validations read: the part
    * files `writtenFiles` this run wrote, by name, never a listing of the
    * directory, so a file that is no part of the output (a README) is not
    * read. A parquet output of no partitions writes no file at all, and is
    * the empty view of its schema. */
  private def materializeIfNeeded(
      q: ExecutedQuery, writtenOutput: Option[(OutputFilePath, String)], writtenFiles: Seq[String]): CatalogView = {
    writtenOutput match {
      case Some((ofp, dir)) =>
        TaskRunRecord.outputView(Paths.get(dir), ofp.detectedFormat, writtenFiles, ofp.options, Some(q.schema))
      case None => MaterializedView.fromQuery(q)
    }
  }

  private def runValidations(
      node: TaskDagNode,
      executor: SqlExecutor,
      catalog: Catalog,
      opts: ExecutionOptions): Seq[ValidationFailure] = {
    node.task.validations.iterator.zip(node.renderedValidationSqls.iterator).flatMap { case (v, sql) =>
      val q = executor.execute(sql, catalog, opts)
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
    * pointer per task so the GUI can load one file to find everything, and
    * records the run's lineage: each input as it was read, and what each
    * task's main SQL reads. Its paths are absolute, so a reader finds them
    * from any working directory. */
  private def writeJobRecord(
      dag: TaskDag,
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
      val inputNames = inputs.iterator.map(in => in.viewName.toLowerCase(Locale.ROOT) -> in.viewName).toMap
      val summaries: Seq[JobTaskSummary] = results.iterator.zipWithIndex.map { case (r, i) =>
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
        // Point at the per-task record whenever the task is CONFIGURED with an
        // output directory, not just when it produced output: the runner stamps
        // a `_run.json` there for every terminal status, and a Failed or Skipped
        // task's `TaskResult` carries no `outputPath`. Deriving it from the
        // result instead left those records on disk but unreachable from the
        // manifest, so reopening the run showed only this compact summary. A
        // cloud path writes no record at all, so it gets no pointer.
        val runFile = sql(i).outputFile
          .filterNot(_.isCloud)
          .map(taskOut => Paths.get(TemplateRenderer.render(taskOut.path, vars)).toAbsolutePath.normalize()
            .resolve(TaskRunRecord.FileName).toString)
        val node = dag.nodes(i)
        JobTaskSummary(
          taskName = r.taskName,
          status = status,
          runFile = runFile,
          rowsProduced = r.rowsProduced,
          failedValidationCount = failedValidationCount,
          errorMessage = errMsg,
          startedAt = Some(r.startedAt),
          finishedAt = Some(r.finishedAt),
          enqueuedAt = Some(r.enqueuedAt),
          failedValidations = r.status match {
            case TaskStatus.ValidationFailed(fs) => fs.map(f => FailedValidation(f.validationName, f.rowCount))
            case _                               => Nil
          },
          readsInputs = node.mainInputDeps.toSeq.flatMap(inputNames.get).sorted,
          readsTasks = node.mainDeps.toSeq.sorted.map(d => results(d).taskName)
        )
      }.toSeq
      val inputRecords = inputs.map { in =>
        val rendered = try TemplateRenderer.render(in.path, vars) catch { case NonFatal(_) => in.path }
        JobInputRecord(in.viewName, absolute(rendered), in.detectedFormat, in.options)
      }
      // perfManifest collects every `_perf.json` actually written under the
      // tasks' output directories. Populated only when at least one task
      // emitted a perf record — keeps `job.json`'s default shape unchanged
      // for unmetered runs.
      val perfPaths: Vector[String] = results.iterator.flatMap { r =>
        r.outputPath.flatMap { outPath =>
          val perfFile = Paths.get(outPath).resolve(TaskMetricsRecord.FileName)
          if (Files.isRegularFile(perfFile)) Some(perfFile.toString) else None
        }
      }.toVector
      val perfManifest: Option[Seq[String]] = if (perfPaths.nonEmpty) Some(perfPaths) else None
      JobRunRecord.write(target, JobRunRecord(
        schemaVersion = JobRunRecord.SchemaVersion,
        succeeded = succeeded,
        errorMessage = errorMessage,
        executionTime = vars.executionTime,
        startedAt = startedAt,
        finishedAt = finishedAt,
        tasks = summaries,
        warnings = warnings,
        perfManifest = perfManifest,
        outputTemplate = try Option(Paths.get(absolute(ofp.path)).getParent).map(_.toString)
          catch { case NonFatal(_) => None },
        inputs = Some(inputRecords)
      ))
    } catch { case NonFatal(_) => () }
  }

  /** `path` made absolute against the working directory, as the run reads and
    * writes it; a cloud path, or one that is no path here (a glob on
    * Windows), as it is. */
  private def absolute(path: String): String =
    if (path.startsWith("gs://") || path.startsWith("s3://")) path
    else try Paths.get(path).toAbsolutePath.normalize().toString catch { case NonFatal(_) => path }

  /** How a failed input load reads in the run's records: the reason a task
    * that reads the input is skipped with, and the input's warning. */
  private def inputLoadFailure(viewName: String, cause: String): String =
    s"failed to load input '$viewName': $cause"

  /** One warning per input that failed to load, in declared order, whether or
    * not a task reads it. A failed input fails the run only through the tasks
    * it skips, and a skipped task names just the first failed upstream it met,
    * so the warnings are the one place every failed input is named, and the
    * only place one that no task reads shows at all. Such an input fails
    * nothing, and its warning says no task reads it. */
  private def inputWarnings(dag: TaskDag, failures: IndexedSeq[Option[String]]): Seq[String] = {
    val read = dag.nodes.iterator.flatMap(_.inputDeps).toSet
    inputs.indices.flatMap { i =>
      failures(i).map { cause =>
        val name = inputs(i).viewName
        if (read.contains(name.toLowerCase(Locale.ROOT))) inputLoadFailure(name, cause)
        else s"failed to load input '$name', which no task reads: $cause"
      }
    }
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

object DataJob {

  /** A task's result, and why the run stops, when the task's failure stops
    * it (`stopsTheJob`). */
  private final case class TaskRun(result: TaskResult, stop: Option[String])

  /** Name of the sysprop the macro bench runner sets to force spill on every
    * task. See [[buildExecutionOptions]]'s scaladoc for the full contract.
    *
    * One concrete consumer: the macro bench runner in `benchmarks/macro/`
    * captures the spill-on baseline by invoking the deploy jar with
    * `-Dtransformer.macro.force_spill=true`, which is cheaper than rewriting
    * every task's `output.json` to add `options.spill = "true"`. */
  val ForceSpillPropertyName: String = "transformer.macro.force_spill"

  /** Env-var alternative to [[ForceSpillPropertyName]]. Set to `"true"` /
    * `"1"` to force spill on every task. */
  val ForceSpillEnvVarName: String = "TRANSFORMER_MACRO_FORCE_SPILL"

  /** Resolved once at class load. True iff [[ForceSpillPropertyName]] or
    * [[ForceSpillEnvVarName]] parse as truthy. */
  val forceSpillFromSysprop: Boolean = {
    val sys = Option(System.getProperty(ForceSpillPropertyName)).map(_.trim).filter(_.nonEmpty)
    val env = Option(System.getenv(ForceSpillEnvVarName)).map(_.trim).filter(_.nonEmpty)
    sys.orElse(env).exists { s =>
      val t = s.toLowerCase(Locale.ROOT)
      t == "true" || t == "1"
    }
  }
}
