package com.transformer.fuzz

import com.transformer.core.{Catalog, ExecutedQuery, ExecutionOptions, SqlExecutor}
import com.transformer.job.{DataJob, InputFilePath, JobFiles, JobRunRecord, OutputFilePath, SQLTask, TaskRunRecord, Validation}
import com.transformer.temporal.{TemplateRenderer, TemporalVariables}

import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.{Duration, Instant}

/** Generator of a random `DataJob` — inputs plus a DAG of [[SQLTask]]s — for the
  * scheduler property ([[com.transformer.fuzz.oracle.DagSchedule]]) and the
  * hydration property ([[com.transformer.fuzz.oracle.Hydration]]).
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
  *
  * A case also says where its runs land and how often it runs ([[RunLayout]]):
  * the job may write under a templated directory, a task's output may append a
  * templated partition, and the job may run a second time at another execution
  * time. That is the on-disk shape the GUI reads a finished run back from —
  * several runs side by side, a task's partitions across them — and
  * [[JobCase.writeDirectoryJob]] writes the same job as the directory the GUI
  * opens. A case then names one [[Corruption]] of the finished tree, which the
  * hydration property applies once it has checked the tree intact. And a share
  * of cases draw [[AdversarialStrings]] for what the records carry as text: task
  * names, the message a failing task throws (injected through [[JobCase.executor]]),
  * and the names of failing validations. Those draws come after the DAG's, each
  * from its own stream, so a seed's DAG is the one it always was.
  */
object JobDagGen {

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
      validationTaskSrc: Option[Int],
      name: Option[Adversarial] = None,
      failure: Option[Adversarial] = None,
      checks: Vector[Adversarial] = Vector.empty) {
    def view: String = s"t$index"
    /** What the run calls the task (`SQLTask.name`): its view, or an
      * adversarial name with the view embedded at its middle, so the name is
      * unique and both its ends stay as drawn. */
    def displayName: String = name.fold(view)(a => embed(a.text, view))
    def sources: Int = inputSrcs.length + taskSrcs.length
    /** Every upstream TASK this node waits on — FROM sources plus the task a
      * validation reads. This, not `taskSrcs`, is the DAG edge set. */
    def taskDeps: Vector[Int] = (taskSrcs ++ validationTaskSrc).distinct
  }

  /** A string the run records must carry as text and read back unchanged,
    * labelled for the coverage guard. */
  final case class Adversarial(label: String, text: String) {
    override def toString: String = label
  }

  /** What a record's JSON writer and `Json.parse` must agree on: each character
    * JSON escapes, the control characters it writes as hex escapes, text past
    * the Basic Multilingual Plane and halves of it, invisible characters, text
    * shaped like the JSON around it or like a template, nothing, and a long
    * multi-line stack trace — the message a real failure carries. Non-ASCII
    * text is built from code points. */
  val AdversarialStrings: Vector[Adversarial] = {
    def cps(points: Int*): String = points.map(p => new String(Character.toChars(p))).mkString
    val trace = "java.lang.IllegalStateException: task body failed\n" +
      (1 to 2000).map(i => s"\tat com.example.pipeline.Stage$i.run(Stage$i.scala:$i)\n").mkString
    Vector(
      Adversarial("quotes", "\"quoted\" and 'single' \""),
      Adversarial("backslashes", "\\C:\\temp\\new\\"),
      Adversarial("newlines", "first\nsecond\r\nthird\r"),
      Adversarial("tabs", "\tcolumn\tcolumn\t"),
      Adversarial("control characters",
        (0 until 32).filterNot(c => c == 9 || c == 10 || c == 13).map(_.toChar).mkString + 127.toChar),
      Adversarial("astral", "emoji " + cps(0x1F600) + " script " + cps(0x1D4B3)),
      Adversarial("lone surrogates", "half " + 0xD800.toChar + " of " + 0xDC00.toChar + " a pair"),
      Adversarial("invisible", cps(0xFEFF, 0x200B, 0x2028, 0x2029)),
      Adversarial("json", "\"}, {\"taskName\": \"x\", \"status\": \"Succeeded\"} ,["),
      Adversarial("template", "{{ today }} and {{ no_such_variable }}"),
      Adversarial("empty", ""),
      Adversarial("long", trace))
  }

  /** `text` with `marker` inserted at its middle code point, so a surrogate pair
    * is never split and both of `text`'s ends stay where they were. */
  def embed(text: String, marker: String): String = {
    val mid = text.offsetByCodePoints(0, text.codePointCount(0, text.length) / 2)
    text.substring(0, mid) + "#" + marker + "#" + text.substring(mid)
  }

  /** A task's output directory config: format plus the execution knobs that ride
    * in `OutputFilePath.options`, and an optional templated partition appended
    * to the task's directory, as `DirectoryJobLoader`'s `partitionBy` appends
    * it. A task with no [[TaskOutput]] is memory-only. */
  final case class TaskOutput(
      parquet: Boolean,
      spill: Boolean,
      metrics: Boolean,
      partitionBy: Option[String] = None) {
    def format: String = if (parquet) "parquet" else "csv"
    def options: Map[String, String] = Map(
      "spill" -> spill.toString,
      "metrics" -> metrics.toString) ++
      (if (spill) Map("spill_threshold_bytes" -> "1") else Map.empty)
  }

  /** One validation of a task: a query that must return no rows. */
  final case class GenValidation(name: String, sql: String)

  /** Where a case's runs write, and when they run.
    *
    * `runDir`, when set, is a templated directory under the case's `runs/`
    * folder that each run renders to its own subdirectory, holding that run's
    * `job.json` and task outputs — the multi-run layout `JobOutputLayout`
    * classifies. Without it every run writes to the same `out/` directory, and a
    * later run rewrites the earlier run's manifest.
    *
    * `executionTimes` holds one instant per run, in the order the runs happen.
    * A second run lands in the same partitions as the first when its templates
    * render the same, in new ones otherwise; it may also be a backfill, run
    * second with the older execution time, so the newest partition on disk is
    * not the latest execution time. */
  final case class RunLayout(runDir: Option[String], executionTimes: Vector[Instant]) {
    def runs: Int = executionTimes.length
  }

  /** The templated directories a run can write under — one path segment, the
    * way a user templates `outputDir` (`/data/runs/{{ today }}`). */
  val RunDirTemplates: Vector[String] =
    Vector("{{ iso_date }}", "{{ today }}-{{ current_hour_pad }}", "run-{{ epoch_seconds }}")

  /** The templated partitions a task output can append, one to three segments
    * deep, so `TaskRunRecord.discover` walks each depth. */
  val PartitionTemplates: Vector[String] = Vector(
    "day={{ today }}",
    "dt={{ iso_date }}/hour={{ current_hour_pad }}",
    "{{ year }}/{{ current_month_pad }}/{{ current_day_pad }}")

  /** First-run execution times: an ordinary morning, an hour before a year
    * turns (so an hour later every date segment changes), and the day before a
    * leap day. */
  val BaseExecutionTimes: Vector[Instant] = Vector(
    Instant.parse("2026-01-01T05:30:21Z"),
    Instant.parse("2025-12-31T23:30:00Z"),
    Instant.parse("2024-02-28T12:00:00Z"))

  /** How far a second run's execution time sits from the first's: an hour (a
    * day-templated path is rewritten in place, an hour-templated one is not), a
    * day, and a day back — the backfill. */
  val SecondRunOffsets: Vector[Duration] =
    Vector(Duration.ofHours(1), Duration.ofDays(1), Duration.ofDays(-1))

  /** One mutation of a finished case's output tree — what a crash, a bad copy or
    * a hand edit leaves behind. It touches the latest run's files only, and a
    * task it names has an output directory. */
  sealed abstract class Corruption(val name: String)
  object Corruption {
    /** The task's `_run.json` is gone. */
    final case class DeleteRecord(task: Int) extends Corruption("delete a record")
    /** The task's `_run.json` stops `keep` of the way through its bytes, inside
      * its object. */
    final case class TruncateRecord(task: Int, keep: Double) extends Corruption("truncate a record")
    /** Text follows the object in the task's `_run.json`. */
    final case class GarbageAfterRecord(task: Int, garbage: String) extends Corruption("garbage after a record")
    /** The run's `job.json` is gone, as if the job died after its tasks. */
    case object DeleteManifest extends Corruption("delete job.json")
    /** `job.json` points the task's `runFile` at a path that does not exist. */
    final case class DanglingRunFile(task: Int) extends Corruption("dangling runFile")
    /** Two tasks' `_run.json` files trade places. */
    final case class SwapRecords(a: Int, b: Int) extends Corruption("swap two records")

    val Names: Vector[String] = Vector(
      "delete a record", "truncate a record", "garbage after a record", "delete job.json",
      "dangling runFile", "swap two records")

    /** What follows a record's object: none of it whitespace, which JSON would
      * read past. One is a second object, one is NUL characters. */
    val Garbage: Vector[String] = Vector(
      "x", "}", "{\"taskName\": \"other\"}", Seq(0, 0).map(_.toChar).mkString, ",\n]")
  }

  /** A whole generated job. Rendered to a runnable [[DataJob]] by [[build]],
    * which needs a scratch directory for the input CSVs and task outputs.
    * `corruption` is applied to the finished tree by [[corrupt]]. */
  final case class JobCase(
      inputs: Vector[GenInput],
      tasks: Vector[GenTask],
      layout: RunLayout,
      corruption: Option[Corruption] = None) {

    /** The directory every run writes under, templated if the layout is — what
      * `DirectoryJobLoader` calls `outputDir`. */
    def outputDir(root: Path): String = layout.runDir match {
      case None => root.resolve("out").toString
      case Some(template) => root.resolve("runs").toString + "/" + template
    }

    /** Where each run writes its `job.json`, templated as [[outputDir]] is. */
    def manifestPath(root: Path): String = outputDir(root) + "/job.json"

    /** A task's output directory, templated: `<outputDir>/<view>`, then its
      * partition — the path `DirectoryJobLoader` builds for the same table. */
    def outputPath(root: Path, t: GenTask): Option[String] = t.output.map { o =>
      val base = outputDir(root) + "/" + t.view
      o.partitionBy.fold(base)(p => base + "/" + p)
    }

    /** A templated path as the run at `executionTime` renders it. */
    def render(path: String, executionTime: Instant): String =
      TemplateRenderer.render(path, TemporalVariables(executionTime))

    /** The `_run.json` the run at `executionTime` writes for task `t`. */
    def recordFile(root: Path, t: Int, executionTime: Instant): Path =
      Path.of(render(outputPath(root, tasks(t)).get, executionTime)).resolve(TaskRunRecord.FileName)

    /** The `job.json` the run at `executionTime` writes. */
    def manifestFile(root: Path, executionTime: Instant): Path =
      Path.of(render(manifestPath(root), executionTime))

    /** Where [[Corruption.DanglingRunFile]] points task `t`'s `runFile`: under a
      * directory nothing writes. */
    def danglingRunFile(root: Path, t: Int): String =
      root.resolve("nowhere").resolve(tasks(t).view).resolve(TaskRunRecord.FileName).toString

    /** Whether [[corruption]] still names tasks with an output directory — a
      * shrink can drop the task or its output. */
    def corruptionApplies: Boolean = {
      def recorded(t: Int): Boolean = tasks.isDefinedAt(t) && tasks(t).output.isDefined
      corruption.exists {
        case Corruption.DeleteRecord(t) => recorded(t)
        case Corruption.TruncateRecord(t, _) => recorded(t)
        case Corruption.GarbageAfterRecord(t, _) => recorded(t)
        case Corruption.DeleteManifest => true
        case Corruption.DanglingRunFile(t) => recorded(t)
        case Corruption.SwapRecords(a, b) => recorded(a) && recorded(b)
      }
    }

    /** Apply [[corruption]] to the files the run at `executionTime` (the latest)
      * wrote under `root`. */
    def corrupt(root: Path, executionTime: Instant): Unit =
      if (corruptionApplies) corruption.get match {
        case Corruption.DeleteRecord(t) =>
          Files.delete(recordFile(root, t, executionTime))
        case Corruption.TruncateRecord(t, keep) =>
          val file = recordFile(root, t, executionTime)
          val bytes = Files.readAllBytes(file)
          // A record ends "}\n", so a cut before its last two bytes leaves the
          // object open.
          val cut = math.max(1, math.min(bytes.length - 2, (bytes.length * keep).toInt))
          Files.write(file, java.util.Arrays.copyOf(bytes, cut))
        case Corruption.GarbageAfterRecord(t, garbage) =>
          Files.writeString(recordFile(root, t, executionTime), garbage, StandardOpenOption.APPEND)
        case Corruption.DeleteManifest =>
          Files.delete(manifestFile(root, executionTime))
        case Corruption.DanglingRunFile(t) =>
          val file = manifestFile(root, executionTime)
          val rec = JobRunRecord.read(file).get
          val entry = rec.tasks(t).copy(runFile = Some(danglingRunFile(root, t)))
          JobRunRecord.write(file, rec.copy(tasks = rec.tasks.updated(t, entry)))
        case Corruption.SwapRecords(a, b) =>
          val fa = recordFile(root, a, executionTime)
          val fb = recordFile(root, b, executionTime)
          val parked = fa.resolveSibling("_run.json.swap")
          Files.move(fa, parked)
          Files.move(fb, fa)
          Files.move(parked, fb)
      }

    private def inputFile(root: Path, in: GenInput): Path = root.resolve("in").resolve(s"${in.view}.csv")

    private def inputPath(root: Path, in: GenInput): String =
      if (in.broken) root.resolve("in").resolve(s"${in.view}-missing.csv").toString
      else inputFile(root, in).toString

    /** Materialize the input CSVs under `root` and return the job as the run at
      * `executionTime` sees it. */
    def build(root: Path, executionTime: Instant): DataJob = {
      Files.createDirectories(root.resolve("in"))
      val jobInputs = inputs.map { in =>
        val sb = new java.lang.StringBuilder("k,v\n")
        var r = 0
        while (r < in.rows) { sb.append(r).append(',').append(r * 10).append('\n'); r += 1 }
        Files.writeString(inputFile(root, in), sb.toString)
        InputFilePath(inputPath(root, in), viewName = in.view, cache = in.index % 2 == 0)
      }
      DataJob(
        inputs = jobInputs,
        sql = tasks.map(t => sqlTask(t, root)),
        temporalVariables = Some(TemporalVariables(executionTime)),
        jobRunOutput = Some(OutputFilePath(manifestPath(root))))
    }

    /** The same job as a job directory, written through `JobFiles` (the GUI's
      * own authoring path) to `root/job`: one input config per input, and per
      * task its `main.sql`, its validations and an `output.json` with its format
      * and partition. Opened with [[outputDir]] as the output directory, it is
      * the job the runs ran: `DirectoryJobLoader` gives every table the output
      * path [[outputPath]] gives the task, and the manifest the path
      * [[manifestPath]] gives. A memory-only task gets a directory nothing is
      * ever written to, since a table always has one. */
    def writeDirectoryJob(root: Path): Path = {
      val dir = root.resolve("job")
      inputs.foreach { in =>
        JobFiles.addInput(dir, in.view, inputPath(root, in), format = None,
          cache = in.index % 2 == 0, options = Map.empty)
      }
      tasks.foreach { t =>
        JobFiles.addTable(dir, t.view, mainSql(t),
          format = t.output.filter(_.parquet).map(_.format),
          partitionBy = t.output.flatMap(_.partitionBy),
          maxPartitions = None)
        // A validation is a file named for it, which JobFiles writes only for an
        // identifier; the directory job calls an adversarial one by its place.
        validations(t).zipWithIndex.foreach { case (v, k) =>
          val name = if (JobFiles.validateViewName(v.name).isRight) v.name else s"check_$k"
          JobFiles.addValidation(dir, t.view, name, v.sql)
        }
      }
      dir
    }

    /** A task's validations, in the order the task declares them. A
      * `ValidationFails` task's failing ones are named by its `checks`, or
      * `always_fails` when it has none; a second one selects another column, so
      * its sample rows differ from the first's. */
    def validations(t: GenTask): Vector[GenValidation] =
      (if (t.passingValidation)
         Vector(GenValidation("no_negative_k", s"SELECT k FROM ${t.view} WHERE k < -1000000"))
       else Vector.empty) ++
        t.validationTaskSrc.map(j =>
          GenValidation("upstream_no_negative_k", s"SELECT k FROM t$j WHERE k < -1000000")) ++
        (if (t.outcome != ValidationFails) Vector.empty
         else {
           val names = if (t.checks.isEmpty) Vector("always_fails") else t.checks.map(_.text)
           names.zip(Vector("k", "k, v")).map { case (name, cols) =>
             GenValidation(name, s"SELECT $cols FROM ${t.view}")
           }
         })

    private def sqlTask(t: GenTask, root: Path): SQLTask =
      SQLTask(
        sqlString = Some(mainSql(t)),
        outputFile = t.output.map { o =>
          OutputFilePath(outputPath(root, t).get, options = o.options, format = Some(o.format))
        },
        validations = validations(t).map(v => Validation(v.name, sqlString = Some(v.sql))),
        viewName = Some(t.view),
        name = Some(t.displayName))

    /** `UNION ALL` over every source. A `Fails` task poisons its first arm with a
      * column that does not exist — the statement still parses, so the DAG keeps
      * the same edges and the failure lands in the worker. A task with an
      * injected failure names its own column, so [[executor]] knows its SQL. */
    def mainSql(t: GenTask): String = {
      val srcs = t.inputSrcs.map(i => inputs(i).view) ++ t.taskSrcs.map(j => s"t$j")
      val poison = if (t.failure.isDefined) s"failure_${t.view}" else "no_such_column"
      srcs.iterator.zipWithIndex.map { case (s, arm) =>
        if (t.outcome == Fails && arm == 0) s"SELECT k, $poison AS v FROM $s"
        else s"SELECT k, v FROM $s"
      }.mkString(" UNION ALL ")
    }

    /** The executor a run uses: `engine`, except that a `Fails` task with an
      * injected failure throws it before reaching the engine, so the message the
      * run records is exactly the drawn text. */
    def executor(engine: SqlExecutor): SqlExecutor = {
      val injected: Map[String, String] =
        tasks.filter(t => t.outcome == Fails && t.failure.isDefined).map(t => mainSql(t) -> t.failure.get.text).toMap
      if (injected.isEmpty) engine
      else new SqlExecutor {
        def execute(sql: String, catalog: Catalog, opts: ExecutionOptions): ExecutedQuery =
          injected.get(sql) match {
            case Some(message) => throw new IllegalStateException(message)
            case None => engine.execute(sql, catalog, opts)
          }
      }
    }

    override def toString: String = {
      val ins = inputs.map(i => s"${i.view}(rows=${i.rows}${if (i.broken) ",BROKEN" else ""})").mkString(", ")
      val ts = tasks.map { t =>
        val srcs = (t.inputSrcs.map(i => inputs(i).view) ++ t.taskSrcs.map(j => s"t$j")).mkString("+")
        val out = t.output.map { o =>
          s",${o.format}${if (o.spill) ",spill" else ""}${o.partitionBy.map(p => s",part=$p").getOrElse("")}"
        }.getOrElse(",mem")
        val vdep = t.validationTaskSrc.map(j => s",valOn=t$j").getOrElse("")
        val strings = Seq(
          t.name.map(a => s"name=$a"),
          t.failure.map(a => s"failure=$a"),
          Some(t.checks).filter(_.nonEmpty).map(cs => s"checks=${cs.mkString("+")}")).flatten
        val adv = if (strings.isEmpty) "" else strings.mkString(",", ",", "")
        s"${t.view}<-$srcs(${t.outcome}$out$vdep$adv)"
      }.mkString(", ")
      val dir = layout.runDir.map(d => s"runs/$d").getOrElse("out")
      val corrupt = corruption.map(c => s", corruption=$c").getOrElse("")
      s"JobCase(inputs=[$ins], tasks=[$ts], dir=$dir, runs=[${layout.executionTimes.mkString(", ")}]$corrupt)"
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
    val dag = attachOrphanInputs(rng, inputs, tasks.toVector)
    val (layout, partitioned) = drawLayout(rng.split(), dag)
    val corruption = drawCorruption(rng.split(), partitioned)
    JobCase(inputs, drawStrings(rng.split(), partitioned), layout, Some(corruption))
  }

  /** Half the cases carry [[AdversarialStrings]]: half their tasks are named
    * from the pool, nearly all their `Fails` tasks throw a string from it, and
    * half their `ValidationFails` tasks name their failing validations from it.
    * A second failing validation is either another string from the pool or the
    * first's name with a `!` after it — names that differ only in punctuation,
    * which reduce to one sample file name. */
  private def drawStrings(rng: Rng, tasks: Vector[GenTask]): Vector[GenTask] =
    if (!rng.bool(0.5)) tasks
    else tasks.map { t =>
      val named = if (rng.bool(0.5)) t.copy(name = Some(rng.oneOf(AdversarialStrings))) else t
      t.outcome match {
        case Fails if rng.bool(0.9) => named.copy(failure = Some(rng.oneOf(AdversarialStrings)))
        case ValidationFails if rng.bool(0.5) =>
          val first = rng.oneOf(AdversarialStrings)
          val second =
            if (rng.bool(0.5)) Adversarial(first.label, first.text + "!")
            else rng.oneOf(AdversarialStrings.filterNot(_ == first))
          named.copy(checks = if (rng.bool(0.5)) Vector(first, second) else Vector(first))
        case _ => named
      }
    }

  /** One corruption the case's tree can take: uniform over the kinds that apply,
    * which without a task that has an output directory is only losing
    * `job.json`. */
  private def drawCorruption(rng: Rng, tasks: Vector[GenTask]): Corruption = {
    val recorded = tasks.filter(_.output.isDefined).map(_.index)
    if (recorded.isEmpty) Corruption.DeleteManifest
    else {
      val t = rng.oneOf(recorded)
      val kinds = Vector[() => Corruption](
        () => Corruption.DeleteRecord(t),
        () => Corruption.TruncateRecord(t, rng.nextDouble()),
        () => Corruption.GarbageAfterRecord(t, rng.oneOf(Corruption.Garbage)),
        () => Corruption.DeleteManifest,
        () => Corruption.DanglingRunFile(t)) ++
        (if (recorded.length >= 2) Vector(() => {
          val other = rng.oneOf(recorded.filterNot(_ == t))
          Corruption.SwapRecords(math.min(t, other), math.max(t, other))
        }) else Vector.empty)
      rng.oneOf(kinds)()
    }
  }

  /** Where the case's runs land and when they run, and which task outputs
    * append a templated partition. */
  private def drawLayout(rng: Rng, tasks: Vector[GenTask]): (RunLayout, Vector[GenTask]) = {
    val runDir = if (rng.bool(0.4)) Some(rng.oneOf(RunDirTemplates)) else None
    val first = rng.oneOf(BaseExecutionTimes)
    val times =
      if (rng.bool(0.35)) Vector(first, first.plus(rng.oneOf(SecondRunOffsets)))
      else Vector(first)
    val partitioned = tasks.map { t =>
      t.output match {
        case Some(o) if rng.bool(0.35) => t.copy(output = Some(o.copy(partitionBy = Some(rng.oneOf(PartitionTemplates)))))
        case _ => t
      }
    }
    (RunLayout(runDir, times), partitioned)
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
