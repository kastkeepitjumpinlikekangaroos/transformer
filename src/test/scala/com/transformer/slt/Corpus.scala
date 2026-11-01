package com.transformer.slt

import com.transformer.slt.Runner._

import java.io.PrintWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.concurrent.{Callable, Executors, TimeUnit}
import scala.jdk.CollectionConverters._

/** Runs a directory of sqllogictest scripts (SQLite's corpus, say) and
  * reports what happened to every record, for an audit rather than a gate:
  * nothing here fails a build.
  *
  * {{{
  *   bazel run //src/test/scala/com/transformer/slt:corpus -- \
  *     <dir-or-file>... [--dialect sqlite|transformer] [--modes all|none|<name>,...] \
  *     [--threads N] [--report <file> [--list-refusals]] [--memory]
  *   bazel run //src/test/scala/com/transformer/slt:corpus -- \
  *     --dialect transformer --complete <script>...
  * }}}
  *
  * Prints one summary line per script and a total; `--report` writes every
  * record that did not pass (refusals tallied by message, failures and
  * divergences in full with their SQL). `--modes none` runs the baseline only,
  * which is how a first pass over the whole corpus stays affordable.
  * `--memory` prints the heap a script left live, after a collection, to
  * find a leak across scripts.
  * `--complete` rewrites each script with the engine's results in its `----`
  * blocks ([[Runner.complete]]), for writing a new script.
  */
object Corpus {

  def main(args: Array[String]): Unit = {
    var dialect: Dialect = Dialect.Sqlite
    var modes = Modes.Verification
    var threads = 1
    var report: Option[Path] = None
    var listRefusals = false
    var memory = false
    val inputs = Seq.newBuilder[Path]
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--dialect" =>
          i += 1
          dialect = args(i) match {
            case "sqlite" => Dialect.Sqlite
            case "transformer" => Dialect.Transformer
            case other => throw new IllegalArgumentException(s"unknown dialect $other")
          }
        case "--modes" =>
          i += 1
          modes = args(i) match {
            case "all" => Modes.Verification
            case "none" => Nil
            case names =>
              val wanted = names.split(",").toSet
              val chosen = Modes.Verification.filter(m => wanted(m.name))
              if (chosen.size != wanted.size)
                throw new IllegalArgumentException(s"modes are: ${Modes.Verification.map(_.name).mkString(", ")}")
              chosen
          }
        case "--complete" =>
          // Write each script given after this flag back with the engine's
          // results filled in, and stop.
          val runner = new Runner(dialect, Nil)
          args.drop(i + 1).foreach { f =>
            val path = resolve(f)
            val (text, problems) = runner.complete(f, new String(Files.readAllBytes(path), StandardCharsets.UTF_8))
            Files.write(path, text.getBytes(StandardCharsets.UTF_8))
            problems.foreach(p => System.out.println(p))
          }
          return
        case "--threads" => i += 1; threads = args(i).toInt
        case "--report" => i += 1; report = Some(resolve(args(i)))
        case "--list-refusals" => listRefusals = true
        case "--memory" => memory = true
        case path => inputs += resolve(path)
      }
      i += 1
    }
    val files = inputs.result().flatMap { p =>
      if (Files.isDirectory(p)) {
        val walk = Files.walk(p)
        try walk.iterator.asScala.filter(f => f.toString.endsWith(".test")).toVector.sortBy(_.toString)
        finally walk.close()
      } else Seq(p)
    }
    val runner = new Runner(dialect, modes)
    val pool = Executors.newFixedThreadPool(threads)
    val started = System.nanoTime()
    val futures = files.map { f =>
      pool.submit(new Callable[CompactResult] {
        def call(): CompactResult = {
          val t0 = System.nanoTime()
          val r =
            try runner.run(f.toString, new String(Files.readAllBytes(f), StandardCharsets.UTF_8))
            catch { case e: Throwable => FileResult(f.toString, Vector.empty, Some("the runner failed: " + describe(e))) }
          val ms = (System.nanoTime() - t0) / 1000000
          val heap = if (!memory) "" else {
            System.gc()
            val rt = Runtime.getRuntime
            s" [heap ${(rt.totalMemory - rt.freeMemory) / (1024 * 1024)} MB]"
          }
          System.out.println(s"${r.summary} [${ms} ms]$heap")
          // Keep what the totals and the report read, not the record of every
          // query that passed: the whole corpus is millions of records.
          r.compact(keepRefusals = listRefusals)
        }
      })
    }
    val results = futures.map(_.get())
    pool.shutdown()
    pool.awaitTermination(1, TimeUnit.MINUTES)
    val secs = (System.nanoTime() - started) / 1e9
    def n(kind: String) = results.map(_.counts.getOrElse(kind, 0)).sum
    System.out.println(f"TOTAL ${files.length} files, ${results.map(_.counts.values.sum).sum} records in $secs%.1f s: " +
      s"${n("passed")} passed, ${n("refused")} refused, ${n("failed")} failed, ${n("diverged")} diverged, " +
      s"${n("skipped")} skipped; ${results.count(_.stoppedAt.isDefined)} files stopped early")
    report.foreach(p => writeReport(p, results, listRefusals))
  }

  /** A path given on the command line: under `bazel run` a relative one is
    * relative to the workspace, not the runfiles directory it runs in. */
  private def resolve(p: String): Path = {
    val path = Paths.get(p)
    if (path.isAbsolute) path
    else Option(System.getenv("BUILD_WORKSPACE_DIRECTORY")).map(Paths.get(_).resolve(path)).getOrElse(path)
  }

  private def writeReport(path: Path, results: Seq[CompactResult], listRefusals: Boolean): Unit = {
    val w = new PrintWriter(Files.newBufferedWriter(path, StandardCharsets.UTF_8))
    try {
      val tally = results.flatMap(_.refusalTally).groupMapReduce(_._1)(_._2)(_ + _)
      w.println("== refusals by message")
      tally.toSeq.sortBy(-_._2).foreach { case (m, n) => w.println(f"$n%8d  $m") }
      w.println()
      if (listRefusals) {
        w.println("== refused records")
        results.foreach { fr =>
          fr.kept.foreach {
            case RecordResult(rec, Refused(m)) => w.println(s"${fr.file}:${rec.line}: ${m.split("\n").head.take(200)}")
            case _ => ()
          }
        }
        w.println()
      }
      w.println("== files stopped early")
      results.filter(_.stoppedAt.isDefined).foreach(r => w.println(s"${r.file}: ${r.stoppedAt.get}"))
      w.println()
      for ((label, pick) <- Seq[(String, Outcome => Boolean)](
          "failed" -> (_.isInstanceOf[Failed]), "diverged" -> (_.isInstanceOf[Diverged]))) {
        w.println(s"== $label")
        results.foreach { fr =>
          fr.kept.filter(r => pick(r.outcome)).foreach { r =>
            w.println(s"${fr.file}:${r.record.line}: ${r.outcome}")
            r.record match {
              case q: Script.Query => w.println("  " + q.sql.replace("\n", "\n  "))
              case s: Script.Statement => w.println("  " + s.sql.replace("\n", "\n  "))
              case _ => ()
            }
          }
        }
        w.println()
      }
    } finally w.close()
  }

  /** A refusal's message with its literals and names blurred, so refusals of
    * one kind tally together. */
  def normalizeMessage(m: String): String =
    m.split("\n").head.take(160)
      .replaceAll("'[^']*'", "'_'")
      .replaceAll("\\b\\d+\\b", "N")
}
