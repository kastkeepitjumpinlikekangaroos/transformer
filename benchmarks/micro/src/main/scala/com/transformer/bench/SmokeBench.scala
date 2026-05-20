package com.transformer.bench

import org.openjdk.jmh.annotations._
import java.util.concurrent.TimeUnit

/** Toolchain spike — the first benchmark the [[MicroBench]] runner exercises
  * end-to-end. Lives in the same Bazel target as the production benchmarks
  * so a successful `bazel build //benchmarks/micro:bench_all_deploy.jar`
  * combined with a successful smoke run proves the integration works. The
  * real benchmarks live in `KeyCodecBench` / `ExprEvalBench` / etc.
  *
  * Why this stays in the bundle (instead of being deleted post-spike): the
  * smoke benchmark is a permanent self-check. If someone bumps JMH or
  * touches the runner shape, running
  * `bench_all_deploy.jar com.transformer.bench.SmokeBench.*` quickly verifies
  * the harness still works without paying for the 60-second warmup of the
  * real benchmarks.
  */
@State(Scope.Benchmark)
@BenchmarkMode(Array(Mode.AverageTime))
@OutputTimeUnit(TimeUnit.NANOSECONDS)
class SmokeBench {

  /** Trivial work — `Math.sin` on a varied input so the JIT can't fold the
    * loop to a constant. JMH's `Blackhole.consume` ensures the result isn't
    * dead-code-eliminated. */
  @Benchmark
  def sinLoop(): Double = {
    var sum = 0.0
    var i = 0
    while (i < 1024) {
      sum += math.sin(i.toDouble * 0.001)
      i += 1
    }
    sum
  }
}
