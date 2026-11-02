package com.transformer.fuzz.oracle

import com.transformer.core.{Chaos, Faults, SqlExecutorRegistry}
import com.transformer.fuzz.DagCrashChild
import com.transformer.fuzz.JobDagGen.{Fails, FailsInValidation, FailsWhileWriting, FaultPlan, JobCase, Ok, ValidationFails}
import com.transformer.job.{JobResult, JobRunRecord, TaskProgressListener, TaskResult, TaskRunRecord, TaskRunStatus, TaskStatus}
import com.transformer.sql.exec.SqlEngine

import java.nio.file.{Files, Path}
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import scala.jdk.CollectionConverters._

/** Scheduler oracle: run a generated job and decide it against what the DAG
  * alone says must happen.
  *
  * Unlike the SQL oracles, this one needs no differential — the expected outcome
  * of every task is a pure function of the generated DAG:
  *
  *   - a task whose sources are all healthy gets the outcome it was rigged with
  *     (`Succeeded` / `Failed` / `ValidationFailed`);
  *   - a task with any unhealthy upstream — a broken input, or a task that did
  *     not succeed, transitively — is `Skipped`, and nothing else.
  *
  * On top of that status oracle the run must satisfy the scheduler's structural
  * contract:
  *
  *   1. '''Completeness''' — one terminal result per declared task, in declared
  *      order, none left `Pending`.
  *   2. '''Dependency ordering''' — a task becomes eligible only after every
  *      upstream has finished: `enqueuedAt >= upstream.finishedAt` on every edge.
  *   3. '''Timestamp monotonicity''' — `enqueuedAt <= startedAt <= finishedAt`,
  *      and a `Skipped` task did no work at all (all three coincide, zero rows,
  *      no output path).
  *   4. '''Attributable skips''' — a skip reason names a real upstream.
  *   5. '''Job verdict''' — `JobResult.succeeded` iff every task succeeded,
  *      whatever the warnings say; and the warnings are exactly one per input
  *      that failed to load, in declared order, naming it and saying whether a
  *      task reads it, and nothing else (no consistency drift: the manifest and
  *      the disk agree). An input no task reads is what tells the two halves
  *      apart: its failure fails nothing, and its warning is all it leaves.
  *   6. '''Durable record''' — a task with an output directory leaves a
  *      `_run.json` in the directory its templated path renders to for this
  *      run, whose status matches the in-memory result. That file is what the
  *      GUI and the next run read, so a task that reports one status in memory
  *      and another (or none) on disk is a real defect even though the run
  *      itself looks fine.
  *   7. '''Job manifest''' — the `job.json` the run writes carries the run's
  *      verdict and warnings, lists every task in order with the same status,
  *      and points at the `_run.json` of every task that has an output
  *      directory. That manifest is the entry point the GUI and the next run
  *      load, so an entry that disagrees with the run, or a `runFile` that is
  *      not this run's record, loses detail that is on disk.
  *   8. '''Liveness''' — the run terminates. The job executes on a watchdog
  *      thread; a job still running past [[TimeoutMillis]] fails the property
  *      with a full thread dump, which is how a `Scheduler.pool` starvation
  *      deadlock surfaces here rather than hanging CI silently. Under
  *      `--config=chaos` each thread in the dump carries the last chaos points
  *      it passed ([[Chaos.lastPoints]]): an interleaving does not replay from
  *      its seed, so they are its record.
  *   9. '''Directories say what they hold''' ([[checkDirectories]]) —
  *      after any run, finished or stopped, each task's directory is absent or
  *      holds a record; a `Failed` or `Skipped` one holds no part file and no
  *      staged one; a `Succeeded` or `ValidationFailed` one holds exactly the
  *      part files its record names, and no staged one. A directory a halt
  *      caught as a run created it for its first record holds no record, and
  *      nothing but that record's temp file.
  *
  * A case that runs twice (`JobDagGen.RunLayout`) is decided run by run: the
  * second run answers to the same invariants, over directories the first run
  * may already have written.
  *
  * A case with a [[FaultPlan]] stops one run with a fault first, armed in this
  * JVM to throw (or, `crash`, halting a child JVM); then the same run runs
  * clean, and must give a clean run's statuses with every invariant above:
  * the rerun a failed run gets needs no cleanup. The stopped attempt answers
  * to what a fault allows ([[verifyStopped]]): at most the one task the fault
  * hit fails, with the tasks after it skipped; its directories say what they
  * hold; and a session reopening it shows a task whose record says `Running`
  * as unfinished, never as succeeded ([[Hydration.checkUnfinished]]).
  */
object DagSchedule {

  /** How long one generated job may take before it is called hung. Generated
    * jobs are tiny (single-digit tasks over a handful of rows); anything past
    * this is a liveness bug, not slowness. */
  val TimeoutMillis: Long = 120000L

  /** The attempt a [[FaultPlan]] stopped, before its run reran clean: the plan,
    * whether its point was reached, what the attempt returned (None when a
    * child JVM ran it), and how many tasks a session reopened unfinished
    * after it. */
  final case class Stopped(plan: FaultPlan, fired: Boolean, result: Option[JobResult], unfinished: Int)

  /** One run of a generated job: the execution time it ran at, what it
    * returned, and the attempt a fault stopped before it, if one did. */
  final case class Run(executionTime: Instant, result: JobResult, stopped: Option[Stopped] = None)

  /** Runs each of `jc`'s runs, in order, in one fresh scratch directory and
    * asserts every invariant above after each, a run a fault stops first
    * included (in a child JVM that halts, `crash`); then hands the runs and
    * the directory they wrote to `after`, and deletes the directory. */
  def run[A](jc: JobCase, crash: Boolean = false)(after: (Vector[Run], Path) => A): A = {
    val root = Files.createTempDirectory("fuzz-dag-")
    try {
      val runs = jc.layout.executionTimes.zipWithIndex.map { case (time, r) =>
        val stopped = jc.faultPlan.filter(_.run == r).map { plan =>
          if (plan.overEarlier) verify(jc, runWithWatchdog(jc, root, time), root, time)
          val (fired, result) = if (crash) haltChild(jc, root, r, plan) else throwInJvm(jc, root, time, plan)
          verifyStopped(jc, plan, fired, result, root, time)
          Stopped(plan, fired, result, Hydration.checkUnfinished(jc, root, time))
        }
        val result = runWithWatchdog(jc, root, time)
        verify(jc, result, root, time)
        Run(time, result, stopped)
      }
      after(runs, root)
    } finally deleteRecursively(root)
  }

  /** `jc`'s run at `time` with `plan` armed in this JVM. Returns whether its
    * point was reached, and what the run returned. */
  private def throwInJvm(jc: JobCase, root: Path, time: Instant, plan: FaultPlan): (Boolean, Option[JobResult]) = {
    Faults.arm(plan.arming)
    try {
      val result = runWithWatchdog(jc, root, time)
      (Faults.fired, Some(result))
    } finally Faults.disarm()
  }

  /** `jc`'s run `r` in a child JVM armed with `plan` (a halt): the case goes
    * to it as a file ([[DagCrashChild]]), never the property's own JVM, which a
    * halt would end. Returns whether the child halted; a child that ran to its
    * end returns nothing this JVM can see. */
  private def haltChild(jc: JobCase, root: Path, r: Int, plan: FaultPlan): (Boolean, Option[JobResult]) = {
    val caseFile = root.resolve("case.ser")
    val out = new java.io.ObjectOutputStream(Files.newOutputStream(caseFile))
    try out.writeObject(jc) finally out.close()
    val log = root.resolve(s"child-$r.log")
    val javaBin = java.nio.file.Paths.get(System.getProperty("java.home"), "bin", "java").toString
    val child = new ProcessBuilder(javaBin, "-cp", System.getProperty("java.class.path"),
      s"-D${Faults.PropertyName}=${plan.arming}", DagCrashChild.getClass.getName.stripSuffix("$"),
      caseFile.toString, root.toString, r.toString)
      .redirectErrorStream(true).redirectOutput(log.toFile).start()
    if (!child.waitFor(TimeoutMillis, TimeUnit.MILLISECONDS)) {
      child.destroyForcibly()
      throw new AssertionError(s"[dag-crash] the child JVM running $plan did not end\n  case = $jc")
    }
    child.exitValue match {
      case Faults.HaltStatus => (true, None)
      case 0 => (false, None)
      case other => throw new AssertionError(
        s"[dag-crash] the child JVM running $plan exited $other:\n${Files.readString(log)}\n  case = $jc")
    }
  }

  /** Runs `jc` and asserts every invariant above. Returns the runs so callers
    * can tally coverage (how many tasks actually ran, overlapped, were
    * skipped). */
  def check(jc: JobCase): Vector[Run] = run(jc)((runs, _) => runs)

  /** Run the job on its own thread so a scheduler that never terminates is
    * reported as a failure with a thread dump instead of hanging the JVM. */
  private def runWithWatchdog(jc: JobCase, root: Path, time: Instant): JobResult = {
    val job = jc.build(root, time)
    SqlEngine.init()
    val executor = jc.executor(SqlExecutorRegistry.get)
    watched(s"case = $jc", TimeoutMillis)(job.run(executor, TaskProgressListener.NoOp))
  }

  /** Run `body` on a thread of its own, and fail with a thread dump, each
    * thread's last chaos points in it, when it is still running after
    * `timeoutMillis`; `what` names it in the failure. */
  def watched[T](what: String, timeoutMillis: Long)(body: => T): T = {
    val out = new AtomicReference[Option[T]](None)
    val err = new AtomicReference[Throwable](null)
    val t = new Thread(() => {
      try out.set(Some(body))
      catch { case e: Throwable => err.set(e) }
    }, "fuzz-dag-runner")
    t.setDaemon(true)
    t.start()
    t.join(timeoutMillis)
    if (t.isAlive) throw new AssertionError(
      s"[dag-schedule] job did not terminate within ${timeoutMillis}ms — scheduler liveness bug.\n" +
        s"  $what\n${threadDump()}")
    if (err.get != null) throw new AssertionError(s"[dag-schedule] job.run() threw.\n  $what", err.get)
    out.get.get
  }

  /** What a run a fault stopped must still be. Its tasks give a clean run's
    * statuses, but where the fault failed a task — its message is the
    * fault's — that task is `Failed` and the tasks after it `Skipped`; a
    * fault in a write whose failure fails no task fails none. Each record in
    * the run's directories is the run's own and its task's status, or
    * `Running` for a task that failed or was skipped, or, where the fault
    * kept the run from writing any, the record that was there before, for a
    * task that failed or was skipped. Its directories say what they hold
    * ([[checkDirectories]]). A run in a child JVM returns nothing to check but
    * its directories. */
  private def verifyStopped(
      jc: JobCase, plan: FaultPlan, fired: Boolean, stopped: Option[JobResult], root: Path, time: Instant): Unit = {
    def ctx = s"\n  fault = $plan (${if (fired) "reached" else "not reached"})\n  case = $jc"
    stopped.foreach { result =>
      val tasks = result.tasks.toVector
      assert(result.error.isEmpty, s"[dag-fault] the stopped run reported a load error: ${result.error.get}$ctx")
      assert(tasks.map(_.taskName) == jc.tasks.map(_.displayName),
        s"[dag-fault] the stopped run's results are ${tasks.map(t => shown(t.taskName))}$ctx")
      val hit = jc.tasks.indices.filter { i =>
        tasks(i).status match {
          case TaskStatus.Failed(msg) => isFault(msg)
          case _ => false
        }
      }
      assert(hit.size <= (if (fired) 1 else 0),
        s"[dag-fault] the fault failed ${hit.map(i => jc.tasks(i).view).mkString(", ")}$ctx\n${statusTable(jc, tasks)}")
      val expected = expectedStatuses(jc, failed = hit.toSet)
      jc.tasks.indices.foreach { i =>
        assert(kind(tasks(i).status) == expected(i),
          s"[dag-fault] task ${jc.tasks(i).view}: expected ${expected(i)} after the fault, got " +
            s"${describe(tasks(i).status)}$ctx\n${statusTable(jc, tasks)}")
      }
      checkTimestamps(jc, tasks)
      checkOrdering(jc, tasks)
      checkSkipReasons(jc, tasks)
      checkStoppedRecords(jc, tasks, root, time, ctx)
      checkWarnings(jc, result)
      assert(result.succeeded == tasks.forall(_.succeeded), s"[dag-fault] the stopped run's verdict ${result.succeeded}$ctx")
    }
    checkDirectories(jc, root, time, ctx)
  }

  /** A failure's message is a fault's: what [[Faults]] throws, maybe in the
    * words of the final record's failed write. */
  def isFault(message: String): Boolean =
    message.contains("Injected fault at ") || message.contains("No space left on device")

  /** In a stopped run, each task's record is its own run's with its status,
    * or `Running` for a task that failed or was skipped; a record from before
    * the run, or none, only for a task that failed or was skipped, whose
    * record the fault kept from being written. */
  private def checkStoppedRecords(jc: JobCase, tasks: Vector[TaskResult], root: Path, time: Instant, ctx: => String): Unit =
    jc.tasks.filter(_.output.isDefined).foreach { t =>
      val dir = Path.of(jc.render(jc.outputPath(root, t).get, time))
      val r = tasks(t.index)
      val endedWithout = kind(r.status) == "Failed" || kind(r.status) == "Skipped"
      TaskRunRecord.read(dir) match {
        case Some(rec) if rec.taskName == r.taskName && rec.startedAt == r.startedAt =>
          val onDisk = runStatusName(rec.status)
          assert(onDisk == kind(r.status) || (onDisk == "Running" && endedWithout),
            s"[dag-fault] task ${t.view} ended ${describe(r.status)} but its record in $dir says $onDisk$ctx")
        case other =>
          assert(endedWithout && !other.exists(_.status == TaskRunStatus.Running),
            s"[dag-fault] task ${t.view} ended ${describe(r.status)} and its record in $dir is " +
              s"${other.map(o => s"an earlier run's ${runStatusName(o.status)}").getOrElse("missing")}$ctx")
      }
    }

  /** Invariant 9, in the directories `jc`'s run at `time` renders to. */
  private def checkDirectories(jc: JobCase, root: Path, time: Instant, ctx: => String): Unit =
    jc.tasks.filter(_.output.isDefined).foreach { t =>
      val dir = Path.of(jc.render(jc.outputPath(root, t).get, time))
      if (Files.isDirectory(dir)) {
        val files = {
          val s = Files.list(dir)
          try s.iterator().asScala.filter(Files.isRegularFile(_)).map(_.getFileName.toString).toVector.sorted
          finally s.close()
        }
        val parts = files.filter(_.startsWith("part-"))
        val staged = files.filter(_.startsWith("."))
        TaskRunRecord.read(dir) match {
          // A run creates a fresh directory as it writes its first record into
          // it; a halt in between leaves the directory with no record, holding
          // nothing, or the record's temp file.
          case None =>
            assert(files.forall(_ == TaskRunRecord.FileName + ".tmp"),
              s"[dag-schedule] task ${t.view}'s directory $dir holds ${files.mkString(", ")} and no readable record$ctx")
          case Some(rec) => runStatusName(rec.status) match {
            case "Failed" | "Skipped" =>
              assert(parts.isEmpty && staged.isEmpty,
                s"[dag-schedule] task ${t.view}'s ${runStatusName(rec.status)} record in $dir sits beside " +
                  s"${(parts ++ staged).mkString(", ")}$ctx")
            case "Succeeded" | "ValidationFailed" =>
              assert(parts == rec.outputFiles.sorted && staged.isEmpty,
                s"[dag-schedule] task ${t.view}'s ${runStatusName(rec.status)} record in $dir names " +
                  s"${rec.outputFiles.mkString(", ")} beside ${(parts ++ staged).mkString(", ")}$ctx")
            case _ => ()
          }
        }
      }
    }

  private def verify(jc: JobCase, result: JobResult, root: Path, time: Instant): Unit = {
    val tasks = result.tasks.toVector
    assert(result.error.isEmpty, s"[dag-schedule] job reported a load error: ${result.error.get}\n  case = $jc")
    assert(tasks.length == jc.tasks.length,
      s"[dag-schedule] expected ${jc.tasks.length} task results, got ${tasks.length}\n  case = $jc")

    val expected = expectedStatuses(jc)
    jc.tasks.indices.foreach { i =>
      val got = tasks(i)
      assert(got.taskName == jc.tasks(i).displayName,
        s"[dag-schedule] result $i is '${shown(got.taskName)}', expected '${shown(jc.tasks(i).displayName)}' — " +
          s"results are not in declared order\n  case = $jc")
      assert(kind(got.status) == expected(i),
        s"[dag-schedule] task ${jc.tasks(i).view}: expected ${expected(i)}, got ${describe(got.status)}\n" +
          s"  sql = ${jc.mainSql(jc.tasks(i))}\n  case = $jc\n${statusTable(jc, tasks)}")
    }

    checkInjectedFailures(jc, tasks)
    checkWriteFailures(jc, tasks)
    checkTimestamps(jc, tasks)
    checkOrdering(jc, tasks)
    checkSkipReasons(jc, tasks)
    checkRunRecords(jc, tasks, root, time)
    checkJobManifest(jc, tasks, result, root, time)
    checkDirectories(jc, root, time, s"\n  case = $jc")

    val allOk = tasks.forall(_.succeeded)
    assert(result.succeeded == allOk,
      s"[dag-schedule] JobResult.succeeded=${result.succeeded} but tasks-all-succeeded=$allOk\n" +
        s"  case = $jc\n${statusTable(jc, tasks)}\n  warnings = ${result.warnings.map(shown).mkString("; ")}")
    checkWarnings(jc, result)
  }

  /** What each warning a run reports must begin with, in order: one per input
    * that failed to load, in declared order, saying whether a task reads it.
    * The rest of a warning is why the load failed, in the file reader's words.
    * A generated run leaves the manifest and the disk agreeing, so it reports
    * no other warning. */
  def expectedWarnings(jc: JobCase): Vector[String] =
    jc.inputs.indices.filter(jc.inputs(_).broken).map { i =>
      val view = jc.inputs(i).view
      if (jc.tasks.exists(_.inputSrcs.contains(i))) s"failed to load input '$view': "
      else s"failed to load input '$view', which no task reads: "
    }.toVector

  private def checkWarnings(jc: JobCase, result: JobResult): Unit = {
    val expected = expectedWarnings(jc)
    val got = result.warnings.toVector
    assert(got.length == expected.length && got.zip(expected).forall { case (w, start) => w.startsWith(start) },
      s"[dag-schedule] warnings ${got.map(shown).mkString("[", "; ", "]")}, expected one per input that " +
        s"failed to load, beginning ${expected.mkString("[", "; ", "]")}\n  case = $jc")
  }

  /** A task configured with an output directory must leave a `_run.json` there
    * whose status matches what the run reported in memory — for every terminal
    * status, `Skipped` included (the runner stamps a skipped record so the next
    * run and the GUI can tell "not attempted" from "never configured"). */
  private def checkRunRecords(jc: JobCase, tasks: Vector[TaskResult], root: Path, time: Instant): Unit =
    jc.tasks.filter(_.output.isDefined).foreach { t =>
      val dir = Path.of(jc.render(jc.outputPath(root, t).get, time))
      val r = tasks(t.index)
      TaskRunRecord.read(dir) match {
        case None =>
          throw new AssertionError(
            s"[dag-schedule] task ${t.view} finished ${kind(r.status)} but wrote no _run.json to $dir\n" +
              s"  case = $jc")
        case Some(rec) =>
          val onDisk = runStatusName(rec.status)
          assert(onDisk == kind(r.status),
            s"[dag-schedule] task ${t.view}: in-memory status ${kind(r.status)} but _run.json says " +
              s"$onDisk\n  case = $jc")
          assert(rec.taskName == t.displayName,
            s"[dag-schedule] _run.json in $dir names task '${shown(rec.taskName)}', expected " +
              s"'${shown(t.displayName)}'\n" +
              s"  case = $jc")
      }
    }

  /** A task rigged to throw a drawn string fails with exactly that string as
    * its reason: the runner records an exception's message as it is. */
  private def checkInjectedFailures(jc: JobCase, tasks: Vector[TaskResult]): Unit =
    jc.tasks.foreach { t =>
      (t.failure, tasks(t.index).status) match {
        case (Some(f), TaskStatus.Failed(reason)) =>
          assert(reason == f.text,
            s"[dag-schedule] task ${t.view} threw the injected '$f' failure, but the run recorded " +
              s"'${shown(reason)}'\n  case = $jc")
        case _ => ()
      }
    }

  /** A task rigged to fail after it starts writing fails on the overflow it
    * was rigged with, whether while its parts are written or in its
    * validation, never on something else. */
  private def checkWriteFailures(jc: JobCase, tasks: Vector[TaskResult]): Unit =
    jc.tasks.filter(t => t.outcome == FailsWhileWriting || t.outcome == FailsInValidation).foreach { t =>
      tasks(t.index).status match {
        case TaskStatus.Failed(reason) =>
          assert(reason.startsWith("Integer overflow: "),
            s"[dag-schedule] task ${t.view} (${t.outcome}) failed with '${shown(reason)}', not its overflow\n  case = $jc")
        case _ => ()
      }
    }

  /** A string for a failure message: control characters spelled out, and a
    * long one cut short with its length. */
  def shown(s: String): String = {
    val visible = s.flatMap(c => if (c < 0x20 || c == 0x7f) f"<${c.toInt}%02x>" else c.toString)
    if (visible.length <= 120) visible else visible.take(120) + s"...(${s.length} chars)"
  }

  /** The status every task must end in, from the DAG alone; `failed`, the
    * tasks a fault failed, end `Failed` where they run. */
  def expectedStatuses(jc: JobCase, failed: Set[Int] = Set.empty): Vector[String] = {
    val brokenInput = jc.inputs.map(_.broken)
    val out = Array.fill(jc.tasks.length)("")
    jc.tasks.foreach { t =>
      val upstreamBad =
        t.inputSrcs.exists(brokenInput) || t.taskDeps.exists(j => out(j) != "Succeeded")
      out(t.index) =
        if (upstreamBad) "Skipped"
        else if (failed.contains(t.index)) "Failed"
        else t.outcome match {
          case Ok => "Succeeded"
          case Fails | FailsWhileWriting | FailsInValidation => "Failed"
          case ValidationFails => "ValidationFailed"
        }
    }
    out.toVector
  }

  private def checkTimestamps(jc: JobCase, tasks: Vector[TaskResult]): Unit =
    tasks.foreach { r =>
      assert(!r.startedAt.isBefore(r.enqueuedAt),
        s"[dag-schedule] task ${r.taskName}: startedAt ${r.startedAt} < enqueuedAt ${r.enqueuedAt}\n  case = $jc")
      assert(!r.finishedAt.isBefore(r.startedAt),
        s"[dag-schedule] task ${r.taskName}: finishedAt ${r.finishedAt} < startedAt ${r.startedAt}\n  case = $jc")
      if (kind(r.status) == "Skipped") {
        assert(r.enqueuedAt == r.startedAt && r.startedAt == r.finishedAt,
          s"[dag-schedule] skipped task ${r.taskName} has non-coincident timestamps " +
            s"(${r.enqueuedAt}/${r.startedAt}/${r.finishedAt}) — it did work it should not have\n  case = $jc")
        assert(r.rowsProduced == 0L,
          s"[dag-schedule] skipped task ${r.taskName} produced ${r.rowsProduced} rows\n  case = $jc")
        assert(r.outputPath.isEmpty,
          s"[dag-schedule] skipped task ${r.taskName} reports output ${r.outputPath.get}\n  case = $jc")
      }
    }

  /** No task may become eligible before every upstream it reads has finished. */
  private def checkOrdering(jc: JobCase, tasks: Vector[TaskResult]): Unit =
    jc.tasks.foreach { t =>
      val v = tasks(t.index)
      t.taskDeps.foreach { u =>
        val up = tasks(u)
        assert(!v.enqueuedAt.isBefore(up.finishedAt),
          s"[dag-schedule] task ${t.view} was enqueued at ${v.enqueuedAt}, before upstream " +
            s"${up.taskName} finished at ${up.finishedAt}\n  case = $jc\n${statusTable(jc, tasks)}")
      }
    }

  /** A skip must name the upstream that caused it — the reason is what the run
    * record and the GUI show, so a generic or wrong attribution is a real defect. */
  private def checkSkipReasons(jc: JobCase, tasks: Vector[TaskResult]): Unit =
    jc.tasks.foreach { t =>
      tasks(t.index).status match {
        case TaskStatus.Skipped(reason) =>
          val names = t.taskDeps.map(j => jc.tasks(j).displayName) ++ t.inputSrcs.map(j => jc.inputs(j).view)
          assert(names.exists(reason.contains),
            s"[dag-schedule] task ${t.view} skipped with reason '${shown(reason)}', which names none of its " +
              s"upstreams ${names.map(shown).mkString("[", ", ", "]")}\n  case = $jc")
        case _ => ()
      }
    }

  /** The aggregate `job.json`: one entry per task, in declared order, carrying
    * the same status, and — for a task configured with an output directory — a
    * `runFile` pointing at the `_run.json` that actually exists there. The
    * runner stamps a record for EVERY terminal status, `Failed` and `Skipped`
    * included, so the manifest must reach all of them. */
  private def checkJobManifest(
      jc: JobCase, tasks: Vector[TaskResult], result: JobResult, root: Path, time: Instant): Unit = {
    val file = Path.of(jc.render(jc.manifestPath(root), time))
    val rec: JobRunRecord = JobRunRecord.read(file).getOrElse(
      throw new AssertionError(s"[dag-schedule] no readable job manifest at $file\n  case = $jc"))
    assert(rec.tasks.length == jc.tasks.length,
      s"[dag-schedule] manifest lists ${rec.tasks.length} tasks, job has ${jc.tasks.length}\n  case = $jc")
    assert(rec.succeeded == result.succeeded,
      s"[dag-schedule] manifest says succeeded=${rec.succeeded}, run said ${result.succeeded}\n  case = $jc")
    assert(rec.warnings == result.warnings,
      s"[dag-schedule] manifest warnings ${rec.warnings.map(shown)}, run said ${result.warnings.map(shown)}\n" +
        s"  case = $jc")
    jc.tasks.foreach { t =>
      val entry = rec.tasks(t.index)
      val inMemory = kind(tasks(t.index).status)
      assert(entry.taskName == t.displayName,
        s"[dag-schedule] manifest entry ${t.index} names '${shown(entry.taskName)}', expected " +
          s"'${shown(t.displayName)}'\n" +
          s"  case = $jc")
      assert(runStatusName(entry.status) == inMemory,
        s"[dag-schedule] task ${t.view}: manifest says ${runStatusName(entry.status)}, run said " +
          s"$inMemory\n  case = $jc")
      if (t.output.isDefined) {
        val rf = entry.runFile.getOrElse(throw new AssertionError(
          s"[dag-schedule] task ${t.view} finished $inMemory with an outputFile, and its _run.json is on " +
            s"disk, but the manifest carries no runFile for it — the record is unreachable from " +
            s"job.json\n  case = $jc"))
        assert(Files.isRegularFile(Path.of(rf)),
          s"[dag-schedule] task ${t.view}: manifest runFile '$rf' does not exist\n  case = $jc")
        val own = Path.of(jc.render(jc.outputPath(root, t).get, time)).resolve(TaskRunRecord.FileName)
        assert(Path.of(rf) == own,
          s"[dag-schedule] task ${t.view}: manifest runFile '$rf' is not this run's record $own\n  case = $jc")
      } else assert(entry.runFile.isEmpty,
        s"[dag-schedule] task ${t.view} has no outputFile but the manifest points at " +
          s"'${entry.runFile.get}'\n  case = $jc")
    }
  }

  def runStatusName(s: TaskRunStatus): String = s match {
    case TaskRunStatus.Running => "Running"
    case TaskRunStatus.Succeeded => "Succeeded"
    case TaskRunStatus.Failed => "Failed"
    case TaskRunStatus.ValidationFailed => "ValidationFailed"
    case TaskRunStatus.Skipped => "Skipped"
  }

  // ---- reporting ----------------------------------------------------------

  def kind(s: TaskStatus): String = s match {
    case TaskStatus.Succeeded => "Succeeded"
    case _: TaskStatus.Failed => "Failed"
    case _: TaskStatus.ValidationFailed => "ValidationFailed"
    case _: TaskStatus.Skipped => "Skipped"
    case TaskStatus.Pending => "Pending"
  }

  private def describe(s: TaskStatus): String = s match {
    case TaskStatus.Failed(r) => s"Failed($r)"
    case TaskStatus.Skipped(r) => s"Skipped($r)"
    case TaskStatus.ValidationFailed(fs) => s"ValidationFailed(${fs.map(_.validationName).mkString(",")})"
    case other => other.toString
  }

  private def statusTable(jc: JobCase, tasks: Vector[TaskResult]): String = {
    val exp = expectedStatuses(jc)
    jc.tasks.map { t =>
      f"    ${t.view}%-4s expected=${exp(t.index)}%-16s got=${describe(tasks(t.index).status)}"
    }.mkString("\n")
  }

  private def threadDump(): String = {
    val sb = new StringBuilder("  --- thread dump ---\n")
    val all = Thread.getAllStackTraces
    all.forEach { (t, st) =>
      if (t.getName.startsWith("transformer-") || t.getName.startsWith("fuzz-dag")) {
        sb.append(s"  ${t.getName} [${t.getState}]\n")
        val points = Chaos.lastPointsOf(t)
        if (points.nonEmpty) sb.append(s"    last chaos points: ${points.mkString(", ")}\n")
        st.take(18).foreach(f => sb.append(s"      at $f\n"))
      }
    }
    if (Chaos.enabled) sb.append(s"  (chaos seed ${Chaos.seed.get}, ${Chaos.passed} points passed)\n")
    sb.toString
  }

  /** Where a run's timestamps say two tasks overlapped in wall-clock time — the
    * coverage signal that the scheduler really is running independent branches
    * concurrently rather than one at a time. */
  def hasOverlap(tasks: Seq[TaskResult]): Boolean = {
    val ran = tasks.filter(t => kind(t.status) != "Skipped").toVector
    ran.combinations(2).exists { case Vector(a, b) => overlaps(a, b); case _ => false }
  }

  private def overlaps(a: TaskResult, b: TaskResult): Boolean =
    a.startedAt.isBefore(b.finishedAt) && b.startedAt.isBefore(a.finishedAt)

  private def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }

  private def assert(cond: Boolean, msg: => String): Unit =
    if (!cond) throw new AssertionError(msg)
}
