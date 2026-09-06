package com.transformer.fuzz

import com.transformer.job.{DataJob, InputFilePath, OutputFilePath, SQLTask, Validation}

import java.nio.file.{Files, Path}

/** Generator of a random `DataJob` — inputs plus a DAG of [[SQLTask]]s — for the
  * scheduler property ([[com.transformer.fuzz.oracle.DagSchedule]]).
  *
  * Where the other generators fuzz what the SQL engine *computes*, this one
  * fuzzes what `DataJob.runUnifiedDag` *orchestrates*: readiness counting across
  * input loads and task deps, failure/skip propagation through the DAG, the
  * enqueue/start/finish timestamps, and pool liveness when many tasks each fan
  * their own nested work onto the same shared [[com.transformer.core.Scheduler]].
  *
  * Three properties make a generated job an exact oracle input:
  *
  *   - '''Acyclic by construction.''' Task `i` may only read inputs and tasks
  *     `j < i`, so `TaskDag.build` never rejects the job for a cycle and the
  *     expected status of every task is a single forward pass.
  *
  *   - '''Row-monotone SQL.''' Every task body is a `UNION ALL` of unfiltered
  *     `SELECT k, v FROM <src>` arms over non-empty sources, so a task that runs
  *     always produces at least one row. That makes the injected outcome exact:
  *     a `ValidationFails` task's `SELECT k FROM <ownView>` check is guaranteed
  *     to return rows, and a passing check is guaranteed not to.
  *
  *   - '''Failure injected at execution, not at DAG build.''' A `Fails` task
  *     selects a column that does not exist. It parses (so `TaskDag` still sees
  *     the same table references and builds the same edges) and dies inside the
  *     worker, which is exactly the path the scheduler's skip propagation reacts
  *     to. A broken input points at a path matching no file, so its load fails
  *     the same way.
  *
  * Per-task writer/spill/metrics knobs are drawn too, so concurrently running
  * tasks exercise the parquet and CSV writers, the shared spill directory, and
  * the metrics snapshot path at the same time, and a share of tasks acquire a
  * dependency through a VALIDATION's FROM rather than the main SQL — a separate
  * edge source in `TaskDag.build` with the same readiness and skip semantics.
  */
object JobDagGen {

  /** Where every generated job writes its aggregate `job.json` manifest,
    * relative to the case's scratch root. The oracle reads it back: the manifest
    * is what the GUI and the next run load to reconstruct a finished run, so it
    * has to agree with the in-memory result for every task. */
  val ManifestFile: String = "job.json"

  /** What a generated task is rigged to do when the scheduler gets to it. */
  sealed trait Outcome
  /** Runs and succeeds. */
  case object Ok extends Outcome
  /** Main SQL references a non-existent column: the worker throws. */
  case object Fails extends Outcome
  /** Runs, then a validation returns rows. */
  case object ValidationFails extends Outcome

  /** One generated input. `broken` points the path at a name that matches no
    * file, so `InputResolver.resolve` throws and the load reports failure. */
  final case class GenInput(index: Int, rows: Int, broken: Boolean) {
    def view: String = s"in$index"
  }

  /** One generated task. `inputSrcs` indexes [[JobCase.inputs]], `taskSrcs`
    * indexes earlier tasks; together they are the task's FROM sources and hence
    * the DAG edges `TaskDag` recovers from the rendered SQL.
    *
    * `validationTaskSrc` is a second, independent way to acquire a dependency:
    * a validation that queries ANOTHER task's view. `TaskDag` extracts table
    * references from validation SQL as well as main SQL, so this edge gates
    * readiness and propagates skips exactly like a FROM-clause edge — a
    * distinct code path worth generating, since a runner that only counted
    * main-SQL refs would run the validation against a view that does not
    * exist yet. */
  final case class GenTask(
      index: Int,
      inputSrcs: Vector[Int],
      taskSrcs: Vector[Int],
      outcome: Outcome,
      output: Option[TaskOutput],
      passingValidation: Boolean,
      validationTaskSrc: Option[Int]) {
    def view: String = s"t$index"
    def sources: Int = inputSrcs.length + taskSrcs.length
    /** Every upstream TASK this node waits on — FROM sources plus the task a
      * validation reads. This, not `taskSrcs`, is the DAG edge set. */
    def taskDeps: Vector[Int] = (taskSrcs ++ validationTaskSrc).distinct
  }

  /** A task's output directory config: format plus the execution knobs that ride
    * in `OutputFilePath.options`. A task with no [[TaskOutput]] is memory-only. */
  final case class TaskOutput(parquet: Boolean, spill: Boolean, metrics: Boolean) {
    def format: String = if (parquet) "parquet" else "csv"
    def options: Map[String, String] = Map(
      "spill" -> spill.toString,
      "metrics" -> metrics.toString) ++
      (if (spill) Map("spill_threshold_bytes" -> "1") else Map.empty)
  }

  /** A whole generated job. Rendered to a runnable [[DataJob]] by [[build]],
    * which needs a scratch directory for the input CSVs and task outputs. */
  final case class JobCase(inputs: Vector[GenInput], tasks: Vector[GenTask]) {

    /** Materialize the input CSVs under `root` and return the runnable job. */
    def build(root: Path): DataJob = {
      val inDir = root.resolve("in")
      Files.createDirectories(inDir)
      val jobInputs = inputs.map { in =>
        val file = inDir.resolve(s"${in.view}.csv")
        val sb = new java.lang.StringBuilder("k,v\n")
        var r = 0
        while (r < in.rows) { sb.append(r).append(',').append(r * 10).append('\n'); r += 1 }
        Files.writeString(file, sb.toString)
        val path = if (in.broken) inDir.resolve(s"${in.view}-missing.csv").toString else file.toString
        InputFilePath(path, viewName = in.view, cache = in.index % 2 == 0)
      }
      DataJob(
        inputs = jobInputs,
        sql = tasks.map(t => sqlTask(t, root)),
        jobRunOutput = Some(OutputFilePath(root.resolve(ManifestFile).toString)))
    }

    private def sqlTask(t: GenTask, root: Path): SQLTask = {
      val validations =
        (if (t.passingValidation)
           Seq(Validation("no-negative-k", sqlString = Some(s"SELECT k FROM ${t.view} WHERE k < -1000000")))
         else Nil) ++
          t.validationTaskSrc.map(j =>
            Validation("upstream-no-negative-k", sqlString = Some(s"SELECT k FROM t$j WHERE k < -1000000"))) ++
          (if (t.outcome == ValidationFails)
             Seq(Validation("always-fails", sqlString = Some(s"SELECT k FROM ${t.view}")))
           else Nil)
      SQLTask(
        sqlString = Some(mainSql(t)),
        outputFile = t.output.map { o =>
          OutputFilePath(root.resolve(t.view).toString, options = o.options, format = Some(o.format))
        },
        validations = validations,
        viewName = Some(t.view),
        name = Some(t.view))
    }

    /** `UNION ALL` over every source. A `Fails` task poisons its first arm with a
      * column that does not exist — the statement still parses, so the DAG keeps
      * the same edges and the failure lands in the worker. */
    def mainSql(t: GenTask): String = {
      val srcs = t.inputSrcs.map(i => inputs(i).view) ++ t.taskSrcs.map(j => s"t$j")
      srcs.iterator.zipWithIndex.map { case (s, arm) =>
        if (t.outcome == Fails && arm == 0) s"SELECT k, no_such_column AS v FROM $s"
        else s"SELECT k, v FROM $s"
      }.mkString(" UNION ALL ")
    }

    override def toString: String = {
      val ins = inputs.map(i => s"${i.view}(rows=${i.rows}${if (i.broken) ",BROKEN" else ""})").mkString(", ")
      val ts = tasks.map { t =>
        val srcs = (t.inputSrcs.map(i => inputs(i).view) ++ t.taskSrcs.map(j => s"t$j")).mkString("+")
        val out = t.output.map(o => s",${o.format}${if (o.spill) ",spill" else ""}").getOrElse(",mem")
        val vdep = t.validationTaskSrc.map(j => s",valOn=t$j").getOrElse("")
        s"${t.view}<-$srcs(${t.outcome}$out$vdep)"
      }.mkString(", ")
      s"JobCase(inputs=[$ins], tasks=[$ts])"
    }
  }

  // ---- generation ---------------------------------------------------------

  def generate(rng: Rng): JobCase = {
    // Per-case rates rather than one global rate: a share of cases is entirely
    // clean (the shape that hunts pool liveness and ordering under real
    // concurrency) and a share is failure-heavy (the shape that hunts skip
    // propagation). One blended rate would generate neither well.
    val failProb = rng.weighted((4, 0.0), (4, 0.15), (2, 0.4))
    val brokenProb = rng.weighted((6, 0.0), (2, 0.25))

    val nInputs = rng.between(1, 3)
    val inputs = Vector.tabulate(nInputs)(i =>
      GenInput(i, rows = rng.between(1, 4), broken = rng.bool(brokenProb)))

    // Up to ten tasks: enough that a wide DAG has more concurrently-eligible
    // tasks than the pool has threads under the starved-pool target, which is
    // the shape the liveness check wants.
    val nTasks = rng.between(1, 10)
    val tasks = Array.ofDim[GenTask](nTasks)
    var i = 0
    while (i < nTasks) {
      val nSrc = math.min(rng.weighted((5, 1), (3, 2), (1, 3)), nInputs + i)
      // Draw sources from the union of inputs and earlier tasks, so the DAG
      // grows both wide (many roots on inputs) and deep (chains of tasks).
      val pool: Vector[Either[Int, Int]] =
        Vector.tabulate(nInputs)(x => Left(x)) ++ Vector.tabulate(i)(x => Right(x))
      val picked = distinctPicks(rng, pool, nSrc)
      tasks(i) = GenTask(
        index = i,
        inputSrcs = picked.collect { case Left(x) => x },
        taskSrcs = picked.collect { case Right(x) => x },
        outcome =
          if (rng.bool(failProb)) { if (rng.bool()) Fails else ValidationFails }
          else Ok,
        output = if (rng.bool(0.6)) Some(TaskOutput(
          parquet = rng.bool(0.4), spill = rng.bool(0.25), metrics = rng.bool(0.3)))
        else None,
        passingValidation = rng.bool(0.3),
        validationTaskSrc = if (i > 0 && rng.bool(0.25)) Some(rng.nextInt(i)) else None)
      i += 1
    }
    // Every input must have a consumer: an input nothing reads still loads (and
    // may still fail) but reaches no task, which would leave its `broken` draw
    // invisible to the oracle.
    val result = tasks.toVector
    JobCase(inputs, attachOrphanInputs(rng, inputs, result))
  }

  /** Give every input at least one reading task, so a broken input always shows
    * up in some task's expected status. */
  private def attachOrphanInputs(rng: Rng, inputs: Vector[GenInput], tasks: Vector[GenTask]): Vector[GenTask] = {
    val consumed = tasks.iterator.flatMap(_.inputSrcs).toSet
    var out = tasks
    inputs.indices.foreach { in =>
      if (!consumed.contains(in)) {
        val victim = rng.nextInt(out.length)
        val t = out(victim)
        out = out.updated(victim, t.copy(inputSrcs = (t.inputSrcs :+ in).distinct))
      }
    }
    out
  }

  private def distinctPicks[A](rng: Rng, pool: Vector[A], n: Int): Vector[A] = {
    val remaining = scala.collection.mutable.ArrayBuffer.from(pool)
    val out = Vector.newBuilder[A]
    var k = 0
    while (k < n && remaining.nonEmpty) {
      out += remaining.remove(rng.nextInt(remaining.length))
      k += 1
    }
    out.result()
  }
}
