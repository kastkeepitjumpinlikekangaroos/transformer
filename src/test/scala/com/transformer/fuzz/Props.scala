package com.transformer.fuzz

import scala.util.control.NonFatal

/** The property runner shared by every fuzz property.
  *
  * [[forAll]] enumerates `count` iterations with generator seeds
  * `base, base + 1, ...`. Each iteration builds a value `a` from a fresh
  * [[Rng]] and runs `prop(a)`; the first iteration whose `prop` throws is
  * greedily minimized via `shrink` and then reported through a JUnit failure
  * that prints the failing seed (the exact repro key), the minimized
  * counterexample, and the original throwable.
  *
  * Seed budget and base seed come from system properties (falling back to
  * environment variables, then defaults), so CI runs a small fixed batch while
  * a long campaign is just `--jvmopt='-Dfuzz.seeds=100000'`:
  *   - `fuzz.seeds` / `FUZZ_SEEDS` — iteration count (default [[DefaultSeeds]]).
  *   - `fuzz.seed` / `FUZZ_SEED` — base seed (default [[DefaultBaseSeed]]).
  *
  * `shrink` must yield strictly-smaller candidates (the `Expr` shrinker filters
  * to a decreasing node count); a hard [[MaxShrinkSteps]] cap backstops any
  * shrinker that does not.
  */
object Props {

  /** Iterations per property in the default (unconfigured) run. Small enough to
    * keep `bazel test //...` fast, large enough that a reverted `evalVec`
    * override is caught — see the sensitivity note in
    * `ExprParityFuzzTest`. Override with `-Dfuzz.seeds=N` for a campaign. */
  val DefaultSeeds: Int = 400

  /** Base seed for the default run. Fixed so CI explores the same trees every
    * time; iteration `i` uses seed `DefaultBaseSeed + i`. */
  val DefaultBaseSeed: Long = 0L

  /** Backstop so a misbehaving shrinker cannot loop forever. */
  val MaxShrinkSteps: Int = 2000

  def seedCount: Int = intConfig("fuzz.seeds", "FUZZ_SEEDS", DefaultSeeds)
  def baseSeed: Long = longConfig("fuzz.seed", "FUZZ_SEED", DefaultBaseSeed)

  /** Run `prop` over `count` generated values. On the first failure, minimize
    * and fail the enclosing JUnit test. */
  def forAll[A](name: String, gen: Rng => A, shrink: A => Iterator[A])(prop: A => Unit): Unit = {
    val base = baseSeed
    val count = seedCount
    var i = 0
    while (i < count) {
      val seed = base + i
      val value = gen(new Rng(seed))
      runProp(prop, value) match {
        case None => () // passed
        case Some(failure) =>
          val (minimized, minFailure) = minimize(prop, shrink, value, failure)
          fail(name, seed, minimized, minFailure)
      }
      i += 1
    }
  }

  /** Returns `Some(throwable)` if `prop(value)` failed, `None` if it passed.
    * Fatal throwables propagate (a VM error is not a property counterexample). */
  private def runProp[A](prop: A => Unit, value: A): Option[Throwable] =
    try { prop(value); None }
    catch { case NonFatal(t) => Some(t) }

  /** Greedy shrink: repeatedly replace the failing value with the first smaller
    * candidate that still fails, until nothing smaller fails (or the step cap
    * trips). */
  private def minimize[A](
      prop: A => Unit,
      shrink: A => Iterator[A],
      start: A,
      startFailure: Throwable): (A, Throwable) = {
    var current = start
    var failure = startFailure
    var steps = 0
    var progressed = true
    while (progressed && steps < MaxShrinkSteps) {
      progressed = false
      val candidates = shrink(current)
      while (candidates.hasNext && !progressed) {
        val cand = candidates.next()
        steps += 1
        runProp(prop, cand) match {
          case Some(t) => current = cand; failure = t; progressed = true
          case None => ()
        }
      }
    }
    (current, failure)
  }

  private def fail[A](name: String, seed: Long, minimized: A, failure: Throwable): Nothing = {
    val repro = s"--jvmopt='-Dfuzz.seed=$seed -Dfuzz.seeds=1'"
    val msg =
      s"""[fuzz:$name] property failed.
         |  seed = $seed   (reproduce: bazel test <target> $repro)
         |  minimized counterexample:
         |    $minimized
         |  original failure: ${failure.getClass.getName}: ${failure.getMessage}""".stripMargin
    val err = new AssertionError(msg)
    err.initCause(failure)
    throw err
  }

  private def intConfig(prop: String, env: String, default: Int): Int =
    rawConfig(prop, env).flatMap(s => scala.util.Try(s.trim.toInt).toOption).getOrElse(default)

  private def longConfig(prop: String, env: String, default: Long): Long =
    rawConfig(prop, env).flatMap(s => scala.util.Try(s.trim.toLong).toOption).getOrElse(default)

  private def rawConfig(prop: String, env: String): Option[String] =
    Option(System.getProperty(prop)).orElse(Option(System.getenv(env))).filter(_.trim.nonEmpty)
}
