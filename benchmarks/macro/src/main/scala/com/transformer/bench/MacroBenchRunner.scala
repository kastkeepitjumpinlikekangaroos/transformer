package com.transformer.bench

import java.nio.file.{Files, Path, Paths, StandardCopyOption}
import java.time.Instant
import java.util.concurrent.TimeUnit
import scala.collection.mutable

/** End-to-end macro bench runner — Sub-plan 4 of the instrumentation work.
  *
  * Invokes a workload's deploy jar N times with `TRANSFORMER_METRICS_ENABLED=1`,
  * harvests every `_perf.json` produced (via the job-level `job.json.perfManifest`
  * field), aggregates per-task wall-time statistics across iterations
  * (median / p95 / stddev) and emits a single JSON result file consumable by
  * [[BenchDiff]] and the perf-tagged regression test.
  *
  * # Design rationale
  *
  *   - **Subprocess instead of in-process.** Each iteration is a full JVM
  *     launch so the JIT, classloader, and parquet reader caches all start
  *     cold — that matches the steady-state cost of a real deploy. In-process
  *     invocation would let HotSpot's tiered compilation amortize across
  *     iterations and report numbers significantly better than production.
  *     The cost is a 1-2 second JVM startup per iteration which is irrelevant
  *     on a job taking seconds.
  *   - **Env-var driven enablement.** `TRANSFORMER_METRICS_ENABLED=1` is the
  *     existing public knob; honoring it here means the macro bench tests the
  *     same code path users invoke when they enable metrics on their own
  *     deploys.
  *   - **Force-spill via sysprop.** `-Dtransformer.macro.force_spill=true`
  *     flips spill on for every task without touching `output.json`. Lets us
  *     capture the spill-on baseline against the same workload as the
  *     spill-off baseline so the two diff cleanly.
  *   - **Hand-rolled JSON.** No new Maven dep. The schema is small (per-task
  *     wall-time stats + operator-level counter medians) so the cost of
  *     hand-rolling is one method on each side.
  *
  * # Output schema
  *
  * {{{
  *   {
  *     "schemaVersion": 1,
  *     "workload": "jaffle",
  *     "iterations": 5,
  *     "warmup": 1,
  *     "forceSpill": false,
  *     "capturedAt": "2026-05-20T17:09:48Z",
  *     "tasks": [
  *       {
  *         "taskName": "customers",
  *         "wallNanosMedian": 48000000,
  *         "wallNanosP95": 52000000,
  *         "wallNanosStddev": 1200000,
  *         "wallNanosSamples": [46123456, 48000000, ...],
  *         "operatorTreeMedian": { "name": "...", "wallNanos": ..., ... }
  *       },
  *       ...
  *     ]
  *   }
  * }}}
  *
  * # CLI surface
  *
  * {{{
  *   bazel run //benchmarks/macro:macro_bench_runner -- \
  *       --workload jaffle \
  *       --iterations 5 \
  *       --warmup 1 \
  *       --output benchmarks/baseline/jaffle_shop.json
  *
  *   bazel run //benchmarks/macro:macro_bench_runner -- \
  *       --workload jaffle \
  *       --iterations 5 \
  *       --force-spill \
  *       --output benchmarks/baseline/jaffle_shop_spill.json
  *
  *   bazel run //benchmarks/macro:macro_bench_runner -- \
  *       --workload custom \
  *       --job-dir /path/to/job \
  *       --deploy-jar /path/to/deploy.jar \
  *       --iterations 3 \
  *       --output /tmp/custom.json
  * }}}
  */
object MacroBenchRunner {

  val SchemaVersion: Int = 1

  /** Entry point. Returns 0 on success, 1 on argument / runtime errors. */
  def main(args: Array[String]): Unit = {
    val parsed = try parseArgs(args)
    catch {
      case e: IllegalArgumentException =>
        System.err.println(s"macro_bench_runner: ${e.getMessage}")
        printUsage()
        System.exit(2); return
    }
    val result = runWorkload(parsed)
    val text = serialize(result)
    Files.writeString(parsed.outputPath, text)
    println(s"Wrote ${parsed.outputPath} (${result.tasks.size} tasks across ${result.iterations} iterations)")
    // One-line summary for the operator: median per task in ascending order.
    val sorted = result.tasks.sortBy(_.wallNanosMedian)
    sorted.foreach { t =>
      val ms = t.wallNanosMedian / 1.0e6
      println(f"  ${t.taskName}%-32s  median=${ms}%.2f ms  p95=${t.wallNanosP95 / 1.0e6}%.2f ms  stddev=${t.wallNanosStddev / 1.0e6}%.2f ms")
    }
  }

  /** Hand-rolled argument parsing. We avoid pulling a Maven CLI parser dep
    * because the surface is small and unlikely to grow. */
  private[bench] def parseArgs(args: Array[String]): Args = {
    var workload: Option[String] = None
    var jobDir: Option[String] = None
    var deployJar: Option[String] = None
    var iterations: Int = 5
    var warmup: Int = 1
    var output: Option[String] = None
    var forceSpill: Boolean = false
    var outputDir: Option[String] = None
    var executionTime: Option[String] = None
    var i = 0
    def take(flag: String): String = {
      i += 1
      if (i >= args.length) throw new IllegalArgumentException(s"$flag requires a value")
      args(i)
    }
    while (i < args.length) {
      args(i) match {
        case "--workload"       => workload = Some(take("--workload"))
        case "--job-dir"        => jobDir = Some(take("--job-dir"))
        case "--deploy-jar"     => deployJar = Some(take("--deploy-jar"))
        case "--iterations"     => iterations = take("--iterations").toInt
        case "--warmup"         => warmup = take("--warmup").toInt
        case "--output"         => output = Some(take("--output"))
        case "--output-dir"     => outputDir = Some(take("--output-dir"))
        case "--execution-time" => executionTime = Some(take("--execution-time"))
        case "--force-spill"    => forceSpill = true
        case "-h" | "--help"    => printUsage(); System.exit(0)
        case other              => throw new IllegalArgumentException(s"unknown flag: $other")
      }
      i += 1
    }
    val w = workload.getOrElse(throw new IllegalArgumentException("--workload is required"))
    val (resolvedJar, resolvedJobDir) = resolveWorkload(w, jobDir, deployJar)
    val resolvedOutput = output.getOrElse(throw new IllegalArgumentException("--output is required"))
    if (iterations < 1) throw new IllegalArgumentException("--iterations must be >= 1")
    if (warmup < 0) throw new IllegalArgumentException("--warmup must be >= 0")
    Args(
      workload = w,
      jobDir = resolvedJobDir,
      deployJar = resolvedJar,
      iterations = iterations,
      warmup = warmup,
      forceSpill = forceSpill,
      outputPath = Paths.get(resolvedOutput),
      benchOutputDir = outputDir.map(Paths.get(_)),
      executionTime = executionTime)
  }

  /** Resolve `--workload jaffle|polymarket|custom` into a (deployJar, jobDir)
    * pair. For `jaffle` and `polymarket` we look up the conventional location
    * under `bazel-bin/examples/<name>/`; the caller may override via
    * `--deploy-jar` / `--job-dir` (and must when running from outside a
    * bazel workspace). For `custom` both are required. */
  private def resolveWorkload(
      workload: String,
      jobDir: Option[String],
      deployJar: Option[String]): (Path, Path) = workload match {
    case "jaffle" =>
      val jar = deployJar.getOrElse("bazel-bin/examples/jaffle_shop/jaffle_shop_deploy.jar")
      val dir = jobDir.getOrElse("examples/jaffle_shop/job")
      (Paths.get(jar), Paths.get(dir))
    case "polymarket" =>
      val jar = deployJar.getOrElse("bazel-bin/examples/polymarket/polymarket_deploy.jar")
      val dir = jobDir.getOrElse("examples/polymarket/job")
      (Paths.get(jar), Paths.get(dir))
    case "custom" =>
      val jar = deployJar.getOrElse(
        throw new IllegalArgumentException("--workload custom requires --deploy-jar"))
      val dir = jobDir.getOrElse(
        throw new IllegalArgumentException("--workload custom requires --job-dir"))
      (Paths.get(jar), Paths.get(dir))
    case other =>
      throw new IllegalArgumentException(s"unknown --workload '$other' (expected jaffle / polymarket / custom)")
  }

  private def printUsage(): Unit = {
    System.err.println(
      """Usage: macro_bench_runner [options]
        |
        |  --workload <jaffle|polymarket|custom>   required
        |  --job-dir <path>                        override the workload's default job dir
        |  --deploy-jar <path>                     override the workload's default deploy jar
        |  --iterations <N>                        measurement iterations (default 5)
        |  --warmup <K>                            warmup iterations discarded (default 1)
        |  --output <path>                         where to write the result JSON (required)
        |  --output-dir <path>                     job's output dir (default: /tmp/transformer-macro-<workload>)
        |  --execution-time <ISO>                  fixed execution time for the workload (jaffle/polymarket only)
        |  --force-spill                           run with -Dtransformer.macro.force_spill=true
        |  -h / --help                             this message
        |""".stripMargin)
  }

  /** Run all iterations (warmup + measurement), harvest the `_perf.json`
    * files, aggregate, return the result. */
  private[bench] def runWorkload(args: Args): MacroBenchResult = {
    val totalIters = args.warmup + args.iterations
    // perTaskSamples(taskName) -> List of (wallNanos, operatorTreeJson)
    val perTask = mutable.LinkedHashMap.empty[String, mutable.ArrayBuffer[TaskSample]]
    val benchOutDir = args.benchOutputDir.getOrElse(
      Paths.get(System.getProperty("java.io.tmpdir"))
        .resolve(s"transformer-macro-${args.workload}"))
    var i = 0
    while (i < totalIters) {
      val isWarmup = i < args.warmup
      val tag = if (isWarmup) s"warmup ${i + 1}/${args.warmup}" else s"iter ${i - args.warmup + 1}/${args.iterations}"
      println(s"[macro_bench_runner] $tag")
      // Each iteration writes to a fresh per-iter output dir so we always
      // read the freshly-emitted job.json — overwriting in place would
      // technically work since `_run.json` / `_perf.json` are written
      // atomically, but starting fresh removes any chance of stale records
      // poisoning the aggregate.
      val iterDir = benchOutDir.resolve(s"iter-$i")
      if (Files.isDirectory(iterDir)) deleteRecursively(iterDir)
      Files.createDirectories(iterDir)
      val exitCode = runOneIteration(args, iterDir)
      if (exitCode != 0) {
        throw new RuntimeException(s"workload exited with code $exitCode on iteration $i")
      }
      if (!isWarmup) {
        val samples = harvestSamples(iterDir)
        samples.foreach { case (name, sample) =>
          perTask.getOrElseUpdate(name, mutable.ArrayBuffer.empty[TaskSample]) += sample
        }
      }
      i += 1
    }
    val taskRows = perTask.iterator.map { case (name, buf) =>
      val samples = buf.toVector
      val walls = samples.map(_.wallNanos)
      TaskRow(
        taskName = name,
        wallNanosSamples = walls,
        wallNanosMedian = median(walls),
        wallNanosP95 = p95(walls),
        wallNanosStddev = stddev(walls),
        // Pick the median sample's operator tree — it's the most "typical"
        // run shape and avoids over-weighting tail variations.
        operatorTreeMedian = pickMedianTree(samples))
    }.toVector
    MacroBenchResult(
      schemaVersion = SchemaVersion,
      workload = args.workload,
      iterations = args.iterations,
      warmup = args.warmup,
      forceSpill = args.forceSpill,
      capturedAt = Instant.now(),
      tasks = taskRows)
  }

  /** Build the ProcessBuilder for one iteration, run it, return its exit
    * code. STDOUT/STDERR inherit the parent's streams so progress lines from
    * the deploy jar surface in the macro bench log. */
  private def runOneIteration(args: Args, iterOutputDir: Path): Int = {
    val javaBin = Paths.get(System.getProperty("java.home")).resolve("bin").resolve("java").toString
    val cmd = mutable.ArrayBuffer.empty[String]
    cmd += javaBin
    cmd += "-Xmx2g"
    if (args.forceSpill) cmd += s"-D${com.transformer.job.DataJob.ForceSpillPropertyName}=true"
    cmd += "-jar"
    cmd += args.deployJar.toString
    cmd += args.jobDir.toString
    cmd += iterOutputDir.toString
    args.executionTime.foreach(cmd += _)
    val pb = new ProcessBuilder(cmd.toArray: _*)
    pb.inheritIO()
    pb.environment().put("TRANSFORMER_METRICS_ENABLED", "1")
    val proc = pb.start()
    val finished = proc.waitFor(10, TimeUnit.MINUTES)
    if (!finished) {
      proc.destroyForcibly()
      throw new RuntimeException("workload did not finish within 10 minutes")
    }
    proc.exitValue()
  }

  /** Read every `_perf.json` listed in `<iterDir>/job.json`'s `perfManifest`
    * and return `(taskName, sample)` pairs. */
  private[bench] def harvestSamples(iterDir: Path): Vector[(String, TaskSample)] = {
    val jobJson = iterDir.resolve("job.json")
    if (!Files.isRegularFile(jobJson)) return Vector.empty
    val perfPaths = parsePerfManifest(Files.readString(jobJson))
    perfPaths.flatMap { p =>
      val parent = Paths.get(p).getParent
      val perfFile = Paths.get(p)
      if (parent == null || !Files.isRegularFile(perfFile)) None
      else {
        com.transformer.core.metrics.TaskMetricsRecord.read(parent).map { r =>
          // Total task-body wall time: the operator tree's per-node `wallNanos`
          // only measures that one node's `hasNext`/`next` self-time (excludes
          // children), so the right "per-task wall time" is the
          // `startedAt → finishedAt` delta. This matches the duration the
          // human-readable run summary surfaces and is the right unit to gate
          // regression on.
          val wall = java.time.Duration.between(r.startedAt, r.finishedAt).toNanos
          // Extract the operatorTree fragment verbatim from the on-disk
          // `_perf.json` rather than re-serializing via
          // `QueryMetrics.serializeFragment` (which is `private[metrics]`).
          // This also means we get the exact bytes the metrics module wrote,
          // so a future schema change in QueryMetrics doesn't double-serialize.
          val opTreeJson =
            try sliceOperatorTreeFragment(Files.readString(perfFile))
            catch { case _: Throwable => "null" }
          (r.taskName, TaskSample(wall, opTreeJson))
        }
      }
    }
  }

  /** Pull just the `perfManifest` array out of a job.json without standing up
    * a full JSON parser. The shape is simple — an array of strings or
    * absent — so a hand-rolled scan suffices and keeps the macro bench from
    * needing to depend on `//src/main/scala/com/transformer/job:job`. */
  private[bench] def parsePerfManifest(jobJson: String): Vector[String] = {
    val key = "\"perfManifest\""
    val keyPos = jobJson.indexOf(key)
    if (keyPos < 0) return Vector.empty
    // skip "perfManifest": then whitespace
    var i = keyPos + key.length
    while (i < jobJson.length && jobJson.charAt(i) != '[') i += 1
    if (i >= jobJson.length) return Vector.empty
    i += 1 // past '['
    val out = mutable.ArrayBuffer.empty[String]
    while (i < jobJson.length && jobJson.charAt(i) != ']') {
      // skip whitespace, commas
      while (i < jobJson.length && (jobJson.charAt(i) == ' ' || jobJson.charAt(i) == '\n' ||
        jobJson.charAt(i) == '\t' || jobJson.charAt(i) == '\r' || jobJson.charAt(i) == ',')) i += 1
      if (i >= jobJson.length || jobJson.charAt(i) == ']') return out.toVector
      if (jobJson.charAt(i) != '"') return out.toVector
      i += 1
      val sb = new java.lang.StringBuilder
      while (i < jobJson.length && jobJson.charAt(i) != '"') {
        val c = jobJson.charAt(i)
        if (c == '\\' && i + 1 < jobJson.length) {
          // basic unescape — paths shouldn't contain non-ASCII tab/newline
          // but support the common case.
          jobJson.charAt(i + 1) match {
            case '"'  => sb.append('"');  i += 2
            case '\\' => sb.append('\\'); i += 2
            case other => sb.append(c); sb.append(other); i += 2
          }
        } else {
          sb.append(c); i += 1
        }
      }
      out += sb.toString
      i += 1 // past closing quote
    }
    out.toVector
  }

  /** Slice the `operatorTree` JSON sub-object out of a `_perf.json` payload
    * verbatim. The metrics module owns the serializer; we just want the bytes
    * it already wrote so we can embed them in our aggregate output.
    *
    * Returns `"null"` when the field is absent (e.g. a task whose body threw
    * before the operator tree was materialized). */
  private[bench] def sliceOperatorTreeFragment(perfJson: String): String = {
    val key = "\"operatorTree\""
    val keyPos = perfJson.indexOf(key)
    if (keyPos < 0) return "null"
    var i = keyPos + key.length
    while (i < perfJson.length && perfJson.charAt(i) != ':') i += 1
    if (i >= perfJson.length) return "null"
    i += 1
    while (i < perfJson.length && Character.isWhitespace(perfJson.charAt(i))) i += 1
    if (i >= perfJson.length) return "null"
    if (perfJson.charAt(i) != '{') {
      // could be `null` — return that verbatim.
      val end = math.min(perfJson.length, i + 4)
      val token = perfJson.substring(i, end)
      if (token == "null") return "null"
      return "null"
    }
    val start = i
    var depth = 0
    var inString = false
    var escape = false
    while (i < perfJson.length) {
      val c = perfJson.charAt(i)
      if (inString) {
        if (escape) escape = false
        else if (c == '\\') escape = true
        else if (c == '"') inString = false
      } else {
        c match {
          case '"' => inString = true
          case '{' => depth += 1
          case '}' =>
            depth -= 1
            if (depth == 0) return perfJson.substring(start, i + 1)
          case _ => ()
        }
      }
      i += 1
    }
    "null"
  }

  // ---- statistics --------------------------------------------------------

  private[bench] def median(xs: IndexedSeq[Long]): Long = {
    if (xs.isEmpty) return 0L
    val sorted = xs.sorted
    val n = sorted.size
    if (n % 2 == 1) sorted(n / 2)
    else (sorted(n / 2 - 1) + sorted(n / 2)) / 2L
  }

  private[bench] def p95(xs: IndexedSeq[Long]): Long = {
    if (xs.isEmpty) return 0L
    val sorted = xs.sorted
    // nearest-rank, simple and stable.
    val idx = math.min(sorted.size - 1, math.ceil(0.95 * sorted.size).toInt - 1)
    sorted(math.max(0, idx))
  }

  private[bench] def stddev(xs: IndexedSeq[Long]): Long = {
    val n = xs.size
    if (n < 2) return 0L
    val mean = xs.iterator.map(_.toDouble).sum / n
    val variance = xs.iterator.map { x => val d = x.toDouble - mean; d * d }.sum / (n - 1)
    math.sqrt(variance).toLong
  }

  /** Find the sample whose wallNanos equals the median; return its operator
    * tree JSON. Empty string when the input was empty. */
  private def pickMedianTree(samples: IndexedSeq[TaskSample]): String = {
    if (samples.isEmpty) return "null"
    val medianWall = median(samples.map(_.wallNanos))
    samples.find(_.wallNanos == medianWall).map(_.operatorTreeJson).getOrElse(samples.head.operatorTreeJson)
  }

  // ---- serialization -----------------------------------------------------

  private[bench] def serialize(r: MacroBenchResult): String = {
    val sb = new java.lang.StringBuilder()
    sb.append("{\n")
    sb.append("  \"schemaVersion\": ").append(r.schemaVersion).append(",\n")
    sb.append("  \"workload\": \"").append(escape(r.workload)).append("\",\n")
    sb.append("  \"iterations\": ").append(r.iterations).append(",\n")
    sb.append("  \"warmup\": ").append(r.warmup).append(",\n")
    sb.append("  \"forceSpill\": ").append(r.forceSpill).append(",\n")
    sb.append("  \"capturedAt\": \"").append(escape(r.capturedAt.toString)).append("\",\n")
    sb.append("  \"_comment\": \"")
      .append("Machine-dependent baseline. Re-capture by running `bazel run //benchmarks/macro:macro_bench_runner -- --workload jaffle --iterations 5 --output benchmarks/baseline/jaffle_shop.json` and committing the result. ")
      .append("See docs/benchmarking.md for the full procedure.\",\n")
    sb.append("  \"tasks\": [")
    if (r.tasks.nonEmpty) {
      sb.append('\n')
      var i = 0
      val n = r.tasks.size
      while (i < n) {
        val t = r.tasks(i)
        sb.append("    {\n")
        sb.append("      \"taskName\": \"").append(escape(t.taskName)).append("\",\n")
        sb.append("      \"wallNanosMedian\": ").append(t.wallNanosMedian).append(",\n")
        sb.append("      \"wallNanosP95\": ").append(t.wallNanosP95).append(",\n")
        sb.append("      \"wallNanosStddev\": ").append(t.wallNanosStddev).append(",\n")
        sb.append("      \"wallNanosSamples\": [")
        var j = 0
        val nj = t.wallNanosSamples.size
        while (j < nj) {
          sb.append(t.wallNanosSamples(j))
          if (j < nj - 1) sb.append(", ")
          j += 1
        }
        sb.append("],\n")
        sb.append("      \"operatorTreeMedian\": ").append(t.operatorTreeMedian).append('\n')
        sb.append("    }")
        if (i < n - 1) sb.append(',')
        sb.append('\n')
        i += 1
      }
      sb.append("  ")
    }
    sb.append("]\n")
    sb.append("}\n")
    sb.toString
  }

  private def escape(s: String): String = {
    val sb = new java.lang.StringBuilder(s.length + 8)
    var i = 0
    while (i < s.length) {
      val c = s.charAt(i)
      c match {
        case '"'           => sb.append("\\\"")
        case '\\'          => sb.append("\\\\")
        case '\n'          => sb.append("\\n")
        case '\r'          => sb.append("\\r")
        case '\t'          => sb.append("\\t")
        case c if c < 0x20 => sb.append(f"\\u${c.toInt}%04x")
        case c             => sb.append(c)
      }
      i += 1
    }
    sb.toString
  }

  /** Recursively delete the directory tree at `p`. Used between iterations
    * to keep stale `_perf.json` from earlier iterations out of the harvest.
    * Best-effort; we don't rethrow on individual file-removal failures
    * because the iter dir may legitimately not exist on the first iter. */
  private def deleteRecursively(p: Path): Unit = {
    if (!Files.exists(p)) return
    Files.walk(p).sorted(java.util.Comparator.reverseOrder())
      .forEach(child => try Files.delete(child) catch { case _: Throwable => () })
  }

  // ---- data classes ------------------------------------------------------

  /** Parsed CLI args. */
  private[bench] final case class Args(
      workload: String,
      jobDir: Path,
      deployJar: Path,
      iterations: Int,
      warmup: Int,
      forceSpill: Boolean,
      outputPath: Path,
      benchOutputDir: Option[Path],
      executionTime: Option[String])

  /** One per-iteration sample for one task: the operator-tree-root wall
    * time and the full operator tree JSON (kept verbatim so we can write
    * the median run's tree into the result file). */
  private[bench] final case class TaskSample(
      wallNanos: Long,
      operatorTreeJson: String)

  /** Aggregated per-task row that lands in the output file. */
  private[bench] final case class TaskRow(
      taskName: String,
      wallNanosSamples: IndexedSeq[Long],
      wallNanosMedian: Long,
      wallNanosP95: Long,
      wallNanosStddev: Long,
      operatorTreeMedian: String)

  /** Top-level result body. */
  private[bench] final case class MacroBenchResult(
      schemaVersion: Int,
      workload: String,
      iterations: Int,
      warmup: Int,
      forceSpill: Boolean,
      capturedAt: Instant,
      tasks: Vector[TaskRow])
}
