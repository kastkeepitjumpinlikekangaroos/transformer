package com.transformer.tools.mutation.flaky

import java.util.SplittableRandom
import org.junit.Assert._
import org.junit.Test

/** Fails half its runs, at random, and reads nothing of Flaky's answers. */
class FlakyTest {
  @Test def failsAtRandom(): Unit = {
    assertNotNull(Flaky)
    assertTrue("a failure at random", new SplittableRandom().nextBoolean())
  }
}
