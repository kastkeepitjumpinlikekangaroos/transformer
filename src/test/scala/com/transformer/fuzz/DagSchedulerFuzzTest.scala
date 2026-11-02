package com.transformer.fuzz

import com.transformer.core.{Faults, Scheduler, Spill}
import com.transformer.fuzz.JobDagGen.JobCase
import com.transformer.fuzz.oracle.{DagSchedule, Hydration}
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

/** Property-based gate over the job-level DAG scheduler — the layer above SQL
  * execution, where `DataJob.runUnifiedDag` decides what may run and when.
  *
  * The other fuzz targets generate one query and ask whether the engine computes
  * it correctly. This one generates a whole job — inputs plus a DAG of tasks,
  * some rigged to fail, some to fail a validation, some reading an input that
  * cannot load — and asks whether the scheduler *orchestrated* it correctly.
  * Every claim it checks is decided by the DAG alone, so there is no reference
  * implementation to disagree with (see [[DagSchedule]] for the list):
  * per-task status, dependency ordering by timestamp, terminal-state
  * completeness, skip attribution, the job verdict, and liveness.
  *
  * Then it reopens the finished runs the way the GUI does, in a `JobSession`
  * on the same job as a directory, and holds what the session rebuilds to what
  * the runs returned ([[Hydration]]): the read side of the records the
  * scheduler property checks are written. Last it damages the tree — a record
  * deleted, cut short or followed by garbage, `job.json` deleted or pointing
  * nowhere, two records swapped — and reopens it again, where the session must
  * show exactly what survived, rebuild the rest from `job.json`, and say what it
  * could not read.
  *
  * Liveness is the reason this target exists in the same repo as the sharded
  * exchange work. Every task body fans its own nested work onto the shared
  * [[com.transformer.core.Scheduler]] pool that the DAG scheduler is already
  * using for the tasks themselves, so a job with more concurrently-eligible
  * tasks than pool threads is exactly the nested-submission shape that has
  * deadlocked this pool before. A job that stops making progress fails here with
  * a thread dump instead of hanging.
  *
  * Budgets are small — each case starts a JVM-wide job run with real file I/O —
  * and scale with `-Dfuzz.seeds=N` / `FUZZ_SEEDS=N` against
  * `dag_scheduler_fuzz_campaign`.
  */
class DagSchedulerFuzzTest {

  /** Default per-property budget. Each case writes input CSVs, runs a whole job,
    * and deletes its scratch tree, so this is far heavier per seed than the SQL
    * properties. */
  private val DefaultDagSeeds: Int = 40

  // Generated tasks may enable spill at a 1-byte threshold; keep those files out
  // of the default spill location.
  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setSpillDir(): Unit = {
    tmpRoot = Files.createTempDirectory("dag-spill-")
    System.setProperty(Spill.SpillDirProperty, tmpRoot.resolve("transformer-spill").toString)
  }

  @After def restoreSpillDir(): Unit = {
    if (originalDirProp == null) System.clearProperty(Spill.SpillDirProperty)
    else System.setProperty(Spill.SpillDirProperty, originalDirProp)
    deleteRecursively(tmpRoot)
  }

  private def deleteRecursively(p: Path): Unit = {
    if (p == null || !Files.exists(p)) return
    if (Files.isDirectory(p)) {
      val s = Files.list(p)
      try { val it = s.iterator(); while (it.hasNext) deleteRecursively(it.next()) }
      finally s.close()
    }
    try Files.deleteIfExists(p) catch { case _: java.io.IOException => () }
  }

  // ---- the property ---------------------------------------------------------

  @Test def fuzzDagSchedule(): Unit =
    Props.forAll[JobCase](
      name = "dag-schedule",
      gen = JobDagGen.generate,
      shrink = Shrinker.jobCase,
      count = Props.seedCountOr(DefaultDagSeeds)
    ) { jc => DagSchedule.run(jc)((runs, root) => Hydration.check(jc, runs, root)); () }

  // ---- corpus health --------------------------------------------------------

  /** Coverage guard. The status oracle is only as good as the statuses the
    * corpus actually reaches: a generator drift that stopped producing broken
    * inputs, failing tasks, or failing validations would leave the skip
    * propagation unchecked while the property still passed. This asserts each
    * terminal status appears, that DAGs get deep enough for a *transitive* skip
    * (a task skipped by an upstream that was itself skipped), and that the
    * scheduler is really overlapping independent branches rather than running
    * them one at a time. */
  @Test def generatorCoversScheduleShapes(): Unit = {
    val n = Props.seedCountOr(60)
    var succeeded, failed, validationFailed, skipped, transitiveSkips, brokenInputs, deepDags = 0
    var validationEdges, overlapped = 0
    var i = 0
    while (i < n) {
      val jc = JobDagGen.generate(new Rng(Props.baseSeed + i))
      val statuses = DagSchedule.expectedStatuses(jc)
      succeeded += statuses.count(_ == "Succeeded")
      failed += statuses.count(_ == "Failed")
      validationFailed += statuses.count(_ == "ValidationFailed")
      skipped += statuses.count(_ == "Skipped")
      // A broken input some task reads, so it skips that task.
      if (jc.tasks.exists(_.inputSrcs.exists(jc.inputs(_).broken))) brokenInputs += 1
      if (jc.tasks.exists(_.taskDeps.exists(j => jc.tasks(j).taskDeps.nonEmpty))) deepDags += 1
      // A dependency acquired through a validation's FROM rather than the main
      // SQL's — a distinct edge source in `TaskDag.build`.
      if (jc.tasks.exists(t => t.validationTaskSrc.exists(!t.taskSrcs.contains(_)))) validationEdges += 1
      // A skip whose own cause was a skip — the recursive propagation path.
      if (jc.tasks.exists(t => statuses(t.index) == "Skipped" &&
        t.taskDeps.exists(j => statuses(j) == "Skipped"))) transitiveSkips += 1
      i += 1
    }
    // Overlap needs a real run, so sample a handful of clean cases rather than
    // every seed.
    var seed = Props.baseSeed
    var sampled = 0
    while (sampled < 12 && seed < Props.baseSeed + 200) {
      val jc = JobDagGen.generate(new Rng(seed))
      if (jc.tasks.length >= 4 && DagSchedule.expectedStatuses(jc).forall(_ == "Succeeded")) {
        sampled += 1
        if (DagSchedule.check(jc).exists(run => DagSchedule.hasOverlap(run.result.tasks))) overlapped += 1
      }
      seed += 1
    }
    println(s"[dag-schedule] over $n seeds: succeeded=$succeeded failed=$failed " +
      s"validationFailed=$validationFailed skipped=$skipped transitiveSkipCases=$transitiveSkips " +
      s"brokenInputCases=$brokenInputs deepDagCases=$deepDags " +
      s"validationEdgeCases=$validationEdges overlappingRuns=$overlapped/$sampled")
    assertTrue(s"succeeded=$succeeded", succeeded > n / 2)
    assertTrue(s"failed=$failed", failed > 0)
    assertTrue(s"validationFailed=$validationFailed", validationFailed > 0)
    assertTrue(s"skipped=$skipped", skipped > 0)
    assertTrue(s"transitiveSkipCases=$transitiveSkips", transitiveSkips > 0)
    assertTrue(s"brokenInputCases=$brokenInputs", brokenInputs > 0)
    assertTrue(s"deepDagCases=$deepDags", deepDags > 0)
    assertTrue(s"validationEdgeCases=$validationEdges", validationEdges > 0)
    // Two tasks overlap only where two threads run them; on one thread
    // (`--config=serial`) a task overlaps another only when a wait of its
    // happens to run the other, which nothing makes it do.
    if (Scheduler.parallelism > 1)
      assertTrue(s"no run overlapped two tasks in wall-clock time ($sampled sampled)", overlapped > 0)
  }

  /** Coverage guard for input failures. The run's warnings name every input
    * that failed to load, and its verdict ignores an input no task reads, so
    * the corpus must reach: inputs no task reads, broken and healthy; a case
    * where every task succeeds beside such a broken input, the only shape in
    * which a warning meets a succeeded run; and a task reading two broken
    * inputs, whose skip reason names one of them and whose warnings name both. */
  @Test def generatorCoversInputFailures(): Unit = {
    val n = Props.seedCountOr(2000)
    var unreadHealthy, unreadBroken, succeededWithWarning, twoBrokenReads = 0
    var i = 0
    while (i < n) {
      val jc = JobDagGen.generate(new Rng(Props.baseSeed + i))
      val read = jc.tasks.iterator.flatMap(_.inputSrcs).toSet
      jc.inputs.indices.filterNot(read).foreach { k =>
        if (jc.inputs(k).broken) unreadBroken += 1 else unreadHealthy += 1
      }
      if (DagSchedule.expectedStatuses(jc).forall(_ == "Succeeded") && DagSchedule.expectedWarnings(jc).nonEmpty)
        succeededWithWarning += 1
      if (jc.tasks.exists(_.inputSrcs.count(jc.inputs(_).broken) >= 2)) twoBrokenReads += 1
      i += 1
    }
    println(s"[dag-schedule] input failures over $n seeds: unreadHealthy=$unreadHealthy " +
      s"unreadBroken=$unreadBroken succeededWithWarning=$succeededWithWarning twoBrokenReads=$twoBrokenReads")
    // Bounds near a third of the rates measured over the default 2000 seeds:
    // about 260 unread inputs of each kind, 130 succeeded cases with a warning,
    // and 27 tasks reading two broken inputs.
    assertTrue(s"unreadHealthy=$unreadHealthy over $n", unreadHealthy > n / 24)
    assertTrue(s"unreadBroken=$unreadBroken over $n", unreadBroken > n / 24)
    assertTrue(s"succeededWithWarning=$succeededWithWarning over $n", succeededWithWarning > n / 45)
    assertTrue(s"twoBrokenReads=$twoBrokenReads over $n", twoBrokenReads > n / 220)
  }

  /** Reopens generated cases from the base seed — those `take` accepts — until
    * `enough` holds of what their sessions showed, or `cap` seeds have passed.
    * A rare shape is sought rather than hoped for in a fixed sample, which can
    * miss it at another base seed; a shape the corpus no longer produces still
    * fails the guard at the cap. Returns what the sessions showed and how many
    * cases ran. */
  private def sessionsUntil(cap: Int, take: JobCase => Boolean)(
      enough: Hydration.Report => Boolean): (Hydration.Report, Int) = {
    val seen = new Hydration.Report
    var seed = Props.baseSeed
    var ran = 0
    while (!enough(seen) && seed < Props.baseSeed + cap) {
      val jc = JobDagGen.generate(new Rng(seed))
      if (take(jc)) {
        ran += 1
        seen.add(DagSchedule.run(jc)((runs, root) => Hydration.check(jc, runs, root)))
      }
      seed += 1
    }
    (seen, ran)
  }

  /** Tasks of a status rebuilt from their own record, by any route: the record
    * `job.json` points at, the task's own record after the pointer failed, or
    * the one discovery finds. */
  private def fromRecord(seen: Hydration.Report, status: String): Int =
    seen.sources(("record", status)) + seen.sources(("own record", status)) + seen.sources(("discovered", status))

  private val Statuses: Seq[String] = Seq("Succeeded", "Failed", "ValidationFailed", "Skipped")

  /** Coverage guard for the hydration property. Hydration is only checked where
    * the corpus puts it: a session rebuilds a task from its record only when the
    * task has an output directory, and from job.json's entry when it has none
    * (or, once the tree is damaged, when its record is unusable), and only a
    * status the run reached; partition pickers list two runs only when a
    * templated path renders twice; the run picker lists two only when a
    * templated run directory does. The first half counts what the generated
    * cases imply (cheap, many seeds); the second reopens cases until the
    * sessions have shown every source and status and been opened every way. */
  @Test def generatorCoversHydrationShapes(): Unit = {
    val n = Props.seedCountOr(2000)
    val root = Path.of("/scratch")
    val recordBacked, memoryOnly = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var templatedRunDir, partitioned, multiPartition, multiRun, rewritten, backfill = 0
    val depths = scala.collection.mutable.Map.empty[Int, Int].withDefaultValue(0)
    var i = 0
    while (i < n) {
      val jc = JobDagGen.generate(new Rng(Props.baseSeed + i))
      val statuses = DagSchedule.expectedStatuses(jc)
      val times = jc.layout.executionTimes
      jc.tasks.foreach { t =>
        if (t.output.isDefined) recordBacked(statuses(t.index)) += 1 else memoryOnly(statuses(t.index)) += 1
        t.output.flatMap(_.partitionBy).foreach { p =>
          partitioned += 1
          depths(p.count(_ == '/') + 1) += 1
        }
        jc.outputPath(root, t).foreach { p =>
          val dirs = times.map(jc.render(p, _)).distinct.length
          if (dirs >= 2) multiPartition += 1
          if (times.length >= 2 && dirs == 1) rewritten += 1
        }
      }
      if (jc.layout.runDir.isDefined) templatedRunDir += 1
      if (times.map(jc.render(jc.manifestPath(root), _)).distinct.length >= 2) multiRun += 1
      if (times.length >= 2 && times(1).isBefore(times(0))) backfill += 1
      i += 1
    }
    // Real runs, until the sessions have shown every source the property
    // compares: a task of each status from its record, one from job.json's
    // entry, two partitions, two runs, and all three ways of opening.
    val (seen, sampled) = sessionsUntil(600, _ => true) { r =>
      Statuses.forall(fromRecord(r, _) > 0) && r.sources(("summary", "ValidationFailed")) > 0 &&
        r.multiPartitionTasks > 0 && r.multiRunSessions > 0 && r.openings("picked") > 0 && r.openings("earlier") > 0
    }
    println(s"[hydration] over $n seeds: recordBacked=${recordBacked.toMap} memoryOnly=${memoryOnly.toMap} " +
      s"templatedRunDirCases=$templatedRunDir partitionedTasks=$partitioned partitionDepths=${depths.toMap} " +
      s"multiPartitionTasks=$multiPartition multiRunCases=$multiRun rewrittenTasks=$rewritten " +
      s"backfillCases=$backfill")
    println(s"[hydration] over $sampled sampled runs: sources=${seen.sources.toMap} " +
      s"openings=${seen.openings.toMap} multiPartitionTasks=${seen.multiPartitionTasks} " +
      s"multiRunSessions=${seen.multiRunSessions} problems=${seen.problems.toMap}")
    // Bounds sit near a third of the rates measured over the default 2000
    // seeds (the message prints the counts), so a collapse fails and a drift
    // of a few percent does not.
    Seq("Succeeded", "Failed", "ValidationFailed", "Skipped").foreach { st =>
      assertTrue(s"record-backed $st tasks=${recordBacked(st)} over $n", recordBacked(st) > n / 25)
      assertTrue(s"memory-only $st tasks=${memoryOnly(st)} over $n", memoryOnly(st) > n / 25)
    }
    assertTrue(s"templatedRunDirCases=$templatedRunDir over $n", templatedRunDir > n / 8)
    Seq(1, 2, 3).foreach(dpt => assertTrue(s"partitions $dpt deep=${depths(dpt)} over $n", depths(dpt) > n / 8))
    assertTrue(s"multiPartitionTasks=$multiPartition over $n", multiPartition > n / 4)
    assertTrue(s"multiRunCases=$multiRun over $n", multiRun > n / 20)
    assertTrue(s"rewrittenTasks=$rewritten over $n", rewritten > n / 8)
    assertTrue(s"backfillCases=$backfill over $n", backfill > n / 25)
    Statuses.foreach { st =>
      assertTrue(s"sessions rebuilt no $st task from its record: ${seen.sources.toMap}", fromRecord(seen, st) > 0)
    }
    assertTrue(s"sessions rebuilt no ValidationFailed task from job.json's entry: ${seen.sources.toMap}",
      seen.sources(("summary", "ValidationFailed")) > 0)
    assertTrue(s"no session listed two partitions: ${seen.multiPartitionTasks}", seen.multiPartitionTasks > 0)
    assertTrue(s"no session listed two runs: ${seen.multiRunSessions}", seen.multiRunSessions > 0)
    assertTrue(s"no run was picked: ${seen.openings.toMap}", seen.openings("picked") > 0)
    assertTrue(s"no session opened at an earlier time: ${seen.openings.toMap}", seen.openings("earlier") > 0)
  }

  /** Coverage guard for the corruption mode: every corruption is drawn (the
    * first half counts the generated cases), and over real runs every one is
    * applied and reopened, and the sessions over the damaged trees reach every
    * rule the property decides: a task rebuilt from its `job.json` entry because
    * its record is missing, unreadable, or another task's; a task's own record
    * found after its `runFile` led nowhere; a record found on disk once
    * `job.json` is gone, with the warning that says so; and a task nothing on
    * disk names. */
  @Test def generatorCoversCorruptions(): Unit = {
    val n = Props.seedCountOr(2000)
    val drawn = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var i = 0
    while (i < n) {
      val jc = JobDagGen.generate(new Rng(Props.baseSeed + i))
      if (jc.corruptionApplies) drawn(jc.corruption.get.name) += 1
      i += 1
    }
    val problems = Seq("missing", "unreadable", "names task")
    val (seen, ran) = sessionsUntil(600, _ => true) { r =>
      JobDagGen.Corruption.Names.forall(r.corruptions(_) > 0) && problems.forall(r.problems(_) > 0) &&
        r.sources.keys.exists(_._1 == "own record") && r.sources.keys.exists(_._1 == "discovered") &&
        r.jobJsonWarnings > 0 && r.sources(("unknown", "Pending")) > 0
    }
    println(s"[hydration] corruptions drawn over $n seeds: ${drawn.toMap}")
    println(s"[hydration] over $ran corrupted runs: applied=${seen.corruptions.toMap} sources=${seen.sources.toMap} " +
      s"rebuilt from job.json because the record is=${seen.problems.toMap} jobJsonWarnings=${seen.jobJsonWarnings}")
    JobDagGen.Corruption.Names.foreach { name =>
      assertTrue(s"'$name' drawn ${drawn(name)} times over $n", drawn(name) > n / 12)
      assertTrue(s"'$name' never applied and reopened: ${seen.corruptions.toMap}", seen.corruptions(name) > 0)
    }
    problems.foreach { p =>
      assertTrue(s"no task was rebuilt from job.json because its record is '$p': ${seen.problems.toMap}",
        seen.problems(p) > 0)
    }
    assertTrue(s"no task fell back to its own record after its runFile led nowhere: ${seen.sources.toMap}",
      seen.sources.keys.exists(_._1 == "own record"))
    assertTrue(s"no session found a record on disk without job.json: ${seen.sources.toMap}",
      seen.sources.keys.exists(_._1 == "discovered"))
    assertTrue(s"no session warned of a missing job.json: ${seen.jobJsonWarnings}", seen.jobJsonWarnings > 0)
    assertTrue(s"no task was left unknown: ${seen.sources.toMap}", seen.sources(("unknown", "Pending")) > 0)
  }

  /** Coverage guard for the adversarial strings. Over the generated cases,
    * every string in `JobDagGen.AdversarialStrings` is drawn as a task's name,
    * as the message of a task that will fail (not one an upstream skips), and as
    * the name of a validation that will fail; and two failing validations of one
    * task draw names whose sample files would share a name. Over real runs,
    * every path a string takes back is taken — a task name through
    * `job.json` and through a record, a failure's message through both, a
    * validation's name through a record — and every string comes back by at
    * least one. */
  @Test def generatorCoversAdversarialStrings(): Unit = {
    val n = Props.seedCountOr(2000)
    val drawn = scala.collection.mutable.Map.empty[(String, String), Int].withDefaultValue(0)
    var sharedSampleNames, adversarialCases = 0
    var i = 0
    while (i < n) {
      val jc = JobDagGen.generate(new Rng(Props.baseSeed + i))
      val statuses = DagSchedule.expectedStatuses(jc)
      if (jc.tasks.exists(t => t.name.isDefined || t.failure.isDefined || t.checks.nonEmpty)) adversarialCases += 1
      jc.tasks.foreach { t =>
        t.name.foreach(a => drawn(("name", a.label)) += 1)
        if (statuses(t.index) == "Failed") t.failure.foreach(a => drawn(("failure", a.label)) += 1)
        if (statuses(t.index) == "ValidationFailed") {
          t.checks.foreach(a => drawn(("validation name", a.label)) += 1)
          if (t.checks.length == 2 && t.checks.map(c =>
            com.transformer.job.TaskRunRecord.validationSampleFileName(c.text)).distinct.length == 1) sharedSampleNames += 1
        }
      }
      i += 1
    }
    val paths = Seq("task name through job.json", "task name through a record", "failure through job.json",
      "failure through a record", "validation name through a record")
    val (seen, sampled) =
      sessionsUntil(1500, jc => jc.tasks.exists(t => t.name.isDefined || t.failure.isDefined || t.checks.nonEmpty)) { r =>
        JobDagGen.AdversarialStrings.forall(a => r.strings.keys.exists(_._2 == a.label)) &&
          paths.forall(path => r.strings.keys.exists(_._1 == path))
      }
    val consumers = Seq("name", "failure", "validation name")
    println(s"[hydration] adversarial strings over $n seeds ($adversarialCases cases): " +
      consumers.map(c => s"$c=${JobDagGen.AdversarialStrings.map(a => a.label -> drawn((c, a.label))).toMap}").mkString(" ") +
      s" sharedSampleNames=$sharedSampleNames")
    println(s"[hydration] adversarial strings read back over $sampled runs: ${seen.strings.toMap}")
    // Bounds near a third of the rates measured over the default 2000 seeds:
    // about 230 names, 20 messages and 15 validation names per string, and 33
    // pairs of validation names that share a sample file name.
    val bound = Map("name" -> n / 25, "failure" -> n / 400, "validation name" -> n / 400)
    JobDagGen.AdversarialStrings.foreach { a =>
      consumers.foreach { c =>
        assertTrue(s"'${a.label}' drawn as a $c ${drawn((c, a.label))} times over $n", drawn((c, a.label)) > bound(c))
      }
      assertTrue(s"'${a.label}' never read back: ${seen.strings.toMap}", seen.strings.keys.exists(_._2 == a.label))
    }
    assertTrue(s"sharedSampleNames=$sharedSampleNames over $n", sharedSampleNames > n / 200)
    paths.foreach { path =>
      assertTrue(s"no string read back as a $path: ${seen.strings.toMap}", seen.strings.keys.exists(_._1 == path))
    }
  }

  /** Coverage guard for the failures after a write starts (plans/testing/06).
    * The corpus must draw tasks that fail while their parts are written and
    * tasks whose validation overflows, each where it runs (no upstream skips
    * it) and has an output directory; and over real runs each must fail for
    * real, on its overflow, which `DagSchedule` holds it to. */
  @Test def generatorCoversWriteFailures(): Unit = {
    val n = Props.seedCountOr(2000)
    val drawn = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var i = 0
    while (i < n) {
      val jc = JobDagGen.generate(new Rng(Props.baseSeed + i))
      val statuses = DagSchedule.expectedStatuses(jc)
      jc.tasks.foreach { t =>
        if ((t.outcome == JobDagGen.FailsWhileWriting || t.outcome == JobDagGen.FailsInValidation) &&
          statuses(t.index) == "Failed") drawn(t.outcome.toString) += 1
      }
      i += 1
    }
    val outcomes = Seq("FailsWhileWriting", "FailsInValidation")
    val (seen, ran) = sessionsUntil(600, _.tasks.exists(t => outcomes.contains(t.outcome.toString))) { r =>
      outcomes.forall(r.writeFailures(_) > 0)
    }
    println(s"[dag-schedule] write failures drawn where they run over $n seeds: ${drawn.toMap}; failed for real " +
      s"over $ran runs: ${seen.writeFailures.toMap}")
    // Bounds near a third of the rates measured over the default 2000 seeds:
    // 47 tasks failing while writing and 57 failing in a validation.
    outcomes.foreach { o =>
      assertTrue(s"$o drawn ${drawn(o)} times over $n", drawn(o) > n / 125)
      assertTrue(s"$o never failed for real: ${seen.writeFailures.toMap}", seen.writeFailures(o) > 0)
    }
  }

  /** Coverage guard for the fault mode (plans/testing/06). Every fault point
    * is drawn, and over real runs every one is reached, so each stops a run
    * that then reruns clean; and some stopped run leaves a `Running` record a
    * session reopens as unfinished. */
  @Test def generatorCoversFaultPoints(): Unit = {
    val n = Props.seedCountOr(2000)
    val drawn = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    var i = 0
    while (i < n) {
      JobDagGen.generate(new Rng(Props.baseSeed + i)).faultPlan.foreach(f => drawn(f.arming.point) += 1)
      i += 1
    }
    val (seen, ran) = sessionsUntil(3000, _.faultPlan.isDefined) { r =>
      Faults.Points.forall(p => r.faults((p, true)) > 0) && r.unfinished > 0
    }
    println(s"[dag-schedule] fault points drawn over $n seeds: ${drawn.toMap}")
    println(s"[dag-schedule] over $ran stopped runs: reached=${Faults.Points.map(p => p -> seen.faults((p, true))).toMap} " +
      s"notReached=${Faults.Points.map(p => p -> seen.faults((p, false))).toMap} unfinished=${seen.unfinished}")
    // Bounds near a third of the rates measured over the default 2000 seeds:
    // each point drawn 47 to 69 times; all ten reached within 57 stopped runs.
    Faults.Points.foreach { p =>
      assertTrue(s"fault point $p drawn ${drawn(p)} times over $n", drawn(p) > n / 125)
      assertTrue(s"fault point $p never reached over $ran stopped runs", seen.faults((p, true)) > 0)
    }
    assertTrue(s"no stopped run left a task a session reopened unfinished", seen.unfinished > 0)
  }

  /** The seed is the repro key: the same seed must rebuild the same job. */
  @Test def sameSeedReproduces(): Unit = {
    val a = JobDagGen.generate(new Rng(4242L))
    val b = JobDagGen.generate(new Rng(4242L))
    assertEquals(a.toString, b.toString)
  }

  /** The shrinker must terminate and strictly reduce, or `Props.minimize` loops
    * until its step cap. Measure = tasks + edges + injected failures + output
    * config + the run layout + the fault. */
  @Test def shrinkerTerminatesAndReduces(): Unit = {
    def measure(jc: JobCase): Int =
      jc.tasks.length + jc.tasks.map(_.sources).sum +
        jc.tasks.count(_.outcome != JobDagGen.Ok) + jc.inputs.count(_.broken) +
        jc.tasks.count(_.output.isDefined) + jc.tasks.count(_.passingValidation) +
        jc.tasks.count(_.validationTaskSrc.isDefined) + jc.inputs.map(_.rows).sum +
        jc.layout.runs + jc.layout.runDir.size + jc.tasks.count(_.output.exists(_.partitionBy.isDefined)) +
        jc.corruption.size + jc.tasks.map(t => t.name.size + t.failure.size + t.checks.length).sum +
        jc.fault.map(f => 1 + (if (f.overEarlier) 1 else 0) + f.arming.nth).getOrElse(0)
    var i = 0
    while (i < 40) {
      val jc = JobDagGen.generate(new Rng(i.toLong))
      val m = measure(jc)
      Shrinker.jobCase(jc).take(50).foreach { c =>
        assertTrue(s"shrink did not reduce: $m -> ${measure(c)} for $jc", measure(c) < m)
      }
      i += 1
    }
  }
}
