package com.transformer.tools.mutation.flaky

/** A source for the runner's flaky-kill check: FlakyTest fails at random,
  * whatever this says. */
object Flaky {
  def clamp(a: Int, lo: Int, hi: Int): Int = if (a < lo) lo else if (a > hi) hi else a
  def either(a: Boolean, b: Boolean): Boolean = a && !b || b && !a
  def next(a: Int): Int = a + 1
  def start: Int = 0
}
