package com.transformer.bench

import org.openjdk.jmh.runner.Runner
import org.openjdk.jmh.runner.options.{OptionsBuilder, TimeValue, VerboseMode}
import org.openjdk.jmh.results.format.ResultFormatType

import java.util.concurrent.TimeUnit

/** Programmatic JMH runner for the transformer microbenchmarks.
  *
  * This is **Pattern B** from the Sub-plan 3 toolchain spike — no annotation
  * processor, no APT, pure Scala. Each `@Benchmark`-annotated method in the
  * sibling files (e.g. `KeyCodecBench`, `ExprEvalBench`, ...) is picked up by
  * JMH's runtime via the bytecode generator pulled in by the
  * `jmh-generator-bytecode` Maven artifact.
  *
  * # Why programmatic instead of APT
  *
  * `rules_scala` 7.0.0 has no first-class JMH integration. The two options
  * documented in the plan are:
  *
  *   - **A** — Java benchmark classes delegating to Scala via static methods.
  *     APT runs as part of javac on the Java side. Cleanest Bazel-native
  *     setup but every benchmark needs a Java wrapper.
  *   - **B (this file)** — Programmatic Runner. JMH's runtime API enumerates
  *     `@Benchmark` methods at JVM start via its bytecode generator. No
  *     annotation processor. Trivial Bazel integration: one `scala_binary`
  *     that pulls `jmh-core` + `jmh-generator-bytecode` from Maven and
  *     bundles every benchmark.
  *
  * Pattern B works because JMH's bytecode generator scans loaded classes at
  * `Runner.run` time — it doesn't need pre-generated harness classes. The
  * trade-off is slightly more boilerplate per benchmark (no `@Param`
  * sugar) which we handle by defining state classes per parameterized
  * dimension instead.
  *
  * # Command-line surface
  *
  * Mimics the JMH CLI subset we actually use:
  *
  * {{{
  * # Smoke run: 1s warmup, 3 measurement iterations, 1 fork.
  * java -jar bench_all_deploy.jar -w 1s -i 3 -f 1
  *
  * # Real run with JSON output:
  * java -jar bench_all_deploy.jar -w 3s -i 5 -f 1 -r 3s \
  *   -rf json -rff /tmp/bench-micro.json
  *
  * # Target a single benchmark by glob:
  * java -jar bench_all_deploy.jar 'com.transformer.bench.KeyCodecBench.*'
  * }}}
  *
  * Supported flags (everything else is passed verbatim to JMH's option
  * parser):
  *
  *   - `-w <duration>` — warmup iteration duration (default `1s`).
  *   - `-i <count>` — measurement iterations (default `3`).
  *   - `-f <count>` — number of forks (default `1`).
  *   - `-r <duration>` — measurement iteration duration (default `3s`).
  *   - `-wi <count>` — warmup iterations (default `3`).
  *   - `-rf <format>` — result format (`json`, `csv`, `text`).
  *   - `-rff <path>` — result file path.
  *   - `-v <mode>` — verbose mode (`SILENT`, `NORMAL`, `EXTRA`).
  *   - `-prof <name>` — JMH profiler (e.g. `gc`).
  *   - First positional argument: benchmark name regex / glob.
  */
object MicroBench {

  def main(args: Array[String]): Unit = {
    val parsed = parseArgs(args)
    val builder = new OptionsBuilder()

    parsed.include.foreach(builder.include)
    builder
      .warmupIterations(parsed.warmupIterations)
      .warmupTime(parsed.warmupTime)
      .measurementIterations(parsed.measurementIterations)
      .measurementTime(parsed.measurementTime)
      .forks(parsed.forks)
      .timeUnit(TimeUnit.NANOSECONDS)
      .shouldDoGC(true)
      .verbosity(parsed.verbosity)

    parsed.resultFormat.foreach(builder.resultFormat)
    parsed.resultFile.foreach(builder.result)
    parsed.profilers.foreach(builder.addProfiler)

    val runner = new Runner(builder.build())
    runner.run()
    ()
  }

  /** Parse the CLI flags. We re-implement (instead of relying on
    * `CommandLineOptions`) because that requires the GPL'd JopFlexParser
    * classes which we don't carry transitively. Subset suffices for the
    * benchmarks we actually run. */
  private[bench] def parseArgs(args: Array[String]): Parsed = {
    var i = 0
    var include: List[String] = Nil
    var warmupIterations = 3
    var warmupTime: TimeValue = TimeValue.seconds(1)
    var measurementIterations = 3
    var measurementTime: TimeValue = TimeValue.seconds(3)
    var forks = 1
    var verbosity: VerboseMode = VerboseMode.NORMAL
    var resultFormat: Option[ResultFormatType] = None
    var resultFile: Option[String] = None
    var profilers: List[String] = Nil
    def takeArg(flag: String): String = {
      i += 1
      if (i >= args.length)
        throw new IllegalArgumentException(s"$flag requires an argument")
      args(i)
    }
    while (i < args.length) {
      args(i) match {
        case "-w"    => warmupTime = parseTime(takeArg("-w"))
        case "-wi"   => warmupIterations = takeArg("-wi").toInt
        case "-i"    => measurementIterations = takeArg("-i").toInt
        case "-r"    => measurementTime = parseTime(takeArg("-r"))
        case "-f"    => forks = takeArg("-f").toInt
        case "-rf"   => resultFormat = Some(parseFormat(takeArg("-rf")))
        case "-rff"  => resultFile = Some(takeArg("-rff"))
        case "-v"    => verbosity = parseVerbosity(takeArg("-v"))
        case "-prof" => profilers = takeArg("-prof") :: profilers
        case other if other.startsWith("-") =>
          throw new IllegalArgumentException(s"unknown flag: $other")
        case glob => include = glob :: include
      }
      i += 1
    }
    Parsed(
      include.reverse,
      warmupIterations,
      warmupTime,
      measurementIterations,
      measurementTime,
      forks,
      verbosity,
      resultFormat,
      resultFile,
      profilers.reverse)
  }

  private def parseTime(s: String): TimeValue = {
    val t = s.trim
    if (t.endsWith("ms")) TimeValue.milliseconds(t.dropRight(2).toLong)
    else if (t.endsWith("s")) TimeValue.seconds(t.dropRight(1).toLong)
    else if (t.endsWith("us")) TimeValue.microseconds(t.dropRight(2).toLong)
    else if (t.endsWith("ns")) TimeValue.nanoseconds(t.dropRight(2).toLong)
    else if (t.endsWith("m")) TimeValue.minutes(t.dropRight(1).toLong)
    else throw new IllegalArgumentException(s"unrecognized duration: $s")
  }

  private def parseFormat(s: String): ResultFormatType = s.toLowerCase match {
    case "json" => ResultFormatType.JSON
    case "csv"  => ResultFormatType.CSV
    case "scsv" => ResultFormatType.SCSV
    case "text" => ResultFormatType.TEXT
    case "latex" => ResultFormatType.LATEX
    case other  => throw new IllegalArgumentException(s"unrecognized result format: $other")
  }

  private def parseVerbosity(s: String): VerboseMode = s.toUpperCase match {
    case "SILENT" => VerboseMode.SILENT
    case "NORMAL" => VerboseMode.NORMAL
    case "EXTRA"  => VerboseMode.EXTRA
    case other    => throw new IllegalArgumentException(s"unrecognized verbosity: $other")
  }

  private[bench] final case class Parsed(
      include: List[String],
      warmupIterations: Int,
      warmupTime: TimeValue,
      measurementIterations: Int,
      measurementTime: TimeValue,
      forks: Int,
      verbosity: VerboseMode,
      resultFormat: Option[ResultFormatType],
      resultFile: Option[String],
      profilers: List[String])
}
