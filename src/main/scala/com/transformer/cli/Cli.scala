package com.transformer.cli

import com.transformer.job.{DirectoryJobLoader, TaskProgressListener, TaskResult, TaskStatus}
import com.transformer.sql.exec.SqlEngine
import com.transformer.temporal.TemporalVariables

import java.io.PrintStream
import java.nio.file.{Files, Path, Paths}
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import scala.util.control.NonFatal

/** The `transformer` command line, minus the GUI. `TransformerMain` hands
  * `gui` to JavaFX and everything else here, so this object needs no toolkit
  * on its classpath and a test can drive it directly.
  *
  * Exit codes: 0 when every task succeeded, 1 when the run finished with any
  * task not Succeeded (a failed validation counts) or the job could not be
  * loaded, 2 for a usage error. A scheduler such as Airflow or an agent
  * harness needs nothing beyond the exit code to tell a good run from a bad
  * one; the per-task lines and `<output>/job.json` say which task went wrong.
  */
object Cli {

  sealed trait Command
  object Command {
    final case class Run(jobDir: Path, outputDir: Option[String], executionTime: Instant) extends Command
    final case class Gui(jobDir: Option[Path]) extends Command
    case object Help extends Command
  }

  val ExitOk = 0
  val ExitRunFailed = 1
  val ExitUsage = 2

  val Usage: String =
    """Usage:
      |  transformer run <jobDir> [--output <dir>] [--execution-time <instant>]
      |  transformer gui [jobDir]
      |  transformer help
      |
      |run   Load the job directory and run every task. Exits 0 when all tasks
      |      succeed, 1 when any task fails, fails a validation, or is skipped.
      |        --output <dir>             Where outputs and job.json go. Templated,
      |                                   e.g. /data/out/day={{ today }}.
      |                                   Default: <jobDir>/output
      |        --execution-time <instant> ISO-8601 instant the templates resolve
      |                                   against, e.g. 2026-01-01T00:00:00Z.
      |                                   Default: now
      |gui   Open the desktop GUI, optionally on a job directory.
      |""".stripMargin

  /** Parse `args` into a command, or a message saying what is wrong with them. */
  def parse(args: Seq[String], now: => Instant = Instant.now()): Either[String, Command] =
    args.toList match {
      case Nil | ("help" | "--help" | "-h") :: Nil => Right(Command.Help)
      case "gui" :: Nil => Right(Command.Gui(None))
      case "gui" :: dir :: Nil => Right(Command.Gui(Some(Paths.get(dir))))
      case "gui" :: _ => Left("gui takes at most one argument, the job directory")
      case "run" :: rest => parseRun(rest, now)
      case other :: _ => Left(s"unknown command '$other'")
    }

  private def parseRun(args: List[String], now: => Instant): Either[String, Command] = {
    def loop(
        rest: List[String],
        jobDir: Option[String],
        output: Option[String],
        time: Option[Instant]
    ): Either[String, Command] = rest match {
      case Nil =>
        jobDir match {
          case None => Left("run needs a job directory")
          case Some(d) =>
            Right(Command.Run(Paths.get(d), output, time.getOrElse(now.truncatedTo(ChronoUnit.SECONDS))))
        }
      case "--output" :: value :: tail if output.isEmpty =>
        loop(tail, jobDir, Some(value), time)
      case "--execution-time" :: value :: tail if time.isEmpty =>
        try loop(tail, jobDir, output, Some(Instant.parse(value)))
        catch {
          case _: DateTimeParseException =>
            Left(s"--execution-time '$value' is not an ISO-8601 instant such as 2026-01-01T00:00:00Z")
        }
      case ("--output" | "--execution-time") :: tail if tail.isEmpty =>
        Left(s"${rest.head} needs a value")
      case ("--output" | "--execution-time") :: _ =>
        Left(s"${rest.head} given twice")
      case flag :: _ if flag.startsWith("-") =>
        Left(s"unknown option '$flag'")
      case dir :: tail if jobDir.isEmpty =>
        loop(tail, Some(dir), output, time)
      case extra :: _ =>
        Left(s"unexpected argument '$extra'; run takes one job directory")
    }
    loop(args, None, None, None)
  }

  /** Load and run the job `cmd` names, printing progress to `out` and the
    * failure reasons to `err`. Returns the exit code. */
  def run(cmd: Command.Run, out: PrintStream, err: PrintStream): Int = {
    if (!Files.isDirectory(cmd.jobDir)) {
      err.println(s"Job directory not found: ${cmd.jobDir}")
      return ExitRunFailed
    }
    val job =
      try {
        DirectoryJobLoader.load(
          jobDir = cmd.jobDir,
          outputDir = cmd.outputDir,
          temporalVariables = Some(TemporalVariables(cmd.executionTime))
        )
      } catch {
        case NonFatal(e) =>
          err.println(s"Could not load job ${cmd.jobDir}: ${e.getMessage}")
          return ExitRunFailed
      }

    out.println(s"Job:            ${cmd.jobDir.toAbsolutePath.normalize()}")
    out.println(s"Execution time: ${cmd.executionTime}")
    out.println(s"Inputs:         ${job.inputs.size}")
    out.println(s"Tables:         ${job.sql.size}")
    out.println()

    // Worker threads finish tasks concurrently; one lock keeps lines whole.
    val listener = new TaskProgressListener {
      def onTaskStart(taskIndex: Int, taskName: String): Unit = ()
      def onTaskFinish(taskIndex: Int, result: TaskResult): Unit =
        out.synchronized(out.println(describe(result)))
    }
    val result = job.run(SqlEngine, listener)

    out.println()
    result.warnings.foreach(w => out.println(s"warning: $w"))
    val counts = result.tasks.groupBy(t => statusName(t.status)).map { case (k, v) => s"${v.size} $k" }
    out.println(s"Job ${if (result.succeeded) "SUCCEEDED" else "FAILED"} (${counts.toSeq.sorted.mkString(", ")})")
    result.error.foreach(e => err.println(s"Error: $e"))
    if (result.succeeded) ExitOk else ExitRunFailed
  }

  private def statusName(s: TaskStatus): String = s match {
    case TaskStatus.Pending => "Pending"
    case TaskStatus.Succeeded => "Succeeded"
    case _: TaskStatus.Failed => "Failed"
    case _: TaskStatus.ValidationFailed => "ValidationFailed"
    case _: TaskStatus.Skipped => "Skipped"
  }

  private def describe(t: TaskResult): String = {
    val head = "  %-16s %-28s rows=%-10d %6d ms".formatLocal(Locale.ROOT,
      statusName(t.status), t.taskName, t.rowsProduced, t.durationMillis)
    t.status match {
      case TaskStatus.Failed(reason) => s"$head\n    $reason"
      case TaskStatus.Skipped(reason) => s"$head\n    $reason"
      case TaskStatus.ValidationFailed(fs) =>
        (head +: fs.map(f => s"    validation '${f.validationName}' returned ${f.rowCount} row(s)")).mkString("\n")
      case _ => head
    }
  }
}
