package com.transformer.fuzz

import com.transformer.core.{Chaos, Scheduler}
import com.transformer.fuzz.oracle.DagSchedule
import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.{Callable, CountDownLatch}

/** The DAG fuzzer's watchdog under `--config=chaos`, run under
  * `-Dtransformer.chaos=3`: a pool wedged on purpose, every worker blocked,
  * is reported with a thread dump that names each worker's last chaos
  * points, which is all a failure under chaos can be diagnosed by. */
class DagWatchdogTest {

  @Test def aWedgedPoolIsReportedWithTheLastPointsEachThreadPassed(): Unit = {
    assertTrue("the target's jvm_flags set -Dtransformer.chaos=3", Chaos.enabled)
    val release = new CountDownLatch(1)
    val e = assertThrows(classOf[AssertionError], () => DagSchedule.watched("a wedged pool", 3000L) {
      Scheduler.submitAndAwaitAll((0 until Scheduler.parallelism).map(_ => new Callable[Unit] {
        def call(): Unit = release.await()
      }))
    })
    release.countDown()
    val report = e.getMessage
    assertTrue(report, report.contains("job did not terminate within 3000ms"))
    assertTrue(report, report.contains("a wedged pool"))
    val workers = report.linesIterator.count(_.trim.startsWith("transformer-worker-"))
    assertTrue(s"every worker is in the dump ($workers): $report", workers >= Scheduler.parallelism)
    assertTrue(report, report.linesIterator.exists(l => l.contains("last chaos points:") && l.contains(Chaos.Submit)))
    assertTrue(report, report.contains("(chaos seed 3,"))
  }
}
