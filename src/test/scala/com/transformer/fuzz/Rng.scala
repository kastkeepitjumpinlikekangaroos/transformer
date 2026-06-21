package com.transformer.fuzz

import java.util.SplittableRandom

/** Seeded, splittable random source — the repro key for the whole harness.
  *
  * Backed by [[java.util.SplittableRandom]], whose constructor runs the seed
  * through a strong avalanche mix, so two `Rng`s built from consecutive seeds
  * (`base`, `base + 1`, ...) produce statistically independent streams. That
  * is what lets [[Props]] enumerate iterations as `base + i` and print a single
  * `Long` that reproduces an exact failing tree + batch.
  *
  * [[split]] returns an independent child stream. Generators draw their schema,
  * their column data, and their expression tree from separate split streams so
  * that changing one generator's draw sequence does not perturb the others —
  * a tree-shape refactor leaves the batch bytes for a given seed unchanged.
  */
final class Rng private (private val r: SplittableRandom) {

  def this(seed: Long) = this(new SplittableRandom(seed))

  /** Uniform `Int` in `[0, bound)`. `bound` must be positive. */
  def nextInt(bound: Int): Int = r.nextInt(bound)

  /** Uniform `Int` over the full 32-bit range. */
  def nextInt(): Int = r.nextInt()

  /** Uniform `Int` in `[lo, hi]` (inclusive both ends). */
  def between(lo: Int, hi: Int): Int = {
    require(hi >= lo, s"between: hi $hi < lo $lo")
    lo + r.nextInt(hi - lo + 1)
  }

  /** Uniform `Long` over the full 64-bit range. */
  def nextLong(): Long = r.nextLong()

  /** Uniform `Double` in `[0, 1)`. */
  def nextDouble(): Double = r.nextDouble()

  /** True with probability `p`. */
  def bool(p: Double): Boolean = r.nextDouble() < p

  /** Fair coin. */
  def bool(): Boolean = r.nextBoolean()

  /** Uniformly pick one element. `xs` must be non-empty. */
  def oneOf[A](xs: Seq[A]): A = {
    require(xs.nonEmpty, "oneOf: empty sequence")
    xs(r.nextInt(xs.length))
  }

  /** Uniformly pick one argument. */
  def pick[A](a: A, rest: A*): A = {
    val n = r.nextInt(rest.length + 1)
    if (n == 0) a else rest(n - 1)
  }

  /** Pick by integer weight. Each pair is `(weight, value)`; higher weight is
    * proportionally more likely. All weights must be positive. */
  def weighted[A](options: (Int, A)*): A = {
    require(options.nonEmpty, "weighted: no options")
    var total = 0
    var i = 0
    while (i < options.length) {
      require(options(i)._1 > 0, s"weighted: non-positive weight ${options(i)._1}")
      total += options(i)._1
      i += 1
    }
    var target = r.nextInt(total)
    i = 0
    while (i < options.length) {
      target -= options(i)._1
      if (target < 0) return options(i)._2
      i += 1
    }
    options.last._2
  }

  /** An independent sub-stream. Deterministic given this stream's state, so the
    * whole derivation tree is reproducible from the top-level seed. */
  def split(): Rng = new Rng(r.split())
}
