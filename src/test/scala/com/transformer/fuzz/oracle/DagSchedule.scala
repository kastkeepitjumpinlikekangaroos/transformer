package com.transformer.fuzz.oracle

import com.transformer.fuzz.JobDagGen
import com.transformer.fuzz.JobDagGen.{Fails, JobCase, Ok, ValidationFails}
import com.transformer.job.{JobResult, JobRunRecord, TaskResult, TaskRunRecord, TaskRunStatus, TaskStatus}

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference

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
  *   5. '''Job verdict''' — `JobResult.succeeded` iff every task succeeded, with
  *      no consistency warning (the manifest and the disk agree).
  *   6. '''Durable record''' — a task with an output directory leaves a
  *      `_run.json` whose status matches the in-memory result. That file is what
  *      the GUI and the next run read, so a task that reports one status in
  *      memory and another (or none) on disk is a real defect even though the
  *      run itself looks fine.
  *   7. '''Job manifest''' — the `job.json` the run writes lists every task in
  *      order with the same status, and points at the `_run.json` of every task
  *      that has an output directory. That manifest is the entry point the GUI
  *      and the next run load, so an entry that disagrees with the run, or a
  *      `runFile` that does not resolve, loses detail that is on disk.
  *   8. '''Liveness''' — the run terminates. The job executes on a watchdog
  *      thread; a job still running past [[TimeoutMillis]] fails the property
  *      with a full thread dump, which is how a `Scheduler.pool` starvation
  *      deadlock surfaces here rather than hanging CI silently.
  */
object DagSchedule {

  /** How long one generated job may take before it is called hung. Generated
    * jobs are tiny (single-digit tasks over a handful of rows); anything past
    * this is a liveness bug, not slowness. */
  val TimeoutMillis: Long = 120000L

  /** Runs `jc` and asserts every invariant above. Returns the run's task results
    * so callers can tally coverage (how many tasks actually ran, overlapped,
    * were skipped). */
  def check(jc: JobCase): JobResult = {
    val root = Files.createTempDirectory("fuzz-dag-")
    try {
      val result = runWithWatchdog(jc, root)
      verify(jc, result, root)
      result
    } finally deleteRecursively(root)
  }

  /** Run the job on its own thread so a scheduler that never terminates is
    * reported as a failure with a thread dump instead of hanging the JVM. */
  private def runWithWatchdog(jc: JobCase, root: Path): JobResult = {
    val job = jc.build(root)
    val out = new AtomicReference[JobResult](null)
    val err = new AtomicReference[Throwable](null)
    val t = new Thread(() => {
      try out.set(job.run())
      catch { case e: Throwable => err.set(e) }
    }, "fuzz-dag-runner")
    t.setDaemon(true)
    t.start()
    t.join(TimeoutMillis)
    if (t.isAlive) throw new AssertionError(
      s"[dag-schedule] job did not terminate within ${TimeoutMillis}ms — scheduler liveness bug.\n" +
        s"  case = $jc\n${threadDump()}")
    if (err.get != null) throw new AssertionError(s"[dag-schedule] job.run() threw.\n  case = $jc", err.get)
    out.get
  }

  private def verify(jc: JobCase, result: JobResult, root: Path): Unit = {
    val tasks = result.tasks.toVector
    assert(result.error.isEmpty, s"[dag-schedule] job reported a load error: ${result.error.get}\n  case = $jc")
    assert(tasks.length == jc.tasks.length,
      s"[dag-schedule] expected ${jc.tasks.length} task results, got ${tasks.length}\n  case = $jc")

    val expected = expectedStatuses(jc)
    jc.tasks.indices.foreach { i =>
      val got = tasks(i)
      assert(got.taskName == jc.tasks(i).view,
        s"[dag-schedule] result $i is '${got.taskName}', expected '${jc.tasks(i).view}' — " +
          s"results are not in declared order\n  case = $jc")
      assert(kind(got.status) == expected(i),
        s"[dag-schedule] task ${jc.tasks(i).view}: expected ${expected(i)}, got ${describe(got.status)}\n" +
          s"  sql = ${jc.mainSql(jc.tasks(i))}\n  case = $jc\n${statusTable(jc, tasks)}")
    }

    checkTimestamps(jc, tasks)
    checkOrdering(jc, tasks)
    checkSkipReasons(jc, tasks)
    checkRunRecords(jc, tasks, root)
    checkJobManifest(jc, tasks, result, root)

    val allOk = tasks.forall(_.succeeded)
    assert(result.succeeded == allOk,
      s"[dag-schedule] JobResult.succeeded=${result.succeeded} but tasks-all-succeeded=$allOk\n" +
        s"  case = $jc\n${statusTable(jc, tasks)}")
    assert(result.warnings.isEmpty,
      s"[dag-schedule] run reported consistency warnings (manifest disagrees with disk): " +
        s"${result.warnings.mkString("; ")}\n  case = $jc")
  }

  /** A task configured with an output directory must leave a `_run.json` there
    * whose status matches what the run reported in memory — for every terminal
    * status, `Skipped` included (the runner stamps a skipped record so the next
    * run and the GUI can tell "not attempted" from "never configured"). */
  private def checkRunRecords(jc: JobCase, tasks: Vector[TaskResult], root: Path): Unit =
    jc.tasks.filter(_.output.isDefined).foreach { t =>
      val dir = root.resolve(t.view)
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
          assert(rec.taskName == t.view,
            s"[dag-schedule] _run.json in $dir names task '${rec.taskName}', expected '${t.view}'\n" +
              s"  case = $jc")
      }
    }

  /** The status every task must end in, from the DAG alone. */
  def expectedStatuses(jc: JobCase): Vector[String] = {
    val brokenInput = jc.inputs.map(_.broken)
    val out = Array.fill(jc.tasks.length)("")
    jc.tasks.foreach { t =>
      val upstreamBad =
        t.inputSrcs.exists(brokenInput) || t.taskDeps.exists(j => out(j) != "Succeeded")
      out(t.index) =
        if (upstreamBad) "Skipped"
        else t.outcome match {
          case Ok => "Succeeded"
          case Fails => "Failed"
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
          val names = t.taskDeps.map(j => jc.tasks(j).view) ++ t.inputSrcs.map(j => jc.inputs(j).view)
          assert(names.exists(reason.contains),
            s"[dag-schedule] task ${t.view} skipped with reason '$reason', which names none of its " +
              s"upstreams ${names.mkString("[", ", ", "]")}\n  case = $jc")
        case _ => ()
      }
    }

  /** The aggregate `job.json`: one entry per task, in declared order, carrying
    * the same status, and — for a task configured with an output directory — a
    * `runFile` pointing at the `_run.json` that actually exists there. The
    * runner stamps a record for EVERY terminal status, `Failed` and `Skipped`
    * included, so the manifest must reach all of them. */
  private def checkJobManifest(
      jc: JobCase, tasks: Vector[TaskResult], result: JobResult, root: Path): Unit = {
    val file = root.resolve(JobDagGen.ManifestFile)
    val rec: JobRunRecord = JobRunRecord.read(file).getOrElse(
      throw new AssertionError(s"[dag-schedule] no readable job manifest at $file\n  case = $jc"))
    assert(rec.tasks.length == jc.tasks.length,
      s"[dag-schedule] manifest lists ${rec.tasks.length} tasks, job has ${jc.tasks.length}\n  case = $jc")
    assert(rec.succeeded == result.succeeded,
      s"[dag-schedule] manifest says succeeded=${rec.succeeded}, run said ${result.succeeded}\n  case = $jc")
    jc.tasks.foreach { t =>
      val entry = rec.tasks(t.index)
      val inMemory = kind(tasks(t.index).status)
      assert(entry.taskName == t.view,
        s"[dag-schedule] manifest entry ${t.index} names '${entry.taskName}', expected '${t.view}'\n" +
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
      } else assert(entry.runFile.isEmpty,
        s"[dag-schedule] task ${t.view} has no outputFile but the manifest points at " +
          s"'${entry.runFile.get}'\n  case = $jc")
    }
  }

  private def runStatusName(s: TaskRunStatus): String = s match {
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
        st.take(18).foreach(f => sb.append(s"      at $f\n"))
      }
    }
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
