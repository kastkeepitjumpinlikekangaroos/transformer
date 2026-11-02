package com.transformer.fuzz

import com.transformer.core.{Faults, Spill}
import com.transformer.fuzz.JobDagGen.JobCase
import com.transformer.fuzz.oracle.{DagSchedule, Hydration}
import org.junit.Assert._
import org.junit.{After, Before, Test}

import java.nio.file.{Files, Path}

/** The DAG fuzzer's crash campaign (plans/testing/06): every generated job has
  * one run stopped by a halt — a kill, a power cut — at a fault point
  * ([[Faults]]), in a child JVM ([[DagCrashChild]]), since a halt must never
  * end this one. This JVM then checks what the stopped run left (every
  * directory absent or holding a record, a `Failed` or `Skipped` one with no
  * part or staged file, a finished one with exactly the parts it names), that
  * a session reopening it shows a task whose record says `Running` as
  * unfinished, and that the run, rerun here with nothing armed, gives a clean
  * run's statuses with every invariant `DagSchedule` holds, and the tree a
  * clean run leaves for `Hydration`.
  *
  * Each case starts a JVM, so the budget is small: `dag_crash_fuzz_campaign`,
  * tagged `fuzz`, runs it with `FUZZ_SEEDS=N`. A full disk is the `enospc`
  * action the in-JVM fault mode throws; a real one needs a mounted
  * size-limited volume, which stays a manual check.
  */
class DagCrashFuzzTest {

  private val DefaultCrashSeeds: Int = 20

  private val originalDirProp: String = System.getProperty(Spill.SpillDirProperty)
  private var tmpRoot: Path = _

  @Before def setSpillDir(): Unit = {
    tmpRoot = Files.createTempDirectory("dag-crash-spill-")
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

  @Test def fuzzDagCrash(): Unit =
    Props.forAll[JobCase](
      name = "dag-crash",
      gen = JobDagGen.generateCrash,
      shrink = Shrinker.jobCase,
      count = Props.seedCountOr(DefaultCrashSeeds)
    ) { jc => DagSchedule.run(jc, crash = true)((runs, root) => Hydration.check(jc, runs, root)); () }

  /** Coverage guard: every crash case carries a halt, and over the cases run
    * every fault point halts a child, and some halt leaves a task a session
    * reopens unfinished. */
  @Test def crashCoversFaultPoints(): Unit = {
    val n = Props.seedCountOr(DefaultCrashSeeds)
    val halts = (0 until n).map(i => JobDagGen.generateCrash(new Rng(Props.baseSeed + i)).faultPlan)
    assertTrue(s"a crash case without a halt: $halts",
      halts.forall(_.exists(_.arming.action == Faults.Action.Halt)))
    val seen = new Hydration.Report
    var seed = Props.baseSeed
    while (!(Faults.Points.forall(p => seen.faults((p, true)) > 0) && seen.unfinished > 0) &&
      seed < Props.baseSeed + math.max(n, 400)) {
      val jc = JobDagGen.generateCrash(new Rng(seed))
      seen.add(DagSchedule.run(jc, crash = true)((runs, root) => Hydration.check(jc, runs, root)))
      seed += 1
    }
    println(s"[dag-crash] over ${seed - Props.baseSeed} cases: halted=${Faults.Points.map(p => p -> seen.faults((p, true))).toMap} " +
      s"notReached=${Faults.Points.map(p => p -> seen.faults((p, false))).toMap} unfinished=${seen.unfinished}")
    Faults.Points.foreach(p => assertTrue(s"no child halted at $p", seen.faults((p, true)) > 0))
    assertTrue("no halt left a task a session reopened unfinished", seen.unfinished > 0)
  }
}
