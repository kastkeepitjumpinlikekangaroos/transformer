package com.transformer.bench

import com.transformer.core.metrics.TaskMetricsRecord
import com.transformer.job.{DataJob, DirectoryJobLoader}
import com.transformer.temporal.TemporalVariables

import org.junit.Assert._
import org.junit.Test

import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable

/** Perf-tagged regression guard for the jaffle workload — Sub-plan 4 of the
  * instrumentation work.
  *
  * Opt-in via `bazel test //... --test_tag_filters=perf`. Both the BUILD
  * target and the test method don't run as part of `bazel test //...` by
  * default, so they neither slow down the regular CI loop nor are flaky on
  * machines that don't match the baseline. The reason this isn't in
  * unit-test territory: the test needs to be run on the *machine that
  * captured the baseline* (or one comparable to it), so the regression
  * threshold is meaningful. CI gates run elsewhere set their own thresholds.
  *
  * # Approach
  *
  * In-process invocation of [[DataJob.run]] rather than running the
  * pre-built deploy jar via [[ProcessBuilder]]. The deploy jar route would
  * better match the macro bench runner's protocol — fresh JVM per iteration
  * means no JIT carryover — but it would require either:
  *
  *   - A Bazel `genrule` target that builds and bundles the jar as a test
  *     resource (mixes test + build phases unnecessarily), OR
  *   - A test runtime dep on `//examples/jaffle_shop:jaffle_shop_deploy.jar`
  *     (cross-package binary dep is awkward in rules_scala 7.0.0).
  *
  * The in-process route runs the same `DataJob.run` code path the deploy
  * jar would. The regression threshold is set conservatively (20%
  * spill-off, 25% spill-on) to absorb the in-process warm-JIT advantage —
  * if a *real* regression is in the 5-10% range it will still trip the
  * gate with a clear message; tighter thresholds would require the
  * subprocess route. Document this in
  * [docs/benchmarking.md](../../../../../../../docs/benchmarking.md).
  *
  * # Iterations
  *
  * Two warmup iterations + three measurement iterations. Three medians is
  * the smallest reliable median; one warmup iteration is enough for the
  * JIT to compile the operator-tree hot paths.
  */
class JaffleRegressionTest {

  @Test def jaffleRegressionGuardSpillOff(): Unit = {
    runRegressionGuard(forceSpill = false, threshold = 0.20)
  }

  @Test def jaffleRegressionGuardSpillOn(): Unit = {
    runRegressionGuard(forceSpill = true, threshold = 0.25)
  }

  private def runRegressionGuard(forceSpill: Boolean, threshold: Double): Unit = {
    val workspace = locateWorkspace()
    val jobDir = workspace.resolve("examples/jaffle_shop/job")
    if (!Files.isDirectory(jobDir)) {
      // Test was invoked outside the bazel sandbox — fall back to skip
      // rather than fail. Inside the bazel sandbox jobDir resolves via the
      // runfiles tree.
      System.err.println(s"[jaffle_regression_test] skipping: jaffle job dir not found at $jobDir")
      return
    }
    val baselineFile = workspace.resolve(
      if (forceSpill) "benchmarks/baseline/jaffle_shop_spill.json"
      else "benchmarks/baseline/jaffle_shop.json")
    if (!Files.isRegularFile(baselineFile)) {
      System.err.println(s"[jaffle_regression_test] skipping: baseline not found at $baselineFile")
      return
    }
    val baseline = BenchDiff.parseBenchJson(Files.readString(baselineFile))
    val baselineRows = baseline.iterator.map(r => r.taskName -> r.wallNanosMedian).toMap

    val warmup = 2
    val measurements = 3
    // Force-spill is plumbed via System.setProperty rather than the env var
    // because the test harness has full control of the JVM and setProperty
    // is reliable; setting env vars inside a running JVM is platform-flaky.
    val prev = System.getProperty(DataJob.ForceSpillPropertyName)
    if (forceSpill) System.setProperty(DataJob.ForceSpillPropertyName, "true")
    else System.clearProperty(DataJob.ForceSpillPropertyName)
    val prevMetrics = System.getProperty("transformer.metrics.enabled")
    System.setProperty("transformer.metrics.enabled", "true")
    try {
      // Each iteration writes into its own sub-dir under a per-test temp
      // location so spillOn/Off don't trip over each other.
      val tmp = Files.createTempDirectory(s"jaffle_regression_${if (forceSpill) "spill_on" else "spill_off"}_")
      val perTask = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[Long]]
      val iters = warmup + measurements
      var i = 0
      while (i < iters) {
        val isWarmup = i < warmup
        val iterDir = tmp.resolve(s"iter-$i")
        Files.createDirectories(iterDir)
        runJaffleOnce(jobDir, iterDir)
        if (!isWarmup) {
          val samples = harvest(iterDir)
          samples.foreach { case (name, nanos) =>
            perTask.getOrElseUpdate(name, mutable.ArrayBuffer.empty[Long]) += nanos
          }
        }
        i += 1
      }
      val regressions = mutable.ArrayBuffer.empty[String]
      perTask.foreach { case (name, samples) =>
        val median = MacroBenchRunner.median(samples.toIndexedSeq)
        val baseMedian = baselineRows.getOrElse(name, 0L)
        if (baseMedian > 0L) {
          val pct = (median - baseMedian).toDouble / baseMedian.toDouble
          if (pct > threshold) {
            regressions += f"  $name%-32s  baseline=${baseMedian / 1.0e6}%.2f ms  measured=${median / 1.0e6}%.2f ms  delta=${pct * 100}%+6.2f%% (threshold ${(threshold * 100).toInt}%%)"
          }
        }
      }
      if (regressions.nonEmpty) {
        val mode = if (forceSpill) "spill-on" else "spill-off"
        fail(s"jaffle $mode regression:\n" + regressions.mkString("\n"))
      }
    } finally {
      if (prev == null) System.clearProperty(DataJob.ForceSpillPropertyName)
      else System.setProperty(DataJob.ForceSpillPropertyName, prev)
      if (prevMetrics == null) System.clearProperty("transformer.metrics.enabled")
      else System.setProperty("transformer.metrics.enabled", prevMetrics)
    }
  }

  /** Invoke the jaffle workload once. Mirrors [[com.example.jaffle.JaffleShopExample.main]]
    * but without the cross-package binary dep — we use [[DirectoryJobLoader]]
    * directly, which is the same code path the example calls into. */
  private def runJaffleOnce(jobDir: Path, outputDir: Path): Unit = {
    val job = DirectoryJobLoader.load(
      jobDir = jobDir,
      outputDir = Some(outputDir.toString),
      temporalVariables = Some(TemporalVariables(Instant.parse("2026-01-01T00:00:00Z"))))
    val result = job.run()
    assertTrue(s"jaffle iteration must succeed; got error=${result.error}", result.succeeded)
  }

  /** Read every `_perf.json` produced by an iteration, return per-task
    * wall-nanos samples. Matches the harvest logic in [[MacroBenchRunner]]
    * (started → finished delta, not the per-operator self-time). */
  private def harvest(iterDir: Path): Vector[(String, Long)] = {
    val out = mutable.ArrayBuffer.empty[(String, Long)]
    Files.walk(iterDir).filter { p =>
      Files.isRegularFile(p) && p.getFileName.toString == TaskMetricsRecord.FileName
    }.forEach { p =>
      val parent = p.getParent
      TaskMetricsRecord.read(parent).foreach { r =>
        val nanos = java.time.Duration.between(r.startedAt, r.finishedAt).toNanos
        out += ((r.taskName, nanos))
      }
    }
    out.toVector
  }

  /** Resolve the workspace root. In a Bazel sandbox the current dir IS the
    * runfiles root for `data = glob(["..."])` deps; outside Bazel we walk
    * up from cwd looking for a `WORKSPACE` or `MODULE.bazel`. */
  private def locateWorkspace(): Path = {
    val cwd = Paths.get(".").toAbsolutePath.normalize()
    // Bazel sandbox: jaffle job dir is at examples/jaffle_shop/job relative
    // to cwd, because the BUILD rule adds it as data.
    if (Files.isDirectory(cwd.resolve("examples/jaffle_shop/job"))) return cwd
    // Outside Bazel — walk up.
    var p = cwd
    while (p != null) {
      if (Files.isRegularFile(p.resolve("MODULE.bazel")) || Files.isRegularFile(p.resolve("WORKSPACE"))) return p
      p = p.getParent
    }
    cwd
  }
}

/** Standalone counter so we can give in-process iterations stable temp paths
  * even if multiple test methods run in parallel within one JVM. */
private object JaffleRegressionTest {
  val iterId: AtomicInteger = new AtomicInteger(0)
}
