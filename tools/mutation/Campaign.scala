package com.transformer.tools.mutation

import java.nio.charset.StandardCharsets
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.{Files, Path, StandardOpenOption}
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters._
import scala.util.Using

/** Bazel in a worktree, under its own output base, with a disk cache, so that
  * putting a file back puts every action back on the cache. Each invocation's
  * console goes to a file under `logs`. */
final class BazelExecutor(repo: Path, outputBase: Path, diskCache: Path, logs: Path, log: String => Unit) extends Executor {
  private var invocation = 0
  /** The Bazel client running now, for [[stop]]. */
  @volatile private var running: Option[Process] = None

  /** Stops the Bazel client running now, if any; Bazel's server cancels its
    * command. */
  def stop(): Unit = running.foreach(_.destroy())

  private val common = Vector(
    s"--disk_cache=$diskCache", "--color=no", "--curses=no", "--noshow_progress", "--keep_going")

  /** Runs `bazel <command> <args>`, its console to a new log file; the exit
    * code, or -1 when it ran past `wallSeconds` and was stopped. */
  private def bazel(command: String, args: Seq[String], wallSeconds: Long): (Int, Path) = {
    invocation += 1
    val out = logs.resolve("%05d-%s.log".formatLocal(Locale.ROOT, invocation, command))
    val cmd = Vector("bazel", s"--output_base=$outputBase", command) ++ args
    val pb = new ProcessBuilder(cmd.asJava).directory(repo.toFile).redirectErrorStream(true).redirectOutput(out.toFile)
    val p = pb.start()
    running = Some(p)
    val finished = try p.waitFor(wallSeconds, TimeUnit.SECONDS) finally running = None
    if (finished) (p.exitValue(), out)
    else {
      log(s"bazel $command ran past ${wallSeconds}s: stopping it and its server")
      p.destroy()
      if (!p.waitFor(30, TimeUnit.SECONDS)) p.destroyForcibly()
      stopServer()
      (-1, out)
    }
  }

  private def stopServer(): Unit = {
    val pidFile = outputBase.resolve("server/server.pid.txt")
    if (Files.exists(pidFile)) {
      val pid = Files.readString(pidFile).trim.toLong
      java.lang.ProcessHandle.of(pid).ifPresent { h => val _ = h.destroyForcibly() }
    }
  }

  def query(expression: String): Vector[String] = {
    val (code, out) = bazel("query", Seq(expression, "--output=label"), 600)
    val lines = Files.readAllLines(out).asScala.toVector
    if (code != 0) throw new IllegalStateException(s"bazel query $expression failed:\n${lines.takeRight(20).mkString("\n")}")
    lines.filter(_.startsWith("//")).sorted
  }

  def build(targets: Seq[String]): Option[String] = {
    val (code, out) = bazel("build", common ++ targets, 1800)
    if (code == 0) None
    else {
      val lines = Files.readAllLines(out).asScala
      Some(lines.find(_.contains("error:")).orElse(lines.find(_.startsWith("ERROR"))).getOrElse(s"bazel build exited $code").trim)
    }
  }

  def test(targets: Seq[String], flags: Seq[String], timeoutSeconds: Long, cached: Boolean): TestRun = {
    val bep = logs.resolve("%05d-bep.json".formatLocal(Locale.ROOT, invocation + 1))
    val args = common ++ flags ++ Seq(s"--test_timeout=$timeoutSeconds", s"--build_event_json_file=$bep") ++
      (if (cached) Nil else Seq("--nocache_test_results")) ++ targets
    val (code, _) = bazel("test", args, timeoutSeconds + 900)
    val events = BuildEvents.read(bep)
    Files.deleteIfExists(bep)
    val statuses = events.statuses
    val failed = events.attempts.filter(a => a.status == "FAILED" || a.status == "TIMEOUT").groupBy(_.label).map {
      case (label, as) => label -> as.flatMap(_.xml).flatMap(BuildEvents.failedTests).distinct
    }
    // Stopped past its wall time: what did not finish ran too long.
    val all = if (code == -1) targets.map(t => t -> statuses.getOrElse(t, "TIMEOUT")).toMap else statuses
    TestRun(all, failed)
  }

  /** Each target's longest attempt in one uncached run, in milliseconds;
    * fails unless every target passes. */
  def baseline(targets: Seq[String], flags: Seq[String]): Map[String, Long] = {
    val bep = logs.resolve("baseline-bep.json")
    val (code, out) = bazel("test", common ++ flags ++ Seq("--nocache_test_results", s"--build_event_json_file=$bep") ++ targets, 7200)
    val events = BuildEvents.read(bep)
    val bad = targets.filter(t => !events.statuses.get(t).contains("PASSED"))
    if (code != 0 || bad.nonEmpty)
      throw new IllegalStateException(s"the baseline is not green (exit $code): ${bad.mkString(" ")}; see $out")
    events.millis
  }

  /** Deletes what the disk cache gained after `since`: the mutants' outputs.
    * What the unmutated tree built stays. */
  def pruneCache(since: Instant): Unit = if (Files.isDirectory(diskCache)) {
    Using.resource(Files.walk(diskCache)) { paths =>
      paths.iterator.asScala.filter(Files.isRegularFile(_)).foreach { p =>
        val created = Files.readAttributes(p, classOf[BasicFileAttributes]).creationTime.toInstant
        if (created.isAfter(since)) { val _ = Files.deleteIfExists(p) }
      }
    }
  }
}

/** A campaign over a list of mutants in a worktree: the stages of each file,
  * a baseline that sets each stage's timeout, then each mutant through the
  * [[Runner]], its result appended to the results file as it is decided. A
  * campaign stopped by a signal puts back the file of the mutant in progress
  * and stops its Bazel command, and can be run again: mutants already in the
  * results file are skipped. */
object Campaign {
  /** The suites a mutant meets in the second stage, after its own package's
    * tests: the engine's end-to-end tests, the SQL logic tests and the job
    * runner's tests. */
  val SecondStage: Vector[String] = Vector(
    "//src/test/scala/com/transformer/sql/exec:sql_engine_test",
    "//src/test/scala/com/transformer/slt:slt_test")
  val SecondStagePackages: Vector[String] = Vector("//src/test/scala/com/transformer/job:")

  /** The least timeout a stage gets, in seconds, however fast its baseline. */
  val MinTimeoutSeconds = 60L

  final case class Plan(libraries: Vector[String], stages: Vector[Stage])

  /** The tags whose tests a campaign leaves out, as the default suite does. */
  val ExcludedTags = "manual|fuzz|perf"

  /** The stages of `file`'s mutants, before timeouts are known: its own
    * package's tests (the package the file is in, or its twin under
    * `src/test`); the second-stage suites; every other test that depends on
    * it. Tests with a tag `excludeTags` matches are left out. With `sharded`, a
    * fourth stage runs the first two again under `--config=sharded`. */
  def plan(bazel: BazelExecutor, file: String, sharded: Boolean, excludeTags: String): Plan = {
    val label = bazel.query(file).headOption.getOrElse(throw new IllegalArgumentException(s"no Bazel label for $file"))
    val libraries = bazel.query(s"rdeps(//..., $label, 1) except $label")
    val tests = bazel.query(s"kind(test, rdeps(//..., $label)) except attr(tags, '\\b($excludeTags)\\b', //...)")
    val pkg = label.takeWhile(_ != ':')
    val twin = pkg.replace("//src/main/", "//src/test/")
    val own = tests.filter(t => t.startsWith(pkg + ":") || t.startsWith(twin + ":"))
    val second = tests.filter(t => !own.contains(t) && (SecondStage.contains(t) || SecondStagePackages.exists(t.startsWith)))
    val rest = tests.filterNot(t => own.contains(t) || second.contains(t))
    val stages = Vector(
      Stage(1, "own package", own, Vector.empty, 0),
      Stage(2, "engine, slt and job", second, Vector.empty, 0),
      Stage(3, "the rest", rest, Vector.empty, 0)) ++
      (if (sharded) Vector(Stage(4, "sharded", own ++ second, Vector("--config=sharded"), 0)) else Vector.empty)
    Plan(libraries, stages.filter(_.targets.nonEmpty))
  }

  /** Runs `mutants` in `repo`, appending to `out/results.tsv`. Files
    * `sharded` holds get the sharded stage. */
  def run(
      repo: Path,
      outputBase: Path,
      diskCache: Path,
      out: Path,
      mutants: Vector[Mutant],
      sharded: String => Boolean,
      excludeTags: String): Unit = {
    val logs = out.resolve("logs")
    Files.createDirectories(logs)
    val progress = out.resolve("progress.log")
    def log(line: String): Unit = {
      val stamped = s"${Instant.now()} $line\n"
      print(stamped)
      val _ = Files.writeString(progress, stamped, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
    }
    val bazel = new BazelExecutor(repo, outputBase, diskCache, logs, log)
    val status = new ProcessBuilder("git", "status", "--porcelain").directory(repo.toFile).redirectErrorStream(true).start()
    val dirty = new String(status.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
    if (dirty.nonEmpty) throw new IllegalStateException(s"$repo is not clean:\n$dirty")

    val files = mutants.map(_.file).distinct
    val plans = files.map(f => f -> plan(bazel, f, sharded(f), excludeTags)).toMap
    files.foreach { f =>
      log(s"$f: libraries ${plans(f).libraries.mkString(" ")}")
      plans(f).stages.foreach(s => log(s"$f: stage ${s.number} ${s.name}: ${s.targets.mkString(" ")} ${s.flags.mkString(" ")}"))
    }

    val baselineFile = out.resolve("baseline.tsv")
    val millis: Map[String, Long] =
      if (Files.exists(baselineFile))
        Files.readAllLines(baselineFile).asScala.map(_.split("\t")).collect { case Array(k, v) => k -> v.toLong }.toMap
      else {
        val byFlags = plans.values.flatMap(_.stages).groupBy(_.flags)
        val measured = byFlags.toSeq.flatMap { case (flags, stages) =>
          val targets = stages.flatMap(_.targets).toVector.distinct.sorted
          log(s"baseline ${flags.mkString(" ")}: ${targets.size} targets")
          bazel.baseline(targets, flags).toSeq.map { case (t, ms) => (flags.mkString(" ") + "|" + t) -> ms }
        }.toMap
        val _ = Files.writeString(baselineFile, measured.toSeq.sorted.map { case (k, v) => s"$k\t$v" }.mkString("", "\n", "\n"))
        measured
      }
    def timed(stage: Stage): Stage = {
      val slowest = stage.targets.map(t => millis.getOrElse(stage.flags.mkString(" ") + "|" + t, 0L)).max
      stage.copy(timeoutSeconds = math.max(MinTimeoutSeconds, (3 * slowest + 999) / 1000))
    }

    val resultsFile = out.resolve("results.tsv")
    if (!Files.exists(resultsFile)) { val _ = Files.writeString(resultsFile, Result.Header + "\n") }
    val done = Sample.readTsv(resultsFile).map(_("id")).toSet
    val cutoff = Instant.now()
    val runner = new Runner(new DirectoryWorkspace(repo), bazel, log)
    // Stopped (SIGTERM, Ctrl-C) mid-mutant: the file goes back, and so the
    // worktree is clean for the run that resumes this one.
    Runtime.getRuntime.addShutdownHook(new Thread(() => { runner.restore(); bazel.stop() }))
    mutants.filterNot(m => done(m.id)).iterator.takeWhile(_ => !runner.isStopped).foreach { m =>
      log(s"${m.id} ${m.file} ${m.site.describe}")
      val p = plans(m.file)
      val r = runner.run(m, p.libraries, p.stages.map(timed))
      // A mutant the stop cut short has no outcome: it runs again on resuming.
      if (!runner.isStopped) {
        log(s"${m.id} ${r.outcome} ${if (r.target.nonEmpty) s"by ${r.target} at stage ${r.stage}" else ""} in ${r.seconds}s ${r.note}")
        val _ = Files.writeString(resultsFile, Result.row(r) + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND)
        bazel.pruneCache(cutoff)
      }
    }
  }
}
