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
  *     <dir-or-file>... [--decimal p,s] [--dialect sqlite|transformer|cockroach|datafusion|risingwave] \
  *     [--modes all|none|<name>,...] \
  *     [--threads N] [--report <file> [--list-refusals]] [--memory]
  *   bazel run //src/test/scala/com/transformer/slt:corpus -- \
  *     --dialect transformer --complete <script>...
  * }}}
  *
  * Prints one summary line per script and a total; `--report` writes every
  * record that did not pass (refusals tallied by message, failures,
  * divergences and stale skips in full with their SQL) and the skips tallied
  * by their rule. A script's conditions on transformer cite the ledger in the
  * workspace's `docs/gotchas.md` ([[Ledger]]). `--modes none` runs the
  * baseline only, which is how a first pass over the whole corpus stays
  * affordable.
  * `--memory` prints the heap a script left live, after a collection, to
  * find a leak across scripts. `--keep-going` carries on past a statement
  * that fails, and `--problems <file>` writes each record that did not pass,
  * with the engine's own answer, a JSON line each: how a file of CockroachDB's
  * or DataFusion's is vendored. `--decimal p,s` (before `--dialect`) is the
  * unconstrained DECIMAL of a script whose header does not say, for an audit
  * of a corpus as its upstream holds it.
  * `--complete` rewrites each script with the engine's results in its `----`
  * blocks ([[Runner.complete]]), for writing a new script.
  */
object Corpus {

  def main(args: Array[String]): Unit = {
    lazy val ledger = Ledger.load(resolve(Ledger.DefaultPath.toString))
    var dialect: Dialect = Dialect.Sqlite
    var modes = Modes.Verification
    var threads = 1
    var report: Option[Path] = None
    var listRefusals = false
    var memory = false
    var problems: Option[Path] = None
    // What an unconstrained DECIMAL is in a script whose header does not say,
    // for an audit of a corpus as its upstream holds it; given before
    // --dialect.
    var decimal: Option[com.transformer.core.DataType.DecimalType] = None
    var keepGoing = false
    val inputs = Seq.newBuilder[Path]
    var i = 0
    while (i < args.length) {
      args(i) match {
        case "--dialect" =>
          i += 1
          dialect = args(i) match {
            case "sqlite" => Dialect.Sqlite
            case "transformer" => Dialect.Transformer
            case "cockroach" => Dialect.Cockroach(decimal)
            case "datafusion" => Dialect.DataFusion(decimal)
            case "risingwave" => Dialect.RisingWave(decimal)
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
          val runner = new Runner(dialect, ledger, Nil)
          args.drop(i + 1).foreach { f =>
            val path = resolve(f)
            val (text, problems) = runner.complete(f, path)
            Files.write(path, text.getBytes(StandardCharsets.UTF_8))
            problems.foreach(p => System.out.println(p))
          }
          return
        case "--threads" => i += 1; threads = args(i).toInt
        case "--report" => i += 1; report = Some(resolve(args(i)))
        case "--list-refusals" => listRefusals = true
        case "--memory" => memory = true
        case "--problems" => i += 1; problems = Some(resolve(args(i)))
        case "--keep-going" => keepGoing = true
        case "--decimal" =>
          i += 1
          val Array(p, sc) = args(i).split(",").map(_.trim.toInt)
          decimal = Some(com.transformer.core.DataType.DecimalType(p, sc))
        case path => inputs += resolve(path)
      }
      i += 1
    }
    val files = inputs.result().flatMap { p =>
      if (Files.isDirectory(p)) {
        val walk = Files.walk(p)
        try walk.iterator.asScala.filter(f => Files.isRegularFile(f) && isScript(f, dialect)).toVector.sortBy(_.toString)
        finally walk.close()
      } else Seq(p)
    }
    val problemLog = problems.map(p => new PrintWriter(Files.newBufferedWriter(p, StandardCharsets.UTF_8)))
    val runner = new Runner(dialect, ledger, modes,
      if (problemLog.isDefined || keepGoing) Some(new ProblemLog(keepGoing)) else None)
    val pool = Executors.newFixedThreadPool(threads)
    val started = System.nanoTime()
    val futures = files.map { f =>
      pool.submit(new Callable[CompactResult] {
        def call(): CompactResult = {
          val t0 = System.nanoTime()
          val r =
            try runner.run(f.toString, f)
            catch { case e: Throwable => FileResult(f.toString, Vector.empty, Some("the runner failed: " + describe(e))) }
          val ms = (System.nanoTime() - t0) / 1000000
          val heap = if (!memory) "" else {
            System.gc()
            val rt = Runtime.getRuntime
            s" [heap ${(rt.totalMemory - rt.freeMemory) / (1024 * 1024)} MB]"
          }
          System.out.println(s"${r.summary} [${ms} ms]$heap")
          problemLog.foreach { w =>
            val seen = ProblemLog.seen.get
            w.synchronized {
              r.results.foreach { case RecordResult(rec, o) =>
                if (o match { case _: Failed | _: Refused | _: Diverged | _: Stale => true; case _ => false })
                  w.println(ProblemLog.json(r.file, rec, o, Option(seen.get(rec)), dialect.format))
              }
              w.flush()
            }
            seen.clear()
          }
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
      s"${n("skipped")} skipped, ${n("stale")} stale; ${results.count(_.stoppedAt.isDefined)} files stopped early")
    report.foreach(p => writeReport(p, results, listRefusals))
    problemLog.foreach(_.close())
  }

  /** The runner's audit for `--problems`: what the engine did with each
    * record, kept per thread's script, and `--keep-going`. */
  private final class ProblemLog(val keepGoing: Boolean) extends Runner.Audit {
    def observe(rec: Script.Record, result: Either[Throwable, (com.transformer.core.Schema, Vector[Array[Any]])]): Unit =
      ProblemLog.seen.get.put(rec, result)
  }

  private object ProblemLog {
    val seen: ThreadLocal[java.util.IdentityHashMap[Script.Record, Either[Throwable, (com.transformer.core.Schema, Vector[Array[Any]])]]] =
      ThreadLocal.withInitial(() => new java.util.IdentityHashMap())

    private def str(s: String): String = {
      val sb = new StringBuilder("\"")
      s.foreach {
        case '"' => sb ++= "\\\""
        case '\\' => sb ++= "\\\\"
        case '\n' => sb ++= "\\n"
        case '\t' => sb ++= "\\t"
        case '\r' => sb ++= "\\r"
        case c if c < ' ' => sb ++= f"\\u${c.toInt}%04x"
        case c => sb += c
      }
      sb += '"'
      sb.result()
    }

    /** One problem record as a JSON line: where it is, what went wrong, its
      * SQL, and the engine's own answer to it in the baseline (its error, or
      * its column names, types and rows as the format prints them). */
    def json(file: String, rec: Script.Record, o: Outcome,
        seen: Option[Either[Throwable, (com.transformer.core.Schema, Vector[Array[Any]])]], format: Script.Format): String = {
      val (kind, detail) = o match {
        case Failed(d) => ("failed", d)
        case Refused(m) => ("refused", m)
        case Diverged(m, d) => ("diverged", s"$m: $d")
        case Stale(_, d) => ("stale", d)
        case other => ("other", other.toString)
      }
      val sql = Script.sqlOf(rec).getOrElse("")
      val (stmt, expectError) = rec match {
        case s: Script.Statement => (true, s.expectError)
        case _ => (false, false)
      }
      val engine = seen match {
        case None => "null"
        case Some(Left(e)) => s"{\"error\":${str(Option(e.getMessage).getOrElse(e.getClass.getName))}}"
        case Some(Right((schema, rows))) =>
          val names = schema.fields.map(f => str(f.name)).mkString("[", ",", "]")
          val types = schema.fields.map(f => str(Script.typeName(f.dataType))).mkString("[", ",", "]")
          val printed = rows.map(r => schema.fields.indices.map(c => str(Values.upstream(r(c), format))).mkString("[", ",", "]")).mkString("[", ",", "]")
          s"{\"names\":$names,\"types\":$types,\"rows\":$printed}"
      }
      s"{\"file\":${str(file)},\"line\":${rec.line},\"kind\":\"$kind\",\"detail\":${str(detail)},\"sql\":${str(sql)}," +
        s"\"statement\":$stmt,\"expectError\":$expectError,\"engine\":$engine}"
    }
  }

  /** Whether `f`, a file under a directory given, is a script of `dialect`:
    * a `.test` file, and in an upstream checkout of CockroachDB's logic tests
    * any file without a dot, in DataFusion's an `.slt` file. */
  private def isScript(f: Path, dialect: Dialect): Boolean = {
    val name = f.getFileName.toString
    name.endsWith(".test") || (dialect.format match {
      case Script.Format.Cockroach => !name.contains('.')
      case Script.Format.DataFusion | Script.Format.RisingWave => name.endsWith(".slt")
      case Script.Format.Sqlite => false
    })
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
      w.println("== skips by rule")
      results.flatMap(_.skipTally).groupMapReduce(_._1)(_._2)(_ + _).toSeq.sortBy { case (id, n) => (-n, id) }
        .foreach { case (id, n) => w.println(f"$n%8d  $id") }
      w.println()
      w.println("== files stopped early")
      results.filter(_.stoppedAt.isDefined).foreach(r => w.println(s"${r.file}: ${r.stoppedAt.get}"))
      w.println()
      for ((label, pick) <- Seq[(String, Outcome => Boolean)](
          "failed" -> (_.isInstanceOf[Failed]), "diverged" -> (_.isInstanceOf[Diverged]),
          "stale skips" -> (_.isInstanceOf[Stale]))) {
        w.println(s"== $label")
        results.foreach { fr =>
          fr.kept.filter(r => pick(r.outcome)).foreach { r =>
            w.println(s"${fr.file}:${r.record.line}: ${show(r.outcome)}")
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
