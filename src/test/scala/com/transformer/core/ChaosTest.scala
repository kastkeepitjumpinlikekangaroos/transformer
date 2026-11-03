package com.transformer.core

import org.junit.Assert._
import org.junit.Test

import java.util.concurrent.atomic.AtomicReference

/** `--config=chaos` ([[Chaos]]), run under `-Dtransformer.chaos=42`: the seed
  * is read once; a point does nothing, yields or sleeps 0 to 2 ms, a third
  * each; and each thread keeps the last points it passed, for a failure's
  * report. */
class ChaosTest {

  @Test def theSeedIsReadOnce(): Unit = {
    assertTrue("the target's jvm_flags set -Dtransformer.chaos=42", Chaos.enabled)
    assertEquals(Some(42L), Chaos.seed)
  }

  @Test def aPointDoesNothingYieldsOrSleepsATimeEach(): Unit = {
    val random = new java.util.SplittableRandom(42L)
    val draws = Vector.fill(30000)(Chaos.draw(random))
    val nothing = draws.count(_ == Chaos.Action.Nothing)
    val yields = draws.count(_ == Chaos.Action.Yield)
    val sleeps = draws.collect { case Chaos.Action.Sleep(n) => n }
    Seq(nothing, yields, sleeps.size).foreach(n => assertTrue(s"a third each of 30000: $n", n > 9500 && n < 10500))
    assertTrue("a sleep lasts 0 to 2 ms", sleeps.forall(n => n >= 0L && n <= 2000000L))
    assertTrue("sleeps spread over the range", sleeps.exists(_ < 200000L) && sleeps.exists(_ > 1800000L))
  }

  @Test def eachThreadKeepsTheLastPointsItPassed(): Unit = {
    val seen = new AtomicReference[Seq[String]]()
    val before = Chaos.passed
    val t = new Thread(() => {
      Chaos.Points.foreach(p => Chaos.at(p))
      seen.set(Chaos.lastPointsOf(Thread.currentThread))
    }, "chaos-test-thread")
    t.start()
    t.join()
    assertEquals("the last eight, oldest first", Chaos.Points.takeRight(Chaos.Kept), seen.get)
    assertTrue(Chaos.passed >= before + Chaos.Points.size)
    Chaos.Points.foreach(p => assertTrue(p, Chaos.passed(p) >= 1L))
    assertEquals(Seq.empty, Chaos.lastPointsOf(new Thread(() => ())))
  }
}
